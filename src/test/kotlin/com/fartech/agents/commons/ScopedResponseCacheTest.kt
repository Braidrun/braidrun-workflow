package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.memory.InMemoryPromptCache
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [MeteringSafeCachedPromptExecutor] keys responses by model, full tool schemas and prompt. */
class ScopedResponseCacheTest {

    private fun model(id: String) = LLModel(
        provider = LLMProvider.Anthropic,
        id = id,
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 200_000,
    )

    /** Answers with the model id it was asked, so a replay from the wrong model is visible. */
    private class ModelEchoExecutor : PromptExecutor() {
        var calls = 0

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            calls++
            return Message.Assistant(content = "answer from ${model.id}", metaInfo = ResponseMetaInfo.create(KoogClock.System))
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")

        override fun close() = Unit
    }

    private val chat = prompt("scoped") {
        system("You are a test agent.")
        user("same question")
    }

    @Test
    fun `an identical prompt to another model is a miss, not the other model's answer`() = runBlocking {
        val nested = ModelEchoExecutor()
        val executor = MeteringSafeCachedPromptExecutor(InMemoryPromptCache(maxEntries = 16), nested)

        executor.execute(chat, model("claude-sonnet-4-6"), emptyList())
        val switched = executor.execute(chat, model("claude-opus-4-7"), emptyList())
        val repeated = executor.execute(chat, model("claude-opus-4-7"), emptyList())

        assertEquals("answer from claude-opus-4-7", switched.textContent())
        assertEquals("answer from claude-opus-4-7", repeated.textContent())
        assertEquals(2, nested.calls, "the repeat on the same model is still a hit")
    }

    @Test
    fun `tools that differ only in their parameter schema do not share an entry`() = runBlocking {
        val nested = ModelEchoExecutor()
        val executor = MeteringSafeCachedPromptExecutor(InMemoryPromptCache(maxEntries = 16), nested)
        fun lookup(type: ToolParameterType) =
            listOf(ToolDescriptor("lookup", "Looks up.", listOf(ToolParameterDescriptor("q", "query", type))))

        executor.execute(chat, model("m"), lookup(ToolParameterType.String))
        executor.execute(chat, model("m"), lookup(ToolParameterType.Integer))

        assertEquals(2, nested.calls)
    }

    @Test
    fun `a colliding storage key is a miss, not someone else's answer`() = runBlocking {
        // Every lookup finds the one stored entry, as if all keys collided.
        val colliding = object : PromptCache {
            var stored: Message.Assistant? = null
            override suspend fun get(request: PromptCache.Request): Message.Assistant? = stored
            override suspend fun put(request: PromptCache.Request, response: Message.Assistant) {
                stored = response
            }
        }
        val nested = ModelEchoExecutor()
        val executor = MeteringSafeCachedPromptExecutor(colliding, nested)

        executor.execute(chat, model("m"), emptyList())
        val other = prompt("scoped") { system("You are a test agent."); user("a different question") }
        executor.execute(other, model("m"), emptyList())

        assertEquals(2, nested.calls)
    }

    @Test
    fun `prompt-cache markers do not change the key`() = runBlocking {
        val nested = ModelEchoExecutor()
        val executor = MeteringSafeCachedPromptExecutor(InMemoryPromptCache(maxEntries = 16), nested)
        val marked = prompt("scoped") {
            system("You are a test agent.", cache = AnthropicCacheControl.Default)
            user("same question")
        }

        executor.execute(chat, model("m"), emptyList())
        executor.execute(marked, model("m"), emptyList())

        assertEquals(1, nested.calls)
    }
}
