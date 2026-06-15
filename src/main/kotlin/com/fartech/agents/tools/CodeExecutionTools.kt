package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.tools.exec.DockerSubprocessExecutor
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor
import java.io.File

/**
 * Code execution tools for running Python, JavaScript, and other code snippets.
 *
 * Delegates subprocess execution to a [SubprocessExecutor], which may be either
 * [NativeSubprocessExecutor] (dev) or `DockerSubprocessExecutor` (production).
 *
 * In strict sandbox mode ([packageInstallEnabled] = false), `pipInstall` and
 * `npmInstall` are disabled — all dependencies must be pre-installed in the
 * Docker execution image. This is enforced by constraint C8 / §2.9 of the
 * multi-user phase-3 design.
 */
@LLMDescription(
    "Code execution tools for running Python and JavaScript/Node.js code snippets. " +
            "Creates temporary files and executes them with the appropriate interpreter. " +
            "The interpreter (python3/node) must be available on the system or bundled by the desktop app."
)
class CodeExecutionTools(
    private val executor: SubprocessExecutor = NativeSubprocessExecutor(),
    private val userId: String = "local-user",
    private val packageInstallEnabled: Boolean = true,
    private val context: SubprocessToolContext = SubprocessToolContext()
) : ToolSet {

    private val isWindows: Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun pythonCommand(): String =
        if (isWindows) "python" else "python3"

    private fun shouldInstallPythonPackagesToUserSite(): Boolean =
        !System.getenv("PYTHONUSERBASE").isNullOrBlank() &&
                System.getenv("VIRTUAL_ENV").isNullOrBlank()

    private suspend fun executeCode(
        code: String,
        interpreter: String,
        suffix: String,
        timeoutSeconds: Long,
        workingDirectory: String
    ): String {
        val workDir = if (workingDirectory.isNotBlank()) File(workingDirectory) else context.workspaceDir ?: File(".")
        // Phase 11: `File.createTempFile` honours umask (default 0644 on Linux). Code
        // snippets routinely contain inline API tokens / credentials the LLM was told
        // to use. SecureTempFile chmod's the file to 0600 immediately so another local
        // user can't `cat` the script during the seconds it sits on disk.
        val tempFile = com.fartech.agents.commons.SecureTempFile.create("agent_code_", suffix, workDir)
        tempFile.deleteOnExit()

        return try {
            tempFile.writeText(code)

            val result = executor.execute(
                ExecRequest(
                    command = listOf(interpreter, tempFile.name),
                    workingDir = workDir,
                    timeoutSeconds = timeoutSeconds,
                    env = buildExecutorEnvironment(workDir),
                    mounts = buildExecutorMounts(workDir),
                    imageHint = resolveImageHint(interpreter),
                    userId = userId
                )
            )

            val rawOutput = result.combinedOutput
            val output = ShellTools.truncateOutput(rawOutput)
            val timeoutNote = if (result.exitCode == -1 && result.stderr.contains("TIMED OUT")) {
                " [TIMED OUT after ${timeoutSeconds}s]"
            } else ""

            "Exit code: ${result.exitCode}$timeoutNote\nOutput:\n$output"
        } catch (e: Exception) {
            "Error executing code: ${e.message}"
        } finally {
            tempFile.delete()
        }
    }

    @Tool
    @LLMDescription(
        "Execute a Python code snippet. The code is written to a temporary file and executed with python3 (or python on Windows). " +
                "Python must be installed on the system. Supports any Python code including imports, functions, classes, etc. " +
                "Use print() to produce output that will be returned."
    )
    suspend fun executePython(
        @LLMDescription("Python code to execute")
        code: String,
        @LLMDescription("Timeout in seconds (default 60)")
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        @LLMDescription("Working directory for execution (optional)")
        workingDirectory: String = ""
    ): String {
        printlnColor(AnsiColor.CYAN, "[Python] Executing code snippet (${code.length} chars)...")
        val result = executeCode(code, pythonCommand(), ".py", timeoutSeconds, workingDirectory)
        printlnColor(AnsiColor.CYAN, result)
        return result
    }

    @Tool
    @LLMDescription(
        "Execute a JavaScript/Node.js code snippet. The code is written to a temporary file and executed with node. " +
                "Node.js must be installed on the system. Supports any Node.js code including require(), async/await, etc. " +
                "Use console.log() to produce output that will be returned."
    )
    suspend fun executeJavaScript(
        @LLMDescription("JavaScript/Node.js code to execute")
        code: String,
        @LLMDescription("Timeout in seconds (default 60)")
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        @LLMDescription("Working directory for execution (optional)")
        workingDirectory: String = ""
    ): String {
        printlnColor(AnsiColor.CYAN, "[Node.js] Executing code snippet (${code.length} chars)...")
        val result = executeCode(code, "node", ".js", timeoutSeconds, workingDirectory)
        printlnColor(AnsiColor.CYAN, result)
        return result
    }

    @Tool
    @LLMDescription(
        "Execute a code snippet in any language by specifying the interpreter command. " +
                "For example: interpreter='ruby' with suffix='.rb', or interpreter='perl' with suffix='.pl'. " +
                "The specified interpreter must be installed on the system."
    )
    suspend fun executeWithInterpreter(
        @LLMDescription("Code to execute")
        code: String,
        @LLMDescription("Interpreter command (e.g., 'ruby', 'perl', 'php', 'bash')")
        interpreter: String,
        @LLMDescription("File suffix for the temporary code file (e.g., '.rb', '.pl', '.php', '.sh')")
        fileSuffix: String = ".tmp",
        @LLMDescription("Timeout in seconds (default 60)")
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        @LLMDescription("Working directory for execution (optional)")
        workingDirectory: String = ""
    ): String {
        printlnColor(AnsiColor.CYAN, "[$interpreter] Executing code snippet (${code.length} chars)...")
        val result = executeCode(code, interpreter, fileSuffix, timeoutSeconds, workingDirectory)
        printlnColor(AnsiColor.CYAN, result)
        return result
    }

    @Tool
    @LLMDescription(
        "Install Python package(s) using 'python -m pip install'. " +
                "When the desktop app provides a bundled Python environment, packages are installed into the user-writable Python user base."
    )
    suspend fun pipInstall(
        @LLMDescription("Package name(s) to install, space-separated (e.g., 'pandas numpy matplotlib')")
        packages: String,
        @LLMDescription("Timeout in seconds (default 120)")
        timeoutSeconds: Long = 120L
    ): String {
        if (!packageInstallEnabled) {
            return "Error: Package installation is disabled in sandbox mode. " +
                    "All required Python packages must be pre-installed in the execution image (braidrun/exec-python-node:1.0). " +
                    "Available packages: numpy, pandas, requests, openpyxl, python-dateutil, pydantic, pyyaml."
        }

        val packageList = packages
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (packageList.isEmpty()) {
            return "Error: no Python packages were provided"
        }

        val python = pythonCommand()
        val installIntoUserSite = shouldInstallPythonPackagesToUserSite()
        printlnColor(AnsiColor.CYAN, "[pip] Installing: $packages")

        // Check pip availability
        val pipCheck = executor.execute(
            ExecRequest(
                command = listOf(python, "-m", "pip", "--version"),
                workingDir = context.workspaceDir ?: File("."),
                timeoutSeconds = 20,
                env = buildExecutorEnvironment(context.workspaceDir ?: File(".")),
                mounts = buildExecutorMounts(context.workspaceDir ?: File(".")),
                imageHint = "python",
                userId = userId
            )
        )

        if (pipCheck.exitCode != 0) {
            val ensurePipArgs = listOf(python, "-m", "ensurepip", "--upgrade") +
                    (if (installIntoUserSite) listOf("--user") else emptyList())
            val ensurePip = executor.execute(
                ExecRequest(
                    command = ensurePipArgs,
                    workingDir = context.workspaceDir ?: File("."),
                    timeoutSeconds = timeoutSeconds,
                    env = buildExecutorEnvironment(context.workspaceDir ?: File(".")),
                    mounts = buildExecutorMounts(context.workspaceDir ?: File(".")),
                    imageHint = "python",
                    userId = userId
                )
            )
            if (ensurePip.exitCode != 0) {
                return "Failed to bootstrap pip (exit code ${ensurePip.exitCode}):\n${ensurePip.combinedOutput}"
            }
        }

        val installArgs = listOf(python, "-m", "pip", "install") +
                (if (installIntoUserSite) listOf("--user") else emptyList()) +
                packageList
        val installResult = executor.execute(
            ExecRequest(
                command = installArgs,
                workingDir = context.workspaceDir ?: File("."),
                timeoutSeconds = timeoutSeconds,
                env = buildExecutorEnvironment(context.workspaceDir ?: File(".")),
                mounts = buildExecutorMounts(context.workspaceDir ?: File(".")),
                imageHint = "python",
                userId = userId
            )
        )

        return if (installResult.exitCode == 0) {
            "Successfully installed: $packages"
        } else {
            "pip install failed (exit code ${installResult.exitCode}):\n${installResult.combinedOutput}"
        }
    }

    @Tool
    @LLMDescription(
        "Install a Node.js package using npm. Equivalent to running 'npm install <package>'. " +
                "Useful for installing dependencies before running JavaScript code."
    )
    suspend fun npmInstall(
        @LLMDescription("Package name(s) to install, space-separated (e.g., 'lodash axios cheerio')")
        packages: String,
        @LLMDescription("Timeout in seconds (default 120)")
        timeoutSeconds: Long = 120L,
        @LLMDescription("Working directory (optional, defaults to current directory)")
        workingDirectory: String = ""
    ): String {
        if (!packageInstallEnabled) {
            return "Error: Package installation is disabled in sandbox mode. " +
                    "All required Node.js packages must be pre-installed in the execution image (braidrun/exec-node:1.0)."
        }

        val packageList = packages
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (packageList.isEmpty()) {
            return "Error: no Node.js packages were provided"
        }

        printlnColor(AnsiColor.CYAN, "[npm] Installing: $packages")

        val workDir = if (workingDirectory.isNotBlank()) File(workingDirectory) else context.workspaceDir ?: File(".")
        val installResult = executor.execute(
            ExecRequest(
                command = listOf("npm", "install") + packageList,
                workingDir = workDir,
                timeoutSeconds = timeoutSeconds,
                env = buildExecutorEnvironment(workDir),
                mounts = buildExecutorMounts(workDir),
                imageHint = "node",
                userId = userId
            )
        )

        return if (installResult.exitCode == 0) {
            "Successfully installed: $packages"
        } else {
            "npm install failed (exit code ${installResult.exitCode}):\n${installResult.combinedOutput}"
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 60L

        private fun resolveImageHint(interpreter: String): String = when {
            interpreter.contains("python") -> "python"
            interpreter == "node" -> "node"
            else -> "shell"
        }

        /** Create an instance with package install enabled (dev mode). */
        fun withPackageInstall(
            executor: SubprocessExecutor,
            userId: String = "local-user",
            context: SubprocessToolContext = SubprocessToolContext()
        ) = CodeExecutionTools(executor, userId, packageInstallEnabled = true, context = context)

        /** Create a read-only instance with pip/npm install disabled (sandbox mode). */
        fun readOnly(
            executor: SubprocessExecutor,
            userId: String = "local-user",
            context: SubprocessToolContext = SubprocessToolContext()
        ) = CodeExecutionTools(executor, userId, packageInstallEnabled = false, context = context)
    }

    private fun buildExecutorEnvironment(workDir: File): Map<String, String> {
        val usingDocker = executor is DockerSubprocessExecutor
        val workspacePath = if (usingDocker) "/workspace" else workDir.absolutePath
        val outputPath = when {
            context.outputDir == null -> null
            usingDocker -> "/output"
            else -> context.outputDir.absolutePath
        }
        val skillsPath = when {
            context.skillsDir == null || !context.skillsDir.isDirectory -> null
            usingDocker -> "/skills"
            else -> context.skillsDir.absolutePath
        }

        return buildMap {
            put("BRAIDRUN_WORKSPACE", workspacePath)
            put("BRAIDRUN_USER_ID", userId)
            context.executionId?.let { put("BRAIDRUN_EXECUTION_ID", it) }
            context.stepName?.let { put("BRAIDRUN_STEP_NAME", it) }
            context.sessionId?.let { put("BRAIDRUN_SESSION_ID", it) }
            outputPath?.let { put("BRAIDRUN_OUTPUT_DIR", it) }
            skillsPath?.let { put("BRAIDRUN_SKILLS_DIR", it) }
        }
    }

    private fun buildExecutorMounts(workDir: File): List<SubprocessExecutor.Mount> {
        if (executor !is DockerSubprocessExecutor) return emptyList()

        val mounts = mutableListOf<SubprocessExecutor.Mount>()
        val canonicalWorkDir = runCatching { workDir.canonicalFile }.getOrDefault(workDir.absoluteFile)

        context.outputDir
            ?.takeIf { it.exists() || it.mkdirs() }
            ?.takeUnless { runCatching { it.canonicalFile == canonicalWorkDir }.getOrDefault(false) }
            ?.let {
                mounts += SubprocessExecutor.Mount(
                    hostPath = it,
                    containerPath = "/output",
                    readOnly = false
                )
            }

        context.skillsDir
            ?.takeIf { it.exists() && it.isDirectory }
            ?.let {
                mounts += SubprocessExecutor.Mount(
                    hostPath = it,
                    containerPath = "/skills",
                    readOnly = true
                )
            }

        return mounts
    }
}
