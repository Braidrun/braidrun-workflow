package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateMachineStepIntegrationTest {

    @BeforeEach
    fun setup() {
        WorkflowMonitor.clear()
    }

    @Test
    fun `state_machine step executes inside dag and exposes final state`() = runBlocking {
        val workflow = WorkflowDefinition(
            name = "state-machine-dag",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "review_flow",
                    stateMachine = StateMachineConfig(
                        states = mapOf(
                            "classify" to StateDefinition(
                                name = "classify",
                                stepConfig = StateStepConfig(
                                    code = CodeStepConfig(language = "bash", script = "echo decision=approved"),
                                    extract = listOf(ExtractConfig(pattern = "decision=(\\w+)", variable = "decision"))
                                ),
                                transitions = listOf(
                                    StateTransition(event = "complete", target = "approved", condition = "decision == approved"),
                                    StateTransition(event = "complete", target = "needs_changes", condition = "decision == revise")
                                )
                            ),
                            "approved" to StateDefinition(name = "approved"),
                            "needs_changes" to StateDefinition(name = "needs_changes")
                        ),
                        initialState = "classify",
                        finalStates = listOf("approved", "needs_changes")
                    )
                ),
                WorkflowStep(
                    step = "summarize",
                    dependsOn = listOf("review_flow"),
                    code = CodeStepConfig(
                        language = "bash",
                        script = """
                            echo "state=${'$'}WF_VAR_REVIEW_FLOW_FINAL_STATE|output=${'$'}STEP_OUTPUT_REVIEW_FLOW|inner=${'$'}WF_VAR_REVIEW_FLOW_CLASSIFY_OUTPUT"
                        """.trimIndent()
                    )
                )
            )
        )

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = true
        )
        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertEquals("approved", result.variables["review_flow_final_state"])
        assertEquals("decision=approved", result.variables["review_flow_classify_output"])
        assertTrue(result.stepResults["summarize"]!!.output!!.contains("state=approved"))
        assertTrue(result.stepResults["summarize"]!!.output!!.contains("inner=decision=approved"))
    }

    @Test
    fun `state_machine step can recover via failure transition`() = runBlocking {
        val workflow = WorkflowDefinition(
            name = "state-machine-recovery",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "repair_flow",
                    stateMachine = StateMachineConfig(
                        states = mapOf(
                            "start" to StateDefinition(
                                name = "start",
                                stepConfig = StateStepConfig(
                                    code = CodeStepConfig(language = "bash", script = "echo boom >&2 && exit 7")
                                ),
                                transitions = listOf(
                                    StateTransition(event = "error", target = "recover")
                                )
                            ),
                            "recover" to StateDefinition(
                                name = "recover",
                                stepConfig = StateStepConfig(
                                    code = CodeStepConfig(language = "bash", script = "echo recovered=true"),
                                    extract = listOf(ExtractConfig(pattern = "recovered=(\\w+)", variable = "recovered"))
                                ),
                                transitions = listOf(
                                    StateTransition(event = "complete", target = "done")
                                )
                            ),
                            "done" to StateDefinition(name = "done")
                        ),
                        initialState = "start",
                        finalStates = listOf("done")
                    )
                )
            )
        )

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = true
        )
        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertEquals("done", result.variables["repair_flow_final_state"])
        assertEquals("recovered=true", result.stepResults["repair_flow"]!!.output)
        assertEquals("recovered=true", result.variables["repair_flow_last_output"])
        assertEquals("true", result.variables["recovered"])
    }

    @Test
    fun `state_machine executes final state step before completion`() = runBlocking {
        val workflow = WorkflowDefinition(
            name = "state-machine-final-step",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "health_flow",
                    stateMachine = StateMachineConfig(
                        states = mapOf(
                            "evaluate" to StateDefinition(
                                name = "evaluate",
                                stepConfig = StateStepConfig(
                                    code = CodeStepConfig(language = "bash", script = "echo health_status=watch"),
                                    extract = listOf(ExtractConfig(pattern = "health_status=(\\w+)", variable = "health_status"))
                                ),
                                transitions = listOf(
                                    StateTransition(event = "complete", target = "watch_done", condition = "health_status == watch")
                                )
                            ),
                            "watch_done" to StateDefinition(
                                name = "watch_done",
                                stepConfig = StateStepConfig(
                                    code = CodeStepConfig(
                                        language = "bash",
                                        script = """
                                            echo "health_final_status=${'$'}WF_VAR_HEALTH_STATUS"
                                            echo "action_taken=logged_for_observation"
                                        """.trimIndent()
                                    )
                                )
                            )
                        ),
                        initialState = "evaluate",
                        finalStates = listOf("watch_done")
                    )
                )
            )
        )

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = true
        )
        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertEquals("watch_done", result.variables["health_flow_final_state"])
        assertEquals(
            "health_final_status=watch\naction_taken=logged_for_observation",
            result.stepResults["health_flow"]!!.output
        )
        assertEquals(
            "health_final_status=watch\naction_taken=logged_for_observation",
            result.variables["health_flow_last_output"]
        )
        assertEquals(
            "health_final_status=watch\naction_taken=logged_for_observation",
            result.variables["health_flow_watch_done_output"]
        )
    }

    @Test
    fun `parser validates state_machine transition target`() {
        val yaml = """
            name: invalid-state-machine
            version: 1.0.0
            workflow:
              - step: review_flow
                state_machine:
                  initial_state: classify
                  final_states: [done]
                  states:
                    classify:
                      name: classify
                      step:
                        code:
                          language: bash
                          script: "echo ok"
                      transitions:
                        - event: complete
                          target: missing
                    done:
                      name: done
        """.trimIndent()

        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }
}
