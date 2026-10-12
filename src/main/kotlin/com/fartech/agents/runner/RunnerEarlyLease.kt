package com.fartech.agents.runner

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * S renews the platform lease from claim time and forwards `leaseRenewed` to the engine as soon as the
 * JVM is spawned, so a renewal can arrive before `start` has created the environment gate. Such a
 * renewal is not a loss of authority: its frame binding has already been verified by RunnerIpc, so the
 * newest valid one is kept and installed when the gate is created, after the start frame's own lease
 * (or instead of it, when newer). Renewals naming another lease identity than the start lease, or than
 * each other, are refused exactly as installLease refuses them on a live gate.
 */
internal class RunnerEarlyLease {
    private var pending: JsonObject? = null
    private var gate: RunnerEnvironmentGate? = null

    @Synchronized fun renewed(lease: JsonObject) {
        gate?.let { it.installLease(lease); return }
        RunnerProtocol().validate(lease, "lease")
        val kept = pending
        if (kept != null && kept.string("leaseId") != lease.string("leaseId")) throw RunnerAuthorityLost()
        if (kept == null || newer(lease, kept)) pending = lease
    }

    /** Installs the start lease, then a newer early renewal of the same lease; later renewals go straight to [environment]. */
    @Synchronized fun attach(environment: RunnerEnvironmentGate, startLease: JsonObject) {
        environment.installLease(startLease)
        pending?.let { early ->
            if (early.string("leaseId") != startLease.string("leaseId")) throw RunnerAuthorityLost()
            if (newer(early, startLease)) environment.installLease(early)
        }
        pending = null; gate = environment
    }

    private fun newer(a: JsonObject, b: JsonObject) = Instant.parse(a.string("serverTime")) > Instant.parse(b.string("serverTime"))
}
