package com.fartech.agents.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.test.*

class RunnerProtocolTest {
    @Test fun `all canonical fixtures satisfy the same definitions and negative cases fail`() {
        val protocol = RunnerProtocol()
        val index = runnerJson.parseToJsonElement(resource("fixtures/index.json"))
        for ((file, definition) in index.jsonObject) {
            val value = runnerJson.parseToJsonElement(resource("fixtures/$file"))
            if (file.startsWith("valid/")) protocol.validate(value, definition.jsonPrimitive.content)
            else assertFailsWith<RunnerProtocolException>(file) { protocol.validate(value, definition.jsonPrimitive.content) }
        }
    }
    @Test fun `claim recovery preserves real phases and explicit report-only authority`() {
        val protocol = RunnerProtocol()
        val claim = runnerJson.parseToJsonElement(resource("fixtures/valid/claim.json")).jsonObject
        assertEquals("PREFLIGHT", claim.string("claimAuthority"))
        for (phase in listOf("READY", "RUNNING", "WAITING_APPROVAL", "STOP_REQUESTED", "FINALIZING", "STOP_UNCONFIRMED", "TERMINAL")) {
            val reportOnly = JsonObject(claim + mapOf("phase" to JsonPrimitive(phase), "claimAuthority" to JsonPrimitive("REPORT_ONLY")))
            protocol.validate(reportOnly, "claimResponse")
            // Claims are supervisor HTTP state, not a JVM control/start frame. The start schema
            // has no report-only loophole or implicit claim-to-start conversion.
            assertFailsWith<RunnerProtocolException> { protocol.validate(reportOnly, "ipcFrame") }
            assertFailsWith<RunnerProtocolException> { protocol.validate(frame(claim.obj("binding"), "start", reportOnly), "ipcFrame") }
        }
        assertFailsWith<RunnerProtocolException> { protocol.validate(JsonObject(claim - "claimAuthority"), "claimResponse") }
        assertFailsWith<RunnerProtocolException> { protocol.validate(JsonObject(claim + ("claimAuthority" to JsonPrimitive("EXECUTE"))), "claimResponse") }
    }
    @Test fun `unknown fields duplicate members and trailing JSON fail without reflecting secrets`() {
        val protocol = RunnerProtocol()
        val binding = binding()
        val frame = frame(binding, "cancel", buildJsonObject { put("commandId", "cancel"); put("reason", "stop") })
        assertFailsWith<RunnerProtocolException> { protocol.parse(frame.toString().replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1")) }
        val error = assertFailsWith<RunnerProtocolException> { protocol.parse("${frame}{\"secret\":\"sensitive-value\"}") }
        assertFalse(error.message.orEmpty().contains("sensitive-value"))
        assertFailsWith<RunnerProtocolException> { protocol.validate(JsonObject(frame + ("unexpected" to JsonPrimitive("x"))), "ipcFrame") }
    }
    @Test fun `truncated oversize and empty lines are rejected`() {
        assertFailsWith<RunnerProtocolException> { RunnerIpc.readLine(ByteArrayInputStream("{}".toByteArray())) }
        assertFailsWith<RunnerProtocolException> { RunnerIpc.readLine(ByteArrayInputStream("\n".toByteArray())) }
        assertFailsWith<RunnerProtocolException> { RunnerIpc.readLine(ByteArrayInputStream(ByteArray(RunnerIpc.MAX_FRAME_BYTES) { 65 })) }
        assertEquals("{}", RunnerIpc.readLine(ByteArrayInputStream("{}\n".toByteArray()))!!.toString(Charsets.UTF_8))
    }
    @Test fun `CN IPv6 unknown expired and conflicting paths cannot allow while Chinese warning can`() {
        val instant = Instant.parse("2026-10-08T01:00:00Z")
        var monotonic = 0L
        val gate = RunnerEnvironmentGate(binding(), setOf("engine-http"), now = { instant }, nanoTime = { monotonic })
        val environment = environment(binding(), instant)
        gate.install(environment); gate.check()
        val cn = JsonObject(environment + ("egress" to JsonArray(environment.array("egress") + JsonObject(environment.array("egress")[0].jsonObject +
            mapOf("addressFamily" to JsonPrimitive("IPv6"), "countryCode" to JsonPrimitive("CN"))))))
        assertFailsWith<RunnerAuthorityLost> { gate.install(cn) }
        assertFailsWith<RunnerAuthorityLost> { gate.install(JsonObject(environment + ("egress" to JsonArray(emptyList())))) }
        monotonic = 61_000_000_000
        assertFailsWith<RunnerAuthorityLost> { gate.check() }
        gate.invalidate("CN")
        assertFailsWith<RunnerAuthorityLost> { gate.install(environment) }
    }
    @Test fun `lease renewal cannot revive expiry or replace lease identity`() {
        val instant = Instant.parse("2026-10-08T01:00:00Z"); var clock = 0L
        val gate = RunnerEnvironmentGate(binding(), setOf("engine-http"), now = { instant }, nanoTime = { clock })
        gate.install(environment(binding(), instant)); gate.installLease(lease(instant))
        gate.check()
        assertFailsWith<RunnerAuthorityLost> { gate.installLease(JsonObject(lease(instant) + ("leaseId" to JsonPrimitive("another")))) }
        clock = 61_000_000_000
        assertFailsWith<RunnerAuthorityLost> { gate.installLease(lease(instant)) }
    }
    companion object {
        fun resource(path: String) = requireNotNull(RunnerProtocolTest::class.java.getResourceAsStream("/runner-workflow-v1/$path")).use { it.readBytes().toString(Charsets.UTF_8) }
        fun binding(): JsonObject = runnerJson.parseToJsonElement(resource("fixtures/valid/claim.json")).jsonObject.obj("binding")
        fun frame(binding: JsonObject, type: String, payload: JsonObject, reply: String? = null, id: String = java.util.UUID.randomUUID().toString()) = buildJsonObject {
            put("protocolVersion", 1); put("frameId", id); put("type", type); put("binding", binding); put("payload", payload)
            reply?.let { put("replyTo", it) }
        }
        fun environment(binding: JsonObject, now: Instant): JsonObject {
            val original = runnerJson.parseToJsonElement(resource("fixtures/valid/environment-zh-allow.json")).jsonObject
            return JsonObject(original + mapOf("binding" to binding, "checkedAt" to JsonPrimitive(now.toString()), "validUntil" to JsonPrimitive(now.plusSeconds(60).toString()),
                "egress" to JsonArray(original.array("egress").map { JsonObject(it.jsonObject + mapOf("pathId" to JsonPrimitive("engine-http"), "observedAt" to JsonPrimitive(now.toString()))) })))
        }
        fun lease(now: Instant): JsonObject = buildJsonObject {
            put("leaseId", "lease"); put("serverTime", now.toString()); put("leaseExpiresAt", now.plusSeconds(60).toString())
            put("renewAfterSeconds", 15); put("leaseDurationSeconds", 60)
        }
    }
}
