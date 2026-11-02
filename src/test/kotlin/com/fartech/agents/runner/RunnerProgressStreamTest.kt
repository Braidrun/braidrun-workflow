package com.fartech.agents.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class RunnerProgressStreamTest {
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
