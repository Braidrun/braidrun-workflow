package com.fartech.agents.runner

import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque

/** Bounded process output handoff. Overflow is an interruption, never silent truncation.
 * EOF drains already-received bytes; failure/cancellation wakes blocked readers immediately.
 * This class has no private wire fields: the frozen protocol dispatcher feeds verified bytes.
 */
class RunnerBytePipe(private val maximumPendingBytes: Int = 1024 * 1024) {
    private val lock = Object()
    private data class Chunk(val bytes: ByteArray, val consumed: (() -> Unit)?)
    private val chunks = ArrayDeque<Chunk>()
    private var pending = 0
    private var offset = 0
    private var finished = false
    private var failure: IOException? = null
    init { require(maximumPendingBytes > 0) }
    fun offer(bytes: ByteArray, consumed: (() -> Unit)? = null) = synchronized(lock) {
        if (finished || failure != null) throw IOException("Interactive process output is closed")
        if (bytes.isEmpty()) return@synchronized
        if (bytes.size > maximumPendingBytes - pending) {
            failure = IOException("Interactive process output exceeded bounded backlog"); chunks.clear(); pending = 0
            lock.notifyAll(); throw failure!!
        }
        chunks.addLast(Chunk(bytes.copyOf(), consumed)); pending += bytes.size; lock.notifyAll()
    }
    fun eof() = synchronized(lock) { finished = true; lock.notifyAll() }
    fun fail() = synchronized(lock) {
        failure = IOException("Interactive process was cancelled or lost authority")
        chunks.clear(); pending = 0; lock.notifyAll()
    }
    val input: InputStream = object : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(target: ByteArray, start: Int, length: Int): Int {
            var consumed: (() -> Unit)? = null
            val result = synchronized(lock) {
                java.util.Objects.checkFromIndexSize(start, length, target.size)
                if (length == 0) return@synchronized 0
                while (chunks.isEmpty() && !finished && failure == null) {
                    try { lock.wait() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Interactive process read interrupted") }
                }
                failure?.let { throw it }
                if (chunks.isEmpty()) return@synchronized -1
                val chunk = chunks.first(); val n = minOf(length, chunk.bytes.size - offset)
                chunk.bytes.copyInto(target, start, offset, offset + n); offset += n; pending -= n
                if (offset == chunk.bytes.size) { chunks.removeFirst(); offset = 0; consumed = chunk.consumed }
                n
            }
            consumed?.invoke() // no lock inversion with the protocol dispatcher
            return result
        }
        override fun close() { fail() }
    }
}
