package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Anthropic `max_tokens` when the workflow sets none: a 16K reply budget without streaming. */
internal const val ANTHROPIC_DEFAULT_MAX_TOKENS = 16_000

/** Streaming allows long outputs without request timeouts, so the default budget is larger. */
internal const val ANTHROPIC_STREAMING_DEFAULT_MAX_TOKENS = 64_000

/**
 * [LLMClient] decorator that fits the shared prompt [LLMParams] to the model a request is
 * actually sent to.
 *
 * The prompt's params are shared by the primary model, the fallback model and every cascade
 * tier, so they can only be corrected per request, where the concrete [LLModel] is known.
 * Applied by [createLLMClient] to every client it builds (the same way Koog's
 * `RetryingLLMClient` decorates), so every route — execute, streaming, multiple choices,
 * moderation — sees the corrected params:
 *
 * - `temperature` is dropped when the model does not declare [LLMCapability.Temperature] or
 *   [ModelQuirks.rejectsSampling] (Claude Opus 4.7+, Sonnet 5, Fable / Mythos 5.x return 400).
 * - A forced tool choice ([LLMParams.ToolChoice.Required] / [LLMParams.ToolChoice.Named]) is
 *   downgraded to [LLMParams.ToolChoice.Auto] when the model does not declare
 *   [LLMCapability.ToolChoice] or [ModelQuirks.rejectsForcedToolChoice]. The engine forces tool
 *   calls itself (onlyCallingTools, tool-cycle feedback), so omitting the capability in the
 *   catalog is not enough.
 * - For the direct Anthropic provider with no `max_tokens`, [ANTHROPIC_DEFAULT_MAX_TOKENS]
 *   ([ANTHROPIC_STREAMING_DEFAULT_MAX_TOKENS] when streaming) capped by the model's output
 *   ceiling replaces Koog's 2048 default, which truncates the thinking-by-default Claude 5
 *   models (thinking shares the budget).
 */
internal class ModelParamsSanitizingLLMClient(
    private val delegate: LLMClient,
) : LLMClient(), DelegatingLLMClient {

    override val wrapped: LLMClient get() = delegate

    override val clientName: String get() = delegate.clientName

    override fun llmProvider(): LLMProvider = delegate.llmProvider()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = delegate.execute(prompt.sanitizedFor(model, streaming = false), model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt.sanitizedFor(model, streaming = true), model, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = delegate.executeMultipleChoices(prompt.sanitizedFor(model, streaming = false), model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt.sanitizedFor(model, streaming = false), model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override suspend fun embed(text: String, model: LLModel): List<Double> = delegate.embed(text, model)

    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> =
        delegate.embed(inputs, model)

    override fun getStandardJsonSchemaGenerator(): StandardJsonSchemaGenerator =
        delegate.getStandardJsonSchemaGenerator()

    override fun getBasicJsonSchemaGenerator(): BasicJsonSchemaGenerator = delegate.getBasicJsonSchemaGenerator()

    override fun close() = delegate.close()

    private fun Prompt.sanitizedFor(model: LLModel, streaming: Boolean): Prompt {
        val sanitized = params.sanitizedFor(model, streaming)
        return if (sanitized === params) this else withParams(sanitized)
    }
}

/**
 * [LLMParams] adjusted for [model] per the rules on [ModelParamsSanitizingLLMClient].
 * Returns the same instance when nothing changes. Provider-specific params subclasses are
 * preserved (their `copy` overrides keep the subclass).
 */
internal fun LLMParams.sanitizedFor(model: LLModel, streaming: Boolean): LLMParams {
    val dropTemperature = temperature != null &&
        (!model.supports(LLMCapability.Temperature) || ModelQuirks.rejectsSampling(model))
    val forcedToolChoice = toolChoice == LLMParams.ToolChoice.Required || toolChoice is LLMParams.ToolChoice.Named
    val downgradeToolChoice = forcedToolChoice &&
        (!model.supports(LLMCapability.ToolChoice) || ModelQuirks.rejectsForcedToolChoice(model))
    val defaultMaxTokens = if (maxTokens == null && model.provider == LLMProvider.Anthropic) {
        anthropicDefaultMaxTokens(model, streaming)
    } else {
        null
    }
    if (!dropTemperature && !downgradeToolChoice && defaultMaxTokens == null) return this

    logger.debug {
        "Adjusting LLM params for ${model.provider.id}/${model.id}: " +
            listOfNotNull(
                "temperature dropped".takeIf { dropTemperature },
                "forced tool_choice -> auto".takeIf { downgradeToolChoice },
                defaultMaxTokens?.let { "max_tokens=$it" },
            ).joinToString()
    }
    return copy(
        temperature = if (dropTemperature) null else temperature,
        toolChoice = if (downgradeToolChoice) LLMParams.ToolChoice.Auto else toolChoice,
        maxTokens = defaultMaxTokens ?: maxTokens,
    )
}

internal fun anthropicDefaultMaxTokens(model: LLModel, streaming: Boolean): Int {
    val budget = if (streaming) ANTHROPIC_STREAMING_DEFAULT_MAX_TOKENS else ANTHROPIC_DEFAULT_MAX_TOKENS
    val ceiling = (model.maxOutputTokens ?: ModelQuirks.legacyClaudeMaxOutputTokens(model))
        ?.takeIf { it > 0 }
        ?: return budget
    return minOf(budget.toLong(), ceiling).toInt()
}

/**
 * An [LLMClient] decorator that forwards every request to [wrapped]. Code that needs to know which
 * provider client actually serves a request (e.g. [ToolResultImageSupport.route]) looks through
 * these with [unwrapDecorators] instead of seeing only the outermost wrapper.
 */
internal interface DelegatingLLMClient {
    val wrapped: LLMClient
}

/** The provider client underneath any chain of [DelegatingLLMClient] decorators. */
internal fun LLMClient.unwrapDecorators(): LLMClient {
    var current = this
    while (current is DelegatingLLMClient) current = current.wrapped
    return current
}
