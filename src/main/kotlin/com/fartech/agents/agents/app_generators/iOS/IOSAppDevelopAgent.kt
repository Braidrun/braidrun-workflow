package com.fartech.agents.agents.app_generators.iOS

/**
 * IOSAppDevelopAgent.kt
 *
 * Purpose
 * - Defines the "iOS App Development" agent and its strategy for turning user-provided project properties and design
 *   assets into a complete iOS app project.
 * - Supports multiple stacks: Native iOS, Flutter, React Native, UniApp, and Kotlin Multiplatform (KMP).
 * - On macOS, it can automate Xcode/XcodeGen build and testing; on non-macOS platforms, it generates the project and
 *   source code only and skips build/testing.
 *
 * Audience
 * - Engineers maintaining the automated app generation pipeline.
 * - Teammates who need to onboard new stacks or flows.
 *
 * Key parameters (from `parameters`)
 * - project_properties: IOSAppProjectProperties containing app name, bundle ID, UI framework, stack, design files, etc.
 * - skip_ask_requirements: whether to skip the interactive requirements stage (default false). In GUI integration
 *   scenarios, set true to directly use provided project properties.
 * - architecture_design_max_retries: max retries if architecture design yields empty results (default 3).
 * - file_generation_batch_size: batch size when generating source files (default 5).
 * - file_generation_parallelism: parallelism when generating source files (default 5).
 * - media_generation_parallelism: parallelism when generating media files (default 2, lower to avoid API overload).
 * - media_retry_max_attempts: max attempts to retry failed media generation (default 6).
 * - retry_cycle: max retry cycles for build/testing phases on macOS (default 20).
 *
 * Initialization strategy (2025)
 * - All stacks (Native/Flutter/React Native/KMP) are initialized by dedicated Sub Agents per stack.
 * - Each Sub Agent performs a full initialization task (environment checks, tool invocations, configuration, retries).
 * - Initialization is isolated from the main agent flow to contain complexity and reduce risk.
 *
 * File generation strategy
 * - All files (source + media) are processed in the main agent context to preserve history and consistency across files.
 * - Both source and media files are handled one-by-one via a RetrievalTask mechanism.
 *
 * Storage keys
 * - layout: the project file/directory structure (IOSAppProjectFiles).
 * - files: the pending file task list (MutableList<IOSAppProjectFile>).
 * - file: the current file being processed (IOSAppProjectFile).
 * - total_files: total file count for progress tracking (Int).
 *
 * High-level flow (2025 optimized)
 *
 *  Start
 *    |
 *    v
 *  skip_ask_requirements?
 *    |              \
 *   no              yes
 *    |               \
 *    v                +------------+
 *  (AskRequirements)                |
 *  Ask/confirm user requirements    |
 *  Produce final ProjectProperties   |
 *  Do not upload design files here   |
 *    |                               |
 *    +-------------------------------+
 *    |
 *    v
 *  shouldInitializeProject? (depends on stack)
 *    |             \
 *   yes             no
 *    |               \
 *    v                v
 *  (InitializeProject) +    2025: initialization delegated to Sub Agents by stack
 *   - Native: InitNativeXcodeGen (generate project.yml + xcodegen)
 *   - Flutter: InitFlutterProject (flutter create + configuration)
 *   - React Native: InitReactNativeProject (npx init + configuration)
 *   - KMP: InitKotlinMppProject (Gradle setup + module creation)
 *   - UniApp: minimal directories only
 *    |                  |
 *    +------------------+
 *    |
 *    v
 *  (GetProjectFiles)
 *  Read existing structure and ask LLM to generate the remaining file manifest.
 *  Upload design files here (if any) to produce detailed asset descriptions.
 *    |
 *    v
 *  (DesignArchitecture) — 2025
 *  Design per-file architecture: functions, data structures, globals, inter-file dependencies, global principles
 *    |
 *    v
 *  (SetupNode) — 2025 major refactor
 *  Split source vs media and create dependency-aware batches
 *    |
 *    v
 *  +--------------------- Source generation loop ---------------------+
 *  |  (RetrievalTask) -> take a batch of source files (dependency-aware) |
 *  |       |                                                           |
 *  |       v                                                           |
 *  |  (GenerateSourceFilesParallel) — use Sub Agents in parallel        |
 *  |   - one Sub Agent per file                                        |
 *  |   - default batch size: 5                                          |
 *  |   - default parallelism: 5                                         |
 *  |   - no historical chat context, only pass architecture definition  |
 *  |       |                                                           |
 *  |       +-----------------------------------------------------------+
 *  |
 *  v (all source batches done)
 *  (CollectMediaFiles) -> collect media files
 *    |
 *    v
 *  (GenerateMediaFilesParallel) — use Sub Agents in parallel
 *   - one Sub Agent per media file
 *   - default parallelism: 2 (throttle to avoid API overload)
 *   - media files are independent (no dependencies)
 *   - errors are tolerated and recorded; failed files are stored for retry
 *    |
 *    v
 *  Any failed media files?
 *    |              \
 *   yes              no
 *    |               \
 *    v                v
 *  (RetryFailedMediaFiles) — intelligent sequential retries
 *   - retry failed files one-by-one (sequential to reduce API load)
 *   - 2s delay between attempts to avoid rate limiting
 *   - max attempts configurable via media_retry_max_attempts
 *   - track success/failure per file; loop until success or max attempts
 *    |                  |
 *    v                  v
 *  Success or max attempts reached -> next phase
 *    |
 *    +------------------+
 *    |
 *    v
 *  if isMacOS():
 *    (BuildDebug Sub Agent) -> (Testing Sub Agent) -> (Summary) -> [Finish]
 *  else:
 *    (Summary) -> [Finish]
 *
 * Notes
 * - AskRequirements can be skipped via skip_ask_requirements. It collects functional/technical requirements only;
 *   design files are not uploaded there. In GUI flows, this is usually skipped.
 * - GetProjectFiles reads the real directory tree from prior tools and asks LLM to produce the remaining manifest. LLM
 *   thus knows which files already exist and won’t duplicate them.
 * - SetupNode creates directories and enqueues files that require content generation.
 * - RetrievalTask pulls files from the queue, generating all content within the main agent context for consistency.
 * - Source files are generated by GenerateFileSource; media by GenerateMediaFile.
 * - Build/Testing is macOS-only; non-macOS environments go straight to Summary.
 */

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.fartech.agents.commons.*
import com.fartech.agents.tools.*
import com.fartech.ftapp2.commonsKt.*
import kotlinx.coroutines.delay
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

// ============================================
// Constants
// ============================================

private object NodeNames {
    const val INITIALIZE_PROJECT = "__initialize_project__"
    const val GET_PROJECT_FILES = "__get_project_files__"
    const val GENERATE_DESIGN_SPECIFICATION = "__generate_design_specification__"
    const val REVIEW_DESIGN = "__review_design__"
    const val SLICE_DESIGN_ASSETS = "__slice_design_assets__"
    const val REVIEW_SLICING = "__review_slicing__"
    const val DESIGN_ARCHITECTURE = "__design_architecture__"
    const val REVIEW_ARCHITECTURE = "__review_architecture__"
    const val ASK_REQUIREMENTS = "__ask_requirements_if_needed__"
    const val SETUP_NODE = "__setup_node__"
    const val RETRIEVAL_TASK = "__retrieval_task__"

    // Parallel source file generation
    const val GENERATE_SOURCE_FILES_PARALLEL = "__generate_source_files_parallel__"
    const val CHECK_AND_RETRY_FAILED_SOURCE_FILES = "__check_and_retry_failed_source_files__"
    const val RETRY_FAILED_SOURCE_FILES = "__retry_failed_source_files__"

    // Parallel media file generation
    const val COLLECT_MEDIA_FILES = "__collect_media_files__"
    const val GENERATE_MEDIA_FILES_PARALLEL = "__generate_media_files_parallel__"
    const val RETRY_FAILED_MEDIA_FILES = "__retry_failed_media_files__"

    // Compilation check and fix
    const val PERFORM_COMPILATION_CHECK = "__perform_compilation_check__"
    const val FIX_COMPILATION_ERRORS = "__fix_compilation_errors__"

    const val BUILD_DEBUG = "__build_debug__"
    const val TESTING = "__testing__"
    const val SUMMARY = "__summary__"
}

private object StorageKeys {
    const val LAYOUT = "layout"
    const val ARCHITECTURE = "architecture"
    const val FILES = "files"
    const val TOTAL_FILES = "total_files"
    const val FINAL_PROJECT_PROPERTIES = "final_project_properties"

    // For design specification and media assets
    const val DESIGN_SPEC = "design_spec"
    const val MEDIA_MANIFEST = "media_manifest"

    // For parallel generation
    const val SOURCE_FILE_BATCHES = "source_file_batches"
    const val MEDIA_FILES = "media_files"
    const val CURRENT_BATCH_INDEX = "current_batch_index"
    const val FILE_GENERATION_FAILURES = "file_generation_failures"
    const val SUCCESSFUL_FILES = "successful_files"

    // For retry tracking
    const val ARCHITECTURE_RETRY_COUNT = "architecture_retry_count"
    const val ARCHITECTURE_REVIEW_RETRY_COUNT = "architecture_review_retry_count"
    const val ARCHITECTURE_REVIEW_RESULT = "architecture_review_result"
    const val DESIGN_REVIEW_RETRY_COUNT = "design_review_retry_count"
    const val DESIGN_REVIEW_RESULT = "design_review_result"
    const val SLICING_REVIEW_RETRY_COUNT = "slicing_review_retry_count"
    const val SLICING_REVIEW_RESULT = "slicing_review_result"
    const val MEDIA_RETRY_ATTEMPTS = "media_retry_attempts"
    const val FAILED_MEDIA_FILES = "failed_media_files"

    // For compilation fix limits
    const val COMPILATION_FIX_COUNTS = "compilation_fix_counts"
}


// ============================================
// iOS App Development Agent Entry Point
// ============================================

/**
 * iOS App Development Agent entry point.
 *
 * This agent helps transform user requirements into a runnable iOS app project
 * (or a project that can be further built on macOS). It specializes in:
 * - Swift/SwiftUI development
 * - Xcode, XcodeGen, SPM, CocoaPods
 * - Alternative solutions: Flutter, React Native, UniApp, Kotlin Multiplatform
 *
 * The workflow consists of six main phases:
 * 1. Project Initialization: Prepare directories and delegate Native initialization to a SubAgent (generate project.yml, run xcodegen on macOS)
 * 2. Project Files: Reads existing project structure and asks LLM to generate the remaining file list; may ask user requirements if needed
 * 3. File Generation: Creates source code and media files
 * 4. Build and Debug: Builds the project and iteratively fixes any errors
 * 5. Testing: Generates and runs basic tests to validate functionality
 * 6. Summary: Provides a comprehensive summary with build instructions and next steps
 *
 * @param httpAccess HTTP access provider for downloading resources and making web requests
 * @param parameters Configuration parameters including project properties, retry cycles, etc.
 */
suspend fun iosAppDevelopAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>
) {
    val systemPrompt = """
        You are IOSAppDevelopAgent, a full-stack development engineer capable of transforming user requirements into fully functional, runnable iOS apps (or projects that can be built on macOS). You excel at Swift/SwiftUI/Objective-C, Xcode, XcodeGen, SPM, CocoaPods, and can provide alternative solutions using Flutter/React Native/UniApp/Kotlin Multiplatform when needed.
        
        CORE COMPETENCIES:
        - Expert-level code development across all iOS technologies
        - Accurate asset extraction and integration from design files into projects
        - Designing correct project structures based on user requirements
        - Writing development documentation, test documentation, and test cases
        - Creating product designs and generating design files, assets, and other necessary resources based on user requirements
        
        CODE QUALITY STANDARDS:
        Your code MUST be:
        - Well-structured with comprehensive comments for clarity
        - Efficient and concise
        - Maintainable
        - Testable
        - Extensible
        - Secure
        - Following established conventions and best practices
        - Adhering to industry standards
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        OUTPUT SPECIFICATIONS (MANDATORY)
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        1. TOOL INVOCATION PHASES:
           - Return ONLY necessary content
           - DO NOT include unnecessary explanations, reasoning, prefixes, suffixes, acknowledgments, or pleasantries
           - During tool-calling phases: Complete tasks ONLY by calling tools
           - DO NOT send any free-form text except tool calls and final Exit
        
        2. STREAMING CODE GENERATION PHASES:
           - Output ONLY the complete raw content of the target file
           - DO NOT use Markdown code block markers (e.g., ```)
           - DO NOT add filenames or explanations
        
        3. BASE64 CONTENT GENERATION:
           - Output ONLY pure Base64 strings
           - DO NOT include data: prefix
           - DO NOT include language tags
           - DO NOT include newlines or any other characters
        
        4. JSON/STRUCTURED DATA RESPONSES:
           - Return strictly structured data as required
           - DO NOT include extra text or code block fences
    """.trimIndent()

    val props = parameters.parameter("project_properties", IOSAppProjectProperties())
    logProgress(
        AnsiColor.GREEN,
        "AgentStart",
        "启动 iOS App 生成代理：${props.appName}（技术栈：${props.implementTechnology}，项目根目录：${props.projectRoot}）"
    )

    buildAndRunStringAgent(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt,
        toolRegistry = parseToolSet(
            parameters,
            httpAccess,
            tools = listOf(
                AgentTools.INTERACTIVE,
                AgentTools.SUB_AGENT,
                AgentTools.FILE_OPERATIONS,
                AgentTools.WEB_TOOLS,
                AgentTools.EXIT,
                AgentTools.MULTI_MEDIA,
                AgentTools.APPLE_APP_INFO
            )
        ),
        strategyBuilder = { parameters, httpAccess, _ ->
            appDevelopStrategy(parameters, httpAccess)
        }
    )
}

/**
 * Creates the iOS app development strategy with all nodes and workflow edges.
 *
 * @param parameters Configuration parameters for the agent
 * @param httpAccess HTTP access provider for web requests
 * @param projectProperties iOS project properties with app configuration
 * @return The configured agent strategy
 */
fun appDevelopStrategy(
    parameters: List<ConfigurationParameter>,
    httpAccess: HttpAccess,
    projectProperties: IOSAppProjectProperties = parameters.parameter("project_properties", IOSAppProjectProperties()),
): AIAgentGraphStrategy<String, String> = strategy<String, String>(
    name = parameters.parameter("strategy", "ios_app_develop"),
) {

    val llModelGroupConfig = parameters.parameter(
        key = "llm_config",
        defaultValue = LLModelGroupConfig(models = listOf())
    )

    // ============================================
    // Storage Keys
    // ============================================

    val layoutKey = createStorageKey<IOSAppProjectFiles>(StorageKeys.LAYOUT)
    val architectureKey = createStorageKey<ProjectArchitecture>(StorageKeys.ARCHITECTURE)
    val filesKey = createStorageKey<IOSAppProjectFiles>(StorageKeys.FILES)

    // Pre-generate and reuse all storage keys
    val finalProjectPropertiesKey = createStorageKey<IOSAppProjectProperties>(StorageKeys.FINAL_PROJECT_PROPERTIES)
    val designSpecKey = createStorageKey<DesignSpecification>(StorageKeys.DESIGN_SPEC)
    val mediaManifestKey = createStorageKey<MediaAssetsManifest>(StorageKeys.MEDIA_MANIFEST)
    val sourceFileBatchesKey = createStorageKey<List<List<IOSAppProjectFile>>>(StorageKeys.SOURCE_FILE_BATCHES)
    val mediaFilesKey = createStorageKey<List<IOSAppProjectFile>>(StorageKeys.MEDIA_FILES)
    val currentBatchIndexKey = createStorageKey<Int>(StorageKeys.CURRENT_BATCH_INDEX)
    val totalFilesKey = createStorageKey<Int>(StorageKeys.TOTAL_FILES)
    val fileGenerationFailuresKey = createStorageKey<FileGenerationFailures>(StorageKeys.FILE_GENERATION_FAILURES)
    val successfulFilesKey = createStorageKey<MutableList<String>>(StorageKeys.SUCCESSFUL_FILES)

    val architectureRetryCountKey = createStorageKey<Int>(StorageKeys.ARCHITECTURE_RETRY_COUNT)
    val projectFilesRetryCountKey = createStorageKey<Int>("project_files_retry_count")
    val architectureReviewRetryCountKey = createStorageKey<Int>(StorageKeys.ARCHITECTURE_REVIEW_RETRY_COUNT)
    val architectureReviewResultKey =
        createStorageKey<UnifiedArchitectureReview>(StorageKeys.ARCHITECTURE_REVIEW_RESULT)

    // New: Design & Slicing review tracking
    val designReviewRetryCountKey = createStorageKey<Int>(StorageKeys.DESIGN_REVIEW_RETRY_COUNT)
    val designReviewResultKey = createStorageKey<DesignReviewResult>(StorageKeys.DESIGN_REVIEW_RESULT)
    val slicingReviewRetryCountKey = createStorageKey<Int>(StorageKeys.SLICING_REVIEW_RETRY_COUNT)
    val slicingReviewResultKey = createStorageKey<MediaAssetsReviewResult>(StorageKeys.SLICING_REVIEW_RESULT)

    val mediaRetryAttemptsKey = createStorageKey<Int>(StorageKeys.MEDIA_RETRY_ATTEMPTS)
    val failedMediaFilesKey = createStorageKey<List<IOSAppProjectFile>>(StorageKeys.FAILED_MEDIA_FILES)

    val compilationFixCountsKey = createStorageKey<MutableMap<String, Int>>(StorageKeys.COMPILATION_FIX_COUNTS)
    val compilationCheckResultKey = createStorageKey<CompilationCheckResult>("compilation_check_result")
    val compilationFixRetriesKey = createStorageKey<Int>("compilation_fix_retries")

    val skipAskRequirements = parameters.parameter("skip_ask_requirements", false)

    // ============================================
    // Competitor Analysis Tools
    // ============================================
    val appleAppInfoTools = AppleAppInfoTools(httpAccess)

    // ============================================
    // Helper Functions (defined before nodes that use them)
    // ============================================

    /**
     * Helper function to run initialization SubAgents for non-Native tech stacks.
     */
    suspend fun runInitializationSubAgent(name: String, prompt: String): String {
        return SubAgentTools(httpAccess, parameters).runSubAgent(
            SubAgentContext(
                prompt = prompt,
                name = name,
                llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.setup)
                    .toLLM()
            )
        )
    }

    /**
     * Native iOS initialization: Programmatic approach with Sub Agent for YAML generation only.
     * This approach is more robust as it:
     * - Creates directories programmatically (deterministic)
     * - Uses Sub Agent only for generating/fixing project.yml (creative work)
     * - Runs xcodegen programmatically with retry logic
     */
    suspend fun initializeNativeProject(props: IOSAppProjectProperties, root: File, absRoot: String): String {
        printlnColor(AnsiColor.CYAN, "[Native Init] 开始初始化原生 iOS 项目...")

        // Step 1: Create standard directory structure programmatically
        printlnColor(AnsiColor.BLUE, "[Native Init] 步骤 1: 创建标准目录结构...")
        val standardDirs = listOf("Sources", "Resources", "Tests")
        standardDirs.forEach { dirName ->
            val dir = File(root, dirName)
            if (!dir.exists()) {
                dir.mkdirs()
                printlnColor(AnsiColor.CYAN, "[Native Init] 创建目录: ${dir.name}")
            }
        }

        // Step 2: Generate project.yml using Sub Agent
        printlnColor(AnsiColor.BLUE, "[Native Init] 步骤 2: 委托 Sub Agent 生成 project.yml...")
        val projectYamlFile = File(root, "project.yml")

        var lastError: String? = null
        val maxRetries: Int = parameters.parameter("xcodegen_max_retries", IOSGenConstants.DEFAULT_XCODEGEN_MAX_RETRIES)

        for (attempt in 1..maxRetries) {
            printlnColor(AnsiColor.CYAN, "[Native Init] 尝试 $attempt/$maxRetries: 生成/修复 project.yml")

            // Generate or fix project.yml
            val yamlPrompt = if (lastError == null) {
                createProjectYamlGenerationPrompt(props, absRoot)
            } else {
                createProjectYamlFixPrompt(props, absRoot, projectYamlFile, lastError)
            }

            val subAgentResult = SubAgentTools(httpAccess, parameters).runSubAgent(
                SubAgentContext(
                    prompt = yamlPrompt,
                    name = "GenerateProjectYaml_Attempt$attempt",
                    llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.generateCode)
                        .toLLM()
                )
            )

            printlnColor(AnsiColor.BLUE, "[Native Init] Sub Agent 返回: $subAgentResult")

            // Wait for file system to complete I/O operations
            delay(500.milliseconds)

            // Verify project.yml was created and has valid content
            if (!projectYamlFile.exists()) {
                lastError = "project.yml 文件未生成。Sub Agent 输出：$subAgentResult"
                printlnColor(AnsiColor.YELLOW, "[Native Init] project.yml 未生成，将重试...")
                continue
            }

            val yamlContent = projectYamlFile.readText()
            if (yamlContent.isBlank()) {
                lastError = "project.yml 文件为空。Sub Agent 输出：$subAgentResult"
                printlnColor(AnsiColor.YELLOW, "[Native Init] project.yml 为空，将重试...")
                continue
            }

            printlnColor(AnsiColor.GREEN, "[Native Init] project.yml 已生成")

            // Step 3: Check if xcodegen is available
            if (!isMacOS()) {
                printlnColor(AnsiColor.YELLOW, "[Native Init] 非 macOS 环境，跳过 xcodegen 执行")
                return "Native 项目结构和 project.yml 已创建（非 macOS 环境，未执行 xcodegen）"
            }

            printlnColor(AnsiColor.BLUE, "[Native Init] 步骤 3: 检查 xcodegen 可用性...")
            val xcodegenCheckProcess = try {
                ProcessBuilder("xcodegen", "--version")
                    .directory(root)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
            } catch (_: java.io.IOException) {
                printlnColor(AnsiColor.YELLOW, "[Native Init] xcodegen 未安装，跳过项目生成")
                printlnColor(AnsiColor.CYAN, "[Native Init] 提示：请运行 'brew install xcodegen' 安装 xcodegen")
                return "Native 项目结构和 project.yml 已创建（xcodegen 未安装）"
            } finally {
                // Cleanup is automatic for ProcessBuilder
            }

            // 30 s is generous for `xcodegen --version`; a hang past this is a stuck CLI.
            val xcodegenCheckFinished = xcodegenCheckProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!xcodegenCheckFinished) {
                xcodegenCheckProcess.destroyForcibly()
                printlnColor(AnsiColor.YELLOW, "[Native Init] xcodegen --version 超时，跳过项目生成")
                return "Native 项目结构和 project.yml 已创建（xcodegen 超时）"
            }
            val xcodegenCheckExitCode = xcodegenCheckProcess.exitValue()
            if (xcodegenCheckExitCode != 0) {
                printlnColor(AnsiColor.YELLOW, "[Native Init] xcodegen 不可用，跳过项目生成")
                return "Native 项目结构和 project.yml 已创建（xcodegen 不可用）"
            }

            printlnColor(AnsiColor.GREEN, "[Native Init] xcodegen 可用")

            // Step 4: Run xcodegen programmatically
            printlnColor(AnsiColor.BLUE, "[Native Init] 步骤 4: 执行 xcodegen...")
            val xcodegenProcess = ProcessBuilder(
                "xcodegen",
                "--spec", projectYamlFile.absolutePath,
                "--project", root.absolutePath
            )
                .directory(root)
                .redirectErrorStream(true) // Merge stderr into stdout to avoid potential deadlocks
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()

            // Drain output on a background thread with a 1 MiB cap, so `xcodegen` can't
            // OOM the agent by spamming progress output. After the cap we keep draining
            // so the producer doesn't wedge on a full pipe.
            val xcodegenOutput = StringBuilder()
            val xcodegenDrainer = Thread {
                try {
                    val buf = ByteArray(8 * 1024)
                    xcodegenProcess.inputStream.use { stream ->
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            if (xcodegenOutput.length < 1 * 1024 * 1024) {
                                val take = minOf(n, 1 * 1024 * 1024 - xcodegenOutput.length)
                                xcodegenOutput.append(String(buf, 0, take, Charsets.UTF_8))
                            }
                        }
                    }
                } catch (_: Exception) {
                    // pipe closed (process killed) — return what we have
                }
            }
            xcodegenDrainer.isDaemon = true
            xcodegenDrainer.start()

            // 5 minutes is generous even for large project.yml; xcodegen normally finishes
            // in seconds. A longer hang is a stuck CLI / filesystem.
            val xcodegenFinished = xcodegenProcess.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
            if (!xcodegenFinished) {
                xcodegenProcess.destroyForcibly()
                xcodegenDrainer.join(2_000)
                lastError = "xcodegen 执行超时（5 分钟）"
                printlnColor(AnsiColor.YELLOW, "[Native Init] xcodegen 超时，将重试...")
                continue
            }
            xcodegenDrainer.join(2_000)
            val combinedOutput = xcodegenOutput.toString()
            val exitCode = xcodegenProcess.exitValue()

            if (exitCode == 0) {
                printlnColor(AnsiColor.GREEN_B, "[Native Init] ✅ xcodegen 执行成功！")
                return "Native iOS 项目初始化成功（尝试 $attempt 次）"
            } else {
                // xcodegen failed, prepare error message for next retry
                lastError = buildString {
                    appendLine("xcodegen 执行失败（退出码：$exitCode）")
                    if (combinedOutput.isNotBlank()) {
                        appendLine("\n输出：")
                        appendLine(combinedOutput)
                    }
                }

                printlnColor(AnsiColor.RED, "[Native Init] xcodegen 失败：")
                printlnColor(AnsiColor.YELLOW, lastError)

                if (attempt < maxRetries) {
                    printlnColor(AnsiColor.CYAN, "[Native Init] 将根据错误信息重新生成 project.yml...")
                }
            }
        }

        // If we exhausted all retries
        printlnColor(AnsiColor.RED, "[Native Init] ❌ 经过 $maxRetries 次尝试后仍然失败")
        return "Native 项目初始化失败（已重试 $maxRetries 次）。最后错误：$lastError"
    }

    // ============================================
    // Extracted helper nodes for readability
    // ============================================

    suspend fun handleGenerateSourceFilesBatch(
        batch: List<IOSAppProjectFile>,
        architecture: ProjectArchitecture?,
        parallelism: Int,
        httpAccess: HttpAccess,
        parameters: List<ConfigurationParameter>,
        designSpec: DesignSpecification?,
        mediaManifest: MediaAssetsManifest?
    ): String {
        if (batch.isEmpty()) {
            logProgress(AnsiColor.GREEN, "Generate", "当前批次为空，跳过")
            return "Empty batch, skipped"
        }

        logProgress(
            AnsiColor.MAGENTA,
            "Generate",
            "开始并行生成 ${batch.size} 个源码文件（并行度：$parallelism）"
        )

        val agentContexts = batch.map { file ->
            SubAgentContext(
                prompt = createFileGenerationPrompt(
                    file = file,
                    architecture = architecture,
                    projectProperties = projectProperties,
                    designSpec = designSpec,
                    mediaManifest = mediaManifest
                ),
                name = "GenerateFile_${file.name}",
                llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.generateCode)
                    .toLLM()
            )
        }

        val result = try {
            SubAgentTools(httpAccess, parameters).runSubAgentInParallel(
                agentContexts = agentContexts,
                parallelism = parallelism,
                maxExecutionTimeInSeconds = 300
            )
        } catch (e: Exception) {
            logProgress(
                AnsiColor.RED,
                "Generate",
                "源码文件并行生成过程中发生错误：${e.message}"
            )
            printlnColor(AnsiColor.YELLOW, "错误详情：${e.stackTraceToString()}")

            val successfulFiles = batch.filter { file ->
                File(file.path).exists()
            }
            logProgress(
                AnsiColor.YELLOW,
                "Generate",
                "已成功生成 ${successfulFiles.size}/${batch.size} 个源码文件"
            )

            "源码文件生成部分完成：${successfulFiles.size}/${batch.size} 个文件已生成。错误：${e.message}"
        }

        logProgress(
            AnsiColor.GREEN,
            "Generate",
            "批次生成流程完成"
        )

        return result
    }

    data class MediaGenerationOutcome(
        val summary: String,
        val failedFiles: List<IOSAppProjectFile>,
        val successfulCount: Int,
        val totalCount: Int,
        val baseImageResults: String,
        val derivedImageResults: String,
        val otherMediaResults: String
    )

    suspend fun handleGenerateMediaFiles(
        mediaFiles: List<IOSAppProjectFile>,
        parallelism: Int,
        httpAccess: HttpAccess,
        projectProperties: IOSAppProjectProperties
    ): MediaGenerationOutcome {
        if (mediaFiles.isEmpty()) {
            logProgress(AnsiColor.GREEN, "Media", "没有媒体文件需要生成")
            return MediaGenerationOutcome(
                summary = "No media files to generate",
                failedFiles = emptyList(),
                successfulCount = 0,
                totalCount = 0,
                baseImageResults = "",
                derivedImageResults = "",
                otherMediaResults = ""
            )
        }

        val imageFiles = mediaFiles.filter { file ->
            val m = file.mime.lowercase()
            m.startsWith("image/")
        }
        val otherMediaFiles = mediaFiles - imageFiles.toSet()

        logProgress(
            AnsiColor.MAGENTA,
            "Media",
            "开始并行生成 ${mediaFiles.size} 个媒体文件（图片：${imageFiles.size}，其他：${otherMediaFiles.size}，并行度：$parallelism）"
        )

        val imageGroups: Map<String, List<IOSAppProjectFile>> = imageFiles.groupBy { computeImageGroupKey(it) }
        logProgress(
            AnsiColor.CYAN,
            "Media",
            "检测到 ${imageGroups.size} 个图像分组（按 .imageset/.appiconset 或规范化名称聚类）"
        )

        val baseFilesByGroup: Map<String, IOSAppProjectFile> = imageGroups.mapValues { (_, group) ->
            pickBaseImageFile(group)
        }

        val baseImageAgentContexts = baseFilesByGroup.map { (_, baseFile) ->
            val hasDesignFiles = projectProperties.designFiles.isNotEmpty()
            SubAgentContext(
                prompt = createImageGenerationPromptWithDownload(
                    file = baseFile,
                    projectProperties = projectProperties,
                    referenceImagePath = null,
                    hasDesignAttachments = hasDesignFiles
                ),
                name = "GenerateImageBase_${baseFile.name}",
                llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.default)
                    .toLLM(),
                attachments = if (hasDesignFiles) projectProperties.designFiles else emptyList()
            )
        }

        val baseImageResults = if (baseImageAgentContexts.isNotEmpty()) {
            try {
                logProgress(
                    AnsiColor.CYAN,
                    "Media",
                    "阶段 A：并行生成 ${baseImageAgentContexts.size} 个图像分组的基准图片..."
                )
                SubAgentTools(httpAccess, parameters).runSubAgentInParallel(
                    agentContexts = baseImageAgentContexts,
                    parallelism = parallelism,
                    maxExecutionTimeInSeconds = 240
                )
            } catch (e: Exception) {
                logProgress(AnsiColor.RED, "Media", "阶段 A 基准图片生成错误：${e.message}")
                printlnColor(AnsiColor.YELLOW, "错误详情：${e.stackTraceToString()}")
                "阶段A错误：${e.message}"
            }
        } else {
            "No base images to generate"
        }

        baseFilesByGroup.values.forEach { baseFile ->
            try {
                val agentName = "GenerateImageBase_${baseFile.name}"
                val agentOutput = extractAgentOutput(baseImageResults, agentName)
                if (agentOutput == null) {
                    printlnColor(AnsiColor.YELLOW, "[Media] 未找到基准图片代理输出: ${baseFile.path}")
                } else {
                    printlnColor(AnsiColor.CYAN, "[Media] 基准图片生成完成，检查文件: ${baseFile.path}")
                    if (validateAndRepairMedia(baseFile)) {
                        printlnColor(AnsiColor.GREEN, "[Media] 基准图片已生成并验证: ${baseFile.path}")
                    } else {
                        printlnColor(AnsiColor.RED, "[Media] 基准图片生成失败或文件无效: ${baseFile.path}")
                        printlnColor(AnsiColor.YELLOW, "[Media] 代理输出内容: $agentOutput")
                    }
                }
            } catch (e: Exception) {
                printlnColor(AnsiColor.RED, "[Media] 处理基准图片 ${baseFile.path} 时发生错误: ${e.message}")
            }
        }

        val derivedImageFiles = imageGroups.flatMap { (groupKey, group) ->
            val base = baseFilesByGroup[groupKey]
            group.filter { it.path != base?.path }
        }

        val derivedAgentContexts = derivedImageFiles.map { file ->
            val bp = baseFilesByGroup[computeImageGroupKey(file)]?.path
            val refPath = bp?.takeIf { File(it).exists() }
            val hasDesignFiles = projectProperties.designFiles.isNotEmpty()
            SubAgentContext(
                prompt = createImageGenerationPromptWithDownload(
                    file = file,
                    projectProperties = projectProperties,
                    referenceImagePath = refPath,
                    hasDesignAttachments = hasDesignFiles
                ),
                name = "GenerateImageRef_${file.name}",
                llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.default)
                    .toLLM(),
                attachments = if (hasDesignFiles) projectProperties.designFiles else emptyList()
            )
        }

        val derivedImageResults = if (derivedAgentContexts.isNotEmpty()) {
            try {
                logProgress(
                    AnsiColor.CYAN,
                    "Media",
                    "阶段 B：并行生成 ${derivedAgentContexts.size} 个参考图像（严格与基准一致，仅尺寸不同）..."
                )
                SubAgentTools(httpAccess, parameters).runSubAgentInParallel(
                    agentContexts = derivedAgentContexts,
                    parallelism = parallelism,
                    maxExecutionTimeInSeconds = 240
                )
            } catch (e: Exception) {
                logProgress(AnsiColor.RED, "Media", "阶段 B 参考图像生成错误：${e.message}")
                printlnColor(AnsiColor.YELLOW, "错误详情：${e.stackTraceToString()}")
                "阶段B错误：${e.message}"
            }
        } else {
            "No derived images to generate"
        }

        derivedImageFiles.forEach { file ->
            try {
                val agentName = "GenerateImageRef_${file.name}"
                val agentOutput = extractAgentOutput(derivedImageResults, agentName)
                if (agentOutput == null) {
                    printlnColor(AnsiColor.YELLOW, "[Media] 未找到参考图片代理输出: ${file.path}")
                } else {
                    printlnColor(AnsiColor.CYAN, "[Media] 参考图片生成完成，检查文件: ${file.path}")
                    if (validateAndRepairMedia(file)) {
                        printlnColor(AnsiColor.GREEN, "[Media] 参考图片已生成并验证: ${file.path}")
                    } else {
                        printlnColor(AnsiColor.RED, "[Media] 参考图片生成失败或文件无效: ${file.path}")
                        printlnColor(AnsiColor.YELLOW, "[Media] 代理输出内容: $agentOutput")
                    }
                }
            } catch (e: Exception) {
                printlnColor(AnsiColor.RED, "[Media] 处理参考图片 ${file.path} 时发生错误: ${e.message}")
            }
        }

        val otherMediaAgentContexts = otherMediaFiles.map { file ->
            SubAgentContext(
                prompt = createMediaGenerationPromptWithWrite(file, projectProperties),
                name = "GenerateMedia_${file.name}",
                llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.generateCode)
                    .toLLM()
            )
        }

        val otherMediaResults = if (otherMediaAgentContexts.isNotEmpty()) {
            try {
                logProgress(AnsiColor.CYAN, "Media", "并行生成 ${otherMediaFiles.size} 个非图片媒体文件...")
                SubAgentTools(httpAccess, parameters).runSubAgentInParallel(
                    agentContexts = otherMediaAgentContexts,
                    parallelism = parallelism,
                    maxExecutionTimeInSeconds = 300
                )
            } catch (e: Exception) {
                logProgress(
                    AnsiColor.RED,
                    "Media",
                    "其他媒体文件并行生成过程中发生错误：${e.message}"
                )
                printlnColor(AnsiColor.YELLOW, "错误详情：${e.stackTraceToString()}")
                "其他媒体生成错误：${e.message}"
            }
        } else {
            "No other media files to generate"
        }

        // partition: validateAndRepairMedia is SIDE-EFFECTFUL (may rewrite the file)
        // and reads up to 10 MB per call — the old filter + filterNot pair ran it
        // twice per file, doubling the I/O and re-validating just-repaired files.
        val (successfulFiles, failedFiles) = mediaFiles.partition { file ->
            validateAndRepairMedia(file)
        }

        logProgress(
            AnsiColor.CYAN,
            "Media",
            "已成功生成 ${successfulFiles.size}/${mediaFiles.size} 个媒体文件"
        )

        logProgress(
            AnsiColor.GREEN,
            "Media",
            "媒体文件生成流程完成"
        )

        val summary =
            "Base image generation: $baseImageResults\nDerived image generation: $derivedImageResults\nOther media generation: $otherMediaResults"
        return MediaGenerationOutcome(
            summary = summary,
            failedFiles = failedFiles,
            successfulCount = successfulFiles.size,
            totalCount = mediaFiles.size,
            baseImageResults = baseImageResults,
            derivedImageResults = derivedImageResults,
            otherMediaResults = otherMediaResults
        )
    }

    /**
     * Create UniApp directory structure programmatically.
     */
    fun createUniAppDirectories(root: File) {
        val uniAppDirs = listOf("pages", "static", "components", "common")
        uniAppDirs.forEach { dirName ->
            val dir = File(root, dirName)
            if (!dir.exists()) {
                dir.mkdirs()
                printlnColor(AnsiColor.CYAN, "[UniApp Init] 创建目录: ${dir.name}")
            }
        }
    }

    // ============================================
    // Node Definitions
    // ============================================


    // Project initialization node - delegates to tech-stack specific initialization logic
    val initializeProject by node<IOSAppProjectProperties, Unit>(
        name = NodeNames.INITIALIZE_PROJECT
    ) { props ->
        val root = prepareProjectRootDirectory(props)
        val absRoot = root.absolutePath

        val result = when (props.implementTechnology) {
            IOSAppProjectProperties.ImplementTechnology.Native ->
                initializeNativeProject(props, root, absRoot)

            IOSAppProjectProperties.ImplementTechnology.Flutter ->
                runInitializationSubAgent("InitFlutterProject", createFlutterInitPrompt(props, absRoot))

            IOSAppProjectProperties.ImplementTechnology.ReactNative ->
                runInitializationSubAgent("InitReactNativeProject", createReactNativeInitPrompt(props, absRoot))

            IOSAppProjectProperties.ImplementTechnology.KotlinMpp ->
                runInitializationSubAgent("InitKotlinMppProject", createKotlinMppInitPrompt(props, absRoot))

            IOSAppProjectProperties.ImplementTechnology.UniApp -> {
                printlnColor(AnsiColor.CYAN, "[Init] UniApp 技术栈：手动创建基础目录结构")
                createUniAppDirectories(root)
                "UniApp 项目目录已创建"
            }

            else -> {
                printlnColor(AnsiColor.YELLOW, "[Init] 未知技术栈：跳过工具化初始化")
                "跳过初始化"
            }
        }

        printlnColor(AnsiColor.GREEN, "[Init] ${props.implementTechnology} 初始化完成：$result")
        logProgress(AnsiColor.GREEN, "InitProject", "项目初始化完成")
    }

    // ============================================
    // Phase 0: Generate Design Specification (if no design files provided)
    // ============================================
    val generateDesignSpecification by node<IOSAppProjectProperties, DesignSpecification>(
        name = NodeNames.GENERATE_DESIGN_SPECIFICATION
    ) { props ->
        logProgress(AnsiColor.CYAN, "DesignSpec", "未检测到设计文件，基于需求生成设计规格...")

        // Fetch competitor app information if provided
        val competitorApps = if (props.competitorAppIds.isNotEmpty()) {
            logProgress(AnsiColor.CYAN, "CompetitorAnalysis", "获取 ${props.competitorAppIds.size} 个竞品应用信息...")
            props.competitorAppIds.mapNotNull { appId ->
                try {
                    val apps = appleAppInfoTools.getAppInfo(appId)
                    apps.firstOrNull()?.also {
                        logProgress(AnsiColor.GREEN, "CompetitorAnalysis", "✓ 已获取竞品: ${it.trackName ?: appId}")
                    }
                } catch (e: Exception) {
                    logProgress(AnsiColor.YELLOW, "CompetitorAnalysis", "⚠️ 无法获取竞品 $appId: ${e.message}")
                    null
                }
            }
        } else {
            emptyList()
        }

        if (competitorApps.isNotEmpty()) {
            logProgress(
                AnsiColor.GREEN,
                "CompetitorAnalysis",
                "已成功获取 ${competitorApps.size} 个竞品应用信息，将用于设计参考"
            )
        }

        // If this is a redesign after a failed review, include previous review feedback
        val lastDesignReview = storage.get(designReviewResultKey)
        val redesignSuffix = if (lastDesignReview != null && !lastDesignReview.approved) {
            logProgress(
                AnsiColor.YELLOW,
                "DesignSpec",
                "检测到上一次设计评审未通过，将根据反馈进行重新设计"
            )
            buildString {
                append("\n\nREVIEW FEEDBACK TO ADDRESS (STRICTLY FIX THESE BEFORE RESUBMISSION):\n")
                if (lastDesignReview.summary.isNotBlank()) {
                    append("- Summary: ")
                    append(lastDesignReview.summary)
                    append("\n")
                }
                if (lastDesignReview.issues.isNotEmpty()) {
                    append("- Issues (top ${lastDesignReview.issues.size}):\n")
                    lastDesignReview.issues.forEach { append("  • "); append(it); append("\n") }
                }
                if (lastDesignReview.missingItems.isNotEmpty()) {
                    append("- Missing Items (MUST include in new spec):\n")
                    lastDesignReview.missingItems.forEach { append("  • "); append(it); append("\n") }
                }
                append("\nRevise the design specification to FULLY address the above review feedback. Clearly add any previously missing screens/components/assets with exact dimensions and details.\n")
            }
        } else ""

        val (_, designSpec) = buildAndRunStructureToolAgent<DesignSpecification>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = """
                You are an expert product designer and UI/UX specialist.
                Generate a comprehensive design specification for an iOS app based on user requirements.
                ${if (competitorApps.isNotEmpty()) "You have access to competitor app information for reference. Use it to inform your design decisions and create a competitive product." else ""}
                Must include:
                - App icon design (colors, style, symbolism)
                - Launch screen design
                - All screen layouts with exact dimensions
                - Color palette and typography
                - All UI elements (buttons, images, backgrounds, etc.)
                - Animation specifications

                Output MUST be detailed enough for developers to implement without ambiguity.
            """.trimIndent(),
            toolRegistry = ToolRegistry {
                tool(ExitTool)
                tools(AppleAppInfoTools(httpAccess))
            },
            input = createDesignSpecificationPrompt(props, competitorApps) + redesignSuffix,
            llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.design)
                .toLLM().toLLModel(),
            attachments = emptyList(),
            examples = listOf(
                // Extended core examples
                exampleDesignSpecification_FitnessApp_Extended,
                exampleDesignSpecification_FinanceApp_Extended,
                exampleDesignSpecification_TravelApp_Extended,
                // Multi-category coverage
                exampleDesignSpecification_ECommerceApp,
                exampleDesignSpecification_SocialApp,
                exampleDesignSpecification_HealthMedicalApp,
                exampleDesignSpecification_ProductivityApp,
                exampleDesignSpecification_NewsApp,
                exampleDesignSpecification_EducationApp
            )
        )

        val specResult = designSpec ?: error("Design spec generation returned null")
        logProgress(
            AnsiColor.GREEN,
            "DesignSpec",
            "设计规格生成完成: ${specResult.screens.size} 个屏幕, ${specResult.assets.size} 个资源规格"
        )

        // Store design spec for later use
        storage.set(designSpecKey, specResult)
        specResult
    }

    // ============================================
    // Phase 1: Slice Design Assets (from design files or design spec)
    // ============================================
    // ============================================
    // Phase 1.4: Review Design Specification
    // ============================================
    val reviewDesign by node<DesignSpecification, DesignReviewResult>(
        name = NodeNames.REVIEW_DESIGN
    ) { designSpec ->
        // Optional design review: default disabled
        val designReviewEnabled = parameters.parameter("enable_design_review", false)
        if (!designReviewEnabled) {
            logProgress(AnsiColor.YELLOW, "DesignReview", "已按配置跳过设计评审（默认关闭）。")
            val skipped = DesignReviewResult(
                qualityScore = 100,
                approved = true,
                complete = true,
                issues = emptyList(),
                missingItems = emptyList(),
                summary = "Design review skipped by config (enable_design_review=false)"
            )
            storage.set(designReviewResultKey, skipped)
            return@node skipped
        }

        val currentRetries = storage.get(designReviewRetryCountKey) ?: 0
        val maxRetries =
            parameters.parameter("design_review_max_retries", IOSGenConstants.DEFAULT_DESIGN_REVIEW_MAX_RETRIES)
        logProgress(AnsiColor.CYAN, "DesignReview", "开始设计规格评审（第 ${currentRetries + 1}/$maxRetries 次）...")

        val (_, result) = buildAndRunStructureAgent<String, DesignReviewResult>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = """
                You are a senior mobile product designer and UI/UX reviewer.
                Review the provided design specification for quality and completeness.
                Be strict: the design must be detailed enough for unambiguous implementation and asset slicing.
            """.trimIndent(),
            input = createDesignReviewPrompt(projectProperties, designSpec),
            emptyValue = DesignReviewResult(
                qualityScore = 0,
                approved = false,
                complete = false,
                summary = "Design review failed"
            ),
            llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.reviewDesign)
                .toLLM().toLLModel(),
            attachments = emptyList(),
            examples = listOf(
                exampleDesignReviewResult_Pass,
                exampleDesignReviewResult_FailIncomplete,
                exampleDesignReviewResult_PassWithNotes
            )
        )
        val reviewResult = result ?: error("Design review returned null result")

        val statusIcon = if (reviewResult.approved) "✅" else "⚠️"
        val completenessIcon = if (reviewResult.complete) "✓" else "✗"
        logProgress(
            if (reviewResult.approved) AnsiColor.GREEN else AnsiColor.YELLOW,
            "DesignReview",
            "质量评分: ${reviewResult.qualityScore}/100 | 完整性: $completenessIcon | 状态: $statusIcon ${if (reviewResult.approved) "通过" else "需要改进"}"
        )
        if (!reviewResult.approved) {
            if (reviewResult.issues.isNotEmpty()) {
                logProgress(AnsiColor.YELLOW, "DesignReview", "发现问题 ${reviewResult.issues.size} 项（示例）：")
                reviewResult.issues.take(5).forEach { printlnColor(AnsiColor.CYAN, "  • $it") }
            }
            if (reviewResult.missingItems.isNotEmpty()) {
                logProgress(AnsiColor.YELLOW, "DesignReview", "缺失项 ${reviewResult.missingItems.size} 项（示例）：")
                reviewResult.missingItems.take(5).forEach { printlnColor(AnsiColor.CYAN, "  • $it") }
            }
        } else {
            logProgress(AnsiColor.GREEN, "DesignReview", "✅ 设计规格评审通过！")
        }

        storage.set(designReviewResultKey, reviewResult)
        reviewResult
    }

    // ============================================
    // Phase 1.5: Review Slicing (Media Assets Manifest)
    // ============================================
    val sliceDesignAssets by node<Unit, MediaAssetsManifest>(
        name = NodeNames.SLICE_DESIGN_ASSETS
    ) {
        logProgress(AnsiColor.CYAN, "SliceAssets", "开始切图与资源规格生成...")

        // Fetch competitor app information if provided (reuse from earlier or fetch again)
        val competitorApps = if (projectProperties.competitorAppIds.isNotEmpty()) {
            logProgress(AnsiColor.CYAN, "CompetitorAnalysis", "获取竞品应用的视觉资产参考...")
            projectProperties.competitorAppIds.mapNotNull { appId ->
                try {
                    val apps = appleAppInfoTools.getAppInfo(appId)
                    apps.firstOrNull()
                } catch (_: Exception) {
                    logProgress(AnsiColor.YELLOW, "CompetitorAnalysis", "⚠️ 无法获取竞品 $appId 的资产信息")
                    null
                }
            }
        } else {
            emptyList()
        }

        if (competitorApps.isNotEmpty()) {
            logProgress(
                AnsiColor.GREEN,
                "CompetitorAnalysis",
                "将参考 ${competitorApps.size} 个竞品的视觉设计进行资产生成"
            )
        }

        // Determine whether to use design files or design spec
        val hasDesignFiles = projectProperties.designFiles.isNotEmpty()
        val designSpec = storage.get(designSpecKey)

        val (attachments, inputPrompt) = if (hasDesignFiles) {
            // Scenario A: User provided design files
            logProgress(
                AnsiColor.CYAN,
                "SliceAssets",
                "基于用户提供的 ${projectProperties.designFiles.size} 个设计图进行切图"
            )
            projectProperties.designFiles to
                    createSliceAssetsFromDesignFilesPrompt(projectProperties, competitorApps)
        } else if (designSpec != null) {
            // Scenario B: Use LLM-generated design spec
            logProgress(AnsiColor.CYAN, "SliceAssets", "基于生成的设计规格进行切图")
            emptyList<AttachmentFile>() to
                    createSliceAssetsFromDesignSpecPrompt(projectProperties, designSpec, competitorApps)
        } else {
            // Scenario C: Fallback to imagination mode (should not happen, but maintain compatibility)
            logProgress(AnsiColor.YELLOW, "SliceAssets", "警告: 无设计图也无设计规格，使用想象模式")
            emptyList<AttachmentFile>() to
                    createSliceAssetsFallbackPrompt(projectProperties)
        }

        // If this is a re-slicing after a failed review, include previous review feedback
        val lastSlicingReview = storage.get(slicingReviewResultKey)
        val slicingFixSuffix = if (lastSlicingReview != null && !lastSlicingReview.approved) {
            logProgress(
                AnsiColor.YELLOW,
                "SliceAssets",
                "检测到上一次切图评审未通过，将根据反馈进行修正再生成媒体清单"
            )
            buildString {
                append("\n\nREVIEW FEEDBACK TO ADDRESS FOR ASSET MANIFEST (FIX BEFORE RESUBMISSION):\n")
                if (lastSlicingReview.summary.isNotBlank()) {
                    append("- Summary: ")
                    append(lastSlicingReview.summary)
                    append("\n")
                }
                if (lastSlicingReview.missingAssets.isNotEmpty()) {
                    append("- Missing/Problematic Assets (MUST add/fix):\n")
                    lastSlicingReview.missingAssets.forEach { append("  • "); append(it); append("\n") }
                }
                append("\nUpdate the manifest to cover ALL required app icon sizes and any assets requested by the review. Ensure exact dimensions, proper .appiconset/.imageset grouping, and precise descriptions.\n")
            }
        } else ""
        val finalInputPrompt = inputPrompt + slicingFixSuffix

        val (_, manifest) = buildAndRunStructureAgent<String, MediaAssetsManifest>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = """
                You are an expert iOS asset manager and designer.
                Analyze the design materials and generate a COMPLETE manifest of all required media assets.

                CRITICAL REQUIREMENTS:
                1. App Icon: ALL required sizes (.appiconset) - at least 12 sizes
                2. Launch Screen: At least one launch image
                3. UI Assets: All images, icons, backgrounds mentioned in design
                4. Each asset MUST have exact dimensions and detailed description
                5. DO NOT miss any visual element from the design.
            """.trimIndent(),
            input = finalInputPrompt,
            emptyValue = MediaAssetsManifest(assets = emptyList()),
            llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.design)
                .toLLM().toLLModel(),
            attachments = attachments,
            examples = listOf(
                exampleMediaAssetsManifest,
                exampleMediaAssetsManifest_Finance,
                exampleMediaAssetsManifest_Travel
            )
        )

        // Validate: must have app icon and launch screen
        val manifestResult = manifest ?: error("Media manifest generation returned null")
        val hasAppIcon = manifestResult.assets.any { it.purpose.contains("app_icon") }
        val hasLaunchScreen =
            manifestResult.assets.any { it.purpose.contains("launch_screen") || it.purpose.contains("launch") }

        if (!hasAppIcon) {
            logProgress(AnsiColor.RED, "SliceAssets", "⚠️ 错误: 媒体清单缺少 App Icon!")
        }
        if (!hasLaunchScreen) {
            logProgress(AnsiColor.YELLOW, "SliceAssets", "⚠️ 警告: 媒体清单缺少 Launch Screen!")
        }

        val iconCount = manifestResult.assets.count { it.purpose.contains("icon", ignoreCase = true) }
        val imageCount = manifestResult.assets.count { it.type == "image" && !it.purpose.contains("icon", ignoreCase = true) }
        val otherCount = manifestResult.assets.size - iconCount - imageCount

        logProgress(
            AnsiColor.GREEN,
            "SliceAssets",
            "切图完成: ${manifestResult.assets.size} 个媒体文件 (图标: $iconCount, 图片: $imageCount${if (otherCount > 0) ", 其他: $otherCount" else ""})"
        )

        storage.set(mediaManifestKey, manifestResult)
        manifestResult
    }

    // ============================================
    // Phase 1.6: Review Slicing Result (Media Assets Manifest)
    // ============================================
    val reviewSlicing by node<MediaAssetsManifest, MediaAssetsReviewResult>(
        name = NodeNames.REVIEW_SLICING
    ) { manifest ->
        // Optional slicing review: default disabled
        val slicingReviewEnabled = parameters.parameter("enable_slicing_review", false)
        if (!slicingReviewEnabled) {
            logProgress(AnsiColor.YELLOW, "SlicingReview", "已按配置跳过切图评审（默认关闭）。")
            val skipped = MediaAssetsReviewResult(
                qualityScore = 100,
                approved = true,
                complete = true,
                missingAssets = emptyList(),
                summary = "Slicing review skipped by config (enable_slicing_review=false)"
            )
            storage.set(slicingReviewResultKey, skipped)
            return@node skipped
        }

        val currentRetries = storage.get(slicingReviewRetryCountKey) ?: 0
        val maxRetries =
            parameters.parameter("slicing_review_max_retries", IOSGenConstants.DEFAULT_SLICING_REVIEW_MAX_RETRIES)
        logProgress(AnsiColor.CYAN, "SlicingReview", "开始切图结果评审（第 ${currentRetries + 1}/$maxRetries 次）...")

        val (_, result) = buildAndRunStructureAgent<String, MediaAssetsReviewResult>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = """
                You are an expert iOS visual asset reviewer.
                Review the assets manifest for completeness and production readiness.
            """.trimIndent(),
            input = createMediaAssetsReviewPrompt(projectProperties, manifest),
            emptyValue = MediaAssetsReviewResult(
                qualityScore = 0,
                approved = false,
                complete = false,
                summary = "Media assets review failed"
            ),
            llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.reviewDesign)
                .toLLM().toLLModel(),
            attachments = emptyList(),
            examples = listOf(
                exampleMediaAssetsReviewResult_Pass,
                exampleMediaAssetsReviewResult_FailMissing,
                exampleMediaAssetsReviewResult_PassWithNotes
            )
        )
        val slicingResult = result ?: error("Media assets review returned null result")

        val statusIcon = if (slicingResult.approved) "✅" else "⚠️"
        val completenessIcon = if (slicingResult.complete) "✓" else "✗"
        logProgress(
            if (slicingResult.approved) AnsiColor.GREEN else AnsiColor.YELLOW,
            "SlicingReview",
            "质量评分: ${slicingResult.qualityScore}/100 | 完整性: $completenessIcon | 状态: $statusIcon ${if (slicingResult.approved) "通过" else "需要改进"}"
        )
        if (!slicingResult.approved && slicingResult.missingAssets.isNotEmpty()) {
            logProgress(AnsiColor.YELLOW, "SlicingReview", "缺失/问题资产 ${slicingResult.missingAssets.size} 项（示例）：")
            slicingResult.missingAssets.take(5).forEach { printlnColor(AnsiColor.CYAN, "  • $it") }
        } else if (slicingResult.approved) {
            logProgress(AnsiColor.GREEN, "SlicingReview", "✅ 切图评审通过！")
        }

        storage.set(slicingReviewResultKey, slicingResult)
        slicingResult
    }

    // Get existing project structure after initialization
    val getProjectFiles by node<Unit, IOSAppProjectFiles>(
        name = NodeNames.GET_PROJECT_FILES
    ) {
        logProgress(AnsiColor.GREEN, "GetProjectFiles", "读取已存在的项目结构并生成剩余文件清单…")

        val projectRoot = File(projectProperties.projectRoot ?: ".")
        val existingStructure = readExistingProjectStructure(projectRoot)

        logProgress(AnsiColor.CYAN, "GetProjectFiles", "当前项目结构已读取，开始向 LLM 请求生成剩余文件清单")

        // Ask LLM to generate the list of additional files needed using the structure agent
        val systemPrompt = """
            You are an expert iOS project planner and repository architect.
            Based on the existing project directory structure and app requirements, generate a COMPLETE list of remaining project files and directories that must be created next.

            RULES:
            - Return ONLY source code files and project configuration files (e.g., Podfile for native iOS).
            - Do NOT include any binary/media assets (icons, images); those are handled in the SliceAssets phase.
            - Paths must be absolute or relative to ${projectProperties.projectRoot}.
            - Each file should include a short description of its purpose.
        """.trimIndent()

        val (_, agentLayout) = buildAndRunStructureAgent<String, IOSAppProjectFiles>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = systemPrompt,
            input = createProjectFilesPrompt(projectProperties, existingStructure),
            emptyValue = IOSAppProjectFiles(emptyList()),
            llmModel = llModelGroupConfig
                .resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.generateFileList)
                .toLLM().toLLModel(),
            attachments = projectProperties.designFiles,
            examples = listOf(
                exampleIOSAppProjectFiles_NativeSwiftUI,
                exampleIOSAppProjectFiles_Flutter,
                exampleIOSAppProjectFiles_ReactNative
            )
        )

        val layout = filterInvalidFiles(agentLayout ?: error("Project file list generation returned null"))
        val filteredCount = agentLayout.files.size - layout.files.size

        if (filteredCount > 0) {
            logProgress(AnsiColor.YELLOW, "GetProjectFiles", "已过滤 $filteredCount 个无效文件条目")
        }

        // NOTE: Media files are handled separately in SliceAssets phase
        // This node should only generate SOURCE CODE files
        val mediaFileCount = layout.files.count { !it.isDir && it.isMedia() }

        if (mediaFileCount > 0) {
            logProgress(
                AnsiColor.YELLOW,
                "GetProjectFiles",
                "⚠️ 警告：文件清单中包含 $mediaFileCount 个媒体文件，但媒体资源应该在 SliceAssets 阶段处理。这些文件将被保留但可能与媒体清单重复。"
            )
        }

        // CRITICAL: Ensure Podfile is always included for Native iOS projects
        val finalLayout =
            if (projectProperties.implementTechnology == IOSAppProjectProperties.ImplementTechnology.Native) {
                val hasPodfile = layout.files.any { it.name.equals("Podfile", ignoreCase = true) }
                if (!hasPodfile) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "GetProjectFiles",
                        "⚠️  文件清单中缺少 Podfile，将自动添加（对现代 iOS 开发至关重要）"
                    )

                    // Use a relative path; sanitizePath will place it under projectRoot
                    val podfilePath = "Podfile"
                    val podfileDescription = if (projectProperties.thirdPartyDependencies.isNotEmpty()) {
                        """
                    CocoaPods Podfile for dependency management.
                    Required dependencies: ${projectProperties.thirdPartyDependencies.joinToString(", ")}
                    Deployment target: ${projectProperties.deploymentTarget}
                    """.trimIndent()
                    } else {
                        """
                    CocoaPods Podfile for dependency management.
                    Deployment target: ${projectProperties.deploymentTarget}
                    Ready for adding third-party libraries.
                    """.trimIndent()
                    }

                    val podfile = IOSAppProjectFile(
                        name = "Podfile",
                        path = podfilePath,
                        isDir = false,
                        mime = "text/plain",
                        description = podfileDescription
                    )

                    IOSAppProjectFiles(layout.files + podfile)
                } else {
                    layout
                }
            } else {
                layout
            }

        val finalFileCount = finalLayout.files.size
        val finalDirCount = finalLayout.files.count { it.isDir }
        val finalSourceCount = finalLayout.files.count { !it.isDir && !it.isMedia() }
        val finalMediaCount = finalLayout.files.count { !it.isDir && it.isMedia() }

        logProgress(
            AnsiColor.GREEN,
            "GetProjectFiles",
            "文件清单生成完成：总计 $finalFileCount 个条目（目录 $finalDirCount，源码 $finalSourceCount，媒体 $finalMediaCount）"
        )

        // Store the layout for later use
        storage.set(layoutKey, finalLayout)
        finalLayout
    }

    // ============================================
    // Phase 2: Design Architecture with Media Integration
    // ============================================
    val designArchitecture by node<MediaAssetsManifest, ProjectArchitecture>(
        name = NodeNames.DESIGN_ARCHITECTURE
    ) { mediaManifest ->
        val layout = storage.get(layoutKey) ?: error("Project layout not found in storage")

        // Check if this is a redesign based on review feedback
        val previousReview =
            storage.get(architectureReviewResultKey)
        val previousArchitecture = storage.get(architectureKey)
        val isRedesign = previousReview != null && !previousReview.approved && previousArchitecture != null

        if (isRedesign) {
            val retryCount = storage.get(architectureReviewRetryCountKey) ?: 0
            logProgress(
                AnsiColor.YELLOW,
                "ArchitectureRedesign",
                "基于评审反馈重新设计架构（第 $retryCount 次重试）..."
            )
        } else {
            logProgress(
                AnsiColor.CYAN,
                "ArchitectureDesign",
                "基于文件清单和媒体切图(${mediaManifest.assets.size} 个资源)设计架构..."
            )
        }

        // Choose prompt based on whether this is initial design or redesign
        val prompt = if (isRedesign) {
            createArchitectureRedesignPromptWithFeedback(
                projectProperties,
                layout,
                mediaManifest,
                previousArchitecture,
                previousReview
            )
        } else {
            createArchitectureDesignPromptWithMedia(projectProperties, layout, mediaManifest)
        }

        val systemPrompt = if (isRedesign) {
            """
                You are a senior iOS architect REDESIGNING the architecture based on quality review feedback.
                Your previous design scored ${previousReview.qualityScore}/100 and was NOT APPROVED.

                CRITICAL TASK:
                - Fix ALL the issues identified in the review feedback
                - Ensure your redesign scores >= ${IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD} to pass the next review
                - Focus on the specific problems mentioned, not cosmetic changes

                TOKEN OPTIMIZATION:
                - You MAY return ONLY the files that need changes (partial architecture)
                - Unchanged files will be automatically merged from the previous architecture
                - If you return the full architecture, that's also acceptable
            """.trimIndent()
        } else {
            """
                You are a senior iOS architect with expertise in SwiftUI, UIKit, Flutter, React Native.
                Design a complete software architecture including:
                - File-level function signatures
                - Data structures and models
                - Dependency relationships between files
                - Global architectural principles
                - How to integrate the provided media assets

                CRITICAL: Media assets are ALREADY defined in the manifest.
                Your job is to design SOURCE CODE that uses these assets correctly.
            """.trimIndent()
        }

        val (_, rawArchitecture) = buildAndRunStructureAgent<String, ProjectArchitecture>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = systemPrompt,
            input = prompt,
            emptyValue = ProjectArchitecture(),
            llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.designArchitecture)
                .toLLM().toLLModel(),
            attachments = emptyList(),
            examples = listOf(
                exampleProjectArchitecture_SwiftUI,
                exampleProjectArchitecture_Flutter,
                exampleProjectArchitecture_ReactNative
            )
        )

        // Merge architecture if this is a redesign with partial results
        val architecture = if (isRedesign && (rawArchitecture ?: error("Architecture generation returned null")).fileArchitectures.isNotEmpty()) {
            // Check if LLM provided an updated file list (e.g., adding new files like ViewModels)
            if (rawArchitecture.updatedFileList != null) {
                val newLayout = rawArchitecture.updatedFileList
                val oldLayoutSize = layout.files.size
                val newLayoutSize = newLayout.files.size
                val newSourceCount = newLayout.files.count { !it.isDir && !it.isMedia() }

                logProgress(
                    AnsiColor.CYAN,
                    "ArchitectureRedesign",
                    "📝 文件清单已更新：从 $oldLayoutSize 个条目增加到 $newLayoutSize 个条目（新增 ${newLayoutSize - oldLayoutSize} 个）"
                )

                // Update the layout in storage
                storage.set(layoutKey, newLayout)

                // Validate completeness: when updatedFileList is provided, architecture should be complete
                val archCount = rawArchitecture.fileArchitectures.size
                if (archCount < newSourceCount) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "ArchitectureRedesign",
                        "⚠️ 警告：提供了新文件清单但架构不完整。预期 $newSourceCount 个源码文件，实际 $archCount 个架构定义，缺失 ${newSourceCount - archCount} 个"
                    )
                    // Fall back to merge mode to preserve existing architectures
                    val mergedFileArchitectures = mergeFileArchitectures(
                        previous = previousArchitecture.fileArchitectures,
                        updated = rawArchitecture.fileArchitectures
                    )
                    logProgress(
                        AnsiColor.CYAN,
                        "ArchitectureRedesign",
                        "已自动合并架构：${rawArchitecture.fileArchitectures.size} 个新定义 + ${mergedFileArchitectures.size - rawArchitecture.fileArchitectures.size} 个保留的旧定义"
                    )
                    ProjectArchitecture(
                        fileArchitectures = mergedFileArchitectures,
                        globalPrinciples = rawArchitecture.globalPrinciples.ifEmpty { previousArchitecture.globalPrinciples },
                        namingConventions = rawArchitecture.namingConventions.ifEmpty { previousArchitecture.namingConventions },
                        codePatterns = rawArchitecture.codePatterns.ifEmpty { previousArchitecture.codePatterns },
                        errorHandlingStrategy = rawArchitecture.errorHandlingStrategy.ifEmpty { previousArchitecture.errorHandlingStrategy },
                        typeSystemConventions = rawArchitecture.typeSystemConventions.ifEmpty { previousArchitecture.typeSystemConventions }
                    )
                } else {
                    // Architecture is complete - use as-is
                    logProgress(
                        AnsiColor.GREEN,
                        "ArchitectureRedesign",
                        "使用新文件清单的完整架构定义（${rawArchitecture.fileArchitectures.size} 个文件）"
                    )
                    rawArchitecture
                }
            } else {
                // Standard incremental merge - no new files added
                val mergedFileArchitectures = mergeFileArchitectures(
                    previous = previousArchitecture.fileArchitectures,
                    updated = rawArchitecture.fileArchitectures
                )

                val filesChanged = rawArchitecture.fileArchitectures.size
                val filesUnchanged = mergedFileArchitectures.size - filesChanged

                logProgress(
                    AnsiColor.CYAN,
                    "ArchitectureRedesign",
                    "架构合并：${filesChanged} 个文件已修改，${filesUnchanged} 个文件保持不变"
                )

                // Merge with updated global principles (use new ones if provided, otherwise keep old)
                ProjectArchitecture(
                    fileArchitectures = mergedFileArchitectures,
                    globalPrinciples = rawArchitecture.globalPrinciples.ifEmpty { previousArchitecture.globalPrinciples },
                    namingConventions = rawArchitecture.namingConventions.ifEmpty { previousArchitecture.namingConventions },
                    codePatterns = rawArchitecture.codePatterns.ifEmpty { previousArchitecture.codePatterns },
                    errorHandlingStrategy = rawArchitecture.errorHandlingStrategy.ifEmpty { previousArchitecture.errorHandlingStrategy },
                    typeSystemConventions = rawArchitecture.typeSystemConventions.ifEmpty { previousArchitecture.typeSystemConventions }
                )
            }
        } else {
            rawArchitecture
        }

        // Validate architecture completeness
        // Use updated layout if it was changed during redesign
        val currentLayout = storage.get(layoutKey) ?: error("Project layout not found in storage")
        val totalFiles = currentLayout.files.size
        val dirCount = currentLayout.files.count { it.isDir }
        val mediaCount = currentLayout.files.count { it.isMedia() }
        val sourceFileCount = currentLayout.files.count { !it.isDir && !it.isMedia() }
        val architectureResult = architecture ?: error("Architecture generation returned null")
        val architectureFileCount = architectureResult.fileArchitectures.size

        // Debug logging
        logProgress(
            AnsiColor.CYAN,
            "ArchitectureValidation",
            "文件统计：总计 $totalFiles 个（目录 $dirCount，媒体 $mediaCount，源码 $sourceFileCount），架构定义 $architectureFileCount 个"
        )

        if (architectureResult.fileArchitectures.isEmpty()) {
            logProgress(
                AnsiColor.RED,
                "ArchitectureDesign",
                "❌ 错误：架构设计返回了 0 个文件定义。这是不可接受的。"
            )
        } else if (!isRedesign && architectureFileCount < sourceFileCount) {
            // For initial design, warn if some files are missing
            val missingCount = sourceFileCount - architectureFileCount
            logProgress(
                AnsiColor.YELLOW,
                "ArchitectureDesign",
                "⚠️  警告：架构设计不完整。预期 $sourceFileCount 个源码文件，实际返回 $architectureFileCount 个，缺失 $missingCount 个"
            )

            // Identify which files are missing
            val architecturedPaths = architectureResult.fileArchitectures.map { normalizeFilePath(it.filePath) }.toSet()
            val missingFiles = currentLayout.files
                .filter { !it.isDir && !it.isMedia() }
                .filter { !architecturedPaths.contains(normalizeFilePath(it.path)) }

            if (missingFiles.isNotEmpty()) {
                printlnColor(AnsiColor.YELLOW, "缺失架构定义的文件：")
                missingFiles.take(10).forEach { file ->
                    printlnColor(AnsiColor.YELLOW, "  • ${file.path}")
                }
                if (missingFiles.size > 10) {
                    printlnColor(AnsiColor.YELLOW, "  ... 还有 ${missingFiles.size - 10} 个文件")
                }
            }

            logProgress(
                AnsiColor.CYAN,
                "ArchitectureDesign",
                "架构设计完成（不完整）：${architectureResult.fileArchitectures.size} 个源码文件，整合 ${mediaManifest.assets.size} 个媒体资源"
            )
        } else {
            val designType = if (isRedesign) "重新设计" else "设计"
            val completenessNote = if (!isRedesign && architectureFileCount == sourceFileCount) {
                "（完整 ✓）"
            } else if (isRedesign) {
                "（增量更新）"
            } else {
                ""
            }

            logProgress(
                AnsiColor.GREEN,
                "ArchitectureDesign",
                "架构${designType}完成$completenessNote：${architecture.fileArchitectures.size} 个源码文件，整合 ${mediaManifest.assets.size} 个媒体资源"
            )
        }

        storage.set(architectureKey, architecture)
        architecture
    }

    // ============================================
    // Phase 2.5: Unified Architecture Review (Quality + Completeness)
    // ============================================
    val reviewArchitecture by node<ProjectArchitecture, IOSAppProjectFiles>(
        name = NodeNames.REVIEW_ARCHITECTURE
    ) { architecture ->
        // Skip review if architecture is empty
        if (architecture.fileArchitectures.isEmpty()) {
            logProgress(AnsiColor.YELLOW, "ArchitectureReview", "架构为空，跳过评审")
            return@node storage.get(layoutKey) ?: IOSAppProjectFiles(emptyList())
        }

        val layout = storage.get(layoutKey) ?: IOSAppProjectFiles(emptyList())
        val mediaManifest = storage.get(mediaManifestKey)
            ?: MediaAssetsManifest(assets = emptyList())
        val currentRetries = storage.get(architectureReviewRetryCountKey) ?: 0
        val maxRetries = parameters.parameter(
            "architecture_review_max_retries",
            IOSGenConstants.DEFAULT_ARCHITECTURE_REVIEW_MAX_RETRIES
        )

        logProgress(AnsiColor.CYAN, "ArchitectureReview", "开始统一架构评审（质量+完整性）...")

        // Helper function to merge missing files into layout
        fun mergeFiles(layout: IOSAppProjectFiles, missing: List<IOSAppProjectFile>): IOSAppProjectFiles {
            if (missing.isEmpty()) return layout
            // Preserve original order; append truly missing files in a deterministic order
            val existingKeys = layout.files.map { normalizeFilePath(it.path) }.toMutableSet()
            val additions = missing
                .filter { normalizeFilePath(it.path) !in existingKeys }
                .sortedBy { it.path.replace('\\', '/').lowercase() }
            return if (additions.isEmpty()) layout else IOSAppProjectFiles(layout.files + additions)
        }

        val (_, review) = buildAndRunStructureAgent<String, UnifiedArchitectureReview>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = """
                You are a SENIOR iOS architect with 10+ years of experience.
                Perform a COMPREHENSIVE review covering BOTH architecture quality AND functional completeness.
                Be strict but fair to ensure production-ready, complete applications.
            """.trimIndent(),
            input = createUnifiedArchitectureReviewPrompt(
                projectProperties,
                layout,
                architecture,
                mediaManifest,
                getProjectStructureSummary(File(projectProperties.projectRoot ?: ".").absoluteFile)
            ),
            emptyValue = UnifiedArchitectureReview(
                qualityScore = 0,
                approved = false,
                complete = false,
                summary = "Review failed"
            ),
            llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.reviewArchitecture)
                .toLLM().toLLModel(),
            attachments = emptyList(),
            examples = listOf(
                exampleUnifiedArchitectureReview_Pass,
                exampleUnifiedArchitectureReview_FailMissing,
                exampleUnifiedArchitectureReview_PassWithWarnings
            )
        )

        // Filter out 'missing files' that already exist locally
        val projectRoot = File(projectProperties.projectRoot ?: ".").absoluteFile
        val existingPaths = buildExistingPathSet(projectRoot)
        val reviewResult = review ?: error("Architecture review returned null")
        val filteredMissingFiles = reviewResult.missingFiles.filter { mf ->
            val rel = toRelativeUnder(projectRoot, mf.path)
            val normalized = normalizeFilePath(rel.ifBlank { mf.path })
            normalized !in existingPaths
        }
        val falseMissingCount = reviewResult.missingFiles.size - filteredMissingFiles.size
        if (falseMissingCount > 0) {
            logProgress(
                AnsiColor.CYAN,
                "ArchitectureReview",
                "已忽略 $falseMissingCount 个被误报为缺失但本地已存在的文件/目录（例如 xcodegen 生成的工程文件）"
            )
        }
        val effectiveComplete =
            if (!reviewResult.complete && reviewResult.missingFiles.isNotEmpty() && filteredMissingFiles.isEmpty()) {
                true
            } else reviewResult.complete
        val qualityThreshold = IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD
        val effectiveApproved = (reviewResult.qualityScore >= qualityThreshold) && effectiveComplete
        val finalReview = reviewResult.copy(
            missingFiles = filteredMissingFiles,
            complete = effectiveComplete,
            approved = effectiveApproved
        )

        // Log review results
        val statusIcon = if (finalReview.approved) "✅" else "⚠️"
        val completenessIcon = if (finalReview.complete) "✓" else "✗"
        logProgress(
            if (finalReview.approved) AnsiColor.GREEN else AnsiColor.YELLOW,
            "ArchitectureReview",
            "质量评分: ${finalReview.qualityScore}/100 | 完整性: $completenessIcon | 状态: $statusIcon ${if (finalReview.approved) "通过" else "需要改进"}"
        )

        // Log design issues
        if (reviewResult.designIssues.isNotEmpty()) {
            val criticalCount = reviewResult.designIssues.count { it.severity == "critical" }
            val warningCount = reviewResult.designIssues.count { it.severity == "warning" }
            val infoCount = reviewResult.designIssues.count { it.severity == "info" }

            logProgress(
                AnsiColor.YELLOW,
                "ArchitectureReview",
                "发现问题: $criticalCount 个严重, $warningCount 个警告, $infoCount 个提示"
            )

            reviewResult.designIssues.take(10).forEach { issue ->
                val color = when (issue.severity) {
                    "critical" -> AnsiColor.RED
                    "warning" -> AnsiColor.YELLOW
                    else -> AnsiColor.CYAN
                }
                printlnColor(color, "  [${issue.severity.uppercase()}] ${issue.category}: ${issue.description}")
                if (issue.affectedFiles.isNotEmpty()) {
                    printlnColor(color, "    影响文件: ${issue.affectedFiles.joinToString(", ")}")
                }
                if (issue.suggestedFix.isNotEmpty()) {
                    printlnColor(AnsiColor.CYAN, "    建议修复: ${issue.suggestedFix}")
                }
            }
            if (reviewResult.designIssues.size > 10) {
                printlnColor(AnsiColor.YELLOW, "  ... 还有 ${reviewResult.designIssues.size - 10} 个问题")
            }
        }

        // Log missing files
        if (finalReview.missingFiles.isNotEmpty()) {
            logProgress(
                AnsiColor.YELLOW,
                "ArchitectureReview",
                "缺失文件: ${finalReview.missingFiles.size} 个，将合并到文件清单"
            )
            finalReview.missingFiles.take(5).forEach { file ->
                printlnColor(AnsiColor.CYAN, "  • ${file.path}: ${file.description}")
            }
            if (finalReview.missingFiles.size > 5) {
                printlnColor(AnsiColor.CYAN, "  ... 还有 ${finalReview.missingFiles.size - 5} 个文件")
            }
        }

        // Log recommendations
        if (reviewResult.recommendations.isNotEmpty()) {
            logProgress(AnsiColor.CYAN, "ArchitectureReview", "改进建议:")
            reviewResult.recommendations.take(5).forEach { recommendation ->
                printlnColor(AnsiColor.CYAN, "  • $recommendation")
            }
        }

        logProgress(AnsiColor.CYAN, "ArchitectureReview", "评审总结: ${finalReview.summary}")

        // Merge missing files into layout
        val mergedLayout = mergeFiles(layout, finalReview.missingFiles)
        storage.set(layoutKey, mergedLayout)

        // Store review result for potential redesign
        storage.set(architectureReviewResultKey, finalReview)

        // Determine if retry is needed
        if (!finalReview.approved) {
            logProgress(
                AnsiColor.YELLOW,
                "ArchitectureReview",
                "⚠️  架构评审未通过（评分 < ${IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD} 或存在缺失文件），当前重试: $currentRetries/$maxRetries"
            )
        } else {
            logProgress(AnsiColor.GREEN, "ArchitectureReview", "✅ 架构评审通过！质量和完整性均符合要求")
        }

        mergedLayout
    }

    // ============================================
    // Phase 3: Setup (removed evaluateArchitectureCompleteness - now unified in reviewArchitecture)
    // ============================================

    // Ask requirements at the beginning, before any project initialization
    val askRequirementsIfNeeded by toolCycleGraph(
        name = NodeNames.ASK_REQUIREMENTS,
        tools = listOf(ExitTool) + TerminalInteractiveTools().asTools(),
        inputPrompt = { initialProps: IOSAppProjectProperties, agentContext ->
            // Store initial properties for reference
            agentContext.storage.set(
                finalProjectPropertiesKey,
                initialProps
            )
            logProgress(AnsiColor.CYAN, "Requirements", "询问用户需求，确认项目属性...")
            createInitialRequirementsPrompt(initialProps)
        },
        outputCompute = { _, agentContext ->
            // Return the final project properties (may have been updated during the conversation)
            val finalProps =
                agentContext.storage.get(finalProjectPropertiesKey) ?: error("Final project properties not found in storage")
            logProgress(AnsiColor.CYAN, "Requirements", "需求确认完成，开始项目开发")
            finalProps
        }
    )

    // Setup node - prepares files for batch processing, separating source and media files
    val setupNode by node<IOSAppProjectFiles, Unit>(
        name = NodeNames.SETUP_NODE
    ) { layout ->
        logProgress(AnsiColor.BLUE, "Setup", "开始准备目录与任务列表…")

        val allFiles = collectFilesFromLayout(layout, projectProperties)
        val (sourceFiles, _) = splitSourceAndMedia(allFiles)

        val batchSize =
            parameters.parameter("file_generation_batch_size", IOSGenConstants.DEFAULT_FILE_GENERATION_BATCH_SIZE)
        val architecture = storage.get(architectureKey)
        val sourceFileBatches = groupFilesByDependency(sourceFiles, architecture, batchSize)

        // Store batches for later processing (media files will be collected from manifest in COLLECT_MEDIA_FILES)
        storage.set(sourceFileBatchesKey, sourceFileBatches)
        storage.set(currentBatchIndexKey, 0)
        storage.set(totalFilesKey, allFiles.size)
        // Persist the full file list for later use by retries and compilation checks
        storage.set(filesKey, IOSAppProjectFiles(allFiles))

        // Get media manifest count for accurate reporting
        val mediaManifest = storage.get(mediaManifestKey)
        val mediaAssetCount = mediaManifest?.assets?.size ?: 0

        logProgress(
            AnsiColor.BLUE,
            "Setup",
            "任务准备完成：源码文件 ${sourceFiles.size} 个（分为 ${sourceFileBatches.size} 批），" +
                    "媒体文件 $mediaAssetCount 个（来自切图阶段）"
        )
    }


    // Retrieval task - fetches the next batch of source files to process
    val retrievalTask by node<Unit, List<IOSAppProjectFile>>(
        name = NodeNames.RETRIEVAL_TASK
    ) {
        val batches =
            storage.get(sourceFileBatchesKey) ?: emptyList()
        val currentIndex = storage.get(currentBatchIndexKey) ?: 0

        if (currentIndex >= batches.size) {
            logProgress(AnsiColor.GREEN, "Progress", "所有源码文件批次已完成，进入媒体文件生成阶段…")
            emptyList()
        } else {
            val batch = batches[currentIndex]
            storage.set(currentBatchIndexKey, currentIndex + 1)

            val totalBatches = batches.size
            val percent = if (totalBatches == 0) 100 else ((currentIndex + 1) * 100 / totalBatches)
            logProgress(
                AnsiColor.CYAN_B,
                "Progress",
                "开始处理第 ${currentIndex + 1}/$totalBatches 批（${percent}%），包含 ${batch.size} 个文件"
            )

            batch
        }
    }

    // Parallel source file generation - uses Sub Agents to generate source code files in parallel
    val generateSourceFilesParallel by node<List<IOSAppProjectFile>, String>(
        name = NodeNames.GENERATE_SOURCE_FILES_PARALLEL
    ) { batch ->
        if (batch.isEmpty()) {
            logProgress(AnsiColor.GREEN, "Generate", "当前批次为空，跳过")
            return@node "Empty batch, skipped"
        }
        val architecture = storage.get(architectureKey)
        val parallelismRaw =
            parameters.parameter("file_generation_parallelism", IOSGenConstants.DEFAULT_FILE_GENERATION_PARALLELISM)
        val parallelism = if (parallelismRaw <= 0) 1 else parallelismRaw

        val result = handleGenerateSourceFilesBatch(
            batch = batch,
            architecture = architecture,
            parallelism = parallelism,
            httpAccess = httpAccess,
            parameters = parameters,
            designSpec = storage.get(designSpecKey),
            mediaManifest = storage.get(mediaManifestKey)
        )

        // Track failed files after generation
        val failureTracker = storage.get(fileGenerationFailuresKey) ?: FileGenerationFailures()

        val failedFiles = batch.filter { file ->
            !File(file.path).exists() || File(file.path).length() == 0L
        }

        // Record failures and attempt counts
        failedFiles.forEach { file ->
            failureTracker.recordFailure(file.path)
            val retryCount = failureTracker.getRetryCount(file.path)
            val fileArch = findFileArchitecture(architecture?.fileArchitectures, file.path)
            val isCritical = fileArch?.isCritical ?: false

            logProgress(
                if (isCritical) AnsiColor.RED else AnsiColor.YELLOW,
                "Generate",
                "文件生成失败${if (isCritical) " [关键文件]" else ""}: ${file.name} (重试 $retryCount 次)"
            )
        }

        // Clear failure records for files that succeeded in this batch
        val succeededFiles = batch.filterNot { failedFiles.contains(it) }
        if (succeededFiles.isNotEmpty()) {
            succeededFiles.forEach { file ->
                if (failureTracker.getRetryCount(file.path) > 0) {
                    failureTracker.clearFile(file.path)
                    logProgress(
                        AnsiColor.GREEN,
                        "RetryCheck",
                        "文件已生成，移除重试队列: ${file.name}"
                    )
                }
            }
        }

        storage.set(fileGenerationFailuresKey, failureTracker)

        // Track successful files for observability
        val successFiles = succeededFiles.map { it.path }
        val existingSuccess = storage.get(successfulFilesKey) ?: mutableListOf()
        val updatedSuccess = (existingSuccess + successFiles).distinct().toMutableList()
        storage.set(successfulFilesKey, updatedSuccess)

        val successCount = batch.size - failedFiles.size
        logProgress(
            AnsiColor.GREEN,
            "Generate",
            "批次生成流程完成 (成功: $successCount/${batch.size})"
        )

        result
    }

    // Check and retry failed source files
    val checkAndRetryFailedSourceFiles by node<List<IOSAppProjectFile>, List<IOSAppProjectFile>>(
        name = NodeNames.CHECK_AND_RETRY_FAILED_SOURCE_FILES
    ) {
        val failureTracker = storage.get(fileGenerationFailuresKey)
            ?: FileGenerationFailures()

        val failedFilesList = failureTracker.getFailedFiles()
        if (failedFilesList.isEmpty()) {
            logProgress(AnsiColor.GREEN, "RetryCheck", "所有源码文件生成成功，无需重试")
            return@node emptyList()
        }

        val maxRetries =
            parameters.parameter("source_file_max_retries", IOSGenConstants.DEFAULT_SOURCE_FILE_MAX_RETRIES)
        val architecture = storage.get(architectureKey)

        // Separate files by retry status
        val filesToRetry = mutableListOf<IOSAppProjectFile>()
        val criticalFailures = mutableListOf<String>()

        failedFilesList.forEach { filePath ->
            // Guard against stale entries: if file now exists and is non-empty, clear and skip
            val fileObj = File(filePath)
            if (fileObj.exists() && fileObj.length() > 0L) {
                failureTracker.clearFile(filePath)
                logProgress(AnsiColor.GREEN, "RetryCheck", "文件已生成，无需重试: ${fileObj.name}")
                return@forEach
            }

            val retryCount = failureTracker.getRetryCount(filePath)
            val fileArch = findFileArchitecture(architecture?.fileArchitectures, filePath)
            val isCritical = fileArch?.isCritical ?: false

            if (retryCount < maxRetries) {
                // Can still retry
                val allFiles = storage.get(filesKey)
                val fileToRetry = allFiles?.files?.find { it.path == filePath }
                if (fileToRetry != null) {
                    filesToRetry.add(fileToRetry)
                    logProgress(
                        if (isCritical) AnsiColor.YELLOW else AnsiColor.CYAN,
                        "RetryCheck",
                        "准备重试${if (isCritical) " [关键文件]" else ""}: ${fileToRetry.name} (第 ${retryCount + 1}/$maxRetries 次)"
                    )
                }
            } else {
                // Max retries reached
                if (isCritical) {
                    criticalFailures.add(filePath)
                }
                logProgress(
                    if (isCritical) AnsiColor.RED else AnsiColor.YELLOW,
                    "RetryCheck",
                    "文件达到最大重试次数${if (isCritical) " [关键文件]" else ""}: $filePath"
                )
            }
        }

        // Persist any updates to the failure tracker (e.g., cleared entries)
        storage.set(fileGenerationFailuresKey, failureTracker)

        // Check if there are critical file failures
        if (criticalFailures.isNotEmpty()) {
            val criticalFileNames = criticalFailures.map { File(it).name }
            logProgress(
                AnsiColor.RED,
                "RetryCheck",
                "❌ ${criticalFailures.size} 个关键文件生成失败，停止流程"
            )
            criticalFailures.forEach { path ->
                printlnColor(AnsiColor.RED, "  • $path")
            }
            throw IllegalStateException(
                "Critical files failed to generate after $maxRetries retries: ${criticalFileNames.joinToString(", ")}"
            )
        }

        if (filesToRetry.isNotEmpty()) {
            logProgress(AnsiColor.CYAN, "RetryCheck", "将重试 ${filesToRetry.size} 个文件")
        }

        filesToRetry
    }

    // Retry failed source files
    val retryFailedSourceFiles by node<List<IOSAppProjectFile>, String>(
        name = NodeNames.RETRY_FAILED_SOURCE_FILES
    ) { filesToRetry ->
        if (filesToRetry.isEmpty()) {
            return@node "No files to retry"
        }

        val architecture = storage.get(architectureKey)
        val parallelismRaw =
            parameters.parameter("file_generation_parallelism", IOSGenConstants.DEFAULT_FILE_GENERATION_PARALLELISM)
        val parallelism = if (parallelismRaw <= 0) 1 else parallelismRaw

        logProgress(
            AnsiColor.CYAN,
            "Retry",
            "开始重试 ${filesToRetry.size} 个失败的源码文件"
        )

        val result = handleGenerateSourceFilesBatch(
            batch = filesToRetry,
            architecture = architecture,
            parallelism = parallelism,
            httpAccess = httpAccess,
            parameters = parameters,
            designSpec = storage.get(designSpecKey),
            mediaManifest = storage.get(mediaManifestKey)
        )

        // After retry attempt, update failure tracker just like initial generation
        val failureTracker = storage.get(fileGenerationFailuresKey)
            ?: FileGenerationFailures()

        val failedFiles = filesToRetry.filter { file ->
            !File(file.path).exists() || File(file.path).length() == 0L
        }

        // Record failures and attempt counts for files still failing after retry
        failedFiles.forEach { file ->
            failureTracker.recordFailure(file.path)
            val retryCount = failureTracker.getRetryCount(file.path)
            val fileArch = findFileArchitecture(architecture?.fileArchitectures, file.path)
            val isCritical = fileArch?.isCritical ?: false

            logProgress(
                if (isCritical) AnsiColor.RED else AnsiColor.YELLOW,
                "Retry",
                "文件重试后仍然失败${if (isCritical) " [关键文件]" else ""}: ${file.name} (重试 $retryCount 次)"
            )
        }

        // Clear failure records for files that succeeded in this retry batch
        val succeededFiles = filesToRetry.filterNot { failedFiles.contains(it) }
        if (succeededFiles.isNotEmpty()) {
            succeededFiles.forEach { file ->
                if (failureTracker.getRetryCount(file.path) > 0) {
                    failureTracker.clearFile(file.path)
                    logProgress(
                        AnsiColor.GREEN,
                        "RetryCheck",
                        "文件已生成，移除重试队列: ${file.name}"
                    )
                }
            }
        }

        storage.set(fileGenerationFailuresKey, failureTracker)

        val successCount = filesToRetry.size - failedFiles.size
        logProgress(
            AnsiColor.GREEN,
            "Retry",
            "重试批次完成 (成功: $successCount/${filesToRetry.size})"
        )

        result
    }

    // Collect media files - converts MediaAssetsManifest to IOSAppProjectFile list for generation
    val collectMediaFiles by node<String, List<IOSAppProjectFile>>(
        name = NodeNames.COLLECT_MEDIA_FILES
    ) {
        val mediaManifest = storage.get(mediaManifestKey)
            ?: MediaAssetsManifest(assets = emptyList())

        // Convert MediaAssetSpec to IOSAppProjectFile with enhanced descriptions
        val mediaFiles = mediaManifest.assets.map { assetSpec ->
            // Sanitize path to ensure it's relative to project root
            val sanitizedPath = sanitizePath(assetSpec.path, projectProperties.projectRoot)

            IOSAppProjectFile(
                name = assetSpec.name,
                path = sanitizedPath,
                isDir = false,
                mime = when (assetSpec.type.lowercase()) {
                    "icon" -> "image/png"
                    "image" -> "image/png"
                    "video" -> "video/mp4"
                    "audio" -> "audio/mp3"
                    else -> "application/octet-stream"
                },
                description = """
                    ${assetSpec.purpose}: ${assetSpec.detailedDescription}
                    Dimensions: ${assetSpec.width}x${assetSpec.height}
                    ${assetSpec.group?.let { "Asset Group: $it" } ?: ""}
                    ${assetSpec.scale?.let { "Scale: $it" } ?: ""}
                """.trimIndent()
            )
        }

        // Store for later use
        storage.set(mediaFilesKey, mediaFiles)

        if (mediaFiles.isEmpty()) {
            logProgress(AnsiColor.GREEN, "Media", "没有媒体文件需要生成，跳过")
        } else {
            logProgress(AnsiColor.CYAN, "Media", "从媒体清单转换得到 ${mediaFiles.size} 个媒体文件，准备并行生成")
            logProgress(
                AnsiColor.CYAN,
                "Media",
                "  • App Icons: ${mediaFiles.count { it.description.contains("app_icon") }}"
            )
            logProgress(
                AnsiColor.CYAN,
                "Media",
                "  • Launch Screen: ${mediaFiles.count { it.description.contains("launch") }}"
            )
            logProgress(
                AnsiColor.CYAN,
                "Media",
                "  • UI Assets: ${mediaFiles.count { !it.description.contains("app_icon") && !it.description.contains("launch") }}"
            )
        }

        mediaFiles
    }

    // Parallel media file generation - uses Sub Agents to generate images and other media files
    val generateMediaFilesParallel by node<List<IOSAppProjectFile>, String>(
        name = NodeNames.GENERATE_MEDIA_FILES_PARALLEL
    ) { mediaFiles ->
        if (mediaFiles.isEmpty()) {
            logProgress(AnsiColor.GREEN, "Media", "没有媒体文件需要生成")
            return@node "No media files to generate"
        }

        val parallelismRaw =
            parameters.parameter("media_generation_parallelism", IOSGenConstants.DEFAULT_MEDIA_GENERATION_PARALLELISM)
        val parallelism = if (parallelismRaw <= 0) 1 else parallelismRaw
        val outcome = handleGenerateMediaFiles(
            mediaFiles = mediaFiles,
            parallelism = parallelism,
            httpAccess = httpAccess,
            projectProperties = projectProperties
        )

        if (outcome.failedFiles.isNotEmpty()) {
            storage.set(failedMediaFilesKey, outcome.failedFiles)
            storage.set(mediaRetryAttemptsKey, 0)
            logProgress(AnsiColor.YELLOW, "Media", "${outcome.failedFiles.size} 个媒体文件生成失败，将尝试重试")
            outcome.failedFiles.forEach { file -> printlnColor(AnsiColor.YELLOW, "  ❌ ${file.path}") }
        }

        logProgress(AnsiColor.CYAN, "Media", "已成功生成 ${outcome.successfulCount}/${outcome.totalCount} 个媒体文件")
        logProgress(AnsiColor.GREEN, "Media", "媒体文件生成流程完成")
        outcome.summary
    }

    // Retry failed media files - attempts to regenerate media files that failed in the initial pass
    val retryFailedMediaFiles by node<String, String>(
        name = NodeNames.RETRY_FAILED_MEDIA_FILES
    ) { _ ->
        val failedFiles = storage.get(failedMediaFilesKey)
            ?: emptyList()
        val currentAttempt = storage.get(mediaRetryAttemptsKey) ?: 0
        val maxAttempts: Int =
            parameters.parameter("media_retry_max_attempts", IOSGenConstants.DEFAULT_MEDIA_RETRY_MAX_ATTEMPTS)

        if (failedFiles.isEmpty()) {
            logProgress(AnsiColor.GREEN, "MediaRetry", "没有需要重试的媒体文件")
            return@node "No files to retry"
        }

        logProgress(
            AnsiColor.CYAN,
            "MediaRetry",
            "尝试重新生成 ${failedFiles.size} 个失败的媒体文件（尝试 ${currentAttempt + 1}/$maxAttempts）"
        )

        val stillFailed = mutableListOf<IOSAppProjectFile>()
        val nowSucceeded = mutableListOf<IOSAppProjectFile>()

        // Retry each file individually (sequential, not parallel) to reduce API load
        failedFiles.forEach { file ->
            printlnColor(AnsiColor.CYAN, "[MediaRetry] 重试生成: ${file.path}")

            try {
                // Add a small delay between retries to avoid API rate limiting
                delay(2000.milliseconds)

                // Determine if this is an image file
                val isImageFile = file.mime.lowercase().startsWith("image/")

                if (isImageFile) {
                    // Use image generation approach for images
                    val allMedia =
                        storage.get(mediaFilesKey) ?: emptyList()
                    val refPath = findExistingReferenceImageOnDisk(file, allMedia)
                    val hasDesignFiles = projectProperties.designFiles.isNotEmpty()
                    val agentContext = SubAgentContext(
                        prompt = createImageGenerationPromptWithDownload(
                            file = file,
                            projectProperties = projectProperties,
                            referenceImagePath = refPath,
                            hasDesignAttachments = hasDesignFiles
                        ),
                        name = "RetryImage_${file.name}_Attempt${currentAttempt + 1}",
                        llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.default)
                            .toLLM(),
                        attachments = if (hasDesignFiles) projectProperties.designFiles else emptyList()
                    )

                    SubAgentTools(httpAccess, parameters).runSubAgent(agentContext)

                    printlnColor(AnsiColor.CYAN, "[MediaRetry] 图像已重新生成，检查文件: ${file.path}")

                    if (validateAndRepairMedia(file)) {
                        printlnColor(AnsiColor.GREEN_B, "  ✅ 重试成功: ${file.path}")
                        nowSucceeded.add(file)
                    } else {
                        printlnColor(AnsiColor.YELLOW, "  ❌ 重试失败（文件无效或未创建）: ${file.path}")
                        stillFailed.add(file)
                    }
                } else {
                    // Use Base64 generation approach for other media
                    val agentContext = SubAgentContext(
                        prompt = createMediaGenerationPromptWithWrite(file, projectProperties),
                        name = "RetryMedia_${file.name}_Attempt${currentAttempt + 1}",
                        llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.generateCode)
                            .toLLM()
                    )

                    SubAgentTools(httpAccess, parameters).runSubAgent(agentContext)

                    // Check if file is valid (with auto-repair if needed)
                    if (validateAndRepairMedia(file)) {
                        printlnColor(AnsiColor.GREEN_B, "  ✅ 重试成功: ${file.path}")
                        nowSucceeded.add(file)
                    } else {
                        printlnColor(AnsiColor.YELLOW, "  ❌ 重试失败（文件无效或未创建）: ${file.path}")
                        stillFailed.add(file)
                    }
                }
            } catch (e: Exception) {
                printlnColor(AnsiColor.RED, "  ❌ 重试失败（异常）: ${file.path}")
                printlnColor(AnsiColor.YELLOW, "     错误: ${e.message}")
                stillFailed.add(file)
            }
        }

        logProgress(
            AnsiColor.CYAN,
            "MediaRetry",
            "重试结果：${nowSucceeded.size} 成功，${stillFailed.size} 失败"
        )

        // Update storage
        storage.set(failedMediaFilesKey, stillFailed)
        storage.set(mediaRetryAttemptsKey, currentAttempt + 1)

        if (stillFailed.isEmpty()) {
            logProgress(AnsiColor.GREEN_B, "MediaRetry", "✅ 所有媒体文件重试成功！")
            "All media files generated successfully after retry"
        } else if (currentAttempt + 1 >= maxAttempts) {
            logProgress(
                AnsiColor.RED,
                "MediaRetry",
                "⚠️ 经过 $maxAttempts 次尝试，仍有 ${stillFailed.size} 个媒体文件无法生成"
            )
            printlnColor(AnsiColor.YELLOW, "未能生成的媒体文件：")
            stillFailed.forEach { file ->
                printlnColor(AnsiColor.YELLOW, "  • ${file.path}")
            }
            "Max retry attempts reached, ${stillFailed.size} files still failed"
        } else {
            logProgress(
                AnsiColor.YELLOW,
                "MediaRetry",
                "仍有 ${stillFailed.size} 个文件失败，将继续重试"
            )
            "Retry attempt ${currentAttempt + 1} completed, ${stillFailed.size} files still pending"
        }
    }

    // ============================================
    // Phase 6: Lightweight Compilation Check (NEW - 2025)
    // ============================================

    // Perform lightweight compilation check on generated source files
    val performCompilationCheck by node<String, CompilationCheckResult>(
        name = NodeNames.PERFORM_COMPILATION_CHECK
    ) {
        val compilationCheckEnabled = parameters.parameter("compilation_check_enabled", true)
        if (!compilationCheckEnabled) {
            logProgress(AnsiColor.CYAN, "CompilationCheck", "轻量级编译检查已禁用，跳过")
            return@node CompilationCheckResult(success = true, summary = "Check disabled")
        }

        logProgress(AnsiColor.CYAN, "CompilationCheck", "开始轻量级编译检查...")

        val architecture = storage.get(architectureKey) ?: ProjectArchitecture()
        val allFiles = storage.get(filesKey)
            ?: IOSAppProjectFiles(emptyList())

        // Read all source files
        val sourceFiles = allFiles.files
            .filter {
                !it.isDir && (it.mime.contains("swift") || it.mime.contains("kotlin") ||
                        it.mime.contains("java") || it.mime.contains("javascript") ||
                        it.name.endsWith(".swift") || it.name.endsWith(".kt") ||
                        it.name.endsWith(".java") || it.name.endsWith(".js") ||
                        it.name.endsWith(".ts") || it.name.endsWith(".tsx"))
            }
            .mapNotNull { file ->
                val f = File(file.path)
                if (f.exists() && f.isFile) {
                    try {
                        file.path to f.readText()
                    } catch (_: Exception) {
                        logProgress(AnsiColor.YELLOW, "CompilationCheck", "无法读取文件: ${file.path}")
                        null
                    }
                } else null
            }

        if (sourceFiles.isEmpty()) {
            logProgress(AnsiColor.YELLOW, "CompilationCheck", "没有源文件需要检查")
            return@node CompilationCheckResult(success = true, summary = "No source files found")
        }

        logProgress(AnsiColor.CYAN, "CompilationCheck", "检查 ${sourceFiles.size} 个源文件...")

        val (_, checkResult) = buildAndRunStructureAgent<String, CompilationCheckResult>(
            httpAccess = httpAccess,
            parameters = parameters,
            systemPrompt = """
                You are an expert static analyzer and compiler for ${projectProperties.developmentLanguage}.
                Perform thorough but practical compilation checks, focusing on real errors that would prevent compilation.
            """.trimIndent(),
            input = createCompilationCheckPrompt(projectProperties, sourceFiles, architecture),
            emptyValue = CompilationCheckResult(success = false, summary = "Check failed"),
            llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.build)
                .toLLM().toLLModel(),
            attachments = emptyList(),
            examples = listOf(
                exampleCompilationCheck_Success,
                exampleCompilationCheck_Failure,
                exampleCompilationCheck_WarningsHeavy
            )
        )

        val buildResult = checkResult ?: error("Build check returned null result")
        // Log results
        if (buildResult.success) {
            logProgress(AnsiColor.GREEN, "CompilationCheck", "✅ 编译检查通过")
        } else {
            logProgress(AnsiColor.RED, "CompilationCheck", "❌ 发现 ${buildResult.errors.size} 个编译错误")
            buildResult.errors.groupBy { it.filePath }.forEach { (filePath, errors) ->
                printlnColor(AnsiColor.RED, "  ${File(filePath).name}: ${errors.size} 个错误")
                errors.take(3).forEach { error ->
                    printlnColor(AnsiColor.YELLOW, "    Line ${error.line}: ${error.message}")
                }
                if (errors.size > 3) {
                    printlnColor(AnsiColor.YELLOW, "    ... 和 ${errors.size - 3} 个其他错误")
                }
            }
        }

        if (buildResult.warnings.isNotEmpty()) {
            logProgress(AnsiColor.YELLOW, "CompilationCheck", "⚠️  ${buildResult.warnings.size} 个警告")
        }

        logProgress(AnsiColor.CYAN, "CompilationCheck", buildResult.summary)

        // Store result
        storage.set(compilationCheckResultKey, buildResult)

        buildResult
    }

    // Fix compilation errors if any were found
    val fixCompilationErrors by node<CompilationCheckResult, CompilationCheckResult>(
        name = NodeNames.FIX_COMPILATION_ERRORS
    ) { checkResult ->
        if (checkResult.success || checkResult.errors.isEmpty()) {
            logProgress(AnsiColor.GREEN, "CompilationFix", "无需修复，所有文件编译正常")
            return@node checkResult
        }

        logProgress(AnsiColor.CYAN, "CompilationFix", "开始修复编译错误...")

        val architecture = storage.get(architectureKey) ?: ProjectArchitecture()
        val errorsByFile = checkResult.errors.groupBy { it.filePath }

        var fixedCount = 0
        val remainingErrors = mutableListOf<CompilationError>()

        errorsByFile.forEach { (filePath, errors) ->
            val file = File(filePath)
            if (!file.exists()) {
                logProgress(AnsiColor.RED, "CompilationFix", "文件不存在: $filePath")
                remainingErrors.addAll(errors)
                return@forEach
            }

            val fileContent = file.readText()
            logProgress(AnsiColor.YELLOW, "CompilationFix", "修复 ${file.name} (${errors.size} 个错误)")

            // Limit per-file fix attempts and add simple backoff
            val fixCounts = storage.get(compilationFixCountsKey) ?: mutableMapOf()
            val previousAttempts = fixCounts[filePath] ?: 0
            val maxAttempts = parameters.parameter("compilation_fix_max_attempts", 2)
            if (previousAttempts >= maxAttempts) {
                logProgress(
                    AnsiColor.YELLOW,
                    "CompilationFix",
                    "跳过 ${file.name}：已达到最大修复尝试次数 ($maxAttempts)"
                )
                remainingErrors.addAll(errors)
                return@forEach
            }
            val nextAttempt = previousAttempts + 1
            fixCounts[filePath] = nextAttempt
            storage.set(compilationFixCountsKey, fixCounts)

            // Simple jitter backoff
            try {
                delay(((nextAttempt - 1) * 200L).milliseconds)
            } catch (_: Exception) {
            }

            try {
                val (_, fix) = buildAndRunStructureAgent<String, CompilationFix>(
                    httpAccess = httpAccess,
                    parameters = parameters,
                    systemPrompt = """
                        You are an expert ${projectProperties.developmentLanguage} developer.
                        Fix compilation errors precisely and correctly.
                    """.trimIndent(),
                    input = createCompilationFixPrompt(projectProperties, filePath, fileContent, errors, architecture),
                    emptyValue = CompilationFix(filePath, fileContent, fileContent, "Fix failed"),
                    llmModel = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.build)
                        .toLLM().toLLModel(),
                    attachments = emptyList(),
                    examples = listOf(
                        exampleCompilationFix,
                        exampleCompilationFix_MissingImport,
                        exampleCompilationFix_Syntax
                    )
                )

                // Backup original once; later attempts keep last version as prior state
                val bak = File("$filePath.bak")
                if (!bak.exists()) {
                    try {
                        file.copyTo(bak, overwrite = false)
                    } catch (_: Exception) {
                    }
                }
                val resolvedFix = fix ?: error("Build fix generation returned null")
                // structureGraph returns `emptyValue` (NOT null, no exception) when
                // structured parsing fails — detect the sentinel and count those
                // errors as REMAINING. Previously the unchanged file was rewritten,
                // logged "✅ … Fix failed", counted as fixed, and a wholly-failed fix
                // round could report "All errors fixed successfully".
                val isFailedSentinel =
                    resolvedFix.explanation == "Fix failed" && resolvedFix.fixedCode == fileContent
                if (isFailedSentinel) {
                    logProgress(AnsiColor.RED, "CompilationFix", "修复失败(结构化输出解析失败) ${file.name}")
                    remainingErrors.addAll(errors)
                } else {
                    // Write fixed code to file
                    file.writeText(resolvedFix.fixedCode)
                    fixedCount++
                    logProgress(AnsiColor.GREEN, "CompilationFix", "✅ ${file.name}: ${resolvedFix.explanation}")
                }
            } catch (e: Exception) {
                logProgress(AnsiColor.RED, "CompilationFix", "修复失败 ${file.name}: ${e.message}")
                remainingErrors.addAll(errors)
            }
        }

        logProgress(
            if (remainingErrors.isEmpty()) AnsiColor.GREEN else AnsiColor.YELLOW,
            "CompilationFix",
            "修复完成: $fixedCount/${errorsByFile.size} 个文件已修复"
        )

        // Return updated result
        CompilationCheckResult(
            success = remainingErrors.isEmpty(),
            errors = remainingErrors,
            warnings = checkResult.warnings,
            summary = if (remainingErrors.isEmpty()) {
                "All errors fixed successfully"
            } else {
                "Fixed $fixedCount files, ${remainingErrors.size} errors remaining"
            }
        )
    }

    // Build and debug - delegates to Sub Agent for building and fixing compilation errors (macOS only)
    val buildDebug by node<String, String>(
        name = NodeNames.BUILD_DEBUG
    ) { _ ->
        logProgress(AnsiColor.GREEN, "Build", "委托 Sub Agent 执行构建与调试...")

        val projectRoot = File(projectProperties.projectRoot ?: ".")
        val projectStructure = getProjectStructureSummary(projectRoot)
        val retryTimes = parameters.parameter("retry_cycle", Defaults.DEFAULT_RETRY_CYCLES)
        val prompt = createBuildDebugPrompt(projectProperties, retryTimes, projectStructure)

        SubAgentTools(httpAccess, parameters).runSubAgent(
            SubAgentContext(
                prompt = prompt,
                name = "BuildDebugAgent",
                llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.debug)
                    .toLLM()
            )
        )
    }

    // Testing - delegates to Sub Agent for generating and running tests (macOS only)
    val testing by node<String, String>(
        name = NodeNames.TESTING
    ) { _ ->
        logProgress(AnsiColor.GREEN, "Testing", "委托 Sub Agent 执行测试...")

        val projectRoot = File(projectProperties.projectRoot ?: ".")
        val projectStructure = getProjectStructureSummary(projectRoot)
        val retryTimes = parameters.parameter("retry_cycle", Defaults.DEFAULT_RETRY_CYCLES)
        val prompt = createTestingPrompt(projectProperties, retryTimes, projectStructure)

        SubAgentTools(httpAccess, parameters).runSubAgent(
            SubAgentContext(
                prompt = prompt,
                name = "TestingAgent",
                llm = llModelGroupConfig.resolveModel(llModelGroupConfig.getAgentDefinedSettings<IOSAgentDefinedSettings>().modelAssignments.testing)
                    .toLLM()
            )
        )
    }

    // Summary - provides final project summary with build instructions and next steps
    val summary by toolCycleGraph<String, String>(
        name = NodeNames.SUMMARY,
        // Koog 1.0.0 — `asTools()` returns `List<ToolBase<*, *>>`; filter to
        // Tool for the strategy helper.
        tools = (listOf(
            ReadFileTool(JVMFileSystemProvider.ReadOnly),
            ListDirectoryTool(JVMFileSystemProvider.ReadOnly),
            ExitTool
        ) + TerminalInteractiveTools().asTools()).filterIsInstance<ai.koog.agents.core.tools.Tool<*, *>>(),
        inputPrompt = { input: String, _ -> input },
        // Koog 1.0.0 — `.content` accessor gone; use `.textContent()`.
        outputCompute = { resp, _ -> resp.textContent() }
    )

    // ============================================
    // Workflow Edge Definitions
    // ============================================

    // Step 1a: Ask requirements first (if not skipped)
    edge(
        nodeStart forwardTo askRequirementsIfNeeded
                onCondition { !skipAskRequirements }
                transformed {
            logProgress(AnsiColor.CYAN, "Start", "开始项目开发，首先询问用户需求...")
            projectProperties
        }
    )

    // Step 1b: Skip ask requirements and go directly to initialization or getProjectFiles
    edge(
        nodeStart forwardTo initializeProject
                onCondition { skipAskRequirements && shouldInitializeProject(projectProperties) }
                transformed {
            logProgress(
                AnsiColor.GREEN,
                "InitProject",
                "跳过需求询问，开始初始化项目（技术栈：${projectProperties.implementTechnology}，OS=${System.getProperty("os.name")}）"
            )
            projectProperties
        }
    )

    // Step 1c: Skip requirements & initialization, check design files first
    edge(
        nodeStart forwardTo generateDesignSpecification
                onCondition { skipAskRequirements && !shouldInitializeProject(projectProperties) && projectProperties.designFiles.isEmpty() }
                transformed {
            logProgress(AnsiColor.CYAN, "Start", "跳过需求询问和项目初始化，未检测到设计文件，将生成设计规格")
            projectProperties
        }
    )

    edge(
        nodeStart forwardTo sliceDesignAssets
                onCondition { skipAskRequirements && !shouldInitializeProject(projectProperties) && projectProperties.designFiles.isNotEmpty() }
                transformed {
            logProgress(
                AnsiColor.CYAN,
                "Start",
                "跳过需求询问和项目初始化，检测到 ${projectProperties.designFiles.size} 个设计文件，开始切图"
            )
        }
    )

    // Step 2a: After requirements confirmed, initialize project if needed
    edge(
        askRequirementsIfNeeded forwardTo initializeProject
                onCondition { props -> shouldInitializeProject(props) }
                transformed { props ->
            logProgress(
                AnsiColor.GREEN,
                "InitProject",
                "需求确认完成，开始初始化项目（技术栈：${props.implementTechnology}，OS=${System.getProperty("os.name")}）"
            )
            props
        }
    )

    // Step 2b: After requirements confirmed, skip initialization but check design files first
    edge(
        askRequirementsIfNeeded forwardTo generateDesignSpecification
                onCondition { props -> !shouldInitializeProject(props) && projectProperties.designFiles.isEmpty() }
                transformed { props ->
            logProgress(AnsiColor.CYAN, "Requirements", "需求确认完成，跳过项目初始化，未检测到设计文件，将生成设计规格")
            props
        }
    )

    edge(
        askRequirementsIfNeeded forwardTo sliceDesignAssets
                onCondition { props -> !shouldInitializeProject(props) && projectProperties.designFiles.isNotEmpty() }
                transformed { _ ->
            logProgress(
                AnsiColor.CYAN,
                "Requirements",
                "需求确认完成，跳过项目初始化，检测到 ${projectProperties.designFiles.size} 个设计文件，开始切图"
            )
        }
    )

    // ============================================
    // New Phase: Design Specification and Asset Slicing (BEFORE getProjectFiles)
    // ============================================

    // Step 3a: After initialization, check if need to generate design spec (no design files provided)
    edge(
        initializeProject forwardTo generateDesignSpecification
                onCondition { projectProperties.designFiles.isEmpty() }
                transformed {
            logProgress(AnsiColor.CYAN, "InitProject", "项目初始化完成，未检测到设计文件，将生成设计规格")
            projectProperties
        }
    )

    // Step 3b: After initialization, skip to sliceDesignAssets if design files exist
    edge(
        initializeProject forwardTo sliceDesignAssets
                onCondition { projectProperties.designFiles.isNotEmpty() }
                transformed {
            logProgress(
                AnsiColor.CYAN,
                "InitProject",
                "项目初始化完成，检测到 ${projectProperties.designFiles.size} 个设计文件，开始切图"
            )
        }
    )

    // Step 3c: After design spec generated, go to reviewDesign first
    edge(generateDesignSpecification forwardTo reviewDesign transformed { spec ->
        logProgress(AnsiColor.GREEN, "DesignSpec", "设计规格生成完成，进入设计评审")
        spec
    })

    // Step 3d: Design review retry on failure (with max retry limit)
    edge(
        reviewDesign forwardTo generateDesignSpecification onCondition { _ ->
            val review = storage.get(designReviewResultKey)
            val currentRetry = storage.get(designReviewRetryCountKey) ?: 0
            val maxRetries =
                parameters.parameter("design_review_max_retries", IOSGenConstants.DEFAULT_DESIGN_REVIEW_MAX_RETRIES)
            if (review != null && !review.approved && currentRetry < maxRetries) {
                storage.set(designReviewRetryCountKey, currentRetry + 1)
                logProgress(
                    AnsiColor.YELLOW,
                    "DesignReview",
                    "🔄 设计评审未通过，将根据反馈重新生成设计规格（第 ${currentRetry + 1}/$maxRetries 次重试）"
                )
                true
            } else false
        } transformed {
            projectProperties
        }
    )

    // Step 3e: If design review passed, proceed to slicing
    edge(
        reviewDesign forwardTo sliceDesignAssets
                onCondition { _ ->
            val review = storage.get(designReviewResultKey)
            review?.approved == true
        } transformed {
            logProgress(AnsiColor.GREEN, "DesignReview", "✅ 设计评审通过，开始切图")
        }
    )

    // Step 3f: If design review failed and max retries reached, block and summarize
    edge(
        reviewDesign forwardTo summary
                onCondition { _ ->
            val review = storage.get(designReviewResultKey)
            val currentRetry = storage.get(designReviewRetryCountKey) ?: 0
            val maxRetries =
                parameters.parameter("design_review_max_retries", IOSGenConstants.DEFAULT_DESIGN_REVIEW_MAX_RETRIES)
            review != null && !review.approved && currentRetry >= maxRetries
        } transformed {
            val review = storage.get(designReviewResultKey)
            "❌ 设计评审在达到最大重试次数后仍未通过，阻断生成流程。\n总结: ${review?.summary ?: "无"}"
        }
    )

    // Step 4: After slicing assets, go to reviewSlicing first
    edge(
        sliceDesignAssets forwardTo reviewSlicing transformed { manifest ->
            logProgress(
                AnsiColor.GREEN,
                "SliceAssets",
                "切图完成（${manifest.assets.size} 个媒体资源），进入切图评审"
            )
            manifest
        }
    )

    // Step 4b: Slicing review retry on failure (with max retry limit)
    edge(
        reviewSlicing forwardTo sliceDesignAssets
                onCondition { _ ->
            val review = storage.get(slicingReviewResultKey)
            val currentRetry = storage.get(slicingReviewRetryCountKey) ?: 0
            val maxRetries =
                parameters.parameter("slicing_review_max_retries", IOSGenConstants.DEFAULT_SLICING_REVIEW_MAX_RETRIES)
            if (review != null && !review.approved && currentRetry < maxRetries) {
                storage.set(slicingReviewRetryCountKey, currentRetry + 1)
                logProgress(
                    AnsiColor.YELLOW,
                    "SlicingReview",
                    "🔄 切图评审未通过，将根据反馈重新进行切图（第 ${currentRetry + 1}/$maxRetries 次重试）"
                )
                true
            } else false
        } transformed {
        }
    )

    // Step 4c: If slicing review passed, proceed to getProjectFiles
    edge(
        reviewSlicing forwardTo getProjectFiles
                onCondition { _ ->
            val review = storage.get(slicingReviewResultKey)
            review?.approved == true
        } transformed {
            logProgress(AnsiColor.GREEN, "SlicingReview", "✅ 切图评审通过，开始读取项目结构并生成文件清单")
        }
    )

    // Step 4d: If slicing review failed and max retries reached, block and summarize
    edge(
        reviewSlicing forwardTo summary
                onCondition { _ ->
            val review = storage.get(slicingReviewResultKey)
            val currentRetry = storage.get(slicingReviewRetryCountKey) ?: 0
            val maxRetries =
                parameters.parameter("slicing_review_max_retries", IOSGenConstants.DEFAULT_SLICING_REVIEW_MAX_RETRIES)
            review != null && !review.approved && currentRetry >= maxRetries
        } transformed {
            val review = storage.get(slicingReviewResultKey)
            "❌ 切图评审在达到最大重试次数后仍未通过，阻断生成流程。\n总结: ${review?.summary ?: "无"}"
        }
    )

    // Retry if no files were generated — bounded like the architecture retry below:
    // an unconditional self-loop burned a full structured-LLM call per iteration
    // until Koog's global iteration cap aborted the whole run.
    edge(getProjectFiles forwardTo getProjectFiles onCondition {
        if (it.files.isNotEmpty()) return@onCondition false
        val currentRetries = storage.get(projectFilesRetryCountKey) ?: 0
        val maxRetries = parameters.parameter(
            "project_files_max_retries",
            IOSGenConstants.DEFAULT_ARCHITECTURE_DESIGN_MAX_RETRIES
        )
        if (currentRetries < maxRetries) {
            storage.set(projectFilesRetryCountKey, currentRetries + 1)
            true
        } else {
            printlnColor(AnsiColor.RED, "[Error] 文件清单重试 $currentRetries 次后仍为空，继续执行(可能影响生成质量)")
            false
        }
    } transformed {
        logProgress(AnsiColor.YELLOW, "GetProjectFiles", "文件清单为空，重试")
    })

    // Step 5: After getProjectFiles, go to designArchitecture with media manifest
    edge(
        getProjectFiles forwardTo designArchitecture transformed { _ ->
            logProgress(AnsiColor.GREEN, "GetProjectFiles", "文件清单生成完成，开始设计架构（整合媒体资源）")
            // Retrieve MediaAssetsManifest from storage
            storage.get(mediaManifestKey)
                ?: MediaAssetsManifest(assets = emptyList())
        }
    )

    // Retry if no architecture was generated (with max retry limit)
    edge(
        designArchitecture forwardTo designArchitecture
                onCondition { arch ->
            if (arch.fileArchitectures.isNotEmpty()) {
                return@onCondition false
            }

            // Check retry count
            val currentRetries = storage.get(architectureRetryCountKey) ?: 0
            val maxRetries = parameters.parameter(
                "architecture_design_max_retries",
                IOSGenConstants.DEFAULT_ARCHITECTURE_DESIGN_MAX_RETRIES
            )

            if (currentRetries < maxRetries) {
                storage.set(architectureRetryCountKey, currentRetries + 1)
                true
            } else {
                // Max retries reached, log error and continue with empty architecture
                printlnColor(
                    AnsiColor.RED,
                    "[Error] 架构设计重试 $currentRetries 次后仍返回 0 个文件定义，将继续执行但可能影响代码质量"
                )
                false
            }
        } transformed {
            val retryCount = storage.get(architectureRetryCountKey) ?: 0
            logProgress(
                AnsiColor.YELLOW,
                "ArchitectureDesign",
                "架构设计返回 0 个文件定义，重试 (${retryCount}/${
                    parameters.parameter(
                        "architecture_design_max_retries",
                        IOSGenConstants.DEFAULT_ARCHITECTURE_DESIGN_MAX_RETRIES
                    )
                })..."
            )
            // Return MediaAssetsManifest from storage for retry
            storage.get(mediaManifestKey)
                ?: MediaAssetsManifest(assets = emptyList())
        }
    )

    // Step 5: After architecture design, run unified review (quality + completeness)
    edge(
        designArchitecture forwardTo reviewArchitecture transformed { arch ->
            if (arch.fileArchitectures.isEmpty()) {
                logProgress(
                    AnsiColor.YELLOW,
                    "ArchitectureDesign",
                    "架构设计为空（经过重试后仍失败），跳过评审"
                )
            } else {
                logProgress(
                    AnsiColor.GREEN,
                    "ArchitectureDesign",
                    "架构设计完成（${arch.fileArchitectures.size} 个文件），进入统一评审"
                )
            }
            arch
        }
    )

    // Step 6a: If unified review failed and can retry, go back to redesign
    edge(
        reviewArchitecture forwardTo designArchitecture
                onCondition { _ ->
            val review =
                storage.get(architectureReviewResultKey)
            val currentRetry = storage.get(architectureReviewRetryCountKey) ?: 0
            val maxRetries = parameters.parameter(
                "architecture_review_max_retries",
                IOSGenConstants.DEFAULT_ARCHITECTURE_REVIEW_MAX_RETRIES
            )
            val qualityGateEnabled = parameters.parameter("architecture_quality_gate_enabled", true)

            // Retry if: review failed AND haven't reached max retries AND quality gate is enabled
            if (qualityGateEnabled && review != null && !review.approved && currentRetry < maxRetries) {
                storage.set(architectureReviewRetryCountKey, currentRetry + 1)
                logProgress(
                    AnsiColor.YELLOW,
                    "ArchitectureReview",
                    "🔄 架构评审未通过，将基于反馈重新设计（第 ${currentRetry + 1}/$maxRetries 次重试）"
                )
                true
            } else {
                false
            }
        } transformed {
            // Return media manifest to trigger redesign
            storage.get(mediaManifestKey)
                ?: MediaAssetsManifest(assets = emptyList())
        }
    )

    // Step 6b: If unified review passed, proceed to setup (do NOT proceed on failed max-retry)
    edge(
        reviewArchitecture forwardTo setupNode
                onCondition { _ ->
            val review =
                storage.get(architectureReviewResultKey)
            val qualityGateEnabled = parameters.parameter("architecture_quality_gate_enabled", true)

            // Continue only if: quality gate disabled OR review passed
            val shouldContinue = !qualityGateEnabled || review == null || review.approved

            if (review?.approved == true) {
                logProgress(
                    AnsiColor.GREEN,
                    "ArchitectureReview",
                    "✅ 统一评审通过（质量+完整性），进入 Setup 阶段"
                )
            }
            shouldContinue
        } transformed { layout ->
            layout
        }
    )

    // Step 6c: If unified review failed and max retries reached, block and summarize
    edge(
        reviewArchitecture forwardTo summary
                onCondition { _ ->
            val review = storage.get(architectureReviewResultKey)
            val currentRetry = storage.get(architectureReviewRetryCountKey) ?: 0
            val maxRetries = parameters.parameter(
                "architecture_review_max_retries",
                IOSGenConstants.DEFAULT_ARCHITECTURE_REVIEW_MAX_RETRIES
            )
            val qualityGateEnabled = parameters.parameter("architecture_quality_gate_enabled", true)

            qualityGateEnabled && review != null && !review.approved && currentRetry >= maxRetries
        }
                transformed {
            val review = storage.get(architectureReviewResultKey)
            "❌ 架构评审在达到最大重试次数后仍未通过，阻断生成流程。\n总结: ${review?.summary ?: "无"}"
        }
    )

    // Phase 3: New parallel generation workflow
    // Step 5.1: Setup node generates batches
    edge(setupNode forwardTo retrievalTask transformed {
        logProgress(AnsiColor.BLUE, "Setup", "开始批量生成源码文件")
    })

    // Step 5.2: Retrieval task returns a batch of source files
    edge(
        retrievalTask forwardTo generateSourceFilesParallel
                onCondition { it.isNotEmpty() }
                transformed { batch ->
            logProgress(AnsiColor.MAGENTA, "Generate", "取得批次，准备并行生成 ${batch.size} 个源码文件")
            batch
        }
    )

    // Step 5.3: After parallel generation, go back to retrieval for next batch
    edge(generateSourceFilesParallel forwardTo retrievalTask transformed {
        logProgress(AnsiColor.GREEN, "Generate", "当前批次完成，继续处理下一批次")
    })

    // Step 5.4: When all source file batches are done, check for failed files
    edge(
        retrievalTask forwardTo checkAndRetryFailedSourceFiles
                onCondition { it.isEmpty() }
                transformed {
            logProgress(AnsiColor.GREEN, "Progress", "所有源码批次完成，检查失败文件")
            emptyList()
        }
    )

    // Step 5.5: Retry failed source files if any
    edge(
        checkAndRetryFailedSourceFiles forwardTo retryFailedSourceFiles
                onCondition { it.isNotEmpty() }
                transformed { filesToRetry ->
            logProgress(AnsiColor.YELLOW, "Retry", "发现 ${filesToRetry.size} 个失败文件，开始重试")
            filesToRetry
        }
    )

    // Step 5.6: After retry, go back to check again
    edge(retryFailedSourceFiles forwardTo checkAndRetryFailedSourceFiles transformed {
        logProgress(AnsiColor.GREEN, "Retry", "重试完成，重新检查")
        emptyList()
    })

    // Step 5.7: When no files to retry, proceed to media files
    edge(
        checkAndRetryFailedSourceFiles forwardTo collectMediaFiles
                onCondition { it.isEmpty() }
                transformed {
            logProgress(AnsiColor.GREEN, "Progress", "所有源码文件生成完成，开始处理媒体文件")
            ""
        }
    )

    // Step 5.5: Parallel generate media files
    edge(
        collectMediaFiles forwardTo generateMediaFilesParallel
                onCondition { it.isNotEmpty() }
                transformed { mediaFiles ->
            logProgress(AnsiColor.MAGENTA, "Media", "准备并行生成 ${mediaFiles.size} 个媒体文件")
            mediaFiles
        }
    )

    // Step 5.6: After parallel media generation, check if any files failed and need retry
    edge(
        generateMediaFilesParallel forwardTo retryFailedMediaFiles
                onCondition {
            val failedFiles = storage.get(failedMediaFilesKey)
                ?: emptyList()
            failedFiles.isNotEmpty()
        }
                transformed {
            logProgress(AnsiColor.YELLOW, "Media", "检测到媒体文件生成失败，准备重试")
            ""
        }
    )

    // Step 5.7: Retry loop - continue retrying if files still failed and haven't reached max attempts
    edge(
        retryFailedMediaFiles forwardTo retryFailedMediaFiles
                onCondition {
            val failedFiles = storage.get(failedMediaFilesKey)
                ?: emptyList()
            val currentAttempt = storage.get(mediaRetryAttemptsKey) ?: 0
            val maxAttempts =
                parameters.parameter("media_retry_max_attempts", IOSGenConstants.DEFAULT_MEDIA_RETRY_MAX_ATTEMPTS)

            failedFiles.isNotEmpty() && currentAttempt < maxAttempts
        }
                transformed {
            logProgress(AnsiColor.CYAN, "MediaRetry", "继续重试剩余失败的媒体文件")
            ""
        }
    )

    // Step 5.8: After successful media generation (or no failures), perform compilation check
    edge(
        generateMediaFilesParallel forwardTo performCompilationCheck
                onCondition {
            val failedFiles = storage.get(failedMediaFilesKey)
                ?: emptyList()
            failedFiles.isEmpty()
        }
                transformed {
            logProgress(AnsiColor.GREEN, "Progress", "媒体文件生成完成，进入编译检查")
            ""
        }
    )

    // Step 5.9: After compilation check, fix errors if any
    edge(
        performCompilationCheck forwardTo fixCompilationErrors
                transformed { checkResult ->
            logProgress(AnsiColor.CYAN, "CompilationCheck", "检查完成，进入修复流程")
            checkResult
        }
    )

    // Step 5.10: After fix, re-check compilation (loop until no errors or max attempts)
    edge(
        fixCompilationErrors forwardTo performCompilationCheck
                onCondition { checkResult ->
            val compilationFixMaxRetries = parameters.parameter("compilation_fix_max_retries", 2)
            val currentRetries = storage.get(compilationFixRetriesKey) ?: 0

            if (!checkResult.success && currentRetries < compilationFixMaxRetries) {
                storage.set(compilationFixRetriesKey, currentRetries + 1)
                logProgress(
                    AnsiColor.YELLOW,
                    "CompilationFix",
                    "仍有错误，重新检查 (第 ${currentRetries + 1}/$compilationFixMaxRetries 次)"
                )
                true
            } else {
                if (!checkResult.success) {
                    logProgress(AnsiColor.YELLOW, "CompilationFix", "达到最大重试次数，继续构建")
                }
                false
            }
        }
                transformed {
            ""
        }
    )

    // Step 5.11: If all errors are fixed successfully, proceed immediately
    edge(
        fixCompilationErrors forwardTo buildDebug
                onCondition { checkResult ->
            isMacOS() && checkResult.success
        }
                transformed {
            val retryTimes = parameters.parameter("retry_cycle", Defaults.DEFAULT_RETRY_CYCLES)
            logProgress(
                AnsiColor.GREEN,
                "Build",
                "编译错误已全部修复，进入构建与调试阶段（最大重试次数：${retryTimes}，使用 Sub Agent）"
            )
            ""
        }
    )

    edge(
        fixCompilationErrors forwardTo summary
                onCondition { checkResult ->
            !isMacOS() && checkResult.success
        }
                transformed {
            logProgress(AnsiColor.GREEN, "Summary", "编译错误已全部修复，非 macOS 环境：生成总结报告…")
            createSummaryPrompt(projectProperties, builtAndTested = false)
        }
    )

    // Step 5.11b: After compilation check passes (or max retries), go to build on macOS
    edge(
        fixCompilationErrors forwardTo buildDebug
                onCondition {
            val currentRetries = storage.get(compilationFixRetriesKey) ?: 0
            val compilationFixMaxRetries = parameters.parameter("compilation_fix_max_retries", 2)
            isMacOS() && currentRetries >= compilationFixMaxRetries
        }
                transformed {
            val retryTimes = parameters.parameter("retry_cycle", Defaults.DEFAULT_RETRY_CYCLES)
            logProgress(
                AnsiColor.GREEN,
                "Build",
                "编译检查完成，进入构建与调试阶段（最大重试次数：${retryTimes}，使用 Sub Agent）"
            )
            ""
        }
    )

    edge(
        fixCompilationErrors forwardTo summary
                onCondition {
            val currentRetries = storage.get(compilationFixRetriesKey) ?: 0
            val compilationFixMaxRetries = parameters.parameter("compilation_fix_max_retries", 2)
            !isMacOS() && currentRetries >= compilationFixMaxRetries
        }
                transformed {
            logProgress(AnsiColor.GREEN, "Summary", "编译检查完成，非 macOS 环境：生成总结报告…")
            createSummaryPrompt(projectProperties, builtAndTested = false)
        }
    )

    // Step 5.12: After retry completion (success or max attempts), go to compilation check
    edge(
        retryFailedMediaFiles forwardTo performCompilationCheck
                onCondition {
            val failedFiles = storage.get(failedMediaFilesKey)
                ?: emptyList()
            val currentAttempt = storage.get(mediaRetryAttemptsKey) ?: 0
            val maxAttempts =
                parameters.parameter("media_retry_max_attempts", IOSGenConstants.DEFAULT_MEDIA_RETRY_MAX_ATTEMPTS)

            failedFiles.isEmpty() || currentAttempt >= maxAttempts
        }
                transformed {
            val failedFiles = storage.get(failedMediaFilesKey)
                ?: emptyList()

            if (failedFiles.isEmpty()) {
                logProgress(AnsiColor.GREEN, "Progress", "媒体文件重试成功，进入编译检查")
            } else {
                logProgress(
                    AnsiColor.YELLOW,
                    "Progress",
                    "媒体文件部分失败，继续进入编译检查（${failedFiles.size} 个文件未能生成）"
                )
            }
            ""
        }
    )

    // Step 5.10: After successful media generation (or no failures), go to summary on non-macOS
    edge(
        generateMediaFilesParallel forwardTo summary
                onCondition {
            val failedFiles = storage.get(failedMediaFilesKey)
                ?: emptyList()
            failedFiles.isEmpty() && !isMacOS()
        }
                transformed {
            logProgress(AnsiColor.GREEN, "Summary", "媒体文件生成完成，非 macOS 环境：跳过构建与测试，生成总结报告…")
            createSummaryPrompt(projectProperties, builtAndTested = false)
        }
    )

    // Step 5.11: After retry completion, go to summary on non-macOS
    edge(
        retryFailedMediaFiles forwardTo summary
                onCondition {
            val failedFiles = storage.get(failedMediaFilesKey)
                ?: emptyList()
            val currentAttempt = storage.get(mediaRetryAttemptsKey) ?: 0
            val maxAttempts =
                parameters.parameter("media_retry_max_attempts", IOSGenConstants.DEFAULT_MEDIA_RETRY_MAX_ATTEMPTS)

            !isMacOS() && (failedFiles.isEmpty() || currentAttempt >= maxAttempts)
        }
                transformed {
            logProgress(AnsiColor.GREEN, "Summary", "媒体文件处理完成，非 macOS 环境：生成总结报告…")
            createSummaryPrompt(projectProperties, builtAndTested = false)
        }
    )

    // Step 5.8: If no media files, go to build (macOS) or summary (non-macOS)
    edge(
        collectMediaFiles forwardTo performCompilationCheck
                onCondition { it.isEmpty() }
                transformed {
            logProgress(AnsiColor.GREEN, "Progress", "无媒体文件，进入编译检查")
            ""
        }
    )

    // Phase 6: Testing (macOS only, delegated to Sub Agent)
    edge(buildDebug forwardTo testing transformed {
        val retryTimes = parameters.parameter("retry_cycle", Defaults.DEFAULT_RETRY_CYCLES)
        logProgress(AnsiColor.GREEN, "Build", "构建阶段完成，进入测试阶段（最大重试次数：${retryTimes}，使用 Sub Agent）")
        ""
    })

    // Phase 7: Summary (from testing on macOS)
    edge(testing forwardTo summary transformed {
        logProgress(AnsiColor.GREEN, "Testing", "测试阶段完成，准备生成总结报告…")
        createSummaryPrompt(projectProperties, builtAndTested = true)
    })

    // Final completion
    summary then nodeFinish
}
