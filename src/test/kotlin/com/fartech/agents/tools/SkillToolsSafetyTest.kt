package com.fartech.agents.tools

import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.workflow.WorkflowHostPolicy
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mutating skill tools are reachable by a model, so their arguments are hostile input:
 * `clearSkillCache` must only ever delete one entry inside the cache (B2) and
 * `downloadSkillFromGit` must only clone https remotes into the cache (B3).
 */
class SkillToolsSafetyTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var parentDir: File
    private lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        WorkflowHostPolicy.resetForTests()
        parentDir = File(tempDir, "parent").apply { mkdirs() }
        cacheDir = File(parentDir, "cache").apply { mkdirs() }
        File(parentDir, "sibling.txt").writeText("outside the cache")
    }

    @AfterEach
    fun tearDown() {
        WorkflowHostPolicy.resetForTests()
    }

    private fun adminTools(): SkillAdminTools {
        val config = SkillsConfiguration(
            skillsPath = cacheDir.absolutePath,
            scanStandardPaths = false,
            builtinSkillsEnabled = false
        )
        val parameters = listOf(
            ConfigurationParameter("skills_config", Json.encodeToJsonElement(SkillsConfiguration.serializer(), config))
        )
        return SkillAdminTools(SkillTools(parameters, HttpAccess()))
    }

    private fun cachedSkill(dirName: String, skillName: String = dirName.substringBefore('@')): File =
        File(cacheDir, dirName).apply {
            mkdirs()
            File(this, "SKILL.md").writeText("---\nname: $skillName\ndescription: $skillName skill\n---\nBody\n")
        }

    // ==================== B2: clearSkillCache ====================

    @Test
    fun `clearSkillCache refuses dot-dot and leaves the cache parent untouched`() {
        val cached = cachedSkill("pdf@1.0.0")

        val result = adminTools().clearSkillCache("..")

        assertContains(result, "invalid cacheKey")
        assertTrue(File(parentDir, "sibling.txt").isFile, "the parent of the cache must survive")
        assertTrue(cached.isDirectory)
        assertTrue(cacheDir.isDirectory)
    }

    @Test
    fun `clearSkillCache refuses keys that address the cache itself or a path`() {
        val cached = cachedSkill("pdf@1.0.0")
        val tools = adminTools()

        for (key in listOf(".", "a/b", "../parent", "..\\parent", "pdf@1.0.0/..", "/etc")) {
            assertContains(tools.clearSkillCache(key), "invalid cacheKey", message = "key '$key'")
        }
        assertTrue(cached.isDirectory)
        assertTrue(File(parentDir, "sibling.txt").isFile)
    }

    @Test
    fun `clearSkillCache without a key never wipes the whole cache`() {
        val cached = cachedSkill("pdf@1.0.0")
        val tools = adminTools()

        assertContains(tools.clearSkillCache(""), "cacheKey is required")
        assertContains(tools.clearSkillCache("   "), "cacheKey is required")
        assertTrue(cached.isDirectory)
    }

    @Test
    fun `clearSkillCache removes exactly the named entry`() {
        val target = cachedSkill("pdf@1.0.0")
        val other = cachedSkill("docx@2.0.0")

        val result = adminTools().clearSkillCache("pdf@1.0.0")

        assertContains(result, "Cleared cached skill")
        assertFalse(target.exists())
        assertTrue(other.isDirectory)
    }

    @Test
    fun `clearSkillCache still resolves a bare slug to its versioned entry`() {
        val target = cachedSkill("pdf@1.0.0")
        val other = cachedSkill("docx@2.0.0")

        assertContains(adminTools().clearSkillCache("pdf"), "Cleared cached skill")
        assertFalse(target.exists())
        assertTrue(other.isDirectory)
    }

    @Test
    fun `clearSkillCache refuses an entry that links outside the cache`() {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val precious = File(outside, "precious.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(cacheDir, "evil").toPath(), outside.toPath())

        val result = adminTools().clearSkillCache("evil")

        assertContains(result, "outside the skills cache")
        assertTrue(precious.isFile)
    }

    @Test
    fun `clearSkillCache does not follow links inside the deleted entry`() {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val precious = File(outside, "precious.txt").apply { writeText("keep") }
        val cached = cachedSkill("git_repo_1")
        Files.createSymbolicLink(File(cached, "escape").toPath(), outside.toPath())

        assertContains(adminTools().clearSkillCache("git_repo_1"), "Cleared cached skill")

        assertFalse(cached.exists())
        assertTrue(precious.isFile, "a link inside a cached skill must be removed, not followed")
    }

    // ==================== B3: downloadSkillFromGit ====================

    @Test
    fun `only https repository urls are accepted`() {
        val rejected = listOf(
            "file:///etc",
            "/tmp/some-repo",
            "./relative-repo",
            "-uhttps://example.com/x.git",
            "--upload-pack=touch /tmp/pwned",
            "ext::sh -c touch% /tmp/pwned",
            "ssh://git@github.com/user/skill.git",
            "git@github.com:user/skill.git",
            "http://github.com/user/skill.git",
            "https://",
            "https://github.com/user/skill repo.git",
            ""
        )
        for (url in rejected) {
            assertFailsWith<IllegalArgumentException>("url '$url' must be rejected") {
                SkillAdminTools.requireHttpsRepositoryUrl(url)
            }
        }
        assertEquals(
            "https://github.com/user/skill.git",
            SkillAdminTools.requireHttpsRepositoryUrl("  https://github.com/user/skill.git ")
        )
    }

    @Test
    fun `git clone command ends option parsing before the url`() {
        val destination = File(cacheDir, "git_skill_1")
        val command = SkillAdminTools.gitCloneCommand("https://github.com/user/skill.git", destination)

        val separator = command.indexOf("--")
        assertEquals(listOf("https://github.com/user/skill.git", destination.absolutePath), command.drop(separator + 1))
        assertTrue(command.indexOf("clone") < separator)
    }

    @Test
    fun `git clone command disables symlinks submodules and non-https transports`() {
        val command = SkillAdminTools.gitCloneCommand("https://github.com/user/skill.git", File(cacheDir, "g"))
        val clone = command.indexOf("clone")
        val globalConfig = command.subList(1, clone)

        assertEquals(
            listOf(
                "-c", "core.symlinks=false",
                "-c", "protocol.allow=never",
                "-c", "protocol.https.allow=always"
            ),
            globalConfig
        )
        assertEquals(listOf("--depth", "1", "--no-recurse-submodules", "--"), command.subList(clone + 1, clone + 5))
    }

    @Test
    fun `downloadSkillFromGit rejects a local repository before cloning anything`() {
        val tools = adminTools()

        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking { tools.downloadSkillFromGit("file://${tempDir.absolutePath}") }
        }

        assertContains(error.message.orEmpty(), "https://")
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.startsWith("git_") }, "nothing may be cloned")
    }

    @Test
    fun `subdirectory must stay inside the clone`() {
        val cloneRoot = File(cacheDir, "git_skill_1").apply { mkdirs() }
        File(cloneRoot, "skills/pdf").mkdirs()
        val outside = File(tempDir, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(cloneRoot, "escape").toPath(), outside.toPath())

        assertEquals(cloneRoot, SkillAdminTools.resolveSkillSubdirectory(cloneRoot, ""))
        assertEquals(
            File(cloneRoot, "skills/pdf").canonicalFile,
            SkillAdminTools.resolveSkillSubdirectory(cloneRoot, "skills/pdf")
        )
        for (subdirectory in listOf("..", "../..", "skills/../../..", "/etc", "escape")) {
            assertFailsWith<IllegalArgumentException>("subdirectory '$subdirectory' must be rejected") {
                SkillAdminTools.resolveSkillSubdirectory(cloneRoot, subdirectory)
            }
        }
    }
}
