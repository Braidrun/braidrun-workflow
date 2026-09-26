package com.fartech.agents.tools

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.fartech.agents.commons.PreparedToolImage
import com.fartech.agents.commons.ToolResultMediaPolicy
import com.fartech.agents.commons.determineDefaultStrategy
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Browser contexts are keyed by the host-injected [ToolRunScope], so one run can never reach a page,
 * cookie jar or screenshot of another run in the same JVM, and a run's contexts close when it ends.
 * A fake Playwright browser ([FakeBrowserWorld]) stands in for Chromium.
 */
class BrowserToolsRunIsolationTest {

    private val world = FakeBrowserWorld()
    private val launchedWith = Collections.synchronizedList(mutableListOf<List<ConfigurationParameter>>())
    private val defaultIdleTtl = BrowserTools.idleTtlNanos

    @BeforeEach
    fun setUp() {
        BrowserTools.closeAll()
        ToolRunScope.resetForTests()
        BrowserTools.browserLauncherForTests = { parameters -> launchedWith += parameters; world.browser }
    }

    @AfterEach
    fun tearDown() {
        BrowserTools.closeAll()
        BrowserTools.browserLauncherForTests = null
        BrowserTools.idleTtlNanos = defaultIdleTtl
        ToolRunScope.resetForTests()
    }

    @Test
    fun `run A cannot see run B's page, cookies or screenshot through the same contextId`() = runBlocking {
        // One shared instance, like a cached tool registry: only the run scope separates the runs.
        val tools = BrowserTools(emptyList())
        val aOpened = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<Unit>()

        coroutineScope {
            launch {
                ToolRunScope.withRunScope("run-a") {
                    tools.browser_navigate("https://bank.example/account")
                    val set = tools.browser_set_cookies("""[{"name":"session","value":"a-secret","url":"https://bank.example"}]""")
                    assertTrue(set.startsWith("✅"), set)
                    aOpened.complete(Unit)
                    bDone.await()
                    // Run B's probing and closing left run A's page alone.
                    assertEquals("✅ Current URL: https://bank.example/account", tools.browser_get_url())
                    val cookies = tools.browser_get_cookies()
                    assertTrue(cookies.contains("a-secret"), cookies)
                }
            }
            launch {
                ToolRunScope.withRunScope("run-b") {
                    aOpened.await()
                    assertEquals("✅ Current URL: about:blank", tools.browser_get_url())
                    assertFalse(tools.browser_get_content().contains("bank.example"))
                    assertFalse(tools.browser_get_cookies().contains("a-secret"))
                    val screenshot = tools.captureScreenshot("run-b.png")
                    assertTrue(screenshot is BrowserTools.ScreenshotCapture.Saved, "$screenshot")
                    assertEquals("about:blank", String((screenshot as BrowserTools.ScreenshotCapture.Saved).png))
                    tools.browser_close_context()
                    tools.browser_close_all()
                    bDone.complete(Unit)
                }
            }
        }

        assertEquals(2, world.contexts.size, "each run gets its own context for contextId 'default'")
    }

    @Test
    fun `execution_id and session_id parameters do not grant access to another run's contexts`() = runBlocking {
        val spoofed = listOf(
            ConfigurationParameter("execution_id", JsonPrimitive("run-a")),
            ConfigurationParameter("session_id", JsonPrimitive("run-a")),
        )
        val aOpened = CompletableDeferred<Unit>()
        val probed = CompletableDeferred<Unit>()
        val runA = launch {
            ToolRunScope.withRunScope("run-a") {
                BrowserTools(emptyList()).browser_navigate("https://bank.example/account")
                aOpened.complete(Unit)
                probed.await()
            }
        }
        aOpened.await()

        // Same ids as run A, but no host scope: a private namespace.
        assertEquals("✅ Current URL: about:blank", BrowserTools(spoofed).browser_get_url())
        // Same ids as run A, but the host says this is run C.
        val otherRun = ToolRunScope.withRunScope("run-c") { BrowserTools(spoofed).browser_get_url() }
        assertEquals("✅ Current URL: about:blank", otherRun)

        probed.complete(Unit)
        runA.join()
    }

    @Test
    fun `calls outside any run scope get a namespace private to the tool instance`() = runBlocking {
        val first = BrowserTools(emptyList())
        val second = BrowserTools(emptyList())

        first.browser_navigate("https://first.example/")

        assertEquals("✅ Current URL: https://first.example/", first.browser_get_url())
        assertEquals("✅ Current URL: about:blank", second.browser_get_url())
        val scoped = ToolRunScope.withRunScope("run-a") { first.browser_get_url() }
        assertEquals("✅ Current URL: about:blank", scoped)
    }

    @Test
    fun `contexts close when the run completes`() = runBlocking {
        val tools = BrowserTools(emptyList())

        ToolRunScope.withRunScope("run-a") {
            tools.browser_navigate("https://a.example/", "one")
            tools.browser_navigate("https://a.example/", "two")
            assertEquals(2, BrowserTools.openContextCount())
        }

        assertEquals(0, BrowserTools.openContextCount())
        assertTrue(world.contexts.all { it.closed && it.page.closed })
    }

    @Test
    fun `contexts close when the run fails`() = runBlocking {
        val failure = runCatching {
            ToolRunScope.withRunScope("run-a") {
                BrowserTools(emptyList()).browser_navigate("https://a.example/")
                error("step failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "$failure")

        assertEquals(0, BrowserTools.openContextCount())
        assertTrue(world.contexts.single().closed)
    }

    @Test
    fun `contexts close when the run is cancelled`() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val run = launch {
            ToolRunScope.withRunScope("run-a") {
                BrowserTools(emptyList()).browser_navigate("https://a.example/")
                opened.complete(Unit)
                awaitCancellation()
            }
        }
        opened.await()

        run.cancelAndJoin()

        assertEquals(0, BrowserTools.openContextCount())
        assertTrue(world.contexts.single().closed)
    }

    @Test
    fun `a nested run is a run of its own and the outer run keeps its contexts`() = runBlocking {
        val tools = BrowserTools(emptyList())

        ToolRunScope.withRunScope("outer") {
            tools.browser_navigate("https://outer.example/")
            ToolRunScope.withRunScope("inner") {
                assertEquals("✅ Current URL: about:blank", tools.browser_get_url())
            }
            assertEquals(1, BrowserTools.openContextCount(), "only the inner run's context was closed")
            assertEquals("✅ Current URL: https://outer.example/", tools.browser_get_url())

            // The same id entered again (a re-entrant run) is released only by the outermost exit.
            ToolRunScope.withRunScope("outer") { tools.browser_get_url() }
            assertEquals(1, BrowserTools.openContextCount())
        }
        assertEquals(0, BrowserTools.openContextCount())
    }

    @Test
    fun `idle TTL closes contexts nobody uses and spares one a tool call is holding`() = runBlocking {
        BrowserTools.idleTtlNanos = TimeUnit.MINUTES.toNanos(1)
        val tools = BrowserTools(emptyList())
        val farFuture = System.nanoTime() + TimeUnit.HOURS.toNanos(1)

        tools.browser_navigate("https://idle.example/", "idle")
        world.onWait = { BrowserTools.closeIdleSessions(farFuture) }
        tools.browser_wait("5", "busy")

        val idle = world.contexts.single { it.page.url == "https://idle.example/" }
        val busy = world.contexts.single { it !== idle }
        assertTrue(idle.closed, "the idle context is reaped")
        assertFalse(busy.closed, "a context is never reaped while a call holds it")
        assertEquals(1, BrowserTools.openContextCount())
    }

    @Test
    fun `single-user process shares contexts across runs and keeps them after a run ends`() = runBlocking {
        ToolRunScope.declareSingleUserProcess()
        val tools = BrowserTools(emptyList())

        ToolRunScope.withRunScope("run-a") { tools.browser_navigate("https://cli.example/") }
        val seenByRunB = ToolRunScope.withRunScope("run-b") { BrowserTools(emptyList()).browser_get_url() }
        BrowserTools.closeIdleSessions(System.nanoTime() + TimeUnit.DAYS.toNanos(1))

        assertEquals("✅ Current URL: https://cli.example/", seenByRunB)
        assertEquals(1, BrowserTools.openContextCount())
        assertFalse(world.contexts.single().closed)
    }

    @Test
    fun `run parameters configure the shared browser launch only in a single-user process`() = runBlocking {
        val hostile = listOf(
            ConfigurationParameter("PLAYWRIGHT_ARGS", JsonArray(listOf(JsonPrimitive("--renderer-cmd-prefix=/bin/sh")))),
            ConfigurationParameter("PLAYWRIGHT_HEADLESS", JsonPrimitive(false)),
        )

        ToolRunScope.withRunScope("run-a") { BrowserTools(hostile).browser_navigate("https://a.example/") }
        assertEquals(listOf(emptyList<ConfigurationParameter>()), launchedWith.toList())

        BrowserTools.closeAll()
        ToolRunScope.declareSingleUserProcess()
        BrowserTools(hostile).browser_navigate("https://a.example/")
        assertEquals(hostile, launchedWith.last())
    }

    @Test
    fun `browser_screenshot attaches the caller's own page`() = runBlocking {
        world.screenshotBytes = {
            ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", it) }
                .toByteArray()
        }
        val tools = BrowserTools(emptyList())
        val screenshot = BrowserScreenshotTool(tools, ToolResultMediaPolicy(attachImages = true))

        val result = ToolRunScope.withRunScope("run-a") {
            tools.browser_navigate("https://a.example/")
            screenshot.execute(BrowserScreenshotTool.Args(path = "run-a.png"))
        }

        assertTrue(result.message.startsWith("✅ Screenshot saved to"), result.message)
        assertTrue(result.image is PreparedToolImage.Ready, "the page is the caller's own: ${result.image}")
    }

    @Test
    fun `tool calls made by a Koog agent run in the host's run scope`() = runBlocking {
        val tools = BrowserTools(emptyList())
        val registry = ToolRegistry { tools(tools) }
        // Two calls in one round: the parallel strategy runs them concurrently.
        val llm = ScriptedExecutor(
            Message.Assistant(
                parts = listOf("one", "two").map { contextId ->
                    MessagePart.Tool.Call(
                        id = "call_$contextId",
                        tool = "browser_navigate",
                        args = JsonObject(
                            mapOf(
                                "url" to JsonPrimitive("https://agent.example/$contextId"),
                                "contextId" to JsonPrimitive(contextId),
                            )
                        ),
                    )
                },
                metaInfo = ResponseMetaInfo.create(KoogClock.System),
                finishReason = "tool_calls",
            ),
            Message.Assistant(content = "done", metaInfo = ResponseMetaInfo.create(KoogClock.System), finishReason = "stop"),
        )
        val agent = GraphAIAgent(
            promptExecutor = llm,
            agentConfig = AIAgentConfig(
                prompt = prompt("browser-run-scope") { system("You are a test agent.") },
                model = model,
                maxAgentIterations = 20,
            ),
            strategy = determineDefaultStrategy(
                httpAccess = HttpAccess(),
                parameters = listOf(ConfigurationParameter("strategy", JsonPrimitive("just_work_parallel"))),
                toolRegistry = registry,
            ),
            toolRegistry = registry,
        )

        ToolRunScope.withRunScope("run-a") {
            assertEquals("done", agent.run("Open two pages."))
            // Direct calls in run-a see the agent's pages, so the agent's calls ran in run-a too
            // (outside it they would have landed in the instance's unscoped namespace).
            assertEquals("✅ Current URL: https://agent.example/one", tools.browser_get_url("one"))
            assertEquals("✅ Current URL: https://agent.example/two", tools.browser_get_url("two"))
        }

        assertEquals(0, BrowserTools.openContextCount())
        assertEquals(2, world.contexts.count { it.closed })
    }

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000,
    )

    private class ScriptedExecutor(vararg responses: Message.Assistant) : PromptExecutor() {
        private val queue = ArrayDeque(responses.toList())

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            queue.removeFirst()

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            flow { error("streaming is not used") }

        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
            error("multiple choices are not used")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("moderation is not used")

        override fun close() = Unit
    }
}
