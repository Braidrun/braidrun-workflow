package com.fartech.agents.runner

import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArraySet

/** One terminal fence shared by host capabilities and their retained adapter/IPC references. */
class RunnerBusinessFence {
    @Volatile private var closed = false
    private val active = CopyOnWriteArraySet<Job>()
    fun check() { if (closed) throw RunnerAuthorityLost() }
    /** Only bounded local admission/enqueue is inside this lock, never remote reply or process waits. */
    @Synchronized fun <T> admit(action: () -> T): T { check(); return action() }
    suspend fun <T> operation(action: suspend () -> T): T = coroutineScope {
        val owner = currentCoroutineContext()[Job]
        admit { owner?.let(active::add) }
        try { currentCoroutineContext().ensureActive(); check(); action().also { check() } }
        finally { owner?.let(active::remove) }
    }
    fun close() {
        synchronized(this) { closed = true }
        active.forEach { it.cancel(RunnerAuthorityLost()) }; active.clear()
    }
    fun isClosed() = closed
}
