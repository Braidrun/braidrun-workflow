package com.fartech.agents.workflow

import com.fartech.agents.commons.SkillLoader
import com.fartech.agents.commons.SkillsConfiguration
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * What an execution's `.skills-runtime/` copy of a skills directory may contain.
 *
 * The staged copy is readable from the execution sandbox, so it must not be a verbatim copy
 * of the shared directory: that would hand the sandbox every other tenant's installed skills
 * and the credentials administrators keep in `<skill>/.state/`. Only skills that the scoped
 * `skills_config` actually loads are staged, and never `.state/`, `.git` or symbolic links.
 */
internal object RuntimeSkillMaterialization {

    /** Directory names never staged, at any depth: runtime credentials and VCS metadata. */
    val EXCLUDED_DIRECTORY_NAMES: Set<String> = setOf(".state", ".git")

    /**
     * Canonical skill directories under [sourceDir] that [config] loads — the same discovery
     * and allow/deny evaluation (including the built-in auto-allowlist) the runtime manager
     * applies, restricted to this one directory.
     */
    fun enabledSkillRoots(sourceDir: File, config: SkillsConfiguration): Set<Path> {
        val source = sourceDir.canonicalFile.toPath()
        val scopedToSource = config.copy(
            skillsPath = source.toString(),
            additionalSkillPaths = emptyList(),
            scanStandardPaths = false
        )
        return SkillLoader(source, scopedToSource).loadAllSkills()
            .filter { it.scope == "configured" }
            .mapNotNull { skill -> skill.baseDirectory?.let { runCatching { File(it).canonicalFile.toPath() }.getOrNull() } }
            .filter { it.startsWith(source) }
            .toSet()
    }

    /** Stable short id of a selection, so differently-scoped agents never share a staged copy. */
    fun selectionFingerprint(sourceDir: File, skillRoots: Set<Path>): String {
        val source = sourceDir.canonicalFile.toPath()
        val material = skillRoots
            .map { source.relativize(it).toString().replace('\\', '/') }
            .sorted()
            .joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
    }

    /**
     * Copies [skillRoots] (and the loose regular files directly in [sourceDir], which only the
     * operator can place there) from [sourceDir] into [destination]. A nested directory that is
     * a skill of its own but not selected is left out, as are [EXCLUDED_DIRECTORY_NAMES] and
     * every symbolic link (links are never followed).
     */
    fun copySelectedSkills(sourceDir: File, destination: File, skillRoots: Set<Path>) {
        val source = sourceDir.canonicalFile.toPath()
        val target = destination.toPath()
        Files.createDirectories(target)

        fun owningSkillRoot(path: Path): Path? {
            var current: Path? = path
            while (current != null && current.startsWith(source)) {
                if (current in skillRoots) return current
                current = current.parent
            }
            return null
        }

        fun destinationFor(path: Path): Path {
            val resolved = target.resolve(source.relativize(path).toString()).normalize()
            require(resolved.startsWith(target.normalize())) { "Skill materialization path escapes destination: $path" }
            return resolved
        }

        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == source) return FileVisitResult.CONTINUE
                if (dir.fileName.toString() in EXCLUDED_DIRECTORY_NAMES) return FileVisitResult.SKIP_SUBTREE
                val owner = owningSkillRoot(dir)
                    // Outside every selected skill: only descend towards one.
                    ?: return if (skillRoots.any { it.startsWith(dir) }) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                if (dir != owner && containsSkillFile(dir)) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(destinationFor(dir))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink || !attrs.isRegularFile) return FileVisitResult.CONTINUE
                val parent = file.parent ?: return FileVisitResult.CONTINUE
                if (parent != source && owningSkillRoot(parent) == null) return FileVisitResult.CONTINUE
                val destinationFile = destinationFor(file)
                Files.createDirectories(destinationFile.parent)
                Files.copy(file, destinationFile, StandardCopyOption.REPLACE_EXISTING)
                if (Files.isExecutable(file)) {
                    destinationFile.toFile().setExecutable(true, false)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }

    private fun containsSkillFile(dir: Path): Boolean =
        dir.toFile().listFiles()?.any { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) } == true
}
