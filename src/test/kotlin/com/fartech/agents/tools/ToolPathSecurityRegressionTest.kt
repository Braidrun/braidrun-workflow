package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.HttpAccess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ToolPathSecurityRegressionTest {

    @Test
    fun `ASA cookie file parser rejects host files outside tool sandbox`() {
        val outside = File("/etc/hosts")
        if (!outside.exists()) return

        val result = ASATokenAutomationTools(HttpAccess()).parseCookieFromFile(outside.absolutePath)

        assertContains(result, "outside the allowed")
    }

    @Test
    fun `CSV preview rejects host files outside tool sandbox`() {
        val outside = File("/etc/hosts")
        if (!outside.exists()) return

        val result = CSVTools().readCsvPreview(outside.absolutePath)

        assertContains(result, "outside the allowed")
    }

    @Test
    fun `OCR rejects host files outside tool sandbox before invoking tesseract`() {
        val outside = File("/etc/hosts")
        if (!outside.exists()) return

        val result = OCRTools.extractTextFromImage(outside.absolutePath)

        assertContains(result, "outside the allowed")
    }

    @Test
    fun `enhanced office image tools reject host files outside tool sandbox`(@TempDir tempDir: Path) {
        val outside = File("/etc/hosts")
        if (!outside.exists()) return

        val wordResult = WordEnhancedTools().insertImageWithCaption(
            imagePath = outside.absolutePath,
            outputPath = tempDir.resolve("word.docx").toString()
        )
        val excelResult = ExcelEnhancedTools().insertImageToExcel(
            imagePath = outside.absolutePath,
            outputPath = tempDir.resolve("book.xlsx").toString()
        )
        val powerpointTools = PowerPointEnhancedTools()
        val pptxPath = tempDir.resolve("slides.pptx").toString()
        powerpointTools.addRichSlide(outputPath = pptxPath, title = "Title")
        val powerpointResult = powerpointTools.addEnhancedImageToSlide(
            pptxPath = pptxPath,
            slideIndex = 0,
            imagePath = outside.absolutePath,
            outputPath = tempDir.resolve("slides-out.pptx").toString()
        )

        assertContains(wordResult, "outside the allowed")
        assertContains(excelResult, "outside the allowed")
        assertContains(powerpointResult, "outside the allowed")
    }

    @Test
    fun `directory iWork packages do not expose symlinks that escape the package root`(@TempDir tempDir: Path) {
        val outside = Path.of("/etc/hosts")
        if (!Files.exists(outside)) return
        val pkg = tempDir.resolve("sample.pages")
        Files.createDirectories(pkg)
        val link = pkg.resolve("host-link.txt")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: SecurityException) {
            return
        }

        val result = IWorkTools.listPackageContents(pkg.toString())

        assertFalse(result.contains("host-link.txt"))
    }
}
