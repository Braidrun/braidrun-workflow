package com.fartech.agents.runner

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.tools.LLM
import com.fartech.agents.tools.SubAgentContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One budget per root attempt, shared across all parent steps and child workflow invocations. */
internal class RunnerSubAgentBudget {
    val slots = Semaphore(8)
    private val calls = AtomicInteger()
    private val requests = AtomicInteger()
    private fun consume(counter: AtomicInteger, limit: Int, count: Int) {
        while (true) {
            val old = counter.get()
            require(count > 0 && old <= limit - count) { "Runner sub-agent shared budget exhausted" }
            if (counter.compareAndSet(old, old + count)) return
        }
    }
    fun reserveCalls(count: Int) = consume(calls, 64, count)
    fun modelRequest() = consume(requests, 256, 1)
}

private class SubAgentDepth : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SubAgentDepth>
}

/** Hosted substitute; never retries, reparses tools, loads skills or chooses a fallback model. */
@LLMDescription("Run bounded sub agents using the parent's approved model, tools and skills")
class RunnerSubAgentTools internal constructor(
    private val model: LLM,
    private val parentMaxIterations: Int,
    private val budget: RunnerSubAgentBudget,
    private val fence: RunnerBusinessFence,
    private val checkLive: () -> Unit,
    private val runOnce: suspend (SubAgentContext) -> String,
) : ToolSet {
    init { require(parentMaxIterations > 0) }
    private suspend fun validate(contexts: List<SubAgentContext>) {
        currentCoroutineContext().ensureActive()
        require(currentCoroutineContext()[SubAgentDepth] == null) { "Runner sub-agent nesting depth is limited to one" }
        checkLive()
        require(contexts.size in 1..64) { "Runner sub-agent batch size must be 1..64" }
        contexts.forEach {
            require(it.llm == model) { "Sub-agent model differs from the parent's resolved leased model" }
            require(it.attachments.isEmpty()) { "Sub-agent attachments need a separate confined adapter" }
            require(it.name.isNotBlank() && it.name.toByteArray().size <= 256) { "Invalid sub-agent name" }
            require(it.prompt.toByteArray().size <= 65536) { "Sub-agent prompt exceeds 64 KiB" }
            require(it.maxIterations in 1..minOf(parentMaxIterations, 128)) { "Sub-agent iterations exceed the parent limit" }
            require(it.reasoningInterval == 1) { "Sub-agent reasoning reconfiguration is not admitted" }
        }
    }
    private suspend fun execute(context: SubAgentContext, seconds: Int): String =
        withTimeout(seconds * 1000L) {
            budget.slots.withPermit {
                withContext(SubAgentDepth()) {
                    fence.operation {
                        checkLive()
                        runOnce(context).also {
                            currentCoroutineContext().ensureActive(); checkLive()
                            require(it.toByteArray().size <= 131072) { "Sub-agent output exceeds 128 KiB" }
                        }
                    }
                }
            }
        }

    @Tool @LLMDescription("Run one child once, with a five minute deadline; failures are not retried")
    suspend fun runSubAgent(agentContext: SubAgentContext): String {
        validate(listOf(agentContext)); budget.reserveCalls(1)
        return execute(agentContext, 300)
    }

    @Tool @LLMDescription("Run children once; any failure cancels siblings. Parallelism is 1..8 and timeout is at most 300 seconds")
    suspend fun runSubAgentInParallel(agentContexts: List<SubAgentContext>, parallelism: Int = 3, maxExecutionTimeInSeconds: Int = 0): String {
        validate(agentContexts)
        require(parallelism in 1..8) { "Runner sub-agent parallelism must be 1..8" }
        require(maxExecutionTimeInSeconds in 0..300) { "Runner sub-agent deadline must be at most 300 seconds" }
        budget.reserveCalls(agentContexts.size)
        val localSlots = Semaphore(parallelism)
        val bytes = AtomicInteger()
        return coroutineScope {
            agentContexts.map { context -> async {
                localSlots.withPermit {
                    val result = "The result for agent with name ${context.name} is:\n" + execute(context, maxExecutionTimeInSeconds.takeIf { it > 0 } ?: 300)
                    require(bytes.addAndGet(result.toByteArray().size + 2) <= 1048576) { "Sub-agent batch output exceeds 1 MiB" }
                    result
                }
            } }.awaitAll().joinToString("\n\n")
        }
    }

    @Tool @LLMDescription("The sole resolved model already leased to this parent scope")
    fun getAvailableLLMs(): List<LLM> { checkLive(); return listOf(model) }
}
