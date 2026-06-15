package com.fartech.agents.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowBatchExecutorTest {

    @Test
    fun `batch executor honors max concurrency`() = runBlocking {
        val activeCount = AtomicInteger(0)
        val peakConcurrency = AtomicInteger(0)
        val executor = WorkflowBatchExecutor(maxConcurrency = 2)
        val items = (1..5).map { index ->
            BatchExecutionItem(
                id = "wf-$index",
                label = "workflow-$index",
                payload = index
            )
        }

        val summary = executor.execute(items) { item ->
            val current = activeCount.incrementAndGet()
            peakConcurrency.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(120)
                item.payload * 10
            } finally {
                activeCount.decrementAndGet()
            }
        }

        assertTrue(summary.allSucceeded)
        assertEquals(5, summary.totalCount)
        assertEquals(2, peakConcurrency.get())
        assertEquals(listOf(10, 20, 30, 40, 50), summary.results.map { it.value })
    }

    @Test
    fun `batch executor keeps other workflows running after one failure`() = runBlocking {
        val executor = WorkflowBatchExecutor(maxConcurrency = 3)
        val items = listOf(
            BatchExecutionItem(id = "wf-a", label = "workflow-a", payload = "a"),
            BatchExecutionItem(id = "wf-b", label = "workflow-b", payload = "b"),
            BatchExecutionItem(id = "wf-c", label = "workflow-c", payload = "c")
        )

        val summary = executor.execute(items) { item ->
            delay(50)
            if (item.payload == "b") {
                error("workflow-b failed")
            }
            item.payload.uppercase()
        }

        assertFalse(summary.allSucceeded)
        assertEquals(2, summary.successCount)
        assertEquals(1, summary.failureCount)
        assertEquals("A", summary.results[0].value)
        assertEquals("workflow-b failed", summary.results[1].error)
        assertEquals("C", summary.results[2].value)
    }
}
