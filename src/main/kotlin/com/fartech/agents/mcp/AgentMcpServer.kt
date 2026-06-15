package com.fartech.agents.mcp

// Koog 1.0.0 split the `Tool<TArgs, TResult>` hierarchy: `ToolBase` is the
// runtime contract dispatched by the agent, while `Tool` is the no-metadata
// convenience subclass. `ToolRegistry.tools` returns `List<ToolBase<*, *>>`
// after the split, so the MCP exposure walks `ToolBase` and the MCP `addTool`
// callback uses `decodeArgs` / `executeUnsafe` / `encodeResultUnsafe` — all of
// which live on `ToolBase`.
import ai.koog.agents.core.tools.ToolBase as KoogTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import com.fartech.agents.commons.parseExactToolSet
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.Job
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val AGENT_MCP_SERVER_NAME = "braidrun-agent"
private const val AGENT_MCP_SERVER_VERSION = "1.0.0-SNAPSHOT"

private val mcpLogger = KotlinLogging.logger("com.fartech.agents.mcp.AgentMcpServer")

// ---------------------------------------------------------------------------
// MCP server request policy (Phase 4 hardening)
// ---------------------------------------------------------------------------
//
// Even though the stdio transport assumes a single trusted client, the HTTP / SSE
// variants (shared by braidrun-mcp) don't have that guarantee. Rather than carry
// the "we only speak to one client so we skip checks" assumption through the code,
// we apply the same defenses everywhere:
//
//   1. **Tool allowlist** — operators can restrict the callable tool names via
//      `BRAIDRUN_MCP_ALLOWED_TOOLS=tool_a,tool_b,...` (substring/glob match by exact
//      tool name). When set, calls to any un-listed tool reject with a sanitized
//      error message. Default is "everything registered", preserving current behavior.
//
//   2. **Per-tool rate-limit** — token-bucket keyed by tool name. Default is
//      [DEFAULT_MCP_RATE_LIMIT_PER_MIN] calls per tool per minute, overridable via
//      `BRAIDRUN_MCP_RATE_LIMIT_PER_MIN`. Prevents a runaway client from DoS'ing the
//      executor pool or exhausting upstream API budget.
//
//   3. **Input size cap** — reject requests whose JSON args exceed
//      [DEFAULT_MCP_MAX_INPUT_BYTES] (default 1 MiB, overridable via
//      `BRAIDRUN_MCP_MAX_INPUT_BYTES`). Without this, a malicious peer could feed the
//      server a 1 GB blob of JSON and crash the JVM during deserialization.
//
// All three defaults are permissive enough that existing stdio workflows are
// unaffected; operators who expose MCP over the network should tighten them.

private const val DEFAULT_MCP_RATE_LIMIT_PER_MIN: Int = 600
private const val DEFAULT_MCP_MAX_INPUT_BYTES: Int = 1 * 1024 * 1024

private val mcpAllowedTools: Set<String>? by lazy {
    val raw = (System.getProperty("BRAIDRUN_MCP_ALLOWED_TOOLS")
        ?: System.getenv("BRAIDRUN_MCP_ALLOWED_TOOLS"))
        ?.takeIf { it.isNotBlank() }
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
}

private val mcpRateLimitPerMin: Int by lazy {
    (System.getProperty("BRAIDRUN_MCP_RATE_LIMIT_PER_MIN")
        ?: System.getenv("BRAIDRUN_MCP_RATE_LIMIT_PER_MIN"))
        ?.toIntOrNull()?.takeIf { it > 0 }
        ?: DEFAULT_MCP_RATE_LIMIT_PER_MIN
}

private val mcpMaxInputBytes: Int by lazy {
    (System.getProperty("BRAIDRUN_MCP_MAX_INPUT_BYTES")
        ?: System.getenv("BRAIDRUN_MCP_MAX_INPUT_BYTES"))
        ?.toIntOrNull()?.takeIf { it > 0 }
        ?: DEFAULT_MCP_MAX_INPUT_BYTES
}

/**
 * Per-tool sliding-window rate limiter. Holds a ring of timestamps for each tool name
 * and trims entries older than [windowMs] on every check. Synchronized per tool to
 * keep the shared map safe under concurrent MCP invocations.
 */
private class McpRateLimiter(private val maxPerWindow: Int, private val windowMs: Long) {
    private val windows = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun tryAcquire(toolName: String, now: Long = System.currentTimeMillis()): Boolean {
        val lock = locks.computeIfAbsent(toolName) { Any() }
        synchronized(lock) {
            val q = windows.computeIfAbsent(toolName) { ArrayDeque() }
            val cutoff = now - windowMs
            while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
            if (q.size >= maxPerWindow) return false
            q.addLast(now)
            return true
        }
    }
}

private val mcpRateLimiter by lazy {
    McpRateLimiter(maxPerWindow = mcpRateLimitPerMin, windowMs = 60_000L)
}

/**
 * Estimate the byte size of a JSON element by walking the tree and summing
 * primitive content lengths (plus per-node structural overhead). Avoids
 * re-serializing the whole tree to a fresh String — for payloads near the
 * size cap the previous `toString().length` implementation allocated an
 * extra multi-MiB string per request AND undercounted multi-byte UTF-8
 * input (String.length counts UTF-16 units). The walk over-approximates
 * slightly per node, which is the safe direction for a cap check.
 */
internal fun estimateJsonSizeBytes(element: kotlinx.serialization.json.JsonElement): Int = when (element) {
    is kotlinx.serialization.json.JsonNull -> 4
    is JsonPrimitive -> {
        val content = element.content
        // UTF-8 worst-ish case for non-ASCII without a full encode pass:
        // count chars ≥ 0x80 as 3 bytes (covers CJK; 4-byte chars appear as
        // surrogate pairs = 2 chars × 3 = 6 ≥ their real 4).
        var bytes = 2 // quotes / structural slack
        for (ch in content) bytes += if (ch.code < 0x80) 1 else 3
        bytes
    }
    is kotlinx.serialization.json.JsonArray ->
        2 + element.sumOf { estimateJsonSizeBytes(it) + 1 }
    is JsonObject ->
        2 + element.entries.sumOf { (key, value) ->
            key.length * 3 + 4 + estimateJsonSizeBytes(value)
        }
}

/**
 * Regexes used to strip information that MCP clients (or LLMs driving them) have no business
 * seeing: absolute host paths, internal class names, stack-trace-ish lines. Each pattern is
 * intentionally conservative — we'd rather leak a vague error than a filesystem layout.
 */
private val ABSOLUTE_PATH_PATTERN = Regex("""(?<![A-Za-z0-9])(/[A-Za-z0-9_./-]+|[A-Z]:\\[^\s)]+)""")
private val FQCN_PATTERN = Regex("""\b(?:java|kotlin|com|org|io|ai)\.[A-Za-z0-9_.$]{3,}""")
private val STACK_FRAME_PATTERN = Regex("""\s*at\s+[A-Za-z0-9_.$<>]+(?:\([^)]*\))?""")

/**
 * Sanitize an exception for transmission back to MCP clients.
 *
 * The previous implementation sent the raw `exception.message` / `exception.toString()` —
 * this leaks absolute filesystem paths, internal package names and, under enough stack-trace
 * verbosity, full internal call graphs. An LLM that can see those responses can construct
 * better-targeted follow-up attacks or exfiltrate config layout.
 *
 * We keep a compact human-readable message (exception simple name + first-line message with
 * paths/FQCNs redacted) plus a correlation ID that operators can grep from server logs. The
 * full exception — stack trace and all — is written to the local log under that ID.
 */
internal fun sanitizeToolException(e: Throwable, toolName: String): Pair<String, String> {
    val correlationId = UUID.randomUUID().toString().take(12)
    mcpLogger.error(e) { "[mcp] tool=$toolName errorId=$correlationId" }

    val rawMessage = e.message?.takeIf { it.isNotBlank() }
        ?: e::class.simpleName
        ?: "tool execution failed"

    // Truncate first to avoid unbounded regex work on pathological input.
    val truncated = if (rawMessage.length > 512) rawMessage.take(512) + "..." else rawMessage
    // Phase 11: also scrub long secret blobs / auth headers / cloud API keys via the
    // shared LogSanitizers redactor before the path/FQCN scrub. Pre-fix, an exception
    // like `IllegalStateException("auth failed for token <github-token>")`
    // could leak the literal GitHub token into the MCP wire response — observable to any
    // client connected to the MCP server, including untrusted IDE extensions.
    val secretsScrubbed = com.fartech.ftapp2.commonsKt.redactForLog(truncated)
    val redacted = secretsScrubbed
        .replace(STACK_FRAME_PATTERN, "")
        .replace(ABSOLUTE_PATH_PATTERN, "<path>")
        .replace(FQCN_PATTERN, "<internal>")
        .trim()

    val shape = e::class.simpleName ?: "Exception"
    val message = "Tool '$toolName' failed ($shape): $redacted [errorId=$correlationId]"
    return message to correlationId
}

data class AgentMcpToolGroupDefinition(
    val name: String,
    val description: String
)

private val koogJsonSerializer = KotlinxSerializer()

private val agentMcpToolGroups = listOf(
    AgentMcpToolGroupDefinition("sub_agent", "子 Agent 委派与协作工具"),
    AgentMcpToolGroupDefinition("file_system", "基础文件读写、目录遍历与编辑工具"),
    AgentMcpToolGroupDefinition("shell", "Shell 命令执行工具"),
    AgentMcpToolGroupDefinition("web", "网页抓取、搜索与 HTTP 工具"),
    AgentMcpToolGroupDefinition("browser", "基于 Playwright 的浏览器自动化工具"),
    // Document tool groups (Phase 6 consolidation — each group maps to a single
    // canonical backend ToolSet family; Advanced/Enhanced tier classes merged or
    // kept @Deprecated under the same group name).
    AgentMcpToolGroupDefinition("pdf", "PDF 工具 (读/编辑/合并/页面操作/表单/加密/标注) — 已合并 PDFAdvancedTools"),
    AgentMcpToolGroupDefinition("iwork", "Apple iWork 工具 (Pages/Numbers/Keynote) — 已整并 Apple*Tools 子类"),
    AgentMcpToolGroupDefinition("office", "Office 文档全套 (Word + Excel + PowerPoint + CSV),一次装载所有相关工具"),
    AgentMcpToolGroupDefinition("word", "Word 文档工具 (含基础/高级/增强层)"),
    AgentMcpToolGroupDefinition("excel", "Excel 工作簿工具 (含基础/高级/增强层)"),
    AgentMcpToolGroupDefinition("powerpoint", "PowerPoint 演示文稿工具 (含基础/高级/增强层)"),
    AgentMcpToolGroupDefinition("csv", "CSV 工具 (读写/过滤/选列/合并/XLSX 互转)"),
    AgentMcpToolGroupDefinition("multimedia", "多媒体生成工具"),
    AgentMcpToolGroupDefinition("apple_app_info", "Apple App Store 信息查询工具"),
    AgentMcpToolGroupDefinition("skill_tools", "技能检索、安装与管理工具"),
    AgentMcpToolGroupDefinition("im", "即时通讯集成工具"),
    AgentMcpToolGroupDefinition("workflow", "工作流执行与管理工具"),
    AgentMcpToolGroupDefinition("file_management", "文件复制、移动、压缩、搜索等管理工具"),
    AgentMcpToolGroupDefinition("code_execution", "代码执行工具"),
    AgentMcpToolGroupDefinition("database", "SQLite/JDBC 数据库工具"),
    AgentMcpToolGroupDefinition("image_processing", "图片处理工具"),
    AgentMcpToolGroupDefinition("email", "邮件收发工具"),
    AgentMcpToolGroupDefinition("knowledge_memory", "本地知识记忆工具"),
    AgentMcpToolGroupDefinition("rag_tools", "RAG 文档索引与检索工具"),
    AgentMcpToolGroupDefinition("data_transform", "数据格式转换工具"),
    AgentMcpToolGroupDefinition("calendar", "日期、时区与日历工具"),
    AgentMcpToolGroupDefinition("git", "Git 版本控制工具"),
    AgentMcpToolGroupDefinition("ocr", "OCR 文字识别工具")
)

private val agentMcpToolGroupMap = agentMcpToolGroups.associateBy { it.name }

fun getSupportedAgentMcpToolGroups(): List<AgentMcpToolGroupDefinition> = agentMcpToolGroups

internal fun resolveAgentMcpToolGroups(rawSelections: List<String>): List<String> {
    val requested = rawSelections
        .flatMap { it.split(",") }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    if (requested.isEmpty() || requested.any { it == "all" }) {
        return agentMcpToolGroups.map { it.name }
    }

    val resolved = linkedSetOf<String>()
    val unknown = mutableListOf<String>()
    requested.forEach { group ->
        if (agentMcpToolGroupMap.containsKey(group)) {
            resolved.add(group)
        } else {
            unknown.add(group)
        }
    }

    require(unknown.isEmpty()) {
        "Unknown MCP tool group(s): ${unknown.joinToString(", ")}. Available groups: ${agentMcpToolGroups.joinToString(", ") { it.name }}"
    }

    return resolved.toList()
}

private fun redirectStdoutToStderr(): PrintStream {
    val protocolOut = System.out
    System.setOut(PrintStream(System.err, true, StandardCharsets.UTF_8))
    return protocolOut
}

suspend fun startAgentMcpServer(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    requestedGroups: List<String>
) {
    val protocolOut = redirectStdoutToStderr()
    val selectedGroups = resolveAgentMcpToolGroups(requestedGroups)
    val toolRegistry = parseExactToolSet(
        parameters = parameters,
        httpAccess = httpAccess,
        tools = selectedGroups
    )

    System.err.println("[braidrun-agent:mcp] Starting stdio MCP server with tool groups: ${selectedGroups.joinToString(", ")}")
    System.err.println("[braidrun-agent:mcp] Registered ${toolRegistry.tools.size} tools")

    val server = Server(
        Implementation(name = AGENT_MCP_SERVER_NAME, version = AGENT_MCP_SERVER_VERSION),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    toolRegistry.tools.forEach { tool ->
        registerTool(server, tool)
    }

    val transport = StdioServerTransport(
        System.`in`.asInput(),
        protocolOut.asSink().buffered()
    )

    server.createSession(transport)
    val done = Job()
    server.onClose { done.complete() }
    done.join()
}

@OptIn(InternalAgentToolsApi::class)
private fun registerTool(server: Server, tool: KoogTool<*, *>) {
    // Upfront ACL rejection. When an operator has set BRAIDRUN_MCP_ALLOWED_TOOLS, tools
    // outside the allowlist are still registered in the schema catalog (so clients can
    // enumerate them) but every call rejects. Registering-and-rejecting is less
    // surprising than silently hiding tools — clients get a clear error message.
    val toolAllowed = mcpAllowedTools == null || tool.name in mcpAllowedTools!!

    server.addTool(
        name = tool.name,
        description = tool.descriptor.description,
        inputSchema = tool.descriptor.toMcpInputSchema()
    ) { request ->
        try {
            if (!toolAllowed) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Tool '${tool.name}' is not in the MCP server's allowlist (BRAIDRUN_MCP_ALLOWED_TOOLS).")),
                    isError = true,
                    structuredContent = buildJsonObject { put("error", "tool_not_allowlisted") }
                )
            }

            // Rate-limit before decoding — if a client is hammering us, do the cheapest
            // possible work before returning.
            if (!mcpRateLimiter.tryAcquire(tool.name)) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Rate limit exceeded for tool '${tool.name}' (max ${mcpRateLimitPerMin}/min). Retry shortly.")),
                    isError = true,
                    structuredContent = buildJsonObject { put("error", "rate_limited") }
                )
            }

            val rawArgs = request.arguments ?: buildJsonObject { }
            val approxSize = estimateJsonSizeBytes(rawArgs)
            if (approxSize > mcpMaxInputBytes) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Request payload for tool '${tool.name}' is $approxSize bytes, exceeds limit $mcpMaxInputBytes.")),
                    isError = true,
                    structuredContent = buildJsonObject { put("error", "payload_too_large") }
                )
            }

            val decodedArgs = tool.decodeArgs(rawArgs.toKoogJSONObject(), koogJsonSerializer)
            val result = tool.executeUnsafe(decodedArgs)
            val encodedResult = tool.encodeResultUnsafe(result, koogJsonSerializer).toKotlinxJsonElement()
            val textContent = when (encodedResult) {
                is JsonPrimitive -> encodedResult.contentOrNull ?: encodedResult.toString()
                else -> encodedResult.toString()
            }
            val structuredContent = when (encodedResult) {
                is JsonObject -> encodedResult
                else -> buildJsonObject { put("result", encodedResult) }
            }

            CallToolResult(
                content = listOf(TextContent(textContent)),
                isError = false,
                structuredContent = structuredContent
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Client disconnect / session shutdown — preserve structured
            // concurrency instead of fabricating a CallToolResult on a
            // cancelled coroutine (and polluting error logs).
            throw e
        } catch (e: Exception) {
            val (message, errorId) = sanitizeToolException(e, tool.name)
            CallToolResult(
                content = listOf(TextContent(message)),
                isError = true,
                structuredContent = buildJsonObject {
                    put("error", message)
                    put("errorId", errorId)
                }
            )
        }
    }
}

private fun ToolDescriptor.toMcpInputSchema(): ToolSchema {
    val allParameters = requiredParameters + optionalParameters
    val properties = buildJsonObject {
        allParameters.forEach { parameter ->
            put(parameter.name, parameter.toJsonSchema())
        }
    }
    val required = requiredParameters.map { it.name }.takeIf { it.isNotEmpty() }
    return ToolSchema(
        properties = properties,
        required = required
    )
}

private fun ToolParameterDescriptor.toJsonSchema(): JsonObject = buildJsonObject {
    type.toJsonSchema().forEach { (key, value) -> put(key, value) }
    if (description.isNotBlank()) {
        put("description", description)
    }
}

private fun ToolParameterType.toJsonSchema(): JsonObject = when (this) {
    ToolParameterType.String -> buildJsonObject {
        put("type", "string")
    }

    ToolParameterType.Null -> buildJsonObject {
        put("type", "null")
    }

    ToolParameterType.Integer -> buildJsonObject {
        put("type", "integer")
    }

    ToolParameterType.Float -> buildJsonObject {
        put("type", "number")
    }

    ToolParameterType.Boolean -> buildJsonObject {
        put("type", "boolean")
    }

    is ToolParameterType.Enum -> buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(entries.map(::JsonPrimitive)))
    }

    is ToolParameterType.List -> buildJsonObject {
        put("type", "array")
        put("items", itemsType.toJsonSchema())
    }

    is ToolParameterType.Object -> buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                properties.forEach { property ->
                    put(property.name, property.toJsonSchema())
                }
            }
        )
        if (requiredProperties.isNotEmpty()) {
            put("required", JsonArray(requiredProperties.map(::JsonPrimitive)))
        }
        val additionalType = additionalPropertiesType
        when {
            additionalType != null -> put("additionalProperties", additionalType.toJsonSchema())
            additionalProperties != null -> put("additionalProperties", additionalProperties)
        }
    }

    is ToolParameterType.AnyOf -> buildJsonObject {
        put("anyOf", JsonArray(types.map { it.toJsonSchema() }))
    }
}
