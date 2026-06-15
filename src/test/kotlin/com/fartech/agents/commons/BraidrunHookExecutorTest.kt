package com.fartech.agents.commons

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BraidrunHookExecutorTest {

    private val executor = BraidrunHookExecutor()

    // -------------------------------------------------------------------------
    // extractJsonString
    // -------------------------------------------------------------------------

    @Test
    fun `extractJsonString returns plain ASCII value`() {
        val json = """{"injectContent":"hello world"}"""
        assertEquals("hello world", executor.extractJsonString(json, "injectContent"))
    }

    @Test
    fun `extractJsonString decodes standard escape sequences`() {
        val json = """{"msg":"line1\nline2\ttabbed"}"""
        assertEquals("line1\nline2\ttabbed", executor.extractJsonString(json, "msg"))
    }

    @Test
    fun `extractJsonString decodes basic plane unicode escape`() {
        // \u00E9 = é
        val json = """{"msg":"\u00E9l\u00E8ve"}"""
        assertEquals("élève", executor.extractJsonString(json, "msg"))
    }

    @Test
    fun `extractJsonString decodes surrogate pair emoji (brain)`() {
        // 🧠 = U+1F9E0 encoded as surrogate pair \uD83E\uDDE0
        val json = """{"msg":"\uD83E\uDDE0 Self-improvement hook active"}"""
        val result = executor.extractJsonString(json, "msg")
        assertEquals("🧠 Self-improvement hook active", result)
    }

    @Test
    fun `extractJsonString decodes surrogate pair emoji (speech bubble)`() {
        // 💬 = U+1F4AC → \uD83D\uDCAC
        val json = """{"msg":"\uD83D\uDCAC hello"}"""
        assertEquals("💬 hello", executor.extractJsonString(json, "msg"))
    }

    @Test
    fun `extractJsonString returns null for missing key`() {
        val json = """{"other":"value"}"""
        assertNull(executor.extractJsonString(json, "missing"))
    }

    @Test
    fun `extractJsonString handles multiple emoji in sequence`() {
        // 🧠💬
        val json = """{"msg":"\uD83E\uDDE0\uD83D\uDCAC done"}"""
        assertEquals("🧠💬 done", executor.extractJsonString(json, "msg"))
    }

    // -------------------------------------------------------------------------
    // extractJsonStringArray
    // -------------------------------------------------------------------------

    @Test
    fun `extractJsonStringArray returns plain ASCII items`() {
        val json = """{"messages":["hello","world"]}"""
        assertEquals(listOf("hello", "world"), executor.extractJsonStringArray(json, "messages"))
    }

    @Test
    fun `extractJsonStringArray decodes surrogate pair emoji in array items`() {
        // Reproduces the exact scenario from the execution log:
        // [Hooks] 💬 ud83eudde0 Self-improvement hook active on braidrun-agent
        // The hook script outputs: {"messages":["\uD83E\uDDE0 Self-improvement hook active on braidrun-agent"]}
        val json = """{"messages":["\uD83E\uDDE0 Self-improvement hook active on braidrun-agent"]}"""
        val result = executor.extractJsonStringArray(json, "messages")
        assertEquals(1, result.size)
        assertEquals("🧠 Self-improvement hook active on braidrun-agent", result[0])
    }

    @Test
    fun `extractJsonStringArray decodes mixed ascii and unicode items`() {
        val json = """{"messages":["plain text","\uD83E\uDDE0 emoji item","more text"]}"""
        val result = executor.extractJsonStringArray(json, "messages")
        assertEquals(listOf("plain text", "🧠 emoji item", "more text"), result)
    }

    @Test
    fun `extractJsonStringArray returns empty list for missing key`() {
        val json = """{"other":"value"}"""
        assertEquals(emptyList<String>(), executor.extractJsonStringArray(json, "messages"))
    }

    @Test
    fun `extractJsonStringArray decodes standard escape sequences in items`() {
        val json = """{"messages":["line1\nline2","tab\there"]}"""
        assertEquals(listOf("line1\nline2", "tab\there"), executor.extractJsonStringArray(json, "messages"))
    }

    // -------------------------------------------------------------------------
    // parseScriptResult (end-to-end)
    // -------------------------------------------------------------------------

    @Test
    fun `parseScriptResult decodes emoji in messages field`() {
        val stdout =
            """{"injectContent":null,"virtualFiles":[],"messages":["\uD83E\uDDE0 Self-improvement hook active on braidrun-agent"]}"""
        val result = executor.parseScriptResult(stdout)
        assertEquals(1, result.messages.size)
        assertEquals("🧠 Self-improvement hook active on braidrun-agent", result.messages[0])
    }

    @Test
    fun `parseScriptResult decodes emoji in injectContent field`() {
        val stdout = """{"injectContent":"\uD83E\uDDE0 Remember to improve!","virtualFiles":[],"messages":[]}"""
        val result = executor.parseScriptResult(stdout)
        assertEquals("🧠 Remember to improve!", result.injectContent)
    }

    @Test
    fun `parseScriptResult returns empty result for empty stdout`() {
        val result = executor.parseScriptResult("")
        assertNull(result.injectContent)
        assertTrue(result.messages.isEmpty())
        assertTrue(result.virtualFiles.isEmpty())
    }
}
