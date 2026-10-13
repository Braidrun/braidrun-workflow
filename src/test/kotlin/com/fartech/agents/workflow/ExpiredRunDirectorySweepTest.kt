package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A shared base directory holding (a) a stale flat-layout run, (b) an owner directory
 * with a fresh nested run and (c) an owner directory with only stale nested runs. The
 * owner directories' own mtimes are stale: they only move when a run directory is
 * created directly inside them. Before the fix the sweep deleted all three.
 */
class ExpiredRunDirectorySweepTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = System.currentTimeMillis()
    private val stale = now - 100L * 3_600_000L
    private val staleFlatRun = "3bacdfad-0000-4000-8000-000000000001"
    private val freshOwner = "37fd8466-ebbf-4cb6-9dfb-36e80cdd648a"
    private val freshNestedRun = "aaaaaaaa-0000-4000-8000-000000000002"
    private val staleOwner = "2b443db5-0000-4000-8000-000000000003"
    private val staleNestedRun = "bbbbbbbb-0000-4000-8000-000000000004"

    private fun baseDir(): File = tempDir.resolve("runs").toFile().apply {
        File(this, "$staleFlatRun/output/report.json").apply { parentFile.mkdirs(); writeText("{}") }
        File(this, "$freshOwner/$freshNestedRun/output/app.txt").apply { parentFile.mkdirs(); writeText("running") }
        File(this, "$staleOwner/$staleNestedRun/output/app.txt").apply { parentFile.mkdirs(); writeText("old") }
        File(this, staleFlatRun).setLastModified(stale)
        File(this, "$staleOwner/$staleNestedRun").setLastModified(stale)
        File(this, freshOwner).setLastModified(stale)
        File(this, staleOwner).setLastModified(stale)
    }

    private fun flat(base: File, hours: Int = 72) = DirectoryIsolationConfig(
        baseDir = base.absolutePath,
        workingDirPattern = "{base_dir}/{execution_id}/{step_name}",
        outputDirPattern = "{base_dir}/{execution_id}/output",
        persistenceDirPattern = "{base_dir}/{execution_id}/.snapshots",
        cleanupAfterHours = hours
    )

    @Test
    fun `flat sweep deletes the stale run and keeps both owner directories`() {
        val base = baseDir()
        val deleted = ExpiredRunDirectorySweep.sweep(flat(base), now, emptySet())
        assertEquals(listOf(staleFlatRun), deleted.map { it.path.name })
        assertTrue(deleted.single().ageHours >= 99)
        assertFalse(File(base, staleFlatRun).exists())
        assertTrue(File(base, "$freshOwner/$freshNestedRun/output/app.txt").exists())
        assertTrue(File(base, "$staleOwner/$staleNestedRun/output/app.txt").exists())
    }

    @Test
    fun `keep marker, hidden entries, running executions and fresh runs survive`() {
        val base = baseDir()
        val marked = File(base, "cccccccc-0000-4000-8000-000000000005").apply { mkdirs() }
        File(marked, DirectoryIsolationConfig.KEEP_MARKER_FILE).writeText("")
        val emptyOwner = File(base, "dddddddd-0000-4000-8000-000000000006").apply { mkdirs() }
        File(emptyOwner, DirectoryIsolationConfig.KEEP_MARKER_FILE).writeText("")
        val hidden = File(base, ".prompt_cache").apply { mkdirs() }
        val running = File(base, "eeeeeeee-0000-4000-8000-000000000007").apply { mkdirs() }
        val fresh = File(base, "ffffffff-0000-4000-8000-000000000008").apply { mkdirs() }
        listOf(marked, emptyOwner, hidden, running).forEach { it.setLastModified(stale) }

        ExpiredRunDirectorySweep.sweep(flat(base), now, setOf(running.name))

        listOf(marked, emptyOwner, hidden, running, fresh).forEach { assertTrue(it.exists(), "${it.name} must survive") }
        assertFalse(File(base, staleFlatRun).exists())
    }

    @Test
    fun `owner-scoped and mixed layouts are never swept`() {
        val base = baseDir()
        val ownerScoped = DirectoryIsolationConfig(baseDir = base.absolutePath, cleanupAfterHours = 72)
        assertTrue(ExpiredRunDirectorySweep.sweep(ownerScoped, now, emptySet()).isEmpty())
        val mixed = flat(base).copy(outputDirPattern = "{base_dir}/{user_id}/{execution_id}/output")
        assertTrue(ExpiredRunDirectorySweep.sweep(mixed, now, emptySet()).isEmpty())
        assertTrue(File(base, staleFlatRun).exists())
        assertTrue(File(base, staleOwner).exists())
    }

    @Test
    fun `a link is removed, never followed`() {
        val base = baseDir()
        val outside = tempDir.resolve("outside").toFile().apply { mkdirs() }
        val kept = File(outside, "kept.txt").apply { writeText("x") }
        Files.createSymbolicLink(File(base, "$staleFlatRun/linked").toPath(), outside.toPath())
        File(base, staleFlatRun).setLastModified(stale)
        ExpiredRunDirectorySweep.sweep(flat(base), now, emptySet())
        assertFalse(File(base, staleFlatRun).exists())
        assertTrue(kept.exists())
    }

    @Test
    fun `a completed flat run triggers the guarded sweep`(): Unit = runBlocking {
        val base = baseDir()
        val workflow = WorkflowDefinition(
            name = "flat",
            agents = emptyMap(),
            workflow = listOf(WorkflowStep(step = "noop", code = CodeStepConfig(language = "bash", script = "true"))),
            directoryIsolation = flat(base)
        )
        WorkflowExecutor(HttpAccess(), emptyList(), enableMonitoring = false)
            .execute(workflow, externalExecutionId = "99999999-0000-4000-8000-000000000009")
        assertFalse(File(base, staleFlatRun).exists())
        assertTrue(File(base, "$freshOwner/$freshNestedRun/output/app.txt").exists())
        assertTrue(File(base, "$staleOwner/$staleNestedRun/output/app.txt").exists())
    }
}
