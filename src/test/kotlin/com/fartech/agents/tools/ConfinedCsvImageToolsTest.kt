package com.fartech.agents.tools

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.*

class ConfinedCsvImageToolsTest {
    private class Files : ToolFileAccess {
        val data = mutableMapOf<String, ByteArray>()
        val limits = mutableListOf<Int>()
        val written = mutableListOf<String>()
        var failure: RuntimeException? = null
        override fun readBytes(path: String, maxBytes: Int): ByteArray {
            failure?.let { throw it }; limits += maxBytes
            return requireNotNull(data[path]).copyOf()
        }
        override fun writeBytes(path: String, bytes: ByteArray) {
            failure?.let { throw it }; written += path; data[path] = bytes.copyOf()
        }
        fun text(path: String) = String(requireNotNull(data[path]), Charsets.UTF_8)
    }
    private fun png(width: Int = 4, height: Int = 3): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { graphics.color = Color.RED; graphics.fillRect(0, 0, width, height) } finally { graphics.dispose() }
        val output = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(output).use { assertTrue(ImageIO.write(image, "PNG", it)) }
        return output.toByteArray()
    }
    private fun image(bytes: ByteArray): BufferedImage = ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)))
    private fun xlsxZip(sheet: String, sharedStrings: String? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val values = mutableMapOf("[Content_Types].xml" to "<Types/>", "xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to sheet)
            sharedStrings?.let { values["xl/sharedStrings.xml"] = it }
            values.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
        return bytes.toByteArray()
    }

    @Test fun `csv headerless preview preserves first row and bounded return`() {
        val files = Files().apply { data["input.csv"] = "a,b\n1,2\n3,4".toByteArray() }
        val tools = CSVTools(files) {}
        val preview = tools.readCsvPreview("input.csv", hasHeader = false, maxRows = Int.MAX_VALUE, maxChars = Int.MAX_VALUE)
        assertContains(preview, "a,b")
        assertContains(preview, "3,4")
        assertTrue(preview.toByteArray().size <= 32768)
        assertEquals(listOf(ConfinedCsvOperations.INPUT_BYTES), files.limits)
    }
    @Test fun `csv selection filtering and header union keep formulas as text`() {
        val files = Files().apply {
            data["a.csv"] = "name,value\nfirst,=2+2\nsecond,plain".toByteArray()
            data["b.csv"] = "name,extra\nthird,@other".toByteArray()
        }
        val tools = CSVTools(files) {}
        tools.selectColumns("a.csv", "selected.csv", listOf("value"))
        assertContains(files.text("selected.csv"), "'=2+2")
        tools.filterRows("a.csv", "filtered.csv", "name", "equals", "FIRST")
        assertContains(files.text("filtered.csv"), "first")
        assertFalse(files.text("filtered.csv").contains("second"))
        tools.mergeCsvFiles(listOf("a.csv", "b.csv"), "merged.csv")
        assertTrue(files.text("merged.csv").startsWith("name,value,extra"))
        assertContains(files.text("merged.csv"), "third,,'@other")
    }
    @Test fun `csv malformed utf8 duplicate header and unknown filters fail without output`() {
        val files = Files().apply { data["input.csv"] = byteArrayOf(0xc3.toByte(), 0x28) }
        val tools = CSVTools(files) {}
        assertFailsWith<CharacterCodingException> { tools.getCsvInfo("input.csv") }
        files.data["input.csv"] = "x,x\na,b".toByteArray()
        assertFailsWith<IllegalArgumentException> { tools.selectColumns("input.csv", "out.csv", listOf("x")) }
        assertFailsWith<IllegalArgumentException> { tools.filterRows("input.csv", "out.csv", "x", "execute", "x") }
        assertTrue(files.written.isEmpty())
    }
    @Test fun `csv byte row column and cell ceilings refuse before publishing`() {
        val cases = listOf(ByteArray(ConfinedCsvOperations.INPUT_BYTES + 1),
            ("h\n" + "v\n".repeat(ConfinedCsvOperations.MAX_ROWS + 1)).toByteArray(),
            (1..257).joinToString(",") { "c$it" }.toByteArray(),
            ("h\n" + "x".repeat(ConfinedCsvOperations.CELL_BYTES + 1)).toByteArray())
        for (bytes in cases) {
            val files = Files().apply { data["input.csv"] = bytes }
            assertFailsWith<IllegalArgumentException> { CSVTools(files) {}.selectColumns("input.csv", "out.csv", listOf("0")) }
            assertTrue(files.written.isEmpty())
        }
    }
    @Test fun `xlsx round trip uses memory bytes and never evaluates formulas`() {
        val files = Files().apply { data["input.csv"] = "name,value\nfirst,=1+1\nsecond,42".toByteArray() }
        val tools = CSVTools(files) {}
        tools.csvToXlsx("input.csv", "book.xlsx", "Data")
        XSSFWorkbook(ByteArrayInputStream(files.data.getValue("book.xlsx"))).use { workbook ->
            assertEquals("'=1+1", workbook.getSheet("Data").getRow(1).getCell(1).stringCellValue)
        }
        tools.xlsxSheetToCsv("book.xlsx", "Data", "output.csv")
        assertContains(files.text("output.csv"), "'=1+1")
        assertContains(files.text("output.csv"), "42.0")
    }
    @Test fun `xlsx preflight rejects sparse coordinates entities shared-string inflation and expanded bytes`() {
        val samples = listOf(
            xlsxZip("<worksheet><sheetData><row r='10001'><c r='A10001'/></row></sheetData></worksheet>") to "row index limit",
            xlsxZip("<worksheet><sheetData><row r='1'><c r='XFD1'/></row></sheetData></worksheet>") to "coordinates exceed",
            xlsxZip("<!DOCTYPE a [<!ENTITY leak SYSTEM 'file:///must-not-read'>]><worksheet>&leak;</worksheet>") to "DTD",
            xlsxZip("<worksheet/>", "<sst uniqueCount='999999999'><si><t>v</t></si></sst>") to "shared-string count limit",
            xlsxZip("<worksheet>" + " ".repeat(ConfinedCsvOperations.INPUT_BYTES + 1) + "</worksheet>") to "output byte limit"
        )
        for ((bytes, reason) in samples) {
            val files = Files().apply { data["input.xlsx"] = bytes }
            val failure = assertFails { CSVTools(files) {}.xlsxSheetToCsv("input.xlsx", "Data", "out.csv") }
            assertContains(failure.message.orEmpty(), reason)
            assertTrue(files.written.isEmpty())
        }
    }
    @Test fun `csv cancellation or confinement failure propagates unchanged`() {
        for (failure in listOf(SecurityException("outside task"), CancellationException("cancelled"))) {
            val files = Files().apply { this.failure = failure }
            assertSame(failure, assertFails { CSVTools(files) {}.readCsvPreview("input.csv") })
        }
        val files = Files()
        val failure = CancellationException("fenced")
        assertSame(failure, assertFails { CSVTools(files) { throw failure }.getCsvInfo("input.csv") })
        assertTrue(files.limits.isEmpty())
    }
    @Test fun `image resize rotate crop flip and merge produce actual bounded images`() {
        val files = Files().apply { data["in.png"] = png() }
        val tools = ImageProcessingToolSet(files) {}
        tools.resizeImage("in.png", "resized.png", 8, 0)
        assertEquals(8 to 6, image(files.data.getValue("resized.png")).let { it.width to it.height })
        tools.rotateImage("in.png", "rotated.png", 90.0)
        assertEquals(3 to 4, image(files.data.getValue("rotated.png")).let { it.width to it.height })
        tools.cropImage("in.png", "crop.png", 1, 1, 2, 2)
        assertEquals(2, image(files.data.getValue("crop.png")).width)
        tools.flipImage("in.png", "flip.png")
        tools.mergeImages("in.png,in.png", "merge.png", gap = 1)
        assertEquals(9, image(files.data.getValue("merge.png")).width)
        assertTrue(files.limits.all { it == ImageProcessingToolSet.INPUT_BYTES })
    }
    @Test fun `image codec streams ignore global disk cache setting without modifying it`() {
        val files = Files().apply { data["in.png"] = png() }
        val before = ImageIO.getUseCache()
        val tools = ImageProcessingToolSet(files) {}
        tools.convertImageFormat("in.png", "converted.jpg")
        tools.compressImage("in.png", "compressed.jpg", 0.5f)
        assertFalse(image(files.data.getValue("converted.jpg")).colorModel.hasAlpha())
        assertTrue(files.data.getValue("compressed.jpg").isNotEmpty())
        assertEquals(before, ImageIO.getUseCache())
    }
    @Test fun `image unknown output suffix unsupported input and invalid transforms never report success`() {
        val files = Files().apply { data["in.png"] = png(); data["bad.png"] = byteArrayOf(1, 2, 3) }
        val tools = ImageProcessingToolSet(files) {}
        assertFailsWith<IllegalArgumentException> { tools.convertImageFormat("in.png", "out.unknown") }
        assertFailsWith<IllegalArgumentException> { tools.convertImageFormat("in.png", "out.webp") }
        assertFailsWith<IllegalArgumentException> { tools.getImageInfo("bad.png") }
        assertFailsWith<IllegalArgumentException> { tools.resizeImage("in.png", "out.png", Int.MAX_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> { tools.cropImage("in.png", "out.png", Int.MAX_VALUE, 0, 3, 3) }
        assertFailsWith<IllegalArgumentException> { tools.rotateImage("in.png", "out.png", Double.NaN) }
        assertFailsWith<IllegalArgumentException> { tools.compressImage("in.png", "out.jpg", Float.NaN) }
        assertTrue(files.written.isEmpty())
    }
    @Test fun `image dimensions rejected from metadata before pixel allocation`() {
        val bytes = png()
        ByteBuffer.wrap(bytes, 16, 8).putInt(100_000).putInt(100_000)
        val crc = CRC32().apply { update(bytes, 12, 17) }
        ByteBuffer.wrap(bytes, 29, 4).putInt(crc.value.toInt())
        val files = Files().apply { data["huge.png"] = bytes }
        assertFailsWith<IllegalArgumentException> { ImageProcessingToolSet(files) {}.getImageInfo("huge.png") }
        assertTrue(files.written.isEmpty())
    }
    @Test fun `image authority cancellation and byte quota errors propagate`() {
        for (failure in listOf(SecurityException("quota"), CancellationException("fenced"))) {
            val files = Files().apply { this.failure = failure }
            assertSame(failure, assertFails { ImageProcessingToolSet(files) {}.getImageInfo("input.png") })
        }
        val files = Files().apply { data["in.png"] = png() }
        var revoked = false
        val failure = CancellationException("revoked")
        val tools = ImageProcessingToolSet(files) { if (revoked) throw failure }
        revoked = true
        assertSame(failure, assertFails { tools.convertImageFormat("in.png", "out.png") })
        assertTrue(files.written.isEmpty())
    }
    @Test fun `bounded output stops before accepting oversize chunks`() {
        val output = BoundedToolOutput(4) {}
        output.write(byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> { output.write(byteArrayOf(4, 5)) }
        assertContentEquals(byteArrayOf(1, 2, 3), output.bytes())
    }
    @Test fun `legacy default entry points still construct without host dependencies`() {
        assertNotNull(CSVTools())
        assertNotNull(ImageProcessingTools)
    }
}
