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
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val leases = ConcurrentHashMap<String, RunnerCredentialLease>()
    suspend fun acquire(ref: String, version: Long, scope: WorkflowHostScope, purpose: String): RunnerCredentialLease {
        gate.check()
        require(scope.rootExecutionId == ipc.binding.string("executionId")) { "Credential scope mismatch" }
        val requestId = UUID.randomUUID().toString()
        val grant = ipc.request("credentialRequest", buildJsonObject {
            put("requestId", requestId); put("credentialRef", ref); put("expectedVersion", version)
            put("childExecutionPath", childPath(scope.rootExecutionId, scope.executionId)); put("purpose", purpose)
        }, "credentialGrant")
        // Register FIRST: even a rejected grant must never appear in late logs.
        secrets.register(grant.obj("material"))
        require(grant.string("requestId") == requestId && grant.string("credentialRef") == ref && grant.long("version") == version) { "Credential grant mismatch" }
        val expiry = Instant.parse(grant.string("expiresAt"))
        val operations = grant.array("allowedOperations").map { it.jsonPrimitive.content }.toSet()
        require(expiry > now() && purpose in operations) { "Credential grant lacks live operation authority" }
        gate.check()
        return RunnerCredentialLease(ref, grant.string("credentialLeaseId"), version, scope, grant.obj("material"), operations, expiry, nanoTime() + java.time.Duration.between(now(), expiry).toNanos())
            .also { check(leases.putIfAbsent(it.leaseId, it) == null) { "Credential lease identity reused" } }
    }
    fun material(lease: RunnerCredentialLease, scope: WorkflowHostScope, operation: String): JsonObject {
        gate.check()
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
