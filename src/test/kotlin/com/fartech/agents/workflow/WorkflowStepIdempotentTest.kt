package com.fartech.agents.workflow

import com.charleskorn.kaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `idempotent` is host-facing metadata: the engine never branches on it, but a
 * host that offers crash recovery or rolling-deploy handoff needs to read it
 * off a step that came from a template YAML. Hosts parse templates with kaml's
 * strict configuration, so the field has to exist on the model — an unknown
 * key there is a hard parse failure, not a warning.
 */
class WorkflowStepIdempotentTest {

    private val strictYaml = Yaml.default

    private val yaml = """
        name: idempotent-demo
        description: Steps declare whether replaying them is safe
        agents: {}
        workflow:
          - step: recover_notification
            code:
              language: python
              script: |
                print("status=recovered")
            idempotent: true
          - step: charge_customer
            code:
              language: python
              script: |
                print("charged=1")
    """.trimIndent()

    @Test
    fun `strict parsing accepts an idempotent step attribute`() {
        val def = strictYaml.decodeFromString(WorkflowDefinition.serializer(), yaml)
        val steps = def.workflow.associateBy { it.step }
        assertTrue(steps.getValue("recover_notification").idempotent)
    }

    @Test
    fun `steps are not replay safe unless they say so`() {
        val def = strictYaml.decodeFromString(WorkflowDefinition.serializer(), yaml)
        val steps = def.workflow.associateBy { it.step }
        assertFalse(steps.getValue("charge_customer").idempotent)
    }

    @Test
    fun `the workflow parser keeps the flag`() {
        val def = WorkflowParser.parseYaml(yaml)
        assertEquals(
            listOf("recover_notification"),
            def.workflow.filter { it.idempotent }.map { it.step }
        )
    }

    @Test
    fun `serializing a parsed workflow round trips the flag`() {
        val def = WorkflowParser.parseYaml(yaml)
        val reparsed = WorkflowParser.parseYaml(WorkflowParser.toYaml(def))
        assertEquals(
            def.workflow.map { it.step to it.idempotent },
            reparsed.workflow.map { it.step to it.idempotent }
        )
    }
}
