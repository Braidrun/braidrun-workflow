package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*

/** Authority comes from the verified definition graph, never resolved parameters or preset defaults. */
internal class RunnerSandboxPolicy(private val bundle: FrozenWorkflowBundle) {
    fun verifyInvocation(workflow: WorkflowDefinition, scope: WorkflowHostScope) {
        require(bundle.contains(workflow) && bundle.identityOf(workflow) == bundle.identityOf(resolveInvocation(scope))) {
            "Sandbox invocation differs from the frozen graph"
        }
    }

    fun groups(workflowId: String, scope: WorkflowHostScope): Set<String> {
        require(scope.occurrence == null) { "Sandbox does not admit loop or derived Agent scopes" }
        val workflow = resolveInvocation(scope)
        require(bundle.identityOf(workflow) == workflowId) { "Sandbox producer identity mismatch" }
        val name = requireNotNull(scope.stepName)
        require(name.toByteArray().size in 1..256)
        val step = requireNotNull(workflow.workflow.singleOrNull { it.step == name }) { "Sandbox requires an exact frozen Agent step" }
        require(step.iterateOver == null && step.repeatUntil == null && step.subWorkflow == null && step.groupChat == null)
        val agent = requireNotNull(workflow.agents[requireNotNull(step.agent)]) { "Sandbox requires a frozen named Agent" }
        require(agent.agentRef == null) { "Sandbox Agent references must already be frozen" }
        val explicit = agent.overrides["tool_set"]?.let { value ->
            require(value is JsonArray) { "Sandbox tools must be a literal frozen list" }
            value.map { item ->
                require(item is JsonPrimitive && item.isString)
                item.content
            }
        } ?: agent.tools
        require(explicit.size <= 128 && explicit.size == explicit.toSet().size)
        return explicit.toSet().intersect(GROUPS)
    }

    private fun resolveInvocation(scope: WorkflowHostScope): WorkflowDefinition {
        val root = bundle.taskBinding.string("executionId")
        require(scope.rootExecutionId == root)
        val parts = executionParts(root, scope.executionId)
        val parent = if (parts.isEmpty()) null else root + parts.dropLast(2).let { if (it.isEmpty()) "" else "__" + it.joinToString("__") }
        require(scope.parentExecutionId == parent) { "Sandbox parent execution mismatch" }
        var definition = bundle.rootDefinition
        for ((segment, _) in parts.chunked(2)) {
            // Reject collisions after the executor's real sanitizeStepNameForId transformation.
            val step = requireNotNull(definition.workflow.singleOrNull { it.step.replace(Regex("[^A-Za-z0-9_.-]"), "_") == segment }) {
                "Sandbox child path is unknown or ambiguous"
            }
            val call = requireNotNull(step.subWorkflow) { "Sandbox child path is not a static sub-workflow" }
            require(step.iterateOver == null && step.repeatUntil == null && call.agentInheritance == null) {
                "Sandbox child cannot inherit or synthesize Agent tools"
            }
            definition = bundle.resolve(call.toReference()).definition
        }
        return definition
    }

    companion object {
        val GROUPS = setOf("shell", "code_execution", "git")
        /** IPC keeps its existing string field; its meaning is the FULL canonical executionId. */
        fun executionParts(root: String, execution: String): List<String> {
            require(root.toByteArray().size in 1..256 && Regex("[A-Za-z0-9][A-Za-z0-9_.-]*").matches(root) && "__" !in root)
            require(execution.toByteArray().size in 1..256)
            if (execution == root) return emptyList()
            require(execution.startsWith(root + "__")) { "Sandbox execution escapes bound root" }
            val parts = execution.removePrefix(root + "__").split("__")
            require(parts.size in 2..32 && parts.size % 2 == 0)
            parts.chunked(2).forEach { (segment, ordinal) ->
                require(Regex("[A-Za-z0-9_.-]{1,256}").matches(segment) && segment !in setOf(".", "..") &&
                    Regex("call_[1-9][0-9]{0,17}").matches(ordinal)) { "Sandbox execution path is not canonical" }
            }
            return parts
        }
    }
}
