package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

@Serializable
private data class MemoryEntry(
    val key: String,
    val value: String,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
private data class MemoryStore(
    val entries: MutableMap<String, MemoryEntry> = mutableMapOf()
)

@Serializable
private data class NoteEntry(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
private data class NoteStore(
    val notes: MutableMap<String, NoteEntry> = mutableMapOf()
)

private val confinedJson = Json { encodeDefaults = true }

private val jsonFormat = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@LLMDescription(
    "Knowledge and memory tools for persistent cross-session storage. " +
            "Provides key-value memory storage and note/knowledge management. " +
            "Data is persisted to local JSON files so it survives across sessions. " +
            "Useful for remembering user preferences, important facts, project context, and notes."
)
class KnowledgeMemoryTools @JvmOverloads constructor(
    private val parameters: List<ConfigurationParameter>,
    private val confinedStorage: ConfinedStorage? = null
) : ToolSet {

    /** Trusted host injection only: the directory is fixed per task/step, never a workflow parameter. */
    class ConfinedStorage(val files: ToolFileAccess, val directory: String, val guard: () -> Unit) {
        init {
            val path = Path.of(directory)
            require(directory.isNotBlank() && !path.isAbsolute && path.normalize().toString() == directory &&
                path.none { it.toString() == ".." || it.toString().startsWith(".") } &&
                !directory.contains('\\') && !directory.contains('\u0000')) { "Invalid confined memory directory" }
        }
    }

    companion object {
        const val RUNNER_MAX_ENTRY_BYTES = 64 * 1024
        const val RUNNER_MAX_STORE_BYTES = 2 * 1024 * 1024
        const val RUNNER_MAX_RESULT_BYTES = 32 * 1024
        const val RUNNER_MAX_ENTRIES = 512
        private const val TRUNCATED = "\n[Result truncated to the Runner memory output limit]"
    }

    private fun checkRunnerArguments(vararg values: String) {
        if (confinedStorage == null) return
        confinedStorage.guard()
        require(values.all { it.length <= RUNNER_MAX_ENTRY_BYTES } &&
            values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= RUNNER_MAX_ENTRY_BYTES) {
            "Runner memory arguments exceed the per-entry limit"
        }
    }

    private fun boundedResult(value: String): String {
        val storage = confinedStorage ?: return value
        storage.guard()
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= RUNNER_MAX_RESULT_BYTES) return value
        var end = RUNNER_MAX_RESULT_BYTES - TRUNCATED.toByteArray(Charsets.UTF_8).size
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + TRUNCATED
    }

    private fun readConfined(name: String): String? {
        val storage = requireNotNull(confinedStorage)
        storage.guard()
        val bytes = try {
            storage.files.readBytes("${storage.directory}/$name", RUNNER_MAX_STORE_BYTES)
        } catch (_: NoSuchFileException) {
            storage.guard()
            return null // Only definite absence may create a fresh store.
        }
        require(bytes.size <= RUNNER_MAX_STORE_BYTES) { "Runner memory store exceeds the byte limit" }
        storage.guard()
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun writeConfined(name: String, content: String) {
        val storage = requireNotNull(confinedStorage)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= RUNNER_MAX_STORE_BYTES) { "Runner memory store exceeds the byte limit" }
        storage.guard()
        storage.files.writeBytes("${storage.directory}/$name", bytes)
        storage.guard()
    }

    private fun validateTags(tags: List<String>) {
        require(tags.size <= 32 && tags.all { it.toByteArray(Charsets.UTF_8).size <= 256 }) {
            "Runner memory tags exceed the count or byte limit"
        }
    }

    private fun validateMemory(store: MemoryStore): MemoryStore {
        require(store.entries.size <= RUNNER_MAX_ENTRIES) { "Runner memory entry count limit exceeded" }
        store.entries.forEach { (key, entry) ->
            require(key == entry.key && key.toByteArray(Charsets.UTF_8).size <= 256 &&
                jsonFormat.encodeToString(entry).toByteArray(Charsets.UTF_8).size <= RUNNER_MAX_ENTRY_BYTES) {
                "Invalid or oversized Runner memory entry"
            }
            validateTags(entry.tags)
        }
        return store
    }

    private fun validateNotes(store: NoteStore): NoteStore {
        require(store.notes.size <= RUNNER_MAX_ENTRIES) { "Runner note count limit exceeded" }
        store.notes.forEach { (id, note) ->
            require(id == note.id && id.toByteArray(Charsets.UTF_8).size <= 256 &&
                note.title.toByteArray(Charsets.UTF_8).size <= 1024 &&
                jsonFormat.encodeToString(note).toByteArray(Charsets.UTF_8).size <= RUNNER_MAX_ENTRY_BYTES) {
                "Invalid or oversized Runner note entry"
            }
            validateTags(note.tags)
        }
        return store
    }

    private val memoryLock = ReentrantLock()
    private val noteLock = ReentrantLock()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Per-entry and per-store size caps (bytes, approximate via `String.length * 2`).
     * An LLM can be coaxed into writing megabyte-scale "memories" to the local store;
     * without a cap that leaks disk and, because the store is loaded in full on every
     * read, OOMs the JVM on the next agent turn. Defaults are 64 KB per entry and
     * 16 MB total — overridable via parameters.
     */
    private val maxEntryBytes: Long by lazy {
        if (confinedStorage != null) RUNNER_MAX_ENTRY_BYTES.toLong() else positiveLongParameter("memory_max_entry_bytes", 64L * 1024)
    }
    private val maxStoreBytes: Long by lazy {
        if (confinedStorage != null) RUNNER_MAX_STORE_BYTES.toLong() else positiveLongParameter("memory_max_store_bytes", 16L * 1024 * 1024)
    }

    /**
     * Optional namespace suffix that isolates the on-disk store by user/agent/session.
     * Prevents one caller from reading or overwriting another caller's memory when
     * multiple agent instances share the same backing directory.
     */
    private val namespace: String by lazy {
        val ns = parameters.parameter("memory_namespace", "").ifBlank { null }
        if (ns != null) ToolPathSecurity.sanitizePathSegment(ns) else ""
    }

    private fun getStorageDir(): File {
        val base = File(parameters.parameter("memory_storage_dir", ".agent_memory"))
        val dir = if (namespace.isNotEmpty()) File(base, namespace) else base
        dir.mkdirs()
        return dir
    }

    private fun getMemoryFile(): File = File(getStorageDir(), "memory.json")
    private fun getNotesFile(): File = File(getStorageDir(), "notes.json")

    private fun approxBytes(s: String): Long = s.length.toLong() * 2L // UTF-16 String estimate

    private fun positiveLongParameter(key: String, default: Long): Long {
        return parameters.parameter(key, default.toString()).toLongOrNull()?.takeIf { it > 0 } ?: default
    }

    private fun tagsApproxBytes(tags: List<String>): Long = tags.sumOf { approxBytes(it) }

    private fun memoryEntryApproxBytes(entry: MemoryEntry): Long =
        approxBytes(entry.key) + approxBytes(entry.value) + tagsApproxBytes(entry.tags)

    private fun noteEntryApproxBytes(entry: NoteEntry): Long =
        approxBytes(entry.id) + approxBytes(entry.title) + approxBytes(entry.content) + tagsApproxBytes(entry.tags)

    private fun loadMemoryStoreUnlocked(): MemoryStore {
        if (confinedStorage != null) {
            val content = readConfined("memory.json") ?: return MemoryStore()
            return validateMemory(confinedJson.decodeFromString<MemoryStore>(content))
        }
        val file = getMemoryFile()
        return if (file.exists()) {
            try {
                jsonFormat.decodeFromString<MemoryStore>(file.readText())
            } catch (e: Exception) {
                logger.debug(e) { "Corrupt memory store file, starting fresh" }
                MemoryStore()
            }
        } else {
            MemoryStore()
        }
    }

    private fun saveMemoryStoreUnlocked(store: MemoryStore) {
        if (confinedStorage != null) {
            writeConfined("memory.json", jsonFormat.encodeToString(validateMemory(store)))
            return
        }
        val file = getMemoryFile()
        writeJsonAtomically(file, jsonFormat.encodeToString(store))
    }

    private fun loadNoteStoreUnlocked(): NoteStore {
        if (confinedStorage != null) {
            val content = readConfined("notes.json") ?: return NoteStore()
            return validateNotes(confinedJson.decodeFromString<NoteStore>(content))
        }
        val file = getNotesFile()
        return if (file.exists()) {
            try {
                jsonFormat.decodeFromString<NoteStore>(file.readText())
            } catch (e: Exception) {
                logger.debug(e) { "Corrupt note store file, starting fresh" }
                NoteStore()
            }
        } else {
            NoteStore()
        }
    }

    private fun saveNoteStoreUnlocked(store: NoteStore) {
        if (confinedStorage != null) {
            writeConfined("notes.json", jsonFormat.encodeToString(validateNotes(store)))
            return
        }
        val file = getNotesFile()
        writeJsonAtomically(file, jsonFormat.encodeToString(store))
    }

    private fun writeJsonAtomically(file: File, content: String) {
        val parent = file.parentFile ?: getStorageDir()
        parent.mkdirs()

        val tempFile = File(parent, "${file.name}.${UUID.randomUUID()}.tmp")
        tempFile.writeText(content)

        try {
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                logger.debug(e) { "Atomic file move unsupported, using fallback" }
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private inline fun readMemoryStore(action: (MemoryStore) -> String): String =
        memoryLock.withLock { boundedResult(action(loadMemoryStoreUnlocked())) }

    private inline fun updateMemoryStore(action: (MemoryStore) -> String): String =
        memoryLock.withLock {
            val store = loadMemoryStoreUnlocked()
            val result = action(store)
            saveMemoryStoreUnlocked(store)
            boundedResult(result)
        }

    private inline fun readNoteStore(action: (NoteStore) -> String): String =
        noteLock.withLock { boundedResult(action(loadNoteStoreUnlocked())) }

    private inline fun updateNoteStore(action: (NoteStore) -> String): String =
        noteLock.withLock {
            val store = loadNoteStoreUnlocked()
            val result = action(store)
            saveNoteStoreUnlocked(store)
            boundedResult(result)
        }

    private fun now(): String = LocalDateTime.now().format(timestampFormatter)

    // ==================== Memory (Key-Value) ====================

    @Tool
    @LLMDescription(
        "Save a key-value pair to persistent memory. Use this to remember important information " +
                "across sessions, such as user preferences, project details, or any facts worth retaining."
    )
    fun saveMemory(
        @LLMDescription("Unique key to identify this memory (e.g., 'user_name', 'project_goal', 'preferred_language')")
        key: String,
        @LLMDescription("Value to store")
        value: String,
        @LLMDescription("Optional comma-separated tags for categorization (e.g., 'user,preference')")
        tags: String = ""
    ): String {
        checkRunnerArguments(key, value, tags)
        return try {
            // Per-entry cap — stop a chatty agent from stuffing a whole PDF into one entry.
            val requestedBytes = approxBytes(key) + approxBytes(value) +
                tags.split(",").filter { it.isNotBlank() }.sumOf { approxBytes(it.trim()) }
            if (requestedBytes > maxEntryBytes) {
                return "Memory rejected: entry size ${requestedBytes}B exceeds per-entry limit ${maxEntryBytes}B"
            }
            updateMemoryStore { store ->
                val tagList = if (tags.isNotBlank()) tags.split(",").map { it.trim() } else emptyList()
                val existing = store.entries[key]
                val timestamp = now()
                val entry = MemoryEntry(
                    key = key,
                    value = value,
                    tags = tagList,
                    createdAt = existing?.createdAt ?: timestamp,
                    updatedAt = timestamp
                )
                val candidate = store.entries.toMutableMap().apply { put(key, entry) }
                val candidateBytes = candidate.values.sumOf { memoryEntryApproxBytes(it) }
                if (candidateBytes > maxStoreBytes) {
                    return@updateMemoryStore "Memory rejected: total store size ${candidateBytes}B would exceed limit ${maxStoreBytes}B. " +
                        (if (confinedStorage != null) "Delete unused entries." else "Delete unused entries or raise `memory_max_store_bytes`.")
                }
                store.entries[key] = entry
                if (existing != null) {
                    "Memory updated: '$key' (previous value: '${existing.value}')"
                } else {
                    "Memory saved: '$key' = '$value'"
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error saving memory: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Recall a specific memory by its key.")
    fun recallMemory(
        @LLMDescription("Key of the memory to recall")
        key: String
    ): String {
        checkRunnerArguments(key)
        return try {
            readMemoryStore { store ->
                val entry = store.entries[key]
                if (entry != null) {
                    "Memory '$key': ${entry.value} (tags: ${entry.tags.joinToString(", ")}, last updated: ${entry.updatedAt})"
                } else {
                    "No memory found for key: '$key'"
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error recalling memory: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Search memories by keyword in keys, values, or tags. Returns all matching entries.")
    fun searchMemory(
        @LLMDescription("Search keyword (searches in key, value, and tags)")
        query: String
    ): String {
        checkRunnerArguments(query)
        return try {
            readMemoryStore { store ->
                val queryLower = query.lowercase()
                val matches = store.entries.values.filter { entry ->
                    entry.key.lowercase().contains(queryLower) ||
                            entry.value.lowercase().contains(queryLower) ||
                            entry.tags.any { it.lowercase().contains(queryLower) }
                }

                if (matches.isEmpty()) {
                    "No memories found matching '$query'"
                } else {
                    val sb = StringBuilder()
                    sb.appendLine("Found ${matches.size} memory/memories matching '$query':")
                    for (entry in matches) {
                        sb.appendLine("  - [${entry.key}]: ${entry.value} (tags: ${entry.tags.joinToString(", ")})")
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error searching memory: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("List all stored memories.")
    fun listMemories(
        @LLMDescription("Optional tag filter (only show memories with this tag)")
        tagFilter: String = ""
    ): String {
        checkRunnerArguments(tagFilter)
        return try {
            readMemoryStore { store ->
                val entries = if (tagFilter.isNotBlank()) {
                    store.entries.values.filter { it.tags.any { t -> t.equals(tagFilter, ignoreCase = true) } }
                } else {
                    store.entries.values.toList()
                }

                if (entries.isEmpty()) {
                    "No memories stored${if (tagFilter.isNotBlank()) " with tag '$tagFilter'" else ""}."
                } else {
                    val sb = StringBuilder()
                    sb.appendLine("Stored memories (${entries.size}):")
                    for (entry in entries.sortedBy { it.key }) {
                        sb.appendLine("  - [${entry.key}]: ${entry.value.take(100)}${if (entry.value.length > 100) "..." else ""}")
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error listing memories: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Delete a specific memory by key.")
    fun deleteMemory(
        @LLMDescription("Key of the memory to delete")
        key: String
    ): String {
        checkRunnerArguments(key)
        return try {
            updateMemoryStore { store ->
                val removed = store.entries.remove(key)
                if (removed != null) {
                    "Memory deleted: '$key'"
                } else {
                    "No memory found for key: '$key'"
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error deleting memory: ${e.message}"
        }
    }

    // ==================== Notes ====================

    @Tool
    @LLMDescription(
        "Save a note with a title and content. Notes are longer-form knowledge items " +
                "compared to key-value memories. Useful for storing meeting notes, research findings, " +
                "instructions, templates, etc."
    )
    fun saveNote(
        @LLMDescription("Unique ID for the note (e.g., 'meeting-2024-01-15', 'project-architecture')")
        id: String,
        @LLMDescription("Title of the note")
        title: String,
        @LLMDescription("Full content of the note (supports markdown)")
        content: String,
        @LLMDescription("Optional comma-separated tags (e.g., 'meeting,project-x')")
        tags: String = ""
    ): String {
        checkRunnerArguments(id, title, content, tags)
        return try {
            val requestedBytes = approxBytes(id) + approxBytes(title) + approxBytes(content) +
                tags.split(",").filter { it.isNotBlank() }.sumOf { approxBytes(it.trim()) }
            if (requestedBytes > maxEntryBytes) {
                return "Note rejected: entry size ${requestedBytes}B exceeds per-entry limit ${maxEntryBytes}B"
            }
            updateNoteStore { store ->
                val tagList = if (tags.isNotBlank()) tags.split(",").map { it.trim() } else emptyList()
                val existing = store.notes[id]
                val timestamp = now()
                val note = NoteEntry(
                    id = id,
                    title = title,
                    content = content,
                    tags = tagList,
                    createdAt = existing?.createdAt ?: timestamp,
                    updatedAt = timestamp
                )
                val candidate = store.notes.toMutableMap().apply { put(id, note) }
                val candidateBytes = candidate.values.sumOf { noteEntryApproxBytes(it) }
                if (candidateBytes > maxStoreBytes) {
                    return@updateNoteStore "Note rejected: total store size ${candidateBytes}B would exceed limit ${maxStoreBytes}B. " +
                        (if (confinedStorage != null) "Delete unused notes." else "Delete unused notes or raise `memory_max_store_bytes`.")
                }
                store.notes[id] = note
                if (existing != null) "Note updated: '$title'" else "Note saved: '$title'"
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error saving note: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Read a specific note by its ID.")
    fun readNote(
        @LLMDescription("ID of the note to read")
        id: String
    ): String {
        checkRunnerArguments(id)
        return try {
            readNoteStore { store ->
                val note = store.notes[id]
                if (note != null) {
                    val sb = StringBuilder()
                    sb.appendLine("Title: ${note.title}")
                    sb.appendLine("ID: ${note.id}")
                    sb.appendLine("Tags: ${note.tags.joinToString(", ")}")
                    sb.appendLine("Created: ${note.createdAt}")
                    sb.appendLine("Updated: ${note.updatedAt}")
                    sb.appendLine("---")
                    sb.appendLine(note.content)
                    sb.toString()
                } else {
                    "Note not found: '$id'"
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error reading note: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Search notes by keyword in title, content, or tags.")
    fun searchNotes(
        @LLMDescription("Search keyword")
        query: String
    ): String {
        checkRunnerArguments(query)
        return try {
            readNoteStore { store ->
                val queryLower = query.lowercase()
                val matches = store.notes.values.filter { note ->
                    note.title.lowercase().contains(queryLower) ||
                            note.content.lowercase().contains(queryLower) ||
                            note.tags.any { it.lowercase().contains(queryLower) }
                }

                if (matches.isEmpty()) {
                    "No notes found matching '$query'"
                } else {
                    val sb = StringBuilder()
                    sb.appendLine("Found ${matches.size} note(s) matching '$query':")
                    for (note in matches) {
                        sb.appendLine("  - [${note.id}] ${note.title} (tags: ${note.tags.joinToString(", ")})")
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error searching notes: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("List all stored notes with their titles and tags.")
    fun listNotes(
        @LLMDescription("Optional tag filter")
        tagFilter: String = ""
    ): String {
        checkRunnerArguments(tagFilter)
        return try {
            readNoteStore { store ->
                val notes = if (tagFilter.isNotBlank()) {
                    store.notes.values.filter { it.tags.any { t -> t.equals(tagFilter, ignoreCase = true) } }
                } else {
                    store.notes.values.toList()
                }

                if (notes.isEmpty()) {
                    "No notes stored${if (tagFilter.isNotBlank()) " with tag '$tagFilter'" else ""}."
                } else {
                    val sb = StringBuilder()
                    sb.appendLine("Notes (${notes.size}):")
                    for (note in notes.sortedByDescending { it.updatedAt }) {
                        sb.appendLine(
                            "  - [${note.id}] ${note.title} (updated: ${note.updatedAt}, tags: ${
                                note.tags.joinToString(
                                    ", "
                                )
                            })"
                        )
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error listing notes: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Delete a specific note by ID.")
    fun deleteNote(
        @LLMDescription("ID of the note to delete")
        id: String
    ): String {
        checkRunnerArguments(id)
        return try {
            updateNoteStore { store ->
                val removed = store.notes.remove(id)
                if (removed != null) {
                    "Note deleted: '${removed.title}'"
                } else {
                    "Note not found: '$id'"
                }
            }
        } catch (e: Exception) {
            if (confinedStorage != null) throw e
            "Error deleting note: ${e.message}"
        }
    }
}
