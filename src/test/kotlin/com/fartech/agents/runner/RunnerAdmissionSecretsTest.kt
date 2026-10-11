package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerAdmissionSecretsTest {
    private fun inspect(key: String, value: JsonElement) {
        val agent = AgentDefinition(overrides = mapOf(key to value))
        val workflow = WorkflowDefinition("secret-policy", agents = mapOf("agent" to agent), workflow = listOf(WorkflowStep("step", agent = "agent", input = "Synthetic")))
        RunnerAdmission(NullWorkflowResolver, setOf("workflow.full.v1", "step.agent.v1", "model.http.v1")).inspect(workflow)
    }
    @Test fun `only subscription fields allow a list of delegated references`() {
        val list = JsonPrimitive("credential:first@1,credential:second@1")
        for (key in listOf("api_key", "password", "authorization", "service_token")) {
            inspect(key, JsonPrimitive("credential:first@1"))
            assertFailsWith<IllegalArgumentException> { inspect(key, list) }
        }
        for (key in listOf("external_agent_codex_auth_json", "external_agent_claude_oauth_token")) inspect(key, list)
        assertFailsWith<IllegalArgumentException> { inspect("llm_provider_keys", buildJsonObject { put("openai", list) }) }
    }
    @Test fun `startup exception messages and nested URL credentials never enter diagnostic summary`() {
        val failure = IllegalArgumentException("https://example.invalid?key=synthetic-key", IllegalStateException("synthetic-password"))
        val rendered = runnerFailureSummary(failure)
        assertTrue(rendered.contains("IllegalArgumentException")); assertTrue(rendered.contains("IllegalStateException"))
        assertFalse(rendered.contains("synthetic")); assertFalse(rendered.contains("example.invalid"))
    }
}
