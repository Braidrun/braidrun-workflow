package com.fartech.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object StorageJson {
    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

@Serializable
data class StoredDocument(
    val id: String,
    val collection: String,
    val namespace: String,
    val ownerId: String? = null,
    val parentId: String? = null,
    val secondaryId: String? = null,
    val status: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val payload: String
)

enum class DocumentSortField {
    CREATED_AT,
    UPDATED_AT,
    ID
}

data class DocumentQuery(
    val collection: String,
    val ownerId: String? = null,
    val parentId: String? = null,
    val secondaryId: String? = null,
    val status: String? = null,
    val limit: Int? = null,
    val descending: Boolean = true,
    val sortBy: DocumentSortField = DocumentSortField.UPDATED_AT,
    /**
     * Number of leading rows to skip after sorting+filtering. Pairs with [limit]
     * for Mongo-side pagination — the caller is then guaranteed at most
     * `limit` records ever land in heap. Added in the 2026-05-27 OOM
     * post-mortem follow-up: the previous "load all then in-memory subList"
     * pattern in [ExecutionService.listExecutionsPage] briefly held the entire
     * `execution_status` collection (≈ 350 MiB of payload strings on prod)
     * in heap before paginating; passing `offset` here pushes that bound to
     * the storage backend.
     */
    val offset: Int = 0,
    /**
     * When true, the storage backend MUST NOT return the `payload` string —
     * the returned [StoredDocument.payload] is replaced with an empty string
     * sentinel. Mongo executes the read with `Projections.exclude("payload")`
     * so the wire payload + BSON `readString` allocations are skipped
     * entirely. Use this when the caller only needs the indexed columns
     * (id / ownerId / parentId / status / createdAt / updatedAt) and will
     * never call [kotlinx.serialization.json.Json.decodeFromString] on the
     * result. Trying to decode a stripped document yields a confusing
     * `JsonDecodingException`; pair the flag with a code path that reads
     * only top-level fields.
     */
    val excludePayload: Boolean = false,
    /**
     * Restrict the result to documents whose [StoredDocument.id] is in this
     * set. Mongo uses a `_id` / `id`-field `$in` query in a single round-trip.
     * Use this for batch-load patterns ("get all records for THESE 50 ids");
     * pairs naturally with the metadata-first pagination pattern in
     * [ExecutionService.listExecutionsPage] which (1) reads slim metadata
     * for filter+sort+paginate, (2) batch-loads full payloads only for the
     * resulting page.
     *
     * Null = no id filter (default). Empty list = no rows returned.
     * The list is capped at 1024 ids by the storage backend; longer queries
     * should page or use a different access pattern.
     */
    val idsAnyOf: Collection<String>? = null,
    /**
     * Inclusive lower bound on [StoredDocument.createdAt]. Null = no bound.
     * Mongo translates this to a `createdAt >= x` range predicate, which slots
     * into the R position of the ESR-ordered compound indexes (equality fields
     * first, range last) — see V020DashboardCountIndexes. Primary consumer is
     * [DocumentStore.count] for "how many since <timestamp>" stats; [list]
     * honors it too.
     */
    val createdAtFrom: Long? = null
) {
    init {
        require(limit == null || limit >= 0) {
            "DocumentQuery limit must be >= 0"
        }
        require(offset >= 0) {
            "DocumentQuery offset must be >= 0"
        }
        require(idsAnyOf == null || idsAnyOf.size <= 1024) {
            "DocumentQuery idsAnyOf cap is 1024 (got ${idsAnyOf?.size})"
        }
    }
}

interface DocumentStore : AutoCloseable {
    fun put(document: StoredDocument)

    fun get(collection: String, id: String): StoredDocument?

    fun delete(collection: String, id: String): Boolean

    fun list(query: DocumentQuery): List<StoredDocument>

    /**
     * Count documents matching the query's filter fields without materializing
     * them. [DocumentQuery.sortBy] / [DocumentQuery.descending] /
     * [DocumentQuery.excludePayload] are irrelevant and ignored;
     * [DocumentQuery.offset] and [DocumentQuery.limit] are NOT applied — the
     * full matching cardinality is returned. The Mongo backend issues a
     * server-side `countDocuments`, so no document (let alone payload) ever
     * crosses the wire. Use this instead of `list(query).size` anywhere a
     * number is all you need.
     */
    fun count(query: DocumentQuery): Long

    override fun close() = Unit
}
