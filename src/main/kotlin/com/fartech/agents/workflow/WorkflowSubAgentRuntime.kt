package com.fartech.agents.workflow

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.tools.ToolRegistry
import com.fartech.agents.commons.SkillManager

/** Trusted, lazily bound parent registry. It must never be reconstructed from workflow parameters.
 * The host must reject MCP augmentation unless it can provide the final augmented registry. */
data class WorkflowSubAgentRuntime(
    val registry: () -> ToolRegistry,
    val skillManager: SkillManager?,
    val installFeatures: GraphAIAgent.FeatureContext.() -> Unit,
)
