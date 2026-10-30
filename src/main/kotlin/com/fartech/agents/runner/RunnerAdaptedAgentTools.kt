package com.fartech.agents.runner

import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.tools.*
import com.fartech.agents.runner.document.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import java.util.UUID

/** Host-only factories: requested groups never select an ambient library file/network adapter. */
internal object RunnerAdaptedAgentTools {
    val GROUPS = setOf("web", "knowledge_memory", "csv", "image_processing", "pdf", "office", "word", "excel", "powerpoint")

    @Suppress("DEPRECATION")
    fun create(
        requested: Set<String>, capabilities: Set<String>, parameters: List<ConfigurationParameter>,
        files: () -> ToolFileAccess, webHttp: () -> HttpAccess,
        guard: () -> Unit, redact: (String) -> String,
    ): Map<String, List<ToolSet>> {
        guard()
        return requested.filter { it in GROUPS }.associateWith { group ->
            require("tool.$group.v1" in capabilities) { "Runner tool adapter was not negotiated: $group" }
            val access = files()
            val documents by lazy { DocumentToolIO(access, DocumentToolLimits.RUNNER, authorityGuard = guard) }
            fun word(): List<ToolSet> = listOf(RunnerWordTools(documents), RunnerWordAdvancedTools(documents), RunnerWordEnhancedTools(documents))
            fun excel(): List<ToolSet> = listOf(RunnerExcelTools(documents), RunnerExcelAdvancedTools(documents), RunnerExcelEnhancedTools(documents))
            fun powerpoint(): List<ToolSet> = listOf(RunnerPowerPointTools(documents), RunnerPowerPointAdvancedTools(documents), RunnerPowerPointEnhancedTools(documents))
            when (group) {
                "pdf" -> listOf(RunnerPDFTools(documents))
                "office" -> word() + excel() + powerpoint() + CSVTools(access, guard)
                "word" -> if ("office" in requested) emptyList() else word()
                "excel" -> if ("office" in requested) emptyList() else excel()
                "powerpoint" -> if ("office" in requested) emptyList() else powerpoint()
                "csv" -> if ("office" in requested) emptyList() else listOf(CSVTools(access, guard))
                else -> listOf(when (group) {
                    "web" -> WebTools(webHttp(), parameters, access, redact)
                    // A new tool instance gets a fresh namespace within its step's task directory.
                    // Concurrent invocations cannot share stores or reuse a workflow-supplied namespace.
                    "knowledge_memory" -> KnowledgeMemoryTools(parameters, KnowledgeMemoryTools.ConfinedStorage(
                        access, "agent-memory/${UUID.randomUUID()}", guard))
                    "image_processing" -> ImageProcessingToolSet(access, guard)
                    else -> error("Unimplemented Runner tool group")
                })
            }
        }
    }
}
