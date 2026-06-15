package com.fartech.agents.workflow

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowParserTest {

    @Test
    fun `test parse valid workflow YAML`() {
        val yaml = """
            name: test-workflow
            version: 1.0.0
            description: Test workflow
            agents:
              test_agent:
                type: universal_agent
                strategy: just_work
                tools:
                  - exit
                  - shell
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: step1
                agent: test_agent
                input: "Test input"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)

        assertEquals("test-workflow", workflow.name)
        assertEquals("1.0.0", workflow.version)
        assertEquals("Test workflow", workflow.description)
        assertEquals(1, workflow.agents.size)
        assertEquals(1, workflow.workflow.size)
    }

    @Test
    fun `test validation fails for undefined agent reference`() {
        val yaml = """
            name: invalid-workflow
            version: 1.0.0
            agents:
              agent1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: step1
                agent: undefined_agent
                input: "Test"
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `test circular dependency detection`() {
        val yaml = """
            name: circular-workflow
            version: 1.0.0
            agents:
              agent1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: step1
                agent: agent1
                input: "Test"
                depends_on:
                  - step2
              - step: step2
                agent: agent1
                input: "Test"
                depends_on:
                  - step1
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `test topological sort`() {
        val yaml = """
            name: ordered-workflow
            version: 1.0.0
            agents:
              agent1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: step3
                agent: agent1
                input: "Test"
                depends_on:
                  - step1
                  - step2
              - step: step1
                agent: agent1
                input: "Test"
              - step: step2
                agent: agent1
                input: "Test"
                depends_on:
                  - step1
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val order = WorkflowParser.getTopologicalOrder(workflow)

        assertEquals(3, order.size)
        assertEquals("step1", order[0].step)
        assertEquals("step2", order[1].step)
        assertEquals("step3", order[2].step)
    }

    @Test
    fun `test workflow summary generation`() {
        val yaml = """
            name: summary-test
            version: 1.0.0
            agents:
              agent1:
                type: universal_agent
                strategy: just_work
                tools: [exit, shell]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: step1
                agent: agent1
                input: "Test input"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val summary = WorkflowParser.getWorkflowSummary(workflow)

        assertTrue(summary.contains("summary-test"))
        assertTrue(summary.contains("agent1"))
        assertTrue(summary.contains("step1"))
    }
}
