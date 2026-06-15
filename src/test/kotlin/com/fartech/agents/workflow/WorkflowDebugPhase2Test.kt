package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2 debug feature tests:
 * - Conditional breakpoints
 * - Break on Error
 * - Snapshot history
 * - Variable editing during pause
 * - Once breakpoints (auto-disable)
 * - Dynamic breakpoint replacement
 */
class WorkflowDebugPhase2Test {

    // ── Conditional breakpoints ──────────────────────────────────────────

    @Test
    fun `conditional breakpoint pauses only when condition is true`() = runBlocking {
        // Use a workflow with a variable we can check against
        val workflow = WorkflowDefinition(
            name = "debug-cond-test",
            variables = mapOf("threshold" to "5"),
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "step_one",
                    code = CodeStepConfig(language = "bash", script = "echo first")
                ),
                WorkflowStep(
                    step = "step_two",
                    code = CodeStepConfig(language = "bash", script = "echo second"),
                    dependsOn = listOf("step_one")
                )
            )
        )

        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakpoints = listOf(
                    WorkflowDebugBreakpoint(
                        point = WorkflowDebugPoint.BEFORE_STEP,
                        stepName = "step_one",
                        // threshold == 5, so this condition is true
                        condition = "threshold == 5"
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val resultDeferred = async { executor.execute(workflow, mapOf("threshold" to "5")) }

        // The condition is true (threshold == 5), so the breakpoint should fire
        val pause = withTimeout(8_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.BEFORE_STEP, pause.location.point)
        assertEquals("step_one", pause.location.stepName)
        assertEquals(WorkflowDebugPauseReason.BREAKPOINT, pause.reason)

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    @Test
    fun `conditional breakpoint skips when condition is false`() = runBlocking {
        // Set threshold to 10 but condition checks for threshold < 5, so it's false
        val workflow = WorkflowDefinition(
            name = "debug-cond-skip-test",
            variables = mapOf("threshold" to "10"),
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "step_one",
                    code = CodeStepConfig(language = "bash", script = "echo first")
                ),
                WorkflowStep(
                    step = "step_two",
                    code = CodeStepConfig(language = "bash", script = "echo second"),
                    dependsOn = listOf("step_one")
                )
            )
        )

        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakpoints = listOf(
                    WorkflowDebugBreakpoint(
                        point = WorkflowDebugPoint.BEFORE_STEP,
                        stepName = "step_one",
                        // threshold is 10, not < 5, so condition is false
                        condition = "threshold < 5"
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        // Condition is false, so breakpoint should not fire; execution completes without pausing
        val result = executor.execute(workflow, mapOf("threshold" to "10"))
        assertTrue(result.success)
    }

    // ── Break on Error ───────────────────────────────────────────────────

    @Test
    fun `break on error pauses when a step fails`() = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakOnError = true
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val resultDeferred = async { executor.execute(buildFailingWorkflow()) }

        // The failing step should trigger a STEP_ERROR checkpoint, and breakOnError should pause
        val pause = withTimeout(8_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.STEP_ERROR, pause.location.point)
        assertEquals("failing_step", pause.location.stepName)

        assertTrue(executor.continueDebug())
        resultDeferred.await()
    }

    @Test
    fun `break on error can be toggled at runtime`() = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakOnError = false,
                stopOnEntry = true
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        // Start with breakOnError off
        assertFalse(debugController.getState().breakOnError)

        // Toggle it on
        debugController.setBreakOnError(true)
        assertTrue(debugController.getState().breakOnError)

        // Toggle it back off
        debugController.setBreakOnError(false)
        assertFalse(debugController.getState().breakOnError)
    }

    // ── Snapshot history ─────────────────────────────────────────────────

    @Test
    fun `snapshot history accumulates at each checkpoint`() = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                stopOnEntry = true
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val resultDeferred = async { executor.execute(buildTwoStepWorkflow()) }

        // First pause: before_step:step_one
        val pause1 = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals("step_one", pause1.location.stepName)

        var history = debugController.getSnapshotHistory()
        assertTrue(history.isNotEmpty(), "History should have at least 1 snapshot after first pause")
        val firstSnapshotCount = history.size

        // Step to next checkpoint
        assertTrue(executor.stepDebug())

        // Second pause: after_step:step_one
        val pause2 = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals("step_one", pause2.location.stepName)
        assertEquals(WorkflowDebugPoint.AFTER_STEP, pause2.location.point)

        history = debugController.getSnapshotHistory()
        assertTrue(history.size > firstSnapshotCount, "History should grow after stepping")

        // Verify getSnapshotAt works
        val firstSnapshot = debugController.getSnapshotAt(0)
        assertNotNull(firstSnapshot)
        assertEquals("step_one", firstSnapshot.location?.stepName)

        // Out of bounds returns null
        assertNull(debugController.getSnapshotAt(999))

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    // ── Variable editing ─────────────────────────────────────────────────

    @Test
    fun `modifyDebugVariables updates context during pause`() = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                stopOnEntry = true
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val workflow = buildTwoStepWorkflowWithVariables()
        val resultDeferred = async { executor.execute(workflow, mapOf("greeting" to "hello")) }

        // Wait for first pause
        withTimeout(5_000) { debugController.waitForNextPause() }
        assertTrue(executor.getDebugState()?.paused == true)

        // Verify initial variable
        val snapshot = executor.getDebugSnapshot()
        assertNotNull(snapshot)
        assertEquals("hello", snapshot.variables["greeting"])

        // Modify the variable
        executor.modifyDebugVariables(mapOf("greeting" to "world"))

        // Verify updated
        val updatedSnapshot = executor.getDebugSnapshot()
        assertNotNull(updatedSnapshot)
        assertEquals("world", updatedSnapshot.variables["greeting"])

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    // ── Once breakpoints ─────────────────────────────────────────────────

    @Test
    fun `once breakpoint fires only once then auto-disables`() = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakpoints = listOf(
                    WorkflowDebugBreakpoint(
                        point = WorkflowDebugPoint.BEFORE_STEP,
                        stepName = null, // matches ALL steps
                        once = true
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val resultDeferred = async { executor.execute(buildTwoStepWorkflow()) }

        // Should pause on step_one (the first match)
        val pause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals("step_one", pause.location.stepName)

        // The once breakpoint should now be disabled
        val state = executor.getDebugState()
        assertNotNull(state)
        val onceBreakpoint = state.breakpoints.firstOrNull { it.once }
        assertNotNull(onceBreakpoint, "Once breakpoint should still exist in list")
        assertFalse(onceBreakpoint.enabled, "Once breakpoint should be disabled after firing")

        // Continue - should NOT pause on step_two since the once breakpoint is disabled
        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    // ── Dynamic breakpoint replacement ───────────────────────────────────

    @Test
    fun `replaceBreakpoints updates active breakpoints`() = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                stopOnEntry = true
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val resultDeferred = async { executor.execute(buildTwoStepWorkflow()) }

        // Pause at entry
        withTimeout(5_000) { debugController.waitForNextPause() }

        // Initially no breakpoints
        assertTrue(debugController.getState().breakpoints.isEmpty())

        // Add a breakpoint dynamically
        val newBreakpoints = listOf(
            WorkflowDebugBreakpoint(
                point = WorkflowDebugPoint.BEFORE_STEP,
                stepName = "step_two"
            )
        )
        executor.updateDebugBreakpoints(newBreakpoints)

        // Verify the breakpoint was added
        val state = executor.getDebugState()
        assertNotNull(state)
        assertEquals(1, state.breakpoints.size)
        assertEquals("step_two", state.breakpoints[0].stepName)

        // Continue - should pause on step_two
        assertTrue(executor.continueDebug())

        val secondPause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals("step_two", secondPause.location.stepName)

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    // ── Snapshot content verification ────────────────────────────────────

    @Test
    fun `snapshot contains variables step outputs and step results`() = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakpoints = listOf(
                    WorkflowDebugBreakpoint(
                        point = WorkflowDebugPoint.BEFORE_STEP,
                        stepName = "step_two"
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val workflow = buildTwoStepWorkflowWithVariables()
        val resultDeferred = async { executor.execute(workflow, mapOf("greeting" to "hello")) }

        // Should pause before step_two, after step_one completed
        val pause = withTimeout(8_000) { debugController.waitForNextPause() }
        assertEquals("step_two", pause.location.stepName)

        val snapshot = executor.getDebugSnapshot()
        assertNotNull(snapshot)

        // Variables should include our initial variable
        assertTrue(snapshot.variables.containsKey("greeting"), "Snapshot should contain 'greeting' variable")

        // Step results should have step_one
        assertTrue(snapshot.stepResults.containsKey("step_one"), "Snapshot should contain step_one result")
        assertEquals("COMPLETED", snapshot.stepResults["step_one"]?.status)

        // Step outputs should have step_one
        assertTrue(snapshot.stepOutputs.containsKey("step_one"), "Snapshot should contain step_one output")

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    @Test
    fun `iteration checkpoint exposes item variables and isolated output dir`(@TempDir tempDir: Path) = runBlocking {
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakpoints = listOf(
                    WorkflowDebugBreakpoint(
                        point = WorkflowDebugPoint.ITERATION_ITEM,
                        stepName = "loop"
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            debugController = debugController
        )

        val workflow = WorkflowDefinition(
            name = "debug-iteration-test",
            variables = mapOf("output_dir" to "./output"),
            agents = emptyMap(),
            directoryIsolation = DirectoryIsolationConfig(
                enabled = true,
                baseDir = tempDir.resolve("runs").toString(),
                cleanupOnCompletion = false
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "loop",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "printf '%s' \"\$WF_VAR_TASK_ITEM|\$WF_VAR_TASK_INDEX|\$WF_VAR_OUTPUT_DIR\""
                    ),
                    iterateOver = IterateOverConfig(
                        source = "alpha",
                        delimiter = ",",
                        itemVariable = "task_item",
                        indexVariable = "task_index",
                        maxItems = 1,
                        parallel = false
                    )
                )
            )
        )

        val resultDeferred = async { executor.execute(workflow) }

        val pause = withTimeout(8_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.ITERATION_ITEM, pause.location.point)
        assertEquals("loop", pause.location.stepName)

        val snapshot = executor.getDebugSnapshot()
        assertNotNull(snapshot)
        assertEquals("alpha", snapshot.variables["task_item"])
        assertEquals("0", snapshot.variables["task_index"])

        val expectedOutputDir = File(
            workflow.directoryIsolation.getOutputDir(
                snapshot.executionId,
                "loop:iterate:0",
                "default",
                workflow.name
            )
        ).absolutePath
        assertEquals(expectedOutputDir, snapshot.variables["output_dir"])

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
        assertEquals("alpha|0|$expectedOutputDir", result.stepResults["loop"]?.output)
    }

    // ── Helper methods ───────────────────────────────────────────────────

    private fun buildTwoStepWorkflow(): WorkflowDefinition {
        return WorkflowDefinition(
            name = "debug-phase2-test",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "step_one",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo first"
                    )
                ),
                WorkflowStep(
                    step = "step_two",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo second"
                    ),
                    dependsOn = listOf("step_one")
                )
            )
        )
    }

    private fun buildTwoStepWorkflowWithVariables(): WorkflowDefinition {
        return WorkflowDefinition(
            name = "debug-phase2-vars-test",
            variables = mapOf("greeting" to "default"),
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "step_one",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo step_one_output"
                    )
                ),
                WorkflowStep(
                    step = "step_two",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo step_two_output"
                    ),
                    dependsOn = listOf("step_one")
                )
            )
        )
    }

    private fun buildFailingWorkflow(): WorkflowDefinition {
        return WorkflowDefinition(
            name = "debug-failing-test",
            agents = emptyMap(),
            errorHandling = ErrorHandlingConfig(continueOnError = true),
            workflow = listOf(
                WorkflowStep(
                    step = "failing_step",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "exit 1"
                    )
                ),
                WorkflowStep(
                    step = "after_fail",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo survived"
                    ),
                    dependsOn = listOf("failing_step")
                )
            )
        )
    }
}
