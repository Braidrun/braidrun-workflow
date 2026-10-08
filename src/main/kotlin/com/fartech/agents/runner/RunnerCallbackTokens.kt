package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowHostScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

class RunnerCallbackTokens(private val ipc: RunnerIpc, private val gate: RunnerEnvironmentGate, private val secrets: RunnerSecrets) {
    fun mint(scope: WorkflowHostScope, budget: Long): String = runBlocking {
        gate.check()
        require(budget in 1..3500) { "Callback budget needs renewable controls" }
        val id = UUID.randomUUID().toString()
        val operations = setOf("artifact.read", "artifact.publish", "output.read", "output.publish", "interaction.request")
        val response = ipc.request("callbackTokenRequest", buildJsonObject {
            put("requestId", id); put("childExecutionPath", childPath(scope.rootExecutionId, scope.executionId))
            put("operations", JsonArray(operations.sorted().map(::JsonPrimitive)))
            put("requestedTtlSeconds", budget + 60); put("operationBudgetSeconds", budget)
        }, "callbackTokenGrant")
        secrets.register(response.getValue("token"))
        require(response.string("requestId") == id && response.array("operations").map { it.jsonPrimitive.content }.toSet() == operations &&
            Instant.parse(response.string("expiresAt")) >= Instant.now().plusSeconds(budget)) { "Callback grant insufficient or exceeds controls" }
        gate.check(); response.string("token")
    }
}
