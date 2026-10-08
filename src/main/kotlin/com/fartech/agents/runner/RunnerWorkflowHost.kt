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
    private val secrets: RunnerSecrets
) : WorkflowHost {
    override val workflowResolver: WorkflowResolver get() = bundle
    override val approvalHandler: ApprovalHandler = RunnerApprovalHandler(ipc, gate)
    private val credentials = RunnerCredentials(ipc, gate, secrets)
    private val llmLeases = java.util.concurrent.CopyOnWriteArrayList<RunnerCredentialLease>()
    private val references = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val network = RunnerHttpClients(gate) { llmLeases.forEach { credentials.material(it, it.scope, "llm.request") } }
    override val llmHttpClientFactory get() = network.factory
    override suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> {
        gate.check()
        suspend fun lease(value: JsonElement): JsonElement {
            if (value == JsonNull || value == JsonPrimitive("")) return value
            if (value is JsonObject) return JsonObject(value.mapValues { lease(it.value) })
            val ref = RunnerAdmission.CREDENTIAL_REFERENCE.matchEntire(value.jsonPrimitive.content) ?: error("Model keys must be leased references")
            val granted = credentials.acquire(ref.groupValues[1], ref.groupValues[2].toLong(), scope, "llm.request")
            llmLeases.add(granted)
            val key = credentials.material(granted, scope, "llm.request").string("apiKey")
            references[key] = value.jsonPrimitive.content
            return JsonPrimitive(key)
        }
        val result = parameters.map { p ->
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
    fun close() { network.close(); credentials.clear() }
    private var sequence = 0L
    @Volatile private var acknowledged = 0L
    override val codeEnvironment: Map<String, String> = mapOf(
        "WF_RUNNER_ID" to binding.runnerId, "WF_ROOT_EXECUTION_ID" to binding.executionId,
        "WF_OWNER_ID" to binding.ownerId, "WF_ACTOR_USER_ID" to binding.actorUserId,
        "WF_BILLING_OWNER_ID" to binding.billingOwnerId
    )
    override suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope) {
        require(scope.rootExecutionId == binding.executionId && bundle.contains(workflow)) { "Workflow escaped frozen host context" }
        RunnerAdmission(bundle, capabilities).inspect(workflow)
    }
    override suspend fun beforeOperation(scope: WorkflowHostScope, operation: String) {
        require(scope.rootExecutionId == binding.executionId) { "Operation escaped root scope" }; gate.check()
    }
    override fun scriptContents(path: String): String = bundle.script(path)
    override fun dynamicWorkflow(path: String, scope: WorkflowHostScope): WorkflowDefinition {
        gate.check(); require(scope.rootExecutionId == binding.executionId) { "Dynamic scope mismatch" }
        return bundle.dynamic(path).also { RunnerAdmission(bundle, capabilities).inspect(it) }
    }
    override fun userInteraction(scope: WorkflowHostScope): UserInteractionHandler = RunnerUserInteraction(ipc, gate, scope, secrets)
    @Synchronized override fun monitor(update: WorkflowMonitorUpdate) {
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
        ipc.send("checkpoint", store.save(checkpoint, finalEventSeq()))
    }
    private val outputs = RunnerOutputs(ipc, gate, secrets)
    override val outputPublisher: WorkflowOutputPublisher get() = outputs
    override val outputResolver: WorkflowOutputResolver get() = outputs
    private val tokens = RunnerCallbackTokens(ipc, gate, secrets)
    override val callbackToken: ((Long) -> String)? = if ("control.callback.v1" in capabilities) ({ budget -> tokens.mint(WorkflowHostScope(binding.executionId, binding.executionId), budget) }) else null
    override fun callbackTokenFor(scope: WorkflowHostScope, budget: Long): String = tokens.mint(scope, budget)
}
