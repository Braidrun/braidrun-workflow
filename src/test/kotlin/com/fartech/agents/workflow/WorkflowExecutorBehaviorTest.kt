package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowExecutorBehaviorTest {

    @Test
    fun `extractJsonPath prefers business json over tool metadata for root path`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val context = WorkflowExecutionContext(
            workflowName = "test",
            executionId = "exec-json-root"
        )
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "extractJsonPath",
            String::class.java,
            String::class.java,
            String::class.java,
            WorkflowExecutionContext::class.java
        )
        method.isAccessible = true

        val output = """
            {"tool_call_id":"call_1","tool_name":"__exit__","tool_args":{}}
            ```json
            {"sheets":[{"sheet_name":"Google日报","summary_commentary":"深度分析"}]}
            ```
            done
        """.trimIndent()

        method.invoke(executor, "$", "ai_commentary_json", output, context)

        val extracted = context.variables["ai_commentary_json"] as String
        val root = Json.parseToJsonElement(extracted).jsonObject
        assertTrue("sheets" in root)
        assertFalse("tool_call_id" in root)
    }

    @Test
    fun `extractJsonPath reads nested value from noisy output with trailing text`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val context = WorkflowExecutionContext(
            workflowName = "test",
            executionId = "exec-json-nested"
        )
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "extractJsonPath",
            String::class.java,
            String::class.java,
            String::class.java,
            WorkflowExecutionContext::class.java
        )
        method.isAccessible = true

        val output = """prefix {"result":{"status":"ok","score":95}} trailing {"tool_call_id":"call_2"}"""

        method.invoke(executor, "$.result.status", "status", output, context)

        assertEquals("ok", context.variables["status"])
    }

    @Test
    fun `extractJsonPath unwraps pseudo writeFile content`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val context = WorkflowExecutionContext(
            workflowName = "test",
            executionId = "exec-json-tool-wrapper"
        )
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "extractJsonPath",
            String::class.java,
            String::class.java,
            String::class.java,
            WorkflowExecutionContext::class.java
        )
        method.isAccessible = true

        val output = """
            ```json
            {
              "tool": "writeFile",
              "arguments": {
                "path": "/tmp/ai_commentary.json",
                "content": "{\"sheets\":[{\"sheet_name\":\"Google日报\",\"summary_commentary\":\"有效解读\"}]}"
              }
            }
            ```
        """.trimIndent()

        method.invoke(executor, "$", "ai_commentary_json", output, context)

        val extracted = context.variables["ai_commentary_json"] as String
        val root = Json.parseToJsonElement(extracted).jsonObject
        assertTrue("sheets" in root)
        assertFalse("tool" in root)
    }

    @Test
    fun `extractJsonPath recovers json from exit tool detail text`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val context = WorkflowExecutionContext(
            workflowName = "test",
            executionId = "exec-json-exit-detail"
        )
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "extractJsonPath",
            String::class.java,
            String::class.java,
            String::class.java,
            WorkflowExecutionContext::class.java
        )
        method.isAccessible = true

        val output = """
            任务已完成。
            {"message":"{
              "sheets":[{"sheet_name":"Google日报","summary_commentary":"有效解读"}]
            }"}
        """.trimIndent()

        method.invoke(executor, "$", "ai_commentary_json", output, context)

        val extracted = context.variables["ai_commentary_json"] as String
        val root = Json.parseToJsonElement(extracted).jsonObject
        assertTrue("sheets" in root)
        assertFalse("message" in root)
    }

    @Test
    fun `mergeParameters lets workflow agent overrides replace base parameters`() {
        val baseParameters = listOf(
            ConfigurationParameter("strategy", JsonPrimitive("tone")),
            ConfigurationParameter("system_prompt", JsonPrimitive("base prompt")),
            ConfigurationParameter("tool_set", JsonArray(listOf(JsonPrimitive("shell"))))
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = baseParameters,
            enableMonitoring = false
        )

        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "mergeParameters",
            List::class.java,
            Map::class.java
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val merged = method.invoke(
            executor,
            baseParameters,
            linkedMapOf(
                "strategy" to JsonPrimitive("react"),
                "system_prompt" to JsonPrimitive("workflow prompt"),
                "tool_set" to JsonArray(listOf(JsonPrimitive("file_system")))
            )
        ) as MutableList<ConfigurationParameter>

        val mergedByKey = merged.associateBy { it.key }
        assertEquals("react", (mergedByKey.getValue("strategy").value as JsonPrimitive).content)
        assertEquals("workflow prompt", (mergedByKey.getValue("system_prompt").value as JsonPrimitive).content)
        assertEquals(
            listOf("file_system"),
            (mergedByKey.getValue("tool_set").value as JsonArray).map { it as JsonPrimitive }.map { it.content }
        )
    }

    @Test
    fun `resolveTemplate rewrites shared output literals to absolute execution output dir`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val context = WorkflowExecutionContext(
            workflowName = "test",
            executionId = "exec-1",
            variables = mutableMapOf(
                "output_dir" to "/tmp/shared-output"
            )
        )
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "resolveTemplate",
            String::class.java,
            WorkflowExecutionContext::class.java
        )
        method.isAccessible = true

        val resolved = method.invoke(
            executor,
            """
                Save the draft to ./output/draft_article.txt
                Review ./output/draft_article.txt and write feedback to ./output/review_feedback.txt
            """.trimIndent(),
            context
        ) as String

        assertEquals(
            """
                Save the draft to /tmp/shared-output/draft_article.txt
                Review /tmp/shared-output/draft_article.txt and write feedback to /tmp/shared-output/review_feedback.txt
            """.trimIndent(),
            resolved
        )
    }

    @Test
    fun `manual approval waits for approveStep and then resumes workflow`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "manual-approval",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "deploy",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo approved"
                    ),
                    manualApproval = ManualApprovalConfig(
                        enabled = true,
                        timeout = 5,
                        approvalMessage = "approve deployment"
                    )
                )
            )
        )

        val resultDeferred = async { executor.execute(workflow) }

        var pollsRemaining = 20
        while (executor.getPendingApprovals().isEmpty() && pollsRemaining-- > 0) {
            delay(50)
        }

        val pendingApproval = executor.getPendingApprovals().singleOrNull()
        assertNotNull(pendingApproval)
        assertTrue(executor.approveStep(pendingApproval.approvalId))

        val result = resultDeferred.await()
        assertTrue(result.success)
        assertEquals("approved", result.stepResults.getValue("deploy").output?.trim())
    }

    @Test
    fun `manual approval resolves runtime variables before sending the request`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "localized-manual-approval",
            variables = mapOf(
                "approval_message" to "Review the localized optimization plan"
            ),
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "approve",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo approved"
                    ),
                    manualApproval = ManualApprovalConfig(
                        enabled = true,
                        timeout = 5,
                        approvalMessage = "{{var:approval_message}}"
                    )
                )
            )
        )

        val resultDeferred = async { executor.execute(workflow) }

        var pollsRemaining = 20
        while (executor.getPendingApprovals().isEmpty() && pollsRemaining-- > 0) {
            delay(50)
        }

        val pendingApproval = executor.getPendingApprovals().singleOrNull()
        assertNotNull(pendingApproval)
        assertEquals("Review the localized optimization plan", pendingApproval.message)
        assertTrue(executor.approveStep(pendingApproval.approvalId))
        assertTrue(resultDeferred.await().success)
    }

    /**
     * Phase 10 (2026-05-08) audit fix: rejection now mirrors the variables that the
     * approved branch sets via [WorkflowExecutor.applyApprovedReviewableActions], so
     * downstream handlers that catch [WorkflowApprovalRequiredException] (or sub_workflow
     * callers reading `context.variables` after a rejection) see a consistent state.
     *
     * Pre-fix the rejection branch threw without touching any variable, which broke
     * conditions like `if: "{{var:approval_decision}} == 'rejected'"`.
     */
    @Test
    fun `rejecting manual approval populates approval_decision variables`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = true
        )
        val executionId = "manual-approval-reject-monitor-${System.nanoTime()}"
        WorkflowMonitor.resetExecution(executionId)
        val workflow = WorkflowDefinition(
            name = "manual-approval-reject",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "deploy",
                    code = CodeStepConfig(
                        language = "bash",
                        script = "echo never-runs"
                    ),
                    manualApproval = ManualApprovalConfig(
                        enabled = true,
                        timeout = 5,
                        approvalMessage = "approve deployment"
                    )
                )
            )
        )

        val resultDeferred = async { executor.execute(workflow, externalExecutionId = executionId) }

        var pollsRemaining = 20
        while (executor.getPendingApprovals().isEmpty() && pollsRemaining-- > 0) {
            delay(50)
        }
        val pendingApproval = executor.getPendingApprovals().singleOrNull()
        assertNotNull(pendingApproval)

        assertTrue(executor.rejectStep(pendingApproval.approvalId))

        val result = resultDeferred.await()
        assertFalse(result.success, "Workflow must report failure on rejection")
        assertNotNull(result.error, "Rejected workflow must surface an error")
        assertEquals(
            ExecutionStatus.FAILED,
            WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("deploy")?.status,
            "Rejected manual approval should complete the monitored step as FAILED instead of leaving it awaiting approval"
        )
        assertEquals(
            "rejected",
            result.stepResults["deploy"]?.producedVariables?.get("approval_decision"),
            "Rejected manual approval should persist approval variables for resume/recovery"
        )

        // The Phase 10 fix: both global and step-scoped approval variables are populated.
        assertEquals(
            "rejected",
            result.variables["approval_decision"],
            "approval_decision must be 'rejected' after rejection"
        )
        assertEquals(
            "rejected",
            result.variables["deploy_approval_decision"],
            "step-scoped approval_decision must mirror the global variable"
        )
        assertEquals(
            "0",
            result.variables["approval_approved_count"],
            "approved count must be 0 on rejection"
        )
        assertEquals(
            "0",
            result.variables["deploy_approved_count"],
            "step-scoped approved count must be 0 on rejection"
        )
        // Comment is empty when the rejection carried none, but the key MUST exist.
        assertNotNull(result.variables["approval_comment"])
        assertNotNull(result.variables["deploy_approval_comment"])
    }

    @Test
    fun `code step honors configured interpreter override from base parameters`() = runBlocking {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            println("[SKIP] Interpreter override test skipped on Windows")
            return@runBlocking
        }

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter(
                    WorkflowRuntimeParameterKeys.codeInterpreter("python"),
                    JsonPrimitive("/bin/sh -e")
                )
            ),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "interpreter-override",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "run-script",
                    code = CodeStepConfig(
                        language = "python",
                        script = "echo interpreter override works"
                    )
                )
            )
        )

        val result = executor.execute(workflow)

        assertTrue(result.success)
        assertEquals("interpreter override works", result.stepResults.getValue("run-script").output?.trim())
    }

    @Test
    fun `legacy code step environment removes inherited sensitive variables`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val processBuilder = ProcessBuilder("env")
        processBuilder.environment()["BRAIDRUN_LEAK_TOKEN"] = "should-not-leak"

        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "applyLegacyCodeStepEnvironment",
            ProcessBuilder::class.java,
            Map::class.java
        )
        method.isAccessible = true
        method.invoke(executor, processBuilder, mapOf("WF_VAR_ALLOWED" to "ok"))

        val env = processBuilder.environment()
        assertFalse(env.containsKey("BRAIDRUN_LEAK_TOKEN"))
        assertEquals("ok", env["WF_VAR_ALLOWED"])
    }

    @Test
    fun `iterate_over step is visible in monitor before all iterations finish`() = runBlocking {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            println("[SKIP] iterate_over monitoring test skipped on Windows")
            return@runBlocking
        }

        WorkflowMonitor.clear()
        try {
            val executor = WorkflowExecutor(
                httpAccess = HttpAccess(),
                baseParameters = emptyList(),
                enableMonitoring = true
            )
            val workflow = WorkflowDefinition(
                name = "iterate-over-monitoring",
                agents = emptyMap(),
                workflow = listOf(
                    WorkflowStep(
                        step = "fanout",
                        code = CodeStepConfig(
                            language = "bash",
                            script = "sleep 0.4\necho {{var:current_step}}"
                        ),
                        iterateOver = IterateOverConfig(
                            source = "alpha,beta,gamma",
                            delimiter = ",",
                            itemVariable = "current_step"
                        )
                    )
                )
            )

            val executionId = "iterate-over-monitoring"
            val deferred = async {
                executor.execute(workflow, externalExecutionId = executionId)
            }

            var observedStepMetrics: StepMetrics? = null
            for (attempt in 1..10) {
                val metrics = WorkflowMonitor.getMetrics(executionId)
                val stepMetrics = metrics?.stepMetrics?.get("fanout")
                if (stepMetrics != null && stepMetrics.events.any { it.type == "iteration_started" }) {
                    observedStepMetrics = stepMetrics
                    break
                }
                delay(50)
            }

            assertNotNull(
                observedStepMetrics,
                "iterate_over step should be visible in monitor while iterations are still running"
            )
            assertFalse(deferred.isCompleted)
            assertEquals(ExecutionStatus.RUNNING, observedStepMetrics.status)

            val result = deferred.await()
            assertTrue(result.success)
        } finally {
            WorkflowMonitor.clear()
        }
    }

    @Test
    fun `retry step keeps a single monitor record and preserves retry count`() = runBlocking {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            println("[SKIP] retry monitoring test skipped on Windows")
            return@runBlocking
        }

        WorkflowMonitor.clear()
        val tempDir = Files.createTempDirectory("workflow-retry-monitoring")
        try {
            val markerFile = tempDir.resolve("first-attempt.marker").toAbsolutePath()
            val executor = WorkflowExecutor(
                httpAccess = HttpAccess(),
                baseParameters = emptyList(),
                enableMonitoring = true
            )
            val workflow = WorkflowDefinition(
                name = "retry-monitoring",
                agents = emptyMap(),
                workflow = listOf(
                    WorkflowStep(
                        step = "flaky_step",
                        code = CodeStepConfig(
                            language = "bash",
                            script = """
                                if [ ! -f "$markerFile" ]; then
                                  touch "$markerFile"
                                  echo first failure >&2
                                  exit 1
                                fi
                                sleep 0.4
                                echo recovered
                            """.trimIndent()
                        ),
                        retry = RetryConfig(
                            maxAttempts = 2,
                            initialDelay = 100,
                            maxDelay = 100,
                            backoff = BackoffStrategy.CONSTANT
                        )
                    )
                )
            )

            val executionId = "retry-monitoring"
            val deferred = async {
                executor.execute(workflow, externalExecutionId = executionId)
            }

            var observedStepMetrics: StepMetrics? = null
            for (attempt in 1..20) {
                val stepMetrics = WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("flaky_step")
                if (stepMetrics != null && stepMetrics.retryCount == 1) {
                    observedStepMetrics = stepMetrics
                    break
                }
                delay(50)
            }

            assertNotNull(
                observedStepMetrics,
                "retry step should stay visible in monitor while a later attempt is running"
            )
            assertFalse(deferred.isCompleted)
            assertEquals(ExecutionStatus.RUNNING, observedStepMetrics.status)

            val result = deferred.await()
            assertTrue(result.success)

            val metrics = WorkflowMonitor.getMetrics(executionId)!!
            assertEquals(1, metrics.completedSteps)
            assertEquals(0, metrics.failedSteps)
            assertEquals(1, metrics.stepMetrics.getValue("flaky_step").retryCount)
        } finally {
            tempDir.toFile().deleteRecursively()
            WorkflowMonitor.clear()
        }
    }

    @Test
    fun `repeat_until step keeps one monitor record across iterations`() = runBlocking {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            println("[SKIP] repeat_until monitoring test skipped on Windows")
            return@runBlocking
        }

        WorkflowMonitor.clear()
        val tempDir = Files.createTempDirectory("workflow-repeat-monitoring")
        try {
            val counterFile = tempDir.resolve("score.txt").toAbsolutePath()
            val executor = WorkflowExecutor(
                httpAccess = HttpAccess(),
                baseParameters = emptyList(),
                enableMonitoring = true
            )
            val workflow = WorkflowDefinition(
                name = "repeat-until-monitoring",
                agents = emptyMap(),
                workflow = listOf(
                    WorkflowStep(
                        step = "improve_step",
                        code = CodeStepConfig(
                            language = "bash",
                            script = """
                                score=0
                                if [ -f "$counterFile" ]; then
                                  score=$(cat "$counterFile")
                                fi
                                score=$((score + 1))
                                echo "${'$'}score" > "$counterFile"
                                sleep 0.2
                                echo score=${'$'}score
                            """.trimIndent()
                        ),
                        extract = listOf(
                            ExtractConfig(
                                pattern = "score=(\\d+)",
                                variable = "score"
                            )
                        ),
                        repeatUntil = RepeatUntilConfig(
                            condition = "score == 3",
                            maxIterations = 5
                        )
                    )
                )
            )

            val executionId = "repeat-until-monitoring"
            val deferred = async {
                executor.execute(workflow, externalExecutionId = executionId)
            }

            var observedStepMetrics: StepMetrics? = null
            for (attempt in 1..30) {
                val stepMetrics = WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("improve_step")
                if (stepMetrics != null && stepMetrics.events.count { it.type == "repeat_until_iteration" } >= 2) {
                    observedStepMetrics = stepMetrics
                    break
                }
                delay(50)
            }

            assertNotNull(
                observedStepMetrics,
                "repeat_until step should remain visible while later iterations are still running"
            )
            assertFalse(deferred.isCompleted)
            assertEquals(ExecutionStatus.RUNNING, observedStepMetrics.status)

            val result = deferred.await()
            assertTrue(result.success)

            val metrics = WorkflowMonitor.getMetrics(executionId)!!
            val stepMetrics = metrics.stepMetrics.getValue("improve_step")
            assertEquals(1, metrics.completedSteps)
            assertEquals(0, metrics.failedSteps)
            assertEquals(3, stepMetrics.events.count { it.type == "repeat_until_iteration" })
            assertTrue(stepMetrics.events.any { it.type == "repeat_until_started" })
        } finally {
            tempDir.toFile().deleteRecursively()
            WorkflowMonitor.clear()
        }
    }

    @Test
    fun `onSuccess transition only executes activated branch and allows downstream join`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "success-transition-branching",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "decide",
                    code = CodeStepConfig(language = "bash", script = "echo ok"),
                    onSuccess = listOf(TransitionAction(next = "success_handler")),
                    onFailure = listOf(TransitionAction(next = "error_handler"))
                ),
                WorkflowStep(
                    step = "success_handler",
                    code = CodeStepConfig(language = "bash", script = "echo success")
                ),
                WorkflowStep(
                    step = "error_handler",
                    code = CodeStepConfig(language = "bash", script = "echo error")
                ),
                WorkflowStep(
                    step = "cleanup",
                    code = CodeStepConfig(language = "bash", script = "echo cleanup"),
                    dependsOn = listOf("success_handler", "error_handler")
                )
            )
        )

        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertEquals("ok", result.stepResults.getValue("decide").output?.trim())
        assertEquals("success", result.stepResults.getValue("success_handler").output?.trim())
        assertFalse(result.stepResults.containsKey("error_handler"))
        assertEquals("cleanup", result.stepResults.getValue("cleanup").output?.trim())
    }

    @Test
    fun `onFailure transition can recover workflow without continueOnError`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "failure-transition-recovery",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "risky",
                    code = CodeStepConfig(language = "bash", script = "echo boom >&2 && exit 7"),
                    onFailure = listOf(TransitionAction(next = "recover"))
                ),
                WorkflowStep(
                    step = "recover",
                    code = CodeStepConfig(language = "bash", script = "echo recovered"),
                    dependsOn = listOf("risky")
                ),
                WorkflowStep(
                    step = "finalize",
                    code = CodeStepConfig(language = "bash", script = "echo finalized"),
                    dependsOn = listOf("recover")
                )
            )
        )

        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertEquals(setOf("risky"), result.recoveredFailures)
        assertFalse(result.stepResults.getValue("risky").success)
        assertEquals("recovered", result.stepResults.getValue("recover").output?.trim())
        assertEquals("finalized", result.stepResults.getValue("finalize").output?.trim())
    }

    @Test
    fun `workflow on_error transition can recover workflow without continueOnError`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "workflow-on-error-recovery",
            agents = emptyMap(),
            errorHandling = ErrorHandlingConfig(
                onError = listOf(TransitionAction(next = "recover"))
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "risky",
                    code = CodeStepConfig(language = "bash", script = "echo boom >&2 && exit 7")
                ),
                WorkflowStep(
                    step = "recover",
                    code = CodeStepConfig(language = "bash", script = "echo recovered-by-workflow"),
                    dependsOn = listOf("risky")
                ),
                WorkflowStep(
                    step = "finalize",
                    code = CodeStepConfig(language = "bash", script = "echo finalized"),
                    dependsOn = listOf("recover")
                )
            )
        )

        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertFalse(result.stepResults.getValue("risky").success)
        assertEquals("recovered-by-workflow", result.stepResults.getValue("recover").output?.trim())
        assertEquals("finalized", result.stepResults.getValue("finalize").output?.trim())
    }

    @Test
    fun `workflow on_error targets stay inactive on success path`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "workflow-on-error-inactive",
            agents = emptyMap(),
            errorHandling = ErrorHandlingConfig(
                onError = listOf(TransitionAction(next = "error_handler"))
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "safe",
                    code = CodeStepConfig(language = "bash", script = "echo ok")
                ),
                WorkflowStep(
                    step = "error_handler",
                    code = CodeStepConfig(language = "bash", script = "echo should-not-run")
                ),
                WorkflowStep(
                    step = "finalize",
                    code = CodeStepConfig(language = "bash", script = "echo done"),
                    dependsOn = listOf("safe")
                )
            )
        )

        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertEquals("ok", result.stepResults.getValue("safe").output?.trim())
        assertFalse(result.stepResults.containsKey("error_handler"))
        assertEquals("done", result.stepResults.getValue("finalize").output?.trim())
    }

    @Test
    fun `continueOnError without recovery still marks workflow as failed`() = runBlocking {
        // Regression for the bug shown in execution-process-0bc1a785: a workflow with
        // continueOnError=true and several uncaught step failures was reporting result.success=true
        // (and the web UI showed "已完成 / 100% / 4/4" on top of red failed steps). With the fix,
        // continueOnError lets execution proceed past failures but the overall result must be FAILED
        // unless the failure was officially recovered via on_failure / errorHandling.onError.
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "continue-on-error-without-recovery",
            agents = emptyMap(),
            errorHandling = ErrorHandlingConfig(continueOnError = true),
            workflow = listOf(
                WorkflowStep(
                    step = "first_failure",
                    code = CodeStepConfig(language = "bash", script = "echo boom1 >&2 && exit 1")
                ),
                WorkflowStep(
                    step = "ok_after_failure",
                    code = CodeStepConfig(language = "bash", script = "echo ok")
                ),
                WorkflowStep(
                    step = "second_failure",
                    code = CodeStepConfig(language = "bash", script = "echo boom2 >&2 && exit 2")
                )
            )
        )

        val result = executor.execute(workflow)

        // continueOnError lets execution finish all 3 steps:
        assertEquals(3, result.stepResults.size)
        assertFalse(result.stepResults.getValue("first_failure").success)
        assertTrue(result.stepResults.getValue("ok_after_failure").success)
        assertFalse(result.stepResults.getValue("second_failure").success)
        // …but the overall result must be marked as failed because the failures were never recovered.
        assertFalse(result.success, "continueOnError without recovery must NOT report success=true")
        val errorMessage = result.error
        assertNotNull(errorMessage)
        assertTrue(
            errorMessage.contains("2 step") || errorMessage.contains("steps failed"),
            "error message should mention multiple failures, was: $errorMessage"
        )
    }

    @Test
    fun `workflow total timeout fires when active execution exceeds budget`() = runBlocking {
        // Workflow.total is enforced at step boundaries (excluding manual-approval
        // wait) — a chain of two slow steps whose combined runtime exceeds total
        // should be killed before the second one starts.
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "workflow-total-timeout-active",
            agents = emptyMap(),
            timeout = TimeoutConfig(total = "1s", perStep = "30s"),
            workflow = listOf(
                WorkflowStep(
                    step = "first_slow",
                    code = CodeStepConfig(language = "bash", script = "sleep 2 && echo first")
                ),
                WorkflowStep(
                    step = "second_slow",
                    code = CodeStepConfig(language = "bash", script = "echo second"),
                    dependsOn = listOf("first_slow")
                )
            )
        )

        val result = executor.execute(workflow)

        assertFalse(result.success)
        // Either the first step's tail or the boundary check before step 2 fires;
        // both produce a "timed out" error message but the second is what proves
        // the new step-boundary enforcement is wired up.
        assertTrue(
            result.error?.contains("timed out") == true,
            "expected timeout error, was: ${result.error}"
        )
        assertFalse(
            result.stepResults.containsKey("second_slow"),
            "second_slow should never have started after the deadline expired"
        )
    }

    @Test
    fun `manual approval wait does not count against workflow total timeout`() = runBlocking {
        // Regression for execution-process-dd0198c2: a workflow with total=2h and
        // a manual_approval step would die at 2h even though the user had 19h
        // left on the approval card. Manual approval wait is a "waiting on a
        // human" duration, not active execution, so it must be excluded from the
        // workflow.total budget. This test makes that explicit: total is shorter
        // than the approval timeout, but approving the step still resumes the
        // workflow successfully.
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "approval-immune-from-total",
            agents = emptyMap(),
            timeout = TimeoutConfig(total = "2s", perStep = "30s"),
            workflow = listOf(
                WorkflowStep(
                    step = "deploy",
                    code = CodeStepConfig(language = "bash", script = "echo approved"),
                    manualApproval = ManualApprovalConfig(
                        enabled = true,
                        timeout = 30,
                        approvalMessage = "wait past the 2s workflow.total"
                    )
                )
            )
        )

        val resultDeferred = async { executor.execute(workflow) }

        // Sleep past the workflow.total budget. Pre-fix this would already have
        // cancelled the approval wait; post-fix the approval is still pending
        // and the workflow is still running.
        delay(3_000)
        val pending = executor.getPendingApprovals().singleOrNull()
        assertNotNull(pending, "approval should still be pending past the 2s workflow.total")

        assertTrue(executor.approveStep(pending.approvalId))

        val result = resultDeferred.await()
        assertTrue(result.success, "approved workflow should succeed; got error=${result.error}")
        assertEquals("approved", result.stepResults.getValue("deploy").output?.trim())
    }

    @Test
    fun `manual approval timeout still bounds an indefinitely waiting approval`() = runBlocking {
        // Counterpart to the previous test: when nobody approves, the approval
        // step still terminates (via approvalConfig.timeout) and the workflow
        // ends with a clear "Manual approval timed out" error rather than
        // hanging forever. Workflow.total is intentionally short to prove the
        // failure does NOT come from the workflow-level budget.
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "approval-times-out-on-its-own",
            agents = emptyMap(),
            timeout = TimeoutConfig(total = "1s", perStep = "30s"),
            workflow = listOf(
                WorkflowStep(
                    step = "approval",
                    code = CodeStepConfig(language = "bash", script = "echo unreachable"),
                    manualApproval = ManualApprovalConfig(
                        enabled = true,
                        timeout = 2,
                        approvalMessage = "no one will approve this"
                    )
                )
            )
        )

        val result = executor.execute(workflow)

        assertFalse(result.success)
        val errorMessage = result.error.orEmpty()
        assertTrue(
            errorMessage.contains("Manual approval timed out"),
            "expected approval-side timeout, was: $errorMessage"
        )
    }

    @Test
    fun `workflow per_step timeout is applied to steps without explicit timeout`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "workflow-default-step-timeout",
            agents = emptyMap(),
            timeout = TimeoutConfig(total = "10s", perStep = "1s"),
            workflow = listOf(
                WorkflowStep(
                    step = "slow",
                    code = CodeStepConfig(language = "bash", script = "sleep 2 && echo too-late")
                )
            )
        )

        val result = executor.execute(workflow)

        assertFalse(result.success)
        val workflowError = result.error?.lowercase()
        val stepError = result.stepResults["slow"]?.error?.lowercase()
        assertTrue(workflowError?.contains("timed out") == true || stepError?.contains("timed out") == true)
    }

    @Test
    fun `extractPersistedFilePathsFromText detects Chinese and English save targets`() {
        val text = """
            请将总结保存到 /tmp/demo-report.md。
            The final summary has been saved to `/tmp/output/discussion_summary.txt`.
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text)

        assertEquals(
            setOf("/tmp/demo-report.md", "/tmp/output/discussion_summary.txt"),
            paths
        )
    }

    @Test
    fun `extractPersistedFilePathsFromText supports colon delimited save targets`() {
        val text = """
            已输出到：/tmp/demo-report.md。
            The final summary has been saved to: `/tmp/output/discussion_summary.txt`.
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text)

        assertEquals(
            setOf("/tmp/demo-report.md", "/tmp/output/discussion_summary.txt"),
            paths
        )
    }

    @Test
    fun `extractPersistedFilePathsFromText resolves relative targets against working directory`() {
        val text = """
            请将总结保存到 output/discussion_summary.txt。
            Then save the PRD to `registration-flow-demo-prd.md`.
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text, "/tmp/workspace")

        assertEquals(
            setOf(
                "/tmp/workspace/output/discussion_summary.txt",
                "/tmp/workspace/registration-flow-demo-prd.md"
            ),
            paths
        )
    }

    @Test
    fun `extractPersistedFilePathsFromText keeps paths with spaces`() {
        val text = """
            请将总结保存到 /home/user/Library/Application Support/braidrun/server-data/output/report.txt。
            Then save the draft to /tmp/My Documents/draft article.txt successfully.
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text)

        assertEquals(
            setOf(
                "/home/user/Library/Application Support/braidrun/server-data/output/report.txt",
                "/tmp/My Documents/draft article.txt"
            ),
            paths
        )
    }

    @Test
    fun `extractPersistedFilePathsFromText ignores trailing completion text after path`() {
        val text = """
            任务完成，已保存到 /tmp/workspace/requirements.txt。任务完成
            The file was saved to /tmp/output/draft_article.txt done
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text)

        assertEquals(
            setOf(
                "/tmp/workspace/requirements.txt",
                "/tmp/output/draft_article.txt"
            ),
            paths
        )
    }

    // ── Placeholder-text rejection (added 2026-04-26) ────────────────────
    // Regression for execution-process-19eed731-...: the pwa-app-builder
    // template's prompt contained `写到 \`<OUTPUT_DIR>/<相对>\`` as a
    // teaching example. After workflow template variables were resolved,
    // the matcher captured `/.../output/<相对` (trailing `>` was trimmed
    // but the leading `<` remained) as an "expected file" and failed the
    // step because no such file existed.

    @Test
    fun `extractPersistedFilePathsFromText rejects angle-bracket placeholders in resolved prompt text`() {
        val text = """
            所有产物用 __write_file__ 以绝对路径写到 `/abs/output/<相对>`。
            把日报写到 /abs/output/<filename>.md
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text)

        // None of the placeholder examples should be treated as real paths.
        assertEquals(emptySet<String>(), paths,
            "placeholder paths containing < or > must be rejected; got $paths")
    }

    @Test
    fun `extractPersistedFilePathsFromText still captures real paths alongside placeholders`() {
        val text = """
            示例: 写到 `/tmp/output/<相对>` (这是占位符,不是真路径)
            实际请把报告写到 `/tmp/real-report.md`
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text)

        assertEquals(
            setOf("/tmp/real-report.md"),
            paths,
            "real paths must still be detected when placeholders coexist; got $paths"
        )
    }

    @Test
    fun `extractPersistedFilePathsFromText rejects unresolved bash-style template variables`() {
        val dollar = "$"
        val text = """
            写到 ${dollar}{OUTPUT_DIR}/index.html
            save to ${dollar}{WORKDIR}/report.txt
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text)

        // Unresolved shell-style templates are never real paths; the engine
        // would have resolved workflow-level templates before this call.
        assertEquals(emptySet<String>(), paths,
            "unresolved bash-style ${dollar}{...} templates must be rejected; got $paths")
    }

    @Test
    fun `extractRequiredPersistedFilePathsFromPrompt rejects placeholder paths in prompts`() {
        // The prompt-side analogue of the above. Same rejection rules apply
        // because both paths funnel through normalizePersistedFilePath.
        val prompt = """
            写到 `/abs/output/<相对>` 然后退出。
            生成 `<OUTPUT_DIR>/index.html` 文件。
        """.trimIndent()

        val candidates = extractRequiredPersistedFilePathsFromPrompt(prompt)

        assertEquals(emptySet<String>(), candidates,
            "prompt-time validator must also reject placeholder paths; got $candidates")
    }

    @Test
    fun `extractPersistedFilePathsFromText ignores directory targets`() {
        val text = """
            请将图表保存到 ./reports/ 目录
            Save artifacts to /tmp/output directory
        """.trimIndent()

        val paths = extractPersistedFilePathsFromText(text, "/tmp/workspace")

        assertTrue(paths.isEmpty())
    }

    @Test
    fun `extractRequiredPersistedFilePathsFromPrompt supports colon delimited save targets`() {
        val text = """
            请输出到：aso-competitor-audit.md
            Then save to: `output/final_report.txt`.
        """.trimIndent()

        val paths = extractRequiredPersistedFilePathsFromPrompt(text, "/tmp/workspace")

        assertEquals(
            setOf(
                "/tmp/workspace/aso-competitor-audit.md",
                "/tmp/workspace/output/final_report.txt"
            ),
            paths
        )
    }

    @Test
    fun `extractRequiredPersistedFilePathsFromPrompt matches aso optimization workflow prompt`() {
        val text = """
            分析 {{app_id}} 在 {{target_region}} 市场的 ASO 现状及 Top 3 竞品。

            1. 获取竞品的关键词覆盖情况（列出竞品名称、覆盖关键词数、重叠度）。
            2. 分析竞品的标题和描述结构（字符数、关键词密度、卖点表述方式）。
            3. 评估当前 App 的搜索排名劣势（与竞品的排名差距、未覆盖的高价值词）。
            4. 竞品视觉资产对比（图标风格、截图数量与布局、是否有预览视频）。
            5. 竞品评分和评论分析（星级、评论关键词、用户满意/不满的高频主题）。

            输出必须包含以下格式化指标：
            - keywords_count=N（关键词数量）
            - current_ranking=N（当前排名）
            输出到：aso-competitor-audit.md
        """.trimIndent()

        val paths = extractRequiredPersistedFilePathsFromPrompt(text, "/tmp/workspace")

        assertEquals(
            setOf("/tmp/workspace/aso-competitor-audit.md"),
            paths
        )
    }

    @Test
    fun `extractRequiredPersistedFilePathsFromPrompt ignores optional demo save targets`() {
        val text = """
            请将正式结果保存到 output/final_report.txt。
            演示约束：如果缺少外部依赖，请直接产出可展示的 demo 版本结果；如适合，请同时保存到 output/final_report.md。
            Optionally save the preview to output/preview.md if appropriate.
        """.trimIndent()

        val paths = extractRequiredPersistedFilePathsFromPrompt(text, "/tmp/workspace")

        assertEquals(
            setOf("/tmp/workspace/output/final_report.txt"),
            paths
        )
    }

    @Test
    fun `extractRequiredPersistedFilePathsFromPrompt ignores saved paths quoted from previous step output`() {
        val text = """
            当前能力研究摘要：
            **Full research saved to `/tmp/workspace/output/workflow_knowledge_research.md`** (created via filesystem tools; matches required persistence).

            请输出当前步骤分析结果，并保存到 output/deep_analysis.md。
        """.trimIndent()

        val paths = extractRequiredPersistedFilePathsFromPrompt(text, "/tmp/workspace")

        assertEquals(
            setOf("/tmp/workspace/output/deep_analysis.md"),
            paths
        )
    }

    @Test
    fun `extractRequiredPersistedFilePathsFromPrompt ignores chinese completion claims from prior context`() {
        val text = """
            历史摘要：研究结果已保存到 output/workflow_knowledge_research.md。
            请将本步骤结论保存到 output/final_plan.md。
        """.trimIndent()

        val paths = extractRequiredPersistedFilePathsFromPrompt(text, "/tmp/workspace")

        assertEquals(
            setOf("/tmp/workspace/output/final_plan.md"),
            paths
        )
    }

    @Test
    fun `extractRequiredPersistedFilePathsFromPrompt detects create and update file targets without treating directories as files`() {
        val text = """
            Create the directory ./output/FullFeatureTest if it doesn't exist.
            Update ./output/FullFeatureTest/manifest.json to set status to "completed".
            Create ./output/FullFeatureTest/summary.txt with:
            - Final status: SUCCESS
        """.trimIndent()

        val paths = extractRequiredPersistedFilePathsFromPrompt(text, "/tmp/workspace")

        assertEquals(
            setOf(
                "/tmp/workspace/output/FullFeatureTest/manifest.json",
                "/tmp/workspace/output/FullFeatureTest/summary.txt"
            ),
            paths
        )
    }

    @Test
    fun `extractRequiredPersistedFilePathCandidatesFromPrompt keeps template path placeholders for later resolution`() {
        val text = """
            请把完整 YAML 保存到 {{var:output_dir}}/generated-workflow.yaml。
        """.trimIndent()

        val candidates = extractRequiredPersistedFilePathCandidatesFromPrompt(text)
        val resolved = resolvePersistedFilePathCandidates(candidates, "/tmp/workspace") {
            it.replace("{{var:output_dir}}", "./output")
        }

        assertEquals(setOf("{{var:output_dir}}/generated-workflow.yaml"), candidates)
        assertEquals(setOf("/tmp/workspace/output/generated-workflow.yaml"), resolved)
    }

    @Test
    fun `resolvePersistedFilePathCandidates ignores unresolved template placeholders`() {
        val resolved = resolvePersistedFilePathCandidates(
            candidates = setOf("{{var:output_dir}}/publishing_report.md"),
            baseDir = "/tmp/workspace"
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `extractRequiredPersistedFilePathCandidatesFromPrompt ignores save instructions hidden behind placeholders`() {
        val promptTemplate = """
            各步骤 YAML 片段：{{var:steps_yaml_fragments}}
            上次评估反馈（如有）：{{steps.assemble_and_refine:evaluate.output}}
            10. 把完整 YAML 保存到 {{var:output_dir}}/generated-workflow.yaml
        """.trimIndent()

        val candidates = extractRequiredPersistedFilePathCandidatesFromPrompt(promptTemplate)

        assertEquals(setOf("{{var:output_dir}}/generated-workflow.yaml"), candidates)
    }

    @Test
    fun `pruneClaimedPersistedFilePaths removes truncated prefixes of expected paths`() {
        val expected = setOf(
            "/home/user/Library/Application Support/braidrun/server-data/output/review_feedback.txt"
        )
        val claimed = setOf(
            "/home/user/Library/Application",
            "/home/user/Library/Application Support/braidrun/server-data/output/review_feedback.txt"
        )

        val pruned = pruneClaimedPersistedFilePaths(expected, claimed)

        assertEquals(
            setOf("/home/user/Library/Application Support/braidrun/server-data/output/review_feedback.txt"),
            pruned
        )
    }

    @Test
    fun `pruneClaimedPersistedFilePaths keeps exact expected path even if another expected path shares prefix`() {
        val expected = setOf(
            "/home/user/Library/Application",
            "/home/user/Library/Application Support/braidrun/server-data/output/review_feedback.txt"
        )
        val claimed = setOf("/home/user/Library/Application")

        val pruned = pruneClaimedPersistedFilePaths(expected, claimed)

        assertEquals(setOf("/home/user/Library/Application"), pruned)
    }

    @Test
    fun `selectPersistedFilePathsForValidation prefers explicit prompt targets over output claims`() {
        val expected = setOf("/tmp/output/introduction.txt")
        val claimed = setOf("/tmp/workspace/output/introduction.txt")

        val selected = selectPersistedFilePathsForValidation(expected, claimed)

        assertEquals(setOf("/tmp/output/introduction.txt"), selected)
    }

    @Test
    fun `selectPersistedFilePathsForValidation ignores save claims when prompt has no targets`() {
        val claimed = setOf("/tmp/workspace/output/introduction.txt")

        val selected = selectPersistedFilePathsForValidation(emptySet(), claimed)

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `autoPersistStepOutputIfPossible writes output to single missing text target`() {
        val tempDir = Files.createTempDirectory("workflow-auto-persist")
        val targetPath = tempDir.resolve("requirements.txt")
        val validation = PersistedFileValidationResult(
            checkedPaths = listOf(targetPath.toString()),
            missingPaths = listOf(targetPath.toString()),
            stalePaths = emptyList()
        )

        val persistedPath = autoPersistStepOutputIfPossible(
            output = "核心功能点:\n1. 登录\n2. 注册",
            expectedPersistedFiles = setOf(targetPath.toString()),
            claimedPersistedFiles = emptySet(),
            validation = validation
        )

        assertEquals(targetPath.toString(), persistedPath)
        assertEquals("核心功能点:\n1. 登录\n2. 注册", Files.readString(targetPath))
    }

    @Test
    fun `autoPersistStepOutputIfPossible skips when claims already exist`() {
        val tempDir = Files.createTempDirectory("workflow-auto-persist-skip")
        val targetPath = tempDir.resolve("requirements.txt")
        val validation = PersistedFileValidationResult(
            checkedPaths = listOf(targetPath.toString()),
            missingPaths = listOf(targetPath.toString()),
            stalePaths = emptyList()
        )

        val persistedPath = autoPersistStepOutputIfPossible(
            output = "核心功能点",
            expectedPersistedFiles = setOf(targetPath.toString()),
            claimedPersistedFiles = setOf("/tmp/other.txt"),
            validation = validation
        )

        assertNull(persistedPath)
        assertFalse(Files.exists(targetPath))
    }

    @Test
    fun `validatePersistedOutputs auto persists single missing target from step output`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val tempDir = Files.createTempDirectory("workflow-validate-persist")
        val targetPath = tempDir.resolve("requirements.txt")
        val step = WorkflowStep(step = "gather_requirements", agent = "analyst", input = "ignored")
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "validatePersistedOutputs",
            WorkflowStep::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Set::class.java,
            String::class.java
        )
        method.isAccessible = true

        method.invoke(
            executor,
            step,
            "execution-id",
            "核心功能点:\n1. 登录\n2. 注册",
            System.currentTimeMillis() - 10,
            setOf(targetPath.toString()),
            tempDir.toString()
        )

        assertEquals("核心功能点:\n1. 登录\n2. 注册", Files.readString(targetPath))
    }

    @Test
    fun `validatePersistedOutputs ignores unprompted save claims`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val tempDir = Files.createTempDirectory("workflow-validate-claims")
        val step = WorkflowStep(step = "deep_analysis", agent = "analyst", input = "ignored")
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "validatePersistedOutputs",
            WorkflowStep::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Set::class.java,
            String::class.java
        )
        method.isAccessible = true

        method.invoke(
            executor,
            step,
            "execution-id",
            "分析已保存到 output/deep_analysis.md",
            System.currentTimeMillis() - 10,
            emptySet<String>(),
            tempDir.toString()
        )

        assertFalse(Files.exists(tempDir.resolve("output/deep_analysis.md")))
    }

    @Test
    fun `validatePersistedFilePaths reports missing and stale files`() {
        val staleFile = Files.createTempFile("stale-output", ".md").toFile().apply {
            writeText("old content")
            setLastModified(1_000L)
            deleteOnExit()
        }
        val missingFile = staleFile.parentFile.resolve("missing-output.md")

        val result = validatePersistedFilePaths(
            paths = setOf(staleFile.absolutePath, missingFile.absolutePath),
            stepStartedAt = 5_000L,
            freshnessSlackMs = 0L
        )

        assertFalse(result.isValid)
        assertEquals(listOf(missingFile.absolutePath), result.missingPaths)
        assertEquals(listOf(staleFile.absolutePath), result.stalePaths)
    }

    @Test
    fun `resume state restores extracted variables for later code steps`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "resume-with-extract",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "discover_route",
                    code = CodeStepConfig(language = "bash", script = "echo route=alpha"),
                    extract = listOf(
                        ExtractConfig(
                            variable = "route",
                            pattern = "route=(\\w+)"
                        )
                    )
                ),
                WorkflowStep(
                    step = "reuse_route",
                    code = CodeStepConfig(language = "bash", script = "printf '%s' \"${'$'}WF_VAR_ROUTE\""),
                    dependsOn = listOf("discover_route")
                )
            )
        )

        val resumed = executor.execute(
            workflow = workflow,
            resumeState = WorkflowResumeState(
                preservedStepResults = mapOf(
                    "discover_route" to StepExecutionResult(
                        stepName = "discover_route",
                        agentName = "code(bash)",
                        success = true,
                        startTime = 1_000L,
                        endTime = 1_200L,
                        duration = 200L,
                        output = "route=alpha"
                    )
                )
            )
        )

        assertTrue(resumed.success, resumed.error)
        assertEquals("route=alpha", resumed.stepResults.getValue("discover_route").output?.trim())
        assertEquals("alpha", resumed.stepResults.getValue("reuse_route").output)
        assertEquals("alpha", resumed.variables["route"])
    }

    @Test
    fun `resume state replays failure transitions for downstream recovery steps`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val workflow = WorkflowDefinition(
            name = "resume-with-failure-transition",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "risky",
                    code = CodeStepConfig(language = "bash", script = "exit 7"),
                    onFailure = listOf(TransitionAction(next = "recover"))
                ),
                WorkflowStep(
                    step = "recover",
                    code = CodeStepConfig(language = "bash", script = "echo recovered"),
                    dependsOn = listOf("risky")
                )
            )
        )

        val resumed = executor.execute(
            workflow = workflow,
            resumeState = WorkflowResumeState(
                preservedStepResults = mapOf(
                    "risky" to StepExecutionResult(
                        stepName = "risky",
                        agentName = "code(bash)",
                        success = false,
                        startTime = 2_000L,
                        endTime = 2_300L,
                        duration = 300L,
                        error = "boom"
                    )
                ),
                activatedTransitionSteps = setOf("recover"),
                transitionActivationSources = mapOf("recover" to setOf("risky"))
            )
        )

        assertTrue(resumed.success, resumed.error)
        assertFalse(resumed.stepResults.getValue("risky").success)
        assertEquals("recovered", resumed.stepResults.getValue("recover").output?.trim())
    }

    // Regression: execution-process-0377fa69-... — Kimi/DeepSeek-R1 via
    // OpenRouter routes "I'm going to call this tool" intent through the
    // Reasoning channel as a {tool_call_id, tool_name, tool_args} JSON
    // envelope. The agent emitted this verbatim as a `reasoning_message`
    // event, so the UI's 推理链 → 思考 box showed the same JSON that the
    // 动作 box already showed below it. `isToolCallEnvelopeJson` is the
    // gate that keeps real prose-style reasoning flowing while suppressing
    // these duplicate envelopes.
    @Test
    fun `isToolCallEnvelopeJson detects tool-call envelope and ignores real reasoning`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )

        // The exact shape captured in the 0377fa69 execution log
        val envelope = """
            {"tool_call_id":"tool___write_file___m1sYI4W3iCImK7h7yR7J","tool_name":"__write_file__","tool_args":{"path":"/tmp/manifest.json","content":"{\"name\":\"日程管家\"}"}}
        """.trimIndent()
        assertTrue(executor.isToolCallEnvelopeJson(envelope), "tool-call envelope should be detected")

        // Real prose-style reasoning must NOT match (would silently lose model thinking)
        val realReasoning = "用户希望生成一个 PWA。我需要先写 manifest.json，再写 index.html，最后注册 sw.js。"
        assertFalse(executor.isToolCallEnvelopeJson(realReasoning), "Chinese prose reasoning should not match")

        val englishReasoning = "I'll start by drafting the file layout, then write the manifest, then the html shell."
        assertFalse(executor.isToolCallEnvelopeJson(englishReasoning), "English prose reasoning should not match")

        // A JSON object that happens to start with `{` but isn't the tool envelope shape
        val unrelatedJson = """{"step": "plan", "ok": true}"""
        assertFalse(executor.isToolCallEnvelopeJson(unrelatedJson), "unrelated JSON should not match")

        // Whitespace-leading envelope still matches (some providers prepend a newline)
        val leadingWhitespace = "\n  " + envelope
        assertTrue(executor.isToolCallEnvelopeJson(leadingWhitespace), "leading whitespace must not bypass detection")
    }
}
