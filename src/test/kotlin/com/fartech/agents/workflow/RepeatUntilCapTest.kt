package com.fartech.agents.workflow

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Phase 11 regression — RepeatUntilConfig must reject `max_iterations` above the hard cap
 * so a workflow YAML can't park a step in a multi-hour loop.
 */
class RepeatUntilCapTest {

    @Test
    fun `oversized max_iterations is rejected`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            RepeatUntilConfig(
                condition = "x == 1",
                maxIterations = RepeatUntilConfig.MAX_REPEAT_UNTIL_ITERATIONS + 1
            )
        }
        assertTrue(ex.message!!.contains("exceeds hard cap"), "Got: ${ex.message}")
    }

    @Test
    fun `boundary value is accepted`() {
        val config = RepeatUntilConfig(
            condition = "x == 1",
            maxIterations = RepeatUntilConfig.MAX_REPEAT_UNTIL_ITERATIONS
        )
        assertEquals(RepeatUntilConfig.MAX_REPEAT_UNTIL_ITERATIONS, config.maxIterations)
    }

    @Test
    fun `default value is well below cap`() {
        val config = RepeatUntilConfig(condition = "x == 1")
        assertTrue(config.maxIterations < RepeatUntilConfig.MAX_REPEAT_UNTIL_ITERATIONS)
    }
}
