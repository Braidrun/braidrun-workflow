package com.fartech.agents.workflow

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WorkflowTemplateValidationTest {

    private fun workflowFiles(directory: String): List<File> {
        val dir = File(directory)
        assertTrue(dir.isDirectory, "$directory should exist")
        return dir.listFiles { file -> file.extension == "yaml" }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    @Test
    fun `public examples and templates parse and validate`() {
        val files = workflowFiles("examples/workflows") + workflowFiles("workflows/templates")
        assertTrue(files.isNotEmpty(), "Public workflows should not be empty")

        files.forEach { file ->
            val workflow = WorkflowParser.parseFile(file.absolutePath)
            assertDoesNotThrow {
                WorkflowParser.validateWorkflow(workflow)
            }
            assertTrue(workflow.name.isNotBlank(), "${file.name} should define a workflow name")
            assertTrue(workflow.workflow.isNotEmpty(), "${file.name} should define at least one step")
        }
    }

    @Test
    fun `hello code example stays minimal and deterministic`() {
        val workflow = WorkflowParser.parseFile("examples/workflows/hello-code.yaml")

        assertEquals("hello-code", workflow.name)
        assertTrue(workflow.agents.isEmpty())
        assertEquals(1, workflow.workflow.size)

        val step = workflow.workflow.single()
        assertEquals("hello", step.step)
        assertEquals("bash", step.code?.language)
        assertTrue(step.code?.script?.contains("Hello from Braidrun Workflow") == true)
    }

    @Test
    fun `codex delegation example uses external agent tooling`() {
        val workflow = WorkflowParser.parseFile("examples/workflows/codex-delegation.yaml")

        val coder = workflow.agents["coder"]
        assertEquals("coder", coder?.preset)
        assertTrue(
            coder?.overrides?.keys?.contains("tool_set") == true,
            "The example should show how to override the coder preset with external_agent tooling"
        )
    }
}
