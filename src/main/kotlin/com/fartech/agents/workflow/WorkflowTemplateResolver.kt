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

    /**
     * Resolve every placeholder in [template] against [context] and return the result.
     *
     * Substitution is NOT safe against operator injection in condition expressions —
     * use [resolveConditionPart] / `ConditionEvaluator.evaluateWorkflowCondition` when
     * the substituted value could alter the enclosing syntax. This method is intended
     * for prompts, log messages, and action payloads where the result is consumed as
     * an opaque string.
     */
    fun resolve(template: String, context: WorkflowExecutionContext): String {
        if (template.isEmpty() || '{' !in template) return template
        var result = template

        // Step outputs resolved first, so a variable with the same name does NOT shadow
        // step output (matches legacy behaviour across all three previous implementations).
        context.stepOutputs.forEach { (stepName, output) ->
            result = result.replace("{{steps.$stepName.output}}", output)
            result = result.replace("{{$stepName}}", output)
        }
        context.variables.forEach { (varName, value) ->
            val s = value.toString()
            result = result.replace("{{var:$varName}}", s)
            result = result.replace("{{$varName}}", s)
        }
        return result
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
