package com.fartech.agents.runner

import com.fartech.agents.tools.FileReadGuard
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Runner-only confinement for the `file_system` agent tools. The server keeps its own providers.
 *
 * Reachable: only the run directories of this root execution and its child executions,
 * `<familyBase>/<root>` and `<familyBase>/<root>__*`, where familyBase is the execution directory's
 * `.workflow-runs/<owner>` (the frozen directory isolation). Never reachable: anything else in the
 * execution directory (`.runner-state`, `.runner-private`, bundle definitions, `skills/`, `.runtime/`,
 * inputs), the persistence `.snapshots` directory, the JVM temp directory and the rest of the host.
 * Exception: the bound input files this run names ([readOnlyInputs], execution-relative, e.g.
 * `inputs/uploads/<variable>/<name>`) can be read, exactly those files, never written, listed or created
 * beside; a read checks the frozen size (and the whole-file read the frozen sha256).
 *
 * Code steps run concurrently as the same uid and may plant links, so every operation:
 * - walks each path component below the execution directory with NOFOLLOW_LINKS and refuses any
 *   symbolic link (and any non-directory ancestor), then re-walks after opening and compares the
 *   directories' file keys to detect a swap;
 * - opens the final component with NOFOLLOW_LINKS and refuses a regular file whose link count is
 *   above one (a hard link to a file outside the tree);
 * - writes through a new, exclusively created sibling file that is renamed over the target, so an
 *   existing link at the target is replaced, never written through.
 * The JVM has no openat(2), so the post-open re-walk narrows but cannot fully close a rename race
 * between the walk and the open; the attempt-private directories make that race the code step's own.
 *
 * Reads are bounded per call; writes share one per-attempt byte quota.
 */
class RunnerFileConfinement(
    executionDirectory: Path,
    familyBase: Path,
    rootExecutionSegment: String,
    private val guard: () -> Unit = {},
    val readLimitBytes: Int = READ_LIMIT_BYTES,
    private val writeQuotaBytes: Long = WRITE_QUOTA_BYTES,
    /** Execution-relative path -> (size, sha256) of the bound inputs agents may read (FrozenWorkflowBundle.boundInputFiles). */
    private val readOnlyInputs: Map<String, Pair<Long, String>> = emptyMap()
) {
    private val executionRoot: Path = executionDirectory.toAbsolutePath().normalize()
    private val realExecutionRoot: Path = executionDirectory.toRealPath()
    private val familyRelative: Path = executionRoot.relativize(familyBase.toAbsolutePath().normalize())
    private val realFamilyBase: Path = realExecutionRoot.resolve(familyRelative).normalize()
    private val rootSegment = rootExecutionSegment
    private val written = AtomicLong()
    private val readGuard = FileReadGuard(enabled = true)

    init {
        require(familyRelative.toString().isNotEmpty() && !familyRelative.isAbsolute && familyRelative.none { it.toString() == ".." }) { "Run directory family must be inside the execution directory" }
        require(rootSegment.isNotBlank() && '/' !in rootSegment && rootSegment != "." && rootSegment != "..") { "Invalid root execution segment" }
    }

    /** A located path: [path] is under the real execution directory, [relative] is relative to the family base
     * (for a bound input: to the execution directory). A [readOnly] target is a bound input of [expected] size/sha. */
    class Target internal constructor(val path: Path, val relative: String, internal val readOnly: Boolean = false,
                                      internal val expected: Pair<Long, String>? = null)

    private fun refuseReadOnly(target: Target) { if (target.readOnly) throw SecurityException("Bound input files are read-only") }

    fun bytesWritten(): Long = written.get()

    /** Lexical checks only; [workingDirectory] resolves relative names. Filesystem state is checked per operation. */
    fun locate(raw: String, workingDirectory: Path): Target {
        var text = raw.trim()
        while (text.length >= 2 && ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith('\'') && text.endsWith('\'')) || (text.startsWith('`') && text.endsWith('`')))) {
            text = text.substring(1, text.length - 1).trim()
        }
        if (text.isEmpty() || text.length > 4096 || '\u0000' in text || '\\' in text) throw SecurityException("Invalid path")
        val requested = Path.of(text)
        if (requested.any { it.toString() == ".." }) throw SecurityException("Parent directory references are not allowed")
        val absolute = rebase(if (requested.isAbsolute) requested.normalize() else rebase(workingDirectory.toAbsolutePath().normalize()).resolve(requested).normalize())
        if (readOnlyInputs.isNotEmpty() && absolute.startsWith(realExecutionRoot) && absolute != realExecutionRoot) {
            val executionRelative = realExecutionRoot.relativize(absolute).toString()
            readOnlyInputs[executionRelative]?.let { return Target(absolute, executionRelative, readOnly = true, expected = it) }
        }
        if (!absolute.startsWith(realFamilyBase) || absolute == realFamilyBase) throw SecurityException(outside())
        val relative = realFamilyBase.relativize(absolute)
        val first = relative.getName(0).toString()
        if (first != rootSegment && !first.startsWith(rootSegment + "__")) throw SecurityException(outside())
        if (relative.any { it.toString() == ".snapshots" || it.toString().startsWith(".runner") }) throw SecurityException(outside())
        return Target(absolute, relative.toString())
    }

    private fun rebase(path: Path): Path = when {
        path.startsWith(realExecutionRoot) -> path
        path.startsWith(executionRoot) -> realExecutionRoot.resolve(executionRoot.relativize(path)).normalize()
        else -> path
    }

    private fun outside() = "Path is outside this run's directories. Use paths under the step workspace or the run output directory."

    /** NOFOLLOW walk from the execution directory to [directory]; returns the directories' file keys. */
    private fun walk(directory: Path, create: Boolean): List<Any?> {
        val keys = ArrayList<Any?>()
        var current = realExecutionRoot
        for (component in realExecutionRoot.relativize(directory)) {
            current = current.resolve(component)
            val attributes = try {
                Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                if (!create) throw NoSuchFileException(component.toString())
                try { Files.createDirectory(current) } catch (_: FileAlreadyExistsException) {}
                Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
            if (attributes.isSymbolicLink) throw SecurityException("Symbolic links are not followed")
            if (!attributes.isDirectory) throw SecurityException("A path component is not a directory")
            keys.add(attributes.fileKey())
        }
        return keys
    }

    private fun recheck(directory: Path, keys: List<Any?>) {
        if (walk(directory, create = false) != keys) throw SecurityException("Directory changed during the operation")
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun linkCount(path: Path): Int = (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt()

    private fun requireSingleLinkRegular(path: Path): BasicFileAttributes {
        val attributes = attributes(path) ?: throw NoSuchFileException(path.fileName.toString())
        if (attributes.isSymbolicLink) throw SecurityException("Symbolic links are not followed")
        if (!attributes.isRegularFile) throw SecurityException("Not a regular file")
        if (linkCount(path) != 1) throw SecurityException("Files with more than one hard link are refused")
        return attributes
    }

    /** Opens [target] for reading after the NOFOLLOW checks, and verifies the opened file is the checked one. */
    private fun <T> openChecked(target: Target, body: (SeekableByteChannel, BasicFileAttributes) -> T): T {
        guard()
        readGuard.validateReadFile(target.path.toFile())
        val keys = walk(target.path.parent, create = false)
        val before = requireSingleLinkRegular(target.path)
        return Files.newByteChannel(target.path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val after = requireSingleLinkRegular(target.path)
            if (after.fileKey() != before.fileKey()) throw SecurityException("File changed during the operation")
            recheck(target.path.parent, keys)
            target.expected?.let { if (after.size() != it.first) throw SecurityException("Bound input file changed") }
            body(channel, after)
        }
    }

    /** Whole-file read, refused above [readLimitBytes]. */
    fun read(target: Target): ByteArray = readWithLimit(target, readLimitBytes)

    /** Bounded binary IO for host-adapted format tools; the text tools keep their smaller limit. */
    fun readBinary(target: Target, maxBytes: Int = BINARY_READ_LIMIT_BYTES): ByteArray {
        require(maxBytes in 1..BINARY_READ_LIMIT_BYTES) { "Invalid binary read limit" }
        return readWithLimit(target, maxBytes)
    }

    private fun readWithLimit(target: Target, limit: Int): ByteArray = openChecked(target) { channel, attributes ->
        if (attributes.size() > limit) throw SecurityException("File is ${attributes.size()} bytes; the read limit is $limit bytes per call. Use readFileRange for part of it.")
        val bytes = readBounded(channel, limit) ?: throw SecurityException("File grew past the read limit of $limit bytes")
        target.expected?.let { if (sha256(bytes) != it.second) throw SecurityException("Bound input file changed") }
        guard()
        bytes
    }

    private fun readBounded(channel: SeekableByteChannel, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (true) {
            guard()
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) return null
            out.write(buffer.array(), 0, n)
        }
    }

    /** Lines [startLine]..[endLine] (1-based, inclusive, -1 = end), streamed; output stops at [readLimitBytes]. */
    fun readLines(target: Target, startLine: Int, endLine: Int): Pair<List<String>, Boolean> = openChecked(target) { channel, _ ->
        val reader = java.io.BufferedReader(java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(channel), Charsets.UTF_8))
        val lines = ArrayList<String>(); var number = 0; var bytes = 0L; var truncated = false
        while (true) {
            val line = reader.readLine() ?: break
            number++
            if (number < startLine) continue
            if (endLine in 0 until number) break
            bytes += line.toByteArray(Charsets.UTF_8).size + 1
            if (bytes > readLimitBytes) { truncated = true; break }
            lines.add(line)
        }
        lines to truncated
    }

    /** Writes through an exclusive sibling file renamed over the target. Returns bytes written. */
    fun write(target: Target, data: ByteArray, append: Boolean): Long {
        guard()
        refuseReadOnly(target)
        val name = target.path.fileName.toString()
        if (name.startsWith(".") || BLOCKED_WRITE_NAMES.any { it.matches(name.lowercase(java.util.Locale.ROOT)) }) {
            throw SecurityException("Writing '$name' is not allowed: hidden and key or certificate file names are reserved")
        }
        val parent = target.path.parent
        val keys = walk(parent, create = true)
        val existing = attributes(target.path)
        if (existing != null && (existing.isSymbolicLink || !existing.isRegularFile)) throw SecurityException("Target is not a regular file")
        val previous = if (append && existing != null) read(target) else ByteArray(0)
        val total = previous.size.toLong() + data.size
        reserve(total)
        val temporary = parent.resolve(".${name.take(64)}.${UUID.randomUUID()}.partial")
        try {
            Files.newByteChannel(temporary, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                for (chunk in listOf(previous, data)) {
                    val buffer = ByteBuffer.wrap(chunk)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
            }
            recheck(parent, keys)
            guard()
            Files.move(temporary, target.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(temporary) }
            written.addAndGet(-total)
            throw failure
        }
        return total
    }

    private fun reserve(bytes: Long) {
        while (true) {
            val used = written.get()
            if (used + bytes > writeQuotaBytes) throw SecurityException("Write quota exhausted: $used of $writeQuotaBytes bytes already written in this attempt")
            if (written.compareAndSet(used, used + bytes)) return
        }
    }

    fun createDirectory(target: Target) {
        guard()
        refuseReadOnly(target)
        if (target.path.fileName.toString().startsWith(".")) throw SecurityException("Hidden directory names are reserved")
        walk(target.path, create = true)
    }

    data class Entry(val name: String, val directory: Boolean, val size: Long)

    fun list(target: Target, limit: Int = 1000): Pair<List<Entry>, Boolean> {
        guard()
        if (target.readOnly) throw SecurityException("A bound input is a file, not a directory")
        val keys = walk(target.path, create = false)
        val entries = ArrayList<Entry>(); var more = false
        Files.newDirectoryStream(target.path).use { stream ->
            for (child in stream) {
                val attributes = attributes(child) ?: continue
                if (attributes.isSymbolicLink) continue
                if (entries.size >= limit) { more = true; break }
                entries.add(Entry(child.fileName.toString(), attributes.isDirectory, if (attributes.isDirectory) 0 else attributes.size()))
            }
        }
        recheck(target.path, keys)
        return entries.sortedBy { it.name } to more
    }

    fun info(target: Target): BasicFileAttributes {
        guard()
        walk(target.path.parent, create = false)
        val attributes = attributes(target.path) ?: throw NoSuchFileException(target.relative)
        if (attributes.isSymbolicLink) throw SecurityException("Symbolic links are not followed")
        return attributes
    }

    companion object {
        /** The file tools can read this run's bound attachments and saved documents (read-only, exact files). */
        const val INPUTS_CAPABILITY = "tool.file_system.inputs.v1"
        const val READ_LIMIT_BYTES: Int = 2 * 1024 * 1024
        const val BINARY_READ_LIMIT_BYTES: Int = 64 * 1024 * 1024
        const val WRITE_QUOTA_BYTES: Long = 256L * 1024 * 1024
        private val BLOCKED_WRITE_NAMES = listOf(Regex("""^id_[a-z0-9_]+$"""), Regex(""".*\.(?:pem|key|p12|pfx|jks|keystore)$"""),
            Regex("""^(?:known_hosts|authorized_keys|credentials)$"""))
        /** NOFOLLOW link counts need the POSIX `unix` attribute view; without it the tools are not offered. */
        fun available(): Boolean = "unix" in FileSystems.getDefault().supportedFileAttributeViews()

        internal fun ioMessage(failure: IOException): String = when (failure) {
            is NoSuchFileException -> "No such file or directory"
            is FileAlreadyExistsException -> "Already exists"
            is AccessDeniedException -> "Permission denied"
            is DirectoryNotEmptyException -> "Directory not empty"
            else -> failure.javaClass.simpleName
        }
    }
}
