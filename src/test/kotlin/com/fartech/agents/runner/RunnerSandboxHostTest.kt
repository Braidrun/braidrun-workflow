package com.fartech.agents.runner

import com.fartech.agents.tools.SandboxShellTools
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/** Transport fixtures assert E authority only. They do not simulate a successful Linux isolation probe. */
internal object SandboxTestFrames {
    val capabilities = setOf(RunnerSandboxProtocol.CAPABILITY, "tool.file_system.v1", "tool.shell.v1", "tool.code_execution.v1", "tool.git.v1")
    fun start(binding: JsonObject, required: Set<String> = capabilities): JsonObject {
        val original = runnerJson.parseToJsonElement(RunnerProtocolTest.resource("fixtures/valid/start.json")).jsonObject
        val payload = original.obj("payload")
        return JsonObject(original + mapOf("binding" to binding, "payload" to JsonObject(payload + mapOf(
            "environment" to JsonObject(payload.obj("environment") + ("binding" to binding)),
            "authorization" to JsonObject(payload.obj("authorization") + ("requiredCapabilities" to JsonArray(required.map(::JsonPrimitive))))))))
    }
    fun negotiate(h: IpcHarness, required: Set<String> = capabilities) {
        val start = start(h.binding, required); h.ipc.accept(start); h.ipc.enableAgentSandbox(start, capabilities)
    }
    suspend fun next(h: IpcHarness): JsonObject = withTimeout(3000) {
        runInterruptible(Dispatchers.IO) {
            runnerJson.parseToJsonElement(requireNotNull(RunnerIpc.readLine(h.supervisorInput)).toString(Charsets.UTF_8)).jsonObject
        }.also { RunnerSandboxProtocol.validate(it, true) }
    }
    fun result(h: IpcHarness, request: JsonObject, requestId: String = request.obj("payload").string("requestId")): JsonObject =
        h.reply(request, "sandboxProcessResult", buildJsonObject {
            put("requestId", requestId); put("exitCode", 0); put("stdout", "fixture"); put("stderr", "")
            put("outputs", JsonArray(emptyList())); put("cleanup", "STOP_CONFIRMED")
        })
}

class RunnerSandboxHostTest {
    private fun source(tools: String = "tools: [shell]", calls: String = "") = """
        |name: fixture
        |agents:
        |  worker:
        |    $tools
        |workflow:
        |  - step: answer
        |    agent: worker
        |    input: hello
        |$calls
    """.trimMargin()

    private class Fixture(dir: Path, definitions: Map<String, String>, negotiate: Boolean = true,
                          required: Set<String> = SandboxTestFrames.capabilities) : AutoCloseable {
        val root = dir.toRealPath()
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(sha256(definitions.getValue("root").toByteArray()))))
        val h = IpcHarness(binding)
        val bundle: FrozenWorkflowBundle
        val gate = RunnerEnvironmentGate(binding, setOf("engine-http"))
        val host: RunnerWorkflowHost
        val rootId = binding.string("executionId")
        val scope get() = WorkflowHostScope(rootId, rootId, stepName = "answer")
        init {
            definitions.forEach { (name, source) -> Files.writeString(root.resolve("$name.yaml"), source) }
            val metadata = buildJsonObject {
                put("rootWorkflowId", "root")
                put("files", JsonArray(definitions.toSortedMap().map { (name, source) -> buildJsonObject {
                    put("path", "$name.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", sha256(source.toByteArray()))
                } }))
                put("definitions", JsonArray(definitions.toSortedMap().map { (name, source) -> buildJsonObject {
                    put("workflowId", name); put("path", "$name.yaml"); put("definitionHash", sha256(source.toByteArray()))
                } }))
            }
            bundle = FrozenWorkflowBundle(root, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
            gate.install(RunnerProtocolTest.environment(binding, Instant.now()))
            if (negotiate) SandboxTestFrames.negotiate(h, required)
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", rootId,
                binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), binding.string("definitionHash"), binding.string("runtimeHash"))
            val secrets = RunnerSecrets()
            val forbidden = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest) = error("Sandbox must not use the native executor")
            }
            host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, SandboxTestFrames.capabilities, forbidden,
                RunnerCheckpointStore(root, binding, secrets), secrets, validateDirectNetwork = {})
        }
        fun bindRoot(static: Boolean = true) = host.bindArtifactProducer(bundle.rootDefinition, scope.copy(stepName = null), static)
        fun shell(scope: WorkflowHostScope = this.scope, parameters: List<ConfigurationParameter> = emptyList()): SandboxShellTools =
            host.agentToolSets(scope, setOf("shell"), parameters).getValue("shell").single() as SandboxShellTools
        override fun close() = h.close()
    }

    @Test fun `root host replaces explicitly frozen shell through typed IPC`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir, mapOf("root" to source())).use { f ->
            assertFails { f.shell() } // sandbox alone must retain the producer map even without artifact/local policies
            f.bindRoot()
            val result = async { f.shell().executeShellCmd("printf fixture") }
            val request = SandboxTestFrames.next(f.h)
            assertEquals(f.rootId, request.obj("payload").string("childExecutionPath"))
            assertEquals("answer", request.obj("payload").string("stepName"))
            assertEquals("NONE", request.obj("payload").string("network"))
            assertFalse(request.obj("payload").getValue("interactive").jsonPrimitive.boolean)
            f.h.ipc.accept(SandboxTestFrames.result(f.h, request))
            assertContains(result.await(), "fixture")
        }
    }

    @Test fun `host refuses unnegotiated dynamically bound and unselected groups`(@TempDir dir: Path) {
        for (mode in 0..2) {
            val path = Files.createDirectories(dir.resolve("case$mode"))
            val required = if (mode == 2) SandboxTestFrames.capabilities - "tool.shell.v1" else SandboxTestFrames.capabilities
            Fixture(path, mapOf("root" to source()), negotiate = mode != 0, required = required).use { f ->
                f.bindRoot(static = mode != 1)
                assertFails { f.shell() }
            }
        }
    }

    @Test fun `runtime and preset parameters cannot introduce sandbox groups`(@TempDir dir: Path) {
        Fixture(dir, mapOf("root" to source("preset: universal"))).use { f ->
            f.bindRoot()
            val runtime = listOf(ConfigurationParameter("tool_set", JsonArray(listOf(JsonPrimitive("shell")))))
            assertFails { f.shell(parameters = runtime) }
            assertFails { f.host.agentToolSets(f.scope, setOf("code_execution", "git"), runtime) }
        }
    }

    @Test fun `explicit frozen override is accepted but undeclared derived and loop scopes are refused`(@TempDir dir: Path) {
        Fixture(dir, mapOf("root" to source("overrides: {tool_set: [shell]}"))).use { f ->
            f.bindRoot(); assertIs<SandboxShellTools>(f.shell())
            for (scope in listOf(f.scope.copy(stepName = "unknown"), f.scope.copy(stepName = "answer:worker"),
                f.scope.copy(occurrence = "iterate:0"), f.scope.copy(parentExecutionId = f.rootId), f.scope.copy(rootExecutionId = "foreign"))) {
                assertFails { f.shell(scope) }
            }
        }
    }

    @Test fun `real child lineage requires admitted parent and exact frozen call target`(@TempDir dir: Path): Unit = runBlocking {
        val calls = "  - step: call_1\n    sub_workflow: {workflow_id: child}"
        Fixture(dir, mapOf("root" to source(calls = calls), "child" to source(), "wrong" to source())).use { f ->
            val child = f.bundle.resolve(WorkflowReference(workflowId = "child")).definition
            val scope = WorkflowHostScope(f.rootId, "${f.rootId}__call_1__call_2", f.rootId, "answer")
            assertFails { f.host.bindArtifactProducer(child, scope, true) }
            f.bindRoot()
            assertFails { f.host.bindArtifactProducer(child, scope.copy(parentExecutionId = null), true) }
            assertFails { f.host.bindArtifactProducer(f.bundle.resolve(WorkflowReference(workflowId = "wrong")).definition, scope, true) }
            assertFails { f.host.bindArtifactProducer(child, scope.copy(executionId = "${f.rootId}__missing__call_1"), true) }
            f.host.bindArtifactProducer(child, scope, true)
            assertFails { f.shell(scope.copy(parentExecutionId = "foreign")) }
            // WorkflowExecutor.hostAgentToolSets supplies executionId/stepName but no parent.
            val result = async { f.shell(scope.copy(parentExecutionId = null)).executeShellCmd("true") }
            val request = SandboxTestFrames.next(f.h)
            assertEquals(scope.executionId, request.obj("payload").string("childExecutionPath"))
            f.h.ipc.accept(SandboxTestFrames.result(f.h, request)); result.await()
        }
    }

    @Test fun `retained tool cannot bypass gate fence or frozen file integrity`(@TempDir dir: Path): Unit = runBlocking {
        for (mode in 0..2) {
            Fixture(Files.createDirectories(dir.resolve("case$mode")), mapOf("root" to source())).use { f ->
                f.bindRoot(); val tool = f.shell()
                when (mode) {
                    0 -> f.h.ipc.businessFence.close()
                    1 -> f.gate.invalidate("fixture expired")
                    else -> Files.writeString(f.root.resolve("root.yaml"), "changed")
                }
                assertFails { tool.executeShellCmd("true") }
                assertEquals(0, f.h.supervisorInput.available())
            }
        }
    }

    @Test fun `canonical execution syntax rejects examples traversal and excessive depth`() {
        assertEquals(emptyList(), RunnerSandboxPolicy.executionParts("root", "root"))
        assertEquals(listOf("call_1", "call_2", "nested", "call_3"), RunnerSandboxPolicy.executionParts("root", "root__call_1__call_2__nested__call_3"))
        for (id in listOf("root/step/0", "foreign__step__call_1", "root__step", "root__step__call_0", "root__step__call_01",
            "root__..__call_1", "root__step__call_1__", "root" + "__x__call_1".repeat(17), "root__" + "x".repeat(256) + "__call_1")) {
            assertFails { RunnerSandboxPolicy.executionParts("root", id) }
        }
    }
}
