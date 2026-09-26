package com.fartech.agents.commons

import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class LlmTokenUsageTest {

    private fun meta(input: Int?, output: Int?, total: Int?, vararg metadata: Pair<String, Int>) = ResponseMetaInfo(
        timestamp = Instant.fromEpochMilliseconds(0),
        totalTokensCount = total,
        inputTokensCount = input,
        outputTokensCount = output,
        metadata = metadata.takeIf { it.isNotEmpty() }?.let { pairs -> JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }) },
    )

    @Test
    fun `Anthropic cache counts are separate from the uncached input`() {
        // Koog's Anthropic totals are input_tokens + output_tokens: the uncached remainder only.
        val usage = meta(
            input = 120, output = 30, total = 150,
            PromptCacheUsageKeys.CACHE_READ_INPUT_TOKENS to 9_000,
            PromptCacheUsageKeys.CACHE_CREATION_INPUT_TOKENS to 400,
        ).tokenUsage()

        assertEquals(LlmTokenUsage(inputTokens = 120, outputTokens = 30, totalTokens = 150, cacheReadTokens = 9_000, cacheCreationTokens = 400), usage)
        assertEquals(9_520, usage.promptTokens)
        assertEquals("input=120, output=30, total=150, cache_read=9000, cache_creation=400", usage.detail())
    }

    @Test
    fun `Google cached content moves out of the input it is counted in`() {
        // promptTokenCount 10_000 includes 8_000 cached.
        val usage = meta(
            input = 10_000, output = 200, total = 10_200,
            PromptCacheUsageKeys.CACHED_CONTENT_TOKEN_COUNT to 8_000,
        ).tokenUsage()

        assertEquals(2_000, usage.inputTokens)
        assertEquals(8_000, usage.cacheReadTokens)
        assertNull(usage.cacheCreationTokens)
        assertEquals(2_200, usage.totalTokens, "the cached share leaves the total along with the input")
        assertEquals(10_000, usage.promptTokens)
    }

    @Test
    fun `Gemini thinking tokens are billed as output`() {
        // Koog's Google mapping: output = candidatesTokenCount (200), total = totalTokenCount,
        // which also holds 50 thinking tokens and the 8_000 cached ones.
        val raw = meta(
            input = 10_000, output = 200, total = 10_250,
            PromptCacheUsageKeys.CACHED_CONTENT_TOKEN_COUNT to 8_000,
        )
        val usage = raw.withGeminiThinkingInOutput().tokenUsage()

        assertEquals(
            LlmTokenUsage(inputTokens = 2_000, outputTokens = 250, totalTokens = 2_250, cacheReadTokens = 8_000, reasoningTokens = 50),
            usage,
        )
        assertEquals(usage.totalTokens, usage.inputTokens!! + usage.outputTokens!!, "total = input + output again")
        assertEquals("input=2000, output=250, total=2250, cache_read=8000, reasoning=50", usage.detail())
        assertEquals(200, raw.tokenUsage().outputTokens, "without the Google decorator the thinking share is unpriced")
    }

    @Test
    fun `thinking normalization leaves totals without a gap alone and is idempotent`() {
        // No thinking, or a response whose candidates count already includes it.
        val settled = meta(input = 100, output = 900, total = 1_000)
        assertSame(settled, settled.withGeminiThinkingInOutput())

        val once = meta(input = 100, output = 60, total = 1_000).withGeminiThinkingInOutput()
        assertEquals(once, once.withGeminiThinkingInOutput())
        assertEquals(LlmTokenUsage(inputTokens = 100, outputTokens = 900, totalTokens = 1_000, reasoningTokens = 840), once.tokenUsage())
    }

    @Test
    fun `thinking normalization handles missing counts`() {
        // A thinking-only reply with no candidates count: everything past the prompt is output.
        assertEquals(700, meta(input = 100, output = null, total = 800).withGeminiThinkingInOutput().outputTokensCount)
        // Without the prompt or the total there is nothing to derive from.
        val noPrompt = meta(input = null, output = 50, total = 800)
        assertSame(noPrompt, noPrompt.withGeminiThinkingInOutput())
        val noTotal = meta(input = 100, output = 50, total = null)
        assertSame(noTotal, noTotal.withGeminiThinkingInOutput())
    }

    @Test
    fun `a thoughts count the provider already recorded is kept`() {
        val usage = meta(input = 100, output = 60, total = 1_000, ReasoningUsageKeys.THOUGHTS_TOKEN_COUNT to 840)
            .withGeminiThinkingInOutput()
            .tokenUsage()

        assertEquals(900, usage.outputTokens)
        assertEquals(840, usage.reasoningTokens)
    }

    @Test
    fun `Bedrock Converse cache writes use their own key`() {
        val usage = meta(
            input = 10, output = 5, total = 15,
            PromptCacheUsageKeys.CACHE_READ_INPUT_TOKENS to 700,
            PromptCacheUsageKeys.CACHE_WRITE_INPUT_TOKENS to 300,
        ).tokenUsage()

        assertEquals(700, usage.cacheReadTokens)
        assertEquals(300, usage.cacheCreationTokens)
        assertEquals(10, usage.inputTokens)
    }

    @Test
    fun `providers without cache metadata pass through unchanged`() {
        assertEquals(
            LlmTokenUsage(inputTokens = 100, outputTokens = 20, totalTokens = 120),
            meta(input = 100, output = 20, total = 120).tokenUsage(),
        )
        assertEquals(
            LlmTokenUsage(inputTokens = 100, outputTokens = 20, totalTokens = 120),
            meta(input = 100, output = 20, total = null).tokenUsage(),
            "a missing total is input + output",
        )
    }

    @Test
    fun `zero counts and a response-cache hit report no usage`() {
        assertEquals(
            LlmTokenUsage(inputTokens = 5, outputTokens = 1, totalTokens = 6),
            meta(input = 5, output = 1, total = 6, PromptCacheUsageKeys.CACHE_READ_INPUT_TOKENS to 0).tokenUsage(),
        )
        val hit = ResponseMetaInfo(
            timestamp = Instant.fromEpochMilliseconds(0),
            metadata = JsonObject(mapOf(PROMPT_CACHE_HIT_METADATA_KEY to JsonPrimitive(true))),
        ).tokenUsage()
        assertTrue(hit.isEmpty)
        assertNull(hit.detail())
    }
}
