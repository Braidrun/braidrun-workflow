package com.fartech.agents.runner

import kotlinx.serialization.json.*

/** Opt-in supplement. Including these bytes never advertises or enables the capability. */
internal object RunnerLocalModelProtocol {
    const val CAPABILITY = "model.local.ollama.text.v1"
    const val SHA256 = "349c223a3d654361c51b76d1ded167c7a9b3785d0589c4b1440f37fad6ee3dcb"
    val requests = setOf("localModelReserve", "localModelCheck", "localModelStarted", "localModelFinish")
    val responses = setOf("localModelGrant", "localModelStatus")
    private fun bytes() = javaClass.getResourceAsStream("/runner-workflow-v1/local-model.schema.json")?.use { it.readBytes() }
    fun available() = bytes()?.let { sha256(it) == SHA256 } == true
    private val validator by lazy {
        val raw = requireNotNull(bytes()); require(sha256(raw) == SHA256)
        RunnerProtocol(runnerJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject)
    }
    fun validate(frame: JsonObject) {
        val type = frame.string("type")
        require(type in requests || type in responses)
        validator.validate(frame, type + "Frame")
    }
    fun binding(value: JsonObject): RunnerLocalModelBinding {
        validator.validate(value, "localBinding")
        return RunnerLocalModelBinding(value.string("runnerId"), value.string("ownerId"), value.string("executionId"), value.string("attemptId"),
            value.string("endpointId"), value.string("endpoint"), value.string("serviceVersion"), value.string("model"), value.string("digest"),
            value.long("contextTokens").toInt(), value.long("maximumOutputTokens").toInt())
    }
    fun json(binding: RunnerLocalModelBinding) = buildJsonObject {
        put("runnerId", binding.runnerId); put("ownerId", binding.ownerId); put("executionId", binding.executionId); put("attemptId", binding.attemptId)
        put("endpointId", binding.endpointId); put("endpoint", binding.endpoint); put("serviceVersion", binding.serviceVersion)
        put("model", binding.model); put("digest", binding.digest); put("contextTokens", binding.contextTokens); put("maximumOutputTokens", binding.maximumOutputTokens)
    }
}
