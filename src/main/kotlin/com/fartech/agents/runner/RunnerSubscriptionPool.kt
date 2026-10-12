package com.fartech.agents.runner

import com.fartech.agents.tools.ClaudeCredentialProvider
import com.fartech.agents.tools.InvocationCredentialProvider
import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Exactly the declared provider references, in the platform's failover order; never another vendor or unlisted local account.
 * Every returned credential instance is one invocation's hold on the step's shared live handle. Invocations of the same
 * step run concurrently, so a hold is ended only by its own invocation ([release]), after that invocation published its
 * rotation. Failover never releases a card by id: another invocation may still be running on it. */
class RunnerSubscriptionPool(
    private val live: RunnerLiveCredentials,
    private val scope: WorkflowHostScope,
    private val provider: String,
    private val references: List<String>,
    private val secrets: RunnerSecrets,
    private val record: (String, JsonObject) -> Unit
) : InvocationCredentialProvider {
    private val holds = java.util.IdentityHashMap<ClaudeCredentialProvider.Credential, RunnerLiveCredentials.Hold>()
    private val purpose = "subscription.$provider"
    override suspend fun acquire(excludedCredentialIds: Set<String>): ClaudeCredentialProvider.Credential? {
        var refused: Exception? = null
        for (reference in references) {
            val ref = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(reference)!!.groupValues[1]
            if (ref in excludedCredentialIds) continue
            val selected = try { live.hold(reference, scope, purpose) }
            catch (e: CancellationException) { throw e } // Includes RunnerAuthorityLost; a cancelled caller is not a refused card.
            catch (e: RunnerHoldUnavailable) { throw e } // A local shared-lease state, not the platform's answer.
            catch (e: Exception) {
                // The platform withdrew or refused this reference; the next delegated card is the explicit fallback.
                record("usage", buildJsonObject { put("credentialRef", ref); put("provider", provider); put("outcome", "LEASE_REFUSED") }); refused = e; continue
            }
            val credential = try {
                val token = selected.material.string(if (provider == "codex") "authJson" else "token")
                secrets.register(JsonPrimitive(token))
                ClaudeCredentialProvider.Credential(ref, token, source = "runner-scoped-lease")
            } catch (failure: Throwable) {
                withContext(NonCancellable) { live.drop(selected.handle) }
                throw failure
            }
            synchronized(holds) { holds[credential] = selected }
            return credential
        }
        refused?.let { throw it }
        return null
    }
    override suspend fun markRateLimited(credential: ClaudeCredentialProvider.Credential, resetAtMillis: Long) {
        record("usage", buildJsonObject { put("credentialRef", credential.id); put("provider", provider); put("outcome", "RATE_LIMITED"); put("resetAtMillis", resetAtMillis) })
    }
    override suspend fun markSucceeded(credential: ClaudeCredentialProvider.Credential, executionId: String?, stepName: String?) {
        record("usage", buildJsonObject { put("credentialRef", credential.id); put("provider", provider); put("outcome", "SUCCEEDED") })
    }
    /** Publishes the rotation produced on exactly this hold's card, while the hold is still live. A refresh
     * derived from a version another holder of the card already rotated is not written over it. */
    override fun rotated(credential: ClaudeCredentialProvider.Credential, authJson: String) {
        val selected = requireNotNull(synchronized(holds) { holds[credential] }) { "Rotation has no scoped subscription lease" }
        require(provider == "codex" && credential.id == selected.handle.latest.get().credentialRef) { "Rotation credential scope mismatch" }
        val json = runnerJson.parseToJsonElement(authJson).jsonObject
        secrets.register(json); secrets.register(JsonPrimitive(authJson))
        runBlocking { live.rotate(selected.handle, scope, selected.version) { material -> JsonObject(material + ("authJson" to JsonPrimitive(authJson))) } }
    }
    /** Ends this invocation's hold once; the grant is released when no concurrent invocation still holds it. */
    override suspend fun release(credential: ClaudeCredentialProvider.Credential) {
        val selected = synchronized(holds) { holds.remove(credential) } ?: return
        live.drop(selected.handle)
    }
}
