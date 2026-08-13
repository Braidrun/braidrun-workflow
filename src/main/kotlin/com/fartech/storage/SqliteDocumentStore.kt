package com.fartech.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite-backed [DocumentStore] for single-process, local-first applications.
 *
 * Each instance owns one JDBC connection. Access is serialized because SQLite JDBC
 * connections are not safe for concurrent use and the desktop workload is intentionally
 * low-concurrency. WAL mode still lets other processes and diagnostic connections read the
 * database without blocking this store's normal writes.
 */
class SqliteDocumentStore(
    databasePath: Path,
    private val namespace: String
) : DocumentStore {
    private val lock = ReentrantLock()
    private val connection: Connection = openConnection(databasePath)

    init {
        try {
            lock.withLock {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS documents (
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
                        CREATE INDEX IF NOT EXISTS idx_documents_owner_created
                        ON documents(namespace, collection, owner_id, created_at)
                        """.trimIndent()
                    )
                    statement.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_documents_parent
                        ON documents(namespace, collection, parent_id)
                        """.trimIndent()
                    )
                    statement.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_documents_secondary
                        ON documents(namespace, collection, secondary_id)
                        """.trimIndent()
                    )
                    statement.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_documents_status
                        ON documents(namespace, collection, status)
                        """.trimIndent()
                    )
                    // Fencing column (1.1.0). SQLite has no ADD COLUMN IF NOT
                    // EXISTS, so guard the ALTER by inspecting the live schema
                    // — existing desktop databases migrate in place on open.
                    val hasFence = statement.executeQuery("PRAGMA table_info(documents)").use { columns ->
                        generateSequence { if (columns.next()) columns.getString("name") else null }
                            .any { it == "fence" }
                    }
                    if (!hasFence) {
                        statement.execute("ALTER TABLE documents ADD COLUMN fence INTEGER")
                    }
                }
            }
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    override fun put(document: StoredDocument) {
        lock.withLock {
            connection.prepareStatement(
                """
                INSERT INTO documents (
                    id, collection, namespace, owner_id, parent_id, secondary_id,
                    status, created_at, updated_at, payload, fence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(namespace, collection, id) DO UPDATE SET
                    owner_id = excluded.owner_id,
                    parent_id = excluded.parent_id,
                    secondary_id = excluded.secondary_id,
                    status = excluded.status,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    payload = excluded.payload,
                    fence = excluded.fence
                """.trimIndent()
            ).use { statement ->
                statement.bindDocument(document, namespace)
                statement.executeUpdate()
            }
        }
    }

    override fun putFenced(document: StoredDocument, fence: Long): Boolean = lock.withLock {
        // Single-statement atomic check-and-write: the DO UPDATE ... WHERE
        // clause rejects the write when a NEWER fence owns the row (0 rows
        // changed); an absent row takes the plain INSERT arm.
        connection.prepareStatement(
            """
            INSERT INTO documents (
                id, collection, namespace, owner_id, parent_id, secondary_id,
                status, created_at, updated_at, payload, fence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(namespace, collection, id) DO UPDATE SET
                owner_id = excluded.owner_id,
                parent_id = excluded.parent_id,
                secondary_id = excluded.secondary_id,
                status = excluded.status,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                payload = excluded.payload,
                fence = excluded.fence
            WHERE documents.fence IS NULL OR documents.fence <= excluded.fence
            """.trimIndent()
        ).use { statement ->
            statement.bindDocument(document.copy(fence = fence), namespace)
            statement.executeUpdate() > 0
        }
    }

    override fun get(collection: String, id: String): StoredDocument? = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, collection, namespace, owner_id, parent_id, secondary_id,
                   status, created_at, updated_at, payload, fence
            FROM documents
            WHERE namespace = ? AND collection = ? AND id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, collection)
            statement.setString(3, id)
            statement.executeQuery().use { result ->
                if (result.next()) result.toStoredDocument() else null
            }
        }
    }

    override fun delete(collection: String, id: String): Boolean = lock.withLock {
        connection.prepareStatement(
            "DELETE FROM documents WHERE namespace = ? AND collection = ? AND id = ?"
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, collection)
            statement.setString(3, id)
            statement.executeUpdate() > 0
        }
    }

    override fun list(query: DocumentQuery): List<StoredDocument> = lock.withLock {
        val built = buildQuery(query, countOnly = false) ?: return@withLock emptyList()
        connection.prepareStatement(built.sql).use { statement ->
            statement.bind(built.parameters)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.toStoredDocument())
                    }
                }
            }
        }
    }

    override fun count(query: DocumentQuery): Long = lock.withLock {
        val built = buildQuery(query, countOnly = true) ?: return@withLock 0L
        connection.prepareStatement(built.sql).use { statement ->
            statement.bind(built.parameters)
            statement.executeQuery().use { result ->
                check(result.next()) { "SQLite count query returned no row" }
                result.getLong(1)
            }
        }
    }

    override fun close() = lock.withLock {
        connection.close()
    }

    private fun buildQuery(query: DocumentQuery, countOnly: Boolean): BuiltQuery? {
        val ids = query.idsAnyOf?.toList()
        if (ids?.isEmpty() == true) return null

        val parameters = mutableListOf<Any>()
        val sql = StringBuilder(
            when {
                countOnly -> "SELECT COUNT(*) FROM documents"
                query.excludePayload ->
                    """
                    SELECT id, collection, namespace, owner_id, parent_id, secondary_id,
                           status, created_at, updated_at, '' AS payload, fence
                    FROM documents
                    """.trimIndent()
                else ->
                    """
                    SELECT id, collection, namespace, owner_id, parent_id, secondary_id,
                           status, created_at, updated_at, payload, fence
                    FROM documents
                    """.trimIndent()
            }
        )

        sql.append(" WHERE namespace = ? AND collection = ?")
        parameters += namespace
        parameters += query.collection

        fun addStringFilter(column: String, value: String?) {
            value?.let {
                sql.append(" AND ").append(column).append(" = ?")
                parameters += it
            }
        }

        addStringFilter("owner_id", query.ownerId)
        addStringFilter("parent_id", query.parentId)
        addStringFilter("secondary_id", query.secondaryId)
        addStringFilter("status", query.status)
        query.createdAtFrom?.let {
            sql.append(" AND created_at >= ?")
            parameters += it
        }
        ids?.let {
            sql.append(" AND id IN (")
            sql.append(List(it.size) { "?" }.joinToString(", "))
            sql.append(')')
            parameters.addAll(it)
        }

        if (!countOnly) {
            val sortColumn = when (query.sortBy) {
                DocumentSortField.CREATED_AT -> "created_at"
                DocumentSortField.UPDATED_AT -> "updated_at"
                DocumentSortField.ID -> "id"
            }
            sql.append(" ORDER BY ").append(sortColumn)
            sql.append(if (query.descending) " DESC" else " ASC")

            when {
                query.limit != null -> {
                    sql.append(" LIMIT ?")
                    parameters += query.limit
                    if (query.offset > 0) {
                        sql.append(" OFFSET ?")
                        parameters += query.offset
                    }
                }
                query.offset > 0 -> {
                    // SQLite requires LIMIT when OFFSET is present. -1 means no limit.
                    sql.append(" LIMIT -1 OFFSET ?")
                    parameters += query.offset
                }
            }
        }

        return BuiltQuery(sql.toString(), parameters)
    }

    private data class BuiltQuery(
        val sql: String,
        val parameters: List<Any>
    )

    private companion object {
        fun openConnection(databasePath: Path): Connection {
            val absolutePath = databasePath.toAbsolutePath().normalize()
            absolutePath.parent?.let { Files.createDirectories(it) }
            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite:$absolutePath")
            try {
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("PRAGMA busy_timeout=5000")
                    statement.execute("PRAGMA synchronous=NORMAL")
                }
                return connection
            } catch (failure: Throwable) {
                connection.close()
                throw failure
            }
        }

        fun PreparedStatement.bind(parameters: List<Any>) {
            parameters.forEachIndexed { index, value ->
                when (value) {
                    is String -> setString(index + 1, value)
                    is Int -> setInt(index + 1, value)
                    is Long -> setLong(index + 1, value)
                    else -> error("Unsupported SQLite query parameter: ${value::class.qualifiedName}")
                }
            }
        }

        /** Shared 11-column binding for the [put] / [putFenced] upsert statements. */
        fun PreparedStatement.bindDocument(document: StoredDocument, namespace: String) {
            setString(1, document.id)
            setString(2, document.collection)
            setString(3, namespace)
            setString(4, document.ownerId)
            setString(5, document.parentId)
            setString(6, document.secondaryId)
            setString(7, document.status)
            setLong(8, document.createdAt)
            setLong(9, document.updatedAt)
            setString(10, document.payload)
            document.fence?.let { setLong(11, it) } ?: setNull(11, java.sql.Types.INTEGER)
        }

        fun ResultSet.toStoredDocument(): StoredDocument = StoredDocument(
            id = getString("id"),
            collection = getString("collection"),
            namespace = getString("namespace"),
            ownerId = getString("owner_id"),
            parentId = getString("parent_id"),
            secondaryId = getString("secondary_id"),
            status = getString("status"),
            createdAt = getLong("created_at"),
            updatedAt = getLong("updated_at"),
            payload = getString("payload"),
            fence = getLong("fence").let { value -> if (wasNull()) null else value }
        )
    }
}
