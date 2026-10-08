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
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real no-account fake executable; platform credential IPC/egress/executor are controlled fixtures. */
class RunnerClaudeChildSkillsTest {
    @Test fun `Claude root and static child receive only selected frozen skill stdin and own scoped token`(@TempDir dir: Path) = runFixture(dir, false)
    @Test fun `Claude secret echo is redacted from persisted events and refuses credential checkpoint`(@TempDir dir: Path) = runFixture(dir, true)

    @Test fun `CLI structured write targets become scoped artifact declarations after actual persistence`(@TempDir dir: Path) = runFixture(dir, false, true)

    private fun runFixture(dir: Path, echoSecret: Boolean, structured: Boolean = false) = runBlocking<Unit> {
        val fake = dir.resolve("fake-claude")
        val persist = if (structured) """
            mkdir -p '${dir.resolve("run")}'
            if [[ "${'$'}WF_EXECUTION_ID" == *"__nested__call_1" ]]; then
              printf '{"fixture":"child"}' > '${dir.resolve("run/claude-child.json")}'
            else
              printf '{"fixture":"root"}' > '${dir.resolve("run/claude-root.json")}'
            fi
        """.trimIndent() else ""
        val executable = """
            #!/bin/bash
            set -eu
            prompt=${'$'}(cat)
            [[ "${'$'}HOME" == *".runner-private"* ]]
            [[ "${'$'}CLAUDE_CONFIG_DIR" == *".runner-private"* ]]
            [[ -z "${'$'}CODEX_HOME:-" ]]
            if [[ "${'$'}WF_EXECUTION_ID" == *"__nested__call_1" ]]; then
              [[ "${'$'}prompt" == *"CHILD_ONLY_FROZEN_SKILL"* ]]
              [[ "${'$'}prompt" != *"ROOT_ONLY_FROZEN_SKILL"* ]]
              [[ "${'$'}CLAUDE_CODE_OAUTH_TOKEN" == "fixture-claude-child-secret" ]]
            else
              [[ "${'$'}prompt" == *"ROOT_ONLY_FROZEN_SKILL"* ]]
              [[ "${'$'}prompt" != *"CHILD_ONLY_FROZEN_SKILL"* ]]
              [[ "${'$'}CLAUDE_CODE_OAUTH_TOKEN" == "fixture-claude-root-secret" ]]
            fi
            ${persist.replace("\n", "\n            ")}
            printf '{"type":"result","is_error":false,"result":"claude-proof %s %s"}\n' "${'$'}WF_EXECUTION_ID" "${if (echoSecret) "${'$'}CLAUDE_CODE_OAUTH_TOKEN" else "clean-result"}"
        """.trimIndent().replace("${'$'}CODEX_HOME:-", "${'$'}{CODEX_HOME:-}")
        fun definition(name: String, selected: String, account: String) = """
            name: $name
            agents:
              writer:
                type: claude_code_agent
                overrides:
                  external_agent_claude_auth_mode: subscription
                  external_agent_claude_oauth_token: credential:$account@3
                  external_agent_claude_command: $fake
                  skills_config:
                    enabledSkills: [$selected]
            workflow:
              - step: write
                agent: writer
                input: Follow the selected frozen skill.
        """.trimIndent() + if (structured) "\n    structured_output:\n      schema: ai_commentary_parts\n      write_to: ${dir.resolve("run/$name.json")}\n" else ""
        val root = definition("claude-root", "root-only", "root-account") + "\n  - step: nested\n    sub_workflow:\n      workflow_id: child\n"
        val content = linkedMapOf(
            "root.yaml" to ("definition" to root), "child.yaml" to ("definition" to definition("claude-child", "child-only", "child-account")),
            "fake-claude" to ("script" to executable),
            "skills/root-only/SKILL.md" to ("skill" to "ROOT_ONLY_FROZEN_SKILL"),
            "skills/child-only/SKILL.md" to ("skill" to "CHILD_ONLY_FROZEN_SKILL")
        )
        content.forEach { (path, entry) -> Files.createDirectories(dir.resolve(path).parent); Files.writeString(dir.resolve(path), entry.second) }
        fake.toFile().setExecutable(true)
        val hash = sha256(root.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val metadata = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(content.toSortedMap().map { (path, entry) -> buildJsonObject {
                put("path", path); put("role", entry.first); put("size", entry.second.toByteArray().size); put("sha256", sha256(entry.second.toByteArray()))
            } }))
            put("definitions", JsonArray(listOf("child", "root").map { id -> buildJsonObject {
                put("workflowId", id); put("path", "$id.yaml"); put("definitionHash", sha256(content.getValue("$id.yaml").second.toByteArray()))
            } }))
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
        IpcHarness(binding).use { h ->
            val gate = RunnerEnvironmentGate(binding, setOf("agent-cli")); val env = RunnerProtocolTest.environment(binding, Instant.now())
            gate.install(JsonObject(env + ("egress" to JsonArray(env.array("egress").map { JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("agent-cli"))) }))))
            val secrets = RunnerSecrets(); val invocations = mutableListOf<Map<String, String>>(); val scopes = mutableListOf<Pair<String, List<String>>>()
            val executor = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                    invocations += request.env
                    for (key in listOf("HOME", "CLAUDE_CONFIG_DIR")) assertTrue(Path.of(request.env.getValue(key)).startsWith(dir.resolve(".runner-private")))
                    assertFalse(request.command.joinToString(" ").contains("fixture-claude"))
                    assertFalse(request.command.joinToString(" ").contains("FROZEN_SKILL"))
                    val process = ProcessBuilder(request.command).directory(request.workingDir).apply {
                        environment().clear(); environment()["PATH"] = "/usr/bin:/bin"; environment().putAll(request.env)
                    }.start()
                    try {
                        process.outputStream.use { it.write(requireNotNull(request.stdin).toByteArray()) }
                        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                        val stdout = process.inputStream.bufferedReader().readText(); val stderr = process.errorStream.bufferedReader().readText()
                        assertEquals(0, process.exitValue(), stderr)
                        assertEquals(echoSecret, stdout.contains(request.env.getValue("CLAUDE_CODE_OAUTH_TOKEN")), "Fake CLI echo mode must be observed")
                        stdout.lineSequence().filter { it.isNotBlank() }.forEach { request.stdoutLineCallback?.invoke(it) }
                        return SubprocessExecutor.ExecResult(process.exitValue(), stdout, stderr, 1)
                    } finally { process.destroyForcibly() }
                }
            }
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", binding.string("executionId"),
                binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), hash, binding.string("runtimeHash"))
            val publications = mutableListOf<JsonObject>(); val starts = mutableMapOf<String, String>()
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, executor, RunnerCheckpointStore(dir, binding, secrets), secrets,
                validateDirectNetwork = { RunnerHttpClients.requireDirect(emptyMap(), java.util.Properties()) },
                artifactPolicy = if (!structured) null else RunnerArtifactPolicy { workflowId, scope, candidates ->
                    if (scope.stepName != "write") emptyList() else {
                        val logical = "run/claude-$workflowId.json"
                        assertEquals(listOf(WorkflowArtifactCandidate(dir.resolve(logical).toString(), WorkflowArtifactCandidate.Declaration.STRUCTURED_OUTPUT)), candidates)
                        listOf(RunnerDeclaredArtifact(logical, 1024))
                    }
                }, artifactFingerprinter = if (!structured) null else RunnerArtifactFingerprinter { intent ->
                    val bytes = Files.readAllBytes(dir.resolve(intent.declaration.logicalPath))
                    RunnerArtifactFingerprint(bytes.size.toLong(), sha256(bytes))
                })
            val task = async { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(bundle.rootDefinition) }
            val pump = launch {
                while (isActive) {
                    val frame = h.next(); val body = frame.obj("payload")
                    when (frame.string("type")) {
                        "credentialRequest" -> {
                            val ref = body.string("credentialRef"); val child = body.array("childExecutionPath").map { it.jsonPrimitive.content }
                            assertTrue(ref in setOf("root-account", "child-account")); assertEquals(if (ref == "root-account") emptyList() else listOf("nested", "call_1"), child)
                            scopes += ref to child
                            h.ipc.accept(h.reply(frame, "credentialGrant", buildJsonObject {
                                put("requestId", body.string("requestId")); put("credentialRef", ref); put("credentialLeaseId", "lease-$ref"); put("version", 3); put("revision", 1)
                                put("expiresAt", Instant.now().plusSeconds(60).toString()); put("allowedOperations", buildJsonArray { add("subscription.claude") })
                                put("material", buildJsonObject { put("token", if (ref == "root-account") "fixture-claude-root-secret" else "fixture-claude-child-secret") })
                            }))
                        }
                        "event", "checkpoint" -> {
                            assertFalse(frame.toString().contains(Regex("fixture-claude-(root|child)-secret")))
                            if (frame.string("type") == "event") {
                                val detail = body.obj("payload")
                                if (detail["kind"]?.jsonPrimitive?.content == "step_started" && detail["stepName"]?.jsonPrimitive?.content == "write") starts[detail.string("executionId")] = body.string("eventId")
                                if (body.string("type") == "artifact") {
                                    publications += detail
                                    val execution = if (detail.string("producerWorkflowId") == "root") identity.executionId else "${identity.executionId}__nested__call_1"
                                    assertEquals(starts.getValue(execution), detail.string("producerStartEventId"))
                                    assertEquals(sha256(Files.readAllBytes(dir.resolve(detail.string("logicalPath")))), detail.string("sha256"))
                                    h.ipc.accept(h.reply(frame, "ack", buildJsonObject { put("ackSeq", body.long("eventSeq")); put("revision", publications.size); put("serverTime", Instant.now().toString()) }))
                                }
                            }
                        }
                        else -> fail("Unexpected fixture frame ${frame.string("type")}")
                    }
                }
            }
            try {
                val result = withTimeout(10000) { task.await() }; assertEquals(!echoSecret, result.success, result.error)
                if (echoSecret) assertTrue(result.error.orEmpty().contains("Credential material cannot be checkpointed"))
                assertEquals(if (echoSecret) listOf("root-account") else listOf("root-account", "child-account"), scopes.map { it.first })
                assertEquals(if (echoSecret) listOf(identity.executionId) else listOf(identity.executionId, "${identity.executionId}__nested__call_1"), invocations.map { it.getValue("WF_EXECUTION_ID") })
                assertEquals(if (echoSecret) 1 else 2, invocations.map { it.getValue("HOME") }.toSet().size)
                assertEquals(if (echoSecret) 1 else 2, invocations.map { it.getValue("CLAUDE_CONFIG_DIR") }.toSet().size)
                assertEquals(if (structured) listOf("root", "child") else emptyList(), publications.map { it.string("producerWorkflowId") })
                Files.walk(dir.resolve(".runner-state")).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    assertFalse(Files.readString(path).contains(Regex("fixture-claude-(root|child)-secret")), "Secret persisted at $path")
                } }
            } finally { host.close(); pump.cancel(); h.supervisorInput.close() }
        }
    }
}
