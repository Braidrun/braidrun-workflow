package com.fartech.agents.commons

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class SubprocessSafetyTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `runCapturedWithTimeout returns exit code and output for a fast command`() {
        val r = SubprocessSafety.runCapturedWithTimeout(
            command = listOf("/bin/sh", "-c", "echo hello"),
            timeoutSeconds = 5
        )
        assertFalse(r.timedOut)
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("hello"), "Expected output to contain 'hello', got: ${r.output}")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `runCapturedWithTimeout kills a hanging process and reports timeout`() {
        val r = SubprocessSafety.runCapturedWithTimeout(
            command = listOf("/bin/sh", "-c", "sleep 30"),
            timeoutSeconds = 1
        )
        assertTrue(r.timedOut, "Expected timed out, got exitCode=${r.exitCode}")
        assertNull(r.exitCode)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `runCapturedWithTimeout drains chatty output without hanging`() {
        // Generate enough bytes to fill the OS pipe buffer multiple times over (>1 MB)
        // — without a background drainer, the subprocess would block on `write` and
        // `waitFor(timeout)` would fire instead of completing cleanly.
        val r = SubprocessSafety.runCapturedWithTimeout(
            command = listOf("/bin/sh", "-c", "yes | head -c 2000000"),
            timeoutSeconds = 10,
            maxOutputBytes = 512 * 1024
        )
        assertFalse(r.timedOut, "Expected normal exit, but timed out")
        assertEquals(0, r.exitCode)
        // Output is capped at maxOutputBytes + truncation marker
        assertTrue(r.output.length <= 512 * 1024 + 200)
        assertTrue(r.output.contains("[truncated"), "Expected truncation marker, got: ${r.output.take(80)}")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `runProbe returns true for a successful command and false for a failure`() {
        assertTrue(SubprocessSafety.runProbe(listOf("/bin/sh", "-c", "exit 0")))
        assertFalse(SubprocessSafety.runProbe(listOf("/bin/sh", "-c", "exit 1")))
        assertFalse(SubprocessSafety.runProbe(listOf("/nonexistent/binary")))
    }

    @Test
    fun `runCapturedWithTimeout rejects empty command`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubprocessSafety.runCapturedWithTimeout(command = emptyList())
        }
    }

    @Test
    fun `runCapturedWithTimeout rejects zero or negative timeout`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubprocessSafety.runCapturedWithTimeout(command = listOf("echo"), timeoutSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubprocessSafety.runCapturedWithTimeout(command = listOf("echo"), timeoutSeconds = -1)
        }
    }
}
