package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowCodeControlMode
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerCodeControlNegotiationTest {
    @Test fun `ready per-call capability is backed by exact committed HTTP supplement`() {
        assertTrue(RunnerCodeControlNegotiation.supplementAvailable())
        assertTrue(RunnerCodeControlNegotiation.PER_CALL in RunnerHostSession.CAPABILITIES)
        val origin = javaClass.getResourceAsStream("/runner-workflow-v1/late-control-origin.txt")!!.bufferedReader().readLines().first()
        assertEquals(RunnerCodeControlNegotiation.CONTRACT_COMMIT, origin)
        assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
            RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL), RunnerHostSession.CAPABILITIES))
    }
    @Test fun `missing altered supplement and absent engine capability are not accepted`() {
        assertFalse(RunnerCodeControlNegotiation.supplementAvailable(null))
        assertFalse(RunnerCodeControlNegotiation.supplementAvailable("{}".toByteArray()))
        assertFailsWith<IllegalArgumentException> { RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL), emptySet()) }
    }
    @Test fun `missing selector never implicitly enables per-call mode and mutually exclusive selectors refuse`() {
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN, RunnerCodeControlNegotiation.select(setOf("step.script.v1"), RunnerHostSession.CAPABILITIES))
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN, RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES))
        assertFailsWith<IllegalArgumentException> {
            RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL, RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES)
        }
    }
}
