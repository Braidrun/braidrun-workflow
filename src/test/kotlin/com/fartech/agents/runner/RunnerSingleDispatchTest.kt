package com.fartech.agents.runner

import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.HttpSingleDispatch
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class RunnerSingleDispatchTest {
    private class Server(private val status: Int = 200, private val headers: Map<String, String> = emptyMap()) : AutoCloseable {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}/test"
        init {
            server.createContext("/") { e ->
                e.requestBody.use { it.readBytes() }
                seen.add(e.requestURI.path to e.requestHeaders.getFirst("X-Changed"))
                headers.forEach { (k, v) -> e.responseHeaders.add(k, v) }
                val bytes = "fixture".toByteArray()
                e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }
    private fun client(change: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().retryOnConnectionFailure(false).apply(change).apply(HttpSingleDispatch::install).build()
    private fun close(c: OkHttpClient) { c.dispatcher.executorService.shutdownNow(); c.connectionPool.evictAll() }

    @Test fun `503 Retry After zero cannot resend the same POST exchange`() {
        Server(503, mapOf("Retry-After" to "0")).use { server ->
            val c = client()
            try {
                assertFails { c.newCall(Request.Builder().url(server.url).post("side-effect".toRequestBody()).build()).execute().use { it.body?.string() } }
                assertEquals(1, server.seen.size)
            } finally { close(c) }
        }
    }

    @Test fun `a changed header on authentication followup does not create a new dispatch authority`() {
        Server(401).use { server ->
            val c = client { authenticator { _, response -> response.request.newBuilder().header("X-Changed", "second-request").build() } }
            try {
                assertFails { c.newCall(Request.Builder().url(server.url).build()).execute().use { it.body?.string() } }
                assertEquals(listOf("/test" to null), server.seen.toList())
            } finally { close(c) }
        }
    }

    @Test fun `redirect target changes cannot reuse the same Call`() {
        Server(302, mapOf("Location" to "/other")).use { server ->
            val c = client { followRedirects(true) }
            try {
                assertFails { c.newCall(Request.Builder().url(server.url).build()).execute().use { it.body?.string() } }
                assertEquals(listOf("/test" to null), server.seen.toList())
            } finally { close(c) }
        }
    }

    @Test fun `separate normal web GET calls each retain one dispatch`() {
        Server().use { server ->
            val c = client()
            try {
                repeat(2) { c.newCall(Request.Builder().url(server.url).build()).execute().use { assertEquals("fixture", it.body?.string()) } }
                assertEquals(2, server.seen.size)
            } finally { close(c) }
        }
    }

    @Test fun `HttpAccess singleRequestClient applies physical guard without changing normal GET`(): Unit = runBlocking {
        // Explicit fixture DNS exists only on this test client; no process-wide private URL override.
        fun http() = HttpAccess.withOkHttpConfig { b ->
            b.proxy(Proxy.NO_PROXY).dns { hostname ->
                assertEquals("fixture.invalid", hostname)
                listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        Server(503, mapOf("Retry-After" to "0")).use { server ->
            val access = http()
            try {
                assertFails { access.singleRequestClient.get("http://fixture.invalid:${server.server.address.port}/test").bodyAsText() }
                assertEquals(1, server.seen.size)
            } finally { access.close() }
        }
        Server().use { server ->
            val access = http()
            try {
                repeat(2) { assertEquals("fixture", access.singleRequestClient.get("http://fixture.invalid:${server.server.address.port}/test").bodyAsText()) }
                assertEquals(2, server.seen.size)
            } finally { access.close() }
        }
    }
}
