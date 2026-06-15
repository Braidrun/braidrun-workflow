package com.fartech.agents.commons

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentHooksModelsTest {

    // =========================================================================
    // BraidrunHookEvent
    // =========================================================================

    @Nested
    inner class BraidrunHookEventTest {

        @Test
        fun `fromString parses all original agent lifecycle events`() {
            assertEquals(BraidrunHookEvent.AGENT_BOOTSTRAP, BraidrunHookEvent.fromString("agent:bootstrap"))
            assertEquals(BraidrunHookEvent.COMMAND, BraidrunHookEvent.fromString("command"))
            assertEquals(BraidrunHookEvent.COMMAND_NEW, BraidrunHookEvent.fromString("command:new"))
            assertEquals(BraidrunHookEvent.COMMAND_RESET, BraidrunHookEvent.fromString("command:reset"))
            assertEquals(BraidrunHookEvent.COMMAND_STOP, BraidrunHookEvent.fromString("command:stop"))
            assertEquals(BraidrunHookEvent.SESSION_COMPACT_BEFORE, BraidrunHookEvent.fromString("session:compact:before"))
            assertEquals(BraidrunHookEvent.SESSION_COMPACT_AFTER, BraidrunHookEvent.fromString("session:compact:after"))
            assertEquals(BraidrunHookEvent.GATEWAY_STARTUP, BraidrunHookEvent.fromString("gateway:startup"))
            assertEquals(BraidrunHookEvent.MESSAGE, BraidrunHookEvent.fromString("message"))
            assertEquals(BraidrunHookEvent.MESSAGE_RECEIVED, BraidrunHookEvent.fromString("message:received"))
            assertEquals(BraidrunHookEvent.MESSAGE_TRANSCRIBED, BraidrunHookEvent.fromString("message:transcribed"))
            assertEquals(BraidrunHookEvent.MESSAGE_PREPROCESSED, BraidrunHookEvent.fromString("message:preprocessed"))
            assertEquals(BraidrunHookEvent.MESSAGE_SENT, BraidrunHookEvent.fromString("message:sent"))
            assertEquals(BraidrunHookEvent.SESSION_START, BraidrunHookEvent.fromString("session:start"))
            assertEquals(BraidrunHookEvent.SESSION_END, BraidrunHookEvent.fromString("session:end"))
            assertEquals(BraidrunHookEvent.AGENT_ERROR, BraidrunHookEvent.fromString("agent:error"))
            assertEquals(BraidrunHookEvent.SKILL_ACTIVATED, BraidrunHookEvent.fromString("skill:activated"))
            assertEquals(BraidrunHookEvent.SKILL_DEACTIVATED, BraidrunHookEvent.fromString("skill:deactivated"))
            assertEquals(BraidrunHookEvent.AGENT_SHUTDOWN, BraidrunHookEvent.fromString("agent:shutdown"))
        }

        @Test
        fun `fromString parses Koog agent lifecycle events`() {
            assertEquals(BraidrunHookEvent.AGENT_STARTING, BraidrunHookEvent.fromString("agent:starting"))
            assertEquals(BraidrunHookEvent.AGENT_COMPLETED, BraidrunHookEvent.fromString("agent:completed"))
            assertEquals(BraidrunHookEvent.AGENT_CLOSING, BraidrunHookEvent.fromString("agent:closing"))
        }

        @Test
        fun `fromString parses Koog strategy events`() {
            assertEquals(BraidrunHookEvent.STRATEGY_STARTING, BraidrunHookEvent.fromString("strategy:starting"))
            assertEquals(BraidrunHookEvent.STRATEGY_COMPLETED, BraidrunHookEvent.fromString("strategy:completed"))
        }

        @Test
        fun `fromString parses Koog node execution events`() {
            assertEquals(
                BraidrunHookEvent.NODE_EXECUTION_STARTING,
                BraidrunHookEvent.fromString("node:execution:starting")
            )
            assertEquals(
                BraidrunHookEvent.NODE_EXECUTION_COMPLETED,
                BraidrunHookEvent.fromString("node:execution:completed")
            )
            assertEquals(BraidrunHookEvent.NODE_EXECUTION_FAILED, BraidrunHookEvent.fromString("node:execution:failed"))
        }

        @Test
        fun `fromString parses Koog subgraph events`() {
            assertEquals(
                BraidrunHookEvent.SUBGRAPH_EXECUTION_STARTING,
                BraidrunHookEvent.fromString("subgraph:execution:starting")
            )
            assertEquals(
                BraidrunHookEvent.SUBGRAPH_EXECUTION_COMPLETED,
                BraidrunHookEvent.fromString("subgraph:execution:completed")
            )
            assertEquals(
                BraidrunHookEvent.SUBGRAPH_EXECUTION_FAILED,
                BraidrunHookEvent.fromString("subgraph:execution:failed")
            )
        }

        @Test
        fun `fromString parses Koog LLM call events`() {
            assertEquals(BraidrunHookEvent.LLM_CALL_STARTING, BraidrunHookEvent.fromString("llm:call:starting"))
            assertEquals(BraidrunHookEvent.LLM_CALL_COMPLETED, BraidrunHookEvent.fromString("llm:call:completed"))
        }

        @Test
        fun `fromString parses Koog LLM streaming events`() {
            assertEquals(BraidrunHookEvent.LLM_STREAMING_STARTING, BraidrunHookEvent.fromString("llm:streaming:starting"))
            assertEquals(
                BraidrunHookEvent.LLM_STREAMING_COMPLETED,
                BraidrunHookEvent.fromString("llm:streaming:completed")
            )
            assertEquals(BraidrunHookEvent.LLM_STREAMING_FAILED, BraidrunHookEvent.fromString("llm:streaming:failed"))
            assertEquals(
                BraidrunHookEvent.LLM_STREAMING_FRAME_RECEIVED,
                BraidrunHookEvent.fromString("llm:streaming:frame:received")
            )
        }

        @Test
        fun `fromString parses Koog tool call events`() {
            assertEquals(BraidrunHookEvent.TOOL_CALL_STARTING, BraidrunHookEvent.fromString("tool:call:starting"))
            assertEquals(BraidrunHookEvent.TOOL_CALL_COMPLETED, BraidrunHookEvent.fromString("tool:call:completed"))
            assertEquals(BraidrunHookEvent.TOOL_CALL_FAILED, BraidrunHookEvent.fromString("tool:call:failed"))
            assertEquals(BraidrunHookEvent.TOOL_VALIDATION_FAILED, BraidrunHookEvent.fromString("tool:validation:failed"))
        }

        @Test
        fun `fromString returns null for unknown event`() {
            assertNull(BraidrunHookEvent.fromString("unknown:event"))
            assertNull(BraidrunHookEvent.fromString(""))
            assertNull(BraidrunHookEvent.fromString("totally-bogus"))
        }

        @Test
        fun `fromString is case insensitive`() {
            assertEquals(BraidrunHookEvent.AGENT_BOOTSTRAP, BraidrunHookEvent.fromString("Agent:Bootstrap"))
            assertEquals(BraidrunHookEvent.MESSAGE, BraidrunHookEvent.fromString("MESSAGE"))
        }

        @Test
        fun `fromString trims whitespace`() {
            assertEquals(BraidrunHookEvent.AGENT_BOOTSTRAP, BraidrunHookEvent.fromString("  agent:bootstrap  "))
        }

        @Test
        fun `toEventName returns colon-separated lowercase`() {
            assertEquals("agent:bootstrap", BraidrunHookEvent.AGENT_BOOTSTRAP.toEventName())
            assertEquals("command", BraidrunHookEvent.COMMAND.toEventName())
            assertEquals("command:new", BraidrunHookEvent.COMMAND_NEW.toEventName())
            assertEquals("message:received", BraidrunHookEvent.MESSAGE_RECEIVED.toEventName())
            assertEquals("tool:call:starting", BraidrunHookEvent.TOOL_CALL_STARTING.toEventName())
            assertEquals("llm:streaming:frame:received", BraidrunHookEvent.LLM_STREAMING_FRAME_RECEIVED.toEventName())
        }

        @Test
        fun `toEventName roundtrips with fromString for all events`() {
            for (event in BraidrunHookEvent.entries) {
                val eventName = event.toEventName()
                val parsed = BraidrunHookEvent.fromString(eventName)
                assertEquals(event, parsed, "Roundtrip failed for $event (eventName=$eventName)")
            }
        }

        @Test
        fun `all enum values have unique toEventName`() {
            val names = BraidrunHookEvent.entries.map { it.toEventName() }
            assertEquals(names.size, names.toSet().size, "Duplicate event names found")
        }
    }

    // =========================================================================
    // BraidrunHookRequires
    // =========================================================================

    @Nested
    inner class BraidrunHookRequiresTest {

        @Test
        fun `default values are all empty`() {
            val req = BraidrunHookRequires()
            assertTrue(req.bins.isEmpty())
            assertTrue(req.anyBins.isEmpty())
            assertTrue(req.env.isEmpty())
            assertTrue(req.os.isEmpty())
            assertTrue(req.config.isEmpty())
        }

        @Test
        fun `custom values preserved`() {
            val req = BraidrunHookRequires(
                bins = listOf("git", "node"),
                anyBins = listOf("python3", "python"),
                env = listOf("HOME"),
                os = listOf("darwin", "linux"),
                config = listOf("/etc/myconfig")
            )
            assertEquals(listOf("git", "node"), req.bins)
            assertEquals(listOf("python3", "python"), req.anyBins)
            assertEquals(listOf("HOME"), req.env)
            assertEquals(listOf("darwin", "linux"), req.os)
            assertEquals(listOf("/etc/myconfig"), req.config)
        }

        @Test
        fun `data class equality works`() {
            val a = BraidrunHookRequires(bins = listOf("git"))
            val b = BraidrunHookRequires(bins = listOf("git"))
            assertEquals(a, b)
        }
    }

    // =========================================================================
    // BraidrunAgentHook
    // =========================================================================

    @Nested
    inner class BraidrunAgentHookTest {

        @Test
        fun `minimal hook created`() {
            val hook = BraidrunAgentHook(
                name = "test-hook",
                description = "A test hook",
                events = listOf(BraidrunHookEvent.AGENT_BOOTSTRAP),
                content = "Hook body content",
                skillName = "test-skill"
            )
            assertEquals("test-hook", hook.name)
            assertEquals("A test hook", hook.description)
            assertEquals(1, hook.events.size)
            assertEquals(BraidrunHookEvent.AGENT_BOOTSTRAP, hook.events[0])
            assertEquals("Hook body content", hook.content)
            assertEquals("test-skill", hook.skillName)
        }

        @Test
        fun `default optional values are correct`() {
            val hook = BraidrunAgentHook(
                name = "h",
                description = "d",
                events = emptyList(),
                content = "c",
                skillName = "s"
            )
            assertEquals("", hook.emoji)
            assertEquals("", hook.homepage)
            assertNull(hook.requires)
            assertFalse(hook.isWorkspaceHook)
            assertNull(hook.virtualFilePath)
            assertNull(hook.handlerScript)
            assertFalse(hook.always)
        }

        @Test
        fun `hook with all fields`() {
            val requires = BraidrunHookRequires(bins = listOf("git"), os = listOf("darwin"))
            val hook = BraidrunAgentHook(
                name = "full-hook",
                description = "Full hook",
                events = listOf(BraidrunHookEvent.AGENT_BOOTSTRAP, BraidrunHookEvent.SESSION_START),
                content = "content",
                skillName = "skill",
                emoji = "🔖",
                homepage = "https://example.com",
                requires = requires,
                isWorkspaceHook = true,
                virtualFilePath = "REMINDER.md",
                always = true
            )
            assertEquals("🔖", hook.emoji)
            assertEquals("https://example.com", hook.homepage)
            assertNotNull(hook.requires)
            assertEquals(listOf("git"), hook.requires!!.bins)
            assertTrue(hook.isWorkspaceHook)
            assertEquals("REMINDER.md", hook.virtualFilePath)
            assertTrue(hook.always)
            assertEquals(2, hook.events.size)
        }
    }

    // =========================================================================
    // BraidrunHookContext
    // =========================================================================

    @Nested
    inner class BraidrunHookContextTest {

        @Test
        fun `default values are empty strings and empty map`() {
            val ctx = BraidrunHookContext(event = "agent:bootstrap")
            assertEquals("agent:bootstrap", ctx.event)
            assertEquals("", ctx.sessionKey)
            assertEquals("", ctx.workspaceDir)
            assertEquals("", ctx.skillName)
            assertTrue(ctx.config.isEmpty())
        }

        @Test
        fun `full context created`() {
            val ctx = BraidrunHookContext(
                event = "session:start",
                sessionKey = "sess-123",
                workspaceDir = "/tmp/workspace",
                skillName = "my-skill",
                config = mapOf("model" to "gpt-4", "agent" to "coder")
            )
            assertEquals("session:start", ctx.event)
            assertEquals("sess-123", ctx.sessionKey)
            assertEquals("/tmp/workspace", ctx.workspaceDir)
            assertEquals("my-skill", ctx.skillName)
            assertEquals(2, ctx.config.size)
            assertEquals("gpt-4", ctx.config["model"])
        }
    }

    // =========================================================================
    // BraidrunVirtualFile
    // =========================================================================

    @Nested
    inner class BraidrunVirtualFileTest {

        @Test
        fun `virtual file created correctly`() {
            val vf = BraidrunVirtualFile(path = "REMINDER.md", content = "Remember to improve!")
            assertEquals("REMINDER.md", vf.path)
            assertEquals("Remember to improve!", vf.content)
        }

        @Test
        fun `data class equality works`() {
            val a = BraidrunVirtualFile(path = "a.md", content = "c")
            val b = BraidrunVirtualFile(path = "a.md", content = "c")
            assertEquals(a, b)
        }
    }

    // =========================================================================
    // BraidrunHookScriptResult
    // =========================================================================

    @Nested
    inner class BraidrunHookScriptResultTest {

        @Test
        fun `default result has null and empty lists`() {
            val result = BraidrunHookScriptResult()
            assertNull(result.injectContent)
            assertTrue(result.virtualFiles.isEmpty())
            assertTrue(result.messages.isEmpty())
        }

        @Test
        fun `full result preserves all fields`() {
            val result = BraidrunHookScriptResult(
                injectContent = "injected text",
                virtualFiles = listOf(BraidrunVirtualFile("a.md", "content")),
                messages = listOf("msg1", "msg2")
            )
            assertEquals("injected text", result.injectContent)
            assertEquals(1, result.virtualFiles.size)
            assertEquals("a.md", result.virtualFiles[0].path)
            assertEquals(2, result.messages.size)
        }
    }

    // =========================================================================
    // BraidrunHookExecutor (additional JSON utility coverage)
    // =========================================================================

    @Nested
    inner class BraidrunHookExecutorExtendedTest {

        private val executor = BraidrunHookExecutor()

        @Test
        fun `parseScriptResult with empty JSON object returns empty result`() {
            val result = executor.parseScriptResult("{}")
            assertNull(result.injectContent)
            assertTrue(result.virtualFiles.isEmpty())
            assertTrue(result.messages.isEmpty())
        }

        @Test
        fun `parseScriptResult with only injectContent`() {
            val result = executor.parseScriptResult("""{"injectContent":"hello world"}""")
            assertEquals("hello world", result.injectContent)
            assertTrue(result.messages.isEmpty())
        }

        @Test
        fun `parseScriptResult ignores blank injectContent`() {
            val result = executor.parseScriptResult("""{"injectContent":"   "}""")
            assertNull(result.injectContent)
        }

        @Test
        fun `extractJsonString handles escaped quotes`() {
            val json = """{"key":"value with \"quotes\""}"""
            assertEquals("value with \"quotes\"", executor.extractJsonString(json, "key"))
        }

        @Test
        fun `extractJsonString handles backslash`() {
            val json = """{"key":"path\\to\\file"}"""
            assertEquals("path\\to\\file", executor.extractJsonString(json, "key"))
        }

        @Test
        fun `extractJsonString handles newlines and tabs`() {
            val json = """{"key":"line1\nline2\tend"}"""
            assertEquals("line1\nline2\tend", executor.extractJsonString(json, "key"))
        }

        @Test
        fun `extractJsonStringArray handles empty array`() {
            val json = """{"items":[]}"""
            assertTrue(executor.extractJsonStringArray(json, "items").isEmpty())
        }

        @Test
        fun `extractJsonStringArray with whitespace in array`() {
            val json = """{"items":[ "a" , "b" , "c" ]}"""
            assertEquals(listOf("a", "b", "c"), executor.extractJsonStringArray(json, "items"))
        }

        @Test
        fun `parseScriptResult with virtualFiles`() {
            val stdout = """{"virtualFiles":[{"path":"test.md","content":"test content"}],"messages":[]}"""
            val result = executor.parseScriptResult(stdout)
            assertEquals(1, result.virtualFiles.size)
            assertEquals("test.md", result.virtualFiles[0].path)
            assertEquals("test content", result.virtualFiles[0].content)
        }

        @Test
        fun `SUPPORTED_EXTENSIONS has expected values`() {
            assertEquals(listOf("py", "js", "ts", "kts"), BraidrunHookExecutor.SUPPORTED_EXTENSIONS)
        }
    }
}
