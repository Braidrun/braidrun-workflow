package com.fartech.agents.tools

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecResult
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

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
        private val throws: (() -> Throwable)? = null,
        private val delayMs: Long = 0,
        private val resultForRequest: ((ExecRequest) -> ExecResult)? = null
    ) : SubprocessExecutor {
        var lastRequest: ExecRequest? = null
            private set

        // Captured at execute-time (i.e. while the auth.json still exists, before the
        // post-run cleanup deletes the per-run CODEX_HOME).
        var capturedCodexAuthJson: String? = null
            private set

        override suspend fun execute(request: ExecRequest): ExecResult {
            lastRequest = request
            request.env["CODEX_HOME"]?.let { home ->
                val authFile = File(home, "auth.json")
                if (authFile.isFile) capturedCodexAuthJson = authFile.readText()
            }
            if (delayMs > 0) delay(delayMs)
            throws?.let { throw it() }
            val effectiveResult = resultForRequest?.invoke(request) ?: result
            effectiveResult.stdout.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { request.stdoutLineCallback?.invoke(it) }
            return effectiveResult
        }
    }

    private data class CapturedEvent(val type: String, val summary: String, val detail: String?)

    private fun parametersWithKey(provider: String, key: String): List<ConfigurationParameter> =
        listOf(ConfigurationParameter("${provider}_api_key", JsonPrimitive(key)))

    private fun buildTools(
        executor: SubprocessExecutor,
        parameters: List<ConfigurationParameter> = parametersWithKey("anthropic", "sk-test-anthropic") +
            parametersWithKey("openai", "sk-test-openai"),
        events: MutableList<CapturedEvent> = mutableListOf(),
        trustExecutorSandbox: Boolean = false
    ): Pair<ExternalAgentTools, MutableList<CapturedEvent>> {
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parameters,
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File(".")),
            onMonitorEvent = { type, summary, detail -> events += CapturedEvent(type, summary, detail) },
            trustExecutorSandbox = trustExecutorSandbox
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

    @Test
    fun `claude sub-agent emits progress while subprocess is running`() = runBlocking {
        val executor = FakeSubprocessExecutor(
            result = ExecResult(0, """{"result":"ok"}""", "", 1_250),
            delayMs = 1_250
        )
        val params = parametersWithKey("anthropic", "sk-test") + listOf(
            ConfigurationParameter(
                ExternalAgentTools.CLAUDE_PROGRESS_INTERVAL_SECONDS_PARAMETER,
                JsonPrimitive("1")
            )
        )
        val (tools, events) = buildTools(executor, params)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", name = "claude-slow"))

        val progress = events.firstOrNull { it.type == "claude_code_sub_agent_progress" }
        assertNotNull(progress, "expected a progress event while Claude subprocess was still running")
        assertTrue(progress!!.summary.contains("正在思考"))
        assertTrue(progress.detail!!.contains("phase=running"))
        assertTrue(progress.detail.contains("authMode=api_key"))
    }

    @Test
    fun `claude sub-agent emits stream json events and returns final result`() = runBlocking {
        val streamJson = """
            {"type":"system","model":"sonnet","session_id":"abc123","cwd":"/workspace"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"正在分析关键词"},{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"keywords.csv"}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"file contents"}]}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"正在分析关键词，已经找到问题"}]}}
            {"type":"result","subtype":"success","result":"最终答案","session_id":"abc123","cost_usd":0.001,"usage":{"input_tokens":10,"output_tokens":20}}
        """.trimIndent()
        val executor = FakeSubprocessExecutor(ExecResult(0, streamJson, "", 500))
        val (tools, events) = buildTools(executor)

        val out = tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", name = "claude-stream"))

        assertEquals("最终答案", out)
        assertTrue(events.any { it.type == "claude_code_stream_system" })
        assertTrue(events.any { it.type == "claude_code_stream_text_delta" && it.summary.contains("正在分析关键词") })
        assertTrue(events.any { it.type == "claude_code_stream_tool_call" && it.summary.contains("Read") })
        assertTrue(events.any { it.type == "claude_code_stream_tool_result" && it.summary.contains("file contents") })
        val completed = events.first { it.type == "claude_code_sub_agent_completed" }
        assertTrue(completed.detail!!.contains("input_tokens=10"))
        assertTrue(completed.detail.contains("output_tokens=20"))
    }

    @Test
    fun `runConversation returns detailed claude result and streams clean text deltas`() = runBlocking {
        val streamJson = """
            {"type":"system","model":"sonnet","session_id":"abc123","cwd":"/workspace"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"正在分析"}]}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"正在分析完成"}]}}
            {"type":"result","subtype":"success","result":"最终答案","session_id":"abc123","cost_usd":0.001,"usage":{"input_tokens":10,"output_tokens":20}}
        """.trimIndent()
        val executor = FakeSubprocessExecutor(ExecResult(0, streamJson, "", 500))
        val deltas = mutableListOf<String>()
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parametersWithKey("anthropic", "sk-test"),
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File("."))
        )

        val result = tools.runConversation(
            ExternalAgentTools.Engine.CLAUDE,
            ExternalAgentContext(prompt = "x", name = "claude-stream")
        ) { delta -> deltas += delta }

        assertEquals("最终答案", result.text)
        assertEquals("abc123", result.sessionId)
        assertEquals(10, result.inputTokens)
        assertEquals(20, result.outputTokens)
        assertEquals(0.001, result.costUsd)
        assertEquals(listOf("正在分析", "完成"), deltas)
    }

    @Test
    fun `runConversation streams claude partial-message tokens without duplicating the final message`() = runBlocking {
        // With --include-partial-messages the CLI streams token-level
        // `stream_event`/`content_block_delta` frames, THEN a complete `assistant`
        // message with the same text. The emitter must forward the tokens live and
        // NOT re-emit the whole answer when the complete message lands.
        val streamJson = """
            {"type":"system","model":"opus","session_id":"s1","cwd":"/workspace"}
            {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}
            {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你"}}}
            {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好呀"}}}
            {"type":"stream_event","event":{"type":"content_block_stop","index":0}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"你好呀"}]}}
            {"type":"result","subtype":"success","result":"你好呀","session_id":"s1","usage":{"input_tokens":5,"output_tokens":2}}
        """.trimIndent()
        val executor = FakeSubprocessExecutor(ExecResult(0, streamJson, "", 500))
        val deltas = mutableListOf<String>()
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parametersWithKey("anthropic", "sk-test"),
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File("."))
        )

        val result = tools.runConversation(
            ExternalAgentTools.Engine.CLAUDE,
            ExternalAgentContext(prompt = "x", name = "claude-partial")
        ) { delta -> deltas += delta }

        assertEquals("你好呀", result.text)
        // Streamed token-by-token, and the trailing complete message added nothing.
        assertEquals(listOf("你", "好呀"), deltas)
    }

    @Test
    fun `runConversation streams codex cumulative agent messages as deltas`() = runBlocking {
        val streamJson = """
            {"type":"thread.started","thread_id":"thread-1"}
            {"type":"item.updated","item":{"type":"agent_message","text":"hello"}}
            {"type":"item.completed","item":{"type":"agent_message","text":"hello world"}}
            {"type":"turn.completed","usage":{"input_tokens":7,"output_tokens":3,"cached_input_tokens":2}}
        """.trimIndent()
        val executor = FakeSubprocessExecutor(ExecResult(0, streamJson, "", 500))
        val deltas = mutableListOf<String>()
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parametersWithKey("openai", "sk-test"),
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File("."))
        )

        val result = tools.runConversation(
            ExternalAgentTools.Engine.CODEX,
            ExternalAgentContext(prompt = "x", name = "codex-stream", model = "gpt-5-codex")
        ) { delta -> deltas += delta }

        assertEquals("hello world", result.text)
        assertEquals("thread-1", result.sessionId)
        assertEquals(7, result.inputTokens)
        assertEquals(3, result.outputTokens)
        assertNull(result.costUsd)
        assertEquals(listOf("hello", " world"), deltas)
    }

    @Test
    fun `history replay prompt carries prior turn into the next external invocation`() = runBlocking {
        val executor = FakeSubprocessExecutor(
            resultForRequest = { request ->
                val prompt = request.stdin.orEmpty()
                val answer = if ("上一轮我说" in prompt && "42" in prompt) {
                    "你让我记的数字是 42。"
                } else if ("请记住数字 42" in prompt) {
                    "OK"
                } else {
                    "我不知道。"
                }
                ExecResult(0, """{"type":"result","result":"$answer","session_id":"session-1"}""", "", 100)
            }
        )
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parametersWithKey("anthropic", "sk-test"),
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File("."))
        )

        val first = tools.runConversation(
            ExternalAgentTools.Engine.CLAUDE,
            ExternalAgentContext(prompt = "请记住数字 42，只回复 OK", name = "history-replay")
        )
        val secondPrompt = "[上一轮我说: 请记住数字 42，只回复 OK / 你回答: ${first.text}]\n现在回答：我让你记的数字是多少？"
        val second = tools.runConversation(
            ExternalAgentTools.Engine.CLAUDE,
            ExternalAgentContext(prompt = secondPrompt, name = "history-replay")
        )

        assertEquals("OK", first.text)
        assertTrue(second.text.contains("42"), "expected second answer to use replayed history, got: ${second.text}")
    }

    // ------------------------------------------------------------------------
    // Command construction
    // ------------------------------------------------------------------------

    @Test
    fun `claude command uses -p and passes prompt through stdin`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(
            ExternalAgentContext(prompt = "very long prompt", model = "claude-sonnet-4-5", maxTurns = 16)
        )

        val req = executor.lastRequest!!
        assertEquals("claude", req.command.first())
        assertTrue(req.command.contains("-p"), "expected -p flag, got: ${req.command}")
        assertTrue(req.command.contains("--append-system-prompt"), "expected Claude language system prompt, got: ${req.command}")
        val appendSystemPrompt = req.command[req.command.indexOf("--append-system-prompt") + 1]
        assertTrue("始终使用简体中文" in appendSystemPrompt, "expected Chinese response constraint, got: $appendSystemPrompt")
        assertEquals(listOf("--output-format", "stream-json"),
            req.command.subList(req.command.indexOf("--output-format"), req.command.indexOf("--output-format") + 2))
        // `-p --output-format=stream-json` requires --verbose or the CLI aborts.
        assertTrue(req.command.contains("--verbose"), "stream-json print mode requires --verbose")
        assertTrue(req.command.contains("--include-partial-messages"))
        assertTrue(req.command.contains("--include-hook-events"))
        assertTrue(req.command.containsInOrder("--model", "claude-sonnet-4-5"))
        assertTrue(req.command.containsInOrder("--max-turns", "16"))
        assertFalse(req.command.contains("very long prompt"))
        assertEquals("very long prompt", req.stdin)
    }

    @Test
    fun `claude command resumes an existing session when requested`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(
            ExternalAgentContext(prompt = "continue", resumeSessionId = "claude-session-123")
        )

        val request = executor.lastRequest!!
        val command = request.command
        assertTrue(command.containsInOrder("--resume", "claude-session-123"))
        assertEquals("continue", request.stdin)
    }

    @Test
    fun `claude command skips permissions when executor sandbox is trusted`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor, trustExecutorSandbox = true)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

        assertTrue(executor.lastRequest!!.command.contains("--dangerously-skip-permissions"))
    }

    @Test
    fun `claude mcp config is written inside docker mounted workspace`() = runBlocking {
        val workspace = Files.createTempDirectory("claude-mcp-workspace").toFile()
        var configPathSeenByClaude: String? = null
        var configTextSeenByExecutor: String? = null
        try {
            val executor = FakeSubprocessExecutor(
                resultForRequest = { request ->
                    val configPath = request.command[request.command.indexOf("--mcp-config") + 1]
                    configPathSeenByClaude = configPath
                    assertTrue(configPath.startsWith("/workspace/.braidrun-claude-mcp-"), "got: $configPath")
                    val hostConfig = File(workspace, configPath.removePrefix("/workspace/"))
                    assertTrue(hostConfig.isFile, "expected host MCP config to exist at ${hostConfig.absolutePath}")
                    assertTrue(hostConfig.canRead(), "expected MCP config to be readable by the container user")
                    configTextSeenByExecutor = hostConfig.readText()
                    ExecResult(0, """{"result":"ok"}""", "", 1)
                }
            )
            val tools = ExternalAgentTools(
                executor = executor,
                parameters = parametersWithKey("anthropic", "sk-test-anthropic"),
                userId = "test-user",
                context = SubprocessToolContext(workspaceDir = workspace),
                trustExecutorSandbox = true
            )

            tools.runClaudeCodeSubAgent(
                ExternalAgentContext(
                    prompt = "x",
                    mcpServers = listOf(
                        ExternalMcpServerConfig(
                            name = "braidrun_authoring",
                            command = "braidrun-authoring-mcp-bridge",
                            env = mapOf(
                                "WF_BACKEND_URL" to "http://127.0.0.1:8090",
                                "BRAIDRUN_AUTHORING_TOKEN" to "token"
                            ),
                            allowedTools = listOf("mcp__braidrun_authoring__startDraft")
                        )
                    )
                )
            )

            assertTrue(configPathSeenByClaude?.startsWith("/workspace/") == true)
            assertTrue(configTextSeenByExecutor!!.contains(""""mcpServers""""))
            assertTrue(configTextSeenByExecutor.contains(""""braidrun_authoring""""))
            assertTrue(configTextSeenByExecutor.contains(""""BRAIDRUN_AUTHORING_TOKEN":"token""""))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `claude system prompt includes workflow prompt, configured prompt, and chinese response rule`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val params = parametersWithKey("anthropic", "sk-test") + listOf(
            ConfigurationParameter("external_agent_claude_append_system_prompt", JsonPrimitive("额外运行约束"))
        )
        val (tools, _) = buildTools(executor, params)

        tools.runClaudeCodeSubAgent(
            ExternalAgentContext(
                prompt = "x",
                systemPrompt = "工作流系统提示"
            )
        )

        val command = executor.lastRequest!!.command
        val appendSystemPrompt = command[command.indexOf("--append-system-prompt") + 1]
        assertTrue(appendSystemPrompt.indexOf("工作流系统提示") < appendSystemPrompt.indexOf("额外运行约束"))
        assertTrue(appendSystemPrompt.indexOf("额外运行约束") < appendSystemPrompt.indexOf("始终使用简体中文"))
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
        assertFalse(req.command.contains("x"))
        assertEquals("x", req.stdin)
    }

    @Test
    fun `claude command allows output directory access`() = runBlocking {
        val outputDir = Files.createTempDirectory("external-agent-output").toFile()
        try {
            val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val tools = ExternalAgentTools(
                executor = executor,
                parameters = parametersWithKey("anthropic", "sk-test"),
                userId = "u",
                context = SubprocessToolContext(workspaceDir = File("."), outputDir = outputDir)
            )

            tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

            val command = executor.lastRequest!!.command
            val env = executor.lastRequest!!.env
            assertTrue(command.containsInOrder("--add-dir", outputDir.canonicalPath))
            assertEquals(outputDir.absolutePath, env["BRAIDRUN_OUTPUT_DIR"])
            assertTrue(
                command.indexOf("--add-dir") < command.indexOf("--output-format"),
                "--add-dir must be followed by another option so the prompt is not consumed as an allowed directory"
            )
        } finally {
            outputDir.deleteRecursively()
        }
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

        val req = executor.lastRequest!!
        val cmd = req.command
        assertEquals("codex", cmd[0])
        assertEquals("exec", cmd[1])
        assertTrue(cmd.containsInOrder("--model", "gpt-5-codex"))
        // A literal `-` selects stdin; the real prompt never enters argv.
        assertEquals("-", cmd.last())
        assertEquals("--", cmd[cmd.size - 2])
        assertEquals("x", req.stdin)
    }

    @Test
    fun `codex command resumes an existing thread with the resume subcommand`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runCodexSubAgent(
            ExternalAgentContext(
                prompt = "continue",
                model = "gpt-5-codex",
                resumeSessionId = "thread-123",
                codexSandboxMode = "read-only"
            )
        )

        val request = executor.lastRequest!!
        val cmd = request.command
        assertEquals("codex", cmd[0])
        assertEquals("exec", cmd[1])
        assertEquals("resume", cmd[2])
        assertFalse(cmd.contains("--sandbox"))
        assertTrue(cmd.containsInOrder("-c", "sandbox_mode=\"read-only\""))
        assertTrue(cmd.indexOf("thread-123") > cmd.indexOf("--json"))
        assertTrue(cmd.containsInOrder("--model", "gpt-5-codex"))
        assertEquals("-", cmd.last())
        assertEquals("--", cmd[cmd.size - 2])
        assertEquals("continue", request.stdin)
    }

    @Test
    fun `large multibyte prompts stay out of argv for both engines`() = runBlocking {
        val hugePrompt = "执行记录".repeat(100_000)
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = hugePrompt))
        val claudeRequest = executor.lastRequest!!
        assertEquals(hugePrompt, claudeRequest.stdin)
        assertFalse(claudeRequest.command.any { hugePrompt in it })
        assertTrue(
            claudeRequest.command.all {
                it.toByteArray(Charsets.UTF_8).size <= ExternalAgentTools.MAX_COMMAND_ARGUMENT_BYTES
            }
        )

        tools.runCodexSubAgent(ExternalAgentContext(prompt = hugePrompt, model = "gpt-5-codex"))
        val codexRequest = executor.lastRequest!!
        assertEquals(hugePrompt, codexRequest.stdin)
        assertEquals("-", codexRequest.command.last())
        assertFalse(codexRequest.command.any { hugePrompt in it })
    }

    @Test
    fun `claude composed system prompt is utf8 bounded before argv`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val params = parametersWithKey("anthropic", "sk-test") + listOf(
            ConfigurationParameter(
                ExternalAgentTools.CLAUDE_APPEND_SYSTEM_PROMPT_PARAMETER,
                JsonPrimitive("配置尾部".repeat(40_000))
            )
        )
        val (tools, _) = buildTools(executor, params)

        tools.runClaudeCodeSubAgent(
            ExternalAgentContext(
                prompt = "x",
                systemPrompt = "SYSTEM-BEGIN-" + "系统".repeat(40_000)
            )
        )

        val command = executor.lastRequest!!.command
        val systemPrompt = command[command.indexOf("--append-system-prompt") + 1]
        assertTrue(systemPrompt.toByteArray(Charsets.UTF_8).size <= ExternalAgentTools.MAX_COMMAND_ARGUMENT_BYTES)
        assertTrue(systemPrompt.startsWith("SYSTEM-BEGIN-"))
        assertTrue(systemPrompt.contains("truncated to fit external-agent command argument"))
        assertTrue(systemPrompt.contains("始终使用简体中文"))
    }

    @Test
    fun `oversized non-prompt argv fails clearly before subprocess spawn`() {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor)

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tools.runClaudeCodeSubAgent(
                    ExternalAgentContext(
                        prompt = "x",
                        allowedTools = listOf("T".repeat(ExternalAgentTools.MAX_COMMAND_ARGUMENT_BYTES + 1))
                    )
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("command argument"))
        assertNull(executor.lastRequest)
    }

    @Test
    fun `codex command bypasses approvals when executor sandbox is trusted`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor, trustExecutorSandbox = true)

        tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", model = "gpt-5-codex"))

        assertTrue(executor.lastRequest!!.command.contains("--dangerously-bypass-approvals-and-sandbox"))
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
    fun `claude subscription 429 switches to next credential`() = runBlocking {
        val usedTokens = mutableListOf<String>()
        val candidates = listOf(
            ClaudeCredentialProvider.Credential("credential-a", "token-a", "Personal A", "user"),
            ClaudeCredentialProvider.Credential("credential-b", "token-b", "Team B", "team")
        )
        val limited = mutableSetOf<String>()
        val resetTimes = mutableListOf<Long>()
        val succeeded = mutableListOf<String>()
        val provider = object : ClaudeCredentialProvider {
            override suspend fun acquire(excludedCredentialIds: Set<String>) =
                candidates.firstOrNull { it.id !in excludedCredentialIds && it.id !in limited }

            override suspend fun markRateLimited(
                credential: ClaudeCredentialProvider.Credential,
                resetAtMillis: Long
            ) {
                limited += credential.id
                resetTimes += resetAtMillis
            }

            override suspend fun markSucceeded(
                credential: ClaudeCredentialProvider.Credential,
                executionId: String?,
                stepName: String?
            ) {
                succeeded += credential.id
            }
        }
        val rateLimit = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":4102444800,"rateLimitType":"seven_day"}}
            {"type":"result","is_error":true,"api_error_status":429,"num_turns":1,"terminal_reason":"api_error","usage":{"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0},"permission_denials":[]}"""
        val executor = FakeSubprocessExecutor(resultForRequest = { request ->
            val token = request.env["CLAUDE_CODE_OAUTH_TOKEN"].orEmpty()
            usedTokens += token
            if (token == "token-a") {
                ExecResult(1, "", rateLimit, 10)
            } else {
                ExecResult(0, """{"type":"result","result":"fallback ok"}""", "", 10)
            }
        })
        val parameters = listOf(
            ConfigurationParameter(ExternalAgentTools.CLAUDE_AUTH_MODE_PARAMETER, JsonPrimitive("subscription"))
        )
        val events = mutableListOf<CapturedEvent>()
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = parameters,
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File("."), executionId = "exec-1", stepName = "step-1"),
            onMonitorEvent = { type, summary, detail -> events += CapturedEvent(type, summary, detail) },
            claudeCredentialProvider = provider
        )

        val output = tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", name = "failover"))

        assertEquals("fallback ok", output)
        assertEquals(listOf("token-a", "token-b"), usedTokens)
        assertEquals(listOf(4_102_444_800_000L), resetTimes)
        assertEquals(listOf("credential-b"), succeeded)
        assertTrue(events.any { it.type == ExternalAgentTools.CLAUDE_SUBSCRIPTION_RATE_LIMIT_EVENT })
    }

    @Test
    fun `claude subscription 429 after token usage cools credential without replay by default`() {
        val cooled = mutableListOf<String>()
        var executions = 0
        val provider = object : ClaudeCredentialProvider {
            override suspend fun acquire(excludedCredentialIds: Set<String>) =
                ClaudeCredentialProvider.Credential("credential-a", "token-a")
            override suspend fun markRateLimited(
                credential: ClaudeCredentialProvider.Credential,
                resetAtMillis: Long
            ) {
                cooled += credential.id
            }
            override suspend fun markSucceeded(
                credential: ClaudeCredentialProvider.Credential,
                executionId: String?,
                stepName: String?
            ) = Unit
        }
        val output = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":4102444800}}
            {"type":"result","is_error":true,"api_error_status":429,"num_turns":2,"terminal_reason":"api_error","usage":{"input_tokens":10,"output_tokens":2},"permission_denials":[]}"""
        val executor = FakeSubprocessExecutor(resultForRequest = {
            executions += 1
            ExecResult(1, "", output, 10)
        })
        val events = mutableListOf<CapturedEvent>()
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = listOf(
                ConfigurationParameter(ExternalAgentTools.CLAUDE_AUTH_MODE_PARAMETER, JsonPrimitive("subscription"))
            ),
            onMonitorEvent = { type, summary, detail -> events += CapturedEvent(type, summary, detail) },
            claudeCredentialProvider = provider
        )

        assertThrows(ExternalAgentTools.ExternalAgentExecutionException::class.java) {
            runBlocking { tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x")) }
        }
        assertEquals(1, executions)
        assertEquals(listOf("credential-a"), cooled)
        val rateLimited = events.single { it.type == ExternalAgentTools.CLAUDE_SUBSCRIPTION_RATE_LIMIT_EVENT }
        assertTrue(rateLimited.summary.contains("未自动重放"))
        assertTrue(rateLimited.detail!!.contains("partial_progress=true"))
        assertTrue(rateLimited.detail.contains("replay_enabled=false"))
    }

    @Test
    fun `claude subscription 429 after partial progress switches when replay is explicitly safe`() = runBlocking {
        val usedTokens = mutableListOf<String>()
        val usedPrompts = mutableListOf<String>()
        val candidates = listOf(
            ClaudeCredentialProvider.Credential("credential-a", "token-a", "Personal A", "user"),
            ClaudeCredentialProvider.Credential("credential-b", "token-b", "Personal B", "user"),
            ClaudeCredentialProvider.Credential("credential-c", "token-c", "Team C", "team"),
            ClaudeCredentialProvider.Credential("credential-d", "token-d", "Team D", "team")
        )
        val limited = mutableSetOf<String>()
        val succeeded = mutableListOf<String>()
        val provider = object : ClaudeCredentialProvider {
            override suspend fun acquire(excludedCredentialIds: Set<String>) =
                candidates.firstOrNull { it.id !in excludedCredentialIds && it.id !in limited }

            override suspend fun markRateLimited(
                credential: ClaudeCredentialProvider.Credential,
                resetAtMillis: Long
            ) {
                limited += credential.id
            }

            override suspend fun markSucceeded(
                credential: ClaudeCredentialProvider.Credential,
                executionId: String?,
                stepName: String?
            ) {
                succeeded += credential.id
            }
        }
        // Mirrors the production failure shape: several turns and non-zero cache/token usage
        // before Claude reports the structured session-limit 429.
        val rateLimit = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":4102444800,"rateLimitType":"five_hour"}}
            {"type":"result","subtype":"error_during_execution","is_error":true,"api_error_status":429,"num_turns":4,"terminal_reason":"api_error","result":"You've hit your session limit","usage":{"input_tokens":8,"output_tokens":3,"cache_creation_input_tokens":120,"cache_read_input_tokens":240},"permission_denials":[]}"""
        val executor = FakeSubprocessExecutor(resultForRequest = { request ->
            val token = request.env["CLAUDE_CODE_OAUTH_TOKEN"].orEmpty()
            usedTokens += token
            usedPrompts += request.stdin.orEmpty()
            if (token != "token-d") {
                ExecResult(1, "", rateLimit, 10)
            } else {
                ExecResult(0, """{"type":"result","result":"fallback after partial progress"}""", "", 10)
            }
        })
        val events = mutableListOf<CapturedEvent>()
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = listOf(
                ConfigurationParameter(ExternalAgentTools.CLAUDE_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(
                    ExternalAgentTools.CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_SAFE_PARAMETER,
                    JsonPrimitive(true)
                )
            ),
            userId = "test-user",
            context = SubprocessToolContext(workspaceDir = File("."), executionId = "exec-1", stepName = "safe-step"),
            onMonitorEvent = { type, summary, detail -> events += CapturedEvent(type, summary, detail) },
            claudeCredentialProvider = provider
        )

        val output = tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", name = "partial-failover"))

        assertEquals("fallback after partial progress", output)
        assertEquals(listOf("token-a", "token-b", "token-c", "token-d"), usedTokens)
        assertEquals("x", usedPrompts.first())
        assertTrue(usedPrompts.drop(1).all { it.startsWith("[系统重试说明]") })
        assertTrue(usedPrompts.drop(1).all { it.count { char -> char == '[' } == 1 })
        assertEquals(setOf("credential-a", "credential-b", "credential-c"), limited)
        assertEquals(listOf("credential-d"), succeeded)
        val rateLimited = events.filter { it.type == ExternalAgentTools.CLAUDE_SUBSCRIPTION_RATE_LIMIT_EVENT }
        assertEquals(3, rateLimited.size)
        assertTrue(rateLimited.all { it.summary.contains("正在切换备用凭据") })
        assertTrue(rateLimited.all { it.detail!!.contains("partial_progress=true") })
        assertTrue(rateLimited.all { it.detail!!.contains("replay_enabled=true") })
    }

    @Test
    fun `unstructured 429 does not cool or switch even when partial replay is enabled`() {
        var executions = 0
        val provider = object : ClaudeCredentialProvider {
            override suspend fun acquire(excludedCredentialIds: Set<String>) =
                ClaudeCredentialProvider.Credential("credential-a", "token-a")
            override suspend fun markRateLimited(
                credential: ClaudeCredentialProvider.Credential,
                resetAtMillis: Long
            ) = error("unconfirmed 429 must not cool a credential")
            override suspend fun markSucceeded(
                credential: ClaudeCredentialProvider.Credential,
                executionId: String?,
                stepName: String?
            ) = Unit
        }
        val output =
            """{"type":"result","is_error":true,"api_error_status":429,"num_turns":3,"terminal_reason":"api_error","usage":{"input_tokens":10,"output_tokens":2}}"""
        val executor = FakeSubprocessExecutor(resultForRequest = {
            executions += 1
            ExecResult(1, "", output, 10)
        })
        val events = mutableListOf<CapturedEvent>()
        val tools = ExternalAgentTools(
            executor = executor,
            parameters = listOf(
                ConfigurationParameter(ExternalAgentTools.CLAUDE_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(
                    ExternalAgentTools.CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_SAFE_PARAMETER,
                    JsonPrimitive(true)
                )
            ),
            onMonitorEvent = { type, summary, detail -> events += CapturedEvent(type, summary, detail) },
            claudeCredentialProvider = provider
        )

        assertThrows(ExternalAgentTools.ExternalAgentExecutionException::class.java) {
            runBlocking { tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x")) }
        }
        assertEquals(1, executions)
        assertFalse(events.any { it.type == ExternalAgentTools.CLAUDE_SUBSCRIPTION_RATE_LIMIT_EVENT })
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

    // ------------------------------------------------------------------------
    // Codex subscription mode (CODEX_HOME / auth.json)
    // ------------------------------------------------------------------------

    private val fakeCodexAuthJson =
        """{"OPENAI_API_KEY":null,"auth_mode":"chatgpt","tokens":{"access_token":"a","refresh_token":"r","account_id":"acc"}}"""

    @Test
    fun `codex subscription materializes auth_json into CODEX_HOME and adds ignore-user-config`() = runBlocking {
        val codexHome = Files.createTempDirectory("codex-home-test").toFile()
        try {
            val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val params = listOf(
                // An API key present in subscription mode must be ignored, not leaked.
                ConfigurationParameter("openai_api_key", JsonPrimitive("sk-should-be-ignored")),
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson)),
                ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5")),
                ConfigurationParameter(ExternalAgentTools.CODEX_HOME_DIR_PARAMETER, JsonPrimitive(codexHome.absolutePath))
            )
            val (tools, _) = buildTools(executor, params)

            tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "codex-sub"))

            val req = executor.lastRequest!!
            assertEquals("codex", req.command[0])
            assertEquals("exec", req.command[1])
            assertTrue(req.command.contains("--skip-git-repo-check"))
            assertTrue(req.command.contains("--ignore-user-config"))
            assertTrue(req.command.containsInOrder("--model", "gpt-5.5"))

            // CODEX_HOME points at our dir (native mode) and held the tenant auth.json
            // verbatim while the subprocess ran (captured at execute-time).
            assertEquals(codexHome.absolutePath, req.env["CODEX_HOME"])
            assertEquals(fakeCodexAuthJson, executor.capturedCodexAuthJson)

            // Subscription mode must NOT leak an OPENAI_API_KEY into the child env.
            assertFalse(req.env.containsKey("OPENAI_API_KEY"))

            // An operator-supplied CODEX_HOME is treated as a persistent session dir.
            assertTrue(File(codexHome, "auth.json").exists(), "auth.json should remain in the configured CODEX_HOME")
        } finally {
            codexHome.deleteRecursively()
        }
    }

    @Test
    fun `codex subscription creates a configured CODEX_HOME when it does not exist`() = runBlocking {
        val parent = Files.createTempDirectory("codex-home-parent").toFile()
        val codexHome = File(parent, "nested/configured-home")
        try {
            val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val params = listOf(
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson)),
                ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5")),
                ConfigurationParameter(ExternalAgentTools.CODEX_HOME_DIR_PARAMETER, JsonPrimitive(codexHome.absolutePath))
            )
            val (tools, _) = buildTools(executor, params)

            tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "codex-sub"))

            assertTrue(codexHome.isDirectory)
            assertEquals(fakeCodexAuthJson, File(codexHome, "auth.json").readText())
            assertEquals(codexHome.absolutePath, executor.lastRequest!!.env["CODEX_HOME"])
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `codex subscription keeps default CODEX_HOME for a chat session without a workflow execution`() = runBlocking {
        val parent = File(
            File(File(System.getProperty("java.io.tmpdir"), "braidrun-codex"), "test-user"),
            "chat-session-1"
        )
        try {
            val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val params = listOf(
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson)),
                ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5"))
            )
            val tools = ExternalAgentTools(
                executor = executor,
                parameters = params,
                userId = "test-user",
                context = SubprocessToolContext(workspaceDir = File("."), sessionId = "chat-session-1")
            )

            tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "codex-sub"))

            val home = executor.lastRequest!!.env["CODEX_HOME"]!!
            assertTrue(home.contains("chat-session-1"), "unexpected CODEX_HOME: $home")
            assertTrue(File(home, "auth.json").exists(), "session CODEX_HOME should persist after the run")
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `workflow runtime session id does not disable per invocation CODEX_HOME isolation`() = runBlocking {
        val executionId = "workflow-session-${System.nanoTime()}"
        val parent = File(
            File(File(System.getProperty("java.io.tmpdir"), "braidrun-codex"), "test-user"),
            executionId
        )
        try {
            val params = listOf(
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson)),
                ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5"))
            )
            val firstExecutor = FakeSubprocessExecutor(ExecResult(0, """{"result":"first"}""", "", 1))
            val secondExecutor = FakeSubprocessExecutor(ExecResult(0, """{"result":"second"}""", "", 1))
            val context = SubprocessToolContext(
                workspaceDir = File("."),
                executionId = executionId,
                stepName = "analyze_performance_iterate_0",
                sessionId = "$executionId:asa_keyword_review_agent:analyze_performance_iterate_0"
            )

            ExternalAgentTools(firstExecutor, params, "test-user", context)
                .runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "keyword-reviewer"))
            ExternalAgentTools(secondExecutor, params, "test-user", context)
                .runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "keyword-reviewer"))

            val firstHome = firstExecutor.lastRequest!!.env["CODEX_HOME"]!!
            val secondHome = secondExecutor.lastRequest!!.env["CODEX_HOME"]!!
            assertNotEquals(firstHome, secondHome)
            assertTrue(firstHome.contains("$executionId/analyze_performance_iterate_0_"))
            assertTrue(secondHome.contains("$executionId/analyze_performance_iterate_0_"))
            assertFalse(File(firstHome).exists(), "completed workflow CODEX_HOME should be removed")
            assertFalse(File(secondHome).exists(), "completed workflow CODEX_HOME should be removed")
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `codex subscription default CODEX_HOME is isolated per step invocation`() = runBlocking {
        val parent = File(
            File(File(System.getProperty("java.io.tmpdir"), "braidrun-codex"), "test-user"),
            "exec-1"
        )
        try {
            val params = listOf(
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson)),
                ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5"))
            )
            val firstExecutor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val secondExecutor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))

            val first = ExternalAgentTools(
                executor = firstExecutor,
                parameters = params,
                userId = "test-user",
                context = SubprocessToolContext(workspaceDir = File("."), executionId = "exec-1", stepName = "step:iterate:0")
            )
            val second = ExternalAgentTools(
                executor = secondExecutor,
                parameters = params,
                userId = "test-user",
                context = SubprocessToolContext(workspaceDir = File("."), executionId = "exec-1", stepName = "step:iterate:1")
            )

            first.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "codex-sub"))
            second.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "codex-sub"))

            val firstHome = firstExecutor.lastRequest!!.env["CODEX_HOME"]!!
            val secondHome = secondExecutor.lastRequest!!.env["CODEX_HOME"]!!

            assertNotEquals(firstHome, secondHome)
            assertTrue(firstHome.contains("exec-1/step_iterate_0/"), "unexpected first CODEX_HOME: $firstHome")
            assertTrue(secondHome.contains("exec-1/step_iterate_1/"), "unexpected second CODEX_HOME: $secondHome")
            assertEquals(fakeCodexAuthJson, firstExecutor.capturedCodexAuthJson)
            assertEquals(fakeCodexAuthJson, secondExecutor.capturedCodexAuthJson)
            assertFalse(File(firstHome).exists(), "first invocation CODEX_HOME should be removed after the run")
            assertFalse(File(secondHome).exists(), "second invocation CODEX_HOME should be removed after the run")
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `concurrent codex calls from one step do not delete each others CODEX_HOME`() = runBlocking {
        val executionId = "codex-concurrent-${System.nanoTime()}"
        val parent = File(
            File(File(System.getProperty("java.io.tmpdir"), "braidrun-codex"), "test-user"),
            executionId
        )
        val firstStarted = CompletableDeferred<ExecRequest>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstExecutor = object : SubprocessExecutor {
            override suspend fun execute(request: ExecRequest): ExecResult {
                firstStarted.complete(request)
                releaseFirst.await()
                val home = request.env["CODEX_HOME"]!!
                assertTrue(File(home).isDirectory, "first CODEX_HOME was deleted while its process was running")
                assertEquals(fakeCodexAuthJson, File(home, "auth.json").readText())
                return ExecResult(0, """{"result":"first-ok"}""", "", 1)
            }
        }
        val secondExecutor = FakeSubprocessExecutor(ExecResult(0, """{"result":"second-ok"}""", "", 1))
        val params = listOf(
            ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
            ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson)),
            ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5"))
        )
        val context = SubprocessToolContext(
            workspaceDir = File("."),
            executionId = executionId,
            stepName = "research_website_market_context",
            sessionId = "$executionId:website-market-agent:research_website_market_context"
        )
        val first = ExternalAgentTools(firstExecutor, params, "test-user", context)
        val second = ExternalAgentTools(secondExecutor, params, "test-user", context)

        try {
            val firstRun = async {
                first.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "website-market-researcher"))
            }
            val firstRequest = firstStarted.await()
            val firstHome = firstRequest.env["CODEX_HOME"]!!

            assertEquals(
                "second-ok",
                second.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "website-market-planner"))
            )
            val secondHome = secondExecutor.lastRequest!!.env["CODEX_HOME"]!!

            assertNotEquals(firstHome, secondHome)
            assertTrue(File(firstHome).isDirectory, "sibling cleanup removed the running CODEX_HOME")
            assertFalse(File(secondHome).exists(), "completed sibling CODEX_HOME should be removed")

            releaseFirst.complete(Unit)
            assertEquals("first-ok", firstRun.await())
            assertFalse(File(firstHome).exists(), "first CODEX_HOME should be removed after it completes")
        } finally {
            releaseFirst.complete(Unit)
            parent.deleteRecursively()
        }
    }

    @Test
    fun `codex subscription without an explicit model is rejected`() {
        val executor = FakeSubprocessExecutor(ExecResult(0, "", "", 1))
        val params = listOf(
            ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
            ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson))
        )
        val (tools, _) = buildTools(executor, params)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { tools.runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }
        assertTrue(ex.message!!.contains(ExternalAgentTools.CODEX_MODEL_PARAMETER))
        assertNull(executor.lastRequest, "subprocess must not spawn without an explicit model")
    }

    @Test
    fun `codex subscription without a credential throws a clear error`() {
        val executor = FakeSubprocessExecutor(ExecResult(0, "", "", 1))
        val params = listOf(
            ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
            ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5"))
        )
        val (tools, events) = buildTools(executor, params)

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "codex-noauth")) }
        }
        assertTrue(ex.message!!.contains(ExternalAgentTools.CODEX_SUBSCRIPTION_PROVIDER))
        assertTrue(events.any { it.type == "codex_sub_agent_failed" })
    }

    @Test
    fun `codex subscription rejects a non-json credential (eg an api key pasted by mistake)`() {
        val executor = FakeSubprocessExecutor(ExecResult(0, "", "", 1))
        val params = listOf(
            ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
            ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5")),
            ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive("sk-this-is-an-api-key-not-authjson"))
        )
        val (tools, _) = buildTools(executor, params)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { tools.runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }
        assertTrue(ex.message!!.contains("auth.json"))
    }

    @Test
    fun `codex api_key mode is unaffected by the subscription changes`() = runBlocking {
        val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
        val (tools, _) = buildTools(executor) // default params include openai_api_key, no auth_mode

        tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", model = "gpt-5-codex"))

        val req = executor.lastRequest!!
        assertEquals("sk-test-openai", req.env["OPENAI_API_KEY"])
        assertFalse(req.env.containsKey("CODEX_HOME"))
        assertFalse(req.command.contains("--ignore-user-config"))
        // --skip-git-repo-check is now added unconditionally (safe for non-repo workspaces).
        assertTrue(req.command.contains("--skip-git-repo-check"))
    }

    @Test
    fun `claude subscription config dir is mounted in docker mode`() = runBlocking {
        val configDir = Files.createTempDirectory("claude-config-test").toFile()
        try {
            val executor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val params = listOf(
                ConfigurationParameter(ExternalAgentTools.CLAUDE_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CLAUDE_OAUTH_TOKEN_PARAMETER, JsonPrimitive("oauth-token")),
                ConfigurationParameter(ExternalAgentTools.CLAUDE_CONFIG_DIR_PARAMETER, JsonPrimitive(configDir.absolutePath))
            )
            val (tools, _) = buildTools(executor, params, trustExecutorSandbox = true)

            tools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x"))

            val req = executor.lastRequest!!
            assertEquals(configDir.absolutePath, req.env["CLAUDE_CONFIG_DIR"])
            val permissions = Files.getPosixFilePermissions(configDir.toPath())
            val ownerUid = (Files.getAttribute(configDir.toPath(), "unix:uid") as Number).toInt()
            val sandboxOwned =
                ownerUid == 2000 &&
                    PosixFilePermission.OWNER_WRITE in permissions &&
                    PosixFilePermission.OWNER_EXECUTE in permissions
            val writableFallback =
                PosixFilePermission.OTHERS_WRITE in permissions &&
                    PosixFilePermission.OTHERS_EXECUTE in permissions
            assertTrue(
                sandboxOwned || writableFallback,
                "Claude config dir must be writable by Docker uid 2000: uid=$ownerUid permissions=$permissions"
            )
            assertTrue(
                req.mounts.any {
                    it.hostPath.absolutePath == configDir.canonicalPath &&
                        it.containerPath == configDir.absolutePath &&
                        !it.readOnly
                },
                "expected Claude config dir to be bind-mounted, got: ${req.mounts}"
            )
        } finally {
            configDir.deleteRecursively()
        }
    }

    @Test
    fun `claude subscription default config dir is isolated per step invocation`() = runBlocking {
        val executionId = "claude-config-isolation-${System.nanoTime()}"
        val parent = File(
            File(File(System.getProperty("java.io.tmpdir"), "braidrun-claude"), "test-user"),
            executionId
        )
        try {
            val params = listOf(
                ConfigurationParameter(ExternalAgentTools.CLAUDE_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CLAUDE_OAUTH_TOKEN_PARAMETER, JsonPrimitive("oauth-token"))
            )
            val firstExecutor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val secondExecutor = FakeSubprocessExecutor(ExecResult(0, """{"result":"ok"}""", "", 1))
            val first = ExternalAgentTools(
                executor = firstExecutor,
                parameters = params,
                userId = "test-user",
                context = SubprocessToolContext(
                    workspaceDir = File("."),
                    executionId = executionId,
                    stepName = "analyze_performance:iterate:0"
                )
            )
            val second = ExternalAgentTools(
                executor = secondExecutor,
                parameters = params,
                userId = "test-user",
                context = SubprocessToolContext(
                    workspaceDir = File("."),
                    executionId = executionId,
                    stepName = "orchestrate_account_analysis"
                )
            )

            first.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", name = "keyword-review"))
            second.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "x", name = "account-analysis"))

            val firstConfig = firstExecutor.lastRequest!!.env["CLAUDE_CONFIG_DIR"]!!
            val secondConfig = secondExecutor.lastRequest!!.env["CLAUDE_CONFIG_DIR"]!!
            assertNotEquals(firstConfig, secondConfig)
            assertTrue(firstConfig.endsWith("$executionId/analyze_performance_iterate_0"), firstConfig)
            assertTrue(secondConfig.endsWith("$executionId/orchestrate_account_analysis"), secondConfig)
            assertTrue(File(firstConfig).isDirectory)
            assertTrue(File(secondConfig).isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `codex subscription auth failure surfaces an expired-credential alert`() {
        val codexHome = Files.createTempDirectory("codex-home-expired").toFile()
        try {
            val executor = FakeSubprocessExecutor(
                ExecResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = """ERROR: {"error":{"status":401,"message":"Unauthorized"}}""",
                    durationMs = 50
                )
            )
            val params = listOf(
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
                ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeCodexAuthJson)),
                ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5")),
                ConfigurationParameter(ExternalAgentTools.CODEX_HOME_DIR_PARAMETER, JsonPrimitive(codexHome.absolutePath))
            )
            val (tools, events) = buildTools(executor, params)

            val ex = assertThrows(ExternalAgentTools.ExternalAgentExecutionException::class.java) {
                runBlocking { tools.runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "codex-expired")) }
            }
            assertTrue(
                ex.message!!.contains("expired") || ex.message!!.contains("revoked"),
                "expected an expired/revoked credential hint, got: ${ex.message}"
            )
            assertTrue(
                events.any { it.type == ExternalAgentTools.CODEX_SUBSCRIPTION_EXPIRED_EVENT },
                "expected a codex_subscription_expired alert event"
            )
        } finally {
            codexHome.deleteRecursively()
        }
    }
}

private fun List<String>.containsInOrder(first: String, second: String): Boolean {
    val idx = this.indexOf(first)
    return idx >= 0 && idx + 1 < this.size && this[idx + 1] == second
}
