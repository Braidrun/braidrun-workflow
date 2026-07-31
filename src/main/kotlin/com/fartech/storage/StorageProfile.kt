package com.fartech.storage

import kotlinx.serialization.Serializable

/**
 * Document store backend selector.
 *
 * ## 多用户改造（M1.5）后的后端
 *
 * - [MEMORY]：进程内 [java.util.concurrent.ConcurrentHashMap]，无持久化。
 *   仅用于**单元测试**和**临时占位**——不要在任何会被多用户共享的代码路径上使用。
 *
 * - [MONGODB]：MongoDB 后端，是 braidrun-web SaaS 部署的**唯一生产存储**。
 *   所有 dev / staging / production SaaS 环境都必须配置成这个。
 *
 * - [SQLITE]：单文件 SQLite 后端，仅用于 Braidrun Desktop 的 LOCAL profile。
 *   它不改变现有 SaaS 环境的默认值或存储选择。
 *
 * ## 历史
 *
 * 此 enum 之前还有 `FILE` 和 `SQL` 两个值，对应 [FileDocumentStore]
 * 和 [SqlDocumentStore]。在 M1.5 阶段（"全面 Mongo 化"）这两个实现连同
 * 它们的配置都被删除，因为：
 *
 * 1. 多后端意味着永远要写 parity 测试，且生产 bug 仍然只在 Mongo 层面出
 * 2. 平台升级到多用户后，平台层数据需要事务、原子计数、Change Streams 等
 *    Mongo 原生能力，FILE/SQL 后端始终拖后腿
 * 3. SQLite 的优势（单文件、无外部依赖）通过 dev 阶段也要求 mongo 来抹平
 *
 * 见 `braidrun-web/docs/multi-user-overview.md` §C1（约束）。
 */
@Serializable
enum class StorageBackend {
    MEMORY,
    MONGODB,
    SQLITE
}

@Serializable
data class MongoStoreConfig(
    val connectionString: String = "mongodb://localhost:27017",
    val database: String = "braidrun",
    val collectionName: String = "dy_documents"
)

@Serializable
data class StorageProfile(
    val backend: StorageBackend = StorageBackend.MONGODB,
    val namespace: String = "default",
    val mongodb: MongoStoreConfig = MongoStoreConfig(),
    val sqlitePath: String = "braidrun.db"
) {
    fun normalized(defaultNamespace: String = namespace): StorageProfile {
        val normalizedNamespace = namespace.ifBlank { defaultNamespace.ifBlank { "default" } }
        return copy(
            namespace = normalizedNamespace,
            mongodb = mongodb.copy(collectionName = mongodb.collectionName.ifBlank { "dy_documents" }),
            sqlitePath = sqlitePath.ifBlank { "braidrun.db" }
        )
    }
}
