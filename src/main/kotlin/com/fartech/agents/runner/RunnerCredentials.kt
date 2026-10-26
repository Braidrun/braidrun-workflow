package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Secrets live only in lease objects/transient authorization frames, never host state/events. */
class RunnerCredentialLease internal constructor(
    val credentialRef: String,
    val leaseId: String,
    val version: Long,
    val scope: WorkflowHostScope,
    internal val material: JsonObject,
    internal val operations: Set<String>,
    internal val expiresAt: Instant,
    internal val expiresNanos: Long
) {
    override fun toString(): String = "RunnerCredentialLease(<redacted>)"
}

class RunnerCredentials(
    private val ipc: RunnerIpc,
    private val gate: RunnerEnvironmentGate,
    private val secrets: RunnerSecrets,
    private val now: () -> Instant = Instant::now,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onRequestCancelled: (JsonObject) -> Unit = {}
) {
    private val leases = ConcurrentHashMap<String, RunnerCredentialLease>()
    suspend fun acquire(ref: String, version: Long, scope: WorkflowHostScope, purpose: String): RunnerCredentialLease {
        ipc.businessFence.check(); gate.check()
        require(scope.rootExecutionId == ipc.binding.string("executionId")) { "Credential scope mismatch" }
        val requestId = UUID.randomUUID().toString()
        val requestFrameId = UUID.randomUUID().toString()
        val request = buildJsonObject {
            put("requestId", requestId); put("credentialRef", ref); put("expectedVersion", version)
            put("childExecutionPath", childPath(scope.rootExecutionId, scope.executionId)); put("purpose", purpose)
        }
        var dispatched = false
        // S records the grant's watchdog lineage under this exact request frame. Whenever E will not
        // hold the grant, the same request identity retires it there.
        fun retireRequest() {
            if (dispatched && !ipc.businessFence.isClosed()) runCatching {
                onRequestCancelled(JsonObject(request + ("requestFrameId" to JsonPrimitive(requestFrameId))))
            }.onFailure { gate.invalidate("CREDENTIAL_REQUEST_CANCELLATION_UNREPORTED") }
        }
        val grant = try {
            ipc.requestCorrelated("credentialRequest", request, "credentialGrant", frameId = requestFrameId,
                onRequest = { dispatched = true }).payload
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The RPC waiter has been removed before cancellation escapes. A late response can never
            // become a usable lease. S can now retire only the grant associated with this exact request.
            retireRequest()
            throw cancelled
        }
        val lease = try {
            // Register FIRST: even a rejected grant must never appear in late logs.
            grant["material"]?.let(secrets::register)
            require(grant.string("requestId") == requestId && grant.string("credentialRef") == ref && grant.long("version") == version) { "Credential grant mismatch" }
            val expiry = Instant.parse(grant.string("expiresAt"))
            val operations = grant.array("allowedOperations").map { it.jsonPrimitive.content }.toSet()
            require(expiry > now() && purpose in operations) { "Credential grant lacks live operation authority" }
            ipc.businessFence.check(); gate.check()
            RunnerCredentialLease(ref, grant.string("credentialLeaseId"), version, scope, grant.obj("material"), operations, expiry, nanoTime() + java.time.Duration.between(now(), expiry).toNanos())
        } catch (refused: Exception) {
            // Refused locally after S already armed its watchdog for this grant: retire it there, or the
            // fallback card would run until the refused grant's deadline stops the whole attempt.
            retireRequest()
            throw refused
        }
        if (leases.putIfAbsent(lease.leaseId, lease) != null) {
            // A reused grant identity names a lineage S may already hold for a live lease; retiring it
            // would drop that lease's backstop. Fail closed instead.
            gate.invalidate("CREDENTIAL_LEASE_IDENTITY_REUSED")
            throw RunnerAuthorityLost()
        }
        return lease
    }
    fun material(lease: RunnerCredentialLease, scope: WorkflowHostScope, operation: String): JsonObject {
        ipc.businessFence.check(); gate.check()
        require(leases[lease.leaseId] === lease && scope == lease.scope && lease.expiresAt > now() && nanoTime() < lease.expiresNanos && operation in lease.operations) { "Credential lease scope or authority mismatch" }
        return lease.material
    }
    /** Refresh is performed by the local adapter first; the platform only conditionally stores it. */
    suspend fun rotate(lease: RunnerCredentialLease, scope: WorkflowHostScope, material: JsonObject) {
        material(lease, scope, "credential.rotate")
        secrets.register(material)
        try { ipc.request("credentialRotation", buildJsonObject {
            put("credentialLeaseId", lease.leaseId); put("credentialRef", lease.credentialRef)
            put("expectedVersion", lease.version); put("material", material)
            put("receiptHash", sha256(canonical(material).toByteArray(Charsets.UTF_8)))
        }, "ack") } catch (_: Exception) {
            leases.remove(lease.leaseId, lease)
            gate.invalidate("CREDENTIAL_ROTATION_UNCERTAIN")
            throw RunnerAuthorityLost()
        }
        // A version CAS succeeded. The old lease cannot be used again; reacquire the new version.
        leases.remove(lease.leaseId, lease)
    }
    fun remainingNanos(lease: RunnerCredentialLease): Long = lease.expiresNanos - nanoTime()
    fun discard(lease: RunnerCredentialLease) { leases.remove(lease.leaseId, lease) }
    fun clear() { leases.clear() }
}
