package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFileToolsTest {

    private val safeFileTools = SafeFileTools()
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterEach
    fun cleanup() {
        tempDirs.forEach { path ->
            if (path.toFile().exists()) {
                path.toFile().deleteRecursively()
            }
        }
        tempDirs.clear()
    }

    @Test
    fun `writeFile rejects absolute sibling path that only matches by string prefix`() {
        val workingDirectory = File(System.getProperty("user.dir")).canonicalFile
        val siblingDir = createTempDirectory(workingDirectory.parentFile.toPath(), "${workingDirectory.name}-outside-")
        tempDirs.add(siblingDir)

        val escapedFile = siblingDir.resolve("escaped.txt").toFile()

        val result = safeFileTools.writeFile(escapedFile.absolutePath, "blocked")

        assertTrue(result.contains("Security Error"))
        assertFalse(escapedFile.exists())
    }

    @Test
    fun `writeFile rejects protected metadata directories`() {
        val protectedDir = File(System.getProperty("user.dir"), ".svn")
        val protectedFile = File(protectedDir, "blocked.txt")

        try {
            val result = safeFileTools.writeFile(protectedFile.path, "blocked")

            assertTrue(result.contains("Security Error"))
            assertFalse(protectedFile.exists())
        } finally {
            if (protectedFile.exists()) {
                protectedFile.delete()
            }
            if (protectedDir.exists()) {
                protectedDir.deleteRecursively()
            }
        }
    }

    @Test
    fun `writeFile rejects sensitive dot env files`() {
        val dir = File(System.getProperty("user.dir"), "build/tmp/safe-file-tools-${java.util.UUID.randomUUID()}")
            .also { it.mkdirs(); tempDirs.add(it.toPath()) }
        val envFile = File(dir, ".env")

        val result = safeFileTools.writeFile(envFile.path, "TOKEN=secret")

        assertTrue(result.contains("Security Error"))
        assertFalse(envFile.exists())
    }

    @Test
    fun `createFromParameters blocks reads outside workflow sandbox`() {
        val workingDir = createTempDirectory("safe-tools-working").toFile().also { tempDirs.add(it.toPath()) }
        val outsideFile = File(System.getProperty("user.dir"), "braidrun-workflow/build.gradle.kts")
        val tool = SafeFileTools.createFromParameters(
            parameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(workingDir.absolutePath))
            )
        )

        val result = tool.readFile(outsideFile.absolutePath)

        assertTrue(result.contains("Security Error"))
        assertTrue(result.contains("Allowed directories"))
    }

    @Test
    fun `createFromParameters allows writes into output directory`() {
        val workingDir = createTempDirectory("safe-tools-working").toFile().also { tempDirs.add(it.toPath()) }
        val outputDir = createTempDirectory("safe-tools-output").toFile().also { tempDirs.add(it.toPath()) }
        val targetFile = File(outputDir, "result.txt")
        val tool = SafeFileTools.createFromParameters(
            parameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(workingDir.absolutePath)),
                ConfigurationParameter("output_dir", JsonPrimitive(outputDir.absolutePath))
            )
        )

        val result = tool.writeFile(targetFile.absolutePath, "ok")

        assertTrue(result.contains("Successfully wrote"))
        assertTrue(targetFile.exists())
        assertTrue(targetFile.readText() == "ok")
    }

    @Test
    fun `writeFile accepts jsonl files in output directory`() {
        val workingDir = createTempDirectory("safe-tools-working").toFile().also { tempDirs.add(it.toPath()) }
        val outputDir = createTempDirectory("safe-tools-output").toFile().also { tempDirs.add(it.toPath()) }
        val targetFile = File(outputDir, "ai_commentary_parts.jsonl")
        val tool = SafeFileTools.createFromParameters(
            parameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(workingDir.absolutePath)),
                ConfigurationParameter("output_dir", JsonPrimitive(outputDir.absolutePath))
            )
        )

        val result = tool.writeFile(targetFile.absolutePath, """{"type":"summary_commentary"}""")

        assertTrue(result.contains("Successfully wrote"))
        assertTrue(targetFile.exists())
        assertTrue(targetFile.readText() == """{"type":"summary_commentary"}""")
    }

    @Test
    fun `writeFile accepts filePath alias`() {
        val workingDir = createTempDirectory("safe-tools-working").toFile().also { tempDirs.add(it.toPath()) }
        val targetFile = File(workingDir, "alias-result.txt")
        val tool = SafeFileTools.createFromParameters(
            parameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(workingDir.absolutePath))
            )
        )

        val result = tool.writeFile(filePath = targetFile.absolutePath, content = "ok")

        assertTrue(result.contains("Successfully wrote"))
        assertTrue(targetFile.exists())
        assertTrue(targetFile.readText() == "ok")
    }

    @Test
    fun `readFile denies blacklisted file names when read guard enabled`() {
        val workingDir = createTempDirectory("safe-tools-working").toFile().also { tempDirs.add(it.toPath()) }
        val envFile = File(workingDir, ".env").apply { writeText("TOKEN=secret") }
        val tool = SafeFileTools.createFromParameters(
            parameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(workingDir.absolutePath))
            ),
            readGuard = FileReadGuard(enabled = true)
        )

        val result = tool.readFile(envFile.absolutePath)

        assertTrue(result.contains("Security Error"))
        assertTrue(result.contains("blacklisted"))
    }

    @Test
    fun `writeFile then readFile round-trips swift sources when read guard enabled`() {
        // Regression: an iOS coder agent writes Foo.swift and a reviewer agent
        // reads it back. Pre-fix neither side worked — `swift` was in neither
        // ALLOWED_TEXT_EXTENSIONS nor the read guard's whitelist — leaving every
        // coder / reviewer / auditor step half-blind on iOS projects.
        val workingDir = createTempDirectory("safe-tools-working").toFile().also { tempDirs.add(it.toPath()) }
        val tool = SafeFileTools.createFromParameters(
            parameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(workingDir.absolutePath))
            ),
            readGuard = FileReadGuard(enabled = true)
        )

        val appleFiles = listOf(
            "ContentView.swift", "Bridge.m", "Bridge.mm", "Bridge.h",
            "Info.plist", "PrivacyInfo.xcprivacy", "Localizable.xcstrings",
            "Localizable.strings", "Plurals.stringsdict", "project.pbxproj",
            "Release.xcconfig", "App.entitlements", "App.xcscheme",
            "contents.xcworkspacedata", "Package.resolved", "Main.storyboard",
            "Launch.xib", "module.modulemap", "App.podspec"
        )

        for (name in appleFiles) {
            val target = File(workingDir, name)
            val written = tool.writeFile(target.absolutePath, "// $name")
            assertTrue(written.contains("Successfully wrote"), "write of $name failed: $written")

            val read = tool.readFile(target.absolutePath)
            assertTrue(read == "// $name", "read of $name returned: $read")
        }
    }

    @Test
    fun `readFile still denies binary build artifacts when read guard enabled`() {
        val workingDir = createTempDirectory("safe-tools-working").toFile().also { tempDirs.add(it.toPath()) }
        val artifact = File(workingDir, "App.ipa").apply { writeText("PK") }
        val tool = SafeFileTools.createFromParameters(
            parameters = listOf(
                ConfigurationParameter("working_dir", JsonPrimitive(workingDir.absolutePath))
            ),
            readGuard = FileReadGuard(enabled = true)
        )

        val result = tool.readFile(artifact.absolutePath)

        assertTrue(result.contains("Security Error"))
        assertTrue(result.contains("not allowed"))
    }
}
