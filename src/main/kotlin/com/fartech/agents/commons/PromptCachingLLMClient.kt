package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Workflow / preset parameter: place Anthropic prompt-cache breakpoints automatically on
 * direct Anthropic requests. Default `true`; `false` sends prompts without added breakpoints
 * (caller-supplied [PromptCacheHints] markers are still honoured).
 */
const val ANTHROPIC_PROMPT_CACHING_PARAMETER = "anthropic_prompt_caching"

/** Anthropic's per-request limit on `cache_control` breakpoints, the automatic one included. */
internal const val ANTHROPIC_MAX_CACHE_BREAKPOINTS = 4

internal fun anthropicPromptCachingEnabled(parameters: List<ConfigurationParameter>): Boolean =
    parameters.parameter(ANTHROPIC_PROMPT_CACHING_PARAMETER, true)

/**
 * [LLMClient] decorator that fits prompt-cache markers to the provider a request is sent to.
 * Applied by [createLLMClient] to every client, next to [ModelParamsSanitizingLLMClient].
 *
 * Every provider:
 * - Cache-control markers of another provider's type are removed. Koog's Anthropic client
 *   `require`s an [AnthropicCacheControl] (and the Bedrock client a [BedrockCacheControl]) and
 *   throws `IllegalStateException` on anything else, and the prompt is shared with fallback and
 *   cascade tiers on other providers.
 * - A system message split with [systemWithVolatileTail] is joined back into one text for
 *   providers without explicit cache control, so they receive the same system prompt they did
 *   before the split.
 *
 * Direct Anthropic, when [anthropicCaching] is on, gets up to three 5-minute breakpoints (see
 * [placeAnthropicBreakpoints]); markers already on the prompt count against Anthropic's limit of
 * [ANTHROPIC_MAX_CACHE_BREAKPOINTS].
 */
internal class PromptCachingLLMClient(
    delegate: LLMClient,
    private val anthropicCaching: Boolean,
) : ForwardingLLMClient(delegate) {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val request = prepare(prompt, tools)
        return delegate.execute(request.prompt, model, request.tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> {
        val request = prepare(prompt, tools)
        return delegate.executeStreaming(request.prompt, model, request.tools)
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice {
        val request = prepare(prompt, tools)
        return delegate.executeMultipleChoices(request.prompt, model, request.tools)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prepareCacheControls(prompt, emptyList(), delegate.llmProvider(), placeBreakpoints = false).prompt, model)

    private fun prepare(prompt: Prompt, tools: List<ToolDescriptor>): CacheControlledRequest =
        prepareCacheControls(prompt, tools, delegate.llmProvider(), anthropicCaching)
}

/** A prompt and its tool list after cache-control preparation. */
internal data class CacheControlledRequest(val prompt: Prompt, val tools: List<ToolDescriptor>)

/**
 * The request [provider] should receive: foreign cache markers removed, split system messages
 * joined for providers without explicit caching, and — for Anthropic with [placeBreakpoints] —
 * automatic breakpoints added.
 */
internal fun prepareCacheControls(
    prompt: Prompt,
    tools: List<ToolDescriptor>,
    provider: LLMProvider,
    placeBreakpoints: Boolean,
): CacheControlledRequest {
    val honoured: (CacheControl) -> Boolean = when (provider) {
        LLMProvider.Anthropic -> { cc -> cc is AnthropicCacheControl }
        LLMProvider.Bedrock -> { cc -> cc is BedrockCacheControl }
        else -> { _ -> false }
    }
    var request = CacheControlledRequest(prompt, tools).mapCacheControls { _, cc -> cc?.takeIf(honoured) }
    if (provider != LLMProvider.Anthropic && provider != LLMProvider.Bedrock) {
        request = request.copy(prompt = request.prompt.joiningSplitSystemMessages())
    }
    if (provider == LLMProvider.Anthropic && placeBreakpoints) {
        request = placeAnthropicBreakpoints(request)
    }
    return request
}

/**
 * Adds Anthropic prompt-cache breakpoints (5-minute TTL), in priority order while slots remain:
 *
 * 1. **Stable system prefix** — the last stable part of the system prompt. A system message
 *    built with [systemWithVolatileTail] marks its stable parts; otherwise the whole system
 *    prompt counts as stable. Tools render before system, so this entry covers tools + system
 *    and is shared by every request (and every run) with the same tools and system prompt.
 * 2. **Rolling tail in a tool loop** — the last block of the prompt when it answers a tool
 *    call (the latest assistant turn called tools; the user turns after it carry the results
 *    and any continuation nudge). The next round extends this exact prefix — the strategies
 *    only append — so it reads everything up to here and writes only the new assistant turn
 *    and tool results. A request that is not a tool-loop round (single shot, a fresh question
 *    after a text answer) gets no tail marker, so it never pays the write premium on a prefix
 *    nobody extends.
 * 3. **Last tool** — tools-only prefix, reused across steps and agents that share a tool set
 *    but differ in system prompt. Writes are billed once per request no matter how many
 *    breakpoints nest, so this read point costs nothing extra.
 *
 * Markers are never placed on blank text (Anthropic rejects them) or on reasoning blocks.
 * Finally a 5-minute marker that precedes a 1-hour one is lengthened to one hour, because
 * Anthropic requires longer-TTL entries to come first.
 */
internal fun placeAnthropicBreakpoints(request: CacheControlledRequest): CacheControlledRequest {
    val breakpoint = AnthropicCacheControl.Default
    val automatic = if ((request.prompt.params as? AnthropicParams)?.cacheControl != null) 1 else 0
    var slots = ANTHROPIC_MAX_CACHE_BREAKPOINTS - request.cacheControlCount() - automatic
    var messages = request.prompt.messages

    fun mark(target: PartRef?) {
        if (slots <= 0 || target == null) return
        val message = messages[target.messageIndex]
        if (message.parts[target.partIndex].cacheControl != null) return
        messages = messages.toMutableList().also {
            it[target.messageIndex] = message.withPartCacheControl(target.partIndex, breakpoint)
        }
        slots--
    }
    mark(stableSystemBreakpoint(messages))
    mark(toolLoopTailBreakpoint(messages))

    var tools = request.tools
    val lastTool = tools.lastOrNull()
    if (slots > 0 && lastTool != null && lastTool.cacheControl == null) {
        tools = tools.dropLast(1) + lastTool.withCacheControl(breakpoint)
        slots--
    }
    return CacheControlledRequest(request.prompt.withMessages { messages }, tools).withLongerTtlsFirst()
}

private data class PartRef(val messageIndex: Int, val partIndex: Int)

private fun stableSystemBreakpoint(messages: List<Message>): PartRef? {
    val systemIndices = messages.indices.filter { messages[it] is Message.System }
    if (systemIndices.isEmpty()) return null
    val split = systemIndices.firstOrNull { (messages[it] as Message.System).stablePartCount() != null }
    val messageIndex = split ?: systemIndices.last()
    val system = messages[messageIndex] as Message.System
    val stableParts = (system.stablePartCount() ?: system.parts.size).coerceIn(0, system.parts.size)
    val partIndex = (stableParts - 1 downTo 0).firstOrNull { system.parts[it].text.isNotBlank() } ?: return null
    return PartRef(messageIndex, partIndex)
}

private fun toolLoopTailBreakpoint(messages: List<Message>): PartRef? {
    val last = messages.lastOrNull() as? Message.User ?: return null
    // A round of a tool loop: the latest assistant turn called tools and the user turns after it
    // carry the results (plus any nudge the strategy appends, e.g. TOOL_RESULTS_CONTINUATION_PROMPT).
    val lastAssistant = messages.lastOrNull { it is Message.Assistant } ?: return null
    if (lastAssistant.parts.none { it is MessagePart.Tool.Call }) return null
    val partIndex = last.parts.indices.lastOrNull { index ->
        when (val part = last.parts[index]) {
            is MessagePart.Text -> part.text.isNotBlank()
            is MessagePart.Tool.Result, is MessagePart.Attachment -> true
        }
    } ?: return null
    return PartRef(messages.lastIndex, partIndex)
}

internal fun Message.System.stablePartCount(): Int? =
    (metaInfo.metadata?.get(STABLE_SYSTEM_PARTS_METADATA_KEY) as? JsonPrimitive)?.intOrNull

/** System messages split by [systemWithVolatileTail], joined back into a single text part. */
private fun Prompt.joiningSplitSystemMessages(): Prompt {
    if (messages.none { it is Message.System && it.parts.size > 1 && it.stablePartCount() != null }) return this
    return withMessages { list ->
        list.map { message ->
            if (message is Message.System && message.parts.size > 1 && message.stablePartCount() != null) {
                message.copy(parts = listOf(MessagePart.Text(message.parts.joinToString("") { it.text })))
            } else {
                message
            }
        }
    }
}

private fun CacheControlledRequest.cacheControlCount(): Int {
    var count = 0
    mapCacheControls { _, cc -> cc.also { if (it != null) count++ } }
    return count
}

/** Lengthens every 5-minute marker that renders before a 1-hour marker to one hour. */
private fun CacheControlledRequest.withLongerTtlsFirst(): CacheControlledRequest {
    var lastOneHour = -1
    mapCacheControls { position, cc -> cc.also { if (it == AnthropicCacheControl.OneHour) lastOneHour = position } }
    if (lastOneHour < 0) return this
    return mapCacheControls { position, cc ->
        if (cc == AnthropicCacheControl.Default && position < lastOneHour) AnthropicCacheControl.OneHour else cc
    }
}

/**
 * Rewrites every cache-control slot (tools, message parts, and the parts nested in tool
 * results) through [transform], visiting them in Anthropic's render order — tools, then
 * system parts, then the other messages — and passing each slot's position in that order.
 * Unchanged parts keep their identity.
 */
private fun CacheControlledRequest.mapCacheControls(
    transform: (position: Int, cacheControl: CacheControl?) -> CacheControl?,
): CacheControlledRequest {
    var position = 0
    val newTools = tools.map { tool ->
        val cc = transform(position++, tool.cacheControl)
        if (cc == tool.cacheControl) tool else tool.copy(cacheControl = cc)
    }
    fun <P : MessagePart> rewrite(part: P): P {
        val withNested = if (part is MessagePart.Tool.Result) {
            val nested = part.parts.map { rewrite(it) }
            @Suppress("UNCHECKED_CAST")
            if (nested == part.parts) part else part.copy(parts = nested) as P
        } else {
            part
        }
        val cc = transform(position++, withNested.cacheControl)
        return if (cc == withNested.cacheControl) withNested else withNested.withCacheControl(cc)
    }
    fun Message.rewriteParts(): Message = when (this) {
        is Message.System -> parts.map { rewrite(it) }.let { if (it == parts) this else copy(parts = it) }
        is Message.User -> parts.map { rewrite(it) }.let { if (it == parts) this else copy(parts = it) }
        is Message.Assistant -> parts.map { rewrite(it) }.let { if (it == parts) this else copy(parts = it) }
    }
    val rewritten = arrayOfNulls<Message>(prompt.messages.size)
    prompt.messages.forEachIndexed { i, m -> if (m is Message.System) rewritten[i] = m.rewriteParts() }
    prompt.messages.forEachIndexed { i, m -> if (m !is Message.System) rewritten[i] = m.rewriteParts() }
    val newMessages = rewritten.map { it!! }
    val newPrompt = if (newMessages == prompt.messages) prompt else prompt.withMessages { newMessages }
    return CacheControlledRequest(newPrompt, newTools)
}

private fun Message.withPartCacheControl(partIndex: Int, cc: CacheControl): Message = when (this) {
    is Message.System -> copy(parts = parts.mapIndexed { i, p -> if (i == partIndex) p.withCacheControl(cc) else p })
    is Message.User -> copy(parts = parts.mapIndexed { i, p -> if (i == partIndex) p.withCacheControl(cc) else p })
    is Message.Assistant -> copy(parts = parts.mapIndexed { i, p -> if (i == partIndex) p.withCacheControl(cc) else p })
}

@Suppress("UNCHECKED_CAST")
private fun <P : MessagePart> P.withCacheControl(cc: CacheControl?): P = when (this) {
    is MessagePart.Text -> copy(cacheControl = cc)
    is MessagePart.Attachment -> copy(cacheControl = cc)
    is MessagePart.Reasoning -> copy(cacheControl = cc)
    is MessagePart.Tool.Call -> copy(cacheControl = cc)
    is MessagePart.Tool.Result -> copy(cacheControl = cc)
    else -> error("Unknown message part ${this::class}")
} as P
