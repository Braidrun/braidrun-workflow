package com.fartech.agents.workflow

import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.commons.createSkillManager
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-execution `.skills-runtime/` copy is readable from the sandbox, so it may only hold
 * the skills the agent's scoped `skills_config` loads — never other tenants' installs, a
 * skill's `.state/` credentials, `.git`, or anything reached through a symbolic link.
 */
class WorkflowExecutorSkillMaterializationFilterTest {

    private val json = Json { encodeDefaults = true }

    private fun writeSkill(dir: Path, name: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: $name\ndescription: $name skill\n---\nBody\n")
    }

    /** Runs the real agent-runtime staging path and returns the staged primary skills dir. */
    private fun materialize(
        tempDir: Path,
        sourceRoot: Path,
        config: SkillsConfiguration,
        agentName: String = "worker"
    ): File {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(ConfigurationParameter("user_id", JsonPrimitive("actor-1"))),
            enableMonitoring = false
        )
        val params = mutableMapOf<String, JsonElement>(
            "tool_set" to JsonArray(listOf(JsonPrimitive("skill_tools"))),
            "skills_config" to json.encodeToJsonElement(SkillsConfiguration.serializer(), config)
        )
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = sourceRoot.toString(),
            sharedCacheDir = tempDir.resolve("cache").toString(),
            sharedHistoryDir = tempDir.resolve("history").toString()
        )
        WorkflowExecutor::class.java.getDeclaredMethod(
            "prepareMaterializedSkillsRuntime",
            MutableMap::class.java,
            String::class.java,
            String::class.java,
            DirectoryIsolationConfig::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
            .invoke(executor, params, "exec-1", "plan", isolation, agentName, "demo-workflow")
        return File((params.getValue("skills_dir") as JsonPrimitive).content)
    }

    private fun sharedSkillsTree(tempDir: Path): Path {
        val sourceRoot = tempDir.resolve("skills-source")
        val allowed = sourceRoot.resolve("allowed")
        writeSkill(allowed, "allowed")
        Files.createDirectories(allowed.resolve("references"))
        Files.writeString(allowed.resolve("references/guide.md"), "guide")
        Files.createDirectories(allowed.resolve("scripts"))
        Files.writeString(allowed.resolve("scripts/run.sh"), "#!/bin/sh\necho ok\n")
        allowed.resolve("scripts/run.sh").toFile().setExecutable(true)
        Files.createDirectories(allowed.resolve(".state"))
        Files.writeString(allowed.resolve(".state/config.json"), """{"apiKey":"secret"}""")
        Files.createDirectories(allowed.resolve(".git"))
        Files.writeString(allowed.resolve(".git/config"), "[core]")
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("host-secret.txt"), "host")
        Files.createSymbolicLink(allowed.resolve("escape"), outside)
        Files.createSymbolicLink(allowed.resolve("secret-link.txt"), outside.resolve("host-secret.txt"))
        writeSkill(allowed.resolve("nested-disabled"), "nested-disabled")

        writeSkill(sourceRoot.resolve("denied"), "denied")
        writeSkill(sourceRoot.resolve("other-user@zip"), "other-user-skill")
        Files.writeString(sourceRoot.resolve("helper.py"), "print('shared helper')")
        return sourceRoot
    }

    private fun scopedConfig(sourceRoot: Path) = SkillsConfiguration(
        skillsPath = sourceRoot.toString(),
        scanStandardPaths = false,
        builtinSkillsEnabled = false,
        disabledSkills = listOf("denied", "nested-disabled"),
        notLoadSkills = listOf("other-user-skill")
    )

    private fun File.has(relative: String) = Files.exists(toPath().resolve(relative), LinkOption.NOFOLLOW_LINKS)

    @Test
    fun `only skills the scoped config loads are staged, without state, git or links`(@TempDir tempDir: Path) {
        val sourceRoot = sharedSkillsTree(tempDir)

        val staged = materialize(tempDir, sourceRoot, scopedConfig(sourceRoot))

        assertTrue(staged.has("allowed/SKILL.md"))
        assertTrue(staged.has("allowed/references/guide.md"))
        assertTrue(staged.resolve("allowed/scripts/run.sh").canExecute(), "script bits are preserved")
        assertTrue(staged.has("helper.py"), "operator files at the root are kept")

        assertFalse(staged.has("allowed/.state"), ".state holds runtime credentials")
        assertFalse(staged.has("allowed/.git"))
        assertFalse(staged.has("allowed/escape"), "directory links are not staged")
        assertFalse(staged.has("allowed/secret-link.txt"), "file links are not staged")
        assertFalse(staged.has("allowed/nested-disabled"), "a disabled nested skill is not staged")
        assertFalse(staged.has("denied"), "an administrator-disabled skill is not staged")
        assertFalse(staged.has("other-user@zip"), "another user's install is not staged")
        assertTrue(Files.exists(tempDir.resolve("outside/host-secret.txt")))
    }

    @Test
    fun `a whitelist stages only the allowlisted skills`(@TempDir tempDir: Path) {
        val sourceRoot = sharedSkillsTree(tempDir)
        val config = scopedConfig(sourceRoot).copy(
            skillWhitelistMode = true,
            enabledSkills = listOf("denied"),
            disabledSkills = emptyList()
        )

        val staged = materialize(tempDir, sourceRoot, config)

        assertTrue(staged.has("denied/SKILL.md"))
        assertFalse(staged.has("allowed"))
        assertFalse(staged.has("other-user@zip"))
    }

    @Test
    fun `agents with different scopes in one execution get separate staged copies`(@TempDir tempDir: Path) {
        val sourceRoot = sharedSkillsTree(tempDir)
        val narrow = scopedConfig(sourceRoot)
        val wide = narrow.copy(disabledSkills = listOf("nested-disabled"))

        val narrowDir = materialize(tempDir, sourceRoot, narrow, agentName = "narrow")
        val wideDir = materialize(tempDir, sourceRoot, wide, agentName = "wide")
        val narrowAgain = materialize(tempDir, sourceRoot, narrow, agentName = "narrow-2")

        assertNotEquals(narrowDir, wideDir)
        assertEquals(narrowDir, narrowAgain, "the same selection is reused within the execution")
        assertFalse(narrowDir.has("denied"), "the wider agent must not widen the narrow agent's copy")
        assertTrue(wideDir.has("denied/SKILL.md"))
    }

    @Test
    fun `the staged copy is what the runtime skill manager loads`(@TempDir tempDir: Path) {
        val sourceRoot = sharedSkillsTree(tempDir)
        val staged = materialize(tempDir, sourceRoot, scopedConfig(sourceRoot))
        val stagedConfig = scopedConfig(sourceRoot).copy(skillsPath = staged.absolutePath)

        val manager = assertNotNull(
            createSkillManager(
                listOf(
                    ConfigurationParameter(
                        "skills_config",
                        json.encodeToJsonElement(SkillsConfiguration.serializer(), stagedConfig)
                    )
                )
            )
        )

        assertEquals(listOf("allowed"), manager.getAllSkills().map { it.name })
    }
}
