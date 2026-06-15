package org.openapitools.vertxweb.server.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentHistory(
    val sessionId: String,
    val role: String,
    val content: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentHistoryDocumentObject(
    val createDate: Long,
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val username: String? = null,
    val agentHistory: AgentHistory? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentSnapshot(
    val agentId: String,
    val nodeId: String,
    val checkpointId: String,
    val version: Long,
    val createdAt: Long,
    val `data`: Map<String, Any?>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentSnapshotDocumentObject(
    val createDate: Long,
    val id: String,
    val agentSnapshot: AgentSnapshot,
    val username: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentChat(
    val deleteMark: Boolean? = null,
    val title: String? = null,
    val originalChatId: String? = null,
    val created: Long? = null,
    val role: Role? = null,
    val content: String? = null,
    val model: String? = null,
    val agentId: String? = null,
    val temperature: Float? = null
) {
    enum class Role(val value: String) {
        user("user"),
        assistant("assistant"),
        system("system")
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentChatDocumentObject(
    val createDate: Long,
    val id: String,
    val appId: String,
    val platform: Platform,
    val uuid: String,
    val agentChat: AgentChat,
    val username: String? = null
) {
    enum class Platform(val value: String) {
        ios("ios"),
        android("android"),
        web("web"),
        desktop("desktop")
    }
}

enum class ChatPDFPlatform(val value: String) {
    ios("ios"),
    android("android"),
    web("web"),
    desktop("desktop")
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PDFSnapshot(
    val originalChatId: String,
    val pdfPath: String,
    val pdfFileName: String,
    val pdfFileSize: Long,
    val pdfFileHash: String,
    val totalPages: Int,
    val extractedText: String,
    val extractedPages: Int,
    val extractionMethod: ExtractionMethod,
    val hasPageMarkers: Boolean,
    val characterCount: Int,
    val wordCount: Int,
    val estimatedTokens: Int,
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val producer: String? = null,
    val creator: String? = null,
    val creationDate: String? = null,
    val modificationDate: String? = null,
    val pdfVersion: String? = null,
    val pageSize: String? = null,
    val fastWebView: Boolean? = null,
    val translatedPdfUrls: Map<String, String>? = null,
    val chatTitle: String? = null,
    val summaryReturned: Boolean? = false
) {
    enum class ExtractionMethod(val value: String) {
        FULL_TEXT("FULL_TEXT"),
        PAGE_RANGE("PAGE_RANGE"),
        WITH_PAGE_MARKERS("WITH_PAGE_MARKERS"),
        SMART_EXTRACTION("SMART_EXTRACTION")
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatPDFSnapshotDocumentObject(
    val createDate: Long,
    val id: String,
    val appId: String,
    val platform: ChatPDFPlatform,
    val uuid: String,
    val chatPdfSnapshot: PDFSnapshot,
    val username: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PDFChatMessage(
    val originalChatId: String,
    val role: Role,
    val content: String,
    val isIncognito: Boolean,
    val pdfSnapshotId: String? = null,
    val quoteContent: String? = null,
    val created: Long? = null,
    val maxToken: Int? = null,
    val model: String? = null,
    val temperature: Float? = null
) {
    enum class Role(val value: String) {
        system("system"),
        user("user"),
        assistant("assistant")
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PDFChatDocumentObject(
    val createDate: Long,
    val id: String,
    val appId: String,
    val platform: Platform,
    val uuid: String,
    val pdfChat: PDFChatMessage,
    val username: String? = null
) {
    enum class Platform(val value: String) {
        ios("ios"),
        android("android"),
        web("web"),
        desktop("desktop")
    }
}
