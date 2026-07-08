package com.fartech.agents.commons

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

// ============================================================================
// YAML Data Model
// ============================================================================

/**
 * YAML 文件的顶层结构，每个文件对应一个 provider。
 */
@Serializable
data class ProviderModelsYaml(
    /** LLMProvider 枚举名（小写），如 openrouter, openai, google */
    val provider: String,
    /** provider 别名列表，如 [open_router] */
    val aliases: List<String> = emptyList(),
    /** 需要路由到 OpenRouter 的第三方 provider 名（如 amazon, cohere 等） */
    @SerialName("openrouter_routed")
    val openRouterRouted: List<String> = emptyList(),
    /** 模型定义 map: key = lookup name, value = 模型属性 */
    val models: Map<String, ModelEntryYaml>
)

/**
 * 单个模型条目的 YAML 表示。
 */
@Serializable
data class ModelEntryYaml(
    /** 实际发送给 API 的 model ID（如 "x-ai/grok-4.20", "gpt-4o"） */
    val id: String,
    /** 能力集预设名或列表。预设: "standard", "multimodal", "basic"；
     *  也可用逗号分隔的能力名或 YAML list */
    val capabilities: String = "standard",
    /** 上下文窗口长度（token 数） */
    @SerialName("context_length")
    val contextLength: Long = 32768,
    /** 最大输出 token 数（可选） */
    @SerialName("max_output_tokens")
    val maxOutputTokens: Long? = null
)

/**
 * 多媒体模型条目（图像/音频生成模型）。
 */
@Serializable
data class MultimediaModelEntryYaml(
    val description: String = "",
    val default: Boolean = false
)

/**
 * multimedia.yaml 的顶层结构。
 */
@Serializable
data class MultimediaModelsYaml(
    @SerialName("image_models")
    val imageModels: Map<String, MultimediaModelEntryYaml> = emptyMap(),
    @SerialName("audio_models")
    val audioModels: Map<String, MultimediaModelEntryYaml> = emptyMap()
)

// ============================================================================
// Model Registry
// ============================================================================

/**
 * 从 YAML 配置文件加载模型定义的全局注册表。
 *
 * 加载顺序：
 * 1. classpath resources/models/ 目录下的 YAML 文件（内置模型）
 * 2. 工作目录 ./models/ 目录下的 YAML 文件（用户自定义，可覆盖内置）
 *
 * 使用方式：通过 [getProviderModels] 获取指定 provider 的所有模型，
 * 或通过 [getAllProviderModels] 获取完整的 provider → models 映射。
 */
object ModelRegistry {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    // Snapshot semantics: readers atomically observe one fully-populated snapshot or
    // another. Reloads build a fresh snapshot off to the side then publish via volatile
    // assignment, so readers never see a half-cleared registry. This replaces the prior
    // pattern of mutating shared mutable maps under @Synchronized while readers ran lock-free.
    private data class Snapshot(
        val providerModels: Map<String, Map<String, LLModel>>,
        val aliasToProvider: Map<String, String>,
        val routedProviders: Map<String, String>,
        val imageModels: Map<String, MultimediaModelEntryYaml>,
        val audioModels: Map<String, MultimediaModelEntryYaml>,
    ) {
        companion object {
            val EMPTY = Snapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    // Single source of truth for the whole registry. Published with `@Volatile` so
    // subsequent readers see the most recently set instance; every read uses a local
    // copy of `snapshot` to avoid double-reads observing different values.
    //
    // There was previously a separate `@Volatile var initialized: Boolean` flag that
    // was written but never read — it was dead code whose only effect was to confuse
    // reasoning about visibility ordering. Removed.
    @Volatile
    private var snapshot: Snapshot = Snapshot.EMPTY

    init {
        reload()
    }

    /**
     * 重新加载所有 YAML 模型定义。
     *
     * Builds a fresh snapshot in a local mutable buffer, then publishes it atomically
     * via [snapshot] (volatile). Concurrent readers always see either the previous or
     * the new snapshot — never an in-progress partial state.
     */
    @Synchronized
    fun reload() {
        val newProviderModels = mutableMapOf<String, MutableMap<String, LLModel>>()
        val newAliasToProvider = mutableMapOf<String, String>()
        val newRoutedProviders = mutableMapOf<String, String>()
        val newImageModels = mutableMapOf<String, MultimediaModelEntryYaml>()
        val newAudioModels = mutableMapOf<String, MultimediaModelEntryYaml>()

        // 1. 从 classpath 加载内置模型
        loadFromClasspath(newProviderModels, newAliasToProvider, newRoutedProviders, newImageModels, newAudioModels)

        // 2. 从工作目录加载用户自定义模型（覆盖内置）
        loadFromDirectory(
            File("./models"),
            newProviderModels, newAliasToProvider, newRoutedProviders, newImageModels, newAudioModels
        )

        // Atomic publication: the volatile write is visible to all subsequent reads.
        snapshot = Snapshot(
            providerModels = newProviderModels.mapValues { (_, v) -> v.toMap() },
            aliasToProvider = newAliasToProvider.toMap(),
            routedProviders = newRoutedProviders.toMap(),
            imageModels = newImageModels.toMap(),
            audioModels = newAudioModels.toMap(),
        )
        logger.info {
            "ModelRegistry loaded: ${snapshot.providerModels.size} providers, " +
                "${snapshot.providerModels.values.sumOf { it.size }} total chat models, " +
                "${snapshot.imageModels.size} image models, ${snapshot.audioModels.size} audio models, " +
                "${snapshot.aliasToProvider.size} aliases"
        }
    }

    /**
     * 获取指定 provider 的所有模型（支持别名解析）。
     */
    fun getProviderModels(provider: String): Map<String, LLModel>? {
        val s = snapshot
        val key = resolveProvider(s, provider)
        return s.providerModels[key]
    }

    /**
     * 获取完整的 provider → models 映射（包含所有别名展开）。
     * 返回的 map 中每个 alias 都指向其 canonical provider 的模型 map。
     */
    fun getAllProviderModels(): Map<String, Map<String, LLModel>> {
        val s = snapshot
        val result = mutableMapOf<String, Map<String, LLModel>>()
        // 先加入所有 canonical provider
        for ((key, models) in s.providerModels) {
            result[key] = models
        }
        // 再加入所有别名
        for ((alias, canonicalKey) in s.aliasToProvider) {
            s.providerModels[canonicalKey]?.let { result[alias] = it }
        }
        // 加入路由映射
        for ((routed, targetKey) in s.routedProviders) {
            s.providerModels[targetKey]?.let { result[routed] = it }
        }
        return result
    }

    /**
     * 查找单个模型。
     */
    fun getModel(provider: String, model: String): LLModel? {
        return getProviderModels(provider)?.get(model.lowercase())
    }

    // ========================================================================
    // Multimedia Models
    // ========================================================================

    /** 获取所有可用的图像生成模型 ID 列表。 */
    fun getImageModelIds(): List<String> = snapshot.imageModels.keys.toList()

    /** 获取所有可用的音频生成模型 ID 列表。 */
    fun getAudioModelIds(): List<String> = snapshot.audioModels.keys.toList()

    /** 获取默认图像模型 ID。 */
    fun getDefaultImageModel(): String? {
        val s = snapshot.imageModels
        return s.entries.firstOrNull { it.value.default }?.key ?: s.keys.firstOrNull()
    }

    /** 获取默认音频模型 ID。 */
    fun getDefaultAudioModel(): String? {
        val s = snapshot.audioModels
        return s.entries.firstOrNull { it.value.default }?.key ?: s.keys.firstOrNull()
    }

    /** 检查给定模型 ID 是否是已知的图像生成模型。 */
    fun isKnownImageModel(modelId: String): Boolean = snapshot.imageModels.containsKey(modelId)

    /** 检查给定模型 ID 是否是已知的音频生成模型。 */
    fun isKnownAudioModel(modelId: String): Boolean = snapshot.audioModels.containsKey(modelId)

    /** 获取图像模型描述。 */
    fun getImageModelDescription(modelId: String): String? = snapshot.imageModels[modelId]?.description

    /** 获取音频模型描述。 */
    fun getAudioModelDescription(modelId: String): String? = snapshot.audioModels[modelId]?.description

    // ========================================================================
    // Internal
    // ========================================================================

    private fun resolveProvider(s: Snapshot, provider: String): String {
        val key = provider.lowercase()
        return s.aliasToProvider[key] ?: s.routedProviders[key] ?: key
    }

    private fun loadFromClasspath(
        providerModels: MutableMap<String, MutableMap<String, LLModel>>,
        aliasToProvider: MutableMap<String, String>,
        routedProviders: MutableMap<String, String>,
        imageModels: MutableMap<String, MultimediaModelEntryYaml>,
        audioModels: MutableMap<String, MultimediaModelEntryYaml>,
    ) {
        val classLoader = Thread.currentThread().contextClassLoader ?: ModelRegistry::class.java.classLoader
        val knownFiles = listOf(
            "openrouter.yaml", "openai.yaml", "google.yaml", "anthropic.yaml",
            "deepseek.yaml", "xai.yaml", "qwen.yaml", "qwen-direct.yaml",
            "kimi.yaml", "minimax.yaml", "meta.yaml", "mistral.yaml",
            "perplexity.yaml", "ollama.yaml", "lmstudio.yaml", "zai.yaml",
            "nvidia.yaml"
        )
        for (fileName in knownFiles) {
            val url = classLoader.getResource("models/$fileName") ?: continue
            try {
                loadYamlContent(url.readText(), providerModels, aliasToProvider, routedProviders)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load model file from classpath: models/$fileName" }
            }
        }
        // 加载多媒体模型
        classLoader.getResource("models/multimedia.yaml")?.let { url ->
            try { loadMultimediaYaml(url.readText(), imageModels, audioModels) }
            catch (e: Exception) { logger.warn(e) { "Failed to load multimedia models from classpath" } }
        }
    }

    private fun loadFromDirectory(
        dir: File,
        providerModels: MutableMap<String, MutableMap<String, LLModel>>,
        aliasToProvider: MutableMap<String, String>,
        routedProviders: MutableMap<String, String>,
        imageModels: MutableMap<String, MultimediaModelEntryYaml>,
        audioModels: MutableMap<String, MultimediaModelEntryYaml>,
    ) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles { f -> f.extension == "yaml" || f.extension == "yml" }
            ?.sorted()
            ?.forEach { file ->
                try {
                    if (file.nameWithoutExtension == "multimedia") {
                        loadMultimediaYaml(file.readText(), imageModels, audioModels)
                    } else {
                        loadYamlContent(file.readText(), providerModels, aliasToProvider, routedProviders)
                    }
                    logger.debug { "Loaded custom models from ${file.absolutePath}" }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to load model file: ${file.absolutePath}" }
                }
            }
    }

    private fun loadYamlContent(
        content: String,
        providerModels: MutableMap<String, MutableMap<String, LLModel>>,
        aliasToProvider: MutableMap<String, String>,
        routedProviders: MutableMap<String, String>,
    ) {
        val providerDef = yaml.decodeFromString(ProviderModelsYaml.serializer(), content)
        val canonicalKey = providerDef.provider.lowercase()
        val llmProvider = mapProviderStringToEnum(canonicalKey)

        // 注册 aliases
        for (alias in providerDef.aliases) {
            aliasToProvider[alias.lowercase()] = canonicalKey
        }

        // 注册路由 providers
        for (routed in providerDef.openRouterRouted) {
            routedProviders[routed.lowercase()] = canonicalKey
        }

        // 解析模型
        val models = providerModels.getOrPut(canonicalKey) { mutableMapOf() }
        for ((key, entry) in providerDef.models) {
            val capabilities = parseCapabilitiesString(entry.capabilities)
            models[key.lowercase()] = LLModel(
                provider = llmProvider,
                id = entry.id,
                capabilities = capabilities,
                contextLength = entry.contextLength,
                maxOutputTokens = entry.maxOutputTokens
            )
        }
    }

    private fun loadMultimediaYaml(
        content: String,
        imageModels: MutableMap<String, MultimediaModelEntryYaml>,
        audioModels: MutableMap<String, MultimediaModelEntryYaml>,
    ) {
        val def = yaml.decodeFromString(MultimediaModelsYaml.serializer(), content)
        imageModels.putAll(def.imageModels)
        audioModels.putAll(def.audioModels)
    }

    private fun parseCapabilitiesString(caps: String): List<LLMCapability> {
        return when (caps.trim().lowercase()) {
            "standard" -> STANDARD_CAPABILITIES
            "multimodal" -> MULTIMODAL_CAPABILITIES
            "basic" -> BASIC_CAPABILITIES
            else -> {
                // 支持逗号分隔的能力列表
                val parts = caps.split(",").map { it.trim() }
                parseCapabilities(parts)
            }
        }
    }

    private fun mapProviderStringToEnum(provider: String): LLMProvider {
        return when (provider) {
            "openrouter", "open_router" -> LLMProvider.OpenRouter
            "openai", "open_ai" -> LLMProvider.OpenAI
            "google" -> LLMProvider.Google
            "anthropic" -> LLMProvider.Anthropic
            "deepseek" -> LLMProvider.DeepSeek
            "ollama", "local" -> LLMProvider.Ollama
            // XAI, Qwen, Kimi, MiniMax, Meta, Mistral, Perplexity 等都通过 OpenRouter 或 OpenAI-compatible
            "xai", "x-ai" -> LLMProvider.OpenRouter
            "qwen" -> LLMProvider.OpenRouter
            "qwen_direct", "qwen-direct", "dashscope" -> QWEN_DIRECT_LLM_PROVIDER
            "kimi", "moonshot" -> KIMI_LLM_PROVIDER
            "minimax" -> LLMProvider.MiniMax
            "lmstudio", "lm-studio", "lm_studio" -> LMSTUDIO_LLM_PROVIDER
            "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai" -> ZAI_LLM_PROVIDER
            "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build" -> NVIDIA_LLM_PROVIDER
            "meta", "meta-llama" -> LLMProvider.OpenRouter
            "mistral", "mistralai" -> LLMProvider.OpenRouter
            "perplexity" -> LLMProvider.OpenRouter
            else -> {
                // Phase 9 (2026-05): pre-Phase-9 the silent fallback to OpenRouter
                // turned a typo (`deepseek_` instead of `deepseek`) into "every
                // request to that model now goes to OpenRouter using the OpenRouter
                // API key" — no operator visibility. Now we log so the misroute
                // surfaces at agent boot. Behaviour preserved (OpenRouter remains
                // the catch-all because legitimate third-party providers like
                // amazon / cohere / together genuinely route through it), but the
                // WARN makes typos diagnosable from a single grep.
                logger.warn {
                    "Unknown LLM provider '$provider' — defaulting to OpenRouter route. " +
                        "If this is a typo, fix the YAML; if it's a new provider, add an explicit case here."
                }
                LLMProvider.OpenRouter
            }
        }
    }
}
