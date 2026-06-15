package com.fartech.agents.tools

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.llm.LLModel
import com.fartech.agents.commons.*
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.printlnColor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.util.*

@Serializable
@LLMDescription("LLM configuration for sub-agent creation, management and usage")
data class LLM(
    @property:LLMDescription("LLM provider name")
    val provider: String,
    @property:LLMDescription("LLM model")
    val model: String
)

fun LLModelConfig.toLLM(
): LLM = LLM(provider = provider, model = model)

fun LLM.toLLModel(): LLModel {
    // Use determineLLMModel, which supports dynamic model creation from configuration;
    // This allows models to be automatically generated from a database without pre-definition
    val modelConfig = LLModelConfig(
        provider = provider,
        model = model
    )
    return determineLLMModel(modelConfig)
}

@Serializable
@LLMDescription("Context for sub-agent creation, management and usage")
data class SubAgentContext(
    @property:LLMDescription("prompt for the sub-agent")
    val prompt: String,
    @property:LLMDescription("name of the sub-agent")
    val name: String = UUID.randomUUID().toString(),
    @property:LLMDescription("the LLM configuration for the sub-agent")
    val llm: LLM,
    @property:LLMDescription("the attachments for the sub-agent")
    val attachments: List<AttachmentFile> = emptyList(),
    @property:LLMDescription("maximum number of iterations for the sub-agent")
    val maxIterations: Int = 128,
    @property:LLMDescription("reasoning interval for the sub-agent in seconds")
    val reasoningInterval: Int = 1
)

private suspend fun SubAgentContext.buildAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    llm: LLM
): AIAgent<String, String> {
    // Build base system prompt
    val baseSystemPrompt = """
        Your name is $name
        You're a sub agent running by a parent agent to accomplish a specified task.
        Try your best to complete the task and deliver the result.
    """.trimIndent()

    // Create a modified parameters list with the sub-agent's iteration limit
    val subAgentParameters = parameters.toMutableList().apply {
        // Remove any existing max_iterations entries, then append the sub-agent's value at the end
        removeAll { it.key == "max_iterations" }
        add(ConfigurationParameter("max_iterations", JsonPrimitive(maxIterations)))
        add(ConfigurationParameter("reasoning_interval", JsonPrimitive(reasoningInterval)))
    }

    return buildAgent(
        httpAccess = httpAccess,
        parameters = subAgentParameters,
        systemPrompt = baseSystemPrompt,
        toolRegistry = parseToolSet(
            subAgentParameters,
            httpAccess,
            emptyList<AgentTools>()
        ),
        strategyBuilder = { params, _, toolRegistry ->
            determineDefaultStrategy(
                httpAccess,
                params,
                toolRegistry = toolRegistry,
                strategyParameterName = "sub_agent_strategy"
            )
        },
        llmModel = llm.toLLModel()
    )
}

@LLMDescription("Toolset for sub-agent creation, management and usage")
class SubAgentTools(
    val httpAccess: HttpAccess,
    val parameters: List<ConfigurationParameter>,
    private val onMonitorEvent: MonitoringEventCallback? = null
) : ToolSet {

    val llmGroupConfig = parameters.getLLMGroupConfig()

    private fun emit(type: String, summary: String, detail: String? = null) {
        onMonitorEvent?.invoke(type, summary, detail)
    }

    fun shouldRetry(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        return msg.contains("jsondecodingexception") ||
                msg.contains("expected start of the object") ||
                msg.contains("eof") ||
                msg.contains("timeout") ||
                msg.contains("bad gateway") ||
                msg.contains("service unavailable") ||
                msg.contains("too many requests")
    }

    @Tool
    @LLMDescription("Run a new sub agent and wait for its completion")
    suspend fun runSubAgent(
        @LLMDescription("context for the sub-agent")
        agentContext: SubAgentContext
    ): String {
        logProgress(AnsiColor.CYAN, "SubAgentTools", "Running sub-agent with context: ${agentContext.prompt}")
        emit("sub_agent_starting", "🚀 启动 Sub Agent: ${agentContext.name}", agentContext.prompt)
        val maxAttempts = 3
        var lastError: Throwable? = null
        repeat(maxAttempts) { attemptIdx ->
            var agent: AIAgent<String, String>? = null
            try {
                agent = agentContext.buildAgent(httpAccess, parameters, agentContext.llm)
                val output = agent.run(agentContext.prompt)
                emit(
                    "sub_agent_completed",
                    "✅ Sub Agent 完成: ${agentContext.name}",
                    if (output.length > 500) output.take(500) + "..." else output
                )
                return output
            } catch (e: Throwable) {
                lastError = e
                if (attemptIdx < maxAttempts - 1 && shouldRetry(e)) {
                    // small exponential backoff
                    val backoffMs = 1000L * (attemptIdx + 1)
                    emit(
                        "sub_agent_retrying",
                        "🔁 Sub Agent 重试: ${agentContext.name} (${attemptIdx + 1}/$maxAttempts)",
                        e.message
                    )
                    delay(backoffMs)
                } else {
                    emit("sub_agent_failed", "❌ Sub Agent 失败: ${agentContext.name}", e.message)
                    throw e
                }
            } finally {
                try {
                    agent?.close()
                } catch (_: Throwable) {
                }
            }
        }
        throw lastError ?: IllegalStateException("Unknown error running sub agent")
    }

    /**
     * Run a sub-agent with timeout and retry limits to prevent infinite loops.
     *
     * Security features:
     * - Maximum restart attempts (prevents infinite loops)
     * - Exponential backoff between retries
     * - Circuit breaker pattern for repeated failures
     * - Resource cleanup on timeout
     *
     * @param ctx Sub-agent context
     * @param timeoutMs Timeout per attempt in milliseconds
     * @param maxRestarts Maximum number of restart attempts (default 5)
     * @return Agent output string
     * @throws MaxRetriesExceededException if max restarts exceeded
     * @throws Throwable for non-recoverable errors
     */
    private suspend fun runSubAgentWithTimeoutRestarts(
        ctx: SubAgentContext,
        timeoutMs: Long,
        maxRestarts: Int = 5  // 🔒 Add maximum restart limit
    ): String {
        require(timeoutMs > 0) { "timeoutMs must be > 0" }
        require(maxRestarts > 0) { "maxRestarts must be > 0" }

        var consecutiveFailures = 0
        val maxConsecutiveFailures = 3  // Circuit breaker threshold

        logProgress(
            AnsiColor.CYAN,
            "SubAgentTools",
            "Running sub-agent with timeout and restart limits. Timeout: ${timeoutMs / 1000}s, Max Restarts: $maxRestarts"
        )
        emit(
            "sub_agent_starting",
            "🚀 启动 Sub Agent(带超时): ${ctx.name}",
            "timeout=${timeoutMs / 1000}s, maxRestarts=$maxRestarts"
        )

        repeat(maxRestarts) { attemptIdx ->
            var agent: AIAgent<String, String>? = null
            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    val maxAttempts = 3
                    var lastError: Throwable? = null
                    repeat(maxAttempts) { retryIdx ->
                        try {
                            agent = ctx.buildAgent(httpAccess, parameters, ctx.llm)
                            val output = agent!!.run(ctx.prompt)
                            consecutiveFailures = 0  // Reset on success
                            return@withTimeoutOrNull output
                        } catch (e: Throwable) {
                            lastError = e
                            consecutiveFailures++

                            // Circuit breaker: stop if too many consecutive failures
                            if (consecutiveFailures >= maxConsecutiveFailures) {
                                printlnColor(
                                    AnsiColor.RED,
                                    "[SubAgent] Circuit breaker triggered after $consecutiveFailures consecutive failures"
                                )
                                emit(
                                    "sub_agent_failed",
                                    "❌ Sub Agent 触发熔断: ${ctx.name}",
                                    "consecutiveFailures=$consecutiveFailures"
                                )
                                throw MaxRetriesExceededException(
                                    "Circuit breaker: Agent '${ctx.name}' failed $consecutiveFailures consecutive times"
                                )
                            }

                            if (retryIdx < maxAttempts - 1 && shouldRetry(e)) {
                                val backoffMs = 1000L * (retryIdx + 1)
                                emit(
                                    "sub_agent_retrying",
                                    "🔁 Sub Agent 内部重试: ${ctx.name} (${retryIdx + 1}/$maxAttempts)",
                                    e.message
                                )
                                delay(backoffMs)
                            } else {
                                throw e
                            }
                        } finally {
                            try {
                                agent?.close()
                            } catch (_: Throwable) {
                            }
                            agent = null
                        }
                    }
                    throw lastError ?: IllegalStateException("Unknown error running sub agent")
                }

                if (result != null) {
                    emit("sub_agent_completed", "✅ Sub Agent 完成: ${ctx.name}")
                    return result
                } else {
                    // Timeout occurred
                    printlnColor(
                        AnsiColor.YELLOW,
                        "[SubAgent] Agent '${ctx.name}' timed out after ${timeoutMs / 1000}s. " +
                                "Attempt ${attemptIdx + 1}/$maxRestarts. Restarting..."
                    )
                    emit(
                        "sub_agent_timeout",
                        "⏱️ Sub Agent 超时: ${ctx.name}",
                        "attempt=${attemptIdx + 1}/$maxRestarts"
                    )

                    // Exponential backoff between restart attempts
                    if (attemptIdx < maxRestarts - 1) {
                        val backoffMs = minOf(1000L * (1 shl attemptIdx), 30_000L)  // Cap at 30s
                        delay(backoffMs)
                    }
                }
            } catch (e: MaxRetriesExceededException) {
                // Circuit breaker triggered - don't retry
                emit("sub_agent_failed", "❌ Sub Agent 超出重试上限: ${ctx.name}", e.message)
                throw e
            } catch (e: Throwable) {
                // Non-timeout exception
                printlnColor(
                    AnsiColor.RED,
                    "[SubAgent] Agent '${ctx.name}' failed with error: ${e.message}. " +
                            "Attempt ${attemptIdx + 1}/$maxRestarts"
                )
                emit(
                    "sub_agent_failed",
                    "❌ Sub Agent 执行失败: ${ctx.name}",
                    "attempt=${attemptIdx + 1}/$maxRestarts, error=${e.message}"
                )

                if (attemptIdx >= maxRestarts - 1) {
                    throw MaxRetriesExceededException(
                        "Agent '${ctx.name}' failed after $maxRestarts restart attempts",
                        e
                    )
                }

                // Exponential backoff
                val backoffMs = minOf(1000L * (1 shl attemptIdx), 30_000L)
                delay(backoffMs)
            } finally {
                try {
                    agent?.close()
                } catch (_: Throwable) {
                }
            }
        }

        // Should never reach here due to repeat() logic, but add for safety
        throw MaxRetriesExceededException(
            "Agent '${ctx.name}' failed to complete after $maxRestarts restart attempts"
        )
    }

    /**
     * Exception thrown when maximum retry attempts are exceeded.
     */
    class MaxRetriesExceededException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    @Tool
    @LLMDescription("Run multiple sub agents in parallel")
    suspend fun runSubAgentInParallel(
        @LLMDescription("contexts for the sub-agents")
        agentContexts: List<SubAgentContext>,
        @LLMDescription("maximum number of sub agents to run in parallel")
        parallelism: Int = 3,
        @LLMDescription("maximum execution time in seconds for a single sub agent before it is abandoned and restarted; <= 0 disables the timeout")
        maxExecutionTimeInSeconds: Int = 0
    ): String = supervisorScope {
        logProgress(
            AnsiColor.CYAN,
            "SubAgentTools",
            "Running ${agentContexts.size} sub-agents in parallel with parallelism=$parallelism and maxExecutionTimeInSeconds=$maxExecutionTimeInSeconds"
        )
        emit(
            "sub_agent_parallel_starting",
            "🚀 并行启动 Sub Agent: ${agentContexts.size} 个",
            "parallelism=$parallelism, timeout=$maxExecutionTimeInSeconds"
        )
        if (agentContexts.isEmpty()) return@supervisorScope "Done. No agents to run."
        val maxParallelism = if (parallelism <= 0) 1 else parallelism
        val semaphore = Semaphore(maxParallelism)
        val timeoutMs = if (maxExecutionTimeInSeconds > 0) maxExecutionTimeInSeconds.toLong() * 1000L else 0L
        val deferred = agentContexts.map { ctx ->
            async {
                semaphore.withPermit {
                    try {
                        val result: String = if (timeoutMs <= 0L) {
                            // No timeout configured: run normally with built-in retries
                            runSubAgent(ctx)
                        } else {
                            // With timeout: if a run times out, abandon and restart a fresh sub-agent
                            runSubAgentWithTimeoutRestarts(ctx, timeoutMs)
                        }
                        ctx to Result.success(result)
                    } catch (e: Throwable) {
                        ctx to Result.failure(e)
                    }
                }
            }
        }
        val results = deferred.awaitAll()
        emit(
            "sub_agent_parallel_completed",
            "✅ 并行 Sub Agent 执行完成",
            "success=${results.count { it.second.isSuccess }}, failed=${results.count { it.second.isFailure }}"
        )
        // We do not throw; we aggregate results so that partial successes are preserved
        results.joinToString("\n\n") { pair ->
            val (ctx, res) = pair
            if (res.isSuccess) {
                "The result for agent with name ${ctx.name} is:\n${res.getOrNull()}"
            } else {
                "The result for agent with name ${ctx.name} failed with error: ${res.exceptionOrNull()?.message}"
            }
        }
    }

    @Tool
    @LLMDescription("Get a list of all available LLMs that can be used for sub-agent creation. You MUST use one of these models when creating sub-agents to avoid using unsupported models.")
    fun getAvailableLLMs(
    ): List<LLM> {
        return llmGroupConfig.models.map { modelConfig: LLModelConfig ->
            LLM(
                provider = modelConfig.provider,
                model = modelConfig.model
            )
        }
    }
}
