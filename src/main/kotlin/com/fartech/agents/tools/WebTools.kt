package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.redactForLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@Serializable
@LLMDescription("HTTP method types for web requests")
enum class HttpMethod {
    @LLMDescription("HTTP GET request")
    GET,

    @LLMDescription("HTTP POST request")
    POST,

    @LLMDescription("HTTP PUT request")
    PUT,

    @LLMDescription("HTTP DELETE request")
    DELETE,

    @LLMDescription("HTTP PATCH request")
    PATCH
}

@Serializable
@LLMDescription("Response from HTTP request")
data class HttpResponse(
    @property:LLMDescription("HTTP status code (e.g., 200, 404, 500)")
    val statusCode: Int,

    @property:LLMDescription("Response body as string")
    val body: String,

    @property:LLMDescription("Response headers")
    val headers: Map<String, String>,

    @property:LLMDescription("Whether the request was successful (status 2xx)")
    val isSuccess: Boolean
)

@Serializable
@LLMDescription("Parsed webpage information")
data class WebpageInfo(
    @property:LLMDescription("Page title")
    val title: String,

    @property:LLMDescription("Main text content (cleaned)")
    val textContent: String,

    @property:LLMDescription("Meta description")
    val description: String?,

    @property:LLMDescription("Meta keywords")
    val keywords: String?,

    @property:LLMDescription("All links found on the page")
    val links: List<String>,

    @property:LLMDescription("All image URLs found on the page")
    val images: List<String>,

    @property:LLMDescription("Page language")
    val language: String?
)

/**
 * Runner must inject its gated HttpAccess, confined ToolFileAccess and execution-local redactor.
 * The two-argument library/server constructor retains the local file policy and log redactor.
 * Jsoup is a parser only: every fetch, search and download uses the same bounded transport.
 */
@LLMDescription("Toolset for web operations including HTTP requests, web scraping, and file downloads")
class WebTools(
    httpAccess: HttpAccess,
    private val parameters: List<ConfigurationParameter>,
    private val fileAccess: ToolFileAccess = LocalToolFileAccess,
    private val redactOutput: (String) -> String = ::redactForLog,
) : ToolSet {
    private val transport = WebToolTransport(httpAccess)

    @Tool
    @LLMDescription("Make HTTP GET request and return response. Supports custom headers. Response body is truncated if too large.")
    suspend fun httpGet(
        @LLMDescription("URL to fetch") url: String,
        @LLMDescription("Optional HTTP headers as a JSON object string") headersJson: String? = null,
        @LLMDescription("Maximum response size in bytes (default 1MB)") maxSize: Int? = null,
    ): String = toolResult("HTTP GET", url, headersJson) { headers ->
        formatResponse("GET", transport.request(HttpMethod.GET, url, headers, maxBytes = responseLimit(maxSize), truncate = true))
    }

    @Tool
    @LLMDescription("Make HTTP POST request with JSON body and return response. Supports custom headers.")
    suspend fun httpPost(
        @LLMDescription("URL to post to") url: String,
        @LLMDescription("JSON body as a string") bodyJson: String,
        @LLMDescription("Optional HTTP headers as a JSON object string") headersJson: String? = null,
        @LLMDescription("Maximum response size in bytes (default 1MB)") maxSize: Int? = null,
    ): String = toolResult("HTTP POST", url, headersJson, bodyJson) { headers ->
        formatResponse("POST", transport.request(HttpMethod.POST, url, jsonHeaders(headers), jsonBody(bodyJson), responseLimit(maxSize), truncate = true))
    }

    @Tool
    @LLMDescription("Make HTTP request with specified method (GET, POST, PUT, DELETE, PATCH) and optional body")
    suspend fun httpRequest(
        @LLMDescription("HTTP method to use") method: HttpMethod,
        @LLMDescription("URL to request") url: String,
        @LLMDescription("Optional JSON body as a string (required for POST, PUT, PATCH)") bodyJson: String? = null,
        @LLMDescription("Optional HTTP headers as a JSON object string") headersJson: String? = null,
    ): String = toolResult("HTTP $method", url, headersJson, bodyJson) { headers ->
        if (method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) requireNotNull(bodyJson)
        val body = if (method == HttpMethod.GET) null else bodyJson?.let(::jsonBody)
        formatResponse(method.name, transport.request(method, url, if (body != null) jsonHeaders(headers) else headers, body, truncate = true))
    }

    @Tool
    @LLMDescription("Fetch and parse HTML webpage, extracting title, content, links, images, and metadata. Returns clean text without HTML tags.")
    suspend fun fetchWebpage(
        @LLMDescription("URL of the webpage to fetch") url: String,
        @LLMDescription("Whether to include all links found on the page") includeLinks: Boolean = false,
        @LLMDescription("Whether to include all image URLs found on the page") includeImages: Boolean = false,
        @LLMDescription("Maximum text content length (default 50000 chars)") maxContentLength: Int? = null,
    ): String = toolResult("Fetch webpage", url) {
        val maxLength = (maxContentLength ?: MAX_TEXT_CONTENT_LENGTH).also { require(it > 0) }.coerceAtMost(MAX_TEXT_CONTENT_LENGTH)
        val doc = fetchDocument(url)
        val text = doc.body().text()
        buildString {
            appendLine("✅ Webpage fetched successfully")
            appendLine("URL: ${doc.location()}")
            appendLine("Title: ${doc.title()}")
            for ((label, value) in listOf("Description" to doc.select("meta[name=description]").attr("content"), "Keywords" to doc.select("meta[name=keywords]").attr("content"), "Language" to doc.select("html").attr("lang"))) {
                if (value.isNotBlank()) appendLine("$label: $value")
            }
            appendLine("\n═══ Content ═══")
            appendLine(text.take(maxLength))
            if (text.length > maxLength) appendLine("\n[... Content truncated at $maxLength characters]")
            if (includeLinks) appendUrls("Links", doc.select("a[href]").map { it.absUrl("href") }.distinct().take(100), 20)
            if (includeImages) appendUrls("Images", doc.select("img[src]").map { it.absUrl("src") }.distinct().take(50), 10)
        }
    }

    @Tool
    @LLMDescription("Download a file through the supplied network and file services. An oversized or failed response is not written.")
    suspend fun downloadFile(
        @LLMDescription("URL of the file to download") url: String,
        @LLMDescription("Local file path where the file will be saved") outputPath: String,
        @LLMDescription("Maximum file size in bytes (default 100MB)") maxSize: Long? = null,
        @LLMDescription("Optional HTTP headers as a JSON object string") headersJson: String? = null,
    ): String = toolResult("Download", url, headersJson) { headers ->
        val limit = (maxSize ?: WebToolTransport.MAX_DOWNLOAD_BYTES.toLong()).also { require(it > 0) }
            .coerceAtMost(WebToolTransport.MAX_DOWNLOAD_BYTES.toLong()).toInt()
        val response = transport.request(HttpMethod.GET, url, headers, maxBytes = limit, timeoutMillis = WebToolTransport.DOWNLOAD_TIMEOUT_MILLIS)
        currentCoroutineContext().ensureActive()
        // The adapter owns validation, atomic publication, revocation and the shared write quota.
        fileAccess.writeBytes(outputPath, response.bytes)
        buildString {
            appendLine("✅ File downloaded successfully")
            appendLine("URL: ${response.url}")
            appendLine("Saved to: $outputPath")
            appendLine("Size: ${response.bytes.size} bytes (${response.bytes.size / 1024} KB)")
        }
    }

    @Tool
    @LLMDescription("Extract all links from a webpage that match optional filter criteria")
    suspend fun extractLinks(
        @LLMDescription("URL of the webpage") url: String,
        @LLMDescription("Optional filter: only include links containing this text") filter: String? = null,
        @LLMDescription("Maximum number of links to return") maxLinks: Int = 100,
    ): String = toolResult("Extract links", url) {
        require(maxLinks > 0)
        val doc = fetchDocument(url)
        val all = doc.select("a[href]").map { it.absUrl("href") }.distinct()
            .filter { filter == null || it.contains(filter, ignoreCase = true) }
        val links = all.take(maxLinks.coerceAtMost(100))
        buildString {
            appendLine("✅ Extracted ${links.size} links from webpage")
            appendLine("URL: ${doc.location()}")
            if (filter != null) appendLine("Filter: $filter")
            appendLine()
            links.forEach { appendLine("• $it") }
            if (all.size > links.size) appendLine("\n[... ${all.size - links.size} more links not shown]")
        }
    }

    @Tool
    @LLMDescription("Search the web using DuckDuckGo and return the top results (title + link)")
    suspend fun searchWeb(
        @LLMDescription("Search keywords") searchTerm: String,
        @LLMDescription("Maximum number of results to return (default 5)") maxResults: Int = 5,
    ): String = toolResult("Search") {
        val limit = resultLimit(maxResults)
        val doc = searchDocument(searchTerm)
        val results = doc.select("a.result__a").take(limit).mapIndexed { index, element ->
            "${index + 1}. ${element.text().trim()}\n   ${decodeSearchLink(element.absUrl("href"))}"
        }
        formatSearch("Web", searchTerm, results)
    }

    @Tool
    @LLMDescription("Search for specific text within a webpage's content")
    suspend fun searchInWebpage(
        @LLMDescription("URL of the webpage") url: String,
        @LLMDescription("Text to search for") searchText: String,
        @LLMDescription("Whether to use case-sensitive search") caseSensitive: Boolean = false,
        @LLMDescription("Number of context characters to show around each match") contextChars: Int = 100,
    ): String = toolResult("Search webpage", url) {
        require(searchText.isNotEmpty() && searchText.length <= MAX_TEXT_CONTENT_LENGTH && contextChars >= 0)
        val contextLength = contextChars.coerceAtMost(2000)
        val doc = fetchDocument(url)
        val text = doc.body().text()
        val matches = mutableListOf<Pair<Int, String>>()
        var position = 0
        var count = 0
        while (position < text.length) {
            currentCoroutineContext().ensureActive()
            position = text.indexOf(searchText, position, ignoreCase = !caseSensitive)
            if (position < 0) break
            count++
            if (matches.size < 10) matches += position to text.substring(maxOf(0, position - contextLength), minOf(text.length, position + searchText.length + contextLength))
            position += searchText.length
        }
        buildString {
            appendLine("✅ Search completed")
            appendLine("URL: ${doc.location()}")
            appendLine("Search text: \"$searchText\"")
            appendLine("Matches found: $count")
            matches.forEachIndexed { index, (offset, context) -> appendLine("\nMatch ${index + 1} at position $offset:\n... $context ...") }
            if (count > matches.size) appendLine("\n[... ${count - matches.size} more matches not shown]")
        }
    }

    @Tool
    @LLMDescription("Search for news articles using DuckDuckGo news search. Returns recent news results with titles, links and snippets.")
    suspend fun searchNews(
        @LLMDescription("News search keywords") searchTerm: String,
        @LLMDescription("Maximum number of results to return (default 5)") maxResults: Int = 5,
    ): String = toolResult("News search") {
        val limit = resultLimit(maxResults)
        val doc = searchDocument(searchTerm, "&iar=news")
        val results = doc.select(".result").take(limit).mapIndexed { index, element ->
            val link = element.select(".result__a")
            "${index + 1}. ${link.text().trim()}\n   ${decodeSearchLink(link.first()?.absUrl("href").orEmpty())}\n   ${element.select(".result__snippet").text().trim()}"
        }
        formatSearch("News", searchTerm, results)
    }

    @Tool
    @LLMDescription("Search for images on the web using DuckDuckGo. Returns image URLs and descriptions.")
    suspend fun searchImages(
        @LLMDescription("Image search keywords") searchTerm: String,
        @LLMDescription("Maximum number of results to return (default 5)") maxResults: Int = 5,
    ): String = toolResult("Image search") {
        val limit = resultLimit(maxResults)
        val doc = searchDocument(searchTerm, "&iax=images&ia=images")
        val results = doc.select("img[src]").filter { it.absUrl("src").startsWith("http") }
            .take(limit).mapIndexed { index, element ->
                "${index + 1}. ${element.attr("alt").ifBlank { "(no description)" }}\n   Image: ${element.absUrl("src")}"
            }
        formatSearch("Image", searchTerm, results)
    }

    private suspend fun fetchDocument(url: String): Document {
        val response = transport.request(HttpMethod.GET, url, SEARCH_HEADERS)
        currentCoroutineContext().ensureActive()
        return Jsoup.parse(response.bytes.inputStream(), response.charsetName, response.url)
    }

    private suspend fun searchDocument(term: String, suffix: String = ""): Document {
        require(term.isNotBlank() && term.length <= 2000)
        return fetchDocument("https://duckduckgo.com/html/?q=${URLEncoder.encode(term, Charsets.UTF_8)}$suffix&kl=cn-zh&k1=-1")
    }

    private fun decodeSearchLink(href: String): String = try {
        val uri = URI(href)
        if (uri.host?.endsWith("duckduckgo.com") != true || uri.path != "/l/") href else {
            val encoded = uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "uddg" }?.substringAfter('=')
            encoded?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: href
        }
    } catch (_: IllegalArgumentException) { href } catch (_: java.net.URISyntaxException) { href }

    private fun formatSearch(kind: String, term: String, results: List<String>): String = buildString {
        appendLine("✅ $kind search completed")
        appendLine("Query: \"$term\"")
        appendLine("Results found: ${results.size}\n")
        appendLine(if (results.isEmpty()) "No results found. Try a different search term." else results.joinToString("\n\n"))
    }

    private fun formatResponse(method: String, response: WebToolResponse): String = buildString {
        appendLine("✅ HTTP $method successful")
        appendLine("URL: ${response.url}")
        appendLine("Response size: ${response.bytes.size} bytes${if (response.truncated) " (bounded prefix)" else ""}\n")
        appendLine(response.text())
        if (response.truncated) appendLine("\n[... Response truncated at ${response.bytes.size} bytes]")
    }

    private fun StringBuilder.appendUrls(label: String, urls: List<String>, displayLimit: Int) {
        if (urls.isEmpty()) return
        appendLine("\n═══ $label (${urls.size}) ═══")
        urls.take(displayLimit).forEach { appendLine("• $it") }
        if (urls.size > displayLimit) appendLine("... and ${urls.size - displayLimit} more")
    }

    private fun responseLimit(value: Int?): Int = (value ?: WebToolTransport.MAX_RESPONSE_BYTES)
        .also { require(it > 0) }.coerceAtMost(WebToolTransport.MAX_RESPONSE_BYTES)

    private fun resultLimit(value: Int): Int = value.also { require(it > 0) }.coerceAtMost(20)

    private fun jsonBody(body: String): String {
        require(body.length <= WebToolTransport.MAX_RESPONSE_BYTES)
        return Json.parseToJsonElement(body).jsonObject.toString()
    }

    private fun jsonHeaders(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) headers else headers + ("Content-Type" to "application/json")

    private suspend fun toolResult(
        action: String,
        url: String? = null,
        headersJson: String? = null,
        bodyJson: String? = null,
        block: suspend (Map<String, String>) -> String,
    ): String = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            require(headersJson == null || headersJson.length <= 32_768)
            val headers = headersJson?.let { Json.decodeFromString<Map<String, String>>(it) }.orEmpty()
            val output = block(headers)
            currentCoroutineContext().ensureActive()
            WebToolRedaction.scrub(redactOutput(output), url, headers, bodyJson)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Engines can wrap a cancellation in an IOException. Do not turn authority loss into
            // ordinary tool text and allow the agent to issue another external request.
            currentCoroutineContext().ensureActive()
            var cause: Throwable? = failure
            repeat(16) {
                if (cause is CancellationException) throw cause as CancellationException
                cause = cause?.cause
            }
            val reason = when (failure) {
                is WebToolSizeFailure -> "response exceeds the allowed byte limit"
                is WebToolHttpFailure -> "HTTP ${failure.status}"
                is SecurityException -> "request or file access denied"
                is IllegalArgumentException -> "invalid request arguments"
                else -> "request failed or timed out"
            }
            // Neither exception messages/stacks nor response headers/bodies are failure diagnostics.
            "❌ $action failed: $reason"
        }
    }

    companion object {
        private const val MAX_TEXT_CONTENT_LENGTH = 50_000
        private val SEARCH_HEADERS = mapOf("User-Agent" to "Mozilla/5.0 (compatible; BraidrunAgent/1.0)", "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8")
    }
}

/** Complements the host's registered-secret redactor with request echoes and common response fields. */
internal object WebToolRedaction {
    private val secretName = Regex("(?i)(?:authorization|proxy-authorization|cookie|set-cookie|(?:access|refresh|id)[_-]?token|token|password|passwd|secret|client[_-]?secret|api[_-]?key|private[_-]?key|signature|sign|sig|key)")
    private val secretField = Regex("""(?i)(["']?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passwd|secret|client[_-]?secret|api[_-]?key|private[_-]?key|authorization|cookie|set-cookie)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"?|'[^']*'?|[^\s,;}\]]+)""")
    private val secretHeader = Regex("(?im)^((?:authorization|proxy-authorization|cookie|set-cookie):)[^\\r\\n]*")

    fun scrub(text: String, url: String? = null, headers: Map<String, String> = emptyMap(), body: String? = null): String {
        val values = mutableSetOf<String>()
        fun add(value: String) {
            if (value.length < 4) return
            values += value
            values += URLEncoder.encode(value, Charsets.UTF_8)
            values += JsonPrimitive(value).toString().removeSurrounding("\"")
        }
        headers.filterKeys { secretName.containsMatchIn(it) }.forEach { (key, value) ->
            add(value)
            if (key.contains("authorization", ignoreCase = true)) add(value.substringAfter(' ', value))
            if (key.contains("cookie", ignoreCase = true)) value.split(';').forEach { add(it.substringAfter('=', "").trim()) }
        }
        runCatching { url?.let(::URI)?.rawQuery }.getOrNull()?.split('&')?.forEach { field ->
            val name = runCatching { URLDecoder.decode(field.substringBefore('='), Charsets.UTF_8) }.getOrDefault("")
            if (secretName.matches(name)) runCatching { URLDecoder.decode(field.substringAfter('=', ""), Charsets.UTF_8) }.getOrNull()?.let(::add)
        }
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> if (secretName.matches(key) && value is JsonPrimitive) add(value.content) else collect(value) }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        if (body != null && body.length <= WebToolTransport.MAX_RESPONSE_BYTES) runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let(::collect)
        var output = text
        values.sortedByDescending { it.length }.forEach { output = output.replace(it, "<redacted>") }
        output = secretHeader.replace(output) { "${it.groupValues[1]} <redacted>" }
        output = secretField.replace(output) { "${it.groupValues[1]}\"<redacted>\"" }
        return redactForLog(output)
    }
}
