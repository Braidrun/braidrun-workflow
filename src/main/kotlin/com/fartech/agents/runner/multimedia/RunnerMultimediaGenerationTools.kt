package com.fartech.agents.runner.multimedia

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.tools.AudioModel
import com.fartech.agents.tools.ImageGenerationAspectRatios
import com.fartech.agents.tools.ImageModel
import com.fartech.agents.tools.ToolFileAccess
import com.fartech.agents.runner.RunnerBusinessFence
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Dedicated Runner implementation. All dependencies and frozen routes are mandatory; the original
 * library tool's parameter/env discovery, endpoints and file operations are intentionally not used.
 * Host must pass its confined file service and gated HttpAccess. No capability is enabled here.
 */
@LLMDescription("Generate image or audio files with the frozen workflow model and leased credential")
class RunnerMultimediaGenerationTools(
    http: HttpAccess,
    private val files: ToolFileAccess,
    private val plan: RunnerMultimediaPlan,
    private val lease: RunnerMultimediaLease,
    private val limits: RunnerMultimediaLimits = RunnerMultimediaLimits(),
    private val businessFence: RunnerBusinessFence? = null
) : ToolSet {
    private val transport = RunnerMultimediaTransport(http, limits)

    @Tool
    @LLMDescription("Generate an image into a workspace file. Reference images must be confined local PNG, JPEG, GIF or WebP files. Uses the frozen workflow model.")
    suspend fun generateImage(
        @LLMDescription("Image prompt") prompt: String,
        @LLMDescription("Output workspace file path") imagePath: String,
        @LLMDescription("Image aspect ratio") aspectRatio: ImageGenerationAspectRatios = ImageGenerationAspectRatios.RATIO_1_1,
        @LLMDescription("Optional assertion of the frozen model; cannot select another model") model: ImageModel? = null,
        @LLMDescription("Confined local reference image paths; URL and data URI references are not admitted") referenceImages: List<String> = emptyList()
    ): String = operation {
        val frozen = plan.imageModelId ?: throw SecurityException("Image generation is not in the frozen plan")
        if (model != null && model.model != frozen) throw SecurityException("Image model differs from the frozen plan")
        guard(frozen)
        validateText(prompt)
        validatePath(imagePath)
        if (referenceImages.size > limits.maxReferences) throw RunnerMultimediaFailure("Too many reference images")
        val references = mutableListOf<JsonElement>()
        var referenceBytes = 0L
        for (path in referenceImages) {
            guard(frozen)
            validatePath(path)
            if (path.contains("://") || path.startsWith("data:", ignoreCase = true)) {
                throw SecurityException("Remote or inline reference images are not admitted")
            }
            val bytes = files.readBytes(path, limits.maxReferenceBytes)
            if (bytes.isEmpty() || bytes.size > limits.maxReferenceBytes) throw RunnerMultimediaFailure("Reference image exceeds limit")
            referenceBytes += bytes.size
            if (referenceBytes > limits.maxTotalReferenceBytes) throw RunnerMultimediaFailure("Reference images exceed aggregate limit")
            val mime = imageMime(bytes) ?: throw RunnerMultimediaFailure("Unsupported reference image format")
            guard(frozen)
            val encoded = Base64.getEncoder().encodeToString(bytes)
            guard(frozen)
            references += buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:$mime;base64,$encoded") }
            }
        }
        val body = buildJsonObject {
            put("model", frozen)
            putJsonArray("messages") { add(buildJsonObject {
                put("role", "user")
                if (references.isEmpty()) put("content", prompt)
                else putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    references.forEach { add(it) }
                }
            }) }
            putJsonArray("modalities") { add("text"); add("image") }
            putJsonObject("image_config") { put("aspect_ratio", aspectRatio.ratio) }
        }
        val message = request(frozen, body)
        val image = (message["images"] as? JsonArray)?.firstOrNull() as? JsonObject
        val uri = ((image?.get("image_url") as? JsonObject)?.get("url") as? JsonPrimitive)?.content
            ?: throw RunnerMultimediaFailure("Multimedia response contains no inline image")
        val comma = uri.indexOf(',')
        if (comma !in 1..64) throw RunnerMultimediaFailure("Multimedia response contains no inline image")
        val mime = IMAGE_MIMES.firstOrNull { uri.substring(0, comma).equals("data:$it;base64", ignoreCase = true) }
            ?: throw RunnerMultimediaFailure("Unsupported generated image format")
        val bytes = decodeBase64(uri, comma + 1, frozen)
        if (imageMime(bytes) != mime) throw RunnerMultimediaFailure("Generated image content does not match its format")
        guard(frozen)
        files.writeBytes(imagePath, bytes)
        "Successfully generated image and saved it to path: $imagePath"
    }

    @Tool
    @LLMDescription("Generate audio into a workspace file with the frozen workflow model")
    suspend fun generateAudio(
        @LLMDescription("Speech or audio prompt") prompt: String,
        @LLMDescription("Output workspace file path") audioPath: String,
        @LLMDescription("Optional assertion of the frozen model; cannot select another model") model: AudioModel? = null,
        @LLMDescription("Optional voice name") voice: String? = null
    ): String = operation {
        val frozen = plan.audioModelId ?: throw SecurityException("Audio generation is not in the frozen plan")
        if (model != null && model.model != frozen) throw SecurityException("Audio model differs from the frozen plan")
        guard(frozen)
        validateText(prompt)
        validatePath(audioPath)
        if (voice != null && (voice.length !in 1..64 || !voice.all { it.isLetterOrDigit() || it in "_-" })) {
            throw RunnerMultimediaFailure("Invalid voice name")
        }
        val body = buildJsonObject {
            put("model", frozen)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
            putJsonArray("modalities") { add("text"); add("audio") }
            if (voice != null) put("voice", voice)
        }
        val message = request(frozen, body)
        val encoded = ((message["audio"] as? JsonObject)?.get("data") as? JsonPrimitive)?.content
            ?: throw RunnerMultimediaFailure("Multimedia response contains no inline audio")
        val bytes = decodeBase64(encoded, 0, frozen)
        guard(frozen)
        files.writeBytes(audioPath, bytes)
        "Successfully generated audio and saved it to path: $audioPath"
    }

    private suspend fun request(modelId: String, body: JsonObject): JsonObject {
        guard(modelId)
        val bytes = transport.post(body.toString(), lease.key(modelId)) { guard(modelId) }
        guard(modelId)
        val text = bytes.toString(Charsets.UTF_8)
        inspectJson(text, modelId)
        val root = Json.parseToJsonElement(text) as? JsonObject
            ?: throw RunnerMultimediaFailure("Invalid multimedia response")
        guard(modelId)
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
            ?: throw RunnerMultimediaFailure("Invalid multimedia response")
        val responseText = message["content"]
        if (responseText != null && responseText !is JsonNull && responseText.toString().length > limits.maxResponseTextChars) {
            throw RunnerMultimediaFailure("Multimedia response text exceeds limit")
        }
        return message
    }

    private suspend fun inspectJson(text: String, modelId: String) {
        var depth = 0
        var nodes = 0
        var quoted = false
        var escaped = false
        for (index in text.indices) {
            if (index % 8192 == 0) guard(modelId)
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> { quoted = true; nodes++ }
                '[', '{' -> { depth++; nodes++ }
                ']', '}' -> depth--
                ',' -> nodes++
            }
            if (depth !in 0..limits.maxJsonDepth || nodes > limits.maxJsonNodes) throw RunnerMultimediaFailure("Multimedia JSON complexity exceeds limit")
        }
        if (quoted || depth != 0) throw RunnerMultimediaFailure("Invalid multimedia JSON")
        guard(modelId)
    }

    private suspend fun decodeBase64(value: String, offset: Int, modelId: String): ByteArray {
        val length = value.length - offset
        if (length <= 0 || length % 4 != 0 || length.toLong() > ((limits.maxOutputBytes.toLong() + 2) / 3) * 4) {
            throw RunnerMultimediaFailure("Generated media exceeds limit or has invalid encoding")
        }
        var padding = 0
        for (index in offset until value.length) {
            if ((index - offset) % 8192 == 0) guard(modelId)
            val char = value[index]
            if (char == '=') {
                padding++
                if (padding > 2 || index < value.length - 2) throw RunnerMultimediaFailure("Invalid generated media encoding")
            } else if (padding > 0 || !(char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '+' || char == '/')) {
                throw RunnerMultimediaFailure("Invalid generated media encoding")
            }
        }
        val expected = length.toLong() / 4 * 3 - padding
        if (expected !in 1..limits.maxOutputBytes.toLong()) throw RunnerMultimediaFailure("Generated media exceeds limit")
        guard(modelId)
        val bytes = Base64.getDecoder().decode(value.substring(offset))
        guard(modelId)
        if (bytes.size.toLong() != expected) throw RunnerMultimediaFailure("Invalid generated media encoding")
        return bytes
    }

    private suspend fun guard(modelId: String) {
        currentCoroutineContext().ensureActive()
        lease.requireActive(modelId)
        currentCoroutineContext().ensureActive()
    }

    private fun validateText(text: String) {
        if (text.isBlank() || text.length > limits.maxPromptChars) throw RunnerMultimediaFailure("Multimedia prompt exceeds limit or is empty")
    }
    private fun validatePath(path: String) {
        if (path.isBlank() || path.length > limits.maxPathChars || path.any { it.code < 32 }) throw RunnerMultimediaFailure("Invalid multimedia file path")
    }

    private suspend fun <T> operation(block: suspend () -> T): T = try {
        if (businessFence == null) withTimeout(limits.timeoutMillis) { block() }
        else businessFence.operation { withTimeout(limits.timeoutMillis) { block() } }
    } catch (failure: Throwable) {
        // OkHttp may wrap a host authority/cancellation exception. Restore it, but never return
        // an untrusted exception message/body/cause that may contain a key or provider response.
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && seen.add(cause)) {
            if (cause is CancellationException || cause is SecurityException || cause is Error) throw cause
            cause = cause.cause
        }
        currentCoroutineContext().ensureActive()
        if (failure is RunnerMultimediaFailure) throw failure
        throw RunnerMultimediaFailure("Multimedia operation failed")
    }

    companion object {
        private val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private fun imageMime(bytes: ByteArray): String? {
            fun prefix(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 255 == values[it] }
            return when {
                prefix(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
                prefix(255, 216, 255) -> "image/jpeg"
                bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
                bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> null
            }
        }
    }
}
