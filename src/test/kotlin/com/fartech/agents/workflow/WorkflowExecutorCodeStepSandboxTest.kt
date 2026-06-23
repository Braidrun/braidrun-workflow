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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowExecutorCodeStepSandboxTest {

    private class CapturingExecutor : SubprocessExecutor {
        var lastRequest: SubprocessExecutor.ExecRequest? = null
        var lastScriptText: String? = null

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            lastRequest = request
            lastScriptText = request.command.lastOrNull()
                ?.let { File(request.workingDir, it) }
                ?.takeIf { it.isFile }
                ?.readText()
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

    @Test
    fun `sandboxed code step spills oversized workflow env values to files`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = skillsDir.absolutePath
        )
        val largePayload = "x".repeat(80_000)
        val workflow = WorkflowDefinition(
            name = "large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to largePayload),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "read-large-env",
                    code = CodeStepConfig(language = "python", script = "print('ok')")
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("user-42"))
            ),
            enableMonitoring = false,
            codeStepExecutor = capture
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-large-env")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertFalse(request.env.containsKey("WF_VAR_LARGE_PAYLOAD"))
        assertEquals("/workspace/.wf_env/WF_VAR_LARGE_PAYLOAD.txt", request.env["WF_VAR_LARGE_PAYLOAD__FILE"])
        assertEquals("/workspace/.wf_env", request.env["PYTHONPATH"])
        val spilledFile = File(request.workingDir, ".wf_env/WF_VAR_LARGE_PAYLOAD.txt")
        assertEquals(largePayload, spilledFile.readText())
        val bridgeFile = File(request.workingDir, ".wf_env/sitecustomize.py")
        assertTrue(bridgeFile.isFile)
        val bridgeText = bridgeFile.readText()
        assertTrue("_braidrun_environ_get" in bridgeText)
        assertTrue("_braidrun_raw_get" in bridgeText)
        assertContainerReadableDirectory(spilledFile.parentFile)
        assertContainerReadableFile(spilledFile)
        assertContainerReadableFile(bridgeFile)
    }

    @Test
    fun `javascript code step installs spilled env node bridge`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = skillsDir.absolutePath
        )
        val workflow = WorkflowDefinition(
            name = "node-large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to "n".repeat(80_000)),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "read-node-env",
                    code = CodeStepConfig(language = "javascript", script = "console.log(process.env.WF_VAR_LARGE_PAYLOAD)")
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("user-42"))
            ),
            enableMonitoring = false,
            codeStepExecutor = capture
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-node-large-env")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertEquals("/workspace/.wf_env/WF_VAR_LARGE_PAYLOAD.txt", request.env["WF_VAR_LARGE_PAYLOAD__FILE"])
        assertEquals("/workspace/.wf_env/node_modules", request.env["NODE_PATH"])
        assertEquals("--require braidrun-spilled-env-bridge", request.env["NODE_OPTIONS"])
        val bridgeFile = File(request.workingDir, ".wf_env/node_modules/braidrun-spilled-env-bridge/index.js")
        assertTrue(bridgeFile.isFile)
        val bridgeText = bridgeFile.readText()
        assertTrue("readSpilledEnv" in bridgeText)
        assertTrue("getOwnPropertyDescriptor" in bridgeText)
        assertTrue("ownKeys" in bridgeText)
        assertContainerReadableFile(bridgeFile)
    }

    @Test
    fun `sandboxed code step spills oversized utf8 env values by bytes`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = skillsDir.absolutePath
        )
        val utf8Payload = "界".repeat(6_000)
        val workflow = WorkflowDefinition(
            name = "utf8-large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to utf8Payload),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "read-python-env",
                    code = CodeStepConfig(language = "python", script = "print('ok')")
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("user-42"))
            ),
            enableMonitoring = false,
            codeStepExecutor = capture
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-utf8-large-env")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertFalse(request.env.containsKey("WF_VAR_LARGE_PAYLOAD"))
        assertEquals("/workspace/.wf_env/WF_VAR_LARGE_PAYLOAD.txt", request.env["WF_VAR_LARGE_PAYLOAD__FILE"])
        assertEquals(utf8Payload, File(request.workingDir, ".wf_env/WF_VAR_LARGE_PAYLOAD.txt").readText())
    }

    @Test
    fun `spilled env bridges preserve existing python and node path variables`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = skillsDir.absolutePath
        )
        val workflow = WorkflowDefinition(
            name = "node-existing-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to "n".repeat(80_000)),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "read-node-env",
                    code = CodeStepConfig(language = "javascript", script = "console.log(process.env.WF_VAR_LARGE_PAYLOAD)")
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("user-42"))
            ),
            enableMonitoring = false,
            codeStepExecutor = capture,
            extraCodeStepEnv = mapOf(
                "NODE_PATH" to "/existing/node_modules",
                "NODE_OPTIONS" to "--max-old-space-size=2048",
                "PYTHONPATH" to "/existing/python"
            )
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-node-existing-env")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertEquals("/workspace/.wf_env/node_modules:/existing/node_modules", request.env["NODE_PATH"])
        assertEquals("--require braidrun-spilled-env-bridge --max-old-space-size=2048", request.env["NODE_OPTIONS"])
        assertEquals("/existing/python", request.env["PYTHONPATH"])
    }

    @Test
    fun `bash code step restores spilled env as shell variables`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = skillsDir.absolutePath
        )
        val workflow = WorkflowDefinition(
            name = "bash-large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to "s".repeat(80_000)),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "read-shell-env",
                    code = CodeStepConfig(language = "bash", script = "printf '%s' \"${'$'}WF_VAR_LARGE_PAYLOAD\"")
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("user-42"))
            ),
            enableMonitoring = false,
            codeStepExecutor = capture
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-shell-large-env")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertEquals("/workspace/.wf_env/WF_VAR_LARGE_PAYLOAD.txt", request.env["WF_VAR_LARGE_PAYLOAD__FILE"])
        val script = assertNotNull(capture.lastScriptText)
        assertTrue("_braidrun_restore_spilled_env" in script)
        assertTrue("read -r -d ''" in script)
        assertTrue("printf '%s' \"${'$'}WF_VAR_LARGE_PAYLOAD\"" in script)
    }

    @Test
    fun `cli code step keeps spilled env file reference without bash bridge`(@TempDir tempDir: Path) = runBlocking {
        val capture = CapturingExecutor()
        val skillsDir = tempDir.resolve("skills").toFile().apply { mkdirs() }.canonicalFile
        val isolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = skillsDir.absolutePath
        )
        val workflow = WorkflowDefinition(
            name = "cli-large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to "c".repeat(80_000)),
            directoryIsolation = isolation,
            workflow = listOf(
                WorkflowStep(
                    step = "read-cli-env",
                    code = CodeStepConfig(
                        language = "cli",
                        script = "cat \"${'$'}WF_VAR_LARGE_PAYLOAD__FILE\" >/dev/null"
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("subprocess_mode", JsonPrimitive("docker")),
                ConfigurationParameter("user_id", JsonPrimitive("user-42"))
            ),
            enableMonitoring = false,
            codeStepExecutor = capture
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-cli-large-env")
        assertTrue(result.success)

        val request = assertNotNull(capture.lastRequest)
        assertEquals("/workspace/.wf_env/WF_VAR_LARGE_PAYLOAD.txt", request.env["WF_VAR_LARGE_PAYLOAD__FILE"])
        val script = assertNotNull(capture.lastScriptText)
        assertFalse("_braidrun_restore_spilled_env" in script)
        assertTrue("WF_VAR_LARGE_PAYLOAD__FILE" in script)
    }

    @Test
    fun `legacy javascript code step reads spilled env when cwd has spaces`(@TempDir tempDir: Path) = runBlocking {
        assumeTrue(commandAvailable("node"))
        val workingDir = tempDir.resolve("legacy node dir with spaces").toFile().apply { mkdirs() }
        val workflow = WorkflowDefinition(
            name = "legacy-node-large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to "j".repeat(80_000)),
            workflow = listOf(
                WorkflowStep(
                    step = "read-node-env",
                    code = CodeStepConfig(
                        language = "javascript",
                        workingDirectory = workingDir.absolutePath,
                        script = """
                            const value = process.env.WF_VAR_LARGE_PAYLOAD;
                            const visible = Object.keys(process.env).includes('WF_VAR_LARGE_PAYLOAD');
                            const own = Object.prototype.hasOwnProperty.call(process.env, 'WF_VAR_LARGE_PAYLOAD');
                            process.stdout.write(`${'$'}{value.slice(0, 3)}:${'$'}{visible}:${'$'}{own}:${'$'}{process.env.MISSING || 'default'}`);
                        """.trimIndent()
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-legacy-node-large-env")

        assertTrue(result.success)
        assertEquals("jjj:true:true:default", result.stepResults.getValue("read-node-env").output?.trim())
        assertFalse(File(workingDir, ".wf_env").exists())
    }

    @Test
    fun `legacy python code step reads spilled env through os getenv`(@TempDir tempDir: Path) = runBlocking {
        assumeTrue(commandAvailable("python3"))
        val workingDir = tempDir.resolve("legacy python dir with spaces").toFile().apply { mkdirs() }
        val largePayload = "line1'quoted\nline2:" + "p".repeat(80_000)
        val workflow = WorkflowDefinition(
            name = "legacy-python-large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to largePayload),
            workflow = listOf(
                WorkflowStep(
                    step = "read-python-env",
                    code = CodeStepConfig(
                        language = "python",
                        workingDirectory = workingDir.absolutePath,
                        script = """
                            import os
                            value = os.getenv("WF_VAR_LARGE_PAYLOAD")
                            in_dict = "WF_VAR_LARGE_PAYLOAD" in dict(os.environ)
                            in_copy = "WF_VAR_LARGE_PAYLOAD" in os.environ.copy()
                            print(value.splitlines()[0] + ":" + str(in_dict).lower() + ":" + str(in_copy).lower() + ":" + os.getenv("MISSING", "fallback"))
                        """.trimIndent()
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-legacy-python-large-env")

        assertTrue(result.success)
        assertEquals("line1'quoted:true:true:fallback", result.stepResults.getValue("read-python-env").output?.trim())
        assertFalse(File(workingDir, ".wf_env").exists())
    }

    @Test
    fun `legacy python spilled env bridge chains existing sitecustomize`(@TempDir tempDir: Path) = runBlocking {
        assumeTrue(commandAvailable("python3"))
        val existingPythonPath = tempDir.resolve("existing python path").toFile().apply { mkdirs() }
        File(existingPythonPath, "sitecustomize.py").writeText(
            "import os\nos.environ['CHAINED_SITECUSTOMIZE'] = 'ok'\n"
        )
        val workflow = WorkflowDefinition(
            name = "legacy-python-sitecustomize",
            agents = emptyMap(),
            variables = mapOf("large_payload" to "p".repeat(80_000)),
            workflow = listOf(
                WorkflowStep(
                    step = "read-python-env",
                    code = CodeStepConfig(
                        language = "python",
                        script = "import os\nprint(os.getenv('WF_VAR_LARGE_PAYLOAD')[:1] + ':' + os.getenv('CHAINED_SITECUSTOMIZE', 'missing'))"
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            extraCodeStepEnv = mapOf("PYTHONPATH" to existingPythonPath.absolutePath)
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-legacy-python-sitecustomize")

        assertTrue(result.success)
        assertEquals("p:ok", result.stepResults.getValue("read-python-env").output?.trim())
    }

    @Test
    fun `legacy bash code step restores spilled env with quotes and newlines`(@TempDir tempDir: Path) = runBlocking {
        assumeTrue(commandAvailable("bash"))
        val workingDir = tempDir.resolve("legacy bash dir with spaces").toFile().apply { mkdirs() }
        val largePayload = "line1'quoted\nline2:" + "b".repeat(80_000) + "\n\n"
        val workflow = WorkflowDefinition(
            name = "legacy-bash-large-env",
            agents = emptyMap(),
            variables = mapOf("large_payload" to largePayload),
            workflow = listOf(
                WorkflowStep(
                    step = "read-bash-env",
                    code = CodeStepConfig(
                        language = "bash",
                        workingDirectory = workingDir.absolutePath,
                        script = "printf '%s:%s' \"${'$'}{WF_VAR_LARGE_PAYLOAD%%${'$'}'\\n'*}\" \"${'$'}{#WF_VAR_LARGE_PAYLOAD}\""
                    )
                )
            )
        )
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )

        val result = executor.execute(workflow, externalExecutionId = "exec-legacy-bash-large-env")

        assertTrue(result.success)
        assertEquals("line1'quoted:${largePayload.length}", result.stepResults.getValue("read-bash-env").output?.trim())
        assertFalse(File(workingDir, ".wf_env").exists())
    }

    private fun assertContainerReadableDirectory(directory: File) {
        assumeTrue(Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(directory.toPath())
        assertTrue(PosixFilePermission.OTHERS_READ in permissions)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in permissions)
        assertFalse(PosixFilePermission.OTHERS_WRITE in permissions)
    }

    private fun assertContainerWritableDirectory(directory: File) {
        assumeTrue(Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(directory.toPath())
        assertTrue(PosixFilePermission.OTHERS_READ in permissions)
        assertTrue(PosixFilePermission.OTHERS_WRITE in permissions)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in permissions)
    }

    private fun assertContainerReadableFile(file: File) {
        assumeTrue(Files.getFileStore(file.toPath()).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(file.toPath())
        assertTrue(PosixFilePermission.OTHERS_READ in permissions)
        assertFalse(PosixFilePermission.OTHERS_WRITE in permissions)
    }

    private fun commandAvailable(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder(command, "--version").start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@runCatching false
            }
            process.exitValue() == 0
        }.getOrDefault(false)
    }
}
