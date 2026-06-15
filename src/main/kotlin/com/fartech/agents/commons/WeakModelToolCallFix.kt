package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.processor.LLMBasedToolCallFixProcessor
import ai.koog.prompt.processor.ManualToolCallFixProcessor
import ai.koog.prompt.processor.ResponseProcessor
import ai.koog.prompt.processor.ToolCallJsonConfig
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import mu.KotlinLogging

private val weakModelToolCallFixLogger = KotlinLogging.logger("WeakModelToolCallFix")

/**
 * Tier-2 (2026-04) — weak-model tool-call hardening.
 *
 * ## Background
 *
 * Strong tool-calling LLMs (Claude 4.x, GPT-5.x, Gemini 3+) emit well-
 * formed `tool_use` / `function_call` blocks that Koog parses cleanly.
 *
 * Weaker or cheaper models — DeepSeek V3, Qwen-Plus, Moonshot Kimi,
 * MiniMax, some local Ollama models — frequently mis-emit tool calls:
 * they embed JSON inside markdown fences, omit the `id` field, wrap
 * arguments in a different envelope, or produce plain-prose
 * "I'll call searchWeb('braidrun')" text instead of structured calls.
 *
 * Koog 0.8.0 ships two response processors that address this:
 *
 *   1. [ManualToolCallFixProcessor] — regex-based preprocessor, pulls
 *      JSON out of code fences and tries a handful of known argument
 *      envelopes. Free (no LLM calls).
 *   2. [LLMBasedToolCallFixProcessor] — iterative LLM-in-the-loop fixer.
 *      Asks the original LLM to repair its own output using targeted
 *      feedback prompts. Runs up to `maxRetries` iterations; can fall
 *      back to a different, stronger model via a nested processor.
 *
 * Both are **constructor arguments** on [ai.koog.agents.core.agent.config.AIAgentConfig]
 * — they are NOT `install(...) { }` features. See [buildAgent] in
 * `AgentBootstrap.kt` for the wiring.
 *
 * ## When to enable
 *
 * Set `weak_model_tool_fix_enabled=true` in workflow parameters when the
 * primary model (or any cascade-fallback tier) is in the "weak tool-
 * calling" bucket. Cost:
 *
 *   - Each malformed tool call triggers up to [DEFAULT_MAX_RETRIES]
 *     extra LLM round-trips to the same model.
 *   - Well-formed calls cost 0 additional tokens — the manual
 *     preprocessor catches them without any LLM re-call.
 *
 * Rule of thumb: enable when > 2% of agent runs hit
 * `tool_validation_failed` events. Leave off for Anthropic / OpenAI /
 * Google primary flows.
 */
object WeakModelToolCallFix {
    /** Default retry budget for the LLM-based fix loop. */
    const val DEFAULT_MAX_RETRIES: Int = 3

    /**
     * Decide whether the workflow / preset parameters ask for tool-call
     * fixup, and build a matching [ResponseProcessor] chain.
     *
     * Returns `null` when the feature is off — callers should forward
     * `null` directly to [ai.koog.agents.core.agent.config.AIAgentConfig.responseProcessor]
     * so no processing happens on the hot path.
     */
    fun buildProcessorIfEnabled(
        parameters: List<ConfigurationParameter>,
        toolRegistry: ToolRegistry,
    ): ResponseProcessor? {
        if (!parameters.parameter("weak_model_tool_fix_enabled", false)) return null

        val maxRetries = parameters.parameter("weak_model_tool_fix_max_retries", DEFAULT_MAX_RETRIES)
        val manualOnly = parameters.parameter("weak_model_tool_fix_manual_only", false)

        // Manual-only mode: just unwrap code fences, never spend a
        // second LLM round-trip. Sufficient for models that format
        // correctly but occasionally wrap in ```json```.
        if (manualOnly) {
            return ManualToolCallFixProcessor(toolRegistry, ToolCallJsonConfig())
        }

        return LLMBasedToolCallFixProcessor(
            toolRegistry = toolRegistry,
            toolCallJsonConfig = ToolCallJsonConfig(),
            maxRetries = maxRetries,
            // Defaults: preprocessor = ManualToolCallFixProcessor; no
            // fallback processor (returns the original message when the
            // loop exhausts). Callers that want a stronger model to
            // take over on exhaustion can post-process the ResponseProcessor
            // chain themselves via `processor + anotherProcessor`.
        )
    }
}

/**
 * Tier-2 (2026-04) — portable "force this specific tool" helper.
 *
 * Each provider-native LLM client in Koog 0.8.0 reads from a single
 * unified [LLMParams.ToolChoice] sealed type; the same value is
 * translated to the provider-specific wire field
 * (`tool_choice`/`toolConfig`/etc.) at the client layer. This gives
 * cross-provider "must call this tool" / "never call any tool"
 * semantics without provider-specific params.
 *
 * Wiring point: set `tool_choice` on the `LLMParams` used when
 * constructing the initial agent prompt. See [buildAgent] for the
 * current use-site (it resolves `tool_choice_*` parameters into an
 * `LLMParams.ToolChoice` value that propagates through the prompt
 * builder).
 *
 * Recognised string values (case-insensitive, matches parameter value):
 *
 *   - `"auto"` → [LLMParams.ToolChoice.Auto] (default; model decides).
 *   - `"required"` → [LLMParams.ToolChoice.Required] (any tool).
 *   - `"none"` → [LLMParams.ToolChoice.None] (text only).
 *   - Any other non-blank string → [LLMParams.ToolChoice.Named] with the
 *     string as the tool name (case-sensitive).
 *
 * Unknown / blank values return `null` → Koog uses its default.
 */
fun resolveToolChoice(parameters: List<ConfigurationParameter>): LLMParams.ToolChoice? =
    resolveToolChoice(parameters, toolRegistry = null)

/**
 * Overload that validates `tool_choice: <toolName>` against the supplied
 * [toolRegistry]. When the named tool is NOT registered, we log a warning
 * and fall back to `null` (Koog default) rather than emit
 * `LLMParams.ToolChoice.Named(<missing>)` — the latter causes every
 * provider to reply with a tool-call error that the agent cannot satisfy,
 * usually resulting in a loop until max_iterations.
 *
 * Pass `toolRegistry = null` (via the no-arg overload) for call sites that
 * cannot cheaply resolve the registry — in that case no validation is
 * performed and the behaviour is identical to pre-2026-04.
 */
fun resolveToolChoice(
    parameters: List<ConfigurationParameter>,
    toolRegistry: ToolRegistry?,
): LLMParams.ToolChoice? {
    // Use the standard `parameter(key, default)` helper so JSON decoding
    // is identical to every other parameter read in the codebase.
    // Previous implementation used `.toString()` on the raw JsonElement
    // which round-tripped string values with embedded quotes, then relied
    // on `removeSurrounding("\"")` to strip them — brittle for non-string
    // primitives (booleans / numbers) and hand-rolled YAML escapes.
    val raw = parameters.parameter("tool_choice", "").trim()
    if (raw.isEmpty()) return null

    return when (raw.lowercase()) {
        "auto" -> LLMParams.ToolChoice.Auto
        "required" -> LLMParams.ToolChoice.Required
        "none" -> LLMParams.ToolChoice.None
        else -> {
            if (toolRegistry != null) {
                val known = toolRegistry.tools.any { it.descriptor.name == raw }
                if (!known) {
                    val available = toolRegistry.tools.joinToString(", ") { it.descriptor.name }
                        .ifEmpty { "<none>" }
                    weakModelToolCallFixLogger.warn {
                        "tool_choice='$raw' names a tool that is NOT in the tool registry; falling back to default (Auto). " +
                            "Available tool names: $available"
                    }
                    return null
                }
            }
            LLMParams.ToolChoice.Named(raw)
        }
    }
}
