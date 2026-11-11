package com.fartech.agents.tools.exec

/** Bounded head/tail capture for verbose agent protocols whose terminal result is the last JSONL record. */
internal class AgentOutputCapture {
    private val limit = SubprocessExecutor.MAX_STREAM_BYTES / 2
    private val head = java.io.ByteArrayOutputStream()
    private val tail = ByteArray(limit)
    private var position = 0
    private var count = 0
    private var total = 0L

    fun write(bytes: ByteArray, length: Int) {
        total += length
        val prefix = minOf(length, limit - head.size())
        if (prefix > 0) head.write(bytes, 0, prefix)
        var offset = prefix
        while (offset < length) {
            val take = minOf(length - offset, limit - position)
            bytes.copyInto(tail, position, offset, offset + take)
            position = (position + take) % limit
            count = minOf(limit, count + take)
            offset += take
        }
    }

    fun text(): String {
        val suffix = if (count < limit) tail.copyOf(count) else
            tail.copyOfRange(position, limit) + tail.copyOfRange(0, position)
        return if (total <= SubprocessExecutor.MAX_STREAM_BYTES) {
            (head.toByteArray() + suffix).toString(Charsets.UTF_8)
        } else {
            head.toString(Charsets.UTF_8) + SubprocessExecutor.STREAM_TRUNCATION_MARKER +
                "\n[retained final output]\n" + suffix.toString(Charsets.UTF_8)
        }
    }
}
