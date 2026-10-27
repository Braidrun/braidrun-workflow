package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowCodeControlMode

internal enum class RunnerNegotiationReason {
    RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, RUNNER_CONTROL_MODE_CONFLICT, RUNNER_CONTROL_SUPPLEMENT_UNAVAILABLE,
    RUNNER_ARTIFACT_CAPABILITY_REQUIRED, RUNNER_ARTIFACT_POLICY_INVALID, RUNNER_FEEDBACK_CONTEXT_REQUIRES_PER_CALL, RUNNER_SOURCE_READ_POLICY_REQUIRES_PER_CALL, RUNNER_SET_DEPENDENCY_REQUIRED, RUNNER_GATE_PROJECTION_DEPENDENCY_REQUIRED, RUNNER_GATE_FEEDBACK_DEPENDENCY_REQUIRED
}
internal class RunnerNegotiationException(val reason: RunnerNegotiationReason) : IllegalArgumentException(reason.name)

/** Pinned HTTP supplement; selector semantics remain those introduced by cdc4. Engine capability proves its adapter + pinned supplement, not
 * supervisor peer availability. The trusted supervisor must admit its provider before start. */
internal object RunnerCodeControlNegotiation {
    const val PER_CALL = "control.perCall.v1"
    const val LEGACY = "control.callback.v1"
    // P/S metadata travels through the existing guarded CC feedback.read adapter, never a new E IPC frame.
    const val FEEDBACK_CONTEXT = "control.feedbackContext.v1"
    // Historical-source carrier and guarded helper remain P/S-owned; E only negotiates existing CC transport.
    const val SOURCE_READ_POLICY = "control.sourceReadPolicy.v1"
    // P/S frozen per-gate dispatch/projection; its legacy context provider is not the mutually exclusive callback selector.
    const val GATE_FEEDBACK = "control.gateFeedbackContext.v1"
    const val GATE_PROJECTION = "control.gateProjection.v1"
    const val CONTRACT_COMMIT = "cc684f0a28b6b3f3106b91e486244843f979aa83"
    const val SCHEMA_SHA256 = "5ba7d76c4f048f6f7ca5b71e427492b639cad4b80ccbe28d621e197d768b72f4"

    fun supplementAvailable(bytes: ByteArray? = javaClass.getResourceAsStream("/runner-workflow-v1/late-control.schema.json")?.use { it.readBytes() }): Boolean =
        bytes != null && sha256(bytes) == SCHEMA_SHA256

    fun select(required: Set<String>, available: Set<String>): WorkflowCodeControlMode {
        if ((RunnerTestContractProtocol.CAPABILITY in required || RunnerLongCodeProtocol.CAPABILITY in required) && PER_CALL !in required)
            throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE)
        if (GATE_FEEDBACK in required && !required.containsAll(setOf(GATE_PROJECTION, FEEDBACK_CONTEXT)))
            throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_GATE_FEEDBACK_DEPENDENCY_REQUIRED)
        if (GATE_PROJECTION in required && !required.containsAll(setOf(RunnerArtifactSetProtocol.GATES, RunnerArtifactSetProtocol.CAPABILITY, PER_CALL)))
            throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_GATE_PROJECTION_DEPENDENCY_REQUIRED)
        if ((RunnerArtifactSetProtocol.CAPABILITY in required && RunnerCollectionProtocol.CAPABILITY !in required) ||
            (RunnerArtifactSetProtocol.GATES in required && (RunnerArtifactSetProtocol.CAPABILITY !in required || PER_CALL !in required)) ||
            (RunnerArtifactSetProtocol.SOURCE in required && RunnerArtifactSetProtocol.CAPABILITY !in required))
            throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_SET_DEPENDENCY_REQUIRED)
        if (FEEDBACK_CONTEXT in required && PER_CALL !in required) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_FEEDBACK_CONTEXT_REQUIRES_PER_CALL)
        if (SOURCE_READ_POLICY in required && PER_CALL !in required) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_SOURCE_READ_POLICY_REQUIRES_PER_CALL)
        if (PER_CALL in required && LEGACY in required) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_CONTROL_MODE_CONFLICT)
        if (PER_CALL in required && !supplementAvailable()) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_CONTROL_SUPPLEMENT_UNAVAILABLE)
        if (!available.containsAll(required)) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE)
        if (PER_CALL !in required) return WorkflowCodeControlMode.LEGACY_STEP_TOKEN
        return WorkflowCodeControlMode.SUPERVISOR_PER_CALL
    }
}
