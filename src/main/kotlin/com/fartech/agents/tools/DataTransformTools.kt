package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

@LLMDescription(
    "Data transformation and conversion tools. Supports JSON/YAML conversion, " +
            "Base64 encoding/decoding, URL encoding/decoding, JSON path queries, " +
            "Markdown/HTML conversion, and XML parsing."
)
object DataTransformTools : ToolSet {

    private val jsonFormat = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private const val MAX_XML_DEPTH = 50

    // Pre-compiled regex patterns for markdownToHtml
    private val MD_FENCED_CODE = Regex("```(\\w*)\\n([\\s\\S]*?)```")
    private val MD_INLINE_CODE = Regex("`([^`]+)`")
    private val MD_H6 = Regex("^#{6}\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_H5 = Regex("^#{5}\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_H4 = Regex("^#{4}\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_H3 = Regex("^###\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_H2 = Regex("^##\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_H1 = Regex("^#\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_BOLD_ITALIC = Regex("\\*\\*\\*(.+?)\\*\\*\\*")
    private val MD_BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val MD_ITALIC = Regex("\\*(.+?)\\*")
    private val MD_IMAGE = Regex("!\\[([^]]*)]\\(([^)]+)\\)")
    private val MD_LINK = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    private val MD_BLOCKQUOTE = Regex("^>\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_HR = Regex("^---+$", RegexOption.MULTILINE)
    private val MD_UL = Regex("^[*-]\\s+(.+)$", RegexOption.MULTILINE)
    private val MD_PARAGRAPH = Regex("\n\n")
    private val MD_EMPTY_P = Regex("<p>\\s*</p>")

    // Pre-compiled regex patterns for htmlToMarkdown
    private val HTML_H1 = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_H2 = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_H3 = Regex("<h3[^>]*>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_H4 = Regex("<h4[^>]*>(.*?)</h4>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_H5 = Regex("<h5[^>]*>(.*?)</h5>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_H6 = Regex("<h6[^>]*>(.*?)</h6>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_STRONG = Regex("<strong[^>]*>(.*?)</strong>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_B = Regex("<b[^>]*>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_EM = Regex("<em[^>]*>(.*?)</em>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_I = Regex("<i[^>]*>(.*?)</i>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_CODE = Regex("<code[^>]*>(.*?)</code>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_PRE = Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_A = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_IMG = Regex("<img[^>]*src=\"([^\"]+)\"[^>]*alt=\"([^\"]*)\"/?>")
    private val HTML_LI = Regex("<li[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_P = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_BR = Regex("<br\\s*/?>")
    private val HTML_HR = Regex("<hr\\s*/?>")
    private val HTML_TAGS = Regex("<[^>]+>")
    private val HTML_EXTRA_NEWLINES = Regex("\n{3,}")

    // ==================== JSON / YAML ====================

    @Tool
    @LLMDescription(
        "Convert a JSON string to YAML format. " +
                "Handles nested objects, arrays, and all JSON data types."
    )
    fun jsonToYaml(
        @LLMDescription("JSON string to convert")
        json: String
    ): String {
        return try {
            val element = Json.parseToJsonElement(json)
            jsonElementToYaml(element, 0)
        } catch (e: Exception) {
            "Error converting JSON to YAML: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Convert a basic/flat YAML string to JSON format. " +
                "Supports flat mappings, simple sequences, and scalars. " +
                "Does NOT support nested objects, multi-line values (| or >), anchors/aliases, or flow collections."
    )
    fun yamlToJson(
        @LLMDescription("YAML string to convert")
        yaml: String
    ): String {
        return try {
            val element = parseYamlToJsonElement(yaml)
            jsonFormat.encodeToString(JsonElement.serializer(), element)
        } catch (e: Exception) {
            "Error converting YAML to JSON: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Format (pretty-print) a JSON string with proper indentation."
    )
    fun formatJson(
        @LLMDescription("JSON string to format")
        json: String
    ): String {
        return try {
            val element = Json.parseToJsonElement(json)
            jsonFormat.encodeToString(JsonElement.serializer(), element)
        } catch (e: Exception) {
            "Error formatting JSON: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Minify a JSON string by removing all whitespace and newlines."
    )
    fun minifyJson(
        @LLMDescription("JSON string to minify")
        json: String
    ): String {
        return try {
            val element = Json.parseToJsonElement(json)
            Json.encodeToString(JsonElement.serializer(), element)
        } catch (e: Exception) {
            "Error minifying JSON: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Query a JSON document using a simple dot-notation path (e.g., 'data.users[0].name'). " +
                "Supports object property access with dots and array indexing with brackets."
    )
    fun jsonQuery(
        @LLMDescription("JSON string to query")
        json: String,
        @LLMDescription("Query path in dot notation (e.g., 'data.items[0].name', 'results[*].id')")
        path: String
    ): String {
        return try {
            val element = Json.parseToJsonElement(json)
            val result = navigateJsonPath(element, path)
            if (result is JsonPrimitive) {
                result.content
            } else {
                jsonFormat.encodeToString(JsonElement.serializer(), result)
            }
        } catch (e: Exception) {
            "Error querying JSON: ${e.message}"
        }
    }

    // ==================== Base64 ====================

    @Tool
    @LLMDescription("Encode a string to Base64.")
    fun base64Encode(
        @LLMDescription("String to encode")
        input: String,
        @LLMDescription("Whether to use URL-safe Base64 encoding (default false)")
        urlSafe: Boolean = false
    ): String {
        return try {
            val encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
            encoder.encodeToString(input.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            "Error encoding to Base64: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Decode a Base64 string back to plain text.")
    fun base64Decode(
        @LLMDescription("Base64 encoded string to decode")
        input: String,
        @LLMDescription("Whether the input uses URL-safe Base64 encoding (default false)")
        urlSafe: Boolean = false
    ): String {
        return try {
            val decoder = if (urlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
            String(decoder.decode(input), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            "Error decoding Base64: ${e.message}"
        }
    }

    // ==================== URL Encoding ====================

    @Tool
    @LLMDescription("URL-encode a string (percent encoding).")
    fun urlEncode(
        @LLMDescription("String to URL-encode")
        input: String
    ): String {
        return try {
            URLEncoder.encode(input, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            "Error URL-encoding: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("URL-decode a percent-encoded string.")
    fun urlDecode(
        @LLMDescription("URL-encoded string to decode")
        input: String
    ): String {
        return try {
            URLDecoder.decode(input, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            "Error URL-decoding: ${e.message}"
        }
    }

    // ==================== HTML / Markdown ====================

    @Tool
    @LLMDescription(
        "Convert Markdown text to HTML. Supports basic Markdown syntax: " +
                "headers, bold, italic, code blocks, links, images, lists, blockquotes, and horizontal rules."
    )
    fun markdownToHtml(
        @LLMDescription("Markdown text to convert")
        markdown: String
    ): String {
        return try {
            var html = markdown

            // Code blocks (fenced)
            html = html.replace(MD_FENCED_CODE) { match ->
                val lang = match.groupValues[1]
                val code = match.groupValues[2].trimEnd()
                if (lang.isNotBlank()) "<pre><code class=\"language-$lang\">$code</code></pre>"
                else "<pre><code>$code</code></pre>"
            }

            // Inline code
            html = html.replace(MD_INLINE_CODE, "<code>$1</code>")

            // Headers (most specific first)
            html = html.replace(MD_H6, "<h6>$1</h6>")
            html = html.replace(MD_H5, "<h5>$1</h5>")
            html = html.replace(MD_H4, "<h4>$1</h4>")
            html = html.replace(MD_H3, "<h3>$1</h3>")
            html = html.replace(MD_H2, "<h2>$1</h2>")
            html = html.replace(MD_H1, "<h1>$1</h1>")

            // Bold and italic
            html = html.replace(MD_BOLD_ITALIC, "<strong><em>$1</em></strong>")
            html = html.replace(MD_BOLD, "<strong>$1</strong>")
            html = html.replace(MD_ITALIC, "<em>$1</em>")

            // Images and links
            html = html.replace(MD_IMAGE, "<img src=\"$2\" alt=\"$1\">")
            html = html.replace(MD_LINK, "<a href=\"$2\">$1</a>")

            // Blockquotes
            html = html.replace(MD_BLOCKQUOTE, "<blockquote>$1</blockquote>")

            // Horizontal rules
            html = html.replace(MD_HR, "<hr>")

            // Unordered lists
            html = html.replace(MD_UL, "<li>$1</li>")

            // Line breaks for paragraphs
            html = html.replace(MD_PARAGRAPH, "</p>\n<p>")
            html = "<p>$html</p>"

            // Clean up empty paragraphs
            html = html.replace(MD_EMPTY_P, "")

            html
        } catch (e: Exception) {
            "Error converting Markdown to HTML: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Convert HTML to plain text / basic Markdown by stripping tags and converting common elements."
    )
    fun htmlToMarkdown(
        @LLMDescription("HTML string to convert")
        html: String
    ): String {
        return try {
            var md = html

            // Headers
            md = md.replace(HTML_H1, "# $1\n\n")
            md = md.replace(HTML_H2, "## $1\n\n")
            md = md.replace(HTML_H3, "### $1\n\n")
            md = md.replace(HTML_H4, "#### $1\n\n")
            md = md.replace(HTML_H5, "##### $1\n\n")
            md = md.replace(HTML_H6, "###### $1\n\n")

            // Bold and italic
            md = md.replace(HTML_STRONG, "**$1**")
            md = md.replace(HTML_B, "**$1**")
            md = md.replace(HTML_EM, "*$1*")
            md = md.replace(HTML_I, "*$1*")

            // Code
            md = md.replace(HTML_CODE, "`$1`")
            md = md.replace(HTML_PRE, "```\n$1\n```\n")

            // Links and images
            md = md.replace(HTML_A, "[$2]($1)")
            md = md.replace(HTML_IMG, "![$2]($1)")

            // Lists
            md = md.replace(HTML_LI, "- $1\n")

            // Paragraphs and line breaks
            md = md.replace(HTML_P, "$1\n\n")
            md = md.replace(HTML_BR, "\n")
            md = md.replace(HTML_HR, "---\n")

            // Strip remaining tags
            md = md.replace(HTML_TAGS, "")

            // Decode HTML entities
            md = md.replace("&amp;", "&")
            md = md.replace("&lt;", "<")
            md = md.replace("&gt;", ">")
            md = md.replace("&quot;", "\"")
            md = md.replace("&#39;", "'")
            md = md.replace("&nbsp;", " ")

            // Clean up extra whitespace
            md = md.replace(HTML_EXTRA_NEWLINES, "\n\n")

            md.trim()
        } catch (e: Exception) {
            "Error converting HTML to Markdown: ${e.message}"
        }
    }

    // ==================== XML ====================

    @Tool
    @LLMDescription(
        "Parse an XML string and convert it to a JSON representation for easier processing."
    )
    fun xmlToJson(
        @LLMDescription("XML string to parse")
        xml: String
    ): String {
        return try {
            val result = parseXmlToJson(xml)
            jsonFormat.encodeToString(JsonElement.serializer(), result)
        } catch (e: Exception) {
            "Error converting XML to JSON: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Extract text content from specific XML tags by tag name."
    )
    fun xmlExtractByTag(
        @LLMDescription("XML string to search")
        xml: String,
        @LLMDescription("Tag name to extract (e.g., 'title', 'item')")
        tagName: String
    ): String {
        return try {
            val pattern =
                Regex("<$tagName[^>]*>(.*?)</$tagName>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val matches = pattern.findAll(xml).toList()

            if (matches.isEmpty()) {
                "No <$tagName> elements found."
            } else {
                val sb = StringBuilder()
                sb.appendLine("Found ${matches.size} <$tagName> element(s):")
                matches.forEachIndexed { index, match ->
                    sb.appendLine("  [${index + 1}]: ${match.groupValues[1].trim()}")
                }
                sb.toString()
            }
        } catch (e: Exception) {
            "Error extracting XML tag: ${e.message}"
        }
    }

    // ==================== Helpers ====================

    private fun jsonElementToYaml(element: JsonElement, indent: Int): String {
        val prefix = "  ".repeat(indent)
        return when (element) {
            is JsonObject -> {
                if (element.isEmpty()) "{}"
                else element.entries.joinToString("\n") { (key, value) ->
                    when (value) {
                        is JsonObject -> "$prefix$key:\n${jsonElementToYaml(value, indent + 1)}"
                        is JsonArray -> "$prefix$key:\n${jsonElementToYaml(value, indent + 1)}"
                        else -> "$prefix$key: ${jsonElementToYaml(value, indent)}"
                    }
                }
            }

            is JsonArray -> {
                if (element.isEmpty()) "[]"
                else element.joinToString("\n") { item ->
                    when (item) {
                        is JsonObject -> "$prefix- \n${jsonElementToYaml(item, indent + 1)}"
                        is JsonArray -> "$prefix- \n${jsonElementToYaml(item, indent + 1)}"
                        else -> "$prefix- ${jsonElementToYaml(item, 0)}"
                    }
                }
            }

            is JsonPrimitive -> {
                if (element.isString) {
                    val content = element.content
                    if (content.contains("\n") || content.contains(": ") || content.contains("#")) {
                        "\"${content.replace("\"", "\\\"")}\""
                    } else {
                        content
                    }
                } else {
                    element.content
                }
            }
        }
    }

    /**
     * Simple YAML parser that handles only flat structures:
     * - Flat key-value mappings (key: value)
     * - Simple sequences (- item)
     * - Scalar values (strings, numbers, booleans, null)
     *
     * Limitations: no nested objects, no multi-line values (| or >),
     * no anchors/aliases, no flow collections ({} or []), no complex keys.
     * For complex YAML, use a proper library like kaml or snakeyaml.
     */
    private fun parseYamlToJsonElement(yaml: String): JsonElement {
        val lines = yaml.lines()
        return parseYamlLines(lines, 0).first
    }

    private fun parseYamlLines(lines: List<String>, startIndent: Int): Pair<JsonElement, Int> {
        val filteredLines = lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (filteredLines.isEmpty()) return JsonNull to 0

        val firstLine = filteredLines.firstOrNull() ?: return JsonNull to 0
        val trimmed = firstLine.trimStart()

        return if (trimmed.startsWith("- ")) {
            // Array
            val array = buildJsonArray {
                for (line in filteredLines) {
                    val t = line.trimStart()
                    if (t.startsWith("- ")) {
                        val value = t.removePrefix("- ").trim()
                        add(parseYamlScalar(value))
                    }
                }
            }
            array to filteredLines.size
        } else if (trimmed.contains(":")) {
            // Object
            val obj = buildJsonObject {
                for (line in filteredLines) {
                    val t = line.trimStart()
                    val colonIdx = t.indexOf(':')
                    if (colonIdx > 0) {
                        val key = t.substring(0, colonIdx).trim()
                        val value = t.substring(colonIdx + 1).trim()
                        if (value.isNotEmpty()) {
                            put(key, parseYamlScalar(value))
                        } else {
                            put(key, JsonNull)
                        }
                    }
                }
            }
            obj to filteredLines.size
        } else {
            parseYamlScalar(trimmed) to 1
        }
    }

    private fun parseYamlScalar(value: String): JsonElement {
        if (value.isEmpty() || value == "null" || value == "~") return JsonNull
        if (value == "true") return JsonPrimitive(true)
        if (value == "false") return JsonPrimitive(false)
        value.toLongOrNull()?.let { return JsonPrimitive(it) }
        value.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        val unquoted = if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))
        ) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
        return JsonPrimitive(unquoted)
    }

    private fun navigateJsonPath(element: JsonElement, path: String): JsonElement {
        if (path.isBlank()) return element

        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in path) {
            when (ch) {
                '.' -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.clear()
                    }
                }

                '[' -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.clear()
                    }
                    current.append('[')
                }

                ']' -> {
                    current.append(']')
                    parts.add(current.toString())
                    current.clear()
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())

        var result = element
        for (part in parts) {
            result = when {
                part.startsWith("[") && part.endsWith("]") -> {
                    val indexStr = part.removeSurrounding("[", "]")
                    if (indexStr == "*") {
                        // Wildcard: return all elements
                        val array = result as? JsonArray ?: throw IllegalArgumentException("Not an array")
                        return buildJsonArray { array.forEach { add(it) } }
                    }
                    val index = indexStr.toInt()
                    (result as? JsonArray)?.get(index)
                        ?: throw IllegalArgumentException("Not an array at index $index")
                }

                else -> {
                    (result as? JsonObject)?.get(part)
                        ?: throw IllegalArgumentException("Key '$part' not found")
                }
            }
        }
        return result
    }

    private fun parseXmlToJson(xml: String, depth: Int = 0): JsonElement {
        if (depth > MAX_XML_DEPTH) {
            throw IllegalArgumentException("XML nesting exceeds maximum depth of $MAX_XML_DEPTH")
        }
        val trimmed = xml.trim()
        // Simple regex-based XML to JSON (handles basic cases)
        val tagPattern = Regex("<(\\w+)([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)

        val matches = tagPattern.findAll(trimmed).toList()
        if (matches.isEmpty()) {
            // Try self-closing or plain text
            return JsonPrimitive(trimmed.replace(HTML_TAGS, "").trim())
        }

        return buildJsonObject {
            val grouped = matches.groupBy { it.groupValues[1] }
            for ((tagName, elements) in grouped) {
                if (elements.size == 1) {
                    val content = elements[0].groupValues[3].trim()
                    val innerMatch = tagPattern.find(content)
                    if (innerMatch != null) {
                        put(tagName, parseXmlToJson(content, depth + 1))
                    } else {
                        put(tagName, JsonPrimitive(content))
                    }
                } else {
                    put(tagName, buildJsonArray {
                        for (el in elements) {
                            val content = el.groupValues[3].trim()
                            val innerMatch = tagPattern.find(content)
                            if (innerMatch != null) {
                                add(parseXmlToJson(content, depth + 1))
                            } else {
                                add(JsonPrimitive(content))
                            }
                        }
                    })
                }
            }
        }
    }
}
