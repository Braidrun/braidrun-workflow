package com.fartech.agents.runner

import com.fartech.agents.tools.ClaudeCredentialProvider
import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Exactly the declared provider references, in the platform's failover order; never another vendor or unlisted local account. */
class RunnerSubscriptionPool(
    private val live: RunnerLiveCredentials,
    private val scope: WorkflowHostScope,
    private val provider: String,
    private val references: List<String>,
    private val secrets: RunnerSecrets,
    private val record: (String, JsonObject) -> Unit
) : ClaudeCredentialProvider {
    private val handles = java.util.concurrent.ConcurrentHashMap<String, RunnerLiveCredentials.Handle>()
    @Volatile private var handle: RunnerLiveCredentials.Handle? = null
    private val purpose = "subscription.$provider"
    override suspend fun acquire(excludedCredentialIds: Set<String>): ClaudeCredentialProvider.Credential? {
        // CLI failover is sequential. No abandoned card may retain a renewal/expiry task.
        handles.entries.filter { it.key in excludedCredentialIds }.forEach { (ref, previous) ->
            val lineageReleased = live.release(previous)
            handles.remove(ref, previous)
            if (handle === previous) handle = null
            if (lineageReleased) record("usage", buildJsonObject {
                put("credentialRef", ref); put("provider", provider); put("outcome", "RELEASED")
                put("childExecutionPath", childPath(scope.rootExecutionId, scope.executionId)); put("purpose", purpose)
            })
        }
        var refused: Exception? = null
        for (reference in references) {
            val ref = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(reference)!!.groupValues[1]
            if (ref in excludedCredentialIds) continue
            val selected = try { live.acquire(reference, scope, purpose) } catch (e: RunnerAuthorityLost) { throw e } catch (e: Exception) {
                // The platform withdrew or refused this reference; the next delegated card is the explicit fallback.
                record("usage", buildJsonObject { put("credentialRef", ref); put("provider", provider); put("outcome", "LEASE_REFUSED") }); refused = e; continue
            }
            handles[ref] = selected; handle = selected
            val material = live.checked(selected, scope, purpose)
            val token = material.string(if (provider == "codex") "authJson" else "token")
            secrets.register(JsonPrimitive(token))
            return ClaudeCredentialProvider.Credential(ref, token, source = "runner-scoped-lease")
        }
        if (refused != null && handles.isEmpty()) throw refused
        return null
    }
    override suspend fun markRateLimited(credential: ClaudeCredentialProvider.Credential, resetAtMillis: Long) {
        record("usage", buildJsonObject { put("credentialRef", credential.id); put("provider", provider); put("outcome", "RATE_LIMITED"); put("resetAtMillis", resetAtMillis) })
    }
    override suspend fun markSucceeded(credential: ClaudeCredentialProvider.Credential, executionId: String?, stepName: String?) {
        record("usage", buildJsonObject { put("credentialRef", credential.id); put("provider", provider); put("outcome", "SUCCEEDED") })
    }
    fun rotated(credentialId: String?, authJson: String) = runBlocking {
        val selected = requireNotNull(credentialId?.let { handles[it] } ?: handle) { "Rotation has no scoped subscription lease" }
        val lease = selected.latest.get()
        require(provider == "codex" && credentialId == lease.credentialRef) { "Rotation credential scope mismatch" }
        val json = runnerJson.parseToJsonElement(authJson).jsonObject
        secrets.register(json); secrets.register(JsonPrimitive(authJson))
        live.rotate(selected, scope, JsonObject(lease.material + ("authJson" to JsonPrimitive(authJson))))
    }
}
