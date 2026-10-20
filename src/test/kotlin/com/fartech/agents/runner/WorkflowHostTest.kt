package com.fartech.agents.runner

import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class WorkflowHostTest {
    internal class Host(private val directory: Path) : WorkflowHost {
        override val binding = WorkflowHostBinding("owner", "actor", "billing", "root", "runner", "instance", 0, "attempt", "a".repeat(64), "b".repeat(64))
        val checks = mutableListOf<Pair<WorkflowHostScope, String>>()
        val checkpoints = mutableListOf<WorkflowHostCheckpoint>()
        val updates = mutableListOf<WorkflowMonitorUpdate>()
        val invocations = mutableListOf<Map<String, String>>()
        val approvals = mutableListOf<ApprovalRequest>()
        var child: WorkflowDefinition? = null
        var dynamicAllowed = true
        var block = false
        val entered = CompletableDeferred<Unit>()
        override val approvalHandler = object : ApprovalHandler {
            override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
                approvals.add(request); return ApprovalDecision.APPROVED_AS_IS
            }
        }
        override val outputPublisher = object : WorkflowOutputPublisher { override suspend fun publish(output: PublishedWorkflowOutputPayload) {} }
        override val outputResolver = object : WorkflowOutputResolver { override suspend fun read(request: WorkflowOutputReadRequest) = WorkflowOutputReadResult("source-exec", "source-workflow", mapOf("value" to "inherited")) }
        override val subprocessExecutor = object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                invocations.add(request.env); entered.complete(Unit)
                if (block) awaitCancellation()
                return SubprocessExecutor.ExecResult(0, "done", "", 1)
            }
        }
        override val workflowResolver: WorkflowResolver get() = InMemoryWorkflowResolver(byName = child?.let { mapOf(it.name to it) }.orEmpty())
        override suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope) { checks.add(scope to "admit") }
        override suspend fun beforeOperation(scope: WorkflowHostScope, operation: String) { checks.add(scope to operation) }
        override fun userInteraction(scope: WorkflowHostScope): UserInteractionHandler = object : UserInteractionHandler {
            override fun sendMessage(message: String) {}
            override suspend fun askQuestion(question: String, timeoutMs: Long) = "reply"
        }
        override fun dynamicWorkflow(path: String, scope: WorkflowHostScope): WorkflowDefinition {
            check(dynamicAllowed) { "Unapproved dynamic hash" }; return requireNotNull(child)
        }
        override fun monitor(update: WorkflowMonitorUpdate) { updates.add(update) }
        override suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint) { checkpoints.add(checkpoint) }
        fun isolation() = DirectoryIsolationConfig(baseDir = directory.resolve("runs").toString(), sharedCacheDir = directory.resolve("cache").toString(), sharedHistoryDir = directory.resolve("history").toString(), sharedSkillsDir = directory.resolve("skills").toString())
        fun workflow(name: String, approval: Boolean = false) = WorkflowDefinition(name, agents = emptyMap(), directoryIsolation = isolation(), workflow = listOf(
            WorkflowStep("code", code = CodeStepConfig("bash", "echo done"), manualApproval = if (approval) ManualApprovalConfig(true) else null)
        ))
    }
    @Test fun `static and dynamic children inherit root identity approvals monitoring and supervision`(@TempDir dir: Path) = runBlocking {
        val host = Host(dir); val child = host.workflow("child", true); host.child = child
        val root = WorkflowDefinition("parent", agents = emptyMap(), directoryIsolation = host.isolation(), workflow = listOf(WorkflowStep("nested", subWorkflow = SubWorkflowConfig(name = "child"))))
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(root)
        assertTrue(result.success, result.error)
        assertEquals("root__nested__call_1", host.approvals.single().executionId)
        assertEquals("root", host.invocations.single()["WF_ROOT_EXECUTION_ID"])
        assertTrue(host.updates.any { it.executionId == "root__nested__call_1" && it.type == "step_started" })
        assertEquals(mapOf("nested" to 1L), host.checkpoints.last { it.scope.executionId == "root" && it.safeToResume }.state.childInvocationCounts)
        val tools = WorkflowTools(HttpAccess(), emptyList(), NestedWorkflowRuntime(host = host), WorkflowHostScope("root", "root", stepName = "agent"))
        assertTrue(tools.executeWorkflow("authorized.yaml").contains("success", true))
        assertTrue(host.approvals.last().executionId.startsWith("root__agent_dynamic_"))
        assertEquals("root", host.invocations.last()["WF_ROOT_EXECUTION_ID"])
        assertEquals("root", host.invocations.last()["WF_PARENT_EXECUTION_ID"])
        val before = host.invocations.size; host.dynamicAllowed = false
        assertTrue(tools.executeWorkflow("changed.yaml").contains("refused")); assertEquals(before, host.invocations.size)
        assertFailsWith<IllegalArgumentException> { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(root, externalExecutionId = "different-root") }
    }
    @Test fun `state machine child reentry needs a new approval and preserves invocation counts`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir); base.child = base.workflow("child", true)
        val second = CompletableDeferred<ApprovalRequest>(); val requests = mutableListOf<ApprovalRequest>()
        val host = object : WorkflowHost by base {
            override val approvalHandler = object : ApprovalHandler {
                override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
                    requests.add(request)
                    if (requests.size == 1) return ApprovalDecision.APPROVED_AS_IS
                    second.complete(request); awaitCancellation()
                }
            }
        }
        val root = WorkflowDefinition("loop-parent", agents = emptyMap(), directoryIsolation = base.isolation(), workflow = listOf(
            WorkflowStep("machine", stateMachine = StateMachineConfig(states = mapOf(
                "child" to StateDefinition("child", stepConfig = StateStepConfig(subWorkflow = SubWorkflowConfig(name = "child")), transitions = listOf(StateTransition("complete", "control"))),
                "control" to StateDefinition("control", transitions = listOf(StateTransition("enter", "child")))
            ), initialState = "child", maxTransitions = 5))
        ))
        val running = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(root) }
        withTimeout(2000) { second.await() }
        assertEquals(1, base.invocations.size, "Second side effect must wait for its own human decision")
        assertNotEquals(requests[0].approvalId, requests[1].approvalId)
        assertTrue(requests[0].executionId.endsWith("__call_1")); assertTrue(requests[1].executionId.endsWith("__call_2"))
        running.cancelAndJoin()
        // In-flight FSM snapshots stay unsafe; the existing root boundary test below verifies persisted counters.
    }
    @Test fun `typed savepoint resumes without repeating a preserved effect`(@TempDir dir: Path) = runBlocking {
        val host = Host(dir); val workflow = host.workflow("resume")
        val first = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow, mapOf("count" to 7, "list" to listOf("a", "b")))
        assertTrue(first.success)
        val checkpoint = host.checkpoints.last { it.safeToResume }
        assertEquals(7, checkpoint.state.variables["count"])
        val resumed = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow, resumeState = checkpoint.state)
        assertTrue(resumed.success); assertEquals(1, host.invocations.size); assertEquals(7, resumed.variables["count"])
    }
    @Test fun `cancellation propagates to supervised operation and never starts next step`(@TempDir dir: Path) = runBlocking {
        val host = Host(dir); host.block = true
        val workflow = host.workflow("cancel").let { it.copy(workflow = it.workflow + WorkflowStep("later", code = CodeStepConfig("bash", "echo later"))) }
        val running = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow) }
        host.entered.await(); running.cancel(); running.join()
        assertEquals(1, host.invocations.size)
        assertFalse(host.checkpoints.last().safeToResume)
        assertTrue(host.updates.any { it.type == "execution_cancelled" })
    }
    class HostFileMarker : ai.koog.agents.core.tools.reflect.ToolSet {
        @ai.koog.agents.core.tools.annotations.Tool
        @ai.koog.agents.core.tools.annotations.LLMDescription("Host-owned file tool marker")
        fun hostFileMarker(): String = "marker"
    }
    @Test fun `host file system tools replace the library file tools in the agent registry`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir)
        val bodies = mutableListOf<String>()
        val mock = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine) { engine { addHandler { request ->
            val body = String(request.body.toByteArray()); synchronized(bodies) { bodies.add(body) }
            val reply = buildJsonObject {
                put("id", "test-only-model"); put("object", "chat.completion"); put("created", 1760000000); put("model", "gpt-4o-mini")
                put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("message", buildJsonObject { put("role", "assistant"); put("content", "done") }); put("finish_reason", "stop") }) })
                put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
            }
            respond(reply.toString(), io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        } } }
        val scopes = mutableListOf<WorkflowHostScope>()
        val host = object : WorkflowHost by base {
            override val llmHttpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(mock)
            override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>) = parameters + listOf(
                com.fartech.ftapp2.commonsKt.ConfigurationParameter("openai_api_key", JsonPrimitive("test-only-model-key")),
                com.fartech.ftapp2.commonsKt.ConfigurationParameter("disable_skills", JsonPrimitive(true)))
            override fun fileSystemTools(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<ai.koog.agents.core.tools.reflect.ToolSet> {
                scopes += scope; return listOf(HostFileMarker())
            }
        }
        val workflow = WorkflowParser.parseYaml("""
            name: hosted-files
            agents:
              model:
                strategy: single_run
                tools: [file_system]
                llm:
                  model: gpt-4o-mini
                  provider: openai
            workflow:
              - step: write
                agent: model
                input: Write the notes.
        """.trimIndent()).copy(directoryIsolation = base.isolation())
        try {
            val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
            assertTrue(result.success, result.error)
        } finally { mock.close() }
        val request = synchronized(bodies) { bodies.first() }
        assertTrue(request.contains("hostFileMarker"), request)
        for (library in listOf("readFile", "writeFile", "__write_file__", "__read_file__", "copyFile", "deleteFile")) assertFalse(request.contains("\"$library\""), library)
        assertEquals(listOf(WorkflowHostScope("root", "root", stepName = "write")), scopes)
    }
    @Test fun `closure admission refuses unknown dormant nested tools before effects`(@TempDir dir: Path) {
        val host = Host(dir)
        val child = WorkflowDefinition("unsafe", agents = mapOf("unknown" to AgentDefinition(tools = listOf("server_business_broker"))), workflow = listOf(WorkflowStep("unused", agent = "unknown", input = "ignored")))
        val resolver = InMemoryWorkflowResolver(byName = mapOf("unsafe" to child))
        val root = WorkflowDefinition("root", agents = emptyMap(), workflow = listOf(WorkflowStep("dormant", condition = "enabled == true", subWorkflow = SubWorkflowConfig(name = "unsafe"))))
        assertFailsWith<IllegalArgumentException> { RunnerAdmission(resolver, setOf("workflow.full.v1", "step.subWorkflow.v1", "step.agent.v1")).inspect(root) }
        assertTrue(host.invocations.isEmpty())
    }
    @Test fun `checkpoint hash typed variables old identity and leaked credential protections`(@TempDir dir: Path) {
        val old = RunnerProtocolTest.binding(); val secrets = RunnerSecrets()
        val store = RunnerCheckpointStore(dir, old, secrets)
        val state = WorkflowResumeState(variables = mapOf("number" to 5, "bool" to true, "nested" to listOf("a", 2)), stepOutputs = mapOf("step" to "output"), activatedTransitionSteps = setOf("next"), childInvocationCounts = mapOf("child" to 2L))
        val announcement = store.save(WorkflowHostCheckpoint(WorkflowHostScope(old.string("executionId"), old.string("executionId")), "test", state, true), 3)
        val new = JsonObject(old + mapOf("resumeEpoch" to JsonPrimitive(1), "attemptId" to JsonPrimitive("new-attempt"), "runnerInstanceId" to JsonPrimitive("new-instance")))
        val restored = RunnerCheckpointStore(dir, new, secrets).restore(announcement, old)
        assertEquals(state, restored)
        assertFailsWith<IllegalArgumentException> { store.restore(announcement, old) }
        secrets.register(JsonPrimitive("secret-value"))
        assertFailsWith<IllegalArgumentException> { store.save(WorkflowHostCheckpoint(WorkflowHostScope("root", "root"), "test", WorkflowResumeState(variables = mapOf("leak" to "secret-value")), true), 0) }
        val unsafe = store.save(WorkflowHostCheckpoint(WorkflowHostScope(old.string("executionId"), old.string("executionId")), "test", state, false), 0)
        assertFailsWith<IllegalArgumentException> { RunnerCheckpointStore(dir, new, secrets).restore(unsafe, old) }
    }
}
