package com.fartech.agents.workflow

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Agent 预设定义
 *
 * 包含创建 agent 所需的完整默认配置。
 * Workflow 中的 agent 可以引用预设并覆盖部分参数。
 */
@Serializable
data class AgentPreset(
    /** 预设唯一标识符 */
    @SerialName("id") val id: String,
    /** 预设显示名称 */
    @SerialName("display_name") val displayName: String,
    /** 预设描述 */
    @SerialName("description") val description: String,
    /** 分类（如 general, coding, data, creative） */
    @SerialName("category") val category: String = "general",
    /** 图标（用于 UI 显示） */
    @SerialName("icon") val icon: String = "🤖",
    /** 完整的 agent 配置参数（key-value 形式，与 ConfigurationParameter 兼容） */
    @SerialName("parameters")
    @Serializable(with = YamlJsonElementMapSerializer::class)
    val parameters: Map<String, JsonElement> = emptyMap(),
    /** 预设是否为内置（不可删除） */
    @SerialName("builtin") val builtin: Boolean = false
)

/**
 * Agent 预设注册中心
 *
 * 管理所有可用的 agent 预设模板。支持：
 * - 内置预设（从 classpath 资源文件加载，不可删除）
 * - 外部预设（从文件系统目录加载，不可删除）
 * - 自定义预设（用户通过 API 创建）
 * - 预设与覆盖参数的合并
 *
 * 内置预设从 classpath 的 agent-presets/ 目录下的 YAML 文件加载。
 * 添加新的内置预设只需在该目录下添加新的 YAML 文件，无需修改代码。
 *
 * 外部预设从文件系统指定目录加载，允许在不修改应用程序的情况下扩展预设。
 */
object AgentPresetRegistry {

    private val presets = java.util.concurrent.ConcurrentHashMap<String, AgentPreset>()

    /** classpath 中内置预设的资源目录 */
    private const val BUILTIN_PRESETS_RESOURCE_DIR = "agent-presets"

    /** 内置预设文件名列表（classpath 不支持目录遍历，需显式列出） */
    private val builtinPresetFiles = listOf(
        "universal.yaml",
        "universal_reasoning.yaml",
        "coder.yaml",
        "researcher.yaml",
        "writer.yaml",
        "data_analyst.yaml",
        "asa.yaml",
        "pdf_processor.yaml",
        "lightweight.yaml",
        "chat.yaml",
        "office_document.yaml",
        "word_document.yaml",
        "excel_workbook.yaml",
        "powerpoint_presentation.yaml",
        "multimedia_creator.yaml",
        "devops.yaml",
        "communication.yaml",
        "web_scraper.yaml",
        "computer_operator.yaml"
    )

    /** 用于 YAML 解析的配置（宽松模式） */
    private val yamlParser = Yaml(
        configuration = YamlConfiguration(
            strictMode = false
        )
    )

    init {
        loadBuiltinPresets()
    }

    /**
     * 注册一个预设
     */
    fun register(preset: AgentPreset) {
        presets[preset.id] = preset
        logger.debug { "Registered agent preset: ${preset.id} (${preset.displayName})" }
    }

    /**
     * 获取指定预设
     */
    fun get(presetId: String): AgentPreset? = presets[presetId]

    /**
     * 获取所有已注册的预设
     */
    fun getAll(): List<AgentPreset> = presets.values.toList()

    /**
     * 按分类获取预设
     */
    fun getByCategory(category: String): List<AgentPreset> =
        presets.values.filter { it.category == category }

    /**
     * 获取所有分类
     */
    fun getCategories(): List<String> = presets.values.map { it.category }.distinct().sorted()

    /**
     * 删除自定义预设（内置预设不可删除）
     */
    fun remove(presetId: String): Boolean {
        val preset = presets[presetId] ?: return false
        if (preset.builtin) {
            logger.warn { "Cannot remove builtin preset: $presetId" }
            return false
        }
        presets.remove(presetId)
        return true
    }

    /**
     * 检查预设是否存在
     */
    fun exists(presetId: String): Boolean = presets.containsKey(presetId)

    /**
     * 将预设参数与覆盖参数合并，生成最终参数映射。
     *
     * 合并规则：
     * - 覆盖参数中的值优先于预设默认值
     * - 嵌套对象（JsonObject）执行深度合并
     * - 数组（JsonArray）整体替换（不合并元素）
     *
     * @param presetId 预设标识符
     * @param overrides 覆盖参数
     * @return 合并后的参数映射，如果预设不存在则仅返回覆盖参数
     */
    fun resolveParameters(
        presetId: String,
        overrides: Map<String, JsonElement> = emptyMap()
    ): Map<String, JsonElement> {
        val preset = presets[presetId]
        if (preset == null) {
            logger.warn { "Preset '$presetId' not found, using overrides only" }
            return overrides
        }
        return if (overrides.isEmpty()) {
            preset.parameters
        } else {
            deepMerge(preset.parameters, overrides)
        }
    }

    /**
     * 深度合并两个 Map<String, JsonElement>。
     * 对于嵌套的 JsonObject 递归合并，其他类型（包括 JsonArray）使用覆盖值替换。
     */
    fun deepMerge(
        base: Map<String, JsonElement>,
        overrides: Map<String, JsonElement>
    ): Map<String, JsonElement> {
        val result = base.toMutableMap()
        for ((key, overrideValue) in overrides) {
            val baseValue = result[key]
            result[key] = if (baseValue is JsonObject && overrideValue is JsonObject) {
                // 深度合并嵌套对象
                JsonObject(deepMerge(baseValue.toMap(), overrideValue.toMap()))
            } else {
                overrideValue
            }
        }
        return result
    }

    /**
     * 从 classpath 资源目录加载所有内置预设 YAML 文件。
     */
    private fun loadBuiltinPresets() {
        var loadedCount = 0
        for (fileName in builtinPresetFiles) {
            try {
                val resourcePath = "$BUILTIN_PRESETS_RESOURCE_DIR/$fileName"
                val yamlContent = this::class.java.classLoader
                    ?.getResourceAsStream(resourcePath)
                    ?.use { stream -> stream.bufferedReader().readText() }

                if (yamlContent == null) {
                    logger.warn { "Builtin preset resource not found: $resourcePath" }
                    continue
                }

                val preset = parsePresetYaml(yamlContent)
                register(preset)
                loadedCount++
            } catch (e: Exception) {
                logger.error(e) { "Failed to load builtin preset from: $fileName" }
            }
        }
        logger.info { "Loaded $loadedCount builtin agent presets from resources" }
    }

    /**
     * 从外部文件系统目录加载预设 YAML 文件。
     *
     * 目录中的每个 .yaml/.yml 文件将被解析为一个预设。
     * 如果预设 ID 与已有预设冲突，外部预设将覆盖已有预设。
     *
     * @param directory 包含预设 YAML 文件的目录
     * @return 成功加载的预设数量
     */
    fun loadPresetsFromDirectory(directory: File): Int {
        if (!directory.exists() || !directory.isDirectory) {
            logger.warn { "Preset directory does not exist or is not a directory: ${directory.absolutePath}" }
            return 0
        }

        var loadedCount = 0
        val yamlFiles = directory.listFiles { file ->
            file.isFile && (file.extension == "yaml" || file.extension == "yml")
        } ?: emptyArray()

        for (file in yamlFiles.sortedBy { it.name }) {
            try {
                val yamlContent = file.readText()
                val preset = parsePresetYaml(yamlContent)
                register(preset)
                loadedCount++
                logger.info { "Loaded external preset '${preset.id}' from: ${file.absolutePath}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load preset from file: ${file.absolutePath}" }
            }
        }

        logger.info { "Loaded $loadedCount external agent presets from: ${directory.absolutePath}" }
        return loadedCount
    }

    /**
     * 从单个 YAML 文件加载预设。
     *
     * @param file 预设 YAML 文件
     * @return 解析后的预设，如果解析失败则返回 null
     */
    fun loadPresetFromFile(file: File): AgentPreset? {
        return try {
            val yamlContent = file.readText()
            val preset = parsePresetYaml(yamlContent)
            register(preset)
            logger.info { "Loaded preset '${preset.id}' from: ${file.absolutePath}" }
            preset
        } catch (e: Exception) {
            logger.error(e) { "Failed to load preset from file: ${file.absolutePath}" }
            null
        }
    }

    /**
     * 解析 YAML 字符串为 AgentPreset。
     *
     * YAML 格式示例：
     * ```yaml
     * id: my_agent
     * display_name: "My Custom Agent"
     * description: "A custom agent for specific tasks"
     * category: general
     * icon: "🤖"
     * builtin: false
     * parameters:
     *   type: universal_agent
     *   strategy: just_work_parallel
     *   tool_set:
     *     - exit
     *     - shell
     *   max_iterations: 8196
     * ```
     */
    fun parsePresetYaml(yamlContent: String): AgentPreset {
        return yamlParser.decodeFromString(AgentPreset.serializer(), yamlContent)
    }

    /**
     * 重置为仅包含内置预设（主要用于测试）
     */
    fun resetToBuiltins() {
        presets.clear()
        loadBuiltinPresets()
    }
}
