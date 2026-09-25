package com.fartech.agents.workflow

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolFromCallable
import com.fartech.agents.jev.JEV_MISSING_CREDENTIALS_MESSAGE
import com.fartech.agents.jev.JevChoiceAnswer
import com.fartech.agents.jev.JevClient
import com.fartech.agents.jev.JevClientFactory
import com.fartech.agents.jev.JevClientSettings
import com.fartech.agents.jev.JevCredentials
import com.fartech.agents.jev.JevRequest
import com.fartech.agents.jev.JevResponse
import com.fartech.agents.jev.JevUsage
import com.fartech.agents.tools.SubAgentTools
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.jvm.isAccessible
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The `workflow` agent tool runs workflows in a nested [WorkflowExecutor]. That nested run
 * must keep the parent executor's Jev policy (host credentials, client factory, env-key
 * fallback) instead of the standalone defaults, which read `TYPESAFE_API_KEY` (spec §3.3).
 */
class JevWorkflowToolPolicyTest {

    @TempDir
    lateinit var tempDir: File

    private class RecordingJevClientFactory(
        private val handler: (JevRequest) -> JevResponse
    ) : JevClientFactory {
        val apiKeys: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val requests: MutableList<JevRequest> = Collections.synchronizedList(mutableListOf())

        override fun create(apiKey: String, settings: JevClientSettings, httpClient: HttpClient): JevClient {
            apiKeys += apiKey
            return object : JevClient {
                override suspend fun systemOne(request: JevRequest): JevResponse {
                    requests += request
                    return handler(request)
                }
            }
        }
    }

    @BeforeEach
    fun setUp() {
        WorkflowMonitor.clear()
    }

    @AfterEach
    fun tearDown() {
        WorkflowMonitor.clear()
    }

    // ==================== Fixtures ====================

    private fun parentExecutor(
        factory: JevClientFactory,
        credentials: JevCredentials?,
        envKeyFallback: Boolean = false
    ) = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = emptyList(),
        enableMonitoring = false,
        jevCredentials = credentials,
        jevClientFactory = factory,
        jevEnvKeyFallback = envKeyFallback
    )

    private fun agentWithTools(vararg tools: String) = AgentDefinition(
        tools = tools.toList(),
        overrides = mapOf("disable_skills" to JsonPrimitive("true"))
    )

    /** The registry an agent step of [this] executor gets (the real agent-runtime preparation path). */
    private fun WorkflowExecutor.agentToolRegistry(agent: AgentDefinition): ToolRegistry = runBlocking {
        val prepare = WorkflowExecutor::class.declaredFunctions.single { it.name == "prepareAgentRuntime" }
        prepare.isAccessible = true
        val runtime = prepare.callSuspendBy(
            mapOf(
                prepare.instanceParameter!! to this@agentToolRegistry,
                prepare.parameters.single { it.name == "agentDef" } to agent,
                prepare.parameters.single { it.name == "sessionId" } to "jev-policy-session"
            )
        )!!
        runtime.javaClass.getDeclaredField("toolRegistry").apply { isAccessible = true }.get(runtime) as ToolRegistry
    }

    private fun ToolRegistry.workflowTools(): WorkflowTools {
        assertEquals(1, tools.count { it.name == "executeWorkflow" }, "exactly one workflow tool set must be registered")
        return (getTool("executeWorkflow") as ToolFromCallable<*>).thisRef as WorkflowTools
    }

    private fun WorkflowTools.nestedExecutor(): WorkflowExecutor =
        WorkflowTools::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(this) as WorkflowExecutor

    private inline fun <reified T> WorkflowExecutor.privateField(name: String): T =
        WorkflowExecutor::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun jevClassifierWorkflowFile(): String {
        val file = File(tempDir, "nested-jev.yaml")
        file.writeText(
            """
            name: nested-jev
            version: 1.0.0
            agents: {}
            variables:
              ticket_text: "I was charged twice for the March invoice."
            workflow:
              - step: triage
                classifier:
                  input: "{{var:ticket_text}}"
                  categories:
                    - name: billing
                      description: "Payments, invoices, refunds"
                    - name: general
                      description: "Anything else"
                  output_variable: team
                  jev:
                    min_confidence: 0.5
            """.trimIndent()
        )
        return file.absolutePath
    }

    private fun billingResponse() = JevResponse(
        model = "jev-1.13.0",
        answers = mapOf(
            "team" to JevChoiceAnswer(
                choice = "billing",
                probabilities = mapOf("billing" to 0.9, "general" to 0.1),
                confidence = 0.9
            )
        ),
        usage = JevUsage(120, 8)
    )

    // ==================== Tests ====================

    @Test
    fun `workflow tool does not fall back to TYPESAFE_API_KEY when the parent disabled the env fallback`() = runBlocking {
        val factory = RecordingJevClientFactory { error("must not be called without a key") }
        val parent = parentExecutor(factory, credentials = null, envKeyFallback = false)

        val workflowTools = parent.agentToolRegistry(agentWithTools("workflow")).workflowTools()
        val nested = workflowTools.nestedExecutor()

        // The nested executor carries the parent's policy, not the standalone defaults
        // (jevEnvKeyFallback = true would read TYPESAFE_API_KEY from the process env).
        assertEquals(false, nested.privateField<Boolean>("jevEnvKeyFallback"))
        assertSame(factory, nested.privateField<JevClientFactory>("jevClientFactory"))

        val report = workflowTools.executeWorkflow(jevClassifierWorkflowFile())

        assertContains(report, "failed")
        assertContains(report, JEV_MISSING_CREDENTIALS_MESSAGE)
        assertTrue(factory.apiKeys.isEmpty(), "no Jev client may be created without a host or parameter key")
    }

    @Test
    fun `workflow tool runs nested jev steps with the parent's host credentials and client factory`() = runBlocking {
        val factory = RecordingJevClientFactory { billingResponse() }
        val hostCredentials = JevCredentials("ts-host-key", defaultModel = "jev-1.13.0")
        val parent = parentExecutor(factory, credentials = hostCredentials, envKeyFallback = false)

        val workflowTools = parent.agentToolRegistry(agentWithTools("workflow")).workflowTools()
        val report = workflowTools.executeWorkflow(jevClassifierWorkflowFile())

        assertContains(report, "completed successfully")
        assertContains(report, "team: billing")
        assertEquals(listOf("ts-host-key"), factory.apiKeys)
        assertEquals("jev-1.13.0", factory.requests.single().model, "host default model must reach the nested run")
        assertFalse(report.contains("ts-host-key"), "the tool report must never contain the API key")
    }

    @Test
    fun `sub agents of an agent with the workflow tool reuse the parent's host WorkflowTools`() {
        val parent = parentExecutor(RecordingJevClientFactory { billingResponse() }, credentials = JevCredentials("k"))
        val registry = parent.agentToolRegistry(agentWithTools("workflow", "sub_agent"))

        val workflowTools = registry.workflowTools()
        val subAgentTools = (registry.getTool("runSubAgent") as ToolFromCallable<*>).thisRef as SubAgentTools

        assertEquals(listOf<Any>(workflowTools), subAgentTools.hostToolSets)
    }

    @Test
    fun `agents without the workflow tool get no workflow tool set`() {
        val parent = parentExecutor(RecordingJevClientFactory { billingResponse() }, credentials = JevCredentials("k"))
        val registry = parent.agentToolRegistry(agentWithTools("sub_agent"))

        assertFalse(registry.tools.any { it.name == "executeWorkflow" })
        val subAgentTools = (registry.getTool("runSubAgent") as ToolFromCallable<*>).thisRef as SubAgentTools
        assertTrue(subAgentTools.hostToolSets.isEmpty())
    }
}
