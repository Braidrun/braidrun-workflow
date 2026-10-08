package com.fartech.agents.runner

import com.fartech.ftapp2.commonsKt.redactForLog
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArraySet

/** Execution-local values, retained through cleanup so late logs are still redacted. */
class RunnerSecrets {
    private val values = CopyOnWriteArraySet<String>()
    fun register(material: JsonElement) {
        when (material) {
            is JsonObject -> material.values.forEach(::register)
            is JsonArray -> material.forEach(::register)
            is JsonPrimitive -> if (material.isString && material.content.isNotEmpty()) {
                values.add(material.content)
                values.add(java.net.URLEncoder.encode(material.content, Charsets.UTF_8))
                values.add(JsonPrimitive(material.content).toString().removeSurrounding("\""))
            }
        }
    }
    fun redact(text: String): String {
        var s = text
        values.sortedByDescending { it.length }.forEach { s = s.replace(it, "<redacted>") }
        return redactForLog(s)
    }
    fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content)) else value
    }
    fun containsSecret(value: JsonElement): Boolean = value.toString().let { raw -> values.any { raw.contains(it) } }
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
