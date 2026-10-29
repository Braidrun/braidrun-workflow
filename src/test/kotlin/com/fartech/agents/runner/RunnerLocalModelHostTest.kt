package com.fartech.agents.runner

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.fartech.agents.commons.buildAgent
import com.fartech.agents.commons.selectedHostLocalModel
import com.fartech.agents.commons.isHostLocalTextType
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RunnerLocalModelHostTest {
    private val localBinding = RunnerLocalModelBinding("runner", "owner", "root", "attempt", "local", "http://127.0.0.1:11434", "0.12.6", "fixture:8b", "a".repeat(64), 8192, 512)
    private val scope = WorkflowHostScope("root", "root", stepName = "answer")
    private val fixed = LLMParams(temperature = 0.0, maxTokens = 512, numberOfChoices = 1, toolChoice = LLMParams.ToolChoice.None)

    private inner class Fixture(dir: Path) {
        val base = WorkflowHostTest.Host(dir)
        val requests = mutableListOf<RunnerLocalModelRequest>()
        var factories = 0
        var closes = 0
        var promptTokens: Long? = 5
        var outputTokens: Long? = 1
        var failure = false
        var live = true
        var returnedScope: WorkflowHostScope? = null
        var returnedBinding: WorkflowHostBinding? = null
        val resolvedScopes = mutableListOf<WorkflowHostScope>()
        val host = object : WorkflowHost by base {
            override fun localModel(scope: WorkflowHostScope): WorkflowLocalModelSelection {
                check(live) { "fixture approval revoked" }
                resolvedScopes += scope
                val selectedScope = returnedScope ?: scope
                val childPath = if (scope.executionId == base.binding.executionId) emptyList() else scope.executionId.removePrefix(base.binding.executionId + "__").split("__")
                val executor = RunnerLocalModelPromptExecutor(localBinding, requireNotNull(scope.stepName), childPath,
                    complete = { request, validate ->
                        requests += request
                        check(!failure) { "fixture inference failed" }
                        RunnerLocalModelResult("accepted", localBinding.model, localBinding.digest, localBinding.serviceVersion, "request", 1, 2, promptTokens, outputTokens).also { validate(it.content) }
                    }, closeTransport = { closes++ }, checkLive = { check(live) }, validateOutput = {})
                return WorkflowLocalModelSelection(returnedBinding ?: base.binding, selectedScope, executor.localModel, fixed) { factories++; executor }
            }
            override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<ConfigurationParameter>): List<ConfigurationParameter> =
                error("Local model must not prepare cloud credentials")
            override fun claudeCredentialsFor(scope: WorkflowHostScope) = error("Local model must not ask for CLI credentials")
            override fun codexCredentialsFor(scope: WorkflowHostScope) = error("Local model must not ask for CLI credentials")
        }
        suspend fun <T> within(body: suspend () -> T): T = WorkflowHostContext.withHost(host) { WorkflowHostContext.withScope(scope) { body() } }
    }

    private fun assertUsageOnce(f: Fixture, executionId: String) {
        val events = f.base.updates.filter { it.executionId == executionId && it.stepName == "answer" }.mapNotNull { it.event }
        // This is the same distinction used by RunnerWorkflowHost when choosing usage vs step frames.
        val metered = events.filter { !it.isDisplayOnlyTokenEvent() && (it.inputTokens != null || it.outputTokens != null || it.totalTokens != null) }
        val usage = metered.single()
        assertEquals("llm_call_completed", usage.type)
        assertEquals(5, usage.inputTokens)
        assertEquals(1, usage.outputTokens)
        assertEquals(6, usage.totalTokens)
        assertEquals(1, events.count { it.isDisplayOnlyTokenEvent() }, "Display token event must not be counted again")
    }

    @Test fun `host selection precedes malformed cloud defaults MCP and caller strategy`(@TempDir dir: Path): Unit = runBlocking {
        val f = Fixture(dir)
        val parameters = listOf(ConfigurationParameter("llm_config", JsonPrimitive("must not parse")),
            ConfigurationParameter("mcp_servers", JsonPrimitive("must not prepare")),
            ConfigurationParameter("enable_persistence", JsonPrimitive(true)), ConfigurationParameter("enable_langfuse_tracing", JsonPrimitive(true)))
        f.within {
            val agent = buildAgent<String, String>(HttpAccess(), parameters, "Summarize", ToolRegistry {}, installFeatures = {},
                strategyBuilder = { _, _, _ -> error("Local model must not build a fallback or tool strategy") })
            try { assertEquals("accepted", agent.run("question")) } finally { agent.close() }
        }
        assertEquals(1, f.factories)
        assertEquals(1, f.requests.size)
        assertEquals(512, f.requests.single().outputTokens)
        assertEquals(listOf("system", "user"), f.requests.single().messages.map { it.role })
        assertEquals("Summarize", f.requests.single().messages.first().content)
        assertEquals(1, f.closes)
    }

    @Test fun `local failure has no cloud retry or fallback`(@TempDir dir: Path): Unit = runBlocking {
        val f = Fixture(dir); f.failure = true
        f.within {
            val agent = buildAgent<String, String>(HttpAccess(), emptyList(), "Text", ToolRegistry {}, installFeatures = {},
                strategyBuilder = { _, _, _ -> error("No fallback") })
            try { assertFails { agent.run("question") } } finally { agent.close() }
        }
        assertEquals(1, f.factories); assertEquals(1, f.requests.size); assertEquals(1, f.closes)
    }

    @Test fun `workflow parameters cannot enable local routing without a host`(): Unit = runBlocking {
        WorkflowHostContext.withHost(null) {
            WorkflowHostContext.withScope(scope) { assertNull(selectedHostLocalModel()) }
            val parameters = listOf(ConfigurationParameter("local_model", JsonPrimitive(true)), ConfigurationParameter("llm_config", JsonPrimitive("invalid")))
            assertFails { buildAgent<String, String>(HttpAccess(), parameters, "Text", ToolRegistry {}, installFeatures = {}, strategyBuilder = { _, _, _ -> error("invalid cloud config must fail") }) }
        }
    }

    @Test fun `revoked or wrong step selection refuses before executor factory`(@TempDir dir: Path): Unit = runBlocking {
        val f = Fixture(dir)
        f.returnedScope = scope.copy(stepName = "other")
        f.within { assertFails { selectedHostLocalModel() } }
        f.returnedScope = scope.copy(rootExecutionId = "other", executionId = "other")
        f.within { assertFails { selectedHostLocalModel() } }
        f.returnedScope = null
        f.returnedBinding = f.base.binding.copy(attemptId = "old-attempt")
        f.within { assertFails { selectedHostLocalModel() } }
        f.live = false
        f.within { assertFails { selectedHostLocalModel() } }
        assertEquals(0, f.factories)
    }

    @Test fun `host selection rejects cloud providers unbounded budgets and unfixed choices`(@TempDir dir: Path): Unit = runBlocking {
        val f = Fixture(dir)
        val valid = assertNotNull(f.host.localModel(scope))
        assertFails { valid.copy(model = valid.model.copy(provider = LLMProvider.OpenAI)) }
        assertFails { valid.copy(model = valid.model.copy(contextLength = null)) }
        assertFails { valid.copy(params = fixed.copy(temperature = 1.0)) }
        assertFails { valid.copy(params = fixed.copy(numberOfChoices = 2)) }
        assertFails { valid.copy(params = fixed.copy(maxTokens = 8192)) }
        assertFails { valid.copy(scope = scope.copy(occurrence = "iterate:0")) }
        for (child in listOf("root-evil__nested__call_1", "root/nested/call_1", "root____call_1", "root__nested__call_0", "root__nested__call_01", "root__nested", "root" + "__nested__call_1".repeat(17))) {
            assertFails { valid.copy(scope = scope.copy(executionId = child, parentExecutionId = "root")) }
        }
        assertFails { valid.copy(scope = scope.copy(executionId = "root__nested__call_1", parentExecutionId = "wrong")) }
    }

    @Test fun `full workflow local branch skips credential and MCP preparation and keeps monitoring`(@TempDir dir: Path): Unit = runBlocking {
        val f = Fixture(dir)
        val workflow = WorkflowDefinition("local", agents = mapOf("writer" to AgentDefinition(strategy = "single_run", systemPrompt = "Summarize")),
            workflow = listOf(WorkflowStep("answer", agent = "writer", input = "question")), directoryIsolation = f.base.isolation())
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = f.host).execute(workflow, externalExecutionId = "root")
        assertTrue(result.success, "Local root workflow failed: ${result.error}; steps=${result.stepResults}")
        assertEquals(1, f.factories); assertEquals(1, f.requests.size)
        assertTrue(f.base.updates.any { it.stepName == "answer" })
        assertTrue(f.base.checks.any { it.first.stepName == "answer" })
        assertUsageOnce(f, "root")
    }

    @Test fun `real static child workflow uses segment ordinal identity without cloud preparation`(@TempDir dir: Path): Unit = runBlocking {
        val f = Fixture(dir)
        val child = WorkflowDefinition("child", agents = mapOf("writer" to AgentDefinition(strategy = "single_run")),
            workflow = listOf(WorkflowStep("answer", agent = "writer", input = "question")), directoryIsolation = f.base.isolation())
        f.base.child = child
        val root = WorkflowDefinition("root", agents = emptyMap(), workflow = listOf(WorkflowStep("call_1", subWorkflow = SubWorkflowConfig(name = "child"))), directoryIsolation = f.base.isolation())
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = f.host).execute(root, externalExecutionId = "root")
        assertTrue(result.success, "Local child workflow failed: ${result.error}; steps=${result.stepResults}")
        assertEquals(1, f.factories); assertEquals(1, f.requests.size)
        val selected = f.resolvedScopes.first { it.stepName == "answer" }
        assertEquals("root__call_1__call_1", selected.executionId)
        assertEquals("root", selected.parentExecutionId)
        assertUsageOnce(f, selected.executionId)
        assertEquals(listOf("call_1", "call_1"), assertNotNull(f.host.localModel(selected)).childExecutionPath)
        assertEquals(listOf("call_1", "call_1"), f.requests.single().childExecutionPath)
    }

    @Test fun `text tokens compare represented type and refuse nullable and generic values`() {
        assertTrue(isHostLocalTextType(ai.koog.serialization.TypeToken.of(String::class.java)))
        assertTrue(isHostLocalTextType(ai.koog.serialization.TypeToken.of(String::class.java as java.lang.reflect.Type)))
        assertTrue(isHostLocalTextType(ai.koog.serialization.KotlinTypeToken(kotlin.reflect.typeOf<String>())))
        assertFalse(isHostLocalTextType(ai.koog.serialization.KotlinTypeToken(kotlin.reflect.typeOf<String?>())))
        assertFalse(isHostLocalTextType(ai.koog.serialization.KotlinTypeToken(kotlin.reflect.typeOf<List<String>>())))
        assertFalse(isHostLocalTextType(ai.koog.serialization.TypeToken.of(Int::class.java)))
        assertFalse(isHostLocalTextType(ai.koog.serialization.TypeToken.of(String::class.java, listOf(ai.koog.serialization.TypeToken.of(String::class.java)))))
    }
    @Test fun `real workflow monitor preserves local zero and partially unknown usage`(@TempDir dir: Path): Unit = runBlocking {
        listOf(null to 1L, 5L to null, 0L to 0L, null to null).forEachIndexed { index, (input, output) ->
            val f = Fixture(dir.resolve("case-$index")); f.promptTokens = input; f.outputTokens = output
            val workflow = WorkflowDefinition("local", agents = mapOf("writer" to AgentDefinition(strategy = "single_run")),
                workflow = listOf(WorkflowStep("answer", agent = "writer", input = "question")), directoryIsolation = f.base.isolation())
            val result = WorkflowExecutor(HttpAccess(), emptyList(), host = f.host).execute(workflow, externalExecutionId = "root")
            assertTrue(result.success, "Partial usage workflow failed: ${result.error}; steps=${result.stepResults}")
            val events = f.base.updates.filter { it.executionId == "root" && it.stepName == "answer" }.mapNotNull { it.event }
            val completed = events.single { it.type == "llm_call_completed" }
            assertEquals(input?.toInt(), completed.inputTokens)
            assertEquals(output?.toInt(), completed.outputTokens)
            assertEquals(if (input != null && output != null) (input + output).toInt() else null, completed.totalTokens)
            val known = input != null || output != null
            assertEquals(if (known) 1 else 0, events.count { !it.isDisplayOnlyTokenEvent() && (it.inputTokens != null || it.outputTokens != null || it.totalTokens != null) })
            val display = events.filter { it.isDisplayOnlyTokenEvent() }
            assertEquals(if (known) 1 else 0, display.size)
            if (known && (input == null || output == null)) assertTrue(display.single().summary.contains("未知"))
        }
    }

}
