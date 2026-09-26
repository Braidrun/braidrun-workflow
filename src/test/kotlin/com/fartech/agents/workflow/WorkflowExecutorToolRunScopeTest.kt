package com.fartech.agents.workflow

import com.fartech.agents.tools.BrowserTools
import com.fartech.agents.tools.FakeBrowserWorld
import com.fartech.agents.tools.ToolRunScope
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [WorkflowExecutor.execute] is the host that scopes run state: every tool call of an execution runs in
 * [ToolRunScope] = its execution id, and the execution's browser contexts close however it ends.
 * A `code:` step whose injected [SubprocessExecutor] calls the browser tools stands in for an agent step.
 */
class WorkflowExecutorToolRunScopeTest {

    @TempDir
    lateinit var tempDir: Path

    private val world = FakeBrowserWorld()

    @BeforeEach
    fun setUp() {
        BrowserTools.closeAll()
        ToolRunScope.resetForTests()
        BrowserTools.browserLauncherForTests = { world.browser }
    }

    @AfterEach
    fun tearDown() {
        BrowserTools.closeAll()
        BrowserTools.browserLauncherForTests = null
        ToolRunScope.resetForTests()
    }

    private class StepBody(private val body: suspend () -> Unit) : SubprocessExecutor {
        val scopes: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            scopes += ToolRunScope.currentId()
            body()
            return SubprocessExecutor.ExecResult(exitCode = 0, stdout = "ok", stderr = "", durationMs = 1)
        }
    }

    private fun executor(step: StepBody) = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = emptyList(),
        enableMonitoring = false,
        codeStepExecutor = step,
    )

    private fun workflow(name: String) = WorkflowDefinition(
        name = name,
        agents = emptyMap(),
        directoryIsolation = DirectoryIsolationConfig(
            enabled = true,
            baseDir = tempDir.resolve("runs").toString(),
            sharedSkillsDir = tempDir.resolve("skills").toString(),
            sharedCacheDir = tempDir.resolve("cache").toString(),
            sharedHistoryDir = tempDir.resolve("history").toString(),
        ),
        workflow = listOf(WorkflowStep(step = "browse", code = CodeStepConfig(language = "bash", script = "true"))),
    )

    @Test
    fun `an execution's tool calls run in its own scope and its contexts close when it completes`() = runBlocking {
        val step = StepBody {
            BrowserTools(emptyList()).browser_navigate("https://a.example/")
            assertEquals(1, BrowserTools.openContextCount())
        }

        val result = executor(step).execute(workflow("browse-ok"), externalExecutionId = "exec-ok")

        assertTrue(result.success, "${result.error}")
        assertEquals(listOf<String?>("exec-ok"), step.scopes.toList())
        assertEquals(0, BrowserTools.openContextCount())
        assertTrue(world.contexts.single().closed)
    }

    @Test
    fun `contexts close when the execution fails`() = runBlocking {
        val step = StepBody {
            BrowserTools(emptyList()).browser_navigate("https://a.example/")
            throw IllegalStateException("step exploded")
        }

        val outcome = runCatching { executor(step).execute(workflow("browse-fail"), externalExecutionId = "exec-fail") }

        assertFalse(outcome.getOrNull()?.success ?: false, "the execution must not succeed: $outcome")
        assertEquals(0, BrowserTools.openContextCount())
        assertTrue(world.contexts.single().closed)
    }

    @Test
    fun `contexts close when the execution is cancelled`() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val step = StepBody {
            BrowserTools(emptyList()).browser_navigate("https://a.example/")
            opened.complete(Unit)
            awaitCancellation()
        }
        val run = launch { executor(step).execute(workflow("browse-cancel"), externalExecutionId = "exec-cancel") }
        opened.await()

        run.cancelAndJoin()

        assertEquals(0, BrowserTools.openContextCount())
        assertTrue(world.contexts.single().closed)
    }

    @Test
    fun `concurrent executions cannot reach each other's contexts`() = runBlocking {
        val aOpened = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<Unit>()
        var seenByB = ""
        val a = StepBody {
            BrowserTools(emptyList()).browser_navigate("https://bank.example/account")
            aOpened.complete(Unit)
            bDone.await()
        }
        val b = StepBody {
            aOpened.await()
            seenByB = BrowserTools(emptyList()).browser_get_content()
            bDone.complete(Unit)
        }

        val runA = launch { executor(a).execute(workflow("tenant-a"), externalExecutionId = "exec-a") }
        val runB = launch { executor(b).execute(workflow("tenant-b"), externalExecutionId = "exec-b") }
        runA.join()
        runB.join()

        assertTrue(seenByB.startsWith("✅"), seenByB)
        assertFalse(seenByB.contains("bank.example"), seenByB)
        assertEquals(0, BrowserTools.openContextCount())
    }
}
