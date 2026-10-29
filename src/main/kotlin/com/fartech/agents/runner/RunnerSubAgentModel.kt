package com.fartech.agents.runner

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import com.fartech.agents.commons.toolCycleGraph
import com.fartech.agents.tools.SubAgentContext
import com.fartech.agents.workflow.WorkflowSubAgentRuntime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

/** Exactly one approved client. This is intentionally independent of the generic retry/cascade
 * executor builder; no model discovery, embeddings, moderation or implicit feature preparation. */
internal class RunnerSubAgentPromptExecutor(
    private val model: LLModel,
    private val maximumOutputTokens: Int,
    private val client: LLMClient,
    private val budget: RunnerSubAgentBudget,
    private val checkLive: () -> Unit,
    private val wireGuard: RunnerSubAgentWireGuard? = null,
) : PromptExecutor() {
    private val closed = AtomicBoolean()
    private fun check(requested: LLModel = model) {
        check(!closed.get()) { "Sub-agent model client is closed" }
        checkLive()
        require(requested == model) { "Sub-agent model changes and fallback are forbidden" }
    }
    override suspend fun resolveModel(model: LLModel, promptExecutorOperation: PromptExecutorOperation): ResolvedModel {
        check(model)
        require(promptExecutorOperation == PromptExecutorOperation.Execute) { "Sub-agent model operation is not admitted" }
        return ResolvedModel(model)
    }
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        currentCoroutineContext().ensureActive(); check(model)
        require(prompt.params.numberOfChoices == null || prompt.params.numberOfChoices == 1) { "Multiple choices are not admitted" }
        require(requireNotNull(prompt.params.maxTokens) in 1..maximumOutputTokens) { "Sub-agent token budget exceeds parent limit" }
        budget.modelRequest()
        wireGuard?.begin()
        return try { client.execute(prompt, model, tools).also { currentCoroutineContext().ensureActive(); check(model) } }
        finally { wireGuard?.finish() }
    }
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("Sub-agent streaming is not admitted")
    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice = error("Sub-agent multiple choices are not admitted")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Sub-agent moderation is not admitted")
    override suspend fun models(): List<LLModel> { check(); return listOf(model) }
    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator = error("Sub-agent schema generation is not admitted")
    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator = error("Sub-agent schema generation is not admitted")
    override fun close() { if (closed.compareAndSet(false, true)) client.close() }
}

/** Reuses the exact parent tool instances and skill manager. No MCP, environment, persistence,
 * tracing, memory, hook discovery or model/client factories run here. */
internal suspend fun runRunnerSubAgent(
    context: SubAgentContext,
    runtime: WorkflowSubAgentRuntime,
    model: LLModel,
    params: LLMParams,
    executor: RunnerSubAgentPromptExecutor,
): String {
    var agent: GraphAIAgent<String, String>? = null
    try {
        val registry = runtime.registry()
        requireRunnerSubAgentRegistry(registry)
        val graph = strategy<String, String>("__runner_sub_agent__") {
            val cycle by toolCycleGraph<String, String>(
                tools = registry.tools.filterIsInstance<Tool<*, *>>(),
                inputPrompt = { input, _ -> input }, outputCompute = { result, _ -> result.textContent() },
                skillManager = runtime.skillManager, historyCompression = null,
            )
            nodeStart then cycle then nodeFinish
        }
        agent = GraphAIAgent(
            promptExecutor = executor, toolRegistry = registry, strategy = graph,
            agentConfig = AIAgentConfig(model = model, maxAgentIterations = context.maxIterations,
                prompt = prompt("runner-sub-agent", params) {
                    // Skill discovery/activation remains in the inherited parent tool instances;
                    // calling createSkillSystemPrompt here could lazily initialize a new loader.
                    system("You are a child agent named ${context.name}. Complete the delegated task using the parent's approved tools and existing skill discovery tools.")
                }),
            installFeatures = runtime.installFeatures,
        )
        return agent.run(context.prompt)
    } finally {
        try { agent?.close() } finally { executor.close() }
    }
}

/** These tools start other agents outside this child's metered executor. Refuse the combination
 * until a transitive execution/iteration budget is carried across those schedulers. */
internal fun requireRunnerSubAgentRegistry(registry: ToolRegistry) {
    require(registry.tools.none { it.descriptor.name in setOf("executeWorkflow", "delegateTask", "delegateParallel") }) {
        "Sub-agent workflow/orchestrator delegation requires a transitive budget adapter"
    }
}
