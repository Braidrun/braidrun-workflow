package com.fartech.agents.tools

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** No File/Path/temporary-file handles: only the host byte service may touch storage. */
internal class ConfinedCsvOperations(private val files: ToolFileAccess, private val guard: () -> Unit) {
    companion object {
        const val INPUT_BYTES = 8 * 1024 * 1024
        const val OUTPUT_BYTES = 16 * 1024 * 1024
        const val MAX_ROWS = 10_000
        const val MAX_COLUMNS = 256
        const val MAX_CELLS = 100_000
        const val CELL_BYTES = 32 * 1024
    }
    private data class Table(val header: List<String>?, val rows: List<List<String>>, val delimiter: Char) {
        val columns: Int get() = maxOf(header?.size ?: 0, rows.maxOfOrNull { it.size } ?: 0)
    }
    private class Budget(var bytes: Long = 0, var cells: Long = 0, var rows: Long = 0)
    private inline fun operation(action: () -> String): String {
        guard()
        return ToolByteBuffers.result(action(), guard)
    }
    private fun chooseDelimiter(value: String?, content: String? = null): Char {
        if (!value.isNullOrBlank()) {
            require(value.length == 1 && value[0] !in charArrayOf('\r', '\n', '\u0000', '"')) { "Invalid CSV delimiter" }
            return value[0]
        }
        val line = content?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return ','
        return listOf(',', ';', '\t', '|').maxByOrNull { candidate -> line.count { it == candidate } } ?: ','
    }
    private fun checkCells(row: List<String>, budget: Budget) {
        guard()
        require(row.size <= MAX_COLUMNS) { "CSV column limit exceeded" }
        require(row.all { it.length <= CELL_BYTES && it.toByteArray(Charsets.UTF_8).size <= CELL_BYTES }) { "CSV cell byte limit exceeded" }
        budget.cells += row.size
        require(budget.cells <= MAX_CELLS) { "CSV cell count limit exceeded" }
    }
    private fun parse(path: String, delimiter: String?, hasHeader: Boolean?, budget: Budget = Budget()): Table {
        val bytes = ToolByteBuffers.read(files, path, INPUT_BYTES, guard)
        budget.bytes += bytes.size
        require(budget.bytes <= 32L * 1024 * 1024) { "Combined CSV input limit exceeded" }
        val text = ToolByteBuffers.utf8(bytes)
        val separator = chooseDelimiter(delimiter, text)
        val builder = CSVFormat.DEFAULT.builder().setDelimiter(separator).setIgnoreEmptyLines(true).setTrim(true).setAllowMissingColumnNames(true)
        if (hasHeader == true) builder.setHeader().setSkipHeaderRecord(true)
        CSVParser.parse(StringReader(text), builder.get()).use { parser ->
            val header = if (hasHeader == true) parser.headerNames.toList().also {
                checkCells(it, budget)
                require(it.distinct().size == it.size) { "Duplicate CSV headers are ambiguous" }
            } else null
            val rows = mutableListOf<List<String>>()
            for (record in parser) {
                guard(); budget.rows++
                require(budget.rows <= MAX_ROWS) { "CSV row limit exceeded" }
                val row = record.toList(); checkCells(row, budget); rows += row
            }
            return Table(header, rows, separator)
        }
    }
    private fun write(path: String, rows: List<List<String>>, separator: Char) {
        val budget = Budget()
        require(rows.size <= MAX_ROWS + 1) { "CSV output row limit exceeded" }
        val output = BoundedToolOutput(OUTPUT_BYTES, guard)
        CSVPrinter(OutputStreamWriter(output, Charsets.UTF_8), CSVFormat.DEFAULT.builder().setDelimiter(separator).get()).use { printer ->
            rows.forEach { row -> checkCells(row, budget); printer.printRecord(row.map(SpreadsheetSafety::escapeFormula)) }
        }
        guard(); files.writeBytes(path, output.bytes()); guard()
    }
    private fun index(table: Table, column: String): Int {
        val result = table.header?.indexOf(column)?.takeIf { it >= 0 } ?: column.toIntOrNull()
        require(result != null && result in 0 until table.columns) { "CSV column not found" }
        return result
    }
    fun readCsvPreview(csvPath: String, delimiter: String?, hasHeader: Boolean?, maxRows: Int?, maxChars: Int?): String = operation {
        val table = parse(csvPath, delimiter, hasHeader)
        val rowLimit = (maxRows ?: 50).coerceIn(0, 100)
        val body = StringBuilder()
        CSVPrinter(body, CSVFormat.DEFAULT.builder().setDelimiter(table.delimiter).get()).use { printer ->
            table.header?.let { printer.printRecord(it) }
            table.rows.take(rowLimit).forEach { printer.printRecord(it) }
        }
        "File: $csvPath\nDelimiter: '${table.delimiter}'\nPreview (first ${minOf(rowLimit, table.rows.size)} rows):\n" + body.toString().take((maxChars ?: 5000).coerceIn(0, 32768))
    }
    fun getCsvInfo(csvPath: String, delimiter: String?, hasHeader: Boolean?): String = operation {
        val table = parse(csvPath, delimiter, hasHeader)
        "File: $csvPath\nDelimiter: '${table.delimiter}'\nColumns: ${table.columns}\nRows: ${table.rows.size}\nHeaders: ${table.header.orEmpty().joinToString(", ")}"
    }
    fun selectColumns(csvPath: String, outputPath: String, columns: List<String>, delimiter: String?, hasHeader: Boolean?, includeHeader: Boolean?): String = operation {
        require(columns.size in 1..MAX_COLUMNS && columns.all { it.length <= CELL_BYTES }) { "Invalid column selection" }
        val table = parse(csvPath, delimiter, hasHeader); val selected = columns.map { index(table, it) }
        val rows = mutableListOf<List<String>>()
        if (includeHeader ?: (hasHeader == true)) rows += selected.map { table.header?.getOrNull(it) ?: "col$it" }
        rows += table.rows.map { row -> selected.map { row.getOrElse(it) { "" } } }
        write(outputPath, rows, table.delimiter)
        "Selected ${selected.size} columns and saved to: $outputPath"
    }
    fun filterRows(csvPath: String, outputPath: String, column: String, op: String, value: String, delimiter: String?, hasHeader: Boolean?, includeHeader: Boolean?, caseSensitive: Boolean?): String = operation {
        require(column.length <= CELL_BYTES && value.length <= CELL_BYTES && op.length <= 32) { "Filter arguments exceed limits" }
        val operator = op.lowercase(); require(operator in setOf("equals", "contains", "startswith", "endswith")) { "Unsupported CSV filter operator" }
        val table = parse(csvPath, delimiter, hasHeader); val selected = index(table, column)
        val target = if (caseSensitive == true) value else value.lowercase()
        val matched = table.rows.filter { row ->
            guard(); val original = row.getOrElse(selected) { "" }; val cell = if (caseSensitive == true) original else original.lowercase()
            when (operator) { "equals" -> cell == target; "contains" -> cell.contains(target); "startswith" -> cell.startsWith(target); else -> cell.endsWith(target) }
        }
        val rows = mutableListOf<List<String>>()
        if ((includeHeader ?: (hasHeader == true)) && table.header != null) rows += table.header
        rows += matched; write(outputPath, rows, table.delimiter)
        "Filtered ${matched.size} / ${table.rows.size} rows and saved to: $outputPath"
    }
    fun mergeCsvFiles(sourcePaths: List<String>, outputPath: String, hasHeader: Boolean?, delimiter: String?): String = operation {
        require(sourcePaths.size in 1..16) { "CSV merge requires one to sixteen files" }
        val budget = Budget(); val tables = sourcePaths.map { parse(it, delimiter, hasHeader, budget) }
        val rows = mutableListOf<List<String>>()
        if (hasHeader == true) {
            val union = tables.flatMap { it.header.orEmpty() }.distinct()
            require(union.size <= MAX_COLUMNS) { "Merged CSV column limit exceeded" }
            rows += union
            tables.forEach { table ->
                val indices = table.header.orEmpty().withIndex().associate { it.value to it.index }
                table.rows.forEach { row -> rows += union.map { column -> indices[column]?.let { row.getOrElse(it) { "" } } ?: "" } }
            }
        } else tables.forEach { rows += it.rows }
        write(outputPath, rows, tables.first().delimiter)
        "Merged ${tables.size} CSV files into: $outputPath"
    }
    fun csvToXlsx(csvPath: String, outputXlsxPath: String, sheetName: String?, delimiter: String?, hasHeader: Boolean?): String = operation {
        require((sheetName ?: "Sheet1").length in 1..31) { "Invalid XLSX sheet name" }
        val table = parse(csvPath, delimiter, hasHeader)
        val rows = mutableListOf<List<String>>(); table.header?.let { rows += it }; rows += table.rows
        require(rows.size <= MAX_ROWS) { "XLSX output row limit exceeded" }
        val output = BoundedToolOutput(OUTPUT_BYTES, guard)
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(sheetName ?: "Sheet1")
            rows.forEachIndexed { rowIndex, values ->
                guard(); val row = sheet.createRow(rowIndex)
                values.forEachIndexed { column, value ->
                    val cell = row.createCell(column); val number = value.toDoubleOrNull()?.takeIf { it.isFinite() }
                    if (number != null && !(rowIndex == 0 && hasHeader == true)) cell.setCellValue(number)
                    else cell.setCellValue(SpreadsheetSafety.escapeFormula(value))
                }
            }
            workbook.write(output)
        }
        guard(); files.writeBytes(outputXlsxPath, output.bytes()); guard()
        "Converted CSV to XLSX at: $outputXlsxPath"
    }
    fun xlsxSheetToCsv(xlsxPath: String, sheetName: String, outputCsvPath: String, delimiter: String?, includeHeader: Boolean?): String = operation {
        require(sheetName.length in 1..31) { "Invalid XLSX sheet name" }
        val bytes = ToolByteBuffers.read(files, xlsxPath, INPUT_BYTES, guard)
        preflightWorkbook(bytes)
        val rows = mutableListOf<List<String>>()
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            require(workbook.numberOfSheets in 1..16) { "XLSX sheet count limit exceeded" }
            val sheet = requireNotNull(workbook.getSheet(sheetName)) { "XLSX sheet not found" }
            require(sheet.firstRowNum >= 0 && sheet.lastRowNum < MAX_ROWS) { "XLSX row limit exceeded" }
            var columns = 0
            for (row in sheet) { guard(); columns = maxOf(columns, row.lastCellNum.toInt()); require(columns <= MAX_COLUMNS) { "XLSX column limit exceeded" } }
            require((sheet.lastRowNum + 1L) * columns <= MAX_CELLS) { "XLSX output cell count limit exceeded" }
            if (includeHeader == true) rows += (1..columns).map { "column_$it" }
            for (rowIndex in sheet.firstRowNum..sheet.lastRowNum) {
                guard(); val row = sheet.getRow(rowIndex)
                rows += (0 until columns).map { column ->
                    val cell = row?.getCell(column)
                    val type = if (cell?.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell?.cellType
                    when (type) { CellType.NUMERIC -> cell!!.numericCellValue.toString(); CellType.BOOLEAN -> cell!!.booleanCellValue.toString()
                        CellType.STRING -> cell!!.stringCellValue; else -> "" }
                }
            }
        }
        write(outputCsvPath, rows, chooseDelimiter(delimiter))
        "Exported sheet '$sheetName' to CSV at: $outputCsvPath"
    }
    /** Bound decompression and worksheet structure before POI allocates workbook objects. */
    private fun preflightWorkbook(bytes: ByteArray) {
        var expanded = 0L; var sheets = 0; var cells = 0; val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                guard(); val entry = zip.nextEntry ?: break
                require(names.add(entry.name) && names.size <= 256 && !entry.name.startsWith('/') &&
                    entry.name.split('/').none { it == ".." } && '\\' !in entry.name) { "Invalid XLSX ZIP entry" }
                val output = BoundedToolOutput(INPUT_BYTES, guard); val buffer = ByteArray(32768)
                while (true) {
                    val count = zip.read(buffer); if (count < 0) break
                    expanded += count; require(expanded <= 32L * 1024 * 1024) { "XLSX expanded byte limit exceeded" }
                    output.write(buffer, 0, count)
                }
                if (entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml")) {
                    sheets++; require(sheets <= 16) { "XLSX sheet count limit exceeded" }
                    cells += inspectXml(output.bytes(), sharedStrings = false)
                    require(cells <= MAX_CELLS) { "XLSX cell count limit exceeded" }
                } else if (entry.name == "xl/sharedStrings.xml") {
                    inspectXml(output.bytes(), sharedStrings = true)
                }
            }
        }
        require("[Content_Types].xml" in names && "xl/workbook.xml" in names && sheets > 0) { "Not an XLSX workbook" }
    }
    private fun inspectXml(bytes: ByteArray, sharedStrings: Boolean): Int {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setXMLResolver { _, _, _, _ -> throw SecurityException("External XML references are forbidden") }
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
        var rows = 0; var cells = 0; var rowCells = 0; var cellBytes = 0; var inCell = false
        try {
            while (reader.hasNext()) {
                guard()
                when (reader.next()) {
                    XMLStreamConstants.DTD -> throw SecurityException("XLSX DTD is forbidden")
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "sst" -> if (sharedStrings) {
                            for (name in listOf("count", "uniqueCount")) reader.getAttributeValue(null, name)?.let {
                                require((it.toLongOrNull() ?: -1L) in 0L..MAX_CELLS.toLong()) { "XLSX shared-string count limit exceeded" }
                            }
                        }
                        "si" -> if (sharedStrings) {
                            cells++; cellBytes = 0; inCell = true
                            require(cells <= MAX_CELLS) { "XLSX shared-string count limit exceeded" }
                        }
                        "row" -> {
                            rows++; rowCells = 0; require(rows <= MAX_ROWS) { "XLSX row count limit exceeded" }
                            reader.getAttributeValue(null, "r")?.let { require((it.toIntOrNull() ?: -1) in 1..MAX_ROWS) { "XLSX row index limit exceeded" } }
                        }
                        "c" -> {
                            cells++; rowCells++; cellBytes = 0; inCell = true
                            require(cells <= MAX_CELLS && rowCells <= MAX_COLUMNS) { "XLSX cell count limit exceeded" }
                            reader.getAttributeValue(null, "r")?.let { reference ->
                                require(reference.length <= 16 && reference.matches(Regex("[A-Z]{1,3}[1-9][0-9]{0,4}"))) { "Invalid XLSX cell reference" }
                                val column = reference.takeWhile { it in 'A'..'Z' }.fold(0) { total, letter -> total * 26 + (letter - 'A' + 1) }
                                require(column <= MAX_COLUMNS && reference.dropWhile { it in 'A'..'Z' }.toInt() <= MAX_ROWS) { "XLSX cell coordinates exceed limits" }
                            }
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inCell) {
                        cellBytes += reader.text.toByteArray(Charsets.UTF_8).size
                        require(cellBytes <= CELL_BYTES) { "XLSX cell text limit exceeded" }
                    }
                    XMLStreamConstants.END_ELEMENT -> if (reader.localName == "c" || (sharedStrings && reader.localName == "si")) inCell = false
                }
            }
        } finally { reader.close() }
        return cells
    }
}
