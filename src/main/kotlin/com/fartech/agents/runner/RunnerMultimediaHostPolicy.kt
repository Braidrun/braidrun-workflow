package com.fartech.agents.runner

import com.fartech.agents.runner.multimedia.RunnerMultimediaPlan
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.*

/** Only literal selections in the frozen, static Agent own multimedia routing and credential scope. */
internal class RunnerMultimediaHostPolicy(private val bundle: FrozenWorkflowBundle) {
    data class Selection(val image: String?, val audio: String?, val credentialReference: String) {
        fun plan() = RunnerMultimediaPlan(image, audio)
        val models get() = setOfNotNull(image, audio)
    }

    fun verifyInvocation(workflow: WorkflowDefinition, scope: WorkflowHostScope) {
        require(bundle.contains(workflow) && bundle.identityOf(workflow) == bundle.identityOf(resolveInvocation(scope))) {
            "Multimedia invocation differs from the frozen graph"
        }
    }

    fun selection(workflowId: String, scope: WorkflowHostScope): Selection {
        require(scope.occurrence == null) { "Multimedia does not admit derived or loop Agent scopes" }
        val workflow = resolveInvocation(scope)
        require(bundle.identityOf(workflow) == workflowId) { "Multimedia producer identity mismatch" }
        val step = requireNotNull(workflow.workflow.singleOrNull { it.step == scope.stepName }) { "Multimedia requires an exact frozen Agent step" }
        require(step.iterateOver == null && step.repeatUntil == null && step.subWorkflow == null && step.groupChat == null)
        val agent = requireNotNull(workflow.agents[requireNotNull(step.agent)]) { "Multimedia requires a named frozen Agent" }
        require(agent.agentRef == null) { "Multimedia Agent references must already be frozen" }
        val explicit = agent.overrides["tool_set"]?.let { value ->
            require(value is JsonArray && value.size <= 128) { "Multimedia tools must be a literal frozen list" }
            value.map { require(it is JsonPrimitive && it.isString); it.content }
        } ?: agent.tools
        require(explicit.size == explicit.toSet().size && "multimedia" in explicit && "multi_media" !in explicit) {
            "Multimedia must be explicitly frozen under its canonical tool group"
        }
        require(agent.overrides.keys.none { it.startsWith("multimedia_") && it !in PARAMETERS }) { "Multimedia route overrides are not admitted" }
        fun literal(key: String): String? = agent.overrides[key]?.let {
            require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "Multimedia parameters must be nonempty frozen strings" }
            it.content
        }
        val selection = Selection(literal(IMAGE), literal(AUDIO), requireNotNull(literal(KEY)) { "Multimedia requires a dedicated scoped credential reference" })
        require(RunnerAdmission.CREDENTIAL_REFERENCE.matches(selection.credentialReference)) { "Multimedia requires a dedicated scoped credential reference" }
        selection.plan() // Reject unknown models and templated values before any credential request.
        return selection
    }

    fun verifyParameters(selection: Selection, parameters: List<ConfigurationParameter>) {
        val relevant = parameters.filter { it.key.startsWith("multimedia_") }
        require(relevant.map { it.key }.toSet().size == relevant.size && relevant.all { it.key in PARAMETERS }) { "Multimedia route overrides are not admitted" }
        val values = relevant.associate { it.key to it.value }
        val expected = buildMap<String, JsonElement> {
            selection.image?.let { put(IMAGE, JsonPrimitive(it)) }
            selection.audio?.let { put(AUDIO, JsonPrimitive(it)) }
            put(KEY, JsonPrimitive(selection.credentialReference))
        }
        require(values == expected) { "Resolved multimedia configuration differs from the frozen selection" }
    }

    private fun resolveInvocation(scope: WorkflowHostScope): WorkflowDefinition {
        val root = bundle.taskBinding.string("executionId")
        require(scope.rootExecutionId == root)
        val parts = RunnerSandboxPolicy.executionParts(root, scope.executionId)
        val parent = if (parts.isEmpty()) null else root + parts.dropLast(2).let { if (it.isEmpty()) "" else "__" + it.joinToString("__") }
        require(scope.parentExecutionId == parent) { "Multimedia parent execution mismatch" }
        var definition = bundle.rootDefinition
        for ((segment, _) in parts.chunked(2)) {
            val step = requireNotNull(definition.workflow.singleOrNull { it.step.replace(Regex("[^A-Za-z0-9_.-]"), "_") == segment }) {
                "Multimedia child path is unknown or ambiguous"
            }
            val call = requireNotNull(step.subWorkflow) { "Multimedia child must be a static sub-workflow" }
            require(step.iterateOver == null && step.repeatUntil == null && call.agentInheritance == null) {
                "Multimedia child cannot inherit or synthesize Agent configuration"
            }
            definition = bundle.resolve(call.toReference()).definition
        }
        return definition
    }

    companion object {
        const val CAPABILITY = "tool.multimedia.v1"
        const val PURPOSE = "multimedia.generate"
        const val ENABLE_PROPERTY = "braidrun.runner.multimedia.enabled"
        const val IMAGE = "multimedia_default_image_model"
        const val AUDIO = "multimedia_default_audio_model"
        const val KEY = "multimedia_api_key"
        private val PARAMETERS = setOf(IMAGE, AUDIO, KEY)
        fun trustedEnabled(properties: java.util.Properties = System.getProperties()) = properties.getProperty(ENABLE_PROPERTY) == "true"
        fun grantedModels(material: JsonObject, selection: Selection): Set<String> {
            val values = material["grantedModelIds"]
            require(values is JsonArray && values.size in 1..128) { "Multimedia lease requires explicit model grants" }
            val models = values.map {
                require(it is JsonPrimitive && it.isString && it.content.length in 1..256 && it.content.none { c -> c.code < 32 }) {
                    "Multimedia lease model grants are invalid"
                }
                it.content
            }.toSet()
            require(models.size == values.size && models.containsAll(selection.models)) { "Frozen multimedia models exceed the credential lease" }
            return models
        }
    }
}
