package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** S has already snapshotted and ACKed bytes. E never reads a business file here. */
internal class RunnerCollectionBarrier(
    private val root: Path, private val ipc: RunnerIpc, private val store: RunnerCheckpointStore,
    private val gate: RunnerEnvironmentGate, private val policy: RunnerCollectionPolicy, private val secrets: RunnerSecrets,
    private val checkProducer: (JsonObject) -> Unit,
    private val recordEvent: (JsonObject, String, String, (JsonObject) -> Unit) -> JsonObject,
    private val acknowledge: (JsonObject) -> Unit,
    private val guardedComplete: (JsonObject, () -> Unit) -> Unit
) : AutoCloseable {
    private class State(val request: JsonObject) {
        @Volatile var event: JsonObject? = null
        @Volatile var complete: JsonObject? = null
        @Volatile var eventReady = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = LinkedHashMap<String, State>()
    private val occurrences = HashMap<String, String>()
    private fun occurrence(body: JsonObject) = canonical(buildJsonObject {
        put("binding", ipc.binding); put("declarationId", body.string("declarationId")); put("producerStartEventId", body.string("producerStartEventId"))
    })
    private val publications = HashMap<String, String>()
    private val artifacts = HashMap<String, String>()
    private val invalidation = gate.onInvalidated { scope.cancel() }
    private fun path(requestId: String) = ".runner-state/artifact-collection/" + sha256((canonical(ipc.binding) + ":" + requestId).toByteArray()) + ".json"
    private fun persist(state: State, completion: JsonObject? = state.complete) {
        store.writePrivate(path(state.request.obj("payload").string("requestId")), canonical(buildJsonObject {
            put("request", state.request); state.event?.let { put("event", it) }; completion?.let { put("complete", it) }
        }).toByteArray())
    }
    private fun validate(request: JsonObject) {
        gate.check(); RunnerCollectionProtocol.validate(request, "collectionRequest")
        require(request.obj("binding") == ipc.binding)
        require(!secrets.containsSecret(request)) { "Collection metadata contains secrets" }
        policy.validateRequest(request.obj("payload")); checkProducer(request.obj("payload"))
    }
    /** Fast bounded admission only; filesystem work and ACK waits never run on the IPC reader. */
    @Synchronized fun receive(request: JsonObject) {
        gate.check()
        val body = request.obj("payload"); val requestId = body.string("requestId")
        val previous = states[requestId]
        if (previous != null) {
            require(previous.request == request) { "Collection retry changed original frame/metadata" }
            scope.launch {
                try {
                    validate(request)
                    guardedComplete(body) {
                        previous.complete?.let { ipc.sendFrame(it) }
                            ?: previous.event?.takeIf { previous.eventReady }?.let { ipc.sendFrame(it) }
                    }
                    // If first request is still allocating, its original job will send the one event.
                } catch (failure: CancellationException) {
                    if (scope.isActive) gate.invalidate("RUNNER_ARTIFACT_COLLECTION_REJECTED")
                    throw failure
                } catch (_: Exception) { gate.invalidate("RUNNER_ARTIFACT_COLLECTION_REJECTED") }
            }
            return
        }
        val logicalIntent = occurrence(body)
        require(occurrences[logicalIntent]?.let { it == requestId } != false) { "Declaration occurrence already has an original intent" }
        require(states.size < 4096) { "Collection intent bound exceeded" }
        require(publications[body.string("publicationId")]?.let { it == requestId } != false && artifacts[body.string("artifactId")]?.let { it == requestId } != false) { "Collection identity reused" }
        occurrences[logicalIntent] = requestId
        publications[body.string("publicationId")] = requestId; artifacts[body.string("artifactId")] = requestId
        val state = State(request); states[requestId] = state
        scope.launch {
            try { execute(state) }
            catch (failure: CancellationException) {
                if (scope.isActive) gate.invalidate("RUNNER_ARTIFACT_COLLECTION_UNCONFIRMED")
                throw failure
            } catch (_: Exception) { gate.invalidate("RUNNER_ARTIFACT_COLLECTION_UNCONFIRMED") }
        }
    }
    private suspend fun execute(state: State) {
        val request = state.request; val body = request.obj("payload"); val requestId = body.string("requestId")
        validate(request)
        for ((kind, identity) in listOf("occurrence" to occurrence(body), "publication" to body.string("publicationId"), "artifact" to body.string("artifactId"))) {
            val link = ".runner-state/artifact-collection/$kind-${sha256((canonical(ipc.binding) + identity).toByteArray())}.json"
            val value = buildJsonObject { put("requestId", requestId); put("requestHash", sha256(canonical(request).toByteArray())) }
            synchronized(this) {
                val file = confinedPath(root, link)
                if (Files.exists(file)) require(runnerJson.parseToJsonElement(Files.readString(file)) == value) { "Persisted collection identity conflict" }
                else store.writePrivate(link, canonical(value).toByteArray())
            }
        }
        val existing = confinedPath(root, path(requestId))
        if (Files.exists(existing)) {
            val saved = runnerJson.parseToJsonElement(Files.readString(existing)).jsonObject
            require(saved.obj("request") == request) { "Persisted collection request changed" }
            state.event = saved["event"]?.jsonObject ?: error("Unfinished collection intent needs reconciliation")
            state.complete = saved["complete"]?.jsonObject
        } else persist(state) // exact S request/intent before allocating any E sequence
        state.event?.let { validateEvent(request, it) }
        state.complete?.let { complete ->
            RunnerCollectionProtocol.validate(complete, "collectionComplete")
            require(complete.obj("binding") == ipc.binding && complete.string("replyTo") == request.string("frameId"))
            require(complete.obj("payload") == completionPayload(request, requireNotNull(state.event))) { "Persisted completion changed" }
            guardedComplete(body) { ipc.sendFrame(complete) }; return
        }
        val frameId = state.event?.string("frameId") ?: UUID.randomUUID().toString()
        val ack = ipc.awaitReply("ack", frameId = frameId) {
            if (state.event == null) {
                val payload = buildJsonObject {
                    put("schemaVersion", 1)
                    for (key in listOf("publicationId", "artifactId", "logicalPath", "size", "sha256", "childExecutionPath", "producerWorkflowId", "producerStep", "producerStartEventId", "codeSourceSha256")) put(key, body.getValue(key))
                }
                recordEvent(payload, request.string("frameId"), frameId) { frame ->
                    validateEvent(request, frame); state.event = frame; persist(state)
                }
                state.eventReady = true
            } else guardedComplete(body) {
                validateEvent(request, requireNotNull(state.event)); ipc.sendFrame(requireNotNull(state.event)); state.eventReady = true
            }
        }
        val event = requireNotNull(state.event)
        require(ack.payload.long("ackSeq") >= event.obj("payload").long("eventSeq"))
        acknowledge(ack.payload)
        validate(request)
        guardedComplete(body) {
            val complete = buildJsonObject {
                put("protocolVersion", 1); put("frameId", UUID.randomUUID().toString()); put("type", "artifactCollectComplete")
                put("binding", ipc.binding); put("replyTo", request.string("frameId")); put("payload", completionPayload(request, event))
            }
            RunnerCollectionProtocol.validate(complete, "collectionComplete")
            persist(state, complete); state.complete = complete; gate.check(); ipc.sendFrame(complete)
        }
    }
    private fun validateEvent(request: JsonObject, event: JsonObject) {
        val body = request.obj("payload")
        RunnerProtocol().validate(event, "ipcFrame")
        require(event.obj("binding") == ipc.binding && event.string("type") == "event" && event.string("replyTo") == request.string("frameId"))
        require(event.obj("payload").string("type") == "artifact")
        val publication = event.obj("payload").obj("payload")
        require(publication.long("schemaVersion") == 1L && publication.keys == body.keys - setOf("requestId", "policySha256", "declarationId") + "schemaVersion")
        require(body.filterKeys { it !in setOf("requestId", "policySha256", "declarationId") }.all { publication[it.key] == it.value })
    }
    private fun completionPayload(request: JsonObject, event: JsonObject) = buildJsonObject {
        val body = request.obj("payload")
        for (key in listOf("requestId", "publicationId", "artifactId", "sha256", "size")) put(key, body.getValue(key))
        put("eventId", event.obj("payload").string("eventId")); put("eventSeq", event.obj("payload").long("eventSeq"))
    }
    @Synchronized private fun completed(declarations: List<String>, producerStartEventId: String): Boolean = declarations.all { id -> states.values.any { state ->
        state.complete != null && state.request.obj("payload").string("declarationId") == id &&
            state.request.obj("payload").string("producerStartEventId") == producerStartEventId
    } }
    fun requireCompleted(declarations: List<String>, producerStartEventId: String) {
        gate.check(); require(completed(declarations, producerStartEventId)) { "Closed code producer has uncollected material" }
    }
    suspend fun awaitCompleted(declarations: List<String>, producerStartEventId: String) {
        val budgets = currentCoroutineContext()[com.fartech.agents.workflow.ArtifactCompletionBudgets]?.remaining.orEmpty()
        val fallback = System.nanoTime() + 30_000_000_000L // only when no original step/global budget exists
        while (true) {
            currentCoroutineContext().ensureActive(); gate.check()
            val remaining = budgets.minOfOrNull { it() } ?: ((fallback - System.nanoTime()) / 1_000_000)
            if (remaining <= 0) withTimeout(1) { awaitCancellation() }
            if (completed(declarations, producerStartEventId)) return
            delay(minOf(remaining, 20L).coerceAtLeast(1))
        }
    }
    override fun close() { invalidation.close(); scope.cancel() }
}
