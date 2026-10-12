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
    /** Scrubs every string: for content (outputs, errors, messages, logs) only, never for protocol control data. */
    fun redact(value: JsonElement): JsonElement = redact(value, keepControl = false)
    /**
     * Scrubs the content of a protocol structure (event payload, metadata) but never its control
     * fields ([CONTROL_FIELDS], at any depth). Those are platform-issued identities and protocol
     * structure that S and the platform match exactly; registered values are matched as substrings,
     * so a short credential field equal to e.g. "local" would otherwise turn `local-user` into
     * `<redacted>-user` and fail binding, lineage or sequence validation.
     */
    fun redactContent(value: JsonElement): JsonElement = redact(value, keepControl = true)
    private fun redact(value: JsonElement, keepControl: Boolean): JsonElement {
        val available = refresh()
        fun walk(v: JsonElement): JsonElement = when (v) {
            is JsonObject -> JsonObject(v.mapValues { (key, field) -> if (keepControl && key in CONTROL_FIELDS) field else walk(field) })
            is JsonArray -> JsonArray(v.map(::walk))
            is JsonPrimitive -> if (v.isString) JsonPrimitive(if (available) scrub(v.content) else "[credential scope unavailable; output suppressed]") else v
        }
        return walk(value)
    }
    /**
     * Whether any content string holds registered material. Object keys are structure, and so are the
     * values of [control] fields. Protocol metadata uses [CONTROL_FIELDS]; a structure that carries
     * user-named maps (workflow variables, approval items) passes only `binding`, so a user field that
     * happens to be named like a control field is still checked.
     */
    fun containsSecret(value: JsonElement, control: Set<String> = CONTROL_FIELDS): Boolean {
        if (!refresh()) return true
        fun walk(v: JsonElement): Boolean = when (v) {
            is JsonObject -> v.any { (key, field) -> key !in control && walk(field) }
            is JsonArray -> v.any(::walk)
            is JsonPrimitive -> v.isString && values.any { v.content.contains(it) }
        }
        return walk(value)
    }
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
    companion object {
        /** Platform-issued identities and protocol structure: never content, never scrubbed or treated as secret. */
        val CONTROL_FIELDS = setOf("binding", "protocolVersion", "frameId", "replyTo", "eventId", "eventSeq", "attemptId", "resumeEpoch",
            "executionId", "rootExecutionId", "runnerId", "runnerInstanceId", "ownerId", "actorUserId", "billingOwnerId",
            "definitionHash", "runtimeHash", "bundleHash", "environmentCheckId", "requestId", "requestFrameId", "credentialRef",
            "credentialLeaseId", "childExecutionPath", "purpose", "provider", "outcome", "expectedVersion", "status", "kind",
            "stepName", "workflowId", "producerStartEventId", "publicationId", "artifactId", "setId", "declarationIds")
    }
}

/**
 * A document that mixes identities with user content, as content only: [fields] are dropped from the
 * top-level object and from the named nested [envelopes] (objects or arrays of objects) and nowhere
 * else, so user maps (variables, approval items) below them are still checked in full.
 */
internal fun contentOf(value: JsonElement, fields: Set<String>, envelopes: Set<String>): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.filterKeys { it !in fields }.mapValues { (key, field) -> if (key in envelopes) contentOf(field, fields, envelopes) else field })
    is JsonArray -> JsonArray(value.map { contentOf(it, fields, envelopes) })
    else -> value
}
