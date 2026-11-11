package com.fartech.agents.tools.exec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType

class AgentOutputCaptureTest {
    private val terminal = "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}"
    @Test fun `small unicode output remains exact across byte boundaries`() {
        val capture = AgentOutputCapture()
        val bytes = "中文结果\n".toByteArray()
        bytes.forEach { capture.write(byteArrayOf(it), 1) }
        assertEquals("中文结果\n", capture.text())
    }
    @Test fun `large docker agent output retains terminal result with bounded size`() {
        val capture = DockerSubprocessExecutor.LogCaptureCallback(retainAgentResult = true)
        val chunk = ("x".repeat(8191) + "\n").toByteArray()
        repeat(2048) { capture.onNext(Frame(StreamType.STDOUT, chunk)) }
        capture.onNext(Frame(StreamType.STDOUT, terminal.toByteArray()))
        val text = capture.text()
        assertTrue(text.endsWith(terminal))
        assertTrue(text.contains(SubprocessExecutor.STREAM_TRUNCATION_MARKER))
        assertTrue(text.length < SubprocessExecutor.MAX_STREAM_BYTES + 100)
    }
    @Test fun `native agent result survives more than eight MiB of progress`() = runBlocking {
        val result = NativeSubprocessExecutor().execute(SubprocessExecutor.ExecRequest(
            command = listOf("/bin/sh", "-c", "head -c 16777216 /dev/zero; printf '\n%s\n' '$terminal'"),
            workingDir = File("."), timeoutSeconds = 20, admissionKind = "AGENT_SUBPROCESS"))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.trimEnd().endsWith(terminal))
        assertTrue(result.stdout.length < SubprocessExecutor.MAX_STREAM_BYTES + 100)
    }
}
