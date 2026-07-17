package com.fartech.agents.commons

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageFileWriter
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.JsonSchemaGenerator
import ai.koog.prompt.streaming.StreamFrame
// Koog 1.0.0 renamed `Iterable<StreamFrame>.toMessageResponses()` (plural,
// returning `List<Message.Response>`) to `toMessageResponse()` (singular,
// returning a single `Message.Assistant`). The split reflects the new
// `Message.Assistant.parts` model where reasoning/text/tool-calls all live
// inside one message.
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.serialization.TypeToken
// `toLLM()` / `toLLModel()` extensions live in tools.SubAgentTools. Explicitly imported
// here because AgentBootstrap no longer shares `AgentCommon`'s wildcard `com.fartech.agents.tools.*`
// import after the Phase 5 file split.
import com.fartech.agents.tools.toLLM
import com.fartech.agents.tools.toLLModel
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.MyUtils
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Resolve sampling temperature after applying model-specific wire constraints.
 *
 * Kimi K3 fixes temperature at 1.0 and recommends that clients omit the field.
 * Workflow and preset defaults are commonly 0.5-0.8, so forwarding the configured
 * value would make an otherwise valid K3 request fail with HTTP 400.
 */
@PublishedApi
internal fun resolveModelTemperature(
    model: LLModel,
    configuredTemperature: Double?,
    modelGroupConfig: LLModelGroupConfig? = null,
): Double? {
    val includesKimiK3 = model.provider == KIMI_LLM_PROVIDER &&
        model.id.equals("kimi-k3", ignoreCase = true) ||
        modelGroupConfig
            ?.let { it.models + it.fallback + it.cascadeFallbacks }
            .orEmpty()
            .any {
                val isKimiProvider =
                    it.provider.equals("kimi", ignoreCase = true) ||
                        it.provider.equals("moonshot", ignoreCase = true)
                isKimiProvider && it.model.equals("kimi-k3", ignoreCase = true)
            }

    return if (includesKimiK3) {
        null
    } else {
        configuredTemperature
    }
}

/**
 * Agent lifecycle glue: construct a [GraphAIAgent] from workflow / preset
 * parameters, run it, hook in koog features (persistence, Langfuse tracing),
 * and invoke skill hooks at the right points.
 *
 * Carved out of `AgentCommon.kt` in Phase 5 of the 2026-04 audit follow-up.
 * Before the split, 550 lines of agent-construction code and 550 lines of
 * tool-registry / prompt-executor factories lived in the same god-file.
 *
 * ## Flow
 *
 * ```
 *                 parameters / httpAccess / systemPrompt / input
 *                           │
 *                           ▼
 *   buildAndRunStringAgent ──► buildAndRunAgent ──► buildAgent(reified)
 *                                   │                    │
 *                                   ▼                    ▼
 *                         dispatch skill hooks    buildAgent(non-inline)
 *                                                         │
 *                                                         ▼
 *                                        GraphAIAgent { promptExecutor + strategy
 *                                                       + toolRegistry + features }
 * ```
 *
 * Structured variants:
 *   - [buildAndRunStructureAgent] — JSON-Schema structured output with no tools
 *   - [buildAndRunStructureToolAgent] — JSON-Schema structured output + tools
 *   - [buildAndRunConfiguredAgentWithStructuredOutput] — single-shot structured
 *     output with post-hoc JSON parsing; cheapest path for assistant-style calls
 */

/**
 * The default koog feature context installer used by every `buildAgent` call
 * that doesn't pass an explicit one. The hook-aware installer is built lazily
 * so the `SkillManager`/`sessionId` defaults don't force a skill-manager
 * construction when the caller has its own.
 */
val defaultInstallFeatures: GraphAIAgent.FeatureContext.() -> Unit =
    createHookAwareInstallFeatures(skillManager = null, sessionId = "")

/**
 * Build a [GraphAIAgent] with the given I/O types, tool registry, system
 * prompt, strategy, and feature installer.
 *
 * Inline-reified siblings [buildAgent] (reified) and the run helpers below
 * delegate here; the non-inline form is kept so the heavy body isn't
 * duplicated at every call site.
 *
 * Responsibilities:
 *   - compose the environment info string (`compactEnvSettings` vs full
 *     `envSettings`) based on `compact_env` (default true)
 *   - resolve `max_tokens` from parameters (JsonPrimitive or string)
 *   - wire [createPromptExecutor] + [registerMcpTools] into the agent
 *   - inject skill-manager bootstrap content, virtual files, and operator
 *     messages into the system prompt when a [SkillManager] is provided
 *   - install [Persistence] and [OpenTelemetry] features based on parameters
 *   - dispatch `SESSION_START` hook on agent creation
 */
suspend inline fun <Input, Output> buildAgent(
    inputType: TypeToken,
    outputType: TypeToken,
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    toolRegistry: ToolRegistry,
    llModelGroupConfig: LLModelGroupConfig = parameters.parameter(
        key = "llm_config",
        defaultValue = LLModelGroupConfig(models = listOf())
    ),
    llmModel: LLModel = llModelGroupConfig.getDefaultModelName()?.let {
        llModelGroupConfig.foundModelWithName(it).toLLM().toLLModel()
    } ?: llModelGroupConfig.models.firstOrNull()?.toLLM()?.toLLModel() ?: DEFAULT_LLM_MODEL,
    skillManager: SkillManager? = createSkillManager(parameters),
    noinline installFeatures: GraphAIAgent.FeatureContext.() -> Unit = defaultInstallFeatures,
    noinline strategyBuilder: (List<ConfigurationParameter>, HttpAccess, ToolRegistry) -> AIAgentGraphStrategy<Input, Output>
): GraphAIAgent<Input, Output> {
    val envInfo = if (parameters.parameter("compact_env", true)) {
        compactEnvSettings(parameters)
    } else {
        envSettings(parameters)
    }
    val sessionId = parameters.parameter("session_id", "default")
    val configuredMaxTokens: Int? = parameters
        .find { it.key == "max_tokens" }
        ?.value
        ?.let { value -> (value as? JsonPrimitive)?.intOrNull ?: value.toString().toIntOrNull() }

    // Hoisted once so the same fully-resolved registry (caller's tools +
    // any dynamically discovered MCP tools) is forwarded to both the
    // strategy builder and the response processor. Previously we called
    // `registerMcpTools(...)` only on the `toolRegistry` argument and
    // routed the un-augmented registry into the strategy builder; this
    // works only because MCP tools are discovered at use-time inside
    // nodes. Tier-2's `ResponseProcessor` also needs the registered set
    // so the tool-call-fix loop can look up tool descriptors correctly.
    val mcpAugmentedToolRegistry = registerMcpTools(httpAccess, parameters, toolRegistry)

    return GraphAIAgent(
        // Koog 1.0.0 dropped the `inputType` / `outputType` constructor parameters
        // on `GraphAIAgent`; they're now derived from `strategy.nodeStart.inputType`
        // / `strategy.nodeFinish.outputType`. The `inputType` / `outputType`
        // parameters our callers pass are still respected because they're threaded
        // into the strategy at construction time.
        promptExecutor = createPromptExecutor(
            parameters = parameters,
            llmClients = determineLLMClients(httpAccess, parameters),
            // Tier-1 cascading on-error fallback — only materializes extra
            // executors when `llm_config.cascadeFallbacks` is non-empty AND
            // `cascade_fallback_enabled=true` in the parameters. Empty list
            // is the pre-Tier-1 default and keeps behaviour identical.
            extraCascadeTiers = determineCascadeFallbackClients(httpAccess, parameters),
        ),
        toolRegistry = mcpAugmentedToolRegistry,
        strategy = strategyBuilder(parameters, httpAccess, toolRegistry).also {
            require(it.metadata.uniqueNames) {
                "Checkpoint feature requires unique node names in the strategy metadata"
            }
        },
        agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "chat",
                params = LLMParams(
                    temperature = resolveModelTemperature(
                        model = llmModel,
                        configuredTemperature = llModelGroupConfig.temperature,
                        modelGroupConfig = llModelGroupConfig,
                    ),
                    maxTokens = configuredMaxTokens,
                    numberOfChoices = parameters.parameter("num_choices", 1),
                    // Tier-2 (2026-04) — portable `tool_choice` forwarding.
                    // Translates the workflow YAML's `tool_choice: auto|required|none|<toolName>`
                    // into Koog's unified [LLMParams.ToolChoice]; each provider
                    // client maps that to its native wire format, so the same
                    // workflow is portable across Anthropic / OpenAI / Google /
                    // DeepSeek / OpenRouter. Pass the MCP-augmented registry so
                    // `Named(<typo>)` is caught here instead of deadlocking the
                    // agent on an impossible tool call.
                    toolChoice = resolveToolChoice(parameters, mcpAugmentedToolRegistry),
                )
            ) {
                skillManager?.let {
                    // Print operator messages from handler scripts (not injected into prompt)
                    it.collectBootstrapMessages().forEach { msg ->
                        logProgress(AnsiColor.CYAN, "Hooks", "💬 $msg")
                    }
                    system {
                        val hookContent = it.collectBootstrapHookContent()
                        val virtualFiles = it.collectBootstrapVirtualFiles()
                        val fullPrompt = buildString {
                            append(it.createSkillSystemPrompt())
                            append("\n\n")
                            append(systemPrompt)
                            if (hookContent.isNotBlank()) {
                                append("\n\n")
                                append(hookContent)
                            }
                            // Inject virtual bootstrap files (mirrors OpenClaw bootstrapFiles mechanism).
                            // Each file is presented as if it had been read from disk at session start,
                            // formatted identically to ReadFileTool output so the agent can reference
                            // the file by path.
                            if (virtualFiles.isNotEmpty()) {
                                append("\n\n")
                                append("## Bootstrap Files\n\n")
                                append("The following files were automatically loaded at session start:\n\n")
                                virtualFiles.forEach { (path, content) ->
                                    append("[File: $path]\n")
                                    append(content)
                                    append("\n\n")
                                }
                            }
                            append("\n\n")
                            append(envInfo)
                        }
                        +fullPrompt
                    }
                } ?: run {
                    system {
                        +"${systemPrompt}\n\n$envInfo"
                    }
                }
            },
            model = llmModel,
            maxAgentIterations = parameters.parameter("max_iterations", 8196),
            // Tier-2 (2026-04) — optional weak-model tool-call repair.
            // Opt-in per-workflow via `weak_model_tool_fix_enabled=true`.
            // See `WeakModelToolCallFix.kt` for cost model & supported flags.
            responseProcessor = WeakModelToolCallFix.buildProcessorIfEnabled(
                parameters = parameters,
                toolRegistry = mcpAugmentedToolRegistry,
            ),
        ),
        installFeatures = {
            installFeatures()
            if (parameters.parameter("enable_persistence", false)) {
                install(Persistence) {
                    // Use in-memory storage for snapshots
                    storage = createPersistenceStorageProvider(parameters)
                    // Enable automatic persistence
                    enableAutomaticPersistence = true
                    // Tier-2 (2026-04) — optional rollback tool pairs.
                    // Only meaningful when the workflow declares
                    // `rollback_tool_pairs`; empty registry is the
                    // default and matches pre-Tier-2 behaviour exactly.
                    // `rollbackToCheckpoint(id, ctx)` walks the
                    // forward tool calls in reverse and invokes the
                    // paired inverse tool for each match. See
                    // `WorkflowRollbackRegistry.kt` for authoring.
                    rollbackToolRegistry = WorkflowRollbackRegistry.buildFromParameters(
                        parameters = parameters,
                        toolRegistry = mcpAugmentedToolRegistry,
                    )
                }
            }
            if (parameters.parameter("enable_langfuse_tracing", false)) {
                install(OpenTelemetry) {
                    addLangfuseExporter(
                        langfuseSecretKey = parameters.parameter("langfuse_secret_key", ""),
                        langfusePublicKey = parameters.parameter("langfuse_public_key", ""),
                        langfuseUrl = parameters.parameter("langfuse_url", "https://us.cloud.langfuse.com")
                    )
                }
            }
            // Tier-1 observability features (2026-04):
            //
            // Both features are **off by default** so they have zero overhead
            // for workflows that don't opt in. Enable per-workflow via YAML
            // `parameters:` or per-preset via the assistant configuration.
            installTokenizerIfEnabled(parameters)
            installTracingIfEnabled(parameters)
            // Tier-2 (2026-04) — optional cross-session memory.
            // `long_term_memory_enabled=true` turns on RAG over past
            // conversation turns keyed by `session_id`. See
            // `LongTermMemoryInstall.kt` for the storage / namespacing /
            // timing knobs.
            with(LongTermMemoryInstall) { installLongTermMemoryIfEnabled(parameters) }
            // Phase 11 (Koog 1.0.0) — `ToolCallMetadata` side channel.
            // Opt-in via `tool_call_metadata_enabled=true`. Publishes the
            // workflow execution_id / step_name / user_id / correlation_id
            // / session_id to every tool call without polluting the LLM-
            // visible argument schema. Tools that want them extend
            // `ToolBase` directly and read `metadata` in the
            // `execute(args, metadata)` overload. Defaults to off so
            // workflows that don't consume the side channel pay zero cost.
            installBraidrunToolCallContextIfEnabled(parameters)
            // Koog 1.0.0 (Phase 11) — `AgentMemory` feature removed; the
            // 0.x `agents.memory.feature.*` namespace (MemorySubject, fact
            // store, encrypted storage adapter) is gone entirely. Use
            // [LongTermMemoryInstall] above for cross-session recall; the
            // facts-style API surface is no longer offered upstream. The
            // `agent_memory_enabled` workflow parameter is now silently
            // ignored. A follow-up could migrate the use cases onto the
            // new `ChatMemory` feature (in agents-features-memory 1.0.0)
            // which provides chat-history persistence with a different
            // shape (no MemorySubject classification).
        }
    ).also {
        skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_START, sessionId)
    }
}

/**
 * Reified convenience overload — derives `TypeToken`s from reified type
 * parameters and forwards to the non-inline [buildAgent] above.
 */
@OptIn(ExperimentalUuidApi::class)
suspend inline fun <reified Input, reified Output> buildAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    toolRegistry: ToolRegistry,
    llModelGroupConfig: LLModelGroupConfig = parameters.parameter(
        key = "llm_config",
        defaultValue = LLModelGroupConfig(models = listOf())
    ),
    llmModel: LLModel = llModelGroupConfig.getDefaultModelName()?.let {
        llModelGroupConfig.foundModelWithName(it).toLLM().toLLModel()
    } ?: llModelGroupConfig.models.firstOrNull()?.toLLM()?.toLLModel() ?: DEFAULT_LLM_MODEL,
    skillManager: SkillManager? = createSkillManager(parameters),
    noinline installFeatures: GraphAIAgent.FeatureContext.() -> Unit = defaultInstallFeatures,
    noinline strategyBuilder: (List<ConfigurationParameter>, HttpAccess, ToolRegistry) -> AIAgentGraphStrategy<Input, Output>
): GraphAIAgent<Input, Output> = buildAgent(
    inputType = TypeToken.of(Input::class.java),
    outputType = TypeToken.of(Output::class.java),
    httpAccess = httpAccess,
    parameters = parameters,
    systemPrompt = systemPrompt,
    toolRegistry = toolRegistry,
    llModelGroupConfig = llModelGroupConfig,
    llmModel = llmModel,
    skillManager = skillManager,
    installFeatures = installFeatures,
    strategyBuilder = strategyBuilder
)

/**
 * Build an agent and run a single turn, persisting history + dispatching
 * `MESSAGE*` / `COMMAND*` / `AGENT_ERROR` / `SESSION_END` skill hooks.
 *
 * Closes the agent in `finally` so subprocess / file handles don't leak if
 * the run throws. A known koog 0.6.4 OpenTelemetry bug (orphaned inference
 * span when an LLM call fails) is specifically caught here — it would
 * otherwise manifest as an unrelated `IllegalStateException` deep inside
 * node execution.
 */
suspend inline fun <reified Input, reified Output> buildAndRunAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    input: Input,
    toolRegistry: ToolRegistry,
    skillManager: SkillManager? = createSkillManager(parameters),
    noinline installFeatures: GraphAIAgent.FeatureContext.() -> Unit = defaultInstallFeatures,
    noinline strategyBuilder: (List<ConfigurationParameter>, HttpAccess, ToolRegistry) -> AIAgentGraphStrategy<Input, Output>
): Pair<AIAgent<Input, Output>, Output?> {
    val sessionId = parameters.parameter("session_id", MyUtils.generateUniqueID())
    val updatedParameters = parameters.toMutableList().apply {
        if (none { it.key == "session_id" }) {
            add(ConfigurationParameter("session_id", JsonPrimitive(sessionId)))
        }
    }

    val inputStr = input.toString().trim()
    skillManager?.dispatchHooks(BraidrunHookEvent.MESSAGE, sessionId, mapOf("content" to inputStr, "direction" to "in"))
    skillManager?.dispatchHooks(BraidrunHookEvent.MESSAGE_RECEIVED, sessionId, mapOf("content" to inputStr))
    skillManager?.dispatchHooks(BraidrunHookEvent.MESSAGE_TRANSCRIBED, sessionId, mapOf("content" to inputStr))
    skillManager?.dispatchHooks(BraidrunHookEvent.MESSAGE_PREPROCESSED, sessionId, mapOf("content" to inputStr))

    if (inputStr.startsWith("/")) {
        val command = inputStr.substringBefore(" ").substring(1)
        skillManager?.dispatchHooks(BraidrunHookEvent.COMMAND, sessionId, mapOf("command" to command))
        when (command.lowercase()) {
            "new" -> skillManager?.dispatchHooks(BraidrunHookEvent.COMMAND_NEW, sessionId)
            "reset" -> skillManager?.dispatchHooks(BraidrunHookEvent.COMMAND_RESET, sessionId)
            "stop" -> skillManager?.dispatchHooks(BraidrunHookEvent.COMMAND_STOP, sessionId)
        }
    }

    val agent = buildAgent<Input, Output>(
        httpAccess,
        updatedParameters,
        systemPrompt,
        toolRegistry,
        skillManager = skillManager,
        strategyBuilder = strategyBuilder,
        installFeatures = installFeatures
    )

    // Persist the user turn AFTER buildAgent: the strategy builder calls
    // loadHistoryMessages, and saving first put the in-flight message into the
    // restored history — every history-enabled run then sent the current user
    // turn TWICE (restoreHistoryNode replay + the request node's user(input)),
    // wasting tokens and producing user/user sequences some providers reject.
    saveHistoryMessage(updatedParameters, "user", input.toString())
    // koog 0.6.4 OTel bug workaround: when an LLM API call fails, the inference span is never
    // removed from SpanCollector (missing interceptLLMCallFailed handler). When the node then
    // fails, interceptNodeExecutionFailed tries to removeSpan() but finds the orphaned child
    // span and throws IllegalStateException. We catch it here to prevent a full program crash.
    var output: Output? = null
    try {
        output = agent.run(input)
        output?.let {
            saveHistoryMessage(updatedParameters, "assistant", it.toString())
            skillManager?.dispatchHooks(
                BraidrunHookEvent.MESSAGE,
                sessionId,
                mapOf("content" to it.toString(), "direction" to "out")
            )
            skillManager?.dispatchHooks(BraidrunHookEvent.MESSAGE_SENT, sessionId, mapOf("content" to it.toString()))
        }
    } catch (e: Exception) {
        skillManager?.dispatchHooks(
            BraidrunHookEvent.AGENT_ERROR,
            sessionId,
            mapOf("error" to (e.message ?: e.toString()))
        )
        if (e is IllegalStateException && e.message?.contains("Error deleting span node from the tree") == true) {
            logProgress(
                AnsiColor.RED,
                "Agent",
                "⚠️ koog OTel bug triggered (missing interceptLLMCallFailed): ${e.message}"
            )
            logProgress(
                AnsiColor.YELLOW,
                "Agent",
                "💡 Workaround: set 'enable_langfuse_tracing: false' in config to disable Langfuse tracing"
            )
        } else {
            throw e
        }
    } finally {
        try {
            agent.close()
        } catch (_: Exception) {
        }
        skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_END, sessionId)
    }
    return agent to output
}

/**
 * `String` → `String` convenience over [buildAndRunAgent]. Defaults
 * [toolRegistry] to an empty `parseToolSet` build and [strategyBuilder] to
 * [determineDefaultStrategy]. The initial user prompt is read from the
 * `prompt` parameter (fallback: "Hello, what you can do for me?").
 */
suspend fun buildAndRunStringAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    toolRegistry: ToolRegistry? = null,
    skillManager: SkillManager? = createSkillManager(parameters),
    strategyBuilder: (List<ConfigurationParameter>, HttpAccess, ToolRegistry) -> AIAgentGraphStrategy<String, String> = { parameters, httpAccess, toolReg ->
        determineDefaultStrategy(
            httpAccess,
            parameters,
            toolReg,
            skillManager = skillManager,
        )
    }
): Pair<AIAgent<String, String>, String?> {
    val actualToolRegistry =
        toolRegistry ?: parseToolSet(parameters, httpAccess, emptyList<AgentTools>(), skillManager = skillManager)
    return buildAndRunAgent(
        httpAccess,
        parameters,
        systemPrompt,
        parameters.parameter("prompt", "Hello, what you can do for me?"),
        actualToolRegistry,
        skillManager = skillManager,
        strategyBuilder = strategyBuilder
    )
}

/**
 * Agent that produces structured JSON output matching a generated schema, no
 * tool calls involved. Suitable for classify / extract / transform tasks
 * where the LLM should conform to a Kotlin data-class shape.
 */
suspend inline fun <reified Input, reified Output> buildAndRunStructureAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    toolRegistry: ToolRegistry? = null,
    input: Input,
    emptyValue: Output,
    llmModel: LLModel,
    examples: List<Output> = emptyList(),
    attachments: List<AttachmentFile> = emptyList(),
    schemaGenerator: JsonSchemaGenerator = BasicJsonSchemaGenerator.Default,
    skillManager: SkillManager? = createSkillManager(parameters)
): Pair<AIAgent<Input, Output>, Output?> {
    val actualToolRegistry =
        toolRegistry ?: parseToolSet(parameters, httpAccess, emptyList<AgentTools>(), skillManager = skillManager)
    return buildAndRunAgent(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt,
        input = input,
        toolRegistry = actualToolRegistry,
        skillManager = skillManager
    ) { _, _, _ ->
        strategy<Input, Output>(
            name = "__structure_agent_strategy__"
        ) {
            val requestStructure by structureGraph<Input, Output>(
                name = "__structure_agent_strategy_graph__",
                emptyValue = emptyValue,
                llmModel = llmModel,
                attachments = attachments,
                structureExamples = examples,
                schemaGenerator = schemaGenerator
            )
            nodeStart then requestStructure then nodeFinish
        }
    }
}

/**
 * Like [buildAndRunStructureAgent] but tools are available during generation —
 * the structured output schema is enforced at the final step via
 * [structureToolGraph].
 */
suspend inline fun <reified Output> buildAndRunStructureToolAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    toolRegistry: ToolRegistry? = null,
    input: String,
    llmModel: LLModel,
    examples: List<Output> = emptyList(),
    attachments: List<AttachmentFile> = emptyList(),
    schemaGenerator: JsonSchemaGenerator = BasicJsonSchemaGenerator.Default,
    skillManager: SkillManager? = createSkillManager(parameters)
): Pair<AIAgent<String, Output>, Output?> {
    val actualToolRegistry =
        toolRegistry ?: parseToolSet(parameters, httpAccess, emptyList<AgentTools>(), skillManager = skillManager)
    return buildAndRunAgent(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt,
        input = input,
        toolRegistry = actualToolRegistry,
        skillManager = skillManager
    ) { _, _, _ ->
        strategy<String, Output>(
            name = "__structure_tool_agent_strategy__"
        ) {
            val requestStructure by structureToolGraph<Output>(
                // Koog 1.0.0 — ToolRegistry.tools widened to List<ToolBase<*, *>>;
                // structureToolGraph still wants List<Tool<*, *>>.
                tools = actualToolRegistry.tools.filterIsInstance<ai.koog.agents.core.tools.Tool<*, *>>(),
                llmModel = llmModel,
                attachments = attachments,
                structureExamples = examples,
                schemaGenerator = schemaGenerator
            )
            nodeStart then requestStructure then nodeFinish
        }
    }
}

/**
 * `structuredOutputJson` is the permissive deserializer used when parsing LLM
 * output — `ignoreUnknownKeys` (LLMs tend to invent keys) plus `isLenient`
 * (single quotes, trailing commas, etc.) plus `coerceInputValues` (nulls in
 * non-null-with-default fields become the default).
 */
val structuredOutputJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Extract the first JSON object or array from a raw LLM response.
 *
 * LLMs frequently wrap JSON in markdown code fences or add leading / trailing
 * prose. This helper first tries a `` ```json … ``` `` / `` ``` … ``` ``
 * fenced block, then falls back to brace-matching from the first `{`.
 *
 * @return the extracted JSON string, or `null` if none is found.
 */
fun extractJsonFromResponse(raw: String): String? {
    // Try to find a fenced code block first (```json … ``` or ``` … ```)
    jsonFencePattern.find(raw)?.groupValues?.get(1)?.let { return it }

    // Fall back to the first top-level { … }
    val start = raw.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escape = false
    for (i in start until raw.length) {
        val ch = raw[i]
        if (escape) { escape = false; continue }
        if (ch == '\\' && inString) { escape = true; continue }
        if (ch == '"') { inString = !inString; continue }
        if (inString) continue
        if (ch == '{') depth++
        if (ch == '}') depth--
        if (depth == 0) return raw.substring(start, i + 1)
    }
    return null
}

// Hoisted: compiled once instead of per structured-output round.
private val jsonFencePattern = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)

/**
 * Single-round-trip assistant-style structured output.
 *
 * Builds the agent with the "single_run" strategy (one LLM call, no
 * reasoning loop, no tool-calling iterations), then post-processes the raw
 * string output with [extractJsonFromResponse] + [structuredOutputJson]
 * deserialization.
 *
 * Prefer this over [buildAndRunStructureAgent] when the caller just needs a
 * quick typed response and doesn't need the schema-generator / JSON-schema
 * machinery.
 */
suspend inline fun <reified Output> buildAndRunConfiguredAgentWithStructuredOutput(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    input: String,
    toolRegistry: ToolRegistry? = null,
    skillManager: SkillManager? = createSkillManager(parameters),
): Pair<AIAgent<String, String>, Output?> {
    val actualToolRegistry =
        toolRegistry ?: parseToolSet(parameters, httpAccess, emptyList<AgentTools>(), skillManager = skillManager)
    val (agent, rawOutput) = buildAndRunAgent<String, String>(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt,
        input = input,
        toolRegistry = actualToolRegistry,
        skillManager = skillManager,
    ) { _, _, _ ->
        singleRunWithParallelAbility(
            name = "__structured_output_single_run__",
            parallel = false,
        )
    }
    if (rawOutput.isNullOrBlank()) return agent to null
    val parsed = runCatching {
        val jsonStr = extractJsonFromResponse(rawOutput) ?: rawOutput
        structuredOutputJson.decodeFromString<Output>(jsonStr)
    }.getOrNull()
    return agent to parsed
}

/**
 * Collect every frame from a stream flow into a single [Message.Assistant].
 *
 * Used by agent strategies that want to expose streaming output over a
 * request/response boundary: all frames are buffered, converted to
 * [Message.Assistant]s via [toMessageResponses], and the final one is returned
 * (it carries the full aggregated content).
 *
 * Throws when the flow is empty — an agent that emits no frames usually
 * means a misconfigured model or a short-circuited error.
 */
fun AIAgentSubgraphBuilderBase<*, *>.streamCollectNode(
    name: String,
) = node<Flow<StreamFrame>, Message.Assistant>(name) { flow ->
    // 收集所有帧，同时实时处理文本输出
    val frames = mutableListOf<StreamFrame>()

    flow.collect { frame ->
        frames.add(frame)
    }
    // Koog 1.0.0 — `toMessageResponses()` (plural) collapsed to a single
    // `toMessageResponse()` returning `Message.Assistant` (whose `parts`
    // carry all the reasoning/text/tool-call pieces). Append via the generic
    // `message(msg)` overload — the `assistant(...)` builders all take parts
    // / String / builder blocks, not a pre-built Message.Assistant.
    val response = frames.toMessageResponse()
    llm.writeSession {
        appendPrompt {
            message(response)
        }
    }
    // Koog 1.0.0 — the stream now collapses into one Message.Assistant; just return it.
    response
}

/**
 * Install Koog's [MessageTokenizer] feature when `tokenizer_enabled=true`.
 *
 * The feature adds an [ai.koog.prompt.tokenizer.PromptTokenizer] to the
 * agent context, queryable from any strategy node via
 * `tokenizer().tokenCountFor(prompt)`. This gives us **client-side
 * pre-request token estimation** for budget gating — complementary to the
 * post-request provider-reported token counts surfaced by the active LLM client.
 *
 * Defaults:
 *   - tokenizer = [SimpleRegexBasedTokenizer] — pure-JVM, no native deps,
 *     accuracy ±10–15% vs provider counts (fine for budget gating).
 *   - enableCaching = true — reuses token counts for identical message
 *     bodies across multiple `tokenCountFor` calls within a session.
 *
 * Overrideable via parameters:
 *   - `tokenizer_enabled: Boolean` (default false).
 *   - `tokenizer_enable_caching: Boolean` (default true).
 *
 * We intentionally DO NOT wire a provider-specific tokenizer (like
 * Tiktoken) by default — it's a ~4 MB vocab download + native-ish
 * performance cost that most workflows won't use. Callers that need
 * per-provider accuracy can install their own `Tokenizer` downstream.
 */
fun GraphAIAgent.FeatureContext.installTokenizerIfEnabled(parameters: List<ConfigurationParameter>) {
    if (!parameters.parameter("tokenizer_enabled", false)) return
    install(MessageTokenizer) {
        tokenizer = SimpleRegexBasedTokenizer()
        enableCaching = parameters.parameter("tokenizer_enable_caching", true)
    }
}

/**
 * Install Koog's [Tracing] feature when `tracing_enabled=true`.
 *
 * Writes a structured NDJSON trace of every agent step (node, LLM call,
 * tool call, streaming frame) to disk, useful for debugging strategy
 * graphs that don't surface enough detail through the
 * [com.fartech.agents.workflow.WorkflowMonitor] / audit event pipeline.
 *
 * Parameters:
 *   - `tracing_enabled: Boolean` (default false).
 *   - `tracing_file_path: String` — absolute or working-directory-relative
 *     path where trace events are appended. Defaults to
 *     `.workflow-runs/traces/agent-<sessionId>.ndjson`.
 *   - `tracing_to_log: Boolean` — also mirror events through the
 *     `agent.trace` KLogger (INFO level). Defaults to false; enabling it
 *     is noisy but handy when tailing container logs.
 *
 * ## Production safety
 *
 * Tracing writes every message, tool call, and token delta. That's
 * verbose AND can include prompt content that should never leave the
 * host. Keep this OFF in production unless you've reviewed the destination
 * path against your log-retention / PII policies — `tracing_enabled=true`
 * is a per-workflow / per-preset opt-in, not a global toggle.
 */
fun GraphAIAgent.FeatureContext.installTracingIfEnabled(parameters: List<ConfigurationParameter>) {
    if (!parameters.parameter("tracing_enabled", false)) return

    // Sanitize the session-id segment so a pathological value like
    // `../../etc/passwd` can't escape the intended traces directory when
    // interpolated into the default path template. Keep alphanumerics,
    // dashes, underscores, dots; replace everything else (including `/`
    // and `\`) with `_`. Trim to a reasonable length to avoid filesystem
    // path-length limits.
    val rawSessionId = parameters.parameter("session_id", "default")
    val safeSessionId = rawSessionId
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(128)
        .ifBlank { "default" }

    val filePath = parameters.parameter(
        "tracing_file_path",
        ".workflow-runs/traces/agent-$safeSessionId.ndjson"
    )
    val tracePath = java.nio.file.Paths.get(filePath).toAbsolutePath().normalize()

    // Operator-supplied `tracing_file_path` is honoured verbatim (they
    // control the value) — but the DEFAULT template is locked to live
    // under `<cwd>/.workflow-runs/traces/`. If the caller didn't set
    // `tracing_file_path` explicitly, enforce that prefix so a crafted
    // session_id that survived the sanitizer above still can't escape.
    val hasExplicitPath = parameters.any { it.key == "tracing_file_path" }
    val defaultRoot = java.nio.file.Paths.get(".workflow-runs/traces").toAbsolutePath().normalize()
    if (!hasExplicitPath && !tracePath.startsWith(defaultRoot)) {
        // Extremely unlikely (session_id sanitizer should prevent this)
        // but bail rather than write to an unexpected location.
        return
    }

    install(Tracing) {
        // Ensure the parent dir exists so the file writer doesn't throw
        // NoSuchFileException on first write. `createDirectories` is a
        // no-op if the directory already exists.
        tracePath.parent?.let { java.nio.file.Files.createDirectories(it) }
        addMessageProcessor(TraceFeatureMessageFileWriter.create(targetPath = tracePath))

        if (parameters.parameter("tracing_to_log", false)) {
            addMessageProcessor(
                TraceFeatureMessageLogWriter.create(
                    org.slf4j.LoggerFactory.getLogger("agent.trace")
                )
            )
        }
    }
}
