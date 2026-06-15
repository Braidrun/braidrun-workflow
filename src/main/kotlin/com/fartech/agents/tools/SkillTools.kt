package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.commons.*
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal fun normalizeClawHubVersion(value: String?): String? {
    return value
        ?.trim()
        ?.takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals("undefined", ignoreCase = true) &&
                !it.equals("unknown", ignoreCase = true)
        }
}

private fun JsonElement?.versionContentOrNull(): String? {
    return normalizeClawHubVersion((this as? JsonPrimitive)?.contentOrNull)
}

@Serializable
@LLMDescription("Reference to a downloaded or available Claude skill")
data class SkillReference(
    @property:LLMDescription("local path to the skill directory (must contain SKILL.md)")
    val skillPath: String,
    @property:LLMDescription("optional skill name; if not provided, will be read from SKILL.md")
    val skillName: String? = null,
    @property:LLMDescription("slug of the skill in ClawHub registry, if downloaded from ClawHub")
    val slug: String? = null,
    @property:LLMDescription("version of the skill, if downloaded from ClawHub")
    val version: String? = null
)

@Serializable
@LLMDescription("Search result from ClawHub skill registry")
data class ClawHubSkillSearchResult(
    @property:LLMDescription("relevance score of the search result")
    val score: Double,
    @property:LLMDescription("unique slug identifier for the skill")
    val slug: String,
    @property:LLMDescription("display name of the skill")
    val displayName: String,
    @property:LLMDescription("short summary description of the skill")
    val summary: String,
    @property:LLMDescription("latest version number")
    val version: String,
    @property:LLMDescription("last update timestamp in milliseconds")
    val updatedAt: Long
)

@Serializable
@LLMDescription("Detailed skill information from ClawHub")
data class ClawHubSkillDetails(
    @property:LLMDescription("unique slug identifier")
    val slug: String,
    @property:LLMDescription("display name")
    val displayName: String,
    @property:LLMDescription("summary description")
    val summary: String,
    @property:LLMDescription("latest version")
    val version: String,
    @property:LLMDescription("owner handle")
    val ownerHandle: String?,
    @property:LLMDescription("OS restrictions (e.g., ['macos', 'linux'])")
    val os: List<String>?,
    @property:LLMDescription("download count")
    val downloads: Long?,
    @property:LLMDescription("star count")
    val stars: Long?
)

@LLMDescription("Toolset for searching and downloading Claude skills from ClawHub registry")
class SkillTools(
    private val parameters: List<ConfigurationParameter>,
    private val httpAccess: HttpAccess,
    private var skillManager: SkillManager? = null,
    private val onMonitorEvent: MonitoringEventCallback? = null,
    private val onHookEvent: MonitoringEventCallback? = null
) : ToolSet {

    private val clawhubBaseUrl: String = parameters.parameter("clawhub_base_url", "https://clawhub.ai")
    private val skillsConfig: SkillsConfiguration? = parameters.getSkillsConfig()
    private val skillsCacheDir: String = skillsConfig?.skillsPath
        ?: parameters.parameter("clawhub_cache_dir", System.getProperty("user.home") + "/.braidrun/skills-cache")

    init {
        if (skillsSubsystemDisabled(parameters)) {
            logProgress(AnsiColor.CYAN, "SkillTools", "Skills subsystem disabled; skipping SkillTools initialization")
            emit("skill_disabled", "🧩 技能子系统已禁用")
            skillManager = null
        } else {
            // Ensure cache directory exists
            File(skillsCacheDir).mkdirs()

            // Auto-initialize: discover and load all installed skills at startup
            // Skills are first-class citizens — they should be available immediately
            try {
                emit("skill_initializing", "🧩 技能子系统初始化中...", "技能缓存目录: $skillsCacheDir")
                logProgress(AnsiColor.CYAN, "SkillTools", "Initializing skill subsystem, cache dir: $skillsCacheDir")

                var manager = getManager()
                // Wire hook event callback to the skill manager
                manager?.onHookEvent = onHookEvent
                // Fallback: if createSkillManager returned null (e.g. no skills_config and tool_set
                // deserialization failed), create a default SkillManager using skillsCacheDir
                if (manager == null) {
                    logProgress(AnsiColor.YELLOW, "SkillTools", "SkillManager was null, creating fallback with skillsCacheDir=$skillsCacheDir")
                    emit("skill_initializing", "🧩 使用默认配置初始化技能管理器")
                    val fallbackConfig = SkillsConfiguration(skillsPath = skillsCacheDir)
                    val loader = SkillLoader(java.nio.file.Paths.get(skillsCacheDir), fallbackConfig)
                    skillManager = SkillManager(fallbackConfig, loader)
                    manager = skillManager
                    manager?.onHookEvent = onHookEvent
                }
                if (manager != null) {
                    emit("skill_initializing", "🧩 正在扫描已安装技能...")
                    manager.initialize()
                    val skillCount = manager.getSkillCount()
                    val allSkills = manager.getAllSkills()
                    val skillNames = allSkills.map { it.name }
                    if (skillCount > 0) {
                        // Emit individual skill details
                        allSkills.forEachIndexed { index, skill ->
                            val skillInfo = buildString {
                                append("名称: ${skill.name}")
                                if (skill.description.isNotBlank()) append(" | 描述: ${skill.description.take(100)}")
                            }
                            emit("skill_discovered", "🧩 [${index + 1}/$skillCount] 发现技能: ${skill.name}", skillInfo)
                        }
                        emit("skill_loaded", "🧩 已加载 $skillCount 个技能", skillNames.joinToString(", "))
                        logProgress(AnsiColor.GREEN, "SkillTools", "✓ Auto-loaded $skillCount skill(s): ${skillNames.joinToString(", ")}")
                    } else {
                        emit("skill_loaded", "🧩 技能子系统就绪（暂无已安装技能）")
                        logProgress(AnsiColor.CYAN, "SkillTools", "Skill subsystem ready, no skills installed yet")
                    }
                }
            } catch (e: Exception) {
                logProgress(AnsiColor.YELLOW, "SkillTools", "⚠ Failed to auto-initialize skills: ${e.message}")
                emit("skill_load_failed", "⚠️ 技能自动加载失败: ${e.message}")
            }
        }
    }

    companion object {
        /**
         * 全局下载锁注册表：每个 slug 对应一把锁
         * 防止多个工作流并发下载同一个 skill 时文件损坏
         */
        private val downloadLocks = ConcurrentHashMap<String, Mutex>()

        /**
         * Wall-clock cap for `git clone --depth 1` of a single skill. 5 minutes is generous
         * for even a multi-MB repo over a slow link; longer hangs are a stuck server.
         */
        internal const val GIT_CLONE_TIMEOUT_SECONDS: Long = 300L
    }

    private fun getManager(): SkillManager? {
        if (skillManager == null) {
            skillManager = createSkillManager(parameters)
        }
        return skillManager
    }

    private fun emit(type: String, summary: String, detail: String? = null) {
        onMonitorEvent?.invoke(type, summary, detail)
    }

    /**
     * Search for Claude skills in the ClawHub registry.
     */
    @Tool
    @LLMDescription("Search for Claude skills in the ClawHub registry by query string")
    suspend fun searchSkills(
        @LLMDescription("search query to find relevant skills")
        query: String,
        @LLMDescription("maximum number of results to return (1-200, default 10)")
        limit: Int = 10,
        @LLMDescription("if true, only return highlighted/featured skills")
        highlightedOnly: Boolean = false
    ): List<ClawHubSkillSearchResult> = withContext(Dispatchers.IO) {
        logProgress(AnsiColor.CYAN, "ClawHub", "Searching for skills: $query")
        emit("skill_search_starting", "🔎 搜索技能: $query")
        try {

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val highlightedParam = if (highlightedOnly) "&highlightedOnly=true" else ""
            val url = "$clawhubBaseUrl/api/v1/search?q=$encodedQuery&limit=$limit$highlightedParam"

            val json = httpAccess.get<JsonObject>(url)
            val results = json["results"]?.jsonArray ?: emptyList()

            val skills = results.map { element ->
                val obj = element.jsonObject
                ClawHubSkillSearchResult(
                    score = obj["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    slug = obj["slug"]?.jsonPrimitive?.content ?: "",
                    displayName = obj["displayName"]?.jsonPrimitive?.content ?: "",
                    summary = obj["summary"]?.jsonPrimitive?.content ?: "",
                    version = obj["version"].versionContentOrNull() ?: "",
                    updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            }

            logProgress(
                AnsiColor.GREEN,
                "ClawHub",
                "✓ Found ${skills.size} skill(s) matching '$query'"
            )
            emit("skill_search_completed", "✅ 搜索技能完成: $query", "命中 ${skills.size} 个结果")

            skills
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "ClawHub", "✗ Search failed: ${e.message}")
            emit("skill_search_failed", "❌ 搜索技能失败: $query", e.message)
            throw IllegalStateException("Failed to search ClawHub: ${e.message}", e)
        }
    }

    /**
     * Get detailed information about a specific skill from ClawHub.
     */
    @Tool
    @LLMDescription("Get detailed information about a specific skill from ClawHub registry by slug")
    suspend fun getSkillDetails(
        @LLMDescription("unique slug identifier of the skill")
        slug: String
    ): ClawHubSkillDetails {
        logProgress(AnsiColor.CYAN, "ClawHub", "Fetching details for skill: $slug")
        emit("skill_discovery_starting", "🔍 获取技能详情: $slug")
        try {

            val url = "$clawhubBaseUrl/api/v1/skills/$slug"
            val json = httpAccess.get<JsonObject>(url)

            val skill = json["skill"]?.takeIf { it !is JsonNull }?.jsonObject
                ?: throw IllegalArgumentException("Skill not found")
            val latestVersion = json["latestVersion"]?.takeIf { it !is JsonNull }?.jsonObject
            val metadata = json["metadata"]?.takeIf { it !is JsonNull }?.jsonObject
            val owner = json["owner"]?.takeIf { it !is JsonNull }?.jsonObject
            val stats = skill["stats"]?.takeIf { it !is JsonNull }?.jsonObject

            val details = ClawHubSkillDetails(
                slug = skill["slug"]?.jsonPrimitive?.content ?: slug,
                displayName = skill["displayName"]?.jsonPrimitive?.content ?: slug,
                summary = skill["summary"]?.jsonPrimitive?.content ?: "",
                version = latestVersion?.get("version").versionContentOrNull() ?: "unknown",
                ownerHandle = owner?.get("handle")?.jsonPrimitive?.content,
                os = metadata?.get("os")?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
                downloads = stats?.get("downloads")?.jsonPrimitive?.longOrNull,
                stars = stats?.get("stars")?.jsonPrimitive?.longOrNull
            )

            logProgress(AnsiColor.GREEN, "ClawHub", "✓ Retrieved details for: ${details.displayName}")
            emit(
                "skill_discovery_completed",
                "✅ 获取技能详情完成: ${details.displayName}",
                "version=${details.version}"
            )

            return details
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "ClawHub", "✗ Failed to get skill details: ${e.message}")
            emit("skill_discovery_failed", "❌ 获取技能详情失败: $slug", e.message)
            throw IllegalStateException("Failed to get skill details from ClawHub: ${e.message}", e)
        }
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
    ): SkillReference = withContext(Dispatchers.IO) {
        logProgress(
            AnsiColor.CYAN,
            "ClawHub",
            "Preparing to download skill: $slug (version: ${version ?: "latest"}, tag: ${tag ?: "none"})"
        )
        emit(
            "skill_download_preparing",
            "📦 准备下载技能: $slug",
            "version=${version ?: "latest"}, tag=${tag ?: "none"}"
        )
        // Parse slug: handle case where LLM passes "slug@version" as a single slug parameter
        val (parsedSlug, embeddedVersion) = if (slug.contains("@")) {
            val parts = slug.split("@", limit = 2)
            parts[0].lowercase() to parts[1]
        } else {
            slug.lowercase() to null
        }
        // Treat "latest" as a tag, not a specific version — so it resolves via getSkillDetails
        val rawVersion = version ?: embeddedVersion
        val resolvedVersion = if (rawVersion == "latest") null else rawVersion
        val resolvedTag = if (resolvedVersion == null) (if (rawVersion == "latest") "latest" else tag) else null

        // Determine actual version if not specified
        val actualVersion = resolvedVersion ?: run {
            // Fetch real version from skill details (covers no version, or version="latest")
            val details = getSkillDetails(parsedSlug)
            details.version
        }

        // 使用 per-slug 锁防止并发下载同一个 skill 导致文件损坏
        val slugLock = downloadLocks.getOrPut("$parsedSlug@$actualVersion") { Mutex() }

        return@withContext slugLock.withLock {
            // Check cache first — support both "slug@version" and "slug-version" directory naming conventions
            // to avoid duplicate downloads when the two styles coexist on disk.
            fun toCacheDirName(slug: String, ver: String, sep: Char) =
                "${slug}${sep}${ver}".replace("/", "_").replace(":", "_")

            val cacheKeyAt = toCacheDirName(parsedSlug, actualVersion, '@')
            val cacheKeyDash = toCacheDirName(parsedSlug, actualVersion, '-')
            val cachedDirAt = File(skillsCacheDir, cacheKeyAt)
            val cachedDirDash = File(skillsCacheDir, cacheKeyDash)

            fun File.hasSkillMd() = walkTopDown().any { it.isFile && (it.name == "SKILL.md" || it.name == "skill.md") }

            // Prefer the canonical "@" format; fall back to "-" format if it already exists
            val existingCachedDir = when {
                cachedDirAt.exists() && cachedDirAt.hasSkillMd() -> cachedDirAt
                cachedDirDash.exists() && cachedDirDash.hasSkillMd() -> cachedDirDash
                else -> null
            }
            // The directory used for new downloads always uses the canonical "@" format
            val cachedDir = cachedDirAt

            if (!forceDownload && existingCachedDir != null) {
                // Auto-upgrade: when enabled, check ClawHub for a newer version before using the cache
                if (skillsConfig?.autoUpgrade == true) {
                    val latestVersion = try {
                        getSkillDetails(parsedSlug).version
                    } catch (e: Exception) {
                        logProgress(AnsiColor.YELLOW, "ClawHub", "⚠ Could not check for updates: ${e.message}")
                        null
                    }
                    if (latestVersion != null && latestVersion != actualVersion) {
                        logProgress(
                            AnsiColor.CYAN,
                            "ClawHub",
                            "↑ Upgrading '$parsedSlug' from '$actualVersion' to '$latestVersion'"
                        )
                        // Delegate to a fresh download call with the newer version
                        return@withLock downloadSkillFromClawHub(
                            slug = parsedSlug,
                            version = latestVersion,
                            tag = null,
                            forceDownload = true
                        )
                    }
                }

                logProgress(
                    AnsiColor.GREEN,
                    "ClawHub",
                    "✓ Using cached skill '$parsedSlug' version '$actualVersion' from: ${existingCachedDir.absolutePath}"
                )
                emit(
                    "skill_install_completed",
                    "✅ 使用已安装技能缓存: $parsedSlug@$actualVersion",
                    existingCachedDir.absolutePath
                )

                return@withLock SkillReference(
                    skillPath = existingCachedDir.absolutePath,
                    skillName = null,
                    slug = parsedSlug,
                    version = actualVersion
                )
            }

            // Download to cache directory
            try {
                logProgress(AnsiColor.CYAN, "ClawHub", "Downloading skill: $parsedSlug@$actualVersion")
                emit("skill_download_starting", "⬇️ 下载技能: $parsedSlug@$actualVersion")

                // Build download URL
                val versionParam = if (resolvedVersion != null) {
                    "?version=${URLEncoder.encode(resolvedVersion, "UTF-8")}"
                } else if (resolvedTag != null) {
                    "?tag=${URLEncoder.encode(resolvedTag, "UTF-8")}"
                } else {
                    "" // Latest version
                }

                val downloadUrl =
                    "$clawhubBaseUrl/api/v1/download?slug=${URLEncoder.encode(parsedSlug, "UTF-8")}$versionParam"

                // Create cache directory
                if (cachedDir.exists()) {
                    cachedDir.deleteRecursively()
                }
                cachedDir.mkdirs()

                // Download the zip file as byte array
                val zipBytes: ByteArray = httpAccess.get(downloadUrl)

                // Extract zip to cache directory
                ZipInputStream(zipBytes.inputStream()).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val entryPath = entry.name
                        // 🔒 Security: Check for path traversal (Zip Slip vulnerability)
                        val file = File(cachedDir, entryPath).canonicalFile
                        val cachedDirCanonical = cachedDir.canonicalPath
                        val cachedDirPath =
                            if (cachedDirCanonical.endsWith(File.separator)) cachedDirCanonical else cachedDirCanonical + File.separator
                        if (!file.canonicalPath.startsWith(cachedDirPath) && file.canonicalPath != cachedDirCanonical) {
                            throw SecurityException("Potential path traversal attack detected in zip entry: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output ->
                                zipStream.copyTo(output)
                            }
                        }

                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }

                // Verify SKILL.md exists (search recursively in case zip has a subdirectory)
                // Use case-insensitive matching to handle varying naming conventions (SKILL.md, skill.md, Skill.md, etc.)
                val skillMdFile = cachedDir.walkTopDown()
                    .firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                if (skillMdFile == null) {
                    cachedDir.deleteRecursively()
                    throw IllegalArgumentException("Downloaded skill does not contain SKILL.md or skill.md")
                }
                // If SKILL.md is inside a subdirectory, move its parent up to cachedDir
                val skillRootDir = skillMdFile.parentFile
                if (skillRootDir != cachedDir) {
                    // Move contents of skillRootDir into cachedDir
                    skillRootDir.listFiles()?.forEach { it.renameTo(File(cachedDir, it.name)) }
                    skillRootDir.deleteRecursively()
                }
            } catch (e: Exception) {
                cachedDir.deleteRecursively()
                logProgress(AnsiColor.RED, "ClawHub", "✗ Failed to download skill: ${e.message}")
                emit("skill_download_failed", "❌ 下载技能失败: $parsedSlug@$actualVersion", e.message)
                throw IllegalStateException("Failed to download skill from ClawHub: ${e.message}", e)
            }

            logProgress(
                AnsiColor.GREEN,
                "ClawHub",
                "✓ Successfully downloaded and cached skill '$parsedSlug@$actualVersion' to: ${cachedDir.absolutePath}"
            )
            emit("skill_download_completed", "✅ 下载技能完成: $parsedSlug@$actualVersion", cachedDir.absolutePath)

            // Refresh skill manager to include new skill
            skillManager?.refresh()

            // Load skill name outside the download try-catch to avoid deleting cached files on load errors
            val loader = SkillLoader(
                cachedDir.toPath(),
                SkillsConfiguration(skillsPath = cachedDir.absolutePath, maxSkillsPerRequest = 1)
            )
            val loadedSkill = try {
                loader.loadSkill(cachedDir.toPath())
            } catch (e: Exception) {
                logProgress(AnsiColor.YELLOW, "ClawHub", "⚠ Could not read skill metadata: ${e.message}")
                null
            }
            SkillReference(
                skillPath = cachedDir.absolutePath,
                skillName = loadedSkill?.name,
                slug = parsedSlug,
                version = actualVersion
            )
        }
    }

    /**
     * Download a skill from a Git repository URL.
     */
    @Tool
    @LLMDescription("Download a Claude skill from a Git repository URL (GitHub, GitLab, etc.) to a local temporary directory")
    suspend fun downloadSkillFromGit(
        @LLMDescription("Git repository URL containing the skill (e.g., https://github.com/user/skill-repo.git)")
        repositoryUrl: String,
        @LLMDescription("optional subdirectory within the repo where SKILL.md is located; leave empty if SKILL.md is in repo root")
        subdirectory: String = ""
    ): SkillReference = withContext(Dispatchers.IO) {
        logProgress(
            AnsiColor.CYAN,
            "GitDownload",
            "Preparing to download skill from Git: $repositoryUrl (subdirectory: '$subdirectory')"
        )
        emit("skill_download_preparing", "📦 准备从 Git 下载技能", repositoryUrl)
        val repoName = repositoryUrl.substringAfterLast("/").removeSuffix(".git")
        val repoHash = repositoryUrl.hashCode().toString(16)
        val skillDirName = "git_${repoName}_$repoHash"
        val skillPathRoot = File(skillsCacheDir, skillDirName)

        if (skillPathRoot.exists()) {
            skillPathRoot.deleteRecursively()
        }
        skillPathRoot.mkdirs()

        try {
            logProgress(AnsiColor.CYAN, "GitDownload", "Downloading skill from: $repositoryUrl")

            // 🔒 Security: Use ProcessBuilder with arguments directly to prevent command injection.
            // Use SubprocessSafety so the child can't pin a thread on a stuck network connection
            // (5-minute cap) or wedge us on a full stdout pipe (drained on background thread).
            val osName = System.getProperty("os.name", "Unknown").lowercase()
            val isWindows = osName.contains("win")

            val command = if (isWindows) {
                listOf("cmd", "/c", "git", "clone", "--depth", "1", repositoryUrl, skillPathRoot.absolutePath)
            } else {
                listOf("git", "clone", "--depth", "1", repositoryUrl, skillPathRoot.absolutePath)
            }

            val cloneResult = com.fartech.agents.commons.SubprocessSafety.runCapturedWithTimeout(
                command = command,
                timeoutSeconds = GIT_CLONE_TIMEOUT_SECONDS,
                maxOutputBytes = 1 * 1024 * 1024
            )

            if (cloneResult.timedOut) {
                skillPathRoot.deleteRecursively()
                throw IllegalStateException(
                    "git clone for '$repositoryUrl' exceeded ${GIT_CLONE_TIMEOUT_SECONDS}s timeout — aborted"
                )
            }
            val output = cloneResult.output
            val exitCode = cloneResult.exitCode ?: -1

            if (exitCode != 0) {
                skillPathRoot.deleteRecursively()
                throw IllegalStateException("Failed to clone repository: $output")
            }

            // Determine the actual skill path
            val skillPath = if (subdirectory.isNotBlank()) {
                File(skillPathRoot, subdirectory)
            } else {
                skillPathRoot
            }

            // Verify SKILL.md exists (case-insensitive to handle varying naming conventions)
            val skillFile = skillPath.listFiles()?.firstOrNull {
                it.isFile && it.name.equals("SKILL.md", ignoreCase = true)
            }

            if (skillFile == null) {
                skillPathRoot.deleteRecursively()
                throw IllegalArgumentException(
                    "SKILL.md not found in ${skillPath.absolutePath}. " +
                            "Please check the repository URL and subdirectory."
                )
            }

            logProgress(
                AnsiColor.GREEN,
                "GitDownload",
                "✓ Successfully downloaded skill to: ${skillPath.absolutePath}"
            )
            emit("skill_download_completed", "✅ 从 Git 下载技能完成", skillPath.absolutePath)

            // Refresh skill manager to include new skill
            skillManager?.refresh()

            val loader = SkillLoader(skillPath.toPath(), SkillsConfiguration(skillsPath = skillPath.absolutePath))
            val loadedSkill = loader.loadSkill(skillPath.toPath())

            SkillReference(
                skillPath = skillPath.absolutePath,
                skillName = loadedSkill?.name
            )
        } catch (e: Exception) {
            skillPathRoot.deleteRecursively()
            logProgress(
                AnsiColor.RED,
                "GitDownload",
                "✗ Failed to download skill: ${e.message}"
            )
            emit("skill_download_failed", "❌ 从 Git 下载技能失败", e.message)
            throw e
        }
    }

    /**
     * Inspect a skill at a local path.
     */
    @Tool
    @LLMDescription("Get detailed information about a skill from a local path or downloaded skill reference")
    fun inspectSkill(
        @LLMDescription("path to the skill directory containing SKILL.md")
        skillPath: String
    ): String {
        logProgress(AnsiColor.CYAN, "SkillTools", "Inspecting skill at path: $skillPath")
        emit("skill_inspect_starting", "🔍 检查技能: $skillPath")
        return try {
            val path = Paths.get(skillPath)
            if (!path.exists() || !path.isDirectory()) {
                return "Error: Skill path does not exist or is not a directory: $skillPath"
            }

            val tempConfig = SkillsConfiguration(
                skillsPath = skillPath,
                progressiveDisclosure = false,
                maxSkillsPerRequest = 1
            )
            val loader = SkillLoader(path, tempConfig)
            val skill = loader.loadSkill(path)

            if (skill == null) {
                "No valid skill found in: $skillPath\nPlease ensure SKILL.md exists and is properly formatted."
            } else {
                buildString {
                    appendLine("Skill Information:")
                    appendLine("━".repeat(60))
                    appendLine("Name: ${skill.name}")
                    appendLine("Description: ${skill.description}")
                    if (skill.version != null) appendLine("Version: ${skill.version}")
                    if (skill.author != null) appendLine("Author: ${skill.author}")
                    if (skill.tags.isNotEmpty()) appendLine("Tags: ${skill.tags.joinToString(", ")}")
                    if (skill.dependencies.isNotEmpty()) {
                        appendLine("Dependencies: ${skill.dependencies.joinToString(", ")}")
                    }
                    if (skill.attachments.isNotEmpty()) {
                        appendLine("Attachments: ${skill.attachments.size} file(s)")
                        skill.attachments.forEach { att ->
                            appendLine("  - ${att.name} (${att.type})")
                        }
                    }
                    appendLine("━".repeat(60))
                    appendLine()
                    appendLine("Full Content:")
                    appendLine(skill.toFullContent())
                }.also { emit("skill_inspect_completed", "✅ 检查技能完成: ${skill.name}") }
            }
        } catch (e: Exception) {
            emit("skill_inspect_failed", "❌ 检查技能失败: $skillPath", e.message)
            "Failed to inspect skill: ${e.message}"
        }
    }

    /**
     * List cached skills from the local cache directory.
     */
    @Tool
    @LLMDescription("List all skills that have been downloaded and cached locally")
    fun listCachedSkills(): String {
        logProgress(AnsiColor.CYAN, "SkillTools", "Listing cached skills in directory: $skillsCacheDir")
        emit("skill_list_cached_starting", "📋 列出缓存技能")
        val cacheDir = File(skillsCacheDir)
        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            return "No cached skills found. Cache directory: ${cacheDir.absolutePath}"
        }

        val cachedSkills = cacheDir.listFiles { file ->
            file.isDirectory && (File(file, "SKILL.md").exists() || File(file, "skill.md").exists())
        }?.sortedBy { it.name } ?: emptyList()

        if (cachedSkills.isEmpty()) {
            return "No cached skills found in: ${cacheDir.absolutePath}"
        }

        return buildString {
            appendLine("Cached Skills (${cachedSkills.size}):")
            appendLine("Cache directory: ${cacheDir.absolutePath}")
            appendLine()

            cachedSkills.forEach { skillDir ->
                val cacheKey = skillDir.name
                val skillMdFile = File(skillDir, "SKILL.md").takeIf { it.exists() }
                    ?: File(skillDir, "skill.md")

                appendLine("• $cacheKey")
                appendLine("  Path: ${skillDir.absolutePath}")

                try {
                    val firstLine = skillMdFile.readLines().firstOrNull { it.startsWith("#") }
                    if (firstLine != null) {
                        appendLine("  Name: ${firstLine.removePrefix("#").trim()}")
                    }
                } catch (e: Exception) {
                    // Ignore read errors
                }
                appendLine()
            }

            appendLine("Use inspectSkill() to view detailed information about a cached skill.")
            appendLine("Use downloadSkillFromClawHub() with forceDownload=true to refresh a cached skill.")
        }.also { emit("skill_list_cached_completed", "✅ 缓存技能列出完成", "共 ${cachedSkills.size} 个技能") }
    }

    /**
     * Clear the local skill cache.
     */
    @Tool
    @LLMDescription("Clear all cached skills from the local cache directory, or clear a specific skill by cache key")
    fun clearSkillCache(
        @LLMDescription("optional specific skill cache key to clear (e.g., 'slug@version'); if not specified, clears entire cache")
        cacheKey: String? = null
    ): String {
        emit("skill_cache_clear_starting", "🗑️ 清除技能缓存")
        val cacheDir = File(skillsCacheDir)

        val result = if (cacheKey != null) {
            // Clear specific skill
            val normalizedKey = cacheKey.replace("/", "_").replace(":", "_")
            val skillDir = File(cacheDir, normalizedKey)
            // If exact directory not found, try matching by slug in _meta.json or name in SKILL.md
            val targetDir = if (skillDir.exists()) {
                skillDir
            } else {
                cacheDir.listFiles { f -> f.isDirectory }?.firstOrNull { dir ->
                    // Match by directory name prefix (slug@version or slug-version)
                    dir.name == normalizedKey ||
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
            }
            if (targetDir != null && targetDir.exists()) {
                val deleted = targetDir.deleteRecursively()
                if (deleted) {
                    "✓ Cleared cached skill: $cacheKey"
                } else {
                    "✗ Failed to clear cached skill: $cacheKey"
                }
            } else {
                "Cached skill not found: $cacheKey"
            }
        } else {
            // Clear entire cache
            if (!cacheDir.exists()) {
                "Cache directory does not exist: ${cacheDir.absolutePath}"
            } else {
                val count = cacheDir.listFiles()?.size ?: 0
                val deleted = cacheDir.deleteRecursively() && cacheDir.mkdirs()
                if (deleted) {
                    "✓ Cleared $count cached skill(s) from: ${cacheDir.absolutePath}"
                } else {
                    "✗ Failed to clear cache directory: ${cacheDir.absolutePath}"
                }
            }
        }

        // Refresh skill manager to reflect deleted skills
        skillManager?.refresh()
        emit("skill_cache_clear_completed", "✅ 技能缓存清除完成")
        return result
    }

    /**
     * Activate a skill and load its full instructions into the conversation context.
     * Uses deduplication: if a skill has already been activated in this session,
     * returns a notice instead of re-injecting the same instructions.
     *
     * The returned content is wrapped in structured XML tags (`<skill_content>`)
     * for clear identification during context management, and includes a listing
     * of bundled resources that the model can load on demand.
     */
    @Tool
    @LLMDescription("Activate a skill and load its full instructions. Returns structured content with skill instructions and bundled resources. If already activated in this session, returns a deduplication notice.")
    fun useSkill(
        @LLMDescription("The exact name of the skill to activate (e.g., 'search_files'). Must match a name from the available_skills catalog.")
        skillName: String
    ): String {
        logProgress(AnsiColor.CYAN, "SkillTools", "Attempting to activate skill: $skillName")
        emit("skill_use_starting", "🧩 使用技能: $skillName")
        val manager = getManager()
            ?: return "Error: Skill manager not available or no skills directory configured."

        return manager.activateSkill(skillName).also { result ->
            emit("skill_use_completed", "✅ 技能使用完成: $skillName", result)
        }
    }

    /**
     * Find local skills that are relevant to a specific query or task description.
     */
    @Tool
    @LLMDescription("Find local skills that are relevant to a specific query or task description")
    fun findLocalSkills(
        @LLMDescription("A description of the task or keywords to find relevant skills")
        query: String
    ): List<String> {
        logProgress(AnsiColor.CYAN, "SkillTools", "Finding local skills relevant to query: $query")
        emit("skill_discovery_starting", "🔍 发现本地技能: $query")
        val manager = getManager()
            ?: return emptyList()

        return manager.detectRelevantSkills(query).also { results ->
            emit("skill_discovery_completed", "✅ 本地技能发现完成: $query", "命中 ${results.size} 个技能")
        }
    }

    /**
     * Refresh the skill manager to re-discover and re-load all skills from the configured directory.
     */
    @Tool
    @LLMDescription("Refresh the skill manager to re-discover and re-load all skills from the configured directory")
    fun refreshSkills(): String {
        logProgress(
            AnsiColor.CYAN,
            "SkillTools",
            "Refreshing skill manager and re-loading skills from configured directory"
        )
        emit("skill_install_starting", "♻️ 刷新技能安装状态")
        val manager = getManager()
            ?: return "Error: Skill manager not available or no skills directory configured."

        manager.refresh()
        return "✓ Successfully refreshed ${manager.getSkillCount()} skill(s).".also {
            emit("skill_install_completed", "✅ 技能刷新完成", it)
        }
    }

    /**
     * List skills from the configured skills directory.
     */
    @Tool
    @LLMDescription("List all skills available in the configured skills directory (from configuration file)")
    fun listConfiguredSkills(): String {
        logProgress(AnsiColor.CYAN, "SkillTools", "Listing skills from configured skills directory")
        emit("skill_list_configured_starting", "📋 列出已配置技能")
        val manager = getManager()
            ?: return "No skills directory configured in the system configuration."

        return try {
            manager.initialize()
            val skills = manager.getAllSkills()

            if (skills.isEmpty()) {
                "No skills found in the configured skills directory."
            } else {
                buildString {
                    appendLine("Configured Skills (${skills.size}):")
                    appendLine()
                    skills.forEach { skill ->
                        appendLine("• ${skill.name}: ${skill.description}")
                        if (skill.version != null) {
                            appendLine("  Version: ${skill.version}")
                        }
                        if (skill.tags.isNotEmpty()) {
                            appendLine("  Tags: ${skill.tags.joinToString(", ")}")
                        }
                        appendLine()
                    }
                    appendLine()
                    appendLine("Note: These skills are from the system configuration.")
                    appendLine("To add skills dynamically:")
                    appendLine("  1. Use searchSkills() to find skills in ClawHub registry")
                    appendLine("  2. Use downloadSkillFromClawHub() to download from ClawHub (uses local cache)")
                    appendLine("  3. Use downloadSkillFromGit() to download from Git repositories")
                    appendLine("  4. Use listCachedSkills() to view cached skills")
                    appendLine("  5. Pass the returned SkillReference to sub-agents")
                }.also { emit("skill_list_configured_completed", "✅ 已配置技能列出完成", "共 ${skills.size} 个技能") }
            }
        } catch (e: Exception) {
            emit("skill_list_configured_failed", "❌ 列出已配置技能失败", e.message)
            "Failed to list configured skills: ${e.message}"
        }
    }
}
