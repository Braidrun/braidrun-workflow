package com.fartech.agents.commons

import com.fartech.agents.workflow.WorkflowHostPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SingleLlmChoiceHostPolicyTest {

    @AfterEach
    fun resetPolicy() {
        WorkflowHostPolicy.resetForTests()
    }

    @Test
    fun `num_choices passes through without the host latch`() {
        assertEquals(3, resolveNumberOfChoices(3))
        assertEquals(1, resolveNumberOfChoices(1))
    }

    @Test
    fun `host latch caps user-configured num_choices at one`() {
        WorkflowHostPolicy.requireSingleLlmChoice()

        assertEquals(1, resolveNumberOfChoices(3))
        assertEquals(1, resolveNumberOfChoices(1))
    }
}
