package com.fartech.agents.commons

import ai.koog.agents.core.agent.asMermaidDiagram
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess

/**
 * Tier-2 (2026-04) — Mermaid diagram export for agent strategy graphs.
 *
 * Koog 0.8.0 ships [asMermaidDiagram] (under `agents-core` JVM source set)
 * which emits a `stateDiagram` representation of any
 * [AIAgentGraphStrategy] — nested subgraphs render as Mermaid composite
 * states out of the box.
 *
 * This helper is a thin wrapper so callers don't need to import Koog's
 * agent-core extensions directly; it also exposes a "describe the default
 * strategy a given parameter set would pick" convenience so UI layers can
 * render the diagram for a **workflow step** without instantiating a full
 * agent.
 *
 * Output format
 * -------------
 * The returned string is a standalone Mermaid block — prefix it with
 * ```` ```mermaid ```` / `` ``` `` when embedding in markdown, or pass
 * it directly to any Mermaid renderer (`@mermaid-js/mermaid`, Mermaid CLI,
 * mermaid.live etc.). No client-side escaping is required.
 *
 * Limitations
 * -----------
 * - The emitted diagram describes the **agent strategy graph** of a single
 *   step (ReAct loop, tool cycles, reasoning subgraphs) — NOT the workflow
 *   DAG (which is assembled by our own `WorkflowExecutor`; its Vue Flow
 *   rendering stays the source of truth for step-level structure).
 * - Node IDs are sanitised to `[a-zA-Z0-9_]` by Koog; long Chinese names
 *   become their `hashCode` fallback. If users complain about opaque
 *   labels, the fix is upstream.
 * - Configuration is fixed — no orientation / theme flags. Callers that
 *   need customization can post-process the string before rendering.
 */
object StrategyMermaid {

    /**
     * Render an already-constructed [AIAgentGraphStrategy] to a Mermaid
     * `stateDiagram` source string.
     *
     * Reified `Any` bounds match Koog's `asMermaidDiagram<I : Any, O : Any>`
     * extension — callers with a star-projected strategy can unsafe-cast
     * ([ofStarProjected]) at the boundary, see that method's KDoc.
     */
    fun <I : Any, O : Any> of(strategy: AIAgentGraphStrategy<I, O>): String = strategy.asMermaidDiagram()

    /**
     * Render a star-projected strategy. Use when you're holding an
     * `AIAgentGraphStrategy<*, *>` (e.g. from a registry keyed by name)
     * and don't care about the input/output parameter types — Mermaid
     * rendering inspects only the graph metadata, not the I/O types.
     *
     * The unchecked cast is safe at runtime because
     * [MermaidDiagramGenerator] never materializes values of `I` or `O`.
     */
    @Suppress("UNCHECKED_CAST")
    fun ofStarProjected(strategy: AIAgentGraphStrategy<*, *>): String =
        (strategy as AIAgentGraphStrategy<Any, Any>).asMermaidDiagram()

    /**
     * Convenience: build the default strategy the agent runtime would pick
     * for [parameters] and render it. Useful for workflow-editor debug
     * panels where the caller has a `ConfigurationParameter` bag but no
     * live agent.
     *
     * The [httpAccess] argument is required only because
     * [determineDefaultStrategy] plumbs it through for tool-registry
     * construction; no HTTP calls are made while rendering the diagram.
     */
    fun forDefaultStrategy(
        httpAccess: HttpAccess,
        parameters: List<ConfigurationParameter>,
    ): String = determineDefaultStrategy(httpAccess = httpAccess, parameters = parameters).asMermaidDiagram()
}
