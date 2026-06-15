package com.fartech.agents.commons

import ai.koog.prompt.cache.files.FilePromptCache
import ai.koog.prompt.cache.model.PromptCache
import ai.koog.prompt.message.Message
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 线程安全的 FilePromptCache 包装器
 *
 * 在多工作流并发执行时，多个 agent 可能同时读写同一个文件缓存目录。
 * 此包装器使用协程安全的 Mutex 确保并发安全，避免在 suspend 函数中使用
 * runBlocking 导致的线程池死锁问题。
 *
 * 使用全局锁注册表，确保同一缓存目录的所有实例共享同一把锁。
 */
class ConcurrentFilePromptCache(
    storage: Path,
    maxFiles: Int = 1024
) : PromptCache {

    private val delegate = FilePromptCache(storage, maxFiles)
    // Normalize the key so two references to the same cache directory (one with trailing
    // slash, one with `..` segments, case-different on case-insensitive FS) share a Mutex.
    // Otherwise two instances both "correctly" see putIfAbsent win and hold different
    // locks, re-opening the race the registry exists to close.
    //
    // `computeIfAbsent` is atomic per key (Javadoc: "performed atomically, so the function
    // is applied at most once per key"), so a single Mutex allocation per distinct key is
    // guaranteed regardless of concurrent construction.
    private val mutex = acquireMutex(normalizeCacheKey(storage))

    // Koog 1.0.0 narrowed `PromptCache.get` from `List<Message.Response>?` to
    // `Message.Assistant?` and `PromptCache.put` from `(request, List<...>)` to
    // `(request, Message.Assistant)` — matching the broader response model
    // change (one Assistant message per response, multi-choice via LLMChoice).
    override suspend fun get(request: PromptCache.Request): Message.Assistant? {
        return mutex.withLock {
            delegate.get(request)
        }
    }

    override suspend fun put(request: PromptCache.Request, response: Message.Assistant) {
        mutex.withLock {
            delegate.put(request, response)
        }
    }

    companion object {
        /**
         * Maximum number of distinct cache-directory keys we hold in the mutex registry.
         * Pre-Phase-10 the registry was unbounded — a long-running agent that touched
         * many distinct prompt-cache directories (e.g. one per workflow execution)
         * accumulated dead Mutex references forever. 4096 is a cheap heap budget
         * (≈64 bytes per entry => 256 KiB) and well above any realistic working set.
         */
        private const val MAX_MUTEX_REGISTRY_ENTRIES: Int = 4096

        /** 全局锁注册表：相同路径的缓存实例共享同一把 Mutex */
        private val mutexRegistry = ConcurrentHashMap<String, Mutex>()

        /** Order tracker for opportunistic LRU eviction when the registry hits its cap. */
        private val accessOrder = java.util.Collections.synchronizedSet(
            java.util.LinkedHashSet<String>()
        )

        /**
         * Canonical form of a cache storage path for mutex-registry lookup. Uses the real
         * path (resolves symlinks, case-sensitive OS behaviour) when the target exists,
         * else falls back to the normalized absolute path. Both paths and pseudo-paths
         * are valid inputs — `FilePromptCache` creates the directory lazily.
         */
        private fun normalizeCacheKey(storage: Path): String {
            val absolute = storage.toAbsolutePath().normalize()
            return runCatching { absolute.toRealPath().toString() }
                .getOrElse { absolute.toString() }
        }

        /**
         * Look up (or allocate) the Mutex for [key], evicting the oldest entries when the
         * registry exceeds [MAX_MUTEX_REGISTRY_ENTRIES]. The Mutex is the only thing
         * keyed by path, and the lock guards a `delegate.get/put` round-trip — once an
         * entry is evicted, any new instance for the same path rebuilds it from scratch
         * (the worst case is a brief window where two instances hold different Mutexes;
         * the underlying FilePromptCache file ops remain atomic on the FS).
         */
        private fun acquireMutex(key: String): Mutex {
            val mutex = mutexRegistry.computeIfAbsent(key) { Mutex() }
            // Refresh recency ordering — synchronized because LinkedHashSet is not safe
            // for concurrent structural modification.
            synchronized(accessOrder) {
                accessOrder.remove(key)
                accessOrder.add(key)
                if (mutexRegistry.size > MAX_MUTEX_REGISTRY_ENTRIES) {
                    val excess = mutexRegistry.size - MAX_MUTEX_REGISTRY_ENTRIES
                    val iter = accessOrder.iterator()
                    var removed = 0
                    while (iter.hasNext() && removed < excess) {
                        val victim = iter.next()
                        if (victim == key) continue // never evict the entry we just made
                        iter.remove()
                        mutexRegistry.remove(victim)
                        removed++
                    }
                    if (removed > 0) {
                        logger.debug { "Evicted $removed mutex registry entries (cap=$MAX_MUTEX_REGISTRY_ENTRIES)" }
                    }
                }
            }
            return mutex
        }
    }
}
