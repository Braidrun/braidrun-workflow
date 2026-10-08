package com.fartech.agents.workflow

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.*

/** Internal trusted-host seam, not a serialized selector or a capability declaration. */
enum class WorkflowCodeControlMode { LEGACY_STEP_TOKEN, SUPERVISOR_PER_CALL }

/** Remove only reserved callback authority fields, including their generated environment aliases.
 * Frozen script bytes are never rewritten. Arbitrary secret-bearing source must be rejected by
 * admission/secret checks; this is not a general-purpose source-code or credential sanitizer. */
internal object WorkflowCodeControlTokens {
    private val json = Json { ignoreUnknownKeys = true }
    fun reserved(key: String): Boolean {
        val canonical = key.uppercase(java.util.Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "_")
        return canonical.removePrefix("WF_VAR_").removeSuffix("_FILE") in setOf("WF_API_TOKEN", "EXECUTION_API_TOKEN")
    }
    fun parameters(values: List<ConfigurationParameter>): List<ConfigurationParameter> = values.filterNot { reserved(it.key) }
        .map { ConfigurationParameter(it.key, element(it.value)) }
    fun environment(values: Map<String, String>): Map<String, String> = values.filterKeys { !reserved(it) }.mapValues { string(it.value) }
    fun variables(values: MutableMap<String, Any>) {
        values.keys.filter(::reserved).forEach(values::remove)
        values.toMap().forEach { (key, value) -> values[key] = clean(value) }
    }
    private fun clean(value: Any): Any = when (value) {
        is JsonElement -> element(value)
        is Map<*, *> -> value.filterKeys { it !is String || !reserved(it) }.mapValues { (_, v) -> v?.let(::clean) }
        is List<*> -> value.map { it?.let(::clean) }
        is String -> string(value)
        else -> value
    }
    private fun element(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { !reserved(it) }.mapValues { element(it.value) })
        is JsonArray -> JsonArray(value.map(::element))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(string(value.content)) else value
        else -> value
    }
    private fun string(value: String): String {
        val trimmed = value.trimStart()
        if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return value
        val parsed = runCatching { json.parseToJsonElement(value) }.getOrNull() ?: return value
        val cleaned = element(parsed)
        return if (cleaned == parsed) value else cleaned.toString()
    }
}
