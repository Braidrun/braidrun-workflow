package com.fartech.agents.runner

import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.UUID

/** Stdio adapter uses only the pinned control contract and frozen definition resolver. */
class RunnerWorkflowHost(
    private val ipc: RunnerIpc,
    override val binding: WorkflowHostBinding,
    private val gate: RunnerEnvironmentGate,
    private val bundle: FrozenWorkflowBundle,
    private val capabilities: Set<String>,
    subprocessExecutor: SubprocessExecutor,
    private val store: RunnerCheckpointStore,
    private val secrets: RunnerSecrets,
    private val validateDirectNetwork: () -> Unit = { RunnerHttpClients.requireDirect() },
    private val interactiveBroker: RunnerInteractiveProcesses? = null,
    private val callbackEnabled: Boolean = false,
    override val codeControlMode: WorkflowCodeControlMode = WorkflowCodeControlMode.LEGACY_STEP_TOKEN,
    private val artifactPolicy: RunnerArtifactPolicy? = null,
    artifactFingerprinter: RunnerArtifactFingerprinter? = null,
    private val collectionPolicy: RunnerCollectionPolicy? = null,
    private val setPolicy: RunnerArtifactSetPolicy? = null,
    private val localModelPolicy: RunnerLocalModelPolicy? = null,
    private val localModelAuthority: RunnerIpcLocalModelAuthority? = null,
    private val subAgentToolsEnabled: Boolean = false,
    /** Trusted transport injection for host-boundary tests; workflow parameters cannot supply this. */
    private val multimediaHttpFactory: ((() -> Unit) -> com.fartech.ftapp2.commonsKt.HttpAccess)? = null
) : WorkflowHost {
    init {
        require(bundle.taskBinding == ipc.binding) { "Bundle host binding differs from IPC authority" }
        val pairs = mapOf("ownerId" to binding.ownerId, "actorUserId" to binding.actorUserId, "executionId" to binding.executionId,
            "runnerId" to binding.runnerId, "runnerInstanceId" to binding.runnerInstanceId, "attemptId" to binding.attemptId,
            "definitionHash" to binding.definitionHash, "runtimeHash" to binding.runtimeHash)
        require(pairs.all { (key, value) -> ipc.binding.string(key) == value } && ipc.binding.long("resumeEpoch") == binding.resumeEpoch) { "Host identity differs from IPC authority" }
        require((localModelPolicy == null) == (localModelAuthority == null)) { "Local model policy and authority must be paired" }
    }
    private val staticInvocations = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val admittedProducers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sandboxInvocations = java.util.concurrent.ConcurrentHashMap<String, WorkflowHostScope>()
    private val multimediaInvocations = java.util.concurrent.ConcurrentHashMap<String, WorkflowHostScope>()
    private val multimediaPolicy = if (ipc.multimediaNegotiated) {
        require(setOf(RunnerMultimediaHostPolicy.CAPABILITY, "tool.file_system.v1", "model.http.v1").all { it in capabilities })
        RunnerMultimediaHostPolicy(bundle)
    } else null
    private val sandboxPolicy = if (ipc.agentSandboxNegotiated) {
        require(RunnerSandboxProtocol.CAPABILITY in capabilities && "tool.file_system.v1" in capabilities)
        RunnerSandboxPolicy(bundle)
    } else null
    private val businessFence = ipc.businessFence
    private val resourcesClosed = java.util.concurrent.atomic.AtomicBoolean()
    private fun checkBusinessOpen() = businessFence.check()
    private val localModelClients = java.util.concurrent.ConcurrentHashMap.newKeySet<RunnerLocalModelClient>()
    override fun localModel(scope: WorkflowHostScope): WorkflowLocalModelSelection? {
        val policy = localModelPolicy ?: return null
        checkBusinessOpen(); gate.check(); policy.verify()
        require(scope.rootExecutionId == binding.executionId && staticInvocations[scope.executionId] == true) { "Local model invocation is not frozen and static" }
        val workflow = requireNotNull(admittedProducers[scope.executionId])
        val path = childPath(binding.executionId, scope.executionId).map { it.jsonPrimitive.content }
        val step = scope.stepName ?: return null
        val selected = policy.resolve(workflow, step, path) ?: return null
        val pin = selected.binding
        fun checkLive() {
            checkBusinessOpen(); gate.check(); policy.verify()
            require(staticInvocations[scope.executionId] == true && admittedProducers[scope.executionId] == workflow && policy.resolve(workflow, step, path) == selected)
        }
        return WorkflowLocalModelSelection(binding, scope,
            ai.koog.prompt.llm.LLModel(provider = ai.koog.prompt.llm.LLMProvider.Ollama, id = pin.model, capabilities = listOf(ai.koog.prompt.llm.LLMCapability.Completion),
                contextLength = pin.contextTokens.toLong(), maxOutputTokens = pin.maximumOutputTokens.toLong()),
            ai.koog.prompt.params.LLMParams(temperature = 0.0, maxTokens = selected.outputTokens, numberOfChoices = 1, toolChoice = ai.koog.prompt.params.LLMParams.ToolChoice.None)) {
            // Admission and registration share the finalization fence: a retained factory cannot
            // add an unowned transport after close has taken its resource snapshot.
            businessFence.admit {
                checkLive()
                val client = RunnerLocalModelClient(pin, gate, businessFence, requireNotNull(localModelAuthority))
                localModelClients.add(client)
                try {
                    RunnerLocalModelPromptExecutor(pin, step, path, client, ::checkLive, {},
                        onClose = { localModelClients.remove(client) })
                } catch (error: Throwable) {
                    localModelClients.remove(client)
                    client.close()
                    throw error
                }
            }
        }
    }
    private val processDelegate = subprocessExecutor
    override val subprocessExecutor: SubprocessExecutor = object : SubprocessExecutor {
        override val isDocker get() = processDelegate.isDocker
        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult = businessFence.operation {
            processDelegate.execute(request.copy(environmentAtStart = {
                businessFence.check()
                request.environmentAtStart?.invoke().orEmpty()
            }))
        }
    }
    private val producerStarts = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, String>()
    private val codeProducerStarts = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, String>()
    private data class ActiveProducer(val eventId: String, val eventSeq: Long)
    private val agentReadySequences = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()
    private val activeProducers = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, ActiveProducer>()
    private val activeCodeProducers = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, ActiveProducer>()
    private val collectionBarrier = collectionPolicy?.let { policy ->
        RunnerCollectionBarrier(bundle.executionDirectory, ipc, store, gate, policy, secrets, ::checkCollectionProducer,
            recordEvent = { payload, replyTo, frameId, before -> synchronized(this) {
                checkCollectionProducer(payload)
                eventJournal.append("artifact", payload, frameId, replyTo, before)
            } }, acknowledge = ::acknowledge,
            guardedComplete = { body, action -> synchronized(this) { checkCollectionProducer(body); action() } })
    }
    fun collectRequest(frame: JsonObject) { checkBusinessOpen(); requireNotNull(collectionBarrier) { "Artifact collection was not negotiated" }.receive(frame) }
    @Synchronized private fun checkCollectionProducer(body: JsonObject) {
        gate.check()
        val policy = requireNotNull(collectionPolicy)
        val child = body.array("childExecutionPath").map { it.jsonPrimitive.content }
        val execution = binding.executionId + if (child.isEmpty()) "" else "__" + child.joinToString("__")
        val workflowId = body.string("producerWorkflowId"); val step = body.string("producerStep")
        require(staticInvocations[execution] == true && admittedProducers[execution] == workflowId) { "Collection producer was not actually invoked" }
        val occurrence = (if (policy.isCode(workflowId, step)) activeCodeProducers else activeProducers)[execution to step]
            ?: error("Collection producer is closed or unknown")
        require(occurrence.eventId == body.string("producerStartEventId") && eventJournal.acknowledged() >= occurrence.eventSeq) { "Collection occurrence is stale or not durably ACKed" }
        if (!policy.isCode(workflowId, step)) {
            val declaration = body["declarationId"]?.jsonPrimitive?.content ?: policy.declarationForPath(workflowId, execution, step, body.string("logicalPath"))
            val readySeq = requireNotNull(agentReadySequences[occurrence.eventId to declaration]) { "Agent boundary is not ready" }
            require(eventJournal.acknowledged() >= readySeq) { "Agent boundary is not durably ACKed" }
        }
    }
    private val setProducerBudgets = java.util.concurrent.ConcurrentHashMap<String, List<() -> Long>>()
    private val artifactSets = setPolicy?.let { policy -> RunnerArtifactSets(bundle.executionDirectory, ipc, store, gate, policy, secrets,
        guarded = { producer, action -> synchronized(this) { checkSetProducer(producer); action() } },
        append = { payload, reply, before -> eventJournal.append("step", payload, replyTo = reply, beforePersist = before) },
        acknowledged = { eventJournal.acknowledged() }) }
    fun setControl(frame: JsonObject) {
        if (businessFence.isClosed() && frame.string("type") == "ack") return
        checkBusinessOpen()
        if (frame.string("type") == "ack") artifactSets?.receive(frame)
        else requireNotNull(artifactSets) { "Artifact sets not negotiated" }.receive(frame)
    }
    @Synchronized private fun checkSetProducer(producer: JsonObject) {
        gate.check(); requireNotNull(setPolicy).verify()
        val child = producer.array("childExecutionPath").map { it.jsonPrimitive.content }
        val execution = binding.executionId + if (child.isEmpty()) "" else "__" + child.joinToString("__")
        val workflowId = producer.string("workflowId"); val step = producer.string("stepName")
        require(staticInvocations[execution] == true && admittedProducers[execution] == workflowId)
        require(setPolicy.declarationsFor(workflowId, execution, step).isNotEmpty())
        val active = (if (setPolicy.isCode(workflowId, step)) activeCodeProducers else activeProducers)[execution to step]
        val start = producer.string("producerStartEventId")
        require(active?.eventId == start) { "Set producer is closed or stale" }
        val budgets = requireNotNull(setProducerBudgets[start]) { "Producer is not at its natural set boundary" }
        require(budgets.all { it() > 0 }) { "Original producer budget expired" }
    }
    private suspend fun collectSets(step: WorkflowStep, scope: WorkflowHostScope, success: Boolean) {
        val policy = setPolicy ?: return
        try {
            gate.check(); require(scope.rootExecutionId == binding.executionId && scope.stepName == step.step)
            if (staticInvocations[scope.executionId] == false) return
            val workflow = requireNotNull(admittedProducers[scope.executionId])
            val ids = policy.declarationsFor(workflow, scope.executionId, step.step)
            if (ids.isEmpty() || (!success && !policy.hasDiagnostics(ids))) return
            val start = requireNotNull((if (step.code != null) codeProducerStarts else producerStarts)[scope.executionId to step.step])
            val producer = buildJsonObject {
                put("workflowId", workflow); put("stepName", step.step); put("childExecutionPath", childPath(binding.executionId, scope.executionId)); put("producerStartEventId", start)
            }
            val sets = requireNotNull(artifactSets)
            val inherited = kotlinx.coroutines.currentCoroutineContext()[ArtifactCompletionBudgets]?.remaining.orEmpty()
            val fallback = System.nanoTime() + 30_000_000_000L
            val budgets = inherited.ifEmpty { listOf({ (fallback - System.nanoTime()) / 1_000_000 }) }
            require(setProducerBudgets.putIfAbsent(start, budgets) == null) { "Set boundary already waiting" }
            try { sets.awaitCompleted(sets.ready(producer, ids, if (success) "SUCCESS" else "FAILED")) }
            finally { setProducerBudgets.remove(start) }
        } catch (failure: kotlinx.coroutines.CancellationException) { gate.invalidate("RUNNER_ARTIFACT_SET_UNCONFIRMED"); throw failure }
        catch (_: Exception) { gate.invalidate("RUNNER_ARTIFACT_SET_UNCONFIRMED"); throw RunnerAuthorityLost() }
    }
    override suspend fun codeArtifactsReady(workflow: WorkflowDefinition, step: WorkflowStep, scope: WorkflowHostScope, success: Boolean) {
        collectSets(step, scope, success)
    }
    private val artifactPublications = artifactPolicy?.let {
        RunnerArtifactPublications(bundle.executionDirectory, ipc, gate, store, secrets,
            requireNotNull(artifactFingerprinter) { "Declared artifacts require a trusted fingerprint reader" },
            recordEvent = { payload, frameId, before -> eventJournal.append("artifact", payload, frameId, beforePersist = before) }, acceptAck = ::acknowledge)
    }
    override val workflowResolver: WorkflowResolver get() = bundle
    override val approvalHandler: RunnerApprovalHandler = RunnerApprovalHandler(ipc, gate,
        journal = RunnerApprovalJournal(bundle.executionDirectory, ipc.binding, store, secrets), beforeRequest = ::checkBusinessOpen)
    private val credentials = RunnerCredentials(ipc, gate, secrets, onRequestCancelled = { request ->
        emit("usage", JsonObject(request + ("outcome" to JsonPrimitive("REQUEST_CANCELLED"))))
    })
    override val privateDirectory: java.io.File get() = bundle.executionDirectory.resolve(".runner-private").toFile()
    override fun observeCredentialFile(scope: WorkflowHostScope, path: java.nio.file.Path): AutoCloseable {
        checkBusinessOpen(); require(scope.rootExecutionId == binding.executionId) { "Credential file scope mismatch" }
        val root = privateDirectory.toPath().toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        require(candidate.startsWith(root) && candidate.fileName.toString() == "auth.json") { "Credential file outside scoped private directory" }
        val safe = confinedPath(root, root.relativize(candidate).toString())
        return secrets.observe(safe)
    }
    private val live = RunnerLiveCredentials(credentials, gate, preserveRotation = { lease, material ->
        val path = ".runner-private/pending-rotations/${sha256((lease.credentialRef + ":" + lease.version).toByteArray())}.json"
        store.writePrivate(path, canonical(buildJsonObject { put("binding", ipc.binding); put("credentialRef", lease.credentialRef); put("expectedVersion", lease.version); put("material", material) }).toByteArray())
        val cleanup: () -> Unit = { java.nio.file.Files.deleteIfExists(confinedPath(bundle.executionDirectory, path)) }
        cleanup
    }, onReleased = { lease, purpose ->
        emit("usage", buildJsonObject {
            put("outcome", "RELEASED"); put("credentialRef", lease.credentialRef); put("credentialLeaseId", lease.leaseId)
            put("childExecutionPath", childPath(lease.scope.rootExecutionId, lease.scope.executionId)); put("purpose", purpose)
        })
    })
    private val cliPools = java.util.concurrent.ConcurrentHashMap<Triple<String, String, String?>, RunnerSubscriptionPool>()
    private fun poolKey(provider: String, scope: WorkflowHostScope) = Triple(provider, scope.executionId, scope.stepName)
    override fun claudeCredentialsFor(scope: WorkflowHostScope) = cliPools[poolKey("claude", scope)]
    override fun codexCredentialsFor(scope: WorkflowHostScope) = cliPools[poolKey("codex", scope)]
    // No id-keyed rotation callback: ExternalAgentTools rotates through the invocation's own hold on the pool.
    override suspend fun interactiveProcess(scope: WorkflowHostScope, request: com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest): WorkflowInteractiveProcess =
        run { checkBusinessOpen(); requireNotNull(interactiveBroker) { "Interactive broker not assembled" }.open(scope, request) }
    override suspend fun mcpTransport(scope: WorkflowHostScope, name: String, config: com.fartech.agents.commons.McpServerConfig): io.modelcontextprotocol.kotlin.sdk.shared.Transport {
        require(config.url.isNullOrBlank()) { "HTTP MCP needs a distinct client/network adapter" }
        gate.requirePaths(setOf("mcp-stdio"))
        val cwd = java.nio.file.Path.of(config.cwd ?: bundle.executionDirectory.toString()).let { if (it.isAbsolute) it else bundle.executionDirectory.resolve(it) }.normalize()
        require(cwd.startsWith(bundle.executionDirectory)) { "MCP cwd escaped root" }
        val session = interactiveProcess(scope, com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest(
            command = listOf(requireNotNull(config.command)) + config.args.orEmpty(), workingDir = cwd.toFile(),
            env = config.env.orEmpty() + codeEnvironment, timeoutSeconds = 86400, admissionKind = "MCP_STDIO"
        ))
        return RunnerMcpTransport(session)
    }
    fun restoreHostState(state: WorkflowResumeState) { live.restore(state.hostState) }
    private fun verifyAssets() {
        checkBusinessOpen()
        try { bundle.verifyPortableAssets() } catch (_: Exception) {
            gate.invalidate("RUNNER_ASSET_INTEGRITY_FAILED")
            throw RunnerAuthorityLost()
        }
    }
    private val nativeSkills = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, com.fartech.agents.commons.SkillsConfiguration>()
    override fun nativeAgentPrompt(scope: WorkflowHostScope, prompt: String): String {
        require(scope.rootExecutionId == binding.executionId) { "Native skill scope mismatch" }
        gate.check(); verifyAssets()
        val config = nativeSkills[scope.executionId to scope.stepName] ?: return prompt
        val instructions = bundle.nativeSkillInstructions(config)
        return if (instructions.isBlank()) prompt else "$instructions\n\nWorkflow request:\n$prompt"
    }
    private val references = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Run-directory layout of the frozen bundle (FrozenWorkflowBundle replaces every definition's isolation). */
    private val isolation: DirectoryIsolationConfig get() = bundle.rootDefinition.directoryIsolation
    /** One per attempt: the write quota spans every agent of this JVM. */
    private val fileConfinement: RunnerFileConfinement? by lazy {
        if ("tool.file_system.v1" !in capabilities || !RunnerFileConfinement.available()) null
        else RunnerFileConfinement(bundle.executionDirectory,
            Path.of(isolation.resolvePattern("{base_dir}/{user_id}", binding.executionId, userId = binding.ownerId)),
            isolation.resolvePattern("{execution_id}", binding.executionId),
            guard = { checkBusinessOpen(); gate.check() },
            // Bound attachments / saved documents are readable only when the platform admitted the plan against
            // tool.file_system.inputs.v1; an older runtime never sees this capability and reads nothing under inputs/.
            readOnlyInputs = if (RunnerFileConfinement.INPUTS_CAPABILITY in capabilities) bundle.boundInputFiles else emptyMap())
    }
    private fun stepWorkingDirectory(scope: WorkflowHostScope): String =
        java.io.File(isolation.getWorkingDir(scope.executionId, scope.stepName ?: "default", userId = binding.ownerId)).absolutePath
    private fun usesFileTools(parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): Boolean {
        val type = parameters.firstOrNull { it.key == "type" }?.value?.let(RunnerAgentToolPolicy::text)
        val tools = parameters.firstOrNull { it.key == "tool_set" }?.value as? JsonArray
        return type !in setOf("claude_code_agent", "codex_agent") && tools?.any { (it as? JsonPrimitive)?.content in RunnerAgentToolPolicy.RUN_DIRECTORY_TOOL_GROUPS } == true
    }
    internal fun toolFileAccess(scope: WorkflowHostScope): com.fartech.agents.tools.ToolFileAccess {
        checkBusinessOpen(); gate.check()
        require(scope.rootExecutionId == binding.executionId) { "File tool scope mismatch" }
        return RunnerToolFileAccess(requireNotNull(fileConfinement) { "Runner file tools are not available on this host" },
            Path.of(stepWorkingDirectory(scope)))
    }
    override fun fileSystemTools(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<ai.koog.agents.core.tools.reflect.ToolSet> {
        checkBusinessOpen(); gate.check()
        require(scope.rootExecutionId == binding.executionId) { "File tool scope mismatch" }
        val files = requireNotNull(fileConfinement) { "Runner file tools are not available on this host" }
        val workingDirectory = Path.of(stepWorkingDirectory(scope))
        return listOf(RunnerFileTools(files, workingDirectory, redact = secrets::redact) { operation, path, bytes, digest, outcome ->
            monitor(WorkflowMonitorUpdate(scope.executionId, "agent_event", scope.stepName, AgentEvent(
                type = "file_operation", category = "tool", subCategory = "file_system", summary = "file_system $operation $outcome",
                detail = buildJsonObject {
                    put("operation", operation); put("path", path); put("bytes", bytes); put("outcome", outcome)
                    digest?.let { put("sha256", it) }
                }.toString()
            )))
        })
    }
    override fun agentToolSets(scope: WorkflowHostScope, toolNames: Set<String>, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): Map<String, List<ai.koog.agents.core.tools.reflect.ToolSet>> {
        checkBusinessOpen(); gate.check()
        require(scope.rootExecutionId == binding.executionId) { "Tool scope mismatch" }
        require("multi_media" !in toolNames) { "Multimedia requires the canonical frozen tool group" }
        val multimediaTools = if ("multimedia" in toolNames) mapOf("multimedia" to listOf(multimediaTools(scope, parameters))) else emptyMap()
        val sandboxGroups = toolNames.intersect(RunnerSandboxPolicy.GROUPS)
        val sandboxTools: Map<String, List<ai.koog.agents.core.tools.reflect.ToolSet>> = if (sandboxGroups.isEmpty()) emptyMap() else {
            val policy = requireNotNull(sandboxPolicy) { "Sandbox Agent tools were not negotiated" }
            val workflow = requireNotNull(admittedProducers[scope.executionId]) { "Sandbox invocation was not admitted" }
            val invocation = requireNotNull(sandboxInvocations[scope.executionId]) { "Sandbox invocation lineage was not registered" }
            // The real executor's tool hook omits parentExecutionId. Recover it only from the
            // previously verified bindArtifactProducer call, never by trusting caller text.
            require(scope.parentExecutionId == null || scope.parentExecutionId == invocation.parentExecutionId) { "Sandbox tool parent mismatch" }
            val frozenScope = scope.copy(parentExecutionId = invocation.parentExecutionId)
            fun checkSandbox() {
                checkBusinessOpen(); gate.check()
                require(ipc.agentSandboxNegotiated && staticInvocations[scope.executionId] == true && admittedProducers[scope.executionId] == workflow) {
                    "Sandbox requires an admitted static invocation"
                }
                require(ipc.agentSandboxGroups.containsAll(sandboxGroups) && sandboxGroups.all { "tool.$it.v1" in capabilities }) {
                    "Sandbox tool group capability was not negotiated"
                }
                require(sandboxInvocations[scope.executionId] == invocation)
                require(policy.groups(workflow, frozenScope).containsAll(sandboxGroups)) { "Sandbox tools were not explicitly frozen" }
                bundle.verifyPortableAssets()
            }
            checkSandbox()
            val executor = RunnerSandboxProcesses(ipc, gate, toolFileAccess(scope), requireNotNull(scope.stepName),
                scope.executionId, sandboxGroups, liveGuard = ::checkSandbox)
            sandboxGroups.associateWith { group -> listOf(when (group) {
                "shell" -> com.fartech.agents.tools.SandboxShellTools(executor)
                "code_execution" -> com.fartech.agents.tools.SandboxCodeExecutionTools(executor)
                "git" -> com.fartech.agents.tools.SandboxGitTools(executor)
                else -> error("Unsupported sandbox group")
            }) }
        }
        return sandboxTools + multimediaTools + RunnerAdaptedAgentTools.create(toolNames, capabilities, parameters,
            files = { toolFileAccess(scope) }, webHttp = { network.webHttpAccess },
            guard = { checkBusinessOpen(); gate.check() }, redact = secrets::redact)
    }
    override fun claudeBuiltinTools(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): String =
        RunnerAgentToolPolicy.claudeBuiltinTools(parameters.firstOrNull { it.key == "external_agent_claude_allowed_tools" }?.value?.let(RunnerAgentToolPolicy::text))
    private val network = RunnerHttpClients(gate) { checkBusinessOpen(); live.checkAll() }
    val toolHttpAccess get() = network.toolHttpAccess
    override val llmHttpClientFactory get() = network.factory
    private val subAgentBudget = RunnerSubAgentBudget()
    private val subAgentInvocations = java.util.concurrent.ConcurrentHashMap<String, WorkflowHostScope>()
    private val subAgentNetwork = lazy { RunnerHttpClients.singleDispatch(gate) { checkBusinessOpen(); live.checkAll() } }
    private val scopedModelKeys = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, Map<String, RunnerLiveCredentials.Handle>>()
    private class PreparedMultimedia(val scope: WorkflowHostScope, val selection: RunnerMultimediaHostPolicy.Selection,
                                     val handle: RunnerLiveCredentials.Handle, val apiKey: String, val grantedModels: Set<String>)
    private val scopedMultimedia = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, PreparedMultimedia>()
    private val multimediaClients = java.util.concurrent.ConcurrentHashMap.newKeySet<AutoCloseable>()
    private fun multimediaScope(scope: WorkflowHostScope): WorkflowHostScope {
        requireNotNull(multimediaPolicy) { "Multimedia support was not negotiated by the trusted runtime" }
        require(scope.rootExecutionId == binding.executionId && scope.stepName != null && staticInvocations[scope.executionId] == true) {
            "Multimedia scope must be a frozen static step"
        }
        val invocation = requireNotNull(multimediaInvocations[scope.executionId]) { "Multimedia invocation lineage was not registered" }
        require(scope.parentExecutionId == null || scope.parentExecutionId == invocation.parentExecutionId) { "Multimedia parent mismatch" }
        return scope.copy(parentExecutionId = invocation.parentExecutionId)
    }
    private fun multimediaTools(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): ai.koog.agents.core.tools.reflect.ToolSet {
        val policy = requireNotNull(multimediaPolicy) { "Multimedia support was not negotiated by the trusted runtime" }
        val frozenScope = multimediaScope(scope)
        require(localModel(frozenScope) == null) { "Local text-only steps cannot use multimedia tools" }
        val workflow = requireNotNull(admittedProducers[scope.executionId])
        val selection = policy.selection(workflow, frozenScope)
        policy.verifyParameters(selection, parameters)
        val key = scope.executionId to scope.stepName
        val captured = requireNotNull(scopedMultimedia[key]) { "Multimedia scoped lease is missing" }
        require(captured.scope == frozenScope && captured.selection == selection)
        fun checkLease() {
            try {
                checkBusinessOpen(); gate.requirePaths(setOf("jvm-http")); verifyAssets()
                require(ipc.multimediaNegotiated && multimediaScope(scope) == frozenScope && admittedProducers[scope.executionId] == workflow)
                require(scopedMultimedia[key] === captured) { "Multimedia credential lease generation changed" }
                require(policy.selection(workflow, frozenScope) == selection) { "Multimedia frozen selection changed" }
                synchronized(captured.handle) {
                    val material = live.checked(captured.handle, frozenScope, RunnerMultimediaHostPolicy.PURPOSE)
                    require(material.string("apiKey") == captured.apiKey && RunnerMultimediaHostPolicy.grantedModels(material, selection) == captured.grantedModels) {
                        "Multimedia credential lease material changed"
                    }
                }
            } catch (_: IllegalArgumentException) {
                // Authority failure is terminal to the adapter, never a retryable provider failure.
                throw SecurityException("Multimedia scoped authority is no longer valid")
            }
        }
        checkLease()
        val files = toolFileAccess(frozenScope)
        val lease = com.fartech.agents.runner.multimedia.RunnerMultimediaLease(captured.apiKey, captured.grantedModels) {
            checkLease()
            if (WorkflowHostContext.current !== this || WorkflowHostContext.scope != frozenScope) throw SecurityException("Multimedia adapter escaped its parent scope")
        }
        return businessFence.admit {
            checkLease()
            val transport = multimediaHttpFactory?.invoke(::checkLease)?.also { multimediaClients.add(AutoCloseable { it.close() }) }
                ?: RunnerHttpClients(gate, ::checkLease).also { multimediaClients.add(it) }.webHttpAccess
            com.fartech.agents.runner.multimedia.RunnerMultimediaGenerationTools(transport, files, selection.plan(), lease, businessFence = businessFence)
        }
    }
    override fun subAgentTools(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>, runtime: WorkflowSubAgentRuntime): ai.koog.agents.core.tools.reflect.ToolSet {
        require(subAgentToolsEnabled && "tool.sub_agent.v1" in capabilities) { "Hosted sub-agent support was not enabled by the trusted runtime" }
        require(scope.rootExecutionId == binding.executionId && scope.stepName != null && staticInvocations[scope.executionId] == true) { "Sub-agent scope must be a frozen static step" }
        val invocation = requireNotNull(subAgentInvocations[scope.executionId]) { "Sub-agent invocation lineage was not registered" }
        require(scope.parentExecutionId == null || scope.parentExecutionId == invocation.parentExecutionId) { "Sub-agent parent mismatch" }
        val frozenScope = scope.copy(parentExecutionId = invocation.parentExecutionId)
        require(localModel(frozenScope) == null) { "Local text-only steps cannot use sub agents" }
        val leaseScopeKey = scope.executionId to scope.stepName
        val capturedLeases = requireNotNull(scopedModelKeys[leaseScopeKey]) { "Sub-agent parent model leases are missing" }
        fun checkScope() {
            checkBusinessOpen(); gate.check(); live.checkAll(); verifyAssets()
            require(WorkflowHostContext.current === this && WorkflowHostContext.scope == frozenScope) { "Sub-agent adapter escaped its parent scope" }
            require(subAgentInvocations[scope.executionId] == invocation) { "Sub-agent invocation lineage changed" }
            require(scopedModelKeys[leaseScopeKey] === capturedLeases) { "Sub-agent parent model lease generation changed" }
            require(staticInvocations[scope.executionId] == true && admittedProducers.containsKey(scope.executionId))
        }
        return runnerSubAgentTools(parameters, runtime, subAgentBudget, businessFence, subAgentNetwork.value.factory,
            requireLeasedKey = { key ->
                val handle = requireNotNull(capturedLeases[key]) { "Sub-agent key was not leased to this parent scope" }
                require(scopedModelKeys[leaseScopeKey] === capturedLeases && scopedModelKeys[leaseScopeKey]?.get(key) === handle) { "Sub-agent parent model lease generation changed" }
                val leasedScope = handle.latest.get().scope
                require(leasedScope.rootExecutionId == scope.rootExecutionId && leasedScope.executionId == scope.executionId && leasedScope.stepName == scope.stepName)
                require(live.checked(handle, leasedScope, "llm.request").string("apiKey") == key) { "Sub-agent model lease material changed" }
            },
            checkLive = ::checkScope)
    }
    override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> {
        gate.check()
        verifyAssets()
        val multimediaKey = scope.executionId to scope.stepName
        scopedMultimedia.remove(multimediaKey) // Repreparing a scope always ends the previous factory generation.
        var preparedMultimedia: PreparedMultimedia? = null
        val requested = parameters.firstOrNull { it.key == "tool_set" }?.value as? JsonArray
        require(requested?.none { (it as? JsonPrimitive)?.content == "multi_media" } != false) { "Multimedia requires the canonical frozen tool group" }
        val multimediaSelected = requested?.any { (it as? JsonPrimitive)?.content == "multimedia" } == true
        if (multimediaSelected) {
            val policy = requireNotNull(multimediaPolicy) { "Multimedia support was not negotiated by the trusted runtime" }
            val frozenScope = multimediaScope(scope)
            require(localModel(frozenScope) == null) { "Local text-only steps cannot use multimedia tools" }
            val selected = policy.selection(requireNotNull(admittedProducers[scope.executionId]), frozenScope)
            policy.verifyParameters(selected, parameters)
            requireNotNull(fileConfinement) { "Multimedia requires confined Runner file access" }
            validateDirectNetwork(); gate.requirePaths(setOf("jvm-http"))
            val handle = live.acquire(selected.credentialReference, frozenScope, RunnerMultimediaHostPolicy.PURPOSE)
            val material = synchronized(handle) { live.checked(handle, frozenScope, RunnerMultimediaHostPolicy.PURPOSE) }
            val key = material.string("apiKey")
            require(key.isNotBlank() && key.length <= 8192 && key.none { it.code < 32 || it.code == 127 }) { "Multimedia credential material is invalid" }
            preparedMultimedia = PreparedMultimedia(frozenScope, selected, handle, key, RunnerMultimediaHostPolicy.grantedModels(material, selected))
        } else {
            require(parameters.none { it.key == RunnerMultimediaHostPolicy.KEY && it.value != JsonNull && it.value != JsonPrimitive("") }) {
                "Multimedia credentials require an explicitly frozen multimedia tool"
            }
        }
        val type = parameters.firstOrNull { it.key == "type" }?.value?.let(RunnerAgentToolPolicy::text)
        parameters.firstOrNull { it.key == "skills_config" }?.value?.takeUnless { it == JsonNull }?.let {
            nativeSkills[scope.executionId to scope.stepName] = runnerJson.decodeFromJsonElement(com.fartech.agents.commons.SkillsConfiguration.serializer(), it)
        } ?: nativeSkills.remove(scope.executionId to scope.stepName)
        val preparedModelKeys = mutableMapOf<String, RunnerLiveCredentials.Handle>()
        suspend fun lease(value: JsonElement): JsonElement {
            if (value == JsonNull || value == JsonPrimitive("")) return value
            if (value is JsonObject) return JsonObject(value.mapValues { lease(it.value) })
            val ref = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(value.jsonPrimitive.content) ?: error("Model keys must be leased references")
            val handle = live.acquire(value.jsonPrimitive.content, scope, "llm.request")
            val key = live.checked(handle, scope, "llm.request").string("apiKey")
            references[key] = value.jsonPrimitive.content
            preparedModelKeys[key] = handle
            return JsonPrimitive(key)
        }
        for ((provider, parameter) in mapOf("codex" to "external_agent_codex_auth_json", "claude" to "external_agent_claude_oauth_token")) {
            val references = parameters.firstOrNull { it.key == parameter }?.value?.jsonPrimitive?.content?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (references.isNotEmpty()) {
                require(references.all { RunnerAdmission.CREDENTIAL_REFERENCE.matches(it) }) { "Subscription requires scoped references" }
                cliPools[poolKey(provider, scope)] = RunnerSubscriptionPool(live, scope, provider, references, secrets, ::emit)
            }
        }
        suspend fun mcp(value: JsonElement): JsonElement {
            return JsonObject(value.jsonObject.mapValues { (_, config) ->
                val server = config.jsonObject
                RunnerAdmission.requireLeasedProcessInputs(server) // the same rule admission froze; never a plain secret
                val env = server["env"]?.takeUnless { it == JsonNull }?.jsonObject?.mapValues { (_, entry) ->
                    val ref = entry.jsonPrimitive.content
                    if (RunnerAdmission.CREDENTIAL_REFERENCE.matches(ref)) {
                        val handle = live.acquire(ref, scope, "mcp.invoke")
                        val token = live.checked(handle, scope, "mcp.invoke").string("token")
                        references[token] = ref; JsonPrimitive(token)
                    } else entry
                }
                if (env == null) server else JsonObject(server + ("env" to JsonObject(env)))
            })
        }
        // Host-owned file and CLI policy: replaced here, after every YAML override and template.
        val forced = mutableListOf(com.fartech.ftapp2.commonsKt.ConfigurationParameter("sandbox_strict", JsonPrimitive("true")))
        val fileTools = usesFileTools(parameters)
        if (fileTools) {
            requireNotNull(fileConfinement) { "Runner file tools are not available on this host" }
            forced += com.fartech.ftapp2.commonsKt.ConfigurationParameter("working_dir", JsonPrimitive(stepWorkingDirectory(scope)))
            forced += com.fartech.ftapp2.commonsKt.ConfigurationParameter("output_dir", JsonPrimitive(java.io.File(isolation.getOutputDir(scope.executionId, scope.stepName ?: "default", userId = binding.ownerId)).absolutePath))
            forced += com.fartech.ftapp2.commonsKt.ConfigurationParameter("persistence_storage_root", JsonPrimitive(java.io.File(isolation.getPersistenceDir(scope.executionId, scope.stepName ?: "default", userId = binding.ownerId)).absolutePath))
        }
        // Claude steps: the allowlist is the derived file tools only and the permission bypass is dropped
        // (claudeBuiltinTools forces the matching `--tools`). Other agent types never read these keys.
        val allowed = parameters.firstOrNull { it.key == "external_agent_claude_allowed_tools" }?.value?.let(RunnerAgentToolPolicy::text)
        forced += com.fartech.ftapp2.commonsKt.ConfigurationParameter("external_agent_claude_allowed_tools", JsonPrimitive(RunnerAgentToolPolicy.claudeAllowedEntries(allowed).joinToString(",")))
        val extraArgs = parameters.firstOrNull { it.key == "external_agent_claude_extra_args" }?.value?.let(RunnerAgentToolPolicy::text)
        forced += com.fartech.ftapp2.commonsKt.ConfigurationParameter("external_agent_claude_extra_args", JsonPrimitive(
            if (type == "claude_code_agent") RunnerAgentToolPolicy.claudeExtraArgs(extraArgs).joinToString(",") else ""))
        val replaced = forced.map { it.key }.toSet() + (if (fileTools) setOf("workspace_dir") else emptySet())
        val result = parameters.filterNot { it.key in setOf("external_agent_codex_auth_json", "external_agent_claude_oauth_token") }.map { p ->
            if (p.key == RunnerMultimediaHostPolicy.KEY) p // Keep only the reference in Agent parameters, never material.
            else if (p.key == "mcp_servers") com.fartech.ftapp2.commonsKt.ConfigurationParameter(p.key, mcp(p.value))
            else if (p.key.endsWith("_api_key") || p.key == "llm_provider_keys") com.fartech.ftapp2.commonsKt.ConfigurationParameter(p.key, lease(p.value)) else p
        }.filterNot { it.key in setOf("user_id", "disable_skills", "disable_browser_tools", "clawhub_cache_dir") || it.key in replaced }
        scopedModelKeys[scope.executionId to scope.stepName] = preparedModelKeys.toMap()
        preparedMultimedia?.let { prepared -> businessFence.admit {
            gate.check(); live.checked(prepared.handle, prepared.scope, RunnerMultimediaHostPolicy.PURPOSE)
            scopedMultimedia[multimediaKey] = prepared
        } }
        return result + forced + listOf(com.fartech.ftapp2.commonsKt.ConfigurationParameter("user_id", JsonPrimitive(binding.ownerId)),
            com.fartech.ftapp2.commonsKt.ConfigurationParameter("disable_skills", JsonPrimitive("skill.text.v1" !in capabilities)),
            com.fartech.ftapp2.commonsKt.ConfigurationParameter("disable_browser_tools", JsonPrimitive(true)),
            com.fartech.ftapp2.commonsKt.ConfigurationParameter("clawhub_cache_dir", JsonPrimitive(bundle.executionDirectory.resolve("skills").toString())))
    }
    override fun nestedParameters(parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> {
        fun refs(v: JsonElement): JsonElement = when(v) {
            is JsonObject -> JsonObject(v.mapValues { refs(it.value) })
            is JsonPrimitive -> if (v.isString) references[v.content]?.let(::JsonPrimitive) ?: v else v
            else -> v
        }
        return parameters.map { com.fartech.ftapp2.commonsKt.ConfigurationParameter(it.key, refs(it.value)) }
    }
    /** E's business side is terminal before result publication. S alone confirms OS stop and runs POST_STOP. */
    private val finalizationLock = Any()
    fun beginFinalization(): Long = synchronized(finalizationLock) {
        // Do not hold the Host monitor while waiting for admitted recorders. Host producers take host -> fence -> journal.
        if (resourcesClosed.compareAndSet(false, true)) {
            businessFence.close() // returns only after admitted append/persist/enqueue has released this same boundary
            try { eventJournal.seal() }
            finally {
                localModelClients.forEach { it.close() }; localModelClients.clear(); localModelAuthority?.close()
                approvalHandler.close(); artifactSets?.close(); collectionBarrier?.close()
                interactiveBroker?.close(); live.close(); network.close(); credentials.clear()
                if (subAgentNetwork.isInitialized()) subAgentNetwork.value.close()
                scopedModelKeys.clear(); subAgentInvocations.clear()
                multimediaClients.forEach { it.close() }; multimediaClients.clear()
                scopedMultimedia.clear(); multimediaInvocations.clear()
                cliPools.clear(); references.clear(); nativeSkills.clear(); setProducerBudgets.clear()
            }
        }
        eventJournal.seal()
    }
    fun close() { beginFinalization() }
    internal var beforeEventEnqueueForTest: (JsonObject) -> Unit = {}
    private val eventJournal = RunnerEventJournal(ipc.binding, secrets,
        write = { path, bytes -> store.writePrivate(path, bytes); Unit }, enqueue = { frame ->
            beforeEventEnqueueForTest(frame)
            if (frame.obj("payload").string("type") == "artifact") gate.check()
            ipc.sendFrame(frame); Unit
        },
        failed = { gate.invalidate("RUNNER_EVENT_JOURNAL_FAILED") }, businessFence = businessFence,
        outboundSlot = { append -> ipc.withOutboundSlot(append) })
    // The journal's own durable copy proves an acknowledged event, so S's ACK releases its retained slot.
    init { ipc.retainEventsDurably { seq -> store.readPrivate(RunnerEventJournal.eventPath(seq)) } }
    override val codeEnvironment: Map<String, String> = mapOf(
        "WF_RUNNER_ID" to binding.runnerId, "WF_ROOT_EXECUTION_ID" to binding.executionId,
        "WF_OWNER_ID" to binding.ownerId, "WF_ACTOR_USER_ID" to binding.actorUserId,
        "WF_BILLING_OWNER_ID" to binding.billingOwnerId
    )
    override suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope) {
        checkBusinessOpen()
        require(scope.rootExecutionId == binding.executionId && bundle.contains(workflow)) { "Workflow escaped frozen host context" }
        val definitions = if (scope.executionId == binding.executionId) bundle.allDefinitions else listOf(workflow)
        definitions.forEach { definition -> checkRoutes(RunnerAdmission(bundle, capabilities, localModelPolicy = localModelPolicy).inspect(definition)) }
    }
    override fun bindArtifactProducer(workflow: WorkflowDefinition, scope: WorkflowHostScope, staticInvocation: Boolean) {
        if (artifactPolicy == null && collectionPolicy == null && setPolicy == null && localModelPolicy == null && sandboxPolicy == null && multimediaPolicy == null && !subAgentToolsEnabled) return
        require(scope.rootExecutionId == binding.executionId && bundle.contains(workflow)) { "Artifact producer outside frozen invocation" }
        childPath(binding.executionId, scope.executionId)
        val lineage = staticInvocation && (scope.parentExecutionId == null || staticInvocations[scope.parentExecutionId] == true)
        if (sandboxPolicy != null && staticInvocation) {
            sandboxPolicy.verifyInvocation(workflow, scope)
            require(lineage) { "Sandbox parent invocation was not admitted as static" }
        }
        if (multimediaPolicy != null && staticInvocation) {
            multimediaPolicy.verifyInvocation(workflow, scope)
            require(lineage) { "Multimedia parent invocation was not admitted as static" }
        }
        require(staticInvocations.putIfAbsent(scope.executionId, lineage)?.let { it == lineage } != false) { "Invocation lineage cannot change" }
        val identity = bundle.identityOf(workflow)
        require(admittedProducers.putIfAbsent(scope.executionId, identity)?.let { it == identity } != false) { "Producer definition changed within invocation" }
        if (subAgentToolsEnabled && lineage) {
            val invocation = scope.copy(stepName = null, occurrence = null)
            require(subAgentInvocations.putIfAbsent(scope.executionId, invocation)?.let { it == invocation } != false) { "Sub-agent invocation lineage changed" }
        }
        if (multimediaPolicy != null && lineage) {
            val invocation = scope.copy(stepName = null, occurrence = null)
            require(multimediaInvocations.putIfAbsent(scope.executionId, invocation)?.let { it == invocation } != false) { "Multimedia invocation lineage changed" }
        }
        if (sandboxPolicy != null && lineage) {
            val invocation = scope.copy(stepName = null, occurrence = null)
            require(sandboxInvocations.putIfAbsent(scope.executionId, invocation)?.let { it == invocation } != false) { "Sandbox invocation lineage changed" }
        }
    }
    private fun checkRoutes(required: Set<String>) {
        if ("model.http.v1" in required) { validateDirectNetwork(); gate.requirePaths(setOf("jvm-http")) }
        if (required.any { it in setOf("agent.codex.v1", "agent.claude.v1") }) { validateDirectNetwork(); gate.requirePaths(setOf("agent-cli")) }
        if ("mcp.stdio.v1" in required) gate.requirePaths(setOf("mcp-stdio"))
        if ("step.script.v1" in required || required.any { it in RunnerAgentToolPolicy.SUBPROCESS_CAPABILITIES }) gate.requirePaths(setOf("subprocess"))
    }
    override suspend fun beforeOperation(scope: WorkflowHostScope, operation: String) {
        require(scope.rootExecutionId == binding.executionId) { "Operation escaped root scope" }; gate.check(); verifyAssets()
    }
    override fun scriptContents(path: String): String { checkBusinessOpen(); return bundle.script(path) }
    override fun dynamicWorkflow(path: String, scope: WorkflowHostScope): WorkflowDefinition {
        checkBusinessOpen(); gate.check(); require(scope.rootExecutionId == binding.executionId) { "Dynamic scope mismatch" }
        return bundle.dynamic(path).also { checkRoutes(RunnerAdmission(bundle, capabilities).inspect(it)) }
    }
    override fun userInteraction(scope: WorkflowHostScope): UserInteractionHandler { checkBusinessOpen(); return RunnerUserInteraction(ipc, gate, scope, secrets) }
    override suspend fun collectDeclaredArtifacts(workflow: WorkflowDefinition, step: WorkflowStep, scope: WorkflowHostScope, candidates: List<WorkflowArtifactCandidate>) {
        if (step.code == null) collectSets(step, scope, true)
        if (collectionPolicy != null) {
            try {
                gate.check(); collectionPolicy.verify()
                val workflowId = requireNotNull(admittedProducers[scope.executionId])
                if (staticInvocations[scope.executionId] == false) return // Known interpreter dynamic invocation: no static declarations apply.
                val declared = collectionPolicy.declarationsFor(workflowId, scope.executionId, step.step)
                if (declared.isNotEmpty()) {
                    val start = requireNotNull((if (step.code != null) codeProducerStarts else producerStarts)[scope.executionId to step.step])
                    if (step.code != null) requireNotNull(collectionBarrier).requireCompleted(declared, start)
                    else {
                        val barrier = requireNotNull(collectionBarrier)
                        announceArtifactReady(RunnerArtifactReadyBoundary(collectionPolicy.hash, workflowId, scope, start, declared))
                        barrier.awaitCompleted(declared, start)
                    }
                }
                return // S exclusively reads/snapshots; never invoke the old injected fingerprint path.
            } catch (failure: kotlinx.coroutines.CancellationException) {
                gate.invalidate("RUNNER_DECLARED_ARTIFACTS_UNCONFIRMED"); throw failure
            } catch (_: Exception) { gate.invalidate("RUNNER_DECLARED_ARTIFACTS_UNCONFIRMED"); throw RunnerAuthorityLost() }
        }
        val policy = artifactPolicy ?: return
        try {
        require(scope.rootExecutionId == binding.executionId && scope.stepName == step.step) { "Artifact boundary scope mismatch" }
        gate.check(); verifyAssets()
        val workflowId = requireNotNull(admittedProducers[scope.executionId]) { "Artifact producer was not admitted" }
        val declared = policy.declared(workflowId, scope, candidates.toList())
        require(declared.size <= 64 && declared.map { it.logicalPath }.distinct().size == declared.size) { "Artifact declaration count or identity invalid" }
        if (declared.isEmpty()) return
        val key = scope.executionId to step.step
        val start = requireNotNull((if (step.code != null) codeProducerStarts else producerStarts)[key]) { "Original durable producer start missing" }
        declared.forEach { requireNotNull(artifactPublications).publish(workflowId, scope, start, it) }
        } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (_: Exception) { gate.invalidate("RUNNER_ARTIFACT_PUBLICATION_UNCONFIRMED"); throw RunnerAuthorityLost() }
    }
    /** The whole partition set is persisted/enqueued before completion waiting, through one sequencer. */
    @Synchronized internal fun announceArtifactReady(boundary: RunnerArtifactReadyBoundary) {
        gate.check()
        val policy = requireNotNull(collectionPolicy); policy.verify()
        require(boundary.policySha256 == policy.hash && boundary.scope.rootExecutionId == binding.executionId)
        val key = boundary.scope.executionId to requireNotNull(boundary.scope.stepName)
        require(staticInvocations[key.first] == true && admittedProducers[key.first] == boundary.producerWorkflowId)
        require(!policy.isCode(boundary.producerWorkflowId, key.second)) { "Ready notification is Agent-only" }
        require(activeProducers[key]?.eventId == boundary.producerStartEventId) { "Ready occurrence is not active" }
        val declared = policy.declarationsFor(boundary.producerWorkflowId, key.first, key.second)
        require(declared.isNotEmpty() && boundary.declarationIds == declared)
        fun estimatedFrame(payload: JsonObject) = buildJsonObject {
            put("protocolVersion", 1); put("frameId", "0".repeat(36)); put("type", "event"); put("binding", ipc.binding)
            put("payload", buildJsonObject { put("eventId", "0".repeat(36)); put("eventSeq", Long.MAX_VALUE); put("attemptId", binding.attemptId); put("resumeEpoch", binding.resumeEpoch); put("type", "step"); put("payload", payload) })
        }
        val parts = mutableListOf<JsonObject>()
        fun partition(ids: List<String>) {
            val payload = boundary.payload(ids); RunnerArtifactBoundaryProtocol.validate(payload)
            if (canonical(estimatedFrame(payload)).toByteArray().size <= 65536) parts += payload
            else {
                require(ids.size > 1) { "Ready event cannot fit the server event limit" }
                val half = ids.size / 2; partition(ids.take(half)); partition(ids.drop(half))
            }
        }
        declared.chunked(64).forEach(::partition)
        val context = buildJsonObject { put("binding", ipc.binding); put("boundary", boundary.payload()) }
        require(!secrets.containsSecret(context)) { "Ready context contains secret material" }
        val id = sha256(canonical(context).toByteArray())
        val relative = ".runner-state/artifact-boundaries/$id.json"
        val path = confinedPath(bundle.executionDirectory, relative)
        if (java.nio.file.Files.exists(path)) {
            val retained = runnerJson.parseToJsonElement(java.nio.file.Files.readString(path)).jsonObject
            require(retained.obj("context") == context)
            val frames = retained.array("frames").map { it.jsonObject }
            require(frames.size == parts.size) { "Partial ready intent needs reconciliation; do not allocate replacement events" }
            frames.zip(parts).forEach { (frame, payload) ->
                RunnerProtocol().validate(frame, "ipcFrame"); require(frame.obj("binding") == ipc.binding && frame.string("type") == "event")
                require(frame.obj("payload").string("type") == "step" && frame.obj("payload").obj("payload") == payload)
                payload.array("declarationIds").forEach { id -> agentReadySequences[boundary.producerStartEventId to id.jsonPrimitive.content] = frame.obj("payload").long("eventSeq") }
                gate.check(); ipc.sendFrame(frame)
            }
            return
        }
        val frames = mutableListOf<JsonObject>()
        fun retain() { store.writePrivate(relative, canonical(buildJsonObject { put("context", context); put("frames", JsonArray(frames)) }).toByteArray()) }
        retain()
        parts.forEach { payload ->
            gate.check()
            require(secrets.redactContent(payload) == payload) { "Ready metadata cannot be redacted or changed" }
            val frame = eventJournal.append("step", payload, beforePersist = { original ->
                require(canonical(original).toByteArray().size <= 65536)
                frames += original; retain()
            })
            payload.array("declarationIds").forEach { id -> agentReadySequences[boundary.producerStartEventId to id.jsonPrimitive.content] = frame.obj("payload").long("eventSeq") }
        }
    }
    override fun admitLoop(scope: WorkflowHostScope, step: WorkflowStep, items: Int, concurrency: Int) {
        checkBusinessOpen(); require(scope.rootExecutionId == binding.executionId && scope.stepName == step.step) { "Loop scope mismatch" }
        RunnerLoopPolicy.admitRuntime(step.step, items, concurrency)
    }
    /** The item's (or evaluator round's) own leases, CLI pools and skill config end with it; the loop step's end
     * (prefix release) stays the fallback. */
    override fun occurrenceEnded(scope: WorkflowHostScope) {
        require(scope.rootExecutionId == binding.executionId) { "Occurrence escaped root scope" }
        releaseOwned(scope.executionId, "${requireNotNull(scope.stepName)}:${requireNotNull(scope.occurrence)}")
    }
    /** A classifier call or group-chat turn ended: its own lease, CLI pool and skill config end with it, so a long
     * chat holds one lease at a time instead of one per turn until the step ends. Never throws: a release that
     * cannot be recorded (journal or business fence closed) invalidates this attempt instead, and the step's end
     * still releases by prefix. */
    override fun derivedScopeEnded(scope: WorkflowHostScope) {
        if (scope.rootExecutionId != binding.executionId) { gate.invalidate("RUNNER_SCOPE_ESCAPED"); return }
        val name = scope.stepName ?: return
        runCatching { releaseOwned(scope.executionId, name) }.onFailure { gate.invalidate("RUNNER_RELEASE_FAILED") }
    }
    private fun releaseOwned(executionId: String, name: String) {
        fun owned(step: String?) = step == name || step?.startsWith("$name:") == true
        cliPools.keys.removeIf { it.second == executionId && owned(it.third) }
        nativeSkills.keys.removeIf { it.first == executionId && owned(it.second) }
        scopedMultimedia.keys.removeIf { it.first == executionId && owned(it.second) }
        live.release(executionId, name)
    }
    @Synchronized override fun monitor(update: WorkflowMonitorUpdate) {
        if (update.type in setOf("step_completed", "step_cancelled")) update.stepName?.let { live.release(update.executionId, it) }
        val payload = buildJsonObject {
            put("executionId", update.executionId); put("kind", update.type)
            update.stepName?.let { put("stepName", it) }; update.occurrence?.let { put("occurrence", it) }; update.success?.let { put("success", it) }
            update.output?.let { put("output", secrets.redact(it)) }; update.error?.let { put("error", secrets.redact(it)) }
            update.event?.let { e -> put("event", buildJsonObject {
                put("type", e.type); put("category", e.category); put("summary", secrets.redact(e.summary))
                e.detail?.let { put("detail", secrets.redact(it)) }
                e.inputTokens?.let { put("inputTokens", it) }; e.outputTokens?.let { put("outputTokens", it) }
                e.totalTokens?.let { put("totalTokens", it) }; e.cacheReadTokens?.let { put("cacheReadTokens", it) }
                e.cacheCreationTokens?.let { put("cacheCreationTokens", it) }
            }) }
        }
        val kind = if (update.event?.let { !it.isDisplayOnlyTokenEvent() && (it.inputTokens != null || it.outputTokens != null || it.totalTokens != null) } == true) "usage" else "step"
        val frame = eventJournal.append(kind, payload)
        // Loop occurrences share their step's name and are never artifact producers (RunnerLoopPolicy): they must
        // neither open nor close the step's producer occurrence.
        if (update.occurrence == null) update.stepName?.let { step ->
            val key = update.executionId to step
            val occurrence = ActiveProducer(frame.obj("payload").string("eventId"), frame.obj("payload").long("eventSeq"))
            if (update.type == "step_started") { producerStarts[key] = occurrence.eventId; codeProducerStarts.remove(key); activeProducers[key] = occurrence; activeCodeProducers.remove(key) }
            if (update.event?.type == "code_step_started") { codeProducerStarts[key] = occurrence.eventId; activeCodeProducers[key] = occurrence }
            if (update.event?.type == "code_step_completed") activeCodeProducers.remove(key)
            if (update.type in setOf("step_completed", "step_cancelled")) {
                producerStarts[key]?.let { original -> agentReadySequences.keys.removeIf { it.first == original } }
                producerStarts.remove(key); codeProducerStarts.remove(key); activeProducers.remove(key); activeCodeProducers.remove(key) }
        }
    }
    fun emit(kind: String, payload: JsonObject) { eventJournal.append(kind, payload) }

    fun acknowledge(payload: JsonObject) {
        eventJournal.acknowledge(payload)
        ipc.reclaimAcknowledged(eventJournal.acknowledged())
    }
    fun finalEventSeq(): Long = eventJournal.lastSequence()
    private val checkpointOrder = kotlinx.coroutines.sync.Mutex()
    override suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint) = checkpointOf { checkpoint }
    /**
     * One savepoint at a time, announced in capture order. The state is built under the Host monitor, which every
     * step event (monitor) holds while it appends, so the announced eventSeq is exactly the last event the
     * captured state already reflects; a concurrent step cannot slip an event between the two.
     */
    override suspend fun checkpointOf(build: () -> WorkflowHostCheckpoint?) = checkpointOrder.withLock {
        checkBusinessOpen()
        val (checkpoint, eventSeq) = synchronized(this) {
            val built = build() ?: return@withLock
            built.copy(state = built.state.copy(hostState = live.state())) to finalEventSeq()
        }
        ipc.send("checkpoint", store.save(checkpoint, eventSeq))
        Unit
    }
    private val outputs = RunnerOutputs(ipc, gate, secrets)
    override val outputPublisher: WorkflowOutputPublisher get() = outputs
    override val outputResolver: WorkflowOutputResolver get() = outputs
    private val tokens = RunnerCallbackTokens(ipc, gate, secrets)
    override fun prepareCodeStepEnvironment(scope: WorkflowHostScope, timeoutSeconds: Long): Map<String, String> {
        require(codeControlMode == WorkflowCodeControlMode.SUPERVISOR_PER_CALL && scope.rootExecutionId == binding.executionId && !scope.stepName.isNullOrBlank()) { "Per-call code scope unavailable" }
        ipc.requireProcessTimeout(timeoutSeconds, "CODE_STEP")
        childPath(binding.executionId, scope.executionId)
        gate.check(); verifyAssets()
        return emptyMap() // S owns peer-provider injection after registered process authorization.
    }
    override val callbackToken: ((Long) -> String)? = if (codeControlMode == WorkflowCodeControlMode.LEGACY_STEP_TOKEN && callbackEnabled && "control.callback.v1" in capabilities) ({ budget -> tokens.mint(WorkflowHostScope(binding.executionId, binding.executionId), budget) }) else null
    override fun callbackTokenFor(scope: WorkflowHostScope, budget: Long): String {
        checkBusinessOpen()
        require(codeControlMode == WorkflowCodeControlMode.LEGACY_STEP_TOKEN && callbackEnabled && "control.callback.v1" in capabilities && scope.rootExecutionId == binding.executionId) { "Callback scope/capability not authorized" }
        return tokens.mint(scope, budget)
    }
}
