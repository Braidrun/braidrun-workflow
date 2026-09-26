package com.fartech.agents.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import com.fartech.agents.commons.PreparedToolImage
import com.fartech.agents.commons.ToolResultImages
import com.fartech.agents.commons.ToolResultMediaPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * `browser_screenshot`: same name, arguments and saved PNG as the former `@Tool` method on
 * [BrowserTools], plus — when [mediaPolicy] allows — the screenshot itself as an image part of the
 * tool result, so a vision model can look at the page instead of being told a file path.
 *
 * The image comes from Playwright's return value (this tool's own output); nothing is read back from
 * the LLM-chosen path. It is normalised by [ToolResultImages] (downscaled, size-capped). The text part,
 * `ReceivedToolResult.output` and the `tool_call_completed` payload stay the plain status line.
 */
class BrowserScreenshotTool(
    private val source: ScreenshotSource,
    private val mediaPolicy: ToolResultMediaPolicy,
) : Tool<BrowserScreenshotTool.Args, BrowserScreenshotTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = if (mediaPolicy.attachImages) {
        "Take a screenshot of the current page. The PNG is saved to `path` and the screenshot is also " +
            "returned to you as an image (downscaled to at most ${ToolResultImages.MAX_EDGE_PX}px), so you can " +
            "check what the page shows."
    } else {
        "Take a screenshot of the current page"
    },
) {

    constructor(browserTools: BrowserTools, mediaPolicy: ToolResultMediaPolicy) : this(
        ScreenshotSource { path, contextId, fullPage -> browserTools.captureScreenshot(path, contextId, fullPage) },
        mediaPolicy,
    )

    /**
     * Produces the screenshot. The bytes in [BrowserTools.ScreenshotCapture.Saved.png] are the capture
     * itself (Playwright's return value), never a file read from the LLM-supplied path.
     */
    fun interface ScreenshotSource {
        suspend fun capture(path: String, contextId: String, fullPage: Boolean): BrowserTools.ScreenshotCapture
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Path to save the screenshot image (e.g., 'screenshot.png')")
        val path: String,
        @property:LLMDescription("Optional context ID")
        val contextId: String = "default",
        @property:LLMDescription("Whether to take a full page screenshot")
        val fullPage: Boolean = false,
    )

    /** [image] is null when images are off or the capture failed; it is never serialized. */
    @Serializable
    data class Result(
        val message: String,
        @Transient val image: PreparedToolImage? = null,
    )

    override suspend fun execute(args: Args): Result =
        when (val capture = source.capture(args.path, args.contextId, args.fullPage)) {
            is BrowserTools.ScreenshotCapture.Failed -> Result(capture.message)
            is BrowserTools.ScreenshotCapture.Saved -> Result(
                message = "✅ Screenshot saved to ${capture.file.absolutePath}",
                // No ownership check: BrowserTools keys contexts by the caller's ToolRunScope, so the
                // page is always this run's own.
                image = if (mediaPolicy.attachImages) {
                    mediaPolicy.prepareImage { ToolResultImages.prepare(capture.png) }
                } else null,
            )
        }

    override fun encodeResultToString(result: Result, serializer: JSONSerializer): String = result.message

    /** A JSON string, exactly what the reflection-based tool emitted, so event payloads are unchanged. */
    override fun encodeResult(result: Result, serializer: JSONSerializer): JSONElement = JSONPrimitive(result.message)

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): Result =
        Result(message = (rawResult as? JSONPrimitive)?.content ?: rawResult.toString())

    override fun encodeResultToParts(result: Result, serializer: JSONSerializer): List<MessagePart.ContentPart> =
        when (val image = result.image) {
            null -> listOf(MessagePart.Text(result.message))
            is PreparedToolImage.Ready -> listOf(
                MessagePart.Text("${result.message}\n${ToolResultImages.attachedNote(1, image.image)}"),
                image.image.toContentPart(),
            )
            is PreparedToolImage.Rejected -> listOf(
                MessagePart.Text("${result.message}\n[screenshot image not attached: ${image.reason}]")
            )
        }

    companion object {
        const val NAME = "browser_screenshot"
    }
}
