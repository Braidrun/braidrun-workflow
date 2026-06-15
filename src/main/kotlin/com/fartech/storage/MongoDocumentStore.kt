package com.fartech.storage

import com.mongodb.MongoException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReplaceOptions
import mu.KotlinLogging
import org.bson.Document
import org.bson.conversions.Bson
import org.litote.kmongo.KMongo
import org.litote.kmongo.and
import org.litote.kmongo.ascending
import org.litote.kmongo.descending
import org.litote.kmongo.eq
import org.litote.kmongo.`in`

private val logger = KotlinLogging.logger("com.fartech.storage.MongoDocumentStore")

class MongoDocumentStore(
    private val profile: StorageProfile
) : DocumentStore {
    private val namespace = profile.namespace
    private val mongoClient = try {
        KMongo.createClient(profile.mongodb.connectionString)
    } catch (e: Exception) {
        // Don't leak the full connection string (which can embed user:password) via the
        // bubbled exception. Callers still get a clear failure; the full URI (redacted)
        // is in the server log only.
        logger.error(e) {
            "KMongo client creation failed for database=${profile.mongodb.database} " +
                "(connection string redacted)"
        }
        throw MongoException("Failed to initialize MongoDB client (see server logs for details)", e)
    }
    private val collection: MongoCollection<Document> =
        mongoClient.getDatabase(profile.mongodb.database).getCollection(profile.mongodb.collectionName)

    /**
     * Atomically replace-or-insert the document.
     *
     * The previous implementation was `delete(...); insertOne(...)` — that's a two-step
     * sequence with a gap: a concurrent `put` for the same id would interleave, losing
     * one of the writes entirely. Even a single-writer scenario could leave the record
     * gone if `insertOne` failed after `delete` succeeded.
     *
     * Using `replaceOne(..., upsert=true)` collapses the two operations into one atomic
     * MongoDB document-level write.
     */
    override fun put(document: StoredDocument) {
        val payload = document.copy(namespace = namespace).toBson()
        collection.replaceOne(
            namespaceFilter(document.collection, document.id),
            payload,
            ReplaceOptions().upsert(true)
        )
    }

    override fun get(collection: String, id: String): StoredDocument? {
        return this.collection.find(namespaceFilter(collection, id)).firstOrNull()?.toStoredDocument()
    }

    override fun delete(collection: String, id: String): Boolean {
        return this.collection.deleteOne(namespaceFilter(collection, id)).deletedCount > 0
    }

    override fun list(query: DocumentQuery): List<StoredDocument> {
        val filter = mutableListOf<Bson>(
            StoredDocument::namespace eq namespace,
            StoredDocument::collection eq query.collection
        )
        query.ownerId?.let { filter.add(StoredDocument::ownerId eq it) }
        query.parentId?.let { filter.add(StoredDocument::parentId eq it) }
        query.secondaryId?.let { filter.add(StoredDocument::secondaryId eq it) }
        query.status?.let { filter.add(StoredDocument::status eq it) }
        query.idsAnyOf?.let { ids ->
            // Short-circuit on an empty id list — without this Mongo
            // accepts `$in: []` and returns zero rows, which is correct
            // but wastes a round-trip. Bail in Kotlin.
            if (ids.isEmpty()) return emptyList()
            filter.add(StoredDocument::id `in` ids)
        }

        val sort = when (query.sortBy) {
            DocumentSortField.CREATED_AT -> if (query.descending) descending(StoredDocument::createdAt) else ascending(StoredDocument::createdAt)
            DocumentSortField.UPDATED_AT -> if (query.descending) descending(StoredDocument::updatedAt) else ascending(StoredDocument::updatedAt)
            DocumentSortField.ID -> if (query.descending) descending(StoredDocument::id) else ascending(StoredDocument::id)
        }

        var cursor = collection.find(and(filter)).sort(sort)
        // Mongo-side pagination — pushes the offset down to the cursor so the
        // server never sends pages we'll discard. Pairs with `limit`.
        if (query.offset > 0) {
            cursor = cursor.skip(query.offset)
        }
        query.limit?.let { cursor = cursor.limit(it) }
        // Mongo-side projection — pushes "drop the heavy payload column" to
        // the cursor so the BSON `readString` allocation for payload (avg
        // 470 KB / max 6.4 MB on prod execution_status) never happens.
        // Caller MUST be OK with `payload = ""` on the returned documents.
        if (query.excludePayload) {
            cursor = cursor.projection(Projections.exclude("payload"))
        }
        return cursor.toList().mapNotNull { it.toStoredDocument() }
    }

    override fun close() {
        mongoClient.close()
    }

    private fun namespaceFilter(collection: String, id: String): Bson = and(
        StoredDocument::namespace eq namespace,
        StoredDocument::collection eq collection,
        StoredDocument::id eq id
    )

    private fun StoredDocument.toBson(): Document {
        return Document(
            mapOf(
                "id" to id,
                "collection" to collection,
                "namespace" to namespace,
                "ownerId" to ownerId,
                "parentId" to parentId,
                "secondaryId" to secondaryId,
                "status" to status,
                "createdAt" to createdAt,
                "updatedAt" to updatedAt,
                "payload" to payload
            )
        )
    }

    private fun Document.toStoredDocument(): StoredDocument? {
        val id = getString("id") ?: return null
        val collectionName = getString("collection") ?: return null
        val namespaceValue = getString("namespace") ?: return null
        val createdAt = coerceLong(get("createdAt")) ?: return null
        val updatedAt = coerceLong(get("updatedAt")) ?: return null
        // payload may be absent when the read was issued with
        // `DocumentQuery.excludePayload = true` (Mongo `Projections.exclude`).
        // In that case we surface an empty-string sentinel; callers that
        // opted into payload-stripping are expected to read only top-level
        // columns and never decode the payload JSON.
        val payload = getString("payload") ?: ""
        return StoredDocument(
            id = id,
            collection = collectionName,
            namespace = namespaceValue,
            ownerId = getString("ownerId"),
            parentId = getString("parentId"),
            secondaryId = getString("secondaryId"),
            status = getString("status"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            payload = payload
        )
    }
}

internal fun coerceLong(value: Any?): Long? = when (value) {
    null -> null
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is Double -> value.takeIf { it.isFinite() }?.toLong()
    is Float -> value.takeIf { it.isFinite() }?.toLong()
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
