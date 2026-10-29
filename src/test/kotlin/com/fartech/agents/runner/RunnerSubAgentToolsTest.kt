package com.fartech.agents.runner

import com.fartech.agents.tools.LLM
import com.fartech.agents.tools.SubAgentContext
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RunnerSubAgentToolsTest {
    private val model = LLM("openai", "frozen-model")
    private fun ctx(name: String = "child", iterations: Int = 8) = SubAgentContext("task", name, model, maxIterations = iterations)
    private fun tools(budget: RunnerSubAgentBudget = RunnerSubAgentBudget(), fence: RunnerBusinessFence = RunnerBusinessFence(),
                      guard: () -> Unit = {}, run: suspend (SubAgentContext) -> String = { "ok" }) =
        RunnerSubAgentTools(model, 8, budget, fence, guard, run)

    @Test fun `model iterations reasoning and prompt are validated before any child starts`(): Unit = runBlocking {
        var starts = 0
        val tool = tools { starts++; "ok" }
        for (bad in listOf(ctx().copy(llm = LLM("other", "frozen-model")), ctx(iterations = 9), ctx(iterations = 0),
            ctx().copy(prompt = "x".repeat(65537)), ctx().copy(name = ""), ctx().copy(reasoningInterval = 2))) {
            assertFailsWith<IllegalArgumentException> { tool.runSubAgent(bad) }
        }
        assertEquals(0, starts)
        assertEquals(listOf(model), tool.getAvailableLLMs())
    }

    @Test fun `single child failures are never retried`(): Unit = runBlocking {
        var starts = 0
        val tool = tools { starts++; throw java.io.IOException("503 temporarily unavailable") }
        assertFailsWith<java.io.IOException> { tool.runSubAgent(ctx()) }
        assertEquals(1, starts)
    }

    @Test fun `shared total child budget covers separate parents and failed attempts`(): Unit = runBlocking {
        val budget = RunnerSubAgentBudget()
        val failing = tools(budget) { error("failed") }
        repeat(63) { assertFailsWith<IllegalStateException> { failing.runSubAgent(ctx()) } }
        assertEquals("ok", tools(budget).runSubAgent(ctx()))
        assertFailsWith<IllegalArgumentException> { tools(budget).runSubAgent(ctx()) }
    }

    @Test fun `shared concurrency across parents never exceeds eight`(): Unit = runBlocking {
        val budget = RunnerSubAgentBudget(); val active = AtomicInteger(); val high = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val work: suspend (SubAgentContext) -> String = {
            val count = active.incrementAndGet(); high.updateAndGet { maxOf(it, count) }
            if (count == 8) allStarted.complete(Unit)
            try { release.await(); "ok" } finally { active.decrementAndGet() }
        }
        val a = async { tools(budget, run = work).runSubAgentInParallel((1..8).map { ctx("a$it") }, 8) }
        val b = async { tools(budget, run = work).runSubAgentInParallel((1..8).map { ctx("b$it") }, 8) }
        withTimeout(3000) { allStarted.await() }
        assertEquals(8, active.get()); release.complete(Unit); a.await(); b.await()
        assertEquals(8, high.get()); assertEquals(0, active.get())
    }

    @Test fun `parallel child failure cancels its siblings without retry`(): Unit = runBlocking {
        val waiting = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val starts = AtomicInteger()
        val tool = tools { context ->
            starts.incrementAndGet()
            if (context.name == "bad") { waiting.await(); error("boom") }
            try { waiting.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        assertFailsWith<IllegalStateException> { tool.runSubAgentInParallel(listOf(ctx("bad"), ctx("wait")), 2) }
        assertTrue(cancelled.isCompleted); assertEquals(2, starts.get())
    }

    @Test fun `cancellation is propagated and host finalization stops children`(): Unit = runBlocking {
        val fence = RunnerBusinessFence(); val started = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val tool = tools(fence = fence) { try { started.complete(Unit); awaitCancellation() } finally { closed.complete(Unit) } }
        val child = async { tool.runSubAgent(ctx()) }
        started.await(); fence.close()
        assertFailsWith<CancellationException> { child.await() }
        assertTrue(closed.isCompleted)
    }

    @Test fun `depth one refuses recursive calls before taking another slot`(): Unit = runBlocking {
        lateinit var tool: RunnerSubAgentTools
        tool = tools { tool.runSubAgent(ctx("nested")) }
        assertFailsWith<IllegalArgumentException> { tool.runSubAgent(ctx()) }
    }

    @Test fun `batch bounds and unsafe timeout are rejected atomically`(): Unit = runBlocking {
        var starts = 0; val tool = tools { starts++; "ok" }
        for (n in listOf(0, 9)) assertFailsWith<IllegalArgumentException> { tool.runSubAgentInParallel(listOf(ctx()), n) }
        for (seconds in listOf(-1, 301)) assertFailsWith<IllegalArgumentException> { tool.runSubAgentInParallel(listOf(ctx()), 1, seconds) }
        assertFailsWith<IllegalArgumentException> { tool.runSubAgentInParallel(List(65) { ctx() }) }
        assertFailsWith<IllegalArgumentException> { tool.runSubAgentInParallel(listOf(ctx(), ctx(iterations = 9))) }
        assertEquals(0, starts)
    }

    @Test fun `deadline cancels a child once without restarting it`(): Unit = runBlocking {
        var starts = 0; var stops = 0
        val tool = tools { starts++; try { awaitCancellation() } finally { stops++ } }
        assertFailsWith<TimeoutCancellationException> { tool.runSubAgentInParallel(listOf(ctx()), 1, 1) }
        assertEquals(1, starts); assertEquals(1, stops)
    }

    @Test fun `revoked scope and oversized outputs cannot be published`(): Unit = runBlocking {
        var live = true
        val tool = tools(guard = { check(live) }) { live = false; "private output" }
        assertFailsWith<IllegalStateException> { tool.runSubAgent(ctx()) }
        assertFailsWith<IllegalStateException> { tool.getAvailableLLMs() }
        assertFailsWith<IllegalArgumentException> { tools { "x".repeat(131073) }.runSubAgent(ctx()) }
        assertFailsWith<IllegalArgumentException> { tools { "x".repeat(131072) }.runSubAgentInParallel(List(9) { ctx() }, 8) }
    }
}
