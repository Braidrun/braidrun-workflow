package com.fartech.agents.commons

import com.fartech.agents.workflow.WorkflowHostPolicy
import java.net.InetAddress

/**
 * A service endpoint the host refused (see [ServiceEndpointPolicy]). Like
 * [LlmEndpointNotAllowedException] it is a configuration error thrown while the agent is
 * built, before any connection is opened.
 */
class ServiceEndpointNotAllowedException(message: String) : IllegalArgumentException(message)

/**
 * SSRF guard for the non-LLM endpoints a workflow can point the engine at: the Langfuse
 * exporter URL and MCP server URLs. Both connections are opened by the host JVM, so on a
 * multi-tenant host a user-supplied `http://169.254.169.254/...` or `http://localhost:6379`
 * would reach the server's own network.
 *
 * Once the host latched [WorkflowHostPolicy.requirePublicServiceEndpoints], every such URL must
 * use one of the given TLS schemes and resolve only to public addresses. It is the same
 * check as [LlmEndpointPolicy], including its limit: the connect-time DNS resolution is not
 * pinned. Without the latch (CLI / library use) this is a no-op.
 */
internal object ServiceEndpointPolicy {

    val HTTPS = setOf("https")

    /** WebSocket MCP servers connect over `wss`; the other transports use `https`. */
    val HTTPS_OR_WSS = setOf("https", "wss")

    /**
     * @param service what the URL is for, e.g. `Langfuse` or `MCP server 'files'` (used in the message).
     * @throws ServiceEndpointNotAllowedException when the latch is on and [url] is not allowed.
     */
    fun check(url: String, service: String, allowedSchemes: Set<String> = HTTPS) =
        check(url, service, allowedSchemes) { host -> InetAddress.getAllByName(host).toList() }

    internal fun check(
        url: String,
        service: String,
        allowedSchemes: Set<String>,
        resolve: (String) -> List<InetAddress>,
    ) {
        if (!WorkflowHostPolicy.requiresPublicServiceEndpoints) return
        val reason = LlmEndpointPolicy.violation(url, allowUnresolvable = false, resolve, allowedSchemes) ?: return
        throw ServiceEndpointNotAllowedException(
            "$service endpoint '${LlmEndpointPolicy.redactedEndpoint(url)}' is not allowed on this host: $reason. " +
                "Only ${allowedSchemes.sorted().joinToString(" or ")} URLs on a public host can be reached from this server."
        )
    }
}
