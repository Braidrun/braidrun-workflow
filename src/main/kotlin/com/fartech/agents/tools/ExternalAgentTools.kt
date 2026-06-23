package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.commons.MonitoringEventCallback
import com.fartech.agents.commons.resolveConfiguredApiKey
import com.fartech.agents.tools.exec.DockerSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import com.fartech.ftapp2.commonsKt.printlnColor
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.io.File
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Toolset for spawning **external agent SDKs as sub-agents** —
 * Anthropic Claude Agent SDK (`claude` CLI) and OpenAI Codex CLI (`codex` CLI).
 *
 * Unlike [SubAgentTools], which builds an in-process Koog `AIAgent` parameterised by an
 * LLM model + tool registry, this tool delegates execution to a *complete external agent
 * runtime*. Claude Code and Codex each ship their own tool-execution loop (Bash, Read,
 * Edit, Glob, Grep, WebFetch, MCP integration), their own context management, and their
 * own LLM client. From the parent agent's perspective both look like a single asynchronous
 * tool call: prompt → string result.
 *
 * ## When to use which
 *
 * - **`runClaudeCodeSubAgent`** — code-heavy tasks that benefit from Claude's strong code
 *   reasoning + the SDK's built-in editing / shell / MCP tooling. Returns final assistant
 *   text plus token / cost telemetry.
 * - **`runCodexSubAgent`** — same shape but routed through OpenAI's Codex CLI, useful when
 *   the operator wants a GPT-class model or the codex sandboxing model.
 *
 * ## Execution model
 *
 * Both tools shell out through the shared [SubprocessExecutor] abstraction, so they inherit
 * every sandbox guarantee shell/code tools already have:
 *
 * - In `subprocess_mode=docker` (production): RO rootfs, UID 2000:2000, `--cap-drop ALL`,
 *   `workflow-egress-only` network, 8 MiB per-stream caps, `--pids-limit 256` (see
 *   [DockerSubprocessExecutor]).
 * - In `subprocess_mode=native` (development): stripped env, 15-item whitelist
 *   (see [com.fartech.agents.tools.exec.NativeSubprocessExecutor]).
 *
 * The host working directory is bind-mounted to `/workspace` so Claude/Codex's
 * file-system operations stay inside the agent's workspace.
 *
 * ## Operator setup
 *
 * The `claude` and `codex` binaries must be reachable. Three deployment options
 * (no new Docker image required):
 *
 *  1. **Pre-install into the existing `braidrun/exec-node` image**:
 *     ```
 *     RUN npm install -g @anthropic-ai/claude-code @openai/codex
 *     ```
 *  2. **Override the command to `npx -y` at runtime** (slower cold start, zero image
 *     change). Set the workflow parameters:
 *     - `external_agent_claude_command=npx`, `external_agent_claude_extra_args=-y,@anthropic-ai/claude-code`
 *     - `external_agent_codex_command=npx`,  `external_agent_codex_extra_args=-y,@openai/codex`
 *  3. **Native dev mode**: `npm install -g` on the host, or `brew install`, etc.
 *
 * ## API key / subscription token resolution
 *
 * Reuses the same precedence chain as the main LLM client factory
 * ([com.fartech.agents.commons.resolveConfiguredApiKey]):
 *
 * 1. Workflow parameter `anthropic_api_key` / `openai_api_key`
 * 2. `llm_provider_keys` map entry
 * 3. Environment variable `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`
 *
 * Either engine can alternatively run against a user's **subscription quota**
 * instead of metered API billing:
 *
 *   - **Claude** — set `external_agent_claude_auth_mode=subscription` and provide a
 *     `claude_code_oauth` credential (a long-lived `claude setup-token` value).
 *     Braidrun injects it as `CLAUDE_CODE_OAUTH_TOKEN` for the child `claude`.
 *   - **Codex** — set `external_agent_codex_auth_mode=subscription` and provide a
 *     `codex_subscription` credential: the **full JSON contents of `~/.codex/auth.json`**
 *     from a `codex login` (ChatGPT) session. Braidrun writes it into a per-run
 *     `CODEX_HOME` (and bind-mounts that dir in Docker), then runs
 *     `codex exec --ignore-user-config` so the CLI resolves auth from there.
 *     Codex subscription mode **requires** an explicit model via
 *     `external_agent_codex_model` (e.g. `gpt-5.5`) or the tool-call `model` field —
 *     a ChatGPT account only accepts its provisioned models. The auth.json token is
 *     not rewritten while valid, so there is no write-back; if it ever expires the
 *     credential must be refreshed (re-`codex login`).
 *
 * Set `external_agent_claude_model` to choose the default Claude Code model
 * (`sonnet`, `opus`, `haiku`, or a full model id); a tool-call `model` field
 * can still override it for one invocation.
 *
 * ⚠️ Using a subscription through a multi-tenant platform is outside the vendors'
 * official terms (which sanction the official client for the account owner, or the
 * paid API for programmatic use); accounts may be rate-limited or banned. Prefer
 * the API-key path when stability matters.
 *
 * The resolved key is injected via [ExecRequest.env]; the rest of the parent JVM's
 * environment is *not* leaked into the subprocess (native executor strips it, docker
 * executor only sees what we pass).
 *
 * ## Routing through OpenRouter (or any compatible proxy)
 *
 * Operators can redirect either SDK to a non-default API endpoint by setting:
 *
 *   - `external_agent_claude_base_url=https://openrouter.ai/api/v1`
 *   - `external_agent_codex_base_url=https://openrouter.ai/api/v1`
 *
 * When set, the corresponding env var (`ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL`)
 * is injected into the subprocess. The `*_api_key` parameter then holds the
 * proxy's key (e.g. an `sk-or-v1-…` OpenRouter token) rather than a direct
 * Anthropic / OpenAI key. **Caveats:**
 *
 *   - Anthropic prompt caching (`cache_control` blocks) is **not guaranteed** to
 *     be preserved through a proxy — Claude Code's multi-turn cost can spike.
 *   - Model names follow the proxy's convention (e.g. OpenRouter uses
 *     `anthropic/claude-sonnet-4-5`, not the bare `claude-sonnet-4-5`).
 *   - Codex CLI uses OpenAI's Responses API; many OpenRouter providers only
 *     expose Chat Completions, so `codex exec` may fail outright through a
 *     proxy. Test before relying on this in production.
 *   - The `cost_usd` field in the SDK's JSON output is provider-derived and
 *     **will be empty / inaccurate** when the call is proxied, so Phase 2's
 *     cost telemetry will degrade for proxied calls.
 *
 * If you only have an OpenRouter key and don't specifically need the SDK's
 * built-in tool loop (Bash/Read/Edit/MCP), prefer the existing `runSubAgent`
 * tool ([SubAgentTools]) — it natively supports OpenRouter without these
 * compatibility issues.
 */
@LLMDescription(
    "Toolset for spawning external coding agents — Anthropic Claude Code and OpenAI Codex — as " +
        "self-contained sub-agents. Each external sub-agent has its own LLM, its own tool loop " +
        "(file ops, shell, MCP), and its own context. Use when the task benefits from a complete " +
        "agentic coding assistant rather than a Koog-driven sub-agent."
)
class ExternalAgentTools(
    private val executor: SubprocessExecutor,
    private val parameters: List<ConfigurationParameter>,
    private val userId: String = "local-user",
    private val context: SubprocessToolContext = SubprocessToolContext(),
    private val onMonitorEvent: MonitoringEventCallback? = null,
    private val trustExecutorSandbox: Boolean = executor is DockerSubprocessExecutor
) : ToolSet {

    private val isDocker: Boolean = trustExecutorSandbox

    private fun emit(type: String, summary: String, detail: String? = null) {
        onMonitorEvent?.invoke(type, summary, detail)
    }

    // ------------------------------------------------------------------------
    // Public tools
    // ------------------------------------------------------------------------

    @Tool
    @LLMDescription(
        "Spawn an Anthropic Claude Code sub-agent (claude CLI / Claude Agent SDK). The sub-agent runs " +
            "with its own LLM and built-in tools (Read/Edit/Bash/Glob/Grep/WebFetch). Best for: " +
            "code generation, refactoring, debugging, multi-file edits, anything that benefits from " +
            "a complete agentic coding workflow. Returns the final assistant text. Token usage and " +
            "USD cost are logged but not included in the return value."
    )
    suspend fun runClaudeCodeSubAgent(
        @LLMDescription("Context for the Claude Code sub-agent")
        agentContext: ExternalAgentContext
    ): String = runExternal(Engine.CLAUDE, agentContext)

    @Tool
    @LLMDescription(
        "Spawn an OpenAI Codex sub-agent (codex CLI). Similar shape to runClaudeCodeSubAgent but " +
            "routed through OpenAI's Codex SDK. Use when the task benefits from a GPT-class model " +
            "or Codex's sandboxing model."
    )
    suspend fun runCodexSubAgent(
        @LLMDescription("Context for the Codex sub-agent")
        agentContext: ExternalAgentContext
    ): String = runExternal(Engine.CODEX, agentContext)

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private enum class Engine(
        val displayName: String,
        val defaultCommand: String,
        val providerKey: String,
        val apiKeyEnvVar: String,
        /**
         * Env var the CLI inspects for a custom API base URL — `ANTHROPIC_BASE_URL`
         * for the Claude Code CLI, `OPENAI_BASE_URL` for the Codex CLI. Letting an
         * operator route through OpenRouter (or any Anthropic-/OpenAI-compatible
         * proxy) is the whole point of the corresponding `*_base_url` parameter.
         */
        val baseUrlEnvVar: String,
        val commandParameterKey: String,
        val extraArgsParameterKey: String,
        /**
         * Workflow parameter the operator sets to override the CLI's default API
         * endpoint. When non-empty, [buildEnvironment] injects [baseUrlEnvVar]
         * with the validated value so the SDK speaks to the override instead of
         * Anthropic / OpenAI directly. Caveats — prompt caching may not be
         * preserved, cost JSON may be empty, and Codex's Responses API isn't
         * supported by all proxies — are documented in the class kdoc.
         */
        val baseUrlParameterKey: String,
        /**
         * Workflow parameter selecting the auth mode (`api_key` | `subscription`).
         * Both engines now support subscription: Claude injects a long-lived
         * `CLAUDE_CODE_OAUTH_TOKEN`, Codex materialises a per-tenant
         * `CODEX_HOME/auth.json`.
         */
        val authModeParameterKey: String
    ) {
        CLAUDE(
            displayName = "Claude Code",
            defaultCommand = "claude",
            providerKey = "anthropic",
            apiKeyEnvVar = "ANTHROPIC_API_KEY",
            baseUrlEnvVar = "ANTHROPIC_BASE_URL",
            commandParameterKey = "external_agent_claude_command",
            extraArgsParameterKey = "external_agent_claude_extra_args",
            baseUrlParameterKey = "external_agent_claude_base_url",
            authModeParameterKey = "external_agent_claude_auth_mode"
        ),
        CODEX(
            displayName = "Codex",
            defaultCommand = "codex",
            providerKey = "openai",
            apiKeyEnvVar = "OPENAI_API_KEY",
            baseUrlEnvVar = "OPENAI_BASE_URL",
            commandParameterKey = "external_agent_codex_command",
            extraArgsParameterKey = "external_agent_codex_extra_args",
            baseUrlParameterKey = "external_agent_codex_base_url",
            authModeParameterKey = "external_agent_codex_auth_mode"
        )
    }

    private enum class ExternalAuthMode(val detailValue: String) {
        API_KEY("api_key"),
        SUBSCRIPTION("subscription");

        companion object {
            fun parse(raw: String?, parameterName: String): ExternalAuthMode {
                val normalized = raw
                    ?.trim()
                    ?.lowercase()
                    ?.replace('-', '_')
                    ?.takeIf { it.isNotEmpty() }
                    ?: return API_KEY

                return when (normalized) {
                    "api", "api_key", "apikey", "direct" -> API_KEY
                    "oauth", "oauth_token", "subscription", "claude_subscription", "codex_subscription" -> SUBSCRIPTION
                    else -> throw IllegalArgumentException(
                        "$parameterName='$raw' is invalid. " +
                            "Use 'api_key' or 'subscription'."
                    )
                }
            }
        }
    }

    private data class ResolvedExternalAuth(
        val mode: ExternalAuthMode,
        val env: Map<String, String>,
        /**
         * Codex subscription mode only: the host directory we materialised the
         * tenant's `auth.json` into. [buildMounts] bind-mounts it into the
         * container at [CODEX_HOME_CONTAINER_PATH]; null for every other mode.
         */
        val codexHomeHostDir: File? = null
    )

    private suspend fun runExternal(engine: Engine, ctx: ExternalAgentContext): String {
        val resolvedName = ctx.name.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val invocationId = UUID.randomUUID().toString().take(8)
        val invocationLabel = invocationLabel(resolvedName, invocationId)
        val workDir = resolveWorkingDir(ctx.workingDir)

        // Resolve authentication BEFORE building any subprocess so misconfiguration fails
        // with a clear error instead of leaving the LLM staring at a 401 from the SDK.
        val auth = runCatching { resolveExternalAuth(engine) }.getOrElse { e ->
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 配置错误: $invocationLabel",
                detail = listOfNotNull("invocation_id=$invocationId", e.message).joinToString(", ")
            )
            throw e
        }

        // Wrap the rest so the per-run Codex auth.json (a live subscription token we
        // materialise on the host) is always removed once the subprocess finishes —
        // on success, failure, or timeout. Avoids leaving customer tokens on disk.
        try {

        // Build the command. Operators can override the binary name (e.g. "npx -y @anthropic-ai/claude-code")
        // by setting external_agent_<engine>_command / _extra_args parameters; default is "claude" / "codex"
        // assumed to be on PATH inside the container or host.
        val effectiveModel = resolveModel(engine, ctx)
        val command = buildCommand(engine, ctx, effectiveModel, auth)

        val timeoutSeconds = ctx.timeoutSeconds
            .coerceAtLeast(MIN_TIMEOUT_SECONDS)
            .coerceAtMost(MAX_TIMEOUT_SECONDS)

        // Resolve the optional base URL once so we can mention it in the start-event
        // detail (operators want to see at a glance whether the SDK is talking to
        // OpenRouter or directly to Anthropic / OpenAI) AND inside buildEnvironment.
        // The double-resolve is intentional — both call sites use the same validator
        // so a malformed URL fails fast before we spawn anything.
        val baseUrlOverride = runCatching { resolveBaseUrlOverride(engine, auth) }.getOrElse { e ->
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 配置错误: $invocationLabel",
                detail = listOfNotNull("invocation_id=$invocationId", e.message).joinToString(", ")
            )
            throw e
        }

        emit(
            type = engine.startEventType(),
            summary = "${engine.startEmoji()} 启动 ${engine.displayName} Sub Agent: $invocationLabel",
            detail = buildString {
                append("invocation_id=$invocationId, ")
                append("model=${effectiveModel.ifBlank { "<sdk-default>" }}, ")
                append("maxTurns=${ctx.maxTurns}, ")
                append("allowedTools=${ctx.allowedTools}, ")
                append("timeout=${timeoutSeconds}s, ")
                append("workDir=${workDir.absolutePath}, ")
                append("executor=${if (isDocker) "docker" else "native"}, ")
                append("authMode=${auth.mode.detailValue}")
                if (baseUrlOverride != null) append(", baseUrl=$baseUrlOverride")
            }
        )

        printlnColor(
            AnsiColor.CYAN,
            "[ExternalAgent] Spawning ${engine.displayName} sub-agent '$resolvedName': " +
                command.joinToString(" ").take(200)
        )

        val claudeStreamEmitter = if (engine == Engine.CLAUDE && onMonitorEvent != null) {
            ClaudeStreamEventEmitter(resolvedName, invocationId)
        } else null

        val request = ExecRequest(
            command = command,
            workingDir = workDir,
            // BOTH engines take the prompt as a positional argv argument (see
            // buildClaudeFlags / buildCodexFlags). We deliberately never use stdin:
            // the Docker executor keeps stdin open (OpenStdin), so a stdin-reading
            // `claude -p` / `codex exec` blocks forever waiting for an EOF that never
            // comes (the "runs 20+ min with no output" hang).
            stdin = null,
            timeoutSeconds = timeoutSeconds.toLong(),
            env = buildEnvironment(engine, auth),
            mounts = buildMounts(auth),
            imageHint = "node",
            userId = userId,
            stdoutLineCallback = claudeStreamEmitter?.let { emitter ->
                { line -> emitter.handleLine(line) }
            }
        )

        val result = try {
            executeWithProgress(
                engine = engine,
                request = request,
                resolvedName = resolvedName,
                invocationId = invocationId,
                model = effectiveModel,
                authMode = auth.mode
            )
        } catch (e: Throwable) {
            logger.error(e) { "[ExternalAgent] ${engine.displayName} sub-agent '$resolvedName' subprocess failed" }
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 失败: $invocationLabel",
                detail = "invocation_id=$invocationId, executor_error: ${e.message}"
            )
            throw e
        }

        // Subprocess timed out — the executors already destroyForcibly + drain
        // the streams and signal via exit code -1 + a "[TIMED OUT after Ns]"
        // marker in stderr (see NativeSubprocessExecutor.kt:108 and
        // DockerSubprocessExecutor's analogue). Surface a structured event so
        // the UI / monitoring layer can distinguish timeout from other failures.
        if (result.exitCode == -1 && result.stderr.contains("TIMED OUT")) {
            emit(
                type = engine.timeoutEventType(),
                summary = "⏱️ ${engine.displayName} Sub Agent 超时: $invocationLabel",
                detail = "invocation_id=$invocationId, timeout=${timeoutSeconds}s, stderr_tail=${result.stderr.takeLast(500)}"
            )
            throw ExternalAgentTimeoutException(
                "${engine.displayName} sub-agent '$resolvedName' timed out after ${timeoutSeconds}s"
            )
        }

        if (result.exitCode != 0) {
            val tail = result.stderr.takeLast(2000).ifBlank { result.stdout.takeLast(2000) }
            // Codex subscription credentials are short-lived OAuth tokens. When the
            // access token can no longer be refreshed (revoked / refresh token expired)
            // codex returns a 401. Surface that as a distinct, actionable alert so the
            // operator knows the fix is "re-run codex login + update the credential",
            // not "retry" or "check the prompt".
            if (engine == Engine.CODEX &&
                auth.mode == ExternalAuthMode.SUBSCRIPTION &&
                isCodexAuthFailure("${result.stderr}\n${result.stdout}")
            ) {
                emit(
                    type = CODEX_SUBSCRIPTION_EXPIRED_EVENT,
                    summary = "🔑 Codex 订阅凭据失效: $invocationLabel",
                    detail = "invocation_id=$invocationId, 该 ChatGPT 订阅凭据已过期或被吊销," +
                        "需重新执行 `codex login` 并更新凭据中心 provider=$CODEX_SUBSCRIPTION_PROVIDER 的 auth.json。" +
                        "stderr_tail=$tail"
                )
                throw ExternalAgentExecutionException(
                    "Codex sub-agent '$resolvedName' failed authentication — the ChatGPT subscription " +
                        "credential ('$CODEX_SUBSCRIPTION_PROVIDER') has expired or been revoked. The user must " +
                        "re-run `codex login` and update the stored auth.json. stderr: $tail"
                )
            }
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 失败: $invocationLabel",
                detail = "invocation_id=$invocationId, exit_code=${result.exitCode}, stderr_tail=$tail"
            )
            throw ExternalAgentExecutionException(
                "${engine.displayName} sub-agent '$resolvedName' exited with code ${result.exitCode}. " +
                    "stderr: $tail"
            )
        }

        // Phase 2: parse usage / cost from the SDK's JSON output and log+emit it.
        // The text the parent LLM sees is just the final assistant message — including
        // raw JSON or usage stats in the return value would pollute the parent's context.
        val parsed = parseSdkOutput(engine, result.stdout)

        logger.info {
            "[ExternalAgent] ${engine.displayName} sub-agent '$resolvedName' completed in ${result.durationMs}ms. " +
                "usage=${parsed.usage}, cost_usd=${parsed.costUsd}, duration_ms=${result.durationMs}"
        }

        emit(
            type = engine.completedEventType(),
            summary = "✅ ${engine.displayName} Sub Agent 完成: $invocationLabel",
            detail = buildString {
                append("invocation_id=$invocationId, duration_ms=${result.durationMs}")
                parsed.costUsd?.let { append(", cost_usd=").append(formatCost(it)) }
                parsed.usage?.let { u ->
                    append(", input_tokens=${u.inputTokens ?: "?"}")
                    append(", output_tokens=${u.outputTokens ?: "?"}")
                    u.cacheReadInputTokens?.let { append(", cache_read=").append(it) }
                    u.cacheCreationInputTokens?.let { append(", cache_creation=").append(it) }
                }
                parsed.sessionId?.let { append(", session_id=").append(it.take(16)) }
                val preview = parsed.text.take(160).replace('\n', ' ')
                append(", preview=").append(preview).append(if (parsed.text.length > 160) "..." else "")
            }
        )

        return parsed.text
        } finally {
            auth.codexHomeHostDir?.let { dir -> runCatching { dir.deleteRecursively() } }
        }
    }

    private suspend fun executeWithProgress(
        engine: Engine,
        request: ExecRequest,
        resolvedName: String,
        invocationId: String,
        model: String,
        authMode: ExternalAuthMode
    ): SubprocessExecutor.ExecResult {
        if (engine != Engine.CLAUDE || onMonitorEvent == null) {
            return executor.execute(request)
        }

        return coroutineScope {
            val startedAt = System.currentTimeMillis()
            val intervalMs = resolveClaudeProgressIntervalMs()
            val progressJob = launch {
                while (true) {
                    delay(intervalMs)
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    val elapsedSeconds = elapsedMs / 1000
                    emit(
                        type = engine.progressEventType(),
                        summary = "⏳ ${engine.displayName} Sub Agent 正在思考: ${invocationLabel(resolvedName, invocationId)} (${elapsedSeconds}s)",
                        detail = buildString {
                            append("invocation_id=$invocationId, ")
                            append("phase=running, ")
                            append("elapsed_ms=$elapsedMs, ")
                            append("model=${model.ifBlank { "<sdk-default>" }}, ")
                            append("authMode=${authMode.detailValue}, ")
                            append("executor=${if (isDocker) "docker" else "native"}")
                        }
                    )
                }
            }

            try {
                executor.execute(request)
            } finally {
                progressJob.cancelAndJoin()
            }
        }
    }

    private inner class ClaudeStreamEventEmitter(
        private val resolvedName: String,
        private val invocationId: String
    ) {
        private val label = invocationLabel(resolvedName, invocationId)
        private var lastAssistantText = ""
        private val seenToolCalls = linkedSetOf<String>()
        private val seenToolResults = linkedSetOf<String>()
        private var systemEventEmitted = false

        fun handleLine(line: String) {
            val obj = parseJsonObjectOrNull(line) ?: return
            when (obj.stringField("type")) {
                "system" -> handleSystem(obj)
                "assistant" -> handleAssistant(obj)
                "user" -> handleUser(obj)
                "result" -> handleResult(obj)
                "hook" -> handleHook(obj)
                else -> handleGeneric(obj)
            }
        }

        private fun handleSystem(obj: JsonObject) {
            if (systemEventEmitted) return
            systemEventEmitted = true
            val model = obj.stringField("model")
            val sessionId = obj.stringField("session_id")
            emit(
                type = "claude_code_stream_system",
                summary = "⚙️ Claude Code 会话已初始化: $label",
                detail = listOfNotNull(
                    "invocation_id=$invocationId",
                    model?.let { "model=$it" },
                    sessionId?.let { "session_id=${it.take(16)}" },
                    obj.stringField("cwd")?.let { "cwd=$it" }
                ).joinToString(", ").ifBlank { null }
            )
        }

        private fun handleAssistant(obj: JsonObject) {
            val message = (obj["message"] as? JsonObject) ?: obj
            val content = message["content"] as? JsonArray ?: obj["content"] as? JsonArray ?: return
            val textParts = mutableListOf<String>()

            content.mapNotNull { it as? JsonObject }.forEach { part ->
                when (part.stringField("type")) {
                    "text" -> part.stringField("text")?.let { textParts += it }
                    "tool_use" -> handleToolUse(part)
                    "thinking", "reasoning" -> handleReasoningSummary(part)
                }
            }

            val text = textParts.joinToString("")
            if (text.isNotBlank()) emitAssistantDelta(text)
        }

        private fun handleUser(obj: JsonObject) {
            val message = (obj["message"] as? JsonObject) ?: obj
            val content = message["content"] as? JsonArray ?: obj["content"] as? JsonArray ?: return
            content.mapNotNull { it as? JsonObject }
                .filter { it.stringField("type") == "tool_result" }
                .forEach { handleToolResult(it) }
        }

        private fun handleResult(obj: JsonObject) {
            val result = obj.stringField("result")?.trim().orEmpty()
            emit(
                type = "claude_code_stream_result",
                summary = buildSummaryWithPreview("🏁 Claude Code 最终结果: $label", result.ifBlank { obj.stringField("subtype") ?: "done" }),
                detail = buildString {
                    append("invocation_id=$invocationId")
                    obj.stringField("subtype")?.let { append(", subtype=$it") }
                    obj.stringField("session_id")?.let {
                        append(", session_id=${it.take(16)}")
                    }
                    obj.doubleField("cost_usd")?.let {
                        append(", cost_usd=${formatCost(it)}")
                    }
                }.ifBlank { null }
            )
        }

        private fun handleHook(obj: JsonObject) {
            val name = obj.stringField("hook") ?: obj.stringField("name") ?: obj.stringField("subtype") ?: "hook"
            emit(
                type = "claude_code_stream_hook",
                summary = "🪝 Claude Code Hook: $label / $name",
                detail = "invocation_id=$invocationId, payload=${compactJson(obj, 600)}"
            )
        }

        private fun handleGeneric(obj: JsonObject) {
            val type = obj.stringField("type") ?: return
            if (type.endsWith("_delta")) {
                val text = obj.stringField("text")
                    ?: obj.stringField("delta")
                    ?: (obj["delta"] as? JsonObject)?.stringField("text")
                if (!text.isNullOrBlank()) {
                    emit(
                        type = "claude_code_stream_text_delta",
                        summary = buildSummaryWithPreview("💬 Claude Code 输出: $label", text),
                        detail = truncateDetail("invocation_id=$invocationId\n$text")
                    )
                }
            }
        }

        private fun handleToolUse(part: JsonObject) {
            val id = part.stringField("id") ?: part.stringField("tool_use_id") ?: part.toString().take(80)
            if (!seenToolCalls.add(id)) return
            val name = part.stringField("name") ?: "tool"
            emit(
                type = "claude_code_stream_tool_call",
                summary = "🔧 Claude Code 调用工具: $label / $name",
                detail = buildString {
                    append("invocation_id=$invocationId, id=$id")
                    part["input"]?.let { append(", input=").append(compactJson(it, 900)) }
                }
            )
        }

        private fun handleToolResult(part: JsonObject) {
            val id = part.stringField("tool_use_id") ?: part.stringField("id") ?: part.toString().take(80)
            val content = extractToolResultContent(part)
            val fingerprint = "$id:${content.take(120)}"
            if (!seenToolResults.add(fingerprint)) return
            emit(
                type = "claude_code_stream_tool_result",
                summary = buildSummaryWithPreview("📥 Claude Code 工具结果: $label", content.ifBlank { id }),
                detail = truncateDetail("invocation_id=$invocationId\n${content.ifBlank { compactJson(part, 900) }}")
            )
        }

        private fun handleReasoningSummary(part: JsonObject) {
            val text = part.stringField("text") ?: part.stringField("summary") ?: return
            if (text.isBlank()) return
            emit(
                type = "claude_code_stream_reasoning_summary",
                summary = buildSummaryWithPreview("💭 Claude Code 思考摘要: $label", text),
                detail = truncateDetail("invocation_id=$invocationId\n$text")
            )
        }

        private fun emitAssistantDelta(text: String) {
            val delta = when {
                lastAssistantText.isEmpty() -> text
                text.startsWith(lastAssistantText) -> text.removePrefix(lastAssistantText)
                else -> text
            }.trim()
            lastAssistantText = text
            if (delta.isBlank()) return
            emit(
                type = "claude_code_stream_text_delta",
                summary = buildSummaryWithPreview("💬 Claude Code 输出: $label", delta),
                detail = truncateDetail("invocation_id=$invocationId\n$delta")
            )
        }
    }

    private fun resolveWorkingDir(requested: String?): File {
        if (!requested.isNullOrBlank()) {
            val dir = File(requested)
            if (dir.isDirectory) return dir
            logger.warn {
                "[ExternalAgent] Requested workingDir '$requested' is not a directory; " +
                    "falling back to parent workspace."
            }
        }
        return context.workspaceDir?.takeIf { it.isDirectory } ?: File(".")
    }

    private fun invocationLabel(name: String, invocationId: String): String = "$name#$invocationId"

    private fun buildCommand(
        engine: Engine,
        ctx: ExternalAgentContext,
        model: String,
        auth: ResolvedExternalAuth
    ): List<String> {
        val baseCommand = parameters.parameter(engine.commandParameterKey, engine.defaultCommand)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: engine.defaultCommand

        // Operator-supplied extra args come BEFORE engine-specific flags so something like
        // `external_agent_claude_command=npx`, `_extra_args=-y,@anthropic-ai/claude-code`
        // produces `npx -y @anthropic-ai/claude-code -p ...` (npx then forwards remaining args).
        val extraArgs = parameters
            .parameter(engine.extraArgsParameterKey, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val cliFlags = when (engine) {
            Engine.CLAUDE -> buildClaudeFlags(ctx, model)
            Engine.CODEX -> buildCodexFlags(ctx, model, auth.mode == ExternalAuthMode.SUBSCRIPTION)
        }

        return buildList {
            add(baseCommand)
            addAll(extraArgs)
            addAll(cliFlags)
        }
    }

    private fun buildClaudeFlags(ctx: ExternalAgentContext, model: String): List<String> = buildList {
        // `-p` (= --print) runs Claude non-interactively. The prompt is passed as
        // the positional argument at the end rather than via stdin: the Docker
        // subprocess executor attaches stdin only after the container has already
        // started, so `claude -p` reading from stdin sees EOF and aborts with
        // "Input must be provided either through stdin or as a prompt argument".
        // Passing it as argv sidesteps that; per-arg length limits (ARG_MAX) are
        // not a concern for the prompt sizes we send.
        add("-p")
        val extraAllowedDirs = claudeAdditionalAllowedDirs()
        if (extraAllowedDirs.isNotEmpty()) {
            add("--add-dir")
            addAll(extraAllowedDirs)
        }
        val appendSystemPrompt = claudeAppendSystemPrompt(ctx)
        if (appendSystemPrompt.isNotBlank()) {
            add("--append-system-prompt")
            add(appendSystemPrompt)
        }
        add("--output-format")
        add("stream-json")
        // Claude CLI rejects `-p --output-format=stream-json` unless --verbose is set
        // ("Error: When using --print, --output-format=stream-json requires --verbose").
        add("--verbose")
        add("--include-partial-messages")
        add("--include-hook-events")
        if (trustExecutorSandbox) {
            add("--dangerously-skip-permissions")
        }
        if (model.isNotBlank()) {
            add("--model")
            add(model)
        }
        if (ctx.maxTurns > 0) {
            add("--max-turns")
            add(ctx.maxTurns.toString())
        }
        if (ctx.allowedTools.isNotEmpty()) {
            add("--allowedTools")
            add(ctx.allowedTools.joinToString(","))
        }
        if (ctx.disallowedTools.isNotEmpty()) {
            add("--disallowedTools")
            add(ctx.disallowedTools.joinToString(","))
        }
        // `--` terminates option parsing. Without it, claude's *variadic* flags
        // (--allowedTools / --disallowedTools / --add-dir) swallow the trailing
        // positional prompt, so claude aborts with
        // "Error: Input must be provided either through stdin or as a prompt argument
        // when using --print". Verified against claude 2.1.x. Must stay immediately
        // before the positional prompt.
        add("--")
        // Positional prompt — must come after the flags.
        add(ctx.prompt)
    }

    private fun buildCodexFlags(
        ctx: ExternalAgentContext,
        model: String,
        subscription: Boolean
    ): List<String> = buildList {
        add("exec")
        // Allow running outside a git repo — the bind-mounted workspace is usually
        // not a git checkout, and `codex exec` otherwise refuses to start.
        add("--skip-git-repo-check")
        if (trustExecutorSandbox) {
            // In production, Codex already runs inside Braidrun's Docker sandbox
            // (read-only rootfs, non-root UID, restricted network, bounded streams).
            // Skip Codex's own interactive approval prompts so workflow steps cannot
            // wedge waiting for an operator inside a headless subprocess.
            add("--dangerously-bypass-approvals-and-sandbox")
        }
        if (subscription) {
            // The per-tenant CODEX_HOME holds only the tenant's auth.json — no
            // config.toml. `--ignore-user-config` skips the (absent) config while
            // STILL resolving auth from CODEX_HOME (per `codex exec --help`:
            // "auth still uses CODEX_HOME"), and avoids inheriting any operator-host
            // config that could pin an unsupported model.
            add("--ignore-user-config")
            // A ChatGPT subscription only accepts the model(s) provisioned for that
            // account; codex's built-in default is frequently NOT one of them and
            // fails with a 400. Force the operator to pick one explicitly.
            require(model.isNotBlank()) {
                "Codex subscription mode requires an explicit model — a ChatGPT subscription " +
                    "only accepts the models provisioned for that account (e.g. gpt-5.5). " +
                    "Set parameter '$CODEX_MODEL_PARAMETER' or the tool-call 'model' field."
            }
        }
        if (model.isNotBlank()) {
            add("--model")
            add(model)
        }
        // --full-auto / --json flags are codex-version-dependent; operators can append
        // them through external_agent_codex_extra_args until we standardise across
        // versions.
        //
        // Pass the prompt as a POSITIONAL argument (`codex exec [OPTIONS] [PROMPT]`),
        // not via stdin. The Docker executor keeps stdin open (OpenStdin), so a
        // stdin-reading `codex exec` blocks forever waiting for an EOF that never
        // comes — the cause of the "runs for 20+ min, no output" hang. `--`
        // terminates option parsing so the prompt can't be taken as a subcommand
        // (resume / review) or swallowed by a flag.
        add("--")
        add(ctx.prompt)
    }

    private fun buildEnvironment(engine: Engine, auth: ResolvedExternalAuth): Map<String, String> = buildMap {
        putAll(auth.env)
        // Codex writes session / cache / state into CODEX_HOME (default ~/.codex) and
        // touches HOME on startup. In the read-only-rootfs container both default to
        // the non-writable /home/runner, so codex aborts with "Permission denied
        // (os error 13)" regardless of auth mode. Point both at the writable tmpfs.
        // Subscription mode already set CODEX_HOME to the bind-mounted auth dir
        // (putIfAbsent keeps it); API-key mode gets a fresh tmpfs CODEX_HOME.
        if (engine == Engine.CODEX && isDocker) {
            putIfAbsent("CODEX_HOME", "/tmp/codex-home")
            put("HOME", "/tmp")
        }
        // Optional custom API base URL — lets the operator route the SDK through
        // OpenRouter (or any Anthropic-/OpenAI-compatible proxy). Validated to
        // reject obviously-broken values so a typo in the workflow YAML doesn't
        // silently disable the override.
        resolveBaseUrlOverride(engine, auth)?.let { baseUrl -> put(engine.baseUrlEnvVar, baseUrl) }
        val outputPath = when {
            context.outputDir == null -> null
            isDocker -> "/output"
            else -> context.outputDir.absolutePath
        }
        // Surface workspace identity, mirroring ShellTools so any audit / log integration in
        // the user's external SDK config can pick them up.
        put("BRAIDRUN_USER_ID", userId)
        put("BRAIDRUN_WORKSPACE", if (isDocker) "/workspace" else (context.workspaceDir?.absolutePath ?: "."))
        outputPath?.let { put("BRAIDRUN_OUTPUT_DIR", it) }
        context.executionId?.let { put("BRAIDRUN_EXECUTION_ID", it) }
        context.stepName?.let { put("BRAIDRUN_STEP_NAME", it) }
        context.sessionId?.let { put("BRAIDRUN_SESSION_ID", it) }
    }

    /**
     * Resolve the operator-supplied base URL override for [engine], validating it
     * to https:// (or a localhost http:// for development). Returns null when the
     * parameter is unset, so callers can treat that as "use the SDK's default".
     *
     * SSRF is **not** a concern here in the same way it is for [WebTools] — the
     * value comes from an authenticated workflow author setting a CLI proxy,
     * not from an LLM prompt or end-user input. The validation exists to catch
     * typos (`htps://`, `://`) before they surface as opaque CLI errors.
     */
    private fun resolveBaseUrlOverride(engine: Engine, auth: ResolvedExternalAuth): String? {
        val raw = parameters.parameter(engine.baseUrlParameterKey, "").trim()
        if (raw.isEmpty()) return null
        require(auth.mode == ExternalAuthMode.API_KEY) {
            "${engine.baseUrlParameterKey} cannot be used when " +
                "${engine.authModeParameterKey}=subscription. Subscription credentials must be sent " +
                "directly to the agent CLI, not to a custom API proxy."
        }
        require(BASE_URL_REGEX.matches(raw)) {
            "${engine.baseUrlParameterKey}='$raw' must be a fully-qualified URL " +
                "(https:// or http://localhost[:port] for dev)"
        }
        return raw
    }

    private fun buildMounts(auth: ResolvedExternalAuth): List<SubprocessExecutor.Mount> {
        if (!isDocker) return emptyList()
        val mounts = mutableListOf<SubprocessExecutor.Mount>()
        // Codex subscription: bind the host dir holding the tenant's auth.json into
        // the container so `codex` can read it via CODEX_HOME. RW because codex
        // writes cache / session / state files there during the run.
        auth.codexHomeHostDir?.let { homeDir ->
            val canonical = runCatching { homeDir.canonicalFile }.getOrDefault(homeDir.absoluteFile)
            mounts += SubprocessExecutor.Mount(
                hostPath = canonical,
                containerPath = CODEX_HOME_CONTAINER_PATH,
                readOnly = false
            )
        }
        context.outputDir
            ?.takeIf { it.exists() || it.mkdirs() }
            ?.let { outputDir ->
                val canonicalOutputDir = runCatching { outputDir.canonicalFile }.getOrDefault(outputDir.absoluteFile)
                mounts += SubprocessExecutor.Mount(
                    hostPath = canonicalOutputDir,
                    containerPath = "/output",
                    readOnly = false
                )

                val hostVisibleOutputPath = canonicalOutputDir.absolutePath
                if (hostVisibleOutputPath != "/output") {
                    mounts += SubprocessExecutor.Mount(
                        hostPath = canonicalOutputDir,
                        containerPath = hostVisibleOutputPath,
                        readOnly = false
                    )
                }
        }
        // For now we don't mount /skills for external agents. Future enhancement: mount
        // /skills RO when the operator opts in via parameter, to share braidrun skills
        // with Claude Code's skill loader.
        return mounts
    }

    private fun claudeAdditionalAllowedDirs(): List<String> {
        val dirs = linkedSetOf<String>()
        if (!isDocker) {
            context.outputDir
                ?.takeIf { it.exists() && it.isDirectory }
                ?.let { outputDir ->
                    dirs += outputDir.canonicalPath
                }
            return dirs.toList()
        }

        dirs += "/output"
        context.outputDir
            ?.takeIf { (it.exists() && it.isDirectory) || it.mkdirs() }
            ?.let { outputDir ->
                dirs += runCatching { outputDir.canonicalPath }.getOrDefault(outputDir.absolutePath)
            }
        return dirs.toList()
    }

    private fun claudeAppendSystemPrompt(ctx: ExternalAgentContext): String {
        val workflowPrompt = ctx.systemPrompt.trim()
        val configuredPrompt = parameters.parameter(CLAUDE_APPEND_SYSTEM_PROMPT_PARAMETER, "").trim()
        return listOf(
            workflowPrompt,
            configuredPrompt,
            DEFAULT_CLAUDE_CHINESE_SYSTEM_PROMPT
        )
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun resolveApiKey(engine: Engine): String? {
        val keys = parameters.parameter("llm_provider_keys", mapOf<String, String>())
        return resolveConfiguredApiKey(parameters, engine.providerKey, keys)
    }

    private fun resolveModel(engine: Engine, ctx: ExternalAgentContext): String {
        val requested = ctx.model.trim()
        if (requested.isNotEmpty()) return requested
        return when (engine) {
            Engine.CLAUDE -> parameters.parameter(CLAUDE_MODEL_PARAMETER, "").trim()
            Engine.CODEX -> parameters.parameter(CODEX_MODEL_PARAMETER, "").trim()
        }
    }

    private fun resolveClaudeProgressIntervalMs(): Long {
        val raw = parameters.parameter(
            CLAUDE_PROGRESS_INTERVAL_SECONDS_PARAMETER,
            DEFAULT_CLAUDE_PROGRESS_INTERVAL_SECONDS.toString()
        )
        val seconds = raw
            .trim()
            .toDoubleOrNull()
            ?: DEFAULT_CLAUDE_PROGRESS_INTERVAL_SECONDS
        return (seconds
            .coerceAtLeast(MIN_CLAUDE_PROGRESS_INTERVAL_SECONDS)
            .coerceAtMost(MAX_CLAUDE_PROGRESS_INTERVAL_SECONDS) * 1000).toLong()
    }

    private fun resolveExternalAuth(engine: Engine): ResolvedExternalAuth {
        val mode = ExternalAuthMode.parse(
            parameters.parameter(engine.authModeParameterKey, "api_key"),
            engine.authModeParameterKey
        )

        if (mode == ExternalAuthMode.SUBSCRIPTION) {
            return when (engine) {
                Engine.CLAUDE -> resolveClaudeSubscriptionAuth()
                Engine.CODEX -> resolveCodexSubscriptionAuth()
            }
        }

        val apiKey = resolveApiKey(engine)
            ?: throw IllegalStateException(
                "Missing API key for ${engine.displayName}. " +
                    "Set workflow parameter '${engine.providerKey}_api_key', " +
                    "or 'llm_provider_keys.${engine.providerKey}', " +
                    "or environment variable ${engine.apiKeyEnvVar}."
            )
        return ResolvedExternalAuth(
            mode = ExternalAuthMode.API_KEY,
            env = mapOf(engine.apiKeyEnvVar to apiKey)
        )
    }

    private fun resolveClaudeSubscriptionAuth(): ResolvedExternalAuth {
        val token = resolveClaudeCodeOAuthToken()
            ?: throw IllegalStateException(
                "Missing Claude subscription OAuth token. Create a credential with provider " +
                    "'$CLAUDE_CODE_OAUTH_PROVIDER', or set workflow parameter " +
                    "'$CLAUDE_OAUTH_TOKEN_PARAMETER'."
            )
        return ResolvedExternalAuth(
            mode = ExternalAuthMode.SUBSCRIPTION,
            env = buildMap {
                put("CLAUDE_CODE_OAUTH_TOKEN", token)
                put("CLAUDE_CONFIG_DIR", resolveClaudeConfigDir())
                put("DISABLE_AUTOUPDATER", "1")
            }
        )
    }

    /**
     * Codex subscription auth: unlike Claude (a single long-lived token in an env
     * var), the `codex` CLI only reads ChatGPT credentials from `CODEX_HOME/auth.json`.
     * We write the tenant's `auth.json` into a per-run dir and point `CODEX_HOME` at
     * it. Verified locally: a transplanted auth.json + `--ignore-user-config` is
     * accepted, and codex does NOT rewrite the file while the token is valid, so no
     * write-back channel is required.
     */
    private fun resolveCodexSubscriptionAuth(): ResolvedExternalAuth {
        val authJson = resolveCodexAuthJson()
            ?: throw IllegalStateException(
                "Missing Codex subscription credential. Create a credential with provider " +
                    "'$CODEX_SUBSCRIPTION_PROVIDER' (the contents of ~/.codex/auth.json from a " +
                    "`codex login` ChatGPT session), or set workflow parameter " +
                    "'$CODEX_AUTH_JSON_PARAMETER'."
            )
        val homeDir = materializeCodexHome(authJson)
        val codexHomeEnv = if (isDocker) CODEX_HOME_CONTAINER_PATH else homeDir.absolutePath
        // HOME (and an API-key-mode CODEX_HOME fallback) are set centrally for codex in
        // [buildEnvironment]; here we only pin CODEX_HOME to the materialised auth dir.
        return ResolvedExternalAuth(
            mode = ExternalAuthMode.SUBSCRIPTION,
            env = mapOf("CODEX_HOME" to codexHomeEnv),
            codexHomeHostDir = homeDir
        )
    }

    private fun resolveCodexAuthJson(): String? {
        normalizeCodexAuthJson(parameters.parameter(CODEX_AUTH_JSON_PARAMETER, ""))?.let { return it }

        val keys = parameters.parameter("llm_provider_keys", mapOf<String, String>())
        CODEX_SUBSCRIPTION_PROVIDER_ALIASES.forEach { alias ->
            normalizeCodexAuthJson(keys[alias])?.let { return it }
        }
        return null
    }

    /**
     * Validate that the supplied value is a Codex `auth.json` blob (not an API key
     * pasted by mistake). Only trims — unlike the Claude OAuth token this is JSON, so
     * collapsing internal whitespace would corrupt it.
     */
    private fun normalizeCodexAuthJson(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val obj = runCatching { permissiveJson.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException(
                "Codex subscription credential must be the JSON contents of ~/.codex/auth.json, " +
                    "but the supplied value is not valid JSON."
            )
        require(obj["tokens"] is JsonObject) {
            "Codex subscription credential is missing the 'tokens' object — paste the full " +
                "~/.codex/auth.json from a `codex login` (ChatGPT) session, not an API key."
        }
        return trimmed
    }

    /**
     * Create a per-invocation host directory, write the tenant's `auth.json` into it (owner
     * read/write only), and return it. In Docker mode [buildMounts] bind-mounts this
     * dir to [CODEX_HOME_CONTAINER_PATH]; in native mode `CODEX_HOME` points straight
     * at it.
     */
    private fun materializeCodexHome(authJson: String): File {
        val configured = parameters.parameter(CODEX_HOME_DIR_PARAMETER, "").trim()
        val base = if (configured.isNotEmpty()) {
            File(configured)
        } else {
            val runId = context.executionId ?: context.sessionId ?: UUID.randomUUID().toString()
            val invocationId = listOfNotNull(context.stepName, context.sessionId)
                .joinToString("_")
                .ifBlank { UUID.randomUUID().toString() }
            val userDir = File(
                File(System.getProperty("java.io.tmpdir"), "braidrun-codex"),
                safePathSegment(userId)
            )
            File(
                File(userDir, safePathSegment(runId)),
                safePathSegment(invocationId)
            )
        }
        if (!base.isDirectory && !base.mkdirs()) {
            throw IllegalStateException("Unable to create Codex home dir: ${base.absolutePath}")
        }
        val authFile = File(base, "auth.json")
        authFile.writeText(authJson)
        applyCodexHomePermissions(base, authFile)
        return base
    }

    /**
     * Docker mode runs the codex container as an unprivileged uid (2000:2000) that
     * differs from the agent JVM which created this dir. The bind-mounted CODEX_HOME
     * must therefore be writable by that other uid (codex writes cache / session /
     * state files into it) and the auth.json readable by it — otherwise codex aborts
     * with "Permission denied (os error 13)". So we open the perms up (the dir is a
     * per-run host temp dir that gets cleaned up). In native mode the same uid runs
     * codex, so keep it owner-only (0700 / 0600) to limit token exposure.
     */
    private fun applyCodexHomePermissions(dir: File, authFile: File) {
        runCatching {
            if (isDocker) {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
                authFile.setReadable(true, false)
                authFile.setWritable(true, false)
            } else {
                authFile.setReadable(false, false)
                authFile.setWritable(false, false)
                authFile.setReadable(true, true)
                authFile.setWritable(true, true)
            }
        }
    }

    private fun resolveClaudeCodeOAuthToken(): String? {
        val direct = parameters.parameter(CLAUDE_OAUTH_TOKEN_PARAMETER, "")
        normalizeOAuthToken(direct)?.let { return it }

        val keys = parameters.parameter("llm_provider_keys", mapOf<String, String>())
        CLAUDE_CODE_OAUTH_PROVIDER_ALIASES.forEach { alias ->
            normalizeOAuthToken(keys[alias])?.let { return it }
        }

        return null
    }

    private fun normalizeOAuthToken(value: String?): String? {
        return value
            ?.replace(Regex("\\s+"), "")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun resolveClaudeConfigDir(): String {
        val configured = parameters.parameter(CLAUDE_CONFIG_DIR_PARAMETER, "").trim()
        if (configured.isNotEmpty()) return configured

        val base = if (isDocker) {
            File("/tmp/braidrun-claude")
        } else {
            File(System.getProperty("java.io.tmpdir"), "braidrun-claude")
        }
        val runId = context.executionId ?: context.sessionId ?: UUID.randomUUID().toString()
        return File(File(base, safePathSegment(userId)), safePathSegment(runId)).absolutePath
    }

    private fun safePathSegment(value: String): String {
        return value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "unknown" }
    }

    // ------------------------------------------------------------------------
    // Output parsing
    // ------------------------------------------------------------------------

    /**
     * Parsed external-SDK output. `text` is what gets returned to the parent agent;
     * the rest is captured for telemetry only.
     */
    internal data class ParsedSdkOutput(
        val text: String,
        val usage: TokenUsage?,
        val costUsd: Double?,
        val sessionId: String?
    )

    internal data class TokenUsage(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val cacheCreationInputTokens: Long?,
        val cacheReadInputTokens: Long?
    )

    private fun parseSdkOutput(engine: Engine, stdout: String): ParsedSdkOutput {
        return when (engine) {
            Engine.CLAUDE -> parseClaudeOutput(stdout)
            Engine.CODEX -> parseCodexOutput(stdout)
        }
    }

    private fun parseJsonObjectOrNull(text: String): JsonObject? =
        runCatching { permissiveJson.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun buildSummaryWithPreview(prefix: String, content: String?, maxLength: Int = 120): String {
        val normalized = content
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        if (normalized.isBlank()) return prefix
        return "$prefix: " + if (normalized.length > maxLength) "${normalized.take(maxLength)}..." else normalized
    }

    private fun truncateDetail(content: String?, maxLength: Int = 2000): String? {
        val trimmed = content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.length > maxLength) trimmed.take(maxLength) + "..." else trimmed
    }

    private fun compactJson(element: JsonElement, maxLength: Int = 1000): String {
        val raw = element.toString().replace('\n', ' ')
        return if (raw.length > maxLength) raw.take(maxLength) + "..." else raw
    }

    private fun extractToolResultContent(part: JsonObject): String {
        val content = part["content"] ?: return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("\n") { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull.orEmpty()
                    is JsonObject -> item.stringField("text") ?: item.stringField("content") ?: compactJson(item, 500)
                    else -> item.toString()
                }
            }.trim()
            else -> compactJson(content, 1000)
        }
    }

    /**
     * Claude CLI `--output-format json` emits a single JSON object; `stream-json`
     * emits one JSON object per line and ends with a `type=result` record. The
     * relevant result fields we observe in practice:
     *
     * ```
     * {
     *   "type": "result",
     *   "subtype": "success",
     *   "result": "<final assistant text>",
     *   "session_id": "...",
     *   "cost_usd": 0.0123,
     *   "duration_ms": 12345,
     *   "usage": {
     *     "input_tokens": 1234,
     *     "output_tokens": 567,
     *     "cache_creation_input_tokens": 0,
     *     "cache_read_input_tokens": 0
     *   }
     * }
     * ```
     *
     * Defensive parsing: if the output isn't valid JSON or the `result` field is
     * missing, fall back to the raw stdout. SDK versions change; we don't want a
     * format drift to silently break the tool.
     */
    private fun parseClaudeOutput(stdout: String): ParsedSdkOutput {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) {
            return ParsedSdkOutput(text = "", usage = null, costUsd = null, sessionId = null)
        }
        val obj = parseJsonObjectOrNull(trimmed)
            ?: trimmed
                .lineSequence()
                .mapNotNull { parseJsonObjectOrNull(it.trim()) }
                .lastOrNull { it.stringField("type") == "result" }
        if (obj == null) {
            logger.debug { "[ExternalAgent] Claude output was not JSON; returning raw stdout." }
            return ParsedSdkOutput(text = trimmed, usage = null, costUsd = null, sessionId = null)
        }
        val result = obj.stringField("result") ?: trimmed
        val sessionId = obj.stringField("session_id")
        val costUsd = obj.doubleField("cost_usd") ?: obj.doubleField("total_cost_usd")
        val usage = obj["usage"]?.let { it as? JsonObject }?.let { usageObj ->
            TokenUsage(
                inputTokens = usageObj.longField("input_tokens"),
                outputTokens = usageObj.longField("output_tokens"),
                cacheCreationInputTokens = usageObj.longField("cache_creation_input_tokens"),
                cacheReadInputTokens = usageObj.longField("cache_read_input_tokens")
            )
        }
        return ParsedSdkOutput(text = result, usage = usage, costUsd = costUsd, sessionId = sessionId)
    }

    /**
     * Codex CLI output format is less stable than Claude's at the time of writing — some
     * versions emit JSON lines, others emit plain text. Strategy:
     *
     *  1. Try to parse the last non-empty line as JSON; if it has a result-like field,
     *     use it.
     *  2. Otherwise treat the entire stdout as the assistant text.
     *
     * Usage / cost telemetry is optional — if the CLI doesn't emit it in a parseable
     * form, we degrade silently rather than fail the tool call.
     */
    private fun parseCodexOutput(stdout: String): ParsedSdkOutput {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) {
            return ParsedSdkOutput(text = "", usage = null, costUsd = null, sessionId = null)
        }
        val lastLine = trimmed.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        val obj = runCatching { permissiveJson.parseToJsonElement(lastLine).jsonObject }.getOrNull()
        if (obj == null) {
            return ParsedSdkOutput(text = trimmed, usage = null, costUsd = null, sessionId = null)
        }
        val resultText = obj.stringField("result")
            ?: obj.stringField("output")
            ?: obj.stringField("text")
            ?: trimmed
        val costUsd = obj.doubleField("cost_usd") ?: obj.doubleField("total_cost_usd")
        val sessionId = obj.stringField("session_id") ?: obj.stringField("conversation_id")
        val usage = obj["usage"]?.let { it as? JsonObject }?.let { usageObj ->
            TokenUsage(
                inputTokens = usageObj.longField("input_tokens")
                    ?: usageObj.longField("prompt_tokens"),
                outputTokens = usageObj.longField("output_tokens")
                    ?: usageObj.longField("completion_tokens"),
                cacheCreationInputTokens = null,
                cacheReadInputTokens = null
            )
        }
        return ParsedSdkOutput(text = resultText, usage = usage, costUsd = costUsd, sessionId = sessionId)
    }

    // ------------------------------------------------------------------------
    // Exceptions
    // ------------------------------------------------------------------------

    class ExternalAgentTimeoutException(message: String) : RuntimeException(message)
    class ExternalAgentExecutionException(message: String) : RuntimeException(message)

    companion object {
        const val MIN_TIMEOUT_SECONDS = 10
        const val MAX_TIMEOUT_SECONDS = 3600
        const val CLAUDE_AUTH_MODE_PARAMETER = "external_agent_claude_auth_mode"
        const val CLAUDE_MODEL_PARAMETER = "external_agent_claude_model"
        const val CLAUDE_OAUTH_TOKEN_PARAMETER = "external_agent_claude_oauth_token"
        const val CLAUDE_CONFIG_DIR_PARAMETER = "external_agent_claude_config_dir"
        const val CLAUDE_APPEND_SYSTEM_PROMPT_PARAMETER = "external_agent_claude_append_system_prompt"
        const val CLAUDE_PROGRESS_INTERVAL_SECONDS_PARAMETER = "external_agent_claude_progress_interval_seconds"
        const val CLAUDE_CODE_OAUTH_PROVIDER = "claude_code_oauth"
        const val CODEX_AUTH_MODE_PARAMETER = "external_agent_codex_auth_mode"
        const val CODEX_MODEL_PARAMETER = "external_agent_codex_model"
        const val CODEX_AUTH_JSON_PARAMETER = "external_agent_codex_auth_json"
        const val CODEX_HOME_DIR_PARAMETER = "external_agent_codex_home_dir"
        const val CODEX_SUBSCRIPTION_PROVIDER = "codex_subscription"

        /** Container path the per-tenant CODEX_HOME is bind-mounted to in Docker mode. */
        private const val CODEX_HOME_CONTAINER_PATH = "/codex-home"
        private const val DEFAULT_CLAUDE_PROGRESS_INTERVAL_SECONDS = 20.0
        private const val MIN_CLAUDE_PROGRESS_INTERVAL_SECONDS = 1.0
        private const val MAX_CLAUDE_PROGRESS_INTERVAL_SECONDS = 60.0
        const val DEFAULT_CLAUDE_CHINESE_SYSTEM_PROMPT =
            "你必须始终使用简体中文回复用户，除非用户明确要求使用其他语言。代码、命令、日志、错误信息、文件路径、API 字段名、JSON key、" +
                "英文专有名词可以保留原文；解释、结论、澄清、状态说明和交付内容必须使用中文。"

        private val CLAUDE_CODE_OAUTH_PROVIDER_ALIASES = listOf(
            "claude_code_oauth",
            "claude_code_oauth_token",
            "claude_oauth",
            "claude_subscription"
        )

        private val CODEX_SUBSCRIPTION_PROVIDER_ALIASES = listOf(
            "codex_subscription",
            "codex_oauth",
            "codex_chatgpt",
            "openai_subscription"
        )

        /** Monitoring event emitted when a Codex subscription credential is rejected (expired/revoked). */
        const val CODEX_SUBSCRIPTION_EXPIRED_EVENT = "codex_subscription_expired"

        /**
         * Lowercased substrings that mark an auth failure from `codex exec` in
         * subscription mode. Deliberately excludes the generic
         * `invalid_request_error` (that also covers the model-not-supported 400,
         * which is a config problem, not an expired credential).
         */
        private val CODEX_AUTH_FAILURE_MARKERS = listOf(
            "401",
            "unauthorized",
            "invalid_grant",
            "not logged in",
            "codex login",
            "authentication failed",
            "authentication error",
            "token expired",
            "token has expired",
            "session expired",
            "re-authenticate",
            "reauthenticate"
        )

        private fun isCodexAuthFailure(output: String): Boolean {
            val lower = output.lowercase()
            return CODEX_AUTH_FAILURE_MARKERS.any { it in lower }
        }

        internal val permissiveJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * Allowed forms for the `external_agent_*_base_url` parameter. Production
         * traffic must be https; the localhost http://… exemption is for local dev
         * and integration tests pointing at a mock proxy. Anything else (file://,
         * data:, ftp://, javascript:, internal-only http://… to RFC1918) is
         * rejected by [resolveBaseUrlOverride].
         */
        internal val BASE_URL_REGEX: Regex =
            Regex("^(https://[A-Za-z0-9._\\-]+(?::[0-9]{1,5})?(?:/[^\\s]*)?|http://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]{1,5})?(?:/[^\\s]*)?)$")

        private fun formatCost(usd: Double): String =
            if (usd < 0.01) "%.6f".format(usd) else "%.4f".format(usd)

        // Event-type constants — kept on the Engine values so the monitoring layer
        // can distinguish Claude from Codex events without parsing summary strings.
        private fun Engine.startEventType(): String = when (this) {
            Engine.CLAUDE -> "claude_code_sub_agent_starting"
            Engine.CODEX -> "codex_sub_agent_starting"
        }

        private fun Engine.completedEventType(): String = when (this) {
            Engine.CLAUDE -> "claude_code_sub_agent_completed"
            Engine.CODEX -> "codex_sub_agent_completed"
        }

        private fun Engine.failedEventType(): String = when (this) {
            Engine.CLAUDE -> "claude_code_sub_agent_failed"
            Engine.CODEX -> "codex_sub_agent_failed"
        }

        private fun Engine.progressEventType(): String = when (this) {
            Engine.CLAUDE -> "claude_code_sub_agent_progress"
            Engine.CODEX -> "codex_sub_agent_progress"
        }

        private fun Engine.timeoutEventType(): String = when (this) {
            Engine.CLAUDE -> "claude_code_sub_agent_timeout"
            Engine.CODEX -> "codex_sub_agent_timeout"
        }

        private fun Engine.startEmoji(): String = when (this) {
            Engine.CLAUDE -> "🤖"
            Engine.CODEX -> "🧬"
        }

        // Tiny JSON helpers — kept private to avoid leaking ad-hoc accessors into the
        // wider codebase. Each one returns null on type mismatch / absence so callers
        // can chain `?:` for defaults without runtime exceptions.
        private fun JsonObject.stringField(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.doubleField(name: String): Double? =
            (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()

        private fun JsonObject.longField(name: String): Long? =
            (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
    }
}

/**
 * Context for an external sub-agent invocation. Mirrors [SubAgentContext]'s shape
 * but adds external-SDK-specific knobs (`allowedTools`, `disallowedTools`, `model`,
 * `workingDir`). The defaults are intentionally conservative — callers should narrow
 * `allowedTools` in production rather than relying on the SDK's default.
 */
@Serializable
@LLMDescription("Context for spawning an external Claude Code or Codex sub-agent")
data class ExternalAgentContext(
    @property:LLMDescription("The prompt the external sub-agent should work on (passed via argv for Claude, stdin for Codex)")
    val prompt: String,
    @property:LLMDescription("Human-readable name for the sub-agent (used in logs and monitoring events)")
    val name: String = "",
    @property:LLMDescription(
        "Whitelist of SDK tool names the sub-agent is allowed to use, e.g. ['Read','Edit','Bash']. " +
            "Empty list = SDK default (which for Claude Code is the full toolset). Strongly " +
            "recommend constraining this in production."
    )
    val allowedTools: List<String> = emptyList(),
    @property:LLMDescription("Blacklist of SDK tool names the sub-agent must NOT use")
    val disallowedTools: List<String> = emptyList(),
    @property:LLMDescription(
        "Host path the sub-agent operates in. In Docker mode this directory is bind-mounted " +
            "to /workspace. Defaults to the parent agent's workspace."
    )
    val workingDir: String? = null,
    @property:LLMDescription(
        "Optional workflow-level system prompt to append to the external agent session. Claude Code " +
            "receives this via --append-system-prompt, followed by Braidrun's language constraints."
    )
    val systemPrompt: String = "",
    @property:LLMDescription("Maximum number of agent turns before the sub-agent is forcibly stopped")
    val maxTurns: Int = 32,
    @property:LLMDescription("Wall-clock timeout in seconds (clamped to [10, 3600])")
    val timeoutSeconds: Int = 1800,
    @property:LLMDescription(
        "Model name to pass to the SDK (e.g. 'claude-sonnet-4-5' for Claude Code, 'gpt-5-codex' " +
            "for Codex). Empty = SDK default."
    )
    val model: String = ""
)
