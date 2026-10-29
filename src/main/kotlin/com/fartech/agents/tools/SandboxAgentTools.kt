package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

/** Runner-only typed execution; deliberately unrelated to the legacy native subprocess default. */
fun interface SandboxToolExecutor {
    suspend fun execute(request: SandboxToolRequest): String
}

data class SandboxToolRequest(
    val toolGroup: String,
    val runtime: String,
    val source: String = "",
    val arguments: List<String> = emptyList(),
    val inputFiles: List<String> = emptyList(),
    val outputFiles: List<String> = emptyList(),
    val timeoutSeconds: Long = 120
)

@LLMDescription("Offline Linux shell in a fresh bounded sandbox. Files listed in inputFiles are copied into /output/workspace; only outputFiles are saved back. No host environment, network, interactive terminal or persistent process.")
class SandboxShellTools(private val executor: SandboxToolExecutor) : ToolSet {
    @Tool @LLMDescription("Execute a shell command in the offline sandbox. Input and output paths are relative to this step. No environment overrides or host working directory.")
    suspend fun executeShellCmd(cmd: String, inputFiles: List<String> = emptyList(), outputFiles: List<String> = emptyList(), timeoutSeconds: Long = 120): String =
        executor.execute(SandboxToolRequest("shell", "shell", cmd, inputFiles = inputFiles, outputFiles = outputFiles, timeoutSeconds = timeoutSeconds))

    @Tool @LLMDescription("Execute a multiline shell script without creating any host script or temporary file.")
    suspend fun executeShellScript(script: String, inputFiles: List<String> = emptyList(), outputFiles: List<String> = emptyList(), timeoutSeconds: Long = 120): String =
        executeShellCmd(script, inputFiles, outputFiles, timeoutSeconds)
}

@LLMDescription("Run code offline in a fresh bounded sandbox. Only packaged interpreters are available; package installation, arbitrary interpreters, host paths and network are unavailable.")
class SandboxCodeExecutionTools(private val executor: SandboxToolExecutor) : ToolSet {
    @Tool @LLMDescription("Execute Python code using the pinned Python runtime. Relative files must be explicitly declared as inputs or outputs.")
    suspend fun executePython(code: String, inputFiles: List<String> = emptyList(), outputFiles: List<String> = emptyList(), timeoutSeconds: Long = 120): String =
        executor.execute(SandboxToolRequest("code_execution", "python", code, inputFiles = inputFiles, outputFiles = outputFiles, timeoutSeconds = timeoutSeconds))

    @Tool @LLMDescription("Execute JavaScript using the pinned Node runtime, when included in the accepted sandbox image.")
    suspend fun executeJavaScript(code: String, inputFiles: List<String> = emptyList(), outputFiles: List<String> = emptyList(), timeoutSeconds: Long = 120): String =
        executor.execute(SandboxToolRequest("code_execution", "javascript", code, inputFiles = inputFiles, outputFiles = outputFiles, timeoutSeconds = timeoutSeconds))
}

@LLMDescription("Local Git against explicitly listed repository snapshots in a fresh offline sandbox. No clone, pull, push, fetch, remote, host credentials or persistent repository. Declare changed files to save them back.")
class SandboxGitTools(private val executor: SandboxToolExecutor) : ToolSet {
    @Tool @LLMDescription("Run a local Git operation: init, status, diff, log, show, branch, add, commit, checkout, stash, restore, reset, rm, mv or apply. arguments are passed as an argument vector. inputFiles must include all required repository files; outputFiles names the files to save.")
    suspend fun executeLocalGit(operation: String, arguments: List<String> = emptyList(), inputFiles: List<String> = emptyList(), outputFiles: List<String> = emptyList(), timeoutSeconds: Long = 120): String {
        require(operation in LOCAL_OPERATIONS) { "Only local Git operations are supported" }
        return executor.execute(SandboxToolRequest("git", "git", arguments = listOf(operation) + arguments,
            inputFiles = inputFiles, outputFiles = outputFiles, timeoutSeconds = timeoutSeconds))
    }
    companion object {
        val LOCAL_OPERATIONS = setOf("init", "status", "diff", "log", "show", "branch", "add", "commit", "checkout", "stash", "restore", "reset", "rm", "mv", "apply")
    }
}
