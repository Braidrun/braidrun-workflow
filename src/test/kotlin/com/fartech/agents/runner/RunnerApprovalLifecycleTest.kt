package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerApprovalLifecycleTest {
    private fun decision(h: IpcHarness, original: JsonObject, approved: Boolean = true) = h.reply(original, "approvalDecision", buildJsonObject {
        val body = original.obj("payload")
        put("approvalId", "original-human-record"); put("idempotencyKey", body.string("idempotencyKey")); put("occurrenceId", body.string("occurrenceId")); put("decisionVersion", 1)
        put("decision", if (approved) "APPROVE" else "REJECT"); put("decidedBy", h.binding.string("ownerId")); put("decidedAt", Instant.now().toString()); put("comment", "original comment")
        put("editMode", "AS_IS"); put("editedItems", JsonArray(emptyList()))
    })
    @Test fun `same binding reconstructs original approval frame and identical original decision without new request`(@TempDir dir: Path) = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val secrets = RunnerSecrets(); val store = RunnerCheckpointStore(dir, h.binding, secrets)
            val request = ApprovalRequest("${h.binding.string("executionId")}:approve-candidate-delivery:1", "source", h.binding.string("executionId"), "approve-candidate-delivery", "Review original candidate", listOf(h.binding.string("ownerId")), System.currentTimeMillis(), "259200", occurrenceId = "1")
            fun handler() = RunnerApprovalHandler(h.ipc, gate, false, RunnerApprovalJournal(dir, h.binding, store, secrets))
            val firstHandler = handler(); val first = async { firstHandler.requestApproval(request) }; val original = withTimeout(1000) { h.next() }
            val response = decision(h, original); h.ipc.accept(response); assertTrue(first.await().approved)
            firstHandler.close()
            val secondHandler = handler(); val second = async { secondHandler.requestApproval(request) }
            assertEquals(original, withTimeout(1000) { h.next() }); assertFalse(second.isCompleted)
            h.ipc.accept(response) // Exact original inbound receipt can complete the re-registered original waiter.
            assertTrue(second.await().approved)
            assertFails { RunnerApprovalJournal(dir, h.binding, store, secrets).prepare(request.copy(message = "changed material"), original.obj("payload")) }
            assertFails { RunnerApprovalJournal(dir, h.binding, store, secrets).prepare(request.copy(requestedAt = request.requestedAt + 1), original.obj("payload")) }
            Files.walk(dir.resolve(".runner-state/approval-requests")).use { paths -> assertEquals(1, paths.filter { Files.isRegularFile(it) }.count()) }
            secondHandler.close()
        }
    }
    @Test fun `approval transport spends only the original remaining budget and expired requests never dispatch`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val request = ApprovalRequest("original:1", "source", h.binding.string("executionId"), "approve-candidate-delivery", "Review", emptyList(), System.currentTimeMillis() - 700, "1", occurrenceId = "1")
            val handler = RunnerApprovalHandler(h.ipc, gate, false)
            val started = System.nanoTime(); val job = async { runCatching { handler.requestApproval(request) } }
            withTimeout(1000) { h.next() }
            assertTrue(withTimeout(1000) { job.await() }.exceptionOrNull() is TimeoutCancellationException)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 650, "No new 1-second budget after 700ms already elapsed")
            assertFails { handler.requestApproval(request.copy(requestedAt = System.currentTimeMillis() - 2000)) }
            handler.close()
        }
    }
    @Test fun `fresh environment confirmation after decision remains inside original approval deadline`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val request = ApprovalRequest("original:1", "source", h.binding.string("executionId"), "approve-candidate-delivery", "Review", emptyList(), System.currentTimeMillis() - 700, "1", occurrenceId = "1")
            val handler = RunnerApprovalHandler(h.ipc, gate)
            val started = System.nanoTime(); val job = async { runCatching { handler.requestApproval(request) } }
            val frame = withTimeout(1000) { h.next() }; h.ipc.accept(decision(h, frame))
            assertTrue(withTimeout(1000) { job.await() }.exceptionOrNull() is TimeoutCancellationException)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 650)
            handler.close()
        }
    }
    @Test fun `normal source approval waits after intervening code with original 259200 budget then original decision`(@TempDir dir: Path) = interpreter(dir, "approve")
    @Test fun `original source approval rejection never starts delivery acceptance body`(@TempDir dir: Path) = interpreter(dir, "reject")
    @Test fun `pending proof cancellation preserves original request and ignores late human decision`(@TempDir dir: Path) = interpreter(dir, "cancel")
    @Test fun `restored pending manual approval retains original key and already-expired 259200 deadline`(@TempDir dir: Path) = interpreter(dir, "expired-resume")

    @Test fun `older checkpoint recovers original pending timestamp and refuses absent or conflicting deadline`(@TempDir dir: Path) {
        val binding = RunnerProtocolTest.binding(); val secrets = RunnerSecrets(); val originalStore = RunnerCheckpointStore(dir, binding, secrets)
        val root = binding.string("executionId"); val key = "$root:approve-candidate-delivery:1"; val at = System.currentTimeMillis() - 5000
        val request = ApprovalRequest(key, "source", root, "approve-candidate-delivery", "Original material", emptyList(), at, "259200", occurrenceId = "1")
        val state = WorkflowResumeState(pendingApprovalIds = mapOf("approve-candidate-delivery" to key), approvalOccurrences = mapOf("approve-candidate-delivery" to 1L))
        val checkpoint = WorkflowHostCheckpoint(WorkflowHostScope(root, root), "source", state, true, listOf(request))
        val announcement = originalStore.save(checkpoint, 1)
        val file = dir.resolve(announcement.string("relativePath")); val body = runnerJson.parseToJsonElement(Files.readString(file)).jsonObject
        val old = JsonObject(body + ("state" to JsonObject(body.obj("state") - "pendingApprovalRequestedAt")))
        val oldHash = originalStore.writePrivate(announcement.string("relativePath"), canonical(old).toByteArray())
        val newer = JsonObject(binding + mapOf("resumeEpoch" to JsonPrimitive(binding.long("resumeEpoch") + 1), "attemptId" to JsonPrimitive("new-attempt"), "runnerInstanceId" to JsonPrimitive("new-instance")))
        val restoring = RunnerCheckpointStore(dir, newer, secrets)
        val restored = restoring.restore(JsonObject(announcement + ("checkpointHash" to JsonPrimitive(oldHash))), binding)
        assertEquals(key, restored.pendingApprovalIds["approve-candidate-delivery"]); assertEquals(at, restored.pendingApprovalRequestedAt["approve-candidate-delivery"])
        val missing = originalStore.save(checkpoint.copy(pendingApprovals = emptyList()), 1)
        assertFails { restoring.restore(missing, binding) }
        val conflicting = originalStore.save(checkpoint.copy(state = state.copy(pendingApprovalRequestedAt = mapOf("approve-candidate-delivery" to at + 1))), 1)
        assertFails { restoring.restore(conflicting, binding) }
    }

    private fun interpreter(dir: Path, mode: String) = runBlocking<Unit> {
        fun code(name: String) = """
              - step: $name
                code:
                  language: bash
                  working_dir: $dir
                  script: |
                    mkdir -p '$dir/run'
                    printf 'once\n' >> '$dir/run/$name.txt'
        """.trimIndent().prependIndent("  ")
        val source = "name: original-source-approval\nagents: {}\nworkflow:\n" + code("produce-source") + "\n" + code("normal-intermediate") + "\n" + """
              - step: approve-candidate-delivery
                manual_approval:
                  enabled: true
                  timeout: 259200
                  approvers: [original-owner]
                  approval_message: Review the original candidate.
                code:
                  language: bash
                  working_dir: $dir
                  script: |
                    printf 'accepted\n' > '$dir/run/delivery-acceptance.txt'
        """.trimIndent().prependIndent("  ") + "\ntimeout:\n  total: 1s\n"
        Files.writeString(dir.resolve("root.yaml"), source)
        val hash = sha256(source.toByteArray()); val b = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root"); put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", hash) }) })
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), b)
        lateinit var host: RunnerWorkflowHost
        val gate = RunnerEnvironmentGate(b, setOf("subprocess"))
        fun environment() = RunnerProtocolTest.environment(b, Instant.now()).let { e -> JsonObject(e + mapOf("environmentCheckId" to JsonPrimitive(java.util.UUID.randomUUID().toString()), "egress" to JsonArray(e.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("subprocess"))) }))) }
        gate.install(environment())
        IpcHarness(b) { frame -> when (frame.string("type")) {
            "ack" -> host.acknowledge(frame.obj("payload"))
            "environmentRefreshed" -> gate.install(frame.obj("payload"), frame.string("replyTo"))
        } }.use { h ->
            val identity = WorkflowHostBinding(b.string("ownerId"), b.string("actorUserId"), "billing", b.string("executionId"), b.string("runnerId"), b.string("runnerInstanceId"), b.long("resumeEpoch"), b.string("attemptId"), hash, b.string("runtimeHash"))
            val secrets = RunnerSecrets(); val store = RunnerCheckpointStore(dir, b, secrets)
            host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), store, secrets)
            val requests = Channel<JsonObject>(2); val checkpoints = mutableListOf<JsonObject>(); val completedSteps = mutableSetOf<String>()
            val pump = launch { while (isActive) {
                val raw = withContext(Dispatchers.IO) { RunnerIpc.readLine(h.supervisorInput) } ?: break
                val frame = RunnerProtocol().parse(raw.toString(Charsets.UTF_8))
                when (frame.string("type")) {
                    "approval" -> requests.send(frame)
                    "checkpoint" -> checkpoints += frame
                    "event" -> {
                        val e = frame.obj("payload"); val detail = e.obj("payload")
                        if (detail["kind"]?.jsonPrimitive?.content == "step_completed") completedSteps += detail.string("stepName")
                        h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", e.long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) }))
                    }
                    else -> error("Unexpected fixture frame")
                }
            } }
            val originalKey = identity.executionId + ":approve-candidate-delivery:1"
            val requestedAt = System.currentTimeMillis() - 259201000L
            val resumed = if (mode == "expired-resume") WorkflowResumeState(preservedStepResults = listOf("produce-source", "normal-intermediate").associateWith {
                StepExecutionResult(it, "code", true, requestedAt - 1000, requestedAt - 500, 500)
            }, pendingApprovalIds = mapOf("approve-candidate-delivery" to originalKey), approvalOccurrences = mapOf("approve-candidate-delivery" to 1L), pendingApprovalRequestedAt = mapOf("approve-candidate-delivery" to requestedAt)) else null
            val task = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(bundle.rootDefinition, emptyMap(), identity.executionId, resumed) }
            try {
                if (mode == "expired-resume") {
                    val finished = withTimeout(5000) { task.await() }
                    assertFalse(finished.success); assertTrue(finished.error.orEmpty().contains("Manual approval timed out"), finished.error)
                    assertTrue(requests.tryReceive().isFailure)
                    val pending = Files.list(dir.resolve(".runner-state")).use { paths -> paths.filter { it.fileName.toString().startsWith("checkpoint-") }.toList() }
                        .map { runnerJson.parseToJsonElement(Files.readString(it)).jsonObject }.first { it.array("pendingApprovals").isNotEmpty() }
                    assertEquals(originalKey, pending.array("pendingApprovals").single().jsonObject.string("approvalId"))
                    assertEquals(requestedAt, pending.array("pendingApprovals").single().jsonObject.long("requestedAt"))
                    assertFalse(Files.exists(dir.resolve("run/produce-source.txt"))); assertFalse(Files.exists(dir.resolve("run/delivery-acceptance.txt")))
                } else {
                    val request = withTimeout(5000) { requests.receive() }
                    assertEquals(originalKey, request.obj("payload").string("idempotencyKey"))
                    assertEquals("1", request.obj("payload").string("occurrenceId"))
                    assertTrue(request.obj("payload").array("childExecutionPath").isEmpty())
                    assertTrue(completedSteps.containsAll(listOf("produce-source", "normal-intermediate")), "Earlier producer and normal intermediate step closed before PRE approval wait")
                    assertEquals("once\n", Files.readString(dir.resolve("run/produce-source.txt"))); assertEquals("once\n", Files.readString(dir.resolve("run/normal-intermediate.txt")))
                    val pending = checkpoints.map { it.obj("payload").string("relativePath") }.map { runnerJson.parseToJsonElement(Files.readString(dir.resolve(it))).jsonObject }.first { it.array("pendingApprovals").isNotEmpty() }
                    val original = pending.array("pendingApprovals").single().jsonObject
                    assertEquals("259200", original.string("timeout")); assertEquals(originalKey, original.string("approvalId"))
                    assertEquals(original.long("requestedAt"), pending.obj("state").obj("pendingApprovalRequestedAt").long("approve-candidate-delivery"))
                    delay(1200) // Controlled P proof remains unavailable; E receives no private proof frame or decision.
                    assertFalse(task.isCompleted); assertFalse(Files.exists(dir.resolve("run/delivery-acceptance.txt")))
                    if (mode == "cancel") {
                        task.cancelAndJoin(); h.ipc.accept(decision(h, request)); delay(50)
                        assertFalse(Files.exists(dir.resolve("run/delivery-acceptance.txt")))
                        Files.list(dir.resolve(".runner-state/approval-requests")).use { files ->
                            val saved = runnerJson.parseToJsonElement(Files.readString(files.toList().single())).jsonObject
                            assertEquals(request, saved.obj("frame")); assertNull(saved["decision"])
                        }
                    } else {
                        h.ipc.accept(h.reply(request, "environmentRefreshed", environment()))
                        h.ipc.accept(decision(h, request, mode == "approve"))
                        val result = withTimeout(5000) { task.await() }; assertEquals(mode == "approve", result.success, result.error)
                        assertEquals(mode == "approve", Files.exists(dir.resolve("run/delivery-acceptance.txt")))
                    }
                }
            } finally { task.cancel(); host.close(); pump.cancel(); h.supervisorInput.close() }
        }
    }
}
