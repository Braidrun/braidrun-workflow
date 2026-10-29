package com.fartech.agents.runner.document

import com.fartech.agents.tools.*

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

@LLMDescription("Tools for Microsoft Word (.docx) using Apache POI")
class RunnerWordTools(private val io: DocumentToolIO) : ToolSet {

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
        io.begin(docxPath, maxChars)
        return try {
            io.input(docxPath).use { fis ->
                io.word(fis).use { doc ->
                    XWPFWordExtractor(doc).use { extractor ->
                        val text = extractor.text ?: ""
                        val out = text.take((maxChars ?: 20000).coerceIn(0, io.limits.maxTextChars))
                        "Read ${doc.paragraphs.size} paragraphs, ${doc.tables.size} tables. Returning ${out.length} chars.\n" + out
                    }
                }
            }
        } catch (e: Throwable) {
            io.rethrowFailure(e)
            "Error reading DOCX '$docxPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get basic info and core properties of a .docx file")
    fun getDocxInfo(
        @LLMDescription("Path to the .docx file") docxPath: String
    ): String {
        io.begin(docxPath)
        return try {
            io.input(docxPath).use { fis ->
                io.word(fis).use { doc ->
                    val core = doc.properties.coreProperties
                    io.buildText {
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
            io.rethrowFailure(e)
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
        io.begin(text, outputPath, title)
        return try {
            XWPFDocument().use { doc ->
                doc.properties.coreProperties.title = title
                val paragraphs = text.replace("\r", "").split("\n\n")
                paragraphs.forEach { block ->
                    io.check()
                    val p = doc.createParagraph()
                    p.alignment = ParagraphAlignment.LEFT
                    val run = p.createRun()
                    run.setText(block.trim())
                }
                io.saveWord(doc, outputPath)
            }
            "Created DOCX at: $outputPath"
        } catch (e: Throwable) {
            io.rethrowFailure(e)
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
        io.begin(docxPath, text, outputPath)
        return try {
            io.input(docxPath).use { fis ->
                io.word(fis).use { doc ->
                    val p = doc.createParagraph()
                    val run = p.createRun()
                    run.setText(text)
                    io.saveWord(doc, outputPath)
                }
            }
            "Appended text and saved to: $outputPath"
        } catch (e: Throwable) {
            io.rethrowFailure(e)
            "Error appending to DOCX: ${e.message}"
        }
    }
}

@LLMDescription("Tools for Microsoft Excel (.xlsx) using Apache POI")
class RunnerExcelTools(private val io: DocumentToolIO) : ToolSet {

    init {
        PoiSecurity.ensureHardened()
    }

    @Tool
    @LLMDescription("List sheet names and dimensions of an .xlsx file")
    fun listSheets(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String
    ): String {
        io.begin(xlsxPath)
        return try {
            io.input(xlsxPath).use { fis ->
                io.workbook(fis).use { wb ->
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
            io.rethrowFailure(e)
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
        io.begin(xlsxPath, sheetName, startRow, startCol, endRow, endCol, maxRows)
        if (io.limits.runnerIsolation) io.row(startRow)
        if (io.limits.runnerIsolation) io.column(startCol)
        if (io.limits.runnerIsolation) io.row(endRow)
        if (io.limits.runnerIsolation) io.column(endCol)
        fun cellToString(c: Cell?): String {
            if (c == null) return ""
            return when (c.cellType) {
                CellType.NUMERIC -> c.numericCellValue.toString()
                CellType.STRING -> c.stringCellValue
                CellType.BOOLEAN -> c.booleanCellValue.toString()
                CellType.FORMULA -> try {
                    c.numericCellValue.toString()
                } catch (failure: Throwable) {
                    io.rethrowFailure(failure)
                    try { c.stringCellValue } catch (failure: Throwable) {
                        io.rethrowFailure(failure)
                        c.toString() }
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
            io.input(xlsxPath).use { fis ->
                io.workbook(fis).use { wb ->
                    val sheet = wb.getSheet(sheetName) ?: return "Sheet not found: $sheetName"
                    val sb = io.textBuilder()
                    var rows = 0
                    for (r in startRow..endRow) {
                        io.check()
                        if (rows >= effectiveMaxRows) break
                        val row = sheet.getRow(r)
                        for (c in startCol..endCol) {
                            io.check()
                            if (c != startCol) sb.append(',')
                            sb.append('"')
                            val value = cellToString(row?.getCell(c))
                            // Append spans around quotes without copying/expanding the whole value.
                            var start = 0
                            value.forEachIndexed { index, ch ->
                                if (ch == '"') {
                                    sb.append(value, start, index).append("\"\"")
                                    start = index + 1
                                }
                            }
                            sb.append(value, start, value.length).append('"')
                        }
                        sb.append('\n')
                        rows++
                    }
                    sb.toString()
                }
            }
        } catch (e: Throwable) {
            io.rethrowFailure(e)
            "Error reading range: ${e.message}"
        }
    }

    /** Row span cap for [readRangeAsCSV]. Excel hard limit is 1,048,576. */
    internal val MAX_RANGE_ROW_SPAN = 50_000
    /** Column span cap for [readRangeAsCSV]. Excel hard limit is 16,384. */
    internal val MAX_RANGE_COL_SPAN = 1_000
    /**
     * Total-cells cap for [readRangeAsCSV]. The product guard catches narrow-but-tall
     * and wide-but-short ranges that individually pass the span caps but together
     * still represent millions of cells.
     */
    internal val MAX_RANGE_TOTAL_CELLS = 1_000_000L

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
        io.begin(xlsxPath, sheetName, rowIndex, colIndex, value, outputPath)
        if (io.limits.runnerIsolation) io.row(rowIndex)
        if (io.limits.runnerIsolation) io.column(colIndex)
        return try {
            io.input(xlsxPath).use { fis ->
                io.workbook(fis).use { wb ->
                    val sheet = wb.getSheet(sheetName) ?: wb.createSheet(sheetName)
                    val row = sheet.getRow(rowIndex) ?: sheet.createRow(io.rowIndex(rowIndex))
                    val cell = row.getCell(colIndex) ?: row.createCell(io.columnIndex(colIndex))
                    val numeric = value.toDoubleOrNull()
                    if (numeric != null) cell.setCellValue(numeric) else cell.setCellValue(value)
                    io.saveWorkbook(wb, outputPath)
                }
            }
            "Cell written and saved to: $outputPath"
        } catch (e: Throwable) {
            io.rethrowFailure(e)
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
        io.begin(csvText, outputPath, sheet)
        return try {
            XSSFWorkbook().use { wb ->
                val sh = wb.createSheet(sheet ?: "Sheet1")
                val lines = csvText.replace("\r", "").split('\n')
                var r = 0
                for (line in lines) {
                    io.check()
                    if (line.isEmpty()) continue
                    val row = sh.createRow(io.rowIndex(r++))
                    // naive CSV split (no full RFC parser to keep deps minimal)
                    val cells = line.split(',')
                    cells.forEachIndexed { idx, raw ->
                        io.check()
                        val v = raw.trim().trim('"')
                        val cell = row.createCell(io.columnIndex(idx))
                        val num = v.toDoubleOrNull()
                        if (num != null) cell.setCellValue(num) else cell.setCellValue(v)
                    }
                }
                io.saveWorkbook(wb, outputPath)
            }
            "Workbook created at: $outputPath"
        } catch (e: Throwable) {
            io.rethrowFailure(e)
            "Error creating workbook: ${e.message}"
        }
    }
}

@LLMDescription("Tools for Microsoft PowerPoint (.pptx) using Apache POI")
class RunnerPowerPointTools(private val io: DocumentToolIO) : ToolSet {

    init {
        PoiSecurity.ensureHardened()
    }

    @Tool
    @LLMDescription("List slides and extract simple text from a .pptx file")
    fun listSlides(
        @LLMDescription("Path to the .pptx file") pptxPath: String
    ): String {
        io.begin(pptxPath)
        return try {
            io.input(pptxPath).use { fis ->
                io.presentation(fis).use { show ->
                    val slides = show.slides
                    if (slides.isEmpty()) return "No slides."
                    slides.mapIndexed { idx, slide ->
                        val preview = StringBuilder()
                        for (shape in slide.shapes) {
                            io.check()
                            if (preview.length >= 200) break
                            val text = (shape as? XSLFTextShape)?.text.orEmpty()
                            if (preview.isNotEmpty()) preview.append(' ')
                            preview.append(text.take(200 - preview.length))
                        }
                        val text = preview.toString().trim()
                        "${idx}. ${text.take(200)}"
                    }.joinToString("\n")
                }
            }
        } catch (e: Throwable) {
            io.rethrowFailure(e)
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
        io.begin(pptxPath, slideIndex, outputImagePath, width, height)
        io.requireSystemFontRendering()
        return try {
            io.input(pptxPath).use { fis ->
                io.presentation(fis).use { show ->
                    if (slideIndex < 0 || slideIndex >= show.slides.size) return "Invalid slideIndex ${slideIndex}, total: ${show.slides.size}"
                    val slide: XSLFSlide = show.slides[slideIndex]
                    val pg = show.pageSize
                    val target = if (width != null && height != null) Dimension(width, height)
                    else if (width != null) Dimension(width, (width.toDouble() / pg.width * pg.height).toInt())
                    else if (height != null) Dimension((height.toDouble() / pg.height * pg.width).toInt(), height)
                    else Dimension(pg.width, pg.height)

                    io.pixels(target.width.toDouble(), target.height.toDouble())
                    io.requireSystemFontRendering()
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
                    io.writeImage(img, io.filename(outputImagePath).substringAfterLast('.', "png"), outputImagePath)
                    "Saved slide $slideIndex to $outputImagePath"
                }
            }
        } catch (e: Throwable) {
            io.rethrowFailure(e)
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
        io.begin(pptxPath, outputPath, title, body)
        return try {
            io.input(pptxPath).use { fis ->
                io.presentation(fis).use { show ->
                    val slide = io.newSlide(show)
                    slide.createTextBox().apply {
                        anchor = Rectangle(50, 40, 620, 60)
                        textParagraphs[0].textAlign = TextParagraph.TextAlign.CENTER
                        text = title
                    }
                    slide.createTextBox().apply {
                        anchor = Rectangle(60, 120, 600, 360)
                        text = body
                    }
                    io.savePresentation(show, outputPath)
                }
            }
            "Added slide and saved to: $outputPath"
        } catch (e: Throwable) {
            io.rethrowFailure(e)
            "Error adding slide: ${e.message}"
        }
    }
}
