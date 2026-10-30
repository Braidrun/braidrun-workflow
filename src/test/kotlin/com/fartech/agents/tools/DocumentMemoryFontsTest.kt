package com.fartech.agents.tools

import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Global mapper is only changed in a separate JVM; the test worker/server process is untouched. */
class DocumentMemoryFontsTest {
    @Test fun `bundled PDF font mapper creates reads and renders without home or tmp writes`(@TempDir temp: Path) {
        val home = Files.createDirectory(temp.resolve("home"))
        val scratch = Files.createDirectory(temp.resolve("tmp"))
        val classpath = requireNotNull(System.getProperty("runner.test.runtimeClasspath")) {
            "The test task must supply runner.test.runtimeClasspath for the isolated document policy probe"
        }
        val log = temp.resolve("probe.log")
        val child = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=$home", "-Djava.io.tmpdir=$scratch", "-Djava.awt.headless=true",
            "-cp", classpath, DocumentFontPolicyProbe::class.java.name
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Isolated font probe timed out")
            assertEquals(0, child.exitValue(), Files.readString(log).take(8000))
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
        Files.list(home).use { assertEquals(0L, it.count(), "PDF font handling wrote into home") }
        Files.list(scratch).use { assertEquals(0L, it.count(), "PDF font handling used system temp") }
    }
}

object DocumentFontPolicyProbe {
    @JvmStatic fun main(args: Array<String>) {
        try { DocumentMemoryFonts.checkInstalled(); error("Font policy should not be active by default") }
        catch (_: SecurityException) { /* expected before explicit dedicated-process install */ }
        // Deliberately unsafe initial values: only explicit dedicated-process installation
        // may change them. This subprocess never shares the library/server JVM globals.
        org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(0)
        org.apache.poi.openxml4j.opc.ZipPackage.setUseTempFilePackageParts(true)
        DocumentMemoryRuntime.installForDedicatedRunnerProcess()
        DocumentMemoryFonts.checkInstalled()
        val data = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(20f, 700f)
                stream.showText("bundled font fixture")
                stream.endText()
            }
            doc.save(data)
        }
        Loader.loadPDF(data.toByteArray()).use { doc ->
            check(PDFTextStripper().getText(doc).contains("bundled font fixture"))
            check(PDFRenderer(doc).renderImageWithDPI(0, 72f).width > 0)
        }
        val pixel = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val imageBytes = ByteArrayOutputStream()
        javax.imageio.stream.MemoryCacheImageOutputStream(imageBytes).use { check(javax.imageio.ImageIO.write(pixel, "png", it)) }
        org.apache.poi.xslf.usermodel.XMLSlideShow().use { ppt ->
            val picture = ppt.addPicture(imageBytes.toByteArray(), org.apache.poi.sl.usermodel.PictureData.PictureType.PNG)
            // createPicture implicitly reads dimensions with ImageIO.read(InputStream).
            ppt.createSlide().createPicture(picture)
            ppt.write(ByteArrayOutputStream())
        }
        val fixtures = mutableMapOf("word" to DocumentFixtures.word(), "sheet" to DocumentFixtures.excel(), "slides" to DocumentFixtures.slides())
        val files = object : ToolFileAccess {
            override fun readBytes(path: String, maxBytes: Int): ByteArray = fixtures.getValue(path).also { check(it.size <= maxBytes) }
            override fun writeBytes(path: String, bytes: ByteArray) { fixtures[path] = bytes }
        }
        val io = DocumentToolIO(files)
        io.begin()
        io.word(io.input("word")).use { doc ->
            check(doc.`package`.packageAccess == org.apache.poi.openxml4j.opc.PackageAccess.READ_WRITE)
            doc.createParagraph().createRun().setText("edited")
            io.saveWord(doc, "word2")
        }
        io.xlsx(io.input("sheet")).use { doc -> doc.createSheet("Added"); io.saveWorkbook(doc, "sheet2") }
        io.presentation(io.input("slides")).use { doc -> doc.createSlide(); io.savePresentation(doc, "slides2") }
        check(listOf("word2", "sheet2", "slides2").all { fixtures.getValue(it).isNotEmpty() })
        check(!javax.imageio.ImageIO.getUseCache())
        DocumentMemoryRuntime.checkInstalled()
        org.apache.poi.openxml4j.opc.ZipPackage.setUseTempFilePackageParts(true)
        try { DocumentMemoryRuntime.checkInstalled(); error("Changed POI policy must fail closed") }
        catch (_: SecurityException) { /* no document IO is permitted after this change */ }
        println("BUNDLED_PDF_FONT_IMAGEIO_AND_POI_MEMORY_OK")
    }
}
