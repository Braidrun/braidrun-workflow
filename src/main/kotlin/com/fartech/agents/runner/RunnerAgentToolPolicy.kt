package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.DirectoryIsolationConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/** Shared by admission (frozen definitions) and the host (resolved parameters at each step). */
internal object RunnerAgentToolPolicy {
    /** Groups requiring run-owned directories; listing here does not grant admission to the group. */
    val FILE_TOOL_GROUPS = setOf("file_system", "file_management", "web", "knowledge_memory", "pdf", "office", "word",
        "csv", "excel", "powerpoint", "image_processing", "multimedia", "multi_media", "database", "ocr", "rag_tools", "iwork", "browser", "sub_agent")
    val SUBPROCESS_TOOL_GROUPS = setOf("shell", "code_execution", "git")
    val RUN_DIRECTORY_TOOL_GROUPS = FILE_TOOL_GROUPS + SUBPROCESS_TOOL_GROUPS
    val SUBPROCESS_CAPABILITIES = SUBPROCESS_TOOL_GROUPS.map { "tool.$it.v1" }.toSet()
    /** Agent parameters that would move adapted tools off the host-chosen run directories. */
    val DIRECTORY_PARAMETERS = listOf("working_dir", "workspace_dir", "output_dir", "persistence_storage_root")

    /** Claude Code built-in tools a Runner step may register: file tools only, no Bash, no Web*, no sub-agents. */
    val CLAUDE_FILE_TOOLS = setOf("Read", "Write", "Edit", "MultiEdit", "Glob", "Grep", "LS")
    const val CLAUDE_DEFAULT_TOOLS = "Read,Write,Edit,Glob,Grep"
    private const val SKIP_PERMISSIONS = "--dangerously-skip-permissions"
    private const val CODEX_BYPASS_APPROVALS = "--dangerously-bypass-approvals-and-sandbox"
    private val ANTIGRAVITY_EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")
    val APPROVED_ANTIGRAVITY_API_BASE_URLS = setOf(
        "https://generativelanguage.googleapis.com",
        "https://llm.braidrun.com"
    )
    val APPROVED_OPENAI_API_BASE_URLS = setOf(
        "https://api.openai.com",
        "https://api.openai.com/v1",
        "https://llm.braidrun.com",
        "https://llm.braidrun.com/v1"
    )

    fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { value != JsonNull }?.content

    fun isApprovedAntigravityApiBaseUrl(raw: String?): Boolean {
        val normalized = raw?.trim()?.trimEnd('/').orEmpty()
        return normalized.isEmpty() || normalized in APPROVED_ANTIGRAVITY_API_BASE_URLS
    }

    fun isApprovedOpenAiApiBaseUrl(raw: String?): Boolean {
        val normalized = raw?.trim()?.trimEnd('/').orEmpty()
        return normalized.isEmpty() || normalized in APPROVED_OPENAI_API_BASE_URLS
    }

    /**
     * `external_agent_claude_allowed_tools` entries restricted to [CLAUDE_FILE_TOOLS] (a path rule such as
     * `Read(src)` counts by its tool name). Unset or blank selects [CLAUDE_DEFAULT_TOOLS].
     */
    fun claudeAllowedEntries(raw: String?): List<String> {
        val entries = (raw?.takeIf { it.isNotBlank() } ?: CLAUDE_DEFAULT_TOOLS).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return entries.filter { it.substringBefore('(').trim() in CLAUDE_FILE_TOOLS }.distinct()
    }

    /** The `--tools` value: the registered built-in tools, so nothing else exists for the CLI to call. */
    fun claudeBuiltinTools(raw: String?): String = claudeAllowedEntries(raw).map { it.substringBefore('(').trim() }.distinct().joinToString(",")

    /**
     * Extra CLI arguments for a Runner Claude step. `--dangerously-skip-permissions` is dropped: the
     * forced `--tools` list is what binds, and keeping the permission layer on is defence in depth.
     * Anything else is refused, because repeating a variadic option such as `--tools`/`--allowedTools`
     * or `--permission-mode`/`--settings`/`--add-dir` would widen what the forced flags allow.
     */
    fun claudeExtraArgs(raw: String?): List<String> {
        val args = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.filterNot { it == SKIP_PERMISSIONS }
        require(args.isEmpty()) { "Runner Claude Code steps take no extra CLI arguments" }
        return args
    }

    /**
     * Extra CLI arguments for a Runner Antigravity step. Drops `--dangerously-skip-permissions` and
     * permits only `--effort` with an approved reasoning level; `--settings`, `--add-dir`, `--mode`,
     * `--sandbox`, `--conversation`, etc. are refused.
     */
    fun antigravityExtraArgs(raw: String?): List<String> {
        val args = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.filterNot { it == SKIP_PERMISSIONS }
        var i = 0
        while (i < args.size) {
            val token = args[i]
            when {
                token == "--effort" -> {
                    val level = args.getOrNull(i + 1)?.lowercase().orEmpty()
                    require(level in ANTIGRAVITY_EFFORT_LEVELS) { "Invalid Antigravity --effort value '$level'" }
                    i += 2
                }
                token.startsWith("--effort=") -> {
                    val level = token.substringAfter('=').lowercase()
                    require(level in ANTIGRAVITY_EFFORT_LEVELS) { "Invalid Antigravity --effort value '$level'" }
                    i += 1
                }
                else -> throw IllegalArgumentException("Runner Antigravity steps do not allow extra CLI argument '$token'")
            }
        }
        return args
    }

    /**
     * Extra CLI arguments for a Runner Codex step. Refuses `--dangerously-bypass-approvals-and-sandbox`
     * and `-c`/`--config` overrides targeting `mcp_servers.*` (which would bypass leased MCP admission).
     */
    fun codexExtraArgs(raw: String?): List<String> {
        val args = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var i = 0
        while (i < args.size) {
            val token = args[i]
            require(token != CODEX_BYPASS_APPROVALS && !token.startsWith("$CODEX_BYPASS_APPROVALS=")) {
                "Runner Codex steps cannot bypass approvals and sandbox"
            }
            if (token == "-c" || token == "--config") {
                val assignment = args.getOrNull(i + 1)?.trim().orEmpty()
                require(!assignment.startsWith("mcp_servers.")) { "Runner Codex steps cannot inject mcp_servers via -c" }
                i += 2
                continue
            }
            if (token.startsWith("-c=") || token.startsWith("--config=")) {
                val assignment = token.substringAfter('=').trim()
                require(!assignment.startsWith("mcp_servers.")) { "Runner Codex steps cannot inject mcp_servers via -c" }
            }
            i += 1
        }
        return args
    }

    /** Final subprocess-boundary guard preventing leased CLI credentials from being sent to unapproved hosts. */
    fun enforceCliEndpointBounds(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecRequest {
        fun verifyEnv(env: Map<String, String>) {
            val anthropicBaseUrl = env["ANTHROPIC_BASE_URL"]?.trim().orEmpty()
            if (anthropicBaseUrl.isNotEmpty()) {
                require(env["CLAUDE_CODE_OAUTH_TOKEN"].isNullOrBlank()) {
                    "Runner Claude subscription credentials cannot use custom ANTHROPIC_BASE_URL"
                }
                require(RunnerClaudeApiPolicy.route(anthropicBaseUrl) != null) {
                    "Runner Claude API endpoint is not an approved provider"
                }
            }
            val openaiBaseUrl = env["OPENAI_BASE_URL"]?.trim().orEmpty()
            if (openaiBaseUrl.isNotEmpty()) {
                require(env["CODEX_HOME"].isNullOrBlank()) {
                    "Runner Codex subscription credentials cannot use custom OPENAI_BASE_URL"
                }
                require(isApprovedOpenAiApiBaseUrl(openaiBaseUrl)) {
                    "Runner OpenAI API endpoint is not an approved provider"
                }
            }
            val geminiBaseUrl = env["GOOGLE_GEMINI_BASE_URL"]?.trim().orEmpty()
            val legacyGeminiBaseUrl = env["GEMINI_API_BASE_URL"]?.trim().orEmpty()
            if (geminiBaseUrl.isNotEmpty() || legacyGeminiBaseUrl.isNotEmpty()) {
                require(env["GOOGLE_APPLICATION_CREDENTIALS"].isNullOrBlank()) {
                    "Runner Antigravity subscription credentials cannot use custom Gemini base URL"
                }
                require(isApprovedAntigravityApiBaseUrl(geminiBaseUrl) && isApprovedAntigravityApiBaseUrl(legacyGeminiBaseUrl)) {
                    "Runner Antigravity API endpoint is not an approved provider"
                }
            }
        }
        verifyEnv(request.env)
        val start = request.environmentAtStart
        return if (start == null) request else request.copy(environmentAtStart = {
            val dynamic = start()
            verifyEnv(request.env + dynamic)
            dynamic
        })
    }

    /**
     * Directory isolation that keeps every run directory under `{base_dir}/{user_id}/{execution_id}/`.
     * A frozen bundle already replaces the definition's isolation with this layout; [isolationRoot]
     * is then the only accepted base directory. Without it the base must be relative.
     */
    fun requireConfinedIsolation(config: DirectoryIsolationConfig, isolationRoot: Path?) {
        require(config.enabled) { "Runner file tools require directory isolation" }
        for (pattern in listOf(config.workingDirPattern, config.outputDirPattern, config.persistenceDirPattern)) {
            require(pattern.startsWith("{base_dir}/{user_id}/{execution_id}/") && !pattern.contains("..") && !pattern.contains('\\')) {
                "Runner file tools require run directories under {base_dir}/{user_id}/{execution_id}/"
            }
        }
        val base = Path.of(config.baseDir)
        require(base.none { it.toString() == ".." }) { "Runner directory isolation base cannot leave its root" }
        if (isolationRoot != null) require(base.toAbsolutePath().normalize() == isolationRoot.toAbsolutePath().normalize()) { "Runner directory isolation base is not the frozen run root" }
        else require(!base.isAbsolute && config.baseDir.isNotBlank()) { "Runner directory isolation base must be relative" }
    }
}
