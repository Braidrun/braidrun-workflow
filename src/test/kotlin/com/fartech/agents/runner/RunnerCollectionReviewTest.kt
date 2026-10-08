package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerCollectionReviewTest {
    private fun gate(f: CollectionFixture): RunnerEnvironmentGate {
        val gate = RunnerEnvironmentGate(f.binding, setOf("subprocess")); val e = RunnerProtocolTest.environment(f.binding, Instant.now())
        gate.install(JsonObject(e + ("egress" to JsonArray(e.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("subprocess"))) }))))
        return gate
    }
    @Test fun `authorized dynamic workflow completes with root-only declarations`(@TempDir dir: Path) = dynamicFixture(dir, true)
    @Test fun `dynamic invocation never borrows declarations from another static lineage`(@TempDir dir: Path) = dynamicFixture(dir, false)
    private fun dynamicFixture(dir: Path, rootOnly: Boolean) = runBlocking<Unit> {
        val f = collectionFixture(dir, change = { p -> if (rootOnly) JsonObject(p + ("declarations" to JsonArray(p.array("declarations").take(1)))) else p })
        val bundle = FrozenWorkflowBundle(dir, f.bundle, f.binding); val policy = RunnerCollectionPolicy(bundle)
        IpcHarness(f.binding).use { h ->
            val gate = gate(f); val secrets = RunnerSecrets(); val b = f.binding
            val identity = WorkflowHostBinding(b.string("ownerId"), b.string("actorUserId"), "billing", b.string("executionId"), b.string("runnerId"), b.string("runnerInstanceId"), b.long("resumeEpoch"), b.string("attemptId"), b.string("definitionHash"), b.string("runtimeHash"))
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), RunnerCheckpointStore(dir, b, secrets), secrets, collectionPolicy = policy)
            try {
                val root = WorkflowHostScope(identity.executionId, identity.executionId)
                host.admit(bundle.rootDefinition, root); host.bindArtifactProducer(bundle.rootDefinition, root)
                val tools = WorkflowTools(HttpAccess(), emptyList(), NestedWorkflowRuntime(host = host), root.copy(stepName = "agent"))
                val result = withTimeout(3000) { tools.executeWorkflow("child.yaml") }
                assertTrue(result.contains("success", true), result); gate.check()
                val dynamic = JsonObject(collectFrame(f, if (rootOnly) "root" else "child").obj("payload") + ("childExecutionPath" to buildJsonArray { add("agent_dynamic_fixture") }))
                assertFails { policy.validateRequest(dynamic) }
                assertTrue(Files.list(dir.resolve(".runner-state/events")).use { paths -> paths.noneMatch { Files.readString(it).contains("\"type\":\"artifact\"") } })
            } finally { host.close() }
        }
    }
    @Test fun `durable semantic intent rejects fresh IDs after component cancellation and reconstruction`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = collectionFixture(dir); val policy = RunnerCollectionPolicy(FrozenWorkflowBundle(dir, f.bundle, f.binding))
        val original = collectFrame(f, "root"); var events = 0
        fun broker(h: IpcHarness, gate: RunnerEnvironmentGate): RunnerCollectionBarrier {
            h.ipc.enableArtifactCollection(); val store = RunnerCheckpointStore(dir, f.binding, RunnerSecrets())
            val journal = RunnerEventJournal(f.binding, RunnerSecrets(), { p, b -> store.writePrivate(p, b); Unit }, { h.ipc.sendFrame(it); Unit }, {})
            return RunnerCollectionBarrier(dir, h.ipc, store, gate, policy, RunnerSecrets(), { body -> assertEquals("original-start", body.string("producerStartEventId")) },
                recordEvent = { payload, reply, frame, before -> events++; journal.append("artifact", payload, frame, reply, before) },
                acknowledge = journal::acknowledge, guardedComplete = { _, action -> action() })
        }
        IpcHarness(f.binding).use { h ->
            val first = broker(h, gate(f))
            first.receive(original)
            val event = withTimeout(2000) { h.next() }; assertEquals("artifact", event.obj("payload").string("type"))
            first.close() // original ACK is held; no producer rerun or new attempt is authorized by this fixture
        }
        fun fingerprints(): Map<String, String> = Files.list(dir.resolve(".runner-state/artifact-collection")).use { paths -> paths.toList().associate { it.fileName.toString() to sha256(Files.readAllBytes(it)) } }
        val before = fingerprints()
        IpcHarness(f.binding).use { h ->
            val freshGate = gate(f); val restored = broker(h, freshGate)
            try {
                val different = JsonObject(original + mapOf("frameId" to JsonPrimitive("new-frame"), "payload" to JsonObject(original.obj("payload") + mapOf(
                    "requestId" to JsonPrimitive("new-request"), "publicationId" to JsonPrimitive("new-publication"), "artifactId" to JsonPrimitive("new-artifact")
                ))))
                restored.receive(different)
                withTimeout(2000) { while (runCatching { freshGate.check() }.isSuccess) delay(10) }
                assertEquals(1, events); assertEquals(before, fingerprints())
            } finally { restored.close() }
        }
    }
}
