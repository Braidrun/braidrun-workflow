package com.fartech.agents.workflow

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Supported comparison operators in workflow conditions, in the order we try to match them.
 * Longest first so `>=` wins over `>`, and `!=` is parsed before the bare value.
 *
 * ## Case-sensitivity policy (cleaned up in Phase 3)
 *
 *   - `==` / `!=` — case-sensitive (exact match)
 *   - `contains` — case-sensitive (previously case-insensitive, inconsistent with `==`)
 *   - `contains_ci` — NEW explicit case-insensitive variant
 *   - `contains_cs` — NEW explicit case-sensitive variant (alias for `contains`)
 *
 * The old `contains` defaulted to case-insensitive which silently made
 * `status contains Error` match both "error" and "WARNING: error inside". Because some
 * workflows rely on that behaviour, an env var `WORKFLOW_CONDITION_CONTAINS_ICASE=true`
 * preserves the legacy semantic. Operators can opt in globally during the migration
 * window and opt out per-condition via `contains_cs`.
 */
private val CONDITION_OPERATOR_PATTERN = Regex(
    // At least one whitespace on each side — stops "abc==def" (no spaces) from parsing as
    // an operator, which matches the pre-existing split-by-\\s+ behaviour.
    """\s+(==|!=|>=|<=|>|<|contains_ci|contains_cs|contains)\s+""",
    RegexOption.IGNORE_CASE
)

private fun legacyContainsIsCaseInsensitive(): Boolean {
    val sys = System.getProperty("WORKFLOW_CONDITION_CONTAINS_ICASE")?.takeIf { it.isNotBlank() }
    val env = System.getenv("WORKFLOW_CONDITION_CONTAINS_ICASE")?.takeIf { it.isNotBlank() }
    return (sys ?: env)?.equals("true", ignoreCase = true) == true
}

private fun resolveTemplateExpr(expr: String, context: WorkflowExecutionContext): String =
    WorkflowTemplateResolver.resolveConditionPart(expr, context)

/**
 * Back-compat helper: resolve *all* templates inside a condition string without splitting.
 *
 * **Warning**: this is unsafe to use for condition evaluation — a substituted value can
 * contain whitespace / operators that re-parse into new condition structure. Kept public
 * only for diagnostics/logging. Use [evaluateWorkflowCondition] for real condition checks.
 */
internal fun resolveWorkflowConditionTemplates(
    condition: String,
    context: WorkflowExecutionContext
): String = resolveTemplateExpr(condition, context)

/**
 * Parsed, unresolved pieces of a condition.
 */
private data class ParsedCondition(
    val leftTemplate: String,
    val operator: String,
    val rightTemplate: String
)

/**
 * Parse the *raw template* before any substitution. This is the critical anti-injection
 * step: if we split after substitution, a malicious step output (e.g. `== true`) would be
 * able to rewrite the condition. By locking in operator boundaries from the template we
 * ensure substituted values can only affect the data being compared, not the structure
 * of the comparison.
 */
private fun parseConditionTemplate(condition: String): ParsedCondition? {
    val trimmed = condition.trim()
    val match = CONDITION_OPERATOR_PATTERN.find(trimmed) ?: return null
    val leftTemplate = trimmed.substring(0, match.range.first)
    val rightTemplate = trimmed.substring(match.range.last + 1)
    val operator = match.groupValues[1].lowercase()
    if (leftTemplate.isBlank() || rightTemplate.isBlank()) return null
    return ParsedCondition(leftTemplate.trim(), operator, rightTemplate.trim())
}

/**
 * Public entry point for condition evaluation.
 *
 * Supports compound conditions with `&&` (logical AND) and `||` (logical OR) at the
 * **template level** (precedence: `||` binds loosest, `&&` binds tighter, comparison
 * operators bind tightest). For example:
 *
 *   `a == 1 && b != '' || c contains_cs ://`
 *
 * is evaluated as `(a == 1 AND b != '') OR (c contains_cs ://)`.
 *
 * Splitting happens **before** any variable substitution so that values containing
 * literal `&&` / `||` cannot rewrite the structure (mirrors the same anti-injection
 * stance as [parseConditionTemplate]). Quoted string literals (`'...'` / `"..."`) are
 * also respected — `name == 'a && b'` keeps the `&&` inside the value.
 *
 * Parentheses are NOT supported; use the implicit precedence (`&&` > `||`). For
 * complex grouping, decompose into multiple steps with an intermediate boolean
 * variable.
 *
 * Empty pieces (e.g. trailing `&&`) and otherwise-unparseable leaves return `false`
 * with a warning, consistent with the pre-2026-04 single-comparison semantics.
 */
internal fun evaluateWorkflowCondition(
    condition: String?,
    context: WorkflowExecutionContext,
    fallbackToStepOutput: Boolean = false
): Boolean {
    // null = "no condition" → step always runs (preserves pre-2026-04 contract).
    if (condition == null) return true
    val trimmed = condition.trim()
    // Empty / whitespace-only string is a malformed condition. Pre-2026-04 this fell
    // through to parseConditionTemplate() which returned null → false. Preserve that
    // so an empty leaf produced by splitting `"a == 1 && "` on `&&` correctly evaluates
    // as false rather than silently passing.
    if (trimmed.isEmpty()) {
        logger.warn { "Empty workflow condition; treating as false" }
        return false
    }

    // Lowest precedence first: split on top-level || (any branch true → true).
    val orParts = splitOnLogicalOperator(trimmed, "||")
    if (orParts.size > 1) {
        return orParts.any { evaluateWorkflowCondition(it, context, fallbackToStepOutput) }
    }

    // Then: split on top-level && (all branches true → true).
    val andParts = splitOnLogicalOperator(trimmed, "&&")
    if (andParts.size > 1) {
        return andParts.all { evaluateWorkflowCondition(it, context, fallbackToStepOutput) }
    }

    // Leaf: a single comparison expression.
    return evaluateSingleComparison(trimmed, context, fallbackToStepOutput)
}

/**
 * Evaluate one leaf comparison `<left> <op> <right>`. Pre-2026-04 the public
 * entry point did this directly; the compound (`&&` / `||`) layer was added on
 * top so existing call sites & semantics for single comparisons are unchanged.
 */
private fun evaluateSingleComparison(
    condition: String,
    context: WorkflowExecutionContext,
    fallbackToStepOutput: Boolean
): Boolean {
    return try {
        val parsed = parseConditionTemplate(condition) ?: run {
            logger.warn { "Invalid workflow condition (expected 'left op right'): '$condition'" }
            return false
        }

        val leftResolved = resolveTemplateExpr(parsed.leftTemplate, context)
        val rightResolved = resolveTemplateExpr(parsed.rightTemplate, context)

        // The left operand can either be a literal expression (already resolved) or a
        // bare variable name. Preserve legacy behaviour: if `leftResolved` matches a known
        // variable, use its value; otherwise treat the resolved string itself as the value.
        val actualValue: Any = context.getVariable(leftResolved)
            ?: (if (fallbackToStepOutput) context.getStepOutput(leftResolved) else null)
            ?: leftResolved

        // Strip a single pair of matching surrounding quotes (legacy behaviour so that
        // `status == "success"` works as expected). Do not do this on the raw resolved
        // value — it applies only to quote-wrapped literal right-hand sides.
        val expectedValue = rightResolved
            .removeSurrounding("'")
            .removeSurrounding("\"")

        when (parsed.operator) {
            "==" -> actualValue.toString() == expectedValue
            "!=" -> actualValue.toString() != expectedValue
            ">", "<", ">=", "<=" -> {
                val actualNum = actualValue.toString().toDoubleOrNull()
                val expectedNum = expectedValue.toDoubleOrNull()
                if (actualNum == null || expectedNum == null) {
                    logger.warn {
                        "Non-numeric comparison in condition '$condition': " +
                            "actual='$actualValue', expected='$expectedValue'"
                    }
                    false
                } else when (parsed.operator) {
                    ">" -> actualNum > expectedNum
                    "<" -> actualNum < expectedNum
                    ">=" -> actualNum >= expectedNum
                    "<=" -> actualNum <= expectedNum
                    else -> false
                }
            }
            "contains_cs" -> actualValue.toString().contains(expectedValue, ignoreCase = false)
            "contains_ci" -> actualValue.toString().contains(expectedValue, ignoreCase = true)
            "contains" -> actualValue.toString().contains(expectedValue, ignoreCase = legacyContainsIsCaseInsensitive())
            else -> {
                logger.warn { "Unknown operator '${parsed.operator}' in workflow condition: '$condition'" }
                false
            }
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to evaluate workflow condition: '$condition'" }
        false
    }
}

/**
 * Split [template] on top-level occurrences of [op] (must be `&&` or `||`),
 * respecting single/double-quoted string literals.
 *
 * Returns the trimmed pieces in order; returns a single-element list when [op]
 * doesn't appear at the top level (the caller uses `result.size > 1` as the
 * "this is a compound condition" signal).
 *
 * Splits only on whitespace-bounded operator occurrences (`\s+OP\s+`), so
 * substrings like `URL_OR_TOKEN || OPTION_2` only split at the `||` between
 * tokens — not on `||` glued to identifier characters.
 */
private fun splitOnLogicalOperator(template: String, op: String): List<String> {
    require(op == "&&" || op == "||") { "Only && and || are supported boolean operators" }
    val pieces = mutableListOf<String>()
    val sb = StringBuilder()
    val opLen = op.length
    var i = 0
    var quote: Char? = null
    while (i < template.length) {
        val c = template[i]
        when {
            quote != null -> {
                sb.append(c)
                if (c == quote) quote = null
                i++
            }
            c == '\'' || c == '"' -> {
                quote = c
                sb.append(c)
                i++
            }
            c.isWhitespace() -> {
                // Consume the whitespace run, then check whether the next token is the operator
                // followed by another whitespace run — i.e. a `\s+OP\s+` sequence at top level.
                var j = i
                while (j < template.length && template[j].isWhitespace()) j++
                val opMatches = j + opLen <= template.length &&
                    template.substring(j, j + opLen) == op &&
                    (j + opLen >= template.length || template[j + opLen].isWhitespace())
                if (opMatches) {
                    pieces.add(sb.toString().trim())
                    sb.clear()
                    i = j + opLen
                    while (i < template.length && template[i].isWhitespace()) i++
                } else {
                    // Preserve the whitespace verbatim so resolveTemplateExpr sees the
                    // template exactly as the author wrote it.
                    sb.append(c)
                    i++
                }
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    pieces.add(sb.toString().trim())
    return pieces
}
