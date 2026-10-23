package com.fartech.agents.runner

import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.workflow.AgentDefinition
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Static skills are frozen bundle assets. Loading them never discovers operator HOME,
 * downloads/upgrades a registry package or auto-starts a bundled MCP/handler process.
 * Hook launch and interactive MCP have separate supervised host seams/capability gates.
 */
object RunnerSkillsPolicy {
    fun normalize(agent: AgentDefinition, root: Path): AgentDefinition {
        val parameters = agent.resolveParameters()
        val explicit = parameters["skills_config"]
        val tools = parameters["tool_set"] as? JsonArray
        if (explicit == null && tools?.any { it.jsonPrimitive.content == "skill_tools" } != true) return agent
        val config = explicit?.let { runnerJson.decodeFromJsonElement(SkillsConfiguration.serializer(), it) } ?: SkillsConfiguration()
        require(!config.autoSearchDownload && !config.autoUpgrade) { "Runner skill registry mutation requires separate admission" }
        val scoped = config.copy(skillsPath = root.resolve("skills").toString(), additionalSkillPaths = emptyList(),
            scanStandardPaths = false, autoSearchDownload = false, autoUpgrade = false, hookScriptExecutionEnabled = false,
            workspaceDir = null, hookContextConfig = emptyMap())
        return agent.copy(overrides = agent.overrides + ("skills_config" to runnerJson.encodeToJsonElement(SkillsConfiguration.serializer(), scoped)))
    }
}
