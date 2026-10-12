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
    /** A grant echoing the request's own card and version, as S forwards it. */
    private fun reply(h: IpcHarness, request: JsonObject, id: String, material: JsonObject = buildJsonObject { put("authJson", original) },
                      operations: List<String> = listOf("subscription.codex", "credential.rotate"), expiresAt: Instant = Instant.now().plusSeconds(60)) {
        val asked = request.obj("payload")
        h.ipc.accept(h.reply(request, "credentialGrant", buildJsonObject {
            put("requestId", asked.string("requestId")); put("credentialLeaseId", id); put("credentialRef", asked.string("credentialRef"))
            put("version", asked.long("expectedVersion")); put("revision", 1); put("expiresAt", expiresAt.toString())
            put("allowedOperations", JsonArray(operations.map(::JsonPrimitive))); put("material", material)
        }))
    }
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
            val cancelled = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val secrets = RunnerSecrets(); val credentials = RunnerCredentials(h.ipc, gate, secrets, onRequestCancelled = { cancelled += it })
            val releases = java.util.concurrent.CopyOnWriteArrayList<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 30, onReleased = { lease, _ -> releases += lease.leaseId })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            val pool = RunnerSubscriptionPool(live, scope, "codex", listOf("credential:account@3", "credential:backup@3"), secrets) { _, _ -> }
            try {
                val first = async { pool.acquire(emptySet()) }; reply(h, h.next(), "first"); val held = first.await()!!
                assertEquals("account", held.id)
                val pendingOldRenewal = h.next() // Failover while the old card's renewal reply is withheld.
                pool.release(held) // The abandoned invocation ends its own hold before it fails over.
                assertEquals(listOf(pendingOldRenewal.string("frameId")), cancelled.map { it.string("requestFrameId") })
                assertEquals(pendingOldRenewal.obj("payload"), JsonObject(cancelled.single() - "requestFrameId"))
                assertEquals(listOf("first"), releases)
                val second = async { pool.acquire(setOf("account")) }
                val next = h.next(); assertEquals("backup", next.obj("payload").string("credentialRef")); reply(h, next, "second")
                assertEquals("backup", second.await()!!.id)
                reply(h, pendingOldRenewal, "late-old") // S tombstoned this request; E drops the late grant.
                val renewal = h.next(); assertEquals("backup", renewal.obj("payload").string("credentialRef")); reply(h, renewal, "renewed-second")
                withTimeout(1000) { while (releases.size < 2) delay(5) }
                gate.check()
                // Exactly: the abandoned card once, and the replacement's superseded grant once. The late grant was
                // never held, and the replacement's current grant is still live.
                assertEquals(listOf("first", "second"), releases)
            } finally { live.close(); credentials.clear() }
        } }
    }

    @Test fun `concurrent invocations share one card and one invocation's failover keeps the other's grant`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val secrets = RunnerSecrets(); val credentials = RunnerCredentials(h.ipc, gate, secrets)
            val releases = java.util.concurrent.CopyOnWriteArrayList<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 60_000, onReleased = { lease, _ -> releases += lease.leaseId })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            val pool = RunnerSubscriptionPool(live, scope, "codex", listOf("credential:account@3", "credential:backup@3"), secrets) { _, _ -> }
            try {
                val acquiringA = async { pool.acquire(emptySet()) }; reply(h, h.next(), "shared"); val a = acquiringA.await()!!
                val b = pool.acquire(emptySet())!! // Joins the live handle: no second platform grant.
                assertEquals("account", b.id); assertNotSame(a, b)
                // A hits the card's limit: it ends its own hold and fails over to the backup card.
                pool.release(a)
                val failover = async { pool.acquire(setOf("account")) }
                val backupRequest = h.next(); assertEquals("backup", backupRequest.obj("payload").string("credentialRef"))
                reply(h, backupRequest, "backup-grant"); val aBackup = failover.await()!!
                assertTrue(releases.isEmpty(), "B still runs on the shared card; its grant must stay held")
                gate.check()
                // B is still covered by the same grant: it can publish its refreshed auth on that exact lease.
                val rotating = async(Dispatchers.IO) { pool.rotated(b, refreshed) }
                val rotation = h.next(); assertEquals("credentialRotation", rotation.string("type"))
                assertEquals("shared", rotation.obj("payload").string("credentialLeaseId"))
                h.ipc.accept(h.reply(rotation, "ack", buildJsonObject { put("ackSeq", 0); put("revision", 2); put("serverTime", Instant.now().toString()) }))
                rotating.await()
                pool.release(b); pool.release(b) // A second release of the same hold is a no-op.
                assertEquals(listOf("shared"), releases)
                pool.release(aBackup)
                assertEquals(listOf("shared", "backup-grant"), releases)
                gate.check()
            } finally { live.close(); credentials.clear() }
        } }
    }

    @Test fun `rotation by one holder keeps a concurrent holder under a grant for the new version`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val secrets = RunnerSecrets(); val credentials = RunnerCredentials(h.ipc, gate, secrets)
            val releases = java.util.concurrent.CopyOnWriteArrayList<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 60_000, onReleased = { lease, _ -> releases += lease.leaseId })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            val pool = RunnerSubscriptionPool(live, scope, "codex", listOf("credential:account@3"), secrets) { _, _ -> }
            try {
                val acquiringA = async { pool.acquire(emptySet()) }; reply(h, h.next(), "v3"); val a = acquiringA.await()!!
                val b = pool.acquire(emptySet())!!
                val rotating = async(Dispatchers.IO) { pool.rotated(a, refreshed) }
                val rotation = h.next(); assertEquals("v3", rotation.obj("payload").string("credentialLeaseId"))
                h.ipc.accept(h.reply(rotation, "ack", buildJsonObject { put("ackSeq", 0); put("revision", 2); put("serverTime", Instant.now().toString()) }))
                val successor = h.next(); assertEquals("credentialRequest", successor.string("type"))
                assertEquals(4L, successor.obj("payload").long("expectedVersion"))
                reply(h, successor, "v4", material = buildJsonObject { put("authJson", refreshed) })
                rotating.await()
                assertEquals(listOf("v3"), releases)
                pool.release(a)
                assertEquals(listOf("v3"), releases, "B keeps running on the account; the new grant stays held")
                gate.check()
                pool.release(b)
                assertEquals(listOf("v3", "v4"), releases)
            } finally { live.close(); credentials.clear() }
        } }
    }

    @Test fun `step release that races a renewal swap retires the new grant instead of leaking it`() = runBlocking<Unit> {
        withTimeout(10_000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            // Park the renewal inside its swap critical section (after its liveness check, before it installs the
            // new grant) through the injected clock, which that section reads under the handle monitor.
            val armed = java.util.concurrent.atomic.AtomicReference<RunnerLiveCredentials.Handle?>()
            val inSwap = java.util.concurrent.CountDownLatch(1); val proceed = java.util.concurrent.CountDownLatch(1)
            val clock = {
                val handle = armed.get()
                if (handle != null && Thread.holdsLock(handle) && Thread.currentThread().stackTrace.any { it.className.contains("\$renew\$") } && armed.compareAndSet(handle, null)) {
                    inSwap.countDown(); proceed.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                System.nanoTime()
            }
            val credentials = RunnerCredentials(h.ipc, gate, RunnerSecrets(), nanoTime = clock)
            val releases = java.util.concurrent.CopyOnWriteArrayList<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 20, onReleased = { lease, _ -> releases += lease.leaseId })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            try {
                val pending = async { live.acquire("credential:account@3", scope, "subscription.codex") }
                reply(h, h.next(), "first"); val handle = pending.await()
                val renewal = h.next()
                armed.set(handle); reply(h, renewal, "renewed")
                assertTrue(runInterruptible(Dispatchers.IO) { inSwap.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                val releaser = Thread { live.release(scope.executionId, "cli") }.apply { start() }
                // Either the step release waits for the swap (fixed), or it already finished against the old grant.
                withTimeout(5000) { while (releaser.state != Thread.State.BLOCKED && releaser.state != Thread.State.TERMINATED) delay(1) }
                proceed.countDown()
                runInterruptible(Dispatchers.IO) { releaser.join(5000) }
                withTimeout(2000) { while (releases.size < 2) delay(5) }
                delay(50)
                assertEquals(listOf("first", "renewed"), releases, "every grant S recorded is released exactly once")
                assertFailsWith<IllegalArgumentException> { live.checked(handle, scope, "subscription.codex") }
                gate.check()
            } finally { proceed.countDown(); live.close(); credentials.clear() }
        } }
    }

    @Test fun `cancelling the caller during handle release cannot skip the final RELEASED`() = runBlocking<Unit> {
        withTimeout(10_000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val reporting = java.util.concurrent.CountDownLatch(1); val reported = java.util.concurrent.CountDownLatch(1)
            val credentials = RunnerCredentials(h.ipc, gate, RunnerSecrets(), onRequestCancelled = {
                reporting.countDown(); reported.await(5, java.util.concurrent.TimeUnit.SECONDS)
            })
            val releases = java.util.concurrent.CopyOnWriteArrayList<String>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 20, onReleased = { lease, _ -> releases += lease.leaseId })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            try {
                val pending = async { live.acquire("credential:account@3", scope, "subscription.codex") }
                reply(h, h.next(), "first"); val handle = pending.await()
                h.next() // Renewal in flight; its reply is withheld.
                val releaser = launch(Dispatchers.IO) { live.release(handle) }
                // The renewal is reporting its cancelled request while release joins it.
                assertTrue(runInterruptible(Dispatchers.IO) { reporting.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                releaser.cancel()
                reported.countDown()
                releaser.join()
                assertEquals(listOf("first"), releases)
                assertFailsWith<IllegalArgumentException> { live.checked(handle, scope, "subscription.codex") }
                gate.check()
            } finally { reported.countDown(); live.close(); credentials.clear() }
        } }
    }

    @Test fun `locally refused grant is retired at S under its exact request identity`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val cancelled = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val secrets = RunnerSecrets(); val credentials = RunnerCredentials(h.ipc, gate, secrets, onRequestCancelled = { cancelled += it })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            val material = buildJsonObject { put("authJson", original) }
            // The platform's grant lacks this purpose, then one that has already expired by E's clock.
            for ((leaseId, operations, expiresAt) in listOf(
                Triple("grant-no-purpose", listOf("other"), Instant.now().plusSeconds(60)),
                Triple("grant-expired", listOf("subscription.codex"), Instant.now().minusSeconds(1))
            )) {
                cancelled.clear()
                val pending = async { runCatching { credentials.acquire("account", 3, scope, "subscription.codex") } }
                val request = h.next()
                reply(h, request, leaseId, material = material, operations = operations, expiresAt = expiresAt)
                assertIs<IllegalArgumentException>(pending.await().exceptionOrNull())
                assertEquals(request.string("frameId"), cancelled.single().string("requestFrameId"), leaseId)
                assertEquals(request.obj("payload"), JsonObject(cancelled.single() - "requestFrameId"))
                assertFalse(secrets.redact("fake-original-access").contains("fake-original"), "refused material stays redacted")
            }
            // A refused first card is reported, and the pool's fallback card still works.
            val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val live = RunnerLiveCredentials(credentials, gate, renewalIntervalMs = 60_000)
            val pool = RunnerSubscriptionPool(live, scope, "codex", listOf("credential:account@3", "credential:backup@3"), secrets) { _, payload -> events += payload }
            try {
                cancelled.clear()
                val acquiring = async { pool.acquire(emptySet()) }
                val refusedRequest = h.next(); reply(h, refusedRequest, "refused", operations = listOf("other"))
                val backupRequest = h.next(); assertEquals("backup", backupRequest.obj("payload").string("credentialRef")); reply(h, backupRequest, "backup")
                assertEquals("backup", acquiring.await()!!.id)
                assertEquals(listOf(refusedRequest.string("frameId")), cancelled.map { it.string("requestFrameId") })
                assertEquals("LEASE_REFUSED", events.single().string("outcome"))
                gate.check()
            } finally { live.close(); credentials.clear() }
        } }
    }

    @Test fun `reused grant identity fails closed instead of retiring a live lineage`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val cancelled = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val credentials = RunnerCredentials(h.ipc, gate, RunnerSecrets(), onRequestCancelled = { cancelled += it })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "cli")
            val first = async { credentials.acquire("account", 3, scope, "subscription.codex") }
            reply(h, h.next(), "same"); first.await()
            val second = async { runCatching { credentials.acquire("account", 3, scope, "subscription.codex") } }
            reply(h, h.next(), "same")
            assertIs<RunnerAuthorityLost>(second.await().exceptionOrNull())
            assertTrue(cancelled.isEmpty()); assertFailsWith<RunnerAuthorityLost> { gate.check() }
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

    @Test fun `pre-dispatch cancellation creates no orphan request notification and backup stays usable`() = runBlocking<Unit> {
        withTimeout(5000) { IpcHarness().use { h ->
            val gate=RunnerEnvironmentGate(h.binding,setOf("engine-http"));gate.install(RunnerProtocolTest.environment(h.binding,Instant.now()))
            val cancelled=java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val credentials=RunnerCredentials(h.ipc,gate,RunnerSecrets(),onRequestCancelled={cancelled+=it})
            val root=h.binding.string("executionId");val scope=WorkflowHostScope(root,root,stepName="cli")
            val beforeDispatch=launch(start=CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                credentials.acquire("account",3,scope,"subscription.codex")
            }
            beforeDispatch.join()
            assertTrue(beforeDispatch.isCancelled);assertTrue(cancelled.isEmpty());assertFalse(h.ipc.businessFence.isClosed());gate.check()
            val backup=async { credentials.acquire("backup",3,scope,"subscription.codex") }
            val request=h.next();assertEquals("backup",request.obj("payload").string("credentialRef"))
            val reply=grant(h,request,"backup-grant",buildJsonObject { put("authJson",original) })
            h.ipc.accept(JsonObject(reply+("payload" to JsonObject(reply.obj("payload")+("credentialRef" to JsonPrimitive("backup"))))))
            assertEquals(original,credentials.material(backup.await(),scope,"subscription.codex").string("authJson"));gate.check()
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
            } finally { pump.cancelAndJoin(); host.close(); h.supervisorInput.close() }
        }
    }
    @Test fun `codex failover publishes the abandoned card's rotation under its own live lease before the backup runs`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = """
            name: codex-failover-fixture
            agents:
              coder:
                type: codex_agent
                overrides:
                  external_agent_codex_auth_mode: subscription
                  external_agent_codex_auth_json: credential:account@3,credential:backup@3
                  external_agent_codex_model: gpt-6-astra
            workflow:
              - step: generate
                agent: coder
                input: Write fixture code
        """.trimIndent()
        Files.writeString(dir.resolve("root.yaml"), source); val hash = sha256(source.toByteArray())
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val backupAuth = """{"tokens":{"access_token":"fake-backup-access","refresh_token":"fake-backup-refresh"}}"""
        IpcHarness(binding).use { h ->
            val metadata = buildJsonObject { put("rootWorkflowId", "root")
                put("files", JsonArray(listOf(buildJsonObject { put("path", "root.yaml"); put("size", source.toByteArray().size); put("sha256", hash); put("role", "definition") })))
                put("definitions", JsonArray(listOf(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }))) }
            val bundle = FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
            val gate = RunnerEnvironmentGate(h.binding, setOf("agent-cli"))
            val evidence = RunnerProtocolTest.environment(h.binding, Instant.now())
            gate.install(JsonObject(evidence + ("egress" to JsonArray(evidence.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("agent-cli"))) }))))
            val secrets = RunnerSecrets()
            val trace = java.util.concurrent.CopyOnWriteArrayList<String>()
            val executor = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                    val home = Path.of(request.env.getValue("CODEX_HOME"))
                    val auth = Files.readString(home.resolve("auth.json"))
                    return if ("fake-original-access" in auth) {
                        trace += "run:account"
                        // The CLI refreshed the account's token, then hit its usage limit.
                        Files.writeString(home.resolve("auth.json"), refreshed)
                        SubprocessExecutor.ExecResult(1, "", "stream error: 429 Too Many Requests (usage limit reached)", 1)
                    } else {
                        assertTrue("fake-backup-access" in auth); trace += "run:backup"
                        SubprocessExecutor.ExecResult(0, """{"type":"item.completed","item":{"type":"agent_message","text":"backup ok"}}""", "", 1)
                    }
                }
            }
            val identity = WorkflowHostBinding(h.binding.string("ownerId"), h.binding.string("actorUserId"), "billing", h.binding.string("executionId"), h.binding.string("runnerId"), h.binding.string("runnerInstanceId"), h.binding.long("resumeEpoch"), h.binding.string("attemptId"), hash, h.binding.string("runtimeHash"))
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, executor, RunnerCheckpointStore(dir, h.binding, secrets), secrets, validateDirectNetwork = { RunnerHttpClients.requireDirect(emptyMap(), java.util.Properties()) })
            val running = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(bundle.rootDefinition) }
            val released = java.util.concurrent.CopyOnWriteArrayList<String>()
            val pump = launch {
                while (isActive) {
                    val frame = h.next(); val payload = frame.obj("payload")
                    when (frame.string("type")) {
                        "credentialRequest" -> {
                            val ref = payload.string("credentialRef"); trace += "grant:$ref"
                            reply(h, frame, "grant-$ref", material = buildJsonObject { put("authJson", if (ref == "account") original else backupAuth) })
                        }
                        "credentialRotation" -> {
                            trace += "rotate:${payload.string("credentialRef")}"
                            assertEquals("grant-account", payload.string("credentialLeaseId"))
                            assertEquals(3L, payload.long("expectedVersion")); assertEquals(refreshed, payload.obj("material").string("authJson"))
                            h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", 0); put("revision", 2); put("serverTime", Instant.now().toString()) }))
                        }
                        "event" -> payload.obj("payload").takeIf { payload.string("type") == "usage" && it["outcome"] == JsonPrimitive("RELEASED") }
                            ?.let { released += it.string("credentialLeaseId"); trace += "released:${it.string("credentialLeaseId")}" }
                    }
                }
            }
            try {
                val result = withTimeout(5000) { running.await() }
                assertTrue(result.success, result.error); assertEquals("backup ok", result.stepResults.getValue("generate").output)
                withTimeout(2000) { while (released.size < 2) delay(5) }
                delay(50)
                // The refreshed refresh token reaches the vault under the account's own still-live lease, and only
                // then is that lease released and the backup card requested.
                assertEquals(listOf("grant:account", "run:account", "rotate:account", "released:grant-account", "grant:backup", "run:backup", "released:grant-backup"), trace)
                assertEquals(listOf("grant-account", "grant-backup"), released)
                gate.check()
            } finally { pump.cancelAndJoin(); host.close(); h.supervisorInput.close() }
        }
    }
    @Test fun `refused credential file observation still deletes the materialised auth json and ends the hold`(@TempDir dir: Path) = runBlocking<Unit> {
        val private = dir.resolve(".runner-private")
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val pool = object : com.fartech.agents.tools.InvocationCredentialProvider {
            override suspend fun acquire(excludedCredentialIds: Set<String>) =
                com.fartech.agents.tools.ClaudeCredentialProvider.Credential("account", original).also { calls += "acquire" }
            override suspend fun markRateLimited(credential: com.fartech.agents.tools.ClaudeCredentialProvider.Credential, resetAtMillis: Long) {}
            override suspend fun markSucceeded(credential: com.fartech.agents.tools.ClaudeCredentialProvider.Credential, executionId: String?, stepName: String?) {}
            override fun rotated(credential: com.fartech.agents.tools.ClaudeCredentialProvider.Credential, authJson: String) { calls += "rotated" }
            override suspend fun release(credential: com.fartech.agents.tools.ClaudeCredentialProvider.Credential) { calls += "release" }
        }
        var observed: Path? = null
        val host = object : WorkflowHost by WorkflowHostTest.Host(dir) {
            override val privateDirectory get() = private.toFile()
            override fun observeCredentialFile(scope: WorkflowHostScope, path: Path): AutoCloseable {
                observed = path; assertTrue(Files.exists(path))
                throw RunnerAuthorityLost() // The business fence closed between materialisation and observation.
            }
        }
        var executed = false
        val tools = com.fartech.agents.tools.ExternalAgentTools(
            executor = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult { executed = true; error("must not start") }
            },
            parameters = listOf(com.fartech.ftapp2.commonsKt.ConfigurationParameter("external_agent_codex_auth_mode", JsonPrimitive("subscription"))),
            context = com.fartech.agents.tools.exec.SubprocessToolContext(workspaceDir = dir.toFile(), executionId = "root", stepName = "cli"),
            codexCredentialProvider = pool
        )
        assertFailsWith<RunnerAuthorityLost> {
            WorkflowHostContext.withHost(host) { WorkflowHostContext.withScope(WorkflowHostScope("root", "root", stepName = "cli")) {
                tools.runCodexSubAgent(com.fartech.agents.tools.ExternalAgentContext(prompt = "x"))
            } }
        }
        assertFalse(executed)
        assertTrue(observed!!.startsWith(private)); assertFalse(Files.exists(observed), "live token file must not outlive a refused observation")
        assertEquals(listOf("acquire", "release"), calls)
    }
}
