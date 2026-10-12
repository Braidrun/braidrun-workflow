package com.fartech.agents.runner

import com.fartech.agents.workflow.ApprovalRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Private local retention of the original normal approval frame. It confers no scan or human authority. */
class RunnerApprovalJournal(
    private val root: Path, private val binding: JsonObject,
    private val store: RunnerCheckpointStore, private val secrets: RunnerSecrets
) {
    private val mapper = jacksonObjectMapper()
    private fun path(request: ApprovalRequest) = ".runner-state/approval-requests/${sha256((canonical(binding) + request.approvalId).toByteArray())}.json"
    private fun context(request: ApprovalRequest) = runnerJson.parseToJsonElement(mapper.writeValueAsString(request)).jsonObject
    private fun read(path: String): JsonObject? {
        val file = confinedPath(root, path)
        if (!Files.exists(file)) return null
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= RunnerCheckpointStore.MAX_CHECKPOINT_BYTES)
        return runnerJson.parseToJsonElement(Files.readString(file)).jsonObject
    }
    private fun write(path: String, value: JsonObject) {
        require(!secrets.containsSecret(contentOf(value, IDENTITY, ENVELOPES), control = emptySet())) { "Approval journal contains credential material" }
        store.writePrivate(path, canonical(value).toByteArray())
    }
    @Synchronized fun prepare(request: ApprovalRequest, payload: JsonObject): JsonObject {
        val c = context(request); val relative = path(request)
        val saved = read(relative)
        if (saved != null) {
            require(saved.obj("binding") == binding && saved.obj("request") == c) { "Original approval context changed" }
            val frame = saved.obj("frame"); RunnerProtocol().validate(frame, "ipcFrame")
            require(frame.string("type") == "approval" && frame.obj("binding") == binding && frame.obj("payload") == payload)
            return frame
        }
        val frame = buildJsonObject {
            put("protocolVersion", 1); put("frameId", UUID.randomUUID().toString()); put("type", "approval"); put("binding", binding); put("payload", payload)
        }
        RunnerProtocol().validate(frame, "ipcFrame")
        write(relative, buildJsonObject { put("binding", binding); put("request", c); put("frame", frame) })
        return frame
    }
    @Synchronized fun retainDecision(request: ApprovalRequest, frame: JsonObject, decision: JsonObject) {
        val relative = path(request); val saved = requireNotNull(read(relative))
        require(saved.obj("binding") == binding && saved.obj("request") == context(request) && saved.obj("frame") == frame)
        saved["decision"]?.let { require(it == decision) { "Original human decision changed" } }
        write(relative, JsonObject(saved + ("decision" to decision)))
    }
    private companion object {
        /** Platform and engine identities of the request, its frame and its decision; never credential material. */
        val IDENTITY = setOf("binding", "protocolVersion", "frameId", "type", "approvalId", "occurrenceId", "idempotencyKey", "executionId",
            "workflowName", "stepName", "stepId", "childExecutionPath", "approvers", "decidedBy")
        val ENVELOPES = setOf("request", "frame", "payload", "decision")
    }
}
