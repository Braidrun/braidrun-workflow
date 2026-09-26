package com.fartech.agents.commons

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.fartech.agents.jev.JEV_NOT_A_CHAT_MODEL_MESSAGE
import com.fartech.agents.jev.isTypeSafeProviderId
import com.fartech.agents.workflow.WorkflowHostPolicy
import com.fartech.agents.tools.toLLM
import com.fartech.agents.tools.toLLModel
import com.fartech.ftapp2.commonsKt.*
import io.ktor.client.*
import mu.KotlinLogging
import io.ktor.client.plugins.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.decodeFromJsonElement

private val logger = KotlinLogging.logger {}

// ============================================================================
// Configuration Data Classes
// ============================================================================

/** @deprecated 已迁移到 ollama.yaml，通过 ModelRegistry 加载。保留常量引用以兼容外部代码。 */
val GPT_OSS_120b: LLModel
    get() = ModelRegistry.getModel("ollama", "gpt-oss:120b")
        ?: error("gpt-oss:120b not found in ModelRegistry — check ollama.yaml")


// `llm_config` reaches the engine in both spellings: the engine's own
// AgentDefinition.resolveParameters and braidrun-web write snake_case (`base_url`,
// `max_token`, `is_vision`, `custom_models`), hand-written workflow parameters and older
// callers use camelCase. JsonMapper ignores unknown keys, so without the @JsonNames aliases
// every snake_case value was silently dropped (e.g. the base URL, sending the request to the
// provider default instead).

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LLModelConfig(
    val provider: String,
    val model: String,
    @JsonNames("base_url")
    val baseUrl: String? = null,
    @JsonNames("max_token")
    val maxToken: Long? = null,
    @JsonNames("is_vision")
    val isVision: Boolean? = null,
    @JsonNames("display_name")
    val displayName: String? = null,
    val capabilities: List<String>? = null,
    val description: String? = null
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class CustomModelDefinition(
    val name: String,
    val provider: String,
    @JsonNames("model_id")
    val modelId: String,
    @JsonNames("base_url")
    val baseUrl: String? = null,
    @JsonNames("context_length")
    val contextLength: Long? = null,
    @JsonNames("max_output_tokens")
    val maxOutputTokens: Long? = null,
    @JsonNames("is_vision")
    val isVision: Boolean? = null,
    val capabilities: List<String>? = null,
    val description: String? = null
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LLModelGroupConfig(
    val models: List<LLModelConfig>,
    val fallback: LLModelConfig = DEFAULT_LLM_MODEL_CONFIG,
    val temperature: Double? = 1.0,
    val agentDefinedSettings: JsonElement = Json.parseToJsonElement("{}"),
    @JsonNames("custom_models")
    val customModels: List<CustomModelDefinition>? = null, // New field for custom model definitions
    /**
     * Ordered list of additional LLM provider tiers to try **on error**
     * after the primary (`models` + `fallback`) chain has been exhausted.
     *
     * Introduced 2026-04 alongside [com.fartech.agents.commons.CascadingFallbackPromptExecutor].
     * Each entry is a full [LLModelConfig] describing a completely
     * independent provider (different API key pool, different endpoint,
     * possibly a different model family). The cascade is activated by
     * setting the `cascade_fallback_enabled=true` configuration parameter;
     * an empty list here keeps pre-Tier-1 behaviour exactly unchanged.
     *
     * Typical usage:
     *
     * ```
     * cascadeFallbacks = listOf(
     *     LLModelConfig(provider = "openrouter", model = "anthropic/claude-sonnet-4.5"),
     *     LLModelConfig(provider = "deepseek", model = "deepseek-flash"),
     * )
     * ```
     */
    @JsonNames("cascade_fallbacks")
    val cascadeFallbacks: List<LLModelConfig> = emptyList(),
)

/**
 * 历史消息压缩配置，用于在 toolCycleGraph / toolCycleGraphMulti 中节约 token。
 *
 * strategy 取值：
 * - whole_history（默认）：压缩所有历史消息
 * - whole_history_multi_system：压缩所有历史消息（支持多系统消息场景）
 * - from_last_n：只保留最近 [n] 条消息，其余压缩
 * - chunked：按 [chunkSize] 分块压缩
 */
@Serializable
data class HistoryCompressionConfig(
    val strategy: String = "whole_history",
    val n: Int = 10,
    val chunkSize: Int = 5,
    val preserveMemory: Boolean = true
)

fun LLModelGroupConfig.foundModelWithName(name: String): LLModelConfig =
    models.firstOrNull { it.model == name } ?: DEFAULT_LLM_MODEL_CONFIG

/**
 * Null-safe model resolution: returns [DEFAULT_LLM_MODEL_CONFIG] when [assignmentName] is null
 * instead of throwing NullPointerException.
 */
fun LLModelGroupConfig.resolveModel(assignmentName: String?): LLModelConfig {
    val name = assignmentName ?: return DEFAULT_LLM_MODEL_CONFIG
    return foundModelWithName(name)
}

inline fun <reified T> LLModelGroupConfig.getAgentDefinedSettings(): T =
    Json.decodeFromJsonElement(agentDefinedSettings)

/**
 * Attempts to extract a default model name from agent-defined settings.
 * Looks for common patterns like "default", "modelAssignments.default", etc.
 * Returns null if no default model name is found in the settings.
 */
fun LLModelGroupConfig.getDefaultModelName(): String? {
    return try {
        val jsonObj = agentDefinedSettings as? kotlinx.serialization.json.JsonObject ?: return null

        // Try direct "default" field
        jsonObj["default"]?.let {
            if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) {
                return it.content
            }
        }

        // Try "modelAssignments.default" pattern (for IOSAgentDefinedSettings and similar)
        jsonObj["modelAssignments"]?.let { modelAssignments ->
            if (modelAssignments is kotlinx.serialization.json.JsonObject) {
                modelAssignments["default"]?.let { default ->
                    if (default is kotlinx.serialization.json.JsonPrimitive && default.isString) {
                        return default.content
                    }
                }
            }
        }

        null
    } catch (e: Exception) {
        null
    }
}

/**
 * The model an agent built from this group runs on when the caller passes no explicit model:
 * the agent-defined `default` assignment, else the first configured model, else [DEFAULT_LLM_MODEL].
 * Mirrors the `llmModel` default of `buildAgent`.
 */
fun LLModelGroupConfig.resolveDefaultLLModel(): LLModel =
    getDefaultModelName()?.let { foundModelWithName(it).toLLM().toLLModel() }
        ?: models.firstOrNull()?.toLLM()?.toLLModel()
        ?: DEFAULT_LLM_MODEL

fun List<ConfigurationParameter>.getLLMGroupConfig(): LLModelGroupConfig =
    parameter(key = "llm_config", defaultValue = LLModelGroupConfig(models = listOf()))

fun List<ConfigurationParameter>.firstLLMConfig(): LLModelConfig =
    parameter(key = "llm_config", defaultValue = LLModelGroupConfig(models = listOf())).models.firstOrNull()
        ?: DEFAULT_LLM_MODEL_CONFIG

// ============================================================================
// Capabilities and Defaults
// ============================================================================

val BASIC_CAPABILITIES: List<LLMCapability> = listOf(
    LLMCapability.Schema.JSON.Standard,
    LLMCapability.Speculation,
    LLMCapability.Tools,
    LLMCapability.ToolChoice,
    LLMCapability.Completion
)

val STANDARD_CAPABILITIES = BASIC_CAPABILITIES + LLMCapability.Temperature
val MULTIMODAL_CAPABILITIES = STANDARD_CAPABILITIES + LLMCapability.Vision.Image

/**
 * Converts capability strings from configuration to LLMCapability enums.
 * Supports both simple names (e.g., "Temperature", "Tools") and nested names (e.g., "Vision.Image").
 */
fun parseCapabilities(capabilityStrings: List<String>?): List<LLMCapability> {
    if (capabilityStrings == null) return STANDARD_CAPABILITIES

    val capabilities = mutableListOf<LLMCapability>()

    for (cap in capabilityStrings) {
        when (cap.lowercase()) {
            "temperature" -> capabilities.add(LLMCapability.Temperature)
            "tools" -> capabilities.add(LLMCapability.Tools)
            "toolchoice", "tool_choice" -> capabilities.add(LLMCapability.ToolChoice)
            "completion" -> capabilities.add(LLMCapability.Completion)
            "speculation" -> capabilities.add(LLMCapability.Speculation)
            "thinking", "reasoning" -> capabilities.add(LLMCapability.Thinking)
            "vision", "vision.image", "image" -> capabilities.add(LLMCapability.Vision.Image)
            "vision.video", "video" -> capabilities.add(LLMCapability.Vision.Video)
            "audio" -> capabilities.add(LLMCapability.Audio)
            "document" -> capabilities.add(LLMCapability.Document)
            "json", "schema.json.standard" -> capabilities.add(LLMCapability.Schema.JSON.Standard)
            "json.basic", "schema.json.basic" -> capabilities.add(LLMCapability.Schema.JSON.Basic)
            "multiplechoices", "multiple_choices" -> capabilities.add(LLMCapability.MultipleChoices)
            "openai.completions" -> capabilities.add(LLMCapability.OpenAIEndpoint.Completions)
            "openai.responses" -> capabilities.add(LLMCapability.OpenAIEndpoint.Responses)
            "standard" -> capabilities.addAll(STANDARD_CAPABILITIES)
            "multimodal" -> capabilities.addAll(MULTIMODAL_CAPABILITIES)
            "basic" -> capabilities.addAll(BASIC_CAPABILITIES)
            else -> logger.warn { "Unknown LLM capability string: '$cap' — ignoring" }
        }
    }

    return if (capabilities.isEmpty()) STANDARD_CAPABILITIES else capabilities.distinct()
}

// ============================================================================
// Model Registry — 模型定义已迁移到 resources/models/*.yaml
// 通过 ModelRegistry 从 YAML 配置文件加载，支持用户自定义覆盖。
// ============================================================================

/** @see ModelRegistry */
val OPEN_ROUTER_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("openrouter") ?: emptyMap()
val OPENAI_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("openai") ?: emptyMap()
val GOOGLE_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("google") ?: emptyMap()
val ANTHROPIC_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("anthropic") ?: emptyMap()
val DEEPSEEK_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("deepseek") ?: emptyMap()
val XAI_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("xai") ?: emptyMap()
val QWEN_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("qwen") ?: emptyMap()
val QWEN_DIRECT_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("qwen_direct") ?: emptyMap()
val KIMI_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("kimi") ?: emptyMap()
val MINIMAX_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("minimax") ?: emptyMap()
val META_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("meta") ?: emptyMap()
val MISTRAL_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("mistral") ?: emptyMap()
val PERPLEXITY_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("perplexity") ?: emptyMap()
val OLLAMA_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("ollama") ?: emptyMap()
val ZAI_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("zai") ?: emptyMap()
val NVIDIA_MODELS: Map<String, LLModel> get() = ModelRegistry.getProviderModels("nvidia") ?: emptyMap()

val DEFAULT_LLM_MODEL: LLModel get() = OPEN_ROUTER_MODELS["grok-4.20"]
    ?: error("Default model grok-4.20 not found — check openrouter.yaml")

val DEFAULT_LLM_MODEL_CONFIG = LLModelConfig(
    provider = "openrouter",
    model = "grok-4.20"
)

val LLM_PROVIDER_MODELS: Map<String, Map<String, LLModel>> get() = ModelRegistry.getAllProviderModels()


// Thread-safe custom model registry
private val CUSTOM_MODEL_REGISTRY = mutableMapOf<String, MutableMap<String, LLModel>>()
private val registryLock = Any()

/**
 * TypeSafe Jev returns typed decisions, not chat completions, so `typesafe` /
 * `typesafe_ai` / `jev` must never resolve to a chat client. Without this guard the
 * id would hit the unknown-provider → OpenRouter default and send the Jev key to
 * openrouter.ai. Jev is used through `classifier.jev` / `repeat_until.jev` only.
 *
 * @throws IllegalArgumentException for a TypeSafe provider id.
 */
internal fun rejectTypeSafeChatProvider(provider: String) {
    if (isTypeSafeProviderId(provider)) throw IllegalArgumentException(JEV_NOT_A_CHAT_MODEL_MESSAGE)
}

/**
 * Maps a provider string (lowercase) to the corresponding [LLMProvider] enum value.
 *
 * Providers that are routed through OpenRouter (xAI, Qwen, Meta, Mistral, Perplexity)
 * are mapped to [LLMProvider.OpenRouter]. Unknown providers also default to OpenRouter.
 * Either way the client only ever gets an OpenRouter key ([ApiKeySource.openRouterOrThrow]).
 * TypeSafe (Jev) ids are rejected — see [rejectTypeSafeChatProvider].
 */
private fun mapProviderToLLMProvider(providerKey: String): LLMProvider = when (providerKey) {
    // Jev is a decision model: never let it reach the OpenRouter catch-all below.
    "typesafe", "typesafe_ai", "typesafe-ai", "jev" -> throw IllegalArgumentException(JEV_NOT_A_CHAT_MODEL_MESSAGE)
    "openrouter", "open_router" -> LLMProvider.OpenRouter
    "openai", "open_ai" -> LLMProvider.OpenAI
    "google" -> LLMProvider.Google
    "anthropic" -> LLMProvider.Anthropic
    "deepseek" -> LLMProvider.DeepSeek
    "xai", "x-ai" -> LLMProvider.OpenRouter
    "qwen" -> LLMProvider.OpenRouter                       // Qwen via OpenRouter
    "qwen_direct", "dashscope" -> QWEN_DIRECT_LLM_PROVIDER       // Qwen via DashScope (OpenAI-compatible)
    "kimi", "moonshot" -> KIMI_LLM_PROVIDER                       // Kimi/Moonshot (OpenAI-compatible)
    "minimax" -> LLMProvider.MiniMax                              // MiniMax (OpenAI-compatible)
    "lmstudio", "lm-studio", "lm_studio" -> LMSTUDIO_LLM_PROVIDER // LM Studio (OpenAI-compatible)
    "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai" -> ZAI_LLM_PROVIDER // Z.ai official (OpenAI-compatible)
    "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build" -> NVIDIA_LLM_PROVIDER // NVIDIA-hosted NIM APIs
    "meta", "meta-llama" -> LLMProvider.OpenRouter
    "mistral", "mistralai" -> LLMProvider.OpenRouter
    "perplexity" -> LLMProvider.OpenRouter
    "amazon", "cohere", "zhipu", "baidu", "xiaomi",
    "inception", "writer", "upstage", "stepfun",
    "arcee-ai", "arcee", "ai21", "aion-labs",
    "deepcogito", "ibm-granite", "liquid", "kwaipilot",
    "rekaai", "reka", "tencent", "microsoft",
    "nousresearch", "alibaba", "meituan", "morph",
    "prime-intellect", "essentialai", "bytedance",
    "moonshotai" -> LLMProvider.OpenRouter                 // All routed via OpenRouter
    "ollama", "local", "olla" -> LLMProvider.Ollama
    else -> LLMProvider.OpenRouter
}

/**
 * Registers a custom model definition to the model registry.
 * This allows models defined in configuration files to be used like built-in models.
 */
fun registerCustomModel(definition: CustomModelDefinition) {
    synchronized(registryLock) {
        val providerKey = definition.provider.lowercase()
        val modelKey = definition.name.lowercase()

        val provider = mapProviderToLLMProvider(providerKey)

        // Parse capabilities
        val declaredCapabilities = when {
            definition.capabilities != null -> parseCapabilities(definition.capabilities)
            definition.isVision == true -> MULTIMODAL_CAPABILITIES
            else -> STANDARD_CAPABILITIES
        }
        val capabilities = withClientRequiredCapabilities(provider, definition.modelId, declaredCapabilities)

        // Create LLModel instance
        val model = LLModel(
            provider = provider,
            id = definition.modelId,
            capabilities = capabilities,
            contextLength = definition.contextLength ?: 32_768L,
            maxOutputTokens = definition.maxOutputTokens
        )

        // Register in custom registry
        CUSTOM_MODEL_REGISTRY.getOrPut(providerKey) { mutableMapOf() }[modelKey] = model

        printlnColor(
            AnsiColor.GREEN,
            "Registered custom model: '$modelKey' (provider: $providerKey, id: ${definition.modelId})"
        )
    }
}

/**
 * Registers multiple custom model definitions from configuration.
 */
fun registerCustomModels(definitions: List<CustomModelDefinition>?) {
    definitions?.forEach { registerCustomModel(it) }
}

/** Removes a custom model registered by [registerCustomModel]; lets tests leave the global registry clean. */
internal fun unregisterCustomModel(provider: String, name: String) {
    synchronized(registryLock) {
        CUSTOM_MODEL_REGISTRY[provider.lowercase()]?.remove(name.lowercase())
    }
}

/**
 * Gets a custom model from the registry.
 */
private fun getCustomModel(provider: String, modelName: String): LLModel? {
    synchronized(registryLock) {
        return CUSTOM_MODEL_REGISTRY[provider.lowercase()]?.get(modelName.lowercase())
    }
}

// ============================================================================
// Model Resolution Functions
// ============================================================================

/**
 * Resolves the LLM model based on configuration.
 * Checks in this order:
 * 1. Custom model registry (from configuration)
 * 2. Built-in provider models
 * 3. Dynamically creates model from configuration
 */
fun determineLLMModel(llmModelConfig: LLModelConfig): LLModel {
    rejectTypeSafeChatProvider(llmModelConfig.provider)
    val providerKey = llmModelConfig.provider.lowercase()
    val modelKey = llmModelConfig.model.lowercase()

    // First, check custom model registry
    val customModel = getCustomModel(providerKey, modelKey)
    if (customModel != null) return customModel

    // Second, check built-in provider models
    val providerModels = LLM_PROVIDER_MODELS[providerKey]
    val builtInModel = providerModels?.get(modelKey)
    if (builtInModel != null) return builtInModel

    // Finally, create a custom model dynamically based on configuration
    return createCustomModel(llmModelConfig)
}

/**
 * Creates a custom model dynamically based on configuration.
 * This allows models to be automatically generated from database configuration
 * without requiring pre-definition in the code.
 *
 * Supports all providers: OpenRouter, OpenAI, Google, Anthropic, DeepSeek, Ollama, etc.
 */
private fun createCustomModel(modelConfig: LLModelConfig): LLModel {
    val providerKey = modelConfig.provider.lowercase()
    val hasExplicitConfig = modelConfig.maxToken != null ||
            modelConfig.isVision != null ||
            modelConfig.capabilities != null
    val configSource = if (hasExplicitConfig) "explicit configuration" else "default values"

    printlnColor(
        AnsiColor.YELLOW,
        "Unknown model '${modelConfig.model}' for provider '$providerKey', creating custom model using $configSource."
    )

    // Determine context length
    val contextLength = modelConfig.maxToken ?: 32_768L

    // Determine capabilities from configuration
    val declaredCapabilities = when {
        // If capabilities are explicitly defined, use them
        modelConfig.capabilities != null -> parseCapabilities(modelConfig.capabilities)
        // If vision is specified, use multimodal capabilities
        modelConfig.isVision == true -> MULTIMODAL_CAPABILITIES
        // Default to standard capabilities
        else -> STANDARD_CAPABILITIES
    }

    val provider = mapProviderToLLMProvider(providerKey)
    val capabilities = withClientRequiredCapabilities(provider, modelConfig.model, declaredCapabilities)
    if (provider == LLMProvider.OpenRouter && providerKey !in setOf(
            "openrouter", "open_router",
            "xai", "x-ai", "qwen", "meta", "meta-llama",
            "mistral", "mistralai", "perplexity",
            "amazon", "cohere", "zhipu", "baidu", "xiaomi",
            "inception", "writer", "upstage", "stepfun",
            "arcee-ai", "arcee", "ai21", "aion-labs",
            "deepcogito", "ibm-granite", "liquid", "kwaipilot",
            "rekaai", "reka", "tencent", "microsoft",
            "nousresearch", "alibaba", "meituan", "morph",
            "prime-intellect", "essentialai", "bytedance",
            "moonshotai"
        ) || provider == LLMProvider.OpenAI && providerKey !in setOf(
            "openai", "open_ai",
            "kimi", "moonshot", "minimax", "qwen_direct", "dashscope",
            "lmstudio", "lm-studio", "lm_studio",
            "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai",
            "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build"
        )
    ) {
        printlnColor(
            AnsiColor.YELLOW,
            "Unknown provider '$providerKey', defaulting to OpenRouter."
        )
    }

    // Determine model ID: for OpenRouter, if model doesn't contain "/", 
    // it might need provider prefix, but we'll use it as-is since the model
    // identifier from database/config should already be correct.
    // For other providers, use the model name directly.
    val modelId = modelConfig.model

    return LLModel(
        provider = provider,
        id = modelId,
        capabilities = capabilities,
        contextLength = contextLength
    )
}

/**
 * Converts database AgentModelInfo to LLModelConfig.
 * This allows models from database to be automatically converted to LLModelConfig
 * for dynamic model creation.
 * 
 * @param agentModelInfo The model information from database
 * @return LLModelConfig that can be used with determineLLMModel()
 */
fun agentModelInfoToLLModelConfig(agentModelInfo: Any): LLModelConfig {
    // Use reflection to access fields since AgentModelInfo is in a different module
    // This is a workaround - ideally we'd import AgentModelInfo directly
    val modelField = agentModelInfo.javaClass.getMethod("getModel").invoke(agentModelInfo) as? String
    val providerField = agentModelInfo.javaClass.getMethod("getProvider").invoke(agentModelInfo) as? String
    val contextLengthField = agentModelInfo.javaClass.getMethod("getContextLength").invoke(agentModelInfo) as? Number
    val inputModalitiesField =
        agentModelInfo.javaClass.getMethod("getInputModalities").invoke(agentModelInfo) as? List<*>

    // Extract model name from id if model field is null
    val modelName = modelField ?: (agentModelInfo.javaClass.getMethod("getId").invoke(agentModelInfo) as? String)
        ?.substringAfterLast("/") ?: "unknown"

    // Determine if model is multimodal based on inputModalities
    // Only pure text (single text modality) is considered unimodal (STANDARD_CAPABILITIES)
    // Any other modality (image, video, audio, file) makes it multimodal (MULTIMODAL_CAPABILITIES)
    val isMultimodal = if (inputModalitiesField.isNullOrEmpty()) {
        false  // No modalities specified, default to standard
    } else {
        val modalities = inputModalitiesField.mapNotNull { it?.toString()?.lowercase() }
        // If only "text" modality exists, it's unimodal; otherwise it's multimodal
        !(modalities.size == 1 && modalities.contains("text"))
    }

    return LLModelConfig(
        provider = providerField?.lowercase() ?: "openrouter",
        model = modelName,
        maxToken = contextLengthField?.toLong(),
        isVision = isMultimodal
    )
}

/**
 * Converts database AgentModelInfo directly to LLModel.
 * This is a convenience function that combines agentModelInfoToLLModelConfig and determineLLMModel.
 * 
 * @param agentModelInfo The model information from database
 * @return LLModel that can be used with koog framework
 */
fun agentModelInfoToLLModel(agentModelInfo: Any): LLModel {
    val config = agentModelInfoToLLModelConfig(agentModelInfo)
    return determineLLMModel(config)
}

// ============================================================================
// Client Factory Functions
// ============================================================================

private fun envVarNamesForProvider(provider: String): List<String> = when (provider.lowercase()) {
    "openai", "open_ai" -> listOf("OPENAI_API_KEY")
    "openrouter", "open_router" -> listOf("OPENROUTER_API_KEY", "OPEN_ROUTER_API_KEY")
    "google" -> listOf("GOOGLE_API_KEY", "GOOGLE_GENAI_API_KEY", "GENAI_API_KEY")
    "anthropic" -> listOf("ANTHROPIC_API_KEY")
    "deepseek" -> listOf("DEEPSEEK_API_KEY")
    // Served by openrouter.ai (see mapProviderToLLMProvider): only OpenRouter keys, never the vendor's own.
    "xai", "x-ai", "qwen", "meta", "meta-llama", "mistral", "mistralai", "perplexity" ->
        listOf("OPENROUTER_API_KEY", "OPEN_ROUTER_API_KEY")
    "qwen_direct", "dashscope" -> listOf("DASHSCOPE_API_KEY", "QWEN_API_KEY") // Qwen Direct via DashScope
    "kimi", "moonshot" -> listOf("KIMI_API_KEY", "MOONSHOT_API_KEY") // Kimi / Moonshot
    "minimax" -> listOf("MINIMAX_API_KEY") // MiniMax
    "lmstudio", "lm-studio", "lm_studio" -> listOf("LMSTUDIO_API_KEY") // LM Studio (usually no key needed)
    "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai" -> listOf("ZAI_API_KEY", "Z_AI_API_KEY", "ZHIPUAI_API_KEY")
    "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build" ->
        listOf("NVIDIA_API_KEY", "NVIDIA_NIM_API_KEY", "NGC_API_KEY")
    "typesafe", "typesafe_ai", "typesafe-ai", "jev" -> listOf("TYPESAFE_API_KEY") // TypeSafe Jev (decision model, not chat)
    else -> emptyList()
}

private fun resolveApiKey(provider: String, explicitApiKey: String?): String? {
    if (explicitApiKey?.isNotBlank() == true) return explicitApiKey
    val envNames = envVarNamesForProvider(provider)
    return envNames.firstNotNullOfOrNull { System.getenv(it) }
        ?.takeIf { it.isNotBlank() }
}

private fun providerKeyAliases(provider: String): List<String> = when (provider.lowercase()) {
    "openrouter", "open_router" -> listOf("openrouter", "open_router")
    "openai", "open_ai" -> listOf("openai", "open_ai")
    "google" -> listOf("google")
    "anthropic" -> listOf("anthropic")
    "deepseek" -> listOf("deepseek")
    // Served by openrouter.ai: a key stored under the vendor's name is the vendor's own key and
    // must not be sent there (see envVarNamesForProvider).
    "xai", "x-ai", "qwen", "meta", "meta-llama", "mistral", "mistralai", "perplexity" ->
        listOf("openrouter", "open_router")
    "qwen_direct", "dashscope" -> listOf("qwen_direct", "dashscope")
    "kimi", "moonshot" -> listOf("kimi", "moonshot")
    "minimax" -> listOf("minimax")
    "lmstudio", "lm-studio", "lm_studio" -> listOf("lmstudio", "lm-studio", "lm_studio")
    "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai" -> listOf("zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai")
    "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build" ->
        listOf("nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build")
    "ollama", "local", "olla" -> listOf("ollama", "local", "olla")
    // Must match com.fartech.agents.jev.TYPESAFE_PROVIDER_ALIASES (and the web credential aliases).
    "typesafe", "typesafe_ai", "typesafe-ai", "jev" -> listOf("typesafe", "typesafe_ai", "jev")
    else -> listOf(provider.lowercase())
}.distinct()

private fun providerApiKeyParameterNames(provider: String): List<String> =
    providerKeyAliases(provider).map { "${it.replace('-', '_')}_api_key" }.distinct()

/**
 * Resolves a provider key from configuration ONLY — standalone `<alias>_api_key`
 * parameters first, then [keys] (`llm_provider_keys`) by alias — without the
 * process-environment fallback of [resolveConfiguredApiKey]. Used where the env
 * lookup must be injectable or disabled (TypeSafe Jev credential resolution).
 */
internal fun resolveConfiguredApiKeyFromParams(
    parameters: List<ConfigurationParameter>,
    provider: String,
    keys: Map<String, String>
): String? {
    // Workflow preset overrides and workflow-web commonly persist provider keys as standalone
    // fields like `openrouter_api_key`. Honor those first so preset-based agents authenticate.
    val paramKeyNames = providerApiKeyParameterNames(provider)
    debugLlmConfig {
        "resolveConfiguredApiKey provider=$provider paramKeyNames=$paramKeyNames " +
                "allParamKeys=${parameters.map { it.key }.filter { it.contains("api_key") || it.contains("openrouter") }}"
    }
    paramKeyNames.firstNotNullOfOrNull { keyName ->
        val value = parameters.parameter(keyName, "")
        debugLlmConfig { "  checking param '$keyName' -> ${redactKey(value)}" }
        value.takeIf { it.isNotBlank() }
    }?.let { return it }

    return providerKeyAliases(provider).firstNotNullOfOrNull { alias ->
        keys[alias]?.takeIf { it.isNotBlank() }
    }
}

internal fun resolveConfiguredApiKey(
    parameters: List<ConfigurationParameter>,
    provider: String,
    keys: Map<String, String>
): String? {
    resolveConfiguredApiKeyFromParams(parameters, provider, keys)?.let { return it }
    val envResult = resolveApiKey(provider, null)
    debugLlmConfig { "  fallback resolveApiKey -> ${redactKey(envResult)}" }
    return envResult
}

internal fun resolveConfiguredApiKeyOrThrow(
    parameters: List<ConfigurationParameter>,
    provider: String,
    keys: Map<String, String>
): String {
    return resolveConfiguredApiKey(parameters, provider, keys)
        ?: throw IllegalStateException(buildMissingApiKeyMessage(provider))
}

/**
 * Provider-key lookup for one [createLLMClient] call. With [explicitOnly] (a user-chosen base URL
 * under [WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints]) only keys supplied with the
 * run are used — the host's environment keys must never be sent to a host the user picked — and
 * a missing key is a configuration error instead of an empty `Authorization` header.
 */
private class ApiKeySource(
    private val parameters: List<ConfigurationParameter>,
    private val keys: Map<String, String>,
    private val explicitOnly: Boolean,
) {
    fun resolve(provider: String): String? {
        if (!explicitOnly) return resolveConfiguredApiKey(parameters, provider, keys)
        return resolveConfiguredApiKeyFromParams(parameters, provider, keys)
            ?: if (provider.lowercase() in KEYLESS_LLM_PROVIDERS) null else throw missingExplicitKey(provider)
    }

    fun orThrow(provider: String): String = if (explicitOnly) {
        resolve(provider) ?: throw missingExplicitKey(provider)
    } else {
        resolveConfiguredApiKeyOrThrow(parameters, provider, keys)
    }

    /**
     * The OpenRouter key for a model whose [configProvider] is served by openrouter.ai. Only
     * OpenRouter keys are looked up, whatever the configured provider: resolving under
     * `mistral`, `cohere`, ... would send that vendor's own key (`MISTRAL_API_KEY`,
     * `llm_provider_keys.mistral`, `cohere_api_key`) to a third party.
     */
    fun openRouterOrThrow(configProvider: String): String = try {
        orThrow(OPENROUTER_KEY_PROVIDER)
    } catch (e: IllegalStateException) {
        if (configProvider in providerKeyAliases(OPENROUTER_KEY_PROVIDER)) throw e
        throw IllegalStateException(
            "Provider '$configProvider' is served through OpenRouter, which only accepts an OpenRouter key. ${e.message}",
            e,
        )
    }

    private fun missingExplicitKey(provider: String) = IllegalStateException(
        "Missing API key for provider '$provider' with a custom base_url. This host does not use its own " +
            "environment keys for custom endpoints; pass one of ${providerApiKeyParameterNames(provider).joinToString(" / ")} " +
            "or set llm_provider_keys for: ${providerKeyAliases(provider).joinToString(" / ")}."
    )
}

private const val OPENROUTER_KEY_PROVIDER = "openrouter"

/** Local servers that usually need no key. */
private val KEYLESS_LLM_PROVIDERS = setOf("lmstudio", "lm-studio", "lm_studio", "ollama", "local", "olla")

internal fun buildMissingApiKeyMessage(provider: String): String {
    val envText = envVarNamesForProvider(provider)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" or ")
        ?: "<no default env var>"
    val parameterText = providerApiKeyParameterNames(provider).joinToString(" / ")
    val providerKeyText = providerKeyAliases(provider).joinToString(" / ")

    return buildString {
        append("Missing API key for provider '$provider'. ")
        append("Set environment variable(s): ")
        append(envText)
        append(", or pass one of: ")
        append(parameterText)
        append(", or set llm_provider_keys for: ")
        append(providerKeyText)
        append(".")
    }
}

private fun warnMissingApiKey(provider: String) {
    val hints = envVarNamesForProvider(provider)
    val hintText = if (hints.isNotEmpty()) hints.joinToString(" or ") else "<no default env var>"
    printlnColor(
        AnsiColor.YELLOW,
        "No API key provided for $provider. Set environment variable(s): $hintText, or pass via configuration."
    )
}

private fun debugLlmConfig(message: () -> String) {
    // Route through the logger rather than `println`. When this module runs
    // inside the MCP stdio transport, stdout is the JSON-RPC channel — a
    // stray `println` corrupts the protocol stream and breaks the client.
    // The opt-in env var (`BRAIDRUN_DEBUG_LLM_PARAMS=true`) gates the work
    // but the routing must still respect the surrounding I/O contract.
    if (System.getenv("BRAIDRUN_DEBUG_LLM_PARAMS") == "true") {
        logger.debug { "[LLM Debug] ${message()}" }
    }
}

private fun redactKey(value: String?): String =
    if (value.isNullOrBlank()) "<empty>" else "${value.take(8)}...(${value.length})"

/** Matches an API version path segment, e.g. `v1`, `v2`, `v1beta`, `v1alpha`. */
private val VERSION_SEGMENT = Regex("v\\d+[a-z]*", RegexOption.IGNORE_CASE)

/** The last path segment of [baseUrl] after the host (`""` for a bare host). */
private fun baseUrlTrailingPathSegment(baseUrl: String): String =
    baseUrl.substringAfter("://", baseUrl)
        .substringAfter('/', "")
        .trim('/')
        .substringAfterLast('/')

/**
 * Resolves the endpoint path Koog should join onto [baseUrl] for a client
 * whose stock default path is [koogDefaultPath].
 *
 * Koog 1.0 joins endpoint paths RELATIVE to the client base URL: Ktor's
 * `DefaultRequest` normalizes the base to a trailing `/` and concatenates a
 * relative request path onto it (a leading-slash path would instead REPLACE
 * the base path). Koog's stock default paths carry the API version segment
 * (`v1/chat/completions`, `v1/messages`, `v1beta/models`) on the assumption of
 * a bare-host base URL (`https://api.openai.com`). Our configured and default
 * base URLs already include the version segment (`https://api.openai.com/v1`,
 * `https://api.anthropic.com/v1`, `https://api.mistral.ai/v1`,
 * `https://dashscope.aliyuncs.com/compatible-mode/v1`, ...), so keeping the
 * version-prefixed default double-joins it — `.../v1/v1/chat/completions` →
 * HTTP 404 from every provider. The OpenRouter branch sidesteps this with an
 * explicit leading-slash override; the RAG embedder sidesteps it with
 * `RAGTools.resolveEmbeddingsPath`. This applies the same rule to the
 * chat/messages/generateContent endpoints used during prompt execution.
 *
 * Rule: drop the leading version component from [koogDefaultPath] only when the
 * base URL's trailing path segment is ITSELF a version segment. This refines
 * the embeddings heuristic (which keys off "any path present") so that a
 * version-less proxy base (`https://proxy.example.com/openai`) keeps the
 * version prefix instead of losing it, and a path whose first segment is not a
 * version (DeepSeek's `chat/completions`) is never altered.
 */
internal fun resolveVersionedEndpointPath(baseUrl: String, koogDefaultPath: String): String {
    val baseIsVersioned = VERSION_SEGMENT.matches(baseUrlTrailingPathSegment(baseUrl))
    val pathHasVersionPrefix = koogDefaultPath.contains('/') &&
        VERSION_SEGMENT.matches(koogDefaultPath.substringBefore('/'))
    return if (baseIsVersioned && pathHasVersionPrefix) {
        koogDefaultPath.substringAfter('/')
    } else {
        koogDefaultPath
    }
}

/**
 * Creates an LLM client based on the provider configuration.
 * Returns a pair of (LLMProvider, LLMClient).
 *
 * Koog 1.0.0 decoupled LLM clients from Ktor: the `baseClient: HttpClient` constructor
 * parameter is gone and each client builds its own `KoogHttpClient` from a
 * `KoogHttpClient.Factory`, discovered on the classpath via ServiceLoader
 * (`http-client-ktor`). The [httpClient] parameter is retained for source compatibility
 * with existing callers and is ignored.
 */
fun createLLMClient(
    parameters: List<ConfigurationParameter>,
    @Suppress("UNUSED_PARAMETER") httpClient: HttpClient,
    modelConfig: LLModelConfig,
    keys: Map<String, String>
): Pair<LLMProvider, LLMClient> =
    createLLMClient(parameters, modelConfig, keys, HttpClientFactoryResolver.resolve())

/**
 * [createLLMClient] with an explicit Koog HTTP client factory — the seam a host (or a test)
 * uses to supply its own transport.
 *
 * Every client is built against its effective base URL (the configured one, else the
 * provider default), which is checked by [LlmEndpointPolicy] before the factory sees it, and
 * is wrapped in [ModelParamsSanitizingLLMClient] so the shared prompt params are fitted to
 * the model each request is actually sent to.
 */
fun createLLMClient(
    parameters: List<ConfigurationParameter>,
    modelConfig: LLModelConfig,
    keys: Map<String, String>,
    httpClientFactory: KoogHttpClient.Factory,
): Pair<LLMProvider, LLMClient> {
    rejectTypeSafeChatProvider(modelConfig.provider)
    val model = determineLLMModel(modelConfig)
    debugLlmConfig {
        "createLLMClient provider=${model.provider.id} model=${model.id} configProvider=${modelConfig.provider} " +
                "keys=${keys.mapValues { redactKey(it.value) }}"
    }
    val configProvider = modelConfig.provider.lowercase()
    // A blank base URL (an empty form field) means "use the provider default".
    val defaultBaseUrl = defaultLlmBaseUrl(model.provider, configProvider)
    val baseUrl = modelConfig.baseUrl?.takeIf { it.isNotBlank() } ?: defaultBaseUrl
    val customEndpoint = LlmEndpointPolicy.isCustomEndpoint(modelConfig.baseUrl, defaultBaseUrl)
    LlmEndpointPolicy.check(baseUrl, configProvider, providerDefault = !customEndpoint)
    val apiKeys = ApiKeySource(parameters, keys, explicitOnly = customEndpoint &&
        WorkflowHostPolicy.requiresExplicitKeysForCustomLlmEndpoints)

    val client: LLMClient = when (model.provider) {
        LLMProvider.Ollama -> OllamaClient(
            httpClientFactory = httpClientFactory,
            baseUrl = baseUrl,
        )

        LLMProvider.OpenAI,
        QWEN_DIRECT_LLM_PROVIDER,
        KIMI_LLM_PROVIDER,
        LLMProvider.MiniMax,
        LMSTUDIO_LLM_PROVIDER,
        ZAI_LLM_PROVIDER,
        NVIDIA_LLM_PROVIDER -> {
            val providerKey = when (configProvider) {
                "kimi", "moonshot" -> "kimi"
                "minimax" -> "minimax"
                "qwen_direct", "dashscope" -> "qwen_direct"
                "lmstudio", "lm-studio", "lm_studio" -> "lmstudio"
                "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai" -> "zai"
                "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build" -> "nvidia"
                else -> "openai"
            }
            val apiKey = if (providerKey == "lmstudio") {
                apiKeys.resolve(providerKey)
                    .also { if (it == null) warnMissingApiKey(providerKey) } ?: ""
            } else {
                apiKeys.orThrow(providerKey)
            }
            OpenAILLMClient(
                apiKey = apiKey,
                settings = OpenAIClientSettings(
                    baseUrl = baseUrl,
                    // Every default/configured base URL above carries `/v1`, so the
                    // stock `v1/chat/completions` would resolve to
                    // `.../v1/v1/chat/completions` → 404. See resolveVersionedEndpointPath.
                    chatCompletionsPath = resolveVersionedEndpointPath(baseUrl, "v1/chat/completions")
                ),
                httpClientFactory = httpClientFactory,
            )
        }

        LLMProvider.Google -> GoogleLLMClient(
            apiKey = apiKeys.resolve("google")
                .also { if (it == null) warnMissingApiKey("google") } ?: "",
            settings = GoogleClientSettings(
                baseUrl = baseUrl,
                // The default base URL is a bare host, so `v1beta/models` is
                // correct as-is; a user-supplied `.../v1beta` base would
                // otherwise double-join. See resolveVersionedEndpointPath.
                defaultPath = resolveVersionedEndpointPath(baseUrl, "v1beta/models")
            ),
            httpClientFactory = httpClientFactory,
        )

        LLMProvider.Anthropic -> AnthropicLLMClient(
            apiKey = apiKeys.resolve("anthropic")
                .also { if (it == null) warnMissingApiKey("anthropic") } ?: "",
            settings = AnthropicClientSettings(
                // Koog's default map only knows Koog's own LLModel objects; send our
                // (normalized) model id instead. See AnthropicModelIdPassThrough.
                modelVersionsMap = AnthropicModelIdPassThrough,
                baseUrl = baseUrl,
                // The default base URL carries `/v1`, so the stock `v1/messages`
                // default would resolve to `.../v1/v1/messages` → 404.
                // See resolveVersionedEndpointPath.
                messagesPath = resolveVersionedEndpointPath(baseUrl, "v1/messages")
            ),
            httpClientFactory = httpClientFactory,
        )

        // Koog 1.0.0's stock DeepSeekLLMClient natively performs the
        // reasoning+content+tool_calls collapse our former
        // `DeepSeekThinkingModeLLMClient` wrapper hand-rolled
        // (`prepareMessagesForDeepSeek` lives inside the stock client now).
        LLMProvider.DeepSeek -> DeepSeekLLMClient(
            apiKey = apiKeys.resolve("deepseek")
                .also { if (it == null) warnMissingApiKey("deepseek") } ?: "",
            settings = DeepSeekClientSettings(
                baseUrl = baseUrl,
                // DeepSeek's stock default `chat/completions` has no version
                // prefix, so it never double-joins; routed through the resolver
                // for uniformity (it returns the path unchanged either way).
                chatCompletionsPath = resolveVersionedEndpointPath(baseUrl, "chat/completions")
            ),
            httpClientFactory = httpClientFactory,
        )

        // Koog 1.1.1+ sends Tool.Call.args verbatim (upstream fix #2095), so the former
        // ToolArgsFixingKoogHttpClient double-encoding shim is gone; see
        // ToolCallArgsUpstreamRegressionTest for the pin.
        LLMProvider.OpenRouter -> OpenRouterLLMClient(
            apiKey = apiKeys.openRouterOrThrow(configProvider),
            settings = OpenRouterClientSettings(
                baseUrl = baseUrl,
                chatCompletionsPath = "/api/v1/chat/completions"
            ),
            httpClientFactory = httpClientFactory,
        )

        LLMProvider.MistralAI -> MistralAILLMClient(
            apiKey = apiKeys.resolve("mistral")
                .also { if (it == null) warnMissingApiKey("mistral") } ?: "",
            settings = MistralAIClientSettings(
                baseUrl = baseUrl,
                // The default base URL carries `/v1`, so the stock
                // `v1/chat/completions` would resolve to `.../v1/v1/chat/completions`
                // → 404. See resolveVersionedEndpointPath.
                chatCompletionsPath = resolveVersionedEndpointPath(baseUrl, "v1/chat/completions")
            ),
            httpClientFactory = httpClientFactory,
        )

        else -> OpenAILLMClient(
            apiKey = apiKeys.resolve(modelConfig.provider).also {
                if (it == null) warnMissingApiKey(
                    modelConfig.provider
                )
            } ?: "",
            settings = OpenAIClientSettings(
                baseUrl = baseUrl,
                // OpenAI-compatible fallback: the default base carries `/v1`,
                // so the stock `v1/chat/completions` would double-join.
                // See resolveVersionedEndpointPath.
                chatCompletionsPath = resolveVersionedEndpointPath(baseUrl, "v1/chat/completions")
            ),
            httpClientFactory = httpClientFactory,
        )
    }
    return model.provider to ModelParamsSanitizingLLMClient(client)
}

/** The base URL a client for [provider] uses when `llm_config` sets none. */
private fun defaultLlmBaseUrl(provider: LLMProvider, configProvider: String): String = when (provider) {
    LLMProvider.Ollama -> OllamaClient.DEFAULT_BASE_URL
    LLMProvider.Google -> "https://generativelanguage.googleapis.com"
    LLMProvider.Anthropic -> "https://api.anthropic.com/v1"
    LLMProvider.DeepSeek -> "https://api.deepseek.com/v1"
    LLMProvider.OpenRouter -> "https://openrouter.ai"
    LLMProvider.MistralAI -> "https://api.mistral.ai/v1"
    // OpenAI and the OpenAI-compatible providers, plus the OpenAI-client fallback.
    else -> when (configProvider) {
        "kimi", "moonshot" -> "https://api.moonshot.cn/v1"
        "minimax" -> "https://api.minimax.chat/v1"
        "qwen_direct", "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
        "lmstudio", "lm-studio", "lm_studio" -> "http://localhost:1234/v1"
        "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai" -> "https://api.z.ai/api/coding/paas/v4"
        "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build" -> "https://integrate.api.nvidia.com/v1"
        else -> "https://api.openai.com/v1"
    }
}

/**
 * Initializes multiple LLM clients based on configuration parameters.
 * Returns a map of clients and fallback settings for multi-LLM prompt execution.
 *
 * This function also registers any custom models defined in the configuration.
 */
fun determineLLMClients(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>
): Pair<Map<LLMProvider, LLMClient>, MultiLLMPromptExecutor.FallbackPromptExecutorSettings> {
    val llmConfig = parameters.getLLMGroupConfig()

    // Register custom models from configuration
    if (llmConfig.customModels != null) {
        printlnColor(AnsiColor.CYAN, "Registering ${llmConfig.customModels.size} custom model(s) from configuration...")
        registerCustomModels(llmConfig.customModels)
    }

    val keys = parameters.parameter("llm_provider_keys", mapOf<String, String>())
    debugLlmConfig {
        "determineLLMClients models=${llmConfig.models.map { "${it.provider}:${it.model}" }} " +
                "fallback=${llmConfig.fallback.provider}:${llmConfig.fallback.model} " +
                "decodedKeys=${keys.mapValues { redactKey(it.value) }}"
    }
    val (fallbackProvider, fallbackClient) = createLLMClient(parameters, httpAccess.client, llmConfig.fallback, keys)
    val primaryClientPairs = llmConfig.models.map { createLLMClient(parameters, httpAccess.client, it, keys) }
    // `associate` keeps only the LAST entry per provider key. Two model configs that
    // map to the same LLMProvider enum (two openrouter entries, or kimi + minimax —
    // both LLMProvider.OpenAI with different base URLs) silently collapse to one
    // client; requests for the overwritten model land at the surviving client's
    // baseUrl/key. Warn loudly so the misconfiguration is diagnosable from logs.
    primaryClientPairs.groupBy { it.first }
        .filterValues { it.size > 1 }
        .forEach { (provider, entries) ->
            logger.warn {
                "determineLLMClients: ${entries.size} model configs map to provider '${provider.id}' — " +
                    "only the last one's client (baseUrl/key) survives. Models sharing a provider enum " +
                    "(e.g. kimi/minimax/dashscope → OpenAI-compatible) cannot have distinct base URLs in one agent."
            }
        }
    val primaryClients = primaryClientPairs.toMap()
    // Koog's MultiLLMPromptExecutor (since 0.6.4) requires the
    // FallbackPromptExecutorSettings.fallbackProvider to also be present in the
    // llmClients map; otherwise its constructor throws
    // "Fallback client not found for provider: <X>" before any request runs.
    // When the workflow declares only a primary provider (e.g. deepseek) and
    // inherits the default OpenRouter fallback, register the fallback's client
    // too. Explicit `models` entries win so any user-supplied baseUrl/settings
    // are never overridden.
    val clients = if (primaryClients.containsKey(fallbackProvider)) {
        primaryClients
    } else {
        primaryClients + (fallbackProvider to fallbackClient)
    }
    debugLlmConfig {
        "clients=${clients.keys.map { "${it.id}:${it.display}" }} fallbackProvider=${fallbackProvider.id}:${fallbackProvider.display}"
    }
    return clients to MultiLLMPromptExecutor.FallbackPromptExecutorSettings(
        fallbackProvider = fallbackProvider,
        fallbackModel = determineLLMModel(llmConfig.fallback)
    )
}

/**
 * Materialize the per-tier `(clients, fallback)` pairs for
 * [LLModelGroupConfig.cascadeFallbacks]. Returns an empty list when cascade
 * isn't configured — callers can forward the result directly to
 * [createPromptExecutor]'s `extraCascadeTiers` parameter and the factory
 * skips the wrapper entirely in that case.
 *
 * Each cascade entry gets its own single-provider client map (built from
 * the same keys material as the primary) so the retry / fallback policy
 * inside each tier is independent.
 */
fun determineCascadeFallbackClients(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
): List<Pair<Map<LLMProvider, LLMClient>, MultiLLMPromptExecutor.FallbackPromptExecutorSettings>> {
    val llmConfig = parameters.getLLMGroupConfig()
    if (llmConfig.cascadeFallbacks.isEmpty()) return emptyList()

    val keys = parameters.parameter("llm_provider_keys", mapOf<String, String>())
    return llmConfig.cascadeFallbacks.map { tierConfig ->
        val (provider, client) = createLLMClient(parameters, httpAccess.client, tierConfig, keys)
        mapOf(provider to client) to MultiLLMPromptExecutor.FallbackPromptExecutorSettings(
            fallbackProvider = provider,
            fallbackModel = determineLLMModel(tierConfig),
        )
    }
}
