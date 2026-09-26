package com.fartech.agents.commons

import ai.koog.prompt.llm.LLModel

/** `claude-<family>-<major>.<minor>` — the OpenRouter-style dotted spelling of a dashed alias. */
private val DOTTED_CLAUDE_VERSION = Regex("""^(claude-[a-z]+-\d+)\.(\d+)""")

/**
 * The wire id the first-party Anthropic API expects for a catalog / custom model id.
 *
 * Strips an OpenRouter `anthropic/` prefix and rewrites the dotted spelling
 * (`claude-opus-4.7`) to Anthropic's dashed alias (`claude-opus-4-7`). Dated snapshots
 * (`claude-sonnet-4-20250514`), already-dashed aliases and non-Claude custom ids are
 * returned unchanged.
 */
internal fun normalizeAnthropicModelId(id: String): String =
    id.trim().removePrefix("anthropic/").replaceFirst(DOTTED_CLAUDE_VERSION, "$1-$2")

/**
 * `AnthropicClientSettings.modelVersionsMap` that maps every model to its own (normalized) id.
 *
 * Koog's `AnthropicLLMClient` builds the request with `modelVersionsMap[model] ?: throw
 * IllegalArgumentException("Unsupported model")`, and its default map is keyed by Koog's own
 * `LLModel` objects (data-class equality over provider, id, capabilities and limits). None of
 * our catalog or custom models equal those, so without this every direct Anthropic call
 * failed before sending. Koog only reads the map through `get` (AnthropicLLMClient request
 * builder, Koog 1.0–1.3), so iteration is deliberately empty.
 */
internal object AnthropicModelIdPassThrough : AbstractMap<LLModel, String>() {
    override val entries: Set<Map.Entry<LLModel, String>> = emptySet()
    override fun get(key: LLModel): String = normalizeAnthropicModelId(key.id)
    override fun containsKey(key: LLModel): Boolean = true
}
