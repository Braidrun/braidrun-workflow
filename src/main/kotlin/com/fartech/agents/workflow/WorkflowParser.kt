package com.fartech.agents.workflow

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.File

private val parserLogger = KotlinLogging.logger {}

/**
 * 工作流 YAML 解析器
 *
 * 功能:
 * - 解析 YAML 格式的工作流定义
 * - 验证工作流语法正确性
 * - 检查循环依赖
 * - 验证引用完整性
 * - 验证模块契约
 * - 校验 sub_workflow 引用(可通过 [WorkflowResolver] 接入跨工作流校验)
 */
object WorkflowParser {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
            encodeDefaults = true
        )
    )

    /**
     * 从 YAML 字符串解析工作流定义。
     */
    fun parseYaml(yamlContent: String): WorkflowDefinition = parseYaml(yamlContent, NullWorkflowResolver)

    /**
     * 从 YAML 字符串解析工作流定义,并使用指定 resolver 做跨工作流校验。
     */
    fun parseYaml(yamlContent: String, resolver: WorkflowResolver): WorkflowDefinition {
        return try {
            val workflow = yaml.decodeFromString(WorkflowDefinition.serializer(), yamlContent)
            validateWorkflow(workflow, resolver)
            workflow
        } catch (e: SerializationException) {
            throw WorkflowValidationException("Failed to parse workflow YAML: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw WorkflowValidationException("Workflow validation failed: ${e.message}", e)
        }
    }

    /**
     * 从文件解析工作流定义。
     */
    fun parseFile(filePath: String): WorkflowDefinition = parseFile(filePath, NullWorkflowResolver)

    /**
     * 从文件解析工作流定义,并使用指定 resolver 做跨工作流校验。
     */
    fun parseFile(filePath: String, resolver: WorkflowResolver): WorkflowDefinition {
        val file = File(filePath)
        if (!file.exists()) {
            throw WorkflowValidationException("Workflow file not found: $filePath")
        }
        if (!file.canRead()) {
            throw WorkflowValidationException("Cannot read workflow file: $filePath")
        }

        val content = file.readText()
        return parseYaml(content, resolver)
    }

    /**
     * 验证工作流定义的完整性和正确性(无 resolver 版本)。
     *
     * 当 workflow 中存在 `sub_workflow` 步骤时,只做结构校验,不校验引用是否真实存在
     * (会输出 WARN 日志提示需要 resolver)。要做跨工作流的引用 / 循环 / 契约校验,请使用
     * 带 resolver 参数的重载。
     */
    fun validateWorkflow(workflow: WorkflowDefinition) {
        validateWorkflow(workflow, NullWorkflowResolver)
    }

    /**
     * 验证工作流定义,并使用指定 resolver 做跨工作流校验。
     */
    fun validateWorkflow(workflow: WorkflowDefinition, resolver: WorkflowResolver) {
        // 0. 验证模块契约(本工作流自身的契约一致性)
        validateModuleContract(workflow)

        // 1. 验证 Agent 引用
        validateAgentReferences(workflow)

        // 2. 验证 Agent 预设引用
        validateAgentPresets(workflow)

        // 3. 验证步骤依赖关系
        validateStepDependencies(workflow)

        // 4. 检查循环依赖
        detectCircularDependencies(workflow)

        // 5. 验证转换动作引用
        validateTransitionReferences(workflow)

        // 6. 验证并行执行配置
        validateParallelExecution(workflow)

        // 7. 验证条件表达式
        validateConditions(workflow)

        // 8. 验证 repeat_until 配置
        validateRepeatUntil(workflow)

        // 9. 验证 agent_based 配置
        validateAgentBased(workflow)

        // 10. 验证知识库配置
        validateKnowledgeBase(workflow)

        // 11. 验证 code 步骤配置
        validateCodeSteps(workflow)

        // 12. 验证 classifier 步骤配置
        validateClassifier(workflow)

        // 13. 验证 state_machine 配置
        validateStateMachines(workflow)

        // 14. 验证 extract 配置
        validateExtract(workflow)

        // 15. 验证 iterate_over 配置
        validateIterateOver(workflow)

        // 16. 验证 aggregate 配置
        validateAggregate(workflow)

        // 17. 验证 sub_workflow 配置(含跨工作流引用检查)
        validateSubWorkflows(workflow, resolver)

        // 18. 检测 sub_workflow 静态循环
        detectSubWorkflowCycles(workflow, resolver)
    }

    /**
     * 验证 Agent 预设引用是否有效
     */
    private fun validateAgentPresets(workflow: WorkflowDefinition) {
        workflow.agents.forEach { (name, agentDef) ->
            agentDef.preset?.let { presetId ->
                if (!AgentPresetRegistry.exists(presetId)) {
                    throw WorkflowValidationException(
                        "Agent '$name' references unknown preset '$presetId'. " +
                                "Available presets: ${AgentPresetRegistry.getAll().joinToString(", ") { it.id }}"
                    )
                }
            }
        }
    }

    /**
     * 验证所有步骤引用的 Agent 都已定义
     */
    private fun validateAgentReferences(workflow: WorkflowDefinition) {
        val definedAgents = workflow.agents.keys
        workflow.workflow.forEach { step ->
            step.referencedAgents.forEach { agentName ->
                if (!definedAgents.contains(agentName)) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' references undefined agent '$agentName'. " +
                                "Available agents: ${definedAgents.joinToString(", ")}"
                    )
                }
            }
        }
    }

    /**
     * 验证步骤依赖关系的有效性
     */
    private fun validateStepDependencies(workflow: WorkflowDefinition) {
        val allSteps = workflow.workflow.map { it.step }.toSet()

        workflow.workflow.forEach { step ->
            step.dependsOn.forEach { dependency ->
                if (!allSteps.contains(dependency)) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' depends on undefined step '$dependency'. " +
                                "Available steps: ${allSteps.joinToString(", ")}"
                    )
                }
            }
        }
    }

    /**
     * 检测工作流中的循环依赖
     */
    private fun detectCircularDependencies(workflow: WorkflowDefinition) {
        val graph = buildDependencyGraph(workflow)
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun hasCycle(step: String): Boolean {
            if (recursionStack.contains(step)) {
                return true
            }
            if (visited.contains(step)) {
                return false
            }

            visited.add(step)
            recursionStack.add(step)

            graph[step]?.forEach { dependency ->
                if (hasCycle(dependency)) {
                    return true
                }
            }

            recursionStack.remove(step)
            return false
        }

        workflow.workflow.forEach { step ->
            if (hasCycle(step.step)) {
                throw WorkflowValidationException(
                    "Circular dependency detected involving step '${step.step}'"
                )
            }
        }
    }

    /**
     * 构建依赖关系图
     */
    private fun buildDependencyGraph(workflow: WorkflowDefinition): Map<String, List<String>> {
        return workflow.workflow.associate { step ->
            step.step to step.dependsOn
        }
    }

    /**
     * 验证转换动作中引用的步骤存在
     */
    private fun validateTransitionReferences(workflow: WorkflowDefinition) {
        val allSteps = workflow.workflow.map { it.step }.toSet()

        workflow.workflow.forEach { step ->
            val validateAction = { action: TransitionAction ->
                action.next?.let { next ->
                    if (!allSteps.contains(next)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' transition references undefined step '$next'"
                        )
                    }
                }
                action.rollback?.let { rollback ->
                    if (!allSteps.contains(rollback)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' rollback references undefined step '$rollback'"
                        )
                    }
                }
                action.parallel?.forEach { parallelStep ->
                    if (!allSteps.contains(parallelStep)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' parallel transition references undefined step '$parallelStep'"
                        )
                    }
                }
            }

            step.onSuccess.forEach { validateAction(it) }
            step.onFailure.forEach { validateAction(it) }
        }
    }

    /**
     * 验证并行执行配置
     */
    private fun validateParallelExecution(workflow: WorkflowDefinition) {
        workflow.workflow.forEach { step ->
            step.parallel?.let { parallel ->
                if (step.agent.isNullOrBlank()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': parallel is only supported for single-agent steps"
                    )
                }
                if (parallel.tasks.isEmpty()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' has empty parallel tasks list"
                    )
                }
                parallel.maxParallel?.let { maxParallel ->
                    if (maxParallel <= 0) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' has invalid max_parallel value: $maxParallel (must be > 0)"
                        )
                    }
                }
            }
        }
    }

    /**
     * 验证条件表达式语法（简单验证）
     */
    private fun validateConditions(workflow: WorkflowDefinition) {
        workflow.workflow.forEach { step ->
            step.condition?.let { condition ->
                if (condition.isBlank()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' has blank condition"
                    )
                }
                // Validate format for conditions without unresolved templates
                if (!condition.contains("{{")) {
                    val parts = condition.trim().split(Regex("\\s+"), limit = 3)
                    if (parts.size < 3) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' has invalid condition format: '$condition' " +
                                "(expected 'variable operator value')"
                        )
                    }
                }
            }

            step.stateMachine?.states?.forEach { (stateName, stateDef) ->
                stateDef.transitions.forEach { transition ->
                    transition.condition?.let { transitionCondition ->
                        if (transitionCondition.isBlank()) {
                            throw WorkflowValidationException(
                                "Step '${step.step}' state '$stateName' has blank transition condition"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 验证 repeat_until 配置
     */
    private fun validateRepeatUntil(workflow: WorkflowDefinition) {
        val definedAgents = workflow.agents.keys

        workflow.workflow.forEach { step ->
            step.repeatUntil?.let { config ->
                // 验证终止条件不为空
                if (config.condition.isBlank()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' repeat_until has blank condition"
                    )
                }

                // 验证 evaluate_agent 引用的 agent 存在
                config.evaluateAgent?.let { evalAgent ->
                    if (!definedAgents.contains(evalAgent)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' repeat_until references undefined evaluate_agent '$evalAgent'. " +
                                    "Available agents: ${definedAgents.joinToString(", ")}"
                        )
                    }
                }

                // 验证 extract_pattern 和 extract_variable 必须同时提供或同时为空
                if ((config.extractPattern != null) != (config.extractVariable != null)) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' repeat_until: extract_pattern and extract_variable must both be provided or both be omitted"
                    )
                }

                // 验证正则表达式语法
                config.extractPattern?.let { pattern ->
                    try {
                        Regex(pattern)
                    } catch (e: Exception) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' repeat_until has invalid extract_pattern '$pattern': ${e.message}"
                        )
                    }
                }

                // repeat_until 不能用在 agent_based 步骤上
                if (step.isAgentBased) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': repeat_until cannot be used with agent_based steps"
                    )
                }
            }
        }
    }

    /**
     * 验证 agent_based 动态编排配置
     */
    private fun validateAgentBased(workflow: WorkflowDefinition) {
        val definedAgents = workflow.agents.keys

        workflow.workflow.forEach { step ->
            step.agentBased?.let { config ->
                // 验证所有 participant 都是已定义的 agent
                config.participants.forEach { participant ->
                    if (!definedAgents.contains(participant)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' agent_based references undefined participant '$participant'. " +
                                    "Available agents: ${definedAgents.joinToString(", ")}"
                        )
                    }
                }

                // 验证 goal 不为空
                if (config.goal.isBlank()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' agent_based has blank goal"
                    )
                }

                val orchestratorAgentRef = config.orchestrator.agentRef?.trim()?.takeIf { it.isNotEmpty() }
                val orchestratorPreset = config.orchestrator.preset?.trim()?.takeIf { it.isNotEmpty() }

                if (orchestratorAgentRef != null && orchestratorPreset != null) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' agent_based orchestrator cannot define both 'agent' and 'preset'"
                    )
                }

                orchestratorAgentRef?.let { agentName ->
                    if (!definedAgents.contains(agentName)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' agent_based orchestrator references undefined agent '$agentName'. " +
                                    "Available agents: ${definedAgents.joinToString(", ")}"
                        )
                    }
                }

                // 验证 orchestrator 的 preset 引用（如果有）
                orchestratorPreset?.let { presetId ->
                    if (!AgentPresetRegistry.exists(presetId)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' agent_based orchestrator references unknown preset '$presetId'. " +
                                    "Available presets: ${AgentPresetRegistry.getAll().joinToString(", ") { it.id }}"
                        )
                    }
                }
            }
        }
    }

    /**
     * 验证工作流级知识库配置
     */
    private fun validateKnowledgeBase(workflow: WorkflowDefinition) {
        val kb = workflow.knowledgeBase ?: return
        if (!kb.enabled) return

        // 验证 source_files 路径不为空（init 已校验，此处检查重复路径）
        val paths = kb.sourceFiles.map { it.path }
        val duplicates = paths.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw WorkflowValidationException(
                "knowledge_base source_files contains duplicate paths: ${duplicates.joinToString(", ")}"
            )
        }

        // 验证 embedding_provider 是已知的提供商
        val knownProviders = setOf("openai", "ollama")
        if (kb.embeddingProvider !in knownProviders) {
            throw WorkflowValidationException(
                "knowledge_base embedding_provider '${kb.embeddingProvider}' is not supported. " +
                        "Supported providers: ${knownProviders.joinToString(", ")}"
            )
        }
    }

    /**
     * 验证 code 步骤配置
     */
    private fun validateCodeSteps(workflow: WorkflowDefinition) {
        workflow.workflow.forEach { step ->
            step.code?.let { config ->
                // script_file 存在性不在解析时检查（运行时检查），此处验证语言和基本配置
                // CodeStepConfig.init 已校验 language 和 script/script_file 互斥

                // code 步骤不能使用 repeat_until（代码步骤是确定性的，循环无意义）
                if (step.repeatUntil != null) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': repeat_until cannot be used with code steps"
                    )
                }
            }
        }
    }

    /**
     * 验证 classifier 步骤配置
     */
    private fun validateClassifier(workflow: WorkflowDefinition) {
        val definedAgents = workflow.agents.keys

        workflow.workflow.forEach { step ->
            step.classifier?.let { config ->
                // 验证 agent 引用存在
                if (!definedAgents.contains(config.agent)) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' classifier references undefined agent '${config.agent}'. " +
                                "Available agents: ${definedAgents.joinToString(", ")}"
                    )
                }

                // 验证类别名称唯一
                val names = config.categories.map { it.name }
                val duplicates = names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                if (duplicates.isNotEmpty()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' classifier has duplicate category names: ${duplicates.joinToString(", ")}"
                    )
                }
            }
        }
    }

    /**
     * 验证 state_machine 复合步骤配置
     */
    private fun validateStateMachines(workflow: WorkflowDefinition) {
        val definedAgents = workflow.agents.keys

        workflow.workflow.forEach { step ->
            val config = step.stateMachine ?: return@forEach

            if (step.parallel != null) {
                throw WorkflowValidationException(
                    "Step '${step.step}': parallel cannot be used with state_machine steps"
                )
            }

            config.states.forEach { (stateKey, stateDef) ->
                if (stateKey.isBlank()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' state_machine has blank state key"
                    )
                }
                if (stateDef.name != stateKey) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' state '$stateKey' must use the same value for key and name"
                    )
                }

                stateDef.stepConfig?.let { stateStep ->
                    stateStep.referencedAgents.forEach { agentName ->
                        if (!definedAgents.contains(agentName)) {
                            throw WorkflowValidationException(
                                "Step '${step.step}' state '$stateKey' references undefined agent '$agentName'. " +
                                    "Available agents: ${definedAgents.joinToString(", ")}"
                            )
                        }
                    }

                    validateExtractConfigs(
                        owner = "Step '${step.step}' state '$stateKey'",
                        extracts = stateStep.extract
                    )
                }

                stateDef.transitions.forEach { transition ->
                    if (transition.event.isBlank()) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' state '$stateKey' has transition with blank event"
                        )
                    }
                    if (!config.states.containsKey(transition.target)) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' state '$stateKey' transitions to undefined state '${transition.target}'"
                        )
                    }
                }
            }
        }
    }

    /**
     * 验证 extract 配置
     */
    private fun validateExtract(workflow: WorkflowDefinition) {
        workflow.workflow.forEach { step ->
            validateExtractConfigs(owner = "Step '${step.step}'", extracts = step.extract)
        }
    }

    private fun validateExtractConfigs(owner: String, extracts: List<ExtractConfig>?) {
        extracts?.forEach { config ->
            config.pattern?.let { pattern ->
                try {
                    Regex(pattern)
                } catch (e: Exception) {
                    throw WorkflowValidationException(
                        "$owner extract has invalid pattern '$pattern': ${e.message}"
                    )
                }
            }

            config.jsonPath?.let { jsonPath ->
                if (!jsonPath.startsWith("$")) {
                    throw WorkflowValidationException(
                        "$owner extract has invalid json_path '$jsonPath': must start with '$'"
                    )
                }
            }
        }

        extracts?.let { configs ->
            val variables = configs.map { it.variable }
            val duplicates = variables.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            if (duplicates.isNotEmpty()) {
                throw WorkflowValidationException(
                    "$owner extract has duplicate variable names: ${duplicates.joinToString(", ")}"
                )
            }
        }
    }

    /**
     * 验证 iterate_over 配置
     */
    private fun validateIterateOver(workflow: WorkflowDefinition) {
        workflow.workflow.forEach { step ->
            step.iterateOver?.let {
                if (!step.isCode && step.agent.isNullOrBlank()) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': iterate_over is only supported for single-agent or code steps"
                    )
                }

                if (step.parallel != null) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': iterate_over cannot be combined with step-level parallel"
                    )
                }

                if (step.repeatUntil != null) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': iterate_over cannot be combined with repeat_until"
                    )
                }

                if (step.retry != null) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': iterate_over cannot be combined with retry"
                    )
                }

                if (step.manualApproval != null) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': iterate_over cannot be combined with manual_approval"
                    )
                }

                if (step.timeoutSeconds != null) {
                    throw WorkflowValidationException(
                        "Step '${step.step}': iterate_over cannot be combined with timeout_seconds"
                    )
                }
            }
        }
    }

    /**
     * 验证 aggregate 配置
     */
    private fun validateAggregate(workflow: WorkflowDefinition) {
        workflow.workflow.forEach { step ->
            step.aggregate?.let { config ->
                // 验证 sources 不为空字符串
                config.sources.forEachIndexed { index, source ->
                    if (source.isBlank()) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' aggregate source at index $index is blank"
                        )
                    }
                }
            }
        }
    }

    /**
     * 验证 workflow 自身的模块契约一致性。
     *
     * 校验项:
     * - 每个 input 的 mapsToVariable / name 必须存在于 workflow.variables 或 variableTypes
     * - 每个 output 的 source 模板必须可解析(引用的 var / step 名称存在)
     * - input / output 重名(由 data class init 已检查,这里再次防御)
     * - required=true 不允许有 default(由 data class init 已检查)
     * - type=enum 必须给 allowed_values(由 data class init 已检查)
     * - deprecated=true 时只 warn 不 fail
     */
    private fun validateModuleContract(workflow: WorkflowDefinition) {
        val module = workflow.module ?: return
        if (!module.enabled) return

        if (module.deprecated) {
            parserLogger.warn {
                "Workflow '${workflow.name}' module is marked as deprecated${module.deprecationNote?.let { ": $it" }.orEmpty()}"
            }
        }

        val declaredVars = workflow.variables.keys + workflow.variableTypes.keys
        val declaredSteps = workflow.workflow.map { it.step }.toSet()

        // inputs: mapsToVariable / name 必须落在 workflow.variables 或 variableTypes 上
        for (input in module.inputs) {
            val varKey = input.variableKey()
            if (varKey !in declaredVars) {
                throw WorkflowValidationException(
                    "Workflow '${workflow.name}' module input '${input.name}' maps to variable '$varKey' which is not declared in 'variables' or 'variable_types'"
                )
            }
        }

        // outputs: source 模板的引用都必须可解析
        val varRefPattern = Regex("""\{\{var:([A-Za-z_][A-Za-z0-9_]*)\}\}""")
        val stepRefPattern = Regex("""\{\{steps\.([A-Za-z_][A-Za-z0-9_]*)\.output\}\}""")
        val barePattern = Regex("""\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}""")

        for (output in module.outputs) {
            varRefPattern.findAll(output.source).forEach { match ->
                val name = match.groupValues[1]
                if (name !in declaredVars) {
                    throw WorkflowValidationException(
                        "Workflow '${workflow.name}' module output '${output.name}' source references undeclared variable '${name}'"
                    )
                }
            }
            stepRefPattern.findAll(output.source).forEach { match ->
                val name = match.groupValues[1]
                if (name !in declaredSteps) {
                    throw WorkflowValidationException(
                        "Workflow '${workflow.name}' module output '${output.name}' source references undefined step '${name}'"
                    )
                }
            }
            barePattern.findAll(output.source).forEach { match ->
                val name = match.groupValues[1]
                // 裸模板 {{xxx}} 既可能是 step 名又可能是变量名,任一存在即可
                if (name !in declaredVars && name !in declaredSteps) {
                    throw WorkflowValidationException(
                        "Workflow '${workflow.name}' module output '${output.name}' bare reference '{{$name}}' does not match any variable or step"
                    )
                }
            }
        }
    }

    /**
     * 验证 sub_workflow 步骤配置(含跨工作流引用检查,如果 resolver 不是 Null)。
     *
     * 校验项:
     * - exactly-one of (workflow_id / path / name) → 已由 SubWorkflowConfig.init 检查
     * - 不允许同 step 配 iterate_over / repeat_until / parallel
     * - extract 是允许的(允许从 child 输出 JSON 中提取字段)
     * - manual_approval / retry 是允许的(包装整个子工作流执行)
     * - 通过 resolver 解析 child 后:
     *   - 若 child 有 module 契约 → 严格校验 inputs key 集合、required 是否提供、outputs key 是否声明
     *   - 若 child 没有 module 契约 → 宽松模式,只校验 required_inputs 列表里的 key 在 sub_workflow.inputs 中存在
     */
    private fun validateSubWorkflows(workflow: WorkflowDefinition, resolver: WorkflowResolver) {
        val hasAnySubWorkflow = workflow.workflow.any { it.isSubWorkflow }
        if (!hasAnySubWorkflow) return

        if (resolver === NullWorkflowResolver) {
            parserLogger.warn {
                "Workflow '${workflow.name}' contains sub_workflow steps but no WorkflowResolver was supplied; cross-workflow references are NOT validated. Use validateWorkflow(workflow, resolver) for full validation."
            }
        }

        for (step in workflow.workflow) {
            val config = step.subWorkflow ?: continue

            // 1. 组合约束
            if (step.iterateOver != null) {
                throw WorkflowValidationException(
                    "Step '${step.step}': sub_workflow cannot be combined with iterate_over (deferred to Phase 4)"
                )
            }
            if (step.repeatUntil != null) {
                throw WorkflowValidationException(
                    "Step '${step.step}': sub_workflow cannot be combined with repeat_until (deferred to Phase 4)"
                )
            }
            if (step.parallel != null) {
                throw WorkflowValidationException(
                    "Step '${step.step}': sub_workflow cannot be combined with step-level parallel"
                )
            }

            // 2. 跨工作流校验(需要 resolver)
            if (resolver === NullWorkflowResolver) continue

            val resolved = try {
                resolver.resolve(config.toReference())
            } catch (e: WorkflowValidationException) {
                when (config.missingReferencePolicy) {
                    SubWorkflowMissingReferencePolicy.ERROR ->
                        throw WorkflowValidationException(
                            "Step '${step.step}' sub_workflow reference cannot be resolved: ${e.message}",
                            e
                        )

                    SubWorkflowMissingReferencePolicy.WARN -> {
                        parserLogger.warn {
                            "Step '${step.step}' sub_workflow reference (${config.identityLabel()}) cannot be resolved during static validation: ${e.message}"
                        }
                        continue
                    }

                    SubWorkflowMissingReferencePolicy.SKIP_STEP -> {
                        parserLogger.warn {
                            "Step '${step.step}' sub_workflow reference (${config.identityLabel()}) cannot be resolved; will be skipped at runtime"
                        }
                        continue
                    }
                }
            }
            val childDef = resolved.definition
            val childModule = childDef.module?.takeIf { it.enabled }

            if (childModule != null) {
                // 严格模式
                validateSubWorkflowAgainstContract(step, config, childDef.name, childModule)
            } else {
                // 宽松模式
                validateSubWorkflowLooseMode(step, config, childDef)
            }
        }
    }

    /**
     * 严格模式: child 有契约,父 sub_workflow 必须严格匹配。
     */
    private fun validateSubWorkflowAgainstContract(
        step: WorkflowStep,
        config: SubWorkflowConfig,
        childName: String,
        contract: WorkflowModuleContract
    ) {
        val inputNames = contract.inputs.map { it.name }.toSet()
        val outputNames = contract.outputs.map { it.name }.toSet()
        val requiredInputs = contract.requiredInputNames().toSet()

        // a. 父 inputs key 必须落在契约 input 集合里
        for (key in config.inputs.keys) {
            if (key !in inputNames) {
                throw WorkflowValidationException(
                    "Step '${step.step}' sub_workflow input '$key' is not declared in module contract of '$childName' (allowed: ${inputNames.sorted()})"
                )
            }
        }

        // b. 所有 required input 必须由父提供
        for (req in requiredInputs) {
            if (req !in config.inputs.keys) {
                throw WorkflowValidationException(
                    "Step '${step.step}' sub_workflow missing required input '$req' (declared in module contract of '$childName')"
                )
            }
        }

        // c. type=enum input 的值如果是字面量,必须在 allowed_values 中
        // (含模板的值在运行时再校验)
        for ((key, value) in config.inputs) {
            val spec = contract.findInput(key) ?: continue
            if (spec.type == "enum" && !value.contains("{{")) {
                if (spec.allowedValues != null && value !in spec.allowedValues) {
                    throw WorkflowValidationException(
                        "Step '${step.step}' sub_workflow input '$key'='$value' is not in allowed_values ${spec.allowedValues}"
                    )
                }
            }
            if (spec.type == "number" && !value.contains("{{")) {
                val num = value.toDoubleOrNull()
                    ?: throw WorkflowValidationException(
                        "Step '${step.step}' sub_workflow input '$key' expects type=number but got '$value'"
                    )
                spec.min?.let {
                    if (num < it) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' sub_workflow input '$key'=$num is below min=$it"
                        )
                    }
                }
                spec.max?.let {
                    if (num > it) {
                        throw WorkflowValidationException(
                            "Step '${step.step}' sub_workflow input '$key'=$num is above max=$it"
                        )
                    }
                }
            }
        }

        // d. 父 outputs 引用的 key 必须在契约 output 中(value 是 child output 名)
        for ((parentVar, childOutputName) in config.outputs) {
            // 兼容用模板形式的旧写法 {{var:xxx}}: 跳过此校验,作为宽松回退
            if (childOutputName.contains("{{")) continue
            if (childOutputName !in outputNames) {
                throw WorkflowValidationException(
                    "Step '${step.step}' sub_workflow output '$parentVar' maps to child output '$childOutputName' which is not declared in module contract of '$childName' (allowed: ${outputNames.sorted()})"
                )
            }
        }

        if (contract.deprecated) {
            parserLogger.warn {
                "Step '${step.step}' references deprecated module '$childName'${contract.deprecationNote?.let { ": $it" }.orEmpty()}"
            }
        }
    }

    /**
     * 宽松模式: child 没有契约,只做手写 required_inputs 的最小校验。
     */
    private fun validateSubWorkflowLooseMode(
        step: WorkflowStep,
        config: SubWorkflowConfig,
        childDef: WorkflowDefinition
    ) {
        // a. required_inputs 列表中的每一项必须出现在 sub_workflow.inputs
        for (req in config.requiredInputs) {
            if (req !in config.inputs.keys) {
                throw WorkflowValidationException(
                    "Step '${step.step}' sub_workflow declared required input '$req' but did not supply it in 'inputs'"
                )
            }
        }

        // b. inputs 的 key 最好是 child 已声明的变量(只 warn,不 fail)
        val childVars = childDef.variables.keys + childDef.variableTypes.keys
        for (key in config.inputs.keys) {
            if (key !in childVars) {
                parserLogger.warn {
                    "Step '${step.step}' sub_workflow input '$key' is not declared in child workflow '${childDef.name}' variables (loose mode warning)"
                }
            }
        }
    }

    /**
     * 静态检测 sub_workflow 嵌套循环 (best-effort).
     *
     * 用 resolver 加载每个 child,DFS 构建调用图,检测是否存在 A→B→A 这种循环。
     * 使用 [WorkflowResolver.identityKey] 作为节点 key 保证一致性。
     *
     * **Scope**: This is a pre-execution safety net. It cannot cover every runtime scenario:
     *   - When a child workflow cannot be resolved (missing file / missing network reference
     *     under `missing_reference_policy=warn`), we warn and skip that branch — cycles
     *     through the unresolvable node escape static detection.
     *   - When `identityKey` collapses multiple references onto the same node, it is the
     *     caller's identity function that must be unambiguous.
     *
     * **Runtime backstop**: `WorkflowExecutor.executeSubWorkflowStep` also tracks the live
     * call stack via `SubWorkflowStackElement` in coroutine context and rejects recursion
     * independently. Even if static cycle detection lets something slip through, the
     * runtime will not — the two layers are defense-in-depth, not duplicates.
     */
    private fun detectSubWorkflowCycles(workflow: WorkflowDefinition, resolver: WorkflowResolver) {
        if (resolver === NullWorkflowResolver) return  // 无 resolver 时跳过(已在 validateSubWorkflows 中 warn 过)
        if (workflow.workflow.none { it.isSubWorkflow }) return

        val visited = mutableSetOf<String>()
        val stack = linkedSetOf<String>()
        val rootIdentity = "<root:${workflow.name}>"
        val skippedReferences = mutableListOf<String>()

        fun dfs(def: WorkflowDefinition, identity: String) {
            if (identity in stack) {
                val cycle = stack.toList().dropWhile { it != identity } + identity
                throw WorkflowValidationException(
                    "Cyclic sub_workflow chain detected: ${cycle.joinToString(" → ")}"
                )
            }
            if (identity in visited) return
            stack += identity
            try {
                def.workflow.forEach { step ->
                    val config = step.subWorkflow ?: return@forEach
                    val resolved = try {
                        resolver.resolve(config.toReference())
                    } catch (e: WorkflowValidationException) {
                        // 记录未解析的引用,以便发出一次性 warning。
                        // 即使我们跳过了静态检查,runtime 仍会通过 SubWorkflowStackElement
                        // 独立地阻止递归/深度超限。
                        skippedReferences += "${step.step} → ${config.toReference()}"
                        return@forEach
                    }
                    dfs(resolved.definition, resolved.identity)
                }
            } finally {
                stack -= identity
                visited += identity
            }
        }

        dfs(workflow, rootIdentity)

        if (skippedReferences.isNotEmpty()) {
            parserLogger.warn {
                "Static sub_workflow cycle detection is incomplete for workflow '${workflow.name}': " +
                    "the following references could not be resolved and were skipped " +
                    "(runtime cycle guard remains active): ${skippedReferences.joinToString("; ")}"
            }
        }
    }

    /**
     * 将工作流定义序列化为 YAML 字符串
     */
    fun toYaml(workflow: WorkflowDefinition): String {
        return yaml.encodeToString(WorkflowDefinition.serializer(), workflow)
    }

    /**
     * 将工作流定义保存到文件
     */
    fun saveToFile(workflow: WorkflowDefinition, filePath: String) {
        val yamlContent = toYaml(workflow)
        File(filePath).writeText(yamlContent)
    }

    /**
     * 获取工作流的拓扑排序（执行顺序）
     */
    fun getTopologicalOrder(workflow: WorkflowDefinition): List<WorkflowStep> {
        val graph = buildDependencyGraph(workflow)
        val inDegree = mutableMapOf<String, Int>()
        val stepMap = workflow.workflow.associateBy { it.step }

        // 初始化入度
        workflow.workflow.forEach { step ->
            inDegree[step.step] = 0
        }

        // 计算入度
        workflow.workflow.forEach { step ->
            step.dependsOn.forEach { dependency ->
                inDegree[step.step] = (inDegree[step.step] ?: 0) + 1
            }
        }

        // 拓扑排序
        val queue = ArrayDeque<String>()
        inDegree.forEach { (step, degree) ->
            if (degree == 0) {
                queue.add(step)
            }
        }

        val sorted = mutableListOf<WorkflowStep>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted.add(stepMap.getValue(current))

            // 找到所有依赖当前步骤的步骤
            workflow.workflow.forEach { step ->
                if (step.dependsOn.contains(current)) {
                    inDegree[step.step] = (inDegree[step.step] ?: 1) - 1
                    if (inDegree[step.step] == 0) {
                        queue.add(step.step)
                    }
                }
            }
        }

        if (sorted.size != workflow.workflow.size) {
            throw WorkflowValidationException("Unable to determine execution order - circular dependency detected")
        }

        return sorted
    }

    /**
     * 获取工作流摘要信息
     */
    fun getWorkflowSummary(workflow: WorkflowDefinition): String = buildString {
        appendLine("Workflow: ${workflow.name} (v${workflow.version})")
        workflow.description?.let { appendLine("Description: $it") }
        appendLine()
        appendLine("Agents: ${workflow.agents.size}")
        workflow.agents.forEach { (name, agent) ->
            val info = if (agent.preset != null) {
                "preset: ${agent.preset}" + if (agent.overrides.isNotEmpty()) ", +${agent.overrides.size} overrides" else ""
            } else {
                "${agent.type}, strategy: ${agent.strategy}"
            }
            appendLine("  - $name ($info)")
        }
        appendLine()
        appendLine("Steps: ${workflow.workflow.size}")
        workflow.workflow.forEach { step ->
            appendLine("  - ${step.step} (${step.displayAgentName})")
            if (step.dependsOn.isNotEmpty()) {
                appendLine("    depends on: ${step.dependsOn.joinToString(", ")}")
            }
            step.repeatUntil?.let {
                appendLine("    repeat_until: ${it.condition} (max ${it.maxIterations} iterations)")
            }
            if (step.isAgentBased) {
                step.agentBased?.let { ab ->
                    appendLine("    agent_based: goal='${ab.goal.take(80)}', participants=${ab.participants}, max_steps=${ab.maxSteps}")
                }
            }
        }
        workflow.knowledgeBase?.let { kb ->
            if (kb.enabled) {
                appendLine()
                appendLine("Knowledge Base: ENABLED")
                appendLine("  storage_dir: ${kb.storageDir ?: "(auto)"}")
                appendLine("  embedding_model: ${kb.embeddingModel} (${kb.embeddingProvider})")
                appendLine("  auto_index_outputs: ${kb.autoIndexOutputs}")
                appendLine("  auto_inject_rag_tools: ${kb.autoInjectRagTools}")
                if (kb.sourceFiles.isNotEmpty()) {
                    appendLine("  source_files: ${kb.sourceFiles.size}")
                    kb.sourceFiles.forEach { sf ->
                        appendLine("    - ${sf.path}${if (sf.tags.isNotBlank()) " (tags: ${sf.tags})" else ""}")
                    }
                }
            }
        }

        // 模块契约信息
        workflow.module?.takeIf { it.enabled }?.let { module ->
            appendLine()
            appendLine("Module Contract: ${module.displayName ?: workflow.name} (contract v${module.contractVersion})")
            module.category?.let { appendLine("  category: $it") }
            module.description?.let { appendLine("  description: $it") }
            if (module.deprecated) appendLine("  ⚠ DEPRECATED${module.deprecationNote?.let { ": $it" }.orEmpty()}")
            if (module.inputs.isNotEmpty()) {
                appendLine("  inputs (${module.inputs.size}):")
                module.inputs.forEach { input ->
                    val flags = mutableListOf<String>()
                    if (input.required) flags.add("required") else flags.add("optional")
                    input.default?.let { flags.add("default=$it") }
                    appendLine("    - ${input.name}: ${input.type} [${flags.joinToString(", ")}]")
                }
            }
            if (module.outputs.isNotEmpty()) {
                appendLine("  outputs (${module.outputs.size}):")
                module.outputs.forEach { output ->
                    appendLine("    - ${output.name}: ${output.type} ← ${output.source}")
                }
            }
        }

        // 统计新特性使用情况
        val codeSteps = workflow.workflow.count { it.isCode }
        val classifierSteps = workflow.workflow.count { it.isClassifier }
        val stateMachineSteps = workflow.workflow.count { it.isStateMachine }
        val subWorkflowSteps = workflow.workflow.count { it.isSubWorkflow }
        val extractSteps = workflow.workflow.count { it.extract != null }
        val iterateSteps = workflow.workflow.count { it.iterateOver != null }
        val aggregateSteps = workflow.workflow.count { it.aggregate != null }
        if (codeSteps + classifierSteps + stateMachineSteps + subWorkflowSteps + extractSteps + iterateSteps + aggregateSteps > 0) {
            appendLine()
            appendLine("Enhanced Features:")
            if (codeSteps > 0) appendLine("  code steps: $codeSteps")
            if (classifierSteps > 0) appendLine("  classifier steps: $classifierSteps")
            if (stateMachineSteps > 0) appendLine("  state machine steps: $stateMachineSteps")
            if (subWorkflowSteps > 0) {
                appendLine("  sub_workflow steps: $subWorkflowSteps")
                workflow.workflow.filter { it.isSubWorkflow }.forEach { step ->
                    appendLine("    - ${step.step} → ${step.subWorkflow?.identityLabel() ?: "?"}")
                }
            }
            if (extractSteps > 0) appendLine("  steps with extract: $extractSteps")
            if (iterateSteps > 0) appendLine("  steps with iterate_over: $iterateSteps")
            if (aggregateSteps > 0) appendLine("  steps with aggregate: $aggregateSteps")
        }
    }
}
