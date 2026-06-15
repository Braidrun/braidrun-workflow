package com.fartech.agents.commons

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import com.fartech.ftapp2.commonsKt.AnsiColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

// ============================================================================
// Braidrun Agent Hook Models
// ============================================================================

/**
 * Represents a hook event type for braidrun-agent.
 * Combines the original OpenClaw-style lifecycle events with Koog framework agent events.
 *
 * Events are grouped into categories:
 * - **Agent lifecycle**: bootstrap, start, complete, error, close, shutdown
 * - **Message lifecycle**: received, transcribed, preprocessed, sent
 * - **Session lifecycle**: start, end, compact
 * - **Command lifecycle**: command, new, reset, stop
 * - **Strategy lifecycle**: starting, completed (Koog)
 * - **Node execution**: starting, completed, failed (Koog)
 * - **Subgraph execution**: starting, completed, failed (Koog)
 * - **LLM calls**: starting, completed (Koog)
 * - **LLM streaming**: starting, completed, failed, frame received (Koog)
 * - **Tool calls**: starting, completed, failed, validation failed (Koog)
 * - **Skill lifecycle**: activated, deactivated
 * - **Infrastructure**: gateway startup
 */
enum class BraidrunHookEvent {
    // --- Original braidrun-agent lifecycle events ---

    /** Fired during agent bootstrap before workspace files are injected. */
    AGENT_BOOTSTRAP,

    /** Fired when any agent command is issued. */
    COMMAND,

    /** Fired when a /new command is issued. */
    COMMAND_NEW,

    /** Fired when a /reset command is issued. */
    COMMAND_RESET,

    /** Fired when a /stop command is issued. */
    COMMAND_STOP,

    /** Fired right before session compaction summarises history. */
    SESSION_COMPACT_BEFORE,

    /** Fired after session compaction completes. */
    SESSION_COMPACT_AFTER,

    /** Fired when the gateway starts. */
    GATEWAY_STARTUP,

    /** Fired for all message events. */
    MESSAGE,

    /** Fired when an inbound message is received. */
    MESSAGE_RECEIVED,

    /** Fired when a message has been fully transcribed. */
    MESSAGE_TRANSCRIBED,

    /** Fired after all media and link understanding completes. */
    MESSAGE_PREPROCESSED,

    /** Fired when an outbound message is successfully sent. */
    MESSAGE_SENT,

    /** Fired when a new session starts. */
    SESSION_START,

    /** Fired when a session ends. */
    SESSION_END,

    /** Fired when the agent encounters an unrecoverable error. */
    AGENT_ERROR,

    /** Fired when a skill is activated (loaded into conversation context). */
    SKILL_ACTIVATED,

    /** Fired when skill activation tracking is cleared (e.g., new conversation). */
    SKILL_DEACTIVATED,

    /** Fired when the agent/skill manager is shutting down. */
    AGENT_SHUTDOWN,

    // --- Koog framework agent lifecycle events ---

    /** Fired when the Koog agent is starting execution (maps to onAgentStarting). */
    AGENT_STARTING,

    /** Fired when the Koog agent has completed execution (maps to onAgentCompleted). */
    AGENT_COMPLETED,

    /** Fired when the Koog agent is closing/disposing resources (maps to onAgentClosing). */
    AGENT_CLOSING,

    // --- Koog framework strategy events ---

    /** Fired when a strategy begins execution (maps to onStrategyStarting). */
    STRATEGY_STARTING,

    /** Fired when a strategy completes execution (maps to onStrategyCompleted). */
    STRATEGY_COMPLETED,

    // --- Koog framework node execution events ---

    /** Fired when a graph node begins execution (maps to onNodeExecutionStarting). */
    NODE_EXECUTION_STARTING,

    /** Fired when a graph node completes execution (maps to onNodeExecutionCompleted). */
    NODE_EXECUTION_COMPLETED,

    /** Fired when a graph node execution fails (maps to onNodeExecutionFailed). */
    NODE_EXECUTION_FAILED,

    // --- Koog framework subgraph execution events ---

    /** Fired when a subgraph begins execution (maps to onSubgraphExecutionStarting). */
    SUBGRAPH_EXECUTION_STARTING,

    /** Fired when a subgraph completes execution (maps to onSubgraphExecutionCompleted). */
    SUBGRAPH_EXECUTION_COMPLETED,

    /** Fired when a subgraph execution fails (maps to onSubgraphExecutionFailed). */
    SUBGRAPH_EXECUTION_FAILED,

    // --- Koog framework LLM call events ---

    /** Fired when an LLM API call is about to start (maps to onLLMCallStarting). */
    LLM_CALL_STARTING,

    /** Fired when an LLM API call completes successfully (maps to onLLMCallCompleted). */
    LLM_CALL_COMPLETED,

    // --- Koog framework LLM streaming events ---

    /** Fired when LLM streaming begins (maps to onLLMStreamingStarting). */
    LLM_STREAMING_STARTING,

    /** Fired when LLM streaming completes (maps to onLLMStreamingCompleted). */
    LLM_STREAMING_COMPLETED,

    /** Fired when LLM streaming fails (maps to onLLMStreamingFailed). */
    LLM_STREAMING_FAILED,

    /** Fired when an LLM streaming frame is received (maps to onLLMStreamingFrameReceived). */
    LLM_STREAMING_FRAME_RECEIVED,

    // --- Koog framework tool call events ---

    /** Fired when a tool call is about to start (maps to onToolCallStarting). */
    TOOL_CALL_STARTING,

    /** Fired when a tool call completes successfully (maps to onToolCallCompleted). */
    TOOL_CALL_COMPLETED,

    /** Fired when a tool call fails (maps to onToolCallFailed). */
    TOOL_CALL_FAILED,

    /** Fired when tool input validation fails (maps to onToolValidationFailed). */
    TOOL_VALIDATION_FAILED;

    /**
     * Returns the standardized event name string (e.g., "agent:bootstrap").
     */
    fun toEventName(): String = name.lowercase().replace('_', ':')

    companion object {
        fun fromString(value: String): BraidrunHookEvent? = when (value.trim().lowercase()) {
            // Original events
            "agent:bootstrap" -> AGENT_BOOTSTRAP
            "command" -> COMMAND
            "command:new" -> COMMAND_NEW
            "command:reset" -> COMMAND_RESET
            "command:stop" -> COMMAND_STOP
            "session:compact:before" -> SESSION_COMPACT_BEFORE
            "session:compact:after" -> SESSION_COMPACT_AFTER
            "gateway:startup" -> GATEWAY_STARTUP
            "message" -> MESSAGE
            "message:received" -> MESSAGE_RECEIVED
            "message:transcribed" -> MESSAGE_TRANSCRIBED
            "message:preprocessed" -> MESSAGE_PREPROCESSED
            "message:sent" -> MESSAGE_SENT
            "session:start" -> SESSION_START
            "session:end" -> SESSION_END
            "agent:error" -> AGENT_ERROR
            "skill:activated" -> SKILL_ACTIVATED
            "skill:deactivated" -> SKILL_DEACTIVATED
            "agent:shutdown" -> AGENT_SHUTDOWN
            // Koog agent lifecycle events
            "agent:starting" -> AGENT_STARTING
            "agent:completed" -> AGENT_COMPLETED
            "agent:closing" -> AGENT_CLOSING
            // Koog strategy events
            "strategy:starting" -> STRATEGY_STARTING
            "strategy:completed" -> STRATEGY_COMPLETED
            // Koog node execution events
            "node:execution:starting" -> NODE_EXECUTION_STARTING
            "node:execution:completed" -> NODE_EXECUTION_COMPLETED
            "node:execution:failed" -> NODE_EXECUTION_FAILED
            // Koog subgraph execution events
            "subgraph:execution:starting" -> SUBGRAPH_EXECUTION_STARTING
            "subgraph:execution:completed" -> SUBGRAPH_EXECUTION_COMPLETED
            "subgraph:execution:failed" -> SUBGRAPH_EXECUTION_FAILED
            // Koog LLM call events
            "llm:call:starting" -> LLM_CALL_STARTING
            "llm:call:completed" -> LLM_CALL_COMPLETED
            // Koog LLM streaming events
            "llm:streaming:starting" -> LLM_STREAMING_STARTING
            "llm:streaming:completed" -> LLM_STREAMING_COMPLETED
            "llm:streaming:failed" -> LLM_STREAMING_FAILED
            "llm:streaming:frame:received" -> LLM_STREAMING_FRAME_RECEIVED
            // Koog tool call events
            "tool:call:starting" -> TOOL_CALL_STARTING
            "tool:call:completed" -> TOOL_CALL_COMPLETED
            "tool:call:failed" -> TOOL_CALL_FAILED
            "tool:validation:failed" -> TOOL_VALIDATION_FAILED
            else -> null
        }
    }
}

// ============================================================================
// Braidrun Agent Hook Data Models
// ============================================================================

/**
 * Eligibility requirements for a braidrun-agent hook.
 * If any requirement is not satisfied the hook is silently skipped.
 */
data class BraidrunHookRequires(
    /** All listed binaries must be present on PATH. */
    val bins: List<String> = emptyList(),
    /** At least one of these binaries must be present on PATH. */
    val anyBins: List<String> = emptyList(),
    /** All listed environment variables must be set. */
    val env: List<String> = emptyList(),
    /** Current OS must match one of these values (e.g., ["darwin", "linux", "windows"]). */
    val os: List<String> = emptyList(),
    /** All listed file/directory paths must exist on the filesystem. */
    val config: List<String> = emptyList()
)

/**
 * Represents a braidrun-agent hook loaded from hooks/braidrun-agent/HOOK.md.
 * The hook content is injected into the agent's system prompt during the specified events.
 */
data class BraidrunAgentHook(
    val name: String,
    val description: String,
    val events: List<BraidrunHookEvent>,
    val content: String,
    val skillName: String,
    /** Display emoji shown in logs (e.g., "🔖"). */
    val emoji: String = "",
    /** Documentation URL for this hook. */
    val homepage: String = "",
    /** Optional eligibility requirements; null means always eligible. */
    val requires: BraidrunHookRequires? = null,
    /** True when this hook was loaded from the workspace-level hooks directory. */
    val isWorkspaceHook: Boolean = false,
    /**
     * When non-null, the hook content will be injected as a virtual bootstrap file with this
     * path (e.g. "SELF_IMPROVEMENT_REMINDER.md") rather than as inline system-prompt text.
     * Mirrors OpenClaw's bootstrapFiles.push({path, content, virtual:true}) behaviour.
     */
    val virtualFilePath: String? = null,
    /**
     * Optional path to a handler script (.py, .js, or .kts) located in the same directory as
     * HOOK.md.  When present the script is executed at event time; its JSON stdout is parsed into
     * a [BraidrunHookScriptResult] and takes precedence over the static HOOK.md body content.
     */
    val handlerScript: Path? = null,
    /**
     * When true, eligibility requirements ([requires]) are skipped and the hook is always run.
     * Mirrors OpenClaw's `always` frontmatter field.
     */
    val always: Boolean = false
)

// ============================================================================
// Braidrun Agent Hook Script Execution Models
// ============================================================================

/**
 * Context serialised to JSON and written to a handler script's stdin.
 * Scripts can use this to make conditional decisions (e.g., skip sub-agent sessions).
 */
data class BraidrunHookContext(
    /** Event type string, e.g. "agent:bootstrap". */
    val event: String,
    /** Identifies the current session; empty string when not available. */
    val sessionKey: String = "",
    /** Absolute path to the current workspace directory; empty when unknown. */
    val workspaceDir: String = "",
    /** The skill name that owns this hook. */
    val skillName: String = "",
    /**
     * Subset of agent configuration key-value pairs passed to the handler script.
     * Allows scripts to make decisions based on agent settings without reading config files.
     */
    val config: Map<String, String> = emptyMap()
)

/**
 * A virtual file emitted by a handler script.
 * Injected into the agent context as if it were loaded from disk at session start.
 */
data class BraidrunVirtualFile(
    val path: String,
    val content: String
)

/**
 * Result emitted by a hook handler script as JSON on stdout.
 * Any combination of the three output fields may be present.
 */
data class BraidrunHookScriptResult(
    /** Plain text appended to the system prompt. */
    val injectContent: String? = null,
    /** Virtual files injected as bootstrap files. */
    val virtualFiles: List<BraidrunVirtualFile> = emptyList(),
    /** Informational messages printed to the agent operator console. */
    val messages: List<String> = emptyList()
)

// ============================================================================
// Braidrun Agent Hook Loader
// ============================================================================

/**
 * Loads braidrun-agent hooks from skill directories and workspace directories.
 *
 * Looks for `hooks/braidrun-agent/HOOK.md` in each skill directory.
 * Returns null if the hook file does not exist.
 */
class BraidrunHookLoader(private val config: SkillsConfiguration) {

    companion object {
        private const val HOOK_DIR = "hooks/braidrun-agent"
        private const val HOOK_FILENAME = "HOOK.md"
        private const val YAML_DELIMITER = "---"
        private const val NS_BRAIDRUN = "braidrun-agent"
    }

    /**
     * Loads a braidrun-agent hook from the given skill directory, if present.
     * Looks for `hooks/braidrun-agent/HOOK.md`. Returns null if not found.
     */
    fun loadHookFromSkillDir(skillDir: Path, skillName: String): BraidrunAgentHook? {
        val hookFile = skillDir.resolve(HOOK_DIR).resolve(HOOK_FILENAME)
        if (hookFile.exists() && hookFile.isRegularFile()) {
            return loadHookFromFile(hookFile, skillName, isWorkspaceHook = false)
        }
        return null
    }

    /**
     * Loads a braidrun-agent hook from a direct file path.
     * Used for workspace-level hooks that don't follow the skill directory structure.
     */
    fun loadHookFromFile(hookFile: Path, skillName: String, isWorkspaceHook: Boolean = false): BraidrunAgentHook? {
        if (!hookFile.exists() || !hookFile.isRegularFile()) return null
        return try {
            val content = hookFile.readText(StandardCharsets.UTF_8)
            parseHookFile(content, skillName, isWorkspaceHook, hookDir = hookFile.parent)
        } catch (e: Exception) {
            logProgress(AnsiColor.YELLOW, "Hooks", "⚠ Failed to load hook from $hookFile: ${e.message}")
            null
        }
    }

    private fun parseHookFile(
        content: String,
        skillName: String,
        isWorkspaceHook: Boolean = false,
        hookDir: Path? = null
    ): BraidrunAgentHook? {
        val lines = content.lines()
        if (lines.isEmpty() || lines.first().trim() != YAML_DELIMITER) return null

        val endIdx = lines.drop(1).indexOfFirst { it.trim() == YAML_DELIMITER }
        if (endIdx < 0) return null

        val yamlLines = lines.drop(1).take(endIdx)
        val body = lines.drop(endIdx + 2).joinToString("\n").trim()

        val frontmatter = parseSimpleYaml(yamlLines)

        val name = frontmatter["name"]?.trim() ?: return null
        val description = frontmatter["description"]?.trim() ?: ""
        val emoji = frontmatter["emoji"]?.trim() ?: ""
        val homepage = frontmatter["homepage"]?.trim() ?: ""
        val always = frontmatter["always"]?.trim()?.lowercase() == "true"

        val metadataStr = frontmatter["metadata"]
        val nsObject = parseNamespaceObject(metadataStr)

        val events = parseEvents(nsObject)
        if (events.isEmpty()) return null

        val requires = parseRequires(nsObject)
        val virtualFilePath = parseVirtualFilePath(nsObject)

        // Keep in sync with BraidrunHookExecutor.SUPPORTED_EXTENSIONS — "ts" is
        // executable (via `npx tsx`) and in the default allowlist, but the loader
        // previously never probed for it, so handler.ts silently never ran.
        val handlerScript: Path? = hookDir?.let { dir ->
            listOf("py", "js", "ts", "kts").firstNotNullOfOrNull { ext: String ->
                val candidate: Path = dir.resolve("handler.$ext")
                if (candidate.exists() && candidate.isRegularFile()) candidate else null
            }
        }
        if (handlerScript != null) {
            logProgress(AnsiColor.CYAN, "Hooks", "  Found handler script: ${handlerScript.getFileName()} (${name})")
        }

        return BraidrunAgentHook(
            name = name,
            description = description,
            events = events,
            content = body,
            skillName = skillName,
            emoji = emoji,
            homepage = homepage,
            requires = requires,
            isWorkspaceHook = isWorkspaceHook,
            virtualFilePath = virtualFilePath,
            handlerScript = handlerScript,
            always = always
        )
    }

    private fun parseSimpleYaml(lines: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in lines) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
                result[key] = value
            }
        }
        return result
    }

    private fun parseNamespaceObject(metadataStr: String?): JsonObject? {
        if (metadataStr.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(metadataStr).jsonObject }.getOrNull() ?: return null
        return root[NS_BRAIDRUN] as? JsonObject
    }

    private fun parseEvents(nsObject: JsonObject?): List<BraidrunHookEvent> {
        return parseJsonStringArray(nsObject, "events")
            .mapNotNull(BraidrunHookEvent::fromString)
    }

    private fun parseVirtualFilePath(nsObject: JsonObject?): String? {
        val value = (nsObject?.get("virtualFilePath") as? JsonPrimitive)?.contentOrNull
        return value?.takeIf { it.isNotBlank() }
    }

    private fun parseRequires(nsObject: JsonObject?): BraidrunHookRequires? {
        val requiresObject = nsObject?.get("requires") as? JsonObject ?: return null
        val bins = parseJsonStringArray(requiresObject, "bins")
        val anyBins = parseJsonStringArray(requiresObject, "anyBins")
        val env = parseJsonStringArray(requiresObject, "env")
        val os = parseJsonStringArray(requiresObject, "os")
        val config = parseJsonStringArray(requiresObject, "config")

        return if (bins.isEmpty() && anyBins.isEmpty() && env.isEmpty() && os.isEmpty() && config.isEmpty()) null
        else BraidrunHookRequires(bins = bins, anyBins = anyBins, env = env, os = os, config = config)
    }

    private fun parseJsonStringArray(json: JsonObject?, key: String): List<String> {
        val array = json?.get(key) as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }
}

// ============================================================================
// Braidrun Agent Hook Executor
// ============================================================================

/**
 * Executes braidrun-agent hook handler scripts (.py, .js, .kts).
 *
 * Protocol:
 *   - A [BraidrunHookContext] is serialised to JSON and written to the script's **stdin**.
 *   - The script writes a JSON object to **stdout** whose fields map to [BraidrunHookScriptResult].
 *   - Non-zero exit code or timeout causes the result to be discarded (hook is skipped).
 *
 * Minimal Python example:
 * ```python
 * import sys, json
 * ctx = json.load(sys.stdin)
 * print(json.dumps({"injectContent": "Hello from hook!", "virtualFiles": [], "messages": []}))
 * ```
 */
class BraidrunHookExecutor(private val config: SkillsConfiguration = SkillsConfiguration()) {

    companion object {
        /** Script file name extensions checked in priority order when discovering handler scripts. */
        val SUPPORTED_EXTENSIONS = listOf("py", "js", "ts", "kts")

        /**
         * Per-stream byte cap for hook handler stdout / stderr captures. Mirrors
         * [com.fartech.agents.tools.exec.SubprocessExecutor.MAX_STREAM_BYTES] so a
         * trusted skill running a misbehaving handler script can't OOM the JVM the
         * way a sandbox-escaping container could under Phase 8. The marker matches
         * the SubprocessExecutor convention.
         */
        const val MAX_HOOK_STREAM_BYTES: Int = 8 * 1024 * 1024
        private const val HOOK_STREAM_TRUNCATION_MARKER: String =
            "\n[hook output truncated at 8 MiB]\n"
    }

    /**
     * Read up to [maxBytes] bytes from [input] into a UTF-8 string, then drain (and
     * discard) the rest so the producer doesn't block on a full pipe buffer. Tagged
     * truncation marker matches SubprocessExecutor for log readability.
     */
    private fun readBoundedAndDrain(
        input: java.io.InputStream,
        maxBytes: Int
    ): String {
        val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val chunk = ByteArray(8 * 1024)
        var captured = 0
        var truncated = false
        try {
            input.use { stream ->
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (captured < maxBytes) {
                        val take = minOf(n, maxBytes - captured)
                        buffer.write(chunk, 0, take)
                        captured += take
                        if (captured >= maxBytes) truncated = true
                    } else {
                        // Already at cap — keep draining so the producer doesn't block,
                        // but discard the bytes.
                        truncated = true
                    }
                }
            }
        } catch (_: Exception) {
            // Pipe closed (process killed / EOF after destroyForcibly) — return what we have.
        }
        val text = buffer.toString(StandardCharsets.UTF_8)
        return if (truncated) text + HOOK_STREAM_TRUNCATION_MARKER else text
    }

    /**
     * Executes [hook]'s handler script with the given [context] and returns the parsed result.
     * Returns null when no script is attached, the script is not found, times out, or exits non-zero.
     */
    fun execute(hook: BraidrunAgentHook, context: BraidrunHookContext): BraidrunHookScriptResult? {
        val scriptPath = hook.handlerScript ?: return null
        if (!scriptPath.exists()) {
            logProgress(AnsiColor.YELLOW, "Hooks", "⚠ Handler script not found: $scriptPath")
            return null
        }

        val ext = scriptPath.getFileName().toString().substringAfterLast('.', "").lowercase()

        // Check whether this script type is allowed by configuration.
        if (!config.allowedScriptTypes.contains(ext)) {
            logProgress(
                AnsiColor.YELLOW,
                "Hooks",
                "⚠ Script type '.$ext' not in allowedScriptTypes ${config.allowedScriptTypes} — skipping handler for: ${hook.name}"
            )
            return null
        }

        val cmd = when (ext) {
            "py" -> listOf("python3", scriptPath.toAbsolutePath().toString())
            "js" -> listOf("node", scriptPath.toAbsolutePath().toString())
            "ts" -> listOf("npx", "tsx", scriptPath.toAbsolutePath().toString())
            "kts" -> listOf("kotlinc", "-script", scriptPath.toAbsolutePath().toString())
            else -> {
                logProgress(AnsiColor.YELLOW, "Hooks", "⚠ Unsupported handler extension: .$ext (${hook.name})")
                return null
            }
        }

        return try {
            val contextJson = buildContextJson(context)
            val process = ProcessBuilder(cmd).redirectErrorStream(false).start()

            // Write context JSON to stdin, then close the stream so the script sees EOF.
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(contextJson) }

            // Drain stdout and stderr concurrently to avoid blocking on full OS pipe buffers,
            // but cap each stream at MAX_HOOK_STREAM_BYTES so a runaway script can't OOM the
            // JVM. After the cap we keep draining (and discarding) so the script doesn't
            // block on a full pipe — same pattern SubprocessExecutor uses for shell / code
            // step subprocesses (Phase 8 hardening).
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread {
                stdout = readBoundedAndDrain(process.inputStream, MAX_HOOK_STREAM_BYTES)
            }
            val stderrThread = Thread {
                stderr = readBoundedAndDrain(process.errorStream, MAX_HOOK_STREAM_BYTES)
            }
            stdoutThread.isDaemon = true
            stderrThread.isDaemon = true
            stdoutThread.start()
            stderrThread.start()

            val timeoutSec = config.hookTimeoutSeconds
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)

            if (!finished) {
                // Destroy the process first so the reader threads see EOF and exit on their
                // own; otherwise join() can block until 5 s and then leave the threads
                // running on a still-live pipe.
                process.destroyForcibly()
                stdoutThread.join(5_000)
                stderrThread.join(5_000)
                logProgress(AnsiColor.YELLOW, "Hooks", "⚠ Handler timed out (${timeoutSec}s): ${hook.name}")
                return null
            }

            stdoutThread.join(5_000)
            stderrThread.join(5_000)

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logProgress(AnsiColor.YELLOW, "Hooks", "⚠ Handler exited $exitCode [${hook.name}]: ${stderr.trimEnd()}")
                return null
            }

            if (stderr.isNotBlank()) {
                logProgress(AnsiColor.YELLOW, "Hooks", "ℹ Handler stderr [${hook.name}]: ${stderr.trimEnd()}")
            }

            logProgress(AnsiColor.GREEN, "Hooks", "✓ Executed handler ($ext): ${hook.name}")
            parseScriptResult(stdout)
        } catch (e: Exception) {
            logProgress(AnsiColor.YELLOW, "Hooks", "⚠ Failed to run handler [${hook.name}]: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // JSON serialisation (context → stdin)
    // -------------------------------------------------------------------------

    private fun buildContextJson(context: BraidrunHookContext): String = buildString {
        append("{")
        append("\"event\":${jsonStr(context.event)},")
        append("\"sessionKey\":${jsonStr(context.sessionKey)},")
        append("\"workspaceDir\":${jsonStr(context.workspaceDir)},")
        append("\"skillName\":${jsonStr(context.skillName)},")
        append("\"config\":{")
        context.config.entries.forEachIndexed { idx, (k, v) ->
            if (idx > 0) append(",")
            append("${jsonStr(k)}:${jsonStr(v)}")
        }
        append("}")
        append("}")
    }

    private fun jsonStr(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // JSON deserialisation (stdout → BraidrunHookScriptResult)
    // -------------------------------------------------------------------------

    internal fun parseScriptResult(stdout: String): BraidrunHookScriptResult {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return BraidrunHookScriptResult()
        return BraidrunHookScriptResult(
            injectContent = extractJsonString(trimmed, "injectContent")?.takeIf { it.isNotBlank() },
            virtualFiles = extractVirtualFiles(trimmed),
            messages = extractJsonStringArray(trimmed, "messages")
        )
    }

    /**
     * Extracts a single JSON string value for [key] from [json], handling escape sequences.
     */
    internal fun extractJsonString(json: String, key: String): String? {
        val keyToken = "\"$key\""
        val keyIdx = json.indexOf(keyToken)
        if (keyIdx < 0) return null
        var i = keyIdx + keyToken.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        if (i >= json.length || json[i] != '"') return null
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    '"' -> {
                        sb.append('"'); i += 2
                    }

                    '\\' -> {
                        sb.append('\\'); i += 2
                    }

                    'n' -> {
                        sb.append('\n'); i += 2
                    }

                    'r' -> {
                        sb.append('\r'); i += 2
                    }

                    't' -> {
                        sb.append('\t'); i += 2
                    }

                    'u' -> i += decodeUnicodeEscape(json, i, sb)
                    else -> {
                        sb.append(json[i + 1]); i += 2
                    }
                }
            } else if (ch == '"') {
                break
            } else {
                sb.append(ch); i++
            }
        }
        return sb.toString()
    }

    /**
     * Extracts a JSON array of strings for [key] from [json].
     */
    internal fun extractJsonStringArray(json: String, key: String): List<String> {
        val keyToken = "\"$key\""
        val keyIdx = json.indexOf(keyToken)
        if (keyIdx < 0) return emptyList()
        var i = keyIdx + keyToken.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        if (i >= json.length || json[i] != '[') return emptyList()
        i++ // skip '['
        val results = mutableListOf<String>()
        while (i < json.length) {
            while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r' || json[i] == ',')) i++
            if (i >= json.length || json[i] == ']') break
            if (json[i] != '"') {
                i++; continue
            }
            i++ // skip opening quote
            val sb = StringBuilder()
            while (i < json.length) {
                val ch = json[i]
                if (ch == '\\' && i + 1 < json.length) {
                    when (json[i + 1]) {
                        '"' -> {
                            sb.append('"'); i += 2
                        }

                        '\\' -> {
                            sb.append('\\'); i += 2
                        }

                        'n' -> {
                            sb.append('\n'); i += 2
                        }

                        'r' -> {
                            sb.append('\r'); i += 2
                        }

                        't' -> {
                            sb.append('\t'); i += 2
                        }

                        'u' -> i += decodeUnicodeEscape(json, i, sb)
                        else -> {
                            sb.append(json[i + 1]); i += 2
                        }
                    }
                } else if (ch == '"') {
                    i++; break
                } else {
                    sb.append(ch); i++
                }
            }
            results.add(sb.toString())
        }
        return results
    }

    /**
     * Decodes a `\uXXXX` JSON Unicode escape sequence starting at [backslashIdx] in [json],
     * appends the resulting character(s) to [sb], and returns the number of characters consumed
     * (including the leading backslash).
     *
     * Handles surrogate pairs (e.g., `\uD83E\uDDE0` → 🧠) so that emoji in hook script output
     * are decoded correctly instead of appearing as raw escape sequences like `ud83eudde0`.
     */
    private fun decodeUnicodeEscape(json: String, backslashIdx: Int, sb: StringBuilder): Int {
        // Need at least \uXXXX (6 chars starting at backslashIdx)
        if (backslashIdx + 5 >= json.length) {
            sb.append('u')
            return 2 // consume only '\' and 'u'
        }
        val hex1 = json.substring(backslashIdx + 2, backslashIdx + 6)
        val codeUnit1 = hex1.toIntOrNull(16)
        if (codeUnit1 == null) {
            sb.append('u')
            return 2
        }
        // Check for high surrogate: if so, look ahead for the paired low surrogate
        if (codeUnit1 in 0xD800..0xDBFF && backslashIdx + 11 < json.length
            && json[backslashIdx + 6] == '\\' && json[backslashIdx + 7] == 'u'
        ) {
            val hex2 = json.substring(backslashIdx + 8, backslashIdx + 12)
            val codeUnit2 = hex2.toIntOrNull(16)
            if (codeUnit2 != null && codeUnit2 in 0xDC00..0xDFFF) {
                val codePoint = 0x10000 + ((codeUnit1 - 0xD800) shl 10) + (codeUnit2 - 0xDC00)
                sb.appendCodePoint(codePoint)
                return 12 // consume both \uXXXX\uXXXX
            }
        }
        sb.append(codeUnit1.toChar())
        return 6 // consume \uXXXX
    }

    /**
     * Extracts a JSON array of `{path, content}` objects for the "virtualFiles" key.
     */
    private fun extractVirtualFiles(json: String): List<BraidrunVirtualFile> {
        val keyToken = "\"virtualFiles\""
        val keyIdx = json.indexOf(keyToken)
        if (keyIdx < 0) return emptyList()
        var i = keyIdx + keyToken.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        if (i >= json.length || json[i] != '[') return emptyList()
        i++ // skip '['
        val results = mutableListOf<BraidrunVirtualFile>()
        while (i < json.length) {
            while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r' || json[i] == ',')) i++
            if (i >= json.length || json[i] == ']') break
            if (json[i] != '{') {
                i++; continue
            }
            // Scan to the matching '}'
            var depth = 1
            val objStart = i; i++
            while (i < json.length && depth > 0) {
                when {
                    json[i] == '"' -> {
                        i++
                        while (i < json.length) {
                            if (json[i] == '\\') {
                                i += 2; continue
                            }
                            if (json[i] == '"') {
                                i++; break
                            }
                            i++
                        }
                    }

                    json[i] == '{' -> {
                        depth++; i++
                    }

                    json[i] == '}' -> {
                        depth--; i++
                    }

                    else -> i++
                }
            }
            val objJson = json.substring(objStart, i)
            val path = extractJsonString(objJson, "path") ?: continue
            val content = extractJsonString(objJson, "content") ?: continue
            results.add(BraidrunVirtualFile(path = path, content = content))
        }
        return results
    }
}

// ============================================================================
// Hook-Aware Install Features (Koog Event → Braidrun Hook Dispatching)
// ============================================================================

/**
 * Creates a Koog [GraphAIAgent.FeatureContext] installer that wires **all** Koog framework
 * events to the braidrun-agent hook dispatching system via [SkillManager.dispatchHooks].
 *
 * This replaces the original [defaultInstallFeatures] and ensures every Koog lifecycle event
 * is forwarded to any registered hook handler scripts.
 *
 * @param skillManager The skill manager used for hook dispatching; if null, only logging is performed.
 * @param sessionId The current session identifier passed to hook scripts.
 */
fun createHookAwareInstallFeatures(
    skillManager: SkillManager?,
    sessionId: String = ""
): GraphAIAgent.FeatureContext.() -> Unit = {
    handleEvents {
        // --- Tool events ---
        onToolCallStarting { eventContext ->
            val toolName = eventContext.toolName
            logProgress(AnsiColor.YELLOW, "Tool", "🔧调用工具: $toolName")
            skillManager?.dispatchHooks(BraidrunHookEvent.TOOL_CALL_STARTING, sessionId, mapOf("toolName" to toolName))
        }
        onToolCallCompleted { eventContext ->
            val toolName = eventContext.toolName
            logProgress(AnsiColor.GREEN, "Tool", "✅工具完成: $toolName")
            skillManager?.dispatchHooks(BraidrunHookEvent.TOOL_CALL_COMPLETED, sessionId, mapOf("toolName" to toolName))
        }
        onToolCallFailed { eventContext ->
            val toolName = eventContext.toolName
            val error = eventContext.error?.message
            logProgress(AnsiColor.RED, "Tool", "❌工具失败: $toolName — $error")
            skillManager?.dispatchHooks(
                BraidrunHookEvent.TOOL_CALL_FAILED,
                sessionId,
                mapOf("toolName" to toolName, "error" to (error ?: "unknown"))
            )
        }
        onToolValidationFailed { eventContext ->
            val toolName = eventContext.toolName
            // Koog 1.0.0 made `Throwable.message` nullable in the event-context
            // accessor (was non-null in 0.x). Coerce to "" so the
            // `Map<String, String>` payload remains non-nullable.
            val error = eventContext.error.message.orEmpty()
            logProgress(AnsiColor.RED, "Tool", "⚠️工具验证失败: $toolName — $error")
            skillManager?.dispatchHooks(
                BraidrunHookEvent.TOOL_VALIDATION_FAILED,
                sessionId,
                mapOf("toolName" to toolName, "error" to error)
            )
        }

        // --- Agent lifecycle events ---
        onAgentStarting {
            logProgress(AnsiColor.CYAN, "Agent", "🚀 Agent 开始执行")
            skillManager?.dispatchHooks(BraidrunHookEvent.AGENT_STARTING, sessionId)
        }
        onAgentCompleted {
            val result = it.result?.toString() ?: ""
            logProgress(AnsiColor.GREEN, "Agent", "✅ Agent 执行完成")
            skillManager?.dispatchHooks(BraidrunHookEvent.AGENT_COMPLETED, sessionId, mapOf("result" to result))
        }
        onAgentExecutionFailed {
            logProgress(AnsiColor.RED, "Agent", "❌ Agent 执行失败")
            skillManager?.dispatchHooks(BraidrunHookEvent.AGENT_ERROR, sessionId)
        }
        onAgentClosing {
            logProgress(AnsiColor.CYAN, "Agent", "🔒 Agent 正在关闭")
            skillManager?.dispatchHooks(BraidrunHookEvent.AGENT_CLOSING, sessionId)
        }

        // --- Strategy events ---
        onStrategyStarting {
            logProgress(AnsiColor.CYAN, "Strategy", "📋 策略开始执行")
            skillManager?.dispatchHooks(BraidrunHookEvent.STRATEGY_STARTING, sessionId)
        }
        onStrategyCompleted {
            logProgress(AnsiColor.GREEN, "Strategy", "✅ 策略执行完成")
            skillManager?.dispatchHooks(BraidrunHookEvent.STRATEGY_COMPLETED, sessionId)
        }

        // --- Node execution events ---
        onNodeExecutionStarting {
            skillManager?.dispatchHooks(BraidrunHookEvent.NODE_EXECUTION_STARTING, sessionId)
        }
        onNodeExecutionCompleted {
            skillManager?.dispatchHooks(BraidrunHookEvent.NODE_EXECUTION_COMPLETED, sessionId)
        }
        onNodeExecutionFailed {
            logProgress(AnsiColor.RED, "Node", "❌ 节点执行失败")
            skillManager?.dispatchHooks(BraidrunHookEvent.NODE_EXECUTION_FAILED, sessionId)
        }

        // --- Subgraph execution events ---
        onSubgraphExecutionStarting {
            skillManager?.dispatchHooks(BraidrunHookEvent.SUBGRAPH_EXECUTION_STARTING, sessionId)
        }
        onSubgraphExecutionCompleted {
            skillManager?.dispatchHooks(BraidrunHookEvent.SUBGRAPH_EXECUTION_COMPLETED, sessionId)
        }
        onSubgraphExecutionFailed {
            logProgress(AnsiColor.RED, "Subgraph", "❌ 子图执行失败")
            skillManager?.dispatchHooks(BraidrunHookEvent.SUBGRAPH_EXECUTION_FAILED, sessionId)
        }

        // --- LLM call events ---
        onLLMCallStarting {
            skillManager?.dispatchHooks(BraidrunHookEvent.LLM_CALL_STARTING, sessionId)
        }
        onLLMCallCompleted {
            skillManager?.dispatchHooks(BraidrunHookEvent.LLM_CALL_COMPLETED, sessionId)
        }

        // --- LLM streaming events ---
        onLLMStreamingStarting {
            skillManager?.dispatchHooks(BraidrunHookEvent.LLM_STREAMING_STARTING, sessionId)
        }
        onLLMStreamingCompleted {
            skillManager?.dispatchHooks(BraidrunHookEvent.LLM_STREAMING_COMPLETED, sessionId)
        }
        onLLMStreamingFailed {
            logProgress(AnsiColor.RED, "LLM", "❌ LLM 流式传输失败")
            skillManager?.dispatchHooks(BraidrunHookEvent.LLM_STREAMING_FAILED, sessionId)
        }
        onLLMStreamingFrameReceived {
            skillManager?.dispatchHooks(BraidrunHookEvent.LLM_STREAMING_FRAME_RECEIVED, sessionId)
        }
    }
}
