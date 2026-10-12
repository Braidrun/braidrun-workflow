package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerAdmissionSecretsTest {
    private fun inspect(key: String, value: JsonElement) {
        val agent = AgentDefinition(overrides = mapOf(key to value))
        val workflow = WorkflowDefinition("secret-policy", agents = mapOf("agent" to agent), workflow = listOf(WorkflowStep("step", agent = "agent", input = "Synthetic")))
        RunnerAdmission(NullWorkflowResolver, setOf("workflow.full.v1", "step.agent.v1", "model.http.v1", "service.mcp_servers.v1", "mcp.stdio.v1")).inspect(workflow)
    }
    private fun mcp(field: String, entries: Map<String, String>) = buildJsonObject {
        put("search", buildJsonObject { put("command", "search-mcp"); put(field, JsonObject(entries.mapValues { JsonPrimitive(it.value) })) })
    }
    @Test fun `secret field aliases are recognised regardless of case hyphens or underscores`() {
        for (key in listOf("secret", "apiKey", "accessToken", "x-api-key", "api-key", "private_key", "client_key", "clientSecret",
                "passwd", "cookie", "bearer", "Proxy-Authorization", "AWS_SECRET_ACCESS_KEY", "service_credentials")) {
            assertFailsWith<IllegalArgumentException>(key) { inspect(key, JsonPrimitive("sk-raw")) }
            inspect(key, JsonPrimitive("credential:first@1"))
        }
        // Model sizing that merely ends in "token" is not a credential.
        inspect("max_token", JsonPrimitive(128000)); inspect("maxToken", JsonPrimitive(128000))
        assertFailsWith<IllegalArgumentException> { inspect("max_token", JsonPrimitive("sk-raw")) }
    }
    @Test fun `whole-map MCP process injection carries only leased references or explicit nonsecret settings`() {
        // An arbitrary name hides a raw secret from any name list.
        assertFailsWith<IllegalArgumentException> { inspect("mcp_servers", mcp("env", mapOf("TAVILY_KEY" to "tvly-raw"))) }
        assertFailsWith<IllegalArgumentException> { inspect("mcp_servers", mcp("env", mapOf("SEARCH_CONFIG" to "anything"))) }
        assertFailsWith<IllegalArgumentException> { inspect("mcp_servers", mcp("headers", mapOf("x-api-key" to "sk-raw"))) }
        assertFailsWith<IllegalArgumentException> { inspect("mcp_servers", mcp("headers", mapOf("X-Custom" to "raw"))) }
        inspect("mcp_servers", mcp("env", mapOf("TAVILY_KEY" to "credential:tavily@1", "NODE_ENV" to "production", "LOG_LEVEL" to "info", "PATH" to "/usr/bin:/bin")))
        // Stdio MCP has no headers today; the rule still covers them for any future transport.
        RunnerAdmission.requireLeasedProcessInputs(mcp("headers", mapOf("Authorization" to "credential:search@2", "Content-Type" to "application/json")).getValue("search"))
        // The host repeats the same rule before it leases and launches.
        assertFailsWith<IllegalArgumentException> { RunnerAdmission.requireLeasedProcessInputs(mcp("env", mapOf("OPENAI_KEY" to "sk-raw")).getValue("search")) }
        RunnerAdmission.requireLeasedProcessInputs(mcp("env", mapOf("OPENAI_KEY" to "credential:openai@1")).getValue("search"))
    }
    private fun server(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf("command" to JsonPrimitive("search-mcp")) + fields)
    private fun args(vararg values: String) = "args" to JsonArray(values.map(::JsonPrimitive))
    @Test fun `MCP argv cannot carry a secret-named flag value or a literal token`() {
        for (rejected in listOf(args("--api-key", "tvly-synthetic"), args("--token=abc"), args("--api-key", "credential:search@1"),
                args("-y", "search-mcp", "--auth-token", "opaque"), args("--key", "opaque"), args("serve", "sk-synthetic"))) {
            assertFailsWith<IllegalArgumentException>(rejected.toString()) { RunnerAdmission.requireLeasedProcessInputs(server(rejected)) }
            assertFailsWith<IllegalArgumentException> { inspect("mcp_servers", buildJsonObject { put("search", server(rejected)) }) }
        }
        assertFailsWith<IllegalArgumentException> { RunnerAdmission.requireLeasedProcessInputs(JsonObject(mapOf("command" to JsonPrimitive("ghp_synthetic")))) }
        // Allowlisted nonsecret settings still may not hold a literal token.
        assertFailsWith<IllegalArgumentException> { RunnerAdmission.requireLeasedProcessInputs(mcp("env", mapOf("DEBUG" to "tvly-synthetic")).getValue("search")) }
        for (accepted in listOf(args("-y", "search-mcp"), args("--api-key-env", "SEARCH_KEY"), args("--verbose", "--port", "8080"), args("--token"))) {
            RunnerAdmission.requireLeasedProcessInputs(server(accepted))
        }
        RunnerAdmission.requireLeasedProcessInputs(mcp("env", mapOf("DEBUG" to "1", "SEARCH_KEY" to "credential:search@1")).getValue("search"))
        inspect("mcp_servers", buildJsonObject { put("search", server(args("-y", "search-mcp"), "env" to buildJsonObject { put("SEARCH_KEY", "credential:search@1") })) })
    }
    @Test fun `startup errors are summarised by class name only`() {
        val failure = ExceptionInInitializerError(IllegalArgumentException("https://example.invalid?key=synthetic-key"))
        val rendered = runnerFailureSummary(failure)
        assertTrue(rendered.contains("ExceptionInInitializerError")); assertTrue(rendered.contains("IllegalArgumentException"))
        assertFalse(rendered.contains("synthetic")); assertFalse(rendered.contains("example.invalid"))
        val error = runnerFailureSummary(OutOfMemoryError("https://example.invalid?key=synthetic-key"))
        assertTrue(error.contains("OutOfMemoryError")); assertFalse(error.contains("synthetic"))
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
