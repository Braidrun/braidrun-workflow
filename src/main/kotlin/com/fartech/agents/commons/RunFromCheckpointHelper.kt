package com.fartech.agents.commons

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.context.RollbackStrategy
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.Persistence
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Phase 11 (Koog 1.0.0) adoption — Features #3 + #4: `runFromCheckpoint`
 * + `AIAgentStorage` checkpoint participation.
 *
 * ## What Koog 1.0.0 added
 *
 * In 0.8.0, resuming a strategy execution from a saved [AgentCheckpointData]
 * required the `Persistence` feature to be installed at construction time
 * — i.e. you had to know up front "this agent might resume from a checkpoint
 * later" and pay the per-node persistence overhead during the live run.
 *
 * 1.0.0 separated checkpoint *creation* from checkpoint *resume*: the new
 * [Persistence.Companion.runFromCheckpoint] entry point only requires a
 * checkpoint payload and a target [AIAgent]; the agent does NOT need
 * the `Persistence` feature installed. Internally, the helper hydrates an
 * [ai.koog.agents.core.agent.entity.AIAgentStorage] from the
 * checkpoint's `storage: JSONObject?` field, restores the message history,
 * and re-enters the strategy graph at the recorded node.
 *
 * Companion change: [AgentCheckpointData.storage] (new in 1.0.0) carries
 * custom key-value data saved by features alongside the message history.
 * This restores the same K/V map a feature wrote during the original run,
 * making feature-state-aware resume practical for the first time.
 *
 * ## Why this matters for braidrun
 *
 * Pre-1.0.0 our [com.fartech.agents.commons.AgentState]
 * `MongoDbCustomStorageProvider` persisted checkpoints, but resume always
 * went through `restorePersistedExecutions` in `ExecutionService` — which
 * rebuilds the workflow from scratch and replays the step DAG. That path
 * loses any in-strategy state held in `AIAgentStorage`.
 *
 * The 1.0.0 `runFromCheckpoint` API gives us an alternative for the
 * narrower "resume a single agent step from where it failed" case. This
 * helper exposes the API to the workflow-web layer (and any other caller)
 * without forcing them to import internal Koog types.
 *
 * ## Use
 *
 * ```kotlin
 * val checkpoint = persistenceStorage.getLatestCheckpoint(sessionId)
 *     ?: error("no checkpoint to resume from")
 * val output = runAgentFromCheckpoint(
 *     agent = myAgent,
 *     input = "user prompt at resume point",
 *     checkpoint = checkpoint,
 * )
 * ```
 *
 * The agent does not need `enable_persistence=true`; the helper plumbs the
 * checkpoint into the session storage directly. `RollbackStrategy.Default`
 * preserves the message history and storage but skips replaying tool calls;
 * pass `RollbackStrategy.Full` to also re-run inverse tools registered in
 * `RollbackToolRegistry`.
 */
private val runFromCheckpointLogger = KotlinLogging.logger("com.fartech.agents.commons.RunFromCheckpoint")

/**
 * Resume an [AIAgent] from a previously saved [AgentCheckpointData]. The
 * agent does NOT need the `Persistence` feature installed (Koog 1.0.0
 * introduced this independence — see file-level KDoc).
 *
 * Returns the same output type the agent's strategy produces.
 *
 * @param agent The agent to resume.
 * @param input The input that would normally be passed to `agent.run(input)`.
 *   Re-supplied here because the checkpoint stores prior history but not
 *   the "next user message" — typical resume happens after a user retry.
 * @param checkpoint The serialized agent state captured by an earlier
 *   `createCheckpointAfterNode` call (or read from a
 *   `PersistenceStorageProvider`).
 * @param rollbackStrategy How aggressively to restore. Default preserves
 *   history without re-running tool calls; pass `RollbackStrategy.Full`
 *   to also execute paired inverse tools.
 * @param sessionId Optional session id (uses a random UUID when omitted —
 *   typically passed when the workflow has a stable session key).
 */
public suspend fun <Input, Output> runAgentFromCheckpoint(
    agent: AIAgent<Input, Output>,
    input: Input,
    checkpoint: AgentCheckpointData,
    rollbackStrategy: RollbackStrategy = RollbackStrategy.Default,
    sessionId: String? = null,
): Output {
    runFromCheckpointLogger.info {
        "Resuming agent from checkpoint id=${checkpoint.checkpointId} version=${checkpoint.version} " +
            "messageHistory=${checkpoint.messageHistory.size} sessionId=${sessionId ?: "<auto>"}"
    }
    return Persistence.runFromCheckpoint(
        agent = agent,
        input = input,
        checkpoint = checkpoint,
        rollbackStrategy = rollbackStrategy,
        sessionId = sessionId,
    )
}
