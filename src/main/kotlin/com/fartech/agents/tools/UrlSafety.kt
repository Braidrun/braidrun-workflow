package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.HttpHostSafety
import java.net.URL

/**
 * Shared SSRF guard for every outbound URL the agent may reach through LLM-controlled
 * input (web fetch, image reference, webhook, multimedia generation reference URL, etc.).
 *
 * Was previously private to [WebTools]. Extracted here so [MultimediaGenerationTools],
 * [BrowserTools], and any future tool that wants to forward a URL can reuse the same
 * validation rules — preventing the class of bug where one tool blocks `169.254.169.254`
 * while another happily fetches it.
 *
 * Phase 9 (2026-05): the host-resolution guts moved to [HttpHostSafety] in `commonsKt`
 * so [com.fartech.ftapp2.commonsKt.HttpAccess] can re-validate every redirect hop
 * without creating a circular module dependency. The existing internal API of this
 * object is preserved — callers don't change.
 */
internal object UrlSafety {

    /**
     * Reject URLs that:
     *  - use a non-http(s) scheme (file:, jar:, javascript:, gopher: …)
     *  - have no host
     *  - resolve to loopback / link-local / site-local / private / multicast /
     *    cloud-metadata addresses (SSRF classics)
     *
     * Callers that legitimately need internal URLs set `WEB_TOOLS_ALLOW_PRIVATE_URLS=true`,
     * in which case only the scheme check is enforced. The env-var name is kept for
     * backward compat with WebTools.
     */
    fun validateAndNormalizeUrl(url: String): String {
        require(url.isNotBlank()) { "URL cannot be blank" }

        val lower = url.lowercase()
        val normalized = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> url
            lower.startsWith("//") -> "https:$url"
            "://" in lower -> throw IllegalArgumentException(
                "Only http:// and https:// URLs are allowed, got: $url"
            )
            else -> "https://$url"
        }

        @Suppress("DEPRECATION")
        val parsed = try {
            URL(normalized)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL format: $url")
        }

        val scheme = parsed.protocol?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Only http:// and https:// URLs are allowed, got scheme '$scheme'"
        }

        val host = parsed.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("URL has no host: $url")

        HttpHostSafety.assertHostIsPublic(host)
        return normalized
    }
}
