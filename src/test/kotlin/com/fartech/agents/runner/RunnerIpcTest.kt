package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Instant
import kotlin.test.*

internal class IpcHarness(val binding: JsonObject = RunnerProtocolTest.binding(), onControl: (JsonObject) -> Unit = {}) : AutoCloseable {
    val supervisorInput = PipedInputStream(2 * RunnerIpc.MAX_FRAME_BYTES)
    private val engineOutput = PipedOutputStream(supervisorInput)
    private val engineInput = PipedInputStream()
    private val supervisorOutput = PipedOutputStream(engineInput)
    val controls = mutableListOf<JsonObject>()
    var failed = false
    val ipc = RunnerIpc(binding, engineInput, engineOutput, onControl = { synchronized(controls) { controls.add(it) }; onControl(it) }, onFailure = { failed = true })
    init { ipc.start() }
    suspend fun next(): JsonObject = withTimeout(10_000) {
        try {
            runInterruptible(Dispatchers.IO) {
                val raw = RunnerIpc.readLine(supervisorInput) ?: error("EOF")
                RunnerProtocol().parse(raw.toString(Charsets.UTF_8))
            }
        } catch (interrupted: java.io.InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw interrupted
        }
    }
    fun reply(request: JsonObject, type: String, payload: JsonObject): JsonObject = RunnerProtocolTest.frame(binding, type, payload, request.string("frameId"))
    fun eof() { supervisorOutput.close() }
    override fun close() { ipc.close(); supervisorOutput.close(); supervisorInput.close(); engineInput.close(); engineOutput.close() }
}

class RunnerIpcTest {
    @Test fun `cancelled fixture reader cannot hang the test scope`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val waiting=launch { h.next() }
            delay(30)
            withTimeout(1000) { waiting.cancelAndJoin() }
            assertTrue(waiting.isCancelled)
        }
    }
    @Test fun `approval and askUser round trip preserve request correlation`() = runBlocking {
        IpcHarness().use { harness ->
            val gate = RunnerEnvironmentGate(harness.binding, setOf("engine-http"))
            gate.install(RunnerProtocolTest.environment(harness.binding, Instant.now()))
            val approval = async { RunnerApprovalHandler(harness.ipc, gate, requireFreshDecision = false).requestApproval(ApprovalRequest(
                "root:step", "workflow", harness.binding.string("executionId"), "step", "Approve?", emptyList(), System.currentTimeMillis(), "60")) }
            val request = harness.next()
            assertEquals("approval", request.string("type"))
            val decision = harness.reply(request, "approvalDecision", buildJsonObject {
                put("approvalId", "human-approval"); put("occurrenceId", "root:step"); put("idempotencyKey", "root:step"); put("decisionVersion", 1)
                put("decision", "APPROVE"); put("comment", JsonNull); put("editMode", "AS_IS"); put("editedItems", JsonArray(emptyList())); put("decidedBy", "human"); put("decidedAt", Instant.now().toString())
            })
            harness.ipc.accept(decision); harness.ipc.accept(decision)
            assertTrue(approval.await().approved)
            val interaction = RunnerUserInteraction(harness.ipc, gate,
                WorkflowHostScope(harness.binding.string("executionId"), harness.binding.string("executionId")), RunnerSecrets(), requireFreshDecision = false)
            val answer = async { interaction.askQuestion("Which value?", 1000) }
            val question = harness.next()
            harness.ipc.accept(harness.reply(question, "userResponse", buildJsonObject {
                put("idempotencyKey", question.obj("payload").string("idempotencyKey")); put("response", "chosen")
            }))
            assertEquals("chosen", answer.await())
            interaction.sendMessage("Finished")
            assertEquals("tellUser", harness.next().obj("payload").string("operation"))
        }
    }
    @Test fun `late unknown replies never trigger controls old epoch and changed duplicate fail`() = runBlocking {
        IpcHarness().use { harness ->
            val late = RunnerProtocolTest.frame(harness.binding, "userResponse", buildJsonObject { put("idempotencyKey", "old"); put("response", "late") }, "missing")
            harness.ipc.accept(late); assertTrue(harness.controls.isEmpty())
            val cancel = RunnerProtocolTest.frame(harness.binding, "cancel", buildJsonObject { put("commandId", "stop"); put("reason", "cancel") }, reply = "missing")
            harness.ipc.accept(cancel); harness.ipc.accept(cancel); assertEquals(1, harness.controls.size)
            assertFailsWith<RunnerProtocolException> { harness.ipc.accept(JsonObject(cancel + ("payload" to buildJsonObject { put("commandId", "stop"); put("reason", "changed") }))) }
            val stale = JsonObject(cancel + ("binding" to JsonObject(harness.binding + ("resumeEpoch" to JsonPrimitive(99)))))
            assertFailsWith<RunnerProtocolException> { harness.ipc.accept(stale) }
        }
    }
    @Test fun `known credentials redacted across split log writes and structured data`() {
        val secrets = RunnerSecrets(); secrets.register(buildJsonObject { put("token", "custom-secret") })
        val bytes = java.io.ByteArrayOutputStream(); val stream = secrets.logStream(bytes)
        stream.write("first custom-".toByteArray()); stream.flush(); stream.write("secret final\n".toByteArray())
        assertFalse(bytes.toString().contains("custom-secret")); assertTrue(bytes.toString().contains("<redacted>"))
        assertFalse(secrets.redact(buildJsonObject { put("value", "custom-secret") }).toString().contains("custom-secret"))
    }
}
