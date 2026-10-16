package com.fartech.agents.runner

import com.fartech.agents.workflow.StepExecutionResult
import com.fartech.agents.workflow.WorkflowHostCheckpoint
import com.fartech.agents.workflow.WorkflowHostScope
import com.fartech.agents.workflow.WorkflowResumeState
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
}
