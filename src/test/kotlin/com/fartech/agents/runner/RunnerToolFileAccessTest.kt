package com.fartech.agents.runner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RunnerToolFileAccessTest {
    @Test fun `binary reads have independent limits and writes share the attempt quota`(@TempDir temp: Path) {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val files = RunnerFileConfinement(root, family, "root", readLimitBytes = 4, writeQuotaBytes = 12)
        val access = RunnerToolFileAccess(files, workspace)
        val bytes = byteArrayOf(0, 1, -1, 3, 4, 5, 6, 7)
        access.writeBytes("image.png", bytes)
        assertContentEquals(bytes, access.readBytes("image.png", 8))
        assertFailsWith<SecurityException> { files.read(files.locate("image.png", workspace)) }
        assertFailsWith<SecurityException> { access.readBytes("image.png", 7) }
        assertFailsWith<IllegalArgumentException> { access.readBytes("image.png", RunnerFileConfinement.BINARY_READ_LIMIT_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { access.readBytes("image.png", 0) }
        // Another adapter still consumes the same attempt-wide budget; failure does not publish partial bytes.
        val second = RunnerToolFileAccess(files, workspace)
        second.writeBytes("other.png", byteArrayOf(8, 9, 10, 11))
        assertFailsWith<SecurityException> { access.writeBytes("overflow.png", byteArrayOf(12)) }
        assertFalse(Files.exists(workspace.resolve("overflow.png")))
    }

    @Test fun `binary adapters reject sibling runs private state links and writes to bound inputs`(@TempDir temp: Path) {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val privateFile = Files.createDirectories(root.resolve(".runner-private")).resolve("auth.json")
        Files.writeString(privateFile, "private")
        val sibling = Files.createDirectories(family.resolve("other/agent/workspace")).resolve("data.png")
        Files.writeString(sibling, "sibling")
        val input = Files.createDirectories(root.resolve("inputs/uploads/document")).resolve("input.png")
        val bytes = byteArrayOf(1, 2, 3, 4)
        Files.write(input, bytes)
        val files = RunnerFileConfinement(root, family, "root", readOnlyInputs = mapOf(root.relativize(input).toString() to (4L to sha256(bytes))))
        val access = RunnerToolFileAccess(files, workspace)
        assertContentEquals(bytes, access.readBytes(input.toString()))
        assertFailsWith<SecurityException> { access.writeBytes(input.toString(), byteArrayOf(9)) }
        Files.createSymbolicLink(workspace.resolve("linked.png"), input)
        Files.createLink(workspace.resolve("hard.png"), sibling)
        for (path in listOf(privateFile.toString(), sibling.toString(), "linked.png", "hard.png", "../data.png")) {
            assertFailsWith<SecurityException>(path) { access.readBytes(path) }
        }
        Files.write(input, byteArrayOf(4, 3, 2, 1))
        assertFailsWith<SecurityException> { access.readBytes(input.toString()) }
    }

    @Test fun `revocation during a bounded read stops before bytes return`(@TempDir temp: Path) {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        Files.write(workspace.resolve("large.png"), ByteArray(200_000))
        var checks = 0
        var revoke = false
        val files = RunnerFileConfinement(root, family, "root", guard = {
            checks++
            if (revoke && checks >= 3) throw SecurityException("attempt revoked")
        })
        val target = files.locate("large.png", workspace)
        checks = 0; revoke = true
        val failure = assertFailsWith<SecurityException> { files.readBinary(target) }
        assertEquals("attempt revoked", failure.message)
        assertEquals(3, checks)
    }
}
