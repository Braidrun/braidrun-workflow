package com.fartech.agents.tools.exec

import com.fartech.agents.tools.ShellTools
import com.github.dockerjava.api.DockerClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy

/**
 * Tests that [ShellTools] correctly delegates to [NativeSubprocessExecutor]
 * and preserves the existing output format contract.
 */
class ShellToolsExecutorTest {

    private val shellTools = ShellTools(NativeSubprocessExecutor(), "test-user")

    @Test
    fun `executeShellCmd returns output and exit code`() = runBlocking {
        val result = shellTools.executeShellCmd("echo hello")
        assertTrue(result.contains("hello"))
        assertTrue(result.contains("exit code is: 0"))
    }

    @Test
    fun `executeShellCmd reports non-zero exit code`() = runBlocking {
        val result = shellTools.executeShellCmd("exit 1")
        assertTrue(result.contains("exit code is: 1"))
    }

    @Test
    fun `executeShellCmd respects working directory`(@TempDir tempDir: File) = runBlocking {
        val result = shellTools.executeShellCmd("pwd", workingDirectory = tempDir.canonicalPath)
        assertTrue(result.contains(tempDir.canonicalPath))
    }

    @Test
    fun `executeShellCmd injects environment variables`() = runBlocking {
        val result = shellTools.executeShellCmd("echo \$FOO", environment = "FOO=bar123")
        assertTrue(result.contains("bar123"))
    }

    @Test
    fun `executeShellCmd reports invalid working directory`() = runBlocking {
        val result = shellTools.executeShellCmd("echo hello", workingDirectory = "/nonexistent/dir")
        assertTrue(result.contains("does not exist"))
        assertTrue(result.contains("exit code is: -1"))
    }

    @Test
    fun `executeShellScript runs multi-line script`(@TempDir tempDir: File) = runBlocking {
        val script = """
            echo line1
            echo line2
        """.trimIndent()
        val result = shellTools.executeShellScript(script, workingDirectory = tempDir.canonicalPath)
        assertTrue(result.contains("line1"))
        assertTrue(result.contains("line2"))
    }

    @Test
    fun `executeShellScript defaults to context workspace when working directory omitted`(@TempDir tempDir: File) = runBlocking {
        val tools = ShellTools(
            executor = NativeSubprocessExecutor(),
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = tempDir)
        )

        val result = tools.executeShellScript("pwd")

        assertTrue(result.contains(tempDir.canonicalPath))
    }

    @Test
    fun `default constructor uses NativeSubprocessExecutor`() = runBlocking {
        // Default constructor should work without Docker
        val defaultTools = ShellTools()
        val result = defaultTools.executeShellCmd("echo default")
        assertTrue(result.contains("default"))
        assertTrue(result.contains("exit code is: 0"))
    }

    @Test
    fun `truncateOutput preserves short output`() {
        val short = "hello"
        assertEquals(short, ShellTools.truncateOutput(short))
    }

    @Test
    fun `truncateOutput truncates long output`() {
        val long = "x".repeat(200_000)
        val truncated = ShellTools.truncateOutput(long)
        assertTrue(truncated.length < long.length)
        assertTrue(truncated.contains("OUTPUT TRUNCATED"))
    }

    @Test
    fun `docker context exposes runtime env vars and mounts`(@TempDir tempDir: File) {
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }
        val outputDir = File(tempDir, "output").apply { mkdirs() }
        val skillsDir = File(tempDir, "skills").apply { mkdirs() }
        val dockerExecutor = DockerSubprocessExecutor(dummyDockerClient())
        val tools = ShellTools(
            executor = dockerExecutor,
            userId = "user-42",
            context = SubprocessToolContext(
                workspaceDir = workspaceDir,
                outputDir = outputDir,
                skillsDir = skillsDir,
                executionId = "exec-1",
                stepName = "plan",
                sessionId = "session-1"
            )
        )

        val envMethod = ShellTools::class.java.getDeclaredMethod("buildExecutorEnvironment", File::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val env = envMethod.invoke(tools, workspaceDir) as Map<String, String>

        assertEquals("/workspace", env["BRAIDRUN_WORKSPACE"])
        assertEquals("/output", env["BRAIDRUN_OUTPUT_DIR"])
        assertEquals("/skills", env["BRAIDRUN_SKILLS_DIR"])
        assertEquals("user-42", env["BRAIDRUN_USER_ID"])
        assertEquals("exec-1", env["BRAIDRUN_EXECUTION_ID"])
        assertEquals("plan", env["BRAIDRUN_STEP_NAME"])
        assertEquals("session-1", env["BRAIDRUN_SESSION_ID"])

        val mountsMethod = ShellTools::class.java.getDeclaredMethod("buildExecutorMounts", File::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mounts = mountsMethod.invoke(tools, workspaceDir) as List<SubprocessExecutor.Mount>

        assertTrue(mounts.any { it.containerPath == "/output" && !it.readOnly && it.hostPath.canonicalFile == outputDir.canonicalFile })
        assertTrue(mounts.any { it.containerPath == "/skills" && it.readOnly && it.hostPath.canonicalFile == skillsDir.canonicalFile })
    }

    private fun dummyDockerClient(): DockerClient {
        return Proxy.newProxyInstance(
            DockerClient::class.java.classLoader,
            arrayOf(DockerClient::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "close" -> null
                "toString" -> "DummyDockerClient"
                else -> throw UnsupportedOperationException("Not used in this test: ${method.name}")
            }
        } as DockerClient
    }
}
