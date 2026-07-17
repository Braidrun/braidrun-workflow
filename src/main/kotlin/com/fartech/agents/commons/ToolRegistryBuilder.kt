package com.fartech.agents.commons

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.fartech.agents.tools.*
import com.fartech.agents.tools.FileReadGuard
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter

/**
 * Tool registry construction.
 *
 * This file was carved out of `AgentCommon.kt` (Phase 5 of the 2026-04 audit
 * follow-up) — previously everything from "build me a registry with these
 * tool groups" lived in `AgentCommon` alongside prompt-executor factories,
 * subprocess-executor factories, environment info, and agent bootstrap code.
 *
 * Entry points (in order of caller preference):
 *
 *   1. [parseToolSet] (List&lt;String&gt; form) — the canonical call. Tool group
 *      names are matched against the set of `tool_set` entries in the workflow
 *      / preset, and optional `enableImplicitSkillTools` auto-adds `skill_tools`
 *      when an agent otherwise has no way to discover installed skills.
 *   2. [parseExactToolSet] — identical to [parseToolSet] but never injects
 *      `skill_tools` implicitly. Used by MCP server startup where the caller
 *      wants full control.
 *   3. [parseToolSet] (List&lt;AgentTools&gt; form) — enum → string adapter over
 *      the string form. Convenience for in-process Kotlin callers.
 *   4. [getDefaultToolRegistry] — the "everything enabled by default" set
 *      used by the CLI and test harnesses; does not consult `tool_set`.
 *
 * All four funnel into the private [buildToolRegistry] below.
 */

/**
 * Build the "default" tool registry — the one the CLI and tests get when no
 * `tool_set` parameter is configured. Registers the common essentials
 * (terminal, exit, sub-agent, skills, file system, shell, web, browser,
 * calendar, data-transform, knowledge memory) with any subprocess /
 * file-sandbox configuration resolved from [parameters].
 */
fun getDefaultToolRegistry(
    parameters: List<ConfigurationParameter>,
    httpAccess: HttpAccess,
    skillManager: SkillManager? = createSkillManager(parameters),
    onSkillEvent: MonitoringEventCallback? = null,
    onSubAgentEvent: MonitoringEventCallback? = null,
    onHookEvent: MonitoringEventCallback? = null,
) = ToolRegistry {
    tools(TerminalInteractiveTools())
    tool(ExitTool)
    tools(SubAgentTools(httpAccess, parameters, onMonitorEvent = onSubAgentEvent))
    // External SDK sub-agents are off by default in the "everything enabled" registry — they
    // require operator-configured CLIs (claude / codex) and per-engine API keys. Workflows
    // that want them must opt in via `tool_set: [..., external_agent]`.
    if (!skillsSubsystemDisabled(parameters)) {
        tools(SkillTools(parameters, httpAccess, skillManager, onMonitorEvent = onSkillEvent, onHookEvent = onHookEvent))
    }
    val defaultExecutor = createSubprocessExecutor(parameters)
    val defaultUserId = parameters.parameter("user_id", "local-user")
    val subprocessContext = buildSubprocessToolContext(parameters)
    // 文件沙箱：写操作限制在 working_dir 内（C7），读操作限制扩展名白名单 + 文件名黑名单（C9）
    val defaultStrictSandbox = parameters.parameter(SandboxedFileSystemProvider.STRICT_SANDBOX_PARAMETER, "false")
        .equals("true", ignoreCase = true)
    val defaultReadGuard = if (defaultStrictSandbox) FileReadGuard(enabled = true) else FileReadGuard.DISABLED
    val sandboxedFs = SandboxedFileSystemProvider.createFromParameters(parameters, readGuard = defaultReadGuard)
    val writeFs: FileSystemProvider.ReadWrite<java.nio.file.Path> = sandboxedFs ?: JVMFileSystemProvider.ReadWrite
    // When sandboxed, ReadFileTool goes through the sandbox (read guard applied); otherwise direct
    val readFs: FileSystemProvider.ReadOnly<java.nio.file.Path> = sandboxedFs ?: JVMFileSystemProvider.ReadOnly
    tool(ReadFileTool<java.nio.file.Path>(readFs))
    tool(ListDirectoryTool<java.nio.file.Path>(readFs))
    tool(EditFileTool<java.nio.file.Path>(writeFs))
    tool(WriteFileTool<java.nio.file.Path>(writeFs))
    tools(FileManagementTools.createFromParameters(parameters, readGuard = defaultReadGuard))
    tools(SafeFileTools.createFromParameters(parameters, readGuard = defaultReadGuard))
    tools(ShellTools(defaultExecutor, defaultUserId, subprocessContext))
    tools(WebTools(httpAccess, parameters))
    if (!browserToolsDisabled(parameters)) {
        tools(BrowserTools(parameters))
    }
    tools(CalendarTools)
    tools(DataTransformTools)
    tools(KnowledgeMemoryTools(parameters))
}

/**
 * Build a [ToolRegistry] from a string list of tool-group names. The `skill_tools`
 * group is added implicitly when absent unless explicitly disabled via
 * `disable_skills=true` (parameter or system property).
 */
@JvmName("parseToolSetByStrings")
fun parseToolSet(
    parameters: List<ConfigurationParameter>,
    httpAccess: HttpAccess,
    tools: List<String> = emptyList(),
    customToolSets: List<ToolSet> = emptyList(),
    customTools: List<Tool<*, *>> = emptyList(),
    skillManager: SkillManager? = createSkillManager(parameters),
    onSkillEvent: MonitoringEventCallback? = null,
    onSubAgentEvent: MonitoringEventCallback? = null,
    onHookEvent: MonitoringEventCallback? = null,
    userInteractionHandler: UserInteractionHandler? = null,
    externalAgentExecutor: SubprocessExecutor? = null,
): ToolRegistry {
    val toolSet =
        parameters.parameter("tool_set", emptyList<String>().toMutableSet()).also { it.addAll(tools) }
    return buildToolRegistry(
        parameters = parameters,
        httpAccess = httpAccess,
        toolSet = toolSet,
        enableImplicitSkillTools = true,
        customToolSets = customToolSets,
        customTools = customTools,
        skillManager = skillManager,
        onSkillEvent = onSkillEvent,
        onSubAgentEvent = onSubAgentEvent,
        onHookEvent = onHookEvent,
        userInteractionHandler = userInteractionHandler,
        externalAgentExecutor = externalAgentExecutor
    )
}

/**
 * Build a [ToolRegistry] from an exact list of tool-group names — no implicit
 * additions. Used by the MCP server where operators want the registry to match
 * their `--mcp-tool-group` selection bit-for-bit.
 */
@JvmName("parseExactToolSetByStrings")
fun parseExactToolSet(
    parameters: List<ConfigurationParameter>,
    httpAccess: HttpAccess,
    tools: List<String>,
    customToolSets: List<ToolSet> = emptyList(),
    customTools: List<Tool<*, *>> = emptyList(),
    skillManager: SkillManager? = createSkillManager(parameters),
    onSkillEvent: MonitoringEventCallback? = null,
    onSubAgentEvent: MonitoringEventCallback? = null,
    onHookEvent: MonitoringEventCallback? = null,
    userInteractionHandler: UserInteractionHandler? = null,
    externalAgentExecutor: SubprocessExecutor? = null,
): ToolRegistry = buildToolRegistry(
    parameters = parameters,
    httpAccess = httpAccess,
    toolSet = tools.toMutableSet(),
    enableImplicitSkillTools = false,
    customToolSets = customToolSets,
    customTools = customTools,
    skillManager = skillManager,
    onSkillEvent = onSkillEvent,
    onSubAgentEvent = onSubAgentEvent,
    onHookEvent = onHookEvent,
    userInteractionHandler = userInteractionHandler,
    externalAgentExecutor = externalAgentExecutor
)

/**
 * Typed-enum adapter over [parseToolSet] — converts [AgentTools] enum values
 * into their canonical string names and forwards.
 */
@JvmName("parseToolSetByAgentTools")
fun parseToolSet(
    parameters: List<ConfigurationParameter>,
    httpAccess: HttpAccess,
    tools: List<AgentTools> = emptyList(),
    customToolSets: List<ToolSet> = emptyList(),
    customTools: List<Tool<*, *>> = emptyList(),
    skillManager: SkillManager? = createSkillManager(parameters),
    onSkillEvent: MonitoringEventCallback? = null,
    onSubAgentEvent: MonitoringEventCallback? = null,
    onHookEvent: MonitoringEventCallback? = null,
): ToolRegistry = parseToolSet(
    parameters,
    httpAccess,
    tools.map {
        when (it) {
            AgentTools.INTERACTIVE -> "interactive"
            AgentTools.EXIT -> "exit"
            AgentTools.SUB_AGENT -> "sub_agent"
            AgentTools.FILE_OPERATIONS -> "file_system"
            AgentTools.WEB_TOOLS -> "web"
            AgentTools.PDF_TOOLS -> "pdf"
            AgentTools.IWORK_TOOLS -> "iwork"
            AgentTools.OFFICE_TOOLS -> "office"
            AgentTools.WORD_TOOLS -> "word"
            AgentTools.EXCEL_TOOLS -> "excel"
            AgentTools.POWERPOINT_TOOLS -> "powerpoint"
            AgentTools.CSV_TOOLS -> "csv"
            AgentTools.MULTI_MEDIA -> "multimedia"
            AgentTools.APPLE_APP_INFO -> "apple_app_info"
            AgentTools.SKILL_TOOLS -> "skill_tools"
            AgentTools.BROWSER -> "browser"
            AgentTools.SHELL -> "shell"
            AgentTools.IM_TOOLS -> "im"
            AgentTools.FILE_MANAGEMENT -> "file_management"
            AgentTools.CODE_EXECUTION -> "code_execution"
            AgentTools.DATABASE -> "database"
            AgentTools.IMAGE_PROCESSING -> "image_processing"
            AgentTools.EMAIL -> "email"
            AgentTools.KNOWLEDGE_MEMORY -> "knowledge_memory"
            AgentTools.DATA_TRANSFORM -> "data_transform"
            AgentTools.CALENDAR -> "calendar"
            AgentTools.GIT -> "git"
            AgentTools.OCR -> "ocr"
            AgentTools.RAG_TOOLS -> "rag_tools"
            AgentTools.EXTERNAL_AGENT -> "external_agent"
        }
    },
    customToolSets,
    customTools,
    skillManager,
    onSkillEvent,
    onSubAgentEvent,
    onHookEvent
)

/**
 * Single authoritative place where `toolSet.contains("<name>")` maps to a
 * concrete tool set being registered. New tool groups get their entry added
 * here and nowhere else.
 */
private fun buildToolRegistry(
    parameters: List<ConfigurationParameter>,
    httpAccess: HttpAccess,
    toolSet: MutableSet<String>,
    enableImplicitSkillTools: Boolean,
    customToolSets: List<ToolSet>,
    customTools: List<Tool<*, *>>,
    skillManager: SkillManager?,
    onSkillEvent: MonitoringEventCallback?,
    onSubAgentEvent: MonitoringEventCallback?,
    onHookEvent: MonitoringEventCallback?,
    userInteractionHandler: UserInteractionHandler?,
    externalAgentExecutor: SubprocessExecutor?,
): ToolRegistry = ToolRegistry {
    val browserDisabled = browserToolsDisabled(parameters)
    val subprocessExecutor = createSubprocessExecutor(parameters)
    val userId = parameters.parameter("user_id", "local-user")
    val strictSandbox = parameters.parameter(SandboxedFileSystemProvider.STRICT_SANDBOX_PARAMETER, "false")
        .equals("true", ignoreCase = true)
    val readGuard = if (strictSandbox) FileReadGuard(enabled = true) else FileReadGuard.DISABLED
    val subprocessContext = buildSubprocessToolContext(parameters)
    // Skill tools are "first-class citizens" in normal agent mode, unless the
    // agent explicitly disables the skills subsystem.
    val skillsDisabled = skillsSubsystemDisabled(parameters)
    if (enableImplicitSkillTools && !skillsDisabled && !toolSet.contains("skill_tools")) {
        toolSet.add("skill_tools")
    }

    if (toolSet.contains("interactive")) {
        tools(TerminalInteractiveTools())
    }

    if (toolSet.contains("exit")) {
        tool(ExitTool)
    }

    if (toolSet.contains("sub_agent")) {
        tools(SubAgentTools(httpAccess, parameters, onMonitorEvent = onSubAgentEvent))
    }

    if (toolSet.contains("external_agent")) {
        // External SDK sub-agents (Claude Code, OpenAI Codex). Reuses the same subprocess
        // sandbox infrastructure as shell/code tools; the user/event callback wiring mirrors
        // SubAgentTools so the UI's monitoring panel renders both kinds uniformly.
        tools(
            ExternalAgentTools(
                executor = externalAgentExecutor ?: subprocessExecutor,
                parameters = parameters,
                userId = userId,
                context = subprocessContext,
                onMonitorEvent = onSubAgentEvent,
                trustExecutorSandbox = parameters.parameter("subprocess_mode", "native")
                    .equals("docker", ignoreCase = true)
            )
        )
    }

    if (toolSet.contains("file_system")) {
        // 文件沙箱：写操作限制在 working_dir 内（C7），读操作限制扩展名白名单 + 文件名黑名单（C9）
        val sandboxedFs = SandboxedFileSystemProvider.createFromParameters(parameters, readGuard = readGuard)
        val writeFs: FileSystemProvider.ReadWrite<java.nio.file.Path> = sandboxedFs ?: JVMFileSystemProvider.ReadWrite
        val readFs: FileSystemProvider.ReadOnly<java.nio.file.Path> = sandboxedFs ?: JVMFileSystemProvider.ReadOnly
        tool(ReadFileTool(readFs))
        tool(ListDirectoryTool(readFs))
        tool(EditFileTool(writeFs))
        tool(WriteFileTool(writeFs))
        tools(FileManagementTools.createFromParameters(parameters, readGuard = readGuard))
        tools(SafeFileTools.createFromParameters(parameters, readGuard = readGuard))
    }

    if (toolSet.contains("shell")) {
        tools(ShellTools(subprocessExecutor, userId, subprocessContext))
    }

    if (toolSet.contains("web")) {
        tools(WebTools(httpAccess, parameters))
    }

    if (toolSet.contains("browser") && !browserDisabled) {
        tools(BrowserTools(parameters))
    }

    if (toolSet.contains("pdf")) {
        // Phase 6: `PDFAdvancedTools` was absorbed into `PDFTools`; registering it
        // separately is now a no-op (the deprecated object exposes no @Tools).
        tools(PDFTools)
    }

    if (toolSet.contains("iwork")) {
        // Phase 6: `ApplePagesTools` / `AppleKeynoteTools` / `AppleNumbersTools` were
        // absorbed into `IWorkTools` (the format-specific @Tool names like `pagesInfo`,
        // `keynoteInfo`, `numbersInfo` now live on IWorkTools itself).
        tools(IWorkTools)
    }

    val officeRequested = toolSet.contains("office")
    val wordRequested = toolSet.contains("word") && !officeRequested
    val excelRequested = toolSet.contains("excel") && !officeRequested
    val powerPointRequested = toolSet.contains("powerpoint") && !officeRequested

    // Phase 6 note on Word/Excel/PowerPoint:
    // The `*AdvancedTools` / `*EnhancedTools` classes were marked `@Deprecated` but
    // their bodies remain. We keep registering all three tiers under a single tool
    // group so the LLM surface stays byte-for-byte identical; external callers who
    // used to instantiate the tier classes directly should migrate to
    // `parseToolSet(listOf("word" | "excel" | "powerpoint"))` as shown in the
    // `@Deprecated` messages on those classes.
    @Suppress("DEPRECATION")
    if (officeRequested || wordRequested) {
        tools(WordTools)
        tools(WordAdvancedTools())
        tools(WordEnhancedTools())
    }

    @Suppress("DEPRECATION")
    if (officeRequested || excelRequested) {
        tools(ExcelTools)
        tools(ExcelAdvancedTools())
        tools(ExcelEnhancedTools())
    }

    @Suppress("DEPRECATION")
    if (officeRequested || powerPointRequested) {
        tools(PowerPointTools)
        tools(PowerPointAdvancedTools())
        tools(PowerPointEnhancedTools())
    }

    // Register CSVTools only once if either "csv" is requested explicitly,
    // or the full Office bundle is enabled.
    if (toolSet.contains("csv") || officeRequested) {
        tools(CSVTools())
    }

    if (toolSet.contains("multimedia")) {
        // Multimedia Generation Tools (image and audio)
        tools(MultimediaGenerationTools(httpAccess, parameters))
    }

    if (toolSet.contains("apple_app_info")) {
        // Apple App Info Tools
        tools(AppleAppInfoTools(httpAccess))
    }

    if (toolSet.contains("skill_tools") && !skillsDisabled) {
        // Skill Tools
        tools(SkillTools(parameters, httpAccess, skillManager, onMonitorEvent = onSkillEvent, onHookEvent = onHookEvent))
    }

    if (toolSet.contains("im")) {
        // Instant Messaging Tools
        tools(InstantMessagingTools(httpAccess, parameters))
    }

    if (toolSet.contains("user_interaction") && userInteractionHandler != null) {
        tools(UserInteractionTools(userInteractionHandler))
    }

    if (toolSet.contains("workflow")) {
        // Workflow Tools
        tools(com.fartech.agents.workflow.WorkflowTools(httpAccess, parameters))
    }

    // Register FileManagementTools only if not already registered via "file_system"
    if (toolSet.contains("file_management") && !toolSet.contains("file_system")) {
        tools(FileManagementTools.createFromParameters(parameters, readGuard = readGuard))
    }

    if (toolSet.contains("code_execution")) {
        if (strictSandbox) {
            tools(CodeExecutionTools.readOnly(subprocessExecutor, userId, subprocessContext))
        } else {
            tools(CodeExecutionTools.withPackageInstall(subprocessExecutor, userId, subprocessContext))
        }
    }

    if (toolSet.contains("database")) {
        tools(DatabaseTools)
    }

    if (toolSet.contains("image_processing")) {
        tools(ImageProcessingTools)
    }

    if (toolSet.contains("email")) {
        tools(EmailTools(parameters))
    }

    if (toolSet.contains("knowledge_memory")) {
        tools(KnowledgeMemoryTools(parameters))
    }

    if (toolSet.contains("rag_tools")) {
        // 如果 customToolSets 中已包含 RAGTools 实例（如工作流共享知识库），跳过创建新实例
        val hasSharedRag = customToolSets.any { it is RAGTools }
        if (!hasSharedRag) {
            tools(RAGTools.createFromParameters(parameters, baseClient = httpAccess.client))
        }
    }

    if (toolSet.contains("data_transform")) {
        tools(DataTransformTools)
    }

    if (toolSet.contains("calendar")) {
        tools(CalendarTools)
    }

    if (toolSet.contains("git")) {
        tools(GitTools(subprocessExecutor, userId, subprocessContext))
    }

    if (toolSet.contains("ocr")) {
        tools(OCRTools)
    }

    customToolSets.forEach { tools(it) }
    customTools.forEach { tool(it) }
}

internal fun skillsSubsystemDisabled(parameters: List<ConfigurationParameter>): Boolean {
    return parameters.parameter("disable_skills", false) ||
        parameters.parameter("disable_skills", "false").equals("true", ignoreCase = true) ||
        System.getProperty("disable_skills", "false").equals("true", ignoreCase = true) ||
        parameters.getSkillsConfig()?.enabled == false
}

/**
 * Flag for global browser-tool disablement. Honoured by both
 * [getDefaultToolRegistry] and [buildToolRegistry]. Controlled via:
 *   - workflow parameter `disable_browser_tools` (bool or "true"/"false" string), or
 *   - env var `BRAIDRUN_DISABLE_BROWSER_TOOLS` with a truthy value.
 *
 * Visibility: `internal` so the factory above (same package) can call it; not
 * part of the public API since the browser-tool toggle is an implementation
 * detail of tool-registry construction.
 */
internal fun browserToolsDisabled(parameters: List<ConfigurationParameter>): Boolean {
    val disabledByParameter = parameters.parameter("disable_browser_tools", false) ||
        parameters.parameter("disable_browser_tools", "false") == "true"
    val disabledByEnv = System.getenv("BRAIDRUN_DISABLE_BROWSER_TOOLS")
        ?.trim()
        ?.lowercase()
        ?.let { it in setOf("1", "true", "yes", "on") }
        ?: false
    return disabledByParameter || disabledByEnv
}
