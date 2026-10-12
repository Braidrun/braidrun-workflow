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
    private val preserveRotation: ((RunnerCredentialLease, JsonObject) -> (() -> Unit))? = null,
    private val onReleased: (RunnerCredentialLease, String) -> Unit = { _, _ -> }
) : AutoCloseable {
    class Handle internal constructor(val latest: AtomicReference<RunnerCredentialLease>, internal val purpose: String, internal val job: AtomicReference<Job?> = AtomicReference()) {
        internal val expiryJob = AtomicReference<Job?>()
        internal val active = java.util.concurrent.atomic.AtomicBoolean(true)
        /** The terminal RELEASED of [latest] is reported once, by whichever path retires the handle first. */
        internal val retired = java.util.concurrent.atomic.AtomicBoolean(false)
        /** Invocations currently holding this handle through [hold]. Guarded by the handle's monitor. */
        internal var holders = 0
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = ConcurrentHashMap<String, Handle>()
    private val plans = ConcurrentHashMap<String, Long>()
    private val versions = ConcurrentHashMap<String, Long>()
    private fun key(ref: String, scope: WorkflowHostScope, purpose: String) = "$ref:$purpose:${scope.executionId}:${scope.stepName}"
    /** Shared, step-lived permission (model keys, MCP tokens): retired only at step end. */
    suspend fun acquire(reference: String, executionScope: WorkflowHostScope, purpose: String): Handle =
        obtain(reference, executionScope, purpose, hold = false)
    /** One invocation's hold on the shared handle. Each hold is ended exactly once with [drop];
     * the grant is released only when the last concurrent holder drops it. */
    suspend fun hold(reference: String, executionScope: WorkflowHostScope, purpose: String): Handle =
        obtain(reference, executionScope, purpose, hold = true)
    private suspend fun obtain(reference: String, executionScope: WorkflowHostScope, purpose: String, hold: Boolean): Handle {
        val parsed = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(reference) ?: error("Credential reference required")
        val ref = parsed.groupValues[1]; val initial = parsed.groupValues[2].toLong()
        require(plans.putIfAbsent(ref, initial)?.let { it == initial } != false) { "Conflicting pinned credential plans" }
        versions.putIfAbsent(ref, initial)
        val k = key(ref, executionScope, purpose)
        while (true) {
            handles[k]?.let { existing ->
                // Join only a live handle, atomically with its last holder's drop: a retiring
                // handle is never handed to a new invocation.
                val joined = synchronized(existing) { existing.active.get().also { if (it && hold) existing.holders++ } }
                if (!joined) { handles.remove(k, existing); return@let }
                try { checked(existing, executionScope, purpose) } catch (failure: Exception) {
                    if (hold) drop(existing)
                    throw failure
                }
                return existing
            }
            if (handles[k] != null) continue
            val lease = credentials.acquire(ref, versions.getValue(ref), executionScope, purpose)
            val handle = Handle(AtomicReference(lease), purpose)
            if (hold) handle.holders = 1
            if (handles.putIfAbsent(k, handle) != null) {
                // Another invocation published a handle first; release this redundant grant and join that one.
                handle.active.set(false); handle.retired.set(true); discard(handle, lease); continue
            }
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
            renew(handle)
            return handle
        }
    }
    private fun renew(handle: Handle) {
        val ref = handle.latest.get().credentialRef
        val executionScope = handle.latest.get().scope
        val purpose = handle.purpose
        handle.job.set(scope.launch {
            try {
                while (isActive && handle.active.get()) {
                    val old = handle.latest.get()
                    val remaining = credentials.remainingNanos(old)
                    if (remaining <= 0) throw RunnerAuthorityLost()
                    delay(minOf(renewalIntervalMs, (remaining / 3 / 1_000_000).coerceAtLeast(1)))
                    gate.check()
                    val next = credentials.acquire(ref, old.version, executionScope, purpose)
                    synchronized(handle) {
                        if (!handle.active.get()) {
                            discard(handle, next); return@launch
                        }
                        if (credentials.remainingNanos(old) <= 0) {
                            discard(handle, next); gate.invalidate("CREDENTIAL_RENEWAL_AFTER_EXPIRY"); throw RunnerAuthorityLost()
                        }
                        gate.check()
                        if (next.material != old.material) throw RunnerAuthorityLost()
                        handle.latest.set(next); discard(handle, old)
                    }
                }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive && handle.active.get()) gate.invalidate("CREDENTIAL_PERMISSION_RENEWAL_FAILED")
                throw e
            } catch (_: Exception) { if (handle.active.get()) gate.invalidate("CREDENTIAL_PERMISSION_RENEWAL_FAILED") }
        })
    }
    fun checked(handle: Handle, scope: WorkflowHostScope, operation: String): JsonObject {
        require(handle.active.get()) { "Credential generation is no longer active" }
        return credentials.material(handle.latest.get(), scope, operation)
    }
    fun checkAll() { handles.values.forEach { val lease = it.latest.get(); credentials.material(lease, lease.scope, lease.operations.first()) } }
    /**
     * Publishes locally refreshed material. When other invocations still hold this handle, they
     * keep running on the same account, so the handle continues under a grant for the new vault
     * version instead of leaving them without any live permission.
     */
    suspend fun rotate(handle: Handle, executionScope: WorkflowHostScope, material: JsonObject) {
        handle.job.getAndSet(null)?.cancelAndJoin()
        val old = handle.latest.get()
        val cleanReceipt = preserveRotation?.invoke(old, material)
        credentials.rotate(old, executionScope, material)
        cleanReceipt?.invoke()
        versions[old.credentialRef] = old.version + 1
        if (synchronized(handle) { handle.active.get() && handle.holders > 1 }) {
            val next = try { credentials.acquire(old.credentialRef, old.version + 1, old.scope, handle.purpose) } catch (failure: Exception) {
                // Concurrent holders would otherwise keep using the account without permission.
                gate.invalidate("CREDENTIAL_PERMISSION_RENEWAL_FAILED")
                finishRotation(handle, old); throw failure
            }
            val continued = synchronized(handle) {
                if (handle.active.get()) { handle.latest.set(next); true } else { discard(handle, next); false }
            }
            if (continued) { onReleased(old, handle.purpose); renew(handle); return }
        }
        finishRotation(handle, old)
    }
    private suspend fun finishRotation(handle: Handle, rotated: RunnerCredentialLease) = withContext(NonCancellable) {
        synchronized(handle) { handle.active.set(false) }
        handle.expiryJob.getAndSet(null)?.cancelAndJoin()
        handles.entries.removeIf { it.value === handle }
        // The rotated lease already left the local lease table; report its RELEASED exactly once.
        if (handle.retired.compareAndSet(false, true)) onReleased(rotated, handle.purpose)
    }
    /** Stop the abandoned card before another card starts. Wait for in-flight renewal to settle. */
    private fun discard(handle: Handle, lease: RunnerCredentialLease) {
        credentials.discard(lease)
        onReleased(lease, handle.purpose)
    }
    /** Terminal release of the handle's current grant. Callers hold the monitor or have joined renewal. */
    private fun retire(handle: Handle) {
        synchronized(handle) {
            handle.active.set(false)
            if (handle.retired.compareAndSet(false, true)) discard(handle, handle.latest.get())
        }
    }
    /** Ends one [hold]; the last holder releases the grant. */
    suspend fun drop(handle: Handle) {
        val last = synchronized(handle) {
            handle.holders = (handle.holders - 1).coerceAtLeast(0)
            (handle.holders == 0).also { if (it) handle.active.set(false) }
        }
        if (last) release(handle)
    }
    /** Not cancellable: an interrupted release would stop renewal yet leave the grant held by S. */
    suspend fun release(handle: Handle) = withContext(NonCancellable) {
        synchronized(handle) { handle.active.set(false) }
        handle.job.getAndSet(null)?.cancelAndJoin()
        handle.expiryJob.getAndSet(null)?.cancelAndJoin()
        handles.entries.removeIf { it.value === handle }
        retire(handle)
    }
    /** Step end. Runs under the host monitor, so it cannot join; instead it reads and retires the
     * current grant inside the handle monitor, the same monitor in which renewal swaps grants. A
     * renewal that completes afterwards observes the inactive handle and releases its new grant. */
    fun release(executionId: String, step: String) {
        handles.entries.filter { it.value.latest.get().scope.let { s -> s.executionId == executionId && (s.stepName == step || s.stepName?.startsWith("$step:") == true) } }
            .forEach { (key, handle) ->
                handles.remove(key, handle)
                retire(handle)
                handle.job.get()?.cancel(); handle.expiryJob.get()?.cancel()
            }
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
