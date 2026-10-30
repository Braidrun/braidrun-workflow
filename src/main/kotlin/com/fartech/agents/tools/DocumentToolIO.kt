package com.fartech.agents.tools

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.util.ZipEntrySource
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.awt.image.BufferedImage
import java.io.*
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import kotlin.math.ceil

/** Per-invocation resource ceilings. These are independent of POI's process-wide ZIP settings. */
data class DocumentToolLimits(
    val maxInputBytes: Int = 32 * 1024 * 1024,
    val maxOutputBytes: Int = 64 * 1024 * 1024,
    val maxTotalInputBytes: Int = 128 * 1024 * 1024,
    val maxTotalOutputBytes: Int = 128 * 1024 * 1024,
    val maxZipEntries: Int = 2048,
    val maxZipEntryBytes: Int = 32 * 1024 * 1024,
    val maxZipExpandedBytes: Int = 128 * 1024 * 1024,
    val maxZipRatio: Int = 200,
    val maxPages: Int = 200,
    val maxPixels: Long = 16_000_000,
    val maxDimension: Int = 8192,
    val maxRows: Int = 10000,
    val maxColumns: Int = 256,
    val maxCells: Int = 100000,
    val maxTextChars: Int = 1_000_000,
    val maxOperations: Int = 200000,
    val maxDurationMillis: Long = 60000,
    val allowMissingInputAsNew: Boolean = false,
    val strictImages: Boolean = true,
    val runnerIsolation: Boolean = true
) {
    init {
        require(maxInputBytes in 1..ToolFileAccess.DEFAULT_READ_LIMIT_BYTES)
        require(listOf(maxOutputBytes, maxTotalInputBytes, maxTotalOutputBytes, maxZipEntries, maxZipEntryBytes, maxZipExpandedBytes,
            maxZipRatio, maxPages, maxDimension, maxRows, maxColumns, maxCells, maxTextChars,
            maxOperations).all { it > 0 })
        require(maxPixels > 0 && maxDurationMillis in 1..300000)
    }
    companion object {
        val RUNNER = DocumentToolLimits()
    }
}

class DocumentResourceLimitException(message: String) : SecurityException(message)

/**
 * File names are opaque references until ToolFileAccess handles them. All parsers and serializers
 * receive memory-backed bytes/streams. OOXML is validated before the memory-only POI loader;
 * the dedicated process policy disables POI temporary parts and ImageIO's disk cache.
 * A Runner must supply its confined file adapter, RUNNER limits and fence. Library/server
 * tools retain their original streaming implementations and never construct this adapter.
 */
class DocumentToolIO(
    private val files: ToolFileAccess,
    val limits: DocumentToolLimits = DocumentToolLimits.RUNNER,
    private val authorityGuard: () -> Unit = {},
    private val pdfFontGuard: () -> Unit = DocumentMemoryFonts::checkInstalled,
    private val runtimeGuard: () -> Unit = DocumentMemoryRuntime::checkInstalled
) {
    private data class Budget(val started: Long = System.nanoTime(), var operations: Int = 0, var inputBytes: Long = 0, var expandedBytes: Long = 0, var outputBytes: Long = 0, var xmlTextChars: Long = 0, var generatedTextChars: Long = 0)
    private val budget = ThreadLocal<Budget>()

    fun begin(vararg arguments: Any?) {
        budget.set(Budget())
        check()
        var chars = 0L
        var items = 0
        fun inspect(value: Any?, depth: Int) {
            if (depth > 16) limit("Document arguments are too deeply nested")
            when (value) {
                is String -> { chars += value.length; if (chars > limits.maxTextChars) limit("Document text limit exceeded") }
                is Collection<*> -> {
                    if (value.size > limits.maxCells) limit("Document collection limit exceeded")
                    value.forEach { if (++items > limits.maxCells) limit("Document collection limit exceeded"); inspect(it, depth + 1) }
                }
                is Map<*, *> -> value.forEach { (k, v) ->
                    if (++items > limits.maxCells) limit("Document collection limit exceeded")
                    inspect(k, depth + 1); inspect(v, depth + 1)
                }
                is Double -> if (!value.isFinite()) limit("Non-finite document dimension")
                is Float -> if (!value.isFinite()) limit("Non-finite document dimension")
            }
        }
        arguments.forEach { inspect(it, 0) }
    }

    fun check() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Document operation interrupted")
        try { authorityGuard() } catch (failure: Throwable) {
            rethrowControlFailure(failure)
            throw SecurityException("Document execution authority was revoked", failure)
        }
        if (limits.runnerIsolation) runtimeGuard()
        val b = budget.get() ?: Budget().also { budget.set(it) }
        if (++b.operations > limits.maxOperations ||
            (System.nanoTime() - b.started) / 1_000_000 > limits.maxDurationMillis) limit("Document work budget exceeded")
    }

    fun read(path: String): ByteArray {
        check()
        val bytes = files.readBytes(path, limits.maxInputBytes)
        if (bytes.size > limits.maxInputBytes) limit("Document input exceeds limit")
        val b = budget.get()
        b.inputBytes += bytes.size
        if (b.inputBytes > limits.maxTotalInputBytes) limit("Document aggregate input limit exceeded")
        check()
        return bytes
    }
    fun input(path: String): ByteArrayInputStream = read(path).inputStream()
    fun optionalInput(path: String?): ByteArrayInputStream? {
        check()
        if (path == null) return null
        return try { input(path) } catch (e: FileNotFoundException) {
            if (!limits.allowMissingInputAsNew) throw e else null
        } catch (e: java.nio.file.NoSuchFileException) {
            if (!limits.allowMissingInputAsNew) throw e else null
        }
    }

    fun write(path: String, writer: (OutputStream) -> Unit) {
        check()
        val out = boundedOutput()
        writer(out)
        check()
        val b = budget.get()
        b.outputBytes += out.size()
        if (b.outputBytes > limits.maxTotalOutputBytes) limit("Document aggregate output limit exceeded")
        files.writeBytes(path, out.toByteArray())
        check()
    }
    private fun boundedOutput(max: Int = limits.maxOutputBytes) = object : ByteArrayOutputStream() {
        override fun write(b: Int) { check(); if (count >= max) limit("Document output exceeds limit"); super.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(); if (len < 0 || count.toLong() + len > max) limit("Document output exceeds limit")
            super.write(b, off, len)
        }
    }

    private fun closeFailed(error: Throwable, close: () -> Unit): Nothing {
        try { close() } catch (closing: Throwable) { error.addSuppressed(closing) }
        throw error
    }
    private fun packageFrom(input: InputStream): OPCPackage {
        check()
        val bytes = input.readNBytes(limits.maxInputBytes + 1)
        // Validate expansion/XML/relationships before POI allocates its package. The public
        // ZipEntrySource overload is READ only; the InputStream overload is READ_WRITE.
        // Its memory-only settings are pinned by the dedicated Runner process and checked
        // here even in fixture tests which inject a no-op font/runtime guard.
        MemoryZipSource(bytes).use { check() }
        DocumentMemoryRuntime.checkPoiMemoryPolicy()
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(): Int { check(); DocumentMemoryRuntime.checkPoiMemoryPolicy(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                check(); DocumentMemoryRuntime.checkPoiMemoryPolicy(); return super.read(b, off, len)
            }
        }
        return source.use { OPCPackage.open(it) }
    }
    fun word(input: InputStream): XWPFDocument = packageFrom(input).let { pkg ->
        try { XWPFDocument(pkg).also { checkWord(it) } } catch (e: Throwable) { closeFailed(e) { pkg.revert() } }
    }
    fun xlsx(input: InputStream): XSSFWorkbook = packageFrom(input).let { pkg ->
        try { XSSFWorkbook(pkg).also { checkWorkbook(it) } } catch (e: Throwable) { closeFailed(e) { pkg.revert() } }
    }
    fun workbook(input: InputStream): Workbook {
        val bytes = input.readNBytes(limits.maxInputBytes + 1)
        if (bytes.size > limits.maxInputBytes) limit("Document input exceeds limit")
        return if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) xlsx(bytes.inputStream())
        else HSSFWorkbook(bytes.inputStream()).also { wb ->
            try { checkWorkbook(wb) } catch (e: Throwable) { closeFailed(e) { wb.close() } }
        }
    }
    fun presentation(input: InputStream): XMLSlideShow = packageFrom(input).let { pkg ->
        try { XMLSlideShow(pkg).also { checkPresentation(it) } } catch (e: Throwable) { closeFailed(e) { pkg.revert() } }
    }
    fun pdf(path: String, password: String = ""): PDDocument = pdfBytes(read(path), password)
    fun pdfBytes(bytes: ByteArray, password: String = ""): PDDocument {
        pdfFonts()
        check()
        if (bytes.size > limits.maxInputBytes) limit("PDF input exceeds limit")
        // PDFBox byte-array loading uses an in-memory source and stream cache.
        return Loader.loadPDF(bytes, password, null, null) { pdfStreamCache() }.also { doc ->
            try { checkPdf(doc); PoiSecurity.sanitizePdfDocument(doc) } catch (e: Throwable) { closeFailed(e) { doc.close() } }
        }
    }
    fun newPdf(): PDDocument { check(); return PDDocument { pdfStreamCache() } }
    private fun pdfStreamCache(): org.apache.pdfbox.io.RandomAccessStreamCache {
        val scratch = org.apache.pdfbox.io.ScratchFile.getMainMemoryOnlyInstance(limits.maxZipExpandedBytes.toLong())
        return object : org.apache.pdfbox.io.RandomAccessStreamCache {
            override fun createBuffer(): org.apache.pdfbox.io.RandomAccess {
                check()
                val buffer = scratch.createBuffer()
                return object : org.apache.pdfbox.io.RandomAccess by buffer {
                    private fun boundedWrite(block: () -> Unit) {
                        check()
                        try { block() } catch (e: IOException) { throw DocumentResourceLimitException("PDF memory stream limit exceeded") }
                    }
                    override fun write(b: Int) = boundedWrite { buffer.write(b) }
                    override fun write(b: ByteArray) = boundedWrite { buffer.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) = boundedWrite { buffer.write(b, off, len) }
                }
            }
            override fun close() = scratch.close()
        }
    }
    fun savePdf(doc: PDDocument, path: String) { checkPdf(doc); write(path) { doc.save(it) } }
    fun serializePdf(doc: PDDocument): ByteArray {
        checkPdf(doc); val out = boundedOutput(limits.maxInputBytes); doc.save(out); return out.toByteArray()
    }
    fun saveWord(doc: XWPFDocument, path: String) { checkWord(doc); write(path) { doc.write(it) } }
    fun saveWorkbook(doc: Workbook, path: String) { checkWorkbook(doc); write(path) { doc.write(it) } }
    fun savePresentation(doc: XMLSlideShow, path: String) { checkPresentation(doc); write(path) { doc.write(it) } }

    fun pdfFonts() { check(); if (limits.runnerIsolation) pdfFontGuard() }
    fun standardFont(name: org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName): org.apache.pdfbox.pdmodel.font.PDType1Font {
        pdfFonts()
        return org.apache.pdfbox.pdmodel.font.PDType1Font(name)
    }
    fun requireSystemFontRendering() {
        check()
        if (limits.runnerIsolation) throw SecurityException("This document operation requires an isolated host font renderer; Runner support is not enabled")
    }
    fun rethrowFailure(error: Throwable) {
        if (limits.runnerIsolation || error is DocumentResourceLimitException) rethrowControlFailure(error)
        var cause: Throwable? = error
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (cause != null && seen.add(cause)) {
            if (cause is CancellationException || cause is Error) throw cause
            cause = cause.cause
        }
    }

    fun pageCount(count: Int) { check(); if (count !in 0..limits.maxPages) limit("Document page limit exceeded") }
    fun rowIndex(index: Int): Int { row(index); return index }
    fun columnIndex(index: Int): Int { column(index); return index }
    fun newSlide(ppt: XMLSlideShow): org.apache.poi.xslf.usermodel.XSLFSlide { pageCount(ppt.slides.size + 1); return ppt.createSlide() }
    fun row(index: Int) { check(); if (index !in 0 until limits.maxRows) limit("Document row limit exceeded") }
    fun column(index: Int) { check(); if (index !in 0 until limits.maxColumns) limit("Document column limit exceeded") }
    fun rowSpan(start: Int, count: Int) { row(start); span(start, count, limits.maxRows) }
    fun columnSpan(start: Int, count: Int) { column(start); span(start, count, limits.maxColumns) }
    private fun span(start: Int, count: Int, upperExclusive: Int) {
        check()
        if (count <= 0 || start.toLong() + count > upperExclusive) limit("Document range exceeds limit")
    }
    /** Reject before allocating a result proportional to repeated/shared input text. */
    inner class BoundedText internal constructor(private val maximum: Int) : Appendable {
        private val text = StringBuilder()
        override fun append(value: CharSequence?): BoundedText {
            val s = value ?: "null"
            return append(s, 0, s.length)
        }
        override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): BoundedText {
            check()
            val s = value ?: "null"
            require(startIndex in 0..endIndex && endIndex <= s.length)
            if (endIndex.toLong() - startIndex + text.length > maximum) limit("Document text output limit exceeded")
            countGeneratedText(endIndex - startIndex)
            text.append(s, startIndex, endIndex)
            return this
        }
        override fun append(value: Char): BoundedText {
            check()
            if (text.length >= maximum) limit("Document text output limit exceeded")
            countGeneratedText(1)
            text.append(value)
            return this
        }
        fun appendLine(value: CharSequence? = ""): BoundedText = append(value).append('\n')
        override fun toString() = text.toString()
    }
    fun textBuilder(maximum: Int = limits.maxTextChars): BoundedText = BoundedText(maximum.coerceIn(0, limits.maxTextChars))
    fun buildText(block: BoundedText.() -> Unit): String = textBuilder().apply(block).toString()
    fun replaceText(value: String, oldValue: String, newValue: String): String = buildText {
        if (oldValue.isEmpty()) {
            append(newValue)
            for (char in value) { append(char); append(newValue) }
        } else {
            var cursor = 0
            while (true) {
                check()
                val next = value.indexOf(oldValue, cursor)
                if (next < 0) break
                append(value, cursor, next).append(newValue)
                cursor = next + oldValue.length
            }
            append(value, cursor, value.length)
        }
    }
    private fun countGeneratedText(length: Int) {
        val b = budget.get()
        if (b.generatedTextChars + length > limits.maxTextChars) limit("Document aggregate text output limit exceeded")
        b.generatedTextChars += length
    }
    fun table(rows: Int, columns: Int) {
        check()
        if (rows !in 0..limits.maxRows || columns !in 0..limits.maxColumns ||
            rows.toLong() * columns > limits.maxCells) limit("Document table limit exceeded")
    }
    fun pixels(width: Double, height: Double) {
        check()
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 ||
            width > limits.maxDimension || height > limits.maxDimension ||
            ceil(width) * ceil(height) > limits.maxPixels) limit("Document image dimensions exceed limit")
    }
    fun checkPdf(doc: PDDocument) {
        pageCount(doc.numberOfPages)
        doc.pages.forEach { page -> check(); if (!page.mediaBox.width.isFinite() || !page.mediaBox.height.isFinite()) limit("Invalid PDF page size") }
    }
    fun pdfRender(page: PDPage, dpi: Float) {
        if (!dpi.isFinite() || dpi <= 0 || dpi > 600) limit("PDF resolution exceeds limit")
        pixels(page.cropBox.width.toDouble() * dpi / 72, page.cropBox.height.toDouble() * dpi / 72)
        val visited = Collections.newSetFromMap(IdentityHashMap<org.apache.pdfbox.cos.COSDictionary, Boolean>())
        fun inspect(resources: org.apache.pdfbox.pdmodel.PDResources?, depth: Int) {
            if (resources == null) return
            if (depth > 16) limit("PDF resource nesting exceeds limit")
            if (!visited.add(resources.cosObject)) return
            for (name in resources.xObjectNames) {
                check()
                when (val obj = resources.getXObject(name)) {
                    is org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject -> pixels(obj.width.toDouble(), obj.height.toDouble())
                    is org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject -> inspect(obj.resources, depth + 1)
                }
            }
        }
        inspect(page.resources, 0)
    }
    fun checkWord(doc: XWPFDocument) {
        check()
        if (doc.paragraphs.size > limits.maxRows) limit("Word paragraph limit exceeded")
        var cells = 0L
        doc.tables.forEach { t -> table(t.numberOfRows, t.rows.maxOfOrNull { it.tableCells.size } ?: 0); cells += t.rows.sumOf { it.tableCells.size.toLong() } }
        if (cells > limits.maxCells) limit("Word table limit exceeded")
    }
    fun checkWorkbook(wb: Workbook) {
        pageCount(wb.numberOfSheets)
        var cells = 0L
        for (sheet in wb) {
            check(); if (sheet.physicalNumberOfRows > 0) row(sheet.lastRowNum)
            for (r in sheet) {
                check(); if (r.lastCellNum > 0) column(r.lastCellNum.toInt() - 1)
                cells += r.physicalNumberOfCells
                if (cells > limits.maxCells) limit("Workbook cell limit exceeded")
            }
        }
    }
    fun checkPresentation(ppt: XMLSlideShow) {
        pageCount(ppt.slides.size)
        ppt.slides.forEach { check(); if (it.shapes.size > limits.maxCells) limit("Slide shape limit exceeded") }
        if (limits.strictImages) ppt.pictureData.forEach { imageBytes(it.data, "embedded") }
    }
    fun pdfText(doc: PDDocument, requested: Int): String {
        pdfFonts()
        checkPdf(doc)
        val max = requested.coerceIn(0, limits.maxTextChars)
        val text = StringBuilder()
        val writer = object : Writer() {
            override fun write(c: CharArray, off: Int, len: Int) { check(); val n = minOf(len, max - text.length); if (n > 0) text.append(c, off, n) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        PDFTextStripper().writeText(doc, writer)
        return text.toString()
    }

    data class ImageBytes(val bytes: ByteArray, val name: String) {
        val extension: String get() = name.substringAfterLast('.', "")
        fun readBytes() = bytes
        fun inputStream() = bytes.inputStream()
    }
    fun image(path: String) = imageBytes(read(path), filename(path))
    private fun imageBytes(bytes: ByteArray, name: String): ImageBytes {
        check()
        MemoryCacheImageInputStream(bytes.inputStream()).use { source ->
            val readers = ImageIO.getImageReaders(source)
            if (!readers.hasNext()) {
                if (limits.strictImages) limit("Unsupported document image format")
            } else {
                val reader = readers.next()
                try {
                    reader.input = source
                    pixels(reader.getWidth(0).toDouble(), reader.getHeight(0).toDouble())
                } finally { reader.dispose() }
            }
        }
        return ImageBytes(bytes, name)
    }
    fun writeImage(image: BufferedImage, format: String, path: String) {
        pixels(image.width.toDouble(), image.height.toDouble())
        write(path) { out ->
            MemoryCacheImageOutputStream(out).use { imageOut ->
                if (!ImageIO.write(image, format, imageOut)) throw IllegalArgumentException("Unsupported image format")
            }
        }
    }
    fun filename(path: String): String = path.replace('\\', '/').substringAfterLast('/')
    fun child(directory: String, name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        return directory.trimEnd('/', '\\') + "/" + name
    }

    private inner class MemoryZipSource(bytes: ByteArray) : ZipEntrySource {
        private val entries = linkedMapOf<String, Pair<ZipArchiveEntry, ByteArray>>()
        private var closed = false
        init {
            if (bytes.size > limits.maxInputBytes) limit("Office input exceeds limit")
            var total = 0L
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    check()
                    val entry = zip.nextEntry ?: break
                    if (entries.size >= limits.maxZipEntries) limit("Office ZIP entry limit exceeded")
                    val name = entry.name
                    if (name.startsWith('/') || '\\' in name || name.split('/').any { it == ".." } || entries.containsKey(name)) limit("Invalid Office ZIP entry")
                    val out = boundedOutput(limits.maxZipEntryBytes)
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(); val n = zip.read(buffer); if (n < 0) break
                        total += n
                        val b = budget.get()
                        b.expandedBytes += n
                        if (b.expandedBytes > limits.maxZipExpandedBytes) limit("Office aggregate ZIP expansion limit exceeded")
                        if (total > limits.maxZipExpandedBytes) limit("Office ZIP expansion limit exceeded")
                        out.write(buffer, 0, n)
                    }
                    val data = out.toByteArray()
                    if (data.size > 1_000_000 && entry.compressedSize > 0 && data.size.toLong() > entry.compressedSize * limits.maxZipRatio) limit("Office ZIP compression ratio exceeded")
                    if (name.endsWith(".xml") || name.endsWith(".rels")) validateXml(data)
                    entries[name] = ZipArchiveEntry(name).apply { size = data.size.toLong() } to data
                }
            }
            if (total > 1_000_000 && total > bytes.size.toLong() * limits.maxZipRatio) limit("Office ZIP compression ratio exceeded")
        }
        override fun getEntries() = Collections.enumeration(entries.values.map { it.first })
        override fun getEntry(path: String): ZipArchiveEntry? = entries[path]?.first
        override fun getInputStream(entry: ZipArchiveEntry): InputStream {
            check(); check(!closed) { "Office package is closed" }
            return entries[entry.name]?.second?.inputStream() ?: throw FileNotFoundException("Office entry missing")
        }
        override fun close() { closed = true; entries.clear() }
        override fun isClosed() = closed
    }
    private fun validateXml(bytes: ByteArray) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        var depth = 0
        var elements = 0
        factory.newSAXParser().parse(bytes.inputStream(), object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String?, attrs: Attributes) {
                check()
                if (++depth > 128 || ++elements > limits.maxCells * 20L || attrs.length > 128) limit("Office XML complexity limit exceeded")
                for (index in 0 until attrs.length) countXmlText(attrs.getValue(index).length)
                if (attrs.getValue("TargetMode") == "External" && !attrs.getValue("Type").orEmpty().endsWith("/hyperlink")) limit("External Office resource is not allowed")
            }
            override fun characters(chars: CharArray, start: Int, length: Int) { countXmlText(length) }
            override fun ignorableWhitespace(chars: CharArray, start: Int, length: Int) { countXmlText(length) }
            override fun endElement(uri: String?, local: String?, qName: String?) { depth-- }
        })
    }

    private fun countXmlText(length: Int) {
        check()
        val b = budget.get()
        b.xmlTextChars += length
        if (b.xmlTextChars > limits.maxTextChars) limit("Office XML text limit exceeded")
    }

    companion object {
        private fun limit(message: String): Nothing = throw DocumentResourceLimitException(message)
        fun rethrowControlFailure(error: Throwable) {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            while (cause != null && seen.add(cause)) {
                if (cause is CancellationException || cause is SecurityException || cause is Error) throw cause
                cause = cause.cause
            }
        }
    }
}
