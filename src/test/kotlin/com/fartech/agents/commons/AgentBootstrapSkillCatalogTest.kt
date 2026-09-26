package com.fartech.agents.commons

import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.message.Message
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `<available_skills>` catalog tells the model to call `useSkill`, so it must only reach
 * the system prompt when that tool is actually registered.
 */
class AgentBootstrapSkillCatalogTest {

    private val json = Json { encodeDefaults = false }

    private fun skillParams(tempDir: Path): List<ConfigurationParameter> {
        val skillDir = tempDir.resolve("catalog-skill")
        Files.createDirectories(skillDir)
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            "---\nname: catalog-skill\ndescription: Catalog skill\n---\nInstructions"
        )
        val config = SkillsConfiguration(
            skillsPath = tempDir.toString(),
            scanStandardPaths = false,
            builtinSkillsEnabled = false
        )
        return listOf(
            ConfigurationParameter("skills_config", json.encodeToJsonElement(SkillsConfiguration.serializer(), config))
        )
    }

    @Test
    fun `createSkillManager returns null when disable_skills is set`(@TempDir tempDir: Path) {
        val enabled = skillParams(tempDir)
        assertNotNull(createSkillManager(enabled))

        assertNull(createSkillManager(enabled + ConfigurationParameter("disable_skills", JsonPrimitive(true))))
        assertNull(createSkillManager(enabled + ConfigurationParameter("disable_skills", JsonPrimitive("true"))))
    }

    @Test
    fun `exact tool set without skill_tools gets no catalog`(@TempDir tempDir: Path) {
        val params = skillParams(tempDir)
        val manager = createSkillManager(params)!!
        val registry = parseExactToolSet(params, HttpAccess(), tools = listOf("exit"), skillManager = manager)

        assertFalse(registry.hasSkillActivationTool())
        val prompt = buildSkillAwareSystemPrompt(
            skillManager = manager,
            systemPrompt = "OPERATOR PROMPT",
            envInfo = "ENV INFO",
            skillToolsRegistered = registry.hasSkillActivationTool(),
        )

        assertFalse(prompt.contains("<available_skills>"), prompt)
        assertFalse(prompt.contains("useSkill"), prompt)
        assertTrue(prompt.startsWith("OPERATOR PROMPT"), prompt)
        assertTrue(prompt.endsWith("ENV INFO"), prompt)
    }

    @Test
    fun `registered skill_tools keep the catalog`(@TempDir tempDir: Path) {
        val params = skillParams(tempDir)
        val manager = createSkillManager(params)!!
        val registry = parseExactToolSet(params, HttpAccess(), tools = listOf("skill_tools"), skillManager = manager)

        // Guards the constant against a rename of SkillTools.useSkill.
        assertNotNull(registry.getToolOrNull(USE_SKILL_TOOL_NAME))
        val prompt = buildSkillAwareSystemPrompt(
            skillManager = manager,
            systemPrompt = "OPERATOR PROMPT",
            envInfo = "ENV INFO",
            skillToolsRegistered = registry.hasSkillActivationTool(),
        )

        assertTrue(prompt.contains("<available_skills>"), prompt)
        assertTrue(prompt.contains("catalog-skill"), prompt)
        assertTrue(prompt.indexOf("<available_skills>") < prompt.indexOf("OPERATOR PROMPT"))
    }

    @Test
    fun `buildAgent system prompt carries the catalog only when useSkill is registered`(@TempDir tempDir: Path) =
        runBlocking {
            val params = skillParams(tempDir) + listOf(
                ConfigurationParameter(
                    "llm_config",
                    json.encodeToJsonElement(
                        LLModelGroupConfig.serializer(),
                        LLModelGroupConfig(models = listOf(DEFAULT_LLM_MODEL_CONFIG))
                    )
                ),
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-test-not-used")),
            )
            val manager = createSkillManager(params)!!

            suspend fun systemPromptWith(tools: List<String>): String {
                val registry = parseExactToolSet(params, HttpAccess(), tools = tools, skillManager = manager)
                val agent = buildAgent<String, String>(
                    httpAccess = HttpAccess(),
                    parameters = params,
                    systemPrompt = "OPERATOR PROMPT",
                    toolRegistry = registry,
                    skillManager = manager,
                ) { _, _, _ ->
                    strategy<String, String>("catalog-test") { nodeStart then nodeFinish }
                }
                return agent.agentConfig.prompt.messages.first { it is Message.System }.textContent()
            }

            val withoutSkillTools = systemPromptWith(listOf("exit"))
            assertFalse(withoutSkillTools.contains("<available_skills>"), withoutSkillTools)
            assertTrue(withoutSkillTools.startsWith("OPERATOR PROMPT"), withoutSkillTools)

            val withSkillTools = systemPromptWith(listOf("skill_tools"))
            assertTrue(withSkillTools.contains("<available_skills>"), withSkillTools)
            assertTrue(withSkillTools.contains("catalog-skill"), withSkillTools)
        }

    @Test
    fun `disable_skills leaves no manager for the catalog and no skill tools`(@TempDir tempDir: Path) {
        val params = skillParams(tempDir) + ConfigurationParameter("disable_skills", JsonPrimitive(true))

        // buildAgent only emits a catalog through a SkillManager; its default is this call.
        assertNull(createSkillManager(params))
        // skill_tools is requested explicitly, so only disable_skills can keep useSkill out.
        val registry = parseToolSet(params, HttpAccess(), tools = listOf("exit", "skill_tools"))
        assertFalse(registry.hasSkillActivationTool())

        // Positive control: the same request without disable_skills registers useSkill.
        val enabledRegistry = parseToolSet(skillParams(tempDir), HttpAccess(), tools = listOf("exit", "skill_tools"))
        assertTrue(enabledRegistry.hasSkillActivationTool())
    }
}
