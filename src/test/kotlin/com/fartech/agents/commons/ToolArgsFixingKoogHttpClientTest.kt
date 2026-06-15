package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.message.Message
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class ToolArgsFixingKoogHttpClientTest {

    @Test
    fun `OpenRouterLLMClient request body has unwrapped arguments after the decorator`() {
        val capturing = CapturingKoogHttpClient(
            responseJson = """
                {"id":"x","object":"chat.completion","created":0,"model":"m","choices":[{
                  "index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"
                }],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """.trimIndent(),
        )
        val client = OpenRouterLLMClient(
            settings = OpenRouterClientSettings(),
            httpClient = ToolArgsFixingKoogHttpClient(capturing),
            clock = KoogClock.System,
        )

        val originalArgs = buildJsonObject {
            put("path", JsonPrimitive("/foo/bar.json"))
        }
        // Build a Prompt with one prior assistant tool call (the exact shape Koog 1.0.0
        // produces internally between agent iterations). When this gets re-encoded for
        // the next turn, `AbstractOpenAILLMClient.convertPromptToMessages` calls
        // `Json.encodeToString(it.args)` → double-encodes.
        val history = prompt("test") {
            user("read the manifest")
        }.withMessages { msgs ->
            msgs + Message.Assistant(
                parts = listOf(
                    MessagePart.Tool.Call(id = "call_469", tool = "readFile", args = originalArgs),
                ),
                metaInfo = ResponseMetaInfo.Empty,
            ) + Message.User(
                parts = listOf(
                    MessagePart.Tool.Result(id = "call_469", tool = "readFile", output = """{"ok":true}"""),
                ),
                metaInfo = RequestMetaInfo.create(KoogClock.System),
            )
        }

        runBlocking {
            client.execute(history, openRouterModel, listOf(readFileTool))
        }

        val body = capturing.lastBody ?: error("post() never invoked")
        val arguments = Json.parseToJsonElement(body)
            .jsonObject["messages"]!!.jsonArray
            .first { (it as JsonObject)["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("""{"path":"/foo/bar.json"}""", arguments)
    }

    private val openRouterModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "minimax/minimax-m2.7",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000,
    )

    private val readFileTool = ToolDescriptor(name = "readFile", description = "Read a file")

    private class CapturingKoogHttpClient(
        private val responseJson: String,
    ) : KoogHttpClient {
        override val clientName: String = "capturing"
        var lastBody: String? = null

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("not used")

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R {
            lastBody = requestBody as String
            return responseJson as R
        }

        override fun <T : Any, R : Any, O : Any> sse(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<O> = emptyFlow()

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = emptyFlow()

        override fun close() = Unit
    }
}
