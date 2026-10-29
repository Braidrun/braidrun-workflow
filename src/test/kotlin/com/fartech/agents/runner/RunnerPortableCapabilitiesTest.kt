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
    private fun bundle(root: Path, extras: Map<String, Pair<String, String>>,
                       definition: String = "name: assets\nagents: {}\nworkflow:\n  - step: check\n    code:\n      language: bash\n      script: 'true'\n"): FrozenWorkflowBundle {
        val all = mapOf("root.yaml" to ("definition" to definition)) + extras
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
    /** A real template is larger than one IPC frame; definitions are parsed in-process and never framed. */
    private fun largeDefinition(bytes: Int): String {
        val head = "name: large\nagents: {}\nworkflow:\n  - step: check\n    code:\n      language: bash\n      script: |\n        true\n"
        val line = "        # " + "\u7f16".repeat(20) + "x".repeat(28) + "\n" // multibyte: code points < bytes
        val body = StringBuilder(head)
        val lineBytes = line.toByteArray().size
        var size = head.toByteArray().size
        while (size + lineBytes <= bytes) { body.append(line); size += lineBytes }
        return body.toString()
    }
    @Test fun `definitions above one IPC frame and up to the planner limit freeze and parse`(@TempDir root: Path) {
        val text = largeDefinition(FrozenWorkflowBundle.MAX_DEFINITION_BYTES)
        assertTrue(text.toByteArray().size > RunnerIpc.MAX_FRAME_BYTES * 2)
        val frozen = bundle(root, emptyMap(), text)
        assertEquals("large", frozen.rootDefinition.name)
        assertTrue(frozen.rootDefinition.workflow.single().code!!.script!!.length > RunnerIpc.MAX_FRAME_BYTES)
    }
    @Test fun `definitions above the planner limit and scripts above one frame are refused`(@TempDir root: Path) {
        val oversized = largeDefinition(FrozenWorkflowBundle.MAX_DEFINITION_BYTES + 4096) + "#" + "x".repeat(4096) + "\n"
        assertTrue(oversized.toByteArray().size > FrozenWorkflowBundle.MAX_DEFINITION_BYTES)
        assertFailsWith<IllegalArgumentException> { bundle(root.resolve("a").also { Files.createDirectories(it) }, emptyMap(), oversized) }
        val script = ".runtime/project/scripts/large.sh" to ("script" to "#" + "x".repeat(RunnerIpc.MAX_FRAME_BYTES))
        assertFailsWith<IllegalArgumentException> { bundle(root.resolve("b").also { Files.createDirectories(it) }, mapOf(script)) }
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
    @Test fun `per-call Runner host never mints static token and rechecks original scope before launch`(@TempDir root: Path) = runBlocking<Unit> {
        val frozen = bundle(root, assets)
        IpcHarness(frozen.taskBinding).use { h ->
            val gate = RunnerEnvironmentGate(h.binding, setOf("engine-http")); gate.install(RunnerProtocolTest.environment(h.binding, Instant.now()))
            val identity = com.fartech.agents.workflow.WorkflowHostBinding(h.binding.string("ownerId"), h.binding.string("actorUserId"), "billing",
                h.binding.string("executionId"), h.binding.string("runnerId"), h.binding.string("runnerInstanceId"), h.binding.long("resumeEpoch"),
                h.binding.string("attemptId"), h.binding.string("definitionHash"), h.binding.string("runtimeHash"))
            val executor = object : com.fartech.agents.tools.exec.SubprocessExecutor {
                override suspend fun execute(request: com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest): com.fartech.agents.tools.exec.SubprocessExecutor.ExecResult = error("Only preparation under test")
            }
            val secrets = RunnerSecrets()
            val host = RunnerWorkflowHost(h.ipc, identity, gate, frozen, RunnerHostSession.CAPABILITIES, executor, RunnerCheckpointStore(root, h.binding, secrets), secrets,
                callbackEnabled = true, codeControlMode = com.fartech.agents.workflow.WorkflowCodeControlMode.SUPERVISOR_PER_CALL)
            try {
                val scope = WorkflowHostScope(identity.executionId, identity.executionId, stepName = "code")
                assertNull(host.callbackToken)
                assertFailsWith<IllegalArgumentException> { host.callbackTokenFor(scope, 30) }
                assertEquals(emptyMap(), host.prepareCodeStepEnvironment(scope, 7000))
                assertEquals(emptyMap(), host.prepareCodeStepEnvironment(scope.copy(executionId = "${identity.executionId}__nested__call_1"), 7200))
                assertFailsWith<IllegalArgumentException> { host.prepareCodeStepEnvironment(scope.copy(executionId = "other"), 7200) }
                gate.invalidate("TEST_CANCEL")
                assertFailsWith<RunnerAuthorityLost> { host.prepareCodeStepEnvironment(scope, 7000) }
            } finally { host.close() }
        }
    }
    @Test fun `Runner host forces strict sandbox and run directories and serves only confined audited file tools`(@TempDir root: Path) = runBlocking<Unit> {
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
                fun p(key: String, value: kotlinx.serialization.json.JsonElement) = com.fartech.ftapp2.commonsKt.ConfigurationParameter(key, value)
                fun List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>.value(key: String) = single { it.key == key }.value.jsonPrimitive.content
                val scope = WorkflowHostScope(identity.executionId, identity.executionId, stepName = "write")
                val isolation = frozen.rootDefinition.directoryIsolation
                val prepared = host.prepareAgentParameters(scope, listOf(p("type", JsonPrimitive("universal_agent")),
                    p("tool_set", buildJsonArray { add("exit"); add("file_system") }), p("sandbox_strict", JsonPrimitive("false")),
                    p("working_dir", JsonPrimitive("/")), p("workspace_dir", JsonPrimitive("/")), p("output_dir", JsonPrimitive("/tmp"))))
                assertEquals("true", prepared.value("sandbox_strict"))
                val working = java.io.File(isolation.getWorkingDir(identity.executionId, "write", userId = identity.ownerId)).absolutePath
                assertEquals(working, prepared.value("working_dir"))
                assertEquals(java.io.File(isolation.getOutputDir(identity.executionId, "write", userId = identity.ownerId)).absolutePath, prepared.value("output_dir"))
                assertTrue(prepared.none { it.key == "workspace_dir" })
                assertEquals(1, prepared.count { it.key == "working_dir" })
                // A YAML that sets sandbox_strict false on any agent is still strict on the Runner.
                assertEquals("true", host.prepareAgentParameters(scope, listOf(p("sandbox_strict", JsonPrimitive("false")))).value("sandbox_strict"))

                Files.createDirectories(Path.of(working))
                val tools = host.fileSystemTools(scope, prepared).single() as RunnerFileTools
                assertTrue(tools.writeFile(path = "out.json", content = "{\"ok\":true}").startsWith("Successfully"))
                assertEquals("{\"ok\":true}", Files.readString(Path.of(working, "out.json")))
                Files.createDirectories(root.resolve(".runner-state")); Files.writeString(root.resolve(".runner-state/checkpoint.json"), "{}")
                for (escape in listOf(root.resolve(".runner-state/checkpoint.json"), root.resolve("root.yaml"), root.resolve("skills/frontend-design/SKILL.md"),
                        root.resolve(".runtime/project/contract.md"), root.resolve("inputs/brief.txt"), Path.of(System.getProperty("java.io.tmpdir"), "x.txt"))) {
                    assertTrue(tools.readFile(escape.toString()).startsWith("Error:"), escape.toString())
                    assertTrue(tools.writeFile(path = escape.toString(), content = "x").startsWith("Error:"), escape.toString())
                }
                // Each operation reaches the durable event journal as a small audit event.
                val audits = mutableListOf<JsonObject>()
                withTimeout(5000) {
                    while (audits.size < 13) {
                        val frame = h.next()
                        if (frame.string("type") != "event") continue
                        val event = frame.obj("payload").obj("payload")["event"]?.jsonObject ?: continue
                        if (event.string("type") == "file_operation") audits += runnerJson.parseToJsonElement(event.string("detail")).jsonObject
                    }
                }
                val write = audits.first()
                assertEquals("write", write.string("operation")); assertEquals("ok", write.string("outcome")); assertEquals(11, write.long("bytes"))
                assertEquals(sha256("{\"ok\":true}".toByteArray()), write.string("sha256"))
                assertTrue(audits.drop(1).all { it.string("outcome") == "refused" })

                // Claude steps: allowlist narrowed to file tools, permission bypass dropped, hard registry forced.
                val claude = host.prepareAgentParameters(scope, listOf(p("type", JsonPrimitive("claude_code_agent")),
                    p("external_agent_claude_allowed_tools", JsonPrimitive("Read,Write,WebFetch,Bash")),
                    p("external_agent_claude_extra_args", JsonPrimitive("--dangerously-skip-permissions"))))
                assertEquals("Read,Write", claude.value("external_agent_claude_allowed_tools"))
                assertEquals("", claude.value("external_agent_claude_extra_args"))
                assertEquals("Read,Write", host.claudeBuiltinTools(scope, claude))
                assertEquals("Read,Write,Edit,Glob,Grep", host.claudeBuiltinTools(scope, host.prepareAgentParameters(scope, listOf(p("type", JsonPrimitive("claude_code_agent"))))))
                assertFailsWith<IllegalArgumentException> { host.prepareAgentParameters(scope, listOf(p("type", JsonPrimitive("claude_code_agent")), p("external_agent_claude_extra_args", JsonPrimitive("--tools,Bash")))) }
                assertFailsWith<IllegalArgumentException> { host.fileSystemTools(scope.copy(rootExecutionId = "other"), prepared) }
            } finally { host.close() }
        }
    }
    @Test fun `confined file tools are offered in the ready inventory`() {
        assertTrue(RunnerFileConfinement.available())
        assertTrue("tool.file_system.v1" in RunnerHostSession.CAPABILITIES)
        assertFalse("tool.shell.v1" in RunnerHostSession.CAPABILITIES)
        for (tool in listOf("web", "knowledge_memory", "image_processing", "csv")) assertTrue("tool.$tool.v1" in RunnerHostSession.CAPABILITIES)
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
