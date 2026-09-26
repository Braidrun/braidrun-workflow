package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `llm_config` arrives in snake_case (engine AgentDefinition.resolveParameters, braidrun-web
 * WorkflowService / TemplateService / XaiDirectRouting) and camelCase (hand-written workflows,
 * web CustomLLMModelDto). Both must decode; JsonMapper ignores unknown keys, so a missing alias
 * silently drops the value.
 */
class LlmConfigKeySpellingTest {

    private fun decode(llmConfigJson: String): LLModelGroupConfig =
        listOf(ConfigurationParameter("llm_config", Json.parseToJsonElement(llmConfigJson))).getLLMGroupConfig()

    private val expectedModel = LLModelConfig(
        provider = "openai",
        model = "grok-4.6",
        baseUrl = "https://api.x.ai/v1",
        maxToken = 500_000,
        isVision = true,
        displayName = "Grok",
    )

    private val expectedCustom = CustomModelDefinition(
        name = "my-grok",
        provider = "openai",
        modelId = "grok-4.6",
        baseUrl = "https://api.x.ai/v1",
        contextLength = 256_000,
        maxOutputTokens = 32_000,
        isVision = true,
    )

    @Test
    fun `snake_case llm_config keys decode`() {
        val config = decode(
            """
            {
              "models": [{"provider":"openai","model":"grok-4.6","base_url":"https://api.x.ai/v1",
                          "max_token":500000,"is_vision":true,"display_name":"Grok"}],
              "fallback": {"provider":"openai","model":"grok-4.6","base_url":"https://api.x.ai/v1",
                           "max_token":500000,"is_vision":true,"display_name":"Grok"},
              "cascade_fallbacks": [{"provider":"anthropic","model":"claude-opus-5"}],
              "custom_models": [{"name":"my-grok","provider":"openai","model_id":"grok-4.6",
                                 "base_url":"https://api.x.ai/v1","context_length":256000,
                                 "max_output_tokens":32000,"is_vision":true}]
            }
            """
        )
        assertEquals(listOf(expectedModel), config.models)
        assertEquals(expectedModel, config.fallback)
        assertEquals(listOf(LLModelConfig(provider = "anthropic", model = "claude-opus-5")), config.cascadeFallbacks)
        assertEquals(listOf(expectedCustom), config.customModels)
    }

    @Test
    fun `camelCase llm_config keys still decode`() {
        val config = decode(
            """
            {
              "models": [{"provider":"openai","model":"grok-4.6","baseUrl":"https://api.x.ai/v1",
                          "maxToken":500000,"isVision":true,"displayName":"Grok"}],
              "fallback": {"provider":"openai","model":"grok-4.6","baseUrl":"https://api.x.ai/v1",
                           "maxToken":500000,"isVision":true,"displayName":"Grok"},
              "cascadeFallbacks": [{"provider":"anthropic","model":"claude-opus-5"}],
              "customModels": [{"name":"my-grok","provider":"openai","modelId":"grok-4.6",
                                "baseUrl":"https://api.x.ai/v1","contextLength":256000,
                                "maxOutputTokens":32000,"isVision":true}]
            }
            """
        )
        assertEquals(listOf(expectedModel), config.models)
        assertEquals(expectedModel, config.fallback)
        assertEquals(listOf(LLModelConfig(provider = "anthropic", model = "claude-opus-5")), config.cascadeFallbacks)
        assertEquals(listOf(expectedCustom), config.customModels)
    }
}
