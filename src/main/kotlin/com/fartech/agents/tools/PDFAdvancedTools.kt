package com.fartech.agents.tools

import ai.koog.agents.core.tools.reflect.ToolSet

/**
 * Empty deprecation shim retained for backwards compatibility.
 *
 * Every `@Tool` method that used to live on `PDFAdvancedTools` was merged into
 * [PDFTools] in Phase 6 of the 2026-04 audit follow-up:
 *   `extractPagesRange`, `rotatePage`, `deletePages`, `addImageToPage`,
 *   `fillFormFields`, `encryptPdf`, `decryptPdf`, `setMetadata`,
 *   `addHeaderFooterText`, `addTextBoxToPage`
 *
 * This object is intentionally empty so existing Kotlin callers such as
 * `tools(PDFAdvancedTools)` still compile, but the call registers **zero
 * tools** with koog. Update sites are expected to switch to `PDFTools`.
 *
 * The Kotlin class is scheduled for removal once every in-tree caller has
 * migrated; it remains for at least one release cycle.
 */
@Deprecated(
    message = "Merged into PDFTools in Phase 6. Call tools(PDFTools) instead; every @Tool method name and signature is preserved.",
    replaceWith = ReplaceWith("PDFTools", "com.fartech.agents.tools.PDFTools"),
    level = DeprecationLevel.WARNING
)
object PDFAdvancedTools : ToolSet
