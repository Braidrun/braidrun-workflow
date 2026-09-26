package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import com.fartech.agents.tools.BrowserScreenshotTool
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Guards the production wiring: removing the adapter from [createPromptExecutor] (primary or cascade
 * tier), the cache bypass, or the class-based `browser_screenshot` registration must fail a test.
 */
class ToolResultMediaWiringTest {

    private val image = ToolResultMediaFixtures.readyImage()
    private val imageBase64 = Base64.getEncoder().encodeToString(image.bytes)
    private val model = OpenAIModels.Chat.GPT4o

    private val chatCompletionJson = """
        {"id":"x","object":"chat.completion","created":0,"model":"gpt-4o","choices":[{
          "index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"
        }],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """.trimIndent()

    private fun params(vararg pairs: Pair<String, Any>) = pairs.map { (k, v) ->
        ConfigurationParameter(
            key = k,
            value = when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            },
        )
    }

    private fun fallback() = MultiLLMPromptExecutor.FallbackPromptExecutorSettings(
        fallbackProvider = LLMProvider.OpenAI,
        fallbackModel = model,
    )

    /** Records the prompt it receives, then fails so the cascade advances. */
    private class FailingClient : LLMClient() {
        val prompts = mutableListOf<Prompt>()
        override val clientName = "failing"
        override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts += prompt
            throw IllegalStateException("primary down")
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            error("not used")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() = Unit
    }

    @Test
    fun `createPromptExecutor adapts tool-result images on the primary and on cascade tiers`() {
        val primary = FailingClient()
        val http = RecordingKoogHttpClient { _, _ -> chatCompletionJson }
        val executor = createPromptExecutor(
            parameters = params("cascade_fallback_enabled" to true, "retry_max_attempts" to 1),
            llmClients = mapOf<LLMProvider, LLMClient>(LLMProvider.OpenAI to primary) to fallback(),
            extraCascadeTiers = listOf(
                mapOf<LLMProvider, LLMClient>(
                    LLMProvider.OpenAI to OpenAILLMClient(settings = OpenAIClientSettings(), httpClient = http)
                ) to fallback()
            ),
        )

        runBlocking {
            executor.execute(
                ToolResultMediaFixtures.promptWithToolImage(image),
                model,
                listOf(ToolResultMediaFixtures.screenshotDescriptor),
            )
        }

        // Primary tier: an unknown client is text-only, so the attachment is already a placeholder.
        val primaryResult = ToolResultMediaFixtures.toolResults(primary.prompts.first()).single()
        assertTrue(primaryResult.parts.none { it is MessagePart.Attachment }, "primary got raw image")
        assertTrue((primaryResult.parts.single() as MessagePart.Text).text.contains("[image omitted (image/png"))
        // Cascade tier: GPT-4o over Chat Completions, placeholder on the wire and no bytes.
        val body = http.bodies.single().second
        assertFalse(body.contains(imageBase64), "image bytes reached a Chat Completions route")
        assertTrue(body.contains("[image omitted (image/png"), body)
    }

    @Test
    fun `prompts with tool-result images bypass the prompt cache, plain prompts are still cached`() {
        val http = RecordingKoogHttpClient { _, _ -> chatCompletionJson }
        val executor = createPromptExecutor(
            parameters = params("cache_policy" to "memory"),
            llmClients = mapOf<LLMProvider, LLMClient>(
                LLMProvider.OpenAI to OpenAILLMClient(settings = OpenAIClientSettings(), httpClient = http)
            ) to fallback(),
            extraCascadeTiers = emptyList(),
        )
        val withImage = ToolResultMediaFixtures.promptWithToolImage(image)
        val plain = prompt("plain") { user("hello") }

        runBlocking {
            repeat(2) { executor.execute(withImage, model, listOf(ToolResultMediaFixtures.screenshotDescriptor)) }
            repeat(2) { executor.execute(plain, model, emptyList()) }
        }

        assertEquals(3, http.bodies.size, "image prompt must reach the provider twice, the plain one once")
    }

    @Test
    fun `default registry exposes browser_screenshot only as BrowserScreenshotTool`() {
        val registry = getDefaultToolRegistry(
            parameters = params("session_id" to "wiring-test"),
            httpAccess = HttpAccess(),
            skillManager = null,
        )
        val screenshots = registry.tools.filter { it.name == BrowserScreenshotTool.NAME }
        assertEquals(1, screenshots.size, "exactly one browser_screenshot tool")
        assertTrue(screenshots.single() is BrowserScreenshotTool, screenshots.single()::class.qualifiedName)
    }
}
