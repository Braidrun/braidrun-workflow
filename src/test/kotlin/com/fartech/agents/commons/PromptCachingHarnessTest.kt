package com.fartech.agents.commons

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import com.fartech.agents.workflow.AgentEvent
import com.fartech.agents.workflow.WorkflowExecutor
import com.fartech.agents.workflow.WorkflowHostPolicy
import com.fartech.agents.workflow.WorkflowMonitor
import com.fartech.agents.workflow.WorkflowParser
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID

/**
 * Measures Anthropic prompt caching end to end: real agent loops and the real Koog Anthropic
 * client over HTTP, against [FakeAnthropicApi], whose `usage.cache_read_input_tokens` /
 * `cache_creation_input_tokens` come from [SimulatedAnthropicPromptCache] — so the numbers
 * reflect where the engine put its breakpoints and whether the prefix stayed byte-stable.
 *
 * Each test prints its per-request usage (`./gradlew test --tests '*PromptCachingHarnessTest' -i`).
 */
class PromptCachingHarnessTest {

    private val systemPrompt = (1..60).joinToString("\n") { "Rule $it: answer precisely, cite the tool output, never invent numbers." }

    private object LookupTool : SimpleTool<LookupTool.Args>(
        argsType = typeToken<Args>(),
        name = "lookup",
        description = "Looks up a fact by query.",
    ) {
        @Serializable
        data class Args(val q: String)

        override suspend fun execute(args: Args): String = "Fact for ${args.q}: " + "reference material ".repeat(120)
    }

    /** Two lookups, then the final answer — decided from the request so every run replays the same loop. */
    private val toolLoop: (Int, JsonObject) -> List<JsonObject> = { _, request ->
        val results = request["messages"]!!.jsonArray.sumOf { message ->
            (message.jsonObject["content"] as? JsonArray)?.count { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result" } ?: 0
        }
        if (results < 2) {
            listOf(FakeAnthropicApi.toolUse("toolu_$results", "lookup", JsonObject(mapOf("q" to JsonPrimitive("fact $results")))))
        } else {
            listOf(FakeAnthropicApi.text("Final answer."))
        }
    }

    private lateinit var api: FakeAnthropicApi

    @BeforeEach
    fun setUp() {
        WorkflowHostPolicy.resetForTests()
        WorkflowMonitor.clear()
    }

    @AfterEach
    fun tearDown() {
        if (::api.isInitialized) api.close()
        WorkflowMonitor.clear()
    }

    private fun startApi(minCacheableTokens: Int = 1024, script: (Int, JsonObject) -> List<JsonObject> = toolLoop) =
        FakeAnthropicApi(SimulatedAnthropicPromptCache(minCacheableTokens), script).also { api = it }

    private fun parameters(vararg extra: Pair<String, Any>): List<ConfigurationParameter> {
        val model = """{"provider":"anthropic","model":"claude-sonnet-4-6","base_url":"${api.baseUrl}"}"""
        val llmConfig = """{"models":[$model],"fallback":$model}"""
        return listOf(
            ConfigurationParameter("llm_config", Json.parseToJsonElement(llmConfig)),
            ConfigurationParameter("llm_provider_keys", JsonObject(mapOf("anthropic" to JsonPrimitive("sk-ant-test")))),
            ConfigurationParameter("disable_skills", JsonPrimitive(true)),
            // Keep runs hermetic: no `.agent_history/` files in the working directory.
            ConfigurationParameter("history_enabled", JsonPrimitive(false)),
        ) + extra.map { (key, value) ->
            ConfigurationParameter(key, if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString()))
        }
    }

    private fun runAgent(
        parameters: List<ConfigurationParameter>,
        streaming: Boolean = false,
        onStreamEnd: (StreamFrame.End) -> Unit = {},
    ): String? = runBlocking {
        buildAndRunAgent<String, String>(
            httpAccess = HttpAccess(),
            parameters = parameters,
            systemPrompt = systemPrompt,
            input = "What do the two facts say?",
            toolRegistry = ToolRegistry { tool(LookupTool) },
            skillManager = null,
            installFeatures = {
                handleEvents {
                    onLLMStreamingFrameReceived { ctx -> (ctx.streamFrame as? StreamFrame.End)?.let(onStreamEnd) }
                }
            },
            strategyBuilder = { _, _, _ ->
                if (streaming) singleRunStreamingWithParallelAbility("harness") else singleRunWithParallelAbility("harness", parallel = false)
            },
        ).second
    }

    /** Input as billed relative to the full input rate: cache reads 0.1×, 5-minute writes 1.25×. */
    private fun billedInputUnits(usages: List<SimulatedAnthropicPromptCache.Usage>): Double =
        usages.sumOf { it.inputTokens + 0.1 * it.cacheReadInputTokens + 1.25 * it.cacheCreationInputTokens }

    private fun report(title: String, usages: List<SimulatedAnthropicPromptCache.Usage>) {
        println("== $title")
        usages.forEachIndexed { i, u ->
            println("  #$i prompt=${u.promptTokens} input=${u.inputTokens} cache_read=${u.cacheReadInputTokens} cache_creation=${u.cacheCreationInputTokens}")
        }
        println("  billed input units=${"%.0f".format(billedInputUnits(usages))} (uncached: ${usages.sumOf { it.promptTokens }})")
    }

    private fun JsonObject.hasCacheControl() = "cache_control" in this
    private fun JsonObject.messageBlocks(): List<List<JsonObject>> =
        this["messages"]!!.jsonArray.map { m -> (m.jsonObject["content"] as? JsonArray)?.map { it.jsonObject } ?: emptyList() }

    @Test
    fun `a tool loop reads everything up to the previous round`() {
        startApi()
        assertEquals("Final answer.", runAgent(parameters()))

        val requests = api.exchanges.map { it.request }
        val usages = api.exchanges.map { it.usage }
        report("tool loop, caching on", usages)
        assertEquals(3, requests.size)

        val first = requests[0]
        assertTrue(first["tools"]!!.jsonArray.last().jsonObject.hasCacheControl(), "last tool")
        val system = first["system"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(true, false), system.map { it.hasCacheControl() }, "stable prefix marked, env tail not")
        assertTrue(system[1]["text"]!!.jsonPrimitive.content.contains("Date:"), "the env tail carries the date")
        assertFalse(first.messageBlocks().flatten().any { it.hasCacheControl() }, "a fresh request has no rolling marker")
        requests.drop(1).forEach { request ->
            assertTrue(request.messageBlocks().last().last().hasCacheControl(), "tool-loop tail: ${request["messages"].toString().takeLast(600)}")
        }

        assertEquals(0, usages[0].cacheReadInputTokens)
        assertTrue(usages[0].cacheCreationInputTokens > 0, "tools + system written")
        assertTrue(usages[1].cacheReadInputTokens >= usages[0].cacheCreationInputTokens, "round 2 reads tools + system")
        assertEquals(usages[1].promptTokens, usages[2].cacheReadInputTokens, "round 3 reads all of round 2")
        assertTrue(usages[2].inputTokens < usages[2].promptTokens / 5)
    }

    @Test
    fun `a new agent build reuses tools and system even though the env tail changed`() {
        startApi()
        runAgent(parameters("working_dir" to "/tmp/run-one"))
        val firstRun = api.exchanges.size
        runAgent(parameters("working_dir" to "/tmp/run-two"))

        val secondRunFirst = api.exchanges[firstRun]
        val env = { i: Int -> api.exchanges[i].request["system"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(env(0) != env(firstRun), "precondition: the env tails differ")
        report("second build, first request", listOf(secondRunFirst.usage))
        assertTrue(
            secondRunFirst.usage.cacheReadInputTokens >= api.exchanges[0].usage.cacheCreationInputTokens,
            "the second build reads the tools + stable system prefix: ${secondRunFirst.usage}",
        )
    }

    @Test
    fun `the date in a single system block invalidated the prefix, the split does not`() {
        startApi { _, _ -> listOf(FakeAnthropicApi.text("ok")) }
        val config = LLModelConfig(provider = "anthropic", model = "claude-sonnet-4-6", baseUrl = api.baseUrl)
        val (_, client) = createLLMClient(emptyList(), config, mapOf("anthropic" to "sk-ant-test"), HttpClientFactoryResolver.resolve())
        val model = determineLLMModel(config)
        val envs = listOf("\n\nDate: Sat Sep 26 15:00:01 CST 2026", "\n\nDate: Sat Sep 26 15:00:02 CST 2026")

        runBlocking {
            envs.forEach { env -> client.execute(prompt("joined") { system(systemPrompt + env); user("hi") }, model) }
            envs.forEach { env -> client.execute(prompt("split") { systemWithVolatileTail(systemPrompt, env); user("hi") }, model) }
        }
        val usages = api.exchanges.map { it.usage }
        report("joined vs split system prompt", usages)
        assertEquals(0, usages[1].cacheReadInputTokens, "joined: the new date rewrites the whole prefix")
        assertTrue(usages[3].cacheReadInputTokens > 0, "split: the stable prefix is read")
    }

    @Test
    fun `caching off is the baseline the savings are measured against`() {
        startApi()
        runAgent(parameters(ANTHROPIC_PROMPT_CACHING_PARAMETER to false))
        val off = api.exchanges.map { it.usage }
        api.close()
        startApi()
        runAgent(parameters())
        val on = api.exchanges.map { it.usage }

        report("caching off", off)
        report("caching on", on)
        assertTrue(off.all { it.cacheReadInputTokens == 0 && it.cacheCreationInputTokens == 0 })
        assertEquals(off.map { it.promptTokens }, on.map { it.promptTokens }, "caching changes billing, not the prompt")
        assertTrue(billedInputUnits(on) < 0.75 * billedInputUnits(off), "${billedInputUnits(on)} vs ${billedInputUnits(off)}")
    }

    @Test
    fun `streamed rounds stay live and report their cache usage`() {
        startApi()
        val ends = Collections.synchronizedList(mutableListOf<StreamFrame.End>())
        runAgent(parameters(), streaming = true) { ends += it }

        assertTrue(api.exchanges.isNotEmpty() && api.exchanges.all { it.streamed }, "the response cache must not turn streams into one-shot calls")
        assertEquals(api.exchanges.size, ends.size)
        val reported = ends.map { it.metaInfo.tokenUsage() }
        assertEquals(api.exchanges.map { it.usage.cacheReadInputTokens }, reported.map { it.cacheReadTokens ?: 0 })
        assertEquals(api.exchanges.map { it.usage.cacheCreationInputTokens }, reported.map { it.cacheCreationTokens ?: 0 })
        assertTrue(reported.last().cacheReadTokens!! > 0)
    }

    @Test
    fun `workflow llm_call_completed events carry cache reads and writes`() {
        // The lightweight preset's prompt is ~150 tokens, below every real minimum; this test
        // checks the event wiring, so the simulated minimum is lowered.
        startApi(minCacheableTokens = 64) { _, _ -> listOf(FakeAnthropicApi.text("ok")) }
        val yaml = """
            name: cache-events
            version: 1.0.0
            agents:
              helper:
                preset: lightweight
                overrides:
                  tool_set: [exit]
                  disable_skills: true
                  history_enabled: false
                  llm_provider_keys: { anthropic: sk-ant-test }
                  llm_config:
                    models:
                      - { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
                    fallback: { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
            workflow:
              - step: first
                agent: helper
                input: "Say hello."
              - step: second
                agent: helper
                depends_on: [first]
                input: "Say goodbye."
        """.trimIndent()
        val executionId = "cache-events-" + UUID.randomUUID()
        val result = runBlocking {
            WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList())
                .execute(WorkflowParser.parseYaml(yaml), emptyMap(), executionId)
        }
        assertTrue(result.success, "workflow failed: ${result.stepResults}")

        fun events(step: String): List<AgentEvent> =
            WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get(step)?.eventsSnapshot().orEmpty()
        val first = events("first").single { it.type == "llm_call_completed" }
        val second = events("second").single { it.type == "llm_call_completed" }
        val wire = api.exchanges.map { it.usage }
        report("workflow steps", wire)

        assertEquals(wire[0].inputTokens, first.inputTokens)
        assertEquals(wire[0].cacheCreationInputTokens, first.cacheCreationTokens ?: 0)
        assertTrue(wire[0].cacheCreationInputTokens > 0)
        assertEquals(wire[1].cacheReadInputTokens, second.cacheReadTokens ?: 0)
        assertTrue((second.cacheReadTokens ?: 0) > 0, "step two reads the prefix step one wrote")
        assertTrue(second.detail.orEmpty().contains("cache_read=${wire[1].cacheReadInputTokens}"), second.detail)

        val metrics = WorkflowMonitor.getMetrics(executionId)!!.stepMetrics["second"]!!
        assertEquals(second.inputTokens!!.toLong(), metrics.getInputTokens(), "token_usage is not counted twice")
        assertEquals(second.cacheReadTokens!!.toLong(), metrics.getCacheReadTokens())
    }
}
