package com.fartech.agents.runner

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import com.fartech.agents.commons.CapturingHttpClientFactory
import com.fartech.agents.tools.LLM
import com.fartech.agents.tools.SubAgentContext
import com.fartech.agents.workflow.WorkflowSubAgentRuntime
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RunnerSubAgentModelTest {
    private val model = LLModel(LLMProvider.OpenAI, "frozen", listOf(LLMCapability.Completion, LLMCapability.Tools), 8192, 512)
    private val params = LLMParams(maxTokens = 512, numberOfChoices = 1)
    private fun request(p: LLMParams = params) = prompt("test", p) { user("task") }
    private class Client : LLMClient() {
        var calls = 0; val closes = AtomicInteger()
        var action: suspend (Prompt, List<ToolDescriptor>) -> Message.Assistant = { _, _ -> Message.Assistant("ok", ResponseMetaInfo.Empty) }
        override val clientName = "fixture"
        override fun llmProvider() = LLMProvider.OpenAI
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant { calls++; return action(prompt, tools) }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("must not stream")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("must not moderate")
        override fun close() { closes.incrementAndGet() }
    }
    private fun executor(c: Client, budget: RunnerSubAgentBudget = RunnerSubAgentBudget(), guard: () -> Unit = {}) =
        RunnerSubAgentPromptExecutor(model, 512, c, budget, guard)

    @Test fun `model changes unsupported operations and token expansion never reach transport`(): Unit = runBlocking {
        val c = Client(); val ex = executor(c)
        assertFailsWith<IllegalArgumentException> { ex.execute(request(), model.copy(id = "other"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { ex.execute(request(params.copy(maxTokens = 513)), model, emptyList()) }
        assertFailsWith<IllegalArgumentException> { ex.execute(request(params.copy(numberOfChoices = 2)), model, emptyList()) }
        assertFails { ex.resolveModel(model, PromptExecutorOperation.Streaming) }
        assertFails { ex.executeStreaming(request(), model, emptyList()) }
        assertFails { ex.executeMultipleChoices(request(), model, emptyList()) }
        assertFails { ex.moderate(request(), model) }
        assertEquals(0, c.calls); ex.close()
    }

    @Test fun `model request budget is shared across executors without retries`(): Unit = runBlocking {
        val c = Client(); val budget = RunnerSubAgentBudget()
        val first = executor(c, budget); val second = executor(c, budget)
        repeat(255) { first.execute(request(), model, emptyList()) }
        second.execute(request(), model, emptyList())
        assertFailsWith<IllegalArgumentException> { second.execute(request(), model, emptyList()) }
        assertEquals(256, c.calls)
    }

    @Test fun `transport failure is one call and live authority is checked before publication`(): Unit = runBlocking {
        val c = Client(); val ex = executor(c)
        c.action = { _, _ -> throw java.io.IOException("503") }
        assertFailsWith<java.io.IOException> { ex.execute(request(), model, emptyList()) }
        assertEquals(1, c.calls)
        var live = true; val revoked = executor(c, guard = { check(live) })
        c.action = { _, _ -> live = false; Message.Assistant("hidden", ResponseMetaInfo.Empty) }
        assertFailsWith<IllegalStateException> { revoked.execute(request(), model, emptyList()) }
    }

    @Test fun `concurrent close releases client exactly once and prevents reuse`(): Unit = runBlocking {
        val c = Client(); val ex = executor(c)
        coroutineScope { List(32) { launch(Dispatchers.Default) { ex.close() } }.joinAll() }
        assertEquals(1, c.closes.get())
        assertFailsWith<IllegalStateException> { ex.execute(request(), model, emptyList()) }
    }

    @LLMDescription("A parent-owned adapted test tool")
    class ParentTool : ToolSet {
        var calls = 0
        @Tool @LLMDescription("Use the inherited parent instance") fun sentinel(): String { calls++; return "from-parent" }
    }
    @LLMDescription("Fixture with a transitive execution surface")
    class DelegationTool : ToolSet {
        @Tool @LLMDescription("Starts another workflow") fun executeWorkflow(): String = error("must not run")
    }

    @Test fun `undeclared nested workflow tool cannot bypass the child budget through parent registry`(): Unit = runBlocking {
        val registry = ToolRegistry { tools(DelegationTool()) }
        val c = Client()
        assertFailsWith<IllegalArgumentException> {
            runRunnerSubAgent(SubAgentContext("task", "child", LLM("openai", "frozen"), maxIterations = 8),
                WorkflowSubAgentRuntime({ registry }, null) {}, model, params, executor(c))
        }
        assertEquals(0, c.calls); assertEquals(1, c.closes.get())
    }

    @Test fun `real child graph invokes the exact parent tool and monitoring installer and closes client`(): Unit = runBlocking {
        val parent = ParentTool(); val registry = ToolRegistry { tools(parent) }; var installed = 0
        val runtime = WorkflowSubAgentRuntime({ registry }, null) { installed++ }
        val c = Client()
        c.action = { p, tools ->
            assertEquals(registry.tools.map { it.descriptor.name }, tools.map { it.name })
            if (c.calls == 1) Message.Assistant(parts = listOf(MessagePart.Tool.Call("call-1", "sentinel", JsonObject(emptyMap()))), metaInfo = ResponseMetaInfo.Empty)
            else { assertTrue(p.messages.toString().contains("from-parent")); Message.Assistant("done", ResponseMetaInfo.Empty) }
        }
        val result = runRunnerSubAgent(SubAgentContext("task", "child", LLM("openai", "frozen"), maxIterations = 16), runtime, model, params, executor(c))
        assertEquals("done", result); assertEquals(1, parent.calls); assertEquals(1, installed); assertEquals(1, c.closes.get())
    }

    @Test fun `failed and cancelled child graphs always close the owned client`(): Unit = runBlocking {
        val context = SubAgentContext("task", "child", LLM("openai", "frozen"), maxIterations = 8)
        val runtime = WorkflowSubAgentRuntime({ ToolRegistry {} }, null) {}
        val c = Client(); c.action = { _, _ -> throw java.io.IOException("503") }
        assertFails { runRunnerSubAgent(context, runtime, model, params, executor(c)) }
        assertEquals(1, c.calls); assertEquals(1, c.closes.get())
        val waiting = Client(); val entered = CompletableDeferred<Unit>()
        waiting.action = { _, _ -> entered.complete(Unit); awaitCancellation() }
        val child = async { runRunnerSubAgent(context, runtime, model, params, executor(waiting)) }
        entered.await(); child.cancel()
        assertFailsWith<CancellationException> { child.await() }
        assertEquals(1, waiting.closes.get())
    }

    private fun configuration(extra: List<ConfigurationParameter> = emptyList()) = listOf(
        ConfigurationParameter("llm_config", buildJsonObject {
            put("models", buildJsonArray { add(buildJsonObject { put("provider", "openai"); put("model", "gpt-4o") }) }) }),
        ConfigurationParameter("openai_api_key", JsonPrimitive("fixture-parent-lease")),
        ConfigurationParameter("max_iterations", JsonPrimitive(8)), ConfigurationParameter("max_tokens", JsonPrimitive(512)),
    ) + extra

    @Test fun `factory rejects missing lease MCP and unresolved defaults without constructing a client`() {
        val http = CapturingHttpClientFactory(); val runtime = WorkflowSubAgentRuntime({ ToolRegistry {} }, null) {}
        fun make(p: List<ConfigurationParameter>, leased: Boolean = true) = runnerSubAgentTools(p, runtime, RunnerSubAgentBudget(), RunnerBusinessFence(), http,
            requireLeasedKey = { require(leased && it == "fixture-parent-lease") }, checkLive = {})
        assertFails { make(configuration(), false) }
        assertFails { make(configuration().filterNot { it.key == "openai_api_key" }) }
        assertFails { make(configuration(listOf(ConfigurationParameter("mcp_servers", buildJsonObject { put("other", JsonObject(emptyMap())) })))) }
        assertFails { make(configuration(listOf(ConfigurationParameter("tool_set", buildJsonArray { add("workflow") })))) }
        assertFails { make(configuration().filterNot { it.key == "llm_config" }) }
        assertTrue(http.created.isEmpty()); assertTrue(http.requests.isEmpty())
        assertEquals(listOf(LLM("openai", "gpt-4o")), make(configuration()).getAvailableLLMs())
        assertTrue(http.created.isEmpty())
    }

    @Test fun `real provider adapter sends one request on failure without fallback or retry`(): Unit = runBlocking {
        val http = CapturingHttpClientFactory { _, _ -> throw java.io.IOException("503") }
        val runtime = WorkflowSubAgentRuntime({ ToolRegistry {} }, null) {}
        val tool = runnerSubAgentTools(configuration(), runtime, RunnerSubAgentBudget(), RunnerBusinessFence(), http,
            requireLeasedKey = { require(it == "fixture-parent-lease") }, checkLive = {})
        assertFails { tool.runSubAgent(SubAgentContext("task", "child", LLM("openai", "gpt-4o"), maxIterations = 8)) }
        assertEquals(1, http.created.size); assertEquals(1, http.requests.size)
    }
}
