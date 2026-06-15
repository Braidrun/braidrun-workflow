package com.fartech.agents.commons

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pins the behaviour of the Tier-1 prompt-cache-control helpers.
 *
 * After the Koog 1.0.0 migration:
 *   - `.content` accessor on messages is gone; use `.textContent()` or walk
 *     `parts: List<MessagePart>` directly.
 *   - Provider-specific `CacheControl` types moved out of the prompt-model
 *     module: Bedrock variants live in `prompt-executor-bedrock-client`
 *     as `BedrockCacheControl`; Anthropic variants live in
 *     `prompt-executor-anthropic-client` as `AnthropicCacheControl`.
 *   - The Anthropic client now natively honours `cache_control` (new in
 *     1.0.0 — was Bedrock-only in 0.8.0), so [systemWithCacheHint] defaults
 *     to [CacheProvider.Anthropic] instead of forward-compat-only Bedrock.
 *   - `MessagePart.Text` (was top-level `ContentPart.Text`) carries the
 *     `cacheControl` field directly; the parent message no longer has one.
 */
class PromptCacheHintsTest {

    @Test
    fun `systemWithCacheHint attaches Anthropic OneHour by default`() {
        val p = prompt("test") {
            systemWithCacheHint("stable skill card prefix")
        }
        val sys = p.messages.filterIsInstance<Message.System>().single()
        assertEquals("stable skill card prefix", sys.textContent())
        // The cache_control on the message-level part is what providers read.
        val textPart = sys.parts.single()
        assertEquals(AnthropicCacheControl.OneHour, textPart.cacheControl)
    }

    @Test
    fun `systemWithCacheHint with explicit Bedrock provider produces Bedrock FiveMinutes`() {
        val p = prompt("test") {
            systemWithCacheHint(
                "short-lived running transcript",
                ttl = CacheTtl.FiveMinutes,
                provider = CacheProvider.Bedrock,
            )
        }
        val sys = p.messages.filterIsInstance<Message.System>().single()
        assertEquals(BedrockCacheControl.FiveMinutes, sys.parts.single().cacheControl)
    }

    @Test
    fun `systemWithCacheHint with explicit Bedrock provider maps Default to Bedrock Default`() {
        val p = prompt("test") {
            systemWithCacheHint(
                "default-ttl prefix",
                ttl = CacheTtl.Default,
                provider = CacheProvider.Bedrock,
            )
        }
        val sys = p.messages.filterIsInstance<Message.System>().single()
        assertEquals(BedrockCacheControl.Default, sys.parts.single().cacheControl)
    }

    @Test
    fun `userWithCacheHint wraps content with Anthropic OneHour by default`() {
        val p = prompt("test") {
            userWithCacheHint("long stable context preamble")
        }
        val user = p.messages.filterIsInstance<Message.User>().single()
        assertEquals("long stable context preamble", user.textContent())
        val textPart = user.parts.filterIsInstance<MessagePart.Text>().single()
        assertEquals(AnthropicCacheControl.OneHour, textPart.cacheControl)
    }

    @Test
    fun `plain system message without hint has null cacheControl`() {
        val p = prompt("test") {
            system { +"no hint here" }
        }
        val sys = p.messages.filterIsInstance<Message.System>().single()
        val textPart = sys.parts.single()
        assertNull(textPart.cacheControl)
    }

    @Test
    fun `systemWithCacheHint rejects blank content`() {
        assertThrows(IllegalArgumentException::class.java) {
            prompt("test") { systemWithCacheHint("   ") }
        }
    }

    @Test
    fun `userWithCacheHint rejects blank content`() {
        assertThrows(IllegalArgumentException::class.java) {
            prompt("test") { userWithCacheHint("") }
        }
    }

    @Test
    fun `mixed prompt — cached stable prefix plus volatile tail`() {
        // Represents the canonical usage pattern: long stable prefix is
        // cacheable, but the final turn-specific message is not tagged.
        val p = prompt("test") {
            systemWithCacheHint("long stable skill + tool guide block")
            system { +"per-turn locale hint: zh-CN" }
            user { +"what changed in the Q3 report?" }
        }
        val systems = p.messages.filterIsInstance<Message.System>()
        assertEquals(2, systems.size)
        assertNotNull(systems[0].parts.single().cacheControl)
        assertNull(systems[1].parts.single().cacheControl)
        val user = p.messages.filterIsInstance<Message.User>().single()
        val textPart = user.parts.filterIsInstance<MessagePart.Text>().single()
        assertNull(textPart.cacheControl)
        assertEquals(MessagePart.Text("what changed in the Q3 report?"), textPart)
    }

    @Test
    fun `userPartsWithCacheHint tags each text part with the same cacheControl`() {
        val p = prompt("test") {
            userPartsWithCacheHint(
                parts = listOf(
                    MessagePart.Text("first segment"),
                    MessagePart.Text("second segment"),
                ),
                ttl = CacheTtl.OneHour,
                provider = CacheProvider.Anthropic,
            )
        }
        val user = p.messages.filterIsInstance<Message.User>().single()
        val texts = user.parts.filterIsInstance<MessagePart.Text>()
        assertEquals(2, texts.size)
        assertEquals(AnthropicCacheControl.OneHour, texts[0].cacheControl)
        assertEquals(AnthropicCacheControl.OneHour, texts[1].cacheControl)
    }
}
