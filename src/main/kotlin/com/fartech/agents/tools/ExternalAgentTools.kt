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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Claude Code can alternatively run against a user's Claude subscription quota
 * by setting `external_agent_claude_auth_mode=subscription` and providing a
 * `claude_code_oauth` credential. At execution time Braidrun injects that secret
 * as `CLAUDE_CODE_OAUTH_TOKEN` only for the child `claude` process.
 * Set `external_agent_claude_model` to choose the default Claude Code model
 * (`sonnet`, `opus`, `haiku`, or a full model id); a tool-call `model` field
 * can still override it for one invocation.
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
    private val onMonitorEvent: MonitoringEventCallback? = null
) : ToolSet {

    private val isDocker: Boolean = executor is DockerSubprocessExecutor

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
        val baseUrlParameterKey: String
    ) {
        CLAUDE(
            displayName = "Claude Code",
            defaultCommand = "claude",
            providerKey = "anthropic",
            apiKeyEnvVar = "ANTHROPIC_API_KEY",
            baseUrlEnvVar = "ANTHROPIC_BASE_URL",
            commandParameterKey = "external_agent_claude_command",
            extraArgsParameterKey = "external_agent_claude_extra_args",
            baseUrlParameterKey = "external_agent_claude_base_url"
        ),
        CODEX(
            displayName = "Codex",
            defaultCommand = "codex",
            providerKey = "openai",
            apiKeyEnvVar = "OPENAI_API_KEY",
            baseUrlEnvVar = "OPENAI_BASE_URL",
            commandParameterKey = "external_agent_codex_command",
            extraArgsParameterKey = "external_agent_codex_extra_args",
            baseUrlParameterKey = "external_agent_codex_base_url"
        )
    }

    private enum class ExternalAuthMode(val detailValue: String) {
        API_KEY("api_key"),
        SUBSCRIPTION("subscription");

        companion object {
            fun parse(raw: String?): ExternalAuthMode {
                val normalized = raw
                    ?.trim()
                    ?.lowercase()
                    ?.replace('-', '_')
                    ?.takeIf { it.isNotEmpty() }
                    ?: return API_KEY

                return when (normalized) {
                    "api", "api_key", "apikey", "direct" -> API_KEY
                    "oauth", "oauth_token", "subscription", "claude_subscription" -> SUBSCRIPTION
                    else -> throw IllegalArgumentException(
                        "external_agent_claude_auth_mode='$raw' is invalid. " +
                            "Use 'api_key' or 'subscription'."
                    )
                }
            }
        }
    }

    private data class ResolvedExternalAuth(
        val mode: ExternalAuthMode,
        val env: Map<String, String>
    )

    private suspend fun runExternal(engine: Engine, ctx: ExternalAgentContext): String {
        val resolvedName = ctx.name.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val workDir = resolveWorkingDir(ctx.workingDir)

        // Resolve authentication BEFORE building any subprocess so misconfiguration fails
        // with a clear error instead of leaving the LLM staring at a 401 from the SDK.
        val auth = runCatching { resolveExternalAuth(engine) }.getOrElse { e ->
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 配置错误: $resolvedName",
                detail = e.message
            )
            throw e
        }

        // Build the command. Operators can override the binary name (e.g. "npx -y @anthropic-ai/claude-code")
        // by setting external_agent_<engine>_command / _extra_args parameters; default is "claude" / "codex"
        // assumed to be on PATH inside the container or host.
        val effectiveModel = resolveModel(engine, ctx)
        val command = buildCommand(engine, ctx, effectiveModel)

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
                summary = "❌ ${engine.displayName} Sub Agent 配置错误: $resolvedName",
                detail = e.message
            )
            throw e
        }

        emit(
            type = engine.startEventType(),
            summary = "${engine.startEmoji()} 启动 ${engine.displayName} Sub Agent: $resolvedName",
            detail = buildString {
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

        val request = ExecRequest(
            command = command,
            workingDir = workDir,
            stdin = ctx.prompt,
            timeoutSeconds = timeoutSeconds.toLong(),
            env = buildEnvironment(engine, auth),
            mounts = buildMounts(),
            imageHint = "node",
            userId = userId
        )

        val result = try {
            executor.execute(request)
        } catch (e: Throwable) {
            logger.error(e) { "[ExternalAgent] ${engine.displayName} sub-agent '$resolvedName' subprocess failed" }
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 失败: $resolvedName",
                detail = "executor_error: ${e.message}"
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
                summary = "⏱️ ${engine.displayName} Sub Agent 超时: $resolvedName",
                detail = "timeout=${timeoutSeconds}s, stderr_tail=${result.stderr.takeLast(500)}"
            )
            throw ExternalAgentTimeoutException(
                "${engine.displayName} sub-agent '$resolvedName' timed out after ${timeoutSeconds}s"
            )
        }

        if (result.exitCode != 0) {
            val tail = result.stderr.takeLast(2000).ifBlank { result.stdout.takeLast(2000) }
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 失败: $resolvedName",
                detail = "exit_code=${result.exitCode}, stderr_tail=$tail"
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
            summary = "✅ ${engine.displayName} Sub Agent 完成: $resolvedName",
            detail = buildString {
                append("duration_ms=${result.durationMs}")
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

    private fun buildCommand(engine: Engine, ctx: ExternalAgentContext, model: String): List<String> {
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
            Engine.CODEX -> buildCodexFlags(ctx, model)
        }

        return buildList {
            add(baseCommand)
            addAll(extraArgs)
            addAll(cliFlags)
        }
    }

    private fun buildClaudeFlags(ctx: ExternalAgentContext, model: String): List<String> = buildList {
        // -p with no positional arg → claude reads the prompt from stdin (we pipe ctx.prompt
        // via ExecRequest.stdin so prompts of arbitrary length avoid argv limits).
        add("-p")
        add("--output-format")
        add("json")
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
    }

    private fun buildCodexFlags(ctx: ExternalAgentContext, model: String): List<String> = buildList {
        add("exec")
        // Codex reads prompt from stdin when no positional prompt is provided.
        if (model.isNotBlank()) {
            add("--model")
            add(model)
        }
        // --full-auto / --json flags are codex-version-dependent; operators can append
        // them through external_agent_codex_extra_args until we standardise across
        // versions.
    }

    private fun buildEnvironment(engine: Engine, auth: ResolvedExternalAuth): Map<String, String> = buildMap {
        putAll(auth.env)
        // Optional custom API base URL — lets the operator route the SDK through
        // OpenRouter (or any Anthropic-/OpenAI-compatible proxy). Validated to
        // reject obviously-broken values so a typo in the workflow YAML doesn't
        // silently disable the override.
        resolveBaseUrlOverride(engine, auth)?.let { baseUrl -> put(engine.baseUrlEnvVar, baseUrl) }
        // Surface workspace identity, mirroring ShellTools so any audit / log integration in
        // the user's external SDK config can pick them up.
        put("BRAIDRUN_USER_ID", userId)
        put("BRAIDRUN_WORKSPACE", if (isDocker) "/workspace" else (context.workspaceDir?.absolutePath ?: "."))
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
                "$CLAUDE_AUTH_MODE_PARAMETER=subscription. Claude subscription tokens must be sent " +
                "directly to Claude Code, not to a custom API proxy."
        }
        require(BASE_URL_REGEX.matches(raw)) {
            "${engine.baseUrlParameterKey}='$raw' must be a fully-qualified URL " +
                "(https:// or http://localhost[:port] for dev)"
        }
        return raw
    }

    private fun buildMounts(): List<SubprocessExecutor.Mount> {
        if (!isDocker) return emptyList()
        // For v1 we don't mount /skills or /output for external agents — Claude/Codex run
        // entirely inside their workspace. Future enhancement: mount /skills RO when the
        // operator opts in via parameter, to share braidrun skills with Claude Code's
        // skill loader.
        return emptyList()
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
            Engine.CODEX -> ""
        }
    }

    private fun resolveExternalAuth(engine: Engine): ResolvedExternalAuth {
        val mode = if (engine == Engine.CLAUDE) {
            ExternalAuthMode.parse(parameters.parameter(CLAUDE_AUTH_MODE_PARAMETER, "api_key"))
        } else {
            ExternalAuthMode.API_KEY
        }

        if (mode == ExternalAuthMode.SUBSCRIPTION) {
            require(engine == Engine.CLAUDE) {
                "Subscription OAuth mode is only supported by Claude Code."
            }
            val token = resolveClaudeCodeOAuthToken()
                ?: throw IllegalStateException(
                    "Missing Claude subscription OAuth token. Create a credential with provider " +
                        "'$CLAUDE_CODE_OAUTH_PROVIDER', or set workflow parameter " +
                        "'$CLAUDE_OAUTH_TOKEN_PARAMETER'."
                )
            return ResolvedExternalAuth(
                mode = mode,
                env = buildMap {
                    put("CLAUDE_CODE_OAUTH_TOKEN", token)
                    put("CLAUDE_CONFIG_DIR", resolveClaudeConfigDir())
                    put("DISABLE_AUTOUPDATER", "1")
                }
            )
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

    /**
     * Claude CLI `--output-format json` emits a single JSON object. The relevant fields
     * we observe in practice:
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
        val obj = runCatching { permissiveJson.parseToJsonElement(trimmed).jsonObject }.getOrNull()
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
        const val CLAUDE_CODE_OAUTH_PROVIDER = "claude_code_oauth"

        private val CLAUDE_CODE_OAUTH_PROVIDER_ALIASES = listOf(
            "claude_code_oauth",
            "claude_code_oauth_token",
            "claude_oauth",
            "claude_subscription"
        )

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
    @property:LLMDescription("The prompt the external sub-agent should work on (any length; passed via stdin)")
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
