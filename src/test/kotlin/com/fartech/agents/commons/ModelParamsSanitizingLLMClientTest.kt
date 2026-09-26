package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

class ModelParamsSanitizingLLMClientTest {

    private fun model(
        id: String,
        provider: LLMProvider = LLMProvider.Anthropic,
        capabilities: List<LLMCapability> = STANDARD_CAPABILITIES,
        maxOutputTokens: Long? = null,
    ) = LLModel(provider, id, capabilities, contextLength = 200_000, maxOutputTokens = maxOutputTokens)

    // =========================================================================
    // ModelQuirks — id matching
    // =========================================================================

    @Nested
    inner class ModelQuirksTest {

        @Test
        fun `sampling is rejected by Opus 4_7 and later, Sonnet 5 and every Fable or Mythos 5`() {
            for (id in listOf(
                "claude-opus-4-7", "claude-opus-4.7", "anthropic/claude-opus-4.7", "claude-opus-4-8",
                "claude-opus-5", "claude-opus-5-5", "anthropic/claude-opus-5.5", "claude-sonnet-5",
                "anthropic/claude-sonnet-5", "claude-fable-5", "claude-fable-5-1", "claude-mythos-5-1",
                "CLAUDE-OPUS-5", "claude-opus-4-7-20260101",
            )) {
                assertTrue(ModelQuirks.rejectsSampling(model(id)), id)
            }
        }

        @Test
        fun `sampling is allowed on older Claude and non-Claude models`() {
            for (id in listOf(
                "claude-opus-4-6", "claude-opus-4.6", "claude-sonnet-4-6", "claude-sonnet-4.5",
                "claude-haiku-4-5", "claude-opus-4-20250514", "claude-3-7-sonnet-20250219",
                "claude-opus-latest", "gpt-4o", "kimi-k3", "my-claude-proxy",
            )) {
                assertFalse(ModelQuirks.rejectsSampling(model(id)), id)
            }
        }

        @Test
        fun `Kimi K3 rejects sampling only on the direct Kimi provider`() {
            assertTrue(ModelQuirks.rejectsSampling(model("kimi-k3", provider = KIMI_LLM_PROVIDER)))
            assertTrue(ModelQuirks.rejectsSampling(model("Kimi-K3", provider = KIMI_LLM_PROVIDER)))
            assertFalse(ModelQuirks.rejectsSampling(model("kimi-k2.6", provider = KIMI_LLM_PROVIDER)))
            assertFalse(ModelQuirks.rejectsSampling(model("kimi-k3", provider = LLMProvider.OpenRouter)))
            // A custom K3 declaring STANDARD (Temperature) still gets no temperature on the wire.
            val params = LLMParams(temperature = 0.7).sanitizedFor(model("kimi-k3", provider = KIMI_LLM_PROVIDER), streaming = false)
            assertNull(params.temperature)
        }

        @Test
        fun `forced tool choice is rejected only by the thinking-by-default models`() {
            for (id in listOf(
                "claude-opus-5", "claude-opus-5-5", "anthropic/claude-opus-5.5", "claude-sonnet-5",
                "claude-fable-5", "claude-fable-5-1", "anthropic/claude-fable-5.1", "claude-mythos-5",
            )) {
                assertTrue(ModelQuirks.rejectsForcedToolChoice(model(id)), id)
            }
            for (id in listOf("claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-4-5", "gpt-4o")) {
                assertFalse(ModelQuirks.rejectsForcedToolChoice(model(id)), id)
            }
        }

        @Test
        fun `legacy Claude output ceilings below the default budgets`() {
            assertEquals(32_000L, ModelQuirks.legacyClaudeMaxOutputTokens(model("claude-opus-4-20250514")))
            assertEquals(32_000L, ModelQuirks.legacyClaudeMaxOutputTokens(model("claude-opus-4.1")))
            assertEquals(8_192L, ModelQuirks.legacyClaudeMaxOutputTokens(model("claude-3-5-haiku-20241022")))
            assertEquals(4_096L, ModelQuirks.legacyClaudeMaxOutputTokens(model("claude-3-haiku-20240307")))
            assertNull(ModelQuirks.legacyClaudeMaxOutputTokens(model("claude-opus-4-5")))
            assertNull(ModelQuirks.legacyClaudeMaxOutputTokens(model("claude-opus-5")))
        }
    }

    // =========================================================================
    // LLMParams.sanitizedFor — the three rules
    // =========================================================================

    @Nested
    inner class SanitizeRulesTest {

        @Test
        fun `temperature is dropped for a sampling-rejecting model even when STANDARD declares it`() {
            val params = LLMParams(temperature = 0.7, maxTokens = 1000)
            assertNull(params.sanitizedFor(model("claude-opus-5"), streaming = false).temperature)
        }

        @Test
        fun `temperature is dropped for a model without the Temperature capability`() {
            val kimiK3 = requireNotNull(ModelRegistry.getModel("kimi", "kimi-k3"))
            assertNull(LLMParams(temperature = 0.7).sanitizedFor(kimiK3, streaming = false).temperature)
        }

        @Test
        fun `temperature is kept where it is supported`() {
            val params = LLMParams(temperature = 0.7, maxTokens = 1000)
            assertEquals(0.7, params.sanitizedFor(model("claude-sonnet-4-6"), streaming = false).temperature)
            val gpt4o = requireNotNull(ModelRegistry.getModel("openai", "gpt-4o"))
            assertSame(params, params.sanitizedFor(gpt4o, streaming = false))
        }

        @Test
        fun `forced tool choice is downgraded to auto for thinking-by-default Claude`() {
            for (choice in listOf(LLMParams.ToolChoice.Required, LLMParams.ToolChoice.Named("readFile"))) {
                val params = LLMParams(toolChoice = choice, maxTokens = 1000)
                assertEquals(
                    LLMParams.ToolChoice.Auto,
                    params.sanitizedFor(model("claude-sonnet-5"), streaming = false).toolChoice,
                )
            }
        }

        @Test
        fun `forced tool choice is downgraded when the model lacks the ToolChoice capability`() {
            val noToolChoice = model(
                "some-model",
                provider = LLMProvider.OpenRouter,
                capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion),
            )
            assertEquals(
                LLMParams.ToolChoice.Auto,
                LLMParams(toolChoice = LLMParams.ToolChoice.Required).sanitizedFor(noToolChoice, false).toolChoice,
            )
        }

        @Test
        fun `non-forced tool choices and capable models are left alone`() {
            val gpt4o = requireNotNull(ModelRegistry.getModel("openai", "gpt-4o"))
            assertEquals(
                LLMParams.ToolChoice.Required,
                LLMParams(toolChoice = LLMParams.ToolChoice.Required).sanitizedFor(gpt4o, false).toolChoice,
            )
            assertEquals(
                LLMParams.ToolChoice.None,
                LLMParams(toolChoice = LLMParams.ToolChoice.None, maxTokens = 10)
                    .sanitizedFor(model("claude-fable-5-1"), false).toolChoice,
            )
            assertEquals(
                LLMParams.ToolChoice.Required,
                LLMParams(toolChoice = LLMParams.ToolChoice.Required, maxTokens = 10)
                    .sanitizedFor(model("claude-opus-4-7"), false).toolChoice,
            )
        }

        @Test
        fun `anthropic max_tokens defaults to 16000 without streaming and 64000 with it`() {
            val opus5 = model("claude-opus-5")
            assertEquals(ANTHROPIC_DEFAULT_MAX_TOKENS, LLMParams().sanitizedFor(opus5, streaming = false).maxTokens)
            assertEquals(
                ANTHROPIC_STREAMING_DEFAULT_MAX_TOKENS,
                LLMParams().sanitizedFor(opus5, streaming = true).maxTokens,
            )
        }

        @Test
        fun `anthropic default max_tokens is capped by the model output ceiling`() {
            assertEquals(8_192, LLMParams().sanitizedFor(model("custom", maxOutputTokens = 8_192), true).maxTokens)
            // No catalog ceiling: the known Opus 4 / 4.1 limit applies.
            assertEquals(32_000, LLMParams().sanitizedFor(model("claude-opus-4-20250514"), true).maxTokens)
            assertEquals(16_000, LLMParams().sanitizedFor(model("claude-opus-4-20250514"), false).maxTokens)
        }

        @Test
        fun `explicit max_tokens and non-anthropic providers keep their value`() {
            assertEquals(2_000, LLMParams(maxTokens = 2_000).sanitizedFor(model("claude-opus-5"), false).maxTokens)
            val openRouterClaude = model("anthropic/claude-opus-5", provider = LLMProvider.OpenRouter)
            assertNull(LLMParams().sanitizedFor(openRouterClaude, false).maxTokens)
        }
    }

    // =========================================================================
    // The decorator: every route sees sanitized params, everything else delegates
    // =========================================================================

    @Nested
    inner class DecoratorTest {

        private val opus5 = model("claude-opus-5")
        private val forcedHot = prompt("p", params = LLMParams(temperature = 0.7, toolChoice = LLMParams.ToolChoice.Required)) {
            user("hi")
        }

        @Test
        fun `execute, streaming, multiple choices and moderate all receive sanitized params`() {
            val recording = RecordingClient()
            val client = ModelParamsSanitizingLLMClient(recording)

            runBlocking {
                client.execute(forcedHot, opus5, emptyList())
                client.executeStreaming(forcedHot, opus5, emptyList()).toList()
                client.executeMultipleChoices(forcedHot, opus5, emptyList())
                client.moderate(forcedHot, opus5)
            }

            assertEquals(listOf("execute", "stream", "multi", "moderate"), recording.calls.map { it.first })
            recording.calls.forEach { (route, params) ->
                assertNull(params.temperature, route)
                assertEquals(LLMParams.ToolChoice.Auto, params.toolChoice, route)
            }
            assertEquals(
                listOf(16_000, 64_000, 16_000, 16_000),
                recording.calls.map { it.second.maxTokens },
            )
        }

        @Test
        fun `non-request members delegate`() {
            val recording = RecordingClient()
            val client = ModelParamsSanitizingLLMClient(recording)

            assertEquals(LLMProvider.Anthropic, client.llmProvider())
            assertEquals("recording", client.clientName)
            assertSame(recording.standardGenerator, client.getStandardJsonSchemaGenerator())
            assertSame(recording.basicGenerator, client.getBasicJsonSchemaGenerator())
            runBlocking {
                assertEquals(listOf(1.0), client.embed("x", opus5))
                assertEquals(listOf(listOf(1.0)), client.embed(listOf("x"), opus5))
                assertEquals(listOf("claude-opus-5"), client.models().map { it.id })
            }
            client.close()
            assertTrue(recording.closed)
        }
    }

    private class RecordingClient : LLMClient() {
        val calls = mutableListOf<Pair<String, LLMParams>>()
        var closed = false
        val standardGenerator: StandardJsonSchemaGenerator = StandardJsonSchemaGenerator
        val basicGenerator: BasicJsonSchemaGenerator = BasicJsonSchemaGenerator

        override val clientName: String = "recording"
        override fun llmProvider(): LLMProvider = LLMProvider.Anthropic

        private fun reply() = Message.Assistant("ok", ResponseMetaInfo.Empty)

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            calls += "execute" to prompt.params
            return reply()
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            calls += "stream" to prompt.params
            return emptyFlow()
        }

        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice {
            calls += "multi" to prompt.params
            return listOf(reply())
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
            calls += "moderate" to prompt.params
            return ModerationResult(isHarmful = false, categories = emptyMap())
        }

        override suspend fun models(): List<LLModel> = listOf(model("claude-opus-5"))
        override suspend fun embed(text: String, model: LLModel): List<Double> = listOf(1.0)
        override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> = listOf(listOf(1.0))
        override fun getStandardJsonSchemaGenerator(): StandardJsonSchemaGenerator = standardGenerator
        override fun getBasicJsonSchemaGenerator(): BasicJsonSchemaGenerator = basicGenerator
        override fun close() {
            closed = true
        }

        private fun model(id: String) = LLModel(LLMProvider.Anthropic, id, STANDARD_CAPABILITIES, 1)
    }

    // =========================================================================
    // Shared params across a cascade: sanitized per tier, on the wire
    // =========================================================================

    @Nested
    inner class CascadeTest {

        @Test
        fun `primary keeps its temperature while the claude-opus-5 cascade tier gets none`() {
            val http = CapturingHttpClientFactory { clientName, _ ->
                if (clientName.startsWith("OpenAI")) throw IOException("primary is down")
                CapturingHttpClientFactory.ANTHROPIC_OK
            }
            val parameters = listOf(
                ConfigurationParameter("cascade_fallback_enabled", JsonPrimitive(true)),
                ConfigurationParameter("retry_max_attempts", JsonPrimitive(1)),
                ConfigurationParameter("disable_cache_for_streaming", JsonPrimitive(true)),
            )
            val keys = mapOf("openai" to "sk-openai-test", "anthropic" to "sk-ant-test")
            val primaryConfig = LLModelConfig(provider = "openai", model = "gpt-4o")
            val tierConfig = LLModelConfig(provider = "anthropic", model = "claude-opus-5")
            val (primaryProvider, primaryClient) = createLLMClient(parameters, primaryConfig, keys, http)
            val (tierProvider, tierClient) = createLLMClient(parameters, tierConfig, keys, http)
            val executor = createPromptExecutor(
                parameters = parameters,
                llmClients = mapOf(primaryProvider to primaryClient) to
                    ai.koog.prompt.executor.llms.MultiLLMPromptExecutor.FallbackPromptExecutorSettings(
                        fallbackProvider = primaryProvider,
                        fallbackModel = determineLLMModel(primaryConfig),
                    ),
                extraCascadeTiers = listOf(
                    mapOf(tierProvider to tierClient) to
                        ai.koog.prompt.executor.llms.MultiLLMPromptExecutor.FallbackPromptExecutorSettings(
                            fallbackProvider = tierProvider,
                            fallbackModel = determineLLMModel(tierConfig),
                        )
                ),
            )
            val sharedPrompt = prompt("cascade", params = LLMParams(temperature = 0.7)) { user("hi") }

            runBlocking { executor.execute(sharedPrompt, determineLLMModel(primaryConfig), emptyList()) }

            val openAiRequest = http.requests.single { it.clientName.startsWith("OpenAI") }
            val anthropicRequest = http.requests.single { it.clientName.startsWith("Anthropic") }
            assertEquals(0.7, openAiRequest.json["temperature"]!!.jsonPrimitive.content.toDouble())
            assertFalse(anthropicRequest.json.containsKey("temperature"), anthropicRequest.body)
            assertEquals("claude-opus-5", anthropicRequest.json["model"]!!.jsonPrimitive.content)
            assertEquals(16_000, anthropicRequest.json["max_tokens"]!!.jsonPrimitive.content.toInt())
        }

        @Test
        fun `a claude-opus-5 primary does not take the temperature away from a gpt-4o cascade tier`() {
            val http = CapturingHttpClientFactory { clientName, _ ->
                if (clientName.startsWith("Anthropic")) throw IOException("primary is down")
                CapturingHttpClientFactory.OPENAI_CHAT_OK
            }
            val parameters = listOf(
                ConfigurationParameter("cascade_fallback_enabled", JsonPrimitive(true)),
                ConfigurationParameter("retry_max_attempts", JsonPrimitive(1)),
                ConfigurationParameter("disable_cache_for_streaming", JsonPrimitive(true)),
            )
            val keys = mapOf("openai" to "sk-openai-test", "anthropic" to "sk-ant-test")
            val primaryConfig = LLModelConfig(provider = "anthropic", model = "claude-opus-5")
            val tierConfig = LLModelConfig(provider = "openai", model = "gpt-4o")
            val (primaryProvider, primaryClient) = createLLMClient(parameters, primaryConfig, keys, http)
            val (tierProvider, tierClient) = createLLMClient(parameters, tierConfig, keys, http)
            val executor = createPromptExecutor(
                parameters = parameters,
                llmClients = mapOf(primaryProvider to primaryClient) to
                    ai.koog.prompt.executor.llms.MultiLLMPromptExecutor.FallbackPromptExecutorSettings(
                        fallbackProvider = primaryProvider,
                        fallbackModel = determineLLMModel(primaryConfig),
                    ),
                extraCascadeTiers = listOf(
                    mapOf(tierProvider to tierClient) to
                        ai.koog.prompt.executor.llms.MultiLLMPromptExecutor.FallbackPromptExecutorSettings(
                            fallbackProvider = tierProvider,
                            fallbackModel = determineLLMModel(tierConfig),
                        )
                ),
            )
            // The prompt temperature is resolved exactly as buildAgent does it.
            val sharedPrompt = prompt("cascade", params = LLMParams(temperature = resolveModelTemperature(0.7))) {
                user("hi")
            }

            runBlocking { executor.execute(sharedPrompt, determineLLMModel(primaryConfig), emptyList()) }

            val anthropicRequest = http.requests.first { it.clientName.startsWith("Anthropic") }
            val openAiRequest = http.requests.single { it.clientName.startsWith("OpenAI") }
            assertFalse(anthropicRequest.json.containsKey("temperature"), anthropicRequest.body)
            assertEquals(0.7, openAiRequest.json["temperature"]!!.jsonPrimitive.content.toDouble())
        }
    }
}
