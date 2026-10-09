package com.fartech.agents.runner

import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import kotlinx.coroutines.runBlocking
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
    private val setPolicy: RunnerArtifactSetPolicy? = null
) : WorkflowHost {
    init {
        require(bundle.taskBinding == ipc.binding) { "Bundle host binding differs from IPC authority" }
        val pairs = mapOf("ownerId" to binding.ownerId, "actorUserId" to binding.actorUserId, "executionId" to binding.executionId,
            "runnerId" to binding.runnerId, "runnerInstanceId" to binding.runnerInstanceId, "attemptId" to binding.attemptId,
            "definitionHash" to binding.definitionHash, "runtimeHash" to binding.runtimeHash)
        require(pairs.all { (key, value) -> ipc.binding.string(key) == value } && ipc.binding.long("resumeEpoch") == binding.resumeEpoch) { "Host identity differs from IPC authority" }
    }
    private val staticInvocations = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val admittedProducers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val businessFence = ipc.businessFence
    private val resourcesClosed = java.util.concurrent.atomic.AtomicBoolean()
    private fun checkBusinessOpen() = businessFence.check()
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
    private val credentials = RunnerCredentials(ipc, gate, secrets)
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
    })
    private val cliPools = java.util.concurrent.ConcurrentHashMap<String, RunnerSubscriptionPool>()
    private fun poolKey(provider: String, scope: WorkflowHostScope) = "$provider:${scope.executionId}:${scope.stepName}"
    override fun claudeCredentialsFor(scope: WorkflowHostScope) = cliPools[poolKey("claude", scope)]
    override fun codexCredentialsFor(scope: WorkflowHostScope) = cliPools[poolKey("codex", scope)]
    override fun codexAuthRotationFor(scope: WorkflowHostScope): ((String?, String) -> Unit)? = cliPools[poolKey("codex", scope)]?.let { pool -> { id, json -> pool.rotated(id, json) } }
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
    private val network = RunnerHttpClients(gate) { checkBusinessOpen(); live.checkAll() }
    override val llmHttpClientFactory get() = network.factory
    override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> {
        gate.check()
        verifyAssets()
        parameters.firstOrNull { it.key == "skills_config" }?.value?.takeUnless { it == JsonNull }?.let {
            nativeSkills[scope.executionId to scope.stepName] = runnerJson.decodeFromJsonElement(com.fartech.agents.commons.SkillsConfiguration.serializer(), it)
        } ?: nativeSkills.remove(scope.executionId to scope.stepName)
        suspend fun lease(value: JsonElement): JsonElement {
            if (value == JsonNull || value == JsonPrimitive("")) return value
            if (value is JsonObject) return JsonObject(value.mapValues { lease(it.value) })
            val ref = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(value.jsonPrimitive.content) ?: error("Model keys must be leased references")
            val handle = live.acquire(value.jsonPrimitive.content, scope, "llm.request")
            val key = live.checked(handle, scope, "llm.request").string("apiKey")
            references[key] = value.jsonPrimitive.content
            return JsonPrimitive(key)
        }
        for ((provider, parameter) in mapOf("codex" to "external_agent_codex_auth_json", "claude" to "external_agent_claude_oauth_token")) {
            val reference = parameters.firstOrNull { it.key == parameter }?.value?.jsonPrimitive?.content
            if (!reference.isNullOrBlank()) {
                require(RunnerAdmission.CREDENTIAL_REFERENCE.matches(reference)) { "Subscription requires scoped reference" }
                cliPools[poolKey(provider, scope)] = RunnerSubscriptionPool(live, scope, provider, reference, secrets, ::emit)
            }
        }
        suspend fun mcp(value: JsonElement): JsonElement {
            return JsonObject(value.jsonObject.mapValues { (_, config) ->
                val server = config.jsonObject
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
        val result = parameters.filterNot { it.key in setOf("external_agent_codex_auth_json", "external_agent_claude_oauth_token") }.map { p ->
            if (p.key == "mcp_servers") com.fartech.ftapp2.commonsKt.ConfigurationParameter(p.key, mcp(p.value))
            else if (p.key.endsWith("_api_key") || p.key == "llm_provider_keys") com.fartech.ftapp2.commonsKt.ConfigurationParameter(p.key, lease(p.value)) else p
        }.filterNot { it.key in setOf("user_id", "disable_skills", "disable_browser_tools", "clawhub_cache_dir") }
        return result + listOf(com.fartech.ftapp2.commonsKt.ConfigurationParameter("user_id", JsonPrimitive(binding.ownerId)),
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
                approvalHandler.close(); artifactSets?.close(); collectionBarrier?.close()
                interactiveBroker?.close(); live.close(); network.close(); credentials.clear()
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
        failed = { gate.invalidate("RUNNER_EVENT_JOURNAL_FAILED") }, businessFence = businessFence)
    override val codeEnvironment: Map<String, String> = mapOf(
        "WF_RUNNER_ID" to binding.runnerId, "WF_ROOT_EXECUTION_ID" to binding.executionId,
        "WF_OWNER_ID" to binding.ownerId, "WF_ACTOR_USER_ID" to binding.actorUserId,
        "WF_BILLING_OWNER_ID" to binding.billingOwnerId
    )
    override suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope) {
        checkBusinessOpen()
        require(scope.rootExecutionId == binding.executionId && bundle.contains(workflow)) { "Workflow escaped frozen host context" }
        val definitions = if (scope.executionId == binding.executionId) bundle.allDefinitions else listOf(workflow)
        definitions.forEach { definition -> checkRoutes(RunnerAdmission(bundle, capabilities).inspect(definition)) }
    }
    override fun bindArtifactProducer(workflow: WorkflowDefinition, scope: WorkflowHostScope, staticInvocation: Boolean) {
        if (artifactPolicy == null && collectionPolicy == null && setPolicy == null) return
        require(scope.rootExecutionId == binding.executionId && bundle.contains(workflow)) { "Artifact producer outside frozen invocation" }
        childPath(binding.executionId, scope.executionId)
        val lineage = staticInvocation && (scope.parentExecutionId == null || staticInvocations[scope.parentExecutionId] == true)
        require(staticInvocations.putIfAbsent(scope.executionId, lineage)?.let { it == lineage } != false) { "Invocation lineage cannot change" }
        val identity = bundle.identityOf(workflow)
        require(admittedProducers.putIfAbsent(scope.executionId, identity)?.let { it == identity } != false) { "Producer definition changed within invocation" }
    }
    private fun checkRoutes(required: Set<String>) {
        if ("model.http.v1" in required) { validateDirectNetwork(); gate.requirePaths(setOf("jvm-http")) }
        if (required.any { it in setOf("agent.codex.v1", "agent.claude.v1") }) { validateDirectNetwork(); gate.requirePaths(setOf("agent-cli")) }
        if ("mcp.stdio.v1" in required) gate.requirePaths(setOf("mcp-stdio"))
        if ("step.script.v1" in required) gate.requirePaths(setOf("subprocess"))
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
            require(secrets.redact(payload) == payload) { "Ready metadata cannot be redacted or changed" }
            val frame = eventJournal.append("step", payload, beforePersist = { original ->
                require(canonical(original).toByteArray().size <= 65536)
                frames += original; retain()
            })
            payload.array("declarationIds").forEach { id -> agentReadySequences[boundary.producerStartEventId to id.jsonPrimitive.content] = frame.obj("payload").long("eventSeq") }
        }
    }
    @Synchronized override fun monitor(update: WorkflowMonitorUpdate) {
        if (update.type in setOf("step_completed", "step_cancelled")) update.stepName?.let { live.release(update.executionId, it) }
        val payload = buildJsonObject {
            put("executionId", update.executionId); put("kind", update.type)
            update.stepName?.let { put("stepName", it) }; update.success?.let { put("success", it) }
            update.output?.let { put("output", secrets.redact(it)) }; update.error?.let { put("error", secrets.redact(it)) }
            update.event?.let { e -> put("event", buildJsonObject {
                put("type", e.type); put("category", e.category); put("summary", secrets.redact(e.summary))
                e.detail?.let { put("detail", secrets.redact(it)) }
                e.inputTokens?.let { put("inputTokens", it) }; e.outputTokens?.let { put("outputTokens", it) }
                e.totalTokens?.let { put("totalTokens", it) }; e.cacheReadTokens?.let { put("cacheReadTokens", it) }
                e.cacheCreationTokens?.let { put("cacheCreationTokens", it) }
            }) }
        }
        val kind = if (update.event?.let { !it.isDisplayOnlyTokenEvent() && (it.inputTokens != null || it.totalTokens != null) } == true) "usage" else "step"
        val frame = eventJournal.append(kind, payload)
        update.stepName?.let { step ->
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

    fun acknowledge(payload: JsonObject) = eventJournal.acknowledge(payload)
    fun finalEventSeq(): Long = eventJournal.lastSequence()
    override suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint) {
        checkBusinessOpen()
        ipc.send("checkpoint", store.save(checkpoint.copy(state = checkpoint.state.copy(hostState = live.state())), finalEventSeq()))
    }
    private val outputs = RunnerOutputs(ipc, gate, secrets)
    override val outputPublisher: WorkflowOutputPublisher get() = outputs
    override val outputResolver: WorkflowOutputResolver get() = outputs
    private val tokens = RunnerCallbackTokens(ipc, gate, secrets)
    override fun prepareCodeStepEnvironment(scope: WorkflowHostScope, timeoutSeconds: Long): Map<String, String> {
        require(codeControlMode == WorkflowCodeControlMode.SUPERVISOR_PER_CALL && scope.rootExecutionId == binding.executionId && !scope.stepName.isNullOrBlank()) { "Per-call code scope unavailable" }
        require(timeoutSeconds in 1..86400) { "Unsupported original code timeout" }
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
