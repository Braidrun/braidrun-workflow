package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The supervisor is the sole slot/tokenizer/stop authority. A service's done=true has no
 * release semantics here. Lost/late replies, stale health or a closed attempt refuse output.
 * The reader thread stays free for cancellation while these requests await their replies.
 */
class RunnerIpcLocalModelAuthority(
    private val ipc: RunnerIpc,
    private val checkLive: () -> Unit,
) : RunnerLocalModelAuthority, AutoCloseable {
    private val closed = AtomicBoolean()
    private val grants = ConcurrentHashMap.newKeySet<Grant>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun check() { check(!closed.get()) { "Local authority closed" }; ipc.businessFence.check(); checkLive() }
    override suspend fun reserve(binding: RunnerLocalModelBinding, requestId: String, request: RunnerLocalModelRequest): RunnerLocalModelGrant {
        check()
        val pin = RunnerLocalModelProtocol.json(binding)
        require(listOf("runnerId", "ownerId", "executionId", "attemptId").all { pin[it] == ipc.binding[it] }) { "Local attempt mismatch" }
        val payload = buildJsonObject {
            put("binding", pin); put("requestId", requestId); put("stepName", request.stepName)
            put("childExecutionPath", JsonArray(request.childExecutionPath.map(::JsonPrimitive)))
            put("messages", JsonArray(request.messages.map { buildJsonObject { put("role", it.role); put("content", it.content) } }))
            put("outputTokens", request.outputTokens); put("jsonObject", request.jsonObject)
        }
        val reply = ipc.request("localModelReserve", payload, "localModelGrant", 120_000)
        require(reply.obj("binding") == pin && reply.string("requestId") == requestId && reply.string("state") == "RESERVED") { "Local grant mismatch" }
        check()
        return Grant(binding, requestId).also { grants.add(it); it.start() }
    }
    private inner class Grant(override val binding: RunnerLocalModelBinding, override val requestId: String) : RunnerLocalModelGrant {
        private val finished = AtomicBoolean()
        private val healthyUntil = AtomicLong(System.nanoTime() + FRESHNESS_NANOS)
        private val valid = AtomicBoolean(true)
        private lateinit var watcher: Job
        private fun payload() = buildJsonObject { put("binding", RunnerLocalModelProtocol.json(binding)); put("requestId", requestId) }
        fun start() {
            watcher = scope.launch {
                try {
                    while (isActive) {
                        delay(250)
                        status(ipc.request("localModelCheck", payload(), "localModelStatus", 1_000), setOf("RESERVED", "STARTED"))
                        healthyUntil.set(System.nanoTime() + FRESHNESS_NANOS)
                    }
                } catch (e: Exception) { valid.set(false) }
            }
        }
        private fun status(value: JsonObject, states: Set<String>) {
            require(value.obj("binding") == RunnerLocalModelProtocol.json(binding) && value.string("requestId") == requestId && value.string("state") in states) { "Local status mismatch" }
        }
        override fun check() {
            this@RunnerIpcLocalModelAuthority.check()
            check(valid.get() && !finished.get() && System.nanoTime() < healthyUntil.get()) { "Local supervisor authority stale" }
        }
        override suspend fun markInferenceMayHaveStarted() {
            check()
            status(ipc.request("localModelStarted", payload(), "localModelStatus", 1_000), setOf("STARTED"))
            check()
        }
        override suspend fun finish(observation: RunnerLocalStopObservation): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            watcher.cancelAndJoin()
            return try {
                this@RunnerIpcLocalModelAuthority.check()
                val response = ipc.request("localModelFinish", JsonObject(payload() + ("observation" to JsonPrimitive(observation.name))), "localModelStatus", 30_000)
                status(response, setOf("RELEASED", "QUARANTINED"))
                response.string("state") == "RELEASED" && response["released"] == JsonPrimitive(true)
            } finally { grants.remove(this) }
        }
        fun close() { valid.set(false); if (::watcher.isInitialized) watcher.cancel() }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) { grants.forEach { it.close() }; grants.clear(); scope.cancel() }
        // No synthetic finish: the supervisor quarantines every unresolved reservation on exit.
    }
    companion object { private const val FRESHNESS_NANOS = 1_500_000_000L }
}
