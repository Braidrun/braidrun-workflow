package com.fartech.agents.tools

import com.fartech.agents.commons.SandboxedFileSystemProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests that [SandboxedFileSystemProvider] enforces:
 *
 *   1. **Sandbox-dir check** on every operation (read or write): the path
 *      MUST be inside one of the allowed directories.
 *   2. **Content gate** (FileReadGuard) only on operations that return
 *      file contents: [readBytes] and [inputStream]. Metadata-only
 *      operations (`exists`, `list`, `metadata`, `size`,
 *      `getFileContentType`) deliberately bypass the content gate so
 *      Koog's `__write_file__` (which probes existence first) and
 *      `__list_directory__` (which iterates children's metadata) work for
 *      files whose extension isn't on the read whitelist.
 *
 * Pre-2026-04-26 every read-side operation ran through `validateReadPath`,
 * which silently blocked PWA workflows from writing `.webmanifest` files
 * (Koog's WriteFileTool calls `exists()` before `writeBytes()`). See
 * `execution-process-868c6031-...-failed.yaml` for the trigger case.
 *
 * ## Test-style note
 *
 * Each `@Test` body uses a regular `{ }` block (not `= runBlocking { }`)
 * with `runBlocking { }` placed inside. Pre-2026-04-26 several tests in
 * this file used `= runBlocking { ... assertThrows(...) }` — Kotlin
 * inferred the return type as the thrown exception (T), JUnit 5 silently
 * skipped non-Unit-returning `@Test` methods, and 8 of 9 assertion blocks
 * never ran. The new convention guarantees `@Test` methods always return
 * Unit so JUnit discovers and executes every one.
 */
class SandboxedReadValidationTest {

    @Test
    fun `readBytes blocks blacklisted file when guard enabled`(@TempDir dir: File) {
        val envFile = File(dir, ".env").also { it.writeText("SECRET=value") }
        val guard = FileReadGuard(enabled = true)
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = guard
        )
        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.readBytes(envFile.toPath()) }
        }
    }

    @Test
    fun `readBytes allows whitelisted file when guard enabled`(@TempDir dir: File) {
        val txtFile = File(dir, "data.txt").also { it.writeText("hello") }
        val guard = FileReadGuard(enabled = true)
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = guard
        )
        val bytes = runBlocking { sandbox.readBytes(txtFile.toPath()) }
        assertEquals("hello", String(bytes))
    }

    @Test
    fun `readBytes allows everything when guard disabled`(@TempDir dir: File) {
        val envFile = File(dir, ".env").also { it.writeText("SECRET=value") }
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard.DISABLED
        )
        val bytes = runBlocking { sandbox.readBytes(envFile.toPath()) }
        assertEquals("SECRET=value", String(bytes))
    }

    @Test
    fun `readBytes blocks non-whitelisted extension`(@TempDir dir: File) {
        val exeFile = File(dir, "malware.exe").also { it.writeText("bad") }
        val guard = FileReadGuard(enabled = true)
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = guard
        )
        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.readBytes(exeFile.toPath()) }
        }
    }

    @Test
    fun `inputStream blocks blacklisted file when guard enabled`(@TempDir dir: File) {
        val sshKey = File(dir, "id_rsa").also { it.writeText("PRIVATE KEY") }
        val guard = FileReadGuard(enabled = true)
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = guard
        )
        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.inputStream(sshKey.toPath()) }
        }
    }

    @Test
    fun `metadata does NOT run the content gate (only readBytes does)`(@TempDir dir: File) {
        // Pre-2026-04-26: this test asserted metadata() throws SecurityException
        // for blacklisted .env files. New contract: metadata-only ops bypass
        // the content gate (they return name/size/mtime, not file contents).
        // The sandbox-dir check still applies — see the `metadata blocks files
        // outside allowed sandbox` test for that boundary.
        val envFile = File(dir, ".env").also { it.writeText("SECRET=value") }
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard(enabled = true)
        )

        // Inside the sandbox: metadata succeeds even for a blacklisted name.
        // Reading the actual contents still throws (content gate intact).
        val md = runBlocking { sandbox.metadata(envFile.toPath()) }
        assertNotNull(md)
        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.readBytes(envFile.toPath()) }
        }
    }

    // ── New 2026-04-26 contract: metadata-only ops bypass content gate ──

    @Test
    fun `exists succeeds for non-whitelisted extension inside sandbox`(@TempDir dir: File) {
        // Reproducer for execution-process-868c6031-...: Koog's __write_file__
        // probes target existence before writing. Pre-fix, exists() ran the
        // content gate, so writing manifest.webmanifest threw "Read denied"
        // on the existence check — and the agent could never even create the
        // file. New contract: exists() is metadata-only and doesn't gate on
        // extension.
        val target = File(dir, "manifest.webmanifest")  // not in pre-fix whitelist
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard(enabled = true)
        )

        val exists = runBlocking { sandbox.exists(target.toPath()) }
        assertFalse(exists, "non-existing file should return false, not throw")
    }

    @Test
    fun `exists succeeds even for blacklisted filename inside sandbox`(@TempDir dir: File) {
        // exists() reveals only a boolean — it doesn't leak file contents,
        // so the blacklist (designed to protect content) shouldn't apply.
        val envFile = File(dir, ".env").also { it.writeText("SECRET=value") }
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard(enabled = true)
        )

        val exists = runBlocking { sandbox.exists(envFile.toPath()) }
        assertTrue(exists, "blacklisted file should be exists()-able inside sandbox")
        // But content reads still throw.
        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.readBytes(envFile.toPath()) }
        }
    }

    @Test
    fun `list of a sandboxed directory works even when it contains non-whitelisted files`(@TempDir dir: File) {
        // Reproducer for the second symptom of execution-process-868c6031-...:
        // __list_directory__ failed on a directory that contained a single
        // .webmanifest file (after a different write path planted one) because
        // pre-fix list() ran content gate per-child. New contract: listing
        // only requires sandbox-dir check on the directory itself.
        File(dir, "manifest.webmanifest").writeText("{}")
        File(dir, "data.parquet").writeText("binary-junk")
        File(dir, "ok.txt").writeText("ok")
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard(enabled = true)
        )

        val children = runBlocking { sandbox.list(dir.toPath()) }
        assertEquals(3, children.size, "list should include all 3 files regardless of extension")
    }

    @Test
    fun `webmanifest is in the default read whitelist (regression)`() {
        // Prevents anyone from accidentally removing webmanifest while
        // tightening the whitelist later.
        assertTrue(
            "webmanifest" in FileReadGuard.DEFAULT_READ_EXTENSION_WHITELIST,
            ".webmanifest must remain in the default whitelist (W3C-blessed PWA manifest extension)"
        )
        // Same for other modern web extensions agents commonly emit.
        listOf("mjs", "cjs", "tsx", "jsx", "vue", "svelte", "ico", "avif").forEach {
            assertTrue(
                it in FileReadGuard.DEFAULT_READ_EXTENSION_WHITELIST,
                ".$it should be in the default whitelist (added 2026-04-26 with webmanifest)"
            )
        }
    }

    @Test
    fun `readBytes returns swift source under the strict guard`(@TempDir dir: File) {
        // Regression for the iOS pipeline: agents write Foo.swift through the
        // sandbox and then read it back for review / audit steps. Pre-fix the
        // whitelist had no Apple source types, so readBytes threw
        // SecurityException on the very file the same agent had just written.
        val swiftFile = File(dir, "ContentView.swift").also { it.writeText("import SwiftUI") }
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard(enabled = true)
        )

        val bytes = runBlocking { sandbox.readBytes(swiftFile.toPath()) }
        assertEquals("import SwiftUI", String(bytes))

        // A genuinely disallowed binary artifact is still blocked.
        val binary = File(dir, "App.ipa").also { it.writeText("PK") }
        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.readBytes(binary.toPath()) }
        }
    }

    @Test
    fun `apple source extensions are in the default read whitelist (regression)`() {
        // Prevents anyone from dropping the iOS formats while tightening the
        // whitelist later — every coder / reviewer / auditor step depends on them.
        listOf(
            "swift", "m", "mm", "h", "modulemap", "podspec",
            "plist", "xcprivacy", "xcstrings", "strings", "stringsdict",
            "pbxproj", "xcconfig", "entitlements", "xcscheme", "xcworkspacedata", "resolved",
            "storyboard", "xib"
        ).forEach {
            assertTrue(
                it in FileReadGuard.DEFAULT_READ_EXTENSION_WHITELIST,
                ".$it must be in the default whitelist (Apple/iOS text formats)"
            )
        }
        // Compiled / packaged Apple output stays out.
        listOf("ipa", "dylib", "xcarchive", "nib", "car").forEach {
            assertFalse(
                it in FileReadGuard.DEFAULT_READ_EXTENSION_WHITELIST,
                ".$it is a binary build artifact and must NOT be whitelisted"
            )
        }
    }

    @Test
    fun `metadata still blocks files outside allowed sandbox`(@TempDir dir: File) {
        // The sandbox-dir check is the absolute boundary that ALL ops must
        // pass. Make sure we didn't accidentally relax it.
        val outsideFile = dir.parentFile.resolve("${dir.name}-outside.txt").also { it.writeText("hello") }
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard.DISABLED
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sandbox.metadata(outsideFile.toPath()) }
        }
    }

    @Test
    fun `list blocks directories outside allowed sandbox`(@TempDir dir: File) {
        val outsideDir = dir.parentFile.resolve("${dir.name}-outside").also { it.mkdirs() }
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard.DISABLED
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sandbox.list(outsideDir.toPath()) }
        }
    }

    @Test
    fun `exists blocks files outside allowed sandbox`(@TempDir dir: File) {
        val outsideFile = dir.parentFile.resolve("${dir.name}-outside.txt").also { it.writeText("secret") }
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard.DISABLED
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sandbox.exists(outsideFile.toPath()) }
        }
    }

    @Test
    fun `copy blocks source outside allowed sandbox`(@TempDir dir: File) {
        val outsideFile = dir.parentFile.resolve("${dir.name}-outside.txt").also { it.writeText("secret") }
        val target = File(dir, "copied.txt")
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard.DISABLED
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sandbox.copy(outsideFile.toPath(), target.toPath()) }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `copy blocks blacklisted source rename when guard enabled`(@TempDir dir: File) {
        val envFile = File(dir, ".env").also { it.writeText("SECRET=value") }
        val target = File(dir, "env.txt")
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard(enabled = true)
        )

        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.copy(envFile.toPath(), target.toPath()) }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `move blocks blacklisted source rename when guard enabled`(@TempDir dir: File) {
        val envFile = File(dir, ".env").also { it.writeText("SECRET=value") }
        val target = File(dir, "env.txt")
        val sandbox = SandboxedFileSystemProvider(
            allowedDirectories = listOf(dir.toPath()),
            readGuard = FileReadGuard(enabled = true)
        )

        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox.move(envFile.toPath(), target.toPath()) }
        }
        assertTrue(envFile.exists())
        assertFalse(target.exists())
    }

    @Test
    fun `createFromParameters passes readGuard to instance`(@TempDir dir: File) {
        val guard = FileReadGuard(enabled = true)
        val params = listOf(
            com.fartech.ftapp2.commonsKt.ConfigurationParameter("working_dir", kotlinx.serialization.json.JsonPrimitive(dir.absolutePath))
        )
        val sandbox = SandboxedFileSystemProvider.createFromParameters(params, readGuard = guard)
        assertNotNull(sandbox)

        // Should block blacklisted reads
        val envFile = File(dir, ".env").also { it.writeText("SECRET") }
        assertThrows(SecurityException::class.java) {
            runBlocking { sandbox!!.readBytes(envFile.toPath()) }
        }
    }
}
