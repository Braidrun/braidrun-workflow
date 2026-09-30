package com.fartech.agents.workflow

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A finished step must not look finished to the concurrent scheduler until its whole
 * completion — extract, publish_outputs, on_success/on_failure activation, and any
 * retry / repeat_until round still to come — is done.
 *
 * Execution f15d26b4 (2026-09-30): the guard step was already recorded as a satisfied
 * dependency when its parallel sibling finished, but its `extract` had not landed. The
 * scan that sibling triggered read `approved_count` as missing, skipped the execute step
 * as "condition not met" and started verification right away.
 *
 * Each test holds the guard step inside one such window through a public seam (the
 * output publisher, a retry backoff, a repeat_until round) while the sibling finishes, and
 * the last test replays the f15d26b4 shape many times on a multi-threaded dispatcher.
 */
class ConcurrentStepCompletionRaceTest {

    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("wf-completion-race").toFile()
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    /**
     * Runs each code step, and each claude_code_agent step, through the handler registered
     * under the `STEP:<name>` marker in its script or prompt.
     */
    private class StubSteps : SubprocessExecutor {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val handlers = ConcurrentHashMap<String, suspend (attempt: Int) -> String>()
        private val attempts = ConcurrentHashMap<String, AtomicInteger>()

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            val text = request.command.joinToString("\n") + request.stdin.orEmpty() + request.command
                .flatMap { listOf(File(it), File(request.workingDir, it)) }
                .filter { it.isFile && it.length() < 100_000 }
                .joinToString("\n") { it.readText() }
            val name = requireNotNull(Regex("STEP:(\\w+)").find(text)) { "no step marker in $text" }.groupValues[1]
            val attempt = attempts.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            events += "start:$name"
            val output = handlers[name]?.invoke(attempt) ?: "${name}_status=ok"
            events += "end:$name"
            if (output.startsWith("FAIL")) {
                return SubprocessExecutor.ExecResult(1, "", output, 1)
            }
            if ("claude" in request.command) {
                val line = buildJsonObject {
                    put("type", "result")
                    put("subtype", "success")
                    put("result", output)
                    put("session_id", "stub-$name-$attempt")
                }.toString()
                request.stdoutLineCallback?.invoke(line)
                return SubprocessExecutor.ExecResult(0, line + "\n", "", 1)
            }
            return SubprocessExecutor.ExecResult(0, "$output\n", "", 1)
        }
    }

    private fun workflow(yaml: String): WorkflowDefinition =
        WorkflowParser.parseYaml(yaml).copy(
            directoryIsolation = DirectoryIsolationConfig(
                enabled = true,
                baseDir = File(root, "runs").path,
                sharedSkillsDir = File(root, "skills").path,
                sharedCacheDir = File(root, "cache").path,
                sharedHistoryDir = File(root, "history").path
            )
        )

    /** One code step; [extra] blocks are written at step level. */
    private fun step(name: String, vararg extra: String): String = buildString {
        appendLine("  - step: $name")
        appendLine("    code:")
        appendLine("      language: bash")
        appendLine("      script: \"# STEP:$name\"")
        extra.forEach { appendLine(it.trimIndent().prependIndent("    ")) }
    }

    private fun document(name: String, vararg steps: String, agents: String = "{}"): String = """
        name: $name
        version: "1.0"
        agents: $agents
        variables:
          approval_status: approved
        concurrency:
          enabled: true
        workflow:
    """.trimIndent() + "\n" + steps.joinToString("")

    /** One claude_code_agent step on the `reviewer` agent. */
    private fun agentStep(name: String, vararg extra: String): String = buildString {
        appendLine("  - step: $name")
        appendLine("    agent: reviewer")
        appendLine("    input: \"STEP:$name apply the approved guard\"")
        extra.forEach { appendLine(it.trimIndent().prependIndent("    ")) }
    }

    private val extractCount = """
        extract:
          - variable: approved_count
            pattern: "approved_count=(\\d+)"
    """

    /** The f15d26b4 tail: execute gated on enforce's extract, verify after everything. */
    private fun approvalChain(vararg callbacks: String): Array<String> = arrayOf(
        step(
            "execute",
            "depends_on: [enforce]",
            "condition: 'approval_status == \"approved\" && approved_count > 0'"
        ),
        step("verify", "depends_on: [${(listOf("enforce", "notify", "execute") + callbacks).joinToString(", ")}]")
    )

    /** Every [first] event happened before the first [second] event. */
    private fun List<String>.assertBefore(first: String, second: String) {
        val a = lastIndexOf(first)
        val b = indexOf(second)
        assertTrue(a >= 0 && b >= 0 && a < b, "expected $first before $second: $this")
    }

    @Test
    fun `sibling finishing while a step publishes outputs neither starts its dependents nor skips its transition`(): Unit = runBlocking {
        val stub = StubSteps()
        val publishing = CompletableDeferred<Unit>()
        val executeStarted = CompletableDeferred<Unit>()
        stub.handlers["enforce"] = { "approved_count=1" }
        stub.handlers["notify"] = { publishing.await(); "notify_status=ok" }
        stub.handlers["execute"] = { executeStarted.complete(Unit); "execute_status=ok" }
        val publisher = object : WorkflowOutputPublisher {
            override suspend fun publish(output: PublishedWorkflowOutputPayload) {
                stub.events += "publish:start"
                publishing.complete(Unit)
                // notify finishes now. Give the scheduler time to react to it; a scheduler that
                // treats enforce as done starts execute right away and ends the wait early.
                withTimeoutOrNull(500) { executeStarted.await() }
                stub.events += "publish:end"
            }
        }
        val definition = workflow(
            document(
                "completion-window-publish",
                step(
                    "enforce",
                    extractCount,
                    """
                    publish_outputs:
                      - name: guard
                        source: "{{var:approved_count}}"
                    on_success:
                      - next: announce
                    """
                ),
                step("notify"),
                step("announce"),
                *approvalChain()
            )
        )

        val result = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            workflowOutputPublisher = publisher,
            codeStepExecutor = stub
        ).execute(definition)

        val events = stub.events.toList()
        assertTrue(result.success, "${result.error}: $events")
        assertTrue("start:announce" in events, "on_success target was skipped: $events")
        events.assertBefore("publish:end", "start:execute")
        events.assertBefore("publish:end", "start:announce")
        events.assertBefore("end:execute", "start:verify")
    }

    @Test
    fun `dependents wait for the final repeat_until round instead of reading the first round`(): Unit = runBlocking {
        val stub = StubSteps()
        val secondRound = CompletableDeferred<Unit>()
        val verifyStarted = CompletableDeferred<Unit>()
        stub.handlers["enforce"] = { attempt ->
            if (attempt == 1) {
                "enforce_phase=collecting"
            } else {
                // Round one is recorded by now. notify finishes; a scheduler that treats the
                // first round as the step's result skips execute and starts verify at once.
                secondRound.complete(Unit)
                withTimeoutOrNull(500) { verifyStarted.await() }
                "approved_count=1"
            }
        }
        stub.handlers["notify"] = { secondRound.await(); "notify_status=ok" }
        stub.handlers["verify"] = { verifyStarted.complete(Unit); "verify_status=ok" }
        val definition = workflow(
            document(
                "completion-window-repeat-until",
                agentStep(
                    "enforce",
                    extractCount,
                    """
                    repeat_until:
                      condition: "approved_count > 0"
                      max_iterations: 2
                    on_success:
                      - next: announce
                    """
                ),
                step("notify"),
                step("announce"),
                *approvalChain(),
                agents = "{ reviewer: { type: claude_code_agent } }"
            )
        )

        val result = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = listOf(
                ConfigurationParameter("anthropic_api_key", JsonPrimitive("sk-test-anthropic")),
                ConfigurationParameter("user_id", JsonPrimitive("race-user"))
            ),
            enableMonitoring = false,
            codeStepExecutor = stub
        ).execute(definition)

        val events = stub.events.toList()
        assertTrue(result.success, "${result.error}: $events")
        assertTrue("start:execute" in events, "execute was skipped: $events")
        assertTrue("start:announce" in events, "on_success target was skipped: $events")
        assertEquals(2, events.count { it == "end:enforce" }, events.toString())
        events.assertBefore("end:enforce", "start:execute")
        events.assertBefore("end:enforce", "start:announce")
        events.assertBefore("end:execute", "start:verify")
    }

    @Test
    fun `sibling finishing during a retry backoff does not fail the dependents of the retried step`(): Unit = runBlocking {
        val stub = StubSteps()
        val secondAttempt = CompletableDeferred<Unit>()
        stub.handlers["enforce"] = { attempt ->
            if (attempt == 1) {
                "FAIL transient"
            } else {
                // Attempt one's failure is recorded by now. notify finishes; a scheduler that
                // reads it as the step's outcome fails execute as "Dependencies failed".
                secondAttempt.complete(Unit)
                delay(300)
                "approved_count=1"
            }
        }
        stub.handlers["notify"] = { secondAttempt.await(); "notify_status=ok" }
        val definition = workflow(
            document(
                "completion-window-retry",
                step(
                    "enforce",
                    extractCount,
                    """
                    retry:
                      max_attempts: 2
                      backoff: constant
                      initial_delay: 10
                    """
                ),
                step("notify"),
                *approvalChain()
            )
        )

        val result = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = stub
        ).execute(definition)

        val events = stub.events.toList()
        assertTrue(result.success, "${result.error}: $events")
        assertTrue("start:execute" in events, "execute did not run: $events")
        assertEquals(2, events.count { it == "end:enforce" }, events.toString())
        events.assertBefore("end:execute", "start:verify")
    }

    @Test
    fun `parallel guard and callbacks finishing together never skip the approved execute step`(): Unit =
        runBlocking(Dispatchers.Default) {
            // The f15d26b4 shape on a multi-threaded dispatcher, with extra callbacks to multiply
            // the scans that can land between a step's result and its extract.
            val callbacks = (1..6).map { "notify_$it" }
            val definition = workflow(
                document(
                    "completion-window-stress",
                    step("enforce", extractCount),
                    step("notify"),
                    *callbacks.map { step(it) }.toTypedArray(),
                    *approvalChain(*callbacks.toTypedArray())
                )
            )
            val failures = mutableListOf<String>()
            repeat(STRESS_RUNS) { run ->
                val stub = StubSteps()
                stub.handlers["enforce"] = { delay(20); "approved_count=1" }
                (callbacks + "notify").forEach { name -> stub.handlers[name] = { delay(20); "${name}_status=ok" } }
                val result = WorkflowExecutor(
                    httpAccess = HttpAccess(),
                    baseParameters = emptyList(),
                    enableMonitoring = false,
                    codeStepExecutor = stub
                ).execute(definition)
                val events = stub.events.toList()
                if (!result.success || "start:execute" !in events ||
                    events.indexOf("end:execute") > events.indexOf("start:verify")
                ) {
                    failures += "run $run: ${result.error} $events"
                }
            }
            assertTrue(failures.isEmpty(), "${failures.size}/$STRESS_RUNS runs skipped or misordered execute:\n${failures.take(3).joinToString("\n")}")
        }

    private companion object {
        const val STRESS_RUNS = 200
    }
}
