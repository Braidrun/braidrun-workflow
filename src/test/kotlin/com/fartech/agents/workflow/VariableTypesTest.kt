package com.fartech.agents.workflow

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableTypesTest {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false
        )
    )

    @Test
    fun `WorkflowDefinition parses variable_types from YAML`() {
        val yamlContent = """
            name: test-workflow
            version: "1.0.0"
            agents:
              test_agent:
                preset: universal
            workflow:
            - step: step1
              agent: test_agent
              input: "test"
            variables:
              invoice_text: "demo invoice content"
              output_format: markdown
            variable_types:
              invoice_text: file_or_text
              output_format: string
        """.trimIndent()

        val workflow = yaml.decodeFromString(WorkflowDefinition.serializer(), yamlContent)

        assertEquals(2, workflow.variableTypes.size)
        assertEquals("file_or_text", workflow.variableTypes["invoice_text"])
        assertEquals("string", workflow.variableTypes["output_format"])
    }

    @Test
    fun `WorkflowDefinition defaults empty variable_types`() {
        val yamlContent = """
            name: test-workflow
            version: "1.0.0"
            agents:
              test_agent:
                preset: universal
            workflow:
            - step: step1
              agent: test_agent
              input: "test"
            variables:
              topic: "AI"
        """.trimIndent()

        val workflow = yaml.decodeFromString(WorkflowDefinition.serializer(), yamlContent)

        assertTrue(workflow.variableTypes.isEmpty())
        assertEquals("AI", workflow.variables["topic"])
    }

    @Test
    fun `variable_types field is optional and does not break existing YAML`() {
        val yamlContent = """
            name: minimal
            version: "1.0.0"
            agents:
              a:
                preset: universal
            workflow:
            - step: s1
              agent: a
              input: "hello"
        """.trimIndent()

        val workflow = yaml.decodeFromString(WorkflowDefinition.serializer(), yamlContent)

        assertTrue(workflow.variables.isEmpty())
        assertTrue(workflow.variableTypes.isEmpty())
    }

    @Test
    fun `variable_types with file type parsed correctly`() {
        val yamlContent = """
            name: file-test
            version: "1.0.0"
            agents:
              a:
                preset: universal
            workflow:
            - step: s1
              agent: a
              input: "{{var:document}}"
            variables:
              document: "sample document text"
            variable_types:
              document: file
        """.trimIndent()

        val workflow = yaml.decodeFromString(WorkflowDefinition.serializer(), yamlContent)

        assertEquals("file", workflow.variableTypes["document"])
        assertEquals("sample document text", workflow.variables["document"])
    }

    @Test
    fun `serialization round-trip preserves variable_types`() {
        val yamlContent = """
            name: round-trip
            version: "1.0.0"
            agents:
              a:
                preset: universal
            workflow:
            - step: s1
              agent: a
              input: "test"
            variables:
              source: "hello"
            variable_types:
              source: file_or_text
        """.trimIndent()

        val workflow = yaml.decodeFromString(WorkflowDefinition.serializer(), yamlContent)
        val serialized = yaml.encodeToString(WorkflowDefinition.serializer(), workflow)
        val reparsed = yaml.decodeFromString(WorkflowDefinition.serializer(), serialized)

        assertEquals(workflow.variableTypes, reparsed.variableTypes)
        assertEquals(workflow.variables, reparsed.variables)
    }
}
