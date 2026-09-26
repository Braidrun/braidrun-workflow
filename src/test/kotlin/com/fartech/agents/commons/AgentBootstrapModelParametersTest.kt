package com.fartech.agents.commons

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentBootstrapModelParametersTest {

    @Test
    fun `prompt temperature is the configured value regardless of the primary model`() {
        // Shared by every tier; per-model dropping happens per request (ModelParamsSanitizingLLMClient).
        assertEquals(0.7, resolveModelTemperature(0.7))
        assertNull(resolveModelTemperature(null))
    }

    @Test
    fun `Kimi K3 request omits configured temperature`() {
        val model = ModelRegistry.getModel("kimi", "kimi-k3")
            ?: error("kimi-k3 must be registered")

        assertNull(LLMParams(temperature = 0.7).sanitizedFor(model, streaming = false).temperature)
        assertNull(LLMParams(temperature = 1.0).sanitizedFor(model, streaming = true).temperature)
    }

    @Test
    fun `other Kimi models preserve configured temperature`() {
        val model = ModelRegistry.getModel("kimi", "kimi-k2.6")
            ?: error("kimi-k2.6 must be registered")

        assertEquals(0.7, LLMParams(temperature = 0.7).sanitizedFor(model, streaming = false).temperature)
    }

    @Test
    fun `Kimi K3 fallback omits temperature shared by the prompt`() {
        // The prompt keeps the configured temperature; the K3 fallback request drops it per call.
        val primary = ModelRegistry.getModel("openai", "gpt-4o")
            ?: error("gpt-4o must be registered")
        val fallback = determineLLMModel(LLModelConfig(provider = "kimi", model = "kimi-k3"))
        val params = LLMParams(temperature = resolveModelTemperature(0.7))

        assertEquals(0.7, params.sanitizedFor(primary, streaming = false).temperature)
        assertNull(params.sanitizedFor(fallback, streaming = false).temperature)
    }

    @Test
    fun `Kimi K3 cascade fallback omits temperature shared by the prompt`() {
        val primary = ModelRegistry.getModel("openai", "gpt-4o")
            ?: error("gpt-4o must be registered")
        val cascadeTier = determineLLMModel(LLModelConfig(provider = "moonshot", model = "kimi-k3"))
        val params = LLMParams(temperature = resolveModelTemperature(0.7))

        assertEquals(0.7, params.sanitizedFor(primary, streaming = true).temperature)
        assertNull(params.sanitizedFor(cascadeTier, streaming = true).temperature)
    }

    @Test
    fun `a primary that rejects temperature keeps it for tiers that support it`() {
        val primary = determineLLMModel(LLModelConfig(provider = "kimi", model = "kimi-k3"))
        val tier = ModelRegistry.getModel("openai", "gpt-4o")
            ?: error("gpt-4o must be registered")
        val params = LLMParams(temperature = resolveModelTemperature(0.7))

        assertNull(params.sanitizedFor(primary, streaming = false).temperature)
        assertEquals(0.7, params.sanitizedFor(tier, streaming = false).temperature)
    }

    @Test
    fun `same model id on another provider does not trigger Kimi compatibility rule`() {
        val model = LLModel(
            provider = LLMProvider.OpenRouter,
            id = "kimi-k3",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Temperature),
            contextLength = 1_048_576L,
        )

        assertEquals(0.7, LLMParams(temperature = 0.7).sanitizedFor(model, streaming = false).temperature)
    }

    @Test
    fun `OpenTelemetry span-tree error propagates instead of becoming a null output`() {
        val spanError = IllegalStateException("Error deleting span node from the tree")

        val thrown = assertThrows<IllegalStateException> { rethrowAgentRunFailure(spanError) }

        assertSame(spanError, thrown)
    }

    @Test
    fun `other agent run failures propagate unchanged`() {
        val failure = RuntimeException("boom")

        assertSame(failure, assertThrows<RuntimeException> { rethrowAgentRunFailure(failure) })
    }
}
