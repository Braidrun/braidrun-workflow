package com.fartech.agents.runner

import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.metadata.McpServerInfo
import com.fartech.agents.workflow.WorkflowInteractiveProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class RunnerMcpTransportTest {
    @Test fun `MCP SDK handshake and tool catalog use hosted byte channels and close requests owned stop`() = runBlocking {
        val serverInput = PipedInputStream(65536); val clientOutput = PipedOutputStream(serverInput)
        val clientInput = PipedInputStream(65536); val serverOutput = PipedOutputStream(clientInput)
        val stopped = AtomicBoolean()
        val process = object : WorkflowInteractiveProcess {
            override val registrationId = "hosted-fixture"
            override val stdin = clientOutput
            override val stdout = clientInput
            override val stderr = java.io.ByteArrayInputStream(ByteArray(0))
            override suspend fun awaitExit() = 0
            override fun close() { stopped.set(true) }
        }
        val server = Thread {
            try {
                serverInput.bufferedReader().use { reader ->
                    val writer = serverOutput.bufferedWriter()
                    while (true) {
                        val line = reader.readLine() ?: break
                        val request = runnerJson.parseToJsonElement(line).jsonObject
                        if (request["id"] == null) continue
                        val result = when (request.string("method")) {
                            "initialize" -> buildJsonObject { put("protocolVersion", request.obj("params").string("protocolVersion")); put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                                put("serverInfo", buildJsonObject { put("name", "byte-channel-fixture"); put("version", "1") }) }
                            "tools/list" -> buildJsonObject { put("tools", JsonArray(listOf(buildJsonObject { put("name", "echo"); put("description", "fixture")
                                put("inputSchema", buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) }) }))) }
                            else -> JsonObject(emptyMap())
                        }
                        writer.write(buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result) }.toString()); writer.newLine(); writer.flush()
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val transport = RunnerMcpTransport(process)
        try {
            val registry = withTimeout(5000) { McpToolRegistryProvider.fromTransport(transport, McpServerInfo("", "fixture"), name = "fixture") }
            assertTrue(registry.tools.any { it.descriptor.name.contains("echo") })
        } finally {
            transport.close(); serverInput.close(); serverOutput.close(); clientInput.close(); clientOutput.close(); server.join(1000)
        }
        assertTrue(stopped.get())
    }
}
