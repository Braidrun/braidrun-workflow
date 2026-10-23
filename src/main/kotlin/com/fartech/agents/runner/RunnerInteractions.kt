package com.fartech.agents.runner

import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.workflow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.serialization.json.*
import java.util.UUID

/** Only remote human decisions may approve. No timeout/network error defaults to approval. */
class RunnerApprovalHandler(
    private val ipc: RunnerIpc, private val gate: RunnerEnvironmentGate,
    private val requireFreshDecision: Boolean = true, private val journal: RunnerApprovalJournal? = null,
    private val beforeRequest: () -> Unit = {}
) : ApprovalHandler, AutoCloseable {
    private val closed = AtomicBoolean()
    private val active = CopyOnWriteArraySet<Job>()
    private fun checkOpen() { if (closed.get()) throw RunnerAuthorityLost() }
    override fun close() { closed.set(true); active.forEach { it.cancel(RunnerAuthorityLost()) }; active.clear() }
    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        checkOpen(); beforeRequest(); ipc.businessFence.check(); gate.check()
        val seconds = request.timeout?.toLongOrNull() ?: 86400L
        require(seconds > 0 && request.requestedAt > 0 && request.requestedAt <= System.currentTimeMillis()) { "Invalid original approval budget" }
        val deadline = Math.addExact(request.requestedAt, Math.multiplyExact(seconds, 1000L))
        val remaining = deadline - System.currentTimeMillis()
        require(remaining > 0) { "Original approval budget expired" }
        var requestFrameId: String? = null
        val owner = currentCoroutineContext()[Job]
        try {
        owner?.let(active::add); checkOpen()
        val payload = buildJsonObject {
            put("idempotencyKey", request.approvalId); put("occurrenceId", request.occurrenceId ?: request.approvalId)
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
        }
        val original = journal?.prepare(request, payload)
        val waitRemaining = deadline - System.currentTimeMillis()
        require(waitRemaining > 0) { "Original approval budget expired before dispatch" }
        return withTimeout(waitRemaining) {
        val reply = ipc.requestCorrelated("approval", payload, "approvalDecision", remaining,
            frameId = original?.string("frameId") ?: UUID.randomUUID().toString(), onRequest = { id ->
            requestFrameId = id; if (requireFreshDecision) gate.beginInteraction(id)
        })
        val decision = reply.payload
        require(decision.string("idempotencyKey") == request.approvalId && decision.long("decisionVersion") >= 1 && decision.string("occurrenceId") == (request.occurrenceId ?: request.approvalId)) { "Approval decision does not match request" }
        val groups = request.reviewableGroups.map { it.name }.toSet()
        val edits = decision.array("editedItems").map { it.jsonObject }
        require(edits.all { it.string("group") in groups }) { "Decision edited unknown approval group" }
        val approved = decision.string("decision") == "APPROVE"
        require(decision.string("editMode") != "AS_IS" || edits.isEmpty()) { "Ambiguous approval edits" }
        if (requireFreshDecision) gate.freshForInteraction(reply.frameId) else gate.check()
        checkOpen(); beforeRequest(); ipc.businessFence.check(); gate.check()
        if (original != null) requireNotNull(journal).retainDecision(request, original, decision)
        ApprovalDecision(approved = approved,
            edits = if (!approved || decision.string("editMode") == "AS_IS") null else groups.associateWith { group -> edits.filter { it.string("group") == group }.map { it.getValue("item") } },
            comment = decision["comment"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
        }
        } finally { requestFrameId?.let(gate::endInteraction); owner?.let(active::remove) }
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
    override fun sendMessage(message: String) { ipc.businessFence.check(); gate.check(); ipc.send("userInteraction", payload("tellUser", message)) }
    override suspend fun askQuestion(question: String, timeoutMs: Long): String {
        ipc.businessFence.check(); gate.check()
        val request = payload("askUser", question)
        var requestFrameId: String? = null
        try {
        val reply = ipc.requestCorrelated("userInteraction", request, "userResponse", timeoutMs, onRequest = { id ->
            requestFrameId = id; if (requireFreshDecision) gate.beginInteraction(id)
        })
        val response = reply.payload
        require(response.string("idempotencyKey") == request.string("idempotencyKey")) { "User response scope mismatch" }
        if (requireFreshDecision) gate.freshForInteraction(reply.frameId) else gate.check()
        ipc.businessFence.check(); return response.string("response")
        } finally { requestFrameId?.let(gate::endInteraction) }
    }
}

internal fun childPath(root: String, execution: String): JsonArray {
    require(execution == root || execution.startsWith("${root}__")) { "Child scope escapes root" }
    return JsonArray(if (execution == root) emptyList() else execution.removePrefix("${root}__").split("__").map(::JsonPrimitive))
}
