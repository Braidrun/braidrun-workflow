package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.params.LLMParams
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Direct-provider clients built by [createLLMClient] actually reach the wire, with a
 * capturing Koog HTTP factory in place of the network.
 */
class DirectProviderClientsTest {

    private val chatPrompt = prompt("direct") { user("hi") }

    private fun send(
        modelConfig: LLModelConfig,
        keys: Map<String, String>,
        http: CapturingHttpClientFactory,
        parameters: List<ConfigurationParameter> = emptyList(),
        streaming: Boolean = false,
        requestPrompt: ai.koog.prompt.Prompt = chatPrompt,
        tools: List<ToolDescriptor> = emptyList(),
    ): CapturingHttpClientFactory.Request {
        val (_, client) = createLLMClient(parameters, modelConfig, keys, http)
        val model = determineLLMModel(modelConfig)
        runBlocking {
            if (streaming) {
                runCatching { client.executeStreaming(requestPrompt, model, tools).toList() }
            } else {
                client.execute(requestPrompt, model, tools)
            }
        }
        return http.requests.last()
    }

    // =========================================================================
    // Anthropic: the wire model id is our (normalized) id, not Koog's version map
    // =========================================================================

    @Nested
    inner class AnthropicDirectTest {

        private val keys = mapOf("anthropic" to "sk-ant-test")

        private fun wireModel(model: String): String {
            val http = CapturingHttpClientFactory { _, _ -> CapturingHttpClientFactory.ANTHROPIC_OK }
            val request = send(LLModelConfig(provider = "anthropic", model = model), keys, http)
            return request.json["model"]!!.jsonPrimitive.content
        }

        @Test
        fun `catalog and custom Claude ids are sent instead of failing as unsupported`() {
            assertEquals("claude-opus-5", wireModel("claude-opus-5"))
            assertEquals("claude-opus-4-7", wireModel("claude-opus-4.7"))
            assertEquals("claude-sonnet-5", wireModel("anthropic/claude-sonnet-5"))
            assertEquals("my-bedrock-proxy-claude", wireModel("my-bedrock-proxy-claude"))
        }

        @Test
        fun `normalizeAnthropicModelId only rewrites the dotted alias spelling`() {
            assertEquals("claude-haiku-4-5", normalizeAnthropicModelId("claude-haiku-4.5"))
            assertEquals("claude-opus-4-6", normalizeAnthropicModelId("anthropic/claude-opus-4.6"))
            assertEquals("claude-sonnet-4-20250514", normalizeAnthropicModelId("claude-sonnet-4-20250514"))
            assertEquals("claude-3-7-sonnet-20250219", normalizeAnthropicModelId("claude-3-7-sonnet-20250219"))
            assertEquals("claude-opus-4-7", normalizeAnthropicModelId("claude-opus-4-7"))
        }

        @Test
        fun `request goes to the messages endpoint with the anthropic key`() {
            val http = CapturingHttpClientFactory { _, _ -> CapturingHttpClientFactory.ANTHROPIC_OK }
            val request = send(LLModelConfig(provider = "anthropic", model = "claude-opus-5"), keys, http)

            assertEquals("https://api.anthropic.com/v1", request.baseUrl)
            assertEquals("messages", request.path)
            assertEquals("sk-ant-test", http.created.single().headers["x-api-key"])
        }

        @Test
        fun `claude-opus-5 gets a 16K budget, no temperature and no forced tool choice`() {
            val http = CapturingHttpClientFactory { _, _ -> CapturingHttpClientFactory.ANTHROPIC_OK }
            val hot = prompt("p", params = LLMParams(temperature = 0.7, toolChoice = LLMParams.ToolChoice.Required)) {
                user("hi")
            }
            val request = send(
                LLModelConfig(provider = "anthropic", model = "claude-opus-5"), keys, http,
                requestPrompt = hot,
                tools = listOf(ToolDescriptor(name = "readFile", description = "Read a file")),
            )

            assertEquals(16_000, request.json["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertFalse(request.json.containsKey("temperature"), request.body)
            assertEquals("auto", request.json["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }

        @Test
        fun `streaming requests get a 64K budget`() {
            val http = CapturingHttpClientFactory()
            val request = send(
                LLModelConfig(provider = "anthropic", model = "claude-sonnet-5"), keys, http, streaming = true,
            )
            assertEquals(64_000, request.json["max_tokens"]!!.jsonPrimitive.content.toInt())
        }

        @Test
        fun `workflow max_tokens and temperature of a sampling model are kept`() {
            val http = CapturingHttpClientFactory { _, _ -> CapturingHttpClientFactory.ANTHROPIC_OK }
            val request = send(
                LLModelConfig(provider = "anthropic", model = "claude-sonnet-4-6"), keys, http,
                requestPrompt = prompt("p", params = LLMParams(temperature = 0.3, maxTokens = 1_234)) { user("hi") },
            )
            assertEquals(1_234, request.json["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertEquals(0.3, request.json["temperature"]!!.jsonPrimitive.content.toDouble())
        }
    }

    // =========================================================================
    // OpenAI-client providers: models without a declared endpoint now send
    // =========================================================================

    @Nested
    inner class OpenAIClientProvidersTest {

        @Test
        fun `openai gpt-5_5 from the catalog sends a chat completions request`() {
            val http = CapturingHttpClientFactory()
            val request = send(
                LLModelConfig(provider = "openai", model = "gpt-5.5"),
                mapOf("openai" to "sk-openai-test"),
                http,
            )

            assertEquals("https://api.openai.com/v1", request.baseUrl)
            assertEquals("chat/completions", request.path)
            assertEquals("gpt-5.5", request.json["model"]!!.jsonPrimitive.content)
        }

        @Test
        fun `a model unknown to the catalog (web supplemental pick) sends too`() {
            val http = CapturingHttpClientFactory()
            val request = send(
                LLModelConfig(provider = "openai", model = "gpt-5.6-sol"),
                mapOf("openai" to "sk-openai-test"),
                http,
            )
            assertEquals("gpt-5.6-sol", request.json["model"]!!.jsonPrimitive.content)
        }

        @Test
        fun `dashscope and minimax catalog models send`() {
            val qwen = requireNotNull(ModelRegistry.getProviderModels("qwen_direct")).entries.first()
            val minimax = requireNotNull(ModelRegistry.getProviderModels("minimax")).entries.first()
            for ((provider, entry, key) in listOf(
                Triple("qwen_direct", qwen, "qwen_direct"),
                Triple("minimax", minimax, "minimax"),
            )) {
                val http = CapturingHttpClientFactory()
                val request = send(LLModelConfig(provider = provider, model = entry.key), mapOf(key to "sk-test"), http)
                assertEquals(entry.value.id, request.json["model"]!!.jsonPrimitive.content, provider)
            }
        }
    }

    // =========================================================================
    // snake_case llm_config: base_url is honored, and the xAI-direct shape the
    // web writes sends the xAI key to api.x.ai — never to api.openai.com
    // =========================================================================

    @Nested
    inner class SnakeCaseBaseUrlTest {

        /** The llm_config braidrun-web's routeXaiDirectParameters produces for provider xai / grok-4.6. */
        private val xaiDirectParameters = listOf(
            ConfigurationParameter(
                "llm_config",
                Json.parseToJsonElement(
                    """
                    {"models":[{"provider":"openai","model":"grok-4.6","base_url":"https://api.x.ai/v1",
                      "max_token":500000,"is_vision":true,
                      "capabilities":["temperature","tools","tool_choice","completion","thinking","vision","json"]}],
                     "fallback":{"provider":"openai","model":"grok-4.6","base_url":"https://api.x.ai/v1"},
                     "temperature":0.7}
                    """.trimIndent()
                ),
            ),
            ConfigurationParameter("llm_provider_keys", Json.parseToJsonElement("""{"xai":"xai-secret","openai":"xai-secret"}""")),
            ConfigurationParameter("openai_api_key", JsonPrimitive("xai-secret")),
        )

        @Test
        fun `xai-direct routing sends the xai key to api_x_ai only`() {
            val http = CapturingHttpClientFactory()
            val llmConfig = xaiDirectParameters.getLLMGroupConfig()
            val keys = mapOf("xai" to "xai-secret", "openai" to "xai-secret")

            for (config in llmConfig.models + llmConfig.fallback) {
                send(config, keys, http, parameters = xaiDirectParameters)
            }

            assertEquals(2, http.requests.size)
            http.requests.forEach { request ->
                assertEquals("https://api.x.ai/v1", request.baseUrl)
                assertEquals("chat/completions", request.path)
                assertEquals("grok-4.6", request.json["model"]!!.jsonPrimitive.content)
            }
            http.created.forEach { created ->
                assertEquals("https://api.x.ai/v1", created.baseUrl)
                assertFalse(created.baseUrl.contains("api.openai.com"))
                assertTrue(created.headers.values.any { it.contains("xai-secret") }, "xAI key is on the xAI client")
            }
        }

        @Test
        fun `a blank base_url falls back to the provider default`() {
            val http = CapturingHttpClientFactory()
            val request = send(
                LLModelConfig(provider = "openai", model = "gpt-4o", baseUrl = "  "),
                mapOf("openai" to "sk-openai-test"),
                http,
            )
            assertEquals("https://api.openai.com/v1", request.baseUrl)
        }

        @Test
        fun `grok custom model on the openai route selects chat completions`() {
            val model = determineLLMModel(xaiDirectParameters.getLLMGroupConfig().models.single())
            assertTrue(model.supports(LLMCapability.OpenAIEndpoint.Completions))
            assertEquals(500_000L, model.contextLength)
        }
    }
}
