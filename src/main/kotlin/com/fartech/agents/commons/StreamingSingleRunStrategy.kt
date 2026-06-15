package com.fartech.agents.commons

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.CancellationException

/**
 * Streaming sibling of [singleRunWithParallelAbility].
 *
 * Same graph shape (history restore → LLM call → tool loop → finish), but every
 * LLM round-trip goes through `requestLLMStreaming()` instead of the buffered
 * multiple-choices API. Frame-level consumers (text deltas for live typing,
 * reasoning deltas for the thinking panel, `StreamFrame.End` for token
 * accounting) hook in via the agent's `onLLMStreamingFrameReceived` /
 * `onLLMStreamingStarting` event handlers — the strategy itself stays
 * transport-only and returns the final aggregated text exactly like its
 * non-streaming sibling.
 *
 * Notes vs the non-streaming variant:
 *  - Tool execution is always concurrent (Koog 1.0.0 `executeTools` runs the
 *    batch in a supervisorScope; the old `parallel` knob is no longer a
 *    runtime choice — see [nodeExecuteMultipleTools]).
 *  - The OpenRouter empty-response retry is preserved: an empty/`reasoning`-only
 *    streamed round is dropped from history, retried once with the standard
 *    retry prompt (still streaming), then falls back to one non-streaming
 *    request before giving up. See [streamRequestWithEmptyRetry].
 */
fun singleRunStreamingWithParallelAbility(
    name: String,
    historyMessages: List<Map<String, String>> = emptyList(),
): AIAgentGraphStrategy<String, String> = strategy(name) {
    val restoreFromHistory by restoreHistoryNode(historyMessages)

    val nodeCallLLM by node<String, Message.Assistant>("__stream_single_run_call_llm__") { input ->
        llm.writeSession {
            appendPrompt {
                user(input)
            }
            streamRequestWithEmptyRetry()
        }
    }

    val nodeExecuteTool by nodeExecuteMultipleTools(
        parallelTools = true,
        name = "__stream_single_run_execute_tools__",
    )

    val nodeSendToolResult by node<List<ReceivedToolResult>, Message.Assistant>(
        "__stream_single_run_send_tool_result__"
    ) { results ->
        llm.writeSession {
            appendPrompt {
                user {
                    results.forEach { toolResult(it.toMessagePart()) }
                }
                user(TOOL_RESULTS_CONTINUATION_PROMPT)
            }
            streamRequestWithEmptyRetry()
        }
    }

    nodeStart then restoreFromHistory then nodeCallLLM

    // Tool-call edge first: when a response carries BOTH text and tool calls
    // the tool loop must continue (declaration order resolves the tie, same
    // as singleRunWithParallelAbility).
    edge(
        nodeCallLLM forwardTo nodeExecuteTool onCondition { msg: Message.Assistant ->
            msg.parts.any { it is MessagePart.Tool.Call }
        } transformed { msg: Message.Assistant -> listOf(msg) }
    )
    edge(
        nodeCallLLM forwardTo nodeFinish onCondition { msg: Message.Assistant ->
            msg.parts.none { it is MessagePart.Tool.Call }
        } transformed { msg: Message.Assistant -> msg.textContent() }
    )

    edge(nodeExecuteTool forwardTo nodeSendToolResult)

    edge(
        nodeSendToolResult forwardTo nodeExecuteTool onCondition { msg: Message.Assistant ->
            msg.parts.any { it is MessagePart.Tool.Call }
        } transformed { msg: Message.Assistant -> listOf(msg) }
    )
    edge(
        nodeSendToolResult forwardTo nodeFinish onCondition { msg: Message.Assistant ->
            msg.parts.none { it is MessagePart.Tool.Call }
        } transformed { msg: Message.Assistant -> msg.textContent() }
    )
}

/**
 * One streamed LLM round: collect every [StreamFrame] into a single
 * [Message.Assistant] and append it to history (streaming requests do NOT
 * auto-append, unlike `requestLLM()`).
 *
 * Returns null when the stream produced no frames at all (Koog's
 * `toMessageResponse()` throws on an empty list — that shape is OpenRouter's
 * "empty response" failure mode, handled by the caller's retry).
 */
private suspend fun AIAgentLLMWriteSession.streamOnceAndCollect(): Message.Assistant? {
    val frames = mutableListOf<StreamFrame>()
    requestLLMStreaming().collect { frames.add(it) }
    val response = try {
        frames.toMessageResponse()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    appendPrompt {
        message(response)
    }
    return response
}

/**
 * Streamed counterpart of [requestWithOpenRouterEmptyResponseRetry] +
 * [requestLLMSanitizingReasoningOnlyResponses]:
 *
 *   1. Stream once. Valid payload (text or tool call) → done.
 *   2. Invalid/empty → drop the bad assistant message from history, append the
 *      standard retry prompt, stream again.
 *   3. Still invalid → final fallback through the non-streaming
 *      [requestLLMPreservingDeepSeekReasoning] (which has its own
 *      reasoning-only sanitation), so one provider hiccup on the streaming
 *      endpoint never fails the whole turn.
 */
internal suspend fun AIAgentLLMWriteSession.streamRequestWithEmptyRetry(): Message.Assistant {
    val first = streamOnceAndCollect()
    if (first != null && first.hasProviderValidAssistantPayload()) return first

    if (first != null) dropLastNMessages(1)
    appendPrompt {
        user(buildOpenRouterEmptyResponseRetryPrompt())
    }
    val second = streamOnceAndCollect()
    if (second != null && second.hasProviderValidAssistantPayload()) return second

    if (second != null) dropLastNMessages(1)
    return requestLLMPreservingDeepSeekReasoning()
}
