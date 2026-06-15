package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@LLMDescription(
    "Enhanced Excel (.xlsx) tools for creating richly formatted spreadsheets. " +
            "Supports custom fonts, cell colors, borders, merged cells, conditional formatting, " +
            "images, column widths, row heights, freeze panes, and more."
)
@Deprecated(
    message = "Use the `excel` tool group via parseToolSet(listOf(\"excel\")) — it already registers every Excel tier (basic + advanced + enhanced). This class will be removed once external Kotlin callers migrate.",
    level = DeprecationLevel.WARNING
)
class ExcelEnhancedTools : ToolSet {

    init { PoiSecurity.ensureHardened() }

    private fun openOrCreate(path: String?): XSSFWorkbook {
        val safePath = path?.let { ToolPathSecurity.validateInputPath(it) }
        if (safePath != null && safePath.exists()) {
            return FileInputStream(safePath).use { XSSFWorkbook(it) }
        }
        return XSSFWorkbook()
    }

    private fun save(wb: XSSFWorkbook, outputPath: String) {
        val validated = ToolPathSecurity.validateOutputPath(outputPath)
        FileOutputStream(validated).use { wb.write(it) }
    }

    /** Open (or create) a workbook, run [block], and ensure the workbook is closed regardless of outcome. */
    private inline fun <R> withWorkbook(path: String?, block: (XSSFWorkbook) -> R): R {
        val wb = openOrCreate(path)
        try {
            return block(wb)
        } finally {
            wb.close()
        }
    }

    private fun parseXSSFColor(hex: String): XSSFColor {
        val clean = hex.removePrefix("#")
        val r = clean.substring(0, 2).toInt(16).toByte()
        val g = clean.substring(2, 4).toInt(16).toByte()
        val b = clean.substring(4, 6).toInt(16).toByte()
        return XSSFColor(byteArrayOf(r, g, b), null)
    }

    private fun getOrCreateSheet(wb: XSSFWorkbook, name: String?): XSSFSheet {
        val sheetName = name ?: "Sheet1"
        return wb.getSheet(sheetName) ?: wb.createSheet(sheetName)
    }

    private fun getOrCreateRow(sheet: XSSFSheet, rowIdx: Int): XSSFRow {
        return sheet.getRow(rowIdx) ?: sheet.createRow(rowIdx)
    }

    private fun getOrCreateCell(row: XSSFRow, colIdx: Int): XSSFCell {
        return row.getCell(colIdx) ?: row.createCell(colIdx)
    }

    private fun listSheetNames(wb: XSSFWorkbook): List<String> =
        (0 until wb.numberOfSheets).map { wb.getSheetAt(it).sheetName }

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

    // ── 1. Write styled cell ────────────────────────────────────────

    @Tool
    @LLMDescription(
        "Write a value to a cell with rich formatting: font family, size, bold, italic, " +
                "text color, background color, borders, and alignment. Creates the file if it doesn't exist."
    )
    fun writeStyledCell(
        @LLMDescription("Path to .xlsx file, or null to create new") xlsxPath: String? = null,
        @LLMDescription("Sheet name (default 'Sheet1')") sheetName: String? = null,
        @LLMDescription("Row index (0-based)") row: Int,
        @LLMDescription("Column index (0-based)") col: Int,
        @LLMDescription("Cell value (text or number)") value: String,
        @LLMDescription("Output .xlsx path") outputPath: String,
        @LLMDescription("Font family, e.g. 'Arial', '微软雅黑'") fontFamily: String? = null,
        @LLMDescription("Font size in points") fontSize: Int? = null,
        @LLMDescription("Bold text") bold: Boolean = false,
        @LLMDescription("Italic text") italic: Boolean = false,
        @LLMDescription("Text color hex, e.g. '#FF0000'") textColor: String? = null,
        @LLMDescription("Background fill color hex, e.g. '#FFFF00'") bgColor: String? = null,
        @LLMDescription("Horizontal alignment: LEFT, CENTER, RIGHT, GENERAL") hAlign: String? = null,
        @LLMDescription("Vertical alignment: TOP, CENTER, BOTTOM") vAlign: String? = null,
        @LLMDescription("Add thin borders around the cell") borders: Boolean = false,
        @LLMDescription("Enable text wrapping") wrapText: Boolean = false
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                val xRow = getOrCreateRow(sheet, row)
                val cell = getOrCreateCell(xRow, col)

                val num = value.toDoubleOrNull()
                // Escape formula-leading strings to prevent CSV/spreadsheet injection when
                // the workbook is later opened in Excel / Sheets / Calc.
                if (num != null) cell.setCellValue(num) else cell.setCellValue(SpreadsheetSafety.escapeFormula(value))

                val style = wb.createCellStyle()
                val font = wb.createFont() as XSSFFont

                fontFamily?.let { font.fontName = it }
                fontSize?.let { font.fontHeightInPoints = it.toShort() }
                if (bold) font.bold = true
                if (italic) font.italic = true
                textColor?.let { font.setColor(parseXSSFColor(it)) }
                style.setFont(font)

                bgColor?.let {
                    style.setFillForegroundColor(parseXSSFColor(it))
                    style.fillPattern = FillPatternType.SOLID_FOREGROUND
                }

                hAlign?.let {
                    style.alignment = when (it.uppercase()) {
                        "CENTER" -> HorizontalAlignment.CENTER
                        "RIGHT" -> HorizontalAlignment.RIGHT
                        "LEFT" -> HorizontalAlignment.LEFT
                        else -> HorizontalAlignment.GENERAL
                    }
                }
                vAlign?.let {
                    style.verticalAlignment = when (it.uppercase()) {
                        "TOP" -> VerticalAlignment.TOP
                        "BOTTOM" -> VerticalAlignment.BOTTOM
                        else -> VerticalAlignment.CENTER
                    }
                }

                if (borders) {
                    style.borderTop = BorderStyle.THIN
                    style.borderBottom = BorderStyle.THIN
                    style.borderLeft = BorderStyle.THIN
                    style.borderRight = BorderStyle.THIN
                }
                if (wrapText) style.wrapText = true

                cell.cellStyle = style
                save(wb, outputPath)
                "✅ Wrote styled cell ($row, $col) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error writing styled cell: ${e.message}"
        }
    }

    // ── 2. Style a range of cells ───────────────────────────────────

    @Tool
    @LLMDescription(
        "Apply formatting to a range of cells. Useful for styling headers, data regions, etc."
    )
    fun styleRange(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Start row (0-based)") startRow: Int,
        @LLMDescription("Start col (0-based)") startCol: Int,
        @LLMDescription("End row (inclusive, 0-based)") endRow: Int,
        @LLMDescription("End col (inclusive, 0-based)") endCol: Int,
        @LLMDescription("Output .xlsx path") outputPath: String,
        @LLMDescription("Font family") fontFamily: String? = null,
        @LLMDescription("Font size in points") fontSize: Int? = null,
        @LLMDescription("Bold") bold: Boolean = false,
        @LLMDescription("Italic") italic: Boolean = false,
        @LLMDescription("Text color hex") textColor: String? = null,
        @LLMDescription("Background fill color hex") bgColor: String? = null,
        @LLMDescription("Horizontal alignment: LEFT, CENTER, RIGHT") hAlign: String? = null,
        @LLMDescription("Add thin borders") borders: Boolean = false
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)

                val style = wb.createCellStyle()
                val font = wb.createFont() as XSSFFont
                fontFamily?.let { font.fontName = it }
                fontSize?.let { font.fontHeightInPoints = it.toShort() }
                if (bold) font.bold = true
                if (italic) font.italic = true
                textColor?.let { font.setColor(parseXSSFColor(it)) }
                style.setFont(font)
                bgColor?.let {
                    style.setFillForegroundColor(parseXSSFColor(it))
                    style.fillPattern = FillPatternType.SOLID_FOREGROUND
                }
                hAlign?.let {
                    style.alignment = when (it.uppercase()) {
                        "CENTER" -> HorizontalAlignment.CENTER
                        "RIGHT" -> HorizontalAlignment.RIGHT
                        "LEFT" -> HorizontalAlignment.LEFT
                        else -> HorizontalAlignment.GENERAL
                    }
                }
                if (borders) {
                    style.borderTop = BorderStyle.THIN
                    style.borderBottom = BorderStyle.THIN
                    style.borderLeft = BorderStyle.THIN
                    style.borderRight = BorderStyle.THIN
                }

                for (r in startRow..endRow) {
                    val row = getOrCreateRow(sheet, r)
                    for (c in startCol..endCol) {
                        val cell = getOrCreateCell(row, c)
                        cell.cellStyle = style
                    }
                }

                save(wb, outputPath)
                val count = (endRow - startRow + 1) * (endCol - startCol + 1)
                "✅ Styled $count cells ($startRow,$startCol to $endRow,$endCol) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error styling range: ${e.message}"
        }
    }

    // ── 3. Merge cells ──────────────────────────────────────────────

    @Tool
    @LLMDescription("Merge a range of cells in an Excel sheet")
    fun mergeCells(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Start row (0-based)") startRow: Int,
        @LLMDescription("Start col (0-based)") startCol: Int,
        @LLMDescription("End row (inclusive)") endRow: Int,
        @LLMDescription("End col (inclusive)") endCol: Int,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                sheet.addMergedRegion(CellRangeAddress(startRow, endRow, startCol, endCol))
                save(wb, outputPath)
                "✅ Merged cells ($startRow,$startCol to $endRow,$endCol) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error merging cells: ${e.message}"
        }
    }

    // ── 4. Set column width ─────────────────────────────────────────

    @Tool
    @LLMDescription("Set the width of one or more columns in an Excel sheet")
    fun setColumnWidth(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Column index (0-based)") col: Int,
        @LLMDescription("Width in characters (e.g. 15, 20, 30)") widthChars: Int,
        @LLMDescription("Number of consecutive columns to set (default 1)") count: Int = 1,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                for (c in col until col + count) {
                    sheet.setColumnWidth(c, widthChars * 256)
                }
                save(wb, outputPath)
                "✅ Set column width ($count cols from $col) to $widthChars chars and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting column width: ${e.message}"
        }
    }

    // ── 5. Set row height ───────────────────────────────────────────

    @Tool
    @LLMDescription("Set the height of one or more rows in an Excel sheet")
    fun setRowHeight(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Row index (0-based)") row: Int,
        @LLMDescription("Height in points (e.g. 15, 20, 30)") heightPt: Float,
        @LLMDescription("Number of consecutive rows to set (default 1)") count: Int = 1,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                for (r in row until row + count) {
                    val xRow = getOrCreateRow(sheet, r)
                    xRow.heightInPoints = heightPt
                }
                save(wb, outputPath)
                "✅ Set row height ($count rows from $row) to ${heightPt}pt and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting row height: ${e.message}"
        }
    }

    // ── 6. Freeze panes ─────────────────────────────────────────────

    @Tool
    @LLMDescription("Freeze rows and/or columns (like freezing the header row)")
    fun freezePanes(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Number of columns to freeze from left (0 = none)") freezeCols: Int = 0,
        @LLMDescription("Number of rows to freeze from top (e.g. 1 to freeze header)") freezeRows: Int = 1,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                sheet.createFreezePane(freezeCols, freezeRows)
                save(wb, outputPath)
                "✅ Froze $freezeRows rows and $freezeCols cols and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error freezing panes: ${e.message}"
        }
    }

    // ── 7. Insert image into Excel ──────────────────────────────────

    @Tool
    @LLMDescription(
        "Insert an image into an Excel sheet at a specified cell position. " +
                "Supports PNG, JPEG formats."
    )
    fun insertImageToExcel(
        @LLMDescription("Path to .xlsx file, or null to create new") xlsxPath: String? = null,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Path to image file (PNG or JPEG)") imagePath: String,
        @LLMDescription("Top-left anchor row (0-based)") anchorRow: Int = 0,
        @LLMDescription("Top-left anchor column (0-based)") anchorCol: Int = 0,
        @LLMDescription("Image width in cells (columns to span)") widthCells: Int = 5,
        @LLMDescription("Image height in cells (rows to span)") heightCells: Int = 10,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                val imgFile = validateImageInputPath(imagePath)

                val imgType = when (imgFile.extension.lowercase()) {
                    "png" -> Workbook.PICTURE_TYPE_PNG
                    "jpg", "jpeg" -> Workbook.PICTURE_TYPE_JPEG
                    else -> Workbook.PICTURE_TYPE_PNG
                }
                val imgBytes = imgFile.readBytes()
                val pictureIdx = wb.addPicture(imgBytes, imgType)

                val helper = wb.creationHelper
                val drawing = sheet.createDrawingPatriarch()
                val anchor = helper.createClientAnchor()
                anchor.setCol1(anchorCol)
                anchor.setRow1(anchorRow)
                anchor.setCol2(anchorCol + widthCells)
                anchor.setRow2(anchorRow + heightCells)
                drawing.createPicture(anchor, pictureIdx)

                save(wb, outputPath)
                "✅ Inserted image at ($anchorRow, $anchorCol) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error inserting image: ${e.message}"
        }
    }

    // ── 8. Create styled spreadsheet from CSV ───────────────────────

    @Tool
    @LLMDescription(
        "Create a professionally styled Excel spreadsheet from CSV data. " +
                "Automatically styles the header row with custom font, colors, and borders. " +
                "Applies alternating row colors for readability."
    )
    fun createStyledSpreadsheet(
        @LLMDescription("CSV text: rows separated by newline, columns by comma") csvText: String,
        @LLMDescription("Output .xlsx path") outputPath: String,
        @LLMDescription("Sheet name (default 'Sheet1')") sheetName: String? = null,
        @LLMDescription("Header font family") headerFont: String? = "Arial",
        @LLMDescription("Header font size") headerFontSize: Int? = 12,
        @LLMDescription("Header background color hex") headerBgColor: String? = "#003366",
        @LLMDescription("Header text color hex") headerTextColor: String? = "#FFFFFF",
        @LLMDescription("Body font family") bodyFont: String? = "Arial",
        @LLMDescription("Body font size") bodyFontSize: Int? = 11,
        @LLMDescription("Alternating row color hex (null to disable)") altRowColor: String? = "#F2F2F2",
        @LLMDescription("Auto-size columns after creation") autoSize: Boolean = true
    ): String {
        return try {
            val rows = csvText.trim().split("\n").filter { it.isNotBlank() }.map { it.split(",").map { c -> c.trim() } }
            if (rows.isEmpty()) return "❌ CSV data is empty"

            val wb = XSSFWorkbook()
            try {
                val sheet = wb.createSheet(sheetName ?: "Sheet1")

                // Header style
                val hdrStyle = wb.createCellStyle()
                val hdrFont = wb.createFont() as XSSFFont
                headerFont?.let { hdrFont.fontName = it }
                headerFontSize?.let { hdrFont.fontHeightInPoints = it.toShort() }
                hdrFont.bold = true
                headerTextColor?.let { hdrFont.setColor(parseXSSFColor(it)) }
                hdrStyle.setFont(hdrFont)
                headerBgColor?.let {
                    hdrStyle.setFillForegroundColor(parseXSSFColor(it))
                    hdrStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                }
                hdrStyle.alignment = HorizontalAlignment.CENTER
                hdrStyle.borderTop = BorderStyle.THIN
                hdrStyle.borderBottom = BorderStyle.THIN
                hdrStyle.borderLeft = BorderStyle.THIN
                hdrStyle.borderRight = BorderStyle.THIN

                // Body style
                val bodyStyle = wb.createCellStyle()
                val bFont = wb.createFont() as XSSFFont
                bodyFont?.let { bFont.fontName = it }
                bodyFontSize?.let { bFont.fontHeightInPoints = it.toShort() }
                bodyStyle.setFont(bFont)
                bodyStyle.borderTop = BorderStyle.THIN
                bodyStyle.borderBottom = BorderStyle.THIN
                bodyStyle.borderLeft = BorderStyle.THIN
                bodyStyle.borderRight = BorderStyle.THIN

                // Alt row style
                val altStyle = if (altRowColor != null) {
                    val s = wb.createCellStyle()
                    s.cloneStyleFrom(bodyStyle)
                    s.setFillForegroundColor(parseXSSFColor(altRowColor))
                    s.fillPattern = FillPatternType.SOLID_FOREGROUND
                    s
                } else null

                for ((rIdx, rowData) in rows.withIndex()) {
                    val xRow = sheet.createRow(rIdx)
                    for ((cIdx, cellVal) in rowData.withIndex()) {
                        val cell = xRow.createCell(cIdx)
                        val num = cellVal.toDoubleOrNull()
                        if (num != null) cell.setCellValue(num) else cell.setCellValue(SpreadsheetSafety.escapeFormula(cellVal))

                        cell.cellStyle = when {
                            rIdx == 0 -> hdrStyle
                            altStyle != null && rIdx % 2 == 0 -> altStyle
                            else -> bodyStyle
                        }
                    }
                }

                if (autoSize) {
                    val maxCols = rows.maxOf { it.size }
                    for (c in 0 until maxCols) sheet.autoSizeColumn(c)
                }

                save(wb, outputPath)
                "✅ Created styled spreadsheet (${rows.size} rows) and saved to: $outputPath"
            } finally {
                wb.close()
            }
        } catch (e: Throwable) {
            "❌ Error creating styled spreadsheet: ${e.message}"
        }
    }

    // ── 9. Add data validation (dropdown) ───────────────────────────

    @Tool
    @LLMDescription("Add a dropdown data validation list to a cell range")
    fun addDropdownValidation(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Start row (0-based)") startRow: Int,
        @LLMDescription("Start col (0-based)") startCol: Int,
        @LLMDescription("End row (inclusive)") endRow: Int,
        @LLMDescription("End col (inclusive)") endCol: Int,
        @LLMDescription("Comma-separated list of allowed values") options: String,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                val optionList = options.split(",").map { it.trim() }.toTypedArray()

                val constraint = sheet.dataValidationHelper.createExplicitListConstraint(optionList)
                val addressList = org.apache.poi.ss.util.CellRangeAddressList(startRow, endRow, startCol, endCol)
                val validation = sheet.dataValidationHelper.createValidation(constraint, addressList)
                validation.showErrorBox = true
                sheet.addValidationData(validation)

                save(wb, outputPath)
                "✅ Added dropdown validation (${optionList.size} options) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error adding validation: ${e.message}"
        }
    }

    // ── 10. Set number format ───────────────────────────────────────

    @Tool
    @LLMDescription(
        "Apply a number format to a range of cells. " +
                "Common formats: '#,##0' (thousands), '#,##0.00' (decimal), '0.00%' (percent), " +
                "'yyyy-mm-dd' (date), '$#,##0.00' (currency), '0.00E+00' (scientific)"
    )
    fun setNumberFormat(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Start row (0-based)") startRow: Int,
        @LLMDescription("Start col (0-based)") startCol: Int,
        @LLMDescription("End row (inclusive)") endRow: Int,
        @LLMDescription("End col (inclusive)") endCol: Int,
        @LLMDescription("Number format string, e.g. '#,##0.00'") format: String,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                val dataFormat = wb.createDataFormat()
                val fmtIdx = dataFormat.getFormat(format)
                // Create one shared style for the entire range to avoid hitting Excel's 64K style limit
                val fmtStyle = wb.createCellStyle()
                fmtStyle.dataFormat = fmtIdx

                for (r in startRow..endRow) {
                    val row = getOrCreateRow(sheet, r)
                    for (c in startCol..endCol) {
                        val cell = getOrCreateCell(row, c)
                        cell.cellStyle = fmtStyle
                    }
                }

                save(wb, outputPath)
                "✅ Applied number format '$format' and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting number format: ${e.message}"
        }
    }

    // ── 11. Add formula to cell ─────────────────────────────────────

    @Tool
    @LLMDescription(
        "Set an Excel formula in a cell. Examples: 'SUM(A1:A10)', 'AVERAGE(B2:B20)', 'IF(A1>10,\"Yes\",\"No\")'"
    )
    fun setFormula(
        @LLMDescription("Path to .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name") sheetName: String? = null,
        @LLMDescription("Row index (0-based)") row: Int,
        @LLMDescription("Column index (0-based)") col: Int,
        @LLMDescription("Excel formula (without leading =)") formula: String,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val sheet = getOrCreateSheet(wb, sheetName)
                val xRow = getOrCreateRow(sheet, row)
                val cell = getOrCreateCell(xRow, col)
                cell.cellFormula = formula.removePrefix("=")
                save(wb, outputPath)
                "✅ Set formula '=$formula' at ($row, $col) and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error setting formula: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Delete a worksheet from an Excel workbook. " +
                "Useful for removing placeholder sheets such as the default Sheet1 once the real sheets are ready."
    )
    fun deleteSheet(
        @LLMDescription("Path to the existing .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name to delete") sheetName: String,
        @LLMDescription("Output .xlsx path") outputPath: String
    ): String {
        return try {
            withWorkbook(xlsxPath) { wb ->
                val index = wb.getSheetIndex(sheetName)
                if (index < 0) {
                    return "❌ Sheet not found: $sheetName. Available sheets: ${listSheetNames(wb).joinToString(", ")}"
                }
                if (wb.numberOfSheets <= 1) {
                    return "❌ Cannot delete the last remaining sheet in the workbook"
                }

                wb.removeSheetAt(index)
                wb.setActiveSheet(0)
                save(wb, outputPath)
                "✅ Deleted sheet '$sheetName' and saved to: $outputPath"
            }
        } catch (e: Throwable) {
            "❌ Error deleting sheet: ${e.message}"
        }
    }

    private companion object {
        private const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L
        private val ALLOWED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")
    }
}
