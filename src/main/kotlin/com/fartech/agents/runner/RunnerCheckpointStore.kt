package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Runtime-local format 1, announcement uses the official checkpoint DTO. No secrets in state. */
class RunnerCheckpointStore(private val root: Path, private val binding: JsonObject, private val secrets: RunnerSecrets) {
    private val mapper = jacksonObjectMapper()
    fun save(checkpoint: WorkflowHostCheckpoint, eventSeq: Long): JsonObject {
        val state = runnerJson.parseToJsonElement(mapper.writeValueAsString(checkpoint.state))
        require(!secrets.containsSecret(state)) { "Credential material cannot be checkpointed" }
        // JSON primitives/collections only: serializing an arbitrary object as a string changes
        // behavior on restoration. Reject unsupported runtime state instead of claiming recovery.
        validateVariables(checkpoint.state.variables)
        validateVariables(checkpoint.state.hostState)
        val id = UUID.randomUUID().toString()
        val value = buildJsonObject {
            put("formatVersion", 1); put("binding", binding); put("executionDirectory", root.toAbsolutePath().normalize().toString()); put("workflowName", checkpoint.workflowName)
            put("executionId", checkpoint.scope.executionId); put("safeToResume", checkpoint.safeToResume)
            put("state", state)
            put("pendingApprovals", JsonArray(checkpoint.pendingApprovals.map { request -> buildJsonObject {
                put("approvalId", request.approvalId); put("occurrenceId", request.occurrenceId?.let(::JsonPrimitive) ?: JsonNull); put("workflowName", request.workflowName); put("executionId", request.executionId)
                put("stepName", request.stepName); put("message", request.message); put("approvers", JsonArray(request.approvers.map(::JsonPrimitive)))
                put("requestedAt", request.requestedAt); put("timeout", request.timeout?.let(::JsonPrimitive) ?: JsonNull)
                put("reviewableGroups", JsonArray(request.reviewableGroups.map { group -> buildJsonObject {
                    put("name", group.name); put("sourceVar", group.sourceVar); put("outputVar", group.outputVar)
                    put("title", group.title?.let(::JsonPrimitive) ?: JsonNull); put("keyField", group.keyField?.let(::JsonPrimitive) ?: JsonNull)
                    put("sourcePath", group.sourcePath?.let(::JsonPrimitive) ?: JsonNull)
                    put("columns", JsonArray(group.columns.map { runnerJson.encodeToJsonElement(ReviewableActionColumn.serializer(), it) })); put("items", JsonArray(group.items))
                } }))
            } }))
        }
        require(!secrets.containsSecret(value)) { "Secrets cannot be in approval state" }
        val relative = ".runner-state/checkpoint-$id.json"
        writePrivate(relative, value.toString().toByteArray(Charsets.UTF_8))
        return buildJsonObject {
            put("checkpointId", id); put("checkpointHash", sha256(value.toString().toByteArray(Charsets.UTF_8)))
            put("relativePath", relative); put("eventSeq", eventSeq); put("formatVersion", 1)
        }
    }
    fun restore(announcement: JsonObject, previousBinding: JsonObject): WorkflowResumeState {
        RunnerProtocol().validate(announcement, "checkpoint")
        RunnerProtocol().validate(previousBinding, "binding")
        val bytes = Files.readAllBytes(confinedPath(root, announcement.string("relativePath")))
        require(bytes.size <= MAX_CHECKPOINT_BYTES && sha256(bytes) == announcement.string("checkpointHash")) { "Checkpoint integrity failure" }
        val value = runnerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(value.long("formatVersion") == 1L && value.obj("binding") == previousBinding && value["safeToResume"] == JsonPrimitive(true)) { "Checkpoint requires reconciliation, not replay" }
        require(value.string("executionDirectory") == root.toAbsolutePath().normalize().toString()) { "Checkpoint directory cannot be relocated implicitly" }
        require(value.string("executionId") == binding.string("executionId")) { "Nested checkpoint cannot restart a root" }
        for (key in listOf("ownerId", "actorUserId", "executionId", "runnerId", "definitionHash", "runtimeHash")) {
            require(binding[key] == previousBinding[key]) { "Checkpoint identity mismatch" }
        }
        require(binding.long("resumeEpoch") > previousBinding.long("resumeEpoch") && binding["attemptId"] != previousBinding["attemptId"] && binding["runnerInstanceId"] != previousBinding["runnerInstanceId"]) { "Resume requires new fenced identity" }
        val state = mapper.readValue(value.getValue("state").toString(), WorkflowResumeState::class.java)
        // Older format-1 savepoints already retained the full original pending request outside state.
        // Recover that exact timestamp; never invent a new 259200-second window for an existing key.
        val requestedAt = state.pendingApprovalRequestedAt.toMutableMap()
        state.pendingApprovalIds.forEach { (step, id) ->
            val original = value.array("pendingApprovals").map { it.jsonObject }.singleOrNull {
                it.string("approvalId") == id && it.string("stepName") == step
            }
            original?.let {
                require(it.string("executionId") == binding.string("executionId") && it.long("requestedAt") > 0)
                require(requestedAt[step]?.let { old -> old == it.long("requestedAt") } != false)
                requestedAt[step] = it.long("requestedAt")
            }
            requireNotNull(requestedAt[step]) { "Original approval timestamp unavailable; reconcile without resetting deadline" }
        }
        return state.copy(pendingApprovalRequestedAt = requestedAt)
    }
    fun writePrivate(relative: String, bytes: ByteArray): String {
        require(bytes.size <= MAX_CHECKPOINT_BYTES) { "Runner state exceeds bounded size" }
        val path = confinedPath(root, relative)
        Files.createDirectories(path.parent)
        Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwx------"))
        val temp = Files.createTempFile(path.parent, ".runner-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(path.parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
        finally { Files.deleteIfExists(temp) }
        return sha256(bytes)
    }
    /** A file this store wrote, or null when absent; never follows a planted link. */
    fun readPrivate(relative: String): ByteArray? {
        val path = confinedPath(root, relative)
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
        return Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes(MAX_CHECKPOINT_BYTES + 1).also { require(it.size <= MAX_CHECKPOINT_BYTES) { "Runner state exceeds bounded size" } }
        }
    }
    private fun validateVariables(value: Any?) {
        when (value) {
            null, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> Unit
            is Map<*, *> -> value.forEach { (k,v) -> require(k is String) { "Unsupported checkpoint key" }; validateVariables(v) }
            is List<*> -> value.forEach(::validateVariables)
            else -> error("Unsupported typed checkpoint value")
        }
    }
    companion object { const val MAX_CHECKPOINT_BYTES = 16 * 1024 * 1024 }
}
