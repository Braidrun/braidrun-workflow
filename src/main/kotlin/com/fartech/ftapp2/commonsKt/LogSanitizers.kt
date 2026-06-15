package com.fartech.ftapp2.commonsKt

/**
 * Centralized log-scrubbing helpers.
 *
 * Every place in the codebase that emits user-controlled or credential-bearing text to
 * a log stream (SLF4J, println, tool return messages) should go through one of these
 * helpers. Adding a new redaction rule here is how you retire one more
 * "oops we logged the API key" ticket; avoid hand-rolling regexes at call sites.
 *
 * See also:
 *   - `HttpAccess`: uses [redactSensitiveUrlForLogs] on every outbound URL log
 *   - `EnvironmentConfig.printEnvironmentInfo`: scrubs Mongo / Redis URLs
 *   - `InstantMessagingTools`: scrubs Telegram bot-token in webhook URLs
 *   - `AgentMcpServer.sanitizeToolException`: additional redaction of FQCNs and paths
 */

// Bounded quantifiers ({0,31} scheme, {1,512} userinfo) keep this linear-time. The
// previous unbounded `[a-z0-9+.-]*://([^/@\s]+)@` exhibited O(n^2) backtracking on
// inputs like `http://` + a long run with no `@` (the security-critical log redactor
// became a CPU-DoS amplifier on attacker-/LLM-controlled text — e.g. long URLs logged
// on every HttpAccess retry).
private val BASIC_AUTH_IN_URL_REGEX = Regex("([a-z][a-z0-9+.-]{0,31}://)([^/@\\s]{1,512})@", RegexOption.IGNORE_CASE)
private val TELEGRAM_BOT_TOKEN_IN_PATH_REGEX = Regex("(/bot)([^/?#\\s]+)")
private val SENSITIVE_QUERY_PARAM_REGEX = Regex(
    "([?&](?:access_token|token|secret|signature|sign|sig|api_key|apikey|key|password|pass|pwd)=)([^&#]*)",
    RegexOption.IGNORE_CASE
)

/**
 * Redact well-known secret patterns inside a URL string. Opportunistic — missing
 * unusual formats, but covers the 95% of URLs the agent emits:
 *
 *   - Basic auth: `https://user:pass@host/…` → `https://<redacted>@host/…`
 *   - Telegram bot path: `/bot<TOKEN>/getUpdates` → `/bot<redacted>/getUpdates`
 *   - Sensitive query params (`token=`, `api_key=`, `signature=`, …) → `…=<redacted>`
 */
fun redactSensitiveUrlForLogs(url: String): String {
    var redacted = url
    redacted = BASIC_AUTH_IN_URL_REGEX.replace(redacted) { matchResult ->
        "${matchResult.groupValues[1]}<redacted>@"
    }
    redacted = TELEGRAM_BOT_TOKEN_IN_PATH_REGEX.replace(redacted) { matchResult ->
        "${matchResult.groupValues[1]}<redacted>"
    }
    redacted = SENSITIVE_QUERY_PARAM_REGEX.replace(redacted) { matchResult ->
        "${matchResult.groupValues[1]}<redacted>"
    }
    return redacted
}

// ---------------------------------------------------------------------------
// Generic message redaction
// ---------------------------------------------------------------------------

/**
 * MongoDB / PostgreSQL / Redis / AMQP-style connection strings with inline credentials.
 * Conservative about what we consider a scheme to avoid matching arbitrary text.
 */
private val INLINE_CREDENTIAL_URL_REGEX = Regex(
    """\b([a-z][a-z0-9+.-]{0,31}://)([^\s:@/]{1,256}:[^\s@/]{1,256})@""",
    RegexOption.IGNORE_CASE
)

/**
 * `Authorization: Bearer <token>` / `Authorization: Basic <blob>` — shows up in HTTP
 * client debug dumps.
 */
private val AUTHORIZATION_HEADER_REGEX = Regex(
    """(Authorization:\s*(?:Bearer|Basic|Token|ApiKey)\s+)\S+""",
    RegexOption.IGNORE_CASE
)

/**
 * Long alphanumeric blobs that *look* like API keys. Deliberately tight — we want few
 * false positives in log text. Keys shorter than 24 chars are typically not credentials;
 * longer than 200 chars is usually a base64 payload that's OK to keep.
 *
 * Phase 9 (2026-05) extension:
 *  - GitHub `gh[psuhroaPSUHROA]_…` covers PAT (`ghp_`), OAuth scope (`ghs_`),
 *    user-to-server (`ghu_`), refresh (`ghr_`), and the newer `gho_` / `ghs_admin_`
 *    variants. Pre-Phase-9 only `ghp_` matched.
 *  - Stripe live / test keys (`sk_live_…`, `sk_test_…`, `rk_live_…`).
 *  - JWT (`eyJ…` — base64-encoded header `{"alg":...}` is the universal prefix).
 *  - Google API keys (`AIza…`) — service-account / OAuth token surfaces.
 */
private val LONG_SECRET_BLOB_REGEX = Regex(
    """\b(sk-[A-Za-z0-9_\-]{24,})\b""" +
        """|\bAKIA[A-Z0-9]{12,}\b""" +                          // AWS access keys
        """|\bgithub_pat_[A-Za-z0-9_]{20,}\b""" +               // GitHub fine-grained PAT
        """|\bgh[psuhroaPSUHROA]_[A-Za-z0-9]{30,}\b""" +        // GitHub PAT/OAuth/U2S/refresh
        """|\bxox[abpors]-[A-Za-z0-9\-]{10,}\b""" +             // Slack bot/user/refresh tokens
        """|\bxapp-[A-Za-z0-9\-]{10,}\b""" +                    // Slack app-level tokens
        """|\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{20,}\b""" +   // Stripe live/test keys
        """|\bAIza[0-9A-Za-z_\-]{30,}\b""" +                     // Google API keys
        """|\bya29\.[A-Za-z0-9_\-]{20,}""" +                     // Google OAuth access tokens
        """|\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\b""" // JWT (header.payload.sig)
)

/**
 * AWS secret access key — the 40-char base64 secret (paired with the `AKIA…` access
 * key id). Anchored on the `aws_secret_access_key` field name so we don't redact every
 * 40-char base64 blob in logs. Case-insensitive; matches `=`/`:` assignment with optional quotes.
 */
private val AWS_SECRET_KEY_REGEX = Regex(
    // Anchored on the `(aws_)secret_access_key` field name, so a 20+ base64 run after the
    // assignment is the secret (real AWS secrets are 40 chars; bound loosely + key-anchored
    // to avoid false positives on bare base64 elsewhere in the log line).
    """((?:aws[_-]?)?secret[_-]?access[_-]?key["']?\s*[=:]\s*["']?)([A-Za-z0-9/+]{20,})""",
    RegexOption.IGNORE_CASE
)

/**
 * Scrub arbitrary text for log output. Applies [redactSensitiveUrlForLogs] plus the
 * broader patterns above. Idempotent — safe to apply twice.
 *
 * Not a general-purpose secret scanner — it won't find one-off custom key formats.
 * Defense in depth: call this wherever you embed user-controlled text in a log message
 * but also keep using safer patterns (structured logging, explicit redaction at the
 * data-gather step).
 */
fun redactForLog(message: String): String {
    var s = message
    s = redactSensitiveUrlForLogs(s)
    s = INLINE_CREDENTIAL_URL_REGEX.replace(s) { m -> "${m.groupValues[1]}<redacted>@" }
    s = AUTHORIZATION_HEADER_REGEX.replace(s) { m -> "${m.groupValues[1]}<redacted>" }
    s = AWS_SECRET_KEY_REGEX.replace(s) { m -> "${m.groupValues[1]}<redacted>" }
    s = LONG_SECRET_BLOB_REGEX.replace(s, "<redacted>")
    return s
}
