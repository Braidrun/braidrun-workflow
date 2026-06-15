package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.commons.logProgress
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.AnsiColor.RED
import com.fartech.ftapp2.commonsKt.parameter
import java.io.File
import java.io.IOException
import java.util.*

/**
 * Safe file operation tools that sanitize incoming paths before writing, and
 * optionally enforce read-side security (extension whitelist + filename blacklist).
 *
 * Security Features:
 * - Path traversal attack prevention (blocks ../ sequences)
 * - Sandbox enforcement (restricts writes to allowed directories)
 * - File size limits (prevents disk exhaustion attacks)
 * - File type validation (whitelist-based)
 * - Canonical path validation (prevents symlink attacks)
 * - **Phase 3c (C9)**: Read extension whitelist + sensitive filename blacklist
 *
 * Why: LLMs sometimes wrap file paths with quotes/backticks or add stray spaces.
 * That can accidentally create directories named "'" or '"', or write outside the intended root.
 *
 * This tool trims quotes/backticks, normalizes separators, and creates parent directories automatically.
 *
 * @param readGuard controls read-side validation. When [FileReadGuard.enabled] is true
 *        (staging/production), reads are filtered by extension whitelist and filename blacklist.
 *        When false (dev mode), all reads are permitted.
 */
class SafeFileTools(
    private val readGuard: FileReadGuard = FileReadGuard.DISABLED,
    allowedDirectories: List<File> = listOf(File(System.getProperty("user.dir")).canonicalFile)
) : ToolSet {

    // Security configuration with safe defaults
    private val BLOCKED_PATH_SEGMENTS = setOf(".git", ".hg", ".svn")

    private val BLOCKED_WRITE_FILENAMES = listOf(
        Regex("""^\.env(\..*)?$"""),
        Regex("""^\.npmrc$"""),
        Regex("""^\.pypirc$"""),
        Regex("""^\.netrc$"""),
        Regex("""^id_[a-z0-9_]+$"""),
        Regex(""".*\.pem$"""),
        Regex(""".*\.key$"""),
        Regex(""".*\.p12$"""),
        Regex(""".*\.pfx$"""),
        Regex(""".*\.jks$"""),
        Regex(""".*\.keystore$""")
    )

    // Allowed extensionless filenames (files without a dot-based extension)
    private val ALLOWED_EXTENSIONLESS_FILENAMES = setOf(
        // Build & CI
        "Makefile", "Dockerfile", "Containerfile", "Jenkinsfile", "Vagrantfile",
        "Procfile", "Brewfile", "Rakefile", "Gemfile", "Guardfile", "Podfile",
        "CMakeLists", "Justfile", "Taskfile",
        // Metadata & project
        "LICENSE", "LICENCE", "README", "CHANGELOG", "CONTRIBUTING",
        "AUTHORS", "CODEOWNERS", "OWNERS", "VERSION", "CREDITS",
        // Dotfiles (version-control & tooling)
        ".gitignore", ".gitattributes", ".gitmodules", ".gitkeep",
        ".dockerignore", ".editorconfig", ".prettierrc", ".eslintrc",
        ".babelrc", ".browserslistrc", ".npmrc", ".nvmrc", ".yarnrc",
        ".env", ".flake8", ".pylintrc", ".rubocop", ".stylelintrc",
        ".htaccess", ".mailmap"
    )

    // Allowed file extensions (whitelist approach)
    private val ALLOWED_TEXT_EXTENSIONS = setOf(
        "txt", "md", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "log", "csv", "tsv", "properties", "env",
        "html", "css", "js", "ts", "jsx", "tsx",
        "java", "kt", "kts", "scala", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp",
        "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
        "sql", "graphql", "proto", "thrift"
    )

    private val ALLOWED_BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "zip", "tar", "gz", "bz2", "7z", "rar",
        "mp3", "wav", "ogg", "flac", "m4a",
        "mp4", "avi", "mkv", "mov", "webm",
        "ttf", "otf", "woff", "woff2", "eot"
    )

    private val normalizedAllowedDirs: List<File> = allowedDirectories
        .ifEmpty { listOf(File(System.getProperty("user.dir")).canonicalFile) }
        .map { it.canonicalFile }

    private val defaultWorkingDirectory: File by lazy {
        normalizedAllowedDirs.firstOrNull() ?: File(System.getProperty("user.dir")).canonicalFile
    }

    private fun isWithinAllowedDirectories(candidate: File): Boolean {
        val candidatePath = candidate.canonicalFile.toPath()
        return normalizedAllowedDirs.any { allowedDir ->
            val allowedPath = allowedDir.toPath()
            candidatePath == allowedPath || candidatePath.startsWith(allowedPath)
        }
    }

    /**
     * Validates and sanitizes a file path to prevent security vulnerabilities.
     *
     * Security checks:
     * 1. Removes quotes and special characters
     * 2. Prevents path traversal attacks (../)
     * 3. Validates path length
     * 4. Ensures path stays within sandbox
     * 5. Validates file extension
     *
     * @param raw Raw path input from LLM
     * @param allowBinary Whether to allow binary file extensions
     * @return Validated File object
     * @throws SecurityException if path is unsafe
     * @throws IllegalArgumentException if path is invalid
     */
    private fun sanitizeAndValidatePath(raw: String, allowBinary: Boolean = false, forWrite: Boolean = true): File {
        // Step 1: Remove quotes and normalize
        var p = raw.trim()

        // Remove wrapping quotes/backticks if both ends match
        while (p.length >= 2 && (
                    (p.startsWith('"') && p.endsWith('"')) ||
                            (p.startsWith('\'') && p.endsWith('\'')) ||
                            (p.startsWith('`') && p.endsWith('`'))
                    )
        ) {
            p = p.substring(1, p.length - 1).trim()
        }

        // Trim any stray leading/trailing quote characters
        p = p.trim('"', '\'', '`')

        require(p.isNotBlank()) {
            "Path cannot be blank"
        }

        // Validate path length before processing
        require(p.length <= MAX_PATH_LENGTH) {
            "Path length exceeds maximum allowed length of $MAX_PATH_LENGTH characters"
        }

        // Step 2: Normalize separators
        p = p.replace('\\', '/')

        // Step 3: Security check - detect and reject path traversal attempts
        val pathSegments = p.split('/').filter { it.isNotEmpty() }

        if (pathSegments.any { it == ".." }) {
            throw SecurityException("Path traversal detected: relative path component '..' is not allowed")
        }

        // Additional check for encoded path traversal attempts
        if (p.contains("%2e%2e", ignoreCase = true) ||
            p.contains("%2f", ignoreCase = true) ||
            p.contains("%5c", ignoreCase = true)
        ) {
            throw SecurityException("Encoded path traversal detected")
        }

        // Step 4: Reconstruct clean path
        val cleanPath = pathSegments.joinToString("/")

        // Handle absolute vs relative paths
        val targetFile = if (p.startsWith('/') || (p.length >= 2 && p[1] == ':')) {
            File(p)
        } else {
            // Relative path - resolve against working directory
            defaultWorkingDirectory.resolve(cleanPath)
        }

        // Step 5: Get canonical path to resolve symlinks and validate sandbox
        val canonicalTarget = try {
            targetFile.canonicalFile
        } catch (e: IOException) {
            // If file doesn't exist yet, validate parent directory
            val parent = targetFile.parentFile?.canonicalFile
                ?: throw IllegalArgumentException("Invalid file path: no parent directory")

            // Ensure parent is within allowed directories
            if (!isWithinAllowedDirectories(parent)) {
                throw SecurityException(buildAllowedDirectoryError(parent))
            }

            // Return file with canonical parent
            File(parent, targetFile.name)
        }

        // Step 6: Validate that canonical path is within allowed directories (sandbox check)
        if (!isWithinAllowedDirectories(canonicalTarget)) {
            throw SecurityException(buildAllowedDirectoryError(canonicalTarget))
        }

        val sandboxRoot = normalizedAllowedDirs.firstOrNull()
        val relativePath = sandboxRoot
            ?.toPath()
            ?.takeIf { canonicalTarget.toPath().startsWith(it) }
            ?.relativize(canonicalTarget.toPath())
        val blockedSegment = relativePath
            ?.iterator()
            ?.asSequence()
            ?.map { it.toString().lowercase(Locale.ROOT) }
            ?.firstOrNull { it in BLOCKED_PATH_SEGMENTS }
        if (blockedSegment != null) {
            throw SecurityException("Writes to protected directory '$blockedSegment' are not allowed")
        }

        if (forWrite) {
            val lowerName = canonicalTarget.name.lowercase(Locale.ROOT)
            if (BLOCKED_WRITE_FILENAMES.any { it.matches(lowerName) }) {
                throw SecurityException(
                    "Writes to sensitive filename '${canonicalTarget.name}' are not allowed. " +
                        "Use a non-secret template filename such as 'env.example.txt' if you need to document configuration."
                )
            }
        }

        // Step 7: Validate file extension or extensionless filename
        val extension = canonicalTarget.extension.lowercase()
        if (extension.isEmpty()) {
            // Files without extension: validate against known safe filenames
            val filename = canonicalTarget.name
            if (filename !in ALLOWED_EXTENSIONLESS_FILENAMES) {
                throw SecurityException(
                    "Files without an extension must have a recognized name. " +
                            "'$filename' is not in the allowed extensionless filenames list."
                )
            }
        } else {
            val allowedExtensions = if (allowBinary) {
                ALLOWED_TEXT_EXTENSIONS + ALLOWED_BINARY_EXTENSIONS
            } else {
                ALLOWED_TEXT_EXTENSIONS
            }
            if (extension !in allowedExtensions) {
                throw SecurityException(
                    "File extension '.$extension' is not allowed. " +
                            "Allowed extensions: ${allowedExtensions.sorted().joinToString(", ")}"
                )
            }
        }

        return canonicalTarget
    }

    private fun buildAllowedDirectoryError(target: File): String {
        val allowed = normalizedAllowedDirs.joinToString(", ") { it.path }
        return "Path escapes workflow sandbox. Allowed directories: $allowed. Target: ${target.path}"
    }

    /**
     * Validates file size before writing.
     *
     * @param contentSize Size of content to write
     * @throws SecurityException if size exceeds limit
     */
    private fun validateFileSize(contentSize: Long) {
        require(contentSize >= 0) { "Content size cannot be negative" }

        if (contentSize > MAX_FILE_SIZE) {
            throw SecurityException(
                "Content size ($contentSize bytes) exceeds maximum allowed size " +
                        "($MAX_FILE_SIZE bytes / ${MAX_FILE_SIZE / 1024 / 1024}MB)"
            )
        }
    }

    // -----------------------------
    // Content normalization helpers
    // -----------------------------
    private fun stripCodeFences(input: String): String {
        var s = input.trim()
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl >= 0) s = s.substring(firstNl + 1)
            val endFence = s.lastIndexOf("```")
            if (endFence >= 0) s = s.substring(0, endFence)
            s = s.trim()
        }
        return s
    }

    private fun htmlDecode(input: String): String {
        var s = input
        // Basic named entities
        s = s.replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        // Numeric entities: decimal and hex
        val dec = Regex("&#(\\d+);")
        val hex = Regex("&#x([0-9A-Fa-f]+);")
        s = dec.replace(s) { m ->
            runCatching { m.groupValues[1].toInt().toChar().toString() }.getOrElse { m.value }
        }
        s = hex.replace(s) { m ->
            runCatching { m.groupValues[1].toInt(16).toChar().toString() }.getOrElse { m.value }
        }
        return s
    }

    private fun unescapeJsonish(input: String): String {
        var s = input
        // Common escaped sequences
        s = s.replace("\\r\\n", "\n")
        s = s.replace("\\n", "\n")
        s = s.replace("\\t", "\t")
        s = s.replace("\\r", "\r")
        // Unicode escapes \uXXXX
        val unicode = Regex("\\\\u([0-9a-fA-F]{4})")
        s = unicode.replace(s) { m ->
            runCatching { m.groupValues[1].toInt(16).toChar().toString() }.getOrElse { m.value }
        }
        return s
    }

    private fun normalizeContent(raw: String): String {
        // Apply in order: strip code fences -> HTML decode -> unescape JSON-like sequences
        val noFences = stripCodeFences(raw)
        val html = htmlDecode(noFences)
        return unescapeJsonish(html)
    }

    @Tool
    @LLMDescription(
        "Read a text file. The path is validated for security: blocked extensions and sensitive filenames " +
                "(e.g. .env, private keys, SSH config) are rejected in multi-user mode. " +
                "Returns the file content as a string."
    )
    fun readFile(
        @LLMDescription("File path to read (relative or absolute)") path: String,
    ): String {
        return try {
            val file = sanitizeAndValidatePath(path, allowBinary = true, forWrite = false)

            if (!file.exists()) {
                return "Error: file does not exist: ${file.absolutePath}"
            }
            if (!file.isFile) {
                return "Error: path is not a file: ${file.absolutePath}"
            }

            // Apply read guard (C9) — throws SecurityException if blocked
            readGuard.validateReadFile(file)

            file.readText(Charsets.UTF_8)
        } catch (e: SecurityException) {
            logProgress(RED, "SafeFile", "❌ Read denied: ${e.message}")
            "❌ Security Error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            "❌ Validation Error: ${e.message}"
        } catch (e: IOException) {
            "❌ IO Error: ${e.message}"
        } catch (e: Exception) {
            "❌ Unexpected Error: ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Write text content to a file. Accepts `path` or `filePath` for the target file. The path will be sanitized and validated for security. File size is limited to 100MB. Only allowed file extensions are permitted.")
    fun writeFile(
        @LLMDescription("Target file path (relative or absolute). Prefer this field. Do NOT wrap with quotes; this tool sanitizes automatically.") path: String? = null,
        @LLMDescription("Text content to write to the file") content: String,
        @LLMDescription("Append to file instead of overwrite (default false)") append: Boolean? = false,
        @LLMDescription("Alias for `path`; accepted for models that emit filePath instead of path") filePath: String? = null,
    ): String {
        return try {
            val targetPath = path?.takeIf { it.isNotBlank() }
                ?: filePath?.takeIf { it.isNotBlank() }
                ?: return "❌ Validation Error: missing required file path. Provide `path` or `filePath`."

            // Validate and sanitize path (text files only)
            val validatedFile = sanitizeAndValidatePath(targetPath, allowBinary = false)

            // Normalize content
            val processed = normalizeContent(content)

            // Validate content size
            val contentSize = processed.toByteArray(Charsets.UTF_8).size.toLong()
            validateFileSize(contentSize)

            // Check if appending to existing file would exceed size limit
            if (append == true && validatedFile.exists()) {
                val totalSize = validatedFile.length() + contentSize
                if (totalSize > MAX_FILE_SIZE) {
                    throw SecurityException(
                        "Appending would exceed file size limit. " +
                                "Current: ${validatedFile.length()} bytes, " +
                                "Adding: $contentSize bytes, " +
                                "Total: $totalSize bytes, " +
                                "Limit: $MAX_FILE_SIZE bytes"
                    )
                }
            }

            // Create parent directories if needed
            validatedFile.parentFile?.mkdirs()

            // Write or append
            if (append == true) {
                validatedFile.appendText(processed, Charsets.UTF_8)
            } else {
                validatedFile.writeText(processed, Charsets.UTF_8)
            }

            "✅ Successfully wrote ${processed.length} chars (${contentSize} bytes) to: ${validatedFile.absolutePath}"
        } catch (e: SecurityException) {
            logProgress(RED, "SafeFile", "❌ Security Error: ${e.javaClass.simpleName}")
            "❌ Security Error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            logProgress(RED, "SafeFile", "❌ Validation Error: ${e.javaClass.simpleName}")
            "❌ Validation Error: ${e.message}"
        } catch (e: IOException) {
            logProgress(RED, "SafeFile", "❌ IO Error: ${e.javaClass.simpleName}")
            "❌ IO Error: ${e.message}"
        } catch (e: Exception) {
            logProgress(RED, "SafeFile", "❌ Unexpected Error: ${e.javaClass.simpleName}")
            "❌ Unexpected Error: ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Write binary file from Base64 encoded content. This tool decodes the Base64 string and writes raw binary bytes to disk. Use this for images, audio, video, and other media files. File size limited to 100MB.")
    fun writeBinaryFile(
        @LLMDescription("Target file path (relative or absolute). Do NOT wrap with quotes; this tool sanitizes automatically.") path: String,
        @LLMDescription("Base64 encoded content (pure Base64 string without data URI prefix like 'data:image/png;base64,')") base64Content: String,
    ): String {
        return try {
            // Validate and sanitize path (binary files allowed)
            val validatedFile = sanitizeAndValidatePath(path, allowBinary = true)

            // Sanitize and normalize Base64 input robustly
            fun removeMarkdownFences(input: String): String {
                var s = input.trim()
                if (s.startsWith("```")) {
                    val firstNl = s.indexOf('\n')
                    if (firstNl >= 0) s = s.substring(firstNl + 1)
                    val endFence = s.lastIndexOf("```")
                    if (endFence >= 0) s = s.substring(0, endFence)
                    s = s.trim()
                }
                return s
            }

            fun fixPadding(s: String): String {
                var r = s
                var mod = r.length % 4
                if (mod == 1) {
                    while (r.isNotEmpty() && (r.length % 4 == 1)) {
                        r = r.dropLast(1)
                    }
                    mod = r.length % 4
                }
                when (mod) {
                    2 -> r += "=="
                    3 -> r += "="
                }
                return r
            }

            var b64 = base64Content.trim()
            b64 = removeMarkdownFences(b64)
            // Remove data URI prefix if present (e.g., "data:image/png;base64,")
            b64 = b64.replace(Regex("^data:[^;]+;base64,", RegexOption.IGNORE_CASE), "")
            // Remove any surrounding quotes/backticks
            while (b64.length >= 2 && (
                        (b64.startsWith('"') && b64.endsWith('"')) ||
                                (b64.startsWith('\'') && b64.endsWith('\'')) ||
                                (b64.startsWith('`') && b64.endsWith('`'))
                        )
            ) {
                b64 = b64.substring(1, b64.length - 1).trim()
            }
            b64 = b64.trim('"', '\'', '`')
            // Remove whitespace/newlines
            b64 = b64.replace("\\s".toRegex(), "")
            // Keep only valid Base64 URL or standard characters
            b64 = b64.replace(Regex("[^A-Za-z0-9+/=_-]"), "")
            // Convert URL-safe Base64 to standard
            b64 = b64.replace('-', '+').replace('_', '/')
            // Fix padding
            b64 = fixPadding(b64)

            // Validate Base64 length (approximate size check before decoding)
            val estimatedBytes = (b64.length * 3L) / 4L
            if (estimatedBytes > MAX_FILE_SIZE) {
                throw SecurityException(
                    "Estimated decoded size ($estimatedBytes bytes) exceeds maximum allowed size ($MAX_FILE_SIZE bytes)"
                )
            }

            // Decode
            val bytes = Base64.getDecoder().decode(b64)

            // Validate actual decoded size
            validateFileSize(bytes.size.toLong())

            // Create parent directories if needed
            validatedFile.parentFile?.mkdirs()

            // Write binary data
            validatedFile.writeBytes(bytes)

            "✅ Successfully wrote ${bytes.size} bytes (decoded from Base64) to: ${validatedFile.absolutePath}"
        } catch (e: SecurityException) {
            logProgress(RED, "SafeFile", "❌ Security Error: ${e.javaClass.simpleName}")
            "❌ Security Error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            logProgress(RED, "SafeFile", "❌ Validation Error: ${e.javaClass.simpleName}")
            "❌ Validation Error (possibly invalid Base64): ${e.message}"
        } catch (e: IOException) {
            logProgress(RED, "SafeFile", "❌ IO Error: ${e.javaClass.simpleName}")
            "❌ IO Error: ${e.message}"
        } catch (e: Exception) {
            logProgress(RED, "SafeFile", "❌ Unexpected Error: ${e.javaClass.simpleName}")
            "❌ Unexpected Error: ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    companion object {
        private const val MAX_FILE_SIZE: Long = 100 * 1024 * 1024  // 100MB
        private const val MAX_PATH_LENGTH: Int = 4096

        fun createFromParameters(
            parameters: List<ConfigurationParameter>,
            readGuard: FileReadGuard = FileReadGuard.DISABLED
        ): SafeFileTools {
            val workingDir = parameters.parameter("working_dir", "")
            val outputDir = parameters.parameter("output_dir", "")

            if (workingDir.isBlank()) {
                return SafeFileTools(readGuard = readGuard)
            }

            val allowedDirs = mutableListOf<File>()
            allowedDirs += File(workingDir)
            if (outputDir.isNotBlank()) {
                allowedDirs += File(outputDir)
            }
            allowedDirs += File(System.getProperty("java.io.tmpdir", "/tmp"))
            return SafeFileTools(readGuard = readGuard, allowedDirectories = allowedDirs)
        }
    }
}
