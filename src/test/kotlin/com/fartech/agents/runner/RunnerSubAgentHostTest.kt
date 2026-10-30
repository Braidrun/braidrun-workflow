package com.fartech.agents.runner

import ai.koog.agents.core.tools.ToolRegistry
import com.fartech.agents.tools.LLM
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

class RunnerSubAgentHostTest {
    private class Fixture(dir: Path, enabled: Boolean = true) : AutoCloseable {
        val root = dir.toRealPath()
        val source = "name: fixture\nagents:\n  worker:\n    tools: []\nworkflow:\n  - step: answer\n    agent: worker\n    input: fixture\n"
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(sha256(source.toByteArray()))))
        val h = IpcHarness(binding)
        val gate = RunnerEnvironmentGate(binding, setOf("engine-http"))
        val bundle: FrozenWorkflowBundle
        val host: RunnerWorkflowHost
        val rootId = binding.string("executionId")
        val scope = WorkflowHostScope(rootId, rootId, stepName = "answer")
        val runtime = WorkflowSubAgentRuntime({ ToolRegistry {} }, null) {}
        init {
            Files.writeString(root.resolve("root.yaml"), source)
            val metadata = buildJsonObject {
                put("rootWorkflowId", "root")
                put("files", buildJsonArray { add(buildJsonObject { put("path", "root.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", sha256(source.toByteArray())) }) })
                put("definitions", buildJsonArray { add(buildJsonObject { put("workflowId", "root"); put("path", "root.yaml"); put("definitionHash", sha256(source.toByteArray())) }) })
            }
            bundle = FrozenWorkflowBundle(root, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
            gate.install(RunnerProtocolTest.environment(binding, Instant.now()))
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", rootId,
                binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), binding.string("definitionHash"), binding.string("runtimeHash"))
            val secrets = RunnerSecrets()
            host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, setOf("tool.sub_agent.v1"),
                object : SubprocessExecutor { override suspend fun execute(request: SubprocessExecutor.ExecRequest) = error("No process in sub-agent fixture") },
                RunnerCheckpointStore(root, binding, secrets), secrets, validateDirectNetwork = {}, subAgentToolsEnabled = enabled)
        }
        fun bind(scope: WorkflowHostScope = this.scope, static: Boolean = true) = host.bindArtifactProducer(bundle.rootDefinition, scope.copy(stepName = null, occurrence = null), static)
        suspend fun prepare(scope: WorkflowHostScope = this.scope): List<ConfigurationParameter> = coroutineScope {
            val pending = async { host.prepareAgentParameters(scope.copy(parentExecutionId = null), listOf(
                ConfigurationParameter("llm_config", buildJsonObject { put("models", buildJsonArray { add(buildJsonObject { put("provider", "openai"); put("model", "gpt-4o") }) }) }),
                ConfigurationParameter("openai_api_key", JsonPrimitive("credential:model@1")))) }
            val ask = h.next(); assertEquals("credentialRequest", ask.string("type")); val body = ask.obj("payload")
            h.ipc.accept(h.reply(ask, "credentialGrant", buildJsonObject {
                put("requestId", body.string("requestId")); put("credentialRef", body.string("credentialRef")); put("credentialLeaseId", "lease-${body.string("requestId")}")
                put("version", 1); put("revision", 1); put("expiresAt", Instant.now().plusSeconds(60).toString())
                put("allowedOperations", buildJsonArray { add("llm.request") }); put("material", buildJsonObject { put("apiKey", "fixture-${scope.executionId}") })
            }))
            pending.await()
        }
        suspend fun <T> within(scope: WorkflowHostScope = this.scope, body: suspend () -> T): T =
            WorkflowHostContext.withHost(host) { WorkflowHostContext.withScope(scope, body) }
        fun tool(parameters: List<ConfigurationParameter>, scope: WorkflowHostScope = this.scope) = host.subAgentTools(scope, parameters, runtime) as RunnerSubAgentTools
        override fun close() { try { host.close() } finally { h.close() } }
    }

    @Test fun `host feature defaults closed and unbound or dynamic scopes cannot construct tools`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(Files.createDirectories(dir.resolve("disabled")), enabled = false).use { f ->
            assertFails { f.tool(emptyList()) }
        }
        Fixture(Files.createDirectories(dir.resolve("dynamic"))).use { f ->
            assertFails { f.tool(emptyList()) }
            f.bind(static = false); assertFails { f.tool(emptyList()) }
        }
    }

    @Test fun `root model key must be an active lease and cannot outlive its parent step`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind(); val p = f.prepare(); val tool = f.tool(p)
            f.within { assertEquals(listOf(LLM("openai", "gpt-4o")), tool.getAvailableLLMs()) }
            f.host.derivedScopeEnded(f.scope)
            assertFails { f.tool(p) }
            f.within { assertFails { tool.runSubAgent(com.fartech.agents.tools.SubAgentContext("task", "child", LLM("openai", "gpt-4o"), maxIterations = 8)) } }
        }
    }

    @Test fun `repreparing the same scope and provider key never revives a released child tool`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind(); val oldParameters = f.prepare(); val oldTool = f.tool(oldParameters)
            f.host.derivedScopeEnded(f.scope)
            val released = f.h.next()
            assertEquals("event", released.string("type"))
            assertEquals("usage", released.obj("payload").string("type"))
            assertEquals("RELEASED", released.obj("payload").obj("payload").string("outcome"))
            val newParameters = f.prepare()
            assertEquals(oldParameters, newParameters, "The fixture deliberately reuses identical provider key bytes")
            val newTool = f.tool(newParameters)
            f.within {
                assertEquals(listOf(LLM("openai", "gpt-4o")), newTool.getAvailableLLMs())
                val rejected = assertFailsWith<IllegalArgumentException> { oldTool.getAvailableLLMs() }
                assertTrue(rejected.message.orEmpty().contains("lease generation changed"))
                val failed = assertFailsWith<IllegalArgumentException> {
                    oldTool.runSubAgent(com.fartech.agents.tools.SubAgentContext("task", "child", LLM("openai", "gpt-4o"), maxIterations = 8))
                }
                assertTrue(failed.message.orEmpty().contains("lease generation changed"))
            }
        }
    }

    @Test fun `child scope uses independent verified lineage even without sandbox and rejects forged parent`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind()
            val child = WorkflowHostScope(f.rootId, "${f.rootId}__nested__call_1", f.rootId, "answer", "iterate:0")
            f.bind(child); val p = f.prepare(child)
            val tool = f.tool(p, child.copy(parentExecutionId = null))
            f.within(child) { assertEquals(listOf(LLM("openai", "gpt-4o")), tool.getAvailableLLMs()) }
            assertFails { f.tool(p, child.copy(parentExecutionId = "forged")) }
            f.within(child.copy(parentExecutionId = null)) { assertFails { tool.getAvailableLLMs() } }
            f.within(child.copy(occurrence = "iterate:1")) { assertFails { tool.getAvailableLLMs() } }
            f.within(f.scope) { assertFails { tool.getAvailableLLMs() } }
        }
    }
}
