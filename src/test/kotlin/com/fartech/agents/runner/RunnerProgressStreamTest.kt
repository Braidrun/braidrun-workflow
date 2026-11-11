package com.fartech.agents.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import com.fartech.agents.tools.exec.SubprocessExecutor
import org.junit.jupiter.api.assertThrows

class RunnerProgressStreamTest {
    @Test
    fun `verbose runner agent drains beyond the capture budget and retains the terminal result`() {
        // DayFlow 2026-11-11: many individually valid screenshot/tool events exceeded the aggregate 8 MiB cap.
        val event = "{\"type\":\"assistant\",\"text\":\"" + "x".repeat(8192) + "\"}\n"
        val count = SubprocessExecutor.MAX_STREAM_BYTES / event.length + 100
        val terminal = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\"}"
        val input = ByteArrayInputStream((event.repeat(count) + terminal).toByteArray())
        var callbacks = 0
        var last = ""
        val output = captureProgressStream(input) { callbacks++; last = it }
        assertEquals(count + 1, callbacks)
        assertEquals(terminal, last)
        assertEquals(-1, input.read(), "the child pipe must be fully drained")
        assertTrue(output.startsWith(event))
        assertTrue(output.endsWith(terminal))
        assertTrue(output.contains(SubprocessExecutor.STREAM_TRUNCATION_MARKER))
        assertTrue(output.toByteArray().size < SubprocessExecutor.MAX_STREAM_BYTES + 256)
    }

    @Test
    fun `non agent stream keeps its existing hard limit`() {
        assertThrows<IllegalArgumentException> {
            captureProgressStream(ByteArrayInputStream(ByteArray(SubprocessExecutor.MAX_STREAM_BYTES + 1)), null)
        }
    }

    @Test
    fun `a progress line above the event budget is kept as a marker instead of failing the call`() {
        // Production 2026-11-02 (PantryWise 5c066528): Codex put one command's whole output into a single JSON line.
        val huge = "{\"type\":\"item.completed\",\"item\":{\"aggregated_output\":\"" + "x".repeat(PROGRESS_LINE_LIMIT_BYTES + 10) + "\"}}"
        val input = "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}\n$huge\n{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"done\"}}"
        val seen = mutableListOf<String>()
        val output = captureProgressStream(ByteArrayInputStream(input.toByteArray()), seen::add)
        assertEquals(listOf("{\"type\":\"thread.started\",\"thread_id\":\"t1\"}",
            "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"done\"}}"), seen)
        val lines = output.split('\n')
        assertEquals(3, lines.size)
        assertEquals("[progress line of ${huge.toByteArray().size} bytes omitted]", lines[1])
        assertTrue(lines[2].contains("agent_message"), "the final event after the omitted line is kept")
    }

    @Test
    fun `lines within the budget and output without a callback are kept byte for byte`() {
        val input = "a\r\nb\n\nlast"
        val seen = mutableListOf<String>()
        assertEquals(input, captureProgressStream(ByteArrayInputStream(input.toByteArray()), seen::add))
        assertEquals(listOf("a\r", "b", "", "last"), seen)
        val big = "y".repeat(PROGRESS_LINE_LIMIT_BYTES + 1)
        assertEquals(big, captureProgressStream(ByteArrayInputStream(big.toByteArray()), null))
    }
}
