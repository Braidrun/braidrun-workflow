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

class RunnerArtifactSetsTest {
    @Test fun `exact P named definitions accept and reject all ten set fixtures`() {
        val fixtures = runnerJson.parseToJsonElement(javaClass.getResourceAsStream("/runner-workflow-v1/artifact-set-fixtures.json")!!.readBytes().toString(Charsets.UTF_8)).jsonArray
        assertEquals(10, fixtures.size)
        fixtures.forEach { value ->
            val f = value.jsonObject
            val result = runCatching { RunnerArtifactSetProtocol.validate(f.getValue("value"), f.string("definition")) }
            assertEquals(f.getValue("valid").jsonPrimitive.boolean, result.isSuccess, f.string("name"))
        }
    }

    @Test fun `all 128 declarations wait for two original set receipts and duplicate requests never repeat business`(@TempDir dir: Path) = executeCode(dir, 128, false, false)
    @Test fun `native root and actual child preserve distinct original code occurrences`(@TempDir dir: Path) = executeCode(dir, 1, true, false)
    @Test fun `nonzero natural code exit retains diagnostics before original failed closure`(@TempDir dir: Path) = executeCode(dir, 1, false, true)

    @Test fun `set capability dependencies remain explicit and unimplemented lifecycle capabilities are withheld`() {
        val set = RunnerArtifactSetProtocol.CAPABILITY; val collection = RunnerCollectionProtocol.CAPABILITY
        assertTrue(set in RunnerHostSession.CAPABILITIES)
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN, RunnerCodeControlNegotiation.select(setOf(set, collection), RunnerHostSession.CAPABILITIES))
        for (required in listOf(setOf(set), setOf(RunnerArtifactSetProtocol.GATES, set, collection), setOf(RunnerArtifactSetProtocol.SOURCE))) {
            assertEquals(RunnerNegotiationReason.RUNNER_SET_DEPENDENCY_REQUIRED, assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES)
            }.reason)
        }
        assertFalse(RunnerArtifactSetProtocol.GATES in RunnerHostSession.CAPABILITIES)
        assertFalse(RunnerArtifactSetProtocol.SOURCE in RunnerHostSession.CAPABILITIES)
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(setOf(set, collection), RunnerHostSession.CAPABILITIES - set) }
        assertTrue(RunnerArtifactSetProtocol.available())
        assertFails { RunnerArtifactSetProtocol.validate(buildJsonObject {}, "boundaryPayload") }
        assertFails { RunnerArtifactSetProtocol.validate(buildJsonObject {}, "ipcFrame") }
    }

    @Test fun `changed all-new set identities cannot replace original boundary intent`(@TempDir dir: Path) = executeCode(dir, 1, false, false, "changed-identity")
    @Test fun `wrong original index event refuses completion`(@TempDir dir: Path) = executeCode(dir, 1, false, false, "wrong-index")
    @Test fun `set request before separate ready ACK refuses`(@TempDir dir: Path) = executeCode(dir, 1, false, false, "unacked-ready")
    @Test fun `natural code wait keeps original code timeout`(@TempDir dir: Path) = executeCode(dir, 1, false, false, "code-timeout")

    @Test fun `set IPC requires negotiation direction and exact binding`() {
        val fixtures = runnerJson.parseToJsonElement(javaClass.getResourceAsStream("/runner-workflow-v1/artifact-set-fixtures.json")!!.readBytes().toString(Charsets.UTF_8)).jsonArray
        val original = fixtures.single { it.jsonObject.string("name") == "collect" }.jsonObject.obj("value")
        IpcHarness(original.obj("binding")).use { h ->
            assertFails { h.ipc.accept(original) }
            h.ipc.enableArtifactCollection(); h.ipc.enableArtifactSets()
            assertFails { h.ipc.sendFrame(original) }
            val wrong = JsonObject(original + ("binding" to JsonObject(original.obj("binding") + ("resumeEpoch" to JsonPrimitive(999)))))
            assertFails { h.ipc.accept(wrong) }
            h.ipc.accept(original); assertEquals(listOf(original), h.controls)
            val complete = fixtures.single { it.jsonObject.string("name") == "complete" }.jsonObject.obj("value")
            assertFails { h.ipc.accept(complete) }
        }
    }

    @Test fun `actual supervised processExited precedes ready and logical completion`(@TempDir dir: Path) = executeCode(dir, 1, false, false, "supervised")
    @Test fun `failed supervised native process remains failed after diagnostics set receipt`(@TempDir dir: Path) = executeCode(dir, 1, false, true, "supervised")
    @Test fun `first upload window is metadata and cannot replace live original code budget`(@TempDir dir: Path) = executeCode(dir, 1, false, false, "elapsed-upload-window")
    @Test fun `unrelated cumulative ACK cannot satisfy separate original ready ACK`(@TempDir dir: Path) = executeCode(dir, 1, false, false, "unrelated-ready-ack")

    private fun executeCode(dir: Path, count: Int, child: Boolean, fail: Boolean, mode: String = "normal") = runBlocking<Unit> {
        fun definition(id: String) = """
            name: set-$id
            agents: {}
            workflow:
              - step: write
                code:
                  language: bash
                  timeout: ${if (mode == "code-timeout") 1 else 15}
                  working_dir: $dir
                  script: |
                    mkdir -p '$dir/run'
                    printf 'once\n' >> '$dir/run/$id.txt'
                    exit ${if (fail) 4 else 0}
        """.trimIndent() + if (id == "root" && child) "\n  - step: nested\n    sub_workflow:\n      workflow_id: child\n" else "\n"
        val definitions = if (child) mapOf("root" to definition("root"), "child" to definition("child")) else mapOf("root" to definition("root"))
        val policyJson = buildJsonObject {
            put("schemaVersion", 1); put("rootWorkflowId", "root")
            put("declarations", JsonArray(definitions.keys.flatMap { id -> (0 until count).map { n -> buildJsonObject {
                put("declarationId", "$id-$n")
                put("producer", buildJsonObject { put("workflowId", id); put("stepName", "write"); put("childSteps", JsonArray(if (id == "child") listOf(JsonPrimitive("nested")) else emptyList())) })
                put("selector", buildJsonObject { put("kind", "FILE"); put("path", "run/$id-$n.txt") })
                put("presence", "OPTIONAL"); put("purpose", if (fail) "DIAGNOSTIC" else "GATE_MATERIAL")
                put("maximumFiles", 1); put("maximumFileBytes", 1024); put("maximumTotalBytes", 1024)
            } } }))
        }
        val files = definitions.mapKeys { it.key + ".yaml" }.mapValues { "definition" to it.value } + (RunnerArtifactSetPolicy.PATH to ("runtime" to policyJson.toString()))
        files.forEach { (name, value) -> Files.createDirectories(dir.resolve(name).parent); Files.writeString(dir.resolve(name), value.second) }
        val hash = sha256(definitions.getValue("root").toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(files.toSortedMap().map { (path, v) -> buildJsonObject { put("path", path); put("role", v.first); put("size", v.second.toByteArray().size); put("sha256", sha256(v.second.toByteArray())) } }))
            put("definitions", JsonArray(definitions.toSortedMap().map { (id, source) -> buildJsonObject { put("workflowId", id); put("path", "$id.yaml"); put("definitionHash", sha256(source.toByteArray())) } }))
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), binding)
        val policy = RunnerArtifactSetPolicy(bundle)
        var target: RunnerWorkflowHost? = null
        var processes: RunnerProcesses? = null
        IpcHarness(binding) { frame ->
            if (frame.string("type") == "processExited") requireNotNull(processes).exited(frame.obj("payload"))
            else {
                if (frame.string("type") == "ack") target!!.acknowledge(frame.obj("payload"))
                target!!.setControl(frame)
            }
        }.use { h ->
            h.ipc.enableArtifactCollection(); h.ipc.enableArtifactSets()
            val gate = RunnerEnvironmentGate(binding, setOf("subprocess")); val env = RunnerProtocolTest.environment(binding, Instant.now())
            gate.install(JsonObject(env + ("egress" to JsonArray(env.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("subprocess"))) }))))
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), hash, binding.string("runtimeHash"))
            val secrets = RunnerSecrets()
            processes = RunnerProcesses(h.ipc, dir, gate) { _, _ -> error("No truncated captures in this fixture") }
            val executor = if (mode == "supervised") requireNotNull(processes) else NativeSubprocessExecutor()
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, executor, RunnerCheckpointStore(dir, binding, secrets), secrets, validateDirectNetwork = {}, setPolicy = policy)
            target = host
            val ready = Channel<JsonObject>(4); val commits = Channel<JsonObject>(8); val completes = Channel<JsonObject>(8)
            val starts = mutableSetOf<String>(); val closed = mutableSetOf<String>(); var reaped = 0
            fun ack(frame: JsonObject) = h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", frame.obj("payload").long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) }))
            val task = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(bundle.rootDefinition) }
            val pump = launch {
                while (isActive) {
                    val raw = withContext(Dispatchers.IO) { RunnerIpc.readLine(h.supervisorInput) } ?: break
                    val f = runnerJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
                    if (f.string("type") == "processLaunch") {
                        val request = f.obj("payload")
                        val process = ProcessBuilder(request.array("argv").map { it.jsonPrimitive.content })
                            .directory(dir.resolve(request.string("relativeCwd")).toFile()).apply {
                                environment().putAll(request.obj("environment").mapValues { it.value.jsonPrimitive.content })
                            }.start()
                        h.ipc.accept(h.reply(f, "processStarted", buildJsonObject {
                            put("requestId", request.string("requestId")); put("registrationId", "fixture-process-${process.pid()}")
                            put("pid", process.pid()); put("processGroupId", process.pid()); put("startIdentity", "test-observed-${process.pid()}")
                        }))
                        val stdout = withContext(Dispatchers.IO) { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
                        val stderr = withContext(Dispatchers.IO) { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
                        val exit = withContext(Dispatchers.IO) { process.waitFor() }; reaped++
                        assertFalse(process.isAlive)
                        h.ipc.accept(RunnerProtocolTest.frame(binding, "processExited", buildJsonObject {
                            put("registrationId", "fixture-process-${process.pid()}"); put("exitCode", exit); put("stdout", stdout); put("stderr", stderr)
                            put("timedOut", false); put("captureTruncated", false); put("stdoutArtifactId", JsonNull); put("stderrArtifactId", JsonNull)
                        }))
                        continue
                    }
                    if (f.string("type") == "artifactSetCollectComplete") { RunnerArtifactSetProtocol.validate(f, "completeFrame"); completes.send(f); continue }
                    RunnerProtocol().validate(f, "ipcFrame")
                    if (f.string("type") != "event") continue
                    val e = f.obj("payload"); val b = e.obj("payload")
                    val eventType = b["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                    if (eventType == "code_step_started") starts += e.string("eventId")
                    if (eventType == "code_step_completed") closed += b.string("executionId")
                    when (b["kind"]?.jsonPrimitive?.content) {
                        "artifact_set_ready" -> {
                            if (mode == "supervised") assertEquals(1, reaped, "Physical process exit has been delivered before ready")
                            if (mode !in setOf("unacked-ready", "unrelated-ready-ack")) ack(f)
                            if (mode == "unrelated-ready-ack") h.ipc.accept(RunnerProtocolTest.frame(binding, "ack", buildJsonObject {
                                put("ackSeq", e.long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString())
                            }, "unrelated-frame"))
                            ready.send(f)
                        }
                        "artifact_set_committed" -> commits.send(f) // deliberately hold both original proofs
                        else -> ack(f)
                    }
                }
            }
            try {
                val allReady = mutableListOf<JsonObject>()
                for (id in definitions.keys) {
                    val partitions = (count + 63) / 64
                    val frames = (0 until partitions).map { withTimeout(10000) { ready.receive() } }; allReady += frames
                    assertEquals(count, frames.sumOf { it.obj("payload").obj("payload").array("declarationIds").size })
                    val requests = frames.mapIndexed { n, f ->
                        val b = f.obj("payload").obj("payload"); RunnerArtifactSetProtocol.validate(b, "boundaryPayload")
                        assertTrue(b.obj("producer").string("producerStartEventId") in starts)
                        val actualExecution = binding.string("executionId") + if (id == "child") "__nested__call_1" else ""
                        assertFalse(actualExecution in closed, "Logical code closure follows every set Complete")
                        assertEquals(id, b.obj("producer").string("workflowId")); assertEquals(if (fail) "FAILED" else "SUCCESS", b.string("outcome"))
                        assertEquals(if (id == "root") 0 else 2, b.obj("producer").array("childExecutionPath").size)
                        assertTrue(Files.readString(dir.resolve("run/$id.txt")) == "once\n")
                        RunnerProtocolTest.frame(binding, "artifactSetCollectRequest", buildJsonObject {
                            put("setId", "$id-set-$n"); put("ticketId", "$id-ticket-$n"); put("snapshotSha256", "a".repeat(64)); put("policySha256", policy.hash)
                            put("producer", b.obj("producer")); put("boundaryEventId", f.obj("payload").string("eventId")); put("expiresAt", Instant.now().plusSeconds(if (mode == "elapsed-upload-window") -1 else 50).toString())
                        })
                    }
                    if (mode == "code-timeout") {
                        val result = withTimeout(3000) { task.await() }; assertFalse(result.success)
                        assertTrue(commits.tryReceive().isFailure); assertEquals("once\n", Files.readString(dir.resolve("run/root.txt")))
                        return@runBlocking
                    }
                    for ((n, request) in requests.withIndex()) {
                        if (mode in setOf("unacked-ready", "unrelated-ready-ack")) {
                            h.ipc.accept(request); withTimeout(3000) { while (runCatching { gate.check() }.isSuccess) delay(10) }
                            assertNotEquals(true, runCatching { withTimeout(3000) { task.await() } }.getOrNull()?.success); assertTrue(commits.tryReceive().isFailure)
                            return@runBlocking
                        }
                        h.ipc.accept(request); val commit = withTimeout(3000) { commits.receive() }
                        h.ipc.accept(request); assertEquals(commit, withTimeout(3000) { commits.receive() })
                        if (mode == "changed-identity") {
                            val seq = host.finalEventSeq()
                            val changed = JsonObject(request + mapOf("frameId" to JsonPrimitive("new-frame"), "payload" to JsonObject(request.obj("payload") + mapOf("setId" to JsonPrimitive("new-set"), "ticketId" to JsonPrimitive("new-ticket")))))
                            h.ipc.accept(changed); withTimeout(3000) { while (runCatching { gate.check() }.isSuccess) delay(10) }
                            assertNotEquals(true, runCatching { withTimeout(3000) { task.await() } }.getOrNull()?.success)
                            assertTrue(commits.tryReceive().isFailure); assertTrue(completes.tryReceive().isFailure)
                            assertTrue(host.finalEventSeq() >= seq); assertEquals("once\n", Files.readString(dir.resolve("run/root.txt")))
                            return@runBlocking
                        }
                        val event = commit.obj("payload"); val b = event.obj("payload")
                        assertEquals(request.string("frameId"), commit.string("replyTo"))
                        val index = h.reply(commit, "artifactSetIndexAck", buildJsonObject {
                            put("setId", b.string("setId")); put("snapshotSha256", b.string("snapshotSha256")); put("eventId", event.string("eventId")); put("eventSeq", event.long("eventSeq")); put("setIndexHash", "c".repeat(64)); put("revision", 1)
                        })
                        if (mode == "wrong-index") {
                            val wrong = JsonObject(index + ("payload" to JsonObject(index.obj("payload") + ("eventId" to JsonPrimitive("wrong-original-event")))))
                            h.ipc.accept(wrong); withTimeout(3000) { while (runCatching { gate.check() }.isSuccess) delay(10) }
                            assertNotEquals(true, runCatching { withTimeout(3000) { task.await() } }.getOrNull()?.success); assertTrue(completes.tryReceive().isFailure)
                            return@runBlocking
                        }
                        h.ipc.accept(index); delay(60)
                        assertTrue(completes.tryReceive().isFailure, "Index proof alone cannot complete")
                        assertFalse(task.isCompleted, "Producer cannot close before every partition")
                        ack(commit)
                        val complete = withTimeout(3000) { completes.receive() }
                        assertEquals(request.string("frameId"), complete.string("replyTo")); assertEquals("c".repeat(64), complete.obj("payload").string("setIndexHash"))
                        if (n < requests.lastIndex) {
                            delay(40); assertFalse(task.isCompleted)
                            h.ipc.accept(request); assertEquals(complete, withTimeout(3000) { completes.receive() })
                        }
                    }
                }
                val result = withTimeout(5000) { task.await() }
                assertEquals(!fail, result.success, result.error)
                definitions.keys.forEach { assertEquals("once\n", Files.readString(dir.resolve("run/$it.txt"))) }
                assertTrue(allReady.all { canonical(it).toByteArray().size <= 65536 })
            } finally { task.cancel(); pump.cancel(); host.close(); h.supervisorInput.close() }
        }
    }
}
