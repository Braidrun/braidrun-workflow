package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
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
        /** One rotation at a time per handle; while one runs, new holders wait instead of joining a consumed lease. */
        internal val rotation = kotlinx.coroutines.sync.Mutex()
        /** Guarded by the handle's monitor. */
        internal var rotating = false
        @Volatile internal var rotationDone: CompletableDeferred<Unit>? = null
    }
    /** A hold plus the material and vault version it was granted on, read atomically with the join. */
    class Hold internal constructor(val handle: Handle, internal val material: JsonObject, internal val version: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = ConcurrentHashMap<String, Handle>()
    private val plans = ConcurrentHashMap<String, Long>()
    private val versions = ConcurrentHashMap<String, Long>()
    private fun key(ref: String, scope: WorkflowHostScope, purpose: String) = "$ref:$purpose:${scope.executionId}:${scope.stepName}"
    /** Shared, step-lived permission (model keys, MCP tokens): retired only at step end. */
    suspend fun acquire(reference: String, executionScope: WorkflowHostScope, purpose: String): Handle =
        obtain(reference, executionScope, purpose, hold = false).handle
    /** One invocation's hold on the shared handle. Each hold is ended exactly once with [drop];
     * the grant is released only when the last concurrent holder drops it. */
    suspend fun hold(reference: String, executionScope: WorkflowHostScope, purpose: String): Hold =
        obtain(reference, executionScope, purpose, hold = true)
    private suspend fun obtain(reference: String, executionScope: WorkflowHostScope, purpose: String, hold: Boolean): Hold {
        val parsed = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(reference) ?: error("Credential reference required")
        val ref = parsed.groupValues[1]; val initial = parsed.groupValues[2].toLong()
        require(plans.putIfAbsent(ref, initial)?.let { it == initial } != false) { "Conflicting pinned credential plans" }
        versions.putIfAbsent(ref, initial)
        val k = key(ref, executionScope, purpose)
        while (true) {
            val existing = handles[k]
            if (existing != null) {
                // Join only a live, non-rotating handle, atomically with its last holder's drop and with
                // the material read: a retiring handle or a lease being consumed by rotation is never handed out.
                var rotationInProgress: CompletableDeferred<Unit>? = null
                val joined = synchronized(existing) {
                    when {
                        !existing.active.get() -> null
                        existing.rotating -> { rotationInProgress = existing.rotationDone; null }
                        else -> {
                            val lease = existing.latest.get()
                            val material = try { credentials.material(lease, executionScope, purpose) } catch (failure: CancellationException) { throw failure } catch (failure: Exception) {
                                throw RunnerHoldUnavailable(failure)
                            }
                            if (hold) existing.holders++
                            Hold(existing, material, lease.version)
                        }
                    }
                }
                if (joined != null) return joined
                val pending = rotationInProgress
                if (pending != null) pending.await() else handles.remove(k, existing)
                continue
            }
            val lease = credentials.acquire(ref, versions.getValue(ref), executionScope, purpose)
            val handle = Handle(AtomicReference(lease), purpose)
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
            // Join through the same atomic path as every other holder.
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
    /** Every live, settled lease is still authorized. The swap of a renewal or rotation happens inside the
     * handle monitor, so each lease is read and checked there; a handle mid-rotation is covered by that
     * rotation, which fails closed. */
    fun checkAll() {
        handles.values.forEach { handle ->
            synchronized(handle) {
                if (handle.active.get() && !handle.rotating) handle.latest.get().let { credentials.material(it, it.scope, it.operations.first()) }
            }
        }
    }
    enum class RotationOutcome { PUBLISHED, UNCHANGED, SUPERSEDED }
    /**
     * Publishes locally refreshed material produced on the lease of [baseVersion]. Rotations of one
     * handle run one at a time; one built on a version another holder already rotated is not
     * published over the newer vault material ([RotationOutcome.SUPERSEDED]; the caller reports it).
     * Preconditions run before renewal stops. When other invocations still hold this handle, they
     * keep running on the same account, so the handle continues under a grant for the new vault
     * version instead of leaving them without permission.
     *
     * Known window (inherent to sharing one OAuth account between concurrent CLIs): a CLI may refresh
     * its token long before its invocation ends and publishes. Until then the vault, and so any holder
     * joining meanwhile, still has the previous refresh token, which the provider may already treat as
     * used. That joiner then fails its own refresh (401: auth failover or a failed invocation), and a
     * provider that revokes the whole token family on reuse also invalidates the first CLI's new token,
     * leaving the account to be logged in again. The [rotating] wait covers only the publication itself.
     */
    suspend fun rotate(handle: Handle, executionScope: WorkflowHostScope, baseVersion: Long, update: (JsonObject) -> JsonObject): RotationOutcome = handle.rotation.withLock {
        val snapshot = synchronized(handle) {
            require(handle.active.get()) { "Credential generation is no longer active" }
            handle.latest.get().also { if (it.version == baseVersion) { handle.rotating = true; handle.rotationDone = CompletableDeferred() } }
        }
        if (snapshot.version != baseVersion) {
            return@withLock if (update(snapshot.material) == snapshot.material) RotationOutcome.UNCHANGED else RotationOutcome.SUPERSEDED
        }
        try {
            val material = update(snapshot.material)
            if (material == snapshot.material) return@withLock RotationOutcome.UNCHANGED
            credentials.material(snapshot, executionScope, "credential.rotate")
            val cleanReceipt = preserveRotation?.invoke(snapshot, material)
            handle.job.getAndSet(null)?.cancelAndJoin()
            // Renewal may have swapped in an equal successor before it stopped; rotate the current one.
            val old = handle.latest.get()
            try { credentials.rotate(old, executionScope, material) } catch (failure: Exception) {
                if (failure !is CancellationException && synchronized(handle) { handle.active.get() }) renew(handle)
                throw failure
            }
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
                if (continued) { onReleased(old, handle.purpose); renew(handle); return@withLock RotationOutcome.PUBLISHED }
            }
            finishRotation(handle, old)
            RotationOutcome.PUBLISHED
        } finally {
            synchronized(handle) { handle.rotating = false }
            handle.rotationDone?.complete(Unit)
        }
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
     * renewal that completes afterwards observes the inactive handle and releases its new grant.
     * A handle still held by a running invocation is left to that invocation: a concurrent workflow
     * reports step_cancelled before it cancels the step, and the invocation's CLI keeps running, then
     * publishes its rotation, under this grant. Its last drop releases the grant. */
    fun release(executionId: String, step: String) {
        handles.entries.filter { it.value.latest.get().scope.let { s -> s.executionId == executionId && (s.stepName == step || s.stepName?.startsWith("$step:") == true) } }
            .forEach { (key, handle) ->
                val retired = synchronized(handle) {
                    (handle.holders == 0).also { if (it) { handles.remove(key, handle); retire(handle) } }
                }
                if (retired) { handle.job.get()?.cancel(); handle.expiryJob.get()?.cancel() }
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

/** A shared handle exists but its current lease cannot be handed out: never a platform refusal of the card. */
class RunnerHoldUnavailable(cause: Exception) : IllegalStateException("Shared credential generation is unavailable", cause)
