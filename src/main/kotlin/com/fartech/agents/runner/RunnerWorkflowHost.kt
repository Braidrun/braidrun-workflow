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
    override val subprocessExecutor: SubprocessExecutor,
    private val store: RunnerCheckpointStore,
    private val secrets: RunnerSecrets,
    private val validateDirectNetwork: () -> Unit = { RunnerHttpClients.requireDirect() }
) : WorkflowHost {
    init {
        require(bundle.taskBinding == ipc.binding) { "Bundle host binding differs from IPC authority" }
        val pairs = mapOf("ownerId" to binding.ownerId, "actorUserId" to binding.actorUserId, "executionId" to binding.executionId,
            "runnerId" to binding.runnerId, "runnerInstanceId" to binding.runnerInstanceId, "attemptId" to binding.attemptId,
            "definitionHash" to binding.definitionHash, "runtimeHash" to binding.runtimeHash)
        require(pairs.all { (key, value) -> ipc.binding.string(key) == value } && ipc.binding.long("resumeEpoch") == binding.resumeEpoch) { "Host identity differs from IPC authority" }
    }
    override val workflowResolver: WorkflowResolver get() = bundle
    override val approvalHandler: ApprovalHandler = RunnerApprovalHandler(ipc, gate)
    private val credentials = RunnerCredentials(ipc, gate, secrets)
    override val privateDirectory: java.io.File get() = bundle.executionDirectory.resolve(".runner-private").toFile()
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
    fun restoreHostState(state: WorkflowResumeState) { live.restore(state.hostState) }
    private val references = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val network = RunnerHttpClients(gate) { live.checkAll() }
    override val llmHttpClientFactory get() = network.factory
    override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> {
        gate.check()
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
        val result = parameters.filterNot { it.key in setOf("external_agent_codex_auth_json", "external_agent_claude_oauth_token") }.map { p ->
            if (p.key.endsWith("_api_key") || p.key == "llm_provider_keys") com.fartech.ftapp2.commonsKt.ConfigurationParameter(p.key, lease(p.value)) else p
        }.filterNot { it.key in setOf("user_id", "disable_skills", "disable_browser_tools") }
        return result + listOf(com.fartech.ftapp2.commonsKt.ConfigurationParameter("user_id", JsonPrimitive(binding.ownerId)),
            com.fartech.ftapp2.commonsKt.ConfigurationParameter("disable_skills", JsonPrimitive(true)),
            com.fartech.ftapp2.commonsKt.ConfigurationParameter("disable_browser_tools", JsonPrimitive(true)))
    }
    override fun nestedParameters(parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> {
        fun refs(v: JsonElement): JsonElement = when(v) {
            is JsonObject -> JsonObject(v.mapValues { refs(it.value) })
            is JsonPrimitive -> if (v.isString) references[v.content]?.let(::JsonPrimitive) ?: v else v
            else -> v
        }
        return parameters.map { com.fartech.ftapp2.commonsKt.ConfigurationParameter(it.key, refs(it.value)) }
    }
    fun close() { live.close(); network.close(); credentials.clear(); cliPools.clear(); references.clear() }
    private var sequence = 0L
    @Volatile private var acknowledged = 0L
    override val codeEnvironment: Map<String, String> = mapOf(
        "WF_RUNNER_ID" to binding.runnerId, "WF_ROOT_EXECUTION_ID" to binding.executionId,
        "WF_OWNER_ID" to binding.ownerId, "WF_ACTOR_USER_ID" to binding.actorUserId,
        "WF_BILLING_OWNER_ID" to binding.billingOwnerId
    )
    override suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope) {
        require(scope.rootExecutionId == binding.executionId && bundle.contains(workflow)) { "Workflow escaped frozen host context" }
        val definitions = if (scope.executionId == binding.executionId) bundle.allDefinitions else listOf(workflow)
        definitions.forEach { definition -> checkRoutes(RunnerAdmission(bundle, capabilities).inspect(definition)) }
    }
    private fun checkRoutes(required: Set<String>) {
        if ("model.http.v1" in required) { validateDirectNetwork(); gate.requirePaths(setOf("jvm-http")) }
        if (required.any { it in setOf("agent.codex.v1", "agent.claude.v1") }) { validateDirectNetwork(); gate.requirePaths(setOf("agent-cli")) }
        if ("step.script.v1" in required) gate.requirePaths(setOf("subprocess"))
    }
    override suspend fun beforeOperation(scope: WorkflowHostScope, operation: String) {
        require(scope.rootExecutionId == binding.executionId) { "Operation escaped root scope" }; gate.check()
    }
    override fun scriptContents(path: String): String = bundle.script(path)
    override fun dynamicWorkflow(path: String, scope: WorkflowHostScope): WorkflowDefinition {
        gate.check(); require(scope.rootExecutionId == binding.executionId) { "Dynamic scope mismatch" }
        return bundle.dynamic(path).also { checkRoutes(RunnerAdmission(bundle, capabilities).inspect(it)) }
    }
    override fun userInteraction(scope: WorkflowHostScope): UserInteractionHandler = RunnerUserInteraction(ipc, gate, scope, secrets)
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
        emit(kind, payload)
    }
    @Synchronized fun emit(kind: String, payload: JsonObject) {
        val eventSequence = sequence + 1
        val id = UUID.randomUUID().toString()
        val event = buildJsonObject {
            put("eventId", id); put("eventSeq", eventSequence)
            put("attemptId", binding.attemptId); put("resumeEpoch", binding.resumeEpoch); put("type", kind)
            put("payload", secrets.redact(payload))
        }
        val frameId = UUID.randomUUID().toString()
        val frame = buildJsonObject {
            put("protocolVersion", 1); put("frameId", frameId); put("type", "event"); put("binding", ipc.binding); put("payload", event)
        }
        store.writePrivate(".runner-state/events/$eventSequence.json", canonical(frame).toByteArray(Charsets.UTF_8))
        sequence = eventSequence
        ipc.send("event", event, frameId)
    }
    @Synchronized fun acknowledge(payload: JsonObject) {
        val seq = payload.long("ackSeq")
        require(seq <= sequence) { "ACK exceeds emitted contiguous sequence" }
        if (seq > acknowledged) acknowledged = seq
    }
    @Synchronized fun finalEventSeq(): Long = sequence
    override suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint) {
        ipc.send("checkpoint", store.save(checkpoint.copy(state = checkpoint.state.copy(hostState = live.state())), finalEventSeq()))
    }
    private val outputs = RunnerOutputs(ipc, gate, secrets)
    override val outputPublisher: WorkflowOutputPublisher get() = outputs
    override val outputResolver: WorkflowOutputResolver get() = outputs
    private val tokens = RunnerCallbackTokens(ipc, gate, secrets)
    override val callbackToken: ((Long) -> String)? = if ("control.callback.v1" in capabilities) ({ budget -> tokens.mint(WorkflowHostScope(binding.executionId, binding.executionId), budget) }) else null
    override fun callbackTokenFor(scope: WorkflowHostScope, budget: Long): String = tokens.mint(scope, budget)
}
