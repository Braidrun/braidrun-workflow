package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.apache.pdfbox.Loader
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.apache.pdfbox.multipdf.Splitter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDField
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.imageio.ImageIO

private val pdfToolsLogger = KotlinLogging.logger("com.fartech.agents.tools.PDFTools")

/**
 * Unified PDF toolset (merged in Phase 6 of the 2026-04 audit follow-up).
 *
 * Pre-merger history:
 *   - `PDFTools`  — read / extract / render / split / merge / simple annotations
 *   - `PDFAdvancedTools` — page-range extraction, rotation, deletion, image
 *     insertion, form filling, encryption, metadata, header/footer, text boxes
 *
 * Both classes registered separately under the `pdf` tool group. They
 * shared the same safe-output path helper (`safeOutput`) and similar
 * text-wrapping logic, but in two slightly different implementations. This
 * file merges every `@Tool` method into a single `object PDFTools` — same
 * @Tool method names, same signatures, same observable behaviour, but
 * callers no longer need to reason about which "version" to pick. The old
 * `PDFAdvancedTools` object is removed (see [PDFAdvancedTools.kt] replacement
 * shim for the deprecation notice).
 */
@LLMDescription("A toolset for working with PDF files using Apache PDFBox (read, extract, render, split, merge, page ops, forms, encryption, annotations)")
object PDFTools : ToolSet {

    // ObjectMapper is thread-safe and expensive to construct — reuse one instance.
    private val jsonMapper by lazy { jacksonObjectMapper() }

    init { PoiSecurity.ensureHardened() }

    /** Validate input path and return the safe canonical file. */
    private fun safeInput(inputPath: String): File =
        ToolPathSecurity.validateInputPath(inputPath)

    /** Validate output path and return the safe canonical path string for PDFBox save() */
    private fun safeOutput(outputPath: String): String =
        ToolPathSecurity.validateOutputPath(outputPath).absolutePath

    private fun safeOutputFile(outputPath: String): File =
        ToolPathSecurity.validateOutputPath(outputPath)

    private fun safeOutputDirectory(outputDirectory: String): File {
        val marker = ToolPathSecurity.validateOutputPath(File(outputDirectory, ".braidrun-agent-dir-check").path)
        return marker.parentFile
    }

    @Tool
    @LLMDescription("Extract all text from a PDF file. Suitable for quick inspection or RAG. Returns up to maxChars characters.")
    fun readPdfText(
        @LLMDescription("Path to the PDF file") pdfPath: String,
        @LLMDescription("Max characters to return (default 20_000)") maxChars: Int? = 20000
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val stripper = PDFTextStripper()
                val text = stripper.getText(doc)
                val out = text.take(maxChars ?: 20000)
                "Read ${doc.numberOfPages} pages. Returning ${out.length} chars.\n" + out
            }
        } catch (e: Throwable) {
            "Error reading PDF '$pdfPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get PDF basic info: page count, metadata, size of first page, etc.")
    fun getPdfInfo(
        @LLMDescription("Path to the PDF file") pdfPath: String
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val info = doc.documentInformation
                val pages = doc.numberOfPages
                val first: PDPage? = if (doc.numberOfPages > 0) doc.getPage(0) else null
                val mediaBox = first?.mediaBox ?: PDRectangle.LETTER
                val created = info?.creationDate?.time?.let { Date(it.time) }
                val modified = info?.modificationDate?.time?.let { Date(it.time) }
                val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                buildString {
                    appendLine("File: $pdfPath")
                    appendLine("Pages: $pages")
                    appendLine("Title: ${info?.title ?: ""}")
                    appendLine("Author: ${info?.author ?: ""}")
                    appendLine("Subject: ${info?.subject ?: ""}")
                    appendLine("Keywords: ${info?.keywords ?: ""}")
                    appendLine("Producer: ${info?.producer ?: ""}")
                    appendLine("CreationDate: ${created?.let { df.format(it) } ?: ""}")
                    appendLine("ModDate: ${modified?.let { df.format(it) } ?: ""}")
                    appendLine("FirstPageSize: ${mediaBox.width} x ${mediaBox.height}")
                }
            }
        } catch (e: Throwable) {
            "Error reading info from '$pdfPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Merge multiple PDF files into one output PDF. Source paths are merged in given order.")
    fun mergePdfs(
        @LLMDescription("List of source PDF paths in the order to merge") sourcePaths: List<String>,
        @LLMDescription("Destination output PDF path") outputPath: String
    ): String {
        return try {
            if (sourcePaths.isEmpty()) return "No source PDFs provided"
            PDDocument().use { dest ->
                val merger = PDFMergerUtility()
                for (p in sourcePaths) {
                    Loader.loadPDF(safeInput(p)).use { src ->
                        merger.appendDocument(dest, src)
                    }
                }
                dest.save(safeOutput(outputPath))
            }
            "Merged ${sourcePaths.size} PDFs into: $outputPath"
        } catch (e: Throwable) {
            "Error merging PDFs: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Split a PDF into parts every N pages. Files are saved to destDir as <base>_partNNN.pdf")
    fun splitPdfByInterval(
        @LLMDescription("Path to the source PDF") pdfPath: String,
        @LLMDescription("Destination directory for the output PDFs") destDir: String,
        @LLMDescription("Split interval in pages (e.g., 10)") splitAtPage: Int
    ): String {
        return try {
            val inputFile = safeInput(pdfPath)
            val base = inputFile.nameWithoutExtension
            val outDir = safeOutputDirectory(destDir)
            Loader.loadPDF(inputFile).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val splitter = Splitter().apply { setSplitAtPage(if (splitAtPage > 0) splitAtPage else 1) }
                val parts = splitter.split(doc)
                var idx = 1
                parts.forEach { part ->
                    part.use {
                        val out = safeOutputFile(File(outDir, "${base}_part${idx.toString().padStart(3, '0')}.pdf").path)
                        it.save(out)
                    }
                    idx++
                }
                "Split into ${parts.size} parts in directory: ${outDir.absolutePath}"
            }
        } catch (e: Throwable) {
            "Error splitting PDF: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Create a simple PDF from plain text. Each paragraph is wrapped on the page.")
    fun createPdfFromText(
        @LLMDescription("Text content to put into the PDF") text: String,
        @LLMDescription("Destination output PDF path") outputPath: String,
        @LLMDescription("Optional title for the document") title: String? = null,
        @LLMDescription("Font size (default 12)") fontSize: Float? = 12f,
        @LLMDescription("Page width (default A4 width)") pageWidth: Float? = PDRectangle.A4.width,
        @LLMDescription("Page height (default A4 height)") pageHeight: Float? = PDRectangle.A4.height,
    ): String {
        return try {
            PDDocument().use { doc ->
                // Metadata
                doc.documentInformation?.let { info ->
                    info.title = title ?: info.title
                }
                var page = PDPage(PDRectangle(pageWidth ?: PDRectangle.A4.width, pageHeight ?: PDRectangle.A4.height))
                doc.addPage(page)

                val font: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val margin = 50f
                val leading = 1.5f * (fontSize ?: 12f)
                val wrapWidth = page.mediaBox.width - 2 * margin
                val lines = wrapText(text, font, fontSize ?: 12f, wrapWidth)

                var cs = PDPageContentStream(doc, page)
                var y = page.mediaBox.height - margin
                cs.beginText()
                cs.setFont(font, fontSize ?: 12f)
                cs.newLineAtOffset(margin, y)

                for (line in lines) {
                    if (y - leading < margin) {
                        cs.endText()
                        cs.close()
                        page = PDPage(page.mediaBox)
                        doc.addPage(page)
                        cs = PDPageContentStream(doc, page)
                        y = page.mediaBox.height - margin
                        cs.beginText()
                        cs.setFont(font, fontSize ?: 12f)
                        cs.newLineAtOffset(margin, y)
                    }
                    cs.showText(line)
                    cs.newLineAtOffset(0f, -leading)
                    y -= leading
                }
                cs.endText()
                cs.close()

                doc.save(safeOutput(outputPath))
            }
            "Created PDF at: $outputPath"
        } catch (e: Throwable) {
            "Error creating PDF: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Render a specific page of a PDF to an image file (PNG by default)")
    fun renderPageToImage(
        @LLMDescription("Path to the PDF file") pdfPath: String,
        @LLMDescription("Zero-based page index to render") pageIndex: Int,
        @LLMDescription("Output image path, e.g., output.png") outputImagePath: String,
        @LLMDescription("DPI (default 150)") dpi: Float? = 150f
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                    return "Invalid pageIndex ${pageIndex}, total pages: ${doc.numberOfPages}"
                }
                val renderer = PDFRenderer(doc)
                val image = renderer.renderImageWithDPI(pageIndex, dpi ?: 150f, ImageType.RGB)
                val outFile = safeOutputFile(outputImagePath)
                ImageIO.write(image, outFile.extension.ifEmpty { "png" }, outFile)
                "Saved page $pageIndex of '$pdfPath' to ${outFile.absolutePath}"
            }
        } catch (e: Throwable) {
            "Error rendering page: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add a semi-transparent centered watermark text to every page of a PDF")
    fun addWatermarkText(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination output PDF path") outputPath: String,
        @LLMDescription("Watermark text") watermarkText: String,
        @LLMDescription("Font size (default 72)") fontSize: Float? = 72f,
        @LLMDescription("Opacity 0..1 (default 0.15)") opacity: Float? = 0.15f,
        @LLMDescription("Rotation in degrees (default 30)") rotationDegrees: Float? = 30f
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val font: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
                val radians = Math.toRadians((rotationDegrees ?: 30f).toDouble()).toFloat()
                val alpha = (opacity ?: 0.15f).coerceIn(0f, 1f)
                for (i in 0 until doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val mediaBox = page.mediaBox
                    val cx = mediaBox.width / 2f
                    val cy = mediaBox.height / 2f

                    val gs = PDExtendedGraphicsState().apply {
                        nonStrokingAlphaConstant = alpha
                    }

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.setGraphicsStateParameters(gs)
                        cs.beginText()
                        cs.setNonStrokingColor(Color(200, 0, 0))
                        cs.setFont(font, fontSize ?: 72f)
                        val m = Matrix()
                        m.translate(cx, cy)
                        m.rotate(radians.toDouble())
                        cs.setTextMatrix(m)
                        cs.showText(watermarkText)
                        cs.endText()
                        cs.restoreGraphicsState()
                    }
                }
                doc.save(safeOutput(outputPath))
            }
            "Watermark added and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error watermarking PDF: ${e.message}"
        }
    }

    // Helper: simple word wrapping using font metrics.
    // Used by `annotate*` / simple overlay tools that don't need paragraph spacing.
    private fun wrapText(text: String, font: PDFont, fontSize: Float, maxWidth: Float): List<String> {
        val words = text.replace("\r", "").split("\n").flatMap { line ->
            if (line.isBlank()) listOf("") else line.split(" ").map { it + " " }
        }
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (w in words) {
            val trial = sb.toString() + w
            val width = font.getStringWidth(trial) / 1000 * fontSize
            if (width > maxWidth && sb.isNotEmpty()) {
                lines += sb.toString().trimEnd()
                sb.clear()
                sb.append(w)
            } else {
                sb.append(w)
            }
        }
        if (sb.isNotEmpty()) lines += sb.toString().trimEnd()
        return lines
    }

    // ========================================================================
    // Merged from former PDFAdvancedTools (Phase 6): page-range extraction,
    // rotation, deletion, image insertion, form filling, encryption, metadata,
    // header/footer, text boxes, plus paragraph-aware text wrapping helper.
    // ========================================================================

    @Tool
    @LLMDescription("Extract a page range from a PDF (inclusive) into a new PDF")
    fun extractPagesRange(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Start page index (0-based, inclusive)") startIndex: Int,
        @LLMDescription("End page index (0-based, inclusive)") endIndex: Int,
        @LLMDescription("Destination PDF path") outputPath: String
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { src ->
                PoiSecurity.sanitizePdfDocument(src)
                if (startIndex < 0 || endIndex >= src.numberOfPages || startIndex > endIndex) {
                    return "Invalid range: [$startIndex, $endIndex], total pages: ${src.numberOfPages}"
                }
                // Rebuild using merger to avoid shared COS objects between src and dest.
                PDDocument().use { realDest ->
                    for (i in startIndex..endIndex) {
                        PDDocument().use { one ->
                            one.addPage(src.getPage(i))
                            // Phase 11: SecureTempFile + symlink check. createTempFile alone
                            // on a multi-user /tmp can return a symlinked path if an attacker
                            // pre-created it. Reject if the file ends up as a symlink.
                            val out = com.fartech.agents.commons.SecureTempFile.create("page_", ".pdf")
                            if (java.nio.file.Files.isSymbolicLink(out.toPath())) {
                                out.delete()
                                throw java.io.IOException("Temp file was a symlink — refusing to write")
                            }
                            one.save(out)
                            one.close()
                            Loader.loadPDF(out).use { oneReloaded ->
                                PDFMergerUtility().appendDocument(realDest, oneReloaded)
                            }
                            out.delete()
                        }
                    }
                    realDest.save(safeOutput(outputPath))
                }
            }
            "Extracted pages [$startIndex..$endIndex] to: $outputPath"
        } catch (e: Throwable) {
            "Error extracting pages: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Rotate a single page by degrees (clockwise)")
    fun rotatePage(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Page index (0-based)") pageIndex: Int,
        @LLMDescription("Rotation in degrees. Must be a multiple of 90 (e.g., 90, 180, 270)") degrees: Int,
        @LLMDescription("Destination PDF path") outputPath: String
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                if (pageIndex !in 0 until doc.numberOfPages) return "Invalid page index $pageIndex, total: ${doc.numberOfPages}"
                val page: PDPage = doc.getPage(pageIndex)
                val rot = ((degrees % 360) + 360) % 360
                if (rot % 90 != 0) return "Rotation must be multiple of 90"
                page.rotation = (page.rotation + rot) % 360
                doc.save(safeOutput(outputPath))
            }
            "Rotated page $pageIndex by $degrees degrees. Saved to: $outputPath"
        } catch (e: Throwable) {
            "Error rotating page: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Delete specific pages from a PDF and save to a new file")
    fun deletePages(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("0-based page indices to delete") indicesToDelete: List<Int>,
        @LLMDescription("Destination PDF path") outputPath: String
    ): String {
        return try {
            val toDelete = indicesToDelete.toSet()
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val total = doc.numberOfPages
                if (toDelete.any { it !in 0 until total }) return "One or more indices out of range 0..${total - 1}"
                // delete from highest to lowest index to keep indices valid
                toDelete.sortedDescending().forEach { idx ->
                    doc.removePage(idx)
                }
                doc.save(safeOutput(outputPath))
            }
            "Deleted ${indicesToDelete.size} pages. Saved to: $outputPath"
        } catch (e: Throwable) {
            "Error deleting pages: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add an image onto a given page at coordinates (origin bottom-left)")
    fun addImageToPage(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination PDF path") outputPath: String,
        @LLMDescription("Path to image file (png/jpg)") imagePath: String,
        @LLMDescription("Target page index (0-based)") pageIndex: Int,
        @LLMDescription("X coordinate in points") x: Float,
        @LLMDescription("Y coordinate in points") y: Float,
        @LLMDescription("Width in points") width: Float,
        @LLMDescription("Height in points") height: Float
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                if (pageIndex !in 0 until doc.numberOfPages) return "Invalid page index $pageIndex, total: ${doc.numberOfPages}"
                val page = doc.getPage(pageIndex)
                val image = PDImageXObject.createFromFileByContent(safeInput(imagePath), doc)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(image, x, y, width, height)
                }
                doc.save(safeOutput(outputPath))
            }
            "Added image to page $pageIndex and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Fill AcroForm fields in a PDF and save as a new file")
    fun fillFormFields(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination PDF path") outputPath: String,
        @LLMDescription("JSON object string of fieldName -> value, e.g. {\"name\":\"Alice\",\"age\":\"30\"}") fieldsJson: String
    ): String {
        return try {
            val fields: Map<String, String> = try {
                jsonMapper.readValue(fieldsJson)
            } catch (je: Throwable) {
                return "Invalid fieldsJson. Expect JSON object of string-to-string. Error: ${je.message}"
            }
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val acroForm: PDAcroForm? = doc.documentCatalog.acroForm
                if (acroForm == null) return "No AcroForm found in PDF: $pdfPath"
                acroForm.needAppearances = true
                for ((name, value) in fields) {
                    val field: PDField? = acroForm.getField(name)
                    if (field == null) continue else field.setValue(value)
                }
                doc.save(safeOutput(outputPath))
            }
            "Filled ${fields.size} fields and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error filling form fields: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Encrypt a PDF with owner/user passwords and permissions")
    fun encryptPdf(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination PDF path") outputPath: String,
        @LLMDescription("Owner password (required)") ownerPassword: String,
        @LLMDescription("User password (optional; empty for none)") userPassword: String? = null,
        @LLMDescription("Allow printing") allowPrinting: Boolean = true,
        @LLMDescription("Allow modifying") allowModifying: Boolean = true
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val perm = AccessPermission()
                perm.setCanPrint(allowPrinting)
                perm.setCanModify(allowModifying)
                val policy = StandardProtectionPolicy(ownerPassword, userPassword ?: "", perm)
                policy.encryptionKeyLength = 128
                doc.protect(policy)
                doc.save(safeOutput(outputPath))
            }
            "Encrypted PDF saved to: $outputPath"
        } catch (e: Throwable) {
            "Error encrypting PDF: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Remove encryption from a PDF (requires owner password)")
    fun decryptPdf(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination PDF path") outputPath: String,
        @LLMDescription("Owner password") ownerPassword: String
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath), ownerPassword).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                doc.isAllSecurityToBeRemoved = true
                doc.save(safeOutput(outputPath))
            }
            "Decrypted PDF saved to: $outputPath"
        } catch (e: Throwable) {
            "Error decrypting PDF: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Set basic metadata and save to a new file")
    fun setMetadata(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination PDF path") outputPath: String,
        @LLMDescription("Title (optional)") title: String? = null,
        @LLMDescription("Author (optional)") author: String? = null,
        @LLMDescription("Subject (optional)") subject: String? = null,
        @LLMDescription("Keywords (optional)") keywords: String? = null
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val info = doc.documentInformation
                if (title != null) info.title = title
                if (author != null) info.author = author
                if (subject != null) info.subject = subject
                if (keywords != null) info.keywords = keywords
                doc.save(safeOutput(outputPath))
            }
            "Metadata updated and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error setting metadata: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add header and footer text to every page (simple positioning)")
    fun addHeaderFooterText(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination PDF path") outputPath: String,
        @LLMDescription("Header text (optional)") header: String? = null,
        @LLMDescription("Footer text (optional)") footer: String? = null,
        @LLMDescription("Font size (default 10)") fontSize: Float? = 10f
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val fs = fontSize ?: 10f
                for (i in 0 until doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val mb: PDRectangle = page.mediaBox
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setNonStrokingColor(Color(80, 80, 80))
                        cs.beginText()
                        cs.setFont(font, fs)
                        if (!header.isNullOrEmpty()) {
                            cs.newLineAtOffset(36f, mb.height - 24f)
                            cs.showText(header)
                            cs.newLineAtOffset(-36f, -(mb.height - 24f))
                        }
                        if (!footer.isNullOrEmpty()) {
                            cs.newLineAtOffset(36f, 18f)
                            cs.showText(footer)
                        }
                        cs.endText()
                    }
                }
                doc.save(safeOutput(outputPath))
            }
            "Added header/footer and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding header/footer: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add a text box to a specific page at (x,y) with word wrapping up to maxWidth")
    fun addTextBoxToPage(
        @LLMDescription("Source PDF path") pdfPath: String,
        @LLMDescription("Destination PDF path") outputPath: String,
        @LLMDescription("Target page index (0-based)") pageIndex: Int,
        @LLMDescription("Text content (\n for new lines)") text: String,
        @LLMDescription("X coordinate in points (from left)") x: Float,
        @LLMDescription("Y coordinate in points (from bottom)") y: Float,
        @LLMDescription("Max text width in points for wrapping") maxWidth: Float,
        @LLMDescription("Font size (default 12)") fontSize: Float? = 12f,
        @LLMDescription("Text color, e.g., #000000 or 'r,g,b' (0-255), default black") color: String? = null,
    ): String {
        return try {
            Loader.loadPDF(safeInput(pdfPath)).use { doc ->
                PoiSecurity.sanitizePdfDocument(doc)
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                    return "Invalid pageIndex ${pageIndex}, total pages: ${doc.numberOfPages}"
                }
                val page: PDPage = doc.getPage(pageIndex)
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val fs = fontSize ?: 12f
                val lines = wrapTextParagraphs(text, font, fs, maxWidth)
                val colorObj = parseRgbOrHexColor(color)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setNonStrokingColor(colorObj)
                    cs.beginText()
                    cs.setFont(font, fs)
                    cs.newLineAtOffset(x, y)
                    val leading = 1.35f * fs
                    for (line in lines) {
                        cs.showText(line)
                        cs.newLineAtOffset(0f, -leading)
                    }
                    cs.endText()
                }
                doc.save(safeOutput(outputPath))
            }
            "Added text box and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding text box: ${e.message}"
        }
    }

    // Helper: paragraph-aware word wrap (keeps blank lines between `\n\n` paragraphs).
    // Distinct from [wrapText] above; renamed from the old `wrapText` in
    // PDFAdvancedTools to avoid the name collision. Used by [addTextBoxToPage].
    private fun wrapTextParagraphs(
        text: String,
        font: PDFont,
        fontSize: Float,
        maxWidth: Float
    ): List<String> {
        val normalized = text.replace("\r", "")
        val paragraphs = normalized.split("\n\n")
        val allLines = mutableListOf<String>()
        for (para in paragraphs) {
            val words = para.split(" ")
            var line = StringBuilder()
            for ((_, w) in words.withIndex()) {
                val candidate = if (line.isEmpty()) w else line.toString() + " " + w
                val width = font.getStringWidth(candidate) / 1000f * fontSize
                if (width > maxWidth && line.isNotEmpty()) {
                    allLines += line.toString()
                    line = StringBuilder(w)
                } else {
                    if (line.isEmpty()) line.append(w) else line.append(" ").append(w)
                }
            }
            if (line.isNotEmpty()) allLines += line.toString()
            // paragraph spacing: add a blank line between paragraphs
            allLines += ""
        }
        if (allLines.isNotEmpty() && allLines.last().isEmpty()) allLines.removeLast()
        return allLines
    }

    // Helper: accept `#RRGGBB` or `r,g,b` (0-255) — falls back to black on bad input.
    private fun parseRgbOrHexColor(hex: String?): Color {
        if (hex.isNullOrBlank()) return Color(0, 0, 0)
        val s = hex.trim()
        return try {
            if (s.contains(",")) {
                val parts = s.split(',').map { it.trim().toInt() }.toMutableList()
                val r = parts.getOrNull(0)?.coerceIn(0, 255) ?: 0
                val g = parts.getOrNull(1)?.coerceIn(0, 255) ?: 0
                val b = parts.getOrNull(2)?.coerceIn(0, 255) ?: 0
                Color(r, g, b)
            } else {
                val raw = if (s.startsWith("#")) s.substring(1) else s
                val v = raw.toInt(16)
                Color((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
            }
        } catch (e: Throwable) {
            pdfToolsLogger.warn(e) { "Failed to parse color '$hex'; defaulting to black" }
            Color(0, 0, 0)
        }
    }
}
