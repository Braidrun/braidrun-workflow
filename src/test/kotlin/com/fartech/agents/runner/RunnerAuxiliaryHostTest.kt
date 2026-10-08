package com.fartech.agents.runner

import ai.koog.agents.core.tools.ToolRegistry
import com.fartech.agents.commons.*
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.workflow.*
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RunnerAuxiliaryHostTest {
    @Test fun `hook uses admitted host script and supervised executor under current child scope`(@TempDir dir: Path) = runBlocking {
        val script = dir.resolve("handler.py"); Files.writeString(script, "raise RuntimeError('bare process must never start')")
        val base = WorkflowHostTest.Host(dir); val scope = WorkflowHostScope("root", "root__child", "root", "hook-step")
        var request: SubprocessExecutor.ExecRequest? = null
        val host = object : WorkflowHost by base {
            override fun scriptContents(path: String): String { assertEquals(script.toString(), path); return Files.readString(script) }
            override suspend fun beforeOperation(actual: WorkflowHostScope, operation: String) { assertEquals(scope, actual); assertEquals("skill_hook", operation) }
            override val subprocessExecutor = object : SubprocessExecutor {
                override suspend fun execute(value: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                    request = value; return SubprocessExecutor.ExecResult(0, "{\"injectContent\":\"host-hook-proof\"}", "", 1)
                }
            }
        }
        val hook = BraidrunAgentHook("fixture", "fixture", listOf(BraidrunHookEvent.AGENT_BOOTSTRAP), "", "skill", handlerScript = script)
        val result = WorkflowHostContext.withHost(host) { WorkflowHostContext.withScope(scope) {
            BraidrunHookExecutor().execute(hook, BraidrunHookContext("agent:bootstrap", workspaceDir = dir.toString()))
        } }
        assertEquals("host-hook-proof", result?.injectContent); assertEquals("SKILL_HOOK", request?.admissionKind)
        assertTrue(request?.stdin?.contains("agent:bootstrap") == true)
    }
    @Test fun `host MCP connection failure is explicit and never starts configured native command`(@TempDir dir: Path) = runBlocking {
        val marker = dir.resolve("forbidden-native-start")
        val base = WorkflowHostTest.Host(dir); val scope = WorkflowHostScope("root", "root__child", "root", "mcp-step")
        var delegated = false
        val host = object : WorkflowHost by base {
            override suspend fun mcpTransport(actual: WorkflowHostScope, name: String, config: McpServerConfig): Transport {
                assertEquals(scope, actual); assertEquals("fixture", name); delegated = true
                throw IllegalStateException("Interactive transport pending official contract")
            }
        }
        val servers = buildJsonObject { put("fixture", buildJsonObject {
            put("command", "/bin/sh"); put("args", JsonArray(listOf(JsonPrimitive("-c"), JsonPrimitive("touch $marker"))))
        }) }
        assertFailsWith<IllegalStateException> { WorkflowHostContext.withHost(host) { WorkflowHostContext.withScope(scope) {
            registerMcpTools(HttpAccess(), listOf(ConfigurationParameter("mcp_servers", servers)), ToolRegistry { })
        } } }
        assertTrue(delegated); assertFalse(Files.exists(marker))
    }
}
