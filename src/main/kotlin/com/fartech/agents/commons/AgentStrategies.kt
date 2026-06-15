package com.fartech.agents.commons

// Koog 1.0.0 dropped the 0.x `ai.koog.agents.core.agent.ToolCalls` enum.
// We import our local replacement from [Koog1xCompat] which preserves the
// 3-way `SEQUENTIAL` / `SINGLE_RUN_SEQUENTIAL` / `PARALLEL` semantic at the
// strategy-selection layer. The new Koog `ToolCalls` data class (now in
// `ai.koog.agents.core.dsl.extension`) represents a batch of tool calls
// at the edge / node DSL layer and is unrelated.
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.environment.ReceivedToolResult
// Koog 1.0.0 — `ai.koog.agents.core.environment.result` extension function
// gone; replaced by `ReceivedToolResult.toMessagePart()` member fun.
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.snapshot.feature.persistence
import ai.koog.agents.snapshot.feature.withPersistence
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.TypeToken
import com.fartech.ftapp2.commonsKt.*
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val TOOL_RESULTS_CONTINUATION_PROMPT =
    "Continue after the tool result. If more tool work is needed, call the next tool; otherwise return the final answer."

/**
 * Shared reasoning step execution logic. Call inside an `llm.writeSession { }` block.
 *
 * Handles:
 * - Appending reasoning prompt and continuation prompt
 * - Calling LLM without tools to get reasoning output
 * - Proper exception handling: silently skips expected `reasoning_content` errors,
 *   logs unexpected errors with a warning instead of swallowing them
 * - Always appends the continuation prompt so the LLM conversation flow continues
 *
 * @param requestLLMWithoutToolsFn The LLM call function.
 * @param appendPromptFn Function to append a user prompt message
 * @param reasoningPrompt The prompt asking the LLM to reason
 * @param continuationPrompt The prompt asking the LLM to continue with action
 * @param showReasoning Whether reasoning is enabled
 * @param onReasoningMessage Optional callback for reasoning output
 */
private suspend fun executeReasoningStep(
    requestLLMWithoutToolsFn: suspend () -> Message.Assistant,
    appendPromptFn: suspend (String) -> Unit,
    reasoningPrompt: String,
    continuationPrompt: String,
    showReasoning: Boolean,
    onReasoningMessage: ReasoningMonitorCallback?
) {
    if (!showReasoning) return
    appendPromptFn(reasoningPrompt)
    try {
        val reasoning = requestLLMWithoutToolsFn()
        // Koog 1.0.0 — `.content` accessor gone; `Message.textContent()`
        // walks `parts: List<MessagePart>` and concatenates Text parts.
        val reasoningText = reasoning.textContent()
        if (reasoningText.isNotBlank()) {
            printlnColor(AnsiColor.CYAN, "\n💭 [Reasoning]\n$reasoningText")
            onReasoningMessage?.invoke(reasoningText)
        }
    } catch (e: Exception) {
        // Some models (e.g., OpenRouter reasoning models) may only return reasoning_content
        // instead of content, causing the client to throw. This is expected — skip silently.
        // For other errors (network, timeout, API limits), log a warning.
        val msg = e.message ?: ""
        if (!isOpenRouterReasoningOnlyError(msg)) {
            printlnColor(AnsiColor.YELLOW, "⚠️ Reasoning step error: ${msg.take(200)}")
        }
    }
    appendPromptFn(continuationPrompt)
}

/**
 * Creates a ReAct AI agent strategy that alternates between reasoning and execution stages
 * to dynamically process tasks and request outputs from an LLM.
 *
 * @param reasoningInterval Specifies the interval for reasoning steps.
 * @return An instance of [AIAgentGraphStrategy] that defines the ReAct strategy.
 *
 *
 * +-------+             +---------------+             +---------------+             +--------+
 * | Start | ----------> | CallLLMReason | ----------> | CallLLMAction | ----------> | Finish |
 * +-------+             +---------------+             +---------------+             +--------+
 *                                   ^                       | Finished?     Yes
 *                                   |                       | No
 *                                   |                       v
 *                                   +-----------------------+
 *                                   |      ExecuteTool      |
 *                                   +-----------------------+
 *
 * Example execution flow of a banking agent with ReAct strategy:
 *
 * 1. Start: User asks "How much did I spend last month?"
 *
 * 2. Reasoning Phase:
 *    CallLLMReason: "I need to follow these steps:
 *    1. Get all transactions from last month
 *    2. Filter out deposits (positive amounts)
 *    3. Calculate total spending"
 *
 * 3. Action & Execution Phase 1:
 *    CallLLMAction: {tool: "get_transactions", args: {startDate: "2025-05-19", endDate: "2025-06-18"}}
 *    ExecuteTool Result: [
 *      {date: "2025-05-25", amount: -100.00, description: "Grocery Store"},
 *      {date: "2025-05-31", amount: +1000.00, description: "Salary Deposit"},
 *      {date: "2025-06-10", amount: -500.00, description: "Rent Payment"},
 *      {date: "2025-06-13", amount: -200.00, description: "Utilities"}
 *    ]
 *
 * 4. Reasoning Phase:
 *    CallLLMReason: "I have the transactions. Now I need to:
 *    1. Remove the salary deposit of +1000.00
 *    2. Sum up the remaining transactions"
 *
 * 5. Action & Execution Phase 2:
 *    CallLLMAction: {tool: "calculate_sum", args: {amounts: [-100.00, -500.00, -200.00]}}
 *    ExecuteTool Result: -800.00
 *
 * 6. Final Response:
 *    Assistant: "You spent $800.00 last month on groceries, rent, and utilities."
 *
 * 7. Finish: Execution complete
 */
/**
 * Constructs and returns an AI agent strategy that processes a task with optional support for attachments.
 * The strategy incorporates multiple nodes including reasoning, tool execution, and LLM interactions,
 * with the ability to dynamically process attachments during LLM calls.
 *
 * @param reasoningInterval The interval of reasoning steps at which additional prompts are added.
 *                          Defaults to 1. Must be greater than 0.
 * @param name The name of the strategy being defined. Defaults to "__re_act_strategy_attachments__".
 * @param attachments A list of attachment files (e.g., images, audio, video, binary) to be incorporated
 *                    into LLM prompts when needed. Defaults to an empty list.
 * @return An instance of AIAgentGraphStrategy capable of processing tasks using the specified strategy configuration.
 */
fun reActStrategyWithAttachments(
    reasoningInterval: Int = 1,
    name: String = "__re_act_strategy_attachments__",
    attachments: List<AttachmentFile> = emptyList(),
    historyCompression: HistoryCompressionConfig? = null,
    showReasoning: Boolean = true,
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    onReasoningMessage: ReasoningMonitorCallback? = null,
): AIAgentGraphStrategy<String, String> = strategy(name) {
    require(reasoningInterval > 0) { "Reasoning interval must be greater than 0" }
    val reasoningStepKey = createStorageKey<Int>("${name}_reasoning_step")
    val restoreFromHistory by restoreHistoryNode(historyMessages)
    val nodeSetup by node<String, String> {
        storage.set(reasoningStepKey, 0)
        it
    }
    val nodeCallLLM by node<Unit, Message.Assistant> {
        llm.writeSession {
            requestWithOpenRouterEmptyResponseRetry(
                retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                appendPromptFn = { msg -> appendPrompt { user(msg) } }
            ) {
                requestLLMPreservingDeepSeekReasoning()
            }
        }
    }
    val nodeExecuteTool by nodeExecuteTool()

    val reasoningPrompt = "Please give your thoughts about the task and plan the next steps."
    val continuationPrompt =
        "Based on your analysis above, now proceed to execute the plan. Call the appropriate tool for the next step."
    val nodeCallLLMReasonInput by node<String, Unit> { stageInput ->
        llm.writeSession {
            appendPrompt {
                if (attachments.isNotEmpty()) {
                    user {
                        attachmentsIfNeeded(attachments)
                    }
                }
                user(stageInput)
            }
            executeReasoningStep(
                requestLLMWithoutToolsFn = { requestLLMWithoutToolsPreservingDeepSeekReasoning() },
                appendPromptFn = { msg -> appendPrompt { user(msg) } },
                reasoningPrompt = reasoningPrompt,
                continuationPrompt = continuationPrompt,
                showReasoning = showReasoning,
                onReasoningMessage = onReasoningMessage
            )
        }
    }
    val nodeCallLLMReason by node<ReceivedToolResults, Unit> { results ->
        val reasoningStep = storage.getValue(reasoningStepKey)
        llm.writeSession {
            appendPrompt {
                // Koog 1.0.0 — `tool { result(toolResult) }` → `user { toolResult(part) }`.
                // Append EVERY result: the execute node batches parallel tool calls.
                user { results.toolResults.forEach { toolResult(it.toMessagePart()) } }
            }
            if (reasoningStep % reasoningInterval == 0) {
                executeReasoningStep(
                    requestLLMWithoutToolsFn = { requestLLMWithoutToolsPreservingDeepSeekReasoning() },
                    appendPromptFn = { msg -> appendPrompt { user(msg) } },
                    reasoningPrompt = reasoningPrompt,
                    continuationPrompt = continuationPrompt,
                    showReasoning = showReasoning,
                    onReasoningMessage = onReasoningMessage
                )
            }
        }
        storage.set(reasoningStepKey, reasoningStep + 1)
    }

    edge(nodeStart forwardTo restoreFromHistory)
    edge(restoreFromHistory forwardTo nodeSetup)
    edge(nodeSetup forwardTo nodeCallLLMReasonInput)
    edge(nodeCallLLMReasonInput forwardTo nodeCallLLM)
    edge(nodeCallLLM forwardTo nodeExecuteTool onCondition { msg ->
        msg.parts.any { it is MessagePart.Tool.Call }
    })
    edge(nodeCallLLM forwardTo nodeFinish onCondition { msg ->
        msg.parts.any { it is MessagePart.Text }
    } transformed { msg -> msg.textContent() })
    if (historyCompression != null) {
        val compressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<ReceivedToolResults>(
            name = "__react_compress_history__",
            historyCompression = historyCompression,
        )
        val reactSessionCompactBefore by node<ReceivedToolResults, ReceivedToolResults>("__react_session_compact_before__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
            input
        }
        val reactSessionCompactAfter by node<ReceivedToolResults, ReceivedToolResults>("__react_session_compact_after__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
            input
        }
        edge(nodeExecuteTool forwardTo reactSessionCompactBefore)
        edge(reactSessionCompactBefore forwardTo compressHistory)
        edge(compressHistory forwardTo reactSessionCompactAfter)
        edge(reactSessionCompactAfter forwardTo nodeCallLLMReason)
    } else {
        edge(nodeExecuteTool forwardTo nodeCallLLMReason)
    }
    edge(nodeCallLLMReason forwardTo nodeCallLLM)
}

fun List<ConfigurationParameter>.reactStrategy(
    attachments: List<AttachmentFile> = parameter("attachments", emptyList()),
    historyMessages: List<Map<String, String>> = loadHistoryMessages(this),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    onReasoningMessage: ReasoningMonitorCallback? = null,
) =
    reActStrategyWithAttachments(
        reasoningInterval = parameter("reasoning_interval", 3),
        name = parameter("strategy_name", "ReAct"),
        attachments = attachments,
        historyCompression = parameter("history_compression", null),
        showReasoning = parameter("show_reasoning", true),
        historyMessages = historyMessages,
        skillManager = skillManager,
        sessionKey = sessionKey,
        onReasoningMessage = onReasoningMessage,
    )

/**
 * Defines the "just work" strategy that creates a processing pipeline by chaining input, tools, and outputs.
 *
 * @param httpAccess The HTTP access object used to handle network-related operations.
 * @param parameters A list of configuration parameters required to set up the strategy.
 * @param tools The registry of tools that defines the available operations. This parameter is optional
 *              and defaults to the result of parsing the provided parameters alongside the httpAccess object.
 * @param attachments A list of additional file attachments that may be required during the execution of tools. Defaults to an empty list.
 */
fun justDoWorkStrategy(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    tools: ToolRegistry = parseToolSet(parameters, httpAccess, emptyList<AgentTools>()),
    attachments: List<AttachmentFile> = emptyList(),
    runMode: ToolCalls = ToolCalls.SEQUENTIAL,
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
) = strategy<String, String>(parameters.parameter("strategy_name", "__just_do_work__")) {
    val historyCompression: HistoryCompressionConfig? = parameters.parameter("history_compression", null)
    val restoreFromHistory by restoreHistoryNode(historyMessages)
    val finishWorkByTools by toolCycleGraph<String, String>(
        name = "__finish_work_by_tools__",
        inputPrompt = { input, _ -> input },
        // Koog 1.0.0 — `.content` → `.textContent()`; ToolRegistry.tools is now
        // List<ToolBase<*, *>>, filter to Tool for the strategy helper.
        outputCompute = { resp, _ -> resp.textContent() },
        tools = tools.tools.filterIsInstance<Tool<*, *>>(),
        attachments = attachments,
        historyCompression = historyCompression,
        skillManager = skillManager,
        sessionKey = sessionKey,
    )
    val finishWorkByToolsMulti by toolCycleGraphMulti<String, String>(
        name = "__finish_work_by_tools_multi__",
        inputPrompt = { input, _ -> input },
        // Koog 1.0.0 — `.content` → `.textContent()`; ToolRegistry.tools is now
        // List<ToolBase<*, *>>, filter to Tool for the strategy helper.
        outputCompute = { resp, _ -> resp.textContent() },
        tools = tools.tools.filterIsInstance<Tool<*, *>>(),
        attachments = attachments,
        historyCompression = historyCompression,
        skillManager = skillManager,
        sessionKey = sessionKey,
    )
    val graphNode = when (runMode) {
        ToolCalls.SEQUENTIAL, ToolCalls.SINGLE_RUN_SEQUENTIAL -> finishWorkByTools
        ToolCalls.PARALLEL -> finishWorkByToolsMulti
    }
    nodeStart then restoreFromHistory then graphNode then nodeFinish
}

/**
 * Defines the "just work with reasoning" strategy — equivalent to [justDoWorkStrategy] with
 * [ToolCalls.PARALLEL] but each tool-cycle iteration is preceded by an explicit reasoning step,
 * mirroring the reasoning mechanism in [reActStrategyWithAttachments].
 *
 * @param httpAccess The HTTP access object used to handle network-related operations.
 * @param parameters A list of configuration parameters required to set up the strategy.
 * @param tools The registry of tools that defines the available operations.
 * @param attachments A list of additional file attachments. Defaults to an empty list.
 */
fun justDoWorkWithReasoningStrategy(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    tools: ToolRegistry = parseToolSet(parameters, httpAccess, emptyList<AgentTools>()),
    attachments: List<AttachmentFile> = emptyList(),
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    onReasoningMessage: ReasoningMonitorCallback? = null,
) = strategy<String, String>(parameters.parameter("strategy_name", "__just_do_work_reasoning__")) {
    val historyCompression: HistoryCompressionConfig? = parameters.parameter("history_compression", null)
    val restoreFromHistory by restoreHistoryNode(historyMessages)
    val finishWorkByToolsMultiReasoning by toolCycleGraphMultiReasoning<String, String>(
        name = "__finish_work_by_tools_multi_reasoning__",
        inputPrompt = { input, _ -> input },
        // Koog 1.0.0 — `.content` → `.textContent()`; ToolRegistry.tools is now
        // List<ToolBase<*, *>>, filter to Tool for the strategy helper.
        outputCompute = { resp, _ -> resp.textContent() },
        tools = tools.tools.filterIsInstance<Tool<*, *>>(),
        attachments = attachments,
        historyCompression = historyCompression,
        skillManager = skillManager,
        sessionKey = sessionKey,
        allowAssistantMessageCompletion = parameters.parameter("allow_assistant_completion", true),
        reasoningInterval = parameters.parameter("reasoning_interval", 1),
        showReasoning = parameters.parameter("show_reasoning", true),
        onReasoningMessage = onReasoningMessage,
    )
    nodeStart then restoreFromHistory then finishWorkByToolsMultiReasoning then nodeFinish
}

fun singleRunWithParallelAbility(
    name: String,
    parallel: Boolean = true,
    historyCompression: HistoryCompressionConfig? = null,
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
) = strategy(name) {
    val restoreFromHistory by restoreHistoryNode(historyMessages)
    val nodeCallLLM by node<String, List<Message.Assistant>>("__single_run_call_llm__") { input ->
        llm.writeSession {
            appendPrompt {
                user(input)
            }
            requestWithOpenRouterEmptyResponseRetry(
                retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                appendPromptFn = { msg -> appendPrompt { user(msg) } }
            ) {
                requestLLMMultiplePreservingDeepSeekReasoning()
            }
        }
    }
    val nodeExecuteTool by nodeExecuteMultipleTools(parallelTools = parallel)
    val nodeSendToolResult by node<List<ReceivedToolResult>, List<Message.Assistant>>("__single_run_send_tool_result__") { results ->
        llm.writeSession {
            appendPrompt {
                // Koog 1.0.0 — `tool { result(toolResult) }` → `user { toolResult(part) }`
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
                user(TOOL_RESULTS_CONTINUATION_PROMPT)
            }
            requestWithOpenRouterEmptyResponseRetry(
                retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                appendPromptFn = { msg -> appendPrompt { user(msg) } }
            ) {
                requestLLMMultiplePreservingDeepSeekReasoning()
            }
        }
    }

    nodeStart then restoreFromHistory then nodeCallLLM
    // Koog 1.0.0 — `onMultipleToolCalls` / `onMultipleAssistantMessages` gone;
    // operate on `List<Message.Assistant>` via `onCondition` + flatten parts.
    edge(nodeCallLLM forwardTo nodeExecuteTool onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts }.any { it is MessagePart.Tool.Call }
    })
    edge(
        nodeCallLLM forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
            messages.flatMap { it.parts }.any { it is MessagePart.Text }
        } transformed { messages: List<Message.Assistant> ->
            messages.joinToString("\n") { it.textContent() }
        }
    )

    if (historyCompression != null) {
        val compressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<List<ReceivedToolResult>>(
            name = "__single_run_compress_history__",
            historyCompression = historyCompression,
        )
        val singleRunSessionCompactBefore by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("__single_run_session_compact_before__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
            input
        }
        val singleRunSessionCompactAfter by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("__single_run_session_compact_after__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
            input
        }
        edge(nodeExecuteTool forwardTo singleRunSessionCompactBefore)
        edge(singleRunSessionCompactBefore forwardTo compressHistory)
        edge(compressHistory forwardTo singleRunSessionCompactAfter)
        edge(singleRunSessionCompactAfter forwardTo nodeSendToolResult)
    } else {
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
    }

    edge(nodeSendToolResult forwardTo nodeExecuteTool onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts }.any { it is MessagePart.Tool.Call }
    })

    edge(
        nodeSendToolResult forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
            messages.flatMap { it.parts }.any { it is MessagePart.Text }
        } transformed { messages: List<Message.Assistant> ->
            messages.joinToString("\n") { it.textContent() }
        }
    )
}

/**
 * 在 [singleRunWithParallelAbility] 的基础上增加 reasoning（思考）支持。
 *
 * 仿效 [singleRunWithParallelAbility] 直接在 strategy 内构建图节点：
 * 初始推理 → LLM 调用 → 工具执行 → 工具后推理 → 循环，直到 LLM 返回纯文本。
 * 可选择通过 [historyCompression] 在每轮工具执行后压缩历史消息以节约 token。
 *
 * @param name 策略名称。
 * @param parallel 是否并行执行多个工具调用，默认 true。
 * @param historyCompression 历史消息压缩配置，null 表示不压缩。
 * @param reasoningInterval 每隔几轮工具调用执行一次推理，默认 1（每轮都推理）。
 * @param showReasoning 是否显示推理内容，默认 true。
 * @param historyMessages 恢复的历史消息列表。
 */
fun singleRunWithParallelAbilityWithReasoning(
    name: String,
    parallel: Boolean = true,
    historyCompression: HistoryCompressionConfig? = null,
    reasoningInterval: Int = 1,
    showReasoning: Boolean = true,
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    onReasoningMessage: ReasoningMonitorCallback? = null,
) = strategy<String, String>(name) {
    require(reasoningInterval > 0) { "Reasoning interval must be greater than 0" }
    val reasoningStepKey = createStorageKey<Int>("${name}_reasoning_step")
    val reasoningPrompt = "Please give your thoughts about the task and plan the next steps."
    val continuationPrompt =
        "Based on your analysis above, now proceed to execute the plan. Call the appropriate tool for the next step."

    val restoreFromHistory by restoreHistoryNode(historyMessages)

    // 对初始输入做 reasoning，然后交给 nodeCallLLM 发起真正的工具调用请求
    val nodeDoInitialReasoning by node<String, Unit>("__single_run_reasoning_initial__") { _ ->
        storage.set(reasoningStepKey, 0)
        llm.writeSession {
            executeReasoningStep(
                requestLLMWithoutToolsFn = { requestLLMWithoutToolsPreservingDeepSeekReasoning() },
                appendPromptFn = { msg -> appendPrompt { user(msg) } },
                reasoningPrompt = reasoningPrompt,
                continuationPrompt = continuationPrompt,
                showReasoning = showReasoning,
                onReasoningMessage = onReasoningMessage
            )
        }
    }

    // 发起多工具并行 LLM 请求
    val nodeCallLLM by node<Unit, List<Message.Assistant>>("__single_run_reasoning_call_llm__") { _ ->
        llm.writeSession {
            requestWithOpenRouterEmptyResponseRetry(
                retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                appendPromptFn = { msg -> appendPrompt { user(msg) } }
            ) {
                requestLLMMultiplePreservingDeepSeekReasoning()
            }
        }
    }

    val nodeExecuteTool by nodeExecuteMultipleTools(
        name = "__single_run_reasoning_execute_tool__",
        parallelTools = parallel
    )

    // 将多工具结果追加到 prompt，并按 reasoningInterval 插入推理步骤，然后交由 nodeCallLLM 继续
    val nodeReasonAfterTools by node<List<ReceivedToolResult>, Unit>("__single_run_reasoning_after_tools__") { results ->
        val reasoningStep = storage.getValue(reasoningStepKey)
        val shouldRunReasoning = showReasoning && reasoningStep % reasoningInterval == 0
        llm.writeSession {
            appendPrompt {
                // Koog 1.0.0 — `tool { result(toolResult) }` → `user { toolResult(part) }`
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
            }
            if (shouldRunReasoning) {
                executeReasoningStep(
                    requestLLMWithoutToolsFn = { requestLLMWithoutToolsPreservingDeepSeekReasoning() },
                    appendPromptFn = { msg -> appendPrompt { user(msg) } },
                    reasoningPrompt = reasoningPrompt,
                    continuationPrompt = continuationPrompt,
                    showReasoning = showReasoning,
                    onReasoningMessage = onReasoningMessage
                )
            } else {
                appendPrompt { user(TOOL_RESULTS_CONTINUATION_PROMPT) }
            }
        }
        storage.set(reasoningStepKey, reasoningStep + 1)
    }

    nodeStart then restoreFromHistory then nodeDoInitialReasoning then nodeCallLLM

    edge(nodeCallLLM forwardTo nodeExecuteTool onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts }.any { it is MessagePart.Tool.Call }
    })
    edge(
        nodeCallLLM forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
            messages.flatMap { it.parts }.any { it is MessagePart.Text }
        } transformed { messages: List<Message.Assistant> ->
            messages.joinToString("\n") { it.textContent() }
        }
    )

    if (historyCompression != null) {
        val compressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<List<ReceivedToolResult>>(
            name = "__single_run_reasoning_compress_history__",
            historyCompression = historyCompression,
        )
        val singleRunReasoningSessionCompactBefore by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("__single_run_reasoning_session_compact_before__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
            input
        }
        val singleRunReasoningSessionCompactAfter by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("__single_run_reasoning_session_compact_after__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
            input
        }
        edge(nodeExecuteTool forwardTo singleRunReasoningSessionCompactBefore)
        edge(singleRunReasoningSessionCompactBefore forwardTo compressHistory)
        edge(compressHistory forwardTo singleRunReasoningSessionCompactAfter)
        edge(singleRunReasoningSessionCompactAfter forwardTo nodeReasonAfterTools)
    } else {
        edge(nodeExecuteTool forwardTo nodeReasonAfterTools)
    }

    edge(nodeReasonAfterTools forwardTo nodeCallLLM)
}

/**
nodeStart
→ nodeCallLLM
├──► nodeExecuteTool
│         → nodeSendToolResult
│                ├──► nodeExecuteTool (继续调用工具)
│                └──► nodeGenerateSummary → nodeFinish (结束)
└──► giveFeedbackToCallTools
├──► nodeExecuteTool (→ 回到 nodeSendToolResult → …)
└──► giveFeedbackToCallTools (循环直到正确调用工具)
 **/
/**
 * Defines the strategy for managing a chat workflow that integrates summarization and tool calls.
 *
 * The `chatWithSummaryStrategy` is a lazy-initialized object that orchestrates the interaction
 * between multiple nodes involved in the chat process. It ensures that the chat maintains
 * structured interactions through tool calls and generates a summary upon completing the tasks.
 *
 * Nodes involved in the strategy:
 * - **nodeCallLLM**: Handles sending input to the language model.
 * - **nodeExecuteTool**: Executes the required tools based on specified logic.
 * - **nodeSendToolResult**: Sends the results of the executed tools back.
 * - **giveFeedbackToCallTools**: Provides feedback to encourage tool usage instead of plain text responses.
 * - **nodeGenerateSummary**: Creates a concise task completion summary.
 *
 * Edges defining workflow transitions:
 * - Initial transition from `nodeStart` to `nodeCallLLM`.
 * - Conditional transitions to handle tool calls and assistant messages, ensuring proper execution flow.
 * - Feedback node loops until a tool call is made.
 * - Executes tools, sends results, and transitions to summary generation upon task completion.
 * - Final transition from summary generation to `nodeFinish`.
 *
 * This strategy enforces using tools correctly during the chat and ensures the session concludes with a well-structured summary.
 */
fun chatWithSummaryStrategy(
    historyMessages: List<Map<String, String>> = emptyList(),
    historyCompression: HistoryCompressionConfig? = null,
    skillManager: SkillManager? = null,
    sessionKey: String = "",
) =
    strategy<String, String>("chatWithSummary") {
        val restoreFromHistory by restoreHistoryNode(historyMessages)
        val nodeCallLLM by nodeLLMRequestPreservingDeepSeekReasoning("sendInput")
        val nodeExecuteTool by nodeExecuteTool("nodeExecuteTool")
        val nodeSendToolResult by nodeLLMSendToolResultsPreservingDeepSeekReasoning("nodeSendToolResult")

        val giveFeedbackToCallTools by node<String, Message.Assistant> { _ ->
            llm.writeSession {
                appendPrompt {
                    user(
                        "Don't chat with plain text! Call one of the available tools, instead: ${
                            tools.joinToString(", ") {
                                it.name
                            }
                        }"
                    )
                }

                requestLLMPreservingDeepSeekReasoning()
            }
        }

        // 生成最终总结
        val nodeGenerateSummary by node<String, Message.Assistant> { _ ->
            llm.writeSession {
                appendPrompt {
                    user(
                        "任务已完成！请调用 enhanced_say_to_user 工具生成一句简洁的总结，然后结束对话。\n" +
                                "总结格式：'✅ 任务完成：[简要描述完成的工作和结果]'\n" +
                                "例如：'✅ 任务完成：已成功分析ASA广告活动数据，发现CTR提升15%，建议优化低表现关键词。'"
                    )
                }
                requestLLMPreservingDeepSeekReasoning()
            }
        }

        edge(nodeStart forwardTo restoreFromHistory)
        edge(restoreFromHistory forwardTo nodeCallLLM)

        var chatSummaryFeedbackAttempts = 0
        val maxFeedbackAttempts = 3

        edge(nodeCallLLM forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })
        edge(nodeCallLLM forwardTo giveFeedbackToCallTools onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { msg -> msg.textContent() })

        edge(giveFeedbackToCallTools forwardTo giveFeedbackToCallTools onCondition { msg ->
            msg.parts.any { it is MessagePart.Text } && ++chatSummaryFeedbackAttempts < maxFeedbackAttempts
        } transformed { _ -> "" })
        edge(giveFeedbackToCallTools forwardTo nodeGenerateSummary onCondition { msg ->
            msg.parts.any { it is MessagePart.Text } && chatSummaryFeedbackAttempts >= maxFeedbackAttempts
        } transformed { _ -> "" })
        edge(giveFeedbackToCallTools forwardTo nodeExecuteTool onCondition { msg ->
            val callsTool = msg.parts.any { it is MessagePart.Tool.Call }
            // 反馈预算按"回合"计:模型回到正常调用工具后重置,否则 3 次额度是
            // 整个会话的终身上限,后续任何一次文本回复都会直接触发总结收尾。
            if (callsTool) chatSummaryFeedbackAttempts = 0
            callsTool
        })

        if (historyCompression != null) {
            val compressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<ReceivedToolResults>(
                name = "__chat_with_summary_compress_history__",
                historyCompression = historyCompression,
            )
            val chatWithSummarySessionCompactBefore by node<ReceivedToolResults, ReceivedToolResults>("__chat_with_summary_session_compact_before__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
                input
            }
            val chatWithSummarySessionCompactAfter by node<ReceivedToolResults, ReceivedToolResults>("__chat_with_summary_session_compact_after__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
                input
            }
            edge(nodeExecuteTool forwardTo chatWithSummarySessionCompactBefore)
            edge(chatWithSummarySessionCompactBefore forwardTo compressHistory)
            edge(compressHistory forwardTo chatWithSummarySessionCompactAfter)
            edge(chatWithSummarySessionCompactAfter forwardTo nodeSendToolResult)
        } else {
            edge(nodeExecuteTool forwardTo nodeSendToolResult)
        }

        edge(nodeSendToolResult forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })

        // 🔑 关键：在工具执行后生成总结
        edge(nodeSendToolResult forwardTo nodeGenerateSummary onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { _ -> "" })

        // 生成总结后结束对话
        edge(nodeGenerateSummary forwardTo nodeFinish onCondition { _ -> true } transformed { _ -> "" })

    }

/**
 * 持续对话策略 - 用户可以一直提问直到主动终止
 * 核心思路：
 * 1. 移除自动结束逻辑
 * 2. 添加用户主动结束对话的机制
 * 3. 添加历史压缩功能，避免对话过长影响性能
 * 4. 完全自由对话，不约束AI的表达方式
 *
 * graph TD
 *    Start([nodeStart]) --> CallLLM[nodeCallLLM<br/>LLM请求处理]
 *
 *    CallLLM --> |调用工具| ExecuteTool[nodeExecuteTool<br/>执行工具]
 *    CallLLM --> |纯文本回复| GiveFeedback[giveFeedbackToCallTools<br/>提示必须使用工具]
 *
 *    GiveFeedback --> |继续文本回复| GiveFeedback
 *    GiveFeedback --> |调用工具| ExecuteTool
 *
 *    ExecuteTool --> |ExitTool| Finish([nodeFinish])
 *    ExecuteTool --> |其他工具| LogResult[nodeLogToolResult<br/>记录工具结果]
 *
 *    LogResult --> SendResult[nodeSendToolResult<br/>发送工具结果]
 *
 *    SendResult --> |继续调用工具| ExecuteTool
 *    SendResult --> |普通消息回复| CallLLM
 */
fun continuousChatStrategy(
    historyMessages: List<Map<String, String>> = emptyList(),
    historyCompression: HistoryCompressionConfig? = null,
    skillManager: SkillManager? = null,
    sessionKey: String = "",
) =
    strategy<String, String>("continuousChat") {
        val restoreFromHistory by restoreHistoryNode(historyMessages)
        val nodeCallLLM by nodeLLMRequestPreservingDeepSeekReasoning("sendInput")
        val nodeExecuteTool by nodeExecuteTool("nodeExecuteTool")
        val nodeSendToolResult by nodeLLMSendToolResultsPreservingDeepSeekReasoning("nodeSendToolResult")

        // 添加工具结果记录节点(批量:一次 LLM 回复可能并行调用多个工具)
        val nodeLogToolResult by node<ReceivedToolResults, ReceivedToolResults> { toolResults ->
            toolResults.toolResults.forEach { toolResult ->
                // 记录所有工具的执行结果
                val toolName = toolResult.tool
                // Koog 1.0.0 — `ReceivedToolResult.content` was the 0.x property;
                // 1.0.0 renamed it to `.output` (alongside `.result: JSONElement?`
                // for the typed return value, see ReceivedToolResult.kt).
                val resultContent = toolResult.output

                // 简单判断是否为 MCP 工具（基于工具名称包含 "mcp"）
                val isMcpTool = toolName.contains("mcp", ignoreCase = true)

                if (isMcpTool) {
                    printlnColor(AnsiColor.BOLD + AnsiColor.GREEN, "\n✅ MCP工具执行完成: $toolName")
                    if (resultContent.isNotEmpty()) {
                        printlnColor(AnsiColor.GREEN, "📤 执行结果:")
                        val displayResult = if (resultContent.length > 500) {
                            resultContent.take(500) + "..."
                        } else {
                            resultContent
                        }
                        printlnColor(AnsiColor.GREEN, "   $displayResult")
                    } else {
                        printlnColor(AnsiColor.GREEN, "📤 执行结果: <无返回内容>")
                    }
                }
            }

            // 返回原始结果，继续流程
            toolResults
        }

        // 提示 LLM 必须用工具
        val giveFeedbackToCallTools by node<String, Message.Assistant> { _ ->
            llm.writeSession {
                appendPrompt {
                    user(
                        "Don't chat with plain text! Call one of the available tools, instead: " +
                            tools.joinToString(", ") { it.name }
                    )
                }
                requestLLMPreservingDeepSeekReasoning()
            }
        }


        var continuousChatFeedbackAttempts = 0
        val maxFeedbackAttempts = 3

        // 主流程
        edge(nodeStart forwardTo restoreFromHistory)
        edge(restoreFromHistory forwardTo nodeCallLLM)

        // Koog 1.0.0 — `onToolCall { predicate }` / `onAssistantMessage`
        // removed (see [AgentSubGraphUtils] for the full migration story).
        // The DSL replacement uses `onCondition` over `msg.parts` for type
        // pass-through.
        edge(nodeCallLLM forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })
        edge(nodeCallLLM forwardTo giveFeedbackToCallTools onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { msg -> msg.textContent() })

        // 反馈循环 (max 3 retries to prevent infinite loop)
        edge(giveFeedbackToCallTools forwardTo giveFeedbackToCallTools onCondition { msg ->
            msg.parts.any { it is MessagePart.Text } && ++continuousChatFeedbackAttempts < maxFeedbackAttempts
        } transformed { msg -> msg.textContent() })
        edge(giveFeedbackToCallTools forwardTo nodeFinish onCondition { msg ->
            msg.parts.any { it is MessagePart.Text } && continuousChatFeedbackAttempts >= maxFeedbackAttempts
        } transformed { _ -> "" })
        edge(giveFeedbackToCallTools forwardTo nodeExecuteTool onCondition { msg ->
            val callsTool = msg.parts.any { it is MessagePart.Tool.Call }
            // 反馈预算按"回合"计,回到正常工具调用后重置(否则是会话终身上限)。
            if (callsTool) continuousChatFeedbackAttempts = 0
            callsTool
        })

        // 🔑 特殊结束条件：当 ExitTool (__exit__) 执行完成时结束对话（优先级最高）
        edge(nodeExecuteTool forwardTo nodeFinish onCondition { results ->
            results.toolResults.any { it.tool == "__exit__" }
        } transformed { "" })

        // 执行其他工具后先记录结果
        edge(nodeExecuteTool forwardTo nodeLogToolResult onCondition { results ->
            results.toolResults.none { it.tool == "__exit__" }
        })

        if (historyCompression != null) {
            val compressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<ReceivedToolResults>(
                name = "__continuous_chat_compress_history__",
                historyCompression = historyCompression,
            )
            val continuousChatSessionCompactBefore by node<ReceivedToolResults, ReceivedToolResults>("__continuous_chat_session_compact_before__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
                input
            }
            val continuousChatSessionCompactAfter by node<ReceivedToolResults, ReceivedToolResults>("__continuous_chat_session_compact_after__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
                input
            }
            edge(nodeLogToolResult forwardTo continuousChatSessionCompactBefore)
            edge(continuousChatSessionCompactBefore forwardTo compressHistory)
            edge(compressHistory forwardTo continuousChatSessionCompactAfter)
            edge(continuousChatSessionCompactAfter forwardTo nodeSendToolResult)
        } else {
            edge(nodeLogToolResult forwardTo nodeSendToolResult)
        }

        // 🔑 工具执行后继续调用工具
        edge(nodeSendToolResult forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })

        // 🔑 工具执行后继续对话循环
        edge(nodeSendToolResult forwardTo nodeCallLLM onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { msg -> msg.textContent() })

    }


fun toneStrategy(
    name: String,
    historyCompression: HistoryCompressionConfig? = null,
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
): AIAgentGraphStrategy<String, String> {
    return strategy(name) {
        val restoreFromHistory by restoreHistoryNode(historyMessages)
        val nodeSendInput by nodeLLMRequestPreservingDeepSeekReasoning()
        val nodeExecuteTool by nodeExecuteTool()
        val nodeSendToolResult by nodeLLMSendToolResultsPreservingDeepSeekReasoning()

        // Define the flow of the agent
        edge(nodeStart forwardTo restoreFromHistory)
        edge(restoreFromHistory forwardTo nodeSendInput)

        // If the LLM responds with a message, finish
        edge(
            (nodeSendInput forwardTo nodeFinish) onCondition { msg ->
                msg.parts.any { it is MessagePart.Text }
            } transformed { msg -> msg.textContent() }
        )

        // If the LLM calls a tool, execute it
        edge(
            (nodeSendInput forwardTo nodeExecuteTool) onCondition { msg ->
                msg.parts.any { it is MessagePart.Tool.Call }
            }
        )

        if (historyCompression != null) {
            // 使用配置指定的压缩策略，每次工具执行后无条件压缩
            val nodeCompressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<ReceivedToolResults>(
                name = "__tone_compress_history__",
                historyCompression = historyCompression,
            )
            val toneSessionCompactBefore by node<ReceivedToolResults, ReceivedToolResults>("__tone_session_compact_before__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
                input
            }
            val toneSessionCompactAfter by node<ReceivedToolResults, ReceivedToolResults>("__tone_session_compact_after__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
                input
            }
            edge(nodeExecuteTool forwardTo toneSessionCompactBefore)
            edge(toneSessionCompactBefore forwardTo nodeCompressHistory)
            edge(nodeCompressHistory forwardTo toneSessionCompactAfter)
            edge(toneSessionCompactAfter forwardTo nodeSendToolResult)
        } else {
            edge(nodeExecuteTool forwardTo nodeSendToolResult)
        }

        // If the LLM calls another tool, execute it
        edge(
            (nodeSendToolResult forwardTo nodeExecuteTool) onCondition { msg ->
                msg.parts.any { it is MessagePart.Tool.Call }
            }
        )

        // If the LLM responds with a message, finish
        edge(
            (nodeSendToolResult forwardTo nodeFinish) onCondition { msg ->
                msg.parts.any { it is MessagePart.Text }
            } transformed { msg -> msg.textContent() }
        )
    }
}

/**
 * Tone strategy with an explicit reasoning step before each LLM action call.
 * Mirrors [toneStrategy] but inserts a reasoning phase (using [requestLLMWithoutTools])
 * between the user input / tool results and the actual tool-selection LLM call.
 *
 * @param name Strategy name.
 * @param historyCompression Optional history compression configuration.
 * @param reasoningInterval How many tool-execution cycles between reasoning steps. Default 1.
 * @param showReasoning Whether to perform and display reasoning. Default true.
 */
fun toneStrategyWithReasoning(
    name: String,
    historyCompression: HistoryCompressionConfig? = null,
    reasoningInterval: Int = 1,
    showReasoning: Boolean = true,
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    onReasoningMessage: ReasoningMonitorCallback? = null,
): AIAgentGraphStrategy<String, String> {
    return strategy(name) {
        val reasoningStepKey = createStorageKey<Int>("tone_reasoning_step")
        val reasoningPrompt = "Please give your thoughts about the task and plan the next steps."
        val continuationPrompt =
            "Based on your analysis above, now proceed to execute the plan. Call the appropriate tool for the next step."

        // 接收输入，执行初始推理，然后交给 nodeCallLLM
        val nodeReasonInput by node<String, Unit> { stageInput ->
            storage.set(reasoningStepKey, 0)
            llm.writeSession {
                appendPrompt { user(stageInput) }
                executeReasoningStep(
                    requestLLMWithoutToolsFn = { requestLLMWithoutToolsPreservingDeepSeekReasoning() },
                    appendPromptFn = { msg -> appendPrompt { user(msg) } },
                    reasoningPrompt = reasoningPrompt,
                    continuationPrompt = continuationPrompt,
                    showReasoning = showReasoning,
                    onReasoningMessage = onReasoningMessage
                )
            }
        }

        // 实际 LLM 调用（工具选择）
        val nodeCallLLM by node<Unit, Message.Assistant> {
            llm.writeSession {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMPreservingDeepSeekReasoning()
                }
            }
        }

        val nodeExecuteTool by nodeExecuteTool()

        // 工具执行后：将工具结果追加到 prompt，执行推理，然后交给 nodeCallLLM 继续
        // 注意：这里直接将工具结果写入 prompt 并路由到 nodeCallLLM，
        // 而非经过 nodeSendToolResult，以避免工具结果被重复追加。
        val nodeReasonAfterTool by node<ReceivedToolResults, Unit> { results ->
            val reasoningStep = storage.getValue(reasoningStepKey)
            val shouldRunReasoning = showReasoning && reasoningStep % reasoningInterval == 0
            llm.writeSession {
                appendPrompt {
                    // Koog 1.0.0 — `tool { result(toolResult) }` PromptBuilder block
                    // gone; tool results live as MessagePart.Tool.Result inside a
                    // user message via the `toolResult(part)` builder fn.
                    // 批量追加:一次回复可能并行调用多个工具。
                    user { results.toolResults.forEach { toolResult(it.toMessagePart()) } }
                }
                if (shouldRunReasoning) {
                    executeReasoningStep(
                        requestLLMWithoutToolsFn = { requestLLMWithoutToolsPreservingDeepSeekReasoning() },
                        appendPromptFn = { msg -> appendPrompt { user(msg) } },
                        reasoningPrompt = reasoningPrompt,
                        continuationPrompt = continuationPrompt,
                        showReasoning = showReasoning,
                        onReasoningMessage = onReasoningMessage
                    )
                } else {
                    appendPrompt { user(TOOL_RESULTS_CONTINUATION_PROMPT) }
                }
            }
            storage.set(reasoningStepKey, reasoningStep + 1)
        }

        val restoreFromHistory by restoreHistoryNode(historyMessages)

        edge(nodeStart forwardTo restoreFromHistory)
        edge(restoreFromHistory forwardTo nodeReasonInput)
        edge(nodeReasonInput forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeFinish onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { msg -> msg.textContent() })
        edge(nodeCallLLM forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })

        if (historyCompression != null) {
            val nodeCompressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<ReceivedToolResults>(
                name = "__tone_reasoning_compress_history__",
                historyCompression = historyCompression,
            )
            val toneReasoningSessionCompactBefore by node<ReceivedToolResults, ReceivedToolResults>("__tone_reasoning_session_compact_before__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
                input
            }
            val toneReasoningSessionCompactAfter by node<ReceivedToolResults, ReceivedToolResults>("__tone_reasoning_session_compact_after__") { input ->
                skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
                input
            }
            edge(nodeExecuteTool forwardTo toneReasoningSessionCompactBefore)
            edge(toneReasoningSessionCompactBefore forwardTo nodeCompressHistory)
            edge(nodeCompressHistory forwardTo toneReasoningSessionCompactAfter)
            edge(toneReasoningSessionCompactAfter forwardTo nodeReasonAfterTool)
        } else {
            edge(nodeExecuteTool forwardTo nodeReasonAfterTool)
        }

        // nodeReasonAfterTool 已将工具结果写入 prompt 并完成推理，直接路由到 nodeCallLLM 发起下一次工具调用
        edge(nodeReasonAfterTool forwardTo nodeCallLLM)
    }
}

fun List<ConfigurationParameter>.toneReasoningStrategy(
    historyMessages: List<Map<String, String>> = loadHistoryMessages(this),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    onReasoningMessage: ReasoningMonitorCallback? = null,
) =
    toneStrategyWithReasoning(
        name = parameter("strategy_name", "ToneReasoning"),
        historyCompression = parameter("history_compression", null),
        reasoningInterval = parameter("reasoning_interval", 1),
        showReasoning = parameter("show_reasoning", true),
        historyMessages = historyMessages,
        skillManager = skillManager,
        sessionKey = sessionKey,
        onReasoningMessage = onReasoningMessage,
    )

@Serializable
@SerialName("SuggestedPlan")
@LLMDescription("A Structure containing a list of step-by-step plans to achieve the goal. The list should be empty if no suggested actions are available")
data class SuggestedPlan(
    @property:LLMDescription("List of step-by-step plans to achieve the goal")
    val steps: List<String>,
    @property:LLMDescription("List of optional steps that can be skipped if necessary. The list should be empty if no optional skippable steps are available")
    val optionalSteps: List<Int>,
    @property:LLMDescription("The verification action to take when all steps are done")
    val verificationActionWhenAllStepsAreDone: String,
    @property:LLMDescription("The list of optional next tasks to be completed after the goal is achieved")
    val optionalNextTasks: List<String> = emptyList()
)

private val exampleSuggestedPlan1_WebResearchReport = SuggestedPlan(
    steps = listOf(
        "1. Use the web search tool to look up recent news and data on the requested topic",
        "2. Use the web search tool to find additional background information and authoritative sources",
        "3. Use the read_url tool to retrieve full content from the most relevant URLs found",
        "4. Synthesize the collected information and use the write_file tool to save a structured Markdown report",
        "5. Use the read_file tool to verify the saved report content is complete and well-formatted"
    ),
    optionalSteps = listOf(5),
    verificationActionWhenAllStepsAreDone = "Confirm the report file exists and contains all required sections with cited sources",
    optionalNextTasks = listOf(
        "Translate the report into another language if requested",
        "Generate a summary slide deck from the report content"
    )
)

private val exampleSuggestedPlan2_DataAnalysis = SuggestedPlan(
    steps = listOf(
        "1. Use the read_file tool to load the target dataset (CSV/JSON)",
        "2. Use the execute_code tool to inspect data shape, column types, and check for missing values",
        "3. Use the execute_code tool to compute descriptive statistics and identify key trends",
        "4. Use the execute_code tool to generate charts and save them as image files",
        "5. Use the write_file tool to save a summary report with findings and chart references"
    ),
    optionalSteps = listOf(4),
    verificationActionWhenAllStepsAreDone = "Verify the summary report contains correct statistics and all referenced chart files exist",
    optionalNextTasks = listOf(
        "Build a predictive model based on the analysis findings",
        "Share the report via email tool"
    )
)

private val exampleSuggestedPlan3_ProgrammingTask = SuggestedPlan(
    steps = listOf(
        "1. Use the read_file tool to understand the existing codebase structure and locate relevant source files",
        "2. Use the execute_code tool to run the existing test suite and record which tests pass or fail",
        "3. Use the write_file tool to create or update the implementation file with the required feature code",
        "4. Use the write_file tool to add unit tests covering the new feature, edge cases, and failure scenarios",
        "5. Use the execute_code tool to run the updated test suite and confirm all tests pass"
    ),
    optionalSteps = listOf(),
    verificationActionWhenAllStepsAreDone = "All tests pass and the new feature behaves as specified in the requirements",
    optionalNextTasks = listOf(
        "Update the project README or API documentation to reflect the new feature",
        "Use the git_commit tool to commit the changes with a descriptive message"
    )
)

private val exampleSuggestedPlan4_GenerateDocumentTask = SuggestedPlan(
    steps = listOf(
        "1. Use the read_file tool to load any provided source material, templates, or reference data",
        "2. Use the generate_document tool (or write_file tool) to create the document with required content sections",
        "3. Use the execute_code tool or format_document tool to apply styling, tables, and formatting as needed",
        "4. Use the read_file tool to verify the generated document content is complete and correct",
        "5. Use the send_email tool or upload_file tool to deliver the document to the intended recipient"
    ),
    optionalSteps = listOf(5),
    verificationActionWhenAllStepsAreDone = "The generated document contains all required sections, correct formatting, and has been delivered successfully"
)

private val exampleSuggestedPlan5_RefactoryCodeTask = SuggestedPlan(
    steps = listOf(
        "1. Review the existing codebase to understand its structure and functionality",
        "2. Identify areas of the code that can be improved for readability, maintainability, and performance",
        "3. Create a backup of the current codebase before making any changes",
        "4. Start refactoring by breaking down large functions into smaller, more manageable ones",
        "5. Rename variables and functions to have meaningful and descriptive names",
        "6. Remove any duplicate or redundant code",
        "7. Optimize algorithms and data structures for better performance",
        "8. Ensure that the code follows consistent coding standards and style guidelines",
        "9. Write unit tests to cover the refactored code and ensure its correctness",
        "10. Review the changes and test the entire application to ensure that it still works as expected"
    ),
    optionalSteps = listOf(2, 3),
    verificationActionWhenAllStepsAreDone = "Run the full test suite to ensure all tests pass and the application functions correctly after refactoring",
    optionalNextTasks = listOf(
        "Go to the pull request page and click on \"Details\" to see the test results",
        "Read the test results and fix any errors",
        "Push your changes to your GitHub repository"
    )
)

private val emptySuggestedPlan = SuggestedPlan(
    steps = listOf(),
    optionalSteps = listOf(),
    verificationActionWhenAllStepsAreDone = "no verification action should be taken for this empty plan"
)

/**
 * Constructs a subgraph for planning a sequence of actions and connections to achieve a goal
 * using AI-based suggestion mechanisms.
 *
 * @param name The name of the subgraph. Defaults to "__plan_graph__".
 */
fun AIAgentSubgraphBuilderBase<*, *>.planGraph(
    name: String = "__plan_graph__",
    tools: List<Tool<*, *>>
) = subgraph<String, SuggestedPlan>(
    name = name,
    tools = tools
) {
    val getPlan by structureGraph<String, SuggestedPlan>(
        name = "__get_plan__",
        emptyValue = emptySuggestedPlan,
        structureExamples = listOf(
            exampleSuggestedPlan1_WebResearchReport,
            exampleSuggestedPlan2_DataAnalysis,
            exampleSuggestedPlan3_ProgrammingTask,
            exampleSuggestedPlan4_GenerateDocumentTask,
            exampleSuggestedPlan5_RefactoryCodeTask
        ),
        structureFixingParser = DEFAULT_FIXING_PARSER
    )
    nodeStart then getPlan then nodeFinish
}

/**
 * Plans and executes a strategy to solve a series of steps by leveraging tools, attachments, and history compression.
 *
 * @param name The name of the strategy to be planned and executed.
 * @param tools A registry of tools available for executing the strategy.
 * @param attachments A list of attachment files (optional) to support the execution of the strategy.
 * @param historyCompression The configuration for compressing historical data to optimize token usage during execution. Null by default.
 * @param stepStatusUpdater A callback function to update the status of individual steps. It takes the following parameters:
 *        - plan: The suggested plan, if applicable.
 *        - step: The current step being processed.
 *        - stepResult: The result of the current step execution.
 *        The default implementation is an empty lambda.
 * @return A graph strategy that defines the steps and connections required to execute the planned strategy.
 */
fun planSolveStrategy(
    name: String,
    tools: ToolRegistry,
    attachments: List<AttachmentFile> = emptyList(),
    historyCompression: HistoryCompressionConfig? = null,
    stepStatusUpdater: (plan: SuggestedPlan?, step: String, stepResult: String) -> Unit = { _, _, _ -> },
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
): AIAgentGraphStrategy<String, String> {
    return strategy(name) {

        val steps = mutableListOf<String>()
        var currentStep: String? = null

        val restoreFromHistory by restoreHistoryNode(historyMessages)
        // Koog 1.0.0 — `ToolRegistry.tools` widened to `List<ToolBase<*, *>>`;
        // `planGraph` / `toolCycleGraph` still expect `List<Tool<*, *>>`
        // (the metadata-less convenience subclass). Filter to Tool here since
        // every @Tool-annotated method in braidrun-agent compiles to a Tool
        // subclass; pure-ToolBase instances (AgentContextAwareTool, etc.)
        // would drop out, and we have none.
        val toolList = tools.tools.filterIsInstance<Tool<*, *>>()
        val getSuggestedPlan by planGraph(tools = toolList)

        val takeStep by node<List<String>, String> { _ ->
            if (steps.isEmpty()) {
                currentStep = null
                return@node "__finish all steps__"
            }

            steps.removeFirst().also { currentStep = it }
        }

        val solveOneStepSubGraph by toolCycleGraph<String, String>(
            name = "__plan_solve_one_step__",
            inputPrompt = { step, _ -> step },
            // Koog 1.0.0 — `.content` accessor gone; use `.textContent()`.
            outputCompute = { resp, _ -> resp.textContent() },
            tools = toolList,
            attachments = attachments,
            historyCompression = historyCompression,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        val completedStepResults = mutableListOf<String>()

        edge(nodeStart forwardTo restoreFromHistory)
        edge(restoreFromHistory forwardTo getSuggestedPlan)
        edge(getSuggestedPlan forwardTo takeStep transformed { plan ->
            stepStatusUpdater(plan, "", "")
            // 防御:strategy 实例可能被复用,清掉上一轮残留的步骤/结果。
            steps.clear()
            completedStepResults.clear()
            plan.steps.forEach { steps.add(it) }
            steps
        })
        edge(takeStep forwardTo solveOneStepSubGraph onCondition { it != "__finish all steps__" })
        edge(solveOneStepSubGraph forwardTo takeStep transformed { stepResult ->
            currentStep?.let { step -> stepStatusUpdater(null, step, stepResult) }
            if (stepResult.isNotBlank()) completedStepResults.add(stepResult)
            steps
        })
        // 返回最后一个 step 的实际输出而不是内部哨兵串 "__finish all steps__":
        // 哨兵会被存入会话历史并作为 agent 最终输出返回给调用方。
        edge(takeStep forwardTo nodeFinish onCondition { it == "__finish all steps__" } transformed {
            completedStepResults.lastOrNull() ?: "Plan completed with no executable steps."
        })
    }
}

/**
 * Plan-and-solve strategy with an explicit reasoning step before each individual step execution.
 * The agent first generates a structured plan, then solves each step one by one, inserting a
 * reasoning phase (using [requestLLMWithoutTools]) before handing off to the tool-execution sub-graph.
 *
 * @param name The name of the strategy.
 * @param tools A registry of tools available for executing each step.
 * @param attachments Optional attachment files to include in LLM prompts.
 * @param historyCompression Optional configuration for compressing history to reduce token usage.
 * @param showReasoning Whether to perform and display the reasoning phase. Defaults to true.
 * @param stepStatusUpdater Callback invoked when a plan is generated or a step completes.
 * @return A graph strategy that plans, reasons, and solves each step sequentially.
 */
fun planSolveStrategyWithReasoning(
    name: String,
    tools: ToolRegistry,
    attachments: List<AttachmentFile> = emptyList(),
    historyCompression: HistoryCompressionConfig? = null,
    showReasoning: Boolean = true,
    stepStatusUpdater: (plan: SuggestedPlan?, step: String, stepResult: String) -> Unit = { _, _, _ -> },
    historyMessages: List<Map<String, String>> = emptyList(),
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    onReasoningMessage: ReasoningMonitorCallback? = null,
): AIAgentGraphStrategy<String, String> {
    return strategy(name) {

        val steps = mutableListOf<String>()
        var currentStep: String? = null

        val reasoningPrompt =
            "Think through the current step carefully: what information do you already have, what tool should you call next, and what outcome do you expect?"
        val continuationPrompt =
            "Based on your analysis above, now proceed to execute this step using the appropriate tool."

        // Same Koog 1.0.0 ToolBase narrowing + textContent migration as planSolveStrategy above.
        val toolList = tools.tools.filterIsInstance<Tool<*, *>>()
        val getSuggestedPlan by planGraph(tools = toolList)

        // Dequeue the next step and optionally run a reasoning pass before handing off
        val takeStep by node<List<String>, String> { _ ->
            if (steps.isEmpty()) {
                currentStep = null
                return@node "__finish all steps__"
            }
            steps.removeFirst().also { currentStep = it }
        }

        // Reasoning node: calls LLM without tools to think about the step before executing
        val nodeReasonBeforeStep by node<String, String> { step ->
            llm.writeSession {
                executeReasoningStep(
                    requestLLMWithoutToolsFn = { requestLLMWithoutToolsPreservingDeepSeekReasoning() },
                    appendPromptFn = { msg -> appendPrompt { user(msg) } },
                    reasoningPrompt = reasoningPrompt,
                    continuationPrompt = continuationPrompt,
                    showReasoning = showReasoning,
                    onReasoningMessage = onReasoningMessage
                )
            }
            step
        }

        val solveOneStepSubGraph by toolCycleGraph<String, String>(
            name = "__plan_solve_reasoning_one_step__",
            inputPrompt = { step, _ -> step },
            outputCompute = { resp, _ -> resp.textContent() },
            tools = toolList,
            attachments = attachments,
            historyCompression = historyCompression,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        val restoreFromHistory by restoreHistoryNode(historyMessages)

        val completedStepResults = mutableListOf<String>()

        edge(nodeStart forwardTo restoreFromHistory)
        edge(restoreFromHistory forwardTo getSuggestedPlan)
        edge(getSuggestedPlan forwardTo takeStep transformed { plan ->
            stepStatusUpdater(plan, "", "")
            // 防御:strategy 实例可能被复用,清掉上一轮残留的步骤/结果。
            steps.clear()
            completedStepResults.clear()
            plan.steps.forEach { steps.add(it) }
            steps
        })
        edge(takeStep forwardTo nodeReasonBeforeStep onCondition { it != "__finish all steps__" })
        edge(nodeReasonBeforeStep forwardTo solveOneStepSubGraph)
        edge(solveOneStepSubGraph forwardTo takeStep transformed { stepResult ->
            currentStep?.let { step -> stepStatusUpdater(null, step, stepResult) }
            if (stepResult.isNotBlank()) completedStepResults.add(stepResult)
            steps
        })
        // 同 planSolveStrategy:不要把内部哨兵串作为最终输出。
        edge(takeStep forwardTo nodeFinish onCondition { it == "__finish all steps__" } transformed {
            completedStepResults.lastOrNull() ?: "Plan completed with no executable steps."
        })
    }
}


/**
 * 通用的历史消息恢复节点构建器
 * 
 * 从历史消息列表中恢复对话上下文
 * 
 * @param historyMessages 历史消息列表，每个消息包含 "role" 和 "content" 字段
 * @param nodeName 节点名称，默认为 "restoreFromHistory"
 * @return 节点委托属性
 * 
 * 使用示例:
 * ```kotlin
 * val restoreFromHistory by restoreHistoryNode(historyMessages)
 * ```
 */
fun AIAgentSubgraphBuilderBase<*, *>.restoreHistoryNode(
    historyMessages: List<Map<String, String>>,
    nodeName: String = "restoreFromHistory"
) = node<String, String>(nodeName) { input ->
    llm.writeSession {
        if (historyMessages.isNotEmpty()) {
            appendPrompt {
                historyMessages.forEach { msg ->
                    when (msg["role"]?.lowercase()) {
                        "user" -> user(msg["content"] ?: "")
                        "assistant" -> assistant(msg["content"] ?: "")
                        else -> system(msg["content"] ?: "")
                    }
                }
            }
        }
    }
    input
}

/**
 * 通用的流式聊天策略
 * 
 * 这是一个完整的流式聊天策略，支持：
 * - 历史消息恢复
 * - 流式响应输出
 * - 工具调用
 * - 多轮对话
 * 
 * 流程图：
 * ```
 * nodeStart
 *   → restoreFromHistory (恢复历史消息)
 *   → streamChat (流式聊天子图)
 *      ├─→ [ToolCall] → nodeExecuteTool (执行工具)
 *      │                  → streamSendToolResult (流式发送工具结果)
 *      │                     ├─→ [ToolCall] → nodeExecuteTool (继续调用工具)
 *      │                     └─→ [AssistantMessage] → nodeFinish
 *      └─→ [AssistantMessage] → nodeFinish
 * ```
 * 
 * @param historyMessages 历史消息列表，用于恢复对话上下文
 * @param onChunk 流式输出回调函数，用于实时处理文本块
 * @return 配置好的流式聊天策略
 * 
 * 使用示例:
 * ```kotlin
 * buildStreamChatStrategy(
 *     historyMessages = loadedHistory,
 *     onChunk = { text -> println(text) }
 * )
 * ```
 */
fun buildStreamChatStrategy(
    historyMessages: List<Map<String, String>> = emptyList(),
    sessionId: String,
    onChunk: (String) -> Unit = {}
): AIAgentGraphStrategy<String, String> = strategy("stream-chat-dag") {

    // ========== 节点定义 ==========

    // 1. 恢复点节点 - 从持久化存储中恢复历史会话状态
    //
    // 功能说明：
    // - 尝试从持久化存储中恢复指定会话的历史状态
    // - 如果恢复成功，LLM 将能够访问之前的对话上下文
    // - 如果恢复失败（新会话或恢复点不存在），会静默继续，不影响后续流程
    //
    // 注意事项：
    // - persistence() 会自动使用 agentId (即 sessionId) 来查询对应会话的检查点
    // - 恢复操作是幂等的，多次调用不会产生副作用
    // - 新会话时恢复失败是正常情况，不需要特殊处理
    @Suppress("UNUSED_VARIABLE", "unused")
    val restoreFromCheckpoint by node<String, String>(
        name = "__restore_checkpoint__"
    ) { input ->
        try {
            persistence().rollbackToLatestCheckpoint(this)
        } catch (e: Exception) {
            // 新会话或恢复点不存在时，静默继续
            // 这是正常情况，不需要抛出异常
        }
        // 返回原始输入，继续后续流程
        input
    }

    // 1. 恢复历史消息
    val restoreFromHistory by restoreHistoryNode(historyMessages)

    // 2. 流式聊天子图 - 处理用户输入并生成流式响应
    val streamChat by subgraph<String, Message.Assistant>("stream_subgraph") {
        // 2.1 流式 LLM 请求节点：产生 Flow<StreamFrame>
        val streamNode by nodeLLMRequestStreaming("__stream_chat_stream__") { flow ->
            flow.onEach { frame ->
                if (frame is StreamFrame.TextDelta) {
                    onChunk(frame.text)
                }
            }
        }
        // 2.2 流式收集节点：收集流式帧并转换为 Message.Assistant
        val collectNode by streamCollectNode("__stream_collect__")

        // 子图流程：输入 → 流式请求 → 收集响应 → 输出
        nodeStart then streamNode then collectNode then nodeFinish
    }

    // 3. 工具执行节点 - 执行 LLM 请求的工具调用
    val nodeExecuteTool by nodeExecuteTool("__nodeExecuteTool__")

    // 4. 流式发送工具结果子图 - 将工具执行结果发送给 LLM 并获取流式响应
    val streamSendToolResult by subgraph<ReceivedToolResults, Message.Assistant>("stream_send_tool_result_subgraph") {
        // 4.1 流式工具结果节点：将工具结果写入会话并产生 Flow<StreamFrame>
        val streamToolResultNode by node<ReceivedToolResults, kotlinx.coroutines.flow.Flow<StreamFrame>>("__stream_tool_result_stream__") { results ->
            llm.writeSession {
                appendPrompt {
                    // Koog 1.0.0 — `tool { result(toolResult) }` → `user { toolResult(part) }`
                    // 批量追加:一次回复可能并行调用多个工具。
                    user { results.toolResults.forEach { toolResult(it.toMessagePart()) } }
                }
                requestLLMStreaming()
            }.onEach { frame ->
                if (frame is StreamFrame.TextDelta) {
                    onChunk(frame.text)
                }
            }
        }

        // 4.2 流式收集节点：收集流式帧并转换为 Message.Assistant
        val collectToolResultNode by streamCollectNode("__stream_tool_result_collect__")

        // 子图流程：工具结果 → 流式请求 → 收集响应 → 输出
        nodeStart then streamToolResultNode then collectToolResultNode then nodeFinish
    }

    // 5. 持久化节点 - 创建恢复点保存当前会话状态
    //
    // 功能说明：
    // - 在每次生成完整响应后执行，保存当前会话状态以便后续恢复
    // - 使用 sessionId 作为 checkpointId，version 作为时间戳来区分不同版本的检查点
    // 注意事项：
    // - 每次对话都会创建新的检查点记录，不会覆盖之前的记录
    // - 恢复时会自动获取最新的检查点（按创建时间排序）
    // - 如果需要清理旧检查点，需要在应用层实现清理逻辑
    @Suppress("unused", "UNUSED_VARIABLE")
    val createCheckpointNode by node<String, String>(
        name = "__persistence__"
    ) { input ->
        val checkpoint = withPersistence {
            // Koog 1.0.0 renamed `createCheckpoint(agentContext, nodePath,
            // lastInput, lastInputType, checkpointId, version)` to
            // `createCheckpointAfterNode(agentContext, nodePath, lastOutput,
            // lastOutputType, version, checkpointId)`. Semantically equivalent
            // (the snapshot still captures messageHistory + storage at the
            // named node boundary); the parameter renames reflect the new
            // `GraphCheckpointProperties.lastOutput` field shape.
            createCheckpointAfterNode(
                agentContext = it,
                nodePath = "__persistence__",
                lastOutput = input,
                lastOutputType = TypeToken.of(String::class.java),
                version = System.currentTimeMillis(),  // 版本号，确保每次都是新记录
                checkpointId = it.runId,  // 会话级别的检查点 ID
            )
        }

        // 返回空字符串，表示对话已完成
        ""
    }

    // ========== DAG 路由配置 ==========

    // 主流程：启动 → 恢复历史 → 流式聊天
    nodeStart then restoreFromHistory then streamChat

    // streamChat 子图的路由：
    // - 如果 LLM 请求调用工具 → 执行工具
    edge(streamChat forwardTo nodeExecuteTool onCondition { msg ->
        msg.parts.any { it is MessagePart.Tool.Call }
    })
    // - 如果 LLM 生成文本响应 → 结束
    edge(streamChat forwardTo nodeFinish onCondition { msg ->
        msg.parts.any { it is MessagePart.Text }
    } transformed { msg -> msg.textContent() })

    // 工具执行后的路由：工具结果 → 流式发送给 LLM
    edge(nodeExecuteTool forwardTo streamSendToolResult)

    // streamSendToolResult 子图的路由：
    // - 如果 LLM 再次请求工具 → 继续执行工具（形成工具调用循环）
    edge(streamSendToolResult forwardTo nodeExecuteTool onCondition { msg ->
        msg.parts.any { it is MessagePart.Tool.Call }
    })
    // - 如果 LLM 生成文本响应 → 结束
    edge(streamSendToolResult forwardTo nodeFinish onCondition { msg ->
        msg.parts.any { it is MessagePart.Text }
    } transformed { msg -> msg.textContent() })
}
