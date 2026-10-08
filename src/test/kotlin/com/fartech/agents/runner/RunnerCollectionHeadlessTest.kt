package com.fartech.agents.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real E JVM + Bash same-code wait. S snapshot/chunk/index/gate services below are controlled fixtures. */
class RunnerCollectionHeadlessTest {
    @Test fun `root child same-code gate waits for dual ACK and loss retries preserve bytes and sequence`(@TempDir dir: Path) = runFixture(dir, "normal")
    @Test fun `wrong policy interrupts before event and cannot release same-code gate`(@TempDir dir: Path) = runFixture(dir, "wrong-policy")
    @Test fun `cancel with publication awaiting ACK never returns completion or opens gate`(@TempDir dir: Path) = runFixture(dir, "cancel")
    @Test fun `carrier without negotiated capability refuses before code executes`(@TempDir dir: Path) = runFixture(dir, "missing-capability")

    @Test fun `unACKed producer start cannot authorize collection`(@TempDir dir: Path) = runFixture(dir, "unacked-start")
    @Test fun `platform whole-file hash rejection cannot return completion`(@TempDir dir: Path) = runFixture(dir, "wrong-hash")
    @Test fun `changed duplicate request refuses without new event or gate`(@TempDir dir: Path) = runFixture(dir, "changed-request")
    @Test fun `closed root occurrence cannot reuse its completion while child begins`(@TempDir dir: Path) = runFixture(dir, "late-root")
    @Test fun `code completion with uncollected declared material cannot advance to child`(@TempDir dir: Path) = runFixture(dir, "early-exit")

    @Test fun `same declaration occurrence cannot bypass original pending ACK with all fresh IDs`(@TempDir dir: Path) = runFixture(dir, "reidentified")

    private fun runFixture(dir: Path, mode: String) {
        Files.createDirectories(dir.resolve("run"))
        fun script(id: String) = "printf 'material-$id' > '${dir.resolve("run/$id.txt")}'; while [ ! -f '${dir.resolve("run/$id.release")}' ]; do sleep 0.01; done; printf 'gate-$id-opened'"
        val f = collectionFixture(dir, script("root"), script("child"))
        val bindingFile = dir.resolve("binding.json"); Files.writeString(bindingFile, f.binding.toString())
        val engine = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("runner.test.runtimeClasspath"), "com.fartech.agents.runner.RunnerHostMainKt", "--binding", bindingFile.toString())
            .directory(dir.toFile()).redirectError(dir.resolve("stderr.log").toFile()).start()
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val killer = timer.schedule({ engine.destroyForcibly() }, 25, TimeUnit.SECONDS)
        val tools = mutableMapOf<String, Process>()
        try {
            val reader = engine.inputStream.bufferedReader(); val writer = engine.outputStream.bufferedWriter()
            fun send(frame: JsonObject) { writer.write(canonical(frame)); writer.newLine(); writer.flush() }
            fun base(type: String, payload: JsonObject, reply: String? = null) = send(RunnerProtocolTest.frame(f.binding, type, payload, reply))
            fun read(): Pair<String, JsonObject>? {
                val line = reader.readLine() ?: return null
                val frame = runnerJson.parseToJsonElement(line).jsonObject
                if (frame.string("type") == "artifactCollectComplete") RunnerCollectionProtocol.validate(frame, "collectionComplete") else RunnerProtocol().validate(frame, "ipcFrame")
                return line to frame
            }
            val ready = assertNotNull(read()).second
            assertTrue(ready.obj("payload").array("capabilities").any { it.jsonPrimitive.content == RunnerCollectionProtocol.CAPABILITY })
            val now = Instant.now(); val initial = RunnerProtocolTest.environment(f.binding, now)
            val environment = JsonObject(initial + ("egress" to JsonArray(initial.array("egress") + JsonObject(initial.array("egress")[0].jsonObject + ("pathId" to JsonPrimitive("subprocess"))))))
            val original = runnerJson.parseToJsonElement(RunnerProtocolTest.resource("fixtures/valid/claim.json")).jsonObject.obj("authorization")
            val caps = original.array("requiredCapabilities") + JsonPrimitive("control.perCall.v1") + if (mode == "missing-capability") emptyList() else listOf(JsonPrimitive(RunnerCollectionProtocol.CAPABILITY))
            base("start", buildJsonObject {
                put("bundle", f.bundle); put("executionDirectory", dir.toString()); put("environment", environment)
                put("authorization", JsonObject(original + ("requiredCapabilities" to JsonArray(caps)))); put("lease", RunnerProtocolTest.lease(now)); put("inputs", JsonObject(emptyMap()))
            })
            if (mode == "missing-capability") {
                assertNull(read()); assertTrue(engine.waitFor(3, TimeUnit.SECONDS)); assertEquals(2, engine.exitValue())
                assertTrue(Files.readString(dir.resolve("stderr.log")).contains("RUNNER_ARTIFACT_CAPABILITY_REQUIRED")); return
            }
            val starts = mutableMapOf<String, JsonObject>(); val requests = mutableMapOf<String, JsonObject>()
            val originalEvents = mutableMapOf<String, String>(); val originalCompletions = mutableMapOf<String, String>()
            val indexed = mutableMapOf<String, String>(); val completeCounts = mutableMapOf<String, Int>()
            var lateSent = false
            val allEvents = mutableMapOf<String, String>(); var lastSequence = 0L; var result: JsonObject? = null
            while (true) {
                val (line, frame) = read() ?: break
                val payload = frame.obj("payload")
                when (frame.string("type")) {
                    "event" -> {
                        val eventId = payload.string("eventId")
                        val old = allEvents.putIfAbsent(eventId, line)
                        if (old == null) { assertEquals(lastSequence + 1, payload.long("eventSeq")); lastSequence++ } else assertEquals(old, line)
                        val detail = payload.obj("payload")
                        if (payload.string("type") == "artifact") {
                            val id = detail.string("producerWorkflowId")
                            assertEquals(requests.getValue(id).string("frameId"), frame.string("replyTo"))
                            assertEquals(starts.getValue(id).obj("payload").string("eventId"), detail.string("producerStartEventId"))
                            val bytes = Files.readAllBytes(dir.resolve("fixture-spool-$id"))
                            if (mode == "wrong-hash") {
                                assertNotEquals(sha256(bytes), detail.string("sha256"))
                                base("error", buildJsonObject { put("code", "ARTIFACT_FULL_HASH_MISMATCH"); put("message", "fixture rejected raw file hash"); put("retryable", false); put("serverTime", Instant.now().toString()) }, frame.string("frameId"))
                                continue
                            }
                            assertEquals(sha256(bytes), detail.string("sha256")); assertEquals(bytes.size.toLong(), detail.long("size"))
                            assertFalse(Files.exists(dir.resolve("run/$id.release")))
                            if (mode == "changed-request") {
                                val originalRequest = requests.getValue(id)
                                send(JsonObject(originalRequest + ("payload" to JsonObject(originalRequest.obj("payload") + ("sha256" to JsonPrimitive("0".repeat(64)))))))
                                continue
                            }
                            if (mode == "cancel") { base("cancel", buildJsonObject { put("commandId", "cancel-barrier"); put("reason", "fixture") }); continue }
                            indexed.putIfAbsent(detail.string("publicationId"), sha256(bytes))?.let { assertEquals(sha256(bytes), it) }
                            if (mode == "reidentified") {
                                val originalRequest = requests.getValue(id)
                                send(JsonObject(originalRequest + mapOf("frameId" to JsonPrimitive("fresh-frame"), "payload" to JsonObject(originalRequest.obj("payload") + mapOf(
                                    "requestId" to JsonPrimitive("fresh-request"), "publicationId" to JsonPrimitive("fresh-publication"), "artifactId" to JsonPrimitive("fresh-artifact")
                                )))))
                                continue // original index effect exists, original ACK is deliberately withheld
                            }
                            val first = originalEvents.putIfAbsent(id, line)
                            if (id == "root" && first == null) {
                                send(requests.getValue(id)) // simulate lost event/index ACK; original event must replay byte-for-byte
                            } else {
                                if (first != null) assertEquals(first, line)
                                base("ack", buildJsonObject { put("ackSeq", payload.long("eventSeq")); put("revision", indexed.size); put("serverTime", Instant.now().toString()) }, frame.string("frameId"))
                            }
                        } else {
                            if ((detail["event"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "code_step_started") {
                                val id = if (detail.string("executionId") == f.binding.string("executionId")) "root" else "child"
                                starts[id] = frame
                            }
                            val eventType = (detail["event"] as? JsonObject)?.get("type")?.jsonPrimitive?.content
                            if (!(mode == "unacked-start" && eventType == "code_step_started")) base("ack", buildJsonObject { put("ackSeq", payload.long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) })
                            if (mode == "late-root" && eventType == "code_step_completed" && !lateSent) { lateSent = true; send(requests.getValue("root")) }
                        }
                    }
                    "processLaunch" -> {
                        val id = if (tools.isEmpty()) "root" else "child"
                        assertEquals(if (id == "root") 7000L else 7200L, payload.long("timeoutSeconds"))
                        assertFalse(payload.obj("environment").containsKey("WF_API_TOKEN"))
                        val command = payload.array("argv").map { it.jsonPrimitive.content }
                        val tool = ProcessBuilder(listOf("/bin/bash") + command.drop(1)).directory(dir.resolve(payload.string("relativeCwd")).toFile()).apply {
                            environment().clear(); environment()["PATH"] = "/usr/bin:/bin"; environment().putAll(payload.obj("environment").mapValues { it.value.jsonPrimitive.content })
                        }.redirectOutput(dir.resolve("stdout-$id").toFile()).redirectError(dir.resolve("stderr-$id").toFile()).start()
                        tools[id] = tool
                        base("processStarted", buildJsonObject { put("requestId", payload.string("requestId")); put("registrationId", "registration-$id"); put("pid", tool.pid()); put("processGroupId", tool.pid()); put("startIdentity", tool.info().startInstant().get().toString()) }, frame.string("frameId"))
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while ((!Files.exists(dir.resolve("run/$id.txt")) || Files.size(dir.resolve("run/$id.txt")) == 0L) && System.nanoTime() < deadline) Thread.sleep(5)
                        // Controlled S journals IDs before reading and snapshots actual bytes before E request.
                        Files.writeString(dir.resolve("fixture-intent-$id.json"), "{\"declarationId\":\"decl-$id\",\"requestId\":\"request-$id\"}")
                        val bytes = Files.readAllBytes(dir.resolve("run/$id.txt")); Files.write(dir.resolve("fixture-spool-$id"), bytes)
                        if (mode == "early-exit") {
                            Files.writeString(dir.resolve("run/$id.release"), "fixture-process-exit")
                            assertTrue(tool.waitFor(3, TimeUnit.SECONDS))
                            base("processExited", buildJsonObject { put("registrationId", "registration-$id"); put("exitCode", 0); put("stdout", "code-done"); put("stderr", ""); put("stdoutArtifactId", JsonNull); put("stderrArtifactId", JsonNull); put("captureTruncated", false); put("timedOut", false) })
                            continue
                        }
                        val request = collectFrame(f, id, starts.getValue(id).obj("payload").string("eventId"), bytes.size, sha256(bytes))
                        requests[id] = when (mode) {
                            "wrong-policy" -> JsonObject(request + ("payload" to JsonObject(request.obj("payload") + ("policySha256" to JsonPrimitive("0".repeat(64))))))
                            "wrong-hash" -> JsonObject(request + ("payload" to JsonObject(request.obj("payload") + ("sha256" to JsonPrimitive("0".repeat(64))))))
                            else -> request
                        }
                        send(requests.getValue(id)) // complete chunk ACK is a fixture predicate established by the snapshot above
                    }
                    "artifactCollectComplete" -> {
                        val id = payload.string("artifactId").removePrefix("artifact-")
                        assertEquals(requests.getValue(id).string("frameId"), frame.string("replyTo"))
                        assertEquals(sha256(Files.readAllBytes(dir.resolve("fixture-spool-$id"))), indexed.getValue(payload.string("publicationId")))
                        val event = runnerJson.parseToJsonElement(originalEvents.getValue(id)).jsonObject.obj("payload")
                        assertEquals(event.string("eventId"), payload.string("eventId")); assertEquals(event.long("eventSeq"), payload.long("eventSeq"))
                        val count = completeCounts.merge(id, 1) { a, b -> a + b }!!
                        val old = originalCompletions.putIfAbsent(id, line); if (old != null) assertEquals(old, line)
                        if (id == "root" && count == 1) { send(requests.getValue(id)); continue } // lost completion, no new event/effect
                        assertEquals(if (id == "root") 2 else 1, count)
                        Files.writeString(dir.resolve("run/$id.release"), "both-ACKs-verified")
                        val tool = tools.getValue(id); assertTrue(tool.waitFor(3, TimeUnit.SECONDS)); assertEquals(0, tool.exitValue())
                        val stdout = Files.readString(dir.resolve("stdout-$id")); assertEquals("gate-$id-opened", stdout)
                        base("processExited", buildJsonObject { put("registrationId", "registration-$id"); put("exitCode", 0); put("stdout", stdout); put("stderr", Files.readString(dir.resolve("stderr-$id"))); put("stdoutArtifactId", JsonNull); put("stderrArtifactId", JsonNull); put("captureTruncated", false); put("timedOut", false) })
                    }
                    "checkpoint" -> Unit
                    "result" -> { result = payload; break }
                    else -> fail("Unexpected frame ${frame.string("type")}")
                }
            }
            if (mode in setOf("changed-request", "reidentified")) {
                assertNull(result, "Rejected changed wire duplicate closes IPC; no remote verdict is fabricated")
                assertTrue(engine.waitFor(3, TimeUnit.SECONDS)); assertEquals(2, engine.exitValue())
                assertTrue(completeCounts.isEmpty()); assertEquals(if (mode == "reidentified") 1 else 0, indexed.size); assertFalse(Files.exists(dir.resolve("run/root.release")))
                assertEquals(1, allEvents.values.count { runnerJson.parseToJsonElement(it).jsonObject.obj("payload").string("type") == "artifact" })
                val key = sha256((canonical(f.binding) + ":request-root").toByteArray())
                val retained = runnerJson.parseToJsonElement(Files.readString(dir.resolve(".runner-state/artifact-collection/$key.json"))).jsonObject
                assertEquals(requests.getValue("root"), retained.obj("request")); assertNull(retained["complete"])
                return
            }
            assertNotNull(result, Files.readString(dir.resolve("stderr.log")))
            assertEquals(when (mode) { "normal" -> "COMPLETED"; "cancel" -> "CANCELLED"; else -> "INTERRUPTED" }, result.string("status"))
            if (mode == "normal") { assertEquals(2, indexed.size); assertEquals(2, tools.size); assertEquals(mapOf("root" to 2, "child" to 1), completeCounts) }
            else if (mode == "late-root") { assertEquals(2, completeCounts["root"]); assertNull(completeCounts["child"]); assertEquals(1, indexed.size) }
            else if (mode == "early-exit") { assertEquals(1, tools.size); assertTrue(completeCounts.isEmpty()); assertTrue(indexed.isEmpty()) }
            else { assertTrue(completeCounts.isEmpty()); assertFalse(Files.exists(dir.resolve("run/root.release"))); assertTrue(indexed.isEmpty()) }
        } finally { killer.cancel(true); timer.shutdownNow(); tools.values.forEach { it.descendants().forEach { child -> child.destroyForcibly() }; it.destroyForcibly() }; engine.destroyForcibly() }
    }
}
