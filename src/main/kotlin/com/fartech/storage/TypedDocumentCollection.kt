package com.fartech.storage

import kotlinx.serialization.KSerializer

class TypedDocumentCollection<T>(
    private val store: DocumentStore,
    private val collection: String,
    private val serializer: KSerializer<T>,
    private val idSelector: (T) -> String,
    private val ownerIdSelector: (T) -> String? = { null },
    private val parentIdSelector: (T) -> String? = { null },
    private val secondaryIdSelector: (T) -> String? = { null },
    private val statusSelector: (T) -> String? = { null },
    private val createdAtSelector: (T) -> Long = { System.currentTimeMillis() },
    private val updatedAtSelector: (T) -> Long = createdAtSelector,
    private val namespace: String = ""
) {
    fun save(entity: T) {
        store.put(
            StoredDocument(
                id = idSelector(entity),
                collection = collection,
                namespace = namespace,
                ownerId = ownerIdSelector(entity),
                parentId = parentIdSelector(entity),
                secondaryId = secondaryIdSelector(entity),
                status = statusSelector(entity),
                createdAt = createdAtSelector(entity),
                updatedAt = updatedAtSelector(entity),
                payload = StorageJson.json.encodeToString(serializer, entity)
            )
        )
    }

    fun get(id: String): T? {
        val document = store.get(collection, id) ?: return null
        return StorageJson.json.decodeFromString(serializer, document.payload)
    }

    fun delete(id: String): Boolean = store.delete(collection, id)

    fun list(query: DocumentQuery = DocumentQuery(collection = collection)): List<T> {
        return store.list(query.copy(collection = collection)).map {
            StorageJson.json.decodeFromString(serializer, it.payload)
        }
    }

    /**
     * List documents WITHOUT deserializing the JSON payload. Returns the raw
     * [StoredDocument]s with their indexed columns (id / ownerId / parentId /
     * secondaryId / status / createdAt / updatedAt) populated and `payload`
     * blanked to `""`. The Mongo backend uses `Projections.exclude("payload")`
     * so the heavy string never crosses the wire.
     *
     * Use this for list / paging endpoints that only need metadata columns
     * (the 2026-05-27 OOM post-mortem follow-up). Trying to decode the
     * returned documents will fail loudly with a [kotlinx.serialization.json.internal.JsonDecodingException]
     * — that's by design; if you need the payload, use [list] instead.
     *
     * Forces the query's [DocumentQuery.collection] to this collection's
     * name and sets [DocumentQuery.excludePayload]=true, so the caller can
     * pass any base query without worrying about the collection field.
     */
    fun listMetadata(query: DocumentQuery = DocumentQuery(collection = collection)): List<StoredDocument> {
        return store.list(query.copy(collection = collection, excludePayload = true))
    }
}
