package com.fartech.agents.commons

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class SecureTempFileTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `created file is owner-only readable on POSIX systems`() {
        val f = SecureTempFile.create("braidrun_test_", ".tmp")
        try {
            assertTrue(f.exists())
            val perms = Files.getPosixFilePermissions(f.toPath())
            assertFalse(PosixFilePermission.GROUP_READ in perms, "group should not have read access")
            assertFalse(PosixFilePermission.GROUP_WRITE in perms)
            assertFalse(PosixFilePermission.OTHERS_READ in perms, "others should not have read access")
            assertFalse(PosixFilePermission.OTHERS_WRITE in perms)
            assertTrue(PosixFilePermission.OWNER_READ in perms)
            assertTrue(PosixFilePermission.OWNER_WRITE in perms)
        } finally {
            f.delete()
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `created directory is owner-only on POSIX systems`() {
        val d = SecureTempFile.createDirectory("braidrun_test_dir_")
        try {
            assertTrue(d.exists() && d.isDirectory)
            val perms = Files.getPosixFilePermissions(d.toPath())
            assertFalse(PosixFilePermission.GROUP_READ in perms)
            assertFalse(PosixFilePermission.OTHERS_READ in perms)
            assertTrue(PosixFilePermission.OWNER_EXECUTE in perms)
        } finally {
            d.delete()
        }
    }
}
