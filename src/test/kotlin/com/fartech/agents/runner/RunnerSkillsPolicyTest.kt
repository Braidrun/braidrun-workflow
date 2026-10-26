package com.fartech.agents.runner

import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RunnerSkillsPolicyTest {
    @Test fun `frozen skills retain selection while ambient discovery hooks and upgrades cannot widen scope`(@TempDir root: Path) {
        val original = SkillsConfiguration(skillsPath = "/operator/home/skills", additionalSkillPaths = listOf("/other-user/skills"),
            scanStandardPaths = true, enabledSkills = listOf("selected"), skillWhitelistMode = true)
        val agent = AgentDefinition(tools = listOf("skill_tools"), overrides = mapOf("skills_config" to runnerJson.encodeToJsonElement(SkillsConfiguration.serializer(), original)))
        val normalized = RunnerSkillsPolicy.normalize(agent, root)
        val config = runnerJson.decodeFromJsonElement(SkillsConfiguration.serializer(), normalized.resolveParameters().getValue("skills_config"))
        assertEquals(root.resolve("skills").toString(), config.skillsPath); assertEquals(listOf("selected"), config.enabledSkills)
        assertFalse(config.scanStandardPaths); assertTrue(config.additionalSkillPaths.isEmpty()); assertFalse(config.hookScriptExecutionEnabled)
        val workflow = WorkflowDefinition("skills", agents = mapOf("agent" to normalized), workflow = listOf(WorkflowStep("load", agent = "agent", input = "Use selected static skill")))
        RunnerAdmission(NullWorkflowResolver, setOf("workflow.full.v1", "step.agent.v1", "model.http.v1", "tool.skill_tools.v1", "skill.text.v1")).inspect(workflow)
        assertFailsWith<IllegalArgumentException> { RunnerSkillsPolicy.normalize(agent.copy(overrides = mapOf("skills_config" to runnerJson.encodeToJsonElement(SkillsConfiguration.serializer(), original.copy(autoUpgrade = true)))), root) }
    }
}
