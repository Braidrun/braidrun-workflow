package com.fartech.agents.commons

import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentModelsTest {

    // =========================================================================
    // parseCapabilities
    // =========================================================================

    @Nested
    inner class ParseCapabilitiesTest {

        @Test
        fun `null input returns STANDARD_CAPABILITIES`() {
            val result = parseCapabilities(null)
            assertEquals(STANDARD_CAPABILITIES, result)
        }

        @Test
        fun `empty list returns STANDARD_CAPABILITIES`() {
            val result = parseCapabilities(emptyList())
            assertEquals(STANDARD_CAPABILITIES, result)
        }

        @Test
        fun `unknown capability strings return STANDARD_CAPABILITIES`() {
            val result = parseCapabilities(listOf("totally_unknown"))
            assertEquals(STANDARD_CAPABILITIES, result)
        }

        @Test
        fun `temperature capability parsed`() {
            val result = parseCapabilities(listOf("temperature"))
            assertTrue(result.contains(LLMCapability.Temperature))
        }

        @Test
        fun `tools capability parsed`() {
            val result = parseCapabilities(listOf("tools"))
            assertTrue(result.contains(LLMCapability.Tools))
        }

        @Test
        fun `toolchoice and tool_choice both map to ToolChoice`() {
            val r1 = parseCapabilities(listOf("toolchoice"))
            val r2 = parseCapabilities(listOf("tool_choice"))
            assertTrue(r1.contains(LLMCapability.ToolChoice))
            assertTrue(r2.contains(LLMCapability.ToolChoice))
        }

        @Test
        fun `vision aliases all map to Vision Image`() {
            for (alias in listOf("vision", "vision.image", "image")) {
                val result = parseCapabilities(listOf(alias))
                assertTrue(result.contains(LLMCapability.Vision.Image), "Failed for alias: $alias")
            }
        }

        @Test
        fun `json aliases map to Schema JSON Standard`() {
            for (alias in listOf("json", "schema.json.standard")) {
                val result = parseCapabilities(listOf(alias))
                assertTrue(result.contains(LLMCapability.Schema.JSON.Standard), "Failed for alias: $alias")
            }
        }

        @Test
        fun `json basic aliases map to Schema JSON Basic`() {
            for (alias in listOf("json.basic", "schema.json.basic")) {
                val result = parseCapabilities(listOf(alias))
                assertTrue(result.contains(LLMCapability.Schema.JSON.Basic), "Failed for alias: $alias")
            }
        }

        @Test
        fun `document capability parsed`() {
            val result = parseCapabilities(listOf("document"))
            assertTrue(result.contains(LLMCapability.Document))
        }

        @Test
        fun `completion capability parsed`() {
            val result = parseCapabilities(listOf("completion"))
            assertTrue(result.contains(LLMCapability.Completion))
        }

        @Test
        fun `speculation capability parsed`() {
            val result = parseCapabilities(listOf("speculation"))
            assertTrue(result.contains(LLMCapability.Speculation))
        }

        @Test
        fun `multiplechoices and multiple_choices both parsed`() {
            val r1 = parseCapabilities(listOf("multiplechoices"))
            val r2 = parseCapabilities(listOf("multiple_choices"))
            assertTrue(r1.contains(LLMCapability.MultipleChoices))
            assertTrue(r2.contains(LLMCapability.MultipleChoices))
        }

        @Test
        fun `openai completions parsed`() {
            val result = parseCapabilities(listOf("openai.completions"))
            assertTrue(result.contains(LLMCapability.OpenAIEndpoint.Completions))
        }

        @Test
        fun `standard shortcut expands to STANDARD_CAPABILITIES`() {
            val result = parseCapabilities(listOf("standard"))
            assertTrue(result.containsAll(STANDARD_CAPABILITIES))
        }

        @Test
        fun `multimodal shortcut expands to MULTIMODAL_CAPABILITIES`() {
            val result = parseCapabilities(listOf("multimodal"))
            assertTrue(result.containsAll(MULTIMODAL_CAPABILITIES))
        }

        @Test
        fun `basic shortcut expands to BASIC_CAPABILITIES`() {
            val result = parseCapabilities(listOf("basic"))
            assertTrue(result.containsAll(BASIC_CAPABILITIES))
        }

        @Test
        fun `case insensitive parsing`() {
            val result = parseCapabilities(listOf("TEMPERATURE", "Tools", "Document"))
            assertTrue(result.contains(LLMCapability.Temperature))
            assertTrue(result.contains(LLMCapability.Tools))
            assertTrue(result.contains(LLMCapability.Document))
        }

        @Test
        fun `duplicate capabilities are deduplicated`() {
            val result = parseCapabilities(listOf("temperature", "temperature", "tools"))
            assertEquals(result.size, result.distinct().size)
        }
    }

    // =========================================================================
    // LLModelConfig
    // =========================================================================

    @Nested
    inner class LLModelConfigTest {

        @Test
        fun `default values are correct`() {
            val config = LLModelConfig(provider = "openrouter", model = "test-model")
            assertNull(config.baseUrl)
            assertNull(config.maxToken)
            assertNull(config.isVision)
            assertNull(config.displayName)
            assertNull(config.capabilities)
            assertNull(config.description)
        }

        @Test
        fun `serialization roundtrip`() {
            val config = LLModelConfig(
                provider = "openai",
                model = "gpt-4",
                baseUrl = "https://api.openai.com",
                maxToken = 128000,
                isVision = true,
                displayName = "GPT-4",
                capabilities = listOf("temperature", "tools"),
                description = "OpenAI GPT-4"
            )
            val json = Json.encodeToString(LLModelConfig.serializer(), config)
            val decoded = Json.decodeFromString(LLModelConfig.serializer(), json)
            assertEquals(config, decoded)
        }
    }

    // =========================================================================
    // CustomModelDefinition
    // =========================================================================

    @Nested
    inner class CustomModelDefinitionTest {

        @Test
        fun `default values are correct`() {
            val def = CustomModelDefinition(name = "test", provider = "openai", modelId = "test-id")
            assertNull(def.baseUrl)
            assertNull(def.contextLength)
            assertNull(def.maxOutputTokens)
            assertNull(def.isVision)
            assertNull(def.capabilities)
            assertNull(def.description)
        }

        @Test
        fun `serialization roundtrip`() {
            val def = CustomModelDefinition(
                name = "my-model",
                provider = "openrouter",
                modelId = "x-ai/grok-test",
                contextLength = 200_000,
                maxOutputTokens = 50_000,
                isVision = true,
                capabilities = listOf("multimodal"),
                description = "Test model"
            )
            val json = Json.encodeToString(CustomModelDefinition.serializer(), def)
            val decoded = Json.decodeFromString(CustomModelDefinition.serializer(), json)
            assertEquals(def, decoded)
        }
    }

    // =========================================================================
    // HistoryCompressionConfig
    // =========================================================================

    @Nested
    inner class HistoryCompressionConfigTest {

        @Test
        fun `default values are correct`() {
            val config = HistoryCompressionConfig()
            assertEquals("whole_history", config.strategy)
            assertEquals(10, config.n)
            assertEquals(5, config.chunkSize)
            assertTrue(config.preserveMemory)
        }

        @Test
        fun `custom values preserved`() {
            val config = HistoryCompressionConfig(
                strategy = "from_last_n",
                n = 20,
                chunkSize = 10,
                preserveMemory = false
            )
            assertEquals("from_last_n", config.strategy)
            assertEquals(20, config.n)
            assertEquals(10, config.chunkSize)
            assertFalse(config.preserveMemory)
        }
    }

    // =========================================================================
    // LLModelGroupConfig
    // =========================================================================

    @Nested
    inner class LLModelGroupConfigTest {

        @Test
        fun `foundModelWithName returns matching model`() {
            val config = LLModelGroupConfig(
                models = listOf(
                    LLModelConfig(provider = "openai", model = "gpt-4"),
                    LLModelConfig(provider = "anthropic", model = "claude-3.5-sonnet")
                )
            )
            val found = config.foundModelWithName("gpt-4")
            assertEquals("gpt-4", found.model)
            assertEquals("openai", found.provider)
        }

        @Test
        fun `foundModelWithName returns DEFAULT for unknown model`() {
            val config = LLModelGroupConfig(models = emptyList())
            val found = config.foundModelWithName("nonexistent")
            assertEquals(DEFAULT_LLM_MODEL_CONFIG, found)
        }

        @Test
        fun `getDefaultModelName returns direct default field`() {
            val config = LLModelGroupConfig(
                models = emptyList(),
                agentDefinedSettings = Json.parseToJsonElement("""{"default":"gpt-4"}""")
            )
            assertEquals("gpt-4", config.getDefaultModelName())
        }

        @Test
        fun `getDefaultModelName returns modelAssignments default`() {
            val config = LLModelGroupConfig(
                models = emptyList(),
                agentDefinedSettings = Json.parseToJsonElement("""{"modelAssignments":{"default":"claude-3.5"}}""")
            )
            assertEquals("claude-3.5", config.getDefaultModelName())
        }

        @Test
        fun `getDefaultModelName returns null for empty settings`() {
            val config = LLModelGroupConfig(
                models = emptyList(),
                agentDefinedSettings = Json.parseToJsonElement("{}")
            )
            assertNull(config.getDefaultModelName())
        }

        @Test
        fun `getDefaultModelName returns null for non-object settings`() {
            val config = LLModelGroupConfig(
                models = emptyList(),
                agentDefinedSettings = Json.parseToJsonElement("\"just-a-string\"")
            )
            assertNull(config.getDefaultModelName())
        }

        @Test
        fun `default temperature is 1_0`() {
            val config = LLModelGroupConfig(models = emptyList())
            assertEquals(1.0, config.temperature)
        }
    }

    // =========================================================================
    // determineLLMModel
    // =========================================================================

    @Nested
    inner class DetermineLLMModelTest {

        @Test
        fun `resolves built-in OpenRouter model`() {
            val config = LLModelConfig(provider = "openrouter", model = "grok-4.20-beta")
            val model = determineLLMModel(config)
            assertEquals(LLMProvider.OpenRouter, model.provider)
            assertTrue(model.id.contains("grok-4.20"))
        }

        @Test
        fun `resolves built-in Ollama model`() {
            val config = LLModelConfig(provider = "ollama", model = "gpt-oss:120b")
            val model = determineLLMModel(config)
            assertEquals(LLMProvider.Ollama, model.provider)
            assertEquals("gpt-oss:120b", model.id)
        }

        @Test
        fun `resolves built-in ZAI model through OpenAI compatible provider`() {
            val config = LLModelConfig(provider = "zai", model = "glm-5.1")
            val model = determineLLMModel(config)
            assertEquals(LLMProvider.OpenAI, model.provider)
            assertEquals("glm-5.1", model.id)
            assertTrue(model.supports(LLMCapability.OpenAIEndpoint.Completions))
        }

        @Test
        fun `creates dynamic model for unknown model name`() {
            val config = LLModelConfig(
                provider = "openai",
                model = "some-future-model-v99",
                maxToken = 200_000,
                isVision = true
            )
            val model = determineLLMModel(config)
            assertEquals(LLMProvider.OpenAI, model.provider)
            assertEquals("some-future-model-v99", model.id)
            assertEquals(200_000L, model.contextLength)
            assertTrue(model.capabilities!!.contains(LLMCapability.Vision.Image))
        }

        @Test
        fun `dynamic model defaults to 32768 context length`() {
            val config = LLModelConfig(provider = "openai", model = "unknown-model")
            val model = determineLLMModel(config)
            assertEquals(32_768L, model.contextLength)
        }

        @Test
        fun `dynamic model uses STANDARD_CAPABILITIES by default`() {
            val config = LLModelConfig(provider = "openai", model = "unknown-model")
            val model = determineLLMModel(config)
            assertEquals(STANDARD_CAPABILITIES, model.capabilities)
        }

        @Test
        fun `dynamic model uses MULTIMODAL_CAPABILITIES when isVision is true`() {
            val config = LLModelConfig(provider = "openai", model = "unknown-model", isVision = true)
            val model = determineLLMModel(config)
            assertTrue(model.capabilities!!.contains(LLMCapability.Vision.Image))
        }

        @Test
        fun `dynamic model uses explicit capabilities when provided`() {
            val config = LLModelConfig(
                provider = "openai",
                model = "unknown-model",
                capabilities = listOf("temperature", "tools")
            )
            val model = determineLLMModel(config)
            assertTrue(model.capabilities!!.contains(LLMCapability.Temperature))
            assertTrue(model.capabilities!!.contains(LLMCapability.Tools))
        }

        @Test
        fun `provider aliases map correctly`() {
            // OpenRouter aliases
            for (alias in listOf("openrouter", "open_router")) {
                val model = determineLLMModel(LLModelConfig(provider = alias, model = "unknown-alias-test"))
                assertEquals(LLMProvider.OpenRouter, model.provider)
            }

            // OpenAI aliases
            for (alias in listOf("openai", "open_ai")) {
                val model = determineLLMModel(LLModelConfig(provider = alias, model = "unknown-alias-test"))
                assertEquals(LLMProvider.OpenAI, model.provider)
            }

            // Google
            val googleModel = determineLLMModel(LLModelConfig(provider = "google", model = "unknown-alias-test"))
            assertEquals(LLMProvider.Google, googleModel.provider)

            // Anthropic
            val anthropicModel = determineLLMModel(LLModelConfig(provider = "anthropic", model = "unknown-alias-test"))
            assertEquals(LLMProvider.Anthropic, anthropicModel.provider)

            // DeepSeek
            val deepseekModel = determineLLMModel(LLModelConfig(provider = "deepseek", model = "unknown-alias-test"))
            assertEquals(LLMProvider.DeepSeek, deepseekModel.provider)

            // Z.ai official OpenAI-compatible aliases
            for (alias in listOf("zai", "z_ai", "z-ai", "zhipuai", "zhipu_ai")) {
                val model = determineLLMModel(LLModelConfig(provider = alias, model = "unknown-alias-test"))
                assertEquals(LLMProvider.OpenAI, model.provider, "Failed for alias: $alias")
            }

            // Ollama aliases
            for (alias in listOf("ollama", "local", "olla")) {
                val model = determineLLMModel(LLModelConfig(provider = alias, model = "unknown-alias-test"))
                assertEquals(LLMProvider.Ollama, model.provider, "Failed for alias: $alias")
            }
        }

        @Test
        fun `xAI qwen meta mistral perplexity map to OpenRouter`() {
            for (provider in listOf(
                "xai",
                "x-ai",
                "qwen",
                "meta",
                "meta-llama",
                "mistral",
                "mistralai",
                "perplexity"
            )) {
                val model = determineLLMModel(LLModelConfig(provider = provider, model = "unknown-test"))
                assertEquals(LLMProvider.OpenRouter, model.provider, "Failed for provider: $provider")
            }
        }

        @Test
        fun `unknown provider defaults to OpenRouter`() {
            val model = determineLLMModel(LLModelConfig(provider = "some_new_provider", model = "model-x"))
            assertEquals(LLMProvider.OpenRouter, model.provider)
        }
    }

    // =========================================================================
    // API Key Resolution
    // =========================================================================

    @Nested
    inner class ApiKeyResolutionTest {

        @Test
        fun `standalone openrouter api key is honored`() {
            val parameters = listOf(
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-direct-openrouter"))
            )

            val resolved = resolveConfiguredApiKey(parameters, "openrouter", emptyMap())

            assertEquals("sk-direct-openrouter", resolved)
        }

        @Test
        fun `openrouter standalone key is reused for openrouter-backed aliases`() {
            val parameters = listOf(
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-direct-openrouter"))
            )

            val resolved = resolveConfiguredApiKey(parameters, "xai", emptyMap())

            assertEquals("sk-direct-openrouter", resolved)
        }

        @Test
        fun `standalone provider key wins over llm_provider_keys map`() {
            val parameters = listOf(
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-direct-openrouter"))
            )

            val resolved = resolveConfiguredApiKey(
                parameters,
                "openrouter",
                mapOf("openrouter" to "sk-from-map")
            )

            assertEquals("sk-direct-openrouter", resolved)
        }

        @Test
        fun `llm_provider_keys map is still supported`() {
            val resolved = resolveConfiguredApiKey(
                emptyList(),
                "openrouter",
                mapOf("openrouter" to "sk-from-map")
            )

            assertEquals("sk-from-map", resolved)
        }

        @Test
        fun `standalone zai api key is honored`() {
            val parameters = listOf(
                ConfigurationParameter("zai_api_key", JsonPrimitive("sk-zai-direct"))
            )

            val resolved = resolveConfiguredApiKey(parameters, "zai", emptyMap())

            assertEquals("sk-zai-direct", resolved)
        }

        @Test
        fun `zai aliases resolve provider keys`() {
            val resolved = resolveConfiguredApiKey(
                emptyList(),
                "z-ai",
                mapOf("zai" to "sk-zai-from-map")
            )

            assertEquals("sk-zai-from-map", resolved)
        }

        @Test
        fun `missing openrouter api key throws actionable error`() {
            val message = buildMissingApiKeyMessage("open_router")

            assertTrue(message.contains("Missing API key for provider 'open_router'"))
            assertTrue(message.contains("OPENROUTER_API_KEY"))
            assertTrue(message.contains("OPEN_ROUTER_API_KEY"))
            assertTrue(message.contains("openrouter_api_key"))
            assertTrue(message.contains("open_router_api_key"))
            assertTrue(message.contains("llm_provider_keys"))
        }

        @Test
        fun `missing zai api key throws actionable error`() {
            val message = buildMissingApiKeyMessage("zai")

            assertTrue(message.contains("Missing API key for provider 'zai'"))
            assertTrue(message.contains("ZAI_API_KEY"))
            assertTrue(message.contains("zai_api_key"))
            assertTrue(message.contains("llm_provider_keys"))
        }
    }

    // =========================================================================
    // registerCustomModel / Custom Model Registry
    // =========================================================================

    @Nested
    inner class CustomModelRegistryTest {

        @Test
        fun `registered custom model is resolved by determineLLMModel`() {
            val def = CustomModelDefinition(
                name = "test-custom-agent-model",
                provider = "openai",
                modelId = "ft:gpt-4:my-org:custom-test:abc123",
                contextLength = 64_000,
                capabilities = listOf("temperature", "tools")
            )
            registerCustomModel(def)

            val resolved = determineLLMModel(LLModelConfig(provider = "openai", model = "test-custom-agent-model"))
            assertEquals("ft:gpt-4:my-org:custom-test:abc123", resolved.id)
            assertEquals(LLMProvider.OpenAI, resolved.provider)
            assertEquals(64_000L, resolved.contextLength)
        }

        @Test
        fun `registerCustomModels with null does nothing`() {
            // Should not throw
            registerCustomModels(null)
        }

        @Test
        fun `registerCustomModels registers multiple models`() {
            val defs = listOf(
                CustomModelDefinition(name = "batch-model-a", provider = "openrouter", modelId = "batch/model-a"),
                CustomModelDefinition(name = "batch-model-b", provider = "openrouter", modelId = "batch/model-b")
            )
            registerCustomModels(defs)

            val a = determineLLMModel(LLModelConfig(provider = "openrouter", model = "batch-model-a"))
            assertEquals("batch/model-a", a.id)

            val b = determineLLMModel(LLModelConfig(provider = "openrouter", model = "batch-model-b"))
            assertEquals("batch/model-b", b.id)
        }

        @Test
        fun `custom model with isVision gets MULTIMODAL_CAPABILITIES`() {
            val def = CustomModelDefinition(
                name = "vision-custom-test",
                provider = "openai",
                modelId = "vision-test-id",
                isVision = true
            )
            registerCustomModel(def)

            val resolved = determineLLMModel(LLModelConfig(provider = "openai", model = "vision-custom-test"))
            assertTrue(resolved.capabilities!!.contains(LLMCapability.Vision.Image))
        }

        @Test
        fun `custom model default context length is 32768`() {
            val def = CustomModelDefinition(
                name = "ctx-default-test",
                provider = "openai",
                modelId = "ctx-default-id"
            )
            registerCustomModel(def)

            val resolved = determineLLMModel(LLModelConfig(provider = "openai", model = "ctx-default-test"))
            assertEquals(32_768L, resolved.contextLength)
        }
    }

    // =========================================================================
    // Provider Model Maps
    // =========================================================================

    @Nested
    inner class ProviderModelMapsTest {

        @Test
        fun `LLM_PROVIDER_MODELS contains all expected provider aliases`() {
            val expectedKeys = listOf(
                "openrouter", "open_router",
                "openai", "open_ai",
                "google",
                "anthropic",
                "deepseek",
                "xai", "x-ai",
                "qwen",
                "meta", "meta-llama",
                "mistral", "mistralai",
                "perplexity",
                "ollama", "local", "olla"
            )
            for (key in expectedKeys) {
                assertTrue(LLM_PROVIDER_MODELS.containsKey(key), "Missing provider key: $key")
            }
        }

        @Test
        fun `OLLAMA_MODELS contains gpt-oss 120b`() {
            assertTrue(OLLAMA_MODELS.containsKey("gpt-oss:120b"))
            assertEquals(GPT_OSS_120b, OLLAMA_MODELS["gpt-oss:120b"])
        }

        @Test
        fun `DEFAULT_LLM_MODEL is from OpenRouter`() {
            assertEquals(LLMProvider.OpenRouter, DEFAULT_LLM_MODEL.provider)
        }

        @Test
        fun `DEFAULT_LLM_MODEL_CONFIG provider is openrouter`() {
            assertEquals("openrouter", DEFAULT_LLM_MODEL_CONFIG.provider)
        }

        @Test
        fun `OPEN_ROUTER_MODELS has expected default alternatives`() {
            assertTrue(OPEN_ROUTER_MODELS.containsKey("grok-4.20-beta"))
            assertTrue(OPEN_ROUTER_MODELS.containsKey("minimax-m2.7"))
        }

        @Test
        fun `GPT_OSS_120b has expected properties`() {
            assertEquals(LLMProvider.Ollama, GPT_OSS_120b.provider)
            assertEquals("gpt-oss:120b", GPT_OSS_120b.id)
            assertEquals(128_000L, GPT_OSS_120b.contextLength)
            assertEquals(128_000L, GPT_OSS_120b.maxOutputTokens)
        }
    }

    // =========================================================================
    // Capability Constants
    // =========================================================================

    @Nested
    inner class CapabilityConstantsTest {

        @Test
        fun `BASIC_CAPABILITIES contains expected capabilities`() {
            assertTrue(BASIC_CAPABILITIES.contains(LLMCapability.Schema.JSON.Standard))
            assertTrue(BASIC_CAPABILITIES.contains(LLMCapability.Speculation))
            assertTrue(BASIC_CAPABILITIES.contains(LLMCapability.Tools))
            assertTrue(BASIC_CAPABILITIES.contains(LLMCapability.ToolChoice))
            assertTrue(BASIC_CAPABILITIES.contains(LLMCapability.Completion))
            assertFalse(BASIC_CAPABILITIES.contains(LLMCapability.Temperature))
        }

        @Test
        fun `STANDARD_CAPABILITIES extends BASIC with Temperature`() {
            assertTrue(STANDARD_CAPABILITIES.containsAll(BASIC_CAPABILITIES))
            assertTrue(STANDARD_CAPABILITIES.contains(LLMCapability.Temperature))
        }

        @Test
        fun `MULTIMODAL_CAPABILITIES extends STANDARD with Vision Image`() {
            assertTrue(MULTIMODAL_CAPABILITIES.containsAll(STANDARD_CAPABILITIES))
            assertTrue(MULTIMODAL_CAPABILITIES.contains(LLMCapability.Vision.Image))
        }
    }

    // =========================================================================
    // determineLLMClients — wiring of FallbackPromptExecutorSettings
    // =========================================================================

    @Nested
    inner class DetermineLLMClientsTest {

        /**
         * Regression for the "Fallback client not found for provider:
         * OpenRouterLLMProvider" failure observed on 2026-04-27 (execution
         * 7caa5823-…). When the workflow only declared `provider: deepseek`
         * and inherited the default `LLModelGroupConfig.fallback` (OpenRouter),
         * `determineLLMClients` returned a clients map containing only DeepSeek
         * paired with `FallbackPromptExecutorSettings(fallbackProvider = OpenRouter)`.
         * Koog's `MultiLLMPromptExecutor` constructor validates that the
         * fallback provider is present in the clients map and threw
         * `IllegalStateException` at agent build time, before any LLM call.
         */
        @Test
        fun `default fallback provider is registered in the clients map`() {
            val parameters = listOf(
                ConfigurationParameter(
                    "llm_config",
                    Json.parseToJsonElement("""{"models":[{"provider":"deepseek","model":"deepseek-v4-pro"}]}"""),
                ),
                // Both keys are needed because the default fallback (OpenRouter)
                // calls `resolveConfiguredApiKeyOrThrow` during client build.
                ConfigurationParameter("deepseek_api_key", JsonPrimitive("sk-deepseek-test")),
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-openrouter-test")),
            )

            val (clients, fallback) = determineLLMClients(HttpAccess(), parameters)

            assertTrue(
                clients.containsKey(LLMProvider.DeepSeek),
                "expected DeepSeek (the declared primary) in clients",
            )
            assertTrue(
                clients.containsKey(fallback.fallbackProvider),
                "expected fallback provider ${fallback.fallbackProvider} to be registered in clients",
            )

            // Smoke-test the actual Koog constructor — this was the throw site
            // before the fix.
            MultiLLMPromptExecutor(
                llmClients = clients,
                fallback = fallback,
            )
        }

        @Test
        fun `explicit primary entry is not overridden by the default fallback registration`() {
            val parameters = listOf(
                ConfigurationParameter(
                    "llm_config",
                    Json.parseToJsonElement(
                        // OpenRouter is BOTH the primary (with custom baseUrl) AND the
                        // default fallback. The custom baseUrl must win.
                        """{"models":[{"provider":"openrouter","model":"grok-4.20","baseUrl":"https://custom.example.com"}]}""",
                    ),
                ),
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-openrouter-test")),
            )

            val (clients, fallback) = determineLLMClients(HttpAccess(), parameters)

            assertEquals(1, clients.size, "no extra fallback entry should be added when primary already covers it")
            assertTrue(clients.containsKey(LLMProvider.OpenRouter))
            assertEquals(LLMProvider.OpenRouter, fallback.fallbackProvider)
        }
    }

    // =========================================================================
    // resolveVersionedEndpointPath
    //
    // Koog joins endpoint paths RELATIVE to the client base URL, so a
    // version-carrying stock default path (`v1/chat/completions`, `v1/messages`,
    // `v1beta/models`) double-joins onto a base URL that already carries the
    // version segment (`.../v1`) → `.../v1/v1/...` → HTTP 404. These tests pin
    // the resolved request path for both bare-host and versioned base URLs.
    // =========================================================================

    @Nested
    inner class ResolveVersionedEndpointPathTest {

        @Test
        fun `versioned base drops the v1 prefix for OpenAI-compatible chat completions`() {
            // Mirrors every defaultBaseUrl in the OpenAI branch plus the else fallback.
            for (base in listOf(
                "https://api.openai.com/v1",
                "https://api.moonshot.cn/v1",
                "https://api.minimax.chat/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "http://localhost:1234/v1",
            )) {
                assertEquals(
                    "chat/completions",
                    resolveVersionedEndpointPath(base, "v1/chat/completions"),
                    "expected no double-join for $base",
                )
            }
        }

        @Test
        fun `bare host keeps the v1 prefix for OpenAI-compatible chat completions`() {
            assertEquals("v1/chat/completions", resolveVersionedEndpointPath("https://api.openai.com", "v1/chat/completions"))
            // A trailing slash with no path is still a bare host.
            assertEquals("v1/chat/completions", resolveVersionedEndpointPath("https://api.openai.com/", "v1/chat/completions"))
        }

        @Test
        fun `versioned base drops the v1 prefix for anthropic messages`() {
            assertEquals("messages", resolveVersionedEndpointPath("https://api.anthropic.com/v1", "v1/messages"))
        }

        @Test
        fun `bare host keeps the v1 prefix for anthropic messages`() {
            assertEquals("v1/messages", resolveVersionedEndpointPath("https://api.anthropic.com", "v1/messages"))
        }

        @Test
        fun `bare host keeps the v1beta prefix for google models`() {
            // The Google branch's default base URL is a bare host.
            assertEquals(
                "v1beta/models",
                resolveVersionedEndpointPath("https://generativelanguage.googleapis.com", "v1beta/models"),
            )
        }

        @Test
        fun `versioned google base drops the v1beta prefix`() {
            assertEquals(
                "models",
                resolveVersionedEndpointPath("https://generativelanguage.googleapis.com/v1beta", "v1beta/models"),
            )
        }

        @Test
        fun `deepseek path without a version prefix is unchanged for both base styles`() {
            // DeepSeek's stock default has no version segment, so it must never be
            // altered — a versioned base correctly yields `.../v1/chat/completions`.
            assertEquals("chat/completions", resolveVersionedEndpointPath("https://api.deepseek.com/v1", "chat/completions"))
            assertEquals("chat/completions", resolveVersionedEndpointPath("https://api.deepseek.com", "chat/completions"))
        }

        @Test
        fun `version-less proxy subpath keeps the version prefix`() {
            // Refinement over the embeddings heuristic: a proxy base whose trailing
            // segment is NOT a version must keep the version prefix, otherwise the
            // request would lose `/v1` entirely.
            assertEquals(
                "v1/chat/completions",
                resolveVersionedEndpointPath("https://proxy.example.com/openai", "v1/chat/completions"),
            )
            assertEquals(
                "v1beta/models",
                resolveVersionedEndpointPath("https://proxy.example.com/google", "v1beta/models"),
            )
        }

        @Test
        fun `koog client settings default paths still match the constants the resolver strips`() {
            // Guard: the fix assumes specific Koog stock default paths. If a Koog
            // upgrade changes any of these, the createLLMClient literals (and the
            // expectations above) must be revisited — this test fails loudly first.
            assertEquals("v1/chat/completions", OpenAIClientSettings().chatCompletionsPath)
            assertEquals("v1/chat/completions", MistralAIClientSettings().chatCompletionsPath)
            assertEquals("chat/completions", DeepSeekClientSettings().chatCompletionsPath)
            assertEquals("v1/messages", AnthropicClientSettings().messagesPath)
            assertEquals("v1beta/models", GoogleClientSettings().defaultPath)
        }
    }
}
