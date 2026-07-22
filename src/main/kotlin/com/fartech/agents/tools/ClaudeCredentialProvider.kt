package com.fartech.agents.tools

/** Runtime-only bridge for Claude subscription failover. Implementations retain secrets. */
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
