package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

// ============================================================================
// Claude Skill Models - Based on Anthropic's Skills Standard
// ============================================================================

/**
 * Represents a Claude Skill with metadata and content.
 * Skills are folders containing SKILL.md with YAML frontmatter.
 *
 * Follows the Agent Skills specification with lenient validation:
 * - Name format issues produce warnings but don't prevent loading
 * - Location tracks the absolute path for progressive disclosure catalog
 * - Supports structured wrapping for activation output
 */
@Serializable
data class ClaudeSkill(
    val name: String,
    val description: String,
    val version: String? = null,
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val content: String,
    val attachments: List<SkillAttachment> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val mcpServers: List<String> = emptyList(),
    /** Absolute path to the SKILL.md file; used for catalog disclosure and path resolution. */
    val location: String? = null,
    /** The scope where this skill was discovered (e.g., "project", "user", "cache"). */
    val scope: String? = null
) {
    init {
        require(name.isNotBlank()) { "Skill name cannot be blank" }
        require(description.isNotBlank()) { "Skill description cannot be blank" }
        // Lenient validation: warn instead of throwing for name format issues.
        // The specification defines strict constraints, but we relax them for
        // cross-client compatibility as recommended by the best practices guide.
    }

    /** The skill's base directory (parent of SKILL.md), derived from [location]. */
    val baseDirectory: String?
        get() = location?.let { loc ->
            val lastSep = maxOf(loc.lastIndexOf('/'), loc.lastIndexOf('\\'))
            if (lastSep > 0) loc.substring(0, lastSep) else null
        }

    /**
     * Returns just the name and description for progressive disclosure (Tier 1).
     * This is loaded into the system prompt at startup as part of the skill catalog.
     */
    fun toDiscoverySummary(): String = "**$name**: $description"

    /**
     * Returns the full skill content wrapped in structured XML tags (Tier 2 activation).
     * The structured wrapping allows the harness to identify skill content during
     * context compaction and clearly distinguishes skill instructions from other content.
     */
    fun toFullContent(): String = buildString {
        appendLine("<skill_content name=\"$name\">")
        appendLine("# Skill: $name")
        appendLine()
        if (version != null) appendLine("Version: $version")
        if (author != null) appendLine("Author: $author")
        if (tags.isNotEmpty()) appendLine("Tags: ${tags.joinToString(", ")}")
        appendLine()
        appendLine(content)

        // Skill directory and relative path resolution guidance
        if (baseDirectory != null) {
            appendLine()
            appendLine("Skill directory: $baseDirectory")
            appendLine("Relative paths in this skill are relative to the skill directory.")
        }

        // List bundled resources without eagerly loading them (Tier 3)
        val resources = listBundledResources()
        if (resources.isNotEmpty()) {
            appendLine()
            appendLine("<skill_resources>")
            resources.forEach { res ->
                appendLine("  <file>$res</file>")
            }
            appendLine("</skill_resources>")
        }

        if (attachments.isNotEmpty()) {
            appendLine()
            appendLine("## Attachments")
            attachments.forEach { attachment ->
                appendLine("- ${attachment.name} (${attachment.type.name.lowercase()}): ${attachment.description ?: "No description"}")
                if (attachment.type == AttachmentType.SCRIPT && attachment.absolutePath != null) {
                    val ext = attachment.name.substringAfterLast('.', "").lowercase()
                    val runCmd = when (ext) {
                        "py" -> "python3 ${attachment.absolutePath}"
                        "js" -> "node ${attachment.absolutePath}"
                        "ts" -> "npx tsx ${attachment.absolutePath}"
                        "sh", "bash", "zsh" -> "bash ${attachment.absolutePath}"
                        "rb" -> "ruby ${attachment.absolutePath}"
                        "pl" -> "perl ${attachment.absolutePath}"
                        "php" -> "php ${attachment.absolutePath}"
                        else -> attachment.absolutePath
                    }
                    appendLine("  **This is an executable script. Use ShellTools to run it: `$runCmd`**")
                }
                if (attachment.content != null && attachment.content.length <= 2000) {
                    val lang = attachment.name.substringAfterLast('.', "")
                    appendLine("```$lang")
                    appendLine(attachment.content)
                    appendLine("```")
                }
            }
        }
        appendLine("</skill_content>")
    }

    /**
     * Lists bundled resource files in the skill directory without reading their content.
     * Returns relative paths from the skill's base directory.
     * Caps at 50 entries to avoid overwhelming the model for large skill directories.
     */
    fun listBundledResources(maxEntries: Int = 50): List<String> {
        val dir = baseDirectory ?: return emptyList()
        val baseDir = java.io.File(dir)
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()

        return baseDir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && !it.name.equals("SKILL.md", ignoreCase = true) }
            .filter { !it.name.startsWith(".") }
            .take(maxEntries)
            .map { it.relativeTo(baseDir).path.replace('\\', '/') }
            .toList()
    }
}

/**
 * Represents an attachment (script, template, etc.) within a skill.
 */
@Serializable
data class SkillAttachment(
    val name: String,
    val type: AttachmentType,
    val path: String,
    val description: String? = null,
    val content: String? = null,
    val absolutePath: String? = null
) {
    init {
        require(name.isNotBlank()) { "Attachment name cannot be blank" }
        require(path.isNotBlank()) { "Attachment path cannot be blank" }
    }
}

@Serializable
enum class AttachmentType {
    @SerialName("script")
    SCRIPT,

    @SerialName("template")
    TEMPLATE,

    @SerialName("data")
    DATA,

    @SerialName("config")
    CONFIG,

    @SerialName("other")
    OTHER
}

/**
 * Configuration for Claude Skills loading and management.
 */
@Serializable
data class SkillsConfiguration(
    val skillsPath: String = "./skills",
    /**
     * Additional skill directories to scan beyond [skillsPath].
     * Supports multi-directory discovery as recommended by the Agent Skills specification.
     * Typical values: project-level (.agents/skills/, .claude/skills/) and
     * user-level (~/.agents/skills/, ~/.claude/skills/, ~/.braidrun/skills/).
     */
    val additionalSkillPaths: List<String> = emptyList(),
    /**
     * When true, automatically scan standard cross-client skill directories:
     * - Project-level: <workingDir>/.agents/skills/, <workingDir>/.claude/skills/
     * - User-level: ~/.agents/skills/, ~/.claude/skills/, ~/.braidrun/skills/
     * Default true for maximum cross-client interoperability.
     */
    val scanStandardPaths: Boolean = true,
    /**
     * When true, project-level skills (from the working directory) require the project
     * to be marked as trusted before loading. Prevents untrusted repositories from
     * silently injecting instructions into the agent's context.
     */
    val requireProjectTrust: Boolean = false,
    /** Set of project paths that the user has marked as trusted for skill loading. */
    val trustedProjects: List<String> = emptyList(),
    /** Global switch to enable or disable the entire skills subsystem. Default true. */
    val enabled: Boolean = true,
    /**
     * Explicit whitelist-mode switch for skill discovery.
     *
     * - `true`: only skills listed in [enabledSkills] are discoverable, plus built-in
     *   skills that are auto-allowlisted when [builtinSkillsEnabled] is true.
     * - `false`: all skills are discoverable unless blocklisted.
     * - `null`: legacy compatibility mode. Existing configs that only populated
     *   [enabledSkills] continue to behave like the old implicit allowlist mode.
     */
    val skillWhitelistMode: Boolean? = null,
    /**
     * Explicit skill whitelist entries.
     *
     * In whitelist mode, only these skill names are discoverable. Built-in skill names
     * are auto-added at runtime when [builtinSkillsEnabled] is true, and can still be
     * excluded via [disabledSkills] / [notLoadSkills].
     */
    val enabledSkills: List<String> = emptyList(),
    /** Blocklist: skill names listed here will never be loaded, regardless of enabledSkills. */
    val disabledSkills: List<String> = emptyList(),
    /** Skills in this list will not be loaded (alias for disabledSkills, merged at runtime). */
    val notLoadSkills: List<String> = emptyList(),
    val autoDiscovery: Boolean = true,
    val maxSkillsPerRequest: Int = 8, // Anthropic recommends up to 8 skills
    val progressiveDisclosure: Boolean = true, // Load summaries first
    val maxAttachmentSize: Long = 100_000, // 100KB max per attachment
    val maxSkillContentSize: Long = 500_000, // 500KB max per skill
    val skillsMetadata: Map<String, SkillMetadata> = emptyMap(),
    /**
     * When true, the system will automatically search and download new skills from ClawHub
     * when an agent requests a capability that no locally-installed skill provides.
     * Default false to keep enterprise executions deterministic unless explicitly enabled.
     */
    val autoSearchDownload: Boolean = false,
    /**
     * When true, the system will query ClawHub for the latest version of each cached skill
     * at load time, and automatically upgrade to the newer version before loading.
     * Default false to avoid unexpected network calls.
     */
    val autoUpgrade: Boolean = false,
    /**
     * When true, workflow executions with directory isolation will materialize
     * filesystem-backed skill directories into the execution's sandbox runtime
     * area, then point `skill_tools` and related runtime vars at those staged
     * copies instead of the original repository paths.
     *
     * Default true so workflow agents running under directory isolation can
     * use filesystem-backed skills and attachments without manually opting in.
     */
    val materializeRuntimeSkills: Boolean = true,
    /**
     * Optional workspace directory. When set, hooks/braidrun-agent/ inside this directory
     * will be scanned for workspace-level hooks with the highest priority.
     */
    val workspaceDir: String? = null,
    /**
     * Key-value configuration entries passed verbatim to handler scripts via the
     * [BraidrunHookContext.config] field in stdin JSON.  Populated automatically by
     * [createSkillManager] from the agent's configuration parameters so that scripts
     * can inspect settings such as model name, agent name, or workspace path without
     * reading config files themselves.
     */
    val hookContextConfig: Map<String, String> = emptyMap(),

    // ------------------------------------------------------------------
    // Built-in (static) skills options
    // ------------------------------------------------------------------

    /**
     * When true, load built-in skills packaged as static resources inside the JAR.
     * Built-in skills live under the classpath directory specified by [builtinSkillsResourcePath]
     * and are listed in a `builtin-skills-index.txt` file within that directory.
     * Default true so that bundled skills are available out-of-the-box.
     */
    val builtinSkillsEnabled: Boolean = true,

    /**
     * Classpath prefix where built-in skill resources are stored.
     * Each skill is a sub-directory containing a `SKILL.md` resource.
     * The directory must also contain a `builtin-skills-index.txt` listing one
     * skill directory name per line (blank lines and `#`-comments are ignored).
     *
     * Example layout inside the JAR:
     * ```
     * /builtin-skills/
     *   builtin-skills-index.txt      ← lists "example-skill"
     *   example-skill/
     *     SKILL.md
     *     scripts/helper.py
     * ```
     */
    val builtinSkillsResourcePath: String = "/builtin-skills",

    // ------------------------------------------------------------------
    // Hook subsystem options
    // ------------------------------------------------------------------

    /**
     * Global switch for the braidrun-agent hooks subsystem.
     * When false, all hooks are skipped entirely (no loading, no execution).
     */
    val hooksEnabled: Boolean = true,

    /**
     * When false, handler scripts (.py / .js / .kts) will NOT be executed even if
     * they are present in a hook directory.  Static HOOK.md content is still injected.
     * Useful in security-sensitive or air-gapped environments.
     */
    val hookScriptExecutionEnabled: Boolean = true,

    /**
     * Maximum wall-clock seconds a handler script may run before it is forcibly killed.
     * Defaults to 30 seconds.
     */
    val hookTimeoutSeconds: Long = 30L,

    /**
     * Subset of script extensions that are allowed to execute.
     * Supported values: "py", "js", "ts", "kts".
     * Defaults to all four.  Restrict to e.g. ["py"] when Node.js or kotlinc are
     * not available or not trusted.
     */
    val allowedScriptTypes: List<String> = listOf("py", "js", "ts", "kts"),

    /**
     * Override the default user-global hooks directory (~/.braidrun/hooks/braidrun-agent/).
     * When null the default path is used.  Absolute path expected.
     */
    val userGlobalHooksDir: String? = null
) {
    init {
        require(maxSkillsPerRequest in 1..20) {
            "maxSkillsPerRequest must be between 1 and 20"
        }
        require(maxAttachmentSize > 0) { "maxAttachmentSize must be positive" }
        require(maxSkillContentSize > 0) { "maxSkillContentSize must be positive" }
    }

    /**
     * Returns true when whitelist-mode filtering is active.
     *
     * `null` preserves the historical behavior where a non-empty [enabledSkills] list
     * implicitly acted as an allowlist.
     */
    fun isSkillWhitelistModeEnabled(): Boolean = when (skillWhitelistMode) {
        true -> true
        false -> false
        null -> enabledSkills.isNotEmpty()
    }

    /**
     * Returns true when the global skills switch is on AND the specific skill passes
     * allow/block checks.
     *
     * [autoWhitelistedSkillNames] is used by the loader to inject built-in skill names
     * into whitelist mode so that built-ins stay visible by default, while still
     * allowing users to remove them through the blocklist.
     */
    fun isSkillEnabled(
        skillName: String,
        autoWhitelistedSkillNames: Set<String> = emptySet()
    ): Boolean {
        if (!enabled) return false
        val allDisabled = disabledSkills + notLoadSkills
        if (allDisabled.contains(skillName)) return false
        if (!isSkillWhitelistModeEnabled()) return true

        val effectiveAllowlist = buildSet {
            addAll(enabledSkills)
            if (builtinSkillsEnabled) {
                addAll(autoWhitelistedSkillNames)
            }
        }
        return effectiveAllowlist.contains(skillName)
    }
}

/**
 * Additional metadata for skill management.
 */
@Serializable
data class SkillMetadata(
    val priority: Int = 0,
    val contexts: List<String> = emptyList(), // When this skill should be used
    val excludeWith: List<String> = emptyList(), // Skills to exclude when this is loaded
    val requiredCapabilities: List<String> = emptyList()
)

// ============================================================================
// Skill Loader - Parses SKILL.md files with YAML frontmatter
// ============================================================================

/**
 * Loads Claude Skills from the filesystem.
 * Thread-safe implementation with proper resource management.
 *
 * Supports multi-directory discovery following the Agent Skills specification:
 * - Primary skills path (from configuration)
 * - Additional paths (from configuration)
 * - Standard cross-client paths (.agents/skills/, .claude/skills/) when enabled
 * - Project-level skills override user-level skills (name collision precedence)
 */
class SkillLoader(
    private val skillsPath: Path,
    private val config: SkillsConfiguration
) {
    companion object {
        private const val SKILL_FILENAME = "SKILL.md"
        private const val YAML_DELIMITER = "---"

        /** Recommended depth 4-6 levels per the Agent Skills best practices guide. */
        private const val MAX_FILE_WALK_DEPTH = 5

        /** Max directories to scan to prevent runaway scanning in large trees. */
        private const val MAX_DIRECTORIES_TO_SCAN = 2000

        /** Directories to skip during skill discovery. */
        private val SKIP_DIRECTORIES = setOf(
            ".git", "node_modules", ".svn", ".hg", "__pycache__",
            ".gradle", ".idea", ".vscode", "build", "dist", "target",
            ".tox", ".mypy_cache", ".pytest_cache", ".eggs"
        )
    }

    private val autoWhitelistedSkillNames: Set<String> by lazy {
        if (!config.isSkillWhitelistModeEnabled() || !config.builtinSkillsEnabled) {
            emptySet()
        } else {
            loadBuiltinSkillNames()
        }
    }

    private fun isSkillEnabled(skillName: String): Boolean =
        config.isSkillEnabled(skillName, autoWhitelistedSkillNames)

    /**
     * Discovers and loads all skills from all configured skill paths.
     *
     * Implements the multi-directory discovery strategy recommended by the specification:
     * 1. Scans primary skillsPath
     * 2. Scans additional configured paths
     * 3. Scans standard cross-client paths (.agents/skills/, .claude/skills/) if enabled
     *
     * Name collision handling: project-level skills override user-level skills.
     * Within the same scope, first-found wins. Warnings are logged for shadowed skills.
     *
     * @return List of successfully loaded, deduplicated skills.
     */
    fun loadAllSkills(): List<ClaudeSkill> {
        if (!config.enabled) {
            logProgress(
                AnsiColor.CYAN,
                "Skills",
                "Skills subsystem disabled (enabled=false) — skipping skill discovery"
            )
            return emptyList()
        }

        // Collect skills from all paths with scope tags for precedence handling
        val allSkills = mutableListOf<ClaudeSkill>()

        // 1. Primary skills path (cache or configured)
        allSkills.addAll(loadSkillsFromPath(skillsPath, scope = "configured"))

        // 2. Additional configured paths
        config.additionalSkillPaths.forEach { path ->
            try {
                allSkills.addAll(loadSkillsFromPath(Paths.get(path), scope = "additional"))
            } catch (e: Exception) {
                logProgress(AnsiColor.YELLOW, "Skills", "⚠ Could not scan additional path '$path': ${e.message}")
            }
        }

        // 3. Standard cross-client paths (when enabled)
        if (config.scanStandardPaths) {
            val workingDir = System.getProperty("user.dir") ?: ""
            val userHome = System.getProperty("user.home") ?: ""

            // Project-level paths (higher precedence)
            val projectPaths = listOf(
                "$workingDir/.braidrun/skills",
                "$workingDir/.agents/skills",
                "$workingDir/.claude/skills"
            )
            // Check trust for project-level skills
            val projectTrusted = !config.requireProjectTrust ||
                    config.trustedProjects.any { workingDir.startsWith(it) }

            if (projectTrusted) {
                projectPaths.forEach { path ->
                    try {
                        val p = Paths.get(path)
                        if (p.exists() && p.isDirectory() && p != skillsPath) {
                            allSkills.addAll(loadSkillsFromPath(p, scope = "project"))
                        }
                    } catch (e: Exception) {
                        logProgress(AnsiColor.YELLOW, "Skills", "⚠ Could not scan project path '$path': ${e.message}")
                    }
                }
            } else {
                logProgress(
                    AnsiColor.YELLOW, "Skills",
                    "⚠ Project-level skills skipped: project not trusted. " +
                            "Add the project path to trustedProjects or set requireProjectTrust=false."
                )
            }

            // User-level paths (lower precedence)
            val userPaths = listOf(
                "$userHome/.braidrun/skills",
                "$userHome/.agents/skills",
                "$userHome/.claude/skills"
            )
            userPaths.forEach { path ->
                try {
                    val p = Paths.get(path)
                    if (p.exists() && p.isDirectory() && p != skillsPath) {
                        allSkills.addAll(loadSkillsFromPath(p, scope = "user"))
                    }
                } catch (e: Exception) {
                    logProgress(AnsiColor.YELLOW, "Skills", "⚠ Could not scan user path '$path': ${e.message}")
                }
            }
        }

        // 4. Built-in skills from classpath/JAR (lowest precedence)
        if (config.builtinSkillsEnabled) {
            try {
                allSkills.addAll(loadBuiltinSkills())
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.YELLOW, "Skills",
                    "⚠ Could not load built-in skills: ${e.message}"
                )
            }
        }

        // Apply name collision precedence: project > configured > additional > user > builtin
        // First-found within same scope wins. Log warnings for shadowed skills.
        val scopePrecedence = mapOf(
            "project" to 0,
            "configured" to 1,
            "additional" to 2,
            "user" to 3,
            "cache" to 4,
            "builtin" to 5
        )
        val deduped = LinkedHashMap<String, ClaudeSkill>()
        val sortedSkills = allSkills.sortedBy { scopePrecedence[it.scope] ?: 99 }
        for (skill in sortedSkills) {
            val existing = deduped[skill.name]
            if (existing != null) {
                logProgress(
                    AnsiColor.YELLOW, "Skills",
                    "⚠ Skill name collision: '${skill.name}' from scope '${skill.scope}' " +
                            "shadowed by existing skill from scope '${existing.scope}'"
                )
            } else {
                deduped[skill.name] = skill
            }
        }

        return deduped.values.toList()
    }

    /**
     * Loads built-in skills packaged as static resources inside the JAR/classpath.
     *
     * Built-in skills are discovered via a `builtin-skills-index.txt` file located at
     * [SkillsConfiguration.builtinSkillsResourcePath]. Each line in the index names a
     * sub-directory that must contain a `SKILL.md` classpath resource.
     *
     * Because classpath resources cannot be "walked" like a filesystem directory,
     * the index file is the single source of truth for which built-in skills exist.
     *
     * All built-in skills receive scope = "builtin" (lowest precedence) so that
     * user-level, project-level, and configured skills always override them.
     *
     * @return List of successfully loaded built-in skills.
     */
    fun loadBuiltinSkills(): List<ClaudeSkill> {
        val basePath = config.builtinSkillsResourcePath.trimEnd('/')
        val indexPath = "$basePath/builtin-skills-index.txt"

        val indexStream = javaClass.getResourceAsStream(indexPath)
        if (indexStream == null) {
            // No index file → no built-in skills packaged
            logProgress(
                AnsiColor.CYAN, "Skills",
                "No built-in skills index found at classpath:$indexPath"
            )
            return emptyList()
        }

        val skillDirNames = indexStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        if (skillDirNames.isEmpty()) {
            logProgress(AnsiColor.CYAN, "Skills", "Built-in skills index is empty")
            return emptyList()
        }

        val skills = mutableListOf<ClaudeSkill>()
        for (dirName in skillDirNames) {
            try {
                val skill = loadBuiltinSkill(basePath, dirName)
                if (skill != null && isSkillEnabled(skill.name)) {
                    skills.add(skill)
                    logProgress(AnsiColor.GREEN, "Skills", "✓ Loaded built-in skill: ${skill.name}")
                }
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.YELLOW, "Skills",
                    "⚠ Failed to load built-in skill '$dirName': ${e.message}"
                )
            }
        }

        return skills
    }

    /**
     * Loads a single built-in skill from the classpath.
     *
     * @param basePath The classpath prefix (e.g. "/builtin-skills")
     * @param dirName  The skill directory name inside [basePath]
     * @return The parsed [ClaudeSkill] or null if the resource doesn't exist.
     */
    private fun loadBuiltinSkill(basePath: String, dirName: String): ClaudeSkill? {
        val skillMdPath = "$basePath/$dirName/$SKILL_FILENAME"
        val stream = javaClass.getResourceAsStream(skillMdPath)
            ?: javaClass.getResourceAsStream("$basePath/$dirName/${SKILL_FILENAME.lowercase()}")

        if (stream == null) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Built-in skill '$dirName': SKILL.md not found at classpath:$skillMdPath"
            )
            return null
        }

        val content = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (content.isBlank()) {
            logProgress(AnsiColor.YELLOW, "Skills", "⚠ Built-in skill '$dirName': SKILL.md is empty")
            return null
        }

        if (content.length > config.maxSkillContentSize) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Built-in skill '$dirName': content too large (${content.length} chars, max: ${config.maxSkillContentSize}), skipping"
            )
            return null
        }

        val (frontmatter, body) = try {
            parseSkillFile(content)
        } catch (e: Exception) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Built-in skill '$dirName': failed to parse SKILL.md: ${e.message}"
            )
            return null
        }

        val rawName = frontmatter["name"]?.toString()?.trim()
        if (rawName.isNullOrBlank()) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Built-in skill '$dirName': no 'name' in frontmatter, skipping"
            )
            return null
        }

        val sanitizedName = rawName
            .replace(Regex("[^a-zA-Z0-9_-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        if (sanitizedName != rawName) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Built-in skill name '$rawName' contains non-standard characters, sanitized to '$sanitizedName'"
            )
        }

        val description = frontmatter["description"]?.toString()?.trim()
        if (description.isNullOrBlank()) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Built-in skill '$dirName': no description, skipping"
            )
            return null
        }

        // Enumerate bundled resource files in the skill's classpath directory.
        // Since classpath directories cannot be walked, we check for a
        // resources-index.txt listing inside the skill directory.
        val resourceIndexPath = "$basePath/$dirName/resources-index.txt"
        val builtinResources = try {
            javaClass.getResourceAsStream(resourceIndexPath)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { r ->
                    r.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                } ?: emptyList()
        } catch (_: Exception) {
            emptyList<String>()
        }

        // Build attachments from resource index
        val attachments = builtinResources.map { resPath ->
            SkillAttachment(
                name = resPath.substringAfterLast('/'),
                type = determineAttachmentType(Paths.get(resPath)),
                path = resPath,
                description = "Built-in resource: $resPath"
            )
        }

        // The classpath location is recorded with a classpath: prefix so callers
        // can distinguish it from a filesystem path.
        val classpathLocation = "classpath:$basePath/$dirName/$SKILL_FILENAME"

        return ClaudeSkill(
            name = sanitizedName,
            description = description,
            version = frontmatter["version"]?.toString()?.trim(),
            author = frontmatter["author"]?.toString()?.trim(),
            tags = parseListField(frontmatter["tags"]),
            dependencies = parseListField(frontmatter["dependencies"]),
            content = body,
            attachments = attachments,
            metadata = frontmatter.mapValues { it.value.toString() },
            mcpServers = emptyList(), // Built-in skills don't support MCP server discovery
            location = classpathLocation,
            scope = "builtin"
        )
    }

    /**
     * Resolves the effective names of all packaged built-in skills so whitelist mode can
     * auto-allow them by name. This preserves normal name-based precedence, allowing a
     * project/user override of a built-in skill to stay discoverable without duplicating
     * that name in [SkillsConfiguration.enabledSkills].
     */
    private fun loadBuiltinSkillNames(): Set<String> {
        val basePath = config.builtinSkillsResourcePath.trimEnd('/')
        val indexPath = "$basePath/builtin-skills-index.txt"
        val indexStream = javaClass.getResourceAsStream(indexPath) ?: return emptySet()

        val skillDirNames = indexStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        return skillDirNames.mapNotNull { dirName ->
            try {
                loadBuiltinSkill(basePath, dirName)?.name
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.YELLOW,
                    "Skills",
                    "⚠ Failed to resolve built-in whitelist entry '$dirName': ${e.message}"
                )
                null
            }
        }.toSet()
    }

    /**
     * Scans a single directory for skills.
     * Skips known non-skill directories (.git, node_modules, etc.) and respects
     * the max directory scan limit to prevent runaway scanning.
     */
    private fun loadSkillsFromPath(basePath: Path, scope: String = "configured"): List<ClaudeSkill> {
        if (!basePath.exists()) {
            return emptyList()
        }

        val skills = mutableListOf<ClaudeSkill>()
        var dirCount = 0

        try {
            Files.walk(basePath, MAX_FILE_WALK_DEPTH).use { paths ->
                val iterator = paths
                    .filter { it.isDirectory() }
                    .filter { dir ->
                        // Skip known non-skill directories
                        !SKIP_DIRECTORIES.contains(dir.fileName?.toString())
                    }
                    .iterator()
                // Explicit iterator + break: `return@forEach` inside Stream.forEach only
                // skips the element — the walk itself (the expensive part this cap exists
                // to stop) continued over the whole tree and re-logged the warning once
                // per excess directory.
                while (iterator.hasNext()) {
                    val skillDir = iterator.next()
                    if (++dirCount > MAX_DIRECTORIES_TO_SCAN) {
                        logProgress(
                            AnsiColor.YELLOW, "Skills",
                            "⚠ Reached max directory scan limit ($MAX_DIRECTORIES_TO_SCAN) in $basePath"
                        )
                        break
                    }
                    try {
                        val skill = loadSkill(skillDir, scope)
                        if (skill != null && isSkillEnabled(skill.name)) {
                            skills.add(skill)
                            logProgress(AnsiColor.GREEN, "Skills", "✓ Loaded skill: ${skill.name} [${scope}]")
                        }
                    } catch (e: IllegalArgumentException) {
                        // Lenient: log warning but don't crash
                        logProgress(
                            AnsiColor.YELLOW,
                            "Skills",
                            "⚠ Skipped skill in ${skillDir.fileName}: ${e.message}"
                        )
                    } catch (e: Exception) {
                        logProgress(
                            AnsiColor.YELLOW,
                            "Skills",
                            "⚠ Failed to load skill from ${skillDir.fileName}: ${e.message}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logProgress(
                AnsiColor.YELLOW,
                "Skills",
                "⚠ Error walking skills directory $basePath: ${e.message}"
            )
        }

        return skills
    }

    /**
     * Loads a single skill from a directory.
     * Returns null if SKILL.md is not found.
     *
     * Implements lenient validation as recommended by the specification:
     * - Name doesn't match parent directory → warn, load anyway
     * - Name exceeds 64 characters → warn, load anyway
     * - Name has non-standard characters → sanitize and warn, load anyway
     * - Description missing or empty → skip the skill, log error
     * - YAML completely unparseable → skip the skill, log error
     *
     * @param scope The discovery scope ("project", "user", "configured", etc.)
     */
    fun loadSkill(skillDir: Path, scope: String = "configured"): ClaudeSkill? {
        // Case-insensitive matching to handle varying naming conventions (SKILL.md, skill.md, Skill.md, etc.)
        val skillFile = skillDir.toFile().listFiles()?.firstOrNull {
            it.isFile && it.name.equals(SKILL_FILENAME, ignoreCase = true)
        }?.toPath()

        if (skillFile == null || !skillFile.isRegularFile()) {
            return null
        }

        // Check file size before reading
        val fileSize = Files.size(skillFile)
        if (fileSize > config.maxSkillContentSize) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Skill file too large in ${skillDir.fileName}: $fileSize bytes (max: ${config.maxSkillContentSize}), skipping"
            )
            return null
        }

        val content = try {
            skillFile.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Failed to read skill file in ${skillDir.fileName}: ${e.message}"
            )
            return null
        }

        if (content.isBlank()) {
            logProgress(AnsiColor.YELLOW, "Skills", "⚠ Skill file is empty in ${skillDir.fileName}")
            return null
        }

        val (frontmatter, body) = try {
            parseSkillFile(content)
        } catch (e: Exception) {
            // YAML completely unparseable → skip the skill, log error
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Failed to parse SKILL.md in ${skillDir.fileName}: ${e.message}"
            )
            return null
        }

        val rawName = frontmatter["name"]?.toString()?.trim()
        if (rawName.isNullOrBlank()) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Skill in ${skillDir.fileName} has no 'name' in frontmatter, skipping"
            )
            return null
        }

        // Lenient name validation: sanitize but warn instead of rejecting
        val sanitizedName = rawName
            .replace(Regex("[^a-zA-Z0-9_-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        if (sanitizedName != rawName) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Skill name '$rawName' contains non-standard characters, sanitized to '$sanitizedName'"
            )
        }
        val name = sanitizedName

        // Warn if name exceeds 64 characters (spec recommendation)
        if (name.length > 64) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Skill name '$name' exceeds 64 characters (${name.length}), loading anyway"
            )
        }

        // Warn if name doesn't match parent directory name
        val dirName = skillDir.fileName?.toString()
        if (dirName != null && !dirName.equals(name, ignoreCase = true) &&
            !dirName.startsWith(name, ignoreCase = true)
        ) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Skill name '$name' does not match parent directory '$dirName', loading anyway"
            )
        }

        // Description is essential for disclosure — skip if missing
        val description = frontmatter["description"]?.toString()?.trim()
        if (description.isNullOrBlank()) {
            logProgress(
                AnsiColor.YELLOW, "Skills",
                "⚠ Skill '${rawName}' in ${skillDir.fileName} has no description, skipping (description is essential for catalog disclosure)"
            )
            return null
        }

        val attachments = loadAttachments(skillDir, frontmatter)

        // Discover MCP servers
        val mcpServerManager = MCPServerManager()
        val mcpServers = mcpServerManager.discoverMCPServers(skillDir).map { it.fileName.toString() }

        return ClaudeSkill(
            name = name,
            description = description,
            version = frontmatter["version"]?.toString()?.trim(),
            author = frontmatter["author"]?.toString()?.trim(),
            tags = parseListField(frontmatter["tags"]),
            dependencies = parseListField(frontmatter["dependencies"]),
            content = body,
            attachments = attachments,
            metadata = frontmatter.mapValues { it.value.toString() },
            mcpServers = mcpServers,
            location = skillFile.toAbsolutePath().toString(),
            scope = scope
        )
    }

    /**
     * Parses SKILL.md file, separating YAML frontmatter from markdown content.
     */
    private fun parseSkillFile(content: String): Pair<Map<String, Any>, String> {
        val lines = content.lines()

        if (lines.isEmpty()) {
            throw IllegalArgumentException("SKILL.md file is empty")
        }

        if (lines.first().trim() != YAML_DELIMITER) {
            throw IllegalArgumentException(
                "SKILL.md must start with YAML frontmatter delimiter (---)"
            )
        }

        val frontmatterEndIndex = lines.drop(1).indexOfFirst { it.trim() == YAML_DELIMITER }
        if (frontmatterEndIndex == -1) {
            throw IllegalArgumentException("YAML frontmatter must be closed with ---")
        }

        // Actual index in original list
        val actualEndIndex = frontmatterEndIndex + 1

        val yamlLines = lines.subList(1, actualEndIndex)
        val bodyLines = lines.drop(actualEndIndex + 1)

        val frontmatter = parseYamlFrontmatter(yamlLines.joinToString("\n"))
        val body = bodyLines.joinToString("\n").trim()

        return frontmatter to body
    }

    /**
     * Simple YAML parser for frontmatter (handles key: value pairs and lists).
     * This is not a full YAML parser but handles the subset used in Skills.
     *
     * Includes a fallback for malformed YAML values containing unquoted colons,
     * which is the most common cross-client compatibility issue. For example:
     * ```yaml
     * description: Use this skill when: the user asks about PDFs
     * ```
     * The colon after "when" technically breaks YAML parsing. This parser handles
     * it by treating everything after the first colon as the value.
     */
    /**
     * Block scalar mode for YAML `>` (folded) and `|` (literal) syntax.
     * FOLDED joins lines with spaces; LITERAL joins lines with newlines.
     */
    private enum class BlockScalarMode { FOLDED, LITERAL }

    private fun parseYamlFrontmatter(yaml: String): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        var currentKey: String? = null
        val currentList = mutableListOf<String>()
        // Block scalar state: when a key's value is ">" or "|", subsequent indented lines
        // are collected here and joined according to the mode.
        var blockScalarMode: BlockScalarMode? = null
        val blockScalarLines = mutableListOf<String>()

        fun flushPending() {
            val key = currentKey ?: return
            when {
                blockScalarMode != null && blockScalarLines.isNotEmpty() -> {
                    val separator = if (blockScalarMode == BlockScalarMode.FOLDED) " " else "\n"
                    map[key] = blockScalarLines.joinToString(separator).trim()
                }
                currentList.isNotEmpty() -> {
                    map[key] = currentList.toList()
                }
            }
            currentList.clear()
            blockScalarLines.clear()
            blockScalarMode = null
            currentKey = null
        }

        yaml.lines().forEach { line ->
            val trimmed = line.trim()

            // When inside a block scalar, collect indented continuation lines.
            // A non-empty line that is NOT indented (no leading whitespace) signals the end
            // of the block scalar.  Empty lines inside block scalars are preserved for
            // literal mode and act as paragraph breaks for folded mode (approximated here
            // by simply skipping them, which matches how descriptions are typically used).
            if (blockScalarMode != null && currentKey != null) {
                if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                    // Indented continuation line — belongs to the block scalar
                    if (trimmed.isNotEmpty()) {
                        blockScalarLines.add(trimmed)
                    }
                    return@forEach
                } else if (trimmed.isEmpty()) {
                    // Blank line inside a block scalar — preserve for literal, skip for folded
                    if (blockScalarMode == BlockScalarMode.LITERAL) {
                        blockScalarLines.add("")
                    }
                    return@forEach
                } else {
                    // Non-indented, non-empty line — block scalar ends, fall through to normal parsing
                    flushPending()
                }
            }

            when {
                // Skip empty lines and comments
                trimmed.isEmpty() || trimmed.startsWith("#") -> {
                    // Do nothing
                }

                // List item (must start with "- ")
                trimmed.startsWith("- ") && currentKey != null -> {
                    var item = trimmed.substring(2).trim()
                    if (item.isNotEmpty()) {
                        // Handle basic quoting
                        if ((item.startsWith("\"") && item.endsWith("\"")) ||
                            (item.startsWith("'") && item.endsWith("'"))
                        ) {
                            item = item.substring(1, item.length - 1)
                        }
                        currentList.add(item)
                    }
                }

                // Key-value pair (contains ":")
                trimmed.contains(":") -> {
                    // Save previous pending data
                    flushPending()

                    // Parse new key-value pair
                    val colonIndex = trimmed.indexOf(':')
                    val key = trimmed.substring(0, colonIndex).trim()
                    var value = trimmed.substring(colonIndex + 1).trim()

                    if (key.isEmpty()) {
                        // Skip invalid keys
                        currentKey = null
                    } else if (value == ">" || value == ">-") {
                        // YAML folded block scalar
                        currentKey = key
                        blockScalarMode = BlockScalarMode.FOLDED
                    } else if (value == "|" || value == "|-") {
                        // YAML literal block scalar
                        currentKey = key
                        blockScalarMode = BlockScalarMode.LITERAL
                    } else if (value.isNotEmpty()) {
                        // Direct value
                        // Handle basic quoting
                        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))
                        ) {
                            value = value.substring(1, value.length - 1)
                        }
                        map[key] = value
                        currentKey = null
                    } else {
                        // Value on next lines (possibly a list)
                        currentKey = key
                    }
                }
            }
        }

        // Flush any remaining pending data
        flushPending()

        return map
    }

    /**
     * Loads attachments referenced in the skill directory.
     */
    private fun loadAttachments(
        skillDir: Path,
        frontmatter: Map<String, Any>
    ): List<SkillAttachment> {
        val attachmentsList = parseListField(frontmatter["attachments"])

        // Phase 11 (2026-05-14) — defense in depth against a malicious SKILL.md that
        // references `attachments: ['../../../etc/passwd']` or symlinks under the skill dir
        // pointing outside. `Path.resolve` does NOT collapse `..`, so a naive `skillDir
        // .resolve("../../etc/passwd")` walks straight out of the skill sandbox.
        //
        // Resolve, normalise, then enforce that the canonical attachment path stays inside
        // the canonical skill directory. A symlink escape is caught because we canonicalize
        // both sides via `toRealPath`-equivalent (`canonicalFile` on regular paths; we fall
        // back to `normalize`+`toAbsolutePath` when the file is missing to keep the original
        // "not found" log path live).
        val skillDirCanonical = try {
            skillDir.toRealPath()
        } catch (_: Exception) {
            skillDir.toAbsolutePath().normalize()
        }

        return attachmentsList.mapNotNull { attachmentPath ->
            try {
                val resolved = skillDir.resolve(attachmentPath).normalize()
                val canonical = try {
                    resolved.toRealPath()
                } catch (_: Exception) {
                    resolved.toAbsolutePath().normalize()
                }
                if (!canonical.startsWith(skillDirCanonical)) {
                    logProgress(
                        AnsiColor.RED,
                        "Skills",
                        "✗ Rejected attachment outside skill dir: $attachmentPath -> $canonical"
                    )
                    return@mapNotNull null
                }
                val file = canonical

                if (!file.exists()) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "Skills",
                        "Attachment not found: $attachmentPath"
                    )
                    return@mapNotNull null
                }

                if (!file.isRegularFile()) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "Skills",
                        "Attachment is not a file: $attachmentPath"
                    )
                    return@mapNotNull null
                }

                val fileSize = Files.size(file)
                if (fileSize > config.maxAttachmentSize) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "Skills",
                        "Attachment too large: $attachmentPath ($fileSize bytes, max: ${config.maxAttachmentSize})"
                    )
                    return@mapNotNull null
                }

                val content = try {
                    file.readText(StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "Skills",
                        "Could not read attachment: $attachmentPath (${e.message})"
                    )
                    return@mapNotNull null
                }

                SkillAttachment(
                    name = file.fileName.toString(),
                    type = determineAttachmentType(file),
                    path = attachmentPath,
                    content = content,
                    absolutePath = file.toAbsolutePath().toString()
                )
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.YELLOW,
                    "Skills",
                    "Error loading attachment: $attachmentPath (${e.message})"
                )
                null
            }
        }
    }

    /**
     * Determines attachment type based on file extension.
     */
    private fun determineAttachmentType(file: Path): AttachmentType {
        val extension = file.fileName.toString().substringAfterLast('.', "").lowercase()
        return when (extension) {
            "py", "js", "ts", "sh", "bash", "zsh", "fish", "rb", "pl", "php" -> AttachmentType.SCRIPT
            "template", "tmpl", "tpl" -> AttachmentType.TEMPLATE
            "json", "yaml", "yml", "toml", "ini", "conf", "config" -> AttachmentType.CONFIG
            "csv", "txt", "md", "xml", "html" -> AttachmentType.DATA
            else -> AttachmentType.OTHER
        }
    }

    /**
     * Parses a field that can be either a list or a comma-separated string.
     */
    private fun parseListField(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    /**
     * Discovers and loads all braidrun-agent hooks from all skill directories.
     *
     * Mirrors the multi-directory scanning logic of [loadAllSkills] so that hooks
     * bundled inside skills discovered via additionalSkillPaths, standard cross-client
     * paths, and built-in skills are all considered.
     *
     * Hook precedence (lowest to highest):
     *   builtin skill hooks → user-level skill hooks → additional path skill hooks
     *   → configured path skill hooks → project-level skill hooks
     *   → user-global hooks → workspace hooks.
     * Later entries override earlier ones with the same name.
     */
    fun loadAllHooks(): List<BraidrunAgentHook> {
        if (!config.enabled) {
            logProgress(
                AnsiColor.YELLOW,
                "Hooks",
                "⚠ Skills subsystem disabled (enabled=false) — skipping hook loading"
            )
            return emptyList()
        }
        if (!config.hooksEnabled) {
            logProgress(
                AnsiColor.YELLOW,
                "Hooks",
                "⚠ Hooks subsystem disabled (hooksEnabled=false) — skipping hook loading"
            )
            return emptyList()
        }
        val hookLoader = BraidrunHookLoader(config)
        // Use LinkedHashMap to preserve insertion order; later puts override earlier ones.
        val hooksMap = LinkedHashMap<String, BraidrunAgentHook>()

        // Helper: scan a directory for skill sub-directories and load their hooks
        fun scanPathForHooks(basePath: Path, label: String) {
            if (!basePath.exists() || !basePath.isDirectory()) return
            try {
                Files.walk(basePath, MAX_FILE_WALK_DEPTH).use { paths ->
                    paths
                        .filter { it.isDirectory() }
                        .filter { dir -> !SKIP_DIRECTORIES.contains(dir.fileName?.toString()) }
                        .forEach { skillDir ->
                            // Case-insensitive matching for SKILL.md
                            val skillFile = skillDir.toFile().listFiles()?.firstOrNull {
                                it.isFile && it.name.equals(SKILL_FILENAME, ignoreCase = true)
                            }
                            if (skillFile == null) return@forEach
                            val skill = loadSkill(skillDir, label) ?: return@forEach
                            if (!isSkillEnabled(skill.name)) return@forEach

                            val hook = hookLoader.loadHookFromSkillDir(skillDir, skill.name)
                            if (hook != null) {
                                hooksMap[hook.name] = hook
                                logProgress(
                                    AnsiColor.GREEN,
                                    "Hooks",
                                    "✓ Loaded skill hook: ${hook.name} (from ${skill.name}) [$label]"
                                )
                            }
                        }
                }
            } catch (e: Exception) {
                logProgress(AnsiColor.RED, "Hooks", "✗ Error loading skill hooks from $label ($basePath): ${e.message}")
            }
        }

        // 1. Primary skills path (configured)
        scanPathForHooks(skillsPath, "configured")

        // 2. Additional configured paths
        config.additionalSkillPaths.forEach { path ->
            try {
                scanPathForHooks(Paths.get(path), "additional")
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.YELLOW,
                    "Hooks",
                    "⚠ Could not scan additional path '$path' for hooks: ${e.message}"
                )
            }
        }

        // 3. Standard cross-client paths (when enabled)
        if (config.scanStandardPaths) {
            val workingDir = System.getProperty("user.dir") ?: ""
            val userHome = System.getProperty("user.home") ?: ""

            // Project-level paths
            val projectTrusted = !config.requireProjectTrust ||
                    config.trustedProjects.any { workingDir.startsWith(it) }
            if (projectTrusted) {
                listOf(
                    "$workingDir/.braidrun/skills",
                    "$workingDir/.agents/skills",
                    "$workingDir/.claude/skills"
                ).forEach { path ->
                    try {
                        val p = Paths.get(path)
                        if (p != skillsPath) scanPathForHooks(p, "project")
                    } catch (e: Exception) {
                        logProgress(
                            AnsiColor.YELLOW,
                            "Hooks",
                            "⚠ Could not scan project path '$path' for hooks: ${e.message}"
                        )
                    }
                }
            }

            // User-level paths
            listOf(
                "$userHome/.braidrun/skills",
                "$userHome/.agents/skills",
                "$userHome/.claude/skills"
            ).forEach { path ->
                try {
                    val p = Paths.get(path)
                    if (p != skillsPath) scanPathForHooks(p, "user")
                } catch (e: Exception) {
                    logProgress(AnsiColor.YELLOW, "Hooks", "⚠ Could not scan user path '$path' for hooks: ${e.message}")
                }
            }
        }

        // 4. User-global hooks (~/.braidrun/hooks/braidrun-agent/, medium-high priority)
        loadUserGlobalHooks(hookLoader).forEach { hook ->
            hooksMap[hook.name] = hook
            logProgress(AnsiColor.GREEN, "Hooks", "✓ Loaded user-global hook: ${hook.name} [user-global]")
        }

        // 5. Workspace-level hooks (highest priority, override all)
        loadWorkspaceHooks(hookLoader).forEach { hook ->
            hooksMap[hook.name] = hook
            logProgress(AnsiColor.GREEN, "Hooks", "✓ Loaded workspace hook: ${hook.name} [workspace]")
        }

        return hooksMap.values.toList()
    }

    /**
     * Loads hooks from the user-global directory.
     * Default path: ~/.braidrun/hooks/braidrun-agent/. Can be overridden via
     * [SkillsConfiguration.userGlobalHooksDir].
     * Priority: higher than skill-bundled hooks, lower than workspace hooks.
     */
    private fun loadUserGlobalHooks(hookLoader: BraidrunHookLoader): List<BraidrunAgentHook> {
        val globalHooksDir = try {
            if (config.userGlobalHooksDir != null) {
                Paths.get(config.userGlobalHooksDir)
            } else {
                val userHome = System.getProperty("user.home") ?: return emptyList()
                Paths.get(userHome).resolve(".braidrun/hooks/braidrun-agent")
            }
        } catch (e: Exception) {
            return emptyList()
        }
        if (!globalHooksDir.exists() || !globalHooksDir.isDirectory()) return emptyList()

        val hooks = mutableListOf<BraidrunAgentHook>()

        // Direct HOOK.md in user-global hooks dir
        val directHookFile = globalHooksDir.resolve("HOOK.md")
        if (directHookFile.exists() && directHookFile.isRegularFile()) {
            val hook = hookLoader.loadHookFromFile(directHookFile, "user-global", isWorkspaceHook = false)
            if (hook != null) hooks.add(hook)
        }

        // Subdirectories each with a HOOK.md
        globalHooksDir.toFile().listFiles()?.sortedBy { it.name }?.forEach { subDir ->
            if (subDir.isDirectory) {
                val hookFile = subDir.toPath().resolve("HOOK.md")
                if (hookFile.exists() && hookFile.isRegularFile()) {
                    val hook =
                        hookLoader.loadHookFromFile(hookFile, "user-global/${subDir.name}", isWorkspaceHook = false)
                    if (hook != null) hooks.add(hook)
                }
            }
        }

        return hooks
    }

    /**
     * Loads hooks from <workspaceDir>/hooks/braidrun-agent/.
     * Supports a single HOOK.md directly in that directory, or multiple subdirectories
     * each containing their own HOOK.md.
     */
    private fun loadWorkspaceHooks(hookLoader: BraidrunHookLoader): List<BraidrunAgentHook> {
        val workspaceDirStr = config.workspaceDir ?: return emptyList()
        val wsHooksDir = try {
            Paths.get(workspaceDirStr).resolve("hooks/braidrun-agent")
        } catch (e: Exception) {
            return emptyList()
        }
        if (!wsHooksDir.exists() || !wsHooksDir.isDirectory()) return emptyList()

        val hooks = mutableListOf<BraidrunAgentHook>()

        // Direct HOOK.md in workspace hooks dir (single workspace hook)
        val directHookFile = wsHooksDir.resolve("HOOK.md")
        if (directHookFile.exists() && directHookFile.isRegularFile()) {
            val hook = hookLoader.loadHookFromFile(directHookFile, "workspace", isWorkspaceHook = true)
            if (hook != null) hooks.add(hook)
        }

        // Subdirectories each with a HOOK.md (multiple workspace hooks)
        wsHooksDir.toFile().listFiles()?.sortedBy { it.name }?.forEach { subDir ->
            if (subDir.isDirectory) {
                val hookFile = subDir.toPath().resolve("HOOK.md")
                if (hookFile.exists() && hookFile.isRegularFile()) {
                    val hook = hookLoader.loadHookFromFile(hookFile, "workspace/${subDir.name}", isWorkspaceHook = true)
                    if (hook != null) hooks.add(hook)
                }
            }
        }

        return hooks
    }
}

// ============================================================================
// Skill Manager - Manages skill lifecycle and integration
// ============================================================================

/**
 * Manages Claude Skills for an agent.
 * Handles discovery, loading, and integration into agent context.
 * Thread-safe implementation using ConcurrentHashMap.
 */
class SkillManager(
    private val config: SkillsConfiguration,
    private val loader: SkillLoader
) {
    private val loadedSkills = ConcurrentHashMap<String, ClaudeSkill>()
    private val loadedHooks = ConcurrentHashMap<String, BraidrunAgentHook>()

    /**
     * Optional monitoring callback for hook events.
     * When set, hook dispatch/initialization events are forwarded to the workflow monitoring system.
     * Signature: (type: String, summary: String, detail: String?) -> Unit
     */
    var onHookEvent: MonitoringEventCallback? = null

    /**
     * Cache of script execution results; used for AGENT_BOOTSTRAP to avoid running the
     * same script twice. Values are wrapped in [java.util.Optional] because
     * ConcurrentHashMap cannot store nulls — `computeIfAbsent` returning null does NOT
     * create a mapping, so a FAILING script (timeout / non-zero exit returns null) was
     * re-executed by every bootstrap collector (up to 4× ~30 s timeouts per agent
     * build), defeating exactly the guarantee this cache documents.
     */
    private val hookScriptCache = ConcurrentHashMap<String, java.util.Optional<BraidrunHookScriptResult>>()
    private val executor = BraidrunHookExecutor(config)
    private val mcpServerManager = MCPServerManager()

    /**
     * Tracks the background threads that bring MCP servers online so [shutdown] can
     * coordinate with them rather than ignore them. Pre-Phase-10 these were
     * fire-and-forget daemon threads — a fast `shutdown()` racing against an
     * in-flight `startMCPServer` could leak the new server, and exceptions in the
     * thread were swallowed without notifying any external observer.
     */
    private val mcpInitThreads: java.util.concurrent.CopyOnWriteArrayList<Thread> =
        java.util.concurrent.CopyOnWriteArrayList()

    /**
     * Tracks which skills have been activated in the current session.
     * Used to deduplicate activations and avoid the same instructions appearing
     * multiple times in the conversation context.
     */
    private val activatedSkills = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var initialized = false

    /**
     * Initializes the skill manager by discovering and loading all skills.
     * This method is thread-safe and idempotent.
     */
    fun initialize() {
        if (initialized) return

        synchronized(this) {
            if (initialized) return
            if (!config.enabled) {
                loadedSkills.clear()
                loadedHooks.clear()
                hookScriptCache.clear()
                initialized = true
                logProgress(AnsiColor.CYAN, "Skills", "Skills subsystem disabled (enabled=false)")
                return
            }

            logProgress(AnsiColor.CYAN, "Skills", "Initializing Claude Skills...")

            try {
                val skills = loader.loadAllSkills()
                skills.forEach { skill ->
                    loadedSkills[skill.name] = skill
                    // Initialize MCP servers for this skill
                    initializeMCPServersForSkill(skill)
                }

                val hooks = loader.loadAllHooks()
                hooks.forEach { hook ->
                    loadedHooks[hook.name] = hook
                }
                hookScriptCache.clear()

                initialized = true
                logProgress(
                    AnsiColor.GREEN,
                    "Skills",
                    "✓ Initialized ${loadedSkills.size} skill(s), ${loadedHooks.size} hook(s)"
                )
                if (loadedHooks.isNotEmpty()) {
                    val hookNames = loadedHooks.values.map { it.name }
                    onHookEvent?.invoke(
                        "hook_loaded",
                        "🪝 已加载 ${loadedHooks.size} 个钩子",
                        hookNames.joinToString(", ")
                    )
                    // Emit individual hook details
                    loadedHooks.values.forEachIndexed { index, hook ->
                        val hookInfo = buildString {
                            append("名称: ${hook.name}")
                            append(" | 事件: ${hook.events.joinToString(", ") { it.toEventName() }}")
                            if (hook.skillName.isNotBlank()) append(" | 技能: ${hook.skillName}")
                        }
                        onHookEvent?.invoke(
                            "hook_discovered",
                            "🪝 [${index + 1}/${loadedHooks.size}] 发现钩子: ${hook.name}",
                            hookInfo
                        )
                    }
                }
                dispatchHooks(BraidrunHookEvent.GATEWAY_STARTUP)
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.RED,
                    "Skills",
                    "✗ Failed to initialize skills: ${e.message}"
                )
                throw e
            }
        }
    }

    /**
     * Refreshes the skill manager by re-discovering and re-loading all skills.
     * This method is thread-safe.
     */
    fun refresh() {
        synchronized(this) {
            if (!config.enabled) {
                mcpServerManager.stopAllServers()
                loadedSkills.clear()
                loadedHooks.clear()
                hookScriptCache.clear()
                initialized = true
                logProgress(AnsiColor.CYAN, "Skills", "Skills subsystem disabled (enabled=false)")
                return
            }
            logProgress(AnsiColor.CYAN, "Skills", "Refreshing Claude Skills...")
            try {
                // Stop all existing MCP servers before refresh
                mcpServerManager.stopAllServers()

                val skills = loader.loadAllSkills()
                loadedSkills.clear()
                skills.forEach { skill ->
                    loadedSkills[skill.name] = skill
                    // Initialize MCP servers for this skill
                    initializeMCPServersForSkill(skill)
                }
                val hooks = loader.loadAllHooks()
                loadedHooks.clear()
                hooks.forEach { hook ->
                    loadedHooks[hook.name] = hook
                }
                hookScriptCache.clear()
                initialized = true
                logProgress(
                    AnsiColor.GREEN,
                    "Skills",
                    "✓ Refreshed ${loadedSkills.size} skill(s), ${loadedHooks.size} hook(s)"
                )
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.RED,
                    "Skills",
                    "✗ Failed to refresh skills: ${e.message}"
                )
            }
        }
    }

    /**
     * Gets discovery summaries for all loaded skills using structured XML catalog format.
     * Used for progressive disclosure (Tier 1) in the system prompt.
     *
     * The catalog includes name, description, and location for each skill,
     * formatted as structured XML for clear identification. Behavioral instructions
     * are included to tell the model how and when to use skills.
     *
     * When no skills are available, returns empty string — no empty catalog block
     * is shown to avoid confusing the model.
     */
    fun getDiscoverySummaries(): String {
        if (!initialized) initialize()

        if (loadedSkills.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("## Available Skills")
            appendLine()
            // Behavioral instructions telling the model how and when to use skills
            appendLine("The following skills provide specialized instructions for specific tasks.")
            appendLine("When a task matches a skill's description, call the `useSkill` tool")
            appendLine("with the skill's name to load its full instructions.")
            appendLine("When a skill references relative paths, resolve them against the skill's")
            appendLine("directory (the parent of SKILL.md) and use absolute paths in tool calls.")
            appendLine()
            // Structured XML catalog with name, description, and location
            appendLine("<available_skills>")
            loadedSkills.values.sortedBy { it.name }.forEach { skill ->
                appendLine("  <skill>")
                appendLine("    <name>${skill.name}</name>")
                appendLine("    <description>${skill.description}</description>")
                if (skill.location != null) {
                    appendLine("    <location>${skill.location}</location>")
                }
                appendLine("  </skill>")
            }
            appendLine("</available_skills>")
            appendLine()
            appendLine("To use a skill, call the `useSkill` tool with the exact skill name shown above (e.g., useSkill(skillName = \"${loadedSkills.keys.firstOrNull() ?: "example-skill"}\")).")
        }
    }

    /**
     * Gets full content for specified skills.
     * Used when skills are invoked.
     */
    fun getSkillsContent(skillNames: List<String>): String {
        if (!initialized) initialize()

        return buildString {
            skillNames.take(config.maxSkillsPerRequest).forEach { skillName ->
                val skill = loadedSkills[skillName]
                if (skill != null) {
                    appendLine(skill.toFullContent())
                    appendLine()
                } else {
                    appendLine("⚠️ Skill '$skillName' not found")
                    appendLine()
                }
            }
        }
    }

    /**
     * Gets a specific skill by name.
     */
    fun getSkill(name: String): ClaudeSkill? {
        if (!initialized) initialize()
        // Exact match first, then case-insensitive fallback
        return loadedSkills[name]
            ?: loadedSkills.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /**
     * Activates a skill and returns its full content with structured wrapping.
     * Tracks activation to support deduplication — if a skill has already been
     * activated in this session, returns a short notice instead of re-injecting
     * the full instructions.
     *
     * @param name The skill name to activate.
     * @return The skill's full content (first activation) or a dedup notice, or an error message.
     */
    fun activateSkill(name: String): String {
        if (!initialized) initialize()

        val skill = getSkill(name)
            ?: return "Error: Skill '$name' not found. Use listConfiguredSkills() to see available skills."

        // Deduplication: skip re-injection if already activated in this session
        if (!activatedSkills.add(skill.name)) {
            logProgress(
                AnsiColor.CYAN, "Skills",
                "ℹ Skill '${skill.name}' already activated in this session, skipping re-injection"
            )
            return "Skill '${skill.name}' is already active in this session. " +
                    "Its instructions were loaded earlier and are still in context."
        }

        logProgress(AnsiColor.GREEN, "Skills", "✓ Activated skill: ${skill.name}")
        dispatchHooks(BraidrunHookEvent.SKILL_ACTIVATED, additionalConfig = mapOf("skillName" to skill.name))
        return skill.toFullContent()
    }

    /**
     * Checks whether a skill has already been activated in the current session.
     */
    fun isSkillActivated(name: String): Boolean = activatedSkills.contains(name)

    /**
     * Returns the set of skill names that have been activated in the current session.
     */
    fun getActivatedSkills(): Set<String> = activatedSkills.toSet()

    /**
     * Clears the activation tracking (e.g., for a new conversation within the same session).
     */
    fun clearActivationTracking() {
        val previouslyActivated = activatedSkills.toSet()
        activatedSkills.clear()
        if (previouslyActivated.isNotEmpty()) {
            dispatchHooks(
                BraidrunHookEvent.SKILL_DEACTIVATED,
                additionalConfig = mapOf("skills" to previouslyActivated.joinToString(","))
            )
        }
    }

    /**
     * Gets all loaded skills.
     */
    fun getAllSkills(): List<ClaudeSkill> {
        if (!initialized) initialize()
        return loadedSkills.values.sortedBy { it.name }
    }

    /**
     * Detects which skills should be loaded based on user message.
     */
    fun detectRelevantSkills(message: String): List<String> {
        if (!initialized) initialize()

        if (message.isBlank()) return emptyList()

        return loadedSkills.values
            .filter { skill ->
                message.contains(skill.name, ignoreCase = true) ||
                        skill.tags.any { tag -> message.contains(tag, ignoreCase = true) }
            }
            .sortedByDescending { skill ->
                // Prioritize by metadata priority if available
                config.skillsMetadata[skill.name]?.priority ?: 0
            }
            .take(config.maxSkillsPerRequest)
            .map { it.name }
    }

    /**
     * Creates system prompt augmentation with skill information.
     */
    fun createSkillSystemPrompt(): String {
        if (!initialized) initialize()

        return if (config.progressiveDisclosure) {
            getDiscoverySummaries()
        } else {
            // Load all skills fully (not recommended for many skills)
            getSkillsContent(loadedSkills.keys.toList())
        }
    }

    /**
     * Executes [hook]'s handler script if present.
     *
     * @param context The execution context (event name, session key, etc.)
     * @param cache If true, the result is cached and subsequent calls for the same hook name
     *              (regardless of context change!) will return the cached result.
     *              Use only for one-time events like agent:bootstrap.
     */
    private fun getOrExecuteScript(
        hook: BraidrunAgentHook,
        context: BraidrunHookContext,
        cache: Boolean = false
    ): BraidrunHookScriptResult? {
        if (hook.handlerScript == null) return null
        // Respect the security toggle: skip script execution but allow static injection.
        if (!config.hookScriptExecutionEnabled) {
            logProgress(
                AnsiColor.YELLOW,
                "Hooks",
                "⚠ Script execution disabled (hookScriptExecutionEnabled=false) — skipping handler for: ${hook.name}"
            )
            return null
        }

        return if (cache) {
            hookScriptCache.computeIfAbsent(hook.name) {
                java.util.Optional.ofNullable(executor.execute(hook, context))
            }.orElse(null)
        } else {
            executor.execute(hook, context)
        }
    }

    /**
     * Triggers all eligible hooks for the specified [event].
     *
     * Results like `messages` are logged to the operator console.
     * Currently, `injectContent` and `virtualFiles` from non-bootstrap events are logged
     * but not automatically injected into the active agent conversation (as the prompt
     * is already built).
     *
     * @param event The event to trigger.
     * @param sessionKey Identifies the current session.
     * @param additionalConfig Optional extra key-value pairs to pass to the script.
     */
    fun dispatchHooks(
        event: BraidrunHookEvent,
        sessionKey: String = "",
        additionalConfig: Map<String, String> = emptyMap()
    ) {
        if (!config.enabled) return
        if (!config.hooksEnabled) return
        if (!initialized) initialize()

        val eligibleHooks = loadedHooks.values
            .filter { it.events.contains(event) }
            .filter { isHookEligible(it) }

        if (eligibleHooks.isEmpty()) return

        val eventName = event.toEventName()
        onHookEvent?.invoke(
            "hook_dispatching",
            "🪝 分发钩子事件: $eventName (${eligibleHooks.size} 个钩子)",
            eligibleHooks.joinToString(", ") { it.name }
        )
        eligibleHooks.forEach { hook ->
            val context = BraidrunHookContext(
                event = eventName,
                sessionKey = sessionKey,
                workspaceDir = config.workspaceDir ?: "",
                skillName = hook.skillName,
                config = config.hookContextConfig + additionalConfig
            )

            // We don't cache non-bootstrap events as they may happen multiple times
            val result = getOrExecuteScript(hook, context, cache = (event == BraidrunHookEvent.AGENT_BOOTSTRAP))

            if (result != null) {
                result.messages.forEach { msg ->
                    logProgress(AnsiColor.CYAN, "Hooks", "[$eventName] 💬 $msg")
                }
                onHookEvent?.invoke(
                    "hook_executed",
                    "🪝 [$eventName] 钩子 '${hook.name}' 执行完成 (脚本)",
                    buildString {
                        if (result.messages.isNotEmpty()) appendLine("消息: ${result.messages.joinToString("; ")}")
                        if (!result.injectContent.isNullOrBlank()) appendLine("注入内容: ${result.injectContent.take(200)}")
                        if (result.virtualFiles.isNotEmpty()) appendLine("虚拟文件: ${result.virtualFiles.joinToString(", ") { it.path }}")
                    }.trimEnd().ifEmpty { null }
                )
                // Log but don't inject for now (architecture limitation for runtime events)
                if (!result.injectContent.isNullOrBlank()) {
                    logProgress(
                        AnsiColor.CYAN,
                        "Hooks",
                        "[$eventName] ℹ Script requested system prompt injection (ignored at runtime)"
                    )
                }
                if (result.virtualFiles.isNotEmpty()) {
                    logProgress(
                        AnsiColor.CYAN,
                        "Hooks",
                        "[$eventName] ℹ Script requested virtual file injection (ignored at runtime)"
                    )
                }
            } else if (event != BraidrunHookEvent.AGENT_BOOTSTRAP && hook.content.isNotBlank()) {
                // For non-bootstrap, non-script hooks, we just acknowledge the trigger if HOOK.md has content
                logProgress(AnsiColor.CYAN, "Hooks", "[$eventName] ✓ Hook '${hook.name}' triggered (static)")
                onHookEvent?.invoke(
                    "hook_triggered",
                    "🪝 [$eventName] 钩子 '${hook.name}' 已触发 (静态)",
                    null
                )
            }
        }
    }

    /**
     * Collects combined inline text content for all eligible agent:bootstrap hooks.
     *
     * - If the hook has a **handler script**: executes it (cached) and uses [BraidrunHookScriptResult.injectContent].
     * - If the hook has **no script** and no [BraidrunAgentHook.virtualFilePath]: uses the static HOOK.md body.
     * - Script-based hooks that also declare a virtualFilePath are still included here if the script
     *   emits injectContent (the two output modes are not mutually exclusive).
     *
     * Workspace hooks are appended after skill hooks.
     */
    fun collectBootstrapHookContent(): String {
        if (!config.hooksEnabled) return ""
        if (!initialized) initialize()

        val bootstrapHooks = loadedHooks.values
            .filter { it.events.contains(BraidrunHookEvent.AGENT_BOOTSTRAP) }
            .filter { isHookEligible(it) }
            .sortedWith(compareByDescending<BraidrunAgentHook> { it.isWorkspaceHook }.thenBy { it.skillName })

        if (bootstrapHooks.isEmpty()) return ""

        return buildString {
            bootstrapHooks.forEach { hook ->
                val context = BraidrunHookContext(
                    event = "agent:bootstrap",
                    workspaceDir = config.workspaceDir ?: "",
                    skillName = hook.skillName,
                    config = config.hookContextConfig
                )
                val scriptResult = getOrExecuteScript(hook, context, cache = true)
                when {
                    scriptResult != null -> {
                        // Script output takes precedence
                        val txt = scriptResult.injectContent
                        if (!txt.isNullOrBlank()) {
                            appendLine(txt); appendLine()
                        }
                    }

                    hook.virtualFilePath == null -> {
                        // No script, no virtual file → inject body inline (legacy behaviour)
                        appendLine(hook.content); appendLine()
                    }
                    // else: no script, has virtualFilePath → handled by collectBootstrapVirtualFiles()
                }
            }
        }.trimEnd()
    }

    /**
     * Collects virtual bootstrap files for all eligible agent:bootstrap hooks.
     *
     * - If the hook has a **handler script**: executes it (cached) and yields every
     *   [BraidrunHookScriptResult.virtualFiles] entry as a (path, content) pair.
     * - If the hook has **no script** and declares [BraidrunAgentHook.virtualFilePath]: injects the
     *   static HOOK.md body under that path (legacy behaviour).
     */
    fun collectBootstrapVirtualFiles(): List<Pair<String, String>> {
        if (!config.hooksEnabled) return emptyList()
        if (!initialized) initialize()

        val bootstrapHooks = loadedHooks.values
            .filter { it.events.contains(BraidrunHookEvent.AGENT_BOOTSTRAP) }
            .filter { isHookEligible(it) }
            .sortedWith(compareByDescending<BraidrunAgentHook> { it.isWorkspaceHook }.thenBy { it.skillName })

        val result = mutableListOf<Pair<String, String>>()
        bootstrapHooks.forEach { hook ->
            val context = BraidrunHookContext(
                event = "agent:bootstrap",
                workspaceDir = config.workspaceDir ?: "",
                skillName = hook.skillName,
                config = config.hookContextConfig
            )
            val scriptResult = getOrExecuteScript(hook, context, cache = true)
            if (scriptResult != null) {
                scriptResult.virtualFiles.forEach { vf ->
                    logProgress(AnsiColor.GREEN, "Hooks", "✓ Script virtual file: ${vf.path} (from ${hook.skillName})")
                    result.add(vf.path to vf.content)
                }
            } else if (hook.virtualFilePath != null) {
                logProgress(
                    AnsiColor.GREEN,
                    "Hooks",
                    "✓ Virtual bootstrap file: ${hook.virtualFilePath} (from ${hook.skillName})"
                )
                result.add(hook.virtualFilePath to hook.content)
            }
        }
        return result
    }

    /**
     * Returns informational messages emitted by handler scripts during agent:bootstrap.
     * These should be displayed to the operator (e.g. logged to console) but not injected
     * into the system prompt.
     */
    fun collectBootstrapMessages(): List<String> {
        if (!config.hooksEnabled) return emptyList()
        if (!initialized) initialize()

        return loadedHooks.values
            .filter { it.events.contains(BraidrunHookEvent.AGENT_BOOTSTRAP) }
            .filter { isHookEligible(it) }
            .flatMap { hook ->
                val context = BraidrunHookContext(
                    event = "agent:bootstrap",
                    workspaceDir = config.workspaceDir ?: "",
                    skillName = hook.skillName,
                    config = config.hookContextConfig
                )
                getOrExecuteScript(hook, context, cache = true)?.messages ?: emptyList()
            }
    }

    /**
     * Checks whether a hook's eligibility requirements are satisfied on the current system.
     */
    private fun isHookEligible(hook: BraidrunAgentHook): Boolean {
        // `always = true` bypasses all eligibility checks (mirrors OpenClaw's `always` field)
        if (hook.always) return true

        val req = hook.requires ?: return true

        if (req.os.isNotEmpty()) {
            val currentOs = System.getProperty("os.name").lowercase()
            val matches = req.os.any { os -> currentOs.contains(os.lowercase()) }
            if (!matches) {
                logProgress(AnsiColor.YELLOW, "Hooks", "⊘ Skipping '${hook.name}': OS '$currentOs' not in ${req.os}")
                return false
            }
        }

        if (req.bins.isNotEmpty()) {
            val missing = req.bins.filter { !isBinAvailable(it) }
            if (missing.isNotEmpty()) {
                logProgress(AnsiColor.YELLOW, "Hooks", "⊘ Skipping '${hook.name}': missing required binaries: $missing")
                return false
            }
        }

        if (req.anyBins.isNotEmpty()) {
            val anyPresent = req.anyBins.any { isBinAvailable(it) }
            if (!anyPresent) {
                logProgress(
                    AnsiColor.YELLOW,
                    "Hooks",
                    "⊘ Skipping '${hook.name}': none of required binaries found: ${req.anyBins}"
                )
                return false
            }
        }

        if (req.env.isNotEmpty()) {
            val missing = req.env.filter { System.getenv(it) == null }
            if (missing.isNotEmpty()) {
                logProgress(AnsiColor.YELLOW, "Hooks", "⊘ Skipping '${hook.name}': missing required env vars: $missing")
                return false
            }
        }

        if (req.config.isNotEmpty()) {
            val missing = req.config.filter { path ->
                try {
                    !java.io.File(path).exists()
                } catch (_: Exception) {
                    true
                }
            }
            if (missing.isNotEmpty()) {
                logProgress(
                    AnsiColor.YELLOW,
                    "Hooks",
                    "⊘ Skipping '${hook.name}': missing required config paths: $missing"
                )
                return false
            }
        }

        return true
    }

    /**
     * Checks whether a binary is available on the system PATH.
     *
     * Routed through [SubprocessSafety.runProbe] so a wedged `which` (e.g. NFS lookup
     * timing out) can't pin a thread indefinitely. 5 s timeout matches the in-tree
     * convention for interpreter probes.
     */
    private fun isBinAvailable(bin: String): Boolean {
        // Memoized (mirrors EnvCache for interpreters): hook eligibility runs on EVERY
        // dispatch, including per-tool-call and per-streaming-frame events — an uncached
        // probe spawned one `which` subprocess per bin per event (5 s worst case each).
        return binAvailabilityCache.computeIfAbsent(bin) {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val cmd = if (isWindows) listOf("where", bin) else listOf("which", bin)
            SubprocessSafety.runProbe(cmd, timeoutSeconds = 5)
        }
    }

    private val binAvailabilityCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Gets the number of loaded skills.
     */
    fun getSkillCount(): Int {
        if (!initialized) initialize()
        return loadedSkills.size
    }

    /**
     * Checks if a skill with the given name exists.
     */
    fun hasSkill(name: String): Boolean {
        if (!initialized) initialize()
        return loadedSkills.containsKey(name)
    }

    /**
     * Initializes MCP servers for a skill (prepare and start them).
     */
    private fun initializeMCPServersForSkill(skill: ClaudeSkill) {
        if (skill.mcpServers.isEmpty()) return

        // Prefer the skill's ACTUAL directory (parent of its SKILL.md): skills are
        // discovered from multiple scopes (project .claude/skills, user ~/.agents,
        // additional paths) and their directory name may differ from the sanitized
        // skill name — reconstructing "<skillsPath>/<name>" silently skipped MCP
        // init for every skill outside the primary path.
        val skillPath = try {
            skill.baseDirectory?.let { java.nio.file.Paths.get(it) }
                ?: java.nio.file.Paths.get(config.skillsPath).resolve(skill.name)
        } catch (e: Exception) {
            logProgress(AnsiColor.YELLOW, "MCP", "⚠ Cannot resolve skill path for '${skill.name}': ${e.message}")
            return
        }

        if (!skillPath.exists()) {
            logProgress(AnsiColor.YELLOW, "MCP", "⚠ Skill path does not exist: $skillPath")
            return
        }

        skill.mcpServers.forEach { serverName ->
            val serverPath = skillPath.resolve("mcp-servers").resolve(serverName)
            if (!serverPath.exists()) {
                logProgress(AnsiColor.YELLOW, "MCP", "⚠ MCP server directory not found: $serverPath")
                return@forEach
            }

            // Prepare and start server in a background thread.
            // Tracked via [mcpInitThreads] so [shutdown] can wait for them with a
            // bounded join — pre-Phase-10 these were fire-and-forget and a fast
            // shutdown could race the start, leaking the new MCPServerInfo.
            val initThread = Thread {
                try {
                    kotlinx.coroutines.runBlocking {
                        val prepared = mcpServerManager.prepareMCPServer(serverPath, skill.name)
                        if (prepared) {
                            mcpServerManager.startMCPServer(serverPath, skill.name)
                        }
                    }
                } catch (e: Exception) {
                    logProgress(AnsiColor.RED, "MCP", "✗ Failed to initialize MCP server '$serverName': ${e.message}")
                }
            }
            initThread.isDaemon = true
            initThread.name = "MCP-Init-${skill.name}-$serverName"
            mcpInitThreads.add(initThread)
            // Drop the reference once the thread is done so we don't accumulate
            // dead references across many skill refreshes.
            val cleanup = Thread {
                try {
                    initThread.join()
                } catch (_: InterruptedException) {
                    // shutdown path
                } finally {
                    mcpInitThreads.remove(initThread)
                }
            }
            cleanup.isDaemon = true
            cleanup.name = "MCP-Init-Cleanup-${skill.name}-$serverName"
            cleanup.start()
            initThread.start()
        }
    }

    /**
     * Cleans up MCP servers when shutting down or refreshing.
     */
    fun shutdown() {
        logProgress(AnsiColor.CYAN, "Skills", "Shutting down skill manager...")
        dispatchHooks(BraidrunHookEvent.AGENT_SHUTDOWN)
        // Wait briefly for in-flight MCP-Init threads so we don't race the
        // mcpServerManager.stopAllServers() teardown. We use a short bounded join
        // because skill init can legitimately span minutes (pip install in
        // particular); in shutdown contexts we'd rather move on than block JVM exit.
        val pendingInits = mcpInitThreads.toList()
        if (pendingInits.isNotEmpty()) {
            logProgress(
                AnsiColor.YELLOW,
                "Skills",
                "Waiting up to 3s for ${pendingInits.size} in-flight MCP init thread(s)…"
            )
            val deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos()
            for (t in pendingInits) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                if (remaining == 0L) break
                try {
                    t.join(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        mcpServerManager.stopAllServers()
        loadedSkills.clear()
        loadedHooks.clear()
        hookScriptCache.clear()
        initialized = false
        logProgress(AnsiColor.GREEN, "Skills", "✓ Skill manager shut down")
    }

    /**
     * Gets the MCP server manager for external access.
     */
    fun getMCPServerManager(): MCPServerManager = mcpServerManager
}

// Hook models, loader, and executor have been extracted to AgentHooks.kt

// ============================================================================
// Helper Functions for Integration
// ============================================================================

/**
 * Creates a SkillManager from configuration parameters.
 * Returns null if no skills configuration is provided.
 */
fun createSkillManager(parameters: List<ConfigurationParameter>): SkillManager? {
    val config = parameters.parameter<SkillsConfiguration?>("skills_config", null)
        ?: if (parameters.parameter("tool_set", emptySet<String>()).contains("skill_tools")) {
            val defaultCacheDir =
                parameters.parameter("clawhub_cache_dir", System.getProperty("user.home") + "/.braidrun/skills-cache")
            SkillsConfiguration(skillsPath = defaultCacheDir)
        } else {
            return null
        }

    if (!config.enabled) return null

    val skillsPath = try {
        Paths.get(config.skillsPath)
    } catch (e: Exception) {
        logProgress(
            AnsiColor.RED,
            "Skills",
            "Invalid skills path: ${config.skillsPath} (${e.message})"
        )
        return null
    }

    // Inject workspace_dir from parameters if not already set in config.
    // Also build a config snapshot for handler scripts.
    val resolvedWorkspaceDir = config.workspaceDir
        ?: parameters.parameter<String?>("workspace_dir", null)

    val hookContextConfig = buildMap<String, String> {
        resolvedWorkspaceDir?.let { put("workspace_dir", it) }
        parameters.parameter<String?>("model", null)?.let { put("model", it) }
        parameters.parameter<String?>("agent_name", null)?.let { put("agent_name", it) }
        parameters.parameter<String?>("session_key", null)?.let { put("session_key", it) }
        parameters.parameter<String?>("clawhub_cache_dir", null)?.let { put("clawhub_cache_dir", it) }
    }

    val effectiveConfig = config.copy(
        workspaceDir = resolvedWorkspaceDir,
        hookContextConfig = hookContextConfig
    )

    val loader = SkillLoader(skillsPath, effectiveConfig)
    return SkillManager(effectiveConfig, loader)
}

/**
 * Extracts skills configuration from parameters.
 */
fun List<ConfigurationParameter>.getSkillsConfig(): SkillsConfiguration? {
    return parameter<SkillsConfiguration?>("skills_config", null)
}
