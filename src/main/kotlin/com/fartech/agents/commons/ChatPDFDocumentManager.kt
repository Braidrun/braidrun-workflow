package com.fartech.agents.commons

import mu.KotlinLogging
import org.litote.kmongo.div
import org.litote.kmongo.eq
import org.openapitools.vertxweb.server.model.ChatPDFSnapshotDocumentObject
import org.openapitools.vertxweb.server.model.PDFSnapshot
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger("com.fartech.agents.commons.ChatPDFDocumentManager")

/**
 * 管理 ChatPDF 快照（PDF 元数据 + 提取内容）
 */
object ChatPDFDocumentManager {

    fun calculateFileHash(filePath: String): String {
        val file = File(filePath)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
        val otherChars = text.length - chineseChars
        val tokens = (chineseChars / 1.5) + (otherChars / 4.0)
        return tokens.roundToInt().coerceAtLeast(1)
    }

    fun countWords(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotBlank() }

    /**
     * Outcome of [tryFindDocument]. Callers that need to distinguish "not found" from
     * "lookup failed" should prefer this over the legacy [findDocument] that collapses
     * both into a single nullable.
     */
    sealed class LookupResult {
        data class Found(val snapshot: PDFSnapshot) : LookupResult()
        data object NotFound : LookupResult()
        data class Error(val cause: Throwable) : LookupResult()
    }

    /**
     * Explicit-outcome variant of [findDocument]. Returns [LookupResult.Error] when the
     * Mongo query itself failed (connection/auth/timeout) so the caller can distinguish
     * a genuine "document isn't stored" from an infrastructure outage.
     */
    suspend fun tryFindDocument(originalChatId: String): LookupResult {
        return try {
            val doc = withResolvedKMongo<ChatPDFSnapshotDocumentObject, ChatPDFSnapshotDocumentObject?>(
                dbName = AgentMongoTargets.IOS_SERVE_DB,
                collectionName = AgentMongoTargets.PDF_SNAPSHOT_COLLECTION
            ) {
                find(ChatPDFSnapshotDocumentObject::chatPdfSnapshot / PDFSnapshot::originalChatId eq originalChatId)
                    .firstOrNull()
            }
            val snapshot = doc?.chatPdfSnapshot
            if (snapshot == null) LookupResult.NotFound else LookupResult.Found(snapshot)
        } catch (e: Exception) {
            // Log with full stack trace so operators can diagnose; the surface API still
            // communicates the failure via LookupResult.Error.
            logger.error(e) { "ChatPDF findDocument failed for chatId='$originalChatId'" }
            logProgress(
                AnsiColor.RED, "ChatPDF",
                "❌ Failed to find document for chatId='$originalChatId': ${e::class.simpleName}: ${e.message}"
            )
            LookupResult.Error(e)
        }
    }

    /**
     * Legacy null-or-found lookup. Preserved for backwards compat; new code should call
     * [tryFindDocument] instead so infrastructure errors are not silently swallowed.
     */
    suspend fun findDocument(originalChatId: String): PDFSnapshot? = when (val r = tryFindDocument(originalChatId)) {
        is LookupResult.Found -> r.snapshot
        LookupResult.NotFound -> null
        is LookupResult.Error -> null
    }

    fun extractPageCount(metadata: String): Int =
        Regex("Total Pages:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(metadata)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

    fun extractTitle(metadata: String): String? = extractField("Title", metadata)
    fun extractAuthor(metadata: String): String? = extractField("Author", metadata)
    fun extractSubject(metadata: String): String? = extractField("Subject", metadata)
    fun extractKeywords(metadata: String): String? = extractField("Keywords", metadata)
    fun extractProducer(metadata: String): String? = extractField("Producer", metadata)
    fun extractCreator(metadata: String): String? = extractField("Creator", metadata)
    fun extractCreationDate(metadata: String): String? = extractField("Creation Date", metadata)
    fun extractModificationDate(metadata: String): String? = extractField("Modification Date", metadata)
    fun extractPdfVersion(metadata: String): String? = extractField("PDF Version", metadata)
    fun extractPageSize(metadata: String): String? = extractField("Page Size", metadata)
    fun extractFastWebView(metadata: String): Boolean? {
        val value = extractField("Fast Web View", metadata) ?: return null
        return when (value.trim().lowercase()) {
            "yes", "true" -> true
            "no", "false" -> false
            else -> null
        }
    }

    private fun extractField(label: String, metadata: String): String? {
        val value = Regex("$label:\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(metadata)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        return value?.takeUnless { it.equals("N/A", ignoreCase = true) || it.isEmpty() }
    }

}
