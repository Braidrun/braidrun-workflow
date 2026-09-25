package com.fartech.agents.tools.exec

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ResourceWaitBudgetTest {
    @Test fun `waiting longer than timeout does not rerun or expire the body`() = runBlocking {
        var calls = 0
        val result = withResourceAwareTimeout(200) {
            calls++
            resourceAdmissionWait { delay(400) }
            delay(10)
            "done"
        }
        assertEquals("done", result)
        assertEquals(1, calls)
    }
    @Test fun `active time still expires and joins body cleanup`() = runBlocking {
        var cleaned = false
        assertFailsWith<TimeoutCancellationException> {
            withResourceAwareTimeout(50) {
                try { delay(5000) } finally { cleaned = true }
            }
        }
        assertTrue(cleaned)
    }
    @Test fun `external cancellation is not suppressed by resource waiting`() = runBlocking {
        var cleaned = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            try { withResourceAwareTimeout(50) { resourceAdmissionWait { started.complete(Unit); awaitCancellation() } } }
            finally { cleaned = true }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(cleaned)
    }
    @Test fun `overlapping waits use union duration`() {
        var nanos = 0L
        val clock = ResourceWaitClock { nanos }
        clock.begin()
        nanos = 10_000_000; clock.begin()
        nanos = 20_000_000; clock.end()
        nanos = 30_000_000; clock.end()
        assertEquals(30, clock.waitedMillis())
    }
    @Test fun `nested scopes pause ancestor deadlines too`() = runBlocking {
        val parent = ResourceWaitClock()
        val child = ResourceWaitClock()
        withResourceWaitClock(parent) { withResourceWaitClock(child) { resourceAdmissionWait { delay(100) } } }
        assertTrue(parent.waitedMillis() >= 90)
        assertTrue(child.waitedMillis() >= 90)
    }
    @Test fun `native completion confirms reservation settlement before returning`() = runBlocking {
        var settled = false
        val result = NativeSubprocessExecutor().execute(SubprocessExecutor.ExecRequest(
            command = listOf("/bin/sh", "-c", "exit 0"), workingDir = java.io.File("."),
            onSettled = { settled = true }
        ))
        assertEquals(0, result.exitCode)
        assertTrue(settled)
    }
    @Test fun `native cancellation stops waiting promptly and confirms root termination`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var settled = false
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            NativeSubprocessExecutor().execute(SubprocessExecutor.ExecRequest(
                command = listOf("/bin/sh", "-c", "echo started; exec sleep 30"), workingDir = java.io.File("."),
                stdoutLineCallback = { started.complete(Unit) }, onSettled = { settled = true }
            ))
        }
        withTimeout(3000) { started.await() }
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(settled)
    }

}
