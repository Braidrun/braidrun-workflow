package com.fartech.agents.commons

import ai.koog.agents.core.agent.GraphAIAgent
// Koog 1.0.0 dropped the 0.x `ai.koog.agents.core.agent.ToolCalls` enum.
// We use the local replacement in [Koog1xCompat] (same package, no
// explicit import required) which preserves the 3-way `SEQUENTIAL` /
// `SINGLE_RUN_SEQUENTIAL` / `PARALLEL` semantic.
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.chatAgentStrategy
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.model.StructureFixingParser
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import com.fartech.ftapp2.commonsKt.printlnColor
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

// ============================================================================
// AgentCommon — shared utilities + strategy dispatcher
// ============================================================================
//
// This file has been progressively slimmed across the 2026-04 audit:
//
//   - **Phase 4** extracted subprocess-executor construction →
//     [SubprocessExecutorFactory]
//   - **Phase 5** extracted:
//       · tool-registry construction     → [ToolRegistryBuilder.kt]
//         (`getDefaultToolRegistry`, `parseToolSet`, `parseExactToolSet`,
//          `buildToolRegistry`, `browserToolsDisabled`)
//       · prompt-executor factory        → [PromptExecutorFactory.kt]
//         (`determineCachePolicy`, `createPromptExecutor`)
//       · agent lifecycle glue           → [AgentBootstrap.kt]
//         (`buildAgent`, `buildAndRunAgent`, `buildAndRunStringAgent`,
//          `buildAndRunStructure*`, `buildAndRunConfiguredAgentWithStructuredOutput`,
//          `defaultInstallFeatures`, `streamCollectNode`, `extractJsonFromResponse`,
//          `structuredOutputJson`)
//
// What's left here is the irreducible shared surface:
//   1. Progress-logger helpers (used by every other file in the package)
//   2. `ReasoningMonitorCallback` / `MonitoringEventCallback` typealiases
//   3. `Defaults` + `DEFAULT_FIXING_PARSER` (referenced from `AgentStrategies.kt`)
//   4. Environment-info helpers (`compactEnvSettings`, `envSettings`) and the
//      private `EnvCache` that memoizes interpreter detection across calls
//   5. `determineDefaultStrategy` — the `strategy` parameter → concrete
//      [AIAgentGraphStrategy] dispatcher. Pure function; kept here because
//      it straddles agent-bootstrap and strategy-library concerns and doesn't
//      fit naturally in either.
// ============================================================================

// ----------------------------------------------------------------------------
// Progress Logger Utilities
// ----------------------------------------------------------------------------
fun currentTimestamp(): String = java.time.LocalDateTime.now().format(
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
)

fun logProgress(color: String, phase: String, message: String) {
    printlnColor(color, "[${currentTimestamp()}] [${phase}] $message")
}

typealias ReasoningMonitorCallback = (reasoning: String) -> Unit
typealias MonitoringEventCallback = (type: String, summary: String, detail: String?) -> Unit

object Defaults {
    const val DEFAULT_RETRY_CYCLES = 20
    const val DEFAULT_STRUCTURE_FIX_RETRIES = 3
}

val DEFAULT_FIXING_PARSER = StructureFixingParser(
    model = DEFAULT_LLM_MODEL,
    retries = Defaults.DEFAULT_STRUCTURE_FIX_RETRIES
)

// ----------------------------------------------------------------------------
// Environment info cache + helpers
// ----------------------------------------------------------------------------
//
// These spawn `which` / `<cmd> --version` subprocesses to discover the
// interpreter versions visible to the running JVM. Memoize aggressively —
// every agent start-up used to do this work N times per session.
// ----------------------------------------------------------------------------

private object EnvCache {
    data class InterpreterInfo(val path: String, val version: String)

    private val cache = ConcurrentHashMap<String, InterpreterInfo>()

    private val osName = System.getProperty("os.name", "Unknown").lowercase()
    private val isWindows = osName.contains("win")

    fun getInterpreter(command: String, versionArg: String = "--version"): InterpreterInfo {
        return cache.getOrPut(command) {
            val path = findInterpreter(command)
            val version = if (path != "Not found") getInterpreterVersion(command, versionArg) else "Not installed"
            InterpreterInfo(path, version)
        }
    }

    /**
     * Get python interpreter, trying python3 first then python as fallback.
     */
    fun getPythonInterpreter(): InterpreterInfo {
        return cache.getOrPut("python") {
            val python3Path = findInterpreter("python3")
            if (python3Path != "Not found") {
                val version = getInterpreterVersion("python3")
                InterpreterInfo(python3Path, version)
            } else {
                val pythonPath = findInterpreter("python")
                val version = if (pythonPath != "Not found") getInterpreterVersion("python") else "Not installed"
                InterpreterInfo(pythonPath, version)
            }
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun findInterpreter(command: String): String {
        return try {
            val probeCmd = if (isWindows) {
                listOf("cmd", "/c", "where", command)
            } else {
                listOf("which", command)
            }
            val result = SubprocessSafety.runCapturedWithTimeout(
                command = probeCmd,
                timeoutSeconds = INTERPRETER_PROBE_TIMEOUT_SECONDS,
                maxOutputBytes = 16 * 1024
            )
            if (result.timedOut) {
                logProgress(AnsiColor.YELLOW, "EnvSettings", "⚠ Probe '$command' timed out after ${INTERPRETER_PROBE_TIMEOUT_SECONDS}s")
                return "Not found"
            }
            val trimmed = result.output.trim()
            if (result.exitCode == 0 && trimmed.isNotEmpty()) {
                trimmed.lines().firstOrNull() ?: "Not found"
            } else {
                "Not found"
            }
        } catch (e: Exception) {
            logProgress(AnsiColor.YELLOW, "EnvSettings", "⚠ Failed to find interpreter '$command': ${e.message}")
            "Not found"
        }
    }

    private fun getInterpreterVersion(command: String, versionArg: String = "--version"): String {
        return try {
            val result = SubprocessSafety.runCapturedWithTimeout(
                command = listOf(command, versionArg),
                timeoutSeconds = INTERPRETER_PROBE_TIMEOUT_SECONDS,
                maxOutputBytes = 16 * 1024
            )
            if (result.timedOut) {
                logProgress(AnsiColor.YELLOW, "EnvSettings", "⚠ Version probe '$command $versionArg' timed out after ${INTERPRETER_PROBE_TIMEOUT_SECONDS}s")
                return "Unknown"
            }
            val trimmed = result.output.trim()
            if (result.exitCode == 0 && trimmed.isNotEmpty()) {
                trimmed.lines().firstOrNull { it.isNotBlank() } ?: "Unknown"
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            logProgress(AnsiColor.YELLOW, "EnvSettings", "⚠ Failed to get version for '$command': ${e.message}")
            "Unknown"
        }
    }

    /** Wall-clock cap on `which` / `--version` probes. 5 s is generous — these should be instant. */
    private const val INTERPRETER_PROBE_TIMEOUT_SECONDS = 5L
}

/**
 * Short environment summary injected into the LLM prompt when
 * `compact_env=true` (default). ~70% fewer tokens than [envSettings];
 * interpreter versions are included only when the configured `tool_set`
 * references a tool group that uses them.
 */
fun compactEnvSettings(parameters: List<ConfigurationParameter>): String {
    val dir = System.getProperty("user.dir")
    val workingDir = parameters.parameter("working_dir", dir)
    val outputDir = parameters.parameter("output_dir", dir)
    val language = parameters.parameter("language", Locale.getDefault().language)
    val osInfo = "${System.getProperty("os.name", "Unknown")} ${System.getProperty("os.arch", "Unknown")}"

    return buildString {
        appendLine("Date: ${Date()}")
        appendLine("OS: $osInfo")
        appendLine("Working directory: $workingDir")
        if (outputDir != workingDir) appendLine("Output directory: $outputDir")
        appendLine("Language: $language")
        // Only include interpreters that are actually relevant based on tool_set
        val toolSet = parameters.parameter("tool_set", emptyList<String>().toMutableSet())
        if (toolSet.contains("code_execution") || toolSet.contains("shell")) {
            val python = EnvCache.getPythonInterpreter()
            if (python.path != "Not found") appendLine("Python: ${python.version}")
            val node = EnvCache.getInterpreter("node")
            if (node.path != "Not found") appendLine("Node.js: ${node.version}")
        }
        if (toolSet.contains("git")) {
            val git = EnvCache.getInterpreter("git")
            if (git.path != "Not found") appendLine("Git: ${git.version}")
        }
    }.trimEnd()
}

/**
 * Full environment summary, used when `compact_env=false`. Adds interpreter
 * paths + versions for python / node / npm / git / java, plus memory /
 * filesystem-separator / user-agent info. Legacy — prefer [compactEnvSettings]
 * to keep LLM token usage down.
 */
fun envSettings(
    parameters: List<ConfigurationParameter>
): String {
    val dir = System.getProperty("user.dir")
    val agentVersion = parameters.parameter("agent_version", "1.0.0")

    // Use cached interpreter detection
    val python = EnvCache.getPythonInterpreter()
    val pythonPath = python.path
    val pythonVersion = python.version

    val node = EnvCache.getInterpreter("node")
    val nodePath = node.path
    val nodeVersion = node.version

    val git = EnvCache.getInterpreter("git")
    val gitPath = git.path
    val gitVersion = git.version

    val java = EnvCache.getInterpreter("java")
    val javaPath = java.path

    val npm = EnvCache.getInterpreter("npm")
    val npmPath = npm.path
    val npmVersion = npm.version

    // Get system information safely
    val javaHome = System.getProperty("java.home", "Unknown")
    val agentExecutionPath = System.getProperty("user.dir", "Unknown")
    val tempDir = System.getProperty("java.io.tmpdir", "Unknown")
    val userName = System.getProperty("user.name", "Unknown")
    val userHome = System.getProperty("user.home", "Unknown")
    val fileSeparator = System.getProperty("file.separator", "/")
    val pathSeparator = System.getProperty("path.separator", ":")
    val lineSeparator = System.getProperty("line.separator", "\n").replace("\n", "\\n").replace("\r", "\\r")

    // Memory information with better formatting
    val totalMemoryMB = Runtime.getRuntime().totalMemory() / 1024 / 1024
    val maxMemoryMB = Runtime.getRuntime().maxMemory() / 1024 / 1024
    val freeMemoryMB = Runtime.getRuntime().freeMemory() / 1024 / 1024
    val usedMemoryMB = totalMemoryMB - freeMemoryMB

    return """
                Current date/time: ${Date()} local.
                Current timezone: ${TimeZone.getDefault()} local.
                Running directory: $dir
                Working directory: ${parameters.parameter("working_dir", dir)}
                Output directory: ${parameters.parameter("output_dir", dir)}
                Agent execution path: $agentExecutionPath
                Command That Started the Agent (You): braidrun-cli -c 'configuration file name'
                Language You Should Use: ${parameters.parameter("language", Locale.getDefault().language)}
                Current Running OS: ${System.getProperty("os.name", "Unknown")} ${
        System.getProperty(
            "os.version",
            "Unknown"
        )
    } ${System.getProperty("os.arch", "Unknown")}
                File separator: $fileSeparator
                Path separator: $pathSeparator
                Line separator: $lineSeparator
                Current Running JVM: ${
        System.getProperty(
            "java.vendor",
            "Unknown"
        )
    } ${System.getProperty("java.version", "Unknown")} ${System.getProperty("java.runtime.version", "Unknown")}
                Java Home: $javaHome
                Java Executable: $javaPath
                Current Running CPU: ${Runtime.getRuntime().availableProcessors()} cores
                Current Memory Usage: $usedMemoryMB MB used / $totalMemoryMB MB total / $maxMemoryMB MB max
                Free Memory: $freeMemoryMB MB
                System User: $userName
                User Home: $userHome
                Temp Directory: $tempDir
                Python Interpreter: $pythonPath
                Python Version: $pythonVersion
                Node.js Interpreter: $nodePath
                Node.js Version: $nodeVersion
                NPM: $npmPath
                NPM Version: $npmVersion
                Git: $gitPath
                Git Version: $gitVersion
                The UserAgent You Are Using To Access The Internet: ${
        parameters.parameter(
            "user_agent",
            "UniversalAIAgent/$agentVersion"
        )
    }
    """.trimIndent()
}

// ----------------------------------------------------------------------------
// Strategy dispatcher
// ----------------------------------------------------------------------------

/**
 * Map the `strategy` parameter name to the concrete [AIAgentGraphStrategy].
 * Defaults to a sequential single-run strategy when the name is unknown
 * (use `strategy: single_run_parallel` explicitly for parallel tool calls).
 *
 * Kept as a pure function in this file because the set of known strategy
 * names is a contract between agent YAML configs and the runtime — bundling
 * it with any one strategy-library file would make the dependency direction
 * awkward.
 */
fun determineDefaultStrategy(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    toolRegistry: ToolRegistry = parseToolSet(parameters, httpAccess, emptyList<AgentTools>()),
    strategyParameterName: String = "strategy",
    skillManager: SkillManager? = null,
    onReasoningMessage: ReasoningMonitorCallback? = null,
): AIAgentGraphStrategy<String, String> {
    val historyMessages = loadHistoryMessages(parameters)
    val sessionKey = parameters.parameter("session_id", "")
    return when (parameters.parameter(strategyParameterName, "default")) {
        "tone" -> toneStrategy(
            name = parameters.parameter("strategy_name", "tone"),
            historyCompression = parameters.parameter("history_compression", null),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        "tone_reasoning" -> parameters.toneReasoningStrategy(
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
            onReasoningMessage = onReasoningMessage,
        )

        "plan_solve" -> planSolveStrategy(
            name = parameters.parameter("strategy_name", "plan_solve"),
            tools = toolRegistry,
            historyCompression = parameters.parameter("history_compression", null),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        "plan_solve_reasoning" -> planSolveStrategyWithReasoning(
            name = parameters.parameter("strategy_name", "plan_solve_reasoning"),
            tools = toolRegistry,
            historyCompression = parameters.parameter("history_compression", null),
            showReasoning = parameters.parameter("show_reasoning", true),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
            onReasoningMessage = onReasoningMessage,
        )

        "chat" -> chatAgentStrategy()
        "chat_with_summary" -> chatWithSummaryStrategy(
            historyMessages = historyMessages,
            historyCompression = parameters.parameter("history_compression", null),
            skillManager = skillManager,
            sessionKey = sessionKey
        )
        "continue_chat" -> continuousChatStrategy(
            historyMessages = historyMessages,
            historyCompression = parameters.parameter("history_compression", null),
            skillManager = skillManager,
            sessionKey = sessionKey
        )
        "just_work" -> justDoWorkStrategy(
            httpAccess,
            parameters,
            toolRegistry,
            attachments = parameters.parameter("attachments", emptyList()),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        "just_work_parallel" -> justDoWorkStrategy(
            httpAccess,
            parameters,
            toolRegistry,
            attachments = parameters.parameter("attachments", emptyList()),
            runMode = ToolCalls.PARALLEL,
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        "just_work_parallel_reasoning" -> justDoWorkWithReasoningStrategy(
            httpAccess,
            parameters,
            toolRegistry,
            attachments = parameters.parameter("attachments", emptyList()),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
            onReasoningMessage = onReasoningMessage,
        )

        "react" -> parameters.reactStrategy(
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
            onReasoningMessage = onReasoningMessage,
        )

        "react_original" -> reActStrategy()
        "single_run" -> singleRunWithParallelAbility(
            name = parameters.parameter("strategy_name", "__single_run__"),
            parallel = false,
            historyCompression = parameters.parameter("history_compression", null),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        "single_run_parallel" -> singleRunWithParallelAbility(
            name = parameters.parameter("strategy_name", "__single_run_parallel__"),
            parallel = true,
            historyCompression = parameters.parameter("history_compression", null),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )

        "single_run_reasoning" -> singleRunWithParallelAbilityWithReasoning(
            name = parameters.parameter("strategy_name", "__single_run_reasoning__"),
            parallel = false,
            historyCompression = parameters.parameter("history_compression", null),
            reasoningInterval = parameters.parameter("reasoning_interval", 1),
            showReasoning = parameters.parameter("show_reasoning", true),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
            onReasoningMessage = onReasoningMessage,
        )

        "single_run_parallel_reasoning" -> singleRunWithParallelAbilityWithReasoning(
            name = parameters.parameter("strategy_name", "__single_run_parallel_reasoning__"),
            parallel = true,
            historyCompression = parameters.parameter("history_compression", null),
            reasoningInterval = parameters.parameter("reasoning_interval", 1),
            showReasoning = parameters.parameter("show_reasoning", true),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
            onReasoningMessage = onReasoningMessage,
        )

        // Default/unknown strategy names run SEQUENTIAL tool execution. The old
        // default name "__single_run_parallel__" claimed parallel tool calls that
        // never happened — operators diagnosing throughput were misled by traces.
        else -> singleRunWithParallelAbility(
            name = parameters.parameter("strategy_name", "__single_run__"),
            parallel = false,
            historyCompression = parameters.parameter("history_compression", null),
            historyMessages = historyMessages,
            skillManager = skillManager,
            sessionKey = sessionKey,
        )
    }
}
