package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.copyTo
import kotlin.use

// ---- Internal helper abstractions for iWork package handling (.pages / .key / .numbers) ----
private sealed interface IWorkPackageSource : Closeable {
    fun listEntries(): List<String> // always use forward slashes '/'
    fun openEntry(pathInPackage: String): InputStream?
    fun exists(pathInPackage: String): Boolean = listEntries().any { it.equals(pathInPackage, ignoreCase = true) }
}

private fun isSafeIWorkEntryName(name: String): Boolean {
    if (name.isBlank()) return false
    val normalized = name.replace('\\', '/')
    if (normalized.startsWith('/')) return false
    if (normalized.length >= 2 && normalized[1] == ':') return false
    return normalized.split('/').none { it == ".." }
}

private class DirectoryPackageSource(private val root: File) : IWorkPackageSource {
    private val canonicalRoot = root.canonicalFile
    private val entries: List<String> by lazy {
        val base = canonicalRoot.toPath()
        Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { base.relativize(it).toString().replace('\\', '/') }
                .filter { isSafeIWorkEntryName(it) }
                .sorted().toList()
        }
    }

    override fun listEntries(): List<String> = entries
    override fun openEntry(pathInPackage: String): InputStream? {
        if (!isSafeIWorkEntryName(pathInPackage)) return null
        val target = File(canonicalRoot, pathInPackage.replace('/', File.separatorChar)).canonicalFile
        val rootPath = canonicalRoot.toPath()
        val targetPath = target.toPath()
        if (targetPath != rootPath && !targetPath.startsWith(rootPath)) return null
        return if (target.exists() && target.isFile) FileInputStream(target) else null
    }

    override fun close() { /* nothing */
    }
}

private class ZipPackageSource(private val zipFile: ZipFile) : IWorkPackageSource {
    // Filter the zip's own entries so downstream code never sees a traversal-style path.
    // iWork packages legitimately use forward-slash relative paths like "Data/image.png";
    // anything containing `..` segments (or backslash-separated equivalents) is rejected
    // as a defense-in-depth guard in case a caller later extracts to disk.
    private val entriesIndex: Map<String, ZipEntry> by lazy {
        zipFile.entries().asSequence()
            .filter { isSafeIWorkEntryName(it.name) }
            .associateBy { it.name }
    }
    private val entries: List<String> by lazy { entriesIndex.keys.map { it.replace('\\', '/') }.sorted() }
    override fun listEntries(): List<String> = entries
    override fun openEntry(pathInPackage: String): InputStream? {
        if (!isSafeIWorkEntryName(pathInPackage)) return null
        val key = entriesIndex.keys.firstOrNull { it.equals(pathInPackage, ignoreCase = true) } ?: return null
        val entry = entriesIndex[key] ?: return null
        return zipFile.getInputStream(entry)
    }

    override fun close() {
        zipFile.close()
    }

}

private fun openIWorkPackage(path: String): IWorkPackageSource {
    val f = ToolPathSecurity.validateInputPath(path)
    if (!f.exists()) throw FileNotFoundException("Path does not exist: $path")
    return if (f.isDirectory) {
        DirectoryPackageSource(f)
    } else {
        try {
            ZipPackageSource(ZipFile(f))
        } catch (e: Throwable) {
            // Some .pages exported as flat files may still be packages on macOS; treat as directory if possible
            throw IllegalArgumentException("Not a directory or readable ZIP iWork package: $path (${e.message})")
        }
    }
}

private fun detectIWorkTypeByName(path: String): String {
    val name = path.lowercase(Locale.getDefault())
    return when {
        name.endsWith(".pages") -> "pages"
        name.endsWith(".key") || name.endsWith(".keynote") -> "keynote"
        name.endsWith(".numbers") -> "numbers"
        else -> "unknown"
    }
}

@LLMDescription("Generic tools for Apple iWork documents (.pages, .key, .numbers). Work with both package folders and zipped packages. QuickLook preview is leveraged for text/image extraction.")
object IWorkTools : ToolSet {

    init { PoiSecurity.ensureHardened() }

    private fun safeOutputFile(outputPath: String): File =
        ToolPathSecurity.validateOutputPath(outputPath)

    private fun safeOutputDirectory(outputDirectory: String): File {
        val marker = ToolPathSecurity.validateOutputPath(File(outputDirectory, ".braidrun-agent-dir-check").path)
        return marker.parentFile
    }

    @Tool
    @LLMDescription("Detect the iWork document type by extension: pages/keynote/numbers/unknown")
    fun detectType(
        @LLMDescription("Path to the .pages/.key/.numbers file or package directory") iworkPath: String
    ): String {
        return detectIWorkTypeByName(iworkPath)
    }

    @Tool
    @LLMDescription("List contents (relative paths) in the iWork package; returns up to maxEntries entries")
    fun listPackageContents(
        @LLMDescription("Path to the iWork document") iworkPath: String,
        @LLMDescription("Max entries to list (default 300)") maxEntries: Int? = 300
    ): String {
        return try {
            openIWorkPackage(iworkPath).use { pkg ->
                val all = pkg.listEntries()
                val n = maxEntries ?: 300
                val body = all.take(n).joinToString("\n")
                "Found ${all.size} entries. Showing ${body.lines().size} entries:\n$body"
            }
        } catch (e: Throwable) {
            "Error listing package: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get basic info: type, presence of QuickLook previews (Preview.pdf/thumbnail), existence of index.zip/index.apxl, DocumentIdentifier, metadata (if plist found)")
    fun getBasicInfo(
        @LLMDescription("Path to the iWork document") iworkPath: String
    ): String {
        return try {
            val type = detectIWorkTypeByName(iworkPath)
            openIWorkPackage(iworkPath).use { pkg ->
                val entries = pkg.listEntries()
                fun hasAny(vararg names: String): Boolean = entries.any { e ->
                    names.any { n ->
                        e.equals(n, true) || e.endsWith(
                            "/" + n,
                            true
                        ) || e.contains("/" + n, true)
                    }
                }

                val hasQuickLookPdf = entries.any { it.contains("QuickLook/", true) && it.endsWith(".pdf", true) }
                val hasQuickLookImage = entries.any {
                    it.contains("QuickLook/", true) && (it.endsWith(".jpg", true) || it.endsWith(
                        ".jpeg",
                        true
                    ) || it.endsWith(".png", true))
                }
                val hasIndexZip = hasAny("Index.zip", "index.zip")
                val hasApxl = entries.any { it.endsWith(".apxl", true) }
                val hasDocId = hasAny("Metadata/DocumentIdentifier")
                val metaPlist = entries.firstOrNull { it.endsWith(".plist", true) && it.contains("Metadata/", true) }
                val metaSummary = if (metaPlist != null) {
                    try {
                        val ins = pkg.openEntry(metaPlist)
                        if (ins != null) {
                            ins.use {
                                val obj = PropertyListParser.parse(it)
                                if (obj is NSDictionary) {
                                    val keys = obj.allKeys().toList().take(12).joinToString(", ")
                                    "plist keys: $keys"
                                } else "plist parsed: ${obj.javaClass.simpleName}"
                            }
                        } else {
                            "plist listed but could not be opened"
                        }
                    } catch (e: Exception) {
                        "plist present but parse failed: ${e.message}"
                    }
                } else "no plist in Metadata"
                buildString {
                    appendLine("File: $iworkPath")
                    appendLine("Type: $type")
                    appendLine("Entries: ${entries.size}")
                    appendLine("QuickLook PDF: $hasQuickLookPdf")
                    appendLine("QuickLook Image: $hasQuickLookImage")
                    appendLine("Has Index.zip: $hasIndexZip, Has .apxl (legacy): $hasApxl")
                    appendLine("Has DocumentIdentifier: $hasDocId")
                    appendLine("Metadata summary: $metaSummary")
                }
            }
        } catch (e: Throwable) {
            "Error reading info: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Extract QuickLook/Preview.pdf (if present) to outputPath")
    fun extractQuickLookPreviewPDF(
        @LLMDescription("Path to the iWork document") iworkPath: String,
        @LLMDescription("Destination PDF path") outputPath: String
    ): String {
        return try {
            openIWorkPackage(iworkPath).use { pkg ->
                val candidate =
                    pkg.listEntries().firstOrNull { it.contains("QuickLook/", true) && it.endsWith(".pdf", true) }
                        ?: return "No QuickLook PDF found in: $iworkPath"
                val ins = pkg.openEntry(candidate)
                    ?: return "QuickLook PDF entry found but could not be opened: $candidate"
                val outFile = safeOutputFile(outputPath)
                ins.use { it -> FileOutputStream(outFile).use { outs -> it.copyTo(outs) } }
                "Extracted QuickLook PDF to: $outputPath (source: $candidate)"
            }
        } catch (e: Throwable) {
            "Error extracting QuickLook PDF: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Extract all QuickLook preview images (jpg/png) into a directory; returns list of files written")
    fun extractQuickLookImages(
        @LLMDescription("Path to the iWork document") iworkPath: String,
        @LLMDescription("Destination directory (will be created)") outputDir: String
    ): String {
        return try {
            val dir = safeOutputDirectory(outputDir)
            val written = mutableListOf<String>()
            openIWorkPackage(iworkPath).use { pkg ->
                for (e in pkg.listEntries()) {
                    if (e.contains("QuickLook/", true) && (e.endsWith(".jpg", true) || e.endsWith(
                            ".jpeg",
                            true
                        ) || e.endsWith(".png", true))
                    ) {
                        val outFile = safeOutputFile(File(dir, File(e).name).path)
                        pkg.openEntry(e)?.use { ins -> FileOutputStream(outFile).use { outs -> ins.copyTo(outs) } }
                        written += outFile.absolutePath
                    }
                }
            }
            if (written.isEmpty()) "No QuickLook images found." else "Wrote ${written.size} images:\n" + written.joinToString(
                "\n"
            )
        } catch (e: Throwable) {
            "Error extracting images: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Try to read text from a QuickLook Preview.pdf inside the iWork document; returns up to maxChars characters")
    fun readTextViaQuickLook(
        @LLMDescription("Path to the iWork document") iworkPath: String,
        @LLMDescription("Max characters to return (default 20_000)") maxChars: Int? = 20000
    ): String {
        return try {
            openIWorkPackage(iworkPath).use { pkg ->
                val candidate =
                    pkg.listEntries().firstOrNull { it.contains("QuickLook/", true) && it.endsWith(".pdf", true) }
                        ?: return "No QuickLook PDF available; cannot extract text."
                // Phase 11: SecureTempFile + deleteOnExit safety net. The preview PDF can
                // contain PII (passport scans inside a Pages doc, etc); 0600 perms keep
                // it private. deleteOnExit covers the rare case where the JVM crashes
                // between PDF load and `tmp.delete()` — without it, a workflow that ran
                // 100k iWork files would leave 100k stale 1 MB PDFs in /tmp.
                val tmp = com.fartech.agents.commons.SecureTempFile.create("iwork_preview_", ".pdf")
                tmp.deleteOnExit()
                val ins = pkg.openEntry(candidate)
                    ?: return "QuickLook PDF entry found but could not be opened: $candidate"
                ins.use { it -> FileOutputStream(tmp).use { outs -> it.copyTo(outs) } }
                val text = try {
                    Loader.loadPDF(tmp).use { doc -> PDFTextStripper().getText(doc) }
                } finally {
                    tmp.delete()
                }
                val out = text.take(maxChars ?: 20000)
                "Extracted text from QuickLook PDF (${text.length} chars, returning ${out.length}).\n" + out
            }
        } catch (e: Throwable) {
            "Error reading text via QuickLook: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Extract an arbitrary internal file by its relative path inside the package (use listPackageContents to discover)")
    fun extractInternalFile(
        @LLMDescription("Path to the iWork document") iworkPath: String,
        @LLMDescription("Internal relative path, e.g., 'Metadata/DocumentIdentifier' or 'Index.zip'") internalPath: String,
        @LLMDescription("Destination output path") outputPath: String
    ): String {
        return try {
            openIWorkPackage(iworkPath).use { pkg ->
                val entry = pkg.listEntries()
                    .firstOrNull { it.equals(internalPath, true) || it.endsWith("/" + internalPath, true) }
                    ?: return "Internal path not found: $internalPath"
                val ins = pkg.openEntry(entry)
                    ?: return "Internal entry found but could not be opened: $entry"
                val outFile = safeOutputFile(outputPath)
                ins.use { it -> FileOutputStream(outFile).use { outs -> it.copyTo(outs) } }
                "Extracted '$entry' to: $outputPath"
            }
        } catch (e: Throwable) {
            "Error extracting internal file: ${e.message}"
        }
    }

    // ========================================================================
    // Format-scoped helpers (merged from the former ApplePagesTools /
    // AppleKeynoteTools / AppleNumbersTools in Phase 6 of the 2026-04 audit).
    //
    // These are deliberate name-duplicates of `getBasicInfo` /
    // `extractQuickLookPreviewPDF` / `extractQuickLookImages` / `readTextViaQuickLook`
    // above — they give the LLM explicit format-specific signals when choosing
    // a tool (e.g. `pagesInfo` vs the generic `getBasicInfo`).
    // ========================================================================

    @Tool
    @LLMDescription("Get basic info for a .pages file")
    fun pagesInfo(@LLMDescription(".pages path or package dir") path: String) = getBasicInfo(path)

    @Tool
    @LLMDescription("Extract QuickLook Preview.pdf from a .pages file")
    fun pagesExtractPreviewPdf(
        @LLMDescription(".pages path") path: String,
        @LLMDescription("Destination pdf") outputPdf: String
    ) = extractQuickLookPreviewPDF(path, outputPdf)

    @Tool
    @LLMDescription("Extract QuickLook images from a .pages file")
    fun pagesExtractImages(
        @LLMDescription(".pages path") path: String,
        @LLMDescription("Destination dir") outputDir: String
    ) = extractQuickLookImages(path, outputDir)

    @Tool
    @LLMDescription("Read text via QuickLook Preview.pdf from a .pages file (if present)")
    fun pagesReadText(
        @LLMDescription(".pages path") path: String,
        @LLMDescription("Max chars") maxChars: Int? = 20000
    ) = readTextViaQuickLook(path, maxChars)

    @Tool
    @LLMDescription("Get basic info for a .key file")
    fun keynoteInfo(@LLMDescription(".key path or package dir") path: String) = getBasicInfo(path)

    @Tool
    @LLMDescription("Extract QuickLook Preview.pdf from a .key file")
    fun keynoteExtractPreviewPdf(
        @LLMDescription(".key path") path: String,
        @LLMDescription("Destination pdf") outputPdf: String
    ) = extractQuickLookPreviewPDF(path, outputPdf)

    @Tool
    @LLMDescription("Extract QuickLook images from a .key file")
    fun keynoteExtractImages(
        @LLMDescription(".key path") path: String,
        @LLMDescription("Destination dir") outputDir: String
    ) = extractQuickLookImages(path, outputDir)

    @Tool
    @LLMDescription("Read text via QuickLook Preview.pdf from a .key file (if present)")
    fun keynoteReadText(
        @LLMDescription(".key path") path: String,
        @LLMDescription("Max chars") maxChars: Int? = 20000
    ) = readTextViaQuickLook(path, maxChars)

    @Tool
    @LLMDescription("Get basic info for a .numbers file")
    fun numbersInfo(@LLMDescription(".numbers path or package dir") path: String) = getBasicInfo(path)

    @Tool
    @LLMDescription("Extract QuickLook Preview.pdf from a .numbers file")
    fun numbersExtractPreviewPdf(
        @LLMDescription(".numbers path") path: String,
        @LLMDescription("Destination pdf") outputPdf: String
    ) = extractQuickLookPreviewPDF(path, outputPdf)

    @Tool
    @LLMDescription("Extract QuickLook images from a .numbers file")
    fun numbersExtractImages(
        @LLMDescription(".numbers path") path: String,
        @LLMDescription("Destination dir") outputDir: String
    ) = extractQuickLookImages(path, outputDir)

    @Tool
    @LLMDescription("Read text via QuickLook Preview.pdf from a .numbers file (if present)")
    fun numbersReadText(
        @LLMDescription(".numbers path") path: String,
        @LLMDescription("Max chars") maxChars: Int? = 20000
    ) = readTextViaQuickLook(path, maxChars)
}

/*
 * Apple Pages / Keynote / Numbers helper tools were historically four separate
 * `ToolSet` objects/classes (`IWorkTools`, `ApplePagesTools`, `AppleKeynoteTools`,
 * `AppleNumbersTools`) that each re-exposed the same four underlying operations
 * with format-specific method names. In Phase 6 of the 2026-04 audit follow-up
 * they were absorbed into [IWorkTools] itself — the format-specific @Tool
 * method names (`pagesInfo`, `keynoteInfo`, `numbersInfo`, ...) survive so the
 * LLM still gets format-scoped naming signal, but the three extra ToolSet
 * classes are now `@Deprecated` empty shells (see below).
 *
 * The moved methods are at the bottom of [IWorkTools] for visibility.
 */

@Deprecated(
    message = "Merged into IWorkTools in Phase 6. Call tools(IWorkTools) instead; the pages* @Tool method names are preserved.",
    replaceWith = ReplaceWith("IWorkTools", "com.fartech.agents.tools.IWorkTools"),
    level = DeprecationLevel.WARNING
)
object ApplePagesTools : ToolSet

@Deprecated(
    message = "Merged into IWorkTools in Phase 6. Call tools(IWorkTools) instead; the keynote* @Tool method names are preserved.",
    replaceWith = ReplaceWith("IWorkTools", "com.fartech.agents.tools.IWorkTools"),
    level = DeprecationLevel.WARNING
)
class AppleKeynoteTools : ToolSet

@Deprecated(
    message = "Merged into IWorkTools in Phase 6. Call tools(IWorkTools) instead; the numbers* @Tool method names are preserved.",
    replaceWith = ReplaceWith("IWorkTools", "com.fartech.agents.tools.IWorkTools"),
    level = DeprecationLevel.WARNING
)
class AppleNumbersTools : ToolSet
