package com.fartech.agents.commons

import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        // promptTokenCount 10_000 includes 8_000 cached; the total also carries 50 thinking tokens.
        val usage = meta(
            input = 10_000, output = 200, total = 10_250,
            PromptCacheUsageKeys.CACHED_CONTENT_TOKEN_COUNT to 8_000,
        ).tokenUsage()

        assertEquals(2_000, usage.inputTokens)
        assertEquals(8_000, usage.cacheReadTokens)
        assertNull(usage.cacheCreationTokens)
        assertEquals(2_250, usage.totalTokens, "thinking tokens stay in the total; the cached share leaves it")
        assertEquals(10_000, usage.promptTokens)
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
