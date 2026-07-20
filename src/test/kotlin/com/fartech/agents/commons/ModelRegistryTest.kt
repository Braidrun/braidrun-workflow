package com.fartech.agents.commons

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ModelRegistry 测试 — 验证 YAML 加载的模型与 hardcoded 定义完全一致。
 */
class ModelRegistryTest {

    // ========================================================================
    // Phase 1: 100% 覆盖率验证（YAML vs hardcoded）
    // ========================================================================

    @Nested
    inner class CoverageVerification {

        @Test
        fun `YAML covers all OPEN_ROUTER_MODELS keys`() {
            assertProviderCoverage("openrouter", OPEN_ROUTER_MODELS)
        }

        @Test
        fun `YAML covers all OPENAI_MODELS keys`() {
            assertProviderCoverage("openai", OPENAI_MODELS)
        }

        @Test
        fun `YAML covers all GOOGLE_MODELS keys`() {
            assertProviderCoverage("google", GOOGLE_MODELS)
        }

        @Test
        fun `YAML covers all ANTHROPIC_MODELS keys`() {
            assertProviderCoverage("anthropic", ANTHROPIC_MODELS)
        }

        @Test
        fun `YAML covers all DEEPSEEK_MODELS keys`() {
            assertProviderCoverage("deepseek", DEEPSEEK_MODELS)
        }

        @Test
        fun `YAML covers all XAI_MODELS keys`() {
            assertProviderCoverage("xai", XAI_MODELS)
        }

        @Test
        fun `YAML covers all QWEN_MODELS keys`() {
            assertProviderCoverage("qwen", QWEN_MODELS)
        }

        @Test
        fun `YAML covers all QWEN_DIRECT_MODELS keys`() {
            assertProviderCoverage("qwen_direct", QWEN_DIRECT_MODELS)
        }

        @Test
        fun `YAML covers all KIMI_MODELS keys`() {
            assertProviderCoverage("kimi", KIMI_MODELS)
        }

        @Test
        fun `YAML covers all MINIMAX_MODELS keys`() {
            assertProviderCoverage("minimax", MINIMAX_MODELS)
        }

        @Test
        fun `YAML covers all META_MODELS keys`() {
            assertProviderCoverage("meta", META_MODELS)
        }

        @Test
        fun `YAML covers all MISTRAL_MODELS keys`() {
            assertProviderCoverage("mistral", MISTRAL_MODELS)
        }

        @Test
        fun `YAML covers all PERPLEXITY_MODELS keys`() {
            assertProviderCoverage("perplexity", PERPLEXITY_MODELS)
        }

        @Test
        fun `YAML covers all OLLAMA_MODELS keys`() {
            assertProviderCoverage("ollama", OLLAMA_MODELS)
        }

        @Test
        fun `YAML covers all ZAI_MODELS keys`() {
            assertProviderCoverage("zai", ZAI_MODELS)
        }

        @Test
        fun `YAML covers all NVIDIA_MODELS keys`() {
            assertProviderCoverage("nvidia", NVIDIA_MODELS)
        }

        private fun assertProviderCoverage(providerKey: String, hardcodedModels: Map<String, ai.koog.prompt.llm.LLModel>) {
            val yamlModels = ModelRegistry.getProviderModels(providerKey)
            assertNotNull(yamlModels, "ModelRegistry should have models for provider '$providerKey'")

            val missingKeys = mutableListOf<String>()
            for (key in hardcodedModels.keys) {
                if (!yamlModels!!.containsKey(key.lowercase())) {
                    missingKeys.add(key)
                }
            }
            assertTrue(missingKeys.isEmpty()) {
                "Provider '$providerKey': ${missingKeys.size} keys missing in YAML:\n  ${missingKeys.joinToString("\n  ")}"
            }
            // Also verify counts match
            assertEquals(hardcodedModels.size, yamlModels!!.size) {
                "Provider '$providerKey': model count mismatch (hardcoded=${hardcodedModels.size}, yaml=${yamlModels.size})"
            }
        }
    }

    // ========================================================================
    // Phase 1b: 字段精确性验证
    // ========================================================================

    @Nested
    inner class FieldAccuracyVerification {

        @Test
        fun `all OPEN_ROUTER_MODELS fields match exactly`() {
            assertFieldsMatch("openrouter", OPEN_ROUTER_MODELS)
        }

        @Test
        fun `all OPENAI_MODELS fields match exactly`() {
            assertFieldsMatch("openai", OPENAI_MODELS)
        }

        @Test
        fun `all GOOGLE_MODELS fields match exactly`() {
            assertFieldsMatch("google", GOOGLE_MODELS)
        }

        @Test
        fun `all ANTHROPIC_MODELS fields match exactly`() {
            assertFieldsMatch("anthropic", ANTHROPIC_MODELS)
        }

        @Test
        fun `all DEEPSEEK_MODELS fields match exactly`() {
            assertFieldsMatch("deepseek", DEEPSEEK_MODELS)
        }

        @Test
        fun `all XAI_MODELS fields match exactly`() {
            assertFieldsMatch("xai", XAI_MODELS)
        }

        @Test
        fun `all QWEN_MODELS fields match exactly`() {
            assertFieldsMatch("qwen", QWEN_MODELS)
        }

        @Test
        fun `all QWEN_DIRECT_MODELS fields match exactly`() {
            assertFieldsMatch("qwen_direct", QWEN_DIRECT_MODELS)
        }

        @Test
        fun `all KIMI_MODELS fields match exactly`() {
            assertFieldsMatch("kimi", KIMI_MODELS)
        }

        @Test
        fun `all MINIMAX_MODELS fields match exactly`() {
            assertFieldsMatch("minimax", MINIMAX_MODELS)
        }

        @Test
        fun `all META_MODELS fields match exactly`() {
            assertFieldsMatch("meta", META_MODELS)
        }

        @Test
        fun `all MISTRAL_MODELS fields match exactly`() {
            assertFieldsMatch("mistral", MISTRAL_MODELS)
        }

        @Test
        fun `all PERPLEXITY_MODELS fields match exactly`() {
            assertFieldsMatch("perplexity", PERPLEXITY_MODELS)
        }

        @Test
        fun `all OLLAMA_MODELS fields match exactly`() {
            assertFieldsMatch("ollama", OLLAMA_MODELS)
        }

        @Test
        fun `all ZAI_MODELS fields match exactly`() {
            assertFieldsMatch("zai", ZAI_MODELS)
        }

        @Test
        fun `all NVIDIA_MODELS fields match exactly`() {
            assertFieldsMatch("nvidia", NVIDIA_MODELS)
        }

        private fun assertFieldsMatch(providerKey: String, hardcodedModels: Map<String, ai.koog.prompt.llm.LLModel>) {
            val yamlModels = ModelRegistry.getProviderModels(providerKey)!!
            val mismatches = mutableListOf<String>()

            for ((key, expected) in hardcodedModels) {
                val actual = yamlModels[key.lowercase()]
                    ?: continue // Coverage test already catches missing keys

                if (actual.id != expected.id) {
                    mismatches.add("$key: id mismatch (expected='${expected.id}', actual='${actual.id}')")
                }
                if (actual.contextLength != expected.contextLength) {
                    mismatches.add("$key: contextLength mismatch (expected=${expected.contextLength}, actual=${actual.contextLength})")
                }
                if (actual.maxOutputTokens != expected.maxOutputTokens) {
                    mismatches.add("$key: maxOutputTokens mismatch (expected=${expected.maxOutputTokens}, actual=${actual.maxOutputTokens})")
                }
                // Compare capabilities as sorted sets to ignore order
                val expectedCaps = expected.capabilities?.sortedBy { it.toString() }
                val actualCaps = actual.capabilities?.sortedBy { it.toString() }
                if (expectedCaps != actualCaps) {
                    mismatches.add("$key: capabilities mismatch\n    expected=$expectedCaps\n    actual=$actualCaps")
                }
            }

            assertTrue(mismatches.isEmpty()) {
                "Provider '$providerKey': ${mismatches.size} field mismatches:\n  ${mismatches.joinToString("\n  ")}"
            }
        }
    }

    // ========================================================================
    // Phase 1c: Provider alias 验证
    // ========================================================================

    @Nested
    inner class AliasVerification {

        @Test
        fun `provider aliases resolve correctly`() {
            // Direct providers
            assertNotNull(ModelRegistry.getProviderModels("openrouter"))
            assertNotNull(ModelRegistry.getProviderModels("openai"))
            assertNotNull(ModelRegistry.getProviderModels("google"))
            assertNotNull(ModelRegistry.getProviderModels("anthropic"))
            assertNotNull(ModelRegistry.getProviderModels("deepseek"))
            assertNotNull(ModelRegistry.getProviderModels("ollama"))
            assertNotNull(ModelRegistry.getProviderModels("zai"))
            assertNotNull(ModelRegistry.getProviderModels("nvidia"))

            // Aliases
            assertNotNull(ModelRegistry.getProviderModels("open_router"), "open_router alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("open_ai"), "open_ai alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("z.ai"), "z.ai alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("z_ai"), "z_ai alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("z-ai"), "z-ai alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("x-ai"), "x-ai alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("dashscope"), "dashscope alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("moonshot"), "moonshot alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("meta-llama"), "meta-llama alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("mistralai"), "mistralai alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("local"), "local alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("olla"), "olla alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("nvidia_nim"), "nvidia_nim alias should resolve")
            assertNotNull(ModelRegistry.getProviderModels("nvidia-build"), "nvidia-build alias should resolve")
        }

        @Test
        fun `openrouter-routed providers resolve to openrouter models`() {
            val routedProviders = listOf(
                "amazon", "cohere", "zhipu", "baidu", "tencent", "microsoft",
                "alibaba", "bytedance"
            )
            val openrouterModels = ModelRegistry.getProviderModels("openrouter")
            for (provider in routedProviders) {
                val models = ModelRegistry.getProviderModels(provider)
                assertNotNull(models, "Routed provider '$provider' should resolve")
                assertEquals(openrouterModels, models, "Routed provider '$provider' should map to openrouter models")
            }
        }
    }

    // ========================================================================
    // Phase 1d: 总量统计
    // ========================================================================

    @Nested
    inner class TotalCounts {

        @Test
        fun `total model count matches hardcoded total`() {
            val hardcodedTotal = listOf(
                OPEN_ROUTER_MODELS, OPENAI_MODELS, GOOGLE_MODELS, ANTHROPIC_MODELS,
                DEEPSEEK_MODELS, XAI_MODELS, QWEN_MODELS, QWEN_DIRECT_MODELS,
                KIMI_MODELS, MINIMAX_MODELS, META_MODELS, MISTRAL_MODELS,
                PERPLEXITY_MODELS, OLLAMA_MODELS, ZAI_MODELS, NVIDIA_MODELS
            ).sumOf { it.size }

            val yamlTotal = listOf(
                "openrouter", "openai", "google", "anthropic",
                "deepseek", "xai", "qwen", "qwen_direct",
                "kimi", "minimax", "meta", "mistral",
                "perplexity", "ollama", "zai", "nvidia"
            ).sumOf { ModelRegistry.getProviderModels(it)?.size ?: 0 }

            assertEquals(hardcodedTotal, yamlTotal) {
                "Total model count mismatch: hardcoded=$hardcodedTotal, yaml=$yamlTotal"
            }
            println("Total models verified: $yamlTotal (hardcoded: $hardcodedTotal)")
        }
    }

    // ========================================================================
    // ModelRegistry 功能测试
    // ========================================================================

    @Nested
    inner class FunctionalTests {

        @Test
        fun `getModel returns correct model`() {
            val model = ModelRegistry.getModel("openrouter", "grok-4.20")
            assertNotNull(model)
            assertEquals("x-ai/grok-4.20", model!!.id)
            assertEquals(LLMProvider.OpenRouter, model.provider)
        }

        @Test
        fun `Kimi K3 metadata matches official API constraints`() {
            val model = ModelRegistry.getModel("kimi", "kimi-k3")
            assertNotNull(model)
            assertEquals("kimi-k3", model!!.id)
            assertEquals(KIMI_LLM_PROVIDER, model.provider)
            assertEquals(1_048_576L, model.contextLength)
            assertEquals(1_048_576L, model.maxOutputTokens)
            assertTrue(model.supports(LLMCapability.Thinking))
            assertTrue(model.supports(LLMCapability.Tools))
            assertTrue(model.supports(LLMCapability.ToolChoice))
            assertTrue(model.supports(LLMCapability.Schema.JSON.Standard))
            assertTrue(model.supports(LLMCapability.Schema.JSON.Basic))
            assertTrue(model.supports(LLMCapability.Vision.Image))
            assertTrue(model.supports(LLMCapability.OpenAIEndpoint.Completions))
            assertFalse(model.supports(LLMCapability.Temperature))
        }

        @Test
        fun `all direct Kimi models select OpenAI chat completions`() {
            val models = requireNotNull(ModelRegistry.getProviderModels("kimi"))
            assertTrue(models.isNotEmpty())
            models.forEach { (name, model) ->
                assertTrue(
                    model.supports(LLMCapability.OpenAIEndpoint.Completions),
                    "Kimi model '$name' must declare the OpenAI-compatible completions endpoint"
                )
            }
        }

        @Test
        fun `getModel is case-insensitive`() {
            val model = ModelRegistry.getModel("OpenRouter", "GPT-4o")
            assertNotNull(model)
        }

        @Test
        fun `getModel returns null for unknown model`() {
            assertNull(ModelRegistry.getModel("openrouter", "nonexistent-model-xyz"))
        }

        @Test
        fun `getAllProviderModels includes aliases and routed providers`() {
            val all = ModelRegistry.getAllProviderModels()
            assertTrue(all.containsKey("openrouter"))
            assertTrue(all.containsKey("open_router"))  // alias
            assertTrue(all.containsKey("zai"))
            assertTrue(all.containsKey("z_ai"))          // alias
            assertTrue(all.containsKey("nvidia"))
            assertTrue(all.containsKey("nvidia_nim"))    // alias
            assertTrue(all.containsKey("amazon"))        // routed
        }
    }
}
