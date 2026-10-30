package com.fartech.agents.runner

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RunnerLocalModelTest {
    private fun binding(endpoint: String = "http://127.0.0.1:11434") = RunnerLocalModelBinding(
        "runner", "owner", "execution", "attempt", "registered-ollama", endpoint, "0.34.1", "fixture:1b", "a".repeat(64), 4096, 512)
    private fun request(json: Boolean = false) = RunnerLocalModelRequest("summarize", listOf(RunnerLocalMessage("user", "Summarize this fixture.")), 128, json)
    private fun gate(): RunnerEnvironmentGate {
        val attempt = RunnerProtocolTest.binding()
        return RunnerEnvironmentGate(attempt, setOf("engine-http")).also { it.install(RunnerProtocolTest.environment(attempt, Instant.now())) }
    }
    private class Authority : RunnerLocalModelAuthority {
        val observations = CopyOnWriteArrayList<RunnerLocalStopObservation>()
        var grantBinding: ((RunnerLocalModelBinding) -> RunnerLocalModelBinding)? = null
        var onCheck: () -> Unit = {}
        var confirmed = true
        var finishFailure = false
        var mayHaveStarted = 0
        override suspend fun reserve(binding: RunnerLocalModelBinding, requestId: String, request: RunnerLocalModelRequest) = object : RunnerLocalModelGrant {
            override val binding = grantBinding?.invoke(binding) ?: binding
            override val requestId = requestId
            override fun check() = onCheck()
            override suspend fun markInferenceMayHaveStarted() { mayHaveStarted++ }
            override suspend fun finish(observation: RunnerLocalStopObservation): Boolean {
                observations.add(observation)
                if (finishFailure) error("fixture journal unavailable")
                return confirmed
            }
        }
    }
    private class Service : AutoCloseable {
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        val headers = CopyOnWriteArrayList<Map<String, List<String>>>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint get() = "http://127.0.0.1:${server.address.port}"
        var serve: ((HttpExchange) -> Boolean)? = null
        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                exchange.use {
                    seen.add(exchange.requestURI.path to exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                    headers.add(exchange.requestHeaders.toMap())
                    if (serve?.invoke(exchange) != true) when (exchange.requestURI.path) {
                        "/api/version" -> answer(exchange, """{"version":"0.34.1"}""")
                        "/api/tags" -> answer(exchange, """{"models":[{"name":"fixture:1b","digest":"${"a".repeat(64)}"}]}""")
                        "/api/show" -> answer(exchange, """{"capabilities":["completion"]}""")
                        "/api/chat" -> answer(exchange, terminal())
                        else -> { exchange.sendResponseHeaders(404, -1) }
                    }
                }
            }
            server.start()
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    companion object {
        private fun terminal(content: String = "accepted") = buildJsonObject {
            put("model", "fixture:1b"); putJsonObject("message") { put("role", "assistant"); put("content", content) }
            put("done", true); put("done_reason", "stop"); put("prompt_eval_count", 12); put("eval_count", 2)
        }.toString() + "\n"
        private fun answer(exchange: HttpExchange, body: String, code: Int = 200) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
    }

    @Test fun `unregistered URL forms cannot construct a binding`() {
        for (url in listOf("http://localhost:11434", "http://127.1:11434", "http://2130706433:11434", "http://[::1]:11434",
            "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:011434",
            "https://127.0.0.1:11434", "http://user@127.0.0.1:11434", "http://127.0.0.1:11434/",
            "http://127.0.0.1:11434/api/chat", "http://127.0.0.1:11434?x=1", "http://127.0.0.1:11434#x", "http://192.168.0.1:11434")) {
            assertFailsWith<IllegalArgumentException>(url) { binding(url) }
        }
        assertFails { binding().copy(model = "fixture") }
        assertFails { binding().copy(model = "fixture:cloud") }
        assertFails { binding().copy(digest = "latest") }
        assertFails { binding().copy(maximumOutputTokens = 4096) }
    }

    @Test fun `local transport ignores system proxy and disables redirects retries cookies and auth`() {
        val client = RunnerLocalModelClient.transportBuilder().build()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertSame(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(okhttp3.Authenticator.NONE, client.authenticator)
        assertSame(okhttp3.Authenticator.NONE, client.proxyAuthenticator)
        assertEquals(120_000, client.callTimeoutMillis)
        assertEquals(120_000, client.readTimeoutMillis)
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }

    @Test fun `successful text request verifies identity twice and sends no authority or provider keys`(): Unit = runBlocking {
        Service().use { service ->
            val authority = Authority()
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client ->
                val output = client.complete(request()) { require(it == "accepted") }
                assertEquals("accepted", output.content); assertEquals("a".repeat(64), output.digest)
                assertEquals(12L, output.promptTokens); assertEquals(2L, output.outputTokens)
                assertTrue(output.requestId.isNotBlank()); assertNotNull(output.firstTokenNanos)
                assertEquals(listOf("/api/version", "/api/tags", "/api/show", "/api/chat", "/api/version", "/api/tags", "/api/show"), service.seen.map { it.first })
                assertTrue(service.headers.all { headers -> headers.keys.none { it.equals("Authorization", true) || it.equals("Cookie", true) || it.equals("Proxy-Authorization", true) } })
                val body = runnerJson.parseToJsonElement(service.seen.single { it.first == "/api/chat" }.second).jsonObject
                assertEquals(setOf("model", "messages", "stream", "think", "options"), body.keys)
                assertEquals(listOf(RunnerLocalStopObservation.SERVICE_REPORTED_DONE), authority.observations)
            }
        }
    }

    @Test fun `missing model and changed version fail before inference`(): Unit = runBlocking {
        for (badPath in listOf("/api/version", "/api/tags")) Service().use { service ->
            service.serve = { exchange -> if (exchange.requestURI.path == badPath) { answer(exchange, if (badPath.endsWith("version")) """{"version":"0.35.0"}""" else """{"models":[]}"""); true } else false }
            val authority = Authority()
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client -> assertFails { client.complete(request()) {} } }
            assertFalse(service.seen.any { it.first == "/api/chat" })
            assertEquals(0, authority.mayHaveStarted); assertEquals(listOf(RunnerLocalStopObservation.NOT_SENT), authority.observations)
        }
    }

    @Test fun `redirect cannot escape registered port or replay inference`(): Unit = runBlocking {
        Service().use { other -> Service().use { service ->
            service.serve = { exchange -> exchange.responseHeaders.add("Location", other.endpoint + "/api/chat"); exchange.sendResponseHeaders(307, -1); true }
            val authority = Authority()
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client -> assertFails { client.complete(request()) {} } }
            assertTrue(other.seen.isEmpty()); assertEquals(1, service.seen.size)
        } }
    }

    @Test fun `remote model hidden behind an ordinary local tag is rejected before inference`(): Unit = runBlocking {
        Service().use { service ->
            service.serve = { exchange -> if (exchange.requestURI.path == "/api/show") {
                answer(exchange, """{"capabilities":["completion"],"remote_host":"https://remote.invalid","remote_model":"remote-model"}"""); true
            } else false }
            val authority = Authority()
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client -> assertFails { client.complete(request()) {} } }
            assertFalse(service.seen.any { it.first == "/api/chat" })
            assertEquals(listOf(RunnerLocalStopObservation.NOT_SENT), authority.observations)
        }
    }

    @Test fun `cross owner grant is rejected without network`(): Unit = runBlocking {
        Service().use { service ->
            val authority = Authority().apply { grantBinding = { it.copy(ownerId = "another-owner") } }
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client -> assertFails { client.complete(request()) {} } }
            assertTrue(service.seen.isEmpty()); assertTrue(authority.observations.isEmpty())
        }
    }

    @Test fun `digest drift after successful response cannot publish success`(): Unit = runBlocking {
        Service().use { service ->
            val reads = AtomicInteger()
            service.serve = { exchange -> if (exchange.requestURI.path == "/api/tags" && reads.incrementAndGet() > 1) {
                answer(exchange, """{"models":[{"name":"fixture:1b","digest":"${"b".repeat(64)}"}]}"""); true
            } else false }
            val authority = Authority()
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client -> assertFails { client.complete(request()) {} } }
            assertEquals(1, service.seen.count { it.first == "/api/chat" })
            assertEquals(listOf(RunnerLocalStopObservation.SERVICE_REPORTED_DONE), authority.observations)
        }
    }

    @Test fun `partial malformed tool and truncated responses remain uncertain and never return output`(): Unit = runBlocking {
        val partial = """{"model":"fixture:1b","message":{"role":"assistant","content":"partial"},"done":false}""" + "\n"
        for (response in listOf(partial, terminal().replace("\"stop\"", "\"length\""), terminal().replace("\"assistant\"", "\"tool\""), terminal() + partial, "not-json\n")) Service().use { service ->
            service.serve = { exchange -> if (exchange.requestURI.path == "/api/chat") { answer(exchange, response); true } else false }
            val authority = Authority()
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client -> assertFails { client.complete(request()) {} } }
            assertEquals(listOf(RunnerLocalStopObservation.STOP_UNCONFIRMED), authority.observations)
        }
    }

    @Test fun `invalid JSON and schema rejection do not publish a successful model response`(): Unit = runBlocking {
        Service().use { service ->
            val authority = Authority()
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client ->
                assertFails { client.complete(request(json = true)) {} }
                assertFails { client.complete(request()) { error("fixture schema mismatch") } }
            }
            assertEquals(List(2) { RunnerLocalStopObservation.SERVICE_REPORTED_DONE }, authority.observations)
        }
    }

    @Test fun `unconfirmed or failed finish cannot return success and is not retried`(): Unit = runBlocking {
        for (throws in listOf(false, true)) Service().use { service ->
            val authority = Authority().apply { confirmed = false; finishFailure = throws }
            RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client -> assertFails { client.complete(request()) {} } }
            assertEquals(1, authority.observations.size)
        }
    }

    @Test fun `cancellation closes a streaming request but reports stop unconfirmed`(): Unit = runBlocking {
        Service().use { service ->
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            service.serve = { exchange -> if (exchange.requestURI.path == "/api/chat") {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("""{"model":"fixture:1b","message":{"role":"assistant","content":"partial"},"done":false}
""".toByteArray()); exchange.responseBody.flush(); started.complete(Unit)
                release.await(); true
            } else false }
            val authority = Authority()
            try {
                RunnerLocalModelClient(binding(service.endpoint), gate(), RunnerBusinessFence(), authority).use { client ->
                    val job = launch { client.complete(request()) {} }
                    withTimeout(2000) { started.await(); job.cancelAndJoin() }
                    assertEquals(listOf(RunnerLocalStopObservation.STOP_UNCONFIRMED), authority.observations)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun `authority loss during a blocked stream closes the request and forbids late output`(): Unit = runBlocking {
        Service().use { service ->
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            service.serve = { exchange -> if (exchange.requestURI.path == "/api/chat") {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("""{"model":"fixture:1b","message":{"role":"assistant","content":"partial"},"done":false}
""".toByteArray()); exchange.responseBody.flush(); started.complete(Unit)
                release.await(); true
            } else false }
            val authority = Authority()
            val environment = gate()
            try {
                RunnerLocalModelClient(binding(service.endpoint), environment, RunnerBusinessFence(), authority).use { client ->
                    val pending = async { runCatching { client.complete(request()) {} } }
                    withTimeout(2000) { started.await() }
                    environment.invalidate("fixture authority revoked")
                    assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                    assertEquals(listOf(RunnerLocalStopObservation.STOP_UNCONFIRMED), authority.observations)
                }
            } finally { release.countDown() }
        }
    }
}
