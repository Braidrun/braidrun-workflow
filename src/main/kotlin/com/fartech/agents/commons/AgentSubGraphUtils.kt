package com.fartech.agents.commons

import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.environment.ReceivedToolResult
// Koog 1.0.0 — `ai.koog.agents.core.environment.result` extension is gone;
// `ReceivedToolResult.toMessagePart()` replaces it (used inline below).
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.prompt.dsl.ContentPartsBuilderBase
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.structure.StructuredRequest
import ai.koog.prompt.structure.StructuredRequestConfig
import ai.koog.prompt.structure.json.JsonStructure
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.JsonSchemaGenerator
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * 将 [HistoryCompressionConfig] 转换为 Koog 的 [HistoryCompressionStrategy]。
 */
fun HistoryCompressionConfig.toStrategy(): HistoryCompressionStrategy = when (strategy) {
    "whole_history_multi_system" -> HistoryCompressionStrategy.WholeHistoryMultipleSystemMessages
    "from_last_n" -> HistoryCompressionStrategy.FromLastNMessages(n)
    "chunked" -> HistoryCompressionStrategy.Chunked(chunkSize)
    else -> HistoryCompressionStrategy.WholeHistory
}

@Serializable
@LLMDescription("A structure describes attachment file information")
data class AttachmentFile(
    @property:LLMDescription("The name of the attachment file")
    val fileType: FileType = FileType.IMAGE,
    @property:LLMDescription("The MIME type of the attachment file")
    val getMethod: GetMethod = GetMethod.URL,
    @property:LLMDescription("The file path or URL of the attachment file")
    val content: String = ""
) {
    @Serializable
    @LLMDescription("how to access this attachment file")
    enum class GetMethod {
        @LLMDescription("The attachment file is located at a URL and content value is the URL")
        URL,

        @LLMDescription("The attachment file is a local file and content value is the file path")
        FILE
    }

    @Serializable
    @LLMDescription("The type of the attachment file")
    enum class FileType {
        @LLMDescription("The attachment file is a image file")
        IMAGE,

        @LLMDescription("The attachment file is an audio file")
        AUDIO,

        @LLMDescription("The attachment file is a video file")
        VIDEO,

        @LLMDescription("The attachment file is a binary file")
        BINARY
    }
}

/**
 * Iterates through the provided list of attachments and attaches each file to the content
 * builder based on its type and retrieval method. Supports handling of image, audio, video,
 * and binary files.
 *
 * @param attachments A list of `AttachmentFile` objects representing the files to attach.
 *                    Each attachment specifies its type (e.g., IMAGE, AUDIO, VIDEO, BINARY)
 *                    and method of retrieval (e.g., URL, FILE).
 */
// Koog 1.0.0 renamed `ContentPartsBuilder` to `ContentPartsBuilderBase<T>` (abstract
// base over the content-parts list type). All `image()` / `audio()` / `video()` /
// `binaryFile()` helpers still resolve unchanged. We use the unbounded `<*>`
// generic so callers from any concrete subclass (RequestMessagePartsBuilder, etc.)
// can extend.
fun ContentPartsBuilderBase<*>.attachmentsIfNeeded(
    attachments: List<AttachmentFile>
) {
    if (attachments.isNotEmpty()) {
        attachments.forEach { file ->
            when (file.fileType) {
                AttachmentFile.FileType.IMAGE -> {
                    when (file.getMethod) {
                        AttachmentFile.GetMethod.URL -> image(url = file.content)
                        AttachmentFile.GetMethod.FILE -> image(path = Path(file.content))
                    }
                }

                AttachmentFile.FileType.AUDIO -> {
                    when (file.getMethod) {
                        AttachmentFile.GetMethod.URL -> audio(url = file.content)
                        AttachmentFile.GetMethod.FILE -> audio(path = Path(file.content))
                    }
                }

                AttachmentFile.FileType.VIDEO -> {
                    when (file.getMethod) {
                        AttachmentFile.GetMethod.URL -> video(url = file.content)
                        AttachmentFile.GetMethod.FILE -> video(path = Path(file.content))
                    }
                }

                AttachmentFile.FileType.BINARY -> {
                    when (file.getMethod) {
                        AttachmentFile.GetMethod.URL, AttachmentFile.GetMethod.FILE -> binaryFile(
                            path = Path(file.content),
                            mimeType = "application/octet-stream"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Creates a default request node for the AI agent subgraph builder. The node processes an input of type `Input`,
 * constructs a prompt from the input using the provided `inputPrompt` function, and sends the prompt to the LLM.
 * Optional attachments can be included, and the behavior for tool invocation can be configured.
 *
 * @param name The name of the node.
 * @param exitToolName The name of the tool to be called when the task is complete.
 * @param inputPrompt A suspend function that generates the user prompt string based on the input and the current context.
 * @param attachments A list of attachment files to include with the request. Defaults to an empty list.
 * @param onlyCallingTools If true, restricts responses to tool calls only, following strict output rules. Defaults to true.
 * @return The created AI agent node that processes the input and sends it to the LLM.
 */
inline fun <reified Input> AIAgentSubgraphBuilderBase<*, *>.defaultRequest(
    name: String,
    exitToolName: String,
    crossinline inputPrompt: suspend (Input, AIAgentGraphContextBase) -> String,
    attachments: List<AttachmentFile> = emptyList(),
    onlyCallingTools: Boolean = true,
): AIAgentNodeBase<Input, Message.Assistant> {
    val request by node<Input, Message.Assistant>(name = name) { input ->
        val prompt = inputPrompt(input, this)
        llm.writeSession {
            appendPrompt {
                user {
                    +prompt
                    if (onlyCallingTools) {
                        +"""
                        Call $exitToolName when you are done.

                        STRICT OUTPUT RULES:
                        - Respond ONLY by calling tools; no free-form assistant messages.
                        - Do NOT include analysis, commentary, or Markdown/code fences.
                        - Keep outputs minimal and necessary for task completion.
                        """.trimIndent()
                    } else {
                        +"""
                        You may complete the task in either mode:
                        - Tool mode: call tools as needed; call $exitToolName when tool-based work is done.
                        - Claude skill mode: if the task can be completed directly via skill instructions, you may answer directly without forcing extra tool calls.

                        Keep outputs minimal and necessary for task completion.
                        """.trimIndent()
                    }
                    attachmentsIfNeeded(attachments)
                }
            }
            if (onlyCallingTools) {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(
                        onlyCallingTools = true,
                        exitToolName = exitToolName
                    ),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMOnlyCallingToolsPreservingDeepSeekReasoning()
                }
            } else {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMPreservingDeepSeekReasoning()
                }
            }
        }
    }
    return request
}

/**
 * Builds a subgraph for managing a tool execution cycle within an AI agent graph. This subgraph coordinates
 * the lifecycle of tool calls, response handling, and optional assistant message completion.
 *
 * @param Input The input type for the tool cycle graph.
 * @param Output The output type for the tool cycle graph.
 * @param tools The list of tools available for execution within the subgraph.
 * @param inputPrompt A suspending lambda that generates the prompt string based on the input and graph context.
 * @param outputCompute A suspending lambda that computes the final output from a response message and the graph context.
 * @param name The name of the subgraph. Default is "__tool_cycle_graph__".
 * @param attachments A list of attachment files for the subgraph. Default is an empty list.
 * @param llmModel The language model used in this graph. Default is null.
 * @param llmParams The parameters for the language model. Default is null.
 * @param requestNodeName The name of the request node. Default is "__tool_cycle_graph_request__".
 * @param executeToolNodeName The name of the execute tool node. Default is "__tool_cycle_graph_execute_tool__".
 * @param sendResultNodeName The name of the send result node. Default is "__tool_cycle_graph_send_result__".
 * @param exitToolName The name of the exit tool. Default is the value of `ExitTool.name`.
 * @param allowAssistantMessageCompletion Determines if assistant messages are allowed as completions. Default is true.
 * @param historyCompression Configures history message compression to manage token usage, or null if no compression is applied. Default is null.
 * @param request The request node used for the initial prompt generation. Default implementation is created using `defaultRequest`.
 */
inline fun <reified Input, reified Output> AIAgentSubgraphBuilderBase<*, *>.toolCycleGraph(
    tools: List<Tool<*, *>>,
    crossinline inputPrompt: suspend (Input, AIAgentGraphContextBase) -> String,
    crossinline outputCompute: suspend (Message.Assistant, AIAgentGraphContextBase) -> Output,
    name: String = "__tool_cycle_graph__",
    attachments: List<AttachmentFile> = emptyList(),
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    requestNodeName: String = "__tool_cycle_graph_request__",
    executeToolNodeName: String = "__tool_cycle_graph_execute_tool__",
    sendResultNodeName: String = "__tool_cycle_graph_send_result__",
    exitToolName: String = ExitTool.name,
    allowAssistantMessageCompletion: Boolean = true,
    historyCompression: HistoryCompressionConfig? = null,
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    request: AIAgentNodeBase<Input, Message.Assistant> = defaultRequest(
        requestNodeName,
        exitToolName,
        inputPrompt,
        attachments,
        onlyCallingTools = !allowAssistantMessageCompletion
    ),
) = subgraph(
    name = name,
    tools = tools,
    llmModel = llmModel,
    llmParams = llmParams,
) {
    val executeTool by nodeExecuteTool(name = executeToolNodeName)
    val sendResult by nodeLLMSendToolResultsPreservingDeepSeekReasoning(name = sendResultNodeName)
    val exitToolExecute by nodeExecuteTool(name = "__tool_cycle_graph_exit_tool_execute__")
    val exitToolSendResult by nodeLLMSendToolResultsPreservingDeepSeekReasoning(name = "__tool_cycle_graph_exit_tool_send_result__")
    // 在严格工具模式下提示 LLM 必须调用工具
    val giveFeedbackToCallTools by nodeLLMRequestOnlyCallingToolsPreservingDeepSeekReasoning(name = "__tool_cycle_graph_give_feedback_to_call_tools__")

    nodeStart then request
    // Koog 1.0.0 — `onToolCall { predicate }` (predicate form, not the
    // Tool-instance overload) and `onAssistantMessage` are gone. Replace with
    // typed `onCondition` checks over `msg.parts`.
    edge(request forwardTo executeTool onCondition { msg ->
        msg.parts.any { it is MessagePart.Tool.Call }
    })
    edge(request forwardTo giveFeedbackToCallTools onCondition { msg ->
        !allowAssistantMessageCompletion && msg.parts.any { it is MessagePart.Text }
    } transformed { msg -> msg.textContent() })
    edge(request forwardTo nodeFinish onCondition { msg ->
        allowAssistantMessageCompletion && msg.parts.any { it is MessagePart.Text }
    } transformed { msg ->
        outputCompute(msg, this)
    })
    if (historyCompression != null) {
        val compressHistory by nodeLLMCompressHistoryPreservingDeepSeekReasoning<ReceivedToolResults>(
            name = "__tool_cycle_graph_compress_history__",
            historyCompression = historyCompression,
        )
        val sessionCompactBefore by node<ReceivedToolResults, ReceivedToolResults>("${name}__session_compact_before__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
            input
        }
        val sessionCompactAfter by node<ReceivedToolResults, ReceivedToolResults>("${name}__session_compact_after__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
            input
        }
        edge(executeTool forwardTo sessionCompactBefore)
        edge(sessionCompactBefore forwardTo compressHistory)
        edge(compressHistory forwardTo sessionCompactAfter)
        edge(sessionCompactAfter forwardTo sendResult)
    } else {
        edge(executeTool forwardTo sendResult)
    }
    edge(sendResult forwardTo executeTool onCondition { msg ->
        msg.parts.filterIsInstance<MessagePart.Tool.Call>().any { it.tool != exitToolName }
    })
    edge(sendResult forwardTo exitToolExecute onCondition { msg ->
        msg.parts.filterIsInstance<MessagePart.Tool.Call>().any { it.tool == exitToolName }
    })
    edge(sendResult forwardTo giveFeedbackToCallTools onCondition { msg ->
        !allowAssistantMessageCompletion && msg.parts.any { it is MessagePart.Text }
    } transformed { msg -> msg.textContent() })
    edge(sendResult forwardTo nodeFinish onCondition { msg ->
        allowAssistantMessageCompletion && msg.parts.any { it is MessagePart.Text }
    } transformed { msg ->
        outputCompute(msg, this)
    })
    // Koog 1.0.0 — `onToolCall { predicate }` still exists for single-Message.Assistant
    // input, and `onAssistantMessage` is gone (replaced by `onTextMessage` for text
    // extraction or `onCondition` for typed-pass-through). Both forms drop into
    // `onCondition { msg -> msg.parts.… }` here so the downstream node keeps
    // the typed Message.Assistant.
    edge(giveFeedbackToCallTools forwardTo executeTool onCondition { msg ->
        msg.parts.filterIsInstance<MessagePart.Tool.Call>().any { it.tool != exitToolName }
    })
    edge(giveFeedbackToCallTools forwardTo exitToolExecute onCondition { msg ->
        msg.parts.filterIsInstance<MessagePart.Tool.Call>().any { it.tool == exitToolName }
    })
    edge(giveFeedbackToCallTools forwardTo giveFeedbackToCallTools onCondition { msg ->
        msg.parts.any { it is MessagePart.Text }
    } transformed { msg -> msg.textContent() })
    edge(exitToolExecute forwardTo exitToolSendResult)
    edge(exitToolSendResult forwardTo executeTool onCondition { msg ->
        msg.parts.filterIsInstance<MessagePart.Tool.Call>().any { it.tool != exitToolName }
    })
    edge(exitToolSendResult forwardTo exitToolExecute onCondition { msg ->
        msg.parts.filterIsInstance<MessagePart.Tool.Call>().any { it.tool == exitToolName }
    })
    edge(exitToolSendResult forwardTo giveFeedbackToCallTools onCondition { msg ->
        msg.parts.none { it is MessagePart.Text }
    } transformed { msg ->
        // `giveFeedbackToCallTools` accepts String input (it's a
        // nodeLLMRequest-style node whose first job is to append the user
        // message); pass the joined text so the LLM re-prompts.
        msg.textContent()
    })
    edge(exitToolSendResult forwardTo nodeFinish onCondition { msg ->
        msg.parts.any { it is MessagePart.Text }
    } transformed { msg ->
        outputCompute(msg, this)
    })
}

/**
 * Builds a subgraph for handling tool interaction cycles in an AI agent, supporting the use of multiple tools with
 * structured tool invocation and assistant messaging. It allows for flexible workflows that require iterative tool
 * usage and optionally supports history compression.
 *
 * @param Input The type of the input provided to the subgraph.
 * @param Output The type of the output generated by the subgraph.
 * @param tools A list of tools available for use within this subgraph.
 * @param inputPrompt A suspendable lambda function that generates the input prompt string for the language model,
 * taking the input data and the graph context as parameters.
 * @param outputCompute A suspendable lambda function that computes the final output by processing the response message
 * and the graph context.
 * @param name The name of the subgraph. Defaults to "__tool_cycle_graph_multi__".
 * @param attachments A list of attachment files to include when generating the prompt. Defaults to an empty list.
 * @param llmModel The language model to use within the subgraph. Defaults to null.
 * @param llmParams Additional parameters for the language model. Defaults to null.
 * @param requestNodeName The name of the node responsible for making language model requests. Defaults to "__tool_cycle_graph_multi_request__".
 * @param executeToolNodeName The name of the node responsible for executing tools. Defaults to "__tool_cycle_graph_multi_execute_tool__".
 * @param sendResultNodeName The name of the node responsible for sending tool results. Defaults to "__tool_cycle_graph_multi_send_result__".
 * @param exitToolName The name of the tool indicating the termination of the tool cycle. Defaults to [ExitTool.name].
 * @param allowAssistantMessageCompletion Whether the assistant is allowed to directly complete messages outside
 * of tool calls. Defaults to true.
 * @param historyCompression Configuration for compressing the message history within the subgraph to optimize
 * token usage. Defaults to null for no compression.
 */
inline fun <reified Input, reified Output> AIAgentSubgraphBuilderBase<*, *>.toolCycleGraphMulti(
    tools: List<Tool<*, *>>,
    crossinline inputPrompt: suspend (Input, AIAgentGraphContextBase) -> String,
    crossinline outputCompute: suspend (Message.Assistant, AIAgentGraphContextBase) -> Output,
    name: String = "__tool_cycle_graph_multi__",
    attachments: List<AttachmentFile> = emptyList(),
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    requestNodeName: String = "__tool_cycle_graph_multi_request__",
    executeToolNodeName: String = "__tool_cycle_graph_multi_execute_tool__",
    sendResultNodeName: String = "__tool_cycle_graph_multi_send_result__",
    exitToolName: String = ExitTool.name,
    allowAssistantMessageCompletion: Boolean = true,
    historyCompression: HistoryCompressionConfig? = null,
    skillManager: SkillManager? = null,
    sessionKey: String = "",
) = subgraph<Input, Output>(
    name = name,
    tools = tools,
    llmModel = llmModel,
    llmParams = llmParams,
) {
    val request by node<Input, List<Message.Assistant>>(name = requestNodeName) { input ->
        val prompt = inputPrompt(input, this)
        llm.writeSession {
            appendPrompt {
                user {
                    +prompt
                    if (!allowAssistantMessageCompletion) {
                        +"""
                        Call $exitToolName when you are done.

                        STRICT OUTPUT RULES:
                        - Respond ONLY by calling tools; no free-form assistant messages.
                        - Do NOT include analysis, commentary, or Markdown/code fences.
                        - Keep outputs minimal and necessary for task completion.
                        """.trimIndent()
                    } else {
                        +"""
                        You may complete the task in either mode:
                        - Tool mode: call tools as needed; call $exitToolName when tool-based work is done.
                        - Claude skill mode: if the task can be completed directly via skill instructions, you may answer directly without forcing extra tool calls.

                        Keep outputs minimal and necessary for task completion.
                        """.trimIndent()
                    }
                    attachmentsIfNeeded(attachments)
                }
            }
            if (!allowAssistantMessageCompletion) {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(
                        onlyCallingTools = true,
                        exitToolName = exitToolName
                    ),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultipleOnlyCallingToolsPreservingDeepSeekReasoning()
                }
            } else {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultiplePreservingDeepSeekReasoning()
                }
            }
        }
    }
    val executeTool by nodeExecuteMultipleTools(name = executeToolNodeName)
    val sendResult by node<List<ReceivedToolResult>, List<Message.Assistant>>(name = sendResultNodeName) { results ->
        llm.writeSession {
            appendPrompt {
                // Koog 1.0.0 — `tool { result(toolResult) }` was the 0.x PromptBuilder
                // block for adding a Message.Tool.Result top-level message. Now tool
                // results are MessagePart.Tool.Result entries inside a user message.
                // The DSL replacement: `user { toolResult(toolResult.toMessagePart()) }`.
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
            }
            requestWithOpenRouterEmptyResponseRetry(
                retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                appendPromptFn = { msg -> appendPrompt { user(msg) } }
            ) {
                requestLLMMultiplePreservingDeepSeekReasoning()
            }
        }
    }
    val exitToolExecute by nodeExecuteMultipleTools(name = "__tool_cycle_graph_multi_exit_tool_execute__")
    val exitToolSendResult by node<List<ReceivedToolResult>, List<Message.Assistant>>(name = "__tool_cycle_graph_multi_exit_tool_send_result__") { results ->
        llm.writeSession {
            appendPrompt {
                // Koog 1.0.0 — `tool { result(toolResult) }` was the 0.x PromptBuilder
                // block for adding a Message.Tool.Result top-level message. Now tool
                // results are MessagePart.Tool.Result entries inside a user message.
                // The DSL replacement: `user { toolResult(toolResult.toMessagePart()) }`.
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
            }
            if (!allowAssistantMessageCompletion) {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(
                        onlyCallingTools = true,
                        exitToolName = exitToolName
                    ),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultipleOnlyCallingToolsPreservingDeepSeekReasoning()
                }
            } else {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultiplePreservingDeepSeekReasoning()
                }
            }
        }
    }
    // 在严格工具模式下提示 LLM 必须调用工具
    val giveFeedbackToCallTools by nodeLLMRequestMultipleOnlyCallingToolsPreservingDeepSeekReasoning(
        name = "__tool_cycle_graph_multi_give_feedback_to_call_tools__"
    )

    nodeStart then request
    edge(request forwardTo executeTool onCondition { messages: List<Message.Assistant> -> val tc = messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }; tc.isNotEmpty() && tc.none { it.tool == exitToolName } })
    edge(request forwardTo exitToolExecute onCondition { messages: List<Message.Assistant> -> messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }.any { it.tool == exitToolName } })
    edge(request forwardTo giveFeedbackToCallTools onCondition { messages: List<Message.Assistant> ->
        !allowAssistantMessageCompletion && messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        messages.joinToString("\n") { it.textContent() }
    })
    edge(request forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
        allowAssistantMessageCompletion && messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        val target = messages.lastOrNull { it.parts.any { p -> p is MessagePart.Text } } ?: messages.last()
        outputCompute(target, this)
    })
    if (historyCompression != null) {
        val compressHistoryMulti by nodeLLMCompressHistoryPreservingDeepSeekReasoning<List<ReceivedToolResult>>(
            name = "__tool_cycle_graph_multi_compress_history__",
            historyCompression = historyCompression,
        )
        val sessionCompactBefore by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("${name}__session_compact_before__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
            input
        }
        val sessionCompactAfter by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("${name}__session_compact_after__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
            input
        }
        edge(executeTool forwardTo sessionCompactBefore)
        edge(sessionCompactBefore forwardTo compressHistoryMulti)
        edge(compressHistoryMulti forwardTo sessionCompactAfter)
        edge(sessionCompactAfter forwardTo sendResult)
    } else {
        edge(executeTool forwardTo sendResult)
    }
    edge(sendResult forwardTo executeTool onCondition { messages: List<Message.Assistant> -> val tc = messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }; tc.isNotEmpty() && tc.none { it.tool == exitToolName } })
    edge(sendResult forwardTo exitToolExecute onCondition { messages: List<Message.Assistant> -> messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }.any { it.tool == exitToolName } })
    edge(sendResult forwardTo giveFeedbackToCallTools onCondition { messages: List<Message.Assistant> ->
        !allowAssistantMessageCompletion && messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        messages.joinToString("\n") { it.textContent() }
    })
    edge(sendResult forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
        allowAssistantMessageCompletion && messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        val target = messages.lastOrNull { it.parts.any { p -> p is MessagePart.Text } } ?: messages.last()
        outputCompute(target, this)
    })
    edge(giveFeedbackToCallTools forwardTo executeTool onCondition { messages: List<Message.Assistant> -> val tc = messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }; tc.isNotEmpty() && tc.none { it.tool == exitToolName } })
    edge(giveFeedbackToCallTools forwardTo exitToolExecute onCondition { messages: List<Message.Assistant> -> messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }.any { it.tool == exitToolName } })
    edge(giveFeedbackToCallTools forwardTo giveFeedbackToCallTools onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        messages.joinToString("\n") { it.textContent() }
    })
    edge(exitToolExecute forwardTo exitToolSendResult)
    edge(exitToolSendResult forwardTo executeTool onCondition { messages: List<Message.Assistant> -> val tc = messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }; tc.isNotEmpty() && tc.none { it.tool == exitToolName } })
    edge(exitToolSendResult forwardTo exitToolExecute onCondition { messages: List<Message.Assistant> -> messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }.any { it.tool == exitToolName } })
    edge(exitToolSendResult forwardTo giveFeedbackToCallTools onCondition { messages: List<Message.Assistant> ->
        // Old: `onMultipleAssistantMessages { false }` was a no-op edge that never matched
        // (the predicate is constant false). Preserved here as a defensive no-op: this
        // route fires only when there are no text parts, which is the inverse of the
        // text-completion route below — matches the operational intent.
        messages.flatMap { it.parts }.none { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        messages.joinToString("\n") { it.textContent() }
    })
    edge(exitToolSendResult forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        val target = messages.lastOrNull { it.parts.any { p -> p is MessagePart.Text } } ?: messages.last()
        outputCompute(target, this)
    })
}

/**
 * Configures and executes a multi-step reasoning and decision-making subgraph
 * that leverages multiple AI tools for task resolution based on user input.
 * The reasoning steps iteratively analyze the task, determine the next actions,
 * and coordinate tool execution to accomplish the desired outcome.
 *
 * @param Input The expected input type for the subgraph.
 * @param Output The expected output type for the subgraph.
 * @param tools A list of tools available for the subgraph to use during task resolution.
 * @param inputPrompt A suspend function that generates a prompt for reasoning based on the input
 *                    and subgraph context.
 * @param outputCompute A suspend function that processes the final result message and context
 *                      into the expected output type.
 * @param name The name of the subgraph. Default is "__tool_cycle_graph_multi_reasoning__".
 * @param attachments Additional file attachments for LLM context. Default is an empty list.
 * @param llmModel The LLM (Large Language Model) to be used, or null for the default model. Default is null.
 * @param llmParams Parameters for customizing the LLM configuration. Default is null.
 * @param exitToolName The designated name of the tool that signifies task completion. Default is the predefined ExitTool name.
 * @param allowAssistantMessageCompletion Flag indicating whether the assistant can generate
 *                                        free-form responses or must exclusively use tools. Default is true.
 * @param historyCompression Configuration for compressing the reasoning and tool history within the LLM context,
 *                           or null to disable history compression. Default is null.
 * @param reasoningInterval Specifies the number of tool execution cycles between reasoning steps.
 *                          Must be greater than 0. Default is 1.
 */
inline fun <reified Input, reified Output> AIAgentSubgraphBuilderBase<*, *>.toolCycleGraphMultiReasoning(
    tools: List<Tool<*, *>>,
    crossinline inputPrompt: suspend (Input, AIAgentGraphContextBase) -> String,
    crossinline outputCompute: suspend (Message.Assistant, AIAgentGraphContextBase) -> Output,
    name: String = "__tool_cycle_graph_multi_reasoning__",
    attachments: List<AttachmentFile> = emptyList(),
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    exitToolName: String = ExitTool.name,
    allowAssistantMessageCompletion: Boolean = true,
    historyCompression: HistoryCompressionConfig? = null,
    skillManager: SkillManager? = null,
    sessionKey: String = "",
    reasoningInterval: Int = 1,
    showReasoning: Boolean = true,
    noinline onReasoningMessage: ReasoningMonitorCallback? = null,
) = subgraph<Input, Output>(
    name = name,
    tools = tools,
    llmModel = llmModel,
    llmParams = llmParams,
) {
    require(reasoningInterval > 0) { "Reasoning interval must be greater than 0" }
    val reasoningStepKey = createStorageKey<Int>("${name}_reasoning_step")

    val reasoningPrompt = "Please give your thoughts about the task and plan the next steps."
    val continuationPrompt = """
        Based on your analysis above, now proceed to execute the plan.

        Important: the previous reasoning step had no tool access. Any tool-looking text,
        XML-style tags, JSON snippets, or claims in that reasoning were not executed and did
        not change files or external state.

        If the plan requires a tool, call the real tool now. Do not call $exitToolName until
        all required real tool calls have succeeded.
        """.trimIndent()

    // 对初始输入做 reasoning，然后交给 nodeCallLLMMulti 发起真正的工具调用请求
    val nodeReasonInput by node<Input, Unit>(name = "__tool_cycle_graph_multi_reasoning_reason_input__") { input ->
        storage.set(reasoningStepKey, 0)
        val prompt = inputPrompt(input, this)
        llm.writeSession {
            appendPrompt {
                user {
                    +prompt
                    if (!allowAssistantMessageCompletion) {
                        +"""
                        Call $exitToolName when you are done.

                        STRICT OUTPUT RULES:
                        - Respond ONLY by calling tools; no free-form assistant messages.
                        - Do NOT include analysis, commentary, or Markdown/code fences.
                        - Keep outputs minimal and necessary for task completion.
                        """.trimIndent()
                    } else {
                        +"""
                        You may complete the task in either mode:
                        - Tool mode: call tools as needed; call $exitToolName when tool-based work is done.
                        - Claude skill mode: if the task can be completed directly via skill instructions, you may answer directly without forcing extra tool calls.

                        Keep outputs minimal and necessary for task completion.
                        """.trimIndent()
                    }
                    attachmentsIfNeeded(attachments)
                }
                if (showReasoning) user(reasoningPrompt)
            }
            if (showReasoning) {
                try {
                    val reasoning = requestLLMWithoutToolsPreservingDeepSeekReasoning()
                    // Koog 1.0.0 — `.content` accessor is gone; use `.textContent()`
                    // which walks `parts: List<MessagePart>` and concatenates Text parts.
                    val reasoningText = reasoning.textContent()
                    if (reasoningText.isNotBlank()) {
                        printlnColor(AnsiColor.CYAN, "\n💭 [Reasoning]\n$reasoningText")
                        onReasoningMessage?.invoke(reasoningText)
                    }
                    // 追加续写提示，确保对话末尾是 user 消息而非 assistant 消息，
                    // 防止后续 preserving multiple 调用时 LLM 因末尾已是自身回复而直接输出文字提前终止。
                    appendPrompt {
                        user(continuationPrompt)
                    }
                } catch (e: Exception) {
                    // 部分 OpenRouter 模型（如 reasoning 类模型）可能只返回 reasoning_content
                    // 而没有 content，导致客户端抛出异常，跳过此次 reasoning 步骤继续执行。
                }
            }
        }
    }

    // 发起多工具并行 LLM 请求
    val nodeCallLLMMulti by node<Unit, List<Message.Assistant>>(name = "__tool_cycle_graph_multi_reasoning_call_llm__") {
        llm.writeSession {
            if (!allowAssistantMessageCompletion) {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(
                        onlyCallingTools = true,
                        exitToolName = exitToolName
                    ),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultipleOnlyCallingToolsPreservingDeepSeekReasoning()
                }
            } else {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultiplePreservingDeepSeekReasoning()
                }
            }
        }
    }

    val executeTool by nodeExecuteMultipleTools(name = "__tool_cycle_graph_multi_reasoning_execute_tool__")
    val exitToolExecute by nodeExecuteMultipleTools(name = "__tool_cycle_graph_multi_reasoning_exit_tool_execute__")
    val exitToolSendResult by node<List<ReceivedToolResult>, List<Message.Assistant>>(name = "__tool_cycle_graph_multi_reasoning_exit_tool_send_result__") { results ->
        llm.writeSession {
            appendPrompt {
                // Koog 1.0.0 — `tool { result(toolResult) }` was the 0.x PromptBuilder
                // block for adding a Message.Tool.Result top-level message. Now tool
                // results are MessagePart.Tool.Result entries inside a user message.
                // The DSL replacement: `user { toolResult(toolResult.toMessagePart()) }`.
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
            }
            requestWithOpenRouterEmptyResponseRetry(
                retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                appendPromptFn = { msg -> appendPrompt { user(msg) } }
            ) {
                requestLLMMultiplePreservingDeepSeekReasoning()
            }
        }
    }
    val giveFeedbackToCallTools by nodeLLMRequestMultipleOnlyCallingToolsPreservingDeepSeekReasoning(
        name = "__tool_cycle_graph_multi_reasoning_give_feedback_to_call_tools__"
    )
    val sendResult by node<List<ReceivedToolResult>, List<Message.Assistant>>(name = "__tool_cycle_graph_multi_reasoning_send_result__") { results ->
        llm.writeSession {
            appendPrompt {
                // Koog 1.0.0 — `tool { result(toolResult) }` was the 0.x PromptBuilder
                // block for adding a Message.Tool.Result top-level message. Now tool
                // results are MessagePart.Tool.Result entries inside a user message.
                // The DSL replacement: `user { toolResult(toolResult.toMessagePart()) }`.
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
            }
            if (!allowAssistantMessageCompletion) {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(
                        onlyCallingTools = true,
                        exitToolName = exitToolName
                    ),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultipleOnlyCallingToolsPreservingDeepSeekReasoning()
                }
            } else {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = buildOpenRouterEmptyResponseRetryPrompt(),
                    appendPromptFn = { msg -> appendPrompt { user(msg) } }
                ) {
                    requestLLMMultiplePreservingDeepSeekReasoning()
                }
            }
        }
    }

    // 将多工具结果追加到 prompt，并按 reasoningInterval 插入推理步骤，然后交由 nodeCallLLMMulti 继续
    val nodeReasonAfterTools by node<List<ReceivedToolResult>, Unit>(name = "__tool_cycle_graph_multi_reasoning_reason_after_tools__") { results ->
        val reasoningStep = storage.getValue(reasoningStepKey)
        val completedToolCycles = reasoningStep + 1
        llm.writeSession {
            appendPrompt {
                // Same Koog 1.0.0 migration as above — `tool { result(toolResult) }`
                // becomes `user { toolResult(toolResult.toMessagePart()) }`.
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
            }
            if (showReasoning && completedToolCycles % reasoningInterval == 0) {
                appendPrompt {
                    user(reasoningPrompt)
                }
                try {
                    val reasoning = requestLLMWithoutToolsPreservingDeepSeekReasoning()
                    val reasoningText = reasoning.textContent()
                    if (reasoningText.isNotBlank()) {
                        printlnColor(AnsiColor.CYAN, "\n💭 [Reasoning]\n$reasoningText")
                        onReasoningMessage?.invoke(reasoningText)
                    }
                    // 追加续写提示，确保对话末尾是 user 消息而非 assistant 消息。
                    appendPrompt {
                        user(continuationPrompt)
                    }
                } catch (e: Exception) {
                    // 部分 OpenRouter 模型（如 reasoning 类模型）可能只返回 reasoning_content
                    // 而没有 content，导致客户端抛出异常，跳过此次 reasoning 步骤继续执行。
                }
            }
        }
        storage.set(reasoningStepKey, completedToolCycles)
    }

    nodeStart then nodeReasonInput then nodeCallLLMMulti

    // Koog 1.0.0 — list-of-messages predicates use `onCondition` with the
    // typed List<Message.Assistant>; tool calls and text parts are extracted
    // by flattening `.parts` across the choice list.
    edge(nodeCallLLMMulti forwardTo executeTool onCondition { messages: List<Message.Assistant> ->
        val toolCalls = messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }
        toolCalls.isNotEmpty() && toolCalls.none { it.tool == exitToolName }
    })
    edge(nodeCallLLMMulti forwardTo exitToolExecute onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }
            .any { it.tool == exitToolName }
    })
    edge(nodeCallLLMMulti forwardTo giveFeedbackToCallTools onCondition { messages: List<Message.Assistant> ->
        !allowAssistantMessageCompletion && messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        messages.joinToString("\n") { it.textContent() }
    })
    edge(nodeCallLLMMulti forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
        allowAssistantMessageCompletion && messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        // Pick the last assistant message that carries text content.
        val target = messages.lastOrNull { it.parts.any { p -> p is MessagePart.Text } }
            ?: messages.last()
        outputCompute(target, this)
    })
    if (historyCompression != null) {
        val compressHistoryMulti by nodeLLMCompressHistoryPreservingDeepSeekReasoning<List<ReceivedToolResult>>(
            name = "__tool_cycle_graph_multi_reasoning_compress_history__",
            historyCompression = historyCompression,
        )
        val sessionCompactBefore by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("${name}__session_compact_before__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_BEFORE, sessionKey)
            input
        }
        val sessionCompactAfter by node<List<ReceivedToolResult>, List<ReceivedToolResult>>("${name}__session_compact_after__") { input ->
            skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_COMPACT_AFTER, sessionKey)
            input
        }
        edge(executeTool forwardTo sessionCompactBefore)
        edge(sessionCompactBefore forwardTo compressHistoryMulti)
        edge(compressHistoryMulti forwardTo sessionCompactAfter)
        edge(sessionCompactAfter forwardTo nodeReasonAfterTools)
    } else {
        edge(executeTool forwardTo nodeReasonAfterTools)
    }
    edge(nodeReasonAfterTools forwardTo nodeCallLLMMulti)
    // `giveFeedbackToCallTools` returns `List<Message.Assistant>` (multi-choice
    // response). The old `onMultipleToolCalls` / `onMultipleAssistantMessages`
    // predicates received that list; the 1.0.0 DSL uses `onCondition` with
    // the same parameter type, but the `parts` / `textContent` accessors are
    // on individual `Message.Assistant` entries so we flatten across the list.
    edge(giveFeedbackToCallTools forwardTo executeTool onCondition { messages: List<Message.Assistant> ->
        val toolCalls = messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }
        toolCalls.isNotEmpty() && toolCalls.none { it.tool == exitToolName }
    })
    edge(giveFeedbackToCallTools forwardTo exitToolExecute onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }
            .any { it.tool == exitToolName }
    })
    edge(giveFeedbackToCallTools forwardTo giveFeedbackToCallTools onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        messages.joinToString("\n") { it.textContent() }
    })
    edge(exitToolExecute forwardTo exitToolSendResult)
    // `exitToolSendResult` outputs `List<Message.Assistant>` (multi-choice
    // response). Flatten the parts across the list when filtering.
    edge(exitToolSendResult forwardTo executeTool onCondition { messages: List<Message.Assistant> ->
        val toolCalls = messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }
        toolCalls.isNotEmpty() && toolCalls.none { it.tool == exitToolName }
    })
    edge(exitToolSendResult forwardTo exitToolExecute onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts.filterIsInstance<MessagePart.Tool.Call>() }
            .any { it.tool == exitToolName }
    })
    edge(exitToolSendResult forwardTo giveFeedbackToCallTools onCondition { messages: List<Message.Assistant> ->
        // Old: onMultipleAssistantMessages { false } never matched in any real
        // run. Preserve original intent: this edge fires only when there is
        // no assistant-style text content — i.e. it's a tool-only response
        // and we should re-prompt the model.
        messages.flatMap { it.parts }.none { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        messages.joinToString("\n") { it.textContent() }
    })
    edge(exitToolSendResult forwardTo nodeFinish onCondition { messages: List<Message.Assistant> ->
        messages.flatMap { it.parts }.any { it is MessagePart.Text }
    } transformed { messages: List<Message.Assistant> ->
        // Pick the last assistant message that carries text content — the
        // multi-choice response may include an exit-tool-call sibling without
        // text. `outputCompute` expects a single `Message.Assistant`.
        val target = messages.lastOrNull { it.parts.any { p -> p is MessagePart.Text } }
            ?: messages.last()
        outputCompute(target, this)
    })
}

/**
 * Constructs and executes a structured graph subgraph for parsing a given input into a structured output format.
 *
 * @param Input The type of the input data.
 * @param Output The type of the structured output data.
 * @param name The name of the subgraph. Defaults to "__structure_graph__".
 * @param inputPrompt A suspendable function that generates the prompt for the LLM based on the given input and context.
 * @param attachments A list of attachment files to include with the prompt. Defaults to an empty list.
 * @param emptyValue The default value to return if structured parsing fails.
 * @param llmModel An optional custom language model to use. If null, the default model will be used.
 * @param llmParams Optional parameters to configure the language model's behavior.
 * @param structureExamples A list of example structured outputs to guide the schema generation process.
 * @param structureFixingParser The parser to use for fixing and validating the structured output. Defaults to `DEFAULT_FIXING_PARSER`.
 */
inline fun <reified Input, reified Output> AIAgentSubgraphBuilderBase<*, *>.structureGraph(
    name: String = "__structure_graph__",
    noinline inputPrompt: suspend (Input, AIAgentGraphContextBase) -> String = { input, _ -> input.toString() },
    attachments: List<AttachmentFile> = emptyList(),
    emptyValue: Output,
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    structureExamples: List<Output> = emptyList(),
    schemaGenerator: JsonSchemaGenerator = BasicJsonSchemaGenerator.Default,
    structureFixingParser: StructureFixingParser = DEFAULT_FIXING_PARSER
) = subgraph<Input, Output>(
    name = name,
    llmModel = llmModel,
    llmParams = llmParams,
) {
    val getStructuredData by node<Input, Output>(name = "__structure_graph_get_structured_data__") { input ->
        val promptText = inputPrompt(input, this)
        llm.writeSession {
            val structuredConfig = StructuredRequestConfig(
                default = StructuredRequest.Manual(
                    JsonStructure.create<Output>(
                        schemaGenerator = schemaGenerator,
                        examples = structureExamples
                    )
                )
            )

            val result = if (model.provider == LLMProvider.DeepSeek) {
                prompt = structuredConfig.updatePrompt(model, this.prompt)
                appendPrompt {
                    user {
                        +promptText
                        attachmentsIfNeeded(attachments)
                    }
                }
                // Koog 1.0.0 — `requestLLMMultipleWithoutTools` is gone; the
                // single-response `requestLLMWithoutTools` returns one
                // `Message.Assistant` directly. The `filterIsInstance` /
                // `lastOrNull` chain is redundant.
                val assistant = requestLLMWithoutTools()
                runCatching {
                    parseResponseToStructuredResponse(assistant, structuredConfig, structureFixingParser)
                }
            } else {
                appendPrompt {
                    user {
                        +promptText
                        attachmentsIfNeeded(attachments)
                    }
                }
                requestLLMStructured(structuredConfig, structureFixingParser)
            }
            if (result.isFailure) {
                logProgress(
                    AnsiColor.RED,
                    "StructureGraph",
                    "Failed to parse structured output: ${result.exceptionOrNull()?.message}"
                )
                emptyValue
            } else result.getOrNull()?.component1() ?: emptyValue
        }
    }

    nodeStart then getStructuredData then nodeFinish
}

/**
 * Creates a structured tool graph to handle structured outputs and integrate tool usage
 * within an AI agent subgraph configuration.
 *
 * @param Output The type representing the structured output data for the graph.
 * @param tools The list of tools to be integrated into the subgraph for processing.
 * @param structureExamples A list of example outputs for generating the JSON schema.
 * Defaults to an empty list.
 * @param name The name of the structured tool graph. Defaults to "__structure_graph__".
 * @param attachments A list of attachment files to be included in the graph.
 * Defaults to an empty list.
 * @param llmModel The language model to be used for processing, if applicable. Defaults to null.
 * @param llmParams The parameters for the language model, if applicable. Defaults to null.
 * @param structureFixingParser The parser used for correcting structured outputs, if needed.
 * Defaults to `DEFAULT_FIXING_PARSER`.
 */
inline fun <reified Output> AIAgentSubgraphBuilderBase<*, *>.structureToolGraph(
    tools: List<Tool<*, *>>,
    structureExamples: List<Output> = emptyList(),
    name: String = "__structure_graph__",
    attachments: List<AttachmentFile> = emptyList(),
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    schemaGenerator: JsonSchemaGenerator = BasicJsonSchemaGenerator.Default,
    structureFixingParser: StructureFixingParser = DEFAULT_FIXING_PARSER,
    noinline onAssistantContent: ((String) -> Unit)? = null,
    finalizeWithoutToolsAfterToolResults: Boolean = false
) = structuredOutputWithToolsGraph(
    tools,
    StructuredRequestConfig(
        default = StructuredRequest.Manual(
            JsonStructure.create<Output>(
                schemaGenerator = schemaGenerator,
                examples = structureExamples
            )
        )
    ),
    parallelTools = false,
    llmParams = llmParams,
    llmModel = llmModel,
    attachments = attachments,
    name = name,
    onAssistantContent = onAssistantContent,
    finalizeWithoutToolsAfterToolResults = finalizeWithoutToolsAfterToolResults
)

/**
 * Creates a subgraph within the AI agent framework to handle structured outputs
 * and integrate tool usage during message processing with an identity transformation.
 *
 * @param Output The type representing the structured output data for the subgraph.
 * @param config The configuration object that defines how structured responses are managed.
 * @param parallelTools A flag indicating whether tools are executed in parallel. Defaults to false.
 * @return A delegate that manages the subgraph, providing structured outputs with integrated tool functionality.
 */
inline fun <reified Output> AIAgentSubgraphBuilderBase<*, *>.structuredOutputWithToolsGraph(
    tools: List<Tool<*, *>>,
    config: StructuredRequestConfig<Output>,
    parallelTools: Boolean = false,
    attachments: List<AttachmentFile> = emptyList(),
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    name: String = "__structured_output_with_tools_graph__",
    noinline onAssistantContent: ((String) -> Unit)? = null,
    finalizeWithoutToolsAfterToolResults: Boolean = false
): AIAgentSubgraphDelegate<String, Output> = structuredOutputWithToolsGraph(
    tools,
    config,
    parallelTools,
    attachments,
    llmModel,
    llmParams,
    name,
    onAssistantContent,
    finalizeWithoutToolsAfterToolResults,
) { it }

/**
 * Constructs a subgraph within the AI agent framework for handling structured outputs
 * and tool usage during message processing.
 *
 * @param Input The type representing the input data for the subgraph.
 * @param Output The type representing the output data for the subgraph.
 * @param config The configuration object that defines how structured responses are handled.
 * @param parallelTools A flag indicating whether tools should execute in parallel. Defaults to false.
 * @param transform A suspend lambda function that transforms the input data into a string
 *                  for processing by the AI agent.
 * @return A subgraph delegate that coordinates message handling to produce structured outputs
 *         with tool integration functionality.
 */
inline fun <reified Input, reified Output> AIAgentSubgraphBuilderBase<*, *>.structuredOutputWithToolsGraph(
    tools: List<Tool<*, *>>,
    config: StructuredRequestConfig<Output>,
    parallelTools: Boolean = false,
    attachments: List<AttachmentFile> = emptyList(),
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    name: String = "__structured_output_with_tools_graph__",
    noinline onAssistantContent: ((String) -> Unit)? = null,
    finalizeWithoutToolsAfterToolResults: Boolean = false,
    noinline transform: suspend AIAgentGraphContextBase.(input: Input) -> String
): AIAgentSubgraphDelegate<Input, Output> =
    subgraph<Input, Output>(
        name = name,
        tools = tools,
        llmModel = llmModel,
        llmParams = llmParams
    ) {
        val setStructuredOutput by node<Input, Input> { message ->
            llm.writeSession {
                prompt(config.updatePrompt(model, prompt)) {
                    user {
                        attachmentsIfNeeded(attachments)
                    }
                }
                message
            }
        }
        val transformInput by node<Input, String> { transform(it) }
        // Koog 1.0.0 — `nodeLLMRequest*` now returns a single Message.Assistant
        // whose `parts` carry tool calls, reasoning, and text together. The old
        // multi-response variants are folded into this single-message model.
        // `nodeExecuteTools(parallel: Boolean)` replaces the 0.x
        // `nodeExecuteMultipleTools`; `nodeLLMSendToolResults` replaces the
        // multi-result variant.
        val callLLM by nodeLLMRequestPreservingDeepSeekReasoning()
        val executeTools by nodeExecuteTools(parallel = parallelTools)
        val sendToolResult by if (finalizeWithoutToolsAfterToolResults) {
            nodeLLMSendToolResultsPreservingDeepSeekReasoning(allowToolCalls = false)
        } else {
            nodeLLMSendToolResultsPreservingDeepSeekReasoning()
        }
        val transformToStructuredOutput by node<Message.Assistant, Output> { response ->
            llm.writeSession {
                val responseText = response.textContent()
                onAssistantContent?.invoke(responseText)
                val trimmedContent = responseText.trim()
                val extractedJson = extractJsonFromResponse(responseText)
                val parseTarget = if (!extractedJson.isNullOrBlank() && extractedJson != trimmedContent) {
                    Message.Assistant(
                        content = extractedJson,
                        metaInfo = response.metaInfo
                    )
                } else {
                    response
                }
                parseResponseToStructuredResponse(parseTarget, config).data
            }
        }

        // Set the structured output, get the input and then call the llm
        nodeStart then setStructuredOutput then transformInput then callLLM

        // On tools
        edge(callLLM forwardTo executeTools onToolCalls { true })
        edge(executeTools forwardTo sendToolResult)

        // On assistant messages (text-only response) — `onCondition` keeps the
        // original Message.Assistant so the downstream node receives the typed
        // message, not the joined string `onTextMessage` would produce.
        edge(callLLM forwardTo transformToStructuredOutput onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        })

        // Post tool result
        if (!finalizeWithoutToolsAfterToolResults) {
            edge(sendToolResult forwardTo executeTools onToolCalls { true })
        }
        edge(sendToolResult forwardTo transformToStructuredOutput onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        })

        // Finish
        transformToStructuredOutput then nodeFinish
    }
