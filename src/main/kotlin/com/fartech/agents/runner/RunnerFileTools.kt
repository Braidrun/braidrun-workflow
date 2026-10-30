package com.fartech.agents.runner

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.tools.SafeFileTools
import java.io.IOException
import java.nio.file.Path

/**
 * The `file_system` tool group on the Runner. Tool names and parameters follow the library's
 * `readFile` / `writeFile` / `readFileRange` / `createDirectory` / `getFileInfo` so prompts work
 * unchanged; every path goes through [RunnerFileConfinement]. No shell, web, delete, copy or move.
 * Every result is redacted before the model sees it, and every operation is audited.
 */
class RunnerFileTools(
    private val files: RunnerFileConfinement,
    private val workingDirectory: Path,
    private val redact: (String) -> String,
    private val audit: (operation: String, path: String, bytes: Long, sha256: String?, outcome: String) -> Unit
) : ToolSet {
    private inline fun operation(name: String, rawPath: String, block: (RunnerFileConfinement.Target) -> Pair<String, Pair<Long, String?>>): String {
        var relative = rawPath.take(256)
        return try {
            val target = files.locate(rawPath, workingDirectory)
            relative = target.relative
            val (message, measured) = block(target)
            audit(name, relative, measured.first, measured.second, "ok")
            redact(message)
        } catch (failure: RunnerAuthorityLost) {
            throw failure
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: SecurityException) {
            audit(name, relative, 0, null, "refused")
            redact("Error: ${failure.message}")
        } catch (failure: IOException) {
            audit(name, relative, 0, null, "failed")
            redact("Error: ${RunnerFileConfinement.ioMessage(failure)}: $relative")
        } catch (failure: IllegalArgumentException) {
            audit(name, relative, 0, null, "refused")
            redact("Error: ${failure.message}")
        }
    }

    @Tool
    @LLMDescription("Read a text file of this run (at most 2 MiB per call), including the attached input files whose paths the input gives (read-only). Returns the file content.")
    fun readFile(
        @LLMDescription("File path to read (absolute, or relative to the step workspace)") path: String,
    ): String = operation("read", path) { target ->
        val bytes = files.read(target)
        bytes.toString(Charsets.UTF_8) to (bytes.size.toLong() to null)
    }

    @Tool
    @LLMDescription("Read a range of lines from a text file of this run. Useful for files larger than the 2 MiB read limit.")
    fun readFileRange(
        @LLMDescription("Path to the file") path: String,
        @LLMDescription("Starting line number (1-based)") startLine: Int = 1,
        @LLMDescription("Ending line number (1-based, inclusive). Use -1 for end of file.") endLine: Int = -1,
    ): String = operation("read_range", path) { target ->
        val (lines, truncated) = files.readLines(target, startLine.coerceAtLeast(1), endLine)
        val text = lines.joinToString("\n")
        val note = if (truncated) "\n[output stopped at the ${files.readLimitBytes}-byte read limit; request a later startLine]" else ""
        "Lines $startLine-${startLine + lines.size - 1}:\n$text$note" to (text.toByteArray().size.toLong() to null)
    }

    @Tool
    @LLMDescription("Write text content to a file of this run. Accepts `path` or `filePath` for the target file. Parent directories are created.")
    fun writeFile(
        @LLMDescription("Target file path (absolute, or relative to the step workspace). Do NOT wrap with quotes.") path: String? = null,
        @LLMDescription("Text content to write to the file") content: String,
        @LLMDescription("Append to file instead of overwrite (default false)") append: Boolean? = false,
        @LLMDescription("Alias for `path`; accepted for models that emit filePath instead of path") filePath: String? = null,
    ): String {
        val raw = path?.takeIf { it.isNotBlank() } ?: filePath?.takeIf { it.isNotBlank() }
            ?: return "Error: missing required file path. Provide `path` or `filePath`."
        return operation("write", raw) { target ->
            val bytes = SafeFileTools.normalizeContent(content).toByteArray(Charsets.UTF_8)
            files.write(target, bytes, append == true)
            "Successfully wrote ${bytes.size} bytes to: ${target.path}" to (bytes.size.toLong() to sha256(bytes))
        }
    }

    @Tool
    @LLMDescription("List the entries of a directory of this run.")
    fun listDirectory(
        @LLMDescription("Directory path (absolute, or relative to the step workspace)") path: String,
    ): String = operation("list", path) { target ->
        val (entries, more) = files.list(target)
        val text = entries.joinToString("\n") { if (it.directory) "${it.name}/" else "${it.name} (${it.size} bytes)" } +
            (if (more) "\n[more entries not shown]" else "")
        text.ifEmpty { "(empty directory)" } to (entries.size.toLong() to null)
    }

    @Tool
    @LLMDescription("Create a directory of this run, including any necessary parent directories.")
    fun createDirectory(
        @LLMDescription("Path of the directory to create") path: String,
    ): String = operation("mkdir", path) { target ->
        files.createDirectory(target)
        "Directory ready: ${target.path}" to (0L to null)
    }

    @Tool
    @LLMDescription("Get the type and size of a file or directory of this run.")
    fun getFileInfo(
        @LLMDescription("Path to the file or directory") path: String,
    ): String = operation("info", path) { target ->
        val attributes = files.info(target)
        "Path: ${target.path}\nType: ${if (attributes.isDirectory) "Directory" else "File"}\nSize: ${attributes.size()} bytes\nModified: ${attributes.lastModifiedTime()}" to (0L to null)
    }
}
