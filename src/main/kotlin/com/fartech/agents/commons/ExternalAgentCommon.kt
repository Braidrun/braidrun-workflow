package com.fartech.agents.commons

import com.fartech.agents.tools.ExternalAgentContext
import com.fartech.agents.tools.ExternalAgentTools
import com.fartech.agents.tools.ClaudeCredentialProvider
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter

enum class ExternalAgentEngine {
    CLAUDE,
    CODEX
}

data class ExternalAgentRunResult(
    val text: String,
    val sessionId: String?,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null
)

suspend fun buildAndRunExternalAgent(
    engine: ExternalAgentEngine,
    parameters: List<ConfigurationParameter>,
    agentContext: ExternalAgentContext,
    onEvent: MonitoringEventCallback? = null,
    onTextDelta: ((String) -> Unit)? = null,
    onCodexAuthJsonRotated: ((String) -> Unit)? = null,
    claudeCredentialProvider: ClaudeCredentialProvider? = null
): ExternalAgentRunResult {
    val executor = createSubprocessExecutor(parameters)
    val toolContext = SubprocessExecutorFactory.buildToolContext(parameters)
    val userId = parameters.parameter("user_id", "local-user")
    val tools = ExternalAgentTools(
        executor = executor,
        parameters = parameters,
        userId = userId,
        context = toolContext,
        onMonitorEvent = onEvent,
        onCodexAuthJsonRotated = onCodexAuthJsonRotated,
        claudeCredentialProvider = claudeCredentialProvider
    )
    val detailed = tools.runConversation(
        engine = when (engine) {
            ExternalAgentEngine.CLAUDE -> ExternalAgentTools.Engine.CLAUDE
            ExternalAgentEngine.CODEX -> ExternalAgentTools.Engine.CODEX
        },
        ctx = agentContext,
        onTextDelta = onTextDelta
    )
    return ExternalAgentRunResult(
        text = detailed.text,
        sessionId = detailed.sessionId,
        inputTokens = detailed.inputTokens,
        outputTokens = detailed.outputTokens,
        costUsd = detailed.costUsd
    )
}
