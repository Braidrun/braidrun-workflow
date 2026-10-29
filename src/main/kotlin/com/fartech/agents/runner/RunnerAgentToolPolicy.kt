package com.fartech.agents.runner

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

    fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { value != JsonNull }?.content

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
