package com.fartech.agents.commons

/**
 * Koog 1.0.0 compatibility shims.
 *
 * These declarations stand in for symbols Koog 0.8.0 exposed but 1.0.0
 * removed entirely. They keep the braidrun-workflow strategy layer compiling
 * without rewriting every call site. Each shim notes its 0.x origin and
 * the 1.0.0 replacement (when one exists).
 *
 * Companion shims in [DeepSeekReasoningNodes] cover the node-builder DSL
 * (`nodeExecuteTool` / `nodeExecuteMultipleTools`) and reasoning helpers.
 */

/**
 * Replacement for the 0.8.0 `ai.koog.agents.core.agent.ToolCalls` enum,
 * which controlled how a strategy invoked its tool-cycle sub-graph:
 *
 *   - `SEQUENTIAL` / `SINGLE_RUN_SEQUENTIAL` — one tool call per LLM round.
 *   - `PARALLEL` — multi-tool batch per LLM round.
 *
 * Koog 1.0.0 renamed the public `ToolCalls` symbol to a `data class`
 * representing a batch of tool calls (used by edges + nodes). The 0.x
 * enum no longer exists. This local enum preserves the 0.x semantic at
 * the [justDoWorkStrategy] selection layer; one branch picks
 * `toolCycleGraph` (single-tool cycle), the other picks
 * `toolCycleGraphMulti` (multi-tool cycle).
 *
 * Mapping callers may need:
 *
 *   - `ToolCalls.SEQUENTIAL` / `ToolCalls.SINGLE_RUN_SEQUENTIAL` →
 *     single-tool cycle (cheaper, deterministic).
 *   - `ToolCalls.PARALLEL` → multi-tool cycle (lower wall-clock latency
 *     when the LLM emits several tool calls per turn).
 */
enum class ToolCalls {
    SEQUENTIAL,
    SINGLE_RUN_SEQUENTIAL,
    PARALLEL,
}
