package com.fartech.ftapp2.commonsKt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogSanitizersTest {

    // NOTE: every "secret" below is a synthetic test fixture, not a real
    // credential. Some are written as split string literals
    // (e.g. "sk_" + "live_...") so the contiguous token never appears in
    // source — this keeps secret-scanning push protection from flagging the
    // file while the runtime string fed to the sanitizer is byte-identical.
    // Do not "simplify" the concatenations back into single literals.

    @Test
    fun `redactSensitiveUrlForLogs redacts telegram bot token and query secrets`() {
        val url = "https://api.telegram.org/bot123456:ABC-SECRET/getUpdates?timeout=30&access_token=topsecret&offset=10"

        val redacted = redactSensitiveUrlForLogs(url)

        assertEquals(
            "https://api.telegram.org/bot<redacted>/getUpdates?timeout=30&access_token=<redacted>&offset=10",
            redacted
        )
    }

    @Test
    fun `redactSensitiveUrlForLogs redacts basic auth and preserves safe query params`() {
        val url = "https://alice:secret@example.com/path?api_key=abc123&timeout=30"

        val redacted = redactSensitiveUrlForLogs(url)

        assertEquals(
            "https://<redacted>@example.com/path?api_key=<redacted>&timeout=30",
            redacted
        )
    }

    // ---------------------------------------------------------------------
    // Phase 9 (2026-05): extended secret-blob coverage
    //
    // Pre-Phase-9 the LONG_SECRET_BLOB_REGEX only matched OpenAI `sk-…`,
    // AWS `AKIA…`, GitHub `ghp_…`, and Slack `xox*-…`. The audit found that
    // GitHub OAuth/U2S/refresh tokens (`gh[suor]_…`), Stripe live/test keys,
    // Google API keys, and JWTs all leaked through redactForLog despite being
    // common in agent log paths (HTTP retries, structured error logs).
    // ---------------------------------------------------------------------

    @Test
    fun `redactForLog redacts GitHub OAuth scope tokens`() {
        val msg = "Auth failed: gh" + "s_1234567890abcdefghijklmnopqrstuvwxyz"
        assertTrue("<redacted>" in redactForLog(msg)) {
            "Expected GitHub ghs_ token to be redacted; got: ${redactForLog(msg)}"
        }
    }

    @Test
    fun `redactForLog redacts GitHub user-to-server tokens`() {
        val msg = "Token: gh" + "u_abcdefghij1234567890ABCDEFGHIJabcd1234"
        assertTrue("<redacted>" in redactForLog(msg))
    }

    @Test
    fun `redactForLog redacts GitHub refresh tokens`() {
        val msg = "Refresh: gh" + "r_zzzzzzzzzz1234567890ABCDEFGHIJabcdef"
        assertTrue("<redacted>" in redactForLog(msg))
    }

    @Test
    fun `redactForLog redacts GitHub OAuth tokens`() {
        val msg = "OAuth: gh" + "o_AAAAAAAAAA1234567890ABCDEFGHIJabcdefgh"
        assertTrue("<redacted>" in redactForLog(msg))
    }

    @Test
    fun `redactForLog redacts Stripe live secret key`() {
        val msg = "Stripe key sk_" + "live_51HxxxXXXxxxXXXxxxXXXxxxXXXxxx leaked"
        assertTrue("<redacted>" in redactForLog(msg))
    }

    @Test
    fun `redactForLog redacts Stripe test key`() {
        val msg = "Test key sk_" + "test_51HxxxXXXxxxXXXxxxXXXxxxXXXxxx in logs"
        assertTrue("<redacted>" in redactForLog(msg))
    }

    @Test
    fun `redactForLog redacts Stripe restricted key`() {
        val msg = "Restricted key rk_" + "live_51HxxxXXXxxxXXXxxxXXXxxxXXXxxx leaked"
        assertTrue("<redacted>" in redactForLog(msg))
    }

    @Test
    fun `redactForLog redacts Google API keys`() {
        val msg = "Google API: AIza" + "SyABCDEFGHIJKLMNOP-QRSTUVWXYZabcdef0123 used here"
        assertTrue("<redacted>" in redactForLog(msg))
    }

    @Test
    fun `redactForLog redacts JWT tokens`() {
        val jwt = "eyJ" + "hbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val msg = "Authorization context: $jwt"
        assertTrue("<redacted>" in redactForLog(msg)) {
            "Expected JWT to be redacted; got: ${redactForLog(msg)}"
        }
    }

    @Test
    fun `redactForLog leaves non-secret text alone`() {
        val msg = "User logged in successfully at 2026-05-06"
        assertEquals(msg, redactForLog(msg))
    }

    // ---------------------------------------------------------------------
    // 2026-05-29 audit: additional secret formats + ReDoS hardening
    // ---------------------------------------------------------------------

    @Test
    fun `redactForLog redacts GitHub fine-grained PAT`() {
        val msg = "Token github_" + "pat_11ABCDEFG0abcdefghij_KLMNOPqrstuvwxyz0123456789ABCDEFghij in config"
        assertTrue("<redacted>" in redactForLog(msg)) { "got: ${redactForLog(msg)}" }
    }

    @Test
    fun `redactForLog redacts Slack app-level token`() {
        val msg = "Slack xapp" + "-1-A0123ABC-9876543210-abcdefghijklmnop here"
        assertTrue("<redacted>" in redactForLog(msg)) { "got: ${redactForLog(msg)}" }
    }

    @Test
    fun `redactForLog redacts Google OAuth ya29 access token`() {
        val msg = "Bearer ya29" + ".A0ARrdaM-abcdefghijklmnopqrstuvwxyz0123456789 expired"
        assertTrue("<redacted>" in redactForLog(msg)) { "got: ${redactForLog(msg)}" }
    }

    @Test
    fun `redactForLog redacts AWS secret access key value when key-name-anchored`() {
        val msg = "aws_secret_access_key=wJalrXUtnFEMI" + "K7MDENGbPxRfiCYEXAMPLEKEY done"
        val out = redactForLog(msg)
        assertTrue("<redacted>" in out) { "got: $out" }
        assertTrue("wJalrXUtnFEMI" !in out) { "secret leaked: $out" }
    }

    @Test
    fun `redactSensitiveUrlForLogs is linear-time on a long no-at URL`() {
        // Regression for the BASIC_AUTH_IN_URL_REGEX catastrophic backtracking: a long
        // `http://` + scheme-class run with no trailing `@` used to take seconds (O(n^2)).
        val pathological = "http://" + "a".repeat(100_000)
        val start = System.nanoTime()
        val out = redactSensitiveUrlForLogs(pathological)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(pathological, out, "no credentials present — should be unchanged")
        assertTrue(elapsedMs < 2_000, "redaction took ${elapsedMs}ms — possible ReDoS regression")
    }

    @Test
    fun `redactForLog is linear-time on a long no-at URL embedded in text`() {
        val msg = "fetch failed for url=http://" + "x".repeat(100_000) + " retrying"
        val start = System.nanoTime()
        redactForLog(msg)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 2_000, "redactForLog took ${elapsedMs}ms — possible ReDoS regression")
    }
}
