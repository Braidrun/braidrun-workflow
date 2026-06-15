package com.fartech.agents.workflow

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.commons.MonitoringEventCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Orchestrator 工具集
 *
 * 为 Agent-based 动态编排步骤中的 Orchestrator Agent 提供工具：
 * - delegateTask：委派任务给指定 Worker Agent
 * - delegateParallel：并行委派多个任务
 * - initiateGroupChat：发起多 Agent Group Chat 讨论
 * - setVariable / getVariable：管理共享变量
 * - getParticipantInfo：查询可用 Worker Agent 信息
 * - complete：结束编排并返回最终结果
 *
 * 每个工具调用都会记录到执行步数计数器，当达到 maxSteps 时拒绝新的委派请求。
 */
@LLMDescription("Toolset for orchestrating workflow tasks by delegating to worker agents")
class OrchestratorTools(
    private val workerAgentRunner: suspend (agentName: String, stepLabel: String, task: String) -> String,
    private val participantDescriptions: Map<String, String>,
    private val context: WorkflowExecutionContext,
    private val maxSteps: Int = 20,
    private val onMonitorEvent: MonitoringEventCallback? = null
) : ToolSet {

    private val stepCounter = AtomicInteger(0)
    private val delegationLog = Collections.synchronizedList(mutableListOf<DelegationRecord>())

    @Volatile
    var completed: Boolean = false
        private set

    @Volatile
    var completionSummary: String? = null
        private set

    private fun emit(type: String, summary: String, detail: String? = null) {
        onMonitorEvent?.invoke(type, summary, detail)
    }

    private fun checkStepBudget() {
        val current = stepCounter.get()
        if (current >= maxSteps) {
            throw OrchestratorBudgetExceededException(
                "Orchestrator step budget exhausted ($current/$maxSteps). " +
                        "Call 'complete' to finish, or the orchestration will be terminated."
            )
        }
    }

    @Tool
    @LLMDescription(
        "Delegate a task to a specific worker agent and wait for the result. " +
                "Use this to assign work to one of the available participants. " +
                "Returns the agent's output as a string."
    )
    suspend fun delegateTask(
        @LLMDescription("Name of the worker agent (must be one of the available participants)")
        agentName: String,
        @LLMDescription("Task description or prompt to send to the worker agent")
        task: String
    ): String {
        checkStepBudget()
        val step = stepCounter.incrementAndGet()

        if (!participantDescriptions.containsKey(agentName)) {
            return "Error: Agent '$agentName' is not an available participant. Available: ${participantDescriptions.keys.joinToString(", ")}"
        }

        logger.info { "[Orchestrator] Step $step/$maxSteps: Delegating task to '$agentName'" }
        emit("orchestrator_delegate", "📋 委派任务给 '$agentName' (step $step/$maxSteps)", task.take(500))

        return try {
            val output = workerAgentRunner(agentName, "orchestrated:$step", task)

            val record = DelegationRecord(
                step = step,
                agentName = agentName,
                task = task.take(200),
                success = true,
                outputPreview = output.take(200)
            )
            delegationLog.add(record)
            context.setStepOutput("orchestrated:$agentName:$step", output)

            emit(
                "orchestrator_delegate_completed",
                "✅ '$agentName' 完成任务 (step $step)",
                output.take(500)
            )
            output
        } catch (e: CancellationException) {
            // Propagate cancellation instead of feeding a fabricated failure
            // string back to the orchestrator LLM.
            throw e
        } catch (e: Exception) {
            val record = DelegationRecord(
                step = step,
                agentName = agentName,
                task = task.take(200),
                success = false,
                error = e.message
            )
            delegationLog.add(record)

            emit("orchestrator_delegate_failed", "❌ '$agentName' 任务失败 (step $step)", e.message)
            "Error: Task delegation to '$agentName' failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Delegate tasks to multiple worker agents in parallel and wait for all results. " +
                "Each assignment specifies an agent name and a task. Returns all results."
    )
    suspend fun delegateParallel(
        @LLMDescription("Comma-separated list of agent names to delegate to (e.g. 'designer,engineer,qa')")
        agentNames: String,
        @LLMDescription("Comma-separated list of tasks corresponding to each agent (same order as agentNames)")
        tasks: String
    ): String = coroutineScope {
        checkStepBudget()

        val agents = agentNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val taskList = tasks.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        if (agents.isEmpty()) return@coroutineScope "Error: No agent names provided"
        if (taskList.isEmpty()) return@coroutineScope "Error: No tasks provided"

        // 如果任务数量少于 agent 数量，将最后一个任务复制给剩余的 agent
        val expandedTasks = if (taskList.size < agents.size) {
            taskList + List(agents.size - taskList.size) { taskList.last() }
        } else {
            taskList.take(agents.size)
        }

        val invalidAgents = agents.filter { !participantDescriptions.containsKey(it) }
        if (invalidAgents.isNotEmpty()) {
            return@coroutineScope "Error: Unknown agents: ${invalidAgents.joinToString(", ")}. Available: ${participantDescriptions.keys.joinToString(", ")}"
        }

        val stepsNeeded = agents.size
        if (stepCounter.get() + stepsNeeded > maxSteps) {
            return@coroutineScope "Error: Not enough step budget for $stepsNeeded parallel tasks (current: ${stepCounter.get()}/$maxSteps)"
        }

        logger.info { "[Orchestrator] Parallel delegation to ${agents.size} agents: ${agents.joinToString(", ")}" }
        emit(
            "orchestrator_parallel_start",
            "🔀 并行委派给 ${agents.size} 个 Agent: ${agents.joinToString(", ")}",
            null
        )

        val semaphore = Semaphore(agents.size.coerceAtMost(5))
        val results = agents.zip(expandedTasks).map { (agentName, task) ->
            async {
                semaphore.withPermit {
                    val step = stepCounter.incrementAndGet()
                    try {
                        val output = workerAgentRunner(agentName, "orchestrated:parallel:$step", task)
                        delegationLog.add(
                            DelegationRecord(step, agentName, task.take(200), true, output.take(200))
                        )
                        "$agentName: $output"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        delegationLog.add(
                            DelegationRecord(step, agentName, task.take(200), false, error = e.message)
                        )
                        "$agentName: Error - ${e.message}"
                    }
                }
            }
        }.awaitAll()

        emit(
            "orchestrator_parallel_completed",
            "✅ 并行委派完成 (${agents.size} agents)",
            results.joinToString("\n").take(1000)
        )
        results.joinToString("\n---\n")
    }

    @Tool
    @LLMDescription(
        "Get information about available participant agents, including their names and capabilities."
    )
    fun getParticipantInfo(): String {
        return buildString {
            appendLine("Available participants (${participantDescriptions.size}):")
            participantDescriptions.forEach { (name, desc) ->
                appendLine("  - $name: $desc")
            }
            appendLine()
            appendLine("Current progress: ${stepCounter.get()}/$maxSteps steps used")
            appendLine("Delegations so far: ${delegationLog.size}")
        }
    }

    @Tool
    @LLMDescription("Set a shared variable that can be read by other tools or steps")
    fun setVariable(
        @LLMDescription("Variable name")
        key: String,
        @LLMDescription("Variable value")
        value: String
    ): String {
        context.setVariable(key, value)
        logger.info { "[Orchestrator] Set variable '$key' = '$value'" }
        return "Variable '$key' set to '$value'"
    }

    @Tool
    @LLMDescription("Get the value of a shared variable")
    fun getVariable(
        @LLMDescription("Variable name")
        key: String
    ): String {
        val value = context.getVariable(key)
        return if (value != null) {
            "Variable '$key' = '$value'"
        } else {
            "Variable '$key' is not set"
        }
    }

    @Tool
    @LLMDescription(
        "Mark the orchestration as complete and provide a final summary. " +
                "You MUST call this when the goal has been achieved or when you determine the goal cannot be achieved. " +
                "The summary will be used as the step's output."
    )
    fun complete(
        @LLMDescription("Final summary of the orchestration result")
        summary: String
    ): String {
        completed = true
        completionSummary = summary
        logger.info { "[Orchestrator] Completed with summary: ${summary.take(200)}" }
        emit("orchestrator_completed", "🏁 编排完成", summary.take(500))
        return "Orchestration completed. Summary recorded."
    }

    /**
     * 获取委派日志（供外部使用）
     */
    fun getDelegationLog(): List<DelegationRecord> = delegationLog.toList()
}

/**
 * 委派记录
 */
data class DelegationRecord(
    val step: Int,
    val agentName: String,
    val task: String,
    val success: Boolean,
    val outputPreview: String? = null,
    val error: String? = null
)

/**
 * Orchestrator 步数预算耗尽异常
 */
class OrchestratorBudgetExceededException(message: String) : RuntimeException(message)
