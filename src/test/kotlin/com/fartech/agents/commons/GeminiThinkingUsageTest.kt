package com.fartech.agents.commons

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import com.fartech.agents.workflow.WorkflowHostPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Gemini bills thinking tokens at the output rate, but Koog 1.3.0's `GoogleLLMClient` reports
 * them only in `totalTokensCount`. [GeminiThinkingUsageClient] (installed by [createLLMClient])
 * moves them into the output. Exercised through the real Koog client decoding canned
 * `generateContent` / `streamGenerateContent` payloads, so a Koog change to the usage mapping
 * shows up here.
 */
class GeminiThinkingUsageTest {

    private val config = LLModelConfig(provider = "google", model = "gemini-2.5-pro")
    private val model: LLModel = determineLLMModel(config)
    private val chat = prompt("gemini") { user("hi") }

    @BeforeEach
    fun setUp() = WorkflowHostPolicy.resetForTests()

    private fun client(
        respond: String = "{}",
        stream: List<String> = emptyList(),
        provider: LLModelConfig = config,
    ): LLMClient = createLLMClient(
        emptyList(),
        provider,
        mapOf("google" to "test-google-key", "anthropic" to "sk-ant-test"),
        CapturingHttpClientFactory(respond = { _, _ -> respond }, stream = { _, _ -> stream }),
    ).second

    /** A `GenerateContentResponse` as the Gemini API sends it; null counts are omitted. */
    private fun geminiResponse(
        prompt: Int,
        candidates: Int?,
        thoughts: Int?,
        total: Int,
        cached: Int? = null,
        choices: Int = 1,
        finishReason: String? = "STOP",
        text: String = "hello",
    ): String = buildJsonObject {
        putJsonArray("candidates") {
            repeat(choices) { index ->
                addJsonObject {
                    putJsonObject("content") {
                        put("role", "model")
                        putJsonArray("parts") { addJsonObject { put("text", "$text $index") } }
                    }
                    finishReason?.let { put("finishReason", it) }
                    put("index", index)
                }
            }
        }
        putJsonObject("usageMetadata") {
            put("promptTokenCount", prompt)
            candidates?.let { put("candidatesTokenCount", it) }
            thoughts?.let { put("thoughtsTokenCount", it) }
            cached?.let { put("cachedContentTokenCount", it) }
            put("totalTokenCount", total)
        }
        put("modelVersion", "gemini-2.5-pro")
    }.toString()

    @Test
    fun `non-streamed thinking tokens are counted as output`() = runBlocking {
        val response = client(geminiResponse(prompt = 120, candidates = 30, thoughts = 450, total = 600))
            .execute(chat, model)

        // Koog's own consumers (tracing) read the raw counts, so the fix is in the metaInfo itself.
        assertEquals(480, response.metaInfo.outputTokensCount)
        assertEquals(600, response.metaInfo.totalTokensCount)
        assertEquals(
            LlmTokenUsage(inputTokens = 120, outputTokens = 480, totalTokens = 600, reasoningTokens = 450),
            response.metaInfo.tokenUsage(),
        )
    }

    @Test
    fun `streamed thinking tokens reach the End frame as output`() = runBlocking {
        val chunks = listOf(
            // Intermediate chunks carry running usage but no finish reason, so no End frame.
            geminiResponse(prompt = 120, candidates = null, thoughts = 450, total = 570, finishReason = null, text = "hel"),
            geminiResponse(prompt = 120, candidates = 30, thoughts = 450, total = 600, text = "lo"),
        )
        val end = client(stream = chunks).executeStreaming(chat, model).toList()
            .filterIsInstance<StreamFrame.End>().single()

        assertEquals(
            LlmTokenUsage(inputTokens = 120, outputTokens = 480, totalTokens = 600, reasoningTokens = 450),
            end.metaInfo.tokenUsage(),
        )
    }

    @Test
    fun `every choice of a multi-candidate response is normalized`() = runBlocking {
        val choices = client(geminiResponse(prompt = 100, candidates = 60, thoughts = 900, total = 1_060, choices = 2))
            .executeMultipleChoices(chat, model)

        assertEquals(2, choices.size)
        choices.forEach { assertEquals(960, it.metaInfo.tokenUsage().outputTokens) }
    }

    @Test
    fun `implicit-cache hit moves the cached prompt out and the thinking into the output`() = runBlocking {
        val usage = client(geminiResponse(prompt = 10_000, candidates = 200, thoughts = 50, total = 10_250, cached = 8_000))
            .execute(chat, model).metaInfo.tokenUsage()

        assertEquals(
            LlmTokenUsage(
                inputTokens = 2_000,
                outputTokens = 250,
                totalTokens = 2_250,
                cacheReadTokens = 8_000,
                reasoningTokens = 50,
            ),
            usage,
        )
    }

    @Test
    fun `a response without thinking passes through unchanged`() = runBlocking {
        val usage = client(geminiResponse(prompt = 120, candidates = 30, thoughts = null, total = 150))
            .execute(chat, model).metaInfo.tokenUsage()

        assertEquals(LlmTokenUsage(inputTokens = 120, outputTokens = 30, totalTokens = 150), usage)
    }

    @Test
    fun `only the Google client gets the thinking decorator`() {
        fun LLMClient.chain(): List<LLMClient> = generateSequence(this) { (it as? DelegatingLLMClient)?.wrapped }.toList()

        assertTrue(client().chain().any { it is GeminiThinkingUsageClient })
        val anthropic = client(provider = LLModelConfig(provider = "anthropic", model = "claude-sonnet-4-6"))
        assertFalse(anthropic.chain().any { it is GeminiThinkingUsageClient })
    }

    /**
     * [withGeminiThinkingInOutput] treats every non-prompt, non-candidate token of the total as
     * thinking. That is only right while braidrun cannot enable Google-executed tools (Search
     * grounding, code execution, URL context), whose `toolUsePromptTokenCount` also sits in the
     * total and is billed as input. If Koog's request model grows such a tool, subtract that
     * count before upgrading.
     */
    @Test
    fun `Koog's Gemini request can only declare client-side functions`() {
        val googleTool = Class.forName("ai.koog.prompt.executor.clients.google.models.GoogleTool")
        val fields = googleTool.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("functionDeclarations"), fields)

        val request = Class.forName("ai.koog.prompt.executor.clients.google.models.GoogleRequest")
        assertFalse(
            request.declaredFields.any { it.name == "additionalProperties" },
            "GoogleParams.additionalProperties must stay confined to generationConfig",
        )
    }
}
