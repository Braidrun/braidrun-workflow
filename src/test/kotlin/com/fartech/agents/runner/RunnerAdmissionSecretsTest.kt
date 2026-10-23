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
        val token = "tvly-SYNTHETIC0123456789abcdef"
        for (rejected in listOf(args("--api-key", "opaque-raw"), args("--token=abc"), args("--api-key", "credential:search@1"),
                args("-y", "search-mcp", "--auth-token", "opaque"), args("--key", "opaque"), args("serve", "sk-synthetic0123456789abcd"),
                // Shapes a README commonly teaches (R3-4).
                args("-H", "Authorization: Bearer opaque-raw-123"), args("--header=Authorization: Bearer opaque-raw-123"),
                args("TAVILY_API_KEY=opaque-raw", "tavily-mcp"), args("-c", "TAVILY_API_KEY=opaque-raw tavily-mcp"),
                args("--auth", "user:ghp_SYNTHETIC0123456789abcdef"), args("-H", "X-Api-Key: opaque-raw"))) {
            assertFailsWith<IllegalArgumentException>(rejected.toString()) { RunnerAdmission.requireLeasedProcessInputs(server(rejected)) }
            assertFailsWith<IllegalArgumentException> { inspect("mcp_servers", buildJsonObject { put("search", server(rejected)) }) }
        }
        assertFailsWith<IllegalArgumentException> { RunnerAdmission.requireLeasedProcessInputs(JsonObject(mapOf("command" to JsonPrimitive("ghp_SYNTHETIC0123456789abcdef")))) }
        // Allowlisted nonsecret settings still may not hold a literal token or a credential behind an auth scheme.
        for (env in listOf(mapOf("DEBUG" to token), mapOf("DEBUG" to "1 $token"), mapOf("USER_AGENT" to "bot Bearer opaque-raw"), mapOf("LANG" to "opaque-raw"))) {
            assertFailsWith<IllegalArgumentException>(env.toString()) { RunnerAdmission.requireLeasedProcessInputs(mcp("env", env).getValue("search")) }
        }
        // Not tokens: locales, key-file paths, names ending in "key" and token counts (R3-3).
        for (accepted in listOf(args("-y", "search-mcp"), args("--api-key-env", "SEARCH_KEY"), args("--verbose", "--port", "8080"), args("--token"),
                args("--locale", "sk_SK"), args("--public-key", "/etc/mcp/pub.pem"), args("--ssh-key", "~/.ssh/id_ed25519"),
                args("--sort-key", "name"), args("--private-key", "/etc/mcp/key.pem"), args("--max-token", "4096"),
                args("--url", "http://localhost:8080/mcp"), args("--auth-scheme", "bearer", "--port", "1"))) {
            RunnerAdmission.requireLeasedProcessInputs(server(accepted))
        }
        RunnerAdmission.requireLeasedProcessInputs(mcp("env", mapOf("DEBUG" to "1", "LANG" to "sk_SK.UTF-8", "LC_ALL" to "C.UTF-8", "SEARCH_KEY" to "credential:search@1")).getValue("search"))
        inspect("mcp_servers", buildJsonObject { put("search", server(args("-y", "search-mcp"), "env" to buildJsonObject { put("SEARCH_KEY", "credential:search@1") })) })
        // The bundled default server stays admissible.
        inspect("mcp_servers", buildJsonObject { put("playwright", server(args("@playwright/mcp@latest")) .let { JsonObject(it + ("command" to JsonPrimitive("npx"))) }) })
    }
    private val fileCapabilities = setOf("workflow.full.v1", "step.agent.v1", "model.http.v1", "tool.exit.v1", "tool.file_system.v1")
    private fun fileWorkflow(agent: AgentDefinition, isolation: DirectoryIsolationConfig = DirectoryIsolationConfig()) =
        WorkflowDefinition("files", agents = mapOf("agent" to agent), directoryIsolation = isolation, workflow = listOf(WorkflowStep("step", agent = "agent", input = "Synthetic")))
    @Test fun `file system tools are admitted only with the confined capability and host-chosen directories`() {
        val agent = AgentDefinition(tools = listOf("exit", "file_system"))
        assertTrue("tool.file_system.v1" in RunnerAdmission(NullWorkflowResolver, fileCapabilities).inspect(fileWorkflow(agent)))
        assertFailsWith<IllegalArgumentException> { RunnerAdmission(NullWorkflowResolver, fileCapabilities - "tool.file_system.v1").inspect(fileWorkflow(agent)) }
        for (key in listOf("working_dir", "workspace_dir", "output_dir", "persistence_storage_root")) {
            val moved = AgentDefinition(tools = listOf("exit", "file_system"), overrides = mapOf(key to JsonPrimitive("/Users/someone")))
            assertFailsWith<IllegalArgumentException>(key) { RunnerAdmission(NullWorkflowResolver, fileCapabilities).inspect(fileWorkflow(moved)) }
        }
        for (isolation in listOf(DirectoryIsolationConfig(enabled = false), DirectoryIsolationConfig(baseDir = "/var/shared-runs"),
                DirectoryIsolationConfig(baseDir = "../runs"), DirectoryIsolationConfig(workingDirPattern = "{base_dir}/shared/workspace"),
                DirectoryIsolationConfig(outputDirPattern = "{base_dir}/{user_id}/output"), DirectoryIsolationConfig(persistenceDirPattern = "{base_dir}/{user_id}/{execution_id}/../x"))) {
            assertFailsWith<IllegalArgumentException>(isolation.toString()) { RunnerAdmission(NullWorkflowResolver, fileCapabilities).inspect(fileWorkflow(agent, isolation)) }
        }
        // Shell and web stay outside the Runner tool inventory.
        for (tool in listOf("shell", "web")) {
            assertFailsWith<IllegalArgumentException>(tool) { RunnerAdmission(NullWorkflowResolver, fileCapabilities + "tool.$tool.v1").inspect(fileWorkflow(AgentDefinition(tools = listOf("file_system", tool)))) }
        }
    }
    @Test fun `CLI agents are not checked against engine tool groups but cannot carry extra CLI arguments`() {
        val capabilities = setOf("workflow.full.v1", "step.agent.v1", "agent.claude.v1")
        fun claude(vararg extra: Pair<String, String>) = AgentDefinition(type = "claude_code_agent", tools = listOf("exit", "file_system", "web"), overrides = mapOf(
            "external_agent_claude_auth_mode" to JsonPrimitive("subscription"), "external_agent_claude_oauth_token" to JsonPrimitive("credential:claude@1")) +
            extra.associate { it.first to JsonPrimitive(it.second) })
        val required = RunnerAdmission(NullWorkflowResolver, capabilities).inspect(fileWorkflow(claude("external_agent_claude_extra_args" to "--dangerously-skip-permissions")))
        assertFalse(required.any { it.startsWith("tool.") })
        for (args in listOf("--tools,Bash", "--allowedTools,Bash", "--permission-mode,bypassPermissions", "--add-dir,/", "--settings,/tmp/s.json")) {
            assertFailsWith<IllegalArgumentException>(args) { RunnerAdmission(NullWorkflowResolver, capabilities).inspect(fileWorkflow(claude("external_agent_claude_extra_args" to args))) }
        }
    }
    @Test fun `Claude tool policy derives a file-only registry from the allowlist`() {
        assertEquals("Read,Write,Edit,Glob,Grep", RunnerAgentToolPolicy.claudeBuiltinTools(null))
        assertEquals("Read,Write,Edit,Glob,Grep", RunnerAgentToolPolicy.claudeBuiltinTools(""))
        assertEquals("Read,Write", RunnerAgentToolPolicy.claudeBuiltinTools("Read,Write"))
        assertEquals("Read,Write,Glob", RunnerAgentToolPolicy.claudeBuiltinTools("Read,Write,Glob,WebSearch,WebFetch,Bash"))
        assertEquals(listOf("Read(src)", "Edit"), RunnerAgentToolPolicy.claudeAllowedEntries("Read(src),Bash(curl:*),Edit"))
        assertEquals("", RunnerAgentToolPolicy.claudeBuiltinTools("Bash,WebFetch"))
        assertEquals(emptyList(), RunnerAgentToolPolicy.claudeExtraArgs("--dangerously-skip-permissions"))
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
