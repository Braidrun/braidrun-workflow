package com.fartech.agents.runner

import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerBusinessBackendProtocolTest {
    @Test fun `backend requires pinned schema and per call transport`() {
        val cap = RunnerBusinessBackendProtocol.CAPABILITY
        val perCall = RunnerCodeControlNegotiation.PER_CALL
        assertEquals(setOf(cap), RunnerBusinessBackendProtocol.capabilities(setOf(perCall)))
        assertEquals(emptySet(), RunnerBusinessBackendProtocol.capabilities(emptySet()))
        for (bytes in listOf(null, "changed".toByteArray()))
            assertEquals(emptySet(), RunnerBusinessBackendProtocol.capabilities(setOf(perCall)) { bytes })
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(setOf(cap), setOf(cap)) }
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(setOf(cap, perCall), setOf(perCall)) }
        assertEquals(com.fartech.agents.workflow.WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
            RunnerCodeControlNegotiation.select(setOf(cap, perCall), setOf(cap, perCall)))
    }
    @Test fun `owner inputs require feedback provenance and composite recovery requires original proof capabilities`() {
        val perCall = RunnerCodeControlNegotiation.PER_CALL
        val inputs = "control.ownerAttachments.v1"
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(setOf(inputs, perCall), setOf(inputs, perCall)) }
        val valid = setOf(inputs, perCall, RunnerCodeControlNegotiation.FEEDBACK_CONTEXT)
        assertEquals(com.fartech.agents.workflow.WorkflowCodeControlMode.SUPERVISOR_PER_CALL, RunnerCodeControlNegotiation.select(valid, valid))
        val incomplete = setOf(perCall, RunnerFileGateResumeProtocol.COMPOSITE)
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(incomplete, incomplete) }
    }
}
