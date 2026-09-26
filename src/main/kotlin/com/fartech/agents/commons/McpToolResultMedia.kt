package com.fartech.agents.commons

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpTool
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent

/**
 * Koog's [McpTool] turns a whole `CallToolResult` into JSON text, so an MCP screenshot
 * (`ImageContent.data`, base64) reaches the model as hundreds of KB of text it cannot see, and the
 * same base64 lands in `tool_call_completed` events through `encodeResult`.
 *
 * This wrapper keeps the tool's name, schema and execution, and changes only how results are encoded:
 *  - text for the model, `ReceivedToolResult.output` and the event payload: base64 of images, audio
 *    and blob resources is replaced by a short placeholder (type, size, what happened to it);
 *  - [encodeResultToParts]: when [policy] allows, up to [ToolResultImages.MAX_IMAGES_PER_RESULT]
 *    images are normalised by [ToolResultImages] and sent as image parts after the text.
 *
 * `decodeResult` of an encoded result therefore returns placeholders instead of the original bytes;
 * nothing in the engine decodes MCP results.
 */
@OptIn(InternalAgentsApi::class)
internal class MediaAwareMcpTool(
    private val delegate: McpTool,
    private val policy: ToolResultMediaPolicy,
) : Tool<JSONObject, CallToolResult?>(
    argsType = delegate.argsType,
    resultType = delegate.resultType,
    descriptor = delegate.descriptor,
    metadata = delegate.metadata,
) {
    override suspend fun execute(args: JSONObject): CallToolResult? = delegate.execute(args)

    override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): JSONObject =
        delegate.decodeArgs(rawArgs, serializer)

    override fun encodeArgs(args: JSONObject, serializer: JSONSerializer): JSONObject =
        delegate.encodeArgs(args, serializer)

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): CallToolResult? =
        delegate.decodeResult(rawResult, serializer)

    override fun encodeResult(result: CallToolResult?, serializer: JSONSerializer): JSONElement =
        delegate.encodeResult(result?.withTextOnlyPlaceholders(), serializer)

    override fun encodeResultToString(result: CallToolResult?, serializer: JSONSerializer): String =
        delegate.encodeResultToString(result?.withTextOnlyPlaceholders(), serializer)

    override fun encodeResultToParts(result: CallToolResult?, serializer: JSONSerializer): List<MessagePart.ContentPart> {
        val images = result?.content?.filterIsInstance<ImageContent>().orEmpty()
        if (result == null || result.isError == true || images.isEmpty() || !policy.attachImages) {
            val reason = if (policy.attachImages) MCP_TEXT_CHANNEL_REASON else ToolResultImages.REASON_MODEL_CANNOT_VIEW
            return listOf(MessagePart.Text(delegate.encodeResultToString(result?.withBinaryPlaceholders(reason), serializer)))
        }
        val prepared = images.mapIndexed { index, image ->
            if (index < ToolResultImages.MAX_IMAGES_PER_RESULT) {
                policy.prepareImage { ToolResultImages.prepareBase64(image.data) }
            } else {
                null
            }
        }
        val text = delegate.encodeResultToString(
            result.withBinaryPlaceholders { index, image ->
                when (val outcome = prepared[index]) {
                    is PreparedToolImage.Ready -> ToolResultImages.attachedNote(index + 1, outcome.image)
                    is PreparedToolImage.Rejected -> image.omittedNote(index, outcome.reason)
                    null -> image.omittedNote(
                        index,
                        "only the first ${ToolResultImages.MAX_IMAGES_PER_RESULT} images of a tool result are attached",
                    )
                }
            },
            serializer,
        )
        val imageParts = prepared.filterIsInstance<PreparedToolImage.Ready>().map { it.image.toContentPart() }
        return listOf(MessagePart.Text(text)) + imageParts
    }
}

/** Placeholder reason in the plain-text encodings (`output`, events), whatever the parts carry. */
private const val MCP_TEXT_CHANNEL_REASON = "binary content is not included in text"


/** Wraps every [McpTool] in [this] registry with [MediaAwareMcpTool]; other tools are kept as-is. */
@OptIn(InternalAgentsApi::class)
internal fun ToolRegistry.withMcpToolResultMedia(policy: ToolResultMediaPolicy): ToolRegistry {
    val wrapped = tools.map { tool -> if (tool is McpTool) MediaAwareMcpTool(tool, policy) else tool }
    return ToolRegistry { tools(wrapped) }
}

private fun CallToolResult.withTextOnlyPlaceholders(): CallToolResult = withBinaryPlaceholders(MCP_TEXT_CHANNEL_REASON)

private fun CallToolResult.withBinaryPlaceholders(reason: String): CallToolResult =
    withBinaryPlaceholders { index, image -> image.omittedNote(index, reason) }

/**
 * Replaces base64 payloads with placeholders: images through [describeImage] (0-based image index),
 * audio and blob resources with a fixed note. Text, links and structured content are unchanged.
 */
private fun CallToolResult.withBinaryPlaceholders(describeImage: (Int, ImageContent) -> String): CallToolResult {
    var imageIndex = 0
    return copy(
        content = content.map { block ->
            when (block) {
                is ImageContent -> block.copy(data = describeImage(imageIndex++, block))
                is AudioContent -> block.copy(
                    data = ToolResultImages.omittedNote(
                        "audio",
                        block.mimeType,
                        ToolResultImages.decodedBase64Size(block.data),
                        "audio is not sent to the model",
                    )
                )
                is EmbeddedResource -> when (val resource = block.resource) {
                    is BlobResourceContents -> block.copy(
                        resource = resource.copy(
                            blob = ToolResultImages.omittedNote(
                                "binary resource",
                                resource.mimeType,
                                ToolResultImages.decodedBase64Size(resource.blob),
                                "binary resources are not sent to the model",
                            )
                        )
                    )
                    else -> block
                }
                else -> block
            }
        }
    )
}

private fun ImageContent.omittedNote(index: Int, reason: String): String =
    ToolResultImages.omittedNote("image ${index + 1}", mimeType, ToolResultImages.decodedBase64Size(data), reason)
