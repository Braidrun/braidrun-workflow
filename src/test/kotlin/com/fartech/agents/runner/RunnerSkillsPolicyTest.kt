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

    @Test fun `agent_based orchestrators get the same bundle-scoped skills at the root and inside a state machine`(@TempDir root: Path) {
        // A preset orchestrator keeps the preset's skills_config (standard-path scan, hook scripts on), which Runner
        // admission refuses; scoped like a workflow agent it is admitted.
        val ambient = SkillsConfiguration(scanStandardPaths = true, hookScriptExecutionEnabled = true)
        val orchestrator = AgentDefinition(overrides = mapOf("skills_config" to runnerJson.encodeToJsonElement(SkillsConfiguration.serializer(), ambient),
            "openai_api_key" to JsonPrimitive("credential:model@1")),
            llm = LLMConfiguration(model = "gpt-4o-mini", provider = "openai"))
        val agentBased = AgentBasedConfig(orchestrator = orchestrator, participants = listOf("worker"), goal = "go", maxSteps = 4)
        val machine = StateMachineConfig(states = mapOf("plan" to StateDefinition(name = "plan", stepConfig = StateStepConfig(agentBased = agentBased))), initialState = "plan", finalStates = listOf("plan"))
        val steps = listOf(WorkflowStep("orchestrate", agentBased = agentBased), WorkflowStep("machine", stateMachine = machine))
        val scoped = steps.map { FrozenWorkflowBundle.scopeOrchestratorSkills(it, root) }
        fun config(agent: AgentDefinition) = runnerJson.decodeFromJsonElement(SkillsConfiguration.serializer(), agent.resolveParameters().getValue("skills_config"))
        for (agent in listOf(scoped[0].agentBased!!.orchestrator, scoped[1].stateMachine!!.states.getValue("plan").stepConfig!!.agentBased!!.orchestrator)) {
            val config = config(agent)
            assertEquals(root.resolve("skills").toString(), config.skillsPath)
            assertFalse(config.scanStandardPaths); assertFalse(config.hookScriptExecutionEnabled)
        }
        val worker = AgentDefinition(llm = LLMConfiguration(model = "gpt-4o-mini", provider = "openai"),
            overrides = mapOf("openai_api_key" to JsonPrimitive("credential:model@1")))
        val capabilities = RunnerHostSession.CAPABILITIES
        fun inspect(workflowSteps: List<WorkflowStep>) = RunnerAdmission(NullWorkflowResolver, capabilities)
            .inspect(WorkflowDefinition("orchestrated-skills", agents = mapOf("worker" to worker), workflow = workflowSteps))
        assertTrue(assertFails { inspect(steps.take(1)) }.message.orEmpty().contains("Skill side effects"))
        inspect(scoped)
    }
}
