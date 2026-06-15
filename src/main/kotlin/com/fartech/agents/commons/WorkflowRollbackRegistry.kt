package com.fartech.agents.commons

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.RollbackToolRegistry
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Tier-2 (2026-04) — workflow-level "undo" registry for Koog's
 * [ai.koog.agents.snapshot.feature.Persistence.rollbackToCheckpoint].
 *
 * ## What Koog gives us
 *
 * When a workflow has persistence enabled and rolls back to a prior
 * checkpoint, Koog walks the forward `Message.Tool.Call`s executed
 * **after** the checkpoint in reverse chronological order. For each
 * call whose tool is registered here, Koog invokes the paired
 * `rollbackTool` with **the exact same arguments** the forward tool
 * was called with. This is the building block for "cancel everything
 * this agent did after step X".
 *
 * Two documented constraints (verified against Koog 0.8.0 source):
 *
 *   1. **Same args type.** `registerRollback<TArgs>(forward, rollback)`
 *      requires `forward : Tool<TArgs, *>` and `rollback : Tool<TArgs, *>`
 *      — i.e. the rollback tool must accept the forward tool's arg
 *      shape. An inverse that wants to consume the forward tool's
 *      *return value* (e.g. `removeUser(id)` consuming the id
 *      produced by `createUser`) does NOT fit this API directly; build
 *      a wrapper rollback tool that derives its deletion key from the
 *      forward args (deterministic key in forward args → mirrored
 *      lookup in rollback).
 *
 *   2. **`rollbackToLatestCheckpoint` does NOT run inverse tools.**
 *      Only the explicit `rollbackToCheckpoint(id, ctx)` path walks
 *      the tool-call diff and invokes inverses. The "latest" variant
 *      restores context state without side-effect compensation. Our
 *      UI for workflow rollback (manual_approval reject, admin
 *      intervention) must call the targeted API to get inverse
 *      behaviour.
 *
 * ## What we add here
 *
 * A thin convenience that assembles a [RollbackToolRegistry] from a
 * declarative list of `(forward, rollback)` tool names resolved against
 * an existing [ToolRegistry]. This keeps the inverse wiring close to
 * the workflow authoring layer — authors name the tools, we lift them
 * out of the registry and pair them up.
 *
 * Callers populate the `rollback_tool_pairs` configuration parameter:
 *
 * ```yaml
 * parameters:
 *   rollback_tool_pairs:
 *     - forward: create_user
 *       rollback: delete_user
 *     - forward: send_email
 *       rollback: recall_email
 * ```
 *
 * Tools that don't exist in the registry are skipped with a warning
 * (the workflow still runs — rollback just won't compensate for that
 * particular forward call). This is intentional: partial wiring is
 * safer than a startup failure when the registry is built lazily and
 * one tool name happens to be misspelled.
 *
 * ## How it plugs into [buildAgent]
 *
 * The registry is carried alongside the `Persistence` feature install
 * when `enable_persistence=true`. Default is [RollbackToolRegistry.EMPTY]
 * — rollback behaves exactly as before for workflows that don't declare
 * pairs.
 */
object WorkflowRollbackRegistry {
    private val logger = KotlinLogging.logger {}

    /** Configuration key carrying the list of rollback pairs. */
    const val CONFIG_KEY: String = "rollback_tool_pairs"

    /**
     * Build a [RollbackToolRegistry] from the declarative
     * `rollback_tool_pairs` parameter list, using [toolRegistry] to
     * resolve tool names.
     *
     * Expected parameter shape is a list of maps, each with `forward`
     * and `rollback` keys (both strings). Unknown tool names are
     * logged and skipped; the returned registry contains only the
     * successfully resolved pairs.
     *
     * Returns [RollbackToolRegistry.EMPTY] when the parameter is
     * absent, empty, or malformed — callers can forward the result
     * unconditionally.
     */
    fun buildFromParameters(
        parameters: List<ConfigurationParameter>,
        toolRegistry: ToolRegistry,
    ): RollbackToolRegistry {
        val rawPairs = parameters.parameter(
            CONFIG_KEY,
            emptyList<Map<String, String>>(),
        )
        if (rawPairs.isEmpty()) return RollbackToolRegistry.EMPTY

        // Koog 1.0.0 widened `ToolRegistry.tools` from `List<Tool<*, *>>` to
        // `List<ToolBase<*, *>>` so the registry can hold the new
        // `ToolBase`-only shapes (AgentContextAwareTool, metadata-aware
        // tools). We resolve into ToolBase here and downcast to `Tool`
        // only at the `add` call — `RollbackToolRegistry.add` still
        // requires `Tool<TArgs, *>` because the rollback executor needs
        // the `execute(args)` no-metadata signature.
        val resolved = mutableListOf<Pair<ToolBase<*, *>, ToolBase<*, *>>>()
        rawPairs.forEachIndexed { index, pair ->
            val forwardName = pair["forward"]?.trim().orEmpty()
            val rollbackName = pair["rollback"]?.trim().orEmpty()
            if (forwardName.isBlank() || rollbackName.isBlank()) {
                logger.warn {
                    "rollback_tool_pairs[$index] missing forward/rollback names; skipping: $pair"
                }
                return@forEachIndexed
            }
            val forward = toolRegistry.tools.firstOrNull { it.descriptor.name == forwardName }
            val rollback = toolRegistry.tools.firstOrNull { it.descriptor.name == rollbackName }
            if (forward == null) {
                logger.warn {
                    "rollback_tool_pairs[$index]: forward tool '$forwardName' not in registry — pair skipped"
                }
                return@forEachIndexed
            }
            if (rollback == null) {
                logger.warn {
                    "rollback_tool_pairs[$index]: rollback tool '$rollbackName' not in registry — pair skipped"
                }
                return@forEachIndexed
            }
            resolved += forward to rollback
        }

        if (resolved.isEmpty()) return RollbackToolRegistry.EMPTY

        // Use the public add() API on a fresh registry rather than
        // `RollbackToolRegistry { ... }` DSL — the DSL's
        // `registerRollback<TArgs>(Tool<TArgs, *>, Tool<TArgs, *>)`
        // requires we prove the same `TArgs` type at the call site,
        // which isn't possible when the pairs are resolved by string
        // name. `add` accepts `Tool<*, *>` pairs but enforces the same
        // constraint via an unsafe cast — Koog's internal executor
        // re-decodes args as the forward tool type, so a shape
        // mismatch fails at rollback time with a parse error rather
        // than silently corrupting data. This is the documented
        // trade-off for declarative inverse wiring.
        val registry = RollbackToolRegistry.builder().build()
        resolved.forEach { (forward, rollback) ->
            @Suppress("UNCHECKED_CAST")
            registry.add(forward as Tool<Any, *>, rollback as Tool<Any, *>)
        }
        logger.info {
            "WorkflowRollbackRegistry built with ${resolved.size} pair(s): " +
                resolved.joinToString(prefix = "[", postfix = "]") {
                    "${it.first.descriptor.name}→${it.second.descriptor.name}"
                }
        }
        return registry
    }
}
