package com.fartech.agents.runner

import com.fartech.agents.workflow.ApprovalDecision
import com.fartech.agents.workflow.StepExecutionResult
import com.fartech.agents.workflow.WorkflowExecutor
import com.fartech.agents.workflow.WorkflowHostCheckpoint
import com.fartech.agents.workflow.WorkflowHostScope
import com.fartech.agents.workflow.WorkflowResumeState
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A checkpoint the store writes must restore in the next attempt, computed getters in the file included. */
class RunnerCheckpointStoreResumeTest {
    @Test
    fun `a savepoint with a preserved step restores in a newly fenced attempt`() {
        val root = Files.createTempDirectory("runner-checkpoint-resume-")
        val previous = RunnerProtocolTest.binding()
        val execution = previous.getValue("executionId").jsonPrimitive.content
        val first = StepExecutionResult("first", "code(bash)", true, 1_792_137_646_000L, 1_792_137_647_297L, 1_297L, output = "first=ran",
            producedVariables = mapOf("first_output" to "first=ran"))
        val state = WorkflowResumeState(preservedStepResults = mapOf("first" to first), variables = mapOf("app" to "fixture"), stepOutputs = mapOf("first" to "first=ran"))
        val announcement = RunnerCheckpointStore(root, previous, RunnerSecrets())
            .save(WorkflowHostCheckpoint(WorkflowHostScope(execution, execution), "fixture", state, safeToResume = true), eventSeq = 6)
        val written = Files.readString(root.resolve(announcement.getValue("relativePath").jsonPrimitive.content))
        assertTrue("\"durationSeconds\":" in written, "the engine writes the computed getter; the file is what a real attempt leaves behind")

        val resumed = JsonObject(previous + mapOf("attemptId" to JsonPrimitive("attempt-2"), "resumeEpoch" to JsonPrimitive(1),
            "runnerInstanceId" to JsonPrimitive(previous.getValue("runnerInstanceId").jsonPrimitive.content + ".attempt-2")))
        val restored = RunnerCheckpointStore(root, resumed, RunnerSecrets()).restore(announcement, previous)
        assertEquals(state, restored)
        assertEquals(1.297, restored.preservedStepResults.getValue("first").durationSeconds)
    }

    /**
     * A concurrent run parks with one step completed and its sibling waiting for a human. The quiescent savepoint goes
     * through the real store into a newly fenced attempt, which restarts exactly the step that had not completed: the
     * same approval identity and original deadline, then its dependent; the completed step never runs again.
     */
    @Test
    fun `a concurrent run resumes from its quiescent savepoint with the pending approval and the completed step`() = runBlocking {
        val root = Files.createTempDirectory("runner-concurrent-resume-")
        val previous = RunnerProtocolTest.binding()
        val execution = previous.getValue("executionId").jsonPrimitive.content
        val base = WorkflowHostTest.Host(root)
        val identity = base.binding.copy(executionId = execution)
        val first = WorkflowHostTest.ConcurrentHost(base, decide = { awaitCancellation() }, binding = identity)
        val workflow = first.workflow()
        val running = async(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = first).execute(workflow, emptyMap(), execution) }
        val parked = try {
            withTimeout(5000) {
                while (base.checkpoints.none { it.safeToResume && it.pendingApprovals.isNotEmpty() }) delay(10)
                base.checkpoints.first { it.safeToResume && it.pendingApprovals.isNotEmpty() }
            }
        } finally { running.cancelAndJoin() }
        val original = first.requests.single()
        assertEquals(setOf("slow"), parked.state.preservedStepResults.keys)

        val secrets = RunnerSecrets()
        val announcement = RunnerCheckpointStore(root, previous, secrets).save(parked, eventSeq = 9)
        val resumedBinding = JsonObject(previous + mapOf("attemptId" to JsonPrimitive("attempt-2"), "resumeEpoch" to JsonPrimitive(1),
            "runnerInstanceId" to JsonPrimitive(previous.getValue("runnerInstanceId").jsonPrimitive.content + ".attempt-2")))
        val restored = RunnerCheckpointStore(root, resumedBinding, secrets).restore(announcement, previous)

        val second = WorkflowHostTest.ConcurrentHost(WorkflowHostTest.Host(root), decide = { ApprovalDecision.APPROVED_AS_IS }, binding = identity)
        val result = withTimeout(5000) { WorkflowExecutor(HttpAccess(), emptyList(), host = second).execute(workflow, emptyMap(), execution, restored) }
        assertTrue(result.success, result.error)
        val again = second.requests.single()
        assertEquals(original.approvalId, again.approvalId)
        assertEquals(original.requestedAt, again.requestedAt)
        assertEquals(listOf("after", "review"), second.bodies.sorted(), "only the steps that had not completed run")
        assertEquals(setOf("slow", "review", "after"), result.stepResults.keys)
    }
}
