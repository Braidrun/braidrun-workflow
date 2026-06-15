package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 用户交互请求记录
 */
data class UserInteractionRequest(
    val requestId: String,
    val executionId: String,
    val stepName: String,
    val message: String,
    val requiresResponse: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val response: CompletableDeferred<String>? = null
)

/**
 * 用户交互回调接口
 *
 * 工作流执行引擎通过此接口将 Agent 的用户交互请求转发到前端（WebSocket），
 * 并等待用户回复。
 */
interface UserInteractionHandler {
    /**
     * 发送消息给用户（不需要回复）
     */
    fun sendMessage(message: String)

    /**
     * 向用户提问并等待回复
     * @param question 要问用户的问题
     * @param timeoutMs 超时时间（毫秒），默认 5 分钟
     * @return 用户的回复
     */
    suspend fun askQuestion(question: String, timeoutMs: Long = 300_000L): String
}

/**
 * 用户交互工具集 — 允许 Agent 在工作流执行过程中与用户进行交互
 *
 * 与 TerminalInteractiveTools 不同，此工具通过回调机制实现，
 * 适用于 Web 环境（通过 WebSocket 与前端监控页面的对话界面交互）。
 *
 * 在 YAML 工作流中，通过在 agent 的 tools 列表中添加 "user_interaction" 来启用：
 * ```yaml
 * agents:
 *   my_agent:
 *     preset: universal
 *     overrides:
 *       tools:
 *         - user_interaction
 *         - file_system
 * ```
 */
@Serializable
@LLMDescription("Tools for interacting with users through the web interface. Use these to send messages to the user or ask questions and wait for their response.")
class UserInteractionTools(
    private val handler: UserInteractionHandler
) : ToolSet {

    @Tool
    @LLMDescription("Send a message to the user through the web interface. The user will see this message but no response is required. Use this for progress updates, notifications, or sharing results.")
    fun tellUser(
        @LLMDescription("The message to display to the user")
        message: String
    ): String {
        handler.sendMessage(message)
        return "Message sent to user successfully."
    }

    @Tool
    @LLMDescription("Ask the user a question and wait for their response through the web interface. Use this when you need user input, confirmation, clarification, or a decision. The tool will block until the user responds or the request times out.")
    suspend fun askUser(
        @LLMDescription("The question or prompt to display to the user")
        question: String
    ): String {
        return handler.askQuestion(question)
    }
}

/**
 * 用户交互请求的全局注册表
 *
 * 管理所有活跃的用户交互请求，支持通过 requestId 查找和回复。
 * ExecutionService 通过此注册表桥接 WebSocket 消息和 Agent 工具调用。
 */
object UserInteractionRegistry {
    private val counter = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val requestMetadata = ConcurrentHashMap<String, UserInteractionRequest>()

    /**
     * 生成唯一的请求 ID
     */
    fun generateRequestId(executionId: String, stepName: String): String {
        return "uir-${executionId}-${stepName}-${counter.incrementAndGet()}"
    }

    /**
     * 注册一个等待用户回复的请求
     */
    fun registerRequest(request: UserInteractionRequest, deferred: CompletableDeferred<String>) {
        pendingRequests[request.requestId] = deferred
        requestMetadata[request.requestId] = request
    }

    /**
     * 提交用户的回复
     * @return true 如果请求存在且成功回复，false 如果请求不存在或已超时
     */
    fun submitReply(requestId: String, reply: String): Boolean {
        val deferred = pendingRequests.remove(requestId)
        requestMetadata.remove(requestId)
        return if (deferred != null && deferred.isActive) {
            deferred.complete(reply)
            true
        } else {
            false
        }
    }

    /**
     * 取消一个待处理的请求
     */
    fun cancelRequest(requestId: String) {
        val deferred = pendingRequests.remove(requestId)
        requestMetadata.remove(requestId)
        deferred?.cancel()
    }

    /**
     * 获取某个执行的所有待处理请求
     */
    fun getPendingRequests(executionId: String): List<UserInteractionRequest> {
        return requestMetadata.values.filter { it.executionId == executionId }
    }

    /**
     * 清理某个执行的所有请求（执行完成时调用）
     */
    fun cleanupExecution(executionId: String) {
        val toRemove = requestMetadata.entries.filter { it.value.executionId == executionId }
        for ((requestId, _) in toRemove) {
            val deferred = pendingRequests.remove(requestId)
            requestMetadata.remove(requestId)
            deferred?.cancel()
        }
    }

    /**
     * 检查是否有待处理的请求
     */
    fun hasPendingRequest(requestId: String): Boolean {
        return pendingRequests.containsKey(requestId)
    }
}
