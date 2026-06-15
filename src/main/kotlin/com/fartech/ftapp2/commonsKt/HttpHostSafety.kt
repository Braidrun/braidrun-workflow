package com.fartech.ftapp2.commonsKt

import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

/**
 * Shared SSRF guard primitives used by both [HttpAccess] (for redirect re-validation)
 * and `agents.tools.UrlSafety` (for initial URL validation in LLM-facing tools).
 *
 * Lives in `commonsKt` rather than `agents.tools` so [HttpAccess] can import it
 * without creating a circular module dependency. The agent-facing `UrlSafety`
 * helper delegates to this object.
 *
 * # Why this matters
 *
 * Phase 9 audit (2026-05) found that `WebTools` initially validated user-supplied
 * URLs via `UrlSafety.validateAndNormalizeUrl`, but every HTTP client backend
 * (Ktor / OkHttp engine, Jsoup) then followed 3xx redirects **without re-validating**
 * the redirect target. An attacker registers `attacker.com` (a public IP), points
 * it at an HTTP redirect to `http://localhost:8080/admin` (or
 * `http://169.254.169.254/latest/meta-data/`), and the agent fetches the internal
 * resource — the initial URL passed the SSRF gate, but the actual fetch landed
 * inside the perimeter.
 *
 * `assertHostIsPublic` is now invoked on every URL the OkHttp interceptor sees,
 * including each redirect hop. Combined with the per-call `validateAndNormalizeUrl`
 * in tools, redirects that cross the public/internal boundary are rejected with
 * `SecurityException`.
 *
 * # Opt-out
 *
 * Same opt-out as `UrlSafety`: set `WEB_TOOLS_ALLOW_PRIVATE_URLS=true` (system
 * property or env var) when the agent legitimately needs to reach internal
 * services (e.g. a self-hosted LLM endpoint, a private webhook target).
 */
object HttpHostSafety {

    /**
     * Throw [SecurityException] when [host] resolves to a non-public address
     * (loopback, link-local, site-local, multicast, RFC 1918 private, AWS / GCP
     * cloud-metadata 169.254.0.0/16). Throw [IllegalArgumentException] when
     * [host] is blank or unresolvable.
     *
     * No-op when `WEB_TOOLS_ALLOW_PRIVATE_URLS=true`.
     */
    fun assertHostIsPublic(host: String) = assertHostIsPublic(host, allowPrivateOverride = allowPrivateHosts())

    /**
     * Variant that lets the caller decide whether the
     * `WEB_TOOLS_ALLOW_PRIVATE_URLS` opt-out applies. Callers whose private-host
     * policy is independent of the agent-tools opt-out (e.g. the multi-tenant
     * per-workflow HTTP proxy, which has its OWN dedicated opt-out and must not
     * be widened by a flag meant for letting an agent reach an internal LLM)
     * pass `allowPrivateOverride = false` to force the public-only check
     * regardless of `WEB_TOOLS_ALLOW_PRIVATE_URLS`.
     */
    fun assertHostIsPublic(host: String, allowPrivateOverride: Boolean) {
        require(host.isNotBlank()) { "URL has no host" }
        if (allowPrivateOverride) return

        val addresses: Array<InetAddress> = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw IllegalArgumentException("Could not resolve host '$host': ${e.message}")
        }
        if (addresses.isEmpty()) {
            throw IllegalArgumentException("Host '$host' resolved to no addresses")
        }
        addresses.forEach { addr ->
            val why = disallowedAddressReason(addr)
            if (why != null) {
                throw SecurityException(
                    "URL host '$host' resolves to $why address ${addr.hostAddress}; " +
                        "blocked to prevent SSRF. Set WEB_TOOLS_ALLOW_PRIVATE_URLS=true to override."
                )
            }
        }
    }

    /**
     * Convenience: parse [url], require an http(s) scheme, and assert the host
     * is public. Used by the redirect interceptor — it sees full URLs, not bare
     * hosts.
     */
    @Suppress("DEPRECATION")
    fun assertUrlIsPublic(url: String) {
        require(url.isNotBlank()) { "URL cannot be blank" }
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL format: $url")
        }
        val scheme = parsed.protocol?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Only http:// and https:// URLs are allowed, got scheme '$scheme'"
        }
        val host = parsed.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("URL has no host: $url")
        assertHostIsPublic(host)
    }

    /**
     * Validate an already-resolved address list (the EXACT addresses a connection will use),
     * rather than re-resolving the hostname. Used by a custom DNS resolver so the SSRF check
     * and the actual connect share one resolution — closing the DNS-rebinding TOCTOU where
     * [assertHostIsPublic]'s lookup could return a public IP while the HTTP stack's separate
     * connect-time lookup returns an internal one. No-op when `WEB_TOOLS_ALLOW_PRIVATE_URLS=true`.
     */
    fun assertResolvedAddressesPublic(host: String, addresses: List<InetAddress>) {
        if (allowPrivateHosts()) return
        addresses.forEach { addr ->
            val why = disallowedAddressReason(addr)
            if (why != null) {
                throw SecurityException(
                    "URL host '$host' resolves to $why address ${addr.hostAddress}; " +
                        "blocked to prevent SSRF. Set WEB_TOOLS_ALLOW_PRIVATE_URLS=true to override."
                )
            }
        }
    }

    /**
     * Classify [addr] against the non-public ranges we refuse to connect to.
     *
     * Uses the JDK [InetAddress] predicates first (loopback / link-local /
     * site-local fec0::/fe80:: / multicast / wildcard) and then falls back to
     * **byte-level range checks** for everything those predicates miss. The
     * earlier implementation used `hostAddress` string prefixes, which silently
     * failed to block: IPv6 unique-local `fc00::/7` (`fd00::/8` is the common
     * Docker/k8s/corporate internal range — `isSiteLocalAddress` only matches the
     * deprecated `fec0::/10`), `0.0.0.0/8` (`0.0.0.x` routes to localhost on
     * Linux), CGNAT `100.64.0.0/10`, NAT64 `64:ff9b::/96` wrapping an internal v4,
     * and IPv4-mapped IPv6 (`::ffff:10.0.0.1`) literals.
     */
    private fun disallowedAddressReason(addr: InetAddress): String? {
        when {
            addr.isAnyLocalAddress -> return "wildcard"
            addr.isLoopbackAddress -> return "loopback"
            addr.isLinkLocalAddress -> return "link-local"
            addr.isSiteLocalAddress -> return "site-local"
            addr.isMulticastAddress -> return "multicast"
        }
        val bytes = addr.address
        return when (bytes.size) {
            4 -> ipv4Reason(bytes)
            16 -> {
                // IPv4-mapped (::ffff:a.b.c.d) / IPv4-compatible (::a.b.c.d):
                // validate the embedded IPv4 directly.
                if (isIpv4MappedOrCompat(bytes)) return ipv4Reason(bytes.copyOfRange(12, 16))
                // NAT64 well-known prefix 64:ff9b::/96 forwards to the embedded v4 —
                // block when that v4 is internal, allow when it is public.
                if (isNat64WellKnown(bytes)) return ipv4Reason(bytes.copyOfRange(12, 16))
                // Unique-local fc00::/7 (fc.. and fd..): (b0 & 0xFE) == 0xFC.
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return "unique-local"
                null
            }
            else -> null
        }
    }

    /** Byte-level classification of a 4-byte IPv4 address. Returns a reason string or null if public. */
    private fun ipv4Reason(b: ByteArray): String? {
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        return when {
            o0 == 0 -> "this-network"                  // 0.0.0.0/8 (0.0.0.x routes to localhost on Linux)
            o0 == 10 -> "private"                       // 10.0.0.0/8
            o0 == 127 -> "loopback"                     // 127.0.0.0/8
            o0 == 169 && o1 == 254 -> "link-local"      // 169.254.0.0/16 (cloud metadata)
            o0 == 172 && o1 in 16..31 -> "private"      // 172.16.0.0/12
            o0 == 192 && o1 == 168 -> "private"         // 192.168.0.0/16
            o0 == 100 && o1 in 64..127 -> "cgnat"       // 100.64.0.0/10 (RFC 6598 — provider internal)
            o0 >= 224 -> "reserved"                     // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved
            else -> null
        }
    }

    private fun isIpv4MappedOrCompat(b: ByteArray): Boolean {
        for (i in 0 until 10) if (b[i].toInt() != 0) return false
        val b10 = b[10].toInt() and 0xFF
        val b11 = b[11].toInt() and 0xFF
        return (b10 == 0xFF && b11 == 0xFF) || (b10 == 0x00 && b11 == 0x00)
    }

    private fun isNat64WellKnown(b: ByteArray): Boolean {
        // 64:ff9b::/96 → 00 64 ff 9b 00 00 00 00 00 00 00 00 <v4>
        val prefix = intArrayOf(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0)
        for (i in prefix.indices) if ((b[i].toInt() and 0xFF) != prefix[i]) return false
        return true
    }

    private fun allowPrivateHosts(): Boolean {
        val sys = System.getProperty("WEB_TOOLS_ALLOW_PRIVATE_URLS")?.takeIf { it.isNotBlank() }
        val env = System.getenv("WEB_TOOLS_ALLOW_PRIVATE_URLS")?.takeIf { it.isNotBlank() }
        return (sys ?: env)?.equals("true", ignoreCase = true) == true
    }
}
