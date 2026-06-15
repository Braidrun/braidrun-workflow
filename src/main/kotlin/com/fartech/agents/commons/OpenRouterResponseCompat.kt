package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor

private const val OPEN_ROUTER_CLIENT_MARKER = "OpenRouterLLMClient"
private const val OPEN_ROUTER_PROVIDER_MARKER = "OpenRouter"
private const val EMPTY_RESPONSE_MARKER = "no tool calls and no content"
private const val EMPTY_CONTENT_MARKER = "Empty content"
private const val REASONING_CONTENT_MARKER = "reasoning_content"

@PublishedApi
internal fun isOpenRouterEmptyResponseError(message: String?): Boolean {
    val msg = message?.takeIf { it.isNotBlank() } ?: return false
    val mentionsOpenRouter = msg.contains(OPEN_ROUTER_CLIENT_MARKER, ignoreCase = true) ||
        msg.contains(OPEN_ROUTER_PROVIDER_MARKER, ignoreCase = true)
    val missingContent = msg.contains(EMPTY_RESPONSE_MARKER, ignoreCase = true) ||
        msg.contains(EMPTY_CONTENT_MARKER, ignoreCase = true)
    return mentionsOpenRouter && missingContent
}

@PublishedApi
internal fun isOpenRouterReasoningOnlyError(message: String?): Boolean {
    val msg = message?.takeIf { it.isNotBlank() } ?: return false
    return msg.contains(REASONING_CONTENT_MARKER, ignoreCase = true) ||
        msg.contains(EMPTY_CONTENT_MARKER, ignoreCase = true) ||
        isOpenRouterEmptyResponseError(msg)
}

@PublishedApi
internal fun buildOpenRouterEmptyResponseRetryPrompt(
    onlyCallingTools: Boolean = false,
    exitToolName: String? = null
): String = if (onlyCallingTools) {
    require(!exitToolName.isNullOrBlank()) { "exitToolName is required when onlyCallingTools is true" }
    """
    Your previous reply was empty.
    Retry now and do not leave the response blank.
    You MUST call at least one tool, and call $exitToolName when the task is complete.
    Do not answer with plain text only.
    """.trimIndent()
} else {
    """
    Your previous reply was empty.
    Retry now and do not leave the response blank.
    Return either a concrete assistant message or the appropriate tool call(s) to continue the task.
    """.trimIndent()
}

@PublishedApi
internal suspend fun <T> requestWithOpenRouterEmptyResponseRetry(
    retryPrompt: String,
    appendPromptFn: suspend (String) -> Unit,
    requestFn: suspend () -> T,
): T {
    var retried = false
    while (true) {
        try {
            return requestFn()
        } catch (e: Exception) {
            if (retried || !isOpenRouterEmptyResponseError(e.message)) {
                throw e
            }
            retried = true
            printlnColor(
                AnsiColor.YELLOW,
                "WARNING: OpenRouter returned no tool calls and no content; retrying once with a stricter follow-up prompt."
            )
            appendPromptFn(retryPrompt)
        }
    }
}
