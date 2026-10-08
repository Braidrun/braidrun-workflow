package com.fartech.agents.runner

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.serialization.json.*
import java.time.Instant

/** Failures never include untrusted payloads (authorization frames contain secrets). */
class RunnerProtocolException : IllegalArgumentException("Invalid runner protocol frame")

internal val runnerJson = Json { encodeDefaults = true; isLenient = false }
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toBigDecimal().longValueExact()
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

/** Small validator for the PINNED schema's vocabulary; unsupported keywords fail at construction.
 * This does not authorize a frame. Binding, direction, monotonicity and service permissions are
 * independently validated by the session. The canonical schema is copied byte-for-byte from Git.
 */
class RunnerProtocol internal constructor(private val schema: JsonObject) {
    constructor() : this(runnerJson.parseToJsonElement(requireNotNull(RunnerProtocol::class.java.getResourceAsStream(
        "/runner-workflow-v1/protocol.schema.json"
    )).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject)
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    init {
        fun checkRule(rule: JsonObject) {
            val supported = setOf("\$schema", "\$id", "\$defs", "\$ref", "oneOf", "type", "properties", "required", "additionalProperties", "const", "enum", "items", "minLength", "maxLength", "pattern", "format", "minimum", "maximum", "minItems", "maxItems", "title", "description")
            require(rule.keys.all { it in supported }) { "Pinned runner schema uses unsupported vocabulary" }
            rule["\$defs"]?.jsonObject?.values?.forEach { checkRule(it.jsonObject) }
            rule["properties"]?.jsonObject?.values?.forEach { checkRule(it.jsonObject) }
            rule["oneOf"]?.jsonArray?.forEach { checkRule(it.jsonObject) }
            (rule["items"] as? JsonObject)?.let(::checkRule)
            (rule["additionalProperties"] as? JsonObject)?.let(::checkRule)
        }
        checkRule(schema)
    }

    fun parse(line: String, definition: String = "ipcFrame"): JsonObject = try {
        val value = decode(line)
        validate(value, definition)
        value
    } catch (_: Exception) { throw RunnerProtocolException() }

    internal fun decode(line: String): JsonObject = try {
        val parser = mapper.factory.createParser(line)
        val tree = parser.use {
            val value = mapper.readTree<com.fasterxml.jackson.databind.JsonNode>(it)
            if (it.nextToken() != null) throw RunnerProtocolException()
            value
        }
        runnerJson.parseToJsonElement(tree.toString()).jsonObject
    } catch (_: Exception) { throw RunnerProtocolException() }

    fun validate(value: JsonElement, definition: String) {
        val def = schema.obj("\$defs")[definition]?.jsonObject ?: throw RunnerProtocolException()
        if (!matches(value, def)) throw RunnerProtocolException()
    }

    private fun matches(value: JsonElement, rule: JsonObject): Boolean {
        rule["\$ref"]?.let { ref ->
            val key = ref.jsonPrimitive.content.removePrefix("#/\$defs/")
            return matches(value, schema.obj("\$defs").getValue(key).jsonObject)
        }
        rule["oneOf"]?.let { return it.jsonArray.count { r -> matches(value, r.jsonObject) } == 1 }
        rule["const"]?.let { if (value != it) return false }
        rule["enum"]?.let { if (value !in it.jsonArray) return false }
        rule["type"]?.let { type ->
            val types = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (types.none { isType(value, it) }) return false
        }
        if (value is JsonObject) {
            val properties = rule["properties"]?.jsonObject ?: JsonObject(emptyMap())
            if (rule["additionalProperties"] == JsonPrimitive(false) && value.keys.any { it !in properties }) return false
            if (rule["required"]?.jsonArray?.any { it.jsonPrimitive.content !in value } == true) return false
            val extra = rule["additionalProperties"]
            if (extra is JsonObject && value.any { (key, v) -> key !in properties && !matches(v, extra) }) return false
            if (properties.any { (k, r) -> value[k]?.let { !matches(it, r.jsonObject) } == true }) return false
        }
        if (value is JsonArray) {
            rule["minItems"]?.let { if (value.size < it.jsonPrimitive.int) return false }
            rule["maxItems"]?.let { if (value.size > it.jsonPrimitive.int) return false }
            rule["items"]?.let { r -> if (value.any { !matches(it, r.jsonObject) }) return false }
        }
        if (value is JsonPrimitive && value.isString) {
            val s = value.content
            rule["minLength"]?.let { if (s.codePointCount(0, s.length) < it.jsonPrimitive.int) return false }
            rule["maxLength"]?.let { if (s.codePointCount(0, s.length) > it.jsonPrimitive.int) return false }
            rule["pattern"]?.let { if (!Regex(it.jsonPrimitive.content).containsMatchIn(s)) return false }
            if (rule["format"] == JsonPrimitive("date-time") && runCatching { Instant.parse(s) }.isFailure) return false
        }
        if (value is JsonPrimitive && !value.isString && value != JsonNull) {
            val n = value.content.toBigDecimalOrNull()
            if (n != null) {
                rule["minimum"]?.let { if (n < it.jsonPrimitive.content.toBigDecimal()) return false }
                rule["maximum"]?.let { if (n > it.jsonPrimitive.content.toBigDecimal()) return false }
            }
        }
        return true
    }

    private fun isType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value == JsonNull
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
        "number" -> value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull() != null
        else -> false
    }
}

/** Official canonical metadata encoding: sorted object keys, compact UTF-8, stable arrays. */
internal fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
    else -> value.toString()
}
