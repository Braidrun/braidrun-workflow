package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerLocalModelIpcTest {
    private fun pin(binding: JsonObject) = RunnerLocalModelBinding(binding.string("runnerId"), binding.string("ownerId"), binding.string("executionId"), binding.string("attemptId"),
        "local", "http://127.0.0.1:11434", "0.12.6", "fixture:8b", "a".repeat(64), 8192, 512)
    private suspend fun IpcHarness.nextLocal(): JsonObject = withTimeout(3000) {
        runInterruptible(Dispatchers.IO) { runnerJson.parseToJsonElement(requireNotNull(RunnerIpc.readLine(supervisorInput)).toString(Charsets.UTF_8)).jsonObject.also(RunnerLocalModelProtocol::validate) }
    }
    private fun IpcHarness.status(request: JsonObject, state: String, released: Boolean? = null) {
        val p = request.obj("payload")
        ipc.accept(reply(request, if (request.string("type") == "localModelReserve") "localModelGrant" else "localModelStatus", buildJsonObject {
            put("binding", p.obj("binding")); put("requestId", p.string("requestId")); put("state", state); released?.let { put("released", it) }
        }))
    }
    @Test fun `typed supplement is pinned disabled by default and directional`() {
        assertTrue(RunnerLocalModelProtocol.available())
        IpcHarness().use { h ->
            val payload = buildJsonObject { put("binding", RunnerLocalModelProtocol.json(pin(h.binding))); put("requestId", "request"); put("state", "STARTED") }
            val response = RunnerProtocolTest.frame(h.binding, "localModelStatus", payload, "request-frame")
            RunnerLocalModelProtocol.validate(response)
            assertFails { h.ipc.accept(response) }
            h.ipc.enableLocalModel()
            h.ipc.accept(response) // valid unknown late reply is inert
            assertFails { h.ipc.sendFrame(response) }
            assertFails { h.ipc.accept(JsonObject(response - "replyTo")) }
            assertFails { RunnerLocalModelProtocol.validate(JsonObject(response + ("unknown" to JsonPrimitive(true)))) }
        }
        assertFalse(RunnerLocalModelProtocol.CAPABILITY in RunnerHostSession.CAPABILITIES)
    }
    @Test fun `reserve started and independent stop status retain exact request binding`(): Unit = runBlocking {
        IpcHarness().use { h ->
            h.ipc.enableLocalModel()
            RunnerIpcLocalModelAuthority(h.ipc, {}).use { authority ->
                val pending = async { authority.reserve(pin(h.binding), "request", RunnerLocalModelRequest("answer", listOf(RunnerLocalMessage("user", "hello")), 512)) }
                val reserve = h.nextLocal(); assertEquals("localModelReserve", reserve.string("type")); h.status(reserve, "RESERVED")
                val grant = pending.await(); grant.check()
                val start = async { grant.markInferenceMayHaveStarted() }
                var request = h.nextLocal()
                while (request.string("type") == "localModelCheck") { h.status(request, "RESERVED"); request = h.nextLocal() }
                assertEquals("localModelStarted", request.string("type")); h.status(request, "STARTED"); start.await()
                val finish = async { grant.finish(RunnerLocalStopObservation.SERVICE_REPORTED_DONE) }
                request = h.nextLocal()
                while (request.string("type") == "localModelCheck") { h.status(request, "STARTED"); request = h.nextLocal() }
                assertEquals("localModelFinish", request.string("type")); assertEquals("SERVICE_REPORTED_DONE", request.obj("payload").string("observation"))
                h.status(request, "QUARANTINED", false)
                assertFalse(finish.await()); assertFails { grant.check() }
            }
        }
    }
    @Test fun `lost supervisor health closes grant without inventing release`(): Unit = runBlocking {
        IpcHarness().use { h ->
            h.ipc.enableLocalModel()
            RunnerIpcLocalModelAuthority(h.ipc, {}).use { authority ->
                val pending = async { authority.reserve(pin(h.binding), "request", RunnerLocalModelRequest("answer", listOf(RunnerLocalMessage("user", "hello")), 512)) }
                h.status(h.nextLocal(), "RESERVED"); val grant = pending.await()
                assertEquals("localModelCheck", h.nextLocal().string("type"))
                delay(1600)
                assertFails { grant.check() }
            }
        }
    }
    @Test fun `cross attempt pin refused before any local reserve`(): Unit = runBlocking {
        IpcHarness().use { h ->
            h.ipc.enableLocalModel()
            RunnerIpcLocalModelAuthority(h.ipc, {}).use { authority ->
                assertFails { authority.reserve(pin(h.binding).copy(attemptId = "wrong"), "request", RunnerLocalModelRequest("answer", listOf(RunnerLocalMessage("user", "hello")), 512)) }
            }
        }
    }
}
