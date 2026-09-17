package com.fartech.agents.cli

import com.fartech.agents.workflow.ApprovalRequest
import com.fartech.agents.workflow.CodeStepConfig
import com.fartech.agents.workflow.ManualApprovalConfig
import com.fartech.agents.workflow.ReviewablePayloadGroup
import com.fartech.agents.workflow.WorkflowDefinition
import com.fartech.agents.workflow.WorkflowExecutor
import com.fartech.agents.workflow.WorkflowStep
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BraidrunWorkflowCliApprovalTest {
    @TempDir
    lateinit var tempDir: File

    private fun request(timeout: String = "30", requestedAt: Long = System.currentTimeMillis()) = ApprovalRequest(
        approvalId = "test-run:review",
        workflowName = "local-review",
        executionId = "test-run",
        stepName = "review",
        message = "Review the proposed files before continuing.",
        requestedAt = requestedAt,
        timeout = timeout,
        reviewableGroups = listOf(
            ReviewablePayloadGroup(
                name = "changes",
                sourceVar = "proposed",
                outputVar = "accepted",
                title = "Proposed changes",
                sourcePath = "/review/changes.json",
                items = listOf(Json.parseToJsonElement("{\"file\":\"Reader.swift\",\"action\":\"update\"}"))
            )
        )
    )

    @Test
    fun `only explicit approve grants approval and all material is printed`() = runBlocking {
        val input = ArrayDeque(listOf("", "yes", "approve-after-review", "approve Reviewed the AAC screens"))
        val output = mutableListOf<String>()
        val decision = InteractiveApprovalHandler({ input.removeFirst() }, output::add).requestApproval(request())

        assertTrue(decision.approved)
        assertEquals("Reviewed the AAC screens", decision.comment)
        assertNull(decision.edits)
        assertTrue(input.isEmpty())
        assertEquals(3, output.count { it.startsWith("No decision recorded") })
        val transcript = output.joinToString("\n")
        assertTrue(transcript.contains("Request ID: test-run:review"))
        assertTrue(transcript.contains("step: review"))
        assertTrue(transcript.contains("Review the proposed files"))
        assertTrue(transcript.contains("Source: /review/changes.json"))
        assertTrue(transcript.contains("Reader.swift"))
    }

    @Test
    fun `rejection comment reaches real workflow variables and prevents its code step`() = runBlocking {
        val handler = InteractiveApprovalHandler({ "reject Please fix the reading order first" }, {})
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(tempDir.absolutePath)),
                ConfigurationParameter("output_dir", JsonPrimitive(tempDir.resolve("output").absolutePath))
            ),
            enableMonitoring = false,
            approvalHandler = handler
        )
        val result = executor.execute(
            WorkflowDefinition(
                name = "cli-rejection-test",
                agents = emptyMap(),
                workflow = listOf(
                    WorkflowStep(
                        step = "review",
                        code = CodeStepConfig(language = "bash", script = "exit 99"),
                        manualApproval = ManualApprovalConfig(enabled = true, timeout = 10)
                    )
                )
            )
        )

        assertFalse(result.success)
        assertEquals("rejected", result.variables["approval_decision"])
        assertEquals("Please fix the reading order first", result.variables["approval_comment"])
        assertEquals("Please fix the reading order first", result.variables["review_approval_comment"])
        assertFalse(result.error.orEmpty().contains("exit code 99"))
    }

    @Test
    fun `EOF rejects and cannot approve a later request`() = runBlocking {
        var reads = 0
        val handler = InteractiveApprovalHandler({ reads++; null }, {})
        val first = handler.requestApproval(request())
        val second = handler.requestApproval(request())

        assertFalse(first.approved)
        assertTrue(first.comment.orEmpty().contains("EOF"))
        assertFalse(second.approved)
        assertEquals(1, reads)
    }

    @Test
    fun `timeout rejects explicitly and closes input for subsequent gates`() = runBlocking {
        val output = mutableListOf<String>()
        var reads = 0
        val handler = InteractiveApprovalHandler({ reads++; awaitCancellation() }, output::add, nowMillis = { 800 })
        val result = withTimeout(2000) {
            handler.requestApproval(request(timeout = "1", requestedAt = 0))
        }

        assertFalse(result.approved)
        assertTrue(result.comment.orEmpty().contains("timed out"))
        assertFalse(handler.requestApproval(request()).approved)
        assertEquals(1, reads)
        assertTrue(output.any { it.contains("no approval granted") })
    }

    @Test
    fun `invalid timeout never reads an approval`() = runBlocking {
        val handler = InteractiveApprovalHandler({ error("must not read") }, {})
        assertFalse(handler.requestApproval(request(timeout = "invalid")).approved)
    }

    @Test
    fun `interactive flag installs handler before parsing or running workflow`() = runBlocking {
        val failure = assertFailsWith<IllegalStateException> {
            BraidrunWorkflowCli { error("handler selected") }
                .run(listOf("run", "missing-workflow.yaml", "--interactive-approvals"))
        }
        assertEquals("handler selected", failure.message)
    }

    @Test
    fun `ordinary run does not install interactive handler`() = runBlocking {
        var installed = false
        assertFailsWith<Exception> {
            BraidrunWorkflowCli { installed = true; error("must not install") }
                .run(listOf("run", tempDir.resolve("missing-workflow.yaml").absolutePath))
        }
        assertFalse(installed)
    }

    @Test
    fun `validation command rejects execution-only approval flag`() = runBlocking {
        val failure = assertFailsWith<RuntimeException> {
            BraidrunWorkflowCli().run(listOf("validate", "missing-workflow.yaml", "--interactive-approvals"))
        }
        assertTrue(failure.message.orEmpty().contains("only accepts a workflow path"))
    }

    @Test
    fun `noninteractive standard input is rejected instead of approving`() {
        assumeTrue(System.console() == null, "This test requires a non-TTY test runner")
        val failure = assertFailsWith<RuntimeException> { systemInteractiveApprovalHandler() }
        assertTrue(failure.message.orEmpty().contains("requires an interactive TTY"))
        assertTrue(failure.message.orEmpty().contains("No approvals were granted"))
    }
}
