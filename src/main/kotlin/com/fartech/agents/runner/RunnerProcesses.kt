package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Launch requests go to the supervisor; this class never invokes ProcessBuilder. */
class RunnerProcesses(
    private val ipc: RunnerIpc,
    private val root: Path,
    private val gate: RunnerEnvironmentGate,
    private val readArtifact: suspend (String) -> String
) : SubprocessExecutor {
    private val exits = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val received = ConcurrentHashMap<String, JsonObject>()
    private val registrations = ConcurrentHashMap.newKeySet<String>()
    fun exited(payload: JsonObject) {
        val id = payload.string("registrationId")
        if (received.size >= 128) throw RunnerProtocolException()
        val previous = received.putIfAbsent(id, payload)
        if (previous != null && previous != payload) throw RunnerProtocolException()
        exits[id]?.complete(payload)
    }
    override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
        gate.check()
        require(request.timeoutSeconds in 1..86400) { "Unsupported process timeout" }
        require(request.stdin?.toByteArray()?.size?.let { it <= RunnerIpc.MAX_FRAME_BYTES / 2 } != false) { "Stdin exceeds IPC budget" }
        val base = root.toAbsolutePath().normalize()
        val working = request.workingDir.toPath().toAbsolutePath().normalize()
        require(working.startsWith(base)) { "Runner cwd escapes execution directory" }
        val relative = base.relativize(working).toString().ifBlank { "." }
        if (relative != ".") confinedPath(base, relative)
        val prepared = request.preparedForStart()
        gate.check()
        val requestId = UUID.randomUUID().toString(); val startedAt = System.nanoTime()
        val started = ipc.request("processLaunch", buildJsonObject {
            put("requestId", requestId); put("argv", JsonArray(prepared.command.map(::JsonPrimitive)))
            put("relativeCwd", relative); put("environment", JsonObject(prepared.env.mapValues { JsonPrimitive(it.value) }))
            put("purpose", prepared.admissionKind); put("stdin", prepared.stdin?.let(::JsonPrimitive) ?: JsonNull)
            put("timeoutSeconds", request.timeoutSeconds); put("captureLimitBytes", RunnerIpc.MAX_FRAME_BYTES / 4)
        }, "processStarted", minOf(request.timeoutSeconds * 1000, 30_000))
        require(started.string("requestId") == requestId && started.long("pid") > 0 && started.long("processGroupId") > 0) { "Supervisor launch registration mismatch" }
        val registrationId = started.string("registrationId")
        check(registrations.add(registrationId)) { "Process registration identity reused" }
        val deferred = CompletableDeferred<JsonObject>()
        check(exits.putIfAbsent(registrationId, deferred) == null) { "Duplicate process waiter" }
        received[registrationId]?.let(deferred::complete)
        try {
            val exited = withTimeout(request.timeoutSeconds * 1000) { deferred.await() }
            require(exited["timedOut"] != JsonPrimitive(true)) { "Supervisor process timed out" }
            val truncated = exited["captureTruncated"] == JsonPrimitive(true)
            val stdout = if (!truncated) exited.string("stdout") else exited["stdoutArtifactId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { readArtifact(it) }.orEmpty()
            val stderr = if (!truncated) exited.string("stderr") else exited["stderrArtifactId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { readArtifact(it) }.orEmpty()
            if (truncated) require(exited["stdoutArtifactId"] != JsonNull && exited["stderrArtifactId"] != JsonNull) { "Truncated captures need authorized artifact resolution" }
            require(stdout.toByteArray().size <= SubprocessExecutor.MAX_STREAM_BYTES && stderr.toByteArray().size <= SubprocessExecutor.MAX_STREAM_BYTES) { "Supervisor stream exceeds cap" }
            stdout.lineSequence().forEach { prepared.stdoutLineCallback?.invoke(it) }
            prepared.confirmSettled()
            return SubprocessExecutor.ExecResult(exited.long("exitCode").toInt(), stdout, stderr, (System.nanoTime() - startedAt) / 1_000_000)
        } finally {
            exits.remove(registrationId); received.remove(registrationId)
            // Timeout/cancellation never confirms settled. Supervisor root-stop handles the process.
        }
    }
}
