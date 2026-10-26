package com.fartech.agents.runner

import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Standalone TEST-ONLY launcher. Never included in main distributions. No real model/account calls. */
fun main(args: Array<String>) {
    val protocolOut = System.out
    val secrets = RunnerSecrets()
    System.setErr(java.io.PrintStream(secrets.logStream(System.err), true, Charsets.UTF_8)); System.setOut(System.err)
    System.setProperty("braidrun.quietConsole", "true"); System.setProperty("kotlin-logging.logStartupMessage", "false")
    require(args.size in setOf(2, 4) && args[0] == "--binding" && (args.size == 2 || args[2] == "--pause-dir"))
    val binding = RunnerProtocol().parse(Files.readString(Path.of(args[1])), "binding")
    val pause = if (args.size == 4) Path.of(args[3]).toAbsolutePath().normalize() else null
    val exit = try { runBlocking { FixtureHttpSession(binding, protocolOut, secrets, pause).run() } }
    catch (failure: Exception) {
        System.err.println("TEST-ONLY HTTP Agent fixture refused: ${failure.javaClass.name}")
        failure.stackTrace.filter { it.className.startsWith("com.fartech") }.take(6).forEach { System.err.println("site=${it.className}.${it.methodName}:${it.lineNumber}") }
        2
    }
    kotlin.system.exitProcess(exit)
}

private class FixtureHttpSession(
    private val binding: JsonObject, private val output: java.io.OutputStream,
    private val secrets: RunnerSecrets, private val pause: Path?
) {
    private val start = CompletableDeferred<JsonObject>()
    @Volatile private var gate: RunnerEnvironmentGate? = null
    private val earlyControls = RunnerEarlyControls(binding)
    @Volatile private var runtime: RunnerWorkflowHost? = null
    @Volatile private var task: Deferred<WorkflowExecutionResult>? = null
    @Volatile private var stopReason: String? = null
    private var processes: RunnerProcesses? = null
    private var channels: RunnerInteractiveProcesses? = null
    private lateinit var ipc: RunnerIpc
    private fun stop(reason: String = "RUNNER_AUTHORITY_LOST") {
        if (stopReason == null) stopReason = reason
        gate?.invalidate(reason); start.cancel(); task?.cancel()
    }
    private fun control(frame: JsonObject) {
        val body = frame.obj("payload")
        when (frame.string("type")) {
            "start" -> check(start.complete(frame))
            "resume" -> error("Fixture does not implement production restoration")
            "cancel" -> stop("CANCELLED")
            "environmentInvalidated", "error" -> stop()
            "environmentRefreshed" -> earlyControls.refreshed(body, frame["replyTo"]?.jsonPrimitive?.content)
            "leaseRenewed" -> earlyControls.renewed(body.obj("lease"))
            "ack" -> requireNotNull(runtime).acknowledge(body)
            "artifactCollectRequest" -> requireNotNull(runtime).collectRequest(frame)
            "processExited" -> requireNotNull(processes).exited(body)
            "processOutput" -> requireNotNull(channels).output(body)
            "processClosed" -> requireNotNull(channels).closed(body)
            else -> throw RunnerProtocolException()
        }
    }
    suspend fun run(): Int = coroutineScope {
        ipc = RunnerIpc(binding, System.`in`, output, onControl = ::control, onFailure = { stop() }); ipc.start()
        val modelText = """{"investment_decisions":[{"sheet_name":"fixture","status":"hold"}],"summaries":[{"sheet_name":"fixture","items":["summary"]}],"followups":[{"sheet_name":"fixture","items":["follow up"]}]}"""
        val mock = HttpClient(MockEngine) { engine { addHandler {
            val reply = buildJsonObject {
                put("id", "test-only-model"); put("object", "chat.completion"); put("created", 1760000000); put("model", "gpt-4o-mini")
                put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("message", buildJsonObject { put("role", "assistant"); put("content", modelText) }); put("finish_reason", "stop") }) })
                put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
            }
            respond(reply.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } } }
        try {
            ipc.send("ready", buildJsonObject { put("runtimeHash", binding.string("runtimeHash")); put("capabilities", JsonArray(RunnerHostSession.CAPABILITIES.map(::JsonPrimitive))) })
            val payload = withTimeout(30_000) { start.await() }.obj("payload")
            val required = payload.obj("authorization").array("requiredCapabilities").map { it.jsonPrimitive.content }.toSet()
            val mode = RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES)
            val g = RunnerEnvironmentGate(binding, payload.obj("environment").array("egress").map { it.jsonObject.string("pathId") }.toSet()); gate = g
            g.onInvalidated { stop() }; earlyControls.attach(g, payload.obj("lease"), payload.obj("environment"))
            val root = Path.of(payload.string("executionDirectory")).toRealPath(); require(root == Path.of("").toRealPath())
            if (pause != null) { require(pause.startsWith(root.resolve(".runner-state"))); Files.createDirectories(pause) }
            val bundle = FrozenWorkflowBundle(root, payload.obj("bundle"), binding); bundle.verifyPortableAssets()
            require(bundle.allDefinitions.all { def -> def.agents.values.none { (it.resolveParameters()["type"]?.jsonPrimitive?.content ?: it.type) in setOf("claude_code_agent", "codex_agent") } }) { "Only controlled HTTP model fixture agents are allowed" }
            val policy = if (RunnerCollectionProtocol.CAPABILITY in required) RunnerCollectionPolicy(bundle) else null
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), payload.obj("authorization").string("billingOwnerId"), binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"), binding.string("attemptId"), binding.string("definitionHash"), binding.string("runtimeHash"))
            val store = RunnerCheckpointStore(root, binding, secrets); val captures = RunnerArtifactReader(root, binding, secrets)
            val interactive = RunnerInteractiveProcesses(ipc, root, g, secrets, ownerJob = currentCoroutineContext()[Job]); channels = interactive
            val process = RunnerProcesses(ipc, root, g, interactive) { id, path -> captures.read(id, path) }; processes = process
            val normal = RunnerWorkflowHost(ipc, identity, g, bundle, RunnerHostSession.CAPABILITIES, process, store, secrets,
                validateDirectNetwork = { /* TEST ONLY: the model transport below is MockEngine, never a real socket. */ },
                interactiveBroker = interactive, callbackEnabled = RunnerCodeControlNegotiation.LEGACY in required,
                codeControlMode = mode, collectionPolicy = policy)
            runtime = normal
            secrets.register(JsonPrimitive("test-only-model-key"))
            val host = object : WorkflowHost by normal {
                override val llmHttpClientFactory = KtorKoogHttpClient.Factory(mock)
                override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<ConfigurationParameter>) = parameters + listOf(
                    ConfigurationParameter("openai_api_key", JsonPrimitive("test-only-model-key")), ConfigurationParameter("disable_skills", JsonPrimitive(true)))
            }
            if (policy != null) ipc.enableArtifactCollection()
            normal.emit("environment", buildJsonObject {
                val observed = payload.obj("environment")
                for (key in listOf("environmentCheckId", "decision", "warnings", "systemLanguage", "languageSource")) put(key, observed.getValue(key))
            })
            val monitor = launch { while (isActive) { delay(50); try { g.check() } catch (_: RunnerAuthorityLost) { stop(); return@launch } } }
            try {
                val interpreter = WorkflowExecutor(HttpAccess(), emptyList(), host = host, jevEnvKeyFallback = false)
                interpreter.beforeStructuredOutputWriteForTest = { target ->
                    pause?.let { control ->
                        val path = Path.of(target).toAbsolutePath().normalize(); require(path.startsWith(root))
                        val logical = root.relativize(path).toString(); val key = sha256(logical.toByteArray())
                        Files.writeString(control.resolve("$key.waiting"), logical)
                        while (!Files.exists(control.resolve("$key.release"))) { currentCoroutineContext().ensureActive(); g.check(); delay(10) }
                    }
                }
                val executing = async { interpreter.execute(bundle.rootDefinition, payload.obj("inputs").mapValues { it.value.jsonPrimitive.content }, identity.executionId) }; task = executing
                val result = try { executing.await() } catch (_: CancellationException) { null }
                val status = when { stopReason == "CANCELLED" -> "CANCELLED"; stopReason != null || result == null -> "INTERRUPTED"; result.success -> "COMPLETED"; else -> "FAILED" }
                val resultHash = store.writePrivate(".runner-state/result.json", runnerResultDocument(binding, status, result, secrets).toString().toByteArray())
                val manifestHash = store.writePrivate(".runner-state/artifact-manifest.json", "[]".toByteArray())
                ipc.send("result", buildJsonObject {
                    put("binding", binding); put("finalEventSeq", normal.finalEventSeq()); put("resultHash", resultHash); put("artifactManifestHash", manifestHash); put("status", status)
                    put("terminationReason", stopReason?.let(::JsonPrimitive) ?: JsonNull)
                    put("stopReceipt", buildJsonObject { put("binding", binding); put("receiptId", UUID.randomUUID().toString()); put("recordedAt", Instant.now().toString()); put("reason", "JVM_EXIT_PENDING"); put("confirmed", false); put("engineStarted", true); put("registeredProcesses", JsonArray(emptyList())); put("unmanagedProcessDetected", false); put("journalHash", sha256(byteArrayOf())) })
                })
                ipc.drain(); if (status == "COMPLETED") 0 else 1
            } finally { monitor.cancel(); task = null; interactive.close(); normal.close() }
        } finally { mock.close(); ipc.close() }
    }
}
