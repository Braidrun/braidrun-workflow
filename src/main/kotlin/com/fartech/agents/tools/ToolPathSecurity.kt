package com.fartech.agents.tools

import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Centralized output path validation for all agent tools.
 *
 * Prevents directory traversal, symlink escapes, and writes outside the working directory.
 * All tool functions that write to LLM-specified paths should call [validateOutputPath]
 * before performing any file operations.
 */
object ToolPathSecurity {

    private val workingDirectory: File by lazy {
        File(System.getProperty("user.dir")).canonicalFile
    }

    private val tempDirectory: File by lazy {
        File(System.getProperty("java.io.tmpdir", "/tmp")).canonicalFile
    }

    /**
     * Validates that [outputPath] resolves to a location within the current working directory.
     * Creates parent directories if they don't exist.
     *
     * @param outputPath The LLM-provided output file path
     * @return The validated canonical File
     * @throws SecurityException if the path escapes the working directory
     */
    fun validateOutputPath(outputPath: String): File {
        require(outputPath.isNotBlank()) { "Output path must not be blank" }

        val file = File(outputPath)
        // Resolve to canonical to follow symlinks and normalize ../ etc.
        val canonical = if (file.isAbsolute) {
            file.canonicalFile
        } else {
            workingDirectory.resolve(file.path).canonicalFile
        }

        val workingPath = workingDirectory.toPath()
        val tempPath = tempDirectory.toPath()
        val candidatePath = canonical.toPath()
        val inWorkingDir = candidatePath == workingPath || candidatePath.startsWith(workingPath)
        val inTempDir = candidatePath == tempPath || candidatePath.startsWith(tempPath)
        if (!inWorkingDir && !inTempDir) {
            logger.warn { "[ToolPathSecurity] Output path escapes allowed directories: $outputPath -> $canonical" }
            throw SecurityException(
                "Output path '$outputPath' resolves to '$canonical' which is outside the allowed directories " +
                "('$workingDirectory' or '$tempDirectory'). All output files must be within these directories."
            )
        }

        // Ensure parent directories exist
        canonical.parentFile?.mkdirs()
        return canonical
    }

    /**
     * Validates that [inputPath] resolves to a location within the working directory
     * or the system temp directory. Unlike [validateOutputPath] this does NOT create
     * any parent directory — it's for paths that are expected to already exist.
     *
     * Intended for tools that read LLM-controlled paths (image processing, PDF, office,
     * OCR, etc.) so that the LLM cannot hand us `/etc/passwd` via a tool call.
     *
     * @return the canonical [File] if the path is safe.
     * @throws SecurityException if the canonical path escapes both working and temp
     *   directories.
     */
    fun validateInputPath(inputPath: String): File {
        require(inputPath.isNotBlank()) { "Input path must not be blank" }

        val file = File(inputPath)
        val canonical = if (file.isAbsolute) {
            file.canonicalFile
        } else {
            workingDirectory.resolve(file.path).canonicalFile
        }

        val workingPath = workingDirectory.toPath()
        val tempPath = tempDirectory.toPath()
        val candidatePath = canonical.toPath()
        val inWorkingDir = candidatePath == workingPath || candidatePath.startsWith(workingPath)
        val inTempDir = candidatePath == tempPath || candidatePath.startsWith(tempPath)
        if (!inWorkingDir && !inTempDir) {
            logger.warn { "[ToolPathSecurity] Input path escapes allowed directories: $inputPath -> $canonical" }
            throw SecurityException(
                "Input path '$inputPath' resolves to '$canonical' which is outside the allowed " +
                    "directories ('$workingDirectory' or '$tempDirectory')."
            )
        }
        return canonical
    }

    /**
     * Sanitizes a name to be safe for use as a single path segment (file or directory name).
     * Removes path separators, traversal sequences, and control characters.
     */
    fun sanitizePathSegment(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f]"), "_")
            .replace("..", "_")
            .trimStart('.')
            .trim()
            .take(255)
            .ifEmpty { "unnamed" }
    }
}
