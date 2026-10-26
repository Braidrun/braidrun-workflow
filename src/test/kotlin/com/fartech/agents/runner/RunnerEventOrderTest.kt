package com.fartech.agents.runner

import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.workflow.WorkflowHostBinding
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RunnerEventOrderTest {
    @Test fun `artifact intent fsync and parallel child event cannot enqueue n plus one before n`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        val queued = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val persisted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val journal = RunnerEventJournal(RunnerProtocolTest.binding(), RunnerSecrets(), { path, _ -> persisted += path }, { queued += it }, { fail("Unexpected failure") })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val artifact = workers.submit<JsonObject> { journal.append("artifact", buildJsonObject { put("producer", "first") }, beforePersist = {
                // This is the old b96 gap: intent.event fsync after reserving n.
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val child = workers.submit<JsonObject> { secondStarted.countDown(); journal.append("step", buildJsonObject { put("producer", "parallel-child") }) }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFailsWith<java.util.concurrent.TimeoutException> { child.get(150, TimeUnit.MILLISECONDS) }
            assertFalse(child.isDone); assertTrue(queued.isEmpty())
            release.countDown(); artifact.get(2, TimeUnit.SECONDS); child.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(1L, 2L), queued.map { it.obj("payload").long("eventSeq") })
            assertEquals(listOf("artifact", "step"), queued.map { it.obj("payload").string("type") })
            assertEquals(listOf(".runner-state/events/1.json", ".runner-state/events/2.json"), persisted)
        } finally { release.countDown(); workers.shutdownNow() }
    }
    @Test fun `ACK waiter is armed before enqueue while another producer remains free to append`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val journal = RunnerEventJournal(h.binding, RunnerSecrets(), { _, _ -> }, { h.ipc.sendFrame(it) }, {}, h.ipc.businessFence)
            val artifact = async { h.ipc.awaitReply("ack", frameId = "original-artifact-frame") {
                journal.append("artifact", buildJsonObject { put("source", "fixture") }, "original-artifact-frame")
            } }
            val first = h.next(); assertFalse(artifact.isCompleted)
            withTimeout(1000) { withContext(Dispatchers.Default) { journal.append("step", buildJsonObject { put("producer", "child") }) } }
            val second = h.next(); assertEquals(2L, second.obj("payload").long("eventSeq")); assertFalse(artifact.isCompleted)
            h.ipc.accept(h.reply(first, "ack", buildJsonObject { put("ackSeq", 1); put("revision", 1); put("serverTime", java.time.Instant.now().toString()) }))
            assertEquals(1L, artifact.await().payload.long("ackSeq"))
        }
    }
    @Test fun `failed durable append stops future sequences instead of sending a hole`() {
        var sent = 0; var failed = false
        val journal = RunnerEventJournal(RunnerProtocolTest.binding(), RunnerSecrets(), { _, _ -> error("fsync failure") }, { sent++ }, { failed = true })
        assertFailsWith<RunnerAuthorityLost> { journal.append("artifact", JsonObject(emptyMap())) }
        assertFailsWith<RunnerAuthorityLost> { journal.append("step", JsonObject(emptyMap())) }
        assertTrue(failed); assertEquals(0, sent); assertEquals(1, journal.lastSequence())
    }

    /**
     * Four agent-like producers emit through the real Host while S stops reading stdout for longer than one outbound
     * offer used to wait. No producer may kill the run, nothing may be lost or reordered, and lease renewals that S
     * keeps sending meanwhile are still installed: the IPC reader never waits behind a producer's backpressure.
     */
    @Test fun `four parallel event producers behind a stalled reader neither kill the run nor starve lease renewal`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = "name: event-load\nagents: {}\nworkflow:\n  - step: code\n    code:\n      language: bash\n      script: 'true'\n"
        Files.writeString(dir.resolve("root.yaml"), source)
        val hash = sha256(source.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", hash) }) })
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), binding)
        val gate = RunnerEnvironmentGate(binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(binding, Instant.now()))
        fun lease() = Instant.now().let { now -> buildJsonObject {
            put("leaseId", "lease"); put("serverTime", now.toString()); put("leaseExpiresAt", now.plusMillis(1500).toString())
            put("renewAfterSeconds", 15); put("leaseDurationSeconds", 60)
        } }
        gate.installLease(lease())
        val supervisorInput = PipedInputStream(16 * 1024); val engineOutput = PipedOutputStream(supervisorInput)
        val engineInput = PipedInputStream(64 * 1024); val supervisorOutput = PipedOutputStream(engineInput)
        fun write(frame: JsonObject) = synchronized(supervisorOutput) { supervisorOutput.write((frame.toString() + "\n").toByteArray()); supervisorOutput.flush() }
        lateinit var host: RunnerWorkflowHost
        val renewalFailures = java.util.concurrent.atomic.AtomicInteger()
        val ipc = RunnerIpc(binding, engineInput, engineOutput, onControl = { frame -> when (frame.string("type")) {
            "ack" -> host.acknowledge(frame.obj("payload"))
            "leaseRenewed" -> runCatching { gate.installLease(frame.obj("payload").obj("lease")) }.onFailure { renewalFailures.incrementAndGet() }
        } }, onFailure = {})
        ipc.start()
        val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), hash, binding.string("runtimeHash"))
        val secrets = RunnerSecrets()
        host = RunnerWorkflowHost(ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), RunnerCheckpointStore(dir, binding, secrets), secrets)
        val producers = 4; val perProducer = 150; val total = producers * perProducer
        val leaseLapses = java.util.concurrent.atomic.AtomicInteger()
        val renewer = launch(Dispatchers.IO) { while (isActive) { write(RunnerProtocolTest.frame(binding, "leaseRenewed", buildJsonObject { put("lease", lease()) })); delay(200) } }
        val sampler = launch(Dispatchers.IO) { while (isActive) { try { gate.check() } catch (_: RunnerAuthorityLost) { leaseLapses.incrementAndGet() }; delay(50) } }
        val workers = Executors.newFixedThreadPool(producers)
        try {
            val emitted = (0 until producers).map { producer -> workers.submit<Int> {
                var sent = 0
                repeat(perProducer) { index ->
                    host.emit("step", buildJsonObject { put("kind", "agent_event"); put("stepName", "agent-$producer"); put("index", index); put("detail", "x".repeat(4096)) })
                    sent++
                }
                sent
            } }
            delay(2500) // S reads nothing: the pipe and the outbound queue fill, producers wait
            val received = mutableListOf<JsonObject>()
            withTimeout(30_000) {
                while (received.size < total) {
                    val frame = runInterruptible(Dispatchers.IO) { RunnerProtocol().parse(RunnerIpc.readLine(supervisorInput)!!.toString(Charsets.UTF_8)) }
                    if (frame.string("type") != "event") continue
                    received += frame
                    write(RunnerProtocolTest.frame(binding, "ack", buildJsonObject {
                        put("ackSeq", frame.obj("payload").long("eventSeq")); put("revision", received.size); put("serverTime", Instant.now().toString())
                    }, frame.string("frameId")))
                }
            }
            assertEquals(List(producers) { perProducer }, emitted.map { it.get(5, TimeUnit.SECONDS) })
            assertEquals((1L..total).toList(), received.map { it.obj("payload").long("eventSeq") })
            withTimeout(5000) { while (host.finalEventSeq() != total.toLong() || ipc.retainedEvidenceCount() > 0) delay(10) }
            assertEquals(0, renewalFailures.get(), "every renewal S sent during the stall was installed in time")
            assertEquals(0, leaseLapses.get(), "the lease never lapsed under parallel event load")
            gate.check()
        } finally {
            renewer.cancel(); sampler.cancel(); workers.shutdownNow()
            host.close(); ipc.close(); supervisorOutput.close(); supervisorInput.close()
        }
    }

    /**
     * Savepoints requested from many concurrent steps while other steps keep emitting: each announcement carries the
     * event sequence of the moment its state was captured, and announcements arrive in capture order.
     */
    @Test fun `concurrent savepoints are announced in capture order with the event sequence of their state`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = "name: savepoints\nagents: {}\nworkflow:\n  - step: code\n    code:\n      language: bash\n      script: 'true'\n"
        Files.writeString(dir.resolve("root.yaml"), source)
        val hash = sha256(source.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", hash) }) })
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), binding)
        lateinit var host: RunnerWorkflowHost
        IpcHarness(binding) { frame -> if (frame.string("type") == "ack") host.acknowledge(frame.obj("payload")) }.use { h ->
            val gate = RunnerEnvironmentGate(binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(binding, Instant.now()))
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), hash, binding.string("runtimeHash"))
            val secrets = RunnerSecrets()
            host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), RunnerCheckpointStore(dir, binding, secrets), secrets)
            val root = identity.executionId
            val savepoints = 24
            val announced = mutableListOf<JsonObject>()
            val reader = launch(Dispatchers.IO) {
                while (announced.size < savepoints) {
                    val frame = h.next()
                    if (frame.string("type") == "checkpoint") announced += frame.obj("payload")
                    else if (frame.string("type") == "event") h.ipc.accept(h.reply(frame, "ack", buildJsonObject {
                        put("ackSeq", frame.obj("payload").long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) }))
                }
            }
            try {
                val saving = java.util.concurrent.atomic.AtomicBoolean(true)
                coroutineScope {
                    // Steps keep emitting for as long as savepoints are being taken.
                    repeat(4) { producer -> launch(Dispatchers.IO) { var index = 0; while (saving.get()) {
                        host.monitor(com.fartech.agents.workflow.WorkflowMonitorUpdate(root, "agent_event", "step-$producer",
                            com.fartech.agents.workflow.AgentEvent(type = "progress", category = "agent", summary = "tick ${index++}")))
                        delay(1)
                    } } }
                    coroutineScope {
                        repeat(savepoints) { index -> launch(Dispatchers.IO) {
                            delay((index % 6) * 3L)
                            host.checkpointOf {
                                val captured = host.finalEventSeq()
                                Thread.sleep(3) // copying a large context takes time
                                com.fartech.agents.workflow.WorkflowHostCheckpoint(com.fartech.agents.workflow.WorkflowHostScope(root, root), "savepoints",
                                    com.fartech.agents.workflow.WorkflowResumeState(variables = mapOf("captured" to captured, "index" to index)), safeToResume = true)
                            }
                        } }
                    }
                    saving.set(false)
                }
                withTimeout(10_000) { reader.join() }
                val sequences = announced.map { it.long("eventSeq") }
                assertEquals(sequences.sorted(), sequences, "savepoints are announced in capture order")
                announced.forEach { announcement ->
                    val state = runnerJson.parseToJsonElement(Files.readString(dir.resolve(announcement.string("relativePath")))).jsonObject.obj("state")
                    assertEquals(announcement.long("eventSeq"), state.obj("variables").long("captured"), "the announced sequence is the one the state was captured at")
                }
            } finally { reader.cancel(); host.close() }
        }
    }
}
