package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class RunnerAdaptersTest {
    @Test fun `grouped editing preserves columns item groups comments and explicit empty replacement`() = runBlocking {
        IpcHarness().use { harness ->
            val gate = RunnerEnvironmentGate(harness.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(harness.binding, Instant.now()))
            val groups = listOf(ReviewablePayloadGroup("bids", "source", "approved", keyField = "id", columns = listOf(ReviewableActionColumn("price", editable = true)), items = listOf(buildJsonObject { put("id", "one"); put("price", 7) })),
                ReviewablePayloadGroup("empty", "other", "reviewed", items = emptyList()))
            val pending = async { RunnerApprovalHandler(harness.ipc, gate, false).requestApproval(ApprovalRequest("approval-key", "flow", harness.binding.string("executionId"), "step", "Decide", emptyList(), 0, "30", groups)) }
            val request = harness.next(); assertEquals(2, request.obj("payload").array("editableColumns").size)
            assertEquals("approved", request.obj("payload").array("editableColumns")[0].jsonObject.obj("definition").string("outputVar"))
            harness.ipc.accept(RunnerProtocolTest.frame(harness.binding, "approvalDecision", buildJsonObject {
                put("approvalId", "human"); put("idempotencyKey", "approval-key"); put("decisionVersion", 2); put("decision", "APPROVE")
                put("editMode", "REPLACE"); put("editedItems", JsonArray(emptyList())); put("comment", "no selected rows")
                put("decidedBy", "human"); put("decidedAt", Instant.now().toString())
            })) // supervisor command path correlates by durable idempotencyKey
            val result = pending.await(); assertTrue(result.approved); assertEquals(emptyList(), result.edits!!["bids"])
            assertEquals(emptyList(), result.edits!!["empty"]); assertEquals("no selected rows", result.comment)
        }
    }
    @Test fun `credential leases reject changed scopes and rotate only by exact version after local OAuth`() = runBlocking {
        IpcHarness().use { harness ->
            val gate = RunnerEnvironmentGate(harness.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(harness.binding, Instant.now()))
            val secrets = RunnerSecrets(); val manager = RunnerCredentials(harness.ipc, gate, secrets)
            val scope = WorkflowHostScope(harness.binding.string("executionId"), harness.binding.string("executionId"), stepName = "call")
            val pending = async { manager.acquire("account", 3, scope, "connector.api") }
            val request = harness.next()
            harness.ipc.accept(harness.reply(request, "credentialGrant", buildJsonObject {
                put("requestId", request.obj("payload").string("requestId")); put("credentialRef", "account"); put("credentialLeaseId", "lease-account")
                put("version", 3); put("revision", 1); put("expiresAt", Instant.now().plusSeconds(60).toString())
                put("allowedOperations", JsonArray(listOf("connector.api", "oauth.refresh", "credential.rotate").map(::JsonPrimitive)))
                put("material", buildJsonObject { put("accessToken", "old-private-token"); put("refreshToken", "old-refresh-token"); put("clientId", "native-id") })
            }))
            val lease = pending.await(); assertFalse(lease.toString().contains("old-private"))
            assertFailsWith<IllegalArgumentException> { manager.material(lease, scope.copy(executionId = "other"), "connector.api") }
            assertFailsWith<IllegalArgumentException> { manager.material(lease, scope, "platform.businessBroker") }
            val sends = mutableListOf<RunnerConnectorRequest>()
            val connector = RunnerOAuthConnector(manager, RunnerConnectorTransport { req -> sends.add(req); RunnerConnectorResponse(200, "{\"access_token\":\"new-private-token\",\"refresh_token\":\"new-refresh-token\",\"expires_in\":300}") },
                "https://provider.example", "https://provider.example/token", RunnerOAuthMode.PUBLIC_NATIVE, secrets)
            val refresh = async { connector.refresh(lease, scope) }
            val rotation = harness.next(); assertEquals("credentialRotation", rotation.string("type"))
            assertEquals(3, rotation.obj("payload").long("expectedVersion"))
            assertEquals("new-private-token", rotation.obj("payload").obj("material").string("accessToken"))
            assertEquals("https://provider.example/token", sends.single().url)
            assertFalse(sends.single().form!!.containsKey("client_secret"))
            harness.ipc.accept(harness.reply(rotation, "ack", buildJsonObject { put("ackSeq", 0); put("revision", 2); put("serverTime", Instant.now().toString()) }))
            refresh.await()
            assertFailsWith<IllegalArgumentException> { manager.material(lease, scope, "connector.api") }
            assertFalse(secrets.redact("old-private-token new-private-token new-refresh-token").contains("private-token"))
        }
    }
    @Test fun `output read roundtrip retains source selector missing policy and visibility`() = runBlocking {
        IpcHarness().use { harness ->
            val gate = RunnerEnvironmentGate(harness.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(harness.binding, Instant.now()))
            val outputs = RunnerOutputs(harness.ipc, gate, RunnerSecrets())
            val read = async { outputs.read(WorkflowOutputReadRequest("source", WorkflowOutputSelector(WorkflowOutputSelectorMode.EXECUTION_ID, "source-run", step = "published"), setOf("answer"), "target", harness.binding.string("executionId"), "read", WorkflowOutputMissingPolicy.USE_DEFAULT, "COMPLETED", false)) }
            val request = harness.next(); assertEquals("published", request.obj("payload").obj("selector").string("step"))
            assertEquals("use_default", request.obj("payload").string("missingPolicy"))
            harness.ipc.accept(harness.reply(request, "outputValue", buildJsonObject {
                put("requestId", request.obj("payload").string("requestId")); put("value", JsonNull); put("sourceExecutionId", "source-run"); put("sourceWorkflowId", "source")
                put("outputs", buildJsonObject { put("answer", "42") }); put("missingOutputs", JsonArray(emptyList()))
            }))
            assertEquals("42", read.await().outputs["answer"])
        }
    }
    @Test fun `post approval continuation waits for a new evidence ID rather than accepting repeated observation`() = runBlocking {
        val binding = RunnerProtocolTest.binding(); val gate = RunnerEnvironmentGate(binding, setOf("engine-http"))
        val evidence = RunnerProtocolTest.environment(binding, Instant.now()); gate.install(evidence)
        val mark = gate.evidenceGeneration(); val waiting = async { gate.freshAfterDecision(mark) }
        gate.install(evidence); assertFalse(waiting.isCompleted)
        gate.install(JsonObject(RunnerProtocolTest.environment(binding, Instant.now()) + ("environmentCheckId" to JsonPrimitive("new-check"))))
        withTimeout(1000) { waiting.await() }
    }
}
