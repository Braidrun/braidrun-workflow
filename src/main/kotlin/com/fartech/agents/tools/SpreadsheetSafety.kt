package com.fartech.agents.tools

/**
 * CSV / spreadsheet injection defense.
 *
 * When an LLM-controlled string starts with `=`, `+`, `-`, `@`, `\t`, or `\r` and is
 * later opened in Excel / LibreOffice Calc / Google Sheets, those applications will
 * interpret the cell as a *formula*. That has been weaponized to exfiltrate data via
 * `=HYPERLINK(...)`, run shell commands in some older Excel versions, and stage
 * further attacks against recipients of the generated file. Example:
 *
 *     cell content: "=IMPORTXML(...)"
 *
 * This helper rewrites the cell to have a leading `'` (single quote) — a well-known
 * Excel convention that renders the remainder as a literal string. The behavior is
 * idempotent (already-prefixed content is left unchanged) and is a no-op for values
 * that don't start with a dangerous sigil.
 *
 * Apply this to every **string** cell written to .csv / .xlsx / .xls / .ods output
 * where the content originates from untrusted input (LLM, user upload, scraped web
 * content). Numbers, dates, and booleans parsed into typed cells don't need it.
 */
internal object SpreadsheetSafety {
    // Set of leading characters that Excel / Sheets treat as a formula trigger.
    // `\t` and `\r` are here because Excel trims them and re-evaluates the trimmed
    // content, so `"\t=HYPERLINK(...)"` still fires as a formula.
    private val DANGEROUS_LEAD_CHARS: Set<Char> = setOf('=', '+', '-', '@', '\t', '\r')

    fun escapeFormula(value: String): String {
        if (value.isEmpty()) return value
        val head = value[0]
        if (head !in DANGEROUS_LEAD_CHARS) return value
        // Don't double-escape if caller already prefixed.
        if (head == '\'') return value
        return "'$value"
    }
}
