package com.fartech.agents.tools

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.file.Path

/**
 * Phase 11 regression — `readRangeAsCSV` must reject adversarial row/column spans
 * instead of trying to iterate billions of cells (DoS via OOM or CPU exhaustion).
 */
class OfficeToolsRangeSecurityTest {

    private fun makeSheet(dir: Path): String {
        val file = dir.resolve("sheet.xlsx").toFile()
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            for (r in 0 until 3) {
                val row = sheet.createRow(r)
                for (c in 0 until 3) {
                    row.createCell(c).setCellValue("r${r}c${c}")
                }
            }
            FileOutputStream(file).use { wb.write(it) }
        }
        return file.absolutePath
    }

    @Test
    fun `oversized row span is rejected`(@TempDir tmp: Path) {
        val path = makeSheet(tmp)
        val result = ExcelTools.readRangeAsCSV(
            xlsxPath = path,
            sheetName = "Sheet1",
            startRow = 0,
            startCol = 0,
            endRow = ExcelTools.MAX_RANGE_ROW_SPAN + 1,
            endCol = 0
        )
        assertTrue(result.startsWith("Error: row span"), "Got: $result")
    }

    @Test
    fun `oversized col span is rejected`(@TempDir tmp: Path) {
        val path = makeSheet(tmp)
        val result = ExcelTools.readRangeAsCSV(
            xlsxPath = path,
            sheetName = "Sheet1",
            startRow = 0,
            startCol = 0,
            endRow = 0,
            endCol = ExcelTools.MAX_RANGE_COL_SPAN + 1
        )
        assertTrue(result.startsWith("Error: column span"), "Got: $result")
    }

    @Test
    fun `total cells product cap rejects narrow but very tall ranges`(@TempDir tmp: Path) {
        val path = makeSheet(tmp)
        // Each axis under its cap, but product exceeds MAX_RANGE_TOTAL_CELLS.
        val result = ExcelTools.readRangeAsCSV(
            xlsxPath = path,
            sheetName = "Sheet1",
            startRow = 0,
            startCol = 0,
            endRow = 49_999,
            endCol = 100  // 49,999 * 101 = ~5M, exceeds 1M cap
        )
        assertTrue(result.startsWith("Error: range area"), "Got: $result")
    }

    @Test
    fun `negative or inverted range is rejected`(@TempDir tmp: Path) {
        val path = makeSheet(tmp)
        val result = ExcelTools.readRangeAsCSV(
            xlsxPath = path,
            sheetName = "Sheet1",
            startRow = -1,
            startCol = 0,
            endRow = 0,
            endCol = 0
        )
        assertTrue(result.startsWith("Error: invalid"), "Got: $result")
    }

    @Test
    fun `normal range still works`(@TempDir tmp: Path) {
        val path = makeSheet(tmp)
        val result = ExcelTools.readRangeAsCSV(
            xlsxPath = path,
            sheetName = "Sheet1",
            startRow = 0,
            startCol = 0,
            endRow = 1,
            endCol = 1
        )
        assertTrue(result.contains("r0c0"))
        assertTrue(result.contains("r1c1"))
    }
}
