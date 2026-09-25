package com.fartech.agents.jev

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JevApiTest {

    private fun sampleRequest() = JevRequest(
        model = "jev-latest",
        state = JsonPrimitive("Help! My payouts have been failing for 3 days."),
        questions = linkedMapOf(
            "team" to JevChoiceQuestion(
                instructions = JsonPrimitive("Which team should handle this?"),
                criteria = linkedMapOf(
                    "billing" to JsonPrimitive("Payments, invoicing, refunds"),
                    "technical" to JsonPrimitive("Bugs, outages, integrations"),
                    "general" to null,
                ),
            ),
            "frustration" to JevScoreQuestion(
                instructions = JsonPrimitive("How frustrated is the customer?"),
                criteria = listOf(JsonPrimitive("Calm"), JsonPrimitive("Frustrated"), JsonPrimitive("Very angry")),
            ),
            "is_urgent" to JevNoulQuestion(
                instructions = JsonPrimitive("Does this convey urgency?"),
                criteria = JevNoulCriteria(whenTrue = JsonPrimitive("Explicitly time-sensitive")),
            ),
            "plain_noul" to JevNoulQuestion(instructions = JsonPrimitive("Is it in English?")),
        ),
    )

    private val sampleResponseJson = """
        {
          "model": "jev-1.13.0",
          "request_echo": {"ignored": true},
          "answers": {
            "team": {"type": "choice", "choice": "billing",
                     "probabilities": {"billing": 0.88, "technical": 0.12, "general": 0.0},
                     "confidence": 0.81, "future_field": 1},
            "frustration": {"type": "score", "score": 1.05,
                            "legend": {"0": "Calm", "1": "Frustrated", "2": "Very angry"},
                            "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05}, "confidence": 0.92},
            "is_urgent": {"type": "noul", "noul": 0.95},
            "plain_noul": {"type": "noul", "noul": 0.1}
          },
          "usage": {"input_tokens": 318, "output_tokens": 34, "cache_tokens": 0}
        }
    """.trimIndent()

    @Nested
    inner class Encoding {

        @Test
        fun `every question carries its type discriminator`() {
            val body = JevJson.encodeToString(JevRequest.serializer(), sampleRequest())

            assertTrue(body.contains("\"type\":\"choice\""), body)
            assertTrue(body.contains("\"type\":\"score\""), body)
            assertTrue(body.contains("\"type\":\"noul\""), body)
        }

        @Test
        fun `encoded body has the documented wire shape`() {
            val encoded = JevJson.parseToJsonElement(
                JevJson.encodeToString(JevRequest.serializer(), sampleRequest())
            ).jsonObject

            assertEquals("jev-latest", encoded["model"]!!.jsonPrimitive.content)
            assertEquals("Help! My payouts have been failing for 3 days.", encoded["state"]!!.jsonPrimitive.content)
            val questions = encoded["questions"]!!.jsonObject
            assertEquals(listOf("team", "frustration", "is_urgent", "plain_noul"), questions.keys.toList())

            val team = questions["team"]!!.jsonObject
            assertEquals("choice", team["type"]!!.jsonPrimitive.content)
            val criteria = team["criteria"]!!.jsonObject
            // option order is preserved and a null description is sent as JSON null
            assertEquals(listOf("billing", "technical", "general"), criteria.keys.toList())
            assertEquals(JsonNull, criteria["general"])

            val frustration = questions["frustration"]!!.jsonObject
            assertEquals(listOf("Calm", "Frustrated", "Very angry"), frustration["criteria"]!!.jsonArray.map { it.jsonPrimitive.content })

            val urgent = questions["is_urgent"]!!.jsonObject
            val noulCriteria = urgent["criteria"]!!.jsonObject
            assertEquals("Explicitly time-sensitive", noulCriteria["true"]!!.jsonPrimitive.content)
            assertFalse(noulCriteria.containsKey("false"), "absent noul criteria side must be omitted")

            val plain = questions["plain_noul"]!!.jsonObject
            assertFalse(plain.containsKey("criteria"), "absent noul criteria must be omitted, not null")
        }

        @Test
        fun `structured state and instructions are passed through as JSON`() {
            val request = JevRequest(
                model = "jev-1.13.0",
                state = buildJsonObject { put("ticket", "refund please"); put("priority", 2) },
                questions = mapOf(
                    "dup" to JevNoulQuestion(
                        instructions = buildJsonObject { put("question", "Is this a duplicate of `previous`?") },
                    )
                ),
            )
            val encoded = JevJson.parseToJsonElement(JevJson.encodeToString(JevRequest.serializer(), request)).jsonObject

            assertIs<JsonObject>(encoded["state"])
            assertEquals("2", encoded["state"]!!.jsonObject["priority"]!!.jsonPrimitive.content)
            assertIs<JsonObject>(encoded["questions"]!!.jsonObject["dup"]!!.jsonObject["instructions"])
        }

        @Test
        fun `wire type names match discriminators`() {
            val request = sampleRequest()
            assertEquals(listOf("choice", "score", "noul", "noul"), request.questions.values.map { it.wireType })
        }
    }

    @Nested
    inner class Decoding {

        @Test
        fun `decodes all three answer types and ignores unknown fields`() {
            val response = JevJson.decodeFromString(JevResponse.serializer(), sampleResponseJson)

            assertEquals("jev-1.13.0", response.model)
            val team = assertIs<JevChoiceAnswer>(response.answers["team"])
            assertEquals("billing", team.choice)
            assertEquals(0.81, team.confidence)
            assertEquals(0.12, team.probabilities["technical"])

            val frustration = assertIs<JevScoreAnswer>(response.answers["frustration"])
            assertEquals(1.05, frustration.score)
            assertEquals("Frustrated", frustration.legend["1"])
            assertEquals(0.95, frustration.probabilities["1"])
            assertEquals(0.92, frustration.confidence)

            val urgent = assertIs<JevNoulAnswer>(response.answers["is_urgent"])
            assertEquals(0.95, urgent.noul)

            assertEquals(JevUsage(inputTokens = 318, outputTokens = 34), response.usage)
        }

        @Test
        fun `usage and optional answer fields may be absent`() {
            val response = JevJson.decodeFromString(
                JevResponse.serializer(),
                """{"model":"jev-1.13.0","answers":{"a":{"type":"choice","choice":"x"}}}""",
            )
            assertNull(response.usage)
            val a = assertIs<JevChoiceAnswer>(response.answers["a"])
            assertNull(a.confidence)
            assertTrue(a.probabilities.isEmpty())
        }
    }

    @Nested
    inner class Validation {

        private fun decode(json: String) = JevJson.decodeFromString(JevResponse.serializer(), json)

        @Test
        fun `valid response passes`() {
            decode(sampleResponseJson).validateAgainst(sampleRequest())
        }

        @Test
        fun `extra unrequested answers are ignored`() {
            val request = JevRequest(
                model = "jev-latest",
                state = JsonPrimitive("x"),
                questions = mapOf("q" to JevNoulQuestion(JsonPrimitive("?"))),
            )
            decode("""{"model":"m","answers":{"q":{"type":"noul","noul":0.4},"other":{"type":"noul","noul":1}}}""")
                .validateAgainst(request)
        }

        @Test
        fun `missing answer is an invalid response`() {
            val response = decode(
                """{"model":"jev-1.13.0","answers":{"team":{"type":"choice","choice":"billing"}}}"""
            )
            val error = assertFailsWith<JevApiException> { response.validateAgainst(sampleRequest(), requestId = "req_9") }
            assertEquals(JevApiException.Kind.INVALID_RESPONSE, error.kind)
            assertTrue(error.message!!.contains("frustration"), error.message)
            assertTrue(error.message!!.contains("req_9"), error.message)
            assertEquals("req_9", error.requestId)
            assertTrue(error.isTransient)
            assertFalse(error.isConfigurationError)
        }

        @Test
        fun `answer type mismatch is an invalid response`() {
            val request = JevRequest(
                model = "jev-latest",
                state = JsonPrimitive("x"),
                questions = mapOf("q" to JevScoreQuestion(JsonPrimitive("?"), listOf(JsonPrimitive("a"), JsonPrimitive("b")))),
            )
            val error = assertFailsWith<JevApiException> {
                decode("""{"model":"m","answers":{"q":{"type":"noul","noul":0.4}}}""").validateAgainst(request)
            }
            assertEquals(JevApiException.Kind.INVALID_RESPONSE, error.kind)
            assertTrue(error.message!!.contains("expected 'score'"), error.message)
        }

        @Test
        fun `choice outside the options is an invalid response`() {
            val request = JevRequest(
                model = "jev-latest",
                state = JsonPrimitive("x"),
                questions = mapOf(
                    "q" to JevChoiceQuestion(JsonPrimitive("?"), mapOf("a" to null, "b" to JsonPrimitive("B")))
                ),
            )
            val error = assertFailsWith<JevApiException> {
                decode("""{"model":"m","answers":{"q":{"type":"choice","choice":"c","confidence":0.9}}}""")
                    .validateAgainst(request)
            }
            assertEquals(JevApiException.Kind.INVALID_RESPONSE, error.kind)
        }

        @Test
        fun `unknown answer type fails decoding`() {
            assertFailsWith<kotlinx.serialization.SerializationException> {
                decode("""{"model":"m","answers":{"q":{"type":"rank","rank":[1,2]}}}""")
            }
        }
    }
}
