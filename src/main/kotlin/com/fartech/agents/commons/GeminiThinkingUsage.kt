package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `ResponseMetaInfo.metadata` keys carrying reasoning-token usage. */
object ReasoningUsageKeys {
    /**
     * Gemini thinking tokens. Set by [GeminiThinkingUsageClient], which has already added them
     * to `outputTokensCount`; informational only, never add it to the output again.
     */
    const val THOUGHTS_TOKEN_COUNT = "thoughtsTokenCount"
}

/**
 * Counts Gemini thinking tokens as output, the rate Google bills them at.
 *
 * Gemini reports thinking as `thoughtsTokenCount`, outside `candidatesTokenCount`, and Koog
 * 1.3.0's `GoogleLLMClient` maps only `outputTokensCount = candidatesTokenCount` and
 * `totalTokensCount = totalTokenCount`. The thinking share therefore survived only in the
 * total: the web quota counted it, but cost estimates (input × input rate + output × output
 * rate) never priced it. This client restores the convention the other providers already
 * follow (OpenAI's `completion_tokens` and Anthropic's `output_tokens` include reasoning); see
 * [withGeminiThinkingInOutput].
 *
 * Installed by [createLLMClient] around the Google client only. Other providers' totals are
 * not known to hide output (Bedrock Converse passes AWS's own `totalTokens` through, whose
 * relation to its cache counts is undocumented), so deriving from them could misprice them.
 */
internal class GeminiThinkingUsageClient(delegate: LLMClient) : ForwardingLLMClient(delegate) {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = delegate.execute(prompt, model, tools).withGeminiThinkingInOutput()

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools).map { frame ->
        if (frame is StreamFrame.End) frame.copy(metaInfo = frame.metaInfo.withGeminiThinkingInOutput()) else frame
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = delegate.executeMultipleChoices(prompt, model, tools).map { it.withGeminiThinkingInOutput() }
}

private fun Message.Assistant.withGeminiThinkingInOutput(): Message.Assistant {
    val normalized = metaInfo.withGeminiThinkingInOutput()
    return if (normalized === metaInfo) this else copy(metaInfo = normalized)
}

/**
 * Moves the part of a Gemini response's total that is neither prompt nor candidates into
 * `outputTokensCount`, and records it under [ReasoningUsageKeys.THOUGHTS_TOKEN_COUNT].
 *
 * Gemini's `totalTokenCount` is prompt + candidates + thoughts + `toolUsePromptTokenCount`,
 * with `promptTokenCount` already covering any cached content. Koog 1.3.0 keeps none of the
 * last two, so the gap is derived rather than read:
 * - `toolUsePromptTokenCount` (input-side results of Google-executed tools: Search grounding,
 *   code execution, URL context) cannot occur here. Koog's `GoogleTool` serializes only
 *   `functionDeclarations`, and `GoogleParams.additionalProperties` land in
 *   `generationConfig`, so braidrun has no way to enable those tools.
 *   `GeminiThinkingUsageTest` fails if Koog's request model changes that.
 * - Deriving the gap also avoids double counting. Where `candidatesTokenCount` already
 *   includes thoughts (reports disagree on whether some Gemini endpoints do this; Koog might
 *   also map them one day), the gap is 0 and the counts pass through unchanged. For the same
 *   reason the function is idempotent.
 */
internal fun ResponseMetaInfo.withGeminiThinkingInOutput(): ResponseMetaInfo {
    val total = totalTokensCount ?: return this
    val prompt = inputTokensCount ?: return this
    val candidates = outputTokensCount ?: 0
    val thinking = total - prompt - candidates
    if (thinking <= 0) return this
    val metadata = metadata.orEmpty()
    val recorded = if (ReasoningUsageKeys.THOUGHTS_TOKEN_COUNT in metadata) {
        metadata
    } else {
        metadata + (ReasoningUsageKeys.THOUGHTS_TOKEN_COUNT to JsonPrimitive(thinking))
    }
    return copy(outputTokensCount = candidates + thinking, metadata = JsonObject(recorded))
}
