package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExecutionCallbackRenewalTest {
    @TempDir lateinit var root: Path

    @Test fun `later code steps obtain fresh callbacks rather than the expired startup token`() = runBlocking {
        val budgets = mutableListOf<Long>()
        val executor = WorkflowExecutor(HttpAccess(), listOf(
            ConfigurationParameter("working_dir", JsonPrimitive(root.toString())),
            ConfigurationParameter("execution_api_token", JsonPrimitive("expired-startup"))
        ), enableMonitoring = false, executionApiTokenProvider = { timeout ->
            budgets += timeout
            "fresh-${budgets.size}"
        })
        val workflow = WorkflowDefinition(name="renew", agents=emptyMap(), workflow=listOf(
            WorkflowStep(step="first", code=CodeStepConfig(language="bash",script="test \"\$WF_API_TOKEN\" = fresh-1",timeout=10)),
            WorkflowStep(step="after-wait", code=CodeStepConfig(language="bash",script="test \"\$WF_API_TOKEN\" = fresh-2",timeout=20))
        ))
        val result = executor.execute(workflow)
        assertTrue(result.success, result.error)
        assertEquals(listOf(10L,20L), budgets)
    }

    @Test fun `renewal refusal fails closed instead of reusing startup credential`() = runBlocking {
        val marker = root.resolve("ran")
        val executor = WorkflowExecutor(HttpAccess(),listOf(ConfigurationParameter("execution_api_token",JsonPrimitive("old-token"))),
            enableMonitoring=false,executionApiTokenProvider={ error("Owner no longer active") })
        val result = executor.execute(WorkflowDefinition(name="denied",agents=emptyMap(),workflow=listOf(
            WorkflowStep(step="blocked",code=CodeStepConfig(language="bash",script="touch '${marker}'"))
        )))
        assertFalse(result.success)
        assertFalse(marker.toFile().exists())
    }
    @Test fun `callback is minted after resource wait and wait does not spend workflow or step budget`() = runBlocking {
        var admitted = false
        var calls = 0
        val native = com.fartech.agents.tools.exec.NativeSubprocessExecutor()
        val waitingExecutor = object : com.fartech.agents.tools.exec.SubprocessExecutor {
            override suspend fun execute(request: com.fartech.agents.tools.exec.SubprocessExecutor.ExecRequest): com.fartech.agents.tools.exec.SubprocessExecutor.ExecResult {
                assertFalse(request.env.containsKey("WF_API_TOKEN"))
                assertNotNull(request.environmentAtStart)
                com.fartech.agents.tools.exec.resourceAdmissionWait { kotlinx.coroutines.delay(1200) }
                admitted = true
                return native.execute(request)
            }
        }
        val executor = WorkflowExecutor(HttpAccess(), listOf(
            ConfigurationParameter("working_dir", JsonPrimitive(root.toString())),
            ConfigurationParameter("execution_api_token", JsonPrimitive("expired-startup"))
        ), enableMonitoring = false, codeStepExecutor = waitingExecutor, executionApiTokenProvider = {
            assertTrue(admitted, "must not mint a short-lived token before admission")
            calls++
            "after-admission"
        })
        val result = executor.execute(WorkflowDefinition(name="resource-wait", agents=emptyMap(),
            timeout=TimeoutConfig(total="1s", perStep="1s"), workflow=listOf(
                WorkflowStep(step="first", code=CodeStepConfig(language="bash", script="test \"\$WF_API_TOKEN\" = after-admission", timeout=2)),
                WorkflowStep(step="second", code=CodeStepConfig(language="bash", script="test \"\$WF_API_TOKEN\" = after-admission", timeout=2))
            )))
        assertTrue(result.success, result.error)
        assertEquals(2, calls)
    }

}
