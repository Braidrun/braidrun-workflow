package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
