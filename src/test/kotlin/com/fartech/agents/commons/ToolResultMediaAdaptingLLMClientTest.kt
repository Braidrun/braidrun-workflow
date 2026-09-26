package com.fartech.agents.commons

import ai.koog.prompt.executor.clients.InternalLLMClientApi
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicContent
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicResponse
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

@OptIn(InternalLLMClientApi::class)
class ToolResultMediaAdaptingLLMClientTest {

    private val image = ToolResultMediaFixtures.readyImage()
    private val imageBase64 = Base64.getEncoder().encodeToString(image.bytes)
    private val prompt = ToolResultMediaFixtures.promptWithToolImage(image)

    private val anthropicVision = AnthropicModels.Sonnet_4_5
    private val anthropicTextOnly = anthropicVision.copy(
        capabilities = anthropicVision.capabilities!!.filterNot { it is LLMCapability.Vision },
    )

    private val chatCompletionJson = """
        {"id":"x","object":"chat.completion","created":0,"model":"gpt-4o","choices":[{
          "index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"
        }],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """.trimIndent()

    // --- Google (Gemini): functionResponse.parts[].inlineData --------------------------------

    /** Records the Gemini request body; the fake answer is not decodable, so the call itself fails. */
    private fun googleRequestBody(model: LLModel): kotlinx.serialization.json.JsonObject {
        val http = RecordingKoogHttpClient { _, _ -> error("no response in this test") }
        val client = ToolResultMediaAdaptingLLMClient(
            ai.koog.prompt.executor.clients.google.GoogleLLMClient(
                settings = ai.koog.prompt.executor.clients.google.GoogleClientSettings(),
                httpClient = http,
            )
        )
        runCatching { runBlocking { client.execute(prompt, model, listOf(ToolResultMediaFixtures.screenshotDescriptor)) } }
        return Json.parseToJsonElement(http.bodies.single().second).jsonObject
    }

    private fun functionResponses(body: kotlinx.serialization.json.JsonObject): List<kotlinx.serialization.json.JsonObject> =
        body["contents"]!!.jsonArray
            .flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
            .mapNotNull { it.jsonObject["functionResponse"]?.jsonObject }

    @Test
    fun `Gemini 3 vision model receives inlineData inside functionResponse parts`() {
        val model = ai.koog.prompt.executor.clients.google.GoogleModels.Gemini3_Flash_Preview
        assertTrue(ToolResultImageSupport.isGemini3OrLater(model.id), model.id)

        val response = functionResponses(googleRequestBody(model)).single()

        val inline = response["parts"]!!.jsonArray.single().jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/png", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals(imageBase64, inline["data"]!!.jsonPrimitive.content)
        val result = response["response"]!!.jsonObject["result"]!!.jsonPrimitive.content
        assertTrue(result.startsWith("saved"), result)
    }

    @Test
    fun `Gemini 2_5 gets a placeholder and no inlineData`() {
        val body = googleRequestBody(ai.koog.prompt.executor.clients.google.GoogleModels.Gemini2_5Flash)

        val text = body.toString()
        assertFalse(text.contains("inlineData"), text)
        assertFalse(text.contains(imageBase64))
        val result = functionResponses(body).single()["response"]!!.jsonObject["result"]!!.jsonPrimitive.content
        assertTrue(result.contains("[image omitted (image/png, "), result)
    }

    // --- real Koog clients: what actually goes on the wire -------------------------------------

    @Test
    fun `Anthropic vision model receives an image block inside tool_result`() {
        val http = RecordingKoogHttpClient { _, _ ->
            AnthropicResponse(
                id = "msg_1",
                type = "message",
                role = "assistant",
                content = listOf(AnthropicContent.Text("ok")),
                model = anthropicVision.id,
                stopReason = "end_turn",
            )
        }
        val client = ToolResultMediaAdaptingLLMClient(
            AnthropicLLMClient(settings = AnthropicClientSettings(), httpClient = http, clock = KoogClock.System)
        )

        runBlocking { client.execute(prompt, anthropicVision, listOf(ToolResultMediaFixtures.screenshotDescriptor)) }

        val body = http.bodies.single().second
        val toolResult = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .flatMap { (it.jsonObject["content"] as? kotlinx.serialization.json.JsonArray).orEmpty() }
            .map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "tool_result" }
        val blocks = toolResult["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image"), blocks.map { it["type"]!!.jsonPrimitive.content })
        val source = blocks[1]["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(imageBase64, source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `route looks through the per-model params decorator createLLMClient applies`() {
        val http = RecordingKoogHttpClient { _, _ ->
            AnthropicResponse(
                id = "msg_1",
                type = "message",
                role = "assistant",
                content = listOf(AnthropicContent.Text("ok")),
                model = anthropicVision.id,
                stopReason = "end_turn",
            )
        }
        // createLLMClient wraps every provider client in ModelParamsSanitizingLLMClient, and
        // createPromptExecutor puts the media adapter on top: the route must still be Anthropic's.
        val sanitized = ModelParamsSanitizingLLMClient(
            AnthropicLLMClient(settings = AnthropicClientSettings(), httpClient = http, clock = KoogClock.System)
        )
        assertEquals(ToolResultImageRoute.ANTHROPIC, ToolResultImageSupport.route(sanitized, anthropicVision))

        runBlocking {
            ToolResultMediaAdaptingLLMClient(sanitized)
                .execute(prompt, anthropicVision, listOf(ToolResultMediaFixtures.screenshotDescriptor))
        }

        val body = http.bodies.single().second
        assertTrue(body.contains(imageBase64), "the image must reach Anthropic through the decorator chain")
    }

    @Test
    fun `Anthropic model without vision gets a placeholder and no image bytes`() {
        val http = RecordingKoogHttpClient { _, _ ->
            AnthropicResponse("msg_1", "message", "assistant", listOf(AnthropicContent.Text("ok")), anthropicTextOnly.id)
        }
        val client = ToolResultMediaAdaptingLLMClient(
            AnthropicLLMClient(
                settings = AnthropicClientSettings(modelVersionsMap = mapOf(anthropicTextOnly to anthropicTextOnly.id)),
                httpClient = http,
                clock = KoogClock.System,
            )
        )

        runBlocking { client.execute(prompt, anthropicTextOnly, listOf(ToolResultMediaFixtures.screenshotDescriptor)) }

        val body = http.bodies.single().second
        assertFalse(body.contains(imageBase64), "image bytes must not be sent")
        assertFalse(body.contains("\"image\""), "no image block expected")
        assertTrue(body.contains("image omitted (image/png"), body)
        assertTrue(body.contains(ToolResultImages.REASON_MODEL_CANNOT_VIEW), body)
    }

    @Test
    fun `OpenAI Chat Completions model receives the placeholder text and no exception`() {
        val http = RecordingKoogHttpClient { _, type ->
            check(type == String::class) { "unexpected response type $type" }
            chatCompletionJson
        }
        val client = ToolResultMediaAdaptingLLMClient(
            OpenAILLMClient(settings = OpenAIClientSettings(), httpClient = http)
        )

        val answer = runBlocking {
            client.execute(prompt, OpenAIModels.Chat.GPT4o, listOf(ToolResultMediaFixtures.screenshotDescriptor))
        }

        assertEquals("ok", answer.textContent())
        val (path, body) = http.bodies.single()
        assertTrue(path.endsWith("chat/completions"), path)
        assertFalse(body.contains(imageBase64))
        val toolMessage = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "tool" }
        val content = toolMessage["content"]!!.jsonPrimitive.content
        assertTrue(content.startsWith("saved\n"), content)
        assertTrue(content.contains("[image omitted (image/png, "), content)
        assertTrue(content.contains(ToolResultImages.REASON_MODEL_CANNOT_VIEW), content)
    }

    @Test
    fun `OpenAI Responses-only vision model receives an input_image`() {
        val responsesModel = LLModel(
            provider = LLMProvider.OpenAI,
            id = "gpt-responses-only",
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Tools,
                LLMCapability.Vision.Image,
                LLMCapability.OpenAIEndpoint.Responses,
            ),
            contextLength = 128_000,
            maxOutputTokens = 4096,
        )
        val http = RecordingKoogHttpClient { _, _ -> throw IllegalStateException("stop after capture") }
        val client = ToolResultMediaAdaptingLLMClient(
            OpenAILLMClient(settings = OpenAIClientSettings(), httpClient = http)
        )

        runCatching { runBlocking { client.execute(prompt, responsesModel, emptyList()) } }

        val (path, body) = http.bodies.single()
        assertTrue(path.endsWith("responses"), path)
        val output = Json.parseToJsonElement(body).jsonObject["input"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "function_call_output" }["output"]!!
            .jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_text", "input_image"), output.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("data:image/png;base64,$imageBase64", output[1]["image_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Azure OpenAI base URL is treated as Chat Completions even for a Responses-only model`() {
        val azure = OpenAILLMClient(
            settings = OpenAIClientSettings(baseUrl = "https://acme.openai.azure.com/openai"),
            httpClient = RecordingKoogHttpClient { _, _ -> error("unused") },
        )
        val plain = OpenAILLMClient(settings = OpenAIClientSettings(), httpClient = RecordingKoogHttpClient { _, _ -> error("unused") })
        val model = LLModel(
            LLMProvider.OpenAI,
            "gpt-responses-only",
            listOf(LLMCapability.Completion, LLMCapability.Vision.Image, LLMCapability.OpenAIEndpoint.Responses),
        )
        assertEquals("https://api.openai.com", ToolResultImageSupport.openAIBaseUrl(plain)?.removeSuffix("/"))
        assertEquals(ToolResultImageRoute.OPENAI_RESPONSES, ToolResultImageSupport.route(plain, model))
        assertEquals(ToolResultImageRoute.TEXT_ONLY, ToolResultImageSupport.route(azure, model))
    }

    // --- decorator coverage of every request-carrying member ------------------------------------

    @Test
    fun `every request-carrying member adapts the prompt for a text-only route`() = runBlocking {
        val capturing = CapturingLLMClient(LLMProvider.OpenAI)
        val client = ToolResultMediaAdaptingLLMClient(capturing)
        val model = OpenAIModels.Chat.GPT4o

        client.execute(prompt, model, emptyList())
        client.executeStreaming(prompt, model, emptyList()).toList()
        client.executeMultipleChoices(prompt, model, emptyList())
        client.moderate(prompt, model)

        assertEquals(listOf("execute", "executeStreaming", "executeMultipleChoices", "moderate"), capturing.prompts.map { it.first })
        capturing.prompts.forEach { (member, sent) ->
            val result = ToolResultMediaFixtures.toolResults(sent).single()
            assertTrue(result.parts.none { it is MessagePart.Attachment }, "$member still carries an image")
            val text = (result.parts.single() as MessagePart.Text).text
            assertTrue(text.contains("[image omitted (image/png"), "$member: $text")
        }
    }

    @Test
    fun `prompt is passed through untouched when the route can show the image`() = runBlocking {
        // An unknown LLMClient subclass is TEXT_ONLY, so use the real Anthropic client type.
        val http = RecordingKoogHttpClient { _, _ -> AnthropicResponse("m", "message", "assistant", listOf(AnthropicContent.Text("ok")), "x") }
        val anthropic = AnthropicLLMClient(settings = AnthropicClientSettings(), httpClient = http, clock = KoogClock.System)
        val route = ToolResultImageSupport.route(anthropic, anthropicVision)
        val adapted = prompt.withToolResultMediaAdapted { ToolResultImageSupport.canSend(route, anthropicVision, it) }
        assertEquals(prompt, adapted)
        assertEquals(1, ToolResultMediaFixtures.toolResults(adapted).single().parts.count { it is MessagePart.Attachment })
    }

    @Test
    fun `older images beyond the per-request cap become placeholders in steps`() {
        assertEquals(0, prunedToolResultImageCount(sendable = 12, maxImages = 12, step = 6))
        assertEquals(6, prunedToolResultImageCount(sendable = 13, maxImages = 12, step = 6))
        assertEquals(6, prunedToolResultImageCount(sendable = 18, maxImages = 12, step = 6))
        assertEquals(12, prunedToolResultImageCount(sendable = 19, maxImages = 12, step = 6))

        var combined = ToolResultMediaFixtures.promptWithToolImage(image, "a")
        repeat(2) { i ->
            val next = ToolResultMediaFixtures.promptWithToolImage(image, "b$i")
            combined = combined.withMessages { it + next.messages.drop(2) }
        }
        val adapted = combined.withToolResultMediaAdapted(maxImages = 2, step = 1) { true }
        val kept = ToolResultMediaFixtures.toolResults(adapted).map { r -> r.parts.count { it is MessagePart.Attachment } }
        assertEquals(listOf(0, 1, 1), kept)
        val firstText = (ToolResultMediaFixtures.toolResults(adapted).first().parts.single() as MessagePart.Text).text
        assertTrue(firstText.contains(REASON_OLDER_IMAGE_PRUNED), firstText)
    }

    @Test
    fun `non-image attachments never go out inside tool results`() {
        val withFile = prompt.withMessages { messages ->
            messages.map { m ->
                if (m !is ai.koog.prompt.message.Message.User) m else m.copy(
                    parts = m.parts.map { p ->
                        if (p !is MessagePart.Tool.Result) p else p.copy(
                            parts = p.parts + MessagePart.Attachment(
                                ai.koog.prompt.message.AttachmentSource.File(
                                    content = ai.koog.prompt.message.AttachmentContent.Binary.Bytes(ByteArray(10)),
                                    format = "pdf",
                                    mimeType = "application/pdf",
                                )
                            )
                        )
                    }
                )
            }
        }
        val adapted = withFile.withToolResultMediaAdapted { true }
        val parts = ToolResultMediaFixtures.toolResults(adapted).single().parts
        assertEquals(1, parts.count { it is MessagePart.Attachment })
        assertTrue((parts.first() as MessagePart.Text).text.contains("[file omitted (application/pdf, 10 B)"))
    }

    @Suppress("unused")
    private fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.content
}
