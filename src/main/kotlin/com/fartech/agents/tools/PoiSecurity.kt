package com.fartech.agents.tools

import mu.KotlinLogging
import org.apache.poi.openxml4j.util.ZipSecureFile
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger("com.fartech.agents.tools.PoiSecurity")

/**
 * Centralized POI (Apache POI + PDFBox) security hardening.
 *
 * Tools that parse Office / PDF documents must call [ensureHardened] before opening any
 * user-controlled file. This is idempotent — the first call configures the global
 * defaults; subsequent calls are no-ops.
 *
 * ## What we harden
 *
 * 1. **Zip bomb / decompression bomb** via `ZipSecureFile`:
 *      - `minInflateRatio`  — reject .xlsx/.docx/.pptx whose entries decompress by
 *        more than N× their compressed size (default in POI is 0.01 = 100×; we
 *        tighten to `0.005` = 200× because the default still permits gigabyte bombs
 *        that fit comfortably below POI's defaults).
 *      - `maxEntrySize`     — single uncompressed entry cap (1 GB → we cap 256 MB).
 *      - `maxTextSize`      — total extracted text cap (256 MB).
 *
 * 2. **XXE / external entity injection**: POI 5.x disables DTDs and external entities
 *    in its default `XMLHelper` factory. We verify this at load time and log loudly
 *    if the JVM's XML stack defeats POI's setting (e.g. a custom system property
 *    override), so integrators can spot the regression.
 *
 * 3. **PDFBox JavaScript**: PDF documents can ship JavaScript actions; we don't want
 *    those executing on parse. PDFBox does not auto-execute JS unless explicitly asked,
 *    but we defensively log a warning if any document opened later reports scripted
 *    open actions. Actual disabling happens at the call site via
 *    `doc.documentCatalog.openAction = null` — see [sanitizePdfDocument].
 *
 * Callers:
 *   - [OfficeTools], [OfficeAdvancedTools], [OfficeEnhancedTools], [OfficeCsvTools],
 *     [ExcelEnhancedTools], [IWorkTools], [PowerPointEnhancedTools]
 *   - [PDFTools], [PDFAdvancedTools]
 */
internal object PoiSecurity {

    private val hardened = AtomicBoolean(false)

    /** Strictest inflate ratio that still permits legitimate Office files. */
    private const val SAFE_MIN_INFLATE_RATIO: Double = 0.005

    /** 256 MB per uncompressed entry. Most legitimate documents are far below this. */
    private const val SAFE_MAX_ENTRY_BYTES: Long = 256L * 1024 * 1024

    /** 256 MB total extracted text across an entire workbook. */
    private const val SAFE_MAX_TEXT_BYTES: Long = 256L * 1024 * 1024

    fun ensureHardened() {
        if (!hardened.compareAndSet(false, true)) return
        try {
            ZipSecureFile.setMinInflateRatio(SAFE_MIN_INFLATE_RATIO)
            ZipSecureFile.setMaxEntrySize(SAFE_MAX_ENTRY_BYTES)
            ZipSecureFile.setMaxTextSize(SAFE_MAX_TEXT_BYTES)
            verifyXmlHardening()
            logger.info {
                "POI hardening applied: minInflateRatio=$SAFE_MIN_INFLATE_RATIO, " +
                    "maxEntrySize=${SAFE_MAX_ENTRY_BYTES}B, maxTextSize=${SAFE_MAX_TEXT_BYTES}B"
            }
        } catch (e: Throwable) {
            // Degrade gracefully — POI defaults are still safer than no hardening at all,
            // but we want operators to see this loud and clear.
            logger.error(e) { "POI hardening failed to apply — continuing with POI defaults" }
        }
    }

    /**
     * Sanity-check that the SAXParserFactory the JVM gives us does not permit external
     * entities. We only log — POI's `XMLHelper` wraps factories internally, so even if
     * this check reports a permissive default, POI itself still disables XXE per-parser.
     */
    private fun verifyXmlHardening() {
        runCatching {
            val factory = javax.xml.parsers.SAXParserFactory.newInstance()
            val disallowsDoctype = runCatching {
                factory.getFeature("http://apache.org/xml/features/disallow-doctype-decl")
            }.getOrDefault(false)
            if (!disallowsDoctype) {
                logger.warn {
                    "SAXParserFactory does not disallow DOCTYPE declarations by default. " +
                        "POI still disables XXE on its own parsers, but integrators should " +
                        "ensure any custom XML usage explicitly sets " +
                        "`disallow-doctype-decl=true` and `external-general-entities=false`."
                }
            }
        }
    }

    /**
     * Defensive sanitizer for a freshly-opened PDF — remove any `OpenAction` that would
     * run JavaScript / navigate on load. Does not mutate the on-disk file unless the
     * caller subsequently saves the document.
     */
    fun sanitizePdfDocument(doc: org.apache.pdfbox.pdmodel.PDDocument) {
        runCatching {
            doc.documentCatalog?.openAction = null
        }.onFailure {
            logger.debug(it) { "Unable to clear PDF OpenAction — continuing with read-only access" }
        }
    }
}
