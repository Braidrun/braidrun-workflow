package com.fartech.storage

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [DocumentStore] used by tests and dev mode.
 *
 * ## Key encoding
 *
 * Composite keys are built from `(namespace, collection, id)`. An earlier implementation
 * joined them with the literal separator `"::"` — if any of the three parts contained
 * `"::"` itself, two *different* tuples could produce *the same* key and silently
 * overwrite each other. This implementation uses a length-prefixed encoding so distinct
 * tuples always map to distinct keys:
 *
 *     "<ns.length>:<ns>|<col.length>:<col>|<id.length>:<id>"
 *
 * Any string is now a safe input for namespace/collection/id.
 *
 * ## Snapshot semantics for `list(...)`
 *
 * `ConcurrentHashMap.values` returns a *live* view — iterating it while another thread
 * does `put`/`remove` can observe partial state or (rarely) mis-report size. For the
 * `list(...)` query we want a single consistent point-in-time snapshot, so we take
 * `documents.values.toList()` under no additional lock (this is safe per
 * `ConcurrentHashMap` spec — the iterator sees some valid state between the call and
 * the next modification) and operate on the snapshot thereafter.
 */
class InMemoryDocumentStore(
    private val namespace: String
) : DocumentStore {
    private val documents = ConcurrentHashMap<String, StoredDocument>()

    override fun put(document: StoredDocument) {
        documents[key(document.collection, document.id)] = document.copy(namespace = namespace)
    }

    override fun putFenced(document: StoredDocument, fence: Long): Boolean {
        val stamped = document.copy(namespace = namespace, fence = fence)
        var accepted = false
        // `compute` gives per-key atomicity for the whole check-and-write.
        documents.compute(key(document.collection, document.id)) { _, existing ->
            val existingFence = existing?.fence
            if (existing == null || existingFence == null || existingFence <= fence) {
                accepted = true
                stamped
            } else {
                existing
            }
        }
        return accepted
    }

    override fun get(collection: String, id: String): StoredDocument? {
        return documents[key(collection, id)]
    }

    override fun delete(collection: String, id: String): Boolean {
        return documents.remove(key(collection, id)) != null
    }

    override fun list(query: DocumentQuery): List<StoredDocument> {
        return filteredSnapshot(query)
            .sortedWith(buildComparator(query))
            // Apply offset+limit in the same order Mongo would: offset first
            // (after sort+filter), then limit. Keep them inside the sequence
            // so we don't materialize the dropped prefix.
            .drop(query.offset)
            .let { seq -> query.limit?.let(seq::take) ?: seq }
            // Payload-stripping parity with Mongo: clear the payload so
            // tests for `excludePayload=true` behave identically to prod.
            .map { if (query.excludePayload) it.copy(payload = "") else it }
            .toList()
    }

    override fun count(query: DocumentQuery): Long = filteredSnapshot(query).count().toLong()

    /**
     * Snapshot + the filter chain shared by [list] and [count]. Take a snapshot
     * up-front so downstream filtering can't observe concurrent mutations
     * mid-stream — `ConcurrentHashMap.values.toList()` is a weakly-consistent
     * snapshot, good enough for our list semantics.
     */
    private fun filteredSnapshot(query: DocumentQuery): Sequence<StoredDocument> {
        return documents.values.toList()
            .asSequence()
            .filter { it.collection == query.collection && it.namespace == namespace }
            .filter { query.ownerId == null || it.ownerId == query.ownerId }
            .filter { query.parentId == null || it.parentId == query.parentId }
            .filter { query.secondaryId == null || it.secondaryId == query.secondaryId }
            .filter { query.status == null || it.status == query.status }
            .filter { query.createdAtFrom == null || it.createdAt >= query.createdAtFrom }
            .let { seq ->
                val ids = query.idsAnyOf
                when {
                    ids == null -> seq
                    ids.isEmpty() -> emptySequence()
                    else -> seq.filter { it.id in ids }
                }
            }
    }

    /**
     * Length-prefixed composite key. Any collision would require two distinct tuples
     * with identical `length:content` encoding at each position, which is impossible.
     */
    private fun key(collection: String, id: String): String =
        "${namespace.length}:${namespace}|${collection.length}:${collection}|${id.length}:${id}"
}

internal fun buildComparator(query: DocumentQuery): Comparator<StoredDocument> {
    val base = when (query.sortBy) {
        DocumentSortField.CREATED_AT -> compareBy<StoredDocument> { it.createdAt }
        DocumentSortField.UPDATED_AT -> compareBy<StoredDocument> { it.updatedAt }
        DocumentSortField.ID -> compareBy<StoredDocument> { it.id }
    }
    return if (query.descending) base.reversed() else base
}
