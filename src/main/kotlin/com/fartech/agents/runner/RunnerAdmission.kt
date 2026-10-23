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
    private val knownCountries = java.util.Locale.getISOCountries().toSet()
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
        if (routes.any { (it.string("countryCode") == "CN" || it.string("countryCode") !in knownCountries) || it.string("networkProfileHash") != profile ||
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
    private val approvedTools: Set<String> = setOf("exit", "user_interaction", "workflow", "skill_tools", "file_system"),
    private val maximumDepth: Int = WorkflowExecutor.DEFAULT_MAX_SUB_WORKFLOW_DEPTH,
    /** The only directory-isolation base a frozen bundle produces; null requires a relative base. */
    private val isolationRoot: java.nio.file.Path? = (resolver as? FrozenWorkflowBundle)?.executionDirectory?.resolve(".workflow-runs")
) {
    fun inspect(root: WorkflowDefinition): Set<String> {
        val required = linkedSetOf("workflow.full.v1")
        val visiting = HashSet<String>(); val visited = HashSet<String>()
        fun requireCapability(capability: String) {
            required.add(capability)
            require(capability in capabilities) { "Unsupported Runner capability: $capability" }
        }
        /** Returns whether the agent uses the confined `file_system` tools. */
        fun inspectAgent(agent: AgentDefinition): Boolean {
            requireCapability("step.agent.v1")
            require(agent.agentRef == null) { "Unfrozen agent reference" }
            val preset = agent.preset?.let { AgentPresetRegistry.get(it) ?: error("Unknown agent preset") }
            val effective = agent.resolveParameters()
            val type = effective["type"]?.jsonPrimitive?.content ?: agent.type
            val cli = type in setOf("claude_code_agent", "codex_agent")
            if (cli) {
                val provider = if (type == "codex_agent") "codex" else "claude"
                requireCapability("agent.$provider.v1")
                require(effective["external_agent_${provider}_auth_mode"]?.jsonPrimitive?.content == "subscription") { "Runner CLI requires explicit scoped subscription mode" }
                val key = if (provider == "codex") "external_agent_codex_auth_json" else "external_agent_claude_oauth_token"
                require(effective[key]?.jsonPrimitive?.content?.let { value -> value.split(',').all { CREDENTIAL_REFERENCE.matches(it.trim()) } } == true) { "Runner CLI requires credential references" }
                for (field in listOf("external_agent_codex_auth_file", "external_agent_codex_home_dir", "external_agent_claude_config_dir")) {
                    require(effective[field]?.jsonPrimitive?.content.isNullOrBlank()) { "Runner CLI cannot use unscoped local account state" }
                }
                // The host registers only file tools (`--tools`) for Claude and drops the permission bypass.
                if (provider == "claude") RunnerAgentToolPolicy.claudeExtraArgs(RunnerAgentToolPolicy.text(effective["external_agent_claude_extra_args"]))
            } else requireCapability("model.http.v1")
            val tools = effective["tool_set"]?.let { v ->
                require(v is JsonArray) { "Ambiguous tool set" }; v.map { it.jsonPrimitive.content }
            } ?: agent.tools
            // A CLI agent never loads the engine tool groups: its own tools are restricted by the host instead.
            if (!cli) tools.forEach { tool ->
                require(tool in approvedTools) { "Unsupported Runner tool: $tool" }
                requireCapability("tool.$tool.v1")
            }
            val fileTools = !cli && "file_system" in tools
            if (fileTools) {
                for (key in RunnerAgentToolPolicy.DIRECTORY_PARAMETERS) {
                    require(RunnerAgentToolPolicy.text(effective[key]).isNullOrBlank()) { "Runner file tools use the host's run directories; '$key' cannot be set" }
                }
            }
            val json = runnerJson.encodeToJsonElement(AgentDefinition.serializer(), agent)
            fun services(node: JsonElement) {
                if (node is JsonObject) node.forEach { (key, value) ->
                    if (key == "skills_config" && value != JsonNull) {
                        val config = runnerJson.decodeFromJsonElement(com.fartech.agents.commons.SkillsConfiguration.serializer(), value)
                        // A disabled skills subsystem loads no skills or hooks, so its serialized discovery defaults are inert.
                        if (config.enabled) {
                            requireCapability("skill.text.v1")
                            require(!config.scanStandardPaths && config.additionalSkillPaths.isEmpty() && !config.autoSearchDownload && !config.autoUpgrade && !config.hookScriptExecutionEnabled) { "Skill side effects/ambient discovery need separate supervised admission" }
                        }
                    }
                    if (key == "mcp_servers" && value != JsonNull) {
                        requireCapability("service.mcp_servers.v1")
                        val servers = value.jsonObject
                        servers.values.forEach { server ->
                            requireLeasedProcessInputs(server)
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
                    if ((isSecretKey(key, value) || key == "llm_provider_keys") && value != JsonNull && value.toString() !in setOf("{}", "\"\"")) {
                        val refs = if (value is JsonObject) value.values else listOf(value)
                        val poolReference = key in setOf("external_agent_codex_auth_json", "external_agent_claude_oauth_token")
                        require(refs.all { it is JsonPrimitive && it.isString &&
                            (if (poolReference) it.content.split(',').all { ref -> CREDENTIAL_REFERENCE.matches(ref.trim()) }
                             else CREDENTIAL_REFERENCE.matches(it.content)) }) { "Secrets must be leased at runtime" }
                    }
                    if (key == "im_progress_notify_enabled" && value == JsonPrimitive(true)) requireCapability("service.im-progress.v1")
                    services(value)
                } else if (node is JsonArray) node.forEach(::services)
            }
            services(json); services(JsonObject(effective))
            return fileTools
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
            if (workflow.concurrency.enabled) {
                requireCapability(CONCURRENT_CAPABILITY)
                // Unlimited fan-out would multiply model, process and IPC load past what one Runner JVM admits.
                require(workflow.concurrency.maxConcurrency in 1..MAX_CONCURRENCY) { "Runner concurrency requires max_concurrency between 1 and $MAX_CONCURRENCY" }
                // A step's end retires its credential leases by step name and by its derived "<step>:" scopes.
                require(workflow.workflow.none { ':' in it.step }) { "Concurrent Runner step names cannot contain ':'" }
            }
            if (workflow.knowledgeBase?.enabled == true) requireCapability("workflow.knowledgeBase.v1")
            var fileTools = false
            workflow.globalAgent?.let { global ->
                requireCapability("workflow.globalAgent.v1"); fileTools = inspectAgent(global.agent) || fileTools
            }
            workflow.agents.values.forEach { fileTools = inspectAgent(it) || fileTools }
            workflow.workflow.forEach { step ->
                if (step.retry != null) require(step.idempotent) { "Runner retry requires an explicitly idempotent step" }
                if (step.iterateOver != null) { requireCapability(RunnerLoopPolicy.CAPABILITY); RunnerLoopPolicy.admit(workflow, step) }
                if (step.repeatUntil != null) { requireCapability(RunnerLoopPolicy.REPEAT_CAPABILITY); RunnerLoopPolicy.admitRepeatUntil(workflow, step) }
                if (step.code != null) requireCapability("step.script.v1")
                step.parallel?.let { parallel ->
                    requireCapability(CONCURRENT_CAPABILITY)
                    require((parallel.maxParallel ?: parallel.tasks.size) in 1..MAX_CONCURRENCY) { "Runner parallel tasks require max_parallel between 1 and $MAX_CONCURRENCY" }
                }
                if (step.classifier?.jev != null || step.repeatUntil?.jev != null) requireCapability("decision.jev.v1")
                if (step.classifier != null) { requireCapability("step.classifier.v1"); requireDerivedScopeFree(workflow, step.step, "classifier") }
                if (step.groupChat != null) { requireCapability("step.groupChat.v1"); requireDerivedScopeFree(workflow, step.step, "group chat") }
                step.agentBased?.let {
                    requireCapability("step.agentBased.v1"); requireAgentBasedBounds(workflow, step.step, it)
                    fileTools = inspectAgent(it.orchestrator) || fileTools
                }
                if (step.manualApproval != null) requireCapability("interaction.approval.v1")
                if (step.publishOutputs.isNotEmpty()) requireCapability("output.publish.v1")
                if (step.workflowOutputRead != null) {
                    requireCapability("output.read.v1")
                }
                step.subWorkflow?.let { child(it, depth) }
                step.stateMachine?.let { stateMachine ->
                    requireCapability("step.stateMachine.v1")
                    stateMachine.states.forEach { (stateName, state) ->
                        state.stepConfig?.let { nested ->
                            if (nested.code != null) requireCapability("step.script.v1")
                            // Same Jev rule as a root classifier: a nested one must not pass on step.classifier.v1 alone.
                            if (nested.classifier?.jev != null) requireCapability("decision.jev.v1")
                            if (nested.classifier != null) { requireCapability("step.classifier.v1"); requireDerivedScopeFree(workflow, "${step.step}.$stateName", "classifier") }
                            if (nested.groupChat != null) { requireCapability("step.groupChat.v1"); requireDerivedScopeFree(workflow, "${step.step}.$stateName", "group chat") }
                            nested.agentBased?.let {
                                requireCapability("step.agentBased.v1"); requireAgentBasedBounds(workflow, "${step.step}.$stateName", it)
                                fileTools = inspectAgent(it.orchestrator) || fileTools
                            }
                            nested.subWorkflow?.let { child(it, depth) }
                        }
                    }
                }
            }
            if (fileTools) RunnerAgentToolPolicy.requireConfinedIsolation(workflow.directoryIsolation, isolationRoot)
        }
        WorkflowParser.validateWorkflow(root, resolver)
        requireCapability("workflow.full.v1"); walk(root, 0)
        return required
    }
    companion object {
        /**
         * A classifier or group chat runs its agent calls, and the host leases and releases their credentials, under
         * derived names `<step>:...`; a real step under that prefix would share their scope. Same guard as loops
         * (RunnerLoopPolicy). [name] is the running name: the root step, or `<outer>.<state>` inside a state machine.
         */
        internal fun requireDerivedScopeFree(workflow: WorkflowDefinition, name: String, kind: String) {
            require(workflow.workflow.none { it.step.startsWith("$name:") }) { "Runner $kind step '$name': another step name starts with '$name:'" }
        }
        /** Delegations one Runner agent_based step may make (the engine default is 20; each holds its own lease while it runs). */
        const val MAX_AGENT_BASED_STEPS = 50
        /** agent_based on a Runner: the orchestrator's delegations run, and are released, as "<step>:..."; their number is bounded. */
        internal fun requireAgentBasedBounds(workflow: WorkflowDefinition, name: String, config: AgentBasedConfig) {
            requireDerivedScopeFree(workflow, name, "agent_based")
            require(config.maxSteps in 1..MAX_AGENT_BASED_STEPS) { "Runner agent_based step '$name': max_steps ${config.maxSteps} outside 1..$MAX_AGENT_BASED_STEPS" }
        }
        const val CONCURRENT_CAPABILITY = "workflow.concurrent.v1"
        /** Upper bound for workflow max_concurrency and step-level max_parallel on a Runner. */
        const val MAX_CONCURRENCY = 8
        val CREDENTIAL_REFERENCE = Regex("credential:([A-Za-z0-9_.-]+)@([0-9]+)")
        /** `x-api-key`, `apiKey` and `API_KEY` are the same field: compare without case, `-` or `_`. */
        private fun normalizedKey(key: String) = key.lowercase(java.util.Locale.ROOT).filter { it != '-' && it != '_' }
        private val SECRET_KEY_SUFFIXES = listOf("apikey", "secret", "secretkey", "password", "passwd", "passphrase", "token",
            "authjson", "privatekey", "accesskey", "signingkey", "clientkey", "cookie", "bearer", "credential", "credentials")
        private val SECRET_KEYS = setOf("authorization", "proxyauthorization")
        /** Model sizing, never a credential; `max_token` is how workflow LLM configs spell the context length. */
        private val TOKEN_COUNT_KEYS = setOf("maxtoken", "maxtokens")
        /** Name-based detection for scalar fields anywhere in an agent definition. */
        fun isSecretKey(key: String, value: JsonElement? = null): Boolean {
            val normalized = normalizedKey(key)
            if (normalized in TOKEN_COUNT_KEYS && value is JsonPrimitive && !value.isString) return false
            return normalized in SECRET_KEYS || SECRET_KEY_SUFFIXES.any { normalized.endsWith(it) }
        }
        /** The only plain values a whole-map process injection (MCP `env`/`headers`) may carry. */
        private val NONSECRET_PROCESS_KEYS = setOf("path", "home", "lang", "lcall", "lcctype", "tz", "term", "nodeenv",
            "pythonunbuffered", "pythonioencoding", "pythondontwritebytecode", "debug", "loglevel", "nocolor", "forcecolor", "ci",
            "accept", "acceptlanguage", "acceptencoding", "contenttype", "useragent")
        /**
         * Literal token shapes, found anywhere a word starts (`user:ghp_...`, `"1 sk-..."`). Each needs a
         * token-length body, so locale or package names such as `sk_SK.UTF-8` are not tokens.
         */
        private val LITERAL_SECRET = Regex("(?<![A-Za-z0-9])(?:(?:sk-|sk_|rk_|pk_live_|tvly-|ghp_|gho_|ghs_|ghu_|github_pat_|glpat-|xox[abpr]-|ya29\\.)[A-Za-z0-9._-]{16,}" +
            "|AIza[A-Za-z0-9_-]{30,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})")
        private val LOCALE_KEYS = setOf("lang", "lcall", "lcctype")
        private val LOCALE = Regex("^(?:[A-Za-z]{2,3}(?:_[A-Za-z]{2})?(?:\\.[A-Za-z0-9-]+)?(?:@[A-Za-z0-9]+)?|C(?:\\.UTF-8)?|POSIX)$")
        /** Bare `key`-named flags that conventionally carry a secret; `--public-key`, `--sort-key` do not. */
        private val SECRET_KEY_FLAGS = setOf("key", "licensekey", "masterkey", "encryptionkey", "servicekey", "subscriptionkey", "authkey")
        private val PATH_VALUE = Regex("^(?:/|~/|\\./|\\.\\./)\\S*$")
        private val ASSIGNED_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")
        private fun words(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        /** `Authorization: Bearer x`, `USER_AGENT="bot Bearer x"`: an auth scheme followed by a credential word. */
        private fun carriesAuthScheme(words: List<String>) = words.dropLast(1).withIndex().any { (index, word) ->
            word.trim('"', '\'', ',', ';').lowercase(java.util.Locale.ROOT) in setOf("bearer", "basic") && !words[index + 1].startsWith("-")
        }
        private fun plainValueAllowed(key: String, text: String): Boolean = when (key) {
            in LOCALE_KEYS -> LOCALE.matches(text)
            in NONSECRET_PROCESS_KEYS -> !LITERAL_SECRET.containsMatchIn(text) && !carriesAuthScheme(words(text))
            else -> false
        }
        /**
         * MCP `env`/`headers` are injected into the child as a whole, so a key-name deny list cannot
         * see a secret behind an arbitrary name (`TAVILY_KEY`, `X-Custom`). Every value must be a leased
         * reference unless its key is an explicitly nonsecret setting, and even then it must look like
         * that setting (a locale for LANG/LC_*) and carry no literal token shape or auth scheme.
         */
        fun leasedProcessValue(key: String, value: JsonElement): Boolean =
            value is JsonPrimitive && value.isString && (CREDENTIAL_REFERENCE.matches(value.content) || plainValueAllowed(normalizedKey(key), value.content))
        private fun secretFlag(name: String): Boolean = isSecretKey(name) || normalizedKey(name) in SECRET_KEY_FLAGS
        /** A file path (`--private-key /etc/mcp/key.pem`) or a numeric token count (`--max-token 4096`) is not a secret. */
        private fun flagValueAllowed(name: String, value: String): Boolean =
            (PATH_VALUE.matches(value) && !LITERAL_SECRET.containsMatchIn(value)) ||
                (normalizedKey(name) in TOKEN_COUNT_KEYS && value.isNotEmpty() && value.all { it.isDigit() })
        /**
         * Misconfiguration defence, NOT an isolation boundary. The admitted MCP command is arbitrary code
         * and can read or print anything it is given; and no syntactic check can prove a command line free
         * of secrets. This layer intercepts the common shapes of a definition planting a raw secret where
         * Runner never leases it nor registers it for redaction (argv is also visible to `ps`), and gives no
         * guarantee beyond them:
         * - `env`/`headers` values: leased reference, or an allowlisted nonsecret setting (above).
         * - Across command and args words: a secret-named flag (`--api-key x`, `--token=x`), a secret-named
         *   `NAME=value` assignment (`env TAVILY_API_KEY=x cmd`, `sh -c "API_TOKEN=x ..."`) or header
         *   (`X-Api-Key: x`) may carry only a file path or, for token counts, a number; never a value, not
         *   even a reference (argv is never leased): pass secrets through a leased `env` entry.
         * - An auth scheme followed by a credential (`-H "Authorization: Bearer x"`).
         * - Literal token shapes anywhere in command, args or cwd.
         * Not intercepted: short flags whose meaning depends on the program (`-k x`, `-t x`), and secrets
         * without a recognisable name or shape.
         */
        fun requireLeasedProcessInputs(server: JsonElement) {
            if (server !is JsonObject) return
            for (field in listOf("env", "headers")) {
                val entries = server[field]?.takeUnless { it == JsonNull } ?: continue
                require(entries is JsonObject && entries.all { (key, value) -> leasedProcessValue(key, value) }) { "Secrets must be leased at runtime" }
            }
            val args = server["args"]?.takeUnless { it == JsonNull }?.let { value ->
                require(value is JsonArray && value.all { it is JsonPrimitive && it.isString }) { "Invalid MCP arguments" }
                value.map { it.jsonPrimitive.content }
            }.orEmpty()
            fun text(field: String) = (server[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val literals = listOfNotNull(text("command"), text("cwd")) + args
            require(literals.none { LITERAL_SECRET.containsMatchIn(it) }) { "Secrets must be leased at runtime" }
            val sequence = listOfNotNull(text("command")).flatMap(::words) + args.flatMap(::words)
            require(!carriesAuthScheme(sequence)) { "Secrets must be leased at runtime" }
            sequence.forEachIndexed { index, word ->
                val following = sequence.getOrNull(index + 1)?.takeUnless { it.startsWith("-") }
                val (name, value) = when {
                    word.startsWith("-") -> word.trimStart('-').let { flag ->
                        flag.substringBefore('=') to (if ('=' in flag) flag.substringAfter('=') else following) }
                    '=' in word -> word.substringBefore('=').trim('"', '\'').let { it.takeIf(ASSIGNED_NAME::matches) ?: "" } to word.substringAfter('=').trim('"', '\'')
                    ':' in word && !word.contains("://") -> word.substringBefore(':') to word.substringAfter(':').ifEmpty { following }
                    else -> return@forEachIndexed
                }
                if (name.isEmpty() || !secretFlag(name) || value.isNullOrEmpty()) return@forEachIndexed
                require(flagValueAllowed(name, value)) { "Secrets must be leased at runtime" }
            }
        }
    }
}
