package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.sl.usermodel.ShapeType
import org.apache.poi.sl.usermodel.TextParagraph
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.awt.Color
import java.awt.Rectangle
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@LLMDescription(
    "Enhanced PowerPoint (.pptx) tools for creating rich, visually appealing presentations. " +
            "Supports custom fonts, colors, styled text, images, shapes, backgrounds, " +
            "speaker notes, slide layouts, and more."
)
@Deprecated(
    message = "Use the `powerpoint` tool group via parseToolSet(listOf(\"powerpoint\")) — it already registers every PowerPoint tier (basic + advanced + enhanced). This class will be removed once external Kotlin callers migrate.",
    level = DeprecationLevel.WARNING
)
class PowerPointEnhancedTools : ToolSet {

    init { PoiSecurity.ensureHardened() }

    private fun openOrCreate(path: String?): XMLSlideShow {
        val safePath = path?.let { ToolPathSecurity.validateInputPath(it) }
        if (safePath != null && safePath.exists()) {
            return FileInputStream(safePath).use { XMLSlideShow(it) }
        }
        return XMLSlideShow()
    }

    private fun save(ppt: XMLSlideShow, outputPath: String) {
        val validated = ToolPathSecurity.validateOutputPath(outputPath)
        FileOutputStream(validated).use { ppt.write(it) }
    }

    /** Open (or create) a presentation, run [block], and ensure the presentation is closed regardless of outcome. */
    private inline fun <R> withPresentation(path: String?, block: (XMLSlideShow) -> R): R {
        val ppt = openOrCreate(path)
        try {
            return block(ppt)
        } finally {
            ppt.close()
        }
    }

    /**
     * Parse a hex color string. Accepts:
     *   - 6 hex digits with or without `#` prefix (`#FF00AA` / `FF00AA`)
     *   - 3 hex digits that get expanded to 6 (`F0A` → `FF00AA`)
     *
     * Rejects everything else with a clear message; previously a short input like `#FF`
     * caused `StringIndexOutOfBoundsException` deep inside POI-unrelated code, making the
     * resulting error very hard to attribute.
     */
    private fun parseColor(hex: String): Color {
        val cleanRaw = hex.removePrefix("#").trim()
        val clean = when (cleanRaw.length) {
            6 -> cleanRaw
            3 -> cleanRaw.map { "$it$it" }.joinToString("")
            else -> throw IllegalArgumentException(
                "Invalid hex color '$hex' — expected 3 or 6 hex digits (optionally with leading #)"
            )
        }
        require(clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid hex color '$hex' — must contain only hex digits [0-9a-fA-F]"
        }
        return Color(
            clean.substring(0, 2).toInt(16),
            clean.substring(2, 4).toInt(16),
            clean.substring(4, 6).toInt(16)
        )
    }

    private fun pictureType(ext: String): PictureData.PictureType = when (ext.lowercase()) {
        "png" -> PictureData.PictureType.PNG
        "jpg", "jpeg" -> PictureData.PictureType.JPEG
        "gif" -> PictureData.PictureType.GIF
        "bmp" -> PictureData.PictureType.BMP
        "tiff", "tif" -> PictureData.PictureType.TIFF
        "emf" -> PictureData.PictureType.EMF
        "wmf" -> PictureData.PictureType.WMF
        else -> PictureData.PictureType.PNG
    }

    private fun validateImageInputPath(imagePath: String): File {
        val image = ToolPathSecurity.validateInputPath(imagePath)
        require(image.exists() && image.isFile) { "Image not found: $imagePath" }
        require(image.length() in 1..MAX_IMAGE_BYTES) {
            "Image file size ${image.length()} bytes exceeds maximum allowed size $MAX_IMAGE_BYTES bytes"
        }
        require(image.extension.lowercase() in ALLOWED_IMAGE_EXTENSIONS) {
            "Unsupported image extension '${image.extension}'. Supported: ${ALLOWED_IMAGE_EXTENSIONS.joinToString(", ")}"
        }
        return image
    }

    private fun existingInputPathOrNull(path: String): String? =
        ToolPathSecurity.validateInputPath(path).takeIf { it.exists() }?.path

    private fun getSlide(ppt: XMLSlideShow, index: Int): XSLFSlide? {
        if (index < 0 || index >= ppt.slides.size) return null
        return ppt.slides[index]
    }

    // ── 1. Create presentation with custom size ─────────────────────

    @Tool
    @LLMDescription(
        "Create a new PowerPoint presentation with a custom slide size. " +
                "Common sizes: Standard 4:3 (720x540), Widescreen 16:9 (720x405), " +
                "A4 (720x540). Units are in points."
    )
    fun createEnhancedPresentation(
        @LLMDescription("Output .pptx path") outputPath: String,
        @LLMDescription("Slide width in points (default 720 = 10 inches)") widthPt: Int = 720,
        @LLMDescription("Slide height in points (default 405 for 16:9 widescreen)") heightPt: Int = 405
    ): String {
        return try {
            withPresentation(null) { ppt ->
                ppt.pageSize = java.awt.Dimension(widthPt, heightPt)
                save(ppt, outputPath)
                "✅ Created presentation (${widthPt}x${heightPt}pt) at: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error creating presentation: ${e.message}"
        }
    }

    // ── 2. Add slide with rich styled text ──────────────────────────

    @Tool
    @LLMDescription(
        "Add a new slide with a title and body text, both with custom fonts, colors, and sizes. " +
                "Perfect for creating professional-looking slides with custom typography."
    )
    fun addRichSlide(
        @LLMDescription("Path to .pptx file, or null to create new") pptxPath: String? = null,
        @LLMDescription("Output .pptx path") outputPath: String,
        @LLMDescription("Title text") title: String,
        @LLMDescription("Body text (use \\n for newlines)") body: String? = null,
        @LLMDescription("Title font family, e.g. 'Arial Black', '微软雅黑'") titleFont: String? = null,
        @LLMDescription("Title font size in points") titleFontSize: Double? = 36.0,
        @LLMDescription("Title color hex") titleColor: String? = null,
        @LLMDescription("Title bold") titleBold: Boolean = true,
        @LLMDescription("Body font family") bodyFont: String? = null,
        @LLMDescription("Body font size in points") bodyFontSize: Double? = 18.0,
        @LLMDescription("Body color hex") bodyColor: String? = null,
        @LLMDescription("Title alignment: LEFT, CENTER, RIGHT") titleAlignment: String? = "CENTER",
        @LLMDescription("Body alignment: LEFT, CENTER, RIGHT") bodyAlignment: String? = "LEFT"
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = ppt.createSlide()

                // Title text box
                val titleBox = slide.createTextBox()
                titleBox.anchor = Rectangle(40, 20, ppt.pageSize.width - 80, 80)
                titleBox.clearText()
                val titlePara = titleBox.addNewTextParagraph()
                titleAlignment?.let {
                    titlePara.textAlign = when (it.uppercase()) {
                        "CENTER" -> TextParagraph.TextAlign.CENTER
                        "RIGHT" -> TextParagraph.TextAlign.RIGHT
                        else -> TextParagraph.TextAlign.LEFT
                    }
                }
                val titleRun = titlePara.addNewTextRun()
                titleRun.setText(title)
                titleRun.isBold = titleBold
                titleFontSize?.let { titleRun.fontSize = it }
                titleFont?.let { titleRun.setFontFamily(it) }
                titleColor?.let { titleRun.setFontColor(parseColor(it)) }

                // Body text box
                body?.let { bodyText ->
                    val bodyBox = slide.createTextBox()
                    bodyBox.anchor = Rectangle(40, 110, ppt.pageSize.width - 80, ppt.pageSize.height - 140)
                    bodyBox.clearText()

                    val lines = bodyText.replace("\\n", "\n").split("\n")
                    lines.forEachIndexed { idx, line ->
                        val para = bodyBox.addNewTextParagraph()
                        bodyAlignment?.let {
                            para.textAlign = when (it.uppercase()) {
                                "CENTER" -> TextParagraph.TextAlign.CENTER
                                "RIGHT" -> TextParagraph.TextAlign.RIGHT
                                else -> TextParagraph.TextAlign.LEFT
                            }
                        }
                        val run = para.addNewTextRun()
                        run.setText(line)
                        bodyFontSize?.let { run.fontSize = it }
                        bodyFont?.let { run.setFontFamily(it) }
                        bodyColor?.let { run.setFontColor(parseColor(it)) }
                    }
                }

                save(ppt, outputPath)
                "✅ Added rich slide and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding rich slide: ${e.message}"
        }
    }

    // ── 3. Add image to slide ───────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add an image to a specific slide with precise positioning. " +
                "All coordinates and dimensions are in points (72 points = 1 inch). " +
                "Supports PNG, JPEG, GIF, BMP, TIFF, EMF, WMF."
    )
    fun addEnhancedImageToSlide(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Path to image file") imagePath: String,
        @LLMDescription("X position in points") x: Int = 100,
        @LLMDescription("Y position in points") y: Int = 100,
        @LLMDescription("Width in points") width: Int = 400,
        @LLMDescription("Height in points") height: Int = 300,
        @LLMDescription("Output .pptx path") outputPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide =
                    getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex (total: ${ppt.slides.size})"
                val imgFile = validateImageInputPath(imagePath)

                val picData = ppt.addPicture(imgFile.readBytes(), pictureType(imgFile.extension))
                val pic = slide.createPicture(picData)
                pic.anchor = Rectangle(x, y, width, height)

                save(ppt, outputPath)
                "✅ Added image to slide $slideIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding image: ${e.message}"
        }
    }

    // ── 4. Set slide background ─────────────────────────────────────

    @Tool
    @LLMDescription(
        "Set the background color of a slide. Can set a solid color or a gradient."
    )
    fun setSlideBackground(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Background color hex, e.g. '#003366'") bgColor: String,
        @LLMDescription("Output .pptx path") outputPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex"

                // Use low-level XML to set solid fill
                val xmlSlide = slide.xmlObject
                val cSld = xmlSlide.cSld
                val bgXml = cSld.bg ?: cSld.addNewBg()
                val bgPr = bgXml.bgPr ?: bgXml.addNewBgPr()
                val solidFill = bgPr.addNewSolidFill()
                val srgbClr = solidFill.addNewSrgbClr()
                srgbClr.`val` = parseColor(bgColor).let {
                    byteArrayOf(it.red.toByte(), it.green.toByte(), it.blue.toByte())
                }

                save(ppt, outputPath)
                "✅ Set slide $slideIndex background to $bgColor and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting background: ${e.message}"
        }
    }

    // ── 5. Add text box with styling ────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a text box to a slide at a specific position with custom font, color, and alignment. " +
                "Position and size are in points."
    )
    fun addStyledTextBox(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Text content") text: String,
        @LLMDescription("X position in points") x: Int,
        @LLMDescription("Y position in points") y: Int,
        @LLMDescription("Width in points") width: Int,
        @LLMDescription("Height in points") height: Int,
        @LLMDescription("Output .pptx path") outputPath: String,
        @LLMDescription("Font family") fontFamily: String? = null,
        @LLMDescription("Font size in points") fontSize: Double? = null,
        @LLMDescription("Bold") bold: Boolean = false,
        @LLMDescription("Italic") italic: Boolean = false,
        @LLMDescription("Text color hex") color: String? = null,
        @LLMDescription("Background color hex for the text box (null for transparent)") bgColor: String? = null,
        @LLMDescription("Text alignment: LEFT, CENTER, RIGHT") alignment: String? = null
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex"

                val textBox = slide.createTextBox()
                textBox.anchor = Rectangle(x, y, width, height)
                textBox.clearText()

                bgColor?.let {
                    textBox.fillColor = parseColor(it)
                }

                val lines = text.replace("\\n", "\n").split("\n")
                lines.forEachIndexed { idx, line ->
                    val para = textBox.addNewTextParagraph()
                    alignment?.let {
                        para.textAlign = when (it.uppercase()) {
                            "CENTER" -> TextParagraph.TextAlign.CENTER
                            "RIGHT" -> TextParagraph.TextAlign.RIGHT
                            else -> TextParagraph.TextAlign.LEFT
                        }
                    }
                    val run = para.addNewTextRun()
                    run.setText(line)
                    fontFamily?.let { run.setFontFamily(it) }
                    fontSize?.let { run.fontSize = it }
                    if (bold) run.isBold = true
                    if (italic) run.isItalic = true
                    color?.let { run.setFontColor(parseColor(it)) }
                }

                save(ppt, outputPath)
                "✅ Added styled text box to slide $slideIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding text box: ${e.message}"
        }
    }

    // ── 6. Add styled table to slide ────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a professionally styled table to a slide from CSV data. " +
                "Supports header row styling with custom colors."
    )
    fun addStyledTableToSlide(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("CSV text: rows by newline, columns by comma") csvText: String,
        @LLMDescription("X position in points") x: Int = 40,
        @LLMDescription("Y position in points") y: Int = 100,
        @LLMDescription("Table width in points") width: Int = 640,
        @LLMDescription("Table height in points") height: Int = 300,
        @LLMDescription("Output .pptx path") outputPath: String,
        @LLMDescription("Header background color hex") headerBgColor: String? = "#003366",
        @LLMDescription("Header text color hex") headerTextColor: String? = "#FFFFFF",
        @LLMDescription("Header font family") headerFont: String? = null,
        @LLMDescription("Header font size") headerFontSize: Double? = 14.0,
        @LLMDescription("Body font family") bodyFont: String? = null,
        @LLMDescription("Body font size") bodyFontSize: Double? = 12.0
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex"

                val rows = csvText.trim().split("\n").filter { it.isNotBlank() }.map { it.split(",").map { c -> c.trim() } }
                if (rows.isEmpty()) return "❌ CSV data is empty"

                val numRows = rows.size
                val numCols = rows.maxOf { it.size }
                val table = slide.createTable(numRows, numCols)
                table.anchor = Rectangle(x, y, width, height)

                for ((rIdx, rowData) in rows.withIndex()) {
                    for ((cIdx, cellVal) in rowData.withIndex()) {
                        if (cIdx >= numCols) continue
                        val cell = table.getCell(rIdx, cIdx)
                        cell.setText(cellVal)

                        if (rIdx == 0) {
                            // Header styling
                            headerBgColor?.let {
                                cell.fillColor = parseColor(it)
                            }
                            val para = cell.textParagraphs.firstOrNull()
                            para?.textRuns?.firstOrNull()?.let { run ->
                                run.isBold = true
                                headerTextColor?.let { run.setFontColor(parseColor(it)) }
                                headerFont?.let { run.setFontFamily(it) }
                                headerFontSize?.let { run.fontSize = it }
                            }
                        } else {
                            val para = cell.textParagraphs.firstOrNull()
                            para?.textRuns?.firstOrNull()?.let { run ->
                                bodyFont?.let { run.setFontFamily(it) }
                                bodyFontSize?.let { run.fontSize = it }
                            }
                        }
                    }
                }

                save(ppt, outputPath)
                "✅ Added styled table ($numRows×$numCols) to slide $slideIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding table: ${e.message}"
        }
    }

    // ── 7. Add speaker notes ────────────────────────────────────────

    @Tool
    @LLMDescription("Add speaker notes to a slide")
    fun addSpeakerNotes(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Speaker notes text") notes: String,
        @LLMDescription("Output .pptx path") outputPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex"

                val notes2 = slide.notes
                if (notes2 != null) {
                    val textBody = notes2.placeholders.firstOrNull()
                    textBody?.clearText()
                    val para = textBody?.addNewTextParagraph()
                    para?.addNewTextRun()?.setText(notes)
                } else {
                    // If no notes slide exists, we cannot create one (POI limitation)
                    // Use XML-level approach
                    val xmlSlide = slide.xmlObject
                    // Add a comment-style note via placeholder
                    return "❌ Cannot add notes to a slide without an existing notes slide. Create the notes slide in PowerPoint first."
                }

                save(ppt, outputPath)
                "✅ Added speaker notes to slide $slideIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding notes: ${e.message}"
        }
    }

    // ── 8. Add shape ────────────────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a shape (rectangle, rounded rectangle, ellipse, or arrow) to a slide. " +
                "Can contain text with custom styling."
    )
    fun addShape(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Shape type: RECT, ROUND_RECT, ELLIPSE, RIGHT_ARROW, LEFT_ARROW, STAR") shapeType: String = "RECT",
        @LLMDescription("X position in points") x: Int,
        @LLMDescription("Y position in points") y: Int,
        @LLMDescription("Width in points") width: Int,
        @LLMDescription("Height in points") height: Int,
        @LLMDescription("Output .pptx path") outputPath: String,
        @LLMDescription("Fill color hex") fillColor: String? = "#4472C4",
        @LLMDescription("Text inside the shape (optional)") text: String? = null,
        @LLMDescription("Text font family") textFont: String? = null,
        @LLMDescription("Text font size") textFontSize: Double? = null,
        @LLMDescription("Text color hex") textColor: String? = "#FFFFFF",
        @LLMDescription("Line/border color hex (null for no border)") lineColor: String? = null,
        @LLMDescription("Line width in points") lineWidth: Double? = null
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex"

                val autoShapeType = when (shapeType.uppercase()) {
                    "ROUND_RECT" -> ShapeType.ROUND_RECT
                    "ELLIPSE" -> ShapeType.ELLIPSE
                    "RIGHT_ARROW" -> ShapeType.RIGHT_ARROW
                    "LEFT_ARROW" -> ShapeType.LEFT_ARROW
                    "STAR" -> ShapeType.STAR_5
                    "DIAMOND" -> ShapeType.DIAMOND
                    "TRIANGLE" -> ShapeType.TRIANGLE
                    "HEART" -> ShapeType.HEART
                    else -> ShapeType.RECT
                }

                val shape = slide.createAutoShape()
                shape.shapeType = autoShapeType
                shape.anchor = Rectangle(x, y, width, height)
                fillColor?.let { shape.fillColor = parseColor(it) }
                lineColor?.let { shape.lineColor = parseColor(it) }
                lineWidth?.let { shape.lineWidth = it }

                text?.let { txt ->
                    shape.clearText()
                    val para = shape.addNewTextParagraph()
                    para.textAlign = TextParagraph.TextAlign.CENTER
                    val run = para.addNewTextRun()
                    run.setText(txt)
                    textFont?.let { run.setFontFamily(it) }
                    textFontSize?.let { run.fontSize = it }
                    textColor?.let { run.setFontColor(parseColor(it)) }
                }

                save(ppt, outputPath)
                "✅ Added $shapeType shape to slide $slideIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding shape: ${e.message}"
        }
    }

    // ── 9. Add bullet list slide ────────────────────────────────────

    @Tool
    @LLMDescription(
        "Add a NEW slide with a title and a styled bullet list. " +
                "Pass bullet items as a JSON array of strings. " +
                "If slideIndex is provided by mistake, it is ignored and a new slide is still created. " +
                "Each bullet item has custom font, color, and size."
    )
    fun addBulletListSlide(
        @LLMDescription("Path to .pptx file, or null to create new") pptxPath: String? = null,
        @LLMDescription("Output .pptx path") outputPath: String,
        @LLMDescription("Unused legacy field; a new slide is always created.") slideIndex: Int? = null,
        @LLMDescription("Slide title") title: String,
        @LLMDescription("Bullet items as an array of strings") items: List<String>,
        @LLMDescription("Title font family") titleFont: String? = null,
        @LLMDescription("Title font size") titleFontSize: Double? = 32.0,
        @LLMDescription("Title color hex") titleColor: String? = null,
        @LLMDescription("Bullet font family") bulletFont: String? = null,
        @LLMDescription("Bullet font size") bulletFontSize: Double? = 18.0,
        @LLMDescription("Bullet text color hex") bulletColor: String? = null,
        @LLMDescription("Bullet character (default '•')") bulletChar: String? = "•"
    ): String {
        return try {
            val sourcePath = pptxPath ?: existingInputPathOrNull(outputPath)
            withPresentation(sourcePath) { ppt ->
                val slide = ppt.createSlide()

                // Title
                val titleBox = slide.createTextBox()
                titleBox.anchor = Rectangle(40, 20, ppt.pageSize.width - 80, 60)
                titleBox.clearText()
                val titlePara = titleBox.addNewTextParagraph()
                titlePara.textAlign = TextParagraph.TextAlign.LEFT
                val titleRun = titlePara.addNewTextRun()
                titleRun.setText(title)
                titleRun.isBold = true
                titleFontSize?.let { titleRun.fontSize = it }
                titleFont?.let { titleRun.setFontFamily(it) }
                titleColor?.let { titleRun.setFontColor(parseColor(it)) }

                // Bullet list
                val bulletBox = slide.createTextBox()
                bulletBox.anchor = Rectangle(40, 90, ppt.pageSize.width - 80, ppt.pageSize.height - 120)
                bulletBox.clearText()

                val bulletItems = items.map { it.trim() }.filter { it.isNotBlank() }
                for (item in bulletItems) {
                    val para = bulletBox.addNewTextParagraph()
                    para.textAlign = TextParagraph.TextAlign.LEFT
                    val run = para.addNewTextRun()
                    run.setText("${bulletChar ?: "•"} ${item.trim()}")
                    bulletFontSize?.let { run.fontSize = it }
                    bulletFont?.let { run.setFontFamily(it) }
                    bulletColor?.let { run.setFontColor(parseColor(it)) }
                }

                save(ppt, outputPath)
                "✅ Added bullet list to a new slide (${bulletItems.size} items) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding bullet list slide: ${e.message}"
        }
    }

    // ── 10. Set slide transition ────────────────────────────────────

    @Tool
    @LLMDescription("Remove all shapes/content from a specific slide (clear it for reuse)")
    fun clearSlide(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Output .pptx path") outputPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex"

                // Remove all shapes
                val shapes = slide.shapes.toList()
                shapes.forEach { slide.removeShape(it) }

                save(ppt, outputPath)
                "✅ Cleared slide $slideIndex (removed ${shapes.size} shapes) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error clearing slide: ${e.message}"
        }
    }

    // ── 11. Reorder slides ──────────────────────────────────────────

    @Tool
    @LLMDescription("Move a slide from one position to another")
    fun moveSlide(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Current slide index (0-based)") fromIndex: Int,
        @LLMDescription("Target slide index (0-based)") toIndex: Int,
        @LLMDescription("Output .pptx path") outputPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                if (fromIndex < 0 || fromIndex >= ppt.slides.size)
                    return "❌ Invalid from index: $fromIndex (total: ${ppt.slides.size})"
                if (toIndex < 0 || toIndex >= ppt.slides.size)
                    return "❌ Invalid to index: $toIndex (total: ${ppt.slides.size})"

                ppt.setSlideOrder(ppt.slides[fromIndex], toIndex)
                save(ppt, outputPath)
                "✅ Moved slide from $fromIndex to $toIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error moving slide: ${e.message}"
        }
    }

    // ── 12. Delete slide ────────────────────────────────────────────

    @Tool
    @LLMDescription("Delete a slide from the presentation by index")
    fun deleteSlide(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index to delete (0-based)") slideIndex: Int,
        @LLMDescription("Output .pptx path") outputPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                if (slideIndex < 0 || slideIndex >= ppt.slides.size)
                    return "❌ Invalid slide index: $slideIndex (total: ${ppt.slides.size})"

                ppt.removeSlide(slideIndex)
                save(ppt, outputPath)
                "✅ Deleted slide $slideIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error deleting slide: ${e.message}"
        }
    }

    // ── 13. Add image as full slide background ──────────────────────

    @Tool
    @LLMDescription(
        "Add an image as the full-slide background (cover image). " +
                "The image will be stretched to fill the entire slide."
    )
    fun addBackgroundImage(
        @LLMDescription("Path to .pptx file") pptxPath: String,
        @LLMDescription("Slide index (0-based)") slideIndex: Int,
        @LLMDescription("Path to image file") imagePath: String,
        @LLMDescription("Output .pptx path") outputPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                val slide = getSlide(ppt, slideIndex) ?: return "❌ Invalid slide index: $slideIndex"
                val imgFile = validateImageInputPath(imagePath)

                val picData = ppt.addPicture(imgFile.readBytes(), pictureType(imgFile.extension))
                val pic = slide.createPicture(picData)
                pic.anchor = Rectangle(0, 0, ppt.pageSize.width, ppt.pageSize.height)

                // Move the picture XML element to the front of the shape tree,
                // so it renders behind all other content (first shape = bottom of z-order)
                val spTree = slide.xmlObject.cSld.spTree
                val picList = spTree.picList
                if (picList.size > 1) {
                    // The just-added picture is the last; swap it to position 0
                    val lastIdx = picList.size - 1
                    val bgPic = org.openxmlformats.schemas.presentationml.x2006.main.CTPicture.Factory.parse(
                        picList[lastIdx].xmlText()
                    )
                    spTree.removePic(lastIdx)
                    spTree.insertNewPic(0)
                    spTree.setPicArray(0, bgPic)
                }

                save(ppt, outputPath)
                "✅ Added background image to slide $slideIndex and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding background image: ${e.message}"
        }
    }

    // ── 14. Get presentation info ───────────────────────────────────

    @Tool
    @LLMDescription("Get detailed information about a PowerPoint presentation: slide count, sizes, shapes, etc.")
    fun getPresentationInfo(
        @LLMDescription("Path to .pptx file") pptxPath: String
    ): String {
        return try {
            withPresentation(pptxPath) { ppt ->
                buildString {
                    appendLine("✅ Presentation Info: $pptxPath")
                    appendLine("  Slide Size: ${ppt.pageSize.width}x${ppt.pageSize.height} points")
                    appendLine("  Total Slides: ${ppt.slides.size}")
                    appendLine()
                    for ((idx, slide) in ppt.slides.withIndex()) {
                        appendLine("  Slide $idx:")
                        appendLine("    Shapes: ${slide.shapes.size}")
                        for (shape in slide.shapes) {
                            val type = shape::class.simpleName ?: "Unknown"
                            val text = (shape as? XSLFTextShape)?.text?.take(50) ?: ""
                            appendLine("    - $type at ${shape.anchor} ${if (text.isNotEmpty()) "\"$text\"" else ""}")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            "❌ Error getting presentation info: ${e.message}"
        }
    }

    private companion object {
        private const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L
        private val ALLOWED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "tiff", "tif", "emf", "wmf")
    }
}
