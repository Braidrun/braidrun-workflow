package com.fartech.agents.workflow

import com.fartech.agents.commons.SubprocessExecutorFactory
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

/**
 * TEMPORARY scratch harness (not a real regression test) — reproduces the
 * approval/transition shapes of app-dev-pipeline.yaml and app-factory.yaml
 * against the real engine, with an ApprovalHandler the CLI cannot provide.
 * Delete after the investigation.
 */
class ScratchApprovalShapeTest {

    private val reproDir = File(
        "/private/tmp/claude-501/-Users-liguoliang-GIT-braidrun/0ff040b1-5f15-453a-9335-24a3978cb57d/scratchpad/repro"
    )

    private val cases = listOf(
        "shape-a-fixed2.yaml:APPROVE",
        "shape-a-fixed2.yaml:REJECT",
        "shape-a-fixed2.yaml:TIMEOUT"
    )

    private enum class Mode { APPROVE, REJECT, TIMEOUT }

    private class Handler(private val mode: Mode) : ApprovalHandler {
        override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
            println(">>> approval requested: step=${request.stepName}")
            return when (mode) {
                Mode.APPROVE -> ApprovalDecision(approved = true, comment = "looks good")
                Mode.REJECT -> ApprovalDecision(approved = false, comment = "too expensive, pick another category")
                Mode.TIMEOUT -> {
                    delay(600_000)
                    ApprovalDecision.APPROVED_AS_IS
                }
            }
        }
    }

    private fun runCase(yaml: String, mode: Mode) {
        val workDir = File(reproDir, "work/${yaml.removeSuffix(".yaml")}-${mode.name.lowercase()}")
        workDir.mkdirs()
        val outDir = File(workDir, "output").also { it.mkdirs() }
        val parameters = listOf(
            ConfigurationParameter("subprocess_mode", JsonPrimitive("native")),
            ConfigurationParameter("working_dir", JsonPrimitive(workDir.absolutePath)),
            ConfigurationParameter("output_dir", JsonPrimitive(outDir.absolutePath))
        )
        val file = File(reproDir, yaml)
        val resolver = FileSystemWorkflowResolver(listOf(reproDir))
        var workflow = WorkflowParser.parseFile(file.absolutePath, resolver)
        if (mode == Mode.TIMEOUT) {
            workflow = workflow.copy(
                workflow = workflow.workflow.map { step ->
                    val ma = step.manualApproval
                    if (ma != null) step.copy(manualApproval = ma.copy(timeout = 2)) else step
                }
            )
        }
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = parameters,
            enableMonitoring = false,
            approvalHandler = Handler(mode),
            workflowResolver = resolver,
            codeStepExecutor = SubprocessExecutorFactory.create(parameters)
        )
        println("\n\n========== CASE $yaml / $mode ==========")
        val result = try {
            runBlocking { executor.execute(workflow, externalExecutionId = "repro-${workflow.name}-${mode.name.lowercase()}") }
        } catch (t: Throwable) {
            println("!!! execute() threw ${t::class.java.simpleName}: ${t.message}")
            println("========== END $yaml / $mode ==========")
            return
        }
        println("RESULT success=${result.success}")
        println("RESULT error=${result.error}")
        println("RESULT skippedSteps=${result.skippedSteps.sorted()}")
        println("RESULT recoveredFailures=${result.recoveredFailures.sorted()}")
        workflow.workflow.forEach { step ->
            val r = result.stepResults[step.step]
            val state = when {
                r == null && step.step in result.skippedSteps -> "SKIPPED"
                r == null -> "NEVER-RAN"
                r.success -> "SUCCESS"
                else -> "FAILED"
            }
            println("  step ${step.step.padEnd(26)} $state ${r?.error?.replace("\n", " ") ?: ""}")
        }
        listOf(
            "approval_decision", "approval_comment", "factory_rejected", "factory_outcome",
            "release_approved", "release_decision", "pipeline_complete", "factory_complete"
        ).forEach { v -> result.variables[v]?.let { println("  var $v=$it") } }
        println("========== END $yaml / $mode ==========")
    }

    @Test
    fun `reproduce approval shapes`() {
        cases.forEach { spec ->
            val (yaml, mode) = spec.split(":")
            runCase(yaml, Mode.valueOf(mode))
        }
    }
}
