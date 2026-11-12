package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.AgentOutputCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Launch requests go to the supervisor; this class never invokes ProcessBuilder. */
class RunnerProcesses(
    private val ipc: RunnerIpc,
    private val root: Path,
    private val gate: RunnerEnvironmentGate,
    private val interactive: RunnerInteractiveProcesses? = null,
    private val readArtifact: suspend (String, String) -> String
) : SubprocessExecutor {
    private val exits = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val received = ConcurrentHashMap<String, JsonObject>()
    private val registrations = ConcurrentHashMap.newKeySet<String>()
    fun exited(payload: JsonObject) {
        val id = payload.string("registrationId")
        if (received.size >= 128) throw RunnerProtocolException()
        val previous = received.putIfAbsent(id, payload)
        if (previous != null && previous != payload) throw RunnerProtocolException()
        exits[id]?.complete(payload)
    }
    override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
        require(request.admissionKind != "AGENT_SANDBOX") { "Sandbox requires dedicated noninteractive typed IPC" }
        ipc.businessFence.check(); gate.check()
        ipc.requireProcessTimeout(request.timeoutSeconds, request.admissionKind)
        if (interactive != null && (request.stdoutLineCallback != null || request.stdin?.toByteArray()?.size?.let { it > RunnerIpc.MAX_FRAME_BYTES / 2 } == true)) {
            return executeStreaming(request)
        }
        require(request.stdin?.toByteArray()?.size?.let { it <= RunnerIpc.MAX_FRAME_BYTES / 2 } != false) { "Stdin exceeds IPC budget" }
        val base = root.toAbsolutePath().normalize()
        val working = request.workingDir.toPath().toAbsolutePath().normalize()
        require(working.startsWith(base)) { "Runner cwd escapes execution directory" }
        val relative = base.relativize(working).toString().ifBlank { "." }
        if (relative != ".") confinedPath(base, relative)
        val prepared = request.preparedForStart()
        ipc.businessFence.check(); gate.check()
        val requestId = UUID.randomUUID().toString(); val startedAt = System.nanoTime()
        val started = ipc.requestProcessStart("processLaunch", buildJsonObject {
            put("requestId", requestId); put("argv", JsonArray(prepared.command.map(::JsonPrimitive)))
            put("relativeCwd", relative); put("environment", JsonObject(prepared.env.mapValues { JsonPrimitive(it.value) }))
            put("purpose", prepared.admissionKind); put("stdin", prepared.stdin?.let(::JsonPrimitive) ?: JsonNull)
            put("timeoutSeconds", request.timeoutSeconds); put("captureLimitBytes", RunnerIpc.MAX_FRAME_BYTES / 4)
        })
        require(started.string("requestId") == requestId && started.long("pid") > 0 && started.long("processGroupId") > 0) { "Supervisor launch registration mismatch" }
        val registrationId = started.string("registrationId")
        check(registrations.add(registrationId)) { "Process registration identity reused" }
        val deferred = CompletableDeferred<JsonObject>()
        check(exits.putIfAbsent(registrationId, deferred) == null) { "Duplicate process waiter" }
        received[registrationId]?.let(deferred::complete)
        try {
            val exited = withTimeout(request.timeoutSeconds * 1000) { deferred.await() }
            require(exited["timedOut"] != JsonPrimitive(true)) { "Supervisor process timed out" }
            val truncated = exited["captureTruncated"] == JsonPrimitive(true)
            val stdout = if (!truncated) exited.string("stdout") else exited["stdoutArtifactId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { readArtifact(it, "process-output/$registrationId.stdout") }.orEmpty()
            val stderr = if (!truncated) exited.string("stderr") else exited["stderrArtifactId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { readArtifact(it, "process-output/$registrationId.stderr") }.orEmpty()
            if (truncated) require(exited["stdoutArtifactId"] != JsonNull && exited["stderrArtifactId"] != JsonNull) { "Truncated captures need authorized artifact resolution" }
            require(stdout.toByteArray().size <= SubprocessExecutor.MAX_STREAM_BYTES && stderr.toByteArray().size <= SubprocessExecutor.MAX_STREAM_BYTES) { "Supervisor stream exceeds cap" }
            stdout.lineSequence().forEach { prepared.stdoutLineCallback?.invoke(it) }
            prepared.confirmSettled()
            return SubprocessExecutor.ExecResult(exited.long("exitCode").toInt(), stdout, stderr, (System.nanoTime() - startedAt) / 1_000_000)
        } finally {
            exits.remove(registrationId); received.remove(registrationId)
            // Timeout/cancellation never confirms settled. Supervisor root-stop handles the process.
        }
    }
    private suspend fun executeStreaming(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult = coroutineScope {
        require(request.stdin?.toByteArray()?.size?.let { it <= SubprocessExecutor.MAX_STDIN_BYTES } != false) { "Stdin exceeds runtime budget" }
        val execution = request.env["WF_EXECUTION_ID"] ?: request.env["BRAIDRUN_EXECUTION_ID"] ?: ipc.binding.string("executionId")
        val scope = com.fartech.agents.workflow.WorkflowHostContext.scope ?: com.fartech.agents.workflow.WorkflowHostScope(ipc.binding.string("executionId"), execution)
        val channel = requireNotNull(interactive).open(scope, request)
        val started = System.nanoTime()
        fun capture(stream: java.io.InputStream, callback: ((String) -> Unit)?) = captureProgressStream(stream, callback)
        try {
            val stdout = async(Dispatchers.IO) { capture(channel.stdout, request.stdoutLineCallback) }
            val stderr = async(Dispatchers.IO) { capture(channel.stderr, null) }
            val input = async(Dispatchers.IO) { request.stdin?.let { channel.stdin.write(it.toByteArray(Charsets.UTF_8)) }; channel.stdin.close() }
            val exit = channel.awaitExit(); input.await()
            val result = SubprocessExecutor.ExecResult(exit, stdout.await(), stderr.await(), (System.nanoTime() - started) / 1_000_000)
            request.confirmSettled()
            result
        } finally { channel.close() }
    }

}

/** A progress line above this is no event: an event frame carries at most half of [RunnerIpc.MAX_FRAME_BYTES]. */
internal const val PROGRESS_LINE_LIMIT_BYTES = RunnerIpc.MAX_FRAME_BYTES / 2

/**
 * A streamed child's bounded output, each line also handed to [callback] (the agent's progress events). A line above
 * [PROGRESS_LINE_LIMIT_BYTES] is neither an event nor result evidence - Codex puts a command's whole output into one
 * `item.completed` line, and the caller parses only the small thread/turn/agent-message events - so it is kept as a
 * one-line marker instead of failing the call. Production 2026-11-02 (PantryWise 5c066528): one `cat` of baseline JSON
 * failed the whole assess-existing-project agent with "Progress line exceeds bounded event budget". Without a callback
 * the output is kept byte for byte within the existing hard limit. Agent streams retain a bounded head and tail,
 * including the terminal result, and keep draining after the capture fills instead of aborting a healthy child.
 */
internal fun captureProgressStream(stream: java.io.InputStream, callback: ((String) -> Unit)?): String {
    val output = java.io.ByteArrayOutputStream()
    val agentOutput = if (callback != null) AgentOutputCapture() else null
    val line = java.io.ByteArrayOutputStream()
    var omitted = 0L
    fun keep(bytes: ByteArray, newline: Boolean) {
        val capture = requireNotNull(agentOutput)
        capture.write(bytes, bytes.size)
        if (newline) capture.write(byteArrayOf(10), 1)
    }
    fun endLine(newline: Boolean, callback: (String) -> Unit) {
        if (omitted > 0) {
            keep("[progress line of $omitted bytes omitted]".toByteArray(Charsets.UTF_8), newline)
            omitted = 0
        } else {
            keep(line.toByteArray(), newline)
            callback(line.toString(Charsets.UTF_8))
        }
        line.reset()
    }
    val buffer = ByteArray(8192)
    while (true) {
        val n = stream.read(buffer); if (n < 0) break
        if (callback == null) {
            require(output.size() + n <= SubprocessExecutor.MAX_STREAM_BYTES) { "Stream exceeds complete-output budget" }
            output.write(buffer, 0, n)
            continue
        }
        for (i in 0 until n) {
            val byte = buffer[i]
            when {
                byte == 10.toByte() -> endLine(true, callback)
                omitted > 0 -> omitted++
                line.size() >= PROGRESS_LINE_LIMIT_BYTES -> { omitted = line.size() + 1L; line.reset() }
                else -> line.write(byte.toInt())
            }
        }
    }
    if (callback != null && (line.size() > 0 || omitted > 0)) endLine(false, callback)
    return agentOutput?.text() ?: output.toString(Charsets.UTF_8)
}
