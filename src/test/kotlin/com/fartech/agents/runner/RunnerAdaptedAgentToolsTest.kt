package com.fartech.agents.runner

import com.fartech.agents.tools.KnowledgeMemoryTools
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RunnerAdaptedAgentToolsTest {
    private class Fixture(temp: Path, quota: Long = 256L * 1024 * 1024) {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/step/workspace"))
        var live = true
        val guard: () -> Unit = { if (!live) throw SecurityException("attempt revoked") }
        val files = RunnerFileConfinement(root, family, "root", writeQuotaBytes = quota, guard = guard)
        fun memory() = RunnerAdaptedAgentTools.create(setOf("knowledge_memory"), setOf("tool.knowledge_memory.v1"),
            listOf(ConfigurationParameter("memory_storage_dir", JsonPrimitive(root.resolve("outside").toString())),
                ConfigurationParameter("memory_namespace", JsonPrimitive("../../outside"))),
            files = { RunnerToolFileAccess(files, workspace) }, webHttp = { error("Memory must not create a network client") },
            guard = guard, redact = { it }).getValue("knowledge_memory").single() as KnowledgeMemoryTools
        fun store() = Files.walk(workspace).use { paths -> paths.filter { it.fileName.toString() == "memory.json" }.findFirst().orElseThrow() }
    }

    @Test fun `host factory refuses unnegotiated adapters before file or network access`() {
        assertFailsWith<IllegalArgumentException> {
            RunnerAdaptedAgentTools.create(setOf("web"), emptySet(), emptyList(),
                files = { error("No files before admission") }, webHttp = { error("No client before admission") }, guard = {}, redact = { it })
        }
        assertTrue(RunnerAdaptedAgentTools.create(setOf("exit"), emptySet(), emptyList(),
            files = { error("Unrequested files") }, webHttp = { error("Unrequested client") }, guard = {}, redact = { it }).isEmpty())
    }

    @Test fun `memory instances use isolated task stores and ignore workflow locations`(@TempDir temp: Path) {
        val f = Fixture(temp)
        val a = f.memory(); val b = f.memory()
        a.saveMemory("key", "first"); b.saveMemory("key", "second")
        assertContains(a.recallMemory("key"), "first"); assertContains(b.recallMemory("key"), "second")
        assertFalse(Files.exists(f.root.resolve("outside")))
        assertEquals(2L, Files.walk(f.workspace).use { paths -> paths.filter { it.fileName.toString() == "memory.json" }.count() })
        f.live = false
        assertFailsWith<SecurityException> { a.recallMemory("key") }
        assertFailsWith<SecurityException> { a.saveMemory("key", "changed") }
    }

    @Test fun `corrupt linked and replaced memory stores cannot escape or be overwritten`(@TempDir temp: Path) {
        val f = Fixture(temp); val tool = f.memory()
        tool.saveMemory("key", "original")
        val store = f.store(); val original = Files.readAllBytes(store)
        Files.writeString(store, "{broken")
        assertFails { tool.saveMemory("key", "changed") }
        assertEquals("{broken", Files.readString(store))
        Files.delete(store)
        val outside = Files.write(f.root.resolve("other.json"), original)
        Files.createSymbolicLink(store, outside)
        assertFailsWith<SecurityException> { tool.recallMemory("key") }
        assertFailsWith<SecurityException> { tool.saveMemory("key", "changed") }
        Files.delete(store); Files.createLink(store, outside)
        assertFailsWith<SecurityException> { tool.recallMemory("key") }
        assertFailsWith<SecurityException> { tool.saveMemory("key", "changed") }
        assertContentEquals(original, Files.readAllBytes(outside))
    }

    @Test fun `memory quota failure preserves the last committed store`(@TempDir temp: Path) {
        val f = Fixture(temp, 400); val tool = f.memory()
        tool.saveMemory("key", "original")
        val store = f.store(); val original = Files.readAllBytes(store)
        assertFailsWith<SecurityException> { tool.saveMemory("key", "x".repeat(500)) }
        assertContentEquals(original, Files.readAllBytes(store))
    }
}
