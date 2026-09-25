package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `createWorkflowFromTemplate` is an agent tool: its template name and output path come from
 * the model. Template names stay inside the templates directory, and output stays inside the
 * agent's writable roots (`working_dir` / `output_dir`, else the process working directory).
 */
class WorkflowToolsTemplatePathTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var templatesDir: File
    private lateinit var workingDir: File
    private lateinit var outsideDir: File

    @BeforeEach
    fun setUp() {
        templatesDir = File(tempDir, "templates").apply { mkdirs() }
        workingDir = File(tempDir, "work").apply { mkdirs() }
        outsideDir = File(tempDir, "outside").apply { mkdirs() }
        File(templatesDir, "hello.yaml").writeText("name: hello\ndescription: {{topic}}\n")
        File(tempDir, "secret.yaml").writeText("api_key: do-not-read\n")
    }

    private fun tools(vararg extra: Pair<String, String>): WorkflowTools {
        val parameters = (listOf("workflow_templates_dir" to templatesDir.absolutePath) + extra)
            .map { (key, value) -> ConfigurationParameter(key, JsonPrimitive(value)) }
        return WorkflowTools(HttpAccess(), parameters)
    }

    private fun WorkflowTools.create(templateName: String, outputPath: String, variables: String = "") =
        runBlocking { createWorkflowFromTemplate(templateName, outputPath, variables) }

    private fun yamlFilesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    // ==================== templateName ====================

    @Test
    fun `template names with path separators or dot-dot are rejected before any file is read`() {
        val tools = tools("working_dir" to workingDir.absolutePath)

        listOf("../secret", "..", "sub/hello", "sub\\hello", "/etc/passwd", "hello..", "C:hello").forEach { name ->
            val result = tools.create(name, "out.yaml")
            assertContains(result, "Invalid template name", message = "template name '$name'")
        }
        assertEquals(emptyList(), yamlFilesUnder(workingDir))
    }

    @Test
    fun `blank template name is rejected`() {
        val result = tools("working_dir" to workingDir.absolutePath).create(" ", "out.yaml")

        assertContains(result, "Template name must not be blank")
    }

    @Test
    fun `plain template name still resolves inside the templates directory`() {
        val result = tools("working_dir" to workingDir.absolutePath).create("hello", "out.yaml", "topic=AI")

        assertTrue(result.startsWith("✅"), result)
        assertEquals("name: hello\ndescription: AI\n", File(workingDir, "out.yaml").readText())
    }

    // ==================== outputPath ====================

    @Test
    fun `relative output path resolves against working_dir`() {
        File(workingDir, "flows").mkdirs()

        val result = tools("working_dir" to workingDir.absolutePath).create("hello", "flows/new.yml", "topic=AI")

        assertTrue(result.startsWith("✅"), result)
        assertTrue(File(workingDir, "flows/new.yml").isFile)
    }

    @Test
    fun `absolute output path inside output_dir is allowed`() {
        val outputDir = File(tempDir, "output").apply { mkdirs() }
        val target = File(outputDir, "copy.yaml")

        val result = tools("working_dir" to workingDir.absolutePath, "output_dir" to outputDir.absolutePath)
            .create("hello", target.absolutePath)

        assertTrue(result.startsWith("✅"), result)
        assertTrue(target.isFile)
    }

    @Test
    fun `output paths escaping the working directory are rejected`() {
        val tools = tools("working_dir" to workingDir.absolutePath)

        listOf(
            File(outsideDir, "abs.yaml").absolutePath,
            "../outside/rel.yaml",
            "flows/../../outside/nested.yaml",
        ).forEach { path ->
            val result = tools.create("hello", path)
            assertContains(result, "outside the allowed directories", message = "output path '$path'")
        }
        assertEquals(emptyList(), yamlFilesUnder(outsideDir))
    }

    @Test
    fun `output through a symlink that leaves the working directory is rejected`() {
        Files.createSymbolicLink(File(workingDir, "link").toPath(), outsideDir.toPath())

        val result = tools("working_dir" to workingDir.absolutePath).create("hello", "link/escaped.yaml")

        assertContains(result, "outside the allowed directories")
        assertFalse(File(outsideDir, "escaped.yaml").exists())
    }

    @Test
    fun `non yaml output files are rejected even inside the working directory`() {
        val tools = tools("working_dir" to workingDir.absolutePath)

        listOf("hook.sh", ".bashrc", "config", "flow.yaml.sh", "flows/").forEach { path ->
            val result = tools.create("hello", path)
            assertContains(result, "must end with .yaml or .yml", message = "output path '$path'")
        }
        assertEquals(emptyList(), yamlFilesUnder(workingDir))
    }

    @Test
    fun `without working_dir output is confined to the process working directory`() {
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        val tools = tools()

        val outside = tools.create("hello", File(outsideDir, "cli.yaml").absolutePath)
        assertContains(outside, "outside the allowed directories")
        assertFalse(File(outsideDir, "cli.yaml").exists())

        val relativeDir = File(cwd, "build/tmp/workflow-tools-template-path-test").apply { mkdirs() }
        try {
            val inside = tools.create("hello", "build/tmp/workflow-tools-template-path-test/cli.yaml", "topic=CLI")
            assertTrue(inside.startsWith("✅"), inside)
            assertEquals("name: hello\ndescription: CLI\n", File(relativeDir, "cli.yaml").readText())
        } finally {
            relativeDir.deleteRecursively()
        }
    }

    // ==================== variables ====================

    @Test
    fun `multi-line variable values are rejected so they cannot inject YAML`() {
        val tools = tools("working_dir" to workingDir.absolutePath)

        val result = tools.create("hello", "out.yaml", "topic=AI\nworkflow:\n  - step: pwn")

        assertContains(result, "Variable 'topic' must be a single line")
        assertFalse(File(workingDir, "out.yaml").exists())
    }
}
