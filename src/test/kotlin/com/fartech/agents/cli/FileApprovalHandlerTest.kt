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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileApprovalHandlerTest {
    @TempDir
    lateinit var tempDir: File

    private fun request(step: String = "review", approvalId: String = "exec-1:$step") = ApprovalRequest(
        approvalId = approvalId,
        workflowName = "local-review",
        executionId = "exec-1",
        stepName = step,
        message = "Review the screenshots before recording.",
        requestedAt = System.currentTimeMillis(),
        timeout = "30",
        reviewableGroups = listOf(
            ReviewablePayloadGroup(
                name = "findings",
                sourceVar = "review_findings",
                outputVar = "accepted_findings",
                title = "Visual findings",
                sourcePath = "/review/findings.json",
                items = listOf(Json.parseToJsonElement("{\"id\":\"f1\",\"fix\":\"left-align menu rows\"}"))
            )
        )
    )

    private fun handler(vararg lines: MutableList<String>) =
        FileApprovalHandler(tempDir, pollMillis = 20, printLine = { line -> lines.forEach { it.add(line) } })

    private suspend fun awaitFile(file: File) {
        withTimeout(5_000) { while (!file.isFile) delay(10) }
    }

    @Test
    fun `request is written with all material and an approve decision with comment and edits is consumed`() = runBlocking {
        val output = mutableListOf<String>()
        val pending = async { handler(output).requestApproval(request()) }
        val requestFile = tempDir.resolve("requests/exec-1_review.json")
        awaitFile(requestFile)
        val written = Json.parseToJsonElement(requestFile.readText()).jsonObject
        assertEquals("review", written["step"]!!.jsonPrimitive.content)
        assertTrue(requestFile.readText().contains("left-align menu rows"))
        assertTrue(requestFile.readText().contains("/review/findings.json"))
        assertEquals(tempDir.resolve("decisions/exec-1_review.json").absolutePath, written["decision_file"]!!.jsonPrimitive.content)

        tempDir.resolve("decisions/exec-1_review.json").writeText(
            """{"approved": true, "comment": "looks right", "edits": {"findings": [{"id":"f1","fix":"left-align menu rows","apply":true}]}}"""
        )
        val decision = withTimeout(5_000) { pending.await() }
        assertTrue(decision.approved)
        assertEquals("looks right", decision.comment)
        assertEquals(1, decision.edits!!["findings"]!!.size)
        assertFalse(tempDir.resolve("decisions/exec-1_review.json").exists())
        assertTrue(tempDir.resolve("decisions").listFiles()!!.any { it.name.startsWith("exec-1_review.consumed-") })
        assertTrue(output.any { it.contains("decide by writing") })
    }

    @Test
    fun `reject decision keeps its comment and a consumed file cannot answer the next request`() = runBlocking {
        val pending = async { handler().requestApproval(request()) }
        awaitFile(tempDir.resolve("requests/exec-1_review.json"))
        tempDir.resolve("decisions/exec-1_review.json").writeText("""{"approved": false, "comment": "menu rows still centred"}""")
        val first = withTimeout(5_000) { pending.await() }
        assertFalse(first.approved)
        assertEquals("menu rows still centred", first.comment)
        assertNull(first.edits)

        val second = async { handler().requestApproval(request()) }
        delay(150)
        assertTrue(second.isActive, "a consumed decision must not satisfy a later request with the same id")
        tempDir.resolve("decisions/exec-1_review.json").writeText("""{"approved": true}""")
        assertTrue(withTimeout(5_000) { second.await() }.approved)
    }

    @Test
    fun `malformed decision rejects loudly instead of hanging or approving`() = runBlocking {
        val pending = async { handler().requestApproval(request()) }
        awaitFile(tempDir.resolve("requests/exec-1_review.json"))
        tempDir.resolve("decisions/exec-1_review.json").writeText("""{"approve": "yes"}""")
        val decision = withTimeout(5_000) { pending.await() }
        assertFalse(decision.approved)
        assertTrue(decision.comment.orEmpty().contains("Malformed decision file"))
    }

    @Test
    fun `policy turns a step into a soft gate that continues after silence`() = runBlocking {
        tempDir.resolve("policy.json").writeText("""{"steps": {"escalate": {"auto_approve_after_seconds": 1, "comment": "no objection; continuing"}}}""")
        var now = 1_000L
        val handler = FileApprovalHandler(tempDir, pollMillis = 20, printLine = {}, nowMillis = { now })
        val pending = async { handler.requestApproval(request(step = "escalate", approvalId = "exec-1:escalate")) }
        awaitFile(tempDir.resolve("requests/exec-1_escalate.json"))
        delay(100)
        assertTrue(pending.isActive)
        now += 1_500
        val decision = withTimeout(5_000) { pending.await() }
        assertTrue(decision.approved)
        assertEquals("no objection; continuing", decision.comment)
        assertTrue(tempDir.resolve("decisions").listFiles()!!.any { it.name.startsWith("exec-1_escalate.consumed-") && it.readText().contains("policy_auto_approve") })

        // Hard gates (no policy entry) keep waiting.
        val hard = async { handler.requestApproval(request()) }
        now += 100_000
        delay(150)
        assertTrue(hard.isActive)
        tempDir.resolve("decisions/exec-1_review.json").writeText("""{"approved": false}""")
        assertFalse(withTimeout(5_000) { hard.await() }.approved)
    }

    @Test
    fun `decision file approves a real workflow step and rejection reaches variables`() = runBlocking {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(tempDir.absolutePath)),
                ConfigurationParameter("output_dir", JsonPrimitive(tempDir.resolve("output").absolutePath))
            ),
            enableMonitoring = false,
            approvalHandler = FileApprovalHandler(tempDir.resolve("approvals"), pollMillis = 20, printLine = {})
        )
        val workflow = WorkflowDefinition(
            name = "file-approval-test",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "review",
                    code = CodeStepConfig(language = "bash", script = "echo gate_passed=1"),
                    manualApproval = ManualApprovalConfig(enabled = true, timeout = 20)
                )
            )
        )
        val approved = async { executor.execute(workflow, initialInput = emptyMap(), externalExecutionId = "run-a") }
        val requestFile = tempDir.resolve("approvals/requests/run-a_review.json")
        awaitFile(requestFile)
        tempDir.resolve("approvals/decisions/run-a_review.json").writeText("""{"approved": true, "comment": "ship it"}""")
        val result = withTimeout(20_000) { approved.await() }
        assertTrue(result.success, result.error)
        assertEquals("ship it", result.variables["review_approval_comment"])

        val rejected = async { executor.execute(workflow, initialInput = emptyMap(), externalExecutionId = "run-b") }
        awaitFile(tempDir.resolve("approvals/requests/run-b_review.json"))
        tempDir.resolve("approvals/decisions/run-b_review.json").writeText("""{"approved": false, "comment": "AX5 rows still centred"}""")
        val failure = withTimeout(20_000) { rejected.await() }
        assertFalse(failure.success)
        assertEquals("rejected", failure.variables["approval_decision"])
        assertEquals("AX5 rows still centred", failure.variables["review_approval_comment"])
    }

    @Test
    fun `approval-dir flag installs the file handler and cannot combine with interactive`() = runBlocking {
        val selected = assertFailsWith<IllegalStateException> {
            BraidrunWorkflowCli(fileApprovalHandlerFactory = { error("file handler for ${it.name}") })
                .run(listOf("run", "missing-workflow.yaml", "--approval-dir", tempDir.resolve("gates").absolutePath))
        }
        assertEquals("file handler for gates", selected.message)
        val both = assertFailsWith<RuntimeException> {
            BraidrunWorkflowCli(fileApprovalHandlerFactory = { error("must not select file") }, interactiveApprovalHandlerFactory = { error("must not select interactive") })
                .run(listOf("run", "missing-workflow.yaml", "--approval-dir", "gates", "--interactive-approvals"))
        }
        assertTrue(both.message.orEmpty().contains("not both"))
        val validate = assertFailsWith<RuntimeException> {
            BraidrunWorkflowCli().run(listOf("validate", "missing-workflow.yaml", "--approval-dir", "gates"))
        }
        assertTrue(validate.message.orEmpty().contains("only accepts a workflow path"))
    }
}
