package com.fartech.agents.commons

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.InMemoryPersistenceStorageProvider
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import com.fartech.storage.DocumentQuery
import com.fartech.storage.DocumentSortField
import com.fartech.storage.DocumentStore
import com.fartech.storage.DocumentStoreFactory
import com.fartech.storage.StorageBackend
import com.fartech.storage.StorageProfile
import com.fartech.storage.StoredDocument
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.MyUtils
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.litote.kmongo.ascending
import org.litote.kmongo.descending
import org.litote.kmongo.div
import org.litote.kmongo.eq
import org.openapitools.vertxweb.server.model.AgentHistoryDocumentObject
import org.openapitools.vertxweb.server.model.AgentSnapshot
import org.openapitools.vertxweb.server.model.AgentSnapshotDocumentObject
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

// ============================================================
// Agent State Management
// ============================================================
// Centralises all stateful concerns for agents:
//   1. Conversation History  – multi-turn chat context via restoreHistoryNode
//   2. Persistence Checkpoints – agent snapshot / checkpoint storage
// ============================================================


/**
 * Per-file locks for file-based history read-modify-write operations.
 *
 * Bounded to prevent slow memory growth in long-running processes that touch many
 * distinct sessions.
 *
 * ## Why the previous implementation was racy
 *
 * The earlier `acquireHistoryLock` had a lock-free fast path (`fileHistoryLocks[path]?.let { return it }`)
 * outside the guard. If the fast path returned a lock and, before the caller could call
 * `.lock()` on it, a *different* thread's synchronized block evicted it (evictor's check
 * `!lock.isLocked && !lock.hasQueuedThreads()` both passed), a third thread calling
 * `acquireHistoryLock(samePath)` would create a brand new lock. The two callers would
 * then hold **different locks for the same file**, defeating the whole point of the cache.
 *
 * ## New invariant
 *
 * 1. Every access goes through [fileHistoryLocksGuard].
 * 2. Eviction only touches entries whose `lastAccessNanos` is older than
 *    [LOCK_STALE_NANOS] AND which are currently idle. A recently-returned lock is
 *    therefore never evicted before the caller has a chance to `.lock()` it.
 */
private const val MAX_FILE_HISTORY_LOCKS = 1024
private val LOCK_STALE_NANOS = java.time.Duration.ofMinutes(10).toNanos()

private class TrackedHistoryLock(
    val lock: java.util.concurrent.locks.ReentrantLock,
    @Volatile var lastAccessNanos: Long
)

private val fileHistoryLocks = ConcurrentHashMap<String, TrackedHistoryLock>()
private val fileHistoryLocksGuard = Any()

private fun acquireHistoryLock(absolutePath: String): java.util.concurrent.locks.ReentrantLock {
    return synchronized(fileHistoryLocksGuard) {
        val now = System.nanoTime()
        val existing = fileHistoryLocks[absolutePath]
        if (existing != null) {
            existing.lastAccessNanos = now
            return@synchronized existing.lock
        }

        // Attempt cleanup only when we hit the cap, and only evict entries that
        // (a) have not been accessed for LOCK_STALE_NANOS, AND
        // (b) are not currently held or queued on.
        if (fileHistoryLocks.size >= MAX_FILE_HISTORY_LOCKS) {
            val staleBefore = now - LOCK_STALE_NANOS
            val victims = fileHistoryLocks.entries
                .asSequence()
                .filter { (_, tracked) ->
                    tracked.lastAccessNanos < staleBefore &&
                        !tracked.lock.isLocked &&
                        !tracked.lock.hasQueuedThreads()
                }
                // Remove in small batches so we don't starve a busy system.
                .take(fileHistoryLocks.size - MAX_FILE_HISTORY_LOCKS + 16)
                .map { it.key }
                .toList()
            victims.forEach { fileHistoryLocks.remove(it) }
        }

        val fresh = TrackedHistoryLock(java.util.concurrent.locks.ReentrantLock(), now)
        fileHistoryLocks[absolutePath] = fresh
        fresh.lock
    }
}

/** Sanitize a session ID for safe use as a filename (replace : and other problematic chars). */
private fun sanitizeSessionIdForFilename(sessionId: String): String =
    sessionId.replace(':', '_').replace('/', '_').replace('\\', '_')

// --------------------------------------------
// Section 1: Conversation History
// --------------------------------------------

/**
 * In-memory conversation history store.
 * Used when `persistence_storage_type` is set to `memory`.
 * Data is only retained for the lifetime of the current process.
 */
private object InMemoryHistoryStore {
    private val store = ConcurrentHashMap<String, MutableList<Map<String, String>>>()

    fun load(sessionId: String): List<Map<String, String>> =
        store[sessionId]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    fun save(sessionId: String, role: String, content: String) {
        val list = store.computeIfAbsent(sessionId) { java.util.Collections.synchronizedList(mutableListOf()) }
        list.add(mapOf("role" to role, "content" to content))
    }
}

@Serializable
private data class AgentHistoryRecord(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: Long
)

private fun agentHistoryCollection(parameters: List<ConfigurationParameter>): String {
    val prefix = parameters.parameter("persistence_collection_prefix", "agent")
    return "${prefix}_history"
}

private fun agentCheckpointCollection(parameters: List<ConfigurationParameter>): String {
    val prefix = parameters.parameter("persistence_collection_prefix", "agent")
    return "${prefix}_checkpoint"
}

private fun agentMongoCollection(parameters: List<ConfigurationParameter>, suffix: String, defaultLegacy: String): String {
    val prefix = parameters.parameter("mongo_collection_prefix", "")
    return if (prefix.isBlank()) {
        defaultLegacy
    } else {
        "${prefix}_${suffix}"
    }
}

private fun openAgentDocumentStore(parameters: List<ConfigurationParameter>): DocumentStore {
    // M1.5（全面 Mongo 化）后，agent 持久化只接受 memory 和 mongodb 两种 backend。
    //  - "memory"  : 单元测试 / 一次性 CLI 调用，进程结束数据丢失
    //  - "mongodb" : 默认，所有正式部署都用这个
    //
    // 历史上还支持 "sql" 和 "file"（默认是 "file"），但它们已经从
    // [com.fartech.storage] 包里删除。如果上层 workflow YAML 或 system-global-agent.json
    // 里仍然写着 sql / file，会被静默映射到 mongodb（携带其它参数继续工作）。这是有意的——
    // 让升级体验是"配置过时但 app 不崩"。校验严格性留给 [WorkflowWebApplication] 的启动断言。
    val storageType = parameters.parameter("persistence_storage_type", "mongodb").trim().lowercase()
    val namespace = parameters.parameter("persistence_namespace", "braidrun-workflow")
    val profile = when (storageType) {
        "memory" -> StorageProfile(
            backend = StorageBackend.MEMORY,
            namespace = namespace
        )

        // mongodb 是新默认；旧的 file/sql 配置都会落到这里
        else -> {
            val (connectionString, dbName) = resolveMongoConnection(parameters)
            StorageProfile(
                backend = StorageBackend.MONGODB,
                namespace = namespace,
                mongodb = com.fartech.storage.MongoStoreConfig(
                    connectionString = connectionString,
                    database = dbName,
                    collectionName = parameters.parameter("mongo_document_collection", "dy_documents")
                )
            )
        }
    }
    return DocumentStoreFactory.open(profile)
}

private fun loadHistoryFromDocumentStore(
    parameters: List<ConfigurationParameter>,
    sessionId: String,
    maxMessages: Int
): List<Map<String, String>> {
    val collectionName = agentHistoryCollection(parameters)
    openAgentDocumentStore(parameters).use { store ->
        val records = store.list(
            DocumentQuery(
                collection = collectionName,
                parentId = sessionId,
                sortBy = DocumentSortField.CREATED_AT,
                descending = false
            )
        ).map {
            Json.decodeFromString(AgentHistoryRecord.serializer(), it.payload)
        }
        val messages = records.map { mapOf("role" to it.role, "content" to it.content) }
        return if (maxMessages > 0) messages.takeLast(maxMessages) else messages
    }
}

private fun saveHistoryToDocumentStore(
    parameters: List<ConfigurationParameter>,
    sessionId: String,
    role: String,
    content: String
) {
    val record = AgentHistoryRecord(
        id = MyUtils.generateUniqueID(),
        sessionId = sessionId,
        role = role,
        content = content,
        createdAt = System.currentTimeMillis()
    )
    openAgentDocumentStore(parameters).use { store ->
        store.put(
            StoredDocument(
                id = record.id,
                collection = agentHistoryCollection(parameters),
                namespace = "",
                parentId = sessionId,
                createdAt = record.createdAt,
                updatedAt = record.createdAt,
                payload = Json.encodeToString(AgentHistoryRecord.serializer(), record)
            )
        )
    }
}

/**
 * Shared resilience wrapper for the history Mongo paths. Must be a singleton:
 * the circuit-breaker state (failure count, OPEN trip) lives per instance, so the
 * previous per-call construction could never accumulate failures — during a Mongo
 * outage every save/load still burned the full 3 × 30 s retry budget and the
 * dedicated CircuitBreakerOpenException handling was unreachable.
 */
private val historyResilientOps = ResilientMongoOperations(defaultTimeout = 30.seconds, defaultMaxAttempts = 3)

/** Loads history from MongoDB for the given session. */
private suspend fun loadHistoryFromMongoDB(
    parameters: List<ConfigurationParameter>,
    sessionId: String,
    maxMessages: Int
): List<Map<String, String>> {
    val resilientOps = historyResilientOps
    return try {
        val docs = resilientOps.withRetryAndTimeout {
            withResolvedKMongo<AgentHistoryDocumentObject, List<AgentHistoryDocumentObject>>(
                parameters = parameters,
                dbName = AgentMongoTargets.BRAIDRUN_DB,
                collectionName = agentMongoCollection(parameters, "history", AgentMongoTargets.AGENT_HISTORY_COLLECTION)
            ) {
                find(AgentHistoryDocumentObject::sessionId eq sessionId)
                    .sort(ascending(AgentHistoryDocumentObject::createDate))
                    .toList()
            }
        }
        val messages = docs.map { mapOf("role" to it.role, "content" to it.content) }
        if (maxMessages > 0) messages.takeLast(maxMessages) else messages
    } catch (e: Exception) {
        logProgress(AnsiColor.RED, "History", "❌ Failed to load MongoDB history for '$sessionId': ${e.message}")
        emptyList()
    }
}

/** Saves a single history message to MongoDB. */
private suspend fun saveHistoryToMongoDB(
    parameters: List<ConfigurationParameter>,
    sessionId: String,
    role: String,
    content: String
) {
    val resilientOps = historyResilientOps
    try {
        resilientOps.withRetryAndTimeout {
            withResolvedKMongo<AgentHistoryDocumentObject, Unit>(
                parameters = parameters,
                dbName = AgentMongoTargets.BRAIDRUN_DB,
                collectionName = agentMongoCollection(parameters, "history", AgentMongoTargets.AGENT_HISTORY_COLLECTION)
            ) {
                insertOne(
                    AgentHistoryDocumentObject(
                        createDate = System.currentTimeMillis(),
                        id = MyUtils.generateUniqueID(),
                        sessionId = sessionId,
                        role = role,
                        content = content
                    )
                )
                Unit
            }
        }
    } catch (e: Exception) {
        logProgress(AnsiColor.RED, "History", "❌ Failed to save MongoDB history for '$sessionId': ${e.message}")
    }
}

/**
 * Loads historical messages for a specific session.
 *
 * Supports four storage backends controlled by the `persistence_storage_type` parameter:
 * - `file` (default): reads per-session JSON files from `history_storage_root` directory.
 * - `memory`: reads from an in-process `InMemoryHistoryStore` (data lost on restart).
 * - `mongodb`: reads from the `agent_history` MongoDB collection.
 * - `sql`: reads from the configured SQL backend via the shared document store.
 *
 * @param parameters Configuration parameters:
 * - `history_enabled` (Boolean, default true): master switch for history.
 * - `session_id` (String): current session identifier.
 * - `restore_session_id` (String): session to restore from; defaults to `session_id`.
 *   Set to `""` to start with no injected history.
 * - `persistence_storage_type` (String, default `file`): storage backend.
 * - `history_storage_root` (String, default `.agent_history`): root dir for file backend.
 * - `history_max_messages` (Int, default 50): max messages to inject (0 = no limit).
 */
fun loadHistoryMessages(parameters: List<ConfigurationParameter>): List<Map<String, String>> {
    val historyEnabled = parameters.parameter("history_enabled", true)
    if (!historyEnabled) return emptyList()

    val sessionId = parameters.parameter("session_id", "")
    val restoreSessionId = parameters.parameter("restore_session_id", sessionId)
    if (restoreSessionId.isEmpty()) return emptyList()

    val maxMessages = parameters.parameter("history_max_messages", 50)
    // trim().lowercase(): the checkpoint/doc-store paths normalize this key; matching
    // it raw here meant "MongoDB"/"mongodb " routed checkpoints to Mongo but history
    // silently to the file fallback — a split-brain persistence config.
    val storageType = parameters.parameter("persistence_storage_type", "file").trim().lowercase()

    return when (storageType) {
        "memory" -> {
            val messages = InMemoryHistoryStore.load(restoreSessionId)
            if (maxMessages > 0) messages.takeLast(maxMessages) else messages
        }

        "mongodb" -> runBlocking {
            loadHistoryFromMongoDB(parameters, restoreSessionId, maxMessages)
        }

        "sql" -> loadHistoryFromDocumentStore(parameters, restoreSessionId, maxMessages)

        else -> { // file (default); supports legacy key `history_file_dir`
            val historyDir = parameters.parameter(
                "history_storage_root",
                parameters.parameter("history_file_dir", ".agent_history")
            )
            val safeSessionId = sanitizeSessionIdForFilename(restoreSessionId)
            val historyFile = File("$historyDir/$safeSessionId.json")
            if (!historyFile.exists()) {
                emptyList()
            } else {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val messages = Json.decodeFromString<List<Map<String, String>>>(historyFile.readText())
                    if (maxMessages > 0) messages.takeLast(maxMessages) else messages
                } catch (e: Exception) {
                    logProgress(
                        AnsiColor.RED,
                        "History",
                        "❌ Failed to load history for session '$restoreSessionId': ${e.message}"
                    )
                    emptyList()
                }
            }
        }
    }
}

/**
 * Saves a single conversation message to the history store.
 *
 * Uses the same backend as `loadHistoryMessages`, determined by `persistence_storage_type`.
 *
 * @param parameters Configuration parameters (see `loadHistoryMessages` for details).
 * @param role    Message role: `"user"` or `"assistant"`.
 * @param content Message text content.
 */
fun saveHistoryMessage(parameters: List<ConfigurationParameter>, role: String, content: String) {
    val historyEnabled = parameters.parameter("history_enabled", true)
    if (!historyEnabled) return

    val sessionId = parameters.parameter("session_id", "")
    if (sessionId.isEmpty()) return

    // Normalized to match loadHistoryMessages / checkpoint paths.
    val storageType = parameters.parameter("persistence_storage_type", "file").trim().lowercase()

    when (storageType) {
        "memory" -> InMemoryHistoryStore.save(sessionId, role, content)
        "mongodb" -> runBlocking {
            saveHistoryToMongoDB(parameters, sessionId, role, content)
        }

        "sql" -> saveHistoryToDocumentStore(parameters, sessionId, role, content)

        else -> { // file (default); supports legacy key `history_file_dir`
            val historyDir = parameters.parameter(
                "history_storage_root",
                parameters.parameter("history_file_dir", ".agent_history")
            )
            val safeSessionId = sanitizeSessionIdForFilename(sessionId)
            val historyFile = File("$historyDir/$safeSessionId.json")
            try {
                val dir = File(historyDir)
                dir.mkdirs()
                // Use per-session lock to prevent concurrent read-modify-write corruption.
                // acquireHistoryLock bounds the lock cache so this map cannot grow unboundedly
                // in long-lived processes that touch many sessions.
                val lock = acquireHistoryLock(historyFile.absolutePath)
                lock.lock()
                try {
                    @Suppress("UNCHECKED_CAST")
                    val existing: MutableList<Map<String, String>> = if (historyFile.exists()) {
                        Json.decodeFromString<List<Map<String, String>>>(historyFile.readText()).toMutableList()
                    } else {
                        mutableListOf()
                    }
                    existing.add(mapOf("role" to role, "content" to content))
                    // Trim to a sliding window of the most recent messages so the
                    // file (and the in-memory parse cost) doesn't grow without
                    // bound across long-lived sessions. Default 1000 keeps several
                    // hours of normal interaction; set 0 to disable the cap (legacy
                    // behaviour) for callers that need full transcripts.
                    val maxPersisted = parameters.parameter("history_max_persisted_messages", 1000)
                    if (maxPersisted > 0 && existing.size > maxPersisted) {
                        val drop = existing.size - maxPersisted
                        repeat(drop) { existing.removeAt(0) }
                    }
                    // Atomic write: write to temp file then move
                    val tmpFile = File(historyFile.parentFile, ".${historyFile.name}.tmp")
                    tmpFile.writeText(Json.encodeToString(existing))
                    java.nio.file.Files.move(
                        tmpFile.toPath(), historyFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } finally {
                    lock.unlock()
                }
            } catch (e: Exception) {
                logProgress(AnsiColor.RED, "History", "❌ Failed to save history for session '$sessionId': ${e.message}")
            }
        }
    }
}


// --------------------------------------------
// Section 2: Persistence Checkpoints
// --------------------------------------------

/**
 * Creates the appropriate [PersistenceStorageProvider] based on the `persistence_storage_type`
 * configuration parameter.
 *
 * Supported types:
 * - `memory` (default): in-process storage, lost on restart. Used by unit tests.
 * - `mongodb`: persistent storage using the `agent_snapshot` MongoDB collection.
 *
 * M1.5（全面 Mongo 化）后 `sql` 和 `file` 两个分支被删除——`SqlDocumentStore`
 * 和 `FileDocumentStore` 已经不存在。如果旧配置仍然写 sql / file，会被映射到
 * `mongodb`（通过 [openAgentDocumentStore] 的兜底逻辑）。
 *
 * @param parameters Configuration parameters:
 * - `persistence_storage_type` (String, default `mongodb`): storage backend selector.
 */
fun createPersistenceStorageProvider(parameters: List<ConfigurationParameter>): PersistenceStorageProvider<*> =
    when (parameters.parameter("persistence_storage_type", "mongodb").trim().lowercase()) {
        "memory" -> InMemoryPersistenceStorageProvider()
        // mongodb 是默认；旧的 sql / file 配置都映射到这里
        else -> MongoDbCustomStorageProvider<JsonElement>(parameters)
    }

/**
 * A custom storage provider implementation utilizing MongoDB for persisting and retrieving agent checkpoint data.
 * This class leverages resilient MongoDB operations with built-in retry and timeout mechanisms to ensure robust
 * data handling even in the presence of transient errors or service disruptions.
 *
 * @param T The type of the filter criteria extending from [JsonElement]. Used for filtering records during retrieval.
 */
class MongoDbCustomStorageProvider<T : JsonElement>(
    private val parameters: List<ConfigurationParameter>
) : PersistenceStorageProvider<T> {

    private val resilientOps = ResilientMongoOperations(
        defaultTimeout = 30.seconds,
        defaultMaxAttempts = 3
    )

    override suspend fun getCheckpoints(sessionId: String, filter: T?): List<AgentCheckpointData> {
        return try {
            val docs = resilientOps.withRetryAndTimeout {
                withResolvedKMongo<AgentSnapshotDocumentObject, List<AgentSnapshotDocumentObject>>(
                    parameters = parameters,
                    dbName = AgentMongoTargets.BRAIDRUN_DB,
                    collectionName = agentMongoCollection(parameters, "checkpoint", AgentMongoTargets.AGENT_SNAPSHOTS_COLLECTION)
                ) {
                    find(AgentSnapshotDocumentObject::agentSnapshot / AgentSnapshot::agentId eq sessionId)
                        .sort(descending(AgentSnapshotDocumentObject::createDate))
                        .toList()
                }
            }

            docs.mapNotNull { doc: AgentSnapshotDocumentObject ->
                val dataMap = doc.agentSnapshot.`data`
                runCatching { MyUtils.toObj(dataMap, AgentCheckpointData::class.java) }
                    .getOrNull()
            }
        } catch (e: ResilientMongoOperations.CircuitBreakerOpenException) {
            logProgress(AnsiColor.RED, "MongoDB", "Circuit breaker is OPEN: ${e.message}")
            emptyList()
        } catch (e: ResilientMongoOperations.MaxRetriesExceededException) {
            logProgress(AnsiColor.RED, "MongoDB", "Failed to get checkpoints after retries: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "MongoDB", "Unexpected error getting checkpoints: ${e.message}")
            emptyList()
        }
    }

    override suspend fun saveCheckpoint(sessionId: String, agentCheckpointData: AgentCheckpointData) {
        try {
            val payload = MyUtils.toMap(agentCheckpointData)
            // Koog 1.0.0 restructured AgentCheckpointData: `nodePath` / `lastInput`
            // / `lastOutput` moved off the top level into `graphProperties`
            // (for graph-strategy agents) or `plannerProperties` (for planner agents).
            // Read directly from the typed object — the strongly-typed values are
            // authoritative and dodge the Map<String, Any> indirection. The legacy
            // `nodeId` Mongo field (kept for backward-compat with pre-1.0.0
            // checkpoint rows) is now sourced from `graphProperties.nodePath`;
            // planner rows leave it blank.
            val nodeId = agentCheckpointData.graphProperties?.nodePath.orEmpty()
            val checkpointId = agentCheckpointData.checkpointId
            val version = agentCheckpointData.version
            val createdAt = agentCheckpointData.createdAt.toEpochMilliseconds()

            val id = MyUtils.generateUniqueID()
            val agentSnapshot = AgentSnapshot(
                agentId = sessionId,
                nodeId = nodeId,
                checkpointId = checkpointId,
                version = version,
                createdAt = createdAt,
                data = payload
            )
            val document = AgentSnapshotDocumentObject(
                createDate = System.currentTimeMillis(),
                id = id,
                agentSnapshot = agentSnapshot,
                username = null
            )

            resilientOps.withRetryAndTimeout {
                withResolvedKMongo<AgentSnapshotDocumentObject, Unit>(
                    parameters = parameters,
                    dbName = AgentMongoTargets.BRAIDRUN_DB,
                    collectionName = agentMongoCollection(parameters, "checkpoint", AgentMongoTargets.AGENT_SNAPSHOTS_COLLECTION)
                ) {
                    insertOne(document)
                    Unit
                }
            }

            logProgress(AnsiColor.GREEN, "MongoDB", "✅ Checkpoint saved successfully for session: $sessionId")

        } catch (e: ResilientMongoOperations.CircuitBreakerOpenException) {
            logProgress(AnsiColor.RED, "MongoDB", "❌ Circuit breaker is OPEN: ${e.message}")
            throw e
        } catch (e: ResilientMongoOperations.MaxRetriesExceededException) {
            logProgress(AnsiColor.RED, "MongoDB", "❌ Failed to save checkpoint after retries: ${e.message}")
            throw e
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "MongoDB", "❌ Unexpected error saving checkpoint: ${e.message}")
            throw e
        }
    }

    override suspend fun getLatestCheckpoint(sessionId: String, filter: T?): AgentCheckpointData? {
        return try {
            val docs = resilientOps.withRetryAndTimeout {
                withResolvedKMongo<AgentSnapshotDocumentObject, List<AgentSnapshotDocumentObject>>(
                    parameters = parameters,
                    dbName = AgentMongoTargets.BRAIDRUN_DB,
                    collectionName = agentMongoCollection(parameters, "checkpoint", AgentMongoTargets.AGENT_SNAPSHOTS_COLLECTION)
                ) {
                    find(AgentSnapshotDocumentObject::agentSnapshot / AgentSnapshot::agentId eq sessionId)
                        .sort(descending(AgentSnapshotDocumentObject::createDate))
                        .limit(1)
                        .toList()
                }
            }

            val first = docs.firstOrNull() ?: return null
            runCatching { MyUtils.toObj(first.agentSnapshot.`data`, AgentCheckpointData::class.java) }
                .getOrNull()

        } catch (e: ResilientMongoOperations.CircuitBreakerOpenException) {
            logProgress(AnsiColor.RED, "MongoDB", "Circuit breaker is OPEN: ${e.message}")
            null
        } catch (e: ResilientMongoOperations.MaxRetriesExceededException) {
            logProgress(AnsiColor.RED, "MongoDB", "Failed to get latest checkpoint after retries: ${e.message}")
            null
        } catch (e: Exception) {
            logProgress(AnsiColor.RED, "MongoDB", "Unexpected error getting latest checkpoint: ${e.message}")
            null
        }
    }
}
