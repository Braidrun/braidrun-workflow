package com.fartech.agents.workflow

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolFromCallable
import com.fartech.agents.commons.parseExactToolSet
import com.fartech.agents.tools.ClaudeCredentialProvider
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
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
 * The agent `workflow` tool runs workflows in a nested [WorkflowExecutor] inside the host JVM.
 * On the web that JVM is the server itself, so the nested executor must inherit the host's
 * sandboxed code step executor (and the rest of its runtime) — otherwise a `code:` step in a
 * YAML the agent wrote runs with a bare ProcessBuilder on the server. Executors that have no
 * sandbox at all (e.g. registries built outside any executor, like the web assistant's) must
 * refuse `code:` steps once the host declared [WorkflowHostPolicy.requireCodeStepExecutor].
 */
class NestedWorkflowSandboxTest {

    @TempDir
    lateinit var tempDir: File

    /** Records requests instead of running anything, like a Docker executor would from the host's view. */
    private class RecordingSandbox : SubprocessExecutor {
        val requests: MutableList<SubprocessExecutor.ExecRequest> = Collections.synchronizedList(mutableListOf())
        val startEnvironments: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

        override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
            requests += request
            request.environmentAtStart?.let { startEnvironments += it() }
            return SubprocessExecutor.ExecResult(exitCode = 0, stdout = "sandboxed-ok", stderr = "", durationMs = 1)
        }
    }

    private class NoopCredentialProvider : ClaudeCredentialProvider {
        override suspend fun acquire(excludedCredentialIds: Set<String>): ClaudeCredentialProvider.Credential? = null
        override suspend fun markRateLimited(credential: ClaudeCredentialProvider.Credential, resetAtMillis: Long) = Unit
        override suspend fun markSucceeded(
            credential: ClaudeCredentialProvider.Credential,
            executionId: String?,
            stepName: String?
        ) = Unit
    }

    @BeforeEach
    fun setUp() {
        WorkflowMonitor.clear()
        WorkflowHostPolicy.resetForTests()
    }

    @AfterEach
    fun tearDown() {
        WorkflowMonitor.clear()
        WorkflowHostPolicy.resetForTests()
    }

    // ==================== Fixtures ====================

    private val markerFile get() = File(tempDir, "ran-on-host.marker")

    /** A bash script that leaves [markerFile] behind only if it really runs on this machine. */
    private fun markerScript() = "touch '${markerFile.absolutePath}'"

    private fun codeStepWorkflowFile(): String {
        val file = File(tempDir, "nested-code.yaml")
        file.writeText(
            """
            name: nested-code
            version: 1.0.0
            agents: {}
            workflow:
              - step: escape
                code:
                  language: bash
                  script: "${markerScript()}"
            """.trimIndent()
        )
        return file.absolutePath
    }

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
                prepare.parameters.single { it.name == "sessionId" } to "nested-sandbox-session"
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

    // ==================== Inheritance ====================

    @Test
    fun `workflow tool's nested executor inherits the host executor's runtime`() {
        val sandbox = RecordingSandbox()
        val proxyEnv = mapOf("HTTPS_PROXY" to "http://egress-proxy:3128")
        val tokenProvider: (Long) -> String = { "fresh-token" }
        val resolver = InMemoryWorkflowResolver()
        val claude = NoopCredentialProvider()
        val codex = NoopCredentialProvider()
        val rotationSink: (String?, String) -> Unit = { _, _ -> }
        val host = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            workflowResolver = resolver,
            codeStepExecutor = sandbox,
            extraCodeStepEnv = proxyEnv,
            claudeCredentialProvider = claude,
            codexCredentialProvider = codex,
            onCodexAuthJsonRotated = rotationSink,
            executionApiTokenProvider = tokenProvider
        )

        val nested = host.agentToolRegistry(agentWithTools("workflow")).workflowTools().nestedExecutor()

        assertSame(sandbox, nested.privateField<SubprocessExecutor?>("codeStepExecutor"))
        assertEquals(proxyEnv, nested.privateField<Map<String, String>>("extraCodeStepEnv"))
        assertSame(resolver, nested.privateField<WorkflowResolver?>("workflowResolver"))
        assertSame(claude, nested.privateField<ClaudeCredentialProvider?>("claudeCredentialProvider"))
        assertSame(codex, nested.privateField<ClaudeCredentialProvider?>("codexCredentialProvider"))
        assertSame(rotationSink, nested.privateField<Any?>("onCodexAuthJsonRotated"))
        assertSame(tokenProvider, nested.privateField<Any?>("executionApiTokenProvider"))
    }

    @Test
    fun `nested code steps run through the host sandbox with its proxy env and a freshly minted callback token`() = runBlocking {
        // The web configuration: the host requires a sandbox and injects one.
        WorkflowHostPolicy.requireCodeStepExecutor()
        val sandbox = RecordingSandbox()
        val host = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = sandbox,
            extraCodeStepEnv = mapOf("HTTPS_PROXY" to "http://egress-proxy:3128"),
            executionApiTokenProvider = { budgetSeconds -> "fresh-token-$budgetSeconds" }
        )
        val workflowTools = host.agentToolRegistry(agentWithTools("workflow")).workflowTools()

        val report = workflowTools.executeWorkflow(codeStepWorkflowFile())

        assertContains(report, "completed successfully")
        assertFalse(markerFile.exists(), "the nested code step must not run on the host")
        val request = sandbox.requests.single()
        assertContains(request.command.last(), "escape")
        assertEquals("http://egress-proxy:3128", request.env["HTTPS_PROXY"])
        assertEquals("fresh-token-30", sandbox.startEnvironments.single()["WF_API_TOKEN"])
    }

    @Test
    fun `nested sub_workflow steps resolve through the host resolver and stay sandboxed`() = runBlocking {
        val sandbox = RecordingSandbox()
        val child = WorkflowDefinition(
            name = "child",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(step = "child_code", code = CodeStepConfig(language = "bash", script = markerScript()))
            )
        )
        val host = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            workflowResolver = InMemoryWorkflowResolver(byId = mapOf("child-1" to child)),
            codeStepExecutor = sandbox
        )
        val parentYaml = File(tempDir, "nested-parent.yaml").apply {
            writeText(
                """
                name: nested-parent
                version: 1.0.0
                agents: {}
                workflow:
                  - step: call_child
                    sub_workflow:
                      workflow_id: child-1
                """.trimIndent()
            )
        }
        val workflowTools = host.agentToolRegistry(agentWithTools("workflow")).workflowTools()

        val report = workflowTools.executeWorkflow(parentYaml.absolutePath)

        assertContains(report, "completed successfully")
        assertEquals(1, sandbox.requests.size, "the child's code step must go through the host sandbox")
        assertFalse(markerFile.exists())
    }

    // ==================== Fail-closed host policy ====================

    @Test
    fun `under the host policy an executor without a code step executor refuses code steps`() = runBlocking {
        WorkflowHostPolicy.requireCodeStepExecutor()
        val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)

        val result = executor.execute(WorkflowParser.parseFile(codeStepWorkflowFile()))

        assertFalse(result.success)
        assertContains(result.error.orEmpty(), "requires a sandboxed code step executor")
        assertFalse(markerFile.exists(), "a refused code step must not run on the host")
    }

    @Test
    fun `under the host policy a workflow tool built outside any executor refuses code steps`() = runBlocking {
        // How the web assistant gets its `workflow` tool: a registry assembled without a host
        // executor, so parseToolSet builds a default WorkflowTools with no sandbox to inherit.
        WorkflowHostPolicy.requireCodeStepExecutor()
        val registry = parseExactToolSet(
            parameters = listOf(ConfigurationParameter("subprocess_mode", JsonPrimitive("docker"))),
            httpAccess = HttpAccess(),
            tools = listOf("workflow"),
            skillManager = null
        )

        val report = registry.workflowTools().executeWorkflow(codeStepWorkflowFile())

        assertContains(report, "failed")
        assertContains(report, "requires a sandboxed code step executor")
        assertFalse(markerFile.exists(), "a refused code step must not run on the host")
    }

    @Test
    fun `without the host policy a standalone executor keeps running code steps directly`() = runBlocking {
        // CLI / library default is unchanged.
        val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)

        val result = executor.execute(WorkflowParser.parseFile(codeStepWorkflowFile()))

        assertTrue(result.success, "error=${result.error}")
        assertTrue(markerFile.exists())
    }
}
