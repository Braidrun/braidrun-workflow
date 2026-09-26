package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class PromptCachingLLMClientTest {

    private val stable = "You are a careful assistant. ".repeat(40)
    private val env = "\n\nDate: Sat Sep 26 15:00:00 CST 2026\nOS: test"

    private val tools = listOf(
        ToolDescriptor("lookup", "Looks things up.", listOf(ToolParameterDescriptor("q", "query", ToolParameterType.String))),
        ToolDescriptor("exit", "Finishes."),
    )

    private fun splitPrompt(turns: ai.koog.prompt.dsl.PromptBuilder.() -> Unit = { user("question") }): Prompt =
        prompt("t") {
            systemWithVolatileTail(stable = stable, volatileTail = env)
            turns()
        }

    private fun toolLoopPrompt(): Prompt = splitPrompt {
        user("question")
        message(
            Message.Assistant(
                parts = listOf(MessagePart.Tool.Call(id = "t1", tool = "lookup", args = """{"q":"x"}""")),
                metaInfo = ResponseMetaInfo(Instant.fromEpochMilliseconds(0)),
            )
        )
        user(listOf(MessagePart.Tool.Result(id = "t1", tool = "lookup", output = "result text")))
    }

    private fun Prompt.systemParts() = (messages.first() as Message.System).parts
    private fun Prompt.allCacheControls(): List<CacheControl> =
        messages.flatMap { m -> m.parts.flatMap { p -> listOfNotNull(p.cacheControl) + ((p as? MessagePart.Tool.Result)?.parts?.mapNotNull { it.cacheControl } ?: emptyList()) } }

    @Test
    fun `Anthropic tool-loop round gets system, tail and last-tool breakpoints`() {
        val request = prepareCacheControls(toolLoopPrompt(), tools, LLMProvider.Anthropic, placeBreakpoints = true)

        val system = request.prompt.systemParts()
        assertEquals(AnthropicCacheControl.Default, system[0].cacheControl, "stable prefix is the read point")
        assertNull(system[1].cacheControl, "the volatile env tail stays after the breakpoint")
        val tail = request.prompt.messages.last().parts.single()
        assertEquals(AnthropicCacheControl.Default, tail.cacheControl, "tool-loop tail is the rolling breakpoint")
        assertNull(request.tools.first().cacheControl)
        assertEquals(AnthropicCacheControl.Default, request.tools.last().cacheControl)
        assertEquals(2, request.prompt.allCacheControls().size, "one slot stays free for callers")
    }

    @Test
    fun `a continuation nudge after the tool results takes the tail breakpoint`() {
        val prompt = toolLoopPrompt().withMessages { it + Message.User("Continue after the tool result.", it.last().metaInfo as ai.koog.prompt.message.RequestMetaInfo) }
        val request = prepareCacheControls(prompt, tools, LLMProvider.Anthropic, placeBreakpoints = true)

        assertEquals(AnthropicCacheControl.Default, request.prompt.messages.last().parts.single().cacheControl)
        assertNull(request.prompt.messages[request.prompt.messages.lastIndex - 1].parts.single().cacheControl)
    }

    @Test
    fun `a new question after a text answer is not a tool-loop round`() {
        val prompt = splitPrompt {
            user("first question")
            message(Message.Assistant(content = "text answer", metaInfo = ResponseMetaInfo(Instant.fromEpochMilliseconds(0))))
            user("second question")
        }
        val request = prepareCacheControls(prompt, tools, LLMProvider.Anthropic, placeBreakpoints = true)

        assertNull(request.prompt.messages.last().parts.single().cacheControl)
    }

    @Test
    fun `a request that is not a tool-loop round gets no tail breakpoint`() {
        val request = prepareCacheControls(splitPrompt(), tools, LLMProvider.Anthropic, placeBreakpoints = true)

        assertEquals(AnthropicCacheControl.Default, request.prompt.systemParts()[0].cacheControl)
        assertNull(request.prompt.messages.last().parts.single().cacheControl)
        assertEquals(AnthropicCacheControl.Default, request.tools.last().cacheControl)
    }

    @Test
    fun `without a split the whole system prompt is the stable prefix, and blank text is never marked`() {
        val plain = prepareCacheControls(prompt("t") { system(stable); user("q") }, emptyList(), LLMProvider.Anthropic, true)
        assertEquals(AnthropicCacheControl.Default, plain.prompt.systemParts().single().cacheControl)

        val blank = prepareCacheControls(prompt("t") { system("   "); user("q") }, emptyList(), LLMProvider.Anthropic, true)
        assertNull(blank.prompt.systemParts().single().cacheControl)
    }

    @Test
    fun `caching off adds nothing and keeps the prompt as is`() {
        val prompt = toolLoopPrompt()
        val request = prepareCacheControls(prompt, tools, LLMProvider.Anthropic, placeBreakpoints = false)

        assertSame(prompt, request.prompt)
        assertTrue(request.tools.all { it.cacheControl == null })
    }

    @Test
    fun `other providers get the split system prompt joined back and no markers`() {
        val marked = prepareCacheControls(toolLoopPrompt(), tools, LLMProvider.Anthropic, placeBreakpoints = true)
        val request = prepareCacheControls(marked.prompt, marked.tools, LLMProvider.OpenAI, placeBreakpoints = true)

        assertEquals(listOf(stable + env), request.prompt.systemParts().map { it.text }, "same text as system(stable + env)")
        assertTrue(request.prompt.allCacheControls().isEmpty())
        assertTrue(request.tools.all { it.cacheControl == null })
    }

    @Test
    fun `foreign cache markers are removed before the client serializes them`() {
        val bedrockMarked = prompt("t") {
            system(stable, cache = BedrockCacheControl.OneHour)
            user("q", cache = BedrockCacheControl.Default)
        }
        val request = prepareCacheControls(bedrockMarked, emptyList(), LLMProvider.Anthropic, placeBreakpoints = false)
        assertTrue(request.prompt.allCacheControls().isEmpty())

        // Koog's Anthropic client would `require<AnthropicCacheControl>()` and throw on these.
        val http = CapturingHttpClientFactory { _, _ -> CapturingHttpClientFactory.ANTHROPIC_OK }
        val config = LLModelConfig(provider = "anthropic", model = "claude-sonnet-4-6")
        val (_, client) = createLLMClient(emptyList(), config, mapOf("anthropic" to "sk-ant-test"), http)
        runBlocking { client.execute(bedrockMarked, determineLLMModel(config), emptyList()) }
        val system = http.requests.single().json["system"]!!.jsonArray.single().jsonObject
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content, "our own breakpoint replaces it")
    }

    @Test
    fun `caller markers count against the four-breakpoint limit`() {
        val prompt = toolLoopPrompt().withMessages { messages ->
            messages.mapIndexed { i, m ->
                if (i == 1) Message.User(listOf(MessagePart.Text("question", AnthropicCacheControl.Default)), m.metaInfo as ai.koog.prompt.message.RequestMetaInfo) else m
            }
        }
        val markedTools = listOf(tools[0].withCacheControl(AnthropicCacheControl.Default), tools[1])
        val request = prepareCacheControls(prompt, markedTools, LLMProvider.Anthropic, placeBreakpoints = true)

        assertEquals(AnthropicCacheControl.Default, request.prompt.systemParts()[0].cacheControl)
        assertEquals(AnthropicCacheControl.Default, request.prompt.messages.last().parts.single().cacheControl)
        assertNull(request.tools.last().cacheControl, "no slot left for the last-tool breakpoint")
        val total = request.prompt.allCacheControls().size + request.tools.count { it.cacheControl != null }
        assertEquals(ANTHROPIC_MAX_CACHE_BREAKPOINTS, total)
    }

    @Test
    fun `a 5-minute breakpoint before a caller's 1-hour one is lengthened`() {
        val prompt = splitPrompt { user("a long shared document", cache = AnthropicCacheControl.OneHour) }
        val request = prepareCacheControls(prompt, tools, LLMProvider.Anthropic, placeBreakpoints = true)

        assertEquals(AnthropicCacheControl.OneHour, request.tools.last().cacheControl)
        assertEquals(AnthropicCacheControl.OneHour, request.prompt.systemParts()[0].cacheControl)
    }

    @Test
    fun `the caching decorators stay transparent to tool-result image routing`() {
        // ToolResultImageSupport routes by the provider client under DelegatingLLMClient decorators.
        val http = CapturingHttpClientFactory { _, _ -> CapturingHttpClientFactory.ANTHROPIC_OK }
        val config = LLModelConfig(provider = "anthropic", model = "claude-sonnet-4-6")
        val (_, client) = createLLMClient(emptyList(), config, mapOf("anthropic" to "sk-ant-test"), http)

        assertTrue(client.unwrapDecorators() is AnthropicLLMClient, client.unwrapDecorators()::class.toString())
    }

    @Test
    fun `the client wires caching from the anthropic_prompt_caching parameter`() {
        fun systemBlock(parameters: List<ConfigurationParameter>): JsonObject {
            val http = CapturingHttpClientFactory { _, _ -> CapturingHttpClientFactory.ANTHROPIC_OK }
            val config = LLModelConfig(provider = "anthropic", model = "claude-sonnet-4-6")
            val (_, client) = createLLMClient(parameters, config, mapOf("anthropic" to "sk-ant-test"), http)
            runBlocking { client.execute(splitPrompt(), determineLLMModel(config), tools) }
            return http.requests.single().json["system"]!!.jsonArray.first().jsonObject
        }
        assertTrue("cache_control" in systemBlock(emptyList()))
        assertFalse(
            "cache_control" in systemBlock(listOf(ConfigurationParameter(ANTHROPIC_PROMPT_CACHING_PARAMETER, JsonPrimitive(false))))
        )
    }
}
