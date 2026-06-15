package com.fartech.agents.commons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Workaround for a regression in Koog 1.0.0's `AbstractOpenAILLMClient.convertPromptToMessages`.
 *
 * When converting a [ai.koog.prompt.message.MessagePart.Tool.Call] back into an
 * `OpenAIToolCall` for resend in the conversation history, the upstream code does:
 *
 * ```
 * function = OpenAIFunction(it.tool, Json.encodeToString(it.args))
 * ```
 *
 * `it.args` is **already** a JSON-encoded string (the data class stores `args: String`,
 * and the convenience `JsonObject` ctor immediately `Json.encodeToString`s it). Re-running
 * `Json.encodeToString` on that string wraps it in a JSON string literal, producing a
 * double-encoded `arguments` field on the wire:
 *
 * - Expected wire: `"arguments": "{\"path\":\"/foo\"}"`
 * - Buggy wire:   `"arguments": "\"{\\\"path\\\":\\\"/foo\\\"}\""`
 *
 * Lenient providers (OpenAI, Anthropic, DeepSeek, Claude via OpenRouter) silently
 * re-parse the inner string and recover. MiniMax (and several other providers routed
 * via OpenRouter) reject it midstream with
 * `invalid params, invalid function arguments json string, tool_call_id: ...`.
 *
 * This helper takes a serialized chat-completions request body, walks every
 * `messages[*].tool_calls[*].function.arguments`, and unwraps any value that is a JSON
 * string literal containing a JSON object. Idempotent: correctly-encoded arguments are
 * left untouched.
 */
internal object OpenAIToolArgsDoubleEncodeFix {

    private val json = Json {
        // Pass-through encoding — we round-trip the original wire bytes, so we should
        // not introduce structural differences (snake_case keys, default omissions, …).
        prettyPrint = false
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Returns [requestBody] with double-encoded tool-call arguments unwrapped, or the
     * original string when nothing needed fixing (or the body is not a JSON object).
     */
    fun fix(requestBody: String): String {
        // Fast path: this runs on EVERY OpenRouter request. Without any
        // "tool_calls" key there is nothing to rewrite — skip parsing the whole
        // serialized conversation (potentially megabytes late in a session).
        if (!requestBody.contains("\"tool_calls\"")) return requestBody
        val root = runCatching { json.parseToJsonElement(requestBody) }.getOrNull()
            ?: return requestBody
        if (root !is JsonObject) return requestBody
        val messages = root["messages"] as? JsonArray ?: return requestBody

        var anyChanged = false
        val fixedMessages = messages.map { message ->
            val rewritten = rewriteMessage(message)
            if (rewritten !== message) anyChanged = true
            rewritten
        }
        if (!anyChanged) return requestBody

        val rewritten = JsonObject(root.toMutableMap().apply {
            this["messages"] = JsonArray(fixedMessages)
        })
        return json.encodeToString(JsonElement.serializer(), rewritten)
    }

    private fun rewriteMessage(message: JsonElement): JsonElement {
        if (message !is JsonObject) return message
        val toolCalls = message["tool_calls"] as? JsonArray ?: return message

        var anyChanged = false
        val fixedCalls = toolCalls.map { call ->
            val rewritten = rewriteToolCall(call)
            if (rewritten !== call) anyChanged = true
            rewritten
        }
        if (!anyChanged) return message

        return JsonObject(message.toMutableMap().apply {
            this["tool_calls"] = JsonArray(fixedCalls)
        })
    }

    private fun rewriteToolCall(call: JsonElement): JsonElement {
        if (call !is JsonObject) return call
        val function = call["function"] as? JsonObject ?: return call
        val argsElement = function["arguments"] as? JsonPrimitive ?: return call
        if (!argsElement.isString) return call

        val unwrapped = unwrapDoubleEncoded(argsElement.content) ?: return call
        val fixedFunction = JsonObject(function.toMutableMap().apply {
            this["arguments"] = JsonPrimitive(unwrapped)
        })
        return JsonObject(call.toMutableMap().apply {
            this["function"] = fixedFunction
        })
    }

    /**
     * Detects the double-encoding signature and returns the inner JSON-object string,
     * or null when [content] is already a correctly-encoded arguments value.
     *
     * Heuristic:
     *   - Correct: starts with `{` (raw JSON object literal).
     *   - Buggy:   starts with `"` AND parses as a [JsonPrimitive] string AND that
     *              string's content parses to a [JsonObject].
     *
     * Anything else is left alone: empty strings, primitive strings the model
     * deliberately produced, hand-rolled prose, etc.
     */
    private fun unwrapDoubleEncoded(content: String): String? {
        if (content.isEmpty()) return null
        if (content[0] != '"') return null
        val inner = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return null
        if (inner !is JsonPrimitive || !inner.isString) return null
        val innerString = inner.content
        val innerElement = runCatching { json.parseToJsonElement(innerString) }.getOrNull() ?: return null
        if (innerElement !is JsonObject) return null
        return innerString
    }
}
