package com.fartech.agents.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertTrue

class PDFToolsTest {

    @Test
    fun `readPdfText rejects input outside allowed directories`() {
        val outside = File("/etc/hosts")
        if (!outside.exists()) return

        val result = PDFTools.readPdfText(outside.absolutePath)

        assertContains(result, "outside the allowed")
    }

    @Test
    fun `renderPageToImage writes through safe output path`(@TempDir dir: File) {
        val pdf = File(dir, "source.pdf")
        val png = File(dir, "page.png")

        val createResult = PDFTools.createPdfFromText("hello", pdf.absolutePath)
        val renderResult = PDFTools.renderPageToImage(pdf.absolutePath, 0, png.absolutePath)

        assertContains(createResult, "Created PDF")
        assertContains(renderResult, "Saved page")
        assertTrue(png.isFile)
    }
}
