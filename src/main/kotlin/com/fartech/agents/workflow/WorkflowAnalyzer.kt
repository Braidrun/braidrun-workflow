package com.fartech.agents.workflow

/**
 * 工作流静态分析工具
 *
 * 用于从 workflow 定义中提取:
 * - 所有声明的变量
 * - 所有被引用的变量 / step 输出
 * - 选中 step 范围的变量读写关系(用于 "Extract as Sub-Workflow" 场景)
 *
 * 不依赖运行时执行,纯文本 + AST 分析。对于模板里间接引用的变量只能做 best-effort。
 */
object WorkflowAnalyzer {

    private val VAR_REF_PATTERN = Regex("""\{\{var:([A-Za-z_][A-Za-z0-9_]*)\}\}""")
    private val STEP_REF_PATTERN = Regex("""\{\{steps\.([A-Za-z_][A-Za-z0-9_]*)\.output\}\}""")
    private val BARE_REF_PATTERN = Regex("""\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}""")

    /**
     * 汇总 workflow 中所有声明的变量名(含 `variables` map、`variable_types` map)。
     */
    fun collectDeclaredVariables(workflow: WorkflowDefinition): Set<String> {
        val result = mutableSetOf<String>()
        result += workflow.variables.keys
        result += workflow.variableTypes.keys
        return result
    }

    /**
     * 汇总 workflow 中所有被引用的变量名。
     *
     * 引用来源:
     * - 步骤 input / group_chat.initial_message / agent_based.goal / classifier.input 中的 `{{var:xxx}}` 与 `{{xxx}}`
     * - classifier.instructions / Jev 问题 instructions / repeat_until.jev.state
     * - condition 表达式
     * - iterate_over.source
     * - aggregate.sources
     * - extract 变量(作为写入目标,不算引用)
     */
    fun collectReferencedVariables(workflow: WorkflowDefinition): Set<String> {
        val result = mutableSetOf<String>()
        for (step in workflow.workflow) {
            result += collectStepReadVariables(step)
        }
        return result
    }

    /**
     * 汇总 workflow 中所有被引用的步骤名(`{{steps.xxx.output}}`)。
     */
    fun collectReferencedStepOutputs(workflow: WorkflowDefinition): Set<String> {
        val result = mutableSetOf<String>()
        for (step in workflow.workflow) {
            result += collectStepReferencedOutputs(step)
        }
        return result
    }

    /**
     * 找出步骤中所有会写回变量的地方,返回变量名集合。
     *
     * 写入来源:
     * - `extract[*].variable`
     * - `iterate_over.results_variable`
     * - `aggregate.output_variable`
     * - `classifier.output_variable`(Jev 分类另含 `<out>_confidence` / `<out>_probabilities` 与附加问题变量)
     * - `repeat_until.extract_variable`
     * - `repeat_until.jev` 的问题/综合分变量
     * - `sub_workflow.outputs` keys 与 `state_machine` 各状态 `step` / `actions` 的变量写回
     */
    fun collectWrittenVariables(workflow: WorkflowDefinition): Set<String> {
        val result = mutableSetOf<String>()
        for (step in workflow.workflow) {
            result += collectStepWrittenVariables(step)
        }
        return result
    }

    /**
     * 分析一组选中的 step 范围的读写模式。
     *
     * 用于"Extract as Sub-Workflow"场景:
     * - `readOnly` 中的变量 → 候选必填 inputs(必须从调用方传入)
     * - `writtenThenRead` 中的变量 → 候选内部变量(不公开为 input/output)
     * - `writtenAndExposed` 中的变量 → 候选 outputs(被范围外读取,要公开)
     *
     * @param workflow 父 workflow
     * @param selectedStepNames 要提取的连续 step 名字(顺序敏感)
     */
    fun analyzeVariableReadWrite(
        workflow: WorkflowDefinition,
        selectedStepNames: Set<String>
    ): VariableUsage {
        val selected = workflow.workflow.filter { it.step in selectedStepNames }
        val outside = workflow.workflow.filter { it.step !in selectedStepNames }

        val readInSelected = mutableSetOf<String>()
        val writtenInSelected = mutableSetOf<String>()
        for (step in selected) {
            readInSelected += collectStepReadVariables(step)
            writtenInSelected += collectStepWrittenVariables(step)
        }

        val readOutsideAfter = mutableSetOf<String>()
        for (step in outside) {
            readOutsideAfter += collectStepReadVariables(step)
        }

        val readOnly = readInSelected - writtenInSelected
        val writtenThenRead = writtenInSelected.toMutableSet()
        val writtenAndExposed = writtenInSelected.filter { it in readOutsideAfter }.toSet()
        writtenThenRead -= writtenAndExposed

        return VariableUsage(
            readOnly = readOnly,
            writtenAndExposed = writtenAndExposed,
            writtenInternalOnly = writtenThenRead
        )
    }

    private fun collectStepReadVariables(step: WorkflowStep): Set<String> {
        val result = mutableSetOf<String>()
        result += referencesIn(step.input)
        result += referencesIn(step.condition)
        step.groupChat?.initialMessage?.let { result += referencesIn(it) }
        step.agentBased?.goal?.let { result += referencesIn(it) }
        step.classifier?.input?.let { result += referencesIn(it) }
        jevTemplateFields(step).forEach { result += referencesIn(it) }
        step.iterateOver?.source?.let { result += referencesIn(it) }
        step.aggregate?.sources?.forEach { result += referencesIn(it) }
        step.subWorkflow?.inputs?.values?.forEach { result += referencesIn(it) }

        step.stateMachine?.states?.values?.forEach { stateDef ->
            (stateDef.onEnter + stateDef.onExit).forEach { action ->
                action.value?.let { result += referencesIn(it) }
                action.message?.let { result += referencesIn(it) }
            }
            stateDef.transitions.forEach { transition ->
                result += referencesIn(transition.condition)
                transition.actions.forEach { action ->
                    action.value?.let { result += referencesIn(it) }
                    action.message?.let { result += referencesIn(it) }
                }
            }
            stateDef.stepConfig?.let { stateStep ->
                result += referencesIn(stateStep.input)
                stateStep.groupChat?.initialMessage?.let { result += referencesIn(it) }
                stateStep.agentBased?.goal?.let { result += referencesIn(it) }
                stateStep.classifier?.let { classifier ->
                    result += referencesIn(classifier.input)
                    classifier.instructions?.let { result += referencesIn(it) }
                    classifier.jev?.questions?.forEach { result += referencesIn(it.instructions) }
                }
                stateStep.subWorkflow?.inputs?.values?.forEach { result += referencesIn(it) }
            }
        }
        return result
    }

    private fun collectStepReferencedOutputs(step: WorkflowStep): Set<String> {
        val result = mutableSetOf<String>()
        result += stepOutputsReferencedIn(step.input)
        step.groupChat?.initialMessage?.let { result += stepOutputsReferencedIn(it) }
        step.agentBased?.goal?.let { result += stepOutputsReferencedIn(it) }
        step.classifier?.input?.let { result += stepOutputsReferencedIn(it) }
        jevTemplateFields(step).forEach { result += stepOutputsReferencedIn(it) }
        step.iterateOver?.source?.let { result += stepOutputsReferencedIn(it) }
        step.aggregate?.sources?.forEach { result += stepOutputsReferencedIn(it) }
        step.subWorkflow?.inputs?.values?.forEach { result += stepOutputsReferencedIn(it) }

        step.stateMachine?.states?.values?.forEach { stateDef ->
            (stateDef.onEnter + stateDef.onExit).forEach { action ->
                action.value?.let { result += stepOutputsReferencedIn(it) }
                action.message?.let { result += stepOutputsReferencedIn(it) }
            }
            stateDef.transitions.forEach { transition ->
                result += stepOutputsReferencedIn(transition.condition)
                transition.actions.forEach { action ->
                    action.value?.let { result += stepOutputsReferencedIn(it) }
                    action.message?.let { result += stepOutputsReferencedIn(it) }
                }
            }
            stateDef.stepConfig?.let { stateStep ->
                result += stepOutputsReferencedIn(stateStep.input)
                stateStep.groupChat?.initialMessage?.let { result += stepOutputsReferencedIn(it) }
                stateStep.agentBased?.goal?.let { result += stepOutputsReferencedIn(it) }
                stateStep.classifier?.let { classifier ->
                    result += stepOutputsReferencedIn(classifier.input)
                    classifier.instructions?.let { result += stepOutputsReferencedIn(it) }
                    classifier.jev?.questions?.forEach { result += stepOutputsReferencedIn(it.instructions) }
                }
                stateStep.subWorkflow?.inputs?.values?.forEach { result += stepOutputsReferencedIn(it) }
            }
        }
        return result
    }

    private fun collectStepWrittenVariables(step: WorkflowStep): Set<String> {
        val result = mutableSetOf<String>()
        step.extract?.forEach { result += it.variable }
        step.iterateOver?.resultsVariable?.let { result += it }
        step.aggregate?.outputVariable?.let { result += it }
        step.classifier?.outputVariable?.let { result += it }
        step.repeatUntil?.extractVariable?.let { result += it }
        result += jevWrittenVariables(step)
        step.subWorkflow?.outputs?.keys?.let { result += it }

        step.stateMachine?.states?.values?.forEach { stateDef ->
            (stateDef.onEnter + stateDef.onExit + stateDef.transitions.flatMap { it.actions }).forEach { action ->
                if (action.action == "set_variable" || action.action == "set_var") {
                    action.key?.takeIf { it.isNotBlank() }?.let { result += it }
                }
            }
            stateDef.stepConfig?.let { stateStep ->
                stateStep.extract?.forEach { result += it.variable }
                stateStep.classifier?.let { classifier ->
                    result += classifier.outputVariable
                    result += classifier.jevWrittenVariables()
                }
                stateStep.subWorkflow?.outputs?.keys?.let { result += it }
            }
        }
        return result
    }

    /**
     * classifier.instructions、classifier.jev 问题 instructions、repeat_until.jev.state 与其问题
     * instructions —— 这些字段都支持模板,运行时会被解析。
     */
    private fun jevTemplateFields(step: WorkflowStep): List<String> {
        val fields = mutableListOf<String>()
        step.classifier?.let { classifier ->
            classifier.instructions?.let { fields += it }
            classifier.jev?.questions?.forEach { fields += it.instructions }
        }
        step.repeatUntil?.jev?.let { jev ->
            jev.state?.let { fields += it }
            jev.questions.forEach { fields += it.instructions }
        }
        return fields
    }

    /** Jev 分类器与 repeat_until.jev 写入的派生变量(见 [JevVariableNames]) */
    private fun jevWrittenVariables(step: WorkflowStep): List<String> =
        step.classifier?.jevWrittenVariables().orEmpty() +
            step.repeatUntil?.jev?.writtenVariables().orEmpty()

    /** 从字符串里提取所有 `{{var:xxx}}` / `{{xxx}}` 引用 */
    private fun referencesIn(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val result = mutableSetOf<String>()
        VAR_REF_PATTERN.findAll(text).forEach { result += it.groupValues[1] }
        BARE_REF_PATTERN.findAll(text).forEach { result += it.groupValues[1] }
        return result
    }

    /** 从字符串里提取所有 `{{steps.xxx.output}}` 引用 */
    private fun stepOutputsReferencedIn(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val result = mutableSetOf<String>()
        STEP_REF_PATTERN.findAll(text).forEach { result += it.groupValues[1] }
        return result
    }
}

/**
 * 变量读写分析结果
 *
 * @property readOnly 被选中范围读取、但范围内没写入 → 候选 required inputs
 * @property writtenAndExposed 被选中范围写入、且范围外也读取 → 候选 outputs
 * @property writtenInternalOnly 被选中范围写入、且仅范围内部使用 → 内部变量(不公开)
 */
data class VariableUsage(
    val readOnly: Set<String>,
    val writtenAndExposed: Set<String>,
    val writtenInternalOnly: Set<String>
)
