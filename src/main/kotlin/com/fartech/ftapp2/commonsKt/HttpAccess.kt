package com.fartech.ftapp2.commonsKt

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import okhttp3.Credentials

private val logger = KotlinLogging.logger {}

class HttpAccess(
    httpClientConfig: (HttpClientConfig<*>) -> Unit = {},
) {

    private fun envInt(key: String, default: Int): Int =
        (System.getProperty(key) ?: System.getenv(key))?.toIntOrNull() ?: default

    private fun envLong(key: String, default: Long): Long =
        (System.getProperty(key) ?: System.getenv(key))?.toLongOrNull() ?: default

    private fun envDouble(key: String, default: Double): Double =
        (System.getProperty(key) ?: System.getenv(key))?.toDoubleOrNull() ?: default

    private fun envBoolean(key: String, default: Boolean): Boolean =
        (System.getProperty(key) ?: System.getenv(key))?.toBooleanStrictOrNull() ?: default

    val maxRetries: Int = envInt("BRAIDRUN_HTTP_MAX_RETRIES", 4)
    val initialBackoffMs: Long = envLong("BRAIDRUN_HTTP_BACKOFF_MS", 500)
    val backoffFactor: Double = envDouble("BRAIDRUN_HTTP_BACKOFF_FACTOR", 2.0)
    val totalRetryTimeMs: Long = TimeUnit.SECONDS.toMillis(envLong("BRAIDRUN_HTTP_TOTAL_TIMEOUT_S", 180))
    val logRetries: Boolean = envBoolean("BRAIDRUN_HTTP_LOG_RETRIES", false)
    val retryStatusSet: Set<Int> =
        (System.getProperty("BRAIDRUN_HTTP_RETRY_STATUSES") ?: System.getenv("BRAIDRUN_HTTP_RETRY_STATUSES")
            ?: "408,429,500,502,503,504,520,521,522,523,524,525,526")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    private val clientDelegate = lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    // Phase 9 (2026-05): switched from `followRedirects(true)` to
                    // `false` so the manual redirect interceptor below can re-validate
                    // each hop. Pre-Phase-9, an attacker who controlled `attacker.com`
                    // (a public IP) could redirect a tool fetch to
                    // `http://localhost:8080/admin` or
                    // `http://169.254.169.254/latest/meta-data/`. The initial URL
                    // passed `UrlSafety.validateAndNormalizeUrl` but the redirect
                    // crossed the public/internal boundary unchecked.
                    followRedirects(false)
                    // Manually walk the redirect chain so we can call
                    // `HttpHostSafety.assertUrlIsPublic` on every Location target.
                    // Capped at MAX_REDIRECT_HOPS to match OkHttp's historical default
                    // (10) and short-circuit redirect loops. The interceptor returns
                    // the final, post-redirect response so Ktor's HttpRedirect plugin
                    // (which sees a non-3xx) is a no-op.
                    addInterceptor(HttpAccess.SsrfRedirectInterceptor)
                    // Pin DNS resolution to the validated addresses (closes the
                    // resolve-then-reresolve rebinding TOCTOU). See SsrfValidatingDns.
                    dns(HttpAccess.SsrfValidatingDns)

                    // Warn loudly if an operator set `BRAIDRUN_SKIP_SSL_VERIFICATION=true`
                    // expecting it to disable TLS verification — the flag never actually
                    // disabled anything, and silently accepting it was misleading.
                    // The flag is kept only to detect misconfiguration; real TLS bypass
                    // would need to be added explicitly, and we refuse to do it here.
                    val requestedSkip = System.getProperty("BRAIDRUN_SKIP_SSL_VERIFICATION")
                        ?: System.getenv("BRAIDRUN_SKIP_SSL_VERIFICATION")
                    if (requestedSkip?.equals("true", ignoreCase = true) == true) {
                        logger.error {
                            "BRAIDRUN_SKIP_SSL_VERIFICATION was set but is NOT honoured. " +
                                "TLS verification remains on. If you genuinely need to bypass " +
                                "TLS for a dev integration, use a custom TrustManager at the " +
                                "injection point rather than a global flag."
                        }
                    }
                }
            }
            httpClientConfig(this)
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = true
                })
                jackson()
            }
            install(HttpTimeout) {
                // Enforce a hard upper bound so a misconfigured env var cannot park the
                // calling thread for days. The ceiling is 10 minutes — generous enough
                // for legit long polls, tight enough that nothing should realistically
                // need more.
                val maxTimeoutS = 600L
                requestTimeoutMillis =
                    TimeUnit.SECONDS.toMillis(envLong("BRAIDRUN_HTTP_REQ_TIMEOUT_S", 180).coerceIn(1L, maxTimeoutS))
                socketTimeoutMillis =
                    TimeUnit.SECONDS.toMillis(envLong("BRAIDRUN_HTTP_SOCK_TIMEOUT_S", 180).coerceIn(1L, maxTimeoutS))
                connectTimeoutMillis =
                    TimeUnit.SECONDS.toMillis(envLong("BRAIDRUN_HTTP_CONN_TIMEOUT_S", 180).coerceIn(1L, maxTimeoutS))
            }
        }
    }
    val client: HttpClient
        get() = clientDelegate.value

    fun shouldRetry(status: HttpStatusCode?): Boolean = status?.value in retryStatusSet

    fun shouldRetryOn(exception: Throwable): Boolean = when (exception) {
        is HttpRequestTimeoutException -> true
        is java.net.SocketTimeoutException -> true
        is java.io.EOFException -> true
        is java.net.ConnectException -> true
        // SSRF rejections from SsrfValidatingDns are wrapped as UnknownHostException
        // (Dns contract) with a SecurityException cause — deterministic, never retry.
        is java.net.UnknownHostException -> exception.cause !is SecurityException
        is javax.net.ssl.SSLHandshakeException -> true
        is javax.net.ssl.SSLException -> true
        is java.net.SocketException -> true
        else -> false
    }

    fun parseRetryAfterSeconds(resp: HttpResponse): Long? = try {
        val raw = resp.headers[HttpHeaders.RetryAfter] ?: return null
        raw.trim().toLongOrNull()?.let { return it }
        val date = java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        val delta = java.time.Duration.between(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC), date)
        val secs = delta.seconds
        if (secs > 0) secs else null
    } catch (_: Throwable) {
        null
    }

    fun jitter(ms: Long): Long {
        val jitter = (0..250).random()
        val total = ms + jitter
        return if (total < 0) 0 else total
    }

    @PublishedApi
    internal fun logRetry(message: () -> String) {
        if (logRetries) {
            // Run the composed message through the broader redactor so a caller that
            // interpolates a URL with inline secrets / Authorization header / AWS key
            // blob can't leak it through retry logs.
            logger.info { redactForLog(message()) }
        }
    }

    suspend fun delayBackoff(attempt: Int, overrideSeconds: Long? = null) {
        // Clamp server-supplied Retry-After: it is attacker-controlled for
        // LLM-directed fetches (WebTools), and an uncapped `Retry-After: 999999999`
        // would park the calling coroutine for years — the totalRetryTimeMs budget
        // is only re-checked AFTER the delay completes.
        val base = overrideSeconds?.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)?.let { it * 1000 }
            ?: (initialBackoffMs * backoffFactor.pow((attempt - 1).toDouble())).toLong()
        val delayMs = jitter(base)
        logRetry { "[RETRY] attempt=$attempt delayMs=$delayMs" }
        kotlinx.coroutines.delay(delayMs)
    }

    suspend inline fun downloadFile(
        url: String,
        savePath: String
    ) {
        val fileResponse: HttpResponse = requestResponse<Unit>(HttpMethod.Get, url)
        // Fail loudly: silently returning on a 404/500 left callers with no way to
        // distinguish success from failure short of re-statting the target file.
        if (!fileResponse.status.isSuccess()) {
            throw IllegalStateException(
                "downloadFile failed with HTTP ${fileResponse.status.value} for ${redactSensitiveUrlForLogs(url)}"
            )
        }
        val fileContent: ByteArray = fileResponse.body()
        val outputFile = File(savePath)
        outputFile.writeBytes(fileContent)
    }

    suspend inline fun <reified T> get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): T = request<Void, T>(
        HttpMethod.Get,
        url,
        headers = headers
    )

    suspend inline fun <reified T, reified R> post(
        url: String,
        content: T,
        headers: Map<String, String> = emptyMap()
    ): R = request<T, R>(
        HttpMethod.Post,
        url,
        content = content,
        headers = headers
    )

    suspend inline fun <reified T, reified R> request(
        method: HttpMethod,
        url: String,
        content: T? = null,
        headers: Map<String, String> = emptyMap()
    ): R = requestResponse(method, url, content, headers).body()

    suspend inline fun <reified T> requestResponse(
        method: HttpMethod,
        url: String,
        content: T? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val start = System.currentTimeMillis()
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            if (System.currentTimeMillis() - start > totalRetryTimeMs) {
                throw lastError ?: IllegalStateException(
                    "Total retry time exceeded ${totalRetryTimeMs}ms for ${redactSensitiveUrlForLogs(url)}"
                )
            }
            try {
                val resp = client.request {
                    this.method = method
                    headers.forEach { (name, value) -> header(name, value) }
                    url(url)
                    when (method) {
                        HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch -> setBody(content)
                        // DELETE bodies are legal (some APIs require them) but only
                        // attach one when the caller actually provided content.
                        HttpMethod.Delete -> if (content != null) setBody(content)
                        else -> Unit
                    }
                }
                if (!shouldRetry(resp.status)) return resp
                val retryAfter = parseRetryAfterSeconds(resp)
                try {
                    resp.bodyAsText()
                } catch (_: Throwable) {
                }
                attempt++
                if (attempt > maxRetries) return resp
                logRetry {
                    "[RETRY] status=${resp.status.value} url=${redactSensitiveUrlForLogs(url)} " +
                        "retryAfterS=${retryAfter ?: -1}"
                }
                delayBackoff(attempt, retryAfter)
            } catch (e: Throwable) {
                lastError = e
                if (!shouldRetryOn(e) || attempt >= maxRetries) throw e
                attempt++
                logRetry {
                    "[RETRY] exception=${e::class.simpleName} url=${redactSensitiveUrlForLogs(url)}"
                }
                delayBackoff(attempt)
            }
        }
        throw lastError ?: IllegalStateException("HTTP request failed with unknown error")
    }

    fun close() {
        if (clientDelegate.isInitialized()) {
            clientDelegate.value.close()
        }
    }

    companion object {
        /** Hop ceiling for the SSRF-validating redirect interceptor. Mirrors OkHttp's historical default. */
        internal const val MAX_REDIRECT_HOPS = 10

        /**
         * Upper bound honored for a server-supplied `Retry-After`. Anything larger
         * (hostile or misconfigured) falls back to this cap; the overall retry loop
         * is still bounded by `totalRetryTimeMs`.
         */
        const val MAX_RETRY_AFTER_SECONDS: Long = 60L

        /**
         * Hostnames exempted from [SsrfValidatingDns] because they are operator-configured
         * proxy endpoints (often internal DNS names like `proxy.corp.local`). When an HTTP
         * proxy is in play, OkHttp resolves only the PROXY host locally — target hosts are
         * resolved by the proxy — so validating the proxy hostname against the SSRF rules
         * broke legitimate internal proxies without protecting any target. Target-level
         * SSRF validation still happens in the per-tool UrlSafety gate and the redirect
         * interceptor.
         */
        private val trustedProxyHosts: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Register an operator-configured proxy hostname as exempt from DNS SSRF pinning. */
        fun registerTrustedProxyHost(host: String) {
            if (host.isNotBlank()) trustedProxyHosts.add(host.lowercase())
        }

        /**
         * SSRF-pinning DNS resolver. Resolves via the system resolver, validates the EXACT
         * returned addresses, then hands those same addresses to OkHttp for the connection.
         * Closes the DNS-rebinding TOCTOU: without this, the per-tool `assertHostIsPublic`
         * check and OkHttp's own connect-time resolution are two independent lookups, so an
         * attacker-controlled domain with TTL 0 can return a public IP to the check and an
         * internal IP to the connect. Honors `WEB_TOOLS_ALLOW_PRIVATE_URLS` via HttpHostSafety.
         *
         * Rejections are surfaced as [java.net.UnknownHostException] (the `okhttp3.Dns`
         * contract) with the original SecurityException as cause; [shouldRetryOn]
         * special-cases that cause so SSRF rejections are not pointlessly retried.
         */
        internal val SsrfValidatingDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
                if (hostname.lowercase() in trustedProxyHosts) return addresses
                try {
                    HttpHostSafety.assertResolvedAddressesPublic(hostname, addresses)
                } catch (e: SecurityException) {
                    throw java.net.UnknownHostException(
                        "SSRF guard rejected resolved addresses for '$hostname': ${e.message}"
                    ).initCause(e) as java.net.UnknownHostException
                }
                return addresses
            }
        }

        /**
         * OkHttp interceptor that walks 3xx redirects manually and calls
         * [HttpHostSafety.assertUrlIsPublic] on every Location target. Designed
         * to drop in alongside `followRedirects(false)` so Ktor's HttpRedirect
         * plugin (which only acts on 3xx) sees the final post-redirect response.
         *
         * - Methods on 301/302/303 with a body get dropped to GET (matches
         *   OkHttp / browser behaviour); 307/308 preserve method + body.
         * - SSRF-rejected redirects throw `java.io.IOException` (which is what
         *   OkHttp's interceptor contract permits) — Ktor surfaces it as a
         *   `ResponseException` / wrapping `Throwable` to the caller.
         * - Loops past [MAX_REDIRECT_HOPS] are rejected with a clear message.
         * - Opt-out: setting `WEB_TOOLS_ALLOW_PRIVATE_URLS=true` disables the
         *   host-class check inside [HttpHostSafety] (scheme check still runs).
         */
        internal val SsrfRedirectInterceptor = okhttp3.Interceptor { chain ->
            var currentRequest = chain.request()
            var response = chain.proceed(currentRequest)
            var hops = 0
            while (response.isRedirect) {
                if (hops >= MAX_REDIRECT_HOPS) {
                    val finalUrl = response.request.url.toString()
                    response.close()
                    throw java.io.IOException(
                        "Too many redirects (>$MAX_REDIRECT_HOPS) starting from " +
                            redactSensitiveUrlForLogs(finalUrl)
                    )
                }
                val location = response.header("Location")
                if (location.isNullOrBlank()) break
                val newUrl = response.request.url.resolve(location)
                if (newUrl == null) {
                    response.close()
                    throw java.io.IOException(
                        "Could not resolve redirect Location '$location' against " +
                            redactSensitiveUrlForLogs(response.request.url.toString())
                    )
                }
                try {
                    HttpHostSafety.assertUrlIsPublic(newUrl.toString())
                } catch (e: SecurityException) {
                    response.close()
                    // Wrap as IOException so OkHttp / Ktor propagate as a network failure
                    // rather than letting the SecurityException escape the interceptor
                    // contract. Caller can still pattern-match on the cause.
                    throw java.io.IOException(
                        "SSRF guard rejected redirect to ${redactSensitiveUrlForLogs(newUrl.toString())}: ${e.message}",
                        e
                    )
                } catch (e: IllegalArgumentException) {
                    response.close()
                    throw java.io.IOException(
                        "SSRF guard rejected redirect to ${redactSensitiveUrlForLogs(newUrl.toString())}: ${e.message}",
                        e
                    )
                }
                val status = response.code
                // RFC 7231 §6.4: 301/302/303 SHOULD/MAY change the method to GET when
                // the original was a POST. Mirror OkHttp's `RetryAndFollowUpInterceptor`
                // behaviour so applications behind us see consistent semantics.
                val newRequestBuilder = currentRequest.newBuilder().url(newUrl)
                if (status == 301 || status == 302 || status == 303) {
                    if (currentRequest.method != "GET" && currentRequest.method != "HEAD") {
                        newRequestBuilder.method("GET", null)
                        newRequestBuilder.removeHeader("Transfer-Encoding")
                        newRequestBuilder.removeHeader("Content-Length")
                        newRequestBuilder.removeHeader("Content-Type")
                    }
                }
                response.close()
                currentRequest = newRequestBuilder.build()
                response = chain.proceed(currentRequest)
                hops++
            }
            response
        }
    }
}

suspend fun <T> withHttpAccess(
    httpClientConfig: (HttpClientConfig<*>) -> Unit = {},
    block: suspend HttpAccess.() -> T
): T {
    val http = HttpAccess(httpClientConfig)
    return try {
        http.block()
    } finally {
        http.close()
    }
}

inline fun parseProxyConfig(
    proxyConfigString: String,
    crossinline block: (ProxyConfig, protocol: String, String?, String?) -> Unit
) {
    val url = URLBuilder().takeFrom(proxyConfigString).build()
    when (url.protocol.name) {
        "http", "https" -> block(ProxyBuilder.http(url), url.protocol.name, url.user, url.password)
        "socks5", "socks4", "socks" -> block(
            ProxyBuilder.socks(url.host, url.port),
            url.protocol.name,
            url.user,
            url.password
        )

        else -> throw UnsupportedOperationException("Unknown proxy method: ${url.protocol}")
    }
}

private fun proxyConfig(
    proxyConfigString: String?,
    engineConfig: OkHttpConfig,
    config: HttpClientConfig<*>
) {
    clearSocksProxyAuthentication()
    proxyConfigString?.let {
        // Operator-configured proxies are commonly internal DNS names; exempt the
        // proxy host itself from SsrfValidatingDns (see trustedProxyHosts docs).
        runCatching { URLBuilder().takeFrom(it).build().host }
            .getOrNull()
            ?.let(HttpAccess::registerTrustedProxyHost)
        parseProxyConfig(it) { proxyConfig, protocol, user, password ->
            engineConfig.proxy = proxyConfig
            when (protocol) {
                "http", "https" -> configureHttpProxyAuthentication(engineConfig, config, user, password)
                "socks5", "socks4", "socks" -> configureSocksProxyAuthentication(user, password)
            }
        }
    }
}

fun defaultHttpConfig(
    proxyConfigString: String?
): (HttpClientConfig<*>) -> Unit =
    { config ->
        config.engine { proxyConfig(proxyConfigString, this as OkHttpConfig, config) }
    }

private const val SOCKS_PROXY_USERNAME_PROPERTY = "java.net.socks.username"
private const val SOCKS_PROXY_PASSWORD_PROPERTY = "java.net.socks.password"

private fun configureHttpProxyAuthentication(
    engineConfig: OkHttpConfig,
    config: HttpClientConfig<*>,
    user: String?,
    password: String?
) {
    if (user.isNullOrBlank()) {
        return
    }
    val credentials = Credentials.basic(user, password.orEmpty())
    config.defaultRequest {
        header(HttpHeaders.ProxyAuthorization, credentials)
    }
    engineConfig.config {
        proxyAuthenticator { _, response ->
            if (response.request.header(HttpHeaders.ProxyAuthorization) != null) {
                null
            } else {
                response.request.newBuilder()
                    .header(HttpHeaders.ProxyAuthorization, credentials)
                    .build()
            }
        }
    }
}

private fun configureSocksProxyAuthentication(user: String?, password: String?) {
    if (user.isNullOrBlank()) {
        clearSocksProxyAuthentication()
        return
    }
    System.setProperty(SOCKS_PROXY_USERNAME_PROPERTY, user)
    System.setProperty(SOCKS_PROXY_PASSWORD_PROPERTY, password.orEmpty())
}

private fun clearSocksProxyAuthentication() {
    System.clearProperty(SOCKS_PROXY_USERNAME_PROPERTY)
    System.clearProperty(SOCKS_PROXY_PASSWORD_PROPERTY)
}
