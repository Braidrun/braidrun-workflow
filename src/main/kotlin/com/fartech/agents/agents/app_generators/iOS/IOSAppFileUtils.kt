package com.fartech.agents.agents.app_generators.iOS

import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor
import java.io.File
import java.util.*

// Media detection based on MIME type and file extension
internal fun IOSAppProjectFile.isMedia(): Boolean {
    val m = mime.lowercase(Locale.ROOT)
    if (m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/")) return true

    fun extOf(p: String): String {
        val normalized = p.replace('\\', '/')
        val n = normalized.substringAfterLast('/')
        val dot = n.lastIndexOf('.')
        return if (dot >= 0 && dot < n.length - 1) n.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    val ext = when {
        name.isNotBlank() -> extOf(name)
        else -> extOf(path)
    }
    return ext in setOf(
        // images
        "png", "jpg", "jpeg", "gif", "webp", "heic", "tiff", "svg", "pdf",
        // audio
        "mp3", "aac", "m4a", "wav", "flac", "ogg",
        // video
        "mp4", "mov", "m4v", "avi", "mkv", "webm"
    )
}

// Iterate over project layout
internal fun IOSAppProjectFiles.walkthroughProjectLayout(action: (file: IOSAppProjectFile) -> Unit) =
    files.forEach { file -> action(file) }

// Helper utilities for Setup Node
internal fun sanitizeFileEntry(file: IOSAppProjectFile, projectProperties: IOSAppProjectProperties): IOSAppProjectFile {
    if (file.path.contains("<") || file.path.contains(">") || file.path.contains("=\"")) {
        printlnColor(AnsiColor.RED, "[WARNING] Suspicious path detected: ${file.path}")
    }
    val sanitizedName = sanitizeName(file.name)
    val sanitizedPath = sanitizePath(file.path, projectProperties.projectRoot)
    return if (sanitizedName != file.name || sanitizedPath != file.path) {
        printlnColor(AnsiColor.YELLOW, "Sanitized: '${file.path}' -> '$sanitizedPath'")
        file.copy(name = sanitizedName, path = sanitizedPath)
    } else file
}

internal fun isMediaAssetDirectory(file: IOSAppProjectFile): Boolean {
    if (!file.isDir) return false
    val pathLower = file.path.replace('\\', '/').lowercase(Locale.ROOT)
    return pathLower.endsWith(".imageset") || pathLower.endsWith(".appiconset")
}

internal fun collectFilesFromLayout(
    layout: IOSAppProjectFiles,
    projectProperties: IOSAppProjectProperties
): List<IOSAppProjectFile> {
    val allFiles = mutableListOf<IOSAppProjectFile>()
    // Track case-insensitive normalized paths to warn/dedupe
    val seenKeys = mutableMapOf<String, String>() // key -> original path
    layout.walkthroughProjectLayout { raw ->
        val sanitized = sanitizeFileEntry(raw, projectProperties)
        if (sanitized.isDir) {
            // Always create directories (including asset catalogs), but do not add them to generation list
            if (isMediaAssetDirectory(sanitized)) {
                printlnColor(AnsiColor.CYAN_B, "Media asset directory: ${sanitized.path}")
            } else {
                printlnColor(AnsiColor.CYAN_B, "Creating directory: ${sanitized.path}")
            }
            val dir = File(sanitized.path)
            val ok = dir.mkdirs()
            if (!ok && !dir.exists()) {
                printlnColor(AnsiColor.RED, "[Init] Failed to create directory: ${sanitized.path}")
            }
        } else {
            val key = sanitized.path.replace('\\', '/').lowercase(Locale.ROOT)
            val existing = seenKeys[key]
            if (existing != null && existing != sanitized.path) {
                printlnColor(
                    AnsiColor.YELLOW,
                    "[Dedup] Path collision (case-insensitive) detected: '${sanitized.path}' conflicts with '$existing'. Skipping duplicate."
                )
                return@walkthroughProjectLayout
            }
            seenKeys[key] = sanitized.path
            // Files (source or media) go to the generation list
            if (sanitized.isMedia()) {
                printlnColor(AnsiColor.CYAN_B, "Media file: ${sanitized.path}")
            } else {
                printlnColor(AnsiColor.CYAN_B, "Source file: ${sanitized.path}")
            }
            allFiles.add(sanitized)
        }
    }
    return allFiles
}

internal fun splitSourceAndMedia(allFiles: List<IOSAppProjectFile>): Pair<List<IOSAppProjectFile>, List<IOSAppProjectFile>> {
    val sourceFiles = allFiles.filter { !it.isMedia() && !it.isDir }
    // Only files are considered media generation targets (directories were already created)
    val mediaFiles = allFiles.filter { it.isMedia() && !it.isDir }
    return sourceFiles to mediaFiles
}

// =========================
// Image grouping helpers
// =========================
internal fun computeImageGroupKey(file: IOSAppProjectFile): String {
    val normalizedPath = file.path.replace('\\', '/').lowercase(Locale.ROOT)
    val dir = normalizedPath.substringBeforeLast('/', "")
    // Group by asset catalog directory if present
    if (dir.endsWith(".imageset") || dir.endsWith(".appiconset")) return dir

    val fileName = normalizedPath.substringAfterLast('/')
    val base = normalizeBaseImageName(fileName)
    return if (dir.isNotBlank()) "$dir/$base" else base
}

private fun normalizeBaseImageName(fileName: String): String {
    val nameNoExt = fileName.substringBeforeLast('.')
    var base = nameNoExt.lowercase(Locale.ROOT)
    // Remove @1x/@2x/@3x
    base = base.replace(Regex("@[1-3]x"), "")
    // Remove size tokens like -20x20, _100x100, 1024x1024
    base = base.replace(Regex("""[_\- ]?\d{2,4}x\d{2,4}"""), "")
    // Collapse repeated separators
    base = base.replace(Regex("""[ _\-]+"""), "-").trim('-', '_', ' ')
    return base
}

private fun sizeAreaFromName(file: IOSAppProjectFile): Int {
    val n = file.name.ifBlank { file.path.substringAfterLast('/') }.lowercase(Locale.ROOT)
    val areas = Regex("(\\d{2,4})x(\\d{2,4})").findAll(n).map {
        val w = it.groupValues[1].toIntOrNull() ?: 0
        val h = it.groupValues[2].toIntOrNull() ?: 0
        w * h
    }.toList()
    return areas.maxOrNull() ?: 0
}

internal fun pickBaseImageFile(group: List<IOSAppProjectFile>): IOSAppProjectFile {
    return group.maxWithOrNull(compareBy({ sizeAreaFromName(it) }, { it.path.length })) ?: group.first()
}

internal fun findExistingReferenceImageOnDisk(target: IOSAppProjectFile, allMedia: List<IOSAppProjectFile>): String? {
    val key = computeImageGroupKey(target)
    val siblings = allMedia.filter {
        it.path != target.path && it.mime.lowercase(Locale.ROOT).startsWith("image/") && computeImageGroupKey(it) == key
    }
    // Prefer the largest existing image in the same group
    val existing = siblings
        .filter { File(it.path).exists() }
        .maxWithOrNull(compareBy({ sizeAreaFromName(it) }, { it.path.length }))
    return existing?.path
}

// Group by dependency for batching
internal fun groupFilesByDependency(
    files: List<IOSAppProjectFile>,
    architecture: ProjectArchitecture?,
    batchSize: Int
): List<List<IOSAppProjectFile>> {
    fun normalizePath(p: String): String = p.replace('\\', '/').lowercase(Locale.ROOT)
    fun extractFileName(path: String): String = path.substringAfterLast('/')
    fun baseName(path: String): String = extractFileName(path).substringBefore('.')
    fun dir(path: String): String = path.substringBeforeLast('/', "")

    val deps: Map<String, Set<String>> = files.associate { file ->
        val fileKey = normalizePath(file.path)
        val fileDir = dir(fileKey)
        val arch = architecture?.fileArchitectures?.find {
            val a = normalizePath(it.filePath)
            val aDir = dir(a)
            a == fileKey || (baseName(a) == baseName(fileKey) && aDir == fileDir)
        }
        val d = arch?.dependencies?.map { normalizePath(it) }?.toSet() ?: emptySet()
        fileKey to d
    }

    val remaining = files.toMutableList()
    val generated = mutableSetOf<String>()
    val batches = mutableListOf<List<IOSAppProjectFile>>()

    fun isDependencySatisfied(
        dep: String,
        generatedFiles: Set<String>,
        remainingFiles: List<IOSAppProjectFile>
    ): Boolean {
        val depKey = normalizePath(dep)
        if (generatedFiles.contains(depKey)) return true
        // If an exact-path dependency remains, it's not satisfied
        if (remainingFiles.any { normalizePath(it.path) == depKey }) return false
        // Fallback: baseName match among remaining; if any present, consider unsatisfied (conservative)
        val depName = baseName(depKey)
        val nameMatches = remainingFiles.filter { baseName(normalizePath(it.path)) == depName }
        return nameMatches.isEmpty()
    }

    while (remaining.isNotEmpty()) {
        val batch = mutableListOf<IOSAppProjectFile>()
        val iterator = remaining.iterator()
        while (iterator.hasNext() && batch.size < batchSize) {
            val f = iterator.next()
            val key = normalizePath(f.path)
            val fileDeps = deps[key] ?: emptySet()
            val satisfied = fileDeps.all { isDependencySatisfied(it, generated, remaining) }
            if (satisfied) {
                batch.add(f)
                iterator.remove()
            }
        }

        if (batch.isNotEmpty()) {
            batches.add(batch)
            generated.addAll(batch.map { normalizePath(it.path) })
        } else {
            if (remaining.isNotEmpty()) {
                val forcedFile = remaining.removeAt(0)
                generated.add(normalizePath(forcedFile.path))
                batches.add(listOf(forcedFile))
                printlnColor(
                    AnsiColor.YELLOW,
                    "[Dependency Warning] Forcing generation of ${forcedFile.path} to break potential circular dependency"
                )
            }
        }
    }

    return batches
}

// Path/Name Sanitization Utilities

/**
 * Removes surrounding quotes and whitespace from a string.
 * Handles nested quotes by removing them iteratively.
 */
internal fun sanitizeName(raw: String): String {
    var s = raw.trim()

    // Remove matching quotes iteratively
    while (s.length >= 2 && isQuoted(s)) {
        s = s.substring(1, s.length - 1).trim()
    }

    return s
}

private fun isQuoted(s: String): Boolean = when {
    s.length < 2 -> false
    s.first() == '"' && s.last() == '"' -> true
    s.first() == '\'' && s.last() == '\'' -> true
    s.first() == '`' && s.last() == '`' -> true
    else -> false
}

/**
 * Sanitizes a file path by removing HTML-like tags and quotes, normalizing path separators,
 * and resolving relative segments ('.' and '..'). Returns an absolute path located under the
 * provided project root directory. Absolute inputs inside the root are converted to a normalized
 * path under the root; other inputs are resolved relative to the project root.
 */
internal fun sanitizePath(rawPath: String, projectRoot: String?): String {
    val rootFile = File(projectRoot ?: ".").absoluteFile
    val rootAbs = rootFile.absolutePath.replace("\\", "/")
    val rootName = rootFile.name

    // Step 1: Remove HTML-like tags and attributes
    var p = removeHtmlArtifacts(rawPath)

    // Step 2: Remove quotes
    p = removeQuotes(p)

    // Step 3: Normalize slashes
    p = normalizeSlashes(p)

    // Step 4: Convert to relative path if it's absolute
    val rel = makeRelativePath(p, rootAbs, rootName)

    // Step 5: Normalize path segments (handle . and ..)
    val normSegments = normalizePathSegments(rel)

    // Step 6: Build final path
    val relNorm = normSegments.joinToString("/")
    return if (relNorm.isEmpty()) rootFile.path else File(rootFile, relNorm).path
}

// Compiled regex patterns for better performance
private val HTML_TAG_REGEX = Regex("<[^>]+>|\\w+=\"[^\"]*\"|\\w+='[^']*'")

private fun removeHtmlArtifacts(path: String): String =
    HTML_TAG_REGEX.replace(path.trim(), "").trim()

internal fun removeQuotes(path: String): String {
    var p = path
    while (p.length >= 2 && isQuoted(p)) {
        p = p.substring(1, p.length - 1).trim()
    }
    return p.trim('"', '\'', '`')
}

internal fun normalizeSlashes(path: String): String {
    var p = path.replace("\\", "/")
    if (p.startsWith("./")) p = p.removePrefix("./")
    while (p.contains("//")) p = p.replace("//", "/")
    return p
}

private fun makeRelativePath(path: String, rootAbs: String, rootName: String): String {
    var rel = path.replace("\\", "/").trimStart('/')

    // Prefer absolute-root stripping
    when {
        rel.startsWith(rootAbs) -> rel = rel.removePrefix(rootAbs).trimStart('/')
        rel.startsWith("$rootName/") -> rel = rel.removePrefix(rootName).trimStart('/')
        rel == rootName -> rel = ""
        else -> {
            // Handle cases like ".../braidrun-cli/temp/<rootName>/..." where the path
            // is relative from repository root (or other higher-level dir) and already
            // includes the project root segments. Remove everything up to the last
            // occurrence of the rootName segment to avoid duplicating the root.
            val token = "/$rootName/"
            val idx = rel.indexOf(token)
            if (idx >= 0) {
                rel = rel.substring(idx + token.length)
            } else if (rel.endsWith("/$rootName")) {
                rel = ""
            } else {
                val segments = rel.split('/').filter { it.isNotEmpty() }
                val lastRootIdx = segments.indexOfLast { it == rootName }
                if (lastRootIdx >= 0) {
                    rel = segments.drop(lastRootIdx + 1).joinToString("/")
                }
            }
        }
    }

    return rel.trimStart('/')
}

private fun normalizePathSegments(path: String): List<String> {
    val segments = mutableListOf<String>()

    path.split('/').filter { it.isNotEmpty() }.forEach { segRaw ->
        when (val seg = segRaw.trim().trim('"', '\'', '`')) {
            "." -> { /* Skip current directory */
            }

            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(seg)
        }
    }

    return segments
}

// Compiled regex patterns for Base64 sanitization
private val DATA_URI_PREFIX_REGEX = Regex("^data:[^;]+;base64,", RegexOption.IGNORE_CASE)
private val WHITESPACE_REGEX = Regex("\\s+")
private val INVALID_BASE64_CHARS_REGEX = Regex("[^A-Za-z0-9+/=_-]")

/**
 * Sanitizes Base64 content by removing markdown fences, data URI prefixes, quotes,
 * and fixing padding. Returns a clean Base64 string ready for decoding.
 */
internal fun sanitizeBase64Content(raw: String): String =
    removeMarkdownFences(raw.trim())
        .replace(DATA_URI_PREFIX_REGEX, "")
        .let { removeQuotes(it) }
        .replace(WHITESPACE_REGEX, "")
        .replace(INVALID_BASE64_CHARS_REGEX, "")
        .replace('-', '+')
        .replace('_', '/')
        .let { fixBase64Padding(it) }

// ===============================
// Media validation and auto-repair
// ===============================

private fun fileExtOf(file: IOSAppProjectFile): String {
    fun extOf(p: String): String {
        val normalized = p.replace('\\', '/')
        val n = normalized.substringAfterLast('/')
        val dot = n.lastIndexOf('.')
        return if (dot >= 0 && dot < n.length - 1) n.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }
    return if (file.name.isNotBlank()) extOf(file.name) else extOf(file.path)
}

private fun hasMagic(bytes: ByteArray, magic: ByteArray, offset: Int = 0): Boolean {
    if (bytes.size < offset + magic.size) return false
    return magic.indices.all { i -> bytes[offset + i] == magic[i] }
}

// Magic byte signatures for common file formats
private object FileSignatures {
    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val GIF = "GIF8".toByteArray()
    val RIFF = "RIFF".toByteArray()
    val PDF = "%PDF".toByteArray()
    val ID3 = "ID3".toByteArray()
}

private fun detectKnownBinaryFormat(bytes: ByteArray): Boolean = when {
    hasMagic(bytes, FileSignatures.PNG) -> true
    hasMagic(bytes, FileSignatures.JPEG) -> true
    hasMagic(bytes, FileSignatures.GIF) -> true
    hasMagic(bytes, FileSignatures.PDF) -> true
    hasMagic(bytes, FileSignatures.ID3) -> true
    // WebP (RIFF....WEBP)
    hasMagic(bytes, FileSignatures.RIFF) && bytes.size >= 12 &&
            String(bytes.copyOfRange(8, 12)) == "WEBP" -> true
    // WAV (RIFF....WAVE)
    hasMagic(bytes, FileSignatures.RIFF) && bytes.size >= 12 &&
            String(bytes.copyOfRange(8, 12)) == "WAVE" -> true
    // MP3 frame sync
    bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xE0) == 0xE0 -> true
    // MP4/M4A/MOV (ftyp at offset 4)
    bytes.size >= 12 && String(bytes.copyOfRange(4, 8)) == "ftyp" -> true
    else -> false
}

private fun isProbablyText(content: ByteArray): Boolean {
    val sample = content.take(2048).toByteArray()
    var nonPrintable = 0
    var printable = 0
    for (b in sample) {
        val c = b.toInt() and 0xFF
        if (c in 9..13 || c in 32..126) printable++ else nonPrintable++
    }
    return printable >= nonPrintable
}

private fun readAllIfSmall(f: File, maxBytes: Long = 10L * 1024 * 1024): String? {
    return try {
        if (f.length() <= maxBytes) f.readText() else null
    } catch (_: Exception) {
        null
    }
}

private fun looksLikeSvgText(s: String): Boolean =
    s.contains("<svg", ignoreCase = true) && s.contains("</svg>", ignoreCase = true)

internal fun validateAndRepairMedia(file: IOSAppProjectFile): Boolean {
    val f = File(file.path)
    if (!f.exists()) return false
    if (f.isDirectory) return true
    if (f.length() == 0L) return false

    val ext = fileExtOf(file)
    val isSvg = ext == "svg" || file.mime.lowercase(Locale.ROOT).contains("svg")

    // Special case: SVG validation
    if (isSvg) {
        val text = readAllIfSmall(f) ?: return true
        return looksLikeSvgText(text)
    }

    // Read sample and check for known binary formats
    val sample = runCatching {
        f.inputStream().use { it.readNBytes(16384) }
    }.getOrElse { return false }

    if (sample.isNotEmpty() && detectKnownBinaryFormat(sample)) return true

    // Attempt Base64 repair if content appears to be text
    if (sample.isEmpty() || !isProbablyText(sample)) return false

    val text = readAllIfSmall(f) ?: return false
    val cleaned = sanitizeBase64Content(text)

    return runCatching {
        val bytes = Base64.getDecoder().decode(cleaned)
        if (bytes.isNotEmpty() && detectKnownBinaryFormat(bytes)) {
            f.writeBytes(bytes)
            true
        } else {
            false
        }
    }.getOrElse { false }
}

internal fun removeMarkdownFences(s: String): String {
    var result = s
    if (result.startsWith("```")) {
        val firstNl = result.indexOf('\n')
        if (firstNl >= 0) result = result.substring(firstNl + 1)
        val endFence = result.lastIndexOf("```")
        if (endFence >= 0) result = result.take(endFence)
        result = result.trim()
    }
    return result
}

private fun fixBase64Padding(s: String): String {
    if (s.isEmpty()) return s
    var result = s

    // Remove characters if length % 4 == 1 (invalid)
    while (result.isNotEmpty() && result.length % 4 == 1) {
        result = result.dropLast(1)
    }

    // Add padding
    return when (result.length % 4) {
        2 -> result + "=="
        3 -> result + "="
        else -> result
    }
}
