package com.fartech.agents.workflow

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-04-26 — regression for the silent-skip bug surfaced by
 * `execution-process-0e532e5c-...-failed.yaml`. Pre-fix, `WorkflowExecutionResult`
 * did not expose [WorkflowExecutionResult.skippedSteps]; condition-skipped steps
 * lived only inside `WorkflowExecutionContext.skippedSteps` and never made it to
 * the user-facing layer. The result was a workflow showing
 * `totalSteps=4 / completedSteps=2` with only 3 entries in the UI — the fourth
 * (skipped) step was completely invisible.
 */
class WorkflowExecutorSkippedStepsTest {

    private class CapturingExecutor : SubprocessExecutor {
        val invocations = mutableListOf<SubprocessExecutor.ExecRequest>()
        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            invocations += request
            return SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = "ok",
                stderr = "",
                durationMs = 1
            )
        }
    }

    @Test
    fun `condition-skipped step is exposed via WorkflowExecutionResult skippedSteps`(@TempDir tempDir: Path) = runBlocking {
        val exec = CapturingExecutor()
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = tempDir.resolve("skills").toString(),
            sharedCacheDir = tempDir.resolve("cache").toString(),
            sharedHistoryDir = tempDir.resolve("history").toString(),
        )
        val workflow = WorkflowDefinition(
            name = "skipped-step-visibility",
            agents = emptyMap(),
            variables = mapOf("ready" to "no"),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "always_runs",
                    code = CodeStepConfig(language = "bash", script = "echo first"),
                ),
                WorkflowStep(
                    step = "skipped_by_condition",
                    code = CodeStepConfig(language = "bash", script = "echo second"),
                    dependsOn = listOf("always_runs"),
                    // `ready == 'yes'` evaluates to `'no' == 'yes'` → false → skipped.
                    condition = "ready == 'yes'",
                ),
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = exec,
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-skip-1")

        assertTrue(result.success, "workflow should succeed: skipped steps don't fail the workflow")
        // First step did run — single subprocess invocation.
        assertEquals(1, exec.invocations.size, "only the first step should have actually executed")

        // The fix: skipped step is now reported on the result.
        assertEquals(setOf("skipped_by_condition"), result.skippedSteps,
            "skipped_by_condition must appear in result.skippedSteps")
        // And it does NOT appear in stepResults (those are executed steps only).
        assertFalse(
            "skipped_by_condition" in result.stepResults,
            "skipped step must not appear in stepResults (those are executed only)"
        )
    }

    @Test
    fun `compound condition with && now correctly skips when any branch fails`(@TempDir tempDir: Path) = runBlocking {
        // Reproducer for the specific bug from the pwa-app-builder execution:
        // a step gated on `a == 'ok' && b != ''` should be skipped when b is
        // empty. Pre-2026-04-26 the engine treated everything after the first
        // operator as the right-hand value, so this evaluated to true and the
        // step ran.
        val exec = CapturingExecutor()
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = tempDir.resolve("skills").toString(),
            sharedCacheDir = tempDir.resolve("cache").toString(),
            sharedHistoryDir = tempDir.resolve("history").toString(),
        )
        val workflow = WorkflowDefinition(
            name = "compound-condition-test",
            agents = emptyMap(),
            variables = mapOf(
                "build_status" to "ok",
                "telegram_token" to ""    // empty
            ),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "deliver",
                    code = CodeStepConfig(language = "bash", script = "echo would-deliver"),
                    condition = "build_status == 'ok' && telegram_token != ''",
                ),
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = exec,
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-skip-2")

        assertEquals(0, exec.invocations.size,
            "deliver step must NOT run because telegram_token is empty (was running pre-fix)")
        assertEquals(setOf("deliver"), result.skippedSteps)
    }
}
