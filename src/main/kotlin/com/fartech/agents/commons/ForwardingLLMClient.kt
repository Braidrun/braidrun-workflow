package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import kotlinx.coroutines.flow.Flow

/**
 * [LLMClient] that forwards every call to [delegate]; decorators override only the calls they
 * change. It is a [DelegatingLLMClient], so [unwrapDecorators] (tool-result image routing) still
 * reaches the provider client underneath.
 */
internal abstract class ForwardingLLMClient(protected val delegate: LLMClient) : LLMClient(), DelegatingLLMClient {

    override val wrapped: LLMClient get() = delegate

    override val clientName: String get() = delegate.clientName

    override fun llmProvider(): LLMProvider = delegate.llmProvider()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = delegate.execute(prompt, model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = delegate.executeMultipleChoices(prompt, model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override suspend fun embed(text: String, model: LLModel): List<Double> = delegate.embed(text, model)

    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> =
        delegate.embed(inputs, model)

    override fun getStandardJsonSchemaGenerator(): StandardJsonSchemaGenerator =
        delegate.getStandardJsonSchemaGenerator()

    override fun getBasicJsonSchemaGenerator(): BasicJsonSchemaGenerator = delegate.getBasicJsonSchemaGenerator()

    override fun close() = delegate.close()
}
