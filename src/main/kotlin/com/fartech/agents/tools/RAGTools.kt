package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}
private const val DEFAULT_OPENAI_EMBEDBRAIDRUN_BASE_URL = "https://api.openai.com/v1"
private const val DEFAULT_OPENROUTER_EMBEDBRAIDRUN_BASE_URL = "https://openrouter.ai/api/v1"

// ============================================================================
// runBlocking policy in this file
// ============================================================================
// Every operation that touches the embedder or the on-disk index has a
// `*Suspend` variant. The non-suspend overloads are thin `runBlocking` wrappers
// kept for koog's `@Tool` dispatcher (which invokes tools from a worker thread
// that is free to block) and for non-suspend Java interop.
//
// **Coroutine-native callers must use the `*Suspend` form.** Calling the
// blocking overload from a suspend function parks a Dispatchers.Default worker
// and is visible in production as reduced throughput on the agent's parallel
// step dispatcher.
//
// Migration history:
//   - Phase 4 (2026-04) — added `indexDocumentWithSourceSuspend`,
//     `indexFileSuspend`; migrated `WorkflowExecutor` auto-index call sites.
//   - Phase 5 follow-up — added `searchKnowledgeEntriesSuspend`,
//     `deleteDocumentSuspend`, `clearKnowledgeBaseSuspend`; migrated
//     `AssistantDocsKnowledgeBaseService` in braidrun-web.
// ============================================================================

// ============================================================================
// Persistence Models
// ============================================================================

@Serializable
private data class ChunkRecord(
    val id: String,
    val documentId: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val chunkIndex: Int = 0,
    val sourceFile: String? = null
)

@Serializable
private data class DocumentRecord(
    val id: String,
    val tags: List<String> = emptyList(),
    val chunkIds: List<String> = emptyList(),
    val sourceFile: String? = null,
    val totalChunks: Int = 0,
    val indexedAt: String = ""
)

@Serializable
private data class RAGIndex(
    val documents: MutableMap<String, DocumentRecord> = mutableMapOf(),
    val chunks: MutableMap<String, ChunkRecord> = mutableMapOf()
)

@Serializable
data class RAGDocumentSummary(
    val id: String,
    val tags: List<String> = emptyList(),
    val sourceFile: String? = null,
    val totalChunks: Int = 0,
    val indexedAt: String = ""
)

@Serializable
data class RAGSearchResult(
    val documentId: String,
    val sourceFile: String? = null,
    val tags: List<String> = emptyList(),
    val chunkIndex: Int = 0,
    val content: String,
    val similarity: Double
)

@Serializable
data class RAGKnowledgeBaseStats(
    val documentCount: Int = 0,
    val chunkCount: Int = 0
)

private val jsonFormat = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ============================================================================
// Embedder abstraction for testability
// ============================================================================

/**
 * Factory interface for creating Embedder instances.
 * Allows dependency injection and testing without real LLM calls.
 */
interface EmbedderFactory {
    fun create(parameters: List<ConfigurationParameter>, baseClient: HttpClient? = null): Embedder

    companion object {
        /**
         * Default factory that creates a real LLMEmbedder backed by OpenAI API.
         */
        val Default: EmbedderFactory = object : EmbedderFactory {
            override fun create(parameters: List<ConfigurationParameter>, baseClient: HttpClient?): Embedder {
                val embeddingModel = parameters.parameter("rag_embedding_model", "text-embedding-3-small")
                val baseUrl = resolveEmbeddingBaseUrl(parameters)
                val apiKey = resolveEmbeddingApiKey(parameters)

                val model = LLModel(
                    provider = LLMProvider.OpenAI,
                    id = embeddingModel,
                    capabilities = listOf(LLMCapability.Embed),
                    contextLength = 8192,
                    maxOutputTokens = 0
                )

                // Koog 1.0.0 removed the `baseClient: HttpClient` constructor;
                // LLM clients now route every request through a `KoogHttpClient`
                // discovered via ServiceLoader (`HttpClientFactoryResolver`), or
                // an explicit `httpClientFactory: KoogHttpClient.Factory` passed
                // at construction. The Ktor-backed factory is on our runtime
                // classpath via the `http-client-ktor` transitive dep brought
                // in by `koog-agents:1.0.0`, so the no-factory constructor
                // works out of the box and `baseClient` no longer needs to be
                // threaded through RAG tooling.
                val client = OpenAILLMClient(
                    apiKey = apiKey,
                    settings = OpenAIClientSettings(baseUrl = baseUrl)
                )

                return LLMEmbedder(client, model)
            }
        }
    }
}

/**
 * Resolves the API key for embedding operations.
 * Priority: rag_embedding_api_key parameter > explicit OpenAI/OpenRouter params > env vars.
 */
internal fun resolveEmbeddingApiKey(
    parameters: List<ConfigurationParameter>,
    env: Map<String, String> = System.getenv()
): String {
    // 1. Explicit RAG embedding API key
    parameterValue(parameters, "rag_embedding_api_key")?.let {
        return normalizeEmbeddingApiKey(it, baseUrl = resolveEmbeddingBaseUrl(parameters, env))
    }

    // 2. Explicit provider-scoped parameters
    parameterValue(parameters, "openai_api_key")?.let { return it }
    providerKey(parameters, "openai")?.let { return it }
    parameterValue(parameters, "openrouter_api_key")?.let { return normalizeOpenRouterEmbeddingApiKey(it) }
    providerKey(parameters, "openrouter")?.let { return normalizeOpenRouterEmbeddingApiKey(it) }
    providerKey(parameters, "open_router")?.let { return normalizeOpenRouterEmbeddingApiKey(it) }

    // 3. Provider env vars, ordered by the selected-compatible base URL.
    val prefersOpenRouter = resolveEmbeddingBaseUrl(parameters, env).contains("openrouter.ai")
    val envVarOrder = if (prefersOpenRouter) {
        listOf("OPENROUTER_API_KEY", "OPEN_ROUTER_API_KEY", "OPENAI_API_KEY")
    } else {
        listOf("OPENAI_API_KEY", "OPENROUTER_API_KEY", "OPEN_ROUTER_API_KEY")
    }
    envVarOrder.firstNotNullOfOrNull { name ->
        env[name]?.trim()?.takeIf { it.isNotEmpty() }
    }?.let { return normalizeEmbeddingApiKey(it, baseUrl = resolveEmbeddingBaseUrl(parameters, env)) }

    logger.warn {
        "No API key found for RAG embedding. Set rag_embedding_api_key, openai_api_key, " +
            "openrouter_api_key, or matching environment variables."
    }
    return ""
}

internal fun resolveEmbeddingBaseUrl(
    parameters: List<ConfigurationParameter>,
    env: Map<String, String> = System.getenv()
): String {
    parameterValue(parameters, "rag_embedding_base_url")?.let { return normalizeEmbeddingBaseUrl(it) }

    val openRouterBaseUrl = parameterValue(parameters, "openrouter_base_url")?.let(::normalizeEmbeddingBaseUrl)
    if (openRouterBaseUrl != null || hasOpenRouterEmbeddingCredentials(parameters, env)) {
        return openRouterBaseUrl ?: DEFAULT_OPENROUTER_EMBEDBRAIDRUN_BASE_URL
    }

    return DEFAULT_OPENAI_EMBEDBRAIDRUN_BASE_URL
}

private fun hasOpenRouterEmbeddingCredentials(
    parameters: List<ConfigurationParameter>,
    env: Map<String, String>
): Boolean {
    return parameterValue(parameters, "openrouter_api_key") != null ||
        providerKey(parameters, "openrouter") != null ||
        providerKey(parameters, "open_router") != null ||
        !env["OPENROUTER_API_KEY"].isNullOrBlank() ||
        !env["OPEN_ROUTER_API_KEY"].isNullOrBlank()
}

private fun normalizeEmbeddingBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return DEFAULT_OPENAI_EMBEDBRAIDRUN_BASE_URL
    return if (trimmed.contains("openrouter.ai") && !trimmed.endsWith("/api/v1")) {
        "$trimmed/api/v1"
    } else {
        trimmed
    }
}

private fun parameterValue(parameters: List<ConfigurationParameter>, key: String): String? {
    return parameters.firstOrNull { it.key == key }
        ?.value
        ?.jsonScalarContent()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun providerKey(parameters: List<ConfigurationParameter>, provider: String): String? {
    val keys = try {
        parameters.parameter("llm_provider_keys", mapOf<String, String>())
    } catch (e: Exception) {
        logger.warn(e) { "Failed to decode llm_provider_keys while resolving RAG embedding credentials" }
        emptyMap()
    }
    return keys[provider]?.trim()?.takeIf { it.isNotEmpty() }
}

private fun normalizeEmbeddingApiKey(apiKey: String, baseUrl: String): String {
    return if (baseUrl.contains("openrouter.ai")) {
        normalizeOpenRouterEmbeddingApiKey(apiKey)
    } else {
        apiKey.trim()
    }
}

private fun normalizeOpenRouterEmbeddingApiKey(apiKey: String): String {
    val trimmed = apiKey.trim()
    return if (trimmed.startsWith("k-or-v1-")) {
        "s$trimmed"
    } else {
        trimmed
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonScalarContent(): String? = when (this) {
    is kotlinx.serialization.json.JsonPrimitive -> content
    else -> null
}

// ============================================================================
// RAGTools - LLM-callable tools for document indexing and semantic retrieval
// ============================================================================

@LLMDescription(
    "RAG (Retrieval-Augmented Generation) tools for document indexing and semantic search. " +
            "Use these tools to index documents, files, or text into a knowledge base, " +
            "then semantically search for relevant information. " +
            "Indexed data persists across sessions."
)
class RAGTools(
    private val parameters: List<ConfigurationParameter>,
    embedderFactory: EmbedderFactory = EmbedderFactory.Default,
    baseClient: HttpClient? = null
) : ToolSet {

    private val storageDir: File by lazy {
        val dir = validateStorageDirectory(parameters.parameter("rag_storage_dir", ".rag-index"))
        dir.mkdirs()
        dir
    }

    private val indexFile: File get() = File(storageDir, "rag-index.json")
    private val vectorsDir: File get() = File(storageDir, "vectors").also { it.mkdirs() }

    private val maxChunkSize: Int = positiveIntParameter("rag_chunk_size", 1000).coerceAtMost(MAX_CHUNK_SIZE)
    private val maxChunkOverlap: Int =
        positiveIntParameter("rag_chunk_overlap", 200, allowZero = true).coerceIn(0, maxChunkSize - 1)
    private val defaultTopK: Int = positiveIntParameter("rag_default_top_k", 5).coerceAtMost(MAX_TOP_K)
    private val maxDocumentBytes: Long = positiveLongParameter("rag_max_document_bytes", DEFAULT_MAX_DOCUMENT_BYTES)
        .coerceAtMost(MAX_DOCUMENT_BYTES)
    private val maxDirectoryFiles: Int = positiveIntParameter("rag_max_directory_files", DEFAULT_MAX_DIRECTORY_FILES)
        .coerceAtMost(MAX_DIRECTORY_FILES)
    private val embeddingParallelism: Int = parameters.parameter("rag_embedding_parallelism", "4")
        .toIntOrNull()
        ?.coerceIn(1, 8)
        ?: 4

    private val embedder: Embedder = embedderFactory.create(parameters, baseClient)
    private val mutex = Mutex()

    // In-memory vector cache: chunkId -> Vector.
    //
    // Phase 11 hardening (2026-05-14): switched from `mutableMapOf` (HashMap) to
    // ConcurrentHashMap. The cache is hit from `loadVector` / `saveVector` /
    // `deleteVector` / `clearKnowledgeBaseSuspend`, several of which run inside
    // `embedAndStoreChunk` paths that are explicitly fanned out across
    // [embeddingParallelism] coroutines. Concurrent writes to an unsynchronized
    // HashMap can corrupt the internal table (infinite loop on `get`) — that
    // class of bug has been observed in production logs.
    private val vectorCache: java.util.concurrent.ConcurrentHashMap<String, Vector> =
        java.util.concurrent.ConcurrentHashMap()
    @Volatile
    private var indexLoaded = false

    private val strictSandbox: Boolean by lazy {
        parameters.parameter("sandbox_strict", "false").equals("true", ignoreCase = true)
    }

    private val readGuard: FileReadGuard by lazy {
        if (strictSandbox) FileReadGuard(enabled = true) else FileReadGuard.DISABLED
    }

    private val normalizedAllowedDirs: List<File> by lazy {
        buildAllowedDirectories()
    }

    private val defaultPathBase: File by lazy {
        normalizedAllowedDirs.firstOrNull() ?: File(System.getProperty("user.dir")).canonicalFile
    }

    // ==================== Internal helpers ====================

    private fun positiveIntParameter(name: String, default: Int, allowZero: Boolean = false): Int {
        val parsed = parameters.parameter(name, default.toString()).toIntOrNull() ?: return default
        return when {
            parsed > 0 -> parsed
            allowZero && parsed == 0 -> 0
            else -> default
        }
    }

    private fun positiveLongParameter(name: String, default: Long): Long {
        val parsed = parameters.parameter(name, default.toString()).toLongOrNull() ?: return default
        return parsed.takeIf { it > 0 } ?: default
    }

    private fun buildAllowedDirectories(): List<File> {
        val workingDir = parameters.parameter("working_dir", "")
        val outputDir = parameters.parameter("output_dir", "")
        val dirs = mutableListOf<File>()

        if (workingDir.isBlank()) {
            if (strictSandbox) {
                throw IllegalStateException(
                    "[RAGTools] sandbox_strict=true requires `working_dir`; refusing unsandboxed RAG file access"
                )
            }
            dirs += File(System.getProperty("user.dir"))
        } else {
            dirs += File(workingDir)
        }

        if (outputDir.isNotBlank()) {
            dirs += File(outputDir)
        }
        dirs += File(System.getProperty("java.io.tmpdir", "/tmp"))

        return dirs.map { it.canonicalFile }.distinctBy { it.path }
    }

    private fun resolvePath(rawPath: String): File {
        require(rawPath.isNotBlank()) { "Path cannot be blank" }
        val file = File(rawPath)
        return if (file.isAbsolute) file.canonicalFile else File(defaultPathBase, rawPath).canonicalFile
    }

    private fun isWithinAllowedDirectories(candidate: File): Boolean {
        val candidatePath = candidate.canonicalFile.toPath()
        return normalizedAllowedDirs.any { allowedDir ->
            val allowedPath = allowedDir.toPath()
            candidatePath == allowedPath || candidatePath.startsWith(allowedPath)
        }
    }

    private fun requireAllowedPath(candidate: File, operation: String): File {
        val canonical = candidate.canonicalFile
        if (!isWithinAllowedDirectories(canonical)) {
            val allowed = normalizedAllowedDirs.joinToString(", ") { it.path }
            throw SecurityException("$operation path '${canonical.path}' is outside allowed directories: $allowed")
        }
        return canonical
    }

    private fun validateStorageDirectory(rawPath: String): File {
        val dir = requireAllowedPath(resolvePath(rawPath), "RAG storage")
        if (dir.exists() && !dir.isDirectory) {
            throw IllegalArgumentException("RAG storage path is not a directory: ${dir.path}")
        }
        return dir
    }

    private fun validateReadableFilePath(rawPath: String): File {
        val file = requireAllowedPath(resolvePath(rawPath), "RAG source")
        if (!file.isFile) throw IllegalArgumentException("Path is not a file: $rawPath")
        readGuard.validateReadFile(file)
        return file
    }

    private fun validateReadableDirectoryPath(rawPath: String): File {
        val dir = requireAllowedPath(resolvePath(rawPath), "RAG source directory")
        if (!dir.isDirectory) throw IllegalArgumentException("Path is not a directory: $rawPath")
        return dir
    }

    private fun validateDocumentContent(content: String) {
        val byteCount = content.toByteArray(StandardCharsets.UTF_8).size.toLong()
        require(byteCount <= maxDocumentBytes) {
            "Document content (${byteCount} bytes) exceeds rag_max_document_bytes=$maxDocumentBytes"
        }
    }

    private fun loadIndex(): RAGIndex {
        return if (indexFile.exists()) {
            try {
                jsonFormat.decodeFromString<RAGIndex>(indexFile.readText())
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load RAG index, starting fresh" }
                RAGIndex()
            }
        } else {
            RAGIndex()
        }
    }

    private fun saveIndex(index: RAGIndex) {
        indexFile.writeText(jsonFormat.encodeToString(index))
    }

    fun getStats(): RAGKnowledgeBaseStats {
        val index = loadIndex()
        return RAGKnowledgeBaseStats(
            documentCount = index.documents.size,
            chunkCount = index.chunks.size
        )
    }

    fun listDocumentSummaries(): List<RAGDocumentSummary> {
        val index = loadIndex()
        return index.documents.values
            .sortedBy { it.id }
            .map { doc ->
                RAGDocumentSummary(
                    id = doc.id,
                    tags = doc.tags,
                    sourceFile = doc.sourceFile,
                    totalChunks = doc.totalChunks,
                    indexedAt = doc.indexedAt
                )
            }
    }

    private fun safeVectorFile(chunkId: String): File {
        val sanitized = chunkId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(vectorsDir, "$sanitized.vec").canonicalFile
        val vectorsDirCanonical = vectorsDir.canonicalFile
        require(file.toPath().startsWith(vectorsDirCanonical.toPath())) {
            "Path traversal detected in chunkId: $chunkId"
        }
        return file
    }

    private fun loadVector(chunkId: String): Vector? {
        vectorCache[chunkId]?.let { return it }
        val file = safeVectorFile(chunkId)
        if (!file.exists()) return null
        return try {
            val doubles = jsonFormat.decodeFromString<List<Double>>(file.readText())
            Vector(doubles).also { vectorCache[chunkId] = it }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load vector for chunk $chunkId" }
            null
        }
    }

    private fun saveVector(chunkId: String, vector: Vector) {
        vectorCache[chunkId] = vector
        val file = safeVectorFile(chunkId)
        file.writeText(jsonFormat.encodeToString(vector.values))
    }

    private fun deleteVector(chunkId: String) {
        vectorCache.remove(chunkId)
        val file = safeVectorFile(chunkId)
        if (file.exists()) file.delete()
    }

    /**
     * Split text into chunks with overlap for better retrieval quality.
     */
    internal fun splitIntoChunks(text: String, chunkSize: Int = maxChunkSize, overlap: Int = maxChunkOverlap): List<String> {
        val safeChunkSize = chunkSize.coerceIn(1, MAX_CHUNK_SIZE)
        if (text.length <= safeChunkSize) return listOf(text)

        // Ensure overlap is less than chunkSize to guarantee forward progress
        val effectiveOverlap = overlap.coerceIn(0, safeChunkSize - 1)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + safeChunkSize, text.length)
            chunks.add(text.substring(start, end))
            start += safeChunkSize - effectiveOverlap
            if (start >= text.length) break
        }
        return chunks
    }

    private fun generateId(): String = java.util.UUID.randomUUID().toString().take(12)

    private fun now(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())

    private suspend fun embedChunks(chunkTexts: List<String>): List<Vector> {
        if (chunkTexts.isEmpty()) return emptyList()
        if (embeddingParallelism <= 1 || chunkTexts.size == 1) {
            return chunkTexts.map { chunkText -> embedder.embed(chunkText) }
        }

        val semaphore = Semaphore(embeddingParallelism)
        return coroutineScope {
            chunkTexts.map { chunkText ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        embedder.embed(chunkText)
                    }
                }
            }.awaitAll()
        }
    }

    private fun removeExistingDocument(index: RAGIndex, documentId: String) {
        val existing = index.documents.remove(documentId) ?: return
        existing.chunkIds.forEach { chunkId ->
            index.chunks.remove(chunkId)
            deleteVector(chunkId)
        }
    }

    private suspend fun upsertDocument(
        index: RAGIndex,
        documentId: String,
        content: String,
        tagList: List<String>,
        sourceFile: String?
    ): Int {
        removeExistingDocument(index, documentId)

        val chunks = splitIntoChunks(content)
        val vectors = embedChunks(chunks)
        val chunkIds = mutableListOf<String>()

        for ((i, chunkText) in chunks.withIndex()) {
            val chunkId = "${documentId}_chunk_$i"
            saveVector(chunkId, vectors[i])

            val record = ChunkRecord(
                id = chunkId,
                documentId = documentId,
                content = chunkText,
                tags = tagList,
                chunkIndex = i,
                sourceFile = sourceFile
            )
            index.chunks[chunkId] = record
            chunkIds.add(chunkId)
        }

        index.documents[documentId] = DocumentRecord(
            id = documentId,
            tags = tagList,
            chunkIds = chunkIds,
            sourceFile = sourceFile,
            totalChunks = chunks.size,
            indexedAt = now()
        )

        return chunks.size
    }

    // ==================== LLM Tools ====================

    @Tool
    @LLMDescription(
        "Index a text document into the RAG knowledge base for later semantic retrieval. " +
                "The document will be split into chunks and embedded as vectors. " +
                "Use a unique documentId to identify the document."
    )
    fun indexDocument(
        @LLMDescription("Unique identifier for this document (e.g., 'project-spec', 'meeting-notes-0322')")
        documentId: String,
        @LLMDescription("The full text content of the document to index")
        content: String,
        @LLMDescription("Optional comma-separated tags for categorization (e.g., 'spec,project,v2')")
        tags: String = ""
    ): String {
        return indexDocumentWithSource(documentId, content, tags, null)
    }

    fun indexDocumentWithSource(
        documentId: String,
        content: String,
        tags: String = "",
        sourceFile: String? = null
    ): String = runBlocking { indexDocumentWithSourceSuspend(documentId, content, tags, sourceFile) }

    /**
     * Suspend variant of [indexDocumentWithSource] — callers in coroutine context should
     * use this instead of the blocking wrapper to avoid parking a Dispatchers.Default
     * worker thread. Introduced in Phase 4; the non-suspend form remains for koog's
     * non-suspend @Tool dispatch and for legacy non-suspend integrations in
     * workflow-web (`AssistantDocsKnowledgeBaseService`).
     */
    suspend fun indexDocumentWithSourceSuspend(
        documentId: String,
        content: String,
        tags: String = "",
        sourceFile: String? = null
    ): String {
        return try {
            validateDocumentContent(content)
            mutex.withLock {
                val index = loadIndex()
                val tagList = if (tags.isNotBlank()) tags.split(",").map { it.trim() } else emptyList()
                val chunkCount = upsertDocument(
                    index = index,
                    documentId = documentId,
                    content = content,
                    tagList = tagList,
                    sourceFile = sourceFile
                )

                saveIndex(index)
                "Successfully indexed document '$documentId': $chunkCount chunk(s) created and embedded."
            }
        } catch (e: Exception) {
            "Error indexing document: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Index a file from the file system into the RAG knowledge base. " +
                "The file content will be read, split into chunks, and embedded as vectors. " +
                "Supports text files (.txt, .md, .kt, .java, .py, .yaml, .json, .xml, .html, .csv, etc.)."
    )
    fun indexFile(
        @LLMDescription("Absolute path to the file to index")
        filePath: String,
        @LLMDescription("Optional comma-separated tags for categorization")
        tags: String = ""
    ): String = runBlocking { indexFileSuspend(filePath, tags) }

    /**
     * Suspend variant of [indexFile]; preferred when the caller is already in a coroutine.
     */
    suspend fun indexFileSuspend(filePath: String, tags: String = ""): String {
        return try {
            val requestedFile = resolvePath(filePath)
            if (!requestedFile.exists()) return "Error: File not found: $filePath"
            val file = validateReadableFilePath(filePath)
            if (file.length() > maxDocumentBytes) return "Error: File too large (>${maxDocumentBytes / 1024 / 1024}MB): $filePath"

            val content = file.readText()
            if (content.isBlank()) return "Error: File is empty: $filePath"

            // Use filename as document ID (sanitized)
            val docId = file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

            mutex.withLock {
                val index = loadIndex()
                val tagList = if (tags.isNotBlank()) tags.split(",").map { it.trim() } else emptyList()
                val chunkCount = upsertDocument(
                    index = index,
                    documentId = docId,
                    content = content,
                    tagList = tagList,
                    sourceFile = file.absolutePath
                )

                saveIndex(index)
                "Successfully indexed file '$filePath' as '$docId': $chunkCount chunk(s) created."
            }
        } catch (e: Exception) {
            "Error indexing file: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Index all files in a directory into the RAG knowledge base. " +
                "Recursively scans the directory for text files and indexes each one. " +
                "Useful for bulk-indexing a documentation folder or codebase."
    )
    fun indexDirectory(
        @LLMDescription("Absolute path to the directory to index")
        directoryPath: String,
        @LLMDescription("Optional comma-separated file extensions to include (e.g., 'md,txt,kt'). If empty, indexes common text files.")
        extensions: String = "",
        @LLMDescription("Optional comma-separated tags for all indexed files")
        tags: String = ""
    ): String {
        return try {
            val requestedDir = resolvePath(directoryPath)
            if (!requestedDir.exists()) return "Error: Directory not found: $directoryPath"
            val dir = validateReadableDirectoryPath(directoryPath)

            val defaultExtensions = setOf("txt", "md", "kt", "java", "py", "yaml", "yml", "json", "xml", "html", "css", "js", "ts", "csv", "sql", "sh", "gradle", "properties", "cfg", "conf", "ini", "toml")
            val allowedExtensions = if (extensions.isNotBlank()) {
                extensions.split(",").map { it.trim().lowercase().removePrefix(".") }.toSet()
            } else {
                defaultExtensions
            }

            val files = dir.walkTopDown()
                .filter { it.isFile }
                .filter { it.extension.lowercase() in allowedExtensions }
                .filter { it.length() <= maxDocumentBytes }
                .mapNotNull { candidate ->
                    runCatching { validateReadableFilePath(candidate.absolutePath) }
                        .onFailure { logger.warn(it) { "Skipping unreadable RAG source file: ${candidate.absolutePath}" } }
                        .getOrNull()
                }
                .take(maxDirectoryFiles + 1)
                .toList()

            if (files.size > maxDirectoryFiles) {
                return "Error: Too many matching files in '$directoryPath' (limit: $maxDirectoryFiles). " +
                    "Narrow the extension filter or raise `rag_max_directory_files`."
            }

            if (files.isEmpty()) return "No matching files found in '$directoryPath' with extensions: ${allowedExtensions.joinToString(", ")}"

            val results = mutableListOf<String>()
            var totalChunks = 0
            for (file in files) {
                val result = indexFile(file.absolutePath, tags)
                results.add(result)
                if (result.contains("chunk(s) created")) {
                    val chunkCount = Regex("(\\d+) chunk").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    totalChunks += chunkCount
                }
            }

            "Indexed ${files.size} file(s) from '$directoryPath' with $totalChunks total chunks.\n" +
                    results.joinToString("\n")
        } catch (e: Exception) {
            "Error indexing directory: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Semantically search the RAG knowledge base. Returns the most relevant document chunks " +
                "based on vector similarity to your query. Use natural language queries for best results."
    )
    fun searchKnowledgeEntries(query: String, topK: Int = defaultTopK): List<RAGSearchResult> =
        runBlocking { searchKnowledgeEntriesSuspend(query, topK) }

    /**
     * Suspend variant of [searchKnowledgeEntries]. Callers in coroutine context
     * should prefer this over the blocking form so they don't park a
     * Dispatchers.Default worker thread on every retrieval round-trip.
     * Introduced in Phase 4; `AssistantDocsKnowledgeBaseService.retrieveContext`
     * migrated to this variant in the Phase 5 follow-up.
     */
    suspend fun searchKnowledgeEntriesSuspend(query: String, topK: Int = defaultTopK): List<RAGSearchResult> {
        val k = if (topK <= 0) defaultTopK else topK
        return mutex.withLock {
            val index = loadIndex()
            if (index.chunks.isEmpty()) {
                return@withLock emptyList()
            }

            val queryVector = embedder.embed(query)
            index.chunks.values
                .mapNotNull { chunk ->
                    val chunkVector = loadVector(chunk.id) ?: return@mapNotNull null
                    val similarity = queryVector.cosineSimilarity(chunkVector)
                    RAGSearchResult(
                        documentId = chunk.documentId,
                        sourceFile = chunk.sourceFile,
                        tags = chunk.tags,
                        chunkIndex = chunk.chunkIndex,
                        content = chunk.content,
                        similarity = similarity
                    )
                }
                .sortedByDescending { it.similarity }
                .take(k)
        }
    }

    @Tool
    @LLMDescription(
        "Semantically search the RAG knowledge base. Returns the most relevant document chunks " +
                "based on vector similarity to your query. Use natural language queries for best results."
    )
    fun searchKnowledge(
        @LLMDescription("Natural language search query (e.g., 'How does authentication work?' or 'database migration steps')")
        query: String,
        @LLMDescription("Number of results to return (default: 5)")
        topK: String = ""
    ): String {
        return try {
            val k = topK.toIntOrNull() ?: defaultTopK
            val stats = getStats()
            if (stats.chunkCount == 0) {
                return "Knowledge base is empty. Use indexDocument or indexFile to add content first."
            }
            val scored = searchKnowledgeEntries(query, k)
            if (scored.isEmpty()) {
                return "No results found for query: '$query'"
            }

            val sb = StringBuilder()
            sb.appendLine("Found ${scored.size} relevant result(s) for: \"$query\"\n")
            for ((i, result) in scored.withIndex()) {
                sb.appendLine("--- Result ${i + 1} (similarity: ${"%.4f".format(result.similarity)}) ---")
                sb.appendLine("Document: ${result.documentId}")
                if (result.sourceFile != null) sb.appendLine("Source: ${result.sourceFile}")
                if (result.tags.isNotEmpty()) sb.appendLine("Tags: ${result.tags.joinToString(", ")}")
                sb.appendLine("Content:")
                sb.appendLine(result.content)
                sb.appendLine()
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "Error searching knowledge base: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "List all documents currently indexed in the RAG knowledge base. " +
                "Shows document IDs, chunk counts, tags, and source files."
    )
    fun listDocuments(): String {
        return try {
            val stats = getStats()
            val documents = listDocumentSummaries()
            if (documents.isEmpty()) {
                return "Knowledge base is empty. No documents indexed yet."
            }

            val sb = StringBuilder()
            sb.appendLine("RAG Knowledge Base: ${stats.documentCount} document(s), ${stats.chunkCount} total chunk(s)\n")
            for (doc in documents) {
                sb.appendLine("• ${doc.id}")
                sb.appendLine("  Chunks: ${doc.totalChunks}")
                if (doc.tags.isNotEmpty()) sb.appendLine("  Tags: ${doc.tags.joinToString(", ")}")
                if (doc.sourceFile != null) sb.appendLine("  Source: ${doc.sourceFile}")
                if (doc.indexedAt.isNotBlank()) sb.appendLine("  Indexed: ${doc.indexedAt}")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "Error listing documents: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Delete a document from the RAG knowledge base. " +
                "This removes the document and all its indexed chunks and vectors."
    )
    fun deleteDocument(
        @LLMDescription("ID of the document to delete (use listDocuments to see available IDs)")
        documentId: String
    ): String = runBlocking { deleteDocumentSuspend(documentId) }

    /**
     * Suspend variant of [deleteDocument]. Callers in coroutine context must
     * use this form; the blocking wrapper exists purely for koog's non-suspend
     * `@Tool` dispatcher.
     */
    suspend fun deleteDocumentSuspend(documentId: String): String {
        return try {
            mutex.withLock {
                val index = loadIndex()
                val doc = index.documents[documentId]
                    ?: return@withLock "Document '$documentId' not found in knowledge base."

                // Remove all chunks and vectors
                doc.chunkIds.forEach { chunkId ->
                    index.chunks.remove(chunkId)
                    deleteVector(chunkId)
                }
                index.documents.remove(documentId)

                saveIndex(index)
                "Successfully deleted document '$documentId' and its ${doc.totalChunks} chunk(s)."
            }
        } catch (e: Exception) {
            "Error deleting document: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Get detailed information about a specific document in the knowledge base, " +
                "including its chunks and metadata."
    )
    fun getDocumentInfo(
        @LLMDescription("ID of the document to inspect")
        documentId: String
    ): String {
        return try {
            val index = loadIndex()
            val doc = index.documents[documentId]
                ?: return "Document '$documentId' not found in knowledge base."

            val sb = StringBuilder()
            sb.appendLine("Document: ${doc.id}")
            sb.appendLine("Total Chunks: ${doc.totalChunks}")
            if (doc.tags.isNotEmpty()) sb.appendLine("Tags: ${doc.tags.joinToString(", ")}")
            if (doc.sourceFile != null) sb.appendLine("Source File: ${doc.sourceFile}")
            if (doc.indexedAt.isNotBlank()) sb.appendLine("Indexed At: ${doc.indexedAt}")
            sb.appendLine("\nChunks:")

            for (chunkId in doc.chunkIds) {
                val chunk = index.chunks[chunkId]
                if (chunk != null) {
                    val preview = if (chunk.content.length > 100) chunk.content.take(100) + "..." else chunk.content
                    sb.appendLine("  [${chunk.chunkIndex}] $preview")
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "Error getting document info: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Clear all documents and vectors from the RAG knowledge base. " +
                "This permanently removes all indexed content. Use with caution."
    )
    fun clearKnowledgeBase(): String = runBlocking { clearKnowledgeBaseSuspend() }

    /**
     * Suspend variant of [clearKnowledgeBase]. Wipes every document and vector
     * under the configured storage dir. Destructive — intended for admin-facing
     * rebuild flows.
     */
    suspend fun clearKnowledgeBaseSuspend(): String {
        return try {
            mutex.withLock {
                val index = loadIndex()
                val docCount = index.documents.size
                val chunkCount = index.chunks.size

                // Delete all vector files
                vectorsDir.listFiles()?.forEach { it.delete() }

                // Clear index
                vectorCache.clear()
                saveIndex(RAGIndex())

                "Knowledge base cleared. Removed $docCount document(s) and $chunkCount chunk(s)."
            }
        } catch (e: Exception) {
            "Error clearing knowledge base: ${e.message}"
        }
    }

    companion object {
        private const val DEFAULT_MAX_DOCUMENT_BYTES: Long = 10L * 1024L * 1024L
        private const val MAX_DOCUMENT_BYTES: Long = 100L * 1024L * 1024L
        private const val DEFAULT_MAX_DIRECTORY_FILES: Int = 1000
        private const val MAX_DIRECTORY_FILES: Int = 10_000
        private const val MAX_CHUNK_SIZE: Int = 100_000
        private const val MAX_TOP_K: Int = 100

        /**
         * Factory method to create RAGTools from ConfigurationParameter list.
         * Suitable for integration into AgentCommon's parseToolSet.
         */
        fun createFromParameters(
            parameters: List<ConfigurationParameter>,
            embedderFactory: EmbedderFactory = EmbedderFactory.Default,
            baseClient: HttpClient? = null
        ): RAGTools = RAGTools(parameters, embedderFactory, baseClient)
    }
}
