package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerNativeModelIpcTest {
    private fun pin(binding: JsonObject) = RunnerLocalModelBinding(binding.string("runnerId"), binding.string("ownerId"),
        binding.string("executionId"), binding.string("attemptId"), "native", "native://native", "1.0.0", "qwen:q4", "a".repeat(64), 4096, 256)
    private suspend fun IpcHarness.nextNative(): JsonObject = withTimeout(3000) {
        runInterruptible(Dispatchers.IO) { runnerJson.parseToJsonElement(requireNotNull(RunnerIpc.readLine(supervisorInput)).toString(Charsets.UTF_8)).jsonObject.also(RunnerNativeModelProtocol::validate) }
    }
    private fun output(request: JsonObject) = buildJsonObject {
        put("binding", request.obj("payload").obj("binding")); put("requestId", request.obj("payload").string("requestId"))
        put("text", "accepted"); put("promptTokens", 12); put("outputTokens", 2)
        put("artifactId", "artifact-acknowledged"); put("artifactSha256", "b".repeat(64)); put("elapsedNanos", 1000)
        put("processExited", true); put("scratchRemoved", true)
    }
    @Test fun `native is separately negotiated with no HTTP provider`() {
        assertTrue(RunnerNativeModelProtocol.available())
        IpcHarness().use { h ->
            assertEquals("braidrun-native", pin(h.binding).provider.id)
            val payload = buildJsonObject {
                put("binding", RunnerLocalModelProtocol.json(pin(h.binding))); put("requestId", "request"); put("stepName", "answer")
                put("childExecutionPath", JsonArray(emptyList())); put("messages", JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", "hello") })))
                put("outputTokens", 128); put("jsonObject", false)
            }
            assertFails { h.ipc.send("localNativeGenerate", payload) }
            h.ipc.enableLocalModel()
            assertFails { h.ipc.send("localNativeGenerate", payload) }
            h.ipc.enableNativeModel()
            h.ipc.send("localNativeGenerate", payload)
            assertFails { RunnerLocalModelProtocol.binding(JsonObject(RunnerLocalModelProtocol.json(pin(h.binding)) + ("endpoint" to JsonPrimitive("native://different")))) }
        }
    }
    @Test fun `native completion waits for typed artifact and stop receipt`(): Unit = runBlocking {
        IpcHarness().use { h ->
            h.ipc.enableNativeModel()
            val gate = RunnerEnvironmentGate(h.binding, emptySet())
            RunnerNativeModelClient(pin(h.binding), h.ipc, gate, {}).use { client ->
                val task = async { client.complete(RunnerLocalModelRequest("answer", listOf(RunnerLocalMessage("user", "hello")),128)) {} }
                val request = h.nextNative()
                assertFalse(task.isCompleted)
                h.ipc.accept(h.reply(request, "localNativeResult", output(request)))
                val result = task.await()
                assertEquals("accepted", result.content); assertEquals(12L, result.promptTokens); assertEquals(2L,result.outputTokens)
            }
        }
    }
    @Test fun `native result cannot omit artifact or claim unconfirmed stop`(): Unit = runBlocking {
        IpcHarness().use { h ->
            h.ipc.enableNativeModel()
            val task = async { h.ipc.request("localNativeGenerate", buildJsonObject {
                put("binding",RunnerLocalModelProtocol.json(pin(h.binding)));put("requestId","request");put("stepName","answer")
                put("childExecutionPath",JsonArray(emptyList()));put("messages",JsonArray(listOf(buildJsonObject {put("role","user");put("content","hello")})))
                put("outputTokens",128);put("jsonObject",false)
            },"localNativeResult") }
            val request = h.nextNative(); val good = output(request)
            for (bad in listOf(JsonObject(good - "artifactId"), JsonObject(good + ("processExited" to JsonPrimitive(false))))) {
                assertFails { h.ipc.accept(h.reply(request,"localNativeResult",bad)) }
            }
            h.ipc.accept(h.reply(request,"localNativeResult",good)); task.await()
        }
    }
}
