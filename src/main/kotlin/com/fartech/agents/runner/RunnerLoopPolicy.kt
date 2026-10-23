package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowDefinition
import com.fartech.agents.workflow.WorkflowStep
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which loop steps a Runner runs, and the bounds it holds them to.
 *
 * `step.loop.v1` ([CAPABILITY]): `iterate_over` whose items are plain HTTP-model Agent runs or code runs. The loop is
 * one atomic step: no savepoint is taken while items run, so a resume reruns every item (no `idempotent` mark is
 * required: an owner resume reruns it like any interrupted step, automatic resume skips it unless replay-safe). Each running
 * Agent item holds its own agent instance (prompt history, tool registry, model client, in-flight response) and its
 * own model credential lease, so concurrency is bounded ([MAX_PARALLEL]) and so is the fan-out ([MAX_ITEMS]).
 *
 * `step.repeatUntil.v1` ([REPEAT_CAPABILITY]): a `repeat_until` loop, rounds run one after another, is likewise one
 * atomic step: no savepoint is taken between rounds (every round rewrites the step's result, so a mid-loop savepoint
 * would read the loop as done), and a resume reruns it from round 1. It is a plain step for resume purposes, so it
 * needs no `idempotent` mark: an owner resume reruns it like any interrupted Agent step and automatic resume skips it
 * unless it is replay-safe. Each evaluator round's lease ends with the round ([WorkflowHost.occurrenceEnded],
 * occurrence `evaluate:<n>`). The body's own kind (group chat, classifier, parallel tasks, a CLI agent) is gated by its
 * own capability; Jev decisions by `decision.jev.v1`.
 *
 * Refused until they have their own design: approvals, published outputs and artifact declarations inside a loop
 * (the artifact policies refuse loop producers); CLI agents and every other stage kind as `iterate_over` items, and
 * step options the iteration interpreter ignores (retry, structured output, parallel tasks).
 */
object RunnerLoopPolicy {
    const val CAPABILITY = "step.loop.v1"
    /** A non-idempotent iterate_over (rerun on resume). Older runtimes advertise step.loop.v1 but refuse it after claim. */
    const val RERUN_CAPABILITY = "step.loop.rerun.v1"
    const val REPEAT_CAPABILITY = "step.repeatUntil.v1"
    const val MAX_PARALLEL = 8
    const val MAX_ITEMS = 200
    /** Rounds one Runner `repeat_until` may run (the engine itself allows 1000); shipped templates use 2 to 4. */
    const val MAX_REPEAT_ITERATIONS = 50

    /** Static closure admission of a `repeat_until` step under [REPEAT_CAPABILITY]. */
    fun admitRepeatUntil(workflow: WorkflowDefinition, step: WorkflowStep) {
        val name = step.step
        val repeat = requireNotNull(step.repeatUntil)
        require(repeat.maxIterations <= MAX_REPEAT_ITERATIONS) {
            "Runner loop step '$name': repeat_until.max_iterations ${repeat.maxIterations} above $MAX_REPEAT_ITERATIONS"
        }
        // The evaluator runs, and is released, as "<step>:evaluate:<n>": no real step may live under that prefix.
        require(workflow.workflow.none { it.step.startsWith("$name:") }) { "Runner loop step '$name': another step name starts with '$name:'" }
        require(step.manualApproval == null) { "Runner loop step '$name' cannot ask for approval" }
        require(step.publishOutputs.isEmpty() && step.workflowOutputRead == null) { "Runner loop step '$name' cannot publish or read workflow outputs" }
        require(step.subWorkflow == null && step.stateMachine == null && step.agentBased == null && step.code == null) {
            "Runner loop step '$name': repeat_until runs an Agent, classifier or group chat body"
        }
    }

    /** Static closure admission of an `iterate_over` step under [CAPABILITY]. */
    fun admit(workflow: WorkflowDefinition, step: WorkflowStep) {
        val name = step.step
        val loop = requireNotNull(step.iterateOver) { "Runner loop step '$name': iterate_over missing" }
        require(step.repeatUntil == null) { "Runner loop step '$name': iterate_over cannot be combined with repeat_until" }
        // Items run, and are released, as "<step>:<occurrence>": no real step may live under that prefix.
        require(workflow.workflow.none { it.step.startsWith("$name:") }) { "Runner loop step '$name': another step name starts with '$name:'" }
        // No idempotent mark is required (owner decision 2026-10-23, as for repeat_until): a resume reruns every item, the
        // same as an owner resume reruns any interrupted Agent step; automatic resume skips steps that are not replay-safe.
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
