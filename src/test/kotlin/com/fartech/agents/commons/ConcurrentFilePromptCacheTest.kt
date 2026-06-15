package com.fartech.agents.commons

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Phase 10 (2026-05-08) audit fix: the global mutex registry that backs
 * [ConcurrentFilePromptCache] is now bounded at 4096 entries with LRU eviction.
 *
 * Pre-fix the registry was unbounded — a long-running agent process that touched
 * many distinct prompt-cache directories (e.g. one per workflow execution) would
 * accumulate Mutex references forever. Worst-case heap was a few MB but the leak
 * was never reclaimed even when the underlying directories were deleted.
 *
 * The eviction check fires synchronously on the path that allocates a new mutex,
 * so we can prove the bound by allocating > MAX_MUTEX_REGISTRY_ENTRIES distinct
 * caches and observing the registry size. Reflection is needed because the
 * registry is intentionally `private` to prevent direct manipulation.
 */
class ConcurrentFilePromptCacheTest {

    @Test
    fun `mutex registry is bounded under heavy churn`(@TempDir baseDir: Path) {
        // Allocate 4500 distinct cache directories — well above the 4096 cap. Each
        // cache call goes through `acquireMutex` which evicts the oldest entries
        // when the cap is exceeded.
        val churnSize = 4500
        for (i in 0 until churnSize) {
            val storage = baseDir.resolve("cache-$i")
            Files.createDirectories(storage)
            ConcurrentFilePromptCache(storage)
        }

        // Reach into the companion via reflection — the registry is intentionally
        // private and there's no public observer. Kotlin emits the companion's
        // private vals as static fields on the outer class.
        val outer = ConcurrentFilePromptCache::class.java
        val registryField = outer.getDeclaredField("mutexRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val registry = registryField.get(null) as Map<String, *>

        val capField = outer.getDeclaredField("MAX_MUTEX_REGISTRY_ENTRIES").apply { isAccessible = true }
        val cap = capField.getInt(null)

        assertTrue(
            registry.size <= cap,
            "registry size ${registry.size} must not exceed the configured cap $cap " +
                "after $churnSize distinct allocations (Phase 10 LRU bound)"
        )
        assertTrue(
            registry.size >= cap / 2,
            "registry size ${registry.size} unexpectedly low — eviction logic may be too aggressive"
        )
    }

    @Test
    fun `mutex registry shares a single mutex per canonical path`(@TempDir baseDir: Path) {
        val storage = baseDir.resolve("shared")
        Files.createDirectories(storage)

        val a = ConcurrentFilePromptCache(storage)
        val b = ConcurrentFilePromptCache(storage)

        val mutexField = ConcurrentFilePromptCache::class.java.getDeclaredField("mutex")
            .apply { isAccessible = true }
        val mutexA = mutexField.get(a)
        val mutexB = mutexField.get(b)

        assertTrue(mutexA === mutexB, "Two caches at the same canonical path must share the same Mutex")
    }
}
