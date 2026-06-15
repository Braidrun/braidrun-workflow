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
 * Git version control tools.
 *
 * All `git` invocations are routed through the injected [SubprocessExecutor] — in dev mode this
 * is [NativeSubprocessExecutor] (bare `ProcessBuilder`), in staging/production it is
 * [DockerSubprocessExecutor] (ephemeral container, UID 2000:2000, RO rootfs, egress-only network).
 *
 * **Security note**: previously this file built a [ProcessBuilder] directly, which bypassed the
 * Docker sandbox in production. That was a sandbox-violation design bug: any LLM-provided
 * argument (ref name, file path) ran with the host's full filesystem and network scope instead
 * of the container's. The class now mirrors the same DI pattern as [ShellTools] /
 * [CodeExecutionTools] so the LLM's git commands inherit the same isolation as shell/code steps.
 */
@LLMDescription(
    "Git version control tools for common Git operations. " +
            "Provides structured, safe access to Git commands without needing to use the shell directly. " +
            "All operations require Git to be installed on the system (or in the Docker exec image)."
)
class GitTools(
    private val executor: SubprocessExecutor = NativeSubprocessExecutor(),
    private val userId: String = "local-user",
    private val context: SubprocessToolContext = SubprocessToolContext()
) : ToolSet {

    private suspend fun runGit(args: List<String>, workingDir: String = ""): String {
        return try {
            val workDir = resolveWorkingDir(workingDir)
            val command = listOf("git") + args

            val result = executor.execute(
                ExecRequest(
                    command = command,
                    workingDir = workDir,
                    timeoutSeconds = TIMEOUT_SECONDS,
                    env = buildExecutorEnvironment(workDir),
                    mounts = buildExecutorMounts(workDir),
                    // Git is available in the default shell image; no separate image needed.
                    imageHint = "shell",
                    userId = userId
                )
            )

            val combined = result.combinedOutput
            val truncated = truncateOutput(combined)

            when {
                result.exitCode == -1 && result.stderr.contains("TIMED OUT") ->
                    "Error: git command timed out after ${TIMEOUT_SECONDS}s" +
                        if (truncated.isNotBlank()) "\nPartial output:\n$truncated" else ""
                result.exitCode != 0 -> "Git error (exit code ${result.exitCode}):\n$truncated"
                else -> truncated
            }
        } catch (e: Exception) {
            "Error running git: ${e.message}"
        }
    }

    private fun resolveWorkingDir(workingDir: String): File {
        if (workingDir.isNotBlank()) {
            val dir = File(workingDir)
            // Graceful fallback — git itself will complain if the dir doesn't exist,
            // preserving the old behaviour for callers.
            if (dir.isDirectory) return dir
        }
        return context.workspaceDir ?: File(".")
    }

    private fun buildExecutorEnvironment(workDir: File): Map<String, String> {
        val usingDocker = executor is DockerSubprocessExecutor
        val workspacePath = if (usingDocker) "/workspace" else workDir.absolutePath
        return buildMap {
            put("BRAIDRUN_WORKSPACE", workspacePath)
            put("BRAIDRUN_USER_ID", userId)
            context.executionId?.let { put("BRAIDRUN_EXECUTION_ID", it) }
            context.stepName?.let { put("BRAIDRUN_STEP_NAME", it) }
            context.sessionId?.let { put("BRAIDRUN_SESSION_ID", it) }
        }
    }

    private fun buildExecutorMounts(workDir: File): List<SubprocessExecutor.Mount> {
        if (executor !is DockerSubprocessExecutor) return emptyList()
        // Git rarely needs skills/output mounts; but if outputDir is different from workDir,
        // mirror the shell-tools pattern so `git archive > /output/...` keeps working.
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
        return mounts
    }

    @Tool
    @LLMDescription("Get the current Git status of a repository (modified, staged, untracked files).")
    suspend fun gitStatus(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = ""
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] status")
        return runGit(listOf("status", "--short", "--branch"), repoPath).let { output ->
            if (output.isBlank()) "Working tree clean, nothing to commit." else output
        }
    }

    @Tool
    @LLMDescription("Show the diff of uncommitted changes. Can optionally show diff for a specific file.")
    suspend fun gitDiff(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Specific file path to diff (optional, shows all changes if empty)")
        filePath: String = "",
        @LLMDescription("Whether to show staged (cached) changes only (default false)")
        staged: Boolean = false
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] diff")
        val args = mutableListOf("diff")
        if (staged) args.add("--cached")
        args.add("--stat")
        if (filePath.isNotBlank()) args.add(filePath)
        val statOutput = runGit(args, repoPath)

        val detailArgs = mutableListOf("diff")
        if (staged) detailArgs.add("--cached")
        if (filePath.isNotBlank()) detailArgs.add(filePath)
        val detailOutput = runGit(detailArgs, repoPath)

        return if (detailOutput.isBlank()) {
            "No changes found."
        } else {
            "Summary:\n$statOutput\n\nDetailed diff:\n$detailOutput"
        }
    }

    @Tool
    @LLMDescription("Show the Git commit log. Returns recent commits with hash, author, date, and message.")
    suspend fun gitLog(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Maximum number of commits to show (default 10)")
        maxCount: Int = 10,
        @LLMDescription("Only show commits for this file path (optional)")
        filePath: String = "",
        @LLMDescription("Format: 'oneline' for compact, 'full' for detailed (default 'oneline')")
        format: String = "oneline"
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] log")
        val args = mutableListOf("log", "-n", maxCount.toString())
        if (format == "oneline") {
            args.add("--oneline")
            args.add("--decorate")
        } else {
            args.add("--format=%H%n  Author: %an <%ae>%n  Date: %ad%n  Message: %s%n")
            args.add("--date=short")
        }
        if (filePath.isNotBlank()) {
            args.add("--")
            args.add(filePath)
        }
        return runGit(args, repoPath).ifBlank { "No commits found." }
    }

    @Tool
    @LLMDescription("List Git branches. Shows local branches by default, can also show remote branches.")
    suspend fun gitBranch(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Whether to show remote branches as well (default false)")
        showRemote: Boolean = false
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] branch")
        val args = mutableListOf("branch", "-v")
        if (showRemote) args.add("-a")
        return runGit(args, repoPath).ifBlank { "No branches found." }
    }

    @Tool
    @LLMDescription("Stage files for commit. Equivalent to 'git add'.")
    suspend fun gitAdd(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("File path(s) to stage, comma-separated. Use '.' to stage all changes.")
        files: String = "."
    ): String {
        val fileList = files.split(",").map { it.trim() }
        printlnColor(AnsiColor.BLUE, "[Git] add $files")
        return runGit(listOf("add") + fileList, repoPath).let { output ->
            if (output.isBlank()) "Successfully staged: $files" else output
        }
    }

    @Tool
    @LLMDescription("Create a Git commit with a message.")
    suspend fun gitCommit(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Commit message")
        message: String,
        @LLMDescription("Whether to stage all modified tracked files before committing (equivalent to 'git commit -a')")
        stageAll: Boolean = false
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] commit -m \"$message\"")
        val args = mutableListOf("commit", "-m", message)
        if (stageAll) args.add(1, "-a")
        return runGit(args, repoPath)
    }

    @Tool
    @LLMDescription("Switch to a different branch or create a new branch.")
    suspend fun gitCheckout(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Branch name to switch to")
        branch: String,
        @LLMDescription("Whether to create the branch if it doesn't exist (equivalent to 'git checkout -b')")
        createNew: Boolean = false
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] checkout $branch")
        val args = mutableListOf("checkout")
        if (createNew) args.add("-b")
        // `--` separator blocks refs from being interpreted as flags (e.g. branch name "--author=x"
        // would otherwise be parsed as a git option). Pair with the identifier below.
        args.add("--")
        args.add(branch)
        return runGit(args, repoPath)
    }

    @Tool
    @LLMDescription("Pull changes from a remote repository.")
    suspend fun gitPull(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Remote name (default 'origin')")
        remote: String = "origin",
        @LLMDescription("Branch name (optional, uses current branch if empty)")
        branch: String = ""
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] pull $remote $branch")
        val args = mutableListOf("pull", remote)
        if (branch.isNotBlank()) args.add(branch)
        return runGit(args, repoPath)
    }

    @Tool
    @LLMDescription("Push commits to a remote repository.")
    suspend fun gitPush(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Remote name (default 'origin')")
        remote: String = "origin",
        @LLMDescription("Branch name (optional, uses current branch if empty)")
        branch: String = "",
        @LLMDescription("Whether to set upstream tracking (equivalent to 'git push -u')")
        setUpstream: Boolean = false
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] push $remote $branch")
        val args = mutableListOf("push")
        if (setUpstream) args.add("-u")
        args.add(remote)
        if (branch.isNotBlank()) args.add(branch)
        return runGit(args, repoPath)
    }

    @Tool
    @LLMDescription("Show details of a specific commit (diff, author, message).")
    suspend fun gitShow(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Commit hash or reference (e.g., 'HEAD', 'abc1234', 'HEAD~1')")
        commit: String = "HEAD"
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] show $commit")
        return runGit(listOf("show", "--stat", commit), repoPath)
    }

    @Tool
    @LLMDescription("Stash uncommitted changes to clean the working directory, or apply/list stashed changes.")
    suspend fun gitStash(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = "",
        @LLMDescription("Action: 'save' (default), 'pop', 'apply', 'list', 'drop'")
        action: String = "save",
        @LLMDescription("Optional message for 'save' action")
        message: String = ""
    ): String {
        printlnColor(AnsiColor.BLUE, "[Git] stash $action")
        val args = mutableListOf("stash")
        when (action.lowercase()) {
            "save" -> {
                if (message.isNotBlank()) {
                    args.add("push")
                    args.add("-m")
                    args.add(message)
                }
            }

            "pop" -> args.add("pop")
            "apply" -> args.add("apply")
            "list" -> args.add("list")
            "drop" -> args.add("drop")
            else -> return "Error: unknown stash action '$action'. Use: save, pop, apply, list, drop"
        }
        return runGit(args, repoPath)
    }

    @Tool
    @LLMDescription("Show a summary of the repository: current branch, remote URLs, recent commits, and status.")
    suspend fun gitSummary(
        @LLMDescription("Path to the Git repository (default: current directory)")
        repoPath: String = ""
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== Git Repository Summary ===")
        sb.appendLine()

        sb.appendLine("Branch:")
        sb.appendLine(runGit(listOf("branch", "--show-current"), repoPath).trim())
        sb.appendLine()

        sb.appendLine("Remotes:")
        sb.appendLine(runGit(listOf("remote", "-v"), repoPath).ifBlank { "  (none)" })
        sb.appendLine()

        sb.appendLine("Status:")
        sb.appendLine(runGit(listOf("status", "--short"), repoPath).ifBlank { "  Clean" })
        sb.appendLine()

        sb.appendLine("Recent commits (5):")
        sb.appendLine(runGit(listOf("log", "--oneline", "--decorate", "-n", "5"), repoPath).ifBlank { "  (none)" })

        return sb.toString()
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_OUTPUT_LENGTH = 50_000

        internal fun truncateOutput(output: String): String {
            return if (output.length > MAX_OUTPUT_LENGTH) {
                val half = MAX_OUTPUT_LENGTH / 2
                output.take(half) +
                    "\n\n... [OUTPUT TRUNCATED: ${output.length} chars total, showing first and last $half chars] ...\n\n" +
                    output.takeLast(half)
            } else {
                output
            }
        }
    }
}
