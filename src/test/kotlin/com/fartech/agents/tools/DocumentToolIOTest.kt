package com.fartech.agents.tools

import com.fartech.agents.runner.document.*

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.test.*

internal object DocumentFixtures {
    fun word(): ByteArray = ByteArrayOutputStream().also { out ->
        XWPFDocument().use { it.createParagraph().createRun().setText("trusted fixture"); it.write(out) }
    }.toByteArray()
    fun excel(): ByteArray = ByteArrayOutputStream().also { out ->
        XSSFWorkbook().use { it.createSheet("Data").createRow(0).createCell(0).setCellValue("trusted fixture"); it.write(out) }
    }.toByteArray()
    fun slides(): ByteArray = ByteArrayOutputStream().also { out ->
        XMLSlideShow().use { it.createSlide().createTextBox().setText("trusted fixture"); it.write(out) }
    }.toByteArray()
    fun pdf(pages: Int = 1): ByteArray = ByteArrayOutputStream().also { out ->
        PDDocument().use { doc -> repeat(pages) { doc.addPage(PDPage()) }; doc.save(out) }
    }.toByteArray()
    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
}

@Suppress("DEPRECATION")
class DocumentToolIOTest {
    private class MemoryFiles : ToolFileAccess {
        val values = linkedMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()
        override fun readBytes(path: String, maxBytes: Int): ByteArray {
            reads += path
            val bytes = values[path] ?: throw FileNotFoundException(path)
            if (bytes.size > maxBytes) throw SecurityException("too large")
            return bytes.copyOf()
        }
        override fun writeBytes(path: String, bytes: ByteArray) { writes += path; values[path] = bytes.copyOf() }
    }
    private fun io(files: MemoryFiles, limits: DocumentToolLimits = DocumentToolLimits.RUNNER) =
        DocumentToolIO(files, limits, pdfFontGuard = {}, runtimeGuard = {}) // Only blank PDF fixtures, no font resolution.

    @Test fun `all Office tiers use opaque byte paths and round trip trusted fixtures`() {
        val files = MemoryFiles()
        files.values.putAll(mapOf("word" to DocumentFixtures.word(), "sheet" to DocumentFixtures.excel(), "slides" to DocumentFixtures.slides()))
        val io = io(files)
        assertContains(RunnerWordTools(io).readDocxText("word"), "trusted fixture")
        assertFalse(RunnerWordAdvancedTools(io).findAndReplaceAll("word", "trusted", "edited", "word2").startsWith("Error"))
        assertContains(RunnerWordEnhancedTools(io).addRichParagraph("word2", "appended", "word3"), "word3")
        assertContains(RunnerWordTools(io).readDocxText("word3"), "appended")
        assertContains(RunnerExcelTools(io).listSheets("sheet"), "Data")
        assertFalse(RunnerExcelAdvancedTools(io).addSheet("sheet", "Added", "sheet2").startsWith("Error"))
        assertContains(RunnerExcelEnhancedTools(io).writeStyledCell("sheet2", "Added", 0, 0, "value", "sheet3"), "sheet3")
        assertContains(RunnerExcelTools(io).listSheets("sheet3"), "Added")
        assertContains(RunnerPowerPointTools(io).listSlides("slides"), "trusted fixture")
        assertFalse(RunnerPowerPointAdvancedTools(io).duplicateSlide("slides", 0, "slides2").startsWith("Error"))
        assertContains(RunnerPowerPointEnhancedTools(io).addRichSlide("slides2", "slides3", "Added"), "slides3")
        io.presentation(files.values.getValue("slides3").inputStream()).use { assertEquals(3, it.slides.size) }
        assertEquals(setOf("word2", "word3", "sheet2", "sheet3", "slides2", "slides3"), files.writes.toSet())
    }

    @Test fun `PDF merge split and extract serialize only through byte writer`() {
        val files = MemoryFiles().apply { values["source"] = DocumentFixtures.pdf(3) }
        val tools = RunnerPDFTools(io(files))
        assertContains(tools.getPdfInfo("source"), "Pages: 3")
        assertContains(tools.extractPagesRange("source", 0, 1, "extract"), "Extracted")
        assertContains(tools.mergePdfs(listOf("extract", "extract"), "merged"), "Merged")
        assertContains(tools.splitPdfByInterval("merged", "parts", 2), "2 parts")
        assertEquals(setOf("extract", "merged", "parts/merged_part001.pdf", "parts/merged_part002.pdf"), files.writes.toSet())
    }

    @Test fun `output over limit or serializer failure publishes no partial bytes`() {
        val files = MemoryFiles()
        val io = io(files, DocumentToolLimits.RUNNER.copy(maxOutputBytes = 8))
        assertFailsWith<DocumentResourceLimitException> { io.write("large") { it.write(ByteArray(9)) } }
        assertFailsWith<IllegalStateException> { io.write("broken") { it.write(1); error("serializer failed") } }
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `ZIP entry total ratio traversal and external resource checks precede POI loading`() {
        fun reject(bytes: ByteArray, limits: DocumentToolLimits) {
            val files = MemoryFiles().apply { values["input"] = bytes }
            assertFailsWith<SecurityException> { RunnerWordTools(io(files, limits)).readDocxText("input") }
            assertTrue(files.writes.isEmpty())
        }
        reject(DocumentFixtures.zip("large.bin" to ByteArray(100)), DocumentToolLimits.RUNNER.copy(maxZipEntryBytes = 32))
        reject(DocumentFixtures.zip("a" to ByteArray(20), "b" to ByteArray(20)), DocumentToolLimits.RUNNER.copy(maxZipExpandedBytes = 32))
        reject(DocumentFixtures.zip("a" to byteArrayOf(1), "b" to byteArrayOf(2)), DocumentToolLimits.RUNNER.copy(maxZipEntries = 1))
        reject(DocumentFixtures.zip("bomb.bin" to ByteArray(1_100_000)), DocumentToolLimits.RUNNER.copy(maxZipRatio = 2))
        reject(DocumentFixtures.zip("../outside" to byteArrayOf(1)), DocumentToolLimits.RUNNER)
        reject(DocumentFixtures.zip("_rels/.rels" to "<Relationships><Relationship TargetMode=\"External\" Type=\"x/image\" Target=\"file:///private/key\"/></Relationships>".toByteArray()), DocumentToolLimits.RUNNER)
        reject(DocumentFixtures.zip("word/document.xml" to "<a>${"x".repeat(65)}</a>".toByteArray()), DocumentToolLimits.RUNNER.copy(maxTextChars = 64))
        reject(DocumentFixtures.zip("word/document.xml" to "<a value=\"${"x".repeat(65)}\"/>".toByteArray()), DocumentToolLimits.RUNNER.copy(maxTextChars = 64))
    }

    @Test fun `page row column pixel and text boundaries fail before large allocation`() {
        val files = MemoryFiles().apply { values["pdf"] = DocumentFixtures.pdf(2); values["sheet"] = DocumentFixtures.excel() }
        assertFailsWith<SecurityException> { RunnerPDFTools(io(files, DocumentToolLimits.RUNNER.copy(maxPages = 1))).getPdfInfo("pdf") }
        assertFailsWith<SecurityException> { RunnerExcelEnhancedTools(io(files)).writeStyledCell(null, "Data", Int.MAX_VALUE, 0, "v", "bad") }
        assertFailsWith<SecurityException> { RunnerExcelEnhancedTools(io(files)).writeStyledCell(null, "Data", 0, Int.MAX_VALUE, "v", "bad") }
        val io = io(files)
        assertFailsWith<SecurityException> { io.table(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertFailsWith<SecurityException> { io.pixels(Double.POSITIVE_INFINITY, 1.0) }
        assertFailsWith<SecurityException> { io.pixels(100000.0, 100000.0) }
        assertFailsWith<SecurityException> { RunnerWordTools(io(files, DocumentToolLimits.RUNNER.copy(maxTextChars = 8))).createDocxFromText("too much text", "out") }
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `batch indices and image spans reject overflow before reading inputs`() {
        val files = MemoryFiles()
        val tools = RunnerExcelEnhancedTools(io(files))
        assertFailsWith<SecurityException> { tools.setColumnWidth("missing", null, 1, 20, Int.MAX_VALUE, "out") }
        assertFailsWith<SecurityException> { tools.setColumnWidth("missing", null, 255, 20, 2, "out") }
        assertFailsWith<SecurityException> { tools.setRowHeight("missing", null, 1, 20f, Int.MAX_VALUE, "out") }
        assertFailsWith<SecurityException> { tools.setRowHeight("missing", null, 9999, 20f, 2, "out") }
        assertFailsWith<SecurityException> { tools.insertImageToExcel(null, null, "image", 0, 1, Int.MAX_VALUE, 1, "out") }
        assertFailsWith<SecurityException> { tools.insertImageToExcel(null, null, "image", 9999, 0, 1, 2, "out") }
        assertFailsWith<SecurityException> { tools.freezePanes("missing", null, Int.MAX_VALUE, 1, "out") }
        assertTrue(files.reads.isEmpty())
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `multi style argument and shared cell text respect text budgets`() {
        val files = MemoryFiles()
        assertFailsWith<SecurityException> {
            RunnerWordEnhancedTools(io(files, DocumentToolLimits.RUNNER.copy(maxTextChars = 8)))
                .addMultiStyleParagraph(null, "123456789", "out")
        }
        assertTrue(files.reads.isEmpty())
        files.values["sheet"] = ByteArrayOutputStream().also { out ->
            XSSFWorkbook().use { wb ->
                val row = wb.createSheet("Data").createRow(0)
                repeat(12) { row.createCell(it).setCellValue("shared".repeat(1000)) }
                wb.write(out)
            }
        }.toByteArray()
        assertFailsWith<DocumentResourceLimitException> {
            RunnerExcelTools(io(files, DocumentToolLimits.RUNNER.copy(maxTextChars = 32_000)))
                .readRangeAsCSV("sheet", "Data", 0, 0, 0, 11)
        }
        files.values["word"] = ByteArrayOutputStream().also { out ->
            XWPFDocument().use { doc ->
                doc.createParagraph().createRun().setText("a".repeat(20))
                doc.write(out)
            }
        }.toByteArray()
        assertFailsWith<DocumentResourceLimitException> {
            RunnerWordAdvancedTools(io(files, DocumentToolLimits.RUNNER.copy(maxTextChars = 32_000)))
                .findAndReplaceAll("word", "a", "replacement".repeat(1000), "out")
        }
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `text result rejects excess before appending and preserves authority`() {
        val files = MemoryFiles()
        val io = io(files, DocumentToolLimits.RUNNER.copy(maxTextChars = 8))
        io.begin()
        val text = io.textBuilder().append("1234").append("x5678y", 1, 5)
        assertEquals("12345678", text.toString())
        assertFailsWith<SecurityException> { text.append('9') }
        assertEquals("12345678", text.toString())
        io.begin()
        assertEquals("a--b--", io.replaceText("a.b.", ".", "--"))
        assertFailsWith<SecurityException> { io.replaceText("a.b.", ".", "--") }
        io.begin()
        assertEquals("-a-b-", io.replaceText("ab", "", "-"))
        var cancelled = false
        val guarded = DocumentToolIO(files, authorityGuard = { if (cancelled) throw CancellationException("cancelled") }, runtimeGuard = {})
        guarded.begin()
        val output = guarded.textBuilder()
        cancelled = true
        assertFailsWith<CancellationException> { output.append("secret") }
        assertEquals("", output.toString())
    }

    @Test fun `Runner refuses missing source fallback and host font operations`() {
        val files = MemoryFiles().apply { values["sheet"] = DocumentFixtures.excel(); values["slides"] = DocumentFixtures.slides() }
        val io = io(files)
        assertFailsWith<FileNotFoundException> { io.optionalInput("missing") }
        files.reads.clear()
        assertFailsWith<SecurityException> { RunnerExcelAdvancedTools(io).autoSizeColumns("sheet", "Data", "out") }
        assertFailsWith<SecurityException> { RunnerPowerPointTools(io).renderSlideToImage("slides", 0, "out") }
        assertFailsWith<SecurityException> { RunnerPowerPointAdvancedTools(io).exportAllSlidesToImages("slides", "images") }
        assertFailsWith<SecurityException> { RunnerWordEnhancedTools(io).listSystemFonts() }
        assertFailsWith<SecurityException> { RunnerExcelEnhancedTools(io).createStyledSpreadsheet("a,b", "out") }
        assertTrue(files.reads.isEmpty())
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `all registered methods propagate cancellation and fences before reading or writing`() {
        val files = MemoryFiles()
        for (failure in listOf(CancellationException("cancelled"), SecurityException("revoked"))) {
            val io = DocumentToolIO(files, DocumentToolLimits.RUNNER, { throw failure }, runtimeGuard = {})
            val sets: List<ToolSet> = listOf(RunnerPDFTools(io), RunnerWordTools(io), RunnerExcelTools(io), RunnerPowerPointTools(io), RunnerWordAdvancedTools(io), RunnerExcelAdvancedTools(io), RunnerPowerPointAdvancedTools(io), RunnerWordEnhancedTools(io), RunnerExcelEnhancedTools(io), RunnerPowerPointEnhancedTools(io))
            var invoked = 0
            for (set in sets) for (method in set::class.memberFunctions.filter { it.findAnnotation<Tool>() != null }) {
                val arguments = method.parameters.filter { !it.isOptional }.associateWith { parameter ->
                    if (parameter.kind == KParameter.Kind.INSTANCE) set else when (parameter.type.classifier) {
                        String::class -> "fixture"
                        Int::class -> 0
                        Float::class -> 1f
                        Double::class -> 1.0
                        Boolean::class -> false
                        List::class -> emptyList<String>()
                        else -> error("Unhandled required parameter ${method.name}: $parameter")
                    }
                }
                val thrown = assertFails { method.callBy(arguments) }
                assertSame(failure, thrown.cause ?: thrown, "${set::class.simpleName}.${method.name}")
                invoked++
            }
            assertEquals(84, invoked)
        }
        assertTrue(files.reads.isEmpty())
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `legacy and explicitly injected Runner sets expose identical annotated tool names`() {
        val io = io(MemoryFiles())
        val pairs = listOf(
            PDFTools to RunnerPDFTools(io), WordTools to RunnerWordTools(io),
            ExcelTools to RunnerExcelTools(io), PowerPointTools to RunnerPowerPointTools(io),
            WordAdvancedTools() to RunnerWordAdvancedTools(io), WordEnhancedTools() to RunnerWordEnhancedTools(io),
            ExcelAdvancedTools() to RunnerExcelAdvancedTools(io), ExcelEnhancedTools() to RunnerExcelEnhancedTools(io),
            PowerPointAdvancedTools() to RunnerPowerPointAdvancedTools(io), PowerPointEnhancedTools() to RunnerPowerPointEnhancedTools(io)
        )
        for ((legacy, injected) in pairs) {
            val left = ToolRegistry { tools(legacy) }.tools.map { it.name }.toSet()
            val right = ToolRegistry { tools(injected) }.tools.map { it.name }.toSet()
            assertTrue(left.isNotEmpty())
            assertEquals(left, right)
        }
    }

    @Test fun `Runner page and row caps do not change the legacy library`(@org.junit.jupiter.api.io.TempDir temp: java.nio.file.Path) {
        val pdf = DocumentFixtures.pdf(201)
        val pdfPath = temp.resolve("legacy.pdf")
        java.nio.file.Files.write(pdfPath, pdf)
        assertContains(PDFTools.getPdfInfo(pdfPath.toString()), "201")
        val workbook = ByteArrayOutputStream().also { out -> XSSFWorkbook().use { wb ->
            wb.createSheet("Data").createRow(10000).createCell(0).setCellValue("legacy row")
            wb.write(out)
        } }.toByteArray()
        val sheetPath = temp.resolve("legacy.xlsx")
        java.nio.file.Files.write(sheetPath, workbook)
        assertContains(ExcelTools.listSheets(sheetPath.toString()), "Data")
        assertEquals(50_000, ExcelTools.MAX_RANGE_ROW_SPAN)
        assertEquals(1_000, ExcelTools.MAX_RANGE_COL_SPAN)
        assertEquals(1_000_000L, ExcelTools.MAX_RANGE_TOTAL_CELLS)
        val files = MemoryFiles().apply { values["pdf"] = pdf; values["sheet"] = workbook }
        assertFailsWith<SecurityException> { RunnerPDFTools(io(files)).getPdfInfo("pdf") }
        assertFailsWith<SecurityException> { RunnerExcelTools(io(files)).listSheets("sheet") }
    }

    @Test fun `Markdown conversion has no file entry and preserves cancellation`() {
        val files = MemoryFiles()
        assertContains(RunnerWordAdvancedTools(io(files)).createDocxFromMarkdown("# Title\n\n**hello**", "markdown"), "Created DOCX")
        assertContains(RunnerWordTools(io(files)).readDocxText("markdown"), "hello")
        val cancelled = CancellationException("markdown cancelled")
        var calls = 0
        val io = DocumentToolIO(files, DocumentToolLimits.RUNNER, { if (++calls > 3) throw cancelled }, runtimeGuard = {})
        assertSame(cancelled, assertFailsWith<CancellationException> { RunnerWordAdvancedTools(io).createDocxFromMarkdown("# Title", "cancelled") })
        assertFalse(files.values.containsKey("cancelled"))
    }
}
