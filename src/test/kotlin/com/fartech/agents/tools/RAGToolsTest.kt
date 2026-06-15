package com.fartech.agents.tools

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for RAGTools using a deterministic mock embedder.
 * No real LLM API calls are made.
 */
class RAGToolsTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var ragTools: RAGTools

    /**
     * A deterministic embedder that produces vectors based on simple character frequency.
     * This allows testing semantic similarity without real LLM calls.
     */
    private class DeterministicEmbedder : Embedder {
        override suspend fun embed(text: String): Vector {
            // Create a 26-dimensional vector based on letter frequency
            val freq = DoubleArray(26)
            val lower = text.lowercase()
            for (c in lower) {
                if (c in 'a'..'z') {
                    freq[c - 'a'] += 1.0
                }
            }
            // Normalize
            val total = freq.sum()
            if (total > 0) {
                for (i in freq.indices) freq[i] /= total
            }
            return Vector(freq.toList())
        }

        override fun diff(embedding1: Vector, embedding2: Vector): Double {
            return 1.0 - embedding1.cosineSimilarity(embedding2)
        }
    }

    private val mockEmbedderFactory = object : EmbedderFactory {
        override fun create(parameters: List<ConfigurationParameter>, baseClient: HttpClient?): Embedder {
            return DeterministicEmbedder()
        }
    }

    @BeforeEach
    fun setup() {
        val params = listOf(
            ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("rag-index").absolutePath))
        )
        ragTools = RAGTools(params, mockEmbedderFactory)
    }

    // ==================== Chunking Tests ====================

    @Nested
    inner class ChunkingTests {
        @Test
        fun `short text returns single chunk`() {
            val chunks = ragTools.splitIntoChunks("Hello world", 1000, 200)
            assertEquals(1, chunks.size)
            assertEquals("Hello world", chunks[0])
        }

        @Test
        fun `long text is split into overlapping chunks`() {
            val text = "a".repeat(2500)
            val chunks = ragTools.splitIntoChunks(text, 1000, 200)
            assertTrue(chunks.size >= 3)
            // Each chunk <= 1000 chars
            chunks.forEach { assertTrue(it.length <= 1000) }
        }

        @Test
        fun `chunks have overlap`() {
            val text = (1..100).joinToString(" ") { "word$it" }
            val chunks = ragTools.splitIntoChunks(text, 200, 50)
            if (chunks.size >= 2) {
                // The end of chunk 0 should overlap with the start of chunk 1
                val endOfFirst = chunks[0].takeLast(50)
                assertTrue(chunks[1].startsWith(endOfFirst))
            }
        }

        @Test
        fun `empty overlap still works`() {
            val text = "a".repeat(500)
            val chunks = ragTools.splitIntoChunks(text, 200, 0)
            assertTrue(chunks.size >= 3)
            assertEquals(text, chunks.joinToString(""))
        }

        @Test
        fun `zero chunk size parameter falls back to safe progress`() {
            val tools = RAGTools(
                parameters = listOf(
                    ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("zero-chunk-rag").absolutePath)),
                    ConfigurationParameter("rag_chunk_size", JsonPrimitive("0"))
                ),
                embedderFactory = mockEmbedderFactory
            )

            val result = tools.indexDocument("zero-chunk", "content that should not hang")

            assertContains(result, "Successfully indexed")
        }
    }

    // ==================== Index Document Tests ====================

    @Nested
    inner class IndexDocumentTests {
        @Test
        fun `index a short document`() {
            val result = ragTools.indexDocument("test-doc", "This is a test document about Kotlin programming.")
            assertContains(result, "Successfully indexed")
            assertContains(result, "test-doc")
            assertContains(result, "1 chunk(s)")
        }

        @Test
        fun `index a long document creates multiple chunks`() {
            val content = (1..200).joinToString("\n") { "Line $it: This is a paragraph of text about software engineering and architecture design patterns." }
            val result = ragTools.indexDocument("long-doc", content)
            assertContains(result, "Successfully indexed")
            assertContains(result, "long-doc")
            // Should have multiple chunks
            assertTrue(result.contains("chunk(s)"))
        }

        @Test
        fun `index with tags`() {
            val result = ragTools.indexDocument("tagged-doc", "Content here", "kotlin,test,rag")
            assertContains(result, "Successfully indexed")
        }

        @Test
        fun `re-index overwrites existing document`() {
            ragTools.indexDocument("reindex-doc", "Original content")
            val result = ragTools.indexDocument("reindex-doc", "Updated content")
            assertContains(result, "Successfully indexed")

            // Search should find updated content
            val searchResult = ragTools.searchKnowledge("Updated")
            assertContains(searchResult, "Updated content")
        }

        @Test
        fun `index document embeds chunks with controlled parallelism`() {
            val activeEmbeddings = AtomicInteger(0)
            val maxObservedEmbeddings = AtomicInteger(0)
            val parallelEmbedderFactory = object : EmbedderFactory {
                override fun create(parameters: List<ConfigurationParameter>, baseClient: HttpClient?): Embedder {
                    return object : Embedder {
                        override suspend fun embed(text: String): Vector {
                            val active = activeEmbeddings.incrementAndGet()
                            maxObservedEmbeddings.updateAndGet { current -> maxOf(current, active) }
                            try {
                                delay(40)
                                return Vector(listOf(text.length.toDouble()))
                            } finally {
                                activeEmbeddings.decrementAndGet()
                            }
                        }

                        override fun diff(embedding1: Vector, embedding2: Vector): Double {
                            return 1.0 - embedding1.cosineSimilarity(embedding2)
                        }
                    }
                }
            }
            val parallelTools = RAGTools(
                parameters = listOf(
                    ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("parallel-rag-index").absolutePath)),
                    ConfigurationParameter("rag_chunk_size", JsonPrimitive("40")),
                    ConfigurationParameter("rag_chunk_overlap", JsonPrimitive("0")),
                    ConfigurationParameter("rag_embedding_parallelism", JsonPrimitive("3"))
                ),
                embedderFactory = parallelEmbedderFactory
            )

            val result = parallelTools.indexDocument("parallel-doc", "a".repeat(240))

            assertContains(result, "Successfully indexed")
            assertTrue(maxObservedEmbeddings.get() > 1)
            assertTrue(maxObservedEmbeddings.get() <= 3)
        }
    }

    // ==================== Index File Tests ====================

    @Nested
    inner class IndexFileTests {
        @Test
        fun `index a text file`() {
            val file = tempDir.resolve("test.md")
            file.writeText("# RAG Documentation\n\nThis is a test markdown file about retrieval augmented generation.")
            val result = ragTools.indexFile(file.absolutePath)
            assertContains(result, "Successfully indexed")
            assertContains(result, "test.md")
        }

        @Test
        fun `index nonexistent file returns error`() {
            val result = ragTools.indexFile("/nonexistent/path/file.txt")
            assertContains(result, "Error: File not found")
        }

        @Test
        fun `index empty file returns error`() {
            val file = tempDir.resolve("empty.txt")
            file.writeText("")
            val result = ragTools.indexFile(file.absolutePath)
            assertContains(result, "Error: File is empty")
        }

        @Test
        fun `index directory path returns error`() {
            val result = ragTools.indexFile(tempDir.absolutePath)
            assertContains(result, "Path is not a file")
        }

        @Test
        fun `strict sandbox blocks indexing files outside allowed directories`() {
            val outside = File("/etc/hosts")
            if (!outside.exists()) return
            val tools = RAGTools(
                parameters = listOf(
                    ConfigurationParameter("working_dir", JsonPrimitive(tempDir.absolutePath)),
                    ConfigurationParameter("sandbox_strict", JsonPrimitive("true")),
                    ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("strict-rag").absolutePath))
                ),
                embedderFactory = mockEmbedderFactory
            )

            val result = tools.indexFile(outside.absolutePath)

            assertContains(result, "outside allowed directories")
        }

        @Test
        fun `strict sandbox read guard blocks sensitive source filenames`() {
            val envFile = tempDir.resolve(".env").also { it.writeText("TOKEN=secret") }
            val tools = RAGTools(
                parameters = listOf(
                    ConfigurationParameter("working_dir", JsonPrimitive(tempDir.absolutePath)),
                    ConfigurationParameter("sandbox_strict", JsonPrimitive("true")),
                    ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("strict-rag-env").absolutePath))
                ),
                embedderFactory = mockEmbedderFactory
            )

            val result = tools.indexFile(envFile.absolutePath)

            assertContains(result, "Read denied")
        }

        @Test
        fun `index document enforces configured document byte cap`() {
            val tools = RAGTools(
                parameters = listOf(
                    ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("small-cap-rag").absolutePath)),
                    ConfigurationParameter("rag_max_document_bytes", JsonPrimitive("8"))
                ),
                embedderFactory = mockEmbedderFactory
            )

            val result = tools.indexDocument("too-large", "this is longer than eight bytes")

            assertContains(result, "exceeds rag_max_document_bytes")
        }
    }

    // ==================== Index Directory Tests ====================

    @Nested
    inner class IndexDirectoryTests {
        @Test
        fun `index a directory with multiple files`() {
            val docsDir = tempDir.resolve("docs").also { it.mkdirs() }
            docsDir.resolve("readme.md").writeText("# Project readme\nThis project is about RAG tools.")
            docsDir.resolve("guide.txt").writeText("User guide for the RAG knowledge base system.")
            docsDir.resolve("image.png").writeBytes(ByteArray(100)) // Non-text file, should be skipped

            val result = ragTools.indexDirectory(docsDir.absolutePath)
            assertContains(result, "Indexed 2 file(s)")
        }

        @Test
        fun `index directory with extension filter`() {
            val docsDir = tempDir.resolve("filtered").also { it.mkdirs() }
            docsDir.resolve("code.kt").writeText("fun main() { println(\"Hello\") }")
            docsDir.resolve("readme.md").writeText("# Readme")
            docsDir.resolve("data.json").writeText("{\"key\": \"value\"}")

            val result = ragTools.indexDirectory(docsDir.absolutePath, extensions = "kt")
            assertContains(result, "Indexed 1 file(s)")
        }

        @Test
        fun `index nonexistent directory returns error`() {
            val result = ragTools.indexDirectory("/nonexistent/dir")
            assertContains(result, "Error: Directory not found")
        }

        @Test
        fun `index empty directory returns no matches`() {
            val emptyDir = tempDir.resolve("empty-dir").also { it.mkdirs() }
            val result = ragTools.indexDirectory(emptyDir.absolutePath)
            assertContains(result, "No matching files found")
        }

        @Test
        fun `index directory enforces file count cap`() {
            val docsDir = tempDir.resolve("too-many").also { it.mkdirs() }
            docsDir.resolve("a.md").writeText("A")
            docsDir.resolve("b.md").writeText("B")
            val tools = RAGTools(
                parameters = listOf(
                    ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("limited-rag").absolutePath)),
                    ConfigurationParameter("rag_max_directory_files", JsonPrimitive("1"))
                ),
                embedderFactory = mockEmbedderFactory
            )

            val result = tools.indexDirectory(docsDir.absolutePath)

            assertContains(result, "Too many matching files")
        }
    }

    // ==================== Search Tests ====================

    @Nested
    inner class SearchTests {
        @Test
        fun `search empty knowledge base`() {
            val result = ragTools.searchKnowledge("anything")
            assertContains(result, "Knowledge base is empty")
        }

        @Test
        fun `search returns relevant results`() {
            ragTools.indexDocument("kotlin-doc", "Kotlin is a modern programming language for JVM development with null safety features.")
            ragTools.indexDocument("python-doc", "Python is a dynamic scripting language popular for data science and machine learning.")
            ragTools.indexDocument("recipe-doc", "This is a recipe for chocolate cake with eggs flour and sugar.")

            val result = ragTools.searchKnowledge("programming language")
            assertContains(result, "relevant result(s)")
            // Should return results (our simple embedder will rank by character overlap)
            assertContains(result, "similarity:")
        }

        @Test
        fun `search with topK parameter`() {
            ragTools.indexDocument("doc1", "First document about software architecture.")
            ragTools.indexDocument("doc2", "Second document about database design.")
            ragTools.indexDocument("doc3", "Third document about cloud computing.")

            val result = ragTools.searchKnowledge("software", topK = "1")
            assertContains(result, "1 relevant result(s)")
        }

        @Test
        fun `search with invalid topK uses default`() {
            ragTools.indexDocument("doc1", "Test content for search.")
            val result = ragTools.searchKnowledge("test", topK = "invalid")
            // Should not error, uses default topK
            assertTrue(result.contains("relevant result(s)") || result.contains("No results"))
        }
    }

    // ==================== List Documents Tests ====================

    @Nested
    inner class ListDocumentsTests {
        @Test
        fun `list empty knowledge base`() {
            val result = ragTools.listDocuments()
            assertContains(result, "Knowledge base is empty")
        }

        @Test
        fun `list indexed documents`() {
            ragTools.indexDocument("doc-a", "Content A", "tag1,tag2")
            ragTools.indexDocument("doc-b", "Content B")

            val result = ragTools.listDocuments()
            assertContains(result, "2 document(s)")
            assertContains(result, "doc-a")
            assertContains(result, "doc-b")
            assertContains(result, "tag1")
        }
    }

    // ==================== Delete Document Tests ====================

    @Nested
    inner class DeleteDocumentTests {
        @Test
        fun `delete existing document`() {
            ragTools.indexDocument("to-delete", "Content to be deleted.")
            val result = ragTools.deleteDocument("to-delete")
            assertContains(result, "Successfully deleted")
            assertContains(result, "to-delete")

            // Verify it's gone
            val listResult = ragTools.listDocuments()
            assertContains(listResult, "Knowledge base is empty")
        }

        @Test
        fun `delete nonexistent document`() {
            val result = ragTools.deleteDocument("nonexistent")
            assertContains(result, "not found")
        }
    }

    // ==================== Get Document Info Tests ====================

    @Nested
    inner class GetDocumentInfoTests {
        @Test
        fun `get info for existing document`() {
            ragTools.indexDocument("info-doc", "Short content for info test.", "info,test")
            val result = ragTools.getDocumentInfo("info-doc")
            assertContains(result, "info-doc")
            assertContains(result, "Total Chunks: 1")
            assertContains(result, "info, test")
        }

        @Test
        fun `get info for nonexistent document`() {
            val result = ragTools.getDocumentInfo("nonexistent")
            assertContains(result, "not found")
        }
    }

    // ==================== Clear Knowledge Base Tests ====================

    @Nested
    inner class ClearKnowledgeBaseTests {
        @Test
        fun `clear knowledge base`() {
            ragTools.indexDocument("doc1", "Content 1")
            ragTools.indexDocument("doc2", "Content 2")

            val result = ragTools.clearKnowledgeBase()
            assertContains(result, "cleared")
            assertContains(result, "2 document(s)")

            // Verify it's empty
            val listResult = ragTools.listDocuments()
            assertContains(listResult, "Knowledge base is empty")
        }

        @Test
        fun `clear empty knowledge base`() {
            val result = ragTools.clearKnowledgeBase()
            assertContains(result, "cleared")
            assertContains(result, "0 document(s)")
        }
    }

    // ==================== Persistence Tests ====================

    @Nested
    inner class PersistenceTests {
        @Test
        fun `data persists across RAGTools instances`() {
            // Index with first instance
            ragTools.indexDocument("persist-doc", "Persistent content for testing.")

            // Create a new RAGTools instance with the same storage dir
            val params = listOf(
                ConfigurationParameter("rag_storage_dir", JsonPrimitive(tempDir.resolve("rag-index").absolutePath))
            )
            val ragTools2 = RAGTools(params, mockEmbedderFactory)

            // Should find the document
            val listResult = ragTools2.listDocuments()
            assertContains(listResult, "persist-doc")

            // Search should work
            val searchResult = ragTools2.searchKnowledge("persistent")
            assertContains(searchResult, "Persistent content")
        }
    }

    @Nested
    inner class EmbeddingCredentialResolutionTests {
        @Test
        fun `resolve embedding api key accepts openrouter direct parameter`() {
            val params = listOf(
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-openrouter"))
            )

            assertEquals("sk-openrouter", resolveEmbeddingApiKey(params, env = emptyMap()))
            assertEquals(
                "https://openrouter.ai/api/v1",
                resolveEmbeddingBaseUrl(params, env = emptyMap())
            )
        }

        @Test
        fun `resolve embedding api key accepts openrouter provider key and normalizes base url`() {
            val params = listOf(
                ConfigurationParameter(
                    "llm_provider_keys",
                    kotlinx.serialization.json.buildJsonObject {
                        put("open_router", JsonPrimitive("sk-openrouter-alias"))
                    }
                ),
                ConfigurationParameter("openrouter_base_url", JsonPrimitive("https://openrouter.ai"))
            )

            assertEquals("sk-openrouter-alias", resolveEmbeddingApiKey(params, env = emptyMap()))
            assertEquals(
                "https://openrouter.ai/api/v1",
                resolveEmbeddingBaseUrl(params, env = emptyMap())
            )
        }

        @Test
        fun `resolve embedding api key restores missing openrouter sk prefix`() {
            val params = listOf(
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("k-or-v1-openrouter"))
            )

            assertEquals("sk-or-v1-openrouter", resolveEmbeddingApiKey(params, env = emptyMap()))
        }
    }

    // ==================== Integration Tests ====================

    @Nested
    inner class IntegrationTests {
        @Test
        fun `full workflow - index search delete`() {
            // Index
            val indexResult = ragTools.indexDocument("workflow-doc", "Workflow management system for AI agents with DAG scheduling and agent-based orchestration.")
            assertContains(indexResult, "Successfully indexed")

            // List
            val listResult = ragTools.listDocuments()
            assertContains(listResult, "workflow-doc")

            // Search
            val searchResult = ragTools.searchKnowledge("AI agent orchestration")
            assertContains(searchResult, "relevant result(s)")

            // Get info
            val infoResult = ragTools.getDocumentInfo("workflow-doc")
            assertContains(infoResult, "workflow-doc")

            // Delete
            val deleteResult = ragTools.deleteDocument("workflow-doc")
            assertContains(deleteResult, "Successfully deleted")

            // Verify gone
            val emptyResult = ragTools.listDocuments()
            assertContains(emptyResult, "Knowledge base is empty")
        }

        @Test
        fun `index file and search its content`() {
            val file = tempDir.resolve("architecture.md")
            file.writeText("""
                # System Architecture
                
                The system uses a microservices architecture with the following components:
                - API Gateway for request routing
                - Authentication service for user management
                - Database service using PostgreSQL
                - Message queue with RabbitMQ for async processing
            """.trimIndent())

            ragTools.indexFile(file.absolutePath, "architecture,docs")

            val result = ragTools.searchKnowledge("authentication user management")
            assertContains(result, "relevant result(s)")
        }
    }
}
