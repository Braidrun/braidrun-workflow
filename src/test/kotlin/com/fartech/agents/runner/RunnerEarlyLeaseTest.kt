package com.fartech.agents.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

/** S forwards lease renewals from claim time; one can reach the engine before `start` created the gate. */
class RunnerEarlyLeaseTest {
    private val base = Instant.parse("2026-10-13T04:00:00Z")
    private val binding = RunnerProtocolTest.binding()
    private var clock = base
    private var nanos = 1_000_000_000L
    private fun lease(id: String, serverTime: Instant) = JsonObject(RunnerProtocolTest.lease(serverTime) + ("leaseId" to JsonPrimitive(id)))
    private fun gate() = RunnerEnvironmentGate(binding, setOf("engine-http"), now = { clock }, nanoTime = { nanos }).apply {
        install(RunnerProtocolTest.environment(binding, base))
    }
    private fun advance(seconds: Long) { clock = clock.plusSeconds(seconds); nanos += seconds * 1_000_000_000L }

    @Test fun `a renewal before start is kept and the newer lease is installed when the gate is created`() {
        val early = RunnerEarlyLease()
        early.renewed(lease("lease", base.minusSeconds(55))) // older renewal, superseded by the next one
        early.renewed(lease("lease", base))                   // newest renewal: valid until base+60s
        val environment = gate()
        early.attach(environment, lease("lease", base.minusSeconds(50))) // start frame's lease: valid until base+10s
        advance(20)
        environment.check() // still authorized: the newest renewal, not the start lease, governs the deadline
        advance(45)
        assertFailsWith<RunnerAuthorityLost> { environment.check() }
    }

    @Test fun `an older early renewal never shortens the start lease`() {
        val early = RunnerEarlyLease()
        early.renewed(lease("lease", base.minusSeconds(50)))
        val environment = gate()
        early.attach(environment, lease("lease", base))
        advance(30); environment.check()
    }

    @Test fun `renewals after start go straight to the gate`() {
        val early = RunnerEarlyLease(); val environment = gate()
        early.attach(environment, lease("lease", base.minusSeconds(50)))
        early.renewed(lease("lease", base))
        advance(20); environment.check()
        assertFailsWith<RunnerAuthorityLost> { early.renewed(lease("other-lease", base)) }
    }

    @Test fun `an early renewal for another lease identity is refused`() {
        val early = RunnerEarlyLease()
        early.renewed(lease("other-lease", base))
        assertFailsWith<RunnerAuthorityLost> { early.attach(gate(), lease("lease", base.minusSeconds(5))) }
        val conflicting = RunnerEarlyLease()
        conflicting.renewed(lease("lease", base))
        assertFailsWith<RunnerAuthorityLost> { conflicting.renewed(lease("other-lease", base.plusSeconds(1))) }
        assertFailsWith<RunnerProtocolException> { RunnerEarlyLease().renewed(buildJsonObject { put("leaseId", "lease") }) }
    }
}
