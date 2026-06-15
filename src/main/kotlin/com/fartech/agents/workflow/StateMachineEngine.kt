package com.fartech.agents.workflow

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 状态机工作流引擎
 *
 * 支持复杂的状态转换逻辑，适用于需要精细状态控制的工作流场景。
 *
 * 核心功能：
 * - 事件驱动的状态转换
 * - 条件守卫（Guard Conditions）
 * - 状态进入/退出动作
 * - 转换动作
 * - 最终状态检测
 */
class StateMachineEngine(
    private val config: StateMachineConfig,
    private val context: WorkflowExecutionContext
) {
    private var currentState: String = config.initialState
    private val stateHistory = mutableListOf<StateTransitionRecord>()
    private var initialized = false

    /**
     * 初始化状态机，执行初始状态的 on_enter。
     */
    suspend fun initialize() {
        ensureInitialized()
    }

    /**
     * 触发事件，尝试状态转换
     */
    suspend fun triggerEvent(event: String): StateTransitionResult {
        ensureInitialized()

        val currentStateDef = config.states[currentState]
            ?: throw WorkflowExecutionException("Current state '$currentState' not found in configuration")

        logger.info { "State machine: Current state '$currentState', event '$event'" }

        // 查找匹配的转换
        val candidates = currentStateDef.transitions.filter { it.event == event }
        if (candidates.isEmpty()) {
            return StateTransitionResult(
                success = false,
                fromState = currentState,
                toState = currentState,
                event = event,
                message = "No transition found for event '$event' in state '$currentState'"
            )
        }

        val transition = candidates.firstOrNull { transition ->
            evaluateWorkflowCondition(transition.condition, context, fallbackToStepOutput = true)
        } ?: run {
            val conditionSummary = candidates.mapNotNull { it.condition }.joinToString(" OR ")
            logger.warn { "Transition conditions not met for event '$event' in state '$currentState': $conditionSummary" }
            return StateTransitionResult(
                success = false,
                fromState = currentState,
                toState = currentState,
                event = event,
                message = if (conditionSummary.isBlank()) {
                    "No eligible transition found for event '$event' in state '$currentState'"
                } else {
                    "Transition conditions not met for event '$event': $conditionSummary"
                }
            )
            }

        // 验证目标状态存在
        val targetStateDef = config.states[transition.target]
            ?: throw WorkflowExecutionException("Target state '${transition.target}' not found in configuration")

        // 执行状态退出动作
        executeActions(currentStateDef.onExit, "exit:$currentState")

        val oldState = currentState
        currentState = transition.target

        // 执行转换动作
        executeActions(transition.actions, "transition:$event")

        // 执行状态进入动作
        executeActions(targetStateDef.onEnter, "enter:$currentState")

        // 记录状态转换历史
        val record = StateTransitionRecord(
            timestamp = System.currentTimeMillis(),
            fromState = oldState,
            toState = currentState,
            event = event,
            conditionMet = true
        )
        stateHistory.add(record)

        logger.info { "State transition: $oldState -> $currentState (event: $event)" }

        return StateTransitionResult(
            success = true,
            fromState = oldState,
            toState = currentState,
            event = event,
            message = "Successfully transitioned from $oldState to $currentState"
        )
    }

    /**
     * 执行动作列表
     */
    private suspend fun executeActions(actions: List<TransitionAction>, contextStr: String) {
        actions.forEach { action ->
            logger.debug { "Executing action in $contextStr: $action" }
            when (action.action) {
                "goto" -> {
                    // goto 动作在 triggerEvent 中处理
                }

                "log" -> {
                    action.message?.let { logger.info { resolveTemplate(it) } }
                }

                "set_variable" -> {
                    action.key?.let { key ->
                        action.value?.let { value ->
                            context.variables[key] = resolveTemplate(value)
                        }
                    }
                }

                else -> {
                    logger.warn { "Unknown action type: ${action.action}" }
                }
            }
        }
    }

    /**
     * 执行第一次事件前懒初始化初始状态。
     */
    private suspend fun ensureInitialized() {
        if (initialized) return

        val initialStateDef = config.states[currentState]
            ?: throw WorkflowExecutionException("Initial state '$currentState' not found in configuration")
        executeActions(initialStateDef.onEnter, "enter:$currentState")
        initialized = true
    }

    private fun resolveTemplate(template: String): String =
        WorkflowTemplateResolver.resolve(template, context)

    /**
     * 检查是否处于最终状态
     */
    fun isFinalState(): Boolean = config.finalStates.contains(currentState)

    /**
     * 获取当前状态
     */
    fun getCurrentState(): String = currentState

    /**
     * 获取状态转换历史
     */
    fun getHistory(): List<StateTransitionRecord> = stateHistory.toList()

    /**
     * 重置状态机到初始状态
     */
    fun reset() {
        currentState = config.initialState
        stateHistory.clear()
        initialized = false
    }
}

/**
 * 状态转换记录
 */
data class StateTransitionRecord(
    val timestamp: Long,
    val fromState: String,
    val toState: String,
    val event: String,
    val conditionMet: Boolean
)

/**
 * 状态转换结果
 */
data class StateTransitionResult(
    val success: Boolean,
    val fromState: String,
    val toState: String,
    val event: String,
    val message: String
)
