package com.fartech.agents.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工作流模块契约(Workflow Module Contract)
 *
 * 允许一个 workflow 显式声明自己是一个"可复用模块",公开一套明确的 inputs / outputs API surface,
 * 让调用方(父 workflow 的 `sub_workflow` 步骤)基于契约做类型校验、自动补全、版本兼容性检查。
 *
 * 没有 `module` 块的 workflow 仍然可以被引用(宽松模式),但失去了契约保护。
 *
 * YAML 示例:
 * ```yaml
 * name: video_generation
 * version: "2.0.1"
 *
 * module:
 *   display_name: "视频生成模块"
 *   description: "根据话题与脚本生成带字幕的短视频"
 *   category: content
 *   icon: "🎬"
 *   tags: [video, short-form]
 *   contract_version: "1.0.0"
 *   inputs:
 *     - name: topic
 *       type: string
 *       required: true
 *       description: "视频主题"
 *     - name: style
 *       type: enum
 *       required: false
 *       default: "cinematic"
 *       allowed_values: [cinematic, minimal, energetic]
 *   outputs:
 *     - name: video_path
 *       type: path
 *       source: "{{var:final_path}}"
 *     - name: duration_seconds
 *       type: number
 *       source: "{{var:duration_seconds}}"
 *   examples:
 *     - title: "默认风格"
 *       inputs:
 *         topic: "AI 应用实战"
 *         transcript: "..."
 *
 * agents: {...}
 * variables: {...}
 * workflow: [...]
 * ```
 *
 * 契约版本管理:
 * - `contract_version` 独立于 `workflow.version`,通常遵循 semver
 * - 新增 required input / 删除 output / 修改 type → BREAKING CHANGE,调用方需要升级
 * - 新增 output / 变 required 为 optional → 向后兼容,静默生效
 */
@Serializable
data class WorkflowModuleContract(
    /** 是否启用模块模式(默认 true,字段存在即启用) */
    @SerialName("enabled")
    val enabled: Boolean = true,

    // ---------- 元数据 ----------

    /** 模块的用户可见显示名称(picker 和模块市场展示) */
    @SerialName("display_name")
    val displayName: String? = null,

    /** 模块描述 */
    @SerialName("description")
    val description: String? = null,

    /** 模块分类(如 content / asa / review / ops),用于 picker 过滤 */
    @SerialName("category")
    val category: String? = null,

    /** 模块图标(建议单个 emoji) */
    @SerialName("icon")
    val icon: String? = null,

    /** 模块标签,用于搜索和过滤 */
    @SerialName("tags")
    val tags: List<String> = emptyList(),

    // ---------- 契约主体 ----------

    /** 对外暴露的输入 */
    @SerialName("inputs")
    val inputs: List<ModuleInputSpec> = emptyList(),

    /** 对外暴露的输出 */
    @SerialName("outputs")
    val outputs: List<ModuleOutputSpec> = emptyList(),

    // ---------- 版本兼容性管理 ----------

    /** 契约版本(semver,与 workflow.version 独立) */
    @SerialName("contract_version")
    val contractVersion: String = "1.0.0",

    /** 是否已弃用(调用方会收到 warning) */
    @SerialName("deprecated")
    val deprecated: Boolean = false,

    /** 弃用说明,建议用户迁移到哪个新模块 */
    @SerialName("deprecation_note")
    val deprecationNote: String? = null,

    // ---------- 发现性 ----------

    /** 示例用法(picker 预览、AI 助手生成调用代码时复用) */
    @SerialName("examples")
    val examples: List<ModuleUsageExample> = emptyList()
) {
    init {
        val dupInputs = inputs.map { it.name }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        require(dupInputs.isEmpty()) { "Module duplicate input names: $dupInputs" }
        val dupOutputs = outputs.map { it.name }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        require(dupOutputs.isEmpty()) { "Module duplicate output names: $dupOutputs" }
    }

    /** 按名查找 input spec */
    fun findInput(name: String): ModuleInputSpec? = inputs.firstOrNull { it.name == name }

    /** 按名查找 output spec */
    fun findOutput(name: String): ModuleOutputSpec? = outputs.firstOrNull { it.name == name }

    /** 所有必填 input 的名称列表 */
    fun requiredInputNames(): List<String> = inputs.filter { it.required }.map { it.name }

    /** 所有可选 input 的名称列表 */
    fun optionalInputNames(): List<String> = inputs.filter { !it.required }.map { it.name }
}

/**
 * 模块输入规格
 */
@Serializable
data class ModuleInputSpec(
    /** 输入的外部名称(调用方使用的 key) */
    @SerialName("name")
    val name: String,

    /** 类型: string / number / boolean / path / json / list / enum */
    @SerialName("type")
    val type: String = "string",

    /** 是否必填 */
    @SerialName("required")
    val required: Boolean = false,

    /** 默认值(仅 required=false 时有意义) */
    @SerialName("default")
    val default: String? = null,

    /** 描述说明(UI 展示、AI 生成代码时复用) */
    @SerialName("description")
    val description: String? = null,

    /** 示例值 */
    @SerialName("example")
    val example: String? = null,

    /**
     * 内部映射:此输入对应 workflow.variables 里的哪个 key(默认等于 name)。
     * 允许"外部叫 topic,内部变量叫 refined_topic"这种重命名。
     */
    @SerialName("maps_to_variable")
    val mapsToVariable: String? = null,

    /** type=enum 时的合法枚举值(至少 1 个) */
    @SerialName("allowed_values")
    val allowedValues: List<String>? = null,

    /** type=number 时的最小值约束 */
    @SerialName("min")
    val min: Double? = null,

    /** type=number 时的最大值约束 */
    @SerialName("max")
    val max: Double? = null
) {
    init {
        require(name.isNotBlank()) { "ModuleInputSpec name cannot be blank" }
        require(type in SUPPORTED_TYPES) {
            "ModuleInputSpec type must be one of: $SUPPORTED_TYPES. Got: '$type'"
        }
        if (required) {
            require(default.isNullOrBlank()) {
                "ModuleInputSpec '$name' is required=true but also has a default value; choose one"
            }
        }
        if (type == "enum") {
            require(!allowedValues.isNullOrEmpty()) {
                "ModuleInputSpec '$name' has type=enum but no allowed_values"
            }
            default?.let { dv ->
                require(dv in allowedValues) {
                    "ModuleInputSpec '$name' default '$dv' is not in allowed_values $allowedValues"
                }
            }
        }
        if (type == "number") {
            default?.let { dv ->
                require(dv.toDoubleOrNull() != null) {
                    "ModuleInputSpec '$name' type=number default '$dv' is not a valid number"
                }
            }
            if (min != null && max != null) {
                require(min <= max) {
                    "ModuleInputSpec '$name' has min ($min) > max ($max)"
                }
            }
        }
    }

    /** 计算实际写入 workflow.variables 时使用的 key */
    fun variableKey(): String = mapsToVariable?.takeIf { it.isNotBlank() } ?: name

    companion object {
        val SUPPORTED_TYPES = setOf("string", "number", "boolean", "path", "json", "list", "enum")
    }
}

/**
 * 模块输出规格
 */
@Serializable
data class ModuleOutputSpec(
    /** 输出的外部名称(调用方使用的 key) */
    @SerialName("name")
    val name: String,

    /** 类型: string / number / boolean / path / json / list */
    @SerialName("type")
    val type: String = "string",

    /** 描述说明 */
    @SerialName("description")
    val description: String? = null,

    /**
     * 输出源表达式。child 执行完成时,这个字符串会被 `resolveTemplate(source, childContext)` 解析,
     * 结果就是对外暴露的 output 值。
     *
     * 支持的模板语法:
     * - `{{var:xxx}}` — 从 workflow 变量取
     * - `{{steps.xxx.output}}` — 从指定步骤的输出取
     * - 字面量字符串(不含模板)
     */
    @SerialName("source")
    val source: String
) {
    init {
        require(name.isNotBlank()) { "ModuleOutputSpec name cannot be blank" }
        require(source.isNotBlank()) { "ModuleOutputSpec '$name' source cannot be blank" }
        require(type in SUPPORTED_TYPES) {
            "ModuleOutputSpec type must be one of: $SUPPORTED_TYPES. Got: '$type'"
        }
    }

    companion object {
        val SUPPORTED_TYPES = setOf("string", "number", "boolean", "path", "json", "list")
    }
}

/**
 * 模块使用示例
 */
@Serializable
data class ModuleUsageExample(
    @SerialName("title")
    val title: String,

    @SerialName("inputs")
    val inputs: Map<String, String> = emptyMap(),

    @SerialName("description")
    val description: String? = null
) {
    init {
        require(title.isNotBlank()) { "ModuleUsageExample title cannot be blank" }
    }
}

/**
 * 契约兼容性比较结果
 *
 * 被 `WorkflowService.saveWorkflowRecord` 与 `WebWorkflowResolver` 共用,用于检测模块更新
 * 相对历史版本是否产生了破坏性变更。
 */
data class ContractCompatibilityReport(
    val hasBreakingChanges: Boolean,
    val breakingChanges: List<String>,
    val nonBreakingChanges: List<String>,
    val suggestedContractVersion: String? = null
) {
    val isCompatible: Boolean get() = !hasBreakingChanges

    companion object {
        val COMPATIBLE = ContractCompatibilityReport(
            hasBreakingChanges = false,
            breakingChanges = emptyList(),
            nonBreakingChanges = emptyList()
        )
    }
}

/**
 * 纯 Kotlin 的契约比较工具(不依赖 Web 层)。
 * Web 端的 `ModuleContractCompatibilityChecker` 可以直接复用或扩展这个逻辑。
 */
object ModuleContractComparator {
    /**
     * 比较新旧契约,识别破坏性变更。
     *
     * 破坏性变更:
     * - 新增 required=true 的 input
     * - 删除 output
     * - 修改 input 或 output 的 type
     * - 把 input 从 optional 改为 required
     *
     * 非破坏性变更:
     * - 新增 output
     * - 新增 optional=true 的 input
     * - 把 input 从 required 改为 optional
     * - 修改 description / example / allowed_values(扩展)
     */
    fun compare(old: WorkflowModuleContract, new: WorkflowModuleContract): ContractCompatibilityReport {
        val breaking = mutableListOf<String>()
        val nonBreaking = mutableListOf<String>()

        val oldInputMap = old.inputs.associateBy { it.name }
        val newInputMap = new.inputs.associateBy { it.name }

        // 检查 inputs
        for ((name, newInput) in newInputMap) {
            val oldInput = oldInputMap[name]
            if (oldInput == null) {
                if (newInput.required) {
                    breaking.add("New required input '$name' added")
                } else {
                    nonBreaking.add("New optional input '$name' added")
                }
            } else {
                if (oldInput.type != newInput.type) {
                    breaking.add("Input '$name' type changed: ${oldInput.type} → ${newInput.type}")
                }
                if (!oldInput.required && newInput.required) {
                    breaking.add("Input '$name' changed from optional to required")
                }
                if (oldInput.required && !newInput.required) {
                    nonBreaking.add("Input '$name' relaxed from required to optional")
                }
                if (newInput.type == "enum" && oldInput.type == "enum") {
                    val removed = (oldInput.allowedValues.orEmpty() - newInput.allowedValues.orEmpty().toSet())
                    if (removed.isNotEmpty()) {
                        breaking.add("Input '$name' enum removed values: $removed")
                    }
                }
                if (newInput.type == "number" && oldInput.type == "number") {
                    val oldMin = oldInput.min
                    val newMin = newInput.min
                    when {
                        newMin != null && (oldMin == null || newMin > oldMin) ->
                            breaking.add("Input '$name' minimum increased: ${oldMin ?: "-∞"} → $newMin")

                        oldMin != null && (newMin == null || newMin < oldMin) ->
                            nonBreaking.add("Input '$name' minimum relaxed: $oldMin → ${newMin ?: "-∞"}")
                    }

                    val oldMax = oldInput.max
                    val newMax = newInput.max
                    when {
                        newMax != null && (oldMax == null || newMax < oldMax) ->
                            breaking.add("Input '$name' maximum decreased: ${oldMax ?: "+∞"} → $newMax")

                        oldMax != null && (newMax == null || newMax > oldMax) ->
                            nonBreaking.add("Input '$name' maximum relaxed: $oldMax → ${newMax ?: "+∞"}")
                    }
                }
            }
        }
        for ((name, oldInput) in oldInputMap) {
            if (name !in newInputMap) {
                if (oldInput.required) {
                    // 删除 required input 是破坏性的(调用方可能还在传)
                    breaking.add("Required input '$name' removed")
                } else {
                    nonBreaking.add("Optional input '$name' removed")
                }
            }
        }

        // 检查 outputs
        val oldOutputMap = old.outputs.associateBy { it.name }
        val newOutputMap = new.outputs.associateBy { it.name }
        for ((name, oldOutput) in oldOutputMap) {
            val newOutput = newOutputMap[name]
            if (newOutput == null) {
                breaking.add("Output '$name' removed")
            } else if (oldOutput.type != newOutput.type) {
                breaking.add("Output '$name' type changed: ${oldOutput.type} → ${newOutput.type}")
            }
        }
        for ((name, _) in newOutputMap) {
            if (name !in oldOutputMap) {
                nonBreaking.add("New output '$name' added")
            }
        }

        // Deprecation
        if (!old.deprecated && new.deprecated) {
            nonBreaking.add("Module marked as deprecated")
        }

        val suggested = if (breaking.isNotEmpty()) bumpMajor(old.contractVersion) else null

        return ContractCompatibilityReport(
            hasBreakingChanges = breaking.isNotEmpty(),
            breakingChanges = breaking,
            nonBreakingChanges = nonBreaking,
            suggestedContractVersion = suggested
        )
    }

    private fun bumpMajor(version: String): String {
        val parts = version.split(".")
        if (parts.isEmpty()) return "2.0.0"
        val major = parts[0].toIntOrNull() ?: return "2.0.0"
        return "${major + 1}.0.0"
    }
}
