package com.fartech.agents.tools.exec

import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Monotonic union of overlapping resource waits, never double-counts concurrent waiters. */
class ResourceWaitClock(private val nanoTime: () -> Long = System::nanoTime) {
    private var depth = 0
    private var since = 0L
    private var accumulated = 0L
    @Synchronized fun begin() { if (depth++ == 0) since = nanoTime() }
    @Synchronized fun end() {
        check(depth > 0)
        if (--depth == 0) accumulated += nanoTime() - since
    }
    @Synchronized fun waitedMillis(): Long =
        (accumulated + if (depth > 0) nanoTime() - since else 0L) / 1_000_000
}

private class ResourceWaitContext(val clocks: List<ResourceWaitClock>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResourceWaitContext>
}

suspend fun <T> withResourceWaitClock(clock: ResourceWaitClock, block: suspend () -> T): T {
    val inherited = currentCoroutineContext()[ResourceWaitContext]?.clocks.orEmpty()
    return withContext(ResourceWaitContext(inherited + clock)) { block() }
}

/** Host admission implementations wrap only the wait, never the executing process. */
suspend fun <T> resourceAdmissionWait(block: suspend () -> T): T {
    val clocks = currentCoroutineContext()[ResourceWaitContext]?.clocks.orEmpty()
    clocks.forEach { it.begin() }
    return try { block() } finally { clocks.forEach { it.end() } }
}

/** A waiting permit does not spend the execution budget. The body is started exactly once. */
suspend fun <T> withResourceAwareTimeout(timeoutMs: Long, block: suspend () -> T): T = coroutineScope {
    require(timeoutMs > 0)
    val clock = ResourceWaitClock()
    val started = System.nanoTime()
    val body = async { withResourceWaitClock(clock, block) }
    while (true) {
        if (body.isCompleted) return@coroutineScope body.await()
        val elapsed = (System.nanoTime() - started) / 1_000_000 - clock.waitedMillis()
        val remaining = timeoutMs - elapsed
        if (remaining <= 0) {
            body.cancelAndJoin()
            // Preserve the runtime's existing TimeoutCancellationException handling.
            withTimeout(1L) { awaitCancellation() }
        }
        // Timing out this waiter does not restart or cancel the sibling deferred body.
        withTimeoutOrNull(minOf(remaining, 100L)) { body.join() }
    }
    @Suppress("UNREACHABLE_CODE") error("unreachable")
}
