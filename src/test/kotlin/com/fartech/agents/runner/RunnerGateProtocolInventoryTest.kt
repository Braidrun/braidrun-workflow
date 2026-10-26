package com.fartech.agents.runner

import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerGateProtocolInventoryTest {
    private val base = setOf(RunnerCodeControlNegotiation.PER_CALL, RunnerArtifactSetProtocol.CAPABILITY, RunnerCollectionProtocol.CAPABILITY)
    private fun read(name: String) = javaClass.getResourceAsStream("/runner-workflow-v1/$name")?.use { it.readBytes() }

    @Test fun `both companions negotiate only with every original dependency`() {
        val expected = setOf(RunnerArtifactSetProtocol.GATES, RunnerCodeControlNegotiation.GATE_PROJECTION)
        assertEquals(expected, RunnerGateProtocolInventory.capabilities(base))
        for (missing in base) assertEquals(emptySet(), RunnerGateProtocolInventory.capabilities(base - missing))
        assertFalse(RunnerArtifactSetProtocol.SOURCE in RunnerHostSession.CAPABILITIES)
    }

    @Test fun `missing or replaced schema or origin never invents companion support`() {
        for (suffix in listOf(".schema.json", "-origin.txt")) {
            for (replacement in listOf(null, "changed".toByteArray())) {
                assertEquals(emptySet(), RunnerGateProtocolInventory.capabilities(base) { name ->
                    if (name == "gate-set-control$suffix") replacement else read(name)
                })
                assertEquals(setOf(RunnerArtifactSetProtocol.GATES), RunnerGateProtocolInventory.capabilities(base) { name ->
                    if (name == "gate-projection$suffix") replacement else read(name)
                })
            }
        }
    }
    @Test fun `projected feedback requires exact companion pin and prior feedback support`() {
        val prior = setOf(RunnerArtifactSetProtocol.GATES, RunnerCodeControlNegotiation.GATE_PROJECTION)
        val deps = base + RunnerCodeControlNegotiation.FEEDBACK_CONTEXT
        assertEquals(prior + RunnerCodeControlNegotiation.GATE_FEEDBACK, RunnerGateProtocolInventory.capabilities(deps))
        for (suffix in listOf(".schema.json", "-origin.txt")) for (replacement in listOf(null, "changed".toByteArray())) {
            assertEquals(prior, RunnerGateProtocolInventory.capabilities(deps) { name ->
                if (name == "gate-feedback-context$suffix") replacement else read(name)
            })
        }
        val required = deps + prior + RunnerCodeControlNegotiation.GATE_FEEDBACK
        for (missing in listOf(RunnerCodeControlNegotiation.FEEDBACK_CONTEXT, RunnerCodeControlNegotiation.GATE_PROJECTION)) {
            val error = assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(required - missing, required) }
            assertEquals(RunnerNegotiationReason.RUNNER_GATE_FEEDBACK_DEPENDENCY_REQUIRED, error.reason)
        }
        assertEquals(com.fartech.agents.workflow.WorkflowCodeControlMode.SUPERVISOR_PER_CALL, RunnerCodeControlNegotiation.select(required, required))
    }
}
