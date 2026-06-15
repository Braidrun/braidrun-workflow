package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

private val webToolsLogger = KotlinLogging.logger {}

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

@LLMDescription("Toolset for web operations including HTTP requests, web scraping, and file downloads")
class WebTools(
    private val httpAccess: HttpAccess,
    private val parameters: List<ConfigurationParameter>
) : ToolSet {

    companion object {
        private const val MAX_RESPONSE_SIZE = 1024 * 1024 // 1MB
        private const val MAX_DOWNLOAD_SIZE = 100 * 1024 * 1024 // 100MB
        private const val DEFAULT_TIMEOUT_MS = 30000L // 30 seconds
        private const val MAX_TEXT_CONTENT_LENGTH = 50000 // Max chars to return
    }

    @Tool
    @LLMDescription("Make HTTP GET request and return response. Supports custom headers. Response body is truncated if too large.")
    suspend fun httpGet(
        @LLMDescription("URL to fetch")
        url: String,

        @LLMDescription("Optional HTTP headers as a JSON object string, e.g. {\"Authorization\": \"Bearer token\"}")
        headersJson: String? = null,

        @LLMDescription("Maximum response size in bytes (default 1MB)")
        maxSize: Int? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val actualMaxSize = maxSize?.coerceAtMost(MAX_RESPONSE_SIZE) ?: MAX_RESPONSE_SIZE

            // Validate URL
            val validUrl = validateAndNormalizeUrl(url)

            // Make request
            val response: String = httpAccess.get(validUrl, headersJson.parseHeadersJson())

            // Truncate if needed
            val truncated = if (response.length > actualMaxSize) {
                response.take(actualMaxSize) + "\n\n[... Response truncated. Total size: ${response.length} bytes]"
            } else {
                response
            }

            buildString {
                appendLine("✅ HTTP GET successful")
                appendLine("URL: $validUrl")
                appendLine("Response size: ${response.length} bytes")
                appendLine()
                appendLine(truncated)
            }
        } catch (e: Exception) {
            webToolsLogger.warn(e) { "HTTP GET failed for URL: $url" }
            "❌ HTTP GET failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Make HTTP POST request with JSON body and return response. Supports custom headers.")
    suspend fun httpPost(
        @LLMDescription("URL to post to")
        url: String,

        @LLMDescription("JSON body as a string, e.g. {\"key\": \"value\"}")
        bodyJson: String,

        @LLMDescription("Optional HTTP headers as a JSON object string, e.g. {\"Authorization\": \"Bearer token\"}")
        headersJson: String? = null,

        @LLMDescription("Maximum response size in bytes (default 1MB)")
        maxSize: Int? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val actualMaxSize = maxSize?.coerceAtMost(MAX_RESPONSE_SIZE) ?: MAX_RESPONSE_SIZE

            // Validate URL
            val validUrl = validateAndNormalizeUrl(url)

            // Add Content-Type if not present
            val headersWithContentType = headersJson.parseHeadersJson().toMutableMap().apply {
                putIfAbsent("Content-Type", "application/json")
            }

            // Make request
            val parsedBody = Json.parseToJsonElement(bodyJson).jsonObject
            val response: JsonObject = httpAccess.post(validUrl, parsedBody, headersWithContentType)
            val responseStr = response.toString()

            // Truncate if needed
            val truncated = if (responseStr.length > actualMaxSize) {
                responseStr.take(actualMaxSize) + "\n\n[... Response truncated. Total size: ${responseStr.length} bytes]"
            } else {
                responseStr
            }

            buildString {
                appendLine("✅ HTTP POST successful")
                appendLine("URL: $validUrl")
                appendLine("Response size: ${responseStr.length} bytes")
                appendLine()
                appendLine(truncated)
            }
        } catch (e: Exception) {
            "❌ HTTP POST failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Make HTTP request with specified method (GET, POST, PUT, DELETE, PATCH) and optional body")
    suspend fun httpRequest(
        @LLMDescription("HTTP method to use")
        method: HttpMethod,

        @LLMDescription("URL to request")
        url: String,

        @LLMDescription("Optional JSON body as a string (required for POST, PUT, PATCH), e.g. {\"key\": \"value\"}")
        bodyJson: String? = null,

        @LLMDescription("Optional HTTP headers as a JSON object string, e.g. {\"Authorization\": \"Bearer token\"}")
        headersJson: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val validUrl = validateAndNormalizeUrl(url)
            val parsedHeaders = headersJson.parseHeadersJson()

            val response: String = when (method) {
                HttpMethod.GET -> {
                    httpAccess.get<String>(validUrl, parsedHeaders)
                }

                HttpMethod.POST -> {
                    requireNotNull(bodyJson) { "Body is required for POST requests" }
                    val parsedBody = Json.parseToJsonElement(bodyJson).jsonObject
                    httpAccess.post<JsonObject, JsonObject>(validUrl, parsedBody, parsedHeaders).toString()
                }

                HttpMethod.PUT -> {
                    requireNotNull(bodyJson) { "Body is required for PUT requests" }
                    val parsedBody = Json.parseToJsonElement(bodyJson).jsonObject
                    httpAccess.request<JsonObject, String>(
                        io.ktor.http.HttpMethod.Put, validUrl, parsedBody, parsedHeaders
                    )
                }

                HttpMethod.PATCH -> {
                    requireNotNull(bodyJson) { "Body is required for PATCH requests" }
                    val parsedBody = Json.parseToJsonElement(bodyJson).jsonObject
                    httpAccess.request<JsonObject, String>(
                        io.ktor.http.HttpMethod.Patch, validUrl, parsedBody, parsedHeaders
                    )
                }

                HttpMethod.DELETE -> {
                    val parsedBody = bodyJson?.let { Json.parseToJsonElement(it).jsonObject }
                    httpAccess.request<JsonObject, String>(
                        io.ktor.http.HttpMethod.Delete, validUrl, parsedBody, parsedHeaders
                    )
                }
            }

            buildString {
                appendLine("✅ HTTP $method successful")
                appendLine("URL: $validUrl")
                appendLine("Response size: ${response.length} bytes")
                appendLine()
                appendLine(response.take(MAX_RESPONSE_SIZE))
                if (response.length > MAX_RESPONSE_SIZE) {
                    appendLine("\n[... Response truncated]")
                }
            }
        } catch (e: Exception) {
            "❌ HTTP $method failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Fetch and parse HTML webpage, extracting title, content, links, images, and metadata. Returns clean text without HTML tags.")
    suspend fun fetchWebpage(
        @LLMDescription("URL of the webpage to fetch")
        url: String,

        @LLMDescription("Whether to include all links found on the page")
        includeLinks: Boolean = false,

        @LLMDescription("Whether to include all image URLs found on the page")
        includeImages: Boolean = false,

        @LLMDescription("Maximum text content length (default 50000 chars)")
        maxContentLength: Int? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val validUrl = validateAndNormalizeUrl(url)
            val maxLength = maxContentLength?.coerceAtMost(MAX_TEXT_CONTENT_LENGTH) ?: MAX_TEXT_CONTENT_LENGTH

            // Fetch and parse HTML
            val doc: Document = safeJsoupGet(
                url = validUrl,
                userAgent = "Mozilla/5.0 (compatible; BraidrunAgent/1.0)",
                timeoutMs = DEFAULT_TIMEOUT_MS.toInt()
            )

            // Extract information
            val title = doc.title()
            val description = doc.select("meta[name=description]").attr("content")
            val keywords = doc.select("meta[name=keywords]").attr("content")
            val language = doc.select("html").attr("lang")

            // Extract clean text content
            val textContent = doc.body().text().take(maxLength)

            // Extract links if requested
            val links = if (includeLinks) {
                doc.select("a[href]").map { it.absUrl("href") }.distinct().take(100)
            } else {
                emptyList()
            }

            // Extract images if requested
            val images = if (includeImages) {
                doc.select("img[src]").map { it.absUrl("src") }.distinct().take(50)
            } else {
                emptyList()
            }

            buildString {
                appendLine("✅ Webpage fetched successfully")
                appendLine()
                appendLine("URL: $validUrl")
                appendLine("Title: $title")
                if (description.isNotBlank()) appendLine("Description: $description")
                if (keywords.isNotBlank()) appendLine("Keywords: $keywords")
                if (language.isNotBlank()) appendLine("Language: $language")
                appendLine()
                appendLine("═══ Content ═══")
                appendLine(textContent)
                if (textContent.length >= maxLength) {
                    appendLine("\n[... Content truncated at $maxLength characters]")
                }

                if (includeLinks && links.isNotEmpty()) {
                    appendLine()
                    appendLine("═══ Links (${links.size}) ═══")
                    links.take(20).forEach { appendLine("• $it") }
                    if (links.size > 20) appendLine("... and ${links.size - 20} more")
                }

                if (includeImages && images.isNotEmpty()) {
                    appendLine()
                    appendLine("═══ Images (${images.size}) ═══")
                    images.take(10).forEach { appendLine("• $it") }
                    if (images.size > 10) appendLine("... and ${images.size - 10} more")
                }
            }
        } catch (e: Exception) {
            "❌ Failed to fetch webpage: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Download file from URL to local path. Supports size limits and progress tracking. File is saved to the specified path.")
    suspend fun downloadFile(
        @LLMDescription("URL of the file to download")
        url: String,

        @LLMDescription("Local file path where the file will be saved")
        outputPath: String,

        @LLMDescription("Maximum file size in bytes (default 100MB)")
        maxSize: Long? = null,

        @LLMDescription("Optional HTTP headers as a JSON object string, e.g. {\"Authorization\": \"Bearer token\"}")
        headersJson: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val validUrl = validateAndNormalizeUrl(url)
            val actualMaxSize = maxSize?.coerceAtMost(MAX_DOWNLOAD_SIZE.toLong()) ?: MAX_DOWNLOAD_SIZE.toLong()

            // Validate output path
            val outputFile = ToolPathSecurity.validateOutputPath(outputPath)

            // Walk the redirect chain manually so we can re-validate every Location
            // hop against the SSRF guard. `URL.openConnection()` / `HttpURLConnection`
            // followed redirects unconditionally before Phase 9 (2026-05) — an
            // attacker controlling the initial URL host could redirect to
            // `http://localhost:8080/...` after the per-call validation.
            val parsedHeaders = headersJson.parseHeadersJson()
            val maxHops = 10
            var currentUrl = validUrl
            var hops = 0
            // Resolve redirects to a final URL first; only then open the body stream.
            while (hops <= maxHops) {
                @Suppress("DEPRECATION")
                val probe = URL(currentUrl).openConnection() as java.net.HttpURLConnection
                probe.instanceFollowRedirects = false
                parsedHeaders.forEach { (key, value) -> probe.setRequestProperty(key, value) }
                val status = try {
                    probe.responseCode
                } catch (e: Exception) {
                    probe.disconnect()
                    throw e
                }
                if (status !in 300..399) {
                    // Final hop. Stream the body and return.
                    try {
                        val contentLength = probe.contentLengthLong
                        if (contentLength in 1L..Long.MAX_VALUE && contentLength > actualMaxSize) {
                            return@withContext "❌ File size ($contentLength bytes) exceeds maximum allowed size ($actualMaxSize bytes)"
                        }
                        probe.inputStream.use { input ->
                            outputFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead = 0L
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    bytesRead += read
                                    if (bytesRead > actualMaxSize) {
                                        outputFile.delete()
                                        return@withContext "❌ Download aborted: file size exceeded $actualMaxSize bytes"
                                    }
                                }
                                return@withContext buildString {
                                    appendLine("✅ File downloaded successfully")
                                    appendLine("URL: $currentUrl")
                                    appendLine("Saved to: ${outputFile.absolutePath}")
                                    appendLine("Size: $bytesRead bytes (${bytesRead / 1024} KB)")
                                }
                            }
                        }
                    } finally {
                        probe.disconnect()
                    }
                }
                // Redirect — re-validate Location and continue.
                val location = probe.getHeaderField("Location")
                probe.disconnect()
                if (location.isNullOrBlank()) {
                    return@withContext "❌ Download aborted: HTTP $status with no Location header"
                }
                val resolved = try {
                    java.net.URI(currentUrl).resolve(location).toString()
                } catch (e: Exception) {
                    return@withContext "❌ Download aborted: cannot resolve Location '$location'"
                }
                currentUrl = try {
                    validateAndNormalizeUrl(resolved)
                } catch (e: Exception) {
                    return@withContext "❌ Download aborted: redirect to '$resolved' rejected: ${e.message}"
                }
                hops++
            }
            "❌ Download aborted: too many redirects (>$maxHops)"
        } catch (e: Exception) {
            "❌ Download failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Extract all links from a webpage that match optional filter criteria")
    suspend fun extractLinks(
        @LLMDescription("URL of the webpage")
        url: String,

        @LLMDescription("Optional filter: only include links containing this text")
        filter: String? = null,

        @LLMDescription("Maximum number of links to return")
        maxLinks: Int = 100
    ): String = withContext(Dispatchers.IO) {
        try {
            val validUrl = validateAndNormalizeUrl(url)

            val doc: Document = safeJsoupGet(
                url = validUrl,
                userAgent = "Mozilla/5.0 (compatible; BraidrunAgent/1.0)",
                timeoutMs = DEFAULT_TIMEOUT_MS.toInt()
            )

            val allLinks = doc.select("a[href]").map { it.absUrl("href") }.distinct()

            val filteredLinks = if (filter != null) {
                allLinks.filter { it.contains(filter, ignoreCase = true) }
            } else {
                allLinks
            }.take(maxLinks)

            buildString {
                appendLine("✅ Extracted ${filteredLinks.size} links from webpage")
                appendLine("URL: $validUrl")
                if (filter != null) appendLine("Filter: $filter")
                appendLine()
                filteredLinks.forEach { appendLine("• $it") }
                if (allLinks.size > maxLinks) {
                    appendLine("\n[... ${allLinks.size - maxLinks} more links not shown]")
                }
            }
        } catch (e: Exception) {
            "❌ Failed to extract links: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Search the web using DuckDuckGo and return the top results (title + link)")
    suspend fun searchWeb(
        @LLMDescription("Search keywords, e.g., Kotlin coroutines tutorial")
        searchTerm: String,

        @LLMDescription("Maximum number of results to return (default 5)")
        maxResults: Int = 5
    ): String = withContext(Dispatchers.IO) {
        fun decodeUddgLink(href: String): String {
            return try {
                val uri = java.net.URI(href)
                val query = uri.rawQuery ?: return href
                val params = query.split('&').mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
                }.toMap()
                val uddg = params["uddg"] ?: return href
                java.net.URLDecoder.decode(uddg, java.nio.charset.StandardCharsets.UTF_8)
            } catch (e: Throwable) {
                webToolsLogger.debug(e) { "Browser page close failed" }
                href
            }
        }

        fun stripTags(html: String): String = html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

        try {
            val q = URLEncoder.encode(searchTerm, "UTF-8")
            val url = "https://duckduckgo.com/html/?q=$q&kl=cn-zh&k1=-1"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
            )
            val html = httpAccess.get<String>(url, headers)
            val regex = Regex(
                """<a[^>]+class=\"[^\"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            val results = regex.findAll(html).take(maxResults).mapIndexed { idx, m ->
                val rawHref = m.groupValues[1]
                val titleHtml = m.groupValues[2]
                val link = if (rawHref.contains("duckduckgo.com/l/")) decodeUddgLink(rawHref) else rawHref
                val title = stripTags(titleHtml).trim()
                "${idx + 1}. $title\n   $link"
            }.toList()

            buildString {
                appendLine("✅ Web search completed")
                appendLine("Query: \"$searchTerm\"")
                appendLine("Results found: ${results.size}")
                appendLine()
                if (results.isEmpty()) {
                    appendLine("No structured results found. Try a different search term.")
                } else {
                    appendLine(results.joinToString("\n\n"))
                }
            }
        } catch (e: Exception) {
            "❌ Search failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Search for specific text or patterns within a webpage's content")
    suspend fun searchInWebpage(
        @LLMDescription("URL of the webpage")
        url: String,

        @LLMDescription("Text or pattern to search for")
        searchText: String,

        @LLMDescription("Whether to use case-sensitive search")
        caseSensitive: Boolean = false,

        @LLMDescription("Number of context characters to show around each match")
        contextChars: Int = 100
    ): String = withContext(Dispatchers.IO) {
        try {
            val validUrl = validateAndNormalizeUrl(url)

            val doc: Document = safeJsoupGet(
                url = validUrl,
                userAgent = "Mozilla/5.0 (compatible; BraidrunAgent/1.0)",
                timeoutMs = DEFAULT_TIMEOUT_MS.toInt()
            )

            val text = doc.body().text()

            val matches = mutableListOf<Pair<Int, String>>()
            var index = 0

            while (true) {
                // Search the ORIGINAL string with ignoreCase instead of indexing into a
                // lowercased copy: lowercase() can change string length for some Unicode
                // code points (e.g. 'İ'), misaligning the context slices below.
                index = text.indexOf(searchText, index, ignoreCase = !caseSensitive)
                if (index == -1) break

                val start = maxOf(0, index - contextChars)
                val end = minOf(text.length, index + searchText.length + contextChars)
                val context = text.substring(start, end)

                matches.add(index to context)
                index += searchText.length
            }

            buildString {
                appendLine("✅ Search completed")
                appendLine("URL: $validUrl")
                appendLine("Search text: \"$searchText\"")
                appendLine("Matches found: ${matches.size}")
                appendLine()

                matches.take(10).forEachIndexed { i, (pos, context) ->
                    appendLine("Match ${i + 1} at position $pos:")
                    appendLine("... $context ...")
                    appendLine()
                }

                if (matches.size > 10) {
                    appendLine("[... ${matches.size - 10} more matches not shown]")
                }
            }
        } catch (e: Exception) {
            "❌ Search failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Search for news articles using DuckDuckGo news search. " +
                "Returns recent news results with titles, links, sources, and dates."
    )
    suspend fun searchNews(
        @LLMDescription("News search keywords")
        searchTerm: String,

        @LLMDescription("Maximum number of results to return (default 5)")
        maxResults: Int = 5
    ): String = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(searchTerm, "UTF-8")
            val url = "https://duckduckgo.com/html/?q=$q&iar=news&kl=cn-zh&k1=-1"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
            )
            val html = httpAccess.get<String>(url, headers)

            val doc = Jsoup.parse(html)
            val resultElements = doc.select(".result")

            val results = resultElements.take(maxResults).mapIndexed { idx, el ->
                val title = el.select(".result__a").text().trim()
                val link = el.select(".result__a").attr("href").let { href ->
                    if (href.contains("duckduckgo.com/l/")) {
                        try {
                            val uri = java.net.URI(href)
                            val params = (uri.rawQuery ?: "").split('&').mapNotNull {
                                val eq = it.indexOf('=')
                                if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
                            }.toMap()
                            java.net.URLDecoder.decode(params["uddg"] ?: href, java.nio.charset.StandardCharsets.UTF_8)
                        } catch (e: Throwable) {
                            webToolsLogger.debug(e) { "Failed to extract page content" }
                            href
                        }
                    } else href
                }
                val snippet = el.select(".result__snippet").text().trim()
                "${idx + 1}. $title\n   $link\n   $snippet"
            }

            buildString {
                appendLine("✅ News search completed")
                appendLine("Query: \"$searchTerm\"")
                appendLine("Results found: ${results.size}")
                appendLine()
                if (results.isEmpty()) {
                    appendLine("No news results found. Try a different search term.")
                } else {
                    appendLine(results.joinToString("\n\n"))
                }
            }
        } catch (e: Exception) {
            "❌ News search failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Search for images on the web using DuckDuckGo. " +
                "Returns image URLs along with titles and source page links."
    )
    suspend fun searchImages(
        @LLMDescription("Image search keywords")
        searchTerm: String,

        @LLMDescription("Maximum number of results to return (default 5)")
        maxResults: Int = 5
    ): String = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(searchTerm, "UTF-8")
            val url = "https://duckduckgo.com/html/?q=$q&iax=images&ia=images&kl=cn-zh&k1=-1"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
            )
            val html = httpAccess.get<String>(url, headers)

            val doc = Jsoup.parse(html)
            val imgElements = doc.select("img[src]")

            val results = imgElements
                .filter { it.attr("src").startsWith("http") }
                .take(maxResults)
                .mapIndexed { idx, el ->
                    val imgUrl = el.attr("src")
                    val alt = el.attr("alt").ifBlank { "(no description)" }
                    "${idx + 1}. $alt\n   Image: $imgUrl"
                }

            buildString {
                appendLine("✅ Image search completed")
                appendLine("Query: \"$searchTerm\"")
                appendLine("Results found: ${results.size}")
                appendLine()
                if (results.isEmpty()) {
                    appendLine("No image results found. Try a different search term or use searchWeb to find image URLs.")
                } else {
                    appendLine(results.joinToString("\n\n"))
                }
            }
        } catch (e: Exception) {
            "❌ Image search failed: ${e.message}"
        }
    }

    /**
     * Validates and normalizes URL to prevent common issues.
     *
     * Enforces:
     *  1. A public HTTP/HTTPS scheme — rejects `file:`, `jar:`, `gopher:`, and other exotic schemes.
     *  2. A resolvable host that is NOT loopback / link-local / private / multicast / wildcard —
     *     this blocks SSRF against localhost services, cloud metadata endpoints (169.254.169.254),
     *     and internal-network resources. Opt-out via `WEB_TOOLS_ALLOW_PRIVATE_URLS=true` for
     *     dev/test workflows that must hit internal services.
     */
    private fun validateAndNormalizeUrl(url: String): String = UrlSafety.validateAndNormalizeUrl(url)

    /**
     * SSRF-safe Jsoup `.get()` replacement.
     *
     * Pre-Phase-9 (2026-05) every `Jsoup.connect(validUrl).get()` call followed
     * 3xx redirects automatically without re-validating the redirect target.
     * An attacker who controlled `attacker.com` (a public IP that passed the
     * initial `validateAndNormalizeUrl`) could 302-redirect to
     * `http://localhost:8080/admin` or `http://169.254.169.254/...` and
     * exfiltrate the internal response.
     *
     * This helper disables Jsoup's auto-follow and walks the redirect chain
     * manually, calling [UrlSafety.validateAndNormalizeUrl] on every Location
     * target. Hop ceiling matches OkHttp's default (10) and short-circuits
     * loops. The final response is parsed to a [Document] for the caller.
     */
    private fun safeJsoupGet(url: String, userAgent: String, timeoutMs: Int): Document {
        var current = url
        var hops = 0
        val maxHops = 10
        while (true) {
            val resp: org.jsoup.Connection.Response = Jsoup.connect(current)
                .userAgent(userAgent)
                .timeout(timeoutMs)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .execute()
            val status = resp.statusCode()
            if (status in 300..399) {
                if (hops >= maxHops) {
                    throw java.io.IOException("Too many redirects (>$maxHops) starting from $url")
                }
                val location = resp.header("Location")
                    ?: throw java.io.IOException("Got HTTP $status from $current with no Location header")
                val resolved = try {
                    java.net.URI(current).resolve(location).toString()
                } catch (e: Exception) {
                    throw java.io.IOException(
                        "Could not resolve redirect Location '$location' against '$current': ${e.message}",
                        e
                    )
                }
                // Re-validate against the SSRF guard before following.
                current = validateAndNormalizeUrl(resolved)
                hops++
                continue
            }
            return resp.parse()
        }
    }

    // Previously this class had its own copies of `enforcePublicHost`,
    // `disallowedAddressReason`, and `allowPrivateHosts`. They have moved to
    // [UrlSafety] so every tool (WebTools, MultimediaGenerationTools, BrowserTools,
    // future ones) goes through the same rules — no more "WebTools blocks
    // 169.254.169.254 but MultimediaGen fetches it" drift.

    private fun String?.parseHeadersJson(): Map<String, String> {
        if (this == null) return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(this)
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
