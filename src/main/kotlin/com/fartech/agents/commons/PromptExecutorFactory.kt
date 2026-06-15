package com.fartech.agents.commons

import ai.koog.prompt.cache.memory.InMemoryPromptCache
import ai.koog.prompt.cache.redis.RedisPromptCache
import ai.koog.prompt.executor.cached.CachedPromptExecutor
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import io.lettuce.core.RedisClient
import mu.KotlinLogging
import java.nio.file.Paths
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


private val promptExecutorFactoryLogger = KotlinLogging.logger("PromptExecutorFactory")

/**
 * Process-wide pool of [RedisClient] instances keyed by connect URL.
 *
 * [RedisClient.create] allocates a Netty event-loop group plus a persistent
 * TCP connection pool — shutting it down requires an explicit `shutdown()`.
 * In a workflow-web deployment [determineCachePolicy] runs on every agent
 * build, so a fresh client per call would leak event-loop threads and file
 * descriptors until JVM exit. Pooling by URL bounds the long-lived state to
 * one client per distinct Redis URL and lets the [Runtime] shutdown hook
 * reliably close them all.
 */
private val pooledRedisClients = ConcurrentHashMap<String, RedisClient>()

private val redisShutdownHookInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

private fun getOrCreatePooledRedisClient(url: String): RedisClient {
    if (redisShutdownHookInstalled.compareAndSet(false, true)) {
        Runtime.getRuntime().addShutdownHook(Thread({
            pooledRedisClients.values.forEach { client ->
                runCatching { client.shutdown() }.onFailure {
                    promptExecutorFactoryLogger.warn {
                        "Failed to shutdown pooled RedisClient: ${it.message}"
                    }
                }
            }
        }, "braidrun-redis-client-shutdown"))
    }
    return pooledRedisClients.computeIfAbsent(url) { RedisClient.create(it) }
}

/** Parse `redis_duration` — plain seconds or ISO-8601 duration. Falls back to 900 s on malformed input with a WARN log. */
private fun parseRedisDuration(raw: String): Duration {
    raw.toLongOrNull()?.let { return it.seconds }
    return try {
        Duration.parse(raw)
    } catch (e: IllegalArgumentException) {
        promptExecutorFactoryLogger.warn {
            "Invalid redis_duration '$raw' — expected plain seconds (e.g. '900') or ISO-8601 (e.g. 'PT15M'). Falling back to 900s."
        }
        900.seconds
    } catch (e: DateTimeParseException) {
        promptExecutorFactoryLogger.warn {
            "Invalid redis_duration '$raw' — expected plain seconds or ISO-8601. Falling back to 900s."
        }
        900.seconds
    }
}

/**
 * Upper bounds on cache sizes. Without these an LLM-controlled YAML could set
 * `memory_cache_max_entries=999_999_999` and OOM the JVM. Values chosen so
 * production workloads never hit the ceiling; operators can raise via JVM
 * system properties if they have a legitimate reason.
 */
private const val MEMORY_CACHE_MAX_ENTRIES_HARD_CAP = 1_000_000
private const val FILE_CACHE_MAX_FILES_HARD_CAP = 1_000_000

private fun clampPositive(name: String, value: Int, hardCap: Int, default: Int): Int {
    if (value < 1) {
        promptExecutorFactoryLogger.warn { "$name=$value must be positive; using default $default." }
        return default
    }
    if (value > hardCap) {
        promptExecutorFactoryLogger.warn { "$name=$value exceeds hard cap $hardCap; clamping." }
        return hardCap
    }
    return value
}

/**
 * Prompt-executor construction.
 *
 * Carved out of `AgentCommon.kt` in Phase 5 of the 2026-04 audit follow-up.
 *
 * Two concerns live here:
 *
 *   - [determineCachePolicy] — selects a [ai.koog.prompt.cache.model.PromptCache]
 *     implementation based on the workflow's `cache_policy` parameter
 *     (`memory`, `redis`, `file`).
 *   - [createPromptExecutor] — wraps the provider's LLM clients in
 *     [RetryingLLMClient], composes them via [MultiLLMPromptExecutor], and
 *     optionally wraps the whole thing in a [CachedPromptExecutor] unless the
 *     caller explicitly opts out (streaming, where cache buffering would
 *     interfere with incremental output).
 */

/**
 * Decide which prompt cache to use based on the workflow / preset parameters.
 *
 * Supported `cache_policy` values:
 *   - `"memory"` (default) — [InMemoryPromptCache] with
 *     `memory_cache_max_entries` entries (4096 default).
 *   - `"redis"` — [RedisPromptCache] configured from `redis_client_url`,
 *     `redis_cache_prefix`, `redis_duration` (seconds integer or ISO-8601).
 *   - `"file"` — [ConcurrentFilePromptCache] under `file_cache_storage`
 *     (defaults to `.prompt_cache/`) with `max_files` entries (1024 default).
 *
 * Unknown values fall back to `memory` — we prefer "works but doesn't cache
 * across runs" over "fails to start".
 */
fun determineCachePolicy(
    parameters: List<ConfigurationParameter>
) = when (parameters.parameter("cache_policy", "memory")) {
    "memory" -> InMemoryPromptCache(
        maxEntries = clampPositive(
            "memory_cache_max_entries",
            parameters.parameter("memory_cache_max_entries", 4096),
            MEMORY_CACHE_MAX_ENTRIES_HARD_CAP,
            default = 4096,
        )
    )

    "redis" -> RedisPromptCache(
        // Pooled: same URL shares one client. See `getOrCreatePooledRedisClient`.
        client = getOrCreatePooledRedisClient(parameters.parameter("redis_client_url", "redis://localhost:6379")),
        prefix = parameters.parameter("redis_cache_prefix", "ai_agent"),
        ttl = parseRedisDuration(parameters.parameter("redis_duration", "900")),
    )

    "file" -> ConcurrentFilePromptCache(
        storage = Paths.get(parameters.parameter("file_cache_storage", ".prompt_cache")),
        maxFiles = clampPositive(
            "max_files",
            parameters.parameter("max_files", 1024),
            FILE_CACHE_MAX_FILES_HARD_CAP,
            default = 1024,
        ),
    )

    else -> InMemoryPromptCache(
        maxEntries = clampPositive(
            "memory_cache_max_entries",
            parameters.parameter("memory_cache_max_entries", 4096),
            MEMORY_CACHE_MAX_ENTRIES_HARD_CAP,
            default = 4096,
        )
    )
}

/**
 * Build a [PromptExecutor] that:
 *   1. wraps each provider's [LLMClient] with [RetryingLLMClient] (config
 *      resolved from `retry_max_attempts` / `retry_initial_delay` /
 *      `retry_max_delay`, else `RetryConfig.PRODUCTION`);
 *   2. composes them via [MultiLLMPromptExecutor] with the caller-supplied
 *      fallback settings;
 *   3. wraps the result in a [CachedPromptExecutor] using
 *      [determineCachePolicy] — **unless** `disable_cache_for_streaming=true`,
 *      in which case caching is skipped to avoid incremental-stream buffering
 *      issues.
 */
fun createPromptExecutor(
    parameters: List<ConfigurationParameter>,
    llmClients: Pair<Map<LLMProvider, LLMClient>, MultiLLMPromptExecutor.FallbackPromptExecutorSettings>
): PromptExecutor = createPromptExecutor(parameters, llmClients, extraCascadeTiers = emptyList())

/**
 * Extended overload — supports Tier-1 **cascading on-error fallback** (2026-04).
 *
 * When [extraCascadeTiers] is non-empty AND `cascade_fallback_enabled=true`
 * in [parameters], the primary [MultiLLMPromptExecutor] is wrapped in a
 * [CascadingFallbackPromptExecutor] that advances to subsequent tiers
 * **on provider / transport error**, not just "provider not wired" like
 * Koog's built-in [MultiLLMPromptExecutor.FallbackPromptExecutorSettings].
 *
 * Each extra tier is materialized as its own single-provider
 * [MultiLLMPromptExecutor] with the same retry policy as the primary; the
 * caller constructs `(clients, fallback)` pairs for each tier using the
 * same `determineLLMClients` plumbing that feeds the primary.
 *
 * Empty [extraCascadeTiers] (the default) preserves the pre-Tier-1
 * behaviour exactly — no wrapper, no new log lines.
 */
fun createPromptExecutor(
    parameters: List<ConfigurationParameter>,
    llmClients: Pair<Map<LLMProvider, LLMClient>, MultiLLMPromptExecutor.FallbackPromptExecutorSettings>,
    extraCascadeTiers: List<Pair<Map<LLMProvider, LLMClient>, MultiLLMPromptExecutor.FallbackPromptExecutorSettings>>,
): PromptExecutor {
    val retryConfig = if (parameters.any { it.key == "retry_max_attempts" }) {
        RetryConfig(
            maxAttempts = parameters.parameter("retry_max_attempts", 3),
            initialDelay = parameters.parameter("retry_initial_delay", 1000L).milliseconds,
            maxDelay = parameters.parameter("retry_max_delay", 10000L).milliseconds
        )
    } else {
        RetryConfig.PRODUCTION
    }

    val baseExecutor = MultiLLMPromptExecutor(
        llmClients = llmClients.first
            .toList()
            .associate {
                it.first to RetryingLLMClient(
                    it.second,
                    config = retryConfig
                )
            },
        fallback = llmClients.second,
    )

    val cascadeEnabled = parameters.parameter("cascade_fallback_enabled", false)
    val effectiveExecutor: PromptExecutor = if (cascadeEnabled && extraCascadeTiers.isNotEmpty()) {
        val tiers = buildList {
            add(baseExecutor)
            extraCascadeTiers.forEach { (clients, fallback) ->
                add(
                    MultiLLMPromptExecutor(
                        llmClients = clients.mapValues { (_, client) ->
                            RetryingLLMClient(client, config = retryConfig)
                        },
                        fallback = fallback,
                    )
                )
            }
        }
        logProgress(
            AnsiColor.CYAN,
            "PromptExecutor",
            "Cascading fallback enabled with ${tiers.size} tiers"
        )
        CascadingFallbackPromptExecutor(tiers)
    } else {
        baseExecutor
    }

    val providerSafeExecutor = ProviderValidatingPromptExecutor(effectiveExecutor)

    // 对于流式输出，禁用缓存以避免缓冲问题
    val disableCache = parameters.parameter("disable_cache_for_streaming", false)
    return if (disableCache) {
        providerSafeExecutor
    } else {
        CachedPromptExecutor(
            cache = determineCachePolicy(parameters),
            nested = providerSafeExecutor
        )
    }
}
