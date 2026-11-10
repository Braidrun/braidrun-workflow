package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import com.fartech.agents.tools.exec.SubprocessExecutor
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class RunnerClaudeApiPolicyTest {
    private val selected = mapOf(
        "external_agent_claude_auth_mode" to JsonPrimitive("api_key"),
        "external_agent_claude_base_url" to JsonPrimitive("https://open.bigmodel.cn/api/anthropic"),
        "external_agent_claude_model" to JsonPrimitive("glm-5.3"),
        "anthropic_api_key" to JsonPrimitive("credential:glm@1"))
    private fun inspect(parameters:Map<String,JsonElement>, capabilities:Set<String> = RunnerHostSession.CAPABILITIES):Set<String> {
        val agent=AgentDefinition(type="claude_code_agent",overrides=parameters)
        val wf=WorkflowDefinition("glm",agents=mapOf("a" to agent),workflow=listOf(WorkflowStep("turn",agent="a",input="test")))
        return RunnerAdmission(NullWorkflowResolver,capabilities).inspect(wf)
    }
    @Test fun `only approved provider model and leased key admit with an explicit runtime capability`() {
        assertContains(inspect(selected),RunnerClaudeApiPolicy.CAPABILITY)
        assertFailsWith<IllegalArgumentException>{inspect(selected,RunnerHostSession.CAPABILITIES-RunnerClaudeApiPolicy.CAPABILITY)}
        for((key,value) in listOf("external_agent_claude_base_url" to "https://other.example/anthropic",
            "external_agent_claude_base_url" to "https://open.bigmodel.cn/api/anthropic?redirect=bad",
            "external_agent_claude_model" to "opus", "anthropic_api_key" to "inline-secret",
            "external_agent_claude_oauth_token" to "credential:claude@1", "external_agent_claude_config_dir" to "/tmp/ambient"))
            assertFailsWith<IllegalArgumentException>(key){inspect(selected+(key to JsonPrimitive(value)))}
        assertFailsWith<IllegalArgumentException>{inspect(selected-"anthropic_api_key")}
    }
    @Test fun `auxiliary models and refreshed key stay on the selected provider without OAuth fallback`() {
        val request=SubprocessExecutor.ExecRequest(listOf("claude","--model","glm-5.3"),File("."),
            env=mapOf("ANTHROPIC_BASE_URL" to "https://open.bigmodel.cn/api/anthropic","ANTHROPIC_API_KEY" to "synthetic-old","CLAUDE_CODE_OAUTH_TOKEN" to "synthetic-ambient"),
            environmentAtStart={mapOf("ANTHROPIC_API_KEY" to "synthetic-fresh")})
        val mapped=RunnerClaudeApiPolicy.request(request)
        assertEquals("glm-5.3",mapped.env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("",mapped.env["CLAUDE_CODE_OAUTH_TOKEN"])
        val start=mapped.environmentAtStart!!.invoke()
        assertEquals("synthetic-fresh",start["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("",start["ANTHROPIC_API_KEY"])
        assertFailsWith<IllegalArgumentException>{RunnerClaudeApiPolicy.request(request.copy(command=listOf("claude","--model","opus")))}
        assertFailsWith<IllegalArgumentException>{RunnerClaudeApiPolicy.request(request.copy(environmentAtStart={mapOf("ANTHROPIC_BASE_URL" to "https://other.example")})).environmentAtStart!!.invoke()}
    }
}
