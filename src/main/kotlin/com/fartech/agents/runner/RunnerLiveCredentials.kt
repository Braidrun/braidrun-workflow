package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Renews scoped execution permission while a model/CLI remains active. Renewal does not
 * refresh the provider's token or delegate refresh to the platform. Changed vault versions
 * or material invalidate the attempt; only acknowledged local rotation advances the cursor.
 */
class RunnerLiveCredentials(
    private val credentials: RunnerCredentials,
    private val gate: RunnerEnvironmentGate,
    private val renewalIntervalMs: Long = 15_000,
    private val preserveRotation: ((RunnerCredentialLease, JsonObject) -> (() -> Unit))? = null
) : AutoCloseable {
    class Handle internal constructor(val latest: AtomicReference<RunnerCredentialLease>, internal val job: AtomicReference<Job?> = AtomicReference())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = ConcurrentHashMap<String, Handle>()
    private val plans = ConcurrentHashMap<String, Long>()
    private val versions = ConcurrentHashMap<String, Long>()
    private fun key(ref: String, scope: WorkflowHostScope, purpose: String) = "$ref:$purpose:${scope.executionId}:${scope.stepName}"
    suspend fun acquire(reference: String, executionScope: WorkflowHostScope, purpose: String): Handle {
        val parsed = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(reference) ?: error("Credential reference required")
        val ref = parsed.groupValues[1]; val initial = parsed.groupValues[2].toLong()
        require(plans.putIfAbsent(ref, initial)?.let { it == initial } != false) { "Conflicting pinned credential plans" }
        versions.putIfAbsent(ref, initial)
        val k = key(ref, executionScope, purpose)
        handles[k]?.let { checked(it, executionScope, purpose); return it }
        val lease = credentials.acquire(ref, versions.getValue(ref), executionScope, purpose)
        val handle = Handle(AtomicReference(lease))
        if (handles.putIfAbsent(k, handle) != null) return handles.getValue(k)
        handle.job.set(scope.launch {
            try {
                while (isActive) {
                    delay(renewalIntervalMs)
                    val old = handle.latest.get()
                    gate.check()
                    val next = credentials.acquire(ref, old.version, executionScope, purpose)
                    if (next.material != old.material) throw RunnerAuthorityLost()
                    handle.latest.set(next); credentials.discard(old)
                }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) gate.invalidate("CREDENTIAL_PERMISSION_RENEWAL_FAILED")
                throw e
            } catch (_: Exception) { gate.invalidate("CREDENTIAL_PERMISSION_RENEWAL_FAILED") }
        })
        return handle
    }
    fun checked(handle: Handle, scope: WorkflowHostScope, operation: String): JsonObject = credentials.material(handle.latest.get(), scope, operation)
    fun checkAll() { handles.values.forEach { val lease = it.latest.get(); credentials.material(lease, lease.scope, lease.operations.first()) } }
    suspend fun rotate(handle: Handle, executionScope: WorkflowHostScope, material: JsonObject) {
        handle.job.getAndSet(null)?.cancelAndJoin()
        val old = handle.latest.get()
        val cleanReceipt = preserveRotation?.invoke(old, material)
        credentials.rotate(old, executionScope, material)
        cleanReceipt?.invoke()
        versions[old.credentialRef] = old.version + 1
        handles.entries.removeIf { it.value === handle }
    }
    fun release(executionId: String, step: String) {
        handles.entries.filter { it.value.latest.get().scope.let { s -> s.executionId == executionId && (s.stepName == step || s.stepName?.startsWith("$step:") == true) } }
            .forEach { (key, handle) -> handle.job.get()?.cancel(); handles.remove(key, handle); credentials.discard(handle.latest.get()) }
    }
    fun state(): Map<String, Any> = mapOf("credentialPlans" to plans.toMap(), "credentialVersions" to versions.toMap())
    fun restore(state: Map<String, Any>) {
        fun load(key: String, target: MutableMap<String, Long>) {
            (state[key] as? Map<*, *>)?.forEach { (ref, version) ->
                require(ref is String && version is Number && version.toLong() >= 0) { "Invalid credential cursor state" }
                target[ref] = version.toLong()
            }
        }
        load("credentialPlans", plans); load("credentialVersions", versions)
    }
    override fun close() { scope.cancel(); handles.clear() }
}
