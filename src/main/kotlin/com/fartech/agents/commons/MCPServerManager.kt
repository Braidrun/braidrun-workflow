package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.AnsiColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.coroutines.delay

/**
 * Type of MCP server based on its implementation.
 */
enum class McpBundledServerType {
    /** Node.js/TypeScript server with package.json */
    NODE_JS,

    /** Python server with requirements.txt or pyproject.toml */
    PYTHON,

    /** Executable script (shell, etc.) */
    SCRIPT,

    /** Unknown or unsupported type */
    UNKNOWN
}

/**
 * Represents a running MCP server instance bundled with a skill.
 */
data class MCPServerInfo(
    val name: String,
    val skillName: String,
    val serverPath: Path,
    val serverType: McpBundledServerType,
    val process: Process,
    val port: Int? = null
)

/**
 * Manages the lifecycle of MCP servers bundled with skills.
 *
 * When a skill contains an `mcp-servers/` directory, each subdirectory is treated as
 * a standalone MCP server (Node.js TypeScript project). This manager:
 * - Detects MCP servers in skill directories
 * - Installs npm dependencies if needed
 * - Builds TypeScript projects
 * - Starts MCP server processes
 * - Manages server lifecycle (stop on skill unload)
 */
class MCPServerManager {
    private val runningServers = ConcurrentHashMap<String, MCPServerInfo>()

    companion object {
        private const val MCP_SERVERS_DIR = "mcp-servers"

        /**
         * Max bytes we read from an MCP server's stderr on startup failure. Keeps a
         * catastrophic crash-loop from trying to pull hundreds of MB into memory.
         */
        internal const val MAX_STARTUP_ERROR_BYTES: Int = 64 * 1024

        /**
         * Wall-clock cap for each subprocess we spawn during MCP server preparation
         * (`npm install`, `npm run build`, `pip install`, `python -m venv`). Prior to
         * Phase 10 these were unbounded `waitFor()` calls that could pin the JVM
         * indefinitely if a hung registry lookup or stuck filesystem stalled the
         * tool. 10 minutes is generous for dependency installation but will surface
         * a permanent hang.
         */
        internal const val PREPARE_STEP_TIMEOUT_SECONDS: Long = 600

        /** Bytes captured from each subprocess's combined stdout/stderr during prepare. */
        internal const val PREPARE_OUTPUT_MAX_BYTES: Int = 256 * 1024

        internal fun readBounded(stream: java.io.InputStream, maxBytes: Int): String {
            val buf = ByteArray(maxBytes.coerceAtLeast(1))
            var total = 0
            stream.use {
                while (total < buf.size) {
                    val n = it.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
            }
            val text = String(buf, 0, total, Charsets.UTF_8)
            return if (total == buf.size) "$text\n... [truncated at $maxBytes bytes]" else text
        }
    }

    /**
     * Run a prepare-time subprocess (npm/pip/venv) with a wall-clock timeout and a
     * bounded combined-output capture. Returns the (exitCode, output) pair, or null
     * when the timeout fires (process is destroyForcibly'd before return).
     *
     * The output stream is drained on a background thread so the subprocess can't
     * deadlock waiting for us to read its stdout/stderr (a real failure mode: pip
     * spamming progress bars to a pipe nobody reads). The drainer is bounded by
     * [PREPARE_OUTPUT_MAX_BYTES] but keeps reading-and-discarding past the cap.
     */
    private fun runPrepareStep(
        cmd: List<String>,
        workDir: java.io.File,
        timeoutSeconds: Long = PREPARE_STEP_TIMEOUT_SECONDS
    ): Pair<Int, String>? {
        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()

        val captured = StringBuilder()
        val drainer = Thread {
            try {
                val buf = ByteArray(8 * 1024)
                process.inputStream.use { stream ->
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (captured.length < PREPARE_OUTPUT_MAX_BYTES) {
                            val take = minOf(n, PREPARE_OUTPUT_MAX_BYTES - captured.length)
                            captured.append(String(buf, 0, take, Charsets.UTF_8))
                        }
                        // continue draining past cap to keep producer unblocked
                    }
                }
            } catch (_: Exception) {
                // pipe closed (process killed); return what we have
            }
        }
        drainer.isDaemon = true
        drainer.start()

        val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            drainer.join(2_000)
            val timeoutMessage = "[mcp prepare] subprocess ${cmd.joinToString(" ")} " +
                "exceeded ${timeoutSeconds}s timeout — destroyed"
            return null.also {
                logProgress(AnsiColor.RED, "MCP", "✗ $timeoutMessage")
            }
        }
        drainer.join(2_000)
        val exit = process.exitValue()
        val output = captured.toString().let { raw ->
            if (raw.length >= PREPARE_OUTPUT_MAX_BYTES) "$raw\n... [truncated at $PREPARE_OUTPUT_MAX_BYTES bytes]" else raw
        }
        return exit to output
    }

    /**
     * Discovers MCP servers in a skill directory.
     * Returns a list of server directory paths found in `<skillDir>/mcp-servers/`.
     * Supports Node.js, Python, and script-based servers.
     */
    fun discoverMCPServers(skillDir: Path): List<Path> {
        val mcpServersDir = skillDir.resolve(MCP_SERVERS_DIR)
        if (!mcpServersDir.exists() || !mcpServersDir.isDirectory()) {
            return emptyList()
        }

        return try {
            Files.list(mcpServersDir).use { stream ->
                stream
                    .filter { it.isDirectory() }
                    .filter { detectServerType(it) != McpBundledServerType.UNKNOWN }
                    .toList()
            }
        } catch (e: Exception) {
            logProgress(AnsiColor.YELLOW, "MCP", "⚠ Failed to discover MCP servers in $mcpServersDir: ${e.message}")
            emptyList()
        }
    }

    /**
     * Detects the type of MCP server based on files in the directory.
     */
    private fun detectServerType(serverPath: Path): McpBundledServerType {
        return when {
            // Node.js server: has package.json
            serverPath.resolve("package.json").exists() -> McpBundledServerType.NODE_JS

            // Python server: has requirements.txt, pyproject.toml, or setup.py
            serverPath.resolve("requirements.txt").exists() ||
                    serverPath.resolve("pyproject.toml").exists() ||
                    serverPath.resolve("setup.py").exists() -> McpBundledServerType.PYTHON

            // Script server: has an executable .sh, .bash, or run script
            serverPath.resolve("run.sh").exists() ||
                    serverPath.resolve("start.sh").exists() ||
                    serverPath.resolve("server.sh").exists() -> McpBundledServerType.SCRIPT

            else -> McpBundledServerType.UNKNOWN
        }
    }

    /**
     * Prepares an MCP server by installing dependencies and building it.
     * Returns true if successful, false otherwise.
     */
    suspend fun prepareMCPServer(serverPath: Path, skillName: String): Boolean = withContext(Dispatchers.IO) {
        val serverName = serverPath.name
        val serverType = detectServerType(serverPath)

        logProgress(AnsiColor.CYAN, "MCP", "Preparing MCP server '$serverName' ($serverType) for skill '$skillName'...")

        try {
            when (serverType) {
                McpBundledServerType.NODE_JS -> prepareNodeJsServer(serverPath, serverName)
                McpBundledServerType.PYTHON -> preparePythonServer(serverPath, serverName)
                McpBundledServerType.SCRIPT -> prepareScriptServer(serverPath, serverName)
                McpBundledServerType.UNKNOWN -> {
                    logProgress(AnsiColor.YELLOW, "MCP", "⚠ Unknown server type for '$serverName'")
                    false
                }
            }
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "MCP", "✗ Failed to prepare MCP server '$serverName': ${e.message}")
            false
        }
    }

    /**
     * Prepares a Node.js MCP server.
     */
    private fun prepareNodeJsServer(serverPath: Path, serverName: String): Boolean {
        // Check if node_modules exists
        val nodeModules = serverPath.resolve("node_modules")
        if (!nodeModules.exists()) {
            logProgress(AnsiColor.CYAN, "MCP", "  Installing npm dependencies for '$serverName'...")
            val (installExitCode, installOutput) = runPrepareStep(
                listOf("npm", "install"),
                serverPath.toFile()
            ) ?: run {
                logProgress(AnsiColor.RED, "MCP", "✗ npm install timed out for '$serverName'")
                return false
            }

            if (installExitCode != 0) {
                logProgress(AnsiColor.RED, "MCP", "✗ npm install failed for '$serverName': $installOutput")
                return false
            }
            logProgress(AnsiColor.GREEN, "MCP", "  ✓ npm install completed for '$serverName'")
        }

        // Check if build is needed (TypeScript project)
        val tsConfig = serverPath.resolve("tsconfig.json")
        if (tsConfig.exists()) {
            logProgress(AnsiColor.CYAN, "MCP", "  Building TypeScript project for '$serverName'...")
            val (buildExitCode, buildOutput) = runPrepareStep(
                listOf("npm", "run", "build"),
                serverPath.toFile()
            ) ?: run {
                logProgress(AnsiColor.RED, "MCP", "✗ npm build timed out for '$serverName'")
                return false
            }

            if (buildExitCode != 0) {
                logProgress(AnsiColor.RED, "MCP", "✗ npm build failed for '$serverName': $buildOutput")
                return false
            }
            logProgress(AnsiColor.GREEN, "MCP", "  ✓ Build completed for '$serverName'")
        }

        logProgress(AnsiColor.GREEN, "MCP", "✓ Node.js MCP server '$serverName' prepared successfully")
        return true
    }

    /**
     * Prepares a Python MCP server.
     */
    private fun preparePythonServer(serverPath: Path, serverName: String): Boolean {
        // Check for virtual environment
        val venvPath = serverPath.resolve("venv")
        val hasVenv = venvPath.exists()

        // Create virtual environment if it doesn't exist
        if (!hasVenv) {
            logProgress(AnsiColor.CYAN, "MCP", "  Creating Python virtual environment for '$serverName'...")
            val (venvExitCode, venvOutput) = runPrepareStep(
                listOf("python3", "-m", "venv", "venv"),
                serverPath.toFile(),
                timeoutSeconds = 120
            ) ?: run {
                logProgress(AnsiColor.RED, "MCP", "✗ venv creation timed out for '$serverName'")
                return false
            }

            if (venvExitCode != 0) {
                logProgress(AnsiColor.RED, "MCP", "✗ Failed to create venv for '$serverName': $venvOutput")
                return false
            }
            logProgress(AnsiColor.GREEN, "MCP", "  ✓ Virtual environment created for '$serverName'")
        }

        // Install dependencies if requirements.txt or pyproject.toml exists
        val requirementsTxt = serverPath.resolve("requirements.txt")
        val pyprojectToml = serverPath.resolve("pyproject.toml")

        if (requirementsTxt.exists()) {
            logProgress(AnsiColor.CYAN, "MCP", "  Installing Python dependencies for '$serverName'...")
            val (pipExitCode, pipOutput) = runPrepareStep(
                listOf(
                    serverPath.resolve("venv/bin/pip").toString(),
                    "install",
                    "-r",
                    "requirements.txt"
                ),
                serverPath.toFile()
            ) ?: run {
                logProgress(AnsiColor.RED, "MCP", "✗ pip install timed out for '$serverName'")
                return false
            }

            if (pipExitCode != 0) {
                logProgress(AnsiColor.RED, "MCP", "✗ pip install failed for '$serverName': $pipOutput")
                return false
            }
            logProgress(AnsiColor.GREEN, "MCP", "  ✓ Python dependencies installed for '$serverName'")
        } else if (pyprojectToml.exists()) {
            logProgress(AnsiColor.CYAN, "MCP", "  Installing Python project for '$serverName'...")
            val (pipExitCode, pipOutput) = runPrepareStep(
                listOf(
                    serverPath.resolve("venv/bin/pip").toString(),
                    "install",
                    "-e",
                    "."
                ),
                serverPath.toFile()
            ) ?: run {
                logProgress(AnsiColor.RED, "MCP", "✗ pip install timed out for '$serverName'")
                return false
            }

            if (pipExitCode != 0) {
                logProgress(AnsiColor.RED, "MCP", "✗ pip install failed for '$serverName': $pipOutput")
                return false
            }
            logProgress(AnsiColor.GREEN, "MCP", "  ✓ Python project installed for '$serverName'")
        }

        logProgress(AnsiColor.GREEN, "MCP", "✓ Python MCP server '$serverName' prepared successfully")
        return true
    }

    /**
     * Prepares a script-based MCP server (makes scripts executable).
     */
    private fun prepareScriptServer(serverPath: Path, serverName: String): Boolean {
        val scriptFiles = listOf("run.sh", "start.sh", "server.sh")

        scriptFiles.forEach { scriptName ->
            val scriptPath = serverPath.resolve(scriptName)
            if (scriptPath.exists()) {
                try {
                    logProgress(AnsiColor.CYAN, "MCP", "  Making '$scriptName' executable for '$serverName'...")
                    // Merge stderr into stdout and discard the whole stream — chmod is
                    // silent on success and we don't care about the error text. Without
                    // this the process's output pipe can fill up and deadlock waitFor().
                    // Bound the wait so a hung chmod (extremely unlikely but possible on
                    // stuck filesystems) cannot block startup indefinitely.
                    val proc = ProcessBuilder("chmod", "+x", scriptPath.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    val completed = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                    if (!completed) {
                        proc.destroyForcibly()
                        logProgress(AnsiColor.YELLOW, "MCP", "⚠ chmod timed out for '$scriptName'; skipping")
                    } else {
                        logProgress(AnsiColor.GREEN, "MCP", "  ✓ Made '$scriptName' executable")
                    }
                } catch (e: Exception) {
                    logProgress(AnsiColor.YELLOW, "MCP", "⚠ Failed to chmod '$scriptName': ${e.message}")
                }
            }
        }

        logProgress(AnsiColor.GREEN, "MCP", "✓ Script MCP server '$serverName' prepared successfully")
        return true
    }

    /**
     * Starts an MCP server process.
     * The server is expected to communicate via stdio (standard MCP protocol).
     * Returns the MCPServerInfo if successful, null otherwise.
     */
    suspend fun startMCPServer(serverPath: Path, skillName: String): MCPServerInfo? = withContext(Dispatchers.IO) {
        val serverName = serverPath.name
        val serverKey = "$skillName:$serverName"
        val serverType = detectServerType(serverPath)

        // Fast path: if already running, return existing entry. Final TOCTOU is closed
        // below via putIfAbsent, so this only short-circuits the common case.
        runningServers[serverKey]?.let {
            logProgress(AnsiColor.YELLOW, "MCP", "⚠ MCP server '$serverName' already running for skill '$skillName'")
            return@withContext it
        }

        var process: Process? = null
        try {
            logProgress(
                AnsiColor.CYAN,
                "MCP",
                "Starting MCP server '$serverName' ($serverType) for skill '$skillName'..."
            )

            process = when (serverType) {
                McpBundledServerType.NODE_JS -> startNodeJsServer(serverPath, serverName)
                McpBundledServerType.PYTHON -> startPythonServer(serverPath, serverName)
                McpBundledServerType.SCRIPT -> startScriptServer(serverPath, serverName)
                McpBundledServerType.UNKNOWN -> {
                    logProgress(AnsiColor.RED, "MCP", "✗ Unknown server type for '$serverName'")
                    return@withContext null
                }
            } ?: return@withContext null

            // Give it a moment to start
            delay(500)

            // Check if process is still alive
            if (!process.isAlive) {
                // Cap how much stderr we read; a misconfigured server can produce hundreds
                // of MB of error output and `readText()` would load it all into memory.
                val errorOutput = readBounded(process.errorStream, MAX_STARTUP_ERROR_BYTES)
                logProgress(AnsiColor.RED, "MCP", "✗ MCP server '$serverName' failed to start: $errorOutput")
                return@withContext null
            }

            val serverInfo = MCPServerInfo(
                name = serverName,
                skillName = skillName,
                serverPath = serverPath,
                serverType = serverType,
                process = process
            )

            // Atomic register-or-lose race. If another concurrent invocation already won,
            // destroy our duplicate process so we don't leak it and return the winner.
            val winner = runningServers.putIfAbsent(serverKey, serverInfo)
            if (winner != null) {
                logProgress(
                    AnsiColor.YELLOW, "MCP",
                    "⚠ Concurrent start detected for '$serverName'; keeping the first instance and stopping the duplicate"
                )
                // destroyForcibly() may itself fail (SIGKILL refused, PID gone, JVM
                // security manager). Previously a silent runCatching hid those — which
                // meant a zombie MCP subprocess could sit there untracked. Now we log
                // the failure so operations notices and can hunt the PID manually.
                val destroyed = runCatching { process.destroyForcibly() }
                destroyed.exceptionOrNull()?.let { ex ->
                    logProgress(
                        AnsiColor.RED, "MCP",
                        "✗ Failed to destroy duplicate process for '$serverName' (pid=${runCatching { process.pid() }.getOrNull()}): ${ex.message}"
                    )
                }
                return@withContext winner
            }
            // Ownership transferred to runningServers — clear local handle so the
            // finally block does NOT destroy a now-registered process.
            val ownedProcess = process
            process = null
            logProgress(AnsiColor.GREEN, "MCP", "✓ MCP server '$serverName' started for skill '$skillName'")

            // Start background thread to monitor server output
            Thread {
                try {
                    BufferedReader(InputStreamReader(ownedProcess.errorStream)).use { reader ->
                        reader.lines().forEach { line ->
                            logProgress(AnsiColor.CYAN, "MCP[$serverName]", line)
                        }
                    }
                } catch (e: Exception) {
                    // Server stopped
                }
            }.apply {
                isDaemon = true
                name = "MCP-$serverName-monitor"
                start()
            }
            // Drain stdout too: nothing in-process consumes these servers' stdio
            // protocol channel today, and a server that writes >64 KiB to stdout
            // would block on a full pipe and wedge until the shutdown
            // destroyForcibly. Discard (don't log) — stdout is the JSON-RPC
            // channel, not human-readable logs.
            Thread {
                try {
                    ownedProcess.inputStream.use { stream ->
                        val buf = ByteArray(8 * 1024)
                        @Suppress("ControlFlowWithEmptyBody")
                        while (stream.read(buf) >= 0) {
                        }
                    }
                } catch (e: Exception) {
                    // Server stopped
                }
            }.apply {
                isDaemon = true
                name = "MCP-$serverName-stdout-drain"
                start()
            }

            serverInfo
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "MCP", "✗ Failed to start MCP server '$serverName': ${e.message}")
            null
        } finally {
            // If `process` is still non-null here we either failed before registration
            // or hit an early return — guarantee no orphaned OS process is left behind.
            process?.let { p ->
                val destroyed = runCatching { p.destroyForcibly() }
                destroyed.exceptionOrNull()?.let { ex ->
                    logProgress(
                        AnsiColor.RED, "MCP",
                        "✗ Failed to clean up MCP process for '$serverName' (pid=${runCatching { p.pid() }.getOrNull()}): ${ex.message}"
                    )
                }
            }
        }
    }

    /**
     * Starts a Node.js MCP server.
     */
    private fun startNodeJsServer(serverPath: Path, serverName: String): Process? {
        // For now, assume the built output is index.js in the server directory
        val entryPoint = serverPath.resolve("index.js")
        if (!entryPoint.exists()) {
            logProgress(AnsiColor.RED, "MCP", "✗ Built entry point (index.js) not found for '$serverName'")
            return null
        }

        // Start the MCP server process with node
        return ProcessBuilder("node", entryPoint.toString())
            .directory(serverPath.toFile())
            .redirectErrorStream(false)
            .start()
    }

    /**
     * Starts a Python MCP server.
     */
    private fun startPythonServer(serverPath: Path, serverName: String): Process? {
        // Look for main entry points in order of preference
        val entryPoints = listOf(
            "server.py",
            "main.py",
            "__main__.py",
            "src/server.py",
            "src/main.py"
        )

        val entryPoint = entryPoints
            .map { serverPath.resolve(it) }
            .firstOrNull { it.exists() }

        if (entryPoint == null) {
            logProgress(
                AnsiColor.RED,
                "MCP",
                "✗ No Python entry point found for '$serverName' (tried: ${entryPoints.joinToString(", ")})"
            )
            return null
        }

        // Use the virtual environment's Python if available
        val pythonExecutable = serverPath.resolve("venv/bin/python")
        val pythonCommand = if (pythonExecutable.exists()) {
            pythonExecutable.toString()
        } else {
            "python3"
        }

        // Start the MCP server process with Python
        return ProcessBuilder(pythonCommand, entryPoint.toString())
            .directory(serverPath.toFile())
            .redirectErrorStream(false)
            .start()
    }

    /**
     * Starts a script-based MCP server.
     */
    private fun startScriptServer(serverPath: Path, serverName: String): Process? {
        // Look for start scripts in order of preference
        val scriptFiles = listOf("run.sh", "start.sh", "server.sh")

        val scriptPath = scriptFiles
            .map { serverPath.resolve(it) }
            .firstOrNull { it.exists() }

        if (scriptPath == null) {
            logProgress(AnsiColor.RED, "MCP", "✗ No start script found for '$serverName'")
            return null
        }

        // Start the script
        return ProcessBuilder(scriptPath.toString())
            .directory(serverPath.toFile())
            .redirectErrorStream(false)
            .start()
    }

    /**
     * Stops a running MCP server.
     */
    fun stopMCPServer(skillName: String, serverName: String) {
        val serverKey = "$skillName:$serverName"
        val serverInfo = runningServers.remove(serverKey) ?: return

        try {
            logProgress(AnsiColor.CYAN, "MCP", "Stopping MCP server '$serverName' for skill '$skillName'...")
            serverInfo.process.destroy()

            // Give it 5 seconds to shut down gracefully
            if (!serverInfo.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logProgress(AnsiColor.YELLOW, "MCP", "⚠ Force-killing MCP server '$serverName'...")
                serverInfo.process.destroyForcibly()
            }

            logProgress(AnsiColor.GREEN, "MCP", "✓ MCP server '$serverName' stopped")
        } catch (e: Exception) {
            logProgress(AnsiColor.YELLOW, "MCP", "⚠ Error stopping MCP server '$serverName': ${e.message}")
        }
    }

    /**
     * Stops all MCP servers for a given skill.
     */
    fun stopAllServersForSkill(skillName: String) {
        val serversToStop = runningServers.keys.filter { it.startsWith("$skillName:") }
        serversToStop.forEach { serverKey ->
            val serverName = serverKey.substringAfter(":")
            stopMCPServer(skillName, serverName)
        }
    }

    /**
     * Stops all running MCP servers.
     */
    fun stopAllServers() {
        val servers = runningServers.values.toList()
        servers.forEach { server ->
            stopMCPServer(server.skillName, server.name)
        }
    }

    /**
     * Gets information about all running MCP servers.
     */
    fun getRunningServers(): List<MCPServerInfo> {
        return runningServers.values.toList()
    }

    /**
     * Gets information about MCP servers running for a specific skill.
     */
    fun getServersForSkill(skillName: String): List<MCPServerInfo> {
        return runningServers.values.filter { it.skillName == skillName }
    }

    /**
     * Checks if an MCP server is running.
     */
    fun isServerRunning(skillName: String, serverName: String): Boolean {
        val serverKey = "$skillName:$serverName"
        return runningServers.containsKey(serverKey) && runningServers[serverKey]?.process?.isAlive == true
    }
}
