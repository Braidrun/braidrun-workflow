package com.fartech.agents.tools.exec

import com.fartech.agents.tools.CodeExecutionTools
import com.github.dockerjava.api.DockerClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy

/**
 * Tests for the refactored [CodeExecutionTools] with [SubprocessExecutor] delegation.
 */
class CodeExecutionToolsTest {

    private val tools = CodeExecutionTools.withPackageInstall(NativeSubprocessExecutor(), "test-user")
    private val readOnlyTools = CodeExecutionTools.readOnly(NativeSubprocessExecutor(), "test-user")

    private class CapturingExecutor : SubprocessExecutor {
        var lastRequest: SubprocessExecutor.ExecRequest? = null

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            lastRequest = request
            return SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                durationMs = 0
            )
        }
    }

    companion object {
        @JvmStatic
        fun pythonAvailable(): Boolean = runCatching {
            val cmd = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
            ProcessBuilder(cmd, "--version").start().waitFor() == 0
        }.getOrDefault(false)

        @JvmStatic
        fun nodeAvailable(): Boolean = runCatching {
            ProcessBuilder("node", "--version").start().waitFor() == 0
        }.getOrDefault(false)
    }

    @Test
    @EnabledIf("pythonAvailable")
    fun `executePython runs code and returns output`() = runBlocking {
        val result = tools.executePython("print('hello from python')")
        assertTrue(result.contains("hello from python"))
        assertTrue(result.contains("Exit code: 0"))
    }

    @Test
    @EnabledIf("pythonAvailable")
    fun `executePython reports syntax error`() = runBlocking {
        val result = tools.executePython("def broken(")
        assertTrue(result.contains("SyntaxError") || result.contains("Exit code:"))
        assertFalse(result.contains("Exit code: 0"))
    }

    @Test
    @EnabledIf("pythonAvailable")
    fun `executePython respects working directory`(@TempDir tempDir: File) = runBlocking {
        val result = tools.executePython(
            "import os; print(os.getcwd())",
            workingDirectory = tempDir.canonicalPath
        )
        assertTrue(result.contains(tempDir.canonicalPath))
    }

    @Test
    @EnabledIf("nodeAvailable")
    fun `executeJavaScript runs code and returns output`() = runBlocking {
        val result = tools.executeJavaScript("console.log('hello from node')")
        assertTrue(result.contains("hello from node"))
        assertTrue(result.contains("Exit code: 0"))
    }

    @Test
    @EnabledIf("nodeAvailable")
    fun `executeJavaScript reports runtime error`() = runBlocking {
        val result = tools.executeJavaScript("throw new Error('test error')")
        assertTrue(result.contains("test error"))
        assertFalse(result.contains("Exit code: 0"))
    }

    @Test
    fun `readOnly mode blocks pipInstall`() = runBlocking {
        val result = readOnlyTools.pipInstall("numpy")
        assertTrue(result.contains("disabled in sandbox mode"))
        assertTrue(result.contains("braidrun/exec-python-node"))
    }

    @Test
    fun `readOnly mode blocks npmInstall`() = runBlocking {
        val result = readOnlyTools.npmInstall("lodash")
        assertTrue(result.contains("disabled in sandbox mode"))
        assertTrue(result.contains("braidrun/exec-node"))
    }

    @Test
    fun `withPackageInstall mode allows pipInstall attempt`() = runBlocking {
        // Just verify it doesn't immediately return the "disabled" message
        val result = tools.pipInstall("")
        assertTrue(result.contains("no Python packages"))
    }

    @Test
    fun `withPackageInstall mode allows npmInstall attempt`() = runBlocking {
        val result = tools.npmInstall("")
        assertTrue(result.contains("no Node.js packages"))
    }

    @Test
    fun `factory methods create correct instances`() {
        val withPkg = CodeExecutionTools.withPackageInstall(NativeSubprocessExecutor())
        val readOnly = CodeExecutionTools.readOnly(NativeSubprocessExecutor())
        assertNotSame(withPkg, readOnly)
    }

    @Test
    fun `npmInstall defaults to context workspace when working directory omitted`(@TempDir tempDir: File) = runBlocking {
        val executor = CapturingExecutor()
        val tools = CodeExecutionTools.withPackageInstall(
            executor = executor,
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = tempDir)
        )

        val result = tools.npmInstall("lodash")

        assertTrue(result.contains("Successfully installed"))
        assertEquals(tempDir.canonicalFile, executor.lastRequest?.workingDir?.canonicalFile)
    }

    @Test
    fun `docker context exposes runtime env vars and mounts for code execution`(@TempDir tempDir: File) {
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }
        val outputDir = File(tempDir, "output").apply { mkdirs() }
        val skillsDir = File(tempDir, "skills").apply { mkdirs() }
        val tools = CodeExecutionTools.readOnly(
            executor = DockerSubprocessExecutor(dummyDockerClient()),
            userId = "user-42",
            context = SubprocessToolContext(
                workspaceDir = workspaceDir,
                outputDir = outputDir,
                skillsDir = skillsDir,
                executionId = "exec-1",
                stepName = "code-step",
                sessionId = "session-1"
            )
        )

        val envMethod = CodeExecutionTools::class.java.getDeclaredMethod("buildExecutorEnvironment", File::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val env = envMethod.invoke(tools, workspaceDir) as Map<String, String>

        assertEquals("/workspace", env["BRAIDRUN_WORKSPACE"])
        assertEquals("/output", env["BRAIDRUN_OUTPUT_DIR"])
        assertEquals("/skills", env["BRAIDRUN_SKILLS_DIR"])
        assertEquals("user-42", env["BRAIDRUN_USER_ID"])
        assertEquals("exec-1", env["BRAIDRUN_EXECUTION_ID"])
        assertEquals("code-step", env["BRAIDRUN_STEP_NAME"])
        assertEquals("session-1", env["BRAIDRUN_SESSION_ID"])

        val mountsMethod = CodeExecutionTools::class.java.getDeclaredMethod("buildExecutorMounts", File::class.java)
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
