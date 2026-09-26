package com.fartech.agents.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val toolRunScopeLogger = KotlinLogging.logger {}

/**
 * Host-injected identity of the run a tool call serves.
 *
 * Tools that keep live state between calls (for example [BrowserTools]' Playwright contexts) key it by
 * run. Otherwise, in the shared web JVM, one run could reach another run's state by naming it. The
 * scope travels in the coroutine context, and only host code installs it with [withRunScope]:
 * `WorkflowExecutor.execute` does so for each execution, and the web assistant does so for each turn.
 * It is deliberately not a ConfigurationParameter, because those are user-controlled on the web:
 * `session_id_strategy: fixed` lets a workflow choose its own `session_id`, and no host injects
 * `execution_id` outside `WorkflowExecutor`.
 *
 * When the outermost [withRunScope] for an id returns (completed, failed or cancelled), every listener
 * registered with [onRunEnd] releases that run's state.
 *
 * A single-user host (the CLI) calls [declareSingleUserProcess]. From then on every run shares the one
 * [PROCESS] scope, state lives as long as the process, and the end of a run releases nothing.
 */
class ToolRunScope private constructor(val id: String) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ToolRunScope> {
        /** The scope every run shares after [declareSingleUserProcess]. */
        const val PROCESS = "process"

        @Volatile
        private var singleUserProcess = false

        /** Nesting depth per scope id: the same id can be entered twice (a resumed or re-entrant run). */
        private val openRuns = ConcurrentHashMap<String, Int>()
        private val runEndListeners = CopyOnWriteArrayList<(String) -> Unit>()

        /**
         * One-way. Declares that this process serves a single user: run-scoped tool state is shared
         * process-wide, as it was before run scopes existed, and run parameters may configure shared
         * resources such as the browser launch. Only the CLI calls this. A multi-tenant host must not.
         */
        fun declareSingleUserProcess() {
            singleUserProcess = true
        }

        val isSingleUserProcess: Boolean
            get() = singleUserProcess

        /**
         * Runs [block] as run [id]. The scope applies to [block] and to every coroutine it starts. An
         * inner [withRunScope] replaces it for its own block, so a nested execution is a run of its own.
         * When the last [withRunScope] for [id] exits, the [onRunEnd] listeners run, even on cancellation.
         * In a [declareSingleUserProcess] process this only runs [block].
         */
        suspend fun <T> withRunScope(id: String, block: suspend CoroutineScope.() -> T): T {
            require(id.isNotBlank()) { "run scope id must not be blank" }
            if (singleUserProcess) return coroutineScope(block)
            openRuns.merge(id, 1, Int::plus)
            try {
                return withContext(ToolRunScope(id), block)
            } finally {
                val ended = openRuns.computeIfPresent(id) { _, depth -> if (depth <= 1) null else depth - 1 } == null
                if (ended) withContext(NonCancellable + Dispatchers.IO) { fireRunEnd(id) }
            }
        }

        /**
         * The scope id tool state must be keyed by: [PROCESS] in a single-user process, otherwise the
         * host-installed run id, or null when no host installed one.
         */
        suspend fun currentId(): String? =
            if (singleUserProcess) PROCESS else currentCoroutineContext()[Key]?.id

        /** Registers [listener] to release a run's state when that run ends. It receives the scope id. */
        fun onRunEnd(listener: (String) -> Unit) {
            runEndListeners += listener
        }

        private fun fireRunEnd(id: String) {
            runEndListeners.forEach { listener ->
                try {
                    listener(id)
                } catch (e: Exception) {
                    toolRunScopeLogger.warn(e) { "Releasing tool state of run '$id' failed" }
                }
            }
        }

        internal fun resetForTests() {
            singleUserProcess = false
            openRuns.clear()
        }
    }

    override fun toString(): String = "ToolRunScope($id)"
}
