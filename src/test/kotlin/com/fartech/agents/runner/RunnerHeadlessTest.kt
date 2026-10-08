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
    @Test fun `real headless JVM executes through registered launch and protocol stdout only`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("root.yaml"), """
            name: headless-offline
            agents: {}
            workflow:
              - step: host-proof
                code:
                  language: bash
                  script: 'printf "engine-local-proof\\n"; pwd'
        """.trimIndent())
        val bytes = Files.readAllBytes(directory.resolve("root.yaml")); val hash = sha256(bytes)
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val bindingPath = directory.resolve("binding.json"); Files.writeString(bindingPath, binding.toString())
        val manifest = buildJsonObject {
            put("rootWorkflowId", "root"); put("files", JsonArray(listOf(buildJsonObject { put("path", "root.yaml"); put("size", bytes.size); put("sha256", hash); put("role", "definition") })))
            put("definitions", JsonArray(listOf(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) })))
        }
        val bundle = JsonObject(manifest + ("bundleHash" to JsonPrimitive(sha256(canonical(manifest).toByteArray()))))
        val classpath = requireNotNull(System.getProperty("runner.test.runtimeClasspath"))
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, "com.fartech.agents.runner.RunnerHostMainKt", "--binding", bindingPath.toString())
            .directory(directory.toFile()).redirectError(directory.resolve("stderr.log").toFile()).start()
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val killer = scheduler.schedule({ process.destroyForcibly() }, 15, TimeUnit.SECONDS)
        try {
            val reader = process.inputStream.bufferedReader(); val writer = process.outputStream.bufferedWriter()
            fun send(type: String, payload: JsonObject, reply: String? = null) {
                writer.write(RunnerProtocolTest.frame(binding, type, payload, reply).toString()); writer.newLine(); writer.flush()
            }
            val ready = RunnerProtocol().parse(reader.readLine()); assertEquals("ready", ready.string("type"))
            val now = Instant.now()
            val baseEnvironment = RunnerProtocolTest.environment(binding, now)
            val environment = JsonObject(baseEnvironment + ("egress" to JsonArray(baseEnvironment.array("egress") + JsonObject(baseEnvironment.array("egress")[0].jsonObject + ("pathId" to JsonPrimitive("subprocess"))))))
            val authorization = runnerJson.parseToJsonElement(RunnerProtocolTest.resource("fixtures/valid/claim.json")).jsonObject.obj("authorization")
            send("start", buildJsonObject { put("bundle", bundle); put("executionDirectory", directory.toString()); put("environment", environment)
                put("authorization", authorization); put("lease", RunnerProtocolTest.lease(now)); put("inputs", JsonObject(emptyMap())) })
            var launched = false; var stepProof = false; var result: JsonObject? = null
            while (true) {
                val line = reader.readLine() ?: break
                val frame = RunnerProtocol().parse(line) // any ordinary println on stdout fails this test
                val payload = frame.obj("payload")
                when (frame.string("type")) {
                    "processLaunch" -> {
                        assertFalse(launched); launched = true
                        val cwd = directory.resolve(payload.string("relativeCwd")).normalize()
                        assertTrue(cwd.startsWith(directory)); assertEquals("CODE_STEP", payload.string("purpose"))
                        val argv = payload.array("argv").map { it.jsonPrimitive.content }
                        val tool = ProcessBuilder(listOf("/bin/bash") + argv.drop(1)).directory(cwd.toFile()).apply {
                            environment().putAll(payload.obj("environment").mapValues { it.value.jsonPrimitive.content })
                        }.start()
                        send("processStarted", buildJsonObject { put("requestId", payload.string("requestId")); put("registrationId", "offline-registration")
                            put("pid", tool.pid()); put("processGroupId", tool.pid()); put("startIdentity", tool.info().startInstant().get().toString()) }, frame.string("frameId"))
                        val stdout = tool.inputStream.readBytes().toString(Charsets.UTF_8); val stderr = tool.errorStream.readBytes().toString(Charsets.UTF_8)
                        assertEquals(0, tool.waitFor()); assertTrue(stdout.contains(directory.toString()))
                        send("processExited", buildJsonObject { put("registrationId", "offline-registration"); put("exitCode", 0)
                            put("stdoutArtifactId", JsonNull); put("stderrArtifactId", JsonNull); put("stdout", stdout); put("stderr", stderr)
                            put("captureTruncated", false); put("timedOut", false) })
                    }
                    "event" -> if (payload.obj("payload")["output"]?.jsonPrimitive?.content?.contains("engine-local-proof") == true) stepProof = true
                    "checkpoint" -> assertEquals(payload.string("checkpointHash"), sha256(Files.readAllBytes(directory.resolve(payload.string("relativePath")))))
                    "result" -> { result = payload; break }
                    else -> fail("Unexpected host frame: ${frame.string("type")}")
                }
            }
            assertTrue(launched, "No process launch; result=$result; stderr=" + Files.readString(directory.resolve("stderr.log")))
            assertTrue(stepProof, "No step proof; result=$result; stderr=" + Files.readString(directory.resolve("stderr.log")))
            assertNotNull(result); assertEquals("COMPLETED", result.string("status")); assertEquals(JsonPrimitive(false), result.obj("stopReceipt")["confirmed"])
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), Files.readString(directory.resolve("stderr.log")))
        } finally { killer.cancel(true); scheduler.shutdownNow(); process.destroyForcibly() }
    }
}
