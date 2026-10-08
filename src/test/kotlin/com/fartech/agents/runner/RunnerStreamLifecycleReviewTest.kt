package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerStreamLifecycleReviewTest {
    private fun closed(request: JsonObject, id: String) = buildJsonObject {
        put("requestId", request.obj("payload").string("requestId")); put("registrationId", id); put("exitCode", 0); put("finalOutputSeq", 0)
        put("stopConfirmed", true); put("registeredProcesses", JsonArray(emptyList())); put("unmanagedProcessDetected", false)
        put("stdoutArtifactId", JsonNull); put("stderrArtifactId", JsonNull); put("reason", "EXITED")
    }
    private fun started(h: IpcHarness, request: JsonObject, id: String) = h.reply(request, "processStarted", buildJsonObject {
        put("requestId", request.obj("payload").string("requestId")); put("registrationId", id); put("pid", 42); put("processGroupId", 42); put("startIdentity", "birth")
    })
    @Test fun `fast confirmed exit tolerates no-input EOF and seventeen sequential processes release active capacity`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            val executor = RunnerProcesses(h.ipc, dir, gate, broker) { _, _ -> error("No capture expected") }
            try {
                repeat(17) { number ->
                    val result = async { executor.execute(SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile(), stdoutLineCallback = { })) }
                    val request = h.next(); val id = "registration-$number"
                    broker.closed(closed(request, id)) // Normal exit races ahead of the open continuation.
                    h.ipc.accept(started(h, request, id))
                    assertEquals(0, withTimeout(1000) { result.await() }.exitCode)
                    broker.closed(closed(request, id)) // Late identical close is a tombstone, not new capacity.
                }
            } finally { broker.close() }
        }
    }
    @Test fun `EOF gate probe overlapping confirmed exit atomically becomes a no-op`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            val armed = java.util.concurrent.atomic.AtomicBoolean()
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http"), nanoTime = {
                if (armed.get() && Thread.currentThread().name.startsWith("EOF-review")) {
                    entered.countDown(); check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                }
                System.nanoTime()
            })
            gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "EOF-review") }
            try {
                val opening = async { broker.open(WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId")), SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile())) }
                val request = h.next(); h.ipc.accept(started(h, request, "race")); val channel = opening.await()
                armed.set(true)
                val eof = worker.submit<Boolean> { channel.stdin.close(); true }
                assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
                broker.closed(closed(request, "race")); release.countDown()
                assertTrue(eof.get(1, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, channel.awaitExit())
            } finally { release.countDown(); worker.shutdownNow(); broker.close() }
        }
    }
    @Test fun `local close releases readers but accepts legal output during remote grace`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            try {
                val opening = async { broker.open(WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId")), SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile())) }
                val request = h.next(); h.ipc.accept(started(h, request, "grace")); val channel = opening.await()
                fun output(sequence: Long, stream: String, text: String): JsonObject {
                    val bytes = text.toByteArray()
                    return buildJsonObject { put("requestId", request.obj("payload").string("requestId")); put("registrationId", "grace"); put("outputSeq", sequence)
                        put("channel", stream); put("encoding", "base64"); put("dataBase64", java.util.Base64.getEncoder().encodeToString(bytes))
                        put("decodedBytes", bytes.size); put("dataHash", sha256(bytes)) }
                }
                // This unread prefix is discarded when the reader is released. It must not
                // leave an ACK hole that exhausts the remote window during shutdown.
                val pending = output(1, "stderr", "unread before close")
                broker.output(pending)
                val reader = async(Dispatchers.IO) { runCatching { channel.stdout.read() } }
                channel.close(); assertTrue(withTimeout(1000) { reader.await() }.isFailure)
                assertEquals(1L, h.next().obj("payload").long("ackOutputSeq"))
                assertEquals("processCancel", h.next().string("type"))
                val grace = output(2, "stdout", "late shutdown line")
                broker.output(grace); assertEquals(2L, h.next().obj("payload").long("ackOutputSeq"))
                broker.closed(JsonObject(closed(request, "grace") + ("finalOutputSeq" to JsonPrimitive(2))))
                gate.check(); assertFalse(h.failed)
                broker.output(pending); assertEquals(2L, h.next().obj("payload").long("ackOutputSeq"))
                broker.output(grace); assertEquals(2L, h.next().obj("payload").long("ackOutputSeq"))
            } finally { broker.close() }
        }
    }
    @Test fun `IPC EOF cancels the streaming root without waiting for blocked IO consumers`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            val executor = RunnerProcesses(h.ipc, dir, gate, broker) { _, _ -> error("No capture expected") }
            try {
                val root = async { runCatching { executor.execute(SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile(), stdoutLineCallback = { })) } }
                val request = h.next(); h.ipc.accept(started(h, request, "streaming"))
                val input = h.next(); assertEquals("processInput", input.string("type")); assertEquals(JsonPrimitive(true), input.obj("payload")["eof"])
                h.ipc.accept(h.reply(input, "processInputAck", buildJsonObject {
                    put("requestId", request.obj("payload").string("requestId")); put("registrationId", "streaming"); put("inputSeq", 1)
                    put("writtenBytes", 0); put("eof", true); put("state", "WRITTEN")
                }))
                h.ipc.close(); gate.invalidate("IPC_EOF") // HostMain's stop callback propagates this same latch to channels.
                assertTrue(withTimeout(1000) { root.await() }.isFailure)
            } finally { broker.close() }
        }
    }
    @Test fun `dead IPC local close unblocks pending stream readers within a bound`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            try {
                val opening = async { broker.open(WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId")), SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile())) }
                val request = h.next(); h.ipc.accept(started(h, request, "blocked")); val channel = opening.await()
                val stdout = async(Dispatchers.IO) { runCatching { channel.stdout.read() } }
                val stderr = async(Dispatchers.IO) { runCatching { channel.stderr.read() } }
                h.ipc.close() // Remote cancel can no longer be delivered.
                channel.close()
                assertTrue(withTimeout(1000) { stdout.await() }.isFailure)
                assertTrue(withTimeout(1000) { stderr.await() }.isFailure)
                assertFailsWith<com.fartech.agents.runner.RunnerAuthorityLost> { channel.awaitExit() }
            } finally { broker.close() }
        }
    }
}
