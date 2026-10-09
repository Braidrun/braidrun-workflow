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
    @Test fun `feedback context uses explicit per-call negotiation without claiming supervisor availability`() {
        val required = setOf(RunnerCodeControlNegotiation.FEEDBACK_CONTEXT, RunnerCodeControlNegotiation.PER_CALL)
        assertTrue(RunnerHostSession.CAPABILITIES.containsAll(required))
        assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
            RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES))
        for (missing in required) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES - missing)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, failure.reason)
        }
    }
    @Test fun `feedback context without per-call or with legacy selector fails closed`() {
        val feedback = RunnerCodeControlNegotiation.FEEDBACK_CONTEXT
        for (required in listOf(setOf(feedback), setOf(feedback, RunnerCodeControlNegotiation.LEGACY))) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_FEEDBACK_CONTEXT_REQUIRES_PER_CALL, failure.reason)
        }
        val conflict = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(feedback, RunnerCodeControlNegotiation.PER_CALL,
                RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_CONTROL_MODE_CONFLICT, conflict.reason)
        val unknown = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL, "control.unknown.v1"), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, unknown.reason)
    }

    @Test fun `source read policy requires available explicit per-call and composes with feedback context`() {
        val required = setOf(RunnerCodeControlNegotiation.SOURCE_READ_POLICY, RunnerCodeControlNegotiation.PER_CALL)
        assertTrue(RunnerHostSession.CAPABILITIES.containsAll(required))
        for (selection in listOf(required, required + RunnerCodeControlNegotiation.FEEDBACK_CONTEXT)) {
            assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
                RunnerCodeControlNegotiation.select(selection, RunnerHostSession.CAPABILITIES))
        }
        for (missing in required) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES - missing)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, failure.reason)
        }
    }
    @Test fun `source read policy refuses absent dependency legacy conflict and unknown capabilities`() {
        val source = RunnerCodeControlNegotiation.SOURCE_READ_POLICY
        for (selection in listOf(setOf(source), setOf(source, RunnerCodeControlNegotiation.LEGACY))) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(selection, RunnerHostSession.CAPABILITIES)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_SOURCE_READ_POLICY_REQUIRES_PER_CALL, failure.reason)
        }
        val conflict = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(source, RunnerCodeControlNegotiation.PER_CALL,
                RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_CONTROL_MODE_CONFLICT, conflict.reason)
        val unknown = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(source, RunnerCodeControlNegotiation.PER_CALL,
                "control.sourceReadPolicy.v2"), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, unknown.reason)
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN,
            RunnerCodeControlNegotiation.select(emptySet(), RunnerHostSession.CAPABILITIES))
    }

}
