package com.fartech.agents.commons

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import java.util.Collections
import kotlin.reflect.KClass

/**
 * A [KoogHttpClient.Factory] for [createLLMClient] tests: records every client it creates
 * (base URL + default headers, i.e. where credentials would go) and every request body, and
 * answers `post` with a canned response. Nothing leaves the JVM.
 */
internal class CapturingHttpClientFactory(
    private val respond: (clientName: String, path: String) -> String = { _, _ -> OPENAI_CHAT_OK },
) : KoogHttpClient.Factory {

    data class Created(val clientName: String, val baseUrl: String, val headers: Map<String, String>)

    data class Request(val clientName: String, val baseUrl: String, val path: String, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    val created: MutableList<Created> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient {
        created += Created(clientName, baseUrl, headers)
        return Client(clientName, baseUrl, json)
    }

    private inner class Client(
        override val clientName: String,
        private val baseUrl: String,
        private val json: Json,
    ) : KoogHttpClient {

        private fun record(path: String, body: Any) {
            requests += Request(clientName, baseUrl, path, body as? String ?: body.toString())
        }

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("GET $path not expected")

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R {
            record(path, requestBody)
            val raw = respond(clientName, path)
            return if (responseType == String::class) {
                raw as R
            } else {
                json.decodeFromString(json.serializersModule.serializer(responseType.java), raw) as R
            }
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
            record(path, requestBody)
            return emptyFlow()
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> {
            record(path, requestBody)
            return emptyFlow()
        }

        override fun close() = Unit
    }

    companion object {
        const val OPENAI_CHAT_OK = """{"id":"x","object":"chat.completion","created":0,"model":"m","choices":[{
            "index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"
            }],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

        const val ANTHROPIC_OK = """{"id":"msg_1","type":"message","role":"assistant","model":"m",
            "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
            "usage":{"input_tokens":1,"output_tokens":1}}"""
    }
}
