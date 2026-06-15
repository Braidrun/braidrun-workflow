package com.fartech.agents.commons

import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.MessagePart

/**
 * Prompt-cache-control helpers for stable long prefixes.
 *
 * ## Background — what this actually does
 *
 * Provider-side prompt caching (Anthropic's `cache_control`, Bedrock's
 * `CachePointBlock`) lets the provider bill long stable prefixes once and
 * then hit a token-level cache for subsequent requests that share the same
 * prefix. This is distinct from — and complementary to — our own
 * [ai.koog.prompt.executor.cached.CachedPromptExecutor] layer, which
 * deduplicates **identical** request envelopes; provider-side caching pays
 * off even when the trailing user message differs between calls.
 *
 * ## Koog 1.0.0 support matrix (verified against source)
 *
 * | Provider   | [CacheControl] honored? | TTL options |
 * |------------|-------------------------|-------------|
 * | **Anthropic** | **YES (new in 1.0.0)** | `Default` (= 5 min, no explicit TTL header), `OneHour` |
 * | **Bedrock**   | **YES**                | `Default`, `FiveMinutes`, `OneHour` |
 * | OpenRouter | NO (silent no-op)       | — |
 * | Others     | NO (silent no-op)       | — |
 *
 * Koog 0.8.0 only honoured Bedrock; Koog 1.0.0 added native Anthropic
 * prompt caching via the `AnthropicCacheControl` subtype. The Anthropic
 * support is the headline win — every long system prompt going to
 * Claude (the assistant pipeline default, the workflow-author preset
 * recommendations, the skill-card prefix) now caches automatically on
 * the second request.
 *
 * Setting a cache hint is **safe on every provider** (the field is a
 * marker interface; providers that don't understand a particular subtype
 * silently ignore it). The defaults here emit [AnthropicCacheControl]
 * because Claude is braidrun's most-used provider; pass an explicit
 * [Provider] when targeting Bedrock.
 *
 * ## Usage pattern
 *
 * Build your `prompt { system { ... } ... }` as usual, then thread the
 * stable prefix (e.g. skill cards + tool guide + schema definition) through
 * [systemWithCacheHint] so the backend knows that block is cache-eligible:
 *
 * ```kotlin
 * prompt("chat") {
 *     systemWithCacheHint(longStableSkillPrompt, ttl = CacheTtl.OneHour)
 *     // Short, volatile tail without cache hint:
 *     system { +"User locale: zh-CN" }
 *     user { +currentTurnPrompt }
 * }
 * ```
 *
 * Only the **cumulative prefix up to and including the cache-hinted block**
 * is cached by the provider; any subsequent system/user messages after the
 * hint are re-evaluated per request. This matches Anthropic's documented
 * `cache_control` semantic and Bedrock's `CachePointBlock` placement rule.
 */

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
 * [OneHour] — long TTL; for prefixes that are stable across many
 *   conversation turns (system prompt, skill card, tool schema).
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
 * that don't honor the field ignore it silently — see the file-level KDoc
 * for the support matrix.
 *
 * Prefer this over raw `system { +content }` at the **stable prefix**
 * boundary of a prompt (e.g. right after the baseline skill cards and tool
 * descriptions, before appending locale-specific or turn-specific context).
 *
 * @param content the system-message body. Must not be blank.
 * @param ttl cache tier hint; defaults to [CacheTtl.OneHour] which suits
 *   assistant-style long-prefix workloads.
 * @param provider cache flavour to emit. Defaults to [CacheProvider.Anthropic]
 *   because Claude is the most-used provider in braidrun; pass
 *   [CacheProvider.Bedrock] explicitly when targeting AWS Bedrock.
 */
fun PromptBuilder.systemWithCacheHint(
    content: String,
    ttl: CacheTtl = CacheTtl.OneHour,
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
    ttl: CacheTtl = CacheTtl.OneHour,
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
    ttl: CacheTtl = CacheTtl.OneHour,
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
