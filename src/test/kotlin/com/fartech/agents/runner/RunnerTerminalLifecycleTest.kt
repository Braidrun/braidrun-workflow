package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerTerminalLifecycleTest {
    @Test fun `gate and source lifecycle capabilities require exact dependencies and remain unavailable without assembled providers`() {
        val set = RunnerArtifactSetProtocol.CAPABILITY; val collection = RunnerCollectionProtocol.CAPABILITY
        val gates = RunnerArtifactSetProtocol.GATES; val source = RunnerArtifactSetProtocol.SOURCE
        for (required in listOf(setOf(source), setOf(gates, set, collection))) {
            assertEquals(RunnerNegotiationReason.RUNNER_SET_DEPENDENCY_REQUIRED, assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES)
            }.reason)
        }
        for (required in listOf(setOf(source, set, collection), setOf(gates, set, collection, RunnerCodeControlNegotiation.PER_CALL))) {
            assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES)
            }.reason)
        }
    }

    @Test fun `sealed event sequence cannot acquire a late business event while existing ACK remains bounded`() {
        val sent = mutableListOf<JsonObject>()
        val journal = RunnerEventJournal(RunnerProtocolTest.binding(), RunnerSecrets(), { _, _ -> }, { sent += it }, { error("Unexpected failed journal") })
        val original = journal.append("step", buildJsonObject { put("kind", "step_completed") })
        assertEquals(1, journal.seal()); assertEquals(1, journal.seal())
        assertFailsWith<RunnerAuthorityLost> { journal.append("step", buildJsonObject { put("kind", "late_callback") }) }
        journal.acknowledge(buildJsonObject { put("ackSeq", 1) }); assertEquals(1, journal.lastSequence())
        assertEquals(listOf(original), sent)
        assertFails { journal.acknowledge(buildJsonObject { put("ackSeq", 2) }) }
    }
    @Test fun `business finalization closes approval and operation authority before any POST STOP decision`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = "name: terminal\nagents: {}\nworkflow:\n  - step: code\n    code:\n      language: bash\n      script: 'true'\n"
        Files.writeString(dir.resolve("root.yaml"), source)
        val hash = sha256(source.toByteArray()); val b = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root"); put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", hash) }) })
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), b)
        IpcHarness(b).use { h ->
            val gate = RunnerEnvironmentGate(b, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(b, Instant.now()))
            val identity = WorkflowHostBinding(b.string("ownerId"), b.string("actorUserId"), "billing", b.string("executionId"), b.string("runnerId"), b.string("runnerInstanceId"), b.long("resumeEpoch"), b.string("attemptId"), hash, b.string("runtimeHash"))
            val secrets = RunnerSecrets(); val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), RunnerCheckpointStore(dir, b, secrets), secrets)
            val scope = WorkflowHostScope(identity.executionId, identity.executionId, stepName = "approve-candidate-delivery")
            val request = ApprovalRequest(identity.executionId + ":approve-candidate-delivery:1", "terminal", identity.executionId, "approve-candidate-delivery", "Original review", emptyList(), System.currentTimeMillis(), "259200", occurrenceId = "1")
            val waiting = async { runCatching { host.approvalHandler.requestApproval(request) } }
            withTimeout(1000) { h.next() }
            host.monitor(WorkflowMonitorUpdate(identity.executionId, "step_completed", "code", success = true)); val event = h.next()
            val before = host.finalEventSeq(); assertEquals(before, host.beginFinalization()); assertEquals(before, host.beginFinalization())
            assertTrue(runCatching { withTimeout(1000) { waiting.await() } }.exceptionOrNull() is CancellationException)
            assertFailsWith<RunnerAuthorityLost> { host.beforeOperation(scope, "late-model-or-code") }
            assertFailsWith<RunnerAuthorityLost> { host.approvalHandler.requestApproval(request) }
            assertFailsWith<RunnerAuthorityLost> { host.monitor(WorkflowMonitorUpdate(identity.executionId, "step_started", "late-code")) }
            host.acknowledge(buildJsonObject { put("ackSeq", event.obj("payload").long("eventSeq")) })
            assertFailsWith<RunnerAuthorityLost> { host.scriptContents("root.yaml") }
            assertFalse(RunnerArtifactSetProtocol.SOURCE in RunnerHostSession.CAPABILITIES)
            assertFalse(RunnerArtifactSetProtocol.GATES in RunnerHostSession.CAPABILITIES)
            assertEquals(before, host.finalEventSeq()); gate.check() // Finalization did not fabricate new authority or invalidate normal result publishing.
            host.close()
        }
    }
}
