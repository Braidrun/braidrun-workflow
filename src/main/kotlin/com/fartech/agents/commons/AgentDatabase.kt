package com.fartech.agents.commons

import com.fartech.ftapp2.EnvironmentConfig
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import org.litote.kmongo.KMongo
import org.litote.kmongo.getCollection
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import java.util.concurrent.ConcurrentHashMap

data class NamedMongoEnvironment(
    val envSuffix: String,
    val defaultDbName: String
)

private val namedMongoEnvironments = mapOf(
    "braidrun" to NamedMongoEnvironment("BRAIDRUN", "braidrunpub"),
    "test" to NamedMongoEnvironment("TEST", "braidrun")
)

object AgentMongoTargets {
    const val IOS_SERVE_DB = "ios_serve"
    const val AGENT_CHAT_COLLECTION = "agent_chat"
    const val PDF_SNAPSHOT_COLLECTION = "pdf_snapshot"
    const val PDF_CHAT_COLLECTION = "pdf_chat"

    const val BRAIDRUN_DB = "braidrun"
    const val AGENT_SNAPSHOTS_COLLECTION = "agent_snapshots"
    const val AGENT_HISTORY_COLLECTION = "agent_history"
}

fun resolveMongoConnection(
    env: String?,
    envVars: Map<String, String> = System.getenv(),
    defaultUrl: String? = null,
    defaultDbName: String = EnvironmentConfig.getDefaultDatabaseName()
): Pair<String, String> {
    val genericUrl = envVars["BRAIDRUN_AGENT_MONGO_URL"]?.takeIf { it.isNotBlank() }
    val genericDbName = envVars["BRAIDRUN_AGENT_MONGO_DB_NAME"]?.takeIf { it.isNotBlank() }
    val normalizedEnv = env?.trim()?.lowercase().orEmpty()
    val namedConfig = namedMongoEnvironments[normalizedEnv]
    fun fallbackUrl() = defaultUrl ?: EnvironmentConfig.getDefaultDatabaseUrl()

    if (namedConfig == null) {
        return (genericUrl ?: fallbackUrl()) to (genericDbName ?: defaultDbName)
    }

    val scopedUrl = envVars["BRAIDRUN_AGENT_MONGO_URL_${namedConfig.envSuffix}"]?.takeIf { it.isNotBlank() }
    val scopedDbName = envVars["BRAIDRUN_AGENT_MONGO_DB_${namedConfig.envSuffix}"]?.takeIf { it.isNotBlank() }

    return (scopedUrl ?: genericUrl ?: fallbackUrl()) to (scopedDbName ?: genericDbName ?: namedConfig.defaultDbName)
}

fun resolveMongoConnection(parameters: List<ConfigurationParameter>): Pair<String, String> {
    val environment = parameters.parameter("env", EnvironmentConfig.getEnvironment())
    val explicitConnectionString = parameters.parameter("mongo_connection_string", "")
        .trim()
        .takeIf { it.isNotBlank() }
    val explicitDbName = parameters.parameter("mongo_db_name", "")
        .trim()
        .takeIf { it.isNotBlank() }
    val (defaultUrl, defaultDbName) = resolveMongoConnection(
        environment,
        defaultUrl = explicitConnectionString
    )
    val connectionString = explicitConnectionString ?: defaultUrl
    val dbName = explicitDbName ?: defaultDbName
    // Fail fast on missing / nonsensical values. The previous implementation just
    // returned whatever strings it found — an empty dbName silently became the default
    // Mongo database name (usually `test`) and operations would land there without any
    // warning to the caller. A blank connection string, similarly, would propagate and
    // produce an opaque driver error deep inside KMongo.
    require(connectionString.isNotBlank()) {
        "MongoDB connection string is empty (env='$environment'). Set BRAIDRUN_AGENT_MONGO_URL " +
            "or `-Dapp.config.database.url=...` or pass `mongo_connection_string` parameter."
    }
    require(dbName.isNotBlank()) {
        "MongoDB database name is empty (env='$environment'). Set BRAIDRUN_AGENT_MONGO_DB_NAME " +
            "or pass `mongo_db_name` parameter."
    }
    return connectionString to dbName
}

/**
 * Cached MongoClient instances by connection string to avoid expensive client creation/teardown per operation.
 * MongoClient is thread-safe and manages its own connection pool internally.
 */
private val mongoClientCache = ConcurrentHashMap<String, MongoClient>()

@PublishedApi
internal fun getOrCreateMongoClient(connectionString: String): MongoClient =
    // computeIfAbsent (atomic), not getOrPut: a first-access race on getOrPut builds
    // two clients (each a pool + monitor threads) and leaks the loser unclosed.
    mongoClientCache.computeIfAbsent(connectionString) { KMongo.createClient(it) }

/**
 * Cached JedisPool instances by host:port to avoid creating a new pool per operation.
 */
private val jedisPoolCache = ConcurrentHashMap<String, JedisPool>()

@PublishedApi
internal fun getOrCreateJedisPool(host: String, port: Int): JedisPool =
    jedisPoolCache.computeIfAbsent("$host:$port") { JedisPool(host, port) }

/**
 * Close every cached Mongo/Redis connection. Registered as a JVM shutdown hook so
 * long-running processes (CLI, MCP server) don't leak client/connection pool resources
 * across repeated executions within the same JVM.
 */
fun closeAgentDatabaseConnections() {
    val mongoClients = mongoClientCache.entries.toList()
    mongoClientCache.clear()
    mongoClients.forEach { (_, client) ->
        runCatching { client.close() }
    }
    val jedisPools = jedisPoolCache.entries.toList()
    jedisPoolCache.clear()
    jedisPools.forEach { (_, pool) ->
        runCatching { pool.close() }
    }
}

/**
 * Install the shutdown hook once at file load time. The Boolean value is a sentinel — the
 * real work happens in the hook. This is a workaround for "file top-level init" which
 * Kotlin reserves for classes/objects.
 */
@Suppress("unused")
private val agentDatabaseShutdownHookInstalled: Boolean = run {
    runCatching {
        Runtime.getRuntime().addShutdownHook(Thread({
            closeAgentDatabaseConnections()
        }, "agent-database-shutdown"))
    }
    true
}

suspend inline fun <reified T : Any, reified R> withKMongo(
    connectionString: String,
    dbName: String,
    collection: String,
    crossinline block: suspend MongoCollection<T>.() -> R
): R {
    val mongo = getOrCreateMongoClient(connectionString)
    val db = mongo.getDatabase(dbName)
    val col = db.getCollection<T>(collection)
    return block(col)
}

suspend inline fun <reified T : Any, reified R> withKMongo(
    parameters: List<ConfigurationParameter>,
    dbName: String = parameters.parameter("mongo_db_name", "braidrun-agent"),
    // Fallback to qualifiedName when simpleName is null (anonymous/local class) to avoid NPE.
    // Final fallback of "collection" is defensive — unnamed reified classes are rare in practice.
    collectionName: String = parameters.parameter(
        "mongo_collection",
        T::class.simpleName ?: T::class.qualifiedName ?: "collection"
    ),
    crossinline block: suspend MongoCollection<T>.() -> R
): R {
    val (connectionString, _) = resolveMongoConnection(parameters)
    return withKMongo(connectionString, dbName, collectionName, block)
}

suspend inline fun <reified T : Any, reified R> withResolvedKMongo(
    parameters: List<ConfigurationParameter>,
    dbName: String,
    collectionName: String,
    crossinline block: suspend MongoCollection<T>.() -> R
): R {
    val (connectionString, _) = resolveMongoConnection(parameters)
    val resolvedDbName = parameters.parameter("mongo_db_name", dbName)
    return withKMongo(connectionString, resolvedDbName, collectionName, block)
}

suspend inline fun <reified T : Any, reified R> withResolvedKMongo(
    env: String? = EnvironmentConfig.getEnvironment(),
    dbName: String,
    collectionName: String,
    crossinline block: suspend MongoCollection<T>.() -> R
): R {
    val (connectionString, _) = resolveMongoConnection(env)
    return withKMongo(connectionString, dbName, collectionName, block)
}

suspend inline fun <reified R> withRedis(
    parameters: List<ConfigurationParameter>,
    crossinline block: suspend Jedis.() -> R
): R {
    val host = parameters.parameter("redis_srv", "localhost")
    val port = parameters.parameter("redis_port", 6379)
    val pool = getOrCreateJedisPool(host, port)
    return pool.resource.use { jedis ->
        block(jedis)
    }
}
