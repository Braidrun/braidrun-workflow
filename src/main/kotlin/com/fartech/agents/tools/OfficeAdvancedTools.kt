package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.MarkdownToWordConverter
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.util.Units
import org.apache.poi.xslf.usermodel.*
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.imageio.ImageIO

/**
 * ## Phase 6 deprecation notice
 *
 * The historical `WordTools` / `WordAdvancedTools` / `WordEnhancedTools` split
 * was originally a "basic / advanced / enhanced" tier progression, but the
 * ToolRegistryBuilder already registered all three under a single `word`
 * tool group — so the tier distinction was visible only to Kotlin code, not
 * to the LLM or to workflow YAML.
 *
 * To simplify the architecture, this class is now `@Deprecated`. Its @Tool
 * bodies continue to exist and are still registered when the `word` tool
 * group is requested (so behaviour is unchanged for workflows), but direct
 * Kotlin instantiation — `tools(WordAdvancedTools())` — should migrate to
 * `parseToolSet(parameters, httpAccess, listOf("word"))` which composes the
 * full Word surface automatically.
 *
 * The class stays in place until at least one release cycle has passed to
 * give external Kotlin integrators time to migrate.
 */
@LLMDescription("Advanced Word (.docx) tools: images, find/replace, tables")
@Deprecated(
    message = "Use the `word` tool group via parseToolSet(listOf(\"word\")) — it already registers WordTools + WordAdvancedTools + WordEnhancedTools. This class will be removed once external Kotlin callers migrate.",
    level = DeprecationLevel.WARNING
)
class WordAdvancedTools : ToolSet {

    init { PoiSecurity.ensureHardened() }

    @Tool
    @LLMDescription("Insert an image into a .docx at the end of document")
    fun insertImage(
        @LLMDescription("Path to the .docx file") docxPath: String,
        @LLMDescription("Path to image (png/jpg)") imagePath: String,
        @LLMDescription("Destination .docx path") outputPath: String,
        @LLMDescription("Image width in pixels") widthPx: Int,
        @LLMDescription("Image height in pixels") heightPx: Int
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(docxPath)).use { fis ->
                XWPFDocument(fis).use { doc ->
                    val para = doc.createParagraph()
                    para.alignment = ParagraphAlignment.CENTER
                    val run = para.createRun()
                    val safeImage = ToolPathSecurity.validateInputPath(imagePath)
                    FileInputStream(safeImage).use { iis ->
                        run.addPicture(
                            iis,
                            when (safeImage.extension.lowercase()) {
                                "png" -> Document.PICTURE_TYPE_PNG
                                "jpg", "jpeg" -> Document.PICTURE_TYPE_JPEG
                                "gif" -> Document.PICTURE_TYPE_GIF
                                else -> Document.PICTURE_TYPE_PNG
                            },
                            safeImage.name,
                            Units.toEMU(widthPx.toDouble()),
                            Units.toEMU(heightPx.toDouble())
                        )
                    }
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> doc.write(fos) }
                }
            }
            "Inserted image and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error inserting image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Find and replace all occurrences of a string in .docx (simple; formatting may be lost in affected paragraphs)")
    fun findAndReplaceAll(
        @LLMDescription("Path to the .docx file") docxPath: String,
        @LLMDescription("Text to find") findText: String,
        @LLMDescription("Replacement text") replaceWith: String,
        @LLMDescription("Destination .docx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(docxPath)).use { fis ->
                XWPFDocument(fis).use { doc ->
                    for (p in doc.paragraphs) {
                        val text = p.text
                        if (text.contains(findText)) {
                            // Rebuild paragraph with single run; always remove from index 0
                            // since the list shrinks after each removal
                            val runCount = p.runs.size
                            repeat(runCount) { p.removeRun(0) }
                            val run = p.createRun()
                            run.setText(text.replace(findText, replaceWith))
                        }
                    }
                    for (tbl in doc.tables) {
                        for (row in tbl.rows) {
                            for (cell in row.tableCells) {
                                val paraList = cell.paragraphs
                                for (p in paraList) {
                                    val text = p.text
                                    if (text.contains(findText)) {
                                        val runCount = p.runs.size
                                        repeat(runCount) { p.removeRun(0) }
                                        val run = p.createRun()
                                        run.setText(text.replace(findText, replaceWith))
                                    }
                                }
                            }
                        }
                    }
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> doc.write(fos) }
                }
            }
            "Replaced text and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error find/replace: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Create a simple table in .docx from CSV (comma-separated) and append at end")
    fun createTableFromCSV(
        @LLMDescription("Path to the .docx file") docxPath: String,
        @LLMDescription("CSV text with rows separated by \n") csvText: String,
        @LLMDescription("Destination .docx path") outputPath: String
    ): String {
        return try {
            val rows = csvText.trim().split("\n").filter { it.isNotBlank() }.map { it.split(",") }
            if (rows.isEmpty()) return "CSV is empty"
            FileInputStream(ToolPathSecurity.validateInputPath(docxPath)).use { fis ->
                XWPFDocument(fis).use { doc ->
                    val table = doc.createTable(rows.size, rows.maxOf { it.size })
                    for ((rIdx, row) in rows.withIndex()) {
                        val tRow = table.getRow(rIdx)
                        for ((cIdx, v) in row.withIndex()) {
                            val cell = tRow.getCell(cIdx)
                            cell.removeParagraph(0)
                            val p = cell.addParagraph()
                            val run = p.createRun()
                            run.setText(v.trim())
                        }
                    }
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> doc.write(fos) }
                }
            }
            "Appended table and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error creating table: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Create a .docx document from Markdown text with full formatting support (headings, bold, italic, lists, tables, code blocks, etc.)")
    fun createDocxFromMarkdown(
        @LLMDescription("Markdown text content") markdownText: String,
        @LLMDescription("Destination .docx path") outputPath: String,
        @LLMDescription("Optional title to set in core properties") title: String? = null
    ): String {
        return try {
            val outputFile = ToolPathSecurity.validateOutputPath(outputPath)
            MarkdownToWordConverter.convertToWord(
                markdownText = markdownText,
                outputFile = outputFile,
                title = title
            )
            "Created DOCX from Markdown at: $outputPath"
        } catch (e: Throwable) {
            "Error creating DOCX from Markdown: ${e.message}"
        }
    }
}

@LLMDescription("Advanced Excel (.xlsx) tools: append rows, auto-size, add sheet")
@Deprecated(
    message = "Use the `excel` tool group via parseToolSet(listOf(\"excel\")) — it already registers ExcelTools + ExcelAdvancedTools + ExcelEnhancedTools. This class will be removed once external Kotlin callers migrate.",
    level = DeprecationLevel.WARNING
)
class ExcelAdvancedTools : ToolSet {

    @Tool
    @LLMDescription("Append rows from CSV text to an existing sheet (creates if missing)")
    fun appendRowsFromCSV(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name to append to") sheetName: String,
        @LLMDescription("CSV text (rows separated by \n; columns by ,)") csvText: String,
        @LLMDescription("Destination .xlsx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(xlsxPath)).use { fis ->
                WorkbookFactory.create(fis).use { wb ->
                    val sheet = wb.getSheet(sheetName) ?: wb.createSheet(sheetName)
                    var rowIdx = (sheet.lastRowNum.takeIf { it >= 0 } ?: -1) + 1
                    val rows = csvText.trim().split("\n").filter { it.isNotBlank() }.map { it.split(",") }
                    for (r in rows) {
                        val row = sheet.createRow(rowIdx++)
                        for ((cIdx, v) in r.withIndex()) {
                            val cell = row.createCell(cIdx)
                            val num = v.toDoubleOrNull()
                            if (num != null) cell.setCellValue(num) else cell.setCellValue(v)
                        }
                    }
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> wb.write(fos) }
                }
            }
            "Appended ${csvText.lines().size} rows to '$sheetName' and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error appending rows: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Auto-size columns for a sheet based on current content")
    fun autoSizeColumns(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String,
        @LLMDescription("Destination .xlsx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(xlsxPath)).use { fis ->
                WorkbookFactory.create(fis).use { wb ->
                    val sheet = wb.getSheet(sheetName) ?: return "Sheet not found: $sheetName"
                    val maxCols = (sheet.firstRowNum..sheet.lastRowNum).mapNotNull { idx ->
                        val row = sheet.getRow(idx) ?: return@mapNotNull null
                        row.lastCellNum.toInt()
                    }.maxOrNull() ?: 0
                    for (c in 0 until maxCols) sheet.autoSizeColumn(c)
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> wb.write(fos) }
                }
            }
            "Auto-sized columns on '$sheetName' and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error auto-sizing columns: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add a new sheet to workbook")
    fun addSheet(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String,
        @LLMDescription("New sheet name") newSheetName: String,
        @LLMDescription("Destination .xlsx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(xlsxPath)).use { fis ->
                WorkbookFactory.create(fis).use { wb ->
                    if (wb.getSheet(newSheetName) != null) return "Sheet already exists: $newSheetName"
                    wb.createSheet(newSheetName)
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> wb.write(fos) }
                }
            }
            "Added sheet '$newSheetName' and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding sheet: ${e.message}"
        }
    }
}

@LLMDescription("Advanced PowerPoint (.pptx) tools: create, images, tables, bullets, duplicate/merge, export")
@Deprecated(
    message = "Use the `powerpoint` tool group via parseToolSet(listOf(\"powerpoint\")) — it already registers PowerPointTools + PowerPointAdvancedTools + PowerPointEnhancedTools. This class will be removed once external Kotlin callers migrate.",
    level = DeprecationLevel.WARNING
)
class PowerPointAdvancedTools : ToolSet {

    @Tool
    @LLMDescription("Create a new .pptx with a single title slide")
    fun createPresentation(
        @LLMDescription("Presentation title") title: String,
        @LLMDescription("Destination .pptx path") outputPath: String
    ): String {
        return try {
            XMLSlideShow().use { show ->
                val slide = show.createSlide()
                slide.createTextBox().apply {
                    anchor = Rectangle(80, 80, 600, 60)
                    text = title
                }
                FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> show.write(fos) }
            }
            "Created presentation at: $outputPath"
        } catch (e: Throwable) {
            "Error creating presentation: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add an image to an existing slide at coordinates")
    fun addImageToSlide(
        @LLMDescription("Path to the .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Path to image (png/jpg)") imagePath: String,
        @LLMDescription("X position in pixels") x: Int,
        @LLMDescription("Y position in pixels") y: Int,
        @LLMDescription("Width in pixels") width: Int,
        @LLMDescription("Height in pixels") height: Int,
        @LLMDescription("Destination .pptx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    if (slideIndex !in show.slides.indices) return "Invalid slide index $slideIndex, total: ${show.slides.size}"
                    val slide = show.slides[slideIndex]
                    val safeImage = ToolPathSecurity.validateInputPath(imagePath)
                    val pictureData = show.addPicture(
                        safeImage, when (safeImage.extension.lowercase()) {
                            "png" -> PictureData.PictureType.PNG
                            "jpg", "jpeg" -> PictureData.PictureType.JPEG
                            "gif" -> PictureData.PictureType.GIF
                            else -> PictureData.PictureType.PNG
                        }
                    )
                    val shape: XSLFPictureShape = slide.createPicture(pictureData)
                    shape.anchor = Rectangle(x, y, width, height)
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> show.write(fos) }
                }
            }
            "Added image to slide and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding image to slide: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add a table to a slide with optional CSV content")
    fun addTableToSlide(
        @LLMDescription("Path to the .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Top-left X in pixels") x: Int,
        @LLMDescription("Top-left Y in pixels") y: Int,
        @LLMDescription("Row count") rows: Int,
        @LLMDescription("Column count") cols: Int,
        @LLMDescription("CSV data for table (optional)") csv: String? = null,
        @LLMDescription("Destination .pptx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    if (slideIndex !in show.slides.indices) return "Invalid slide index $slideIndex, total: ${show.slides.size}"
                    val slide = show.slides[slideIndex]
                    val table: XSLFTable = slide.createTable(rows, cols)
                    table.anchor = Rectangle(x, y, cols * 100, rows * 24)
                    val parsed = csv?.trim()?.takeIf { it.isNotEmpty() }?.split("\n")?.map { it.split(",") }
                    for (r in 0 until rows) {
                        val row = table.rows[r]
                        for (c in 0 until cols) {
                            val cell = row.cells[c]
                            val v = parsed?.getOrNull(r)?.getOrNull(c)?.trim() ?: ""
                            // Use the existing default paragraph instead of adding a new one
                            val p = cell.textParagraphs.firstOrNull() ?: cell.addNewTextParagraph()
                            val run = p.addNewTextRun()
                            run.setText(v)
                        }
                    }
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> show.write(fos) }
                }
            }
            "Added table and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding table: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add a bullet list text box to a slide")
    fun addBulletList(
        @LLMDescription("Path to the .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("X in pixels") x: Int,
        @LLMDescription("Y in pixels") y: Int,
        @LLMDescription("Width in pixels") width: Int,
        @LLMDescription("Height in pixels") height: Int,
        @LLMDescription("List items") items: List<String>,
        @LLMDescription("Destination .pptx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    if (slideIndex !in show.slides.indices) return "Invalid slide index $slideIndex, total: ${show.slides.size}"
                    val slide: XSLFSlide = show.slides[slideIndex]
                    val box: XSLFTextBox = slide.createTextBox()
                    box.anchor = Rectangle(x, y, width, height)
                    items.forEach { text ->
                        val p = box.addNewTextParagraph()
                        p.isBullet = true
                        p.addNewTextRun().setText(text)
                    }
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> show.write(fos) }
                }
            }
            "Added bullet list and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding bullet list: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Duplicate a slide (imports content) and append to the end")
    fun duplicateSlide(
        @LLMDescription("Path to the .pptx file") pptxPath: String,
        @LLMDescription("Slide index to duplicate (0-based)") slideIndex: Int,
        @LLMDescription("Destination .pptx path") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    if (slideIndex !in show.slides.indices) return "Invalid slide index $slideIndex, total: ${show.slides.size}"
                    val src = show.slides[slideIndex]
                    val dst = show.createSlide()
                    dst.importContent(src)
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> show.write(fos) }
                }
            }
            "Duplicated slide and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error duplicating slide: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Merge multiple presentations into one (slides appended in order)")
    fun mergePresentations(
        @LLMDescription("List of .pptx paths to merge in order") sourcePaths: List<String>,
        @LLMDescription("Destination .pptx path") outputPath: String
    ): String {
        return try {
            if (sourcePaths.isEmpty()) return "No sources provided"
            XMLSlideShow().use { dest ->
                for (p in sourcePaths) {
                    FileInputStream(ToolPathSecurity.validateInputPath(p)).use { fis ->
                        XMLSlideShow(fis).use { src ->
                            for (slide in src.slides) {
                                val newSlide = dest.createSlide()
                                newSlide.importContent(slide)
                            }
                        }
                    }
                }
                FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos -> dest.write(fos) }
            }
            "Merged ${sourcePaths.size} presentations into: $outputPath"
        } catch (e: Throwable) {
            "Error merging presentations: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Export all slides to images (PNG) into a directory")
    fun exportAllSlidesToImages(
        @LLMDescription("Path to the .pptx file") pptxPath: String,
        @LLMDescription("Output directory path") outputDir: String,
        @LLMDescription("Target width (optional)") width: Int? = null,
        @LLMDescription("Target height (optional)") height: Int? = null
    ): String {
        return try {
            lateinit var exportedDirectory: File
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    val pgsize: Dimension = show.pageSize
                    val (w, h) = if (width == null && height == null) {
                        pgsize.width to pgsize.height
                    } else if (width != null && height != null) {
                        width to height
                    } else if (width != null) {
                        val scale = width.toDouble() / pgsize.width
                        width to (pgsize.height * scale).toInt()
                    } else {
                        val scale = height!!.toDouble() / pgsize.height
                        (pgsize.width * scale).toInt() to height
                    }
                    val marker = ToolPathSecurity.validateOutputPath(File(outputDir, ".braidrun-workflow-dir-check").path)
                    val dir = marker.parentFile
                    exportedDirectory = dir
                    show.slides.forEachIndexed { idx, slide ->
                        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        val g: Graphics2D = img.createGraphics()
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g.scale(w.toDouble() / pgsize.width, h.toDouble() / pgsize.height)
                        slide.draw(g)
                        g.dispose()
                        val outFile = ToolPathSecurity.validateOutputPath(File(dir, "slide_${idx.toString().padStart(3, '0')}.png").path)
                        ImageIO.write(img, "png", outFile)
                    }
                }
            }
            "Exported slides to directory: ${exportedDirectory.absolutePath}"
        } catch (e: Throwable) {
            "Error exporting slides: ${e.message}"
        }
    }
}
