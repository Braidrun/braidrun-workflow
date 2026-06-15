package com.fartech.agents.workflow

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/**
 * 综合测试：覆盖 workflow 模块的所有核心功能
 */
class WorkflowComprehensiveTest {

    // ==================== WorkflowModels Tests ====================

    @Test
    fun `WorkflowDefinition requires non-blank name`() {
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "",
                agents = mapOf("a" to minimalAgent()),
                workflow = listOf(minimalStep("s1", "a"))
            )
        }
    }

    @Test
    fun `WorkflowDefinition requires at least one agent`() {
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test",
                agents = emptyMap(),
                workflow = listOf(minimalStep("s1", "a"))
            )
        }
    }

    @Test
    fun `WorkflowDefinition requires at least one step`() {
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test",
                agents = mapOf("a" to minimalAgent()),
                workflow = emptyList()
            )
        }
    }

    @Test
    fun `WorkflowDefinition rejects undefined agent reference`() {
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test",
                agents = mapOf("a" to minimalAgent()),
                workflow = listOf(minimalStep("s1", "nonexistent"))
            )
        }
    }

    @Test
    fun `WorkflowDefinition rejects duplicate step names`() {
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test",
                agents = mapOf("a" to minimalAgent()),
                workflow = listOf(minimalStep("s1", "a"), minimalStep("s1", "a"))
            )
        }
    }

    @Test
    fun `WorkflowStep requires non-blank fields`() {
        assertThrows<IllegalArgumentException> { WorkflowStep(step = "", agent = "a", input = "i") }
        assertThrows<IllegalArgumentException> { WorkflowStep(step = "s", agent = "", input = "i") }
        assertThrows<IllegalArgumentException> { WorkflowStep(step = "s", agent = "a", input = "") }
    }

    @Test
    fun `RetryConfig defaults are correct`() {
        val retry = RetryConfig()
        assertEquals(3, retry.maxAttempts)
        assertEquals(BackoffStrategy.EXPONENTIAL, retry.backoff)
        assertEquals(1000L, retry.initialDelay)
        assertEquals(60000L, retry.maxDelay)
    }

    @Test
    fun `WorkflowExecutionContext variable and step output operations`() {
        val ctx = WorkflowExecutionContext("wf", "exec-1")
        ctx.setVariable("key1", "val1")
        assertEquals("val1", ctx.getVariable("key1"))
        assertNull(ctx.getVariable("missing"))

        ctx.setStepOutput("step1", "output1")
        assertEquals("output1", ctx.getStepOutput("step1"))
        assertNull(ctx.getStepOutput("missing"))
    }

    @Test
    fun `WorkflowMetrics getDuration and getSuccessRate`() {
        val m = WorkflowMetrics(executionId = "e1", workflowName = "w1", startTime = 1000, totalSteps = 4)
        m.endTime = 2000
        assertEquals(1000L, m.getDuration())

        m.completedSteps = 3
        // successRate = completed / total
        assertEquals(0.75, m.getSuccessRate(), 0.001)
    }

    @Test
    fun `StepMetrics getDuration with null endTime returns 0`() {
        val sm = StepMetrics(stepName = "s1", startTime = 1000)
        // endTime is null
        assertTrue(sm.getDuration() >= 0)
    }

    // ==================== WorkflowParser Tests ====================

    @Test
    fun `parser validates blank condition`() {
        val yaml = """
            name: test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                condition: "   "
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `parser validates empty parallel tasks`() {
        val yaml = """
            name: test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                parallel:
                  tasks: []
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `parser validates invalid parallel max_parallel`() {
        val yaml = """
            name: test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                parallel:
                  tasks: ["task1"]
                  max_parallel: 0
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `parser validates undefined dependency`() {
        val yaml = """
            name: test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                depends_on:
                  - nonexistent
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `parser validates transition references to undefined step`() {
        val yaml = """
            name: test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                on_success:
                  - next: nonexistent
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `parser validates rollback reference to undefined step`() {
        val yaml = """
            name: test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                on_failure:
                  - rollback: nonexistent
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `parser file not found throws exception`() {
        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseFile("/nonexistent/path.yaml")
        }
    }

    @Test
    fun `parser toYaml roundtrip`() {
        val yaml = """
            name: roundtrip
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "hello"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val serialized = WorkflowParser.toYaml(workflow)
        val reparsed = WorkflowParser.parseYaml(serialized)
        assertEquals(workflow.name, reparsed.name)
        assertEquals(workflow.version, reparsed.version)
        assertEquals(workflow.workflow.size, reparsed.workflow.size)
    }

    @Test
    fun `parser saveToFile and parseFile roundtrip`(@TempDir tempDir: File) {
        val yaml = """
            name: file-test
            version: 2.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val filePath = File(tempDir, "test.yaml").absolutePath
        WorkflowParser.saveToFile(workflow, filePath)

        val loaded = WorkflowParser.parseFile(filePath)
        assertEquals("file-test", loaded.name)
        assertEquals("2.0.0", loaded.version)
    }

    @Test
    fun `parser topological order with complex dependencies`() {
        val yaml = """
            name: complex-deps
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: d
                agent: a1
                input: "d"
                depends_on: [b, c]
              - step: b
                agent: a1
                input: "b"
                depends_on: [a]
              - step: c
                agent: a1
                input: "c"
                depends_on: [a]
              - step: a
                agent: a1
                input: "a"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val order = WorkflowParser.getTopologicalOrder(workflow)
        val names = order.map { it.step }

        assertEquals(4, names.size)
        assertTrue(names.indexOf("a") < names.indexOf("b"))
        assertTrue(names.indexOf("a") < names.indexOf("c"))
        assertTrue(names.indexOf("b") < names.indexOf("d"))
        assertTrue(names.indexOf("c") < names.indexOf("d"))
    }

    @Test
    fun `parser getWorkflowSummary includes all info`() {
        val yaml = """
            name: summary-wf
            version: 3.0.0
            description: A test workflow
            agents:
              agent_x:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: first
                agent: agent_x
                input: "go"
                depends_on: []
              - step: second
                agent: agent_x
                input: "go2"
                depends_on: [first]
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertTrue(summary.contains("summary-wf"))
        assertTrue(summary.contains("3.0.0"))
        assertTrue(summary.contains("A test workflow"))
        assertTrue(summary.contains("agent_x"))
        assertTrue(summary.contains("first"))
        assertTrue(summary.contains("second"))
        assertTrue(summary.contains("depends on"))
    }

    // ==================== StateMachineEngine Tests ====================

    @Test
    fun `state machine invalid event returns failure`() = runBlocking {
        val config = StateMachineConfig(
            states = mapOf(
                "idle" to StateDefinition(
                    name = "idle", transitions = listOf(
                        StateTransition(event = "go", target = "done")
                    )
                ),
                "done" to StateDefinition(name = "done")
            ),
            initialState = "idle",
            finalStates = listOf("done")
        )
        val engine = StateMachineEngine(config, WorkflowExecutionContext("w", "e"))
        val result = engine.triggerEvent("invalid_event")
        assertFalse(result.success)
        assertEquals("idle", engine.getCurrentState())
    }

    @Test
    fun `state machine condition not met blocks transition`() = runBlocking {
        val config = StateMachineConfig(
            states = mapOf(
                "start" to StateDefinition(
                    name = "start", transitions = listOf(
                        StateTransition(event = "go", target = "end", condition = "flag == yes")
                    )
                ),
                "end" to StateDefinition(name = "end")
            ),
            initialState = "start"
        )
        val ctx = WorkflowExecutionContext("w", "e")
        ctx.variables["flag"] = "no"
        val engine = StateMachineEngine(config, ctx)

        val result = engine.triggerEvent("go")
        assertFalse(result.success)
        assertEquals("start", engine.getCurrentState())
    }

    @Test
    fun `state machine onEnter and onExit actions execute`() = runBlocking {
        val ctx = WorkflowExecutionContext("w", "e")
        val config = StateMachineConfig(
            states = mapOf(
                "a" to StateDefinition(
                    name = "a",
                    onExit = listOf(TransitionAction(action = "set_variable", key = "exited_a", value = "true")),
                    transitions = listOf(StateTransition(event = "next", target = "b"))
                ),
                "b" to StateDefinition(
                    name = "b",
                    onEnter = listOf(TransitionAction(action = "set_variable", key = "entered_b", value = "true"))
                )
            ),
            initialState = "a"
        )
        val engine = StateMachineEngine(config, ctx)
        engine.triggerEvent("next")

        assertEquals("true", ctx.variables["exited_a"])
        assertEquals("true", ctx.variables["entered_b"])
    }

    @Test
    fun `state machine initializes initial state onEnter actions`() = runBlocking {
        val ctx = WorkflowExecutionContext("w", "e")
        val config = StateMachineConfig(
            states = mapOf(
                "a" to StateDefinition(
                    name = "a",
                    onEnter = listOf(TransitionAction(action = "set_variable", key = "entered_a", value = "true")),
                    transitions = listOf(StateTransition(event = "next", target = "b"))
                ),
                "b" to StateDefinition(name = "b")
            ),
            initialState = "a"
        )

        val engine = StateMachineEngine(config, ctx)
        engine.initialize()

        assertEquals("true", ctx.variables["entered_a"])
    }

    @Test
    fun `state machine transition actions execute`() = runBlocking {
        val ctx = WorkflowExecutionContext("w", "e")
        val config = StateMachineConfig(
            states = mapOf(
                "a" to StateDefinition(
                    name = "a",
                    transitions = listOf(
                        StateTransition(
                            event = "go", target = "b",
                            actions = listOf(
                                TransitionAction(
                                    action = "set_variable",
                                    key = "transitioned",
                                    value = "yes"
                                )
                            )
                        )
                    )
                ),
                "b" to StateDefinition(name = "b")
            ),
            initialState = "a"
        )
        val engine = StateMachineEngine(config, ctx)
        engine.triggerEvent("go")
        assertEquals("yes", ctx.variables["transitioned"])
    }

    @Test
    fun `state machine reset clears history`() = runBlocking {
        val config = StateMachineConfig(
            states = mapOf(
                "a" to StateDefinition(
                    name = "a", transitions = listOf(
                        StateTransition(event = "go", target = "b")
                    )
                ),
                "b" to StateDefinition(name = "b")
            ),
            initialState = "a"
        )
        val engine = StateMachineEngine(config, WorkflowExecutionContext("w", "e"))
        engine.triggerEvent("go")
        assertEquals("b", engine.getCurrentState())
        assertEquals(1, engine.getHistory().size)

        engine.reset()
        assertEquals("a", engine.getCurrentState())
        assertEquals(0, engine.getHistory().size)
    }

    @Test
    fun `state machine evaluateCondition numeric comparisons`() = runBlocking {
        val ctx = WorkflowExecutionContext("w", "e")
        ctx.variables["score"] = "75"
        val config = StateMachineConfig(
            states = mapOf(
                "check" to StateDefinition(
                    name = "check", transitions = listOf(
                        StateTransition(event = "eval", target = "pass", condition = "score >= 60")
                    )
                ),
                "pass" to StateDefinition(name = "pass")
            ),
            initialState = "check"
        )
        val engine = StateMachineEngine(config, ctx)
        val result = engine.triggerEvent("eval")
        assertTrue(result.success)
        assertEquals("pass", engine.getCurrentState())
    }

    @Test
    fun `state machine evaluateCondition contains operator`() = runBlocking {
        val ctx = WorkflowExecutionContext("w", "e")
        ctx.variables["msg"] = "hello world"
        val config = StateMachineConfig(
            states = mapOf(
                "s" to StateDefinition(
                    name = "s", transitions = listOf(
                        StateTransition(event = "check", target = "found", condition = "msg contains world")
                    )
                ),
                "found" to StateDefinition(name = "found")
            ),
            initialState = "s"
        )
        val engine = StateMachineEngine(config, ctx)
        val result = engine.triggerEvent("check")
        assertTrue(result.success)
    }

    @Test
    fun `state machine tries later transition when first guard fails`() = runBlocking {
        val ctx = WorkflowExecutionContext("w", "e")
        ctx.variables["status"] = "failed"
        val config = StateMachineConfig(
            states = mapOf(
                "review" to StateDefinition(
                    name = "review",
                    transitions = listOf(
                        StateTransition(event = "complete", target = "approved", condition = "status == approved"),
                        StateTransition(event = "complete", target = "rejected", condition = "status == failed")
                    )
                ),
                "approved" to StateDefinition(name = "approved"),
                "rejected" to StateDefinition(name = "rejected")
            ),
            initialState = "review"
        )

        val engine = StateMachineEngine(config, ctx)
        val result = engine.triggerEvent("complete")

        assertTrue(result.success)
        assertEquals("rejected", engine.getCurrentState())
    }

    @Test
    fun `state machine config rejects undefined initial state`() {
        assertThrows<IllegalArgumentException> {
            StateMachineConfig(
                states = mapOf("a" to StateDefinition(name = "a")),
                initialState = "nonexistent"
            )
        }
    }

    // ==================== WorkflowMonitor Tests ====================

    @BeforeEach
    fun clearMonitor() {
        WorkflowMonitor.clear()
    }

    @Test
    fun `monitor skipStep without prior startStep creates entry`() {
        val execId = "skip-test"
        WorkflowMonitor.startExecution(execId, "wf", 2)
        // Skip without starting first - should not crash
        WorkflowMonitor.skipStep(execId, "step1")

        val metrics = WorkflowMonitor.getMetrics(execId)
        assertNotNull(metrics)
        assertEquals(1, metrics.skippedSteps)
        assertEquals(ExecutionStatus.CANCELLED, metrics.stepMetrics["step1"]?.status)
    }

    @Test
    fun `monitor cancel execution`() {
        val execId = "cancel-test"
        WorkflowMonitor.startExecution(execId, "wf", 1)
        WorkflowMonitor.cancelExecution(execId)

        val metrics = WorkflowMonitor.getMetrics(execId)
        assertNotNull(metrics)
        assertEquals(ExecutionStatus.CANCELLED, metrics.status)
        // Should not be in active executions
        assertTrue(WorkflowMonitor.getActiveExecutions().none { it.executionId == execId })
    }

    @Test
    fun `monitor awaiting approval`() {
        val execId = "approval-test"
        WorkflowMonitor.startExecution(execId, "wf", 1)
        WorkflowMonitor.startStep(execId, "s1")
        WorkflowMonitor.awaitingApproval(execId, "s1")

        val metrics = WorkflowMonitor.getMetrics(execId)
        assertEquals(ExecutionStatus.AWAITING_APPROVAL, metrics?.status)
        assertEquals(ExecutionStatus.AWAITING_APPROVAL, metrics?.stepMetrics?.get("s1")?.status)
    }

    @Test
    fun `monitor recordRetry increments count`() {
        val execId = "retry-test"
        WorkflowMonitor.startExecution(execId, "wf", 1)
        WorkflowMonitor.startStep(execId, "s1")
        WorkflowMonitor.recordRetry(execId, "s1")
        WorkflowMonitor.recordRetry(execId, "s1")

        val metrics = WorkflowMonitor.getMetrics(execId)
        assertEquals(2, metrics?.stepMetrics?.get("s1")?.retryCount)
    }

    @Test
    fun `monitor getActiveExecutions returns active only`() {
        WorkflowMonitor.startExecution("active1", "wf", 1)
        WorkflowMonitor.startExecution("active2", "wf", 1)
        WorkflowMonitor.completeExecution("active1", true)

        val active = WorkflowMonitor.getActiveExecutions()
        assertEquals(1, active.size)
        assertEquals("active2", active[0].executionId)
    }

    @Test
    fun `monitor completed history respects limit`() {
        repeat(5) { i ->
            WorkflowMonitor.startExecution("h-$i", "wf", 1)
            WorkflowMonitor.completeExecution("h-$i", true)
        }
        val history = WorkflowMonitor.getCompletedExecutions(limit = 3)
        assertEquals(3, history.size)
    }

    @Test
    fun `monitor generate report for unknown execution`() {
        val report = WorkflowMonitor.generateReport("nonexistent")
        assertTrue(report.contains("not found"))
    }

    @Test
    fun `monitor getWorkflowStats empty workflow`() {
        val stats = WorkflowMonitor.getWorkflowStats("nonexistent")
        assertEquals(0, stats.totalExecutions)
        assertEquals(0.0, stats.successRate)
    }

    // ==================== WorkflowVersionControl Tests ====================

    @Test
    fun `version control prune keeps only N versions`(@TempDir tempDir: File) {
        val vc = WorkflowVersionControl(tempDir.absolutePath)
        val wfFile = File(tempDir, "wf.yaml")

        repeat(5) { i ->
            wfFile.writeText(
                """
                name: prune-test
                version: 1.$i.0
                agents:
                  a1:
                    type: universal_agent
                    strategy: just_work
                    tools: [exit]
                    llm:
                      model: gpt-4
                      provider: openai
                workflow:
                  - step: s1
                    agent: a1
                    input: "v$i"
            """.trimIndent()
            )
            val wf = WorkflowParser.parseFile(wfFile.absolutePath)
            vc.saveVersion(wf, wfFile.absolutePath, description = "v1.$i.0")
        }

        assertEquals(5, vc.getVersions("prune-test").size)
        vc.pruneVersions("prune-test", keepCount = 2)
        assertEquals(2, vc.getVersions("prune-test").size)
    }

    @Test
    fun `version control compare nonexistent versions`(@TempDir tempDir: File) {
        val vc = WorkflowVersionControl(tempDir.absolutePath)
        val comparison = vc.compareVersions("missing", "1.0", "2.0")
        assertFalse(comparison.identical)
        assertTrue(comparison.changes.isNotEmpty())
    }

    @Test
    fun `version control rollback nonexistent version returns false`(@TempDir tempDir: File) {
        val vc = WorkflowVersionControl(tempDir.absolutePath)
        assertFalse(vc.rollback("missing", "1.0", "/tmp/out.yaml"))
    }

    @Test
    fun `version control compare identical versions`(@TempDir tempDir: File) {
        val vc = WorkflowVersionControl(tempDir.absolutePath)
        val wfFile = File(tempDir, "wf.yaml")
        val content = """
            name: identical-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
        """.trimIndent()
        wfFile.writeText(content)

        val wf = WorkflowParser.parseFile(wfFile.absolutePath)
        vc.saveVersion(wf, wfFile.absolutePath, description = "first")
        // Save same content again (different call, same checksum)
        vc.saveVersion(wf, wfFile.absolutePath, description = "second")

        val versions = vc.getVersions("identical-test")
        assertEquals(2, versions.size)
        // Both have same checksum
        val comparison = vc.compareVersions("identical-test", "1.0.0", "1.0.0")
        assertTrue(comparison.identical)
    }

    // ==================== WorkflowExecutor - Backoff Calculation ====================

    @Test
    fun `backoff strategies calculate correctly`() {
        val retry = RetryConfig(
            maxAttempts = 5,
            backoff = BackoffStrategy.CONSTANT,
            initialDelay = 100,
            maxDelay = 10000
        )
        // CONSTANT: always initialDelay
        assertEquals(100L, calculateBackoff(retry.copy(backoff = BackoffStrategy.CONSTANT), 0))
        assertEquals(100L, calculateBackoff(retry.copy(backoff = BackoffStrategy.CONSTANT), 3))

        // LINEAR: initialDelay * (attempt + 1), capped at maxDelay
        assertEquals(100L, calculateBackoff(retry.copy(backoff = BackoffStrategy.LINEAR), 0))
        assertEquals(200L, calculateBackoff(retry.copy(backoff = BackoffStrategy.LINEAR), 1))
        assertEquals(300L, calculateBackoff(retry.copy(backoff = BackoffStrategy.LINEAR), 2))

        // EXPONENTIAL: initialDelay * 2^attempt, capped at maxDelay
        assertEquals(100L, calculateBackoff(retry.copy(backoff = BackoffStrategy.EXPONENTIAL), 0))
        assertEquals(200L, calculateBackoff(retry.copy(backoff = BackoffStrategy.EXPONENTIAL), 1))
        assertEquals(400L, calculateBackoff(retry.copy(backoff = BackoffStrategy.EXPONENTIAL), 2))
    }

    @Test
    fun `backoff respects maxDelay`() {
        val retry = RetryConfig(
            maxAttempts = 10,
            backoff = BackoffStrategy.EXPONENTIAL,
            initialDelay = 1000,
            maxDelay = 5000
        )
        // 1000 * 2^3 = 8000, but capped at 5000
        assertEquals(5000L, calculateBackoff(retry, 3))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `parser handles workflow with variables`() {
        val yaml = """
            name: vars-test
            version: 1.0.0
            variables:
              env: production
              region: us-east
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "Deploy to {{var:env}}"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        assertEquals("production", workflow.variables["env"])
        assertEquals("us-east", workflow.variables["region"])
    }

    @Test
    fun `parser handles workflow with error handling config`() {
        val yaml = """
            name: error-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            error_handling:
              max_retries: 5
              retry_delay: "10s"
              continue_on_error: true
            workflow:
              - step: s1
                agent: a1
                input: "test"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        assertNotNull(workflow.errorHandling)
        assertEquals(5, workflow.errorHandling.maxRetries)
        assertTrue(workflow.errorHandling.continueOnError)
    }

    @Test
    fun `parser handles workflow with timeout config`() {
        val yaml = """
            name: timeout-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            timeout:
              total: "7200s"
              per_step: "300s"
            workflow:
              - step: s1
                agent: a1
                input: "test"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        assertNotNull(workflow.timeout)
        assertEquals("7200s", workflow.timeout.total)
        assertEquals("300s", workflow.timeout.perStep)
    }

    @Test
    fun `parser handles step with retry config`() {
        val yaml = """
            name: retry-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                retry:
                  max_attempts: 5
                  backoff: linear
                  initial_delay: 2000
                  max_delay: 30000
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val retry = workflow.workflow[0].retry
        assertNotNull(retry)
        assertEquals(5, retry.maxAttempts)
        assertEquals(BackoffStrategy.LINEAR, retry.backoff)
        assertEquals(2000L, retry.initialDelay)
        assertEquals(30000L, retry.maxDelay)
    }

    @Test
    fun `parser handles step with manual approval`() {
        val yaml = """
            name: approval-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                manual_approval:
                  enabled: true
                  approvers: [admin, manager]
                  timeout: 7200
                  approval_message: "Please review"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val approval = workflow.workflow[0].manualApproval
        assertNotNull(approval)
        assertTrue(approval.enabled)
        assertEquals(listOf("admin", "manager"), approval.approvers)
        assertEquals(7200L, approval.timeout)
        assertEquals("Please review", approval.approvalMessage)
    }

    @Test
    fun `parser handles three-level circular dependency`() {
        val yaml = """
            name: circular3
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "t"
                depends_on: [s3]
              - step: s2
                agent: a1
                input: "t"
                depends_on: [s1]
              - step: s3
                agent: a1
                input: "t"
                depends_on: [s2]
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    // ==================== continueOnError & onFailure Tests ====================

    @Test
    fun `parser handles workflow with continueOnError enabled`() {
        val yaml = """
            name: continue-on-error-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            error_handling:
              max_retries: 2
              continue_on_error: true
            workflow:
              - step: s1
                agent: a1
                input: "test"
              - step: s2
                agent: a1
                input: "test"
                depends_on: [s1]
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val errorHandling = workflow.errorHandling
        assertNotNull(errorHandling)
        assertTrue(errorHandling.continueOnError)
        assertEquals(2, errorHandling.maxRetries)
    }

    @Test
    fun `parser handles workflow with on_error transitions`() {
        val yaml = """
            name: workflow-on-error-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            error_handling:
              on_error:
                - next: recover
                  parallel: [notify_team]
                  message: "workflow failed"
            workflow:
              - step: risky
                agent: a1
                input: "test"
              - step: recover
                agent: a1
                input: "recover"
              - step: notify_team
                agent: a1
                input: "notify"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val errorHandling = workflow.errorHandling
        assertNotNull(errorHandling)
        assertEquals(1, errorHandling.onError.size)
        assertEquals("recover", errorHandling.onError[0].next)
        assertEquals(listOf("notify_team"), errorHandling.onError[0].parallel)
        assertEquals("workflow failed", errorHandling.onError[0].message)
    }

    @Test
    fun `parser handles step with onFailure transitions`() {
        val yaml = """
            name: failure-transition-test
            version: 1.0.0
            agents:
              a1:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: s1
                agent: a1
                input: "test"
                on_failure:
                  - notify: slack
                    message: "Step s1 failed"
                  - rollback: s1
              - step: s2
                agent: a1
                input: "test"
                on_success:
                  - next: s1
                on_failure:
                  - stop: true
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val s1 = workflow.workflow.find { it.step == "s1" }!!
        assertEquals(2, s1.onFailure.size)
        assertEquals("slack", s1.onFailure[0].notify)
        assertEquals("Step s1 failed", s1.onFailure[0].message)
        assertEquals("s1", s1.onFailure[1].rollback)

        val s2 = workflow.workflow.find { it.step == "s2" }!!
        assertEquals(1, s2.onSuccess.size)
        assertEquals("s1", s2.onSuccess[0].next)
        assertTrue(s2.onFailure[0].stop)
    }

    @Test
    fun `monitor tracks skipped steps correctly`() {
        val execId = "skip-tracking-test"
        WorkflowMonitor.startExecution(execId, "wf", 3)

        // Skip two steps
        WorkflowMonitor.skipStep(execId, "step1")
        WorkflowMonitor.skipStep(execId, "step2")

        // Complete one step
        WorkflowMonitor.startStep(execId, "step3")
        WorkflowMonitor.completeStep(execId, "step3", success = true)

        val metrics = WorkflowMonitor.getMetrics(execId)
        assertNotNull(metrics)
        assertEquals(2, metrics.skippedSteps)
        assertEquals(1, metrics.completedSteps)
        assertEquals(ExecutionStatus.CANCELLED, metrics.stepMetrics["step1"]?.status)
        assertEquals(ExecutionStatus.CANCELLED, metrics.stepMetrics["step2"]?.status)
        assertEquals(ExecutionStatus.COMPLETED, metrics.stepMetrics["step3"]?.status)
    }

    @Test
    fun `monitor completeStep tracks failure correctly`() {
        val execId = "failure-tracking-test"
        WorkflowMonitor.startExecution(execId, "wf", 2)

        WorkflowMonitor.startStep(execId, "step1")
        WorkflowMonitor.completeStep(execId, "step1", success = true)

        WorkflowMonitor.startStep(execId, "step2")
        WorkflowMonitor.completeStep(execId, "step2", success = false, error = "Something went wrong")

        val metrics = WorkflowMonitor.getMetrics(execId)
        assertNotNull(metrics)
        assertEquals(1, metrics.completedSteps)
        assertEquals(1, metrics.failedSteps)
        assertEquals(ExecutionStatus.COMPLETED, metrics.stepMetrics["step1"]?.status)
        assertEquals(ExecutionStatus.FAILED, metrics.stepMetrics["step2"]?.status)
        assertEquals("Something went wrong", metrics.stepMetrics["step2"]?.error)
    }

    @Test
    fun `ErrorHandlingConfig defaults are correct`() {
        val config = ErrorHandlingConfig()
        assertEquals(3, config.maxRetries)
        assertEquals("5s", config.retryDelay)
        assertTrue(config.onError.isEmpty())
        assertFalse(config.continueOnError)
    }

    @Test
    fun `WorkflowExecutionResult durationSeconds calculated correctly`() {
        val result = WorkflowExecutionResult(
            workflowName = "test",
            success = true,
            startTime = 1000L,
            endTime = 4500L,
            duration = 3500L
        )
        assertEquals(3.5, result.durationSeconds)
    }

    @Test
    fun `StepExecutionResult durationSeconds calculated correctly`() {
        val result = StepExecutionResult(
            stepName = "s1",
            agentName = "a1",
            success = true,
            startTime = 1000L,
            endTime = 2500L,
            duration = 1500L
        )
        assertEquals(1.5, result.durationSeconds)
    }

    // ==================== AgentPresetRegistry Tests ====================

    @Test
    fun `preset registry contains builtin presets`() {
        val presets = AgentPresetRegistry.getAll()
        assertTrue(presets.isNotEmpty())
        assertTrue(presets.any { it.id == "universal" })
        assertTrue(presets.any { it.id == "coder" })
        assertTrue(presets.any { it.id == "researcher" })
        assertTrue(presets.any { it.id == "lightweight" })
        assertTrue(presets.any { it.id == "word_document" })
        assertTrue(presets.any { it.id == "excel_workbook" })
        assertTrue(presets.any { it.id == "powerpoint_presentation" })
    }

    @Test
    fun `preset registry get returns correct preset`() {
        val preset = AgentPresetRegistry.get("universal")
        assertNotNull(preset)
        assertEquals("Universal Agent", preset.displayName)
        assertEquals("general", preset.category)
        assertTrue(preset.builtin)
        assertTrue(preset.parameters.containsKey("type"))
        assertEquals(JsonPrimitive("universal_agent"), preset.parameters["type"])
    }

    @Test
    fun `preset registry get returns null for unknown preset`() {
        assertNull(AgentPresetRegistry.get("nonexistent_preset"))
    }

    @Test
    fun `preset registry exists works correctly`() {
        assertTrue(AgentPresetRegistry.exists("universal"))
        assertFalse(AgentPresetRegistry.exists("nonexistent"))
    }

    @Test
    fun `preset registry getByCategory filters correctly`() {
        val generalPresets = AgentPresetRegistry.getByCategory("general")
        assertTrue(generalPresets.isNotEmpty())
        assertTrue(generalPresets.all { it.category == "general" })
    }

    @Test
    fun `preset registry getCategories returns distinct sorted categories`() {
        val categories = AgentPresetRegistry.getCategories()
        assertTrue(categories.isNotEmpty())
        assertEquals(categories.sorted(), categories)
        assertEquals(categories.distinct(), categories)
    }

    @Test
    fun `preset registry cannot remove builtin preset`() {
        assertFalse(AgentPresetRegistry.remove("universal"))
        assertTrue(AgentPresetRegistry.exists("universal"))
    }

    @Test
    fun `preset registry can register and remove custom preset`() {
        val custom = AgentPreset(
            id = "test_custom",
            displayName = "Test Custom",
            description = "For testing",
            parameters = mapOf("type" to JsonPrimitive("universal_agent"))
        )
        AgentPresetRegistry.register(custom)
        assertTrue(AgentPresetRegistry.exists("test_custom"))

        assertTrue(AgentPresetRegistry.remove("test_custom"))
        assertFalse(AgentPresetRegistry.exists("test_custom"))
    }

    @Test
    fun `preset registry resolveParameters merges preset with overrides`() {
        val overrides = mapOf(
            "system_prompt" to JsonPrimitive("Custom prompt"),
            "max_iterations" to JsonPrimitive(8196)
        )
        val resolved = AgentPresetRegistry.resolveParameters("universal", overrides)

        // 覆盖值生效
        assertEquals(JsonPrimitive("Custom prompt"), resolved["system_prompt"])
        assertEquals(JsonPrimitive(8196), resolved["max_iterations"])
        // 预设默认值保留
        assertEquals(JsonPrimitive("universal_agent"), resolved["type"])
        assertEquals(JsonPrimitive("just_work_parallel"), resolved["strategy"])
    }

    @Test
    fun `specialized office presets use dedicated tool sets`() {
        val wordTools = AgentPresetRegistry.get("word_document")!!.parameters["tool_set"] as JsonArray
        val excelTools = AgentPresetRegistry.get("excel_workbook")!!.parameters["tool_set"] as JsonArray
        val powerpointTools = AgentPresetRegistry.get("powerpoint_presentation")!!.parameters["tool_set"] as JsonArray

        assertTrue(wordTools.contains(JsonPrimitive("word")))
        assertFalse(wordTools.contains(JsonPrimitive("office")))

        assertTrue(excelTools.contains(JsonPrimitive("excel")))
        assertFalse(excelTools.contains(JsonPrimitive("office")))

        assertTrue(powerpointTools.contains(JsonPrimitive("powerpoint")))
        assertFalse(powerpointTools.contains(JsonPrimitive("office")))
    }

    @Test
    fun `preset registry resolveParameters with unknown preset returns overrides only`() {
        val overrides = mapOf("key" to JsonPrimitive("value"))
        val resolved = AgentPresetRegistry.resolveParameters("nonexistent", overrides)
        assertEquals(overrides, resolved)
    }

    @Test
    fun `preset registry deepMerge merges nested objects`() {
        val base = mapOf(
            "llm_config" to JsonObject(
                mapOf(
                    "temperature" to JsonPrimitive(0.7),
                    "models" to JsonArray(listOf(JsonPrimitive("model-a")))
                )
            ),
            "other" to JsonPrimitive("base")
        )
        val overrides = mapOf(
            "llm_config" to JsonObject(
                mapOf(
                    "temperature" to JsonPrimitive(0.9)
                )
            )
        )
        val merged = AgentPresetRegistry.deepMerge(base, overrides)

        val llm = merged["llm_config"] as JsonObject
        assertEquals(JsonPrimitive(0.9), llm["temperature"])
        // 深度合并保留未覆盖的嵌套键
        assertNotNull(llm["models"])
        assertEquals(JsonPrimitive("base"), merged["other"])
    }

    @Test
    fun `preset registry deepMerge replaces arrays entirely`() {
        val base = mapOf(
            "tool_set" to JsonArray(listOf(JsonPrimitive("exit"), JsonPrimitive("shell")))
        )
        val overrides = mapOf(
            "tool_set" to JsonArray(listOf(JsonPrimitive("web")))
        )
        val merged = AgentPresetRegistry.deepMerge(base, overrides)
        val tools = merged["tool_set"] as JsonArray
        assertEquals(1, tools.size)
        assertEquals(JsonPrimitive("web"), tools[0])
    }

    @Test
    fun `preset registry resetToBuiltins restores initial state`() {
        val customPreset = AgentPreset(
            id = "temp_test",
            displayName = "Temp",
            description = "Temp"
        )
        AgentPresetRegistry.register(customPreset)
        assertTrue(AgentPresetRegistry.exists("temp_test"))

        AgentPresetRegistry.resetToBuiltins()
        assertFalse(AgentPresetRegistry.exists("temp_test"))
        assertTrue(AgentPresetRegistry.exists("universal"))
    }

    // ==================== AgentDefinition Preset Mode Tests ====================

    @Test
    fun `AgentDefinition isPresetMode true when preset is set`() {
        val def = AgentDefinition(preset = "universal")
        assertTrue(def.isPresetMode)
    }

    @Test
    fun `AgentDefinition isPresetMode true when overrides present`() {
        val def = AgentDefinition(overrides = mapOf("key" to JsonPrimitive("val")))
        assertTrue(def.isPresetMode)
    }

    @Test
    fun `AgentDefinition isPresetMode false for legacy mode`() {
        val def = minimalAgent()
        assertFalse(def.isPresetMode)
    }

    @Test
    fun `AgentDefinition resolveParameters preset mode`() {
        val def = AgentDefinition(
            preset = "lightweight",
            overrides = mapOf(
                "system_prompt" to JsonPrimitive("Override prompt")
            )
        )
        val params = def.resolveParameters()

        assertEquals(JsonPrimitive("Override prompt"), params["system_prompt"])
        assertEquals(JsonPrimitive("single_run"), params["strategy"])
        assertEquals(JsonPrimitive("universal_agent"), params["type"])
    }

    @Test
    fun `AgentDefinition resolveParameters overrides-only mode`() {
        val def = AgentDefinition(
            overrides = mapOf(
                "type" to JsonPrimitive("universal_agent"),
                "strategy" to JsonPrimitive("react"),
                "system_prompt" to JsonPrimitive("Custom")
            )
        )
        val params = def.resolveParameters()

        assertEquals(JsonPrimitive("react"), params["strategy"])
        assertEquals(JsonPrimitive("Custom"), params["system_prompt"])
    }

    @Test
    fun `AgentDefinition resolveParameters legacy mode builds correct params`() {
        val def = AgentDefinition(
            type = "universal_agent",
            strategy = "just_work",
            systemPrompt = "My prompt",
            tools = listOf("exit", "shell"),
            llm = LLMConfiguration(model = "gpt-4", provider = "openai", temperature = 0.5),
            maxIterations = 500,
            cachePolicy = "memory"
        )
        val params = def.resolveParameters()

        assertEquals(JsonPrimitive("universal_agent"), params["type"])
        assertEquals(JsonPrimitive("just_work"), params["strategy"])
        assertEquals(JsonPrimitive("My prompt"), params["system_prompt"])
        assertEquals(JsonPrimitive(500), params["max_iterations"])
        assertEquals(JsonPrimitive("memory"), params["cache_policy"])

        // tool_set
        val toolSet = params["tool_set"] as JsonArray
        assertEquals(2, toolSet.size)

        // llm_config
        val llmConfig = params["llm_config"] as JsonObject
        assertEquals(JsonPrimitive(0.5), llmConfig["temperature"])
    }

    @Test
    fun `AgentDefinition legacy mode maps llmProviderKeys correctly`() {
        val def = AgentDefinition(
            llmProviderKeys = mapOf("openrouter" to "sk-test", "anthropic" to "sk-ant-test")
        )
        val params = def.resolveParameters()

        val llmProviderKeys = params["llm_provider_keys"] as JsonObject
        assertEquals(JsonPrimitive("sk-test"), llmProviderKeys["openrouter"])
        assertEquals(JsonPrimitive("sk-ant-test"), llmProviderKeys["anthropic"])
        assertEquals(JsonPrimitive("sk-test"), params["openrouter_api_key"])
        assertEquals(JsonPrimitive("sk-ant-test"), params["anthropic_api_key"])
    }

    // ==================== Parser Preset Validation Tests ====================

    @Test
    fun `parser validates unknown preset reference`() {
        val def = AgentDefinition(preset = "nonexistent_preset")
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("a1" to def),
            workflow = listOf(minimalStep("s1", "a1"))
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflow)
        }
    }

    @Test
    fun `parser accepts valid preset reference`() {
        val yaml = """
            name: test
            agents:
              a1:
                preset: universal
            workflow:
              - step: s1
                agent: a1
                input: test
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        WorkflowParser.validateWorkflow(workflow) // should not throw
    }

    @Test
    fun `parser summary shows preset info`() {
        val def = AgentDefinition(
            preset = "coder",
            overrides = mapOf("system_prompt" to JsonPrimitive("test"))
        )
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("a1" to def),
            workflow = listOf(minimalStep("s1", "a1"))
        )
        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertTrue(summary.contains("preset: coder"))
        assertTrue(summary.contains("+1 overrides"))
    }

    @Test
    fun `parser summary shows legacy info for non-preset agents`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("a1" to minimalAgent()),
            workflow = listOf(minimalStep("s1", "a1"))
        )
        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertTrue(summary.contains("universal_agent"))
        assertTrue(summary.contains("just_work"))
    }

    // ==================== Helper Methods ====================

    private fun minimalAgent() = AgentDefinition(
        type = "universal_agent",
        strategy = "just_work",
        tools = listOf("exit"),
        llm = LLMConfiguration(model = "gpt-4", provider = "openai")
    )

    private fun minimalStep(name: String, agent: String) = WorkflowStep(
        step = name,
        agent = agent,
        input = "test input"
    )

    /**
     * Mirrors WorkflowExecutor.calculateBackoffDelay logic for testing
     */
    private fun calculateBackoff(retry: RetryConfig, attempt: Int): Long {
        return when (retry.backoff) {
            BackoffStrategy.CONSTANT -> retry.initialDelay
            BackoffStrategy.LINEAR -> minOf(retry.initialDelay * (attempt + 1), retry.maxDelay)
            BackoffStrategy.EXPONENTIAL -> minOf(retry.initialDelay * (1 shl attempt), retry.maxDelay)
        }
    }
}
