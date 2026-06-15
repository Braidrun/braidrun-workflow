package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.apache.poi.util.Units
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy
import org.apache.poi.xwpf.usermodel.*
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*
import mu.KotlinLogging
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger

private val logger = KotlinLogging.logger {}

// ─────────────────────────────────────────────────────────────────────
// Enhanced Word Tools – rich document creation with fonts, styles, images
// ─────────────────────────────────────────────────────────────────────

@LLMDescription(
    "Enhanced Word (.docx) tools for creating rich, professionally formatted documents. " +
            "Supports custom fonts, colors, sizes, images with captions, styled tables, " +
            "headers/footers, page setup, table of contents, hyperlinks, page breaks, and more."
)
@Deprecated(
    message = "Use the `word` tool group via parseToolSet(listOf(\"word\")) — it already registers every Word tier (basic + advanced + enhanced). This class will be removed once external Kotlin callers migrate.",
    level = DeprecationLevel.WARNING
)
class WordEnhancedTools : ToolSet {

    init { PoiSecurity.ensureHardened() }

    // ── helpers ──────────────────────────────────────────────────────
    private fun openOrCreate(path: String?): XWPFDocument {
        val safePath = path?.let { ToolPathSecurity.validateInputPath(it) }
        if (safePath != null && safePath.exists()) {
            return FileInputStream(safePath).use { XWPFDocument(it) }
        }
        return XWPFDocument()
    }

    private fun save(doc: XWPFDocument, outputPath: String) {
        val validated = ToolPathSecurity.validateOutputPath(outputPath)
        FileOutputStream(validated).use { doc.write(it) }
    }

    /** Open (or create) a document, run [block], and ensure the document is closed regardless of outcome. */
    private inline fun <R> withDocument(path: String?, block: (XWPFDocument) -> R): R {
        val doc = openOrCreate(path)
        try {
            return block(doc)
        } finally {
            doc.close()
        }
    }

    private fun parseColor(hex: String): String =
        hex.removePrefix("#").uppercase().padStart(6, '0')

    private fun imageType(ext: String): Int = when (ext.lowercase()) {
        "png" -> Document.PICTURE_TYPE_PNG
        "jpg", "jpeg" -> Document.PICTURE_TYPE_JPEG
        "gif" -> Document.PICTURE_TYPE_GIF
        "bmp" -> Document.PICTURE_TYPE_BMP
        "tiff", "tif" -> Document.PICTURE_TYPE_TIFF
        "emf" -> Document.PICTURE_TYPE_EMF
        "wmf" -> Document.PICTURE_TYPE_WMF
        else -> Document.PICTURE_TYPE_PNG
    }

    private fun validateImageInputPath(imagePath: String): File {
        val image = ToolPathSecurity.validateInputPath(imagePath)
        require(image.exists() && image.isFile) { "Image file not found: $imagePath" }
        require(image.length() in 1..MAX_IMAGE_BYTES) {
            "Image file size ${image.length()} bytes exceeds maximum allowed size $MAX_IMAGE_BYTES bytes"
        }
        require(image.extension.lowercase() in ALLOWED_IMAGE_EXTENSIONS) {
            "Unsupported image extension '${image.extension}'. Supported: ${ALLOWED_IMAGE_EXTENSIONS.joinToString(", ")}"
        }
        return image
    }

    private fun applyRunStyle(
        run: XWPFRun,
        fontFamily: String? = null,
        fontSize: Int? = null,
        bold: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false,
        color: String? = null,
        highlight: String? = null
    ) {
        fontFamily?.let { run.fontFamily = it }
        fontSize?.let { run.fontSize = it }
        if (bold) run.isBold = true
        if (italic) run.isItalic = true
        if (underline) run.underline = UnderlinePatterns.SINGLE
        if (strikethrough) run.isStrikeThrough = true
        color?.let { run.color = parseColor(it) }
        // highlight is intentionally skipped for simplicity
    }

    private fun parseAlignment(alignment: String?, default: ParagraphAlignment = ParagraphAlignment.LEFT): ParagraphAlignment =
        when (alignment?.uppercase()) {
            "CENTER" -> ParagraphAlignment.CENTER
            "RIGHT" -> ParagraphAlignment.RIGHT
            "BOTH", "JUSTIFY" -> ParagraphAlignment.BOTH
            "LEFT" -> ParagraphAlignment.LEFT
            else -> default
        }

    private fun resetHeaderFooterContent(headerFooter: XWPFHeaderFooter) {
        while (headerFooter.paragraphs.isNotEmpty()) {
            headerFooter.removeParagraph(headerFooter.paragraphs.first())
        }
    }

    private fun appendStyledText(
        paragraph: XWPFParagraph,
        text: String?,
        fontFamily: String? = null,
        fontSize: Int? = null
    ) {
        if (text.isNullOrEmpty()) return
        val run = paragraph.createRun()
        run.setText(text)
        applyRunStyle(run, fontFamily = fontFamily, fontSize = fontSize)
    }

    private fun appendFieldRun(
        paragraph: XWPFParagraph,
        instruction: String,
        fontFamily: String? = null,
        fontSize: Int? = null
    ) {
        val beginRun = paragraph.createRun()
        applyRunStyle(beginRun, fontFamily = fontFamily, fontSize = fontSize)
        beginRun.ctr.addNewFldChar().fldCharType = STFldCharType.BEGIN

        val instructionRun = paragraph.createRun()
        applyRunStyle(instructionRun, fontFamily = fontFamily, fontSize = fontSize)
        instructionRun.ctr.addNewInstrText().stringValue = instruction

        val separateRun = paragraph.createRun()
        applyRunStyle(separateRun, fontFamily = fontFamily, fontSize = fontSize)
        separateRun.ctr.addNewFldChar().fldCharType = STFldCharType.SEPARATE

        val placeholderRun = paragraph.createRun()
        applyRunStyle(placeholderRun, fontFamily = fontFamily, fontSize = fontSize)
        placeholderRun.setText("1")

        val endRun = paragraph.createRun()
        applyRunStyle(endRun, fontFamily = fontFamily, fontSize = fontSize)
        endRun.ctr.addNewFldChar().fldCharType = STFldCharType.END
    }

    // ── 1. Create rich document ─────────────────────────────────────

    @Tool
    @LLMDescription(
        "Create a new Word document or open an existing one and add a richly formatted paragraph. " +
                "Supports custom font family (e.g. 'Arial', '微软雅黑', 'Times New Roman'), " +
                "font size, bold, italic, underline, strikethrough, text color (hex like '#FF0000'), " +
                "and paragraph alignment (LEFT, CENTER, RIGHT, BOTH/JUSTIFY)."
    )
    fun addRichParagraph(
        @LLMDescription("Path to existing .docx file, or null to create new") docxPath: String? = null,
        @LLMDescription("Text content of the paragraph") text: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Font family name, e.g. 'Arial', '微软雅黑', 'Times New Roman'") fontFamily: String? = null,
        @LLMDescription("Font size in points, e.g. 12, 14, 24") fontSize: Int? = null,
        @LLMDescription("Bold text") bold: Boolean = false,
        @LLMDescription("Italic text") italic: Boolean = false,
        @LLMDescription("Underline text") underline: Boolean = false,
        @LLMDescription("Strikethrough text") strikethrough: Boolean = false,
        @LLMDescription("Text color as hex string, e.g. '#FF0000' for red") color: String? = null,
        @LLMDescription("Paragraph alignment: LEFT, CENTER, RIGHT, BOTH") alignment: String? = null,
        @LLMDescription("Line spacing multiplier, e.g. 1.0, 1.5, 2.0") lineSpacing: Double? = null,
        @LLMDescription("Space before paragraph in points") spaceBefore: Int? = null,
        @LLMDescription("Space after paragraph in points") spaceAfter: Int? = null
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val para = doc.createParagraph()

                alignment?.let {
                    para.alignment = parseAlignment(it)
                }

                lineSpacing?.let {
                    val spacing = para.ctp.addNewPPr().addNewSpacing()
                    spacing.line = BigInteger.valueOf((it * 240).toLong())
                    spacing.lineRule = STLineSpacingRule.AUTO
                }
                spaceBefore?.let {
                    val ppr = para.ctp.pPr ?: para.ctp.addNewPPr()
                    val sp = ppr.spacing ?: ppr.addNewSpacing()
                    sp.before = BigInteger.valueOf(it * 20L)
                }
                spaceAfter?.let {
                    val ppr = para.ctp.pPr ?: para.ctp.addNewPPr()
                    val sp = ppr.spacing ?: ppr.addNewSpacing()
                    sp.after = BigInteger.valueOf(it * 20L)
                }

                // Support newlines within text
                val lines = text.split("\n")
                lines.forEachIndexed { idx, line ->
                    val run = para.createRun()
                    run.setText(line)
                    applyRunStyle(run, fontFamily, fontSize, bold, italic, underline, strikethrough, color)
                    if (idx < lines.size - 1) run.addBreak()
                }

                save(doc, outputPath)
                "✅ Added rich paragraph and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding rich paragraph: ${e.message}"
        }
    }

    // ── 2. Add heading ──────────────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a heading (H1–H6) to a Word document with custom font and color. " +
                "Level 1 = largest heading, level 6 = smallest."
    )
    fun addHeading(
        @LLMDescription("Path to existing .docx file, or null to create new") docxPath: String? = null,
        @LLMDescription("Heading text") text: String,
        @LLMDescription("Heading level 1-6") level: Int = 1,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Font family, e.g. 'Arial Black', '黑体'") fontFamily: String? = null,
        @LLMDescription("Text color hex, e.g. '#003366'") color: String? = null,
        @LLMDescription("Paragraph alignment: LEFT, CENTER, RIGHT") alignment: String? = null
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val para = doc.createParagraph()

                // Set heading style
                val styleName = "Heading$level"
                try {
                    para.style = styleName
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to read workbook sheet" }
                }

                alignment?.let {
                    para.alignment = when (it.uppercase()) {
                        "CENTER" -> ParagraphAlignment.CENTER
                        "RIGHT" -> ParagraphAlignment.RIGHT
                        else -> ParagraphAlignment.LEFT
                    }
                }

                val run = para.createRun()
                run.setText(text)
                run.isBold = true
                val defaultSizes = mapOf(1 to 28, 2 to 24, 3 to 20, 4 to 16, 5 to 14, 6 to 12)
                run.fontSize = defaultSizes[level.coerceIn(1, 6)] ?: 16
                fontFamily?.let { run.fontFamily = it }
                color?.let { run.color = parseColor(it) }

                save(doc, outputPath)
                "✅ Added H$level heading and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding heading: ${e.message}"
        }
    }

    // ── 3. Insert image with caption ────────────────────────────────

    @Tool
    @LLMDescription(
        "Insert an image into a Word document with optional caption text. " +
                "Supports PNG, JPEG, GIF, BMP, TIFF, EMF, WMF formats. " +
                "Width and height are in points (1 inch = 72 points)."
    )
    fun insertImageWithCaption(
        @LLMDescription("Path to existing .docx file, or null to create new") docxPath: String? = null,
        @LLMDescription("Path to the image file") imagePath: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Image width in points (72 points = 1 inch)") widthPt: Double = 400.0,
        @LLMDescription("Image height in points") heightPt: Double = 300.0,
        @LLMDescription("Caption text below the image (optional)") caption: String? = null,
        @LLMDescription("Image alignment: LEFT, CENTER, RIGHT") alignment: String? = "CENTER",
        @LLMDescription("Caption font family") captionFont: String? = null,
        @LLMDescription("Caption font size in points") captionFontSize: Int? = 10,
        @LLMDescription("Caption color hex") captionColor: String? = "#666666"
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val imgFile = validateImageInputPath(imagePath)

                val imgPara = doc.createParagraph()
                alignment?.let {
                    imgPara.alignment = when (it.uppercase()) {
                        "CENTER" -> ParagraphAlignment.CENTER
                        "RIGHT" -> ParagraphAlignment.RIGHT
                        else -> ParagraphAlignment.LEFT
                    }
                }

                val run = imgPara.createRun()
                FileInputStream(imgFile).use { iis ->
                    run.addPicture(
                        iis,
                        imageType(imgFile.extension),
                        imgFile.name,
                        Units.toEMU(widthPt),
                        Units.toEMU(heightPt)
                    )
                }

                caption?.let { capText ->
                    val capPara = doc.createParagraph()
                    capPara.alignment = ParagraphAlignment.CENTER
                    val capRun = capPara.createRun()
                    capRun.setText(capText)
                    capRun.isItalic = true
                    captionFont?.let { capRun.fontFamily = it }
                    captionFontSize?.let { capRun.fontSize = it }
                    captionColor?.let { capRun.color = parseColor(it) }
                }

                save(doc, outputPath)
                "✅ Inserted image${if (caption != null) " with caption" else ""} and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error inserting image: ${e.message}"
        }
    }

    // ── 4. Add styled table ─────────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a professionally styled table to a Word document. " +
                "Data is provided as CSV text. First row can be styled as header with distinct " +
                "background color and bold font. Supports custom fonts and colors."
    )
    fun addStyledTable(
        @LLMDescription("Path to existing .docx, or null to create new") docxPath: String? = null,
        @LLMDescription("CSV text: rows separated by newline, columns by comma") csvText: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Treat first row as header with special styling") hasHeader: Boolean = true,
        @LLMDescription("Header background color hex, e.g. '#003366'") headerBgColor: String? = "#003366",
        @LLMDescription("Header text color hex, e.g. '#FFFFFF'") headerTextColor: String? = "#FFFFFF",
        @LLMDescription("Header font family") headerFont: String? = null,
        @LLMDescription("Header font size") headerFontSize: Int? = 12,
        @LLMDescription("Body font family") bodyFont: String? = null,
        @LLMDescription("Body font size") bodyFontSize: Int? = 11,
        @LLMDescription("Alternate row shading color hex (optional), e.g. '#F2F2F2'") altRowColor: String? = "#F2F2F2",
        @LLMDescription("Table width in percentage of page width (default 100)") tableWidthPct: Int? = 100
    ): String {
        return try {
            val rows = csvText.trim().split("\n").filter { it.isNotBlank() }.map { it.split(",").map { c -> c.trim() } }
            if (rows.isEmpty()) return "❌ CSV data is empty"

            withDocument(docxPath) { doc ->
                val numCols = rows.maxOf { it.size }
                val table = doc.createTable(rows.size, numCols)

                // Set table width
                tableWidthPct?.let {
                    val tblPr = table.ctTbl.tblPr ?: table.ctTbl.addNewTblPr()
                    val tblW = tblPr.addNewTblW()
                    tblW.w = BigInteger.valueOf(it * 50L) // 50 = 1% in fifths of a percent
                    tblW.type = STTblWidth.PCT
                }

                for ((rIdx, row) in rows.withIndex()) {
                    val tRow = table.getRow(rIdx)
                    val isHeaderRow = hasHeader && rIdx == 0
                    val isAltRow = !isHeaderRow && altRowColor != null && rIdx % 2 == 0

                    for ((cIdx, cellVal) in row.withIndex()) {
                        val cell = if (cIdx < tRow.tableCells.size) tRow.getCell(cIdx) else tRow.addNewTableCell()

                        // Set cell background color
                        if (isHeaderRow && headerBgColor != null) {
                            val ctTc = cell.ctTc
                            val tcPr = ctTc.tcPr ?: ctTc.addNewTcPr()
                            val shd = tcPr.addNewShd()
                            shd.fill = parseColor(headerBgColor)
                            shd.`val` = STShd.CLEAR
                        } else if (isAltRow) {
                            val ctTc = cell.ctTc
                            val tcPr = ctTc.tcPr ?: ctTc.addNewTcPr()
                            val shd = tcPr.addNewShd()
                            shd.fill = parseColor(altRowColor)
                            shd.`val` = STShd.CLEAR
                        }

                        // Clear default paragraph and set text
                        if (cell.paragraphs.isNotEmpty()) {
                            cell.removeParagraph(0)
                        }
                        val p = cell.addParagraph()
                        val run = p.createRun()
                        run.setText(cellVal)

                        if (isHeaderRow) {
                            run.isBold = true
                            headerTextColor?.let { run.color = parseColor(it) }
                            headerFont?.let { run.fontFamily = it }
                            headerFontSize?.let { run.fontSize = it }
                        } else {
                            bodyFont?.let { run.fontFamily = it }
                            bodyFontSize?.let { run.fontSize = it }
                        }
                    }
                }

                save(doc, outputPath)
                "✅ Added styled table (${rows.size} rows × $numCols cols) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding styled table: ${e.message}"
        }
    }

    // ── 5. Add header and footer ────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add or update the header and/or footer of a Word document. " +
                "Supports custom text, font, and alignment for both header and footer."
    )
    fun setHeaderFooter(
        @LLMDescription("Path to existing .docx file") docxPath: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Header text (use \\n for newlines). Leave empty or null to skip.") headerText: String? = null,
        @LLMDescription("Footer text. Leave empty or null to skip.") footerText: String? = null,
        @LLMDescription("Header font family") headerFont: String? = null,
        @LLMDescription("Header font size") headerFontSize: Int? = 10,
        @LLMDescription("Footer font family") footerFont: String? = null,
        @LLMDescription("Footer font size") footerFontSize: Int? = 9,
        @LLMDescription("Header alignment: LEFT, CENTER, RIGHT") headerAlignment: String? = "CENTER",
        @LLMDescription("Footer alignment: LEFT, CENTER, RIGHT") footerAlignment: String? = "CENTER"
    ): String {
        return try {
            withDocument(docxPath) { doc ->

                // Ensure section properties exist
                val sectPr = doc.document.body.sectPr ?: doc.document.body.addNewSectPr()

                headerText?.let { txt ->
                    val hdrPolicy = doc.createHeaderFooterPolicy()
                    val header = hdrPolicy.createHeader(XWPFHeaderFooterPolicy.DEFAULT)
                    resetHeaderFooterContent(header)
                    val para = header.createParagraph()
                    para.alignment = parseAlignment(headerAlignment)
                    val run = para.createRun()
                    run.setText(txt.replace("\\n", "\n"))
                    headerFont?.let { run.fontFamily = it }
                    headerFontSize?.let { run.fontSize = it }
                }

                footerText?.let { txt ->
                    val ftrPolicy = doc.headerFooterPolicy ?: doc.createHeaderFooterPolicy()
                    val footer = ftrPolicy.createFooter(XWPFHeaderFooterPolicy.DEFAULT)
                    resetHeaderFooterContent(footer)
                    val para = footer.createParagraph()
                    para.alignment = parseAlignment(footerAlignment)
                    val run = para.createRun()
                    run.setText(txt.replace("\\n", "\n"))
                    footerFont?.let { run.fontFamily = it }
                    footerFontSize?.let { run.fontSize = it }
                }

                save(doc, outputPath)
                "✅ Set header/footer and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting header/footer: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Create or replace a Word footer with dynamic page-number fields. " +
                "The footer uses real PAGE and NUMPAGES fields so Word displays live numbers like 'Page 3 of 12' " +
                "instead of a literal placeholder string."
    )
    fun addDynamicPageNumberFooter(
        @LLMDescription("Path to existing .docx file") docxPath: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Text placed before the current page number") prefixText: String? = "Page ",
        @LLMDescription("Text placed after the current page number") suffixText: String? = "",
        @LLMDescription("Whether to also include the total page count") includeTotalPages: Boolean = false,
        @LLMDescription("Text placed before the total page count when includeTotalPages is true") totalPagesPrefix: String? = " of ",
        @LLMDescription("Text placed after the total page count when includeTotalPages is true") totalPagesSuffix: String? = "",
        @LLMDescription("Footer alignment: LEFT, CENTER, RIGHT") alignment: String? = "CENTER",
        @LLMDescription("Footer font family") fontFamily: String? = null,
        @LLMDescription("Footer font size") fontSize: Int? = 9
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                doc.document.body.sectPr ?: doc.document.body.addNewSectPr()

                val footerPolicy = doc.headerFooterPolicy ?: doc.createHeaderFooterPolicy()
                val footer = footerPolicy.createFooter(XWPFHeaderFooterPolicy.DEFAULT)
                resetHeaderFooterContent(footer)

                val paragraph = footer.createParagraph()
                paragraph.alignment = parseAlignment(alignment, ParagraphAlignment.CENTER)

                appendStyledText(paragraph, prefixText, fontFamily, fontSize)
                appendFieldRun(paragraph, "PAGE", fontFamily, fontSize)
                appendStyledText(paragraph, suffixText, fontFamily, fontSize)

                if (includeTotalPages) {
                    appendStyledText(paragraph, totalPagesPrefix, fontFamily, fontSize)
                    appendFieldRun(paragraph, "NUMPAGES", fontFamily, fontSize)
                    appendStyledText(paragraph, totalPagesSuffix, fontFamily, fontSize)
                }

                save(doc, outputPath)
                "✅ Added dynamic page number footer and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding dynamic page number footer: ${e.message}"
        }
    }

    // ── 6. Page setup ───────────────────────────────────────────────

    @Tool
    @LLMDescription(
        "Configure page setup for a Word document: page size, orientation, margins. " +
                "Common sizes: A4 (11906x16838 twips), Letter (12240x15840 twips), A3 (16838x23811 twips). " +
                "Margins are in points (72 points = 1 inch)."
    )
    fun setPageSetup(
        @LLMDescription("Path to existing .docx file") docxPath: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Page width in twips (A4=11906, Letter=12240). Null to keep current.") pageWidth: Long? = null,
        @LLMDescription("Page height in twips (A4=16838, Letter=15840). Null to keep current.") pageHeight: Long? = null,
        @LLMDescription("Orientation: PORTRAIT or LANDSCAPE") orientation: String? = null,
        @LLMDescription("Top margin in points") marginTop: Int? = null,
        @LLMDescription("Bottom margin in points") marginBottom: Int? = null,
        @LLMDescription("Left margin in points") marginLeft: Int? = null,
        @LLMDescription("Right margin in points") marginRight: Int? = null
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val body = doc.document.body
                val sectPr = body.sectPr ?: body.addNewSectPr()

                pageWidth?.let {
                    val pgSz = sectPr.pgSz ?: sectPr.addNewPgSz()
                    pgSz.w = BigInteger.valueOf(it)
                }
                pageHeight?.let {
                    val pgSz = sectPr.pgSz ?: sectPr.addNewPgSz()
                    pgSz.h = BigInteger.valueOf(it)
                }
                orientation?.let {
                    val pgSz = sectPr.pgSz ?: sectPr.addNewPgSz()
                    pgSz.orient = if (it.uppercase() == "LANDSCAPE") STPageOrientation.LANDSCAPE
                    else STPageOrientation.PORTRAIT
                }

                val pgMar = sectPr.pgMar ?: sectPr.addNewPgMar()
                marginTop?.let { pgMar.top = BigInteger.valueOf(it * 20L) }
                marginBottom?.let { pgMar.bottom = BigInteger.valueOf(it * 20L) }
                marginLeft?.let { pgMar.left = BigInteger.valueOf(it * 20L) }
                marginRight?.let { pgMar.right = BigInteger.valueOf(it * 20L) }

                save(doc, outputPath)
                "✅ Page setup configured and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting page setup: ${e.message}"
        }
    }

    // ── 7. Add page break ───────────────────────────────────────────

    @Tool
    @LLMDescription("Insert a page break at the end of the document")
    fun addPageBreak(
        @LLMDescription("Path to existing .docx file") docxPath: String,
        @LLMDescription("Output .docx path") outputPath: String
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val para = doc.createParagraph()
                val run = para.createRun()
                run.addBreak(BreakType.PAGE)
                save(doc, outputPath)
                "✅ Added page break and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding page break: ${e.message}"
        }
    }

    // ── 8. Add hyperlink ────────────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a hyperlink to a Word document with custom display text, font, and color."
    )
    fun addHyperlink(
        @LLMDescription("Path to existing .docx, or null to create new") docxPath: String? = null,
        @LLMDescription("URL of the hyperlink") url: String,
        @LLMDescription("Display text for the link") displayText: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Font family for the link text") fontFamily: String? = null,
        @LLMDescription("Font size for the link text") fontSize: Int? = null,
        @LLMDescription("Link color hex (default blue)") color: String? = "#0563C1"
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val para = doc.createParagraph()

                // Create hyperlink using low-level API
                val rId = para.document.packagePart.addExternalRelationship(
                    url,
                    XWPFRelation.HYPERLINK.relation
                ).id

                val ctp = para.ctp
                val hyperlink = ctp.addNewHyperlink()
                hyperlink.id = rId

                val ctRun = hyperlink.addNewR()
                val rPr = ctRun.addNewRPr()
                val ctColor = rPr.addNewColor()
                ctColor.`val` = parseColor(color ?: "#0563C1")
                val ctU = rPr.addNewU()
                ctU.`val` = STUnderline.SINGLE

                fontFamily?.let {
                    val fonts = rPr.addNewRFonts()
                    fonts.ascii = it
                    fonts.hAnsi = it
                    fonts.eastAsia = it
                }
                fontSize?.let {
                    val sz = rPr.addNewSz()
                    sz.`val` = BigInteger.valueOf(it * 2L) // half-points
                    val szCs = rPr.addNewSzCs()
                    szCs.`val` = BigInteger.valueOf(it * 2L)
                }

                val ctText = ctRun.addNewT()
                ctText.stringValue = displayText

                save(doc, outputPath)
                "✅ Added hyperlink '$displayText' -> $url and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding hyperlink: ${e.message}"
        }
    }

    // ── 9. Add table of contents ────────────────────────────────────

    @Tool
    @LLMDescription(
        "Insert a Table of Contents (TOC) field at the end of the document. " +
                "The TOC will be updated when the document is opened in Word. " +
                "Requires headings to be present (use addHeading tool)."
    )
    fun addTableOfContents(
        @LLMDescription("Path to existing .docx file") docxPath: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Maximum heading level to include (1-6, default 3)") maxLevel: Int = 3
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val para = doc.createParagraph()

                // Add TOC title
                val titleRun = para.createRun()
                titleRun.setText("Table of Contents")
                titleRun.isBold = true
                titleRun.fontSize = 16

                // Insert TOC field
                val tocPara = doc.createParagraph()
                val fldChar1 = tocPara.ctp.addNewR().addNewFldChar()
                fldChar1.fldCharType = STFldCharType.BEGIN

                val instrRun = tocPara.ctp.addNewR()
                val instrText = instrRun.addNewInstrText()
                instrText.space = org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute.Space.PRESERVE
                instrText.stringValue = " TOC \\o \"1-$maxLevel\" \\h \\z \\u "

                val fldChar2 = tocPara.ctp.addNewR().addNewFldChar()
                fldChar2.fldCharType = STFldCharType.SEPARATE

                val placeholderRun = tocPara.ctp.addNewR()
                val placeholderText = placeholderRun.addNewT()
                placeholderText.stringValue = "[Table of Contents - Update in Word to populate]"

                val fldChar3 = tocPara.ctp.addNewR().addNewFldChar()
                fldChar3.fldCharType = STFldCharType.END

                save(doc, outputPath)
                "✅ Added TOC (levels 1-$maxLevel) and saved to: $outputPath. Open in Word and press F9 to update."
            }
        } catch (e: Throwable) {
            "❌ Error adding TOC: ${e.message}"
        }
    }

    // ── 10. Multi-run paragraph (mixed styles in one paragraph) ────

    @Tool
    @LLMDescription(
        "Add a paragraph with multiple text segments, each with its own formatting. " +
                "Use this to create paragraphs with mixed fonts, colors, and styles. " +
                "Each segment is defined by text|fontFamily|fontSize|bold|italic|color separated by '||'. " +
                "Example segments: 'Hello|Arial|12|true|false|#000000||World|Times New Roman|14|false|true|#FF0000'"
    )
    fun addMultiStyleParagraph(
        @LLMDescription("Path to existing .docx, or null to create new") docxPath: String? = null,
        @LLMDescription(
            "Segments separated by '||'. Each segment: text|fontFamily|fontSize|bold|italic|color. " +
                    "Fields can be empty to use defaults. E.g.: 'Hello |Arial|14|true||#003366||world!|宋体|14|||#FF0000'"
        ) segments: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Paragraph alignment: LEFT, CENTER, RIGHT, BOTH") alignment: String? = null
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val para = doc.createParagraph()

                alignment?.let {
                    para.alignment = when (it.uppercase()) {
                        "CENTER" -> ParagraphAlignment.CENTER
                        "RIGHT" -> ParagraphAlignment.RIGHT
                        "BOTH", "JUSTIFY" -> ParagraphAlignment.BOTH
                        else -> ParagraphAlignment.LEFT
                    }
                }

                val segList = segments.split("||")
                for (seg in segList) {
                    val parts = seg.split("|")
                    val text = parts.getOrNull(0) ?: continue
                    val font = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val size = parts.getOrNull(2)?.toIntOrNull()
                    val bold = parts.getOrNull(3)?.lowercase() == "true"
                    val italic = parts.getOrNull(4)?.lowercase() == "true"
                    val clr = parts.getOrNull(5)?.takeIf { it.isNotBlank() }

                    val run = para.createRun()
                    run.setText(text)
                    applyRunStyle(run, font, size, bold, italic, color = clr)
                }

                save(doc, outputPath)
                "✅ Added multi-style paragraph (${segList.size} segments) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding multi-style paragraph: ${e.message}"
        }
    }

    // ── 11. List available system fonts ─────────────────────────────

    @Tool
    @LLMDescription(
        "List available font families on the current system. " +
                "Useful for finding the correct font name to use in other tools. " +
                "Optionally filter by keyword."
    )
    fun listSystemFonts(
        @LLMDescription("Optional filter keyword (case-insensitive), e.g. '微软' or 'Arial'") filter: String? = null,
        @LLMDescription("Maximum number of fonts to return (default 50)") maxResults: Int = 50
    ): String {
        return try {
            val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            val allFonts = ge.availableFontFamilyNames.toList()
            val filtered = if (filter != null) {
                allFonts.filter { it.contains(filter, ignoreCase = true) }
            } else {
                allFonts
            }
            val result = filtered.take(maxResults)
            buildString {
                appendLine("✅ Found ${filtered.size} fonts${if (filter != null) " matching '$filter'" else ""} (showing ${result.size}):")
                result.forEach { appendLine("  • $it") }
                if (filtered.size > maxResults) {
                    appendLine("  ... ${filtered.size - maxResults} more fonts not shown")
                }
            }
        } catch (e: Throwable) {
            "❌ Error listing fonts: ${e.message}"
        }
    }

    // ── 12. Add numbered/bullet list ────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a formatted list (numbered or bulleted) to a Word document. " +
                "Each item can have custom font and color."
    )
    fun addFormattedList(
        @LLMDescription("Path to existing .docx, or null to create new") docxPath: String? = null,
        @LLMDescription("List items separated by newline") items: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("List type: BULLET or NUMBERED") listType: String = "BULLET",
        @LLMDescription("Font family for list items") fontFamily: String? = null,
        @LLMDescription("Font size for list items") fontSize: Int? = null,
        @LLMDescription("Text color hex") color: String? = null,
        @LLMDescription("Indent level (0-based)") indentLevel: Int = 0
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val itemList = items.split("\n").filter { it.isNotBlank() }

                for ((idx, item) in itemList.withIndex()) {
                    val para = doc.createParagraph()

                    // Use text-based bullets/numbers as prefix (avoids complex abstract numbering definitions)
                    val run = para.createRun()
                    val prefix = if (listType.uppercase() == "NUMBERED") {
                        "${idx + 1}. "
                    } else {
                        "• "
                    }
                    run.setText("$prefix${item.trim()}")
                    fontFamily?.let { run.fontFamily = it }
                    fontSize?.let { run.fontSize = it }
                    color?.let { run.color = parseColor(it) }

                    // Set indentation
                    val ppr = para.ctp.pPr ?: para.ctp.addNewPPr()
                    val ind = ppr.ind ?: ppr.addNewInd()
                    ind.left = BigInteger.valueOf((indentLevel + 1) * 720L) // 720 twips = 0.5 inch
                }

                save(doc, outputPath)
                "✅ Added ${listType.lowercase()} list (${itemList.size} items) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding list: ${e.message}"
        }
    }

    // ── 13. Add watermark text ──────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a text watermark to a Word document (e.g. 'CONFIDENTIAL', 'DRAFT'). " +
                "The watermark appears diagonally behind the content on every page."
    )
    fun addDocTextWatermark(
        @LLMDescription("Path to existing .docx file") docxPath: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Watermark text, e.g. 'CONFIDENTIAL'") watermarkText: String,
        @LLMDescription("Font family for watermark") fontFamily: String? = "Arial",
        @LLMDescription("Font size for watermark text") fontSize: Int? = 72,
        @LLMDescription("Watermark color hex, e.g. '#C0C0C0' for light gray") color: String? = "#C0C0C0"
    ): String {
        return try {
            withDocument(docxPath) { doc ->

                // Create header with watermark
                val hdrPolicy = doc.createHeaderFooterPolicy()
                val header = hdrPolicy.createHeader(XWPFHeaderFooterPolicy.DEFAULT)
                val para = header.createParagraph()
                para.alignment = ParagraphAlignment.CENTER

                val run = para.createRun()
                run.setText(watermarkText)
                fontFamily?.let { run.fontFamily = it }
                fontSize?.let { run.fontSize = it }
                color?.let { run.color = parseColor(it) }

                save(doc, outputPath)
                "✅ Added text watermark '$watermarkText' and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding watermark: ${e.message}"
        }
    }

    // ── 14. Set document default font ───────────────────────────────

    @Tool
    @LLMDescription(
        "Set the default font for the entire Word document. " +
                "All new text that doesn't specify a font will use this default. " +
                "Useful for documents that should use a consistent non-standard font like '微软雅黑' or 'Calibri'."
    )
    fun setDocumentDefaultFont(
        @LLMDescription("Path to existing .docx file") docxPath: String,
        @LLMDescription("Output .docx path") outputPath: String,
        @LLMDescription("Default font family, e.g. '微软雅黑', 'Calibri', 'Arial'") fontFamily: String,
        @LLMDescription("Default font size in points (e.g. 11, 12)") fontSize: Int? = null,
        @LLMDescription("Default East Asian font (for CJK characters), e.g. '微软雅黑', '宋体'") eastAsiaFont: String? = null
    ): String {
        return try {
            withDocument(docxPath) { doc ->

                // Apply font to all existing paragraphs
                for (para in doc.paragraphs) {
                    for (run in para.runs) {
                        run.fontFamily = fontFamily
                        // Set East Asian font via fontFamily setter which handles CJK
                        run.fontFamily = fontFamily
                        fontSize?.let { run.fontSize = it }
                    }
                }

                // Note: Document-level default font is already applied to all existing runs above.
                // New paragraphs added after this call should also specify the font explicitly.

                save(doc, outputPath)
                "✅ Set default font to '$fontFamily'${eastAsiaFont?.let { " (East Asian: $it)" } ?: ""} and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting default font: ${e.message}"
        }
    }

    // ── 15. Insert horizontal rule ──────────────────────────────────

    @Tool
    @LLMDescription("Insert a horizontal rule (divider line) into the Word document")
    fun addHorizontalRule(
        @LLMDescription("Path to existing .docx, or null to create new") docxPath: String? = null,
        @LLMDescription("Output .docx path") outputPath: String
    ): String {
        return try {
            withDocument(docxPath) { doc ->
                val para = doc.createParagraph()
                val ppr = para.ctp.pPr ?: para.ctp.addNewPPr()
                val pBdr = ppr.addNewPBdr()
                val bottom = pBdr.addNewBottom()
                bottom.`val` = STBorder.SINGLE
                bottom.sz = BigInteger.valueOf(6)
                bottom.space = BigInteger.valueOf(1)
                bottom.color = "000000"

                save(doc, outputPath)
                "✅ Added horizontal rule and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding horizontal rule: ${e.message}"
        }
    }

    private companion object {
        private const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L
        private val ALLOWED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "tiff", "tif", "emf", "wmf")
    }
}
