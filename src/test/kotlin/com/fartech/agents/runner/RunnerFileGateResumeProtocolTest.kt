package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowCodeControlMode
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerFileGateResumeProtocolTest {
    @Test fun `original gate resume requires pinned capability and both dependencies`() {
        assertTrue(RunnerFileGateResumeProtocol.available())
        val required = setOf(RunnerFileGateResumeProtocol.CAPABILITY, RunnerCodeControlNegotiation.PER_CALL, RunnerCollectionProtocol.CAPABILITY)
        assertTrue(RunnerHostSession.CAPABILITIES.containsAll(required))
        assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL, RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES))
        for (missing in required) assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES - missing)
        }
        for (dependency in required - RunnerFileGateResumeProtocol.CAPABILITY) assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required - dependency, RunnerHostSession.CAPABILITIES)
        }
        assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required + RunnerCodeControlNegotiation.LEGACY, RunnerHostSession.CAPABILITIES)
        }
    }
}
