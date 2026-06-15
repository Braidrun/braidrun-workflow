package com.fartech.ftapp2.commonsKt

import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTableRow
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

object MarkdownToWordConverter {

    private val logger = LoggerFactory.getLogger(MarkdownToWordConverter::class.java)

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create()
            )
        )
        .build()

    fun convertToWord(markdownText: String, outputFile: File, title: String? = null) {
        try {
            if (markdownText.isBlank()) {
                logger.warn("Markdown text is empty, creating empty document")
                XWPFDocument().use { doc ->
                    if (title != null) {
                        doc.properties.coreProperties.title = title
                    }
                    val paragraph = doc.createParagraph()
                    paragraph.createRun().setText("(空文档)")
                    FileOutputStream(outputFile).use { fos ->
                        doc.write(fos)
                    }
                }
                return
            }

            outputFile.parentFile?.mkdirs()

            val document = parser.parse(markdownText)
            XWPFDocument().use { doc ->
                if (title != null) {
                    doc.properties.coreProperties.title = title
                }

                convertNode(document, doc)

                if (doc.paragraphs.isEmpty() && doc.tables.isEmpty()) {
                    val paragraph = doc.createParagraph()
                    paragraph.createRun().setText("(空文档)")
                }

                FileOutputStream(outputFile).use { fos ->
                    doc.write(fos)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to convert Markdown to Word", e)
            throw RuntimeException("Failed to convert Markdown to Word: ${e.message}", e)
        }
    }

    private fun convertNode(node: Node, doc: XWPFDocument) {
        var current: Node? = node.firstChild
        while (current != null) {
            when (current) {
                is Heading -> {
                    val paragraph = doc.createParagraph()
                    val run = paragraph.createRun()
                    run.setText(extractText(current))
                    run.isBold = true

                    when (current.level) {
                        1 -> run.fontSize = 24
                        2 -> run.fontSize = 20
                        3 -> run.fontSize = 18
                        4 -> run.fontSize = 16
                        5 -> run.fontSize = 14
                        6 -> run.fontSize = 12
                        else -> run.fontSize = 12
                    }

                    paragraph.style = when (current.level) {
                        1 -> "Heading1"
                        2 -> "Heading2"
                        3 -> "Heading3"
                        else -> "Normal"
                    }
                }

                is Paragraph -> {
                    val paragraph = doc.createParagraph()
                    val hasContent = current.firstChild != null
                    if (hasContent) {
                        convertInlineNodes(current, paragraph)
                    } else {
                        paragraph.createRun().setText(" ")
                    }
                }

                is BulletList -> convertBulletList(current, doc)
                is OrderedList -> convertOrderedList(current, doc)
                is FencedCodeBlock, is IndentedCodeBlock -> convertCodeBlock(current, doc)

                is BlockQuote -> {
                    val paragraph = doc.createParagraph()
                    val run = paragraph.createRun()
                    run.setText(extractText(current))
                    run.isItalic = true
                    paragraph.indentFromLeft = 400
                }

                is ThematicBreak -> {
                    val paragraph = doc.createParagraph()
                    val run = paragraph.createRun()
                    run.addBreak()
                    run.setText("_________________________________________________________________")
                    run.fontSize = 8
                    paragraph.spacingAfter = 200
                }

                else -> {
                    val firstChild = current.firstChild
                    if (firstChild is TableHead || firstChild is TableBody) {
                        convertTable(current, doc)
                    } else {
                        convertNode(current, doc)
                    }
                }
            }
            current = current.next
        }
    }

    private fun convertCodeBlock(codeBlock: Node, doc: XWPFDocument) {
        val literal = when (codeBlock) {
            is FencedCodeBlock -> codeBlock.literal
            is IndentedCodeBlock -> codeBlock.literal
            else -> ""
        }
        val paragraph = doc.createParagraph()
        val run = paragraph.createRun()
        run.setText(literal)
        run.fontFamily = "Courier New"
        run.fontSize = 10
        run.color = "666666"
        paragraph.spacingAfter = 200
    }

    private fun convertInlineNodes(node: Node, paragraph: XWPFParagraph) {
        var current: Node? = node.firstChild
        while (current != null) {
            when (current) {
                is Text -> {
                    val run = paragraph.createRun()
                    run.setText(current.literal)
                }

                is StrongEmphasis -> {
                    val run = paragraph.createRun()
                    run.setText(extractText(current))
                    run.isBold = true
                }

                is Emphasis -> {
                    val run = paragraph.createRun()
                    run.setText(extractText(current))
                    run.isItalic = true
                }

                is Code -> {
                    val run = paragraph.createRun()
                    run.setText(current.literal)
                    run.fontFamily = "Courier New"
                    run.fontSize = 10
                    run.color = "CC0000"
                }

                is org.commonmark.ext.gfm.strikethrough.Strikethrough -> {
                    val run = paragraph.createRun()
                    run.setText(extractText(current))
                    run.setStrikeThrough(true)
                }

                is Link -> {
                    val run = paragraph.createRun()
                    val linkText = extractText(current)
                    run.setText(linkText)
                    run.color = "0000FF"
                    run.setUnderline(UnderlinePatterns.SINGLE)
                }

                is HardLineBreak -> {
                    val run = paragraph.createRun()
                    run.addBreak()
                }

                // SoftLineBreak is childless — without this case the else-branch
                // emitted nothing and "line one\nline two" rendered as
                // "line oneline two" (words fused across source line breaks).
                is SoftLineBreak -> {
                    val run = paragraph.createRun()
                    run.setText(" ")
                }

                else -> convertInlineNodes(current, paragraph)
            }
            current = current.next
        }
    }

    private fun convertBulletList(list: BulletList, doc: XWPFDocument) {
        convertListItems(list, doc) { _ -> "• " }
    }

    private fun convertOrderedList(list: OrderedList, doc: XWPFDocument) {
        convertListItems(list, doc, startIndex = 1) { itemIndex -> "${itemIndex}. " }
    }

    private fun convertListItems(list: Node, doc: XWPFDocument, startIndex: Int = 0, prefixProvider: (Int) -> String) {
        var item: Node? = list.firstChild
        var itemIndex = startIndex
        while (item != null) {
            if (item is ListItem) {
                val paragraph = doc.createParagraph()
                val run = paragraph.createRun()
                run.setText(prefixProvider(itemIndex))
                run.isBold = true
                convertListItemContent(item, paragraph)
                itemIndex++
            }
            item = item.next
        }
    }

    private fun convertListItemContent(item: ListItem, paragraph: XWPFParagraph) {
        var contentNode: Node? = item.firstChild
        var hasContent = false
        while (contentNode != null) {
            when (contentNode) {
                is Paragraph -> {
                    convertInlineNodes(contentNode, paragraph)
                    hasContent = true
                }

                is Text -> {
                    val textRun = paragraph.createRun()
                    textRun.setText(contentNode.literal)
                    hasContent = true
                }

                else -> {
                    convertInlineNodes(contentNode, paragraph)
                    hasContent = true
                }
            }
            contentNode = contentNode.next
        }
        if (!hasContent) {
            paragraph.createRun().setText(" ")
        }
    }

    private fun convertTable(table: Node, doc: XWPFDocument) {
        if (table.firstChild == null) {
            val wordTable = doc.createTable(1, 1)
            val cell = wordTable.getRow(0).getCell(0)
            cell.removeParagraph(0)
            val paragraph = cell.addParagraph()
            paragraph.createRun().setText("(空表格)")
            return
        }

        val wordTable = doc.createTable()
        var hasHeader = false
        var tableChild: Node? = table.firstChild
        while (tableChild != null) {
            if (tableChild is TableHead) {
                var headerRow: Node? = tableChild.firstChild
                while (headerRow != null) {
                    if (headerRow is TableRow) {
                        val wordRow = if (hasHeader) {
                            wordTable.createRow()
                        } else {
                            hasHeader = true
                            if (wordTable.rows.isEmpty()) {
                                wordTable.createRow()
                            }
                            wordTable.getRow(0)
                        }
                        convertTableRowCells(headerRow, wordRow, isHeader = true)
                    }
                    headerRow = headerRow.next
                }
            }

            if (tableChild is TableBody) {
                var row: Node? = tableChild.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val wordRow = wordTable.createRow()
                        convertTableRowCells(row, wordRow, isHeader = false)
                    }
                    row = row.next
                }
            }

            tableChild = tableChild.next
        }
    }

    private fun convertTableRowCells(row: TableRow, wordRow: XWPFTableRow, isHeader: Boolean) {
        var cell: Node? = row.firstChild
        var cellIndex = 0
        while (cell != null) {
            if (cell is TableCell) {
                if (cellIndex >= wordRow.tableCells.size) {
                    wordRow.createCell()
                }
                val wordCell = wordRow.getCell(cellIndex)
                if (wordCell.paragraphs.isNotEmpty()) {
                    wordCell.removeParagraph(0)
                }
                val paragraph = wordCell.addParagraph()
                val run = paragraph.createRun()
                run.setText(extractText(cell).trim())
                if (isHeader) {
                    run.isBold = true
                }
                cellIndex++
            }
            cell = cell.next
        }
    }

    private fun extractText(node: Node): String {
        val text = StringBuilder()
        var current: Node? = node.firstChild
        while (current != null) {
            when (current) {
                is Text -> text.append(current.literal)
                // Code is a LEAF carrying its content in `literal` — recursing into
                // (zero) children silently deleted inline code from headings, bold,
                // links, blockquotes and table cells.
                is Code -> text.append(current.literal)
                is HardLineBreak -> text.append("\n")
                is SoftLineBreak -> text.append(" ")
                else -> text.append(extractText(current))
            }
            current = current.next
        }
        return text.toString()
    }
}
