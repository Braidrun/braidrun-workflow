package com.fartech.agents.runner

import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerHttpArtifactCompletionTest {
    @Test fun `HTTP model writes real structured output then suspends for delayed collection and Complete`(@TempDir dir: Path) = runFixture(dir, "complete")
    @Test fun `HTTP artifact completion wait cancels without publishing or opening next step`(@TempDir dir: Path) = runFixture(dir, "cancel")
    @Test fun `HTTP artifact wait respects original step deadline rather than restarting after write`(@TempDir dir: Path) = runFixture(dir, "step-timeout")
    @Test fun `HTTP artifact wait respects original global deadline`(@TempDir dir: Path) = runFixture(dir, "global-timeout")

    @Test fun `cancellation at the normal pre-write pause emits no ready boundary`(@TempDir dir: Path) = runFixture(dir, "cancel-before-write")

    private fun runFixture(dir: Path, mode: String) = runBlocking<Unit> {
        val output = dir.resolve("run/model.json")
        val source = """
            name: http-artifact
            agents:
              model:
                strategy: single_run
                llm:
                  model: gpt-4o-mini
                  provider: openai
            workflow:
              - step: write
                agent: model
                input: Return the fixture structured commentary.
                structured_output:
                  schema: ai_commentary_parts
                  write_to: $output
        """.trimIndent() + (if (mode == "step-timeout") "\n    timeout: 3s\n" else "\n") +
            (if (mode == "global-timeout") "timeout:\n  total: 3s\n" else "")
        val policyJson = buildJsonObject {
            put("schemaVersion", 1); put("rootWorkflowId", "root")
            put("declarations", buildJsonArray { add(buildJsonObject {
                put("declarationId", "decl-model"); put("producerWorkflowId", "root"); put("producerStep", "write"); put("childSteps", JsonArray(emptyList()))
                put("logicalPath", "run/model.json"); put("kind", "MATERIAL"); put("maximumBytes", 100000); put("sourcePolicy", "UNAVAILABLE"); put("codeSourceSha256", JsonNull)
            }) })
        }
        val files = mapOf("root.yaml" to ("definition" to source), RunnerCollectionPolicy.PATH to ("runtime" to policyJson.toString()))
        files.forEach { (name, data) -> Files.createDirectories(dir.resolve(name).parent); Files.writeString(dir.resolve(name), data.second) }
        val rootHash = sha256(source.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(rootHash)))
        val metadata = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(files.toSortedMap().map { (path, data) -> buildJsonObject { put("path", path); put("role", data.first); put("size", data.second.toByteArray().size); put("sha256", sha256(data.second.toByteArray())) } }))
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", rootHash) }) })
        }
        val bundleJson = JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray()))))
        val bundle = FrozenWorkflowBundle(dir, bundleJson, binding); val policy = RunnerCollectionPolicy(bundle)
        val modelText = """{"investment_decisions":[{"sheet_name":"fixture","status":"hold"}],"summaries":[{"sheet_name":"fixture","items":["summary"]}],"followups":[{"sheet_name":"fixture","items":["follow up"]}]}"""
        var modelCalls = 0
        val http = HttpClient(MockEngine) { engine { addHandler {
            modelCalls++; delay(if (mode.endsWith("timeout")) 900 else 120) // business time must remain in the original timeout budget
            val response = buildJsonObject {
                put("id", "fixture"); put("object", "chat.completion"); put("created", 1760000000); put("model", "gpt-4o-mini")
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("index", 0); put("message", buildJsonObject { put("role", "assistant"); put("content", modelText) }); put("finish_reason", "stop")
                }) }); put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
            }
            respond(response.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } } }
        var controlTarget: RunnerWorkflowHost? = null
        IpcHarness(binding) { frame ->
            when (frame.string("type")) {
                "ack" -> requireNotNull(controlTarget).acknowledge(frame.obj("payload"))
                "artifactCollectRequest" -> requireNotNull(controlTarget).collectRequest(frame)
            }
        }.use { h ->
            h.ipc.enableArtifactCollection()
            val gate = RunnerEnvironmentGate(binding, setOf("jvm-http")); val evidence = RunnerProtocolTest.environment(binding, Instant.now())
            gate.install(JsonObject(evidence + ("egress" to JsonArray(evidence.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("jvm-http"))) }))))
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), rootHash, binding.string("runtimeHash"))
            val secrets = RunnerSecrets(); secrets.register(JsonPrimitive("fixture-model-key"))
            val runtime = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), RunnerCheckpointStore(dir, binding, secrets), secrets, validateDirectNetwork = {}, collectionPolicy = policy)
            controlTarget = runtime
            val readyBoundary = Channel<JsonObject>(4)
            val host = object : WorkflowHost by runtime {
                override val llmHttpClientFactory = KtorKoogHttpClient.Factory(http)
                override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<ConfigurationParameter>) = parameters + listOf(
                    ConfigurationParameter("openai_api_key", JsonPrimitive("fixture-model-key")), ConfigurationParameter("disable_skills", JsonPrimitive(true)))
            }
            val started = CompletableDeferred<String>(); val completed = Channel<JsonObject>(1)
            var artifactEvents = 0
            val atWrite = CompletableDeferred<Unit>(); val permitWrite = CompletableDeferred<Unit>()
            val interpreter = WorkflowExecutor(HttpAccess(), emptyList(), host = host)
            interpreter.beforeStructuredOutputWriteForTest = { path ->
                assertEquals(output.toString(), path); atWrite.complete(Unit); permitWrite.await()
            }
            val launchedAt = System.nanoTime()
            val task = async { interpreter.execute(bundle.rootDefinition) }
            val pump = launch {
                while (isActive) {
                    val raw = withContext(Dispatchers.IO) { RunnerIpc.readLine(h.supervisorInput) } ?: break
                    val frame = runnerJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
                    if (frame.string("type") == "artifactCollectComplete") {
                        RunnerCollectionProtocol.validate(frame, "collectionComplete"); completed.send(frame)
                    } else {
                        RunnerProtocol().validate(frame, "ipcFrame")
                        if (frame.string("type") == "event") {
                            val event = frame.obj("payload"); val detail = event.obj("payload")
                            if (detail["kind"]?.jsonPrimitive?.content == "step_started" && detail["stepName"]?.jsonPrimitive?.content == "write") started.complete(event.string("eventId"))
                            val ack = buildJsonObject { put("ackSeq", event.long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) }
                            if (event.string("type") == "artifact") {
                                artifactEvents++
                                assertEquals(sha256(Files.readAllBytes(output)), detail.string("sha256"))
                                h.ipc.accept(h.reply(frame, "ack", ack))
                            } else {
                                if (detail["kind"]?.jsonPrimitive?.content == "artifact_collection_ready") {
                                    RunnerArtifactBoundaryProtocol.validate(detail)
                                    assertTrue(started.isCompleted)
                                    assertEquals(started.await(), detail.string("producerStartEventId"))
                                    assertEquals(frame, runnerJson.parseToJsonElement(Files.readString(dir.resolve(".runner-state/events/${event.long("eventSeq")}.json"))))
                                    h.ipc.accept(h.reply(frame, "ack", ack)) // separate P-ready ACK precedes fixture collection
                                    readyBoundary.send(frame)
                                } else h.ipc.accept(h.reply(frame, "ack", ack))
                            }
                        }
                    }
                }
            }
            try {
                withTimeout(10000) { atWrite.await() }
                assertFalse(Files.exists(output), "No file before normal late write path")
                assertTrue(readyBoundary.tryReceive().isFailure, "No ready event before writing")
                if (mode == "cancel-before-write") {
                    task.cancelAndJoin(); assertTrue(task.isCancelled)
                    assertFalse(Files.exists(output)); assertTrue(readyBoundary.tryReceive().isFailure); assertEquals(0, artifactEvents)
                    return@runBlocking
                }
                permitWrite.complete(Unit)
                val boundaryFrame = withTimeout(10000) { readyBoundary.receive() }
                val boundary = boundaryFrame.obj("payload").obj("payload")
                assertEquals(policy.hash, boundary.string("policySha256"))
                assertEquals("root", boundary.string("producerWorkflowId")); assertEquals(binding.string("executionId"), boundary.string("executionId"))
                assertEquals("write", boundary.string("stepName")); assertEquals(listOf("decl-model"), boundary.array("declarationIds").map { it.jsonPrimitive.content })
                assertEquals(withTimeout(1000) { started.await() }, boundary.string("producerStartEventId"))
                assertTrue(Files.isRegularFile(output) && Files.size(output) > 0L, "Formal ready follows actual typed write_to; no polling authority")
                delay(150) // Controlled S snapshot is triggered only after the separately ACKed formal notification.
                assertFalse(task.isCompleted, "Agent must wait after its real write_to write"); assertTrue(modelCalls > 0)
                if (mode == "cancel") {
                    task.cancelAndJoin(); assertTrue(task.isCancelled); assertEquals(0, artifactEvents)
                } else if (mode.endsWith("timeout")) {
                    val result = withTimeout(5000) { task.await() }
                    assertFalse(result.success); assertTrue(result.error.orEmpty().contains("timed out"), result.error); assertEquals(0, artifactEvents)
                    val elapsed = (System.nanoTime() - launchedAt) / 1_000_000
                    assertTrue(elapsed in 2500..3700, "Original 3s budget includes the 900ms model call; elapsed=$elapsed")
                } else {
                    val bytes = Files.readAllBytes(output)
                    val f = CollectionFixture(binding, bundleJson, policyJson, policy.hash)
                    val original = collectFrame(f, "root", boundary.string("producerStartEventId"), bytes.size, sha256(bytes))
                    val request = JsonObject(original + ("payload" to JsonObject(original.obj("payload") + mapOf("declarationId" to JsonPrimitive("decl-model"), "producerStep" to JsonPrimitive("write"), "logicalPath" to JsonPrimitive("run/model.json")))))
                    h.ipc.accept(request)
                    val complete = withTimeout(3000) { completed.receive() }
                    assertEquals(sha256(bytes), complete.obj("payload").string("sha256"))
                    val result = withTimeout(3000) { task.await() }; assertTrue(result.success, result.error); assertEquals(1, artifactEvents)
                }
            } finally { task.cancel(); pump.cancel(); runtime.close(); h.supervisorInput.close(); http.close() }
        }
    }
}
