package com.fartech.agents.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real JVM and local bash process, synthetic trusted supervisor/egress. No external accounts. */
class RunnerHeadlessTest {
    @Test fun `real headless JVM executes through registered launch and protocol stdout only`(@TempDir directory: Path) = runFixture(directory, false)
    @Test fun `root callback echo is redacted and secret-bearing checkpoint is refused`(@TempDir directory: Path) = runFixture(directory, true)

    @Test fun `child callback echo is redacted and secret-bearing checkpoint is refused`(@TempDir directory: Path) = runFixture(directory, true, true)

    @Test fun `authorized root callback completes when result does not echo token`(@TempDir directory: Path) = runFixture(directory, true, false, false)
    @Test fun `authorized child callback completes with its signed invocation scope`(@TempDir directory: Path) = runFixture(directory, true, true, false)

    @Test fun `per-call NDJSON root retains 7000 timeout without static callback token`(@TempDir directory: Path) = runFixture(directory, false, perCall = true, codeTimeout = 7000)
    @Test fun `per-call NDJSON child retains 7200 timeout and durable original code event identity`(@TempDir directory: Path) = runFixture(directory, false, childScope = true, perCall = true, codeTimeout = 7200)
    @Test fun `per-call short code also receives no static token`(@TempDir directory: Path) = runFixture(directory, false, perCall = true)
    @Test fun `conflicting callback modes refuse NDJSON start before any business event`(@TempDir directory: Path) = runFixture(directory, true, echoSecret = false, perCall = true, rejectStart = true)

    @Test fun `missing packaged supplement refuses per-call NDJSON before business execution`(@TempDir directory: Path) = runFixture(directory, false, perCall = true, rejectStart = true, missingSupplement = true)

    @Test fun `a lease renewal that arrives before ready and start does not stop the engine`(@TempDir directory: Path) = runFixture(directory, false, earlyLeaseId = "lease")
    @Test fun `an early lease renewal for another lease identity still refuses startup`(@TempDir directory: Path) = runFixture(directory, false, rejectStart = true, earlyLeaseId = "other-lease")
    @Test fun `an environment refresh that arrives before ready and start does not stop the engine`(@TempDir directory: Path) = runFixture(directory, false, earlyEnvironment = "newer")
    @Test fun `an early environment refresh for another attempt still stops the engine before any business frame`(@TempDir directory: Path) = runFixture(directory, false, earlyEnvironment = "foreign")

    /**
     * Three concurrent code steps each hold a supervisor-registered launch when S cancels. The stop must reach every
     * branch (each reports step_cancelled), no later step may launch, and the result stays CANCELLED with the
     * unconfirmed receipt S completes from its own registrations: E never starts an unregistered process.
     */
    @Test fun `a cancel reaches every concurrent branch holding a registered launch`(@TempDir directory: Path) {
        fun branch(name: String) = "  - step: $name\n    code:\n      language: bash\n      timeout: 60\n      script: 'sleep 30'\n"
        val source = "name: concurrent-stop\nagents: {}\nconcurrency:\n  enabled: true\n  max_concurrency: 3\nworkflow:\n" +
            branch("left") + branch("middle") + branch("right") +
            "  - step: never\n    depends_on: [left, middle, right]\n    code:\n      language: bash\n      script: 'true'\n"
        Files.writeString(directory.resolve("root.yaml"), source)
        val hash = sha256(source.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val bindingPath = directory.resolve("binding.json"); Files.writeString(bindingPath, binding.toString())
        val manifest = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("size", source.toByteArray().size); put("sha256", hash); put("role", "definition") }) })
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = JsonObject(manifest + ("bundleHash" to JsonPrimitive(sha256(canonical(manifest).toByteArray()))))
        val classpath = requireNotNull(System.getProperty("runner.test.runtimeClasspath"))
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, "com.fartech.agents.runner.RunnerHostMainKt", "--binding", bindingPath.toString())
            .directory(directory.toFile()).redirectError(directory.resolve("stderr.log").toFile()).start()
        val tools = mutableListOf<Process>()
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val killer = scheduler.schedule({ process.destroyForcibly() }, 30, TimeUnit.SECONDS)
        try {
            val reader = process.inputStream.bufferedReader(); val writer = process.outputStream.bufferedWriter()
            fun send(type: String, payload: JsonObject, reply: String? = null) {
                writer.write(RunnerProtocolTest.frame(binding, type, payload, reply).toString()); writer.newLine(); writer.flush()
            }
            assertEquals("ready", RunnerProtocol().parse(reader.readLine()).string("type"), Files.readString(directory.resolve("stderr.log")))
            val now = Instant.now(); val base = RunnerProtocolTest.environment(binding, now)
            val environment = JsonObject(base + mapOf("environmentCheckId" to JsonPrimitive("start-check"),
                "egress" to JsonArray(base.array("egress") + JsonObject(base.array("egress")[0].jsonObject + ("pathId" to JsonPrimitive("subprocess"))))))
            val authorization = runnerJson.parseToJsonElement(RunnerProtocolTest.resource("fixtures/valid/claim.json")).jsonObject.obj("authorization")
            send("start", buildJsonObject { put("bundle", bundle); put("executionDirectory", directory.toString()); put("environment", environment)
                put("authorization", authorization); put("lease", RunnerProtocolTest.lease(now)); put("inputs", JsonObject(emptyMap())) })
            val registered = linkedMapOf<String, String>() // registration -> step
            val starts = mutableMapOf<String, String>() // executionId/step from code_step_started, in launch order
            val cancelledSteps = mutableSetOf<String>(); var cancelSent = false; var result: JsonObject? = null
            val pendingStarts = ArrayDeque<String>()
            while (true) {
                val line = reader.readLine() ?: break
                val frame = RunnerProtocol().parse(line); val payload = frame.obj("payload")
                when (frame.string("type")) {
                    "processLaunch" -> {
                        assertFalse(cancelSent, "No branch may launch after the stop")
                        val tool = ProcessBuilder("/bin/sleep", "30").start(); tools += tool
                        val registration = "registration-${registered.size + 1}"
                        registered[registration] = pendingStarts.removeFirstOrNull() ?: "?"
                        send("processStarted", buildJsonObject { put("requestId", payload.string("requestId")); put("registrationId", registration)
                            put("pid", tool.pid()); put("processGroupId", tool.pid()); put("startIdentity", tool.info().startInstant().get().toString()) }, frame.string("frameId"))
                        if (registered.size == 3) {
                            cancelSent = true
                            send("cancel", buildJsonObject { put("commandId", "stop-fixture"); put("reason", "owner stop") })
                        }
                    }
                    "event" -> {
                        val body = payload.obj("payload")
                        if ((body["event"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "code_step_started") pendingStarts.addLast(body.string("stepName"))
                        if (body["kind"]?.jsonPrimitive?.content == "step_cancelled") cancelledSteps += body.string("stepName")
                        send("ack", buildJsonObject { put("ackSeq", payload.long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) })
                    }
                    "checkpoint" -> Unit
                    "result" -> { result = payload; break }
                    else -> fail("Unexpected host frame: ${frame.string("type")}")
                }
            }
            assertNotNull(result, Files.readString(directory.resolve("stderr.log")))
            assertEquals(setOf("left", "middle", "right"), registered.values.toSet(), "all three branches ran at once, each under its own registration")
            assertEquals(setOf("left", "middle", "right"), cancelledSteps, "the stop reached every running branch")
            assertEquals("CANCELLED", result.string("status")); assertEquals("CANCELLED", result.string("terminationReason"))
            val receipt = result.obj("stopReceipt")
            assertEquals(JsonPrimitive(false), receipt["confirmed"], "S confirms the stop from its own registrations")
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(1, process.exitValue())
        } finally { killer.cancel(true); scheduler.shutdownNow(); process.destroyForcibly(); tools.forEach { it.destroyForcibly() } }
    }

    private fun runFixture(directory: Path, callbackEnabled: Boolean, childScope: Boolean = false, echoSecret: Boolean = callbackEnabled, perCall: Boolean = false, codeTimeout: Int = 30, rejectStart: Boolean = false, missingSupplement: Boolean = false, earlyLeaseId: String? = null, earlyEnvironment: String? = null) {
        val callbackEcho = if (echoSecret) "printf \"%s\\n\" \"${'$'}WF_API_TOKEN\"; printf \"%s\\n\" \"${'$'}WF_API_TOKEN\" >&2; " else ""
        val absence = if (perCall) "test -z \"${'$'}{WF_API_TOKEN+x}\"; test -z \"${'$'}{WF_VAR_WF_API_TOKEN+x}\"; " else ""
        val body = """
            name: headless-offline
            agents: {}
            workflow:
              - step: host-proof
                manual_approval:
                  enabled: true
                  timeout: 30
                  approval_message: Offline fixture approval
                code:
                  language: bash
                  timeout: $codeTimeout
                  script: '${absence}${callbackEcho}printf "engine-local-proof\\n"; pwd'
        """.trimIndent()
        Files.writeString(directory.resolve("root.yaml"), if (childScope) """
            name: callback-parent
            agents: {}
            workflow:
              - step: nested
                sub_workflow:
                  workflow_id: child
        """.trimIndent() else body)
        if (childScope) Files.writeString(directory.resolve("child.yaml"), body)
        val bytes = Files.readAllBytes(directory.resolve("root.yaml")); val hash = sha256(bytes)
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val oldTokenFile = directory.resolve(".runner-private/old-token")
        if (perCall) { Files.createDirectories(oldTokenFile.parent); Files.writeString(oldTokenFile, "STALE_ndjson_file_secret") }
        val bindingPath = directory.resolve("binding.json"); Files.writeString(bindingPath, binding.toString())
        val definitionFiles = if (childScope) listOf("root" to "root.yaml", "child" to "child.yaml") else listOf("root" to "root.yaml")
        val manifest = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(definitionFiles.sortedBy { it.second }.map { (_, path) -> buildJsonObject {
                val content = Files.readAllBytes(directory.resolve(path)); put("path", path); put("size", content.size)
                put("sha256", sha256(content)); put("role", "definition")
            } }))
            put("definitions", JsonArray(definitionFiles.sortedBy { it.first }.map { (id, path) -> buildJsonObject {
                put("workflowId", id); put("path", path); put("definitionHash", sha256(Files.readAllBytes(directory.resolve(path))))
            } }))
        }
        val bundle = JsonObject(manifest + ("bundleHash" to JsonPrimitive(sha256(canonical(manifest).toByteArray()))))
        var classpath = requireNotNull(System.getProperty("runner.test.runtimeClasspath"))
        if (missingSupplement) {
            // Negative packaging fixture only: never edit the real resource tree or installed JAR.
            val original = Path.of(javaClass.getResource("/runner-workflow-v1/late-control.schema.json")!!.toURI()).parent.parent.toRealPath()
            val replacement = directory.resolve("fixture-resources")
            Files.walk(original).use { paths -> paths.forEach { source ->
                val relative = original.relativize(source)
                if (relative.toString() != "runner-workflow-v1/late-control.schema.json") {
                    val target = replacement.resolve(relative)
                    if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
                }
            } }
            var replaced = false
            classpath = classpath.split(java.io.File.pathSeparator).joinToString(java.io.File.pathSeparator) { entry ->
                if (Files.exists(Path.of(entry)) && Path.of(entry).toRealPath() == original) { replaced = true; replacement.toString() } else entry
            }
            assertTrue(replaced)
        }
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, "com.fartech.agents.runner.RunnerHostMainKt", "--binding", bindingPath.toString())
            .directory(directory.toFile()).redirectError(directory.resolve("stderr.log").toFile()).start()
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val killer = scheduler.schedule({ process.destroyForcibly() }, 15, TimeUnit.SECONDS)
        try {
            val reader = process.inputStream.bufferedReader(); val writer = process.outputStream.bufferedWriter()
            fun send(type: String, payload: JsonObject, reply: String? = null) {
                writer.write(RunnerProtocolTest.frame(binding, type, payload, reply).toString()); writer.newLine(); writer.flush()
            }
            // S renews from claim time and forwards the renewal as soon as the JVM is spawned.
            if (earlyLeaseId != null) send("leaseRenewed", buildJsonObject { put("lease", JsonObject(RunnerProtocolTest.lease(Instant.now()) + ("leaseId" to JsonPrimitive(earlyLeaseId)))) })
            fun evidence(at: Instant, checkId: String, forBinding: JsonObject = binding): JsonObject {
                val base = RunnerProtocolTest.environment(forBinding, at)
                return JsonObject(base + mapOf("environmentCheckId" to JsonPrimitive(checkId),
                    "egress" to JsonArray(base.array("egress") + JsonObject(base.array("egress")[0].jsonObject + ("pathId" to JsonPrimitive("subprocess"))))))
            }
            // S refreshes evidence on its own schedule; this refresh is newer than the evidence the start frame carries.
            val earlyAt = Instant.now()
            if (earlyEnvironment == "newer") send("environmentRefreshed", evidence(earlyAt, "early-refresh"))
            val ready = RunnerProtocol().parse(reader.readLine()); assertEquals("ready", ready.string("type"))
            if (earlyEnvironment == "foreign") {
                send("environmentRefreshed", evidence(Instant.now(), "foreign-refresh", JsonObject(binding + ("attemptId" to JsonPrimitive("another-attempt")))))
                assertNull(reader.readLine(), "A refresh for another attempt must stop the engine before any business frame")
                assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(2, process.exitValue())
                assertFalse(Files.exists(directory.resolve(".runner-state/events")))
                assertTrue(Files.readString(directory.resolve("stderr.log")).contains("Runner host refused startup or lost authority"))
                return
            }
            val now = Instant.now()
            val environment = evidence(if (earlyEnvironment == "newer") earlyAt.minusSeconds(5) else now, "start-check")
            val originalAuthorization = runnerJson.parseToJsonElement(RunnerProtocolTest.resource("fixtures/valid/claim.json")).jsonObject.obj("authorization")
            assertEquals(!missingSupplement, ready.obj("payload").array("capabilities").any { it.jsonPrimitive.content == "control.perCall.v1" })
            val requested = originalAuthorization.array("requiredCapabilities") +
                (if (callbackEnabled) listOf(JsonPrimitive("control.callback.v1")) else emptyList()) +
                (if (perCall) listOf(JsonPrimitive("control.perCall.v1")) else emptyList())
            val authorization = JsonObject(originalAuthorization + ("requiredCapabilities" to JsonArray(requested)))
            send("start", buildJsonObject { put("bundle", bundle); put("executionDirectory", directory.toString()); put("environment", environment)
                put("authorization", authorization); put("lease", RunnerProtocolTest.lease(now)); put("inputs", if (perCall) buildJsonObject { put("WF_API_TOKEN", "STALE_ndjson_input"); put("execution_api_token", "STALE_ndjson_alias"); put("WF_API_TOKEN__FILE", oldTokenFile.toString()); put("WF_VAR_WF_API_TOKEN__FILE", oldTokenFile.toString()); put("WF_VAR_EXECUTION_API_TOKEN__FILE", oldTokenFile.toString()); put("large", "spill-proof-".repeat(20000)) } else JsonObject(emptyMap())) })
            if (rejectStart) {
                assertNull(reader.readLine(), "Rejected negotiation must produce no business frames")
                assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(2, process.exitValue())
                assertFalse(Files.exists(directory.resolve(".runner-state/events")))
                assertTrue(Files.readString(directory.resolve("stderr.log")).contains(when {
                    earlyLeaseId != null -> "RunnerAuthorityLost"; missingSupplement -> "RUNNER_CONTROL_SUPPLEMENT_UNAVAILABLE"; else -> "RUNNER_CONTROL_MODE_CONFLICT" }))
                return
            }
            var codeStartEvent: JsonObject? = null
            var redactedEchoEvent = false; var callbackRequested = false; var launched = false; var stepProof = false; var approved = false; var result: JsonObject? = null
            while (true) {
                val line = reader.readLine() ?: break
                val frame = RunnerProtocol().parse(line) // any ordinary println on stdout fails this test
                val payload = frame.obj("payload")
                when (frame.string("type")) {
                    "approval" -> {
                        assertFalse(launched, "Approval must precede any business process")
                        assertFalse(approved); approved = true
                        val refreshed = JsonObject(RunnerProtocolTest.environment(binding, Instant.now()) + mapOf(
                            "environmentCheckId" to JsonPrimitive("post-approval-check"))).let { fresh ->
                                JsonObject(fresh + ("egress" to JsonArray(fresh.array("egress") + JsonObject(fresh.array("egress")[0].jsonObject + ("pathId" to JsonPrimitive("subprocess"))))))
                            }
                        send("environmentRefreshed", refreshed, frame.string("frameId"))
                        send("approvalDecision", buildJsonObject {
                            put("approvalId", "human-fixture"); put("idempotencyKey", payload.string("idempotencyKey")); put("occurrenceId", payload.string("occurrenceId"))
                            put("decisionVersion", 1); put("decision", "APPROVE"); put("editMode", "AS_IS"); put("editedItems", JsonArray(emptyList()))
                            put("comment", JsonNull); put("decidedBy", "fixture-human"); put("decidedAt", Instant.now().toString())
                        }, frame.string("frameId"))
                    }
                    "callbackTokenRequest" -> {
                        assertTrue(callbackEnabled); assertFalse(callbackRequested); callbackRequested = true
                        assertEquals(30, payload.long("operationBudgetSeconds"))
                        assertEquals(if (childScope) listOf("nested", "call_1") else emptyList(), payload.array("childExecutionPath").map { it.jsonPrimitive.content })
                        assertEquals(!childScope, payload.array("operations").any { it.jsonPrimitive.content == "artifact.read" })
                        send("callbackTokenGrant", buildJsonObject {
                            put("requestId", payload.string("requestId")); put("token", "fake-code-callback-token")
                            put("expiresAt", Instant.now().plusSeconds(90).toString()); put("operations", payload.array("operations"))
                        }, frame.string("frameId"))
                    }
                    "processLaunch" -> {
                        assertEquals(codeTimeout.toLong(), payload.long("timeoutSeconds"))
                        val originalEvent = assertNotNull(codeStartEvent, "Code start must precede registered launch")
                        assertNotEquals(originalEvent.string("frameId"), originalEvent.obj("payload").string("eventId"))
                        if (perCall) {
                            assertFalse(payload.toString().contains("STALE_ndjson"))
                            assertFalse(payload.obj("environment").toString().contains(oldTokenFile.toString()))
                            Files.walk(directory.resolve(payload.string("relativeCwd"))).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
                                assertFalse(Files.readString(path).contains("STALE_ndjson"), "Old token entered script/spill")
                            } }
                        }
                        assertEquals(callbackEnabled, payload.obj("environment").containsKey("WF_API_TOKEN"))
                        if (callbackEnabled) assertEquals("fake-code-callback-token", payload.obj("environment").string("WF_API_TOKEN"))
                        assertFalse(launched); launched = true
                        val cwd = directory.resolve(payload.string("relativeCwd")).normalize()
                        assertTrue(cwd.startsWith(directory)); assertEquals("CODE_STEP", payload.string("purpose"))
                        val argv = payload.array("argv").map { it.jsonPrimitive.content }
                        // Held at a stdin gate until S has read its identity: the short script otherwise can exit and be reaped first,
                        // leaving ProcessHandle.info() empty. exec keeps the pid and start time S registers.
                        val tool = ProcessBuilder(listOf("/bin/bash", "-c", "read -r _; exec \"\$@\"", "registered-launch", "/bin/bash") + argv.drop(1)).directory(cwd.toFile()).apply {
                            environment().putAll(payload.obj("environment").mapValues { it.value.jsonPrimitive.content })
                        }.start()
                        val startIdentity = tool.info().startInstant().get().toString()
                        tool.outputStream.close()
                        send("processStarted", buildJsonObject { put("requestId", payload.string("requestId")); put("registrationId", "offline-registration")
                            put("pid", tool.pid()); put("processGroupId", tool.pid()); put("startIdentity", startIdentity) }, frame.string("frameId"))
                        val stdout = tool.inputStream.readBytes().toString(Charsets.UTF_8); val stderr = tool.errorStream.readBytes().toString(Charsets.UTF_8)
                        assertEquals(0, tool.waitFor()); assertTrue(stdout.contains(directory.toString()))
                        if (echoSecret) { assertTrue(stdout.contains("fake-code-callback-token")); assertTrue(stderr.contains("fake-code-callback-token")) }
                        send("processExited", buildJsonObject { put("registrationId", "offline-registration"); put("exitCode", 0)
                            put("stdoutArtifactId", JsonNull); put("stderrArtifactId", JsonNull); put("stdout", stdout); put("stderr", stderr)
                            put("captureTruncated", false); put("timedOut", false) })
                    }
                    "event" -> {
                        if ((payload.obj("payload")["event"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "code_step_started") {
                            codeStartEvent = frame
                            val stored = runnerJson.parseToJsonElement(Files.readString(directory.resolve(".runner-state/events/${payload.long("eventSeq")}.json")))
                            assertEquals(frame, stored)
                            assertEquals(if (childScope) "${binding.string("executionId")}__nested__call_1" else binding.string("executionId"), payload.obj("payload").string("executionId"))
                            assertEquals("host-proof", payload.obj("payload").string("stepName"))
                        }
                        // Synthetic durable ACK only; actual platform persistence remains S/P-owned.
                        send("ack", buildJsonObject { put("ackSeq", payload.long("eventSeq")); put("revision", 1); put("serverTime", Instant.now().toString()) })
                        assertFalse(frame.toString().contains("fake-code-callback-token"))
                        if (frame.toString().contains("<redacted>")) redactedEchoEvent = true
                        assertNotEquals("approval", payload.string("type"), "Approval must never enter the event batch")
                        if (payload.obj("payload")["output"]?.jsonPrimitive?.content?.contains("engine-local-proof") == true) stepProof = true
                    }
                    "checkpoint" -> assertEquals(payload.string("checkpointHash"), sha256(Files.readAllBytes(directory.resolve(payload.string("relativePath")))))
                    "result" -> { result = payload; break }
                    else -> fail("Unexpected host frame: ${frame.string("type")}")
                }
            }
            assertEquals(callbackEnabled, callbackRequested)
            assertFalse(Files.readString(directory.resolve(".runner-state/result.json")).contains("fake-code-callback-token"))
            assertTrue(approved, "Dedicated approval frame missing")
            assertTrue(launched, "No process launch; result=$result; stderr=" + Files.readString(directory.resolve("stderr.log")))
            assertTrue(stepProof, "No step proof; result=$result; stderr=" + Files.readString(directory.resolve("stderr.log")))
            assertNotNull(result); assertEquals(if (echoSecret) "FAILED" else "COMPLETED", result.string("status"), Files.readString(directory.resolve(".runner-state/result.json"))); assertEquals(JsonPrimitive(false), result.obj("stopReceipt")["confirmed"])
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(if (echoSecret) 1 else 0, process.exitValue(), Files.readString(directory.resolve("stderr.log")))
            assertFalse(Files.readString(directory.resolve("stderr.log")).contains("fake-code-callback-token"))
            Files.walk(directory.resolve(".runner-state")).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
                assertFalse(Files.readString(path).contains("fake-code-callback-token"), "Secret persisted at $path")
            } }
            if (echoSecret) {
                val saved = Files.readString(directory.resolve(".runner-state/result.json"))
                assertTrue(redactedEchoEvent, "Actual token echo must reach a redacted event")
                assertTrue(saved.contains("Credential material cannot be checkpointed"))
            }
        } finally { killer.cancel(true); scheduler.shutdownNow(); process.destroyForcibly() }
    }
}
