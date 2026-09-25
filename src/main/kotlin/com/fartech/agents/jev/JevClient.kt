package com.fartech.agents.jev

import com.fartech.agents.commons.resolveVersionedEndpointPath
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import java.io.IOException
import java.net.UnknownHostException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

// ============================================================================
// Constants
// ============================================================================

/** Canonical provider / credential id for TypeSafe (Jev). Aliases: [TYPESAFE_PROVIDER_ALIASES]. */
const val TYPESAFE_PROVIDER_ID = "typesafe"

/** Default API origin; the client appends `v1/systemone` (or just `systemone` for a base ending in `/v1`). */
const val TYPESAFE_DEFAULT_BASE_URL = "https://api.typesafe.ai"

/** Default model alias used when neither the step, the host credential, parameters nor env pick one. */
const val JEV_DEFAULT_MODEL = "jev-latest"

/**
 * Provider display name used in model-info event details (`model=<id>, provider=TypeSafe`).
 * Host metering keys on this literal to price Jev tokens separately — keep it exact.
 */
const val TYPESAFE_PROVIDER_DISPLAY_NAME = "TypeSafe"

// ============================================================================
// Settings / errors / client contract
// ============================================================================

/**
 * Transport settings for one Jev client. [baseUrl] is operator-controlled only (see
 * [resolveJevCall]); it is never taken from workflow or runtime parameters.
 *
 * [maxRetries] counts retries AFTER the first attempt (3 ⇒ up to 4 HTTP calls).
 */
data class JevClientSettings(
    val baseUrl: String = TYPESAFE_DEFAULT_BASE_URL,
    val requestTimeoutMillis: Long = 30_000,
    val maxRetries: Int = 3,
    val initialBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 8_000,
) {
    init {
        require(baseUrl.isNotBlank()) { "JevClientSettings.baseUrl must not be blank" }
        require(requestTimeoutMillis > 0) { "JevClientSettings.requestTimeoutMillis must be > 0" }
        require(maxRetries in 0..10) { "JevClientSettings.maxRetries must be in 0..10" }
        require(initialBackoffMillis >= 0) { "JevClientSettings.initialBackoffMillis must be >= 0" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "JevClientSettings.maxBackoffMillis must be >= initialBackoffMillis"
        }
    }
}

/**
 * A failed Jev call. The message never contains the API key.
 *
 * [status] is the HTTP status when a response was received (null for network errors
 * and missing credentials); [requestId] is the `x-typesafe-request-id` /
 * `x-request-id` response header when present (also appended to the message).
 */
class JevApiException(
    val kind: Kind,
    val status: Int?,
    message: String,
    val requestId: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Kind {
        /** 401 / 403 — bad, revoked or under-privileged key. */
        UNAUTHORIZED,
        /** 400 / 404 / 422 (and other non-retryable 4xx) — the request itself is invalid. */
        INVALID_REQUEST,
        /** 429 after retries. */
        RATE_LIMITED,
        /** 529 after retries. */
        OVERLOADED,
        /** 5xx after retries (500/502/503/504 are retried; other 5xx are not). */
        SERVER_ERROR,
        /** I/O failure or timeout without an HTTP response (after retries). */
        NETWORK,
        /** 2xx whose body is not a valid answer set for the request. */
        INVALID_RESPONSE,
        /** No API key could be resolved (see [resolveJevCall]). */
        MISSING_CREDENTIALS,
    }

    /**
     * True for errors a retry or a fallback cannot fix (missing key, 401/403,
     * 400/404/422): callers must fail the step instead of silently defaulting.
     */
    val isConfigurationError: Boolean
        get() = kind == Kind.UNAUTHORIZED || kind == Kind.INVALID_REQUEST || kind == Kind.MISSING_CREDENTIALS

    /** Inverse of [isConfigurationError]: rate limit / overload / server / network / invalid response. */
    val isTransient: Boolean
        get() = !isConfigurationError
}

/** Asks Jev typed questions. Implementations validate answers against the request. */
interface JevClient {
    /** One `POST /v1/systemone` round trip (with retries). Throws [JevApiException] on failure. */
    suspend fun systemOne(request: JevRequest): JevResponse
}

/**
 * Creates [JevClient]s. The executor always passes its `HttpAccess.client` so Jev
 * traffic goes through the per-workflow egress proxy and SSRF DNS guard; tests pass a
 * MockEngine client (with `install(HttpTimeout)`) or replace the factory with a fake.
 */
fun interface JevClientFactory {
    fun create(apiKey: String, settings: JevClientSettings, httpClient: HttpClient): JevClient

    companion object {
        /** Wraps the PASSED [HttpClient] in [HttpJevClient]; never builds its own client. */
        val Default: JevClientFactory = JevClientFactory { apiKey, settings, httpClient ->
            HttpJevClient(apiKey, settings, httpClient)
        }
    }
}

// ============================================================================
// HTTP implementation
// ============================================================================

/** Full `systemone` URL for [baseUrl], without doubling a trailing `/v1` segment. */
internal fun jevSystemOneUrl(baseUrl: String): String =
    baseUrl.trim().trimEnd('/') + "/" + resolveVersionedEndpointPath(baseUrl.trim(), "v1/systemone")

/** Upper bound honored for a server-supplied `retry-after`. */
internal const val JEV_MAX_RETRY_AFTER_MILLIS = 20_000L

/** Max characters of a server error detail copied into an exception message. */
internal const val JEV_ERROR_DETAIL_MAX_CHARS = 500

private val JEV_RETRYABLE_STATUSES = setOf(429, 529, 500, 502, 503, 504)

private val lenientDetailJson = Json { isLenient = true }

/**
 * Ktor implementation of [JevClient] over a caller-supplied [HttpClient].
 *
 * - Serializes with [JevJson] into a [TextContent] body and decodes `bodyAsText()`
 *   explicitly, independent of the client's content negotiation.
 * - Per-request timeout via Ktor `timeout {}` (falls back to a coroutine timeout
 *   when the client has no `HttpTimeout` plugin).
 * - Own retry loop: retries 429 / 529 / 500 / 502 / 503 / 504 and I/O or timeout
 *   failures with exponential backoff + jitter, honoring `retry-after` (capped at
 *   20 s); never retries 400 / 401 / 403 / 404 / 422. Cancellation is rethrown untouched.
 */
class HttpJevClient internal constructor(
    private val apiKey: String,
    private val settings: JevClientSettings,
    private val httpClient: HttpClient,
    private val sleep: suspend (Long) -> Unit,
    private val random: Random,
) : JevClient {

    constructor(apiKey: String, settings: JevClientSettings, httpClient: HttpClient) :
        this(apiKey, settings, httpClient, { delay(it) }, Random.Default)

    init {
        if (apiKey.isBlank()) {
            throw JevApiException(JevApiException.Kind.MISSING_CREDENTIALS, null, JEV_MISSING_CREDENTIALS_MESSAGE)
        }
    }

    private val endpointUrl: String = jevSystemOneUrl(settings.baseUrl)

    private sealed interface AttemptResult {
        class Success(val response: JevResponse) : AttemptResult
        class Retryable(
            val kind: JevApiException.Kind,
            val status: Int?,
            val message: String,
            val requestId: String?,
            val retryAfterMillis: Long?,
            val cause: Throwable? = null,
        ) : AttemptResult
    }

    override suspend fun systemOne(request: JevRequest): JevResponse {
        val body = JevJson.encodeToString(JevRequest.serializer(), request)
        val maxAttempts = settings.maxRetries + 1
        var last: AttemptResult.Retryable? = null
        for (attempt in 1..maxAttempts) {
            when (val result = executeAttempt(body, request)) {
                is AttemptResult.Success -> return result.response
                is AttemptResult.Retryable -> {
                    last = result
                    if (attempt == maxAttempts) break
                    val waitMillis = result.retryAfterMillis ?: backoffMillis(attempt)
                    logger.info {
                        "TypeSafe Jev call failed (kind=${result.kind}, status=${result.status ?: "-"}, " +
                            "attempt $attempt/$maxAttempts); retrying in ${waitMillis}ms"
                    }
                    sleep(waitMillis)
                }
            }
        }
        val failure = checkNotNull(last)
        val attemptsText = if (maxAttempts > 1) " after $maxAttempts attempts" else ""
        throw JevApiException(
            kind = failure.kind,
            status = failure.status,
            message = redact(failure.message + attemptsText + requestIdSuffix(failure.requestId)),
            requestId = failure.requestId,
            cause = failure.cause,
        )
    }

    private suspend fun executeAttempt(body: String, request: JevRequest): AttemptResult {
        val response: HttpResponse = try {
            send(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            // Only reachable when the host client sets `expectSuccess = true`.
            e.response
        } catch (e: Exception) {
            return networkFailure(e)
        }

        val status = response.status.value
        val requestId = requestIdOf(response)
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (status in 200..299) return networkFailure(e)
            ""
        }

        if (status in 200..299) {
            return AttemptResult.Success(decode(text, request, status, requestId))
        }
        return statusFailure(status, text, requestId, response)
    }

    private suspend fun send(body: String): HttpResponse {
        val hasTimeoutPlugin = httpClient.pluginOrNull(HttpTimeout) != null
        val call: suspend () -> HttpResponse = {
            httpClient.post(endpointUrl) {
                if (hasTimeoutPlugin) {
                    timeout { requestTimeoutMillis = settings.requestTimeoutMillis }
                }
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                accept(ContentType.Application.Json)
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        if (hasTimeoutPlugin) return call()
        return withTimeoutOrNull(settings.requestTimeoutMillis) { call() }
            ?: throw HttpRequestTimeoutException(endpointUrl, settings.requestTimeoutMillis)
    }

    private fun decode(text: String, request: JevRequest, status: Int, requestId: String?): JevResponse {
        val decoded = try {
            JevJson.decodeFromString(JevResponse.serializer(), text)
        } catch (e: SerializationException) {
            throw invalidResponse(status, requestId, e)
        } catch (e: IllegalArgumentException) {
            throw invalidResponse(status, requestId, e)
        }
        decoded.validateAgainst(request, requestId)
        return decoded
    }

    private fun invalidResponse(status: Int, requestId: String?, cause: Exception): JevApiException {
        val reason = cause.message?.let { collapseWhitespace(it).take(300) } ?: cause::class.simpleName
        return JevApiException(
            kind = JevApiException.Kind.INVALID_RESPONSE,
            status = status,
            message = redact("TypeSafe API returned an invalid response (HTTP $status): $reason" + requestIdSuffix(requestId)),
            requestId = requestId,
            cause = cause,
        )
    }

    private fun statusFailure(status: Int, body: String, requestId: String?, response: HttpResponse): AttemptResult {
        val detail = extractJevErrorDetail(body)?.let { redact(it) }
        val detailSuffix = detail?.let { ": $it" } ?: ""
        val (kind, base) = when {
            status == 401 || status == 403 ->
                JevApiException.Kind.UNAUTHORIZED to "TypeSafe API rejected the API key (HTTP $status)"
            status == 429 -> JevApiException.Kind.RATE_LIMITED to "TypeSafe API rate limit exceeded (HTTP 429)"
            status == 529 -> JevApiException.Kind.OVERLOADED to "TypeSafe API is overloaded (HTTP 529)"
            status >= 500 -> JevApiException.Kind.SERVER_ERROR to "TypeSafe API server error (HTTP $status)"
            status in 400..499 ->
                JevApiException.Kind.INVALID_REQUEST to "TypeSafe API rejected the request (HTTP $status)"
            else -> JevApiException.Kind.INVALID_RESPONSE to "TypeSafe API returned unexpected HTTP status $status"
        }
        if (status in JEV_RETRYABLE_STATUSES) {
            return AttemptResult.Retryable(
                kind = kind,
                status = status,
                message = base + detailSuffix,
                requestId = requestId,
                retryAfterMillis = retryAfterMillis(response),
            )
        }
        throw JevApiException(
            kind = kind,
            status = status,
            message = redact(base + detailSuffix + requestIdSuffix(requestId)),
            requestId = requestId,
        )
    }

    private fun networkFailure(e: Exception): AttemptResult.Retryable {
        val description = buildString {
            append("TypeSafe API request failed: ")
            append(e::class.simpleName ?: "error")
            e.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(collapseWhitespace(it).take(300)) }
        }
        // SSRF-guard rejections surface as UnknownHostException(cause = SecurityException):
        // deterministic, so never retried.
        val retryable = e is IOException && !(e is UnknownHostException && e.cause is SecurityException)
        if (!retryable) {
            throw JevApiException(JevApiException.Kind.NETWORK, null, redact(description), cause = e)
        }
        return AttemptResult.Retryable(
            kind = JevApiException.Kind.NETWORK,
            status = null,
            message = description,
            requestId = null,
            retryAfterMillis = null,
            cause = e,
        )
    }

    /** Exponential backoff with "equal jitter": a random delay in [cap/2, cap]. */
    internal fun backoffMillis(retryNumber: Int): Long {
        val exponential = settings.initialBackoffMillis.toDouble() * 2.0.pow((retryNumber - 1).coerceAtLeast(0))
        val capped = min(exponential, settings.maxBackoffMillis.toDouble()).toLong()
        if (capped <= 1) return capped
        val half = capped / 2
        return half + random.nextLong(capped - half + 1)
    }

    private fun retryAfterMillis(response: HttpResponse): Long? {
        val millis = response.headers["retry-after-ms"]?.trim()?.toDoubleOrNull()?.toLong()
            ?: parseRetryAfterSeconds(response.headers[HttpHeaders.RetryAfter])
            ?: return null
        return millis.coerceIn(0L, JEV_MAX_RETRY_AFTER_MILLIS)
    }

    private fun parseRetryAfterSeconds(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return try {
            val date = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            java.time.Duration.between(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC), date).toMillis()
        } catch (_: Exception) {
            null
        }
    }

    private fun requestIdOf(response: HttpResponse): String? =
        (response.headers["x-typesafe-request-id"] ?: response.headers["x-request-id"])
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(128)

    private fun requestIdSuffix(requestId: String?): String = requestId?.let { " (request id: $it)" } ?: ""

    /** Defense in depth: no message built here may ever carry the API key. */
    private fun redact(text: String): String =
        if (apiKey.length >= 4) text.replace(apiKey, "***") else text

    override fun toString(): String = "HttpJevClient(baseUrl=${settings.baseUrl})"
}

/**
 * Best-effort, human-readable detail from a Jev error body: `detail` (string, or a
 * FastAPI-style validation list rendered as `loc: msg`), then `error` / `message`,
 * then the raw text. Whitespace is collapsed and the result truncated to
 * [JEV_ERROR_DETAIL_MAX_CHARS]. Returns null for an empty body.
 */
internal fun extractJevErrorDetail(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    val parsed: JsonElement? = try {
        lenientDetailJson.parseToJsonElement(trimmed)
    } catch (_: Exception) {
        null
    }
    val detail = when (parsed) {
        is JsonObject -> describeErrorObject(parsed)
        is JsonArray -> describeValidationList(parsed)
        else -> null
    } ?: trimmed
    return truncateDetail(collapseWhitespace(detail))
}

private fun describeErrorObject(obj: JsonObject): String {
    for (field in listOf("detail", "error", "message")) {
        when (val value = obj[field]) {
            null, JsonNull -> continue
            is JsonPrimitive -> if (value.isString) return value.content else return value.toString()
            is JsonArray -> return describeValidationList(value)
            is JsonObject -> {
                val nested = value["message"] ?: value["msg"] ?: value["detail"]
                return if (nested is JsonPrimitive && nested.isString) nested.content else value.toString()
            }
        }
    }
    return obj.toString()
}

private fun describeValidationList(items: JsonArray): String =
    items.joinToString("; ") { item ->
        if (item is JsonObject) {
            val msg = (item["msg"] as? JsonPrimitive)?.content ?: (item["message"] as? JsonPrimitive)?.content
            val loc = (item["loc"] as? JsonArray)?.joinToString(".") { (it as? JsonPrimitive)?.content ?: it.toString() }
            when {
                msg != null && !loc.isNullOrBlank() -> "$loc: $msg"
                msg != null -> msg
                else -> item.toString()
            }
        } else {
            (item as? JsonPrimitive)?.content ?: item.toString()
        }
    }

private fun collapseWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

private fun truncateDetail(text: String): String =
    if (text.length <= JEV_ERROR_DETAIL_MAX_CHARS) text else text.take(JEV_ERROR_DETAIL_MAX_CHARS) + "…"
