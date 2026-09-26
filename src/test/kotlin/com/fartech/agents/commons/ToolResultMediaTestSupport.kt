package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.reflect.KClass

/** Shared fixtures for the tool-result media tests. */
internal object ToolResultMediaFixtures {

    fun png(width: Int, height: Int, noise: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        if (noise) {
            val random = Random(42)
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        } else {
            val g = image.createGraphics()
            g.color = Color.BLUE
            g.fillRect(0, 0, width, height)
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    fun readyImage(bytes: ByteArray = png(40, 20)): ToolResultImage =
        (ToolResultImages.prepare(bytes) as PreparedToolImage.Ready).image

    val screenshotDescriptor = ToolDescriptor(name = "browser_screenshot", description = "Take a screenshot")

    /** user → assistant tool call → user tool result carrying "saved" text plus [image]. */
    fun promptWithToolImage(image: ToolResultImage = readyImage(), id: String = "p"): Prompt =
        prompt(id) {
            system("You are a test agent.")
            user("Take a screenshot")
        }.withMessages { messages ->
            messages + Message.Assistant(
                parts = listOf(
                    MessagePart.Tool.Call(
                        id = "toolu_1",
                        tool = "browser_screenshot",
                        args = buildJsonObject { put("path", JsonPrimitive("shot.png")) },
                    ),
                ),
                metaInfo = ResponseMetaInfo.Empty,
            ) + Message.User(
                parts = listOf(
                    MessagePart.Tool.Result(
                        id = "toolu_1",
                        tool = "browser_screenshot",
                        parts = listOf(
                            MessagePart.Text("saved\n${ToolResultImages.attachedNote(1, image)}"),
                            image.toContentPart(),
                        ),
                    ),
                ),
                metaInfo = RequestMetaInfo.create(KoogClock.System),
            )
        }

    fun toolResults(prompt: Prompt): List<MessagePart.Tool.Result> =
        prompt.messages.filterIsInstance<Message.User>().flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
}

/** Records the prompt of every request-carrying call; answers with a fixed assistant message. */
internal class CapturingLLMClient(
    private val provider: LLMProvider = LLMProvider.Anthropic,
) : LLMClient() {
    val prompts = mutableListOf<Pair<String, Prompt>>()

    override val clientName: String = "capturing"

    override fun llmProvider(): LLMProvider = provider

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        prompts += "execute" to prompt
        return Message.Assistant(parts = listOf(MessagePart.Text("ok")), metaInfo = ResponseMetaInfo.Empty)
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
        prompts += "executeStreaming" to prompt
        return flowOf()
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): ai.koog.prompt.message.LLMChoice {
        prompts += "executeMultipleChoices" to prompt
        return listOf(Message.Assistant(parts = listOf(MessagePart.Text("ok")), metaInfo = ResponseMetaInfo.Empty))
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        prompts += "moderate" to prompt
        return ModerationResult(isHarmful = false, categories = emptyMap())
    }

    override fun close() = Unit
}

/** A [KoogHttpClient] that records request bodies and answers `post` through [respond]. */
internal class RecordingKoogHttpClient(
    private val respond: (path: String, responseType: KClass<*>) -> Any,
) : KoogHttpClient {
    override val clientName: String = "recording"
    val bodies = mutableListOf<Pair<String, String>>()

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
        bodies += path to encodeBody(requestBody, requestBodyType)
        return respond(path, responseType) as R
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
    ): Flow<O> {
        bodies += path to requestBody.toString()
        return emptyFlow()
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = emptyFlow()

    override fun close() = Unit

    /** Clients that pass a typed body (Google) are serialized the way a real HTTP client would. */
    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> encodeBody(body: T, type: KClass<T>): String =
        body as? String ?: kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(kotlinx.serialization.serializer(type.java) as kotlinx.serialization.KSerializer<T>, body)
}
