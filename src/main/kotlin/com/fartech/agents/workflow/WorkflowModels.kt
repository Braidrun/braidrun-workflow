package com.fartech.agents.workflow

import com.fartech.agents.commons.CustomModelDefinition
import com.fartech.agents.commons.HistoryCompressionConfig
import com.fartech.agents.commons.SkillsConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 目录隔离配置
 *
 * 控制工作流执行期间各类目录的隔离策略，防止并发执行时目录冲突。
 *
 * 隔离策略分层设计：
 * - working_dir / output_dir：per-execution 隔离（并发写入安全）
 * - persistence_storage_root：per-execution 隔离（状态快照隔离）
 * - file_cache_storage：全局共享（基于内容 hash 的只读缓存，命中率最优）
 * - history_storage_root：全局共享（已通过 session_id 实现文件级隔离）
 * - skills 目录：全局共享（只读资源，减少重复下载）
 *
 * YAML 示例：
 * ```yaml
 * directory_isolation:
 *   enabled: true
 *   base_dir: ".workflow-runs"
 *   working_dir_pattern: "{base_dir}/{execution_id}/{step_name}/workspace"
 *   output_dir_pattern: "{base_dir}/{execution_id}/output"
 *   persistence_dir_pattern: "{base_dir}/{execution_id}/.snapshots"
 *   shared_cache_dir: ".prompt_cache"
 *   shared_history_dir: ".agent_history"
 *   shared_skills_dir: "./skills"
 *   cleanup_on_completion: false
 *   cleanup_after_hours: 72
 * ```
 */
@Serializable
data class DirectoryIsolationConfig(
    /** 是否启用目录隔离（默认 true） */
    @SerialName("enabled")
    val enabled: Boolean = true,

    /** 隔离目录的根路径（默认 .workflow-runs） */
    @SerialName("base_dir")
    val baseDir: String = ".workflow-runs",

    /**
     * working_dir 路径模板，支持占位符：
     * {base_dir}, {user_id}, {execution_id}, {step_name}, {agent_name}, {workflow_name}
     *
     * Phase 4: default pattern now includes `{user_id}` to namespace artifacts per owner.
     */
    @SerialName("working_dir_pattern")
    val workingDirPattern: String = "{base_dir}/{user_id}/{execution_id}/{step_name}/workspace",

    /**
     * output_dir 路径模板
     */
    @SerialName("output_dir_pattern")
    val outputDirPattern: String = "{base_dir}/{user_id}/{execution_id}/output",

    /**
     * persistence_storage_root 路径模板
     */
    @SerialName("persistence_dir_pattern")
    val persistenceDirPattern: String = "{base_dir}/{user_id}/{execution_id}/.snapshots",

    /** 共享的 prompt 缓存目录（全局共享，不隔离） */
    @SerialName("shared_cache_dir")
    val sharedCacheDir: String = ".prompt_cache",

    /** 共享的历史消息目录（全局共享，已通过 session_id 隔离） */
    @SerialName("shared_history_dir")
    val sharedHistoryDir: String = ".agent_history",

    /** 共享的 skills 目录（全局共享，只读资源） */
    @SerialName("shared_skills_dir")
    val sharedSkillsDir: String = "./skills",

    /** 执行完成后是否自动清理隔离目录 */
    @SerialName("cleanup_on_completion")
    val cleanupOnCompletion: Boolean = false,

    /** 自动清理超过指定小时数的旧执行目录（0 = 不自动清理） */
    @SerialName("cleanup_after_hours")
    val cleanupAfterHours: Int = 72
) {
    /**
     * 解析路径模板，将占位符替换为实际值
     */
    fun resolvePattern(
        pattern: String,
        executionId: String,
        stepName: String = "",
        agentName: String = "",
        workflowName: String = "",
        userId: String = ""
    ): String {
        return pattern
            .replace("{base_dir}", baseDir)
            .replace("{user_id}", userId.ifEmpty { "default" })
            .replace("{execution_id}", executionId)
            .replace("{step_name}", stepName.ifEmpty { "default" })
            .replace("{agent_name}", agentName.ifEmpty { "default" })
            .replace("{workflow_name}", workflowName.ifEmpty { "default" })
    }

    /**
     * 获取指定步骤的 working_dir
     */
    fun getWorkingDir(
        executionId: String,
        stepName: String,
        agentName: String = "",
        workflowName: String = "",
        userId: String = ""
    ): String {
        return resolvePattern(workingDirPattern, executionId, stepName, agentName, workflowName, userId)
    }

    /**
     * 获取指定执行的 output_dir
     */
    fun getOutputDir(
        executionId: String,
        stepName: String = "",
        agentName: String = "",
        workflowName: String = "",
        userId: String = ""
    ): String {
        return resolvePattern(outputDirPattern, executionId, stepName, agentName, workflowName, userId)
    }

    /**
     * 获取指定执行的 persistence_storage_root
     */
    fun getPersistenceDir(
        executionId: String,
        stepName: String = "",
        agentName: String = "",
        workflowName: String = "",
        userId: String = ""
    ): String {
        return resolvePattern(persistenceDirPattern, executionId, stepName, agentName, workflowName, userId)
    }

    companion object {
        /** 默认配置（启用隔离） */
        val DEFAULT = DirectoryIsolationConfig()

        /** 禁用隔离的配置（向后兼容） */
        val DISABLED = DirectoryIsolationConfig(enabled = false)
    }
}

/**
 * DAG 自动并发执行配置
 *
 * 当启用后，工作流执行器会对 DAG 拓扑排序后处于同一层级（即依赖均已完成）的步骤
 * 自动进行并发执行，而非逐个串行执行。
 *
 * YAML 格式:
 * ```yaml
 * concurrency:
 *   enabled: true        # 是否启用自动并发（默认 false，保持串行）
 *   max_concurrency: 4   # 同层最大并发数（默认不限制，即 0 表示无限制）
 * ```
 */
@Serializable
data class ConcurrencyConfig(
    /** 是否启用 DAG 自动并发执行（默认 false，保持向后兼容的串行执行） */
    @SerialName("enabled")
    val enabled: Boolean = false,

    /** 同一拓扑层内的最大并发步骤数（0 或 null 表示不限制） */
    @SerialName("max_concurrency")
    val maxConcurrency: Int = 0
) {
    init {
        require(maxConcurrency >= 0) { "max_concurrency must be >= 0 (0 means unlimited)" }
    }
}

/**
 * 多媒体工具配置
 *
 * 为图片/音频生成工具提供独立的 API Key、Base URL 与默认模型，
 * 避免与主 LLM 配置共用同一套模型/凭证。
 */
@Serializable
data class MultimediaToolConfig(
    /** 多媒体调用使用的 API Key（当前主要用于 OpenRouter） */
    @SerialName("api_key")
    val apiKey: String? = null,

    /** 从凭据中心解析多媒体 API Key 时使用的 provider */
    @SerialName("credential_provider")
    val credentialProvider: String? = null,

    /** 多媒体调用使用的 API Base URL */
    @SerialName("base_url")
    val baseUrl: String? = null,

    /** 默认图片生成模型（可覆盖工具函数中的默认枚举） */
    @SerialName("image_model")
    val imageModel: String? = null,

    /** 默认音频生成模型（可覆盖工具函数中的默认枚举） */
    @SerialName("audio_model")
    val audioModel: String? = null
)

/**
 * 工作流编辑器的全局 Agent 配置
 *
 * 该 Agent 主要用于工作流创建与编辑辅助，不参与正常工作流执行调度。
 * 仍然持久化到 YAML 中，便于 Web 编辑器导入/导出时保留完整设置。
 */
@Serializable
data class WorkflowGlobalAgentConfig(
    /** 是否启用全局 Agent */
    @SerialName("enabled")
    val enabled: Boolean = true,

    /** 全局 Agent 的主体配置 */
    @SerialName("agent")
    val agent: AgentDefinition = AgentDefinition(),

    /** 各类工具运行所需的额外参数 */
    @SerialName("tool_parameters")
    @kotlinx.serialization.Serializable(with = YamlJsonElementMapSerializer::class)
    val toolParameters: Map<String, JsonElement> = emptyMap(),

    /** 多媒体工具独立配置 */
    @SerialName("multimedia")
    val multimedia: MultimediaToolConfig? = null
)

/**
 * 工作流定义的完整模型
 *
 * 支持的功能:
 * - 多 Agent 协同
 * - 串行/并行执行
 * - DAG 自动并发执行
 * - 条件分支
 * - 错误处理和重试
 * - 超时控制
 * - 目录隔离（防止并发冲突）
 */
@Serializable
data class WorkflowDefinition(
    @SerialName("name")
    val name: String,

    @SerialName("version")
    val version: String = "1.0.0",

    @SerialName("description")
    val description: String? = null,

    @SerialName("agents")
    val agents: Map<String, AgentDefinition>,

    @SerialName("workflow")
    val workflow: List<WorkflowStep>,

    @SerialName("error_handling")
    val errorHandling: ErrorHandlingConfig? = null,

    @SerialName("timeout")
    val timeout: TimeoutConfig? = null,

    @SerialName("variables")
    val variables: Map<String, String> = emptyMap(),

    @SerialName("variable_types")
    val variableTypes: Map<String, String> = emptyMap(),

    /**
     * 可选：每个变量的标签映射（变量名 → 单个标签字符串）。仅为 workflow-web 编辑器的
     * 变量分组视图服务，agent 运行时不消费此字段。空字符串等同于「无标签」。
     */
    @SerialName("variable_tags")
    val variableTags: Map<String, String> = emptyMap(),

    @SerialName("directory_isolation")
    val directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig(),

    @SerialName("concurrency")
    val concurrency: ConcurrencyConfig = ConcurrencyConfig(),

    /** 工作流级共享知识库配置（可选，启用后步骤间可通过语义检索共享知识） */
    @SerialName("knowledge_base")
    val knowledgeBase: KnowledgeBaseConfig? = null,

    /** 编辑器全局 Agent 配置（用于 Web 端辅助创建/编辑工作流） */
    @SerialName("global_agent")
    val globalAgent: WorkflowGlobalAgentConfig? = null,

    /** 工作流标签（行业标签、技术标签、场景标签，用于分类筛选） */
    @SerialName("tags")
    val tags: List<String> = emptyList(),

    /** 代码前导（按语言分组，自动拼接到对应语言的每个 code step 脚本头部） */
    @SerialName("code_preamble")
    val codePreamble: Map<String, CodePreambleConfig> = emptyMap(),

    /** 工作流类别（如 automation、analysis 等，用于 Web 端分组筛选） */
    @SerialName("category")
    val category: String? = null,

    /**
     * 模块契约(可选)。存在且 enabled=true 时,该 workflow 作为一个"可复用模块"
     * 对外暴露 inputs / outputs API,被父 workflow 的 `sub_workflow` 步骤以严格模式调用。
     * 详见 [WorkflowModuleContract]。
     */
    @SerialName("module")
    val module: WorkflowModuleContract? = null
) {
    /** 是否是一个模块(含非空且 enabled 的契约) */
    val isModule: Boolean get() = module?.enabled == true

    init {
        require(name.isNotBlank()) { "Workflow name cannot be blank" }
        // 如果所有步骤都是 code 步骤（不需要 agent），则允许 agents 为空
        val hasAgentDependentSteps = workflow.any { it.referencedAgents.isNotEmpty() }
        require(agents.isNotEmpty() || !hasAgentDependentSteps) {
            "Workflow must define at least one agent (unless all steps are code steps)"
        }
        require(workflow.isNotEmpty()) { "Workflow must have at least one step" }

        // 验证所有步骤引用的 agent 都已定义
        workflow.forEach { step ->
            step.referencedAgents.forEach { agentName ->
                require(agents.containsKey(agentName)) {
                    "Step '${step.step}' references undefined agent '$agentName'"
                }
            }
        }

        // 验证步骤名称唯一性
        val stepNames = workflow.map { it.step }
        require(stepNames.size == stepNames.toSet().size) {
            "Duplicate step names found: ${stepNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
        }
    }
}

/**
 * Agent 定义（预设模式）
 *
 * 通过引用预设模板 + 覆盖参数来定义 agent，无需手动指定所有配置。
 * 预设提供完整的默认配置，overrides 仅覆盖需要修改的部分。
 *
 * YAML 格式:
 * ```yaml
 * agents:
 *   my_agent:
 *     preset: universal_reasoning          # 引用预设模板
 *     overrides:                            # 覆盖参数（可选）
 *       system_prompt: "自定义提示词"
 *       llm_config:
 *         models:
 *           - model: anthropic/claude-sonnet-4-20250514
 *             provider: openrouter
 *         temperature: 0.7
 *       tool_set:
 *         - exit
 *         - shell
 *         - file_system
 * ```
 *
 * 也支持无预设的完全自定义模式（向后兼容）:
 * ```yaml
 * agents:
 *   my_agent:
 *     overrides:
 *       type: universal_agent
 *       strategy: just_work_parallel
 *       system_prompt: "..."
 *       llm_config:
 *         models:
 *           - model: anthropic/claude-sonnet-4-20250514
 *             provider: openrouter
 * ```
 */
@Serializable
data class AgentDefinition(
    /** 预设模板 ID（引用 AgentPresetRegistry 中的预设） */
    @SerialName("preset")
    val preset: String? = null,

    /** 引用 workflow.agents 中已声明的 agent 名称（主要用于 agent_based.orchestrator） */
    @SerialName("agent")
    val agentRef: String? = null,

    /** 覆盖参数（与 ConfigurationParameter 兼容的 key-value 映射） */
    @SerialName("overrides")
    @kotlinx.serialization.Serializable(with = YamlJsonElementMapSerializer::class)
    val overrides: Map<String, JsonElement> = emptyMap(),

    // ============================================================
    // 向后兼容字段（已弃用，推荐使用 preset + overrides）
    // 如果同时提供了 preset/overrides 和下列字段，preset/overrides 优先
    // ============================================================

    @SerialName("type")
    val type: String = "universal_agent",

    @SerialName("strategy")
    val strategy: String = "just_work_parallel",

    @SerialName("name")
    val name: String? = null,

    @SerialName("system_prompt")
    val systemPrompt: String? = null,

    @SerialName("tools")
    val tools: List<String> = emptyList(),

    @SerialName("llm")
    val llm: LLMConfiguration? = null,

    @SerialName("llm_provider_keys")
    val llmProviderKeys: Map<String, String> = emptyMap(),

    @SerialName("retry_max_attempts")
    val retryMaxAttempts: Int? = null,

    @SerialName("retry_initial_delay")
    val retryInitialDelay: Int? = null,

    @SerialName("retry_max_delay")
    val retryMaxDelay: Int? = null,

    @SerialName("sub_agent_strategy")
    val subAgentStrategy: String? = null,

    @SerialName("strategy_name")
    val strategyName: String? = null,

    @SerialName("max_iterations")
    val maxIterations: Int? = null,

    @SerialName("reasoning_interval")
    val reasoningInterval: Int? = null,

    @SerialName("show_reasoning")
    val showReasoning: Boolean? = null,

    @SerialName("num_choices")
    val numChoices: Int? = null,

    @SerialName("disable_cache_for_streaming")
    val disableCacheForStreaming: Boolean? = null,

    @SerialName("cache_policy")
    val cachePolicy: String? = null,

    @SerialName("memory_cache_max_entries")
    val memoryCacheMaxEntries: Int? = null,

    @SerialName("file_cache_storage")
    val fileCacheStorage: String? = null,

    @SerialName("max_files")
    val maxFiles: Int? = null,

    @SerialName("redis_client_url")
    val redisClientUrl: String? = null,

    @SerialName("redis_cache_prefix")
    val redisCachePrefix: String? = null,

    @SerialName("redis_duration")
    val redisDuration: String? = null,

    @SerialName("persistence_storage_type")
    val persistenceStorageType: String? = null,

    @SerialName("persistence_namespace")
    val persistenceNamespace: String? = null,

    @SerialName("persistence_collection_prefix")
    val persistenceCollectionPrefix: String? = null,

    @SerialName("enable_persistence")
    val enablePersistence: Boolean? = null,

    @SerialName("persistence_storage_root")
    val persistenceStorageRoot: String? = null,

    @SerialName("sql_dialect")
    val sqlDialect: String? = null,

    @SerialName("sql_jdbc_url")
    val sqlJdbcUrl: String? = null,

    @SerialName("sql_database_path")
    val sqlDatabasePath: String? = null,

    @SerialName("sql_username")
    val sqlUsername: String? = null,

    @SerialName("sql_password")
    val sqlPassword: String? = null,

    @SerialName("sql_table_name")
    val sqlTableName: String? = null,

    @SerialName("mongo_connection_string")
    val mongoConnectionString: String? = null,

    @SerialName("mongo_db_name")
    val mongoDbName: String? = null,

    @SerialName("mongo_collection_prefix")
    val mongoCollectionPrefix: String? = null,

    @SerialName("mongo_document_collection")
    val mongoDocumentCollection: String? = null,

    @SerialName("session_id")
    val sessionId: String? = null,

    /**
     * Session ID 生成策略，控制 agent 的 session 隔离方式：
     * - "auto"（默认）：{executionId}:{agentName}:{stepName}，每次执行每个步骤完全隔离
     * - "per_execution"：{executionId}:{agentName}，同一 agent 在同一执行中共享
     * - "per_agent"：{workflowName}:{agentName}，跨执行共享（支持历史累积/长期记忆）
     * - "fixed"：使用 sessionId 字段指定的固定值
     */
    @SerialName("session_id_strategy")
    val sessionIdStrategy: String? = null,

    @SerialName("history_enabled")
    val historyEnabled: Boolean? = null,

    @SerialName("history_storage_root")
    val historyStorageRoot: String? = null,

    @SerialName("restore_session_id")
    val restoreSessionId: String? = null,

    @SerialName("history_max_messages")
    val historyMaxMessages: Int? = null,

    @SerialName("enable_langfuse_tracing")
    val enableLangfuseTracing: Boolean? = null,

    @SerialName("langfuse_url")
    val langfuseUrl: String? = null,

    @SerialName("langfuse_public_key")
    val langfusePublicKey: String? = null,

    @SerialName("langfuse_secret_key")
    val langfuseSecretKey: String? = null,

    @SerialName("clawhub_base_url")
    val clawhubBaseUrl: String? = null,

    @SerialName("clawhub_cache_dir")
    val clawhubCacheDir: String? = null,

    @SerialName("PLAYWRIGHT_HEADLESS")
    val playwrightHeadless: Boolean? = null,

    @SerialName("PLAYWRIGHT_USER_AGENT")
    val playwrightUserAgent: String? = null,

    @SerialName("PLAYWRIGHT_ARGS")
    val playwrightArgs: List<String>? = null,

    // ============================================================
    // Skills 配置
    // ============================================================

    /** Skills 子系统配置（skillsPath、enabledSkills、hooksEnabled 等） */
    @SerialName("skills_config")
    val skillsConfig: SkillsConfiguration? = null,

    /** MCP 外部服务配置 */
    @SerialName("mcp_servers")
    val mcpServers: Map<String, com.fartech.agents.commons.McpServerConfig>? = null,

    /** Agent 工作目录 */
    @SerialName("working_dir")
    val workingDir: String? = null,

    /** Hooks 总开关 */
    @SerialName("hooks_enabled")
    val hooksEnabled: Boolean? = null,

    /** Agent 名称别名 */
    @SerialName("agent_name")
    val agentName: String? = null,

    /** 历史消息压缩配置 */
    @SerialName("history_compression")
    val historyCompression: HistoryCompressionConfig? = null
) {
    /**
     * 判断是否使用预设模式
     */
    val isPresetMode: Boolean get() = agentRef != null || preset != null || (overrides.isNotEmpty() && !hasLegacyConfiguration())

    /**
     * 解析并合并预设和覆盖参数，生成最终的 ConfigurationParameter 兼容参数映射。
     *
     * 优先级：overrides > preset defaults > legacy fields
     */
    fun resolveParameters(referencedAgents: Map<String, AgentDefinition>? = null): Map<String, JsonElement> {
        return resolveParametersInternal(referencedAgents, mutableSetOf())
    }

    private fun resolveParametersInternal(
        referencedAgents: Map<String, AgentDefinition>?,
        visitedAgentRefs: MutableSet<String>
    ): Map<String, JsonElement> {
        if (agentRef != null) {
            val referencedAgent = referencedAgents?.get(agentRef)
            if (referencedAgent != null) {
                require(visitedAgentRefs.add(agentRef)) {
                    "Circular agent reference detected while resolving '$agentRef'"
                }
                return referencedAgent
                    .resolveParametersInternal(referencedAgents, visitedAgentRefs)
                    .toMutableMap()
                    .apply { putAll(overrides) }
            }
            return overrides
        }

        if (preset != null) {
            // 预设模式：使用预设 + overrides
            return AgentPresetRegistry.resolveParameters(preset, overrides)
        }

        if (overrides.isNotEmpty() && !hasLegacyConfiguration()) {
            return overrides
        }

        // 向后兼容模式：将旧字段转换为参数映射
        return buildLegacyParameters().toMutableMap().apply {
            putAll(overrides)
        }
    }

    private fun hasLegacyConfiguration(): Boolean {
        return type != "universal_agent" ||
                strategy != "just_work_parallel" ||
                name != null ||
                systemPrompt != null ||
                tools.isNotEmpty() ||
                llm != null ||
                llmProviderKeys.isNotEmpty() ||
                retryMaxAttempts != null ||
                retryInitialDelay != null ||
                retryMaxDelay != null ||
                subAgentStrategy != null ||
                strategyName != null ||
                maxIterations != null ||
                reasoningInterval != null ||
                showReasoning != null ||
                numChoices != null ||
                disableCacheForStreaming != null ||
                cachePolicy != null ||
                memoryCacheMaxEntries != null ||
                fileCacheStorage != null ||
                maxFiles != null ||
                redisClientUrl != null ||
                redisCachePrefix != null ||
                redisDuration != null ||
                historyEnabled != null ||
                sessionId != null ||
                sessionIdStrategy != null ||
                historyStorageRoot != null ||
                restoreSessionId != null ||
                historyMaxMessages != null ||
                persistenceStorageType != null ||
                persistenceNamespace != null ||
                persistenceCollectionPrefix != null ||
                enablePersistence != null ||
                persistenceStorageRoot != null ||
                sqlDialect != null ||
                sqlJdbcUrl != null ||
                sqlDatabasePath != null ||
                sqlUsername != null ||
                sqlPassword != null ||
                sqlTableName != null ||
                mongoConnectionString != null ||
                mongoDbName != null ||
                mongoCollectionPrefix != null ||
                mongoDocumentCollection != null ||
                enableLangfuseTracing != null ||
                langfuseUrl != null ||
                langfusePublicKey != null ||
                langfuseSecretKey != null ||
                clawhubBaseUrl != null ||
                clawhubCacheDir != null ||
                playwrightHeadless != null ||
                playwrightUserAgent != null ||
                playwrightArgs != null ||
                skillsConfig != null ||
                mcpServers != null ||
                workingDir != null ||
                hooksEnabled != null ||
                agentName != null
    }

    /**
     * 将向后兼容的字段转换为参数映射
     */
    private fun buildLegacyParameters(): Map<String, JsonElement> {
        val params = mutableMapOf<String, JsonElement>()

        params["type"] = JsonPrimitive(type)
        params["strategy"] = JsonPrimitive(strategy)

        name?.let { params["name"] = JsonPrimitive(it) }
        systemPrompt?.let { params["system_prompt"] = JsonPrimitive(it) }

        if (tools.isNotEmpty()) {
            params["tool_set"] = JsonArray(tools.map { JsonPrimitive(it) })
        }

        llm?.let {
            val modelEntries = if (!it.models.isNullOrEmpty()) {
                it.models.map { m ->
                    JsonObject(buildMap {
                        put("model", JsonPrimitive(m.model))
                        put("provider", JsonPrimitive(m.provider))
                        m.baseUrl?.let { url -> put("base_url", JsonPrimitive(url)) }
                        m.maxToken?.let { mt -> put("max_token", JsonPrimitive(mt)) }
                        m.isVision?.let { iv -> put("is_vision", JsonPrimitive(iv)) }
                    })
                }
            } else {
                listOf(
                    JsonObject(
                        mapOf(
                            "model" to JsonPrimitive(it.model),
                            "provider" to JsonPrimitive(it.provider)
                        )
                    )
                )
            }
            val fallbackEntry = it.fallback?.let { fb ->
                JsonObject(buildMap {
                    put("model", JsonPrimitive(fb.model))
                    put("provider", JsonPrimitive(fb.provider))
                    fb.baseUrl?.let { url -> put("base_url", JsonPrimitive(url)) }
                    fb.maxToken?.let { mt -> put("max_token", JsonPrimitive(mt)) }
                    fb.isVision?.let { iv -> put("is_vision", JsonPrimitive(iv)) }
                })
            } ?: modelEntries.first()
            val llmConfig = mutableMapOf<String, JsonElement>(
                "models" to JsonArray(modelEntries),
                "fallback" to fallbackEntry,
                "temperature" to JsonPrimitive(it.temperature)
            )
            it.customModels?.takeIf { models -> models.isNotEmpty() }?.let { models ->
                llmConfig["custom_models"] = Json.encodeToJsonElement(
                    ListSerializer(CustomModelDefinition.serializer()),
                    models
                )
            }
            params["llm_config"] = JsonObject(llmConfig)
            it.maxTokens?.let { maxTokens ->
                params["max_tokens"] = JsonPrimitive(maxTokens)
            }
        }

        if (llmProviderKeys.isNotEmpty()) {
            params["llm_provider_keys"] = buildJsonObject {
                llmProviderKeys.forEach { (provider, key) ->
                    put(provider, JsonPrimitive(key))
                }
            }
            llmProviderKeys.forEach { (provider, key) ->
                when (provider.lowercase()) {
                    "openrouter" -> params["openrouter_api_key"] = JsonPrimitive(key)
                    "openai" -> params["openai_api_key"] = JsonPrimitive(key)
                    "anthropic" -> params["anthropic_api_key"] = JsonPrimitive(key)
                    "deepseek" -> params["deepseek_api_key"] = JsonPrimitive(key)
                    "google" -> params["google_api_key"] = JsonPrimitive(key)
                    "ollama" -> params["ollama_api_key"] = JsonPrimitive(key)
                }
            }
        }

        retryMaxAttempts?.let { params["retry_max_attempts"] = JsonPrimitive(it) }
        retryInitialDelay?.let { params["retry_initial_delay"] = JsonPrimitive(it) }
        retryMaxDelay?.let { params["retry_max_delay"] = JsonPrimitive(it) }
        subAgentStrategy?.let { params["sub_agent_strategy"] = JsonPrimitive(it) }
        strategyName?.let { params["strategy_name"] = JsonPrimitive(it) }
        maxIterations?.let { params["max_iterations"] = JsonPrimitive(it) }
        reasoningInterval?.let { params["reasoning_interval"] = JsonPrimitive(it) }
        showReasoning?.let { params["show_reasoning"] = JsonPrimitive(it) }
        numChoices?.let { params["num_choices"] = JsonPrimitive(it) }
        disableCacheForStreaming?.let { params["disable_cache_for_streaming"] = JsonPrimitive(it) }
        cachePolicy?.let { params["cache_policy"] = JsonPrimitive(it) }
        memoryCacheMaxEntries?.let { params["memory_cache_max_entries"] = JsonPrimitive(it) }
        fileCacheStorage?.let { params["file_cache_storage"] = JsonPrimitive(it) }
        maxFiles?.let { params["max_files"] = JsonPrimitive(it) }
        redisClientUrl?.let { params["redis_client_url"] = JsonPrimitive(it) }
        redisCachePrefix?.let { params["redis_cache_prefix"] = JsonPrimitive(it) }
        redisDuration?.let { params["redis_duration"] = JsonPrimitive(it) }
        persistenceStorageType?.let { params["persistence_storage_type"] = JsonPrimitive(it) }
        persistenceNamespace?.let { params["persistence_namespace"] = JsonPrimitive(it) }
        persistenceCollectionPrefix?.let { params["persistence_collection_prefix"] = JsonPrimitive(it) }
        enablePersistence?.let { params["enable_persistence"] = JsonPrimitive(it) }
        persistenceStorageRoot?.let { params["persistence_storage_root"] = JsonPrimitive(it) }
        sqlDialect?.let { params["sql_dialect"] = JsonPrimitive(it) }
        sqlJdbcUrl?.let { params["sql_jdbc_url"] = JsonPrimitive(it) }
        sqlDatabasePath?.let { params["sql_database_path"] = JsonPrimitive(it) }
        sqlUsername?.let { params["sql_username"] = JsonPrimitive(it) }
        sqlPassword?.let { params["sql_password"] = JsonPrimitive(it) }
        sqlTableName?.let { params["sql_table_name"] = JsonPrimitive(it) }
        mongoConnectionString?.let { params["mongo_connection_string"] = JsonPrimitive(it) }
        mongoDbName?.let { params["mongo_db_name"] = JsonPrimitive(it) }
        mongoCollectionPrefix?.let { params["mongo_collection_prefix"] = JsonPrimitive(it) }
        mongoDocumentCollection?.let { params["mongo_document_collection"] = JsonPrimitive(it) }
        sessionId?.let { params["session_id"] = JsonPrimitive(it) }
        sessionIdStrategy?.let { params["session_id_strategy"] = JsonPrimitive(it) }
        historyEnabled?.let { params["history_enabled"] = JsonPrimitive(it) }
        historyStorageRoot?.let { params["history_storage_root"] = JsonPrimitive(it) }
        restoreSessionId?.let { params["restore_session_id"] = JsonPrimitive(it) }
        historyMaxMessages?.let { params["history_max_messages"] = JsonPrimitive(it) }
        enableLangfuseTracing?.let { params["enable_langfuse_tracing"] = JsonPrimitive(it) }
        langfuseUrl?.let { params["langfuse_url"] = JsonPrimitive(it) }
        langfusePublicKey?.let { params["langfuse_public_key"] = JsonPrimitive(it) }
        langfuseSecretKey?.let { params["langfuse_secret_key"] = JsonPrimitive(it) }
        clawhubBaseUrl?.let { params["clawhub_base_url"] = JsonPrimitive(it) }
        clawhubCacheDir?.let { params["clawhub_cache_dir"] = JsonPrimitive(it) }
        playwrightHeadless?.let { params["PLAYWRIGHT_HEADLESS"] = JsonPrimitive(it) }
        playwrightUserAgent?.let { params["PLAYWRIGHT_USER_AGENT"] = JsonPrimitive(it) }
        playwrightArgs?.let { params["PLAYWRIGHT_ARGS"] = JsonArray(it.map { arg -> JsonPrimitive(arg) }) }

        // Skills 配置：序列化为 JSON 对象传递给 createSkillManager
        skillsConfig?.let { sc ->
            val scMap = mutableMapOf<String, JsonElement>()
            scMap["skillsPath"] = JsonPrimitive(sc.skillsPath)
            scMap["enabled"] = JsonPrimitive(sc.enabled)
            sc.skillWhitelistMode?.let { scMap["skillWhitelistMode"] = JsonPrimitive(it) }
            scMap["autoDiscovery"] = JsonPrimitive(sc.autoDiscovery)
            scMap["maxSkillsPerRequest"] = JsonPrimitive(sc.maxSkillsPerRequest)
            scMap["progressiveDisclosure"] = JsonPrimitive(sc.progressiveDisclosure)
            scMap["builtinSkillsEnabled"] = JsonPrimitive(sc.builtinSkillsEnabled)
            scMap["hooksEnabled"] = JsonPrimitive(sc.hooksEnabled)
            scMap["hookScriptExecutionEnabled"] = JsonPrimitive(sc.hookScriptExecutionEnabled)
            scMap["hookTimeoutSeconds"] = JsonPrimitive(sc.hookTimeoutSeconds)
            scMap["scanStandardPaths"] = JsonPrimitive(sc.scanStandardPaths)
            scMap["requireProjectTrust"] = JsonPrimitive(sc.requireProjectTrust)
            scMap["autoSearchDownload"] = JsonPrimitive(sc.autoSearchDownload)
            scMap["autoUpgrade"] = JsonPrimitive(sc.autoUpgrade)
            scMap["materializeRuntimeSkills"] = JsonPrimitive(sc.materializeRuntimeSkills)
            scMap["maxAttachmentSize"] = JsonPrimitive(sc.maxAttachmentSize)
            scMap["maxSkillContentSize"] = JsonPrimitive(sc.maxSkillContentSize)
            if (sc.enabledSkills.isNotEmpty()) {
                scMap["enabledSkills"] = JsonArray(sc.enabledSkills.map { JsonPrimitive(it) })
            }
            if (sc.disabledSkills.isNotEmpty()) {
                scMap["disabledSkills"] = JsonArray(sc.disabledSkills.map { JsonPrimitive(it) })
            }
            if (sc.notLoadSkills.isNotEmpty()) {
                scMap["notLoadSkills"] = JsonArray(sc.notLoadSkills.map { JsonPrimitive(it) })
            }
            if (sc.additionalSkillPaths.isNotEmpty()) {
                scMap["additionalSkillPaths"] = JsonArray(sc.additionalSkillPaths.map { JsonPrimitive(it) })
            }
            if (sc.allowedScriptTypes != listOf("py", "js", "ts", "kts")) {
                scMap["allowedScriptTypes"] = JsonArray(sc.allowedScriptTypes.map { JsonPrimitive(it) })
            }
            sc.workspaceDir?.let { scMap["workspaceDir"] = JsonPrimitive(it) }
            sc.userGlobalHooksDir?.let { scMap["userGlobalHooksDir"] = JsonPrimitive(it) }
            if (sc.trustedProjects.isNotEmpty()) {
                scMap["trustedProjects"] = JsonArray(sc.trustedProjects.map { JsonPrimitive(it) })
            }
            params["skills_config"] = JsonObject(scMap)
        }

        mcpServers?.let {
            params["mcp_servers"] = JsonObject(
                it.mapValues { (_, config) ->
                    Json.encodeToJsonElement(com.fartech.agents.commons.McpServerConfig.serializer(), config)
                }
            )
        }
        workingDir?.let { params["working_dir"] = JsonPrimitive(it) }
        hooksEnabled?.let { params["hooks_enabled"] = JsonPrimitive(it) }
        agentName?.let { params["agent_name"] = JsonPrimitive(it) }
        historyCompression?.let {
            params["history_compression"] = Json.encodeToJsonElement(HistoryCompressionConfig.serializer(), it)
        }

        return params
    }
}

/**
 * LLM 模型条目（用于多模型配置）
 */
@Serializable
data class LLMModelEntry(
    @SerialName("model")
    val model: String,

    @SerialName("provider")
    val provider: String,

    @SerialName("base_url")
    val baseUrl: String? = null,

    @SerialName("max_token")
    val maxToken: Int? = null,

    @SerialName("is_vision")
    val isVision: Boolean? = null
)

/**
 * LLM 配置
 */
@Serializable
data class LLMConfiguration(
    @SerialName("model")
    val model: String,

    @SerialName("provider")
    val provider: String,

    @SerialName("temperature")
    val temperature: Double = 1.0,

    @SerialName("max_tokens")
    val maxTokens: Int? = null,

    @SerialName("custom_models")
    val customModels: List<com.fartech.agents.commons.CustomModelDefinition>? = null,

    /** 多模型列表（优先级从高到低） */
    @SerialName("models")
    val models: List<LLMModelEntry>? = null,

    /** 回退模型 */
    @SerialName("fallback")
    val fallback: LLMModelEntry? = null
)

/**
 * Group Chat 配置
 *
 * 允许多个 agent 在共享对话空间中协作讨论。
 *
 * YAML 示例：
 * ```yaml
 * - step: team_discussion
 *   group_chat:
 *     participants: [coder, reviewer, tester]
 *     moderator: reviewer
 *     max_rounds: 8
 *     speaker_selection: round_robin
 *     termination_keyword: "CONSENSUS_REACHED"
 *     initial_message: "请讨论这个方案的可行性"
 *     summary_agent: reviewer
 *   depends_on: [research]
 * ```
 */
@Serializable
data class GroupChatConfig(
    /** 参与对话的 agent 名称列表（引用 agents 中定义的 agent） */
    @SerialName("participants")
    val participants: List<String>,

    /** 可选的主持人 agent（负责引导讨论方向，也是 participants 之一） */
    @SerialName("moderator")
    val moderator: String? = null,

    /** 最大对话轮次（每个 agent 发言一次算一轮） */
    @SerialName("max_rounds")
    val maxRounds: Int = 10,

    /** 发言者选择策略：round_robin（轮流）、random（随机） */
    @SerialName("speaker_selection")
    val speakerSelection: String = "round_robin",

    /** 终止关键词（满足参与约束时生效；若设置 moderator，则仅 moderator 可结束对话） */
    @SerialName("termination_keyword")
    val terminationKeyword: String? = "CONSENSUS_REACHED",

    /** 初始话题/消息（注入给第一个发言者） */
    @SerialName("initial_message")
    val initialMessage: String? = null,

    /** 汇总 agent（对话结束后由此 agent 生成摘要，默认为最后一个发言者） */
    @SerialName("summary_agent")
    val summaryAgent: String? = null
) {
    init {
        require(participants.size >= 2) { "Group chat requires at least 2 participants" }
        require(maxRounds > 0) { "max_rounds must be positive" }
        require(speakerSelection in listOf("round_robin", "random")) {
            "speaker_selection must be 'round_robin' or 'random', got '$speakerSelection'"
        }
        if (moderator != null) {
            require(moderator in participants) {
                "Moderator '$moderator' must be one of the participants: $participants"
            }
        }
        if (summaryAgent != null) {
            require(summaryAgent in participants) {
                "Summary agent '$summaryAgent' must be one of the participants: $participants"
            }
        }
    }
}

/**
 * Group Chat 中的单条消息
 */
@Serializable
data class GroupChatMessage(
    /** 发言者 agent 名称 */
    @SerialName("speaker")
    val speaker: String,

    /** 消息内容 */
    @SerialName("content")
    val content: String,

    /** 第几轮对话（从 1 开始） */
    @SerialName("round")
    val round: Int,

    /** 时间戳 */
    @SerialName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 工作流步骤
 *
 * 支持六种模式：
 * 1. 单 Agent 模式（传统）：指定 agent + input
 * 2. Group Chat 模式：指定 group_chat 配置，多个 agent 协作对话
 * 3. Agent-based 模式：指定 agent_based 配置，Orchestrator 动态编排
 * 4. Code 模式：指定 code 配置，执行确定性代码（不经过 LLM）
 * 5. Classifier 模式：指定 classifier 配置，LLM 智能分类路由
 * 6. State Machine 模式：指定 state_machine 配置，在 DAG 节点内执行复合状态机
 */
@Serializable
data class WorkflowStep(
    @SerialName("step")
    val step: String,

    /** 单 agent 模式下引用的 agent 名称（group_chat 模式下可为空） */
    @SerialName("agent")
    val agent: String? = null,

    /** 单 agent 模式下的输入（group_chat 模式下可为空，使用 group_chat.initial_message） */
    @SerialName("input")
    val input: String? = null,

    /** Group Chat 配置（与 agent/input 互斥） */
    @SerialName("group_chat")
    val groupChat: GroupChatConfig? = null,

    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),

    @SerialName("condition")
    val condition: String? = null,

    @SerialName("on_success")
    val onSuccess: List<TransitionAction> = emptyList(),

    @SerialName("on_failure")
    val onFailure: List<TransitionAction> = emptyList(),

    @SerialName("parallel")
    val parallel: ParallelExecution? = null,

    @SerialName("manual_approval")
    val manualApproval: ManualApprovalConfig? = null,

    @SerialName("priority")
    val priority: Int = 0,

    @SerialName("timeout_seconds")
    val timeoutSeconds: Int? = null,

    @SerialName("state")
    val state: String? = null,

    @SerialName("retry")
    val retry: RetryConfig? = null,

    @SerialName("timeout")
    val timeout: String? = null,

    /** 循环执行配置（可选） */
    @SerialName("repeat_until")
    val repeatUntil: RepeatUntilConfig? = null,

    /** Agent-based 动态编排配置（与 agent/input、group_chat、code、classifier 互斥） */
    @SerialName("agent_based")
    val agentBased: AgentBasedConfig? = null,

    /** 确定性代码执行配置（与 agent/input、group_chat、agent_based、classifier 互斥） */
    @SerialName("code")
    val code: CodeStepConfig? = null,

    /** 智能分类路由配置（与 agent/input、group_chat、agent_based、code 互斥） */
    @SerialName("classifier")
    val classifier: ClassifierConfig? = null,

    /** 状态机复合节点配置（与其他执行模式互斥） */
    @SerialName("state_machine")
    val stateMachine: StateMachineConfig? = null,

    /** 通用结构化输出提取（可用于所有产出输出的步骤类型） */
    @SerialName("extract")
    val extract: List<ExtractConfig>? = null,

    /** Koog 结构化最终输出配置（单 agent 步骤可选） */
    @SerialName("structured_output")
    val structuredOutput: StructuredOutputConfig? = null,

    /** 动态列表遍历配置（可选，对变量中的列表逐项执行步骤） */
    @SerialName("iterate_over")
    val iterateOver: IterateOverConfig? = null,

    /** 多路径聚合策略（可选，合并多个依赖步骤的输出） */
    @SerialName("aggregate")
    val aggregate: AggregateConfig? = null,

    /**
     * 子工作流调用配置(与 agent/input、group_chat、agent_based、code、classifier、state_machine 互斥)。
     * 把另一个已保存的 workflow 作为一个原子步骤引用,详见 [SubWorkflowConfig]。
     */
    @SerialName("sub_workflow")
    val subWorkflow: SubWorkflowConfig? = null
) {
    /** 是否为 Group Chat 步骤 */
    val isGroupChat: Boolean get() = groupChat != null

    /** 是否为 Agent-based 动态编排步骤 */
    val isAgentBased: Boolean get() = agentBased != null

    /** 是否为确定性代码步骤 */
    val isCode: Boolean get() = code != null

    /** 是否为分类路由步骤 */
    val isClassifier: Boolean get() = classifier != null

    /** 是否为状态机步骤 */
    val isStateMachine: Boolean get() = stateMachine != null

    /** 是否为子工作流步骤 */
    val isSubWorkflow: Boolean get() = subWorkflow != null

    /** 获取此步骤涉及的所有 agent 名称（用于验证） */
    val referencedAgents: List<String>
        get() = when {
            isGroupChat -> groupChat?.participants.orEmpty()
            isAgentBased -> agentBased?.participants.orEmpty()
            isCode -> emptyList()
            isClassifier -> listOfNotNull(classifier?.agent)
            isStateMachine -> stateMachine?.referencedAgents.orEmpty()
            isSubWorkflow -> emptyList()  // 子 workflow 的 agents 由 child 自己管理,父不校验
            else -> listOfNotNull(agent)
        }

    /** 获取步骤的显示用 agent 名称 */
    val displayAgentName: String
        get() = when {
            isGroupChat -> "group_chat(${groupChat?.participants?.joinToString(",") ?: "?"})"
            isAgentBased -> "agent_based(${agentBased?.participants?.joinToString(",") ?: "?"})"
            isCode -> "code(${code?.language ?: "?"})"
            isClassifier -> "classifier(${classifier?.agent ?: "?"})"
            isStateMachine -> "state_machine(${stateMachine?.states?.size ?: 0} states)"
            isSubWorkflow -> "sub_workflow(${subWorkflow?.identityLabel() ?: "?"})"
            else -> agent ?: "unknown"
        }

    init {
        require(step.isNotBlank()) { "Step name cannot be blank" }
        // 必须指定 agent/input、group_chat、agent_based、code、classifier、state_machine 或 sub_workflow（七选一）
        val modeCount = listOf(
            agent != null && agent.isNotBlank(),
            groupChat != null,
            agentBased != null,
            code != null,
            classifier != null,
            stateMachine != null,
            subWorkflow != null
        ).count { it }
        require(modeCount == 1) {
            "Step '${step}': must specify exactly one of 'agent'+'input', 'group_chat', 'agent_based', 'code', 'classifier', 'state_machine', or 'sub_workflow'"
        }
        // 单 agent 模式需要 input
        if (agent != null && agent.isNotBlank()) {
            require(!input.isNullOrBlank()) { "Step '${step}': 'input' is required for single-agent steps" }
        }
    }
}

/**
 * 转换动作（成功或失败后的行为）
 */
@Serializable
data class TransitionAction(
    @SerialName("next")
    val next: String? = null,

    @SerialName("stop")
    val stop: Boolean = false,

    @SerialName("notify")
    val notify: String? = null,

    @SerialName("message")
    val message: String? = null,

    @SerialName("rollback")
    val rollback: String? = null,

    @SerialName("parallel")
    val parallel: List<String>? = null,

    // State machine actions
    @SerialName("action")
    val action: String? = null,

    @SerialName("key")
    val key: String? = null,

    @SerialName("value")
    val value: String? = null
)

/**
 * 并行执行配置
 */
@Serializable
data class ParallelExecution(
    @SerialName("tasks")
    val tasks: List<String>,

    @SerialName("aggregate_results")
    val aggregateResults: Boolean = true,

    @SerialName("max_parallel")
    val maxParallel: Int? = null
) {
    init {
        require(tasks.isNotEmpty()) { "Parallel execution must have at least one task" }
    }
}

/**
 * 循环执行配置（repeat_until）
 *
 * 支持步骤的循环执行，每次迭代后通过 evaluate_agent 评估条件，
 * 直到条件满足或达到最大迭代次数。
 *
 * YAML 格式:
 * ```yaml
 * - step: refine_draft
 *   agent: writer
 *   input: "改进文档..."
 *   repeat_until:
 *     condition: "quality_score >= 8"
 *     max_iterations: 5
 *     evaluate_agent: reviewer
 *     evaluate_prompt: "评估质量并输出 quality_score=N"
 * ```
 */
@Serializable
data class RepeatUntilConfig(
    /** 终止条件表达式（使用与 step condition 相同的语法） */
    @SerialName("condition")
    val condition: String,

    /** 最大迭代次数（安全上限，防止无限循环） */
    @SerialName("max_iterations")
    val maxIterations: Int = 5,

    /** 评估 agent 名称（每次迭代后调用此 agent 评估条件） */
    @SerialName("evaluate_agent")
    val evaluateAgent: String? = null,

    /** 评估 prompt 模板（支持 {{steps.xxx.output}} 变量引用） */
    @SerialName("evaluate_prompt")
    val evaluatePrompt: String? = null,

    /** 评估结果中提取变量的正则模式（如 "quality_score=(\\d+)"） */
    @SerialName("extract_pattern")
    val extractPattern: String? = null,

    /** 提取的变量名（与 extract_pattern 配合使用，将匹配结果存入此变量） */
    @SerialName("extract_variable")
    val extractVariable: String? = null
) {
    init {
        require(condition.isNotBlank()) { "repeat_until condition cannot be blank" }
        require(maxIterations > 0) { "repeat_until max_iterations must be > 0" }
        // Phase 11 hardening: cap upper bound so a typo or adversarial workflow YAML
        // can't park a step in a loop for hours. 1000 is generous — quality-review
        // loops in real workflows use 3–10 iterations.
        require(maxIterations <= MAX_REPEAT_UNTIL_ITERATIONS) {
            "repeat_until max_iterations $maxIterations exceeds hard cap $MAX_REPEAT_UNTIL_ITERATIONS"
        }
    }

    companion object {
        /** Hard upper bound on [maxIterations]. */
        const val MAX_REPEAT_UNTIL_ITERATIONS = 1_000
    }
}

/**
 * Agent-based 动态编排配置
 *
 * 由 Orchestrator Agent（LLM）动态决策任务分配和执行顺序，
 * 而非静态 DAG 定义。Orchestrator 通过工具调用委派任务给 Worker Agent。
 *
 * YAML 格式:
 * ```yaml
 * - step: dynamic_review
 *   agent_based:
 *     orchestrator:
 *       preset: universal
 *       overrides:
 *         system_prompt: "你是工作流编排者..."
 *     participants:
 *       - designer
 *       - engineer
 *     goal: "对需求进行多角度评审"
 *     max_steps: 15
 *     budget_tokens: 500000
 * ```
 */
@Serializable
data class AgentBasedConfig(
    /** Orchestrator Agent 定义（负责动态编排决策） */
    @SerialName("orchestrator")
    val orchestrator: AgentDefinition,

    /** 参与编排的 Worker Agent 名称列表（引用 agents 中定义的 agent） */
    @SerialName("participants")
    val participants: List<String>,

    /** 自然语言目标描述（替代固定的 input） */
    @SerialName("goal")
    val goal: String,

    /** Orchestrator 最大执行步数（安全上限） */
    @SerialName("max_steps")
    val maxSteps: Int = 20,

    /** Token 预算上限（0 = 不限制） */
    @SerialName("budget_tokens")
    val budgetTokens: Long = 0,

    /** 执行超时（秒） */
    @SerialName("timeout_seconds")
    val timeoutSeconds: Int? = null
) {
    init {
        require(participants.isNotEmpty()) { "agent_based participants cannot be empty" }
        require(goal.isNotBlank()) { "agent_based goal cannot be blank" }
        require(maxSteps > 0) { "agent_based max_steps must be > 0" }
        require(budgetTokens >= 0) { "agent_based budget_tokens must be >= 0" }
    }
}

/**
 * 通用结构化输出提取配置
 *
 * 从步骤输出中提取值并存入工作流变量，可用于所有产出输出的步骤类型。
 * 支持正则表达式提取和 JSON path 提取。
 *
 * YAML 格式:
 * ```yaml
 * extract:
 *   - pattern: "score=(\\d+)"
 *     variable: quality_score
 *   - json_path: "$.result.status"
 *     variable: status
 * ```
 */
@Serializable
data class ExtractConfig(
    /** 正则表达式模式（捕获组 1 的值将被提取） */
    @SerialName("pattern")
    val pattern: String? = null,

    /** JSON path 表达式（如 "$.result.status"，从 JSON 输出中提取） */
    @SerialName("json_path")
    val jsonPath: String? = null,

    /** 提取值存入的变量名 */
    @SerialName("variable")
    val variable: String
) {
    init {
        require(variable.isNotBlank()) { "extract variable name cannot be blank" }
        require(pattern != null || jsonPath != null) {
            "extract must specify either 'pattern' (regex) or 'json_path'"
        }
        require(pattern == null || jsonPath == null) {
            "extract cannot specify both 'pattern' and 'json_path' simultaneously"
        }
    }
}

/**
 * Koog structured output 配置。
 *
 * 用于单 agent 步骤：agent 可以照常调用工具读取数据，但最终回复会按 schema
 * 解析成 Kotlin DTO；如果配置了 write_to，executor 会用代码把 DTO 序列化落盘。
 */
@Serializable
data class StructuredOutputConfig(
    /** 已注册的结构化 schema 名称，例如 ai_commentary_parts */
    @SerialName("schema")
    val schema: String,

    /** 可选：将结构化结果写入指定文件路径，支持 workflow 模板变量 */
    @SerialName("write_to")
    val writeTo: String? = null,

    /** 可选：写入格式。当前支持 json，预留以后扩展 */
    @SerialName("format")
    val format: String = "json",

    /** 结构化结果为空时是否失败 */
    @SerialName("fail_on_empty")
    val failOnEmpty: Boolean = true
) {
    init {
        require(schema.isNotBlank()) { "structured_output.schema cannot be blank" }
        require(format.lowercase() == "json") { "structured_output.format currently only supports 'json'" }
    }
}

/**
 * 确定性代码执行步骤配置
 *
 * 执行人类预写的代码，不经过 LLM。提供 100% 确定性的执行结果。
 * 支持 Python、JavaScript、TypeScript 和 Bash 脚本。
 *
 * 代码通过环境变量接收输入：
 * - STEP_INPUTS: JSON 格式的所有上游步骤输出
 * - WF_VAR_xxx: 工作流变量（变量名转为大写并加前缀）
 * - STEP_OUTPUT_xxx: 指定步骤的输出（步骤名转为大写并加前缀）
 *
 * 代码通过 stdout 产出输出（最后的 print/console.log/echo 结果即为步骤输出）。
 *
 * YAML 格式:
 * ```yaml
 * - step: validate_report
 *   code:
 *     language: python
 *     script: |
 *       import os, json
 *       report = os.environ.get('STEP_OUTPUT_GENERATE_REPORT', '')
 *       if len(report) < 100:
 *           raise ValueError("报告太短")
 *       print(json.dumps({"valid": True, "word_count": len(report)}))
 *     timeout: 30
 * ```
 */
@Serializable
data class CodeStepConfig(
    /** 编程语言：python, javascript, typescript, bash, ruby, lua, cli */
    @SerialName("language")
    val language: String,

    /** 内联脚本代码 */
    @SerialName("script")
    val script: String? = null,

    /** 外部脚本文件路径（与 script 二选一） */
    @SerialName("script_file")
    val scriptFile: String? = null,

    /** 执行超时（秒，默认 30） */
    @SerialName("timeout")
    val timeout: Int = 30,

    /** 工作目录（可选） */
    @SerialName("working_directory")
    val workingDirectory: String? = null
) {
    companion object {
        val SUPPORTED_LANGUAGES = setOf("python", "javascript", "typescript", "bash", "ruby", "lua", "cli")
    }

    init {
        require(language in SUPPORTED_LANGUAGES) {
            "code step language must be one of: ${SUPPORTED_LANGUAGES.joinToString(", ")}. Got: '$language'"
        }
        require(script != null || scriptFile != null) {
            "code step must specify either 'script' (inline) or 'script_file' (external file path)"
        }
        require(script == null || scriptFile == null) {
            "code step cannot specify both 'script' and 'script_file' simultaneously"
        }
        require(timeout > 0) { "code step timeout must be > 0" }
    }
}

/**
 * 代码前导配置
 *
 * 支持两种模式（互斥）：
 * - `inline`: 直接内联代码字符串
 * - `ref`: 引用外部文件路径（运行时读取文件内容）
 *
 * 配置在 `WorkflowDefinition.code_preamble` 中按编程语言分组，
 * 引擎在执行 code step 时自动将匹配语言的 preamble 拼接到脚本头部。
 *
 * YAML 格式:
 * ```yaml
 * code_preamble:
 *   python:
 *     ref: ./skills/shared_utils.py
 *     description: 共享工具函数
 *   bash:
 *     inline: |
 *       set -euo pipefail
 *       export PATH="/custom/bin:$PATH"
 *     description: Bash 安全默认设置
 * ```
 */
@Serializable
data class CodePreambleConfig(
    /** 内联代码（与 ref 二选一） */
    @SerialName("inline")
    val inline: String? = null,

    /** 引用外部文件路径（与 inline 二选一，运行时读取文件内容） */
    @SerialName("ref")
    val ref: String? = null,

    /** 描述说明（UI 显示用） */
    @SerialName("description")
    val description: String? = null
) {
    init {
        require(inline == null || ref == null) {
            "code_preamble: 'inline' and 'ref' are mutually exclusive — specify one or neither"
        }
    }
}

/**
 * 智能分类路由配置
 *
 * 使用 LLM Agent 对输入进行分类，根据分类结果设置变量，
 * 下游步骤可通过 condition 字段判断走不同分支。
 *
 * YAML 格式:
 * ```yaml
 * - step: classify_request
 *   classifier:
 *     agent: classifier_agent
 *     input: "用户请求：{{var:user_input}}"
 *     categories:
 *       - name: technical
 *         description: "技术问题，如 bug 报告、功能请求"
 *       - name: billing
 *         description: "账单和支付相关问题"
 *       - name: general
 *         description: "一般性咨询"
 *     output_variable: request_category
 * ```
 */
@Serializable
data class ClassifierConfig(
    /** 执行分类的 Agent 名称（引用 agents 中定义的 agent） */
    @SerialName("agent")
    val agent: String,

    /** 分类输入（支持模板变量） */
    @SerialName("input")
    val input: String,

    /** 分类类别列表 */
    @SerialName("categories")
    val categories: List<ClassifierCategory>,

    /** 分类结果存入的变量名 */
    @SerialName("output_variable")
    val outputVariable: String = "classification",

    /** 分类失败时的默认类别（可选） */
    @SerialName("default_category")
    val defaultCategory: String? = null
) {
    init {
        require(agent.isNotBlank()) { "classifier agent cannot be blank" }
        require(input.isNotBlank()) { "classifier input cannot be blank" }
        require(categories.size >= 2) { "classifier must have at least 2 categories" }
        require(outputVariable.isNotBlank()) { "classifier output_variable cannot be blank" }
        defaultCategory?.let { dc ->
            require(categories.any { it.name == dc }) {
                "classifier default_category '$dc' must be one of the defined categories: ${categories.map { it.name }}"
            }
        }
    }
}

/**
 * 分类类别定义
 */
@Serializable
data class ClassifierCategory(
    /** 类别名称（唯一标识符，将作为变量值存储） */
    @SerialName("name")
    val name: String,

    /** 类别描述（帮助 LLM 理解此类别） */
    @SerialName("description")
    val description: String
) {
    init {
        require(name.isNotBlank()) { "classifier category name cannot be blank" }
        require(description.isNotBlank()) { "classifier category description cannot be blank" }
    }
}

/**
 * 动态列表遍历配置
 *
 * 对变量中的列表逐项执行步骤，每次迭代将当前项注入为变量。
 * 列表来源可以是工作流变量（分隔符分割）或上游步骤输出（按行/JSON 数组分割）。
 *
 * YAML 格式:
 * ```yaml
 * - step: fix_each_bug
 *   agent: developer
 *   input: "修复以下 bug: {{var:current_item}}"
 *   iterate_over:
 *     source: "{{steps.find_bugs.output}}"
 *     delimiter: "\n"
 *     item_variable: current_item
 *     index_variable: current_index
 *     max_items: 20
 *     parallel: false
 *     results_variable: fix_results
 * ```
 */
@Serializable
data class IterateOverConfig(
    /** 列表数据源（支持模板变量，如 {{steps.X.output}} 或 {{var:list}}） */
    @SerialName("source")
    val source: String,

    /** 分隔符（默认换行符，也支持 "json_array" 表示 JSON 数组解析） */
    @SerialName("delimiter")
    val delimiter: String = "\n",

    /** 当前项注入的变量名（每次迭代更新） */
    @SerialName("item_variable")
    val itemVariable: String = "current_item",

    /** 当前索引注入的变量名（从 0 开始） */
    @SerialName("index_variable")
    val indexVariable: String = "current_index",

    /** 最大遍历项数（安全上限，0 = 不限制） */
    @SerialName("max_items")
    val maxItems: Int = 0,

    /** 是否并行遍历（默认串行） */
    @SerialName("parallel")
    val parallel: Boolean = false,

    /** 并行时的最大并发数 */
    @SerialName("max_parallel")
    val maxParallel: Int? = null,

    /** 所有迭代结果聚合后存入的变量名（可选） */
    @SerialName("results_variable")
    val resultsVariable: String? = null
) {
    init {
        require(source.isNotBlank()) { "iterate_over source cannot be blank" }
        require(itemVariable.isNotBlank()) { "iterate_over item_variable cannot be blank" }
        require(maxItems >= 0) { "iterate_over max_items must be >= 0" }
    }
}

/**
 * 多路径变量聚合配置
 *
 * 合并多个依赖步骤的输出为单一结果，支持多种聚合策略。
 *
 * YAML 格式:
 * ```yaml
 * - step: merge_reviews
 *   agent: summarizer
 *   input: "汇总以下评审意见：{{var:aggregated_input}}"
 *   aggregate:
 *     sources:
 *       - "{{steps.review_security.output}}"
 *       - "{{steps.review_performance.output}}"
 *       - "{{steps.review_ux.output}}"
 *     strategy: concat
 *     separator: "\n\n---\n\n"
 *     output_variable: aggregated_input
 * ```
 */
@Serializable
data class AggregateConfig(
    /** 聚合数据源列表（支持模板变量） */
    @SerialName("sources")
    val sources: List<String>,

    /** 聚合策略：concat（拼接）、json_array（JSON 数组）、numbered_list（编号列表）、pick_longest（取最长）、pick_shortest（取最短） */
    @SerialName("strategy")
    val strategy: String = "concat",

    /** 拼接分隔符（仅 concat 策略使用） */
    @SerialName("separator")
    val separator: String = "\n\n---\n\n",

    /** 聚合结果存入的变量名 */
    @SerialName("output_variable")
    val outputVariable: String
) {
    companion object {
        val SUPPORTED_STRATEGIES = setOf("concat", "json_array", "numbered_list", "pick_longest", "pick_shortest")
    }

    init {
        require(sources.size >= 2) { "aggregate must have at least 2 sources" }
        require(strategy in SUPPORTED_STRATEGIES) {
            "aggregate strategy must be one of: ${SUPPORTED_STRATEGIES.joinToString(", ")}. Got: '$strategy'"
        }
        require(outputVariable.isNotBlank()) { "aggregate output_variable cannot be blank" }
    }
}

/**
 * 工作流级知识库配置
 *
 * 提供跨步骤的共享 RAG 知识库，支持：
 * - 预加载参考文档（source_files）
 * - 自动索引步骤输出（auto_index_outputs）
 * - 所有 Agent 可通过 rag_tools 语义检索共享知识
 *
 * YAML 格式:
 * ```yaml
 * knowledge_base:
 *   enabled: true
 *   storage_dir: "./workflow-kb"
 *   embedding_model: "text-embedding-3-small"
 *   auto_index_outputs: true
 *   chunk_size: 1000
 *   chunk_overlap: 200
 *   max_indexed_documents: 100
 *   max_total_chunks: 1000
 *   source_files:
 *     - path: "./docs/reference.md"
 *       tags: "reference,spec"
 *     - path: "./docs/guidelines.md"
 * ```
 */
@Serializable
data class KnowledgeBaseConfig(
    /** 是否启用工作流级知识库 */
    @SerialName("enabled")
    val enabled: Boolean = true,

    /** 知识库存储目录（默认基于执行 ID 隔离） */
    @SerialName("storage_dir")
    val storageDir: String? = null,

    /** 嵌入模型名称（默认 text-embedding-3-small） */
    @SerialName("embedding_model")
    val embeddingModel: String = "text-embedding-3-small",

    /** 嵌入模型提供商（默认 openai） */
    @SerialName("embedding_provider")
    val embeddingProvider: String = "openai",

    /** 是否自动将每个步骤的输出索引到知识库 */
    @SerialName("auto_index_outputs")
    val autoIndexOutputs: Boolean = true,

    /** 文本分块大小（字符数） */
    @SerialName("chunk_size")
    val chunkSize: Int = 1000,

    /** 分块重叠大小（字符数） */
    @SerialName("chunk_overlap")
    val chunkOverlap: Int = 200,

    /** 最大索引文档数量（安全上限，0 = 不限制） */
    @SerialName("max_indexed_documents")
    val maxIndexedDocuments: Int = 0,

    /** 最大总分块数量（安全上限，0 = 不限制） */
    @SerialName("max_total_chunks")
    val maxTotalChunks: Int = 0,

    /** 预加载的参考文档列表 */
    @SerialName("source_files")
    val sourceFiles: List<KnowledgeBaseSourceFile> = emptyList(),

    /** 是否为每个 Agent 自动注入 rag_tools（即使 Agent 未显式声明 rag_tools） */
    @SerialName("auto_inject_rag_tools")
    val autoInjectRagTools: Boolean = true
) {
    init {
        require(chunkSize > 0) { "knowledge_base chunk_size must be > 0" }
        require(chunkOverlap >= 0) { "knowledge_base chunk_overlap must be >= 0" }
        require(chunkOverlap < chunkSize) { "knowledge_base chunk_overlap must be < chunk_size" }
        require(maxIndexedDocuments >= 0) { "knowledge_base max_indexed_documents must be >= 0" }
        require(maxTotalChunks >= 0) { "knowledge_base max_total_chunks must be >= 0" }
    }
}

/**
 * 知识库预加载文件配置
 */
@Serializable
data class KnowledgeBaseSourceFile(
    /** 文件路径（相对于工作流执行目录或绝对路径） */
    @SerialName("path")
    val path: String,

    /** 可选的标签（逗号分隔） */
    @SerialName("tags")
    val tags: String = ""
) {
    init {
        require(path.isNotBlank()) { "knowledge_base source_file path cannot be blank" }
    }
}

/**
 * 人工审批配置
 */
@Serializable
data class ManualApprovalConfig(
    @SerialName("enabled")
    val enabled: Boolean,

    @SerialName("approvers")
    val approvers: List<String> = emptyList(),

    @SerialName("timeout")
    val timeout: Long = 3600, // seconds

    @SerialName("approval_message")
    val approvalMessage: String? = null,

    /**
     * 可编辑的审批负载分组。每组对应一个 JSON 数组文件,审批人可以勾选要执行的子集
     * 并按列编辑字段(例如改价)。批准后引擎会把审批人提交的子集写入新文件,并通过
     * `output_var` 暴露给下游步骤;下游 `execute_*` 步骤改用 `output_var` 即可只
     * 跑被批准/编辑过的项。
     */
    @SerialName("reviewable_actions")
    val reviewableActions: List<ReviewableActionGroup> = emptyList()
)

/**
 * 单组可审核的 action(例如"加价建议"/"暂停建议")。
 */
@Serializable
data class ReviewableActionGroup(
    /** 分组内部名,例如 raise_bid / lower_bid。同时用作输出文件前缀。 */
    @SerialName("name")
    val name: String,

    /** 来源:指向一个变量,变量值是 JSON 数组文件的绝对路径。 */
    @SerialName("source_var")
    val sourceVar: String,

    /** 批准后要在 context 设置的变量名,值为审批人编辑/勾选后的子集 JSON 文件路径。 */
    @SerialName("output_var")
    val outputVar: String,

    /** 列表标题,UI 渲染用。 */
    @SerialName("title")
    val title: String? = null,

    /**
     * 行身份字段。多行编辑时用此字段在编辑后做对齐;若为 null,前端按数组下标对齐。
     * 不影响后端落盘。
     */
    @SerialName("key_field")
    val keyField: String? = null,

    /** UI 渲染使用的列定义。后端不解读列定义,只透传给前端。 */
    @SerialName("columns")
    val columns: List<ReviewableActionColumn> = emptyList()
)

@Serializable
data class ReviewableActionColumn(
    @SerialName("key")
    val key: String,

    @SerialName("label")
    val label: String? = null,

    /** "string" | "number" | "readonly" — 仅 UI 提示。 */
    @SerialName("type")
    val type: String = "string",

    @SerialName("editable")
    val editable: Boolean = false
)

/**
 * 重试配置
 */
@Serializable
data class RetryConfig(
    @SerialName("max_attempts")
    val maxAttempts: Int = 3,

    @SerialName("backoff")
    val backoff: BackoffStrategy = BackoffStrategy.EXPONENTIAL,

    @SerialName("initial_delay")
    val initialDelay: Long = 1000, // milliseconds

    @SerialName("max_delay")
    val maxDelay: Long = 60000 // milliseconds
) {
    init {
        require(maxAttempts > 0) { "max_attempts must be greater than 0" }
        require(initialDelay > 0) { "initial_delay must be greater than 0" }
        require(maxDelay >= initialDelay) { "max_delay must be >= initial_delay" }
    }
}

/**
 * 退避策略
 */
@Serializable
enum class BackoffStrategy {
    @SerialName("linear")
    LINEAR,

    @SerialName("exponential")
    EXPONENTIAL,

    @SerialName("constant")
    CONSTANT
}

/**
 * 错误处理配置
 */
@Serializable
data class ErrorHandlingConfig(
    @SerialName("max_retries")
    val maxRetries: Int = 3,

    @SerialName("retry_delay")
    val retryDelay: String = "5s",

    @SerialName("on_error")
    val onError: List<TransitionAction> = emptyList(),

    @SerialName("continue_on_error")
    val continueOnError: Boolean = false,

    /**
     * 子工作流最大嵌套深度(默认 8)。超过此深度 [WorkflowExecutor] 在执行到
     * `sub_workflow` 步骤时会抛 [WorkflowExecutionException]。
     */
    @SerialName("max_sub_workflow_depth")
    val maxSubWorkflowDepth: Int? = null
)

/**
 * 超时配置
 */
@Serializable
data class TimeoutConfig(
    @SerialName("total")
    val total: String = "3600s", // 整个工作流总超时

    @SerialName("per_step")
    val perStep: String = "600s" // 每个步骤的默认超时
)

/**
 * 工作流执行结果
 */
data class WorkflowExecutionResult(
    val workflowName: String,
    val success: Boolean,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val error: String? = null,
    val stepResults: Map<String, StepExecutionResult> = emptyMap(),
    val variables: Map<String, Any> = emptyMap(),
    /**
     * 在本次执行过程中被跳过的步骤名集合(condition 不满足、依赖失败时
     * `continue_on_error=true`、state-machine 转移未激活等)。**不是** stepResults
     * 的子集 —— 跳过的步骤没有 StepExecutionResult,只有名字。
     *
     * 用户层(braidrun-web ExecutionService)可以根据这个集合给前端补出
     * `status="SKIPPED"` 的占位 StepResult,避免被静默吞掉。
     *
     * 2026-04-26 加入,默认空集合保持向后兼容。
     */
    val skippedSteps: Set<String> = emptySet(),
    /**
     * Steps that failed but were explicitly recovered by step-level
     * on_failure or workflow-level on_error transitions. These failed step
     * records remain visible for audit, but should not make web/API summary
     * status report the whole workflow as failed.
     */
    val recoveredFailures: Set<String> = emptySet()
) {
    val durationSeconds: Double
        get() = duration / 1000.0
}

/**
 * 步骤执行结果
 */
data class StepExecutionResult(
    val stepName: String,
    val agentName: String,
    val success: Boolean,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val output: String? = null,
    val error: String? = null,
    val retryCount: Int = 0,
    /**
     * Snapshot of `context.variables` taken right after this step finished
     * (post-`processExtract`). Persisted alongside the step so that a later
     * "restart from step N" can recover variables that were set directly via
     * `context.setVariable(...)` — e.g. sub_workflow `outputs:` mapping
     * ([WorkflowExecutor.executeSubWorkflowStep] line ~4264), manual_approval
     * `reviewable_actions` output paths, classifier `output_variable`,
     * state_machine `_current_state` / `_final_state`, etc. — none of which
     * are recoverable from the step's text output alone via `processExtract`.
     *
     * On hydrate the LATEST preserved step's snapshot is applied to
     * `context.variables` as a savepoint — see [hydrateResumeState]. Empty
     * for legacy data persisted before this field existed; the hydrate path
     * then falls back to its pre-existing output-text-only behaviour.
     */
    val producedVariables: Map<String, String> = emptyMap()
) {
    val durationSeconds: Double
        get() = duration / 1000.0
}

/**
 * 从历史执行中恢复工作流时注入的上下文快照。
 *
 * 仅保留“在重跑目标步骤之前已经确定”的状态：
 * - 已完成/失败的步骤结果与输出
 * - 已跳过的步骤
 * - 由 on_success / on_failure / workflow.on_error 激活的转移目标
 */
data class WorkflowResumeState(
    val preservedStepResults: Map<String, StepExecutionResult> = emptyMap(),
    val skippedSteps: Set<String> = emptySet(),
    val activatedTransitionSteps: Set<String> = emptySet(),
    val transitionActivationSources: Map<String, Set<String>> = emptyMap()
)

/**
 * 工作流执行上下文
 */
data class WorkflowExecutionContext(
    val workflowName: String,
    val executionId: String,
    val variables: MutableMap<String, Any> = ConcurrentHashMap(),
    val stepOutputs: MutableMap<String, String> = ConcurrentHashMap(),
    val stepResults: MutableMap<String, StepExecutionResult> = ConcurrentHashMap(),
    val skippedSteps: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap()),
    val activatedTransitionSteps: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap()),
    val transitionActivationSources: MutableMap<String, MutableSet<String>> = ConcurrentHashMap(),
    /**
     * Names of steps whose failure was officially "recovered" by an on_failure transition
     * (step-level) or workflow.errorHandling.onError (workflow-level). Such failures should
     * NOT cause the overall workflow to be marked as failed, since the user explicitly designed
     * the workflow to handle them. Failures rescued only by `continueOnError=true` are NOT added
     * here — those still count toward the failed total.
     */
    val recoveredFailures: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap()),
    /**
     * Sub-workflow lineage. Top-level executions have these both null. When the engine
     * spawns a child context for a `sub_workflow` step, it sets:
     *
     *   - [parentExecutionId] = the immediate parent's `executionId`
     *   - [rootExecutionId]   = the top-most ancestor's `executionId`
     *     (for one-level nesting this equals [parentExecutionId]; for deeper
     *     nesting it propagates from the grandparent)
     *
     * Why both: most modules (e.g. `braidrun-module-artifact-publish`,
     * `braidrun-module-artifact-directory-publish`) need [rootExecutionId] —
     * they look up artifacts produced by the user-visible execution, not by
     * the sub-workflow's own derived id. A small number of module-debugging
     * use cases want [parentExecutionId] (one level up). [executionId]
     * (current/child) is preserved for any caller that genuinely wants
     * sub-workflow scope.
     *
     * Added 2026-04-26 — pre-fix, modules running as sub-workflows received
     * `WF_EXECUTION_ID = <parent>__<step>` (the derived child id) and would
     * fail to find artifacts produced by the parent's other steps. See
     * `execution-process-79621f70-...-failed.yaml` for the trigger case.
     */
    val parentExecutionId: String? = null,
    val rootExecutionId: String? = null,
) {
    /** 工作流级共享知识库实例（由 WorkflowExecutor 初始化） */
    @Transient
    var sharedKnowledgeBase: com.fartech.agents.tools.RAGTools? = null

    fun setVariable(key: String, value: Any) {
        variables[key] = value
    }

    fun getVariable(key: String): Any? = variables[key]

    fun setStepOutput(stepName: String, output: String) {
        stepOutputs[stepName] = output
    }

    fun getStepOutput(stepName: String): String? = stepOutputs[stepName]

    /**
     * Take a string-coerced snapshot of [variables] suitable for persisting
     * inside a [StepExecutionResult.producedVariables]. Used by "restart from
     * step N" so a later run can recover variables that were set via direct
     * [setVariable] calls — these are invisible to the output-text-based
     * `extract:` recovery path. Each value is rendered with `toString()` so
     * complex JSON / collection objects degrade to a stable string form
     * (`WF_VAR_*` env vars and `{{var:foo}}` templates already use
     * `toString()` for substitution, so this matches the runtime contract).
     */
    fun snapshotVariablesForPersistence(): Map<String, String> {
        if (variables.isEmpty()) return emptyMap()
        return variables.entries.associate { (key, value) -> key to value.toString() }
    }

    fun snapshot(extraVariables: Map<String, Any> = emptyMap()): WorkflowExecutionContext {
        val snapshot = WorkflowExecutionContext(
            workflowName = workflowName,
            executionId = executionId,
            variables = ConcurrentHashMap(variables),
            stepOutputs = ConcurrentHashMap(stepOutputs),
            stepResults = ConcurrentHashMap(stepResults),
            skippedSteps = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()),
            activatedTransitionSteps = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()),
            transitionActivationSources = ConcurrentHashMap(),
            recoveredFailures = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()),
            parentExecutionId = parentExecutionId,
            rootExecutionId = rootExecutionId,
        )
        snapshot.variables.putAll(extraVariables)
        snapshot.skippedSteps.addAll(skippedSteps)
        snapshot.activatedTransitionSteps.addAll(activatedTransitionSteps)
        snapshot.recoveredFailures.addAll(recoveredFailures)
        transitionActivationSources.forEach { (stepName, sources) ->
            snapshot.transitionActivationSources[stepName] =
                Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply {
                    addAll(sources)
                }
        }
        snapshot.sharedKnowledgeBase = sharedKnowledgeBase
        return snapshot
    }
}

/**
 * 状态机配置
 */
@Serializable
data class StateMachineConfig(
    @SerialName("states")
    val states: Map<String, StateDefinition>,

    @SerialName("initial_state")
    val initialState: String,

    @SerialName("final_states")
    val finalStates: List<String> = emptyList(),

    @SerialName("max_transitions")
    val maxTransitions: Int = 64
) {
    val referencedAgents: List<String>
        get() = states.values.flatMap { it.stepConfig?.referencedAgents ?: emptyList() }.distinct()

    init {
        require(states.isNotEmpty()) { "State machine must define at least one state" }
        require(states.containsKey(initialState)) { "Initial state '$initialState' not defined in states" }
        require(maxTransitions > 0) { "State machine max_transitions must be greater than 0" }
        finalStates.forEach { finalState ->
            require(states.containsKey(finalState)) { "Final state '$finalState' not defined in states" }
        }
    }
}

/**
 * 状态定义
 */
@Serializable
data class StateDefinition(
    @SerialName("name")
    val name: String,

    /** 当前状态进入后要执行的受限版步骤（可选） */
    @SerialName("step")
    val stepConfig: StateStepConfig? = null,

    @SerialName("on_enter")
    val onEnter: List<TransitionAction> = emptyList(),

    @SerialName("on_exit")
    val onExit: List<TransitionAction> = emptyList(),

    /** 无执行步骤状态的自动事件名称 */
    @SerialName("auto_event")
    val autoEvent: String = "enter",

    /** 执行步骤成功后的默认事件名称 */
    @SerialName("success_event")
    val successEvent: String = "complete",

    /** 执行步骤失败后的默认事件名称 */
    @SerialName("failure_event")
    val failureEvent: String = "error",

    @SerialName("transitions")
    val transitions: List<StateTransition> = emptyList()
)

/**
 * 状态机内部状态的执行配置。
 *
 * 复用 WorkflowStep 的核心执行模式，但不支持 retry / parallel / manual_approval 等外层包装能力。
 */
@Serializable
data class StateStepConfig(
    @SerialName("agent")
    val agent: String? = null,

    @SerialName("input")
    val input: String? = null,

    @SerialName("group_chat")
    val groupChat: GroupChatConfig? = null,

    @SerialName("agent_based")
    val agentBased: AgentBasedConfig? = null,

    @SerialName("code")
    val code: CodeStepConfig? = null,

    @SerialName("classifier")
    val classifier: ClassifierConfig? = null,

    /**
     * Sub-workflow invocation from inside a state-machine state. The state
     * executes the referenced module/workflow, maps `inputs` from the parent
     * context, and writes `outputs` back as parent variables — identical
     * semantics to a top-level `sub_workflow` step.
     *
     * This is what lets braidrun-module-telegram-deliver (and any future
     * deterministic module) be invoked from an `alert` state in a state
     * machine without needing to fall back to an LLM agent.
     */
    @SerialName("sub_workflow")
    val subWorkflow: SubWorkflowConfig? = null,

    @SerialName("extract")
    val extract: List<ExtractConfig>? = null
) {
    val isGroupChat: Boolean get() = groupChat != null
    val isAgentBased: Boolean get() = agentBased != null
    val isCode: Boolean get() = code != null
    val isClassifier: Boolean get() = classifier != null
    val isSubWorkflow: Boolean get() = subWorkflow != null

    val referencedAgents: List<String>
        get() = when {
            isGroupChat -> groupChat?.participants.orEmpty()
            isAgentBased -> agentBased?.participants.orEmpty()
            isCode -> emptyList()
            isClassifier -> listOfNotNull(classifier?.agent)
            isSubWorkflow -> emptyList()
            else -> listOfNotNull(agent)
        }

    init {
        val modeCount = listOf(
            agent != null && agent.isNotBlank(),
            groupChat != null,
            agentBased != null,
            code != null,
            classifier != null,
            subWorkflow != null
        ).count { it }
        require(modeCount == 1) {
            "State step must specify exactly one of 'agent'+'input', 'group_chat', 'agent_based', 'code', 'classifier', or 'sub_workflow'"
        }
        if (agent != null && agent.isNotBlank()) {
            require(!input.isNullOrBlank()) { "State step 'input' is required for single-agent mode" }
        }
    }
}

/**
 * 状态转换
 */
@Serializable
data class StateTransition(
    @SerialName("event")
    val event: String,

    @SerialName("target")
    val target: String,

    @SerialName("condition")
    val condition: String? = null,

    @SerialName("actions")
    val actions: List<TransitionAction> = emptyList()
)

/**
 * 工作流版本信息
 */
@Serializable
data class WorkflowVersion(
    @SerialName("version")
    val version: String,

    @SerialName("created_at")
    val createdAt: String,

    @SerialName("created_by")
    val createdBy: String? = null,

    @SerialName("description")
    val description: String? = null,

    @SerialName("checksum")
    val checksum: String? = null
)

/**
 * 工作流执行指标
 */
data class WorkflowMetrics(
    val executionId: String,
    val workflowName: String,
    val startTime: Long,
    var endTime: Long? = null,
    var status: ExecutionStatus = ExecutionStatus.RUNNING,
    // ConcurrentHashMap: steps run as parallel coroutines under concurrency.enabled
    // and the SSE/UI reader iterates while the executor inserts. The inner
    // StepMetrics.events list is already synchronized; the enclosing map must be too.
    val stepMetrics: MutableMap<String, StepMetrics> = java.util.concurrent.ConcurrentHashMap(),
    var totalSteps: Int = 0,
    var completedSteps: Int = 0,
    var failedSteps: Int = 0,
    var skippedSteps: Int = 0
) {
    fun getDuration(): Long = (endTime ?: System.currentTimeMillis()) - startTime
    fun getSuccessRate(): Double = if (totalSteps == 0) 0.0 else completedSteps.toDouble() / totalSteps
    fun getInputTokens(): Long = stepMetrics.values.sumOf { it.getInputTokens() }
    fun getOutputTokens(): Long = stepMetrics.values.sumOf { it.getOutputTokens() }
    fun getTotalTokens(): Long = stepMetrics.values.sumOf { it.getTotalTokens() }
}

/**
 * Agent 执行事件（工具调用、模型消息、生命周期等）
 */
data class AgentEvent(
    val timestamp: Long = System.currentTimeMillis(),
    /** 事件类型：tool_call_starting, tool_call_completed, tool_call_failed,
     *  llm_call_starting, llm_call_completed, agent_message, agent_starting, agent_completed 等 */
    val type: String,
    /** 事件分类：tool, llm, agent, strategy, message */
    val category: String,
    /** 事件子分类（可选）：如 reasoning/skill/sub_agent/reply/token/incoming/outgoing */
    val subCategory: String? = null,
    /** 简短描述 */
    val summary: String,
    /** 详细信息（可选） */
    val detail: String? = null,
    /** 单次事件对应的输入 token 数（可选） */
    val inputTokens: Int? = null,
    /** 单次事件对应的输出 token 数（可选） */
    val outputTokens: Int? = null,
    /** 单次事件对应的总 token 数（可选） */
    val totalTokens: Int? = null
)

/**
 * 步骤执行指标
 *
 * 子工作流的子 step 也会注册到同一个 [WorkflowMetrics] 中,通过 [parentStepName] 字段建立层级关系。
 * UI 可以通过 `groupBy { it.parentStepName }` 把扁平 stepMetrics 渲染成树形视图。
 */
data class StepMetrics(
    val stepName: String,
    val startTime: Long,
    var endTime: Long? = null,
    var status: ExecutionStatus = ExecutionStatus.RUNNING,
    var retryCount: Int = 0,
    var error: String? = null,
    var output: String? = null,
    var agentName: String? = null,
    /**
     * Underlying events storage. **Do not mutate this list directly from concurrent
     * code** — the previous API let callers call `events.add(...)` / `events.removeAt(i)`
     * straight from a `ConcurrentHashMap` lookup, which produced `ConcurrentModification`
     * crashes and torn reads under realistic workflow traffic (one thread writing agent
     * events while another reads them for SSE).
     *
     * Use [addEventBounded] for writes and [eventsSnapshot] for reads. The raw field
     * stays public (non-breaking) but is wrapped in a `synchronizedList` so that casual
     * reads (e.g. `events.size`) work, while guarded helpers are available for compound
     * operations.
     */
    val events: MutableList<AgentEvent> = java.util.Collections.synchronizedList(mutableListOf()),
    /**
     * 父 step 名称(子工作流场景使用)。
     * - null 表示这是顶层工作流的步骤
     * - 非空表示这是某个父 step 通过 sub_workflow 调用的子 step
     */
    var parentStepName: String? = null,
    /**
     * 子工作流的名称(仅在 parentStepName 非空时有意义),用于 UI 展示 "🧩 调用模块: xxx@version"
     */
    var subWorkflowName: String? = null
) {
    fun getDuration(): Long = (endTime ?: System.currentTimeMillis()) - startTime
    fun getInputTokens(): Long = synchronized(events) { events.sumOf { (it.inputTokens ?: 0).toLong() } }
    fun getOutputTokens(): Long = synchronized(events) { events.sumOf { (it.outputTokens ?: 0).toLong() } }
    fun getTotalTokens(): Long = synchronized(events) {
        events.sumOf {
            val total = it.totalTokens
            if (total != null) total.toLong() else (it.inputTokens ?: 0).toLong() + (it.outputTokens ?: 0).toLong()
        }
    }

    /**
     * Thread-safe snapshot of the events list for readers that need to iterate without
     * races. Returns an immutable copy — callers can safely `map`/`filter`/`forEach` it
     * even while new events are being added concurrently.
     */
    fun eventsSnapshot(): List<AgentEvent> = synchronized(events) { events.toList() }

    /**
     * Thread-safe append with a soft size cap. When the list is at or above [maxEvents],
     * the oldest non-critical event is dropped first to make room. All check-then-modify
     * operations happen under a single monitor, preventing the race where two callers
     * both read size=499 and concurrently push the list past the limit.
     */
    fun addEventBounded(event: AgentEvent, maxEvents: Int) {
        synchronized(events) {
            if (events.size >= maxEvents) {
                val dropIdx = events.indexOfFirst { it.category != "agent" && it.subCategory != "token" }
                if (dropIdx >= 0) events.removeAt(dropIdx) else events.removeAt(0)
            }
            events.add(event)
        }
    }
}

/**
 * 执行状态
 */
enum class ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMEOUT,
    CANCELLED,
    AWAITING_APPROVAL
}

/**
 * 工作流异常
 */
open class WorkflowException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WorkflowValidationException(message: String, cause: Throwable? = null) : WorkflowException(message, cause)

class WorkflowExecutionException(message: String, cause: Throwable? = null) : WorkflowException(message, cause)

class WorkflowTimeoutException(message: String) : WorkflowException(message)

class WorkflowApprovalRequiredException(message: String, val approvalConfig: ManualApprovalConfig) :
    WorkflowException(message)
