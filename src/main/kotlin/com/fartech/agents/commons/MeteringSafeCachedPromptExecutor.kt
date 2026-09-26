package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.cached.CachedPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** `ResponseMetaInfo.metadata` flag set on responses served from the prompt cache. */
const val PROMPT_CACHE_HIT_METADATA_KEY = "braidrun_prompt_cache_hit"

/**
 * Koog [CachedPromptExecutor] whose cache hits carry no token usage.
 *
 * On a hit Koog re-stamps the cached message with the `metaInfo` of the last
 * assistant message already in the request prompt (`PromptCache.get(prompt,
 * tools, clock)`), i.e. the PREVIOUS round's token counts. Every metering
 * consumer — WorkflowExecutor's `llm_call_completed` / `token_usage` events and
 * the web assistant's quota counters (buffered `onLLMCallCompleted` and the
 * streamed `StreamFrame.End`) — would then bill that round a second time for a
 * call that never reached a provider.
 *
 * Koog's executor still owns the cache (keying, lookup, storing, streaming
 * replay). This wrapper only tells a hit from a miss — a miss is the one path
 * that reaches `nested`, which records it on the per-call [CacheMissProbe] —
 * and gives a hit fresh metadata without token counts, flagged with
 * [PROMPT_CACHE_HIT_METADATA_KEY]. Misses pass through untouched.
 *
 * Entries are keyed by [responseCacheDigest] — model, full tool descriptors and prompt — through
 * [ScopedResponseCache], not by Koog's 32-bit prompt hash that ignores the model.
 *
 * Streaming requests bypass the cache: Koog's cached `executeStreaming` answers with a
 * non-streaming call replayed as frames, which would turn live typing into one burst at the end.
 */
internal class MeteringSafeCachedPromptExecutor(
    cache: PromptCache,
    private val nested: PromptExecutor,
) : PromptExecutor() {

    private val cached = CachedPromptExecutor(cache = ScopedResponseCache(cache), nested = MissRecordingPromptExecutor(nested))

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        val probe = CacheMissProbe()
        val key = ResponseCacheKey(responseCacheDigest(prompt, model, tools))
        val response = withContext(probe + key) { cached.execute(prompt, model, tools) }
        return if (probe.missed) response else response.copy(metaInfo = response.metaInfo.asUnmeteredCacheHit())
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = nested.executeStreaming(prompt, model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        cached.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = cached.models()

    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator =
        cached.getStandardJsonSchemaGenerator(model)

    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator =
        cached.getBasicJsonSchemaGenerator(model)

    override fun close() = cached.close()
}

/** Per-call marker the nested executor flips when the cache had to call the provider. */
private class CacheMissProbe : AbstractCoroutineContextElement(CacheMissProbe) {
    companion object Key : CoroutineContext.Key<CacheMissProbe>

    @Volatile
    var missed: Boolean = false
}

/** Transparent delegate that records every provider call on the caller's [CacheMissProbe]. */
private class MissRecordingPromptExecutor(private val nested: PromptExecutor) : PromptExecutor() {

    override suspend fun resolveModel(
        model: LLModel,
        promptExecutorOperation: PromptExecutorOperation
    ): ResolvedModel = nested.resolveModel(model, promptExecutorOperation)

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        recordMiss()
        return nested.execute(prompt, model, tools)
    }

    override suspend fun execute(
        prompt: Prompt,
        model: ResolvedModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        recordMiss()
        return nested.execute(prompt, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = nested.executeStreaming(prompt, model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = nested.executeStreaming(prompt, resolvedModel, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice {
        recordMiss()
        return nested.executeMultipleChoices(prompt, model, tools)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        nested.moderate(prompt, model)

    override suspend fun moderate(prompt: Prompt, model: ResolvedModel): ModerationResult =
        nested.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = nested.models()

    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator =
        nested.getStandardJsonSchemaGenerator(model)

    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator =
        nested.getBasicJsonSchemaGenerator(model)

    override fun close() = nested.close()

    private suspend fun recordMiss() {
        currentCoroutineContext()[CacheMissProbe]?.missed = true
    }
}

/**
 * Everything but the timestamp in a hit's metaInfo describes the previous round
 * (token counts, model id, provider cache-usage metadata), so keep only the
 * timestamp and the hit flag.
 */
private fun ResponseMetaInfo.asUnmeteredCacheHit(): ResponseMetaInfo = ResponseMetaInfo(
    timestamp = timestamp,
    metadata = JsonObject(mapOf(PROMPT_CACHE_HIT_METADATA_KEY to JsonPrimitive(true))),
)
