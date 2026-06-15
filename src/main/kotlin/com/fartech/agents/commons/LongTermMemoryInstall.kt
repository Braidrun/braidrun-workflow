package com.fartech.agents.commons

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.longtermmemory.feature.FailurePolicy
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.agents.longtermmemory.ingestion.extraction.MessagePassingDocumentExtractor
import ai.koog.agents.longtermmemory.retrieval.augmentation.SystemPromptAugmenter
import ai.koog.agents.longtermmemory.retrieval.augmentation.UserPromptAugmenter
import ai.koog.agents.longtermmemory.retrieval.search.LastUserMessageQueryProvider
import ai.koog.agents.longtermmemory.retrieval.search.SimilaritySearchStrategy
import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import ai.koog.prompt.message.Message
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.WriteStorage
import ai.koog.rag.base.storage.search.SearchRequest
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Tier-2 — Koog [LongTermMemory] install site.
 *
 * ## What the feature does
 *
 * When enabled, every completed conversation turn is ingested into a
 * vector-searchable record store; on subsequent turns the agent prompt
 * is augmented with the N most relevant past records (RAG over the
 * user's own history).
 *
 * Result: a multi-session conversation where the assistant
 * "remembers" earlier topics without the caller threading a growing
 * message list through every request. Critical for assistant UX where
 * the user's last interaction was hours/days ago and session hydration
 * alone isn't enough.
 *
 * ## Storage choice
 *
 * Koog 1.0.0 ships [InMemoryRecordStorage] which we use as the
 * default. It's **per-process** and matches via Jaccard coefficient
 * over case-insensitive word sets — suitable for dev and for
 * single-tenant single-host deployments but **not production-grade**
 * for multi-instance setups.
 *
 * Production installs that want cross-instance persistence have two
 * options:
 *
 *   1. Supply a custom `storage` via [customStorage] when calling
 *      [installLongTermMemoryIfEnabled] — any
 *      `SearchStorage<TextDocument, SearchRequest>` +
 *      `WriteStorage<TextDocument>` will slot in. Natural candidates:
 *      Mongo + Atlas Search, our own `rag-vector` store, the new
 *      Bedrock AgentCore long-term memory (`agents-features-longterm-memory-aws`),
 *      or a Spring AI `VectorStore` bridge.
 *   2. Wait for the Mongo-backed storage adapter landing in a follow-up
 *      commit — tracked as future work once cross-instance demand
 *      materialises. Until then the in-memory default only recalls
 *      within a single JVM.
 *
 * ## Namespacing
 *
 * Namespace defaults to `ltm:<user_id>:<session_id>` — isolates each
 * user's memories so a workflow running for user A never retrieves
 * user B's context, AND makes the "forget me" admin action (which
 * prefix-matches on `ltm:<user_id>:`) clear every session for that
 * user in one shot. The `user_id` segment is resolved from the
 * `user_id` parameter; when absent (CLI flows, workflow execution
 * without a logged-in user), we fall back to the `agent-cli` literal
 * so namespaces remain collision-safe but won't be clearable from
 * the admin UI (those memories live on until process restart).
 * Overridable per workflow via `long_term_memory_namespace` for
 * scenarios where operators explicitly want shared cross-user memory
 * (tenant-wide product knowledge, etc.); operators providing a
 * custom namespace own their own cleanup path.
 *
 * ## Default configuration
 *
 * | Setting | Default | Parameter |
 * |---------|---------|-----------|
 * | Enabled | `false` | `long_term_memory_enabled` |
 * | Ingestion roles | User + Assistant | `long_term_memory_ingest_roles` |
 * | Augmenter | System prompt | `long_term_memory_augmenter` |
 * | Top-K | 5 | `long_term_memory_top_k` |
 * | Namespace | `ltm:<user_id>:<session_id>` | `long_term_memory_namespace` |
 * | Retrieval failure policy | LOG_AND_CONTINUE | `long_term_memory_retrieval_failure_policy` |
 * | Ingestion failure policy | LOG_AND_CONTINUE | `long_term_memory_ingestion_failure_policy` |
 *
 * The defaults are tuned for assistant-style conversations
 * (one ingestion per agent completion, not per LLM call — cheaper) and
 * for providers that honour system-prompt caching (`SystemPromptAugmenter`
 * places retrieved context in the cached prefix slot).
 *
 * ## Koog 1.0.0 migration notes
 *
 *   - **`IngestionTiming` removed** (was `ON_LLM_CALL` / `ON_AGENT_COMPLETION`):
 *     Koog 1.0.0 only ingests on agent completion. The
 *     `long_term_memory_timing` parameter is read for backward-compat
 *     but logged as ignored.
 *   - **`QueryExtractor` → `SearchQueryProvider`** /
 *     **`ExtractionStrategy` → `DocumentExtractor`**: pure renames,
 *     transparent here.
 *   - **`FailurePolicy` added** (Feature #5): previously every storage
 *     error was silently swallowed inside the framework. Now we can
 *     pin retrieval failures to `FAIL_FAST` (default in Koog 1.0.0,
 *     but we keep `LOG_AND_CONTINUE` to preserve pre-1.0.0
 *     behaviour where a transient store outage shouldn't abort the
 *     user's chat). Operators can opt into stricter behaviour
 *     via the new `_retrieval_failure_policy` / `_ingestion_failure_policy`
 *     parameters.
 *   - **No more `@ExperimentalAgentsApi`** opt-in: the surface is
 *     part of the BETA stream but no longer flagged at the file level.
 */
object LongTermMemoryInstall {
    private val logger = KotlinLogging.logger {}

    /**
     * Process-level cache of in-memory stores keyed by namespace. This
     * matters because every `buildAgent` call creates a fresh
     * [LongTermMemory] feature instance; without an external registry,
     * the in-memory store would be discarded between turns and the
     * "memory" would reset to empty. Sharing by namespace means two
     * successive agent runs for the same session see the same store.
     *
     * The registry is NOT cleaned up on workflow completion — memories
     * persist for the JVM lifetime. This is acceptable for the
     * in-memory case because (a) the JVM holds at most ~10 MB per
     * user's conversation at typical volume and (b) the eviction
     * policy can be tightened once a real persistent store replaces
     * this.
     */
    private val inMemoryStores: MutableMap<String, InMemoryRecordStorage> = ConcurrentHashMap()

    /**
     * Optional override that lets a host application (workflow-web) supply
     * a persistent backing storage — typically a Mongo-backed subclass of
     * [InMemoryRecordStorage] provisioned at boot when running in
     * multi-instance mode. The provider is invoked with the resolved
     * namespace per agent build so the same underlying collection can
     * serve every namespace.
     *
     * When unset, [installLongTermMemoryIfEnabled] keeps the current
     * in-memory behaviour. When set, the provider is the SINGLE source of
     * truth (the `customStorage` parameter still wins so per-test overrides
     * remain trivial).
     *
     * The provider type intentionally remains `InMemoryRecordStorage?` to
     * avoid breaking the existing host wiring (`MongoLongTermMemoryStorage`
     * extends `InMemoryRecordStorage`). Host applications that want to
     * back LTM with a backend that can't extend the in-memory shim should
     * implement [LongTermMemoryStorage] directly and pass the result via
     * the `customStorage` parameter of [installLongTermMemoryIfEnabled].
     *
     * Register from the host application's boot path, e.g.
     * `LongTermMemoryInstall.storageProvider = { ns -> mongoLtmStorage }`.
     * Pass `null` to revert to the in-memory default — useful in tests.
     */
    @Volatile
    var storageProvider: ((namespace: String) -> InMemoryRecordStorage?)? = null

    /**
     * Optional hook the host application registers so the "forget me" admin
     * action also wipes any persistent storage records (e.g. Mongo rows in
     * the `ltm_records` collection). Receives the namespace prefix to delete;
     * returns the number of underlying records removed (not namespaces) for
     * audit logging — operators can confirm the right scope was cleared.
     *
     * Wiring: the host registers this alongside [storageProvider]. When
     * unset, [clearNamespacesWithPrefix] still drains the in-memory map but
     * the persistent records survive — by design, so a misconfigured host
     * cannot accidentally wipe production data on a clear call.
     */
    @Volatile
    var persistentClearProvider: (suspend (namespacePrefix: String) -> Long)? = null

    /**
     * Clear all in-memory stores whose namespaces match [prefix]. Used by
     * the "forget me" admin action exposed through the UI. Safe to call
     * on an empty registry. Returns the number of in-memory namespaces
     * cleared — the persistent clear (when wired) runs separately, see
     * [clearForUser].
     */
    fun clearNamespacesWithPrefix(prefix: String): Int {
        val matching = inMemoryStores.keys.filter { it.startsWith(prefix) }
        matching.forEach { inMemoryStores.remove(it) }
        if (matching.isNotEmpty()) {
            logger.info { "LongTermMemory: cleared ${matching.size} namespace(s) with prefix '$prefix'" }
        }
        return matching.size
    }

    /**
     * Drain persistent storage records for namespaces beginning with
     * [prefix]. Suspending because the persistent backend (Mongo) is
     * async-by-construction. Returns the count of records removed or 0
     * when no persistent clear hook is registered.
     */
    suspend fun clearPersistentRecordsWithPrefix(prefix: String): Long {
        val hook = persistentClearProvider ?: return 0L
        return runCatching { hook(prefix) }
            .onFailure { logger.warn(it) { "LongTermMemory: persistent clear for prefix '$prefix' failed" } }
            .getOrDefault(0L)
    }

    /**
     * Clear every LTM in-memory namespace for a given user. Intended for
     * the site-level "forget me" action.
     *
     * The match is strict: we append a `:` separator after the user_id
     * segment so a user id of `alice` only clears `ltm:alice:…` namespaces
     * and can never stomp on `ltm:alice-admin:…`. Callers that override
     * the namespace template via `long_term_memory_namespace` must drive
     * their own cleanup — we only know the default shape.
     *
     * Returns the number of namespaces cleared (0 when LTM is disabled
     * or the user has never triggered ingestion).
     */
    fun clearForUser(userId: String): Int {
        val safe = userId.trim()
        if (safe.isEmpty()) return 0
        return clearNamespacesWithPrefix("ltm:$safe:")
    }

    /**
     * Install the Koog [LongTermMemory] feature when
     * `long_term_memory_enabled=true`. No-op otherwise.
     *
     * [customStorage] overrides the default in-memory store; pass your
     * own `SearchStorage + WriteStorage` impl here to get persistence.
     * The single object must implement **both** interfaces — typical
     * concrete types do so via delegation. A type alias
     * [LongTermMemoryStorage] expresses the combined contract.
     */
    fun GraphAIAgent.FeatureContext.installLongTermMemoryIfEnabled(
        parameters: List<ConfigurationParameter>,
        customStorage: LongTermMemoryStorage? = null,
    ) {
        if (!parameters.parameter("long_term_memory_enabled", false)) return

        val sessionId = parameters.parameter("session_id", "default").trim()
            .ifEmpty { "default" }
        // `user_id` is injected by AssistantPipeline.buildParameters so the
        // default namespace can be cleared via the site-level "forget me"
        // button. CLI / workflow flows that never attach a user identity
        // get the literal `agent-cli` segment — still collision-safe but
        // NOT clearable from the admin UI (that's by design: no UI actor
        // to drive the cleanup).
        val userId = parameters.parameter("user_id", "").trim()
            .ifEmpty { "agent-cli" }
        val defaultNamespace = "ltm:$userId:$sessionId"
        // Explicit `long_term_memory_namespace: ""` would key every caller
        // into the same `inMemoryStores[""]` bucket and leak context across
        // users — treat blank as "use the default template".
        val namespace = parameters.parameter("long_term_memory_namespace", defaultNamespace)
            .trim()
            .ifEmpty {
                logger.warn {
                    "LongTermMemory: blank `long_term_memory_namespace` would share state across users; " +
                        "falling back to default namespace '$defaultNamespace'."
                }
                defaultNamespace
            }
        // Resolution order:
        //   1. caller-supplied `customStorage` (test injection / explicit override)
        //   2. host-registered `storageProvider` (production Mongo backend when
        //      WorkflowWebApplication boots with multi-instance enabled)
        //   3. process-local in-memory store keyed by namespace (single-host dev)
        // Both `storageProvider` and the in-memory fallback return concrete
        // `InMemoryRecordStorage` subclasses, which implement `SearchStorage`
        // and `WriteStorage` directly. `combinedLongTermMemoryStorageOf` is
        // the thin adapter that promotes the union shape into the
        // [LongTermMemoryStorage] type the Koog DSL expects.
        val storage: LongTermMemoryStorage = customStorage
            ?: storageProvider?.invoke(namespace)?.let { combinedLongTermMemoryStorageOf(it) }
            ?: combinedLongTermMemoryStorageOf(
                inMemoryStores.computeIfAbsent(namespace) {
                    InMemoryRecordStorage(defaultNamespace = namespace)
                }
            )
        val topK = parameters.parameter("long_term_memory_top_k", 5)
        val ingestRolesRaw = parameters.parameter(
            "long_term_memory_ingest_roles",
            listOf("user", "assistant"),
        )
        val ingestRoles = ingestRolesRaw
            .mapNotNull { role ->
                when (role.trim().lowercase()) {
                    "user" -> Message.Role.User
                    "assistant" -> Message.Role.Assistant
                    "system" -> Message.Role.System
                    // Koog 1.0.0 removed Message.Role.Tool; tool results are now
                    // MessagePart.Tool.Result on a User message. Silently drop
                    // any operator-specified "tool" role for forward-compat.
                    "tool" -> null
                    else -> null
                }
            }
            .toSet()
            .ifEmpty { setOf(Message.Role.User, Message.Role.Assistant) }

        // Koog 1.0.0 removed IngestionTiming — ingestion always fires on agent
        // completion. We still read the parameter so legacy YAML doesn't error,
        // but log a one-off warning if it's anything other than the new default.
        val timingHint = parameters.parameter("long_term_memory_timing", "on_agent_completion")
            .trim().lowercase()
        if (timingHint == "on_llm_call") {
            logger.warn {
                "LongTermMemory: 'long_term_memory_timing=on_llm_call' is no longer supported in Koog 1.0.0; " +
                    "ingestion now always fires on agent completion. Update your workflow YAML to remove the key."
            }
        }

        val augmenterName = parameters.parameter(
            "long_term_memory_augmenter",
            "system",
        ).trim().lowercase()

        // Phase-11 (2026-05) — operator-tunable failure policies. Defaults preserve
        // pre-1.0.0 silent-swallow behaviour so existing deployments don't suddenly
        // start failing chat requests on a transient store hiccup. Operators that
        // care about durable audit can opt into FAIL_FAST per direction.
        val retrievalFailurePolicy = parseFailurePolicy(
            parameters.parameter("long_term_memory_retrieval_failure_policy", "log_and_continue"),
            default = FailurePolicy.LOG_AND_CONTINUE,
        )
        val ingestionFailurePolicy = parseFailurePolicy(
            parameters.parameter("long_term_memory_ingestion_failure_policy", "log_and_continue"),
            default = FailurePolicy.LOG_AND_CONTINUE,
        )

        install(LongTermMemory) {
            retrieval {
                this.storage = storage
                this.searchQueryProvider = LastUserMessageQueryProvider()
                this.searchStrategy = SimilaritySearchStrategy(topK = topK)
                this.promptAugmenter = when (augmenterName) {
                    "user" -> UserPromptAugmenter()
                    else -> SystemPromptAugmenter()
                }
                this.namespace = namespace
                this.failurePolicy = retrievalFailurePolicy
            }
            ingestion {
                this.storage = storage
                this.documentExtractor = MessagePassingDocumentExtractor(
                    messageRolesToExtract = ingestRoles,
                )
                this.namespace = namespace
                this.failurePolicy = ingestionFailurePolicy
            }
        }

        logger.info {
            "LongTermMemory installed: namespace='$namespace' topK=$topK " +
                "roles=${ingestRoles.map { it::class.simpleName }} augmenter=$augmenterName " +
                "retrievalFailurePolicy=$retrievalFailurePolicy ingestionFailurePolicy=$ingestionFailurePolicy"
        }
    }

    private fun parseFailurePolicy(raw: String, default: FailurePolicy): FailurePolicy =
        when (raw.trim().lowercase()) {
            "fail_fast", "fail-fast", "failfast", "strict" -> FailurePolicy.FAIL_FAST
            "log_and_continue", "log-and-continue", "log", "lenient", "" -> FailurePolicy.LOG_AND_CONTINUE
            else -> {
                logger.warn { "LongTermMemory: unknown failure policy '$raw'; using $default" }
                default
            }
        }
}

/**
 * The combined contract a custom storage must implement to back the
 * Koog [LongTermMemory] feature. Mirrors the constraint expressed by
 * Koog's `Config.retrieval { storage: SearchStorage<TextDocument, SearchRequest> }`
 * and `Config.ingestion { storage: WriteStorage<TextDocument> }` blocks: a
 * single object satisfies both because the LTM feature reads / writes
 * through the same backend.
 *
 * Implementors typically extend [InMemoryRecordStorage] for tests or
 * delegate to an external vector store (Mongo Atlas, Bedrock AgentCore,
 * pgvector, etc.) for production. Kotlin can't express intersection
 * types in typealiases or function parameters cleanly, so we declare a
 * marker interface and provide a [combinedLongTermMemoryStorageOf]
 * helper that wraps any object satisfying both source contracts.
 *
 * `InMemoryRecordStorage` from Koog already implements both source
 * interfaces but doesn't extend this one; use [combinedLongTermMemoryStorageOf]
 * (or just pass it directly to `customStorage` — the parameter accepts
 * any object satisfying the combined shape via type bounds).
 */
interface LongTermMemoryStorage :
    SearchStorage<TextDocument, SearchRequest>,
    WriteStorage<TextDocument>

/**
 * Bridge for backends that already implement both [SearchStorage] and
 * [WriteStorage] but don't subclass [LongTermMemoryStorage] — wraps the
 * impl via class delegation so callers can keep their existing type
 * hierarchy.
 */
fun <T> combinedLongTermMemoryStorageOf(impl: T): LongTermMemoryStorage
    where T : SearchStorage<TextDocument, SearchRequest>,
          T : WriteStorage<TextDocument> = object :
    LongTermMemoryStorage,
    SearchStorage<TextDocument, SearchRequest> by impl,
    WriteStorage<TextDocument> by impl {}
