package com.fartech.agents.workflow

import com.fartech.agents.tools.ClaudeCredentialProvider
import com.fartech.agents.tools.UserInteractionHandler
import com.fartech.agents.tools.exec.SubprocessExecutor

/** Trusted, execution-scoped services. Never populate this from workflow parameters.
 * A remote host owns authorization, environment admission, persistence and process supervision;
 * WorkflowExecutor continues to own the workflow interpreter. Null host retains library/CLI behavior.
 */
interface WorkflowHost {
    val llmHttpClientFactory: ai.koog.http.client.KoogHttpClient.Factory? get() = null
    suspend fun prepareAgentParameters(scope: WorkflowHostScope, parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> = parameters
    fun nestedParameters(parameters: List<com.fartech.ftapp2.commonsKt.ConfigurationParameter>): List<com.fartech.ftapp2.commonsKt.ConfigurationParameter> = parameters
    val privateDirectory: java.io.File? get() = null
    /** Scoped CLI auth files may rotate before exit; publication must refresh their secrets. */
    fun observeCredentialFile(scope: WorkflowHostScope, path: java.nio.file.Path): AutoCloseable = AutoCloseable { }
    fun claudeCredentialsFor(scope: WorkflowHostScope): ClaudeCredentialProvider? = claudeCredentials
    fun codexCredentialsFor(scope: WorkflowHostScope): ClaudeCredentialProvider? = codexCredentials
    fun codexAuthRotationFor(scope: WorkflowHostScope): ((String?, String) -> Unit)? = codexAuthRotation
    suspend fun interactiveProcess(scope: WorkflowHostScope, request: SubprocessExecutor.ExecRequest): WorkflowInteractiveProcess =
        throw IllegalStateException("Host interactive process transport is not adapted")
    suspend fun mcpTransport(scope: WorkflowHostScope, name: String, config: com.fartech.agents.commons.McpServerConfig): io.modelcontextprotocol.kotlin.sdk.shared.Transport =
        throw IllegalStateException("Host MCP transport is not adapted")
    val binding: WorkflowHostBinding
    val approvalHandler: ApprovalHandler
    val outputPublisher: WorkflowOutputPublisher
    val outputResolver: WorkflowOutputResolver
    val subprocessExecutor: SubprocessExecutor
    val workflowResolver: WorkflowResolver
    val codeEnvironment: Map<String, String> get() = emptyMap()
    val claudeCredentials: ClaudeCredentialProvider? get() = null
    val codexCredentials: ClaudeCredentialProvider? get() = null
    val codexAuthRotation: ((String?, String) -> Unit)? get() = null
    fun nativeAgentPrompt(scope: WorkflowHostScope, prompt: String): String = prompt
    val codeControlMode: WorkflowCodeControlMode get() = WorkflowCodeControlMode.LEGACY_STEP_TOKEN
    /** Called after resource admission; supervisor supplies its own peer-provider transport.
     * Returns only nonsecret host metadata, never a grant or whole-step credential. */
    fun prepareCodeStepEnvironment(scope: WorkflowHostScope, timeoutSeconds: Long): Map<String, String> = emptyMap()
    val callbackToken: ((Long) -> String)? get() = null
    fun callbackTokenFor(scope: WorkflowHostScope, budget: Long): String =
        requireNotNull(callbackToken) { "Callback capability unavailable" }.invoke(budget)

    /** Must inspect the complete definition closure before any credentials/model/process use. */
    suspend fun admit(workflow: WorkflowDefinition, scope: WorkflowHostScope)
    /** Fail closed on invalid lease, cancellation or stale/unknown environment evidence. */
    suspend fun beforeOperation(scope: WorkflowHostScope, operation: String)
    fun userInteraction(scope: WorkflowHostScope): UserInteractionHandler
    /** Resolve only an authorized definition, binding its bytes/hash before execution. */
    fun scriptContents(path: String): String = throw IllegalStateException("Host script resolver unavailable")
    fun dynamicWorkflow(path: String, scope: WorkflowHostScope): WorkflowDefinition
    /** Synchronous bounded handoff: a delivery failure must not silently drop metering. */
    fun monitor(update: WorkflowMonitorUpdate)
    /** Persist a consistent savepoint. Called outside running steps; not an arbitrary replay permit. */
    suspend fun checkpoint(checkpoint: WorkflowHostCheckpoint)
}

data class WorkflowHostBinding(
    val ownerId: String,
    val actorUserId: String,
    val billingOwnerId: String,
    val executionId: String,
    val runnerId: String,
    val runnerInstanceId: String,
    val resumeEpoch: Long,
    val attemptId: String,
    val definitionHash: String,
    val runtimeHash: String
)

data class WorkflowHostScope(
    val rootExecutionId: String,
    val executionId: String,
    val parentExecutionId: String? = null,
    val stepName: String? = null
)

data class WorkflowHostCheckpoint(
    val scope: WorkflowHostScope,
    val workflowName: String,
    val state: WorkflowResumeState,
    /** False for in-flight nested/parallel work: restoration requires reconciliation. */
    val safeToResume: Boolean,
    val pendingApprovals: List<ApprovalRequest> = emptyList()
)

data class WorkflowMonitorUpdate(
    val executionId: String,
    val type: String,
    val stepName: String? = null,
    val event: AgentEvent? = null,
    val success: Boolean? = null,
    val output: String? = null,
    val error: String? = null
)
