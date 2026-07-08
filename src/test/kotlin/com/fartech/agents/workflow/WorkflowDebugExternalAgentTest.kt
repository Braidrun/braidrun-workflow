package com.fartech.agents.workflow

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowDebugExternalAgentTest {

    private class FakeExternalAgentExecutor(
        private val result: SubprocessExecutor.ExecResult
    ) : SubprocessExecutor {
        val invocations = mutableListOf<SubprocessExecutor.ExecRequest>()

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            invocations += request
            result.stdout.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { request.stdoutLineCallback?.invoke(it) }
            return result
        }
    }

    @Test
    fun `claude code agent step supports stop on entry and single step debug`(@TempDir tempDir: Path) = runBlocking {
        val subprocess = FakeExternalAgentExecutor(
            SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = """
                    {"type":"result","subtype":"success","result":"Claude completed the task.","session_id":"claude-session"}
                """.trimIndent(),
                stderr = "",
                durationMs = 1
            )
        )
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                stopOnEntry = true
            )
        )
        val executor = externalAgentWorkflowExecutor(
            debugController = debugController,
            subprocess = subprocess
        )

        val resultDeferred = async {
            executor.execute(
                externalAgentWorkflow(
                    tempDir = tempDir,
                    agentType = "claude_code_agent",
                    agentName = "claude_worker",
                    stepName = "draft_with_claude"
                )
            )
        }

        val beforePause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.BEFORE_STEP, beforePause.location.point)
        assertEquals("draft_with_claude", beforePause.location.stepName)
        assertEquals("claude_worker", beforePause.location.label)

        assertTrue(executor.stepDebug())
        val afterPause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.AFTER_STEP, afterPause.location.point)
        assertEquals("draft_with_claude", afterPause.location.stepName)

        val snapshot = executor.getDebugSnapshot()
        assertEquals("Claude completed the task.", snapshot?.stepOutputs?.get("draft_with_claude"))
        assertEquals("COMPLETED", snapshot?.stepResults?.get("draft_with_claude")?.status)

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(result.success)
        assertEquals("Claude completed the task.", result.stepResults.getValue("draft_with_claude").output)
        assertTrue(subprocess.invocations.single().command.any { it == "claude" })
    }

    @Test
    fun `codex agent step participates in break on error debug`(@TempDir tempDir: Path) = runBlocking {
        val subprocess = FakeExternalAgentExecutor(
            SubprocessExecutor.ExecResult(
                exitCode = 1,
                stdout = "",
                stderr = "codex failed",
                durationMs = 1
            )
        )
        val debugController = WorkflowDebugController(
            WorkflowDebugOptions(
                enabled = true,
                breakOnError = true
            )
        )
        val executor = externalAgentWorkflowExecutor(
            debugController = debugController,
            subprocess = subprocess
        )

        val resultDeferred = async {
            executor.execute(
                externalAgentWorkflow(
                    tempDir = tempDir,
                    agentType = "codex_agent",
                    agentName = "codex_worker",
                    stepName = "review_with_codex"
                )
            )
        }

        val errorPause = withTimeout(5_000) { debugController.waitForNextPause() }
        assertEquals(WorkflowDebugPoint.STEP_ERROR, errorPause.location.point)
        assertEquals("review_with_codex", errorPause.location.stepName)
        assertTrue(errorPause.location.label?.contains("codex failed") == true)

        val snapshot = executor.getDebugSnapshot()
        assertEquals("FAILED", snapshot?.stepResults?.get("review_with_codex")?.status)

        assertTrue(executor.continueDebug())
        val result = resultDeferred.await()
        assertTrue(!result.success)
        assertTrue(subprocess.invocations.single().command.contains("codex"))
    }

    @Test
    fun `claude stream events are normalized for reasoning view`(@TempDir tempDir: Path) = runBlocking {
        val subprocess = FakeExternalAgentExecutor(
            SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = """
                    {"type":"assistant","message":{"content":[{"type":"thinking","text":"Inspect the report first."},{"type":"tool_use","id":"tool-1","name":"Read","input":{"file":"report.md"}},{"type":"text","text":"I found the main issue."}]}}
                    {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"report contents"}]}}
                    {"type":"result","subtype":"success","result":"Final from Claude.","session_id":"claude-session"}
                """.trimIndent(),
                stderr = "",
                durationMs = 1
            )
        )
        val executionId = "claude-stream-debug"
        WorkflowMonitor.resetExecution(executionId)
        val executor = externalAgentWorkflowExecutor(
            debugController = null,
            subprocess = subprocess,
            enableMonitoring = true
        )

        val result = executor.execute(
            externalAgentWorkflow(
                tempDir = tempDir,
                agentType = "claude_code_agent",
                agentName = "claude_worker",
                stepName = "draft_with_claude"
            ),
            externalExecutionId = executionId
        )

        assertTrue(result.success)
        val events = WorkflowMonitor.getMetrics(executionId)
            ?.stepMetrics
            ?.get("draft_with_claude")
            ?.events
        assertNotNull(events)
        assertTrue(events.any { it.type == "reasoning_message" && it.category == "llm" && it.subCategory == "reasoning" })
        assertTrue(events.any { it.type == "tool_call_starting" && it.category == "tool" })
        assertTrue(events.any { it.type == "tool_call_completed" && it.category == "tool" })
        assertTrue(events.any { it.type == "llm_stream_delta" && it.category == "llm" })
    }

    @Test
    fun `codex jsonl events are normalized for reasoning view`(@TempDir tempDir: Path) = runBlocking {
        // Real `codex exec --json` output (codex-sdk ThreadEvent schema, verified against
        // codex 0.142.3): one event per line, with the content discriminator on the
        // nested item.type and text on item.text / item.aggregated_output.
        val subprocess = FakeExternalAgentExecutor(
            SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = """
                    {"type":"thread.started","thread_id":"codex-thread-1"}
                    {"type":"turn.started"}
                    {"type":"item.started","item":{"id":"item_0","type":"reasoning","text":"Inspect the repository first."}}
                    {"type":"item.completed","item":{"id":"item_0","type":"reasoning","text":"Inspect the repository first."}}
                    {"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"cat README.md","aggregated_output":"","status":"in_progress"}}
                    {"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"cat README.md","aggregated_output":"README contents","exit_code":0,"status":"completed"}}
                    {"type":"item.completed","item":{"id":"item_2","type":"agent_message","text":"The implementation is ready."}}
                    {"type":"turn.completed","usage":{"input_tokens":1200,"cached_input_tokens":0,"output_tokens":340,"reasoning_output_tokens":50}}
                """.trimIndent(),
                stderr = "",
                durationMs = 1
            )
        )
        val executionId = "codex-stream-debug"
        WorkflowMonitor.resetExecution(executionId)
        val executor = externalAgentWorkflowExecutor(
            debugController = null,
            subprocess = subprocess,
            enableMonitoring = true
        )

        val result = executor.execute(
            externalAgentWorkflow(
                tempDir = tempDir,
                agentType = "codex_agent",
                agentName = "codex_worker",
                stepName = "review_with_codex"
            ),
            externalExecutionId = executionId
        )

        assertTrue(result.success)
        // The tool's return value must be the final agent_message, not the raw JSONL.
        assertEquals("The implementation is ready.", result.stepResults.getValue("review_with_codex").output)
        // The default command must request structured output.
        assertTrue(subprocess.invocations.single().command.contains("--json"))

        val events = WorkflowMonitor.getMetrics(executionId)
            ?.stepMetrics
            ?.get("review_with_codex")
            ?.events
        assertNotNull(events)
        assertTrue(events.any { it.type == "reasoning_message" && it.category == "llm" && it.subCategory == "reasoning" })
        assertTrue(events.any { it.type == "tool_call_starting" && it.category == "tool" })
        assertTrue(events.any { it.type == "tool_call_completed" && it.category == "tool" })
        assertTrue(events.any { it.type == "llm_stream_delta" && it.category == "llm" })
    }

    private fun externalAgentWorkflowExecutor(
        debugController: WorkflowDebugController?,
        subprocess: SubprocessExecutor,
        enableMonitoring: Boolean = false
    ): WorkflowExecutor {
        return WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("anthropic_api_key", JsonPrimitive("sk-test-anthropic")),
                ConfigurationParameter("openai_api_key", JsonPrimitive("sk-test-openai")),
                ConfigurationParameter("user_id", JsonPrimitive("debug-user"))
            ),
            enableMonitoring = enableMonitoring,
            debugController = debugController,
            codeStepExecutor = subprocess
        )
    }

    private fun externalAgentWorkflow(
        tempDir: Path,
        agentType: String,
        agentName: String,
        stepName: String
    ): WorkflowDefinition {
        return WorkflowDefinition(
            name = "external-agent-debug-test",
            agents = mapOf(
                agentName to AgentDefinition(
                    type = agentType,
                    overrides = mapOf(
                        "type" to JsonPrimitive(agentType)
                    )
                )
            ),
            directoryIsolation = DirectoryIsolationConfig(
                enabled = true,
                baseDir = tempDir.resolve("runs").toString(),
                sharedSkillsDir = tempDir.resolve("skills").toString(),
                sharedCacheDir = tempDir.resolve("cache").toString(),
                sharedHistoryDir = tempDir.resolve("history").toString()
            ),
            workflow = listOf(
                WorkflowStep(
                    step = stepName,
                    agent = agentName,
                    input = "Handle this task."
                )
            )
        )
    }
}
