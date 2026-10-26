package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerArtifactAckTest {
    private fun gate(h: IpcHarness) = RunnerEnvironmentGate(h.binding, setOf("engine-http")).also { it.install(RunnerProtocolTest.environment(h.binding, Instant.now())) }
    @Test fun `ACK recovery reuses publication identity without recollecting or allocating events`(@TempDir dir: Path) = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = gate(h); val secrets = RunnerSecrets(); val store = RunnerCheckpointStore(dir, h.binding, secrets)
            var reads = 0; var sequence = 0L
            val publisher = RunnerArtifactPublications(dir, h.ipc, gate, store, secrets,
                RunnerArtifactFingerprinter { reads++; RunnerArtifactFingerprint(5, sha256("bytes".toByteArray())) },
                recordEvent = { payload, frameId, before ->
                    sequence++
                    val event = buildJsonObject { put("eventId", "original-event-$sequence"); put("eventSeq", sequence); put("attemptId", h.binding.string("attemptId")); put("resumeEpoch", h.binding.long("resumeEpoch")); put("type", "artifact"); put("payload", payload) }
                    JsonObject(RunnerProtocolTest.frame(h.binding, "event", event) + ("frameId" to JsonPrimitive(frameId))).also {
                        before(it); store.writePrivate(".runner-state/events/$sequence.json", canonical(it).toByteArray()); h.ipc.sendFrame(it)
                    }
                }, acceptAck = { require(it.long("ackSeq") <= sequence) })
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "produce")
            val pending = async { publisher.publish("root", scope, "original-start", RunnerDeclaredArtifact("run/file.txt", 100)) }
            val frame = h.next(); val payload = frame.obj("payload").obj("payload")
            val schema = runnerJson.parseToJsonElement(javaClass.getResourceAsStream("/runner-workflow-v1/artifact-publication.schema.json")!!.readBytes().toString(Charsets.UTF_8)).jsonObject
            assertEquals(schema.obj("properties").keys, payload.keys)
            h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", 1); put("revision", 9); put("serverTime", Instant.now().toString()) }))
            pending.await()
            publisher.publish("root", scope, "original-start", RunnerDeclaredArtifact("run/file.txt", 100))
            assertEquals(1, reads); assertEquals(1, sequence)
            assertFailsWith<IllegalArgumentException> { publisher.publish("root", scope, "original-start", RunnerDeclaredArtifact("run/file.txt", 101)) }
            assertEquals(1, reads); assertEquals(1, sequence)
            val eventPath = dir.resolve(".runner-state/artifact-intents/${payload.string("publicationId").removePrefix("publication_")}.event.json")
            Files.writeString(eventPath, JsonObject(frame + ("payload" to JsonObject(frame.obj("payload") + ("type" to JsonPrimitive("log"))))).toString())
            val recovered = RunnerArtifactPublications(dir, h.ipc, gate, store, secrets,
                RunnerArtifactFingerprinter { error("Never recollect a persisted intent") }, recordEvent = { _, _, _ -> error("Never allocate a replacement event") }, acceptAck = {})
            assertFailsWith<RunnerAuthorityLost> { recovered.publish("root", scope, "original-start", RunnerDeclaredArtifact("run/file.txt", 100)) }
        }
    }
    @Test fun `insufficient index ACK loses authority and cannot release next work`(@TempDir dir: Path) = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = gate(h); val secrets = RunnerSecrets(); val store = RunnerCheckpointStore(dir, h.binding, secrets)
            val publisher = RunnerArtifactPublications(dir, h.ipc, gate, store, secrets,
                RunnerArtifactFingerprinter { RunnerArtifactFingerprint(0, sha256(byteArrayOf())) },
                recordEvent = { payload, frameId, before -> JsonObject(RunnerProtocolTest.frame(h.binding, "event", buildJsonObject {
                    put("eventId", "original-event"); put("eventSeq", 2); put("attemptId", h.binding.string("attemptId")); put("resumeEpoch", h.binding.long("resumeEpoch")); put("type", "artifact"); put("payload", payload)
                }) + ("frameId" to JsonPrimitive(frameId))).also { before(it); h.ipc.sendFrame(it) } }, acceptAck = {})
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "produce")
            val pending = async { runCatching { publisher.publish("root", scope, "start", RunnerDeclaredArtifact("run/file", 100)) } }
            val frame = h.next(); h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", 1); put("revision", 9); put("serverTime", Instant.now().toString()) }))
            assertTrue(pending.await().exceptionOrNull() is RunnerAuthorityLost)
            assertFailsWith<RunnerAuthorityLost> { gate.check() }
            assertTrue(Files.walk(dir).use { it.noneMatch { path -> path.fileName.toString().endsWith(".ack.json") } })
        }
    }
    @Test fun `unlisted private paths and out of budget fingerprint never become artifact events`(@TempDir dir: Path) = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = gate(h); val secrets = RunnerSecrets(); val store = RunnerCheckpointStore(dir, h.binding, secrets)
            var reads = 0; var events = 0
            val publisher = RunnerArtifactPublications(dir, h.ipc, gate, store, secrets,
                RunnerArtifactFingerprinter { reads++; RunnerArtifactFingerprint(101, "a".repeat(64)) },
                recordEvent = { _, _, _ -> events++; error("Must not publish") }, acceptAck = {})
            val scope = WorkflowHostScope(h.binding.string("executionId"), h.binding.string("executionId"), stepName = "produce")
            for (path in listOf("../other", "/outside", ".runner-private/auth.json", "process-output/stdio", "inputs/brief.txt")) {
                assertFailsWith<IllegalArgumentException> { publisher.publish("root", scope, "start", RunnerDeclaredArtifact(path, 100)) }
            }
            assertEquals(0, reads)
            assertFailsWith<RunnerAuthorityLost> { publisher.publish("root", scope, "start", RunnerDeclaredArtifact("run/file", 100)) }
            assertEquals(1, reads); assertEquals(0, events)
        }
    }
}
