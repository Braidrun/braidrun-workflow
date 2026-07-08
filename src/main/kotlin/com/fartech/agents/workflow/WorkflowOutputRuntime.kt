package com.fartech.agents.workflow

/**
 * Runtime-facing contract for published workflow outputs.
 *
 * The agent runtime owns YAML parsing and step execution, but storage and
 * authorization live in workflow-web. These small interfaces keep that boundary
 * explicit: CLI runs can omit them, while web executions inject implementations
 * backed by the platform output registry.
 */
data class PublishedWorkflowOutputPayload(
    val name: String,
    val type: String,
    val value: String,
    val description: String?,
    val contractVersion: String,
    val visibility: WorkflowOutputVisibilityConfig,
    val sourceExpression: String,
    val workflowId: String?,
    val workflowName: String,
    val workflowVersion: String,
    val executionId: String,
    val stepName: String,
    val workflowStatusAtPublish: String,
    val publishedAt: Long = System.currentTimeMillis()
)

interface WorkflowOutputPublisher {
    suspend fun publish(output: PublishedWorkflowOutputPayload)
}

data class WorkflowOutputReadRequest(
    val sourceWorkflowId: String,
    val selector: WorkflowOutputSelector,
    val outputNames: Set<String>,
    val targetWorkflowName: String,
    val targetExecutionId: String,
    val targetStepName: String,
    val missingPolicy: WorkflowOutputMissingPolicy,
    val requireWorkflowStatus: String?,
    val allowPartialExecution: Boolean
)

data class WorkflowOutputReadResult(
    val sourceExecutionId: String,
    val sourceWorkflowId: String,
    val outputs: Map<String, String>,
    val missingOutputs: Set<String> = emptySet()
)

interface WorkflowOutputResolver {
    suspend fun read(request: WorkflowOutputReadRequest): WorkflowOutputReadResult
}
