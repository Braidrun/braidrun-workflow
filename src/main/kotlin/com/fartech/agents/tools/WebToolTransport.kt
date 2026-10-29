package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset

/**
 * Uses only the caller's HttpAccess. Runner supplies its host-gated, public-only client and disables
 * automatic redirects, so every Location is a fresh gated request and parsing uses the final URL.
 * Library callers keep the same injected HttpAccess and its transport/proxy policy.
 *
 * prepareRequest.execute deliberately avoids HttpAccess.requestResponse: that helper buffers bodies
 * and retries requests, including writes. Web tools must bound bytes before buffering and never replay
 * an ambiguous POST/PUT/PATCH/DELETE. This adapter owns neither the client nor its lifetime.
 */
internal class WebToolTransport(private val http: HttpAccess) {
    suspend fun request(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        maxBytes: Int = MAX_RESPONSE_BYTES,
        truncate: Boolean = false,
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ): WebToolResponse = withTimeout(timeoutMillis.coerceIn(1, DOWNLOAD_TIMEOUT_MILLIS)) {
        require(maxBytes in 1..MAX_DOWNLOAD_BYTES) { "Invalid response size limit" }
        require(url.length <= MAX_URL_CHARS) { "URL is too long" }
        require(headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.length } <= 32_768) { "Headers are too large" }
        require(body == null || (body.length <= MAX_RESPONSE_BYTES && body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)) { "Request body is too large" }
        var currentUrl = normalize(url)
        var currentMethod = method
        var currentBody = body
        var currentHeaders = headers.filterKeys { it.lowercase() !in HOP_BY_HOP_HEADERS }
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val hop = http.singleRequestClient.prepareRequest(currentUrl) {
                this.method = io.ktor.http.HttpMethod.parse(currentMethod.name)
                currentHeaders.forEach { (name, value) -> header(name, value) }
                if (currentBody != null) setBody(currentBody!!)
                timeout {
                    requestTimeoutMillis = timeoutMillis.coerceIn(1, DOWNLOAD_TIMEOUT_MILLIS)
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 10_000
                }
            }.execute { response ->
                currentCoroutineContext().ensureActive()
                val channel = response.bodyAsChannel()
                try {
                    val status = response.status.value
                    if (status in REDIRECT_STATUSES) {
                        val location = response.headers["Location"]
                            ?.takeIf { it.isNotBlank() && it.length <= MAX_URL_CHARS }
                            ?: throw WebToolRequestFailure("Redirect has no valid Location")
                        Hop.Redirect(location, status)
                    } else {
                        if (status !in 200..299) throw WebToolHttpFailure(status)
                        val expectedBytes = response.headers["Content-Length"]?.toLongOrNull()
                        if (!truncate && expectedBytes != null && expectedBytes > maxBytes) throw WebToolSizeFailure()
                        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
                        val buffer = ByteArray(8192)
                        var truncated = false
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            // One extra byte distinguishes an exact boundary from an oversized body.
                            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
                            if (count < 0) break
                            if (count == 0) continue
                            val allowed = minOf(count, maxBytes - output.size())
                            output.write(buffer, 0, allowed)
                            if (count > allowed) {
                                if (!truncate) throw WebToolSizeFailure()
                                truncated = true
                                break
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        val charset = response.contentType()?.parameter("charset")
                        Hop.Complete(WebToolResponse(currentUrl, output.toByteArray(), charset, truncated))
                    }
                } finally {
                    // Closes oversized, error and redirect bodies too; no drain of attacker-controlled data.
                    channel.cancel(null)
                }
            }
            when (hop) {
                is Hop.Complete -> return@withTimeout hop.response
                is Hop.Redirect -> {
                    if (redirects++ >= MAX_REDIRECT_HOPS) throw WebToolRequestFailure("Too many redirects")
                    val next = normalize(URI(currentUrl).resolve(hop.location).toString())
                    val crossOrigin = origin(currentUrl) != origin(next)
                    if (crossOrigin) currentHeaders = currentHeaders.filterKeys { it.lowercase() in CROSS_ORIGIN_HEADERS }
                    if (hop.status in setOf(301, 302, 303) && currentMethod != HttpMethod.GET) {
                        currentMethod = HttpMethod.GET
                        currentBody = null
                        currentHeaders = currentHeaders.filterKeys { !it.equals("Content-Type", ignoreCase = true) }
                    } else if (crossOrigin && currentBody != null) {
                        throw WebToolRequestFailure("Cross-origin redirect cannot forward a request body")
                    }
                    currentUrl = next
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    private fun normalize(url: String): String {
        require(url.length <= MAX_URL_CHARS) { "URL is too long" }
        val normalized = UrlSafety.validateAndNormalizeUrl(url)
        require(URI(normalized).rawUserInfo == null) { "URL user information is not supported" }
        return normalized
    }

    private fun origin(url: String): Triple<String, String, Int> = URI(url).let {
        Triple(it.scheme.lowercase(), it.host.lowercase(), if (it.port >= 0) it.port else if (it.scheme.equals("https", true)) 443 else 80)
    }

    private sealed interface Hop {
        data class Redirect(val location: String, val status: Int) : Hop
        data class Complete(val response: WebToolResponse) : Hop
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L
        const val MAX_URL_CHARS = 8192
        private const val MAX_REDIRECT_HOPS = 10
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val HOP_BY_HOP_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-connection", "proxy-authorization", "upgrade", "te", "trailer")
        private val CROSS_ORIGIN_HEADERS = setOf("accept", "accept-language", "user-agent", "content-type")
    }
}

internal data class WebToolResponse(val url: String, val bytes: ByteArray, val charsetName: String?, val truncated: Boolean) {
    fun text(): String = String(bytes, charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8)
}

internal class WebToolSizeFailure : IOException("Response exceeds the allowed byte limit")
internal class WebToolHttpFailure(val status: Int) : IOException("HTTP $status")
internal class WebToolRequestFailure(message: String) : IOException(message)
