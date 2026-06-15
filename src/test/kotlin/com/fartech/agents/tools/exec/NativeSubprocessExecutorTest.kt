package com.fartech.agents.tools.exec

import com.fartech.agents.tools.exec.SubprocessExecutor.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NativeSubprocessExecutorTest {

    private val executor = NativeSubprocessExecutor()

    @Test
    fun `echo returns exit 0 and stdout`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "echo hello"),
                workingDir = File("."),
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.trim() == "hello")
        assertTrue(result.stderr.isBlank())
    }

    @Test
    fun `stderr is captured separately`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "echo err >&2"),
                workingDir = File("."),
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.isBlank())
        assertTrue(result.stderr.trim() == "err")
    }

    @Test
    fun `combinedOutput merges stdout and stderr`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "echo out; echo err >&2"),
                workingDir = File("."),
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.combinedOutput.contains("out"))
        assertTrue(result.combinedOutput.contains("err"))
    }

    @Test
    fun `non-zero exit code is reported`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "exit 42"),
                workingDir = File("."),
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `timeout kills the process`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "sleep 60"),
                workingDir = File("."),
                timeoutSeconds = 1,
                userId = "test-user"
            )
        )
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("TIMED OUT"))
        assertTrue(result.durationMs < 5000, "Should abort quickly, not wait 60s")
    }

    @Test
    fun `working directory is respected`(@TempDir tempDir: File) = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "pwd"),
                workingDir = tempDir,
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertEquals(0, result.exitCode)
        // canonicalPath resolves symlinks (e.g. /tmp → /private/tmp on macOS)
        assertEquals(tempDir.canonicalPath, result.stdout.trim())
    }

    @Test
    fun `environment variables are injected`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "echo \$MY_VAR"),
                workingDir = File("."),
                timeoutSeconds = 10,
                env = mapOf("MY_VAR" to "test_value_123"),
                userId = "test-user"
            )
        )
        assertEquals(0, result.exitCode)
        assertEquals("test_value_123", result.stdout.trim())
    }

    @Test
    fun `stdin is piped to the process`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "cat"),
                workingDir = File("."),
                stdin = "hello from stdin",
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello from stdin", result.stdout.trim())
    }

    @Test
    fun `durationMs is positive`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "echo fast"),
                workingDir = File("."),
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertTrue(result.durationMs > 0)
    }

    @Test
    fun `invalid command returns error in stderr`() = runBlocking {
        val result = executor.execute(
            ExecRequest(
                command = listOf("/nonexistent/binary"),
                workingDir = File("."),
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("Error") || result.combinedOutput.contains("Error"))
    }

    @Test
    fun `file written in workingDir is visible`(@TempDir tempDir: File) = runBlocking {
        executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "echo content > testfile.txt"),
                workingDir = tempDir,
                timeoutSeconds = 10,
                userId = "test-user"
            )
        )
        val content = File(tempDir, "testfile.txt").readText().trim()
        assertEquals("content", content)
    }

    @Test
    fun `oversized stdin is rejected before spawn`() = runBlocking {
        val oversized = "x".repeat(SubprocessExecutor.MAX_STDIN_BYTES + 1)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                executor.execute(
                    ExecRequest(
                        command = listOf("/bin/cat"),
                        workingDir = File("."),
                        stdin = oversized,
                        timeoutSeconds = 5,
                        userId = "test-user"
                    )
                )
            }
        }
        assertTrue(ex.message?.contains("MAX_STDIN_BYTES") == true)
    }

    @Test
    fun `huge stdout is truncated with marker`() = runBlocking {
        // Emit ~16 MiB of zeros — comfortably above MAX_STREAM_BYTES (8 MiB).
        // `tr` is portable across linux/darwin and won't allocate a huge buffer
        // in the child the way `printf` would.
        val result = executor.execute(
            ExecRequest(
                command = listOf("/bin/sh", "-c", "head -c 16777216 /dev/zero | tr '\\0' 'x'"),
                workingDir = File("."),
                timeoutSeconds = 30,
                userId = "test-user"
            )
        )
        assertEquals(0, result.exitCode)
        assertTrue(
            result.stdout.endsWith(SubprocessExecutor.STREAM_TRUNCATION_MARKER),
            "expected truncation marker, stdout ended with: '${result.stdout.takeLast(80)}'"
        )
        // ≤ MAX + marker
        assertTrue(
            result.stdout.length <= SubprocessExecutor.MAX_STREAM_BYTES + SubprocessExecutor.STREAM_TRUNCATION_MARKER.length + 64,
            "stdout grew past the cap: length=${result.stdout.length}"
        )
    }
}
