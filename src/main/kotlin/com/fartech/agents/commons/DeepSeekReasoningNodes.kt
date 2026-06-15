package com.fartech.agents.commons

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.prompt.message.Message

/**
 * DeepSeek reasoning-preservation node wrappers for Koog 1.0.0.
 *
 * See [DeepSeekReasoningSession] for the full migration story; this file
 * mirrors that pattern at the DSL node level. The wrappers exist so that
 * [AgentStrategies] doesn't need 20+ call-site updates; a follow-up commit
 * should inline them and delete this file.
 *
 * Compared to the 0.8.0 version, two API breaks were absorbed here:
 *   - Node return type narrowed from `Message.Assistant` (sealed parent
 *     interface with `Reasoning` / `Assistant` / `Tool.Call` variants) to
 *     `Message.Assistant` (now carries all those flavours as `parts`).
 *   - `nodeLLMSend*ToolResult*` now takes a single [ReceivedToolResults]
 *     batch instead of one [ReceivedToolResult] per call — to preserve the
 *     old "one result at a time" call sites the wrapper auto-wraps.
 */

@PublishedApi
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMRequestPreservingDeepSeekReasoning(
    name: String = "__deepseek_safe_llm_request__",
    allowToolCalls: Boolean = true,
) = node<String, Message.Assistant>(name) { input ->
    llm.writeSession {
        appendPrompt {
            user(input)
        }
        if (allowToolCalls) {
            requestLLMPreservingDeepSeekReasoning()
        } else {
            requestLLMWithoutToolsPreservingDeepSeekReasoning()
        }
    }
}

@PublishedApi
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMRequestOnlyCallingToolsPreservingDeepSeekReasoning(
    name: String = "__deepseek_safe_llm_request_only_tools__",
) = node<String, Message.Assistant>(name) { input ->
    llm.writeSession {
        appendPrompt {
            user(input)
        }
        requestLLMOnlyCallingToolsPreservingDeepSeekReasoning()
    }
}

@PublishedApi
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMRequestMultiplePreservingDeepSeekReasoning(
    name: String = "__deepseek_safe_llm_request_multiple__",
) = node<String, List<Message.Assistant>>(name) { input ->
    llm.writeSession {
        appendPrompt {
            user(input)
        }
        requestLLMMultiplePreservingDeepSeekReasoning()
    }
}

@PublishedApi
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMRequestMultipleOnlyCallingToolsPreservingDeepSeekReasoning(
    name: String = "__deepseek_safe_llm_request_multiple_only_tools__",
) = node<String, List<Message.Assistant>>(name) { input ->
    llm.writeSession {
        appendPrompt {
            user(input)
        }
        requestLLMMultipleOnlyCallingToolsPreservingDeepSeekReasoning()
    }
}

@PublishedApi
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMSendToolResultPreservingDeepSeekReasoning(
    name: String = "__deepseek_safe_llm_send_tool_result__",
) = node<ReceivedToolResult, Message.Assistant>(name) { toolResult ->
    llm.writeSession {
        appendPrompt {
            user {
                toolResult(toolResult.toMessagePart())
            }
        }
        requestLLMPreservingDeepSeekReasoning()
    }
}

@PublishedApi
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMSendMultipleToolResultsPreservingDeepSeekReasoning(
    name: String = "__deepseek_safe_llm_send_multiple_tool_results__",
) = node<List<ReceivedToolResult>, List<Message.Assistant>>(name) { toolResults ->
    llm.writeSession {
        appendPrompt {
            user {
                toolResults.forEach { toolResult(it.toMessagePart()) }
            }
        }
        requestLLMMultiplePreservingDeepSeekReasoning()
    }
}

@PublishedApi
internal fun AIAgentSubgraphBuilderBase<*, *>.nodeLLMSendToolResultsPreservingDeepSeekReasoning(
    name: String = "__deepseek_safe_llm_send_tool_results__",
    allowToolCalls: Boolean = true,
) = node<ReceivedToolResults, Message.Assistant>(name) { toolResults ->
    llm.writeSession {
        appendPrompt {
            user {
                toolResults.toolResults.forEach { toolResult ->
                    toolResult(toolResult.toMessagePart())
                }
            }
        }
        if (allowToolCalls) {
            requestLLMPreservingDeepSeekReasoning()
        } else {
            requestLLMWithoutToolsPreservingDeepSeekReasoning()
        }
    }
}

@PublishedApi
internal inline fun <reified Input> AIAgentSubgraphBuilderBase<*, *>.nodeLLMCompressHistoryPreservingDeepSeekReasoning(
    name: String,
    historyCompression: HistoryCompressionConfig,
) = node<Input, Input>(name) { input ->
    llm.writeSession {
        // Koog 1.0.0 — reasoning is now embedded in `Message.Assistant.parts`
        // as `MessagePart.Reasoning`, so the stock `replaceHistoryWithTLDR`
        // preserves it through normal compression. The old custom
        // `replaceHistoryWithTLDRPreservingDeepSeekReasoning` is obsolete.
        replaceHistoryWithTLDR(
            strategy = historyCompression.toStrategy(),
            preserveMemory = historyCompression.preserveMemory,
        )
    }
    input
}

/**
 * Koog 1.0.0 dropped the 0.8.0 `nodeExecuteMultipleTools` helper. This is a thin
 * compatibility shim that preserves the old signature
 * (`List<Message.Assistant>` in, `List<ReceivedToolResult>` out, parallelism
 * flag) by:
 *   1. Flattening `MessagePart.Tool.Call` parts across all assistant messages,
 *   2. Calling the underlying `executeTools` runtime helper directly so we don't
 *      bind to `nodeExecuteTools`'s `ToolCalls`/`ReceivedToolResults` typed
 *      I/O which would force a much larger downstream rewrite.
 *
 * This is intentionally a port to keep the multi-strategy DSL in
 * `AgentSubGraphUtils` / `AgentStrategies` compiling without re-writing every
 * edge. A follow-up commit should retire this shim in favour of stock
 * `nodeExecuteTools` and the new edge DSL (`onToolCalls { … }`).
 */
fun AIAgentSubgraphBuilderBase<*, *>.nodeExecuteMultipleTools(
    @Suppress("UNUSED_PARAMETER") parallelTools: Boolean = true,
    name: String? = null,
) = node<List<ai.koog.prompt.message.Message.Assistant>, List<ai.koog.agents.core.environment.ReceivedToolResult>>(name) { messages ->
    val toolCalls = messages.flatMap { msg ->
        msg.parts.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Call>()
    }
    if (toolCalls.isEmpty()) return@node emptyList()
    // Koog 1.0.0 AIAgentEnvironment.executeTools always runs the calls
    // concurrently via supervisorScope; the `parallel` knob the old
    // `nodeExecuteMultipleTools` exposed is no longer a runtime choice
    // (callers asked for parallel ~100% of the time in our codebase).
    // We accept and ignore the parameter to preserve the call-site API.
    environment.executeTools(toolCalls)
}

/**
 * Koog 1.0.0 also removed the singular `nodeExecuteTool(name)` helper.
 *
 * In the 1.0.0 parts model a single `Message.Assistant` routinely carries
 * MULTIPLE `Tool.Call` parts (parallel tool use from Claude / GPT). The
 * original 0.8.0-style shim executed only `firstOrNull()` — the other calls
 * were silently dropped and the next request's history contained an
 * assistant message with N tool_calls followed by a single tool result,
 * which OpenAI-compatible providers reject with a 400 ("each tool_call_id
 * must have a response"), typically looping the agent to max_iterations.
 *
 * This shim therefore executes ALL `Tool.Call` parts (concurrently, via the
 * environment's supervisorScope) and returns the batch as
 * [ReceivedToolResults]; pair it with
 * [nodeLLMSendToolResultsPreservingDeepSeekReasoning] which appends every
 * result so history stays balanced.
 */
fun AIAgentSubgraphBuilderBase<*, *>.nodeExecuteTool(
    name: String? = null,
) = node<ai.koog.prompt.message.Message.Assistant, ReceivedToolResults>(name) { message ->
    val toolCalls = message.parts.filterIsInstance<ai.koog.prompt.message.MessagePart.Tool.Call>()
    if (toolCalls.isEmpty()) {
        error("nodeExecuteTool received a Message.Assistant with no MessagePart.Tool.Call parts")
    }
    ReceivedToolResults(environment.executeTools(toolCalls))
}
