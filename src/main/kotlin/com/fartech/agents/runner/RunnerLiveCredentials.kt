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
    class Handle internal constructor(val latest: AtomicReference<RunnerCredentialLease>, internal val job: AtomicReference<Job?> = AtomicReference()) {
        internal val expiryJob = AtomicReference<Job?>()
        internal val active = java.util.concurrent.atomic.AtomicBoolean(true)
    }
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
        handle.expiryJob.set(scope.launch {
            while (isActive && handle.active.get()) {
                val remaining = synchronized(handle) { credentials.remainingNanos(handle.latest.get()) }
                if (remaining <= 0) {
                    synchronized(handle) {
                        if (handle.active.get() && credentials.remainingNanos(handle.latest.get()) <= 0) {
                            handle.active.set(false); gate.invalidate("CREDENTIAL_GRANT_EXPIRED")
                        }
                    }
                    return@launch
                }
                delay(((remaining + 999_999) / 1_000_000).coerceAtLeast(1))
            }
        })
        handle.job.set(scope.launch {
            try {
                while (isActive) {
                    val old = handle.latest.get()
                    val remaining = credentials.remainingNanos(old)
                    if (remaining <= 0) throw RunnerAuthorityLost()
                    delay(minOf(renewalIntervalMs, (remaining / 3 / 1_000_000).coerceAtLeast(1)))
                    gate.check()
                    val next = credentials.acquire(ref, old.version, executionScope, purpose)
                    synchronized(handle) {
                        if (!handle.active.get()) {
                            credentials.discard(next); return@launch
                        }
                        if (credentials.remainingNanos(old) <= 0) {
                            credentials.discard(next); gate.invalidate("CREDENTIAL_RENEWAL_AFTER_EXPIRY"); throw RunnerAuthorityLost()
                        }
                        gate.check()
                        if (next.material != old.material) throw RunnerAuthorityLost()
                        handle.latest.set(next); credentials.discard(old)
                    }
                }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive && handle.active.get()) gate.invalidate("CREDENTIAL_PERMISSION_RENEWAL_FAILED")
                throw e
            } catch (_: Exception) { if (handle.active.get()) gate.invalidate("CREDENTIAL_PERMISSION_RENEWAL_FAILED") }
        })
        return handle
    }
    fun checked(handle: Handle, scope: WorkflowHostScope, operation: String): JsonObject {
        require(handle.active.get()) { "Credential generation is no longer active" }
        return credentials.material(handle.latest.get(), scope, operation)
    }
    fun checkAll() { handles.values.forEach { val lease = it.latest.get(); credentials.material(lease, lease.scope, lease.operations.first()) } }
    suspend fun rotate(handle: Handle, executionScope: WorkflowHostScope, material: JsonObject) {
        handle.job.getAndSet(null)?.cancelAndJoin()
        val old = handle.latest.get()
        val cleanReceipt = preserveRotation?.invoke(old, material)
        credentials.rotate(old, executionScope, material)
        cleanReceipt?.invoke()
        handle.active.set(false); handle.expiryJob.getAndSet(null)?.cancelAndJoin()
        versions[old.credentialRef] = old.version + 1
        handles.entries.removeIf { it.value === handle }
    }
    /** Stop the abandoned card before another card starts. Wait for in-flight renewal to settle. */
    suspend fun release(handle: Handle): Boolean {
        synchronized(handle) { handle.active.set(false) }
        handle.job.getAndSet(null)?.cancelAndJoin()
        handle.expiryJob.getAndSet(null)?.cancelAndJoin()
        handles.entries.removeIf { it.value === handle }
        val old = handle.latest.get()
        credentials.discard(old)
        return handles.values.none { other -> other.active.get() && other.latest.get().let {
            it.credentialRef == old.credentialRef && it.scope.executionId == old.scope.executionId && it.operations == old.operations
        } }
    }
    fun release(executionId: String, step: String) {
        handles.entries.filter { it.value.latest.get().scope.let { s -> s.executionId == executionId && (s.stepName == step || s.stepName?.startsWith("$step:") == true) } }
            .forEach { (key, handle) -> handle.active.set(false); handle.job.get()?.cancel(); handle.expiryJob.get()?.cancel(); handles.remove(key, handle); credentials.discard(handle.latest.get()) }
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
    override fun close() { handles.values.forEach { it.active.set(false) }; scope.cancel(); handles.clear() }
}
