package com.fartech.agents.commons

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

class ToolResultMediaPolicyTest {

    private val anthropicVision = AnthropicModels.Sonnet_4_5
    private val anthropicTextOnly = anthropicVision.copy(
        capabilities = anthropicVision.capabilities!!.filterNot { it is LLMCapability.Vision },
    )
    private val deepSeek = LLModel(LLMProvider.DeepSeek, "deepseek-chat", listOf(LLMCapability.Completion, LLMCapability.Tools))
    private val openRouterVision = LLModel(
        LLMProvider.OpenRouter,
        "openai/gpt-4o",
        listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Vision.Image),
    )

    private fun params(vararg pairs: Pair<String, Any>): List<ConfigurationParameter> =
        pairs.map { (key, value) ->
            ConfigurationParameter(key, if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString()))
        }

    // --- ToolResultImageSupport: the provider matrix --------------------------------------------

    @Test
    fun `build-time routes follow createLLMClient provider mapping`() {
        assertEquals(ToolResultImageRoute.ANTHROPIC, ToolResultImageSupport.route(anthropicVision))
        assertEquals(ToolResultImageRoute.TEXT_ONLY, ToolResultImageSupport.route(deepSeek))
        assertEquals(ToolResultImageRoute.TEXT_ONLY, ToolResultImageSupport.route(openRouterVision))
        // GPT-4o declares Completions, so OpenAILLMClient picks Chat Completions, which drops images.
        assertEquals(ToolResultImageRoute.TEXT_ONLY, ToolResultImageSupport.route(OpenAIModels.Chat.GPT4o))
        assertEquals(ToolResultImageRoute.GOOGLE, ToolResultImageSupport.route(GoogleModels.Gemini2_5Flash))
    }

    @Test
    fun `images need vision capability and an image-carrying route`() {
        assertTrue(ToolResultImageSupport.supportsImages(anthropicVision))
        assertFalse(ToolResultImageSupport.supportsImages(anthropicTextOnly))
        assertFalse(ToolResultImageSupport.supportsImages(openRouterVision))
        assertFalse(ToolResultImageSupport.supportsImages(OpenAIModels.Chat.GPT4o))
        // Gemini 2.5 has vision, but multimodal function responses are Gemini 3+ only.
        assertFalse(ToolResultImageSupport.supportsImages(GoogleModels.Gemini2_5Flash))
        assertTrue(ToolResultImageSupport.isGemini3OrLater("gemini-3-pro-preview"))
        assertFalse(ToolResultImageSupport.isGemini3OrLater("gemini-2.5-pro"))
    }

    @Test
    fun `canSend checks mime type and Google binary-only content`() {
        val png = AttachmentSource.Image(AttachmentContent.Binary.Bytes(byteArrayOf(1)), "png", "image/png")
        val bmp = AttachmentSource.Image(AttachmentContent.Binary.Bytes(byteArrayOf(1)), "bmp", "image/bmp")
        val url = AttachmentSource.Image(AttachmentContent.URL("https://example.com/a.png"), "png", "image/png")
        val gemini3 = LLModel(LLMProvider.Google, "gemini-3-pro", listOf(LLMCapability.Completion, LLMCapability.Vision.Image))
        assertTrue(ToolResultImageSupport.canSend(ToolResultImageRoute.ANTHROPIC, anthropicVision, png))
        assertFalse(ToolResultImageSupport.canSend(ToolResultImageRoute.ANTHROPIC, anthropicVision, bmp))
        assertFalse(ToolResultImageSupport.canSend(ToolResultImageRoute.ANTHROPIC, anthropicTextOnly, png))
        assertTrue(ToolResultImageSupport.canSend(ToolResultImageRoute.GOOGLE, gemini3, png))
        assertFalse(ToolResultImageSupport.canSend(ToolResultImageRoute.GOOGLE, gemini3, url))
        assertFalse(ToolResultImageSupport.canSend(ToolResultImageRoute.TEXT_ONLY, anthropicVision, png))
    }

    // --- ToolResultMediaPolicy ------------------------------------------------------------------

    @Test
    fun `policy attaches images only for models that can see them`() {
        assertTrue(ToolResultMediaPolicy.forModel(anthropicVision, emptyList()).attachImages)
        assertFalse(ToolResultMediaPolicy.forModel(anthropicTextOnly, emptyList()).attachImages)
        assertFalse(ToolResultMediaPolicy.forModel(deepSeek, emptyList()).attachImages)
        assertFalse(ToolResultMediaPolicy.forModel(null, emptyList()).attachImages)
    }

    @Test
    fun `policy can be switched off and stays text-only with durable checkpoints`() {
        assertFalse(
            ToolResultMediaPolicy.forModel(anthropicVision, params(ToolResultMediaPolicy.ENABLED_PARAMETER to false)).attachImages
        )
        assertFalse(ToolResultMediaPolicy.forModel(anthropicVision, params("enable_persistence" to true)).attachImages)
        assertTrue(
            ToolResultMediaPolicy.forModel(
                anthropicVision,
                params("enable_persistence" to true, "persistence_storage_type" to "memory"),
            ).attachImages
        )
    }

    // --- ToolResultImages: size cap and normalisation -------------------------------------------

    @Test
    fun `small png passes through unchanged`() {
        val bytes = ToolResultMediaFixtures.png(64, 32)
        val ready = ToolResultImages.prepare(bytes) as PreparedToolImage.Ready
        assertTrue(ready.image.bytes.contentEquals(bytes))
        assertEquals("image/png", ready.image.mimeType)
        assertEquals(64, ready.image.width)
        assertFalse(ready.image.downscaled)
    }

    @Test
    fun `oversized image is downscaled to the edge limit and the byte cap`() {
        val bytes = ToolResultMediaFixtures.png(3200, 1600, noise = true)
        assertTrue(bytes.size > ToolResultImages.MAX_IMAGE_BYTES, "fixture must exceed the cap")
        val ready = ToolResultImages.prepare(bytes) as PreparedToolImage.Ready
        assertTrue(ready.image.bytes.size <= ToolResultImages.MAX_IMAGE_BYTES)
        assertEquals(ToolResultImages.MAX_EDGE_PX, ready.image.width)
        assertEquals(784, ready.image.height)
        assertTrue(ready.image.downscaled)
        assertEquals(3200, ready.image.sourceWidth)
        val decoded = ImageIO.read(ByteArrayInputStream(ready.image.bytes))
        assertEquals(ToolResultImages.MAX_EDGE_PX, decoded.width)
        assertTrue(ready.image.describe().contains("downscaled from 3200x1600"))
    }

    @Test
    fun `source above the hard limit, non-images and empty data are refused`() {
        val huge = ByteArray(ToolResultImages.MAX_SOURCE_BYTES + 1)
        assertTrue(ToolResultImages.prepare(huge) is PreparedToolImage.Rejected)
        assertTrue(ToolResultImages.prepare("not an image".toByteArray()) is PreparedToolImage.Rejected)
        assertTrue(ToolResultImages.prepare(ByteArray(0)) is PreparedToolImage.Rejected)
        val hugeBase64 = "A".repeat(ToolResultImages.MAX_SOURCE_BYTES / 3 * 4 + 8)
        assertTrue(ToolResultImages.prepareBase64(hugeBase64) is PreparedToolImage.Rejected)
    }

    @Test
    fun `mime type is sniffed from the bytes`() {
        assertEquals("image/png", ToolResultImages.sniffMimeType(ToolResultMediaFixtures.png(2, 2)))
        assertEquals(null, ToolResultImages.sniffMimeType("hello".toByteArray()))
    }

    @Test
    fun `redactInlineBase64 removes data URIs and long base64 runs`() {
        val payload = Base64.getEncoder().encodeToString(ByteArray(3000) { it.toByte() })
        val uri = """{"shot":"data:image/png;base64,$payload","ok":true}"""
        val redacted = ToolResultImages.redactInlineBase64(uri)
        assertFalse(redacted.contains(payload))
        assertTrue(redacted.contains("[image/png data omitted (2.9 KB)]"), redacted)
        assertTrue(redacted.endsWith(""","ok":true}"""), redacted)

        val bare = "result: $payload end"
        assertEquals("result: [base64 data omitted (2.9 KB)] end", ToolResultImages.redactInlineBase64(bare))

        val normal = "a normal sentence with words and a short token abc123=="
        assertEquals(normal, ToolResultImages.redactInlineBase64(normal))
    }
}
