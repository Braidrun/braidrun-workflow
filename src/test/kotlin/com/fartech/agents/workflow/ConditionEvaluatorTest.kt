package com.fartech.agents.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConditionEvaluatorTest {

    @Test
    fun `resolveWorkflowConditionTemplates expands variable and step placeholders`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        context.setVariable("quality_threshold", "8")
        context.setStepOutput("review", "READY")

        val resolved = resolveWorkflowConditionTemplates(
            "quality_score >= {{var:quality_threshold}} && {{steps.review.output}}",
            context
        )

        assertEquals("quality_score >= 8 && READY", resolved)
    }

    @Test
    fun `evaluateWorkflowCondition supports variable placeholders in expected value`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        context.setVariable("quality_score", "9")
        context.setVariable("quality_threshold", "8")

        assertTrue(
            evaluateWorkflowCondition(
                "quality_score >= {{var:quality_threshold}}",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition supports dynamic left-hand variable names`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        context.setVariable("target_metric", "quality_score")
        context.setVariable("quality_score", "9")

        assertTrue(
            evaluateWorkflowCondition(
                "{{var:target_metric}} >= 8",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition can compare against step outputs when fallback is enabled`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        context.setStepOutput("quality_score", "9")
        context.setVariable("quality_threshold", "8")

        assertTrue(
            evaluateWorkflowCondition(
                "quality_score >= {{var:quality_threshold}}",
                context,
                fallbackToStepOutput = true
            )
        )
    }

    // ── Compound conditions (added 2026-04-26) ──────────────────────────
    // Pre-2026-04 the evaluator only matched a single comparison operator;
    // a condition like `a == 1 && b != ''` was parsed as `a == 1 && b != ''`
    // (everything after the FIRST operator was treated as the right-hand
    // value). The compound-operator support fixes the long-standing silent
    // bug that made `&&` / `||` always evaluate as a single comparison.

    @Test
    fun `evaluateWorkflowCondition supports && — all branches must be true`() {
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("token", "abc")
            setVariable("chat", "999")
            setVariable("url", "https://example.test/p/d/xyz/")
        }
        assertTrue(
            evaluateWorkflowCondition(
                "token != '' && chat != '' && url != ''",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition && short-circuits to false when any branch is false`() {
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("token", "abc")
            setVariable("chat", "")        // ← empty
            setVariable("url", "https://x")
        }
        assertFalse(
            evaluateWorkflowCondition(
                "token != '' && chat != '' && url != ''",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition supports — any true branch wins`() {
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("status", "FAILED")
            setVariable("severity", "high")
        }
        assertTrue(
            evaluateWorkflowCondition(
                "status == 'OK' || severity == 'high'",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition all branches false yields false`() {
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("status", "PENDING")
            setVariable("severity", "low")
        }
        assertFalse(
            evaluateWorkflowCondition(
                "status == 'OK' || severity == 'high'",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition && binds tighter than `() {
        // (a == 1 && b == 2) || c == 3
        // With a=9, b=2, c=3: left side false (a != 1), right side true → overall true.
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("a", "9")
            setVariable("b", "2")
            setVariable("c", "3")
        }
        assertTrue(
            evaluateWorkflowCondition(
                "a == 1 && b == 2 || c == 3",
                context
            )
        )
        // Same template with c=99 → both sides false → overall false.
        context.setVariable("c", "99")
        assertFalse(
            evaluateWorkflowCondition(
                "a == 1 && b == 2 || c == 3",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition does not split && inside quoted literals`() {
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("name", "alpha && beta")
        }
        // The right-hand value contains a literal `&&` — must not be split.
        assertTrue(
            evaluateWorkflowCondition(
                "name == 'alpha && beta'",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition does not split || inside double-quoted literals`() {
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("expr", "x || y")
        }
        assertTrue(
            evaluateWorkflowCondition(
                "expr == \"x || y\"",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition handles long compound and the original notify_user pattern`() {
        // Reproducer for the regression that masked the pwa-app-builder execution:
        // `telegram_bot_token != '' && telegram_chat_id != '' && pwa_url != ''`
        // with all three empty must evaluate to FALSE (was TRUE pre-fix).
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("telegram_bot_token", "")
            setVariable("telegram_chat_id", "")
            setVariable("pwa_url", "")
        }
        assertFalse(
            evaluateWorkflowCondition(
                "telegram_bot_token != '' && telegram_chat_id != '' && pwa_url != ''",
                context
            )
        )
        // Filling in all three flips it to true.
        context.setVariable("telegram_bot_token", "tok")
        context.setVariable("telegram_chat_id", "999")
        context.setVariable("pwa_url", "https://x/p/d/abc/")
        assertTrue(
            evaluateWorkflowCondition(
                "telegram_bot_token != '' && telegram_chat_id != '' && pwa_url != ''",
                context
            )
        )
    }

    @Test
    fun `evaluateWorkflowCondition single-comparison behaviour preserved`() {
        val context = WorkflowExecutionContext("wf", "exec-1").apply {
            setVariable("status", "ok")
        }
        assertTrue(evaluateWorkflowCondition("status == 'ok'", context))
        assertFalse(evaluateWorkflowCondition("status == 'fail'", context))
    }

    @Test
    fun `evaluateWorkflowCondition treats empty leaves as false`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        // Trailing && with nothing after → second leaf is empty → second
        // leaf returns false (no-op parseConditionTemplate) → overall false.
        assertFalse(
            evaluateWorkflowCondition(
                "1 == 1 && ",
                context
            )
        )
    }
}
