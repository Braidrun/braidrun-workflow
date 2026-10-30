package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException
import kotlin.test.*

/** In-memory byte adapter only: no host filesystem or native process access. */
class KnowledgeMemoryConfinedTest {
    private class Files : ToolFileAccess {
        val content = mutableMapOf<String, ByteArray>()
        val reads = mutableListOf<Pair<String, Int>>()
        val writes = mutableListOf<String>()
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        override fun readBytes(path: String, maxBytes: Int): ByteArray {
            reads += path to maxBytes
            readFailure?.let { throw it }
            return content[path]?.copyOf() ?: throw NoSuchFileException(path)
        }
        override fun writeBytes(path: String, bytes: ByteArray) {
            writeFailure?.let { throw it }
            writes += path
            content[path] = bytes.copyOf()
        }
    }

    private val root = "agent-memory/step-a"
    private fun tools(files: Files, guard: () -> Unit = {}, directory: String = root,
                      parameters: List<ConfigurationParameter> = emptyList()) = KnowledgeMemoryTools(
        parameters, KnowledgeMemoryTools.ConfinedStorage(files, directory, guard)
    )

    @Test
    fun `host directory wins over workflow storage namespace and limits`() {
        val files = Files()
        val tool = tools(files, parameters = listOf(
            ConfigurationParameter("memory_storage_dir", JsonPrimitive("/must-not-create-host-directory")),
            ConfigurationParameter("memory_namespace", JsonPrimitive("../../outside")),
            ConfigurationParameter("memory_max_entry_bytes", JsonPrimitive("999999999")),
            ConfigurationParameter("memory_max_store_bytes", JsonPrimitive("999999999"))
        ))
        assertContains(tool.saveMemory("theme", "dark"), "Memory saved")
        assertContains(tool.saveNote("n1", "Title", "Content"), "Note saved")
        assertEquals(setOf("$root/memory.json", "$root/notes.json"), files.content.keys)
        assertTrue(files.reads.all { it.second == KnowledgeMemoryTools.RUNNER_MAX_STORE_BYTES })
        assertFailsWith<IllegalArgumentException> {
            tool.saveMemory("large", "x".repeat(KnowledgeMemoryTools.RUNNER_MAX_ENTRY_BYTES + 1))
        }
    }

    @Test
    fun `only definite absence creates a fresh store and read alone never writes`() {
        val files = Files()
        val tool = tools(files)
        assertContains(tool.listMemories(), "No memories stored")
        assertContains(tool.listNotes(), "No notes stored")
        assertTrue(files.writes.isEmpty())
        tool.saveMemory("key", "value")
        tool.saveNote("id", "title", "content")
        assertEquals(listOf("$root/memory.json", "$root/notes.json"), files.writes)
    }

    @Test
    fun `corrupt stores never get silently replaced`() {
        for ((name, operation) in listOf<Pair<String, (KnowledgeMemoryTools) -> String>>(
            "memory.json" to { it.saveMemory("new", "value") },
            "notes.json" to { it.saveNote("new", "title", "content") }
        )) {
            val files = Files()
            val original = "not-valid-json".toByteArray()
            files.content["$root/$name"] = original
            assertFailsWith<SerializationException> { operation(tools(files)) }
            assertTrue(files.writes.isEmpty())
            assertContentEquals(original, files.content["$root/$name"])
        }
    }

    @Test
    fun `invalid utf8 and unknown store schema are errors not empty stores`() {
        val files = Files()
        files.content["$root/memory.json"] = byteArrayOf(0xc3.toByte(), 0x28)
        assertFailsWith<CharacterCodingException> { tools(files).listMemories() }
        files.content["$root/memory.json"] = "{\"unknown\":{}}".toByteArray()
        assertFailsWith<SerializationException> { tools(files).saveMemory("key", "value") }
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `permission confinement cancellation and read failures propagate unchanged`() {
        for (failure in listOf(IOException("disk unavailable"), SecurityException("outside confinement"),
                               CancellationException("cancelled"))) {
            val files = Files().apply { readFailure = failure }
            assertSame(failure, assertFails { tools(files).saveMemory("key", "value") })
            assertSame(failure, assertFails { tools(files).saveNote("id", "title", "content") })
            assertTrue(files.writes.isEmpty())
        }
    }

    @Test
    fun `write failure propagates without a local io fallback`() {
        val failure = SecurityException("quota exhausted")
        val files = Files().apply { writeFailure = failure }
        assertSame(failure, assertFails { tools(files).saveMemory("key", "value") })
        assertTrue(files.content.isEmpty())
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `revoked business fence blocks before io and after a read`() {
        val files = Files()
        val revoked = CancellationException("lease lost")
        assertSame(revoked, assertFails { tools(files, guard = { throw revoked }).listMemories() })
        assertTrue(files.reads.isEmpty())
        var guards = 0
        val tool = tools(files, guard = { if (++guards == 3) throw revoked })
        assertSame(revoked, assertFails { tool.saveMemory("key", "value") })
        assertEquals(1, files.reads.size)
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `runner returns bounded valid utf8 for recalls and searches`() {
        val files = Files()
        val tool = tools(files)
        tool.saveMemory("key", "界🙂".repeat(5000))
        for (result in listOf(tool.recallMemory("key"), tool.searchMemory(""))) {
            assertTrue(result.toByteArray(Charsets.UTF_8).size <= KnowledgeMemoryTools.RUNNER_MAX_RESULT_BYTES)
            assertContains(result, "Result truncated")
            assertFalse(result.contains('\uFFFD'))
        }
    }

    @Test
    fun `serialized entry expansion tags and identifiers are bounded before write`() {
        for (operation in listOf<(KnowledgeMemoryTools) -> String>(
            { it.saveMemory("key", "\u0000".repeat(12000)) },
            { it.saveMemory("key", "value", (1..33).joinToString(",")) },
            { it.saveMemory("k".repeat(257), "value") },
            { it.saveNote("id", "x".repeat(1025), "content") }
        )) {
            val files = Files()
            assertFailsWith<IllegalArgumentException> { operation(tools(files)) }
            assertTrue(files.writes.isEmpty())
        }
    }

    @Test
    fun `oversized bytes and excessive stored entry count cannot be loaded or overwritten`() {
        val files = Files()
        files.content["$root/memory.json"] = ByteArray(KnowledgeMemoryTools.RUNNER_MAX_STORE_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { tools(files).saveMemory("key", "value") }
        files.content["$root/memory.json"] = buildJsonObject {
            putJsonObject("entries") {
                repeat(KnowledgeMemoryTools.RUNNER_MAX_ENTRIES + 1) { index ->
                    putJsonObject("k$index") {
                        put("key", "k$index"); put("value", "v"); put("createdAt", "now"); put("updatedAt", "now")
                    }
                }
            }
        }.toString().toByteArray()
        assertFailsWith<IllegalArgumentException> { tools(files).saveMemory("key", "value") }
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `stored keys must match their declared identity`() {
        val files = Files()
        files.content["$root/memory.json"] = """{"entries":{"outer":{"key":"different","value":"v","createdAt":"now","updatedAt":"now"}}}""".toByteArray()
        assertFailsWith<IllegalArgumentException> { tools(files).recallMemory("outer") }
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `different host step directories never share memory or notes`() {
        val files = Files()
        tools(files).saveMemory("key", "step-a")
        tools(files).saveNote("note", "title", "step-a")
        val second = tools(files, directory = "agent-memory/step-b")
        assertContains(second.recallMemory("key"), "No memory found")
        assertContains(second.readNote("note"), "Note not found")
        second.saveMemory("key", "step-b")
        assertContains(tools(files).recallMemory("key"), "step-a")
        assertContains(second.recallMemory("key"), "step-b")
    }

    @Test
    fun `invalid fixed directories are rejected before io`() {
        for (directory in listOf("", ".", "../other", "/absolute", "a/../b", ".hidden", "a\\b")) {
            assertFailsWith<IllegalArgumentException> { tools(Files(), directory = directory) }
        }
    }
}
