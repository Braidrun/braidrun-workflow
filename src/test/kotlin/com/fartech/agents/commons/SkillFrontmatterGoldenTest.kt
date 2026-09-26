package com.fartech.agents.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Golden test for SKILL.md frontmatter parsing, over real shipped skills copied from
 * braidrun-web (`src/test/resources/skill-fixtures/`). Most of them put `translations:`
 * (nested per-locale `name` / `description`) next to the top-level fields, which the old
 * line parser flattened: `apple-connect` loaded as `Apple` with a 2.6k-char mixed-locale
 * description.
 */
class SkillFrontmatterGoldenTest {

    private val blockIndicators = setOf(">", ">-", "|", "|-")

    private fun loadFixture(name: String): ClaudeSkill {
        val dir = Paths.get(
            requireNotNull(javaClass.getResource("/skill-fixtures/$name")) { "missing fixture $name" }.toURI()
        )
        val config = SkillsConfiguration(scanStandardPaths = false, builtinSkillsEnabled = false)
        return requireNotNull(SkillLoader(dir.parent, config).loadSkill(dir)) { "fixture $name did not load" }
    }

    private fun assertCleanDescription(skill: ClaudeSkill) {
        assertFalse(skill.description in blockIndicators, "${skill.name}: description is a bare block indicator")
        assertFalse(skill.description.contains("translations"), "${skill.name}: translations leaked into description")
        assertFalse(skill.description.contains("name:"), "${skill.name}: nested keys leaked into description")
    }

    @Test
    fun `apple-connect resolves to its top-level name and English description`() {
        val skill = loadFixture("apple-connect")

        assertEquals("apple-connect", skill.name)
        assertEquals(
            "Direct access to Apple's App Store Connect and Apple Search Ads APIs — no relay. " +
                "Use when a workflow needs apps, builds, sales, campaigns, keywords or reports straight " +
                "from Apple with an apple_asc or apple_asa connection.",
            skill.description
        )
        assertEquals(221, skill.description.length)
        assertCleanDescription(skill)
        assertFalse(skill.metadata.containsKey("translations"))
    }

    @Test
    fun `flutter-dev keeps literal description and reads spec metadata`() {
        val skill = loadFixture("flutter-dev")

        assertEquals("flutter-dev", skill.name)
        assertTrue(skill.description.startsWith("Flutter cross-platform development guide covering widget patterns"))
        // Literal block: the two source lines stay separate lines.
        assertTrue(skill.description.contains("DevTools profiling.\nUse when: building Flutter apps"))
        assertCleanDescription(skill)
        assertEquals("1.0.0", skill.version)
        assertEquals("MIT", skill.metadata["license"])
        assertEquals("mobile", skill.metadata["category"])
        // List-valued metadata.sources is stringified, not a parse failure.
        assertEquals(
            "[Flutter Documentation, Riverpod Documentation, Bloc Library Documentation]",
            skill.metadata["sources"]
        )
    }

    @Test
    fun `minimax-docx reads author and version from metadata and keeps folded description`() {
        val skill = loadFixture("minimax-docx")

        assertEquals("minimax-docx", skill.name)
        assertEquals("MiniMaxAI", skill.author)
        assertEquals("1.0.0", skill.version)
        assertTrue(skill.description.startsWith("Professional DOCX document creation, editing, and formatting"))
        assertTrue(skill.description.endsWith("use this skill."))
        assertCleanDescription(skill)
        // `triggers` is an extension key, not tags.
        assertTrue(skill.tags.isEmpty())
        assertTrue(skill.metadata["triggers"]!!.startsWith("[Word, docx, document"))
    }

    @Test
    fun `dingyue-appleconnect keeps its top-level folded description after translations`() {
        val skill = loadFixture("dingyue-appleconnect")

        assertEquals("dingyue-appleconnect", skill.name)
        assertTrue(skill.description.startsWith("Apple App Store Connect API 全覆盖工具。"))
        assertTrue(skill.description.endsWith("同时支持 raw 命令直接访问任意 API 路径。"))
        assertCleanDescription(skill)
    }

    @Test
    fun `pdf with translations first resolves to its top-level fields`() {
        val skill = loadFixture("pdf")

        assertEquals("pdf", skill.name)
        assertTrue(skill.description.startsWith("Use this skill whenever the user wants to do anything with PDF files."))
        assertCleanDescription(skill)
        assertEquals("Proprietary. LICENSE.txt has complete terms", skill.metadata["license"])
    }

    @Test
    fun `web copy of the built-in guide parses tags version and author`() {
        val skill = loadFixture("braidrun-workflow-guide")

        assertEquals("braidrun-workflow-guide", skill.name)
        assertTrue(skill.description.startsWith("Current English guide for Braidrun Workflow YAML"))
        assertCleanDescription(skill)
        assertEquals("3.2.0", skill.version)
        assertEquals("braidrun", skill.author)
        assertEquals(listOf("guide", "workflow", "yaml", "cli", "mcp", "docker", "jev"), skill.tags)
    }

    @Test
    fun `classpath built-in guide parses through the same parser`(@TempDir tempDir: Path) {
        val loader = SkillLoader(tempDir, SkillsConfiguration(scanStandardPaths = false))
        val guide = loader.loadBuiltinSkills().single { it.name == "braidrun-workflow-guide" }

        assertTrue(guide.description.startsWith("Current English guide for Braidrun Workflow YAML"))
        assertCleanDescription(guide)
        assertNotNull(guide.version)
    }

    // -------------------------------------------------------------------------
    // Parser rules on synthetic frontmatter
    // -------------------------------------------------------------------------

    @Test
    fun `nested keys never set identity fields`() {
        val parsed = SkillFrontmatterParser.parse(
            """
            name: real-name
            description: Real description
            translations:
              zh:
                name: "中文名"
                description: 中文描述
            metadata:
              name: metadata-name
              description: metadata description
              tags: [nested]
            """.trimIndent()
        )

        assertEquals("real-name", parsed["name"])
        assertEquals("Real description", parsed["description"])
        assertFalse(parsed.containsKey("tags"))
        assertFalse(parsed.containsKey("translations"))
    }

    @Test
    fun `version and author come from metadata first then top level`() {
        val specStyle = SkillFrontmatterParser.parse(
            """
            name: s
            description: d
            version: "0.1"
            metadata:
              version: "2.0"
              author: meta-author
            """.trimIndent()
        )
        assertEquals("2.0", specStyle["version"])
        assertEquals("meta-author", specStyle["author"])

        val topLevelOnly = SkillFrontmatterParser.parse(
            """
            name: s
            description: d
            version: "0.1"
            author: top-author
            """.trimIndent()
        )
        assertEquals("0.1", topLevelOnly["version"])
        assertEquals("top-author", topLevelOnly["author"])
    }

    @Test
    fun `flow lists and nested metadata maps are normalized`() {
        val parsed = SkillFrontmatterParser.parse(
            """
            name: s
            description: d
            tags: [alpha, "beta"]
            metadata: {"openclaw": {"requires": {"bins": ["op"]}}, "level": 1.10}
            """.trimIndent()
        )

        assertEquals(listOf("alpha", "beta"), parsed["tags"])
        assertEquals("""{"requires":{"bins":["op"]}}""", parsed["openclaw"])
        assertEquals("1.10", parsed["level"])
    }

    @Test
    fun `invalid YAML falls back to the lenient line parser`() {
        val parsed = SkillFrontmatterParser.parse(
            """
            name: colon-skill
            description: Use this skill when: the user asks about PDFs
            """.trimIndent()
        )

        assertEquals("colon-skill", parsed["name"])
        assertEquals("Use this skill when: the user asks about PDFs", parsed["description"])
    }

    @Test
    fun `loadSkill maps parsed comma and sequence lists into tags`(@TempDir tempDir: Path) {
        val dir = tempDir.resolve("flow-tags")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("SKILL.md"),
            "---\nname: flow-tags\ndescription: d\ntags: [a, b]\ndependencies: x, y\n---\nBody"
        )

        val skill = SkillLoader(tempDir, SkillsConfiguration(scanStandardPaths = false)).loadSkill(dir)!!

        assertEquals(listOf("a", "b"), skill.tags)
        assertEquals(listOf("x", "y"), skill.dependencies)
    }
}
