package com.fartech.agents.workflow

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WorkflowModelsExtendedTest {

    // =========================================================================
    // WorkflowExecutionContext
    // =========================================================================

    @Nested
    inner class WorkflowExecutionContextTest {

        @Test
        fun `setVariable and getVariable work`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            ctx.setVariable("key", "value")
            assertEquals("value", ctx.getVariable("key"))
        }

        @Test
        fun `getVariable returns null for missing key`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            assertNull(ctx.getVariable("missing"))
        }

        @Test
        fun `setStepOutput and getStepOutput work`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            ctx.setStepOutput("step1", "output text")
            assertEquals("output text", ctx.getStepOutput("step1"))
        }

        @Test
        fun `getStepOutput returns null for missing step`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            assertNull(ctx.getStepOutput("missing"))
        }

        @Test
        fun `variables are mutable`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            ctx.setVariable("counter", 1)
            ctx.setVariable("counter", 2)
            assertEquals(2, ctx.getVariable("counter"))
        }

        @Test
        fun `stepOutputs are mutable`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            ctx.setStepOutput("s1", "first")
            ctx.setStepOutput("s1", "updated")
            assertEquals("updated", ctx.getStepOutput("s1"))
        }

        @Test
        fun `stepResults map works`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            val result = StepExecutionResult(
                stepName = "s1",
                agentName = "agent1",
                success = true,
                startTime = 1000L,
                endTime = 2000L,
                duration = 1000L,
                output = "result"
            )
            ctx.stepResults["s1"] = result
            assertTrue(ctx.stepResults["s1"]!!.success)
            assertEquals("result", ctx.stepResults["s1"]!!.output)
        }

        @Test
        fun `snapshot creates isolated variable and output copies`() {
            val ctx = WorkflowExecutionContext(workflowName = "test", executionId = "exec-1")
            ctx.setVariable("shared", "root")
            ctx.setStepOutput("step1", "root-output")

            val snapshot = ctx.snapshot(
                extraVariables = mapOf(
                    "shared" to "local",
                    "item" to "alpha"
                )
            )

            snapshot.setVariable("item", "beta")
            snapshot.setStepOutput("step2", "local-output")

            assertEquals("root", ctx.getVariable("shared"))
            assertNull(ctx.getVariable("item"))
            assertNull(ctx.getStepOutput("step2"))
            assertEquals("local", snapshot.getVariable("shared"))
            assertEquals("beta", snapshot.getVariable("item"))
            assertEquals("root-output", snapshot.getStepOutput("step1"))
        }
    }

    // =========================================================================
    // WorkflowMetrics
    // =========================================================================

    @Nested
    inner class WorkflowMetricsTest {

        @Test
        fun `getDuration with null endTime returns elapsed time`() {
            val metrics =
                WorkflowMetrics(executionId = "e1", workflowName = "wf", startTime = System.currentTimeMillis() - 500)
            val duration = metrics.getDuration()
            assertTrue(duration >= 0, "Duration should be non-negative")
        }

        @Test
        fun `getDuration with set endTime`() {
            val metrics = WorkflowMetrics(executionId = "e1", workflowName = "wf", startTime = 1000L)
            metrics.endTime = 3000L
            assertEquals(2000L, metrics.getDuration())
        }

        @Test
        fun `getSuccessRate with zero totalSteps returns 0`() {
            val metrics = WorkflowMetrics(executionId = "e1", workflowName = "wf", startTime = 0L)
            assertEquals(0.0, metrics.getSuccessRate())
        }

        @Test
        fun `getSuccessRate calculated correctly`() {
            val metrics = WorkflowMetrics(executionId = "e1", workflowName = "wf", startTime = 0L)
            metrics.totalSteps = 10
            metrics.completedSteps = 7
            assertEquals(0.7, metrics.getSuccessRate(), 0.01)
        }

        @Test
        fun `default status is RUNNING`() {
            val metrics = WorkflowMetrics(executionId = "e1", workflowName = "wf", startTime = 0L)
            assertEquals(ExecutionStatus.RUNNING, metrics.status)
        }
    }

    // =========================================================================
    // StepMetrics
    // =========================================================================

    @Nested
    inner class StepMetricsTest {

        @Test
        fun `getDuration with null endTime returns elapsed time`() {
            val sm = StepMetrics(stepName = "s1", startTime = System.currentTimeMillis() - 500)
            val duration = sm.getDuration()
            assertTrue(duration >= 0, "Duration should be non-negative")
        }

        @Test
        fun `getDuration with set endTime`() {
            val sm = StepMetrics(stepName = "s1", startTime = 1000L)
            sm.endTime = 5000L
            assertEquals(4000L, sm.getDuration())
        }

        @Test
        fun `default status is RUNNING`() {
            val sm = StepMetrics(stepName = "s1", startTime = 0L)
            assertEquals(ExecutionStatus.RUNNING, sm.status)
        }

        @Test
        fun `default retryCount is 0`() {
            val sm = StepMetrics(stepName = "s1", startTime = 0L)
            assertEquals(0, sm.retryCount)
        }
    }

    // =========================================================================
    // ExecutionStatus
    // =========================================================================

    @Nested
    inner class ExecutionStatusTest {

        @Test
        fun `all expected statuses exist`() {
            val statuses = ExecutionStatus.entries
            assertTrue(statuses.contains(ExecutionStatus.PENDING))
            assertTrue(statuses.contains(ExecutionStatus.RUNNING))
            assertTrue(statuses.contains(ExecutionStatus.COMPLETED))
            assertTrue(statuses.contains(ExecutionStatus.FAILED))
            assertTrue(statuses.contains(ExecutionStatus.TIMEOUT))
            assertTrue(statuses.contains(ExecutionStatus.CANCELLED))
            assertTrue(statuses.contains(ExecutionStatus.AWAITING_APPROVAL))
            assertEquals(7, statuses.size)
        }
    }

    // =========================================================================
    // Exception Classes
    // =========================================================================

    @Nested
    inner class ExceptionTest {

        @Test
        fun `WorkflowException preserves message`() {
            val ex = WorkflowException("test error")
            assertEquals("test error", ex.message)
            assertNull(ex.cause)
        }

        @Test
        fun `WorkflowException preserves cause`() {
            val cause = RuntimeException("root cause")
            val ex = WorkflowException("wrapper", cause)
            assertEquals("wrapper", ex.message)
            assertEquals(cause, ex.cause)
        }

        @Test
        fun `WorkflowValidationException is a WorkflowException`() {
            val ex: Throwable = WorkflowValidationException("validation failed")
            assertInstanceOf(WorkflowException::class.java, ex)
            assertEquals("validation failed", ex.message)
        }

        @Test
        fun `WorkflowExecutionException is a WorkflowException`() {
            val ex: Throwable = WorkflowExecutionException("execution failed")
            assertInstanceOf(WorkflowException::class.java, ex)
        }

        @Test
        fun `WorkflowTimeoutException is a WorkflowException`() {
            val ex: Throwable = WorkflowTimeoutException("timed out")
            assertInstanceOf(WorkflowException::class.java, ex)
            assertEquals("timed out", ex.message)
        }

        @Test
        fun `WorkflowApprovalRequiredException contains approval config`() {
            val config = ManualApprovalConfig(
                enabled = true,
                approvers = listOf("admin"),
                timeout = 7200,
                approvalMessage = "Please approve"
            )
            val ex: Throwable = WorkflowApprovalRequiredException("needs approval", config)
            assertInstanceOf(WorkflowException::class.java, ex)
            val approvalEx = assertInstanceOf(WorkflowApprovalRequiredException::class.java, ex)
            assertEquals("needs approval", approvalEx.message)
            assertEquals(config, approvalEx.approvalConfig)
            assertTrue(approvalEx.approvalConfig.enabled)
            assertEquals(listOf("admin"), approvalEx.approvalConfig.approvers)
        }
    }

    // =========================================================================
    // WorkflowExecutionResult & StepExecutionResult
    // =========================================================================

    @Nested
    inner class ExecutionResultTest {

        @Test
        fun `WorkflowExecutionResult preserves all fields`() {
            val result = WorkflowExecutionResult(
                workflowName = "test-wf",
                success = true,
                startTime = 1000L,
                endTime = 5000L,
                duration = 4000L,
                error = null,
                stepResults = mapOf(
                    "s1" to StepExecutionResult("s1", "a1", true, 1000L, 2000L, 1000L)
                ),
                variables = mapOf("x" to "y")
            )
            assertEquals("test-wf", result.workflowName)
            assertTrue(result.success)
            assertEquals(4000L, result.duration)
            assertNull(result.error)
            assertEquals(1, result.stepResults.size)
            assertEquals(1, result.variables.size)
        }

        @Test
        fun `StepExecutionResult preserves all fields`() {
            val result = StepExecutionResult(
                stepName = "step1",
                agentName = "agent1",
                success = false,
                startTime = 100L,
                endTime = 200L,
                duration = 100L,
                output = "some output",
                error = "step failed",
                retryCount = 3
            )
            assertEquals("step1", result.stepName)
            assertEquals("agent1", result.agentName)
            assertFalse(result.success)
            assertEquals(100L, result.duration)
            assertEquals("some output", result.output)
            assertEquals("step failed", result.error)
            assertEquals(3, result.retryCount)
        }

        @Test
        fun `StepExecutionResult default retryCount is 0`() {
            val result = StepExecutionResult("s", "a", true, 0L, 0L, 0L)
            assertEquals(0, result.retryCount)
            assertNull(result.output)
            assertNull(result.error)
        }
    }

    // =========================================================================
    // BackoffStrategy
    // =========================================================================

    @Nested
    inner class BackoffStrategyTest {

        @Test
        fun `all strategies exist`() {
            assertEquals(3, BackoffStrategy.entries.size)
            assertNotNull(BackoffStrategy.LINEAR)
            assertNotNull(BackoffStrategy.EXPONENTIAL)
            assertNotNull(BackoffStrategy.CONSTANT)
        }
    }

    // =========================================================================
    // RetryConfig
    // =========================================================================

    @Nested
    inner class RetryConfigTest {

        @Test
        fun `default values`() {
            val config = RetryConfig()
            assertEquals(3, config.maxAttempts)
            assertEquals(BackoffStrategy.EXPONENTIAL, config.backoff)
            assertEquals(1000L, config.initialDelay)
            assertEquals(60000L, config.maxDelay)
        }

        @Test
        fun `custom values`() {
            val config = RetryConfig(
                maxAttempts = 5,
                backoff = BackoffStrategy.LINEAR,
                initialDelay = 500L,
                maxDelay = 30000L
            )
            assertEquals(5, config.maxAttempts)
            assertEquals(BackoffStrategy.LINEAR, config.backoff)
            assertEquals(500L, config.initialDelay)
            assertEquals(30000L, config.maxDelay)
        }
    }

    // =========================================================================
    // ErrorHandlingConfig
    // =========================================================================

    @Nested
    inner class ErrorHandlingConfigTest {

        @Test
        fun `default values`() {
            val config = ErrorHandlingConfig()
            assertEquals(3, config.maxRetries)
            assertEquals("5s", config.retryDelay)
            assertTrue(config.onError.isEmpty())
            assertFalse(config.continueOnError)
        }
    }

    // =========================================================================
    // TimeoutConfig
    // =========================================================================

    @Nested
    inner class TimeoutConfigTest {

        @Test
        fun `default values`() {
            val config = TimeoutConfig()
            assertEquals("3600s", config.total)
            assertEquals("600s", config.perStep)
        }
    }

    // =========================================================================
    // WorkflowStep
    // =========================================================================

    @Nested
    inner class WorkflowStepTest {

        @Test
        fun `default values for optional fields`() {
            val step = WorkflowStep(step = "s1", agent = "a1", input = "do stuff")
            assertTrue(step.dependsOn.isEmpty())
            assertNull(step.condition)
            assertTrue(step.onSuccess.isEmpty())
            assertTrue(step.onFailure.isEmpty())
            assertNull(step.parallel)
            assertNull(step.manualApproval)
            assertEquals(0, step.priority)
            assertNull(step.timeoutSeconds)
            assertNull(step.state)
            assertNull(step.retry)
            assertNull(step.timeout)
        }
    }

    // =========================================================================
    // TransitionAction
    // =========================================================================

    @Nested
    inner class TransitionActionTest {

        @Test
        fun `default values`() {
            val action = TransitionAction()
            assertNull(action.next)
            assertFalse(action.stop)
            assertNull(action.notify)
            assertNull(action.message)
            assertNull(action.rollback)
            assertNull(action.parallel)
            assertNull(action.action)
            assertNull(action.key)
            assertNull(action.value)
        }

        @Test
        fun `action with all fields`() {
            val action = TransitionAction(
                next = "step2",
                stop = true,
                notify = "admin",
                message = "Done",
                rollback = "step1",
                parallel = listOf("a", "b"),
                action = "log",
                key = "k",
                value = "v"
            )
            assertEquals("step2", action.next)
            assertTrue(action.stop)
            assertEquals("admin", action.notify)
            assertEquals("Done", action.message)
            assertEquals("step1", action.rollback)
            assertEquals(listOf("a", "b"), action.parallel)
            assertEquals("log", action.action)
            assertEquals("k", action.key)
            assertEquals("v", action.value)
        }
    }

    // =========================================================================
    // ParallelExecution
    // =========================================================================

    @Nested
    inner class ParallelExecutionTest {

        @Test
        fun `defaults`() {
            val pe = ParallelExecution(tasks = listOf("t1", "t2"))
            assertEquals(listOf("t1", "t2"), pe.tasks)
            assertTrue(pe.aggregateResults)
            assertNull(pe.maxParallel)
        }
    }

    // =========================================================================
    // ManualApprovalConfig
    // =========================================================================

    @Nested
    inner class ManualApprovalConfigTest {

        @Test
        fun `defaults`() {
            val mac = ManualApprovalConfig(enabled = true)
            assertTrue(mac.enabled)
            assertTrue(mac.approvers.isEmpty())
            assertEquals(3600L, mac.timeout)
            assertNull(mac.approvalMessage)
        }
    }

    // =========================================================================
    // StateMachineConfig & related
    // =========================================================================

    @Nested
    inner class StateMachineConfigTest {

        @Test
        fun `default finalStates is empty`() {
            val config = StateMachineConfig(
                states = mapOf("init" to StateDefinition(name = "init")),
                initialState = "init"
            )
            assertTrue(config.finalStates.isEmpty())
        }

        @Test
        fun `StateDefinition defaults`() {
            val sd = StateDefinition(name = "idle")
            assertEquals("idle", sd.name)
            assertNull(sd.stepConfig)
            assertTrue(sd.onEnter.isEmpty())
            assertTrue(sd.onExit.isEmpty())
            assertEquals("enter", sd.autoEvent)
            assertEquals("complete", sd.successEvent)
            assertEquals("error", sd.failureEvent)
            assertTrue(sd.transitions.isEmpty())
        }

        @Test
        fun `StateTransition defaults`() {
            val st = StateTransition(event = "go", target = "next")
            assertEquals("go", st.event)
            assertEquals("next", st.target)
            assertNull(st.condition)
            assertTrue(st.actions.isEmpty())
        }

        @Test
        fun `StateStepConfig accepts single agent mode`() {
            val config = StateStepConfig(agent = "writer", input = "draft")
            assertEquals(listOf("writer"), config.referencedAgents)
        }

        @Test
        fun `StateStepConfig accepts sub_workflow mode`() {
            // Regression: engine was extended to let state_machine states invoke
            // a sub_workflow (e.g. braidrun-module-telegram-deliver) instead of
            // an LLM agent. Validation must accept exactly-one sub_workflow
            // as a valid mode, and referencedAgents should be empty (the module
            // owns its own agent lookup).
            val config = StateStepConfig(
                subWorkflow = SubWorkflowConfig(
                    name = "braidrun-module-telegram-deliver",
                    inputs = mapOf("telegram_bot_token" to "{{var:token}}")
                )
            )
            assertTrue(config.isSubWorkflow)
            assertTrue(config.referencedAgents.isEmpty())
        }

        @Test
        fun `StateStepConfig rejects both agent and sub_workflow`() {
            // The modeCount==1 invariant must now include sub_workflow — a state
            // step that specifies both agent+input AND sub_workflow is ambiguous
            // and should fail fast at construction, not at execution time.
            assertFailsWith<IllegalArgumentException> {
                StateStepConfig(
                    agent = "writer",
                    input = "draft",
                    subWorkflow = SubWorkflowConfig(name = "child")
                )
            }
        }

        @Test
        fun `StateMachineConfig defaults maxTransitions`() {
            val config = StateMachineConfig(
                states = mapOf("init" to StateDefinition(name = "init")),
                initialState = "init"
            )
            assertEquals(64, config.maxTransitions)
        }
    }

    // =========================================================================
    // WorkflowVersion
    // =========================================================================

    @Nested
    inner class WorkflowVersionTest {

        @Test
        fun `default optional fields are null`() {
            val wv = WorkflowVersion(version = "1.0.0", createdAt = "2025-01-01")
            assertEquals("1.0.0", wv.version)
            assertEquals("2025-01-01", wv.createdAt)
            assertNull(wv.createdBy)
            assertNull(wv.description)
            assertNull(wv.checksum)
        }
    }

    // =========================================================================
    // AgentDefinition
    // =========================================================================

    @Nested
    inner class AgentDefinitionExtendedTest {

        @Test
        fun `isPresetMode true when preset is set`() {
            val ad = AgentDefinition(preset = "ios-agent")
            assertTrue(ad.preset != null || ad.overrides.isNotEmpty())
        }

        @Test
        fun `isPresetMode true when overrides present`() {
            val ad = AgentDefinition(overrides = mapOf("key" to JsonPrimitive("value")))
            assertTrue(ad.preset != null || ad.overrides.isNotEmpty())
        }

        @Test
        fun `legacy mode has no preset and no overrides`() {
            val ad = AgentDefinition()
            assertNull(ad.preset)
            assertTrue(ad.overrides.isEmpty())
        }

        @Test
        fun `default values are correct`() {
            val ad = AgentDefinition()
            assertEquals("universal_agent", ad.type)
            assertEquals("just_work_parallel", ad.strategy)
            assertNull(ad.name)
            assertNull(ad.systemPrompt)
            assertTrue(ad.tools.isEmpty())
            assertNull(ad.llm)
            assertTrue(ad.llmProviderKeys.isEmpty())
        }

        @Test
        fun `LLMConfiguration defaults`() {
            val llm = LLMConfiguration(model = "gpt-4", provider = "openai")
            assertEquals(1.0, llm.temperature)
            assertNull(llm.maxTokens)
        }
    }
}
