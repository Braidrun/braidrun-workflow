package com.fartech.agents.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [FileReadGuard] — the shared read-path validation enforcing C9.
 */
class FileReadGuardTest {

    private val enabledGuard = FileReadGuard(enabled = true)
    private val disabledGuard = FileReadGuard.DISABLED

    // ─── Extension whitelist ───

    @Test
    fun `allows whitelisted text extensions`(@TempDir dir: File) {
        for (ext in listOf("txt", "md", "json", "yaml", "py", "js", "kt", "csv", "log")) {
            val file = File(dir, "test.$ext").also { it.createNewFile() }
            assertDoesNotThrow { enabledGuard.validateReadFile(file) }
        }
    }

    @Test
    fun `allows whitelisted document extensions`(@TempDir dir: File) {
        for (ext in listOf("pdf", "docx", "xlsx", "pptx", "odt")) {
            val file = File(dir, "test.$ext").also { it.createNewFile() }
            assertDoesNotThrow { enabledGuard.validateReadFile(file) }
        }
    }

    @Test
    fun `allows whitelisted image extensions`(@TempDir dir: File) {
        for (ext in listOf("png", "jpg", "jpeg", "gif", "webp", "svg")) {
            val file = File(dir, "test.$ext").also { it.createNewFile() }
            assertDoesNotThrow { enabledGuard.validateReadFile(file) }
        }
    }

    @Test
    fun `allows whitelisted archive extensions`(@TempDir dir: File) {
        for (ext in listOf("zip", "tar", "gz", "bz2", "xz")) {
            val file = File(dir, "test.$ext").also { it.createNewFile() }
            assertDoesNotThrow { enabledGuard.validateReadFile(file) }
        }
    }

    @Test
    fun `blocks non-whitelisted extensions`(@TempDir dir: File) {
        for (ext in listOf("exe", "dll", "so", "class", "jar", "db", "sqlite")) {
            val file = File(dir, "test.$ext").also { it.createNewFile() }
            val ex = assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(file)
            }
            assertTrue(ex.message!!.contains("not in the read whitelist"))
        }
    }

    @Test
    fun `allows extensionless files`(@TempDir dir: File) {
        val file = File(dir, "Makefile").also { it.createNewFile() }
        assertDoesNotThrow { enabledGuard.validateReadFile(file) }
    }

    // ─── Filename blacklist ───

    @Test
    fun `blocks dot-env files`(@TempDir dir: File) {
        for (name in listOf(".env", ".env.local", ".env.production", ".env.staging")) {
            val file = File(dir, name).also { it.createNewFile() }
            val ex = assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(file)
            }
            assertTrue(ex.message!!.contains("blacklisted"))
        }
    }

    @Test
    fun `blocks SSH private keys`(@TempDir dir: File) {
        for (name in listOf("id_rsa", "id_ed25519", "id_ecdsa")) {
            val file = File(dir, name).also { it.createNewFile() }
            assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(file)
            }
        }
    }

    @Test
    fun `blocks PEM and certificate files`(@TempDir dir: File) {
        for (name in listOf("server.pem", "ca.pem", "keystore.p12", "cert.pfx")) {
            val file = File(dir, name).also { it.createNewFile() }
            assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(file)
            }
        }
    }

    @Test
    fun `blocks SSH config files`(@TempDir dir: File) {
        for (name in listOf("known_hosts", "authorized_keys")) {
            val file = File(dir, name).also { it.createNewFile() }
            assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(file)
            }
        }
    }

    @Test
    fun `blocks git-related files`(@TempDir dir: File) {
        for (name in listOf(".gitconfig", ".git-credentials", ".gitignore")) {
            val file = File(dir, name).also { it.createNewFile() }
            assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(file)
            }
        }
    }

    @Test
    fun `blocks cloud credential files`(@TempDir dir: File) {
        for (name in listOf(".aws", ".ssh", ".gnupg", "credentials", ".npmrc", ".pypirc")) {
            val file = File(dir, name).also { it.createNewFile() }
            assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(file)
            }
        }
    }

    @Test
    fun `blacklist takes precedence over extension whitelist`(@TempDir dir: File) {
        // .env.yml has a whitelisted extension (.yml) but blacklisted filename
        val file = File(dir, ".env.yml").also { it.createNewFile() }
        assertThrows(SecurityException::class.java) {
            enabledGuard.validateReadFile(file)
        }
    }

    // ─── Dev mode (disabled) ───

    @Test
    fun `disabled guard allows everything`(@TempDir dir: File) {
        val blocked = File(dir, ".env").also { it.createNewFile() }
        val exe = File(dir, "malware.exe").also { it.createNewFile() }
        assertDoesNotThrow { disabledGuard.validateReadFile(blocked) }
        assertDoesNotThrow { disabledGuard.validateReadFile(exe) }
    }

    // ─── Path-based validation ───

    @Test
    fun `validateReadPath delegates to validateReadFile`(@TempDir dir: File) {
        val envFile = File(dir, ".env").also { it.createNewFile() }
        assertThrows(SecurityException::class.java) {
            enabledGuard.validateReadPath(envFile.toPath())
        }
    }

    // ─── Custom whitelist / blacklist ───

    @Test
    fun `custom extension whitelist is respected`(@TempDir dir: File) {
        val guard = FileReadGuard(enabled = true, extensionWhitelist = setOf("custom"))
        val allowed = File(dir, "file.custom").also { it.createNewFile() }
        val blocked = File(dir, "file.txt").also { it.createNewFile() }
        assertDoesNotThrow { guard.validateReadFile(allowed) }
        assertThrows(SecurityException::class.java) { guard.validateReadFile(blocked) }
    }

    @Test
    fun `filename blacklist still applies when extension is custom whitelisted`(@TempDir dir: File) {
        val guard = FileReadGuard(
            enabled = true,
            extensionWhitelist = FileReadGuard.DEFAULT_READ_EXTENSION_WHITELIST + setOf("pem", "key")
        )

        for (name in listOf("server.pem", "deploy.key")) {
            val blocked = File(dir, name).also { it.createNewFile() }
            val ex = assertThrows(SecurityException::class.java) {
                guard.validateReadFile(blocked)
            }
            assertTrue(ex.message!!.contains("blacklisted"))
        }
    }

    @Test
    fun `blocks plural sensitive filename keywords with whitelisted extensions`(@TempDir dir: File) {
        for (name in listOf("customer-secrets.json", "api_keys.yaml", "passwords.txt", "session-tokens.csv")) {
            val blocked = File(dir, name).also { it.createNewFile() }
            val ex = assertThrows(SecurityException::class.java) {
                enabledGuard.validateReadFile(blocked)
            }
            assertTrue(ex.message!!.contains("blacklisted"))
        }
    }

    @Test
    fun `custom filename blacklist is respected`(@TempDir dir: File) {
        val guard = FileReadGuard(
            enabled = true,
            filenameBlacklist = listOf(Regex("""^secret_.*"""))
        )
        val blocked = File(dir, "secret_data.txt").also { it.createNewFile() }
        assertThrows(SecurityException::class.java) { guard.validateReadFile(blocked) }
    }
}
