package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationCategory
import ai.koog.prompt.dsl.ModerationCategoryResult
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the Tier-1 [CascadingFallbackPromptExecutor] behaviour against a
 * small set of fake [PromptExecutor]s. The test deliberately does NOT
 * spin up a real [ai.koog.prompt.executor.llms.MultiLLMPromptExecutor] —
 * it targets the cascade decision tree in isolation.
 */
class CascadingFallbackPromptExecutorTest {

    private val samplePrompt: Prompt = prompt("t") { user { +"hi" } }
    private val sampleModel: LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
        contextLength = 8192,
    )
    // Koog 1.0.0 — `execute()` narrowed from `List<Message.Response>` to
    // `Message.Assistant` (single). The sample assistant is built via the
    // convenience `(content: String, metaInfo, finishReason)` constructor.
    private val sampleResponse: Message.Assistant = Message.Assistant(
        content = "ok",
        metaInfo = ResponseMetaInfo.Empty,
        finishReason = "stop",
    )

    private class StubExecutor(
        // Empty default produces a stub assistant with no text — tests that
        // exercise the cascade path replace this lambda with one that throws.
        val onExecute: suspend () -> Message.Assistant = {
            Message.Assistant(content = "", metaInfo = ResponseMetaInfo.Empty)
        },
        val onStream: () -> Flow<StreamFrame> = { flow {} },
        val onModerate: suspend () -> ModerationResult = {
            ModerationResult(isHarmful = false, categories = emptyMap<ModerationCategory, ModerationCategoryResult>())
        },
        // `LLMChoice = List<Message.Assistant>` (typealias) so emptyList() works.
        val onMultiChoices: suspend () -> LLMChoice = { emptyList() },
        val onModels: suspend () -> List<LLModel> = { emptyList() },
    ) : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = onExecute()
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = onStream()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = onMultiChoices()
        override suspend fun moderate(prompt: Prompt, model: LLModel) = onModerate()
        override suspend fun models() = onModels()
        override fun close() = Unit
    }

    @Test
    fun `execute cascades to tier 2 when tier 1 throws LLMClientException`() = runBlocking {
        val tier1Calls = AtomicInteger(0)
        val tier2Calls = AtomicInteger(0)
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = {
                    tier1Calls.incrementAndGet()
                    throw LLMClientException("provider 5xx")
                }),
                StubExecutor(onExecute = {
                    tier2Calls.incrementAndGet()
                    sampleResponse
                }),
            )
        )
        val result = cascade.execute(samplePrompt, sampleModel, emptyList())
        assertEquals(1, tier1Calls.get())
        assertEquals(1, tier2Calls.get())
        assertEquals("ok", result.textContent())
    }

    @Test
    fun `execute cascades on IOException`() = runBlocking {
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = { throw IOException("socket reset") }),
                StubExecutor(onExecute = { sampleResponse }),
            )
        )
        val result = cascade.execute(samplePrompt, sampleModel, emptyList())
        assertEquals("ok", result.textContent())
    }

    @Test
    fun `execute cascades on generic RuntimeException`() = runBlocking {
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = { throw IllegalStateException("wrapper layer") }),
                StubExecutor(onExecute = { sampleResponse }),
            )
        )
        val result = cascade.execute(samplePrompt, sampleModel, emptyList())
        assertEquals("ok", result.textContent())
    }

    @Test
    fun `execute aborts cascade on IllegalArgumentException`() = runBlocking {
        val tier2Calls = AtomicInteger(0)
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = { throw IllegalArgumentException("bad input") }),
                StubExecutor(onExecute = {
                    tier2Calls.incrementAndGet()
                    sampleResponse
                }),
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { cascade.execute(samplePrompt, sampleModel, emptyList()) }
        }
        assertEquals(0, tier2Calls.get(), "tier 2 must not run after programming-error abort")
    }

    @Test
    fun `execute rethrows last error when all tiers exhausted`() = runBlocking {
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = { throw LLMClientException("tier 1 down") }),
                StubExecutor(onExecute = { throw IOException("tier 2 down") }),
                StubExecutor(onExecute = { throw LLMClientException("tier 3 down") }),
            )
        )
        val ex = assertThrows(LLMClientException::class.java) {
            runBlocking { cascade.execute(samplePrompt, sampleModel, emptyList()) }
        }
        assertTrue(ex.message?.contains("tier 3 down") == true, "should rethrow the LAST tier's error, got: ${ex.message}")
    }

    @Test
    fun `execute propagates CancellationException immediately without trying later tiers`() = runBlocking {
        val tier2Calls = AtomicInteger(0)
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = { throw CancellationException("user aborted") }),
                StubExecutor(onExecute = {
                    tier2Calls.incrementAndGet()
                    sampleResponse
                }),
            )
        )
        assertThrows(CancellationException::class.java) {
            runBlocking { cascade.execute(samplePrompt, sampleModel, emptyList()) }
        }
        assertEquals(0, tier2Calls.get(), "cooperative cancellation must not trigger tier advancement")
    }

    @Test
    fun `executeStreaming falls through when tier 1 errors before any frame`() = runBlocking {
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onStream = {
                    flow { throw LLMClientException("no stream") }
                }),
                StubExecutor(onStream = {
                    flow { emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)) }
                }),
            )
        )
        val frames = cascade.executeStreaming(samplePrompt, sampleModel, emptyList()).toList()
        assertEquals(1, frames.size)
        assertTrue(frames[0] is StreamFrame.End)
    }

    @Test
    fun `executeStreaming does NOT cascade once a frame has been emitted`() {
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onStream = {
                    flow {
                        emit(StreamFrame.TextDelta("hello"))
                        throw LLMClientException("mid-stream")
                    }
                }),
                StubExecutor(onStream = {
                    flow { emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)) }
                }),
            )
        )
        assertThrows(LLMClientException::class.java) {
            runBlocking {
                cascade.executeStreaming(samplePrompt, sampleModel, emptyList()).toList()
            }
        }
    }

    @Test
    fun `constructor rejects empty tiers list`() {
        assertThrows(IllegalArgumentException::class.java) {
            CascadingFallbackPromptExecutor(emptyList())
        }
    }

    @Test
    fun `execute does NOT cascade on JVM Error - OutOfMemoryError propagates`() = runBlocking {
        val tier2Calls = AtomicInteger(0)
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = { throw OutOfMemoryError("heap exhausted") }),
                StubExecutor(onExecute = {
                    tier2Calls.incrementAndGet()
                    sampleResponse
                }),
            )
        )
        assertThrows(OutOfMemoryError::class.java) {
            runBlocking { cascade.execute(samplePrompt, sampleModel, emptyList()) }
        }
        assertEquals(0, tier2Calls.get(), "JVM Error must not trigger tier advancement — operators need to see the crash")
    }

    @Test
    fun `execute does NOT cascade on AssertionError`() = runBlocking {
        val tier2Calls = AtomicInteger(0)
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onExecute = { throw AssertionError("invariant broken") }),
                StubExecutor(onExecute = {
                    tier2Calls.incrementAndGet()
                    sampleResponse
                }),
            )
        )
        assertThrows(AssertionError::class.java) {
            runBlocking { cascade.execute(samplePrompt, sampleModel, emptyList()) }
        }
        assertEquals(0, tier2Calls.get())
    }

    @Test
    fun `executeStreaming does NOT cascade on IllegalArgumentException`() {
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onStream = {
                    kotlinx.coroutines.flow.flow { throw IllegalArgumentException("bad prompt") }
                }),
                StubExecutor(onStream = {
                    kotlinx.coroutines.flow.flow {
                        emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
                    }
                }),
            )
        )
        // Programming errors should NOT trigger tier advancement in streaming either.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { cascade.executeStreaming(samplePrompt, sampleModel, emptyList()).toList() }
        }
    }

    @Test
    fun `models unions all tier models and tolerates per-tier errors`() = runBlocking {
        val cascade = CascadingFallbackPromptExecutor(
            listOf(
                StubExecutor(onModels = { listOf(sampleModel) }),
                StubExecutor(onModels = { throw LLMClientException("models endpoint down") }),
                StubExecutor(onModels = {
                    listOf(
                        sampleModel.copy(id = "other-model"),
                        sampleModel,                  // duplicate — distinct() should dedup
                    )
                }),
            )
        )
        val all = cascade.models()
        assertEquals(2, all.size, "expected union + distinct, got $all")
    }

}
