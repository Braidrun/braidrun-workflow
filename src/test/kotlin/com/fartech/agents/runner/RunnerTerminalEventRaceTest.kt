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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Controlled actual Host.emit -> private event fsync -> FIFO admission race, no probabilistic stress loop. */
class RunnerTerminalEventRaceTest {
    @Test fun `already admitted subscription recorder finishes durable FIFO event before business closure and result`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = "name: event-race\nagents: {}\nworkflow:\n  - step: code\n    code:\n      language: bash\n      script: 'true'\n"
        Files.writeString(dir.resolve("root.yaml"), source)
        val hash = sha256(source.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", hash) }) })
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), binding)
        IpcHarness(binding).use { h ->
            val gate = RunnerEnvironmentGate(binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(binding, Instant.now()))
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), hash, binding.string("runtimeHash"))
            val secrets = RunnerSecrets(); val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, NativeSubprocessExecutor(), RunnerCheckpointStore(dir, binding, secrets), secrets)
            val atEnqueue = CountDownLatch(1); val allowEnqueue = CountDownLatch(1); val closeStarted = CountDownLatch(1)
            val closingThread = AtomicReference<Thread>()
            host.beforeEventEnqueueForTest = { frame ->
                assertEquals(1, frame.obj("payload").long("eventSeq"))
                assertEquals(frame, runnerJson.parseToJsonElement(Files.readString(dir.resolve(".runner-state/events/1.json"))))
                atEnqueue.countDown(); check(allowEnqueue.await(5, TimeUnit.SECONDS))
            }
            val retainedRecorder = host::emit // same ordinary path handed to subscription-pool callbacks; no Host monitor lock.
            val append = async(Dispatchers.IO) { runCatching { retainedRecorder("step", buildJsonObject { put("kind", "subscription_rate_limited"); put("provider", "fixture") }) } }
            var closing: Deferred<Long>? = null
            try {
                assertTrue(withContext(Dispatchers.IO) { atEnqueue.await(2, TimeUnit.SECONDS) })
                closing = async(Dispatchers.IO) {
                    closingThread.set(Thread.currentThread()); closeStarted.countDown(); host.beginFinalization()
                }
                assertTrue(withContext(Dispatchers.IO) { closeStarted.await(2, TimeUnit.SECONDS) })
                withTimeout(2000) { while (closingThread.get()?.state != Thread.State.BLOCKED) delay(5) }
                assertFalse(h.ipc.businessFence.isClosed(), "Finalization cannot mark fence closed while admitted append is persisted but not yet queued")
                assertFalse(closing.isCompleted)
                val hostMonitorAvailable = async(Dispatchers.IO) { synchronized(host) { true } }
                assertTrue(withTimeout(1000) { hostMonitorAvailable.await() }, "Finalization must not hold Host monitor while waiting for fence admission")
                allowEnqueue.countDown()
                assertTrue(withTimeout(2000) { append.await() }.isSuccess)
                assertEquals(1, withTimeout(2000) { closing.await() })
                val original = withTimeout(2000) { h.next() }
                assertEquals(1, original.obj("payload").long("eventSeq")); assertEquals(1, host.finalEventSeq())
                gate.check() // Closure did not break the event journal or manufacture authority loss.
                assertFailsWith<RunnerAuthorityLost> { retainedRecorder("step", buildJsonObject { put("kind", "late_callback") }) }
                assertFalse(Files.exists(dir.resolve(".runner-state/events/2.json")))
                assertEquals(1, host.beginFinalization())
                val result = RunnerProtocolTest.frame(binding, "result", buildJsonObject {
                    put("binding", binding); put("finalEventSeq", host.finalEventSeq()); put("resultHash", "a".repeat(64)); put("artifactManifestHash", "b".repeat(64)); put("status", "COMPLETED"); put("terminationReason", JsonNull)
                    put("stopReceipt", buildJsonObject {
                        put("binding", binding); put("receiptId", "original-unconfirmed-stop"); put("recordedAt", Instant.now().toString()); put("reason", "JVM_EXIT_PENDING")
                        put("confirmed", false); put("engineStarted", true); put("registeredProcesses", JsonArray(emptyList())); put("unmanagedProcessDetected", false); put("journalHash", "c".repeat(64))
                    })
                })
                h.ipc.sendFrame(result); assertEquals(result, withTimeout(2000) { h.next() })
                assertEquals(original.obj("payload").long("eventSeq"), result.obj("payload").long("finalEventSeq"))
                h.ipc.sendFrame(original); assertEquals(original, withTimeout(2000) { h.next() })
                assertFalse(result.obj("payload").obj("stopReceipt").getValue("confirmed").jsonPrimitive.boolean)
            } finally {
                allowEnqueue.countDown(); withTimeoutOrNull(2000) { append.join(); closing?.join() }
                host.beforeEventEnqueueForTest = {}; host.close()
            }
        }
    }
    @Test fun `failed append invalidation runs outside closure and journal monitors and cannot seal a complete result sequence`() {
        val fence = RunnerBusinessFence(); var invalidated = false
        lateinit var journal: RunnerEventJournal
        journal = RunnerEventJournal(RunnerProtocolTest.binding(), RunnerSecrets(), { _, _ -> error("controlled fsync failure") }, { error("No enqueue after write failure") }, {
            assertFalse(Thread.holdsLock(fence)); assertFalse(Thread.holdsLock(journal)); invalidated = true
        }, fence)
        assertFailsWith<RunnerAuthorityLost> { journal.append("step", buildJsonObject { put("kind", "fixture") }) }
        assertTrue(invalidated); assertEquals(1, journal.lastSequence()) // Original failed intent remains; never roll back or reuse its ID/seq.
        fence.close(); assertFailsWith<RunnerAuthorityLost> { journal.seal() }
        assertFailsWith<RunnerAuthorityLost> { journal.append("step", buildJsonObject { put("kind", "replacement") }) }
        assertEquals(1, journal.lastSequence())
    }
}
