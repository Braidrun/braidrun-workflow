package com.fartech.agents.commons

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import com.fartech.ftapp2.commonsKt.AnsiColor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SKILL.md frontmatter parsing.
 *
 * The primary path parses real YAML with kaml. The result is a flat map whose values are
 * `String` or `List<String>`, the same shape the historical line parser produced, so
 * [SkillLoader] and `ClaudeSkill.metadata` keep their contract:
 *
 * - Only **top-level** keys may set `name`, `description`, `tags`, `dependencies` and
 *   `attachments`. Nested maps such as `translations.<locale>.name` never leak into the skill
 *   identity (the line parser ignored indentation, so `apple-connect` loaded as `Apple`).
 * - `version` / `author` come from spec-style `metadata.version` / `metadata.author` first,
 *   then from the top-level keys.
 * - Other `metadata.*` entries are flattened into the result, as the line parser always did.
 *   Lists stay lists; nested maps are stringified as JSON.
 * - Other nested maps (for example `translations`) are not flattened; dedicated readers
 *   consume them.
 *
 * Frontmatter that is not valid YAML (most commonly an unquoted colon inside a value, e.g.
 * `description: Use when: the user asks`) falls back to [parseLenient], the historical line
 * parser, so skills that load today keep loading.
 */
internal object SkillFrontmatterParser {

    private const val METADATA_KEY = "metadata"

    /** Keys that decide what a skill is; nested (non top-level) values never set them. */
    private val IDENTITY_KEYS = setOf("name", "description", "tags", "dependencies", "attachments")

    /** Keys read from `metadata.*` first, then from the top level. */
    private val METADATA_FIRST_KEYS = setOf("version", "author")

    fun parse(yaml: String): Map<String, Any> {
        return try {
            parseYaml(yaml)
        } catch (e: Exception) {
            // kaml raises YamlException subclasses for malformed input; any other failure
            // must not make a skill that loads today disappear either.
            logProgress(
                AnsiColor.YELLOW,
                "Skills",
                "⚠ Frontmatter is not valid YAML (${e.message?.lineSequence()?.firstOrNull()}), using lenient parser"
            )
            parseLenient(yaml)
        }
    }

    /**
     * Parses [yaml] with kaml. Throws when the text is not valid YAML or its root is not a map.
     */
    internal fun parseYaml(yaml: String): Map<String, Any> {
        val root = unwrap(Yaml.default.parseToYamlNode(yaml)) as? YamlMap
            ?: throw IllegalArgumentException("frontmatter root is not a YAML map")

        val result = LinkedHashMap<String, Any>()
        var metadata: YamlMap? = null
        root.entries.forEach { (keyNode, valueNode) ->
            val key = keyNode.content
            when (val node = unwrap(valueNode)) {
                is YamlMap -> if (key == METADATA_KEY) metadata = node
                is YamlScalar -> result[key] = node.content
                is YamlList -> result[key] = node.items.mapNotNull(::stringify)
                else -> Unit // YamlNull: an empty key carries no value
            }
        }

        metadata?.entries?.forEach { (keyNode, valueNode) ->
            val key = keyNode.content
            val value = flattenedValue(unwrap(valueNode)) ?: return@forEach
            when (key) {
                in METADATA_FIRST_KEYS -> result[key] = value.toString()
                in IDENTITY_KEYS -> Unit
                else -> result.putIfAbsent(key, value)
            }
        }
        return result
    }

    private fun unwrap(node: YamlNode): YamlNode = if (node is YamlTaggedNode) unwrap(node.innerNode) else node

    /** `metadata.*` value: scalars stay strings, lists stay lists, maps become JSON text. */
    private fun flattenedValue(node: YamlNode): Any? = when (node) {
        is YamlList -> node.items.mapNotNull(::stringify)
        else -> stringify(node)
    }

    private fun stringify(node: YamlNode): String? = when (val unwrapped = unwrap(node)) {
        is YamlScalar -> unwrapped.content
        is YamlNull -> null
        else -> toJson(unwrapped).toString()
    }

    /** Scalars stay strings (`1.10` must not become `1.1`), unlike `YamlJsonElementSerializer`. */
    private fun toJson(node: YamlNode): JsonElement = when (val unwrapped = unwrap(node)) {
        is YamlScalar -> JsonPrimitive(unwrapped.content)
        is YamlList -> JsonArray(unwrapped.items.map(::toJson))
        is YamlMap -> JsonObject(unwrapped.entries.entries.associate { (k, v) -> k.content to toJson(v) })
        else -> JsonNull
    }

    /**
     * Block scalar mode for YAML `>` (folded) and `|` (literal) syntax.
     * FOLDED joins lines with spaces; LITERAL joins lines with newlines.
     */
    private enum class BlockScalarMode { FOLDED, LITERAL }

    /**
     * Historical line parser (handles key: value pairs, `- ` lists and block scalars).
     * Not a YAML parser: it ignores indentation, so nested keys overwrite top-level ones.
     * Kept only as the fallback for frontmatter that is not valid YAML, e.g.
     * ```yaml
     * description: Use this skill when: the user asks about PDFs
     * ```
     * where it treats everything after the first colon as the value.
     */
    internal fun parseLenient(yaml: String): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        var currentKey: String? = null
        val currentList = mutableListOf<String>()
        // Block scalar state: when a key's value is ">" or "|", subsequent indented lines
        // are collected here and joined according to the mode.
        var blockScalarMode: BlockScalarMode? = null
        val blockScalarLines = mutableListOf<String>()

        fun flushPending() {
            val key = currentKey ?: return
            when {
                blockScalarMode != null && blockScalarLines.isNotEmpty() -> {
                    val separator = if (blockScalarMode == BlockScalarMode.FOLDED) " " else "\n"
                    map[key] = blockScalarLines.joinToString(separator).trim()
                }
                currentList.isNotEmpty() -> {
                    map[key] = currentList.toList()
                }
            }
            currentList.clear()
            blockScalarLines.clear()
            blockScalarMode = null
            currentKey = null
        }

        yaml.lines().forEach { line ->
            val trimmed = line.trim()

            // When inside a block scalar, collect indented continuation lines.
            // A non-empty line that is NOT indented (no leading whitespace) signals the end
            // of the block scalar.  Empty lines inside block scalars are preserved for
            // literal mode and act as paragraph breaks for folded mode (approximated here
            // by simply skipping them, which matches how descriptions are typically used).
            if (blockScalarMode != null && currentKey != null) {
                if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                    // Indented continuation line — belongs to the block scalar
                    if (trimmed.isNotEmpty()) {
                        blockScalarLines.add(trimmed)
                    }
                    return@forEach
                } else if (trimmed.isEmpty()) {
                    // Blank line inside a block scalar — preserve for literal, skip for folded
                    if (blockScalarMode == BlockScalarMode.LITERAL) {
                        blockScalarLines.add("")
                    }
                    return@forEach
                } else {
                    // Non-indented, non-empty line — block scalar ends, fall through to normal parsing
                    flushPending()
                }
            }

            when {
                // Skip empty lines and comments
                trimmed.isEmpty() || trimmed.startsWith("#") -> {
                    // Do nothing
                }

                // List item (must start with "- ")
                trimmed.startsWith("- ") && currentKey != null -> {
                    var item = trimmed.substring(2).trim()
                    if (item.isNotEmpty()) {
                        // Handle basic quoting
                        if ((item.startsWith("\"") && item.endsWith("\"")) ||
                            (item.startsWith("'") && item.endsWith("'"))
                        ) {
                            item = item.substring(1, item.length - 1)
                        }
                        currentList.add(item)
                    }
                }

                // Key-value pair (contains ":")
                trimmed.contains(":") -> {
                    // Save previous pending data
                    flushPending()

                    // Parse new key-value pair
                    val colonIndex = trimmed.indexOf(':')
                    val key = trimmed.substring(0, colonIndex).trim()
                    var value = trimmed.substring(colonIndex + 1).trim()

                    if (key.isEmpty()) {
                        // Skip invalid keys
                        currentKey = null
                    } else if (value == ">" || value == ">-") {
                        // YAML folded block scalar
                        currentKey = key
                        blockScalarMode = BlockScalarMode.FOLDED
                    } else if (value == "|" || value == "|-") {
                        // YAML literal block scalar
                        currentKey = key
                        blockScalarMode = BlockScalarMode.LITERAL
                    } else if (value.isNotEmpty()) {
                        // Direct value
                        // Handle basic quoting
                        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))
                        ) {
                            value = value.substring(1, value.length - 1)
                        }
                        map[key] = value
                        currentKey = null
                    } else {
                        // Value on next lines (possibly a list)
                        currentKey = key
                    }
                }
            }
        }

        // Flush any remaining pending data
        flushPending()

        return map
    }
}
