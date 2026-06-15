package com.fartech.agents.workflow

import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.commons.createSkillManager
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WorkflowExecutorRuntimeParameterTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `protected runtime parameters override agent supplied values`() {
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("sandbox_strict", JsonPrimitive(true)),
                ConfigurationParameter("user_id", JsonPrimitive("actor-9"))
            ),
            enableMonitoring = false
        )

        val params = mutableMapOf<String, JsonElement>(
            "subprocess_mode" to JsonPrimitive("native"),
            "sandbox_strict" to JsonPrimitive(false),
            "user_id" to JsonPrimitive("intruder")
        )

        WorkflowExecutor::class.java.getDeclaredMethod(
            "enforceProtectedRuntimeParameters",
            MutableMap::class.java
        ).apply { isAccessible = true }
            .invoke(executor, params)

        assertEquals("docker", (params.getValue("subprocess_mode") as JsonPrimitive).content)
        assertEquals("true", (params.getValue("sandbox_strict") as JsonPrimitive).content)
        assertEquals("actor-9", (params.getValue("user_id") as JsonPrimitive).content)
    }

    @Test
    fun `injectWorkflowRuntimeParameters adds execution context and isolation paths`(@TempDir tempDir: Path) {
        val baseDir = tempDir.resolve("runs").toFile()
        val cacheDir = tempDir.resolve("cache").toFile()
        val historyDir = tempDir.resolve("history").toFile()
        val skillsDir = tempDir.resolve("skills").toFile().canonicalFile
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val params = mutableMapOf<String, JsonElement>()
        val config = DirectoryIsolationConfig(
            enabled = true,
            baseDir = baseDir.absolutePath,
            sharedCacheDir = cacheDir.absolutePath,
            sharedHistoryDir = historyDir.absolutePath,
            sharedSkillsDir = skillsDir.absolutePath
        )

        WorkflowExecutor::class.java.getDeclaredMethod(
            "injectWorkflowRuntimeParameters",
            MutableMap::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            DirectoryIsolationConfig::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
            .invoke(executor, params, "session-1", "exec-1", "plan", config, "worker", "demo-workflow")

        assertEquals("session-1", (params.getValue("session_id") as JsonPrimitive).content)
        assertEquals("exec-1", (params.getValue("execution_id") as JsonPrimitive).content)
        assertEquals("plan", (params.getValue("step_name") as JsonPrimitive).content)
        assertEquals(skillsDir.absolutePath, (params.getValue("shared_skills_dir") as JsonPrimitive).content)
        assertEquals(skillsDir.absolutePath, (params.getValue("skills_dir") as JsonPrimitive).content)

        val workingDir = File((params.getValue("working_dir") as JsonPrimitive).content)
        val outputDir = File((params.getValue("output_dir") as JsonPrimitive).content)
        val persistenceDir = File((params.getValue("persistence_storage_root") as JsonPrimitive).content)

        assertTrue(workingDir.isDirectory)
        assertTrue(outputDir.isDirectory)
        assertTrue(persistenceDir.isDirectory)
        assertTrue(workingDir.absolutePath.startsWith(baseDir.absolutePath))
        assertTrue(outputDir.absolutePath.startsWith(baseDir.canonicalPath))
        assertTrue(persistenceDir.absolutePath.startsWith(baseDir.canonicalPath))
        assertEquals(cacheDir.canonicalPath, (params.getValue("file_cache_storage") as JsonPrimitive).content)
        assertEquals(historyDir.canonicalPath, (params.getValue("history_storage_root") as JsonPrimitive).content)
    }

    @Test
    fun `injectWorkflowRuntimeParameters makes docker mounted dirs writable for sandbox user`(@TempDir tempDir: Path) {
        val baseDir = tempDir.resolve("runs").toFile()
        val cacheDir = tempDir.resolve("cache").toFile()
        val historyDir = tempDir.resolve("history").toFile()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("actor-9"))
            ),
            enableMonitoring = false
        )
        val params = mutableMapOf<String, JsonElement>()
        val config = DirectoryIsolationConfig(
            enabled = true,
            baseDir = baseDir.absolutePath,
            sharedCacheDir = cacheDir.absolutePath,
            sharedHistoryDir = historyDir.absolutePath,
            sharedSkillsDir = skillsDir.absolutePath
        )

        WorkflowExecutor::class.java.getDeclaredMethod(
            "injectWorkflowRuntimeParameters",
            MutableMap::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            DirectoryIsolationConfig::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
            .invoke(executor, params, "session-1", "exec-1", "plan", config, "worker", "demo-workflow")

        assertContainerWritableDirectory(File((params.getValue("working_dir") as JsonPrimitive).content))
        assertContainerWritableDirectory(File((params.getValue("output_dir") as JsonPrimitive).content))
        assertContainerWritableDirectory(File((params.getValue("persistence_storage_root") as JsonPrimitive).content))
        assertContainerWritableDirectory(File((params.getValue("file_cache_storage") as JsonPrimitive).content))
        assertContainerWritableDirectory(File((params.getValue("history_storage_root") as JsonPrimitive).content))
    }

    @Test
    fun `materializeSkillDirectory reuses staged directory instead of deleting and recopying`(@TempDir tempDir: Path) {
        val sourceRoot = tempDir.resolve("skills-source")
        Files.createDirectories(sourceRoot.resolve("demo-skill"))
        Files.writeString(
            sourceRoot.resolve("demo-skill").resolve("SKILL.md"),
            """
            ---
            name: demo-skill
            description: Demo
            ---
            Demo body
            """.trimIndent()
        )
        val destinationRoot = tempDir.resolve("runtime").toFile()
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )

        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "materializeSkillDirectory",
            String::class.java,
            File::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val firstPath = method.invoke(executor, sourceRoot.toString(), destinationRoot, "configured") as String
        val stagedDir = File(firstPath)
        val sentinel = File(stagedDir, "sentinel.txt").apply { writeText("keep-me") }

        val secondPath = method.invoke(executor, sourceRoot.toString(), destinationRoot, "configured") as String

        assertEquals(firstPath, secondPath)
        assertTrue(stagedDir.isDirectory)
        assertTrue(sentinel.isFile)
        assertEquals("keep-me", sentinel.readText())
        assertTrue(File(stagedDir, ".materialized-skills.json").isFile)
    }

    @Test
    fun `materialized runtime skills are used by skill manager and expose staged attachments`(@TempDir tempDir: Path) {
        val sourceRoot = tempDir.resolve("skills-source")
        val skillDir = sourceRoot.resolve("demo-skill")
        Files.createDirectories(skillDir)
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            """
            ---
            name: demo-skill
            description: Demo skill
            ---
            Demo body
            """.trimIndent()
        )
        Files.writeString(
            skillDir.resolve("config-template.json"),
            """{"apiKey":{"type":"string"}}"""
        )

        val baseDir = tempDir.resolve("runs").toFile()
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("user_id", JsonPrimitive("actor-9"))
            ),
            enableMonitoring = false
        )
        val params = mutableMapOf<String, JsonElement>(
            "tool_set" to JsonArray(listOf(JsonPrimitive("skill_tools"))),
            "skills_config" to json.encodeToJsonElement(
                SkillsConfiguration.serializer(),
                SkillsConfiguration(
                    skillsPath = sourceRoot.toString(),
                    scanStandardPaths = false,
                    builtinSkillsEnabled = false,
                    materializeRuntimeSkills = true
                )
            )
        )
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = baseDir.absolutePath,
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
            .invoke(executor, params, "exec-1", "plan", isolation, "worker", "demo-workflow")

        val stagedSkillsDir = File((params.getValue("skills_dir") as JsonPrimitive).content)
        assertTrue(stagedSkillsDir.isDirectory)
        assertTrue(stagedSkillsDir.absolutePath.contains(".skills-runtime"))
        assertTrue(Files.exists(stagedSkillsDir.toPath().resolve("demo-skill").resolve("config-template.json")))

        val rewrittenConfig = json.decodeFromJsonElement(
            SkillsConfiguration.serializer(),
            params.getValue("skills_config")
        )
        assertEquals(stagedSkillsDir.absolutePath, rewrittenConfig.skillsPath)
        assertFalse(rewrittenConfig.scanStandardPaths)

        val manager = assertNotNull(
            createSkillManager(
                listOf(
                    ConfigurationParameter("tool_set", params.getValue("tool_set")),
                    ConfigurationParameter("skills_config", params.getValue("skills_config"))
                )
            )
        )
        val activated = manager.activateSkill("demo-skill")

        assertTrue(activated.contains("config-template.json"))
        assertTrue(
            manager.getSkill("demo-skill")?.location?.startsWith(stagedSkillsDir.absolutePath) == true,
            "skill should be loaded from the staged runtime directory"
        )
    }

    @Test
    fun `disabled skills config skips materialized runtime skills`(@TempDir tempDir: Path) {
        val sourceRoot = tempDir.resolve("skills-source")
        Files.createDirectories(sourceRoot.resolve("demo-skill"))
        Files.writeString(
            sourceRoot.resolve("demo-skill").resolve("SKILL.md"),
            """
            ---
            name: demo-skill
            description: Demo skill
            ---
            Demo body
            """.trimIndent()
        )

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val params = mutableMapOf<String, JsonElement>(
            "tool_set" to JsonArray(listOf(JsonPrimitive("skill_tools"))),
            "skills_config" to json.encodeToJsonElement(
                SkillsConfiguration.serializer(),
                SkillsConfiguration(
                    enabled = false,
                    skillsPath = sourceRoot.toString(),
                    scanStandardPaths = false,
                    builtinSkillsEnabled = false,
                    materializeRuntimeSkills = true
                )
            )
        )
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = sourceRoot.toString()
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
            .invoke(executor, params, "exec-1", "plan", isolation, "worker", "demo-workflow")

        assertFalse(params.containsKey("skills_dir"))
        assertFalse(params.containsKey("shared_skills_dir"))
    }

    private fun assertContainerWritableDirectory(directory: File) {
        assumeTrue(Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(directory.toPath())
        assertTrue(PosixFilePermission.OTHERS_READ in permissions)
        assertTrue(PosixFilePermission.OTHERS_WRITE in permissions)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in permissions)
    }
}
