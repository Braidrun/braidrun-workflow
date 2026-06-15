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
     * - condition 表达式
     * - iterate_over.source
     * - aggregate.sources
     * - extract 变量(作为写入目标,不算引用)
     */
    fun collectReferencedVariables(workflow: WorkflowDefinition): Set<String> {
        val result = mutableSetOf<String>()
        for (step in workflow.workflow) {
            result += referencesIn(step.input)
            result += referencesIn(step.condition)
            step.groupChat?.initialMessage?.let { result += referencesIn(it) }
            step.agentBased?.goal?.let { result += referencesIn(it) }
            step.classifier?.input?.let { result += referencesIn(it) }
            step.iterateOver?.source?.let { result += referencesIn(it) }
            step.aggregate?.sources?.forEach { result += referencesIn(it) }
            step.subWorkflow?.inputs?.values?.forEach { result += referencesIn(it) }
        }
        return result
    }

    /**
     * 汇总 workflow 中所有被引用的步骤名(`{{steps.xxx.output}}`)。
     */
    fun collectReferencedStepOutputs(workflow: WorkflowDefinition): Set<String> {
        val result = mutableSetOf<String>()
        for (step in workflow.workflow) {
            result += stepOutputsReferencedIn(step.input)
            step.groupChat?.initialMessage?.let { result += stepOutputsReferencedIn(it) }
            step.agentBased?.goal?.let { result += stepOutputsReferencedIn(it) }
            step.classifier?.input?.let { result += stepOutputsReferencedIn(it) }
            step.iterateOver?.source?.let { result += stepOutputsReferencedIn(it) }
            step.aggregate?.sources?.forEach { result += stepOutputsReferencedIn(it) }
            step.subWorkflow?.inputs?.values?.forEach { result += stepOutputsReferencedIn(it) }
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
     * - `classifier.output_variable`
     * - `repeat_until.extract_variable`
     */
    fun collectWrittenVariables(workflow: WorkflowDefinition): Set<String> {
        val result = mutableSetOf<String>()
        for (step in workflow.workflow) {
            step.extract?.forEach { result += it.variable }
            step.iterateOver?.resultsVariable?.let { result += it }
            step.aggregate?.outputVariable?.let { result += it }
            step.classifier?.outputVariable?.let { result += it }
            step.repeatUntil?.extractVariable?.let { result += it }
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
            readInSelected += referencesIn(step.input)
            readInSelected += referencesIn(step.condition)
            step.groupChat?.initialMessage?.let { readInSelected += referencesIn(it) }
            step.agentBased?.goal?.let { readInSelected += referencesIn(it) }
            step.classifier?.input?.let { readInSelected += referencesIn(it) }
            step.iterateOver?.source?.let { readInSelected += referencesIn(it) }
            step.aggregate?.sources?.forEach { readInSelected += referencesIn(it) }
            step.subWorkflow?.inputs?.values?.forEach { readInSelected += referencesIn(it) }

            step.extract?.forEach { writtenInSelected += it.variable }
            step.iterateOver?.resultsVariable?.let { writtenInSelected += it }
            step.aggregate?.outputVariable?.let { writtenInSelected += it }
            step.classifier?.outputVariable?.let { writtenInSelected += it }
            step.repeatUntil?.extractVariable?.let { writtenInSelected += it }
        }

        val readOutsideAfter = mutableSetOf<String>()
        for (step in outside) {
            readOutsideAfter += referencesIn(step.input)
            readOutsideAfter += referencesIn(step.condition)
            step.groupChat?.initialMessage?.let { readOutsideAfter += referencesIn(it) }
            step.agentBased?.goal?.let { readOutsideAfter += referencesIn(it) }
            step.classifier?.input?.let { readOutsideAfter += referencesIn(it) }
            step.iterateOver?.source?.let { readOutsideAfter += referencesIn(it) }
            step.aggregate?.sources?.forEach { readOutsideAfter += referencesIn(it) }
            step.subWorkflow?.inputs?.values?.forEach { readOutsideAfter += referencesIn(it) }
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
