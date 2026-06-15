package com.fartech.ftapp2.commonsKt

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
data class ConfigurationParameter(
    @SerialName(value = "key") val key: String = "",
    @SerialName(value = "value") val value: JsonElement = JsonNull,
    @SerialName(value = "ref") val ref: String? = null
)

@Serializable
data class Configuration(
    @SerialName(value = "cmd") val cmd: String = "",
    @SerialName(value = "parameters") val parameters: List<ConfigurationParameter> = listOf(),
    @SerialName(value = "ref") val ref: String? = null
)

val JsonMapper = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    coerceInputValues = true
}

val YamlMapper = Yaml(
    configuration = YamlConfiguration(
        strictMode = false
    )
)

/**
 * Hard cap on YAML→JSON tree depth (Phase 11). Workflows / configs nest a few levels
 * deep at most; an adversarial input with thousands of levels would `StackOverflowError`
 * the recursive walk below long before reaching its leaves.
 */
private const val MAX_YAML_NESTING_DEPTH = 128

fun YamlNode.toJsonElement(): JsonElement = toJsonElementBounded(0)

private fun YamlNode.toJsonElementBounded(depth: Int): JsonElement {
    if (depth > MAX_YAML_NESTING_DEPTH) {
        throw IllegalArgumentException(
            "YAML nesting exceeds depth cap of $MAX_YAML_NESTING_DEPTH — likely malformed or adversarial input"
        )
    }
    return when (this) {
        is YamlScalar -> {
            when (val content = this.content) {
                "null" -> JsonNull
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> {
                    // Try Long FIRST: routing integers through Double silently corrupts
                    // values above 2^53 (snowflake ids, 17+ digit numbers) —
                    // 123456789012345678 became …680.
                    val longValue = content.toLongOrNull()
                    if (longValue != null) {
                        JsonPrimitive(longValue)
                    } else {
                        val doubleValue = content.toDoubleOrNull()
                        if (doubleValue != null && doubleValue.isFinite()) {
                            JsonPrimitive(doubleValue)
                        } else {
                            // Non-numeric, or non-finite ("NaN"/"Infinity" would fail
                            // on JSON re-encode) — keep the literal string.
                            JsonPrimitive(content)
                        }
                    }
                }
            }
        }

        is YamlMap -> JsonObject(
            this.entries.mapKeys { it.key.content }.mapValues { it.value.toJsonElementBounded(depth + 1) }
        )
        is YamlList -> JsonArray(this.items.map { it.toJsonElementBounded(depth + 1) })
        is YamlNull -> JsonNull
        else -> JsonNull
    }
}

/**
 * Allowed roots for configuration `ref:` dereferencing. An operator can extend this at
 * deploy time via the `BRAIDRUN_CONFIG_REF_ROOTS` env var (OS-path-separator-joined list).
 *
 * **Why the allowlist**: YAML config files can appear from user uploads / remote fetches.
 * A malicious config with `ref: "/etc/shadow"` (or Windows equivalent) would otherwise
 * cause the configuration loader to read arbitrary files the JVM has permission to see.
 * Limiting to a known set of roots closes that vector.
 */
private val CONFIG_REF_ALLOWED_ROOTS: List<Path> by lazy {
    val fromEnv = (System.getenv("BRAIDRUN_CONFIG_REF_ROOTS") ?: "")
        .split(java.io.File.pathSeparatorChar)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val defaults = listOf(
        System.getProperty("user.dir"),
        System.getProperty("java.io.tmpdir")
    ).filterNotNull()
    (fromEnv + defaults)
        .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        .distinct()
}

/**
 * Resolve a configuration `ref:` path *relative to a caller-provided base* and reject
 * anything that escapes the configured allowlist of roots. Rejects:
 *   - Paths that fail normalization
 *   - Paths whose canonical form is not under any allowed root
 *   - Symlinks that resolve outside the allowlist
 */
internal fun resolveAndValidateConfigRef(rawPath: String, baseDir: Path? = null): Path {
    val raw = Path.of(rawPath)
    val resolvedFromBase = if (raw.isAbsolute || baseDir == null) raw else baseDir.resolve(raw)
    val normalized = resolvedFromBase.toAbsolutePath().normalize()

    // Canonicalize via toRealPath() when the file exists, to defeat symlink escapes.
    // If it doesn't exist yet, fall back to the normalized form — the real read below
    // will fail cleanly anyway.
    val canonical = runCatching {
        normalized.toRealPath()
    }.getOrElse { normalized }

    val allowedRoots = CONFIG_REF_ALLOWED_ROOTS
    val permitted = allowedRoots.any { root ->
        canonical.startsWith(root) || canonical == root
    }
    require(permitted) {
        "Configuration ref '$rawPath' resolves to '$canonical' which is outside the allowed " +
            "roots $allowedRoots. Set BRAIDRUN_CONFIG_REF_ROOTS to widen if intentional."
    }
    return canonical
}

private fun loadJsonElementFromFile(filePath: Path, baseDir: Path? = null): JsonElement {
    // Defense-in-depth: callers now go through resolveAndValidateConfigRef, but we also
    // sanity-check here so any direct internal use still gets the allowlist guard.
    val validated = resolveAndValidateConfigRef(filePath.toString(), baseDir)

    if (!Files.exists(validated)) {
        throw IllegalArgumentException("File not found: $validated")
    }
    if (!Files.isRegularFile(validated)) {
        throw IllegalArgumentException("Not a regular file: $validated")
    }

    val content = try {
        Files.readString(validated)
    } catch (e: Exception) {
        throw IllegalArgumentException("Failed to read file: $validated", e)
    }

    return try {
        val lower = validated.toString().lowercase()
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            YamlMapper.parseToYamlNode(content).toJsonElement()
        } else {
            JsonMapper.decodeFromString<JsonElement>(content)
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Failed to parse file: $validated", e)
    }
}

private fun JsonElement.toConfiguration(): Configuration {
    if (this !is JsonObject) throw IllegalArgumentException("Configuration must be an object")

    val cmd = (this["cmd"] as? JsonPrimitive)?.content ?: ""
    val ref = (this["ref"] as? JsonPrimitive)?.content
    val explicitParameters = this["parameters"]?.toConfigurationParameters() ?: emptyList()
    val otherParameters = this.filter { (key, _) ->
        key != "cmd" && key != "ref" && key != "parameters"
    }.map { (key, value) ->
        ConfigurationParameter(key = key, value = value)
    }

    return Configuration(cmd = cmd, parameters = explicitParameters + otherParameters, ref = ref)
}

private fun JsonElement.toConfigurationParameters(): List<ConfigurationParameter> {
    return when (this) {
        is JsonObject -> {
            val hasKeyField = this["key"]?.let { it is JsonPrimitive && it.isString } == true
            val hasRefField = this["ref"]?.let { it is JsonPrimitive && it.isString } == true

            if (hasKeyField || (hasRefField && this.size <= 2)) {
                listOf(JsonMapper.decodeFromJsonElement<ConfigurationParameter>(this))
            } else {
                this.map { (k, v) -> ConfigurationParameter(key = k, value = v) }
            }
        }

        is JsonArray -> this.flatMap { it.toConfigurationParameters() }
        else -> throw IllegalArgumentException("Invalid parameter format: $this")
    }
}

inline fun <reified T> List<ConfigurationParameter>.parameter(key: String, defaultValue: T): T =
    this.find { it.key == key }?.value?.let {
        if (it is JsonNull) defaultValue else JsonMapper.decodeFromJsonElement<T>(it)
    } ?: defaultValue

inline fun <reified T> List<ConfigurationParameter>.oneOfParameter(key: String, defaultValue: T, allowed: List<T>): T {
    val v = parameter(key, defaultValue)
    if (allowed.contains(v)) return v
    throw IllegalArgumentException("Invalid value for parameter $key: $v, allowed values: $allowed")
}

inline fun <reified T> List<ConfigurationParameter>.oneOfParameterCollection(
    key: String,
    defaultValue: List<T>,
    allowedElements: List<T>
): List<T> {
    val v = parameter(key, defaultValue)
    if (v.all { allowedElements.contains(it) }) return v
    throw IllegalArgumentException("Invalid value for parameter $key: $v, allowed values: $allowedElements")
}

fun List<ConfigurationParameter>.containsKey(key: String): Boolean =
    this.any { it.key == key }

fun List<ConfigurationParameter>.count(key: String): Int =
    this.count { it.key == key }

/** Hard cap on `sd: "-N"` offset (Phase 11). 100 years is far above any reporting horizon. */
const val MAX_DATE_OFFSET_DAYS: Int = 36_500

fun List<ConfigurationParameter>.date(type: String): OffsetDateTime {
    val timezone = parameter("timezone", "UTC")
    val offset = try {
        if (timezone == "UTC") ZoneOffset.UTC else ZoneOffset.of(timezone)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid timezone: $timezone", e)
    }

    val dateString = if (type == "sd" && parameter("sd", "").startsWith("-")) {
        val sdValue = parameter(type, "")
        // Phase 11: strict parse + range clamp. Previous code accepted Int.MIN_VALUE which
        // then flipped sign inside `minusDays(daysBefore.toLong())` and produced dates in
        // the year ~5,884,000 (LocalDate's upper limit), causing surprising downstream
        // behaviour. Cap at 100 years (36500 days) — anything older than that is far
        // beyond any plausible analytics window.
        val rawDays = sdValue.substring(1).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid date format for $type: $sdValue (expected -N where N is a positive integer)")
        if (rawDays < 0 || rawDays > MAX_DATE_OFFSET_DAYS) {
            throw IllegalArgumentException("Date offset $rawDays out of range (must be 0..$MAX_DATE_OFFSET_DAYS days)")
        }
        dateBeforeDays(rawDays)
    } else {
        parameter(type, todayDate())
    }

    return try {
        "${dateString}T00:00:00Z".offsetDateTime(offset)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid date string: $dateString", e)
    }
}

fun todayDate(): String {
    val currentDate = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    return currentDate.format(formatter)
}

fun dateBeforeDays(daysBefore: Int): String {
    val date = LocalDate.now().minusDays(daysBefore.toLong())
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    return date.format(formatter)
}

fun String.offsetDateTime(zone: ZoneOffset): OffsetDateTime =
    OffsetDateTime.parse(this).withOffsetSameInstant(zone)

fun List<ConfigurationParameter>.startDate() = date("sd")
fun List<ConfigurationParameter>.endDate() = date("ed")

fun List<ConfigurationParameter>.openAIHeaders() =
    run {
        val authHeader = parameter("auth_header", "Authorization")
        val authScheme = parameter("auth_scheme", "Bearer")
        val apiKey = parameter<String?>("apiKey", null)
            ?: throw IllegalArgumentException("Missing required parameter: apiKey")
        val value = if (authScheme.isBlank()) apiKey else "$authScheme $apiKey"
        mapOf(
            HttpHeaders.ContentType to "application/json",
            authHeader to value
        )
    }

inline fun <reified T> List<ConfigurationParameter>.setValue(
    key: String,
    value: T,
    replaceAll: Boolean = false
): List<ConfigurationParameter> {
    val findKey = this.find { it.key == key }
    return if (findKey == null) {
        val newList = this.toMutableList()
        newList.add(ConfigurationParameter(key, JsonMapper.encodeToJsonElement(value)))
        newList.toList()
    } else {
        var firstReplaced = false
        val valueJsonElement = JsonMapper.encodeToJsonElement(value)
        fold(mutableListOf()) { acc, parameter ->
            if (parameter.key == key) {
                if (!firstReplaced) {
                    acc.add(ConfigurationParameter(key, valueJsonElement))
                    firstReplaced = true
                } else if (replaceAll) {
                    acc.add(ConfigurationParameter(key, valueJsonElement))
                } else {
                    acc.add(parameter)
                }
            } else {
                acc.add(parameter)
            }
            acc
        }
    }
}

fun List<ConfigurationParameter>.ensureContains(keys: List<String>) = checkRequired(this, keys)

fun List<ConfigurationParameter>.ensureKeyCount(keys: Map<String, Int>) {
    val violations = keys.mapNotNull { (key, maxCount) ->
        val actualCount = this.count(key)
        if (actualCount > maxCount) "$key: found $actualCount, max allowed $maxCount" else null
    }
    if (violations.isNotEmpty()) {
        throw IllegalArgumentException("Parameter count violations: ${violations.joinToString("; ")}")
    }
}

fun checkRequired(
    parameters: List<ConfigurationParameter>,
    keys: List<String>
): List<ConfigurationParameter> {
    keys.forEach { key ->
        when (parameters.count { it.key == key }) {
            0 -> throw IllegalArgumentException("Missing required parameter $key")
            1 -> {}
            else -> throw IllegalArgumentException("Duplicate parameter $key")
        }
    }
    return parameters
}

fun loadConfiguration(filePath: Path): List<Configuration> {
    val firstLoad = when (val jsons = loadJsonElementFromFile(filePath)) {
        is JsonArray -> jsons.map { it.toConfiguration() }
        is JsonObject -> listOf(jsons.toConfiguration())
        else -> throw IllegalArgumentException("Invalid configuration format at $filePath")
    }
    // Relative `ref:` paths resolve against the REFERENCING file's directory (as
    // resolveAndValidateConfigRef documents) — resolving against the process CWD
    // made configs work or break depending on launch directory, and the allowlist
    // check then applied to the wrong path.
    val baseDir = filePath.toAbsolutePath().normalize().parent
    return firstLoad.fold(mutableListOf()) { acc, configuration ->
        if (configuration.ref != null) {
            when (val refJsons = loadJsonElementFromFile(Path.of(configuration.ref), baseDir)) {
                is JsonObject -> acc.add(loadParameters(refJsons.toConfiguration(), baseDir))
                is JsonArray -> {
                    refJsons.forEach {
                        acc.add(loadParameters(it.toConfiguration(), baseDir))
                    }
                }

                else -> throw IllegalArgumentException("Invalid ref: ${configuration.ref}")
            }
        } else {
            acc.add(loadParameters(configuration, baseDir))
        }
        acc
    }
}

fun loadParameters(configuration: Configuration, baseDir: Path? = null): Configuration {
    val parameters = configuration.parameters.fold(mutableListOf<ConfigurationParameter>()) { acc, parameter ->
        if (parameter.ref != null) {
            val j = loadJsonElementFromFile(Path.of(parameter.ref), baseDir)
            acc.addAll(j.toConfigurationParameters())
        } else {
            acc.add(parameter)
        }
        acc
    }
    return configuration.copy(parameters = parameters)
}
