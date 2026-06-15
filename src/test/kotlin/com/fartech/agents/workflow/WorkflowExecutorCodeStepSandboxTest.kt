package com.fartech.agents.workflow

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowExecutorCodeStepSandboxTest {

    private class CapturingExecutor : SubprocessExecutor {
        var lastRequest: SubprocessExecutor.ExecRequest? = null

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            lastRequest = request
            return SubprocessExecutor.ExecResult(
                exitCode = 0,
                stdout = "sandbox-ok",
                stderr = "",
                durationMs = 1
            )
        }
    }

    @Test
    fun `sandboxed code step preserves full interpreter command and docker path compatibility`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply {
            mkdirs()
            File(this, "braidrun-web/scripts").mkdirs()
            File(this, "braidrun-web/SKILL.md").writeText("# braidrun-web")
            File(this, "braidrun-web/scripts/cli.ts").writeText("console.log('ok')")
        }
        val canonicalSkillsDir = skillsDir.canonicalFile
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = canonicalSkillsDir.absolutePath,
            sharedCacheDir = tempDir.resolve("cache").toString(),
            sharedHistoryDir = tempDir.resolve("history").toString()
        )
        val workflow = WorkflowDefinition(
            name = "sandboxed-code-step",
            agents = emptyMap(),
            variables = mapOf(
                "web_skill_path" to "./skills/braidrun-web"
            ),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "collect data",
                    code = CodeStepConfig(
                        language = "typescript",
                        script = "console.log('hello from sandbox')"
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("user-42")),
                ConfigurationParameter(
                    WorkflowRuntimeParameterKeys.codeInterpreter("typescript"),
                    JsonPrimitive("npx tsx")
                )
            ),
            enableMonitoring = false,
            codeStepExecutor = capture
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-123")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertEquals(listOf("npx", "tsx", "wf_code_collect_data.ts"), request.command)
        assertEquals("node", request.imageHint)

        val expectedWorkingDir = File(
            isolation.getWorkingDir("exec-123", "collect data", "default", workflow.name, "user-42")
        ).absolutePath
        val expectedOutputDir = File(
            isolation.getOutputDir("exec-123", "collect data", "default", workflow.name, "user-42")
        ).canonicalPath
        val expectedPersistenceDir = File(
            isolation.getPersistenceDir("exec-123", "collect data", "default", workflow.name, "user-42")
        ).canonicalPath

        assertEquals(expectedWorkingDir, request.workingDir.absolutePath)
        assertEquals("/workspace", request.env.getValue("WF_VAR_WORKING_DIR"))
        assertEquals(expectedOutputDir, request.env.getValue("WF_VAR_OUTPUT_DIR"))
        assertEquals(expectedOutputDir, request.env.getValue("WF_VAR_OUTPUT_DIR_ABS"))
        assertEquals(expectedPersistenceDir, request.env.getValue("WF_VAR_PERSISTENCE_STORAGE_ROOT"))
        assertEquals(canonicalSkillsDir.absolutePath, request.env.getValue("WF_VAR_SKILLS_DIR"))
        assertEquals(canonicalSkillsDir.absolutePath, request.env.getValue("WF_VAR_SHARED_SKILLS_DIR"))
        assertContainerWritableDirectory(request.workingDir)
        assertContainerWritableDirectory(File(expectedOutputDir))
        assertContainerWritableDirectory(File(expectedPersistenceDir))
        assertEquals(
            File(canonicalSkillsDir, "braidrun-web").absolutePath,
            request.env.getValue("WF_VAR_WEB_SKILL_PATH")
        )

        assertTrue(
            request.mounts.any { it.containerPath == expectedOutputDir && !it.readOnly },
            "output_dir should be mounted at the same absolute path for host/container compatibility"
        )
        assertTrue(
            request.mounts.any { it.containerPath == expectedPersistenceDir && !it.readOnly },
            "persistence dir should be mounted for code-step compatibility"
        )
        assertTrue(
            request.mounts.any { it.containerPath == canonicalSkillsDir.absolutePath && it.readOnly },
            "shared skills dir should be mounted read-only at the same absolute path"
        )
    }

    @Test
    fun `code step sandbox injects shared skills aliases even when directory isolation is disabled`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val workflow = WorkflowDefinition(
            name = "skills-aliases",
            agents = emptyMap(),
            directoryIsolation = DirectoryIsolationConfig(
                enabled = false,
                sharedSkillsDir = skillsDir.absolutePath
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "echo",
                    code = CodeStepConfig(language = "bash", script = "echo ok")
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("native"))
            ),
            enableMonitoring = false,
            codeStepExecutor = capture
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-disabled")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertEquals(skillsDir.absolutePath, request.env.getValue("WF_VAR_SKILLS_DIR"))
        assertEquals(skillsDir.absolutePath, request.env.getValue("WF_VAR_SHARED_SKILLS_DIR"))
    }

    private fun assertContainerWritableDirectory(directory: File) {
        assumeTrue(Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(directory.toPath())
        assertTrue(PosixFilePermission.OTHERS_READ in permissions)
        assertTrue(PosixFilePermission.OTHERS_WRITE in permissions)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in permissions)
    }
}
