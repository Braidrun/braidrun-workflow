package com.fartech.agents.runner

import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** iterate_over on a real Runner host: HTTP model agents leasing their key per item, no real model or account. */
class RunnerLoopTest {
    private companion object {
        val MODEL_AGENT = """
            |  reviewer:
            |    strategy: single_run
            |    llm:
            |      model: gpt-4o-mini
            |      provider: openai""".trimMargin()
    }
    private fun bundle(dir: Path, source: String): Pair<FrozenWorkflowBundle, JsonObject> {
        Files.writeString(dir.resolve("root.yaml"), source); val hash = sha256(source.toByteArray())
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val metadata = buildJsonObject { put("rootWorkflowId", "root")
            put("files", JsonArray(listOf(buildJsonObject { put("path", "root.yaml"); put("size", source.toByteArray().size); put("sha256", hash); put("role", "definition") })))
            put("definitions", JsonArray(listOf(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }))) }
        return FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding) to binding
    }
    private fun agentLoop(items: Int, maxParallel: Int) = """
        name: loop-fixture
        agents:
          reviewer:
            strategy: single_run
            llm:
              model: gpt-4o-mini
              provider: openai
            overrides:
              openai_api_key: credential:model@1
        workflow:
          - step: review
            idempotent: true
            agent: reviewer
            iterate_over:
              source: "${(0 until items).joinToString(",") { "chunk-$it" }}"
              delimiter: ","
              item_variable: chunk
              parallel: true
              max_parallel: $maxParallel
              results_variable: reviews
            input: "Review {{var:chunk}}"
    """.trimIndent()

    private fun admit(step: String, agents: String = MODEL_AGENT, capabilities: Set<String> = RunnerHostSession.CAPABILITIES): Set<String> =
        RunnerAdmission(NullWorkflowResolver, capabilities).inspect(WorkflowParser.parseYaml("name: loop-admission\nagents:\n$agents\nworkflow:\n$step"))
    private fun loopStep(extra: String = "", loop: String = "parallel: true\n      max_parallel: 3", item: String = "agent: reviewer\n    input: \"Review {{var:chunk}}\"") = """
        |  - step: review
        |    idempotent: true
        |    $item
        |    iterate_over:
        |      source: "a,b,c"
        |      delimiter: ","
        |      item_variable: chunk
        |      $loop
        |$extra""".trimMargin().trimEnd()
    private fun refused(step: String, agents: String = MODEL_AGENT, capabilities: Set<String> = RunnerHostSession.CAPABILITIES): String =
        assertFailsWith<IllegalArgumentException> { admit(step, agents, capabilities) }.message.orEmpty()

    @Test fun `the Runner advertises step loop v1 and admits the bounded HTTP-model and code loops the optimizer uses`() {
        assertTrue(RunnerLoopPolicy.CAPABILITY in RunnerHostSession.CAPABILITIES)
        assertTrue(RunnerLoopPolicy.CAPABILITY in admit(loopStep()))
        admit(loopStep(loop = "parallel: true\n      max_parallel: 1"))
        admit(loopStep(loop = "parallel: false"))
        admit(loopStep(loop = "parallel: true\n      max_parallel: 8\n      max_items: 200"))
        admit(loopStep(item = "code:\n      language: bash\n      script: echo item"))
        assertTrue(refused(loopStep(), capabilities = RunnerHostSession.CAPABILITIES - RunnerLoopPolicy.CAPABILITY).contains("step.loop.v1"))
    }

    @Test fun `loop admission refuses unbounded, non-idempotent and unsupported loop shapes before any effect`() {
        assertTrue(refused(loopStep().replace("    idempotent: true\n", "")).contains("idempotent"))
        assertTrue(refused(loopStep(loop = "parallel: true")).contains("max_parallel"))
        assertTrue(refused(loopStep(loop = "parallel: true\n      max_parallel: 9")).contains("max_parallel"))
        assertTrue(refused(loopStep(loop = "parallel: true\n      max_parallel: 0")).contains("max_parallel"))
        assertTrue(refused(loopStep(loop = "parallel: false\n      max_parallel: 12")).contains("max_parallel"))
        assertTrue(refused(loopStep(loop = "parallel: true\n      max_parallel: 3\n      max_items: 201")).contains("max_items"))
        assertTrue(refused(loopStep(extra = "    manual_approval:\n      enabled: true")).contains("approval"))
        assertTrue(refused(loopStep(extra = "    retry:\n      max_attempts: 2")).contains("retry"))
        assertTrue(refused(loopStep(extra = "    publish_outputs:\n      - name: reviews\n        source: x")).contains("outputs"))
        val repeat = """
            |  - step: refine
            |    idempotent: true
            |    agent: reviewer
            |    input: refine
            |    repeat_until:
            |      condition: "done == true"
            |      max_iterations: 2""".trimMargin()
        assertTrue(refused(repeat).contains("repeat_until"))
        val cli = """
            |  reviewer:
            |    type: codex_agent
            |    overrides:
            |      external_agent_codex_auth_mode: subscription
            |      external_agent_codex_auth_json: credential:account@3""".trimMargin()
        assertTrue(refused(loopStep(), agents = cli).contains("CLI agent"))
    }

    @Test fun `runtime fan-out is bounded once the items are known`() {
        RunnerLoopPolicy.admitRuntime("review", 200, 8); RunnerLoopPolicy.admitRuntime("review", 0, 1)
        assertTrue(assertFailsWith<IllegalArgumentException> { RunnerLoopPolicy.admitRuntime("review", 201, 3) }.message!!.contains("at most 200"))
        assertFailsWith<IllegalArgumentException> { RunnerLoopPolicy.admitRuntime("review", 5, 9) }
        assertFailsWith<IllegalArgumentException> { RunnerLoopPolicy.admitRuntime("review", 5, 0) }
    }

    @Test fun `parallel agent items hold at most max_parallel model leases and each item releases its own at its end`(@TempDir dir: Path) = runBlocking<Unit> {
        val (bundle, binding) = bundle(dir, agentLoop(items = 7, maxParallel = 3))
        val inFlight = AtomicInteger(); val peakCalls = AtomicInteger()
        val client = HttpClient(MockEngine) { engine { addHandler {
            peakCalls.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try { delay(120) } finally { inFlight.decrementAndGet() }
            respond("""{"id":"fixture","object":"chat.completion","created":1760000000,"model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"reviewed"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } } }
        IpcHarness(binding).use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("jvm-http"))
            val evidence = RunnerProtocolTest.environment(h.binding, Instant.now())
            gate.install(JsonObject(evidence + ("egress" to JsonArray(evidence.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("jvm-http"))) }))))
            val secrets = RunnerSecrets()
            val identity = WorkflowHostBinding(h.binding.string("ownerId"), h.binding.string("actorUserId"), "billing", h.binding.string("executionId"), h.binding.string("runnerId"), h.binding.string("runnerInstanceId"), h.binding.long("resumeEpoch"), h.binding.string("attemptId"), h.binding.string("definitionHash"), h.binding.string("runtimeHash"))
            val noProcess = object : SubprocessExecutor { override suspend fun execute(request: SubprocessExecutor.ExecRequest) = error("A model loop never launches a process") }
            val normal = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, noProcess, RunnerCheckpointStore(dir, h.binding, secrets), secrets, validateDirectNetwork = {})
            val host = object : WorkflowHost by normal { override val llmHttpClientFactory = KtorKoogHttpClient.Factory(client) }
            val held = ConcurrentHashMap.newKeySet<String>(); val peakHeld = AtomicInteger(); val granted = AtomicInteger()
            val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val heldAtParentEnd = AtomicInteger(-1)
            val pump = launch(Dispatchers.Default) {
                while (isActive) {
                    val frame = h.next(); val payload = frame.obj("payload")
                    when (frame.string("type")) {
                        "credentialRequest" -> {
                            val id = "lease-${granted.incrementAndGet()}"
                            held += id; peakHeld.accumulateAndGet(held.size, ::maxOf)
                            h.ipc.accept(h.reply(frame, "credentialGrant", buildJsonObject {
                                put("requestId", payload.string("requestId")); put("credentialLeaseId", id); put("credentialRef", payload.string("credentialRef"))
                                put("version", payload.long("expectedVersion")); put("revision", 1); put("expiresAt", Instant.now().plusSeconds(60).toString())
                                put("allowedOperations", JsonArray(listOf(JsonPrimitive("llm.request")))); put("material", buildJsonObject { put("apiKey", "synthetic-model-key-$id") })
                            }))
                        }
                        "event" -> {
                            val body = payload.obj("payload"); events += JsonObject(body + ("type" to payload.getValue("type")))
                            if (payload.string("type") == "usage" && body["outcome"]?.jsonPrimitive?.content == "RELEASED") held -= body.string("credentialLeaseId")
                            if (body["kind"]?.jsonPrimitive?.content == "step_completed" && body["stepName"]?.jsonPrimitive?.content == "review") heldAtParentEnd.set(held.size)
                        }
                    }
                }
            }
            try {
                val result = withTimeout(20_000) { withContext(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = host, jevEnvKeyFallback = false).execute(bundle.rootDefinition) } }
                assertTrue(result.success, result.error)
                assertEquals(7, result.variables["reviews"].toString().split("\n---\n").size)
                withTimeout(2000) { while (heldAtParentEnd.get() < 0) delay(10) }
                assertTrue(peakCalls.get() in 2..3, "items run concurrently, never above max_parallel (saw ${peakCalls.get()})")
                assertEquals(7, granted.get(), "one model lease per item")
                assertTrue(peakHeld.get() <= 3, "at most max_parallel item leases are held at once (saw ${peakHeld.get()}): ${events.filter { it["kind"] == null || it["kind"]?.jsonPrimitive?.content?.startsWith("occurrence") == true || it["kind"]?.jsonPrimitive?.content?.startsWith("step_") == true }.map { it.toString().take(300) }}")
                assertEquals(0, heldAtParentEnd.get(), "every item released its lease at its own end, before the loop step completed")
                val lifecycle = events.filter { it["kind"]?.jsonPrimitive?.content in setOf("occurrence_started", "occurrence_completed") }
                assertEquals((0 until 7).map { "iterate:$it" }.toSet(), lifecycle.map { it.string("occurrence") }.toSet())
                lifecycle.forEach { assertEquals("review:${it.string("occurrence")}", it.string("stepName")) }
                var open = 0; var peakOpen = 0
                lifecycle.forEach { open += if (it.string("kind") == "occurrence_started") 1 else -1; peakOpen = maxOf(peakOpen, open) }
                assertEquals(0, open); assertTrue(peakOpen <= 3)
                assertTrue(lifecycle.filter { it.string("kind") == "occurrence_completed" }.all { it["success"] == JsonPrimitive(true) })
                assertFalse(events.toString().contains("synthetic-model-key"), "leased keys never reach the journal")
            } finally { pump.cancelAndJoin(); normal.close(); client.close(); h.supervisorInput.close() }
        }
    }
}
