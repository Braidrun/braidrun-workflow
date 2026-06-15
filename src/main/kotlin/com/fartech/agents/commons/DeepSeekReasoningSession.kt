package com.fartech.agents.commons

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * DeepSeek reasoning-preservation helpers for Koog 1.0.0.
 *
 * ## Why these existed (Koog 0.8.0)
 *
 * Koog's single-response helpers (`requestLLM` / `requestLLMOnlyCallingTools` /
 * `requestLLMWithoutTools`) skipped `Message.Reasoning` and only appended the
 * first non-reasoning response to history. DeepSeek V4 thinking mode needs the
 * raw `reasoning_content` preserved in history; the helpers below fetched
 * **all** responses via the `*Multiple` variants and filtered to the visible
 * one so DeepSeek's session-level `reasoning_content` requirement was honoured.
 *
 * ## Why they are identity wrappers now (Koog 1.0.0)
 *
 * Koog 1.0.0 folded reasoning, plain text, and tool calls into a single
 * `Message.Assistant` with a `parts: List<MessagePart.ResponsePart>` list:
 *
 *   - `MessagePart.Text` — assistant text
 *   - `MessagePart.Reasoning` — thinking content
 *   - `MessagePart.Tool.Call` — tool invocations
 *
 * `requestLLM*` now returns the whole message including reasoning. Reasoning is
 * preserved automatically, but OpenAI-compatible providers still reject a
 * history entry whose assistant message contains only `reasoning_content` and
 * neither `content` nor `tool_calls`. These wrappers keep the reasoning support
 * and sanitize/retry that provider-invalid edge case before the next request.
 */

@PublishedApi
internal suspend fun AIAgentLLMWriteSession.requestLLMWithoutToolsPreservingDeepSeekReasoning(): Message.Assistant =
    requestLLMSanitizingReasoningOnlyResponses(
        allowToolCalls = false,
        request = { requestLLMWithoutTools() }
    )

@PublishedApi
internal suspend fun AIAgentLLMWriteSession.requestLLMPreservingDeepSeekReasoning(): Message.Assistant =
    requestLLMSanitizingReasoningOnlyResponses(
        allowToolCalls = true,
        request = { requestLLM() }
    )

@PublishedApi
internal suspend fun AIAgentLLMWriteSession.requestLLMOnlyCallingToolsPreservingDeepSeekReasoning(): Message.Assistant =
    requestLLMSanitizingReasoningOnlyResponses(
        allowToolCalls = true,
        request = { requestLLMOnlyCallingTools() }
    )

@PublishedApi
internal suspend fun AIAgentLLMWriteSession.requestLLMMultiplePreservingDeepSeekReasoning(): List<Message.Assistant> =
    // Koog 1.0.0's write-session `requestLLMMultipleChoices()` returns choices
    // from the read session but does not append them back to prompt history.
    // Tool-cycle graphs need that assistant `tool_calls` message in history
    // before they append matching tool results, otherwise strict providers
    // reject the next request with "tool result's tool id ... not found".
    requestLLMMultipleChoices().map { choice ->
        choice.takeIf { it.hasProviderValidAssistantPayload() }
            ?: choice.withReasoningAsTextFallback()
    }.also { choices ->
        appendPrompt {
            choices.forEach { message(it) }
        }
    }

@PublishedApi
internal suspend fun AIAgentLLMWriteSession.requestLLMMultipleOnlyCallingToolsPreservingDeepSeekReasoning(): List<Message.Assistant> {
    // Koog 1.0.0 dropped the "multiple + only-calling-tools" variant; emulate by
    // requesting a single tool-call response and wrapping it as a single-element
    // list. Honest preservation of the old semantic isn't possible without the
    // dedicated upstream API, but for our use cases (DeepSeek thinking-mode
    // tool dispatch) a single tool-call message is what we always consumed.
    return listOf(requestLLMOnlyCallingToolsPreservingDeepSeekReasoning())
}

private suspend fun AIAgentLLMWriteSession.requestLLMSanitizingReasoningOnlyResponses(
    allowToolCalls: Boolean,
    request: suspend AIAgentLLMWriteSession.() -> Message.Assistant
): Message.Assistant {
    val first = request()
    if (first.hasProviderValidAssistantPayload()) return first

    dropLastNMessages(1)
    appendPrompt {
        user(
            buildString {
                append(
                    "Your previous response contained only reasoning and no assistant content"
                )
                if (allowToolCalls) {
                    append(" or tool call")
                }
                append(". That message cannot be sent back to the provider in chat history. ")
                if (allowToolCalls) {
                    append("Reply again now with either the required tool call or the final answer content.")
                } else {
                    append("Reply again now with the final answer content.")
                }
            }
        )
    }

    val second = request()
    if (second.hasProviderValidAssistantPayload()) return second

    dropLastNMessages(1)
    val sanitized = second.withReasoningAsTextFallback()
    appendPrompt {
        message(sanitized)
    }
    return sanitized
}

internal fun Message.Assistant.hasProviderValidAssistantPayload(): Boolean =
    parts.any { part ->
        when (part) {
            is MessagePart.Text -> part.text.isNotBlank()
            is MessagePart.Tool.Call -> true
            else -> false
        }
    }

internal fun Message.Assistant.withReasoningAsTextFallback(): Message.Assistant {
    val reasoningText = parts
        .filterIsInstance<MessagePart.Reasoning>()
        .flatMap { it.content }
        .joinToString("\n")
        .trim()
        .ifBlank { "The model returned no assistant content." }
    return Message.Assistant(
        content = reasoningText,
        metaInfo = metaInfo,
        finishReason = finishReason,
        rawResponse = rawResponse,
        id = id
    )
}
