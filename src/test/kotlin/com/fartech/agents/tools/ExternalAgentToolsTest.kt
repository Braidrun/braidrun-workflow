package com.fartech.agents.tools

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecResult
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Unit tests for [ExternalAgentTools]. The strategy is to inject a [FakeSubprocessExecutor]
 * that records the [ExecRequest] it received and returns a canned [ExecResult] — so each test
 * can verify both the *input* (command construction, env propagation, working dir) and the
 * *output handling* (JSON parsing, error mapping, monitoring events) without actually spawning
 * `claude` / `codex` (which obviously aren't available in CI).
 */
class ExternalAgentToolsTest {

    // ------------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------------

    /**
     * Records the request, returns whatever the test configures. Throwing executors are
     * supported by providing a `throws` lambda.
     */
    private class FakeSubprocessExecutor(
        private val result: ExecResult = ExecResult(0, "", "", 100),
        private val throws: (() -> Throwable)? = null
    ) : SubprocessExecutor {
        var lastRequest: ExecRequest? = null
            private set

        override suspend fun execute(request: ExecRequest): ExecResult {
            lastRequest = request
            throws?.let { throw it() }
            return result
        }
    }

    private data class CapturedEvent(val type: String, val summary: String, val detail: String?)

    private fun parametersWithKey(provider: String, key: String): List<ConfigurationParameter> =
        listOf(ConfigurationParameter("${provider}_api_key", JsonPrimitive(key)))

    private fun buildTools(
        executor: SubprocessExecutor,
        parameters: List<ConfigurationParameter> = parametersWithKey("anthropic", "sk-test-anthropic") +
            parametersWithKey("openai", "sk-test-openai"),
        events: MutableList<CapturedEvent> = mutableListOf()
    ): Pair<ExternalAgentTools, MutableList<CapturedEvent>> {
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parameters,
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File(".")),
            onMonitorEvent = { type, summary, detail -> events += CapturedEvent(type, summary, detail) }
        )
        return tools to events
    }

    // ------------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------------

    @Test
    fun `claude sub-agent returns the 'result' field from JSON output`() = runBlocking {
        val claudeJson = """
            {
              "type": "result",
              "subtype": "success",
              "result": "Final answer from Claude.",
              "session_id": "abc123",
              "cost_usd": 0.0123,
              "usage": {
                "input_tokens": 1500,
                "output_tokens": 450,
                "cache_read_input_tokens": 800,
                "cache_creation_input_tokens": 100
              }
            }
        """.trimIndent()
        val executor = FakeSubprocessExecutor(ExecResult(0, claudeJson, "", 250))
        val (tools, events) = buildTools(executor)

        val out = tools.runClaudeCodeSubAgent(
            ExternalAgentContext(prompt = "do the thing", name = "claude-1")
        )

        assertEquals("Final answer from Claude.", out)
        assertTrue(events.any { it.type == "claude_code_sub_agent_starting" })
        val completed = events.first { it.type == "claude_code_sub_agent_completed" }
        // Phase 2: usage + cost should land in the monitoring detail field.
        assertTrue(completed.detail!!.contains("input_tokens=1500"), "expected input_tokens in detail, got: ${completed.detail}")
        assertTrue(completed.detail.contains("output_tokens=450"))
        assertTrue(completed.detail.contains("cache_read=800"))
        assertTrue(completed.detail.contains("cost_usd="), "expected cost_usd in detail")
        assertTrue(completed.detail.contains("session_id="))
    }

    @Test
    fun `codex sub-agent returns the 'result' field from JSON output`() = runBlocking {
        val codexJson = """{"result":"Codex answer","usage":{"prompt_tokens":100,"completion_tokens":50}}"""
        val executor = FakeSubprocessExecutor(ExecResult(0, codexJson, "", 180))
        val (tools, events) = buildTools(executor)

        val out = tools.runCodexSubAgent(
            ExternalAgentContext(prompt = "compute", name = "codex-1")
        )

        assertEquals("Codex answer", out)
        val completed = events.first { it.type == "codex_sub_agent_completed" }
        assertTrue(completed.detail!!.contains("input_tokens=100"))
        assertTrue(completed.detail.contains("output_tokens=50"))
    }

    @Test
    fun `claude sub-agent returns raw stdout when output is not JSON`() = runBlocking {
        // Older SDK versions or `--output-format text` produce plain text — we should fall
        // back rather than fail. The format drift hardening is documented in parseClaudeOutput.
        val executor = FakeSubprocessExecutor(ExecResult(0, "Plain text answer", "", 100))
        val (tools, _) = buildTools(executor)

        val out = tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))
        assertEquals("Plain text answer", out)
    }

    // ------------------------------------------------------------------------
    // Command construction
    // ------------------------------------------------------------------------

    @Test
    fun `claude command uses -p and pipes prompt via stdin`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(
            ExternalAgentContext(prompt = "very long prompt", model = "claude-sonnet-4-5", maxTurns = 16)
        )

        val req = executor.lastRequest!!
        assertEquals("claude", req.command.first())
        assertTrue(req.command.contains("-p"), "expected -p flag, got: ${req.command}")
        assertEquals(listOf("--output-format", "json"),
            req.command.subList(req.command.indexOf("--output-format"), req.command.indexOf("--output-format") + 2))
        assertTrue(req.command.containsInOrder("--model", "claude-sonnet-4-5"))
        assertTrue(req.command.containsInOrder("--max-turns", "16"))
        // Prompt is piped via stdin so we don't blow argv limits.
        assertEquals("very long prompt", req.stdin)
    }

    @Test
    fun `allowed and disallowed tools are joined with commas`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(
            ExternalAgentContext(
                prompt = "x",
                allowedTools = listOf("Read", "Edit", "Bash"),
                disallowedTools = listOf("WebFetch")
            )
        )

        val req = executor.lastRequest!!
        assertTrue(req.command.containsInOrder("--allowedTools", "Read,Edit,Bash"))
        assertTrue(req.command.containsInOrder("--disallowedTools", "WebFetch"))
    }

    @Test
    fun `operator can override the claude command to npx`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val params = parametersWithKey("anthropic", "sk-test") + listOf(
            ConfigurationParameter("external_agent_claude_command", JsonPrimitive("npx")),
            ConfigurationParameter("external_agent_claude_extra_args", JsonPrimitive("-y,@anthropic-ai/claude-code"))
        )
        val (tools, _) = buildTools(executor, params)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

        val cmd = executor.lastRequest!!.command
        assertEquals("npx", cmd[0])
        assertEquals("-y", cmd[1])
        assertEquals("@anthropic-ai/claude-code", cmd[2])
        // CLI flags should follow the operator-supplied npx args.
        assertTrue(cmd.subList(3, cmd.size).contains("-p"))
    }

    @Test
    fun `codex command uses exec subcommand`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", model = "gpt-5-codex"))

        val cmd = executor.lastRequest!!.command
        assertEquals("codex", cmd[0])
        assertEquals("exec", cmd[1])
        assertTrue(cmd.containsInOrder("--model", "gpt-5-codex"))
    }

    // ------------------------------------------------------------------------
    // API key resolution
    // ------------------------------------------------------------------------

    @Test
    fun `missing anthropic key throws clear error`() {
        val executor = FakeSubprocessExecutor(ExecResult(0, "", "", 1))
        // Only configure openai key; anthropic resolution should still fall through to env,
        // and since the test env has no ANTHROPIC_API_KEY (unset via System.getenv stub
        // would be heavier — we just rely on real env not having it).
        val params = parametersWithKey("openai", "sk-openai")
        val (tools, _) = buildTools(executor, params)

        // Skip the test if the developer happens to have ANTHROPIC_API_KEY set locally —
        // the production behaviour (use env var) is correct, so don't fight it.
        if (System.getenv("ANTHROPIC_API_KEY")?.isNotBlank() == true) return

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))
            }
        }
        assertTrue(ex.message!!.contains("Claude Code"), "expected engine name in message")
        assertTrue(ex.message!!.contains("ANTHROPIC_API_KEY"), "expected env var hint")
    }

    @Test
    fun `anthropic key from workflow parameter is injected into env`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

        val env = executor.lastRequest!!.env
        assertEquals("sk-test-anthropic", env["ANTHROPIC_API_KEY"])
        // No OPENAI key should be leaked into Claude's subprocess env.
        assertFalse(env.containsKey("OPENAI_API_KEY"))
    }

    @Test
    fun `codex key from workflow parameter is injected into env`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runCodexSubAgent(ExternalAgentContext(prompt = "x"))

        val env = executor.lastRequest!!.env
        assertEquals("sk-test-openai", env["OPENAI_API_KEY"])
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"))
    }

    // ------------------------------------------------------------------------
    // Error paths
    // ------------------------------------------------------------------------

    @Test
    fun `non-zero exit code throws ExecutionException with stderr tail`() {
        val executor = FakeSubprocessExecutor(
            ExecResult(exitCode = 2, stdout = "", stderr = "OOM killed at line 5", durationMs = 50)
        )
        val (tools, events) = buildTools(executor)

        val ex = assertThrows(ExternalAgentTools.ExternalAgentExecutionException::class.java) {
            runBlocking {
                tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", name = "claude-fail"))
            }
        }
        assertTrue(ex.message!!.contains("OOM killed"))
        // A failed event must surface with the right type so the UI can render it as a failure.
        assertTrue(events.any { it.type == "claude_code_sub_agent_failed" })
    }

    @Test
    fun `timeout sentinel maps to TimeoutException with structured event`() {
        // The subprocess executors signal timeout via exit=-1 + stderr containing
        // "TIMED OUT" — see NativeSubprocessExecutor.kt:108 and DockerSubprocessExecutor's
        // analogue. We test the ExternalAgentTools handling of that sentinel.
        val executor = FakeSubprocessExecutor(
            ExecResult(exitCode = -1, stdout = "", stderr = "killed\n[TIMED OUT after 120s]", durationMs = 120_000)
        )
        val (tools, events) = buildTools(executor)

        val ex = assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
            runBlocking {
                tools.runClaudeCodeSubAgent(
                    ExternalAgentContext(prompt = "x", name = "claude-slow", timeoutSeconds = 120)
                )
            }
        }
        assertTrue(ex.message!!.contains("timed out"))
        val timeout = events.first { it.type == "claude_code_sub_agent_timeout" }
        assertTrue(timeout.detail!!.contains("timeout=120s"))
    }

    @Test
    fun `executor throwing propagates with failed event emitted`() {
        val executor = FakeSubprocessExecutor(throws = { RuntimeException("docker daemon down") })
        val (tools, events) = buildTools(executor)

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))
            }
        }
        val failed = events.first { it.type == "claude_code_sub_agent_failed" }
        assertTrue(failed.detail!!.contains("docker daemon down"))
    }

    // ------------------------------------------------------------------------
    // Timeout clamping
    // ------------------------------------------------------------------------

    @Test
    fun `timeout below floor is clamped up`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", timeoutSeconds = 1))
        assertEquals(ExternalAgentTools.MIN_TIMEOUT_SECONDS.toLong(), executor.lastRequest!!.timeoutSeconds)
    }

    @Test
    fun `timeout above ceiling is clamped down`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", timeoutSeconds = 999_999))
        assertEquals(ExternalAgentTools.MAX_TIMEOUT_SECONDS.toLong(), executor.lastRequest!!.timeoutSeconds)
    }

    // ------------------------------------------------------------------------
    // Image hint / working dir
    // ------------------------------------------------------------------------

    @Test
    fun `image hint is 'node' so docker mode pulls the node-only image`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))
        assertEquals("node", executor.lastRequest!!.imageHint)
    }

    // ------------------------------------------------------------------------
    // Base URL override (route through OpenRouter / proxy)
    // ------------------------------------------------------------------------

    @Test
    fun `claude base_url override injects ANTHROPIC_BASE_URL env`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val params = parametersWithKey("anthropic", "sk-or-v1-fake") + listOf(
            ConfigurationParameter("external_agent_claude_base_url", JsonPrimitive("https://openrouter.ai/api/v1"))
        )
        val (tools, events) = buildTools(executor, params)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

        val env = executor.lastRequest!!.env
        assertEquals("https://openrouter.ai/api/v1", env["ANTHROPIC_BASE_URL"])
        assertEquals("sk-or-v1-fake", env["ANTHROPIC_API_KEY"])
        // Operator wants to see the redirect at a glance in monitoring.
        val starting = events.first { it.type == "claude_code_sub_agent_starting" }
        assertTrue(starting.detail!!.contains("baseUrl=https://openrouter.ai/api/v1"),
            "expected baseUrl in starting event detail, got: ${starting.detail}")
    }

    @Test
    fun `codex base_url override injects OPENAI_BASE_URL env`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val params = parametersWithKey("openai", "sk-or-v1-fake") + listOf(
            ConfigurationParameter("external_agent_codex_base_url", JsonPrimitive("https://openrouter.ai/api/v1"))
        )
        val (tools, _) = buildTools(executor, params)

        tools.runCodexSubAgent(ExternalAgentContext(prompt = "x"))

        val env = executor.lastRequest!!.env
        assertEquals("https://openrouter.ai/api/v1", env["OPENAI_BASE_URL"])
        assertEquals("sk-or-v1-fake", env["OPENAI_API_KEY"])
        // Cross-engine env isolation still holds — the Claude base URL must not appear in
        // a Codex invocation (and vice-versa).
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"))
    }

    @Test
    fun `no base_url override leaves the env clean`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, events) = buildTools(executor)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

        val env = executor.lastRequest!!.env
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"))
        assertFalse(env.containsKey("OPENAI_BASE_URL"))
        // When no override is configured the starting event detail should NOT mention
        // baseUrl — a stray "baseUrl=" line would confuse operators reading the log.
        val starting = events.first { it.type == "claude_code_sub_agent_starting" }
        assertFalse(starting.detail!!.contains("baseUrl="),
            "expected no baseUrl mention when override unset, got: ${starting.detail}")
    }

    @Test
    fun `malformed base_url is rejected before subprocess spawn`() {
        val executor = FakeSubprocessExecutor(ExecResult(0, "", "", 1))
        // Several flavours of broken: missing scheme, http to non-loopback, file://, garbage.
        for (bad in listOf("openrouter.ai/api/v1", "http://internal-corp/api", "file:///etc/passwd", "htps://typo")) {
            val params = parametersWithKey("anthropic", "sk-test") + listOf(
                ConfigurationParameter("external_agent_claude_base_url", JsonPrimitive(bad))
            )
            val (tools, events) = buildTools(executor, params)
            val ex = assertThrows(IllegalArgumentException::class.java, {
                runBlocking {
                    tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))
                }
            }, "expected reject for '$bad'")
            assertTrue(ex.message!!.contains("external_agent_claude_base_url"))
            // The malformed config must NOT have been allowed to reach the subprocess.
            assertNull(executor.lastRequest, "subprocess should not have been spawned for invalid baseUrl '$bad'")
            // A failed-config event must be emitted so operators see the rejection in monitoring.
            assertTrue(events.any { it.type == "claude_code_sub_agent_failed" },
                "expected failure event for bad baseUrl '$bad'")
        }
    }

    @Test
    fun `localhost http base_url is allowed for dev`() = runBlocking {
        // Integration tests / local dev frequently point at a mock proxy on
        // 127.0.0.1; rejecting all http:// would break that workflow.
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val params = parametersWithKey("anthropic", "sk-test") + listOf(
            ConfigurationParameter("external_agent_claude_base_url", JsonPrimitive("http://localhost:8080/v1"))
        )
        val (tools, _) = buildTools(executor, params)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

        assertEquals("http://localhost:8080/v1", executor.lastRequest!!.env["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun `working dir defaults to context workspace`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parametersWithKey("anthropic", "sk-test"),
            userId = "u",
            context = SubprocessToolContext(workspaceDir = File("."))
        )

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))
        assertEquals(File(".").absoluteFile, executor.lastRequest!!.workingDir.absoluteFile)
    }
}

private fun List<String>.containsInOrder(first: String, second: String): Boolean {
    val idx = this.indexOf(first)
    return idx >= 0 && idx + 1 < this.size && this[idx + 1] == second
}
