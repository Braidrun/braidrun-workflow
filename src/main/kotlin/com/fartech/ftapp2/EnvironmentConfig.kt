package com.fartech.ftapp2

import com.fartech.ftapp2.commonsKt.redactSensitiveUrlForLogs
import org.slf4j.LoggerFactory

/**
 * 环境配置管理类
 * 提供统一的环境配置访问接口
 * 所有环境相关的系统属性都在 CommonsMain.setEnvironmentProperties() 中统一设置
 * 注意：数据库连接由原有的 -d 参数处理，数据库名称可以通过 -db 参数覆盖默认值
 *
 * Security:
 * - 数据库 / Redis 连接 URL 绝不能在代码里硬编码带凭据的默认值。
 *   要通过 system property (`-Dapp.config.database.url=...`) 或环境变量
 *   (`BRAIDRUN_AGENT_MONGO_URL[_<ENV>]`) 注入。若没配置，这里返回空串并记录
 *   显式警告；下游 MongoClient 会在无效 URL 上直接失败。
 * - `printEnvironmentInfo()` 通过 `redactSensitiveUrlForLogs` 脱敏凭据，防止
 *   日志外流。
 */
object EnvironmentConfig {

    private val log = LoggerFactory.getLogger(EnvironmentConfig::class.java)

    private const val DB_URL_PROPERTY = "app.config.database.url"
    private const val REDIS_URL_PROPERTY = "app.config.redis.url"

    fun getEnvironment(): String {
        return System.getProperty("app.environment", "braidrun")
    }

    fun getFileStoragePath(): String {
        // `/root/braidrun` 在容器里属于 root 目录，容易和只读 rootfs 冲突；
        // 默认改到 `java.io.tmpdir` 下的子目录，既避免特权路径又便于清理。
        return System.getProperty(
            "app.config.fileStoragePath",
            System.getProperty("java.io.tmpdir") + "/braidrun"
        )
    }

    fun getDefaultDatabaseName(): String {
        return System.getProperty("app.config.dbname", "braidrun")
    }

    /**
     * 返回配置好的 MongoDB URL;若未配置则返回空串并记录 warning。
     * 调用方应当检查返回值是否为空并处理失败情况（AgentDatabase.resolveMongoConnection
     * 会把这个值作为最末一级 fallback）。
     *
     * 不要在此处写死 `mongodb://user:password@host` —— 任何硬编码凭据都会进入
     * git 历史和编译后的 jar，等同于对外泄露账号密码。
     */
    fun getDefaultDatabaseUrl(): String {
        val fromProperty = System.getProperty(DB_URL_PROPERTY)
        if (!fromProperty.isNullOrBlank()) return fromProperty
        // Legacy fallback via env var (只在 system property 缺失时才查)
        val fromEnv = System.getenv("BRAIDRUN_AGENT_MONGO_URL")?.takeIf { it.isNotBlank() }
        if (fromEnv != null) return fromEnv
        log.warn(
            "No MongoDB URL configured — set system property `-D{}=...` or env var " +
                "BRAIDRUN_AGENT_MONGO_URL. Callers will receive an empty string and " +
                "downstream client construction will fail fast.",
            DB_URL_PROPERTY
        )
        return ""
    }

    fun getDefaultRedisUrl(): String {
        val fromProperty = System.getProperty(REDIS_URL_PROPERTY)
        if (!fromProperty.isNullOrBlank()) return fromProperty
        val fromEnv = System.getenv("BRAIDRUN_AGENT_REDIS_URL")?.takeIf { it.isNotBlank() }
        if (fromEnv != null) return fromEnv
        // Redis 默认保留 localhost:6379，这是安全的本地占位；无凭据，不涉及泄露。
        return "redis://127.0.0.1:6379"
    }

    fun getEnvironmentInfo(): Map<String, String> {
        return mapOf(
            "environment" to getEnvironment(),
            "fileStoragePath" to getFileStoragePath(),
            "defaultDatabaseName" to getDefaultDatabaseName(),
            "defaultDatabaseUrl" to redactSensitiveUrlForLogs(getDefaultDatabaseUrl()),
            "defaultRedisUrl" to redactSensitiveUrlForLogs(getDefaultRedisUrl())
        )
    }

    fun printEnvironmentInfo() {
        val info = getEnvironmentInfo()
        log.info("Current environment configuration:")
        info.forEach { (key, value) ->
            log.info("  $key: $value")
        }
    }
}
