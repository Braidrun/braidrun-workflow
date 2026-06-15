package com.fartech.agents.agents.app_generators.iOS

import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor
import java.io.File
import java.util.*

/**
 * iOS generator utility functions extracted from the oversized IOSAppDevelopAgent.
 * Keeping these helpers separate improves readability and reusability.
 */

/**
 * Checks if the current operating system is macOS.
 * Used to decide whether Xcode-related build/test phases should run.
 */
internal fun isMacOS(): Boolean {
    val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
    return osName.contains("mac") || osName.contains("darwin")
}

/**
 * Extract the output for a specific agent from aggregated parallel execution results.
 * Expected block marker format:
 *   "The result for agent with name {agentName} is:\n{output}"
 * Returns null if no such block is found.
 */
internal fun extractAgentOutput(aggregatedResults: String, agentName: String): String? {
    val escapedAgentName = Regex.escape(agentName)
    // More robust: support CRLF/CR/LF line endings and single/multiple blank lines between blocks.
    val pattern = Regex(
        "The result for agent with name $escapedAgentName is:\\s*\\R(.*?)(?=\\R+The result for agent with name |\\z)",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val match = pattern.find(aggregatedResults)
    return match?.groupValues?.getOrNull(1)?.trim()
}

/**
 * Create or clean the project root directory depending on overwrite flag.
 * - If the directory doesn't exist, it will be created.
 * - If it exists and overwrite=true, it will be cleared recursively.
 */
internal fun prepareProjectRootDirectory(props: IOSAppProjectProperties): File {
    val projectRootPath = props.projectRoot ?: "."
    val root = File(projectRootPath).absoluteFile

    if (!root.exists()) {
        val ok = root.mkdirs()
        if (!ok && !root.exists()) {
            printlnColor(AnsiColor.RED, "[Init] Failed to create project root: ${root.path}")
        } else {
            printlnColor(AnsiColor.CYAN_B, "[Init] Created project root: ${root.path}")
        }
    } else if (props.overwrite == true) {
        // Refuse to recursively delete obviously-wrong roots: projectRoot defaults
        // to "." — an LLM-confirmed properties payload that sets overwrite=true but
        // omits projectRoot would otherwise wipe whatever directory the agent
        // process was launched from (the user's repo, configs, …).
        val canonical = runCatching { root.canonicalFile }.getOrDefault(root)
        val cwd = runCatching { File("").absoluteFile.canonicalFile }.getOrNull()
        val home = runCatching { File(System.getProperty("user.home")).canonicalFile }.getOrNull()
        val isDangerousRoot = canonical.parentFile == null || // filesystem root
            canonical == cwd ||
            canonical == home
        if (isDangerousRoot) {
            printlnColor(
                AnsiColor.RED,
                "[Init] REFUSING overwrite of ${canonical.path} — it is the process working directory, " +
                    "user home, or filesystem root. Set an explicit project_root for overwrite mode."
            )
        } else {
            printlnColor(AnsiColor.YELLOW, "[Init] Overwrite mode: clearing directory ${root.path}")
            root.deleteRecursively()
            val ok = root.mkdirs()
            if (!ok && !root.exists()) {
                printlnColor(AnsiColor.RED, "[Init] Failed to recreate project root after clearing: ${root.path}")
            }
        }
    } else {
        printlnColor(AnsiColor.CYAN, "[Init] Directory exists (no overwrite): ${root.path}")
    }

    return root
}

/**
 * Whether project initialization is needed for the selected technology.
 */
internal fun shouldInitializeProject(props: IOSAppProjectProperties): Boolean {
    return when (props.implementTechnology) {
        IOSAppProjectProperties.ImplementTechnology.Native -> true
        IOSAppProjectProperties.ImplementTechnology.Flutter -> true
        IOSAppProjectProperties.ImplementTechnology.ReactNative -> true
        IOSAppProjectProperties.ImplementTechnology.UniApp -> false
        IOSAppProjectProperties.ImplementTechnology.KotlinMpp -> true
        else -> false
    }
}

/**
 * Generate a concise project structure summary for context sharing (e.g., to sub-agents).
 */
internal fun getProjectStructureSummary(projectRoot: File): String = buildString {
    append("Project root: ${projectRoot.absolutePath}\n\n")
    append("Main files and directories:\n")
    runCatching {
        projectRoot.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile || (it.isDirectory && it.listFiles()?.isNotEmpty() == true) }
            .take(50)
            .forEach { file ->
                val relativePath = file.relativeTo(projectRoot).path
                val icon = if (file.isDirectory) "📁" else "📄"
                val suffix = if (file.isDirectory) "/" else ""
                append("$icon $relativePath$suffix\n")
            }
    }.onFailure { e ->
        append("Error reading structure: ${e.message}\n")
    }
}

/**
 * Read the entire project directory tree into a human-readable list.
 */
internal fun readExistingProjectStructure(projectRoot: File): String = buildString {
    append("Project Root: ${projectRoot.absolutePath}\n\n")
    runCatching {
        projectRoot.walkTopDown()
            .filter { it.isFile || (it.isDirectory && it.listFiles()?.isNotEmpty() == true) }
            .forEach { file ->
                val relativePath = file.relativeTo(projectRoot).path
                val icon = if (file.isDirectory) "📁" else "📄"
                val suffix = if (file.isDirectory) "/" else ""
                append("$icon $relativePath$suffix\n")
            }
    }.onFailure { e ->
        append("Error reading structure: ${e.message}\n")
    }
}

/**
 * Filter out invalid file entries from layout (bad placeholders or empty paths).
 */
internal fun filterInvalidFiles(layout: IOSAppProjectFiles): IOSAppProjectFiles {
    val validFiles = layout.files.mapIndexedNotNull { idx, file ->
        val isValid = file.path.isNotEmpty() &&
                !file.path.contains("<") &&
                !file.path.contains(">") &&
                !file.path.contains("=\"") &&
                file.path != "./" &&
                file.name.isNotEmpty()

        if (!isValid) {
            printlnColor(
                AnsiColor.RED,
                "[ERROR] Invalid file entry (index=$idx) filtered: name='${file.name}', path='${file.path}'"
            )
            null
        } else file
    }

    return IOSAppProjectFiles(validFiles)
}

/**
 * Merge file architectures during redesign.
 * When LLM returns only modified files (token optimization), this function merges them with previous architecture.
 *
 * @param previous The previous complete architecture (e.g., 89 files)
 * @param updated The updated/modified architecture from LLM (e.g., 18 files that need changes)
 * @return Merged architecture with all files (updated files override previous, unchanged files are preserved)
 */
internal fun mergeFileArchitectures(
    previous: List<FileArchitecture>,
    updated: List<FileArchitecture>
): List<FileArchitecture> {
    // Create a map of previous architectures by file path for fast lookup
    val previousMap = previous.associateBy { normalizeFilePath(it.filePath) }.toMutableMap()

    // Update or add files from the updated architecture
    updated.forEach { updatedFile ->
        val normalizedPath = normalizeFilePath(updatedFile.filePath)
        previousMap[normalizedPath] = updatedFile
    }

    // Return all files (updated + unchanged)
    return previousMap.values.toList()
}

/**
 * Normalize file path for consistent comparison.
 * Removes leading/trailing slashes and quotes, converts to lowercase for case-insensitive comparison.
 */
fun normalizeFilePath(path: String): String {
    return path
        .trim()
        .removePrefix("/")
        .removeSuffix("/")
        .replace("\"", "")
        .replace("'", "")
        .replace("\\", "/")
        .lowercase(Locale.ROOT)
}

/**
 * Find the [FileArchitecture] entry matching [filePath], tolerating the
 * absolute-vs-relative split between sanitized generated-file paths and the
 * LLM's original architecture paths. Raw `==` matching silently missed in
 * that case: the ARCHITECTURE SPECIFICATION section vanished from every
 * per-file generation prompt and `isCritical` was always false.
 *
 * Match order: exact normalized path → unique suffix match across a '/'
 * boundary → unique basename (mirrors groupFilesByDependency's fallback).
 */
internal fun findFileArchitecture(
    architectures: List<FileArchitecture>?,
    filePath: String
): FileArchitecture? {
    if (architectures.isNullOrEmpty()) return null
    val target = normalizeFilePath(filePath)
    architectures.find { normalizeFilePath(it.filePath) == target }?.let { return it }
    architectures.filter { arch ->
        val a = normalizeFilePath(arch.filePath)
        a.isNotBlank() && (target.endsWith("/$a") || a.endsWith("/$target"))
    }.singleOrNull()?.let { return it }
    val base = target.substringAfterLast('/')
    if (base.isBlank()) return null
    return architectures
        .filter { normalizeFilePath(it.filePath).substringAfterLast('/') == base }
        .singleOrNull()
}

/**
 * Build a set of normalized relative paths for all files and non-empty directories under project root.
 */
internal fun buildExistingPathSet(projectRoot: File): Set<String> {
    val paths = mutableSetOf<String>()
    runCatching {
        projectRoot.walkTopDown()
            .filter { it.isFile || (it.isDirectory && it.listFiles()?.isNotEmpty() == true) }
            .forEach { file ->
                val rel = file.relativeTo(projectRoot).path
                paths.add(normalizeFilePath(rel))
            }
    }
    return paths
}

/**
 * Normalize an arbitrary path string to a relative path under the project root, if possible.
 */
internal fun toRelativeUnder(projectRoot: File, path: String): String {
    if (path.isBlank()) return ""
    val normPath = normalizeFilePath(path)
    val projectRootPath = normalizeFilePath(projectRoot.path)
    // If path already contains the projectRoot segment, strip it
    val stripped = if (normPath.startsWith(projectRootPath)) {
        normPath.removePrefix(projectRootPath).removePrefix("/")
    } else normPath
    // If it's absolute under the same root, relativize
    val f = File(path)
    return try {
        if (f.isAbsolute && f.path.startsWith(projectRoot.path)) {
            normalizeFilePath(f.relativeTo(projectRoot).path)
        } else stripped
    } catch (_: Exception) {
        stripped
    }
}
