package com.fartech.agents.workflow

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WorkflowMonitorExtendedTest {

    @BeforeEach
    fun setUp() {
        WorkflowMonitor.clear()
    }

    @AfterEach
    fun tearDown() {
        WorkflowMonitor.clear()
    }

    // =========================================================================
    // Execution Lifecycle
    // =========================================================================

    @Nested
    inner class ExecutionLifecycleTest {

        @Test
        fun `startExecution creates metrics with correct initial state`() {
            val metrics = WorkflowMonitor.startExecution("e1", "test-wf", 5)
            assertEquals("e1", metrics.executionId)
            assertEquals("test-wf", metrics.workflowName)
            assertEquals(5, metrics.totalSteps)
            assertEquals(ExecutionStatus.RUNNING, metrics.status)
            assertEquals(0, metrics.completedSteps)
            assertEquals(0, metrics.failedSteps)
            assertEquals(0, metrics.skippedSteps)
        }

        @Test
        fun `startStep creates step metrics`() {
            WorkflowMonitor.startExecution("e1", "wf", 3)
            WorkflowMonitor.startStep("e1", "step1")
            val metrics = WorkflowMonitor.getMetrics("e1")
            assertNotNull(metrics)
            assertTrue(metrics!!.stepMetrics.containsKey("step1"))
            assertEquals(ExecutionStatus.RUNNING, metrics.stepMetrics["step1"]!!.status)
        }

        @Test
        fun `completeStep success updates metrics`() {
            WorkflowMonitor.startExecution("e1", "wf", 3)
            WorkflowMonitor.startStep("e1", "step1")
            WorkflowMonitor.completeStep("e1", "step1", success = true)

            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(1, metrics.completedSteps)
            assertEquals(0, metrics.failedSteps)
            assertEquals(ExecutionStatus.COMPLETED, metrics.stepMetrics["step1"]!!.status)
        }

        @Test
        fun `completeStep failure updates metrics`() {
            WorkflowMonitor.startExecution("e1", "wf", 3)
            WorkflowMonitor.startStep("e1", "step1")
            WorkflowMonitor.completeStep("e1", "step1", success = false, error = "something broke")

            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(0, metrics.completedSteps)
            assertEquals(1, metrics.failedSteps)
            assertEquals(ExecutionStatus.FAILED, metrics.stepMetrics["step1"]!!.status)
            assertEquals("something broke", metrics.stepMetrics["step1"]!!.error)
        }

        @Test
        fun `skipStep increments skippedSteps`() {
            WorkflowMonitor.startExecution("e1", "wf", 3)
            WorkflowMonitor.skipStep("e1", "step1")
            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(1, metrics.skippedSteps)
        }

        @Test
        fun `recordRetry increments retry count`() {
            WorkflowMonitor.startExecution("e1", "wf", 3)
            WorkflowMonitor.startStep("e1", "step1")
            WorkflowMonitor.recordRetry("e1", "step1")
            WorkflowMonitor.recordRetry("e1", "step1")

            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(2, metrics.stepMetrics["step1"]!!.retryCount)
        }

        @Test
        fun `awaitingApproval sets correct status`() {
            WorkflowMonitor.startExecution("e1", "wf", 3)
            WorkflowMonitor.startStep("e1", "step1")
            WorkflowMonitor.awaitingApproval("e1", "step1")

            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(ExecutionStatus.AWAITING_APPROVAL, metrics.stepMetrics["step1"]!!.status)
        }

        @Test
        fun `completeExecution success`() {
            WorkflowMonitor.startExecution("e1", "wf", 2)
            WorkflowMonitor.startStep("e1", "s1")
            WorkflowMonitor.completeStep("e1", "s1", success = true)
            WorkflowMonitor.startStep("e1", "s2")
            WorkflowMonitor.completeStep("e1", "s2", success = true)
            WorkflowMonitor.completeExecution("e1", success = true)

            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(ExecutionStatus.COMPLETED, metrics.status)
            assertNotNull(metrics.endTime)
        }

        @Test
        fun `completeExecution failure`() {
            WorkflowMonitor.startExecution("e1", "wf", 2)
            WorkflowMonitor.completeExecution("e1", success = false)

            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(ExecutionStatus.FAILED, metrics.status)
        }

        @Test
        fun `cancelExecution sets cancelled status`() {
            WorkflowMonitor.startExecution("e1", "wf", 2)
            WorkflowMonitor.cancelExecution("e1")

            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(ExecutionStatus.CANCELLED, metrics.status)
            assertNotNull(metrics.endTime)
        }
    }

    // =========================================================================
    // Query Methods
    // =========================================================================

    @Nested
    inner class QueryMethodsTest {

        @Test
        fun `getMetrics returns null for unknown execution`() {
            assertNull(WorkflowMonitor.getMetrics("nonexistent"))
        }

        @Test
        fun `getActiveExecutions returns only running executions`() {
            WorkflowMonitor.startExecution("e1", "wf", 2)
            WorkflowMonitor.startExecution("e2", "wf", 2)
            WorkflowMonitor.completeExecution("e2", success = true)

            val active = WorkflowMonitor.getActiveExecutions()
            assertEquals(1, active.size)
            assertEquals("e1", active[0].executionId)
        }

        @Test
        fun `getCompletedExecutions returns completed and respects limit`() {
            for (i in 1..5) {
                WorkflowMonitor.startExecution("e$i", "wf", 1)
                WorkflowMonitor.completeExecution("e$i", success = true)
            }

            val completed = WorkflowMonitor.getCompletedExecutions(limit = 3)
            assertEquals(3, completed.size)
        }

        @Test
        fun `getCompletedExecutions includes failed and cancelled`() {
            WorkflowMonitor.startExecution("e1", "wf", 1)
            WorkflowMonitor.completeExecution("e1", success = false)

            WorkflowMonitor.startExecution("e2", "wf", 1)
            WorkflowMonitor.cancelExecution("e2")

            val completed = WorkflowMonitor.getCompletedExecutions()
            assertEquals(2, completed.size)
        }
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    @Nested
    inner class StatisticsTest {

        @Test
        fun `getWorkflowStats for unknown workflow returns zero stats`() {
            val stats = WorkflowMonitor.getWorkflowStats("nonexistent")
            assertEquals("nonexistent", stats.workflowName)
            assertEquals(0, stats.totalExecutions)
            assertEquals(0, stats.successfulExecutions)
            assertEquals(0, stats.failedExecutions)
            assertEquals(0.0, stats.successRate)
        }

        @Test
        fun `getWorkflowStats calculates correctly`() {
            // 2 successes, 1 failure
            WorkflowMonitor.startExecution("e1", "my-wf", 1)
            WorkflowMonitor.completeExecution("e1", success = true)

            WorkflowMonitor.startExecution("e2", "my-wf", 1)
            WorkflowMonitor.completeExecution("e2", success = true)

            WorkflowMonitor.startExecution("e3", "my-wf", 1)
            WorkflowMonitor.completeExecution("e3", success = false)

            val stats = WorkflowMonitor.getWorkflowStats("my-wf")
            assertEquals(3, stats.totalExecutions)
            assertEquals(2, stats.successfulExecutions)
            assertEquals(1, stats.failedExecutions)
            assertEquals(0.667, stats.successRate, 0.01)
        }

        @Test
        fun `getWorkflowStats ignores other workflow names`() {
            WorkflowMonitor.startExecution("e1", "wf-a", 1)
            WorkflowMonitor.completeExecution("e1", success = true)

            WorkflowMonitor.startExecution("e2", "wf-b", 1)
            WorkflowMonitor.completeExecution("e2", success = false)

            val statsA = WorkflowMonitor.getWorkflowStats("wf-a")
            assertEquals(1, statsA.totalExecutions)
            assertEquals(1, statsA.successfulExecutions)
            assertEquals(0, statsA.failedExecutions)
        }
    }

    // =========================================================================
    // Report Generation
    // =========================================================================

    @Nested
    inner class ReportTest {

        @Test
        fun `generateReport for unknown execution returns informative message`() {
            val report = WorkflowMonitor.generateReport("nonexistent")
            assertTrue(report.contains("nonexistent") || report.contains("not found") || report.contains("No"))
        }

        @Test
        fun `generateReport for completed execution contains key info`() {
            WorkflowMonitor.startExecution("e1", "report-wf", 2)
            WorkflowMonitor.startStep("e1", "step1")
            WorkflowMonitor.completeStep("e1", "step1", success = true)
            WorkflowMonitor.startStep("e1", "step2")
            WorkflowMonitor.completeStep("e1", "step2", success = false, error = "failed!")
            WorkflowMonitor.completeExecution("e1", success = false)

            val report = WorkflowMonitor.generateReport("e1")
            assertTrue(report.contains("report-wf"))
            assertTrue(report.contains("e1"))
        }

        @Test
        fun `generateReport for running execution`() {
            WorkflowMonitor.startExecution("e1", "running-wf", 3)
            WorkflowMonitor.startStep("e1", "step1")

            val report = WorkflowMonitor.generateReport("e1")
            assertTrue(report.contains("running-wf"))
        }
    }

    // =========================================================================
    // Clear / Reset
    // =========================================================================

    @Nested
    inner class ClearTest {

        @Test
        fun `clear removes all data`() {
            WorkflowMonitor.startExecution("e1", "wf", 1)
            WorkflowMonitor.startExecution("e2", "wf", 1)
            WorkflowMonitor.clear()

            assertNull(WorkflowMonitor.getMetrics("e1"))
            assertNull(WorkflowMonitor.getMetrics("e2"))
            assertTrue(WorkflowMonitor.getActiveExecutions().isEmpty())
            assertTrue(WorkflowMonitor.getCompletedExecutions().isEmpty())
        }
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Nested
    inner class EdgeCaseTest {

        @Test
        fun `startStep without startExecution does nothing`() {
            // Should not throw
            WorkflowMonitor.startStep("nonexistent", "step1")
        }

        @Test
        fun `completeStep without startExecution does nothing`() {
            WorkflowMonitor.completeStep("nonexistent", "step1", true)
        }

        @Test
        fun `skipStep without startExecution creates entry`() {
            // Per existing implementation, skipStep may create entry
            WorkflowMonitor.startExecution("e1", "wf", 2)
            WorkflowMonitor.skipStep("e1", "never-started")
            val metrics = WorkflowMonitor.getMetrics("e1")!!
            assertEquals(1, metrics.skippedSteps)
        }

        @Test
        fun `multiple completeExecution calls on same execution`() {
            WorkflowMonitor.startExecution("e1", "wf", 1)
            WorkflowMonitor.completeExecution("e1", success = true)
            // Second call should not throw or corrupt state
            WorkflowMonitor.completeExecution("e1", success = false)
            val metrics = WorkflowMonitor.getMetrics("e1")!!
            // Status should reflect the last call
            assertNotNull(metrics.endTime)
        }
    }

    // =========================================================================
    // Phase 11 regressions
    // =========================================================================

    @Nested
    inner class Phase11RegressionTest {

        @Test
        fun `addEvent falls back to registered ancestor for derived sub-step names`() {
            WorkflowMonitor.startExecution("e1", "wf", 1)
            WorkflowMonitor.startStep("e1", "classify")
            // Classifier / group-chat / iterate sub-agents emit events under derived
            // names ("step:classifier", "step:group_chat:round1:alice") that are never
            // registered via startStep — events must land on the parent step instead
            // of being silently dropped (token accounting depended on them).
            WorkflowMonitor.addEvent(
                "e1", "classify:classifier",
                AgentEvent(type = "llm_call_completed", category = "llm", summary = "ok", inputTokens = 7, outputTokens = 3, totalTokens = 10)
            )
            WorkflowMonitor.addEvent(
                "e1", "classify:group_chat:round1:alice",
                AgentEvent(type = "tool_call_completed", category = "tool", summary = "t")
            )
            val step = WorkflowMonitor.getMetrics("e1")!!.stepMetrics["classify"]!!
            assertEquals(2, step.eventsSnapshot().size)
            assertEquals(10L, step.getTotalTokens())
        }

        @Test
        fun `addEvent still drops events for fully unknown steps`() {
            WorkflowMonitor.startExecution("e1", "wf", 1)
            WorkflowMonitor.startStep("e1", "alpha")
            WorkflowMonitor.addEvent(
                "e1", "beta:classifier",
                AgentEvent(type = "x", category = "llm", summary = "s")
            )
            assertTrue(WorkflowMonitor.getMetrics("e1")!!.stepMetrics["alpha"]!!.eventsSnapshot().isEmpty())
        }

        @Test
        fun `Claude progress heartbeat replaces previous heartbeat for same invocation`() {
            WorkflowMonitor.startExecution("e1", "wf", 1)
            WorkflowMonitor.startStep("e1", "analyze")

            WorkflowMonitor.addEvent(
                "e1", "analyze",
                AgentEvent(
                    type = "claude_code_sub_agent_progress",
                    category = "agent",
                    subCategory = "claude_code_agent",
                    summary = "⏳ Claude Code Sub Agent 正在思考: reviewer#abc12345 (8s)",
                    detail = "invocation_id=abc12345, phase=running, elapsed_ms=8000"
                )
            )
            WorkflowMonitor.addEvent(
                "e1", "analyze",
                AgentEvent(
                    type = "claude_code_sub_agent_progress",
                    category = "agent",
                    subCategory = "claude_code_agent",
                    summary = "⏳ Claude Code Sub Agent 正在思考: reviewer#def67890 (8s)",
                    detail = "invocation_id=def67890, phase=running, elapsed_ms=8000"
                )
            )
            WorkflowMonitor.addEvent(
                "e1", "analyze",
                AgentEvent(
                    type = "claude_code_sub_agent_progress",
                    category = "agent",
                    subCategory = "claude_code_agent",
                    summary = "⏳ Claude Code Sub Agent 正在思考: reviewer#abc12345 (16s)",
                    detail = "invocation_id=abc12345, phase=running, elapsed_ms=16000"
                )
            )

            val events = WorkflowMonitor.getMetrics("e1")!!.stepMetrics["analyze"]!!.eventsSnapshot()
            assertEquals(2, events.size)
            assertTrue(events.any { it.summary.contains("abc12345 (16s)") })
            assertTrue(events.any { it.summary.contains("def67890 (8s)") })
            assertTrue(events.none { it.summary.contains("abc12345 (8s)") })
        }

        @Test
        fun `cancelExecution keeps completed history bounded`() {
            // Bound is 1000; cancelling more than that must not grow the list past it.
            repeat(1005) { i ->
                WorkflowMonitor.startExecution("c$i", "wf", 1)
                WorkflowMonitor.cancelExecution("c$i")
            }
            assertTrue(WorkflowMonitor.getCompletedExecutions(limit = 2000).size <= 1000)
        }
    }
}
