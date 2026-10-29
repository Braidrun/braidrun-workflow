package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.IOException
import kotlin.test.*

/** All terminal interceptors are synthetic: tests do not open sockets or contact providers. */
class WebToolsTest {
    private class MemoryFiles : ToolFileAccess {
        val written = linkedMapOf<String, ByteArray>()
        override fun readBytes(path: String, maxBytes: Int): ByteArray = error("Web tools never read local files")
        override fun writeBytes(path: String, bytes: ByteArray) { written[path] = bytes.copyOf() }
    }

    private fun response(request: Request, body: String = "fixture", status: Int = 200, location: String? = null,
                         contentLength: String? = null, unknownLength: Boolean = false): Response {
        val responseBody = if (unknownLength) object : ResponseBody() {
            private val buffer = Buffer().writeUtf8(body)
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = buffer
        } else body.toResponseBody()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(responseBody).apply {
                if (location != null) header("Location", location)
                if (contentLength != null) header("Content-Length", contentLength)
            }.build()
    }

    private fun client(reply: (Request) -> Response): HttpAccess = HttpAccess.withOkHttpConfig { builder ->
        builder.addInterceptor { chain -> reply(chain.request()) }
    }

    @Test fun `GET truncates on bytes before materializing the complete body`() = runBlocking {
        val http = client { response(it, "你好world", unknownLength = true) }
        try {
            val result = WebTools(http, emptyList()).httpGet("https://8.8.8.8/a", maxSize = 5)
            assertContains(result, "Response size: 5 bytes (bounded prefix)")
            assertContains(result, "Response truncated at 5 bytes")
            assertFalse(result.contains("world"))
        } finally { http.close() }
    }

    @Test fun `exact byte boundary is not reported as truncated`() = runBlocking {
        val http = client { response(it, "你好", unknownLength = true) }
        try {
            val result = WebTools(http, emptyList()).httpGet("https://8.8.8.8/a", maxSize = 6)
            assertContains(result, "Response size: 6 bytes")
            assertContains(result, "你好")
            assertFalse(result.contains("truncated"))
        } finally { http.close() }
    }

    @Test fun `unknown and declared oversized downloads do not publish any file`() = runBlocking {
        for (declaredLength in listOf<String?>(null, "10000000")) {
            val files = MemoryFiles()
            val http = client { response(it, "abcdef", contentLength = declaredLength, unknownLength = true) }
            try {
                val result = WebTools(http, emptyList(), files).downloadFile("https://8.8.8.8/file", "result.bin", 5)
                assertContains(result, "allowed byte limit")
                assertTrue(files.written.isEmpty())
            } finally { http.close() }
        }
    }

    @Test fun `download bytes go only to injected file service`() = runBlocking {
        val files = MemoryFiles()
        val http = client { response(it, "hello") }
        try {
            val result = WebTools(http, emptyList(), files).downloadFile("https://8.8.8.8/file", "only-in-memory.bin", 5)
            assertContains(result, "File downloaded successfully")
            assertContentEquals("hello".toByteArray(), files.written["only-in-memory.bin"])
        } finally { http.close() }
    }

    @Test fun `HTML follows relative redirects through injected client and resolves final base URL`() = runBlocking {
        val seen = mutableListOf<String>()
        val http = client {
            seen += it.url.toString()
            if (seen.size == 1) response(it, status = 302, location = "/new/base/")
            else response(it, "<html><title>Fixture</title><body>Hello <a href='child'>child</a><img src='image.png'></body></html>")
        }
        try {
            val result = WebTools(http, emptyList()).fetchWebpage("https://8.8.8.8/start", includeLinks = true, includeImages = true)
            assertEquals(listOf("https://8.8.8.8/start", "https://8.8.8.8/new/base/"), seen)
            assertContains(result, "Title: Fixture")
            assertContains(result, "https://8.8.8.8/new/base/child")
            assertContains(result, "https://8.8.8.8/new/base/image.png")
        } finally { http.close() }
    }

    @Test fun `cross origin redirects remove custom secret headers`() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client {
            seen += it
            if (seen.size == 1) response(it, status = 302, location = "https://1.1.1.1/other") else response(it)
        }
        try {
            WebTools(http, emptyList()).httpGet("https://8.8.8.8/start", """{"Authorization":"Bearer fixture","Cookie":"session=fixture","X-Api-Key":"fixture","X-Custom-Secret":"fixture","Accept":"text/plain"}""")
            assertEquals(2, seen.size)
            for (name in listOf("Authorization", "Cookie", "X-Api-Key", "X-Custom-Secret")) assertNull(seen[1].header(name), name)
            // Ktor content negotiation may append its registered JSON media type.
            assertContains(seen[1].header("Accept").orEmpty().split(',').map(String::trim), "text/plain")
        } finally { http.close() }
    }

    @Test fun `same origin 307 preserves body while 303 switches to GET`() = runBlocking {
        for ((status, expectedMethod) in listOf(307 to "POST", 303 to "GET")) {
            val seen = mutableListOf<Request>()
            val http = client {
                seen += it
                if (seen.size == 1) response(it, status = status, location = "/next") else response(it, "{}")
            }
            try {
                WebTools(http, emptyList()).httpPost("https://8.8.8.8/start", """{"value":1}""")
                assertEquals(2, seen.size)
                assertEquals(expectedMethod, seen[1].method)
                if (status == 307) assertNotNull(seen[1].body) else assertNull(seen[1].body)
            } finally { http.close() }
        }
    }

    @Test fun `cross origin 307 never forwards a request body`() = runBlocking {
        var requests = 0
        val http = client { requests++; response(it, status = 307, location = "https://1.1.1.1/other") }
        try {
            val result = WebTools(http, emptyList()).httpPost("https://8.8.8.8/start", """{"client_secret":"fixture-only"}""")
            assertContains(result, "failed")
            assertEquals(1, requests)
        } finally { http.close() }
    }

    @Test fun `HTTP failures do not retry a write or expose error response secrets`() = runBlocking {
        var requests = 0
        val http = client { requests++; response(it, "{\"access_token\":\"response-secret-value\"}", 503) }
        try {
            val result = WebTools(http, emptyList()).httpPost("https://8.8.8.8/start", "{}")
            assertEquals(1, requests)
            assertContains(result, "HTTP 503")
            assertFalse(result.contains("response-secret-value"))
        } finally { http.close() }
    }

    @Test fun `response credentials request echoes query secrets and host secrets are redacted`() = runBlocking {
        val http = client { response(it, """{"access_token":"response-token","api_key":"response-key","echo":"request-secret","bodyEcho":"body-secret","queryEcho":"query-secret","host":"leased-host-secret"}""") }
        try {
            val tools = WebTools(http, emptyList(), MemoryFiles()) { it.replace("leased-host-secret", "<redacted>") }
            val result = tools.httpPost("https://8.8.8.8/start?token=query-secret", """{"client_secret":"body-secret"}""", """{"Authorization":"Bearer request-secret"}""")
            for (secret in listOf("response-token", "response-key", "request-secret", "body-secret", "query-secret", "leased-host-secret")) assertFalse(result.contains(secret), secret)
            assertContains(result, "<redacted>")
        } finally { http.close() }
    }

    @Test fun `raw exception messages never become tool diagnostics`() = runBlocking {
        val http = client { throw IOException("secret fixture-response-key https://8.8.8.8/?token=fixture-token") }
        try {
            val result = WebTools(http, emptyList()).httpGet("https://8.8.8.8/start")
            assertContains(result, "failed")
            assertFalse(result.contains("fixture-response-key"))
            assertFalse(result.contains("fixture-token"))
        } finally { http.close() }
    }

    @Test fun `wrapped authority cancellation propagates instead of becoming tool text`() = runBlocking {
        val cancellation = CancellationException("fixture revoked")
        val http = client { throw IOException("wrapped", cancellation) }
        try {
            val tools = WebTools(http, emptyList(), MemoryFiles())
            for (call in listOf<suspend () -> String>(
                { tools.httpGet("https://8.8.8.8/a") },
                { tools.fetchWebpage("https://8.8.8.8/a") },
                { tools.downloadFile("https://8.8.8.8/a", "output.bin") },
            )) {
                val failure = runCatching { call() }.exceptionOrNull()
                assertIs<CancellationException>(failure)
            }
        } finally { http.close() }
    }

    @Test fun `cancellation during response reading never publishes partial download bytes`() = runBlocking {
        val files = MemoryFiles()
        val http = client { request ->
            val source = object : Source {
                var sent = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (sent) throw IOException("wrapped during read", CancellationException("fixture revoked"))
                    sent = true
                    sink.writeUtf8("part")
                    return 4
                }
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() {}
            }.buffer()
            response(request).newBuilder().body(object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        }
        try {
            assertIs<CancellationException>(runCatching {
                WebTools(http, emptyList(), files).downloadFile("https://8.8.8.8/a", "output.bin")
            }.exceptionOrNull())
            assertTrue(files.written.isEmpty())
        } finally { http.close() }
    }

    @Test fun `HTML responses have a hard byte limit before parsing`() = runBlocking {
        val http = client { response(it, "a".repeat(WebToolTransport.MAX_RESPONSE_BYTES + 1), unknownLength = true) }
        try {
            val result = WebTools(http, emptyList()).fetchWebpage("https://8.8.8.8/a")
            assertContains(result, "allowed byte limit")
            assertFalse(result.contains("Webpage fetched successfully"))
        } finally { http.close() }
    }

    @Test fun `file publication cancellation propagates`() = runBlocking {
        val files = object : ToolFileAccess {
            override fun readBytes(path: String, maxBytes: Int): ByteArray = error("unexpected read")
            override fun writeBytes(path: String, bytes: ByteArray) { throw CancellationException("fixture revoked") }
        }
        val http = client { response(it) }
        try {
            assertIs<CancellationException>(runCatching { WebTools(http, emptyList(), files).downloadFile("https://8.8.8.8/a", "output.bin") }.exceptionOrNull())
        } finally { http.close() }
    }

    @Test fun `invalid size and empty page search fail before dispatch`() = runBlocking {
        var requests = 0
        val http = client { requests++; response(it) }
        try {
            val tools = WebTools(http, emptyList())
            assertContains(tools.httpGet("https://8.8.8.8/a", maxSize = 0), "invalid request arguments")
            assertContains(tools.downloadFile("https://8.8.8.8/a", "a.bin", -1), "invalid request arguments")
            assertContains(tools.searchInWebpage("https://8.8.8.8/a", ""), "invalid request arguments")
            assertContains(tools.httpGet("https://8.8.8.8/a", headersJson = "{broken"), "invalid request arguments")
            assertEquals(0, requests)
        } finally { http.close() }
    }

    @Test fun `redirect loops are bounded without writing a download`() = runBlocking {
        var requests = 0
        val files = MemoryFiles()
        val http = client { requests++; response(it, status = 302, location = "/again") }
        try {
            assertContains(WebTools(http, emptyList(), files).downloadFile("https://8.8.8.8/start", "output.bin"), "failed")
            assertEquals(11, requests)
            assertTrue(files.written.isEmpty())
        } finally { http.close() }
    }

    @Test fun `one total deadline bounds all redirect requests`() = runBlocking {
        val files = MemoryFiles()
        var requests = 0
        val http = client {
            requests++
            Thread.sleep(40)
            response(it, status = 302, location = "/again")
        }
        try {
            assertIs<CancellationException>(runCatching {
                WebToolTransport(http).request(HttpMethod.GET, "https://8.8.8.8/start", timeoutMillis = 60)
            }.exceptionOrNull())
            assertTrue(requests < 11)
            assertTrue(files.written.isEmpty())
        } finally { http.close() }
    }

    @Test @ResourceLock("WEB_TOOLS_ALLOW_PRIVATE_URLS")
    fun `search tools all use the injected transport and keep the library override`() = runBlocking {
        val old = System.getProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS")
        System.setProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS", "true") // Synthetic terminal: avoids even DNS in fixed search URLs.
        val seen = mutableListOf<String>()
        val http = client {
            seen += it.url.toString()
            response(it, "<html><body><div class='result'><a class='result__a' href='https://8.8.8.8/result'>Fixture</a><span class='result__snippet'>Snippet</span></div><img src='https://8.8.8.8/image.png'></body></html>")
        }
        try {
            val tools = WebTools(http, emptyList())
            assertContains(tools.searchWeb("fixture"), "Fixture")
            assertContains(tools.searchNews("fixture"), "Snippet")
            assertContains(tools.searchImages("fixture"), "https://8.8.8.8/image.png")
            assertEquals(3, seen.size)
            assertTrue(seen.all { it.startsWith("https://duckduckgo.com/html/?") })
            assertContains(tools.httpGet("http://127.0.0.1/library-only"), "successful")
        } finally {
            http.close()
            if (old == null) System.clearProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS") else System.setProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS", old)
        }
    }
}
