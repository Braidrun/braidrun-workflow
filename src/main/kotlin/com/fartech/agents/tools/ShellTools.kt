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
 * Shell command execution tools, delegating to a [SubprocessExecutor].
 *
 * In dev mode the executor is [NativeSubprocessExecutor] (bare `ProcessBuilder`).
 * In staging/production it is [DockerSubprocessExecutor] (ephemeral container per call).
 *
 * The companion object provides a default instance backed by [NativeSubprocessExecutor]
 * for backward-compatible call sites that haven't been wired with DI yet.
 */
@LLMDescription("A set of shell tools to execute shell commands on any operating system (macOS, Linux, Windows)")
class ShellTools(
    private val executor: SubprocessExecutor = NativeSubprocessExecutor(),
    private val userId: String = "local-user",
    private val context: SubprocessToolContext = SubprocessToolContext()
) : ToolSet {

    private val isWindows: Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    @Tool
    @LLMDescription(
        "Execute a shell command. Automatically detects the operating system and uses the appropriate shell " +
                "(PowerShell on Windows, /bin/sh on macOS/Linux). Returns stdout+stderr and exit code. " +
                "Output is truncated if it exceeds 100KB to avoid context overflow."
    )
    suspend fun executeShellCmd(
        @LLMDescription("The shell command to execute")
        cmd: String,
        @LLMDescription("Working directory for the command (optional, defaults to current directory)")
        workingDirectory: String = "",
        @LLMDescription("Timeout in seconds (optional, defaults to 120). The command will be killed if it exceeds this time.")
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        @LLMDescription("Optional environment variables as comma-separated KEY=VALUE pairs, e.g. 'PATH=/usr/bin,HOME=/root'")
        environment: String = ""
    ): String {
        printlnColor(AnsiColor.YELLOW, cmd)

        val command = if (isWindows) {
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", cmd)
        } else {
            listOf("/bin/sh", "-c", cmd)
        }

        val envMap = if (environment.isNotBlank()) {
            environment.split(",").mapNotNull { pair ->
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
        } else {
            emptyMap()
        }

        val workDir = if (workingDirectory.isNotBlank()) {
            val dir = File(workingDirectory)
            if (!dir.isDirectory) {
                return "The shell command output is: Error: working directory does not exist or is not a directory: $workingDirectory and exit code is: -1"
            }
            dir
        } else {
            context.workspaceDir ?: File(".")
        }

        val result = executor.execute(
            ExecRequest(
                command = command,
                workingDir = workDir,
                timeoutSeconds = timeoutSeconds,
                env = buildExecutorEnvironment(workDir) + envMap,
                mounts = buildExecutorMounts(workDir),
                imageHint = "shell",
                userId = userId
            )
        )

        val rawOutput = result.combinedOutput
        printlnColor(AnsiColor.YELLOW, rawOutput)

        val output = truncateOutput(rawOutput)
        val timeoutNote = if (result.exitCode == -1 && result.stderr.contains("TIMED OUT")) {
            " [TIMED OUT after ${timeoutSeconds}s]"
        } else ""

        return "The shell command output is: $output and exit code is: ${result.exitCode}$timeoutNote"
    }

    @Tool
    @LLMDescription(
        "Execute a multi-line shell script. Creates a temporary script file and executes it. " +
                "Useful for complex operations that require multiple commands."
    )
    suspend fun executeShellScript(
        @LLMDescription("The shell script content (multiple lines)")
        script: String,
        @LLMDescription("Working directory for the script (optional, defaults to current directory)")
        workingDirectory: String = "",
        @LLMDescription("Timeout in seconds (optional, defaults to 120)")
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): String {
        val workDir = if (workingDirectory.isNotBlank()) {
            val dir = File(workingDirectory)
            if (!dir.isDirectory) {
                return "Error executing script: working directory does not exist or is not a directory: $workingDirectory"
            }
            dir
        } else {
            context.workspaceDir ?: File(".")
        }

        val suffix = if (isWindows) ".ps1" else ".sh"
        val tempFile = try {
            File.createTempFile("agent_script_", suffix, workDir)
        } catch (e: Exception) {
            return "Error executing script: failed to create temp file: ${e.message}"
        }

        return try {
            tempFile.writeText(script)
            if (!isWindows) {
                tempFile.setExecutable(true)
            }

            // Invoke the interpreter directly via the executor (argv form) instead of going
            // back through executeShellCmd, which would wrap us in a SECOND shell. The double
            // wrap was fragile if the temp-file path contained quotes/spaces, and pointlessly
            // doubled subprocess startup cost.
            // Reference the script by NAME relative to workingDir (matches
            // CodeExecutionTools): under DockerSubprocessExecutor the workspace is
            // bind-mounted at /workspace and the cwd is /workspace, so the host
            // absolute path does not exist inside the container.
            val command = if (isWindows) {
                listOf(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", tempFile.name
                )
            } else {
                listOf("/bin/sh", tempFile.name)
            }

            val result = executor.execute(
                ExecRequest(
                    command = command,
                    workingDir = workDir,
                    timeoutSeconds = timeoutSeconds,
                    env = buildExecutorEnvironment(workDir),
                    mounts = buildExecutorMounts(workDir),
                    imageHint = "shell",
                    userId = userId
                )
            )

            val rawOutput = result.combinedOutput
            printlnColor(AnsiColor.YELLOW, rawOutput)
            val output = truncateOutput(rawOutput)
            val timeoutNote = if (result.exitCode == -1 && result.stderr.contains("TIMED OUT")) {
                " [TIMED OUT after ${timeoutSeconds}s]"
            } else ""
            "The shell command output is: $output and exit code is: ${result.exitCode}$timeoutNote"
        } catch (e: Exception) {
            "Error executing script: ${e.message}"
        } finally {
            // Always clean up — deleteOnExit() is unreliable when the JVM is killed.
            runCatching { tempFile.delete() }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val MAX_OUTPUT_LENGTH = 100_000

        internal fun truncateOutput(output: String): String {
            return if (output.length > MAX_OUTPUT_LENGTH) {
                val half = MAX_OUTPUT_LENGTH / 2
                output.take(half) +
                        "\n\n... [OUTPUT TRUNCATED: ${output.length} chars total, showing first and last ${half} chars] ...\n\n" +
                        output.takeLast(half)
            } else {
                output
            }
        }
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
