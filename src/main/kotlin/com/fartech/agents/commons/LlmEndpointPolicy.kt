package com.fartech.agents.commons

import com.fartech.agents.workflow.WorkflowHostPolicy
import com.fartech.ftapp2.commonsKt.HttpHostSafety
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * An LLM endpoint the host refused (see [LlmEndpointPolicy]). A configuration error, not a
 * transport failure: it is thrown while the client is built, before any request, so neither
 * the retrying client nor the cascade ever sees it as something to retry or fall back from.
 */
class LlmEndpointNotAllowedException(message: String) : IllegalArgumentException(message)

/**
 * SSRF guard for the endpoints the engine's in-process LLM clients talk to.
 *
 * Koog clients run inside the host JVM, so on a multi-tenant host a user-supplied
 * `llm_config` base URL — or a provider default such as LM Studio's `http://localhost:1234/v1`
 * or Ollama's `http://localhost:11434` — is a request the server itself makes. Once the host
 * latched [WorkflowHostPolicy.requirePublicLlmEndpoints], every effective base URL (user-supplied
 * or default) must be https and resolve only to public addresses per [HttpHostSafety]. The
 * `WEB_TOOLS_ALLOW_PRIVATE_URLS` opt-out deliberately does not apply: it is an agent-tools
 * knob, and the latch is the host's own declaration. Without the latch (CLI / library use)
 * this is a no-op.
 *
 * The check runs when the client is built. It does not pin the connect-time DNS resolution,
 * so a host that re-resolves to a private address between check and connect is out of scope.
 */
internal object LlmEndpointPolicy {

    private val HTTPS_ONLY = setOf("https")

    /**
     * @param providerDefault true when [baseUrl] is the provider's built-in default (not user-set):
     *   a DNS failure for it is a transient network problem, not an SSRF attempt, so it is left to
     *   the request path (and its retries) instead of failing the run as a configuration error.
     * @throws LlmEndpointNotAllowedException when the latch is on and [baseUrl] is not allowed.
     */
    fun check(baseUrl: String, provider: String, providerDefault: Boolean = false) =
        check(baseUrl, provider, providerDefault) { host -> InetAddress.getAllByName(host).toList() }

    internal fun check(
        baseUrl: String,
        provider: String,
        providerDefault: Boolean = false,
        resolve: (String) -> List<InetAddress>,
    ) {
        if (!WorkflowHostPolicy.requiresPublicLlmEndpoints) return
        val reason = violation(baseUrl, allowUnresolvable = providerDefault, resolve) ?: return
        throw LlmEndpointNotAllowedException(
            "LLM endpoint '${redactedEndpoint(baseUrl)}' for provider '$provider' is not allowed on this host: " +
                "$reason. Use an https base URL on a public host; local or private-network endpoints " +
                "(LM Studio, Ollama, internal proxies) cannot be reached from this server."
        )
    }

    /** Why [baseUrl] is not allowed, or null when it is https on a public host. */
    internal fun violation(baseUrl: String, resolve: (String) -> List<InetAddress>): String? =
        violation(baseUrl, allowUnresolvable = false, resolve)

    /**
     * @param allowedSchemes lower-case schemes that count as TLS for this kind of endpoint
     *   (`https` for LLM clients; [ServiceEndpointPolicy] also allows `wss` for MCP).
     */
    internal fun violation(
        baseUrl: String,
        allowUnresolvable: Boolean,
        resolve: (String) -> List<InetAddress>,
        allowedSchemes: Set<String> = HTTPS_ONLY,
    ): String? {
        val uri = try {
            URI(baseUrl.trim())
        } catch (_: Exception) {
            return "not a valid URL"
        }
        if (uri.scheme?.lowercase() !in allowedSchemes) {
            return "only ${allowedSchemes.sorted().joinToString(" or ")} is allowed, got scheme '${uri.scheme ?: ""}'"
        }
        // URI keeps IPv6 literals bracketed; InetAddress wants them bare.
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }
            ?: return "the URL has no host"
        val addresses = try {
            resolve(host)
        } catch (_: UnknownHostException) {
            return if (allowUnresolvable) null else "host '$host' could not be resolved"
        }
        if (addresses.isEmpty()) return "host '$host' resolved to no addresses"
        return try {
            HttpHostSafety.assertResolvedAddressesPublic(host, addresses, allowPrivateOverride = false)
            null
        } catch (e: SecurityException) {
            // Drop HttpHostSafety's WEB_TOOLS_ALLOW_PRIVATE_URLS hint: that opt-out does not apply here.
            e.message?.substringBefore("; blocked") ?: "host '$host' is not public"
        }
    }

    /**
     * True when [configuredBaseUrl] points somewhere other than the provider's [defaultBaseUrl]
     * (different scheme, host or port; the path may differ). Such an endpoint is user-chosen, so
     * under [WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints] it only gets keys
     * supplied with the run, never the host's environment keys.
     */
    fun isCustomEndpoint(configuredBaseUrl: String?, defaultBaseUrl: String): Boolean {
        if (configuredBaseUrl.isNullOrBlank()) return false
        return originOf(configuredBaseUrl) == null || originOf(configuredBaseUrl) != originOf(defaultBaseUrl)
    }

    private fun originOf(url: String): Triple<String, String, Int>? = try {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
        Triple(scheme, host, port)
    } catch (_: Exception) {
        null
    }

    /** scheme://host[:port] only — a base URL can carry credentials or tokens in userinfo, path or query. */
    internal fun redactedEndpoint(baseUrl: String): String = try {
        val uri = URI(baseUrl.trim())
        buildString {
            append(uri.scheme ?: "?").append("://").append(uri.host ?: "?")
            if (uri.port != -1) append(':').append(uri.port)
        }
    } catch (_: Exception) {
        "<invalid URL>"
    }
}
