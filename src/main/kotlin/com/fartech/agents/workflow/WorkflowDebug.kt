package com.fartech.agents.workflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

data class WorkflowDebugOptions(
    val enabled: Boolean = false,
    val stopOnEntry: Boolean = false,
    val breakOnError: Boolean = false,
    val breakpoints: List<WorkflowDebugBreakpoint> = emptyList()
)

enum class WorkflowDebugPoint(val wireName: String) {
    BEFORE_STEP("before_step"),
    AFTER_STEP("after_step"),
    STEP_ERROR("step_error"),
    STATE_MACHINE_STATE("state_machine_state"),
    GROUP_CHAT_ROUND("group_chat_round"),
    ITERATION_ITEM("iteration_item"),
    AGENT_BASED_DELEGATION("agent_based_delegation"),
    SUB_WORKFLOW_ENTRY("sub_workflow_entry"),
    SUB_WORKFLOW_EXIT("sub_workflow_exit");

    companion object {
        fun fromString(value: String): WorkflowDebugPoint? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { point ->
                point.wireName == normalized || point.name.lowercase() == normalized
            }
        }
    }
}

enum class WorkflowDebugResumeMode {
    CONTINUE,
    STEP
}

enum class WorkflowDebugPauseReason(val wireName: String) {
    BREAKPOINT("breakpoint"),
    STEP("step"),
    PAUSE_REQUEST("pause_request"),
    STOP_ON_ENTRY("stop_on_entry")
}

data class WorkflowDebugBreakpoint(
    val point: WorkflowDebugPoint = WorkflowDebugPoint.BEFORE_STEP,
    val stepName: String? = null,
    val enabled: Boolean = true,
    val once: Boolean = false,
    val condition: String? = null,
    /**
     * 父调用路径过滤(子工作流跨层调试用)。
     *
     * 当设置时,断点只在 child 通过指定父 step 路径触发时命中。例如设置 `parentPath = "render_video"`
     * 后,即使 child 的同名 step 在其他父 step 调用下也不会命中。当 null 时不过滤,行为与之前一致。
     */
    val parentPath: String? = null
)

data class WorkflowDebugLocation(
    val point: WorkflowDebugPoint,
    val stepName: String,
    val label: String? = null
)

data class WorkflowDebugPauseSummary(
    val pauseId: String,
    val reason: WorkflowDebugPauseReason,
    val location: WorkflowDebugLocation,
    val pausedAt: Long = System.currentTimeMillis(),
    val breakpoint: WorkflowDebugBreakpoint? = null
)

data class WorkflowDebugStepSnapshot(
    val status: String,
    val agentName: String? = null,
    val outputPreview: String? = null,
    val error: String? = null
)

data class WorkflowDebugSnapshot(
    val executionId: String,
    val workflowName: String,
    val capturedAt: Long,
    val location: WorkflowDebugLocation? = null,
    val variables: Map<String, String>,
    val stepOutputs: Map<String, String>,
    val stepResults: Map<String, WorkflowDebugStepSnapshot>,
    val skippedSteps: List<String>
)

data class WorkflowDebugState(
    val enabled: Boolean,
    val paused: Boolean,
    val pauseRequested: Boolean,
    val stopOnEntry: Boolean,
    val breakOnError: Boolean,
    val breakpoints: List<WorkflowDebugBreakpoint>,
    val currentPause: WorkflowDebugPauseSummary? = null
)

data class WorkflowDebugPauseResult(
    val pause: WorkflowDebugPauseSummary,
    val resumeMode: WorkflowDebugResumeMode
)

class WorkflowDebugController(
    options: WorkflowDebugOptions = WorkflowDebugOptions()
) {
    companion object {
        private const val MAX_SNAPSHOT_HISTORY = 50
    }

    private val lock = Any()
    private val pauseEvents = Channel<WorkflowDebugPauseSummary>(Channel.BUFFERED)

    @Volatile
    private var executionId: String = ""

    @Volatile
    private var workflowName: String = ""

    @Volatile
    private var latestSnapshot: WorkflowDebugSnapshot? = null

    private var enabled: Boolean = options.enabled
    private var stopOnEntry: Boolean = options.stopOnEntry
    private var stopOnEntryPending: Boolean = options.enabled && options.stopOnEntry
    private var breakOnError: Boolean = options.breakOnError
    private var pauseRequested: Boolean = false
    private var pendingStepBudget: Int = 0
    private val breakpoints = options.breakpoints.toMutableList()
    private var currentPause: ActivePause? = null
    private val snapshotHistory: MutableList<WorkflowDebugSnapshot> = mutableListOf()

    private data class ActivePause(
        val summary: WorkflowDebugPauseSummary,
        val decision: CompletableDeferred<WorkflowDebugResumeMode> = CompletableDeferred()
    )

    suspend fun checkpoint(
        location: WorkflowDebugLocation,
        context: WorkflowExecutionContext
    ): WorkflowDebugPauseResult? {
        if (!enabled) {
            latestSnapshot = buildSnapshot(context, location)
            return null
        }

        while (true) {
            val action = synchronized(lock) {
                currentPause?.let { return@synchronized CheckpointAction.WaitForExisting(it.decision) }

                val snapshot = buildSnapshot(context, location)
                latestSnapshot = snapshot
                snapshotHistory.add(snapshot)
                if (snapshotHistory.size > MAX_SNAPSHOT_HISTORY) {
                    snapshotHistory.removeFirst()
                }

                val matchedBreakpoint = findMatchingBreakpoint(location, context)
                val breakOnErrorTriggered = breakOnError &&
                    location.point == WorkflowDebugPoint.STEP_ERROR
                val pauseReason = when {
                    stopOnEntryPending -> WorkflowDebugPauseReason.STOP_ON_ENTRY
                    pauseRequested -> WorkflowDebugPauseReason.PAUSE_REQUEST
                    pendingStepBudget > 0 -> WorkflowDebugPauseReason.STEP
                    matchedBreakpoint != null -> WorkflowDebugPauseReason.BREAKPOINT
                    breakOnErrorTriggered -> WorkflowDebugPauseReason.BREAKPOINT
                    else -> null
                }

                if (pauseReason == null) {
                    CheckpointAction.Pass
                } else {
                    if (pauseReason == WorkflowDebugPauseReason.STOP_ON_ENTRY) {
                        stopOnEntryPending = false
                    }
                    if (pauseReason == WorkflowDebugPauseReason.STEP) {
                        pendingStepBudget = 0
                    }
                    if (pauseReason == WorkflowDebugPauseReason.PAUSE_REQUEST) {
                        pauseRequested = false
                    }
                    if (matchedBreakpoint?.once == true) {
                        disableBreakpoint(matchedBreakpoint)
                    }

                    val activePause = ActivePause(
                        summary = WorkflowDebugPauseSummary(
                            pauseId = UUID.randomUUID().toString(),
                            reason = pauseReason,
                            location = location,
                            breakpoint = matchedBreakpoint
                        )
                    )
                    currentPause = activePause
                    pauseEvents.trySend(activePause.summary)
                    CheckpointAction.Created(activePause)
                }
            }

            when (action) {
                is CheckpointAction.Pass -> return null
                is CheckpointAction.WaitForExisting -> {
                    action.decision.await()
                }

                is CheckpointAction.Created -> {
                    val resumeMode = action.pause.decision.await()
                    return WorkflowDebugPauseResult(action.pause.summary, resumeMode)
                }
            }
        }
    }

    fun attachExecution(executionId: String, workflowName: String) {
        this.executionId = executionId
        this.workflowName = workflowName
    }

    fun replaceBreakpoints(newBreakpoints: List<WorkflowDebugBreakpoint>) {
        synchronized(lock) {
            breakpoints.clear()
            breakpoints.addAll(newBreakpoints)
        }
    }

    fun requestPause(): Boolean {
        if (!enabled) return false
        synchronized(lock) {
            if (currentPause != null) return true
            pauseRequested = true
        }
        return true
    }

    fun resumeContinue(): Boolean = resolvePause(WorkflowDebugResumeMode.CONTINUE, stepBudget = 0)

    fun resumeStep(): Boolean = resolvePause(WorkflowDebugResumeMode.STEP, stepBudget = 1)

    fun getState(): WorkflowDebugState {
        synchronized(lock) {
            return WorkflowDebugState(
                enabled = enabled,
                paused = currentPause != null,
                pauseRequested = pauseRequested,
                stopOnEntry = stopOnEntry,
                breakOnError = breakOnError,
                breakpoints = breakpoints.toList(),
                currentPause = currentPause?.summary
            )
        }
    }

    fun getCurrentSnapshot(): WorkflowDebugSnapshot? = latestSnapshot

    fun updateLatestSnapshot(snapshot: WorkflowDebugSnapshot) {
        synchronized(lock) {
            latestSnapshot = snapshot
        }
    }

    fun getSnapshotHistory(): List<WorkflowDebugSnapshot> {
        synchronized(lock) {
            return snapshotHistory.toList()
        }
    }

    fun getSnapshotAt(index: Int): WorkflowDebugSnapshot? {
        synchronized(lock) {
            return snapshotHistory.getOrNull(index)
        }
    }

    fun setBreakOnError(value: Boolean) {
        synchronized(lock) {
            breakOnError = value
        }
    }

    suspend fun waitForNextPause(): WorkflowDebugPauseSummary = pauseEvents.receive()

    private fun resolvePause(
        resumeMode: WorkflowDebugResumeMode,
        stepBudget: Int
    ): Boolean {
        val activePause = synchronized(lock) {
            val pause = currentPause ?: return false
            currentPause = null
            pendingStepBudget = stepBudget
            pause
        }
        return activePause.decision.complete(resumeMode)
    }

    private fun findMatchingBreakpoint(
        location: WorkflowDebugLocation,
        context: WorkflowExecutionContext? = null
    ): WorkflowDebugBreakpoint? {
        // 从 context.executionId 派生父调用路径(子工作流场景下 executionId 形如 "rootId/parent_step/grandparent_step")
        val parentPath = context?.executionId?.let { execId ->
            val parts = execId.split("/")
            if (parts.size > 1) parts.drop(1).joinToString("/") else null
        }
        return breakpoints.firstOrNull { breakpoint ->
            breakpoint.enabled &&
                breakpoint.point == location.point &&
                (breakpoint.stepName == null || breakpoint.stepName.equals(location.stepName, ignoreCase = true)) &&
                (breakpoint.parentPath == null || matchesParentPath(breakpoint.parentPath, parentPath)) &&
                evaluateBreakpointCondition(breakpoint, context)
        }
    }

    /**
     * 父调用路径过滤匹配。
     *
     * - 如果 breakpoint 没设 parentPath,任何上下文都通过(由调用方判断)。
     * - 如果 actual 为 null(根工作流),只有 expected 也是空才匹配。
     * - 否则做后缀 / 包含匹配:expected 出现在 actual 路径中即可。
     */
    private fun matchesParentPath(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        val normalizedExpected = expected.trim('/')
        val normalizedActual = actual.trim('/')
        if (normalizedExpected.isEmpty()) return true
        // 路径匹配:期望 "render_video" 应该匹配 "render_video" / "outer/render_video" / "render_video/inner"
        val segments = normalizedActual.split("/")
        return segments.contains(normalizedExpected) ||
            normalizedActual.endsWith("/$normalizedExpected") ||
            normalizedActual == normalizedExpected
    }

    private fun evaluateBreakpointCondition(
        breakpoint: WorkflowDebugBreakpoint,
        context: WorkflowExecutionContext?
    ): Boolean {
        val condition = breakpoint.condition ?: return true
        if (context == null) return true
        return try {
            evaluateWorkflowCondition(condition, context, fallbackToStepOutput = true)
        } catch (e: Exception) {
            logger.debug(e) { "Debug checkpoint failed" }
            true // If condition evaluation fails, treat as unconditional (still pause)
        }
    }

    private fun disableBreakpoint(target: WorkflowDebugBreakpoint) {
        val index = breakpoints.indexOfFirst { existing ->
            existing.point == target.point &&
                existing.stepName == target.stepName &&
                existing.once == target.once
        }
        if (index >= 0) {
            breakpoints[index] = breakpoints[index].copy(enabled = false)
        }
    }

    private fun buildSnapshot(
        context: WorkflowExecutionContext,
        location: WorkflowDebugLocation?
    ): WorkflowDebugSnapshot {
        return WorkflowDebugSnapshot(
            executionId = executionId.ifBlank { context.executionId },
            workflowName = workflowName.ifBlank { context.workflowName },
            capturedAt = System.currentTimeMillis(),
            location = location,
            variables = context.variables
                .toSortedMap()
                .mapValues { (_, value) -> preview(value) },
            stepOutputs = context.stepOutputs
                .toSortedMap()
                .mapValues { (_, value) -> preview(value) },
            stepResults = context.stepResults
                .toSortedMap()
                .mapValues { (_, result) ->
                    WorkflowDebugStepSnapshot(
                        status = if (result.success) "COMPLETED" else "FAILED",
                        agentName = result.agentName,
                        outputPreview = result.output?.let(::preview),
                        error = result.error
                    )
                },
            skippedSteps = context.skippedSteps.toList().sorted()
        )
    }

    private fun preview(value: Any?, maxLength: Int = 1000): String {
        val text = value?.toString()?.trim().orEmpty()
        return if (text.length > maxLength) {
            text.take(maxLength) + "..."
        } else {
            text
        }
    }

    private sealed interface CheckpointAction {
        data object Pass : CheckpointAction
        data class WaitForExisting(
            val decision: CompletableDeferred<WorkflowDebugResumeMode>
        ) : CheckpointAction

        data class Created(
            val pause: ActivePause
        ) : CheckpointAction
    }
}
