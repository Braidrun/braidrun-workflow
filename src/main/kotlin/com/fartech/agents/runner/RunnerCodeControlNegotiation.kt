package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowCodeControlMode

internal enum class RunnerNegotiationReason {
    RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, RUNNER_CONTROL_MODE_CONFLICT, RUNNER_CONTROL_SUPPLEMENT_UNAVAILABLE
}
internal class RunnerNegotiationException(val reason: RunnerNegotiationReason) : IllegalArgumentException(reason.name)

/** cdc4 HTTP supplement. Engine capability proves its adapter + pinned supplement, not
 * supervisor peer availability. The trusted supervisor must admit its provider before start. */
internal object RunnerCodeControlNegotiation {
    const val PER_CALL = "control.perCall.v1"
    const val LEGACY = "control.callback.v1"
    const val CONTRACT_COMMIT = "cdc4f7aea47e282336dce46548a34a716b967c88"
    const val SCHEMA_SHA256 = "13e3d91d1e582253a9f56d5e6de8aae5eac3ee574fd152ea3abec3c336a6b892"

    fun supplementAvailable(bytes: ByteArray? = javaClass.getResourceAsStream("/runner-workflow-v1/late-control.schema.json")?.use { it.readBytes() }): Boolean =
        bytes != null && sha256(bytes) == SCHEMA_SHA256

    fun select(required: Set<String>, available: Set<String>): WorkflowCodeControlMode {
        if (PER_CALL in required && LEGACY in required) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_CONTROL_MODE_CONFLICT)
        if (PER_CALL in required && !supplementAvailable()) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_CONTROL_SUPPLEMENT_UNAVAILABLE)
        if (!available.containsAll(required)) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE)
        if (PER_CALL !in required) return WorkflowCodeControlMode.LEGACY_STEP_TOKEN
        return WorkflowCodeControlMode.SUPERVISOR_PER_CALL
    }
}
