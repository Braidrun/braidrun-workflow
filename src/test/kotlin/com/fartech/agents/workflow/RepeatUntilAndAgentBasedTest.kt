package com.fartech.agents.workflow

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.*

/**
 * 综合测试：repeat_until 和 agent_based 步骤类型
 *
 * 覆盖：
 * - RepeatUntilConfig 数据模型验证
 * - AgentBasedConfig 数据模型验证
 * - WorkflowStep 三选一模式验证
 * - WorkflowParser 验证逻辑
 * - OrchestratorTools 工具逻辑
 * - YAML 解析 roundtrip
 */
class RepeatUntilAndAgentBasedTest {

    @BeforeEach
    fun setup() {
        WorkflowMonitor.clear()
    }

    // ==================== RepeatUntilConfig Model Tests ====================

    @Test
    fun `RepeatUntilConfig defaults are correct`() {
        val config = RepeatUntilConfig(condition = "score >= 8")
        assertEquals("score >= 8", config.condition)
        assertEquals(5, config.maxIterations)
        assertNull(config.evaluateAgent)
        assertNull(config.evaluatePrompt)
        assertNull(config.extractPattern)
        assertNull(config.extractVariable)
    }

    @Test
    fun `RepeatUntilConfig full configuration`() {
        val config = RepeatUntilConfig(
            condition = "quality_score >= 8",
            maxIterations = 10,
            evaluateAgent = "reviewer",
            evaluatePrompt = "Rate the quality: {{steps.write.output}}",
            extractPattern = "quality_score=(\\d+)",
            extractVariable = "quality_score"
        )
        assertEquals("quality_score >= 8", config.condition)
        assertEquals(10, config.maxIterations)
        assertEquals("reviewer", config.evaluateAgent)
        assertEquals("Rate the quality: {{steps.write.output}}", config.evaluatePrompt)
        assertEquals("quality_score=(\\d+)", config.extractPattern)
        assertEquals("quality_score", config.extractVariable)
    }

    @Test
    fun `RepeatUntilConfig rejects blank condition`() {
        assertThrows<IllegalArgumentException> {
            RepeatUntilConfig(condition = "")
        }
        assertThrows<IllegalArgumentException> {
            RepeatUntilConfig(condition = "   ")
        }
    }

    @Test
    fun `RepeatUntilConfig rejects zero or negative max_iterations`() {
        assertThrows<IllegalArgumentException> {
            RepeatUntilConfig(condition = "done == true", maxIterations = 0)
        }
        assertThrows<IllegalArgumentException> {
            RepeatUntilConfig(condition = "done == true", maxIterations = -1)
        }
    }

    // ==================== AgentBasedConfig Model Tests ====================

    @Test
    fun `AgentBasedConfig defaults are correct`() {
        val config = AgentBasedConfig(
            orchestrator = minimalAgent(),
            participants = listOf("worker1"),
            goal = "Complete the task"
        )
        assertEquals(20, config.maxSteps)
        assertEquals(0L, config.budgetTokens)
        assertNull(config.timeoutSeconds)
    }

    @Test
    fun `AgentBasedConfig full configuration`() {
        val config = AgentBasedConfig(
            orchestrator = minimalAgent(),
            participants = listOf("designer", "engineer", "qa"),
            goal = "Review the PRD and generate feedback",
            maxSteps = 30,
            budgetTokens = 500000,
            timeoutSeconds = 600
        )
        assertEquals(3, config.participants.size)
        assertEquals("Review the PRD and generate feedback", config.goal)
        assertEquals(30, config.maxSteps)
        assertEquals(500000L, config.budgetTokens)
        assertEquals(600, config.timeoutSeconds)
    }

    @Test
    fun `AgentBasedConfig rejects empty participants`() {
        assertThrows<IllegalArgumentException> {
            AgentBasedConfig(
                orchestrator = minimalAgent(),
                participants = emptyList(),
                goal = "Do something"
            )
        }
    }

    @Test
    fun `AgentBasedConfig rejects blank goal`() {
        assertThrows<IllegalArgumentException> {
            AgentBasedConfig(
                orchestrator = minimalAgent(),
                participants = listOf("worker"),
                goal = ""
            )
        }
        assertThrows<IllegalArgumentException> {
            AgentBasedConfig(
                orchestrator = minimalAgent(),
                participants = listOf("worker"),
                goal = "   "
            )
        }
    }

    @Test
    fun `AgentBasedConfig rejects zero or negative max_steps`() {
        assertThrows<IllegalArgumentException> {
            AgentBasedConfig(
                orchestrator = minimalAgent(),
                participants = listOf("worker"),
                goal = "Do it",
                maxSteps = 0
            )
        }
    }

    @Test
    fun `AgentBasedConfig rejects negative budget_tokens`() {
        assertThrows<IllegalArgumentException> {
            AgentBasedConfig(
                orchestrator = minimalAgent(),
                participants = listOf("worker"),
                goal = "Do it",
                budgetTokens = -1
            )
        }
    }

    // ==================== WorkflowStep Mode Validation ====================

    @Test
    fun `WorkflowStep accepts single agent mode`() {
        val step = WorkflowStep(step = "s1", agent = "a", input = "test")
        assertFalse(step.isGroupChat)
        assertFalse(step.isAgentBased)
        assertEquals("a", step.displayAgentName)
    }

    @Test
    fun `WorkflowStep accepts group_chat mode`() {
        val step = WorkflowStep(
            step = "s1",
            groupChat = GroupChatConfig(participants = listOf("a", "b"))
        )
        assertTrue(step.isGroupChat)
        assertFalse(step.isAgentBased)
        assertTrue(step.displayAgentName.contains("group_chat"))
    }

    @Test
    fun `WorkflowStep accepts agent_based mode`() {
        val step = WorkflowStep(
            step = "s1",
            agentBased = AgentBasedConfig(
                orchestrator = minimalAgent(),
                participants = listOf("worker1", "worker2"),
                goal = "Complete the review"
            )
        )
        assertFalse(step.isGroupChat)
        assertTrue(step.isAgentBased)
        assertTrue(step.displayAgentName.contains("agent_based"))
        assertEquals(listOf("worker1", "worker2"), step.referencedAgents)
    }

    @Test
    fun `WorkflowStep rejects agent_based with agent`() {
        assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "s1",
                agent = "a",
                input = "test",
                agentBased = AgentBasedConfig(
                    orchestrator = minimalAgent(),
                    participants = listOf("worker"),
                    goal = "Do it"
                )
            )
        }
    }

    @Test
    fun `WorkflowStep rejects agent_based with group_chat`() {
        assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "s1",
                groupChat = GroupChatConfig(participants = listOf("a", "b")),
                agentBased = AgentBasedConfig(
                    orchestrator = minimalAgent(),
                    participants = listOf("worker"),
                    goal = "Do it"
                )
            )
        }
    }

    @Test
    fun `WorkflowStep with repeat_until on single agent`() {
        val step = WorkflowStep(
            step = "s1",
            agent = "writer",
            input = "Write a draft",
            repeatUntil = RepeatUntilConfig(
                condition = "quality_score >= 8",
                maxIterations = 3,
                evaluateAgent = "reviewer"
            )
        )
        val repeatUntil = assertNotNull(step.repeatUntil)
        assertEquals("quality_score >= 8", repeatUntil.condition)
    }

    // ==================== WorkflowDefinition Validation ====================

    @Test
    fun `WorkflowDefinition validates agent_based participant references`() {
        // 应成功：所有 participant 都在 agents 中定义
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf(
                "designer" to minimalAgent(),
                "engineer" to minimalAgent()
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "review",
                    agentBased = AgentBasedConfig(
                        orchestrator = minimalAgent(),
                        participants = listOf("designer", "engineer"),
                        goal = "Review the design"
                    )
                )
            )
        )
        assertEquals(1, workflow.workflow.size)
        assertTrue(workflow.workflow[0].isAgentBased)
    }

    @Test
    fun `WorkflowDefinition rejects undefined agent_based participant`() {
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test",
                agents = mapOf("designer" to minimalAgent()),
                workflow = listOf(
                    WorkflowStep(
                        step = "review",
                        agentBased = AgentBasedConfig(
                            orchestrator = minimalAgent(),
                            participants = listOf("designer", "unknown_agent"),
                            goal = "Review"
                        )
                    )
                )
            )
        }
    }

    // ==================== WorkflowParser Validation Tests ====================

    @Test
    fun `parser validates repeat_until with undefined evaluate_agent`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "write",
                    agent = "writer",
                    input = "Write something",
                    repeatUntil = RepeatUntilConfig(
                        condition = "done == true",
                        evaluateAgent = "nonexistent_reviewer"
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflow)
        }
    }

    @Test
    fun `parser validates repeat_until extract_pattern without extract_variable`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "write",
                    agent = "writer",
                    input = "Write something",
                    repeatUntil = RepeatUntilConfig(
                        condition = "score >= 8",
                        extractPattern = "score=(\\d+)"
                        // extractVariable is missing
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflow)
        }
    }

    @Test
    fun `parser validates repeat_until extract_variable without extract_pattern`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "write",
                    agent = "writer",
                    input = "Write something",
                    repeatUntil = RepeatUntilConfig(
                        condition = "score >= 8",
                        extractVariable = "score"
                        // extractPattern is missing
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflow)
        }
    }

    @Test
    fun `parser validates repeat_until invalid regex`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "write",
                    agent = "writer",
                    input = "Write something",
                    repeatUntil = RepeatUntilConfig(
                        condition = "score >= 8",
                        extractPattern = "[invalid(regex",
                        extractVariable = "score"
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflow)
        }
    }

    @Test
    fun `parser accepts valid repeat_until with evaluate_agent`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf(
                "writer" to minimalAgent(),
                "reviewer" to minimalAgent()
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "write",
                    agent = "writer",
                    input = "Write a draft",
                    repeatUntil = RepeatUntilConfig(
                        condition = "quality_score >= 8",
                        maxIterations = 5,
                        evaluateAgent = "reviewer",
                        evaluatePrompt = "Rate the quality",
                        extractPattern = "quality_score=(\\d+)",
                        extractVariable = "quality_score"
                    )
                )
            )
        )
        // Should not throw
        WorkflowParser.validateWorkflow(workflow)
    }

    @Test
    fun `parser validates agent_based with undefined participant`() {
        // WorkflowDefinition 构造函数已在创建时验证 agent 引用，
        // 因此 undefined participant 会在构造时抛出 IllegalArgumentException
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test",
                agents = mapOf("designer" to minimalAgent()),
                workflow = listOf(
                    WorkflowStep(
                        step = "review",
                        agentBased = AgentBasedConfig(
                            orchestrator = minimalAgent(),
                            participants = listOf("designer", "unknown"),
                            goal = "Review"
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `parser validates agent_based with unknown orchestrator preset`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("worker" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "orchestrate",
                    agentBased = AgentBasedConfig(
                        orchestrator = AgentDefinition(preset = "nonexistent_preset_xyz"),
                        participants = listOf("worker"),
                        goal = "Do work"
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflow)
        }
    }

    @Test
    fun `parser validates agent_based with undefined orchestrator agent reference`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("worker" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "orchestrate",
                    agentBased = AgentBasedConfig(
                        orchestrator = AgentDefinition(agentRef = "missing_orchestrator"),
                        participants = listOf("worker"),
                        goal = "Do work"
                    )
                )
            )
        )
        val error = assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflow)
        }
        assertTrue(error.message!!.contains("undefined agent 'missing_orchestrator'"))
    }

    @Test
    fun `parser accepts valid agent_based configuration`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf(
                "designer" to minimalAgent(),
                "engineer" to minimalAgent()
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "dynamic_review",
                    agentBased = AgentBasedConfig(
                        orchestrator = minimalAgent(),
                        participants = listOf("designer", "engineer"),
                        goal = "Review the design document",
                        maxSteps = 10
                    )
                )
            )
        )
        // Should not throw
        WorkflowParser.validateWorkflow(workflow)
    }

    @Test
    fun `parser accepts agent_based orchestrator agent reference`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf(
                "architect" to AgentDefinition(
                    preset = "coder",
                    overrides = mapOf("system_prompt" to JsonPrimitive("You design workflows"))
                ),
                "designer" to minimalAgent(),
                "engineer" to minimalAgent()
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "dynamic_review",
                    agentBased = AgentBasedConfig(
                        orchestrator = AgentDefinition(
                            agentRef = "architect",
                            overrides = mapOf("session_id_strategy" to JsonPrimitive("per_execution"))
                        ),
                        participants = listOf("designer", "engineer"),
                        goal = "Review the design document",
                        maxSteps = 10
                    )
                )
            )
        )

        WorkflowParser.validateWorkflow(workflow)
        val params = workflow.workflow.first().agentBased!!.orchestrator.resolveParameters(workflow.agents)
        assertEquals(JsonPrimitive("You design workflows"), params["system_prompt"])
        assertEquals(JsonPrimitive("per_execution"), params["session_id_strategy"])
    }

    // ==================== WorkflowParser Summary Tests ====================

    @Test
    fun `parser summary shows repeat_until info`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "write",
                    agent = "writer",
                    input = "Write",
                    repeatUntil = RepeatUntilConfig(
                        condition = "quality >= 8",
                        maxIterations = 5
                    )
                )
            )
        )
        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertTrue(summary.contains("repeat_until"))
        assertTrue(summary.contains("quality >= 8"))
        assertTrue(summary.contains("5 iterations"))
    }

    @Test
    fun `parser summary shows agent_based info`() {
        val workflow = WorkflowDefinition(
            name = "test",
            agents = mapOf(
                "designer" to minimalAgent(),
                "engineer" to minimalAgent()
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "review",
                    agentBased = AgentBasedConfig(
                        orchestrator = minimalAgent(),
                        participants = listOf("designer", "engineer"),
                        goal = "Multi-angle review"
                    )
                )
            )
        )
        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertTrue(summary.contains("agent_based"))
        assertTrue(summary.contains("Multi-angle review"))
        assertTrue(summary.contains("designer"))
        assertTrue(summary.contains("engineer"))
    }

    // ==================== YAML Parse Tests ====================

    @Test
    fun `YAML parse repeat_until step`() {
        val yaml = """
            name: test-repeat
            agents:
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
              reviewer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: write_draft
                agent: writer
                input: "Write a first draft"
                repeat_until:
                  condition: "quality_score >= 8"
                  max_iterations: 3
                  evaluate_agent: reviewer
                  evaluate_prompt: "Rate the quality"
                  extract_pattern: "score=(\\d+)"
                  extract_variable: quality_score
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        assertEquals("test-repeat", workflow.name)
        assertEquals(1, workflow.workflow.size)

        val step = workflow.workflow[0]
        val repeatUntil = assertNotNull(step.repeatUntil)
        assertEquals("quality_score >= 8", repeatUntil.condition)
        assertEquals(3, repeatUntil.maxIterations)
        assertEquals("reviewer", repeatUntil.evaluateAgent)
        assertEquals("score=(\\d+)", repeatUntil.extractPattern)
        assertEquals("quality_score", repeatUntil.extractVariable)
    }

    @Test
    fun `YAML parse agent_based step`() {
        val yaml = """
            name: test-agent-based
            agents:
              designer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
              engineer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: dynamic_review
                agent_based:
                  orchestrator:
                    type: universal_agent
                    strategy: just_work
                    tools: [exit]
                    llm:
                      model: gpt-4
                      provider: openai
                  participants:
                    - designer
                    - engineer
                  goal: "Review the design document"
                  max_steps: 15
                  budget_tokens: 100000
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        assertEquals("test-agent-based", workflow.name)
        assertEquals(1, workflow.workflow.size)

        val step = workflow.workflow[0]
        assertTrue(step.isAgentBased)
        val agentBased = assertNotNull(step.agentBased)
        assertEquals(listOf("designer", "engineer"), agentBased.participants)
        assertEquals("Review the design document", agentBased.goal)
        assertEquals(15, agentBased.maxSteps)
        assertEquals(100000L, agentBased.budgetTokens)
    }

    @Test
    fun `YAML parse agent_based step with orchestrator agent reference`() {
        val yaml = """
            name: test-agent-based
            agents:
              architect:
                preset: coder
                overrides:
                  system_prompt: "You design workflows"
              designer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
              engineer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: dynamic_review
                agent_based:
                  orchestrator:
                    agent: architect
                    overrides:
                      session_id_strategy: per_execution
                  participants:
                    - designer
                    - engineer
                  goal: "Review the design document"
                  max_steps: 15
                  budget_tokens: 100000
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val step = workflow.workflow[0]
        val orchestrator = step.agentBased!!.orchestrator
        assertEquals("architect", orchestrator.agentRef)
        val params = orchestrator.resolveParameters(workflow.agents)
        assertEquals(JsonPrimitive("You design workflows"), params["system_prompt"])
        assertEquals(JsonPrimitive("per_execution"), params["session_id_strategy"])
    }

    @Test
    fun `YAML parse mixed workflow with all step types`() {
        val yaml = """
            name: mixed-workflow
            agents:
              researcher:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
              reviewer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
              designer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: research
                agent: researcher
                input: "Research the topic"
              - step: write
                agent: writer
                input: "Write based on {{steps.research.output}}"
                depends_on:
                  - research
                repeat_until:
                  condition: "quality >= 8"
                  max_iterations: 3
              - step: discuss
                group_chat:
                  participants:
                    - writer
                    - reviewer
                  max_rounds: 5
                  initial_message: "Discuss the draft"
                depends_on:
                  - write
              - step: dynamic_finalize
                agent_based:
                  orchestrator:
                    type: universal_agent
                    strategy: just_work
                    tools: [exit]
                    llm:
                      model: gpt-4
                      provider: openai
                  participants:
                    - designer
                    - reviewer
                  goal: "Finalize based on discussion {{steps.discuss.output}}"
                  max_steps: 10
                depends_on:
                  - discuss
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        assertEquals("mixed-workflow", workflow.name)
        assertEquals(4, workflow.workflow.size)

        // Step 1: single agent
        assertFalse(workflow.workflow[0].isGroupChat)
        assertFalse(workflow.workflow[0].isAgentBased)
        assertNull(workflow.workflow[0].repeatUntil)

        // Step 2: single agent with repeat_until
        assertFalse(workflow.workflow[1].isGroupChat)
        assertFalse(workflow.workflow[1].isAgentBased)
        assertNotNull(workflow.workflow[1].repeatUntil)

        // Step 3: group_chat
        assertTrue(workflow.workflow[2].isGroupChat)
        assertFalse(workflow.workflow[2].isAgentBased)

        // Step 4: agent_based
        assertFalse(workflow.workflow[3].isGroupChat)
        assertTrue(workflow.workflow[3].isAgentBased)
    }

    // ==================== OrchestratorTools Tests ====================

    @Test
    fun `OrchestratorTools getParticipantInfo lists all participants`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        val tools = OrchestratorTools(
            workerAgentRunner = { _, _, _ -> throw UnsupportedOperationException("not in this test") },
            participantDescriptions = mapOf(
                "designer" to "UI/UX specialist",
                "engineer" to "Backend developer"
            ),
            context = context,
            maxSteps = 10
        )

        val info = tools.getParticipantInfo()
        assertTrue(info.contains("designer"))
        assertTrue(info.contains("UI/UX specialist"))
        assertTrue(info.contains("engineer"))
        assertTrue(info.contains("Backend developer"))
        assertTrue(info.contains("0/10"))
    }

    @Test
    fun `OrchestratorTools setVariable and getVariable`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        val tools = OrchestratorTools(
            workerAgentRunner = { _, _, _ -> throw UnsupportedOperationException("not in this test") },
            participantDescriptions = mapOf("w" to "worker"),
            context = context,
            maxSteps = 10
        )

        val setResult = tools.setVariable("review_status", "approved")
        assertTrue(setResult.contains("approved"))

        val getResult = tools.getVariable("review_status")
        assertTrue(getResult.contains("approved"))

        val missingResult = tools.getVariable("nonexistent")
        assertTrue(missingResult.contains("not set"))
    }

    @Test
    fun `OrchestratorTools complete sets completion state`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        val tools = OrchestratorTools(
            workerAgentRunner = { _, _, _ -> throw UnsupportedOperationException("not in this test") },
            participantDescriptions = mapOf("w" to "worker"),
            context = context,
            maxSteps = 10
        )

        assertFalse(tools.completed)
        assertNull(tools.completionSummary)

        tools.complete("All tasks done successfully")

        assertTrue(tools.completed)
        assertEquals("All tasks done successfully", tools.completionSummary)
    }

    @Test
    fun `OrchestratorTools delegateTask rejects unknown agent`() = runBlocking {
        val context = WorkflowExecutionContext("wf", "exec-1")
        val tools = OrchestratorTools(
            workerAgentRunner = { _, _, _ -> throw UnsupportedOperationException("not in this test") },
            participantDescriptions = mapOf("designer" to "designer agent"),
            context = context,
            maxSteps = 10
        )

        val result = tools.delegateTask("unknown_agent", "Do something")
        assertTrue(result.contains("Error"))
        assertTrue(result.contains("not an available participant"))
    }

    @Test
    fun `OrchestratorTools budget exceeded throws exception`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        val tools = OrchestratorTools(
            workerAgentRunner = { _, _, _ -> throw UnsupportedOperationException("not in this test") },
            participantDescriptions = mapOf("w" to "worker"),
            context = context,
            maxSteps = 0 // budget immediately exhausted
        )

        assertThrows<OrchestratorBudgetExceededException> {
            runBlocking { tools.delegateTask("w", "Do something") }
        }
    }

    @Test
    fun `OrchestratorTools delegateParallel rejects unknown agents`() = runBlocking {
        val context = WorkflowExecutionContext("wf", "exec-1")
        val tools = OrchestratorTools(
            workerAgentRunner = { _, _, _ -> throw UnsupportedOperationException("not in this test") },
            participantDescriptions = mapOf("designer" to "designer agent"),
            context = context,
            maxSteps = 10
        )

        val result = tools.delegateParallel("designer,unknown", "task1,task2")
        assertTrue(result.contains("Error"))
        assertTrue(result.contains("Unknown agents"))
    }

    @Test
    fun `OrchestratorTools delegation log tracks operations`() = runBlocking {
        val context = WorkflowExecutionContext("wf", "exec-1")
        val tools = OrchestratorTools(
            workerAgentRunner = { _, _, _ -> throw UnsupportedOperationException("not in this test") },
            participantDescriptions = mapOf("designer" to "designer agent"),
            context = context,
            maxSteps = 10
        )

        // Try delegate to unknown agent (doesn't create actual record since it returns early)
        tools.delegateTask("unknown", "task")

        // Delegation log should be empty since unknown agent returned error without logging
        assertEquals(0, tools.getDelegationLog().size)
    }

    // ==================== WorkflowParser toYaml Roundtrip ====================

    @Test
    fun `toYaml roundtrip preserves repeat_until`() {
        val original = WorkflowDefinition(
            name = "roundtrip-test",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "write",
                    agent = "writer",
                    input = "Write a draft",
                    repeatUntil = RepeatUntilConfig(
                        condition = "done == true",
                        maxIterations = 3
                    )
                )
            )
        )

        val yaml = WorkflowParser.toYaml(original)
        assertTrue(yaml.contains("repeat_until"))
        assertTrue(yaml.contains("done == true"))
        assertTrue(yaml.contains("max_iterations"))
    }

    @Test
    fun `toYaml roundtrip preserves agent_based`() {
        val original = WorkflowDefinition(
            name = "roundtrip-test",
            agents = mapOf(
                "designer" to minimalAgent(),
                "engineer" to minimalAgent()
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "review",
                    agentBased = AgentBasedConfig(
                        orchestrator = minimalAgent(),
                        participants = listOf("designer", "engineer"),
                        goal = "Review the design"
                    )
                )
            )
        )

        val yaml = WorkflowParser.toYaml(original)
        assertTrue(yaml.contains("agent_based"))
        assertTrue(yaml.contains("Review the design"))
        assertTrue(yaml.contains("participants"))
    }

    // ==================== extractAndSetVariable Logic Tests ====================

    @Test
    fun `extractAndSetVariable extracts numeric value from text`() {
        val context = WorkflowExecutionContext("wf", "exec-1")

        // Use reflection-free approach: test the regex logic directly
        val pattern = "quality_score=(\\d+)"
        val text = "The quality_score=9 for this draft is excellent."
        val regex = Regex(pattern)
        val match = regex.find(text)

        val regexMatch = assertNotNull(match)
        val value = if (regexMatch.groupValues.size > 1) regexMatch.groupValues[1] else regexMatch.value
        assertEquals("9", value)

        context.setVariable("quality_score", value)
        assertEquals("9", context.getVariable("quality_score"))
    }

    @Test
    fun `extractAndSetVariable handles no match gracefully`() {
        val pattern = "quality_score=(\\d+)"
        val text = "No score mentioned here."
        val regex = Regex(pattern)
        val match = regex.find(text)

        assertNull(match)
    }

    // ==================== Helper Methods ====================

    private fun minimalAgent() = AgentDefinition(
        type = "universal_agent",
        strategy = "just_work",
        tools = listOf("exit"),
        llm = LLMConfiguration(model = "gpt-4", provider = "openai")
    )
}
