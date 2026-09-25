package com.fartech.agents.jev

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpJevClientTest {

    private companion object {
        const val API_KEY = "ts-live-SECRET-0123456789abcdef"

        val REQUEST = JevRequest(
            model = "jev-latest",
            state = JsonPrimitive("Help! My payouts have been failing for 3 days."),
            questions = linkedMapOf(
                "team" to JevChoiceQuestion(
                    instructions = JsonPrimitive("Which team should handle this?"),
                    criteria = linkedMapOf("billing" to JsonPrimitive("Payments"), "technical" to null),
                ),
                "is_urgent" to JevNoulQuestion(instructions = JsonPrimitive("Does this convey urgency?")),
            ),
        )

        const val OK_BODY = """{"model":"jev-1.13.0","answers":{
            "team":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1},"confidence":0.8},
            "is_urgent":{"type":"noul","noul":0.95}},
            "usage":{"input_tokens":296,"output_tokens":20}}"""

        /** Tiny backoff so retry tests stay fast even with real delays. */
        val FAST = JevClientSettings(maxRetries = 3, initialBackoffMillis = 10, maxBackoffMillis = 40)

        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

    private val delays = mutableListOf<Long>()

    private fun mockClient(engine: MockEngine, installTimeout: Boolean = true, expectSuccess: Boolean = false) =
        HttpClient(engine) {
            this.expectSuccess = expectSuccess
            if (installTimeout) install(HttpTimeout)
        }

    private fun jevClient(
        engine: MockEngine,
        settings: JevClientSettings = FAST,
        httpClient: HttpClient = mockClient(engine),
    ) = HttpJevClient(API_KEY, settings, httpClient, sleep = { delays += it }, random = Random(7))

    private fun MockRequestHandleScope.ok(): HttpResponseData =
        respond(OK_BODY, HttpStatusCode.OK, JSON_HEADERS)

    private fun MockRequestHandleScope.error(
        status: Int,
        body: String = """{"detail":"boom"}""",
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponseData {
        val headers = headersOf(
            *(mapOf(HttpHeaders.ContentType to "application/json") + extraHeaders)
                .map { (k, v) -> k to listOf(v) }.toTypedArray()
        )
        return respond(body, HttpStatusCode(status, "Status $status"), headers)
    }

    /** Engine answering from [script] in order (the last entry repeats). */
    private fun scripted(vararg script: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): MockEngine {
        val calls = AtomicInteger()
        return MockEngine { request ->
            val index = calls.getAndIncrement().coerceAtMost(script.size - 1)
            script[index](this, request)
        }
    }

    private fun assertNoKey(error: Throwable) {
        assertFalse(error.message.orEmpty().contains(API_KEY), "API key leaked into message: ${error.message}")
        assertFalse(error.toString().contains(API_KEY), "API key leaked into toString")
    }

    @Nested
    inner class Success {

        @Test
        fun `posts the request with auth and json headers to the systemone endpoint`(): Unit = runBlocking {
            val engine = scripted({ ok() })
            val response = jevClient(engine).systemOne(REQUEST)

            assertEquals("jev-1.13.0", response.model)
            assertEquals("billing", assertIs<JevChoiceAnswer>(response.answers["team"]).choice)
            assertEquals(JevUsage(296, 20), response.usage)

            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals("https://api.typesafe.ai/v1/systemone", sent.url.toString())
            assertEquals("Bearer $API_KEY", sent.headers[HttpHeaders.Authorization])
            assertEquals(ContentType.Application.Json, sent.body.contentType?.withoutParameters())
            val body = (sent.body as TextContent).text
            val json = JevJson.parseToJsonElement(body).jsonObject
            assertEquals("jev-latest", json["model"]!!.jsonPrimitive.content)
            assertTrue(body.contains("\"type\":\"choice\""), body)
            assertTrue(body.contains("\"type\":\"noul\""), body)
            assertTrue(delays.isEmpty())
        }

        @Test
        fun `content negotiation on the host client does not change the wire body`(): Unit = runBlocking {
            // Same plugin set as HttpAccess: lenient/pretty kotlinx Json (encodeDefaults=false) + Jackson.
            val engine = scripted({ ok() })
            val httpAccessLike = HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true })
                    jackson()
                }
                install(HttpTimeout)
            }
            val response = jevClient(engine, httpClient = httpAccessLike).systemOne(REQUEST)

            assertEquals("jev-1.13.0", response.model)
            val body = (engine.requestHistory.single().body as TextContent).text
            assertEquals(JevJson.encodeToString(JevRequest.serializer(), REQUEST), body)
            assertFalse(body.contains("\n"), "body must be the compact JevJson encoding")
        }

        @Test
        fun `default factory wraps the passed http client`(): Unit = runBlocking {
            val engine = scripted({ ok() })
            val client = JevClientFactory.Default.create(API_KEY, FAST, mockClient(engine))

            assertIs<HttpJevClient>(client)
            client.systemOne(REQUEST)
            assertEquals(1, engine.requestHistory.size, "the factory must not build its own client")
            assertFalse(client.toString().contains(API_KEY))
        }
    }

    @Nested
    inner class UrlJoin {

        private fun urlFor(baseUrl: String): String = runBlocking {
            val engine = scripted({ ok() })
            jevClient(engine, FAST.copy(baseUrl = baseUrl)).systemOne(REQUEST)
            engine.requestHistory.single().url.toString()
        }

        @Test
        fun `bare host gets the v1 prefix`() {
            assertEquals("https://api.typesafe.ai/v1/systemone", urlFor("https://api.typesafe.ai"))
            assertEquals("https://api.typesafe.ai/v1/systemone", urlFor("https://api.typesafe.ai/"))
        }

        @Test
        fun `base ending in v1 does not double the version segment`() {
            assertEquals("https://gw.example.com/v1/systemone", urlFor("https://gw.example.com/v1"))
            assertEquals("https://gw.example.com/v1/systemone", urlFor("https://gw.example.com/v1/"))
        }

        @Test
        fun `version-less proxy path keeps the v1 prefix`() {
            assertEquals("https://gw.example.com/typesafe/v1/systemone", urlFor("https://gw.example.com/typesafe"))
        }

        @Test
        fun `url helper matches the client`() {
            assertEquals("https://x.test/api/v1/systemone", jevSystemOneUrl("https://x.test/api/v1"))
            assertEquals("https://x.test/v1/systemone", jevSystemOneUrl(" https://x.test "))
        }
    }

    @Nested
    inner class NonRetryableErrors {

        @ParameterizedTest
        @ValueSource(ints = [401, 403])
        fun `auth failures map to UNAUTHORIZED without retry`(status: Int): Unit = runBlocking {
            val engine = scripted({
                error(status, """{"detail":"Invalid API key"}""", mapOf("x-typesafe-request-id" to "req_auth_1"))
            })
            val error = assertFailsWith<JevApiException> { jevClient(engine).systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.UNAUTHORIZED, error.kind)
            assertEquals(status, error.status)
            assertEquals("req_auth_1", error.requestId)
            assertTrue(error.isConfigurationError)
            assertTrue(error.message!!.contains("HTTP $status"), error.message)
            assertTrue(error.message!!.contains("Invalid API key"), error.message)
            assertTrue(error.message!!.contains("req_auth_1"), error.message)
            assertEquals(1, engine.requestHistory.size)
            assertTrue(delays.isEmpty())
            assertNoKey(error)
        }

        @ParameterizedTest
        @ValueSource(ints = [400, 404, 422])
        fun `invalid requests map to INVALID_REQUEST without retry`(status: Int): Unit = runBlocking {
            val engine = scripted({
                error(
                    status,
                    """{"detail":[{"loc":["body","questions","team","criteria"],"msg":"field required","type":"missing"}]}""",
                    mapOf("x-request-id" to "req_422"),
                )
            })
            val error = assertFailsWith<JevApiException> { jevClient(engine).systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.INVALID_REQUEST, error.kind)
            assertEquals(status, error.status)
            assertTrue(error.isConfigurationError)
            assertTrue(error.message!!.contains("body.questions.team.criteria: field required"), error.message)
            assertTrue(error.message!!.contains("(request id: req_422)"), error.message)
            assertEquals(1, engine.requestHistory.size)
            assertNoKey(error)
        }

        @Test
        fun `long error detail is truncated`(): Unit = runBlocking {
            val huge = "x".repeat(5_000)
            val engine = scripted({ error(422, """{"detail":"$huge"}""") })
            val error = assertFailsWith<JevApiException> { jevClient(engine).systemOne(REQUEST) }

            assertTrue(error.message!!.length < 700, "message not truncated: ${error.message!!.length}")
            assertTrue(error.message!!.contains("…"))
        }

        @Test
        fun `other 5xx statuses are not retried`(): Unit = runBlocking {
            val engine = scripted({ error(501) })
            val error = assertFailsWith<JevApiException> { jevClient(engine).systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.SERVER_ERROR, error.kind)
            assertEquals(1, engine.requestHistory.size)
        }

        @Test
        fun `malformed success body is INVALID_RESPONSE without retry`(): Unit = runBlocking {
            val engine = scripted({ respond("<html>gateway</html>", HttpStatusCode.OK) })
            val error = assertFailsWith<JevApiException> { jevClient(engine).systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.INVALID_RESPONSE, error.kind)
            assertEquals(200, error.status)
            assertTrue(error.isTransient)
            assertEquals(1, engine.requestHistory.size)
        }

        @Test
        fun `success body missing an answer is INVALID_RESPONSE`(): Unit = runBlocking {
            val engine = scripted({
                respond(
                    """{"model":"jev-1.13.0","answers":{"team":{"type":"choice","choice":"billing"}}}""",
                    HttpStatusCode.OK,
                    headersOf("x-typesafe-request-id", "req_partial"),
                )
            })
            val error = assertFailsWith<JevApiException> { jevClient(engine).systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.INVALID_RESPONSE, error.kind)
            assertEquals("req_partial", error.requestId)
            assertTrue(error.message!!.contains("is_urgent"), error.message)
        }

        @Test
        fun `API key echoed by the server is redacted from the message`(): Unit = runBlocking {
            val engine = scripted({ error(401, """{"detail":"Key $API_KEY is revoked"}""") })
            val error = assertFailsWith<JevApiException> { jevClient(engine).systemOne(REQUEST) }

            assertNoKey(error)
            assertTrue(error.message!!.contains("Key *** is revoked"), error.message)
        }

        @Test
        fun `expectSuccess clients are handled like plain clients`(): Unit = runBlocking {
            val engine = scripted({ error(401, """{"detail":"nope"}""") })
            val client = jevClient(engine, httpClient = mockClient(engine, expectSuccess = true))
            val error = assertFailsWith<JevApiException> { client.systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.UNAUTHORIZED, error.kind)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Nested
    inner class Retries {

        @Test
        fun `429 is retried honoring retry-after seconds`(): Unit = runBlocking {
            val engine = scripted({ error(429, extraHeaders = mapOf(HttpHeaders.RetryAfter to "2")) }, { ok() })
            val response = jevClient(engine).systemOne(REQUEST)

            assertEquals("jev-1.13.0", response.model)
            assertEquals(2, engine.requestHistory.size)
            assertEquals(listOf(2_000L), delays)
        }

        @Test
        fun `retry-after is capped at 20 seconds`(): Unit = runBlocking {
            val engine = scripted({ error(529, extraHeaders = mapOf(HttpHeaders.RetryAfter to "120")) }, { ok() })
            jevClient(engine).systemOne(REQUEST)

            assertEquals(listOf(JEV_MAX_RETRY_AFTER_MILLIS), delays)
        }

        @Test
        fun `retry-after-ms is honored`(): Unit = runBlocking {
            val engine = scripted({ error(429, extraHeaders = mapOf("retry-after-ms" to "150")) }, { ok() })
            jevClient(engine).systemOne(REQUEST)

            assertEquals(listOf(150L), delays)
        }

        @Test
        fun `529 is retried with exponential backoff and jitter`(): Unit = runBlocking {
            val engine = scripted({ error(529) }, { error(529) }, { error(529) }, { ok() })
            jevClient(engine).systemOne(REQUEST)

            assertEquals(4, engine.requestHistory.size)
            assertEquals(3, delays.size)
            // FAST: initial 10ms, doubling, capped at 40ms; equal jitter keeps each delay in [cap/2, cap]
            assertTrue(delays[0] in 5L..10L, "delays=$delays")
            assertTrue(delays[1] in 10L..20L, "delays=$delays")
            assertTrue(delays[2] in 20L..40L, "delays=$delays")
        }

        @ParameterizedTest
        @ValueSource(ints = [500, 502, 503, 504])
        fun `transient 5xx statuses are retried then succeed`(status: Int): Unit = runBlocking {
            val engine = scripted({ error(status) }, { ok() })
            val response = jevClient(engine).systemOne(REQUEST)

            assertEquals("jev-1.13.0", response.model)
            assertEquals(2, engine.requestHistory.size)
        }

        @Test
        fun `exhausted retries surface the last error with attempts and request id`(): Unit = runBlocking {
            val engine = scripted({
                error(503, """{"detail":"maintenance"}""", mapOf("x-typesafe-request-id" to "req_503"))
            })
            val settings = FAST.copy(maxRetries = 2)
            val error = assertFailsWith<JevApiException> { jevClient(engine, settings).systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.SERVER_ERROR, error.kind)
            assertEquals(503, error.status)
            assertEquals("req_503", error.requestId)
            assertEquals(3, engine.requestHistory.size)
            assertEquals(2, delays.size)
            assertTrue(error.message!!.contains("after 3 attempts"), error.message)
            assertTrue(error.message!!.contains("maintenance"), error.message)
            assertTrue(error.message!!.contains("(request id: req_503)"), error.message)
            assertTrue(error.isTransient)
            assertNoKey(error)
        }

        @Test
        fun `rate limit exhaustion maps to RATE_LIMITED and overload to OVERLOADED`(): Unit = runBlocking {
            val rateLimited = assertFailsWith<JevApiException> {
                jevClient(scripted({ error(429) }), FAST.copy(maxRetries = 1)).systemOne(REQUEST)
            }
            assertEquals(JevApiException.Kind.RATE_LIMITED, rateLimited.kind)

            val overloaded = assertFailsWith<JevApiException> {
                jevClient(scripted({ error(529) }), FAST.copy(maxRetries = 0)).systemOne(REQUEST)
            }
            assertEquals(JevApiException.Kind.OVERLOADED, overloaded.kind)
            assertFalse(overloaded.message!!.contains("attempts"), "single attempt must not claim retries")
        }

        @Test
        fun `io failures are retried and exhaust to NETWORK`(): Unit = runBlocking {
            val attempts = AtomicInteger()
            val engine = scripted(
                { attempts.incrementAndGet(); throw IOException("connection reset") },
                { attempts.incrementAndGet(); ok() },
            )
            assertEquals("jev-1.13.0", jevClient(engine).systemOne(REQUEST).model)
            assertEquals(2, attempts.get(), "one failed + one successful call")
            assertEquals(1, delays.size)

            val failing = scripted({ throw IOException("connection reset") })
            val error = assertFailsWith<JevApiException> {
                jevClient(failing, FAST.copy(maxRetries = 2)).systemOne(REQUEST)
            }
            assertEquals(JevApiException.Kind.NETWORK, error.kind)
            assertNull(error.status)
            assertIs<IOException>(error.cause)
            assertTrue(error.message!!.contains("connection reset"), error.message)
            assertTrue(error.message!!.contains("after 3 attempts"), error.message)
        }

        @Test
        fun `request timeout is retried and reported as NETWORK`(): Unit = runBlocking {
            val engine = scripted({
                delay(5_000)
                ok()
            })
            val settings = FAST.copy(requestTimeoutMillis = 100, maxRetries = 1)
            val error = assertFailsWith<JevApiException> { jevClient(engine, settings).systemOne(REQUEST) }

            assertEquals(JevApiException.Kind.NETWORK, error.kind)
            assertIs<HttpRequestTimeoutException>(error.cause)
            assertEquals(1, delays.size, "timeout must be retried once")
        }

        @Test
        fun `clients without HttpTimeout still enforce the request timeout`(): Unit = runBlocking {
            val engine = scripted({
                delay(5_000)
                ok()
            })
            val settings = FAST.copy(requestTimeoutMillis = 100, maxRetries = 0)
            val error = assertFailsWith<JevApiException> {
                jevClient(engine, settings, httpClient = mockClient(engine, installTimeout = false)).systemOne(REQUEST)
            }
            assertEquals(JevApiException.Kind.NETWORK, error.kind)
        }
    }

    @Nested
    inner class Cancellation {

        @Test
        fun `cancellation propagates untouched and is not retried`(): Unit = runBlocking {
            val started = CompletableDeferred<Unit>()
            val engine = scripted({
                started.complete(Unit)
                delay(10_000)
                ok()
            })
            val call = async { jevClient(engine).systemOne(REQUEST) }
            started.await()
            call.cancel()

            assertFailsWith<CancellationException> { call.await() }
            assertTrue(delays.isEmpty(), "a cancelled call must not back off / retry")
        }
    }

    @Nested
    inner class Construction {

        @Test
        fun `blank api key is a MISSING_CREDENTIALS error`() {
            val error = assertFailsWith<JevApiException> {
                HttpJevClient("  ", FAST, mockClient(scripted({ ok() })))
            }
            assertEquals(JevApiException.Kind.MISSING_CREDENTIALS, error.kind)
            assertTrue(error.isConfigurationError)
        }

        @Test
        fun `settings reject nonsensical values`() {
            assertFailsWith<IllegalArgumentException> { JevClientSettings(baseUrl = " ") }
            assertFailsWith<IllegalArgumentException> { JevClientSettings(requestTimeoutMillis = 0) }
            assertFailsWith<IllegalArgumentException> { JevClientSettings(maxRetries = -1) }
            assertFailsWith<IllegalArgumentException> { JevClientSettings(initialBackoffMillis = 100, maxBackoffMillis = 10) }
        }

        @Test
        fun `defaults match the documented API`() {
            val settings = JevClientSettings()
            assertEquals("https://api.typesafe.ai", settings.baseUrl)
            assertEquals(30_000, settings.requestTimeoutMillis)
            assertEquals(3, settings.maxRetries)
            assertEquals("typesafe", TYPESAFE_PROVIDER_ID)
            assertEquals("jev-latest", JEV_DEFAULT_MODEL)
        }

        @Test
        fun `exception carries the given cause`() {
            val cause = IOException("x")
            val error = JevApiException(JevApiException.Kind.NETWORK, null, "m", cause = cause)
            assertSame(cause, error.cause)
        }
    }

    @Nested
    inner class ErrorDetail {

        @Test
        fun `string detail is used as-is`() {
            assertEquals("Invalid API key", extractJevErrorDetail("""{"detail":"Invalid API key"}"""))
        }

        @Test
        fun `validation list is rendered as loc and msg`() {
            assertEquals(
                "body.model: field required; body.state: bad",
                extractJevErrorDetail(
                    """{"detail":[{"loc":["body","model"],"msg":"field required"},{"loc":["body","state"],"msg":"bad"}]}"""
                ),
            )
        }

        @Test
        fun `error object message and plain text bodies are supported`() {
            assertEquals("slow down", extractJevErrorDetail("""{"error":{"message":"slow down"}}"""))
            assertEquals("Bad Gateway from upstream", extractJevErrorDetail("Bad   Gateway\nfrom upstream"))
            assertNull(extractJevErrorDetail("   "))
        }
    }
}
