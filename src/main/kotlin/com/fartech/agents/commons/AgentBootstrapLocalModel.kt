package com.fartech.agents.commons

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.serialization.TypeToken
import ai.koog.serialization.JavaClassToken
import ai.koog.serialization.JavaTypeToken
import ai.koog.serialization.KotlinTypeToken
import com.fartech.agents.workflow.WorkflowHostContext
import com.fartech.agents.workflow.WorkflowLocalModelSelection
import com.fartech.ftapp2.commonsKt.ConfigurationParameter

/** No configuration flag can manufacture a host. Selection is a pure trusted-host lookup;
 * defaults may consult it more than once, but the transport factory runs only at construction.
 */
@PublishedApi
internal fun selectedHostLocalModel(): WorkflowLocalModelSelection? {
    val host = WorkflowHostContext.current ?: return null
    val scope = WorkflowHostContext.scope ?: return null
    val selection = host.localModel(scope) ?: return null
    require(selection.binding == host.binding && selection.scope == scope && scope.rootExecutionId == host.binding.executionId) { "Local host selection scope mismatch" }
    return selection
}

/** Preserve the server default while avoiding skill/MCP hook initialization for a local step. */
@PublishedApi
internal fun createBootstrapSkillManager(parameters: List<ConfigurationParameter>): SkillManager? =
    if (selectedHostLocalModel() != null) null else createSkillManager(parameters)

/** Koog tokens intentionally have identity equality, so compare their represented type. */
@PublishedApi
internal fun isHostLocalTextType(token: TypeToken): Boolean = when (token) {
    is JavaClassToken -> token.klass == String::class.java && token.typeArguments.isEmpty()
    is JavaTypeToken -> token.type == String::class.java
    is KotlinTypeToken -> token.type.classifier == String::class && token.type.arguments.isEmpty() && !token.type.isMarkedNullable
    else -> false
}

/** A single text request. No tool loop, history compression, retry, cache or fallback graph.
 * Only the caller's trusted monitoring installer is retained; YAML-driven feature preparation
 * (Langfuse, persistence, memory and skills) is never entered on this branch.
 */
@PublishedApi
internal fun <Input, Output> buildHostedLocalAgent(
    inputType: TypeToken, outputType: TypeToken, systemPrompt: String,
    selection: WorkflowLocalModelSelection, toolRegistry: ToolRegistry, skillManager: SkillManager?,
    installFeatures: GraphAIAgent.FeatureContext.() -> Unit,
): GraphAIAgent<Input, Output> {
    require(isHostLocalTextType(inputType) && isHostLocalTextType(outputType)) { "Local host admits text input and output only" }
    require(toolRegistry.tools.isEmpty() && skillManager == null) { "Local host does not admit tools or skills" }
    val graph = strategy<String, String>("__host_local_text__") {
        val complete by node<String, String>("__host_local_completion__") { input ->
            llm.writeSession { appendPrompt { user(input) }; requestLLM().textContent() }
        }
        nodeStart then complete then nodeFinish
    }
    val executor = selection.createExecutor()
    try {
        @Suppress("UNCHECKED_CAST")
        return object : GraphAIAgent<String, String>(
            promptExecutor = executor, toolRegistry = ToolRegistry {}, strategy = graph,
            agentConfig = AIAgentConfig(prompt = prompt("host-local", selection.params) { system(systemPrompt) }, model = selection.model, maxAgentIterations = 3),
            installFeatures = installFeatures,
        ) {
            private val closed = java.util.concurrent.atomic.AtomicBoolean()
            override suspend fun close() {
                if (closed.compareAndSet(false, true)) {
                    try { super.close() } finally { executor.close() }
                }
            }
        } as GraphAIAgent<Input, Output>
    } catch (failure: Throwable) {
        runCatching { executor.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}
