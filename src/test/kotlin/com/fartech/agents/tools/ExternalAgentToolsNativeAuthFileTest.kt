package com.fartech.agents.tools

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecResult
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses fake credentials and a fake executor; never starts Codex or calls a model. */
class ExternalAgentToolsNativeAuthFileTest {
    @TempDir
    lateinit var directory: Path

    private val original = """{"tokens":{"access_token":"fake-original-secret","refresh_token":"fake-refresh"}}"""
    private val rotated = """{"tokens":{"access_token":"fake-rotated-secret","refresh_token":"fake-refresh-2"}}"""
    private val newerLogin = """{"tokens":{"access_token":"fake-new-login","refresh_token":"fake-refresh-3"}}"""

    private fun source(content: String = original): Path = directory.resolve("auth.json").also {
        Files.writeString(it, content)
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------"))
    }

    private class FakeExecutor(val action: (ExecRequest) -> Unit = {}) : SubprocessExecutor {
        var request: ExecRequest? = null
        override suspend fun execute(request: ExecRequest): ExecResult {
            this.request = request
            action(request)
            return ExecResult(0, """{"result":"done"}""", "", 1)
        }
    }

    private fun tools(
        source: Path,
        executor: FakeExecutor,
        docker: Boolean = false,
        extraParameters: Map<String, String> = emptyMap(),
        events: MutableList<String> = mutableListOf(),
        workspace: Path = directory.resolve("workspace"),
    ): ExternalAgentTools {
        Files.createDirectories(workspace)
        val params = mapOf(
            "external_agent_codex_auth_mode" to "subscription",
            "external_agent_codex_model" to "gpt-6-astra",
            ExternalAgentTools.CODEX_AUTH_FILE_PARAMETER to source.toString(),
        ) + extraParameters
        return ExternalAgentTools(
            executor = executor,
            parameters = params.map { (key, value) -> ConfigurationParameter(key, JsonPrimitive(value)) },
            userId = "native-auth-file-test",
            context = SubprocessToolContext(
                workspaceDir = workspace.toFile(),
                outputDir = directory.resolve("output").toFile(),
                executionId = "test-execution",
                stepName = "generate",
            ),
            onMonitorEvent = { type, summary, detail -> events += "$type $summary $detail" },
            trustExecutorSandbox = docker,
        )
    }

    private fun run(tools: ExternalAgentTools): String = runBlocking {
        tools.runCodexSubAgent(ExternalAgentContext(prompt = "Generate the requested app", name = "developer"))
    }

    @Test
    fun `native auth file stays out of argv events and artifacts and isolated home is cleaned`() {
        val source = source()
        val events = mutableListOf<String>()
        val executor = FakeExecutor { request ->
            val home = Path.of(request.env.getValue("CODEX_HOME"))
            assertNotEquals(source.parent, home)
            assertEquals(original, Files.readString(home.resolve("auth.json")))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(home.resolve("auth.json")))
        }
        assertEquals("done", run(tools(source, executor, events = events)))
        val request = executor.request!!
        assertTrue(request.command.contains("--ignore-user-config"))
        assertTrue(request.command.contains("gpt-6-astra"))
        assertFalse(request.command.joinToString(" ").contains("fake-original-secret"))
        assertFalse(request.stdin.orEmpty().contains("fake-original-secret"))
        assertFalse(request.env.values.any { it.contains("fake-original-secret") })
        assertFalse(events.joinToString().contains("fake-original-secret"))
        assertFalse(File(request.env.getValue("CODEX_HOME")).exists())
        assertEquals(original, Files.readString(source))
        assertFalse(Files.exists(directory.resolve("output/auth.json")))
    }

    @Test
    fun `refreshed auth is atomically persisted with private permissions and temporary files removed`() {
        val source = source()
        val executor = FakeExecutor { request ->
            File(request.env.getValue("CODEX_HOME"), "auth.json").writeText(rotated)
        }
        assertEquals("done", run(tools(source, executor)))
        assertEquals(rotated, Files.readString(source))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(source))
        Files.list(directory).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
        assertFalse(File(executor.request!!.env.getValue("CODEX_HOME")).exists())
    }

    @Test
    fun `refresh compare and swap never overwrites a changed source`() {
        val source = source()
        val executor = FakeExecutor { request ->
            File(request.env.getValue("CODEX_HOME"), "auth.json").writeText(rotated)
            Files.writeString(source, newerLogin)
        }
        assertEquals("done", run(tools(source, executor)))
        assertEquals(newerLogin, Files.readString(source))
    }

    @Test
    fun `concurrent runs use separate homes and only one refresh wins the original baseline`() {
        val source = source()
        val entered = CountDownLatch(2)
        val firstExecutor = FakeExecutor { request ->
            File(request.env.getValue("CODEX_HOME"), "auth.json").writeText(rotated)
            entered.countDown()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
        }
        val secondExecutor = FakeExecutor { request ->
            File(request.env.getValue("CODEX_HOME"), "auth.json").writeText(newerLogin)
            entered.countDown()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<String> { run(tools(source, firstExecutor)) }
            val second = workers.submit<String> { run(tools(source, secondExecutor)) }
            assertEquals("done", first.get(15, TimeUnit.SECONDS))
            assertEquals("done", second.get(15, TimeUnit.SECONDS))
            assertTrue(Files.readString(source) in setOf(rotated, newerLogin))
            val firstHome = firstExecutor.request!!.env.getValue("CODEX_HOME")
            val secondHome = secondExecutor.request!!.env.getValue("CODEX_HOME")
            assertNotEquals(firstHome, secondHome)
            assertFalse(File(firstHome).exists())
            assertFalse(File(secondHome).exists())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `invalid refreshed auth cannot replace the source`() {
        val source = source()
        val executor = FakeExecutor { request ->
            File(request.env.getValue("CODEX_HOME"), "auth.json").writeText("not-json fake-sensitive-value")
        }
        assertEquals("done", run(tools(source, executor)))
        assertEquals(original, Files.readString(source))
    }

    @Test
    fun `missing file fails before executing codex`() {
        val executor = FakeExecutor()
        assertThrows(IllegalArgumentException::class.java) { run(tools(directory.resolve("missing.json"), executor)) }
        assertNull(executor.request)
    }

    @Test
    fun `malformed credential errors contain no credential content`() {
        val source = source("invalid-json fake-sensitive-value")
        val executor = FakeExecutor()
        val events = mutableListOf<String>()
        val error = assertThrows(IllegalArgumentException::class.java) { run(tools(source, executor, events = events)) }
        assertFalse(error.message.orEmpty().contains("fake-sensitive-value"))
        assertFalse(events.joinToString().contains("fake-sensitive-value"))
        assertNull(executor.request)
    }

    @Test
    fun `docker cannot read a native authentication file`() {
        val executor = FakeExecutor()
        val error = assertThrows(IllegalArgumentException::class.java) { run(tools(source(), executor, docker = true)) }
        assertTrue(error.message.orEmpty().contains("only in native mode"))
        assertNull(executor.request)
    }

    @Test
    fun `native authentication file requires subscription mode`() {
        val executor = FakeExecutor()
        assertThrows(IllegalArgumentException::class.java) {
            run(tools(source(), executor, extraParameters = mapOf("external_agent_codex_auth_mode" to "api_key")))
        }
        assertNull(executor.request)
    }

    @Test
    fun `non private or symlink authentication files are rejected`() {
        val source = source()
        val executor = FakeExecutor()
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-r--r--"))
        assertThrows(IllegalArgumentException::class.java) { run(tools(source, executor)) }
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"))
        val link = directory.resolve("auth-link.json")
        Files.createSymbolicLink(link, source)
        assertThrows(IllegalArgumentException::class.java) { run(tools(link, executor)) }
        assertNull(executor.request)
    }

    @Test
    fun `authentication files inside the workspace and persistent homes are rejected`() {
        val source = source()
        val executor = FakeExecutor()
        assertThrows(IllegalArgumentException::class.java) { run(tools(source, executor, workspace = directory)) }
        assertThrows(IllegalArgumentException::class.java) {
            run(tools(source, executor, extraParameters = mapOf("external_agent_codex_home_dir" to directory.resolve("persistent-home").toString())))
        }
        assertNull(executor.request)
    }
}
