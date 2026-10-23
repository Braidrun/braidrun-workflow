package com.fartech.agents.workflow

import com.fartech.agents.runner.WorkflowHostTest
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.resourceAdmissionWait
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkflowLateCodeControlTest {
    private val noTokenScript = """
        set -eu
        test -z "${'$'}{WF_API_TOKEN+x}"
        test -z "${'$'}{WF_VAR_WF_API_TOKEN+x}"
        test -z "${'$'}{WF_VAR_EXECUTION_API_TOKEN+x}"
        test "${'$'}{#WF_VAR_LARGE}" -gt 100000
        printf 'per-call-ready:%s\n' "${'$'}WF_EXECUTION_ID"
    """.trimIndent()

    @Test fun `7000 root and 7200 static child keep budgets and clear inherited authority before scripts and spills`(@TempDir dir: Path) = runBlocking<Unit> {
        val oldToken = dir.resolve("old-callback-token"); Files.writeString(oldToken, "STALE_readable_file_secret")
        val aliases = listOf("WF_API_TOKEN__FILE", "WF_VAR_WF_API_TOKEN__FILE", "WF_VAR_EXECUTION_API_TOKEN__FILE")
        val base = WorkflowHostTest.Host(dir); val prepared = mutableListOf<Pair<WorkflowHostScope, Long>>()
        val budgets = mutableListOf<Long>(); var minted = 0; val spilledScopes = mutableSetOf<String>()
        val native = NativeSubprocessExecutor()
        val processes = object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                budgets += request.timeoutSeconds
                assertNotNull(request.environmentAtStart)
                assertEquals(budgets.size - 1, prepared.size, "Prepare cannot run before resource admission")
                assertFalse(request.env.keys.any(WorkflowCodeControlTokens::reserved))
                assertFalse(request.env.values.any { "STALE_" in it || oldToken.toString() in it })
                assertTrue(request.env.containsKey("WF_VAR_LARGE__FILE"), "Ordinary business spill must stay enabled")
                spilledScopes += request.env.getValue("WF_EXECUTION_ID")
                Files.walk(request.workingDir.toPath()).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    val text = Files.readString(path); assertFalse("STALE_" in text, "Callback material in script/spill $path")
                    assertFalse(oldToken.toString() in text, "Old file pointer entered script/spill")
                } }
                resourceAdmissionWait { delay(10) }
                return native.execute(request)
            }
        }
        val host = object : WorkflowHost by base {
            override val codeControlMode = WorkflowCodeControlMode.SUPERVISOR_PER_CALL
            override val subprocessExecutor = processes
            override val callbackToken: ((Long) -> String) = { minted++; error("No whole-step mint in per-call mode") }
            override val codeEnvironment = mapOf("WF_API_TOKEN" to "STALE_overlay", "wf_var_execution_api_token" to "STALE_alias", "CONFIG" to """{"WF_API_TOKEN":"STALE_json","safe":"kept"}""") + aliases.associateWith { oldToken.toString() }
            override fun prepareCodeStepEnvironment(scope: WorkflowHostScope, timeoutSeconds: Long): Map<String, String> {
                prepared += scope to timeoutSeconds
                return mapOf("WF_API_TOKEN" to "STALE_prepare", "WF_VAR_WF_API_TOKEN_FILE" to "STALE_file", "SAFE_PREPARED" to "yes") + aliases.associateWith { oldToken.toString() }
            }
        }
        base.child = WorkflowDefinition("late-child", agents = emptyMap(), directoryIsolation = base.isolation(),
            variables = mapOf("WF_API_TOKEN" to "STALE_child", "execution_api_token" to "STALE_child_param", "large" to "large-proof-".repeat(20000)) + aliases.associateWith { oldToken.toString() },
            workflow = listOf(WorkflowStep("child-code", code = CodeStepConfig("bash", noTokenScript, timeout = 7200))))
        val root = WorkflowDefinition("late-root", agents = emptyMap(), directoryIsolation = base.isolation(),
            variables = mapOf("WF_API_TOKEN" to "STALE_variable", "nested" to """{"execution_api_token":"STALE_nested","safe":"kept"}""", "large" to "large-proof-".repeat(20000)) + aliases.associateWith { oldToken.toString() },
            workflow = listOf(WorkflowStep("root-code", code = CodeStepConfig("bash", noTokenScript, timeout = 7000)),
                WorkflowStep("nested", subWorkflow = SubWorkflowConfig(name = "late-child", inputs = mapOf("execution_api_token" to "STALE_input")))))
        val executor = WorkflowExecutor(HttpAccess(), listOf(ConfigurationParameter("execution_api_token", JsonPrimitive("STALE_base")),
            ConfigurationParameter("WF_API_TOKEN", JsonPrimitive("STALE_direct"))) + aliases.map { ConfigurationParameter(it, JsonPrimitive(oldToken.toString())) }, host = host)
        val result = executor.execute(root, mapOf("WF_API_TOKEN" to "STALE_initial") + aliases.associateWith { oldToken.toString() })
        assertTrue(result.success, result.error); assertEquals(0, minted)
        val dynamic = WorkflowTools(HttpAccess(), listOf(ConfigurationParameter("execution_api_token", JsonPrimitive("STALE_dynamic"))) + aliases.map { ConfigurationParameter(it, JsonPrimitive(oldToken.toString())) },
            NestedWorkflowRuntime(host = host), WorkflowHostScope("root", "root", stepName = "tool"))
        assertTrue(dynamic.executeWorkflow("authorized.yaml").contains("success", true))
        assertEquals(0, minted)
        assertEquals(listOf(7000L, 7200L, 7200L), budgets); assertEquals(budgets, prepared.map { it.second })
        assertEquals(listOf("root", "root__nested__call_1"), prepared.take(2).map { it.first.executionId })
        assertTrue(prepared.last().first.executionId.startsWith("root__tool_dynamic_"))
        assertEquals(listOf("root-code", "child-code", "child-code"), prepared.map { it.first.stepName })
        assertEquals(prepared.map { it.first.executionId }.toSet(), spilledScopes)
        assertTrue(base.checkpoints.none { it.state.variables.toString().contains("STALE_") })
    }

    @Test fun `cancel during resource wait prevents prepare and any launch without clipping timeout`(@TempDir dir: Path) = runBlocking<Unit> {
        val base = WorkflowHostTest.Host(dir); val waiting = CompletableDeferred<Unit>(); var prepared = false; var launched = false
        val host = object : WorkflowHost by base {
            override val codeControlMode = WorkflowCodeControlMode.SUPERVISOR_PER_CALL
            override fun prepareCodeStepEnvironment(scope: WorkflowHostScope, timeoutSeconds: Long): Map<String, String> { prepared = true; return emptyMap() }
            override val subprocessExecutor = object : SubprocessExecutor {
                override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                    assertEquals(7200, request.timeoutSeconds)
                    resourceAdmissionWait { waiting.complete(Unit); awaitCancellation() }
                    launched = true
                    return NativeSubprocessExecutor().execute(request)
                }
            }
        }
        val workflow = base.workflow("waiting").copy(workflow = listOf(WorkflowStep("wait", code = CodeStepConfig("bash", "true", timeout = 7200))))
        val task = launch { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow) }
        withTimeout(2000) { waiting.await() }; task.cancelAndJoin()
        assertTrue(task.isCancelled); assertFalse(prepared); assertFalse(launched)
    }

    @Test fun `cancel after prepare reaches the actual native process with original long timeout`(@TempDir dir: Path) = runBlocking<Unit> {
        val base = WorkflowHostTest.Host(dir); val pidFile = dir.resolve("pid"); var prepared = false
        val host = object : WorkflowHost by base {
            override val codeControlMode = WorkflowCodeControlMode.SUPERVISOR_PER_CALL
            override fun prepareCodeStepEnvironment(scope: WorkflowHostScope, timeoutSeconds: Long): Map<String, String> {
                assertEquals(7000, timeoutSeconds); prepared = true; return emptyMap()
            }
            override val subprocessExecutor = NativeSubprocessExecutor()
        }
        val workflow = base.workflow("cancel-live").copy(workflow = listOf(WorkflowStep("live", code = CodeStepConfig("bash", "printf '%s' \"\$\$\" > '$pidFile'; exec sleep 30", timeout = 7000))))
        val task = launch { WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow) }
        var pid: Long? = null
        try {
            withTimeout(5000) { while (!Files.exists(pidFile) || Files.size(pidFile) == 0L) delay(20) }
            pid = Files.readString(pidFile).toLong(); assertTrue(prepared)
            assertTrue(ProcessHandle.of(pid).get().isAlive)
            task.cancelAndJoin()
            withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20) }
            assertTrue(task.isCancelled)
        } finally { task.cancelAndJoin(); pid?.let { ProcessHandle.of(it).ifPresent { p -> if (p.isAlive) p.destroyForcibly() } } }
    }

    @Test fun `actual double underscore spill aliases are removed from every shared sanitization entry`() {
        val aliases = listOf("WF_API_TOKEN__FILE", "WF_VAR_WF_API_TOKEN__FILE", "WF_VAR_EXECUTION_API_TOKEN__FILE", "execution_api_token__FILE")
        aliases.forEach { assertTrue(WorkflowCodeControlTokens.reserved(it)) }
        val parameters = aliases.map { ConfigurationParameter(it, JsonPrimitive("old-file")) }
        assertTrue(WorkflowCodeControlTokens.parameters(parameters).isEmpty())
        val variables = aliases.associateWith { "old-file" as Any }.toMutableMap()
        WorkflowCodeControlTokens.variables(variables); assertTrue(variables.isEmpty())
        assertTrue(WorkflowCodeControlTokens.environment(aliases.associateWith { "old-file" }).isEmpty())
        assertFalse(WorkflowCodeControlTokens.reserved("WF_VAR_BUSINESS__FILE"))
    }

    @Test fun `Python and Node actual spill bridges keep safe data without restoring old callback file pointers`(@TempDir dir: Path) = runBlocking<Unit> {
        val old = dir.resolve("old-token"); Files.writeString(old, "STALE_bridge_secret")
        val aliases = listOf("WF_API_TOKEN__FILE", "WF_VAR_WF_API_TOKEN__FILE", "WF_VAR_EXECUTION_API_TOKEN__FILE")
        val scripts = mapOf(
            "python" to "import os; assert all(os.environ.get(k) is None for k in ['WF_API_TOKEN','WF_VAR_WF_API_TOKEN','WF_VAR_EXECUTION_API_TOKEN']); assert len(os.environ['WF_VAR_LARGE']) > 100000; print('bridge-proof')",
            "javascript" to "for (const k of ['WF_API_TOKEN','WF_VAR_WF_API_TOKEN','WF_VAR_EXECUTION_API_TOKEN']) if(process.env[k] !== undefined) throw Error('old authority'); if(process.env.WF_VAR_LARGE.length < 100000) throw Error('spill disabled'); console.log('bridge-proof');"
        )
        for ((language, script) in scripts) {
            val base = WorkflowHostTest.Host(dir.resolve(language))
            val host = object : WorkflowHost by base {
                override val codeControlMode = WorkflowCodeControlMode.SUPERVISOR_PER_CALL
                override val subprocessExecutor = NativeSubprocessExecutor()
                override val codeEnvironment = aliases.associateWith { old.toString() }
                override fun prepareCodeStepEnvironment(scope: WorkflowHostScope, timeoutSeconds: Long) = aliases.associateWith { old.toString() }
            }
            val workflow = base.workflow("bridge-$language").copy(variables = mapOf("large" to "safe-data".repeat(30000)),
                workflow = listOf(WorkflowStep("run", code = CodeStepConfig(language, script, timeout = 7200))))
            val result = WorkflowExecutor(HttpAccess(), emptyList(), host = host).execute(workflow)
            assertTrue(result.success, result.error); assertTrue(result.stepResults.getValue("run").output.orEmpty().contains("bridge-proof"))
        }
    }

    @Test fun `parameter sanitation keeps business values and removes nested reserved authority`() {
        val clean = WorkflowCodeControlTokens.parameters(listOf(ConfigurationParameter("WF_API_TOKEN", JsonPrimitive("old")),
            ConfigurationParameter("settings", buildJsonObject { put("execution_api_token", "old"); put("safe", "unchanged"); put("encoded", """{"WF_API_TOKEN":"old","value":42}""") })))
        assertEquals(listOf("settings"), clean.map { it.key }); assertEquals("unchanged", clean.single().value.jsonObject.getValue("safe").jsonPrimitive.content)
        assertEquals("""{"value":42}""", clean.single().value.jsonObject.getValue("encoded").jsonPrimitive.content)
    }
}
