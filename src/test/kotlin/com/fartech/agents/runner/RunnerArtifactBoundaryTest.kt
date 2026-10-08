package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerArtifactBoundaryTest {
    @Test fun `exact P boundary fixtures enforce declaration identity and reject path or verdict additions`() {
        val raw = javaClass.getResourceAsStream("/runner-workflow-v1/artifact-boundary-fixtures.json")!!.readBytes().toString(Charsets.UTF_8)
        val fixtures = runnerJson.parseToJsonElement(raw).jsonArray
        assertEquals(5, fixtures.size)
        fixtures.forEach { item ->
            val fixture = item.jsonObject
            val result = runCatching { RunnerArtifactBoundaryProtocol.validate(fixture.obj("value")) }
            assertEquals(fixture.getValue("valid").jsonPrimitive.boolean, result.isSuccess, fixture.string("name"))
        }
        val valid = fixtures[0].jsonObject.obj("value")
        assertFails { RunnerArtifactBoundaryProtocol.validate(JsonObject(valid + ("acceptedRevision" to JsonPrimitive(2)))) }
        assertTrue(RunnerArtifactBoundaryProtocol.available())
    }
    @Test fun `partitioned ready events remain within server bound and duplicate or closed contexts cannot reallocate`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = """
            name: boundary
            agents:
              writer:
                strategy: single_run
                llm: {model: gpt-4o-mini, provider: openai}
            workflow:
              - step: write
                agent: writer
                input: fixture
        """.trimIndent()
        val ids = (0 until 70).map { "decl-$it" }
        val policyJson = buildJsonObject {
            put("schemaVersion", 1); put("rootWorkflowId", "root")
            put("declarations", JsonArray(ids.map { id -> buildJsonObject {
                put("declarationId", id); put("producerWorkflowId", "root"); put("producerStep", "write"); put("childSteps", JsonArray(emptyList()))
                put("logicalPath", "run/$id.txt"); put("kind", "MATERIAL"); put("maximumBytes", 1024); put("sourcePolicy", "UNAVAILABLE"); put("codeSourceSha256", JsonNull)
            } }))
        }
        val files = mapOf("root.yaml" to ("definition" to source), RunnerCollectionPolicy.PATH to ("runtime" to policyJson.toString()))
        files.forEach { (path, data) -> Files.createDirectories(dir.resolve(path).parent); Files.writeString(dir.resolve(path), data.second) }
        val hash = sha256(source.toByteArray()); val b = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(files.toSortedMap().map { (path, data) -> buildJsonObject { put("path", path); put("role", data.first); put("size", data.second.toByteArray().size); put("sha256", sha256(data.second.toByteArray())) } }))
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), b)
        val policy = RunnerCollectionPolicy(bundle)
        IpcHarness(b).use { h ->
            val gate = RunnerEnvironmentGate(b, setOf("jvm-http")); val e = RunnerProtocolTest.environment(b, Instant.now())
            gate.install(JsonObject(e + ("egress" to JsonArray(e.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("jvm-http"))) }))))
            val identity = WorkflowHostBinding(b.string("ownerId"), b.string("actorUserId"), "billing", b.string("executionId"), b.string("runnerId"), b.string("runnerInstanceId"), b.long("resumeEpoch"), b.string("attemptId"), hash, b.string("runtimeHash"))
            val secrets = RunnerSecrets()
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), RunnerCheckpointStore(dir, b, secrets), secrets, validateDirectNetwork = {}, collectionPolicy = policy)
            try {
                val scope = WorkflowHostScope(identity.executionId, identity.executionId, stepName = "write")
                host.admit(bundle.rootDefinition, scope); host.bindArtifactProducer(bundle.rootDefinition, scope)
                host.monitor(WorkflowMonitorUpdate(identity.executionId, "step_started", "write"))
                val start = h.next().obj("payload")
                host.acknowledge(buildJsonObject { put("ackSeq", start.long("eventSeq")) })
                val boundary = RunnerArtifactReadyBoundary(policy.hash, "root", scope, start.string("eventId"), ids)
                host.announceArtifactReady(boundary)
                val frames = listOf(h.next(), h.next())
                assertEquals(listOf(64, 6), frames.map { it.obj("payload").obj("payload").array("declarationIds").size })
                assertEquals(ids, frames.flatMap { it.obj("payload").obj("payload").array("declarationIds").map { value -> value.jsonPrimitive.content } })
                frames.forEach { assertTrue(canonical(it).toByteArray().size <= 65536); RunnerArtifactBoundaryProtocol.validate(it.obj("payload").obj("payload")) }
                val seq = host.finalEventSeq()
                host.announceArtifactReady(boundary)
                assertEquals(frames, listOf(h.next(), h.next())); assertEquals(seq, host.finalEventSeq())
                assertFails { host.announceArtifactReady(boundary.copy(producerStartEventId = "other")) }
                assertFails { host.announceArtifactReady(boundary.copy(declarationIds = listOf("not-declared"))) }
                assertFails { host.announceArtifactReady(boundary.copy(scope = scope.copy(executionId = identity.executionId + "__wrong__call_1"))) }
                host.monitor(WorkflowMonitorUpdate(identity.executionId, "step_completed", "write", success = true)); h.next()
                assertFails { host.announceArtifactReady(boundary) }
            } finally { host.close() }
        }
    }
}
