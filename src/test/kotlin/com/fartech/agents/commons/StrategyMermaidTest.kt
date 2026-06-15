package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tier-2 (2026-04) — smoke-test the Mermaid diagram helper.
 *
 * Koog's renderer is heavily tested upstream; we verify only that our
 * wrapper produces a plausibly-shaped `stateDiagram` string end-to-end
 * against the default strategy the agent runtime picks for a minimal
 * parameter bag. Anything more detailed tests Koog, not us.
 */
class StrategyMermaidTest {

    @Test
    fun `default strategy renders a non-empty mermaid state diagram`() {
        val params = listOf(
            ConfigurationParameter("strategy", JsonPrimitive("default")),
            ConfigurationParameter("session_id", JsonPrimitive("mermaid-smoke")),
        )
        val diagram = StrategyMermaid.forDefaultStrategy(HttpAccess(), params)
        assertTrue(diagram.isNotBlank(), "expected non-blank mermaid output, got: '$diagram'")
        assertTrue(
            diagram.contains("stateDiagram"),
            "expected stateDiagram block in output; got first 200 chars: ${diagram.take(200)}"
        )
    }
}
