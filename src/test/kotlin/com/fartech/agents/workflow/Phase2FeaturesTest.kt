package com.fartech.agents.workflow

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class Phase2FeaturesTest {

    /**
     * Test state machine engine with event-driven transitions
     */
    @Test
    fun `test state machine transitions`() = runBlocking {
        val config = StateMachineConfig(
            states = mapOf(
                "idle" to StateDefinition(
                    name = "idle",
                    transitions = listOf(
                        StateTransition(event = "start", target = "running")
                    )
                ),
                "running" to StateDefinition(
                    name = "running",
                    transitions = listOf(
                        StateTransition(event = "pause", target = "paused"),
                        StateTransition(event = "stop", target = "stopped")
                    )
                ),
                "paused" to StateDefinition(
                    name = "paused",
                    transitions = listOf(
                        StateTransition(event = "resume", target = "running"),
                        StateTransition(event = "stop", target = "stopped")
                    )
                ),
                "stopped" to StateDefinition(name = "stopped")
            ),
            initialState = "idle",
            finalStates = listOf("stopped")
        )

        val context = WorkflowExecutionContext("test-workflow", "exec-1")
        val engine = StateMachineEngine(config, context)

        // Initial state should be idle
        assertEquals("idle", engine.getCurrentState())
        assertFalse(engine.isFinalState())

        // Trigger start event
        var result = engine.triggerEvent("start")
        assertTrue(result.success)
        assertEquals("idle", result.fromState)
        assertEquals("running", result.toState)
        assertEquals("running", engine.getCurrentState())

        // Trigger pause event
        result = engine.triggerEvent("pause")
        assertTrue(result.success)
        assertEquals("running", result.fromState)
        assertEquals("paused", result.toState)

        // Trigger resume event
        result = engine.triggerEvent("resume")
        assertTrue(result.success)
        assertEquals("paused", result.fromState)
        assertEquals("running", result.toState)

        // Trigger stop event
        result = engine.triggerEvent("stop")
        assertTrue(result.success)
        assertEquals("running", result.fromState)
        assertEquals("stopped", result.toState)
        assertTrue(engine.isFinalState())

        // History should contain all transitions
        val history = engine.getHistory()
        assertEquals(4, history.size)
    }

    /**
     * Test state machine with guard conditions
     */
    @Test
    fun `test state machine with conditions`() = runBlocking {
        val config = StateMachineConfig(
            states = mapOf(
                "init" to StateDefinition(
                    name = "init",
                    transitions = listOf(
                        StateTransition(
                            event = "proceed",
                            target = "success",
                            condition = "status == passed"
                        ),
                        StateTransition(
                            event = "proceed",
                            target = "failure",
                            condition = "status == failed"
                        )
                    )
                ),
                "success" to StateDefinition(name = "success"),
                "failure" to StateDefinition(name = "failure")
            ),
            initialState = "init"
        )

        val context = WorkflowExecutionContext("test-workflow", "exec-2")
        context.variables["status"] = "passed"

        val engine = StateMachineEngine(config, context)

        // Should transition to success because status == passed
        val result = engine.triggerEvent("proceed")
        assertTrue(result.success)
        assertEquals("success", engine.getCurrentState())
    }

    @Test
    fun `test state machine selects later guarded transition`() = runBlocking {
        val config = StateMachineConfig(
            states = mapOf(
                "init" to StateDefinition(
                    name = "init",
                    transitions = listOf(
                        StateTransition(
                            event = "proceed",
                            target = "success",
                            condition = "status == passed"
                        ),
                        StateTransition(
                            event = "proceed",
                            target = "failure",
                            condition = "status == failed"
                        )
                    )
                ),
                "success" to StateDefinition(name = "success"),
                "failure" to StateDefinition(name = "failure")
            ),
            initialState = "init"
        )

        val context = WorkflowExecutionContext("test-workflow", "exec-2b")
        context.variables["status"] = "failed"

        val engine = StateMachineEngine(config, context)
        val result = engine.triggerEvent("proceed")

        assertTrue(result.success)
        assertEquals("failure", result.toState)
        assertEquals("failure", engine.getCurrentState())
    }

    /**
     * Test workflow monitor with real-time tracking
     */
    @Test
    fun `test workflow monitor tracking`() {
        val executionId = "test-exec-123"
        val workflowName = "test-workflow"

        // Start execution
        val metrics = WorkflowMonitor.startExecution(executionId, workflowName, totalSteps = 3)
        assertEquals(executionId, metrics.executionId)
        assertEquals(workflowName, metrics.workflowName)
        assertEquals(3, metrics.totalSteps)
        assertEquals(ExecutionStatus.RUNNING, metrics.status)

        // Start and complete steps
        WorkflowMonitor.startStep(executionId, "step1")
        Thread.sleep(10)
        WorkflowMonitor.completeStep(executionId, "step1", success = true)

        WorkflowMonitor.startStep(executionId, "step2")
        Thread.sleep(10)
        WorkflowMonitor.completeStep(executionId, "step2", success = false, error = "Test error")

        WorkflowMonitor.startStep(executionId, "step3")
        WorkflowMonitor.skipStep(executionId, "step3")

        // Check metrics
        val retrievedMetrics = WorkflowMonitor.getMetrics(executionId)
        assertNotNull(retrievedMetrics)
        assertEquals(1, retrievedMetrics?.completedSteps)
        assertEquals(1, retrievedMetrics?.failedSteps)
        assertEquals(1, retrievedMetrics?.skippedSteps)

        // Complete execution
        WorkflowMonitor.completeExecution(executionId, success = false)

        // Should be in completed list
        val completed = WorkflowMonitor.getCompletedExecutions()
        assertTrue(completed.any { it.executionId == executionId })
    }

    /**
     * Test workflow version control
     */
    @Test
    fun `test workflow version control`(@TempDir tempDir: File) {
        val versionControl = WorkflowVersionControl(tempDir.absolutePath)

        // Create a test workflow file
        val workflowFile = File(tempDir, "test-workflow.yaml")
        workflowFile.writeText(
            """
            name: test-workflow
            version: 1.0.0
            agents:
              agent1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: step1
                agent: agent1
                input: "Test"
        """.trimIndent()
        )

        val workflow = WorkflowParser.parseFile(workflowFile.absolutePath)

        // Save version
        val version1 = versionControl.saveVersion(
            workflow,
            workflowFile.absolutePath,
            description = "Initial version",
            createdBy = "test-user"
        )

        assertEquals("1.0.0", version1.version)
        assertEquals("test-workflow", version1.workflowName)
        assertEquals("Initial version", version1.description)
        assertEquals("test-user", version1.createdBy)

        // Modify workflow
        workflowFile.writeText(
            """
            name: test-workflow
            version: 1.1.0
            agents:
              agent1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: step1
                agent: agent1
                input: "Modified"
        """.trimIndent()
        )

        val workflow2 = WorkflowParser.parseFile(workflowFile.absolutePath)

        // Save another version
        val version2 = versionControl.saveVersion(
            workflow2,
            workflowFile.absolutePath,
            description = "Updated version"
        )

        assertEquals("1.1.0", version2.version)

        // List versions
        val versions = versionControl.getVersions("test-workflow")
        assertEquals(2, versions.size)

        // Compare versions
        val comparison = versionControl.compareVersions("test-workflow", "1.0.0", "1.1.0")
        assertFalse(comparison.identical)
        assertTrue(comparison.changes.isNotEmpty())

        // Rollback to version 1.0.0
        val rollbackFile = File(tempDir, "rollback.yaml")
        assertTrue(versionControl.rollback("test-workflow", "1.0.0", rollbackFile.absolutePath))

        val rolledBack = rollbackFile.readText()
        assertTrue(rolledBack.contains("version: 1.0.0"))
        assertTrue(rolledBack.contains("input: \"Test\""))
    }

    /**
     * Test workflow statistics
     */
    @Test
    fun `test workflow statistics`() {
        WorkflowMonitor.clear() // Clean slate

        val workflowName = "stats-test"

        // Simulate 5 executions
        repeat(5) { i ->
            val execId = "exec-$i"
            WorkflowMonitor.startExecution(execId, workflowName, 2)
            Thread.sleep(10)
            WorkflowMonitor.completeExecution(execId, success = i % 2 == 0) // 3 success, 2 failures
        }

        val stats = WorkflowMonitor.getWorkflowStats(workflowName)
        assertEquals(5, stats.totalExecutions)
        assertEquals(3, stats.successfulExecutions)
        assertEquals(2, stats.failedExecutions)
        assertEquals(0.6, stats.successRate, 0.01)
        assertTrue(stats.averageDuration > 0)
    }

    /**
     * Test execution report generation
     */
    @Test
    fun `test execution report generation`() {
        val executionId = "report-test-1"
        WorkflowMonitor.startExecution(executionId, "report-workflow", 2)

        WorkflowMonitor.startStep(executionId, "step1")
        Thread.sleep(10)
        WorkflowMonitor.completeStep(executionId, "step1", success = true)

        WorkflowMonitor.startStep(executionId, "step2")
        Thread.sleep(10)
        WorkflowMonitor.completeStep(executionId, "step2", success = false, error = "Test failure")

        WorkflowMonitor.completeExecution(executionId, success = false)

        val report = WorkflowMonitor.generateReport(executionId)

        assertTrue(report.contains("Workflow Execution Report"))
        assertTrue(report.contains(executionId))
        assertTrue(report.contains("report-workflow"))
        assertTrue(report.contains("step1"))
        assertTrue(report.contains("step2"))
        assertTrue(report.contains("Test failure"))
    }

    /**
     * Test priority scheduling
     */
    @Test
    fun `test priority scheduling in workflow`() {
        val yaml = """
            name: priority-test
            version: 1.0.0
            agents:
              agent1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: low-priority
                agent: agent1
                input: "Low"
                priority: 1
              - step: high-priority
                agent: agent1
                input: "High"
                priority: 10
              - step: medium-priority
                agent: agent1
                input: "Medium"
                priority: 5
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val steps = workflow.workflow

        // Verify priorities are parsed
        assertEquals(1, steps.find { it.step == "low-priority" }?.priority)
        assertEquals(10, steps.find { it.step == "high-priority" }?.priority)
        assertEquals(5, steps.find { it.step == "medium-priority" }?.priority)

        // Get topological order with priority sorting
        val sorted = WorkflowParser.getTopologicalOrder(workflow)
            .sortedByDescending { it.priority }

        // High priority should be first
        assertEquals("high-priority", sorted[0].step)
        assertEquals("medium-priority", sorted[1].step)
        assertEquals("low-priority", sorted[2].step)
    }
}
