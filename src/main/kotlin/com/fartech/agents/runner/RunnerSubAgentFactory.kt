package com.fartech.agents.runner

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.params.LLMParams
import com.fartech.agents.commons.*
import com.fartech.agents.tools.toLLM
import com.fartech.agents.workflow.WorkflowSubAgentRuntime
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal fun runnerSubAgentTools(
    parameters: List<ConfigurationParameter>, runtime: WorkflowSubAgentRuntime,
    budget: RunnerSubAgentBudget, fence: RunnerBusinessFence, factory: KoogHttpClient.Factory,
    requireLeasedKey: (String) -> Unit, checkLive: () -> Unit,
): RunnerSubAgentTools {
    val mcp = parameters.firstOrNull { it.key == "mcp_servers" }?.value
    require(mcp == null || mcp == JsonNull || mcp == JsonObject(emptyMap())) {
        "Hosted sub-agent MCP requires the final augmented parent registry and is not adapted"
    }
    require("workflow" !in parameters.parameter("tool_set", emptyList<String>())) {
        "Sub-agent workflow delegation requires a transitive budget adapter"
    }
    val group = parameters.getLLMGroupConfig()
    val config = requireNotNull(group.getDefaultModelName()?.let { name -> group.models.singleOrNull { it.model == name } }
        ?: if (group.getDefaultModelName() == null) group.models.firstOrNull() else null) {
        "Sub-agent requires an explicitly configured parent default model"
    }
    val model = determineLLMModel(config)
    require(model == group.resolveDefaultLLModel()) { "Parent model resolution is inconsistent" }
    require(config.provider.lowercase() !in setOf("ollama", "lmstudio", "lm-studio", "lm_studio")) {
        "Local text-only models cannot acquire sub-agent tools"
    }
    val keys: Map<String, String> = parameters.parameter("llm_provider_keys", emptyMap())
    val key = requireNotNull(resolveConfiguredApiKeyFromParams(parameters, config.provider, keys)) {
        "Sub-agent requires the parent's explicit scoped model key"
    }
    requireLeasedKey(key)
    val iterations = parameters.parameter("max_iterations", 8196)
    require(iterations > 0) { "Parent iterations must be positive" }
    val tokens = minOf(parameters.parameter("max_tokens", 8192), (model.maxOutputTokens ?: 65536L).coerceAtMost(65536).toInt())
    require(tokens > 0) { "Parent output budget must be positive" }
    val params = LLMParams(temperature = group.temperature, maxTokens = tokens, numberOfChoices = 1)
    return RunnerSubAgentTools(config.toLLM(), iterations, budget, fence, checkLive) { context ->
        checkLive(); requireLeasedKey(key)
        requireRunnerSubAgentRegistry(runtime.registry())
        val wire = RunnerSubAgentWireGuard()
        val client = fence.admit { checkLive(); createLLMClient(parameters, config, keys, wire.factory(factory)).second }
        val executor = RunnerSubAgentPromptExecutor(model, tokens, client, budget,
            checkLive = { checkLive(); requireLeasedKey(key) }, wireGuard = wire)
        runRunnerSubAgent(context, runtime, model, params, executor)
    }
}
