package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Restores the prompt-cache counts that Koog 1.3.0 drops from streamed Anthropic responses.
 *
 * Koog's non-streaming Anthropic path puts `cache_read_input_tokens` /
 * `cache_creation_input_tokens` into `ResponseMetaInfo.metadata`
 * ([PromptCacheUsageKeys.CACHE_READ_INPUT_TOKENS] / [PromptCacheUsageKeys.CACHE_CREATION_INPUT_TOKENS]).
 * Its streaming path keeps only `input_tokens` / `output_tokens` from the `message_start` and
 * `message_delta` events, so a streamed round (every live assistant turn) would report the
 * uncached remainder as its whole prompt and lose the cache reads and writes.
 *
 * Two parts, installed together by [createLLMClient]:
 * - [capturingFactory] wraps the Koog HTTP client factory so the SSE decoder also records the
 *   usage block of each raw event on the per-call [StreamUsageCapture];
 * - this client installs that capture around each streaming call and adds the recorded counts
 *   to the `StreamFrame.End` metadata under the same keys the non-streaming path uses.
 */
internal class AnthropicStreamUsageRecoveringClient(delegate: LLMClient) : ForwardingLLMClient(delegate) {

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        val capture = StreamUsageCapture()
        emitAll(
            delegate.executeStreaming(prompt, model, tools)
                .flowOn(capture)
                .map { frame -> if (frame is StreamFrame.End) frame.copy(metaInfo = capture.mergeInto(frame.metaInfo)) else frame }
        )
    }

    companion object {
        /** Wraps [factory] so the clients it creates record streamed usage on the caller's capture. */
        fun capturingFactory(factory: KoogHttpClient.Factory): KoogHttpClient.Factory =
            UsageCapturingHttpClientFactory(factory)
    }
}

/** Per-call holder for the cache counts seen in a streamed Anthropic response. */
internal class StreamUsageCapture : AbstractCoroutineContextElement(StreamUsageCapture) {
    companion object Key : CoroutineContext.Key<StreamUsageCapture>

    @Volatile
    var cacheReadInputTokens: Int? = null
        private set

    @Volatile
    var cacheCreationInputTokens: Int? = null
        private set

    /**
     * Records the usage block of one raw SSE `data:` payload. `message_start` carries it under
     * `message.usage`, `message_delta` at the top level (cumulative); later values win.
     */
    fun observe(rawEvent: String) {
        if (!rawEvent.contains("cache_")) return
        val event = runCatching { usageEventJson.parseToJsonElement(rawEvent) as? JsonObject }.getOrNull() ?: return
        val usage = when (event.string("type")) {
            "message_start" -> (event["message"] as? JsonObject)?.get("usage") as? JsonObject
            "message_delta" -> event["usage"] as? JsonObject
            else -> null
        } ?: return
        usage.int("cache_read_input_tokens")?.let { cacheReadInputTokens = it }
        usage.int("cache_creation_input_tokens")?.let { cacheCreationInputTokens = it }
    }

    /** [metaInfo] with the recorded counts added; keys the client already reported are kept. */
    fun mergeInto(metaInfo: ResponseMetaInfo): ResponseMetaInfo {
        val additions = buildMap {
            cacheReadInputTokens?.let { put(PromptCacheUsageKeys.CACHE_READ_INPUT_TOKENS, JsonPrimitive(it)) }
            cacheCreationInputTokens?.let { put(PromptCacheUsageKeys.CACHE_CREATION_INPUT_TOKENS, JsonPrimitive(it)) }
        }.filterKeys { metaInfo.metadata?.containsKey(it) != true }
        if (additions.isEmpty()) return metaInfo
        return metaInfo.copy(metadata = JsonObject(metaInfo.metadata.orEmpty() + additions))
    }
}

private val usageEventJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private class UsageCapturingHttpClientFactory(private val delegate: KoogHttpClient.Factory) : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = UsageCapturingHttpClient(
        delegate.create(
            clientName,
            baseUrl,
            headers,
            queryParameters,
            requestTimeoutMillis,
            connectTimeoutMillis,
            socketTimeoutMillis,
            json,
        )
    )
}

/** Forwards everything; `sse` additionally shows each raw event to the caller's [StreamUsageCapture]. */
private class UsageCapturingHttpClient(private val delegate: KoogHttpClient) : KoogHttpClient {

    override val clientName: String get() = delegate.clientName

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.get(path, responseType, parameters, headers)

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = flow {
        val capture = currentCoroutineContext()[StreamUsageCapture]
        emitAll(
            delegate.sse(
                path = path,
                requestBody = requestBody,
                requestBodyType = requestBodyType,
                dataFilter = dataFilter,
                decodeStreamingResponse = { raw ->
                    capture?.observe(raw)
                    decodeStreamingResponse(raw)
                },
                processStreamingChunk = processStreamingChunk,
                parameters = parameters,
                headers = headers,
            )
        )
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = delegate.lines(path, requestBody, requestBodyType, parameters, headers)

    override fun close() = delegate.close()
}
