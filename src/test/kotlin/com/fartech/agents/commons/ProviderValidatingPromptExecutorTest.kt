package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationCategory
import ai.koog.prompt.dsl.ModerationCategoryResult
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderValidatingPromptExecutorTest {

    private val sampleModel = LLModel(
        provider = LLMProvider.DeepSeek,
        id = "deepseek-v4-pro",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000
    )

    private class CapturingExecutor : PromptExecutor() {
        lateinit var capturedPrompt: Prompt

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            capturedPrompt = prompt
            return Message.Assistant(
                content = "ok",
                metaInfo = ResponseMetaInfo.Empty,
                finishReason = "stop"
            )
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            capturedPrompt = prompt
            emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
        }

        override suspend fun executeMultipleChoices(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): LLMChoice {
            capturedPrompt = prompt
            return listOf(Message.Assistant(content = "ok", metaInfo = ResponseMetaInfo.Empty))
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
            capturedPrompt = prompt
            return ModerationResult(
                isHarmful = false,
                categories = emptyMap<ModerationCategory, ModerationCategoryResult>()
            )
        }

        override fun close() = Unit
    }

    @Test
    fun `execute sanitizes reasoning-only assistant messages before provider call`() = runBlocking {
        val nested = CapturingExecutor()
        val executor = ProviderValidatingPromptExecutor(nested)
        val prompt = prompt("reasoning-only-history") {
            user("read the dataset")
            message(
                Message.Assistant(
                    parts = listOf(MessagePart.Reasoning("I should call readFile now.")),
                    metaInfo = ResponseMetaInfo.Empty,
                    finishReason = "stop"
                )
            )
            user("continue")
        }

        executor.execute(prompt, sampleModel, emptyList())

        val assistant = nested.capturedPrompt.messages.filterIsInstance<Message.Assistant>().single()
        assertTrue(assistant.hasProviderValidAssistantPayload())
        assertEquals("I should call readFile now.", assistant.textContent())
        assertTrue(assistant.parts.none { it is MessagePart.Reasoning })
    }
}
