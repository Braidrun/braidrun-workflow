package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.mcp.McpToolRegistryProvider
// Koog 1.0.0 — `defaultStdioTransport` is still a top-level extension fun on
// `McpToolRegistryProvider` (declared in `McpToolRegistryProvider.jvm.kt`).
// Import path is unchanged from 0.x.
import ai.koog.agents.mcp.defaultStdioTransport
import ai.koog.agents.mcp.metadata.McpServerInfo
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.parameter
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File

private val mcpUtilsLogger = KotlinLogging.logger("com.fartech.agents.commons.AgentMcpUtils")

@Serializable
data class McpServerConfig(
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val cwd: String? = null,
    val url: String? = null,
    val type: String? = "stdio",
    val timeout: Long = 30000, // 30秒超时
    val enabled: Boolean = true,
    val description: String? = null
)

internal data class ResolvedMcpLaunch(
    val command: String,
    val args: List<String>,
    val cwd: File?,
    val env: Map<String, String>
)

@Serializable
@LLMDescription("the tool set that sub agent can use to accomplish its task")
enum class AgentTools {
    @LLMDescription("A tool set that interactive with users like asking questions and getting answers, and speaking to users")
    INTERACTIVE,

    @LLMDescription("A tool that agent can use to exit")
    EXIT,

    @LLMDescription("A tool that allowed agent to run sub agents to accomplish a specified task")
    SUB_AGENT,

    @LLMDescription("A tool set that performs file operations like reading and writing files, listing directory and so on")
    FILE_OPERATIONS,

    @LLMDescription("A tool set that performs shell operations like executing shell commands")
    SHELL,

    @LLMDescription("A tool set that searches and access Web pages")
    WEB_TOOLS,

    @LLMDescription("A tool set that performs PDF operations like creating, editing, and converting PDF files")
    PDF_TOOLS,

    @LLMDescription("A tool set that performs iWork documents operations like creating, editing, and converting iWork documents")
    IWORK_TOOLS,

    @LLMDescription("A tool set that performs Office documents operations like creating, editing, and converting Office documents")
    OFFICE_TOOLS,

    @LLMDescription("A tool set that performs Microsoft Word document operations like creating, editing, and converting .docx files")
    WORD_TOOLS,

    @LLMDescription("A tool set that performs Microsoft Excel workbook operations like creating, editing, and converting .xlsx files")
    EXCEL_TOOLS,

    @LLMDescription("A tool set that performs Microsoft PowerPoint presentation operations like creating, editing, and converting .pptx files")
    POWERPOINT_TOOLS,

    @LLMDescription("A tool set that performs CSV operations like creating, editing, and converting CSV files")
    CSV_TOOLS,

    @LLMDescription("A tool set that performs image and audio generation operations like creating and editing images and audio files")
    MULTI_MEDIA,

    @LLMDescription("A tool set that retrieves Apple App Store information for competitive analysis and app research")
    APPLE_APP_INFO,

    @LLMDescription("A tool set that downloads, inspects, and manages Claude skills dynamically")
    SKILL_TOOLS,

    @LLMDescription("A tool set for browser automation using Playwright, including navigation, interaction, and screenshots")
    BROWSER,

    @LLMDescription("A tool set for interacting with users via instant messaging services such as Telegram Bot")
    IM_TOOLS,

    @LLMDescription("A tool set for file management operations like copy, move, delete, rename, search, zip/unzip, and hash computation")
    FILE_MANAGEMENT,

    @LLMDescription("A tool set for executing Python, JavaScript, and other code snippets with package management support")
    CODE_EXECUTION,

    @LLMDescription("A tool set for querying SQLite and JDBC databases, listing tables, and describing schemas")
    DATABASE,

    @LLMDescription("A tool set for image processing operations like resize, crop, rotate, convert, compress, merge, and watermark")
    IMAGE_PROCESSING,

    @LLMDescription("A tool set for sending and reading emails via SMTP and IMAP")
    EMAIL,

    @LLMDescription("A tool set for persistent cross-session memory and note/knowledge management")
    KNOWLEDGE_MEMORY,

    @LLMDescription("A tool set for data transformation: JSON/YAML conversion, Base64, URL encoding, Markdown/HTML, XML parsing")
    DATA_TRANSFORM,

    @LLMDescription("A tool set for calendar, date/time operations, timezone conversions, and date calculations")
    CALENDAR,

    @LLMDescription("A tool set for Git version control operations like status, diff, commit, branch, push, pull")
    GIT,

    @LLMDescription("A tool set for OCR (Optical Character Recognition) to extract text from images using Tesseract")
    OCR,

    @LLMDescription("RAG (Retrieval-Augmented Generation) tools for document indexing and semantic search over a vector knowledge base")
    RAG_TOOLS,

    @LLMDescription("A tool set for spawning external agent SDKs (Anthropic Claude Code, OpenAI Codex) as sub-agents — useful for delegating complex coding tasks to a complete external agentic runtime")
    EXTERNAL_AGENT

}

@Serializable
enum class McpServerType {
    @SerialName("stdio")
    STDIO,

    @SerialName("sse")
    SSE,

    @SerialName("websocket")
    WEBSOCKET,

    @SerialName("http")
    HTTP
}

private fun Map<String, McpServerConfig>.getEnabledServers(): Map<String, McpServerConfig> = filterValues { it.enabled }

private fun String.isNotBlankPath(): Boolean = isNotBlank() && (contains("/") || contains("\\") || startsWith("."))

private fun currentUserDir(): File? = System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let(::File)

private fun resolvePath(path: String, baseDir: File? = null): File {
    val raw = File(path)
    if (raw.isAbsolute) return raw

    return (baseDir?.resolve(path) ?: raw).absoluteFile.toPath().normalize().toFile()
}

private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("windows")

private fun hasExplicitEnvArg(args: List<String>): Boolean = args.any { it == "--env" || it == "-e" }

private val MCP_STDIO_ENV_ALLOWLIST = setOf(
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TZ",
    "TMPDIR",
    "TEMP",
    "TMP",
    "SHELL",
    "PWD",
    "SYSTEMROOT",
    "COMSPEC"
)

internal fun applyMcpLaunchEnvironment(
    processBuilder: ProcessBuilder,
    launchEnv: Map<String, String>
) {
    val env = processBuilder.environment()
    val parentSnapshot = HashMap(env)
    env.clear()

    val deployAllowlist = (System.getenv("BRAIDRUN_NATIVE_EXEC_ENV_ALLOWLIST") ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    (MCP_STDIO_ENV_ALLOWLIST + deployAllowlist).forEach { name ->
        parentSnapshot[name]?.let { env[name] = it }
    }
    launchEnv.forEach { (key, value) -> env[key] = value }
}

/**
 * Resolve `path` against `baseDir`, optionally walking up the filesystem to find an
 * existing match (up to [MAX_UPWARD_SEARCH_DEPTH] parents).
 *
 * The upward walk is needed for legitimate config ergonomics (e.g. an MCP server config
 * written as `braidrun-mcp/start-mcp.sh` should resolve regardless of which subdirectory
 * the user launched from). Without it, the test suite and production configs break.
 *
 * The risk the audit flagged was that a maliciously crafted workflow YAML could name
 * a path that coincidentally exists several levels up — we mitigate by:
 *
 *   1. Logging every successful upward resolution at INFO level (audit trail).
 *   2. Keeping the depth bounded.
 *
 * A stronger isolation (restrict to project root) is infeasible here because the code
 * legitimately runs from many different cwds (CLI, tests, MCP stdio, workflow-web).
 */
private const val MAX_UPWARD_SEARCH_DEPTH = 8

private fun resolveExistingPath(path: String, baseDir: File? = null): File {
    val direct = resolvePath(path, baseDir)
    if (direct.exists() || baseDir == null) {
        return direct
    }

    var current = baseDir.absoluteFile.parentFile
    var depth = 0
    while (current != null && depth < MAX_UPWARD_SEARCH_DEPTH) {
        val candidate = resolvePath(path, current)
        if (candidate.exists()) {
            mcpUtilsLogger.info {
                "MCP path '$path' resolved via upward search at depth $depth: ${candidate.absolutePath}"
            }
            return candidate
        }
        current = current.parentFile
        depth++
    }
    return direct
}

internal fun resolveMcpLaunch(
    serverName: String,
    mcpServer: McpServerConfig,
    agentEnv: String? = null
): ResolvedMcpLaunch {
    val defaultCwd = mcpServer.cwd?.takeIf { it.isNotBlank() }?.let { resolvePath(it, currentUserDir()) } ?: currentUserDir()
    val originalArgs = mcpServer.args ?: emptyList()
    val effectiveArgs = if (!agentEnv.isNullOrBlank() && !hasExplicitEnvArg(originalArgs)) {
        listOf("--env", agentEnv) + originalArgs
    } else {
        originalArgs
    }
    val rawCommand = mcpServer.command?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("STDIO类型服务器 '$serverName' 缺少有效的 command 配置")

    val resolvedCommandFile = rawCommand
        .takeIf { it.isNotBlankPath() }
        ?.let { resolveExistingPath(it, defaultCwd) }

    val (command, args) = when {
        resolvedCommandFile == null -> rawCommand to effectiveArgs
        resolvedCommandFile.extension.equals("bat", ignoreCase = true) ->
            "cmd" to (listOf("/c", resolvedCommandFile.absolutePath) + effectiveArgs)
        resolvedCommandFile.extension.equals("sh", ignoreCase = true) ->
            "bash" to (listOf(resolvedCommandFile.absolutePath) + effectiveArgs)
        else -> resolvedCommandFile.absolutePath to effectiveArgs
    }

    return ResolvedMcpLaunch(
        command = command,
        args = args,
        cwd = defaultCwd,
        env = mcpServer.env ?: emptyMap()
    )
}

/**
 * Registers MCP (Message Component Protocol) tools by processing the provided server configurations
 * and updating the given tool registry with the tools from each configured MCP server.
 *
 * This method supports multiple MCP server configurations, including servers communicating
 * through SSE, WebSocket, HTTP, or a custom command executed in STDIO mode.
 * Any errors during the registration of individual MCP servers will be logged but will not
 * interrupt the overall registration process.
 *
 * @param httpAccess Provides the HTTP client access necessary for establishing connections
 *                   with MCP servers.
 * @param parameters A list of configuration parameters containing the details of MCP servers
 *                   to register. The configurations must include `mcp_servers` with server
 *                   details like type, URL, command, arguments, and environment variables.
 * @param toolRegistry The initial tool registry to which the registered tools will be added.
 * @return The updated tool registry including tools registered from all valid MCP servers.
 */
suspend fun registerMcpTools(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    toolRegistry: ToolRegistry
): ToolRegistry {
    val mcpServersConfig = parameters.parameter("mcp_servers", mapOf<String, McpServerConfig>())
    val agentEnv = parameters.parameter("env", "").takeIf { it.isNotBlank() }
    val mcpRegistries = mutableSetOf<ToolRegistry>()

    mcpServersConfig.getEnabledServers().forEach { (serverName, mcpServer) ->
        // Track the stdio child so the catch below can reap it: if the MCP
        // handshake (fromTransport) throws — the very case the catch exists
        // for — the already-started process would otherwise live until JVM
        // exit holding its pipes.
        var spawnedProcess: Process? = null
        try {
            val transport = if (mcpServer.url.isNullOrBlank()) {
                val launch = resolveMcpLaunch(serverName, mcpServer, agentEnv)

                val process = ProcessBuilder().apply {
                    command(
                        listOf(launch.command) + launch.args
                    )
                    directory(launch.cwd)
                    applyMcpLaunchEnvironment(this, launch.env)
                    redirectError(ProcessBuilder.Redirect.INHERIT)
                }.start()
                spawnedProcess = process
                // Create the stdio transport
                McpToolRegistryProvider.defaultStdioTransport(process)
            } else {
                val url = mcpServer.url
                when (mcpServer.type?.lowercase()) {
                    "sse", null, "" -> McpToolRegistryProvider.defaultSseTransport(url, baseClient = httpAccess.client)
                    "websocket" -> WebSocketClientTransport(httpAccess.client, url)
                    "http", "https", "streamlined" -> StreamableHttpClientTransport(httpAccess.client, url)
                    else -> {
                        // Previously used raw println — that goes to stdout, which in MCP
                        // stdio mode is the protocol channel. Route through the logger
                        // so it doesn't corrupt the JSON-RPC stream.
                        mcpUtilsLogger.warn { "未知的 MCP 传输类型 '${mcpServer.type}' (server='$serverName'), 默认使用 SSE" }
                        McpToolRegistryProvider.defaultSseTransport(url, baseClient = httpAccess.client)
                    }
                }
            }

            // Koog 1.0.0 (Phase 11) — `fromTransport(transport, name)` →
            // `fromTransport(transport, serverInfo, …, name)`. `serverInfo`
            // is now required; populate it with the originating URL/command
            // for diagnostics so the MCP runtime can attribute tool calls
            // back to the right server when more than one is registered.
            val registry = McpToolRegistryProvider.fromTransport(
                transport = transport,
                serverInfo = McpServerInfo(
                    url = mcpServer.url ?: "",
                    command = mcpServer.command ?: "",
                ),
                name = serverName,
            )
            mcpRegistries.add(registry)

        } catch (e: Exception) {
            // 记录错误但不中断整个注册过程;先把已启动的 stdio 子进程杀掉,避免泄漏。
            spawnedProcess?.let { proc ->
                runCatching { proc.destroyForcibly() }
            }
            logProgress(AnsiColor.YELLOW, "MCP", "⚠ 无法注册MCP服务器 '$serverName': ${e.message}")
        }
    }

    return mcpRegistries.fold(
        initial = toolRegistry
    ) { acc, registry -> acc + registry }
}
