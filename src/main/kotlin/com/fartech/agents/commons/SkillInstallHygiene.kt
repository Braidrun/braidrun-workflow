package com.fartech.agents.commons

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * File-system hygiene for skill directories that arrive from outside the host
 * (ClawHub, Git, user ZIP uploads) and for deleting them again.
 */
object SkillInstallHygiene {

    /**
     * Directories directly under a skill root that make the skill *run code* on the host
     * when it is loaded: `hooks/` (HOOK.md handler scripts, see [BraidrunHookLoader]) and
     * `mcp-servers/` (npm/pip/run.sh servers, see [MCPServerManager]).
     */
    val SIDE_EFFECT_DIRECTORY_NAMES: Set<String> = setOf("hooks", "mcp-servers")

    /**
     * Removes [SIDE_EFFECT_DIRECTORY_NAMES] (case-insensitively, symlinks included) from
     * directly under [skillRoot] and returns the names that were removed. Only the skill
     * root is affected: a nested `src/hooks/` inside a template is ordinary content.
     */
    fun stripSideEffectDirectories(skillRoot: File): List<String> {
        val children = skillRoot.listFiles() ?: return emptyList()
        return children
            .filter { child -> SIDE_EFFECT_DIRECTORY_NAMES.any { it.equals(child.name, ignoreCase = true) } }
            .filter { child -> deleteRecursivelyNoFollow(child) }
            .map { it.name }
            .sorted()
    }

    /**
     * Deletes [target] and everything under it **without following symbolic links**: a link
     * is removed, its target is left alone. Kotlin's `File.deleteRecursively()` follows
     * directory links, so a cloned repository containing `x -> /` would take the host with it.
     *
     * Returns true when [target] no longer exists afterwards.
     */
    fun deleteRecursivelyNoFollow(target: File): Boolean {
        val root = target.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(root)
            } else {
                // walkFileTree does not follow links unless FOLLOW_LINKS is requested, so a
                // link below the root is reported to visitFile and deleted as a link.
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    /** True when the canonical form of [candidate] lies strictly inside the canonical [root]. */
    fun isStrictlyInside(candidate: File, root: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return false
        val canonicalCandidate = runCatching { candidate.canonicalFile.toPath() }.getOrNull() ?: return false
        return canonicalCandidate != canonicalRoot && canonicalCandidate.startsWith(canonicalRoot)
    }
}
