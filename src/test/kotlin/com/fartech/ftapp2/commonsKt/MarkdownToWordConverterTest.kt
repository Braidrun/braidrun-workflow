package com.fartech.ftapp2.commonsKt

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path

class MarkdownToWordConverterTest {

    private fun convertAndExtract(markdown: String, dir: Path): String {
        val out = dir.resolve("out.docx").toFile()
        MarkdownToWordConverter.convertToWord(markdown, out)
        return extractAllText(out)
    }

    private fun extractAllText(file: File): String =
        FileInputStream(file).use { fis ->
            XWPFDocument(fis).use { doc ->
                buildString {
                    doc.paragraphs.forEach { appendLine(it.text) }
                    doc.tables.forEach { table ->
                        table.rows.forEach { row ->
                            row.tableCells.forEach { cell -> appendLine(cell.text) }
                        }
                    }
                }
            }
        }

    @Test
    fun `soft line breaks render as spaces instead of fusing words`(@TempDir dir: Path) {
        // "line one\nline two" inside one paragraph is a SoftLineBreak — the old
        // converter emitted nothing for it, producing "line oneline two".
        val text = convertAndExtract("line one\nline two", dir)
        assertTrue(text.contains("line one line two"), "expected space-joined lines, got: $text")
    }

    @Test
    fun `inline code inside bold is not silently deleted`(@TempDir dir: Path) {
        // Code is a leaf node carrying content in `literal`; extractText's recursion
        // into (zero) children deleted it from styled containers.
        val text = convertAndExtract("This is **bold `code` text**", dir)
        assertTrue(text.contains("bold code text"), "inline code dropped: $text")
    }

    @Test
    fun `inline code inside heading is preserved`(@TempDir dir: Path) {
        val text = convertAndExtract("# Use `gradle` here", dir)
        assertTrue(text.contains("Use gradle here"), "heading code dropped: $text")
    }
}
