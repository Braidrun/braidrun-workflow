package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerArtifactPublicationTest {
    @Test fun `actual root and child declared files block next gate until original publication ACK`(@TempDir dir: Path) = runBlocking<Unit> {
        Files.createDirectories(dir.resolve("run"))
        fun code(path: String, content: String) = "printf '$content' > '${dir.resolve(path)}'; printf 'untrusted-output-path=run/not-declared.txt'"
        val sources = mapOf("root" to """
            name: artifact-root
            agents: {}
            workflow:
              - step: produce
                code:
                  language: bash
                  script: "${code("run/root.txt", "root-file") }"
              - step: nested
                sub_workflow:
                  workflow_id: child
              - step: gate
                manual_approval:
                  enabled: true
                  timeout: 30
                code:
                  language: bash
                  script: 'printf after-publication-gate'
        """.trimIndent(), "child" to """
            name: artifact-child
            agents: {}
            workflow:
              - step: produce
                code:
                  language: bash
                  script: "${code("run/child.txt", "child-file") }"
        """.trimIndent())
        sources.forEach { (id, yaml) -> Files.writeString(dir.resolve("$id.yaml"), yaml) }
        Files.writeString(dir.resolve("run/not-declared.txt"), "must not be collected")
        val rootHash = sha256(sources.getValue("root").toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(rootHash)))
        val metadata = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(sources.toSortedMap().map { (id, yaml) -> buildJsonObject { put("path", "$id.yaml"); put("role", "definition"); put("size", yaml.toByteArray().size); put("sha256", sha256(yaml.toByteArray())) } }))
            put("definitions", JsonArray(sources.toSortedMap().map { (id, yaml) -> buildJsonObject { put("workflowId", id); put("path", "$id.yaml"); put("definitionHash", sha256(yaml.toByteArray())) } }))
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
        IpcHarness(binding).use { h ->
            val env = RunnerProtocolTest.environment(binding, Instant.now()); val gate = RunnerEnvironmentGate(binding, setOf("subprocess"))
            gate.install(JsonObject(env + ("egress" to JsonArray(env.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("subprocess"))) }))))
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), rootHash, binding.string("runtimeHash"))
            val secrets = RunnerSecrets(); val stored = RunnerCheckpointStore(dir, binding, secrets)
            var fingerprints = 0; var launches = 0; var approved = false; var accepted = 0
            val executor = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult { launches++; return NativeSubprocessExecutor().execute(request) }
            }
            val runtime = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, executor, stored, secrets,
                artifactPolicy = RunnerArtifactPolicy { workflowId, scope, candidates ->
                    assertTrue(candidates.isEmpty(), "Raw code stdout must not create candidates")
                    if (scope.stepName == "produce") listOf(RunnerDeclaredArtifact("run/$workflowId.txt", 1024)) else emptyList()
                }, artifactFingerprinter = RunnerArtifactFingerprinter { intent ->
                    fingerprints++
                    val digest = intent.publicationId.removePrefix("publication_")
                    assertTrue(Files.exists(dir.resolve(".runner-state/artifact-intents/$digest.intent.json")), "Intent must precede reading")
                    // Trusted fixture measures only its two known ordinary files; production confined adapter is not assembled here.
                    assertTrue(intent.declaration.logicalPath in setOf("run/root.txt", "run/child.txt"))
                    val bytes = Files.readAllBytes(dir.resolve(intent.declaration.logicalPath))
                    RunnerArtifactFingerprint(bytes.size.toLong(), sha256(bytes))
                })
            val host = object : WorkflowHost by runtime {
                override val approvalHandler = object : ApprovalHandler {
                    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
                        assertEquals(2, accepted, "First gate cannot precede both publication/index ACKs")
                        approved = true; return ApprovalDecision.APPROVED_AS_IS
                    }
                }
            }
            val events = Channel<JsonObject>(2); val release = Channel<Unit>(2)
            val starts = mutableMapOf<String, String>(); val seen = mutableListOf<JsonObject>()
            val task = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(bundle.rootDefinition) }
            val pump = launch {
                while (isActive) {
                    val frame = h.next(); val body = frame.obj("payload")
                    if (frame.string("type") == "event") {
                        val payload = body.obj("payload")
                        if ((payload["event"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "code_step_started") starts[payload.string("executionId")] = body.string("eventId")
                        if (body.string("type") == "artifact") {
                            seen += frame
                            val child = payload.array("childExecutionPath").map { it.jsonPrimitive.content }
                            val execution = if (child.isEmpty()) identity.executionId else identity.executionId + "__" + child.joinToString("__")
                            assertEquals(starts.getValue(execution), payload.string("producerStartEventId"))
                            assertEquals(if (child.isEmpty()) "root" else "child", payload.string("producerWorkflowId"))
                            assertEquals(JsonNull, payload["codeSourceSha256"])
                            assertNotEquals(frame.string("frameId"), body.string("eventId"))
                            assertEquals(frame, runnerJson.parseToJsonElement(Files.readString(dir.resolve(".runner-state/events/${body.long("eventSeq")}.json"))))
                            val bytes = Files.readAllBytes(dir.resolve(payload.string("logicalPath")))
                            assertEquals(sha256(bytes), payload.string("sha256")); assertEquals(bytes.size.toLong(), payload.long("size"))
                            events.send(frame); release.receive()
                            accepted++ // controlled S chunk verification / P index ACK fixture, no real HTTP acceptance
                            h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", body.long("eventSeq")); put("revision", accepted); put("serverTime", Instant.now().toString()) }))
                        }
                    }
                }
            }
            try {
                val first = withTimeout(5000) { events.receive() }; assertEquals(1, launches); assertFalse(approved)
                // A generic cumulative ACK without replyTo cannot release the artifact request.
                h.ipc.accept(RunnerProtocolTest.frame(binding, "ack", buildJsonObject { put("ackSeq", first.obj("payload").long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) }))
                delay(30); assertEquals(1, launches); assertFalse(task.isCompleted)
                release.send(Unit)
                withTimeout(5000) { events.receive() }; assertEquals(2, launches); assertFalse(approved)
                release.send(Unit)
                val result = withTimeout(5000) { task.await() }; assertTrue(result.success, result.error)
                assertTrue(approved); assertEquals(3, launches); assertEquals(2, fingerprints)
                assertEquals(2, seen.map { it.obj("payload").obj("payload").string("publicationId") }.distinct().size)
                assertEquals(listOf(emptyList(), listOf("nested", "call_1")), seen.map { it.obj("payload").obj("payload").array("childExecutionPath").map { p -> p.jsonPrimitive.content } })
            } finally { task.cancel(); pump.cancel(); runtime.close(); h.supervisorInput.close() }
        }
    }
}
