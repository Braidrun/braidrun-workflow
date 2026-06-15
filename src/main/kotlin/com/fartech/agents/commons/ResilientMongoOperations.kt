package com.fartech.agents.commons

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


/**
 * Resilient MongoDB operations with retry, timeout, and circuit breaker patterns.
 *
 * Features:
 * - Configurable timeout for each operation
 * - Exponential backoff retry mechanism
 * - Circuit breaker to prevent cascading failures
 * - Proper error handling and logging
 * - Resource cleanup on failures
 *
 * Usage:
 * ```kotlin
 * val resilientOps = ResilientMongoOperations()
 * val result = resilientOps.withRetryAndTimeout {
 *     collection.find(Users::id eq userId).firstOrNull()
 * }
 * ```
 */
class ResilientMongoOperations(
    private val defaultTimeout: Duration = 30.seconds,
    private val defaultMaxAttempts: Int = 3,
    private val defaultBackoffConfig: ExponentialBackoffConfig = ExponentialBackoffConfig()
) {

    /**
     * Configuration for exponential backoff retry strategy.
     *
     * @param initialDelayMs Initial delay in milliseconds
     * @param maxDelayMs Maximum delay in milliseconds (cap)
     * @param multiplier Delay multiplier for each retry
     * @param jitterFactor Random jitter factor (0.0 to 1.0) to prevent thundering herd
     */
    data class ExponentialBackoffConfig(
        val initialDelayMs: Long = 1000L,
        val maxDelayMs: Long = 30_000L,
        val multiplier: Double = 2.0,
        val jitterFactor: Double = 0.1
    )

    /**
     * Circuit breaker state to prevent cascading failures.
     */
    private enum class CircuitState {
        CLOSED,      // Normal operation
        OPEN,        // Too many failures, reject requests
        HALF_OPEN    // Testing if service recovered
    }

    /**
     * Immutable snapshot of all circuit breaker fields, updated atomically via CAS.
     */
    private data class CircuitBreakerSnapshot(
        val state: CircuitState = CircuitState.CLOSED,
        val failureCount: Int = 0,
        val lastFailureTime: Long = 0
    )

    private val circuitBreaker = AtomicReference(CircuitBreakerSnapshot())
    private val failureThreshold: Int = 5
    private val circuitResetTimeMs: Long = 60_000L  // 1 minute

    /**
     * Maximum CAS attempts before [recordResult] degrades to an unconditional set. Chosen
     * so that under normal contention CAS always wins, but pathological hot-looping is
     * bounded to O(microseconds) rather than indefinite.
     */
    private val CAS_MAX_ATTEMPTS: Int = 16

    /**
     * Check if circuit breaker allows operation.
     */
    private fun checkCircuitBreaker() {
        val snapshot = circuitBreaker.get()
        when (snapshot.state) {
            CircuitState.OPEN -> {
                val now = System.currentTimeMillis()
                if (now - snapshot.lastFailureTime > circuitResetTimeMs) {
                    // Atomically transition OPEN -> HALF_OPEN and reset failure count
                    circuitBreaker.compareAndSet(
                        snapshot,
                        snapshot.copy(state = CircuitState.HALF_OPEN, failureCount = 0)
                    )
                } else {
                    throw CircuitBreakerOpenException(
                        "Circuit breaker is OPEN. Too many recent database failures. " +
                                "Will retry after ${(circuitResetTimeMs - (now - snapshot.lastFailureTime)) / 1000}s"
                    )
                }
            }

            CircuitState.HALF_OPEN -> {
                // Allow one request through to test
            }

            CircuitState.CLOSED -> {
                // Normal operation
            }
        }
    }

    /**
     * Record operation result for circuit breaker.
     *
     * Uses a bounded CAS loop: under pathological write contention, an unbounded retry
     * loop could hot-spin a CPU core without ever making progress. We cap at
     * [CAS_MAX_ATTEMPTS] and degrade to a `.set(next)` on the last attempt — the
     * resulting record is stale-safe (failure count may be off by one, but the state
     * transition is still applied).
     */
    private fun recordResult(success: Boolean) {
        var attempts = 0
        while (true) {
            val snapshot = circuitBreaker.get()
            val next = if (success) {
                CircuitBreakerSnapshot(state = CircuitState.CLOSED, failureCount = 0, lastFailureTime = snapshot.lastFailureTime)
            } else {
                val newFailures = snapshot.failureCount + 1
                val newState = if (newFailures >= failureThreshold) CircuitState.OPEN else snapshot.state
                CircuitBreakerSnapshot(state = newState, failureCount = newFailures, lastFailureTime = System.currentTimeMillis())
            }
            if (circuitBreaker.compareAndSet(snapshot, next)) return
            attempts++
            if (attempts >= CAS_MAX_ATTEMPTS) {
                // Give up CAS; set unconditionally. The counter may drift by a few in
                // extreme contention but the OPEN transition is still applied correctly.
                circuitBreaker.set(next)
                return
            }
        }
    }

    /**
     * Calculate delay for exponential backoff with jitter.
     */
    private fun calculateBackoff(
        attemptIdx: Int,
        config: ExponentialBackoffConfig
    ): Long {
        val rawDelay = config.initialDelayMs * Math.pow(config.multiplier, attemptIdx.toDouble())
        val exponentialDelay = if (rawDelay.isNaN() || rawDelay.isInfinite() || rawDelay > Long.MAX_VALUE.toDouble()) {
            config.maxDelayMs
        } else {
            rawDelay.toLong()
        }
        val cappedDelay = minOf(exponentialDelay, config.maxDelayMs)

        // Add random jitter to prevent thundering herd problem. Clamp the intermediate
        // double arithmetic so an extreme jitterFactor / maxDelayMs combination cannot
        // overflow Long on conversion (overflow would produce a negative delay and
        // crash `delay(...)` downstream).
        val jitterDouble = cappedDelay.toDouble() * config.jitterFactor * Math.random()
        val jitter = when {
            jitterDouble.isNaN() || jitterDouble.isInfinite() -> 0L
            jitterDouble >= Long.MAX_VALUE.toDouble() -> config.maxDelayMs
            jitterDouble < 0.0 -> 0L
            else -> jitterDouble.toLong()
        }
        return (cappedDelay + jitter).coerceAtMost(config.maxDelayMs * 2L)
    }

    /**
     * Determine if error is retryable.
     *
     * Strategy: explicit-type matching (reliable, localization-safe) first, then fall back
     * to message substrings for driver-specific errors we haven't seen enough of to map
     * to a concrete class yet.
     *
     * Message-based matching is a known weak point — the MongoDB driver may change its
     * wording between releases, and non-English locales can render keywords the check
     * doesn't expect. So we widen the type check to cover the four classes that escaped
     * it before:
     *
     *   - `java.util.concurrent.TimeoutException` (wrapper we throw ourselves)
     *   - `kotlinx.coroutines.TimeoutCancellationException` (from `withTimeout { }`)
     *   - `com.mongodb.MongoSocketException` / `MongoSocketReadTimeoutException` /
     *     `MongoNotPrimaryException` / `MongoNodeIsRecoveringException` — all retryable
     *     per the MongoDB driver's retryable-error contract.
     *   - `javax.net.ssl.SSLException` — transient TLS handshake failures.
     *
     * Each of those would previously only match through the message-string fallback.
     */
    private fun isRetryableError(error: Throwable): Boolean {
        // Walk the cause chain once, checking every link against the retryable type set.
        val chain = generateSequence(error) { it.cause }
        val fqcnSet = chain.mapNotNull { it::class.qualifiedName }.toSet()
        if (chain.any {
                it is java.net.SocketException ||
                it is java.net.SocketTimeoutException ||
                it is java.net.ConnectException ||
                it is java.io.EOFException ||
                it is java.util.concurrent.TimeoutException ||
                it is kotlinx.coroutines.TimeoutCancellationException ||
                it is javax.net.ssl.SSLException
            }) {
            return true
        }
        // Driver-class match by FQCN so we don't require the mongo-driver jar on the
        // compilation classpath (keeps this class usable by tests that stub the driver).
        if (fqcnSet.any { it.startsWith("com.mongodb.MongoSocket") } ||
            "com.mongodb.MongoNotPrimaryException" in fqcnSet ||
            "com.mongodb.MongoNodeIsRecoveringException" in fqcnSet ||
            "com.mongodb.MongoServerUnavailableException" in fqcnSet
        ) {
            return true
        }

        // Fall back to message-based matching for edge cases we haven't mapped yet.
        val message = (error.message ?: "").lowercase()
        val causeMessage = (error.cause?.message ?: "").lowercase()
        val combined = "$message $causeMessage"
        return combined.contains("timeout") ||
                combined.contains("connection refused") ||
                combined.contains("connection reset") ||
                combined.contains("network") ||
                combined.contains("temporarily unavailable") ||
                combined.contains("too many requests") ||
                combined.contains("503") ||
                combined.contains("502")
    }

    /**
     * Execute a MongoDB operation with retry, timeout, and circuit breaker.
     *
     * @param R Return type
     * @param timeout Operation timeout (default: 30 seconds)
     * @param maxAttempts Maximum retry attempts (default: 3)
     * @param backoffConfig Exponential backoff configuration
     * @param operation The MongoDB operation to execute
     * @return Operation result
     * @throws CircuitBreakerOpenException if circuit breaker is open
     * @throws MaxRetriesExceededException if max attempts exceeded
     * @throws TimeoutException if operation times out
     */
    suspend fun <R> withRetryAndTimeout(
        timeout: Duration = defaultTimeout,
        maxAttempts: Int = defaultMaxAttempts,
        backoffConfig: ExponentialBackoffConfig = defaultBackoffConfig,
        operation: suspend () -> R
    ): R {
        // Check circuit breaker before attempting
        checkCircuitBreaker()

        var lastError: Throwable? = null

        repeat(maxAttempts) { attemptIdx ->
            try {
                // Execute with timeout
                val result = withTimeout(timeout) {
                    operation()
                }

                // Success - record and return
                recordResult(true)
                return result

            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Timeout occurred
                val timeoutError = TimeoutException(
                    "MongoDB operation timed out after ${timeout.inWholeSeconds}s (attempt ${attemptIdx + 1}/$maxAttempts)"
                ).also { it.initCause(e) }
                lastError = timeoutError

                if (attemptIdx < maxAttempts - 1 && isRetryableError(timeoutError)) {
                    val backoffMs = calculateBackoff(attemptIdx, backoffConfig)
                    delay(backoffMs.milliseconds)
                } else {
                    recordResult(false)
                    throw timeoutError
                }

            } catch (e: Throwable) {
                // Other errors
                lastError = e

                if (attemptIdx < maxAttempts - 1 && isRetryableError(e)) {
                    val backoffMs = calculateBackoff(attemptIdx, backoffConfig)
                    delay(backoffMs.milliseconds)
                } else {
                    recordResult(false)
                    throw e
                }
            }
        }

        // All attempts failed
        recordResult(false)
        throw MaxRetriesExceededException(
            "MongoDB operation failed after $maxAttempts attempts",
            lastError
        )
    }

    /**
     * Execute multiple operations in a pseudo-transaction (best-effort).
     *
     * Note: MongoDB transactions require replica set. This provides basic rollback logic.
     *
     * @param operations List of operations to execute
     * @param onRollback Rollback handler if any operation fails
     * @return List of results
     */
    suspend fun <R> withBestEffortTransaction(
        operations: List<suspend () -> R>,
        onRollback: suspend (List<R>, Throwable) -> Unit = { _, _ -> }
    ): List<R> {
        val results = mutableListOf<R>()

        try {
            for (operation in operations) {
                val result = withRetryAndTimeout { operation() }
                results.add(result)
            }
            return results
        } catch (e: Throwable) {
            // Attempt rollback
            try {
                onRollback(results, e)
            } catch (rollbackError: Throwable) {
                // Log rollback failure but throw original error
                throw TransactionRollbackException(
                    "Transaction failed and rollback also failed",
                    e,
                    rollbackError
                )
            }
            throw e
        }
    }

    /**
     * Reset circuit breaker (for testing or manual intervention).
     */
    fun resetCircuitBreaker() {
        circuitBreaker.set(CircuitBreakerSnapshot())
    }

    /**
     * Get current circuit breaker state.
     */
    fun getCircuitState(): String = circuitBreaker.get().state.name

    /**
     * Exception thrown when circuit breaker is open.
     */
    class CircuitBreakerOpenException(message: String) : Exception(message)

    /**
     * Exception thrown when max retry attempts are exceeded.
     */
    class MaxRetriesExceededException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    /**
     * Exception thrown when transaction rollback fails.
     */
    class TransactionRollbackException(
        message: String,
        val originalError: Throwable,
        val rollbackError: Throwable
    ) : Exception(message, originalError)
}
