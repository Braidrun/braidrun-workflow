package com.fartech.agents.runner

import com.fartech.agents.runner.multimedia.RunnerMultimediaGenerationTools
import com.fartech.agents.tools.ImageModel
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.HttpSingleDispatch
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real host/IPC/credential/file boundaries; the provider transport is always a no-network mock. */
class RunnerMultimediaHostTest {
    companion object {
        private val imageModel = ImageModel.NANO_BANANA_2.model
        private val required = setOf(RunnerMultimediaHostPolicy.CAPABILITY, "tool.file_system.v1", "model.http.v1")
        private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jVv0AAAAASUVORK5CYII=")
        private fun source(extra: String = "", tools: String = "[multimedia]", image: String = imageModel) = """
            |name: fixture
            |agents:
            |  worker:
            |    tools: $tools
            |    overrides:
            |      multimedia_default_image_model: '$image'
            |      multimedia_api_key: 'credential:media@1'
            |workflow:
            |  - step: answer
            |    agent: worker
            |    input: fixture
            |$extra
        """.trimMargin()
    }

    private class Fixture(dir: Path, definitions: Map<String, String> = mapOf("root" to source()), negotiate: Boolean = true) : AutoCloseable {
        val root = dir.toRealPath()
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(sha256(definitions.getValue("root").toByteArray()))))
        val h = IpcHarness(binding)
        val rootId = binding.string("executionId")
        val scope = WorkflowHostScope(rootId, rootId, stepName = "answer")
        val gate = RunnerEnvironmentGate(binding, setOf("jvm-http"))
        val bundle: FrozenWorkflowBundle
        val host: RunnerWorkflowHost
        val sent = AtomicInteger()
        var beforeReply: () -> Unit = {}
        var status = 200
        val key = "scoped-media-test-key"
        init {
            definitions.forEach { (id, source) -> Files.writeString(root.resolve("$id.yaml"), source) }
            val metadata = buildJsonObject {
                put("rootWorkflowId", "root")
                put("files", JsonArray(definitions.toSortedMap().map { (id, source) -> buildJsonObject {
                    put("path", "$id.yaml"); put("role", "definition"); put("size", source.toByteArray().size); put("sha256", sha256(source.toByteArray()))
                } }))
                put("definitions", JsonArray(definitions.toSortedMap().map { (id, source) -> buildJsonObject {
                    put("workflowId", id); put("path", "$id.yaml"); put("definitionHash", sha256(source.toByteArray()))
                } }))
            }
            bundle = FrozenWorkflowBundle(root, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
            val environment = RunnerProtocolTest.environment(binding, Instant.now())
            gate.install(JsonObject(environment + ("egress" to JsonArray(environment.array("egress").map {
                JsonObject(it.jsonObject + ("pathId" to JsonPrimitive("jvm-http")))
            }))))
            if (negotiate) {
                val start = SandboxTestFrames.start(binding, required)
                h.ipc.accept(start); h.ipc.enableMultimedia(start, required, trustedEnabled = true)
            }
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), "billing", rootId,
                binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), binding.string("definitionHash"), binding.string("runtimeHash"))
            val secrets = RunnerSecrets()
            host = RunnerWorkflowHost(h.ipc, identity, gate, bundle, required,
                object : SubprocessExecutor { override suspend fun execute(request: SubprocessExecutor.ExecRequest) = error("No multimedia subprocess") },
                RunnerCheckpointStore(root, binding, secrets), secrets, validateDirectNetwork = {}, multimediaHttpFactory = { checkLease ->
                    HttpAccess.withOkHttpConfig { builder ->
                        HttpSingleDispatch.install(builder)
                        RunnerHttpClients.configureDirect(builder) { checkLease() }
                        builder.addInterceptor { chain ->
                            val request = chain.request()
                            assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
                            assertEquals("POST", request.method)
                            assertEquals("Bearer $key", request.header("Authorization"))
                            assertTrue(requireNotNull(request.body).isOneShot())
                            sent.incrementAndGet(); beforeReply()
                            val body = """{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"}}]}}]}"""
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                                .header("Retry-After", "0").body(body.toResponseBody("application/json".toMediaType())).build()
                        }
                    }
                })
        }
        fun bind(scope: WorkflowHostScope = this.scope, definition: WorkflowDefinition = bundle.rootDefinition, static: Boolean = true) =
            host.bindArtifactProducer(definition, scope.copy(stepName = null, occurrence = null), static)
        fun parameters() = listOf(
            ConfigurationParameter("tool_set", JsonArray(listOf(JsonPrimitive("multimedia")))),
            ConfigurationParameter(RunnerMultimediaHostPolicy.IMAGE, JsonPrimitive(imageModel)),
            ConfigurationParameter(RunnerMultimediaHostPolicy.KEY, JsonPrimitive("credential:media@1")))
        suspend fun prepare(scope: WorkflowHostScope = this.scope, operations: Set<String> = setOf(RunnerMultimediaHostPolicy.PURPOSE),
                            grants: Set<String>? = setOf(imageModel), expires: Instant = Instant.now().plusSeconds(60)): List<ConfigurationParameter> = supervisorScope {
            val pending = async { host.prepareAgentParameters(scope.copy(parentExecutionId = null), parameters()) }
            val request = h.next(); assertEquals("credentialRequest", request.string("type"))
            val body = request.obj("payload")
            assertEquals(RunnerMultimediaHostPolicy.PURPOSE, body.string("purpose"))
            assertEquals("media", body.string("credentialRef"))
            assertEquals(childPath(scope.rootExecutionId, scope.executionId), body.array("childExecutionPath"))
            h.ipc.accept(h.reply(request, "credentialGrant", buildJsonObject {
                put("requestId", body.string("requestId")); put("credentialRef", body.string("credentialRef")); put("credentialLeaseId", "lease-${body.string("requestId")}")
                put("version", 1); put("revision", 1); put("expiresAt", expires.toString())
                put("allowedOperations", JsonArray(operations.map(::JsonPrimitive)))
                put("material", buildJsonObject { put("apiKey", key); grants?.let { put("grantedModelIds", JsonArray(it.map(::JsonPrimitive))) } })
            }))
            pending.await().also { prepared -> assertFalse(prepared.any { key in it.value.toString() }, "Agent parameters must never receive multimedia key material") }
        }
        fun tool(parameters: List<ConfigurationParameter>, scope: WorkflowHostScope = this.scope) =
            host.agentToolSets(scope, setOf("multimedia"), parameters).getValue("multimedia").single() as RunnerMultimediaGenerationTools
        suspend fun <T> within(scope: WorkflowHostScope = this.scope, block: suspend () -> T): T =
            WorkflowHostContext.withHost(host) { WorkflowHostContext.withScope(scope, block) }
        fun readOutput(path: String = "result.png", scope: WorkflowHostScope = this.scope) = host.toolFileAccess(scope).readBytes(path, 1024)
        override fun close() { try { host.close() } finally { h.close() } }
    }

    @Test fun `enablement requires accepted start trusted setting and advertised required capabilities`() {
        assertFalse(RunnerMultimediaHostPolicy.trustedEnabled(java.util.Properties()))
        assertFalse(RunnerMultimediaHostPolicy.CAPABILITY in RunnerHostSession.CAPABILITIES)
        for (mode in 0..4) IpcHarness().use { h ->
            val selected = if (mode == 2) required - "model.http.v1" else required
            val start = SandboxTestFrames.start(h.binding, selected)
            if (mode != 0) h.ipc.accept(start)
            assertFails {
                h.ipc.enableMultimedia(if (mode == 4) SandboxTestFrames.start(h.binding, required - "tool.file_system.v1") else start,
                    if (mode == 3) RunnerHostSession.CAPABILITIES else required, trustedEnabled = mode != 1)
            }
            assertFalse(h.ipc.multimediaNegotiated)
        }
    }

    @Test fun `actual host registers isolated adapter from a dedicated leased frozen selection`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind(); val parameters = f.prepare(); val tool = f.tool(parameters)
            f.within { tool.generateImage("fixture", "result.png") }
            assertContentEquals(png, f.readOutput()); assertEquals(1, f.sent.get())
            assertFails { f.tool(parameters.map { if (it.key == RunnerMultimediaHostPolicy.KEY) ConfigurationParameter(it.key, JsonPrimitive(f.key)) else it }) }
        }
    }

    @Test fun `disabled dynamic unbound and runtime route changes fail before credential acquisition`(@TempDir dir: Path): Unit = runBlocking {
        for (mode in 0..7) Fixture(Files.createDirectories(dir.resolve("case$mode")), negotiate = mode != 0).use { f ->
            if (mode != 1) f.bind(static = mode != 2)
            var parameters = f.parameters()
            if (mode == 3) parameters += ConfigurationParameter("multimedia_base_url", JsonPrimitive("https://attacker.invalid"))
            if (mode == 4) parameters = parameters.map { if (it.key == RunnerMultimediaHostPolicy.IMAGE) ConfigurationParameter(it.key, JsonPrimitive("unknown-model")) else it }
            if (mode == 5) parameters = parameters.filterNot { it.key == RunnerMultimediaHostPolicy.KEY }
            if (mode == 6) parameters += parameters.last()
            val scope = if (mode == 7) f.scope.copy(occurrence = "iterate:0") else f.scope
            assertFails { f.host.prepareAgentParameters(scope, parameters) }
            assertEquals(0, f.h.supervisorInput.available()); assertEquals(0, f.sent.get())
        }
    }

    @Test fun `preset and runtime params cannot supply missing explicit frozen tool or model`(@TempDir dir: Path): Unit = runBlocking {
        for ((index, source) in listOf(source(tools = "[]"), source(image = "{{var:model}}"), source().replace("multimedia_api_key: 'credential:media@1'", "multimedia_api_key: 'plain-secret'")).withIndex()) {
            Fixture(Files.createDirectories(dir.resolve("case$index")), mapOf("root" to source)).use { f ->
                f.bind(); assertFails { f.host.prepareAgentParameters(f.scope, f.parameters()) }
                assertEquals(0, f.h.supervisorInput.available())
            }
        }
    }

    @Test fun `lease requires multimedia purpose live expiry and explicit matching model grants`(@TempDir dir: Path): Unit = runBlocking {
        for (mode in 0..3) Fixture(Files.createDirectories(dir.resolve("case$mode"))).use { f ->
            f.bind()
            assertFails { f.prepare(operations = if (mode == 0) setOf("llm.request") else setOf(RunnerMultimediaHostPolicy.PURPOSE),
                grants = when (mode) { 1 -> null; 2 -> setOf("other-model"); else -> setOf(imageModel) },
                expires = if (mode == 3) Instant.now().minusSeconds(1) else Instant.now().plusSeconds(60)) }
            assertFails { f.tool(f.parameters()) }; assertEquals(0, f.sent.get())
        }
    }

    @Test fun `same key and reference never revive tools from a released scope generation`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind(); val first = f.prepare(); val retained = f.tool(first)
            f.host.derivedScopeEnded(f.scope)
            assertEquals("RELEASED", f.h.next().obj("payload").obj("payload").string("outcome"))
            val next = f.prepare(); assertEquals(first, next)
            f.within { assertFails { retained.generateImage("old", "old.png") } }
            assertEquals(0, f.sent.get())
            val replacement = f.tool(next)
            f.within { replacement.generateImage("new", "result.png") }
            assertEquals(1, f.sent.get()); assertContentEquals(png, f.readOutput())
        }
    }

    @Test fun `repreparing a still live handle invalidates the previous factory generation`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind(); val retained = f.tool(f.prepare())
            val next = f.host.prepareAgentParameters(f.scope, f.parameters()) // Same live handle, no new grant request.
            assertEquals(0, f.h.supervisorInput.available())
            f.within { assertFailsWith<SecurityException> { retained.generateImage("old", "old.png") } }
            assertEquals(0, f.sent.get())
            val tool = f.tool(next)
            f.within { tool.generateImage("fixture", "result.png") }
            assertEquals(1, f.sent.get())
        }
    }

    @Test fun `retained adapter cannot cross scope or continue after step environment or integrity loss`(@TempDir dir: Path): Unit = runBlocking {
        for (mode in 0..4) Fixture(Files.createDirectories(dir.resolve("case$mode"))).use { f ->
            f.bind(); val tool = f.tool(f.prepare())
            when (mode) {
                1 -> f.host.derivedScopeEnded(f.scope)
                2 -> f.gate.invalidate("fixture")
                3 -> Files.writeString(f.root.resolve("root.yaml"), "changed")
                4 -> f.host.close()
            }
            f.within(if (mode == 0) f.scope.copy(stepName = "other") else f.scope) { assertFails { tool.generateImage("fixture", "result.png") } }
            assertEquals(0, f.sent.get())
        }
    }

    @Test fun `child requires exact frozen target and independently leased canonical lineage`(@TempDir dir: Path): Unit = runBlocking {
        val root = source(extra = "  - step: nested\n    sub_workflow: {workflow_id: child}")
        Fixture(dir, mapOf("root" to root, "child" to source(), "wrong" to source())).use { f ->
            val child = f.bundle.resolve(WorkflowReference(workflowId = "child")).definition
            val scope = WorkflowHostScope(f.rootId, "${f.rootId}__nested__call_1", f.rootId, "answer")
            assertFails { f.bind(scope, child) }
            f.bind(); assertFails { f.bind(scope, f.bundle.resolve(WorkflowReference(workflowId = "wrong")).definition) }
            assertFails { f.bind(scope.copy(parentExecutionId = null), child) }
            f.bind(scope, child)
            val parameters = f.prepare(scope)
            val tool = f.tool(parameters, scope.copy(parentExecutionId = null))
            assertFails { f.tool(parameters, scope.copy(parentExecutionId = "foreign")) }
            f.within { assertFails { tool.generateImage("fixture", "foreign.png") } }
            f.within(scope) { tool.generateImage("fixture", "result.png") }
            assertEquals(1, f.sent.get()); assertContentEquals(png, f.readOutput(scope = scope))
        }
    }

    @Test fun `cancellation after dispatch prevents output and any second dispatch without replay`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind(); val tool = f.tool(f.prepare())
            f.beforeReply = { f.h.ipc.businessFence.close() }
            f.within { assertFailsWith<CancellationException> { tool.generateImage("fixture", "result.png") } }
            f.within { assertFailsWith<CancellationException> { tool.generateImage("fixture", "again.png") } }
            assertEquals(1, f.sent.get())
            assertFalse(Files.walk(f.root).use { paths -> paths.anyMatch { it.fileName.toString() in setOf("result.png", "again.png") } })
        }
    }

    @Test fun `server busy response never repeats generation and creates no output`(@TempDir dir: Path): Unit = runBlocking {
        Fixture(dir).use { f ->
            f.bind(); val tool = f.tool(f.prepare()); f.status = 503
            f.within { assertFails { tool.generateImage("fixture", "result.png") } }
            assertEquals(1, f.sent.get()); assertFails { f.readOutput() }
        }
    }
}
