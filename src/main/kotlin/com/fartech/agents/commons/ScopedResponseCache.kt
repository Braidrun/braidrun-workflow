package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** `ResponseMetaInfo.metadata` key under which a stored response records the digest of its request. */
internal const val RESPONSE_CACHE_DIGEST_METADATA_KEY = "braidrun_response_cache_digest"

/**
 * [PromptCache] view that keys entries by [ResponseCacheKey.digest] instead of Koog's
 * `PromptCache.Request.asCacheKey`.
 *
 * Koog's key is the 32-bit `String.hashCode` of the prompt plus each tool's name and
 * description. It ignores the model — so after a model switch an identical prompt replays the
 * other model's cached answer — and the tools' parameter schemas, and at 32 bits two different
 * prompts in a shared Redis / file cache can collide and be served each other's answer.
 *
 * The digest is a SHA-256 over the model, the full tool descriptors and the prompt (see
 * [responseCacheDigest]). The underlying cache still stores under a 32-bit key, so every stored
 * response also records its digest, and a lookup whose stored digest differs is a miss rather
 * than someone else's answer. The request passed down keeps the prompt (cache markers removed,
 * digest as its id), so a wrapper below that inspects it — [ToolResultMediaBypassingPromptCache]
 * — still sees what it is about to store.
 */
internal class ScopedResponseCache(private val delegate: PromptCache) : PromptCache {

    override suspend fun get(request: PromptCache.Request): Message.Assistant? {
        val digest = digestFor(request)
        val stored = delegate.get(storageRequest(request, digest)) ?: return null
        val storedDigest = (stored.metaInfo.metadata?.get(RESPONSE_CACHE_DIGEST_METADATA_KEY) as? JsonPrimitive)?.content
        return stored.takeIf { storedDigest == digest }
    }

    override suspend fun put(request: PromptCache.Request, response: Message.Assistant) {
        val digest = digestFor(request)
        val metadata = JsonObject(response.metaInfo.metadata.orEmpty() + (RESPONSE_CACHE_DIGEST_METADATA_KEY to JsonPrimitive(digest)))
        delegate.put(storageRequest(request, digest), response.copy(metaInfo = response.metaInfo.copy(metadata = metadata)))
    }

    /** The per-call key set by the executor; without one, a key from what the request carries. */
    private suspend fun digestFor(request: PromptCache.Request): String =
        currentCoroutineContext()[ResponseCacheKey]?.digest
            ?: sha256Hex("prompt-only\n" + canonicalPromptJson(request.prompt) + "\n" + request.toolJsons)

    /** [request]'s prompt without cache markers, identified by [digest]; tools are in the digest. */
    private fun storageRequest(request: PromptCache.Request, digest: String): PromptCache.Request =
        PromptCache.Request.create(
            request.prompt.withoutCacheControls().copy(id = "braidrun-response-cache:$digest"),
            emptyList(),
        )
}

/** Per-call response-cache key, installed by [MeteringSafeCachedPromptExecutor]. */
internal class ResponseCacheKey(val digest: String) : AbstractCoroutineContextElement(ResponseCacheKey) {
    companion object Key : CoroutineContext.Key<ResponseCacheKey>
}

/**
 * SHA-256 over everything that determines the answer: provider and model id, every tool's full
 * descriptor (parameters included), and the prompt with timestamps and prompt-cache markers
 * removed (markers change billing, not the answer).
 */
internal fun responseCacheDigest(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): String =
    sha256Hex(
        buildString {
            append(model.provider.id).append('\n')
            append(model.id).append('\n')
            tools.forEach { tool ->
                append(tool.name).append('\u0000')
                append(tool.description).append('\u0000')
                append(tool.requiredParameters).append('\u0000')
                append(tool.optionalParameters).append('\n')
            }
            append(canonicalPromptJson(prompt))
        }
    )

private val canonicalJson = Json { encodeDefaults = true }

private fun canonicalPromptJson(prompt: Prompt): String {
    val messages = prompt.withoutCacheControls().messages.map { message ->
        when (message) {
            is Message.System -> message.copy(metaInfo = RequestMetaInfo.Empty)
            is Message.User -> message.copy(metaInfo = RequestMetaInfo.Empty)
            is Message.Assistant -> message.copy(metaInfo = ResponseMetaInfo.Empty)
        }
    }
    return canonicalJson.encodeToString(Prompt.serializer(), Prompt(messages, prompt.id, prompt.params))
}

/** Prompt-cache markers change billing, not the answer, and Koog's cache key cannot serialize them. */
private fun Prompt.withoutCacheControls(): Prompt = withMessages { messages ->
    messages.map { message ->
        when (message) {
            is Message.System -> message.copy(parts = message.parts.map { it.copy(cacheControl = null) })
            is Message.User -> message.copy(parts = message.parts.map { it.withoutCacheControl() })
            is Message.Assistant -> message.copy(parts = message.parts.map { it.withoutCacheControl() })
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <P : MessagePart> P.withoutCacheControl(): P = when (this) {
    is MessagePart.Text -> copy(cacheControl = null)
    is MessagePart.Attachment -> copy(cacheControl = null)
    is MessagePart.Reasoning -> copy(cacheControl = null)
    is MessagePart.Tool.Call -> copy(cacheControl = null)
    is MessagePart.Tool.Result -> copy(parts = parts.map { it.withoutCacheControl() }, cacheControl = null)
    else -> this
} as P

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
