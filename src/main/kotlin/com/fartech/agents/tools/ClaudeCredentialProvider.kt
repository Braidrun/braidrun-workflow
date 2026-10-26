package com.fartech.agents.tools

/**
 * Runtime-only bridge for subscription-credential failover. Implementations retain secrets.
 *
 * One instance serves exactly one provider's pool. Callers pass a separate
 * instance per engine ([ExternalAgentTools] takes a Claude one and a Codex one),
 * because failover must never cross vendors: a Claude subscription that runs out
 * cannot be replaced by a ChatGPT one — different vendor, different account,
 * different billing subject. Exhausting one pool fails the run.
 *
 * The name is historical: Claude was the first engine with a pool.
 */
interface ClaudeCredentialProvider {
    data class Credential(
        val id: String,
        val token: String,
        val label: String? = null,
        val source: String? = null
    )

    suspend fun acquire(excludedCredentialIds: Set<String>): Credential?

    suspend fun markRateLimited(credential: Credential, resetAtMillis: Long)

    suspend fun markSucceeded(credential: Credential, executionId: String?, stepName: String?)
}

/**
 * A pool whose credentials are held per invocation rather than per pool (the Runner's scoped
 * leases). Every [Credential] instance returned by [acquire] is one invocation's hold: the caller
 * publishes that invocation's rotated material through [rotated] and then calls [release] exactly
 * once, whatever the outcome, before it fails over to another credential. Holds are identified by
 * instance, so concurrent invocations on the same account never end each other's hold.
 */
interface InvocationCredentialProvider : ClaudeCredentialProvider {
    fun rotated(credential: ClaudeCredentialProvider.Credential, authJson: String)

    suspend fun release(credential: ClaudeCredentialProvider.Credential)
}
