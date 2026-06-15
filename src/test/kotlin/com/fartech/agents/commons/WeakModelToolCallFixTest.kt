package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.params.LLMParams
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the Tier-2 (2026-04) tool-choice resolver + weak-model response-
 * processor gating. Implementation source: `WeakModelToolCallFix.kt`.
 *
 * We don't exercise `buildProcessorIfEnabled(parameters, toolRegistry)`
 * directly — that requires a live [ai.koog.agents.core.tools.ToolRegistry]
 * and the constructed `LLMBasedToolCallFixProcessor` is tested upstream
 * by Koog. This suite just pins the parameter-decoding surface we own.
 */
class WeakModelToolCallFixTest {

    private fun params(vararg pairs: Pair<String, Any>): List<ConfigurationParameter> = pairs.map { (k, v) ->
        ConfigurationParameter(
            key = k,
            value = JsonPrimitive(v.toString()),
        )
    }

    @Test
    fun `tool_choice is null when parameter absent`() {
        assertNull(resolveToolChoice(emptyList()))
    }

    @Test
    fun `tool_choice auto maps to Auto`() {
        assertEquals(LLMParams.ToolChoice.Auto, resolveToolChoice(params("tool_choice" to "auto")))
    }

    @Test
    fun `tool_choice required maps to Required`() {
        assertEquals(LLMParams.ToolChoice.Required, resolveToolChoice(params("tool_choice" to "required")))
    }

    @Test
    fun `tool_choice none maps to None`() {
        assertEquals(LLMParams.ToolChoice.None, resolveToolChoice(params("tool_choice" to "none")))
    }

    @Test
    fun `tool_choice with arbitrary name maps to Named`() {
        val result = resolveToolChoice(params("tool_choice" to "search_web"))
        assertTrue(result is LLMParams.ToolChoice.Named, "expected Named, got $result")
        assertEquals("search_web", (result as LLMParams.ToolChoice.Named).name)
    }

    @Test
    fun `tool_choice is case-insensitive for canonical values`() {
        assertEquals(LLMParams.ToolChoice.Auto, resolveToolChoice(params("tool_choice" to "AUTO")))
        assertEquals(LLMParams.ToolChoice.Required, resolveToolChoice(params("tool_choice" to "ReQuIrEd")))
        assertEquals(LLMParams.ToolChoice.None, resolveToolChoice(params("tool_choice" to "NONE")))
    }

    @Test
    fun `tool_choice named value preserves case`() {
        // Named values are case-sensitive — tool name lookup is exact match.
        val result = resolveToolChoice(params("tool_choice" to "MyCoolTool"))
        assertEquals("MyCoolTool", (result as LLMParams.ToolChoice.Named).name)
    }

    @Test
    fun `tool_choice blank value returns null`() {
        assertNull(resolveToolChoice(params("tool_choice" to "")))
        assertNull(resolveToolChoice(params("tool_choice" to "   ")))
    }

    @Test
    fun `tool_choice with toolRegistry=null skips validation`() {
        // Backwards-compat: the single-arg overload forwards null and must
        // behave identically to the pre-2026-04-hardening baseline so existing
        // callers don't silently lose their `tool_choice` semantics.
        val result = resolveToolChoice(params("tool_choice" to "any_tool"), toolRegistry = null)
        assertTrue(result is LLMParams.ToolChoice.Named)
        assertEquals("any_tool", (result as LLMParams.ToolChoice.Named).name)
    }

    @Test
    fun `tool_choice with empty toolRegistry falls back to null`() {
        // Validation fires when toolRegistry is supplied but empty — a typo
        // must never drop into `Named(<typo>)` and cause an infinite tool-call
        // loop inside Koog.
        val empty = ToolRegistry.EMPTY
        assertNull(resolveToolChoice(params("tool_choice" to "missing_tool"), empty))
    }

    @Test
    fun `tool_choice canonical values bypass toolRegistry validation`() {
        // auto/required/none are provider-level selectors, not tool names —
        // validating them against the registry would be wrong.
        val empty = ToolRegistry.EMPTY
        assertEquals(LLMParams.ToolChoice.Auto, resolveToolChoice(params("tool_choice" to "auto"), empty))
        assertEquals(LLMParams.ToolChoice.Required, resolveToolChoice(params("tool_choice" to "required"), empty))
        assertEquals(LLMParams.ToolChoice.None, resolveToolChoice(params("tool_choice" to "none"), empty))
    }
}
