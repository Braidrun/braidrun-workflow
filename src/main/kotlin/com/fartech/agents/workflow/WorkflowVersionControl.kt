package com.fartech.agents.workflow

import mu.KotlinLogging
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 工作流版本控制
 *
 * 提供工作流的版本管理和回滚功能：
 * - 版本追踪
 * - 版本比较
 * - 回滚到历史版本
 * - 版本快照
 */
class WorkflowVersionControl(
    private val versionsDir: String = "workflows/versions"
) {
    private val workflowVersions = ConcurrentHashMap<String, MutableList<VersionedWorkflow>>()

    /** Creates a thread-safe list for storing versions */
    private fun newVersionList(): MutableList<VersionedWorkflow> =
        java.util.Collections.synchronizedList(mutableListOf())

    init {
        // 确保版本目录存在
        File(versionsDir).mkdirs()
        loadVersionsFromDisk()
    }

    /**
     * 保存工作流的新版本
     */
    fun saveVersion(
        workflow: WorkflowDefinition,
        workflowPath: String,
        description: String? = null,
        createdBy: String? = null
    ): VersionedWorkflow {
        val workflowContent = File(workflowPath).readText()
        val checksum = calculateChecksum(workflowContent)

        val version = VersionedWorkflow(
            workflowName = workflow.name,
            version = workflow.version,
            checksum = checksum,
            createdAt = Instant.now().toString(),
            createdBy = createdBy,
            description = description,
            content = workflowContent,
            filePath = workflowPath
        )

        // 添加到版本列表
        workflowVersions.computeIfAbsent(workflow.name) { newVersionList() }.add(version)

        // 保存到磁盘
        saveVersionToDisk(version)

        logger.info { "Saved version ${version.version} for workflow ${workflow.name}" }
        return version
    }

    /**
     * 获取工作流的所有版本
     */
    fun getVersions(workflowName: String): List<VersionedWorkflow> {
        return workflowVersions[workflowName]?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /**
     * 获取特定版本
     */
    fun getVersion(workflowName: String, version: String): VersionedWorkflow? {
        val versions = workflowVersions[workflowName] ?: return null
        // find{} iterates — Collections.synchronizedList requires holding the
        // monitor while iterating (a concurrent saveVersion would otherwise CME).
        synchronized(versions) {
            return versions.find { it.version == version }
        }
    }

    /**
     * 回滚到指定版本
     */
    fun rollback(workflowName: String, targetVersion: String, targetPath: String): Boolean {
        val version = getVersion(workflowName, targetVersion)
            ?: run {
                logger.error { "Version $targetVersion not found for workflow $workflowName" }
                return false
            }

        return try {
            // 在回滚前，先保存当前版本作为备份
            if (File(targetPath).exists()) {
                val currentWorkflow = WorkflowParser.parseFile(targetPath)
                saveVersion(
                    workflow = currentWorkflow,
                    workflowPath = targetPath,
                    description = "Auto-backup before rollback to $targetVersion",
                    createdBy = "system"
                )
            }

            // 恢复目标版本
            File(targetPath).writeText(version.content)

            logger.info { "Successfully rolled back workflow $workflowName to version $targetVersion" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to rollback workflow $workflowName to version $targetVersion" }
            false
        }
    }

    /**
     * 比较两个版本
     */
    fun compareVersions(
        workflowName: String,
        version1: String,
        version2: String
    ): VersionComparison {
        val v1 = getVersion(workflowName, version1)
        val v2 = getVersion(workflowName, version2)

        if (v1 == null || v2 == null) {
            return VersionComparison(
                workflowName = workflowName,
                version1 = version1,
                version2 = version2,
                identical = false,
                changes = listOf("One or both versions not found")
            )
        }

        val identical = v1.checksum == v2.checksum
        val changes = if (identical) {
            emptyList()
        } else {
            // 简单的行级差异
            val lines1 = v1.content.lines()
            val lines2 = v2.content.lines()
            findDifferences(lines1, lines2)
        }

        return VersionComparison(
            workflowName = workflowName,
            version1 = version1,
            version2 = version2,
            identical = identical,
            changes = changes
        )
    }

    /**
     * 删除旧版本（保留最近 N 个版本）
     */
    fun pruneVersions(workflowName: String, keepCount: Int = 10) {
        workflowVersions[workflowName]?.let { versions ->
            synchronized(versions) {
                if (versions.size > keepCount) {
                    val sorted = versions.sortedByDescending { it.createdAt }
                    val toRemove = sorted.drop(keepCount)

                    toRemove.forEach { version ->
                        deleteVersionFromDisk(version)
                        versions.remove(version)
                    }

                    logger.info { "Pruned ${toRemove.size} old versions for workflow $workflowName" }
                }
            }
        }
    }

    /**
     * 计算内容的校验和
     */
    private fun calculateChecksum(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 查找两个版本之间的差异
     */
    private fun findDifferences(lines1: List<String>, lines2: List<String>): List<String> {
        val changes = mutableListOf<String>()

        val maxLines = maxOf(lines1.size, lines2.size)
        for (i in 0 until maxLines) {
            val line1 = lines1.getOrNull(i)
            val line2 = lines2.getOrNull(i)

            when {
                line1 == null -> changes.add("+ Line ${i + 1}: $line2")
                line2 == null -> changes.add("- Line ${i + 1}: $line1")
                line1 != line2 -> {
                    changes.add("- Line ${i + 1}: $line1")
                    changes.add("+ Line ${i + 1}: $line2")
                }
            }
        }

        return changes
    }

    /**
     * 保存版本到磁盘
     */
    /** Sanitize a name for safe use as a filesystem path segment */
    private fun safeName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f]"), "_")
            .replace("..", "_")
            .trim()
            .ifEmpty { "unnamed" }

    private fun saveVersionToDisk(version: VersionedWorkflow) {
        val dir = File(versionsDir, safeName(version.workflowName))
        dir.mkdirs()

        val versionFile = File(dir, "${safeName(version.version)}.yaml")
        versionFile.writeText(version.content)

        // 保存元数据
        val metaFile = File(dir, "${safeName(version.version)}.meta.json")
        val meta = mapOf(
            "version" to version.version,
            "checksum" to version.checksum,
            "createdAt" to version.createdAt,
            "createdBy" to (version.createdBy ?: "unknown"),
            "description" to (version.description ?: "")
        )
        metaFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(meta.mapValues {
                    kotlinx.serialization.json.JsonPrimitive(it.value)
                })
            )
        )
    }

    /**
     * 从磁盘删除版本
     */
    private fun deleteVersionFromDisk(version: VersionedWorkflow) {
        val dir = File(versionsDir, safeName(version.workflowName))
        File(dir, "${safeName(version.version)}.yaml").delete()
        File(dir, "${safeName(version.version)}.meta.json").delete()
    }

    /**
     * 从磁盘加载所有版本
     */
    private fun loadVersionsFromDisk() {
        val versionsRoot = File(versionsDir)
        if (!versionsRoot.exists()) return

        versionsRoot.listFiles()?.forEach { workflowDir ->
            if (workflowDir.isDirectory) {
                val workflowName = workflowDir.name
                val versions = mutableListOf<VersionedWorkflow>()

                workflowDir.listFiles()?.filter { it.extension == "yaml" }?.forEach { versionFile ->
                    try {
                        val content = versionFile.readText()
                        val workflow = WorkflowParser.parseYaml(content)

                        // Must mirror saveVersionToDisk, which writes the sanitized name —
                        // otherwise versions containing sanitized characters silently lose
                        // their metadata (createdAt falls back to now, breaking ordering).
                        val metaFile = File(workflowDir, "${safeName(workflow.version)}.meta.json")
                        val meta = if (metaFile.exists()) {
                            kotlinx.serialization.json.Json.parseToJsonElement(metaFile.readText())
                                    as kotlinx.serialization.json.JsonObject
                        } else null

                        val version = VersionedWorkflow(
                            workflowName = workflowName,
                            version = workflow.version,
                            checksum = meta?.get("checksum")?.toString()?.trim('"')
                                ?: calculateChecksum(content),
                            createdAt = meta?.get("createdAt")?.toString()?.trim('"')
                                ?: Instant.now().toString(),
                            createdBy = meta?.get("createdBy")?.toString()?.trim('"'),
                            description = meta?.get("description")?.toString()?.trim('"'),
                            content = content,
                            filePath = versionFile.absolutePath
                        )

                        versions.add(version)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to load version from ${versionFile.absolutePath}" }
                    }
                }

                if (versions.isNotEmpty()) {
                    val syncedVersions = java.util.Collections.synchronizedList(versions)
                    workflowVersions[workflowName] = syncedVersions
                }
            }
        }

        logger.info { "Loaded ${workflowVersions.size} workflows with version history" }
    }
}

/**
 * 版本化的工作流
 */
data class VersionedWorkflow(
    val workflowName: String,
    val version: String,
    val checksum: String,
    val createdAt: String,
    val createdBy: String?,
    val description: String?,
    val content: String,
    val filePath: String
)

/**
 * 版本比较结果
 */
data class VersionComparison(
    val workflowName: String,
    val version1: String,
    val version2: String,
    val identical: Boolean,
    val changes: List<String>
)
