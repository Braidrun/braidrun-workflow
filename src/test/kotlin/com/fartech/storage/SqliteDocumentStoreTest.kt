package com.fartech.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SqliteDocumentStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `put get upsert and delete follow the document store contract`() {
        openStore().use { store ->
            val original = document(id = "doc-1", payload = "first", namespace = "ignored")
            store.put(original)

            val saved = store.get("workflows", "doc-1")
            assertEquals(original.copy(namespace = "test"), saved)
            assertNull(store.get("other", "doc-1"))

            val updated = original.copy(status = "COMPLETED", updatedAt = 2_000L, payload = "second")
            store.put(updated)
            assertEquals(updated.copy(namespace = "test"), store.get("workflows", "doc-1"))

            assertTrue(store.delete("workflows", "doc-1"))
            assertFalse(store.delete("workflows", "doc-1"))
            assertNull(store.get("workflows", "doc-1"))
        }
    }

    @Test
    fun `list supports every indexed filter and inclusive created-at bound`() {
        openStore().use { store ->
            store.put(document("a", ownerId = "alice", parentId = "p1", secondaryId = "s1", status = "RUNNING", createdAt = 100))
            store.put(document("b", ownerId = "alice", parentId = "p2", secondaryId = "s2", status = "DONE", createdAt = 200))
            store.put(document("c", ownerId = "bob", parentId = "p1", secondaryId = "s2", status = "DONE", createdAt = 300))
            store.put(document("other", collection = "credentials", ownerId = "alice", createdAt = 400))

            assertEquals(listOf("b", "a"), store.list(query(ownerId = "alice")).map { it.id })
            assertEquals(listOf("c", "a"), store.list(query(parentId = "p1")).map { it.id })
            assertEquals(listOf("c", "b"), store.list(query(secondaryId = "s2")).map { it.id })
            assertEquals(listOf("c", "b"), store.list(query(status = "DONE")).map { it.id })
            assertEquals(listOf("c", "b"), store.list(query(createdAtFrom = 200)).map { it.id })
            assertEquals(
                listOf("b"),
                store.list(query(ownerId = "alice", status = "DONE", createdAtFrom = 200)).map { it.id }
            )
        }
    }

    @Test
    fun `sorting pagination and payload projection match the shared query contract`() {
        openStore().use { store ->
            store.put(document("c", createdAt = 100, updatedAt = 500, payload = "payload-c"))
            store.put(document("a", createdAt = 200, updatedAt = 300, payload = "payload-a"))
            store.put(document("b", createdAt = 300, updatedAt = 100, payload = "payload-b"))

            assertEquals(
                listOf("c", "a", "b"),
                store.list(query(sortBy = DocumentSortField.UPDATED_AT, descending = true)).map { it.id }
            )
            assertEquals(
                listOf("b", "c"),
                store.list(query(sortBy = DocumentSortField.ID, descending = false, offset = 1)).map { it.id }
            )
            assertEquals(
                listOf("a"),
                store.list(query(sortBy = DocumentSortField.CREATED_AT, descending = false, offset = 1, limit = 1)).map { it.id }
            )

            val metadata = store.list(query(excludePayload = true))
            assertEquals(3, metadata.size)
            assertTrue(metadata.all { it.payload.isEmpty() })
            assertEquals(setOf("a", "b", "c"), metadata.map { it.id }.toSet())
        }
    }

    @Test
    fun `idsAnyOf supports empty one 1024 and rejects 1025 ids`() {
        openStore().use { store ->
            store.put(document("wanted"))

            assertTrue(store.list(query(idsAnyOf = emptyList())).isEmpty())
            assertEquals(listOf("wanted"), store.list(query(idsAnyOf = listOf("wanted"))).map { it.id })

            val maximum = listOf("wanted") + (1 until 1024).map { "missing-$it" }
            assertEquals(listOf("wanted"), store.list(query(idsAnyOf = maximum)).map { it.id })

            assertFailsWith<IllegalArgumentException> {
                query(idsAnyOf = (0..1024).map { "id-$it" })
            }
        }
    }

    @Test
    fun `count applies filters but ignores pagination sorting and payload projection`() {
        openStore().use { store ->
            repeat(5) { index ->
                store.put(document("doc-$index", ownerId = if (index < 3) "alice" else "bob"))
            }

            assertEquals(
                3L,
                store.count(
                    query(
                        ownerId = "alice",
                        offset = 2,
                        limit = 0,
                        sortBy = DocumentSortField.ID,
                        descending = false,
                        excludePayload = true
                    )
                )
            )
            assertEquals(0L, store.count(query(idsAnyOf = emptyList())))
        }
    }

    @Test
    fun `data persists after close and namespaces stay isolated`() {
        val database = databasePath()
        SqliteDocumentStore(database, "first").use { store ->
            store.put(document("shared", payload = "first-value"))
        }
        SqliteDocumentStore(database, "second").use { store ->
            assertNull(store.get("workflows", "shared"))
            store.put(document("shared", payload = "second-value"))
        }
        SqliteDocumentStore(database, "first").use { store ->
            assertEquals("first-value", store.get("workflows", "shared")?.payload)
        }
        SqliteDocumentStore(database, "second").use { store ->
            assertEquals("second-value", store.get("workflows", "shared")?.payload)
        }
    }

    @Test
    fun `factory creates parent directories and opens the sqlite backend`() {
        val database = tempDir.resolve("factory/nested/braidrun.db")
        val profile = StorageProfile(
            backend = StorageBackend.SQLITE,
            namespace = "local",
            sqlitePath = database.toString()
        )

        DocumentStoreFactory.open(profile).use { store ->
            assertIs<SqliteDocumentStore>(store)
            store.put(document("factory-doc"))
            assertEquals("local", store.get("workflows", "factory-doc")?.namespace)
        }
        assertTrue(Files.isRegularFile(database))
    }

    @Test
    fun `database enables WAL and creates query indexes`() {
        val database = databasePath()
        openStore().use { store ->
            store.put(document("doc"))
            DriverManager.getConnection("jdbc:sqlite:$database").use { diagnosticConnection ->
                diagnosticConnection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode").use { result ->
                        assertTrue(result.next())
                        assertEquals("wal", result.getString(1).lowercase())
                    }
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_documents_%'"
                    ).use { result ->
                        val indexes = buildSet {
                            while (result.next()) add(result.getString(1))
                        }
                        assertEquals(
                            setOf(
                                "idx_documents_owner_created",
                                "idx_documents_parent",
                                "idx_documents_secondary",
                                "idx_documents_status"
                            ),
                            indexes
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `concurrent reads and writes are serialized safely`() {
        openStore().use { store ->
            val writers = 4
            val documentsPerWriter = 50
            val executor = Executors.newFixedThreadPool(writers + 2)
            val start = CountDownLatch(1)
            try {
                val futures = buildList {
                    repeat(writers) { writer ->
                        add(executor.submit {
                            start.await()
                            repeat(documentsPerWriter) { index ->
                                store.put(document("writer-$writer-doc-$index", ownerId = "writer-$writer"))
                            }
                        })
                    }
                    repeat(2) {
                        add(executor.submit {
                            start.await()
                            repeat(50) {
                                store.count(query())
                                store.list(query(limit = 5, excludePayload = true))
                            }
                        })
                    }
                }

                start.countDown()
                futures.forEach { it.get(15, TimeUnit.SECONDS) }
                assertEquals((writers * documentsPerWriter).toLong(), store.count(query()))
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun openStore(namespace: String = "test"): SqliteDocumentStore =
        SqliteDocumentStore(databasePath(), namespace)

    private fun databasePath(): Path = tempDir.resolve("nested/braidrun.db")

    private fun document(
        id: String,
        collection: String = "workflows",
        namespace: String = "caller",
        ownerId: String? = null,
        parentId: String? = null,
        secondaryId: String? = null,
        status: String? = null,
        createdAt: Long = 1_000L,
        updatedAt: Long = createdAt,
        payload: String = "{\"id\":\"$id\"}"
    ): StoredDocument = StoredDocument(
        id = id,
        collection = collection,
        namespace = namespace,
        ownerId = ownerId,
        parentId = parentId,
        secondaryId = secondaryId,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        payload = payload
    )

    private fun query(
        collection: String = "workflows",
        ownerId: String? = null,
        parentId: String? = null,
        secondaryId: String? = null,
        status: String? = null,
        limit: Int? = null,
        descending: Boolean = true,
        sortBy: DocumentSortField = DocumentSortField.CREATED_AT,
        offset: Int = 0,
        excludePayload: Boolean = false,
        idsAnyOf: Collection<String>? = null,
        createdAtFrom: Long? = null
    ): DocumentQuery = DocumentQuery(
        collection = collection,
        ownerId = ownerId,
        parentId = parentId,
        secondaryId = secondaryId,
        status = status,
        limit = limit,
        descending = descending,
        sortBy = sortBy,
        offset = offset,
        excludePayload = excludePayload,
        idsAnyOf = idsAnyOf,
        createdAtFrom = createdAtFrom
    )
}
