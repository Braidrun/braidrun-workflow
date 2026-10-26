package com.fartech.agents.runner

import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RunnerLlmContextTest {
    @Test fun `existing agent interpreter uses host model transport and emits usage without environment key fallback`(@TempDir directory: Path) = runBlocking {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            calls.add(request.headers[HttpHeaders.Authorization].orEmpty())
            respond("""{"id":"fixture","object":"chat.completion","created":1760000000,"model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"host-model-proof"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } } }
        val updates = mutableListOf<WorkflowMonitorUpdate>()
        val host = object : WorkflowHost {
            override val binding = WorkflowHostBinding("owner", "actor", "billing", "root-llm", "runner", "instance", 0, "attempt", "a".repeat(64), "b".repeat(64))
            override val llmHttpClientFactory = KtorKoogHttpClient.Factory(client)
            override val approvalHandler = object : ApprovalHandler { override suspend fun requestApproval(request: ApprovalRequest) = ApprovalDecision.REJECTED }
            override val outputPublisher = object : WorkflowOutputPublisher { override suspend fun publish(output: PublishedWorkflowOutputPayload) {} }
            override val outputResolver = object : WorkflowOutputResolver { override suspend fun read(request: WorkflowOutputReadRequest) = error("unused") }
            override val workflowResolver = NullWorkflowResolver
            override val subprocessExecutor = object : SubprocessExecutor { override suspend fun execute(request: SubprocessExecutor.ExecRequest) = error("Model must not execute a process") }
            override suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope) {}
            override suspend fun beforeOperation(scope: WorkflowHostScope, operation: String) { assertEquals("root-llm", scope.rootExecutionId) }
            override fun userInteraction(scope: WorkflowHostScope): UserInteractionHandler = error("unused")
            override fun dynamicWorkflow(path: String, scope: WorkflowHostScope): WorkflowDefinition = error("unused")
            override fun monitor(update: WorkflowMonitorUpdate) { updates.add(update) }
            override suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint) {}
            override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<ConfigurationParameter>) = parameters + listOf(
                ConfigurationParameter("openai_api_key", JsonPrimitive("fixture-scoped-key")), ConfigurationParameter("disable_skills", JsonPrimitive(true)))
        }
        try {
            val workflow = WorkflowDefinition("model", agents = mapOf("model" to AgentDefinition(strategy = "single_run", llm = LLMConfiguration("gpt-4o-mini", "openai"))),
                directoryIsolation = DirectoryIsolationConfig(baseDir = directory.resolve("runs").toString(), sharedSkillsDir = directory.resolve("skills").toString(), sharedHistoryDir = directory.resolve("history").toString(), sharedCacheDir = directory.resolve("cache").toString()),
                workflow = listOf(WorkflowStep("model-proof", agent = "model", input = "Reply with host-model-proof")))
            val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
            assertTrue(result.success, result.error); assertEquals("host-model-proof", result.stepResults.getValue("model-proof").output)
            assertTrue(calls.isNotEmpty()); assertTrue(calls.all { it.contains("fixture-scoped-key") })
            assertTrue(updates.any { it.event?.type == "llm_call_completed" && it.event.totalTokens == 13 })
        } finally { client.close() }
    }
}
