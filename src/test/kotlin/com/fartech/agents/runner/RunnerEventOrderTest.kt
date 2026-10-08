package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RunnerEventOrderTest {
    @Test fun `artifact intent fsync and parallel child event cannot enqueue n plus one before n`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        val queued = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val persisted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val journal = RunnerEventJournal(RunnerProtocolTest.binding(), RunnerSecrets(), { path, _ -> persisted += path }, { queued += it }, { fail("Unexpected failure") })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val artifact = workers.submit<JsonObject> { journal.append("artifact", buildJsonObject { put("producer", "first") }, beforePersist = {
                // This is the old b96 gap: intent.event fsync after reserving n.
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val child = workers.submit<JsonObject> { secondStarted.countDown(); journal.append("step", buildJsonObject { put("producer", "parallel-child") }) }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFailsWith<java.util.concurrent.TimeoutException> { child.get(150, TimeUnit.MILLISECONDS) }
            assertFalse(child.isDone); assertTrue(queued.isEmpty())
            release.countDown(); artifact.get(2, TimeUnit.SECONDS); child.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(1L, 2L), queued.map { it.obj("payload").long("eventSeq") })
            assertEquals(listOf("artifact", "step"), queued.map { it.obj("payload").string("type") })
            assertEquals(listOf(".runner-state/events/1.json", ".runner-state/events/2.json"), persisted)
        } finally { release.countDown(); workers.shutdownNow() }
    }
    @Test fun `ACK waiter is armed before enqueue while another producer remains free to append`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val journal = RunnerEventJournal(h.binding, RunnerSecrets(), { _, _ -> }, { h.ipc.sendFrame(it) }, {})
            val artifact = async { h.ipc.awaitReply("ack", frameId = "original-artifact-frame") {
                journal.append("artifact", buildJsonObject { put("source", "fixture") }, "original-artifact-frame")
            } }
            val first = h.next(); assertFalse(artifact.isCompleted)
            withTimeout(1000) { withContext(Dispatchers.Default) { journal.append("step", buildJsonObject { put("producer", "child") }) } }
            val second = h.next(); assertEquals(2L, second.obj("payload").long("eventSeq")); assertFalse(artifact.isCompleted)
            h.ipc.accept(h.reply(first, "ack", buildJsonObject { put("ackSeq", 1); put("revision", 1); put("serverTime", java.time.Instant.now().toString()) }))
            assertEquals(1L, artifact.await().payload.long("ackSeq"))
        }
    }
    @Test fun `failed durable append stops future sequences instead of sending a hole`() {
        var sent = 0; var failed = false
        val journal = RunnerEventJournal(RunnerProtocolTest.binding(), RunnerSecrets(), { _, _ -> error("fsync failure") }, { sent++ }, { failed = true })
        assertFailsWith<RunnerAuthorityLost> { journal.append("artifact", JsonObject(emptyMap())) }
        assertFailsWith<RunnerAuthorityLost> { journal.append("step", JsonObject(emptyMap())) }
        assertTrue(failed); assertEquals(0, sent); assertEquals(1, journal.lastSequence())
    }
}
