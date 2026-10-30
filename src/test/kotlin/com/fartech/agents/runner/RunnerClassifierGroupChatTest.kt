package com.fartech.agents.runner

import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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

/**
 * Classifier and group chat on a Runner (step.classifier.v1 / step.groupChat.v1), and repeat_until
 * (step.repeatUntil.v1): admission rules, and real Runner-host runs whose every derived agent call leases its own
 * model key and releases it when that call ends. No real model, account or network.
 */
class RunnerClassifierGroupChatTest {
    private companion object {
        val MODEL_AGENTS = """
            |  router:
            |    strategy: single_run
            |    llm:
            |      model: gpt-4o-mini
            |      provider: openai
            |  alice:
            |    strategy: single_run
            |    llm:
            |      model: gpt-4o-mini
            |      provider: openai
            |  bob:
            |    strategy: single_run
            |    llm:
            |      model: gpt-4o-mini
            |      provider: openai""".trimMargin()
        val CLASSIFY = """
            |  - step: classify
            |    classifier:
            |      agent: router
            |      input: "route this"
            |      categories:
            |        - {name: billing, description: "Payments"}
            |        - {name: other, description: "Anything else"}
            |      default_category: other""".trimMargin()
        val DISCUSS = """
            |  - step: discuss
            |    input: "discuss {{var:classification}}"
            |    group_chat:
            |      participants: [alice, bob]
            |      max_rounds: 2
            |      termination_keyword: null
            |      summary_agent: alice""".trimMargin()
    }

    private fun admit(workflow: String, capabilities: Set<String> = RunnerHostSession.CAPABILITIES, agents: String = MODEL_AGENTS) =
        RunnerAdmission(NullWorkflowResolver, capabilities).inspect(WorkflowParser.parseYaml("name: cg-admission\nagents:\n$agents\nworkflow:\n$workflow"))
    private fun refused(workflow: String, capabilities: Set<String> = RunnerHostSession.CAPABILITIES): String =
        assertFails { admit(workflow, capabilities) }.message.orEmpty()

    private fun orchestrated(maxSteps: Int = 6, name: String = "orchestrate") = """
        |  - step: $name
        |    agent_based:
        |      goal: "write the release note"
        |      participants: [alice, bob]
        |      max_steps: $maxSteps
        |      orchestrator:
        |        strategy: single_run
        |        llm:
        |          model: gpt-4o-mini
        |          provider: openai""".trimMargin()

    @Test fun `the Runner advertises classifier, group chat and agent_based but not Jev`() {
        val declared = RunnerHostSession.CAPABILITIES
        assertTrue("step.classifier.v1" in declared && "step.groupChat.v1" in declared && "step.agentBased.v1" in declared)
        assertFalse("decision.jev.v1" in declared, "Jev needs a leased key and the gated egress path first")
        assertTrue("step.classifier.v1" in admit(CLASSIFY))
        assertTrue("step.groupChat.v1" in admit(DISCUSS))
        assertTrue("step.agentBased.v1" in admit(orchestrated()))
        assertTrue(refused(CLASSIFY, declared - "step.classifier.v1").contains("step.classifier.v1"))
        assertTrue(refused(DISCUSS, declared - "step.groupChat.v1").contains("step.groupChat.v1"))
        assertTrue(refused(orchestrated(), declared - "step.agentBased.v1").contains("step.agentBased.v1"))
    }

    @Test fun `an agent_based step is bounded and keeps its delegations' derived scope free`() {
        admit(orchestrated(maxSteps = RunnerAdmission.MAX_AGENT_BASED_STEPS))
        assertTrue(refused(orchestrated(maxSteps = RunnerAdmission.MAX_AGENT_BASED_STEPS + 1)).contains("max_steps"))
        val clash = "\n  - step: \"orchestrate:orchestrated:1\"\n    code:\n      language: bash\n      script: echo clash"
        assertTrue(refused(orchestrated() + clash).contains("orchestrate:"))
        // The orchestrator is inspected like any agent: a plaintext key is refused, a leased reference admitted.
        assertTrue(refused(orchestrated() + "\n        overrides:\n          openai_api_key: sk-plaintextplaintextplaintext").contains("leased"))
        admit(orchestrated() + "\n        overrides:\n          openai_api_key: credential:model@1")
    }

    @Test fun `a Jev classifier needs decision jev v1 at the root and inside a state machine`() {
        val jev = """
            |  - step: route
            |    classifier:
            |      input: "route this"
            |      categories:
            |        - {name: a, description: "A"}
            |        - {name: b, description: "B"}
            |      jev: {}""".trimMargin()
        assertTrue(refused(jev).contains("decision.jev.v1"))
        val nested = """
            |  - step: machine
            |    state_machine:
            |      initial_state: route
            |      states:
            |        route:
            |          name: route
            |          step:
            |            classifier:
            |              input: "route this"
            |              categories:
            |                - {name: a, description: "A"}
            |                - {name: b, description: "B"}
            |              jev: {}
            |          is_final: true""".trimMargin()
        val parsed = runCatching { WorkflowParser.parseYaml("name: sm\nagents:\n$MODEL_AGENTS\nworkflow:\n$nested") }
        assertTrue(parsed.isSuccess, "fixture must parse: ${parsed.exceptionOrNull()?.message}")
        // Before this RC a nested Jev classifier passed on step.classifier.v1 alone.
        assertTrue(refused(nested).contains("decision.jev.v1"))
    }

    @Test fun `a classifier or group chat step refuses a real step living under its derived prefix`() {
        val clash = "\n  - step: \"discuss:x\"\n    code:\n      language: bash\n      script: echo clash"
        assertTrue(refused(DISCUSS + clash).contains("discuss:"))
        assertTrue(refused(CLASSIFY + clash.replace("discuss", "classify")).contains("classify:"))
        admit(DISCUSS + clash.replace("discuss:x", "discuss2") + clash.replace("discuss:x", "discuss_x"))
    }

    private fun bundle(dir: Path, source: String): Pair<FrozenWorkflowBundle, JsonObject> {
        Files.writeString(dir.resolve("root.yaml"), source); val hash = sha256(source.toByteArray())
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val metadata = buildJsonObject { put("rootWorkflowId", "root")
            put("files", JsonArray(listOf(buildJsonObject { put("path", "root.yaml"); put("size", source.toByteArray().size); put("sha256", hash); put("role", "definition") })))
            put("definitions", JsonArray(listOf(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }))) }
        return FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding) to binding
    }

    private fun leasedAgents(vararg names: String) = names.joinToString("\n") { """
        |  $it:
        |    strategy: single_run
        |    llm:
        |      model: gpt-4o-mini
        |      provider: openai
        |    overrides:
        |      openai_api_key: credential:model@1""".trimMargin() }

    private class Run(val result: WorkflowExecutionResult, val granted: Int, val peakHeld: Int, val heldAtStepEnd: Map<String, Int>, val journal: String, val calls: List<String>)

    /** Runs [source] on a real RunnerWorkflowHost, granting every credentialRequest and tracking releases. */
    private fun runOnHost(dir: Path, source: String, reply: (String) -> String): Run = runBlocking {
        val (bundle, binding) = bundle(dir, source)
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            val body = String(request.body.toByteArray())
            calls += body
            val content = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(reply(body)))
            respond("""{"id":"fixture","object":"chat.completion","created":1760000000,"model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":$content},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } } }
        IpcHarness(binding).use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("jvm-http"))
            val evidence = RunnerProtocolTest.environment(h.binding, Instant.now())
            gate.install(JsonObject(evidence + ("egress" to JsonArray(evidence.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("jvm-http"))) }))))
            val secrets = RunnerSecrets()
            val identity = WorkflowHostBinding(h.binding.string("ownerId"), h.binding.string("actorUserId"), "billing", h.binding.string("executionId"), h.binding.string("runnerId"), h.binding.string("runnerInstanceId"), h.binding.long("resumeEpoch"), h.binding.string("attemptId"), h.binding.string("definitionHash"), h.binding.string("runtimeHash"))
            val noProcess = object : SubprocessExecutor { override suspend fun execute(request: SubprocessExecutor.ExecRequest) = error("A model workflow never launches a process") }
            val normal = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, noProcess, RunnerCheckpointStore(dir, h.binding, secrets), secrets, validateDirectNetwork = {})
            val host = object : WorkflowHost by normal { override val llmHttpClientFactory = KtorKoogHttpClient.Factory(client) }
            val held = ConcurrentHashMap.newKeySet<String>(); val peakHeld = AtomicInteger(); val granted = AtomicInteger()
            val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val heldAtStepEnd = ConcurrentHashMap<String, Int>()
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
                            if (body["kind"]?.jsonPrimitive?.content == "step_completed") body["stepName"]?.jsonPrimitive?.content?.let { heldAtStepEnd[it] = held.size }
                        }
                    }
                }
            }
            try {
                val result = withTimeout(30_000) { withContext(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = host, jevEnvKeyFallback = false).execute(bundle.rootDefinition) } }
                delay(200) // let the last release events arrive
                Run(result, granted.get(), peakHeld.get(), heldAtStepEnd.toMap(), events.toString(), calls.toList())
            } finally { pump.cancelAndJoin(); normal.close(); client.close(); h.supervisorInput.close() }
        }
    }

    @Test fun `a classifier and a group chat complete on a Runner host with one model lease per agent call, each released at its own end`(@TempDir dir: Path) {
        val source = "name: cg-run\nagents:\n${leasedAgents("router", "alice", "bob")}\nworkflow:\n$CLASSIFY\n$DISCUSS\n"
        val run = runOnHost(dir, source) { body ->
            when {
                "billing" in body && "Payments" in body -> "billing"
                "summar" in body.lowercase() || "总结" in body || "摘要" in body -> "the summary"
                else -> "a turn"
            }
        }
        assertTrue(run.result.success, run.result.error)
        assertEquals("billing", run.result.variables["classification"]?.toString())
        // classifier 1 + 2 rounds x 2 participants + summary 1, each its own derived scope and lease.
        assertEquals(6, run.calls.size, run.calls.joinToString("\n---\n") { it.take(200) })
        assertEquals(6, run.granted, "one model lease per derived agent call")
        assertEquals(1, run.peakHeld, "each call's lease ends with the call, so a chat holds one lease at a time")
        assertEquals(0, run.heldAtStepEnd["classify"], "the classifier's lease was released before its step completed")
        assertEquals(0, run.heldAtStepEnd["discuss"], "every turn and the summary released their lease before the step completed")
        assertFalse(run.journal.contains("synthetic-model-key"), "leased keys never reach the journal")
    }

    @Test fun `repeat_until rounds run on a Runner host and each evaluator round releases its own lease`(@TempDir dir: Path) {
        val source = """
            |name: repeat-run
            |agents:
            |${leasedAgents("writer", "judge")}
            |workflow:
            |  - step: refine
            |    agent: writer
            |    input: "write"
            |    repeat_until:
            |      condition: "quality_score >= 3"
            |      max_iterations: 4
            |      evaluate_agent: judge
            |      evaluate_prompt: "JUDGE {{steps.refine.output}}"
            |      extract_pattern: "quality_score=(\\d+)"
            |      extract_variable: quality_score
            |""".trimMargin()
        val rounds = AtomicInteger()
        val run = runOnHost(dir, source) { body -> if ("JUDGE" in body) "quality_score=${rounds.incrementAndGet()}" else "draft" }
        assertTrue(run.result.success, run.result.error)
        assertEquals(3, rounds.get(), "the loop stops when the evaluator reaches the condition")
        assertEquals("3", run.result.variables["quality_score"]?.toString())
        // The body's lease is keyed by the step and reused across rounds; every evaluator round leases its own.
        assertEquals(1 + 3, run.granted)
        assertTrue(run.peakHeld <= 2, "body + the current evaluator at most (saw ${run.peakHeld})")
        assertEquals(0, run.heldAtStepEnd["refine"], "the evaluator leases end with their rounds and the body's with the step")
        assertFalse(run.journal.contains("synthetic-model-key"))
    }
}
