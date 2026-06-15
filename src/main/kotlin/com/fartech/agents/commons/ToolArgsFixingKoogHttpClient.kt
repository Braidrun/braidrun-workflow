package com.fartech.agents.commons

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * [KoogHttpClient] decorator that repairs the Koog 1.0.0 tool-call argument
 * double-encoding bug (see [OpenAIToolArgsDoubleEncodeFix] for the bug write-up) on
 * outbound chat-completions requests.
 *
 * Sits between [ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient] and
 * the real Ktor-backed HTTP client. When the request body is a serialized JSON string
 * (which it always is on the OpenAI-compatible chat-completions path), we walk
 * `messages[*].tool_calls[*].function.arguments` and unwrap any value that is a
 * JSON string literal containing a JSON object. Idempotent.
 *
 * Non-`String` request bodies (`GET`, embeddings, etc.) pass through untouched.
 */
internal class ToolArgsFixingKoogHttpClient(
    private val delegate: KoogHttpClient,
) : KoogHttpClient {

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
    ): R {
        @Suppress("UNCHECKED_CAST")
        val fixed = (requestBody as? String)?.let { OpenAIToolArgsDoubleEncodeFix.fix(it) as T }
            ?: requestBody
        return delegate.post(path, fixed, requestBodyType, responseType, parameters, headers)
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> {
        @Suppress("UNCHECKED_CAST")
        val fixed = (requestBody as? String)?.let { OpenAIToolArgsDoubleEncodeFix.fix(it) as T }
            ?: requestBody
        return delegate.sse(
            path = path,
            requestBody = fixed,
            requestBodyType = requestBodyType,
            dataFilter = dataFilter,
            decodeStreamingResponse = decodeStreamingResponse,
            processStreamingChunk = processStreamingChunk,
            parameters = parameters,
            headers = headers,
        )
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> {
        @Suppress("UNCHECKED_CAST")
        val fixed = (requestBody as? String)?.let { OpenAIToolArgsDoubleEncodeFix.fix(it) as T }
            ?: requestBody
        return delegate.lines(path, fixed, requestBodyType, parameters, headers)
    }

    override fun close() {
        delegate.close()
    }
}
