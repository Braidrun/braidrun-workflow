package com.fartech.agents.runner
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class RunnerLongCodeProtocolTest {
    private fun frame(kind: String, seconds: Long, purpose: String = "CODE_STEP") = RunnerProtocolTest.frame(RunnerProtocolTest.binding(), kind, buildJsonObject {
        put("requestId", "long-code"); put("argv", JsonArray(listOf(JsonPrimitive("true"))))
        put("relativeCwd", "."); put("environment", buildJsonObject {}); put("purpose", purpose)
        put("timeoutSeconds", seconds)
        if (kind == "processLaunch") { put("stdin", JsonNull); put("captureLimitBytes", 1024) } else { put("maxOutputBytes", 1024); put("maxPendingBytes",32768); put("channelEncoding","base64") }
    })
    @Test fun `only negotiated code processes accept long budgets with unchanged base contract`() {
        assertTrue(RunnerLongCodeProtocol.available())
        assertTrue(RunnerLongCodeProtocol.CAPABILITY in RunnerHostSession.CAPABILITIES)
        for (kind in listOf("processLaunch", "processOpen")) {
            for (seconds in listOf(86401L,87000L,150000L,172800L,190000L)) {
                val f = frame(kind,seconds)
                assertFailsWith<RunnerProtocolException> { RunnerProtocol().validate(f,"ipcFrame") }
                RunnerLongCodeProtocol.validate(f)
            }
            for (seconds in listOf(86400L,190001L)) assertFailsWith<RunnerProtocolException> { RunnerLongCodeProtocol.validate(frame(kind,seconds)) }
            for (purpose in listOf("MCP_STDIO","AGENT_CLI","NATIVE_STAGE")) assertFailsWith<RunnerProtocolException> { RunnerLongCodeProtocol.validate(frame(kind,87000,purpose)) }
        }
    }
    @Test fun `IPC requires explicit selection and keeps purpose bounds`() {
        val ipc = RunnerIpc(RunnerProtocolTest.binding(), ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), onControl={}, onFailure={})
        try {
            ipc.requireProcessTimeout(86400,"CODE_STEP")
            assertFailsWith<IllegalArgumentException> { ipc.requireProcessTimeout(87000,"CODE_STEP") }
            assertFailsWith<RunnerProtocolException> { ipc.sendFrame(frame("processLaunch",87000)) }
            ipc.enableLongCode()
            ipc.requireProcessTimeout(172800,"CODE_STEP")
            ipc.sendFrame(frame("processLaunch",87000))
            assertFailsWith<IllegalArgumentException> { ipc.requireProcessTimeout(87000,"MCP_STDIO") }
            assertFailsWith<IllegalArgumentException> { ipc.requireProcessTimeout(190001,"CODE_STEP") }
        } finally { ipc.close() }
    }
    @Test fun `long processes cannot select legacy transport`() {
        assertFailsWith<RunnerNegotiationException> { RunnerCodeControlNegotiation.select(setOf(RunnerLongCodeProtocol.CAPABILITY),RunnerHostSession.CAPABILITIES) }
        RunnerCodeControlNegotiation.select(setOf(RunnerLongCodeProtocol.CAPABILITY,RunnerCodeControlNegotiation.PER_CALL),RunnerHostSession.CAPABILITIES)
    }
}
