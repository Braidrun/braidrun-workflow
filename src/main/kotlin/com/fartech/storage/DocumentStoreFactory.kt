package com.fartech.storage

object DocumentStoreFactory {
    fun open(profile: StorageProfile): DocumentStore {
        val normalized = profile.normalized()
        return when (normalized.backend) {
            StorageBackend.MEMORY -> InMemoryDocumentStore(normalized.namespace)
            StorageBackend.MONGODB -> MongoDocumentStore(normalized)
        }
    }
}
