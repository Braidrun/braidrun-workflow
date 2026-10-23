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
    private fun ack(binding: JsonObject, replyTo: String, ackSeq: Long, id: String = java.util.UUID.randomUUID().toString()) =
        RunnerProtocolTest.frame(binding, "ack", buildJsonObject { put("ackSeq", ackSeq); put("revision", 1); put("serverTime", "2026-01-01T00:00:00Z") }, replyTo, id)
    private fun grantReply(binding: JsonObject, replyTo: String, leaseId: String) = RunnerProtocolTest.frame(binding, "credentialGrant", buildJsonObject {
        put("requestId", "request-$replyTo"); put("credentialLeaseId", leaseId); put("credentialRef", "account"); put("version", 3); put("revision", 1)
        put("expiresAt", "2026-01-01T00:01:00Z"); put("allowedOperations", JsonArray(listOf(JsonPrimitive("subscription.codex")))); put("material", buildJsonObject { put("authJson", "synthetic") })
    }, replyTo)
    /** An IPC whose writer drains to nowhere, for long sequences without a supervisor reading the pipe. */
    private fun sinkIpc(binding: JsonObject, onControl: (JsonObject) -> Unit) =
        RunnerIpc(binding, java.io.PipedInputStream(java.io.PipedOutputStream()), java.io.OutputStream.nullOutputStream(), onControl = onControl, onFailure = {}).apply { start() }

    @Test fun `acknowledged events leave the retained set and replay only as their exact durable original`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val durable = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
            val journal = RunnerEventJournal(h.binding, RunnerSecrets(), { path, bytes -> durable[path] = bytes }, { h.ipc.sendFrame(it); Unit }, {}, h.ipc.businessFence)
            h.ipc.retainEventsDurably { seq -> durable[".runner-state/events/$seq.json"] }
            val events = (1..3).map { journal.append("usage", buildJsonObject { put("outcome", "RELEASED"); put("n", it) }) }
            events.forEach { assertEquals(it, h.next()) }
            assertEquals(3, h.ipc.retainedEvidenceCount())
            journal.acknowledge(buildJsonObject { put("ackSeq", 2) }); h.ipc.reclaimAcknowledged(journal.acknowledged())
            assertEquals(1, h.ipc.retainedEvidenceCount(), "acknowledged events no longer occupy the retained set")
            fun changed(frame: JsonObject) = JsonObject(frame + ("payload" to JsonObject(frame.obj("payload") + ("payload" to buildJsonObject { put("outcome", "FORGED") }))))
            // Open fence: an acknowledged original travels again without re-entering the set; a changed one never does.
            h.ipc.sendFrame(events[0]); assertEquals(events[0], h.next()); assertEquals(1, h.ipc.retainedEvidenceCount())
            assertFailsWith<IllegalArgumentException> { h.ipc.sendFrame(changed(events[0])) }
            h.ipc.businessFence.close()
            // Closed fence: unacknowledged events replay from the retained set, acknowledged ones from the durable journal.
            h.ipc.sendFrame(events[2]); assertEquals(events[2], h.next())
            h.ipc.sendFrame(events[1]); assertEquals(events[1], h.next())
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(changed(events[1])) }
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(changed(events[2])) }
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(JsonObject(events[0] + ("frameId" to JsonPrimitive("new-event")))) }
            durable.remove(".runner-state/events/1.json")
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(events[0]) } // An unprovable original is refused, never trusted.
        }
    }

    @Test fun `without a durable journal reader acknowledged events stay retained`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val journal = RunnerEventJournal(h.binding, RunnerSecrets(), { _, _ -> }, { h.ipc.sendFrame(it); Unit }, {}, h.ipc.businessFence)
            val event = journal.append("usage", buildJsonObject { put("outcome", "RELEASED") }); assertEquals(event, h.next())
            h.ipc.reclaimAcknowledged(1)
            assertEquals(1, h.ipc.retainedEvidenceCount())
        }
    }

    @Test fun `seventy thousand renewals with their grants RELEASED events and ACKs never reach an identity bound`() = runBlocking<Unit> {
        val binding = RunnerProtocolTest.binding()
        lateinit var ipc: RunnerIpc
        lateinit var journal: RunnerEventJournal
        var acknowledged = 0
        ipc = sinkIpc(binding) { frame ->
            if (frame.string("type") == "ack") { journal.acknowledge(frame.obj("payload")); ipc.reclaimAcknowledged(journal.acknowledged()); acknowledged++ }
        }
        try {
            journal = RunnerEventJournal(binding, RunnerSecrets(), { _, _ -> }, { ipc.sendFrame(it); Unit }, {}, ipc.businessFence)
            ipc.retainEventsDurably { null }
            val renewals = RunnerIpc.MAX_INBOUND_IDENTITIES + 4464
            repeat(renewals) { i ->
                // Renewal: the grant reply completes its waiter, then the superseded grant is RELEASED and ACKed.
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { ipc.awaitReply("credentialGrant", frameId = "renewal-$i") {} }
                ipc.accept(grantReply(binding, "renewal-$i", "lease-$i"))
                assertEquals("lease-$i", waiter.await().payload.string("credentialLeaseId"))
                val event = journal.append("usage", buildJsonObject { put("outcome", "RELEASED"); put("credentialLeaseId", "lease-${i - 1}") })
                ipc.accept(ack(binding, event.string("frameId"), event.obj("payload").long("eventSeq")))
            }
            assertEquals(renewals, acknowledged)
            assertTrue(ipc.retainedEvidenceCount() <= 1)
            // Uncorrelated controls are still permanently deduplicated after the flood.
            val cancel = RunnerProtocolTest.frame(binding, "cancel", buildJsonObject { put("commandId", "stop"); put("reason", "cancel") })
            ipc.accept(cancel)
            assertFailsWith<RunnerProtocolException> { ipc.accept(JsonObject(cancel + ("payload" to buildJsonObject { put("commandId", "stop"); put("reason", "changed") }))) }
        } finally { ipc.close() }
    }

    @Test fun `correlated replies are windowed but a conflicting reply to the same request is refused`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val original = ack(h.binding, "event-frame", 1, id = "ack-1")
            h.ipc.accept(original); assertEquals(1, h.controls.size)
            h.ipc.accept(original); assertEquals(1, h.controls.size, "the identical frame is a duplicate")
            assertFailsWith<RunnerProtocolException> { h.ipc.accept(JsonObject(original + ("payload" to buildJsonObject { put("ackSeq", 1); put("revision", 2); put("serverTime", "2026-01-01T00:00:00Z") }))) }
            // S replays an ACK proof under a fresh frame identity: same reply, accepted.
            h.ipc.accept(ack(h.binding, "event-frame", 1, id = "ack-1-replay")); assertEquals(2, h.controls.size)
            // A different reply to the same request under a fresh identity conflicts.
            assertFailsWith<RunnerProtocolException> { h.ipc.accept(ack(h.binding, "event-frame", 7, id = "ack-1-forged")) }
            h.ipc.accept(grantReply(h.binding, "request-a", "lease-a"))
            assertFailsWith<RunnerProtocolException> { h.ipc.accept(grantReply(h.binding, "request-a", "lease-forged")) }
            // Human continuations stay permanently deduplicated even after the reply window rolls over.
            val decision = h.reply(buildJsonObject { put("frameId", "approval-frame") }, "approvalDecision", buildJsonObject {
                put("approvalId", "human-approval"); put("occurrenceId", "root:step"); put("idempotencyKey", "root:step"); put("decisionVersion", 1)
                put("decision", "APPROVE"); put("comment", JsonNull); put("editMode", "AS_IS"); put("editedItems", JsonArray(emptyList())); put("decidedBy", "human"); put("decidedAt", "2026-01-01T00:00:00Z")
            })
            h.ipc.accept(decision)
            repeat(RunnerIpc.REPLY_WINDOW + 1) { h.ipc.accept(grantReply(h.binding, "flood-$it", "lease-$it")) }
            assertFailsWith<RunnerProtocolException> { h.ipc.accept(JsonObject(decision + ("payload" to JsonObject(decision.obj("payload") + ("decision" to JsonPrimitive("REJECT")))))) }
            val cancel = RunnerProtocolTest.frame(h.binding, "cancel", buildJsonObject { put("commandId", "stop"); put("reason", "cancel") }, reply = "missing")
            val before = h.controls.size
            h.ipc.accept(cancel); h.ipc.accept(cancel); assertEquals(before + 1, h.controls.size)
        }
    }
}
