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
import org.openapitools.vertxweb.server.model.AgentChat
import org.openapitools.vertxweb.server.model.AgentChatDocumentObject
import java.util.*
import java.util.concurrent.atomic.AtomicReference

// 对话轮次超限异常
class StreamChatRoundsExceededException(message: String) :
    RuntimeException(message)

data class StreamChatAgentUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val source: String = "koog_actual"
)

private class StreamChatAgentUsageCollector {
    private val usageFromCompletedCalls = AtomicReference<StreamChatAgentUsage?>(null)
    private val usageFromStreamingFrames = AtomicReference<StreamChatAgentUsage?>(null)
    private val usageFromStreamingHistory = AtomicReference<StreamChatAgentUsage?>(null)

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

    fun finalUsage(): StreamChatAgentUsage? =
        usageFromCompletedCalls.get() ?: usageFromStreamingFrames.get() ?: usageFromStreamingHistory.get()

    private fun buildUsage(
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int?,
        source: String
    ): StreamChatAgentUsage? {
        val normalizedPromptTokens = promptTokens.coerceAtLeast(0)
        val normalizedCompletionTokens = completionTokens.coerceAtLeast(0)
        val normalizedTotalTokens = (totalTokens ?: (normalizedPromptTokens + normalizedCompletionTokens)).coerceAtLeast(0)
        if (normalizedPromptTokens <= 0 && normalizedCompletionTokens <= 0 && normalizedTotalTokens <= 0) {
            return null
        }
        return StreamChatAgentUsage(
            promptTokens = normalizedPromptTokens,
            completionTokens = normalizedCompletionTokens,
            totalTokens = normalizedTotalTokens,
            source = source
        )
    }

    private fun mergeUsage(
        current: StreamChatAgentUsage?,
        incoming: StreamChatAgentUsage
    ): StreamChatAgentUsage {
        if (current == null) return incoming
        return StreamChatAgentUsage(
            promptTokens = current.promptTokens + incoming.promptTokens,
            completionTokens = current.completionTokens + incoming.completionTokens,
            totalTokens = current.totalTokens + incoming.totalTokens,
            source = incoming.source
        )
    }
}

/**
 * StreamChatAgent - 使用 Koog 流式 API 的聊天代理
 *
 * 这个 Agent 使用 Koog.ai 的流式 API 实时输出响应，提供更好的用户体验
 *
 * 功能特性：
 * - 实时流式输出 LLM 响应
 * - 支持工具调用的流式处理
 * - 支持多轮对话
 * - 自动处理流式事件（文本、工具调用、结束）
 *
 * @param httpAccess HTTP 访问接口
 * @param parameters 配置参数
 * @param onChunk 可选的流式输出回调函数，用于实时处理文本块（用于 Web 流式响应等场景）
 */
suspend fun streamChatAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    onChunk: (String) -> Unit = {},
    onUsage: (StreamChatAgentUsage) -> Unit = {}
) {
    // 1) 解析/生成会话ID（用于复用 Agent 和上下文）
    val sessionId = parameters.parameter("session_id", UUID.randomUUID().toString())

    // 2) 若本次带有用户输入则执行一轮
    val userInput: String = parameters.parameter("prompt", "")
    if (userInput.isEmpty()) {
        return
    }

    // 3) 处理语言优先级逻辑
    val languageInstruction = buildLanguageInstruction(userInput, parameters)

    val systemPrompt = parameters.parameter(
        "system_prompt",
        """ 
            You are a helpful AI assistant with real-time streaming responses.  
            Your name is ${parameters.parameter("name", "StreamChat AI Agent")}.

            ## Core Rules
            - Follow a strict **one-question, one-answer** rule.  
            - Every user message **must receive exactly one complete reply** from **assistant**.  
            - Only the **assistant** replies to users — tools and system messages never do.  
            - Each reply must be meaningful, never empty or placeholder.  
            - After replying, the conversation turn ends.

            ## Tool Use
            - Use tools **only** when new or external info is required.  
            - After using a tool, give **one final assistant response**.  
            - Don't repeat the same tool calls or leave users unanswered.

            ## Style
            - Stream responses naturally.  
            - Be clear, concise, and accurate.  
            - Ask clarifying questions only when truly needed.

            ## Language
            $languageInstruction
        """.trimIndent()
    )

    // 4) 从数据库加载历史记录并检查轮次限制
    val appId = parameters.parameter("appId", "")
    val platform = parameters.parameter("platform", "")
    val uuid = parameters.parameter("uuid", "")
    val username = parameters.parameter("username", "")

    val historyMessages: List<Map<String, String>> = if (appId.isNotEmpty() && platform.isNotEmpty() &&
        uuid.isNotEmpty() && username.isNotEmpty()
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

    // 5) 创建 Agent 实例 并且 运行
    val usageCollector = StreamChatAgentUsageCollector()
    val skillManager = createSkillManager(parameters)
    buildAndRunAgent<String, String>(
        httpAccess = httpAccess,
        parameters = parameters,
        input = userInput,
        systemPrompt = systemPrompt,
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
        },
        toolRegistry = parseToolSet(
            parameters,
            httpAccess,
            tools = listOf(
                AgentTools.EXIT,
                AgentTools.WEB_TOOLS,
            )
        )
    ) { _, _, _ -> buildStreamChatStrategy(historyMessages, sessionId, onChunk) }

    usageCollector.finalUsage()?.let(onUsage)
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
    // Case-insensitive platform resolution (mirrors ChatPDFAgent): the enum names
    // are lowercase ("ios", "web"); a caller passing "iOS"/"Web" used to throw
    // IllegalArgumentException INTO the catch-all below — history silently
    // vanished with zero log output and round-limit enforcement reset.
    val resolvedPlatform = AgentChatDocumentObject.Platform.entries
        .find { it.name.equals(platform, ignoreCase = true) }
    if (resolvedPlatform == null) {
        logProgress(AnsiColor.YELLOW, "StreamChat", "⚠ Unknown platform '$platform', loading no history")
        return emptyList()
    }
    return try {
        val chatDocs = withResolvedKMongo<AgentChatDocumentObject, List<AgentChatDocumentObject>>(
            parameters = parameters,
            dbName = AgentMongoTargets.IOS_SERVE_DB,
            collectionName = AgentMongoTargets.AGENT_CHAT_COLLECTION
        ) {
            find(
                and(
                    AgentChatDocumentObject::username eq username,
                    AgentChatDocumentObject::appId eq appId,
                    AgentChatDocumentObject::platform eq resolvedPlatform,
                    AgentChatDocumentObject::uuid eq uuid,
                    AgentChatDocumentObject::agentChat / AgentChat::originalChatId eq originalChatId
                )
            ).sort(ascending(AgentChatDocumentObject::agentChat / AgentChat::created))
                .toList()
        }

        // 转换为 Agent 可以理解的格式
        chatDocs.mapNotNull { doc ->
            // 安全检查：确保 agentChat 不为 null
            val agentChat = doc.agentChat
            val role = agentChat.role?.value ?: return@mapNotNull null
            val content = agentChat.content ?: ""
            mapOf(
                "role" to role,
                "content" to content
            )
        }
    } catch (e: Exception) {
        // Log before returning empty: a Mongo outage must be distinguishable
        // from "new conversation" in operator logs.
        logProgress(AnsiColor.RED, "StreamChat", "❌ Failed to load chat history: ${e.message}")
        emptyList()
    }
}
