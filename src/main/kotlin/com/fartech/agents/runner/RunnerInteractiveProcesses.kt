package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Official 261b43bb interactive IPC. No ProcessBuilder, launch/input replay, or unowned PID kill. */
class RunnerInteractiveProcesses(
    private val ipc: RunnerIpc,
    private val directory: Path,
    private val gate: RunnerEnvironmentGate,
    private val secrets: RunnerSecrets,
    private val ownerJob: Job? = null
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val retired = LinkedHashMap<String, Session>()
    private val invalidation = gate.onInvalidated { close() }
    @Synchronized private fun retire(session: Session) {
        sessions.remove(session.requestId, session)
        retired[session.requestId] = session
        while (retired.size > 64) retired.remove(retired.keys.first())
    }
    @Synchronized private fun find(id: String): Session? = sessions[id] ?: retired[id]
    suspend fun open(scope: WorkflowHostScope, request: SubprocessExecutor.ExecRequest): WorkflowInteractiveProcess {
        gate.check(); require(scope.rootExecutionId == ipc.binding.string("executionId")) { "Interactive scope mismatch" }
        require(request.timeoutSeconds in 1..86400 && sessions.size < 16) { "Interactive capacity/timeout unsupported" }
        val cwd = request.workingDir.toPath().toAbsolutePath().normalize()
        require(cwd.startsWith(directory.toAbsolutePath().normalize())) { "Interactive cwd outside attempt" }
        val relative = directory.toAbsolutePath().normalize().relativize(cwd).toString().ifBlank { "." }
        if (relative != ".") confinedPath(directory, relative)
        val prepared = with(request) { environmentAtStart?.let { copy(env = env + it(), environmentAtStart = null) } ?: this }
        require(!secrets.containsSecret(JsonArray(prepared.command.map(::JsonPrimitive)))) { "Secret in interactive command line" }
        val requestId = UUID.randomUUID().toString()
        val session = Session(requestId, ownerJob ?: com.fartech.agents.workflow.WorkflowHostContext.job ?: currentCoroutineContext()[Job], request.timeoutSeconds)
        check(sessions.putIfAbsent(requestId, session) == null)
        try {
            val started = ipc.request("processOpen", buildJsonObject {
                put("requestId", requestId); put("argv", JsonArray(prepared.command.map(::JsonPrimitive)))
                put("relativeCwd", relative); put("environment", JsonObject(prepared.env.mapValues { JsonPrimitive(it.value) }))
                put("purpose", prepared.admissionKind); put("timeoutSeconds", prepared.timeoutSeconds)
                put("maxOutputBytes", 16 * 1024 * 1024); put("maxPendingBytes", 1024 * 1024); put("channelEncoding", "base64")
            }, "processStarted")
            require(started.string("requestId") == requestId && started.long("pid") > 0 && started.long("processGroupId") > 0) { "Interactive registration mismatch" }
            session.start(started.string("registrationId"))
            return session
        } catch (e: Exception) { session.fail(); sessions.remove(requestId); throw e }
    }
    fun output(payload: JsonObject) { RunnerProtocol().validate(payload, "processOutput"); val session = find(payload.string("requestId")) ?: throw RunnerProtocolException(); session.output(payload) }
    fun closed(payload: JsonObject) { RunnerProtocol().validate(payload, "processClosed"); val session = find(payload.string("requestId")) ?: return; session.closed(payload) }
    override fun close() { sessions.values.forEach { it.fail() }; sessions.clear(); synchronized(this) { retired.values.forEach { it.fail() }; retired.clear() } }

    private inner class Session(val requestId: String, val owner: Job?, val timeout: Long) : WorkflowInteractiveProcess {
        private val registration = CompletableDeferred<String>()
        override val registrationId: String get() = registration.getCompleted()
        private val stdoutPipe = RunnerBytePipe(); private val stderrPipe = RunnerBytePipe()
        override val stdout get() = stdoutPipe.input
        override val stderr get() = stderrPipe.input
        private val exited = CompletableDeferred<Int>()
        private val stopped = AtomicBoolean()
        private val localReadersClosed = AtomicBoolean()
        private var outputSeq = 0L; private var acknowledged = 0L; private var inputSeq = 0L
        private var totalOutput = 0L; private var inputClosed = false
        private var observedRegistration: String? = null
        private val outputDigests = HashMap<Long, String>()
        private val consumed = sortedSetOf<Long>()
        private val inputLock = Any()
        private var closedBeforeStart: JsonObject? = null
        private var confirmedClose: JsonObject? = null
        private val pendingBeforeStart = java.util.ArrayDeque<JsonObject>()
        @Synchronized fun start(id: String) {
            require(observedRegistration == null || observedRegistration == id) { "Interactive registration changed" }
            observedRegistration = id; registration.complete(id)
            while (pendingBeforeStart.isNotEmpty()) output(pendingBeforeStart.removeFirst())
            closedBeforeStart?.let { closedBeforeStart = null; closed(it) }
        }
        @Synchronized fun output(payload: JsonObject) {
            val id = payload.string("registrationId")
            if (!registration.isCompleted) {
                require(pendingBeforeStart.size < 64 && (observedRegistration == null || observedRegistration == id)) { "Interactive start/output race overflow" }
                observedRegistration = id; pendingBeforeStart.addLast(payload); return
            }
            require(id == registrationId) { "Output belongs to another process" }
            val sequence = payload.long("outputSeq")
            val bytes = Base64.getDecoder().decode(payload.string("dataBase64"))
            require(bytes.size <= 32768 && bytes.size.toLong() == payload.long("decodedBytes") && sha256(bytes) == payload.string("dataHash")) { "Interactive output bytes/hash mismatch" }
            val digest = sha256(canonical(payload).toByteArray())
            outputDigests[sequence]?.let { require(it == digest) { "Changed duplicate output" }; acknowledge(); return }
            require(!stopped.get()) { "New output after process close" }
            require(sequence == outputSeq + 1 && sequence - acknowledged <= 64 && totalOutput + bytes.size <= 16 * 1024 * 1024) { "Interactive output gap/bounds" }
            outputDigests[sequence] = digest; outputSeq = sequence; totalOutput += bytes.size
            if (bytes.isEmpty() || localReadersClosed.get()) {
                // Grace-period output remains evidence, but never reopens released local readers.
                consumed(sequence); return
            }
            val pipe = when(payload.string("channel")) { "stdout" -> stdoutPipe; "stderr" -> stderrPipe; else -> throw RunnerProtocolException() }
            try { pipe.offer(bytes) { consumed(sequence) } } catch (e: Exception) { fail(); gate.invalidate("INTERACTIVE_OUTPUT_BACKPRESSURE"); throw e }
        }
        @Synchronized private fun consumed(sequence: Long) {
            if (sequence <= acknowledged) return // A reader callback may race local queue release.
            consumed.add(sequence)
            while (consumed.remove(acknowledged + 1)) acknowledged++
            acknowledge()
        }
        private fun acknowledge() { ipc.send("processOutputAck", buildJsonObject { put("requestId", requestId); put("registrationId", registrationId); put("ackOutputSeq", acknowledged) }) }
        @Synchronized fun closed(payload: JsonObject) {
            confirmedClose?.let { require(it == payload) { "Changed duplicate process close" }; return }
            if (!registration.isCompleted) { closedBeforeStart = payload; return }
            require(payload.string("registrationId") == (observedRegistration ?: payload.string("registrationId")) && payload.long("finalOutputSeq") == outputSeq) { "Interactive close/output sequence mismatch" }
            if (!registration.isCompleted) start(payload.string("registrationId"))
            require(payload["stopConfirmed"] == JsonPrimitive(true) && payload["unmanagedProcessDetected"] == JsonPrimitive(false) &&
                payload.array("registeredProcesses").all { it.jsonObject.string("state") == "EXITED" }) { "Interactive stop remains unconfirmed" }
            confirmedClose = payload
            stdoutPipe.eof(); stderrPipe.eof(); stopped.set(true)
            retire(this)
            exited.complete(payload["exitCode"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int ?: -1)
        }
        fun fail() { stopped.set(true); stdoutPipe.fail(); stderrPipe.fail(); registration.cancel(); exited.cancel(RunnerAuthorityLost()) }
        override val stdin: OutputStream = object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
                var position = offset
                while (position < offset + length) {
                    val end = minOf(position + 32768, offset + length)
                    sendInput(bytes.copyOfRange(position, end), false); position = end
                }
            }
            override fun close() { sendInput(ByteArray(0), true) }
        }
        private fun sendInput(bytes: ByteArray, eof: Boolean) = synchronized(inputLock) { runBlocking(owner ?: kotlin.coroutines.EmptyCoroutineContext) {
            // A gate probe may overlap normal remote exit. Decide the complete local lifecycle
            // only AFTER it returns, under the same monitor as closed(), never check-then-act.
            gate.check()
            val sequence = synchronized(this@Session) {
                if (eof && (inputClosed || confirmedClose != null)) { inputClosed = true; return@runBlocking }
                require(!stopped.get() && !localReadersClosed.get() && !inputClosed) { "Input after process/EOF close" }
                ++inputSeq
            }
            val id = registration.await()
            try {
                val ack = ipc.request("processInput", buildJsonObject {
                    put("requestId", requestId); put("registrationId", id); put("inputSeq", sequence); put("encoding", "base64")
                    put("dataBase64", Base64.getEncoder().encodeToString(bytes)); put("decodedBytes", bytes.size); put("dataHash", sha256(bytes)); put("eof", eof)
                }, "processInputAck")
                require(ack.string("requestId") == requestId && ack.string("registrationId") == id && ack.long("inputSeq") == sequence &&
                    ack.string("state") == "WRITTEN" && ack.long("writtenBytes") == bytes.size.toLong() && ack["eof"] == JsonPrimitive(eof)) { "Interactive input delivery uncertain" }
                if (eof) inputClosed = true
            } catch (_: Exception) { gate.invalidate("INTERACTIVE_INPUT_UNCERTAIN"); fail(); throw IOException("Interactive input is uncertain; replay prohibited") }
        }
        }
        override suspend fun awaitExit(): Int = withTimeout(timeout * 1000) { exited.await() }
        override fun close() {
            val requestRemoteStop = synchronized(this) {
                val first = localReadersClosed.compareAndSet(false, true)
                if (confirmedClose == null) {
                    stdoutPipe.fail(); stderrPipe.fail(); exited.cancel(RunnerAuthorityLost())
                    if (outputSeq > acknowledged) {
                        // fail() releases unread chunks without their callbacks. Settle that
                        // accepted prefix so grace-period output cannot stall behind discarded IO.
                        consumed.clear(); acknowledged = outputSeq
                        runCatching { acknowledge() }
                    }
                }
                // Reader cancellation is not remote termination. Keep registration/output fencing
                // until processClosed while TERM/grace can still emit legitimate output.
                first && !stopped.get() && registration.isCompleted && !registration.isCancelled
            }
            if (requestRemoteStop) runCatching {
                ipc.send("processCancel", buildJsonObject {
                    put("requestId", requestId); put("registrationId", requireNotNull(observedRegistration)); put("commandId", UUID.randomUUID().toString())
                    put("reason", "HOST_CHANNEL_CLOSED"); put("gracePeriodMs", 3000)
                })
            }
        }
    }
}
