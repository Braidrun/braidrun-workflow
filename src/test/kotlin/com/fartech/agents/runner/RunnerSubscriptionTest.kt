package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerSubscriptionTest {
    private val original = """{"tokens":{"access_token":"fake-original-access","refresh_token":"fake-original-refresh"}}"""
    private val refreshed = """{"tokens":{"access_token":"fake-new-access","refresh_token":"fake-new-refresh"}}"""
    private fun grant(h: IpcHarness, request: JsonObject, id: String, material: JsonObject, version: Long = 3) = h.reply(request, "credentialGrant", buildJsonObject {
        put("requestId", request.obj("payload").string("requestId")); put("credentialLeaseId", id); put("credentialRef", "account"); put("version", version)
        put("revision", 1); put("expiresAt", Instant.now().plusSeconds(60).toString())
        put("allowedOperations", JsonArray(listOf("subscription.codex", "credential.rotate").map(::JsonPrimitive))); put("material", material)
    })
    @Test fun `credential permission renews while a CLI holds the same provider token`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val secrets = RunnerSecrets(); val credentials = RunnerCredentials(h.ipc, gate, secrets)
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 20)
            try {
                val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
                val pending = async { live.acquire("credential:account@3", scope, "subscription.codex") }
                val request = h.next(); val material = buildJsonObject { put("authJson", original) }
                h.ipc.accept(grant(h, request, "first", material)); val handle = pending.await()
                val renewal = h.next(); h.ipc.accept(grant(h, renewal, "renewed", material))
                withTimeout(1000) { while (handle.latest.get().leaseId != "renewed") delay(5) }
                assertEquals(original, live.checked(handle, scope, "subscription.codex").string("authJson"))
                live.release(scope.executionId, "cli")
                assertFailsWith<IllegalArgumentException> { live.checked(handle, scope, "subscription.codex") }
                assertFalse(secrets.redact("fake-original-access fake-original-refresh").contains("fake-original"))
            } finally { live.close(); credentials.clear() }
        }
    }
    @Test fun `failover releases abandoned renewal without invalidating the replacement card`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val secrets = RunnerSecrets(); val credentials = RunnerCredentials(h.ipc, gate, secrets)
            val releases = mutableListOf<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 30, onReleased = { lease, _ -> releases += lease.leaseId })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            val events = mutableListOf<JsonObject>()
            val pool = RunnerSubscriptionPool(live, scope, "codex", listOf("credential:account@3", "credential:backup@3"), secrets) { _, payload -> events += payload }
            fun reply(request: JsonObject, id: String) {
                val response = grant(h, request, id, buildJsonObject { put("authJson", original) })
                h.ipc.accept(JsonObject(response + ("payload" to JsonObject(response.obj("payload") + ("credentialRef" to request.obj("payload").getValue("credentialRef"))))))
            }
            try {
                val first = async { pool.acquire(emptySet()) }; reply(h.next(), "first"); assertEquals("account", first.await()!!.id)
                val pendingOldRenewal = h.next() // Failover while the old card's renewal reply is withheld.
                val second = async { pool.acquire(setOf("account")) }
                val next = h.next(); assertEquals("backup", next.obj("payload").string("credentialRef")); reply(next, "second")
                assertEquals("backup", second.await()!!.id)
                reply(pendingOldRenewal, "late-old")
                val renewal = h.next(); assertEquals("backup", renewal.obj("payload").string("credentialRef")); reply(renewal, "renewed-second")
                gate.check()
                assertTrue("first" in releases)
                assertFalse("second" in releases && "renewed-second" in releases)
            } finally { live.close(); credentials.clear() }
        } }
    }

    @Test fun `pending same-reference acquire survives another handle release with exact grant identity`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val credentials = RunnerCredentials(h.ipc, gate, RunnerSecrets())
            val released = java.util.concurrent.CopyOnWriteArrayList<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 30_000, onReleased = { lease, _ -> released += lease.leaseId })
            val root = h.binding.string("executionId")
            val aScope = WorkflowHostScope(root, root, stepName = "a")
            val bScope = WorkflowHostScope(root, root, stepName = "b")
            try {
                val acquiringA = async { live.acquire("credential:account@3", aScope, "subscription.codex") }
                h.ipc.accept(grant(h, h.next(), "grant-a", buildJsonObject { put("authJson", original) }))
                val a = acquiringA.await()
                val acquiringB = async(start = CoroutineStart.UNDISPATCHED) { live.acquire("credential:account@3", bScope, "subscription.codex") }
                val requestB = h.next()
                // S has written grant-b to its watchdog journal; E has not received/registered it yet.
                live.release(a)
                assertEquals(listOf("grant-a"), released)
                h.ipc.accept(grant(h, requestB, "grant-b", buildJsonObject { put("authJson", original) }))
                val b = acquiringB.await()
                assertEquals(original, live.checked(b, bScope, "subscription.codex").string("authJson"))
                gate.check(); assertFalse("grant-b" in released)
                live.release(b); assertEquals(listOf("grant-a", "grant-b"), released)
            } finally { live.close(); credentials.clear() }
        } }
    }

    @Test fun `cancelled renewal publishes its original request identity and late grant never becomes held`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val cancelled = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val credentials = RunnerCredentials(h.ipc, gate, RunnerSecrets(), onRequestCancelled = { cancelled += it })
            val releases = java.util.concurrent.CopyOnWriteArrayList<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 30, onReleased = { lease, _ -> releases += lease.leaseId })
            val root = h.binding.string("executionId"); val scope = WorkflowHostScope(root, root, stepName = "cli")
            try {
                val pending = async { live.acquire("credential:account@3", scope, "subscription.codex") }
                h.ipc.accept(grant(h, h.next(), "grant-a", buildJsonObject { put("authJson", original) }))
                val handle = pending.await()
                val renewal = h.next() // S has recorded a grant, but its reply is deliberately withheld from E.
                live.release(handle)
                assertEquals(1, cancelled.size)
                assertEquals(renewal.string("frameId"), cancelled.single().string("requestFrameId"))
                assertEquals(renewal.obj("payload"), JsonObject(cancelled.single() - "requestFrameId"))
                h.ipc.accept(grant(h, renewal, "grant-r-late", buildJsonObject { put("authJson", original) }))
                assertEquals("grant-a", handle.latest.get().leaseId)
                assertEquals(listOf("grant-a"), releases)
                assertFailsWith<IllegalArgumentException> { live.checked(handle, scope, "subscription.codex") }
                gate.check()
                val replacement = async { live.acquire("credential:account@3", scope.copy(stepName = "replacement"), "subscription.codex") }
                h.ipc.accept(grant(h, h.next(), "grant-b", buildJsonObject { put("authJson", original) }))
                val b=replacement.await(); assertEquals("grant-b", b.latest.get().leaseId);gate.check()
                live.release(b)
            } finally { live.close(); credentials.clear() }
        } }
    }

    @Test fun `proxy validation applies even when URL has trailing slash`() {
        for (url in listOf("http://u:p@localhost:7890/", "file://localhost/", "http://localhost/")) {
            val properties = java.util.Properties().apply { setProperty(RunnerHttpClients.DECLARED_PROXY_PROPERTY, url) }
            assertFailsWith<IllegalArgumentException> { RunnerHttpClients.declaredProxy(properties) }
        }
    }

    @Test fun `short grant expiry cancels a blocked CLI and late renewal cannot revive it`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            gate.installLease(RunnerProtocolTest.lease(Instant.now()))
            val credentials = RunnerCredentials(h.ipc, gate, RunnerSecrets()); val live = RunnerLiveCredentials(credentials, gate)
            val cli = launch { awaitCancellation() }; val listener = gate.onInvalidated { cli.cancel() }
            try {
                val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "short-cli")
                val acquiring = async { live.acquire("credential:account@3", scope, "subscription.codex") }
                val request = h.next(); val material = buildJsonObject { put("authJson", original) }
                val response = grant(h, request, "short", material)
                h.ipc.accept(JsonObject(response + ("payload" to JsonObject(response.obj("payload") + ("expiresAt" to JsonPrimitive(Instant.now().plusMillis(500).toString()))))))
                val handle = acquiring.await()
                val renewal = h.next() // Deliberately withhold its reply beyond the actual deadline.
                withTimeout(1500) { cli.join() }
                assertTrue(cli.isCancelled); assertFailsWith<RunnerAuthorityLost> { gate.check() }
                h.ipc.accept(grant(h, renewal, "late", material))
                delay(30)
                assertEquals("short", handle.latest.get().leaseId)
                assertFailsWith<RunnerAuthorityLost> { gate.check() }
            } finally { listener.close(); cli.cancel(); live.close(); credentials.clear() }
        }
    }
    @Test fun `real external agent wiring scopes Codex HOME and persists local auth rotation before cleanup`(@TempDir dir: Path) = runBlocking<Unit> {
            val source = """
                name: codex-host-fixture
                agents:
                  coder:
                    type: codex_agent
                    overrides:
                      external_agent_codex_auth_mode: subscription
                      external_agent_codex_auth_json: credential:account@3
                      external_agent_codex_model: gpt-6-astra
                      external_agent_codex_command: ${dir.resolve("fake-codex")}
                      skills_config:
                        enabledSkills: [frontend-design]
                workflow:
                  - step: generate
                    agent: coder
                    input: Write fixture code
            """.trimIndent()
            val skill = "Frozen native skill proof: use amber."
            Files.createDirectories(dir.resolve("skills/frontend-design"))
            Files.writeString(dir.resolve("skills/frontend-design/SKILL.md"), skill)
            val fake = dir.resolve("fake-codex")
            Files.writeString(fake, """
                #!/bin/bash
                set -eu
                prompt=${'$'}(cat)
                [[ "${'$'}prompt" == *"Frozen native skill proof: use amber."* ]]
                [[ "${'$'}prompt" == *"Write fixture code"* ]]
                [[ "${'$'}HOME" == *".runner-private"* ]]
                [[ "${'$'}CODEX_HOME" == *".runner-private"* ]]
                printf '%s' '$refreshed' > "${'$'}CODEX_HOME/auth-new.json"
                mv "${'$'}CODEX_HOME/auth-new.json" "${'$'}CODEX_HOME/auth.json"
                printf '%s\n' '{"result":"cli-host-proof"}'
            """.trimIndent())
            fake.toFile().setExecutable(true)
            Files.writeString(dir.resolve("root.yaml"), source); val hash = sha256(source.toByteArray())
            val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        IpcHarness(binding).use { h ->
            val metadata = buildJsonObject { put("rootWorkflowId", "root")
                put("files", JsonArray(listOf(buildJsonObject { put("path", "root.yaml"); put("size", source.toByteArray().size); put("sha256", hash); put("role", "definition") },
                    buildJsonObject { put("path", "skills/frontend-design/SKILL.md"); put("size", skill.toByteArray().size); put("sha256", sha256(skill.toByteArray())); put("role", "skill") },
                    buildJsonObject { put("path", "fake-codex"); put("size", Files.size(fake)); put("sha256", sha256(Files.readAllBytes(fake))); put("role", "script") }
                ).sortedBy { it.string("path") }))
                put("definitions", JsonArray(listOf(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }))) }
            val bundle = FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
            val gate = RunnerEnvironmentGate(h.binding, setOf("agent-cli"))
            val evidence = RunnerProtocolTest.environment(h.binding, Instant.now())
            gate.install(JsonObject(evidence + ("egress" to JsonArray(evidence.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("agent-cli"))) }))))
            val secrets = RunnerSecrets(); var home: Path? = null
            val executor = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                    home = Path.of(request.env.getValue("CODEX_HOME")); assertTrue(home!!.startsWith(dir.resolve(".runner-private")))
                    assertTrue(Path.of(request.env.getValue("HOME")).startsWith(dir.resolve(".runner-private")))
                    assertFalse(request.command.joinToString(" ").contains("fake-original"))
                    assertEquals(original, Files.readString(home!!.resolve("auth.json")))
                    assertTrue(request.stdin!!.contains("Frozen native skill proof: use amber."))
                    assertFalse(request.command.joinToString(" ").contains("Frozen native skill proof"))
                    val child = ProcessBuilder(request.command).directory(request.workingDir).apply {
                        environment().clear(); environment()["PATH"] = "/usr/bin:/bin"
                        environment().putAll(request.env)
                    }.start()
                    try {
                        child.outputStream.use { it.write(request.stdin!!.toByteArray()) }
                        assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                        assertEquals(0, child.exitValue(), child.errorStream.bufferedReader().readText())
                        assertTrue(child.inputStream.bufferedReader().readText().contains("cli-host-proof"))
                    } finally { child.destroyForcibly() }
                    request.stdoutLineCallback?.invoke("""{"type":"item.completed","item":{"type":"command_execution","command":"fixture-command","aggregated_output":"fake-new-access fake-new-refresh","exit_code":0,"status":"completed"}}""")
                    return SubprocessExecutor.ExecResult(0, "{\"result\":\"cli-host-proof\"}", "", 1)
                }
            }
            val identity = WorkflowHostBinding(h.binding.string("ownerId"), h.binding.string("actorUserId"), "billing", h.binding.string("executionId"), h.binding.string("runnerId"), h.binding.string("runnerInstanceId"), h.binding.long("resumeEpoch"), h.binding.string("attemptId"), hash, h.binding.string("runtimeHash"))
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, executor, RunnerCheckpointStore(dir, h.binding, secrets), secrets, validateDirectNetwork = { RunnerHttpClients.requireDirect(emptyMap(), java.util.Properties()) })
            val running = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(bundle.rootDefinition) }
            var rotated = false
            val pump = launch {
                while (isActive) {
                    val frame = h.next(); val payload = frame.obj("payload")
                    when (frame.string("type")) {
                        "credentialRequest" -> h.ipc.accept(grant(h, frame, "subscription", buildJsonObject { put("authJson", original) }))
                        "credentialRotation" -> {
                            rotated = true; assertEquals(refreshed, payload.obj("material").string("authJson"))
                            assertEquals(3L, payload.long("expectedVersion"))
                            h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", 0); put("revision", 2); put("serverTime", Instant.now().toString()) }))
                        }
                        "event", "checkpoint" -> assertFalse(frame.toString().contains("fake-original-access") || frame.toString().contains("fake-new-access"))
                    }
                }
            }
            try {
                val result = withTimeout(5000) { running.await() }
                assertTrue(result.success, result.error); assertEquals("cli-host-proof", result.stepResults.getValue("generate").output)
                assertTrue(rotated); assertFalse(Files.exists(home!!)); assertFalse(secrets.redact(refreshed).contains("fake-new-access"))
                assertTrue(Files.list(dir.resolve(".runner-private/pending-rotations")).use { it.count() == 0L })
                Files.walk(dir.resolve(".runner-state/events")).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    val text = Files.readString(path); assertFalse(text.contains("fake-new-access") || text.contains("fake-new-refresh"), "New opaque tokens must be redacted before event persistence")
                } }
            } finally { host.close(); pump.cancel(); h.supervisorInput.close() }
        }
    }
}
