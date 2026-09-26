package com.fartech.agents.commons

import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Prompt-cache-control helpers for stable long prefixes.
 *
 * ## Background — what this actually does
 *
 * Provider-side prompt caching (Anthropic's `cache_control`, Bedrock's
 * `CachePointBlock`) lets the provider bill long stable prefixes once and
 * then hit a token-level cache for subsequent requests that share the same
 * prefix. This is distinct from — and complementary to — our own response
 * cache ([MeteringSafeCachedPromptExecutor]), which deduplicates **identical**
 * request envelopes; provider-side caching pays off even when the trailing
 * user message differs between calls.
 *
 * Direct Anthropic requests already get automatic breakpoints (tools, stable
 * system prefix, tool-loop tail) from [PromptCachingLLMClient]; these helpers
 * are for callers that want an extra, explicit read point — e.g. a long
 * document shared by many questions.
 *
 * ## Koog 1.3.0 support matrix (verified against source)
 *
 * | Provider   | [CacheControl] honored? | TTL options |
 * |------------|-------------------------|-------------|
 * | **Anthropic** | **YES** | `Default` (= 5 min, no explicit TTL), `OneHour` |
 * | **Bedrock**   | **YES** | `Default`, `FiveMinutes`, `OneHour` |
 * | Others     | NO                      | — |
 *
 * ## Provider safety
 *
 * A hint is **not** safe on every provider by itself: Koog's Anthropic client
 * `require`s an [AnthropicCacheControl] and throws `IllegalStateException` on a
 * [BedrockCacheControl] (and the Bedrock client does the reverse). Prompts built
 * here are safe only because [PromptCachingLLMClient], applied to every client
 * by [createLLMClient], removes markers of another provider's type before the
 * request is serialized — which matters because a prompt is shared with
 * fallback and cascade tiers on other providers.
 *
 * ## TTL and limits
 *
 * Anthropic allows at most 4 breakpoints per request (the automatic ones
 * count), and the minimum cacheable prefix is 512–4096 tokens depending on the
 * model — shorter prefixes silently don't cache. A 5-minute entry is refreshed
 * by every read, so traffic that reuses a prefix within 5 minutes never needs
 * the 1-hour TTL; a 1-hour write costs 2× input instead of 1.25×. braidrun-web
 * prices cache writes at the 5-minute rate, so prefer [CacheTtl.Default].
 *
 * ## Usage pattern
 *
 * ```kotlin
 * prompt("chat") {
 *     systemWithVolatileTail(stable = longStableSkillPrompt, volatileTail = "\n\nDate: $now")
 *     userWithCacheHint(longSharedDocument)
 *     user { +currentTurnPrompt }
 * }
 * ```
 *
 * Only the **cumulative prefix up to and including the cache-hinted block**
 * is cached by the provider; anything after the hint is re-evaluated per
 * request. This matches Anthropic's documented `cache_control` semantic and
 * Bedrock's `CachePointBlock` placement rule.
 */

/**
 * `RequestMetaInfo.metadata` key on a system message built by [systemWithVolatileTail]: how
 * many leading parts form its stable, cacheable prefix.
 */
const val STABLE_SYSTEM_PARTS_METADATA_KEY = "braidrun_stable_system_parts"

/**
 * Append a system message made of a [stable] prefix and a [volatileTail] (per-request content
 * such as the current date) that must not be part of any cached prefix.
 *
 * Direct Anthropic requests get the two as separate system blocks with the stable-prefix
 * breakpoint on the first, so a changing tail no longer invalidates the cached tools + system
 * prefix. Every other provider receives the single text `stable + volatileTail`, exactly as if
 * it had been built with `system(stable + volatileTail)` (see [PromptCachingLLMClient]).
 *
 * The tail still renders before the conversation, so a tail that changes between two requests
 * invalidates the cached *messages* between them; put anything that changes within a
 * conversation after it instead.
 */
fun PromptBuilder.systemWithVolatileTail(stable: String, volatileTail: String) {
    if (stable.isBlank() || volatileTail.isEmpty()) {
        system(stable + volatileTail)
        return
    }
    message(
        Message.System(
            parts = listOf(MessagePart.Text(stable), MessagePart.Text(volatileTail)),
            metaInfo = RequestMetaInfo(
                timestamp = KoogClock.System.now(),
                metadata = JsonObject(mapOf(STABLE_SYSTEM_PARTS_METADATA_KEY to JsonPrimitive(1))),
            ),
        )
    )
}

/**
 * Cache-eligible provider; selects the concrete [CacheControl] subtype
 * the hint emits. Defaults to [Anthropic] because Claude is braidrun's
 * most-used LLM family.
 */
enum class CacheProvider { Anthropic, Bedrock }

/**
 * TTL tier for a cache hint. Maps to provider-specific TTL values; providers
 * without explicit TTL controls silently ignore the distinction.
 *
 * [Default] — leave TTL selection to the provider (Anthropic ≈ 5 min,
 *   Bedrock default).
 * [FiveMinutes] — short TTL. **Bedrock only** — Anthropic doesn't expose
 *   a discrete 5-min mode; map degrades to [Default].
 * [OneHour] — long TTL (2× input write price); only pays off when the same
 *   prefix is reused after gaps of 5–60 minutes.
 */
enum class CacheTtl {
    Default,
    FiveMinutes,
    OneHour,
}

private fun CacheTtl.toCacheControl(provider: CacheProvider): CacheControl = when (provider) {
    CacheProvider.Anthropic -> when (this) {
        // Anthropic has no 5-min discrete tier — the default (no explicit TTL header)
        // is documented as 5-minute caching, so FiveMinutes and Default map together.
        CacheTtl.Default, CacheTtl.FiveMinutes -> AnthropicCacheControl.Default
        CacheTtl.OneHour -> AnthropicCacheControl.OneHour
    }
    CacheProvider.Bedrock -> when (this) {
        CacheTtl.Default -> BedrockCacheControl.Default
        CacheTtl.FiveMinutes -> BedrockCacheControl.FiveMinutes
        CacheTtl.OneHour -> BedrockCacheControl.OneHour
    }
}

/**
 * Append a single-text system message tagged with a prompt-cache hint.
 *
 * The [content] is emitted as a system message whose `cacheControl` is set
 * to the [ttl]-mapped [CacheControl] for the chosen [provider]. Providers
 * that don't honor the field never see it ([PromptCachingLLMClient] removes it) —
 * see the file-level KDoc for the support matrix.
 *
 * Prefer this over raw `system { +content }` at the **stable prefix**
 * boundary of a prompt (e.g. right after the baseline skill cards and tool
 * descriptions, before appending locale-specific or turn-specific context).
 *
 * @param content the system-message body. Must not be blank.
 * @param ttl cache tier hint; defaults to [CacheTtl.Default] (5 minutes,
 *   refreshed on every read).
 * @param provider cache flavour to emit. Defaults to [CacheProvider.Anthropic]
 *   because Claude is the most-used provider in braidrun; pass
 *   [CacheProvider.Bedrock] explicitly when targeting AWS Bedrock.
 */
fun PromptBuilder.systemWithCacheHint(
    content: String,
    ttl: CacheTtl = CacheTtl.Default,
    provider: CacheProvider = CacheProvider.Anthropic,
) {
    require(content.isNotBlank()) { "systemWithCacheHint requires non-blank content" }
    // Koog 1.0.0 renamed the PromptBuilder.system `cacheControl:` parameter to `cache:`.
    system(content = content, cache = ttl.toCacheControl(provider))
}

/**
 * Append a single-text user message tagged with a prompt-cache hint.
 *
 * Useful when the conversation has a long stable user-visible preamble
 * (e.g. a document the assistant is summarizing across many questions)
 * before the short volatile turn-specific question.
 */
fun PromptBuilder.userWithCacheHint(
    content: String,
    ttl: CacheTtl = CacheTtl.Default,
    provider: CacheProvider = CacheProvider.Anthropic,
) {
    require(content.isNotBlank()) { "userWithCacheHint requires non-blank content" }
    // Koog 1.0.0 `user(content: String, cache: CacheControl? = null)` mirrors the
    // system overload. For the parts-list form, `cacheControl` lives on individual
    // MessagePart.Text entries instead — we use the simple-string form here.
    user(content = content, cache = ttl.toCacheControl(provider))
}

/**
 * Append a user message whose body is the supplied list of parts, with the
 * cache hint applied to **each text part**. Useful when the prompt mixes
 * text and attachments — only the text parts are cache-eligible at the
 * provider layer.
 */
fun PromptBuilder.userPartsWithCacheHint(
    parts: List<MessagePart.RequestPart>,
    ttl: CacheTtl = CacheTtl.Default,
    provider: CacheProvider = CacheProvider.Anthropic,
) {
    val cacheControl = ttl.toCacheControl(provider)
    val tagged = parts.map { part ->
        when (part) {
            is MessagePart.Text -> MessagePart.Text(part.text, cacheControl)
            else -> part
        }
    }
    user(parts = tagged)
}
