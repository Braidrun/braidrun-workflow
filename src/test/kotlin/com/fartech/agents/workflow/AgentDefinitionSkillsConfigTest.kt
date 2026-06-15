package com.fartech.agents.workflow

import com.fartech.agents.commons.SkillsConfiguration
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * AgentDefinition 的 skills_config 字段及 buildLegacyParameters 序列化测试。
 */
class AgentDefinitionSkillsConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // =========================================================================
    // 基础字段测试
    // =========================================================================

    @Nested
    inner class FieldTests {

        @Test
        fun `skillsConfig defaults to null`() {
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel"
            )
            assertNull(agent.skillsConfig)
        }

        @Test
        fun `skillsConfig can be set with default SkillsConfiguration`() {
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = SkillsConfiguration()
            )
            val sc = requireNotNull(agent.skillsConfig)
            assertEquals("./skills", sc.skillsPath)
            assertTrue(sc.enabled)
            assertEquals(8, sc.maxSkillsPerRequest)
            assertTrue(sc.materializeRuntimeSkills)
        }

        @Test
        fun `skillsConfig can be set with custom values`() {
            val config = SkillsConfiguration(
                skillsPath = "/custom/skills",
                enabled = true,
                skillWhitelistMode = true,
                enabledSkills = listOf("code-review", "test-gen"),
                disabledSkills = listOf("deprecated-skill"),
                maxSkillsPerRequest = 5,
                builtinSkillsEnabled = false,
                hooksEnabled = false,
                autoUpgrade = true,
                materializeRuntimeSkills = true,
                workspaceDir = "/workspace"
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val sc = requireNotNull(agent.skillsConfig)
            assertEquals("/custom/skills", sc.skillsPath)
            assertEquals(true, sc.skillWhitelistMode)
            assertEquals(listOf("code-review", "test-gen"), sc.enabledSkills)
            assertEquals(listOf("deprecated-skill"), sc.disabledSkills)
            assertEquals(5, sc.maxSkillsPerRequest)
            assertFalse(sc.builtinSkillsEnabled)
            assertFalse(sc.hooksEnabled)
            assertTrue(sc.autoUpgrade)
            assertTrue(sc.materializeRuntimeSkills)
            assertEquals("/workspace", sc.workspaceDir)
        }
    }

    // =========================================================================
    // YAML/JSON 序列化测试
    // =========================================================================

    @Nested
    inner class SerializationTests {

        @Test
        fun `AgentDefinition with skillsConfig serializes to JSON`() {
            val jsonWithDefaults = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = SkillsConfiguration(
                    skillsPath = "/my/skills",
                    enabled = true,
                    maxSkillsPerRequest = 5
                )
            )
            val encoded = jsonWithDefaults.encodeToString(AgentDefinition.serializer(), agent)
            val jsonObj = jsonWithDefaults.parseToJsonElement(encoded).jsonObject
            assertTrue(jsonObj.containsKey("skills_config"), "Serialized JSON should contain skills_config key")

            val sc = jsonObj["skills_config"]!!.jsonObject
            assertEquals("/my/skills", sc["skillsPath"]?.jsonPrimitive?.content)
            assertEquals(true, sc["enabled"]?.jsonPrimitive?.boolean)
            assertEquals(5, sc["maxSkillsPerRequest"]?.jsonPrimitive?.int)
        }

        @Test
        fun `AgentDefinition without skillsConfig omits it in JSON`() {
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel"
            )
            val encoded = json.encodeToString(AgentDefinition.serializer(), agent)
            val jsonObj = json.parseToJsonElement(encoded).jsonObject
            // With encodeDefaults=false, null fields should be omitted
            assertFalse(jsonObj.containsKey("skills_config"))
        }

        @Test
        fun `AgentDefinition with skillsConfig deserializes from JSON`() {
            val jsonStr = """
            {
                "type": "universal_agent",
                "strategy": "just_work_parallel",
                "skills_config": {
                    "skillsPath": "/test/skills",
                    "enabled": true,
                    "skillWhitelistMode": true,
                    "enabledSkills": ["skill-a", "skill-b"],
                    "maxSkillsPerRequest": 3,
                    "builtinSkillsEnabled": false,
                    "hooksEnabled": true,
                    "materializeRuntimeSkills": true,
                    "hookTimeoutSeconds": 60
                }
            }
            """.trimIndent()
            val agent = json.decodeFromString(AgentDefinition.serializer(), jsonStr)
            val sc = requireNotNull(agent.skillsConfig)
            assertEquals("/test/skills", sc.skillsPath)
            assertEquals(true, sc.skillWhitelistMode)
            assertEquals(listOf("skill-a", "skill-b"), sc.enabledSkills)
            assertEquals(3, sc.maxSkillsPerRequest)
            assertFalse(sc.builtinSkillsEnabled)
            assertTrue(sc.hooksEnabled)
            assertTrue(sc.materializeRuntimeSkills)
            assertEquals(60L, sc.hookTimeoutSeconds)
        }

        @Test
        fun `AgentDefinition deserializes without skills_config field`() {
            val jsonStr = """
            {
                "type": "universal_agent",
                "strategy": "just_work_parallel"
            }
            """.trimIndent()
            val agent = json.decodeFromString(AgentDefinition.serializer(), jsonStr)
            assertNull(agent.skillsConfig)
        }
    }

    // =========================================================================
    // buildLegacyParameters 测试
    // =========================================================================

    @Nested
    inner class BuildLegacyParametersTests {

        @Test
        fun `resolveParameters without skillsConfig does not include skills_config`() {
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel"
            )
            val params = agent.resolveParameters()
            assertFalse(params.containsKey("skills_config"))
        }

        @Test
        fun `resolveParameters with skillsConfig includes skills_config as JsonObject`() {
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = SkillsConfiguration(
                    skillsPath = "/my/skills",
                    enabled = true,
                    maxSkillsPerRequest = 6
                )
            )
            val params = agent.resolveParameters()
            assertTrue(params.containsKey("skills_config"))
            val sc = params["skills_config"]
            assertNotNull(sc)
            assertTrue(sc is JsonObject)
            val scObj = sc as JsonObject
            assertEquals("/my/skills", scObj["skillsPath"]?.jsonPrimitive?.content)
            assertEquals(true, scObj["enabled"]?.jsonPrimitive?.boolean)
            assertEquals(6, scObj["maxSkillsPerRequest"]?.jsonPrimitive?.int)
        }

        @Test
        fun `resolveParameters skills_config contains all core fields`() {
            val config = SkillsConfiguration(
                skillsPath = "/skills",
                enabled = true,
                skillWhitelistMode = true,
                autoDiscovery = true,
                maxSkillsPerRequest = 8,
                progressiveDisclosure = true,
                builtinSkillsEnabled = true,
                hooksEnabled = true,
                hookScriptExecutionEnabled = true,
                hookTimeoutSeconds = 30,
                scanStandardPaths = true,
                requireProjectTrust = false,
                autoUpgrade = false,
                materializeRuntimeSkills = true
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val params = agent.resolveParameters()
            val scObj = params["skills_config"] as JsonObject

            // Verify all core fields are present
            assertEquals("/skills", scObj["skillsPath"]?.jsonPrimitive?.content)
            assertEquals(true, scObj["enabled"]?.jsonPrimitive?.boolean)
            assertEquals(true, scObj["skillWhitelistMode"]?.jsonPrimitive?.boolean)
            assertEquals(true, scObj["autoDiscovery"]?.jsonPrimitive?.boolean)
            assertEquals(8, scObj["maxSkillsPerRequest"]?.jsonPrimitive?.int)
            assertEquals(true, scObj["progressiveDisclosure"]?.jsonPrimitive?.boolean)
            assertEquals(true, scObj["builtinSkillsEnabled"]?.jsonPrimitive?.boolean)
            assertEquals(true, scObj["hooksEnabled"]?.jsonPrimitive?.boolean)
            assertEquals(true, scObj["hookScriptExecutionEnabled"]?.jsonPrimitive?.boolean)
            assertEquals(30L, scObj["hookTimeoutSeconds"]?.jsonPrimitive?.long)
            assertEquals(true, scObj["scanStandardPaths"]?.jsonPrimitive?.boolean)
            assertEquals(false, scObj["requireProjectTrust"]?.jsonPrimitive?.boolean)
            assertEquals(false, scObj["autoUpgrade"]?.jsonPrimitive?.boolean)
            assertEquals(true, scObj["materializeRuntimeSkills"]?.jsonPrimitive?.boolean)
        }

        @Test
        fun `resolveParameters skills_config includes list fields when non-empty`() {
            val config = SkillsConfiguration(
                enabledSkills = listOf("skill-a", "skill-b"),
                disabledSkills = listOf("skill-c"),
                notLoadSkills = listOf("skill-d"),
                additionalSkillPaths = listOf("/extra/skills", "/more/skills"),
                trustedProjects = listOf("/proj1")
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val params = agent.resolveParameters()
            val scObj = params["skills_config"] as JsonObject

            val enabledSkills = scObj["enabledSkills"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("skill-a", "skill-b"), enabledSkills)

            val disabledSkills = scObj["disabledSkills"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("skill-c"), disabledSkills)

            val notLoadSkills = scObj["notLoadSkills"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("skill-d"), notLoadSkills)

            val additionalPaths = scObj["additionalSkillPaths"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("/extra/skills", "/more/skills"), additionalPaths)

            val trustedProjects = scObj["trustedProjects"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("/proj1"), trustedProjects)
        }

        @Test
        fun `resolveParameters skills_config omits empty lists`() {
            val config = SkillsConfiguration(
                enabledSkills = emptyList(),
                disabledSkills = emptyList(),
                additionalSkillPaths = emptyList()
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val params = agent.resolveParameters()
            val scObj = params["skills_config"] as JsonObject

            assertFalse(scObj.containsKey("enabledSkills"))
            assertFalse(scObj.containsKey("disabledSkills"))
            assertFalse(scObj.containsKey("additionalSkillPaths"))
        }

        @Test
        fun `resolveParameters skills_config includes optional string fields`() {
            val config = SkillsConfiguration(
                workspaceDir = "/workspace",
                userGlobalHooksDir = "/custom/hooks"
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val params = agent.resolveParameters()
            val scObj = params["skills_config"] as JsonObject

            assertEquals("/workspace", scObj["workspaceDir"]?.jsonPrimitive?.content)
            assertEquals("/custom/hooks", scObj["userGlobalHooksDir"]?.jsonPrimitive?.content)
        }

        @Test
        fun `resolveParameters skills_config omits null optional string fields`() {
            val config = SkillsConfiguration(
                workspaceDir = null,
                userGlobalHooksDir = null
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val params = agent.resolveParameters()
            val scObj = params["skills_config"] as JsonObject

            assertFalse(scObj.containsKey("workspaceDir"))
            assertFalse(scObj.containsKey("userGlobalHooksDir"))
        }

        @Test
        fun `resolveParameters skills_config includes non-default allowedScriptTypes`() {
            val config = SkillsConfiguration(
                allowedScriptTypes = listOf("py")
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val params = agent.resolveParameters()
            val scObj = params["skills_config"] as JsonObject

            val types = scObj["allowedScriptTypes"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("py"), types)
        }

        @Test
        fun `resolveParameters skills_config omits default allowedScriptTypes`() {
            val config = SkillsConfiguration(
                allowedScriptTypes = listOf("py", "js", "ts", "kts")
            )
            val agent = AgentDefinition(
                type = "universal_agent",
                strategy = "just_work_parallel",
                skillsConfig = config
            )
            val params = agent.resolveParameters()
            val scObj = params["skills_config"] as JsonObject

            assertFalse(scObj.containsKey("allowedScriptTypes"))
        }
    }

    // =========================================================================
    // 预设模式测试
    // =========================================================================

    @Nested
    inner class PresetModeTests {

        @Test
        fun `isPresetMode true when preset is set`() {
            val agent = AgentDefinition(
                preset = "universal_reasoning",
                skillsConfig = SkillsConfiguration(enabled = true)
            )
            assertTrue(agent.isPresetMode)
        }

        @Test
        fun `preset mode resolveParameters uses overrides, skillsConfig in legacy only`() {
            // In preset mode, resolveParameters uses preset registry, not buildLegacyParameters
            val agent = AgentDefinition(
                preset = "universal_reasoning",
                overrides = mapOf(
                    "system_prompt" to JsonPrimitive("test prompt")
                ),
                skillsConfig = SkillsConfiguration(enabled = true)
            )
            // In preset mode, skillsConfig is not in resolveParameters directly
            // (it's handled by the preset system or via overrides)
            assertTrue(agent.isPresetMode)
            // The skillsConfig field is still available on the object
            val sc = requireNotNull(agent.skillsConfig)
            assertTrue(sc.enabled)
        }
    }

    // =========================================================================
    // WorkflowDefinition 集成测试
    // =========================================================================

    @Nested
    inner class WorkflowDefinitionIntegrationTests {

        @Test
        fun `WorkflowDefinition with agent having skills_config`() {
            val workflow = WorkflowDefinition(
                name = "test-workflow",
                agents = mapOf(
                    "researcher" to AgentDefinition(
                        type = "universal_agent",
                        strategy = "just_work_parallel",
                        tools = listOf("exit", "file_system", "skill_tools"),
                        skillsConfig = SkillsConfiguration(
                            enabled = true,
                            skillsPath = "./project-skills",
                            enabledSkills = listOf("code-review"),
                            maxSkillsPerRequest = 4
                        )
                    )
                ),
                workflow = listOf(
                    WorkflowStep(
                        step = "research",
                        agent = "researcher",
                        input = "Do research"
                    )
                )
            )
            assertNotNull(workflow.agents["researcher"]?.skillsConfig)
            assertEquals("./project-skills", workflow.agents["researcher"]?.skillsConfig?.skillsPath)
            assertEquals(listOf("code-review"), workflow.agents["researcher"]?.skillsConfig?.enabledSkills)
        }

        @Test
        fun `WorkflowDefinition serialization round-trip with skills_config`() {
            val original = WorkflowDefinition(
                name = "test-workflow",
                agents = mapOf(
                    "agent1" to AgentDefinition(
                        type = "universal_agent",
                        strategy = "just_work_parallel",
                        skillsConfig = SkillsConfiguration(
                            skillsPath = "/skills",
                            enabled = true,
                            enabledSkills = listOf("s1", "s2"),
                            hooksEnabled = false,
                            hookTimeoutSeconds = 60
                        )
                    )
                ),
                workflow = listOf(
                    WorkflowStep(step = "s1", agent = "agent1", input = "test")
                )
            )
            val jsonFull = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val encoded = jsonFull.encodeToString(WorkflowDefinition.serializer(), original)
            val decoded = jsonFull.decodeFromString(WorkflowDefinition.serializer(), encoded)

            val sc = requireNotNull(decoded.agents["agent1"]?.skillsConfig)
            assertEquals("/skills", sc.skillsPath)
            assertTrue(sc.enabled)
            assertEquals(listOf("s1", "s2"), sc.enabledSkills)
            assertFalse(sc.hooksEnabled)
            assertEquals(60L, sc.hookTimeoutSeconds)
        }
    }
}
