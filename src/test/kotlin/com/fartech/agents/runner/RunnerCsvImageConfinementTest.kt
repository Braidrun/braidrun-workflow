package com.fartech.agents.runner

import com.fartech.agents.tools.CSVTools
import com.fartech.agents.tools.ImageProcessingToolSet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.*

class RunnerCsvImageConfinementTest {
    private class Fixture(temp: Path) {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val private = Files.createDirectories(root.resolve(".runner-private"))
    }
    private fun png(): ByteArray {
        val output = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(output).use { stream ->
            assertTrue(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "PNG", stream))
        }
        return output.toByteArray()
    }
    @Test fun `csv and image reject actual symbolic and hard link input escapes`(@TempDir temp: Path) {
        val fixture = Fixture(temp)
        val csv = Files.writeString(fixture.private.resolve("secret.csv"), "key,value\nsecret,data")
        val image = Files.write(fixture.private.resolve("secret.png"), png())
        val access = RunnerToolFileAccess(RunnerFileConfinement(fixture.root, fixture.family, "root"), fixture.workspace)
        val csvTools = CSVTools(access) {}; val imageTools = ImageProcessingToolSet(access) {}
        Files.createSymbolicLink(fixture.workspace.resolve("csv-link.csv"), csv)
        Files.createLink(fixture.workspace.resolve("csv-hard.csv"), csv)
        Files.createSymbolicLink(fixture.workspace.resolve("image-link.png"), image)
        Files.createLink(fixture.workspace.resolve("image-hard.png"), image)
        for (path in listOf("csv-link.csv", "csv-hard.csv", csv.toString())) {
            assertFailsWith<SecurityException> { csvTools.readCsvPreview(path) }
        }
        for (path in listOf("image-link.png", "image-hard.png", image.toString())) {
            assertFailsWith<SecurityException> { imageTools.getImageInfo(path) }
        }
    }
    @Test fun `output symlink or parent escape leaves the external target intact`(@TempDir temp: Path) {
        val fixture = Fixture(temp)
        Files.writeString(fixture.workspace.resolve("input.csv"), "h\nv")
        Files.write(fixture.workspace.resolve("input.png"), png())
        val external = Files.writeString(fixture.private.resolve("target.csv"), "original")
        Files.createSymbolicLink(fixture.workspace.resolve("out.csv"), external)
        Files.createSymbolicLink(fixture.workspace.resolve("linked-parent"), fixture.private)
        val access = RunnerToolFileAccess(RunnerFileConfinement(fixture.root, fixture.family, "root"), fixture.workspace)
        assertFailsWith<SecurityException> { CSVTools(access) {}.selectColumns("input.csv", "out.csv", listOf("h")) }
        assertFailsWith<SecurityException> { ImageProcessingToolSet(access) {}.convertImageFormat("input.png", "linked-parent/out.png") }
        assertEquals("original", Files.readString(external))
        assertFalse(Files.exists(fixture.private.resolve("out.png")))
    }
    @Test fun `bound attachments can be read but never overwritten by either codec`(@TempDir temp: Path) {
        val fixture = Fixture(temp)
        val inputs = Files.createDirectories(fixture.root.resolve("inputs/uploads/doc"))
        val csv = Files.writeString(inputs.resolve("input.csv"), "h\nv")
        val image = Files.write(inputs.resolve("input.png"), png())
        val bindings = listOf(csv, image).associate { path ->
            val bytes = Files.readAllBytes(path)
            fixture.root.relativize(path).toString() to (bytes.size.toLong() to sha256(bytes))
        }
        val access = RunnerToolFileAccess(RunnerFileConfinement(fixture.root, fixture.family, "root", readOnlyInputs = bindings), fixture.workspace)
        val original = Files.readAllBytes(image)
        assertContains(CSVTools(access) {}.readCsvPreview(csv.toString()), "v")
        assertContains(ImageProcessingToolSet(access) {}.getImageInfo(image.toString()), "Width: 2")
        assertFailsWith<SecurityException> { CSVTools(access) {}.selectColumns(csv.toString(), csv.toString(), listOf("h")) }
        assertFailsWith<SecurityException> { ImageProcessingToolSet(access) {}.flipImage(image.toString(), image.toString()) }
        assertContentEquals(original, Files.readAllBytes(image))
        assertEquals("h\nv", Files.readString(csv))
    }
    @Test fun `shared attempt quota rejects complete codec output without replacing old bytes`(@TempDir temp: Path) {
        val fixture = Fixture(temp)
        Files.writeString(fixture.workspace.resolve("input.csv"), "h\nv")
        Files.write(fixture.workspace.resolve("input.png"), png())
        val output = fixture.workspace.resolve("old.png")
        Files.writeString(output, "original")
        val files = RunnerFileConfinement(fixture.root, fixture.family, "root", writeQuotaBytes = 6)
        val access = RunnerToolFileAccess(files, fixture.workspace)
        CSVTools(access) {}.selectColumns("input.csv", "out.csv", listOf("h")) // CRLF: 6 bytes.
        assertEquals(6L, files.bytesWritten())
        assertFailsWith<SecurityException> { ImageProcessingToolSet(access) {}.convertImageFormat("input.png", "old.png") }
        assertEquals("original", Files.readString(output))
        assertEquals(6L, files.bytesWritten())
    }
    @Test fun `live confinement cancellation reaches both tools before io`(@TempDir temp: Path) {
        val fixture = Fixture(temp)
        val failure = CancellationException("lease cancelled")
        val files = RunnerFileConfinement(fixture.root, fixture.family, "root", guard = { throw failure })
        val access = RunnerToolFileAccess(files, fixture.workspace)
        assertSame(failure, assertFails { CSVTools(access) {}.getCsvInfo("input.csv") })
        assertSame(failure, assertFails { ImageProcessingToolSet(access) {}.getImageInfo("input.png") })
        assertEquals(0L, files.bytesWritten())
    }
}
