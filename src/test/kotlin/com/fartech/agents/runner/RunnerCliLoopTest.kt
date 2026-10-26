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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real local fake CLI processes, real workflow/IPC/lease lifecycle; no account or model calls. */
class RunnerCliLoopTest {
    @Test fun `parallel CLI items isolate sessions and release leases before parent completion`(@TempDir dir: Path) = fixture(dir, false)
    @Test fun `failed CLI item cancels siblings and releases all acquired leases`(@TempDir dir: Path) = fixture(dir, true)

    private fun fixture(dir: Path, fail: Boolean) = runBlocking<Unit> {
        val fake = dir.resolve("fake-claude")
        val script = """
            #!/bin/bash
            set -eu
            prompt=${'$'}(cat)
            [[ "${'$'}HOME" == *".runner-private"* ]]
            [[ "${'$'}CLAUDE_CONFIG_DIR" == *".runner-private"* ]]
            [[ "${'$'}CLAUDE_CODE_OAUTH_TOKEN" == fixture-cli-secret-* ]]
            [[ "${'$'}WF_STEP_NAME" == review:iterate:* ]]
            sleep 0.15
            if [[ "$fail" == true && "${'$'}WF_STEP_NAME" == review:iterate:1 ]]; then
              printf '{"type":"result","is_error":true,"result":"fixture item failed"}\n'
              exit 1
            fi
            printf '{"type":"result","is_error":false,"result":"reviewed %s"}\n' "${'$'}WF_STEP_NAME"
        """.trimIndent()
        val source = """
            name: cli-loop
            agents:
              analyst:
                type: claude_code_agent
                history_enabled: false
                session_id_strategy: per_execution
                skills_config:
                  enabled: false
                overrides:
                  external_agent_claude_auth_mode: subscription
                  external_agent_claude_oauth_token: credential:account@3
                  external_agent_claude_command: $fake
                  external_agent_claude_allowed_tools: Read,Write,Glob
            workflow:
              - step: review
                agent: analyst
                idempotent: true
                input: "Review {{var:item}}"
                iterate_over:
                  source: "a,b,c,d,e,f,g"
                  delimiter: ","
                  item_variable: item
                  parallel: true
                  max_parallel: 3
                  max_items: 7
                  results_variable: reviews
        """.trimIndent()
        val content = linkedMapOf("root.yaml" to ("definition" to source), "fake-claude" to ("script" to script))
        content.forEach { (path, entry) -> Files.writeString(dir.resolve(path), entry.second) }
        fake.toFile().setExecutable(true)
        val hash = sha256(source.toByteArray())
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val metadata = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(content.toSortedMap().map { (path, entry) -> buildJsonObject {
                put("path", path); put("role", entry.first); put("size", entry.second.toByteArray().size); put("sha256", sha256(entry.second.toByteArray()))
            } }))
            put("definitions", JsonArray(listOf(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) })))
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
        val running = AtomicInteger(); val peak = AtomicInteger(); val calls = CopyOnWriteArrayList<Map<String, String>>()
        val sessions = CopyOnWriteArrayList<String>()
        val executor = object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                calls += request.env
                assertTrue(Path.of(request.env.getValue("HOME")).startsWith(dir.resolve(".runner-private")))
                assertFalse(request.command.joinToString(" ").contains("fixture-cli-secret"))
                peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                val process = ProcessBuilder(request.command).directory(request.workingDir).apply {
                    environment().clear(); environment()["PATH"] = "/usr/bin:/bin"; environment().putAll(request.env)
                }.start()
                return try {
                    process.outputStream.use { it.write(requireNotNull(request.stdin).toByteArray()) }
                    withContext(Dispatchers.IO) { assertTrue(process.waitFor(5, TimeUnit.SECONDS)) }
                    val stdout = process.inputStream.bufferedReader().readText()
                    val stderr = process.errorStream.bufferedReader().readText()
                    stdout.lineSequence().filter(String::isNotBlank).forEach { request.stdoutLineCallback?.invoke(it) }
                    SubprocessExecutor.ExecResult(process.exitValue(), stdout, stderr, 150)
                } finally { process.destroyForcibly(); running.decrementAndGet() }
            }
        }
        IpcHarness(binding).use { h ->
            val gate = RunnerEnvironmentGate(binding, setOf("agent-cli"))
            val evidence = RunnerProtocolTest.environment(binding, Instant.now())
            gate.install(JsonObject(evidence + ("egress" to JsonArray(evidence.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("agent-cli"))) }))))
            val secrets = RunnerSecrets()
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), hash, binding.string("runtimeHash"))
            val normal = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, executor, RunnerCheckpointStore(dir, binding, secrets), secrets, validateDirectNetwork = {})
            val host = object : WorkflowHost by normal {
                override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> {
                    sessions += parameters.first { it.key == "session_id" }.value.jsonPrimitive.content
                    return normal.prepareAgentParameters(scope, parameters)
                }
            }
            val held = ConcurrentHashMap.newKeySet<String>(); val granted = AtomicInteger()
            val events = CopyOnWriteArrayList<JsonObject>(); val ended = AtomicInteger(-1)
            val pump = launch(Dispatchers.Default) {
                while (isActive) {
                    val frame = h.next(); val body = frame.obj("payload")
                    when (frame.string("type")) {
                        "credentialRequest" -> {
                            val id = "lease-${granted.incrementAndGet()}"; held += id
                            assertEquals("subscription.claude", body.string("purpose"))
                            h.ipc.accept(h.reply(frame, "credentialGrant", buildJsonObject {
                                put("requestId", body.string("requestId")); put("credentialRef", body.string("credentialRef")); put("credentialLeaseId", id)
                                put("version", 3); put("revision", 1); put("expiresAt", Instant.now().plusSeconds(60).toString())
                                put("allowedOperations", JsonArray(listOf(JsonPrimitive("subscription.claude"))))
                                put("material", buildJsonObject { put("token", "fixture-cli-secret-$id") })
                            }))
                        }
                        "event" -> {
                            val detail = body.obj("payload"); events += detail
                            if (detail["outcome"]?.jsonPrimitive?.content == "RELEASED") held -= detail.string("credentialLeaseId")
                            if (detail["stepName"]?.jsonPrimitive?.content == "review" && detail["kind"]?.jsonPrimitive?.content in setOf("step_completed", "step_cancelled")) ended.set(held.size)
                        }
                    }
                }
            }
            try {
                val result = withTimeout(20_000) { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(bundle.rootDefinition) }
                assertEquals(!fail, result.success, result.error)
                withTimeout(2000) { while (ended.get() < 0 || held.isNotEmpty()) delay(10) }
                assertEquals(0, ended.get(), "item leases must end before the loop's completion")
                assertTrue(peak.get() in 2..3)
                assertEquals(calls.size, sessions.distinct().size, "per_execution session setting must not mix loop items")
                assertEquals(calls.size, calls.map { it.getValue("CLAUDE_CONFIG_DIR") }.distinct().size)
                if (!fail) {
                    assertEquals(7, calls.size)
                    assertEquals(7, result.variables["reviews"].toString().split("\n---\n").size)
                }
                // Claude auth is environment-only; retained isolated settings must not persist the token.
                Files.walk(dir.resolve(".runner-private")).use { paths -> paths.filter(Files::isRegularFile).forEach {
                    assertFalse(Files.readString(it).contains("fixture-cli-secret"), "CLI token persisted at $it")
                } }
                assertFalse(events.toString().contains("fixture-cli-secret"))
            } finally { pump.cancelAndJoin(); normal.close(); h.supervisorInput.close() }
        }
    }
}
