package com.fartech.agents.tools

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Internal bounded in-memory codecs. The host owns paths, atomic writes, quota, and live authority. */
internal object ToolByteBuffers {
    fun read(files: ToolFileAccess, path: String, limit: Int, guard: () -> Unit): ByteArray {
        guard()
        require(path.length in 1..4096 && '\u0000' !in path) { "Invalid tool file path" }
        val bytes = files.readBytes(path, limit)
        require(bytes.size <= limit) { "Tool input byte limit exceeded" }
        guard()
        return bytes
    }

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun result(value: String, guard: () -> Unit, limit: Int = 32 * 1024): String {
        guard()
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return value
        val suffix = "\n[Result truncated to the Runner output limit]"
        var end = limit - suffix.toByteArray().size
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + suffix
    }
}

internal class BoundedToolOutput(private val limit: Int, private val guard: () -> Unit) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    override fun write(value: Int) {
        guard()
        require(buffer.size() < limit) { "Tool output byte limit exceeded" }
        buffer.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        guard()
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size)
        require(buffer.size().toLong() + length <= limit) { "Tool output byte limit exceeded" }
        buffer.write(bytes, offset, length)
    }
    fun bytes(): ByteArray { guard(); return buffer.toByteArray() }
}
