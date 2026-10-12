package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
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
    @Test fun `terminal fence cancels an already waiting business RPC and refuses its retained adapter afterward`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val outputs = RunnerOutputs(h.ipc, gate, RunnerSecrets())
            val request = WorkflowOutputReadRequest("source", WorkflowOutputSelector(WorkflowOutputSelectorMode.EXECUTION_ID, "source-run"), setOf("answer"), "terminal", h.binding.string("executionId"), "read", WorkflowOutputMissingPolicy.FAIL, "COMPLETED", false)
            val pending = async { runCatching { outputs.read(request) } }
            val original = withTimeout(1000) { h.next() }; assertEquals("outputRead", original.string("type"))
            h.ipc.businessFence.close()
            assertTrue(withTimeout(1000) { pending.await() }.exceptionOrNull() is CancellationException)
            assertFailsWith<RunnerAuthorityLost> { outputs.read(request) }
            h.ipc.accept(h.reply(original, "outputValue", buildJsonObject {
                put("requestId", original.obj("payload").string("requestId")); put("value", JsonNull); put("sourceExecutionId", "source-run"); put("sourceWorkflowId", "source")
                put("outputs", buildJsonObject { put("answer", "late") }); put("missingOutputs", JsonArray(emptyList()))
            })) // Late old response cannot revive its canceled business waiter.
            gate.check()
        }
    }
    @Test fun `only unchanged previously emitted collection receipts remain replayable after business closure`() = runBlocking<Unit> {
        val fixtures = runnerJson.parseToJsonElement(javaClass.getResourceAsStream("/runner-workflow-v1/artifact-set-fixtures.json")!!.readBytes().toString(Charsets.UTF_8)).jsonArray
        val complete = fixtures.single { it.jsonObject.string("name") == "complete" }.jsonObject.obj("value")
        IpcHarness(complete.obj("binding")).use { h ->
            h.ipc.enableArtifactCollection(); h.ipc.enableArtifactSets()
            suspend fun next() = withContext(Dispatchers.IO) { runnerJson.parseToJsonElement(RunnerIpc.readLine(h.supervisorInput)!!.toString(Charsets.UTF_8)).jsonObject }
            h.ipc.sendFrame(complete); assertEquals(complete, withTimeout(1000) { next() })
            h.ipc.businessFence.close()
            h.ipc.sendFrame(complete); assertEquals(complete, withTimeout(1000) { next() })
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(JsonObject(complete + ("frameId" to JsonPrimitive("new-complete")))) }
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(JsonObject(complete + ("payload" to JsonObject(complete.obj("payload") + ("setIndexHash" to JsonPrimitive("f".repeat(64))))))) }
        }
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
            val secrets = RunnerSecrets(); var delegatedProcesses = 0; var preparedProcesses = 0
            val delegate = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                    delegatedProcesses++; return SubprocessExecutor.ExecResult(0, "", "", 0)
                }
            }
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, delegate, RunnerCheckpointStore(dir, b, secrets), secrets, callbackEnabled = true)
            val oldProcess = host.subprocessExecutor; assertEquals(delegate.isDocker, oldProcess.isDocker)
            val oldPublisher = host.outputPublisher; val oldResolver = host.outputResolver
            val oldInteraction = host.userInteraction(WorkflowHostScope(identity.executionId, identity.executionId, stepName = "code"))
            val oldCallback = requireNotNull(host.callbackToken); val oldScopedCallback = host::callbackTokenFor
            val directProcesses = RunnerProcesses(h.ipc, dir, gate) { _, _ -> error("No capture after fence") }
            val directCredentials = RunnerCredentials(h.ipc, gate, secrets)
            val interactive = RunnerInteractiveProcesses(h.ipc, dir, gate, secrets)
            val processRequest = SubprocessExecutor.ExecRequest(listOf("/bin/true"), dir.toFile(), environmentAtStart = { preparedProcesses++; emptyMap() })
            val published = PublishedWorkflowOutputPayload("answer", "text", "42", null, "1", WorkflowOutputVisibilityConfig(), "fixture", null, "terminal", "1", identity.executionId, "code", "COMPLETED")
            val read = WorkflowOutputReadRequest("source", WorkflowOutputSelector(WorkflowOutputSelectorMode.EXECUTION_ID, "source-run"), setOf("answer"), "terminal", identity.executionId, "code", WorkflowOutputMissingPolicy.FAIL, "COMPLETED", false)
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
            val retainedBefore = h.ipc.retainedEvidenceCount()
            host.acknowledge(buildJsonObject { put("ackSeq", event.obj("payload").long("eventSeq")) })
            // The host's ACK path reclaims the event; its replay below is proven by the journal's durable copy.
            assertEquals(retainedBefore - 1, h.ipc.retainedEvidenceCount())
            assertFailsWith<RunnerAuthorityLost> { host.scriptContents("root.yaml") }
            assertFailsWith<RunnerAuthorityLost> { oldProcess.execute(processRequest) }
            assertFailsWith<RunnerAuthorityLost> { directProcesses.execute(processRequest) }
            assertFailsWith<RunnerAuthorityLost> { interactive.open(scope, processRequest) }
            assertFailsWith<RunnerAuthorityLost> { oldPublisher.publish(published) }
            assertFailsWith<RunnerAuthorityLost> { oldResolver.read(read) }
            assertFailsWith<RunnerAuthorityLost> { oldCallback(30) }
            assertFailsWith<RunnerAuthorityLost> { oldScopedCallback(scope, 30) }
            assertFailsWith<RunnerAuthorityLost> { oldInteraction.sendMessage("late message") }
            assertFailsWith<RunnerAuthorityLost> { oldInteraction.askQuestion("late question", 100) }
            assertFailsWith<RunnerAuthorityLost> { directCredentials.acquire("fixture", 1, scope, "llm.request") }
            assertEquals(0, delegatedProcesses); assertEquals(0, preparedProcesses)
            val terminal = RunnerProtocolTest.frame(b, "result", buildJsonObject {
                put("binding", b); put("finalEventSeq", before); put("resultHash", "a".repeat(64)); put("artifactManifestHash", "b".repeat(64)); put("status", "COMPLETED"); put("terminationReason", JsonNull)
                put("stopReceipt", buildJsonObject {
                    put("binding", b); put("receiptId", "original-stop"); put("recordedAt", Instant.now().toString()); put("reason", "JVM_EXIT_PENDING")
                    put("confirmed", false); put("engineStarted", true); put("registeredProcesses", JsonArray(emptyList())); put("unmanagedProcessDetected", false); put("journalHash", "c".repeat(64))
                })
            })
            h.ipc.sendFrame(terminal)
            assertEquals(terminal, withTimeout(1000) { h.next() }, "No late business frames before original result")
            h.ipc.sendFrame(terminal); assertEquals(terminal, withTimeout(1000) { h.next() })
            assertFails { h.ipc.sendFrame(JsonObject(terminal + ("frameId" to JsonPrimitive("replacement-result")))) }
            h.ipc.sendFrame(event); assertEquals(event, withTimeout(1000) { h.next() }, "Exact retained original event remains transportable without allocating a new event")
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(JsonObject(event + ("frameId" to JsonPrimitive("new-event")))) }
            assertFailsWith<RunnerAuthorityLost> { h.ipc.sendFrame(JsonObject(event + ("payload" to JsonObject(event.obj("payload") + ("payload" to buildJsonObject { put("kind", "forged") }))))) }
            assertFailsWith<RunnerAuthorityLost> { h.ipc.send("userInteraction", buildJsonObject {
                put("idempotencyKey", "late"); put("operation", "tellUser"); put("childExecutionPath", JsonArray(emptyList())); put("message", "late raw adapter"); put("options", JsonArray(emptyList()))
            }) }
            interactive.close()
            assertFalse(RunnerArtifactSetProtocol.SOURCE in RunnerHostSession.CAPABILITIES)
            assertFalse(RunnerArtifactSetProtocol.GATES in RunnerHostSession.CAPABILITIES)
            assertEquals(before, host.finalEventSeq()); gate.check() // Finalization did not fabricate new authority or invalidate normal result publishing.
            host.close()
        }
    }
}
