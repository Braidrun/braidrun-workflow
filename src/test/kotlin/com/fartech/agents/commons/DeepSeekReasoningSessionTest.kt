package com.fartech.agents.commons

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeepSeekReasoningSessionTest {

    @Test
    fun `reasoning-only assistant is not a valid provider history payload`() {
        val response = Message.Assistant(
            parts = listOf(MessagePart.Reasoning("thinking without answer")),
            metaInfo = ResponseMetaInfo.Empty,
            finishReason = "stop"
        )

        assertFalse(response.hasProviderValidAssistantPayload())
    }

    @Test
    fun `assistant with text or tool call is a valid provider history payload`() {
        val textResponse = Message.Assistant(
            content = "final answer",
            metaInfo = ResponseMetaInfo.Empty,
            finishReason = "stop"
        )
        val toolResponse = Message.Assistant(
            parts = listOf(
                MessagePart.Tool.Call(
                    id = "call_read",
                    tool = "readFile",
                    args = JsonObject(emptyMap())
                )
            ),
            metaInfo = ResponseMetaInfo.Empty,
            finishReason = "tool_calls"
        )

        assertTrue(textResponse.hasProviderValidAssistantPayload())
        assertTrue(toolResponse.hasProviderValidAssistantPayload())
    }

    @Test
    fun `reasoning-only assistant is converted to assistant text fallback`() {
        val response = Message.Assistant(
            parts = listOf(
                MessagePart.Reasoning("first thought"),
                MessagePart.Reasoning("second thought")
            ),
            metaInfo = ResponseMetaInfo.Empty,
            finishReason = "stop"
        )

        val sanitized = response.withReasoningAsTextFallback()

        assertTrue(sanitized.hasProviderValidAssistantPayload())
        assertEquals("first thought\nsecond thought", sanitized.textContent())
        assertTrue(sanitized.parts.none { it is MessagePart.Reasoning })
    }
}
