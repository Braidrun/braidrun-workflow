package com.fartech.agents.runner

import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RunnerBytePipeTest {
    @Test fun `chunk order partial reads and EOF retain all bytes`() {
        val pipe = RunnerBytePipe(8); pipe.offer("abcd".toByteArray()); pipe.offer("ef".toByteArray()); pipe.eof()
        assertEquals("abcdef", pipe.input.readBytes().toString(Charsets.UTF_8)); assertEquals(-1, pipe.input.read())
        assertFailsWith<IOException> { pipe.offer(byteArrayOf(1)) }
    }
    @Test fun `backpressure never discards bytes as a successful stream`() {
        val pipe = RunnerBytePipe(4); pipe.offer("four".toByteArray())
        assertFailsWith<IOException> { pipe.offer(byteArrayOf(1)) }; assertFailsWith<IOException> { pipe.input.read() }
    }
    @Test fun `cancellation wakes a blocked reader without waiting on logger or uploader`() {
        val pipe = RunnerBytePipe(); val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<Boolean> { try { pipe.input.read(); false } catch (_: IOException) { true } }
            pipe.fail(); assertTrue(waiting.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
