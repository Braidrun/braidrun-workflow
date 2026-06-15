package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.*
import java.nio.charset.Charset

@LLMDescription("CSV tools implemented with Apache Commons CSV. Robust parsing/writing, filtering, selection, merge, and conversion to/from XLSX.")
class CSVTools : ToolSet {

    init { PoiSecurity.ensureHardened() }

    // ---------- Helpers ----------
    private fun detectDelimiter(file: File, charset: Charset = Charsets.UTF_8): Char {
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), charset)).use { br ->
                var line: String?
                while (true) {
                    line = br.readLine() ?: break
                    val l = line.trim()
                    if (l.isEmpty()) continue
                    val candidates = listOf(',', ';', '\t', '|')
                    val counts = candidates.associateWith { ch -> l.count { it == ch } }
                    return counts.maxByOrNull { it.value }?.key ?: ','
                }
                ','
            }
        } catch (_: Exception) {
            ','
        }
    }

    private fun buildFormat(delim: Char, hasHeader: Boolean?): CSVFormat {
        val b = CSVFormat.DEFAULT.builder()
            .setDelimiter(delim)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .setAllowMissingColumnNames(true)
        if (hasHeader == true) {
            b.setHeader() // use first record as header
            b.setSkipHeaderRecord(true)
        }
        return b.build()
    }

    private fun parse(csvPath: String, delimiter: String?, hasHeader: Boolean?): Pair<CSVParser, Char> {
        val file = ToolPathSecurity.validateInputPath(csvPath)
        val delim = delimiter?.firstOrNull() ?: detectDelimiter(file)
        val fmt = buildFormat(delim, hasHeader)
        val parser = CSVParser.parse(file, Charsets.UTF_8, fmt)
        return parser to delim
    }

    private fun CSVRecord.getOrEmpty(idx: Int): String {
        return if (idx >= 0 && idx < this.size()) this.get(idx) ?: "" else ""
    }

    @Tool
    @LLMDescription("Preview a CSV file: show delimiter, header (if any), and up to maxRows rows; output truncated to maxChars.")
    fun readCsvPreview(
        @LLMDescription("Path to the CSV file") csvPath: String,
        @LLMDescription("Delimiter character as a one-char string (optional). If blank, auto-detect , ; TAB |") delimiter: String? = null,
        @LLMDescription("Whether the first row is a header (default true)") hasHeader: Boolean? = true,
        @LLMDescription("Maximum data rows to preview (default 50)") maxRows: Int? = 50,
        @LLMDescription("Maximum characters to return (default 5000)") maxChars: Int? = 5000
    ): String {
        return try {
            parse(csvPath, delimiter, hasHeader).let { (parser, delim) ->
                parser.use { p ->
                    val headerNames: List<String> = if (hasHeader == true) {
                        p.headerNames ?: emptyList()
                    } else {
                        val iter = p.iterator()
                        val first = if (iter.hasNext()) iter.next() else null
                        val count = first?.size() ?: 0
                        (1..count).map { idx -> "col$idx" }
                    }
                    // Re-parse if we consumed first record during headerless sniffing
                    val p2 = if (hasHeader == true) p else CSVParser.parse(
                        ToolPathSecurity.validateInputPath(csvPath),
                        Charsets.UTF_8,
                        buildFormat(delim, false)
                    )
                    p2.use { pp ->
                        val rows = mutableListOf<List<String>>()
                        var count = 0
                        for (rec in pp) {
                            if (count >= (maxRows ?: 50)) break
                            val row = (0 until rec.size()).map { idx -> rec.getOrEmpty(idx) }
                            rows += row
                            count++
                        }
                        val sb = StringBuilder()
                        sb.append("File: ").append(csvPath).append('\n')
                        sb.append("Delimiter: '").append(delim).append("'\n")
                        if (hasHeader == true && headerNames.isNotEmpty()) {
                            sb.append("Headers: ").append(headerNames.joinToString(", ")).append('\n')
                        }
                        val previewSb = StringBuilder()
                        CSVPrinter(previewSb, CSVFormat.DEFAULT.builder().setDelimiter(delim).build()).use { printer ->
                            if (hasHeader == true && headerNames.isNotEmpty()) {
                                printer.printRecord(headerNames)
                            }
                            for (r in rows) printer.printRecord(r)
                        }
                        val suffix = if (hasHeader == true) " + header" else ""
                        sb.append("Preview (first ").append(rows.size).append(" rows").append(suffix).append("):\n")
                        val body = previewSb.toString().take(maxChars ?: 5000)
                        sb.append(body)
                        return sb.toString()
                    }
                }
            }
        } catch (e: Throwable) {
            "Error previewing CSV '$csvPath': ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get CSV basic info: delimiter, header names, column count, approximate row count (up to 100k).")
    fun getCsvInfo(
        @LLMDescription("Path to the CSV file") csvPath: String,
        @LLMDescription("Delimiter character as one-char string (optional)") delimiter: String? = null,
        @LLMDescription("Whether the first row is a header (default true)") hasHeader: Boolean? = true
    ): String {
        return try {
            parse(csvPath, delimiter, hasHeader).let { (parser, delim) ->
                parser.use { p ->
                    val headerNames = if (hasHeader == true) p.headerNames ?: emptyList() else emptyList()
                    var rows = 0L
                    for (@Suppress("UNUSED_VARIABLE") rec in p) {
                        rows++
                        if (rows >= 100_000L) break
                    }
                    val sb = StringBuilder()
                    sb.append("File: ").append(csvPath).append('\n')
                    sb.append("Delimiter: '").append(delim).append("'\n")
                    if (hasHeader == true) sb.append("Headers: ").append(headerNames.joinToString(", ")).append('\n')
                    sb.append("Approx. rows (excluding header): ").append(rows).append('\n')
                    val colInfo =
                        if (hasHeader == true && headerNames.isNotEmpty()) headerNames.size.toString() else "unknown (depends on rows)"
                    sb.append("Columns: ").append(colInfo).append('\n')
                    sb.toString()
                }
            }
        } catch (e: Throwable) {
            "Error reading CSV info '$csvPath': ${e.message}"
        }
    }

    private fun resolveColumnIndex(header: List<String>?, column: String): Int? {
        val idxByName = header?.indexOf(column)?.takeIf { it >= 0 }
        if (idxByName != null) return idxByName
        return column.toIntOrNull()
    }

    @Tool
    @LLMDescription("Select a subset of columns by name or index and write to a new CSV (non-destructive).")
    fun selectColumns(
        @LLMDescription("Path to the CSV file") csvPath: String,
        @LLMDescription("Destination CSV path") outputPath: String,
        @LLMDescription("Column identifiers (names if header exists; otherwise 0-based indices as strings)") columns: List<String>,
        @LLMDescription("Delimiter character as one-char string (optional)") delimiter: String? = null,
        @LLMDescription("Whether the first row is a header (default true)") hasHeader: Boolean? = true,
        @LLMDescription("Include header row in output (default true if hasHeader)") includeHeader: Boolean? = null
    ): String {
        return try {
            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            parse(csvPath, delimiter, hasHeader).let { (parser, delim) ->
                parser.use { p ->
                    val header = if (hasHeader == true) p.headerNames else null
                    val colIndices: List<Int> = columns.mapNotNull { c -> resolveColumnIndex(header, c) }
                    if (colIndices.isEmpty()) return "No valid columns resolved from: $columns"
                    CSVPrinter(
                        outFile.bufferedWriter(Charsets.UTF_8),
                        CSVFormat.DEFAULT.builder().setDelimiter(delim).build()
                    ).use { printer ->
                        val writeHeader = includeHeader ?: (hasHeader == true)
                        if (writeHeader) {
                            val hdr = if (header != null) colIndices.map { idx ->
                                header.getOrNull(idx) ?: "col$idx"
                            } else colIndices.map { "col$it" }
                            printer.printRecord(hdr)
                        }
                        for (rec in p) {
                            val row = colIndices.map { idx -> SpreadsheetSafety.escapeFormula(rec.getOrEmpty(idx)) }
                            printer.printRecord(row)
                        }
                    }
                }
            }
            "Selected ${columns.size} columns and saved to: ${outFile.absolutePath}"
        } catch (e: Throwable) {
            "Error selecting columns: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Filter rows by a column using simple ops: equals, contains, startsWith, endsWith; write to a new CSV.")
    fun filterRows(
        @LLMDescription("Path to the CSV file") csvPath: String,
        @LLMDescription("Destination CSV path") outputPath: String,
        @LLMDescription("Column name (if header) or 0-based index as string") column: String,
        @LLMDescription("Operator: equals | contains | startsWith | endsWith") op: String,
        @LLMDescription("Value to match") value: String,
        @LLMDescription("Delimiter character as one-char string (optional)") delimiter: String? = null,
        @LLMDescription("Whether the first row is a header (default true)") hasHeader: Boolean? = true,
        @LLMDescription("Include header row in output (default true if hasHeader)") includeHeader: Boolean? = null,
        @LLMDescription("Case sensitive matching (default false)") caseSensitive: Boolean? = false
    ): String {
        return try {
            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            parse(csvPath, delimiter, hasHeader).let { (parser, delim) ->
                parser.use { p ->
                    val header = if (hasHeader == true) p.headerNames else null
                    val colIdx = resolveColumnIndex(header, column) ?: return "Column not found: $column"
                    val writeHeader = includeHeader ?: (hasHeader == true)
                    val matcher: (String) -> Boolean = { cell ->
                        val a = if (caseSensitive == true) cell else cell.lowercase()
                        val b = if (caseSensitive == true) value else value.lowercase()
                        when (op.lowercase()) {
                            "equals" -> a == b
                            "contains" -> a.contains(b)
                            "startswith" -> a.startsWith(b)
                            "endswith" -> a.endsWith(b)
                            else -> false
                        }
                    }
                    CSVPrinter(
                        outFile.writer(Charsets.UTF_8),
                        CSVFormat.DEFAULT.builder().setDelimiter(delim).build()
                    ).use { printer ->
                        if (writeHeader && header != null) printer.printRecord(header)
                        var matched = 0
                        var total = 0
                        for (rec in p) {
                            total++
                            val v = rec.getOrEmpty(colIdx)
                            if (matcher(v)) {
                                // print the entire row, escaping formula-leading values.
                                val row = (0 until rec.size()).map { idx -> SpreadsheetSafety.escapeFormula(rec.getOrEmpty(idx)) }
                                printer.printRecord(row)
                                matched++
                            }
                        }
                        return "Filtered $matched / $total rows on column '$column' with op='$op' and saved to: ${outFile.absolutePath}"
                    }
                }
            }
        } catch (e: Throwable) {
            "Error filtering rows: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Merge multiple CSV files. If hasHeader=true, unifies header by name across files; missing values become empty. Saves to outputPath.")
    fun mergeCsvFiles(
        @LLMDescription("Source CSV paths to merge in order") sourcePaths: List<String>,
        @LLMDescription("Destination CSV path") outputPath: String,
        @LLMDescription("Whether files include header (default true)") hasHeader: Boolean? = true,
        @LLMDescription("Delimiter character as one-char string to use in output (optional; default auto-detect from first file)") delimiter: String? = null
    ): String {
        if (sourcePaths.isEmpty()) return "No source CSV files provided"
        return try {
            val firstFile = ToolPathSecurity.validateInputPath(sourcePaths.first())
            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            val outDelim = delimiter?.firstOrNull() ?: detectDelimiter(firstFile)
            val outFmt = CSVFormat.DEFAULT.builder().setDelimiter(outDelim).build()
            CSVPrinter(outFile.writer(Charsets.UTF_8), outFmt).use { printer ->
                var headerUnion: MutableList<String>? = null
                for ((idx, path) in sourcePaths.withIndex()) {
                    val file = ToolPathSecurity.validateInputPath(path)
                    val delim = detectDelimiter(file)
                    val fmt = buildFormat(delim, hasHeader)
                    CSVParser.parse(file, Charsets.UTF_8, fmt).use { parser ->
                        val header = if (hasHeader == true) parser.headerNames else null
                        if (idx == 0) {
                            headerUnion = if (hasHeader == true && header != null) header.toMutableList() else null
                            headerUnion?.let { printer.printRecord(it) }
                        } else if (hasHeader == true && header != null) {
                            // If the first file had no usable header (hasHeader==false, or
                            // its headerNames was empty), seed headerUnion from this file
                            // rather than crashing on the old `headerUnion!!`.
                            val union = headerUnion ?: header.toMutableList().also { headerUnion = it }
                            for (h in header) if (!union.contains(h)) union.add(h)
                        }
                        val currentUnion = headerUnion
                        if (currentUnion == null) {
                            for (rec in parser) {
                                val row = (0 until rec.size()).map { i -> SpreadsheetSafety.escapeFormula(rec.getOrEmpty(i)) }
                                printer.printRecord(row)
                            }
                        } else {
                            // header may be null here if hasHeader is false/null but a
                            // previous file set the union. Fall back to index 0-based access.
                            val idxMap: Map<String, Int> = header?.mapIndexed { i, h -> h to i }?.toMap().orEmpty()
                            for (rec in parser) {
                                val row = currentUnion.map { h -> SpreadsheetSafety.escapeFormula(rec.getOrEmpty(idxMap[h] ?: -1)) }
                                printer.printRecord(row)
                            }
                        }
                    }
                }
            }
            "Merged ${sourcePaths.size} CSV files into: ${outFile.absolutePath}"
        } catch (e: Exception) {
            "Error merging CSV files: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Convert a CSV file to an .xlsx workbook (single sheet).")
    fun csvToXlsx(
        @LLMDescription("Path to the CSV file") csvPath: String,
        @LLMDescription("Destination .xlsx path") outputXlsxPath: String,
        @LLMDescription("Sheet name (default 'Sheet1')") sheetName: String? = null,
        @LLMDescription("Delimiter character as one-char string (optional)") delimiter: String? = null,
        @LLMDescription("Whether the first row is a header (default true)") hasHeader: Boolean? = true
    ): String {
        return try {
            val file = ToolPathSecurity.validateInputPath(csvPath)
            val outFile = ToolPathSecurity.validateOutputPath(outputXlsxPath)
            val delim = delimiter?.firstOrNull() ?: detectDelimiter(file)
            val fmt = buildFormat(delim, hasHeader)
            CSVParser.parse(file, Charsets.UTF_8, fmt).use { parser ->
                XSSFWorkbook().use { wb ->
                    val sh = wb.createSheet(sheetName ?: "Sheet1")
                    var rIdx = 0
                    if (hasHeader == true) {
                        val header = parser.headerNames ?: emptyList()
                        val row = sh.createRow(rIdx++)
                        header.forEachIndexed { cIdx, v ->
                            row.createCell(cIdx).setCellValue(SpreadsheetSafety.escapeFormula(v))
                        }
                    }
                    for (rec in parser) {
                        val row = sh.createRow(rIdx++)
                        for (c in 0 until rec.size()) {
                            val v = rec.getOrEmpty(c)
                            val cell = row.createCell(c)
                            val num = v.toDoubleOrNull()
                            // Only escape string cells; numeric parses don't interpret as formulas.
                            if (num != null) cell.setCellValue(num) else cell.setCellValue(SpreadsheetSafety.escapeFormula(v))
                        }
                    }
                    FileOutputStream(outFile).use { fos -> wb.write(fos) }
                }
            }
            "Converted CSV to XLSX at: ${outFile.absolutePath}"
        } catch (e: Throwable) {
            "Error converting CSV to XLSX: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Export an Excel sheet to CSV file. Values are written as displayed strings where possible.")
    fun xlsxSheetToCsv(
        @LLMDescription("Path to the .xlsx file") xlsxPath: String,
        @LLMDescription("Sheet name to export") sheetName: String,
        @LLMDescription("Destination CSV path") outputCsvPath: String,
        @LLMDescription("Delimiter character as one-char string (optional; default ,)") delimiter: String? = null,
        @LLMDescription("Include an artificial header row (column_1, column_2, ...) if the sheet lacks a header (default false)") includeHeader: Boolean? = false
    ): String {
        return try {
            val outFile = ToolPathSecurity.validateOutputPath(outputCsvPath)
            val delim = delimiter?.firstOrNull() ?: ','
            val fmt = CSVFormat.DEFAULT.builder().setDelimiter(delim).build()
            CSVPrinter(outFile.writer(Charsets.UTF_8), fmt).use { printer ->
                FileInputStream(ToolPathSecurity.validateInputPath(xlsxPath)).use { fis ->
                    WorkbookFactory.create(fis).use { wb ->
                        val sheet = wb.getSheet(sheetName) ?: return "Sheet not found: $sheetName"
                        val lastRow = sheet.lastRowNum
                        var maxCols = 0
                        for (ri in sheet.firstRowNum..lastRow) {
                            val row = sheet.getRow(ri) ?: continue
                            maxCols = maxOf(maxCols, row.lastCellNum.toInt())
                        }
                        if (includeHeader == true && maxCols > 0) {
                            val hdr = (1..maxCols).map { i -> "column_$i" }
                            printer.printRecord(hdr)
                        }
                        for (ri in sheet.firstRowNum..lastRow) {
                            val row = sheet.getRow(ri)
                            if (row == null) {
                                printer.printRecord(emptyList<String>())
                                continue
                            }
                            val values = (0 until maxCols).map { ci ->
                                val cell = row.getCell(ci)
                                when {
                                    cell == null -> ""
                                    cell.cellType == CellType.NUMERIC -> cell.numericCellValue.toString()
                                    cell.cellType == CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                    cell.cellType == CellType.STRING -> SpreadsheetSafety.escapeFormula(cell.stringCellValue)
                                    cell.cellType == CellType.FORMULA -> try {
                                        cell.numericCellValue.toString()
                                    } catch (_: Throwable) {
                                        // Treat the rendered formula-as-string as untrusted.
                                        SpreadsheetSafety.escapeFormula(cell.toString())
                                    }

                                    else -> SpreadsheetSafety.escapeFormula(cell.toString())
                                }
                            }
                            printer.printRecord(values)
                        }
                    }
                }
            }
            "Exported sheet '$sheetName' to CSV at: ${outFile.absolutePath}"
        } catch (e: Throwable) {
            "Error exporting sheet to CSV: ${e.message}"
        }
    }
}
