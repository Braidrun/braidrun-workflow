package com.fartech.agents.workflow

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams

/** Host-only, nonserializable selection. The executor factory is lazy and must provide the
 * local authority/gate/stop-proof transport, not a cloud factory or a fallback chain.
 * Scope is repeated intentionally: a retained selection cannot silently cross steps/attempts.
 */
data class WorkflowLocalModelSelection(
    val binding: WorkflowHostBinding,
    val scope: WorkflowHostScope,
    val model: LLModel,
    val params: LLMParams,
    val createExecutor: () -> PromptExecutor,
) {
    /** Concrete execution identity, retaining graph segments even when named `call_1`.
     * Syntax/lineage checks here are necessary but do not replace the host's frozen graph lookup.
     */
    val childExecutionPath: List<String> = if (scope.executionId == binding.executionId) {
        require(scope.parentExecutionId == null) { "Local root scope has a parent" }
        emptyList()
    } else {
        val prefix = binding.executionId + "__"
        require(scope.executionId.startsWith(prefix)) { "Local child execution prefix mismatch" }
        val parts = scope.executionId.removePrefix(prefix).split("__")
        require(parts.size in 2..32 && parts.size % 2 == 0) { "Local child execution depth invalid" }
        parts.chunked(2).forEach { (segment, ordinal) ->
            require(Regex("[A-Za-z0-9_.-]{1,256}").matches(segment) && segment !in setOf(".", "..") &&
                Regex("call_[1-9][0-9]{0,17}").matches(ordinal)) { "Local child execution path invalid" }
        }
        val parent = binding.executionId + parts.dropLast(2).takeIf { it.isNotEmpty() }?.joinToString("__", prefix = "__").orEmpty()
        require(scope.parentExecutionId == parent) { "Local child parent scope mismatch" }
        parts
    }
    init {
        require(scope.rootExecutionId == binding.executionId) { "Local model root binding mismatch" }
        require(scope.stepName?.isNotBlank() == true && scope.occurrence == null) { "Local model requires a plain step scope" }
        val contextTokens = requireNotNull(model.contextLength) { "Local context must be pinned" }
        val outputTokens = requireNotNull(model.maxOutputTokens) { "Local output must be pinned" }
        require(model.provider == LLMProvider.Ollama && contextTokens > 0 && outputTokens > 0) { "Local model identity must be pinned" }
        require(params::class == LLMParams::class && params.temperature == 0.0 && params.numberOfChoices == 1 && params.toolChoice == LLMParams.ToolChoice.None) { "Local model parameters must be fixed" }
        require(params.maxTokens != null && params.maxTokens!! > 0 && params.maxTokens!! <= outputTokens && params.maxTokens!! < contextTokens) { "Local model output budget must be pinned" }
        require(params.additionalProperties.isNullOrEmpty() && params.speculation == null && params.user == null) { "Local model overrides are not admitted" }
        // Initial host hook is text-only; schema routing remains an independently admitted adapter API.
        require(params.schema == null) { "Local host schema not admitted" }
    }
}
