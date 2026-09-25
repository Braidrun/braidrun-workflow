package com.fartech.agents.cli

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertContains
import kotlin.test.assertEquals

class BraidrunWorkflowCliJevTest {
    @TempDir
    lateinit var tempDir: File

    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        return try {
            block() to buffer.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun `dry-run labels jev classifiers and validate accepts a jev-only workflow`() {
        val file = File(tempDir, "jev.yaml").apply {
            writeText(
                """
                name: jev-cli
                agents:
                  clf:
                    type: universal_agent
                    llm: {model: gpt-4, provider: openai}
                workflow:
                  - step: triage
                    classifier:
                      input: "{{var:text}}"
                      categories:
                        - {name: a, description: "A"}
                        - {name: b, description: "B"}
                      jev: {}
                  - step: legacy
                    depends_on: [triage]
                    classifier:
                      agent: clf
                      input: "{{var:text}}"
                      categories:
                        - {name: a, description: "A"}
                        - {name: b, description: "B"}
                      output_variable: legacy_route
                """.trimIndent()
            )
        }

        val (planCode, plan) = captureStdout { runBlocking { BraidrunWorkflowCli().run(listOf("dry-run", file.absolutePath)) } }
        assertEquals(0, planCode)
        assertContains(plan, "1. triage -> classifier(jev)")
        assertContains(plan, "2. legacy -> classifier depends_on=triage")

        val (validateCode, validate) = captureStdout { runBlocking { BraidrunWorkflowCli().run(listOf("validate", file.absolutePath)) } }
        assertEquals(0, validateCode)
        assertContains(validate, "OK jev-cli (2 step(s), 1 agent(s))")
    }
}
