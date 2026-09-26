package com.fartech.agents.commons

import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `ResponseMetaInfo.metadata` keys carrying provider prompt-cache usage.
 *
 * Koog 1.3.0 fills these from the provider's usage block; the Anthropic keys are also
 * recovered for streamed responses by [AnthropicStreamUsageRecovery], which Koog's
 * streaming path drops.
 */
object PromptCacheUsageKeys {
    /** Anthropic / Bedrock Converse: tokens served from the prompt cache. Not in `inputTokensCount`. */
    const val CACHE_READ_INPUT_TOKENS = "cacheReadInputTokens"

    /** Anthropic: tokens written to the prompt cache. Not in `inputTokensCount`. */
    const val CACHE_CREATION_INPUT_TOKENS = "cacheCreationInputTokens"

    /** Bedrock Converse: tokens written to the prompt cache. Not in `inputTokensCount`. */
    const val CACHE_WRITE_INPUT_TOKENS = "cacheWriteInputTokens"

    /** Google Gemini: prompt tokens served from the (implicit) context cache. Already in `inputTokensCount`. */
    const val CACHED_CONTENT_TOKEN_COUNT = "cachedContentTokenCount"
}

/**
 * One LLM call's token usage, normalized across providers to the convention braidrun-web
 * meters with (and Anthropic reports natively):
 *
 * - [inputTokens] — prompt tokens billed at the full input rate, i.e. **excluding** cache reads
 *   and cache writes.
 * - [cacheReadTokens] — prompt tokens served from the provider's prompt cache (billed at the
 *   cache-read rate, e.g. 0.1× input on most Claude models).
 * - [cacheCreationTokens] — prompt tokens written to the prompt cache (billed at the
 *   cache-write rate, 1.25× input for Anthropic's 5-minute TTL).
 * - [outputTokens] — generated tokens billed at the output rate, reasoning included: OpenAI
 *   and Anthropic count it in their output natively, Gemini thinking is added by
 *   [GeminiThinkingUsageClient].
 * - [reasoningTokens] — the reasoning share of [outputTokens] where the provider reports it
 *   separately (Gemini thinking). Informational: it is already in [outputTokens].
 * - [totalTokens] — the provider's total minus any cached prompt tokens it contained, i.e.
 *   [inputTokens] + [outputTokens]. Cache reads and writes are reported separately and are
 *   not included.
 *
 * Total prompt size is [promptTokens] (`input + cacheRead + cacheCreation`).
 */
data class LlmTokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
    val reasoningTokens: Int? = null,
) {
    val promptTokens: Int
        get() = (inputTokens ?: 0) + (cacheReadTokens ?: 0) + (cacheCreationTokens ?: 0)

    val isEmpty: Boolean
        get() = inputTokens == null && outputTokens == null && totalTokens == null &&
            cacheReadTokens == null && cacheCreationTokens == null && reasoningTokens == null

    /** `input=…, output=…, total=…, cache_read=…, cache_creation=…, reasoning=…` for event details; null when empty. */
    fun detail(): String? = listOfNotNull(
        inputTokens?.let { "input=$it" },
        outputTokens?.let { "output=$it" },
        totalTokens?.let { "total=$it" },
        cacheReadTokens?.let { "cache_read=$it" },
        cacheCreationTokens?.let { "cache_creation=$it" },
        reasoningTokens?.let { "reasoning=$it" },
    ).joinToString(", ").ifBlank { null }
}

/**
 * Normalizes this response's token counts into an [LlmTokenUsage].
 *
 * Providers disagree on whether cached tokens are part of the input count:
 * - Anthropic and Bedrock Converse report `input_tokens` as the uncached remainder, with cache
 *   reads / writes as separate counts. Those pass through unchanged.
 * - Google reports `promptTokenCount` including `cachedContentTokenCount`. The cached part is
 *   moved out of [LlmTokenUsage.inputTokens] into [LlmTokenUsage.cacheReadTokens], so it is not
 *   also billed at the full input rate.
 *
 * Providers whose cached counts Koog 1.3.0 does not surface (the OpenAI-compatible family)
 * report their full prompt as [LlmTokenUsage.inputTokens]. Zero or negative counts are treated
 * as absent, matching the engine's historical `takeIf { it > 0 }`.
 *
 * Gemini thinking tokens are already part of `outputTokensCount` when the response came
 * through [GeminiThinkingUsageClient]; [ReasoningUsageKeys.THOUGHTS_TOKEN_COUNT] only reports
 * that share as [LlmTokenUsage.reasoningTokens].
 */
fun ResponseMetaInfo.tokenUsage(): LlmTokenUsage {
    val metadata = metadata
    val reportedInput = inputTokensCount.positiveOrNull()
    val output = outputTokensCount.positiveOrNull()

    val googleCached = metadata.positiveInt(PromptCacheUsageKeys.CACHED_CONTENT_TOKEN_COUNT)
    val cacheRead = metadata.positiveInt(PromptCacheUsageKeys.CACHE_READ_INPUT_TOKENS) ?: googleCached
    val cacheCreation = metadata.positiveInt(PromptCacheUsageKeys.CACHE_CREATION_INPUT_TOKENS)
        ?: metadata.positiveInt(PromptCacheUsageKeys.CACHE_WRITE_INPUT_TOKENS)

    val input = if (googleCached != null && reportedInput != null) {
        (reportedInput - googleCached).coerceAtLeast(0)
    } else {
        reportedInput
    }
    // Prefer the provider's own total. For Google the cached share leaves the total along with
    // the input; Anthropic's total never contained it.
    val total = totalTokensCount.positiveOrNull()?.let { (it - (googleCached ?: 0)).coerceAtLeast(0) }
        ?: if (input != null || output != null) (input ?: 0) + (output ?: 0) else null
    return LlmTokenUsage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = total.positiveOrNull(),
        cacheReadTokens = cacheRead,
        cacheCreationTokens = cacheCreation,
        reasoningTokens = metadata.positiveInt(ReasoningUsageKeys.THOUGHTS_TOKEN_COUNT),
    )
}

private fun Int?.positiveOrNull(): Int? = this?.takeIf { it > 0 }

private fun JsonObject?.positiveInt(key: String): Int? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    val value = primitive.intOrNull ?: primitive.longOrNull?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    return value.positiveOrNull()
}
