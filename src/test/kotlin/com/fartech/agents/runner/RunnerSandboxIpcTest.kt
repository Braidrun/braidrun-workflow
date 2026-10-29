package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerSandboxIpcTest {
    private fun launch(binding: JsonObject) = buildJsonObject {
        put("requestId", "request"); put("stepName", "answer"); put("childExecutionPath", binding.string("executionId"))
        put("toolGroup", "shell"); put("runtime", "shell"); put("source", "true"); put("arguments", JsonArray(emptyList()))
        put("inputs", JsonArray(emptyList())); put("outputs", JsonArray(emptyList())); put("network", "NONE"); put("interactive", false); put("timeoutSeconds", 1)
    }

    @Test fun `sandbox negotiation requires received bound start advertised groups and pinned schema`() {
        assertTrue(RunnerSandboxProtocol.available())
        assertFalse(RunnerSandboxProtocol.CAPABILITY in RunnerHostSession.CAPABILITIES)
        assertTrue(RunnerSandboxPolicy.GROUPS.none { "tool.$it.v1" in RunnerHostSession.CAPABILITIES })
        IpcHarness().use { h ->
            val start = SandboxTestFrames.start(h.binding)
            assertFails { h.ipc.enableAgentSandbox(start, SandboxTestFrames.capabilities) }
            h.ipc.accept(start)
            assertFails { h.ipc.enableAgentSandbox(start, SandboxTestFrames.capabilities - RunnerSandboxProtocol.CAPABILITY) }
            assertFails { h.ipc.enableAgentSandbox(start, SandboxTestFrames.capabilities - "tool.file_system.v1") }
            assertFails { h.ipc.enableAgentSandbox(start, SandboxTestFrames.capabilities - "tool.git.v1") }
            h.ipc.enableAgentSandbox(start, SandboxTestFrames.capabilities)
            assertTrue(h.ipc.agentSandboxNegotiated)
            assertFails { h.ipc.accept(JsonObject(start + ("frameId" to JsonPrimitive("changed-start")))) }
        }
        IpcHarness().use { h ->
            val start = SandboxTestFrames.start(h.binding, emptySet()); h.ipc.accept(start)
            assertFails { h.ipc.enableAgentSandbox(start, SandboxTestFrames.capabilities) }
            assertFalse(h.ipc.agentSandboxNegotiated)
        }
    }

    @Test fun `sandbox frames are default denied directional and bound to a pending request`(): Unit = runBlocking {
        IpcHarness().use { h ->
            val payload = launch(h.binding)
            assertFails { h.ipc.send("sandboxProcessLaunch", payload) }
            SandboxTestFrames.negotiate(h)
            val requestFrame = RunnerProtocolTest.frame(h.binding, "sandboxProcessLaunch", payload)
            val response = SandboxTestFrames.result(h, requestFrame)
            assertFails { h.ipc.accept(requestFrame) }
            assertFails { h.ipc.sendFrame(response) }
            assertFails { h.ipc.accept(response) } // no waiter: forged cleanup cannot be consumed
            val pending = async { h.ipc.request("sandboxProcessLaunch", payload, "sandboxProcessResult") }
            val request = SandboxTestFrames.next(h)
            val valid = SandboxTestFrames.result(h, request)
            assertFails { h.ipc.accept(JsonObject(valid - "replyTo")) }
            assertFails { h.ipc.accept(JsonObject(valid + ("binding" to JsonObject(h.binding + ("attemptId" to JsonPrimitive("other")))))) }
            assertFails { h.ipc.accept(JsonObject(valid + ("unknown" to JsonPrimitive(true)))) }
            h.ipc.accept(valid); assertEquals("request", pending.await().string("requestId"))
            h.ipc.accept(valid) // exact correlated replay is inert
        }
    }

    @Test fun `in flight sandbox request is cancelled by business finalization`(): Unit = runBlocking {
        IpcHarness().use { h ->
            SandboxTestFrames.negotiate(h)
            val request = async { h.ipc.request("sandboxProcessLaunch", launch(h.binding), "sandboxProcessResult") }
            SandboxTestFrames.next(h)
            h.ipc.businessFence.close()
            assertFailsWith<CancellationException> { request.await() }
            assertFails { h.ipc.send("sandboxProcessLaunch", launch(h.binding)) }
        }
    }

    @Test fun `sandbox response payload identity cannot substitute another request`(): Unit = runBlocking {
        IpcHarness().use { h ->
            SandboxTestFrames.negotiate(h)
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http"))
            gate.install(RunnerProtocolTest.environment(h.binding, java.time.Instant.now()))
            val files = object : com.fartech.agents.tools.ToolFileAccess {
                override fun readBytes(path: String, maxBytes: Int): ByteArray = error("No file read")
                override fun writeBytes(path: String, bytes: ByteArray): Unit = error("No file write")
            }
            val bridge = RunnerSandboxProcesses(h.ipc, gate, files, "answer", h.binding.string("executionId"), setOf("shell"), liveGuard = {})
            supervisorScope {
                val call = async { bridge.execute(com.fartech.agents.tools.SandboxToolRequest("shell", "shell")) }
                val request = SandboxTestFrames.next(h)
                h.ipc.accept(SandboxTestFrames.result(h, request, "forged-request"))
                assertFails { call.await() }
            }
        }
    }
}
