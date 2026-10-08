package com.fartech.agents.runner

import com.fartech.ftapp2.commonsKt.redactForLog
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArraySet

/** Execution-local values, retained through cleanup so late logs are still redacted. */
class RunnerSecrets {
    private val values = CopyOnWriteArraySet<String>()
    private val credentialFiles = CopyOnWriteArraySet<java.nio.file.Path>()
    fun observe(path: java.nio.file.Path): AutoCloseable {
        credentialFiles.add(path)
        refresh()
        return AutoCloseable { credentialFiles.remove(path) }
    }
    private fun refresh(): Boolean {
        var available = true
        credentialFiles.forEach { path ->
            try {
                require(java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !java.nio.file.Files.isSymbolicLink(path) && java.nio.file.Files.size(path) <= RunnerIpc.MAX_FRAME_BYTES)
                val material = runnerJson.parseToJsonElement(java.nio.file.Files.readString(path))
                require(material is JsonObject)
                register(material)
            } catch (_: Exception) { available = false }
        }
        return available
    }
    fun register(material: JsonElement) {
        when (material) {
            is JsonObject -> material.values.forEach(::register)
            is JsonArray -> material.forEach(::register)
            is JsonPrimitive -> if (material.isString && material.content.isNotEmpty()) {
                values.add(material.content)
                values.add(java.net.URLEncoder.encode(material.content, Charsets.UTF_8))
                values.add(JsonPrimitive(material.content).toString().removeSurrounding("\""))
                if (material.content.startsWith("{") || material.content.startsWith("[")) {
                    runCatching { runnerJson.parseToJsonElement(material.content) }.getOrNull()?.let { parsed ->
                        if (parsed is JsonObject || parsed is JsonArray) register(parsed)
                    }
                }
            }
        }
    }
    fun redact(text: String): String = if (!refresh()) "[credential scope unavailable; output suppressed]" else scrub(text)
    private fun scrub(text: String): String {
        var s = text
        values.sortedByDescending { it.length }.forEach { s = s.replace(it, "<redacted>") }
        return redactForLog(s)
    }
    fun redact(value: JsonElement): JsonElement {
        val available = refresh()
        fun walk(v: JsonElement): JsonElement = when (v) {
            is JsonObject -> JsonObject(v.mapValues { walk(it.value) })
            is JsonArray -> JsonArray(v.map(::walk))
            is JsonPrimitive -> if (v.isString) JsonPrimitive(if (available) scrub(v.content) else "[credential scope unavailable; output suppressed]") else v
        }
        return walk(value)
    }
    fun containsSecret(value: JsonElement): Boolean = !refresh() || value.toString().let { raw -> values.any { raw.contains(it) } }
    /** Bounded whole-line buffering prevents partial PrintStream writes splitting a token. */
    fun logStream(destination: OutputStream): OutputStream = object : OutputStream() {
        private val line = java.io.ByteArrayOutputStream()
        private var clipped = false
        @Synchronized override fun write(b: Int) {
            if (b == 10) {
                val text = if (clipped) "[oversize log line suppressed]" else redact(line.toString(Charsets.UTF_8))
                destination.write((text + "\n").toByteArray(Charsets.UTF_8)); destination.flush()
                line.reset(); clipped = false
            } else if (line.size() < 64 * 1024) line.write(b) else clipped = true
        }
        @Synchronized override fun flush() { destination.flush() } // incomplete secret-bearing line waits for newline
    }
}
