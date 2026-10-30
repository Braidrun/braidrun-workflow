package com.fartech.agents.tools

import org.apache.fontbox.FontBoxFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.font.*

/**
 * Explicit process-wide setup for a dedicated Runner JVM, never called by library tool defaults.
 * PDFBox only has a global FontMapper API. Install before accepting work and retain for process
 * lifetime; do not install/restore around individual requests in a shared server JVM.
 * Uses PDFBox's licensed bundled Liberation Sans bytes, with no host font scanning or disk cache.
 * This does not authorize POI/Java2D system-font operations (PPT rasterization / Excel auto sizing).
 */
object DocumentMemoryFonts {
    private var installed: FontMapper? = null

    @Synchronized
    fun installForDedicatedRunnerProcess() {
        installed?.let { checkInstalled(); return }
        val bytes = DocumentMemoryFonts::class.java.getResourceAsStream(
            "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
        )?.use { it.readNBytes(4 * 1024 * 1024 + 1) }
            ?: throw SecurityException("Bundled PDF font is unavailable")
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) throw SecurityException("Bundled PDF font exceeds limit")
        val font = TTFParser().parse(RandomAccessReadBuffer(bytes))
        val mapper = object : FontMapper {
            override fun getTrueTypeFont(name: String?, descriptor: PDFontDescriptor?): FontMapping<TrueTypeFont> =
                FontMapping(font, true)
            override fun getFontBoxFont(name: String?, descriptor: PDFontDescriptor?): FontMapping<FontBoxFont> =
                FontMapping(font, true)
            override fun getCIDFont(name: String?, descriptor: PDFontDescriptor?, info: PDCIDSystemInfo?): CIDFontMapping =
                CIDFontMapping(null, font, true)
        }
        FontMappers.set(mapper)
        installed = mapper
    }

    @Synchronized
    fun checkInstalled() {
        val expected = installed ?: throw SecurityException("Runner PDF font policy must be installed in its dedicated JVM")
        if (FontMappers.instance() !== expected) throw SecurityException("Runner PDF font policy was replaced")
    }
}
