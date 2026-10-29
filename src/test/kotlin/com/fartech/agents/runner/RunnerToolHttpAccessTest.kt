package com.fartech.agents.runner

import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.*

/** Synthetic terminal interceptor prevents DNS/socket IO while exercising the real HttpAccess chain. */
class RunnerToolHttpAccessTest {
    private fun response(request: Request, status: Int, redirect: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
        .body("fixture".toResponseBody())
        .apply { if (redirect != null) header("Location", redirect) }.build()

    @Test fun `every redirect rechecks gate and revocation prevents the next request`() = runBlocking {
        var checks = 0
        val dispatched = mutableListOf<String>()
        val http = HttpAccess.withOkHttpConfig { builder ->
            RunnerHttpClients.configureDirect(builder) {
                checks++
                if (checks == 2) throw IOException("attempt revoked")
            }
            builder.addInterceptor { chain ->
                dispatched += chain.request().url.toString()
                response(chain.request(), 302, "https://1.1.1.1/next")
            }
        }
        try {
            val failure = runCatching { http.client.get("https://8.8.8.8/start") }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(2, checks)
            assertEquals(listOf("https://8.8.8.8/start"), dispatched)
        } finally { http.close() }
    }

    @Test fun `cross origin redirects strip auth and still validate every request`() = runBlocking {
        val checked = mutableListOf<String>()
        val requests = mutableListOf<Request>()
        val http = HttpAccess.withOkHttpConfig { builder ->
            RunnerHttpClients.configureDirect(builder) { checked += it.url.toString() }
            builder.addInterceptor { chain ->
                requests += chain.request()
                when (requests.size) {
                    1 -> response(chain.request(), 302, "/same-origin")
                    2 -> response(chain.request(), 307, "https://1.1.1.1/other-origin")
                    else -> response(chain.request(), 200)
                }
            }
        }
        try {
            assertEquals("fixture", http.client.get("https://8.8.8.8/start") {
                header("Authorization", "Bearer fixture")
                header("Cookie", "session=fixture")
                header("Proxy-Authorization", "Basic fixture")
            }.bodyAsText())
            assertEquals(3, requests.size)
            assertEquals(requests.map { it.url.toString() }, checked)
            assertEquals("Bearer fixture", requests[1].header("Authorization"))
            for (name in listOf("Authorization", "Cookie", "Proxy-Authorization")) assertNull(requests[2].header(name), name)
        } finally { http.close() }
    }

    @Test fun `redirect into private network fails before dispatch`() = runBlocking {
        var dispatched = 0
        val http = HttpAccess.withOkHttpConfig { builder ->
            RunnerHttpClients.configureDirect(builder) { }
            builder.addInterceptor { chain ->
                dispatched++
                response(chain.request(), 302, "http://127.0.0.1/private")
            }
        }
        try {
            assertTrue(runCatching { http.client.get("https://8.8.8.8/start") }.isFailure)
            assertEquals(1, dispatched)
        } finally { http.close() }
    }
    @Test fun `Runner DNS validates the actual connection addresses and rejects mixed answers`() {
        val public = java.net.InetAddress.getByName("8.8.8.8")
        val private = java.net.InetAddress.getByName("127.0.0.1")
        assertEquals(listOf(public), RunnerHttpClients.strictDns { listOf(public) }.lookup("fixture.test"))
        for (answer in listOf(emptyList(), listOf(private), listOf(public, private))) {
            assertFailsWith<java.net.UnknownHostException> {
                RunnerHttpClients.strictDns { answer }.lookup("fixture.test")
            }
        }
    }
    @Test fun `library private URL escape hatch cannot widen Runner redirects or DNS`() = runBlocking {
        val key = "WEB_TOOLS_ALLOW_PRIVATE_URLS"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        var dispatched = 0
        val http = HttpAccess.withOkHttpConfig { builder ->
            RunnerHttpClients.configureDirect(builder) { }
            builder.addInterceptor { chain ->
                dispatched++
                response(chain.request(), 302, "http://127.0.0.1/private")
            }
        }
        try {
            assertTrue(runCatching { http.client.get("https://8.8.8.8/start") }.isFailure)
            assertEquals(1, dispatched)
            assertFailsWith<java.net.UnknownHostException> {
                RunnerHttpClients.strictDns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.lookup("fixture.test")
            }
        } finally {
            http.close()
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
