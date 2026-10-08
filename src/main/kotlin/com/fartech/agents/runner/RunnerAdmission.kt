package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Cancellation-like: error handlers/retries must not continue after authority is lost. */
class RunnerAuthorityLost : CancellationException("Runner authority or environment is no longer valid")

/** Environment evidence can be supplied only by the trusted supervisor, never workflow YAML.
 * Monotonic deadline avoids extending validity after a wall-clock adjustment. Full lease
 * supervision remains a supervisor duty until the pinned IPC contract carries lease updates.
 */
class RunnerEnvironmentGate(
    private val binding: JsonObject,
    private val expectedPaths: Set<String>,
    private val now: () -> Instant = Instant::now,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private data class Evidence(val expires: Long, val profileHash: String, val paths: Set<String>, val installedAt: Long, val checkId: String, val evidenceHash: String)
    private val evidence = AtomicReference<Evidence?>()
    private data class SeenEvidence(val hash: String, val firstReplyTo: String?)
    private val seenEvidence = LinkedHashMap<String, SeenEvidence>()
    private val interrupted = AtomicReference<String?>()
    private data class Interaction(val baselineCheck: String?, val proof: AtomicReference<Evidence?> = AtomicReference())
    private val interactions = java.util.concurrent.ConcurrentHashMap<String, Interaction>()
    fun beginInteraction(frameId: String) { check(); require(interactions.putIfAbsent(frameId, Interaction(evidence.get()?.checkId)) == null) }
    fun endInteraction(frameId: String) { interactions.remove(frameId) }
    suspend fun freshForInteraction(frameId: String) {
        val interaction = interactions[frameId] ?: throw RunnerAuthorityLost()
        kotlinx.coroutines.withTimeout(35_000) {
            while (interaction.proof.get() == null) { check(); kotlinx.coroutines.delay(20) }
        }
        val proof = interaction.proof.get() ?: throw RunnerAuthorityLost()
        if (proof.checkId == interaction.baselineCheck || nanoTime() >= proof.expires) throw RunnerAuthorityLost()
        check()
    }
    private val invalidationListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onInvalidated(listener: () -> Unit): AutoCloseable {
        invalidationListeners.add(listener)
        if (interrupted.get() != null) listener()
        return AutoCloseable { invalidationListeners.remove(listener) }
    }
    private data class Lease(val id: String, val expires: Long)
    private val lease = AtomicReference<Lease?>()
    private val leaseRequired = java.util.concurrent.atomic.AtomicBoolean(false)

    fun installLease(value: JsonObject) {
        RunnerProtocol().validate(value, "lease")
        if (interrupted.get() != null) throw RunnerAuthorityLost()
        val previous = lease.get()
        val id = value.string("leaseId")
        if (previous != null && (previous.id != id || nanoTime() >= previous.expires)) throw RunnerAuthorityLost()
        val duration = java.time.Duration.between(Instant.parse(value.string("serverTime")), Instant.parse(value.string("leaseExpiresAt"))).toNanos()
        val localRemaining = java.time.Duration.between(now(), Instant.parse(value.string("leaseExpiresAt"))).toNanos()
        if (duration <= 0 || duration > 60_000_000_000L || localRemaining <= 0) throw RunnerAuthorityLost()
        lease.set(Lease(id, nanoTime() + minOf(duration, localRemaining))); leaseRequired.set(true)
    }

    @Synchronized fun install(value: JsonObject, replyTo: String? = null) {
        RunnerProtocol().validate(value, "environment")
        if (interrupted.get() != null || value.obj("binding") != binding || value.string("decision") != "ALLOW") throw RunnerAuthorityLost()
        val checked = Instant.parse(value.string("checkedAt")); val valid = Instant.parse(value.string("validUntil"))
        val instant = now()
        if (checked > instant || valid <= instant || valid <= checked || java.time.Duration.between(checked, valid).toNanos() > 60_000_000_000L) throw RunnerAuthorityLost()
        val routes = value.array("egress").map { it.jsonObject }
        if (routes.isEmpty() || !routes.map { it.string("pathId") }.toSet().containsAll(expectedPaths)) throw RunnerAuthorityLost()
        val profile = value.string("networkProfileHash")
        val previous = evidence.get()
        if (previous?.profileHash != null && previous.profileHash != profile) throw RunnerAuthorityLost()
        val checkId = value.string("environmentCheckId")
        val payloadHash = sha256(canonical(value).toByteArray())
        seenEvidence[checkId]?.let { seen ->
            if (seen.hash != payloadHash) throw RunnerAuthorityLost()
            // IDs retain their first provenance for the entire attempt, even after newer probes.
            // An unchanged retry never renews time or creates/reassigns interaction proof.
            return
        }
        if (seenEvidence.size >= 65536) throw RunnerAuthorityLost()
        if (routes.any { it.string("countryCode") == "CN" || it.string("networkProfileHash") != profile ||
                Instant.parse(it.string("observedAt")) < checked || Instant.parse(it.string("observedAt")) > instant }) throw RunnerAuthorityLost()
        val conflict = routes.groupBy { it.string("pathId") to it.string("addressFamily") }
            .values.any { group -> group.map { it.string("countryCode") }.distinct().size > 1 }
        if (conflict) throw RunnerAuthorityLost()
        val accepted = Evidence(nanoTime() + java.time.Duration.between(instant, valid).toNanos(), profile, routes.map { it.string("pathId") }.toSet(), nanoTime(), value.string("environmentCheckId"), sha256(canonical(value).toByteArray()))
        seenEvidence[checkId] = SeenEvidence(payloadHash, replyTo)
        evidence.set(accepted)
        replyTo?.let { frame -> interactions[frame]?.let { interaction ->
            if (accepted.checkId != interaction.baselineCheck) interaction.proof.compareAndSet(null, accepted)
        } }
    }
    fun requirePaths(paths: Set<String>) { check(); if (evidence.get()?.paths?.containsAll(paths) != true) throw RunnerAuthorityLost() }
    fun invalidate(reason: String) {
        evidence.set(null)
        if (interrupted.compareAndSet(null, reason)) invalidationListeners.forEach { listener -> runCatching { listener() } }
    }
    fun check() {
        if (interrupted.get() != null || evidence.get()?.let { nanoTime() >= it.expires } != false ||
            (leaseRequired.get() && lease.get()?.let { nanoTime() >= it.expires } != false)) throw RunnerAuthorityLost()
    }
}

/** Recursive closure inspection. Unknown tools/features are refused, including dormant branches.
 * The enabled capability set is supplied by the assembled trusted adapters, never by the workflow.
 */
class RunnerAdmission(
    private val resolver: WorkflowResolver,
    private val capabilities: Set<String>,
    private val approvedTools: Set<String> = setOf("exit", "user_interaction", "workflow", "skill_tools"),
    private val maximumDepth: Int = WorkflowExecutor.DEFAULT_MAX_SUB_WORKFLOW_DEPTH
) {
    fun inspect(root: WorkflowDefinition): Set<String> {
        val required = linkedSetOf("workflow.full.v1")
        val visiting = HashSet<String>(); val visited = HashSet<String>()
        fun requireCapability(capability: String) {
            required.add(capability)
            require(capability in capabilities) { "Unsupported Runner capability: $capability" }
        }
        fun inspectAgent(agent: AgentDefinition) {
            requireCapability("step.agent.v1")
            require(agent.agentRef == null) { "Unfrozen agent reference" }
            val preset = agent.preset?.let { AgentPresetRegistry.get(it) ?: error("Unknown agent preset") }
            val effective = agent.resolveParameters()
            val type = effective["type"]?.jsonPrimitive?.content ?: agent.type
            if (type in setOf("claude_code_agent", "codex_agent")) {
                val provider = if (type == "codex_agent") "codex" else "claude"
                requireCapability("agent.$provider.v1")
                require(effective["external_agent_${provider}_auth_mode"]?.jsonPrimitive?.content == "subscription") { "Runner CLI requires explicit scoped subscription mode" }
                val key = if (provider == "codex") "external_agent_codex_auth_json" else "external_agent_claude_oauth_token"
                require(effective[key]?.jsonPrimitive?.content?.let { CREDENTIAL_REFERENCE.matches(it) } == true) { "Runner CLI requires a credential reference" }
                for (field in listOf("external_agent_codex_auth_file", "external_agent_codex_home_dir", "external_agent_claude_config_dir")) {
                    require(effective[field]?.jsonPrimitive?.content.isNullOrBlank()) { "Runner CLI cannot use unscoped local account state" }
                }
            } else requireCapability("model.http.v1")
            val tools = effective["tool_set"]?.let { v ->
                require(v is JsonArray) { "Ambiguous tool set" }; v.map { it.jsonPrimitive.content }
            } ?: agent.tools
            tools.forEach { tool ->
                require(tool in approvedTools) { "Unsupported Runner tool: $tool" }
                requireCapability("tool.$tool.v1")
            }
            val json = runnerJson.encodeToJsonElement(AgentDefinition.serializer(), agent)
            fun services(node: JsonElement) {
                if (node is JsonObject) node.forEach { (key, value) ->
                    if (key == "skills_config" && value != JsonNull) {
                        val config = runnerJson.decodeFromJsonElement(com.fartech.agents.commons.SkillsConfiguration.serializer(), value)
                        if (config.enabled) requireCapability("skill.text.v1")
                        require(!config.scanStandardPaths && config.additionalSkillPaths.isEmpty() && !config.autoSearchDownload && !config.autoUpgrade && !config.hookScriptExecutionEnabled) { "Skill side effects/ambient discovery need separate supervised admission" }
                    }
                    if (key == "mcp_servers" && value != JsonNull) {
                        requireCapability("service.mcp_servers.v1")
                        val servers = value.jsonObject
                        servers.values.forEach { server ->
                            val config = runnerJson.decodeFromJsonElement(com.fartech.agents.commons.McpServerConfig.serializer(), server)
                            if (config.enabled) {
                                require(config.url.isNullOrBlank()) { "HTTP MCP adapter not yet accepted" }
                                requireCapability("mcp.stdio.v1")
                            }
                        }
                    }
                    if (key in setOf("redis_client_url", "langfuse_url", "mongo_connection_string", "sql_jdbc_url") && value != JsonNull) {
                        requireCapability("service.$key.v1")
                    }
                    if ((key.lowercase() in setOf("token", "api_key", "password", "authorization", "authjson") || key.lowercase().endsWith("_api_key") || key.lowercase().endsWith("_secret") || key.lowercase().endsWith("_password") || key.lowercase().endsWith("_token") || key.lowercase().endsWith("_auth_json") || key == "llm_provider_keys") && value != JsonNull && value.toString() !in setOf("{}", "\"\"")) {
                        val refs = if (value is JsonObject) value.values else listOf(value)
                        require(refs.all { it is JsonPrimitive && it.isString && CREDENTIAL_REFERENCE.matches(it.content) }) { "Secrets must be leased at runtime" }
                    }
                    if (key == "im_progress_notify_enabled" && value == JsonPrimitive(true)) requireCapability("service.im-progress.v1")
                    services(value)
                } else if (node is JsonArray) node.forEach(::services)
            }
            services(json); services(JsonObject(effective))
        }
        lateinit var walk: (WorkflowDefinition, Int) -> Unit
        fun child(config: SubWorkflowConfig, depth: Int) {
            requireCapability("step.subWorkflow.v1")
            val resolved = resolver.resolve(config.toReference())
            require(resolved.identity !in visiting) { "Runner definition cycle" }
            if (resolved.identity !in visited) {
                visiting.add(resolved.identity); walk(resolved.definition, depth + 1)
                visiting.remove(resolved.identity); visited.add(resolved.identity)
            }
        }
        walk = { workflow, depth ->
            require(depth <= maximumDepth) { "Runner definition closure exceeds maximum depth" }
            if (workflow.concurrency.enabled) requireCapability("workflow.concurrent.v1")
            if (workflow.knowledgeBase?.enabled == true) requireCapability("workflow.knowledgeBase.v1")
            workflow.globalAgent?.let { global ->
                requireCapability("workflow.globalAgent.v1"); inspectAgent(global.agent)
            }
            workflow.agents.values.forEach(::inspectAgent)
            workflow.workflow.forEach { step ->
                if (step.retry != null) require(step.idempotent) { "Runner retry requires an explicitly idempotent step" }
                if (step.code != null) requireCapability("step.script.v1")
                if (step.classifier?.jev != null || step.repeatUntil?.jev != null) requireCapability("decision.jev.v1")
                if (step.classifier != null) requireCapability("step.classifier.v1")
                if (step.groupChat != null) requireCapability("step.groupChat.v1")
                step.agentBased?.let { requireCapability("step.agentBased.v1"); inspectAgent(it.orchestrator) }
                if (step.manualApproval != null) requireCapability("interaction.approval.v1")
                if (step.publishOutputs.isNotEmpty()) requireCapability("output.publish.v1")
                if (step.workflowOutputRead != null) {
                    requireCapability("output.read.v1")
                }
                step.subWorkflow?.let { child(it, depth) }
                step.stateMachine?.let { stateMachine ->
                    requireCapability("step.stateMachine.v1")
                    stateMachine.states.values.forEach { state ->
                        state.stepConfig?.let { nested ->
                            if (nested.code != null) requireCapability("step.script.v1")
                            if (nested.classifier != null) requireCapability("step.classifier.v1")
                            if (nested.groupChat != null) requireCapability("step.groupChat.v1")
                            nested.agentBased?.let { requireCapability("step.agentBased.v1"); inspectAgent(it.orchestrator) }
                            nested.subWorkflow?.let { child(it, depth) }
                        }
                    }
                }
            }
        }
        WorkflowParser.validateWorkflow(root, resolver)
        requireCapability("workflow.full.v1"); walk(root, 0)
        return required
    }
    companion object { val CREDENTIAL_REFERENCE = Regex("credential:([A-Za-z0-9_.-]+)@([0-9]+)") }
}
