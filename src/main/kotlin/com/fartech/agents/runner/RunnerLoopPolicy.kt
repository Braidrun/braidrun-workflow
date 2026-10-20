package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowDefinition
import com.fartech.agents.workflow.WorkflowStep
import kotlinx.serialization.json.jsonPrimitive

/**
 * `step.loop.v1`: which loop steps a Runner runs, and the bounds it holds them to.
 *
 * Supported: `iterate_over` whose items are plain HTTP-model Agent runs or code runs. The loop is one atomic,
 * idempotent step: no savepoint is taken while items run, so a resume reruns every item. Each running Agent item
 * holds its own agent instance (prompt history, tool registry, model client, in-flight response) and its own model
 * credential lease, so concurrency is bounded ([MAX_PARALLEL]) and so is the fan-out ([MAX_ITEMS]).
 *
 * Refused until they have their own design: `repeat_until`; approvals, published outputs and artifact declarations
 * inside a loop (the artifact policies refuse loop producers); CLI agents and every other stage kind as loop items,
 * and step options the iteration interpreter ignores (retry, structured output, parallel tasks).
 */
object RunnerLoopPolicy {
    const val CAPABILITY = "step.loop.v1"
    const val MAX_PARALLEL = 8
    const val MAX_ITEMS = 200

    /** Static closure admission; [step] has `iterate_over` or `repeat_until`. */
    fun admit(workflow: WorkflowDefinition, step: WorkflowStep) {
        val name = step.step
        require(step.repeatUntil == null) { "Runner loop step '$name': repeat_until is not supported on Runners ($CAPABILITY covers iterate_over)" }
        val loop = requireNotNull(step.iterateOver)
        // Items run, and are released, as "<step>:<occurrence>": no real step may live under that prefix.
        require(workflow.workflow.none { it.step.startsWith("$name:") }) { "Runner loop step '$name': another step name starts with '$name:'" }
        require(step.idempotent) { "Runner loop step '$name' must be idempotent: a resume reruns every item" }
        if (loop.parallel) require(loop.maxParallel != null && loop.maxParallel in 1..MAX_PARALLEL) {
            "Runner loop step '$name' needs an explicit iterate_over.max_parallel between 1 and $MAX_PARALLEL"
        } else require(loop.maxParallel == null || loop.maxParallel in 1..MAX_PARALLEL) {
            "Runner loop step '$name': iterate_over.max_parallel must be between 1 and $MAX_PARALLEL"
        }
        require(loop.maxItems <= MAX_ITEMS) { "Runner loop step '$name': iterate_over.max_items above $MAX_ITEMS" }
        require(step.manualApproval == null) { "Runner loop step '$name' cannot ask for approval" }
        require(step.publishOutputs.isEmpty() && step.workflowOutputRead == null) { "Runner loop step '$name' cannot publish or read workflow outputs" }
        require(step.retry == null && step.structuredOutput == null && step.parallel == null) {
            "Runner loop step '$name': retry, structured_output and parallel tasks are not applied to loop items"
        }
        require(step.subWorkflow == null && step.stateMachine == null && step.classifier == null && step.groupChat == null && step.agentBased == null) {
            "Runner loop step '$name': only Agent or code items are supported"
        }
        if (step.code != null) return
        val agent = requireNotNull(workflow.agents[requireNotNull(step.agent) { "Runner loop step '$name' needs an Agent or code item" }]) { "Runner loop step '$name' names an unknown Agent" }
        requireNotNull(step.input) { "Runner loop step '$name' needs an item input" }
        val type = agent.resolveParameters()["type"]?.jsonPrimitive?.content ?: agent.type
        require(type !in setOf("claude_code_agent", "codex_agent")) { "Runner loop step '$name': CLI agent items are not supported" }
    }

    /** Runtime bound once the items are known; a refusal fails the loop step before any item runs. */
    fun admitRuntime(step: String, items: Int, concurrency: Int) {
        require(concurrency in 1..MAX_PARALLEL) { "Runner loop step '$step': concurrency $concurrency outside 1..$MAX_PARALLEL" }
        require(items <= MAX_ITEMS) { "Runner loop step '$step' has $items items; a Runner loop runs at most $MAX_ITEMS (set iterate_over.max_items or split the list)" }
    }
}
