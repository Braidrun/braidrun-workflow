package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class RunnerDecisionFreshnessReviewTest {
    private fun fresh(h: IpcHarness, id: String) = JsonObject(RunnerProtocolTest.environment(h.binding, Instant.now()) + ("environmentCheckId" to JsonPrimitive(id)))
    @Test fun `periodic wait probe wrong request and reused check cannot release approved occurrence`() = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(fresh(h, "initial"))
            val approved = async { RunnerApprovalHandler(h.ipc, gate).requestApproval(ApprovalRequest("root:step:2", "flow", h.binding.string("executionId"), "step", "Approve second", emptyList(), 0, "30", occurrenceId = "2")) }
            val request = h.next(); val frameId = request.string("frameId")
            gate.install(fresh(h, "periodic"))
            h.ipc.accept(h.reply(request, "approvalDecision", buildJsonObject {
                put("approvalId", "human-second"); put("idempotencyKey", "root:step:2"); put("occurrenceId", "2"); put("decisionVersion", 1)
                put("decision", "APPROVE"); put("editMode", "AS_IS"); put("editedItems", JsonArray(emptyList())); put("comment", JsonNull)
                put("decidedBy", "human"); put("decidedAt", Instant.now().toString())
            }))
            delay(40); assertFalse(approved.isCompleted)
            val unrelated = fresh(h, "unrelated"); gate.install(unrelated, "other-request")
            delay(40); assertFalse(approved.isCompleted)
            gate.install(unrelated, frameId) // The same observation cannot be relabeled as associated.
            delay(40); assertFalse(approved.isCompleted)
            gate.install(fresh(h, "decision-check"), frameId)
            assertTrue(withTimeout(1000) { approved.await() }.approved)
        }
    }
    @Test fun `askUser accepts legitimate associated pre-delivery check only for its own frame`() = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(fresh(h, "initial"))
            val handler = RunnerUserInteraction(h.ipc, gate, WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId")), RunnerSecrets())
            val answer = async { handler.askQuestion("Choose", 3000) }; val request = h.next()
            gate.install(fresh(h, "periodic"))
            gate.install(fresh(h, "human-check"), request.string("frameId"))
            h.ipc.accept(h.reply(request, "userResponse", buildJsonObject { put("idempotencyKey", request.obj("payload").string("idempotencyKey")); put("response", "selected") }))
            assertEquals("selected", withTimeout(1000) { answer.await() })
        }
    }
    @Test fun `decision correlation retains full environment binding and old epoch is rejected`() {
        val binding = RunnerProtocolTest.binding(); val gate = RunnerEnvironmentGate(binding, setOf("engine-http"))
        gate.install(RunnerProtocolTest.environment(binding, Instant.now())); gate.beginInteraction("request")
        val wrong = JsonObject(RunnerProtocolTest.environment(binding, Instant.now()) + mapOf("environmentCheckId" to JsonPrimitive("new"),
            "binding" to JsonObject(binding + ("resumeEpoch" to JsonPrimitive(5)))))
        assertFailsWith<RunnerAuthorityLost> { gate.install(wrong, "request") }
        gate.endInteraction("request")
    }
}
