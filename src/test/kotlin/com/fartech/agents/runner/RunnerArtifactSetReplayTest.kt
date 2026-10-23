package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/** Durable component reconstruction, separate from the real interpreter/process boundary tests. */
class RunnerArtifactSetReplayTest {
    @Test fun `reconstructed set resumes original event and index proof without new sequence or completion identity`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = "name: replay\nagents: {}\nworkflow:\n  - step: write\n    code:\n      language: bash\n      script: 'true'\n"
        val policyJson = buildJsonObject {
            put("schemaVersion", 1); put("rootWorkflowId", "root")
            put("declarations", buildJsonArray { add(buildJsonObject {
                put("declarationId", "decl"); put("producer", buildJsonObject { put("workflowId", "root"); put("stepName", "write"); put("childSteps", JsonArray(emptyList())) })
                put("selector", buildJsonObject { put("kind", "FILE"); put("path", "run/output.txt") }); put("presence", "OPTIONAL"); put("purpose", "DIAGNOSTIC")
                put("maximumFiles", 1); put("maximumFileBytes", 1024); put("maximumTotalBytes", 1024)
            }) })
        }
        val files = mapOf("root.yaml" to ("definition" to source), RunnerArtifactSetPolicy.PATH to ("runtime" to policyJson.toString()))
        files.forEach { (path, data) -> Files.createDirectories(dir.resolve(path).parent); Files.writeString(dir.resolve(path), data.second) }
        val hash = sha256(source.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(files.toSortedMap().map { (path, v) -> buildJsonObject { put("path", path); put("role", v.first); put("size", v.second.toByteArray().size); put("sha256", sha256(v.second.toByteArray())) } }))
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), binding)
        val policy = RunnerArtifactSetPolicy(bundle)
        var target: RunnerArtifactSets? = null
        var journal: RunnerEventJournal? = null
        IpcHarness(binding) { f -> if (f.string("type") == "ack") journal!!.acknowledge(f.obj("payload")); target!!.receive(f) }.use { h ->
            h.ipc.enableArtifactCollection(); h.ipc.enableArtifactSets()
            val gate = RunnerEnvironmentGate(binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(binding, Instant.now()))
            val secrets = RunnerSecrets(); val store = RunnerCheckpointStore(dir, binding, secrets)
            journal = RunnerEventJournal(binding, secrets, { path, bytes -> store.writePrivate(path, bytes); Unit }, { h.ipc.sendFrame(it); Unit }, { gate.invalidate("JOURNAL_FAILED") })
            val j = requireNotNull(journal)
            val producer = buildJsonObject { put("workflowId", "root"); put("stepName", "write"); put("childExecutionPath", JsonArray(emptyList())); put("producerStartEventId", "original-active-start") }
            var active = true
            fun build() = RunnerArtifactSets(dir, h.ipc, store, gate, policy, secrets,
                guarded = { p, action -> gate.check(); require(active && p == producer); action() },
                append = { p, reply, before -> j.append("step", p, replyTo = reply, beforePersist = before) }, acknowledged = j::acknowledged)
            val observed = Channel<JsonObject>(16)
            val pump = launch { while (isActive) {
                val raw = withContext(Dispatchers.IO) { RunnerIpc.readLine(h.supervisorInput) } ?: break
                observed.send(runnerJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject)
            } }
            suspend fun next() = withTimeout(3000) { observed.receive() }
            fun ack(f: JsonObject) = h.ipc.accept(h.reply(f, "ack", buildJsonObject { put("ackSeq", f.obj("payload").long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) }))
            target = build()
            try {
                target!!.ready(producer, listOf("decl"), "FAILED"); val boundary = next(); ack(boundary)
                val request = RunnerProtocolTest.frame(binding, "artifactSetCollectRequest", buildJsonObject {
                    put("setId", "set-original"); put("ticketId", "ticket-original"); put("snapshotSha256", "a".repeat(64)); put("policySha256", policy.hash)
                    put("producer", producer); put("boundaryEventId", boundary.obj("payload").string("eventId")); put("expiresAt", Instant.now().minusSeconds(10).toString())
                })
                h.ipc.accept(request); val event = next(); val sequence = j.lastSequence()
                val index = h.reply(event, "artifactSetIndexAck", buildJsonObject {
                    put("setId", "set-original"); put("snapshotSha256", "a".repeat(64)); put("eventId", event.obj("payload").string("eventId")); put("eventSeq", event.obj("payload").long("eventSeq")); put("setIndexHash", "b".repeat(64)); put("revision", 1)
                })
                h.ipc.accept(index)
                val statePath = dir.resolve(".runner-state/artifact-sets/state-${sha256((canonical(binding) + boundary.obj("payload").string("eventId")).toByteArray())}.json")
                withTimeout(3000) { while (runnerJson.parseToJsonElement(Files.readString(statePath)).jsonObject["index"] == null) delay(10) }
                val durable = Files.readAllBytes(statePath)
                target!!.close(); target = build()
                assertEquals(listOf(boundary.obj("payload").string("eventId")), target!!.ready(producer, listOf("decl"), "FAILED"))
                assertEquals(boundary, next()); assertEquals(sequence, j.lastSequence())
                h.ipc.accept(request); assertEquals(event, next()); assertContentEquals(durable, Files.readAllBytes(statePath)); assertEquals(sequence, j.lastSequence())
                ack(event); val complete = next(); RunnerArtifactSetProtocol.validate(complete, "completeFrame")
                assertEquals(request.string("frameId"), complete.string("replyTo")); assertEquals("b".repeat(64), complete.obj("payload").string("setIndexHash"))
                target!!.close(); target = build(); target!!.ready(producer, listOf("decl"), "FAILED"); assertEquals(boundary, next())
                h.ipc.accept(request); assertEquals(complete, next()); assertEquals(sequence, j.lastSequence())
                assertFails { target!!.ready(producer, listOf("decl"), "SUCCESS") }
                active = false
                assertFails { target!!.ready(producer, listOf("decl"), "FAILED") }
                val completedBytes = Files.readAllBytes(statePath)
                h.ipc.accept(request); withTimeout(3000) { while (runCatching { gate.check() }.isSuccess) delay(10) }
                assertContentEquals(completedBytes, Files.readAllBytes(statePath)); assertTrue(observed.tryReceive().isFailure)
            } finally { target?.close(); pump.cancel(); h.supervisorInput.close() }
        }
    }
}
