package com.fartech.agents.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GroupChatTest {

    private fun testWorkflowPath(fileName: String): String =
        "src/test/resources/workflows/$fileName"

    @Test
    fun `GroupChatConfig requires at least 2 participants`() {
        val ex = assertThrows<IllegalArgumentException> {
            GroupChatConfig(participants = listOf("agent1"))
        }
        assertTrue(ex.message!!.contains("at least 2"))
    }

    @Test
    fun `GroupChatConfig accepts 2 participants`() {
        val config = GroupChatConfig(participants = listOf("a", "b"))
        assertEquals(2, config.participants.size)
        assertEquals(10, config.maxRounds)
        assertEquals("round_robin", config.speakerSelection)
        assertEquals("CONSENSUS_REACHED", config.terminationKeyword)
        assertNull(config.moderator)
        assertNull(config.summaryAgent)
        assertNull(config.initialMessage)
    }

    @Test
    fun `GroupChatConfig rejects invalid speaker selection`() {
        val ex = assertThrows<IllegalArgumentException> {
            GroupChatConfig(
                participants = listOf("a", "b"),
                speakerSelection = "invalid"
            )
        }
        assertTrue(ex.message!!.contains("speaker_selection"))
    }

    @Test
    fun `GroupChatConfig accepts round_robin selection`() {
        val config = GroupChatConfig(
            participants = listOf("a", "b"),
            speakerSelection = "round_robin"
        )
        assertEquals("round_robin", config.speakerSelection)
    }

    @Test
    fun `GroupChatConfig accepts random selection`() {
        val config = GroupChatConfig(
            participants = listOf("a", "b"),
            speakerSelection = "random"
        )
        assertEquals("random", config.speakerSelection)
    }

    @Test
    fun `GroupChatConfig rejects zero maxRounds`() {
        val ex = assertThrows<IllegalArgumentException> {
            GroupChatConfig(
                participants = listOf("a", "b"),
                maxRounds = 0
            )
        }
        assertTrue(ex.message!!.contains("max_rounds"))
    }

    @Test
    fun `GroupChatConfig rejects negative maxRounds`() {
        assertThrows<IllegalArgumentException> {
            GroupChatConfig(
                participants = listOf("a", "b"),
                maxRounds = -1
            )
        }
    }

    @Test
    fun `GroupChatConfig moderator must be a participant`() {
        val ex = assertThrows<IllegalArgumentException> {
            GroupChatConfig(
                participants = listOf("a", "b"),
                moderator = "c"
            )
        }
        assertTrue(ex.message!!.contains("Moderator"))
        assertTrue(ex.message!!.contains("participants"))
    }

    @Test
    fun `GroupChatConfig accepts valid moderator`() {
        val config = GroupChatConfig(
            participants = listOf("a", "b"),
            moderator = "a"
        )
        assertEquals("a", config.moderator)
    }

    @Test
    fun `GroupChatConfig summaryAgent must be a participant`() {
        val ex = assertThrows<IllegalArgumentException> {
            GroupChatConfig(
                participants = listOf("a", "b"),
                summaryAgent = "c"
            )
        }
        assertTrue(ex.message!!.contains("Summary agent"))
    }

    @Test
    fun `GroupChatConfig accepts valid summaryAgent`() {
        val config = GroupChatConfig(
            participants = listOf("a", "b"),
            summaryAgent = "b"
        )
        assertEquals("b", config.summaryAgent)
    }

    @Test
    fun `GroupChatConfig full configuration`() {
        val config = GroupChatConfig(
            participants = listOf("coder", "reviewer", "tester"),
            moderator = "reviewer",
            maxRounds = 8,
            speakerSelection = "round_robin",
            terminationKeyword = "DONE",
            initialMessage = "Review this code",
            summaryAgent = "reviewer"
        )
        assertEquals(3, config.participants.size)
        assertEquals("reviewer", config.moderator)
        assertEquals(8, config.maxRounds)
        assertEquals("DONE", config.terminationKeyword)
        assertEquals("Review this code", config.initialMessage)
        assertEquals("reviewer", config.summaryAgent)
    }

    @Test
    fun `GroupChatConfig null termination keyword`() {
        val config = GroupChatConfig(
            participants = listOf("a", "b"),
            terminationKeyword = null
        )
        assertNull(config.terminationKeyword)
    }

    @Test
    fun `GroupChatMessage creation`() {
        val msg = GroupChatMessage(
            speaker = "coder",
            content = "Here is my proposal...",
            round = 1
        )
        assertEquals("coder", msg.speaker)
        assertEquals("Here is my proposal...", msg.content)
        assertEquals(1, msg.round)
        assertTrue(msg.timestamp > 0)
    }

    @Test
    fun `WorkflowStep group chat mode basic`() {
        val step = WorkflowStep(
            step = "discussion",
            groupChat = GroupChatConfig(
                participants = listOf("coder", "reviewer")
            )
        )
        assertTrue(step.isGroupChat)
        assertNull(step.agent)
        assertNull(step.input)
        assertEquals(listOf("coder", "reviewer"), step.referencedAgents)
        assertTrue(step.displayAgentName.contains("group_chat"))
        assertTrue(step.displayAgentName.contains("coder"))
        assertTrue(step.displayAgentName.contains("reviewer"))
    }

    @Test
    fun `WorkflowStep single agent mode basic`() {
        val step = WorkflowStep(
            step = "analyze",
            agent = "researcher",
            input = "Research this topic"
        )
        assertFalse(step.isGroupChat)
        assertEquals("researcher", step.agent)
        assertEquals("Research this topic", step.input)
        assertEquals(listOf("researcher"), step.referencedAgents)
        assertEquals("researcher", step.displayAgentName)
    }

    @Test
    fun `WorkflowStep rejects both agent and group_chat`() {
        val ex = assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "invalid",
                agent = "researcher",
                groupChat = GroupChatConfig(
                    participants = listOf("a", "b")
                )
            )
        }
        assertTrue(ex.message!!.contains("must specify exactly one of"))
    }

    @Test
    fun `WorkflowStep rejects neither agent nor group_chat`() {
        val ex = assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "invalid"
            )
        }
        assertTrue(ex.message!!.contains("must specify exactly one of"))
    }

    @Test
    fun `WorkflowStep single agent requires input`() {
        val ex = assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "missing_input",
                agent = "worker"
            )
        }
        assertTrue(ex.message!!.contains("input"))
    }

    @Test
    fun `WorkflowStep group chat does not require input`() {
        val step = WorkflowStep(
            step = "discussion",
            groupChat = GroupChatConfig(
                participants = listOf("a", "b"),
                initialMessage = "Discuss topic"
            )
        )
        assertNull(step.input)
        assertEquals("Discuss topic", step.groupChat!!.initialMessage)
    }

    @Test
    fun `WorkflowDefinition validates group chat agent references`() {
        val workflow = WorkflowDefinition(
            name = "test-gc",
            agents = mapOf(
                "coder" to AgentDefinition(preset = "coder"),
                "reviewer" to AgentDefinition(preset = "reviewer")
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "discussion",
                    groupChat = GroupChatConfig(
                        participants = listOf("coder", "reviewer")
                    )
                )
            )
        )
        assertEquals(1, workflow.workflow.size)
        assertTrue(workflow.workflow[0].isGroupChat)
    }

    @Test
    fun `WorkflowDefinition rejects undefined group chat participant`() {
        val ex = assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test-gc",
                agents = mapOf(
                    "coder" to AgentDefinition(preset = "coder")
                ),
                workflow = listOf(
                    WorkflowStep(
                        step = "discussion",
                        groupChat = GroupChatConfig(
                            participants = listOf("coder", "reviewer")
                        )
                    )
                )
            )
        }
        assertTrue(ex.message!!.contains("reviewer"))
        assertTrue(ex.message!!.contains("undefined agent"))
    }

    @Test
    fun `WorkflowDefinition mixed single and group chat steps`() {
        val workflow = WorkflowDefinition(
            name = "mixed-workflow",
            agents = mapOf(
                "researcher" to AgentDefinition(preset = "researcher"),
                "coder" to AgentDefinition(preset = "coder"),
                "reviewer" to AgentDefinition(preset = "reviewer"),
                "pm" to AgentDefinition(preset = "universal")
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "research",
                    agent = "researcher",
                    input = "Research the topic"
                ),
                WorkflowStep(
                    step = "team_discussion",
                    groupChat = GroupChatConfig(
                        participants = listOf("coder", "reviewer", "pm"),
                        moderator = "pm",
                        maxRounds = 6,
                        initialMessage = "Discuss based on {{steps.research.output}}"
                    ),
                    dependsOn = listOf("research")
                ),
                WorkflowStep(
                    step = "write_report",
                    agent = "pm",
                    input = "Write report based on {{steps.team_discussion.output}}",
                    dependsOn = listOf("team_discussion")
                )
            )
        )

        assertEquals(3, workflow.workflow.size)
        assertFalse(workflow.workflow[0].isGroupChat)
        assertTrue(workflow.workflow[1].isGroupChat)
        assertFalse(workflow.workflow[2].isGroupChat)
        assertEquals(3, workflow.workflow[1].groupChat!!.participants.size)
        assertEquals("pm", workflow.workflow[1].groupChat!!.moderator)
    }

    @Test
    fun `parse group chat workflow from YAML`() {
        val workflow = WorkflowParser.parseFile(testWorkflowPath("group-chat-code-review.yaml"))

        assertEquals("group-chat-code-review", workflow.name)
        assertEquals(3, workflow.agents.size)
        assertTrue(workflow.agents.containsKey("coder"))
        assertTrue(workflow.agents.containsKey("reviewer"))
        assertTrue(workflow.agents.containsKey("tester"))
        assertEquals(3, workflow.workflow.size)

        // Step 1: single agent
        val step1 = workflow.workflow[0]
        assertEquals("write_code", step1.step)
        assertFalse(step1.isGroupChat)
        assertEquals("coder", step1.agent)

        // Step 2: group chat
        val step2 = workflow.workflow[1]
        assertEquals("code_review_discussion", step2.step)
        assertTrue(step2.isGroupChat)
        assertNull(step2.agent)
        assertNull(step2.input)

        val gc = step2.groupChat!!
        assertEquals(listOf("coder", "reviewer", "tester"), gc.participants)
        assertEquals("reviewer", gc.moderator)
        assertEquals(6, gc.maxRounds)
        assertEquals("round_robin", gc.speakerSelection)
        assertEquals("APPROVED", gc.terminationKeyword)
        assertNotNull(gc.initialMessage)
        assertTrue(gc.initialMessage!!.contains("{{steps.write_code.output}}"))
        assertEquals("reviewer", gc.summaryAgent)
        assertEquals(listOf("write_code"), step2.dependsOn)

        // Step 3: single agent depending on group chat
        val step3 = workflow.workflow[2]
        assertEquals("final_report", step3.step)
        assertFalse(step3.isGroupChat)
        assertEquals("reviewer", step3.agent)
        assertEquals(listOf("code_review_discussion"), step3.dependsOn)

    }

    @Test
    fun `GroupChatConfig serialization roundtrip`() {
        val config = GroupChatConfig(
            participants = listOf("a", "b", "c"),
            moderator = "a",
            maxRounds = 5,
            speakerSelection = "random",
            terminationKeyword = "DONE",
            initialMessage = "Start",
            summaryAgent = "c"
        )

        val json = kotlinx.serialization.json.Json.encodeToString(GroupChatConfig.serializer(), config)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(GroupChatConfig.serializer(), json)

        assertEquals(config.participants, decoded.participants)
        assertEquals(config.moderator, decoded.moderator)
        assertEquals(config.maxRounds, decoded.maxRounds)
        assertEquals(config.speakerSelection, decoded.speakerSelection)
        assertEquals(config.terminationKeyword, decoded.terminationKeyword)
        assertEquals(config.initialMessage, decoded.initialMessage)
        assertEquals(config.summaryAgent, decoded.summaryAgent)
    }

    @Test
    fun `GroupChatMessage serialization roundtrip`() {
        val msg = GroupChatMessage(
            speaker = "test",
            content = "Hello",
            round = 3,
            timestamp = 12345L
        )

        val json = kotlinx.serialization.json.Json.encodeToString(GroupChatMessage.serializer(), msg)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(GroupChatMessage.serializer(), json)

        assertEquals(msg.speaker, decoded.speaker)
        assertEquals(msg.content, decoded.content)
        assertEquals(msg.round, decoded.round)
        assertEquals(msg.timestamp, decoded.timestamp)
    }

    @Test
    fun `termination signal ignores explanatory mention of keyword`() {
        val response = """
            Let's hear from the designer and engineer first.
            Once we agree, I will output CONSENSUS_REACHED and summarize the decision.
        """.trimIndent()

        assertFalse(containsGroupChatTerminationSignal(response, "CONSENSUS_REACHED"))
    }

    @Test
    fun `termination signal accepts explicit final line keyword`() {
        val response = """
            Everyone agrees. We should move to the summary.

            CONSENSUS_REACHED
        """.trimIndent()

        assertTrue(containsGroupChatTerminationSignal(response, "CONSENSUS_REACHED"))
    }

    @Test
    fun `termination readiness waits for all participants to speak`() {
        val config = GroupChatConfig(
            participants = listOf("pm", "designer", "engineer"),
            moderator = "pm"
        )
        val history = listOf(
            GroupChatMessage(speaker = "pm", content = "Opening note", round = 1)
        )

        val readiness = assessGroupChatTerminationReadiness(config, "pm", history)

        assertFalse(readiness.canTerminate)
        assertEquals(listOf("designer", "engineer"), readiness.missingParticipants)
    }

    @Test
    fun `termination readiness requires moderator confirmation`() {
        val config = GroupChatConfig(
            participants = listOf("pm", "designer", "engineer"),
            moderator = "pm"
        )
        val history = listOf(
            GroupChatMessage(speaker = "pm", content = "Opening note", round = 1),
            GroupChatMessage(speaker = "designer", content = "Design recommendation", round = 1),
            GroupChatMessage(speaker = "engineer", content = "Technical recommendation", round = 1)
        )

        val readiness = assessGroupChatTerminationReadiness(config, "engineer", history)

        assertFalse(readiness.canTerminate)
        assertTrue(readiness.reason!!.contains("Only moderator"))
    }

    @Test
    fun `termination readiness allows moderator after all participants spoke`() {
        val config = GroupChatConfig(
            participants = listOf("pm", "designer", "engineer"),
            moderator = "pm"
        )
        val history = listOf(
            GroupChatMessage(speaker = "pm", content = "Opening note", round = 1),
            GroupChatMessage(speaker = "designer", content = "Design recommendation", round = 1),
            GroupChatMessage(speaker = "engineer", content = "Technical recommendation", round = 1)
        )

        val readiness = assessGroupChatTerminationReadiness(config, "pm", history)

        assertTrue(readiness.canTerminate)
        assertTrue(readiness.missingParticipants.isEmpty())
    }
}
