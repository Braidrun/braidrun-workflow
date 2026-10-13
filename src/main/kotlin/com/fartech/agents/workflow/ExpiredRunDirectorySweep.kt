package com.fartech.agents.workflow

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private val logger = KotlinLogging.logger {}

/**
 * The `cleanup_after_hours` sweep of [DirectoryIsolationConfig.baseDir].
 *
 * A base directory is often shared: runs of the flat layout
 * (`{base_dir}/{execution_id}/…`) sit beside owner directories of the default layout
 * (`{base_dir}/{user_id}/{execution_id}/…`) and anything else a host keeps there. An
 * owner directory's mtime only moves when a run directory is created or removed directly
 * inside it, so "every child older than the cutoff" also matched owners who had started
 * nothing new for a while, and deleted all of their runs, in-progress ones included.
 *
 * A direct child of the base directory is now deleted only when it is provably an
 * expired run directory of the flat layout:
 *  - the workflow's working, output and persistence patterns all put the execution id
 *    directly under `{base_dir}` (other layouts are never swept: their top-level entries
 *    are owner or grouping directories, not runs);
 *  - it is a real directory (not a link) whose name does not start with `.`;
 *  - it holds no [DirectoryIsolationConfig.KEEP_MARKER_FILE];
 *  - it holds no directory named like an execution id (the shape of an owner directory);
 *  - it is not an execution this process is running;
 *  - its mtime is older than `cleanup_after_hours`.
 * Every deletion is logged at INFO with its path, age and rule.
 */
internal object ExpiredRunDirectorySweep {
    private val EXECUTION_ID_LIKE =
        Regex("^(?:[A-Za-z0-9]+-)?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:__.+)?$")

    data class Deleted(val path: File, val ageHours: Long, val rule: String)

    /** True when every run path of [config] is `{base_dir}/{execution_id}[/…]`. */
    fun isFlatLayout(config: DirectoryIsolationConfig): Boolean =
        listOf(config.workingDirPattern, config.outputDirPattern, config.persistenceDirPattern).all { raw ->
            val pattern = raw.replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/')
            pattern == "{base_dir}/{execution_id}" || pattern.startsWith("{base_dir}/{execution_id}/")
        }

    fun sweep(config: DirectoryIsolationConfig, nowMs: Long, runningExecutionIds: Set<String>): List<Deleted> {
        if (!config.enabled || config.cleanupAfterHours <= 0) return emptyList()
        val baseDir = File(config.baseDir)
        if (!baseDir.isDirectory) return emptyList()
        if (!isFlatLayout(config)) {
            logger.debug { "[DirIsolation] Expiry sweep skipped for ${baseDir.path}: run directories are not top-level entries" }
            return emptyList()
        }
        val cutoffMs = nowMs - config.cleanupAfterHours * 3_600_000L
        val rule = "flat layout run directory older than cleanup_after_hours=${config.cleanupAfterHours}"
        val deleted = mutableListOf<Deleted>()
        baseDir.listFiles().orEmpty().forEach { dir ->
            if (dir.name.startsWith(".") || dir.name in runningExecutionIds) return@forEach
            if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
            val modified = dir.lastModified()
            if (modified >= cutoffMs) return@forEach
            if (File(dir, DirectoryIsolationConfig.KEEP_MARKER_FILE).exists()) return@forEach
            if (containsExecutionLikeDirectory(dir)) return@forEach
            val ageHours = (nowMs - modified) / 3_600_000L
            if (deleteTreeNoFollow(dir.toPath())) {
                logger.info { "[DirIsolation] Deleted expired run directory ${dir.path} (age ${ageHours}h; rule: $rule)" }
                deleted += Deleted(dir, ageHours, rule)
            } else {
                logger.warn { "[DirIsolation] Failed to delete expired run directory ${dir.path}" }
            }
        }
        return deleted
    }

    private fun containsExecutionLikeDirectory(dir: File): Boolean =
        dir.listFiles().orEmpty().any {
            EXECUTION_ID_LIKE.matches(it.name) && Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        }

    /** Recursive delete that removes links instead of following them. */
    private fun deleteTreeNoFollow(root: Path): Boolean = try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
        !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    } catch (e: IOException) {
        logger.warn(e) { "[DirIsolation] Failed to delete ${root.toAbsolutePath()}" }
        false
    }
}
