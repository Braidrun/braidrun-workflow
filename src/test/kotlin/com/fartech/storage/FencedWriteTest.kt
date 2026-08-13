package com.fartech.storage

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Contract tests for [DocumentStore.putFenced], run against every embedded
 * implementation. Mongo shares the same contract but needs a live server;
 * its gated-replace logic mirrors the SQLite single-statement semantics.
 */
class FencedWriteTest {

    @TempDir
    lateinit var tempDir: Path

    private fun stores(): List<Pair<String, DocumentStore>> = listOf(
        "in-memory" to InMemoryDocumentStore("test"),
        "sqlite" to SqliteDocumentStore(tempDir.resolve("fence.db"), "test")
    )

    private fun document(
        id: String,
        payload: String = "{\"id\":\"$id\"}",
        fence: Long? = null
    ): StoredDocument = StoredDocument(
        id = id,
        collection = "executions",
        namespace = "ignored",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        payload = payload,
        fence = fence
    )

    @Test
    fun `first fenced write on an absent row succeeds and records the fence`() {
        for ((name, store) in stores()) store.use {
            assertTrue(store.putFenced(document("d1", payload = "a"), fence = 3), name)
            val saved = store.get("executions", "d1")
            assertEquals("a", saved?.payload, name)
            assertEquals(3L, saved?.fence, name)
        }
    }

    @Test
    fun `equal and newer fences win, older fences are rejected`() {
        for ((name, store) in stores()) store.use {
            assertTrue(store.putFenced(document("d1", payload = "epoch3"), fence = 3), name)
            // Same epoch: last-write-wins within one logical owner.
            assertTrue(store.putFenced(document("d1", payload = "epoch3b"), fence = 3), name)
            // Newer epoch takes over.
            assertTrue(store.putFenced(document("d1", payload = "epoch4"), fence = 4), name)
            // The stale epoch-3 writer must be rejected and change nothing.
            assertFalse(store.putFenced(document("d1", payload = "stale"), fence = 3), name)
            val saved = store.get("executions", "d1")
            assertEquals("epoch4", saved?.payload, name)
            assertEquals(4L, saved?.fence, name)
        }
    }

    @Test
    fun `fenced write claims legacy unfenced rows`() {
        for ((name, store) in stores()) store.use {
            store.put(document("d1", payload = "legacy"))
            assertNull(store.get("executions", "d1")?.fence, name)
            assertTrue(store.putFenced(document("d1", payload = "fenced"), fence = 1), name)
            assertEquals(1L, store.get("executions", "d1")?.fence, name)
        }
    }

    @Test
    fun `plain put replaces a fenced row and clears the fence`() {
        // Documented mixed-version behavior: unfenced writers bypass fencing.
        for ((name, store) in stores()) store.use {
            assertTrue(store.putFenced(document("d1", payload = "fenced"), fence = 7), name)
            store.put(document("d1", payload = "unfenced"))
            val saved = store.get("executions", "d1")
            assertEquals("unfenced", saved?.payload, name)
            assertNull(saved?.fence, name)
            // The next fenced write re-establishes protection from epoch 1.
            assertTrue(store.putFenced(document("d1", payload = "refenced"), fence = 1), name)
        }
    }

    @Test
    fun `fence survives list reads and payload-stripped reads`() {
        for ((name, store) in stores()) store.use {
            assertTrue(store.putFenced(document("d1"), fence = 5), name)
            val listed = store.list(DocumentQuery(collection = "executions"))
            assertEquals(5L, listed.single().fence, name)
            val stripped = store.list(DocumentQuery(collection = "executions", excludePayload = true))
            assertEquals(5L, stripped.single().fence, name)
            assertEquals("", stripped.single().payload, name)
        }
    }

    @Test
    fun `sqlite migration adds the fence column to a pre-fencing database in place`() {
        val path = tempDir.resolve("legacy.db")
        // Simulate a database created before 1.1.0: same table, no fence column.
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE documents (
                        id TEXT NOT NULL,
                        collection TEXT NOT NULL,
                        namespace TEXT NOT NULL,
                        owner_id TEXT,
                        parent_id TEXT,
                        secondary_id TEXT,
                        status TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        PRIMARY KEY(namespace, collection, id)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO documents VALUES
                    ('old-1', 'executions', 'test', NULL, NULL, NULL, NULL, 1, 1, '{}')
                    """.trimIndent()
                )
            }
        }
        SqliteDocumentStore(path, "test").use { store ->
            // Pre-existing row surfaces with a null fence and is claimable.
            assertNull(store.get("executions", "old-1")?.fence)
            assertTrue(store.putFenced(document("old-1", payload = "claimed"), fence = 2))
            assertEquals(2L, store.get("executions", "old-1")?.fence)
        }
        // Reopening again must not fail on the already-migrated schema.
        SqliteDocumentStore(path, "test").use { store ->
            assertEquals(2L, store.get("executions", "old-1")?.fence)
        }
    }
}
