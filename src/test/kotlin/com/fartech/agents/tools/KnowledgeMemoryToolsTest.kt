package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeMemoryToolsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `concurrent saveMemory calls keep all entries and valid json`() = runBlocking {
        val tools = KnowledgeMemoryTools(
            parameters = listOf(
                ConfigurationParameter("memory_storage_dir", JsonPrimitive(tempDir.toString()))
            )
        )

        (1..50).map { index ->
            async(Dispatchers.Default) {
                tools.saveMemory("key-$index", "value-$index")
            }
        }.awaitAll()

        val summary = tools.listMemories()
        assertTrue(summary.contains("Stored memories (50):"))

        val memoryFile = tempDir.resolve("memory.json")
        val json = Json.parseToJsonElement(memoryFile.readText())
        val entries = json.jsonObject["entries"]?.jsonObject
        assertEquals(50, entries?.size)
    }

    @Test
    fun `saveNote rejects content over per-entry cap`() {
        val tools = KnowledgeMemoryTools(
            parameters = listOf(
                ConfigurationParameter("memory_storage_dir", JsonPrimitive(tempDir.toString())),
                ConfigurationParameter("memory_max_entry_bytes", JsonPrimitive("32"))
            )
        )

        val result = tools.saveNote("large-note", "Large", "x".repeat(100))

        assertTrue(result.contains("Note rejected"))
        assertTrue(result.contains("per-entry limit"))
    }

    @Test
    fun `saveNote rejects writes that exceed store cap`() {
        val tools = KnowledgeMemoryTools(
            parameters = listOf(
                ConfigurationParameter("memory_storage_dir", JsonPrimitive(tempDir.toString())),
                ConfigurationParameter("memory_max_entry_bytes", JsonPrimitive("1024")),
                ConfigurationParameter("memory_max_store_bytes", JsonPrimitive("80"))
            )
        )

        val first = tools.saveNote("n1", "First", "x".repeat(20))
        val second = tools.saveNote("n2", "Second", "y".repeat(20))

        assertTrue(first.contains("Note saved"))
        assertTrue(second.contains("Note rejected"))
        assertTrue(second.contains("total store size"))
    }
}
