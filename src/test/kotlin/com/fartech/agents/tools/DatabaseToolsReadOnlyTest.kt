package com.fartech.agents.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the [DatabaseTools.isReadOnlySql] allow/deny surface, specifically the
 * 2026-04 follow-up hardening that strips SQL comments before inspecting the
 * leading keyword and rejects multi-statement SQL.
 *
 * Motivation: under BRAIDRUN_DB_READONLY=true the LLM is given a "read-only"
 * lane that the tool layer enforces. A prior implementation looked only at
 * the first-line prefix — an LLM prepending a block comment (hiding a
 * DESTRUCTIVE statement) could drift past that check on database drivers
 * that accept multi-statement queries. These tests pin the new behaviour.
 */
class DatabaseToolsReadOnlyTest {

    private val tools = DatabaseTools

    @Test
    fun `plain SELECT is read-only`() {
        assertTrue(tools.isReadOnlySql("SELECT * FROM users"))
    }

    @Test
    fun `leading whitespace and newlines are ignored`() {
        assertTrue(tools.isReadOnlySql("   \n  SELECT 1  "))
    }

    @Test
    fun `lowercase SELECT accepted`() {
        assertTrue(tools.isReadOnlySql("select * from x"))
    }

    @Test
    fun `WITH CTE is read-only`() {
        assertTrue(tools.isReadOnlySql("WITH cte AS (SELECT 1) SELECT * FROM cte"))
    }

    @Test
    fun `bare DROP is rejected`() {
        assertFalse(tools.isReadOnlySql("DROP TABLE users"))
    }

    @Test
    fun `block comment hiding DROP is rejected`() {
        // The comment strips to empty; the remaining text `DROP TABLE users`
        // does not begin with an allowed keyword.
        assertFalse(tools.isReadOnlySql("/* SELECT */ DROP TABLE users"))
    }

    @Test
    fun `line comment hiding DROP is rejected`() {
        assertFalse(tools.isReadOnlySql("-- SELECT\nDROP TABLE users"))
    }

    @Test
    fun `multi-statement with trailing DROP is rejected`() {
        // Drivers like MySQL with allowMultiQueries=true execute both; reject
        // outright in read-only mode to avoid surprising the operator.
        assertFalse(tools.isReadOnlySql("SELECT 1; DROP TABLE users"))
    }

    @Test
    fun `trailing semicolon alone is accepted`() {
        assertTrue(tools.isReadOnlySql("SELECT 1;"))
        assertTrue(tools.isReadOnlySql("SELECT 1;   "))
    }

    @Test
    fun `semicolon inside quoted string does not trigger multi-statement rejection`() {
        // A quoted literal containing a semicolon is part of the single
        // statement, not a separator.
        assertTrue(tools.isReadOnlySql("SELECT ';' AS col"))
        assertTrue(tools.isReadOnlySql("SELECT \"a;b\" FROM t"))
    }

    @Test
    fun `empty or whitespace-only SQL is rejected`() {
        assertFalse(tools.isReadOnlySql(""))
        assertFalse(tools.isReadOnlySql("   \n  "))
    }

    @Test
    fun `comment-only SQL is rejected`() {
        assertFalse(tools.isReadOnlySql("-- just a comment"))
        assertFalse(tools.isReadOnlySql("/* only a comment */"))
    }

    @Test
    fun `nested block comments are handled`() {
        // Defensive: not SQL-standard but we allow the parser to unwind them
        // rather than getting stuck reading the inner `*/` as the close of
        // the outer.
        val sql = "/* outer /* inner */ still outer */ SELECT 1"
        assertTrue(tools.isReadOnlySql(sql))
    }

    // ---------------------------------------------------------------------
    // Phase 9 (2026-05): string-aware comment stripping
    //
    // Pre-Phase-9 the comment stripper did NOT track quoted string literals,
    // so an unmatched `/*` inside a string would cause everything after it —
    // including any `;` that `containsStatementSeparator` relies on — to be
    // treated as a block comment and stripped. The trimmed leftover started
    // with `SELECT` (or whatever appeared before the literal) and passed the
    // read-only gate, while the actual SQL handed to JDBC included the
    // multi-statement payload. Drivers with `allowMultiQueries=true` would
    // happily run the trailing `DROP TABLE …`.
    // ---------------------------------------------------------------------

    @Test
    fun `unmatched block comment marker inside single-quoted literal cannot mask trailing DROP`() {
        // Pre-Phase-9: stripped to "SELECT '" → starts with SELECT → ALLOWED.
        // Post-Phase-9: literal preserved, ";" outside quotes → REJECTED.
        val sql = "SELECT '/*' UNION ALL SELECT 1; DROP TABLE x"
        assertFalse(
            tools.isReadOnlySql(sql),
            "Read-only gate must reject multi-statement SQL even when an unmatched /* sits inside a string literal"
        )
    }

    @Test
    fun `unmatched block comment marker inside double-quoted identifier cannot mask trailing DROP`() {
        val sql = "SELECT \"/*\" FROM t; DROP TABLE x"
        assertFalse(tools.isReadOnlySql(sql))
    }

    @Test
    fun `block comment inside string is preserved verbatim`() {
        // The literal `/* not a comment */` is just a column value, not a comment.
        // Stripping it would corrupt the SQL passed downstream.
        assertTrue(tools.isReadOnlySql("SELECT '/* not a comment */' AS col"))
    }

    @Test
    fun `line comment marker inside string is preserved verbatim`() {
        assertTrue(tools.isReadOnlySql("SELECT '-- not a comment' AS col"))
    }

    @Test
    fun `doubled single-quote escape inside literal does not break the closing-quote scan`() {
        // SQL escape: '' inside a '...' literal is a literal single quote.
        // The stripper must not exit the literal on the first '.
        assertTrue(tools.isReadOnlySql("SELECT 'O''Brien' AS name"))
    }
}
