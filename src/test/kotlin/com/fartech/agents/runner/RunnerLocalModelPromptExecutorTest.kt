package com.fartech.agents.runner

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.*
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunnerLocalModelPromptExecutorTest {
    private val binding = RunnerLocalModelBinding("runner", "owner", "execution", "attempt", "endpoint",
        "http://127.0.0.1:11434", "0.34.1", "fixture:1b", "a".repeat(64), 4096, 512)
    private val ordinary = prompt("local", LLMParams(temperature = 0.0, maxTokens = 128)) { system("Only text"); user("Question") }
    private val result = RunnerLocalModelResult("answer", binding.model, binding.digest, binding.serviceVersion,
        "request", 10, 100, 12, 2)

    @Test
    fun `public constructor rejects a transport belonging to another frozen binding`() {
        val authority = object : RunnerLocalModelAuthority {
            override suspend fun reserve(binding: RunnerLocalModelBinding, requestId: String, request: RunnerLocalModelRequest): RunnerLocalModelGrant =
                error("Constructor must not contact authority")
        }
        val gate = RunnerEnvironmentGate(RunnerProtocolTest.binding(), setOf("engine-http"))
        RunnerLocalModelClient(binding, gate, RunnerBusinessFence(), authority).use { client ->
            assertThrows(IllegalArgumentException::class.java) {
                RunnerLocalModelPromptExecutor(binding.copy(attemptId = "other"), "summarize", emptyList(), client, {}, {})
            }
        }
    }

    private inner class Fixture(
        val schema: LLMParams.Schema? = null,
        val validate: (String) -> Unit = {},
        val answer: suspend (RunnerLocalModelRequest) -> RunnerLocalModelResult = { result },
    ) {
        val requests = mutableListOf<RunnerLocalModelRequest>()
        var live = true
        var closes = 0
        val executor = RunnerLocalModelPromptExecutor(binding, "summarize", listOf("child"),
            complete = { request, validator -> requests += request; answer(request).also { validator(it.content) } },
            closeTransport = { closes++ }, checkLive = { check(live) { "gate closed" } },
            validateOutput = validate, approvedSchema = schema)
    }

    @Test
    fun `text request preserves frozen scope and meters actual result once`(): Unit = runBlocking {
        val fixture = Fixture()
        val response = fixture.executor.execute(ordinary, fixture.executor.localModel, emptyList())
        assertEquals("answer", response.textContent())
        assertEquals("stop", response.finishReason)
        assertEquals(12, response.metaInfo.inputTokensCount)
        assertEquals(2, response.metaInfo.outputTokensCount)
        assertEquals(14, response.metaInfo.totalTokensCount)
        assertEquals(binding.model, response.metaInfo.modelId)
        assertEquals(JsonPrimitive(binding.digest), response.metaInfo.metadata?.get("model_digest"))
        assertEquals(RunnerLocalModelRequest("summarize", listOf(RunnerLocalMessage("system", "Only text"), RunnerLocalMessage("user", "Question")), 128, false, listOf("child")), fixture.requests.single())
    }

    @Test
    fun `stream publishes only completed validated output and one metered end`(): Unit = runBlocking {
        val fixture = Fixture()
        val frames = fixture.executor.executeStreaming(ordinary, fixture.executor.localModel, emptyList()).toList()
        assertEquals(2, frames.size)
        assertEquals("answer", (frames[0] as StreamFrame.TextDelta).text)
        assertEquals(14, (frames[1] as StreamFrame.End).metaInfo?.totalTokensCount)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `unknown usage remains unknown rather than becoming zero`(): Unit = runBlocking {
        val fixture = Fixture(answer = { result.copy(promptTokens = null, outputTokens = null) })
        val response = fixture.executor.execute(ordinary, fixture.executor.localModel, emptyList())
        assertNull(response.metaInfo.inputTokensCount)
        assertNull(response.metaInfo.outputTokensCount)
        assertNull(response.metaInfo.totalTokensCount)
    }

    @Test
    fun `cloud model or changed context cannot select fallback`(): Unit = runBlocking {
        val fixture = Fixture()
        for (model in listOf(fixture.executor.localModel.copy(provider = LLMProvider.OpenAI), fixture.executor.localModel.copy(contextLength = 8192))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.executor.execute(ordinary, model, emptyList()) } }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `tools and unsupported parameters refuse before inference`(): Unit = runBlocking {
        val fixture = Fixture()
        val tool = ToolDescriptor(name = "shell", description = "not allowed", requiredParameters = emptyList(), optionalParameters = emptyList())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.executor.execute(ordinary, fixture.executor.localModel, listOf(tool)) } }
        val forbidden = listOf(
            LLMParams(temperature = 0.7), LLMParams(maxTokens = 513), LLMParams(numberOfChoices = 2),
            LLMParams(toolChoice = LLMParams.ToolChoice.Auto), LLMParams(user = "private-user"),
            LLMParams(speculation = "speculative"), LLMParams(additionalProperties = mapOf("base_url" to JsonPrimitive("https://cloud.invalid"))),
        )
        for (parameters in forbidden) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.executor.execute(ordinary.withParams(parameters), fixture.executor.localModel, emptyList()) } }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `media and reasoning history refuse without flattening`(): Unit = runBlocking {
        val fixture = Fixture()
        val attachment = MessagePart.Attachment(AttachmentSource.Image(AttachmentContent.URL("https://example.com/a.png"), "png", "image/png"))
        val invalid = listOf(
            Message.User(parts = listOf(MessagePart.Text("caption"), attachment), metaInfo = RequestMetaInfo.Empty),
            Message.Assistant(parts = listOf(MessagePart.Reasoning("hidden reasoning")), metaInfo = ResponseMetaInfo.Empty),
        )
        for (message in invalid) {
            val request = Prompt(listOf(message), "unsupported")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.executor.execute(request, fixture.executor.localModel, emptyList()) } }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `only the exact frozen JSON schema is admitted and validator must succeed`(): Unit = runBlocking {
        val schema = LLMParams.Schema.JSON.Basic("approved", buildJsonObject { put("type", "object") })
        val fixture = Fixture(schema, validate = { require(Json.parseToJsonElement(it).jsonObject["ok"] == JsonPrimitive(true)) }, answer = { result.copy(content = "{\"ok\":true}") })
        assertTrue(schema.capability in fixture.executor.localModel.capabilities.orEmpty())
        assertFalse(LLMParams.Schema.JSON.Standard("other", buildJsonObject { put("type", "object") }).capability in Fixture().executor.localModel.capabilities.orEmpty())
        val response = fixture.executor.execute(ordinary.withParams(LLMParams(schema = schema)), fixture.executor.localModel, emptyList())
        assertEquals("{\"ok\":true}", response.textContent())
        assertTrue(fixture.requests.single().jsonObject)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.executor.execute(ordinary, fixture.executor.localModel, emptyList()) } }
        val other = LLMParams.Schema.JSON.Standard("different", buildJsonObject { put("type", "object") })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.executor.execute(ordinary.withParams(LLMParams(schema = other)), fixture.executor.localModel, emptyList()) } }
    }

    @Test
    fun `invalid output or authority failure produces no stream frames`(): Unit = runBlocking {
        val frames = mutableListOf<StreamFrame>()
        val fixture = Fixture(validate = { error("semantic validation failed") })
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.executor.executeStreaming(ordinary, fixture.executor.localModel, emptyList()).collect { frames += it } } }
        assertTrue(frames.isEmpty())
        val uncertain = Fixture(answer = { error("stop unconfirmed") })
        assertThrows(IllegalStateException::class.java) { runBlocking { uncertain.executor.executeStreaming(ordinary, uncertain.executor.localModel, emptyList()).collect { frames += it } } }
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `gate loss after transport cannot publish late success`(): Unit = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture(answer = { fixture.live = false; result })
        val frames = mutableListOf<StreamFrame>()
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.executor.executeStreaming(ordinary, fixture.executor.localModel, emptyList()).collect { frames += it } } }
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `cancelled inference cannot publish a final frame`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val fixture = Fixture(answer = { entered.complete(Unit); awaitCancellation() })
        val frames = mutableListOf<StreamFrame>()
        val job = launch { fixture.executor.executeStreaming(ordinary, fixture.executor.localModel, emptyList()).collect { frames += it } }
        entered.await(); job.cancelAndJoin()
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `resolved model path stays local and unsupported operations fail closed`(): Unit = runBlocking {
        val fixture = Fixture()
        val model = fixture.executor.localModel
        assertEquals(model, fixture.executor.resolveModel(model, PromptExecutorOperation.Execute).effectiveModel)
        assertEquals("answer", fixture.executor.execute(ordinary, ResolvedModel(model), emptyList()).textContent())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.executor.resolveModel(model, PromptExecutorOperation.MultipleChoices) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.executor.executeMultipleChoices(ordinary, model, emptyList()) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.executor.moderate(ordinary, model) } }
        assertThrows(IllegalStateException::class.java) { fixture.executor.getBasicJsonSchemaGenerator(model) }
        assertEquals(listOf(model), fixture.executor.models())
    }

    @Test
    fun `close is idempotent and rejects further execution`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.executor.close(); fixture.executor.close()
        assertEquals(1, fixture.closes)
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.executor.execute(ordinary, fixture.executor.localModel, emptyList()) } }
        assertTrue(fixture.requests.isEmpty())
    }
    @Test
    fun `concurrent close releases the transport exactly once`(): Unit = runBlocking {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val executor = RunnerLocalModelPromptExecutor(binding, "summarize", emptyList(),
            complete = { _, _ -> result }, closeTransport = { closes.incrementAndGet() }, checkLive = {}, validateOutput = {})
        val start = CompletableDeferred<Unit>()
        val jobs = List(32) { async(Dispatchers.Default) { start.await(); executor.close() } }
        start.complete(Unit); jobs.awaitAll()
        assertEquals(1, closes.get())
    }

    @Test
    fun `public executor releases host tracking when its transport closes`() {
        val authority = object : RunnerLocalModelAuthority {
            override suspend fun reserve(binding: RunnerLocalModelBinding, requestId: String, request: RunnerLocalModelRequest): RunnerLocalModelGrant =
                error("Closing must not contact authority")
        }
        val tracked = java.util.concurrent.ConcurrentHashMap.newKeySet<RunnerLocalModelClient>()
        val gate = RunnerEnvironmentGate(RunnerProtocolTest.binding(), setOf("engine-http"))
        var releases = 0
        repeat(32) {
            val client = RunnerLocalModelClient(binding, gate, RunnerBusinessFence(), authority)
            tracked.add(client)
            val executor = RunnerLocalModelPromptExecutor(binding, "summarize", emptyList(), client, {}, {},
                onClose = { tracked.remove(client); releases++ })
            executor.close(); executor.close()
            assertTrue(tracked.isEmpty(), "Completed clients must not accumulate until host finalization")
        }
        assertEquals(32, releases)
    }

}
