package com.fartech.agents.workflow

/**
 * Canonical workflow template-string resolver.
 *
 * Workflow YAML supports four placeholder forms, all resolved by this object:
 *
 * | Placeholder                        | Source                        | Notes                                     |
 * |------------------------------------|-------------------------------|-------------------------------------------|
 * | `{{steps.<stepName>.output}}`      | `context.stepOutputs`         | Fully-qualified form (preferred)           |
 * | `{{<stepName>}}`                   | `context.stepOutputs`         | Bare form (kept for backward compat)       |
 * | `{{var:<varName>}}`                | `context.variables`           | Explicit "variable" namespace              |
 * | `{{<varName>}}`                    | `context.variables`           | Bare form — second lookup, so step names   |
 * |                                    |                               | shadow same-name variables                 |
 *
 * ## Why this exists
 *
 * Before Phase 4 the exact same substitution loop was hand-rolled in three places:
 *   - `ConditionEvaluator.resolveTemplateExpr`
 *   - `StateMachineEngine.resolveTemplate`
 *   - `WorkflowExecutor.resolveTemplate`
 *
 * The three copies had drifted slightly (one applied an extra `output_dir` rewrite,
 * another used different iteration order), making bug-fixes cross-cutting landmines.
 * They now all delegate here so:
 *   - fixing substitution order is a single-file change
 *   - the quadratic `str.replace` loop is contained in one place and can be swapped
 *     for a single-pass tokenizer later without touching callers
 *   - new placeholder forms require one addition, not three
 *
 * ## Thread-safety
 *
 * Stateless. Safe to call from any coroutine. The `context` map is snapshotted for
 * iteration (Kotlin's `forEach` on a mutable map will throw on concurrent mutation;
 * callers should not mutate `stepOutputs` or `variables` while a resolve is in flight).
 */
internal object WorkflowTemplateResolver {

    private val PLACEHOLDER_REGEX = Regex("\\{\\{([^{}]+)\\}\\}")

    /**
     * Resolve every placeholder in [template] against [context] and return the result.
     *
     * Uses a single-pass regex replacement so substituted step outputs or variable values
     * containing `{{...}}` sequences are never re-expanded (preventing double-expansion
     * injection such as an upstream output containing `{{var:secret}}`).
     *
     * Substitution is NOT safe against operator injection in condition expressions —
     * use [resolveConditionPart] / `ConditionEvaluator.evaluateWorkflowCondition` when
     * the substituted value could alter the enclosing syntax. This method is intended
     * for prompts, log messages, and action payloads where the result is consumed as
     * an opaque string.
     */
    fun resolve(template: String, context: WorkflowExecutionContext): String {
        if (template.isEmpty() || !template.contains("{{")) return template

        val stepOutputs = context.stepOutputs.toMap()
        val variables = context.variables.toMap()

        return PLACEHOLDER_REGEX.replace(template) { match ->
            val expr = match.groupValues[1]
            when {
                expr.startsWith("var:") -> {
                    val varName = expr.removePrefix("var:")
                    variables[varName]?.toString() ?: match.value
                }
                expr.startsWith("steps.") && expr.endsWith(".output") && expr.length > "steps..output".length -> {
                    val stepName = expr.substring("steps.".length, expr.length - ".output".length)
                    stepOutputs[stepName]
                        ?: stepOutputs[expr]
                        ?: variables[expr]?.toString()
                        ?: match.value
                }
                else -> {
                    stepOutputs[expr]
                        ?: variables[expr]?.toString()
                        ?: match.value
                }
            }
        }
    }

    /**
     * Variant used by [ConditionEvaluator] that resolves an *expression* (one side of
     * a `var op value` comparison), never the full condition string. Identical logic
     * to [resolve]; kept as a named alias so audits of call sites can distinguish the
     * condition-safe pathway.
     */
    fun resolveConditionPart(expression: String, context: WorkflowExecutionContext): String =
        resolve(expression, context)
}
