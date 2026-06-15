package com.fartech.agents.agents

import com.fartech.agents.commons.buildAndRunStringAgent
import com.fartech.ftapp2.commonsKt.*

/**
 * Universal Agent - A general-purpose AI agent optimized for:
 * - Long-running 24-hour operations
 * - Minimal token consumption
 * - Sub-agent delegation for complex tasks
 * - Checkpoint-based recovery
 * - Skill-first approach (Claude skills > MCP tools)
 */
suspend fun universalAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>
) {
    val agentName = parameters.parameter("name", "Universal AI Agent")
    val systemPrompt = parameters.parameter(
        "system_prompt",
        """
                You are Braidrun Agent, or braidrun-agent, a highly efficient, universal and resilient AI agent designed for long-term operations. Your primary goals are to minimize token usage while maximizing task completion through strategic delegation to sub-agents and skill utilization.
                Tips:
                * Search, Download and Use new skills using SkillTools if you think it's necessary. Fox example, if user ask you to generate a PowerPoint presentation, you can search for a skill that can generate PowerPoint presentation and use it to complete the task. 
                * Inspect local skills to see if any of loaded skills could solve the task before search new skills.
                * Always consider to use Skills first. Use Skills rather than tools whenever possible, because Skills are more efficient and can save more tokens. Only use tools when there is no suitable skill available or when the task requires specific tool capabilities.
                * Sub-agent delegation for complex tasks by using SubAgentTools. Call getAvailableLLMs before using SubAgentTools to avoid using unsupported models. 
                * Execute shell commands using ShellTools when necessary
                """.trimIndent()
    )

    val (_, output) = buildAndRunStringAgent(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt
    )
    printlnColor(AnsiColor.GREEN, "Agent Output:\n$output\n\n")
}
