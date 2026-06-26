package com.fartech.agents.commons

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillLoaderAndManagerTest {

    // =========================================================================
    // SkillLoader — loadSkill
    // =========================================================================

    @Nested
    inner class LoadSkillTest {

        private fun createSkillDir(tempDir: Path, skillMdContent: String): Path {
            val skillDir = tempDir.resolve("test-skill")
            Files.createDirectories(skillDir)
            Files.writeString(skillDir.resolve("SKILL.md"), skillMdContent)
            return skillDir
        }

        private fun loader(tempDir: Path, config: SkillsConfiguration = SkillsConfiguration()): SkillLoader {
            return SkillLoader(tempDir, config)
        }

        @Test
        fun `loadSkill returns valid skill from well-formed SKILL md`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: my-skill
                |description: A test skill
                |version: 1.0.0
                |author: TestAuthor
                |tags:
                |  - testing
                |  - demo
                |---
                |
                |# My Skill
                |
                |This is the body content.
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)

            assertNotNull(skill)
            assertEquals("my-skill", skill!!.name)
            assertEquals("A test skill", skill.description)
            assertEquals("1.0.0", skill.version)
            assertEquals("TestAuthor", skill.author)
            assertEquals(listOf("testing", "demo"), skill.tags)
            assertTrue(skill.content.contains("This is the body content."))
            assertEquals("configured", skill.scope)
            assertNotNull(skill.location)
        }

        @Test
        fun `loadSkill returns null when no SKILL md exists`(@TempDir tempDir: Path) {
            val skillDir = tempDir.resolve("empty-dir")
            Files.createDirectories(skillDir)
            val loader = loader(tempDir)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill returns null for empty SKILL md`(@TempDir tempDir: Path) {
            val skillDir = createSkillDir(tempDir, "")
            val loader = loader(tempDir)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill returns null for blank SKILL md`(@TempDir tempDir: Path) {
            val skillDir = createSkillDir(tempDir, "   \n  \n  ")
            val loader = loader(tempDir)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill returns null when frontmatter missing opening delimiter`(@TempDir tempDir: Path) {
            val content = """
                |name: my-skill
                |description: A test skill
                |---
                |Body content
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill returns null when frontmatter missing closing delimiter`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: my-skill
                |description: A test skill
                |Body content
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill returns null when name is missing`(@TempDir tempDir: Path) {
            val content = """
                |---
                |description: A test skill
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill returns null when description is missing`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: my-skill
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill sanitizes name with special characters`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: my skill!@#with spaces
                |description: A test skill
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            // Special chars replaced with dashes, consecutive dashes collapsed
            assertFalse(skill!!.name.contains(" "))
            assertFalse(skill.name.contains("!"))
            assertFalse(skill.name.contains("@"))
            assertFalse(skill.name.contains("#"))
        }

        @Test
        fun `loadSkill handles description with colons`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: colon-skill
                |description: Use this skill when: the user asks about PDFs
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("Use this skill when: the user asks about PDFs", skill!!.description)
        }

        @Test
        fun `loadSkill handles quoted values`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: "quoted-skill"
                |description: 'A quoted description'
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("quoted-skill", skill!!.name)
            assertEquals("A quoted description", skill.description)
        }

        @Test
        fun `loadSkill with comma-separated tags`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: tag-skill
                |description: Has tags
                |tags: ai, coding, test
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            // tags parsed as comma-separated string
            assertEquals(listOf("ai", "coding", "test"), skill!!.tags)
        }

        @Test
        fun `loadSkill returns null when file exceeds maxSkillContentSize`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: big-skill
                |description: Too large
                |---
                |${"x".repeat(200)}
            """.trimMargin()
            val config = SkillsConfiguration(maxSkillContentSize = 50)
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir, config)

            assertNull(loader.loadSkill(skillDir))
        }

        @Test
        fun `loadSkill finds lowercase skill md`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: lower-skill
                |description: Lowercase file
                |---
                |Body
            """.trimMargin()
            val skillDir = tempDir.resolve("lower-skill")
            Files.createDirectories(skillDir)
            Files.writeString(skillDir.resolve("skill.md"), content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("lower-skill", skill!!.name)
        }

        @Test
        fun `loadSkill with scope parameter`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: scoped-skill
                |description: Has scope
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir, scope = "project")
            assertNotNull(skill)
            assertEquals("project", skill!!.scope)
        }

        @Test
        fun `loadSkill handles YAML comments`(@TempDir tempDir: Path) {
            val content = """
                |---
                |# This is a YAML comment
                |name: comment-skill
                |description: Has comments
                |# Another comment
                |version: 1.0
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("comment-skill", skill!!.name)
            assertEquals("1.0", skill.version)
        }

        @Test
        fun `loadSkill handles folded block scalar description`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: folded-skill
                |description: >
                |  This is a folded
                |  block scalar description
                |  spanning multiple lines.
                |version: 2.0
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("folded-skill", skill!!.name)
            assertEquals("This is a folded block scalar description spanning multiple lines.", skill.description)
            assertEquals("2.0", skill.version)
        }

        @Test
        fun `loadSkill handles literal block scalar description`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: literal-skill
                |description: |
                |  Line one.
                |  Line two.
                |  Line three.
                |version: 3.0
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("literal-skill", skill!!.name)
            assertEquals("Line one.\nLine two.\nLine three.", skill.description)
            assertEquals("3.0", skill.version)
        }

        @Test
        fun `loadSkill handles folded block scalar with strip chomp indicator`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: strip-skill
                |description: >-
                |  Folded with strip
                |  chomp indicator.
                |tags:
                |  - test
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("Folded with strip chomp indicator.", skill!!.description)
            assertEquals(listOf("test"), skill.tags)
        }

        @Test
        fun `loadSkill handles block scalar as last frontmatter field`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: last-field-skill
                |version: 1.0
                |description: >
                |  Description is the last
                |  field before closing delimiter.
                |---
                |Body content here.
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals("Description is the last field before closing delimiter.", skill!!.description)
            assertEquals("1.0", skill.version)
        }

        @Test
        fun `loadSkill loads attachments from frontmatter`(@TempDir tempDir: Path) {
            val skillDir = tempDir.resolve("attach-skill")
            Files.createDirectories(skillDir)
            val scriptsDir = skillDir.resolve("scripts")
            Files.createDirectories(scriptsDir)
            Files.writeString(scriptsDir.resolve("helper.py"), "print('hello')")

            val content = """
                |---
                |name: attach-skill
                |description: Has attachments
                |attachments:
                |  - scripts/helper.py
                |---
                |Body
            """.trimMargin()
            Files.writeString(skillDir.resolve("SKILL.md"), content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertEquals(1, skill!!.attachments.size)
            assertEquals("helper.py", skill.attachments[0].name)
            assertEquals(AttachmentType.SCRIPT, skill.attachments[0].type)
            assertEquals("print('hello')", skill.attachments[0].content)
        }

        @Test
        fun `loadSkill skips missing attachments gracefully`(@TempDir tempDir: Path) {
            val content = """
                |---
                |name: missing-attach
                |description: Missing attachment ref
                |attachments:
                |  - nonexistent/file.py
                |---
                |Body
            """.trimMargin()
            val skillDir = createSkillDir(tempDir, content)
            val loader = loader(tempDir)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertTrue(skill!!.attachments.isEmpty())
        }

        @Test
        fun `loadSkill skips attachment exceeding maxAttachmentSize`(@TempDir tempDir: Path) {
            val skillDir = tempDir.resolve("big-attach-skill")
            Files.createDirectories(skillDir)
            Files.writeString(skillDir.resolve("large.txt"), "x".repeat(200))

            val content = """
                |---
                |name: big-attach
                |description: Large attachment
                |attachments:
                |  - large.txt
                |---
                |Body
            """.trimMargin()
            Files.writeString(skillDir.resolve("SKILL.md"), content)
            val config = SkillsConfiguration(maxAttachmentSize = 50)
            val loader = loader(tempDir, config)

            val skill = loader.loadSkill(skillDir)
            assertNotNull(skill)
            assertTrue(skill!!.attachments.isEmpty())
        }
    }

    // =========================================================================
    // SkillLoader — loadAllSkills
    // =========================================================================

    @Nested
    inner class LoadAllSkillsTest {

        @Test
        fun `loadAllSkills discovers skills in subdirectories`(@TempDir tempDir: Path) {
            // Create two skill subdirectories
            listOf("skill-a", "skill-b").forEach { name ->
                val dir = tempDir.resolve(name)
                Files.createDirectories(dir)
                Files.writeString(
                    dir.resolve("SKILL.md"), """
                    |---
                    |name: $name
                    |description: Description of $name
                    |---
                    |Body of $name
                """.trimMargin()
                )
            }

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            val skills = loader.loadAllSkills()

            assertEquals(2, skills.size)
            assertTrue(skills.any { it.name == "skill-a" })
            assertTrue(skills.any { it.name == "skill-b" })
        }

        @Test
        fun `loadAllSkills skips disabled skills`(@TempDir tempDir: Path) {
            val dir = tempDir.resolve("blocked-skill")
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("SKILL.md"), """
                |---
                |name: blocked-skill
                |description: Should be blocked
                |---
                |Body
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false,
                disabledSkills = listOf("blocked-skill")
            )
            val loader = SkillLoader(tempDir, config)
            val skills = loader.loadAllSkills()

            assertTrue(skills.isEmpty())
        }

        @Test
        fun `loadAllSkills returns empty when skills subsystem is disabled`(@TempDir tempDir: Path) {
            val dir = tempDir.resolve("some-skill")
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("SKILL.md"), """
                |---
                |name: some-skill
                |description: Should not load
                |---
                |Body
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                enabled = false,
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)

            assertTrue(loader.loadAllSkills().isEmpty())
        }

        @Test
        fun `loadAllSkills whitelist mode only loads explicitly allowlisted configured skills`(@TempDir tempDir: Path) {
            listOf("allowed-skill", "blocked-skill").forEach { name ->
                val dir = tempDir.resolve(name)
                Files.createDirectories(dir)
                Files.writeString(
                    dir.resolve("SKILL.md"), """
                    |---
                    |name: $name
                    |description: $name description
                    |---
                    |Body
                """.trimMargin()
                )
            }

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                skillWhitelistMode = true,
                enabledSkills = listOf("allowed-skill"),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            val skills = loader.loadAllSkills()

            assertEquals(listOf("allowed-skill"), skills.map { it.name })
        }

        @Test
        fun `loadAllSkills whitelist mode auto-allows configured overrides of built-in names`(@TempDir tempDir: Path) {
            val dir = tempDir.resolve("braidrun-workflow-guide")
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("SKILL.md"), """
                |---
                |name: braidrun-workflow-guide
                |description: Local override for builtin guide
                |---
                |Body
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                skillWhitelistMode = true,
                scanStandardPaths = false,
                builtinSkillsEnabled = true,
                builtinSkillsResourcePath = "/builtin-skills"
            )
            val loader = SkillLoader(tempDir, config)
            val skills = loader.loadAllSkills()
            val overriddenGuide = skills.firstOrNull { it.name == "braidrun-workflow-guide" }

            assertNotNull(overriddenGuide)
            assertEquals("configured", overriddenGuide!!.scope)
            assertEquals("Local override for builtin guide", overriddenGuide.description)
        }

        @Test
        fun `loadAllSkills returns empty for nonexistent path`(@TempDir tempDir: Path) {
            val config = SkillsConfiguration(
                skillsPath = tempDir.resolve("nonexistent").toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir.resolve("nonexistent"), config)
            val skills = loader.loadAllSkills()

            assertTrue(skills.isEmpty())
        }

        @Test
        fun `loadAllSkills skips directories named git and node_modules`(@TempDir tempDir: Path) {
            // Create a SKILL.md directly inside a .git directory
            // The .git dir itself is skipped, so SKILL.md placed there should not be found
            val gitDir = tempDir.resolve(".git")
            Files.createDirectories(gitDir)
            Files.writeString(
                gitDir.resolve("SKILL.md"), """
                |---
                |name: hidden-skill
                |description: In git dir
                |---
                |Body
            """.trimMargin()
            )

            // Create a SKILL.md inside node_modules (should also be skipped)
            val nodeDir = tempDir.resolve("node_modules")
            Files.createDirectories(nodeDir)
            Files.writeString(
                nodeDir.resolve("SKILL.md"), """
                |---
                |name: node-skill
                |description: In node_modules
                |---
                |Body
            """.trimMargin()
            )

            // Create a valid skill
            val validDir = tempDir.resolve("valid-skill")
            Files.createDirectories(validDir)
            Files.writeString(
                validDir.resolve("SKILL.md"), """
                |---
                |name: valid-skill
                |description: A valid skill
                |---
                |Body
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            val skills = loader.loadAllSkills()

            assertEquals(1, skills.size)
            assertEquals("valid-skill", skills[0].name)
        }

        @Test
        fun `loadAllSkills deduplicates by name with scope precedence`(@TempDir tempDir: Path) {
            // Create a skill in the primary path
            val primaryDir = tempDir.resolve("primary")
            Files.createDirectories(primaryDir)
            val skillDir = primaryDir.resolve("dup-skill")
            Files.createDirectories(skillDir)
            Files.writeString(
                skillDir.resolve("SKILL.md"), """
                |---
                |name: dup-skill
                |description: From primary
                |---
                |Primary body
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                skillsPath = primaryDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(primaryDir, config)
            val skills = loader.loadAllSkills()

            assertEquals(1, skills.size)
            assertEquals("dup-skill", skills[0].name)
        }
    }

    // =========================================================================
    // SkillLoader — loadBuiltinSkills
    // =========================================================================

    @Nested
    inner class LoadBuiltinSkillsTest {

        @Test
        fun `loadBuiltinSkills loads skills from classpath`(@TempDir tempDir: Path) {
            val config = SkillsConfiguration(
                builtinSkillsEnabled = true,
                builtinSkillsResourcePath = "/builtin-skills"
            )
            val loader = SkillLoader(tempDir, config)
            val builtinSkills = loader.loadBuiltinSkills()

            // Should load at least the built-in guide skill
            assertTrue(builtinSkills.isNotEmpty(), "Expected at least one built-in skill")
            assertTrue(builtinSkills.any { it.scope == "builtin" })
            assertTrue(builtinSkills.any { it.name == "braidrun-workflow-guide" })
        }

        @Test
        fun `loadBuiltinSkills returns empty for nonexistent resource path`(@TempDir tempDir: Path) {
            val config = SkillsConfiguration(
                builtinSkillsEnabled = true,
                builtinSkillsResourcePath = "/nonexistent-skills-path"
            )
            val loader = SkillLoader(tempDir, config)
            val builtinSkills = loader.loadBuiltinSkills()

            assertTrue(builtinSkills.isEmpty())
        }

        @Test
        fun `loadBuiltinSkills respects disabled skills`(@TempDir tempDir: Path) {
            // First load to discover names
            val config1 = SkillsConfiguration(
                builtinSkillsEnabled = true,
                builtinSkillsResourcePath = "/builtin-skills"
            )
            val loader1 = SkillLoader(tempDir, config1)
            val allBuiltin = loader1.loadBuiltinSkills()

            if (allBuiltin.isNotEmpty()) {
                val firstName = allBuiltin[0].name
                // Now load with that skill disabled
                val config2 = SkillsConfiguration(
                    builtinSkillsEnabled = true,
                    builtinSkillsResourcePath = "/builtin-skills",
                    disabledSkills = listOf(firstName)
                )
                val loader2 = SkillLoader(tempDir, config2)
                val filtered = loader2.loadBuiltinSkills()

                assertFalse(filtered.any { it.name == firstName })
            }
        }

        @Test
        fun `loadBuiltinSkills whitelist mode still loads built-in skills by default`(@TempDir tempDir: Path) {
            val config = SkillsConfiguration(
                skillWhitelistMode = true,
                builtinSkillsEnabled = true,
                builtinSkillsResourcePath = "/builtin-skills"
            )
            val loader = SkillLoader(tempDir, config)
            val builtinSkills = loader.loadBuiltinSkills()

            assertTrue(builtinSkills.isNotEmpty())
            assertTrue(builtinSkills.any { it.name == "braidrun-workflow-guide" })
        }

        @Test
        fun `loadBuiltinSkills exposes workflow guide bundled references`(@TempDir tempDir: Path) {
            val config = SkillsConfiguration(
                builtinSkillsEnabled = true,
                builtinSkillsResourcePath = "/builtin-skills"
            )
            val loader = SkillLoader(tempDir, config)
            val builtinSkills = loader.loadBuiltinSkills()

            val agentGuide = builtinSkills.firstOrNull { it.name == "braidrun-workflow-guide" }
                ?: fail("braidrun-workflow-guide should be present")
            assertTrue(agentGuide.attachments.isNotEmpty(), "Agent guide should expose bundled references")
            assertTrue(agentGuide.attachments.any { it.path == "workflow-template.yaml" })
            assertTrue(agentGuide.attachments.any { it.path == "workflow-capability-reference.md" })
        }
    }

    // =========================================================================
    // SkillLoader — loadAllHooks
    // =========================================================================

    @Nested
    inner class LoadAllHooksTest {

        private fun createSkillWithHook(
            tempDir: Path,
            name: String,
            metadata: String = """{"braidrun-workflow":{"events":["agent:bootstrap"]}}"""
        ): Path {
            val skillDir = tempDir.resolve(name)
            Files.createDirectories(skillDir.resolve("hooks/braidrun-workflow"))
            Files.writeString(
                skillDir.resolve("SKILL.md"), """
                |---
                |name: $name
                |description: $name description
                |---
                |Body
            """.trimMargin()
            )
            Files.writeString(
                skillDir.resolve("hooks/braidrun-workflow/HOOK.md"), """
                |---
                |name: ${name}-hook
                |description: Hook for $name
                |metadata: $metadata
                |---
                |
                |# Hook body
            """.trimMargin()
            )
            return skillDir
        }

        @Test
        fun `loadAllHooks skips hooks from non-whitelisted skills`(@TempDir tempDir: Path) {
            createSkillWithHook(tempDir, "hooked-skill")

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                skillWhitelistMode = true,
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            val hooks = loader.loadAllHooks()

            assertTrue(hooks.isEmpty())
        }

        @Test
        fun `loadAllHooks returns empty when skills subsystem is disabled`(@TempDir tempDir: Path) {
            createSkillWithHook(tempDir, "hooked-skill")

            val userGlobalHooksDir = tempDir.resolve("global-hooks")
            Files.createDirectories(userGlobalHooksDir)
            Files.writeString(
                userGlobalHooksDir.resolve("HOOK.md"), """
                |---
                |name: global-hook
                |description: Should not load
                |metadata: {"braidrun-workflow":{"events":["agent:bootstrap"]}}
                |---
                |Body
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                enabled = false,
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false,
                userGlobalHooksDir = userGlobalHooksDir.toString()
            )
            val loader = SkillLoader(tempDir, config)

            assertTrue(loader.loadAllHooks().isEmpty())
        }

        @Test
        fun `loadAllHooks keeps hooks for allowlisted skills`(@TempDir tempDir: Path) {
            createSkillWithHook(tempDir, "hooked-skill")

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                skillWhitelistMode = true,
                enabledSkills = listOf("hooked-skill"),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            val hooks = loader.loadAllHooks()

            assertEquals(1, hooks.size)
            assertEquals("hooked-skill-hook", hooks.first().name)
        }

        @Test
        fun `loadAllHooks preserves requires config values that contain braces inside JSON strings`(@TempDir tempDir: Path) {
            createSkillWithHook(
                tempDir = tempDir,
                name = "hooked-skill",
                metadata = """{"braidrun-workflow":{"events":["agent:bootstrap"],"requires":{"config":["/tmp/{project}/settings.json"],"bins":["git"]}}}"""
            )

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                enabledSkills = listOf("hooked-skill"),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            val hooks = loader.loadAllHooks()

            assertEquals(1, hooks.size)
            assertEquals(listOf("git"), hooks.first().requires?.bins)
            assertEquals(listOf("/tmp/{project}/settings.json"), hooks.first().requires?.config)
        }
    }

    // =========================================================================
    // SkillManager
    // =========================================================================

    @Nested
    inner class SkillManagerTest {

        private fun createManagerWithSkills(tempDir: Path, skills: List<Pair<String, String>>): SkillManager {
            skills.forEach { (name, desc) ->
                val dir = tempDir.resolve(name)
                Files.createDirectories(dir)
                Files.writeString(
                    dir.resolve("SKILL.md"), """
                    |---
                    |name: $name
                    |description: $desc
                    |tags:
                    |  - $name-tag
                    |---
                    |Instructions for $name
                """.trimMargin()
                )
            }
            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            return SkillManager(config, loader)
        }

        @Test
        fun `initialize loads all skills`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(
                tempDir, listOf(
                    "alpha" to "Alpha skill",
                    "beta" to "Beta skill"
                )
            )
            manager.initialize()

            assertEquals(2, manager.getSkillCount())
            assertTrue(manager.hasSkill("alpha"))
            assertTrue(manager.hasSkill("beta"))
            assertFalse(manager.hasSkill("gamma"))
        }

        @Test
        fun `initialize is idempotent`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("only" to "Only skill"))
            manager.initialize()
            manager.initialize() // Should not throw
            assertEquals(1, manager.getSkillCount())
        }

        @Test
        fun `initialize skips all skills when subsystem is disabled`(@TempDir tempDir: Path) {
            val dir = tempDir.resolve("disabled-skill")
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("SKILL.md"), """
                |---
                |name: disabled-skill
                |description: Should not load
                |---
                |Instructions
            """.trimMargin()
            )
            val config = SkillsConfiguration(
                enabled = false,
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val manager = SkillManager(config, SkillLoader(tempDir, config))

            manager.initialize()
            manager.refresh()

            assertEquals(0, manager.getSkillCount())
            assertFalse(manager.hasSkill("disabled-skill"))
            assertEquals("", manager.getDiscoverySummaries())
        }

        @Test
        fun `getSkill returns skill by exact name`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("my-skill" to "My skill"))
            manager.initialize()

            val skill = manager.getSkill("my-skill")
            assertNotNull(skill)
            assertEquals("my-skill", skill!!.name)
        }

        @Test
        fun `getSkill returns skill by case-insensitive name`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("my-skill" to "My skill"))
            manager.initialize()

            val skill = manager.getSkill("MY-SKILL")
            assertNotNull(skill)
            assertEquals("my-skill", skill!!.name)
        }

        @Test
        fun `getSkill returns null for nonexistent skill`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("exists" to "Exists"))
            manager.initialize()

            assertNull(manager.getSkill("nonexistent"))
        }

        @Test
        fun `activateSkill returns full content on first activation`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("act-skill" to "Activatable"))
            manager.initialize()

            val result = manager.activateSkill("act-skill")
            assertTrue(result.contains("skill_content"))
            assertTrue(result.contains("act-skill"))
            assertTrue(manager.isSkillActivated("act-skill"))
        }

        @Test
        fun `activateSkill returns dedup notice on second activation`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("dedup-skill" to "Dedup"))
            manager.initialize()

            manager.activateSkill("dedup-skill")
            val second = manager.activateSkill("dedup-skill")
            assertTrue(second.contains("already active"))
        }

        @Test
        fun `activateSkill returns error for nonexistent skill`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, emptyList())
            manager.initialize()

            val result = manager.activateSkill("ghost")
            assertTrue(result.contains("Error"))
            assertTrue(result.contains("not found"))
        }

        @Test
        fun `clearActivationTracking resets activated skills`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("reset-skill" to "Resettable"))
            manager.initialize()

            manager.activateSkill("reset-skill")
            assertTrue(manager.isSkillActivated("reset-skill"))

            manager.clearActivationTracking()
            assertFalse(manager.isSkillActivated("reset-skill"))
            assertTrue(manager.getActivatedSkills().isEmpty())
        }

        @Test
        fun `getActivatedSkills returns all activated skill names`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(
                tempDir, listOf(
                    "s1" to "Skill 1",
                    "s2" to "Skill 2",
                    "s3" to "Skill 3"
                )
            )
            manager.initialize()

            manager.activateSkill("s1")
            manager.activateSkill("s3")

            val activated = manager.getActivatedSkills()
            assertEquals(setOf("s1", "s3"), activated)
        }

        @Test
        fun `detectRelevantSkills finds by name match`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(
                tempDir, listOf(
                    "pdf-reader" to "Reads PDFs",
                    "code-writer" to "Writes code"
                )
            )
            manager.initialize()

            val relevant = manager.detectRelevantSkills("I need the pdf-reader")
            assertTrue(relevant.contains("pdf-reader"))
            assertFalse(relevant.contains("code-writer"))
        }

        @Test
        fun `detectRelevantSkills finds by tag match`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(
                tempDir, listOf(
                    "tagged-skill" to "Has tags"
                )
            )
            manager.initialize()

            val relevant = manager.detectRelevantSkills("I need tagged-skill-tag functionality")
            assertTrue(relevant.contains("tagged-skill"))
        }

        @Test
        fun `detectRelevantSkills returns empty for blank message`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("any" to "Any"))
            manager.initialize()

            assertTrue(manager.detectRelevantSkills("").isEmpty())
            assertTrue(manager.detectRelevantSkills("   ").isEmpty())
        }

        @Test
        fun `detectRelevantSkills respects maxSkillsPerRequest`(@TempDir tempDir: Path) {
            // Create many skills with matching name pattern
            val skills = (1..5).map { "match-$it" to "Matching skill $it" }
            val dir = tempDir.resolve("skills-root")
            Files.createDirectories(dir)
            skills.forEach { (name, desc) ->
                val sDir = dir.resolve(name)
                Files.createDirectories(sDir)
                Files.writeString(
                    sDir.resolve("SKILL.md"), """
                    |---
                    |name: $name
                    |description: $desc
                    |---
                    |Body
                """.trimMargin()
                )
            }

            val config = SkillsConfiguration(
                skillsPath = dir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false,
                maxSkillsPerRequest = 2
            )
            val loader = SkillLoader(dir, config)
            val manager = SkillManager(config, loader)
            manager.initialize()

            // All 5 match by name
            val relevant = manager.detectRelevantSkills("match-1 match-2 match-3 match-4 match-5")
            assertTrue(relevant.size <= 2)
        }

        @Test
        fun `getDiscoverySummaries returns XML catalog`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(
                tempDir, listOf(
                    "discover-skill" to "Discoverable"
                )
            )
            manager.initialize()

            val summaries = manager.getDiscoverySummaries()
            assertTrue(summaries.contains("<available_skills>"))
            assertTrue(summaries.contains("</available_skills>"))
            assertTrue(summaries.contains("discover-skill"))
            assertTrue(summaries.contains("Discoverable"))
            assertTrue(summaries.contains("useSkill"))
        }

        @Test
        fun `getDiscoverySummaries returns empty string when no skills`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, emptyList())
            manager.initialize()

            assertEquals("", manager.getDiscoverySummaries())
        }

        @Test
        fun `getSkillsContent returns full content for named skills`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(
                tempDir, listOf(
                    "content-skill" to "Content test"
                )
            )
            manager.initialize()

            val content = manager.getSkillsContent(listOf("content-skill"))
            assertTrue(content.contains("content-skill"))
            assertTrue(content.contains("skill_content"))
        }

        @Test
        fun `getSkillsContent reports not-found for unknown skills`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, emptyList())
            manager.initialize()

            val content = manager.getSkillsContent(listOf("unknown"))
            assertTrue(content.contains("not found"))
        }

        @Test
        fun `getAllSkills returns sorted list`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(
                tempDir, listOf(
                    "zebra" to "Z skill",
                    "alpha" to "A skill",
                    "middle" to "M skill"
                )
            )
            manager.initialize()

            val all = manager.getAllSkills()
            assertEquals(3, all.size)
            assertEquals("alpha", all[0].name)
            assertEquals("middle", all[1].name)
            assertEquals("zebra", all[2].name)
        }

        @Test
        fun `createSkillSystemPrompt uses progressive disclosure when enabled`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("prompt-skill" to "For prompt"))
            manager.initialize()

            val prompt = manager.createSkillSystemPrompt()
            assertTrue(prompt.contains("<available_skills>"))
            assertTrue(prompt.contains("prompt-skill"))
        }

        @Test
        fun `refresh reloads skills`(@TempDir tempDir: Path) {
            val skillDir = tempDir.resolve("refresh-skill")
            Files.createDirectories(skillDir)
            Files.writeString(
                skillDir.resolve("SKILL.md"), """
                |---
                |name: refresh-skill
                |description: Original
                |---
                |Original body
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false
            )
            val loader = SkillLoader(tempDir, config)
            val manager = SkillManager(config, loader)
            manager.initialize()
            assertEquals(1, manager.getSkillCount())

            // Add another skill
            val newDir = tempDir.resolve("new-skill")
            Files.createDirectories(newDir)
            Files.writeString(
                newDir.resolve("SKILL.md"), """
                |---
                |name: new-skill
                |description: Newly added
                |---
                |New body
            """.trimMargin()
            )

            manager.refresh()
            assertEquals(2, manager.getSkillCount())
            assertTrue(manager.hasSkill("new-skill"))
        }

        @Test
        fun `shutdown clears loaded skills and hooks`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("shutdown-skill" to "Will shutdown"))
            manager.initialize()
            assertEquals(1, manager.getSkillCount())

            manager.shutdown()
            // After shutdown, skills are cleared, manager is no longer initialized
            // Re-initialization will reload from filesystem
            manager.initialize()
            assertEquals(1, manager.getSkillCount())
        }

        @Test
        fun `clearActivationTracking after shutdown allows re-activation`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("reactivate" to "Reactivatable"))
            manager.initialize()
            manager.activateSkill("reactivate")
            assertTrue(manager.isSkillActivated("reactivate"))

            manager.clearActivationTracking()
            assertFalse(manager.isSkillActivated("reactivate"))

            // Can re-activate after clearing
            val result = manager.activateSkill("reactivate")
            assertTrue(result.contains("skill_content"))
            assertTrue(manager.isSkillActivated("reactivate"))
        }

        @Test
        fun `auto-initialization on getSkill`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("auto-init" to "Auto initialized"))
            // Don't call initialize() explicitly
            val skill = manager.getSkill("auto-init")
            assertNotNull(skill)
            assertEquals("auto-init", skill!!.name)
        }

        @Test
        fun `auto-initialization on getAllSkills`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("lazy" to "Lazy loaded"))
            val all = manager.getAllSkills()
            assertEquals(1, all.size)
        }

        @Test
        fun `auto-initialization on getSkillCount`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("count" to "Counted"))
            assertEquals(1, manager.getSkillCount())
        }

        @Test
        fun `auto-initialization on hasSkill`(@TempDir tempDir: Path) {
            val manager = createManagerWithSkills(tempDir, listOf("check" to "Checked"))
            assertTrue(manager.hasSkill("check"))
        }
    }

    // =========================================================================
    // SkillManager — getSkillsContent with maxSkillsPerRequest
    // =========================================================================

    @Nested
    inner class SkillsContentLimitTest {

        @Test
        fun `getSkillsContent respects maxSkillsPerRequest`(@TempDir tempDir: Path) {
            val skills = (1..5).map { "skill-$it" to "Skill $it desc" }
            skills.forEach { (name, desc) ->
                val dir = tempDir.resolve(name)
                Files.createDirectories(dir)
                Files.writeString(
                    dir.resolve("SKILL.md"), """
                    |---
                    |name: $name
                    |description: $desc
                    |---
                    |Body of $name
                """.trimMargin()
                )
            }

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false,
                maxSkillsPerRequest = 2
            )
            val loader = SkillLoader(tempDir, config)
            val manager = SkillManager(config, loader)
            manager.initialize()

            val content = manager.getSkillsContent(listOf("skill-1", "skill-2", "skill-3", "skill-4", "skill-5"))
            // Only first 2 should be included
            assertTrue(content.contains("skill-1"))
            assertTrue(content.contains("skill-2"))
            assertFalse(content.contains("skill-3"))
        }
    }

    // =========================================================================
    // SkillManager — createSkillSystemPrompt without progressive disclosure
    // =========================================================================

    @Nested
    inner class SystemPromptTest {

        @Test
        fun `createSkillSystemPrompt loads full content when progressive disclosure disabled`(@TempDir tempDir: Path) {
            val dir = tempDir.resolve("full-skill")
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("SKILL.md"), """
                |---
                |name: full-skill
                |description: Full content skill
                |---
                |Detailed instructions here
            """.trimMargin()
            )

            val config = SkillsConfiguration(
                skillsPath = tempDir.toString(),
                scanStandardPaths = false,
                builtinSkillsEnabled = false,
                progressiveDisclosure = false
            )
            val loader = SkillLoader(tempDir, config)
            val manager = SkillManager(config, loader)
            manager.initialize()

            val prompt = manager.createSkillSystemPrompt()
            assertTrue(prompt.contains("skill_content"))
            assertTrue(prompt.contains("Detailed instructions here"))
        }
    }
}
