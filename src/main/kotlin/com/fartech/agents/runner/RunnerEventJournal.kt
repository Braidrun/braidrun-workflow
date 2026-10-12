package com.fartech.agents.runner

import kotlinx.serialization.json.*
import java.util.UUID

/** One append boundary for EVERY event producer: reserve -> durable links/event -> FIFO enqueue.
 * Waiters register before append and await ACK after this lock has been released. */
internal class RunnerEventJournal(
    private val binding: JsonObject, private val secrets: RunnerSecrets,
    private val write: (String, ByteArray) -> Unit, private val enqueue: (JsonObject) -> Unit,
    private val failed: () -> Unit,
    private val businessFence: RunnerBusinessFence = RunnerBusinessFence()
) {
    private var sequence = 0L
    private var ack = 0L
    private var broken = false
    private var sealed = false
    private class PersistenceFailure : RuntimeException()
    fun append(kind: String, payload: JsonObject, frameId: String = UUID.randomUUID().toString(),
                             replyTo: String? = null, beforePersist: (JsonObject) -> Unit = {}): JsonObject = try {
        // Lock order is fence -> journal. Closure cannot split an admitted reserve/persist/enqueue.
        businessFence.admit {
            synchronized(this) {
                if (broken || sealed) throw RunnerAuthorityLost()
                require(kind != "approval") { "Approvals use the dedicated request channel" }
                if (kind == "artifact") require(secrets.redact(payload) == payload) { "Artifact metadata cannot be altered" }
                val next = sequence + 1
                val event = buildJsonObject {
                    put("eventId", UUID.randomUUID().toString()); put("eventSeq", next)
                    put("attemptId", binding.string("attemptId")); put("resumeEpoch", binding.long("resumeEpoch"))
                    put("type", kind); put("payload", secrets.redact(payload))
                }
                val frame = buildJsonObject {
                    put("protocolVersion", 1); put("frameId", frameId); put("type", "event"); put("binding", binding); put("payload", event)
                    replyTo?.let { put("replyTo", it) }
                }
                sequence = next
                try {
                    beforePersist(frame)
                    write(eventPath(next), canonical(frame).toByteArray())
                    enqueue(frame) // n+1 cannot be reserved/enqueued before n reaches the FIFO
                } catch (_: Exception) {
                    broken = true; throw PersistenceFailure() // no later event may fill/reuse a hole
                }
                frame
            }
        }
    } catch (_: PersistenceFailure) {
        // Invalidation/stop callbacks may acquire host locks: never invoke them inside admission/journal.
        failed(); throw RunnerAuthorityLost()
    }
    @Synchronized fun seal(): Long {
        sealed = true
        if (broken) throw RunnerAuthorityLost() // an actual failed append needs reconciliation, never a complete terminal sequence
        return sequence
    }
    @Synchronized fun acknowledge(payload: JsonObject) {
        val value = payload.long("ackSeq"); require(value <= sequence) { "ACK exceeds emitted sequence" }
        if (value > ack) ack = value
    }
    @Synchronized fun acknowledged() = ack
    @Synchronized fun lastSequence() = sequence
    companion object {
        /** The durable copy of an event's original frame; the IPC proves acknowledged replays against it. */
        fun eventPath(sequence: Long) = ".runner-state/events/$sequence.json"
    }
}
