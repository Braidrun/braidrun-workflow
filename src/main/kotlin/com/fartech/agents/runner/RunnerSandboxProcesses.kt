package com.fartech.agents.runner

import com.fartech.agents.tools.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Explicitly injected per frozen Agent step. There is no native-process fallback. */
class RunnerSandboxProcesses internal constructor(
    private val files: ToolFileAccess,
    private val stepName: String,
    private val childExecutionPath: String,
    private val declaredGroups: Set<String>,
    private val checkLive: () -> Unit,
    private val exchange: suspend (JsonObject, Long) -> JsonObject
) : SandboxToolExecutor {
    constructor(ipc: RunnerIpc, gate: RunnerEnvironmentGate, files: ToolFileAccess, stepName: String,
                childExecutionPath: String, declaredGroups: Set<String>, liveGuard: () -> Unit) :
        this(files, stepName, childExecutionPath, declaredGroups,
            { liveGuard(); ipc.businessFence.check(); gate.check(); check(ipc.agentSandboxNegotiated) { "Sandbox protocol not negotiated" }
                require(ipc.agentSandboxGroups.containsAll(declaredGroups)) { "Sandbox tool group was not negotiated" }
                RunnerSandboxPolicy.executionParts(ipc.binding.string("executionId"), childExecutionPath); Unit },
            { payload, timeout -> ipc.request("sandboxProcessLaunch", payload, "sandboxProcessResult", timeout) })

    override suspend fun execute(request: SandboxToolRequest): String {
        checkLive()
        require(request.toolGroup in declaredGroups) { "Sandbox tool was not explicitly declared by the frozen Agent step" }
        require(when (request.toolGroup) {
            "shell" -> request.runtime == "shell"
            "code_execution" -> request.runtime in setOf("python", "javascript")
            "git" -> request.runtime == "git"
            else -> false
        }) { "Unsupported sandbox tool runtime" }
        require(request.timeoutSeconds in 1..300)
        require(request.source.toByteArray().size <= SOURCE_BYTES && '\u0000' !in request.source)
        require(request.arguments.size <= 64 && request.arguments.all { it.toByteArray().size <= 4096 && '\u0000' !in it })
        require(request.arguments.sumOf { it.toByteArray().size } <= SOURCE_BYTES)
        if (request.runtime == "git") require(request.source.isEmpty() && request.arguments.firstOrNull() in SandboxGitTools.LOCAL_OPERATIONS)
        else require(request.arguments.isEmpty())
        val inputs = paths(request.inputFiles); val outputs = paths(request.outputFiles)
        var totalInput = 0
        val snapshots = inputs.map { path ->
            checkLive()
            val bytes = files.readBytes(path, FILE_BYTES)
            require(bytes.size <= FILE_BYTES); totalInput += bytes.size
            require(totalInput <= FILE_BYTES) { "Sandbox input byte limit exceeded" }
            buildJsonObject { put("path", path); put("contentBase64", Base64.getEncoder().encodeToString(bytes)); put("sha256", sha256(bytes)) }
        }
        val id = UUID.randomUUID().toString()
        checkLive()
        val result = exchange(buildJsonObject {
            put("requestId", id); put("stepName", stepName); put("childExecutionPath", childExecutionPath)
            put("toolGroup", request.toolGroup); put("runtime", request.runtime); put("source", request.source)
            put("arguments", JsonArray(request.arguments.map(::JsonPrimitive))); put("inputs", JsonArray(snapshots))
            put("outputs", JsonArray(outputs.map(::JsonPrimitive))); put("network", "NONE"); put("interactive", false)
            put("timeoutSeconds", request.timeoutSeconds)
        }, (request.timeoutSeconds + 30) * 1000)
        checkLive()
        require(result.string("requestId") == id && result.string("cleanup") == "STOP_CONFIRMED") { "Sandbox cleanup was not confirmed" }
        val exitCode = result.long("exitCode").toInt()
        val stdout = result.string("stdout"); val stderr = result.string("stderr")
        require(stdout.toByteArray().size <= CAPTURE_BYTES && stderr.toByteArray().size <= CAPTURE_BYTES)
        val returned = result.getValue("outputs").jsonArray
        require(returned.size <= outputs.size)
        val seen = mutableSetOf<String>(); var totalOutput = 0
        // Validate every returned byte before publishing any of them. The content is untrusted tool data.
        val writes = returned.map { item ->
            val file = item.jsonObject; val path = file.string("path")
            require(exitCode == 0 && path in outputs && seen.add(path)) { "Unrequested sandbox output" }
            val encoded = file.string("contentBase64"); require(encoded.length <= (FILE_BYTES + 2) / 3 * 4)
            val bytes = Base64.getDecoder().decode(encoded); totalOutput += bytes.size
            require(totalOutput <= FILE_BYTES && sha256(bytes) == file.string("sha256")) { "Sandbox output byte/hash mismatch" }
            path to bytes
        }
        if (exitCode == 0) require(seen == outputs.toSet()) { "Missing declared sandbox output" }
        writes.forEach { (path, bytes) -> checkLive(); files.writeBytes(path, bytes) }
        checkLive()
        return buildJsonObject {
            put("exitCode", exitCode); put("stdout", stdout); put("stderr", stderr)
            put("savedFiles", JsonArray(writes.map { JsonPrimitive(it.first) }))
        }.toString().also { require(it.toByteArray().size <= RETURN_BYTES) }
    }
    companion object {
        const val SOURCE_BYTES = 64 * 1024
        const val FILE_BYTES = 128 * 1024
        const val CAPTURE_BYTES = 64 * 1024
        const val RETURN_BYTES = 1024 * 1024 // Includes worst-case JSON escaping of both bounded captures.
        internal fun paths(paths: List<String>): List<String> {
            require(paths.size <= 32 && paths.toSet().size == paths.size)
            paths.forEach { path -> require(path.isNotEmpty() && path.toByteArray().size <= 256 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) }
            require(paths.none { path -> paths.any { it != path && it.startsWith("$path/") } })
            return paths
        }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
