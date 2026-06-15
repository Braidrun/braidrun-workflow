package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import mu.KotlinLogging
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

/**
 * 文件管理工具集，提供复制、移动、删除、重命名、搜索、压缩/解压、哈希计算等文件管理操作。
 *
 * 当提供 [allowedDirectories] 时，所有写入操作（复制目标、移动目标、重命名、删除、创建目录、
 * ZIP 输出、解压输出）都会验证目标路径是否在允许的目录范围内。如果不在，操作会被拒绝并返回
 * 错误消息引导 LLM 使用正确的目录。读取操作不受限制。
 *
 * 当 [allowedDirectories] 为空时，所有操作不受限制（向后兼容）。
 *
 * @param allowedDirectories 允许写入的目录列表。为空表示不限制。
 */
@LLMDescription(
    "File management tools for copy, move, delete, rename, search, compress/decompress, " +
            "directory creation, file info, and hash computation. " +
            "Complements the built-in read/write/edit/list tools with essential file management operations."
)
class FileManagementTools(
    private val allowedDirectories: List<Path> = emptyList(),
    private val readGuard: FileReadGuard = FileReadGuard.DISABLED
) : ToolSet {

    // DateTimeFormatter is immutable/thread-safe; a shared SimpleDateFormat corrupts
    // output (or throws) when tool calls run concurrently within a turn.
    private val dateFormat = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())

    private val normalizedAllowedDirs: List<Path> = allowedDirectories.map {
        it.toAbsolutePath().normalize()
    }

    init {
        if (normalizedAllowedDirs.isNotEmpty()) {
            logger.info {
                "[FileManagement Sandbox] Write sandbox enabled. Allowed directories: ${normalizedAllowedDirs.joinToString(", ")}"
            }
        }
    }

    /**
     * 验证路径是否在允许的写入目录范围内。
     * 如果 allowedDirectories 为空则不验证（向后兼容）。
     * 如果不在允许范围内，返回错误消息；否则返回 null。
     */
    private fun validateWritePath(path: String, operation: String): String? {
        if (normalizedAllowedDirs.isEmpty()) return null

        // Use canonical path (resolves symlinks) instead of normalize() which doesn't
        val normalizedPath = File(path).canonicalFile.toPath()
        val isAllowed = normalizedAllowedDirs.any { allowedDir ->
            val canonicalAllowed = allowedDir.toFile().canonicalFile.toPath()
            normalizedPath == canonicalAllowed || normalizedPath.startsWith(canonicalAllowed)
        }
        if (!isAllowed) {
            val allowedDirsStr = normalizedAllowedDirs.joinToString("\n  - ")
            logger.warn {
                "[FileManagement Sandbox] $operation denied for path: $normalizedPath. " +
                    "Allowed directories: $normalizedAllowedDirs"
            }
            return "Error: $operation denied — '$normalizedPath' is outside the allowed working directories. " +
                "You MUST operate within one of these directories:\n  - $allowedDirsStr\n" +
                "Please use an absolute path within these directories."
        }
        return null
    }

    private fun validateReadPath(path: String, operation: String): String? {
        val candidate = File(path)
        val normalizedPath = runCatching { candidate.canonicalFile.toPath() }
            .getOrElse { candidate.absoluteFile.toPath().normalize() }

        if (normalizedAllowedDirs.isNotEmpty()) {
            val isAllowed = normalizedAllowedDirs.any { allowedDir ->
                val canonicalAllowed = allowedDir.toFile().canonicalFile.toPath()
                normalizedPath == canonicalAllowed || normalizedPath.startsWith(canonicalAllowed)
            }
            if (!isAllowed) {
                val allowedDirsStr = normalizedAllowedDirs.joinToString("\n  - ")
                logger.warn {
                    "[FileManagement Sandbox] $operation denied for path: $normalizedPath. " +
                        "Allowed directories: $normalizedAllowedDirs"
                }
                return "Error: $operation denied — '$normalizedPath' is outside the allowed working directories. " +
                    "You MUST operate within one of these directories:\n  - $allowedDirsStr\n" +
                    "Please use an absolute path within these directories."
            }
        }

        return runCatching {
            readGuard.validateReadFile(candidate.canonicalFile)
            null
        }.getOrElse { error ->
            "Error: $operation denied — ${error.message}"
        }
    }

    private fun isWithinDirectory(candidate: File, directory: File): Boolean {
        val directoryPath = directory.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        return candidatePath == directoryPath || candidatePath.startsWith(directoryPath)
    }

    @Tool
    @LLMDescription("Copy a file or directory to a new location. If copying a directory, all contents are copied recursively.")
    fun copyFile(
        @LLMDescription("Source file or directory path")
        source: String,
        @LLMDescription("Destination file or directory path")
        destination: String,
        @LLMDescription("Whether to overwrite existing files (default true)")
        overwrite: Boolean = true
    ): String {
        validateReadPath(source, "Copy source")?.let { return it }
        validateWritePath(destination, "Copy")?.let { return it }
        return try {
            val src = File(source)
            val dst = File(destination)
            if (!src.exists()) return "Error: source does not exist: $source"

            if (src.isDirectory) {
                // Phase 9 (2026-05): hard cap on the number of entries walked so an
                // LLM can't trigger a copy of a million-file tree (or follow a symlink
                // loop) and pin the JVM thread / fill the destination disk.
                // walkTopDown does NOT follow symlinks (Kotlin's `walk` semantic), so
                // a self-referential directory tree is the realistic exhaustion vector.
                var entryCount = 0
                src.walkTopDown().forEach { file ->
                    entryCount++
                    if (entryCount > MAX_COPY_ENTRIES) {
                        return "Error: copy aborted — directory '$source' contains more than " +
                            "$MAX_COPY_ENTRIES entries. Narrow the scope or copy subdirectories individually."
                    }
                    if (file.isFile) {
                        validateReadPath(file.absolutePath, "Copy source")?.let { return it }
                    }
                    val relativePath = file.relativeTo(src)
                    val targetFile = File(dst, relativePath.path)
                    if (file.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        if (overwrite || !targetFile.exists()) {
                            targetFile.parentFile?.mkdirs()
                            file.copyTo(targetFile, overwrite)
                        }
                    }
                }
                "Successfully copied directory '$source' to '$destination' ($entryCount entries)"
            } else {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite)
                "Successfully copied file '$source' to '$destination'"
            }
        } catch (e: Exception) {
            "Error copying: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Move or rename a file or directory to a new location.")
    fun moveFile(
        @LLMDescription("Source file or directory path")
        source: String,
        @LLMDescription("Destination file or directory path")
        destination: String,
        @LLMDescription("Whether to overwrite existing files (default true)")
        overwrite: Boolean = true
    ): String {
        validateReadPath(source, "Move source")?.let { return it }
        validateWritePath(destination, "Move")?.let { return it }
        return try {
            val src = File(source).toPath()
            val dst = File(destination).toPath()
            if (!Files.exists(src)) return "Error: source does not exist: $source"

            dst.parent?.let { Files.createDirectories(it) }

            if (overwrite) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(src, dst)
            }
            "Successfully moved '$source' to '$destination'"
        } catch (e: Exception) {
            "Error moving: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Rename a file or directory. Only changes the name, keeps the same parent directory.")
    fun renameFile(
        @LLMDescription("Path to the file or directory to rename")
        path: String,
        @LLMDescription("New name (just the name, not a full path)")
        newName: String
    ): String {
        if (
            newName.isBlank() ||
            newName == "." ||
            newName == ".." ||
            newName.contains('/') ||
            newName.contains('\\')
        ) {
            return "Error: newName must be a simple file or directory name without path separators"
        }
        val file = File(path)
        validateReadPath(path, "Rename source")?.let { return it }
        val target = File(file.parentFile, newName)
        validateWritePath(target.absolutePath, "Rename")?.let { return it }
        return try {
            if (!file.exists()) return "Error: file does not exist: $path"
            if (target.exists()) return "Error: a file or directory with name '$newName' already exists in the same directory"
            val success = file.renameTo(target)
            if (success) "Successfully renamed to '${target.absolutePath}'" else "Error: rename failed"
        } catch (e: Exception) {
            "Error renaming: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Delete a file or directory. If deleting a directory, all contents are deleted recursively.")
    fun deleteFile(
        @LLMDescription("Path to the file or directory to delete")
        path: String,
        @LLMDescription("If true, recursively delete directory contents (required for non-empty directories)")
        recursive: Boolean = false
    ): String {
        validateWritePath(path, "Delete")?.let { return it }
        return try {
            val file = File(path)
            if (!file.exists()) return "Error: file does not exist: $path"

            if (file.isDirectory && recursive) {
                if (file.deleteRecursively()) {
                    "Successfully deleted directory and all contents: $path"
                } else {
                    "Error: failed to delete directory: $path"
                }
            } else if (file.isDirectory) {
                if (file.list()?.isEmpty() == true) {
                    if (file.delete()) {
                        "Successfully deleted empty directory: $path"
                    } else {
                        "Error: failed to delete directory: $path"
                    }
                } else {
                    "Error: directory is not empty. Use recursive=true to delete non-empty directories."
                }
            } else {
                if (file.delete()) {
                    "Successfully deleted file: $path"
                } else {
                    "Error: failed to delete file: $path"
                }
            }
        } catch (e: Exception) {
            "Error deleting: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Create a directory, including any necessary parent directories.")
    fun createDirectory(
        @LLMDescription("Path of the directory to create")
        path: String
    ): String {
        validateWritePath(path, "Create directory")?.let { return it }
        return try {
            val dir = File(path)
            if (dir.exists()) {
                return if (dir.isDirectory) {
                    "Directory already exists: $path"
                } else {
                    "Error: a file already exists at: $path"
                }
            }
            if (dir.mkdirs() || dir.isDirectory) {
                "Successfully created directory: ${dir.absolutePath}"
            } else {
                "Error: failed to create directory: $path"
            }
        } catch (e: Exception) {
            "Error creating directory: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get detailed information about a file or directory including size, timestamps, permissions.")
    fun getFileInfo(
        @LLMDescription("Path to the file or directory")
        path: String
    ): String {
        validateReadPath(path, "Get file info")?.let { return it }
        return try {
            val file = File(path)
            if (!file.exists()) return "Error: file does not exist: $path"

            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val sb = StringBuilder()
            sb.appendLine("Path: ${file.absolutePath}")
            sb.appendLine("Type: ${if (file.isDirectory) "Directory" else "File"}")
            sb.appendLine("Size: ${attrs.size()} bytes (${formatSize(attrs.size())})")
            sb.appendLine("Created: ${dateFormat.format(attrs.creationTime().toInstant())}")
            sb.appendLine("Modified: ${dateFormat.format(attrs.lastModifiedTime().toInstant())}")
            sb.appendLine("Accessed: ${dateFormat.format(attrs.lastAccessTime().toInstant())}")
            sb.appendLine("Readable: ${file.canRead()}")
            sb.appendLine("Writable: ${file.canWrite()}")
            sb.appendLine("Executable: ${file.canExecute()}")
            sb.appendLine("Hidden: ${file.isHidden}")
            if (file.isDirectory) {
                val children = file.listFiles()
                sb.appendLine("Children: ${children?.size ?: 0}")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error getting file info: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Search for files by name pattern (glob or regex) recursively within a directory. " +
                "Returns matching file paths."
    )
    fun searchFiles(
        @LLMDescription("Directory to search in")
        directory: String,
        @LLMDescription("Search pattern: glob pattern (e.g., '*.txt', '**/*.kt') or prefix with 'regex:' for regex (e.g., 'regex:.*\\.log')")
        pattern: String,
        @LLMDescription("Maximum number of results to return (default 100)")
        maxResults: Int = 100,
        @LLMDescription("Maximum directory depth to search (default -1 for unlimited)")
        maxDepth: Int = -1
    ): String {
        validateReadPath(directory, "Search files")?.let { return it }
        // Phase 11 hardening: cap pattern length and per-name match wall-clock so a
        // catastrophic-backtracking regex (e.g. `regex:(a+)+b` against a long filename)
        // can't pin the agent thread. We cap pattern length to defend against giant
        // adversarial patterns, and time-limit each `matches`/`containsMatchIn` call
        // via java.util.regex.Matcher.find on a CharSequence proxy.
        require(maxResults in 1..10_000) { "maxResults must be in 1..10000" }
        if (pattern.length > MAX_SEARCH_PATTERN_LENGTH) {
            return "Error: pattern too long (${pattern.length} chars, max $MAX_SEARCH_PATTERN_LENGTH)"
        }
        return try {
            val dir = File(directory)
            if (!dir.isDirectory) return "Error: not a directory: $directory"

            val isRegex = pattern.startsWith("regex:")
            val regex = try {
                if (isRegex) {
                    Regex(pattern.removePrefix("regex:"))
                } else {
                    val globPattern = pattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".")
                    Regex(globPattern, RegexOption.IGNORE_CASE)
                }
            } catch (e: java.util.regex.PatternSyntaxException) {
                return "Error: invalid regex pattern: ${e.message}"
            }
            val pat = regex.toPattern()

            val results = mutableListOf<String>()
            dir.walkTopDown()
                .let { if (maxDepth >= 0) it.maxDepth(maxDepth) else it }
                .forEach { file ->
                    if (results.size >= maxResults) return@forEach
                    if (validateReadPath(file.absolutePath, "Search files") != null) return@forEach
                    // Time-bounded match — wraps the filename in a `TimeoutCharSequence`
                    // that throws if `Matcher.find` reads characters for longer than
                    // [REGEX_MATCH_WALL_CLOCK_MS]. Catastrophic backtracking burns through
                    // billions of `charAt` calls long before it finishes, so this catches
                    // the pathological case without slowing legitimate matches.
                    val nameLimited = TimeoutCharSequence(file.name, REGEX_MATCH_WALL_CLOCK_MS)
                    val matched = try {
                        pat.matcher(nameLimited).find()
                    } catch (_: TimeoutCharSequence.RegexTimeoutException) {
                        false
                    }
                    if (matched) {
                        results.add(file.absolutePath)
                    }
                }

            if (results.isEmpty()) {
                "No files found matching pattern '$pattern' in '$directory'"
            } else {
                "Found ${results.size} file(s):\n${results.joinToString("\n")}"
            }
        } catch (e: Exception) {
            "Error searching files: ${e.message}"
        }
    }

    /**
     * CharSequence proxy that records the start-of-match wall clock on first read and
     * throws [RegexTimeoutException] once a deadline has passed. Cheap (one volatile
     * read per `charAt`, no allocation in the hot path) and effective at killing
     * catastrophic-backtracking regex evaluations that walk a small input billions of times.
     */
    private class TimeoutCharSequence(
        private val inner: CharSequence,
        private val budgetMillis: Long
    ) : CharSequence {
        class RegexTimeoutException : RuntimeException("Regex match exceeded ${'$'}budgetMillis ms")

        private var deadline: Long = -1L

        private fun checkBudget() {
            if (deadline < 0L) {
                deadline = System.nanoTime() + budgetMillis * 1_000_000L
                return
            }
            if (System.nanoTime() > deadline) throw RegexTimeoutException()
        }

        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            checkBudget()
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            TimeoutCharSequence(inner.subSequence(startIndex, endIndex), budgetMillis).also {
                it.deadline = this.deadline
            }

        override fun toString(): String = inner.toString()
    }

    @Tool
    @LLMDescription("Read a specific range of lines from a file. Useful for large files where you only need a portion.")
    fun readFileRange(
        @LLMDescription("Path to the file")
        path: String,
        @LLMDescription("Starting line number (1-based)")
        startLine: Int = 1,
        @LLMDescription("Ending line number (1-based, inclusive). Use -1 for end of file.")
        endLine: Int = -1
    ): String {
        validateReadPath(path, "Read file range")?.let { return it }
        return try {
            val file = File(path)
            if (!file.exists()) return "Error: file does not exist: $path"
            if (!file.isFile) return "Error: not a file: $path"

            val lines = file.readLines()
            val start = (startLine - 1).coerceIn(0, lines.size)
            val end = if (endLine < 0) lines.size else endLine.coerceIn(start, lines.size)

            val selectedLines = lines.subList(start, end)
            "Lines $startLine-$end of ${lines.size} total:\n${selectedLines.joinToString("\n")}"
        } catch (e: Exception) {
            "Error reading file range: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Compress files or directories into a ZIP archive.")
    fun zipFiles(
        @LLMDescription("Comma-separated list of file or directory paths to include in the ZIP")
        sources: String,
        @LLMDescription("Output ZIP file path")
        outputZipPath: String
    ): String {
        validateWritePath(outputZipPath, "Zip output")?.let { return it }
        return try {
            val paths = sources.split(",").map { it.trim() }
            val outFile = File(outputZipPath)
            outFile.parentFile?.mkdirs()

            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                for (sourcePath in paths) {
                    validateReadPath(sourcePath, "Zip source")?.let { return it }
                    val sourceFile = File(sourcePath)
                    if (!sourceFile.exists()) return "Error: source does not exist: $sourcePath"

                    if (sourceFile.isDirectory) {
                        sourceFile.walkTopDown().forEach { file ->
                            if (file.isFile) {
                                validateReadPath(file.absolutePath, "Zip source")?.let { return it }
                                val entryName = "${sourceFile.name}/${file.relativeTo(sourceFile).path}"
                                zos.putNextEntry(ZipEntry(entryName))
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    } else {
                        zos.putNextEntry(ZipEntry(sourceFile.name))
                        sourceFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            "Successfully created ZIP archive: ${outFile.absolutePath} (${formatSize(outFile.length())})"
        } catch (e: Exception) {
            "Error creating ZIP: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Extract a ZIP archive to a directory.")
    fun unzipArchive(
        @LLMDescription("Path to the ZIP file")
        zipPath: String,
        @LLMDescription("Destination directory for extracted files")
        outputDirectory: String
    ): String {
        validateReadPath(zipPath, "Read ZIP")?.let { return it }
        validateWritePath(outputDirectory, "Unzip output")?.let { return it }
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return "Error: ZIP file does not exist: $zipPath"

            val outDir = File(outputDirectory)
            outDir.mkdirs()

            var count = 0
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(outDir, entry.name)
                    // Security: prevent zip slip
                    if (!isWithinDirectory(outFile, outDir)) {
                        return "Error: zip entry outside target directory (zip slip detected): ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        count++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            "Successfully extracted $count file(s) to: ${outDir.absolutePath}"
        } catch (e: Exception) {
            "Error extracting ZIP: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Compute a hash (MD5, SHA-256, or SHA-512) of a file.")
    fun computeFileHash(
        @LLMDescription("Path to the file")
        path: String,
        @LLMDescription("Hash algorithm: MD5, SHA-256, or SHA-512 (default SHA-256)")
        algorithm: String = "SHA-256"
    ): String {
        validateReadPath(path, "Compute file hash")?.let { return it }
        return try {
            val file = File(path)
            if (!file.exists()) return "Error: file does not exist: $path"
            if (!file.isFile) return "Error: not a file: $path"

            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            "$algorithm: $hash"
        } catch (e: Exception) {
            "Error computing hash: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get the total size of a directory (sum of all files recursively) or a single file.")
    fun getDirectorySize(
        @LLMDescription("Path to the file or directory")
        path: String
    ): String {
        validateReadPath(path, "Read directory size")?.let { return it }
        return try {
            val file = File(path)
            if (!file.exists()) return "Error: path does not exist: $path"

            val totalSize = if (file.isDirectory) {
                var size = 0L
                file.walkTopDown().forEach { f ->
                    if (f.isFile) size += f.length()
                }
                size
            } else {
                file.length()
            }
            "Total size: $totalSize bytes (${formatSize(totalSize)})"
        } catch (e: Exception) {
            "Error calculating size: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Compare two files and report whether they are identical or different.")
    fun compareFiles(
        @LLMDescription("Path to the first file")
        file1: String,
        @LLMDescription("Path to the second file")
        file2: String
    ): String {
        validateReadPath(file1, "Compare files")?.let { return it }
        validateReadPath(file2, "Compare files")?.let { return it }
        return try {
            val f1 = File(file1)
            val f2 = File(file2)
            if (!f1.exists()) return "Error: file does not exist: $file1"
            if (!f2.exists()) return "Error: file does not exist: $file2"

            if (f1.length() != f2.length()) {
                return "Files differ in size: ${f1.length()} bytes vs ${f2.length()} bytes"
            }

            val equal = filesEqual(f1, f2)
            if (equal) "Files are identical" else "Files have the same size (${f1.length()} bytes) but different content"
        } catch (e: Exception) {
            "Error comparing files: ${e.message}"
        }
    }

    private fun filesEqual(first: File, second: File): Boolean {
        first.inputStream().use { input1 ->
            second.inputStream().use { input2 ->
                val buffer1 = ByteArray(8192)
                val buffer2 = ByteArray(8192)
                while (true) {
                    val read1 = input1.read(buffer1)
                    val read2 = input2.read(buffer2)
                    if (read1 != read2) return false
                    if (read1 == -1) return true
                    for (idx in 0 until read1) {
                        if (buffer1[idx] != buffer2[idx]) return false
                    }
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.2f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    companion object {
        /**
         * Hard cap on entries walked by `copyFile` when the source is a directory.
         * Above this, the operation is rejected with a clear message — Phase 9 (2026-05)
         * audit found that an unbounded `walkTopDown` made directory copies a DoS
         * vector for any agent with a large source path. `walkTopDown` does NOT follow
         * symlinks (Kotlin's `walk` semantic), so a self-referential directory tree is
         * the realistic exhaustion vector.
         *
         * 100k entries comfortably covers normal project trees (`node_modules` averages
         * ~30k files, a Kotlin/Gradle project ~10k) while bounding worst-case impact.
         */
        internal const val MAX_COPY_ENTRIES = 100_000

        /**
         * Max length of a user-supplied search pattern. 4 KiB is far above what any legit
         * regex / glob expects; the cap is purely a sanity guard against multi-MB
         * adversarial inputs whose `Pattern.compile` alone would exhaust CPU.
         */
        internal const val MAX_SEARCH_PATTERN_LENGTH = 4_096

        /**
         * Wall-clock budget for matching a single filename against a user regex. 250 ms
         * is generous for any reasonable pattern; catastrophic-backtracking patterns
         * burn through billions of CPU cycles long before this elapses and so trigger
         * the timeout cleanly.
         */
        internal const val REGEX_MATCH_WALL_CLOCK_MS = 250L

        /**
         * 从 Agent 参数创建带沙箱的 FileManagementTools 实例。
         *
         * 如果参数中包含 working_dir 或 output_dir，则创建带路径限制的实例。
         * 否则创建无限制的实例（向后兼容）。
         *
         * @param parameters Agent 配置参数列表
         * @return FileManagementTools 实例
         */
        fun createFromParameters(
            parameters: List<ConfigurationParameter>,
            readGuard: FileReadGuard = FileReadGuard.DISABLED
        ): FileManagementTools {
            val workingDir = parameters.parameter("working_dir", "")
            val outputDir = parameters.parameter("output_dir", "")

            if (workingDir.isBlank()) {
                return FileManagementTools(readGuard = readGuard)
            }

            val allowedDirs = mutableListOf<Path>()
            allowedDirs.add(Path.of(workingDir))
            if (outputDir.isNotBlank()) {
                allowedDirs.add(Path.of(outputDir))
            }
            // 系统临时目录
            val tempDir = System.getProperty("java.io.tmpdir", "/tmp")
            allowedDirs.add(Path.of(tempDir))

            return FileManagementTools(allowedDirs, readGuard)
        }
    }
}
