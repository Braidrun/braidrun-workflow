package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.apache.poi.sl.usermodel.TextParagraph
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.imageio.ImageIO

@LLMDescription("Tools for Microsoft Word (.docx) using Apache POI")
object WordTools : ToolSet {

    init {
        // Idempotent — first access hardens POI (zip-bomb / decompression-ratio / XXE
        // sanity-check) before any user-supplied document is parsed.
        PoiSecurity.ensureHardened()
    }

    @Tool
    @LLMDescription("Extract text from a .docx file; returns up to maxChars characters")
    fun readDocxText(
        @LLMDescription("Path to the .docx file") docxPath: String,
        @LLMDescription("Max characters to return (default 20_000)") maxChars: Int? = 20000
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(docxPath)).use { fis ->
                XWPFDocument(fis).use { doc ->
                    XWPFWordExtractor(doc).use { extractor ->
                        val text = extractor.text ?: ""
                        val out = text.take(maxChars ?: 20000)
                        "Read ${doc.paragraphs.size} paragraphs, ${doc.tables.size} tables. Returning ${out.length} chars.\n" + out
                    }
                }
            }
        } catch (e: Throwable) {
            "Error reading DOCX '$docxPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get basic info and core properties of a .docx file")
    fun getDocxInfo(
        @LLMDescription("Path to the .docx file") docxPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(docxPath)).use { fis ->
                XWPFDocument(fis).use { doc ->
                    val core = doc.properties.coreProperties
                    buildString {
                        appendLine("File: $docxPath")
                        appendLine("Paragraphs: ${doc.paragraphs.size}")
                        appendLine("Tables: ${doc.tables.size}")
                        appendLine("Title: ${core.title}")
                        appendLine("Subject: ${core.subject}")
                        appendLine("Creator: ${core.creator}")
                        appendLine("Keywords: ${core.keywords}")
                        appendLine("Category: ${core.category}")
                        appendLine("Created: ${core.created}")
                        appendLine("Modified: ${core.modified}")
                    }
                }
            }
        } catch (e: Throwable) {
            "Error reading DOCX info '$docxPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Create a .docx document from plain text (paragraphs split by blank lines)")
    fun createDocxFromText(
        @LLMDescription("Text content to write") text: String,
        @LLMDescription("Destination .docx path") outputPath: String,
        @LLMDescription("Optional title to set in core properties") title: String? = null
    ): String {
        return try {
            XWPFDocument().use { doc ->
                doc.properties.coreProperties.title = title
                val paragraphs = text.replace("\r", "").split("\n\n")
                paragraphs.forEach { block ->
                    val p = doc.createParagraph()
                    p.alignment = ParagraphAlignment.LEFT
                    val run = p.createRun()
                    run.setText(block.trim())
                }
                FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos ->
                    doc.write(fos)
                }
            }
            "Created DOCX at: $outputPath"
        } catch (e: Throwable) {
            "Error creating DOCX: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Append text to an existing .docx and save to outputPath (non-destructive)")
    fun appendTextToDocx(
        @LLMDescription("Source .docx path") docxPath: String,
        @LLMDescription("Text to append as a new paragraph") text: String,
        @LLMDescription("Destination .docx path (will be created)") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(docxPath)).use { fis ->
                XWPFDocument(fis).use { doc ->
                    val p = doc.createParagraph()
                    val run = p.createRun()
                    run.setText(text)
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos ->
                        doc.write(fos)
                    }
                }
            }
            "Appended text and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error appending to DOCX: ${e.message}"
        }
    }
}

@LLMDescription("Tools for Microsoft Excel (.xlsx) using Apache POI")
object ExcelTools : ToolSet {

    init {
        PoiSecurity.ensureHardened()
    }

    @Tool
    @LLMDescription("List sheet names and dimensions of an .xlsx file")
    fun listSheets(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(xlsxPath)).use { fis ->
                WorkbookFactory.create(fis).use { wb ->
                    val info = (0 until wb.numberOfSheets).joinToString("\n") { idx ->
                        val s = wb.getSheetAt(idx)
                        val firstRow = s.getRow(s.firstRowNum)
                        val rowCount = if (firstRow == null) 0 else s.lastRowNum - s.firstRowNum + 1
                        val lastCell = (firstRow?.lastCellNum?.toInt() ?: 0).coerceAtLeast(0)
                        "${idx + 1}. ${s.sheetName}: rows=$rowCount, cols=$lastCell"
                    }
                    if (info.isBlank()) "Workbook has no sheets" else info
                }
            }
        } catch (e: Throwable) {
            "Error reading XLSX '$xlsxPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Read a cell range as CSV from a specific sheet (0-based indices)")
    fun readRangeAsCSV(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String,
        @LLMDescription("Start row index (0-based)") startRow: Int,
        @LLMDescription("Start column index (0-based)") startCol: Int,
        @LLMDescription("End row index (inclusive, 0-based)") endRow: Int,
        @LLMDescription("End column index (inclusive, 0-based)") endCol: Int,
        @LLMDescription("Maximum rows to return (default 200)") maxRows: Int? = 200
    ): String {
        fun cellToString(c: Cell?): String {
            if (c == null) return ""
            return when (c.cellType) {
                CellType.NUMERIC -> c.numericCellValue.toString()
                CellType.STRING -> c.stringCellValue
                CellType.BOOLEAN -> c.booleanCellValue.toString()
                CellType.FORMULA -> try {
                    c.numericCellValue.toString()
                } catch (_: Throwable) {
                    try { c.stringCellValue } catch (_: Throwable) { c.toString() }
                }

                else -> c.toString()
            }
        }
        // Phase 11: clamp adversarial indices. Without these caps, an
        // attacker (via prompt-injection) supplying `startRow=0,
        // endRow=Int.MAX_VALUE, startCol=0, endCol=Int.MAX_VALUE` causes the
        // nested loop to iterate ~4.6e18 cells, OOMing or pinning the JVM.
        if (startRow < 0 || startCol < 0 || endRow < startRow || endCol < startCol) {
            return "Error: invalid range indices"
        }
        val rowSpan = endRow - startRow + 1
        val colSpan = endCol - startCol + 1
        if (rowSpan > MAX_RANGE_ROW_SPAN) {
            return "Error: row span $rowSpan exceeds cap $MAX_RANGE_ROW_SPAN"
        }
        if (colSpan > MAX_RANGE_COL_SPAN) {
            return "Error: column span $colSpan exceeds cap $MAX_RANGE_COL_SPAN"
        }
        if (rowSpan.toLong() * colSpan.toLong() > MAX_RANGE_TOTAL_CELLS) {
            return "Error: range area $rowSpan x $colSpan exceeds cap $MAX_RANGE_TOTAL_CELLS cells"
        }
        val effectiveMaxRows = (maxRows ?: 200).coerceIn(1, MAX_RANGE_ROW_SPAN)
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(xlsxPath)).use { fis ->
                WorkbookFactory.create(fis).use { wb ->
                    val sheet = wb.getSheet(sheetName) ?: return "Sheet not found: $sheetName"
                    val sb = StringBuilder()
                    var rows = 0
                    for (r in startRow..endRow) {
                        if (rows >= effectiveMaxRows) break
                        val row = sheet.getRow(r)
                        val line = (startCol..endCol).joinToString(",") { c ->
                            val v = cellToString(row?.getCell(c))
                            '"' + v.replace("\"", "\"\"") + '"'
                        }
                        sb.append(line).append('\n')
                        rows++
                    }
                    sb.toString()
                }
            }
        } catch (e: Throwable) {
            "Error reading range: ${e.message}"
        }
    }

    /** Row span cap for [readRangeAsCSV]. Excel hard limit is 1,048,576. */
    internal const val MAX_RANGE_ROW_SPAN = 50_000
    /** Column span cap for [readRangeAsCSV]. Excel hard limit is 16,384. */
    internal const val MAX_RANGE_COL_SPAN = 1_000
    /**
     * Total-cells cap for [readRangeAsCSV]. The product guard catches narrow-but-tall
     * and wide-but-short ranges that individually pass the span caps but together
     * still represent millions of cells.
     */
    internal const val MAX_RANGE_TOTAL_CELLS = 1_000_000L

    @Tool
    @LLMDescription("Write a single cell (as text) to a sheet and save to outputPath (non-destructive)")
    fun writeCell(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String,
        @LLMDescription("Row index (0-based)") rowIndex: Int,
        @LLMDescription("Column index (0-based)") colIndex: Int,
        @LLMDescription("Value to write (string; numbers will be parsed if possible)") value: String,
        @LLMDescription("Destination .xlsx path (will be created)") outputPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(xlsxPath)).use { fis ->
                WorkbookFactory.create(fis).use { wb ->
                    val sheet = wb.getSheet(sheetName) ?: wb.createSheet(sheetName)
                    val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                    val cell = row.getCell(colIndex) ?: row.createCell(colIndex)
                    val numeric = value.toDoubleOrNull()
                    if (numeric != null) cell.setCellValue(numeric) else cell.setCellValue(value)
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos ->
                        wb.write(fos)
                    }
                }
            }
            "Cell written and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error writing cell: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Create a new workbook from CSV text (single sheet)")
    fun createWorkbookFromCSV(
        @LLMDescription("CSV text (comma-separated; quotes supported if pre-escaped)") csvText: String,
        @LLMDescription("Destination .xlsx path") outputPath: String,
        @LLMDescription("Sheet name (default 'Sheet1')") sheet: String? = null
    ): String {
        return try {
            XSSFWorkbook().use { wb ->
                val sh = wb.createSheet(sheet ?: "Sheet1")
                val lines = csvText.replace("\r", "").split('\n')
                var r = 0
                for (line in lines) {
                    if (line.isEmpty()) continue
                    val row = sh.createRow(r++)
                    // naive CSV split (no full RFC parser to keep deps minimal)
                    val cells = line.split(',')
                    cells.forEachIndexed { idx, raw ->
                        val v = raw.trim().trim('"')
                        val cell = row.createCell(idx)
                        val num = v.toDoubleOrNull()
                        if (num != null) cell.setCellValue(num) else cell.setCellValue(v)
                    }
                }
                FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos ->
                    wb.write(fos)
                }
            }
            "Workbook created at: $outputPath"
        } catch (e: Throwable) {
            "Error creating workbook: ${e.message}"
        }
    }
}

@LLMDescription("Tools for Microsoft PowerPoint (.pptx) using Apache POI")
object PowerPointTools : ToolSet {

    init {
        PoiSecurity.ensureHardened()
    }

    @Tool
    @LLMDescription("List slides and extract simple text from a .pptx file")
    fun listSlides(
        @LLMDescription("Path to the .pptx file") pptxPath: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    val slides = show.slides
                    if (slides.isEmpty()) return "No slides."
                    slides.mapIndexed { idx, slide ->
                        val text = slide.shapes.joinToString(" ") { shape ->
                            (shape as? XSLFTextShape)?.text ?: ""
                        }.trim()
                        "${idx}. ${text.take(200)}"
                    }.joinToString("\n")
                }
            }
        } catch (e: Throwable) {
            "Error reading PPTX '$pptxPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Render a slide to an image (PNG). Index is 0-based. Auto scales to width/height if provided.")
    fun renderSlideToImage(
        @LLMDescription("Path to the .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Output image path, e.g., slide.png") outputImagePath: String,
        @LLMDescription("Target width (optional); height will scale proportionally if only width provided") width: Int? = null,
        @LLMDescription("Target height (optional)") height: Int? = null
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    if (slideIndex < 0 || slideIndex >= show.slides.size) return "Invalid slideIndex ${slideIndex}, total: ${show.slides.size}"
                    val slide: XSLFSlide = show.slides[slideIndex]
                    val pg = show.pageSize
                    val target = if (width != null && height != null) Dimension(width, height)
                    else if (width != null) Dimension(width, (width.toDouble() / pg.width * pg.height).toInt())
                    else if (height != null) Dimension((height.toDouble() / pg.height * pg.width).toInt(), height)
                    else Dimension(pg.width, pg.height)

                    val img = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
                    val g2d: Graphics2D = img.createGraphics()
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    val scaleX = target.width / pg.width.toDouble()
                    val scaleY = target.height / pg.height.toDouble()
                    g2d.scale(scaleX, scaleY)
                    g2d.color = Color.WHITE
                    g2d.fillRect(0, 0, pg.width, pg.height)
                    slide.draw(g2d)
                    g2d.dispose()
                    val out = ToolPathSecurity.validateOutputPath(outputImagePath)
                    ImageIO.write(img, out.extension.ifEmpty { "png" }, out)
                    "Saved slide $slideIndex to ${out.absolutePath}"
                }
            }
        } catch (e: Throwable) {
            "Error rendering slide: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add a new slide with title and body text; save to outputPath (non-destructive)")
    fun addSlideWithText(
        @LLMDescription("Path to the .pptx file") pptxPath: String,
        @LLMDescription("Destination .pptx path") outputPath: String,
        @LLMDescription("Title text") title: String,
        @LLMDescription("Body text") body: String
    ): String {
        return try {
            FileInputStream(ToolPathSecurity.validateInputPath(pptxPath)).use { fis ->
                XMLSlideShow(fis).use { show ->
                    val slide = show.createSlide()
                    slide.createTextBox().apply {
                        anchor = Rectangle(50, 40, 620, 60)
                        textParagraphs[0].textAlign = TextParagraph.TextAlign.CENTER
                        text = title
                    }
                    slide.createTextBox().apply {
                        anchor = Rectangle(60, 120, 600, 360)
                        text = body
                    }
                    FileOutputStream(ToolPathSecurity.validateOutputPath(outputPath)).use { fos ->
                        show.write(fos)
                    }
                }
            }
            "Added slide and saved to: $outputPath"
        } catch (e: Throwable) {
            "Error adding slide: ${e.message}"
        }
    }
}
