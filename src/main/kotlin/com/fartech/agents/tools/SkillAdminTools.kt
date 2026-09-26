package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.commons.SkillInstallHygiene
import com.fartech.agents.commons.SkillLoader
import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.commons.SubprocessSafety
import com.fartech.agents.commons.logProgress
import com.fartech.agents.workflow.WorkflowHostPolicy
import com.fartech.ftapp2.commonsKt.AnsiColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.nio.file.Files

/**
 * Model-facing skill tools that change what is installed on the host: ClawHub and Git
 * downloads, deleting cache entries, and reloading the skill manager.
 *
 * Split out of [SkillTools] so a multi-tenant host can withhold them: the `skill_tools`
 * group registers this set only while [WorkflowHostPolicy.allowsSkillAdminTools], and every
 * call re-checks the policy, so an instance built before the host latched it refuses too.
 */
@LLMDescription("Toolset for installing, removing and reloading Claude skills on this machine")
class SkillAdminTools(private val skills: SkillTools) : ToolSet {

    companion object {
        /**
         * Wall-clock cap for `git clone --depth 1` of a single skill. 5 minutes is generous
         * for even a multi-MB repo over a slow link; longer hangs are a stuck server.
         */
        internal const val GIT_CLONE_TIMEOUT_SECONDS: Long = 300L

        /**
         * Only `https://` remotes: no local paths or `file://` (host file disclosure), no
         * `ssh`/scp-style or `ext::` transports, and nothing git could parse as an option.
         */
        internal fun requireHttpsRepositoryUrl(repositoryUrl: String): String {
            val url = repositoryUrl.trim()
            require(url.isNotEmpty()) { "repositoryUrl is required" }
            require(!url.startsWith("-")) { "repositoryUrl must not start with '-'" }
            require(url.none { it.isWhitespace() || it.isISOControl() }) {
                "repositoryUrl must not contain whitespace or control characters"
            }
            require(url.startsWith("https://", ignoreCase = true)) {
                "Only https:// Git repository URLs are allowed: '$url'"
            }
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "repositoryUrl is not a valid https URL: '$url'"
            }
            return url
        }

        /**
         * `--` ends option parsing, so the URL can never be read as a git option.
         * `core.symlinks=false` checks repository links out as plain files (a committed link to
         * `~/.ssh/id_rsa` must not become readable skill content); the protocol allowlist
         * keeps redirects and submodules on https, and submodules are not fetched at all.
         */
        internal fun gitCloneCommand(repositoryUrl: String, destination: File): List<String> =
            listOf(
                "git",
                "-c", "core.symlinks=false",
                "-c", "protocol.allow=never",
                "-c", "protocol.https.allow=always",
                "clone", "--depth", "1", "--no-recurse-submodules",
                "--", repositoryUrl, destination.absolutePath
            )

        /** Resolves [subdirectory] inside [cloneRoot]; `..`, absolute paths and escaping links are refused. */
        internal fun resolveSkillSubdirectory(cloneRoot: File, subdirectory: String): File {
            val relative = subdirectory.trim()
            if (relative.isEmpty()) return cloneRoot
            require(!File(relative).isAbsolute && !relative.startsWith("/") && !relative.startsWith("\\")) {
                "subdirectory must be relative to the repository root: '$subdirectory'"
            }
            val candidate = File(cloneRoot, relative)
            require(SkillInstallHygiene.isStrictlyInside(candidate, cloneRoot)) {
                "subdirectory escapes the cloned repository: '$subdirectory'"
            }
            return candidate.canonicalFile
        }

        /**
         * A cache key names exactly one entry directly inside the cache directory. Returns the
         * on-disk directory name, or null when the key could address anything else.
         */
        internal fun normalizeCacheKey(cacheKey: String): String? {
            val key = cacheKey.trim()
            if (key.isEmpty() || key == "." || key == "..") return null
            if (key.contains('/') || key.contains('\\') || key.any { it.isISOControl() }) return null
            return key.replace(":", "_")
        }

        private fun sanitizeRepoName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifBlank { "repo" }
    }

    /** Null when allowed; otherwise the explicit refusal returned (or thrown) to the model. */
    private fun hostPolicyRefusal(tool: String): String? =
        if (WorkflowHostPolicy.allowsSkillAdminTools) {
            null
        } else {
            "Error: $tool is disabled by host policy. Installing, removing or reloading skills " +
                "is managed by the host, not by agents."
        }

    /**
     * Download a skill from ClawHub registry with local caching support.
     */
    @Tool
    @LLMDescription("Download a Claude skill from ClawHub registry by slug and extract it to a local directory. Uses local cache if skill version already exists.")
    suspend fun downloadSkillFromClawHub(
        @LLMDescription("unique slug identifier of the skill to download")
        slug: String,
        @LLMDescription("optional version to download (e.g., '1.2.3'); if not specified, downloads latest version")
        version: String? = null,
        @LLMDescription("optional tag to download (e.g., 'latest'); ignored if version is specified")
        tag: String? = null,
        @LLMDescription("if true, always re-download even if cached version exists; default is false")
        forceDownload: Boolean = false
    ): SkillReference {
        hostPolicyRefusal("downloadSkillFromClawHub")?.let { throw IllegalStateException(it) }
        return skills.downloadSkillFromClawHub(slug, version, tag, forceDownload)
    }

    /**
     * Download a skill from a Git repository URL.
     */
    @Tool
    @LLMDescription("Download a Claude skill from an https:// Git repository URL (GitHub, GitLab, etc.) into the local skills cache")
    suspend fun downloadSkillFromGit(
        @LLMDescription("https:// Git repository URL containing the skill (e.g., https://github.com/user/skill-repo.git)")
        repositoryUrl: String,
        @LLMDescription("optional subdirectory within the repo where SKILL.md is located; leave empty if SKILL.md is in repo root")
        subdirectory: String = ""
    ): SkillReference = withContext(Dispatchers.IO) {
        hostPolicyRefusal("downloadSkillFromGit")?.let { throw IllegalStateException(it) }
        logProgress(
            AnsiColor.CYAN,
            "GitDownload",
            "Preparing to download skill from Git: $repositoryUrl (subdirectory: '$subdirectory')"
        )
        skills.emitEvent("skill_download_preparing", "📦 准备从 Git 下载技能", repositoryUrl)
        val url = try {
            requireHttpsRepositoryUrl(repositoryUrl)
        } catch (e: IllegalArgumentException) {
            skills.emitEvent("skill_download_failed", "❌ 从 Git 下载技能失败", e.message)
            throw e
        }
        val repoName = sanitizeRepoName(url.substringAfterLast("/").removeSuffix(".git"))
        val repoHash = url.hashCode().toString(16)
        val skillPathRoot = File(skills.cacheDirectory, "git_${repoName}_$repoHash")

        if (skillPathRoot.exists()) {
            SkillInstallHygiene.deleteRecursivelyNoFollow(skillPathRoot)
        }
        skillPathRoot.mkdirs()

        try {
            logProgress(AnsiColor.CYAN, "GitDownload", "Downloading skill from: $url")

            // 🔒 Security: argv without a shell (no `cmd /c` on Windows either), `--` before the
            // URL, 5-minute cap and a drained, bounded output pipe (SubprocessSafety).
            val cloneResult = SubprocessSafety.runCapturedWithTimeout(
                command = gitCloneCommand(url, skillPathRoot),
                timeoutSeconds = GIT_CLONE_TIMEOUT_SECONDS,
                maxOutputBytes = 1 * 1024 * 1024,
                env = mapOf("GIT_TERMINAL_PROMPT" to "0")
            )

            if (cloneResult.timedOut) {
                throw IllegalStateException(
                    "git clone for '$url' exceeded ${GIT_CLONE_TIMEOUT_SECONDS}s timeout — aborted"
                )
            }
            val exitCode = cloneResult.exitCode ?: -1
            if (exitCode != 0) {
                throw IllegalStateException("Failed to clone repository: ${cloneResult.output}")
            }

            val skillPath = resolveSkillSubdirectory(skillPathRoot, subdirectory)

            // Verify SKILL.md exists (case-insensitive to handle varying naming conventions)
            val skillFile = skillPath.listFiles()?.firstOrNull {
                it.isFile && it.name.equals("SKILL.md", ignoreCase = true)
            }
            if (skillFile == null) {
                throw IllegalArgumentException(
                    "SKILL.md not found in ${skillPath.absolutePath}. " +
                        "Please check the repository URL and subdirectory."
                )
            }
            require(!Files.isSymbolicLink(skillFile.toPath())) {
                "SKILL.md in ${skillPath.absolutePath} is a symbolic link; refusing to load it"
            }

            logProgress(
                AnsiColor.GREEN,
                "GitDownload",
                "✓ Successfully downloaded skill to: ${skillPath.absolutePath}"
            )
            skills.emitEvent("skill_download_completed", "✅ 从 Git 下载技能完成", skillPath.absolutePath)

            // Refresh skill manager to include new skill
            skills.refreshSkillManager()

            val loader = SkillLoader(skillPath.toPath(), SkillsConfiguration(skillsPath = skillPath.absolutePath))
            val loadedSkill = loader.loadSkill(skillPath.toPath())

            SkillReference(
                skillPath = skillPath.absolutePath,
                skillName = loadedSkill?.name
            )
        } catch (e: Exception) {
            // A clone may contain symbolic links; never follow them while cleaning up.
            SkillInstallHygiene.deleteRecursivelyNoFollow(skillPathRoot)
            logProgress(
                AnsiColor.RED,
                "GitDownload",
                "✗ Failed to download skill: ${e.message}"
            )
            skills.emitEvent("skill_download_failed", "❌ 从 Git 下载技能失败", e.message)
            throw e
        }
    }

    /**
     * Remove one entry from the local skill cache.
     */
    @Tool
    @LLMDescription("Remove one cached skill from the local skills cache by its cache key (as shown by listCachedSkills, e.g. 'slug@version')")
    fun clearSkillCache(
        @LLMDescription("cache key of the single cached skill to remove, e.g. 'slug@version'")
        cacheKey: String
    ): String {
        hostPolicyRefusal("clearSkillCache")?.let { return it }
        // Deleting the whole cache (or anything outside it) is never an agent decision.
        if (cacheKey.isBlank()) {
            return "Error: cacheKey is required. Clearing the whole skill cache is not available to agents; " +
                "name one entry from listCachedSkills()."
        }
        val normalizedKey = normalizeCacheKey(cacheKey)
            ?: return "Error: invalid cacheKey '$cacheKey'. It must name one cached skill, e.g. 'slug@version'."

        skills.emitEvent("skill_cache_clear_starting", "🗑️ 清除技能缓存", cacheKey)
        val cacheDir = File(skills.cacheDirectory)
        val canonicalCacheDir = runCatching { cacheDir.canonicalFile }.getOrNull()
            ?: return "Cache directory does not exist: ${cacheDir.absolutePath}"

        val exact = File(cacheDir, normalizedKey)
        val targetDir = if (exact.exists()) {
            exact
        } else {
            cacheDir.listFiles { f -> f.isDirectory }?.firstOrNull { dir ->
                // Match by directory name prefix (slug@version or slug-version)
                dir.name.startsWith("$normalizedKey@") ||
                    dir.name.startsWith("$normalizedKey-") ||
                    // Match by slug field in _meta.json
                    runCatching {
                        val meta = File(dir, "_meta.json").takeIf { it.exists() }?.readText()
                        meta != null && Json.parseToJsonElement(meta).jsonObject["slug"]?.jsonPrimitive?.content == cacheKey
                    }.getOrDefault(false) ||
                    // Match by name field in SKILL.md front-matter
                    runCatching {
                        val skillMd = File(dir, "SKILL.md").takeIf { it.exists() }?.readText()
                        skillMd != null && Regex("""^name:\s*(.+)$""", RegexOption.MULTILINE)
                            .find(skillMd)?.groupValues?.get(1)?.trim() == cacheKey
                    }.getOrDefault(false)
            }
        } ?: return "Cached skill not found: $cacheKey"

        // The resolved entry must be a direct child of the cache directory; a link that
        // points elsewhere resolves outside it and is refused.
        val canonicalTarget = runCatching { targetDir.canonicalFile }.getOrNull()
        if (canonicalTarget == null || canonicalTarget.parentFile != canonicalCacheDir) {
            return "Error: cache entry '$cacheKey' resolves outside the skills cache; refusing to delete it."
        }

        val result = if (SkillInstallHygiene.deleteRecursivelyNoFollow(targetDir)) {
            "✓ Cleared cached skill: $cacheKey"
        } else {
            "✗ Failed to clear cached skill: $cacheKey"
        }

        // Refresh skill manager to reflect deleted skills
        skills.refreshSkillManager()
        skills.emitEvent("skill_cache_clear_completed", "✅ 技能缓存清除完成")
        return result
    }

    /**
     * Refresh the skill manager to re-discover and re-load all skills from the configured directory.
     */
    @Tool
    @LLMDescription("Refresh the skill manager to re-discover and re-load all skills from the configured directory")
    fun refreshSkills(): String {
        hostPolicyRefusal("refreshSkills")?.let { return it }
        logProgress(
            AnsiColor.CYAN,
            "SkillTools",
            "Refreshing skill manager and re-loading skills from configured directory"
        )
        skills.emitEvent("skill_install_starting", "♻️ 刷新技能安装状态")
        val manager = skills.currentSkillManager()
            ?: return "Error: Skill manager not available or no skills directory configured."

        manager.refresh()
        return "✓ Successfully refreshed ${manager.getSkillCount()} skill(s).".also {
            skills.emitEvent("skill_install_completed", "✅ 技能刷新完成", it)
        }
    }
}
