package com.fartech.agents.workflow

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 工作流监控器
 *
 * 提供实时监控和指标收集功能：
 * - 执行进度跟踪
 * - 性能指标收集
 * - 实时状态查询
 * - 事件通知
 */
object WorkflowMonitor {
    private val activeExecutions = ConcurrentHashMap<String, WorkflowMetrics>()
    private val completedExecutions = mutableListOf<WorkflowMetrics>()
    private val maxCompletedHistory = 1000 // 保留最近 1000 个已完成的执行记录

    /**
     * 开始监控一个工作流执行
     */
    fun startExecution(executionId: String, workflowName: String, totalSteps: Int): WorkflowMetrics {
        val metrics = WorkflowMetrics(
            executionId = executionId,
            workflowName = workflowName,
            startTime = System.currentTimeMillis(),
            totalSteps = totalSteps
        )
        activeExecutions[executionId] = metrics
        logger.info { "Started monitoring execution: $executionId for workflow: $workflowName" }
        return metrics
    }

    /**
     * 开始监控一个步骤执行
     *
     * @param parentStepName 子工作流场景下的父 step 名称(null 表示这是顶层步骤)
     * @param subWorkflowName 子工作流的名称(用于 UI 显示 "🧩 调用模块: xxx")
     */
    fun startStep(
        executionId: String,
        stepName: String,
        agentName: String? = null,
        parentStepName: String? = null,
        subWorkflowName: String? = null
    ) {
        activeExecutions[executionId]?.let { metrics ->
            val stepMetrics = StepMetrics(
                stepName = stepName,
                startTime = System.currentTimeMillis(),
                agentName = agentName,
                parentStepName = parentStepName,
                subWorkflowName = subWorkflowName
            )
            metrics.stepMetrics[stepName] = stepMetrics
            logger.debug {
                "Started step: $stepName in execution: $executionId" +
                    if (parentStepName != null) " (child of $parentStepName)" else ""
            }
        }
    }

    /**
     * 获取嵌套的 step 树:把扁平 stepMetrics 按 parentStepName 分组,返回 parent → children 的 map。
     * 顶层 step (parentStepName == null) 用 key "" 收集。
     */
    fun getNestedSteps(executionId: String): Map<String, List<StepMetrics>> {
        val metrics = activeExecutions[executionId]
            ?: synchronized(completedExecutions) {
                completedExecutions.firstOrNull { it.executionId == executionId }
            }
            ?: return emptyMap()
        return metrics.stepMetrics.values.groupBy { it.parentStepName ?: "" }
    }

    /**
     * 完成一个步骤执行
     */
    fun completeStep(
        executionId: String,
        stepName: String,
        success: Boolean,
        error: String? = null,
        output: String? = null
    ) {
        activeExecutions[executionId]?.let { metrics ->
            metrics.stepMetrics[stepName]?.let { stepMetrics ->
                stepMetrics.endTime = System.currentTimeMillis()
                stepMetrics.status = if (success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED
                stepMetrics.error = error
                stepMetrics.output = output

                // Parallel steps complete from concurrent coroutines; a plain ++
                // on the shared metrics object loses counts.
                synchronized(metrics) {
                    if (success) {
                        metrics.completedSteps++
                    } else {
                        metrics.failedSteps++
                    }
                }

                logger.debug { "Completed step: $stepName in execution: $executionId (success: $success)" }
            }
        }
    }

    /**
     * 跳过一个步骤
     */
    fun skipStep(executionId: String, stepName: String) {
        activeExecutions[executionId]?.let { metrics ->
            val stepMetrics = metrics.stepMetrics.getOrPut(stepName) {
                StepMetrics(stepName = stepName, startTime = System.currentTimeMillis())
            }
            stepMetrics.endTime = System.currentTimeMillis()
            stepMetrics.status = ExecutionStatus.CANCELLED
            synchronized(metrics) { metrics.skippedSteps++ }
            logger.debug { "Skipped step: $stepName in execution: $executionId" }
        }
    }

    /**
     * 记录 Agent 执行事件（工具调用、LLM 消息等）
     *
     * Delegates to [StepMetrics.addEventBounded], which atomically checks the size cap and
     * performs the drop-oldest + append under a single monitor. The previous implementation
     * did `events.size` + `indexOfFirst` + `removeAt` + `add` on a plain `mutableListOf()`
     * which was unsafe against concurrent writes from different agent coroutines and
     * concurrent reads by the SSE / UI subscriber.
     */
    fun addEvent(executionId: String, stepName: String, event: AgentEvent) {
        val allSteps = activeExecutions[executionId]?.stepMetrics ?: return
        // Sub-agent runs emit events under derived names ("step:classifier",
        // "step:group_chat:round1:alice", ...) that only the state-machine path
        // registers via startStep. Fall back to the nearest registered ancestor
        // (trimming ":suffix" segments) so token accounting and traces aren't
        // silently dropped for classifier/group-chat/parallel/iterate/evaluate runs.
        var name = stepName
        var stepMetrics = allSteps[name]
        while (stepMetrics == null) {
            val cut = name.lastIndexOf(':')
            if (cut <= 0) return
            name = name.substring(0, cut)
            stepMetrics = allSteps[name]
        }
        stepMetrics.addEventBounded(event, MAX_EVENTS_PER_STEP)
    }

    /** Maximum events stored per step to prevent unbounded memory growth. */
    private const val MAX_EVENTS_PER_STEP = 500

    /**
     * 记录步骤重试
     */
    fun recordRetry(executionId: String, stepName: String) {
        activeExecutions[executionId]?.let { metrics ->
            metrics.stepMetrics[stepName]?.retryCount =
                (metrics.stepMetrics[stepName]?.retryCount ?: 0) + 1
        }
    }

    /**
     * 设置步骤状态为等待审批
     */
    fun awaitingApproval(executionId: String, stepName: String) {
        activeExecutions[executionId]?.let { metrics ->
            metrics.stepMetrics[stepName]?.status = ExecutionStatus.AWAITING_APPROVAL
            metrics.status = ExecutionStatus.AWAITING_APPROVAL
            logger.info { "Step $stepName in execution $executionId is awaiting approval" }
        }
    }

    /**
     * 完成一个工作流执行
     */
    fun completeExecution(executionId: String, success: Boolean) {
        activeExecutions.remove(executionId)?.let { metrics ->
            metrics.endTime = System.currentTimeMillis()
            metrics.status = if (success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED

            // 添加到已完成列表
            synchronized(completedExecutions) {
                completedExecutions.add(0, metrics)
                // 保持历史记录在限制范围内
                if (completedExecutions.size > maxCompletedHistory) {
                    completedExecutions.removeAt(completedExecutions.size - 1)
                }
            }

            logger.info {
                "Completed execution: $executionId (success: $success, duration: ${metrics.getDuration()}ms, " +
                        "completed: ${metrics.completedSteps}/${metrics.totalSteps}, failed: ${metrics.failedSteps})"
            }
        }
    }

    /**
     * 取消一个工作流执行
     */
    fun cancelExecution(executionId: String) {
        activeExecutions.remove(executionId)?.let { metrics ->
            metrics.endTime = System.currentTimeMillis()
            metrics.status = ExecutionStatus.CANCELLED

            synchronized(completedExecutions) {
                completedExecutions.add(0, metrics)
                // Same bound as completeExecution — repeated cancellations must not
                // grow the history past maxCompletedHistory.
                if (completedExecutions.size > maxCompletedHistory) {
                    completedExecutions.removeAt(completedExecutions.size - 1)
                }
            }

            logger.info { "Cancelled execution: $executionId" }
        }
    }

    /**
     * 清理指定 executionId 的历史监控数据，便于同一 executionId 重新执行。
     */
    fun resetExecution(executionId: String) {
        activeExecutions.remove(executionId)
        synchronized(completedExecutions) {
            completedExecutions.removeAll { it.executionId == executionId }
        }
        logger.info { "Reset workflow monitoring data for execution: $executionId" }
    }

    /**
     * 获取执行指标
     */
    fun getMetrics(executionId: String): WorkflowMetrics? {
        return activeExecutions[executionId] ?: synchronized(completedExecutions) {
            completedExecutions.find { it.executionId == executionId }
        }
    }

    /**
     * 获取所有活跃的执行
     */
    fun getActiveExecutions(): List<WorkflowMetrics> = activeExecutions.values.toList()

    /**
     * 获取已完成的执行历史
     */
    fun getCompletedExecutions(limit: Int = 100): List<WorkflowMetrics> {
        synchronized(completedExecutions) {
            return completedExecutions.take(limit)
        }
    }

    /**
     * 获取工作流的统计信息
     */
    fun getWorkflowStats(workflowName: String): WorkflowStats {
        val completedFiltered = synchronized(completedExecutions) {
            completedExecutions.filter { it.workflowName == workflowName }
        }
        val allExecutions = activeExecutions.values.filter { it.workflowName == workflowName } +
                completedFiltered

        val totalExecutions = allExecutions.size
        val successfulExecutions = allExecutions.count { it.status == ExecutionStatus.COMPLETED }
        val failedExecutions = allExecutions.count { it.status == ExecutionStatus.FAILED }
        val avgDuration = if (totalExecutions > 0) {
            allExecutions.map { it.getDuration() }.average()
        } else 0.0

        return WorkflowStats(
            workflowName = workflowName,
            totalExecutions = totalExecutions,
            successfulExecutions = successfulExecutions,
            failedExecutions = failedExecutions,
            averageDuration = avgDuration.toLong(),
            successRate = if (totalExecutions > 0) successfulExecutions.toDouble() / totalExecutions else 0.0
        )
    }

    /**
     * 生成执行报告
     */
    fun generateReport(executionId: String): String {
        val metrics = getMetrics(executionId) ?: return "Execution not found: $executionId"

        return buildString {
            appendLine("=== Workflow Execution Report ===")
            appendLine("Execution ID: ${metrics.executionId}")
            appendLine("Workflow: ${metrics.workflowName}")
            appendLine("Status: ${metrics.status}")
            appendLine("Duration: ${metrics.getDuration()}ms")
            appendLine("Steps: ${metrics.completedSteps}/${metrics.totalSteps} completed")
            appendLine("Failed: ${metrics.failedSteps}, Skipped: ${metrics.skippedSteps}")
            appendLine("Success Rate: ${"%.2f".format(metrics.getSuccessRate() * 100)}%")
            appendLine()
            appendLine("Step Details:")
            metrics.stepMetrics.forEach { (stepName, stepMetrics) ->
                appendLine("  - $stepName:")
                appendLine("    Status: ${stepMetrics.status}")
                appendLine("    Duration: ${stepMetrics.getDuration()}ms")
                if (stepMetrics.retryCount > 0) {
                    appendLine("    Retries: ${stepMetrics.retryCount}")
                }
                stepMetrics.error?.let {
                    appendLine("    Error: $it")
                }
            }
        }
    }

    /**
     * 清除所有历史记录
     */
    fun clear() {
        activeExecutions.clear()
        synchronized(completedExecutions) {
            completedExecutions.clear()
        }
        logger.info { "Cleared all workflow monitoring data" }
    }
}

/**
 * 工作流统计信息
 */
data class WorkflowStats(
    val workflowName: String,
    val totalExecutions: Int,
    val successfulExecutions: Int,
    val failedExecutions: Int,
    val averageDuration: Long,
    val successRate: Double
)
