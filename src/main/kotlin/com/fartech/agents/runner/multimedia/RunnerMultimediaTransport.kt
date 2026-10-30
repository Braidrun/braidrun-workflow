package com.fartech.agents.runner.multimedia

import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

/** No retry, redirect, alternate endpoint, URL download or client ownership. */
internal class RunnerMultimediaTransport(private val http: HttpAccess, private val limits: RunnerMultimediaLimits) {
    suspend fun post(body: String, key: String, guard: suspend () -> Unit): ByteArray {
        guard()
        if (body.length > limits.maxRequestBytes) throw RunnerMultimediaFailure("Multimedia request exceeds limit")
        val request = body.toByteArray(Charsets.UTF_8)
        if (request.size > limits.maxRequestBytes) throw RunnerMultimediaFailure("Multimedia request exceeds limit")
        guard()
        // singleRequestClient keeps the host's gate/DNS/proxy policy but disables both redirect
        // engines and connection retries. Do not call HttpAccess.post/requestResponse here.
        return http.singleRequestClient.prepareRequest(ENDPOINT) {
            method = HttpMethod.Post
            header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            // Ktor's StreamRequestBody marks ReadChannelContent one-shot. A ByteArray/String
            // body can be replayed by OkHttp for 503 Retry-After: 0 even with retries disabled.
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.Application.Json
                override val contentLength = request.size.toLong()
                override fun readFrom(): ByteReadChannel = ByteReadChannel(request)
            })
            timeout {
                requestTimeoutMillis = limits.timeoutMillis
                connectTimeoutMillis = minOf(10_000L, limits.timeoutMillis)
                socketTimeoutMillis = minOf(30_000L, limits.timeoutMillis)
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                guard()
                if (response.status.value in 300..399) throw RunnerMultimediaFailure("Multimedia redirects are not admitted")
                if (response.status.value !in 200..299) throw RunnerMultimediaFailure("Multimedia provider request failed")
                val declared = response.headers["Content-Length"]?.toLongOrNull()
                if (declared != null && declared > limits.maxResponseBytes) throw RunnerMultimediaFailure("Multimedia response exceeds limit")
                val output = ByteArrayOutputStream(minOf(8192, limits.maxResponseBytes))
                val buffer = ByteArray(8192)
                while (true) {
                    guard()
                    val count = channel.readAvailable(buffer, 0, minOf(buffer.size, limits.maxResponseBytes - output.size() + 1))
                    if (count < 0) break
                    if (count == 0) continue
                    if (count > limits.maxResponseBytes - output.size()) throw RunnerMultimediaFailure("Multimedia response exceeds limit")
                    output.write(buffer, 0, count)
                }
                guard()
                output.toByteArray()
            } finally {
                channel.cancel(null)
            }
        }
    }

    companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}
