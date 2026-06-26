package com.fartech.agents.commons

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMcpUtilsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `resolveMcpLaunch resolves relative shell script command against cwd`() {
        val cwd = tempDir.resolve("project").apply { mkdirs() }
        val script = cwd.resolve("scripts/start-mcp.sh").apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env bash\nexit 0\n")
        }

        val launch = resolveMcpLaunch(
            serverName = "custom",
            mcpServer = McpServerConfig(
                command = "scripts/start-mcp.sh",
                cwd = cwd.absolutePath,
                args = listOf("--ping")
            )
        )

        assertEquals(cwd.absolutePath, launch.cwd?.absolutePath)
        assertEquals("bash", launch.command)
        assertEquals(script.absolutePath, launch.args.first())
        assertTrue("--ping" in launch.args)
    }

    @Test
    fun `resolveMcpLaunch searches parent directories for relative command path`() {
        val repoRoot = tempDir.resolve("repo").apply { mkdirs() }
        val launchDir = repoRoot.resolve("braidrun-workflow").apply { mkdirs() }
        val script = repoRoot.resolve("braidrun-mcp/start-mcp.sh").apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env bash\nexit 0\n")
        }

        val launch = resolveMcpLaunch(
            serverName = "braidrun_mcp",
            mcpServer = McpServerConfig(
                command = "./braidrun-mcp/start-mcp.sh",
                cwd = launchDir.absolutePath
            )
        )

        assertEquals(launchDir.absolutePath, launch.cwd?.absolutePath)
        assertEquals("bash", launch.command)
        assertEquals(script.absolutePath, launch.args.first())
    }

    @Test
    fun `resolveMcpLaunch forwards agent env to stdio MCP when args omit env`() {
        val cwd = tempDir.resolve("project").apply { mkdirs() }
        val script = cwd.resolve("scripts/start-mcp.sh").apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env bash\nexit 0\n")
        }

        val launch = resolveMcpLaunch(
            serverName = "braidrun_mcp",
            mcpServer = McpServerConfig(
                command = "scripts/start-mcp.sh",
                cwd = cwd.absolutePath,
                args = listOf("--categories", "searchads")
            ),
            agentEnv = "braidrun"
        )

        assertEquals(cwd.absolutePath, launch.cwd?.absolutePath)
        assertEquals("bash", launch.command)
        assertEquals(script.absolutePath, launch.args.first())
        assertTrue("--env" in launch.args)
        assertTrue("braidrun" in launch.args)
    }

    @Test
    fun `resolveMcpLaunch rejects stdio servers without command`() {
        val error = assertFailsWith<IllegalArgumentException> {
            resolveMcpLaunch(
                serverName = "braidrun_mcp",
                mcpServer = McpServerConfig()
            )
        }

        assertTrue(error.message?.contains("command") == true)
    }

    @Test
    fun `applyMcpLaunchEnvironment removes inherited sensitive variables`() {
        val processBuilder = ProcessBuilder("env")
        processBuilder.environment()["MCP_LEAK_SECRET"] = "should-not-leak"

        applyMcpLaunchEnvironment(processBuilder, mapOf("MCP_EXPLICIT" to "ok"))

        val env = processBuilder.environment()
        assertFalse(env.containsKey("MCP_LEAK_SECRET"))
        assertEquals("ok", env["MCP_EXPLICIT"])
    }
}
