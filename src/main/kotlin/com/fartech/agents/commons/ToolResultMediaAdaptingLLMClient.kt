package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging

private val toolResultMediaLogger = KotlinLogging.logger("com.fartech.agents.commons.ToolResultMedia")

/**
 * Default and upper bound for the tool-result images re-sent per request; older ones become
 * placeholders. Every kept image is re-sent on every round (~1.2-1.6K input tokens each), so the
 * default stays small; workflows can raise it with [MAX_IMAGES_PER_REQUEST_PARAMETER].
 */
const val DEFAULT_TOOL_RESULT_IMAGES_PER_REQUEST = 4
const val MAX_TOOL_RESULT_IMAGES_PER_REQUEST = 12

/** Workflow parameter overriding [DEFAULT_TOOL_RESULT_IMAGES_PER_REQUEST], clamped to 0..[MAX_TOOL_RESULT_IMAGES_PER_REQUEST]. */
const val MAX_IMAGES_PER_REQUEST_PARAMETER = "tool_result_images_max_per_request"

/**
 * Older images are dropped in batches of `max(1, maxImages / 2)`, so the (byte-identical) history
 * prefix — and a provider prompt cache — changes once every few images instead of on every new one.
 */
internal fun toolResultImagePruneStep(maxImages: Int): Int = maxOf(1, maxImages / 2)

internal const val REASON_OLDER_IMAGE_PRUNED =
    "older tool-result image not re-sent, only the most recent images are kept"

/** Per-request image cap from [MAX_IMAGES_PER_REQUEST_PARAMETER]; tolerant of string values, never throws. */
fun toolResultImagesPerRequest(parameters: List<ConfigurationParameter>): Int {
    val raw = (parameters.find { it.key == MAX_IMAGES_PER_REQUEST_PARAMETER }?.value
        as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
    return (raw ?: DEFAULT_TOOL_RESULT_IMAGES_PER_REQUEST).coerceIn(0, MAX_TOOL_RESULT_IMAGES_PER_REQUEST)
}

/**
 * Per-request gate for non-text tool results, wrapped around every provider client in
 * [createPromptExecutor] (primary and cascade tiers).
 *
 * Koog forwards `Tool.Result.parts` unchanged to whatever client serves the call. Most of our
 * routes cannot carry an image there (see [ToolResultImageSupport]); Chat Completions clients drop
 * it with only a log line and Bedrock would throw. Because this decorator sits below
 * `MultiLLMPromptExecutor` it sees the client and model that actually serve the request — including
 * a provider fallback or a cascade tier with a different model — and rewrites each tool result with
 * attachments into one text part (image placeholders included) followed by the images that route
 * can show. The rewrite is a pure function of (prompt, client, model), so repeated rounds send a
 * byte-identical history prefix.
 */
class ToolResultMediaAdaptingLLMClient(
    private val delegate: LLMClient,
    private val maxImages: Int = DEFAULT_TOOL_RESULT_IMAGES_PER_REQUEST,
) : LLMClient() {

    override val clientName: String get() = delegate.clientName

    override fun llmProvider(): LLMProvider = delegate.llmProvider()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = delegate.execute(adapt(prompt, model), model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(adapt(prompt, model), model, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = delegate.executeMultipleChoices(adapt(prompt, model), model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(adapt(prompt, model), model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override suspend fun embed(text: String, model: LLModel): List<Double> = delegate.embed(text, model)

    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> =
        delegate.embed(inputs, model)

    override fun getStandardJsonSchemaGenerator(): StandardJsonSchemaGenerator =
        delegate.getStandardJsonSchemaGenerator()

    override fun getBasicJsonSchemaGenerator(): BasicJsonSchemaGenerator = delegate.getBasicJsonSchemaGenerator()

    override fun close() {
        delegate.close()
    }

    private fun adapt(prompt: Prompt, model: LLModel): Prompt {
        if (!prompt.hasToolResultAttachments()) return prompt
        val route = ToolResultImageSupport.route(delegate, model, prompt.params)
        toolResultMediaLogger.debug {
            "Tool-result attachments for ${delegate.clientName}/${model.id}: route=$route, " +
                "canView=${ToolResultImageSupport.canViewImages(route, model)}"
        }
        return prompt.withToolResultMediaAdapted(maxImages = maxImages, step = toolResultImagePruneStep(maxImages)) { image ->
            ToolResultImageSupport.canSend(route, model, image)
        }
    }
}

internal fun Prompt.hasToolResultAttachments(): Boolean = messages.any { it.hasToolResultAttachments() }

private fun Message.hasToolResultAttachments(): Boolean =
    this is Message.User && parts.any { it is MessagePart.Tool.Result && it.hasAttachments() }

private fun MessagePart.Tool.Result.hasAttachments(): Boolean = parts.any { it is MessagePart.Attachment }

/**
 * Number of oldest sendable images to replace so at most [maxImages] remain. Grows in steps of
 * [step] and never shrinks as images are appended.
 */
internal fun prunedToolResultImageCount(sendable: Int, maxImages: Int, step: Int): Int {
    val excess = sendable - maxImages
    if (excess <= 0) return 0
    return ((excess + step - 1) / step) * step
}

/**
 * Rewrites every tool result that carries attachments: images accepted by [canSend] stay (minus the
 * oldest ones beyond [maxImages]); everything else becomes a placeholder merged into a single leading
 * text part. One text part matters for Gemini, which writes each text part to the same `result` key.
 * Tool results without attachments and non-tool messages are returned as-is.
 */
internal fun Prompt.withToolResultMediaAdapted(
    maxImages: Int = DEFAULT_TOOL_RESULT_IMAGES_PER_REQUEST,
    step: Int = toolResultImagePruneStep(maxImages),
    canSend: (AttachmentSource.Image) -> Boolean,
): Prompt {
    if (!hasToolResultAttachments()) return this
    val sendable = messages.sumOf { message ->
        if (message !is Message.User) {
            0
        } else {
            message.parts.filterIsInstance<MessagePart.Tool.Result>().sumOf { result ->
                result.parts.count { part ->
                    part is MessagePart.Attachment && (part.source as? AttachmentSource.Image)?.let(canSend) == true
                }
            }
        }
    }
    val pruned = prunedToolResultImageCount(sendable, maxImages, step)
    var sendableIndex = 0
    return withMessages { messages ->
        messages.map { message ->
            if (message !is Message.User || !message.hasToolResultAttachments()) {
                message
            } else {
                message.copy(
                    parts = message.parts.map { part ->
                        if (part !is MessagePart.Tool.Result || !part.hasAttachments()) {
                            part
                        } else {
                            part.adaptAttachments { image ->
                                when {
                                    !canSend(image) -> ToolResultImages.REASON_MODEL_CANNOT_VIEW
                                    sendableIndex++ < pruned -> REASON_OLDER_IMAGE_PRUNED
                                    else -> null
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

/** [omitReason] returns why an image is not sent, or null to keep it. Other attachments never go out. */
private fun MessagePart.Tool.Result.adaptAttachments(
    omitReason: (AttachmentSource.Image) -> String?,
): MessagePart.Tool.Result {
    val texts = mutableListOf<String>()
    val kept = mutableListOf<MessagePart.ContentPart>()
    var omitted = 0
    parts.forEach { part ->
        when (part) {
            is MessagePart.Text -> texts += part.text
            is MessagePart.Attachment -> {
                val source = part.source
                val reason = if (source is AttachmentSource.Image) {
                    omitReason(source)
                } else {
                    "this attachment type is not sent inside tool results"
                }
                if (reason == null) {
                    kept += part
                } else {
                    omitted++
                    texts += ToolResultImages.omittedNote(source.label(), source.mimeType, source.byteSize(), reason)
                }
            }
        }
    }
    if (omitted == 0 && parts.count { it is MessagePart.Text } <= 1) return this
    val text = texts.joinToString("\n")
    return copy(parts = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { MessagePart.Text(it) }) + kept)
}

private fun AttachmentSource.label(): String = when (this) {
    is AttachmentSource.Image -> "image"
    is AttachmentSource.Audio -> "audio"
    is AttachmentSource.Video -> "video"
    is AttachmentSource.File -> "file"
}

private fun AttachmentSource.byteSize(): Long? = when (val content = content) {
    is AttachmentContent.Binary.Bytes -> content.data.size.toLong()
    is AttachmentContent.Binary.Base64 -> ToolResultImages.decodedBase64Size(content.base64)
    is AttachmentContent.PlainText -> content.text.length.toLong()
    is AttachmentContent.URL -> null
}

/**
 * Skips [delegate] for prompts whose tool results carry attachments.
 *
 * `CachedPromptExecutor` sits above the adapting clients, so it would see the raw image bytes:
 * `PromptCache.Request.asCacheKey` JSON-encodes the whole request (up to megabytes of base64) on every
 * call, and the file backend writes each multi-MB request to disk (up to `max_files` of them). Such a
 * conversation is effectively unique anyway, so a cache hit is not worth that cost.
 */
class ToolResultMediaBypassingPromptCache(
    private val delegate: PromptCache,
) : PromptCache {
    override suspend fun get(request: PromptCache.Request): Message.Assistant? =
        if (request.prompt.hasToolResultAttachments()) null else delegate.get(request)

    override suspend fun put(request: PromptCache.Request, response: Message.Assistant) {
        if (!request.prompt.hasToolResultAttachments()) delegate.put(request, response)
    }
}
