package com.fartech.agents.tools

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecResult
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Codex only reports usage in its final `turn.completed`; a run that is killed or
 * exits early is recovered from the session rollout Codex keeps under CODEX_HOME.
 * Rollout lines below mirror what codex-cli 0.159.0 writes
 * (`sessions/YYYY/MM/DD/rollout-<timestamp>-<thread id>.jsonl`).
 */
class ExternalAgentToolsCodexUsageRecoveryTest {

    private data class Event(val type: String, val summary: String, val detail: String?)

    /** Runs [onExecute] (which may write rollouts into the request's CODEX_HOME), then feeds stdout lines. */
    private class ScriptedExecutor(
        override val isDocker: Boolean = false,
        private val onExecute: (ExecRequest) -> ExecResult
    ) : SubprocessExecutor {
        var lastRequest: ExecRequest? = null
            private set

        override suspend fun execute(request: ExecRequest): ExecResult {
            lastRequest = request
            val result = onExecute(request)
            result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { request.stdoutLineCallback?.invoke(it) }
            return result
        }
    }

    private val fakeAuthJson = """{"tokens":{"access_token":"test-access","refresh_token":"test-refresh"}}"""

    private fun subscriptionParameters(vararg extra: ConfigurationParameter): List<ConfigurationParameter> = listOf(
        ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_MODE_PARAMETER, JsonPrimitive("subscription")),
        ConfigurationParameter(ExternalAgentTools.CODEX_AUTH_JSON_PARAMETER, JsonPrimitive(fakeAuthJson)),
        ConfigurationParameter(ExternalAgentTools.CODEX_MODEL_PARAMETER, JsonPrimitive("gpt-5.5"))
    ) + extra

    private val apiKeyParameters = listOf(ConfigurationParameter("openai_api_key", JsonPrimitive("sk-test-openai")))

    private fun tools(
        executor: SubprocessExecutor,
        parameters: List<ConfigurationParameter>,
        events: MutableList<Event>,
        executionId: String? = "exec-${UUID.randomUUID()}",
        sessionId: String? = null
    ) = ExternalAgentTools(
        executor = executor,
        parameters = parameters,
        userId = "usage-recovery-test",
        context = SubprocessToolContext(
            workspaceDir = File("."),
            executionId = executionId,
            stepName = "app_developer",
            sessionId = sessionId
        ),
        onMonitorEvent = { type, summary, detail -> events += Event(type, summary, detail) },
        trustExecutorSandbox = executor.isDocker
    )

    private fun tokenCountLine(input: Long, cached: Long, output: Long, reasoning: Long, ordinal: Int = 1): String =
        """{"timestamp":"2026-10-03T20:19:39.489Z","ordinal":$ordinal,"type":"event_msg","payload":{"type":"token_count",""" +
            """"info":{"total_token_usage":{"input_tokens":$input,"cached_input_tokens":$cached,"cache_write_input_tokens":0,""" +
            """"output_tokens":$output,"reasoning_output_tokens":$reasoning,"total_tokens":${input + output}},""" +
            """"last_token_usage":{"input_tokens":1,"cached_input_tokens":0,"cache_write_input_tokens":0,"output_tokens":1,""" +
            """"reasoning_output_tokens":0,"total_tokens":2},"model_context_window":258400},""" +
            """"rate_limits":{"limit_id":"codex","primary":null,"secondary":null}}}"""

    private val rateLimitOnlyLine =
        """{"timestamp":"2026-10-03T20:19:40.000Z","ordinal":9,"type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":{"limit_id":"codex"}}}"""

    private fun writeRollout(home: File, threadId: String, vararg lines: String, day: String = "2026/10/04"): File {
        val dir = File(home, "sessions/$day").apply { mkdirs() }
        val file = File(dir, "rollout-2026-10-04T04-19-39-$threadId.jsonl")
        file.writeText(
            buildString {
                append("""{"timestamp":"2026-10-03T20:19:39.455Z","ordinal":0,"type":"session_meta","payload":{"id":"$threadId","cli_version":"0.159.0"}}""").append('\n')
                lines.forEach { append(it).append('\n') }
            }
        )
        return file
    }

    private fun hostCodexHome(request: ExecRequest): File {
        val home = requireNotNull(request.env["CODEX_HOME"]) { "CODEX_HOME not set" }
        if (home.startsWith("/codex-home")) {
            return request.mounts.single { it.containerPath == home }.hostPath
        }
        return File(home)
    }

    private fun threadStarted(threadId: String) = """{"type":"thread.started","thread_id":"$threadId"}"""

    private fun field(detail: String?, key: String): String? =
        Regex("(?:^|[,\\s])$key=([^,]+)").find(detail.orEmpty())?.groupValues?.get(1)?.trim()

    private fun timeoutResult(stdout: String) =
        ExecResult(exitCode = -1, stdout = stdout, stderr = "killed\n[TIMED OUT after 60s]", durationMs = 60_000)

    @Test
    fun `timeout recovers the last cumulative rollout usage exactly once`(): Unit = runBlocking {
        val threadId = "01a1036c-1d3e-79b3-a870-ff80db94dbf7"
        var home: File? = null
        val executor = ScriptedExecutor { request ->
            home = hostCodexHome(request)
            writeRollout(
                home, threadId,
                tokenCountLine(input = 1_000, cached = 400, output = 50, reasoning = 10, ordinal = 3),
                rateLimitOnlyLine,
                tokenCountLine(input = 52_000, cached = 41_000, output = 3_100, reasoning = 900, ordinal = 11),
                // Killed mid-write: a truncated final line must be ignored.
                """{"timestamp":"2026-10-03T20:21:00.000Z","type":"event_msg","payload":{"type":"token_count","info":{"total_tok"""
            )
            timeoutResult(threadStarted(threadId) + "\n" + """{"type":"turn.started"}""")
        }
        val events = mutableListOf<Event>()

        assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
            runBlocking { tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "app_developer")) }
        }

        val finals = events.filter { it.type == "codex_sub_agent_usage_final" }
        assertEquals(1, finals.size, "events: $events")
        val detail = finals.single().detail
        val invocationId = field(events.first { it.type == "codex_sub_agent_starting" }.detail, "invocation_id")
        assertEquals(invocationId, field(detail, "invocation_id"))
        assertEquals("52000", field(detail, "input_tokens"))
        assertEquals("3100", field(detail, "output_tokens"))
        assertEquals("55100", field(detail, "total_tokens"))
        assertEquals("41000", field(detail, "cache_read"))
        assertEquals("true", field(detail, "partial"))
        assertEquals("timeout", field(detail, "reason"))
        assertTrue(events.any { it.type == "codex_sub_agent_timeout" })
        assertTrue(events.none { it.type == "codex_sub_agent_usage" })
        // The read happened before cleanup removed the ephemeral home.
        assertFalse(requireNotNull(home).exists(), "per-run CODEX_HOME should still be deleted")
    }

    @Test
    fun `normal completion does not emit a recovered usage event`(): Unit = runBlocking {
        val threadId = "thread-normal"
        val executor = ScriptedExecutor { request ->
            writeRollout(hostCodexHome(request), threadId, tokenCountLine(2_200, 1_400, 80, 10))
            ExecResult(
                0,
                listOf(
                    threadStarted(threadId),
                    """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"done"}}""",
                    """{"type":"turn.completed","usage":{"input_tokens":2200,"cached_input_tokens":1400,"cache_write_input_tokens":0,"output_tokens":80,"reasoning_output_tokens":10}}"""
                ).joinToString("\n"),
                "",
                1_000
            )
        }
        val events = mutableListOf<Event>()

        val text = tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x", name = "ok"))

        assertEquals("done", text)
        assertTrue(events.none { it.type == "codex_sub_agent_usage_final" }, "events: $events")
        assertEquals(1, events.count { it.type == "codex_sub_agent_usage" })
        assertEquals("2200", field(events.single { it.type == "codex_sub_agent_completed" }.detail, "input_tokens"))
    }

    @Test
    fun `timeout after turn completed usage does not double count`(): Unit = runBlocking {
        val threadId = "thread-hung-after-turn"
        val executor = ScriptedExecutor { request ->
            writeRollout(hostCodexHome(request), threadId, tokenCountLine(500, 0, 20, 0))
            timeoutResult(
                threadStarted(threadId) + "\n" +
                    """{"type":"turn.completed","usage":{"input_tokens":500,"cached_input_tokens":0,"output_tokens":20}}"""
            )
        }
        val events = mutableListOf<Event>()

        assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
            runBlocking { tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }

        assertTrue(events.none { it.type == "codex_sub_agent_usage_final" }, "events: $events")
        assertEquals(1, events.count { it.type == "codex_sub_agent_usage" })
    }

    @Test
    fun `malformed or missing rollout emits nothing and does not throw`(): Unit = runBlocking {
        for (writeGarbage in listOf(true, false)) {
            val executor = ScriptedExecutor { request ->
                if (writeGarbage) {
                    writeRollout(
                        hostCodexHome(request), "thread-garbage",
                        "not json at all",
                        """{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":"oops"}}}""",
                        """{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{}}}}""",
                        rateLimitOnlyLine
                    )
                }
                timeoutResult(threadStarted("thread-garbage"))
            }
            val events = mutableListOf<Event>()

            assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
                runBlocking { tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
            }

            assertTrue(events.none { it.type == "codex_sub_agent_usage_final" }, "garbage=$writeGarbage events: $events")
            assertTrue(events.any { it.type == "codex_sub_agent_timeout" })
        }
    }

    @Test
    fun `newest rollout wins when the thread id is unknown`(): Unit = runBlocking {
        val executor = ScriptedExecutor { request ->
            val home = hostCodexHome(request)
            val older = writeRollout(home, "thread-old", tokenCountLine(100, 0, 10, 0), day = "2026/10/03")
            val newer = writeRollout(home, "thread-new", tokenCountLine(7_000, 6_000, 300, 0), day = "2026/10/03")
            older.setLastModified(System.currentTimeMillis() - 60_000)
            newer.setLastModified(System.currentTimeMillis())
            // Killed before codex printed thread.started.
            timeoutResult("")
        }
        val events = mutableListOf<Event>()

        assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
            runBlocking { tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }

        val detail = events.single { it.type == "codex_sub_agent_usage_final" }.detail
        assertEquals("7000", field(detail, "input_tokens"))
        assertEquals("300", field(detail, "output_tokens"))
    }

    @Test
    fun `rollout of the run's own thread beats a newer sibling rollout`() {
        val home = Files.createTempDirectory("codex-rollouts").toFile()
        try {
            val own = writeRollout(home, "thread-main", tokenCountLine(900, 0, 90, 0))
            val sibling = writeRollout(home, "thread-subagent", tokenCountLine(5, 0, 1, 0), day = "2026/10/05")
            own.setLastModified(System.currentTimeMillis() - 60_000)
            sibling.setLastModified(System.currentTimeMillis())

            assertEquals(own, ExternalAgentTools.findCodexRollout(home, "thread-main", exclusive = true))
            assertEquals(sibling, ExternalAgentTools.findCodexRollout(home, null, exclusive = true))
            // A shared home is never guessed at.
            assertNull(ExternalAgentTools.findCodexRollout(home, null, exclusive = false))
            assertNull(ExternalAgentTools.findCodexRollout(home, "thread-other", exclusive = false))
            assertEquals(
                ExternalAgentTools.CodexRolloutUsage(inputTokens = 900, cachedInputTokens = 0, outputTokens = 90, reasoningOutputTokens = 0),
                ExternalAgentTools.readCodexRolloutUsage(own)
            )
            assertNull(ExternalAgentTools.readCodexRolloutUsage(File(home, "missing.jsonl")))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `non-zero exit without turn usage recovers it as failed`(): Unit = runBlocking {
        val threadId = "thread-failed"
        val executor = ScriptedExecutor { request ->
            writeRollout(hostCodexHome(request), threadId, tokenCountLine(3_000, 1_000, 120, 40))
            ExecResult(
                1,
                threadStarted(threadId) + "\n" + """{"type":"turn.failed","error":{"message":"stream disconnected"}}""",
                "error: stream disconnected",
                5_000
            )
        }
        val events = mutableListOf<Event>()

        assertThrows(ExternalAgentTools.ExternalAgentExecutionException::class.java) {
            runBlocking { tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }

        val detail = events.single { it.type == "codex_sub_agent_usage_final" }.detail
        assertEquals("3000", field(detail, "input_tokens"))
        assertEquals("failed", field(detail, "reason"))
        assertEquals("true", field(detail, "partial"))
        assertTrue(events.any { it.type == "codex_sub_agent_failed" })
    }

    @Test
    fun `non-zero exit that already carried turn usage is not recovered again`(): Unit = runBlocking {
        val threadId = "thread-failed-late"
        val executor = ScriptedExecutor { request ->
            writeRollout(hostCodexHome(request), threadId, tokenCountLine(3_000, 1_000, 120, 40))
            ExecResult(
                1,
                threadStarted(threadId) + "\n" +
                    """{"type":"turn.completed","usage":{"input_tokens":3000,"cached_input_tokens":1000,"output_tokens":120}}""",
                "error: shutdown failed",
                5_000
            )
        }
        val events = mutableListOf<Event>()

        assertThrows(ExternalAgentTools.ExternalAgentExecutionException::class.java) {
            runBlocking { tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }

        val finals = events.filter { it.type == "codex_sub_agent_usage_final" }
        assertEquals(1, finals.size, "events: $events")
        assertNull(field(finals.single().detail, "reason"), "only the stdout aggregate should be published")
    }

    @Test
    fun `cancellation recovers usage before the per-run home is deleted`(): Unit = runBlocking {
        val threadId = "thread-cancelled"
        var home: File? = null
        val executor = object : SubprocessExecutor {
            override suspend fun execute(request: ExecRequest): ExecResult {
                home = hostCodexHome(request)
                writeRollout(home, threadId, tokenCountLine(8_000, 7_000, 400, 100))
                request.stdoutLineCallback?.invoke(threadStarted(threadId))
                throw CancellationException("step timeout")
            }
        }
        val events = mutableListOf<Event>()

        assertThrows(CancellationException::class.java) {
            runBlocking { tools(executor, subscriptionParameters(), events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }

        val detail = events.single { it.type == "codex_sub_agent_usage_final" }.detail
        assertEquals("8000", field(detail, "input_tokens"))
        assertEquals("cancelled", field(detail, "reason"))
        assertFalse(requireNotNull(home).exists())
    }

    @Test
    fun `docker api-key mode bind-mounts a host CODEX_HOME and recovers from it`(): Unit = runBlocking {
        val threadId = "thread-docker"
        var hostHome: File? = null
        val executor = ScriptedExecutor(isDocker = true) { request ->
            assertEquals("/codex-home", request.env["CODEX_HOME"])
            assertEquals("sk-test-openai", request.env["OPENAI_API_KEY"])
            val mount = request.mounts.single { it.containerPath == "/codex-home" }
            assertFalse(mount.readOnly)
            hostHome = mount.hostPath
            assertFalse(File(mount.hostPath, "auth.json").exists(), "API-key mode must not write an auth.json")
            writeRollout(mount.hostPath, threadId, tokenCountLine(12_000, 9_000, 600, 200))
            timeoutResult(threadStarted(threadId))
        }
        val events = mutableListOf<Event>()

        assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
            runBlocking { tools(executor, apiKeyParameters, events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }

        val detail = events.single { it.type == "codex_sub_agent_usage_final" }.detail
        assertEquals("12000", field(detail, "input_tokens"))
        assertEquals("9000", field(detail, "cache_read"))
        assertFalse(requireNotNull(hostHome).exists(), "API-key CODEX_HOME should be removed after the run")
    }

    @Test
    fun `native api-key mode without an engine-managed home emits nothing`(): Unit = runBlocking {
        val executor = ScriptedExecutor { request ->
            assertNull(request.env["CODEX_HOME"])
            timeoutResult(threadStarted("thread-native"))
        }
        val events = mutableListOf<Event>()

        assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
            runBlocking { tools(executor, apiKeyParameters, events).runCodexSubAgent(ExternalAgentContext(prompt = "x")) }
        }

        assertTrue(events.none { it.type == "codex_sub_agent_usage_final" })
    }

    @Test
    fun `resumed thread charges only what this invocation added`(): Unit = runBlocking {
        val home = Files.createTempDirectory("codex-persistent-home").toFile()
        try {
            val threadId = "thread-resumed"
            val rollout = writeRollout(home, threadId, tokenCountLine(2_200, 1_400, 80, 10))
            val executor = ScriptedExecutor { request ->
                assertEquals(home.absolutePath, request.env["CODEX_HOME"])
                rollout.appendText(tokenCountLine(3_200, 1_800, 130, 20, ordinal = 27) + "\n")
                timeoutResult(threadStarted(threadId))
            }
            val events = mutableListOf<Event>()
            val params = subscriptionParameters(
                ConfigurationParameter(ExternalAgentTools.CODEX_HOME_DIR_PARAMETER, JsonPrimitive(home.absolutePath))
            )

            assertThrows(ExternalAgentTools.ExternalAgentTimeoutException::class.java) {
                runBlocking {
                    tools(executor, params, events, executionId = null, sessionId = "chat-1")
                        .runCodexSubAgent(ExternalAgentContext(prompt = "x", resumeSessionId = threadId))
                }
            }

            val detail = events.single { it.type == "codex_sub_agent_usage_final" }.detail
            assertEquals("1000", field(detail, "input_tokens"))
            assertEquals("50", field(detail, "output_tokens"))
            assertEquals("400", field(detail, "cache_read"))
            assertTrue(rollout.exists(), "a configured CODEX_HOME is persistent")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `clean exit whose turn usage never arrived is recovered as complete`(): Unit = runBlocking {
        val threadId = "thread-truncated"
        val executor = ScriptedExecutor { request ->
            writeRollout(hostCodexHome(request), threadId, tokenCountLine(40_000, 30_000, 2_000, 500))
            ExecResult(
                0,
                threadStarted(threadId) + "\n" +
                    """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"done"}}""" +
                    SubprocessExecutor.STREAM_TRUNCATION_MARKER,
                "",
                9_000
            )
        }
        val events = mutableListOf<Event>()

        val result = tools(executor, subscriptionParameters(), events)
            .runConversation(ExternalAgentTools.Engine.CODEX, ExternalAgentContext(prompt = "x"))

        assertEquals("done", result.text)
        // Callers metering from the returned result (the assistant) see it too.
        assertEquals(40_000L, result.inputTokens)
        assertEquals(2_000L, result.outputTokens)
        val detail = events.single { it.type == "codex_sub_agent_usage_final" }.detail
        assertEquals("40000", field(detail, "input_tokens"))
        assertEquals("false", field(detail, "partial"))
        assertEquals("missing_usage", field(detail, "reason"))
        assertNotNull(events.single { it.type == "codex_sub_agent_completed" })
        assertNull(field(events.single { it.type == "codex_sub_agent_completed" }.detail, "input_tokens"))
    }
}
