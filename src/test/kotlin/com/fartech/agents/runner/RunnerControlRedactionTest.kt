package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * A registered credential value can be a substring of platform-issued identities (day-3: a login label
 * "local" inside ownerId `local-user`). Control data must reach S byte-exact; content stays redacted.
 * The fixture binding's executionId/runnerId/attemptId all contain "fixture", registered here as a secret.
 */
class RunnerControlRedactionTest {
    private val shortSecret = "fixture"

    @Test fun `result document keeps binding and status exact while workflow content stays redacted`() {
        val binding = RunnerProtocolTest.binding(); assertTrue(binding.toString().contains(shortSecret))
        val secrets = RunnerSecrets().apply { register(JsonPrimitive(shortSecret)) }
        val step = StepExecutionResult("code", "agent", false, 0, 1, 1, output = "output with fixture inside", error = "step failed near fixture")
        val result = WorkflowExecutionResult("terminal", false, 0, 1, 1, error = "workflow failed at fixture",
            stepResults = mapOf("code" to step), variables = mapOf("status" to "variable holding fixture"))
        val document = runnerResultDocument(binding, "FAILED", result, secrets)
        // What S's validate_result_file compares exactly.
        assertEquals(binding, document.obj("binding")); assertEquals("FAILED", document.string("status"))
        assertEquals("output with <redacted> inside", document.obj("outputs").string("code"))
        assertEquals("workflow failed at <redacted>", document.string("error"))
        assertEquals("variable holding <redacted>", document.obj("variables").string("status"), "a user variable named like a control field is still content")
    }

    @Test fun `events checkpoints and approvals keep control identities exact while content stays redacted`(@TempDir dir: Path) = runBlocking<Unit> {
        val source = "name: terminal\nagents: {}\nworkflow:\n  - step: code\n    code:\n      language: bash\n      script: 'true'\n"
        Files.writeString(dir.resolve("root.yaml"), source)
        val hash = sha256(source.toByteArray()); val b = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val meta = buildJsonObject {
            put("rootWorkflowId", "root"); put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", hash) }) })
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        val bundle = FrozenWorkflowBundle(dir, JsonObject(meta + ("bundleHash" to JsonPrimitive(sha256(canonical(meta).toByteArray())))), b)
        IpcHarness(b).use { h ->
            val gate = RunnerEnvironmentGate(b, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(b, Instant.now()))
            val identity = WorkflowHostBinding(b.string("ownerId"), b.string("actorUserId"), "billing", b.string("executionId"), b.string("runnerId"), b.string("runnerInstanceId"), b.long("resumeEpoch"), b.string("attemptId"), hash, b.string("runtimeHash"))
            val secrets = RunnerSecrets().apply { register(JsonPrimitive(shortSecret)) }
            val executor = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest) = SubprocessExecutor.ExecResult(0, "", "", 0)
            }
            val store = RunnerCheckpointStore(dir, b, secrets)
            val host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, RunnerHostSession.CAPABILITIES, executor, store, secrets)
            try {
                // Credential lineage S pops by exact [credentialRef, childExecutionPath, purpose, credentialLeaseId].
                val released = buildJsonObject {
                    put("outcome", "RELEASED"); put("credentialRef", "card-fixture"); put("credentialLeaseId", "lease-fixture")
                    put("childExecutionPath", JsonArray(listOf(JsonPrimitive("child-fixture")))); put("purpose", "subscription.codex")
                }
                host.emit("usage", released)
                val usage = h.next()
                assertEquals(b, usage.obj("binding")); assertEquals(b.string("attemptId"), usage.obj("payload").string("attemptId"))
                assertEquals(released, usage.obj("payload").obj("payload"))
                assertEquals(usage, runnerJson.parseToJsonElement(Files.readString(dir.resolve(RunnerEventJournal.eventPath(usage.obj("payload").long("eventSeq"))))))

                host.monitor(WorkflowMonitorUpdate(identity.executionId, "step_failed", "code", success = false, output = "out fixture", error = "failed at fixture"))
                val step = h.next().obj("payload").obj("payload")
                assertEquals(identity.executionId, step.string("executionId")); assertEquals("code", step.string("stepName"))
                assertEquals("out <redacted>", step.string("output")); assertEquals("failed at <redacted>", step.string("error"))

                // Checkpoint: the binding and execution identity are not credential material; a variable still is.
                val scope = WorkflowHostScope(identity.executionId, identity.executionId)
                store.save(WorkflowHostCheckpoint(scope, "terminal", WorkflowResumeState(variables = mapOf("note" to "ok")), true), 0)
                assertFailsWith<IllegalArgumentException> {
                    store.save(WorkflowHostCheckpoint(scope, "terminal", WorkflowResumeState(variables = mapOf("status" to "leak fixture")), true), 0)
                }

                // The approval journal stores the binding with the request; the dedicated request frame still goes out.
                val request = ApprovalRequest(identity.executionId + ":approve:1", "terminal", identity.executionId, "approve", "Review", emptyList(), System.currentTimeMillis(), "60", occurrenceId = "1")
                val waiting = async { runCatching { host.approvalHandler.requestApproval(request) } }
                val approval = withTimeout(5000) { h.next() }
                assertEquals("approval", approval.string("type")); assertEquals(b, approval.obj("binding"))
                waiting.cancel()
                gate.check()
            } finally { host.close() }
        }
    }

    @Test fun `content redaction is unchanged for free text and nested content`() {
        val secrets = RunnerSecrets().apply { register(JsonPrimitive(shortSecret)) }
        assertEquals(JsonPrimitive("x <redacted>"), secrets.redact(JsonPrimitive("x fixture")))
        val payload = buildJsonObject { put("executionId", "execution-fixture"); put("detail", "detail fixture"); put("nested", buildJsonObject { put("text", "fixture") }) }
        val content = secrets.redactContent(payload).jsonObject
        assertEquals("execution-fixture", content.string("executionId"))
        assertEquals("detail <redacted>", content.string("detail")); assertEquals("<redacted>", content.obj("nested").string("text"))
        assertTrue(secrets.containsSecret(payload)); assertFalse(secrets.containsSecret(buildJsonObject { put("binding", RunnerProtocolTest.binding()) }))
    }

    // A Codex login as delivered in grant material: descriptive metadata ("local", "chatgpt", dates, account id) and tokens.
    private val access = "synthetic-access-token-value"; private val refresh = "synthetic-refresh-token-value"
    private val opaque = "Zq8xP2mL9vR4tY7wK1nB5cJ3hG6fD0sA" // an unknown field that looks like a token
    private val login = """{"auth_mode":"local","OPENAI_API_KEY":null,"last_refresh":"2026-10-12T08:00:00Z","tokens":{"id_token":"eyJhbGciOi.synthetic-id-token","access_token":"$access","refresh_token":"$refresh","account_id":"chatgpt-account"},"note":"chatgpt","unknown":"$opaque"}"""
    private val metadataText = "mac_mode=local plan chatgpt chatgpt-account 2026-10-12T08:00:00Z /work/mac-runner-local-env/out.md"

    @Test fun `login metadata is not credential material while its tokens and short connector passwords are`(@TempDir dir: Path) {
        val secrets = RunnerSecrets()
        secrets.register(buildJsonObject { put("authJson", login) }) // subscription grant material
        secrets.register(buildJsonObject { put("username", "ops"); put("password", "pa55word") }) // connector secret fields
        assertEquals(metadataText, secrets.redact(metadataText), "login metadata stays readable")
        for (token in listOf(access, refresh, "eyJhbGciOi.synthetic-id-token", opaque, "pa55word", login)) {
            assertFalse(secrets.redact("value $token end").contains(token), token)
            assertTrue(secrets.containsSecret(JsonPrimitive("value $token")), token)
        }
        assertFalse(secrets.containsSecret(JsonPrimitive("ops")), "values of 1-3 characters are never registered")
        // A login file observed on disk (CODEX_HOME/auth.json) follows the same rules.
        val file = dir.resolve("auth.json"); Files.writeString(file, login)
        val observed = RunnerSecrets(); observed.observe(file).use {
            assertEquals(metadataText, observed.redact(metadataText))
            assertFalse(observed.redact("token $access").contains(access)); assertFalse(observed.redact(opaque).contains(opaque))
        }
        assertTrue(RunnerSecrets.looksLikeToken(opaque)); assertFalse(RunnerSecrets.looksLikeToken("2026-10-12T08:00:00Z")); assertFalse(RunnerSecrets.looksLikeToken("a".repeat(40)))
    }

    @Test fun `checkpoints and events carrying login metadata values proceed while login tokens are still refused and masked`(@TempDir dir: Path) {
        val binding = JsonObject(RunnerProtocolTest.binding() + ("ownerId" to JsonPrimitive("local-user")))
        val secrets = RunnerSecrets().apply {
            register(buildJsonObject { put("authJson", login) }); register(buildJsonObject { put("password", "pa55word") })
        }
        val store = RunnerCheckpointStore(dir, binding, secrets)
        val scope = WorkflowHostScope(binding.string("executionId"), binding.string("executionId"))
        val step = StepExecutionResult("local-build", "agent", true, 0, 1, 1, output = "wrote /work/mac-runner-local-env/out.md with chatgpt")
        store.save(WorkflowHostCheckpoint(scope, "app-factory", WorkflowResumeState(preservedStepResults = mapOf("local-build" to step),
            variables = mapOf("mac_mode" to "local", "auth" to "chatgpt")), true), 0)
        for (leak in listOf(access, "pa55word")) assertFailsWith<IllegalArgumentException>(leak) {
            store.save(WorkflowHostCheckpoint(scope, "app-factory", WorkflowResumeState(variables = mapOf("mac_mode" to "local", "leak" to "x $leak")), true), 0)
        }
        val frames = mutableListOf<JsonObject>()
        val journal = RunnerEventJournal(binding, secrets, { _, _ -> }, { frames += it }, {})
        val step1 = buildJsonObject { put("executionId", binding.string("executionId")); put("kind", "step_completed"); put("stepName", "local-build"); put("output", metadataText) }
        assertEquals(step1, journal.append("step", step1).obj("payload").obj("payload"))
        val leaked = journal.append("step", buildJsonObject { put("kind", "step_failed"); put("error", "failed with $access and pa55word") }).obj("payload").obj("payload")
        assertEquals("failed with <redacted> and <redacted>", leaked.string("error"))
        assertEquals(binding, frames.first().obj("binding"))
    }
}
