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

class RunnerProcessStartWaitTest {
    @Test fun `both launch paths survive durable event backlog longer than thirty seconds`(@TempDir dir: Path) = runBlocking {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http"))
            gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val broker = RunnerInteractiveProcesses(h.ipc, dir, gate, RunnerSecrets(), currentCoroutineContext()[Job])
            val processes = RunnerProcesses(h.ipc, dir, gate) { _, _ -> error("No artifact expected") }
            try {
                val direct = async { processes.execute(SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile())) }
                val interactive = async { broker.open(WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId")),
                    SubprocessExecutor.ExecRequest(listOf("fixture"), dir.toFile(), admissionKind = "MCP_STDIO")) }
                val requests = listOf(h.next(), h.next())
                assertEquals(setOf("processLaunch", "processOpen"), requests.map { it.string("type") }.toSet())
                delay(30_500) // Reproduce the production backlog across the old hard deadline.
                assertTrue(direct.isActive); assertTrue(interactive.isActive)
                requests.forEach { request ->
                    val id = request.string("type")
                    h.ipc.accept(h.reply(request, "processStarted", buildJsonObject {
                        put("requestId", request.obj("payload").string("requestId")); put("registrationId", id)
                        put("pid", 42); put("processGroupId", 42); put("startIdentity", "fixture-birth")
                    }))
                    if (id == "processLaunch") processes.exited(buildJsonObject {
                        put("registrationId", id); put("exitCode", 0); put("timedOut", false)
                        put("captureTruncated", false); put("stdout", "materials-ready"); put("stderr", "")
                    })
                }
                assertEquals("materials-ready", withTimeout(1000) { direct.await() }.stdout)
                assertEquals("processOpen", withTimeout(1000) { interactive.await() }.registrationId)
                assertFalse(h.failed)
            } finally { broker.close() }
        }
    }

    private fun payload() = buildJsonObject {
        put("requestId", "bounded-start"); put("argv", JsonArray(listOf(JsonPrimitive("fixture"))))
        put("relativeCwd", "."); put("environment", buildJsonObject {}); put("purpose", "CODE_STEP")
        put("timeoutSeconds", 60); put("stdin", JsonNull); put("captureLimitBytes", 1024)
    }

    @Test fun `unconfirmed launch exhausts its bound by closing authority without replay`() = runBlocking {
        IpcHarness().use { h ->
            assertEquals(300_000L, RunnerIpc.PROCESS_START_WAIT_MS)
            val start = async { assertFailsWith<RunnerAuthorityLost> { h.ipc.requestProcessStart("processLaunch", payload(), 100) } }
            assertEquals("processLaunch", h.next().string("type"))
            withTimeout(1000) { start.await() }
            assertTrue(h.failed); assertTrue(h.ipc.businessFence.isClosed())
            assertFailsWith<RunnerAuthorityLost> { h.ipc.businessFence.check() }
        }
    }

    @Test fun `parent cancellation interrupts launch wait without pretending its deadline expired`() = runBlocking {
        IpcHarness().use { h ->
            val start = async { h.ipc.requestProcessStart("processLaunch", payload()) }
            h.next()
            withTimeout(1000) { start.cancelAndJoin() }
            assertTrue(start.isCancelled)
            assertFalse(h.failed); assertFalse(h.ipc.businessFence.isClosed())
        }
    }
}
