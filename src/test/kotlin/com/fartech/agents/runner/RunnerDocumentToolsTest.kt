package com.fartech.agents.runner

import com.fartech.agents.runner.document.*

import com.fartech.agents.tools.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.*

@Suppress("DEPRECATION")
class RunnerDocumentToolsTest {
    private data class Fixture(val root: Path, val workspace: Path, val files: RunnerFileConfinement, val io: DocumentToolIO)
    private fun fixture(temp: Path, guard: () -> Unit = {}, quota: Long = RunnerFileConfinement.WRITE_QUOTA_BYTES): Fixture {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val files = RunnerFileConfinement(root, family, "root", guard = guard, writeQuotaBytes = quota)
        return Fixture(root, workspace, files, DocumentToolIO(RunnerToolFileAccess(files, workspace), DocumentToolLimits.RUNNER, guard, pdfFontGuard = {}, runtimeGuard = {}))
    }

    @Test fun `DOCX XLSX PPTX and PDF outputs stay in current run`(@TempDir temp: Path) {
        val f = fixture(temp)
        Files.write(f.workspace.resolve("in.docx"), DocumentFixtures.word())
        Files.write(f.workspace.resolve("in.xlsx"), DocumentFixtures.excel())
        Files.write(f.workspace.resolve("in.pptx"), DocumentFixtures.slides())
        Files.write(f.workspace.resolve("in.pdf"), DocumentFixtures.pdf())
        assertContains(RunnerWordTools(f.io).appendTextToDocx("in.docx", "confined", "out.docx"), "saved")
        assertContains(RunnerExcelAdvancedTools(f.io).addSheet("in.xlsx", "New", "out.xlsx"), "out.xlsx")
        assertContains(RunnerPowerPointAdvancedTools(f.io).duplicateSlide("in.pptx", 0, "out.pptx"), "out.pptx")
        assertContains(RunnerPDFTools(f.io).rotatePage("in.pdf", 0, 90, "out.pdf"), "out.pdf")
        for (extension in listOf("docx", "xlsx", "pptx", "pdf")) assertTrue(Files.size(f.workspace.resolve("out.$extension")) > 0)
        assertTrue(f.files.bytesWritten() > 0)
    }

    @Test fun `private sibling traversal symlink and hardlink reads propagate rejection`(@TempDir temp: Path) {
        val f = fixture(temp)
        val private = Files.createDirectories(f.root.resolve(".runner-private")).resolve("document.docx")
        Files.write(private, DocumentFixtures.word())
        val sibling = Files.createDirectories(f.root.resolve(".workflow-runs/owner/other/agent/workspace")).resolve("document.docx")
        Files.write(sibling, DocumentFixtures.word())
        Files.createSymbolicLink(f.workspace.resolve("symlink.docx"), private)
        Files.createLink(f.workspace.resolve("hardlink.docx"), sibling)
        for (path in listOf(private.toString(), sibling.toString(), "symlink.docx", "hardlink.docx", "../outside.docx")) {
            assertFailsWith<SecurityException>(path) { RunnerWordTools(f.io).readDocxText(path) }
        }
        assertEquals(0L, f.files.bytesWritten())
    }

    @Test fun `bound upload reads are allowed and outputs cannot overwrite them`(@TempDir temp: Path) {
        val root = temp.toRealPath()
        val family = root.resolve(".workflow-runs/owner")
        val workspace = Files.createDirectories(family.resolve("root/agent/workspace"))
        val input = Files.createDirectories(root.resolve("inputs/uploads/doc")).resolve("source.docx")
        val bytes = DocumentFixtures.word()
        Files.write(input, bytes)
        val confinement = RunnerFileConfinement(root, family, "root", readOnlyInputs = mapOf(root.relativize(input).toString() to (bytes.size.toLong() to sha256(bytes))))
        val io = DocumentToolIO(RunnerToolFileAccess(confinement, workspace), DocumentToolLimits.RUNNER, runtimeGuard = {})
        assertContains(RunnerWordTools(io).readDocxText(input.toString()), "trusted fixture")
        assertFailsWith<SecurityException> { RunnerWordTools(io).appendTextToDocx(input.toString(), "changed", input.toString()) }
        assertContentEquals(bytes, Files.readAllBytes(input))
    }

    @Test fun `image insertion cannot escape through an otherwise valid document`(@TempDir temp: Path) {
        val f = fixture(temp)
        Files.write(f.workspace.resolve("word"), DocumentFixtures.word())
        Files.write(f.workspace.resolve("sheet"), DocumentFixtures.excel())
        Files.write(f.workspace.resolve("slides"), DocumentFixtures.slides())
        Files.write(f.workspace.resolve("pdf"), DocumentFixtures.pdf())
        val outside = f.root.resolve(".runner-private/image.png").toString()
        assertFailsWith<SecurityException> { RunnerWordAdvancedTools(f.io).insertImage("word", outside, "out.docx", 10, 10) }
        assertFailsWith<SecurityException> { RunnerExcelEnhancedTools(f.io).insertImageToExcel("sheet", "Data", outside, outputPath = "out.xlsx") }
        assertFailsWith<SecurityException> { RunnerPowerPointEnhancedTools(f.io).addEnhancedImageToSlide("slides", 0, outside, outputPath = "out.pptx") }
        assertFailsWith<SecurityException> { RunnerPDFTools(f.io).addImageToPage("pdf", "out.pdf", outside, 0, 0f, 0f, 10f, 10f) }
        assertEquals(0L, f.files.bytesWritten())
    }

    @Test fun `attempt byte quota rejects oversized output atomically`(@TempDir temp: Path) {
        val f = fixture(temp, quota = 32)
        assertFailsWith<SecurityException> { RunnerWordTools(f.io).createDocxFromText("small document", "too-large.docx") }
        assertFalse(Files.exists(f.workspace.resolve("too-large.docx")))
    }

    @Test fun `fence revoked between reading and saving stops output publication`(@TempDir temp: Path) {
        var revoked = false
        val cancelled = CancellationException("attempt revoked")
        val f = fixture(temp, guard = { if (revoked) throw cancelled })
        Files.write(f.workspace.resolve("in.docx"), DocumentFixtures.word())
        f.io.input("in.docx").use { f.io.word(it) }.use { doc ->
            revoked = true
            assertSame(cancelled, assertFailsWith<CancellationException> { f.io.saveWord(doc, "out.docx") })
        }
        assertFalse(Files.exists(f.workspace.resolve("out.docx")))
    }
}
