package com.fartech.agents.commons

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentBootstrapModelParametersTest {

    @Test
    fun `Kimi K3 omits configured temperature`() {
        val model = ModelRegistry.getModel("kimi", "kimi-k3")
            ?: error("kimi-k3 must be registered")

        assertNull(resolveModelTemperature(model, 0.7))
        assertNull(resolveModelTemperature(model, 1.0))
    }

    @Test
    fun `other Kimi models preserve configured temperature`() {
        val model = ModelRegistry.getModel("kimi", "kimi-k2.6")
            ?: error("kimi-k2.6 must be registered")

        assertEquals(0.7, resolveModelTemperature(model, 0.7))
    }

    @Test
    fun `Kimi K3 fallback omits temperature shared by the prompt`() {
        val primary = ModelRegistry.getModel("openai", "gpt-4o")
            ?: error("gpt-4o must be registered")
        val group = LLModelGroupConfig(
            models = listOf(LLModelConfig(provider = "openai", model = "gpt-4o")),
            fallback = LLModelConfig(provider = "kimi", model = "kimi-k3"),
            temperature = 0.7,
        )

        assertNull(resolveModelTemperature(primary, group.temperature, group))
    }

    @Test
    fun `Kimi K3 cascade fallback omits temperature shared by the prompt`() {
        val primary = ModelRegistry.getModel("openai", "gpt-4o")
            ?: error("gpt-4o must be registered")
        val group = LLModelGroupConfig(
            models = listOf(LLModelConfig(provider = "openai", model = "gpt-4o")),
            cascadeFallbacks = listOf(LLModelConfig(provider = "moonshot", model = "kimi-k3")),
            temperature = 0.7,
        )

        assertNull(resolveModelTemperature(primary, group.temperature, group))
    }

    @Test
    fun `same model id on another provider does not trigger Kimi compatibility rule`() {
        val model = LLModel(
            provider = LLMProvider.OpenRouter,
            id = "kimi-k3",
            capabilities = listOf(LLMCapability.Completion),
            contextLength = 1_048_576L,
        )

        assertEquals(0.7, resolveModelTemperature(model, 0.7))
    }
}
