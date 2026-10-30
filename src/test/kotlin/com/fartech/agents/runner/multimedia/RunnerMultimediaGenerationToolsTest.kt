package com.fartech.agents.runner.multimedia

import ai.koog.agents.core.tools.ToolRegistry
import com.fartech.agents.runner.RunnerFileConfinement
import com.fartech.agents.runner.RunnerHttpClients
import com.fartech.agents.runner.RunnerToolFileAccess
import com.fartech.agents.tools.AudioModel
import com.fartech.agents.tools.ImageModel
import com.fartech.agents.tools.ToolFileAccess
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.*

class RunnerMultimediaGenerationToolsTest {
    private val imageModel = ImageModel.NANO_BANANA_2.model
    private val audioModel = AudioModel.GPT_AUDIO_MINI.model
    private val plan get() = RunnerMultimediaPlan(imageModel, audioModel)
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jVv0AAAAASUVORK5CYII=")
    private class MemoryFiles : ToolFileAccess {
        val values = linkedMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()
        override fun readBytes(path: String, maxBytes: Int): ByteArray {
            reads += path
            val bytes = values.getValue(path)
            if (bytes.size > maxBytes) throw SecurityException("read limit")
            return bytes.copyOf()
        }
        override fun writeBytes(path: String, bytes: ByteArray) { writes += path; values[path] = bytes.copyOf() }
    }
    private fun lease(key: String? = "leased-test-secret", guard: () -> Unit = {}) =
        RunnerMultimediaLease(key, setOf(imageModel, audioModel), guard)
    private fun response(request: Request, body: String, status: Int = 200, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType()))
            .apply { if (location != null) header("Location", location) }.build()
    private fun client(before: (Request) -> Unit = {}, reply: (Request) -> Response): HttpAccess =
        HttpAccess.withOkHttpConfig { builder ->
            RunnerHttpClients.configureDirect(builder, before)
            builder.addInterceptor { chain -> reply(chain.request()) }
        }
    private fun imageResponse() = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") {
            putJsonArray("images") { add(buildJsonObject {
                putJsonObject("image_url") { put("url", "data:image/png;base64,${Base64.getEncoder().encodeToString(png)}") }
            }) }
        } }) }
    }.toString()
    private fun audioResponse(bytes: ByteArray = "audio fixture".toByteArray()) = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") {
            putJsonObject("audio") { put("data", Base64.getEncoder().encodeToString(bytes)) }
        } }) }
    }.toString()

    private fun exceptionGraph(failure: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Throwable>().apply { add(failure) }
        val result = mutableListOf<Throwable>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            result += current
            current.cause?.let { pending.add(it) }
            current.suppressed.forEach { pending.add(it) }
        }
        return result
    }

    private fun assertSanitizedFailure(failure: RunnerMultimediaFailure, expectedMessage: String) {
        // Coroutine stack recovery may copy a safe exception and attach the original as its cause.
        // That is allowed only if the entire graph remains sanitized; a raw transport/parser cause
        // or suppressed failure must still fail this assertion.
        exceptionGraph(failure).forEach { nested ->
            assertIs<RunnerMultimediaFailure>(nested)
            assertEquals(expectedMessage, nested.message)
        }
        val rendered = failure.stackTraceToString()
        for (forbidden in listOf("leased-test-secret", "provider raw body", "transport details", "other.example")) {
            assertFalse(rendered.contains(forbidden), "Failure graph must not disclose provider data")
        }
    }

    private fun assertOriginalCancellation(expected: CancellationException, actual: CancellationException) {
        val graph = exceptionGraph(actual)
        assertTrue(graph.any { it === expected }, "Coroutine recovery must preserve the original cancellation")
        graph.forEach { nested ->
            assertEquals<Class<*>>(expected.javaClass, nested.javaClass)
            assertEquals(expected.message, nested.message)
        }
    }

    @Test fun `frozen route and leased key produce opaque file outputs without changing tool names`() = runBlocking {
        val files = MemoryFiles().apply { values["reference"] = png }
        val sent = mutableListOf<Pair<Request, JsonObject>>()
        var gated = 0
        val http = client(before = { gated++ }) { request ->
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            sent += request to body
            response(request, if (body["model"]!!.jsonPrimitive.content == imageModel) imageResponse() else audioResponse())
        }
        try {
            val tools = RunnerMultimediaGenerationTools(http, files, plan, lease())
            assertEquals(setOf("generateImage", "generateAudio"), ToolRegistry { tools(tools) }.tools.map { it.name }.toSet())
            assertContains(tools.generateImage("a square", "result.png", referenceImages = listOf("reference")), "result.png")
            assertContains(tools.generateAudio("hello", "result.wav"), "result.wav")
            assertContentEquals(png, files.values["result.png"])
            assertContentEquals("audio fixture".toByteArray(), files.values["result.wav"])
            assertEquals(2, gated)
            assertEquals(2, sent.size)
            sent.forEach { (request, _) ->
                assertEquals(RunnerMultimediaTransport.ENDPOINT, request.url.toString())
                assertEquals("POST", request.method)
                assertTrue(request.body!!.isOneShot(), "Provider writes must never be eligible for an OkHttp follow-up")
                assertEquals("Bearer leased-test-secret", request.header("Authorization"))
            }
            val content = sent[0].second["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertContains(content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content, "data:image/png;base64,")
            assertFalse(lease().toString().contains("leased-test-secret"))
        } finally { http.close() }
    }

    @Test fun `missing key model override disabled modality and lease grant reject before IO`() = runBlocking {
        val files = MemoryFiles().apply { values["reference"] = png }
        var sent = 0
        val http = client { sent++; response(it, imageResponse()) }
        try {
            for (key in listOf(null, "", "\nunsafe")) {
                val tools = RunnerMultimediaGenerationTools(http, files, plan, lease(key))
                assertFailsWith<SecurityException> { tools.generateImage("prompt", "out", referenceImages = listOf("reference")) }
            }
            val tools = RunnerMultimediaGenerationTools(http, files, plan, lease())
            assertFailsWith<SecurityException> { tools.generateImage("prompt", "out", model = ImageModel.GPT_5_IMAGE) }
            assertFailsWith<SecurityException> { tools.generateAudio("prompt", "out", model = AudioModel.GPT_AUDIO) }
            val imageOnly = RunnerMultimediaGenerationTools(http, files, RunnerMultimediaPlan(imageModel, null), lease())
            assertFailsWith<SecurityException> { imageOnly.generateAudio("prompt", "out") }
            val ungranted = RunnerMultimediaGenerationTools(http, files, plan, RunnerMultimediaLease("key", emptySet()) {})
            assertFailsWith<SecurityException> { ungranted.generateImage("prompt", "out") }
            assertEquals(0, sent)
            assertTrue(files.reads.isEmpty())
            assertTrue(files.writes.isEmpty())
        } finally { http.close() }
    }

    @Test fun `reference URLs inline data fake image and reference budgets cannot initiate network`() = runBlocking {
        val files = MemoryFiles().apply { values["reference"] = png; values["fake.png"] = "private text".toByteArray() }
        var sent = 0
        val http = client { sent++; response(it, imageResponse()) }
        try {
            val tools = RunnerMultimediaGenerationTools(http, files, plan, lease())
            for (path in listOf("https://127.0.0.1/private", "https://example.com/a.png", "data:image/png;base64,AAAA")) {
                assertFailsWith<SecurityException> { tools.generateImage("prompt", "out", referenceImages = listOf(path)) }
            }
            assertTrue(files.reads.isEmpty())
            assertFailsWith<RunnerMultimediaFailure> { tools.generateImage("prompt", "out", referenceImages = List(5) { "reference" }) }
            assertFailsWith<RunnerMultimediaFailure> { tools.generateImage("prompt", "out", referenceImages = listOf("fake.png")) }
            val bounded = RunnerMultimediaGenerationTools(http, files, plan, lease(), RunnerMultimediaLimits(maxTotalReferenceBytes = png.size))
            assertFailsWith<RunnerMultimediaFailure> { bounded.generateImage("prompt", "out", referenceImages = listOf("reference", "reference")) }
            assertEquals(0, sent)
            assertTrue(files.writes.isEmpty())
        } finally { http.close() }
    }

    @Test fun `post redirects errors and network exceptions are not replayed or echoed`() = runBlocking {
        for (status in listOf(302, 307, 429, 500, 503)) {
            var sent = 0
            val files = MemoryFiles()
            val http = client { sent++; response(it, "leased-test-secret provider raw body", status, "https://other.example/key") }
            try {
                val failure = assertFailsWith<RunnerMultimediaFailure> { RunnerMultimediaGenerationTools(http, files, plan, lease()).generateAudio("prompt", "out") }
                assertSanitizedFailure(failure, if (status in 300..399) "Multimedia redirects are not admitted" else "Multimedia provider request failed")
                assertEquals(1, sent)
                assertTrue(files.writes.isEmpty())
            } finally { http.close() }
        }
        var sent = 0
        val http = client { sent++; throw IOException("leased-test-secret transport details") }
        try {
            val failure = assertFailsWith<RunnerMultimediaFailure> { RunnerMultimediaGenerationTools(http, MemoryFiles(), plan, lease()).generateAudio("prompt", "out") }
            assertSanitizedFailure(failure, "Multimedia operation failed")
            assertEquals(1, sent)
        } finally { http.close() }
    }

    @Test fun `request response JSON and decoded bytes fail within limits without publication`() = runBlocking {
        val files = MemoryFiles()
        var sent = 0
        var body = audioResponse(ByteArray(20))
        val http = client { sent++; response(it, body) }
        try {
            fun tools(limits: RunnerMultimediaLimits) = RunnerMultimediaGenerationTools(http, files, plan, lease(), limits)
            assertFailsWith<RunnerMultimediaFailure> { tools(RunnerMultimediaLimits(maxPromptChars = 3)).generateAudio("long", "out") }
            assertFailsWith<RunnerMultimediaFailure> { tools(RunnerMultimediaLimits(maxRequestBytes = 8)).generateAudio("a", "out") }
            assertEquals(0, sent)
            assertFailsWith<RunnerMultimediaFailure> { tools(RunnerMultimediaLimits(maxResponseBytes = 8)).generateAudio("a", "out") }
            assertFailsWith<RunnerMultimediaFailure> { tools(RunnerMultimediaLimits(maxOutputBytes = 8)).generateAudio("a", "out") }
            body = "[".repeat(33) + "0" + "]".repeat(33)
            assertFailsWith<RunnerMultimediaFailure> { tools(RunnerMultimediaLimits()).generateAudio("a", "out") }
            body = audioResponse().replace("YXVkaW8gZml4dHVyZQ==", "invalid-base64")
            assertFailsWith<RunnerMultimediaFailure> { tools(RunnerMultimediaLimits()).generateAudio("a", "out") }
            assertEquals(4, sent)
            assertTrue(files.writes.isEmpty())
        } finally { http.close() }
    }

    @Test fun `cancellation and revocation before dispatch or after response remain terminal`() = runBlocking {
        val files = MemoryFiles()
        var revoked = false
        var sent = 0
        val cancellation = CancellationException("attempt cancelled")
        val http = client { sent++; revoked = true; response(it, audioResponse()) }
        try {
            val tools = RunnerMultimediaGenerationTools(http, files, plan, lease { if (revoked) throw cancellation })
            assertOriginalCancellation(cancellation, assertFailsWith<CancellationException> { tools.generateAudio("prompt", "out") })
            assertEquals(1, sent)
            assertOriginalCancellation(cancellation, assertFailsWith<CancellationException> { tools.generateAudio("prompt", "out") })
            assertEquals(1, sent)
            assertTrue(files.writes.isEmpty())
        } finally { http.close() }
        val wrapped = client(before = { throw IOException("transport", cancellation) }) { error("gate must reject") }
        try {
            assertOriginalCancellation(cancellation, assertFailsWith<CancellationException> { RunnerMultimediaGenerationTools(wrapped, files, plan, lease()).generateAudio("prompt", "out") })
        } finally { wrapped.close() }
    }

    @Test fun `reference reads and output writes retain run confinement`(@TempDir temp: Path) = runBlocking {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val sibling = Files.createDirectories(family.resolve("other/agent/workspace"))
        Files.write(sibling.resolve("private.png"), png)
        Files.createSymbolicLink(workspace.resolve("linked"), sibling)
        Files.write(workspace.resolve("reference.png"), png)
        val files = RunnerToolFileAccess(RunnerFileConfinement(root, family, "root"), workspace)
        var sent = 0
        val http = client { sent++; response(it, imageResponse()) }
        try {
            val tools = RunnerMultimediaGenerationTools(http, files, plan, lease())
            for (path in listOf("../private.png", sibling.resolve("private.png").toString(), "linked/private.png")) {
                assertFailsWith<SecurityException> { tools.generateImage("prompt", "out.png", referenceImages = listOf(path)) }
            }
            assertEquals(0, sent)
            assertContains(tools.generateImage("prompt", "out.png", referenceImages = listOf("reference.png")), "out.png")
            assertContentEquals(png, Files.readAllBytes(workspace.resolve("out.png")))
            assertFailsWith<SecurityException> { tools.generateImage("prompt", "linked/escape.png") }
            assertFalse(Files.exists(sibling.resolve("escape.png")))
            assertEquals(2, sent)
        } finally { http.close() }
    }
}
