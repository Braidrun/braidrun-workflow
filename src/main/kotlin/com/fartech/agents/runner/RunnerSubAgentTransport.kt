package com.fartech.agents.runner

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/** A separate instance per child. Even an SDK that retries internally cannot dispatch a second
 * HTTP request for one model call. The trusted underlying Runner client also disables OkHttp
 * connection retries and redirects and installs no Ktor retry plugin. */
internal class RunnerSubAgentWireGuard {
    private var active = false
    private var sent = false
    @Synchronized fun begin() { check(!active); active = true; sent = false }
    @Synchronized fun finish() { active = false }
    @Synchronized private fun consume() {
        check(active && !sent) { "Sub-agent model HTTP retry or out-of-call request refused" }
        sent = true // Consumption precedes dispatch; failures are never refunded.
    }
    fun factory(delegate: KoogHttpClient.Factory): KoogHttpClient.Factory = object : KoogHttpClient.Factory {
        override fun create(clientName: String, baseUrl: String, headers: Map<String, String>, queryParameters: Map<String, String>,
                            requestTimeoutMillis: Long, connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: Json): KoogHttpClient {
            val client = delegate.create(clientName, baseUrl, headers, queryParameters, requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json)
            return object : KoogHttpClient {
                override val clientName get() = client.clientName
                override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R =
                    error("Sub-agent GET/model discovery is not admitted")
                override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
                    parameters: Map<String, String>, headers: Map<String, String>): R {
                    consume()
                    return client.post(path, requestBody, requestBodyType, responseType, parameters, headers)
                }
                override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
                    decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?, parameters: Map<String, String>, headers: Map<String, String>): Flow<O> =
                    error("Sub-agent streaming is not admitted")
                override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>, parameters: Map<String, String>, headers: Map<String, String>): Flow<String> =
                    error("Sub-agent streaming is not admitted")
                override fun close() = client.close()
            }
        }
    }
}
