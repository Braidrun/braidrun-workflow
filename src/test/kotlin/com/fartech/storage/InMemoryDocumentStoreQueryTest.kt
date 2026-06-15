package com.fartech.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the [DocumentQuery] additions made in the 2026-05-27 OOM
 * post-mortem: `offset`, `excludePayload`, and `idsAnyOf`. These are
 * tested against [InMemoryDocumentStore] because we don't run Mongo
 * containers in unit tests; the storage backends share the same query
 * contract so behaviour parity is the point of these tests.
 *
 * Each test seeds the in-memory store with deterministically ordered
 * documents, then asserts that the new query flags slice the result
 * the way callers (notably `ExecutionService.listExecutionsPage`'s
 * two-phase fetch) depend on.
 */
class InMemoryDocumentStoreQueryTest {

    private fun seedStore(count: Int = 5, collection: String = "execution_status"): InMemoryDocumentStore {
        val store = InMemoryDocumentStore(namespace = "test")
        repeat(count) { i ->
            store.put(
                StoredDocument(
                    id = "exec-$i",
                    collection = collection,
                    namespace = "test",
                    ownerId = "owner-${i % 2}",       // alternating owner
                    parentId = "workflow-${i / 2}",    // pairs share workflow
                    secondaryId = "Workflow ${i / 2}",
                    status = if (i % 2 == 0) "COMPLETED" else "RUNNING",
                    createdAt = 1_000_000L + i * 1000L,
                    updatedAt = 1_000_000L + i * 1000L,
                    payload = """{"executionId":"exec-$i","heavyEvents":"${"x".repeat(1000)}"}"""
                )
            )
        }
        return store
    }

    @Test
    fun `offset skips the first N rows after sort and filter`() {
        val store = seedStore(count = 5)
        // Default sort: UPDATED_AT descending → exec-4, exec-3, exec-2, exec-1, exec-0
        val skipFirstTwo = store.list(
            DocumentQuery(collection = "execution_status", offset = 2)
        )
        assertEquals(listOf("exec-2", "exec-1", "exec-0"), skipFirstTwo.map { it.id })
    }

    @Test
    fun `offset plus limit yields a Mongo-shaped page`() {
        val store = seedStore(count = 10)
        // Page 2 of size 3 → skip 3, take 3 → exec-6, exec-5, exec-4
        val page = store.list(
            DocumentQuery(collection = "execution_status", offset = 3, limit = 3)
        )
        assertEquals(listOf("exec-6", "exec-5", "exec-4"), page.map { it.id })
    }

    @Test
    fun `offset beyond the result set returns empty without crashing`() {
        val store = seedStore(count = 3)
        val page = store.list(
            DocumentQuery(collection = "execution_status", offset = 100)
        )
        assertTrue(page.isEmpty())
    }

    @Test
    fun `excludePayload returns the same indexed columns but a blank payload`() {
        val store = seedStore(count = 3)
        val slim = store.list(
            DocumentQuery(collection = "execution_status", excludePayload = true)
        )
        assertEquals(3, slim.size)
        slim.forEach { doc ->
            // Indexed columns are preserved.
            assertNotNull(doc.id)
            assertNotNull(doc.parentId)
            assertNotNull(doc.secondaryId)
            assertNotNull(doc.ownerId)
            assertNotNull(doc.status)
            // Payload is the empty-string sentinel — callers MUST NOT
            // decode it as JSON; that would explode with JsonDecodingException
            // (which is what we want — fail loud, not silent).
            assertEquals("", doc.payload)
        }
    }

    @Test
    fun `idsAnyOf restricts the result to the given id set in a single pass`() {
        val store = seedStore(count = 5)
        val rows = store.list(
            DocumentQuery(
                collection = "execution_status",
                idsAnyOf = listOf("exec-0", "exec-3", "exec-99-missing")
            )
        )
        // Two matched (missing id silently dropped), order is the
        // default UPDATED_AT desc.
        assertEquals(listOf("exec-3", "exec-0"), rows.map { it.id })
    }

    @Test
    fun `empty idsAnyOf short-circuits to empty result`() {
        val store = seedStore(count = 5)
        val rows = store.list(
            DocumentQuery(
                collection = "execution_status",
                idsAnyOf = emptyList()
            )
        )
        assertTrue(rows.isEmpty(),
            "An empty idsAnyOf must return zero rows — this is the " +
                "expected semantics of \$in [] and protects against " +
                "accidentally returning the whole collection if a caller " +
                "constructs an empty page-id list.")
    }

    @Test
    fun `idsAnyOf and excludePayload compose for the two-phase fetch pattern`() {
        // This is the exact pattern used by
        // ExecutionService.listExecutionsPage: Phase A scans metadata,
        // Phase B batch-loads payloads for the page's id set.
        val store = seedStore(count = 8)
        // Phase A: metadata-only sweep.
        val metadata = store.list(
            DocumentQuery(collection = "execution_status", excludePayload = true)
        )
        // (filter / sort / paginate would happen here in the real service)
        val pageIds = metadata.take(3).map { it.id }
        // Phase B: batch fetch full payloads for the page's 3 ids.
        val full = store.list(
            DocumentQuery(
                collection = "execution_status",
                idsAnyOf = pageIds
            )
        )
        assertEquals(3, full.size)
        full.forEach { doc ->
            // Full payload restored — heavy events present again.
            assertTrue(doc.payload.contains("heavyEvents"),
                "Phase B payload must restore the full JSON; got: ${doc.payload}")
        }
    }

    @Test
    fun `DocumentQuery rejects negative offset at construction`() {
        assertFailsWith<IllegalArgumentException> {
            DocumentQuery(collection = "x", offset = -1)
        }
    }

    @Test
    fun `DocumentQuery caps idsAnyOf at 1024 to bound Mongo in operator size`() {
        // The cap exists because Mongo's `$in` cardinality starts to
        // hurt query planner performance well before its hard limit;
        // we want callers to use pagination or a different access
        // pattern for very large id sets.
        val tooMany = (1..1025).map { "id-$it" }
        assertFailsWith<IllegalArgumentException> {
            DocumentQuery(collection = "x", idsAnyOf = tooMany)
        }
        // Exactly 1024 is fine.
        val justEnough = (1..1024).map { "id-$it" }
        DocumentQuery(collection = "x", idsAnyOf = justEnough)
    }

    @Test
    fun `listMetadata convenience wrapper goes through excludePayload mode`() {
        val store = seedStore(count = 3)
        // Use the TypedDocumentCollection wrapper that the workflow-web
        // service layer talks to — this is the actual contract that
        // ExecutionService.listExecutionsPage relies on.
        val collection = TypedDocumentCollection(
            store = store,
            collection = "execution_status",
            serializer = kotlinx.serialization.serializer<String>(),
            idSelector = { it }
        )
        val slim = collection.listMetadata(
            DocumentQuery(collection = "execution_status")
        )
        assertEquals(3, slim.size)
        slim.forEach { doc -> assertEquals("", doc.payload) }
    }
}
