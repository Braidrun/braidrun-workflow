package com.fartech.agents.runner

import com.fartech.agents.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files as NioFiles
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlin.test.*

class RunnerSandboxProcessesTest {
    private class Files : ToolFileAccess {
        val data = mutableMapOf<String, ByteArray>()
        val written = mutableListOf<String>()
        var failure: RuntimeException? = null
        override fun readBytes(path: String, maxBytes: Int): ByteArray { failure?.let { throw it }; return data.getValue(path) }
        override fun writeBytes(path: String, bytes: ByteArray) { failure?.let { throw it }; written += path; data[path] = bytes }
    }
    private fun snapshot(path: String, bytes: ByteArray) = buildJsonObject {
        put("path", path); put("contentBase64", Base64.getEncoder().encodeToString(bytes))
        put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
    private fun result(payload: JsonObject, outputs: List<JsonObject> = emptyList(), cleanup: String = "STOP_CONFIRMED", exitCode: Int = 0) = buildJsonObject {
        put("requestId", payload.string("requestId")); put("cleanup", cleanup); put("exitCode", exitCode)
        put("stdout", "ok"); put("stderr", ""); put("outputs", JsonArray(outputs))
    }
    @Test fun `typed request snapshots through file access and publishes bounded bytes`(): Unit = runBlocking {
        val files = Files().apply { data["in.txt"] = "input".toByteArray() }
        val process = RunnerSandboxProcesses(files, "step", "root__step__call_1", setOf("shell"), {}) { payload, timeout ->
            assertEquals("NONE", payload.string("network")); assertEquals(JsonPrimitive(false), payload["interactive"])
            assertFalse("environment" in payload || "argv" in payload || "relativeCwd" in payload)
            assertEquals(150_000L, timeout); assertEquals(snapshot("in.txt", "input".toByteArray()), payload.array("inputs").single())
            result(payload, listOf(snapshot("out.txt", "output".toByteArray())))
        }
        process.execute(SandboxToolRequest("shell", "shell", "cp in.txt out.txt", inputFiles = listOf("in.txt"), outputFiles = listOf("out.txt")))
        assertEquals(listOf("out.txt"), files.written); assertEquals("output", files.data.getValue("out.txt").toString(Charsets.UTF_8))
    }
    @Test fun `undeclared group remote git invalid path and excessive bytes never send`(): Unit = runBlocking {
        val files = Files().apply { data["large"] = ByteArray(128 * 1024 + 1) }
        var sent = false
        val process = RunnerSandboxProcesses(files, "step", "root__step__call_1", setOf("shell", "git"), {}) { p, _ -> sent = true; result(p) }
        val requests = listOf(SandboxToolRequest("code_execution", "python"), SandboxToolRequest("git", "git", arguments = listOf("push")),
            SandboxToolRequest("shell", "shell", inputFiles = listOf("../token")), SandboxToolRequest("shell", "shell", inputFiles = listOf("large")),
            SandboxToolRequest("shell", "shell", "界".repeat(30000)))
        for (request in requests) assertFailsWith<IllegalArgumentException> { process.execute(request) }
        assertFalse(sent); assertTrue(files.written.isEmpty())
    }
    @Test fun `unconfirmed cleanup missing output and forged extra outputs never publish`(): Unit = runBlocking {
        for (mode in 0..3) {
            val files = Files()
            val process = RunnerSandboxProcesses(files, "step", "root__step__call_1", setOf("shell"), {}) { payload, _ ->
                when (mode) {
                    0 -> result(payload, listOf(snapshot("out", byteArrayOf(1))), cleanup = "STOP_UNCONFIRMED")
                    1 -> result(payload)
                    2 -> result(payload, listOf(snapshot("out", byteArrayOf(1)), snapshot("../host", byteArrayOf(2))))
                    else -> result(payload, listOf(snapshot("out", byteArrayOf(1))), exitCode = 1)
                }
            }
            assertFailsWith<IllegalArgumentException> { process.execute(SandboxToolRequest("shell", "shell", outputFiles = listOf("out"))) }
            assertTrue(files.written.isEmpty())
        }
    }
    @Test fun `cancellation after exchange propagates and prevents file persistence`(): Unit = runBlocking {
        val files = Files(); var cancelled = false
        val process = RunnerSandboxProcesses(files, "step", "root__step__call_1", setOf("shell"), { if (cancelled) throw CancellationException("cancelled") }) { payload, _ ->
            cancelled = true; result(payload, listOf(snapshot("out", byteArrayOf(1))))
        }
        assertFailsWith<CancellationException> { process.execute(SandboxToolRequest("shell", "shell", outputFiles = listOf("out"))) }
        assertTrue(files.written.isEmpty())
    }
    @Test fun `confinement failure propagates without invoking exchange`(): Unit = runBlocking {
        val failure = SecurityException("linked path"); val files = Files().apply { this.failure = failure }
        var sent = false
        val process = RunnerSandboxProcesses(files, "step", "root__step__call_1", setOf("shell"), {}) { payload, _ -> sent = true; result(payload) }
        assertSame(failure, assertFailsWith<SecurityException> { process.execute(SandboxToolRequest("shell", "shell", inputFiles = listOf("linked"))) })
        assertFalse(sent)
    }
    @Test fun `tools use typed executor and refuse remote git without a native fallback`(): Unit = runBlocking {
        val calls = mutableListOf<SandboxToolRequest>(); val executor = SandboxToolExecutor { calls += it; "ok" }
        SandboxShellTools(executor).executeShellScript("echo done")
        SandboxCodeExecutionTools(executor).executePython("print(1)")
        SandboxCodeExecutionTools(executor).executeJavaScript("console.log(1)")
        SandboxGitTools(executor).executeLocalGit("status")
        assertEquals(listOf("shell", "python", "javascript", "git"), calls.map { it.runtime })
        assertFailsWith<IllegalArgumentException> { SandboxGitTools(executor).executeLocalGit("fetch") }
        assertEquals(4, calls.size)
    }
    @Test fun `actual symbolic and hard links cannot enter sandbox snapshots`(@TempDir temp: Path): Unit = runBlocking {
        val root = temp.toRealPath(); val family = root.resolve(".workflow-runs/owner")
        val workspace = NioFiles.createDirectories(family.resolve("root/agent/workspace"))
        val secret = NioFiles.writeString(NioFiles.createDirectories(root.resolve(".runner-private")).resolve("token"), "outside")
        NioFiles.createSymbolicLink(workspace.resolve("linked"), secret); NioFiles.createLink(workspace.resolve("hard"), secret)
        val files = RunnerToolFileAccess(RunnerFileConfinement(root, family, "root"), workspace)
        var sent = false
        val process = RunnerSandboxProcesses(files, "step", "root__step__call_1", setOf("shell"), {}) { p, _ -> sent = true; result(p) }
        for (name in listOf("linked", "hard")) assertFailsWith<SecurityException> { process.execute(SandboxToolRequest("shell", "shell", inputFiles = listOf(name))) }
        assertFalse(sent)
    }
    @Test fun `actual output parent escape and quota preserve existing bytes`(@TempDir temp: Path): Unit = runBlocking {
        val root = temp.toRealPath(); val family = root.resolve(".workflow-runs/owner")
        val workspace = NioFiles.createDirectories(family.resolve("root/agent/workspace"))
        val private = NioFiles.createDirectories(root.resolve(".runner-private"))
        NioFiles.createSymbolicLink(workspace.resolve("escape"), private)
        val original = NioFiles.writeString(workspace.resolve("old"), "old content")
        val confinement = RunnerFileConfinement(root, family, "root", writeQuotaBytes = 1)
        val files = RunnerToolFileAccess(confinement, workspace)
        for (name in listOf("escape/new", "old")) {
            val process = RunnerSandboxProcesses(files, "step", "root__step__call_1", setOf("shell"), {}) { payload, _ -> result(payload, listOf(snapshot(name, "new content".toByteArray()))) }
            assertFailsWith<SecurityException> { process.execute(SandboxToolRequest("shell", "shell", outputFiles = listOf(name))) }
        }
        assertFalse(NioFiles.exists(private.resolve("new"))); assertEquals("old content", NioFiles.readString(original)); assertEquals(0L, confinement.bytesWritten())
    }
    @Test fun `pinned supplement validates direction and rejects unexpected sandbox authority`() {
        val frame = buildJsonObject {
            put("protocolVersion", 1); put("frameId", "id"); put("binding", RunnerProtocolTest.binding()); put("type", "sandboxProcessLaunch")
            put("payload", buildJsonObject {
                put("requestId", "request"); put("stepName", "step"); put("childExecutionPath", RunnerProtocolTest.binding().string("executionId"))
                put("toolGroup", "shell"); put("runtime", "shell"); put("source", "true"); put("arguments", JsonArray(emptyList()))
                put("inputs", JsonArray(emptyList())); put("outputs", JsonArray(emptyList())); put("network", "NONE"); put("interactive", false); put("timeoutSeconds", 120)
            })
        }
        RunnerSandboxProtocol.validate(frame, true)
        assertFailsWith<IllegalArgumentException> { RunnerSandboxProtocol.validate(frame, false) }
        for (change in listOf("network" to JsonPrimitive("EGRESS"), "interactive" to JsonPrimitive(true), "environment" to JsonObject(emptyMap()))) {
            val modified = JsonObject(frame + ("payload" to JsonObject(frame.obj("payload") + change)))
            assertFailsWith<IllegalArgumentException> { RunnerSandboxProtocol.validate(modified, true) }
        }
    }
}
