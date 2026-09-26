package com.fartech.agents.commons

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpTool
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

@OptIn(InternalAgentsApi::class)
class McpToolResultMediaTest {

    private val serializer = KotlinxSerializer()
    private val pngBytes = ToolResultMediaFixtures.png(80, 40)
    private val pngBase64 = Base64.getEncoder().encodeToString(pngBytes)

    private val mcpTool = McpTool(
        mcpClient = Client(Implementation(name = "test", version = "1.0")),
        descriptor = ToolDescriptor(name = "take_screenshot", description = "MCP screenshot"),
        metadata = emptyMap(),
    )

    private val result = CallToolResult(
        content = listOf(
            TextContent(text = "Took a screenshot"),
            ImageContent(data = pngBase64, mimeType = "image/png"),
        ),
    )

    private fun tool(attachImages: Boolean) = MediaAwareMcpTool(mcpTool, ToolResultMediaPolicy(attachImages))

    @Test
    fun `vision policy maps ImageContent to an image part and keeps base64 out of the text`() {
        val parts = tool(attachImages = true).encodeResultToParts(result, serializer)

        assertEquals(2, parts.size)
        val text = (parts[0] as MessagePart.Text).text
        assertFalse(text.contains(pngBase64))
        assertTrue(text.contains("Took a screenshot"), text)
        assertTrue(text.contains("[image 1 attached to this tool result: image/png, 80x40"), text)

        val source = (parts[1] as MessagePart.Attachment).source as AttachmentSource.Image
        assertEquals("image/png", source.mimeType)
        assertTrue((source.content as AttachmentContent.Binary.Bytes).data.contentEquals(pngBytes))
    }

    @Test
    fun `text-only policy maps ImageContent to a placeholder`() {
        val parts = tool(attachImages = false).encodeResultToParts(result, serializer)

        val text = (parts.single() as MessagePart.Text).text
        assertFalse(text.contains(pngBase64))
        assertTrue(text.contains("[image 1 omitted (image/png, "), text)
        assertTrue(text.contains(ToolResultImages.REASON_MODEL_CANNOT_VIEW), text)
    }

    @Test
    fun `output string and event payload never carry base64`() {
        val mediaTool = tool(attachImages = true)
        val output = mediaTool.encodeResultToString(result, serializer)
        val eventPayload = mediaTool.encodeResult(result, serializer).toString()

        listOf(output, eventPayload).forEach { encoded ->
            assertFalse(encoded.contains(pngBase64), encoded)
            assertTrue(encoded.contains("image 1 omitted (image/png"), encoded)
            assertTrue(encoded.contains("Took a screenshot"), encoded)
        }
    }

    @Test
    fun `invalid image data becomes a placeholder instead of an image part`() {
        val broken = CallToolResult(content = listOf(ImageContent(data = "bm90IGFuIGltYWdl", mimeType = "image/png")))
        val parts = tool(attachImages = true).encodeResultToParts(broken, serializer)

        val text = (parts.single() as MessagePart.Text).text
        assertTrue(text.contains("[image 1 omitted (image/png, 12 B): the data is not a readable image]"), text)
    }

    @Test
    fun `images beyond the per-result limit are placeholders`() {
        val many = CallToolResult(
            content = List(ToolResultImages.MAX_IMAGES_PER_RESULT + 1) { ImageContent(data = pngBase64, mimeType = "image/png") }
        )
        val parts = tool(attachImages = true).encodeResultToParts(many, serializer)

        assertEquals(ToolResultImages.MAX_IMAGES_PER_RESULT, parts.count { it is MessagePart.Attachment })
        val text = (parts.first() as MessagePart.Text).text
        assertTrue(text.contains("only the first ${ToolResultImages.MAX_IMAGES_PER_RESULT} images"), text)
    }

    @Test
    fun `registry wrapper replaces only MCP tools and keeps their descriptors`() {
        val registry = ToolRegistry { tool(mcpTool) }.withMcpToolResultMedia(ToolResultMediaPolicy.TEXT_ONLY)
        val wrapped = registry.tools.single()
        assertTrue(wrapped is MediaAwareMcpTool)
        assertEquals(mcpTool.descriptor, wrapped.descriptor)
    }
}
