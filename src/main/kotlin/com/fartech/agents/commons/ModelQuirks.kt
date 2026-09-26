package com.fartech.agents.commons

import ai.koog.prompt.llm.LLModel

/**
 * Per-model wire constraints that capability lists cannot be trusted to carry.
 *
 * Web supplemental picks and workflow `custom_models` reach [createCustomModel] with
 * STANDARD capabilities (which declare Temperature and ToolChoice), and Koog's own Claude 5
 * definitions declare Temperature too, so the capability check alone would still send a
 * parameter these models reject with HTTP 400. Matched by id so they apply on every route
 * (direct Anthropic and OpenRouter's `anthropic/...` ids), dotted or dashed, with or without
 * a date / variant suffix.
 *
 * Source: Anthropic model reference (2026-06).
 * - Sampling parameters (`temperature` / `top_p` / `top_k`) are rejected by Opus 4.7, Opus 4.8,
 *   Opus 5.x, Fable 5.x, Mythos 5.x and Sonnet 5 (Sonnet 5 rejects any non-default value).
 * - Forced `tool_choice` (`any` / `tool`) is rejected by the thinking-on-by-default models:
 *   Opus 5.x, Sonnet 5, Fable 5.x, Mythos 5.x.
 * - Kimi K3 on the direct Kimi provider fixes temperature at 1.0 and rejects any other value
 *   (a workflow `custom_models` K3 entry would otherwise declare Temperature via STANDARD).
 */
internal object ModelQuirks {

    fun rejectsSampling(model: LLModel): Boolean {
        if (isKimiK3(model)) return true
        val claude = parseClaudeVersion(model.id) ?: return false
        return when (claude.family) {
            "opus" -> claude.major >= 5 || claude.major == 4 && claude.minor >= 7
            "sonnet", "fable", "mythos" -> claude.major >= 5
            else -> false
        }
    }

    fun rejectsForcedToolChoice(model: LLModel): Boolean = thinksByDefault(model.id)

    /** Omitting `thinking` still runs adaptive thinking, so responses carry thinking blocks. */
    fun thinksByDefault(modelId: String): Boolean {
        val claude = parseClaudeVersion(modelId) ?: return false
        return claude.family in THINKING_BY_DEFAULT_FAMILIES && claude.major >= 5
    }

    /**
     * Max output ceiling of older Claude models whose catalog entries carry no
     * `max_output_tokens`, where it is below the engine's default `max_tokens`.
     * Null when unknown or not below the defaults.
     */
    fun legacyClaudeMaxOutputTokens(model: LLModel): Long? {
        parseClaudeVersion(model.id)?.let { claude ->
            // Opus 4 / 4.1 stop at 32K; every later Claude allows at least 64K.
            return if (claude.family == "opus" && claude.major == 4 && claude.minor <= 1) 32_000L else null
        }
        // Claude 3.x ids put the version before the family (`claude-3-5-haiku-20241022`).
        val match = LEGACY_CLAUDE_ID.find(bareId(model.id)) ?: return null
        val minor = match.groupValues[1].toIntOrNull() ?: 0
        return when (minor) {
            0 -> 4_096L
            5 -> 8_192L
            else -> null
        }
    }

    /** Kimi K3 on the direct Kimi provider only (the same id on OpenRouter keeps its temperature). */
    private fun isKimiK3(model: LLModel): Boolean =
        model.provider == KIMI_LLM_PROVIDER && model.id.equals("kimi-k3", ignoreCase = true)

    private data class ClaudeVersion(val family: String, val major: Int, val minor: Int)

    private val THINKING_BY_DEFAULT_FAMILIES = setOf("opus", "sonnet", "fable", "mythos")

    /** `claude-<family>-<major>[.-<minor>]`, then end or a suffix (`-20250514`, `-fast`, `:thinking`). */
    private val CLAUDE_ID = Regex("""^claude-([a-z]+)-(\d{1,2})(?:[.-](\d{1,2}))?(?=$|[-.@:\[])""")

    private val LEGACY_CLAUDE_ID = Regex("""^claude-3(?:[.-](\d))?-(?:opus|sonnet|haiku)""")

    private fun parseClaudeVersion(id: String): ClaudeVersion? {
        val match = CLAUDE_ID.find(bareId(id)) ?: return null
        return ClaudeVersion(
            family = match.groupValues[1],
            major = match.groupValues[2].toInt(),
            minor = match.groupValues[3].toIntOrNull() ?: 0,
        )
    }

    /** Lowercased id without a routing prefix (`anthropic/`, Bedrock-style `us.anthropic.`). */
    private fun bareId(id: String): String {
        val lower = id.trim().lowercase().substringAfterLast('/')
        return if ("anthropic." in lower) lower.substringAfter("anthropic.") else lower
    }
}
