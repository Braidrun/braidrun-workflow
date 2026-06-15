package com.fartech.agents.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 子工作流调用配置
 *
 * 把一个已保存的 workflow 作为另一个 workflow 的一个原子步骤引用。
 * 参考的 child workflow 可以是一个普通工作流(宽松模式),也可以是一个声明了
 * [WorkflowModuleContract] 的模块(严格模式)。
 *
 * YAML 示例(严格模式 — child 有 module 契约):
 *
 * ```yaml
 * - step: generate_video
 *   sub_workflow:
 *     workflow_id: 0d2c-...-ab12   # Web 场景:持久化 workflow UUID
 *     # 或者 path: ./video_generation.yaml  (CLI 场景)
 *     version_strategy: pinned
 *     pinned_version: "2.0.1"
 *     inputs:
 *       topic: "{{var:topic}}"
 *       transcript: "{{steps.prepare.output}}"
 *       final_path: "/tmp/video.mp4"
 *     outputs:
 *       video_path: video_path         # 把契约 output video_path 映射到父变量 video_path
 *       duration: duration_seconds     # 重命名:契约 duration_seconds → 父变量 duration
 *     kb_scope: isolated
 *     missing_reference_policy: error
 *     max_depth: 4
 * ```
 *
 * YAML 示例(宽松模式 — child 没有 module 契约):
 *
 * ```yaml
 * - step: legacy_call
 *   sub_workflow:
 *     path: ./legacy_child.yaml
 *     inputs:
 *       some_var: "{{var:topic}}"
 *     outputs:
 *       result: "{{var:legacy_output}}"
 *     required_inputs: [some_var]   # 宽松模式必须手写 required_inputs
 * ```
 *
 * 引用标识规则:
 * - `workflow_id` / `path` / `name` 三者互斥,必须且仅能设置一个
 * - Web 场景默认 `workflow_id`,CLI 场景默认 `path`,`name` 仅作低优先级兼容
 *
 * 执行语义:
 * - 子工作流以**独立** [WorkflowExecutionContext] 执行,与父不共享 variables / stepOutputs / agents
 * - 派生 executionId = `${parentExecutionId}/${parentStepName}`,天然隔离 session 与目录
 * - 按 `kb_scope` 决定是否继承父的 `sharedKnowledgeBase`
 * - 按 `agent_inheritance` 决定是否 merge 父的 agents 到 child
 * - child 执行完成后,outputs 映射从 child context 提取并写入父 context.variables
 */
@Serializable
data class SubWorkflowConfig(
    /** 按 workflow UUID 引用(Web 场景的主要路径) */
    @SerialName("workflow_id")
    val workflowId: String? = null,

    /** 按文件路径引用(CLI 场景的主要路径,相对路径基于父 YAML 所在目录) */
    @SerialName("path")
    val path: String? = null,

    /** 按 workflow name 引用(低优先级兼容项,多匹配时报警告) */
    @SerialName("name")
    val name: String? = null,

    /** 版本策略: latest / pinned / range(v1 只支持前两种) */
    @SerialName("version_strategy")
    val versionStrategy: SubWorkflowVersionStrategy = SubWorkflowVersionStrategy.LATEST,

    /** `pinned` 策略必配的具体版本号 */
    @SerialName("pinned_version")
    val pinnedVersion: String? = null,

    /** `range` 策略的版本范围(Phase 4) */
    @SerialName("version_range")
    val versionRange: String? = null,

    /**
     * 父→子的输入映射。
     * - 有契约时:key 必须在 child `module.inputs` 中声明,value 是父上下文的模板表达式。
     * - 无契约时:key 会直接写到 child 的 `variables` 映射里,不做名称校验。
     */
    @SerialName("inputs")
    val inputs: Map<String, String> = emptyMap(),

    /**
     * 子→父的输出映射。
     * - 有契约时:key 是父要接收的变量名,value 是 child `module.outputs` 中的 output 名。
     * - 无契约时:key 是父要接收的变量名,value 是 child context 的模板表达式(如 `{{var:xxx}}`)。
     */
    @SerialName("outputs")
    val outputs: Map<String, String> = emptyMap(),

    /**
     * 宽松模式下手写的必填 input 列表(child 无契约时生效)。
     * 严格模式下由 child `module.inputs[i].required` 自动决定,此字段会被忽略或追加。
     */
    @SerialName("required_inputs")
    val requiredInputs: List<String> = emptyList(),

    /** 宽松模式下手写的可选 input 列表 */
    @SerialName("optional_inputs")
    val optionalInputs: List<String> = emptyList(),

    /**
     * 父 step output 的格式:
     * - `json`(默认):整个 outputs map 序列化为 JSON 字符串,父可用 `extract.json_path` 取字段
     * - `text`:取第一个 output 值或 child 最后一个 step 的输出
     */
    @SerialName("output_format")
    val outputFormat: SubWorkflowOutputFormat = SubWorkflowOutputFormat.JSON,

    /** 知识库作用域 */
    @SerialName("kb_scope")
    val kbScope: SubWorkflowKbScope = SubWorkflowKbScope.ISOLATED,

    /** Agent 继承策略 */
    @SerialName("agent_inheritance")
    val agentInheritance: SubWorkflowAgentInheritanceConfig? = null,

    /** child 引用缺失时的降级策略 */
    @SerialName("missing_reference_policy")
    val missingReferencePolicy: SubWorkflowMissingReferencePolicy = SubWorkflowMissingReferencePolicy.ERROR,

    /** 子工作流最大嵌套深度(null 时回退到 workflow / executor 默认) */
    @SerialName("max_depth")
    val maxDepth: Int? = null
) {
    init {
        val refCount = listOf(workflowId, path, name).count { !it.isNullOrBlank() }
        require(refCount == 1) {
            "sub_workflow must specify exactly one of 'workflow_id', 'path', or 'name' (got $refCount)"
        }
        if (versionStrategy == SubWorkflowVersionStrategy.PINNED) {
            require(!pinnedVersion.isNullOrBlank()) {
                "sub_workflow version_strategy=pinned requires 'pinned_version'"
            }
        }
        if (versionStrategy == SubWorkflowVersionStrategy.RANGE) {
            require(!versionRange.isNullOrBlank()) {
                "sub_workflow version_strategy=range requires 'version_range'"
            }
        }
        maxDepth?.let {
            require(it > 0) { "sub_workflow max_depth must be > 0 (got $it)" }
        }
    }

    /** 转换为 resolver 可用的中立引用对象 */
    fun toReference(): WorkflowReference = WorkflowReference(
        workflowId = workflowId,
        path = path,
        name = name,
        versionStrategy = versionStrategy,
        pinnedVersion = pinnedVersion,
        versionRange = versionRange
    )

    /** 用于日志 / 显示名的简短标签 */
    fun identityLabel(): String = when {
        !workflowId.isNullOrBlank() -> "id=$workflowId${versionLabelSuffix()}"
        !path.isNullOrBlank() -> "path=$path${versionLabelSuffix()}"
        !name.isNullOrBlank() -> "name=$name${versionLabelSuffix()}"
        else -> "unknown"
    }

    private fun versionLabelSuffix(): String = when (versionStrategy) {
        SubWorkflowVersionStrategy.LATEST -> ""
        SubWorkflowVersionStrategy.PINNED -> "@${pinnedVersion ?: "?"}"
        SubWorkflowVersionStrategy.RANGE -> "~${versionRange ?: "?"}"
    }
}

/**
 * 版本策略
 */
@Serializable
enum class SubWorkflowVersionStrategy {
    @SerialName("latest")
    LATEST,

    @SerialName("pinned")
    PINNED,

    @SerialName("range")
    RANGE
}

/**
 * 父 step output 的格式
 */
@Serializable
enum class SubWorkflowOutputFormat {
    @SerialName("json")
    JSON,

    @SerialName("text")
    TEXT
}

/**
 * 知识库作用域
 */
@Serializable
enum class SubWorkflowKbScope {
    /** 子工作流直接使用父的 `sharedKnowledgeBase` 实例(可能读写) */
    @SerialName("inherit")
    INHERIT,

    /** 子工作流用自己的 `knowledge_base` 配置新建实例(默认,最安全) */
    @SerialName("isolated")
    ISOLATED,

    /** 子工作流基于父的 KB 实例,额外追加自己的 source_files */
    @SerialName("extend")
    EXTEND
}

/**
 * Agent 继承策略
 */
@Serializable
data class SubWorkflowAgentInheritanceConfig(
    @SerialName("mode")
    val mode: SubWorkflowAgentInheritanceMode = SubWorkflowAgentInheritanceMode.NONE,

    /** mode=inherit_named 时要继承的父 agents 名称列表 */
    @SerialName("inherit_named")
    val inheritNamed: List<String> = emptyList()
) {
    init {
        if (mode == SubWorkflowAgentInheritanceMode.INHERIT_NAMED) {
            require(inheritNamed.isNotEmpty()) {
                "agent_inheritance mode=inherit_named requires non-empty inherit_named list"
            }
        }
    }
}

@Serializable
enum class SubWorkflowAgentInheritanceMode {
    /** 完全隔离(默认),child 只使用自己的 agents */
    @SerialName("none")
    NONE,

    /** 把父所有 agents merge 到 child,child 同名优先 */
    @SerialName("inherit_all")
    INHERIT_ALL,

    /** 仅继承 `inherit_named` 列表中的父 agents */
    @SerialName("inherit_named")
    INHERIT_NAMED
}

/**
 * Child 引用缺失时的降级策略
 */
@Serializable
enum class SubWorkflowMissingReferencePolicy {
    /** 静态校验和执行时都报错(默认) */
    @SerialName("error")
    ERROR,

    /** 静态校验只 warn,执行时若解析失败则报错 */
    @SerialName("warn")
    WARN,

    /** 缺失时把 step 视为 skip,不影响下游 depends_on */
    @SerialName("skip_step")
    SKIP_STEP
}

/**
 * 跨 workflow 引用的中立表示。
 * 独立于具体存储 / 文件系统实现,供 [WorkflowResolver] 使用。
 */
@Serializable
data class WorkflowReference(
    val workflowId: String? = null,
    val path: String? = null,
    val name: String? = null,
    val versionStrategy: SubWorkflowVersionStrategy = SubWorkflowVersionStrategy.LATEST,
    val pinnedVersion: String? = null,
    val versionRange: String? = null
) {
    /** 同 `SubWorkflowConfig.identityLabel()` 的格式 */
    fun label(): String = when {
        !workflowId.isNullOrBlank() -> "id=$workflowId"
        !path.isNullOrBlank() -> "path=$path"
        !name.isNullOrBlank() -> "name=$name"
        else -> "unknown"
    } + when (versionStrategy) {
        SubWorkflowVersionStrategy.LATEST -> ""
        SubWorkflowVersionStrategy.PINNED -> "@${pinnedVersion ?: "?"}"
        SubWorkflowVersionStrategy.RANGE -> "~${versionRange ?: "?"}"
    }
}
