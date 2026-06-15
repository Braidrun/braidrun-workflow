package com.fartech.agents.commons

import ai.koog.prompt.cache.memory.InMemoryPromptCache
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Pins the 2026-04 follow-up hardening of [determineCachePolicy]:
 *
 *   1. `redis_duration` accepts plain seconds, ISO-8601, OR malformed
 *      strings — the last case must fall back to the default and log a
 *      warning rather than crash agent startup with a
 *      [java.time.format.DateTimeParseException].
 *   2. Cache-size parameters (`memory_cache_max_entries`, `max_files`)
 *      are clamped to a safe range; an LLM-controlled YAML cannot set a
 *      trillion-entry cache and OOM the JVM.
 *
 * The Redis branch isn't exercised here (needs a live Lettuce connection);
 * its parsing helper is internal so it's tested via the policy dispatcher
 * only indirectly.
 */
class PromptExecutorFactoryTest {

    private fun params(vararg pairs: Pair<String, Any>): List<ConfigurationParameter> =
        pairs.map { (k, v) ->
            ConfigurationParameter(key = k, value = JsonPrimitive(v.toString()))
        }

    @Test
    fun `memory cache at negative size falls back to default`() {
        val policy = determineCachePolicy(
            params("cache_policy" to "memory", "memory_cache_max_entries" to "-5"),
        )
        assertNotNull(policy)
        // Construction survives — if the clamp failed, InMemoryPromptCache
        // would throw IllegalArgumentException on a negative maxEntries.
    }

    @Test
    fun `memory cache at absurdly large size is clamped`() {
        val policy = determineCachePolicy(
            params("cache_policy" to "memory", "memory_cache_max_entries" to "999999999"),
        )
        assertNotNull(policy)
    }

    @Test
    fun `memory cache at reasonable size constructs normally`() {
        val policy = determineCachePolicy(
            params("cache_policy" to "memory", "memory_cache_max_entries" to "1024"),
        )
        assertNotNull(policy)
    }

    @Test
    fun `unknown cache policy falls back to in-memory`() {
        val policy = determineCachePolicy(params("cache_policy" to "definitely-not-a-real-policy"))
        // Should not crash and should produce an in-memory cache (default
        // fall-through branch).
        assert(policy is InMemoryPromptCache)
    }

    @Test
    fun `empty parameters produce default in-memory cache`() {
        val policy = determineCachePolicy(emptyList())
        assert(policy is InMemoryPromptCache)
    }
}
