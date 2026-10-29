package com.fartech.agents.runner

import com.fartech.agents.tools.WebTools
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Exercises the same strict host transport and confined files that Runner injects into WebTools. */
class RunnerWebToolsTest {
    private fun response(request: Request, status: Int = 200, redirect: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
        .body("fixture".toResponseBody())
        .apply { if (redirect != null) header("Location", redirect) }.build()

    private fun client(before: (Request) -> Unit = {}, reply: (Request) -> Response): HttpAccess =
        HttpAccess.withOkHttpConfig { builder ->
            RunnerHttpClients.configureDirect(builder, before)
            // This terminal interceptor is after the real host gate, before DNS/socket dispatch.
            builder.addInterceptor { chain -> reply(chain.request()) }
        }

    @Test fun `download redirect rechecks gate and revocation prevents next dispatch`(@TempDir temp: Path) = runBlocking {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val files = RunnerToolFileAccess(RunnerFileConfinement(root, family, "root"), workspace)
        var checks = 0
        val sent = mutableListOf<String>()
        val http = client(before = {
            checks++
            if (checks == 2) throw IOException("revoked", RunnerAuthorityLost())
        }) {
            sent += it.url.toString()
            response(it, 302, "https://1.1.1.1/next")
        }
        try {
            assertIs<CancellationException>(runCatching {
                WebTools(http, emptyList(), files).downloadFile("https://8.8.8.8/start", "result.bin")
            }.exceptionOrNull())
            assertEquals(2, checks)
            assertEquals(listOf("https://8.8.8.8/start"), sent)
            assertFalse(Files.exists(workspace.resolve("result.bin")))
        } finally { http.close() }
    }

    @Test @ResourceLock("WEB_TOOLS_ALLOW_PRIVATE_URLS")
    fun `Runner rejects initial and redirected private URLs even with library override`() = runBlocking {
        val old = System.getProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS")
        System.setProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS", "true")
        val sent = mutableListOf<String>()
        val http = client {
            sent += it.url.toString()
            response(it, 302, "http://127.0.0.1/private")
        }
        try {
            val tools = WebTools(http, emptyList())
            assertContains(tools.httpGet("http://127.0.0.1/initial"), "failed")
            assertTrue(sent.isEmpty())
            assertContains(tools.fetchWebpage("https://8.8.8.8/start"), "failed")
            assertEquals(listOf("https://8.8.8.8/start"), sent)
        } finally {
            http.close()
            if (old == null) System.clearProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS") else System.setProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS", old)
        }
    }

    @Test fun `download cannot write private sibling linked or bound input paths`(@TempDir temp: Path) = runBlocking {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val sibling = Files.createDirectories(family.resolve("other/agent/workspace"))
        val privateDirectory = Files.createDirectories(root.resolve(".runner-private"))
        val input = Files.createDirectories(root.resolve("inputs/uploads/document")).resolve("input.bin")
        val original = "input fixture".toByteArray()
        Files.write(input, original)
        val files = RunnerToolFileAccess(RunnerFileConfinement(root, family, "root",
            readOnlyInputs = mapOf(root.relativize(input).toString() to (original.size.toLong() to sha256(original)))), workspace)
        Files.createSymbolicLink(workspace.resolve("linked"), sibling)
        val http = client { response(it) }
        try {
            val tools = WebTools(http, emptyList(), files)
            for (path in listOf("../outside.bin", privateDirectory.resolve("new.bin").toString(), sibling.resolve("new.bin").toString(), "linked/new.bin", input.toString())) {
                assertContains(tools.downloadFile("https://8.8.8.8/file", path), "failed", message = path)
            }
            assertFalse(Files.exists(privateDirectory.resolve("new.bin")))
            assertFalse(Files.exists(sibling.resolve("new.bin")))
            assertFalse(Files.exists(workspace.parent.resolve("outside.bin")))
            assertContentEquals(original, Files.readAllBytes(input))
        } finally { http.close() }
    }

    @Test fun `downloads consume the shared attempt quota without publishing partial failures`(@TempDir temp: Path) = runBlocking {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val confinement = RunnerFileConfinement(root, family, "root", writeQuotaBytes = 10)
        val http = client { response(it) }
        try {
            val first = WebTools(http, emptyList(), RunnerToolFileAccess(confinement, workspace))
            val second = WebTools(http, emptyList(), RunnerToolFileAccess(confinement, workspace))
            assertContains(first.downloadFile("https://8.8.8.8/file", "first.bin"), "successfully")
            assertContains(second.downloadFile("https://8.8.8.8/file", "overflow.bin"), "failed")
            assertContentEquals("fixture".toByteArray(), Files.readAllBytes(workspace.resolve("first.bin")))
            assertFalse(Files.exists(workspace.resolve("overflow.bin")))
        } finally { http.close() }
    }

    @Test fun `authority revoked after response stops file publication`(@TempDir temp: Path) = runBlocking {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        var revoked = false
        val files = RunnerToolFileAccess(RunnerFileConfinement(root, family, "root", guard = {
            if (revoked) throw RunnerAuthorityLost()
        }), workspace)
        val http = client { revoked = true; response(it) }
        try {
            assertIs<CancellationException>(runCatching {
                WebTools(http, emptyList(), files).downloadFile("https://8.8.8.8/file", "result.bin")
            }.exceptionOrNull())
            assertFalse(Files.exists(workspace.resolve("result.bin")))
        } finally { http.close() }
    }

    @Test fun `HTML assets are parsed without any secondary fetch`() = runBlocking {
        val sent = mutableListOf<String>()
        val http = client {
            sent += it.url.toString()
            response(it).newBuilder().body("<html><body><script src='http://127.0.0.1/private.js'></script><img src='http://127.0.0.1/private.png'><a href='http://127.0.0.1/private'>link</a></body></html>".toResponseBody()).build()
        }
        try {
            val result = WebTools(http, emptyList()).fetchWebpage("https://8.8.8.8/page", includeLinks = true, includeImages = true)
            assertContains(result, "Webpage fetched successfully")
            assertEquals(listOf("https://8.8.8.8/page"), sent)
        } finally { http.close() }
    }
}
