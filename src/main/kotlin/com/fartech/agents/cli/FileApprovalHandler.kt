package com.fartech.agents.cli

import com.fartech.agents.workflow.ApprovalDecision
import com.fartech.agents.workflow.ApprovalHandler
import com.fartech.agents.workflow.ApprovalRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Headless approvals for unattended runs (launchd workers, CI, the App Factory Mac runner).
 *
 * Each `manual_approval` request is written to `<dir>/requests/<id>.json`; the handler then polls
 * `<dir>/decisions/<id>.json` until a decision appears, the optional policy auto-approves the step,
 * or the engine's own approval timeout cancels the wait. A decision file is consumed the moment it
 * is read (renamed to `<id>.consumed-<millis>.json`), so a stale answer can never satisfy a later
 * request that reuses the same id, e.g. a state-machine gate entered twice.
 *
 * Decision file: `{"approved": true|false, "comment": "...", "edits": {"<group>": [items...]}}`.
 * `comment` and `edits` are optional; `edits` mirrors [ApprovalDecision.edits].
 *
 * Policy file `<dir>/policy.json` (optional):
 * `{"steps": {"escalate": {"auto_approve_after_seconds": 1800, "comment": "..."}}, "default": {...}}`.
 * A positive `auto_approve_after_seconds` turns that step into a soft gate: silence means continue.
 * Steps without a policy entry are hard gates and wait for the engine timeout.
 */
class FileApprovalHandler(
    private val dir: File,
    private val pollMillis: Long = 2_000L,
    private val printLine: (String) -> Unit = ::println,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ApprovalHandler {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    data class StepPolicy(val autoApproveAfterSeconds: Long, val comment: String?)

    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        val id = safeId(request.approvalId)
        val requestFile = File(File(dir, "requests"), "$id.json")
        val decisionFile = File(File(dir, "decisions"), "$id.json")
        val policy = withContext(Dispatchers.IO) { stepPolicy(request.stepName) }
        val autoAfterMillis = policy?.autoApproveAfterSeconds?.takeIf { it > 0 }?.times(1_000L)
        withContext(Dispatchers.IO) {
            requestFile.parentFile.mkdirs()
            decisionFile.parentFile.mkdirs()
            requestFile.writeText(json.encodeToString(JsonObject.serializer(), requestJson(request, decisionFile, autoAfterMillis)), Charsets.UTF_8)
        }
        printLine(
            "Approval requested for step '${request.stepName}': decide by writing ${decisionFile.absolutePath}" +
                (autoAfterMillis?.let { "; silence continues automatically after ${it / 1_000}s" } ?: "")
        )
        val started = nowMillis()
        while (true) {
            val decision = withContext(Dispatchers.IO) { readAndConsumeDecision(decisionFile) }
            if (decision != null) {
                printLine("Approval for step '${request.stepName}': ${if (decision.approved) "approved" else "rejected"}")
                return decision
            }
            if (autoAfterMillis != null && nowMillis() - started >= autoAfterMillis) {
                val comment = policy.comment
                    ?: "No decision within ${autoAfterMillis / 1_000}s; continued automatically by approval policy."
                withContext(Dispatchers.IO) {
                    File(decisionFile.parentFile, "$id.consumed-${nowMillis()}.json").writeText(
                        json.encodeToString(
                            JsonObject.serializer(),
                            JsonObject(mapOf("approved" to JsonPrimitive(true), "comment" to JsonPrimitive(comment), "source" to JsonPrimitive("policy_auto_approve")))
                        ),
                        Charsets.UTF_8
                    )
                }
                printLine("Approval for step '${request.stepName}': auto-approved by policy")
                return ApprovalDecision(approved = true, comment = comment)
            }
            delay(pollMillis)
        }
    }

    private fun readAndConsumeDecision(decisionFile: File): ApprovalDecision? {
        if (!decisionFile.isFile) return null
        val text = decisionFile.readText(Charsets.UTF_8)
        val consumed = File(decisionFile.parentFile, decisionFile.nameWithoutExtension + ".consumed-${nowMillis()}.json")
        if (!decisionFile.renameTo(consumed)) decisionFile.delete()
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val approved = root["approved"]?.let { it as? JsonPrimitive }?.booleanOrNull
                ?: throw IllegalArgumentException("'approved' must be true or false")
            val comment = root["comment"]?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull }
            val edits = root["edits"]?.takeIf { it !is JsonNull }?.jsonObject?.mapValues { (_, value) -> value.jsonArray.toList() }
            if (approved) ApprovalDecision(approved = true, edits = edits, comment = comment)
            else ApprovalDecision(approved = false, comment = comment)
        } catch (e: Exception) {
            ApprovalDecision(approved = false, comment = "Malformed decision file ${decisionFile.name}: ${e.message}; no approval granted.")
        }
    }

    private fun stepPolicy(stepName: String): StepPolicy? {
        val file = File(dir, "policy.json")
        if (!file.isFile) return null
        return try {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val entry = root["steps"]?.takeIf { it !is JsonNull }?.jsonObject?.get(stepName)?.takeIf { it !is JsonNull }?.jsonObject
                ?: root["default"]?.takeIf { it !is JsonNull }?.jsonObject
                ?: return null
            StepPolicy(
                autoApproveAfterSeconds = (entry["auto_approve_after_seconds"] as? JsonPrimitive)?.longOrNull ?: 0L,
                comment = (entry["comment"] as? JsonPrimitive)?.contentOrNull
            )
        } catch (e: Exception) {
            printLine("Ignoring unreadable approval policy ${file.absolutePath}: ${e.message}")
            null
        }
    }

    private fun requestJson(request: ApprovalRequest, decisionFile: File, autoAfterMillis: Long?): JsonObject = JsonObject(
        mapOf(
            "approval_id" to JsonPrimitive(request.approvalId),
            "workflow" to JsonPrimitive(request.workflowName),
            "execution_id" to JsonPrimitive(request.executionId),
            "step" to JsonPrimitive(request.stepName),
            "message" to JsonPrimitive(request.message),
            "approvers" to JsonArray(request.approvers.map { JsonPrimitive(it) }),
            "requested_at" to JsonPrimitive(request.requestedAt),
            "timeout_seconds" to JsonPrimitive(request.timeout),
            "auto_approve_after_seconds" to (autoAfterMillis?.let { JsonPrimitive(it / 1_000) } ?: JsonNull),
            "decision_file" to JsonPrimitive(decisionFile.absolutePath),
            "decision_schema" to JsonPrimitive("{\"approved\": true|false, \"comment\": \"...\", \"edits\": {\"<group>\": [items]}}"),
            "reviewable_groups" to JsonArray(
                request.reviewableGroups.map { group ->
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(group.name),
                            "title" to (group.title?.let(::JsonPrimitive) ?: JsonNull),
                            "source_var" to JsonPrimitive(group.sourceVar),
                            "output_var" to JsonPrimitive(group.outputVar),
                            "source_path" to (group.sourcePath?.let(::JsonPrimitive) ?: JsonNull),
                            "items" to JsonArray(group.items)
                        )
                    )
                }
            )
        )
    )

    companion object {
        fun safeId(approvalId: String): String = approvalId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
