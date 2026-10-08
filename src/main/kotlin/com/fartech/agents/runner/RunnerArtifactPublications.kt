package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Internal trusted policy seam only. P has not yet frozen its delivery in start/bundle. */
fun interface RunnerArtifactPolicy {
    fun declared(workflowId: String, scope: WorkflowHostScope, candidates: List<WorkflowArtifactCandidate>): List<RunnerDeclaredArtifact>
}
data class RunnerDeclaredArtifact(val logicalPath: String, val maximumBytes: Long)
data class RunnerArtifactIntent(val publicationId: String, val artifactId: String, val declaration: RunnerDeclaredArtifact,
                                val workflowId: String, val scope: WorkflowHostScope, val producerStartEventId: String)
data class RunnerArtifactFingerprint(val size: Long, val sha256: String)
/** Assembled only with a trusted descriptor-confined reader. Never populated from stdout/model JSON.
 * The S transport must still snapshot/recheck expected full hash before forwarding the event. */
fun interface RunnerArtifactFingerprinter { suspend fun fingerprint(intent: RunnerArtifactIntent): RunnerArtifactFingerprint }

internal class RunnerArtifactPublications(
    private val root: Path, private val ipc: RunnerIpc, private val gate: RunnerEnvironmentGate,
    private val store: RunnerCheckpointStore, private val secrets: RunnerSecrets,
    private val reader: RunnerArtifactFingerprinter, private val recordEvent: (JsonObject) -> JsonObject,
    private val acceptAck: (JsonObject) -> Unit,
    private val ackTimeoutMs: Long = 30_000
) {
    init {
        val schema = requireNotNull(javaClass.getResourceAsStream("/runner-workflow-v1/artifact-publication.schema.json")) { "Artifact publication supplement missing" }.use { it.readBytes() }
        require(sha256(schema) == "d3cbb764d096ca9a68ba5a74adafe543a668f79e6273a65287acad3aaff9af09") { "Artifact publication supplement changed" }
    }
    private val lock = Mutex()
    private val accepted = mutableSetOf<String>()
    suspend fun publish(workflowId: String, scope: WorkflowHostScope, producerStartEventId: String, declaration: RunnerDeclaredArtifact) = lock.withLock {
        gate.check()
        require(scope.rootExecutionId == ipc.binding.string("executionId") && !scope.stepName.isNullOrBlank()) { "Artifact producer scope missing" }
        require(workflowId.length in 1..128 && scope.stepName.length <= 256 && producerStartEventId.length in 1..128)
        val child = childPath(scope.rootExecutionId, scope.executionId)
        require(child.size <= 64 && child.all { it.jsonPrimitive.content.matches(Regex("[A-Za-z0-9_.-]{1,256}")) })
        val path = declaration.logicalPath
        require(path.length in 1..1024 && path.none { it.isISOControl() } && ':' !in path && Path.of(path).normalize().toString() == path)
        confinedPath(root, path)
        require(Path.of(path).none { it.toString().startsWith('.') } && path.substringBefore('/') !in setOf("inputs", "skills", "process-output", "checkpoints")) { "Private/control/input files are not business outputs" }
        require(declaration.maximumBytes in 0..268435456)
        val key = buildJsonObject {
            put("binding", ipc.binding); put("workflowId", workflowId); put("executionId", scope.executionId)
            put("stepName", scope.stepName); put("producerStartEventId", producerStartEventId); put("logicalPath", path)
        }
        val digest = sha256(canonical(key).toByteArray())
        val intent = RunnerArtifactIntent("publication_$digest", "artifact_$digest", declaration, workflowId, scope, producerStartEventId)
        val metadata = JsonObject(key + mapOf("publicationId" to JsonPrimitive(intent.publicationId), "artifactId" to JsonPrimitive(intent.artifactId), "maximumBytes" to JsonPrimitive(declaration.maximumBytes)))
        require(!secrets.containsSecret(metadata)) { "Artifact intent contains secret material" }
        val prefix = ".runner-state/artifact-intents/$digest"
        val intentPath = confinedPath(root, "$prefix.intent.json")
        val eventPath = confinedPath(root, "$prefix.event.json")
        val ackPath = confinedPath(root, "$prefix.ack.json")
        val previous = Files.exists(intentPath)
        if (previous) require(runnerJson.parseToJsonElement(Files.readString(intentPath)) == metadata) { "Changed artifact intent" }
        else store.writePrivate("$prefix.intent.json", canonical(metadata).toByteArray()) // before any fingerprint collection
        try {
            if (digest in accepted) {
                require(Files.exists(eventPath)) { "Artifact ACK has no original event" }
                gate.check(); return@withLock
            }
            val frame = if (Files.exists(eventPath)) {
                runnerJson.parseToJsonElement(Files.readString(eventPath)).jsonObject.also {
                    require(it.obj("binding") == ipc.binding && it.obj("payload").obj("payload").string("publicationId") == intent.publicationId)
                }
            } else {
                check(!previous) { "Unfinished artifact intent needs reconciliation, never recollect or reexecute producer" }
                val fingerprint = reader.fingerprint(intent)
                gate.check()
                require(fingerprint.size in 0..declaration.maximumBytes && fingerprint.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid measured full-file fingerprint" }
                val payload = buildJsonObject {
                    put("schemaVersion", 1); put("publicationId", intent.publicationId); put("artifactId", intent.artifactId)
                    put("logicalPath", path); put("size", fingerprint.size); put("sha256", fingerprint.sha256)
                    put("childExecutionPath", child); put("producerWorkflowId", workflowId); put("producerStep", scope.stepName)
                    put("producerStartEventId", producerStartEventId); put("codeSourceSha256", JsonNull)
                }
                require(!secrets.containsSecret(payload)) { "Artifact declaration contains secret material" }
                recordEvent(payload).also { store.writePrivate("$prefix.event.json", canonical(it).toByteArray()) }
            }
            RunnerProtocol().validate(frame, "ipcFrame")
            val event = frame.obj("payload")
            val publication = event.obj("payload")
            require(frame.obj("binding") == ipc.binding && frame.string("type") == "event" && event.string("type") == "artifact") { "Stored publication is not the original artifact event" }
            require(event.string("attemptId") == ipc.binding.string("attemptId") && event.long("resumeEpoch") == ipc.binding.long("resumeEpoch")) { "Stored publication epoch changed" }
            require(publication.string("publicationId") == intent.publicationId && publication.string("artifactId") == intent.artifactId &&
                publication.string("logicalPath") == path && publication.string("producerWorkflowId") == workflowId && publication.string("producerStep") == scope.stepName &&
                publication.string("producerStartEventId") == producerStartEventId && publication.array("childExecutionPath") == child && publication["codeSourceSha256"] == JsonNull &&
                publication.long("schemaVersion") == 1L && publication.long("size") in 0..declaration.maximumBytes && publication.string("sha256").matches(Regex("[a-f0-9]{64}"))) { "Stored publication context changed" }
            require(!secrets.containsSecret(publication)) { "Stored publication contains secret material" }
            val reply = ipc.requestCorrelated("event", event, "ack", ackTimeoutMs, frameId = frame.string("frameId"))
            require(reply.payload.long("ackSeq") >= event.long("eventSeq")) { "Artifact ACK does not cover original event" }
            acceptAck(reply.payload)
            gate.check()
            store.writePrivate("$prefix.ack.json", canonical(buildJsonObject { put("binding", ipc.binding); put("frameId", reply.frameId); put("eventId", event.string("eventId")); put("ack", reply.payload) }).toByteArray())
            accepted.add(digest)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            gate.invalidate("RUNNER_ARTIFACT_PUBLICATION_UNCONFIRMED"); throw failure
        } catch (_: Exception) {
            gate.invalidate("RUNNER_ARTIFACT_PUBLICATION_UNCONFIRMED"); throw RunnerAuthorityLost()
        }
    }
}
