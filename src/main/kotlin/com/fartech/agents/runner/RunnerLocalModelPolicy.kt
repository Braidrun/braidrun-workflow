package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*

/** Reads only the manifest-hashed platform carrier; arbitrary YAML parameters never grant a
 * local endpoint. This checks the static closure again against the definitions actually run.
 * Current P0 approval/health/tokenizer/configuration lock remain supervisor responsibilities.
 */
class RunnerLocalModelPolicy(private val bundle: FrozenWorkflowBundle) {
    companion object { const val PATH = "runtime/runner-local-model-policy.v1.json" }
    private val original = bundle.runtimeBytes(PATH).also { require(it.size <= 1024 * 1024) }
    private val hash = sha256(original)
    private val policy = runnerJson.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
    private val definitions = bundle.manifest.array("definitions").associate { it.jsonObject.string("workflowId") to it.jsonObject.string("definitionHash") }
    private val graph = policy.array("childInvocationGraph").map { it.jsonObject }
    private val entries = policy.array("entries").map { it.jsonObject }
    val capability: String get() = entries.map { if (it.obj("binding").string("endpoint").startsWith("native://")) RunnerNativeModelProtocol.CAPABILITY else RunnerLocalModelProtocol.CAPABILITY }.distinct().single()
    data class Selection(val workflowId: String, val agentName: String, val binding: RunnerLocalModelBinding, val outputTokens: Int)
    private fun keys(value: JsonObject, expected: Set<String>) { require(value.keys == expected) { "Local carrier fields invalid" } }
    private fun segment(value: String) = Regex("[A-Za-z0-9_.-]{1,256}").matches(value) && "__" !in value && value !in setOf(".", "..")
    private fun child(workflow: String, part: String): String = graph.single { it.string("parentWorkflowId") == workflow && it.string("executionSegment") == part }.string("childWorkflowId")
    init {
        keys(policy, setOf("schemaVersion", "rootWorkflowId", "rootDefinitionHash", "sourceBundleHash", "childInvocationGraph", "entries"))
        require(policy["schemaVersion"] == JsonPrimitive(1) && policy.string("rootWorkflowId") == bundle.rootWorkflowId && policy["rootDefinitionHash"] == bundle.taskBinding["definitionHash"])
        val source = JsonObject(bundle.manifest.filterKeys { it != "bundleHash" } + mapOf(
            "files" to JsonArray(bundle.manifest.array("files").filter { it.jsonObject.string("path") != PATH }.sortedBy { it.jsonObject.string("path") }),
            "definitions" to JsonArray(bundle.manifest.array("definitions").sortedBy { it.jsonObject.string("workflowId") })))
        require(sha256(canonical(source).toByteArray()) == policy.string("sourceBundleHash")) { "Local source bundle changed" }
        require(definitions.size in 1..256 && entries.size in 1..1024 && graph.size <= 1024)
        require(graph.map { it.string("parentWorkflowId") to it.string("executionSegment") }.distinct().size == graph.size) { "Local graph ambiguous" }
        graph.forEach { edge ->
            keys(edge, setOf("parentWorkflowId", "callingStep", "executionSegment", "childWorkflowId", "childDefinitionHash"))
            val parent = bundle.definition(edge.string("parentWorkflowId"))
            val step = parent.workflow.single { it.step == edge.string("callingStep") }
            val call = requireNotNull(step.subWorkflow)
            require(call.agentInheritance == null && step.iterateOver == null && step.repeatUntil == null)
            require(segment(edge.string("executionSegment")) && edge.string("executionSegment") == step.step.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
            require(bundle.resolve(call.toReference()).identity == edge.string("childWorkflowId") && definitions[edge.string("childWorkflowId")] == edge.string("childDefinitionHash"))
        }
        // Every actual static invocation must be represented, including a second edge to the
        // same definition. Reachability alone cannot prove that a selected agent's uses are covered.
        definitions.keys.forEach { workflowId ->
            val definition = bundle.definition(workflowId)
            definition.workflow.forEach { step ->
                step.subWorkflow?.let {
                    require(graph.count { edge -> edge.string("parentWorkflowId") == workflowId && edge.string("callingStep") == step.step } == 1) { "Local static invocation missing from carrier" }
                }
                require(step.stateMachine?.states?.values.orEmpty().none { it.stepConfig?.subWorkflow != null }) { "Local carrier does not admit nested state-machine child invocations" }
            }
        }
        val reachable = mutableSetOf<String>()
        var paths = 0
        fun visit(workflow: String, ancestors: Set<String>) {
            require(workflow !in ancestors && ancestors.size <= 16 && ++paths <= 1024) { "Local child closure invalid" }
            reachable += workflow
            graph.filter { it.string("parentWorkflowId") == workflow }.forEach { visit(it.string("childWorkflowId"), ancestors + workflow) }
        }
        visit(bundle.rootWorkflowId, emptySet())
        require(reachable == definitions.keys) { "Local child closure incomplete" }
        require(entries.map { Triple(it.string("workflowId"), it.string("stepName"), it["childExecutionPath"]) }.distinct().size == entries.size)
        entries.forEach { entry ->
            keys(entry, setOf("workflowId", "definitionHash", "agentName", "stepName", "childExecutionPath", "binding", "approvalId", "evidenceSha256", "temperature"))
            require(entry["temperature"]?.jsonPrimitive?.doubleOrNull == 0.0 && Regex("[a-f0-9]{64}").matches(entry.string("evidenceSha256")) && Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}").matches(entry.string("approvalId")))
            val pin = RunnerLocalModelProtocol.binding(entry.obj("binding"))
            require(listOf("runnerId", "ownerId", "executionId", "attemptId").all { entry.obj("binding")[it] == bundle.taskBinding[it] }) { "Local carrier attempt mismatch" }
            val logical = entry.array("childExecutionPath").map { it.jsonPrimitive.content }
            require(logical.size <= 16 && logical.all(::segment))
            val workflow = logical.fold(bundle.rootWorkflowId, ::child)
            require(workflow == entry.string("workflowId") && definitions[workflow] == entry.string("definitionHash"))
            val definition = bundle.definition(workflow)
            val step = definition.workflow.single { it.step == entry.string("stepName") }
            require(step.agent == entry.string("agentName") && step.groupChat == null && step.classifier == null && step.agentBased == null && step.stateMachine == null &&
                step.code == null && step.subWorkflow == null && step.iterateOver == null && step.repeatUntil == null && step.parallel == null && step.aggregate == null &&
                step.structuredOutput == null && step.extract.isNullOrEmpty() && step.retry == null) { "Local step shape unsupported" }
            val agent = definition.agents.getValue(entry.string("agentName"))
            require(agent.agentRef == null && agent.mediaType in setOf(null, "text") && agent.tools.isEmpty() && agent.llmProviderKeys.isEmpty())
            val params = agent.resolveParameters()
            require(params.keys.all { it in setOf("type", "strategy", "name", "system_prompt", "llm_config", "max_tokens", "num_choices", "tool_set", "tool_choice") })
            require(params["type"] == JsonPrimitive("universal_agent") && params["strategy"] == JsonPrimitive("single_run"))
            require(params["tool_set"] == null || params["tool_set"] == JsonArray(emptyList()))
            require(params["tool_choice"] == null || params["tool_choice"] == JsonPrimitive("none"))
            require(params["num_choices"] == null || params["num_choices"] == JsonPrimitive(1))
            params["max_tokens"]?.let { require(it.jsonPrimitive.int in 1..pin.maximumOutputTokens) }
            val config = params["llm_config"]
            require(config == null || config is JsonObject)
            if (config is JsonObject) {
                require(config.keys.all { it in setOf("models", "fallback", "temperature") })
                val models = config["models"] as? JsonArray
                require(models == null || models.size == 1)
                require(config["fallback"] == null || config["fallback"] == models?.singleOrNull())
                fun inspect(value: JsonElement) { when(value) {
                    is JsonObject -> value.forEach { (key, nested) ->
                        require(key !in setOf("base_url", "api_key", "apiKey", "headers", "custom_models", "cascadeFallbacks") && !(key == "is_vision" && nested != JsonPrimitive(false))); inspect(nested)
                    }
                    is JsonArray -> value.forEach(::inspect)
                    else -> Unit
                } }
                inspect(config)
            }
        }
        require(entries.map { it["binding"] }.distinct().size == 1) { "Local P1 allows one registered model" }
    }
    fun verify() { require(sha256(bundle.runtimeBytes(PATH)) == hash) { "Local carrier changed" } }
    internal fun requireResolver(resolver: WorkflowResolver) {
        verify(); require(resolver === bundle) { "Local policy belongs to a different frozen resolver" }
    }
    /** Only replace cloud admission when every use of this selected agent is a verified plain
     * local step on every static path. Missing coverage is an error, never a cloud fallback. */
    internal fun fullyLocal(workflow: WorkflowDefinition, agentName: String): Boolean {
        verify(); require(bundle.contains(workflow))
        val workflowId = bundle.identityOf(workflow)
        val selected = entries.filter { it.string("workflowId") == workflowId && it.string("agentName") == agentName }
        if (selected.isEmpty()) return false
        val uses = workflow.workflow.filter { agentName in it.referencedAgents || it.repeatUntil?.evaluateAgent == agentName }
        require(uses.isNotEmpty() && uses.all { it.agent == agentName }) { "Local agent has unsupported derived uses" }
        val paths = mutableListOf<List<String>>()
        fun visit(id: String, path: List<String>) {
            if (id == workflowId) paths += path
            graph.filter { it.string("parentWorkflowId") == id }.forEach { visit(it.string("childWorkflowId"), path + it.string("executionSegment")) }
        }
        visit(bundle.rootWorkflowId, emptyList())
        val actual = selected.map { it.string("stepName") to it.array("childExecutionPath").map { part -> part.jsonPrimitive.content } }.toSet()
        val expected = uses.flatMap { step -> paths.map { step.step to it } }.toSet()
        require(actual == expected) { "Local agent carrier does not cover every frozen step and static invocation" }
        return true
    }
    /** Null is permitted only for a genuinely unselected static scope. A selected agent's wrong
     * step/path is a refusal, never an invitation to construct a cloud executor. */
    fun resolve(workflowId: String, stepName: String, actualChildPath: List<String>): Selection? {
        verify()
        require(actualChildPath.size <= 32 && actualChildPath.size % 2 == 0)
        val logical = actualChildPath.chunked(2).map { (part, ordinal) -> require(segment(part) && Regex("call_[1-9][0-9]{0,17}").matches(ordinal)); part }
        require(logical.fold(bundle.rootWorkflowId, ::child) == workflowId) { "Local child scope mismatch" }
        val current = bundle.definition(workflowId).workflow.singleOrNull { it.step == stepName } ?: return null
        val selected = entries.filter { it.string("workflowId") == workflowId && it.string("agentName") == current.agent }
        if (selected.isEmpty()) return null
        val entry = selected.single { it.string("stepName") == stepName && it.array("childExecutionPath").map { p -> p.jsonPrimitive.content } == logical }
        val pin = RunnerLocalModelProtocol.binding(entry.obj("binding"))
        val limit = bundle.definition(workflowId).agents.getValue(entry.string("agentName")).resolveParameters()["max_tokens"]?.jsonPrimitive?.int ?: pin.maximumOutputTokens
        return Selection(workflowId, entry.string("agentName"), pin, limit)
    }
}
