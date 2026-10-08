package com.fartech.agents.runner

import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.workflow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID

/** Only remote human decisions may approve. No timeout/network error defaults to approval. */
class RunnerApprovalHandler(private val ipc: RunnerIpc, private val gate: RunnerEnvironmentGate, private val requireFreshDecision: Boolean = true) : ApprovalHandler {
    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        gate.check()
        val checkGeneration = gate.evidenceGeneration()
        val decision = ipc.request("approval", buildJsonObject {
            put("idempotencyKey", request.approvalId)
            put("childExecutionPath", childPath(ipc.binding.string("executionId"), request.executionId))
            put("stepId", request.stepName); put("title", request.workflowName)
            put("description", request.message.ifBlank { "Approval required" })
            put("editableColumns", JsonArray(request.reviewableGroups.map { group -> buildJsonObject {
                put("group", group.name); put("definition", buildJsonObject {
                    put("name", group.name); put("sourceVar", group.sourceVar); put("outputVar", group.outputVar)
                    put("title", group.title?.let(::JsonPrimitive) ?: JsonNull); put("keyField", group.keyField?.let(::JsonPrimitive) ?: JsonNull)
                    put("columns", JsonArray(group.columns.map { runnerJson.encodeToJsonElement(ReviewableActionColumn.serializer(), it) }))
                    put("items", JsonArray(group.items))
                })
            } }))
            put("items", JsonArray(request.reviewableGroups.flatMap { group -> group.items.map { item ->
                buildJsonObject { put("group", group.name); put("item", item) }
            } }))
        }, "approvalDecision", (request.timeout?.toLongOrNull() ?: 86400L) * 1000)
        require(decision.string("idempotencyKey") == request.approvalId && decision.long("decisionVersion") >= 1) { "Approval decision does not match request" }
        val groups = request.reviewableGroups.map { it.name }.toSet()
        val edits = decision.array("editedItems").map { it.jsonObject }
        require(edits.all { it.string("group") in groups }) { "Decision edited unknown approval group" }
        val approved = decision.string("decision") == "APPROVE"
        require(decision.string("editMode") != "AS_IS" || edits.isEmpty()) { "Ambiguous approval edits" }
        if (requireFreshDecision) gate.freshAfterDecision(checkGeneration) else gate.check()
        return ApprovalDecision(approved = approved,
            edits = if (!approved || decision.string("editMode") == "AS_IS") null else groups.associateWith { group -> edits.filter { it.string("group") == group }.map { it.getValue("item") } },
            comment = decision["comment"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)

    }
}

class RunnerUserInteraction(
    private val ipc: RunnerIpc,
    private val gate: RunnerEnvironmentGate,
    private val scope: WorkflowHostScope,
    private val secrets: RunnerSecrets,
    private val requireFreshDecision: Boolean = true
) : UserInteractionHandler {
    private fun payload(operation: String, message: String) = buildJsonObject {
        put("idempotencyKey", UUID.randomUUID().toString()); put("operation", operation)
        put("childExecutionPath", childPath(scope.rootExecutionId, scope.executionId))
        put("message", secrets.redact(message)); put("options", JsonArray(emptyList()))
    }
    override fun sendMessage(message: String) { gate.check(); ipc.send("userInteraction", payload("tellUser", message)) }
    override suspend fun askQuestion(question: String, timeoutMs: Long): String {
        gate.check()
        val request = payload("askUser", question)
        val checkGeneration = gate.evidenceGeneration()
        val response = ipc.request("userInteraction", request, "userResponse", timeoutMs)
        require(response.string("idempotencyKey") == request.string("idempotencyKey")) { "User response scope mismatch" }
        if (requireFreshDecision) gate.freshAfterDecision(checkGeneration) else gate.check()
        return response.string("response")
    }
}

internal fun childPath(root: String, execution: String): JsonArray {
    require(execution == root || execution.startsWith("${root}__")) { "Child scope escapes root" }
    return JsonArray(if (execution == root) emptyList() else execution.removePrefix("${root}__").split("__").map(::JsonPrimitive))
}
