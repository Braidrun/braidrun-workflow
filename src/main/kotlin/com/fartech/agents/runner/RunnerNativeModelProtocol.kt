package com.fartech.agents.runner

import ai.koog.prompt.llm.LLMProvider
import kotlinx.serialization.json.*
import java.util.UUID

/** Native IPC carries text only. The JVM never connects to or starts a model service. */
internal object RunnerNativeModelProtocol {
    const val CAPABILITY = "model.local.native.text.v1"
    const val SHA256 = "c43e8e7462d95c6a871f0fdc0c95afe20915802d13661da6af5c8753e0516984"
    val provider = LLMProvider("braidrun-native", "Braidrun Native")
    val requests = setOf("localNativeGenerate")
    val responses = setOf("localNativeResult")
    private fun bytes() = javaClass.getResourceAsStream("/runner-workflow-v1/native-model.schema.json")?.use { it.readBytes() }
    fun available() = bytes()?.let { sha256(it) == SHA256 } == true
    private val validator by lazy {
        val raw = requireNotNull(bytes()); require(sha256(raw) == SHA256)
        RunnerProtocol(runnerJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject)
    }
    fun validate(frame: JsonObject) {
        require(frame.string("type") in requests + responses)
        validator.validate(frame, frame.string("type") + "Frame")
    }
    fun validateBinding(value: JsonObject) = validator.validate(value, "localBinding")
}

internal class RunnerNativeModelClient(private val binding: RunnerLocalModelBinding, private val ipc: RunnerIpc,
    private val gate: RunnerEnvironmentGate, private val checkLive: () -> Unit) : AutoCloseable {
    init {
        require(binding.native && listOf("runnerId", "ownerId", "executionId", "attemptId").all {
            RunnerLocalModelProtocol.json(binding)[it] == ipc.binding[it]
        }) { "Native IPC binding differs from execution" }
    }
    private val active = java.util.concurrent.atomic.AtomicBoolean()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    suspend fun complete(request: RunnerLocalModelRequest, validateOutput: (String) -> Unit): RunnerLocalModelResult {
        checkLive()
        require(!closed.get() && active.compareAndSet(false, true)) { "NATIVE_CLIENT_BUSY_OR_CLOSED" }
        try {
            require(!request.jsonObject && request.outputTokens in 1..binding.maximumOutputTokens) { "NATIVE_TEXT_BUDGET_REQUIRED" }
            val id = UUID.randomUUID().toString()
            val payload = buildJsonObject {
                put("binding", RunnerLocalModelProtocol.json(binding)); put("requestId", id)
                put("stepName", request.stepName); put("childExecutionPath", JsonArray(request.childExecutionPath.map(::JsonPrimitive)))
                put("messages", JsonArray(request.messages.map { buildJsonObject { put("role", it.role); put("content", it.content) } }))
                put("outputTokens", request.outputTokens); put("jsonObject", false)
            }
            // Includes model load, inference and durable artifact upload; uncertainty closes
            // the task's business gate instead of retrying or switching to a cloud provider.
            val result = ipc.request("localNativeGenerate", payload, "localNativeResult", 900_000)
            checkLive()
            require(result.obj("binding") == RunnerLocalModelProtocol.json(binding) && result.string("requestId") == id)
            require(result["processExited"] == JsonPrimitive(true) && result["scratchRemoved"] == JsonPrimitive(true))
            require(result.long("outputTokens") <= request.outputTokens && result.long("promptTokens") + request.outputTokens <= binding.contextTokens)
            val text = result.string("text")
            validateOutput(text)
            return RunnerLocalModelResult(text, binding.model, binding.digest, binding.serviceVersion, id, null,
                result.long("elapsedNanos"), result.long("promptTokens"), result.long("outputTokens"))
        } catch (failure: Throwable) {
            gate.invalidate("RUNNER_NATIVE_MODEL_AUTHORITY_LOST")
            throw failure
        } finally { active.set(false) }
    }
    override fun close() {
        closed.set(true)
        if (active.get()) gate.invalidate("RUNNER_NATIVE_MODEL_CLOSED")
    }
}
