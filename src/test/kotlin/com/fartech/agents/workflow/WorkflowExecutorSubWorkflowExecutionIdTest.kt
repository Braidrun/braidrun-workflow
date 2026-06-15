package com.fartech.agents.workflow

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for `execution-process-79621f70-...-failed.yaml`.
 *
 * Pre-2026-04-26: code steps inside sub-workflows received
 *   WF_EXECUTION_ID = "<parent>__<step>"   (the derived child id)
 * with no way to discover the user-visible parent/root execution. Modules
 * like `braidrun-module-artifact-publish` and
 * `braidrun-module-artifact-directory-publish` then called the backend API
 * with that wrong id, the API correctly returned "no artifacts" because the
 * parent's other steps had registered their artifacts under the *root*
 * execution, and the modules failed every time after 6 retries.
 *
 * The fix introduces two new env vars:
 *
 *   - WF_PARENT_EXECUTION_ID — only present in sub-workflow contexts; the
 *     immediate parent's `executionId`.
 *   - WF_ROOT_EXECUTION_ID   — only present in sub-workflow contexts; the
 *     top-most ancestor's `executionId` (= what the user sees in the URL).
 *
 * `WF_EXECUTION_ID` semantics are unchanged for backward compatibility.
 *
 * This file verifies the engine wiring end-to-end via `WorkflowExecutor.execute`
 * with a fake `SubprocessExecutor` that captures the env map handed to each
 * code-step subprocess.
 */
class WorkflowExecutorSubWorkflowExecutionIdTest {

    /** Records every code step's env map keyed by step name. */
    private class CapturingExecutor : SubprocessExecutor {
        val invocations: MutableList<Pair<String, Map<String, String>>> = mutableListOf()

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            // Step name is encoded in the script filename: wf_code_<step>.<ext>
            val stepName = request.command.lastOrNull()
                ?.substringAfterLast('/')
                ?.removePrefix("wf_code_")
                ?.substringBeforeLast('.')
                ?: "?"
            invocations += stepName to request.env.toMap()
            return SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = "ok",
                stderr = "",
                durationMs = 1
            )
        }
    }

    private fun isolation(@TempDir tempDir: Path): DirectoryIsolationConfig =
        DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = tempDir.resolve("skills").toString(),
            sharedCacheDir = tempDir.resolve("cache").toString(),
            sharedHistoryDir = tempDir.resolve("history").toString(),
        )

    // ── Top-level (no sub-workflow) ─────────────────────────────────────

    @Test
    fun `top-level code step gets WF_EXECUTION_ID but no parent or root vars`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val workflow = WorkflowDefinition(
            name = "top-level-only",
            agents = emptyMap(),
            directoryIsolation = isolation(tempDir),
            workflow = listOf(
                WorkflowStep(
                    step = "do_thing",
                    code = CodeStepConfig(language = "bash", script = "echo hi"),
                ),
            ),
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = capture,
        )

        executor.execute(workflow, externalExecutionId = "exec-root-1")

        val (_, env) = capture.invocations.single()
        assertEquals(
            "exec-root-1", env["WF_EXECUTION_ID"],
            "top-level WF_EXECUTION_ID must equal the externally-provided run id"
        )
        assertNull(
            env["WF_PARENT_EXECUTION_ID"],
            "top-level execution must NOT inject WF_PARENT_EXECUTION_ID"
        )
        assertNull(
            env["WF_ROOT_EXECUTION_ID"],
            "top-level execution must NOT inject WF_ROOT_EXECUTION_ID"
        )
    }

    // ── One-level sub-workflow ──────────────────────────────────────────

    @Test
    fun `sub-workflow code step gets parent and root execution ids`(@TempDir tempDir: Path) = runBlocking {
        // Build a child workflow that has one bash step ("publish"). The child
        // is called from a parent workflow's "share_link" sub_workflow step.
        val capture = CapturingExecutor()
        val child = WorkflowDefinition(
            name = "child-publish",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "publish",
                    code = CodeStepConfig(language = "bash", script = "echo published"),
                ),
            ),
        )
        val parent = WorkflowDefinition(
            name = "parent-flow",
            agents = emptyMap(),
            directoryIsolation = isolation(tempDir),
            workflow = listOf(
                WorkflowStep(
                    step = "share_link",
                    subWorkflow = SubWorkflowConfig(name = child.name),
                ),
            ),
        )
        val resolver = InMemoryWorkflowResolver(byName = mapOf(child.name to child))
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = capture,
            workflowResolver = resolver,
        )

        executor.execute(parent, externalExecutionId = "exec-parent-1")

        val (stepName, env) = capture.invocations.single()
        assertEquals("publish", stepName, "expected one code-step invocation: publish")
        // The CHILD's own execution id is `<parent>__<step>` derived by the engine.
        assertEquals(
            "exec-parent-1__share_link", env["WF_EXECUTION_ID"],
            "WF_EXECUTION_ID should be the derived child id (preserved for back-compat)"
        )
        assertEquals(
            "exec-parent-1", env["WF_PARENT_EXECUTION_ID"],
            "WF_PARENT_EXECUTION_ID must be the immediate parent's id"
        )
        assertEquals(
            "exec-parent-1", env["WF_ROOT_EXECUTION_ID"],
            "for one-level nesting, WF_ROOT_EXECUTION_ID equals the parent id"
        )
    }

    // ── Two-level sub-workflow (nested) ─────────────────────────────────

    @Test
    fun `nested sub-workflow code step distinguishes parent from root`(@TempDir tempDir: Path) = runBlocking {
        // grandparent → parent (sub_workflow) → leaf (sub_workflow with code step)
        val capture = CapturingExecutor()
        val leaf = WorkflowDefinition(
            name = "leaf-flow",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "publish",
                    code = CodeStepConfig(language = "bash", script = "echo deep"),
                ),
            ),
        )
        val middle = WorkflowDefinition(
            name = "middle-flow",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "call_leaf",
                    subWorkflow = SubWorkflowConfig(name = leaf.name),
                ),
            ),
        )
        val root = WorkflowDefinition(
            name = "root-flow",
            agents = emptyMap(),
            directoryIsolation = isolation(tempDir),
            workflow = listOf(
                WorkflowStep(
                    step = "call_middle",
                    subWorkflow = SubWorkflowConfig(name = middle.name),
                ),
            ),
        )
        val resolver = InMemoryWorkflowResolver(
            byName = mapOf(leaf.name to leaf, middle.name to middle)
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = capture,
            workflowResolver = resolver,
        )

        executor.execute(root, externalExecutionId = "exec-root-x")

        val (stepName, env) = capture.invocations.single()
        assertEquals("publish", stepName)
        // Self id: the engine appends `__<step>` at each nesting level.
        assertEquals(
            "exec-root-x__call_middle__call_leaf", env["WF_EXECUTION_ID"],
            "WF_EXECUTION_ID accumulates step names across nesting levels"
        )
        // Immediate parent id: middle's derived id.
        assertEquals(
            "exec-root-x__call_middle", env["WF_PARENT_EXECUTION_ID"],
            "WF_PARENT_EXECUTION_ID is the IMMEDIATE parent (not root) for nested sub-workflows"
        )
        // Root id stays at the top-most ancestor regardless of nesting depth.
        assertEquals(
            "exec-root-x", env["WF_ROOT_EXECUTION_ID"],
            "WF_ROOT_EXECUTION_ID is always the top-most ancestor — this is the user-visible run id"
        )
    }

    // ── Direct context test (no executor needed) ────────────────────────

    @Test
    fun `WorkflowExecutionContext snapshot preserves parent and root execution ids`() {
        val ctx = WorkflowExecutionContext(
            workflowName = "child",
            executionId = "child-exec",
            parentExecutionId = "parent-exec",
            rootExecutionId = "root-exec",
        )
        ctx.setVariable("k", "v")
        val snap = ctx.snapshot()
        assertEquals("parent-exec", snap.parentExecutionId,
            "snapshot must propagate parentExecutionId")
        assertEquals("root-exec", snap.rootExecutionId,
            "snapshot must propagate rootExecutionId")
        assertEquals("child-exec", snap.executionId)
    }

    @Test
    fun `top-level WorkflowExecutionContext has null parent and root`() {
        val ctx = WorkflowExecutionContext(
            workflowName = "root",
            executionId = "root-exec",
        )
        assertNull(ctx.parentExecutionId, "top-level must default parentExecutionId to null")
        assertNull(ctx.rootExecutionId, "top-level must default rootExecutionId to null")
    }

    @Test
    fun `legacy SubWorkflowTest-style construction still compiles without parent or root`() {
        // Defensive: existing callers that construct contexts without the new
        // params must keep working (the new fields have defaults).
        val ctx = WorkflowExecutionContext(workflowName = "x", executionId = "y")
        assertNotNull(ctx)
        assertTrue(ctx.parentExecutionId == null)
    }
}
