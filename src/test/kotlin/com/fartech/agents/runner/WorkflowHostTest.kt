package com.fartech.agents.runner

import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class WorkflowHostTest {
    internal class Host(private val directory: Path) : WorkflowHost {
        override val binding = WorkflowHostBinding("owner", "actor", "billing", "root", "runner", "instance", 0, "attempt", "a".repeat(64), "b".repeat(64))
        val checks: MutableList<Pair<WorkflowHostScope, String>> = java.util.concurrent.CopyOnWriteArrayList()
        val checkpoints: MutableList<WorkflowHostCheckpoint> = java.util.concurrent.CopyOnWriteArrayList()
        val updates: MutableList<WorkflowMonitorUpdate> = java.util.concurrent.CopyOnWriteArrayList()
        val invocations: MutableList<Map<String, String>> = java.util.concurrent.CopyOnWriteArrayList()
        val approvals: MutableList<ApprovalRequest> = java.util.concurrent.CopyOnWriteArrayList()
        var child: WorkflowDefinition? = null
        var dynamicAllowed = true
        var block = false
        val entered = CompletableDeferred<Unit>()
        override val approvalHandler = object : ApprovalHandler {
            override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
                approvals.add(request); return ApprovalDecision.APPROVED_AS_IS
            }
        }
        override val outputPublisher = object : WorkflowOutputPublisher { override suspend fun publish(output: PublishedWorkflowOutputPayload) {} }
        override val outputResolver = object : WorkflowOutputResolver { override suspend fun read(request: WorkflowOutputReadRequest) = WorkflowOutputReadResult("source-exec", "source-workflow", mapOf("value" to "inherited")) }
        override val subprocessExecutor = object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                invocations.add(request.env); entered.complete(Unit)
                if (block) awaitCancellation()
                return SubprocessExecutor.ExecResult(0, "done", "", 1)
            }
        }
        override val workflowResolver: WorkflowResolver get() = InMemoryWorkflowResolver(byName = child?.let { mapOf(it.name to it) }.orEmpty())
        override suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope) { checks.add(scope to "admit") }
        override suspend fun beforeOperation(scope: WorkflowHostScope, operation: String) { checks.add(scope to operation) }
        override fun userInteraction(scope: WorkflowHostScope): UserInteractionHandler = object : UserInteractionHandler {
            override fun sendMessage(message: String) {}
            override suspend fun askQuestion(question: String, timeoutMs: Long) = "reply"
        }
        override fun dynamicWorkflow(path: String, scope: WorkflowHostScope): WorkflowDefinition {
            check(dynamicAllowed) { "Unapproved dynamic hash" }; return requireNotNull(child)
        }
        override fun monitor(update: WorkflowMonitorUpdate) { updates.add(update) }
        override suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint) { checkpoints.add(checkpoint) }
        fun isolation() = DirectoryIsolationConfig(baseDir = directory.resolve("runs").toString(), sharedCacheDir = directory.resolve("cache").toString(), sharedHistoryDir = directory.resolve("history").toString(), sharedSkillsDir = directory.resolve("skills").toString())
        fun workflow(name: String, approval: Boolean = false) = WorkflowDefinition(name, agents = emptyMap(), directoryIsolation = isolation(), workflow = listOf(
            WorkflowStep("code", code = CodeStepConfig("bash", "echo done"), manualApproval = if (approval) ManualApprovalConfig(true) else null)
        ))
    }
    /**
     * Concurrent fixture over [Host]: human decisions come from [decide], and a step named in [hold] starts its
     * body only once that gate opens. [bodies] lists every step body that started (after any approval).
     */
    internal class ConcurrentHost(
        val base: Host, private val decide: suspend (ApprovalRequest) -> ApprovalDecision,
        private val hold: Map<String, CompletableDeferred<Unit>> = emptyMap(), override val binding: WorkflowHostBinding = base.binding
    ) : WorkflowHost by base {
        val requests: MutableList<ApprovalRequest> = java.util.concurrent.CopyOnWriteArrayList()
        val bodies: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        override val approvalHandler = object : ApprovalHandler {
            override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision { requests.add(request); return decide(request) }
        }
        override suspend fun beforeOperation(scope: WorkflowHostScope, operation: String) {
            base.beforeOperation(scope, operation)
            if (operation == "step_after_approval") { bodies.add(requireNotNull(scope.stepName)); hold[scope.stepName]?.await() }
        }
        /** slow and review run side by side; after needs both. */
        fun workflow() = WorkflowDefinition("concurrent", agents = emptyMap(), directoryIsolation = base.isolation(), concurrency = ConcurrencyConfig(true, 2), workflow = listOf(
            WorkflowStep("slow", code = CodeStepConfig("bash", "echo slow")),
            WorkflowStep("review", code = CodeStepConfig("bash", "echo reviewed"), manualApproval = ManualApprovalConfig(true, timeout = 3600)),
            WorkflowStep("after", code = CodeStepConfig("bash", "echo after"), dependsOn = listOf("slow", "review"))
        ))
    }
    @Test fun `runner admission bounds workflow concurrency and gates step-level parallel`() {
        assertTrue("workflow.concurrent.v1" in RunnerHostSession.CAPABILITIES)
        val admission = RunnerAdmission(InMemoryWorkflowResolver(), RunnerHostSession.CAPABILITIES)
        fun code(name: String) = WorkflowStep(name, code = CodeStepConfig("bash", "echo $name"))
        fun concurrent(max: Int, vararg names: String) = WorkflowDefinition("concurrent", agents = emptyMap(), concurrency = ConcurrencyConfig(true, max), workflow = names.map(::code))
        assertTrue("workflow.concurrent.v1" in admission.inspect(concurrent(4, "a", "b")))
        assertTrue("workflow.concurrent.v1" in admission.inspect(concurrent(8, "a", "b")))
        assertFailsWith<IllegalArgumentException>("unlimited") { admission.inspect(concurrent(0, "a", "b")) }
        assertFailsWith<IllegalArgumentException>("above the Runner bound") { admission.inspect(concurrent(9, "a", "b")) }
        // A step's credential leases are retired by step name and its derived "<step>:" scopes; siblings cannot share that prefix.
        assertFailsWith<IllegalArgumentException> { admission.inspect(concurrent(2, "a", "a:b")) }
        val agent = AgentDefinition(tools = emptyList())
        fun parallel(tasks: Int, max: Int?) = WorkflowDefinition("fan-out", agents = mapOf("worker" to agent), workflow = listOf(
            WorkflowStep("fan", agent = "worker", input = "fan out", parallel = ParallelExecution(List(tasks) { "task $it" }, maxParallel = max))))
        assertTrue("workflow.concurrent.v1" in admission.inspect(parallel(3, null)))
        assertTrue("workflow.concurrent.v1" in admission.inspect(parallel(20, 8)))
        assertFailsWith<IllegalArgumentException> { admission.inspect(parallel(9, null)) }
        val withoutConcurrency = RunnerAdmission(InMemoryWorkflowResolver(), RunnerHostSession.CAPABILITIES - "workflow.concurrent.v1")
        assertFailsWith<IllegalArgumentException> { withoutConcurrency.inspect(parallel(2, null)) }
        assertFailsWith<IllegalArgumentException> { withoutConcurrency.inspect(concurrent(2, "a", "b")) }
    }
    @Test fun `concurrent savepoints are resumable only when every running step waits for a human`(@TempDir dir: Path) = runBlocking {
        val slowGate = CompletableDeferred<Unit>(); val decision = CompletableDeferred<ApprovalDecision>()
        val host = ConcurrentHost(Host(dir), decide = { decision.await() }, hold = mapOf("slow" to slowGate))
        val running = async(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(host.workflow()) }
        try {
            withTimeout(5000) { while (host.requests.isEmpty() || "slow" !in host.bodies) delay(10) }
            delay(100)
            assertTrue(host.base.checkpoints.none { it.safeToResume }, "A sibling still running makes every savepoint unsafe")
            slowGate.complete(Unit)
            val parked = withTimeout(5000) { while (host.base.checkpoints.none { it.safeToResume }) delay(10); host.base.checkpoints.first { it.safeToResume } }
            val request = host.requests.single()
            assertEquals(setOf("slow"), parked.state.preservedStepResults.keys)
            assertEquals(mapOf("review" to request.approvalId), parked.state.pendingApprovalIds)
            assertEquals(mapOf("review" to request.requestedAt), parked.state.pendingApprovalRequestedAt)
            assertEquals(listOf(request), parked.pendingApprovals)
            decision.complete(ApprovalDecision.APPROVED_AS_IS)
            val result = withTimeout(5000) { running.await() }
            assertTrue(result.success, result.error)
            assertEquals(listOf("after", "review", "slow"), host.bodies.sorted())
            val last = host.base.checkpoints.last()
            assertTrue(last.safeToResume); assertTrue(last.pendingApprovals.isEmpty())
            assertEquals(setOf("slow", "review", "after"), last.state.preservedStepResults.keys)
        } finally { running.cancel() }
    }
    @Test fun `concurrent approvals park together and a retrying wait is never quiescent`(@TempDir dir: Path) = runBlocking {
        val decision = CompletableDeferred<ApprovalDecision>()
        val host = ConcurrentHost(Host(dir), decide = { decision.await() })
        fun review(name: String, retry: RetryConfig? = null) = WorkflowStep(name, code = CodeStepConfig("bash", "echo $name"), manualApproval = ManualApprovalConfig(true, timeout = 3600), retry = retry, idempotent = retry != null)
        val both = WorkflowDefinition("approvals", agents = emptyMap(), directoryIsolation = host.base.isolation(), concurrency = ConcurrencyConfig(true, 3),
            workflow = listOf(review("review_a"), review("review_b")))
        val running = async(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(both) }
        try {
            val parked = withTimeout(5000) {
                while (host.base.checkpoints.none { it.safeToResume && it.pendingApprovals.size == 2 }) delay(10)
                host.base.checkpoints.first { it.safeToResume && it.pendingApprovals.size == 2 }
            }
            assertEquals(host.requests.map { it.approvalId }.toSet(), parked.pendingApprovals.map { it.approvalId }.toSet())
            assertEquals(setOf("review_a", "review_b"), parked.state.pendingApprovalIds.keys)
            assertTrue(parked.state.preservedStepResults.isEmpty())
            decision.complete(ApprovalDecision.APPROVED_AS_IS)
            assertTrue(withTimeout(5000) { running.await() }.success)
        } finally { running.cancel() }

        // A retrying step may already hold an attempt's result: its wait keeps the run non-quiescent.
        val retrying = ConcurrentHost(Host(dir.resolve("retrying")), decide = { awaitCancellation() })
        val mixed = WorkflowDefinition("retrying", agents = emptyMap(), directoryIsolation = retrying.base.isolation(), concurrency = ConcurrencyConfig(true, 2),
            workflow = listOf(review("plain"), review("again", RetryConfig(maxAttempts = 2))))
        val second = async(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = retrying).execute(mixed) }
        try {
            withTimeout(5000) { while (retrying.requests.size < 2) delay(10) }
            delay(200)
            assertTrue(retrying.base.checkpoints.none { it.safeToResume })
            assertTrue(retrying.base.checkpoints.any { it.pendingApprovals.isNotEmpty() })
        } finally { second.cancelAndJoin() }
    }
    @Test fun `static and dynamic children inherit root identity approvals monitoring and supervision`(@TempDir dir: Path) = runBlocking {
        val host = Host(dir); val child = host.workflow("child", true); host.child = child
        val root = WorkflowDefinition("parent", agents = emptyMap(), directoryIsolation = host.isolation(), workflow = listOf(WorkflowStep("nested", subWorkflow = SubWorkflowConfig(name = "child"))))
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(root)
        assertTrue(result.success, result.error)
        assertEquals("root__nested__call_1", host.approvals.single().executionId)
        assertEquals("root", host.invocations.single()["WF_ROOT_EXECUTION_ID"])
        assertTrue(host.updates.any { it.executionId == "root__nested__call_1" && it.type == "step_started" })
        assertEquals(mapOf("nested" to 1L), host.checkpoints.last { it.scope.executionId == "root" && it.safeToResume }.state.childInvocationCounts)
        val tools = WorkflowTools(HttpAccess(), emptyList(), NestedWorkflowRuntime(host = host), WorkflowHostScope("root", "root", stepName = "agent"))
        assertTrue(tools.executeWorkflow("authorized.yaml").contains("success", true))
        assertTrue(host.approvals.last().executionId.startsWith("root__agent_dynamic_"))
        assertEquals("root", host.invocations.last()["WF_ROOT_EXECUTION_ID"])
        assertEquals("root", host.invocations.last()["WF_PARENT_EXECUTION_ID"])
        val before = host.invocations.size; host.dynamicAllowed = false
        assertTrue(tools.executeWorkflow("changed.yaml").contains("refused")); assertEquals(before, host.invocations.size)
        assertFailsWith<IllegalArgumentException> { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(root, externalExecutionId = "different-root") }
    }
    @Test fun `state machine child reentry needs a new approval and preserves invocation counts`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir); base.child = base.workflow("child", true)
        val second = CompletableDeferred<ApprovalRequest>(); val requests = mutableListOf<ApprovalRequest>()
        val host = object : WorkflowHost by base {
            override val approvalHandler = object : ApprovalHandler {
                override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
                    requests.add(request)
                    if (requests.size == 1) return ApprovalDecision.APPROVED_AS_IS
                    second.complete(request); awaitCancellation()
                }
            }
        }
        val root = WorkflowDefinition("loop-parent", agents = emptyMap(), directoryIsolation = base.isolation(), workflow = listOf(
            WorkflowStep("machine", stateMachine = StateMachineConfig(states = mapOf(
                "child" to StateDefinition("child", stepConfig = StateStepConfig(subWorkflow = SubWorkflowConfig(name = "child")), transitions = listOf(StateTransition("complete", "control"))),
                "control" to StateDefinition("control", transitions = listOf(StateTransition("enter", "child")))
            ), initialState = "child", maxTransitions = 5))
        ))
        val running = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(root) }
        withTimeout(2000) { second.await() }
        assertEquals(1, base.invocations.size, "Second side effect must wait for its own human decision")
        assertNotEquals(requests[0].approvalId, requests[1].approvalId)
        assertTrue(requests[0].executionId.endsWith("__call_1")); assertTrue(requests[1].executionId.endsWith("__call_2"))
        running.cancelAndJoin()
        // In-flight FSM snapshots stay unsafe; the existing root boundary test below verifies persisted counters.
    }
    @Test fun `typed savepoint resumes without repeating a preserved effect`(@TempDir dir: Path) = runBlocking {
        val host = Host(dir); val workflow = host.workflow("resume")
        val first = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow, mapOf("count" to 7, "list" to listOf("a", "b")))
        assertTrue(first.success)
        val checkpoint = host.checkpoints.last { it.safeToResume }
        assertEquals(7, checkpoint.state.variables["count"])
        val resumed = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow, resumeState = checkpoint.state)
        assertTrue(resumed.success); assertEquals(1, host.invocations.size); assertEquals(7, resumed.variables["count"])
    }
    @Test fun `repeated interruption of first resumed operation retains a safe entry checkpoint`(@TempDir dir: Path) = runBlocking {
        var restored = WorkflowResumeState(variables = mapOf("count" to 7))
        repeat(2) {
            val host = Host(dir); host.block = true
            val running = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host)
                .execute(host.workflow("repeat-resume"), resumeState = restored) }
            host.entered.await()
            val entry = host.checkpoints.first()
            assertTrue(entry.safeToResume)
            assertEquals(7, entry.state.variables["count"])
            assertTrue(entry.state.preservedStepResults.isEmpty(), "An interrupted operation must not become completed")
            running.cancelAndJoin()
            assertFalse(host.checkpoints.last().safeToResume, "The live operation remains unsafe")
            assertEquals(1, host.invocations.size)
            restored = entry.state
        }
    }
    @Test fun `cancellation propagates to supervised operation and never starts next step`(@TempDir dir: Path) = runBlocking {
        val host = Host(dir); host.block = true
        val workflow = host.workflow("cancel").let { it.copy(workflow = it.workflow + WorkflowStep("later", code = CodeStepConfig("bash", "echo later"))) }
        val running = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow) }
        host.entered.await(); running.cancel(); running.join()
        assertEquals(1, host.invocations.size)
        assertFalse(host.checkpoints.last().safeToResume)
        assertTrue(host.updates.any { it.type == "execution_cancelled" })
    }
    private fun loopStep(items: Int, parallel: Boolean = true, maxParallel: Int? = 2, dependsOn: List<String> = emptyList()) = WorkflowStep("fanout", idempotent = true, dependsOn = dependsOn,
        code = CodeStepConfig("bash", "echo {{var:item}}"),
        iterateOver = IterateOverConfig(source = (0 until items).joinToString(",") { "i$it" }, delimiter = ",", itemVariable = "item", parallel = parallel, maxParallel = maxParallel))
    private class LoopHost(val base: Host, executor: SubprocessExecutor? = null) : WorkflowHost by base {
        val launched = java.util.concurrent.ConcurrentLinkedQueue<Map<String, String>>()
        private val executor = executor ?: object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult { launched.add(request.env); return SubprocessExecutor.ExecResult(0, "done", "", 1) }
        }
        /** Items report from several threads; the base host's plain list is not thread-safe. */
        override fun monitor(update: WorkflowMonitorUpdate) { synchronized(base) { base.updates.add(update) } }
        fun updates() = synchronized(base) { base.updates.toList() }
        val ended = java.util.concurrent.CopyOnWriteArrayList<WorkflowHostScope>()
        val ready = java.util.concurrent.CopyOnWriteArrayList<WorkflowHostScope>()
        val loops = java.util.concurrent.CopyOnWriteArrayList<Triple<WorkflowHostScope, Int, Int>>()
        var refuseLoop: String? = null
        override val subprocessExecutor get() = executor
        override fun admitLoop(scope: WorkflowHostScope, step: WorkflowStep, items: Int, concurrency: Int) {
            loops.add(Triple(scope, items, concurrency)); refuseLoop?.let { throw IllegalArgumentException(it) }
        }
        override fun occurrenceEnded(scope: WorkflowHostScope) { ended.add(scope) }
        override suspend fun codeArtifactsReady(workflow: WorkflowDefinition, step: WorkflowStep, scope: WorkflowHostScope, success: Boolean) { ready.add(scope) }
    }
    private fun occurrences(n: Int) = (0 until n).map { "iterate:$it" }.toSet()

    @Test fun `parallel code items run as their own occurrences and each one ends on its own`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir); val host = LoopHost(base)
        val workflow = WorkflowDefinition("loop", agents = emptyMap(), directoryIsolation = base.isolation(), workflow = listOf(loopStep(4)))
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
        assertTrue(result.success, result.error)
        assertEquals(listOf(4 to 2), host.loops.map { it.second to it.third }); assertEquals("fanout", host.loops.single().first.stepName)
        assertEquals(occurrences(4), host.ready.map { it.occurrence }.toSet(), "code item scopes carry their occurrence")
        assertTrue(host.ready.all { it.stepName == "fanout" && it.rootExecutionId == "root" })
        assertEquals(occurrences(4), host.ended.map { it.occurrence }.toSet()); assertTrue(host.ended.all { it.stepName == "fanout" })
        val updates = host.updates()
        assertEquals(occurrences(4), updates.filter { it.type == "occurrence_started" }.map { it.occurrence }.toSet())
        assertTrue(updates.filter { it.type == "occurrence_completed" }.all { it.success == true && it.stepName == "fanout:${it.occurrence}" })
        assertEquals(4, updates.count { it.event?.type == "code_step_started" && it.occurrence != null })
        assertTrue(updates.none { it.type == "step_started" && it.stepName != "fanout" }, "items are occurrences, never steps of their own")
        assertEquals(1, updates.count { it.type == "step_completed" && it.stepName == "fanout" })
    }

    @Test fun `a loop runs its occurrences alongside other steps under workflow concurrency`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir); val host = LoopHost(base)
        val workflow = WorkflowDefinition("loop-concurrent", agents = emptyMap(), directoryIsolation = base.isolation(), concurrency = ConcurrencyConfig(true, 2),
            workflow = listOf(loopStep(3), WorkflowStep("beside", code = CodeStepConfig("bash", "echo beside"))))
        val result = withContext(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow) }
        assertTrue(result.success, result.error)
        assertEquals(occurrences(3), host.ended.map { it.occurrence }.toSet())
        assertTrue(host.ready.filter { it.stepName == "beside" }.all { it.occurrence == null }, "a sibling step never inherits an occurrence")
        assertEquals(3, host.updates().count { it.type == "occurrence_completed" && it.success == true })
    }

    @Test fun `stopping a loop cancels every running item and starts no further item`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir); val started = java.util.concurrent.atomic.AtomicInteger(); val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val bothRunning = CompletableDeferred<Unit>()
        val host = LoopHost(base, object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                if (started.incrementAndGet() == 2) bothRunning.complete(Unit)
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
        })
        val workflow = WorkflowDefinition("loop-stop", agents = emptyMap(), directoryIsolation = base.isolation(), workflow = listOf(loopStep(5)))
        val running = async(Dispatchers.Default) { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow) }
        withTimeout(10_000) { bothRunning.await() }
        running.cancel(); running.join()
        assertEquals(2, started.get(), "only max_parallel items ever started"); assertEquals(2, cancelled.get(), "every running item was cancelled")
        assertEquals(2, host.ended.size, "each cancelled item ended its occurrence")
        val updates = host.updates()
        assertEquals(host.ended.map { it.occurrence }.toSet(), updates.filter { it.type == "occurrence_cancelled" }.map { it.occurrence }.toSet())
        assertEquals(2, updates.count { it.type == "occurrence_started" })
        assertTrue(base.checkpoints.none { it.safeToResume && it.state.preservedStepResults.containsKey("fanout") })
    }

    @Test fun `a loop is atomic for resume - no safe savepoint holds part of it and a resume reruns every item`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir); val items = java.util.concurrent.atomic.AtomicInteger()
        val itemsAtSafeCheckpoint = java.util.concurrent.CopyOnWriteArrayList<Pair<WorkflowHostCheckpoint, Int>>()
        val host = object : WorkflowHost by LoopHost(base, object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                if (request.env.containsKey("WF_VAR_ITEM")) items.incrementAndGet()
                return SubprocessExecutor.ExecResult(0, "done", "", 1)
            }
        }) {
            override suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint) { if (checkpoint.safeToResume) itemsAtSafeCheckpoint.add(checkpoint to items.get()); base.checkpoint(checkpoint) }
            override suspend fun checkpointOf(build: () -> WorkflowHostCheckpoint?) { build()?.let { checkpoint(it) } }
        }
        val workflow = WorkflowDefinition("loop-resume", agents = emptyMap(), directoryIsolation = base.isolation(), workflow = listOf(
            WorkflowStep("before", code = CodeStepConfig("bash", "echo before")),
            loopStep(3, parallel = false, maxParallel = null, dependsOn = listOf("before")),
            WorkflowStep("after", dependsOn = listOf("fanout"), code = CodeStepConfig("bash", "echo after"))))
        assertTrue(WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow).success)
        assertEquals(3, items.get())
        assertTrue(itemsAtSafeCheckpoint.all { (checkpoint, seen) ->
            if (checkpoint.state.preservedStepResults.containsKey("fanout")) seen == 3 else seen == 0 }, "a safe savepoint holds the whole loop or none of it")
        val beforeLoop = itemsAtSafeCheckpoint.last { (checkpoint, _) -> "before" in checkpoint.state.preservedStepResults && "fanout" !in checkpoint.state.preservedStepResults }.first
        assertTrue(WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow, resumeState = beforeLoop.state).success)
        assertEquals(6, items.get(), "a resume before the loop reruns every item")
        val afterLoop = itemsAtSafeCheckpoint.first { (checkpoint, _) -> "fanout" in checkpoint.state.preservedStepResults }.first
        assertTrue(WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow, resumeState = afterLoop.state).success)
        assertEquals(6, items.get(), "a resume after the loop runs none of its items")
    }

    @Test fun `a host refusal of the loop fails the step before any item runs and closes its record`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir); val host = LoopHost(base).apply { refuseLoop = "loop has too many items" }
        val workflow = WorkflowDefinition("loop-refused", agents = emptyMap(), directoryIsolation = base.isolation(), workflow = listOf(loopStep(3)))
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
        assertFalse(result.success); assertTrue(host.launched.isEmpty()); assertTrue(host.ended.isEmpty())
        val updates = host.updates()
        assertTrue(updates.none { it.type == "occurrence_started" })
        assertTrue(updates.any { it.type == "step_completed" && it.stepName == "fanout" && it.success == false && it.error.orEmpty().contains("too many items") })
    }

    class HostFileMarker : ai.koog.agents.core.tools.reflect.ToolSet {
        @ai.koog.agents.core.tools.annotations.Tool
        @ai.koog.agents.core.tools.annotations.LLMDescription("Host-owned file tool marker")
        fun hostFileMarker(): String = "marker"
    }
    @Test fun `host file system tools replace the library file tools in the agent registry`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir)
        val bodies = mutableListOf<String>()
        val mock = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine) { engine { addHandler { request ->
            val body = String(request.body.toByteArray()); synchronized(bodies) { bodies.add(body) }
            val reply = buildJsonObject {
                put("id", "test-only-model"); put("object", "chat.completion"); put("created", 1760000000); put("model", "gpt-4o-mini")
                put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("message", buildJsonObject { put("role", "assistant"); put("content", "done") }); put("finish_reason", "stop") }) })
                put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
            }
            respond(reply.toString(), io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        } } }
        val scopes = mutableListOf<WorkflowHostScope>()
        val host = object : WorkflowHost by base {
            override val llmHttpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(mock)
            override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>) = parameters + listOf(
                com.fartech.ftapp2.commonsKt.ConfigurationParameter("openai_api_key", JsonPrimitive("test-only-model-key")),
                com.fartech.ftapp2.commonsKt.ConfigurationParameter("disable_skills", JsonPrimitive(true)))
            override fun fileSystemTools(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<ai.koog.agents.core.tools.reflect.ToolSet> {
                scopes += scope; return listOf(HostFileMarker())
            }
        }
        val workflow = WorkflowParser.parseYaml("""
            name: hosted-files
            agents:
              model:
                strategy: single_run
                tools: [file_system]
                llm:
                  model: gpt-4o-mini
                  provider: openai
            workflow:
              - step: write
                agent: model
                input: Write the notes.
        """.trimIndent()).copy(directoryIsolation = base.isolation())
        try {
            val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
            assertTrue(result.success, result.error)
        } finally { mock.close() }
        val request = synchronized(bodies) { bodies.first() }
        assertTrue(request.contains("hostFileMarker"), request)
        for (library in listOf("readFile", "writeFile", "__write_file__", "__read_file__", "copyFile", "deleteFile")) assertFalse(request.contains("\"$library\""), library)
        assertEquals(listOf(WorkflowHostScope("root", "root", stepName = "write")), scopes)
    }
    @Test fun `host can replace multiple tool groups or suppress them with no library fallback`(@TempDir dir: Path) = runBlocking {
        val base = Host(dir)
        val bodies = mutableListOf<String>()
        val mock = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine) { engine { addHandler { request ->
            val body = String(request.body.toByteArray()); synchronized(bodies) { bodies.add(body) }
            val reply = buildJsonObject {
                put("id", "test-only-model"); put("object", "chat.completion"); put("created", 1760000000); put("model", "gpt-4o-mini")
                put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("message", buildJsonObject { put("role", "assistant"); put("content", "done") }); put("finish_reason", "stop") }) })
                put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
            }
            respond(reply.toString(), io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        } } }
        val scopes = mutableListOf<WorkflowHostScope>()
        val host = object : WorkflowHost by base {
            override val llmHttpClientFactory = ai.koog.http.client.ktor.KtorKoogHttpClient.Factory(mock)
            override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>) = parameters + listOf(
                com.fartech.ftapp2.commonsKt.ConfigurationParameter("openai_api_key", JsonPrimitive("test-only-model-key")))
            override fun agentToolSets(scope: WorkflowHostScope, toolNames: Set<String>, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): Map<String, List<ai.koog.agents.core.tools.reflect.ToolSet>> {
                assertTrue(toolNames.containsAll(setOf("file_system", "calendar", "data_transform", "workflow", "skill_tools")))
                scopes += scope
                return mapOf("calendar" to listOf(HostFileMarker()), "file_system" to emptyList(), "data_transform" to emptyList(), "workflow" to emptyList(), "skill_tools" to emptyList())
            }
            override fun fileSystemTools(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<ai.koog.agents.core.tools.reflect.ToolSet> = error("Explicit group replacement must suppress legacy fallback")
        }
        val workflow = WorkflowParser.parseYaml("""
            name: hosted-files
            agents:
              model:
                strategy: single_run
                tools: [file_system, calendar, data_transform, workflow, skill_tools]
                llm:
                  model: gpt-4o-mini
                  provider: openai
            workflow:
              - step: write
                agent: model
                input: Write the notes.
        """.trimIndent()).copy(directoryIsolation = base.isolation())
        try {
            val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
            assertTrue(result.success, result.error)
        } finally { mock.close() }
        val request = synchronized(bodies) { bodies.first() }
        assertTrue(request.contains("hostFileMarker"), request)
        for (library in listOf("readFile", "writeFile", "__write_file__", "__read_file__", "copyFile", "deleteFile", "getCurrentDateTime", "jsonToYaml", "executeWorkflow", "searchSkills", "getSkillDetails")) assertFalse(request.contains("\"$library\""), library)
        assertEquals(listOf(WorkflowHostScope("root", "root", stepName = "write")), scopes)
    }
    @Test fun `closure admission refuses unknown dormant nested tools before effects`(@TempDir dir: Path) {
        val host = Host(dir)
        val child = WorkflowDefinition("unsafe", agents = mapOf("unknown" to AgentDefinition(tools = listOf("server_business_broker"))), workflow = listOf(WorkflowStep("unused", agent = "unknown", input = "ignored")))
        val resolver = InMemoryWorkflowResolver(byName = mapOf("unsafe" to child))
        val root = WorkflowDefinition("root", agents = emptyMap(), workflow = listOf(WorkflowStep("dormant", condition = "enabled == true", subWorkflow = SubWorkflowConfig(name = "unsafe"))))
        assertFailsWith<IllegalArgumentException> { RunnerAdmission(resolver, setOf("workflow.full.v1", "step.subWorkflow.v1", "step.agent.v1")).inspect(root) }
        assertTrue(host.invocations.isEmpty())
    }
    @Test fun `checkpoint hash typed variables old identity and leaked credential protections`(@TempDir dir: Path) {
        val old = RunnerProtocolTest.binding(); val secrets = RunnerSecrets()
        val store = RunnerCheckpointStore(dir, old, secrets)
        val state = WorkflowResumeState(variables = mapOf("number" to 5, "bool" to true, "nested" to listOf("a", 2)), stepOutputs = mapOf("step" to "output"), activatedTransitionSteps = setOf("next"), childInvocationCounts = mapOf("child" to 2L))
        val announcement = store.save(WorkflowHostCheckpoint(WorkflowHostScope(old.string("executionId"), old.string("executionId")), "test", state, true), 3)
        val new = JsonObject(old + mapOf("resumeEpoch" to JsonPrimitive(1), "attemptId" to JsonPrimitive("new-attempt"), "runnerInstanceId" to JsonPrimitive("new-instance")))
        val restored = RunnerCheckpointStore(dir, new, secrets).restore(announcement, old)
        assertEquals(state, restored)
        assertFailsWith<IllegalArgumentException> { store.restore(announcement, old) }
        secrets.register(JsonPrimitive("secret-value"))
        assertFailsWith<IllegalArgumentException> { store.save(WorkflowHostCheckpoint(WorkflowHostScope("root", "root"), "test", WorkflowResumeState(variables = mapOf("leak" to "secret-value")), true), 0) }
        val unsafe = store.save(WorkflowHostCheckpoint(WorkflowHostScope(old.string("executionId"), old.string("executionId")), "test", state, false), 0)
        assertFailsWith<IllegalArgumentException> { RunnerCheckpointStore(dir, new, secrets).restore(unsafe, old) }
    }
}
