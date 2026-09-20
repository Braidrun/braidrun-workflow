package com.fartech.agents.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalAgentToolsAuthClassificationTest {
    @Test
    fun `application code and tool output do not expire the subscription on timeout`() {
        val stdout = """{"type":"item.completed","item":{"type":"command_execution","aggregated_output":"case 401: authentication failed; run codex login"}}
{"type":"item.completed","item":{"type":"agent_message","text":"Handle 401 unauthorized in the generated App"}}"""
        val stderr = "\n[Container error: Awaiting status code timeout.]"
        assertFalse(ExternalAgentTools.isCodexAuthFailure(stdout, stderr))
        assertTrue(ExternalAgentTools.isSubprocessTimeout(-1, stderr))
        assertFalse(ExternalAgentTools.isSubprocessTimeout(1, stderr))
    }

    @Test
    fun `genuine CLI error channels still report authentication failures`() {
        assertTrue(ExternalAgentTools.isCodexAuthFailure("", "HTTP 401 Unauthorized"))
        assertTrue(ExternalAgentTools.isCodexAuthFailure("""{"type":"turn.failed","error":{"message":"refresh failed: invalid_grant"}}""", ""))
        assertTrue(ExternalAgentTools.isCodexAuthFailure("""{"type":"error","message":"token expired"}""", ""))
        assertFalse(ExternalAgentTools.isCodexAuthFailure("", "worker 40123 exited"))
        assertFalse(ExternalAgentTools.isCodexAuthFailure("", "model not supported: HTTP 400"))
        assertTrue(ExternalAgentTools.isSubprocessTimeout(-1, "[TIMED OUT after 1800s]"))
    }
}
