package com.fartech.agents.runner

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RunnerFileConfinementTest {
    private class Fixture(temp: Path, readLimit: Int = 64, quota: Long = 100) {
        val execution: Path = temp.toRealPath()
        val family: Path = execution.resolve(".workflow-runs/owner")
        val workspace: Path = Files.createDirectories(family.resolve("root/agent/workspace"))
        val output: Path = Files.createDirectories(family.resolve("root/output"))
        val state: Path = Files.createDirectories(execution.resolve(".runner-state"))
        val files = RunnerFileConfinement(execution, family, "root", readLimitBytes = readLimit, writeQuotaBytes = quota)
        val secrets = RunnerSecrets()
        val audits = mutableListOf<List<Any?>>()
        val tools = RunnerFileTools(files, workspace, secrets::redact) { operation, path, bytes, digest, outcome -> audits += listOf(operation, path, bytes, digest, outcome) }
        init { Files.writeString(state.resolve("checkpoint.json"), "{\"plaintext\":\"checkpoint\"}") }
    }

    @Test fun `run directories of this execution and its children are readable and writable`(@TempDir temp: Path) {
        val f = Fixture(temp)
        assertEquals(setOf("readFile", "readFileRange", "writeFile", "listDirectory", "createDirectory", "getFileInfo"),
            ai.koog.agents.core.tools.ToolRegistry { tools(f.tools) }.tools.map { it.name }.toSet())
        assertTrue(f.tools.writeFile(path = "notes/a.txt", content = "alpha").startsWith("Successfully"))
        assertEquals("alpha", Files.readString(f.workspace.resolve("notes/a.txt")))
        assertEquals("alpha", f.tools.readFile(f.workspace.resolve("notes/a.txt").toString()))
        assertTrue(f.tools.writeFile(path = f.output.resolve("report.md").toString(), content = "# out").startsWith("Successfully"))
        val child = f.family.resolve("root__nested__call_1/step/workspace/result.json")
        assertTrue(f.tools.writeFile(filePath = child.toString(), content = "{}").startsWith("Successfully"))
        assertTrue(f.tools.listDirectory(f.output.toString()).contains("report.md"))
        assertTrue(f.tools.writeFile(path = "notes/a.txt", content = "+beta", append = true).startsWith("Successfully"))
        assertEquals("alpha+beta", Files.readString(f.workspace.resolve("notes/a.txt")))
        assertTrue(f.tools.getFileInfo("notes").contains("Directory"))
    }

    @Test fun `parent references absolute paths outside the family and private namespaces are refused`(@TempDir temp: Path) {
        val f = Fixture(temp)
        Files.createDirectories(f.family.resolve("other/agent")); Files.writeString(f.family.resolve("other/agent/x.txt"), "other execution")
        Files.createDirectories(f.family.resolve("rootX")); Files.writeString(f.family.resolve("rootX/x.txt"), "prefix sibling")
        Files.createDirectories(f.execution.resolve(".workflow-runs/intruder/root")); Files.writeString(f.execution.resolve(".workflow-runs/intruder/root/x.txt"), "other owner")
        Files.createDirectories(f.execution.resolve("definitions")); Files.writeString(f.execution.resolve("definitions/root.yaml"), "name: x")
        Files.createDirectories(f.family.resolve("root/.snapshots")); Files.writeString(f.family.resolve("root/.snapshots/s.json"), "{}")
        val tmp = Files.createTempFile("runner-file-tools", ".txt").also { Files.writeString(it, "jvm temp") }
        try {
            for (path in listOf("../../../../.runner-state/checkpoint.json", "../output/report.md", f.state.resolve("checkpoint.json").toString(),
                    f.execution.resolve("definitions/root.yaml").toString(), f.family.resolve("other/agent/x.txt").toString(),
                    f.family.resolve("rootX/x.txt").toString(), f.execution.resolve(".workflow-runs/intruder/root/x.txt").toString(),
                    f.family.resolve("root/.snapshots/s.json").toString(), tmp.toString(), "/etc/hosts", f.family.toString())) {
                val result = f.tools.readFile(path)
                assertTrue(result.startsWith("Error:"), "$path -> $result")
                assertTrue(f.tools.writeFile(path = path, content = "overwrite").startsWith("Error:"), path)
            }
            assertEquals("{\"plaintext\":\"checkpoint\"}", Files.readString(f.state.resolve("checkpoint.json")))
            assertEquals("jvm temp", Files.readString(tmp))
            assertTrue(f.audits.all { it[4] == "refused" })
        } finally { Files.deleteIfExists(tmp) }
    }

    @Test fun `symlink components and symlink targets planted by a concurrent step are refused`(@TempDir temp: Path) {
        val f = Fixture(temp)
        Files.createSymbolicLink(f.workspace.resolve("state"), f.state)
        Files.createSymbolicLink(f.workspace.resolve("cp.json"), f.state.resolve("checkpoint.json"))
        Files.createSymbolicLink(f.family.resolve("root/linked"), f.execution)
        for (path in listOf("state/checkpoint.json", "cp.json", f.family.resolve("root/linked/.runner-state/checkpoint.json").toString())) {
            assertTrue(f.tools.readFile(path).startsWith("Error:"), path)
            assertTrue(f.tools.writeFile(path = path, content = "x").startsWith("Error:"), path)
        }
        assertTrue(f.tools.writeFile(path = "state/new.txt", content = "x").startsWith("Error:"))
        assertTrue(f.tools.listDirectory("state").startsWith("Error:"))
        assertFalse(Files.exists(f.state.resolve("new.txt")))
        assertEquals("{\"plaintext\":\"checkpoint\"}", Files.readString(f.state.resolve("checkpoint.json")))
        // Even the family root itself is walked without following links.
        Files.move(f.workspace, f.execution.resolve("moved"))
        Files.createSymbolicLink(f.workspace, f.state)
        assertTrue(f.tools.readFile("checkpoint.json").startsWith("Error:"))
    }

    @Test fun `hard links are never read and writes replace them instead of writing through`(@TempDir temp: Path) {
        val f = Fixture(temp)
        val outside = f.state.resolve("secret.txt").also { Files.writeString(it, "outside") }
        Files.createLink(f.workspace.resolve("hl.txt"), outside)
        assertTrue(f.tools.readFile("hl.txt").contains("hard link"))
        assertTrue(f.tools.readFileRange("hl.txt").startsWith("Error:"))
        assertTrue(f.tools.writeFile(path = "hl.txt", content = "+x", append = true).startsWith("Error:"))
        assertTrue(f.tools.writeFile(path = "hl.txt", content = "replaced").startsWith("Successfully"))
        assertEquals("outside", Files.readString(outside))
        assertEquals("replaced", Files.readString(f.workspace.resolve("hl.txt")))
    }

    @Test fun `reads are bounded per call and range reads stop at the bound`(@TempDir temp: Path) {
        val f = Fixture(temp)
        Files.writeString(f.workspace.resolve("big.txt"), (1..20).joinToString("\n") { "line-$it" })
        assertTrue(f.tools.readFile("big.txt").contains("read limit"))
        val range = f.tools.readFileRange("big.txt", 2, 3)
        assertTrue(range.contains("line-2\nline-3") && !range.contains("line-4"))
        assertTrue(f.tools.readFileRange("big.txt").contains("read limit"))
        assertEquals(RunnerFileConfinement.READ_LIMIT_BYTES, 2 * 1024 * 1024)
    }

    @Test fun `writes share one attempt quota and a refused write leaves nothing behind`(@TempDir temp: Path) {
        val f = Fixture(temp)
        assertTrue(f.tools.writeFile(path = "a.txt", content = "x".repeat(60)).startsWith("Successfully"))
        assertTrue(f.tools.writeFile(path = f.output.resolve("b.txt").toString(), content = "y".repeat(50)).contains("quota"))
        assertFalse(Files.exists(f.output.resolve("b.txt")))
        assertEquals(60, f.files.bytesWritten())
        assertTrue(f.tools.writeFile(path = "c.txt", content = "z".repeat(40)).startsWith("Successfully"))
        assertTrue(Files.list(f.workspace).use { s -> s.noneMatch { it.fileName.toString().endsWith(".partial") } })
        assertEquals(256L * 1024 * 1024, RunnerFileConfinement.WRITE_QUOTA_BYTES)
    }

    @Test fun `hidden and key names cannot be written and sensitive names cannot be read`(@TempDir temp: Path) {
        val f = Fixture(temp)
        for (name in listOf(".env", "id_rsa", "server.pem", "deploy.key")) assertTrue(f.tools.writeFile(path = name, content = "x").startsWith("Error:"), name)
        Files.writeString(f.workspace.resolve("api_key.txt"), "k")
        assertTrue(f.tools.readFile("api_key.txt").startsWith("Error:"))
    }

    @Test fun `results are redacted and every operation is audited with a write digest`(@TempDir temp: Path) {
        val f = Fixture(temp, readLimit = 4096)
        f.secrets.register(JsonPrimitive("fixture-file-secret-value"))
        Files.writeString(f.workspace.resolve("leak.txt"), "token=fixture-file-secret-value")
        val read = f.tools.readFile("leak.txt")
        assertFalse(read.contains("fixture-file-secret-value")); assertTrue(read.contains("<redacted>"))
        f.tools.writeFile(path = "out.json", content = "{\"ok\":true}")
        val write = f.audits.single { it[0] == "write" }
        assertEquals("root/agent/workspace/out.json", write[1]); assertEquals(11L, write[2])
        assertEquals(sha256("{\"ok\":true}".toByteArray()), write[3]); assertEquals("ok", write[4])
        assertEquals(listOf("read", "root/agent/workspace/leak.txt", 31L, null, "ok"), f.audits.first())
    }
}
