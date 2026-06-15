package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowDebugTest {

    @Test
    fun `stop on entry pauses before the first step and continue resumes`() = runBlocking {
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

        val pause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.BEFORE_STEP, pause.location.point)
        assertEquals("step_one", pause.location.stepName)
        assertTrue(executor.getDebugState()?.paused == true)

        assertTrue(executor.continueDebug())

        val result = resultDeferred.await()
        assertTrue(result.success)
        assertEquals("first", result.stepResults.getValue("step_one").output?.trim())
        assertEquals("second", result.stepResults.getValue("step_two").output?.trim())
    }

    @Test
    fun `step debug pauses again at the next checkpoint`() = runBlocking {
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

        val firstPause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.BEFORE_STEP, firstPause.location.point)
        assertTrue(executor.stepDebug())

        val secondPause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.AFTER_STEP, secondPause.location.point)
        assertEquals("step_one", secondPause.location.stepName)

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    @Test
    fun `named breakpoint pauses on matching step`() = runBlocking {
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

        val resultDeferred = async { executor.execute(buildTwoStepWorkflow()) }

        val pause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.BEFORE_STEP, pause.location.point)
        assertEquals("step_two", pause.location.stepName)

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
    }

    private fun buildTwoStepWorkflow(): WorkflowDefinition {
        return WorkflowDefinition(
            name = "debug-test",
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
}
