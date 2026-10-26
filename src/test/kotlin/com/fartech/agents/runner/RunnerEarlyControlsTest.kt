package com.fartech.agents.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

/** S forwards lease renewals and environment refreshes from claim time; either can reach the engine before `start`. */
class RunnerEarlyControlsTest {
    private val base = Instant.parse("2026-10-13T04:00:00Z")
    private val binding = RunnerProtocolTest.binding()
    private var clock = base
    private var nanos = 1_000_000_000L
    private fun lease(id: String, serverTime: Instant) = JsonObject(RunnerProtocolTest.lease(serverTime) + ("leaseId" to JsonPrimitive(id)))
    private fun environment(checkId: String, checkedAt: Instant, binding: JsonObject = this.binding) =
        JsonObject(RunnerProtocolTest.environment(binding, checkedAt) + ("environmentCheckId" to JsonPrimitive(checkId)))
    private fun gate() = RunnerEnvironmentGate(binding, setOf("engine-http"), now = { clock }, nanoTime = { nanos })
    private fun advance(seconds: Long) { clock = clock.plusSeconds(seconds); nanos += seconds * 1_000_000_000L }
    private val startEnvironment = environment("start-check", base)

    @Test fun `a renewal before start is kept and the newer lease is installed when the gate is created`() {
        val early = RunnerEarlyControls(binding)
        early.renewed(lease("lease", base.minusSeconds(55))) // older renewal, superseded by the next one
        early.renewed(lease("lease", base))                   // newest renewal: valid until base+60s
        val target = gate()
        early.attach(target, lease("lease", base.minusSeconds(50)), startEnvironment) // start lease: valid until base+10s
        advance(20)
        target.check() // still authorized: the newest renewal, not the start lease, governs the deadline
        advance(45)
        assertFailsWith<RunnerAuthorityLost> { target.check() }
    }

    @Test fun `an older early renewal never shortens the start lease`() {
        val early = RunnerEarlyControls(binding)
        early.renewed(lease("lease", base.minusSeconds(50)))
        val target = gate()
        early.attach(target, lease("lease", base), startEnvironment)
        advance(30); target.check()
    }

    @Test fun `renewals after start go straight to the gate`() {
        val early = RunnerEarlyControls(binding); val target = gate()
        early.attach(target, lease("lease", base.minusSeconds(50)), startEnvironment)
        early.renewed(lease("lease", base))
        advance(20); target.check()
        assertFailsWith<RunnerAuthorityLost> { early.renewed(lease("other-lease", base)) }
    }

    @Test fun `an early renewal for another lease identity is refused`() {
        val early = RunnerEarlyControls(binding)
        early.renewed(lease("other-lease", base))
        assertFailsWith<RunnerAuthorityLost> { early.attach(gate(), lease("lease", base.minusSeconds(5)), startEnvironment) }
        val conflicting = RunnerEarlyControls(binding)
        conflicting.renewed(lease("lease", base))
        assertFailsWith<RunnerAuthorityLost> { conflicting.renewed(lease("other-lease", base.plusSeconds(1))) }
        assertFailsWith<RunnerProtocolException> { RunnerEarlyControls(binding).renewed(buildJsonObject { put("leaseId", "lease") }) }
    }

    @Test fun `an environment refresh before start is kept and the newer evidence is installed when the gate is created`() {
        val early = RunnerEarlyControls(binding)
        early.refreshed(environment("older-refresh", base.minusSeconds(55)), null)
        early.refreshed(environment("newest-refresh", base), "probe-frame") // valid until base+60s
        val target = gate()
        early.attach(target, lease("lease", base), environment("start-check", base.minusSeconds(50))) // valid until base+10s
        advance(20)
        target.check() // the newest refresh, not the start frame's evidence, governs the deadline
        advance(45)
        assertFailsWith<RunnerAuthorityLost> { target.check() }
    }

    @Test fun `an older early refresh never replaces the start evidence and later refreshes go to the gate`() {
        val early = RunnerEarlyControls(binding)
        early.refreshed(environment("older-refresh", base.minusSeconds(50)), null)
        val target = gate()
        early.attach(target, lease("lease", base), startEnvironment)
        advance(30); target.check()
        early.refreshed(environment("after-start", clock), null); early.renewed(lease("lease", clock))
        advance(40); target.check() // renewed through the gate by the post-start refresh
    }

    @Test fun `an early refresh for another binding decision or network profile is refused`() {
        val foreign = JsonObject(binding + ("attemptId" to JsonPrimitive("another-attempt")))
        assertFailsWith<RunnerAuthorityLost> { RunnerEarlyControls(binding).refreshed(environment("foreign", base, foreign), null) }
        val denied = JsonObject(environment("denied", base) + ("decision" to JsonPrimitive("DENY")))
        assertFails { RunnerEarlyControls(binding).refreshed(denied, null) }
        val otherProfile = JsonObject(environment("other-profile", base) + ("networkProfileHash" to JsonPrimitive("e".repeat(64))))
        val early = RunnerEarlyControls(binding); early.refreshed(otherProfile, null)
        assertFailsWith<RunnerAuthorityLost> { early.attach(gate(), lease("lease", base), environment("start-check", base.minusSeconds(5))) }
    }
}
