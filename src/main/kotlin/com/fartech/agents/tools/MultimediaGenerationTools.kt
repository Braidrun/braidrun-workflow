package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.commons.LLModelGroupConfig
import com.fartech.agents.commons.getLLMGroupConfig
import com.fartech.agents.commons.resolveConfiguredApiKey
import com.fartech.ftapp2.commonsKt.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.*

// Local JSON helpers to safely traverse dynamic JSON without throwing
private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
private fun JsonPrimitive.contentOrNull(): String? = try {
    this.content
} catch (_: Throwable) {
    null
}

@Serializable
@LLMDescription("Aspect ratios for image generation")
enum class ImageGenerationAspectRatios(val ratio: String) {
    @LLMDescription("1:1 (square)")
    RATIO_1_1("1:1"),

    @LLMDescription("16:9 (widescreen)")
    RATIO_16_9("16:9"),

    @LLMDescription("9:16 (portrait)")
    RATIO_9_16("9:16"),

    @LLMDescription("3:2 (cinema)")
    RATIO_3_2("3:2"),

    @LLMDescription("2:3 (portrait)")
    RATIO_2_3("2:3"),

    @LLMDescription("4:3 (classic)")
    RATIO_4_3("4:3"),

    @LLMDescription("1:2 (portrait)")
    RATIO_1_2("1:2"),

    @LLMDescription("3:4 (portrait)")
    RATIO_3_4("3:4"),

    @LLMDescription("4:5 (portrait)")
    RATIO_4_5("4:5"),

    @LLMDescription("9:18 (portrait)")
    RATIO_9_18("9:18"),

    @LLMDescription("5:4")
    RATIO_5_4("5:4"),

    @LLMDescription("21:9 (widescreen)")
    RATIO_21_9("21:9")
}

@Serializable
@LLMDescription("Image generation model")
enum class ImageModel(val model: String) {
    @LLMDescription("Google Gemini 3.1 Flash Image Preview (Nano Banana 2) - Latest and most advanced")
    NANO_BANANA_2("google/gemini-3.1-flash-image-preview"),

    @LLMDescription("Google Gemini 3 Pro Image Preview (Nano Banana Pro)")
    NANO_BANANA_PRO("google/gemini-3-pro-image-preview"),

    @LLMDescription("Google Gemini 2.5 Flash Image")
    NANO_BANANA("google/gemini-2.5-flash-image"),

    @LLMDescription("Google Gemini 2.5 Flash Image Preview")
    NANO_BANANA_PREVIEW("google/gemini-2.5-flash-image-preview"),

    @LLMDescription("OpenAI GPT-5 Image - High quality image generation")
    GPT_5_IMAGE("openai/gpt-5-image"),

    @LLMDescription("OpenAI GPT-5 Image Mini - Cost-efficient image generation")
    GPT_5_IMAGE_MINI("openai/gpt-5-image-mini")
}

@Serializable
@LLMDescription("Audio generation model")
enum class AudioModel(val model: String) {
    @LLMDescription("OpenAI GPT Audio - High quality audio generation with natural voices")
    GPT_AUDIO("openai/gpt-audio"),

    @LLMDescription("OpenAI GPT Audio Mini - Cost-efficient audio generation")
    GPT_AUDIO_MINI("openai/gpt-audio-mini"),

    @LLMDescription("OpenAI GPT-4o Audio Preview - Preview version with experimental features")
    GPT_4O_AUDIO_PREVIEW("openai/gpt-4o-audio-preview")
}

@LLMDescription("Toolset for multimedia generation operations including image and audio")
class MultimediaGenerationTools(
    val httpAccess: HttpAccess,
    val parameters: List<ConfigurationParameter>
) : ToolSet {
    private val maxReferenceImageBytes: Long by lazy {
        parameters.parameter("multimedia_max_reference_image_bytes", (10L * 1024L * 1024L).toString())
            .toLongOrNull()
            ?.takeIf { it > 0 }
            ?: (10L * 1024L * 1024L)
    }

    private val allowedReferenceImageExtensions = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "avif"
    )

    private fun multimediaBaseUrl(): String {
        val dedicated = parameters.parameter("multimedia_base_url", "")
        if (dedicated.isNotBlank()) return dedicated.trimEnd('/')

        val dedicatedGroup = runCatching {
            parameters.parameter("multimedia_llm_config", LLModelGroupConfig(models = listOf()))
        }.getOrNull()
        val dedicatedConfig = dedicatedGroup?.models?.firstOrNull { it.provider.equals("openrouter", ignoreCase = true) }
        dedicatedConfig?.baseUrl?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }

        val group = parameters.getLLMGroupConfig()
        val modelConfig = group.models.firstOrNull { it.provider.equals("openrouter", ignoreCase = true) }
        return (modelConfig?.baseUrl?.takeIf { it.isNotBlank() } ?: "https://openrouter.ai").trimEnd('/')
    }

    private fun multimediaApiKey(): String {
        val dedicated = parameters.parameter("multimedia_api_key", "")
        if (dedicated.isNotBlank()) return dedicated

        val dedicatedKeys = runCatching {
            parameters.parameter("multimedia_llm_provider_keys", mapOf<String, String>())
        }.getOrElse { emptyMap() }
        val dedicatedOpenRouter = dedicatedKeys["openrouter"] ?: dedicatedKeys["open_router"]
        if (!dedicatedOpenRouter.isNullOrBlank()) return dedicatedOpenRouter

        val keys = parameters.parameter("llm_provider_keys", mapOf<String, String>())
        return resolveConfiguredApiKey(parameters, "openrouter", keys) ?: ""
    }

    /**
     * 解析最终使用的图像模型 ID。
     * 优先级：参数配置 > 枚举选择 > 注册表默认值。
     * 支持通过 multimedia.yaml 新增的模型 ID（不限于枚举值）。
     */
    private fun resolveImageModelId(model: ImageModel): String {
        val configured = parameters.parameter("multimedia_default_image_model", "")
        if (configured.isNotBlank() && model == ImageModel.NANO_BANANA_2) {
            // 先尝试匹配枚举
            val enumMatch = ImageModel.entries.firstOrNull {
                it.name.equals(configured, ignoreCase = true) || it.model.equals(configured, ignoreCase = true)
            }
            if (enumMatch != null) return enumMatch.model
            // 再检查是否是注册表中的模型 ID（支持 YAML 新增的模型）
            if (com.fartech.agents.commons.ModelRegistry.isKnownImageModel(configured)) return configured
            // 未知模型也允许，由 OpenRouter 兜底
            return configured
        }
        return model.model
    }

    /**
     * 解析最终使用的音频模型 ID。
     */
    private fun resolveAudioModelId(model: AudioModel): String {
        val configured = parameters.parameter("multimedia_default_audio_model", "")
        if (configured.isNotBlank() && model == AudioModel.GPT_AUDIO_MINI) {
            val enumMatch = AudioModel.entries.firstOrNull {
                it.name.equals(configured, ignoreCase = true) || it.model.equals(configured, ignoreCase = true)
            }
            if (enumMatch != null) return enumMatch.model
            if (com.fartech.agents.commons.ModelRegistry.isKnownAudioModel(configured)) return configured
            return configured
        }
        return model.model
    }

    internal fun referenceImageJson(referenceImage: String): JsonObject {
        // Reference images can be either a publicly-reachable URL or a local file
        // path. Http(s) URLs MUST pass the same SSRF guard WebTools uses so the LLM
        // can't steer the multimedia backend into fetching `http://169.254.169.254/`
        // or internal network assets. Local paths MUST stay inside the tool sandbox,
        // be actual image-looking files, and remain bounded in size so arbitrary
        // workspace files cannot be smuggled into an outbound base64 payload.
        val lower = referenceImage.lowercase()
        val urlValue: JsonPrimitive = if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val safeUrl = try {
                UrlSafety.validateAndNormalizeUrl(referenceImage)
            } catch (e: Exception) {
                throw IllegalArgumentException("Rejected reference image URL: ${e.message}")
            }
            JsonPrimitive(safeUrl)
        } else {
            val safeFile = validateLocalReferenceImage(referenceImage)
            JsonPrimitive("data:image/${safeFile.extension.lowercase()};base64,${imageToBase64(safeFile)}")
        }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("image_url"),
                "image_url" to JsonObject(mapOf("url" to urlValue))
            )
        )
    }

    private fun validateLocalReferenceImage(path: String): File {
        val safeFile = try {
            ToolPathSecurity.validateInputPath(path)
        } catch (e: SecurityException) {
            throw IllegalArgumentException("Rejected reference image path: ${e.message}")
        }

        require(safeFile.isFile) { "Rejected reference image path: not a file: $path" }
        val extension = safeFile.extension.lowercase()
        require(extension in allowedReferenceImageExtensions) {
            "Rejected reference image path: extension '.$extension' is not an allowed image type"
        }
        require(safeFile.length() in 1..maxReferenceImageBytes) {
            "Rejected reference image path: file size ${safeFile.length()} bytes exceeds multimedia_max_reference_image_bytes=$maxReferenceImageBytes"
        }
        return safeFile
    }

    @Tool
    @LLMDescription("Generates an image from a text prompt and a reference image, where reference image is optional, using OpenRouter image models. Returns a list of image URLs generated by LLM")
    suspend fun generateImage(
        @LLMDescription("Text prompt to describe the image that will be generated in details")
        prompt: String,
        @LLMDescription("Path to the image file that will be generated")
        imagePath: String,
        @LLMDescription("Image aspect ratio")
        aspectRatio: ImageGenerationAspectRatios = ImageGenerationAspectRatios.RATIO_1_1,
        @LLMDescription("Image generation model")
        model: ImageModel = ImageModel.NANO_BANANA_2,
        @LLMDescription("A list of reference image file path or http/https URL. Set this value to empty list to indicate that no reference image is provided.")
        referenceImages: List<String> = emptyList()
    ): String {
        val resolvedModelId = resolveImageModelId(model)
        val apiKey = multimediaApiKey()
        if (apiKey.isBlank()) {
            printlnColor(AnsiColor.YELLOW, "[ImageGen] Multimedia API key not configured. Skipping image generation.")
            return "Multimedia API key not configured. Skipping image generation."
        }
        val baseUrl = multimediaBaseUrl()
        val url = "$baseUrl/api/v1/chat/completions"
        // Build a Responses API payload per OpenRouter docs
        val body = JsonObject(
            mutableMapOf(
                "model" to JsonPrimitive(resolvedModelId),
                // The Responses API accepts either a simple string in `input` or full message objects
                "messages" to JsonArray(
                    listOf(
                        JsonObject(
                            mutableMapOf(
                                "role" to JsonPrimitive("user"),
                                "content" to if (referenceImages.isNotEmpty()) {
                                    JsonArray(
                                        mutableListOf(
                                            JsonObject(
                                                mapOf(
                                                    "type" to JsonPrimitive("text"),
                                                    "text" to JsonPrimitive(prompt)
                                                )
                                            )
                                        ).also {
                                            referenceImages.forEach { image -> it.add(referenceImageJson(image)) }
                                        }
                                    )
                                } else JsonPrimitive(prompt)
                            )
                        )
                    )
                ),
                "modalities" to JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("image")))
            ).also {
                // Include aspect ratio for all image models
                it["image_config"] = JsonObject(mutableMapOf("aspect_ratio" to JsonPrimitive(aspectRatio.ratio)))
            }
        )
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        // Try extracting an image URL or base64 data from multiple possible response shapes
        return extractImage0FromResponse(httpAccess.post(url, body, headers), imagePath)
    }

    @Tool
    @LLMDescription("Generates audio from a text prompt using OpenRouter audio models. Returns the path to the generated audio file")
    suspend fun generateAudio(
        @LLMDescription("Text prompt to describe what should be spoken or the content of the audio")
        prompt: String,
        @LLMDescription("Path to the audio file that will be generated (e.g., output.mp3, output.wav)")
        audioPath: String,
        @LLMDescription("Audio generation model")
        model: AudioModel = AudioModel.GPT_AUDIO_MINI,
        @LLMDescription("Voice style or tone for the audio (optional)")
        voice: String? = null
    ): String {
        val resolvedModelId = resolveAudioModelId(model)
        val apiKey = multimediaApiKey()
        if (apiKey.isBlank()) {
            printlnColor(AnsiColor.YELLOW, "[AudioGen] Multimedia API key not configured. Skipping audio generation.")
            return "Multimedia API key not configured. Skipping audio generation."
        }
        val baseUrl = multimediaBaseUrl()
        val url = "$baseUrl/api/v1/chat/completions"

        val body = JsonObject(
            mutableMapOf(
                "model" to JsonPrimitive(resolvedModelId),
                "messages" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("user"),
                                "content" to JsonPrimitive(prompt)
                            )
                        )
                    )
                ),
                "modalities" to JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("audio")))
            ).also {
                if (voice != null) {
                    it["voice"] = JsonPrimitive(voice)
                }
            }
        )

        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )

        return extractAudioFromResponse(httpAccess.post(url, body, headers), audioPath)
    }

    /**
     * Parses image results from an OpenRouter chat-completions style response and saves base64 images to disk.
     *
     * Expected response shape used by our image wrapper:
     * - choices[0].message.images[0].image_url.url → may be a data URI (data:image/...;base64,...) or a remote URL.
     *
     * Behavior:
     * 1) If url is a data URI, decode the base64 payload and write it to `path`.
     * 2) If url is not a data URI (remote URL), we currently return a failure message because this tool focuses on
     *    inline (base64) outputs for deterministic local asset creation.
     * 3) Return a short status string; large base64 blobs are never returned to reduce token usage.
     */
    private fun extractImage0FromResponse(resp: JsonObject, path: String): String {

        val choice = resp["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
        val message = choice?.get("message")?.jsonObjectOrNull()
        val image0 = message?.get("images")?.jsonArrayOrNull()?.getOrNull(0)?.jsonObjectOrNull()
        val urlString = image0?.get("image_url")?.jsonObjectOrNull()?.get("url")?.jsonPrimitiveOrNull()?.contentOrNull()

        if (urlString.isNullOrBlank()) return "Failed to extract image URL from response"

        if (urlString.startsWith("data:image/", ignoreCase = true)) {
            // Save to a temp file for convenience/logging, but return the data URI so upstream "URL" extractors work
            if (saveDataUriToFile(urlString, path)) {
                printlnColor(AnsiColor.GREEN, "[ImageGen] Saved base64 image to file: $path")
                return "Successfully generated image and save it to path: $path"
            } else {
                return "Failed to save base64 image to file: $path"
            }
        } else {
            return "Failed to extract image URL from response"
        }
    }

    /**
     * Extracts audio from OpenRouter response and saves to disk.
     *
     * Expected response shape:
     * - choices[0].message.audio.data → base64 encoded audio data
     */
    private fun extractAudioFromResponse(resp: JsonObject, path: String): String {
        val choice = resp["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
        val message = choice?.get("message")?.jsonObjectOrNull()
        val audio = message?.get("audio")?.jsonObjectOrNull()
        val audioData = audio?.get("data")?.jsonPrimitiveOrNull()?.contentOrNull()

        if (audioData.isNullOrBlank()) {
            return "Failed to extract audio data from response"
        }

        return try {
            val bytes = Base64.getDecoder().decode(audioData)
            val outFile = ToolPathSecurity.validateOutputPath(path)
            outFile.writeBytes(bytes)
            printlnColor(
                AnsiColor.GREEN,
                "[AudioGen] Audio file written: ${outFile.absolutePath} (${bytes.size} bytes)"
            )
            "Successfully generated audio and saved it to path: $path"
        } catch (e: Throwable) {
            printlnColor(AnsiColor.RED, "[AudioGen] Error saving audio to $path: ${e.message}")
            "Failed to save audio to file: $path"
        }
    }

    private fun imageToBase64(image: ByteArray): String =
        Base64.getEncoder().encodeToString(image)

    private fun imageToBase64(imageFile: File): String =
        imageToBase64(imageFile.readBytes())
}


// Convert a data URI (data:image/...;base64,...) to a temp file and return its absolute path
// This prevents returning massive base64 strings to the orchestrator LLM, reducing token usage dramatically
private fun saveDataUriToFile(uri: String, path: String): Boolean {
    return try {
        val header = uri.substringBefore(",", "")
        if (!header.startsWith("data:", ignoreCase = true)) {
            printlnColor(AnsiColor.RED, "[ImageGen] Invalid data URI: $uri")
            false
        } else {
            val mime = header.substringAfter("data:", "").substringBefore(";", "image/png")
            val ext = when {
                mime.contains("png", true) -> ".png"
                mime.contains("jpeg", true) || mime.contains("jpg", true) -> ".jpg"
                mime.contains("webp", true) -> ".webp"
                mime.contains("gif", true) -> ".gif"
                mime.contains("svg", true) -> ".svg"
                else -> ".png"
            }
            val base64 = uri.substringAfter("base64,", "")
            if (base64.isBlank()) {
                printlnColor(AnsiColor.RED, "[ImageGen] Invalid data URI: $uri")
                return false
            }
            val bytes = Base64.getDecoder().decode(base64)
            val outFile = ToolPathSecurity.validateOutputPath(path)
            outFile.writeBytes(bytes)
            printlnColor(
                AnsiColor.GREEN,
                "[ImageGen] Image file written: ${outFile.absolutePath} (${bytes.size} bytes)"
            )
            true
        }
    } catch (e: Throwable) {
        printlnColor(AnsiColor.RED, "[ImageGen] Error saving image to $path: ${e.message}")
        false
    }
}
