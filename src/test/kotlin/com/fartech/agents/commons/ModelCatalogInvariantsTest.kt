package com.fartech.agents.commons

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Registry-wide invariants for the bundled model catalog (the YAML files under resources/models).
 *
 * These guard the rules a catalog entry must honour on its own, independent of
 * any runtime guard: wire-endpoint capabilities for OpenAI-client providers,
 * sampling / tool-choice restrictions of current Claude models, and that every
 * model referenced by presets and bundled templates actually resolves.
 */
class ModelCatalogInvariantsTest {

    private val modelFiles = listOf(
        "openrouter.yaml", "openai.yaml", "google.yaml", "anthropic.yaml",
        "deepseek.yaml", "xai.yaml", "qwen.yaml", "qwen-direct.yaml",
        "kimi.yaml", "minimax.yaml", "meta.yaml", "mistral.yaml",
        "perplexity.yaml", "ollama.yaml", "lmstudio.yaml", "zai.yaml",
        "nvidia.yaml"
    )

    /** Providers whose client is Koog's OpenAILLMClient (AgentModels.createLLMClient). */
    private val openAiClientProviders = listOf("openai", "qwen_direct", "kimi", "minimax", "lmstudio", "zai", "nvidia")

    private fun resourceText(path: String): String =
        requireNotNull(javaClass.classLoader.getResource(path)) { "missing resource $path" }.readText()

    private fun models(provider: String): Map<String, LLModel> =
        requireNotNull(ModelRegistry.getProviderModels(provider)) { "no models for provider $provider" }

    /** Wire id without vendor prefix, dots normalised to dashes: `anthropic/claude-opus-4.8` → `claude-opus-4-8`. */
    private fun normalizedClaudeId(id: String): String = id.substringAfterLast('/').replace('.', '-')

    private val samplingRejectingClaude = Regex("^claude-(opus-4-[78]|opus-5(-\\d+)?|fable-5(-\\d+)?|sonnet-5(-\\d+)?)$")
    private val thinkingDefaultClaude = Regex("^claude-(opus-5(-\\d+)?|fable-5(-\\d+)?|sonnet-5(-\\d+)?)$")

    private fun claudeModels(): List<Pair<String, LLModel>> =
        (models("anthropic").map { "anthropic:${it.key}" to it.value } +
            models("openrouter").filter { it.value.id.startsWith("anthropic/") }.map { "openrouter:${it.key}" to it.value })

    @Test
    fun `no model key is declared twice within a provider file`() {
        val keyLine = Regex("^  \"([^\"]+)\":\\s*$")
        val duplicates = modelFiles.flatMap { file ->
            resourceText("models/$file").lineSequence()
                .mapNotNull { keyLine.find(it)?.groupValues?.get(1)?.lowercase() }
                .groupingBy { it }.eachCount()
                .filterValues { it > 1 }
                .keys.map { "$file: $it" }
        }
        assertTrue(duplicates.isEmpty()) { "Duplicate model keys (the YAML parser keeps only the last):\n  ${duplicates.joinToString("\n  ")}" }
    }

    @Test
    fun `every OpenAI-client provider model declares a wire endpoint`() {
        val missing = openAiClientProviders.flatMap { provider ->
            models(provider).filter { (_, model) ->
                !model.supports(LLMCapability.OpenAIEndpoint.Completions) &&
                    !model.supports(LLMCapability.OpenAIEndpoint.Responses)
            }.keys.map { "$provider:$it" }
        }
        assertTrue(missing.isEmpty()) {
            "OpenAILLMClient throws 'Cannot determine proper LLM params' for:\n  ${missing.joinToString("\n  ")}"
        }
    }

    @Test
    fun `Claude Opus 4_7 and later never declare temperature`() {
        val offenders = claudeModels()
            .filter { (_, model) -> samplingRejectingClaude.matches(normalizedClaudeId(model.id)) }
            .also { assertTrue(it.size >= 10, "expected the Claude 4.7+/5.x entries to be present, got ${it.map { p -> p.first }}") }
            .filter { (_, model) -> model.supports(LLMCapability.Temperature) }
            .map { it.first }
        assertTrue(offenders.isEmpty()) { "These Claude models reject sampling parameters: $offenders" }
    }

    @Test
    fun `thinking-by-default Claude models never declare tool_choice`() {
        val offenders = claudeModels()
            .filter { (_, model) -> thinkingDefaultClaude.matches(normalizedClaudeId(model.id)) }
            .filter { (_, model) -> model.supports(LLMCapability.ToolChoice) || !model.supports(LLMCapability.Thinking) }
            .map { it.first }
        assertTrue(offenders.isEmpty()) { "Forced tool choice 400s / thinking must be declared on: $offenders" }
    }

    @Test
    fun `first-party Anthropic entries send dashed ids`() {
        val dotted = models("anthropic").filter { (_, model) -> model.id.contains('.') || model.id.contains('/') }
        assertTrue(dotted.isEmpty()) { "Anthropic's API rejects OpenRouter spellings: ${dotted.map { "${it.key}->${it.value.id}" }}" }
        // Legacy dotted keys stay resolvable for saved configs, but send the dashed id.
        assertEquals("claude-opus-4-7", models("anthropic")["claude-opus-4.7"]?.id)
        assertEquals("claude-haiku-4-5", models("anthropic")["claude-haiku-4.5"]?.id)
        // Deprecated-but-served Claude 4 keys keep resolving to their dashed alias ids.
        assertEquals("claude-sonnet-4-0", models("anthropic")["claude-sonnet-4"]?.id)
        assertEquals("claude-opus-4-0", models("anthropic")["claude-opus-4"]?.id)
    }

    @Test
    fun `tier aliases stay on models that accept forced tool choice`() {
        // Saved agents on `claude-opus` / `claude-sonnet` must keep working until the runtime
        // temperature / tool_choice guards ship; Opus 5 / Sonnet 5 would 400 on both.
        val anthropic = models("anthropic")
        assertEquals("claude-opus-4-8", anthropic["claude-opus"]?.id)
        assertEquals("claude-sonnet-4-6", anthropic["claude-sonnet"]?.id)
        assertTrue(anthropic["claude-opus"]!!.supports(LLMCapability.ToolChoice))
        assertTrue(anthropic["claude-sonnet"]!!.supports(LLMCapability.ToolChoice))
        assertTrue(anthropic["claude-sonnet"]!!.supports(LLMCapability.Temperature))
    }

    @Test
    fun `current Claude entries carry their real limits`() {
        val opus5 = models("anthropic")["claude-opus-5"]!!
        assertEquals(1_000_000L, opus5.contextLength)
        assertEquals(128_000L, opus5.maxOutputTokens)
        val haiku = models("anthropic")["claude-haiku-4-5"]!!
        assertEquals(200_000L, haiku.contextLength)
        assertEquals(64_000L, haiku.maxOutputTokens)
        assertTrue(haiku.supports(LLMCapability.Temperature))
        assertEquals("anthropic/claude-sonnet-5", models("openrouter")["claude-sonnet-5"]?.id)
        assertEquals("anthropic/claude-opus-4.8", models("openrouter")["claude-opus-4.8"]?.id)
    }

    @Test
    fun `mistral provider only sends OpenRouter slugs`() {
        val bare = models("mistral").filterValues { !it.id.startsWith("mistralai/") }
        assertTrue(bare.isEmpty()) { "The mistral provider is OpenRouter-routed; bare ids fail there: ${bare.keys}" }
    }

    @Test
    fun `shut-down Gemini ids are gone and the default image model is live`() {
        val google = models("google")
        listOf("gemini-3-pro-preview", "gemini-3.1-flash-lite-preview", "gemini-3.1-flash-image-preview", "gemini-2.0-flash")
            .forEach { assertNull(google[it], "$it was shut down by Google") }
        assertNull(models("openrouter")["gemini-3-pro-preview"])
        assertTrue(google.getValue("gemini-3.8-flash").supports(LLMCapability.Thinking))
        assertEquals("google/gemini-3.1-flash-image", ModelRegistry.getDefaultImageModel())
    }

    @Test
    fun `GPT-5_x and GPT-6 reasoning models omit temperature`() {
        val offenders = (models("openai").values + models("openrouter").values)
            .filter { Regex("^(openai/)?gpt-(5|6)(\\.\\d+)?(-|$)").containsMatchIn(it.id) && !it.id.contains("-image") }
            .filter { it.supports(LLMCapability.Temperature) }
            .map { it.id }
        assertTrue(offenders.isEmpty()) { "GPT-5.x/6 reject non-default temperature: $offenders" }
    }

    @Test
    fun `DEFAULT_LLM_MODEL resolves from the registry`() {
        assertEquals("x-ai/grok-4.20", DEFAULT_LLM_MODEL.id)
        assertNotNull(ModelRegistry.getModel(DEFAULT_LLM_MODEL_CONFIG.provider, DEFAULT_LLM_MODEL_CONFIG.model))
    }

    @Test
    fun `every model referenced by presets and bundled templates exists in the registry`() {
        val pair = Regex("model:\\s*\"?([\\w./:-]+)\"?\\s*\\n\\s*provider:\\s*\"?([\\w.-]+)\"?")
        val presetDir = File(requireNotNull(javaClass.classLoader.getResource("agent-presets")).toURI())
        val sources = (presetDir.walkTopDown() + File("workflows").walkTopDown() + File("examples").walkTopDown())
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .toList()
        assertTrue(sources.any { it.path.contains("agent-presets") })
        val unresolved = sources.flatMap { file ->
            pair.findAll(file.readText()).mapNotNull { match ->
                val (model, provider) = match.destructured
                if (model.contains('$') || provider.contains('$')) return@mapNotNull null
                if (ModelRegistry.getModel(provider, model) == null) "${file.path}: $provider/$model" else null
            }.toList()
        }
        assertTrue(unresolved.isEmpty()) { "Unresolvable model references:\n  ${unresolved.joinToString("\n  ")}" }
        assertFalse(sources.isEmpty())
    }
}
