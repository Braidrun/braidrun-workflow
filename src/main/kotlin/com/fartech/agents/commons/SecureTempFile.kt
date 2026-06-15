package com.fartech.agents.commons

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Helpers for creating temp files / dirs with **owner-only** permissions.
 *
 * `File.createTempFile` honours the JVM's umask: on most Linux/macOS systems that means
 * `0644`, so any local user on the host can read the temp file between creation and
 * deletion. That's fine for innocuous outputs but unacceptable for files holding:
 *
 *   * agent-generated source code about to be `exec`d (could contain hard-coded
 *     credentials that the LLM was told to use; pre-exec they are sitting on disk
 *     world-readable for several seconds)
 *   * intermediate document buffers that may include PII
 *
 * Returns the same `File` so callers' `tempFile.absolutePath` / `tempFile.delete()`
 * code keeps working unchanged.
 *
 * On Windows (no POSIX permission model) the file falls back to `createTempFile` —
 * Windows users are isolated by ACLs against other accounts already, and there's no
 * portable way to express "owner-read-write" via PosixFilePermissions there.
 */
internal object SecureTempFile {

    fun create(prefix: String, suffix: String, directory: File? = null): File {
        val path = if (directory != null) {
            Files.createTempFile(directory.toPath(), prefix, suffix)
        } else {
            Files.createTempFile(prefix, suffix)
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY_FILE_PERMS)
        } catch (_: UnsupportedOperationException) {
            // Windows / non-POSIX FS — fall through; ACLs already isolate by default.
        } catch (_: Exception) {
            // Best-effort; on FAT/exFAT or unusual mounts the chmod may fail but the
            // file is still on a path we control.
        }
        return path.toFile()
    }

    fun createDirectory(prefix: String): File {
        val path = Files.createTempDirectory(prefix)
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY_DIR_PERMS)
        } catch (_: UnsupportedOperationException) {
        } catch (_: Exception) {
        }
        return path.toFile()
    }

    private val OWNER_ONLY_FILE_PERMS = PosixFilePermissions.fromString("rw-------")
    private val OWNER_ONLY_DIR_PERMS = PosixFilePermissions.fromString("rwx------")
}
