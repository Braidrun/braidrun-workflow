package com.fartech.agents.commons

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Model of Anthropic's prompt cache, per the documented semantics, for measuring breakpoint
 * placement without a live API key:
 *
 * - The request is linearized in render order — tools, then system blocks, then message
 *   content blocks — and an entry is keyed by the exact bytes of the prefix up to a block
 *   (`cache_control` markers excluded) plus the model.
 * - Each breakpoint looks back at most [lookback] blocks (itself included) for an existing
 *   entry; the furthest hit over all breakpoints is the cache read.
 * - Entries are written at breakpoints past the read point whose prefix reaches
 *   [minCacheableTokens]; the write is billed once, from the read point to the last breakpoint.
 * - `input_tokens` is what remains: the uncached tail after the last breakpoint.
 *
 * Tokens are estimated as characters / 4. Entries never expire (every test request lands
 * within the 5-minute TTL).
 */
internal class SimulatedAnthropicPromptCache(
    private val minCacheableTokens: Int = 1024,
    private val lookback: Int = 20,
) {
    data class Usage(val inputTokens: Int, val cacheReadInputTokens: Int, val cacheCreationInputTokens: Int) {
        val promptTokens: Int get() = inputTokens + cacheReadInputTokens + cacheCreationInputTokens
    }

    private data class Block(val canonical: String, val marked: Boolean)

    private val entries = HashSet<String>()

    @Synchronized
    fun process(request: JsonObject): Usage {
        val model = request["model"]?.jsonPrimitive?.content.orEmpty()
        val blocks = linearize(request)
        val prefixTokens = IntArray(blocks.size)
        val prefixKeys = Array(blocks.size) { "" }
        var tokens = 0
        var key = sha256("model:$model")
        blocks.forEachIndexed { i, block ->
            tokens += (block.canonical.length + 3) / 4
            key = sha256(key + "\u0000" + block.canonical)
            prefixTokens[i] = tokens
            prefixKeys[i] = key
        }
        val total = prefixTokens.lastOrNull() ?: 0
        val breakpoints = blocks.indices.filter { blocks[it].marked }
            .let { if (request["cache_control"] != null && blocks.isNotEmpty()) it + blocks.lastIndex else it }
            .distinct()
        if (breakpoints.isEmpty()) return Usage(total, 0, 0)

        var readAt = -1
        for (b in breakpoints) {
            val hit = (b downTo maxOf(0, b - lookback + 1)).firstOrNull { prefixKeys[it] in entries }
            if (hit != null && hit > readAt) readAt = hit
        }
        val read = if (readAt >= 0) prefixTokens[readAt] else 0
        val last = breakpoints.max()
        var creation = 0
        if (last > readAt && prefixTokens[last] >= minCacheableTokens) {
            breakpoints.filter { it > readAt && prefixTokens[it] >= minCacheableTokens }
                .forEach { entries += prefixKeys[it] }
            creation = prefixTokens[last] - read
        }
        return Usage(total - read - creation, read, creation)
    }

    private fun linearize(request: JsonObject): List<Block> = buildList {
        request["tools"]?.jsonArray?.forEach { add(block("tool", it)) }
        when (val system = request["system"]) {
            is JsonPrimitive -> add(Block("system:" + system.content, marked = false))
            is JsonArray -> system.forEach { add(block("system", it)) }
            else -> Unit
        }
        request["messages"]?.jsonArray?.forEach { message ->
            val role = message.jsonObject["role"]!!.jsonPrimitive.content
            when (val content = message.jsonObject["content"]) {
                is JsonPrimitive -> add(Block("$role:" + content.content, marked = false))
                is JsonArray -> content.forEach { add(block(role, it)) }
                else -> Unit
            }
        }
    }

    private fun block(role: String, element: JsonElement) =
        Block("$role:" + canonical(stripCacheControl(element)), marked = hasCacheControl(element))

    private fun hasCacheControl(element: JsonElement): Boolean = when (element) {
        is JsonObject -> "cache_control" in element || element.values.any(::hasCacheControl)
        is JsonArray -> element.any(::hasCacheControl)
        else -> false
    }

    private fun stripCacheControl(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { it != "cache_control" }.mapValues { stripCacheControl(it.value) })
        is JsonArray -> JsonArray(element.map(::stripCacheControl))
        else -> element
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(element[it]!!) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> element.toString()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Loopback stand-in for `POST /v1/messages`, JSON and SSE, whose `usage` comes from a
 * [SimulatedAnthropicPromptCache]. [script] picks the assistant content blocks for the n-th
 * request; a reply containing a `tool_use` block stops with `tool_use`.
 */
internal class FakeAnthropicApi(
    val cache: SimulatedAnthropicPromptCache = SimulatedAnthropicPromptCache(),
    private val script: (requestIndex: Int, request: JsonObject) -> List<JsonObject>,
) : AutoCloseable {

    data class Exchange(val request: JsonObject, val usage: SimulatedAnthropicPromptCache.Usage, val streamed: Boolean)

    val exchanges: MutableList<Exchange> = Collections.synchronizedList(mutableListOf())

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/v1/messages") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    private fun handle(exchange: HttpExchange) {
        val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
        val index: Int
        val usage: SimulatedAnthropicPromptCache.Usage
        synchronized(this) {
            index = exchanges.size
            usage = cache.process(request)
            exchanges += Exchange(request, usage, request["stream"]?.jsonPrimitive?.booleanOrNull == true)
        }
        val content = script(index, request)
        val stopReason = if (content.any { it["type"]?.jsonPrimitive?.content == "tool_use" }) "tool_use" else "end_turn"
        val outputTokens = maxOf(1, content.sumOf { it.toString().length } / 4)
        if (exchanges[index].streamed) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { out ->
                sseEvents(index, request, content, stopReason, usage, outputTokens).forEach { (event, data) ->
                    out.write("event: $event\ndata: $data\n\n")
                }
            }
        } else {
            val body = buildJsonObject {
                put("id", "msg_$index")
                put("type", "message")
                put("role", "assistant")
                put("model", request["model"]!!)
                put("content", JsonArray(content))
                put("stop_reason", stopReason)
                put("usage", usageJson(usage, outputTokens))
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun sseEvents(
        index: Int,
        request: JsonObject,
        content: List<JsonObject>,
        stopReason: String,
        usage: SimulatedAnthropicPromptCache.Usage,
        outputTokens: Int,
    ): List<Pair<String, String>> = buildList {
        add("message_start" to buildJsonObject {
            put("type", "message_start")
            put("message", buildJsonObject {
                put("id", "msg_$index")
                put("type", "message")
                put("role", "assistant")
                put("model", request["model"]!!)
                put("content", JsonArray(emptyList()))
                put("usage", usageJson(usage, 1))
            })
        }.toString())
        content.forEachIndexed { i, block ->
            when (block["type"]!!.jsonPrimitive.content) {
                "text" -> {
                    add("content_block_start" to """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""")
                    val delta = buildJsonObject { put("type", "text_delta"); put("text", block["text"]!!) }
                    add("content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":$delta}""")
                }
                "tool_use" -> {
                    val start = buildJsonObject {
                        put("type", "tool_use"); put("id", block["id"]!!); put("name", block["name"]!!); put("input", JsonObject(emptyMap()))
                    }
                    add("content_block_start" to """{"type":"content_block_start","index":$i,"content_block":$start}""")
                    val delta = buildJsonObject { put("type", "input_json_delta"); put("partial_json", block["input"].toString()) }
                    add("content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":$delta}""")
                }
            }
            add("content_block_stop" to """{"type":"content_block_stop","index":$i}""")
        }
        // Like the live API, message_delta carries the cumulative output count only.
        add("message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":$outputTokens}}""")
        add("message_stop" to """{"type":"message_stop"}""")
    }

    private fun usageJson(usage: SimulatedAnthropicPromptCache.Usage, outputTokens: Int) = buildJsonObject {
        put("input_tokens", usage.inputTokens)
        put("cache_creation_input_tokens", usage.cacheCreationInputTokens)
        put("cache_read_input_tokens", usage.cacheReadInputTokens)
        put("output_tokens", outputTokens)
    }

    override fun close() = server.stop(0)

    companion object {
        fun text(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

        fun toolUse(id: String, name: String, input: JsonObject): JsonObject = buildJsonObject {
            put("type", "tool_use"); put("id", id); put("name", name); put("input", input)
        }
    }
}
