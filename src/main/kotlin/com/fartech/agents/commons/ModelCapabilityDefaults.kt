package com.fartech.agents.commons

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider

/**
 * Adds the capabilities the Koog client serving [provider] needs before it will send a
 * request, when a catalog entry, workflow `custom_models` definition or dynamically created
 * model left them implicit. Declared capabilities are never removed.
 *
 * - OpenAI-client providers (OpenAI, DashScope, Kimi, MiniMax, LM Studio, Z.ai, NVIDIA — see
 *   [createLLMClient]): `OpenAILLMClient` throws "Cannot determine proper LLM params" for a model
 *   that declares neither [LLMCapability.OpenAIEndpoint.Completions] nor `.Responses`, so
 *   Chat Completions (what every one of these providers serves) is added.
 * - Direct Anthropic models that think by default ([ModelQuirks.thinksByDefault]): their replies
 *   carry thinking blocks, and replaying one in the next tool round requires
 *   [LLMCapability.Thinking] ("Model ... does not support thinking").
 */
internal fun withClientRequiredCapabilities(
    provider: LLMProvider,
    modelId: String,
    capabilities: List<LLMCapability>,
): List<LLMCapability> {
    val needsChatCompletions = isOpenAICompatibleProviderIdentity(provider) &&
        LLMCapability.OpenAIEndpoint.Completions !in capabilities &&
        LLMCapability.OpenAIEndpoint.Responses !in capabilities
    val needsThinking = provider == LLMProvider.Anthropic &&
        LLMCapability.Thinking !in capabilities &&
        ModelQuirks.thinksByDefault(modelId)
    if (!needsChatCompletions && !needsThinking) return capabilities
    return buildList {
        addAll(capabilities)
        if (needsChatCompletions) add(LLMCapability.OpenAIEndpoint.Completions)
        if (needsThinking) add(LLMCapability.Thinking)
    }
}
