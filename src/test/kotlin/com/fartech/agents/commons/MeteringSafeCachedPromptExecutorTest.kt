package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.memory.InMemoryPromptCache
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.cached.CachedPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins that prompt-cache hits are never metered: they must carry no token
 * counts, while misses keep the provider's usage.
 */
class MeteringSafeCachedPromptExecutorTest {

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000,
    )

    private class CountingExecutor : PromptExecutor() {
        var calls = 0
        var streamingCalls = 0

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            calls++
            return Message.Assistant(content = "answer", metaInfo = usage(input = 50, output = 5), finishReason = "stop")
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            streamingCalls++
            emit(StreamFrame.TextDelta("answer"))
            emit(StreamFrame.End(finishReason = "stop", metaInfo = usage(input = 50, output = 5)))
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("not used")

        override fun close() = Unit
    }

    /** A follow-up round: history already holds the previous round's billed assistant message. */
    private val followUpPrompt = prompt("cache-hit-metering") {
        system("You are a test agent.")
        user("first question")
        message(Message.Assistant(content = "first answer", metaInfo = usage(input = 100, output = 20), finishReason = "stop"))
        user("second question")
    }

    @Test
    fun `cache miss keeps the provider usage and a hit reports none`() = runBlocking {
        val nested = CountingExecutor()
        val executor = MeteringSafeCachedPromptExecutor(InMemoryPromptCache(maxEntries = 16), nested)

        val miss = executor.execute(followUpPrompt, model, emptyList())
        val hit = executor.execute(followUpPrompt, model, emptyList())

        assertEquals(1, nested.calls, "second identical request must be served from the cache")
        assertEquals(50, miss.metaInfo.inputTokensCount)
        assertEquals(5, miss.metaInfo.outputTokensCount)
        assertEquals("answer", hit.textContent())
        assertNull(hit.metaInfo.inputTokensCount)
        assertNull(hit.metaInfo.outputTokensCount)
        assertNull(hit.metaInfo.totalTokensCount)
        assertEquals(JsonPrimitive(true), hit.metaInfo.metadata?.get(PROMPT_CACHE_HIT_METADATA_KEY))
        assertNull(miss.metaInfo.metadata?.get(PROMPT_CACHE_HIT_METADATA_KEY))
    }

    @Test
    fun `streaming bypasses the cache and keeps the provider usage`() = runBlocking {
        // Koog's cached streaming replays a non-streaming call as frames, which kills live typing.
        val nested = CountingExecutor()
        val executor = MeteringSafeCachedPromptExecutor(InMemoryPromptCache(maxEntries = 16), nested)

        val ends = List(2) {
            executor.executeStreaming(followUpPrompt, model, emptyList()).toList()
                .filterIsInstance<StreamFrame.End>().single()
        }

        assertEquals(2, nested.streamingCalls, "every streamed request reaches the provider live")
        assertEquals(0, nested.calls)
        ends.forEach { assertEquals(50, it.metaInfo.inputTokensCount) }
    }

    /**
     * Why the wrapper exists: Koog's own cache hit re-reports the PREVIOUS
     * round's usage. If this starts failing after a Koog upgrade, the upstream
     * semantics changed and the wrapper may no longer be needed.
     */
    @Test
    fun `koog cache hit re-reports the previous round's usage`() = runBlocking {
        val executor = CachedPromptExecutor(InMemoryPromptCache(maxEntries = 16), CountingExecutor())

        executor.execute(followUpPrompt, model, emptyList())
        val hit = executor.execute(followUpPrompt, model, emptyList())

        assertEquals(100, hit.metaInfo.inputTokensCount)
        assertEquals(20, hit.metaInfo.outputTokensCount)
    }

    private companion object {
        fun usage(input: Int, output: Int) = ResponseMetaInfo.create(
            KoogClock.System,
            totalTokensCount = input + output,
            inputTokensCount = input,
            outputTokensCount = output,
        )
    }
}
