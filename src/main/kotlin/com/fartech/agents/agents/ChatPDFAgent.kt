package com.fartech.agents.agents

import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.fartech.agents.commons.*
import com.fartech.ftapp2.commonsKt.*
import org.litote.kmongo.and
import org.litote.kmongo.ascending
import org.litote.kmongo.div
import org.litote.kmongo.eq
import org.openapitools.vertxweb.server.model.*
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class ChatPDFAgentUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val source: String = "koog_actual"
)

private class ChatPDFAgentUsageCollector {
    private val usageFromCompletedCalls = AtomicReference<ChatPDFAgentUsage?>(null)
    private val usageFromStreamingFrames = AtomicReference<ChatPDFAgentUsage?>(null)
    private val usageFromStreamingHistory = AtomicReference<ChatPDFAgentUsage?>(null)

    fun recordFromResponses(responses: List<Message.Assistant>) {
        val promptTokens = responses.sumOf { it.metaInfo.inputTokensCount ?: 0 }
        val completionTokens = responses.sumOf { it.metaInfo.outputTokensCount ?: 0 }
        val totalTokens = responses.sumOf {
            it.metaInfo.totalTokensCount ?: ((it.metaInfo.inputTokensCount ?: 0) + (it.metaInfo.outputTokensCount ?: 0))
        }
        buildUsage(promptTokens, completionTokens, totalTokens, "koog_actual")?.let { usage ->
            usageFromCompletedCalls.updateAndGet { current -> mergeUsage(current, usage) }
        }
    }

    fun recordFromHistory(history: List<Message>) {
        if (usageFromCompletedCalls.get() != null || usageFromStreamingFrames.get() != null) return

        val usage = history.asReversed()
            .asSequence()
            .filterIsInstance<Message.Assistant>()
            .mapNotNull { response ->
                buildUsage(
                    promptTokens = response.metaInfo.inputTokensCount ?: 0,
                    completionTokens = response.metaInfo.outputTokensCount ?: 0,
                    totalTokens = response.metaInfo.totalTokensCount,
                    source = "koog_history"
                )
            }
            .firstOrNull()

        if (usage != null) {
            usageFromStreamingHistory.compareAndSet(null, usage)
        }
    }

    fun recordFromStreamFrame(frame: StreamFrame) {
        koogTokenUsageFromStreamFrame(frame)?.let { rawUsage ->
            buildUsage(
                promptTokens = rawUsage.promptTokens,
                completionTokens = rawUsage.completionTokens,
                totalTokens = rawUsage.totalTokens,
                source = rawUsage.source
            )
        }?.let { usage ->
            usageFromStreamingFrames.updateAndGet { current -> mergeUsage(current, usage) }
        }
    }

    fun finalUsage(): ChatPDFAgentUsage? =
        usageFromCompletedCalls.get() ?: usageFromStreamingFrames.get() ?: usageFromStreamingHistory.get()

    private fun buildUsage(
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int?,
        source: String
    ): ChatPDFAgentUsage? {
        val normalizedPromptTokens = promptTokens.coerceAtLeast(0)
        val normalizedCompletionTokens = completionTokens.coerceAtLeast(0)
        val normalizedTotalTokens = (totalTokens ?: (normalizedPromptTokens + normalizedCompletionTokens)).coerceAtLeast(0)
        if (normalizedPromptTokens <= 0 && normalizedCompletionTokens <= 0 && normalizedTotalTokens <= 0) {
            return null
        }
        return ChatPDFAgentUsage(
            promptTokens = normalizedPromptTokens,
            completionTokens = normalizedCompletionTokens,
            totalTokens = normalizedTotalTokens,
            source = source
        )
    }

    private fun mergeUsage(
        current: ChatPDFAgentUsage?,
        incoming: ChatPDFAgentUsage
    ): ChatPDFAgentUsage {
        if (current == null) return incoming
        return ChatPDFAgentUsage(
            promptTokens = current.promptTokens + incoming.promptTokens,
            completionTokens = current.completionTokens + incoming.completionTokens,
            totalTokens = current.totalTokens + incoming.totalTokens,
            source = incoming.source
        )
    }
}

/**
 * ChatPDF Agent - 与 PDF 文档对话的智能助手
 * 
 * 功能特性:
 * 1. 自动生成 PDF 摘要
 * 2. 支持对 PDF 内容进行问答
 * 3. 支持流式输出响应
 * 4. 支持多轮对话,保持上下文
 * 5. 智能搜索和引用 PDF 内容
 * 
 * 优化策略 (Token 节省):
 * - 使用 PDF 快照缓存机制 (一次提取，永久使用)
 * - 首次提取后保存到数据库
 * - 后续对话直接从快照读取
 * - 文件hash验证确保数据一致性
 * - 节省 80-90% token 和响应时间
 * 
 * 系统提示词优化:
 * - 包含完整 PDF 元数据（文件名、大小、页码、作者、标题等）
 * - 统一的系统提示词结构，不区分首次或后续请求
 * - 首次生成后作为 system 消息保存到聊天历史
 * - 后续请求直接从聊天历史读取，无需重新生成
 * - 系统提示词仅包含文档信息和回答原则，不包含用户引导
 * - 用户需要做的事情由 content 体现，而非系统提示词
 * 
 * 使用场景:
 * - 快速理解长篇 PDF 文档
 * - 从 PDF 中提取特定信息
 * - 对 PDF 内容进行分析和总结
 * - 基于 PDF 内容回答问题
 */
suspend fun chatPDFAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    onChunk: (String) -> Unit = {},
    onUsage: (ChatPDFAgentUsage) -> Unit = {}
) {
    // 获取会话 ID
    val sessionId = parameters.parameter("session_id", UUID.randomUUID().toString())

    // 获取用户输入
    val userInput: String = parameters.parameter("prompt", "")

    val isFirstChat = parameters.parameter("is_first_chat", false)


    // 从数据库加载历史记录
    val appId = parameters.parameter("appId", "")
    val platform = parameters.parameter("platform", "")
    val uuid = parameters.parameter("uuid", "")
    val username = parameters.parameter("username", "")
    val preloadedHistoryMessages = parameters.parameter("preloaded_history_messages", emptyList<Map<String, String>>())

    val historyMessages: List<Map<String, String>> = if (preloadedHistoryMessages.isNotEmpty()) {
        preloadedHistoryMessages
    } else if (appId.isNotEmpty() && platform.isNotEmpty() &&
        uuid.isNotEmpty() && username.isNotEmpty() && !isFirstChat
    ) {
        try {
            loadChatHistory(parameters, appId, platform, uuid, username, sessionId)
        } catch (e: Exception) {
            logProgress(
                AnsiColor.YELLOW,
                "ChatHistory",
                "⚠️ 加载历史记录失败: ${e.javaClass.simpleName} - ${e.message}"
            )
            emptyList()
        }
    } else {
        emptyList()
    }

    // 检查历史轮次是否超限
    val maxRounds = parameters.parameter("max_conversation_rounds", 100)
    val historyRounds = historyMessages.count { it["role"] == "user" }

    // 修复逻辑：当 maxRounds > 0 且 historyRounds >= maxRounds 时才限制
    if (maxRounds > 0 && historyRounds >= maxRounds) {
        val msg = parameters.parameter(
            "rounds_exceeded_message",
            "当前对话已达 $historyRounds 轮，超过限制（$maxRounds 轮）。请开启新对话。"
        )
        throw StreamChatRoundsExceededException(msg)
    }

    // 🚀 核心优化：加载或创建 PDF 快照
    val pdfSnapshotDoc = try {
        parameters.parameter<String?>("preloaded_pdf_snapshot_json", null)
            ?.takeIf { it.isNotBlank() }
            ?.let { MyUtils.mapper.readValue(it, ChatPDFSnapshotDocumentObject::class.java) }
            ?: loadPDFSnapshot(parameters, sessionId)
    } catch (e: Exception) {
        logProgress(AnsiColor.RED, "ChatPDF", "❌ 加载 PDF 快照失败: ${e.message}")
        null
    }

    if (pdfSnapshotDoc == null) {
        val msg = "无法找到对应的 PDF 快照（ID: $sessionId）。请确认文件已上传并完成处理。"
        throw IllegalArgumentException(msg)
    }

    // 构建或从历史记录中获取系统提示词
    val restoredSystemPrompt = if (isFirstChat) {
        // 首次请求，生成系统提示词
        buildChatPDFSystemPrompt(pdfSnapshotDoc.chatPdfSnapshot)
    } else {
        // 后续请求，从历史记录中获取系统提示词
        historyMessages.firstOrNull { it["role"] == "system" }?.get("content")
            ?: buildChatPDFSystemPrompt(pdfSnapshotDoc.chatPdfSnapshot) // 兜底：如果没找到就重新生成
    }

    // 历史里保存的是"完整"系统提示词(已含 ## Language 段)。去掉旧的 Language 段
    // 再追加本次请求的指令,否则每轮都会叠加一个(可能互相矛盾的)语言段。
    val languageSectionIdx = restoredSystemPrompt.lastIndexOf("\n## Language")
    val baseSystemPrompt = if (languageSectionIdx >= 0) {
        restoredSystemPrompt.substring(0, languageSectionIdx).trimEnd()
    } else {
        restoredSystemPrompt
    }

    // 处理语言优先级逻辑
    val languageInstruction = buildLanguageInstruction(userInput, parameters)
    val systemPrompt = baseSystemPrompt + "\n\n## Language\n" + languageInstruction

    if (userInput.isEmpty()) {
        return
    }

    // 保存系统提示词到历史记录
    if (isFirstChat && appId.isNotEmpty() && platform.isNotEmpty() && uuid.isNotEmpty() && username.isNotEmpty()) {
        try {
            val platformEnum =
                PDFChatDocumentObject.Platform.entries.find { it.name.equals(platform, ignoreCase = true) }
                    ?: PDFChatDocumentObject.Platform.ios // Default to ios if unknown

            saveSystemPromptToHistory(
                parameters = parameters,
                systemPrompt = systemPrompt,
                sessionId = sessionId,
                appId = appId,
                platform = platformEnum.name,
                uuid = uuid,
                username = username,
                pdfSnapshotId = pdfSnapshotDoc.id  // PDF快照ID与sessionId相同
            )
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "ChatPDF", "⚠️ 保存系统提示词到历史记录失败: ${e.message}")
            // 保存失败不影响主流程
        }
    }


    val usageCollector = ChatPDFAgentUsageCollector()
    val skillManager = createSkillManager(parameters)

    buildAndRunAgent<String, String>(
        httpAccess = httpAccess,
        parameters = parameters,
        input = userInput,
        systemPrompt = systemPrompt,
        toolRegistry = parseToolSet(
            parameters,
            httpAccess,
            tools = listOf(AgentTools.EXIT),
        ),
        skillManager = skillManager,
        installFeatures = {
            createHookAwareInstallFeatures(skillManager, sessionId).invoke(this)
            handleEvents {
                onLLMCallCompleted { eventContext ->
                    // Koog 1.0.0 — `LLMCallCompletedContext.responses` (plural)
                    // collapsed to `response` (single `Message.Assistant?`).
                    eventContext.response?.let { usageCollector.recordFromResponses(listOf(it)) }
                }
                onLLMStreamingFrameReceived { eventContext ->
                    usageCollector.recordFromStreamFrame(eventContext.streamFrame)
                }
                onLLMStreamingCompleted { eventContext ->
                    if (usageCollector.finalUsage() == null) {
                        // Koog 1.0.0 — `context.getHistory()` removed; read the
                        // session-managed prompt messages directly.
                        val history = eventContext.context.llm.readSession { prompt.messages }
                        usageCollector.recordFromHistory(history)
                    }
                }
            }
        }
    ) { _, _, _ ->
        // 过滤 system 行:其内容(含整份 PDF 文本)已经作为 agent 的 systemPrompt
        // 安装;restoreHistoryNode 再重放一次会让 PDF 全文在上下文中出现两遍,
        // 抵消快照机制"节省 80-90% token"的优化。
        buildStreamChatStrategy(historyMessages.filter { it["role"] != "system" }, sessionId, onChunk)
    }

    usageCollector.finalUsage()?.let(onUsage)
}

/**
 * 🚀 核心优化函数：加载或创建 PDF 快照
 * 
 * 流程:
 * 1. 检查数据库是否已有快照
 * 2. 如果有，验证文件hash是否匹配
 * 3. 如果匹配，直接使用缓存（节省80-90% token）
 * 4. 如果不匹配或不存在，重新提取并保存
 */
private suspend fun loadPDFSnapshot(
    parameters: List<ConfigurationParameter>,
    originalChatId: String
): ChatPDFSnapshotDocumentObject? {
    return withResolvedKMongo<ChatPDFSnapshotDocumentObject, ChatPDFSnapshotDocumentObject?>(
        parameters = parameters,
        dbName = AgentMongoTargets.IOS_SERVE_DB,
        collectionName = AgentMongoTargets.PDF_SNAPSHOT_COLLECTION
    ) {
        find(ChatPDFSnapshotDocumentObject::chatPdfSnapshot / PDFSnapshot::originalChatId eq originalChatId)
            .firstOrNull()
    }
}

/**
 * 构建 ChatPDF 系统提示词
 * 包含完整的 PDF 元数据和内容，首次生成后会缓存到数据库
 */
private fun buildChatPDFSystemPrompt(pdfSnapshot: PDFSnapshot): String {
    // 格式化文件大小
    val fileSize = formatFileSize(pdfSnapshot.pdfFileSize)

    // 构建元数据部分
    val metadataSection = buildString {
        appendLine("## 📄 文档元数据")
        appendLine()
        appendLine("- **文件名**: ${pdfSnapshot.pdfFileName}")
        appendLine("- **文件路径**: ${pdfSnapshot.pdfPath}")
        appendLine("- **文件大小**: $fileSize")
        appendLine("- **总页数**: ${pdfSnapshot.totalPages} 页")
        appendLine("- **已提取页数**: ${pdfSnapshot.extractedPages} 页")

        pdfSnapshot.title?.let { if (it.isNotBlank()) appendLine("- **标题**: $it") }
        pdfSnapshot.author?.let { if (it.isNotBlank()) appendLine("- **作者**: $it") }
        pdfSnapshot.subject?.let { if (it.isNotBlank()) appendLine("- **主题**: $it") }
        pdfSnapshot.creator?.let { if (it.isNotBlank()) appendLine("- **创建者**: $it") }

        appendLine("- **字符数**: ${formatNumber(pdfSnapshot.characterCount)}")
        appendLine("- **单词数**: ${formatNumber(pdfSnapshot.wordCount)}")
        appendLine("- **估算 Token 数**: ${formatNumber(pdfSnapshot.estimatedTokens)}")
        appendLine("- **提取方式**: ${pdfSnapshot.extractionMethod.value}")
        appendLine("- **包含页码标记**: ${if (pdfSnapshot.hasPageMarkers) "是" else "否"}")
    }

    return """
        你是 ChatPDF AI，一个专业的 PDF 文档分析助手。你正在帮助用户理解和分析以下 PDF 文档。
        
        $metadataSection
        
        ## 📖 PDF 文档完整内容
        
        以下是 PDF 文档的完整提取内容：
        
        ${pdfSnapshot.extractedText}
        
        ---
        
        **重要提示**: 
        - 你已经拥有完整的 PDF 内容（如上所示），无需使用任何工具提取内容
        - 直接基于上述内容回答用户的所有问题
        - 内容中的 `[Page X]` 标记表示页码，便于你准确引用
        
        ## 📋 回答原则
        
        1. **准确性**: 只基于 PDF 的实际内容回答，不要编造信息
        2. **引用来源**: 回答时引用具体的页码和内容片段（使用 `[Page X]` 标记）
        3. **清晰性**: 使用结构化的格式（标题、列表、引用块等）
        4. **完整性**: 如果问题涉及多个部分，确保全面回答
        5. **诚实性**: 如果 PDF 中没有相关信息，明确告知用户
        6. **直接输出内容**: 直接从标题、正文或结论开始回答，不要添加任何寒暄、引导语或自我说明，例如“好的，我已经阅读了 PDF”“以下是总结”
        
        ## 🎨 格式建议
        
        - 使用 Markdown 格式
        - 使用 emoji 使回答更生动（📄 📋 🎯 💡 ⚠️ ✅ 等）
        - 使用引用块 (>) 来引用 PDF 原文
        - 使用列表来组织要点
        - 在引用时标注页码，如: `(第 5 页)` 或 `[Page 5]`
        
        现在开始基于这个 PDF 文档回答用户的问题吧！
    """.trimIndent()
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024

    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.2f KB", bytes / kb)
        else -> "$bytes Bytes"
    }
}

/**
 * 格式化数字（添加千位分隔符）
 */
private fun formatNumber(number: Int): String {
    return "%,d".format(number)
}

/**
 * 构建语言指令，支持优先级：
 * 1. 用户提示词中明确指定了回复语言（最高优先级）
 * 2. language 参数指定的语言
 * 3. 根据用户询问的语言自动回复（默认）
 */
private fun buildLanguageInstruction(
    userInput: String,
    parameters: List<ConfigurationParameter>
): String {
    // 检查 language 参数
    val languageParam = parameters.find { it.key == "language" }?.value?.toString()?.trim()
    if (!languageParam.isNullOrEmpty()) {
        // 如果传递了 language 参数，直接使用（不转换，LLM 能理解）
        return "- Always respond in $languageParam, as specified by the language parameter."
    }

    // 默认：遵循用户提示词中的语言要求，如果没有要求就使用询问的语言
    return """- If the user explicitly requests a specific language in their message, respond in that language.
            - Otherwise, respond in the same language as the user's question."""
}

/**
 * 保存系统提示词到历史记录
 */
private suspend fun saveSystemPromptToHistory(
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    sessionId: String,
    appId: String,
    platform: String,
    uuid: String,
    username: String,
    pdfSnapshotId: String
) {
    val pdfChatMessage = PDFChatMessage(
        originalChatId = sessionId,
        role = PDFChatMessage.Role.system,
        content = systemPrompt,
        isIncognito = true,
        pdfSnapshotId = pdfSnapshotId,
        created = System.currentTimeMillis()
    )

    val documentObject = PDFChatDocumentObject(
        username = username,
        appId = appId,
        platform = PDFChatDocumentObject.Platform.valueOf(platform),
        uuid = uuid,
        pdfChat = pdfChatMessage,
        createDate = System.currentTimeMillis(),
        id = MyUtils.generateUniqueID()
    )

    withResolvedKMongo<PDFChatDocumentObject, Unit>(
        parameters = parameters,
        dbName = AgentMongoTargets.IOS_SERVE_DB,
        collectionName = AgentMongoTargets.PDF_CHAT_COLLECTION
    ) {
        insertOne(documentObject)
        Unit
    }
}

/**
 * 从数据库加载聊天历史记录
 */
private suspend fun loadChatHistory(
    parameters: List<ConfigurationParameter>,
    appId: String,
    platform: String,
    uuid: String,
    username: String,
    originalChatId: String
): List<Map<String, String>> {
    return try {
        val platformEnum = PDFChatDocumentObject.Platform.entries.find { it.name.equals(platform, ignoreCase = true) }
            ?: return emptyList()

        val pdfChatDoc = withResolvedKMongo<PDFChatDocumentObject, List<PDFChatDocumentObject>>(
            parameters = parameters,
            dbName = AgentMongoTargets.IOS_SERVE_DB,
            collectionName = AgentMongoTargets.PDF_CHAT_COLLECTION
        ) {
            find(
                and(
                    PDFChatDocumentObject::username eq username,
                    PDFChatDocumentObject::appId eq appId,
                    PDFChatDocumentObject::platform eq platformEnum,
                    PDFChatDocumentObject::uuid eq uuid,
                    PDFChatDocumentObject::pdfChat / PDFChatMessage::originalChatId eq originalChatId
                )
            ).sort(ascending(PDFChatDocumentObject::pdfChat / PDFChatMessage::created))
                .toList()
        }

        pdfChatDoc.map { doc ->
            val agentChat = doc.pdfChat
            val role = agentChat.role.value
            val content = agentChat.content
            mapOf(
                "role" to role,
                "content" to content
            )
        }
    } catch (e: Exception) {
        // Log before returning empty: a Mongo outage must be distinguishable from
        // "new conversation" (the swallow also dead-coded the caller's logging).
        logProgress(AnsiColor.RED, "ChatPDF", "❌ 加载聊天历史失败: ${e.message}")
        emptyList()
    }
}
