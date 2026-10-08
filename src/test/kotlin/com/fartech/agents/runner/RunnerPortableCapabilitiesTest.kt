package com.fartech.agents.runner

import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RunnerPortableCapabilitiesTest {
    private fun bundle(root: Path, extras: Map<String, Pair<String, String>>): FrozenWorkflowBundle {
        val all = mapOf("root.yaml" to ("definition" to "name: assets\nagents: {}\nworkflow:\n  - step: check\n    code:\n      language: bash\n      script: 'true'\n")) + extras
        all.forEach { (name, value) -> Files.createDirectories(root.resolve(name).parent); Files.writeString(root.resolve(name), value.second) }
        val hash = sha256(all.getValue("root.yaml").second.toByteArray())
        val metadata = buildJsonObject {
            put("rootWorkflowId", "root")
            put("files", JsonArray(all.toSortedMap().map { (name, value) -> buildJsonObject {
                put("path", name); put("role", value.first); put("size", value.second.toByteArray().size); put("sha256", sha256(value.second.toByteArray()))
            } }))
            put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", hash) }) })
        }
        return FrozenWorkflowBundle(root, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))),
            JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash))))
    }
    private val assets = mapOf(
        ".runtime/project/scripts/fixture.sh" to ("script" to "printf portable-proof"),
        ".runtime/project/contract.md" to ("runtime" to "immutable-contract"),
        "inputs/brief.txt" to ("input" to "authorized-brief"),
        "skills/frontend-design/SKILL.md" to ("skill" to "Use frozen colors.")
    )
    @Test fun `portable script contract input and selected text skill execute locally`(@TempDir root: Path) {
        val frozen = bundle(root, assets)
        frozen.verifyPortableAssets()
        val process = ProcessBuilder("/bin/bash", "--noprofile", "--norc", root.resolve(".runtime/project/scripts/fixture.sh").toString())
            .directory(root.toFile()).apply { environment().clear(); environment()["PATH"] = "/usr/bin:/bin" }.start()
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
        assertEquals("portable-proof", process.inputStream.bufferedReader().readText())
        assertTrue(frozen.nativeSkillInstructions(SkillsConfiguration(enabledSkills = listOf("frontend-design"))).contains("Use frozen colors."))
        assertEquals("", frozen.nativeSkillInstructions(SkillsConfiguration(enabledSkills = listOf("other"))))
        assertEquals("", frozen.nativeSkillInstructions(SkillsConfiguration(disabledSkills = listOf("frontend-design"))))
        Files.createDirectories(root.resolve("run/app")); Files.writeString(root.resolve("run/app/result.txt"), "mutable result")
        frozen.verifyPortableAssets()
    }
    @Test fun `portable asset mutation missing input and undeclared runtime import are refused`(@TempDir root: Path) {
        val frozen = bundle(root, assets)
        Files.writeString(root.resolve(".runtime/project/contract.md"), "different-contract")
        assertFailsWith<IllegalArgumentException> { frozen.verifyPortableAssets() }
        Files.writeString(root.resolve(".runtime/project/contract.md"), "immutable-contract")
        Files.writeString(root.resolve(".runtime/project/injected.py"), "pass")
        assertFailsWith<IllegalArgumentException> { frozen.verifyPortableAssets() }
        Files.delete(root.resolve(".runtime/project/injected.py")); Files.delete(root.resolve("inputs/brief.txt"))
        assertFailsWith<IllegalArgumentException> { frozen.verifyPortableAssets() }
    }
    @Test fun `native skill rechecks changed bytes symlink and bounded input`(@TempDir root: Path) {
        val frozen = bundle(root, assets)
        val config = SkillsConfiguration(maxSkillContentSize = 1)
        assertFailsWith<IllegalArgumentException> { frozen.nativeSkillInstructions(config) }
        Files.delete(root.resolve("skills/frontend-design/SKILL.md"))
        Files.createSymbolicLink(root.resolve("skills/frontend-design/SKILL.md"), root.resolve("inputs/brief.txt"))
        assertFailsWith<IllegalArgumentException> { frozen.nativeSkillInstructions(SkillsConfiguration()) }
    }
    @Test fun `case aliases and private namespace cannot be frozen`(@TempDir root: Path) {
        assertFailsWith<IllegalArgumentException> { bundle(root, mapOf("inputs/A" to ("input" to "a"), "inputs/a" to ("input" to "a"))) }
        assertFailsWith<IllegalArgumentException> { bundle(root, mapOf(".runner-private/auth.json" to ("input" to "fake"))) }
    }
    @Test fun `official callback root and child grants preserve scope and operations`() = runBlocking<Unit> {
        IpcHarness().use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val secrets = RunnerSecrets(); val tokens = RunnerCallbackTokens(h.ipc, gate, secrets)
            val root = h.binding.string("executionId")
            for (execution in listOf(root, "${root}__child__call_1")) {
                val result = async(Dispatchers.Default) { tokens.mint(WorkflowHostScope(root, execution), 30) }
                val frame = withTimeout(2000) { h.next() }; val payload = frame.obj("payload")
                assertEquals(childPath(root, execution), payload.array("childExecutionPath"))
                assertEquals(execution == root, payload.array("operations").any { it.jsonPrimitive.content == "artifact.read" })
                h.ipc.accept(h.reply(frame, "callbackTokenGrant", buildJsonObject {
                    put("requestId", payload.string("requestId")); put("token", "fixture-control-secret")
                    put("expiresAt", Instant.now().plusSeconds(90).toString()); put("operations", payload.array("operations"))
                }))
                assertEquals("fixture-control-secret", result.await())
                assertFalse(secrets.redact("fixture-control-secret").contains("fixture-control-secret"))
            }
            assertFailsWith<IllegalArgumentException> { tokens.mint(WorkflowHostScope(root, root), 7200) }
            assertFailsWith<IllegalArgumentException> { tokens.mint(WorkflowHostScope("other", "other"), 30) }
        }
    }
    @Test fun `tampered asset latches host interruption and inventory alone cannot enable callbacks`(@TempDir root: Path) = runBlocking<Unit> {
        val frozen = bundle(root, assets)
        IpcHarness(frozen.taskBinding).use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val identity = com.fartech.agents.workflow.WorkflowHostBinding(h.binding.string("ownerId"), h.binding.string("actorUserId"), "billing",
                h.binding.string("executionId"), h.binding.string("runnerId"), h.binding.string("runnerInstanceId"), h.binding.long("resumeEpoch"),
                h.binding.string("attemptId"), h.binding.string("definitionHash"), h.binding.string("runtimeHash"))
            val executor = object : com.fartech.agents.tools.exec.SubprocessExecutor {
                override suspend fun execute(request: com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest): com.fartech.agents.tools.exec.SubprocessExecutor.ExecResult = error("Must not launch")
            }
            val secrets = RunnerSecrets()
            val host = RunnerWorkflowHost(h.ipc, identity, gate, frozen, RunnerHostSession.CAPABILITIES, executor, RunnerCheckpointStore(root, h.binding, secrets), secrets)
            try {
                val scope = WorkflowHostScope(identity.executionId, identity.executionId)
                assertNull(host.callbackToken)
                assertFailsWith<IllegalArgumentException> { host.callbackTokenFor(scope, 30) }
                host.beforeOperation(scope, "step")
                Files.writeString(root.resolve("inputs/brief.txt"), "tampered")
                assertFailsWith<RunnerAuthorityLost> { host.beforeOperation(scope, "step") }
                Files.writeString(root.resolve("inputs/brief.txt"), "authorized-brief")
                assertFailsWith<RunnerAuthorityLost> { host.beforeOperation(scope, "step") }
            } finally { host.close() }
        }
    }
    @Test fun `CN and unknown country evidence cannot authorize HTTP or CLI`() {
        val binding = RunnerProtocolTest.binding()
        for (country in listOf("CN", "ZZ", "XX")) {
            val gate = RunnerEnvironmentGate(binding, setOf("engine-http"))
            val environment = RunnerProtocolTest.environment(binding, Instant.now())
            val invalid = JsonObject(environment + ("egress" to JsonArray(environment.array("egress").map {
                JsonObject(it.jsonObject + ("countryCode" to JsonPrimitive(country)))
            })))
            assertFailsWith<RunnerAuthorityLost> { gate.install(invalid) }
        }
    }
    @Test fun `ready inventory does not claim generic subprocess HTTP adaptation`() {
        assertTrue("bundle.portableAssets.v1" in RunnerHostSession.CAPABILITIES)
        assertTrue("control.callback.v1" in RunnerHostSession.CAPABILITIES)
        assertFalse("network.http.v1" in RunnerHostSession.CAPABILITIES)
    }
}
