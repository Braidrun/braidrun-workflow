package com.fartech.agents.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/** Supplement negotiation is separate from base IPC and from capability advertisement. */
object RunnerSandboxProtocol {
    const val CAPABILITY = "tool.agent-sandbox.offline.v1"
    const val SCHEMA_SHA256 = "481dab49db82ab3d9d0a457c1f9f678b2c0fd3586c413cc6aadfe674431f7703"
    val REQUEST_TYPES = setOf("sandboxProcessLaunch")
    val RESPONSE_TYPES = setOf("sandboxProcessResult")
    fun available(): Boolean = runCatching { protocol; true }.getOrDefault(false)
    /** Called only for the start/resume frame already accepted from the bound supervisor pipe. */
    internal fun requireNegotiated(required: Set<String>, advertised: Set<String>) {
        require(CAPABILITY in required && CAPABILITY in advertised && "tool.file_system.v1" in advertised) {
            "Sandbox requires authenticated authorization and advertised confined-file capability"
        }
        require(available()) { "Sandbox supplement pin unavailable" }
    }
    private val protocol: RunnerProtocol by lazy {
        val bytes = requireNotNull(RunnerSandboxProtocol::class.java.getResourceAsStream("/runner-workflow-v1/agent-tool-sandbox.schema.json")).use { it.readBytes() }
        require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == SCHEMA_SHA256)
        RunnerProtocol(runnerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
    }
    fun validate(frame: JsonObject, outbound: Boolean) {
        val kind = frame.string("type")
        require(kind in if (outbound) REQUEST_TYPES else RESPONSE_TYPES)
        protocol.validate(frame, kind + "Frame")
        if (outbound) RunnerSandboxPolicy.executionParts(frame.obj("binding").string("executionId"), frame.obj("payload").string("childExecutionPath"))
    }
}
