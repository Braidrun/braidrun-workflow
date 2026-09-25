package com.fartech.agents.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// TypeSafe Jev 决策配置（classifier.jev / repeat_until.jev）
// ============================================================================
//
// Jev 是"决策模型"而不是聊天模型：一次请求回答多道带类型的问题（choice / score / noul），
// 返回带校准概率的结构化答案。这里的类是 YAML 侧的配置（snake_case，kaml 编解码），
// 运行时（请求构建、答案 → 变量、事件载荷）见 JevDecisionRuntime.kt，
// HTTP 客户端与凭据解析见 com.fartech.agents.jev。
//
// 约定（与 WorkflowModels.kt 一致）：
// - 每个字段都有 @SerialName；可选字段默认 null（kaml encodeDefaults=true 会导出 `x: null`，无害）。
// - 校验放在 init { require(...) } 中；IllegalArgumentException 由 WorkflowParser 包装为
//   WorkflowValidationException。
// - YAML 配置不用 kotlinx 多态（kaml 的 tag 风格多态不友好），题型用扁平类 + 枚举 `type`。

/** 问题 id / 综合分名称的合法格式：会成为工作流变量名以及 code 步骤的 `WF_VAR_*` 环境变量名。 */
internal val JEV_IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/** Jev choice 问题（含 classifier 主问题）最多支持的选项数。 */
const val MAX_JEV_CHOICE_OPTIONS = 255

/** Jev score 问题的等级数范围。 */
const val MIN_JEV_SCORE_LEVELS = 2
const val MAX_JEV_SCORE_LEVELS = 10

/** noul 问题未配置 threshold 时，`<id>_yes` 使用的默认阈值。 */
const val DEFAULT_JEV_NOUL_THRESHOLD = 0.5

/** Jev 题型。YAML / wire 名称分别是 `choice` / `score` / `noul`。 */
@Serializable
enum class JevQuestionType {
    /** 单选：从 options 中选一个，返回 choice + probabilities + confidence */
    @SerialName("choice")
    CHOICE,

    /** 评分：按有序等级打分，返回 0..n-1 的概率加权分数 */
    @SerialName("score")
    SCORE,

    /** 是/否：返回"是"的概率（0..1），没有 confidence */
    @SerialName("noul")
    NOUL;

    /** 与 YAML / Jev wire `type` 一致的名称 */
    val wireName: String
        get() = when (this) {
            CHOICE -> "choice"
            SCORE -> "score"
            NOUL -> "noul"
        }
}

/**
 * 一道附加的 Jev 类型化问题（classifier.jev.questions / repeat_until.jev.questions）。
 *
 * YAML 格式:
 * ```yaml
 * - id: is_urgent
 *   type: noul
 *   instructions: "Does the customer say the problem is time-sensitive?"
 *   yes_description: "Explicitly time-sensitive"   # 可选
 *   no_description: "No urgency expressed"         # 可选
 *   threshold: 0.7                                 # 可选，默认 0.5
 * - id: frustration
 *   type: score
 *   instructions: "How frustrated does the customer appear?"
 *   levels: ["Calm", "Frustrated but civil", "Very angry"]   # 2..10
 * - id: channel
 *   type: choice
 *   instructions: "Which channel should we reply on?"
 *   options:
 *     - {name: email, description: "Reply by email"}
 *     - {name: phone, description: "Call the customer back"}
 *   default_option: email      # 可选
 *   min_confidence: 0.5        # 可选
 * ```
 *
 * 写入的变量见 [JevVariableNames.forQuestions]。
 */
@Serializable
data class JevQuestionConfig(
    /** 问题 id（`^[A-Za-z_][A-Za-z0-9_]*$`），同时是写入变量的基础名 */
    @SerialName("id")
    val id: String,

    /** 题型 */
    @SerialName("type")
    val type: JevQuestionType,

    /** 问题描述（支持 `{{...}}` 模板，运行时解析） */
    @SerialName("instructions")
    val instructions: String,

    /** CHOICE：选项（2..255，名称唯一） */
    @SerialName("options")
    val options: List<ClassifierCategory>? = null,

    /** CHOICE：置信度低于 min_confidence 时使用的默认选项（必须是选项之一） */
    @SerialName("default_option")
    val defaultOption: String? = null,

    /** CHOICE：最低置信度 [0,1] */
    @SerialName("min_confidence")
    val minConfidence: Double? = null,

    /** SCORE：等级描述，等级 0 在前（2..10 个，非空） */
    @SerialName("levels")
    val levels: List<String>? = null,

    /** NOUL：回答"是"的含义（可选） */
    @SerialName("yes_description")
    val yesDescription: String? = null,

    /** NOUL：回答"否"的含义（可选） */
    @SerialName("no_description")
    val noDescription: String? = null,

    /** NOUL：`<id>_yes` 为 "true" 的阈值 [0,1]，默认 0.5 */
    @SerialName("threshold")
    val threshold: Double? = null
) {
    /** NOUL 的有效阈值（未配置时为 [DEFAULT_JEV_NOUL_THRESHOLD]） */
    val effectiveThreshold: Double
        get() = threshold ?: DEFAULT_JEV_NOUL_THRESHOLD

    init {
        require(JEV_IDENTIFIER_REGEX.matches(id)) {
            "jev question id '$id' is invalid: it must match ^[A-Za-z_][A-Za-z0-9_]*\$ (it becomes a workflow variable name)"
        }
        require(instructions.isNotBlank()) { "jev question '$id': instructions cannot be blank" }
        when (type) {
            JevQuestionType.CHOICE -> {
                val choiceOptions = options
                require(choiceOptions != null) { "jev choice question '$id': options are required" }
                require(choiceOptions.size in 2..MAX_JEV_CHOICE_OPTIONS) {
                    "jev choice question '$id' must have 2 to $MAX_JEV_CHOICE_OPTIONS options (got ${choiceOptions.size})"
                }
                val names = choiceOptions.map { it.name }
                val duplicates = names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                require(duplicates.isEmpty()) {
                    "jev choice question '$id' has duplicate option names: ${duplicates.joinToString(", ")}"
                }
                defaultOption?.let { option ->
                    require(option in names) {
                        "jev choice question '$id': default_option '$option' must be one of the options: $names"
                    }
                }
                minConfidence?.let { requireUnitInterval("jev choice question '$id' min_confidence", it) }
                require(levels == null && yesDescription == null && noDescription == null && threshold == null) {
                    "jev choice question '$id': levels, yes_description, no_description and threshold are not allowed for type choice"
                }
            }

            JevQuestionType.SCORE -> {
                val scoreLevels = levels
                require(scoreLevels != null) { "jev score question '$id': levels are required" }
                require(scoreLevels.size in MIN_JEV_SCORE_LEVELS..MAX_JEV_SCORE_LEVELS) {
                    "jev score question '$id' must have $MIN_JEV_SCORE_LEVELS to $MAX_JEV_SCORE_LEVELS levels (got ${scoreLevels.size})"
                }
                require(scoreLevels.all { it.isNotBlank() }) { "jev score question '$id': levels cannot be blank" }
                require(
                    options == null && defaultOption == null && minConfidence == null &&
                        yesDescription == null && noDescription == null && threshold == null
                ) {
                    "jev score question '$id': options, default_option, min_confidence, yes_description, no_description and threshold are not allowed for type score"
                }
            }

            JevQuestionType.NOUL -> {
                threshold?.let { requireUnitInterval("jev noul question '$id' threshold", it) }
                yesDescription?.let { require(it.isNotBlank()) { "jev noul question '$id': yes_description cannot be blank when set" } }
                noDescription?.let { require(it.isNotBlank()) { "jev noul question '$id': no_description cannot be blank when set" } }
                require(options == null && defaultOption == null && minConfidence == null && levels == null) {
                    "jev noul question '$id': options, default_option, min_confidence and levels are not allowed for type noul"
                }
            }
        }
    }
}

/**
 * 综合分：对 score / noul 答案的归一化值做加权平均（Σ w·v / Σ w）。
 * SCORE 取 `<id>_normalized`，NOUL 取 `<id>`（"是"的概率）。
 *
 * YAML 格式:
 * ```yaml
 * composites:
 *   - name: priority
 *     weights: {frustration: 0.4, is_urgent: 0.6}
 * ```
 */
@Serializable
data class JevComposite(
    /** 综合分名称（`^[A-Za-z_][A-Za-z0-9_]*$`），即写入的变量名 */
    @SerialName("name")
    val name: String,

    /** 问题 id → 权重（> 0 且有限） */
    @SerialName("weights")
    val weights: Map<String, Double>
) {
    init {
        require(JEV_IDENTIFIER_REGEX.matches(name)) {
            "jev composite name '$name' is invalid: it must match ^[A-Za-z_][A-Za-z0-9_]*\$ (it becomes a workflow variable name)"
        }
        require(weights.isNotEmpty()) { "jev composite '$name' must have at least one weight" }
        weights.forEach { (questionId, weight) ->
            require(weight.isFinite() && weight > 0.0) {
                "jev composite '$name': weight for '$questionId' must be a finite number > 0 (got $weight)"
            }
        }
    }
}

/**
 * classifier.jev：存在即表示分类由 TypeSafe Jev 回答（一个 choice 问题覆盖 `categories`），
 * 不再使用 LLM agent。可附带额外的类型化问题与综合分，与主问题在同一次请求中回答。
 *
 * YAML 格式（`jev: {}` 也合法）:
 * ```yaml
 * jev:
 *   model: jev-latest          # 可选
 *   min_confidence: 0.6        # 可选，[0,1]
 *   questions: [...]           # 可选，见 JevQuestionConfig
 *   composites: [...]          # 可选，见 JevComposite
 * ```
 */
@Serializable
data class ClassifierJevConfig(
    /** 模型（可选）：未配置时依次使用凭据默认模型 / `typesafe_model` 参数 / TYPESAFE_DEFAULT_MODEL / jev-latest */
    @SerialName("model")
    val model: String? = null,

    /** 主分类的最低置信度 [0,1]：低于它时使用 default_category，没有默认类别则步骤失败 */
    @SerialName("min_confidence")
    val minConfidence: Double? = null,

    /** 额外问题 */
    @SerialName("questions")
    val questions: List<JevQuestionConfig> = emptyList(),

    /** 综合分 */
    @SerialName("composites")
    val composites: List<JevComposite> = emptyList()
) {
    init {
        model?.let { requireValidJevModel("classifier.jev", it) }
        minConfidence?.let { requireUnitInterval("classifier.jev min_confidence", it) }
        validateJevQuestionSet("classifier.jev", questions, composites)
    }

    /** 额外问题与综合分写入的变量名（不含 classifier 的 output_variable 系列） */
    fun questionVariables(): List<String> = JevVariableNames.forQuestions(questions.idTypePairs(), composites.map { it.name })
}

/**
 * repeat_until.jev：每轮迭代后用 Jev 类型化问题（通常是对步骤输出的 score / noul）评估，
 * 替代 evaluate_agent + 正则提取。写入变量与综合分，并把确定性的评语写入
 * `{{steps.<step>:evaluate.output}}`，随后照常评估 `condition`。
 *
 * YAML 格式:
 * ```yaml
 * repeat_until:
 *   condition: "quality >= 0.75"
 *   max_iterations: 3
 *   jev:
 *     model: jev-latest                      # 可选
 *     state: "{{steps.draft.output}}"        # 可选，默认 "{{steps.<当前步骤>.output}}"
 *     questions:                             # 必填，至少一个
 *       - {id: accuracy, type: score, instructions: "...", levels: ["...", "...", "..."]}
 *     composites:
 *       - {name: quality, weights: {accuracy: 1}}
 * ```
 */
@Serializable
data class RepeatUntilJevConfig(
    /** 模型（可选），解析顺序同 [ClassifierJevConfig.model] */
    @SerialName("model")
    val model: String? = null,

    /** 发送给 Jev 的 state 模板（可选，默认当前步骤输出） */
    @SerialName("state")
    val state: String? = null,

    /** 评估问题（至少一个） */
    @SerialName("questions")
    val questions: List<JevQuestionConfig>,

    /** 综合分 */
    @SerialName("composites")
    val composites: List<JevComposite> = emptyList()
) {
    init {
        model?.let { requireValidJevModel("repeat_until.jev", it) }
        state?.let { require(it.isNotBlank()) { "repeat_until.jev: state cannot be blank when set" } }
        require(questions.isNotEmpty()) { "repeat_until.jev must define at least one question" }
        validateJevQuestionSet("repeat_until.jev", questions, composites)
    }

    /** 每轮评估写入的变量名（按 [JevVariableNames.forQuestions] 的顺序） */
    fun writtenVariables(): List<String> = JevVariableNames.forQuestions(questions.idTypePairs(), composites.map { it.name })

    /** 未配置 [state] 时使用的默认 state 模板 */
    fun stateTemplateFor(stepName: String): String = state ?: "{{steps.$stepName.output}}"
}

/**
 * Jev 派生变量命名（纯函数，基于原始类型，便于 web DTO lint 与前端复用同一算法而无需
 * 构造会在 init 中抛异常的引擎配置）。
 *
 * 每道问题（按配置顺序）:
 * - choice → `<id>`, `<id>_confidence`, `<id>_probabilities`
 * - score  → `<id>`, `<id>_level`, `<id>_normalized`, `<id>_confidence`, `<id>_probabilities`
 * - noul   → `<id>`, `<id>_yes`
 * - 未知/空题型 → 仅 `<id>`（从不抛异常）
 * 然后是每个综合分名称（按配置顺序）。id / 名称原样使用（不 trim）。
 */
object JevVariableNames {

    const val CONFIDENCE_SUFFIX = "_confidence"
    const val PROBABILITIES_SUFFIX = "_probabilities"
    const val LEVEL_SUFFIX = "_level"
    const val NORMALIZED_SUFFIX = "_normalized"
    const val YES_SUFFIX = "_yes"

    /**
     * Derived names for a question set. `questions` = (id, type) with type "choice"|"score"|"noul"; unknown/blank
     * types still yield the base id (never throw). Order: per question in order, then composites.
     */
    fun forQuestions(questions: List<Pair<String, String>>, compositeNames: List<String>): List<String> {
        val names = mutableListOf<String>()
        for ((id, type) in questions) {
            names += forQuestion(id, type)
        }
        names += compositeNames
        return names
    }

    /** Classifier Jev: [outputVariable, "<out>_confidence", "<out>_probabilities"] + forQuestions(...). INCLUDES <out>. */
    fun forClassifier(
        outputVariable: String,
        questions: List<Pair<String, String>>,
        compositeNames: List<String>
    ): List<String> =
        listOf(outputVariable, outputVariable + CONFIDENCE_SUFFIX, outputVariable + PROBABILITIES_SUFFIX) +
            forQuestions(questions, compositeNames)

    /** Names written by a single question (see class docs). */
    fun forQuestion(id: String, type: String): List<String> = when (type) {
        "choice" -> listOf(id, id + CONFIDENCE_SUFFIX, id + PROBABILITIES_SUFFIX)
        "score" -> listOf(id, id + LEVEL_SUFFIX, id + NORMALIZED_SUFFIX, id + CONFIDENCE_SUFFIX, id + PROBABILITIES_SUFFIX)
        "noul" -> listOf(id, id + YES_SUFFIX)
        else -> listOf(id)
    }

    /** Distinct names that occur more than once in [names], in first-occurrence order. */
    fun duplicates(names: List<String>): List<String> {
        val seen = HashSet<String>()
        val reported = LinkedHashSet<String>()
        for (name in names) {
            if (!seen.add(name)) reported += name
        }
        return reported.toList()
    }
}

// ---------------------------------------------------------------------------
// 共享校验
// ---------------------------------------------------------------------------

internal fun List<JevQuestionConfig>.idTypePairs(): List<Pair<String, String>> = map { it.id to it.type.wireName }

internal fun requireUnitInterval(label: String, value: Double) {
    require(value.isFinite() && value in 0.0..1.0) { "$label must be between 0 and 1 (got $value)" }
}

internal fun requireValidJevModel(owner: String, model: String) {
    require(model.isNotBlank() && model.none { it.isWhitespace() }) {
        "$owner: model must be a non-blank model id without whitespace (got '$model')"
    }
}

/**
 * 问题集的交叉校验：问题 id 唯一、综合分名称唯一、综合分只引用本集合中的 score / noul 问题，
 * 且派生变量名互不重复。
 */
internal fun validateJevQuestionSet(owner: String, questions: List<JevQuestionConfig>, composites: List<JevComposite>) {
    val duplicateIds = JevVariableNames.duplicates(questions.map { it.id })
    require(duplicateIds.isEmpty()) { "$owner: duplicate question ids: ${duplicateIds.joinToString(", ")}" }

    val duplicateComposites = JevVariableNames.duplicates(composites.map { it.name })
    require(duplicateComposites.isEmpty()) { "$owner: duplicate composite names: ${duplicateComposites.joinToString(", ")}" }

    val questionTypes = questions.associate { it.id to it.type }
    composites.forEach { composite ->
        composite.weights.keys.forEach { questionId ->
            val type = questionTypes[questionId]
            require(type == JevQuestionType.SCORE || type == JevQuestionType.NOUL) {
                "$owner: composite '${composite.name}' references '$questionId', which is not a score or noul question in the same question set"
            }
        }
    }

    requireNoDuplicateJevVariables(
        owner,
        JevVariableNames.forQuestions(questions.idTypePairs(), composites.map { it.name })
    )
}

internal fun requireNoDuplicateJevVariables(owner: String, names: List<String>) {
    val duplicates = JevVariableNames.duplicates(names)
    require(duplicates.isEmpty()) {
        "$owner: Jev would write variable(s) ${duplicates.joinToString(", ") { "'$it'" }} more than once " +
            "(question ids, composite names and derived variable names must not collide)"
    }
}
