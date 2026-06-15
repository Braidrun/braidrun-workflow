package com.fartech.agents.commons

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenRouterResponseCompatTest {

    @Test
    fun `detects OpenRouter empty response error`() {
        val message = "Error from client: OpenRouterLLMClient Unexpected response: no tool calls and no content"
        assertTrue(isOpenRouterEmptyResponseError(message))
    }

    @Test
    fun `does not treat authentication failures as empty response errors`() {
        val message = """
            Error from client: OpenRouterLLMClient
            Status code: 401
            Error body:
            {"error":{"message":"Missing Authentication header","code":401}}
        """.trimIndent()
        assertFalse(isOpenRouterEmptyResponseError(message))
    }

    @Test
    fun `retries once after OpenRouter empty response`() = runBlocking {
        var attempts = 0
        val appendedPrompts = mutableListOf<String>()

        val result = requestWithOpenRouterEmptyResponseRetry(
            retryPrompt = "retry please",
            appendPromptFn = { appendedPrompts += it }
        ) {
            attempts += 1
            if (attempts == 1) {
                error("Error from client: OpenRouterLLMClient Unexpected response: no tool calls and no content")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(listOf("retry please"), appendedPrompts)
    }

    @Test
    fun `rethrows after second empty response attempt`() = runBlocking {
        var attempts = 0
        val appendedPrompts = mutableListOf<String>()

        val thrown = assertThrows<IllegalStateException> {
            runBlocking {
                requestWithOpenRouterEmptyResponseRetry(
                    retryPrompt = "retry please",
                    appendPromptFn = { appendedPrompts += it }
                ) {
                    attempts += 1
                    error("Error from client: OpenRouterLLMClient Unexpected response: no tool calls and no content")
                }
            }
        }

        assertTrue(thrown.message!!.contains("no tool calls and no content"))
        assertEquals(2, attempts)
        assertEquals(listOf("retry please"), appendedPrompts)
    }
}
