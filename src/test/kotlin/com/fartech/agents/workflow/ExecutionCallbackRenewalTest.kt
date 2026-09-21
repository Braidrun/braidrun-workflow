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
}
