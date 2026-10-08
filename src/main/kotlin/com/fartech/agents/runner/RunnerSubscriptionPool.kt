package com.fartech.agents.runner

import com.fartech.agents.tools.ClaudeCredentialProvider
import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Exactly the declared provider references, never another vendor or unlisted local account. */
class RunnerSubscriptionPool(
    private val live: RunnerLiveCredentials,
    private val scope: WorkflowHostScope,
    private val provider: String,
    private val reference: String,
    private val secrets: RunnerSecrets,
    private val record: (String, JsonObject) -> Unit
) : ClaudeCredentialProvider {
    private var handle: RunnerLiveCredentials.Handle? = null
    private val purpose = "subscription.$provider"
    override suspend fun acquire(excludedCredentialIds: Set<String>): ClaudeCredentialProvider.Credential? {
        val ref = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(reference)!!.groupValues[1]
        if (ref in excludedCredentialIds) return null
        val selected = live.acquire(reference, scope, purpose).also { handle = it }
        val material = live.checked(selected, scope, purpose)
        val token = material.string(if (provider == "codex") "authJson" else "token")
        secrets.register(JsonPrimitive(token))
        return ClaudeCredentialProvider.Credential(ref, token, source = "runner-scoped-lease")
    }
    override suspend fun markRateLimited(credential: ClaudeCredentialProvider.Credential, resetAtMillis: Long) {
        record("usage", buildJsonObject { put("credentialRef", credential.id); put("provider", provider); put("outcome", "RATE_LIMITED"); put("resetAtMillis", resetAtMillis) })
    }
    override suspend fun markSucceeded(credential: ClaudeCredentialProvider.Credential, executionId: String?, stepName: String?) {
        record("usage", buildJsonObject { put("credentialRef", credential.id); put("provider", provider); put("outcome", "SUCCEEDED") })
    }
    fun rotated(credentialId: String?, authJson: String) = runBlocking {
        val selected = requireNotNull(handle) { "Rotation has no scoped subscription lease" }
        val lease = selected.latest.get()
        require(provider == "codex" && credentialId == lease.credentialRef) { "Rotation credential scope mismatch" }
        val json = runnerJson.parseToJsonElement(authJson).jsonObject
        secrets.register(json); secrets.register(JsonPrimitive(authJson))
        live.rotate(selected, scope, JsonObject(lease.material + ("authJson" to JsonPrimitive(authJson))))
    }
}
