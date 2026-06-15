package com.fartech.agents.commons

import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAIToolArgsDoubleEncodeFixTest {

    /**
     * End-to-end reproduction of the Koog 1.0.0 regression. Constructing a
     * `MessagePart.Tool.Call` via the `JsonObject` ctor stores the JSON-encoded
     * args as a String. Running `Json.encodeToString(args: String)` (the exact
     * line in `AbstractOpenAILLMClient.convertPromptToMessages`) wraps it in
     * a JSON string literal, producing the buggy wire shape.
     */
    @Test
    fun `reproduces the buggy wire shape Koog 1_0_0 emits`() {
        val toolCall = MessagePart.Tool.Call(
            id = "call_469b4a7e2b00414c896ab6a1",
            tool = "readFile",
            args = Json.parseToJsonElement("""{"path":"/foo/bar.json"}""").jsonObject
        )
        // args field on the Kotlin side is now a JSON-object string.
        assertEquals("""{"path":"/foo/bar.json"}""", toolCall.args)

        // Reproduce convertPromptToMessages's buggy line and verify the result is
        // a JSON string literal (starts with a quote), not a JSON-object string.
        val koogEmitsForArgumentsField = Json.encodeToString(toolCall.args)
        assertTrue(koogEmitsForArgumentsField.startsWith("\""))
        assertEquals("\"{\\\"path\\\":\\\"/foo/bar.json\\\"}\"", koogEmitsForArgumentsField)
    }

    @Test
    fun `unwraps single tool call with double-encoded arguments`() {
        // Synthesised body matching what Koog 1.0.0 emits onto the wire.
        val buggy = """
            {
              "model": "minimax/minimax-m2.7",
              "messages": [
                {"role": "user", "content": "list files"},
                {
                  "role": "assistant",
                  "tool_calls": [
                    {
                      "id": "call_469b4a7e2b00414c896ab6a1",
                      "type": "function",
                      "function": {
                        "name": "readFile",
                        "arguments": "\"{\\\"path\\\":\\\"/foo/bar.json\\\"}\""
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val fixed = OpenAIToolArgsDoubleEncodeFix.fix(buggy)
        val arguments = Json.parseToJsonElement(fixed)
            .jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("""{"path":"/foo/bar.json"}""", arguments)
    }

    @Test
    fun `unwraps every tool call in a parallel tool-call assistant message`() {
        val buggy = """
            {
              "messages": [
                {
                  "role": "assistant",
                  "tool_calls": [
                    {"id": "a", "type": "function", "function": {"name": "readFile", "arguments": "\"{\\\"path\\\":\\\"/one.json\\\"}\""}},
                    {"id": "b", "type": "function", "function": {"name": "readFile", "arguments": "\"{\\\"path\\\":\\\"/two.json\\\"}\""}},
                    {"id": "c", "type": "function", "function": {"name": "readFile", "arguments": "\"{\\\"path\\\":\\\"/three.json\\\"}\""}}
                  ]
                }
              ]
            }
        """.trimIndent()

        val fixed = OpenAIToolArgsDoubleEncodeFix.fix(buggy)
        val calls = Json.parseToJsonElement(fixed)
            .jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["tool_calls"]!!.jsonArray
        assertEquals(3, calls.size)
        val argumentValues = calls.map { it.jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                """{"path":"/one.json"}""",
                """{"path":"/two.json"}""",
                """{"path":"/three.json"}""",
            ),
            argumentValues,
        )
    }

    @Test
    fun `is idempotent — leaves correctly encoded arguments untouched`() {
        val good = """
            {
              "messages": [
                {
                  "role": "assistant",
                  "tool_calls": [
                    {"id": "x", "type": "function", "function": {"name": "readFile", "arguments": "{\"path\":\"/foo.json\"}"}}
                  ]
                }
              ]
            }
        """.trimIndent()

        // Same input on a second pass should match the first pass exactly.
        val onePass = OpenAIToolArgsDoubleEncodeFix.fix(good)
        val twoPass = OpenAIToolArgsDoubleEncodeFix.fix(onePass)
        val arguments = Json.parseToJsonElement(onePass)
            .jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("""{"path":"/foo.json"}""", arguments)
        assertEquals(onePass, twoPass)
    }

    @Test
    fun `passes through requests without tool calls unchanged`() {
        val body = """{"model":"x","messages":[{"role":"user","content":"hello"}]}"""
        assertSame(body, OpenAIToolArgsDoubleEncodeFix.fix(body))
    }

    @Test
    fun `passes through non-JSON request bodies unchanged`() {
        val body = "not even json"
        assertSame(body, OpenAIToolArgsDoubleEncodeFix.fix(body))
    }

    @Test
    fun `does not unwrap when inner content is not a JSON object`() {
        // arguments wire value is a quoted string but the content is not a JSON object.
        // We must leave it alone — could be a deliberate plain-string tool argument.
        val body = """
            {
              "messages": [
                {
                  "role": "assistant",
                  "tool_calls": [
                    {"id": "x", "type": "function", "function": {"name": "echo", "arguments": "\"plain string\""}}
                  ]
                }
              ]
            }
        """.trimIndent()
        val fixed = OpenAIToolArgsDoubleEncodeFix.fix(body)
        val arguments = Json.parseToJsonElement(fixed)
            .jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("\"plain string\"", arguments)
    }

    @Test
    fun `preserves sibling fields on tool calls assistant messages and request root`() {
        val buggy = """
            {
              "model": "x",
              "temperature": 0.2,
              "messages": [
                {"role": "system", "content": "be helpful"},
                {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [
                    {"id": "x", "type": "function", "function": {"name": "readFile", "arguments": "\"{\\\"path\\\":\\\"/p.json\\\"}\""}}
                  ]
                }
              ],
              "stream": true
            }
        """.trimIndent()

        val fixed = OpenAIToolArgsDoubleEncodeFix.fix(buggy)
        val root = Json.parseToJsonElement(fixed).jsonObject
        assertEquals("x", root["model"]!!.jsonPrimitive.content)
        assertEquals(true, root["stream"]!!.jsonPrimitive.boolean)
        val assistant = root["messages"]!!.jsonArray[1].jsonObject
        // Even null `content` should remain a JsonNull on the wire because the
        // upstream serializer chose to emit it (we preserve the wire bytes).
        assertTrue(assistant.containsKey("content"))
        val callType = assistant["tool_calls"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("function", callType)
    }
}
