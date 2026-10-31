package com.fartech.agents.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The reader always remains available for cancellation while requests await their replies.
 * Writer backpressure is bounded and failure closes authority; it cannot indefinitely block
 * supervisor policy enforcement. Only dedicated grant/rotation frames may contain material.
 */
class RunnerIpc(
    val binding: JsonObject,
    private val input: InputStream,
    private val output: OutputStream,
    private val protocol: RunnerProtocol = RunnerProtocol(),
    private val onControl: (JsonObject) -> Unit,
    private val onFailure: () -> Unit
) : AutoCloseable {
    val businessFence = RunnerBusinessFence()
    private val retainedOutbound = LinkedHashMap<String, String>()
    /** Retained events by sequence, so a durable ACK can reclaim them. Guarded by [retainedOutbound]. */
    private val retainedEvents = java.util.TreeMap<Long, String>()
    private var reclaimedThrough = 0L
    @Volatile private var durableEvent: ((Long) -> ByteArray?)? = null
    private var terminalResult: Pair<String, String>? = null
    private data class Pending(val type: String, val correlation: String?, val response: CompletableDeferred<JsonObject>)
    private val pending = ConcurrentHashMap<String, Pending>()
    /**
     * Bounded by [slots], not by the queue: a sender waits for a slot (up to [OUTBOUND_WAIT_MS]) BEFORE it takes the
     * fence or retained-evidence locks, so the enqueue under them never blocks and the reader thread (ACKs, lease
     * renewals, cancellation) never waits behind a producer's backpressure. The writer frees a slot per dequeued frame.
     */
    private val outbound = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val slots = java.util.concurrent.Semaphore(OUTBOUND_CAPACITY, true)
    /** A slot this thread reserved through [withOutboundSlot] for the next [sendFrame]. */
    private val reservedSlot = ThreadLocal<Boolean>()
    private val closed = AtomicBoolean()
    @Volatile private var longCodeEnabled = false
    fun enableLongCode() { require(RunnerLongCodeProtocol.available()); longCodeEnabled = true }
    fun requireProcessTimeout(seconds: Long, purpose: String) {
        val maximum = if (longCodeEnabled && purpose == "CODE_STEP") RunnerLongCodeProtocol.MAX_SECONDS else 86400L
        require(seconds in 1..maximum) { "Unsupported process timeout for negotiated purpose" }
    }
    @Volatile private var artifactCollectionEnabled = false
    fun enableArtifactCollection() { require(RunnerCollectionProtocol.available()); artifactCollectionEnabled = true }
    @Volatile private var artifactSetsEnabled = false
    fun enableArtifactSets() { require(artifactCollectionEnabled && RunnerArtifactSetProtocol.available()); artifactSetsEnabled = true }
    @Volatile private var nativeModelEnabled = false
    fun enableNativeModel() { require(RunnerNativeModelProtocol.available()); nativeModelEnabled = true }
    @Volatile private var localModelEnabled = false
    fun enableLocalModel() { require(RunnerLocalModelProtocol.available()); localModelEnabled = true }
    private var acceptedStart: JsonObject? = null
    @Volatile internal var multimediaNegotiated = false
        private set
    @Synchronized internal fun enableMultimedia(start: JsonObject, advertised: Set<String>, trustedEnabled: Boolean) {
        businessFence.check()
        require(trustedEnabled && !closed.get() && acceptedStart == start && start.obj("binding") == binding) {
            "Multimedia requires trusted runtime enablement and the accepted supervisor start"
        }
        val required = start.obj("payload").obj("authorization").array("requiredCapabilities").map { it.jsonPrimitive.content }.toSet()
        require(setOf(RunnerMultimediaHostPolicy.CAPABILITY, "tool.file_system.v1", "model.http.v1").all { it in required && it in advertised }) {
            "Multimedia runtime and supervisor capabilities were not negotiated"
        }
        multimediaNegotiated = true
    }
    @Volatile internal var agentSandboxNegotiated = false
        private set
    @Volatile internal var agentSandboxGroups: Set<String> = emptySet()
        private set
    @Synchronized internal fun enableAgentSandbox(start: JsonObject, advertised: Set<String>) {
        businessFence.check()
        require(!closed.get() && acceptedStart == start && start.obj("binding") == binding) { "Sandbox requires the accepted supervisor start" }
        val required = start.obj("payload").obj("authorization").array("requiredCapabilities").map { it.jsonPrimitive.content }.toSet()
        RunnerSandboxProtocol.requireNegotiated(required, advertised)
        val groups = RunnerSandboxPolicy.GROUPS.filter { "tool.$it.v1" in required }.toSet()
        require(groups.isNotEmpty() && groups.all { "tool.$it.v1" in advertised }) { "Sandbox tool groups were not negotiated" }
        agentSandboxGroups = groups
        agentSandboxNegotiated = true
    }
    private fun validateFrame(frame: JsonObject, outbound: Boolean) {
        if (RunnerLongCodeProtocol.isLong(frame)) {
            if (!longCodeEnabled || !outbound) throw RunnerProtocolException()
            RunnerLongCodeProtocol.validate(frame)
            return
        }
        val localType = frame["type"]?.jsonPrimitive?.content
        if (localType in RunnerSandboxProtocol.REQUEST_TYPES || localType in RunnerSandboxProtocol.RESPONSE_TYPES) {
            if (!agentSandboxNegotiated) throw RunnerProtocolException()
            RunnerSandboxProtocol.validate(frame, outbound)
            if (outbound && frame.obj("payload").string("toolGroup") !in agentSandboxGroups) throw RunnerProtocolException()
            return
        }
        if (localType in RunnerNativeModelProtocol.requests || localType in RunnerNativeModelProtocol.responses) {
            if (!nativeModelEnabled || (outbound != (localType in RunnerNativeModelProtocol.requests))) throw RunnerProtocolException()
            RunnerNativeModelProtocol.validate(frame)
            return
        }
        if (localType in RunnerLocalModelProtocol.requests || localType in RunnerLocalModelProtocol.responses) {
            if (!localModelEnabled || (outbound != (localType in RunnerLocalModelProtocol.requests))) throw RunnerProtocolException()
            RunnerLocalModelProtocol.validate(frame)
            return
        }
        when (frame["type"]?.jsonPrimitive?.content) {
            "artifactSetCollectRequest", "artifactSetIndexAck" -> {
                if (!artifactSetsEnabled || outbound) throw RunnerProtocolException()
                RunnerArtifactSetProtocol.validate(frame, if (frame.string("type") == "artifactSetCollectRequest") "collectFrame" else "indexAckFrame")
            }
            "artifactSetCollectComplete" -> { if (!artifactSetsEnabled || !outbound) throw RunnerProtocolException(); RunnerArtifactSetProtocol.validate(frame, "completeFrame") }
            "artifactCollectRequest" -> { if (!artifactCollectionEnabled || outbound) throw RunnerProtocolException(); RunnerCollectionProtocol.validate(frame, "collectionRequest") }
            "artifactCollectComplete" -> { if (!artifactCollectionEnabled || !outbound) throw RunnerProtocolException(); RunnerCollectionProtocol.validate(frame, "collectionComplete") }
            else -> protocol.validate(frame, "ipcFrame")
        }
    }
    private val seen = LinkedHashMap<String, String>()
    /** Bounded windows for correlated replies: frame identity -> digest, replyTo -> reply content digest. */
    private val recentReplies = boundedWindow()
    private val recentReplyContents = boundedWindow()
    private val sent = java.util.concurrent.atomic.AtomicLong()
    private val written = java.util.concurrent.atomic.AtomicLong()
    private val writer = Thread({ writeLoop() }, "runner-ipc-write").apply { isDaemon = true }
    private val reader = Thread({ readLoop() }, "runner-ipc-read").apply { isDaemon = true }

    init { protocol.validate(binding, "binding") }
    fun start() { writer.start(); reader.start() }

    fun send(type: String, payload: JsonObject, frameId: String = UUID.randomUUID().toString(), replyTo: String? = null): String {
        if (closed.get()) throw RunnerProtocolException()
        val frame = buildJsonObject {
            put("protocolVersion", 1); put("frameId", frameId); put("type", type)
            put("binding", binding); put("payload", payload)
            replyTo?.let { put("replyTo", it) }
        }
        return sendFrame(frame)
    }
    private fun acquireSlot() {
        if (closed.get()) throw RunnerProtocolException()
        val acquired = try { slots.tryAcquire(OUTBOUND_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!acquired) { fail(); throw RunnerProtocolException() }
        if (closed.get()) { slots.release(); throw RunnerProtocolException() }
    }
    /** Waits for outbound room outside any caller lock; the first [sendFrame] inside [action] uses it without waiting. */
    fun <T> withOutboundSlot(action: () -> T): T {
        if (reservedSlot.get() == true || closed.get()) return action() // a closed IPC refuses through the fence as before
        acquireSlot(); reservedSlot.set(true)
        try { return action() } finally {
            if (reservedSlot.get() == true) { reservedSlot.remove(); slots.release() }
        }
    }
    fun sendFrame(frame: JsonObject): String {
        if (closed.get() || frame.obj("binding") != binding) throw RunnerProtocolException()
        validateFrame(frame, true)
        val bytes = (canonical(frame) + "\n").toByteArray(Charsets.UTF_8)
        val type = frame.string("type"); val id = frame.string("frameId"); val digest = sha256(bytes)
        if (bytes.size > MAX_FRAME_BYTES) { fail(); throw RunnerProtocolException() }
        if (reservedSlot.get() == true) reservedSlot.remove() else acquireSlot()
        var queued = false
        fun enqueue() {
            if (closed.get()) throw RunnerProtocolException()
            outbound.add(bytes); queued = true
            sent.incrementAndGet()
        }
        try { return sendOwned(frame, type, id, digest, ::enqueue) } finally { if (!queued) slots.release() }
    }
    private fun sendOwned(frame: JsonObject, type: String, id: String, digest: String, enqueue: () -> Unit): String {
        if (businessFence.isClosed()) {
            synchronized(retainedOutbound) {
                if (type !in TERMINAL_CONTROL_TYPES && !(type in RETAINED_EVIDENCE_TYPES && retainedOutbound[id] == digest) && !isReclaimedOriginal(frame, type, id)) throw RunnerAuthorityLost()
                if (type == "result") require(terminalResult?.let { it == (id to digest) } != false) { "Original terminal result changed" }
                enqueue()
                if (type == "result") terminalResult = id to digest
            }
        } else businessFence.admit {
            synchronized(retainedOutbound) {
                val reclaimed = type == "event" && id !in retainedOutbound && eventSeq(frame) <= reclaimedThrough
                if (reclaimed) {
                    // Acknowledged and reclaimed: only the exact durably journaled original may travel again.
                    require(isReclaimedOriginal(frame, type, id)) { "Retained evidence frame identity changed" }
                } else if (type in RETAINED_EVIDENCE_TYPES) {
                    require(retainedOutbound[id]?.let { it == digest } != false) { "Retained evidence frame identity changed" }
                    require(id in retainedOutbound || retainedOutbound.size < MAX_INBOUND_IDENTITIES) { "Retained evidence bound exceeded" }
                }
                enqueue()
                if (type in RETAINED_EVIDENCE_TYPES && !reclaimed) {
                    retainedOutbound[id] = digest
                    if (type == "event") retainedEvents[eventSeq(frame)] = id
                }
            }
        }
        return id
    }

    /**
     * Events stay in the bounded retained set only until S durably ACKs them. The event journal
     * already persists every original frame, so [reader] (event sequence -> persisted canonical
     * frame bytes) proves an acknowledged original byte-for-byte when it is replayed later,
     * including after the business fence closed. Checkpoint and collection evidence are never
     * reclaimed. Without a durable reader nothing is reclaimed.
     */
    fun retainEventsDurably(reader: (Long) -> ByteArray?) { durableEvent = reader }
    fun reclaimAcknowledged(ackSeq: Long) {
        if (durableEvent == null) return
        synchronized(retainedOutbound) {
            val acknowledged = retainedEvents.headMap(ackSeq, true)
            acknowledged.values.forEach { retainedOutbound.remove(it) }
            acknowledged.clear()
            if (ackSeq > reclaimedThrough) reclaimedThrough = ackSeq
        }
    }
    internal fun retainedEvidenceCount(): Int = synchronized(retainedOutbound) { retainedOutbound.size }
    private fun eventSeq(frame: JsonObject): Long = frame.obj("payload").long("eventSeq")
    private fun isReclaimedOriginal(frame: JsonObject, type: String, id: String): Boolean {
        if (type != "event" || id in retainedOutbound || eventSeq(frame) > reclaimedThrough) return false
        val stored = runCatching { durableEvent?.invoke(eventSeq(frame)) }.getOrNull() ?: return false
        return stored.contentEquals(canonical(frame).toByteArray(Charsets.UTF_8))
    }

    class CorrelatedReply(val frameId: String, val payload: JsonObject)
    suspend fun request(type: String, payload: JsonObject, responseType: String, timeoutMs: Long = 30_000): JsonObject =
        requestCorrelated(type, payload, responseType, timeoutMs).payload

    suspend fun requestCorrelated(type: String, payload: JsonObject, responseType: String, timeoutMs: Long = 30_000,
                                  frameId: String = UUID.randomUUID().toString(), replyTo: String? = null,
                                  onRequest: (String) -> Unit = {}): CorrelatedReply {
        return businessFence.operation {
            awaitReply(responseType, timeoutMs, frameId, payload["idempotencyKey"]?.jsonPrimitive?.content) {
                onRequest(frameId); send(type, payload, frameId, replyTo)
            }
        }
    }

    /** Register a waiter BEFORE the shared event writer persists/enqueues its original frame. */
    suspend fun awaitReply(responseType: String, timeoutMs: Long = 30_000, frameId: String = UUID.randomUUID().toString(),
                           correlation: String? = null, dispatch: () -> Unit): CorrelatedReply {
        val response = CompletableDeferred<JsonObject>()
        if (pending.size >= 128 || pending.putIfAbsent(frameId, Pending(responseType, correlation, response)) != null) throw RunnerProtocolException()
        try {
            dispatch()
            return CorrelatedReply(frameId, withTimeout(timeoutMs) { response.await() })
        } finally { pending.remove(frameId); response.cancel() }
    }

    /** Public for deterministic transport tests; complete binding equality fences old epochs. */
    @Synchronized
    fun accept(frame: JsonObject) {
        validateFrame(frame, false)
        if (frame.obj("binding") != binding || closed.get()) throw RunnerProtocolException()
        val allowed = setOf("start", "resume", "ack", "approvalDecision", "userResponse", "credentialGrant", "cancel", "environmentInvalidated", "error",
            "leaseRenewed", "environmentRefreshed", "processStarted", "processExited", "processOutput", "processInputAck", "processClosed", "outputValue", "outputAck", "callbackTokenGrant", "artifactCollectRequest", "artifactSetCollectRequest", "artifactSetIndexAck")
        if (frame.string("type") !in allowed && !(nativeModelEnabled && frame.string("type") in RunnerNativeModelProtocol.responses) && !(localModelEnabled && frame.string("type") in RunnerLocalModelProtocol.responses) &&
            !(agentSandboxNegotiated && frame.string("type") in RunnerSandboxProtocol.RESPONSE_TYPES)) throw RunnerProtocolException()
        val id = frame.string("frameId")
        val digest = sha256(canonical(frame).toByteArray())
        val replyTo = frame["replyTo"]?.jsonPrimitive?.content
        // A correlated reply is inert once its waiter is gone (a late grant is dropped, an ACK is
        // monotone), so it is deduplicated in a bounded window instead of consuming a permanent
        // identity per renewal or event. Human continuations and uncorrelated controls stay permanent.
        val correlatedReply = replyTo != null && frame.string("type") !in ALWAYS_CONTROL_TYPES && frame.string("type") !in HUMAN_CONTINUATION_TYPES
        (seen[id] ?: recentReplies[id])?.let {
            if (it != digest) throw RunnerProtocolException()
            if (frame.string("type") == "approvalDecision") {
                val original = frame["replyTo"]?.jsonPrimitive?.content?.let { pending[it] }
                if (original != null) {
                    require(original.type == "approvalDecision")
                    pending.remove(frame.string("replyTo"))?.response?.complete(frame.obj("payload"))
                }
            }
            if (frame.string("type") in setOf("artifactCollectRequest", "artifactSetCollectRequest", "artifactSetIndexAck") || (artifactSetsEnabled && frame.string("type") == "ack")) onControl(frame)
            return
        }
        if (frame.string("type") in setOf("start", "resume")) {
            require(acceptedStart == null || acceptedStart == frame) { "Attempt start authority cannot change" }
            acceptedStart = frame
        }
        if (frame.string("type") in RunnerSandboxProtocol.RESPONSE_TYPES && (replyTo == null || !pending.containsKey(replyTo)) && replyTo !in recentReplyContents) {
            throw RunnerProtocolException() // No unsolicited results may manufacture successful cleanup.
        }
        if (correlatedReply) {
            // A replay under a new frame identity must carry the same reply; a different one conflicts.
            val content = sha256(canonical(JsonObject(frame - "frameId")).toByteArray())
            if (recentReplyContents[requireNotNull(replyTo)]?.let { it != content } == true) throw RunnerProtocolException()
            recentReplies[id] = digest; recentReplyContents[replyTo] = content
        } else {
            // Never evict dedupe identities and accidentally reaccept an old control. Refuse the
            // attempt at the explicit bound; future contract can add compactable durable sequences.
            if (seen.size >= MAX_INBOUND_IDENTITIES) throw RunnerProtocolException()
            seen[id] = digest
        }
        if (frame.string("type") in ALWAYS_CONTROL_TYPES) {
            onControl(frame)
        } else if (replyTo != null) {
            val request = pending[replyTo] ?: run {
                if (frame.string("type") == "ack") onControl(frame) // durable event ACKs have no waiting RPC
                return
            }
            if (frame.string("type") == "error") {
                pending.remove(replyTo)?.response?.completeExceptionally(RunnerProtocolException())
            } else {
                if (frame.string("type") != request.type) throw RunnerProtocolException()
                pending.remove(replyTo)?.response?.complete(frame.obj("payload"))
            }
        } else {
            if (frame.string("type") in setOf("approvalDecision", "userResponse")) {
                throw RunnerProtocolException() // Human continuations require original request frame correlation.
            } else {
                if (frame.string("type") in setOf("credentialGrant", "processStarted", "processInputAck", "outputValue", "outputAck", "callbackTokenGrant")) throw RunnerProtocolException()
                onControl(frame)
            }
        }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val bytes = readLine(input) ?: throw RunnerProtocolException()
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                accept(protocol.decode(text))
            }
        } catch (_: Exception) { fail() }
    }
    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val bytes = outbound.poll(100, TimeUnit.MILLISECONDS) ?: continue
                slots.release()
                output.write(bytes); output.flush(); written.incrementAndGet()
            }
        } catch (_: Exception) { fail() }
    }
    private fun fail() {
        if (closed.compareAndSet(false, true)) {
            businessFence.close()
            slots.release(OUTBOUND_CAPACITY) // wake waiting senders; they observe the closure and refuse
            pending.values.forEach { it.response.completeExceptionally(RunnerProtocolException()) }; pending.clear()
            onFailure()
        }
    }
    /** Flush only the local pipe. This is not a platform durable ACK. */
    fun drain(timeoutMs: Long = 2000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (written.get() < sent.get() && !closed.get() && System.nanoTime() < deadline) Thread.sleep(5)
        return written.get() == sent.get()
    }
    override fun close() { fail(); reader.interrupt(); writer.interrupt() }

    companion object {
        private val TERMINAL_CONTROL_TYPES = setOf("result", "processCancel", "processOutputAck")
        private val ALWAYS_CONTROL_TYPES = setOf("cancel", "environmentInvalidated", "leaseRenewed", "environmentRefreshed", "processExited", "processOutput", "processClosed", "artifactCollectRequest", "artifactSetCollectRequest", "artifactSetIndexAck")
        private val HUMAN_CONTINUATION_TYPES = setOf("approvalDecision", "userResponse")
        const val REPLY_WINDOW = 8192
        private fun boundedWindow() = object : LinkedHashMap<String, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > REPLY_WINDOW
        }
        private val RETAINED_EVIDENCE_TYPES = setOf("event", "checkpoint", "artifactCollectComplete", "artifactSetCollectComplete")
        const val MAX_FRAME_BYTES = 1024 * 1024
        const val OUTBOUND_CAPACITY = 128
        /** A burst waits for S to read; only a reader stalled this long closes authority. */
        const val OUTBOUND_WAIT_MS = 30_000L
        const val MAX_INBOUND_IDENTITIES = 65536
        /** No unbounded BufferedReader.readLine; EOF without newline is a protocol failure. */
        fun readLine(input: InputStream): ByteArray? {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < MAX_FRAME_BYTES) {
                val b = input.read()
                if (b < 0) { if (bytes.size() == 0) return null; throw RunnerProtocolException() }
                if (b == 10) { if (bytes.size() == 0) throw RunnerProtocolException(); return bytes.toByteArray() }
                bytes.write(b)
            }
            throw RunnerProtocolException()
        }
    }
}

internal fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
