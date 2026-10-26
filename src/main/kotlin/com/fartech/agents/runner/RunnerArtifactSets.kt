package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Root-supervisor metadata only. One original boundary -> one original ticket/commit/dual ACK/Complete. */
internal class RunnerArtifactSets(
    private val root: Path, private val ipc: RunnerIpc, private val store: RunnerCheckpointStore,
    private val gate: RunnerEnvironmentGate, private val policy: RunnerArtifactSetPolicy, private val secrets: RunnerSecrets,
    private val guarded: (JsonObject, () -> Unit) -> Unit,
    private val append: (JsonObject, String?, (JsonObject) -> Unit) -> JsonObject,
    private val acknowledged: () -> Long
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val invalidation = gate.onInvalidated { scope.cancel() }
    private val boundaries = LinkedHashMap<String, JsonObject>()
    private val boundaryAcks = LinkedHashMap<String, JsonObject>()
    private val states = LinkedHashMap<String, JsonObject>() // indexed by original boundary eventId
    private fun file(kind: String, identity: String) = ".runner-state/artifact-sets/$kind-${sha256((canonical(ipc.binding) + identity).toByteArray())}.json"
    private fun read(path: String): JsonObject? = confinedPath(root, path).let {
        if (!Files.exists(it)) null else {
            require(Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(it) <= 8L * 1024 * 1024) { "Invalid bounded set journal" }
            runnerJson.parseToJsonElement(Files.readString(it)).jsonObject
        }
    }
    private fun write(path: String, value: JsonObject) { require(!secrets.containsSecret(value)); store.writePrivate(path, canonical(value).toByteArray()) }
    private fun body(frame: JsonObject) = frame.obj("payload").obj("payload")
    private fun originalId(frame: JsonObject) = frame.obj("payload").string("eventId")
    private fun validateEvent(frame: JsonObject, payload: JsonObject, reply: String? = null) {
        RunnerProtocol().validate(frame, "ipcFrame")
        require(frame.obj("binding") == ipc.binding && frame.string("type") == "event" && frame.obj("payload").string("type") == "step")
        require(body(frame) == payload && frame["replyTo"]?.jsonPrimitive?.content == reply)
        require(canonical(frame).toByteArray().size <= 65536)
    }
    @Synchronized fun ready(producer: JsonObject, ids: List<String>, outcome: String): List<String> {
        gate.check(); policy.verify()
        require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.size <= 128)
        val context = buildJsonObject { put("binding", ipc.binding); put("policySha256", policy.hash); put("producer", producer); put("declarationIds", JsonArray(ids.map(::JsonPrimitive))); put("outcome", outcome) }
        // Outcome cannot change under one original occurrence.
        val path = file("boundary", canonical(producer)); val saved = read(path)
        val payloads = ids.chunked(64).map { part -> buildJsonObject {
            put("kind", "artifact_set_ready"); put("policySha256", policy.hash); put("producer", producer)
            put("declarationIds", JsonArray(part.map(::JsonPrimitive))); put("outcome", outcome)
        }.also { RunnerArtifactSetProtocol.validate(it, "boundaryPayload") } }
        require(saved != null || boundaries.size + payloads.size <= 4096) { "Set boundary bound exceeded" }
        val frames = mutableListOf<JsonObject>()
        guarded(producer) {
            if (saved != null) {
                require(saved.obj("context") == context)
                frames += saved.array("frames").map { it.jsonObject }
                require(frames.size == payloads.size) { "Partial ready intent requires reconciliation" }
                frames.zip(payloads).forEach { (f, p) -> validateEvent(f, p); boundaries[originalId(f)] = f; ipc.sendFrame(f) }
            } else {
                fun persist() = write(path, buildJsonObject { put("context", context); put("frames", JsonArray(frames)) })
                persist()
                payloads.forEach { payload ->
                    append(payload, null) { frame -> validateEvent(frame, payload); frames += frame; persist() }
                    boundaries[originalId(frames.last())] = frames.last()
                }
            }
        }
        return frames.map(::originalId)
    }
    /** Never wait for remote ACKs or hold the IPC reader while writing durable state. */
    fun receive(frame: JsonObject) { scope.launch {
        try { handle(frame) }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { gate.invalidate("RUNNER_ARTIFACT_SET_REJECTED") }
    } }
    @Synchronized private fun handle(frame: JsonObject) {
        gate.check(); policy.verify(); require(frame.obj("binding") == ipc.binding)
        when (frame.string("type")) {
            "artifactSetCollectRequest" -> collect(frame)
            "ack", "artifactSetIndexAck" -> proof(frame)
            else -> error("Unexpected set frame")
        }
    }
    private fun collect(request: JsonObject) {
        RunnerArtifactSetProtocol.validate(request, "collectFrame"); require(!secrets.containsSecret(request))
        val b = request.obj("payload"); val boundaryId = b.string("boundaryEventId")
        val boundary = requireNotNull(boundaries[boundaryId]); val ready = body(boundary)
        require(b.string("policySha256") == policy.hash && b.obj("producer") == ready.obj("producer"))
        val readyAck = boundaryAcks[boundaryId] ?: read(file("ready-ack", boundaryId))
        requireNotNull(readyAck) { "Ready not separately durably ACKed" }
        validateReadyAck(boundary, readyAck)
        require(acknowledged() >= boundary.obj("payload").long("eventSeq")) { "Ready not durably ACKed" }
        guarded(b.obj("producer")) {
            val path = file("state", boundaryId)
            val previous = states[boundaryId] ?: read(path)
            if (previous != null) {
                require(previous.obj("request") == request) { "Changed original set request" }
                validateState(previous, boundary)
                states[boundaryId] = previous
                ipc.sendFrame(previous["complete"]?.jsonObject ?: previous.obj("event"))
                return@guarded
            }
            // cb53: frozen expiresAt is P/S upload metadata, not this producer's execution deadline.
            // P validates its current upload ledger; E requires its own live scope and both original proofs.
            Instant.parse(b.string("expiresAt"))
            // Durable semantic uniqueness precedes event allocation, including all-new attacker IDs.
            for (kind in listOf("setId", "ticketId")) {
                val link = file(kind, b.string(kind)); val intent = buildJsonObject { put("boundaryEventId", boundaryId); put("requestHash", sha256(canonical(request).toByteArray())) }
                val old = read(link); if (old == null) write(link, intent) else require(old == intent)
            }
            write(path, buildJsonObject { put("request", request) })
            val payload = commitPayload(b)
            val event = append(payload, request.string("frameId")) { original ->
                validateEvent(original, payload, request.string("frameId"))
                val state = buildJsonObject { put("request", request); put("event", original) }
                write(path, state); states[boundaryId] = state
            }
            require(states[boundaryId]?.obj("event") == event)
        }
    }
    private fun commitPayload(b: JsonObject) = buildJsonObject {
        put("kind", "artifact_set_committed")
        for (key in listOf("setId", "ticketId", "snapshotSha256", "policySha256", "producer", "boundaryEventId")) put(key, b.getValue(key))
    }.also { RunnerArtifactSetProtocol.validate(it, "commitPayload") }
    private fun validateState(state: JsonObject, boundary: JsonObject) {
        val request = state.obj("request"); RunnerArtifactSetProtocol.validate(request, "collectFrame")
        require(request.obj("binding") == ipc.binding && request.obj("payload").string("boundaryEventId") == originalId(boundary))
        validateEvent(state.obj("event"), commitPayload(request.obj("payload")), request.string("frameId"))
        state["ack"]?.jsonObject?.let { validateProof(state, it) }
        state["index"]?.jsonObject?.let { validateProof(state, it) }
        require(state["ack"] == null || state["index"] == null || state["complete"] != null) { "Incomplete dual-proof journal requires reconciliation" }
        state["complete"]?.jsonObject?.let {
            RunnerArtifactSetProtocol.validate(it, "completeFrame")
            require(state["ack"] != null && state["index"] != null && it.obj("binding") == ipc.binding && it.string("replyTo") == request.string("frameId") && it.obj("payload") == completionPayload(state))
        }
    }
    private fun validateProof(state: JsonObject, frame: JsonObject) {
        val event = state.obj("event"); val e = event.obj("payload"); val request = state.obj("request").obj("payload"); val p = frame.obj("payload")
        require(frame.obj("binding") == ipc.binding && frame.string("replyTo") == event.string("frameId"))
        if (frame.string("type") == "ack") {
            RunnerProtocol().validate(frame, "ipcFrame"); require(p.long("ackSeq") >= e.long("eventSeq"))
        } else {
            RunnerArtifactSetProtocol.validate(frame, "indexAckFrame")
            require(p.string("eventId") == e.string("eventId") && p.long("eventSeq") == e.long("eventSeq"))
            require(p.string("setId") == request.string("setId") && p.string("snapshotSha256") == request.string("snapshotSha256"))
        }
    }
    private fun validateReadyAck(boundary: JsonObject, frame: JsonObject) {
        RunnerProtocol().validate(frame, "ipcFrame")
        require(frame.string("type") == "ack" && frame.obj("binding") == ipc.binding && frame.string("replyTo") == boundary.string("frameId"))
        require(frame.obj("payload").long("ackSeq") >= boundary.obj("payload").long("eventSeq"))
    }
    private fun proof(frame: JsonObject) {
        val reply = frame["replyTo"]?.jsonPrimitive?.content ?: return
        if (frame.string("type") == "ack") {
            val boundary = boundaries.values.singleOrNull { it.string("frameId") == reply }
            if (boundary != null) {
                guarded(body(boundary).obj("producer")) {
                    validateReadyAck(boundary, frame)
                    val id = originalId(boundary)
                    val previous = boundaryAcks[id] ?: read(file("ready-ack", id))
                    previous?.let { require(it == frame) { "Changed original ready ACK" } }
                    write(file("ready-ack", id), frame); boundaryAcks[id] = frame
                }
                return
            }
        }
        val entry = states.entries.singleOrNull { it.value.obj("event").string("frameId") == reply }
        if (entry == null) { require(frame.string("type") == "ack") { "Unknown set index correlation" }; return }
        var state = entry.value
        val producer = state.obj("request").obj("payload").obj("producer")
        guarded(producer) {
            validateProof(state, frame)
            val field = if (frame.string("type") == "ack") "ack" else "index"
            state[field]?.let { require(it == frame) { "Changed original set proof" } }
            state = JsonObject(state + (field to frame))
            if (state["ack"] != null && state["index"] != null) {
                require(state.obj("index").obj("payload").long("revision") >= state.obj("ack").obj("payload").long("revision"))
                if (state["complete"] == null) {
                    val complete = buildJsonObject {
                        put("protocolVersion", 1); put("frameId", UUID.randomUUID().toString()); put("type", "artifactSetCollectComplete"); put("binding", ipc.binding)
                        put("replyTo", state.obj("request").string("frameId")); put("payload", completionPayload(state))
                    }
                    RunnerArtifactSetProtocol.validate(complete, "completeFrame")
                    state = JsonObject(state + ("complete" to complete))
                }
            }
            write(file("state", entry.key), state); states[entry.key] = state
            state["complete"]?.jsonObject?.let { gate.check(); ipc.sendFrame(it) }
        }
    }
    private fun completionPayload(state: JsonObject) = buildJsonObject {
        val request = state.obj("request").obj("payload"); val index = state.obj("index").obj("payload")
        for (key in listOf("setId", "ticketId", "snapshotSha256")) put(key, request.getValue(key))
        for (key in listOf("eventId", "eventSeq", "revision", "setIndexHash")) put(key, index.getValue(key))
    }
    @Synchronized private fun completed(ids: List<String>) = ids.all { states[it]?.get("complete") != null }
    suspend fun awaitCompleted(ids: List<String>) {
        val budgets = currentCoroutineContext()[com.fartech.agents.workflow.ArtifactCompletionBudgets]?.remaining.orEmpty()
        val fallback = System.nanoTime() + 30_000_000_000L
        while (true) {
            currentCoroutineContext().ensureActive(); gate.check()
            val remaining = budgets.minOfOrNull { it() } ?: ((fallback - System.nanoTime()) / 1_000_000)
            if (remaining <= 0) withTimeout(1) { awaitCancellation() }
            if (completed(ids)) return
            delay(minOf(remaining, 20L).coerceAtLeast(1))
        }
    }
    override fun close() { invalidation.close(); scope.cancel() }
}
