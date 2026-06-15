package com.fartech.agents.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 11 regression — `searchFiles` must reject pathological regex patterns instead
 * of pinning the JVM thread for hours on catastrophic backtracking.
 */
class FileManagementToolsSearchSecurityTest {

    @Test
    fun `oversized pattern is rejected without running the search`(@TempDir tmp: Path) {
        val tools = FileManagementTools(allowedDirectories = listOf(tmp))
        Files.writeString(tmp.resolve("a.txt"), "x")
        val giant = "x".repeat(FileManagementTools.MAX_SEARCH_PATTERN_LENGTH + 1)
        val result = tools.searchFiles(directory = tmp.toString(), pattern = "regex:$giant")
        assertTrue(result.startsWith("Error: pattern too long"), "Got: $result")
    }

    @Test
    fun `catastrophic backtracking regex returns within wall-clock budget`(@TempDir tmp: Path) {
        // Match against a long-ish filename — long enough that `(a+)+b` against a string
        // of 50 a's followed by 'X' takes years naively.
        val tools = FileManagementTools(allowedDirectories = listOf(tmp))
        val bait = tmp.resolve("a".repeat(50) + "X")
        Files.writeString(bait, "x")

        val start = System.currentTimeMillis()
        val result = tools.searchFiles(
            directory = tmp.toString(),
            pattern = "regex:(a+)+b"
        )
        val elapsedMs = System.currentTimeMillis() - start
        // Generous: REGEX_MATCH_WALL_CLOCK_MS * a few files. Anything below ~10s is a pass —
        // the unprotected baseline runs for tens of minutes.
        assertTrue(elapsedMs < 10_000, "searchFiles took ${elapsedMs}ms (expected < 10s)")
        // Either no matches or a clean error string — neither matters as long as we exited.
        assertNotNull(result)
    }

    @Test
    fun `invalid regex returns clean error not exception`(@TempDir tmp: Path) {
        val tools = FileManagementTools(allowedDirectories = listOf(tmp))
        Files.writeString(tmp.resolve("a.txt"), "x")
        val result = tools.searchFiles(
            directory = tmp.toString(),
            pattern = "regex:["  // unterminated character class
        )
        assertTrue(result.startsWith("Error: invalid regex pattern"), "Got: $result")
    }

    @Test
    fun `glob pattern still works`(@TempDir tmp: Path) {
        val tools = FileManagementTools(allowedDirectories = listOf(tmp))
        Files.writeString(tmp.resolve("foo.txt"), "x")
        Files.writeString(tmp.resolve("bar.log"), "x")
        val result = tools.searchFiles(directory = tmp.toString(), pattern = "*.txt")
        assertTrue(result.contains("foo.txt"), "Expected to find foo.txt, got: $result")
        assertFalse(result.contains("bar.log"))
    }
}
