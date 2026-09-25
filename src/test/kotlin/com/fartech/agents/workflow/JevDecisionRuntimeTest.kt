package com.fartech.agents.workflow

import com.fartech.agents.jev.JevApiException
import com.fartech.agents.jev.JevChoiceAnswer
import com.fartech.agents.jev.JevJson
import com.fartech.agents.jev.JevNoulAnswer
import com.fartech.agents.jev.JevNoulQuestion
import com.fartech.agents.jev.JevRequest
import com.fartech.agents.jev.JevResponse
import com.fartech.agents.jev.JevScoreAnswer
import com.fartech.agents.jev.JevUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure runtime helpers of JevDecisionRuntime.kt (spec §4.1–§4.6). */
class JevDecisionRuntimeTest {

    private val accuracy = JevQuestionConfig(
        id = "accuracy",
        type = JevQuestionType.SCORE,
        instructions = "How factually accurate is the draft?",
        levels = listOf("Clear errors", "Minor imprecision", "Accurate")
    )
    private val onBrand = JevQuestionConfig(
        id = "on_brand",
        type = JevQuestionType.NOUL,
        instructions = "Does the draft follow a friendly tone?"
    )
    private val tone = JevQuestionConfig(
        id = "tone",
        type = JevQuestionType.CHOICE,
        instructions = "Which tone is it?",
        options = listOf(ClassifierCategory("formal", "Formal"), ClassifierCategory("casual", "Casual")),
        defaultOption = "formal",
        minConfidence = 0.6
    )
    private val quality = JevComposite("quality", mapOf("accuracy" to 0.7, "on_brand" to 0.3))

    private fun classifier(minConfidence: Double? = 0.6, defaultCategory: String? = "general") = ClassifierConfig(
        input = "{{var:text}}",
        categories = listOf(
            ClassifierCategory("billing", "Payments"),
            ClassifierCategory("technical", "Bugs"),
            ClassifierCategory("general", "Anything else")
        ),
        outputVariable = "team",
        defaultCategory = defaultCategory,
        jev = ClassifierJevConfig(minConfidence = minConfidence, questions = listOf(onBrand))
    )

    // ==================== §4.1 number formatting ====================

    @Test
    fun `numbers are plain decimals with at most four fraction digits`() {
        assertEquals("0", formatJevNumber(0.0))
        assertEquals("0", formatJevNumber(-0.0))
        assertEquals("0", formatJevNumber(0.00001))
        assertEquals("0", formatJevNumber(1e-9))
        assertEquals("0.0001", formatJevNumber(0.0001))
        assertEquals("0.81", formatJevNumber(0.81))
        assertEquals("0.525", formatJevNumber(0.525))
        assertEquals("1.05", formatJevNumber(1.05))
        assertEquals("0.1235", formatJevNumber(0.123456))
        assertEquals("1", formatJevNumber(1.0))
        assertEquals("2", formatJevNumber(2.0))
        assertEquals("10", formatJevNumber(10.0))
        assertEquals("123456.7", formatJevNumber(123456.7))
        assertEquals("0.6667", formatJevNumber(2.0 / 3.0))
        assertEquals("-0.5", formatJevNumber(-0.5))
        assertEquals("", formatJevNumber(Double.NaN))
    }

    @Test
    fun `probability maps follow configured order and fill missing keys with zero`() {
        assertEquals(
            """{"billing":0.88,"technical":0.12,"general":0}""",
            jevProbabilitiesJson(listOf("billing", "technical", "general"), mapOf("general" to 0.0, "technical" to 0.12, "billing" to 0.88))
        )
        assertEquals("""{"a":0,"b":0}""", jevProbabilitiesJson(listOf("a", "b"), mapOf("a" to 0.00001)))
        assertEquals("{}", jevProbabilitiesJson(listOf("a", "b"), emptyMap()))
    }

    // ==================== request building ====================

    @Test
    fun `classifier request puts the main question first and resolves templates`() {
        val config = classifier().copy(instructions = "Route for {{name}}")
        val request = buildJevClassifierRequest(config, "jev-latest", "state text") { it.replace("{{name}}", "Ada") }
        assertEquals(listOf("team", "on_brand"), request.questions.keys.toList())
        assertEquals(JsonPrimitive("state text"), request.state)
        val body = JevJson.encodeToString(JevRequest.serializer(), request)
        assertContains(body, """"team":{"type":"choice","instructions":"Route for Ada","criteria":{"billing":"Payments","technical":"Bugs","general":"Anything else"}}""")
        assertContains(body, """"on_brand":{"type":"noul","instructions":"Does the draft follow a friendly tone?"}""")

        val defaultInstructions = buildJevClassifierRequest(classifier(), "m", "s") { it }
        assertEquals(
            JsonPrimitive(JEV_DEFAULT_CLASSIFIER_INSTRUCTIONS),
            defaultInstructions.questions.getValue("team").instructions
        )
    }

    @Test
    fun `noul criteria are sent only when a description is set`() {
        val withYes = onBrand.copy(yesDescription = "Friendly")
        val question = buildJevQuestion(withYes) { it } as JevNoulQuestion
        assertEquals(JsonPrimitive("Friendly"), question.criteria?.whenTrue)
        assertNull(question.criteria?.whenFalse)
        val body = JevJson.encodeToString(JevRequest.serializer(), JevRequest("m", JsonPrimitive("s"), mapOf("q" to question)))
        assertContains(body, """"criteria":{"true":"Friendly"}""")
        assertNull((buildJevQuestion(onBrand) { it } as JevNoulQuestion).criteria)
    }

    // ==================== decisions / variables ====================

    @Test
    fun `score and noul answers produce the normative variables and composites`() {
        val decision = evaluateJevQuestionSet(
            "draft",
            listOf(accuracy, onBrand),
            listOf(quality),
            mapOf(
                "accuracy" to JevScoreAnswer(score = 1.2, probabilities = mapOf("0" to 0.1, "1" to 0.6, "2" to 0.3), confidence = 0.84),
                "on_brand" to JevNoulAnswer(0.93)
            )
        )
        assertEquals(
            linkedMapOf(
                "accuracy" to "1.2",
                "accuracy_level" to "1",
                "accuracy_normalized" to "0.6",
                "accuracy_confidence" to "0.84",
                "accuracy_probabilities" to """{"0":0.1,"1":0.6,"2":0.3}""",
                "on_brand" to "0.93",
                "on_brand_yes" to "true",
                "quality" to "0.699"
            ),
            decision.allVariables(listOf(quality))
        )
        assertEquals(
            JevVariableNames.forQuestions(listOf("accuracy" to "score", "on_brand" to "noul"), listOf("quality")),
            decision.allVariables(listOf(quality)).keys.toList()
        )
    }

    @Test
    fun `score level rounds and clamps, normalized is clamped`() {
        fun levelOf(score: Double) = (evaluateJevQuestionSet(
            "s", listOf(accuracy), emptyList(), mapOf("accuracy" to JevScoreAnswer(score = score))
        ).outcomes.single() as JevScoreOutcome)
        assertEquals(1, levelOf(1.49).level)
        assertEquals(2, levelOf(1.5).level)
        assertEquals(2, levelOf(2.7).level)
        assertEquals(1.0, levelOf(2.7).normalized)
        assertEquals(0, levelOf(-0.3).level)
        assertEquals(0.0, levelOf(-0.3).normalized)
        assertEquals("", evaluateJevQuestionSet("s", listOf(accuracy), emptyList(), mapOf("accuracy" to JevScoreAnswer(score = 1.0)))
            .variables()["accuracy_confidence"])
        assertEquals("{}", evaluateJevQuestionSet("s", listOf(accuracy), emptyList(), mapOf("accuracy" to JevScoreAnswer(score = 1.0)))
            .variables()["accuracy_probabilities"])
    }

    @Test
    fun `noul threshold decides the yes flag`() {
        val strict = onBrand.copy(threshold = 0.7)
        fun yes(question: JevQuestionConfig, value: Double) =
            evaluateJevQuestionSet("s", listOf(question), emptyList(), mapOf(question.id to JevNoulAnswer(value))).variables()["on_brand_yes"]
        assertEquals("true", yes(onBrand, 0.5))
        assertEquals("false", yes(onBrand, 0.4999))
        assertEquals("false", yes(strict, 0.69))
        assertEquals("true", yes(strict, 0.7))
    }

    @Test
    fun `choice below min_confidence uses default_option or fails`() {
        val decision = evaluateJevQuestionSet(
            "s", listOf(tone), emptyList(),
            mapOf("tone" to JevChoiceAnswer("casual", mapOf("formal" to 0.45, "casual" to 0.55), 0.55))
        )
        val outcome = decision.outcomes.single() as JevChoiceOutcome
        assertTrue(outcome.fellBack)
        assertEquals("formal", outcome.value)
        assertEquals("casual", outcome.rawChoice)
        assertEquals("0.55", decision.variables()["tone_confidence"])

        val error = assertThrows<WorkflowExecutionException> {
            evaluateJevQuestionSet(
                "s", listOf(tone.copy(defaultOption = null)), emptyList(),
                mapOf("tone" to JevChoiceAnswer("casual", emptyMap(), null))
            )
        }
        assertEquals(
            "Step 's': Jev question 'tone' confidence (none) is below min_confidence 0.6 and no default_option is set",
            error.message
        )
    }

    @Test
    fun `wrong answer types are invalid responses`() {
        val error = assertThrows<JevApiException> {
            evaluateJevQuestionSet("s", listOf(accuracy), emptyList(), mapOf("accuracy" to JevNoulAnswer(0.5)))
        }
        assertEquals(JevApiException.Kind.INVALID_RESPONSE, error.kind)
        assertTrue(error.isTransient)
    }

    @Test
    fun `classifier decision applies min_confidence to the main answer`() {
        val response = JevResponse(
            model = "jev-1.13.0",
            answers = mapOf(
                "team" to JevChoiceAnswer("billing", mapOf("billing" to 0.5, "technical" to 0.3, "general" to 0.2), 0.5),
                "on_brand" to JevNoulAnswer(0.2)
            )
        )
        val decision = decideJevClassifier("triage", classifier(), response)
        assertEquals("general", decision.category)
        assertEquals(JEV_FALLBACK_LOW_CONFIDENCE, decision.fallbackReason)
        assertEquals(
            linkedMapOf(
                "team" to "general",
                "team_confidence" to "0.5",
                "team_probabilities" to """{"billing":0.5,"technical":0.3,"general":0.2}""",
                "on_brand" to "0.2",
                "on_brand_yes" to "false"
            ),
            decision.variables(classifier())
        )
        assertEquals("classification: general", jevClassifierOutput(decision.category, null))
        assertEquals("classification: billing\nconfidence: 0.5", jevClassifierOutput("billing", 0.5))

        val confident = decideJevClassifier("triage", classifier(minConfidence = 0.5), response)
        assertEquals("billing", confident.category)
        assertFalse(confident.fellBack)

        val error = assertThrows<WorkflowExecutionException> {
            decideJevClassifier("triage", classifier(defaultCategory = null), response)
        }
        assertEquals(
            "Classifier step 'triage': Jev confidence 0.5 is below min_confidence 0.6 and no default_category is set",
            error.message
        )
    }

    @Test
    fun `fallback values cover every derived variable`() {
        val config = classifier().copy(
            jev = ClassifierJevConfig(questions = listOf(accuracy, onBrand, tone), composites = listOf(quality))
        )
        val values = jevClassifierErrorFallbackVariables(config, "general")
        assertEquals(config.jevWrittenVariables(), values.keys.toList())
        assertEquals("general", values["team"])
        assertEquals("0", values["team_confidence"])
        assertEquals("{}", values["team_probabilities"])
        assertEquals("false", values["on_brand_yes"])
        values.filterKeys { it !in setOf("team", "team_confidence", "team_probabilities", "on_brand_yes") }
            .forEach { (name, value) -> assertEquals("", value, name) }

        val loop = jevQuestionSetFallbackVariables(listOf(accuracy, onBrand), listOf(quality))
        assertEquals(
            RepeatUntilJevConfig(questions = listOf(accuracy, onBrand), composites = listOf(quality)).writtenVariables(),
            loop.keys.toList()
        )
        assertEquals("false", loop["on_brand_yes"])
        assertEquals("", loop["quality"])
    }

    // ==================== agent-engine prompt (unchanged without instructions) ====================

    @Test
    fun `agent classifier prompt is byte-for-byte unchanged without instructions`() {
        val categories = listOf(ClassifierCategory("tech", "Technical"), ClassifierCategory("billing", "Billing"))
        val legacy = "You are a classifier. Analyze the following input and classify it into exactly one of the given categories.\n" +
            "\n" +
            "INPUT:\n" +
            "my printer is on fire\n" +
            "\n" +
            "CATEGORIES:\n" +
            "- tech: Technical\n" +
            "- billing: Billing\n" +
            "\n" +
            "IMPORTANT: You MUST respond with ONLY the category name (one of: tech, billing). " +
            "Do not include any explanation, punctuation, or extra text. Just the category name."
        assertEquals(legacy, buildLlmClassifierPrompt("my printer is on fire", categories, null))

        val withTask = buildLlmClassifierPrompt("my printer is on fire", categories, "Which queue owns this?")
        assertEquals(
            legacy.replace("\n\nINPUT:\n", "\n\nTASK:\nWhich queue owns this?\n\nINPUT:\n"),
            withTask
        )
    }

    // ==================== payloads / critique / usage ====================

    @Test
    fun `classifier payload uses snake_case keys and plain json numbers`() {
        val config = classifier()
        val response = JevResponse(
            model = "jev-1.13.0",
            answers = mapOf(
                "team" to JevChoiceAnswer("billing", mapOf("billing" to 0.88, "technical" to 0.12, "general" to 0.0), 0.81),
                "on_brand" to JevNoulAnswer(0.00001)
            ),
            usage = JevUsage(318, 34)
        )
        val decision = decideJevClassifier("triage", config, response)
        val payload = jevClassifierDecisionPayload(config, response.model, decision, response.usage)
        assertEquals(
            """{"engine":"jev","kind":"classifier","model":"jev-1.13.0","output_variable":"team","category":"billing",""" +
                """"confidence":0.81,"probabilities":{"billing":0.88,"technical":0.12,"general":0},"min_confidence":0.6,""" +
                """"fell_back":false,"fallback_reason":null,""" +
                """"answers":{"on_brand":{"type":"noul","value":0,"yes":false,"threshold":0.5}},"composites":{},""" +
                """"usage":{"input_tokens":318,"output_tokens":34}}""",
            payload
        )
        assertFalse(payload.contains("E-"), "never scientific notation")

        val errorPayload = Json.parseToJsonElement(jevClassifierErrorPayload(config, "jev-latest", "general", "overloaded")).jsonObject
        assertEquals(JsonNull, errorPayload["confidence"])
        assertEquals(JsonPrimitive("error"), errorPayload["fallback_reason"])
        assertEquals(JsonPrimitive("general"), errorPayload["category"])
    }

    @Test
    fun `large payloads drop probability maps to stay under the event limit`() {
        val categories = (1..255).map { ClassifierCategory("category_with_a_long_name_$it", "Description $it") }
        val config = ClassifierConfig(input = "x", categories = categories, outputVariable = "route", jev = ClassifierJevConfig())
        val probabilities = categories.associate { it.name to 1.0 / 255 }
        val response = JevResponse("jev-1.13.0", mapOf("route" to JevChoiceAnswer(categories.first().name, probabilities, 0.9)))
        val decision = decideJevClassifier("s", config, response)
        val payload = jevClassifierDecisionPayload(config, response.model, decision, null)
        assertTrue(payload.length <= JEV_PAYLOAD_SOFT_LIMIT_CHARS, "payload length ${payload.length}")
        val parsed = Json.parseToJsonElement(payload).jsonObject
        assertFalse(parsed.containsKey("probabilities"))
        assertEquals(JsonPrimitive(true), parsed["truncated"])
        // The variable still carries the full distribution.
        assertTrue(decision.variables(config).getValue("route_probabilities").length > JEV_PAYLOAD_SOFT_LIMIT_CHARS)
    }

    @Test
    fun `critique lists the weakest dimension first then composites`() {
        val decision = evaluateJevQuestionSet(
            "draft",
            listOf(onBrand, tone, accuracy),
            listOf(quality),
            mapOf(
                "accuracy" to JevScoreAnswer(score = 1.2, confidence = 0.84),
                "on_brand" to JevNoulAnswer(0.93),
                "tone" to JevChoiceAnswer("casual", mapOf("formal" to 0.2, "casual" to 0.8), 0.8)
            )
        )
        assertEquals(
            """
            Jev evaluation (iteration 2, model jev-1.13.0):
            - accuracy: level 1/2 "Minor imprecision" (score 1.2, confidence 0.84)
            - on_brand: yes (p=0.93)
            - tone: casual (confidence 0.8)
            - quality (composite): 0.699
            """.trimIndent(),
            buildJevCritique(2, "jev-1.13.0", decision, listOf(quality))
        )
    }

    @Test
    fun `usage event mirrors the koog llm_call_completed shape`() {
        val event = buildJevUsageEvent(JevResponse("jev-1.13.0", emptyMap(), JevUsage(318, 34)))
        assertEquals("llm_call_completed", event.type)
        assertEquals("llm", event.category)
        assertEquals("call", event.subCategory)
        assertEquals("model=jev-1.13.0, provider=TypeSafe; input=318, output=34", event.detail)
        assertEquals("✅ 模型调用完成 · jev-1.13.0（TypeSafe）", event.summary)
        assertEquals(318, event.inputTokens)
        assertEquals(34, event.outputTokens)
        assertEquals(352, event.totalTokens)

        val noUsage = buildJevUsageEvent(JevResponse("jev-1.13.0", emptyMap(), null))
        assertEquals("model=jev-1.13.0, provider=TypeSafe", noUsage.detail)
        assertNull(noUsage.inputTokens)
    }
}
