package com.fartech.agents.tools

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The tests below intentionally exercise the deprecated tier classes
// (`ExcelEnhancedTools`, `WordEnhancedTools`) so the deprecation warnings
// are suppressed at the class level — these tests stay valuable until the
// classes themselves are removed.
@Suppress("DEPRECATION")
class OfficeDocumentToolsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `deleteSheet removes requested worksheet and preserves remaining sheets`() {
        val source = tempDir.resolve("source.xlsx").toFile()
        XSSFWorkbook().use { workbook ->
            workbook.createSheet("Sheet1")
            workbook.createSheet("Plan")
            workbook.createSheet("Dashboard")
            FileOutputStream(source).use { workbook.write(it) }
        }

        val output = tempDir.resolve("without-sheet1.xlsx").toString()
        val result = ExcelEnhancedTools().deleteSheet(
            xlsxPath = source.absolutePath,
            sheetName = "Sheet1",
            outputPath = output
        )

        assertTrue(result.contains("Deleted sheet"))
        FileInputStream(output).use { fis ->
            XSSFWorkbook(fis).use { workbook ->
                val sheetNames = (0 until workbook.numberOfSheets).map { workbook.getSheetAt(it).sheetName }
                assertEquals(listOf("Plan", "Dashboard"), sheetNames)
                assertFalse(sheetNames.contains("Sheet1"))
            }
        }
    }

    @Test
    fun `deleteSheet refuses to delete the last remaining worksheet`() {
        val source = tempDir.resolve("single-sheet.xlsx").toFile()
        XSSFWorkbook().use { workbook ->
            workbook.createSheet("OnlySheet")
            FileOutputStream(source).use { workbook.write(it) }
        }

        val outputPath = tempDir.resolve("single-sheet-output.xlsx")
        val result = ExcelEnhancedTools().deleteSheet(
            xlsxPath = source.absolutePath,
            sheetName = "OnlySheet",
            outputPath = outputPath.toString()
        )

        assertTrue(result.contains("last remaining sheet"))
        assertFalse(outputPath.toFile().exists())
    }

    @Test
    fun `addDynamicPageNumberFooter writes Word PAGE and NUMPAGES fields`() {
        val source = tempDir.resolve("source.docx").toFile()
        XWPFDocument().use { document ->
            val paragraph = document.createParagraph()
            paragraph.createRun().setText("Hello document")
            FileOutputStream(source).use { document.write(it) }
        }

        val output = tempDir.resolve("with-page-footer.docx").toString()
        val result = WordEnhancedTools().addDynamicPageNumberFooter(
            docxPath = source.absolutePath,
            outputPath = output,
            prefixText = "第 ",
            suffixText = " 页",
            includeTotalPages = true,
            totalPagesPrefix = " / 共 ",
            totalPagesSuffix = " 页"
        )

        assertTrue(result.contains("dynamic page number footer"))

        ZipFile(output).use { zip ->
            val entries = zip.entries()
            var footerEntryName: String? = null
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("word/footer") && entry.name.endsWith(".xml")) {
                    footerEntryName = entry.name
                    break
                }
            }

            assertNotNull(footerEntryName)
            val footerXml = zip.getInputStream(zip.getEntry(footerEntryName)).bufferedReader().use { it.readText() }
            assertTrue(footerXml.contains("PAGE"))
            assertTrue(footerXml.contains("NUMPAGES"))
            assertTrue(footerXml.contains("第 "))
            assertTrue(footerXml.contains(" / 共 "))
        }
    }
}
