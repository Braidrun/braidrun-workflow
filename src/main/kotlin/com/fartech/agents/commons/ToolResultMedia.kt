package com.fartech.agents.commons

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.OpenAIBaseSettings
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Non-text tool results (Koog 1.1.1+: `Message.Tool.Result.parts` is `List<ContentPart>`,
 * `ToolBase.encodeResultToParts`).
 *
 * Three pieces live here and are used together:
 *
 *  - [ToolResultImageSupport] — the provider matrix, read from the Koog 1.3.0 client sources (Koog >= 1.1.1 carries `List<ContentPart>`):
 *
 *    | Client (as built by `createLLMClient`)                     | Image parts inside a tool result          |
 *    |------------------------------------------------------------|-------------------------------------------|
 *    | `AnthropicLLMClient`                                       | sent as `image` blocks in `tool_result`   |
 *    | `GoogleLLMClient`                                          | sent as `functionResponse.parts` inline   |
 *    |                                                            | data (Binary only; Gemini 3+ per Google;  |
 *    |                                                            | every Text part overwrites `result`)      |
 *    | `OpenAILLMClient`, Responses path (model declares only     | sent as `input_image`                     |
 *    |   `openai.responses`, base URL not `openai.azure.com`)     |                                           |
 *    | `OpenAILLMClient` Chat Completions, `OpenRouterLLMClient`, | dropped with a WARN log                   |
 *    |   `DeepSeekLLMClient`, `MistralAILLMClient`,               |                                           |
 *    |   `DashscopeLLMClient` (`AbstractOpenAILLMClient`)          |                                           |
 *    | `OllamaClient`                                             | dropped silently (`Tool.Result.output`)   |
 *    | `BedrockLLMClient` (not built by us)                       | Converse `require`s vision, InvokeModel   |
 *    |                                                            | throws on any non-text part               |
 *
 *  - [ToolResultMediaPolicy] — build-time hint for tools: attach image parts only when the
 *    agent's model can see them, so text-only agents never carry image bytes in their history.
 *
 *  - [ToolResultImages] — size/format normalisation of image bytes produced by a tool
 *    (downscale to [ToolResultImages.MAX_EDGE_PX], cap at [ToolResultImages.MAX_IMAGE_BYTES])
 *    and the text placeholders used wherever an image is not sent.
 *
 * The authoritative per-request gate is [ToolResultMediaAdaptingLLMClient]: it sees the actual
 * client and model of every call (MultiLLM fallback and cascade tiers included) and turns every
 * image the route cannot take into a placeholder, so the policy above only saves memory.
 */
enum class ToolResultImageRoute(val acceptedMimeTypes: Set<String>) {
    ANTHROPIC(setOf(ToolResultImages.PNG, ToolResultImages.JPEG, ToolResultImages.GIF, ToolResultImages.WEBP)),
    GOOGLE(setOf(ToolResultImages.PNG, ToolResultImages.JPEG, ToolResultImages.WEBP)),
    OPENAI_RESPONSES(setOf(ToolResultImages.PNG, ToolResultImages.JPEG, ToolResultImages.GIF, ToolResultImages.WEBP)),
    TEXT_ONLY(emptySet()),
}

object ToolResultImageSupport {

    private val geminiMajorVersion = Regex("gemini-(\\d+)")

    /**
     * Route of a request that [client] sends for [model] (per request, used by the client decorator).
     * Decorators such as [ModelParamsSanitizingLLMClient] are looked through, so the provider client
     * underneath decides.
     */
    fun route(client: LLMClient, model: LLModel, params: LLMParams? = null): ToolResultImageRoute = when (val base = client.unwrapDecorators()) {
        is AnthropicLLMClient -> ToolResultImageRoute.ANTHROPIC
        is GoogleLLMClient -> ToolResultImageRoute.GOOGLE
        is OpenAILLMClient ->
            if (!isAzureOpenAI(base) && usesOpenAIResponsesApi(model, params)) {
                ToolResultImageRoute.OPENAI_RESPONSES
            } else {
                ToolResultImageRoute.TEXT_ONLY
            }
        else -> ToolResultImageRoute.TEXT_ONLY
    }

    /**
     * `OpenAILLMClient.determineParams` forces Chat Completions (which drops tool-result images) for
     * any `openai.azure.com` base URL. The settings are private, so read them reflectively; when that
     * fails, answer "Azure" so the image becomes a placeholder rather than being dropped silently.
     */
    internal fun isAzureOpenAI(client: OpenAILLMClient): Boolean = openAIBaseUrl(client)
        ?.let { AZURE_OPENAI_HOST_MARKER in it }
        ?: true

    internal fun openAIBaseUrl(client: OpenAILLMClient): String? = runCatching {
        var type: Class<*>? = client.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == "settings" && OpenAIBaseSettings::class.java.isAssignableFrom(it.type) }
            if (field != null) {
                field.isAccessible = true
                return@runCatching (field.get(client) as OpenAIBaseSettings).baseUrl
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    private const val AZURE_OPENAI_HOST_MARKER = "openai.azure.com"

    /** Route [createLLMClient] would pick for [model] (build time, before any client exists). */
    fun route(model: LLModel): ToolResultImageRoute = when (model.provider) {
        LLMProvider.Anthropic -> ToolResultImageRoute.ANTHROPIC
        LLMProvider.Google -> ToolResultImageRoute.GOOGLE
        LLMProvider.OpenRouter, LLMProvider.DeepSeek, LLMProvider.MistralAI, LLMProvider.Ollama ->
            ToolResultImageRoute.TEXT_ONLY
        // OpenAI and every OpenAI-compatible provider (including the `else` branch) get an OpenAILLMClient.
        else -> if (usesOpenAIResponsesApi(model, null)) ToolResultImageRoute.OPENAI_RESPONSES else ToolResultImageRoute.TEXT_ONLY
    }

    /** Whether [model] can look at an image returned by a tool when it is reached through [route]. */
    fun canViewImages(route: ToolResultImageRoute, model: LLModel): Boolean = when (route) {
        ToolResultImageRoute.TEXT_ONLY -> false
        // Google documents multimodal function responses for the Gemini 3 series only.
        ToolResultImageRoute.GOOGLE -> model.supports(LLMCapability.Vision.Image) && isGemini3OrLater(model.id)
        ToolResultImageRoute.ANTHROPIC, ToolResultImageRoute.OPENAI_RESPONSES -> model.supports(LLMCapability.Vision.Image)
    }

    /** Build-time check: does the client [model] will be routed to show it tool-result images? */
    fun supportsImages(model: LLModel): Boolean = canViewImages(route(model), model)

    /** Whether one concrete image may go out on [route] for [model]. */
    fun canSend(route: ToolResultImageRoute, model: LLModel, image: AttachmentSource.Image): Boolean {
        if (!canViewImages(route, model)) return false
        if (image.mimeType.lowercase() !in route.acceptedMimeTypes) return false
        // GoogleLLMClient skips URL content with a warning; only bytes reach Gemini.
        return route != ToolResultImageRoute.GOOGLE || image.content is AttachmentContent.Binary
    }

    // Mirrors OpenAILLMClient.determineParams: explicit Responses params win, then Completions-capable
    // models use Chat Completions (which drops images). Azure base URLs (always Chat Completions) are not
    // visible here; there the image is dropped with a Koog WARN, never an exception.
    private fun usesOpenAIResponsesApi(model: LLModel, params: LLMParams?): Boolean = when (params) {
        is OpenAIResponsesParams -> model.supports(LLMCapability.OpenAIEndpoint.Responses)
        is OpenAIChatParams -> false
        else -> model.supports(LLMCapability.OpenAIEndpoint.Responses) &&
            !model.supports(LLMCapability.OpenAIEndpoint.Completions)
    }

    internal fun isGemini3OrLater(modelId: String): Boolean =
        geminiMajorVersion.find(modelId.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 3 } ?: false
}

/**
 * Build-time decision whether tools attach image parts to their results.
 *
 * Only an optimisation: [ToolResultMediaAdaptingLLMClient] still replaces images a request's
 * actual route cannot take. `attachImages = false` keeps image bytes out of the agent's
 * history entirely.
 */
data class ToolResultMediaPolicy(
    val attachImages: Boolean,
    /** Images this policy instance (one agent build) attaches at most; see [prepareImage]. */
    val maxImagesPerRun: Int = DEFAULT_MAX_IMAGES_PER_RUN,
) {
    /**
     * Images already attached through this policy. The agent's own history (and in-memory
     * checkpoints) keep every attached image's bytes for the whole run, while the per-request adapter
     * only prunes its copy; this budget bounds that retained heap in the shared web JVM.
     */
    private val attachedImages = AtomicInteger()

    /**
     * Runs [prepare] if this policy attaches images and the per-run budget is not spent; returns
     * `null` when images are off. A [PreparedToolImage.Ready] result consumes one unit of budget.
     */
    fun prepareImage(prepare: () -> PreparedToolImage): PreparedToolImage? {
        if (!attachImages) return null
        if (attachedImages.get() >= maxImagesPerRun) return budgetSpent()
        val prepared = prepare()
        if (prepared is PreparedToolImage.Ready && attachedImages.incrementAndGet() > maxImagesPerRun) {
            return budgetSpent()
        }
        return prepared
    }

    private fun budgetSpent() = PreparedToolImage.Rejected(
        "the per-run budget of $maxImagesPerRun tool-result images is used up"
    )

    companion object {
        /** Workflow parameter bounding images attached per agent run (retained heap). */
        const val MAX_IMAGES_PER_RUN_PARAMETER = "tool_result_images_max_per_run"
        const val DEFAULT_MAX_IMAGES_PER_RUN = 24
        private const val UPPER_MAX_IMAGES_PER_RUN = 200

        /** Workflow parameter to turn tool-result images off (cost control). Default: on. */
        const val ENABLED_PARAMETER = "tool_result_images_enabled"

        val TEXT_ONLY = ToolResultMediaPolicy(attachImages = false)

        /** Policy for an agent running on [model]; `null` (unresolvable model) means text only. */
        fun forModel(model: LLModel?, parameters: List<ConfigurationParameter>): ToolResultMediaPolicy {
            if (model == null || !parameters.flag(ENABLED_PARAMETER, default = true)) return TEXT_ONLY
            // Durable checkpoints serialize the whole message history (Mongo documents cap at 16 MB);
            // keep image bytes out of it.
            if (usesDurableCheckpoints(parameters)) return TEXT_ONLY
            val perRun = ((parameters.find { it.key == MAX_IMAGES_PER_RUN_PARAMETER }?.value as? JsonPrimitive)
                ?.content?.trim()?.toIntOrNull() ?: DEFAULT_MAX_IMAGES_PER_RUN).coerceIn(0, UPPER_MAX_IMAGES_PER_RUN)
            return ToolResultMediaPolicy(
                attachImages = perRun > 0 && ToolResultImageSupport.supportsImages(model),
                maxImagesPerRun = perRun,
            )
        }

        /** Policy for the agent's default model as `buildAgent` resolves it from `llm_config`. */
        fun forParameters(parameters: List<ConfigurationParameter>): ToolResultMediaPolicy =
            forModel(runCatching { parameters.getLLMGroupConfig().resolveDefaultLLModel() }.getOrNull(), parameters)

        private fun usesDurableCheckpoints(parameters: List<ConfigurationParameter>): Boolean =
            parameters.flag("enable_persistence", default = false) &&
                !(runCatching { parameters.parameter("persistence_storage_type", "mongodb") }.getOrNull() ?: "mongodb")
                    .trim().equals("memory", ignoreCase = true)

        /**
         * Tolerant boolean read: accepts JSON booleans and "true"/"false"/"1"/"0"/"yes"/"no"/"on"/"off"
         * strings; never throws (a malformed cost switch must not fail the agent build).
         */
        internal fun List<ConfigurationParameter>.flag(key: String, default: Boolean): Boolean {
            val primitive = find { it.key == key }?.value as? JsonPrimitive ?: return default
            if (primitive is JsonNull) return default
            return when (primitive.content.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> default
            }
        }
    }
}

/**
 * An image ready to be sent inside a tool result. [bytes] already satisfy the limits in
 * [ToolResultImages]; the `source*` fields describe what the tool produced before normalisation.
 */
class ToolResultImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val sourceBytes: Int = bytes.size,
    val sourceWidth: Int? = width,
    val sourceHeight: Int? = height,
) {
    val format: String get() = mimeType.substringAfter('/')

    val downscaled: Boolean get() = sourceWidth != null && width != null && sourceWidth != width

    fun toContentPart(): MessagePart.Attachment = MessagePart.Attachment(
        AttachmentSource.Image(
            content = AttachmentContent.Binary.Bytes(bytes),
            format = format,
            mimeType = mimeType,
        )
    )

    fun describe(): String = buildString {
        append(ToolResultImages.describe(mimeType, bytes.size.toLong(), width, height))
        if (downscaled) append(", downscaled from ${sourceWidth}x$sourceHeight")
    }
}

/** Outcome of [ToolResultImages.prepare]. */
sealed interface PreparedToolImage {
    data class Ready(val image: ToolResultImage) : PreparedToolImage
    data class Rejected(val reason: String) : PreparedToolImage
}

object ToolResultImages {
    const val PNG = "image/png"
    const val JPEG = "image/jpeg"
    const val GIF = "image/gif"
    const val WEBP = "image/webp"

    /** Longest edge sent to a model. Claude's standard vision limit; keeps an image near 1.6K tokens. */
    const val MAX_EDGE_PX = 1568

    /**
     * Largest encoded image sent. With [MAX_TOOL_RESULT_IMAGES_PER_REQUEST] images per request this
     * keeps the base64 payload (~16 MB) under Gemini's 20 MB inline limit and Anthropic's 32 MB body.
     */
    const val MAX_IMAGE_BYTES = 1_000_000

    /** Smallest longest edge tried when an image does not fit [MAX_IMAGE_BYTES] at [MAX_EDGE_PX]. */
    const val MIN_FALLBACK_EDGE_PX = 512

    /** Tool output larger than this is not decoded at all. */
    const val MAX_SOURCE_BYTES = 20 * 1024 * 1024

    /** Decompression-bomb guard, checked from the image header before decoding. */
    const val MAX_SOURCE_PIXELS = 100_000_000L

    /** Images one tool result may carry; the rest become placeholders. */
    const val MAX_IMAGES_PER_RESULT = 4

    /**
     * Canvas size from a RIFF/WebP header (VP8X, lossless VP8L or lossy VP8), or `null` when the
     * header is not one of those or is truncated.
     */
    internal fun readWebpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30) return null
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        val chunk = String(bytes, 12, 4, Charsets.US_ASCII)
        val dims = when (chunk) {
            "VP8X" -> (le24(24) + 1) to (le24(27) + 1)
            "VP8L" -> {
                if (u8(20) != 0x2F) return null
                val b1 = u8(21); val b2 = u8(22); val b3 = u8(23); val b4 = u8(24)
                val w = 1 + (b1 or ((b2 and 0x3F) shl 8))
                val h = 1 + ((b2 shr 6) or (b3 shl 2) or ((b4 and 0x0F) shl 10))
                w to h
            }
            "VP8 " -> {
                // Frame header: 3-byte frame tag, start code 9D 01 2A, then 14-bit width/height.
                if (u8(23) != 0x9D || u8(24) != 0x01 || u8(25) != 0x2A) return null
                (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
            }
            else -> return null
        }
        return dims.takeIf { it.first > 0 && it.second > 0 }
    }

    const val REASON_MODEL_CANNOT_VIEW = "the current model cannot view images returned by tools"

    private val JPEG_QUALITIES = floatArrayOf(0.85f, 0.7f)

    /**
     * Validates and normalises [bytes] produced by a tool. The container format is sniffed from the
     * bytes (a declared MIME type is never trusted: a mismatch makes Anthropic reject the whole
     * request). PNG/JPEG within the limits pass through unchanged; GIF, BMP/TIFF and oversized images
     * are decoded, downscaled to [MAX_EDGE_PX] and re-encoded as PNG or JPEG under [MAX_IMAGE_BYTES].
     * WebP passes through only when its header shows it within [MAX_EDGE_PX] and it fits the byte cap
     * (the JDK cannot decode it, so it cannot be downscaled).
     */
    fun prepare(bytes: ByteArray): PreparedToolImage {
        if (bytes.isEmpty()) return PreparedToolImage.Rejected("the image is empty")
        if (bytes.size > MAX_SOURCE_BYTES) {
            return PreparedToolImage.Rejected("the image is larger than ${formatBytes(MAX_SOURCE_BYTES.toLong())}")
        }
        val sniffed = sniffMimeType(bytes)
        val fitsBytes = bytes.size <= MAX_IMAGE_BYTES
        if (sniffed == WEBP) {
            // The JDK cannot decode (and so cannot downscale) WebP: only pass through images already
            // within the limits. Providers reject oversized edges (Anthropic: 8000px, 2000px with >20
            // images) with a 400 that would recur on every retry while the image stays in history.
            if (!fitsBytes) {
                return PreparedToolImage.Rejected("the WebP image is larger than ${formatBytes(MAX_IMAGE_BYTES.toLong())}")
            }
            val (w, h) = readWebpDimensions(bytes)
                ?: return PreparedToolImage.Rejected("the WebP image header could not be read")
            if (max(w, h) > MAX_EDGE_PX) {
                return PreparedToolImage.Rejected(
                    "the WebP image is ${w}x$h; only WebP up to ${MAX_EDGE_PX}px can be attached"
                )
            }
            return PreparedToolImage.Ready(ToolResultImage(bytes, WEBP, width = w, height = h))
        }
        val (width, height) = readDimensions(bytes)
            ?: return PreparedToolImage.Rejected("the data is not a readable image")
        if (width.toLong() * height > MAX_SOURCE_PIXELS) {
            return PreparedToolImage.Rejected("the image is too large to process (${width}x$height)")
        }
        if ((sniffed == PNG || sniffed == JPEG) && fitsBytes && max(width, height) <= MAX_EDGE_PX) {
            return PreparedToolImage.Ready(ToolResultImage(bytes, sniffed, width, height))
        }
        val decoded = decode(bytes, width, height)
            ?: return PreparedToolImage.Rejected("the image could not be decoded")
        // Busy images (photos, noisy full-page captures) may not fit the byte cap at MAX_EDGE_PX even as
        // JPEG; shrink further in steps before giving up.
        var edge = MAX_EDGE_PX
        var scaled = fitWithin(decoded, edge)
        var fitted = encodeWithinBudget(scaled, preferJpeg = sniffed == JPEG)
        while (fitted == null && edge > MIN_FALLBACK_EDGE_PX) {
            edge = max(MIN_FALLBACK_EDGE_PX, (edge * 0.75).roundToInt())
            scaled = fitWithin(scaled, edge)
            fitted = encodeWithinBudget(scaled, preferJpeg = true)
        }
        val (encoded, mimeType) = fitted
            ?: return PreparedToolImage.Rejected(
                "the image stays larger than ${formatBytes(MAX_IMAGE_BYTES.toLong())} after downscaling"
            )
        return PreparedToolImage.Ready(
            ToolResultImage(
                bytes = encoded,
                mimeType = mimeType,
                width = scaled.width,
                height = scaled.height,
                sourceBytes = bytes.size,
                sourceWidth = width,
                sourceHeight = height,
            )
        )
    }

    /** [prepare] for base64 text such as MCP `ImageContent.data`. */
    fun prepareBase64(base64: String): PreparedToolImage {
        if (decodedBase64Size(base64) > MAX_SOURCE_BYTES) {
            return PreparedToolImage.Rejected("the image is larger than ${formatBytes(MAX_SOURCE_BYTES.toLong())}")
        }
        val bytes = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull()
            ?: return PreparedToolImage.Rejected("the image data is not valid base64")
        return prepare(bytes)
    }

    fun sniffMimeType(bytes: ByteArray): String? = when {
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
        bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> GIF
        bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> WEBP
        else -> null
    }

    /** Size of the data base64 text decodes to, without decoding it. */
    fun decodedBase64Size(base64: String): Long {
        var chars = 0L
        var padding = 0
        for (c in base64) {
            when {
                c == '=' -> padding++
                !c.isWhitespace() -> chars++
            }
        }
        return max(0L, (chars + padding) * 3 / 4 - padding)
    }

    /** `image/png, 1280x720, 245.3 KB` — dimensions and size are omitted when unknown. */
    fun describe(mimeType: String?, byteSize: Long?, width: Int? = null, height: Int? = null): String =
        listOfNotNull(
            mimeType?.takeIf { it.isNotBlank() } ?: "unknown type",
            if (width != null && height != null) "${width}x$height" else null,
            byteSize?.takeIf { it >= 0 }?.let(::formatBytes),
        ).joinToString(", ")

    /** Placeholder text for an image that goes out as an image part of the same tool result. */
    fun attachedNote(index: Int, image: ToolResultImage): String =
        "[image $index attached to this tool result: ${image.describe()}]"

    /** Placeholder text for an image (or other binary content) that is not sent. */
    fun omittedNote(label: String, mimeType: String?, byteSize: Long?, reason: String): String =
        "[$label omitted (${describe(mimeType, byteSize)}): $reason]"

    // Possessive quantifiers: each maximal run is scanned once, so redaction stays linear even on
    // long text made of many alphanumeric runs just below the threshold.
    private val dataUriBase64 = Regex("data:([\\w.+-]++/[\\w.+-]++);base64,([A-Za-z0-9+/]++={0,2})")
    private val base64Run = Regex("[A-Za-z0-9+/]++={0,2}")

    /** Shortest bare base64 run [redactInlineBase64] replaces (data: URIs are replaced at any length). */
    const val MIN_REDACTED_BASE64_RUN = 2048

    /**
     * Last-line guard for text that is persisted or streamed (tool events, step output): replaces
     * `data:<mime>;base64,...` URIs and long bare base64 runs with a short placeholder, so a tool that
     * returns encoded binary as text cannot flood events with it.
     */
    fun redactInlineBase64(text: String): String {
        if (text.length < 64) return text
        val withoutUris = if ("base64," in text) {
            dataUriBase64.replace(text) { match ->
                "[${match.groupValues[1]} data omitted (${formatBytes(decodedBase64Size(match.groupValues[2]))})]"
            }
        } else {
            text
        }
        if (withoutUris.length < MIN_REDACTED_BASE64_RUN) return withoutUris
        return base64Run.replace(withoutUris) { match ->
            if (match.value.length < MIN_REDACTED_BASE64_RUN) {
                match.value
            } else {
                "[base64 data omitted (${formatBytes(decodedBase64Size(match.value))})]"
            }
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

    private fun <T> withReader(bytes: ByteArray, block: (ImageReader) -> T): T? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                block(reader)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /** Header-only read: never allocates the raster. */
    private fun readDimensions(bytes: ByteArray): Pair<Int, Int>? =
        withReader(bytes) { reader -> reader.getWidth(0) to reader.getHeight(0) }
            ?.takeIf { (w, h) -> w > 0 && h > 0 }

    /** Decodes the first frame, subsampling large sources so the raster stays near 2x the target. */
    private fun decode(bytes: ByteArray, width: Int, height: Int): BufferedImage? = withReader(bytes) { reader ->
        val param = reader.defaultReadParam
        val step = max(1, max(width, height) / (MAX_EDGE_PX * 2))
        if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
        reader.read(0, param)
    }

    private fun fitWithin(image: BufferedImage, maxEdge: Int): BufferedImage {
        val longest = max(image.width, image.height)
        if (longest <= maxEdge) return image
        val targetWidth = max(1, (image.width.toDouble() * maxEdge / longest).roundToInt())
        val targetHeight = max(1, (image.height.toDouble() * maxEdge / longest).roundToInt())
        // Halve first: a single bilinear step over a large ratio aliases text badly.
        var current = image
        while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
            current = scale(current, current.width / 2, current.height / 2)
        }
        return if (current.width == targetWidth && current.height == targetHeight) {
            current
        } else {
            scale(current, targetWidth, targetHeight)
        }
    }

    private fun scale(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val type = if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(width, height, type)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodeWithinBudget(image: BufferedImage, preferJpeg: Boolean): Pair<ByteArray, String>? {
        if (!preferJpeg) {
            encodePng(image)?.takeIf { it.size <= MAX_IMAGE_BYTES }?.let { return it to PNG }
        }
        for (quality in JPEG_QUALITIES) {
            encodeJpeg(image, quality)?.takeIf { it.size <= MAX_IMAGE_BYTES }?.let { return it to JPEG }
        }
        return null
    }

    private fun encodePng(image: BufferedImage): ByteArray? = runCatching {
        ByteArrayOutputStream().also { out -> check(ImageIO.write(image, "png", out)) }.toByteArray()
    }.getOrNull()

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray? = runCatching {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) {
            image
        } else {
            // JPEG has no alpha: flatten onto white.
            BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { flat ->
                val graphics = flat.createGraphics()
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, image.width, image.height)
                    graphics.drawImage(image, 0, 0, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { output ->
                writer.output = output
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(rgb, null, null), param)
            }
            out.toByteArray()
        } finally {
            writer.dispose()
        }
    }.getOrNull()
}
