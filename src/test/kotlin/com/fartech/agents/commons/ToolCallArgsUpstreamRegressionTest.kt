package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Upstream-regression pin for Koog fix #2095 (Koog 1.1.1): OpenAI-style clients send a prior
 * tool call's `arguments` as the JSON-object string itself. Koog 1.0.0 double-encoded it
 * (`"{\"path\":...}"`), which MiniMax and other strict OpenRouter backends rejected; the engine
 * used to carry a ToolArgsFixingKoogHttpClient shim for that. If this fails after a Koog bump,
 * the double encoding is back.
 */
class ToolCallArgsUpstreamRegressionTest {

    private val readFileTool = ToolDescriptor(name = "readFile", description = "Read a file")

    /** One prior assistant tool call + its result — the shape Koog replays between agent iterations. */
    private val history: Prompt = prompt("test") {
        user("read the manifest")
    }.withMessages { msgs ->
        msgs + Message.Assistant(
            parts = listOf(
                MessagePart.Tool.Call(
                    id = "call_469",
                    tool = "readFile",
                    args = buildJsonObject { put("path", JsonPrimitive("/foo/bar.json")) },
                ),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        ) + Message.User(
            parts = listOf(
                MessagePart.Tool.Result(id = "call_469", tool = "readFile", output = """{"ok":true}"""),
            ),
            metaInfo = RequestMetaInfo.create(KoogClock.System),
        )
    }

    private fun sentArguments(http: CapturingHttpClientFactory): String =
        http.requests.single().json["messages"]!!.jsonArray
            .first { (it as JsonObject)["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content

    private fun sendWith(client: LLMClient, model: LLModel) = runBlocking {
        client.execute(history, model, listOf(readFileTool))
    }

    @Test
    fun `OpenRouterLLMClient sends tool call arguments as a JSON object string`() {
        val http = CapturingHttpClientFactory()
        val client = OpenRouterLLMClient(
            apiKey = "sk-or-test",
            settings = OpenRouterClientSettings(chatCompletionsPath = "/api/v1/chat/completions"),
            httpClientFactory = http,
        )
        sendWith(
            client,
            LLModel(
                provider = LLMProvider.OpenRouter,
                id = "minimax/minimax-m2.7",
                capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
                contextLength = 128_000,
            ),
        )
        assertEquals("""{"path":"/foo/bar.json"}""", sentArguments(http))
    }

    @Test
    fun `OpenAILLMClient chat completions sends tool call arguments as a JSON object string`() {
        val http = CapturingHttpClientFactory()
        val client = OpenAILLMClient(
            apiKey = "sk-openai-test",
            settings = OpenAIClientSettings(),
            httpClientFactory = http,
        )
        sendWith(
            client,
            LLModel(
                provider = LLMProvider.OpenAI,
                id = "gpt-4o",
                capabilities = listOf(
                    LLMCapability.Completion,
                    LLMCapability.Tools,
                    LLMCapability.OpenAIEndpoint.Completions,
                ),
                contextLength = 128_000,
            ),
        )
        assertEquals("""{"path":"/foo/bar.json"}""", sentArguments(http))
    }
}
