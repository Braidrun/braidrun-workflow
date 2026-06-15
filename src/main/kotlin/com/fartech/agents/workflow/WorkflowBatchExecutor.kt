package com.fartech.agents.workflow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 通用批量执行器，用于在多个独立工作单元之间复用统一的并发控制逻辑。
 *
 * 这里的并发粒度是“多个工作流执行任务”，而不是单个工作流内部的 DAG 分层并发。
 */
class WorkflowBatchExecutor(
    val maxConcurrency: Int = 0
) {
    init {
        require(maxConcurrency >= 0) { "maxConcurrency must be >= 0 (0 means unlimited)" }
    }

    suspend fun <T, R> execute(
        items: List<BatchExecutionItem<T>>,
        runner: suspend (BatchExecutionItem<T>) -> R
    ): BatchExecutionSummary<R> = supervisorScope {
        val batchStartedAt = System.currentTimeMillis()
        val semaphore = if (maxConcurrency > 0) Semaphore(maxConcurrency) else null

        val results = items.map { item ->
            async {
                val startedAt = System.currentTimeMillis()
                val outcome = runCatching {
                    if (semaphore != null) {
                        semaphore.withPermit {
                            runner(item)
                        }
                    } else {
                        runner(item)
                    }
                }
                val completedAt = System.currentTimeMillis()

                BatchExecutionResult(
                    id = item.id,
                    label = item.label,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    duration = (completedAt - startedAt).coerceAtLeast(0),
                    value = outcome.getOrNull(),
                    error = outcome.exceptionOrNull()?.message,
                    exception = outcome.exceptionOrNull()
                )
            }
        }.awaitAll()

        val batchCompletedAt = System.currentTimeMillis()
        BatchExecutionSummary(
            startedAt = batchStartedAt,
            completedAt = batchCompletedAt,
            duration = (batchCompletedAt - batchStartedAt).coerceAtLeast(0),
            maxConcurrency = maxConcurrency,
            results = results
        )
    }
}

data class BatchExecutionItem<T>(
    val id: String,
    val label: String = id,
    val payload: T
)

data class BatchExecutionResult<R>(
    val id: String,
    val label: String,
    val startedAt: Long,
    val completedAt: Long,
    val duration: Long,
    val value: R? = null,
    val error: String? = null,
    val exception: Throwable? = null
) {
    val success: Boolean get() = exception == null
}

data class BatchExecutionSummary<R>(
    val startedAt: Long,
    val completedAt: Long,
    val duration: Long,
    val maxConcurrency: Int,
    val results: List<BatchExecutionResult<R>>
) {
    val totalCount: Int get() = results.size
    val successCount: Int get() = results.count { it.success }
    val failureCount: Int get() = results.count { !it.success }
    val allSucceeded: Boolean get() = results.all { it.success }
}
