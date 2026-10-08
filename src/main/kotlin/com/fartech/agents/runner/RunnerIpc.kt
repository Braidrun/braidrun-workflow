package com.fartech.agents.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
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
    private data class Pending(val type: String, val correlation: String?, val response: CompletableDeferred<JsonObject>)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val outbound = ArrayBlockingQueue<ByteArray>(128)
    private val closed = AtomicBoolean()
    private val seen = LinkedHashMap<String, String>()
    private val sent = java.util.concurrent.atomic.AtomicLong()
    private val written = java.util.concurrent.atomic.AtomicLong()
    private val writer = Thread({ writeLoop() }, "runner-ipc-write").apply { isDaemon = true }
    private val reader = Thread({ readLoop() }, "runner-ipc-read").apply { isDaemon = true }

    init { protocol.validate(binding, "binding") }
    fun start() { writer.start(); reader.start() }

    fun send(type: String, payload: JsonObject, frameId: String = UUID.randomUUID().toString()): String {
        if (closed.get()) throw RunnerProtocolException()
        val frame = buildJsonObject {
            put("protocolVersion", 1); put("frameId", frameId); put("type", type)
            put("binding", binding); put("payload", payload)
        }
        protocol.validate(frame, "ipcFrame")
        val bytes = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES || !outbound.offer(bytes, 1, TimeUnit.SECONDS)) {
            fail(); throw RunnerProtocolException()
        }
        sent.incrementAndGet()
        return frameId
    }

    suspend fun request(type: String, payload: JsonObject, responseType: String, timeoutMs: Long = 30_000): JsonObject {
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<JsonObject>()
        if (pending.size >= 128 || pending.putIfAbsent(id, Pending(responseType, payload["idempotencyKey"]?.jsonPrimitive?.content, response)) != null) throw RunnerProtocolException()
        try {
            send(type, payload, id)
            return withTimeout(timeoutMs) { response.await() }
        } finally { pending.remove(id); response.cancel() }
    }

    /** Public for deterministic transport tests; complete binding equality fences old epochs. */
    @Synchronized
    fun accept(frame: JsonObject) {
        protocol.validate(frame, "ipcFrame")
        if (frame.obj("binding") != binding || closed.get()) throw RunnerProtocolException()
        val allowed = setOf("start", "resume", "ack", "approvalDecision", "userResponse", "credentialGrant", "cancel", "environmentInvalidated", "error",
            "leaseRenewed", "environmentRefreshed", "processStarted", "processExited", "processOutput", "processInputAck", "processClosed", "outputValue", "outputAck", "callbackTokenGrant")
        if (frame.string("type") !in allowed) throw RunnerProtocolException()
        val id = frame.string("frameId")
        val digest = sha256(canonical(frame).toByteArray())
        seen[id]?.let { if (it != digest) throw RunnerProtocolException(); return }
        // Never evict dedupe identities and accidentally reaccept an old control. Refuse the
        // attempt at the explicit bound; future contract can add compactable durable sequences.
        if (seen.size >= MAX_INBOUND_IDENTITIES) throw RunnerProtocolException()
        seen[id] = digest
        val replyTo = frame["replyTo"]?.jsonPrimitive?.content
        if (frame.string("type") in setOf("cancel", "environmentInvalidated", "leaseRenewed", "environmentRefreshed", "processExited", "processOutput", "processClosed")) {
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
                val key = frame.obj("payload").string("idempotencyKey")
                val requests = pending.entries.filter { it.value.type == frame.string("type") && it.value.correlation == key }
                if (requests.size > 1) throw RunnerProtocolException()
                requests.singleOrNull()?.let { pending.remove(it.key)?.response?.complete(frame.obj("payload")) }
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
                accept(protocol.parse(text))
            }
        } catch (_: Exception) { fail() }
    }
    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val bytes = outbound.poll(100, TimeUnit.MILLISECONDS) ?: continue
                output.write(bytes); output.flush(); written.incrementAndGet()
            }
        } catch (_: Exception) { fail() }
    }
    private fun fail() {
        if (closed.compareAndSet(false, true)) {
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
        const val MAX_FRAME_BYTES = 1024 * 1024
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
