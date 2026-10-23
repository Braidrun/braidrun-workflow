package com.fartech.agents.workflow

import com.fartech.agents.runner.WorkflowHostTest
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkflowArtifactBoundaryTest {
    @Test fun `typed file publication is observed before output publish and stdout claims are ignored`(@TempDir dir: Path) = runBlocking<Unit> {
        val base = WorkflowHostTest.Host(dir); val output = dir.resolve("declared.txt"); val order = mutableListOf<String>()
        val host = object : WorkflowHost by base {
            override val subprocessExecutor = NativeSubprocessExecutor()
            override suspend fun collectDeclaredArtifacts(workflow: WorkflowDefinition, step: WorkflowStep, scope: WorkflowHostScope, candidates: List<WorkflowArtifactCandidate>) {
                assertEquals("root", scope.executionId); assertEquals("produce", scope.stepName)
                assertEquals(listOf(WorkflowArtifactCandidate(output.toString(), WorkflowArtifactCandidate.Declaration.PUBLISHED_FILE)), candidates)
                assertEquals("actual file", Files.readString(output)); order += "collect"
            }
            override val outputPublisher = object : WorkflowOutputPublisher {
                override suspend fun publish(output: PublishedWorkflowOutputPayload) { order += "publish" }
            }
        }
        val workflow = base.workflow("file-declaration").copy(workflow = listOf(WorkflowStep("produce",
            code = CodeStepConfig("bash", "printf 'actual file' > '$output'; printf 'claimed-file=/private/ignore'"),
            publishOutputs = listOf(PublishOutputSpec("declared", "file", output.toString())))))
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
        assertTrue(result.success, result.error); assertEquals(listOf("collect", "publish"), order)
    }
    @Test fun `state machine code completion keeps inner producer step before outer boundary`(@TempDir dir: Path) = runBlocking<Unit> {
        val base = WorkflowHostTest.Host(dir); val steps = mutableListOf<String>()
        val host = object : WorkflowHost by base {
            override val subprocessExecutor = NativeSubprocessExecutor()
            override suspend fun collectDeclaredArtifacts(workflow: WorkflowDefinition, step: WorkflowStep, scope: WorkflowHostScope, candidates: List<WorkflowArtifactCandidate>) {
                assertEquals("root", scope.executionId); assertEquals(step.step, scope.stepName); assertTrue(candidates.isEmpty()); steps += step.step
            }
        }
        val workflow = WorkflowParser.parseYaml("""
            name: artifact-state
            agents: {}
            workflow:
              - step: flow
                state_machine:
                  initial_state: produce
                  final_states: [produce]
                  states:
                    produce:
                      name: produce
                      step:
                        code:
                          language: bash
                          script: 'printf state-proof'
        """.trimIndent()).copy(directoryIsolation = base.isolation())
        val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
        assertTrue(result.success, result.error); assertEquals(listOf("flow.produce", "flow"), steps)
    }
}
