package com.fartech.agents.runner

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * S renews the platform lease from claim time and refreshes environment evidence on its own schedule, and
 * forwards both (`leaseRenewed`, `environmentRefreshed`) as soon as the engine JVM is spawned. Either can
 * therefore arrive before `start` has created the environment gate. That is not a loss of authority: the
 * frame binding has already been verified by RunnerIpc, so the newest valid copy of each is kept and
 * installed when the gate is created, after the start frame's own lease/environment and only when newer.
 * Identity rules are those of the live gate: a lease naming another lease id, or evidence for another
 * binding, a non-ALLOW decision or another network profile, is refused.
 */
internal class RunnerEarlyControls(private val binding: JsonObject) {
    private class Refresh(val evidence: JsonObject, val replyTo: String?)
    private var lease: JsonObject? = null
    private var environment: Refresh? = null
    private var gate: RunnerEnvironmentGate? = null

    @Synchronized fun renewed(value: JsonObject) {
        gate?.let { it.installLease(value); return }
        RunnerProtocol().validate(value, "lease")
        val kept = lease
        if (kept != null && kept.string("leaseId") != value.string("leaseId")) throw RunnerAuthorityLost()
        if (kept == null || newer(value, kept, "serverTime")) lease = value
    }

    @Synchronized fun refreshed(value: JsonObject, replyTo: String?) {
        gate?.let { it.install(value, replyTo); return }
        RunnerProtocol().validate(value, "environment")
        if (value.obj("binding") != binding || value.string("decision") != "ALLOW") throw RunnerAuthorityLost()
        val kept = environment
        if (kept != null && kept.evidence.string("networkProfileHash") != value.string("networkProfileHash")) throw RunnerAuthorityLost()
        if (kept == null || newer(value, kept.evidence, "checkedAt")) environment = Refresh(value, replyTo)
    }

    /** Installs the start frame's lease and environment, then a newer early copy of each; later frames go straight to [target]. */
    @Synchronized fun attach(target: RunnerEnvironmentGate, startLease: JsonObject, startEnvironment: JsonObject) {
        target.installLease(startLease)
        lease?.let { early ->
            if (early.string("leaseId") != startLease.string("leaseId")) throw RunnerAuthorityLost()
            if (newer(early, startLease, "serverTime")) target.installLease(early)
        }
        target.install(startEnvironment)
        environment?.let { early ->
            if (early.evidence.string("networkProfileHash") != startEnvironment.string("networkProfileHash")) throw RunnerAuthorityLost()
            if (newer(early.evidence, startEnvironment, "checkedAt")) target.install(early.evidence, early.replyTo)
        }
        lease = null; environment = null; gate = target
    }

    private fun newer(a: JsonObject, b: JsonObject, field: String) = Instant.parse(a.string(field)) > Instant.parse(b.string(field))
}
