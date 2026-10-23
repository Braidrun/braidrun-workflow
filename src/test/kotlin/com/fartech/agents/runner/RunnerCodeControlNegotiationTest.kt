package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowCodeControlMode
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerCodeControlNegotiationTest {
    @Test fun `ready per-call capability is backed by exact committed HTTP supplement`() {
        assertTrue(RunnerCodeControlNegotiation.supplementAvailable())
        assertTrue(RunnerCodeControlNegotiation.PER_CALL in RunnerHostSession.CAPABILITIES)
        val origin = javaClass.getResourceAsStream("/runner-workflow-v1/late-control-origin.txt")!!.bufferedReader().readLines().first()
        assertEquals(RunnerCodeControlNegotiation.CONTRACT_COMMIT, origin)
        assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
            RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL), RunnerHostSession.CAPABILITIES))
    }
    @Test fun `listing supplement exposes worker operations and excludes human decisions`() {
        val bytes = javaClass.getResourceAsStream("/runner-workflow-v1/late-control.schema.json")!!.use { it.readBytes() }
        assertTrue(RunnerCodeControlNegotiation.supplementAvailable(bytes))
        val definitions = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("\$defs").jsonObject
        for (name in listOf("listing_read", "listing_publish", "listing_claim", "listing_failed", "listing_delivery", "directory_publish", "slack_deliver")) {
            val arguments = definitions.getValue(name + "_arguments").jsonObject
            assertEquals(JsonPrimitive(false), arguments["additionalProperties"])
        }
        for (name in listOf("listing_feedback_arguments", "listing_retry_arguments", "listing_accept_arguments")) {
            assertFalse(name in definitions, "Human decisions cannot be granted to workflow code")
        }
    }
    @Test fun `missing altered supplement and absent engine capability are not accepted`() {
        assertFalse(RunnerCodeControlNegotiation.supplementAvailable(null))
        assertFalse(RunnerCodeControlNegotiation.supplementAvailable("{}".toByteArray()))
        assertFailsWith<IllegalArgumentException> { RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL), emptySet()) }
    }
    @Test fun `listing reads support scoped reference chunks without granting upload or human authority`() {
        val schema = javaClass.getResourceAsStream("/runner-workflow-v1/late-control.schema.json")!!.bufferedReader().use { it.readText() }
        val definitions = Json.parseToJsonElement(schema).jsonObject.getValue("\$defs").jsonObject
        val read = definitions.getValue("listing_read_arguments").jsonObject
        val properties = read.getValue("properties").jsonObject
        assertEquals(JsonPrimitive("^[A-Za-z0-9_-]{1,128}$"), properties.getValue("referenceId").jsonObject["pattern"])
        assertEquals(JsonPrimitive("^[A-Za-z0-9_-]{1,128}$"), properties.getValue("feedbackId").jsonObject["pattern"])
        assertEquals(2, read.getValue("oneOf").jsonArray.size)
        assertEquals(setOf("offset"), read.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse("listing_reference_upload_arguments" in definitions)
        assertFalse("listing_reference_delete_arguments" in definitions)
    }
    @Test fun `missing selector never implicitly enables per-call mode and mutually exclusive selectors refuse`() {
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN, RunnerCodeControlNegotiation.select(setOf("step.script.v1"), RunnerHostSession.CAPABILITIES))
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN, RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES))
        assertFailsWith<IllegalArgumentException> {
            RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL, RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES)
        }
    }
    @Test fun `feedback context uses explicit per-call negotiation without claiming supervisor availability`() {
        val required = setOf(RunnerCodeControlNegotiation.FEEDBACK_CONTEXT, RunnerCodeControlNegotiation.PER_CALL)
        assertTrue(RunnerHostSession.CAPABILITIES.containsAll(required))
        assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
            RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES))
        for (missing in required) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES - missing)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, failure.reason)
        }
    }
    @Test fun `feedback context without per-call or with legacy selector fails closed`() {
        val feedback = RunnerCodeControlNegotiation.FEEDBACK_CONTEXT
        for (required in listOf(setOf(feedback), setOf(feedback, RunnerCodeControlNegotiation.LEGACY))) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_FEEDBACK_CONTEXT_REQUIRES_PER_CALL, failure.reason)
        }
        val conflict = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(feedback, RunnerCodeControlNegotiation.PER_CALL,
                RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_CONTROL_MODE_CONFLICT, conflict.reason)
        val unknown = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(RunnerCodeControlNegotiation.PER_CALL, "control.unknown.v1"), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, unknown.reason)
    }

    @Test fun `source read policy requires available explicit per-call and composes with feedback context`() {
        val required = setOf(RunnerCodeControlNegotiation.SOURCE_READ_POLICY, RunnerCodeControlNegotiation.PER_CALL)
        assertTrue(RunnerHostSession.CAPABILITIES.containsAll(required))
        for (selection in listOf(required, required + RunnerCodeControlNegotiation.FEEDBACK_CONTEXT)) {
            assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL,
                RunnerCodeControlNegotiation.select(selection, RunnerHostSession.CAPABILITIES))
        }
        for (missing in required) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES - missing)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, failure.reason)
        }
    }
    @Test fun `source read policy refuses absent dependency legacy conflict and unknown capabilities`() {
        val source = RunnerCodeControlNegotiation.SOURCE_READ_POLICY
        for (selection in listOf(setOf(source), setOf(source, RunnerCodeControlNegotiation.LEGACY))) {
            val failure = assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(selection, RunnerHostSession.CAPABILITIES)
            }
            assertEquals(RunnerNegotiationReason.RUNNER_SOURCE_READ_POLICY_REQUIRES_PER_CALL, failure.reason)
        }
        val conflict = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(source, RunnerCodeControlNegotiation.PER_CALL,
                RunnerCodeControlNegotiation.LEGACY), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_CONTROL_MODE_CONFLICT, conflict.reason)
        val unknown = assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(setOf(source, RunnerCodeControlNegotiation.PER_CALL,
                "control.sourceReadPolicy.v2"), RunnerHostSession.CAPABILITIES)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, unknown.reason)
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN,
            RunnerCodeControlNegotiation.select(emptySet(), RunnerHostSession.CAPABILITIES))
    }

    @Test fun `gate projection requires every explicit dependency and all available capabilities`() {
        val projection = RunnerCodeControlNegotiation.GATE_PROJECTION
        val required = setOf(projection, RunnerArtifactSetProtocol.GATES, RunnerArtifactSetProtocol.CAPABILITY,
            RunnerCollectionProtocol.CAPABILITY, RunnerCodeControlNegotiation.PER_CALL)
        val fixtureAvailable = RunnerHostSession.CAPABILITIES + required // Selector-only controlled inventory, not actual provider/pin acceptance.
        assertEquals(WorkflowCodeControlMode.SUPERVISOR_PER_CALL, RunnerCodeControlNegotiation.select(required, fixtureAvailable))
        for (missing in listOf(RunnerArtifactSetProtocol.GATES, RunnerArtifactSetProtocol.CAPABILITY, RunnerCodeControlNegotiation.PER_CALL)) {
            assertEquals(RunnerNegotiationReason.RUNNER_GATE_PROJECTION_DEPENDENCY_REQUIRED, assertFailsWith<RunnerNegotiationException> {
                RunnerCodeControlNegotiation.select(required - missing, fixtureAvailable)
            }.reason)
        }
        assertEquals(RunnerNegotiationReason.RUNNER_SET_DEPENDENCY_REQUIRED, assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required - RunnerCollectionProtocol.CAPABILITY, fixtureAvailable)
        }.reason)
        for (missing in required) assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required, fixtureAvailable - missing)
        }.reason)
    }
    @Test fun `gate projection remains withheld and does not permit callback conflict unknown capability or implicit mode`() {
        val projection = RunnerCodeControlNegotiation.GATE_PROJECTION
        assertFalse(projection in RunnerHostSession.CAPABILITIES)
        val required = setOf(projection, RunnerArtifactSetProtocol.GATES, RunnerArtifactSetProtocol.CAPABILITY,
            RunnerCollectionProtocol.CAPABILITY, RunnerCodeControlNegotiation.PER_CALL)
        assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required, RunnerHostSession.CAPABILITIES)
        }.reason)
        assertEquals(RunnerNegotiationReason.RUNNER_CONTROL_MODE_CONFLICT, assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required + RunnerCodeControlNegotiation.LEGACY, RunnerHostSession.CAPABILITIES + required)
        }.reason)
        assertEquals(RunnerNegotiationReason.RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE, assertFailsWith<RunnerNegotiationException> {
            RunnerCodeControlNegotiation.select(required + "control.gateProjection.v2", RunnerHostSession.CAPABILITIES + required)
        }.reason)
        assertEquals(WorkflowCodeControlMode.LEGACY_STEP_TOKEN, RunnerCodeControlNegotiation.select(emptySet(), RunnerHostSession.CAPABILITIES))
    }

}
