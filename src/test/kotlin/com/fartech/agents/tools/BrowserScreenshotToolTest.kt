package com.fartech.agents.tools

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.fartech.agents.commons.PreparedToolImage
import com.fartech.agents.commons.ToolResultImages
import com.fartech.agents.commons.ToolResultMediaPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Random
import javax.imageio.ImageIO

class BrowserScreenshotToolTest {

    private val serializer = KotlinxSerializer()
    private val file = File("screenshot.png").absoluteFile

    private fun png(width: Int, height: Int, noise: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        if (noise) {
            val random = Random(7)
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private class FakeSource(private val capture: BrowserTools.ScreenshotCapture) : BrowserScreenshotTool.ScreenshotSource {
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        override suspend fun capture(path: String, contextId: String, fullPage: Boolean): BrowserTools.ScreenshotCapture {
            calls += Triple(path, contextId, fullPage)
            return capture
        }
    }

    @Test
    fun `vision policy returns the status line plus the captured image`() = runBlocking {
        val bytes = png(120, 60)
        val source = FakeSource(BrowserTools.ScreenshotCapture.Saved(file, bytes))
        val tool = BrowserScreenshotTool(source, ToolResultMediaPolicy(attachImages = true))

        val result = tool.execute(BrowserScreenshotTool.Args(path = "screenshot.png", fullPage = true))
        val parts = tool.encodeResultToParts(result, serializer)

        assertEquals(listOf(Triple("screenshot.png", "default", true)), source.calls)
        assertEquals(2, parts.size)
        val text = (parts[0] as MessagePart.Text).text
        assertTrue(text.startsWith("✅ Screenshot saved to ${file.absolutePath}"), text)
        assertTrue(text.contains("[image 1 attached to this tool result: image/png, 120x60"), text)
        val image = (parts[1] as MessagePart.Attachment).source as AttachmentSource.Image
        assertEquals("image/png", image.mimeType)
        assertTrue((image.content as AttachmentContent.Binary.Bytes).data.contentEquals(bytes))
    }

    @Test
    fun `output and event payload stay the plain status line`() = runBlocking {
        val bytes = png(120, 60)
        val tool = BrowserScreenshotTool(
            FakeSource(BrowserTools.ScreenshotCapture.Saved(file, bytes)),
            ToolResultMediaPolicy(attachImages = true),
        )
        val result = tool.execute(BrowserScreenshotTool.Args(path = "screenshot.png"))

        val status = "✅ Screenshot saved to ${file.absolutePath}"
        assertEquals(status, tool.encodeResultToString(result, serializer))
        assertEquals(JSONPrimitive(status), tool.encodeResult(result, serializer))
        assertFalse(tool.encodeResult(result, serializer).toString().contains(Base64.getEncoder().encodeToString(bytes)))
        assertEquals(status, tool.decodeResult(tool.encodeResult(result, serializer), serializer).message)
    }

    @Test
    fun `text-only policy never decodes or attaches the image`() = runBlocking {
        val tool = BrowserScreenshotTool(
            FakeSource(BrowserTools.ScreenshotCapture.Saved(file, png(10, 10))),
            ToolResultMediaPolicy.TEXT_ONLY,
        )
        val result = tool.execute(BrowserScreenshotTool.Args(path = "screenshot.png"))

        assertNull(result.image)
        val parts = tool.encodeResultToParts(result, serializer)
        assertEquals("✅ Screenshot saved to ${file.absolutePath}", (parts.single() as MessagePart.Text).text)
        assertEquals("Take a screenshot of the current page", tool.descriptor.description)
    }

    @Test
    fun `large capture is downscaled under the byte cap`() = runBlocking {
        val tool = BrowserScreenshotTool(
            FakeSource(BrowserTools.ScreenshotCapture.Saved(file, png(1600, 2000, noise = true))),
            ToolResultMediaPolicy(attachImages = true),
        )
        val result = tool.execute(BrowserScreenshotTool.Args(path = "screenshot.png", fullPage = true))

        val ready = result.image as? PreparedToolImage.Ready ?: error("not attached: ${result.image}")
        assertTrue(ready.image.bytes.size <= ToolResultImages.MAX_IMAGE_BYTES)
        assertTrue(ready.image.height!! <= ToolResultImages.MAX_EDGE_PX)
        assertTrue(ready.image.height!! >= ToolResultImages.MIN_FALLBACK_EDGE_PX)
        val text = (tool.encodeResultToParts(result, serializer).first() as MessagePart.Text).text
        assertTrue(text.contains("downscaled from 1600x2000"), text)
    }

    @Test
    fun `unreadable capture is reported instead of attached`() = runBlocking {
        val tool = BrowserScreenshotTool(
            FakeSource(BrowserTools.ScreenshotCapture.Saved(file, "garbage".toByteArray())),
            ToolResultMediaPolicy(attachImages = true),
        )
        val parts = tool.encodeResultToParts(tool.execute(BrowserScreenshotTool.Args(path = "s.png")), serializer)

        val text = (parts.single() as MessagePart.Text).text
        assertTrue(text.contains("[screenshot image not attached: the data is not a readable image]"), text)
    }

    @Test
    fun `rejected path yields only the failure message`() = runBlocking {
        val tool = BrowserScreenshotTool(
            FakeSource(BrowserTools.ScreenshotCapture.Failed("❌ Screenshot path rejected: outside workspace")),
            ToolResultMediaPolicy(attachImages = true),
        )
        val result = tool.execute(BrowserScreenshotTool.Args(path = "../../etc/passwd"))

        assertNull(result.image)
        assertEquals(
            listOf<MessagePart.ContentPart>(MessagePart.Text("❌ Screenshot path rejected: outside workspace")),
            tool.encodeResultToParts(result, serializer),
        )
    }

    @Test
    fun `real BrowserTools rejects an escaping path before touching Playwright`() = runBlocking {
        // validateOutputPath throws before getOrCreatePage, so no browser is launched here.
        for (path in listOf("/etc/cron.d/braidrun-screenshot.png", "../../../../.ssh/authorized_keys")) {
            val capture = BrowserTools(emptyList()).captureScreenshot(path)
            assertTrue(capture is BrowserTools.ScreenshotCapture.Failed, "$path: $capture")
            val message = (capture as BrowserTools.ScreenshotCapture.Failed).message
            assertTrue(message.startsWith("❌ Screenshot path rejected"), message)
        }

        val tool = BrowserScreenshotTool(BrowserTools(emptyList()), ToolResultMediaPolicy(attachImages = true))
        val parts = tool.encodeResultToParts(tool.execute(BrowserScreenshotTool.Args(path = "/etc/cron.d/x.png")), serializer)
        assertTrue(parts.none { it is MessagePart.Attachment })
        assertTrue((parts.single() as MessagePart.Text).text.startsWith("❌ Screenshot path rejected"))
    }

    @Test
    fun `per-run image budget stops attaching once spent`() = runBlocking {
        val source = FakeSource(BrowserTools.ScreenshotCapture.Saved(file, png(30, 30)))
        val tool = BrowserScreenshotTool(source, ToolResultMediaPolicy(attachImages = true, maxImagesPerRun = 2))

        val attached = (1..3).map {
            tool.encodeResultToParts(tool.execute(BrowserScreenshotTool.Args(path = "s.png")), serializer)
                .any { it is MessagePart.Attachment }
        }

        assertEquals(listOf(true, true, false), attached)
        val last = tool.execute(BrowserScreenshotTool.Args(path = "s.png")).image
        assertTrue(last is PreparedToolImage.Rejected && last.reason.contains("per-run budget"), "$last")
    }

    @Test
    fun `tool keeps the browser_screenshot name and argument schema`() {
        val tool = BrowserScreenshotTool(FakeSource(BrowserTools.ScreenshotCapture.Failed("x")), ToolResultMediaPolicy.TEXT_ONLY)
        assertEquals("browser_screenshot", tool.descriptor.name)
        assertEquals(listOf("path"), tool.descriptor.requiredParameters.map { it.name })
        assertEquals(setOf("contextId", "fullPage"), tool.descriptor.optionalParameters.map { it.name }.toSet())
    }
}
