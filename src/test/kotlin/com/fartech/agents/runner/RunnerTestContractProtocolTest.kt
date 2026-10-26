package com.fartech.agents.runner

import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerTestContractProtocolTest {
    @Test fun `test contract requires per call transport and the exact companion bytes`() {
        val base = setOf(RunnerCodeControlNegotiation.PER_CALL)
        assertEquals(setOf(RunnerTestContractProtocol.CAPABILITY), RunnerTestContractProtocol.capabilities(base))
        assertEquals(emptySet(), RunnerTestContractProtocol.capabilities(emptySet()))
        for (name in listOf("test-contract-control.schema.json", "test-contract-control-origin.txt"))
            for (replacement in listOf(null, "changed".toByteArray()))
                assertEquals(emptySet(), RunnerTestContractProtocol.capabilities(base) {
                    if (it == name) replacement else javaClass.getResourceAsStream("/runner-workflow-v1/$it")?.use { stream -> stream.readBytes() }
                })
        assertTrue(RunnerTestContractProtocol.CAPABILITY in RunnerHostSession.CAPABILITIES)
    }
    @Test fun `test contract cannot select a legacy callback or invent provider support`() {
        val cap = RunnerTestContractProtocol.CAPABILITY
        val perCall = RunnerCodeControlNegotiation.PER_CALL
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(setOf(cap), setOf(cap, perCall)) }
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(setOf(cap, perCall), setOf(perCall)) }
        assertEquals(com.fartech.agents.workflow.WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
            RunnerCodeControlNegotiation.select(setOf(cap, perCall), setOf(cap, perCall)))
    }
}
