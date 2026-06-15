package com.fartech.agents.commons

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.feature.AIAgentFeature
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.AIAgentFunctionalFeature
import ai.koog.agents.core.feature.AIAgentPlannerFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentFunctionalPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPlannerPipeline
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.agent.entity.createStorageKey
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Adopts Koog 1.0.0 Feature #2 — `ToolCallMetadata` side channel.
 *
 * ## What Koog 1.0.0 added
 *
 * Pre-1.0.0 a tool's only context was the typed `args` deserialized from the
 * LLM call. Cross-cutting context like the workflow `execution_id`, the
 * authenticated `user_id`, the current `step_name`, or a distributed-trace
 * span id had to be threaded in via configuration parameters (visible to the
 * LLM as the prompt) or via `ThreadLocal` (fragile under coroutine
 * dispatch). 1.0.0 introduced [ai.koog.agents.core.tools.ToolCallMetadata]:
 * an immutable `Map<String, Any?>` side channel that travels with each tool
 * invocation, never serialised to or from the LLM, and accessible by tools
 * via the `metadata` parameter on the new `ToolBase.execute(args, metadata)`
 * overload.
 *
 * Features contribute metadata via [AIAgentPipelineAPI.provideToolCallMetadata];
 * Koog merges contributions across all installed features (caller-supplied
 * metadata wins on key collision per Koog's
 * [ai.koog.agents.core.environment.ContextualAgentEnvironment] doc).
 *
 * ## What this feature contributes
 *
 * `BraidrunToolCallContext` installs an AIAgentFeature that publishes the
 * following keys on every tool call:
 *
 *   - `dy.execution_id`   — workflow execution id (workflow `execution_id`
 *                            parameter, blank when not in a workflow)
 *   - `dy.step_name`      — current workflow step (`step_name` parameter)
 *   - `dy.workflow_name`  — parent workflow name (`workflow_name` parameter)
 *   - `dy.user_id`        — authenticated user / `agent-cli` for CLI flows
 *   - `dy.session_id`     — `session_id` (used by LongTermMemory etc.)
 *   - `dy.correlation_id` — distributed-trace id (`correlation_id` parameter
 *                            when set by the deployer / nginx layer)
 *   - `dy.run_id`         — Koog's agent run id (preserved from the framework)
 *
 * Tools that want to read these extend `ToolBase` directly (rather than the
 * convenience `Tool<TArgs, TResult>`) and override
 * `execute(args: TArgs, metadata: ToolCallMetadata): TResult`. Existing
 * `Tool` subclasses are unaffected — the `Tool.execute(args)` shape
 * discards the metadata silently per the upstream contract.
 *
 * ## Disabled by default
 *
 * The feature is opt-in via `tool_call_metadata_enabled=true` parameter so
 * the metadata writes (one map merge per tool call) are zero-cost for
 * workflows that don't consume the side channel. Worth enabling whenever
 * tools need to publish observability data without expanding LLM-visible
 * schemas — including the upcoming work to thread Prometheus per-tool
 * latency labels and Jaeger / OTLP trace spans into our tool group.
 */
public class BraidrunToolCallContext private constructor() {

    public class Config : FeatureConfig() {
        /**
         * Parameter snapshot captured at feature install time. The metadata
         * contributor reads these on every tool call rather than re-resolving
         * configuration each time (cheap; parameters are stable per agent run).
         */
        public var parameters: List<ConfigurationParameter> = emptyList()
    }

    public companion object Feature :
        AIAgentGraphFeature<Config, BraidrunToolCallContext>,
        AIAgentFunctionalFeature<Config, BraidrunToolCallContext>,
        AIAgentPlannerFeature<Config, BraidrunToolCallContext> {

        private val logger = KotlinLogging.logger { }

        override val key: AIAgentStorageKey<BraidrunToolCallContext> =
            createStorageKey("braidrun-tool-call-context")

        override fun createInitialConfig(
            agentConfig: ai.koog.agents.core.agent.config.AIAgentConfig
        ): Config = Config()

        override fun install(
            config: Config,
            pipeline: AIAgentGraphPipeline,
        ): BraidrunToolCallContext = installCommon(config, pipeline)

        override fun install(
            config: Config,
            pipeline: AIAgentFunctionalPipeline,
        ): BraidrunToolCallContext = installCommon(config, pipeline)

        override fun install(
            config: Config,
            pipeline: AIAgentPlannerPipeline,
        ): BraidrunToolCallContext = installCommon(config, pipeline)

        private fun installCommon(
            config: Config,
            pipeline: ai.koog.agents.core.feature.pipeline.AIAgentPipeline,
        ): BraidrunToolCallContext {
            val instance = BraidrunToolCallContext()
            // Pre-extract parameter strings once; `provideToolCallMetadata`
            // runs on every tool call so we keep it allocation-free.
            val params = config.parameters
            val executionId = params.parameter("execution_id", "")
            val stepName = params.parameter("step_name", "")
            val workflowName = params.parameter("workflow_name", "")
            val userId = params.parameter("user_id", "").ifBlank { "agent-cli" }
            val sessionId = params.parameter("session_id", "")
            val correlationId = params.parameter("correlation_id", "")

            pipeline.provideToolCallMetadata(this) { ctx ->
                buildMap<String, Any?> {
                    if (executionId.isNotBlank()) put("dy.execution_id", executionId)
                    if (stepName.isNotBlank()) put("dy.step_name", stepName)
                    if (workflowName.isNotBlank()) put("dy.workflow_name", workflowName)
                    put("dy.user_id", userId)
                    if (sessionId.isNotBlank()) put("dy.session_id", sessionId)
                    if (correlationId.isNotBlank()) put("dy.correlation_id", correlationId)
                    // Always include Koog's own run id — useful for cross-agent
                    // attribution when one agent invokes another via the
                    // sub_agent tool group.
                    put("dy.run_id", ctx.runId)
                }
            }
            logger.debug {
                "BraidrunToolCallContext installed: executionId=$executionId stepName=$stepName " +
                    "workflowName=$workflowName userId=$userId correlationId=${correlationId.ifBlank { "<unset>" }}"
            }
            return instance
        }
    }
}

/**
 * Install the [BraidrunToolCallContext] feature when
 * `tool_call_metadata_enabled=true`. No-op otherwise.
 *
 * `public` because the wiring is called from an `inline fun buildAgent(...)`
 * in [AgentBootstrap]; Kotlin would otherwise reject "public inline accessing
 * non-public function".
 */
fun GraphAIAgent.FeatureContext.installBraidrunToolCallContextIfEnabled(
    parameters: List<ConfigurationParameter>,
) {
    if (!parameters.parameter("tool_call_metadata_enabled", false)) return
    install(BraidrunToolCallContext) {
        this.parameters = parameters
    }
}
