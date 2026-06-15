package com.fartech.agents.workflow

import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for the sandboxed code step execution path in [WorkflowExecutor].
 *
 * Uses reflection to access private methods since the full executeWorkflow()
 * flow requires LLM connections. Tests verify:
 * - buildFinalCodeScript correctly injects preamble
 * - buildCodeStepEnvironment injects WF_VAR_*, STEP_OUTPUT_*, STEP_INPUTS
 * - resolveCodeStepImageHint returns correct Docker image hints
 * - Constructor accepts codeStepExecutor parameter
 */
class CodeStepSandboxTest {

    private fun minimalParameters(): List<ConfigurationParameter> = listOf(
        ConfigurationParameter("subprocess_mode", JsonPrimitive("native")),
        ConfigurationParameter("user_id", JsonPrimitive("test-user"))
    )

    private fun createExecutor(
        codeStepExecutor: SubprocessExecutor? = null
    ): WorkflowExecutor = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = minimalParameters(),
        enableMonitoring = false,
        codeStepExecutor = codeStepExecutor
    )

    // =========================================================================
    // Constructor accepts codeStepExecutor
    // =========================================================================

    @Test
    fun `constructor accepts null codeStepExecutor for backward compat`() {
        val executor = createExecutor(codeStepExecutor = null)
        assertNotNull(executor)
    }

    @Test
    fun `constructor accepts NativeSubprocessExecutor`() {
        val executor = createExecutor(codeStepExecutor = NativeSubprocessExecutor())
        assertNotNull(executor)
    }

    // =========================================================================
    // buildFinalCodeScript (via reflection)
    // =========================================================================

    @Nested
    inner class BuildFinalCodeScriptTest {

        @Test
        fun `inline script without preamble`() {
            val executor = createExecutor()
            val step = WorkflowStep(
                step = "test",
                code = CodeStepConfig(language = "python", script = "print('hello')")
            )
            val workflow = WorkflowDefinition(
                name = "test", agents = emptyMap(), workflow = listOf(step)
            )

            val method = WorkflowExecutor::class.java.getDeclaredMethod(
                "buildFinalCodeScript",
                WorkflowStep::class.java,
                WorkflowDefinition::class.java,
                CodeStepConfig::class.java
            )
            method.isAccessible = true
            val result = method.invoke(executor, step, workflow, step.code) as String

            assertEquals("print('hello')", result)
        }

        @Test
        fun `inline preamble is prepended`() {
            val executor = createExecutor()
            val step = WorkflowStep(
                step = "test",
                code = CodeStepConfig(language = "python", script = "main()")
            )
            val workflow = WorkflowDefinition(
                name = "test",
                agents = emptyMap(),
                workflow = listOf(step),
                codePreamble = mapOf("python" to CodePreambleConfig(inline = "# preamble"))
            )

            val method = WorkflowExecutor::class.java.getDeclaredMethod(
                "buildFinalCodeScript",
                WorkflowStep::class.java,
                WorkflowDefinition::class.java,
                CodeStepConfig::class.java
            )
            method.isAccessible = true
            val result = method.invoke(executor, step, workflow, step.code) as String

            assertTrue(result.startsWith("# preamble"), "Result: $result")
            assertTrue(result.endsWith("main()"), "Result: $result")
        }
    }

    // =========================================================================
    // buildCodeStepEnvironment (via reflection)
    // =========================================================================

    @Nested
    inner class BuildCodeStepEnvironmentTest {

        @Test
        fun `injects WF_VAR and STEP_OUTPUT and STEP_INPUTS`() {
            val executor = createExecutor()
            val context = WorkflowExecutionContext(
                workflowName = "test",
                executionId = "exec-1",
                variables = ConcurrentHashMap(mapOf("my_key" to "my_value" as Any)),
                stepOutputs = ConcurrentHashMap(mapOf("step_a" to "output_a"))
            )

            val method = WorkflowExecutor::class.java.getDeclaredMethod(
                "buildCodeStepEnvironment",
                WorkflowExecutionContext::class.java
            )
            method.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val env = method.invoke(executor, context) as Map<String, String>

            assertEquals("my_value", env["WF_VAR_MY_KEY"])
            assertEquals("output_a", env["STEP_OUTPUT_STEP_A"])
            assertTrue(env.containsKey("STEP_INPUTS"))
            assertTrue(env["STEP_INPUTS"]!!.contains("step_a"))
        }
    }

    // =========================================================================
    // resolveCodeStepImageHint (via reflection)
    // =========================================================================

    @Nested
    inner class ImageHintTest {

        @Test
        fun `python maps to python hint`() {
            val executor = createExecutor()
            val hint = invokeImageHint(executor, "python")
            assertEquals("python", hint)
        }

        @Test
        fun `javascript maps to node hint`() {
            val executor = createExecutor()
            assertEquals("node", invokeImageHint(executor, "javascript"))
        }

        @Test
        fun `typescript maps to node hint`() {
            val executor = createExecutor()
            assertEquals("node", invokeImageHint(executor, "typescript"))
        }

        @Test
        fun `bash maps to shell hint`() {
            val executor = createExecutor()
            assertEquals("shell", invokeImageHint(executor, "bash"))
        }

        @Test
        fun `unknown language maps to shell hint`() {
            val executor = createExecutor()
            assertEquals("shell", invokeImageHint(executor, "lua"))
        }

        private fun invokeImageHint(executor: WorkflowExecutor, language: String): String {
            val method = WorkflowExecutor::class.java.getDeclaredMethod(
                "resolveCodeStepImageHint",
                String::class.java
            )
            method.isAccessible = true
            return method.invoke(executor, language) as String
        }
    }

    @Nested
    inner class RuntimeParameterInjectionTest {

        @Test
        fun `injectWorkflowRuntimeParameters uses user scoped directories and shared roots`(@TempDir tempDir: Path) {
            val executor = createExecutor()
            val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
            val isolation = DirectoryIsolationConfig(
                enabled = true,
                baseDir = tempDir.resolve("runs").toString(),
                sharedSkillsDir = skillsDir.absolutePath,
                sharedCacheDir = tempDir.resolve("cache").toString(),
                sharedHistoryDir = tempDir.resolve("history").toString()
            )
            val target = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()

            val method = WorkflowExecutor::class.java.getDeclaredMethod(
                "injectWorkflowRuntimeParameters",
                MutableMap::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                DirectoryIsolationConfig::class.java,
                String::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(
                executor,
                target,
                "session-1",
                "exec-1",
                "collect",
                isolation,
                "agent-a",
                "workflow-a"
            )

            assertEquals(skillsDir.absolutePath, target.getValue("shared_skills_dir").jsonPrimitive.content)
            assertEquals(skillsDir.absolutePath, target.getValue("skills_dir").jsonPrimitive.content)
            assertEquals(
                File(tempDir.resolve("cache").toString()).canonicalPath,
                target.getValue("file_cache_storage").jsonPrimitive.content
            )
            assertEquals(
                File(tempDir.resolve("history").toString()).canonicalPath,
                target.getValue("history_storage_root").jsonPrimitive.content
            )
            assertTrue(target.getValue("working_dir").jsonPrimitive.content.contains("/test-user/"))
            assertTrue(target.getValue("output_dir").jsonPrimitive.content.contains("/test-user/"))
            assertTrue(target.getValue("persistence_storage_root").jsonPrimitive.content.contains("/test-user/"))
        }

        @Test
        fun `injectWorkflowRuntimeParameters keeps shared roots when isolation disabled`(@TempDir tempDir: Path) {
            val executor = createExecutor()
            val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
            val isolation = DirectoryIsolationConfig(
                enabled = false,
                sharedSkillsDir = skillsDir.absolutePath,
                sharedCacheDir = tempDir.resolve("cache").toString(),
                sharedHistoryDir = tempDir.resolve("history").toString()
            )
            val target = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()

            val method = WorkflowExecutor::class.java.getDeclaredMethod(
                "injectWorkflowRuntimeParameters",
                MutableMap::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                DirectoryIsolationConfig::class.java,
                String::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(
                executor,
                target,
                "session-1",
                "exec-1",
                "collect",
                isolation,
                "agent-a",
                "workflow-a"
            )

            assertEquals(skillsDir.absolutePath, target.getValue("shared_skills_dir").jsonPrimitive.content)
            assertEquals(skillsDir.absolutePath, target.getValue("skills_dir").jsonPrimitive.content)
            assertNotNull(target["file_cache_storage"])
            assertNotNull(target["history_storage_root"])
            assertNull(target["working_dir"])
            assertNull(target["output_dir"])
            assertNull(target["persistence_storage_root"])
        }
    }
}
