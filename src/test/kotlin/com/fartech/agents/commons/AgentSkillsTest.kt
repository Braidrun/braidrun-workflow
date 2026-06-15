package com.fartech.agents.commons

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentSkillsTest {

    // =========================================================================
    // ClaudeSkill
    // =========================================================================

    @Nested
    inner class ClaudeSkillTest {

        @Test
        fun `blank name throws`() {
            assertThrows<IllegalArgumentException> {
                ClaudeSkill(name = "", description = "desc", content = "body")
            }
        }

        @Test
        fun `blank description throws`() {
            assertThrows<IllegalArgumentException> {
                ClaudeSkill(name = "test", description = "", content = "body")
            }
        }

        @Test
        fun `toDiscoverySummary contains name and description`() {
            val skill = ClaudeSkill(name = "MySkill", description = "Does things", content = "body")
            val summary = skill.toDiscoverySummary()
            assertTrue(summary.contains("MySkill"))
            assertTrue(summary.contains("Does things"))
            assertEquals("**MySkill**: Does things", summary)
        }

        @Test
        fun `toFullContent contains skill XML tags`() {
            val skill = ClaudeSkill(name = "TestSkill", description = "desc", content = "Skill body content")
            val full = skill.toFullContent()
            assertTrue(full.contains("<skill_content name=\"TestSkill\">"))
            assertTrue(full.contains("</skill_content>"))
            assertTrue(full.contains("# Skill: TestSkill"))
            assertTrue(full.contains("Skill body content"))
        }

        @Test
        fun `toFullContent includes version and author when present`() {
            val skill = ClaudeSkill(
                name = "V1Skill",
                description = "desc",
                content = "body",
                version = "2.0",
                author = "Alice"
            )
            val full = skill.toFullContent()
            assertTrue(full.contains("Version: 2.0"))
            assertTrue(full.contains("Author: Alice"))
        }

        @Test
        fun `toFullContent includes tags when present`() {
            val skill = ClaudeSkill(
                name = "Tagged",
                description = "desc",
                content = "body",
                tags = listOf("ai", "coding")
            )
            val full = skill.toFullContent()
            assertTrue(full.contains("Tags: ai, coding"))
        }

        @Test
        fun `toFullContent omits version author tags when absent`() {
            val skill = ClaudeSkill(name = "MinSkill", description = "desc", content = "body")
            val full = skill.toFullContent()
            assertFalse(full.contains("Version:"))
            assertFalse(full.contains("Author:"))
            assertFalse(full.contains("Tags:"))
        }

        @Test
        fun `toFullContent includes attachments section`() {
            val skill = ClaudeSkill(
                name = "WithAttach",
                description = "desc",
                content = "body",
                attachments = listOf(
                    SkillAttachment(
                        name = "helper.py",
                        type = AttachmentType.SCRIPT,
                        path = "scripts/helper.py",
                        description = "A helper script",
                        content = "print('hello')",
                        absolutePath = "/tmp/helper.py"
                    )
                )
            )
            val full = skill.toFullContent()
            assertTrue(full.contains("## Attachments"))
            assertTrue(full.contains("helper.py"))
            assertTrue(full.contains("script"))
            assertTrue(full.contains("python3 /tmp/helper.py"))
        }

        @Test
        fun `toFullContent includes skill directory when location is set`() {
            val skill = ClaudeSkill(
                name = "Located",
                description = "desc",
                content = "body",
                location = "/home/user/skills/my-skill/SKILL.md"
            )
            val full = skill.toFullContent()
            assertTrue(full.contains("Skill directory:"))
        }

        @Test
        fun `baseDirectory derived from location`() {
            val skill = ClaudeSkill(
                name = "LocSkill",
                description = "desc",
                content = "body",
                location = "/home/user/skills/test-skill/SKILL.md"
            )
            assertEquals("/home/user/skills/test-skill", skill.baseDirectory)
        }

        @Test
        fun `baseDirectory is null when location is null`() {
            val skill = ClaudeSkill(name = "NoLoc", description = "desc", content = "body")
            assertNull(skill.baseDirectory)
        }

        @Test
        fun `listBundledResources returns empty when location is null`() {
            val skill = ClaudeSkill(name = "NoLoc", description = "desc", content = "body")
            assertTrue(skill.listBundledResources().isEmpty())
        }

        @Test
        fun `listBundledResources returns empty for non-existing directory`() {
            val skill = ClaudeSkill(
                name = "BadDir",
                description = "desc",
                content = "body",
                location = "/non/existing/path/SKILL.md"
            )
            assertTrue(skill.listBundledResources().isEmpty())
        }

        @Test
        fun `default values are correct`() {
            val skill = ClaudeSkill(name = "Min", description = "desc", content = "body")
            assertEquals(emptyList<String>(), skill.tags)
            assertEquals(emptyList<String>(), skill.dependencies)
            assertEquals(emptyList<SkillAttachment>(), skill.attachments)
            assertEquals(emptyMap<String, String>(), skill.metadata)
            assertEquals(emptyList<String>(), skill.mcpServers)
            assertNull(skill.version)
            assertNull(skill.author)
            assertNull(skill.location)
            assertNull(skill.scope)
        }
    }

    // =========================================================================
    // SkillAttachment
    // =========================================================================

    @Nested
    inner class SkillAttachmentTest {

        @Test
        fun `blank name throws`() {
            assertThrows<IllegalArgumentException> {
                SkillAttachment(name = "", type = AttachmentType.SCRIPT, path = "test.py")
            }
        }

        @Test
        fun `blank path throws`() {
            assertThrows<IllegalArgumentException> {
                SkillAttachment(name = "test.py", type = AttachmentType.SCRIPT, path = "")
            }
        }

        @Test
        fun `valid attachment created`() {
            val att = SkillAttachment(
                name = "helper.py",
                type = AttachmentType.SCRIPT,
                path = "scripts/helper.py",
                description = "A helper",
                content = "print('hi')",
                absolutePath = "/abs/helper.py"
            )
            assertEquals("helper.py", att.name)
            assertEquals(AttachmentType.SCRIPT, att.type)
            assertEquals("scripts/helper.py", att.path)
            assertEquals("A helper", att.description)
            assertEquals("print('hi')", att.content)
            assertEquals("/abs/helper.py", att.absolutePath)
        }

        @Test
        fun `default optional values are null`() {
            val att = SkillAttachment(name = "f.txt", type = AttachmentType.DATA, path = "f.txt")
            assertNull(att.description)
            assertNull(att.content)
            assertNull(att.absolutePath)
        }
    }

    // =========================================================================
    // AttachmentType
    // =========================================================================

    @Nested
    inner class AttachmentTypeTest {

        @Test
        fun `all expected enum values exist`() {
            val types = AttachmentType.entries
            assertTrue(types.contains(AttachmentType.SCRIPT))
            assertTrue(types.contains(AttachmentType.TEMPLATE))
            assertTrue(types.contains(AttachmentType.DATA))
            assertTrue(types.contains(AttachmentType.CONFIG))
            assertTrue(types.contains(AttachmentType.OTHER))
            assertEquals(5, types.size)
        }
    }

    // =========================================================================
    // SkillsConfiguration
    // =========================================================================

    @Nested
    inner class SkillsConfigurationTest {

        @Test
        fun `default values are correct`() {
            val config = SkillsConfiguration()
            assertEquals("./skills", config.skillsPath)
            assertTrue(config.enabled)
            assertTrue(config.autoDiscovery)
            assertEquals(8, config.maxSkillsPerRequest)
            assertTrue(config.progressiveDisclosure)
            assertEquals(100_000L, config.maxAttachmentSize)
            assertEquals(500_000L, config.maxSkillContentSize)
            assertTrue(config.scanStandardPaths)
            assertFalse(config.requireProjectTrust)
            assertNull(config.skillWhitelistMode)
            assertTrue(config.enabledSkills.isEmpty())
            assertTrue(config.disabledSkills.isEmpty())
            assertTrue(config.notLoadSkills.isEmpty())
            assertFalse(config.isSkillWhitelistModeEnabled())
            assertTrue(config.hooksEnabled)
            assertTrue(config.hookScriptExecutionEnabled)
            assertEquals(30L, config.hookTimeoutSeconds)
            assertEquals(listOf("py", "js", "ts", "kts"), config.allowedScriptTypes)
            assertTrue(config.builtinSkillsEnabled)
            assertFalse(config.autoUpgrade)
        }

        @Test
        fun `isSkillEnabled returns false when disabled globally`() {
            val config = SkillsConfiguration(enabled = false)
            assertFalse(config.isSkillEnabled("any-skill"))
        }

        @Test
        fun `isSkillEnabled returns true by default when enabled`() {
            val config = SkillsConfiguration()
            assertTrue(config.isSkillEnabled("some-skill"))
        }

        @Test
        fun `isSkillEnabled returns false for disabledSkills`() {
            val config = SkillsConfiguration(disabledSkills = listOf("blocked-skill"))
            assertFalse(config.isSkillEnabled("blocked-skill"))
            assertTrue(config.isSkillEnabled("other-skill"))
        }

        @Test
        fun `isSkillEnabled returns false for notLoadSkills`() {
            val config = SkillsConfiguration(notLoadSkills = listOf("no-load-skill"))
            assertFalse(config.isSkillEnabled("no-load-skill"))
            assertTrue(config.isSkillEnabled("other-skill"))
        }

        @Test
        fun `legacy enabledSkills still acts as whitelist when mode is unspecified`() {
            val config = SkillsConfiguration(enabledSkills = listOf("allowed-skill"))
            assertTrue(config.isSkillEnabled("allowed-skill"))
            assertFalse(config.isSkillEnabled("not-allowed"))
        }

        @Test
        fun `explicitly disabling whitelist mode keeps all skills visible`() {
            val config = SkillsConfiguration(
                skillWhitelistMode = false,
                enabledSkills = listOf("allowed-skill")
            )
            assertTrue(config.isSkillEnabled("allowed-skill"))
            assertTrue(config.isSkillEnabled("not-allowed"))
        }

        @Test
        fun `whitelist mode auto-allows built-in names`() {
            val config = SkillsConfiguration(skillWhitelistMode = true)
            assertTrue(
                config.isSkillEnabled(
                    "braidrun-agent-guide",
                    autoWhitelistedSkillNames = setOf("braidrun-agent-guide")
                )
            )
            assertFalse(config.isSkillEnabled("not-allowed"))
        }

        @Test
        fun `isSkillEnabled disabledSkills takes priority over enabledSkills`() {
            val config = SkillsConfiguration(
                enabledSkills = listOf("my-skill"),
                disabledSkills = listOf("my-skill")
            )
            assertFalse(config.isSkillEnabled("my-skill"))
        }

        @Test
        fun `invalid maxSkillsPerRequest throws`() {
            assertThrows<IllegalArgumentException> {
                SkillsConfiguration(maxSkillsPerRequest = 0)
            }
            assertThrows<IllegalArgumentException> {
                SkillsConfiguration(maxSkillsPerRequest = 21)
            }
        }

        @Test
        fun `invalid maxAttachmentSize throws`() {
            assertThrows<IllegalArgumentException> {
                SkillsConfiguration(maxAttachmentSize = 0)
            }
        }

        @Test
        fun `invalid maxSkillContentSize throws`() {
            assertThrows<IllegalArgumentException> {
                SkillsConfiguration(maxSkillContentSize = -1)
            }
        }

        @Test
        fun `boundary maxSkillsPerRequest values accepted`() {
            assertDoesNotThrow { SkillsConfiguration(maxSkillsPerRequest = 1) }
            assertDoesNotThrow { SkillsConfiguration(maxSkillsPerRequest = 20) }
        }
    }

    // =========================================================================
    // SkillMetadata
    // =========================================================================

    @Nested
    inner class SkillMetadataTest {

        @Test
        fun `default values are correct`() {
            val meta = SkillMetadata()
            assertEquals(0, meta.priority)
            assertTrue(meta.contexts.isEmpty())
            assertTrue(meta.excludeWith.isEmpty())
            assertTrue(meta.requiredCapabilities.isEmpty())
        }

        @Test
        fun `custom values preserved`() {
            val meta = SkillMetadata(
                priority = 5,
                contexts = listOf("coding"),
                excludeWith = listOf("other-skill"),
                requiredCapabilities = listOf("tools")
            )
            assertEquals(5, meta.priority)
            assertEquals(listOf("coding"), meta.contexts)
            assertEquals(listOf("other-skill"), meta.excludeWith)
            assertEquals(listOf("tools"), meta.requiredCapabilities)
        }
    }
}
