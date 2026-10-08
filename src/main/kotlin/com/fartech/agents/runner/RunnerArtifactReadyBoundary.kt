package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.serialization.json.*

/** Interpreter context mapped only to P's frozen a380 payload; no caller paths or verdicts. */
internal data class RunnerArtifactReadyBoundary(
    val policySha256: String,
    val producerWorkflowId: String,
    val scope: WorkflowHostScope,
    val producerStartEventId: String,
    val declarationIds: List<String>
) {
    fun payload(ids: List<String> = declarationIds) = buildJsonObject {
        put("kind", "artifact_collection_ready"); put("executionId", scope.executionId); put("stepName", requireNotNull(scope.stepName))
        put("producerWorkflowId", producerWorkflowId); put("producerStartEventId", producerStartEventId)
        put("childExecutionPath", childPath(scope.rootExecutionId, scope.executionId)); put("policySha256", policySha256)
        put("declarationIds", JsonArray(ids.map(::JsonPrimitive)))
    }
}
internal object RunnerArtifactBoundaryProtocol {
    const val COMMIT = "a380c4806ca362f7533d3ac443a4e210d130c969"
    const val SHA256 = "0b92f10b13388180069457ef36e428ab698b25d9d397ca26528674b38919fd34"
    private fun bytes() = javaClass.getResourceAsStream("/runner-workflow-v1/artifact-boundary.schema.json")?.use { it.readBytes() }
    fun available() = bytes()?.let { sha256(it) == SHA256 } == true
    private val validator by lazy {
        val raw = requireNotNull(bytes()); require(sha256(raw) == SHA256)
        RunnerProtocol(buildJsonObject { put("\$defs", buildJsonObject { put("boundary", runnerJson.parseToJsonElement(raw.toString(Charsets.UTF_8))) }) })
    }
    fun validate(payload: JsonObject) = validator.validate(payload, "boundary")
}
