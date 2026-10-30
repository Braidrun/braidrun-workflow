package com.fartech.agents.runner.multimedia

import com.fartech.agents.tools.AudioModel
import com.fartech.agents.tools.ImageModel

/** Trusted host input, resolved from the frozen snapshot before registering the tool set. */
class RunnerMultimediaPlan(val imageModelId: String?, val audioModelId: String?) {
    init {
        require(imageModelId != null || audioModelId != null) { "No frozen multimedia model" }
        require(imageModelId == null || ImageModel.entries.any { it.model == imageModelId }) { "Unsupported frozen image model" }
        require(audioModelId == null || AudioModel.entries.any { it.model == audioModelId }) { "Unsupported frozen audio model" }
    }
}

/**
 * Construct only from an already acquired, scope-bound host credential lease. The required guard
 * must revalidate that lease, its model grants and the attempt fence; never resolve host env keys.
 * The host owns release/rotation. This object never logs or serializes the material.
 */
class RunnerMultimediaLease(
    private val apiKey: String?,
    grantedModelIds: Set<String>,
    private val checkActive: () -> Unit
) {
    private val grants = grantedModelIds.toSet()
    internal fun requireActive(modelId: String) {
        checkActive()
        if (modelId !in grants) throw SecurityException("Multimedia model is outside the credential lease")
        if (apiKey.isNullOrBlank() || apiKey.length > 8192 || apiKey.any { it.code < 32 || it.code == 127 }) {
            throw SecurityException("A valid leased multimedia credential is required")
        }
    }
    internal fun key(modelId: String): String { requireActive(modelId); return apiKey!! }
    override fun toString() = "RunnerMultimediaLease(<redacted>)"
}

data class RunnerMultimediaLimits(
    val maxPromptChars: Int = 32_000,
    val maxPathChars: Int = 4096,
    val maxReferences: Int = 4,
    val maxReferenceBytes: Int = 4 * 1024 * 1024,
    val maxTotalReferenceBytes: Int = 12 * 1024 * 1024,
    val maxRequestBytes: Int = 24 * 1024 * 1024,
    val maxResponseBytes: Int = 24 * 1024 * 1024,
    val maxOutputBytes: Int = 16 * 1024 * 1024,
    val maxResponseTextChars: Int = 64_000,
    val timeoutMillis: Long = 120_000,
    val maxJsonDepth: Int = 32,
    val maxJsonNodes: Int = 10_000
) {
    init {
        require(maxPromptChars in 1..32_000 && maxPathChars in 1..4096 && maxReferences in 0..4)
        require(maxReferenceBytes in 1..4 * 1024 * 1024 && maxTotalReferenceBytes in 1..12 * 1024 * 1024)
        require(maxRequestBytes in 1..24 * 1024 * 1024 && maxResponseBytes in 1..24 * 1024 * 1024)
        require(maxOutputBytes in 1..16 * 1024 * 1024 && maxResponseTextChars in 1..64_000)
        require(timeoutMillis in 1..120_000 && maxJsonDepth in 1..32 && maxJsonNodes in 1..10_000)
    }
}

/** Messages are fixed strings; never attach raw HTTP/parser exceptions containing provider data. */
class RunnerMultimediaFailure(message: String) : java.io.IOException(message)
