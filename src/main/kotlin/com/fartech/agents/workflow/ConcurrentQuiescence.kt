package com.fartech.agents.workflow

/**
 * Which steps a concurrent workflow body has launched and not yet collected, and which of them only wait for a
 * human decision. A savepoint of a concurrent run is consistent only at quiescence: every launched step waits in its
 * approval (or none is running). A waiting step mutates nothing until it leaves the wait through [resumed], which
 * takes this monitor, and [ifQuiescent] builds the savepoint under the same monitor; so the captured context is one
 * no step is changing. Steps that already recorded partial results (retry, repeat_until, iterate_over) never count
 * as waiting, so they keep the run non-quiescent until they are collected.
 */
internal class ConcurrentQuiescence {
    private val running = HashSet<String>()
    private val waiting = LinkedHashMap<String, ApprovalRequest>()

    @Synchronized fun launched(step: String) { running.add(step) }

    @Synchronized fun finished(step: String) { running.remove(step); waiting.remove(step) }

    /** Registers [step] as waiting only when it is one of the launched steps and not already waiting. */
    @Synchronized fun waiting(step: String, request: ApprovalRequest): Boolean {
        if (step !in running || step in waiting) return false
        waiting[step] = request
        return true
    }

    /** Must run before a waiting step touches the context again (decision, rejection, timeout or cancellation). */
    @Synchronized fun resumed(step: String) { waiting.remove(step) }

    /** Runs [build] with the waiting requests, under this monitor, only while every launched step waits. */
    @Synchronized fun <T> ifQuiescent(build: (List<ApprovalRequest>) -> T): T? =
        if (running.all { it in waiting }) build(waiting.values.toList()) else null
}
