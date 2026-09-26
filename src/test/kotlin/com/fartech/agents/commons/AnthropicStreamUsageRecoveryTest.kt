package com.fartech.agents.commons

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import com.fartech.agents.workflow.WorkflowHostPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Koog 1.3.0 keeps the prompt-cache counts of a non-streamed Anthropic response in the
 * metadata but drops them from a streamed one; [AnthropicStreamUsageRecoveringClient] puts
 * them back on `StreamFrame.End`. Exercised over real HTTP + SSE against [FakeAnthropicApi].
 */
class AnthropicStreamUsageRecoveryTest {

    private val longSystem = "Follow the house style guide exactly. ".repeat(200)
    private lateinit var api: FakeAnthropicApi
    private lateinit var client: LLMClient
    private lateinit var model: LLModel

    @BeforeEach
    fun setUp() {
        WorkflowHostPolicy.resetForTests()
        api = FakeAnthropicApi(SimulatedAnthropicPromptCache(minCacheableTokens = 256)) { _, _ ->
            listOf(FakeAnthropicApi.text("hello"))
        }
        val config = LLModelConfig(provider = "anthropic", model = "claude-sonnet-4-6", baseUrl = api.baseUrl)
        client = createLLMClient(emptyList(), config, mapOf("anthropic" to "sk-ant-test"), HttpClientFactoryResolver.resolve()).second
        model = determineLLMModel(config)
    }

    @AfterEach
    fun tearDown() = api.close()

    private fun chat(question: String) = prompt("stream") {
        system(longSystem)
        user(question)
    }

    @Test
    fun `streamed rounds report the cache counts the API returned`() = runBlocking {
        val first = client.executeStreaming(chat("first question"), model).toList().filterIsInstance<StreamFrame.End>().single()
        val second = client.executeStreaming(chat("second question"), model).toList().filterIsInstance<StreamFrame.End>().single()

        val (firstWire, secondWire) = api.exchanges.map { it.usage }
        assertTrue(api.exchanges.all { it.streamed })
        assertTrue(firstWire.cacheCreationInputTokens > 0 && secondWire.cacheReadInputTokens > 0, "$firstWire / $secondWire")

        val firstUsage = first.metaInfo.tokenUsage()
        assertEquals(firstWire.inputTokens, firstUsage.inputTokens)
        assertEquals(firstWire.cacheCreationInputTokens, firstUsage.cacheCreationTokens)
        val secondUsage = second.metaInfo.tokenUsage()
        assertEquals(secondWire.inputTokens, secondUsage.inputTokens)
        assertEquals(secondWire.cacheReadInputTokens, secondUsage.cacheReadTokens)
        assertEquals(secondWire.promptTokens, secondUsage.promptTokens)
    }

    @Test
    fun `non-streamed rounds report the same counts through Koog's own metadata`() = runBlocking {
        client.execute(chat("first question"), model)
        val response = client.execute(chat("second question"), model)

        val wire = api.exchanges.last().usage
        val usage = response.metaInfo.tokenUsage()
        assertEquals(wire.cacheReadInputTokens, usage.cacheReadTokens)
        assertEquals(wire.inputTokens, usage.inputTokens)
    }
}
