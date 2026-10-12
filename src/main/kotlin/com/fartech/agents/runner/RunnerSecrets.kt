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
                registerLogin(material)
            } catch (_: Exception) { available = false }
        }
        return available
    }
    /**
     * Credential material (API keys, MCP/callback tokens, connector secret fields, a login's whole authJson):
     * every string value is secret as a whole, however short; an 8-character connector password is masked
     * and refused like any token. Only values of 1-3 characters are left out: masking or refusing them would
     * hit ordinary text everywhere. A value that is itself a login document (authJson) is also registered by
     * the login rules, so its individual tokens are masked wherever the CLI prints them.
     * Same rules as the Supervisor's remember_secrets / remember_login_secrets.
     */
    fun register(material: JsonElement) {
        when (material) {
            is JsonObject -> material.values.forEach(::register)
            is JsonArray -> material.forEach(::register)
            is JsonPrimitive -> if (material.isString && material.content.length > 3) {
                add(material.content)
                if (material.content.startsWith("{") || material.content.startsWith("[")) {
                    runCatching { runnerJson.parseToJsonElement(material.content) }.getOrNull()?.let { parsed ->
                        if (parsed is JsonObject || parsed is JsonArray) registerLogin(parsed)
                    }
                }
            }
        }
    }
    /**
     * A subscription login document (Codex auth.json, Claude .credentials.json): its token fields are secret,
     * its descriptive metadata is not. Taking every string for a secret made labels such as `auth_mode: local`
     * or `chatgpt` secret, and checkpoints, results and events merely containing `mac_mode=local` or an
     * attempt path were refused or mangled. Unknown fields count when their value looks like a token.
     */
    fun registerLogin(value: JsonElement, key: String? = null) {
        val name = key.orEmpty().lowercase(java.util.Locale.ROOT).replace("_", "").replace("-", "")
        when (value) {
            is JsonObject -> value.forEach { (child, item) -> registerLogin(item, child) }
            is JsonArray -> value.forEach { registerLogin(it, key) }
            is JsonPrimitive -> if (value.isString && value.content.length > 3 && name !in LOGIN_METADATA_KEYS &&
                (name in LOGIN_TOKEN_KEYS || looksLikeToken(value.content))) add(value.content)
        }
    }
    private fun add(value: String) {
        values.add(value)
        values.add(java.net.URLEncoder.encode(value, Charsets.UTF_8))
        values.add(JsonPrimitive(value).toString().removeSurrounding("\""))
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
        /** Login token fields, keys compared lower-case without `_`/`-` (Supervisor LOGIN_TOKEN_KEYS). */
        private val LOGIN_TOKEN_KEYS = setOf("accesstoken", "refreshtoken", "idtoken", "openaiapikey", "apikey", "token", "sessiontoken",
            "sessionkey", "cookie", "cookies", "clientsecret", "secret", "password")
        /** Descriptive login metadata, never secret (Supervisor LOGIN_METADATA_KEYS). */
        private val LOGIN_METADATA_KEYS = setOf("authmode", "lastrefresh", "scopes", "subscriptiontype", "ratelimittier", "expiresat",
            "tokentype", "type", "provider", "accountid", "email", "plan", "organizationid")
        /** An unknown login field holding a token: one long, unbroken, high-entropy string (>= 24 chars, >= 3 bits/char). */
        internal fun looksLikeToken(value: String): Boolean {
            if (value.length < 24 || value.any { it.isWhitespace() }) return false
            val entropy = value.groupingBy { it }.eachCount().values.sumOf { count ->
                val p = count.toDouble() / value.length; -p * kotlin.math.ln(p) / kotlin.math.ln(2.0)
            }
            return entropy >= 3.0
        }
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
