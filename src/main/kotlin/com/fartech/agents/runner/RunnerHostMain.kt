package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** One JVM per root execution, launched by supervisor with only the nonsecret binding path. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    val secrets = RunnerSecrets()
    val redactedLogs = java.io.PrintStream(secrets.logStream(System.err), true, Charsets.UTF_8)
    System.setErr(redactedLogs); System.setOut(redactedLogs)
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    System.setProperty("braidrun.quietConsole", "true")
    // Background threads (IO dispatchers, static initializers) must not print messages or cause chains either.
    Thread.setDefaultUncaughtExceptionHandler { _, failure -> System.err.println(runnerFailureSummary(failure)) }
    if (args.size == 1 && args[0] == "--capabilities") {
        // Offline adapter inventory for packaging/registration: no binding, network, process or credential use.
        protocolOutput.println(JsonArray(RunnerHostSession.CAPABILITIES.map(::JsonPrimitive)).toString())
        protocolOutput.flush()
        exitProcess(0)
    }
    val code = try {
        require(args.size == 2 && args[0] == "--binding") { "Use --binding <nonsecret binding JSON path>" }
        val bindingPath = Path.of(args[1])
        require(Files.size(bindingPath) <= RunnerIpc.MAX_FRAME_BYTES) { "Binding file too large" }
        val binding = RunnerProtocol().parse(Files.readString(bindingPath), "binding")
        runBlocking { RunnerHostSession(binding, System.`in`, protocolOutput, secrets).run() }
    } catch (failure: RunnerNegotiationException) {
        System.err.println("Runner host refused startup: ${failure.reason.name}"); 2
    } catch (failure: Throwable) {
        // Errors too (e.g. ExceptionInInitializerError wrapping a URL with a query key): class names only.
        System.err.println(runnerFailureSummary(failure)); 2
    }
    exitProcess(code)
}

/**
 * The engine's raw result. Only workflow content (variables, step outputs, error) is redacted. The
 * binding and status are control data that S compares exactly (validate_result_file): scrubbing a
 * registered substring out of them (a login label "local" inside `local-user`) turned a clean FAILED
 * into a protocol failure and an INTERRUPTED execution.
 */
internal fun runnerResultDocument(binding: JsonObject, status: String, result: WorkflowExecutionResult?, secrets: RunnerSecrets): JsonObject = buildJsonObject {
    put("binding", binding); put("status", status)
    put("variables", secrets.redact(JsonObject(result?.variables.orEmpty().mapValues { JsonPrimitive(it.value.toString()) })))
    put("outputs", secrets.redact(JsonObject(result?.stepResults.orEmpty().mapValues { JsonPrimitive(it.value.output.orEmpty()) })))
    result?.error?.let { put("error", secrets.redact(it)) }
}

/** Exception messages may contain unregistered endpoint query keys or local account paths. */
internal fun runnerFailureSummary(failure: Throwable): String =
    "Runner host refused startup or lost authority: ${failure.javaClass.name}" +
        (failure.cause?.let { " caused by ${it.javaClass.name}" } ?: "")

class RunnerHostSession(
    private val binding: JsonObject,
    private val input: java.io.InputStream,
    private val output: java.io.OutputStream,
    private val secrets: RunnerSecrets = RunnerSecrets()
) {
    private val start = CompletableDeferred<JsonObject>()
    private val begun = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val stopReason = java.util.concurrent.atomic.AtomicReference<String?>()
    @Volatile private var gate: RunnerEnvironmentGate? = null
    private val earlyControls = RunnerEarlyControls(binding)
    @Volatile private var execution: Job? = null
    @Volatile private var host: RunnerWorkflowHost? = null
    @Volatile private var processes: RunnerProcesses? = null
    @Volatile private var interactive: RunnerInteractiveProcesses? = null
    private lateinit var ipc: RunnerIpc
    private fun stop(reason: String = "RUNNER_AUTHORITY_LOST") {
        stopReason.compareAndSet(null, reason)
        stopped.set(true); gate?.invalidate("RUNNER_CONTROL_STOP"); interactive?.close(); start.cancel(); execution?.cancel(RunnerAuthorityLost())
    }
    private fun control(frame: JsonObject) {
        val payload = frame.obj("payload")
        when (frame.string("type")) {
            "start", "resume" -> { if (!begun.compareAndSet(false, true)) throw RunnerProtocolException(); start.complete(frame) }
            "cancel" -> stop("CANCELLED")
            "environmentInvalidated" -> stop("RUNNER_ENVIRONMENT_INVALIDATED")
            "error" -> stop("RUNNER_CONTROL_ERROR")
            // Both may precede start; see RunnerEarlyControls.
            "environmentRefreshed" -> earlyControls.refreshed(payload, frame["replyTo"]?.jsonPrimitive?.content)
            "leaseRenewed" -> earlyControls.renewed(payload.obj("lease"))
            "ack" -> { requireNotNull(host).acknowledge(payload); requireNotNull(host).setControl(frame) }
            "processExited" -> requireNotNull(processes).exited(payload)
            "processOutput" -> requireNotNull(interactive).output(payload)
            "processClosed" -> requireNotNull(interactive).closed(payload)
            "artifactSetCollectRequest", "artifactSetIndexAck" -> requireNotNull(host).setControl(frame)
            "artifactCollectRequest" -> requireNotNull(host).collectRequest(frame)
            else -> throw RunnerProtocolException()
        }
    }
    suspend fun run(): Int = coroutineScope {
        ipc = RunnerIpc(binding, input, output, onControl = ::control, onFailure = { stop() })
        ipc.start()
        try {
            ipc.send("ready", buildJsonObject {
                put("runtimeHash", binding.string("runtimeHash")); put("capabilities", JsonArray(CAPABILITIES.map(::JsonPrimitive)))
            })
            val frame = withTimeout(30_000) { start.await() }
            val payload = frame.obj("payload")
            val authorization = payload.obj("authorization")
            val requiredCapabilities = authorization.array("requiredCapabilities").map { it.jsonPrimitive.content }.toSet()
            val codeControlMode = RunnerCodeControlNegotiation.select(requiredCapabilities, CAPABILITIES)
            val environment = RunnerEnvironmentGate(binding, payload.obj("environment").array("egress").map { it.jsonObject.string("pathId") }.toSet())
            gate = environment
            environment.onInvalidated { stop() }
            earlyControls.attach(environment, payload.obj("lease"), payload.obj("environment"))
            if (stopped.get()) throw RunnerAuthorityLost()
            val directory = Path.of(payload.string("executionDirectory")).toRealPath()
            // Relative runtime paths cannot accidentally refer to the JVM launcher's directory.
            require(directory == Path.of("").toRealPath()) { "Supervisor must launch JVM in execution directory" }
            val bundle = FrozenWorkflowBundle(directory, payload.obj("bundle"), binding)
            bundle.verifyPortableAssets()
            val setsSelected = RunnerArtifactSetProtocol.CAPABILITY in requiredCapabilities
            if (bundle.hasFile(RunnerArtifactSetPolicy.PATH) && !setsSelected) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_ARTIFACT_CAPABILITY_REQUIRED)
            val setPolicy = if (setsSelected) RunnerArtifactSetPolicy(bundle) else null
            val collectionSelected = RunnerCollectionProtocol.CAPABILITY in requiredCapabilities
            if (bundle.hasFile(RunnerCollectionPolicy.PATH) && !collectionSelected) throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_ARTIFACT_CAPABILITY_REQUIRED)
            val collectionPolicy = if (!collectionSelected || (setsSelected && !bundle.hasFile(RunnerCollectionPolicy.PATH))) null else try {
                require(RunnerCollectionProtocol.available())
                RunnerCollectionPolicy(bundle)
            } catch (_: Exception) { throw RunnerNegotiationException(RunnerNegotiationReason.RUNNER_ARTIFACT_POLICY_INVALID) }
            val identity = WorkflowHostBinding(binding.string("ownerId"), binding.string("actorUserId"), authorization.string("billingOwnerId"),
                binding.string("executionId"), binding.string("runnerId"), binding.string("runnerInstanceId"), binding.long("resumeEpoch"),
                binding.string("attemptId"), binding.string("definitionHash"), binding.string("runtimeHash"))
            val store = RunnerCheckpointStore(directory, binding, secrets)
            val artifactReader = RunnerArtifactReader(directory, binding, secrets)
            val channels = RunnerInteractiveProcesses(ipc, directory, environment, secrets, ownerJob = currentCoroutineContext()[Job])
            interactive = channels
            val processAdapter = RunnerProcesses(ipc, directory, environment, interactive = channels) { id, logicalPath ->
                try { artifactReader.read(id, logicalPath) } catch (_: Exception) {
                    environment.invalidate("RUNNER_CAPTURE_INTEGRITY_FAILED"); throw RunnerAuthorityLost()
                }
            }
            processes = processAdapter
            val runtime = RunnerWorkflowHost(ipc, identity, environment, bundle, CAPABILITIES, processAdapter, store, secrets, interactiveBroker = channels,
                callbackEnabled = RunnerCodeControlNegotiation.LEGACY in requiredCapabilities, codeControlMode = codeControlMode, collectionPolicy = collectionPolicy, setPolicy = setPolicy)
            host = runtime
            if (collectionSelected) ipc.enableArtifactCollection()
            if (setsSelected) ipc.enableArtifactSets()
            runtime.emit("environment", buildJsonObject {
                put("environmentCheckId", payload.obj("environment").string("environmentCheckId"))
                put("decision", payload.obj("environment").string("decision")); put("warnings", payload.obj("environment").array("warnings"))
                put("systemLanguage", payload.obj("environment").getValue("systemLanguage")); put("languageSource", payload.obj("environment").getValue("languageSource"))
            })
            val required = RunnerAdmission(bundle, CAPABILITIES).inspect(bundle.rootDefinition)
            if ("model.http.v1" in required) { RunnerHttpClients.requireDirect(); environment.requirePaths(setOf("jvm-http")) }
            if (required.any { it in setOf("agent.codex.v1", "agent.claude.v1") }) { RunnerHttpClients.requireDirect(); environment.requirePaths(setOf("agent-cli")) }
            if ("step.script.v1" in required) environment.requirePaths(setOf("subprocess"))
            val restored = if (frame.string("type") == "resume") {
                store.restore(payload.obj("checkpoint"), payload.obj("previousBinding"))
            } else null
            restored?.let(runtime::restoreHostState)
            val monitor = launch(Dispatchers.Default) {
                while (isActive) { delay(100); try { environment.check() } catch (_: RunnerAuthorityLost) { stop(); return@launch } }
            }
            try {
                val resultJob = async(Dispatchers.Default) {
                    WorkflowExecutor(HttpAccess(), listOf(com.fartech.ftapp2.commonsKt.ConfigurationParameter("user_id", JsonPrimitive(identity.ownerId))), host = runtime, jevEnvKeyFallback = false).execute(
                        bundle.rootDefinition, payload.obj("inputs").mapValues { it.value.jsonPrimitive.content },
                        identity.executionId, restored
                    )
                }
                execution = resultJob
                if (stopped.get()) resultJob.cancel(RunnerAuthorityLost())
                val result = try { resultJob.await() } catch (_: CancellationException) { null }
                try { environment.check() } catch (_: RunnerAuthorityLost) { stop() }
                val status = if (stopReason.get() == "CANCELLED") "CANCELLED" else if (stopped.get() || result == null) "INTERRUPTED" else if (result.success) "COMPLETED" else "FAILED"
                val finalEventSeq = runtime.beginFinalization()
                store.writePrivate(".runner-state/finalization.json", canonical(buildJsonObject {
                    put("binding", binding); put("status", status); put("finalEventSeq", finalEventSeq)
                    put("phase", "E_BUSINESS_CLOSED_JVM_EXIT_PENDING"); put("businessOperationsClosed", true)
                    put("stopConfirmed", false); put("sourceAcceptance", "PENDING_P_S_FINAL_AUTHORITY")
                }).toByteArray())
                val resultBytes = runnerResultDocument(binding, status, result, secrets).toString().toByteArray(Charsets.UTF_8)
                val resultHash = store.writePrivate(".runner-state/result.json", resultBytes)
                val artifactManifestHash = store.writePrivate(".runner-state/artifact-manifest.json", "[]".toByteArray())
                val receipt = unconfirmedReceipt()
                ipc.send("result", buildJsonObject {
                    put("binding", binding); put("finalEventSeq", finalEventSeq); put("resultHash", resultHash)
                    put("artifactManifestHash", artifactManifestHash); put("status", status)
                    put("terminationReason", stopReason.get()?.let(::JsonPrimitive) ?: if (result == null) JsonPrimitive("RUNNER_AUTHORITY_LOST") else JsonNull)
                    put("stopReceipt", receipt)
                })
                ipc.drain()
                if (status == "COMPLETED") 0 else 1
            } finally { monitor.cancel(); execution = null; channels.close(); runtime.close() }
        } finally { ipc.close() }
    }
    private fun unconfirmedReceipt(): JsonObject = buildJsonObject {
        put("binding", binding); put("receiptId", UUID.randomUUID().toString()); put("recordedAt", Instant.now().toString())
        put("reason", "JVM_EXIT_PENDING"); put("confirmed", false); put("engineStarted", true)
        put("registeredProcesses", JsonArray(emptyList())); put("unmanagedProcessDetected", false)
        put("journalHash", sha256(ByteArray(0))) // only supervisor can replace with actual journal/stop proof
    }
    companion object {
        // Engine adapter inventory; platform/supervisor admission and runtime dependency receipts remain separate.
        val CAPABILITIES = linkedSetOf("bundle.portableAssets.v1", "control.callback.v1", "workflow.full.v1", "step.agent.v1", "model.http.v1", "agent.codex.v1", "agent.claude.v1", "tool.exit.v1", "tool.workflow.v1", "tool.user_interaction.v1", "skill.text.v1", "tool.skill_tools.v1", "service.mcp_servers.v1", "mcp.stdio.v1", "process.interactive.v1", "step.script.v1", "step.subWorkflow.v1", "step.stateMachine.v1", "interaction.approval.v1", "output.read.v1", "output.publish.v1", RunnerAdmission.CONCURRENT_CAPABILITY, RunnerLoopPolicy.CAPABILITY).apply {
            if (RunnerCodeControlNegotiation.supplementAvailable()) {
                add(RunnerCodeControlNegotiation.PER_CALL)
                add(RunnerCodeControlNegotiation.SOURCE_READ_POLICY) // P/S separately verify the frozen historical-source carrier.
                add(RunnerCodeControlNegotiation.FEEDBACK_CONTEXT) // S must separately admit its guarded HTTP adapter + schema pin.
            }
            if (RunnerCollectionProtocol.available()) {
                add(RunnerCollectionProtocol.CAPABILITY)
                if (RunnerArtifactSetProtocol.available()) add(RunnerArtifactSetProtocol.CAPABILITY)
            }
            // gateSets requires the real guarded P/S provider; source.attestation awaits approval/terminal lifecycle.
            // Confined to this run's directories (RunnerFileConfinement); needs POSIX link counts.
            if (RunnerFileConfinement.available()) add("tool.file_system.v1")
        }
    }
}
