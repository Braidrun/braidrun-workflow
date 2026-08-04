package com.fartech.agents.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the 2026-08-04 production incident: a Claude
 * subscription session limit was reported in a result shape the detector did
 * not recognise (`subtype=success` + `is_error=true` + `api_error_status=429`,
 * no `rate_limit_event`, no `terminal_reason`), so the credential was never
 * cooled down and the chat surfaced a raw mid-JSON fragment instead.
 */
class ExternalAgentToolsSubscriptionLimitTest {

    /** Verbatim from `journalctl -u braidrun-web` on app-app-1, 2026-08-04 09:25:59 UTC. */
    private val sessionLimitResult = """
        {"type":"result","subtype":"success","is_error":true,"api_error_status":429,"duration_ms":137074,"duration_api_ms":138204,"num_turns":6,"result":"You've hit your session limit · resets 12:10pm (UTC)","stop_reason":"stop_sequence","session_id":"5b26ea86-4ca2-458d-9401-9a720c62f05b","total_cost_usd":0.5642974,"usage":{"input_tokens":10,"output_tokens":42,"cache_creation_input_tokens":0,"cache_read_input_tokens":0},"permission_denials":[]}
    """.trimIndent()

    @Test
    fun `session limit without rate_limit_event is still a confirmed subscription 429`() {
        val limit = ExternalAgentTools.confirmedClaudeSubscriptionRateLimit(sessionLimitResult)
        assertNotNull(limit, "session-limit result must be recognised as a subscription rate limit")
        // 6 turns and non-zero tokens: partial progress, so no implicit replay.
        assertFalse(limit!!.safeWithoutExplicitReplay)
    }

    @Test
    fun `reset time is recovered from the human text when resetsAt is absent`() {
        val resetAt = ExternalAgentTools.claudeRateLimitResetAtMillis(sessionLimitResult)
        assertNotNull(resetAt, "expected 'resets 12:10pm (UTC)' to be parsed")
        val zoned = java.time.Instant.ofEpochMilli(resetAt!!).atZone(java.time.ZoneOffset.UTC)
        assertEquals(12, zoned.hour)
        assertEquals(10, zoned.minute)
        assertTrue(resetAt > System.currentTimeMillis(), "reset must be in the future")
    }

    @Test
    fun `machine readable resetsAt still wins over the text`() {
        val withResetsAt = """{"type":"result","is_error":true,"api_error_status":429,"resetsAt":1785900000}"""
        assertEquals(1785900000_000L, ExternalAgentTools.claudeRateLimitResetAtMillis(withResetsAt))
    }

    @Test
    fun `talking about a rate limit is not a rate limit`() {
        // Text alone must never cool a credential: without an HTTP 429 this is
        // just a failed run whose output mentions limits.
        val chatty = """{"type":"result","subtype":"success","is_error":true,"result":"the workflow hit a rate limit on OpenRouter"}"""
        assertNull(ExternalAgentTools.confirmedClaudeSubscriptionRateLimit(chatty))
    }

    @Test
    fun `a bare 429 with no corroboration still does not cool a credential`() {
        // An upstream burst limit is not an exhausted subscription.
        val bare = """{"type":"result","is_error":true,"api_error_status":429,"terminal_reason":"api_error","num_turns":3}"""
        assertNull(ExternalAgentTools.confirmedClaudeSubscriptionRateLimit(bare))
    }

    @Test
    fun `ordinary failures are not misread as rate limits`() {
        val plainError = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"tool crashed"}"""
        assertNull(ExternalAgentTools.confirmedClaudeSubscriptionRateLimit(plainError))
    }

    @Test
    fun `success results are never rate limits`() {
        val ok = """{"type":"result","subtype":"success","is_error":false,"result":"done"}"""
        assertNull(ExternalAgentTools.confirmedClaudeSubscriptionRateLimit(ok))
    }

    @Test
    fun `claude failure excerpt leads with the terminal fields, not the JSON tail`() {
        val stdout = sessionLimitResult + "\n"
        val excerpt = ExternalAgentTools.externalAgentFailureExcerpt(
            ExternalAgentTools.Engine.CLAUDE,
            stdout,
            ""
        )
        assertTrue(excerpt.startsWith("subtype=success, is_error=true, api_error_status=429"), excerpt)
        assertTrue(excerpt.contains("You've hit your session limit"), excerpt)
    }

    @Test
    fun `codex failures are decoded from turn_failed instead of the result envelope`() {
        // Codex emits ThreadEvents; there is no `result` object and no
        // `api_error_status`, so Claude's field names cannot be reused.
        val stdout = listOf(
            """{"type":"thread.started","thread_id":"t1"}""",
            """{"type":"turn.failed","error":{"message":"stream error: 429 Too Many Requests"}}"""
        ).joinToString("\n")
        val excerpt = ExternalAgentTools.externalAgentFailureExcerpt(
            ExternalAgentTools.Engine.CODEX,
            stdout,
            ""
        )
        assertTrue(excerpt.startsWith("codex_error=stream error: 429 Too Many Requests"), excerpt)
    }

    @Test
    fun `codex output is never fed to the claude rate-limit detector`() {
        val codexStdout = """{"type":"turn.failed","error":{"message":"429 rate limit"}}"""
        assertNull(ExternalAgentTools.confirmedClaudeSubscriptionRateLimit(codexStdout))
    }

    @Test
    fun `codex quota stops are classified from the error channel`() {
        val stdout = listOf(
            """{"type":"thread.started","thread_id":"t1"}""",
            """{"type":"turn.failed","error":{"message":"stream error: 429 Too Many Requests"}}"""
        ).joinToString("\n")
        assertTrue(ExternalAgentTools.isCodexRateLimitFailure(stdout, ""))
    }

    @Test
    fun `codex quota classification ignores what the model says`() {
        // agent_message is the model talking; it must never trigger a quota verdict.
        val stdout = listOf(
            """{"type":"thread.started","thread_id":"t1"}""",
            """{"type":"item.completed","item":{"type":"agent_message","text":"the API returned a rate limit, retry later"}}"""
        ).joinToString("\n")
        assertFalse(ExternalAgentTools.isCodexRateLimitFailure(stdout, ""))
    }

    @Test
    fun `codex quota is read from stderr too`() {
        assertTrue(ExternalAgentTools.isCodexRateLimitFailure("", "Error: usage limit reached for this account"))
        assertFalse(ExternalAgentTools.isCodexRateLimitFailure("", "Error: command not found: codex"))
    }

    @Test
    fun `stream-json output is decoded from the last result line`() {
        // Real runs emit one JSON object per line; the reason lives in the final
        // `type=result` record, which the old takeLast(2000) truncated away.
        val stdout = listOf(
            """{"type":"system","subtype":"init","session_id":"s1"}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"working"}]}}""",
            sessionLimitResult
        ).joinToString("\n")
        val excerpt = ExternalAgentTools.externalAgentFailureExcerpt(
            ExternalAgentTools.Engine.CLAUDE,
            stdout,
            ""
        )
        assertTrue(excerpt.startsWith("subtype=success, is_error=true, api_error_status=429"), excerpt)
    }

    @Test
    fun `stderr still wins over stdout for the raw portion`() {
        val excerpt = ExternalAgentTools.externalAgentFailureExcerpt(
            ExternalAgentTools.Engine.CLAUDE,
            sessionLimitResult,
            "codex: command not found"
        )
        assertTrue(excerpt.endsWith("codex: command not found"), excerpt)
    }
}
