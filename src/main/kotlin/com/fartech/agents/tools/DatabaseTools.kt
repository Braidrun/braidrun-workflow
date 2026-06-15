package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

@LLMDescription(
    "Database tools for querying SQLite databases and any JDBC-compatible database " +
            "(MySQL, PostgreSQL, etc.). Supports executing queries, listing tables, describing schemas, " +
            "and performing basic CRUD operations."
)
object DatabaseTools : ToolSet {

    private const val MAX_ROWS = 1000
    private const val MAX_RESULT_LENGTH = 100_000

    /**
     * SQL identifier whitelist. Table names are never user-input in safe designs, but these
     * tools accept table names from agents, so we enforce a conservative syntax that rules
     * out quote-escape / comment / statement-terminator attacks before the name is ever
     * interpolated into a PRAGMA or FROM clause.
     */
    private val SAFE_SQL_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")

    private fun requireSafeIdentifier(identifier: String, kind: String = "identifier"): String {
        require(SAFE_SQL_IDENTIFIER.matches(identifier)) {
            "Invalid $kind '$identifier'; must match ${SAFE_SQL_IDENTIFIER.pattern}"
        }
        return identifier
    }

    /**
     * Default JDBC login timeout in seconds. Without this the driver will inherit the JVM
     * default of "wait forever", which turned a misconfigured connection string into a
     * thread-pool starvation event in practice.
     */
    private const val CONNECT_TIMEOUT_SECONDS = 10
    private const val SOCKET_TIMEOUT_SECONDS = 60

    private fun getConnection(connectionString: String, username: String, password: String): Connection {
        // DriverManager.setLoginTimeout is JVM-global (not per-call), but setting it here
        // still bounds the worst case. Per-driver socket timeouts below are layered on top.
        DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS)
        return if (username.isNotBlank()) {
            DriverManager.getConnection(connectionString, username, password)
        } else {
            DriverManager.getConnection(connectionString)
        }.also { conn ->
            // Network-read timeout so a hung server doesn't park the calling thread
            // indefinitely. setNetworkTimeout signature is (Executor, millis); we use a
            // direct-executor so the driver doesn't need a pool of its own.
            runCatching {
                conn.setNetworkTimeout({ it.run() }, SOCKET_TIMEOUT_SECONDS * 1000)
            }
        }
    }

    // Whether the LLM-provided SQL is a pure read statement. Used to gate dangerous
    // operations when the caller sets `database_readonly=true`. Deliberately conservative —
    // anything we can't positively identify as SELECT/SHOW/DESCRIBE/EXPLAIN/WITH/PRAGMA is
    // treated as mutating and rejected under read-only mode.
    //
    // Strips both line and block comments before inspecting the leading keyword — a motivated
    // LLM can prepend a SQL block comment hiding a DROP TABLE statement. Additionally rejects
    // any statement-separator (`;` outside of quoted literals followed by non-whitespace) so
    // `SELECT 1; DROP TABLE` is caught even on drivers that accept multi-statement queries.
    internal fun isReadOnlySql(sql: String): Boolean {
        val stripped = stripSqlComments(sql).trim()
        if (stripped.isEmpty()) return false
        // Reject multi-statement SQL outright in read-only mode. Most drivers
        // won't execute the trailing statements, but some (MySQL with
        // `allowMultiQueries=true`, SQLite `Statement.execute`) will, and
        // the first-statement check below would approve `SELECT 1; DROP…`.
        if (containsStatementSeparator(stripped)) return false
        val head = stripped.uppercase()
        return head.startsWith("SELECT") ||
            head.startsWith("SHOW") ||
            head.startsWith("DESCRIBE") ||
            head.startsWith("DESC ") ||
            head.startsWith("EXPLAIN") ||
            head.startsWith("PRAGMA") ||
            head.startsWith("WITH ")
    }

    private fun stripSqlComments(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            val next = if (i + 1 < n) sql[i + 1] else ' '
            when {
                // String literal — copy through verbatim. Comment markers inside a
                // string are NOT comments. Without this branch, an attacker could
                // smuggle a destructive payload via `SELECT '/*' UNION ALL SELECT 1;
                // DROP TABLE x` — the unmatched `/*` inside the literal would cause
                // the comment-stripper to swallow everything after it (including the
                // `;` that `containsStatementSeparator` relies on), leaving a bare
                // `SELECT '` that passes the read-only check while the driver runs
                // the full multi-statement SQL.
                c == '\'' || c == '"' -> {
                    val quote = c
                    out.append(c); i++
                    while (i < n) {
                        val cc = sql[i]
                        // SQL escape is doubled quote (`''` or `""`); keep the pair
                        // intact so the closing-quote search doesn't terminate early.
                        if (cc == quote && i + 1 < n && sql[i + 1] == quote) {
                            out.append(cc); out.append(sql[i + 1]); i += 2
                        } else if (cc == quote) {
                            out.append(cc); i++; break
                        } else {
                            out.append(cc); i++
                        }
                    }
                }
                c == '-' && next == '-' -> {
                    // Line comment — skip to newline.
                    i += 2
                    while (i < n && sql[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    // Block comment — skip to closing `*/`. Nested blocks
                    // aren't SQL-standard but handle them defensively.
                    i += 2
                    var depth = 1
                    while (i < n && depth > 0) {
                        val cc = sql[i]
                        val nn = if (i + 1 < n) sql[i + 1] else ' '
                        if (cc == '/' && nn == '*') {
                            depth++; i += 2
                        } else if (cc == '*' && nn == '/') {
                            depth--; i += 2
                        } else {
                            i++
                        }
                    }
                }
                else -> {
                    out.append(c); i++
                }
            }
        }
        return out.toString()
    }

    /** True if `sql` (already comment-stripped) holds a `;` outside string literals with non-blank content after it. */
    private fun containsStatementSeparator(sql: String): Boolean {
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            when (c) {
                '\'', '"' -> {
                    val quote = c
                    i++
                    while (i < n && sql[i] != quote) {
                        // SQL escape is doubled quote (`''`); skip the pair.
                        if (sql[i] == quote && i + 1 < n && sql[i + 1] == quote) i += 2 else i++
                    }
                    if (i < n) i++
                }
                ';' -> {
                    // Trailing `;` with only whitespace after it is fine.
                    if (sql.substring(i + 1).any { !it.isWhitespace() }) return true
                    i++
                }
                else -> i++
            }
        }
        return false
    }

    /**
     * Check the read-only gate. Returns a non-null error message when the SQL should be
     * rejected, or `null` to allow it.
     */
    private fun readOnlyViolation(sql: String): String? {
        val readonly = System.getProperty("BRAIDRUN_DB_READONLY")?.equals("true", ignoreCase = true) == true ||
            System.getenv("BRAIDRUN_DB_READONLY")?.equals("true", ignoreCase = true) == true
        if (!readonly) return null
        if (isReadOnlySql(sql)) return null
        return "Error: BRAIDRUN_DB_READONLY=true rejects non-SELECT/SHOW/DESCRIBE/EXPLAIN/WITH/PRAGMA statements. " +
            "Remove the flag or switch to a read-only query."
    }

    private fun resultSetToString(rs: ResultSet, maxRows: Int = MAX_ROWS): String {
        val meta = rs.metaData
        val colCount = meta.columnCount
        val headers = (1..colCount).map { meta.getColumnLabel(it) }
        val rows = mutableListOf<List<String>>()

        var rowCount = 0
        while (rs.next() && rowCount < maxRows) {
            val row = (1..colCount).map { i ->
                rs.getString(i) ?: "NULL"
            }
            rows.add(row)
            rowCount++
        }

        if (rows.isEmpty()) return "Query returned 0 rows."

        // Calculate column widths
        val widths = headers.indices.map { i ->
            maxOf(headers[i].length, rows.maxOf { it[i].length.coerceAtMost(50) })
        }

        val sb = StringBuilder()
        // Header
        sb.appendLine(headers.mapIndexed { i, h -> h.padEnd(widths[i]) }.joinToString(" | "))
        sb.appendLine(widths.joinToString("-+-") { "-".repeat(it) })
        // Rows
        for (row in rows) {
            sb.appendLine(row.mapIndexed { i, v ->
                val truncated = if (v.length > 50) v.take(47) + "..." else v
                truncated.padEnd(widths[i])
            }.joinToString(" | "))
        }

        val hasMore = rowCount >= maxRows
        sb.appendLine("\n${rows.size} row(s) returned${if (hasMore) " (truncated at $maxRows)" else ""}.")

        val result = sb.toString()
        return if (result.length > MAX_RESULT_LENGTH) {
            result.take(MAX_RESULT_LENGTH) + "\n... [RESULT TRUNCATED]"
        } else {
            result
        }
    }

    @Tool
    @LLMDescription(
        "Execute a SQL query on a SQLite database file. For SELECT queries, returns the result rows. " +
                "For INSERT/UPDATE/DELETE, returns the number of affected rows. " +
                "The SQLite JDBC driver is built into this tool."
    )
    fun querySQLite(
        @LLMDescription("Path to the SQLite database file (e.g., 'data.db', '/path/to/database.sqlite')")
        databasePath: String,
        @LLMDescription("SQL query to execute (SELECT, INSERT, UPDATE, DELETE, CREATE TABLE, etc.)")
        sql: String,
        @LLMDescription("Maximum number of rows to return for SELECT queries (default 1000)")
        maxRows: Int = MAX_ROWS
    ): String {
        return try {
            readOnlyViolation(sql)?.let { return it }
            Class.forName("org.sqlite.JDBC")
            val connStr = "jdbc:sqlite:$databasePath"
            printlnColor(AnsiColor.MAGENTA, "[SQLite] $sql")

            getConnection(connStr, "", "").use { conn ->
                conn.createStatement().use { stmt ->
                    // execute() + resultSet beats keyword sniffing: the old
                    // SELECT/PRAGMA/EXPLAIN prefix check misrouted `WITH ... SELECT`
                    // (CTE reads) to executeUpdate, silently discarding the rows.
                    val hasResultSet = stmt.execute(sql)
                    if (hasResultSet) {
                        stmt.resultSet.use { rs ->
                            resultSetToString(rs, maxRows)
                        }
                    } else {
                        "Statement executed successfully. Rows affected: ${stmt.updateCount}"
                    }
                }
            }
        } catch (e: Exception) {
            "Error executing SQLite query: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Execute a SQL query on any JDBC-compatible database (MySQL, PostgreSQL, MariaDB, H2, etc.). " +
                "Requires the appropriate JDBC driver to be on the classpath. " +
                "For SELECT queries, returns the result rows. For INSERT/UPDATE/DELETE, returns affected row count."
    )
    fun queryDatabase(
        @LLMDescription("JDBC connection string (e.g., 'jdbc:mysql://localhost:3306/mydb', 'jdbc:postgresql://host:5432/db')")
        connectionString: String,
        @LLMDescription("SQL query to execute")
        sql: String,
        @LLMDescription("Database username (optional)")
        username: String = "",
        @LLMDescription("Database password (optional)")
        password: String = "",
        @LLMDescription("Maximum number of rows to return for SELECT queries (default 1000)")
        maxRows: Int = MAX_ROWS
    ): String {
        return try {
            readOnlyViolation(sql)?.let { return it }
            printlnColor(AnsiColor.MAGENTA, "[DB] $sql")

            getConnection(connectionString, username, password).use { conn ->
                conn.createStatement().use { stmt ->
                    // execute() + resultSet beats keyword sniffing: the old prefix
                    // check misrouted `WITH ... SELECT` (CTE reads) and `PRAGMA` to
                    // executeUpdate — rows silently discarded on SQLite, exception
                    // on MySQL/Postgres. Data-modifying CTEs (`WITH ... INSERT`)
                    // route correctly too.
                    val hasResultSet = stmt.execute(sql)
                    if (hasResultSet) {
                        stmt.resultSet.use { rs ->
                            resultSetToString(rs, maxRows)
                        }
                    } else {
                        "Statement executed successfully. Rows affected: ${stmt.updateCount}"
                    }
                }
            }
        } catch (e: Exception) {
            "Error executing database query: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "List all tables in a SQLite database file. Returns the table names and their row counts."
    )
    fun listSQLiteTables(
        @LLMDescription("Path to the SQLite database file")
        databasePath: String
    ): String {
        return try {
            Class.forName("org.sqlite.JDBC")
            val connStr = "jdbc:sqlite:$databasePath"

            getConnection(connStr, "", "").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
                    ).use { rs ->
                        val tables = mutableListOf<String>()
                        while (rs.next()) {
                            val tableName = rs.getString("name")
                            // sqlite_master.name is trusted (from the DB itself), but a hostile
                            // schema could contain quote characters that break out of the literal
                            // below. Validate before interpolating.
                            if (!SAFE_SQL_IDENTIFIER.matches(tableName)) {
                                tables.add("$tableName (skipped: unsafe identifier)")
                                continue
                            }
                            conn.createStatement().use { countStmt ->
                                countStmt.executeQuery("SELECT COUNT(*) FROM \"$tableName\"").use { countRs ->
                                    val count = if (countRs.next()) countRs.getInt(1) else 0
                                    tables.add("$tableName ($count rows)")
                                }
                            }
                        }

                        if (tables.isEmpty()) {
                            "No tables found in database: $databasePath"
                        } else {
                            "Tables in $databasePath:\n${tables.joinToString("\n") { "  - $it" }}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            "Error listing tables: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Describe the schema (columns, types, constraints) of a table in a SQLite database."
    )
    fun describeSQLiteTable(
        @LLMDescription("Path to the SQLite database file")
        databasePath: String,
        @LLMDescription("Name of the table to describe")
        tableName: String
    ): String {
        return try {
            requireSafeIdentifier(tableName, "table name")
            Class.forName("org.sqlite.JDBC")
            val connStr = "jdbc:sqlite:$databasePath"

            getConnection(connStr, "", "").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA table_info(\"$tableName\")").use { rs ->
                        val sb = StringBuilder()
                        sb.appendLine("Table: $tableName")
                        sb.appendLine("Columns:")
                        sb.appendLine(
                            "  ${"#".padEnd(4)} ${"Name".padEnd(30)} ${"Type".padEnd(15)} ${"NotNull".padEnd(8)} ${
                                "Default".padEnd(15)
                            } PK"
                        )
                        sb.appendLine("  ${"-".repeat(4)} ${"-".repeat(30)} ${"-".repeat(15)} ${"-".repeat(8)} ${"-".repeat(15)} --")

                        var hasRows = false
                        while (rs.next()) {
                            hasRows = true
                            val cid = rs.getInt("cid")
                            val name = rs.getString("name")
                            val type = rs.getString("type")
                            val notNull = rs.getInt("notnull")
                            val defaultVal = rs.getString("dflt_value") ?: ""
                            val pk = rs.getInt("pk")
                            sb.appendLine(
                                "  ${
                                    cid.toString().padEnd(4)
                                } ${name.padEnd(30)} ${type.padEnd(15)} ${(if (notNull == 1) "YES" else "NO").padEnd(8)} ${
                                    defaultVal.padEnd(15)
                                } ${if (pk > 0) "YES" else ""}"
                            )
                        }

                        if (!hasRows) {
                            "Table '$tableName' not found or has no columns."
                        } else {
                            stmt.executeQuery("PRAGMA index_list(\"$tableName\")").use { idxRs ->
                                val indexes = mutableListOf<String>()
                                while (idxRs.next()) {
                                    indexes.add("${idxRs.getString("name")} (unique=${idxRs.getInt("unique") == 1})")
                                }
                                if (indexes.isNotEmpty()) {
                                    sb.appendLine("\nIndexes:")
                                    indexes.forEach { sb.appendLine("  - $it") }
                                }
                            }
                            sb.toString()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            "Error describing table: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "List all tables in a JDBC database. Works with MySQL, PostgreSQL, and other JDBC-compatible databases."
    )
    fun listDatabaseTables(
        @LLMDescription("JDBC connection string")
        connectionString: String,
        @LLMDescription("Database username (optional)")
        username: String = "",
        @LLMDescription("Database password (optional)")
        password: String = ""
    ): String {
        return try {
            getConnection(connectionString, username, password).use { conn ->
                val meta = conn.metaData
                val rs = meta.getTables(conn.catalog, conn.schema, "%", arrayOf("TABLE"))
                val tables = mutableListOf<String>()
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"))
                }
                if (tables.isEmpty()) {
                    "No tables found."
                } else {
                    "Tables:\n${tables.joinToString("\n") { "  - $it" }}"
                }
            }
        } catch (e: Exception) {
            "Error listing tables: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Describe the schema of a table in a JDBC database. Shows column names, types, and nullability."
    )
    fun describeDatabaseTable(
        @LLMDescription("JDBC connection string")
        connectionString: String,
        @LLMDescription("Name of the table to describe")
        tableName: String,
        @LLMDescription("Database username (optional)")
        username: String = "",
        @LLMDescription("Database password (optional)")
        password: String = ""
    ): String {
        return try {
            getConnection(connectionString, username, password).use { conn ->
                val meta = conn.metaData
                val rs = meta.getColumns(conn.catalog, conn.schema, tableName, "%")

                val sb = StringBuilder()
                sb.appendLine("Table: $tableName")
                sb.appendLine("Columns:")

                var hasRows = false
                while (rs.next()) {
                    hasRows = true
                    val colName = rs.getString("COLUMN_NAME")
                    val typeName = rs.getString("TYPE_NAME")
                    val size = rs.getInt("COLUMN_SIZE")
                    val nullable = rs.getInt("NULLABLE")
                    val defaultVal = rs.getString("COLUMN_DEF") ?: ""
                    sb.appendLine("  - $colName: $typeName($size) ${if (nullable == 0) "NOT NULL" else "NULLABLE"} ${if (defaultVal.isNotEmpty()) "DEFAULT $defaultVal" else ""}")
                }

                if (!hasRows) {
                    "Table '$tableName' not found or has no columns."
                } else {
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            "Error describing table: ${e.message}"
        }
    }
}
