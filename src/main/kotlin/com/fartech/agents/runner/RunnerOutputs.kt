package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*
import java.util.UUID

class RunnerOutputs(private val ipc: RunnerIpc, private val gate: RunnerEnvironmentGate, private val secrets: RunnerSecrets) : WorkflowOutputPublisher, WorkflowOutputResolver {
    override suspend fun publish(output: PublishedWorkflowOutputPayload) {
        gate.check()
        val id = UUID.randomUUID().toString()
        require(!secrets.containsSecret(JsonPrimitive(output.value))) { "Secrets cannot be published as outputs" }
        val response = ipc.request("outputPublish", buildJsonObject {
            put("requestId", id); put("outputName", output.name); put("value", output.value)
            put("childExecutionPath", childPath(ipc.binding.string("executionId"), output.executionId))
            put("outputType", output.type); put("description", output.description?.let(::JsonPrimitive) ?: JsonNull)
            put("contractVersion", output.contractVersion); put("visibility", runnerJson.encodeToJsonElement(WorkflowOutputVisibilityConfig.serializer(), output.visibility))
            put("sourceExpression", output.sourceExpression); put("workflowId", output.workflowId?.let(::JsonPrimitive) ?: JsonNull)
            put("workflowName", output.workflowName); put("workflowVersion", output.workflowVersion)
            put("executionId", output.executionId); put("stepName", output.stepName); put("workflowStatusAtPublish", output.workflowStatusAtPublish)
            put("publishedAt", output.publishedAt)
        }, "outputAck")
        require(response.string("requestId") == id) { "Output ACK correlation mismatch" }
        gate.check()
    }
    override suspend fun read(request: WorkflowOutputReadRequest): WorkflowOutputReadResult {
        gate.check()
        val id = UUID.randomUUID().toString()
        val response = ipc.request("outputRead", buildJsonObject {
            put("requestId", id); put("sourceWorkflowId", request.sourceWorkflowId)
            put("childExecutionPath", childPath(ipc.binding.string("executionId"), request.targetExecutionId))
            put("selector", runnerJson.encodeToJsonElement(WorkflowOutputSelector.serializer(), request.selector)); put("sourceExecutionId", request.selector.executionId?.let(::JsonPrimitive) ?: JsonNull)
            put("outputNames", JsonArray(request.outputNames.sorted().map(::JsonPrimitive))); put("targetWorkflowName", request.targetWorkflowName)
            put("targetExecutionId", request.targetExecutionId); put("targetStepName", request.targetStepName)
            put("missingPolicy", when (request.missingPolicy) { WorkflowOutputMissingPolicy.FAIL -> "fail"; WorkflowOutputMissingPolicy.USE_DEFAULT -> "use_default"; WorkflowOutputMissingPolicy.SKIP_STEP -> "skip_step" })
            put("requireWorkflowStatus", request.requireWorkflowStatus?.let(::JsonPrimitive) ?: JsonNull); put("allowPartialExecution", request.allowPartialExecution)
        }, "outputValue")
        require(response.string("requestId") == id && response.string("sourceWorkflowId") == request.sourceWorkflowId) { "Output response scope mismatch" }
        if (request.selector.mode == WorkflowOutputSelectorMode.EXECUTION_ID) require(response.string("sourceExecutionId") == request.selector.executionId) { "Output source execution mismatch" }
        gate.check()
        return WorkflowOutputReadResult(response.string("sourceExecutionId"), response.string("sourceWorkflowId"),
            response.obj("outputs").mapValues { it.value.jsonPrimitive.content }, response.array("missingOutputs").map { it.jsonPrimitive.content }.toSet())
    }
}
