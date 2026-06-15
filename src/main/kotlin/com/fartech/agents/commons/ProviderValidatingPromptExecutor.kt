package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import kotlinx.coroutines.flow.Flow

/**
 * Final guard before provider clients serialize a prompt into HTTP JSON.
 *
 * Koog 1.0 can represent an assistant response as reasoning-only. That is a
 * valid in-memory response shape, but OpenAI-compatible chat APIs reject a
 * historical assistant message that has neither `content` nor `tool_calls`.
 * Normalize those messages here so every caller path is protected, including
 * stock Koog nodes that do not use our DeepSeek session wrappers.
 */
class ProviderValidatingPromptExecutor(
    private val nested: PromptExecutor
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant =
        nested.execute(prompt.withProviderValidAssistantMessages(), model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> =
        nested.executeStreaming(prompt.withProviderValidAssistantMessages(), model, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice =
        nested.executeMultipleChoices(prompt.withProviderValidAssistantMessages(), model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        nested.moderate(prompt.withProviderValidAssistantMessages(), model)

    override suspend fun models(): List<LLModel> = nested.models()

    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator =
        nested.getStandardJsonSchemaGenerator(model)

    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator =
        nested.getBasicJsonSchemaGenerator(model)

    override fun close() {
        nested.close()
    }
}

internal fun Prompt.withProviderValidAssistantMessages(): Prompt =
    withMessages { messages ->
        messages.map { message ->
            if (message is Message.Assistant && !message.hasProviderValidAssistantPayload()) {
                message.withReasoningAsTextFallback()
            } else {
                message
            }
        }
    }
