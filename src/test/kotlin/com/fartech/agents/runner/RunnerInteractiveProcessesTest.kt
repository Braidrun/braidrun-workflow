package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.*

class RunnerInteractiveProcessesTest {
    @Test fun `stream preserves bytes and ACK waits for contiguous consumption while stdin is written once`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            try {
                val opening = async { broker.open(WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId")),
                    SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile(), admissionKind = "MCP_STDIO")) }
                val request = h.next(); assertEquals("processOpen", request.string("type")); val requestId = request.obj("payload").string("requestId")
                h.ipc.accept(h.reply(request, "processStarted", buildJsonObject { put("requestId", requestId); put("registrationId", "registered")
                    put("pid", 42); put("processGroupId", 42); put("startIdentity", "fixture-birth") }))
                val channel = opening.await()
                fun chunk(sequence: Long, stream: String, bytes: ByteArray) = buildJsonObject {
                    put("requestId", requestId); put("registrationId", "registered"); put("outputSeq", sequence); put("channel", stream); put("encoding", "base64")
                    put("dataBase64", Base64.getEncoder().encodeToString(bytes)); put("decodedBytes", bytes.size); put("dataHash", sha256(bytes))
                }
                val first = byteArrayOf(0, 1, 127, -1); val second = "err".toByteArray()
                broker.output(chunk(1, "stdout", first)); broker.output(chunk(2, "stderr", second))
                assertContentEquals(second, channel.stderr.readNBytes(second.size))
                assertEquals(0L, h.next().obj("payload").long("ackOutputSeq"))
                assertContentEquals(first, channel.stdout.readNBytes(first.size))
                assertEquals(2L, h.next().obj("payload").long("ackOutputSeq"))
                broker.output(chunk(1, "stdout", first)); assertEquals(2L, h.next().obj("payload").long("ackOutputSeq"))
                val writing = async(Dispatchers.IO) { channel.stdin.write("rpc-request".toByteArray()) }
                val input = h.next(); assertEquals("processInput", input.string("type"))
                h.ipc.accept(h.reply(input, "processInputAck", buildJsonObject { put("requestId", requestId); put("registrationId", "registered")
                    put("inputSeq", input.obj("payload").long("inputSeq")); put("writtenBytes", 11); put("eof", false); put("state", "WRITTEN") }))
                writing.await()
                broker.closed(buildJsonObject { put("requestId", requestId); put("registrationId", "registered"); put("exitCode", 0); put("finalOutputSeq", 2)
                    put("stopConfirmed", true); put("registeredProcesses", JsonArray(emptyList())); put("unmanagedProcessDetected", false)
                    put("stdoutArtifactId", JsonNull); put("stderrArtifactId", JsonNull); put("reason", "EXITED") })
                assertEquals(-1, channel.stdout.read()); assertEquals(0, channel.awaitExit())
            } finally { broker.close() }
        }
    }
    @Test fun `uncertain stdin ACK invalidates authority without resending bytes`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            try {
                val opening = async { broker.open(WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId")), SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile())) }
                val request = h.next(); val requestId = request.obj("payload").string("requestId")
                h.ipc.accept(h.reply(request, "processStarted", buildJsonObject { put("requestId", requestId); put("registrationId", "registered"); put("pid", 42); put("processGroupId", 42); put("startIdentity", "birth") }))
                val channel = opening.await()
                val write = async(Dispatchers.IO) { runCatching { channel.stdin.write("once".toByteArray()) } }
                val input = h.next()
                h.ipc.accept(h.reply(input, "processInputAck", buildJsonObject { put("requestId", requestId); put("registrationId", "registered"); put("inputSeq", 1)
                    put("writtenBytes", 0); put("eof", false); put("state", "DELIVERY_UNCERTAIN") }))
                assertTrue(write.await().isFailure); assertFailsWith<RunnerAuthorityLost> { gate.check() }
            } finally { broker.close() }
        }
    }
}
