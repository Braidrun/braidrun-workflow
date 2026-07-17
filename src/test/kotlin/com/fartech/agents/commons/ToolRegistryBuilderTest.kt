package com.fartech.agents.commons

import ai.koog.agents.core.tools.reflect.ToolFromCallable
import com.fartech.agents.tools.ExternalAgentContext
import com.fartech.agents.tools.ExternalAgentTools
import com.fartech.agents.tools.SubAgentTools
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolRegistryBuilderTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `skills subsystem disabled follows disabled skills config`() {
        val params = listOf(
            ConfigurationParameter(
                "skills_config",
                json.encodeToJsonElement(
                    SkillsConfiguration.serializer(),
                    SkillsConfiguration(enabled = false)
                )
            )
        )

        assertTrue(skillsSubsystemDisabled(params))
        assertNull(createSkillManager(params))
    }

    @Test
    fun `skills subsystem disabled follows disable skills parameter`() {
        val params = listOf(ConfigurationParameter("disable_skills", JsonPrimitive("true")))

        assertTrue(skillsSubsystemDisabled(params))
    }

    @Test
    fun `skills subsystem stays enabled by default`() {
        assertFalse(skillsSubsystemDisabled(emptyList()))
    }

    @Test
    fun `parseToolSet does not implicitly register skill tools when disabled`() {
        val params = disabledSkillsParams()
        val registry = parseToolSet(
            parameters = params,
            httpAccess = HttpAccess(),
            tools = listOf("exit")
        )

        assertFalse(registry.tools.any { it.name == "searchSkills" })
        assertFalse(registry.tools.any { it.name == "useSkill" })
    }

    @Test
    fun `parseExactToolSet does not register explicit skill tools when disabled`() {
        val params = disabledSkillsParams()
        val registry = parseExactToolSet(
            parameters = params,
            httpAccess = HttpAccess(),
            tools = listOf("skill_tools")
        )

        assertFalse(registry.tools.any { it.name == "searchSkills" })
        assertFalse(registry.tools.any { it.name == "useSkill" })
    }

    @Test
    fun `parseToolSet uses injected executor for external agents`() = runBlocking {
        val executor = CapturingExecutor()
        val registry = parseToolSet(
            parameters = disabledSkillsParams() + listOf(
                ConfigurationParameter("anthropic_api_key", JsonPrimitive("sk-test"))
            ),
            httpAccess = HttpAccess(),
            tools = listOf("external_agent"),
            externalAgentExecutor = executor
        )

        val reflectedTool = registry.getTool("runClaudeCodeSubAgent") as ToolFromCallable<*>
        val externalTools = reflectedTool.thisRef as ExternalAgentTools
        externalTools.runClaudeCodeSubAgent(ExternalAgentContext(prompt = "Say ok"))

        assertNotNull(executor.lastRequest)
    }

    @Test
    fun `parseToolSet propagates injected executor into nested sub agents`() {
        val executor = CapturingExecutor()
        val registry = parseToolSet(
            parameters = disabledSkillsParams(),
            httpAccess = HttpAccess(),
            tools = listOf("sub_agent"),
            externalAgentExecutor = executor
        )

        val reflectedTool = registry.getTool("runSubAgent") as ToolFromCallable<*>
        val subAgentTools = reflectedTool.thisRef as SubAgentTools

        assertSame(executor, subAgentTools.externalAgentExecutor)
    }

    private class CapturingExecutor : SubprocessExecutor {
        var lastRequest: SubprocessExecutor.ExecRequest? = null

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            lastRequest = request
            return SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = """{"type":"result","subtype":"success","result":"ok"}""",
                stderr = "",
                durationMs = 1
            )
        }
    }

    private fun disabledSkillsParams(): List<ConfigurationParameter> {
        return listOf(
            ConfigurationParameter(
                "skills_config",
                json.encodeToJsonElement(
                    SkillsConfiguration.serializer(),
                    SkillsConfiguration(enabled = false)
                )
            )
        )
    }
}
