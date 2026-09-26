package com.fartech.agents.commons

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** WebP limits, tolerant switches, request/run image caps and linear redaction. */
class ToolResultMediaReviewFixesTest {

    private val vision = AnthropicModels.Sonnet_4_5

    private fun param(key: String, value: JsonPrimitive) = ConfigurationParameter(key, value)

    private fun webpHeader(chunk: String, fill: (ByteArray) -> Unit): ByteArray {
        val bytes = ByteArray(64)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        chunk.toByteArray().copyInto(bytes, 12)
        fill(bytes)
        return bytes
    }

    private fun le24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun vp8x(width: Int, height: Int) = webpHeader("VP8X") {
        le24(it, 24, width - 1)
        le24(it, 27, height - 1)
    }

    private fun vp8l(width: Int, height: Int) = webpHeader("VP8L") {
        it[20] = 0x2F
        val bits = (width - 1).toLong() or ((height - 1).toLong() shl 14)
        for (i in 0 until 4) it[21 + i] = ((bits shr (8 * i)) and 0xFF).toByte()
    }

    private fun vp8(width: Int, height: Int) = webpHeader("VP8 ") {
        it[23] = 0x9D.toByte(); it[24] = 0x01; it[25] = 0x2A
        it[26] = (width and 0xFF).toByte(); it[27] = ((width shr 8) and 0x3F).toByte()
        it[28] = (height and 0xFF).toByte(); it[29] = ((height shr 8) and 0x3F).toByte()
    }

    @Test
    fun `WebP dimensions are read from VP8X, VP8L and VP8 headers`() {
        assertEquals(1280 to 12000, ToolResultImages.readWebpDimensions(vp8x(1280, 12000)))
        assertEquals(800 to 600, ToolResultImages.readWebpDimensions(vp8l(800, 600)))
        assertEquals(640 to 480, ToolResultImages.readWebpDimensions(vp8(640, 480)))
        assertEquals(null, ToolResultImages.readWebpDimensions(webpHeader("VP9?") {}))
    }

    @Test
    fun `small WebP passes with its dimensions, oversized or unreadable WebP is rejected`() {
        val ok = ToolResultImages.prepare(vp8x(1024, 768))
        assertTrue(ok is PreparedToolImage.Ready, "$ok")
        val image = (ok as PreparedToolImage.Ready).image
        assertEquals(ToolResultImages.WEBP, image.mimeType)
        assertEquals(1024, image.width)
        assertEquals(768, image.height)
        assertTrue(image.describe().contains("1024x768"), image.describe())

        val tall = ToolResultImages.prepare(vp8x(1280, 12000))
        assertTrue(tall is PreparedToolImage.Rejected && tall.reason.contains("1280x12000"), "$tall")
        val wide = ToolResultImages.prepare(vp8l(10000, 300))
        assertTrue(wide is PreparedToolImage.Rejected, "$wide")
        val garbage = ToolResultImages.prepare(webpHeader("XXXX") {})
        assertTrue(garbage is PreparedToolImage.Rejected, "$garbage")
    }

    @Test
    fun `switches accept string values and never throw`() {
        for (off in listOf("false", "FALSE", " 0 ", "off", "no")) {
            val policy = ToolResultMediaPolicy.forModel(
                vision,
                listOf(param(ToolResultMediaPolicy.ENABLED_PARAMETER, JsonPrimitive(off))),
            )
            assertFalse(policy.attachImages, off)
        }
        assertTrue(
            ToolResultMediaPolicy.forModel(vision, listOf(param(ToolResultMediaPolicy.ENABLED_PARAMETER, JsonPrimitive("maybe"))))
                .attachImages
        )
        val durable = listOf(
            param("enable_persistence", JsonPrimitive("true")),
            param("persistence_storage_type", JsonPrimitive("mongodb")),
        )
        assertFalse(ToolResultMediaPolicy.forModel(vision, durable).attachImages)
        val memory = listOf(
            param("enable_persistence", JsonPrimitive("true")),
            param("persistence_storage_type", JsonPrimitive("memory")),
        )
        assertTrue(ToolResultMediaPolicy.forModel(vision, memory).attachImages)
    }

    @Test
    fun `per-request and per-run caps come from workflow parameters`() {
        assertEquals(DEFAULT_TOOL_RESULT_IMAGES_PER_REQUEST, toolResultImagesPerRequest(emptyList()))
        assertEquals(7, toolResultImagesPerRequest(listOf(param(MAX_IMAGES_PER_REQUEST_PARAMETER, JsonPrimitive("7")))))
        assertEquals(
            MAX_TOOL_RESULT_IMAGES_PER_REQUEST,
            toolResultImagesPerRequest(listOf(param(MAX_IMAGES_PER_REQUEST_PARAMETER, JsonPrimitive(99)))),
        )
        assertEquals(0, toolResultImagesPerRequest(listOf(param(MAX_IMAGES_PER_REQUEST_PARAMETER, JsonPrimitive(-3)))))

        val perRun = ToolResultMediaPolicy.forModel(
            vision,
            listOf(param(ToolResultMediaPolicy.MAX_IMAGES_PER_RUN_PARAMETER, JsonPrimitive(3))),
        )
        assertEquals(3, perRun.maxImagesPerRun)
        val none = ToolResultMediaPolicy.forModel(
            vision,
            listOf(param(ToolResultMediaPolicy.MAX_IMAGES_PER_RUN_PARAMETER, JsonPrimitive("0"))),
        )
        assertFalse(none.attachImages)
    }

    @Test
    fun `adapter keeps only the configured number of images per request`() {
        val image = ToolResultMediaFixtures.readyImage()
        var combined = ToolResultMediaFixtures.promptWithToolImage(image, "a")
        repeat(4) { i ->
            val next = ToolResultMediaFixtures.promptWithToolImage(image, "b$i")
            combined = combined.withMessages { it + next.messages.drop(2) }
        }
        val kept = { max: Int ->
            ToolResultMediaFixtures.toolResults(
                combined.withToolResultMediaAdapted(maxImages = max, step = toolResultImagePruneStep(max)) { true }
            ).sumOf { r -> r.parts.count { it is ai.koog.prompt.message.MessagePart.Attachment } }
        }
        assertEquals(5, kept(12))
        // 5 images, cap 4, prune step 2: the two oldest go, so the prefix changes every other image.
        assertEquals(3, kept(4))
        assertEquals(0, kept(0))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `redaction is linear on many alphanumeric runs just below the threshold`() {
        val line = "a".repeat(ToolResultImages.MIN_REDACTED_BASE64_RUN - 1)
        val text = (1..300).joinToString("\n") { line }
        assertEquals(text, ToolResultImages.redactInlineBase64(text))

        val blob = "QUJD".repeat(1024)
        val redacted = ToolResultImages.redactInlineBase64("x $blob y")
        assertTrue(redacted.startsWith("x [base64 data omitted ("), redacted)
        assertTrue(redacted.endsWith(" y"), redacted)
    }
}
