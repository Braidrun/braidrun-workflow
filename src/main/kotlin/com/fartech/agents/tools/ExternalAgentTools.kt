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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 *     `external_agent_codex_model` or the tool-call `model` field. Use a model
 *     identifier that is enabled for the authenticated account. When the Codex CLI
 *     refreshes `auth.json` in-place, the caller can persist the rotated JSON through
 *     [onCodexAuthJsonRotated] before the per-run `CODEX_HOME` is removed.
 *
 * Set `external_agent_claude_model` to choose the default Claude Code model or alias.
 * A tool-call `model` field can still override it for one invocation.
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
    /**
     * Persist an `auth.json` the Codex CLI refreshed in place. The credential id
     * is the one this invocation actually ran on — with a credential pool the
     * run may be on the second or third candidate, and writing the rotated JSON
     * onto whichever credential the caller happened to resolve first would
     * overwrite one account's login with another's.
     */
    private val onCodexAuthJsonRotated: ((credentialId: String?, authJson: String) -> Unit)? = null,
    private val claudeCredentialProvider: ClaudeCredentialProvider? = null,
    /**
     * Codex's own pool. Deliberately a separate instance from
     * [claudeCredentialProvider]: failover picks another credential *of the same
     * vendor*, never a ChatGPT subscription for an exhausted Claude one.
     */
    private val codexCredentialProvider: ClaudeCredentialProvider? = null,
    private val trustExecutorSandbox: Boolean = executor.isDocker
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

    internal enum class Engine(
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
         * Which credential out of the engine's pool this invocation is running
         * on. Named for the mode rather than the vendor because both
         * subscription engines now draw from a pool — each from its own.
         */
        val subscriptionCredentialId: String? = null,
        val subscriptionCredentialLabel: String? = null,
        val subscriptionCredential: ClaudeCredentialProvider.Credential? = null,
        /**
         * Codex only: the host directory used as this run's `CODEX_HOME` — the
         * tenant's materialised `auth.json` in subscription mode, an empty
         * per-invocation dir for API-key runs in Docker. [buildMounts] bind-mounts
         * it into the container at [CODEX_HOME_CONTAINER_PATH]; null for Claude and
         * for native API-key runs (which keep the operator's own CODEX_HOME).
         */
        val codexHomeHostDir: File? = null,
        val originalCodexAuthJson: String? = null,
        val codexAuthFileSource: CodexAuthFileSource? = null,
        val deleteCodexHomeOnExit: Boolean = true,
        val claudeConfigHostDir: File? = null,
        val claudeConfigContainerPath: String? = null
    )

    /** The digest is a compare-and-swap baseline, never a logged credential. */
    private data class CodexAuthFileSource(val path: Path, val originalDigest: ByteArray)

    private suspend fun runExternal(engine: Engine, ctx: ExternalAgentContext): String =
        runExternalDetailed(engine, ctx).text

    internal suspend fun runConversation(
        engine: Engine,
        ctx: ExternalAgentContext,
        onTextDelta: ((String) -> Unit)? = null
    ): ExternalAgentRunDetailedResult =
        runExternalDetailed(engine, ctx, onTextDelta)

    /** One credential attempt's result: done, or fail over to another credential of the same pool. */
    private sealed interface ExternalAttemptOutcome {
        class Completed(val result: ExternalAgentRunDetailedResult) : ExternalAttemptOutcome
        class Failover(val ctx: ExternalAgentContext, val attemptedCredentialIds: Set<String>) : ExternalAttemptOutcome
    }

    /**
     * Failover runs only after the abandoned attempt has fully finished: its sub-agent usage
     * is reported, its rotated auth.json is published under the credential that produced it,
     * and its credential hold is released. A replacement attempt therefore never runs nested
     * inside the abandoned one, and the replacement's result can never be overwritten by the
     * abandoned attempt's cleanup.
     */
    private suspend fun runExternalDetailed(
        engine: Engine,
        ctx: ExternalAgentContext,
        onTextDelta: ((String) -> Unit)? = null
    ): ExternalAgentRunDetailedResult {
        var attemptContext = ctx
        var attemptedCredentialIds = emptySet<String>()
        while (true) {
            when (val outcome = runExternalAttempt(engine, attemptContext, onTextDelta, attemptedCredentialIds)) {
                is ExternalAttemptOutcome.Completed -> return outcome.result
                is ExternalAttemptOutcome.Failover -> {
                    // Each failover adds the abandoned credential, and every pool rejects an
                    // already-attempted id, so the attempt bounds below always terminate.
                    check(outcome.attemptedCredentialIds.size > attemptedCredentialIds.size) {
                        "Subscription failover did not exclude the abandoned credential"
                    }
                    attemptContext = outcome.ctx
                    attemptedCredentialIds = outcome.attemptedCredentialIds
                }
            }
        }
    }

    private suspend fun runExternalAttempt(
        engine: Engine,
        ctx: ExternalAgentContext,
        onTextDelta: ((String) -> Unit)?,
        /**
         * Credentials this invocation already burned through, within one
         * engine's pool. Failover walks a single vendor's candidates; the set is
         * never carried across engines because a run only ever uses one.
         */
        attemptedCredentialIds: Set<String>
    ): ExternalAttemptOutcome {
        val resolvedName = ctx.name.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val invocationUuid = UUID.randomUUID().toString()
        val invocationId = invocationUuid.take(8)
        val invocationLabel = invocationLabel(resolvedName, invocationId)
        val workDir = resolveWorkingDir(ctx.workingDir)
        com.fartech.agents.workflow.WorkflowHostContext.current?.privateDirectory?.let {
            require(ctx.mcpServers.isEmpty()) { "Runner CLI MCP requires a supervised adapter" }
            require(ctx.resumeSessionId.isBlank()) { "Runner CLI session resume needs a bound restore adapter" }
        }
        val tempFiles = mutableListOf<File>()

        // Resolve authentication BEFORE building any subprocess so misconfiguration fails
        // with a clear error instead of leaving the LLM staring at a 401 from the SDK.
        val auth = runCatching {
            resolveExternalAuth(engine, ctx, invocationUuid, attemptedCredentialIds)
        }.getOrElse { e ->
            emit(
                type = engine.failedEventType(),
                summary = "❌ ${engine.displayName} Sub Agent 配置错误: $invocationLabel",
                detail = listOfNotNull("invocation_id=$invocationId", e.message).joinToString(", ")
            )
            throw e
        }

        val credentialFileObservation = auth.codexHomeHostDir?.let { directory ->
            com.fartech.agents.workflow.WorkflowHostContext.current?.let { host ->
                val scope = com.fartech.agents.workflow.WorkflowHostContext.scope
                    ?: com.fartech.agents.workflow.WorkflowHostScope(host.binding.executionId, context.executionId ?: host.binding.executionId, stepName = context.stepName)
                host.observeCredentialFile(scope, File(directory, "auth.json").toPath())
            }
        }
        // Codex usage recovery: always observe the stream (even with no emitter) so a
        // killed run still knows its thread id and whether usage was already reported.
        val codexObserver = if (engine == Engine.CODEX) CodexRunObserver() else null
        val codexHome = codexUsageHome(engine, auth)
        // Rollouts already in CODEX_HOME before this run (a persistent home keeps
        // earlier runs' threads); sub-agent usage is charged only for what they add.
        val codexRolloutSizesBefore = codexHome?.let { snapshotCodexRolloutSizes(it.hostDir) }
        var codexRunCompleted = false

        // Wrap the rest so the per-run Codex auth.json (a live subscription token we
        // materialise on the host) is always removed once the subprocess finishes —
        // on success, failure, or timeout. Avoids leaving customer tokens on disk.
        try {

        // Build the command. Operators can override the binary name (e.g. "npx -y @anthropic-ai/claude-code")
        // by setting external_agent_<engine>_command / _extra_args parameters; default is "claude" / "codex"
        // assumed to be on PATH inside the container or host.
        val effectiveModel = resolveModel(engine, ctx)
        val command = buildCommand(engine, ctx, effectiveModel, auth, workDir, tempFiles)

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

        val streamEmitter = if (onMonitorEvent != null || onTextDelta != null) {
            when (engine) {
                Engine.CLAUDE -> ClaudeStreamEventEmitter(resolvedName, invocationId, onTextDelta)
                Engine.CODEX -> CodexStreamEventEmitter(resolvedName, invocationId, onTextDelta)
            }
        } else null

        // A resumed thread's rollout already holds earlier runs' cumulative total.
        val codexResumeBaseline = codexHome
            ?.takeIf { ctx.resumeSessionId.isNotBlank() }
            ?.let { home -> findCodexRollout(home.hostDir, ctx.resumeSessionId.trim(), exclusive = false) }
            ?.let(::readCodexRolloutUsage)
        val startedAtMillis = System.currentTimeMillis()
        fun recoverCodexUsage(reason: String, partial: Boolean = true, durationMs: Long? = null) =
            recoverCodexRolloutUsage(
                home = codexHome,
                observer = codexObserver,
                resumeSessionId = ctx.resumeSessionId,
                baseline = codexResumeBaseline,
                reason = reason,
                partial = partial,
                resolvedName = resolvedName,
                invocationId = invocationId,
                durationMs = durationMs ?: (System.currentTimeMillis() - startedAtMillis)
            )

        val request = ExecRequest(
            command = command,
            workingDir = workDir,
            // Prompts can contain complete workflow/execution context and easily
            // exceed Linux's per-argument MAX_ARG_STRLEN. Both CLIs support stdin;
            // DockerSubprocessExecutor uses StdinOnce and closes the attach after
            // writing, so the child reliably receives EOF without putting the
            // payload in execve argv.
            stdin = com.fartech.agents.workflow.WorkflowHostContext.current?.let { host ->
                val scope = com.fartech.agents.workflow.WorkflowHostContext.scope
                    ?: com.fartech.agents.workflow.WorkflowHostScope(host.binding.executionId, context.executionId ?: host.binding.executionId, stepName = context.stepName)
                host.nativeAgentPrompt(scope, ctx.prompt)
            } ?: ctx.prompt,
            timeoutSeconds = timeoutSeconds.toLong(),
            env = buildEnvironment(engine, auth, ctx),
            mounts = buildMounts(auth),
            imageHint = "node",
            userId = userId,
            stdoutLineCallback = if (streamEmitter == null && codexObserver == null) {
                null
            } else {
                { line ->
                    codexObserver?.observe(line)
                    streamEmitter?.handleLine(line)
                }
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
            // Cancellation (e.g. the workflow step's own timeout) kills the process
            // without a result; its usage is still in the rollout until finally.
            recoverCodexUsage(reason = if (e is CancellationException) "cancelled" else "failed")
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
        if (isSubprocessTimeout(result.exitCode, result.stderr)) {
            if (engine == Engine.CODEX && parseSdkOutput(engine, result.stdout).usage == null) {
                recoverCodexUsage(reason = "timeout", durationMs = result.durationMs)
            }
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
            // Claude and Codex can return a perfectly valid final usage envelope
            // together with a non-zero exit (for example Claude's
            // `error_max_turns`). Parse and publish that aggregate before the
            // error branches throw; otherwise every token consumed by a failed
            // invocation disappears from execution metering. The web layer
            // treats this as a final aggregate and ignores it when per-turn live
            // usage for the same invocation is already available.
            val failedParsed = sequenceOf(result.stdout, result.stderr)
                .filter { it.isNotBlank() }
                .map { parseSdkOutput(engine, it) }
                .firstOrNull { it.usage != null }
            failedParsed?.usage?.let { usage ->
                emit(
                    type = engine.finalUsageEventType(),
                    summary = "🔤 ${engine.displayName} 最终 Token 用量: $invocationLabel",
                    detail = buildString {
                        append("invocation_id=$invocationId")
                        append(", input_tokens=${usage.inputTokens ?: 0L}")
                        append(", output_tokens=${usage.outputTokens ?: 0L}")
                        append(", total_tokens=${(usage.inputTokens ?: 0L) + (usage.outputTokens ?: 0L)}")
                        usage.cacheReadInputTokens?.let { append(", cache_read=$it") }
                        usage.cacheCreationInputTokens?.let { append(", cache_creation=$it") }
                        failedParsed.costUsd?.let { append(", cost_usd=${formatCost(it)}") }
                    }
                )
            }
            if (engine == Engine.CODEX && failedParsed?.usage == null) {
                // Before any replay below: the failed attempt's tokens were spent either way.
                recoverCodexUsage(reason = "failed", durationMs = result.durationMs)
            }
            val tail = externalAgentFailureExcerpt(engine, result.stdout, result.stderr)
            val claudeUnavailable = engine == Engine.CLAUDE &&
                auth.mode == ExternalAuthMode.SUBSCRIPTION &&
                auth.subscriptionCredentialId != null &&
                confirmedClaudeSubscriptionCredentialUnavailable("${result.stderr}\n${result.stdout}")
            if (claudeUnavailable) {
                val credential = requireNotNull(auth.subscriptionCredential)
                val resetAtMillis = System.currentTimeMillis() + SUBSCRIPTION_AUTH_FAILURE_COOLDOWN_MS
                runCatching {
                    claudeCredentialProvider?.markRateLimited(credential, resetAtMillis)
                }.onFailure { error ->
                    logger.warn(error) { "[ExternalAgent] Failed to persist unavailable Claude credential cooldown" }
                }
                emit(
                    type = CLAUDE_SUBSCRIPTION_UNAVAILABLE_EVENT,
                    summary = "🔄 Claude 订阅凭据不可用，正在切换备用凭据: $invocationLabel",
                    detail = buildString {
                        append("invocation_id=$invocationId, credential_id=${credential.id}")
                        auth.subscriptionCredentialLabel?.let { append(", credential_label=$it") }
                        append(", reason=oauth_org_not_allowed, resets_at_ms=$resetAtMillis")
                    }
                )
                val attempted = attemptedCredentialIds + credential.id
                if (attempted.size >= MAX_SUBSCRIPTION_CREDENTIAL_ATTEMPTS) {
                    throw ExternalAgentExecutionException(
                        "Claude subscription failover exhausted $MAX_SUBSCRIPTION_CREDENTIAL_ATTEMPTS credential attempts"
                    )
                }
                return ExternalAttemptOutcome.Failover(ctx, attempted)
            }
            val claudeRateLimit = if (
                engine == Engine.CLAUDE &&
                auth.mode == ExternalAuthMode.SUBSCRIPTION &&
                auth.subscriptionCredentialId != null
            ) {
                confirmedClaudeSubscriptionRateLimit("${result.stderr}\n${result.stdout}")
            } else {
                null
            }
            if (claudeRateLimit != null) {
                val resetAtMillis = claudeRateLimitResetAtMillis("${result.stderr}\n${result.stdout}")
                    ?: (System.currentTimeMillis() + DEFAULT_SUBSCRIPTION_RATE_LIMIT_COOLDOWN_MS)
                val credential = requireNotNull(auth.subscriptionCredential)
                runCatching {
                    claudeCredentialProvider?.markRateLimited(credential, resetAtMillis)
                }.onFailure { error ->
                    logger.warn(error) { "[ExternalAgent] Failed to persist Claude credential cooldown" }
                }
                val partialReplayEnabled =
                    parameters.parameter(CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_SAFE_PARAMETER, false)
                val shouldReplay = claudeRateLimit.safeWithoutExplicitReplay || partialReplayEnabled
                emit(
                    type = CLAUDE_SUBSCRIPTION_RATE_LIMIT_EVENT,
                    summary = if (shouldReplay) {
                        "🔄 Claude 订阅凭据已限额，正在切换备用凭据: $invocationLabel"
                    } else {
                        "⏸️ Claude 订阅凭据已限额，已冷却但未自动重放: $invocationLabel"
                    },
                    detail = buildString {
                        append("invocation_id=$invocationId, credential_id=${auth.subscriptionCredentialId}")
                        auth.subscriptionCredentialLabel?.let { append(", credential_label=$it") }
                        append(", resets_at_ms=$resetAtMillis")
                        append(", partial_progress=${!claudeRateLimit.safeWithoutExplicitReplay}")
                        append(", replay_enabled=$shouldReplay")
                    }
                )
                if (shouldReplay) {
                    val attempted = attemptedCredentialIds + credential.id
                    if (attempted.size >= MAX_SUBSCRIPTION_CREDENTIAL_ATTEMPTS) {
                        throw ExternalAgentExecutionException(
                            "Claude subscription failover exhausted $MAX_SUBSCRIPTION_CREDENTIAL_ATTEMPTS credential attempts"
                        )
                    }
                    val replayContext = if (claudeRateLimit.safeWithoutExplicitReplay) {
                        ctx
                    } else {
                        ctx.withClaudePartialRateLimitReplayInstruction()
                    }
                    return ExternalAttemptOutcome.Failover(replayContext, attempted)
                }
            }
            // ChatGPT subscription quota. Checked before the auth branch: a 429
            // is a quota answer, not an expired credential.
            if (engine == Engine.CODEX &&
                auth.mode == ExternalAuthMode.SUBSCRIPTION &&
                isCodexRateLimitFailure(result.stdout, result.stderr)
            ) {
                val credential = auth.subscriptionCredential
                val resetAtMillis = System.currentTimeMillis() + DEFAULT_SUBSCRIPTION_RATE_LIMIT_COOLDOWN_MS
                if (credential != null) {
                    runCatching {
                        codexCredentialProvider?.markRateLimited(credential, resetAtMillis)
                    }.onFailure { error ->
                        logger.warn(error) { "[ExternalAgent] Failed to persist Codex credential cooldown" }
                    }
                }
                // Same rule as Claude: replaying re-runs the whole invocation, so
                // it is only safe while the run has produced nothing outside its
                // own transcript.
                val replaySafe = codexRunReplaySafe(result.stdout)
                val partialReplayEnabled =
                    parameters.parameter(CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_SAFE_PARAMETER, false)
                val attempted = attemptedCredentialIds + setOfNotNull(credential?.id)
                val shouldReplay = credential != null &&
                    (replaySafe || partialReplayEnabled) &&
                    attempted.size < MAX_SUBSCRIPTION_CREDENTIAL_ATTEMPTS
                emit(
                    type = CODEX_SUBSCRIPTION_RATE_LIMIT_EVENT,
                    summary = if (shouldReplay) {
                        "🔄 Codex 订阅凭据已限额，正在切换备用凭据: $invocationLabel"
                    } else {
                        "⏸️ Codex 订阅额度用尽: $invocationLabel"
                    },
                    detail = buildString {
                        append("invocation_id=$invocationId, credential_id=${auth.subscriptionCredentialId}")
                        auth.subscriptionCredentialLabel?.let { append(", credential_label=$it") }
                        append(", resets_at_ms=$resetAtMillis")
                        append(", partial_progress=${!replaySafe}")
                        append(", replay_enabled=$shouldReplay")
                        append(", stderr_tail=$tail")
                    }
                )
                if (shouldReplay) {
                    // Only ever another codex_subscription credential — the pool
                    // is this engine's own, so a spent ChatGPT quota can never
                    // land the run on a Claude subscription.
                    return ExternalAttemptOutcome.Failover(ctx, attempted)
                }
                throw ExternalAgentExecutionException(
                    "Codex sub-agent '$resolvedName' stopped on a ChatGPT subscription usage limit " +
                        "(the account is out of quota, not misconfigured)" +
                        if (credential == null) {
                            ", and no credential pool is wired to fail over to"
                        } else {
                            ", and no other ChatGPT subscription credential was available"
                        } +
                        ". stderr: $tail"
                )
            }
            // Codex subscription credentials are short-lived OAuth tokens. When the
            // access token can no longer be refreshed (revoked / refresh token expired)
            // codex returns a 401. With a pool, move to the next credential; only
            // when none is left does the operator hear "re-run codex login".
            if (engine == Engine.CODEX &&
                auth.mode == ExternalAuthMode.SUBSCRIPTION &&
                isCodexAuthFailure(result.stdout, result.stderr)
            ) {
                val credential = auth.subscriptionCredential
                // A dead login usually fails before the agent does anything, but
                // a token can also die mid-run — check rather than assume.
                val replaySafe = codexRunReplaySafe(result.stdout)
                val attempted = attemptedCredentialIds + setOfNotNull(credential?.id)
                val shouldReplay = credential != null &&
                    replaySafe &&
                    attempted.size < MAX_SUBSCRIPTION_CREDENTIAL_ATTEMPTS
                emit(
                    type = CODEX_SUBSCRIPTION_EXPIRED_EVENT,
                    summary = if (shouldReplay) {
                        "🔑 Codex 订阅凭据失效，正在切换备用凭据: $invocationLabel"
                    } else {
                        "🔑 Codex 订阅凭据失效: $invocationLabel"
                    },
                    detail = "invocation_id=$invocationId, credential_id=${auth.subscriptionCredentialId}, " +
                        "replay_enabled=$shouldReplay, 该 ChatGPT 订阅凭据已过期或被吊销," +
                        "需重新执行 `codex login` 并更新凭据中心 provider=$CODEX_SUBSCRIPTION_PROVIDER 的 auth.json。" +
                        "stderr_tail=$tail"
                )
                if (shouldReplay) {
                    return ExternalAttemptOutcome.Failover(ctx, attempted)
                }
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
        // A clean exit whose `turn.completed` never reached us — stdout is capped at
        // MAX_STREAM_BYTES, and long --json runs can exceed it. The rollout's total is
        // complete here, hence partial=false. The completed event below stays without
        // tokens so the platform counts this invocation once.
        val recoveredUsage = if (engine == Engine.CODEX && parsed.usage == null) {
            recoverCodexUsage(reason = "missing_usage", partial = false, durationMs = result.durationMs)
        } else {
            null
        }
        auth.subscriptionCredential?.let { credential ->
            runCatching {
                subscriptionCredentialProvider(engine)?.markSucceeded(
                    credential,
                    context.executionId,
                    context.stepName
                )
            }.onFailure { error ->
                logger.warn(error) {
                    "[ExternalAgent] Failed to record ${engine.displayName} credential usage"
                }
            }
        }

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

        codexRunCompleted = true
        // Main thread only: sub-agent threads are metered through their own
        // usage events (see [reportCodexSubAgentUsage]).
        return ExternalAttemptOutcome.Completed(ExternalAgentRunDetailedResult(
            text = parsed.text,
            sessionId = parsed.sessionId,
            inputTokens = parsed.usage?.inputTokens ?: recoveredUsage?.inputTokens,
            outputTokens = parsed.usage?.outputTokens ?: recoveredUsage?.outputTokens,
            costUsd = parsed.costUsd
        ))
        } finally {
            // Every exit path, before the per-run home and its rollouts are deleted.
            reportCodexSubAgentUsage(
                home = codexHome,
                primaryThreadId = codexObserver?.threadId ?: ctx.resumeSessionId.trim().takeIf { it.isNotEmpty() },
                rolloutSizesBefore = codexRolloutSizesBefore.orEmpty(),
                partial = !codexRunCompleted,
                resolvedName = resolvedName,
                invocationId = invocationId
            )
            // Publish this attempt's rotation while its own credential hold is still live, then
            // release exactly that hold. Both happen before any failover attempt starts.
            try { maybePublishRotatedCodexAuthJson(engine, auth) } finally {
                try { releaseSubscriptionCredential(engine, auth.subscriptionCredential) } finally {
                    credentialFileObservation?.close()
                    tempFiles.forEach { file -> runCatching { file.delete() } }
                    auth.codexHomeHostDir
                        ?.takeIf { auth.deleteCodexHomeOnExit }
                        ?.let { dir -> runCatching { dir.deleteRecursively() } }
                }
            }
        }
    }

    /** Ends an invocation-held credential even while the caller is being cancelled. */
    private suspend fun releaseSubscriptionCredential(
        engine: Engine,
        credential: ClaudeCredentialProvider.Credential?
    ) {
        val provider = subscriptionCredentialProvider(engine) as? InvocationCredentialProvider ?: return
        if (credential == null) return
        withContext(NonCancellable) { provider.release(credential) }
    }

    /**
     * Watches a Codex `--json` stdout stream for the two facts usage recovery needs
     * even when the run never returns an exec result (cancellation): the thread id
     * that names the session rollout, and whether `turn.completed` already reported
     * usage live. Called from the executor's reader thread.
     */
    private class CodexRunObserver {
        @Volatile
        var threadId: String? = null
            private set

        @Volatile
        var turnUsageReported: Boolean = false
            private set

        fun observe(line: String) {
            val wantsThread = threadId == null && line.contains("\"thread.started\"")
            val wantsUsage = !turnUsageReported && line.contains("\"turn.completed\"")
            if (!wantsThread && !wantsUsage) return
            val obj = parseJsonObjectOrNullStatic(line.trim()) ?: return
            when (obj.stringField("type")) {
                "thread.started" -> obj.stringField("thread_id")?.takeIf { it.isNotBlank() }?.let { threadId = it }
                "turn.completed" -> {
                    val usage = obj["usage"] as? JsonObject ?: return
                    if ((usage.longField("input_tokens") ?: 0L) > 0L || (usage.longField("output_tokens") ?: 0L) > 0L) {
                        turnUsageReported = true
                    }
                }
            }
        }
    }

    /**
     * The host directory this Codex invocation uses as `CODEX_HOME`, or null when the
     * engine does not manage it (Claude, native API-key mode). [exclusive] homes were
     * created for this invocation alone; shared/persistent homes are only searched
     * for the run's own thread id.
     */
    private fun codexUsageHome(engine: Engine, auth: ResolvedExternalAuth): CodexUsageHome? {
        if (engine != Engine.CODEX) return null
        val dir = auth.codexHomeHostDir ?: return null
        return CodexUsageHome(dir, exclusive = auth.deleteCodexHomeOnExit)
    }

    /**
     * Codex reports usage only in the final `turn.completed` event, so a run killed by
     * its timeout, cancelled, or exiting early never prints it and the invocation
     * would be metered at zero. Its session rollout under CODEX_HOME still holds the
     * cumulative `token_count`; read it (on the host, before cleanup deletes the
     * per-run home) and publish it once as the invocation's final aggregate. Callers
     * skip this when the run's stdout already carried a `turn.completed` usage.
     * Returns the usage it published, or null.
     */
    private fun recoverCodexRolloutUsage(
        home: CodexUsageHome?,
        observer: CodexRunObserver?,
        resumeSessionId: String,
        baseline: CodexRolloutUsage?,
        reason: String,
        partial: Boolean,
        resolvedName: String,
        invocationId: String,
        durationMs: Long
    ): CodexRolloutUsage? {
        if (observer == null || observer.turnUsageReported) return null
        if (home == null) {
            logger.debug { "[ExternalAgent] Codex sub-agent '$resolvedName' $reason; CODEX_HOME is not engine-managed, usage not recoverable" }
            return null
        }
        return runCatching {
            val threadId = observer.threadId ?: resumeSessionId.trim().takeIf { it.isNotEmpty() }
            val rollout = findCodexRollout(home.hostDir, threadId, home.exclusive) ?: return null
            val cumulative = readCodexRolloutUsage(rollout) ?: return null
            // A resumed thread appends to its old rollout and keeps counting from the
            // previous runs' total; charge only what this invocation added.
            val usage = baseline?.let { cumulative - it } ?: cumulative
            if (usage.isEmpty()) return null
            logger.info {
                "[ExternalAgent] Codex sub-agent '$resolvedName' $reason after ${durationMs}ms. " +
                    "usage=${usage.toTokenUsage()}, reasoning_output_tokens=${usage.reasoningOutputTokens}, " +
                    "partial=$partial, recovered_from=${rollout.name}"
            }
            emit(
                type = Engine.CODEX.finalUsageEventType(),
                summary = "🔤 Codex 最终 Token 用量（从会话记录恢复）: ${invocationLabel(resolvedName, invocationId)}",
                detail = buildString {
                    append("invocation_id=$invocationId")
                    append(", input_tokens=${usage.inputTokens}")
                    append(", output_tokens=${usage.outputTokens}")
                    append(", total_tokens=${usage.inputTokens + usage.outputTokens}")
                    append(", cache_read=${usage.cachedInputTokens}")
                    append(", partial=$partial")
                    append(", reason=$reason")
                    append(", source=codex_rollout")
                }
            )
            usage
        }.onFailure { e ->
            logger.warn(e) { "[ExternalAgent] Failed to recover Codex usage for '$resolvedName' from its session rollout" }
        }.getOrNull()
    }

    /**
     * Codex's own sub-agents (multi-agent `spawn_agent`) run as separate threads, and
     * `codex exec --json` reports only the main thread's usage — `turn.completed`
     * never includes theirs. Each thread keeps its own rollout under CODEX_HOME with
     * its own cumulative `token_count`; publish one final usage event per sub-agent
     * thread, under its own invocation id `<invocationId>:sub:<threadId>`, so the
     * platform counts every thread exactly once next to the main invocation.
     */
    private fun reportCodexSubAgentUsage(
        home: CodexUsageHome?,
        primaryThreadId: String?,
        rolloutSizesBefore: Map<String, Long>,
        partial: Boolean,
        resolvedName: String,
        invocationId: String
    ) {
        if (home == null) return
        runCatching {
            val label = invocationLabel(resolvedName, invocationId)
            codexSubAgentUsages(home.hostDir, primaryThreadId, home.exclusive, rolloutSizesBefore).forEach { sub ->
                val usage = sub.usage
                // The platform reads ids as [A-Za-z0-9._:-]+; Codex thread ids are UUIDs.
                val subInvocationId = "$invocationId:sub:" + sub.threadId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
                logger.info {
                    "[ExternalAgent] Codex sub-agent '$resolvedName' thread ${sub.threadId} (${sub.agentPath ?: "?"}) " +
                        "usage=${usage.toTokenUsage()}, reasoning_output_tokens=${usage.reasoningOutputTokens}, partial=$partial"
                }
                emit(
                    type = Engine.CODEX.finalUsageEventType(),
                    summary = "🔤 Codex 子代理 Token 用量: $label → ${sub.agentPath ?: sub.threadId}",
                    detail = buildString {
                        append("invocation_id=$subInvocationId")
                        append(", parent_invocation_id=$invocationId")
                        append(", input_tokens=${usage.inputTokens}")
                        append(", output_tokens=${usage.outputTokens}")
                        append(", total_tokens=${usage.inputTokens + usage.outputTokens}")
                        append(", cache_read=${usage.cachedInputTokens}")
                        append(", reasoning_output_tokens=${usage.reasoningOutputTokens}")
                        append(", partial=$partial")
                        append(", source=codex_rollout_sub_agent")
                        sub.agentPath?.let { append(", agent_path=$it") }
                    }
                )
            }
        }.onFailure { e ->
            logger.warn(e) { "[ExternalAgent] Failed to read Codex sub-agent usage for '$resolvedName' from CODEX_HOME" }
        }
    }

    private fun maybePublishRotatedCodexAuthJson(engine: Engine, auth: ResolvedExternalAuth) {
        val callback = onCodexAuthJsonRotated
        // An invocation-held credential is rotated through its own hold, never by id: a
        // concurrent invocation on the same account holds a different instance.
        val holder = (subscriptionCredentialProvider(engine) as? InvocationCredentialProvider)
            ?.takeIf { engine == Engine.CODEX }
            ?.let { provider -> auth.subscriptionCredential?.let { credential -> provider to credential } }
        if (callback == null && holder == null && auth.codexAuthFileSource == null) return
        if (auth.mode != ExternalAuthMode.SUBSCRIPTION) return
        val dir = auth.codexHomeHostDir ?: return
        val original = auth.originalCodexAuthJson?.trim() ?: return
        val current = runCatching { File(dir, "auth.json").readText().trim() }
            .getOrElse { e ->
                logger.warn(e) { "[ExternalAgent] Failed to inspect Codex auth.json for rotation" }
                return
            }
            .takeIf { it.isNotBlank() }
            ?: return
        if (current == original) return
        runCatching { normalizeCodexAuthJson(current) ?: return }
            .onSuccess { normalized ->
                auth.codexAuthFileSource?.let { source ->
                    runCatching { persistRotatedCodexAuthFile(source, normalized) }
                        .onFailure { logger.warn { "[ExternalAgent] Could not persist refreshed local Codex authentication" } }
                    return@onSuccess
                }
                // Pass the credential this run actually used: with a pool the
                // rotated auth.json belongs to whichever candidate failover
                // landed on, not to the first one the caller resolved.
                if (holder != null) {
                    // Runner leases: a failed publication must fail this attempt, never be logged away.
                    holder.first.rotated(holder.second, normalized)
                } else if (com.fartech.agents.workflow.WorkflowHostContext.current != null) {
                    callback?.invoke(auth.subscriptionCredentialId, normalized)
                } else {
                    runCatching { callback?.invoke(auth.subscriptionCredentialId, normalized) }
                        .onFailure { e -> logger.warn(e) { "[ExternalAgent] Failed to publish rotated Codex auth.json" } }
                }
            }
            .onFailure { e -> logger.warn(e) { "[ExternalAgent] Ignoring invalid rotated Codex auth.json" } }
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

    private interface ExternalStreamEventEmitter {
        fun handleLine(line: String)
    }

    /**
     * One assistant turn's token counts as most recently reported by the CLI.
     * Anthropic splits the prompt into uncached [inputTokens] plus the cache
     * counters, so all four fields are needed to describe a turn.
     */
    private data class ClaudeTurnUsage(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheReadTokens: Long = 0,
        val cacheCreationTokens: Long = 0
    ) {
        /** Field-wise difference, floored at zero so a shrinking count never
         *  subtracts from what was already reported. */
        operator fun minus(other: ClaudeTurnUsage) = ClaudeTurnUsage(
            inputTokens = (inputTokens - other.inputTokens).coerceAtLeast(0L),
            outputTokens = (outputTokens - other.outputTokens).coerceAtLeast(0L),
            cacheReadTokens = (cacheReadTokens - other.cacheReadTokens).coerceAtLeast(0L),
            cacheCreationTokens = (cacheCreationTokens - other.cacheCreationTokens).coerceAtLeast(0L)
        )

        fun isEmpty(): Boolean =
            inputTokens == 0L && outputTokens == 0L && cacheReadTokens == 0L && cacheCreationTokens == 0L
    }

    private inner class ClaudeStreamEventEmitter(
        private val resolvedName: String,
        private val invocationId: String,
        private val onTextDelta: ((String) -> Unit)?
    ) : ExternalStreamEventEmitter {
        private val label = invocationLabel(resolvedName, invocationId)
        private var lastAssistantText = ""
        private val seenToolCalls = linkedSetOf<String>()
        private val seenToolResults = linkedSetOf<String>()
        /**
         * Per-message running totals we have already published, so a message
         * seen more than once contributes only what is new. Claude reports the
         * same `message.id` several times as a turn progresses — first from the
         * message-start snapshot, where `output_tokens` is a placeholder, and
         * again once the turn is complete. Deduplicating on first sighting would
         * freeze the placeholder as the turn's usage.
         */
        private val emittedUsage = linkedMapOf<String, ClaudeTurnUsage>()
        private var systemEventEmitted = false
        // Set once real token streaming (`--include-partial-messages` →
        // `stream_event`/`content_block_delta`) has begun, so the COMPLETE
        // `assistant` message that follows doesn't re-emit the same text.
        private var partialStreamActive = false
        // How much of [lastAssistantText] we've already surfaced as a monitoring
        // event, so `content_block_stop` emits one coalesced event per block
        // instead of one per token.
        private var streamedDebugLen = 0

        override fun handleLine(line: String) {
            val obj = parseJsonObjectOrNull(line) ?: return
            when (obj.stringField("type")) {
                "system" -> handleSystem(obj)
                "stream_event" -> handleStreamEvent(obj)
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
            emitAssistantUsage(obj, message)
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
            // When `--include-partial-messages` already streamed this text
            // token-by-token, the complete assistant message is a duplicate —
            // only its tool_use / reasoning parts (handled above) still matter.
            if (text.isNotBlank() && !partialStreamActive) emitAssistantDelta(text)
        }

        /**
         * Claude's final `result` envelope only arrives when the whole sub-agent
         * exits, but every completed assistant turn already carries its own usage.
         * Surface those per-turn deltas so long-running tool loops expose token
         * consumption while they are still running.
         *
         * A turn's usage is reported more than once under the same `message.id`
         * and grows as the turn proceeds: the message-start snapshot carries the
         * real (uncached) input and cache counts but only a placeholder
         * `output_tokens`, and the completed message carries the real output.
         * Every event therefore reports the **increment** since the last one for
         * that message, so consumers can sum events without double counting and
         * without freezing the placeholder as the turn's output.
         */
        private fun emitAssistantUsage(obj: JsonObject, message: JsonObject) {
            val usage = (message["usage"] as? JsonObject)
                ?: (obj["usage"] as? JsonObject)
                ?: return
            val inputTokens = usage.longField("input_tokens")
            val outputTokens = usage.longField("output_tokens")
            if (inputTokens == null && outputTokens == null) return

            val messageId = message.stringField("id")
                ?: obj.stringField("uuid")
                ?: obj.stringField("message_id")

            val running = ClaudeTurnUsage(
                inputTokens = inputTokens?.coerceAtLeast(0L) ?: 0L,
                outputTokens = outputTokens?.coerceAtLeast(0L) ?: 0L,
                cacheReadTokens = usage.longField("cache_read_input_tokens")?.coerceAtLeast(0L) ?: 0L,
                cacheCreationTokens = usage.longField("cache_creation_input_tokens")?.coerceAtLeast(0L) ?: 0L
            )
            // A message with no id can't be correlated across sightings; treat it
            // as its own turn rather than silently folding it into the previous one.
            val alreadyEmitted = messageId?.let { emittedUsage[it] } ?: ClaudeTurnUsage()
            val delta = running - alreadyEmitted
            if (delta.isEmpty()) return
            messageId?.let { emittedUsage[it] = running }

            emit(
                type = "claude_code_sub_agent_usage",
                summary = "🔤 Claude Code Token 用量: $label (+${delta.inputTokens + delta.outputTokens})",
                detail = buildString {
                    append("invocation_id=$invocationId")
                    messageId?.let { append(", message_id=${it.take(32)}") }
                    append(", input_tokens=${delta.inputTokens}")
                    append(", output_tokens=${delta.outputTokens}")
                    append(", total_tokens=${delta.inputTokens + delta.outputTokens}")
                    if (delta.cacheReadTokens > 0L) append(", cache_read=${delta.cacheReadTokens}")
                    if (delta.cacheCreationTokens > 0L) append(", cache_creation=${delta.cacheCreationTokens}")
                }
            )
        }

        /**
         * Live token streaming from `--include-partial-messages`. Each line is a
         * `stream_event` wrapping an Anthropic streaming event; the text tokens
         * arrive as `content_block_delta` → `delta.type == "text_delta"`.
         * Streams each token straight to the chat and advances
         * [lastAssistantText] so the trailing complete `assistant` message dedups
         * to empty. Reasoning/tool blocks are left to the complete-message path.
         */
        private fun handleStreamEvent(obj: JsonObject) {
            val event = obj["event"] as? JsonObject ?: return
            when (event.stringField("type")) {
                "content_block_delta" -> {
                    val delta = event["delta"] as? JsonObject ?: return
                    if (delta.stringField("type") == "text_delta") {
                        val piece = delta.stringField("text").orEmpty()
                        if (piece.isEmpty()) return
                        partialStreamActive = true
                        lastAssistantText += piece
                        onTextDelta?.invoke(piece)
                    }
                }
                "content_block_stop" -> {
                    // One coalesced monitoring event per finished block — per-token
                    // events would flood the debug/execution view.
                    if (lastAssistantText.length > streamedDebugLen) {
                        val newText = lastAssistantText.substring(streamedDebugLen)
                        streamedDebugLen = lastAssistantText.length
                        if (newText.isNotBlank()) {
                            emit(
                                type = "claude_code_stream_text_delta",
                                summary = buildSummaryWithPreview("💬 Claude Code 输出: $label", newText),
                                detail = truncateDetail("invocation_id=$invocationId\n$newText")
                            )
                        }
                    }
                }
            }
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
                    onTextDelta?.invoke(text)
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
            }
            lastAssistantText = text
            if (delta.isBlank()) return
            onTextDelta?.invoke(delta)
            emit(
                type = "claude_code_stream_text_delta",
                summary = buildSummaryWithPreview("💬 Claude Code 输出: $label", delta),
                detail = truncateDetail("invocation_id=$invocationId\n$delta")
            )
        }
    }

    /** Lifecycle phase of a Codex `item.*` event — `item.started` / `item.updated` / `item.completed`. */
    private enum class CodexItemPhase { STARTED, UPDATED, COMPLETED }

    private inner class CodexStreamEventEmitter(
        private val resolvedName: String,
        private val invocationId: String,
        private val onTextDelta: ((String) -> Unit)?
    ) : ExternalStreamEventEmitter {
        private val label = invocationLabel(resolvedName, invocationId)
        private val seenMessages = linkedSetOf<String>()
        private var lastAgentText = ""

        // `codex exec --json` emits one ThreadEvent per line (codex-sdk schema, verified
        // against codex 0.142.3):
        //   thread.started | turn.started | turn.completed | turn.failed | error
        //   item.started | item.updated | item.completed   — each carries `item`
        // The content discriminator is the NESTED item.type (agent_message / reasoning /
        // command_execution / mcp_tool_call / file_change / web_search / todo_list /
        // error); the text lives on item.text / item.aggregated_output — NOT on the
        // top-level object. Lines that are not JSON (codex run without --json, or stray
        // stdout) fall back to a raw passthrough so operators still see live output.
        override fun handleLine(line: String) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) return

            val obj = parseJsonObjectOrNull(trimmed)
            if (obj == null) {
                emitText(trimmed)
                return
            }

            when (obj.stringField("type")?.lowercase()) {
                "item.started" -> (obj["item"] as? JsonObject)?.let { handleItem(it, CodexItemPhase.STARTED) }
                "item.updated" -> (obj["item"] as? JsonObject)?.let { handleItem(it, CodexItemPhase.UPDATED) }
                "item.completed" -> (obj["item"] as? JsonObject)?.let { handleItem(it, CodexItemPhase.COMPLETED) }
                "turn.failed" -> emitError((obj["error"] as? JsonObject)?.stringField("message") ?: compactJson(obj, 800))
                "error" -> emitError(obj.stringField("message") ?: compactJson(obj, 800))
                "turn.completed" -> emitTurnUsage(obj)
                // thread.started / turn.started are lifecycle markers.
                "thread.started", "turn.started" -> Unit
                // Unknown / legacy envelope: surface raw rather than silently dropping it.
                else -> emitGeneric(obj)
            }
        }

        private fun emitTurnUsage(obj: JsonObject) {
            val usage = obj["usage"] as? JsonObject ?: return
            val input = usage.longField("input_tokens")?.coerceAtLeast(0L) ?: 0L
            val output = usage.longField("output_tokens")?.coerceAtLeast(0L) ?: 0L
            if (input <= 0L && output <= 0L) return
            emit(
                type = "codex_sub_agent_usage",
                summary = "🔤 Codex Token 用量: $label (+${input + output})",
                detail = buildString {
                    append("invocation_id=$invocationId")
                    append(", input_tokens=$input")
                    append(", output_tokens=$output")
                    append(", total_tokens=${input + output}")
                    usage.longField("cached_input_tokens")?.let { append(", cache_read=$it") }
                }
            )
        }

        private fun handleItem(item: JsonObject, phase: CodexItemPhase) {
            when (item.stringField("type")?.lowercase()) {
                "reasoning" -> item.stringField("text")?.takeIf { it.isNotBlank() }?.let { emitReasoning(it) }
                "agent_message" -> item.stringField("text")?.takeIf { it.isNotBlank() }?.let { emitText(it) }
                "command_execution" -> emitCommandExecution(item, phase)
                "mcp_tool_call" -> emitMcpToolCall(item, phase)
                "web_search" -> emitWebSearch(item, phase)
                "file_change" -> if (phase == CodexItemPhase.COMPLETED) emitFileChange(item)
                "todo_list" -> emitTodoList(item)
                "error" -> emitError(item.stringField("message") ?: compactJson(item, 800))
                else -> {
                    val text = item.stringField("text") ?: item.stringField("message")
                    if (!text.isNullOrBlank()) emitText(text) else emitGeneric(item)
                }
            }
        }

        private fun emitText(text: String) {
            val delta = when {
                lastAgentText.isEmpty() -> text
                text.startsWith(lastAgentText) -> text.removePrefix(lastAgentText)
                else -> text
            }
            lastAgentText = text
            if (delta.isBlank()) return
            onTextDelta?.invoke(delta)
            emitOnce(
                type = "codex_stream_text_delta",
                summary = buildSummaryWithPreview("💬 Codex 输出: $label", delta),
                detail = truncateDetail("invocation_id=$invocationId\n$delta")
            )
        }

        private fun emitReasoning(text: String) {
            emitOnce(
                type = "codex_stream_reasoning_summary",
                summary = buildSummaryWithPreview("💭 Codex 思考摘要: $label", text),
                detail = truncateDetail("invocation_id=$invocationId\n$text")
            )
        }

        private fun emitCommandExecution(item: JsonObject, phase: CodexItemPhase) {
            val command = item.stringField("command")?.takeIf { it.isNotBlank() } ?: "(shell)"
            when (phase) {
                CodexItemPhase.STARTED ->
                    emitOnce(
                        type = "codex_stream_tool_call",
                        summary = buildSummaryWithPreview("🔧 Codex 执行命令: $label", command),
                        detail = truncateDetail("invocation_id=$invocationId\ncommand=$command")
                    )
                CodexItemPhase.COMPLETED -> {
                    val output = item.stringField("aggregated_output").orEmpty()
                    val exit = item.longField("exit_code")
                    val status = item.stringField("status") ?: "completed"
                    emitOnce(
                        type = "codex_stream_tool_result",
                        summary = buildSummaryWithPreview("📥 Codex 命令结果($status): $label", output.ifBlank { command }),
                        detail = truncateDetail(
                            buildString {
                                append("invocation_id=$invocationId\ncommand=$command")
                                exit?.let { append("\nexit_code=$it") }
                                if (output.isNotBlank()) append('\n').append(output)
                            }
                        )
                    )
                }
                // Streaming stdout growth — the started/completed bracket is enough.
                CodexItemPhase.UPDATED -> Unit
            }
        }

        private fun emitMcpToolCall(item: JsonObject, phase: CodexItemPhase) {
            val server = item.stringField("server") ?: "mcp"
            val tool = item.stringField("tool") ?: "tool"
            val name = "$server.$tool"
            when (phase) {
                CodexItemPhase.STARTED ->
                    emitOnce(
                        type = "codex_stream_tool_call",
                        summary = "🔧 Codex 调用工具: $label / $name",
                        detail = truncateDetail(
                            buildString {
                                append("invocation_id=$invocationId\nserver=$server, tool=$tool")
                                item["arguments"]?.let { append("\narguments=").append(compactJson(it, 900)) }
                            }
                        )
                    )
                CodexItemPhase.COMPLETED -> {
                    val status = item.stringField("status") ?: "completed"
                    val payload = (item["error"] as? JsonObject)?.stringField("message")
                        ?: item["result"]?.let { compactJson(it, 900) }
                        ?: status
                    emitOnce(
                        type = "codex_stream_tool_result",
                        summary = buildSummaryWithPreview("📥 Codex 工具结果($status): $label / $name", payload),
                        detail = truncateDetail("invocation_id=$invocationId\nstatus=$status\n$payload")
                    )
                }
                CodexItemPhase.UPDATED -> Unit
            }
        }

        private fun emitWebSearch(item: JsonObject, phase: CodexItemPhase) {
            val query = item.stringField("query").orEmpty()
            when (phase) {
                CodexItemPhase.STARTED ->
                    emitOnce(
                        type = "codex_stream_tool_call",
                        summary = buildSummaryWithPreview("🔧 Codex 网络搜索: $label", query),
                        detail = truncateDetail("invocation_id=$invocationId\nquery=$query")
                    )
                CodexItemPhase.COMPLETED ->
                    emitOnce(
                        type = "codex_stream_tool_result",
                        summary = buildSummaryWithPreview("📥 Codex 搜索完成: $label", query),
                        detail = truncateDetail("invocation_id=$invocationId\nquery=$query")
                    )
                CodexItemPhase.UPDATED -> Unit
            }
        }

        // file_change is emitted once at its terminal state (patch succeeded / failed).
        private fun emitFileChange(item: JsonObject) {
            val status = item.stringField("status") ?: "completed"
            val paths = (item["changes"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.mapNotNull { c -> c.stringField("path")?.let { p -> "${c.stringField("kind") ?: "update"} $p" } }
                ?.joinToString(", ")
                .orEmpty()
            emitOnce(
                type = "codex_stream_tool_result",
                summary = buildSummaryWithPreview("📝 Codex 文件改动($status): $label", paths),
                detail = truncateDetail("invocation_id=$invocationId\nstatus=$status\n$paths")
            )
        }

        private fun emitTodoList(item: JsonObject) {
            val rendered = (item["items"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.joinToString("\n") { t ->
                    val done = (t["completed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
                    "${if (done) "[x]" else "[ ]"} ${t.stringField("text").orEmpty()}"
                }
                .orEmpty()
            if (rendered.isBlank()) return
            emitOnce(
                type = "codex_stream_reasoning_summary",
                summary = buildSummaryWithPreview("🗒️ Codex 计划: $label", rendered.replace('\n', ' ')),
                detail = truncateDetail("invocation_id=$invocationId\n$rendered")
            )
        }

        private fun emitError(text: String) {
            emitOnce(
                type = "codex_stream_error",
                summary = buildSummaryWithPreview("⚠️ Codex 错误: $label", text),
                detail = truncateDetail("invocation_id=$invocationId\n$text")
            )
        }

        private fun emitGeneric(obj: JsonObject) {
            val type = obj.stringField("type") ?: "event"
            emitOnce(
                type = "codex_stream_event",
                summary = "🧬 Codex 事件: $label / $type",
                detail = "invocation_id=$invocationId, payload=${compactJson(obj, 900)}"
            )
        }

        private fun emitOnce(type: String, summary: String, detail: String?) {
            val fingerprint = "$type|$summary|${detail.orEmpty().take(200)}"
            if (!seenMessages.add(fingerprint)) return
            emit(type = type, summary = summary, detail = detail)
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

    private fun ExternalAgentContext.withClaudePartialRateLimitReplayInstruction(): ExternalAgentContext {
        if (prompt.startsWith(CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_INSTRUCTION)) return this
        return copy(
            prompt = "$CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_INSTRUCTION\n\n$prompt"
        )
    }

    private fun buildCommand(
        engine: Engine,
        ctx: ExternalAgentContext,
        model: String,
        auth: ResolvedExternalAuth,
        workDir: File,
        tempFiles: MutableList<File>
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
            Engine.CLAUDE -> buildClaudeFlags(ctx, model, workDir, tempFiles)
            Engine.CODEX -> buildCodexFlags(ctx, model, auth.mode == ExternalAuthMode.SUBSCRIPTION)
        }

        return buildList {
            add(baseCommand)
            addAll(extraArgs)
            addAll(cliFlags)
        }.also(::validateCommandArguments)
    }

    private fun buildClaudeFlags(
        ctx: ExternalAgentContext,
        model: String,
        workDir: File,
        tempFiles: MutableList<File>
    ): List<String> = buildList {
        // `-p` (= --print) runs Claude non-interactively. With no positional
        // prompt Claude reads text from stdin; the executor closes stdin after
        // writing so even multi-megabyte prompts avoid execve argv limits.
        add("-p")
        ctx.resumeSessionId.trim().takeIf { it.isNotEmpty() }?.let { sessionId ->
            add("--resume")
            add(sessionId)
        }
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
        val mcpConfig = materializeClaudeMcpConfig(ctx, workDir, tempFiles)
        if (mcpConfig != null) {
            add("--mcp-config")
            add(mcpConfig.cliPath)
        }
        add("--output-format")
        add("stream-json")
        // Claude CLI rejects `-p --output-format=stream-json` unless --verbose is set
        // ("Error: When using --print, --output-format=stream-json requires --verbose").
        add("--verbose")
        // Real token-level streaming: without this, `stream-json` emits only
        // COMPLETE assistant messages (the whole answer lands in one block after
        // the model finishes — verified on claude 2.1.179). With it, text arrives
        // as `stream_event` → `content_block_delta` tokens, which
        // ClaudeStreamEventEmitter forwards live to the chat bubble.
        add("--include-partial-messages")
        add("--include-hook-events")
        // --dangerously-skip-permissions bypasses Claude Code's ENTIRE tool permission
        // gate, including --allowedTools/--disallowedTools — it does not just silence
        // confirmation prompts. Callers that need a hard tool allowlist enforced (e.g.
        // MCP-only authoring flows) must pass claudeSkipPermissions = false explicitly;
        // otherwise the executor default (trusted Docker sandbox) applies.
        if (ctx.claudeSkipPermissions ?: trustExecutorSandbox) {
            add("--dangerously-skip-permissions")
        }
        // `--tools` cuts built-in tools out of the session's tool *registry* — a
        // structural boundary independent of --allowedTools/--disallowedTools and of
        // --dangerously-skip-permissions (which only bypasses the permission *check*,
        // not registration). MCP-provided tools are unaffected. "" is the strongest
        // available guarantee for MCP-only flows: even if claudeSkipPermissions or the
        // disallow list is ever misconfigured, there is no Bash/Read/Write/Edit tool
        // for the model to call in the first place.
        ctx.claudeBuiltinTools?.let { tools ->
            add("--tools")
            add(tools)
        }
        if (model.isNotBlank()) {
            add("--model")
            add(model)
        }
        if (ctx.maxTurns > 0) {
            add("--max-turns")
            add(ctx.maxTurns.toString())
        }
        val allowedTools = (ctx.allowedTools + ctx.mcpServers.flatMap { it.allowedTools }).distinct()
        if (allowedTools.isNotEmpty()) {
            add("--allowedTools")
            add(allowedTools.joinToString(","))
        }
        if (ctx.disallowedTools.isNotEmpty()) {
            add("--disallowedTools")
            add(ctx.disallowedTools.joinToString(","))
        }
        // No positional prompt: Claude reads ExecRequest.stdin. Keeping the
        // payload out of this list is the hard guarantee against E2BIG.
    }

    private data class ClaudeMcpConfigFile(
        val cliPath: String,
    )

    private fun materializeClaudeMcpConfig(
        ctx: ExternalAgentContext,
        workDir: File,
        tempFiles: MutableList<File>
    ): ClaudeMcpConfigFile? {
        if (ctx.mcpServers.isEmpty()) return null
        val root = buildJsonObject {
            put("mcpServers", buildJsonObject {
                ctx.mcpServers.forEach { server ->
                    put(server.name, buildJsonObject {
                        put("command", JsonPrimitive(server.command))
                        put("args", JsonArray(server.args.map { JsonPrimitive(it) }))
                        put("env", JsonObject(server.env.mapValues { JsonPrimitive(it.value) }))
                    })
                }
            })
        }
        val file = if (isDocker) {
            // DockerSubprocessExecutor bind-mounts the host working directory at
            // /workspace. A host /tmp file is invisible inside the container, so
            // materialize the MCP config in the mounted workspace and pass the
            // corresponding container path to Claude.
            File(workDir, ".braidrun-claude-mcp-${UUID.randomUUID()}.json")
        } else {
            File.createTempFile("braidrun-claude-mcp-", ".json")
        }
        file.writeText(Json.encodeToString(root), Charsets.UTF_8)
        if (isDocker) {
            file.setReadable(true, false)
            file.setWritable(true, true)
        }
        tempFiles += file
        val cliPath = if (isDocker) "/workspace/${file.name}" else file.absolutePath
        return ClaudeMcpConfigFile(cliPath)
    }

    private fun buildCodexFlags(
        ctx: ExternalAgentContext,
        model: String,
        subscription: Boolean
    ): List<String> = buildList {
        val resumeSessionId = ctx.resumeSessionId.trim().takeIf { it.isNotEmpty() }
        add("exec")
        if (resumeSessionId != null) {
            add("resume")
        }
        // Allow running outside a git repo — the bind-mounted workspace is usually
        // not a git checkout, and `codex exec` otherwise refuses to start.
        add("--skip-git-repo-check")
        val codexBypassSandbox = ctx.codexBypassApprovalsAndSandbox
            ?: trustExecutorSandbox
        val codexSandboxMode = ctx.codexSandboxMode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (codexBypassSandbox) {
            // In production, Codex already runs inside Braidrun's Docker sandbox
            // (read-only rootfs, non-root UID, restricted network, bounded streams).
            // Skip Codex's own interactive approval prompts so workflow steps cannot
            // wedge waiting for an operator inside a headless subprocess.
            add("--dangerously-bypass-approvals-and-sandbox")
        } else if (codexSandboxMode != null) {
            if (resumeSessionId == null) {
                add("--sandbox")
                add(codexSandboxMode)
            } else {
                // `codex exec resume` does not expose the parent command's
                // --sandbox option. Its equivalent is the config override,
                // and resume options must precede the positional session id.
                add("-c")
                add("sandbox_mode=${tomlString(codexSandboxMode)}")
            }
            // `codex exec` has no interactive terminal to answer an approval prompt, and
            // this build has no --ask-for-approval flag (verified against codex-cli
            // 0.142.5's `codex exec --help`) — only `-c key=value` TOML overrides reach
            // approval_policy. Without forcing it to "never", any model action the
            // sandbox alone doesn't cover would sit waiting for an approval that can
            // never arrive until the caller's timeoutSeconds elapses, reintroducing the
            // exact "runs 20+ min with no output" hang --dangerously-bypass-approvals-and-sandbox
            // was originally added to avoid. "never" fails the action closed instead of
            // hanging, which is what we want for an MCP-only authoring turn.
            add("-c")
            add("approval_policy=${tomlString("never")}")
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
        ctx.mcpServers.forEach { server ->
            add("-c")
            add("mcp_servers.${server.name}.command=${tomlString(server.command)}")
            if (server.args.isNotEmpty()) {
                add("-c")
                add("mcp_servers.${server.name}.args=[${server.args.joinToString(",") { tomlString(it) }}]")
            }
            server.env.forEach { (key, value) ->
                add("-c")
                add("mcp_servers.${server.name}.env.${key}=${tomlString(value)}")
            }
        }
        // Emit structured events as JSONL. This is what lets the debug / monitoring
        // layer reconstruct Codex's reasoning summary and tool calls (parsed by
        // CodexStreamEventEmitter) and lets parseCodexOutput recover the final
        // `agent_message` item as the tool's return value. Without it `codex exec`
        // prints human-readable prose, the emitter can only echo raw text, and the
        // reasoning / tool views stay empty. `codex exec --json` predates the 0.140
        // floor these images pin (verified against 0.142.3). The `--full-auto` flag
        // remains version-dependent; operators can still append flags through
        // external_agent_codex_extra_args.
        add("--json")
        resumeSessionId?.let(::add)
        //
        // `-` explicitly tells both `codex exec` and `codex exec resume` to read
        // the prompt from stdin. `--` prevents it from being interpreted as an
        // option or subcommand. The actual prompt never enters argv.
        add("--")
        add("-")
    }

    private fun validateCommandArguments(command: List<String>) {
        command.forEachIndexed { index, argument ->
            val bytes = argument.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_COMMAND_ARGUMENT_BYTES) {
                "External-agent command argument[$index] is $bytes bytes; maximum is " +
                    "$MAX_COMMAND_ARGUMENT_BYTES bytes. Move large payloads to stdin or a file."
            }
        }
        val totalBytes = command.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        require(totalBytes <= MAX_COMMAND_TOTAL_BYTES) {
            "External-agent command argv is $totalBytes bytes; maximum is $MAX_COMMAND_TOTAL_BYTES bytes. " +
                "Reduce CLI flags or materialize configuration in a file."
        }
    }

    private fun tomlString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""

    private fun buildEnvironment(engine: Engine, auth: ResolvedExternalAuth, ctx: ExternalAgentContext): Map<String, String> = buildMap {
        putAll(auth.env)
        putAll(ctx.extraEnv)
        com.fartech.agents.workflow.WorkflowHostContext.current?.privateDirectory?.let { privateRoot ->
            val reserved = setOf("HOME", "CODEX_HOME", "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY")
            require(ctx.extraEnv.keys.none { it.uppercase() in reserved }) { "External Agent environment cannot override host account/network scope" }
            val home = File(privateRoot, "home/${safePathSegment(context.executionId ?: "root")}/${safePathSegment(context.stepName ?: "agent")}")
            createDirectories(home, "Runner scoped HOME")
            java.nio.file.Files.setPosixFilePermissions(home.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            put("HOME", home.absolutePath)
            putAll(requireNotNull(com.fartech.agents.workflow.WorkflowHostContext.current).codeEnvironment)
            context.executionId?.let { put("WF_EXECUTION_ID", it) }
            context.stepName?.let { put("WF_STEP_NAME", it) }
        }
        // Codex writes session / cache / state into CODEX_HOME (default ~/.codex) and
        // touches HOME on startup. In the read-only-rootfs container both default to
        // the non-writable /home/runner, so codex aborts with "Permission denied
        // (os error 13)" regardless of auth mode. Point both at the writable tmpfs.
        // Subscription and API-key mode normally already set CODEX_HOME to the
        // bind-mounted per-run host dir (putIfAbsent keeps it); the tmpfs path is
        // only the fallback when that dir could not be created.
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
        // Codex: bind the per-run host CODEX_HOME (holding the tenant's auth.json in
        // subscription mode, empty in API-key mode) into the container. RW because
        // codex writes cache / session / state files there during the run, and the
        // session rollout must stay readable from the host for usage recovery.
        auth.codexHomeHostDir?.let { homeDir ->
            val canonical = runCatching { homeDir.canonicalFile }.getOrDefault(homeDir.absoluteFile)
            mounts += SubprocessExecutor.Mount(
                hostPath = canonical,
                containerPath = CODEX_HOME_CONTAINER_PATH,
                readOnly = false
            )
        }
        auth.claudeConfigHostDir?.let { configDir ->
            val canonical = runCatching { configDir.canonicalFile }.getOrDefault(configDir.absoluteFile)
            mounts += SubprocessExecutor.Mount(
                hostPath = canonical,
                containerPath = auth.claudeConfigContainerPath ?: canonical.absolutePath,
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
        val combined = listOf(
            workflowPrompt,
            configuredPrompt,
            DEFAULT_CLAUDE_CHINESE_SYSTEM_PROMPT
        )
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return combined.boundUtf8Argument(MAX_COMMAND_ARGUMENT_BYTES)
    }

    private fun String.boundUtf8Argument(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        val markerBytes = COMMAND_ARGUMENT_TRUNCATION_MARKER.toByteArray(Charsets.UTF_8).size
        val contentBytes = maxBytes - markerBytes
        val prefixBytes = contentBytes / 2
        val suffixBytes = contentBytes - prefixBytes
        return takeUtf8Prefix(prefixBytes) + COMMAND_ARGUMENT_TRUNCATION_MARKER + takeUtf8Suffix(suffixBytes)
    }

    private fun String.takeUtf8Prefix(maxBytes: Int): String {
        var index = 0
        var used = 0
        while (index < length) {
            val codePoint = Character.codePointAt(this, index)
            val width = codePoint.utf8Width()
            if (used + width > maxBytes) break
            used += width
            index += Character.charCount(codePoint)
        }
        return substring(0, index)
    }

    private fun String.takeUtf8Suffix(maxBytes: Int): String {
        var index = length
        var used = 0
        while (index > 0) {
            val codePoint = Character.codePointBefore(this, index)
            val width = codePoint.utf8Width()
            if (used + width > maxBytes) break
            used += width
            index -= Character.charCount(codePoint)
        }
        return substring(index)
    }

    private fun Int.utf8Width(): Int = when {
        this <= 0x7f -> 1
        this <= 0x7ff -> 2
        this <= 0xffff -> 3
        else -> 4
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

    /** The pool for [engine], or null when the caller wired none. Never crosses engines. */
    private fun subscriptionCredentialProvider(engine: Engine): ClaudeCredentialProvider? = when (engine) {
        Engine.CLAUDE -> claudeCredentialProvider
        Engine.CODEX -> codexCredentialProvider
    }

    private suspend fun resolveExternalAuth(
        engine: Engine,
        ctx: ExternalAgentContext,
        invocationId: String,
        attemptedCredentialIds: Set<String>
    ): ResolvedExternalAuth {
        val mode = ExternalAuthMode.parse(
            parameters.parameter(engine.authModeParameterKey, "api_key"),
            engine.authModeParameterKey
        )

        if (engine == Engine.CODEX && parameters.parameter(CODEX_AUTH_FILE_PARAMETER, "").isNotBlank()) {
            require(!isDocker && !executor.isDocker) {
                "$CODEX_AUTH_FILE_PARAMETER is available only in native mode"
            }
            require(mode == ExternalAuthMode.SUBSCRIPTION) {
                "$CODEX_AUTH_FILE_PARAMETER requires subscription authentication"
            }
            require(parameters.parameter(CODEX_HOME_DIR_PARAMETER, "").isBlank() && ctx.resumeSessionId.isBlank()) {
                "$CODEX_AUTH_FILE_PARAMETER requires an isolated, non-resumed Codex invocation"
            }
        }

        if (mode == ExternalAuthMode.SUBSCRIPTION) {
            return when (engine) {
                Engine.CLAUDE -> resolveClaudeSubscriptionAuth(attemptedCredentialIds)
                Engine.CODEX -> resolveCodexSubscriptionAuth(ctx, invocationId, attemptedCredentialIds)
            }
        }

        val apiKey = resolveApiKey(engine)
            ?: throw IllegalStateException(
                "Missing API key for ${engine.displayName}. " +
                    "Set workflow parameter '${engine.providerKey}_api_key', " +
                    "or 'llm_provider_keys.${engine.providerKey}', " +
                    "or environment variable ${engine.apiKeyEnvVar}."
            )
        // Docker API-key runs used to keep CODEX_HOME on the container's tmpfs, which
        // disappears with the container — and with it the session rollout, the only
        // usage record of a run killed before `turn.completed`. A per-invocation host
        // dir bind-mounted into the container keeps it readable from the host until
        // this invocation's cleanup deletes it. Native API-key runs keep the
        // operator's own CODEX_HOME, which the engine does not manage.
        val apiKeyCodexHome = if (engine == Engine.CODEX && isDocker) createEphemeralCodexHome(invocationId) else null
        return ResolvedExternalAuth(
            mode = ExternalAuthMode.API_KEY,
            env = buildMap {
                put(engine.apiKeyEnvVar, apiKey)
                if (apiKeyCodexHome != null) put("CODEX_HOME", CODEX_HOME_CONTAINER_PATH)
            },
            codexHomeHostDir = apiKeyCodexHome
        )
    }

    private suspend fun resolveClaudeSubscriptionAuth(
        attemptedCredentialIds: Set<String>
    ): ResolvedExternalAuth {
        val candidate = claudeCredentialProvider?.acquire(attemptedCredentialIds)
        return releasingOnFailure(Engine.CLAUDE, candidate) { claudeSubscriptionAuth(candidate, attemptedCredentialIds) }
    }

    /** An attempt that cannot even start still ends the credential hold it acquired. */
    private suspend fun releasingOnFailure(
        engine: Engine,
        candidate: ClaudeCredentialProvider.Credential?,
        resolve: suspend () -> ResolvedExternalAuth
    ): ResolvedExternalAuth = try {
        resolve()
    } catch (failure: Throwable) {
        releaseSubscriptionCredential(engine, candidate)
        throw failure
    }

    private fun claudeSubscriptionAuth(
        candidate: ClaudeCredentialProvider.Credential?,
        attemptedCredentialIds: Set<String>
    ): ResolvedExternalAuth {
        if (claudeCredentialProvider != null && candidate == null) {
            throw ExternalAgentExecutionException("All accessible Claude subscription credentials are unavailable")
        }
        if (candidate?.id in attemptedCredentialIds) {
            throw ExternalAgentExecutionException(
                "Claude credential provider returned an already-attempted credential"
            )
        }
        val token = candidate?.token?.let(::normalizeOAuthToken) ?: resolveClaudeCodeOAuthToken()
            ?: throw IllegalStateException(
                "Missing Claude subscription OAuth token. Create a credential with provider " +
                    "'$CLAUDE_CODE_OAUTH_PROVIDER', or set workflow parameter " +
                    "'$CLAUDE_OAUTH_TOKEN_PARAMETER'."
            )
        val configDir = resolveClaudeConfigDir()
        return ResolvedExternalAuth(
            mode = ExternalAuthMode.SUBSCRIPTION,
            env = buildMap {
                put("CLAUDE_CODE_OAUTH_TOKEN", token)
                put("CLAUDE_CONFIG_DIR", configDir.containerPath)
                put("DISABLE_AUTOUPDATER", "1")
            },
            subscriptionCredentialId = candidate?.id,
            subscriptionCredentialLabel = candidate?.label,
            subscriptionCredential = candidate,
            claudeConfigHostDir = configDir.hostDir,
            claudeConfigContainerPath = configDir.containerPath
        )
    }

    /**
     * Codex subscription auth: unlike Claude (a single long-lived token in an env
     * var), the `codex` CLI only reads ChatGPT credentials from `CODEX_HOME/auth.json`.
     * We write the tenant's `auth.json` into a per-run dir and point `CODEX_HOME` at
     * it. Verified locally: a transplanted auth.json + `--ignore-user-config` is
     * accepted. The CLI may rewrite the file when it refreshes an access token; the
     * caller can persist that rotated JSON via [onCodexAuthJsonRotated].
     */
    private suspend fun resolveCodexSubscriptionAuth(
        ctx: ExternalAgentContext,
        invocationId: String,
        attemptedCredentialIds: Set<String>
    ): ResolvedExternalAuth {
        val authFile = loadCodexAuthFile()
        val candidate = if (authFile == null) codexCredentialProvider?.acquire(attemptedCredentialIds) else null
        return releasingOnFailure(Engine.CODEX, candidate) {
            codexSubscriptionAuth(ctx, invocationId, attemptedCredentialIds, authFile, candidate)
        }
    }

    private fun codexSubscriptionAuth(
        ctx: ExternalAgentContext,
        invocationId: String,
        attemptedCredentialIds: Set<String>,
        authFile: Pair<CodexAuthFileSource, String>?,
        candidate: ClaudeCredentialProvider.Credential?
    ): ResolvedExternalAuth {
        if (authFile == null && codexCredentialProvider != null && candidate == null) {
            throw ExternalAgentExecutionException("All accessible Codex subscription credentials are unavailable")
        }
        if (candidate?.id in attemptedCredentialIds) {
            throw ExternalAgentExecutionException(
                "Codex credential provider returned an already-attempted credential"
            )
        }
        val authJson = authFile?.second ?: candidate?.token?.let(::normalizeCodexAuthJson)
            ?: resolveCodexAuthJson()
            ?: throw IllegalStateException(
                "Missing Codex subscription credential. Create a credential with provider " +
                    "'$CODEX_SUBSCRIPTION_PROVIDER' (the contents of ~/.codex/auth.json from a " +
                    "`codex login` ChatGPT session), or set workflow parameter " +
                    "'$CODEX_AUTH_JSON_PARAMETER'."
            )
        val deleteHomeOnExit = authFile != null || shouldDeleteCodexHomeOnExit(ctx)
        val homeDir = materializeCodexHome(
            authJson = authJson,
            invocationId = invocationId,
            ephemeral = deleteHomeOnExit
        )
        val codexHomeEnv = if (isDocker) CODEX_HOME_CONTAINER_PATH else homeDir.absolutePath
        // HOME (and an API-key-mode CODEX_HOME fallback) are set centrally for codex in
        // [buildEnvironment]; here we only pin CODEX_HOME to the materialised auth dir.
        return ResolvedExternalAuth(
            mode = ExternalAuthMode.SUBSCRIPTION,
            env = mapOf("CODEX_HOME" to codexHomeEnv),
            subscriptionCredentialId = candidate?.id,
            subscriptionCredentialLabel = candidate?.label,
            subscriptionCredential = candidate,
            codexHomeHostDir = homeDir,
            originalCodexAuthJson = authJson,
            codexAuthFileSource = authFile?.first,
            deleteCodexHomeOnExit = deleteHomeOnExit
        )
    }

    /**
     * Native CLI integration: pass only a private file's absolute path through runtime
     * parameters. Never materialize its contents in workflow definitions or argv.
     * Existing inline/provider credential paths are unchanged when this is absent.
     */
    private fun loadCodexAuthFile(): Pair<CodexAuthFileSource, String>? {
        val configured = parameters.parameter(CODEX_AUTH_FILE_PARAMETER, "").trim()
        if (configured.isEmpty()) return null
        require(!isDocker && !executor.isDocker) {
            "$CODEX_AUTH_FILE_PARAMETER is available only in native mode"
        }
        val path = Path.of(configured)
        require(path.isAbsolute) { "$CODEX_AUTH_FILE_PARAMETER must be an absolute path" }
        val normalized = path.normalize()
        validatePrivateCodexAuthFile(normalized)
        val canonical = normalized.toRealPath()
        listOfNotNull(context.workspaceDir, context.outputDir).forEach { directory ->
            val root = directory.canonicalFile.toPath()
            require(!canonical.startsWith(root)) {
                "$CODEX_AUTH_FILE_PARAMETER must be outside the workflow workspace and output directory"
            }
        }
        val bytes = readBoundedCodexAuthFile(normalized)
        val authJson = normalizeCodexAuthJson(bytes.toString(Charsets.UTF_8))
            ?: throw IllegalArgumentException("Local Codex authentication file is empty")
        return CodexAuthFileSource(canonical, sha256(bytes)) to authJson
    }

    private fun validatePrivateCodexAuthFile(path: Path) {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            "Local Codex authentication file must be an existing regular file, not a symbolic link"
        }
        val owner = path.fileSystem.userPrincipalLookupService
            .lookupPrincipalByName(System.getProperty("user.name"))
        require(Files.getOwner(path, NOFOLLOW_LINKS) == owner) {
            "Local Codex authentication file must belong to the current operating-system user"
        }
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        require(permissions == CODEX_AUTH_FILE_PERMISSIONS) {
            "Local Codex authentication file must have owner-only read/write permissions (0600)"
        }
    }

    private fun readBoundedCodexAuthFile(path: Path): ByteArray =
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val bytes = input.readNBytes(MAX_CODEX_AUTH_FILE_BYTES + 1)
            require(bytes.size <= MAX_CODEX_AUTH_FILE_BYTES) { "Local Codex authentication file exceeds the size limit" }
            bytes
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Serialize Braidrun writers across threads/processes, compare the original bytes,
     * and atomically replace only an unchanged source. A newer login/refresh wins.
     * The lock file is deliberately retained so concurrent writers share one inode.
     */
    private fun persistRotatedCodexAuthFile(source: CodexAuthFileSource, authJson: String) {
        val lock = CODEX_AUTH_FILE_LOCKS.computeIfAbsent(source.path.toString()) { Any() }
        synchronized(lock) {
            validatePrivateCodexAuthFile(source.path)
            val lockPath = source.path.resolveSibling(".${source.path.fileName}.braidrun.lock")
            val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
            FileChannel.open(lockPath, options, PosixFilePermissions.asFileAttribute(CODEX_AUTH_FILE_PERMISSIONS)).use { channel ->
                channel.lock().use {
                    validatePrivateCodexAuthFile(source.path)
                    if (!sha256(readBoundedCodexAuthFile(source.path)).contentEquals(source.originalDigest)) {
                        logger.info { "[ExternalAgent] Local Codex authentication changed during execution; keeping the newer source" }
                        return
                    }
                    val temporary = Files.createTempFile(
                        source.path.parent, ".braidrun-auth-", ".tmp",
                        PosixFilePermissions.asFileAttribute(CODEX_AUTH_FILE_PERMISSIONS)
                    )
                    try {
                        Files.writeString(temporary, authJson)
                        // Recheck immediately before rename, including changes from another login.
                        validatePrivateCodexAuthFile(source.path)
                        if (!sha256(readBoundedCodexAuthFile(source.path)).contentEquals(source.originalDigest)) return
                        Files.move(temporary, source.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
            }
        }
    }

    private fun shouldDeleteCodexHomeOnExit(ctx: ExternalAgentContext): Boolean {
        val configuredHome = parameters.parameter(CODEX_HOME_DIR_PARAMETER, "").trim()
        if (configuredHome.isNotEmpty()) return false
        if (ctx.resumeSessionId.isNotBlank()) return false
        // WorkflowExecutor injects a session_id into every workflow agent for
        // Koog state/history namespacing, even when this external Codex call is
        // a one-shot invocation. Treating that runtime id as a Codex
        // conversation bypasses per-invocation CODEX_HOME isolation. Workflow
        // executions stay ephemeral unless the caller explicitly resumes one.
        if (!context.executionId.isNullOrBlank()) return true
        if (!context.sessionId.isNullOrBlank()) return false
        return true
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
    private fun materializeCodexHome(
        authJson: String,
        invocationId: String,
        ephemeral: Boolean
    ): File {
        val configured = parameters.parameter(CODEX_HOME_DIR_PARAMETER, "").trim()
        val base = if (configured.isNotEmpty()) {
            File(configured)
        } else {
            defaultCodexHomeDir(invocationId, ephemeral)
        }
        createDirectories(base, "Codex home")
        val authFile = File(base, "auth.json")
        authFile.writeText(authJson)
        applyCodexHomePermissions(base, authFile)
        return base
    }

    private fun defaultCodexHomeDir(invocationId: String, ephemeral: Boolean): File {
        val runId = context.executionId ?: context.sessionId ?: UUID.randomUUID().toString()
        val userDir = File(
            File(com.fartech.agents.workflow.WorkflowHostContext.current?.privateDirectory ?: File(System.getProperty("java.io.tmpdir")), "braidrun-codex"),
            safePathSegment(userId)
        )
        val invocationBase = File(
            File(userDir, safePathSegment(runId)),
            safePathSegment(
                listOfNotNull(context.stepName, context.sessionId)
                    .joinToString("_")
                    .ifBlank { "external-agent" }
            )
        )
        // Ephemeral workflow calls can run concurrently from the same step. Each
        // call deletes its CODEX_HOME in finally, so sharing invocationBase lets
        // the first completed child remove auth/state underneath a sibling that
        // is still starting. Add the real per-call id only for ephemeral homes;
        // persistent conversation/resume homes must keep their stable path.
        return if (ephemeral) File(invocationBase, safePathSegment(invocationId)) else invocationBase
    }

    /**
     * An empty per-invocation CODEX_HOME on the host (no auth.json) for API-key runs
     * in Docker. Null when it cannot be created — the run then falls back to the
     * container tmpfs and only loses usage recovery, never the run itself.
     */
    private fun createEphemeralCodexHome(invocationId: String): File? =
        runCatching {
            val dir = defaultCodexHomeDir(invocationId, ephemeral = true)
            createDirectories(dir, "Codex home")
            applyCodexHomePermissions(dir, authFile = null)
            dir
        }.onFailure { e ->
            logger.warn(e) { "[ExternalAgent] Could not create a host CODEX_HOME; Codex usage recovery is unavailable for this run" }
        }.getOrNull()

    /**
     * Docker mode runs the codex container as an unprivileged uid (2000:2000) that
     * differs from the agent JVM which created this dir. The bind-mounted CODEX_HOME
     * must therefore be writable by that other uid (codex writes cache / session /
     * state files into it) and the auth.json readable by it — otherwise codex aborts
     * with "Permission denied (os error 13)". So we open the perms up (the dir is a
     * per-run host temp dir that gets cleaned up). In native mode the same uid runs
     * codex, so keep it owner-only (0700 / 0600) to limit token exposure.
     */
    private fun applyCodexHomePermissions(dir: File, authFile: File?) {
        runCatching {
            if (isDocker) {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
                authFile?.setReadable(true, false)
                authFile?.setWritable(true, false)
            } else if (authFile != null) {
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

    private data class ResolvedClaudeConfigDir(
        val hostDir: File?,
        val containerPath: String
    )

    private fun resolveClaudeConfigDir(): ResolvedClaudeConfigDir {
        val configured = parameters.parameter(CLAUDE_CONFIG_DIR_PARAMETER, "").trim()
        if (configured.isNotEmpty()) {
            val hostDir = File(configured)
            createDirectories(hostDir, "Claude config")
            applyClaudeConfigPermissions(hostDir)
            return ResolvedClaudeConfigDir(
                hostDir = hostDir.takeIf { isDocker },
                containerPath = hostDir.absolutePath
            )
        }

        val hostBase = File(com.fartech.agents.workflow.WorkflowHostContext.current?.privateDirectory ?: File(System.getProperty("java.io.tmpdir")), "braidrun-claude")
        val containerBase = if (isDocker) "/tmp/braidrun-claude" else hostBase.absolutePath
        val runId = context.executionId ?: context.sessionId ?: UUID.randomUUID().toString()
        val relative = File(safePathSegment(userId), safePathSegment(runId)).path
        val invocationSegment = context.executionId
            ?.let { context.stepName }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::safePathSegment)
        val hostDir = invocationSegment
            ?.let { File(File(hostBase, relative), it) }
            ?: File(hostBase, relative)
        val containerDir = invocationSegment
            ?.let { File(File(containerBase, relative), it) }
            ?: File(containerBase, relative)
        createDirectories(hostDir, "Claude config")
        applyClaudeConfigPermissions(hostDir)
        return ResolvedClaudeConfigDir(
            hostDir = hostDir.takeIf { isDocker },
            containerPath = containerDir.absolutePath
        )
    }

    /**
     * The production executor runs Claude as uid:gid 2000:2000 while the host
     * service commonly creates this bind-mounted directory as root. Claude
     * creates session-env and other state below the mount, so a host-default
     * 0755 directory makes Bash and session persistence fail with EACCES.
     *
     * Prefer transferring ownership to the sandbox uid and keeping mode 0700.
     * Non-root development hosts cannot chown, so they fall back to a writable
     * per-step directory; the execution/user-specific parent still limits path
     * discovery.
     */
    private fun applyClaudeConfigPermissions(directory: File) {
        if (!isDocker) return
        val path = directory.toPath()
        val sandboxOwned = runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
            // Set gid first: after ownership moves away from a non-root host
            // process, changing the remaining metadata may no longer be allowed.
            Files.setAttribute(path, "unix:gid", DOCKER_SANDBOX_GID)
            Files.setAttribute(path, "unix:uid", DOCKER_SANDBOX_UID)
            (Files.getAttribute(path, "unix:uid") as Number).toInt() == DOCKER_SANDBOX_UID
        }.getOrDefault(false)
        if (sandboxOwned) return

        val writableFallback = runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE
                )
            )
            true
        }.getOrElse {
            directory.setReadable(true, false) &&
                directory.setWritable(true, false) &&
                directory.setExecutable(true, false)
        }
        check(writableFallback) {
            "Unable to make Claude config dir writable by Docker sandbox uid 2000: " +
                directory.absolutePath
        }
    }

    private fun createDirectories(directory: File, label: String) {
        try {
            Files.createDirectories(directory.toPath())
        } catch (e: Exception) {
            throw IllegalStateException("Unable to create $label dir: ${directory.absolutePath}", e)
        }
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

    internal data class CodexUsageHome(val hostDir: File, val exclusive: Boolean)

    /**
     * One thread's session rollout, identified by its own `session_meta` (the first
     * one: a forked thread's rollout also carries copies of its ancestors' metas).
     * [inheritedUsage] is the cumulative total a fork copied from its parent and
     * kept counting from, or null when the fork started from zero.
     */
    internal data class CodexThreadRollout(
        val threadId: String,
        val usage: CodexRolloutUsage?,
        val inheritedUsage: CodexRolloutUsage?
    )

    /** What one Codex sub-agent thread spent during this invocation. */
    internal data class CodexSubAgentUsage(
        val threadId: String,
        val agentPath: String?,
        val usage: CodexRolloutUsage
    )

    /**
     * A rollout `token_count.info.total_token_usage` snapshot, in raw Codex semantics:
     * [inputTokens] includes [cachedInputTokens] and [outputTokens] includes
     * [reasoningOutputTokens] — the same shape `turn.completed` reports.
     */
    internal data class CodexRolloutUsage(
        val inputTokens: Long,
        val cachedInputTokens: Long,
        val outputTokens: Long,
        val reasoningOutputTokens: Long
    ) {
        operator fun minus(other: CodexRolloutUsage) = CodexRolloutUsage(
            inputTokens = (inputTokens - other.inputTokens).coerceAtLeast(0L),
            cachedInputTokens = (cachedInputTokens - other.cachedInputTokens).coerceAtLeast(0L),
            outputTokens = (outputTokens - other.outputTokens).coerceAtLeast(0L),
            reasoningOutputTokens = (reasoningOutputTokens - other.reasoningOutputTokens).coerceAtLeast(0L)
        )

        fun isEmpty(): Boolean = inputTokens <= 0L && outputTokens <= 0L

        fun toTokenUsage() = TokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheCreationInputTokens = null,
            cacheReadInputTokens = cachedInputTokens
        )
    }

    internal data class ExternalAgentRunDetailedResult(
        val text: String,
        val sessionId: String?,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val costUsd: Double?
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
     * Parse the assistant text + usage from `codex exec` stdout.
     *
     * Preferred path — `codex exec --json` (what [buildCodexFlags] now requests): stdout
     * is one ThreadEvent per line (codex-sdk schema). The answer the parent agent should
     * see is the text of the last `agent_message` item; usage comes from `turn.completed`,
     * and the resumable id from `thread.started`. Crucially we must NOT fall back to "last
     * line as result" here — the last line is `turn.completed`, which carries no text, so
     * the naive heuristic would return the entire JSONL dump as the tool's return value.
     *
     * Legacy fallback — a codex binary run without `--json` (or pinned below where it
     * exists) emits a single JSON object or plain prose; keep the original
     * last-line-or-raw heuristic so those deployments keep working.
     *
     * Usage / cost telemetry is optional — if the CLI doesn't emit it in a parseable form
     * we degrade silently rather than fail the tool call.
     */
    private fun parseCodexOutput(stdout: String): ParsedSdkOutput {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) {
            return ParsedSdkOutput(text = "", usage = null, costUsd = null, sessionId = null)
        }

        val events = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseJsonObjectOrNull(it) }
            .toList()
        val isThreadStream = events.any {
            val type = it.stringField("type")
            type != null && (type.startsWith("thread.") || type.startsWith("turn.") || type.startsWith("item."))
        }
        if (isThreadStream) {
            var agentText: String? = null
            var usage: TokenUsage? = null
            var sessionId: String? = null
            for (ev in events) {
                when (ev.stringField("type")) {
                    "thread.started" -> ev.stringField("thread_id")?.let { sessionId = it }
                    "turn.completed" -> (ev["usage"] as? JsonObject)?.let { u ->
                        usage = TokenUsage(
                            inputTokens = u.longField("input_tokens"),
                            outputTokens = u.longField("output_tokens"),
                            cacheCreationInputTokens = null,
                            cacheReadInputTokens = u.longField("cached_input_tokens")
                        )
                    }
                    "item.completed", "item.updated" ->
                        (ev["item"] as? JsonObject)
                            ?.takeIf { it.stringField("type") == "agent_message" }
                            ?.stringField("text")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { agentText = it }
                }
            }
            // A successful turn essentially always ends with an agent_message; if one is
            // somehow absent, an empty string beats leaking the raw JSONL into the parent.
            return ParsedSdkOutput(text = agentText.orEmpty(), usage = usage, costUsd = null, sessionId = sessionId)
        }

        val lastLine = trimmed.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        val obj = parseJsonObjectOrNull(lastLine)
            ?: return ParsedSdkOutput(text = trimmed, usage = null, costUsd = null, sessionId = null)
        val resultText = obj.stringField("result")
            ?: obj.stringField("output")
            ?: obj.stringField("text")
            ?: trimmed
        val costUsd = obj.doubleField("cost_usd") ?: obj.doubleField("total_cost_usd")
        val sessionId = obj.stringField("session_id") ?: obj.stringField("conversation_id")
        val usage = (obj["usage"] as? JsonObject)?.let { usageObj ->
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
        private const val DOCKER_SANDBOX_UID = 2000
        private const val DOCKER_SANDBOX_GID = 2000
        const val MIN_TIMEOUT_SECONDS = 10
        // Ceiling on a single external agent CLI run. Templates set the real budget
        // (App Factory developers and repairers use 7200 s); this only stops an
        // absurd value from running unbounded. It was 3600, which silently cut every
        // longer template budget to one hour. Four hours matches the longest
        // execution callback token a code step can hold.
        const val MAX_TIMEOUT_SECONDS = 14400
        internal const val MAX_COMMAND_ARGUMENT_BYTES = 96 * 1024
        internal const val MAX_COMMAND_TOTAL_BYTES = 512 * 1024
        private const val COMMAND_ARGUMENT_TRUNCATION_MARKER =
            "\n…(truncated to fit external-agent command argument)…\n"
        const val CLAUDE_AUTH_MODE_PARAMETER = "external_agent_claude_auth_mode"
        const val CLAUDE_MODEL_PARAMETER = "external_agent_claude_model"
        const val CLAUDE_OAUTH_TOKEN_PARAMETER = "external_agent_claude_oauth_token"
        const val CLAUDE_CONFIG_DIR_PARAMETER = "external_agent_claude_config_dir"
        const val CLAUDE_APPEND_SYSTEM_PROMPT_PARAMETER = "external_agent_claude_append_system_prompt"
        const val CLAUDE_PROGRESS_INTERVAL_SECONDS_PARAMETER = "external_agent_claude_progress_interval_seconds"
        /**
         * Enables replay on a different Claude subscription credential when a confirmed
         * rate-limit result arrives after the agent has already made progress. Keep this
         * false unless the workflow step is idempotent and safe to execute from the start.
         */
        const val CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_SAFE_PARAMETER =
            "external_agent_claude_partial_rate_limit_replay_safe"
        private const val CLAUDE_PARTIAL_RATE_LIMIT_REPLAY_INSTRUCTION =
            "[系统重试说明] 上一次执行因订阅限额中途停止。请从头重新读取和验证输入，" +
                "不要把当前工作目录中已有的本步骤输出视为完整结果，并覆盖本步骤负责的输出文件。"
        const val CLAUDE_CODE_OAUTH_PROVIDER = "claude_code_oauth"
        const val CODEX_AUTH_MODE_PARAMETER = "external_agent_codex_auth_mode"
        const val CODEX_MODEL_PARAMETER = "external_agent_codex_model"
        const val CODEX_AUTH_JSON_PARAMETER = "external_agent_codex_auth_json"
        const val CODEX_AUTH_FILE_PARAMETER = "external_agent_codex_auth_file"
        private const val MAX_CODEX_AUTH_FILE_BYTES = 1024 * 1024
        private val CODEX_AUTH_FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val CODEX_AUTH_FILE_LOCKS = ConcurrentHashMap<String, Any>()
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

        /** ChatGPT subscription out of quota; carries whether failover took over. */
        const val CODEX_SUBSCRIPTION_RATE_LIMIT_EVENT = "codex_subscription_rate_limited"
        const val CLAUDE_SUBSCRIPTION_RATE_LIMIT_EVENT = "claude_subscription_rate_limited"
        const val CLAUDE_SUBSCRIPTION_UNAVAILABLE_EVENT = "claude_subscription_unavailable"
        private const val DEFAULT_SUBSCRIPTION_RATE_LIMIT_COOLDOWN_MS = 15 * 60 * 1000L
        private const val SUBSCRIPTION_AUTH_FAILURE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

        /**
         * Cap on credentials one invocation may burn through, per engine. The
         * pools are separate, so this bounds a single vendor's chain — a run can
         * never walk from Claude's pool into Codex's.
         */
        private const val MAX_SUBSCRIPTION_CREDENTIAL_ATTEMPTS = 8

        /**
         * Item types in `codex exec --json` that mean the run touched something
         * outside its own transcript. Replaying after any of these repeats the
         * side effect on the next credential, so failover stops instead.
         *
         * Reasoning and assistant messages are deliberately absent: they cost
         * tokens, not state.
         */
        private val CODEX_SIDE_EFFECT_ITEM_TYPES = setOf(
            "command_execution",
            "file_change",
            "patch_apply",
            "mcp_tool_call",
            "web_search"
        )

        /**
         * True when nothing in the Codex stream shows a side effect, so the whole
         * invocation can be replayed on another credential.
         *
         * Unparseable or empty output counts as safe: `codex exec` that dies on a
         * 401 before emitting a single item has done nothing, and that is the
         * most common failover case by far.
         */
        internal fun codexRunReplaySafe(stdout: String): Boolean {
            return stdout.lineSequence()
                .mapNotNull { parseJsonObjectOrNullStatic(it.trim()) }
                .none { obj ->
                    val itemType = (obj["item"] as? JsonObject)?.stringField("type")
                        ?: obj.stringField("type")
                    itemType in CODEX_SIDE_EFFECT_ITEM_TYPES
                }
        }
        /**
         * Lowercased markers Claude uses in the terminal `result` text for a quota
         * stop. Only consulted alongside an HTTP 429 — never on their own.
         */
        private val CLAUDE_RATE_LIMIT_TEXT_MARKERS = listOf(
            "session limit",
            "usage limit",
            "rate limit",
            "hit your limit"
        )

        /** `resets 12:10pm (UTC)` / `resets 5am (UTC)` — UTC-stamped only, see below. */
        private val CLAUDE_RATE_LIMIT_RESET_TEXT_REGEX = Regex(
            """resets\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s*\(\s*UTC\s*\)""",
            RegexOption.IGNORE_CASE
        )

        internal data class ClaudeSubscriptionRateLimit(
            val safeWithoutExplicitReplay: Boolean
        )

        /**
         * Claude can reject one OAuth subscription because its organization
         * disabled Claude Code while another personal/team credential remains
         * usable. This is a credential-scoped auth failure, not a workflow
         * failure. Failover is safe only for the observed zero-token, one-turn
         * 403 shape so a partially executed agent is never replayed silently.
         */
        internal fun confirmedClaudeSubscriptionCredentialUnavailable(output: String): Boolean {
            val objects = output.lineSequence().mapNotNull { line ->
                runCatching { permissiveJson.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()
            }.toList()
            val result = objects.lastOrNull { obj -> obj["type"]?.jsonPrimitive?.contentOrNull == "result" }
                ?: return false
            val usage = result["usage"] as? JsonObject
            val hasTokens = listOf(
                "input_tokens",
                "output_tokens",
                "cache_creation_input_tokens",
                "cache_read_input_tokens"
            ).any { key ->
                usage?.get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { it > 0 } == true
            }
            val marker = output.lowercase().let { text ->
                "oauth_org_not_allowed" in text ||
                    "organization has disabled claude subscription access" in text
            }
            return result["is_error"]?.jsonPrimitive?.contentOrNull == "true" &&
                result["api_error_status"]?.jsonPrimitive?.contentOrNull == "403" &&
                (result["num_turns"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE) <= 1 &&
                !hasTokens &&
                marker
        }

        /**
         * Identifies a structured Claude subscription 429 independently from whether
         * replay is safe. Confirmed credentials are cooled down even when a partial run
         * is not replayed, so later workflow invocations can select another credential.
         */
        internal fun confirmedClaudeSubscriptionRateLimit(output: String): ClaudeSubscriptionRateLimit? {
            val objects = output.lineSequence().mapNotNull { line ->
                runCatching { permissiveJson.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()
            }.toList()
            val hasRejectedEvent = objects.any { obj ->
                obj["type"]?.jsonPrimitive?.contentOrNull == "rate_limit_event" &&
                    (obj["rate_limit_info"] as? JsonObject)
                        ?.get("status")?.jsonPrimitive?.contentOrNull == "rejected"
            }
            val result = objects.lastOrNull { obj -> obj["type"]?.jsonPrimitive?.contentOrNull == "result" }
                ?: return null
            val usage = result["usage"] as? JsonObject
            val hasTokens = listOf(
                "input_tokens",
                "output_tokens",
                "cache_creation_input_tokens",
                "cache_read_input_tokens"
            ).any { key ->
                usage?.get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { it > 0 } == true
            } == true
            val permissionDenials = result["permission_denials"] as? JsonArray
            // The CLI reports a subscription 429 in more than one shape. The
            // original one carried `terminal_reason=api_error` plus a preceding
            // `rate_limit_event`; observed 2026-08-04 in production, a session
            // limit instead arrives as `subtype=success`, `is_error=true`,
            // `api_error_status=429` and the limit text in `result`, with no
            // rate_limit_event and no terminal_reason. Requiring all four
            // markers made that variant fall through to the generic failure
            // branch, so the credential was never cooled down.
            //
            // An HTTP 429 stays a NECESSARY condition, so a bare 429 with no
            // further evidence still does not cool a credential (an upstream
            // burst limit is not an exhausted subscription — see the
            // "unstructured 429" test). What changed is what counts as
            // corroboration: either the structured rejection event, or the
            // CLI's own limit sentence. The text is never a trigger on its
            // own — a run that merely talks about rate limits carries no 429,
            // so it can't cool anything.
            val isError = result["is_error"]?.jsonPrimitive?.contentOrNull == "true"
            val http429 = result["api_error_status"]?.jsonPrimitive?.contentOrNull == "429"
            val limitText = result["result"]?.jsonPrimitive?.contentOrNull
                ?.lowercase()
                ?.let { text -> CLAUDE_RATE_LIMIT_TEXT_MARKERS.any { it in text } } == true
            val confirmed = isError && http429 && (hasRejectedEvent || limitText)
            if (!confirmed) return null

            val safeWithoutExplicitReplay =
                (result["num_turns"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE) <= 1 &&
                    !hasTokens &&
                    permissionDenials.orEmpty().isEmpty()
            return ClaudeSubscriptionRateLimit(safeWithoutExplicitReplay)
        }

        /**
         * When the credential may be picked again.
         *
         * Only a `rate_limit_event` whose `status` is `rejected` describes the
         * window that actually stopped the run. Claude Code also streams
         * `allowed_warning` events for the *other* window — typically the
         * seven-day one, carrying a reset up to a week away — before the
         * five-hour window rejects. The previous implementation grabbed the
         * first `"resetsAt"` in the output regardless of which event it came
         * from; observed 2026-09-02 in production, a session limit that reset
         * at 14:40 UTC the same day cooled the credential until 2026-09-09,
         * stranding the whole pool on the remaining card. Never take a reset
         * from a non-rejected event.
         *
         * Precedence: rejected event(s) — several windows can reject at once
         * and the latest reset is the binding one — then a `resetsAt` on the
         * terminal `result` record, then the human text.
         */
        internal fun claudeRateLimitResetAtMillis(output: String): Long? {
            val objects = output.lineSequence().mapNotNull { line ->
                runCatching { permissiveJson.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()
            }.toList()
            val rejectedReset = objects
                .filter { obj -> obj["type"]?.jsonPrimitive?.contentOrNull == "rate_limit_event" }
                .mapNotNull { obj -> obj["rate_limit_info"] as? JsonObject }
                .filter { info -> info["status"]?.jsonPrimitive?.contentOrNull == "rejected" }
                .mapNotNull { info -> info["resetsAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() }
                .maxOrNull()
            if (rejectedReset != null) return epochToMillis(rejectedReset)
            val resultReset = objects
                .lastOrNull { obj -> obj["type"]?.jsonPrimitive?.contentOrNull == "result" }
                ?.get("resetsAt")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (resultReset != null) return epochToMillis(resultReset)
            return claudeRateLimitResetFromText(output)
        }

        /** Claude reports `resetsAt` in epoch seconds; tolerate millis too. */
        private fun epochToMillis(raw: Long): Long = if (raw < 100_000_000_000L) raw * 1_000L else raw

        /**
         * A session-limit stop carries no machine-readable `resetsAt`; the only
         * hint is the human text ("You've hit your session limit · resets
         * 12:10pm (UTC)"). Without parsing it the credential is cooled down for
         * the 15-minute default and then picked again while still limited.
         * Only wall-clock times explicitly stamped UTC are honoured — anything
         * else would be a timezone guess.
         */
        private fun claudeRateLimitResetFromText(output: String): Long? {
            val match = CLAUDE_RATE_LIMIT_RESET_TEXT_REGEX.find(output) ?: return null
            val hour12 = match.groupValues[1].toIntOrNull() ?: return null
            if (hour12 !in 1..12) return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (minute !in 0..59) return null
            val hour = when (match.groupValues[3].lowercase()) {
                "pm" -> if (hour12 == 12) 12 else hour12 + 12
                else -> if (hour12 == 12) 0 else hour12
            }
            val now = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC)
            val candidate = now.toLocalDate()
                .atTime(hour, minute)
                .atZone(java.time.ZoneOffset.UTC)
            // "resets 12:10pm" after the current UTC time means today; otherwise
            // the CLI is pointing at tomorrow's window.
            val resolved = if (candidate.toInstant() > now.toInstant()) {
                candidate
            } else {
                candidate.plusDays(1)
            }
            return resolved.toInstant().toEpochMilli()
        }

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

        internal fun isSubprocessTimeout(exitCode: Int, stderr: String): Boolean =
            exitCode == -1 && (stderr.contains("TIMED OUT") ||
                stderr.contains("[Container error: Awaiting status code timeout.]"))

        internal fun isCodexAuthFailure(stdout: String, stderr: String): Boolean =
            codexErrorTexts(stdout, stderr).any { text ->
                val lower = text.lowercase()
                CODEX_AUTH_FAILURE_MARKERS.any { marker ->
                    if (marker == "401") Regex("\\b401\\b").containsMatchIn(lower) else marker in lower
                }
            }

        private fun codexErrorTexts(stdout: String, stderr: String): List<String> = buildList {
            stderr.takeIf { it.isNotBlank() }?.let(::add)
            stdout.lineSequence()
                .mapNotNull { parseJsonObjectOrNullStatic(it.trim()) }
                .forEach { obj ->
                    when (obj.stringField("type")) {
                        "turn.failed" -> (obj["error"] as? JsonObject)?.stringField("message")?.let(::add)
                        "error" -> obj.stringField("message")?.let(::add)
                        else -> (obj["item"] as? JsonObject)
                            ?.takeIf { it.stringField("type") == "error" }
                            ?.stringField("message")
                            ?.let(::add)
                    }
                }
        }

        /**
         * Lowercased quota markers for `codex exec`. Conservative on purpose:
         * this classification is best-effort until a real production sample is
         * captured, so it is only ever matched against the CLI's own *error*
         * channels (a `turn.failed` / `error` event message, or stderr) — never
         * against `agent_message` text, which is the model talking and may well
         * discuss rate limits without any of them applying.
         */
        private val CODEX_RATE_LIMIT_MARKERS = listOf(
            "429",
            "usage limit",
            "rate limit",
            "rate_limit",
            "quota exceeded",
            "insufficient_quota",
            "too many requests"
        )

        internal fun isCodexRateLimitFailure(stdout: String, stderr: String): Boolean {
            val errorTexts = codexErrorTexts(stdout, stderr)
            if (errorTexts.isEmpty()) return false
            return errorTexts.any { text ->
                val lower = text.lowercase()
                CODEX_RATE_LIMIT_MARKERS.any { it in lower }
            }
        }

        /**
         * Build the excerpt that goes into the thrown message and the failure event.
         *
         * The previous `stderr.takeLast(2000).ifBlank { stdout.takeLast(2000) }`
         * lost the actual reason whenever the CLI failed quietly on stdout: both
         * CLIs put the reason at the START of their terminal record, so keeping
         * only the last 2000 characters left operators (and the web layer's
         * detectors) with a mid-object fragment like `web_search_requests":0,…`.
         * Lead with the decoded terminal fields, then append raw output.
         */
        internal fun externalAgentFailureExcerpt(engine: Engine, stdout: String, stderr: String): String {
            val decoded = when (engine) {
                Engine.CLAUDE -> claudeFailureSummary(stdout)
                Engine.CODEX -> codexFailureSummary(stdout)
            }?.takeIf { it.isNotBlank() }
            val raw = stderr.takeLast(1200).ifBlank { stdout.takeLast(1200) }
            return listOfNotNull(decoded, raw.takeIf { it.isNotBlank() }).joinToString(" | ")
        }

        /** Claude's `type=result` record — the whole object for `--output-format json`, last line for `stream-json`. */
        private fun claudeFailureSummary(stdout: String): String? {
            val trimmed = stdout.trim().takeIf { it.isNotEmpty() } ?: return null
            val obj = parseJsonObjectOrNullStatic(trimmed)
                ?: trimmed.lineSequence()
                    .mapNotNull { parseJsonObjectOrNullStatic(it.trim()) }
                    .lastOrNull { it.stringField("type") == "result" }
                ?: return null
            return buildString {
                obj.stringField("subtype")?.let { append("subtype=$it, ") }
                obj["is_error"]?.jsonPrimitive?.contentOrNull?.let { append("is_error=$it, ") }
                obj["api_error_status"]?.jsonPrimitive?.contentOrNull?.let { append("api_error_status=$it, ") }
                obj.stringField("terminal_reason")?.let { append("terminal_reason=$it, ") }
                obj.stringField("stop_reason")?.let { append("stop_reason=$it, ") }
                obj.stringField("result")
                    ?.replace('\n', ' ')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append("result=${it.take(400)}") }
            }.trim().trimEnd(',')
        }

        /**
         * Codex has no `result` envelope. A failed run reports through
         * `turn.failed` (`error.message`), a top-level `error` event, or an
         * `item` of type `error` — the same shapes the stream emitter handles.
         * Take the last one: that is the terminal reason.
         */
        private fun codexFailureSummary(stdout: String): String? {
            val trimmed = stdout.trim().takeIf { it.isNotEmpty() } ?: return null
            val message = trimmed.lineSequence()
                .mapNotNull { parseJsonObjectOrNullStatic(it.trim()) }
                .mapNotNull { obj ->
                    when (obj.stringField("type")) {
                        "turn.failed" -> (obj["error"] as? JsonObject)?.stringField("message")
                        "error" -> obj.stringField("message")
                        else -> (obj["item"] as? JsonObject)
                            ?.takeIf { it.stringField("type") == "error" }
                            ?.stringField("message")
                    }
                }
                .lastOrNull()
                ?.replace('\n', ' ')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return "codex_error=${message.take(400)}"
        }

        private fun parseJsonObjectOrNullStatic(text: String): JsonObject? =
            runCatching { permissiveJson.parseToJsonElement(text).jsonObject }.getOrNull()

        /** `sessions/YYYY/MM/DD/rollout-*.jsonl` sits four levels below `sessions`. */
        private const val CODEX_ROLLOUT_MAX_DEPTH = 4

        /**
         * This invocation's session rollout under `<home>/sessions`. Codex names it
         * `rollout-<local timestamp>-<thread id>.jsonl` (verified on codex-cli 0.159.0;
         * `exec resume` appends to the original file). With a [threadId] the matching
         * file wins; an [exclusive] home otherwise falls back to its newest rollout,
         * while a shared home without a matching thread id yields nothing.
         */
        internal fun findCodexRollout(home: File, threadId: String?, exclusive: Boolean): File? {
            val rollouts = listCodexRollouts(home)
            val id = threadId?.trim()?.takeIf { it.isNotEmpty() }
            val matching = id?.let { rollouts.filter { path -> path.fileName.toString().endsWith("-$it.jsonl") } }.orEmpty()
            val candidates = when {
                matching.isNotEmpty() -> matching
                // Sub-agent threads keep rollouts of their own in the same home and are
                // often the newest; they are never the run's own thread.
                exclusive -> rollouts.filterNot { path -> readCodexRolloutMeta(path.toFile())?.subAgent == true }
                else -> return null
            }
            return candidates
                .map { path -> path to runCatching { Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L) }
                .maxWithOrNull(compareBy<Pair<Path, Long>> { it.second }.thenBy { it.first.fileName.toString() })
                ?.first
                ?.toFile()
        }

        /** Every `rollout-*.jsonl` under `<home>/sessions`; empty when there is none or it cannot be listed. */
        private fun listCodexRollouts(home: File): List<Path> {
            val sessions = File(home, "sessions")
            if (!sessions.isDirectory) return emptyList()
            return runCatching {
                Files.walk(sessions.toPath(), CODEX_ROLLOUT_MAX_DEPTH).use { paths ->
                    paths.filter { path ->
                        val name = path.fileName.toString()
                        name.startsWith("rollout-") && name.endsWith(".jsonl") &&
                            Files.isRegularFile(path, NOFOLLOW_LINKS)
                    }.toList()
                }
            }.getOrDefault(emptyList())
        }

        /** Byte size of every rollout under [home], keyed by absolute path, to tell later what a run added. */
        internal fun snapshotCodexRolloutSizes(home: File): Map<String, Long> =
            listCodexRollouts(home).associate { path ->
                path.toAbsolutePath().toString() to runCatching { Files.size(path) }.getOrDefault(0L)
            }

        /**
         * The last cumulative usage in a rollout: lines
         * `{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{…}}}}`.
         * Malformed lines (including one cut off by a kill mid-write), `info: null`
         * rate-limit updates and missing fields are ignored; null when nothing usable
         * remains or the file cannot be read. [limitBytes] reads only the file's prefix
         * (its content at an earlier [snapshotCodexRolloutSizes]).
         */
        internal fun readCodexRolloutUsage(rollout: File, limitBytes: Long = Long.MAX_VALUE): CodexRolloutUsage? = runCatching {
            var last: CodexRolloutUsage? = null
            BoundedInputStream(rollout.inputStream(), limitBytes).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.contains("\"token_count\"") }
                    .mapNotNull(::parseCodexTokenCountLine)
                    .forEach { last = it }
            }
            last
        }.getOrNull()

        /**
         * Every Codex sub-agent thread that spent tokens during this run, with what it
         * spent. A sub-agent is a rollout whose own `session_meta` has a `subagent`
         * source (or a parent thread) and whose id is not [primaryThreadId]. An
         * [exclusive] home belongs to this run alone; in a shared one a thread must
         * descend from [primaryThreadId] (`session_id` names the root thread) and have
         * grown since [rolloutSizesBefore], and only the growth is charged.
         */
        internal fun codexSubAgentUsages(
            home: File,
            primaryThreadId: String?,
            exclusive: Boolean,
            rolloutSizesBefore: Map<String, Long>
        ): List<CodexSubAgentUsage> {
            val primary = primaryThreadId?.trim()?.takeIf { it.isNotEmpty() }
            if (!exclusive && primary == null) return emptyList()
            return listCodexRollouts(home).sortedBy { it.toString() }.mapNotNull { path ->
                val sizeBefore = rolloutSizesBefore[path.toAbsolutePath().toString()]
                if (sizeBefore != null && runCatching { Files.size(path) }.getOrDefault(0L) <= sizeBefore) {
                    return@mapNotNull null
                }
                val rollout = path.toFile()
                // The header decides; only sub-agent rollouts are read in full.
                val meta = readCodexRolloutMeta(rollout) ?: return@mapNotNull null
                if (!meta.subAgent || meta.threadId == primary) return@mapNotNull null
                if (!exclusive && meta.rootSessionId != primary) return@mapNotNull null
                val thread = readCodexThreadRollout(rollout) ?: return@mapNotNull null
                val total = thread.usage ?: return@mapNotNull null
                // A pre-existing rollout already counted what it held then; that prefix
                // also covers any total a fork inherited.
                val baseline = if (sizeBefore != null) readCodexRolloutUsage(rollout, sizeBefore) else thread.inheritedUsage
                val usage = baseline?.let { total - it } ?: total
                if (usage.isEmpty()) null else CodexSubAgentUsage(meta.threadId, meta.agentPath, usage)
            }
        }

        /**
         * A rollout's thread id, cumulative usage and, for a fork, the total it inherited.
         * Codex 0.159 `exec` threads use paginated history, whose forks drop the parent's
         * `token_count` events and count from zero; a legacy-history fork copies them
         * ahead of its own `thread_settings_applied` and keeps counting from the last
         * one, which [CodexThreadRollout.inheritedUsage] records.
         */
        internal fun readCodexThreadRollout(rollout: File): CodexThreadRollout? = runCatching {
            rollout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var meta: CodexRolloutMeta? = null
                var last: CodexRolloutUsage? = null
                var inherited: CodexRolloutUsage? = null
                var inheritedSettled = false
                for (line in lines) {
                    val own = meta
                    if (own == null) {
                        if (line.contains("\"session_meta\"")) meta = parseCodexSessionMeta(line)
                        continue
                    }
                    if (line.contains("\"token_count\"")) {
                        parseCodexTokenCountLine(line)?.let { last = it }
                    } else if (own.forked && !inheritedSettled && line.contains("\"thread_settings_applied\"") &&
                        isCodexEventMsg(line, "thread_settings_applied")
                    ) {
                        inheritedSettled = true
                        inherited = last
                    }
                }
                meta?.let { own -> CodexThreadRollout(own.threadId, last, inherited) }
            }
        }.getOrNull()

        private class CodexRolloutMeta(
            val threadId: String,
            val rootSessionId: String?,
            val subAgent: Boolean,
            val forked: Boolean,
            val agentPath: String?
        )

        /** The rollout's own `session_meta`, which Codex writes first. */
        private fun readCodexRolloutMeta(rollout: File): CodexRolloutMeta? = runCatching {
            rollout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.take(CODEX_SESSION_META_MAX_LINE)
                    .firstOrNull { it.contains("\"session_meta\"") }
                    ?.let(::parseCodexSessionMeta)
            }
        }.getOrNull()

        private const val CODEX_SESSION_META_MAX_LINE = 8

        private fun parseCodexSessionMeta(line: String): CodexRolloutMeta? {
            val obj = parseJsonObjectOrNullStatic(line.trim()) ?: return null
            if (obj.stringField("type") != "session_meta") return null
            val payload = obj["payload"] as? JsonObject ?: return null
            val id = payload.stringField("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // `"source":{"subagent":{"thread_spawn":{"parent_thread_id":…,"agent_path":…}}}`
            val subAgentSource = (payload["source"] as? JsonObject)?.get("subagent")
            val threadSpawn = (subAgentSource as? JsonObject)?.get("thread_spawn") as? JsonObject
            return CodexRolloutMeta(
                threadId = id,
                rootSessionId = payload.stringField("session_id")?.trim()?.takeIf { it.isNotEmpty() },
                subAgent = subAgentSource != null || !payload.stringField("parent_thread_id").isNullOrBlank(),
                forked = !payload.stringField("forked_from_id").isNullOrBlank(),
                agentPath = (payload.stringField("agent_path") ?: threadSpawn?.stringField("agent_path"))
                    ?.let(::detailSafeValue)
            )
        }

        private fun isCodexEventMsg(line: String, payloadType: String): Boolean {
            val obj = parseJsonObjectOrNullStatic(line.trim()) ?: return false
            return obj.stringField("type") == "event_msg" &&
                (obj["payload"] as? JsonObject)?.stringField("type") == payloadType
        }

        /** A value safe inside a `key=value, …` event detail. */
        private fun detailSafeValue(value: String): String? =
            value.replace(Regex("[\\s,;=]+"), "_").take(160).takeIf { it.isNotBlank() }

        /** Reads at most [remaining] bytes of [input]. */
        private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
            override fun read(): Int {
                if (remaining <= 0L) return -1
                return super.read().also { if (it >= 0) remaining-- }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0L) return -1
                val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n > 0) remaining -= n
                return n
            }
        }

        private fun parseCodexTokenCountLine(line: String): CodexRolloutUsage? {
            val obj = parseJsonObjectOrNullStatic(line.trim()) ?: return null
            if (obj.stringField("type") != "event_msg") return null
            val payload = obj["payload"] as? JsonObject ?: return null
            if (payload.stringField("type") != "token_count") return null
            val total = (payload["info"] as? JsonObject)?.get("total_token_usage") as? JsonObject ?: return null
            val input = total.longField("input_tokens")
            val output = total.longField("output_tokens")
            if (input == null && output == null) return null
            return CodexRolloutUsage(
                inputTokens = input?.coerceAtLeast(0L) ?: 0L,
                cachedInputTokens = total.longField("cached_input_tokens")?.coerceAtLeast(0L) ?: 0L,
                outputTokens = output?.coerceAtLeast(0L) ?: 0L,
                reasoningOutputTokens = total.longField("reasoning_output_tokens")?.coerceAtLeast(0L) ?: 0L
            )
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

        private fun Engine.finalUsageEventType(): String = when (this) {
            Engine.CLAUDE -> "claude_code_sub_agent_usage_final"
            Engine.CODEX -> "codex_sub_agent_usage_final"
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
data class ExternalMcpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val allowedTools: List<String> = emptyList(),
)

@Serializable
@LLMDescription("Context for spawning an external Claude Code or Codex sub-agent")
data class ExternalAgentContext(
    @property:LLMDescription("The prompt the external sub-agent should work on (passed through bounded subprocess stdin)")
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
        "Claude Code only: value for --tools, which controls which built-in tools are " +
            "registered at all (independent of the permission layer). Pass \"\" to register " +
            "no built-in tools (MCP tools are unaffected) — the strongest guarantee for " +
            "MCP-only flows. Null omits the flag (SDK default: all built-in tools registered)."
    )
    val claudeBuiltinTools: String? = null,
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
    val model: String = "",
    @property:LLMDescription(
        "Optional Codex CLI sandbox mode for model-generated shell commands. " +
            "Use read-only for MCP-only authoring flows."
    )
    val codexSandboxMode: String? = null,
    @property:LLMDescription(
        "Whether Codex should bypass its own approval/sandbox layer. Null uses the executor default."
    )
    val codexBypassApprovalsAndSandbox: Boolean? = null,
    @property:LLMDescription(
        "Whether Claude Code should bypass its own tool permission checks via " +
            "--dangerously-skip-permissions. Null uses the executor default (trusted Docker sandbox). " +
            "Set false for MCP-only flows so allowedTools/disallowedTools are actually enforced instead " +
            "of being silently overridden."
    )
    val claudeSkipPermissions: Boolean? = null,
    @property:LLMDescription(
        "Optional external CLI session/thread id to resume. Claude Code receives this as --resume; " +
            "Codex receives it through `codex exec resume <id>`."
    )
    val resumeSessionId: String = "",
    @Transient
    val extraEnv: Map<String, String> = emptyMap(),
    @Transient
    val mcpServers: List<ExternalMcpServerConfig> = emptyList(),
)
