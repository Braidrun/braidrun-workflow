package com.fartech.agents.workflow

import com.fartech.agents.jev.JevAnswer
import com.fartech.agents.jev.JevApiException
import com.fartech.agents.jev.JevChoiceAnswer
import com.fartech.agents.jev.JevChoiceQuestion
import com.fartech.agents.jev.JevNoulAnswer
import com.fartech.agents.jev.JevNoulCriteria
import com.fartech.agents.jev.JevNoulQuestion
import com.fartech.agents.jev.JevQuestion
import com.fartech.agents.jev.JevRequest
import com.fartech.agents.jev.JevResponse
import com.fartech.agents.jev.JevScoreAnswer
import com.fartech.agents.jev.JevScoreQuestion
import com.fartech.agents.jev.JevUsage
import com.fartech.agents.jev.TYPESAFE_PROVIDER_DISPLAY_NAME
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

// ============================================================================
// Jev decision runtime (classifier.jev / repeat_until.jev)
// ============================================================================
//
// Pure helpers used by WorkflowExecutor: request building, answer → workflow
// variables, number formatting, event payloads and the repeat_until critique.
// Nothing here performs I/O or touches the execution context, which keeps the
// executor changes small and makes every rule unit-testable.
//
// Variable contract (normative, mirrored by the web lint and the frontend):
// see JevVariableNames. All numeric values are strings formatted by
// formatJevNumber; booleans are "true" / "false"; probability maps are compact
// JSON objects keyed in configured option / level order.

/** Default instructions of the classifier's main choice question when `classifier.instructions` is absent. */
internal const val JEV_DEFAULT_CLASSIFIER_INSTRUCTIONS = "Classify the input into the single best-matching category."

/** `fallback_reason` values of the decision payload. */
internal const val JEV_FALLBACK_LOW_CONFIDENCE = "low_confidence"
internal const val JEV_FALLBACK_ERROR = "error"

/** Keep the decision payload below the web event-detail compaction limit (8 KB). */
internal const val JEV_PAYLOAD_SOFT_LIMIT_CHARS = 7_000

private val payloadJson = Json { encodeDefaults = true }

// ---------------------------------------------------------------------------
// Number formatting (§4.1)
// ---------------------------------------------------------------------------

/**
 * Plain decimal with at most 4 fraction digits, trailing zeros stripped, never
 * scientific notation; zero (including -0.0) is "0". Non-finite values (rejected
 * by response validation, so only reachable from buggy callers) render as "".
 */
internal fun formatJevNumber(value: Double): String {
    if (!value.isFinite()) return ""
    val scaled = BigDecimal(value).setScale(4, RoundingMode.HALF_UP)
    if (scaled.signum() == 0) return "0"
    return scaled.stripTrailingZeros().toPlainString()
}

/** A JSON number literal formatted like [formatJevNumber] (JSON null for non-finite input). */
@OptIn(ExperimentalSerializationApi::class)
internal fun jevJsonNumber(value: Double?): JsonElement {
    if (value == null || !value.isFinite()) return JsonNull
    return JsonUnquotedLiteral(formatJevNumber(value))
}

/**
 * Compact JSON map for a `<x>_probabilities` variable / payload: keys in [orderedKeys]
 * order (missing keys = 0), numbers formatted per §4.1. An empty [probabilities] map
 * (Jev omitted the distribution) yields `{}`.
 */
internal fun jevProbabilitiesObject(orderedKeys: List<String>, probabilities: Map<String, Double>): JsonObject {
    if (probabilities.isEmpty()) return JsonObject(emptyMap())
    return buildJsonObject {
        orderedKeys.forEach { key -> put(key, jevJsonNumber(probabilities[key] ?: 0.0)) }
    }
}

internal fun jevProbabilitiesJson(orderedKeys: List<String>, probabilities: Map<String, Double>): String =
    payloadJson.encodeToString(JsonObject.serializer(), jevProbabilitiesObject(orderedKeys, probabilities))

// ---------------------------------------------------------------------------
// Request building (§4.3 / §4.4)
// ---------------------------------------------------------------------------

/**
 * Wire question for one configured question. [resolve] template-resolves the
 * instructions against the execution context.
 */
internal fun buildJevQuestion(question: JevQuestionConfig, resolve: (String) -> String): JevQuestion {
    val instructions = JsonPrimitive(resolve(question.instructions))
    return when (question.type) {
        JevQuestionType.CHOICE -> JevChoiceQuestion(
            instructions = instructions,
            criteria = LinkedHashMap<String, JsonElement?>().apply {
                question.options.orEmpty().forEach { option -> put(option.name, JsonPrimitive(option.description)) }
            }
        )

        JevQuestionType.SCORE -> JevScoreQuestion(
            instructions = instructions,
            criteria = question.levels.orEmpty().map { JsonPrimitive(it) }
        )

        JevQuestionType.NOUL -> JevNoulQuestion(
            instructions = instructions,
            criteria = if (question.yesDescription != null || question.noDescription != null) {
                JevNoulCriteria(
                    whenTrue = question.yesDescription?.let { JsonPrimitive(it) },
                    whenFalse = question.noDescription?.let { JsonPrimitive(it) }
                )
            } else {
                null
            }
        )
    }
}

/**
 * Classifier request: the main CHOICE question over `categories` (id =
 * `output_variable`) plus every extra question, all in ONE request.
 * [resolvedInput] becomes the Jev `state` (a JSON string).
 */
internal fun buildJevClassifierRequest(
    config: ClassifierConfig,
    model: String,
    resolvedInput: String,
    resolve: (String) -> String
): JevRequest {
    val jevConfig = requireNotNull(config.jev) { "classifier is not a Jev classifier" }
    val questions = LinkedHashMap<String, JevQuestion>()
    questions[config.outputVariable] = JevChoiceQuestion(
        instructions = JsonPrimitive(config.instructions?.let(resolve) ?: JEV_DEFAULT_CLASSIFIER_INSTRUCTIONS),
        criteria = LinkedHashMap<String, JsonElement?>().apply {
            config.categories.forEach { category -> put(category.name, JsonPrimitive(category.description)) }
        }
    )
    jevConfig.questions.forEach { question -> questions[question.id] = buildJevQuestion(question, resolve) }
    return JevRequest(model = model, state = JsonPrimitive(resolvedInput), questions = questions)
}

/** repeat_until request: every configured question over [resolvedState]. */
internal fun buildJevQuestionSetRequest(
    questions: List<JevQuestionConfig>,
    model: String,
    resolvedState: String,
    resolve: (String) -> String
): JevRequest {
    val wireQuestions = LinkedHashMap<String, JevQuestion>()
    questions.forEach { question -> wireQuestions[question.id] = buildJevQuestion(question, resolve) }
    return JevRequest(model = model, state = JsonPrimitive(resolvedState), questions = wireQuestions)
}

// ---------------------------------------------------------------------------
// Answer → decision (§4.2)
// ---------------------------------------------------------------------------

/** One evaluated question. */
internal sealed interface JevQuestionOutcome {
    val question: JevQuestionConfig
    val id: String get() = question.id

    /** 0..1 value used by composites and the critique ordering; null for choice questions. */
    val normalizedValue: Double?
}

internal data class JevChoiceOutcome(
    override val question: JevQuestionConfig,
    /** Final value: the Jev choice, or `default_option` when below `min_confidence`. */
    val value: String,
    val rawChoice: String,
    val confidence: Double?,
    val probabilities: Map<String, Double>,
    val fellBack: Boolean
) : JevQuestionOutcome {
    override val normalizedValue: Double? get() = null
    val optionNames: List<String> get() = question.options.orEmpty().map { it.name }
}

internal data class JevScoreOutcome(
    override val question: JevQuestionConfig,
    /** Raw probability-weighted score (0..n-1). */
    val score: Double,
    /** Rounded score clamped to 0..n-1. */
    val level: Int,
    /** score / (n-1), clamped to 0..1. */
    val normalized: Double,
    val label: String?,
    val confidence: Double?,
    val probabilities: Map<String, Double>
) : JevQuestionOutcome {
    override val normalizedValue: Double get() = normalized
    val levelCount: Int get() = question.levels.orEmpty().size
    val levelKeys: List<String> get() = (0 until levelCount).map { it.toString() }
}

internal data class JevNoulOutcome(
    override val question: JevQuestionConfig,
    /** Probability of "yes" (0..1). */
    val value: Double,
    val threshold: Double,
    val yes: Boolean
) : JevQuestionOutcome {
    override val normalizedValue: Double get() = value
}

/** Evaluated question set: outcomes in configured order plus composite values (configured order). */
internal data class JevQuestionSetDecision(
    val outcomes: List<JevQuestionOutcome>,
    val composites: Map<String, Double>
)

/**
 * Evaluates every configured question against [answers] (already validated
 * against the request) and computes the composites.
 *
 * @throws WorkflowExecutionException when a choice question's confidence is below
 *   its `min_confidence` and it has no `default_option`.
 */
internal fun evaluateJevQuestionSet(
    stepName: String,
    questions: List<JevQuestionConfig>,
    composites: List<JevComposite>,
    answers: Map<String, JevAnswer>
): JevQuestionSetDecision {
    val outcomes = questions.map { question ->
        val answer = answers[question.id]
            ?: throw JevApiException(
                JevApiException.Kind.INVALID_RESPONSE, null,
                "TypeSafe API returned an invalid response: no answer for question '${question.id}'"
            )
        when (question.type) {
            JevQuestionType.CHOICE -> {
                val choice = answer as? JevChoiceAnswer ?: throw answerTypeMismatch(question, answer)
                val minConfidence = question.minConfidence
                val below = minConfidence != null && isBelow(choice.confidence, minConfidence)
                val value = if (below) {
                    question.defaultOption ?: throw WorkflowExecutionException(
                        "Step '$stepName': Jev question '${question.id}' confidence ${describeConfidence(choice.confidence)} " +
                            "is below min_confidence ${formatJevNumber(minConfidence)} and no default_option is set"
                    )
                } else {
                    choice.choice
                }
                JevChoiceOutcome(
                    question = question,
                    value = value,
                    rawChoice = choice.choice,
                    confidence = choice.confidence,
                    probabilities = choice.probabilities,
                    fellBack = below
                )
            }

            JevQuestionType.SCORE -> {
                val score = answer as? JevScoreAnswer ?: throw answerTypeMismatch(question, answer)
                val levels = question.levels.orEmpty()
                val maxLevel = (levels.size - 1).coerceAtLeast(1)
                val level = score.score.roundToInt().coerceIn(0, levels.size - 1)
                JevScoreOutcome(
                    question = question,
                    score = score.score,
                    level = level,
                    normalized = (score.score / maxLevel).coerceIn(0.0, 1.0),
                    label = levels.getOrNull(level),
                    confidence = score.confidence,
                    probabilities = score.probabilities
                )
            }

            JevQuestionType.NOUL -> {
                val noul = answer as? JevNoulAnswer ?: throw answerTypeMismatch(question, answer)
                val threshold = question.effectiveThreshold
                JevNoulOutcome(question = question, value = noul.noul, threshold = threshold, yes = noul.noul >= threshold)
            }
        }
    }

    val byId = outcomes.associateBy { it.id }
    val compositeValues = LinkedHashMap<String, Double>()
    composites.forEach { composite ->
        var weighted = 0.0
        var totalWeight = 0.0
        composite.weights.forEach { (questionId, weight) ->
            val value = byId[questionId]?.normalizedValue ?: return@forEach
            weighted += weight * value
            totalWeight += weight
        }
        if (totalWeight > 0.0) compositeValues[composite.name] = weighted / totalWeight
    }
    return JevQuestionSetDecision(outcomes, compositeValues)
}

/**
 * Variables written for an evaluated question set, in [JevVariableNames.forQuestions]
 * order. A missing confidence / composite is written as "" so a stale value from an
 * earlier run of the same step is always overwritten.
 */
internal fun JevQuestionSetDecision.variables(): LinkedHashMap<String, String> {
    val result = LinkedHashMap<String, String>()
    outcomes.forEach { outcome ->
        when (outcome) {
            is JevChoiceOutcome -> {
                result[outcome.id] = outcome.value
                result[outcome.id + JevVariableNames.CONFIDENCE_SUFFIX] = outcome.confidence?.let(::formatJevNumber).orEmpty()
                result[outcome.id + JevVariableNames.PROBABILITIES_SUFFIX] =
                    jevProbabilitiesJson(outcome.optionNames, outcome.probabilities)
            }

            is JevScoreOutcome -> {
                result[outcome.id] = formatJevNumber(outcome.score)
                result[outcome.id + JevVariableNames.LEVEL_SUFFIX] = outcome.level.toString()
                result[outcome.id + JevVariableNames.NORMALIZED_SUFFIX] = formatJevNumber(outcome.normalized)
                result[outcome.id + JevVariableNames.CONFIDENCE_SUFFIX] = outcome.confidence?.let(::formatJevNumber).orEmpty()
                result[outcome.id + JevVariableNames.PROBABILITIES_SUFFIX] =
                    jevProbabilitiesJson(outcome.levelKeys, outcome.probabilities)
            }

            is JevNoulOutcome -> {
                result[outcome.id] = formatJevNumber(outcome.value)
                result[outcome.id + JevVariableNames.YES_SUFFIX] = outcome.yes.toString()
            }
        }
    }
    return result
}

/**
 * Error-fallback values for a question set (transient Jev failure): every derived
 * variable is "" except `<id>_yes` = "false". `""` never satisfies a numeric
 * condition (`x >= 0.8` / `x < 0.5` → false, see ConditionEvaluator).
 */
internal fun jevQuestionSetFallbackVariables(
    questions: List<JevQuestionConfig>,
    composites: List<JevComposite>
): LinkedHashMap<String, String> {
    val result = LinkedHashMap<String, String>()
    questions.forEach { question ->
        JevVariableNames.forQuestion(question.id, question.type.wireName).forEach { name -> result[name] = "" }
        if (question.type == JevQuestionType.NOUL) {
            result[question.id + JevVariableNames.YES_SUFFIX] = "false"
        }
    }
    composites.forEach { result[it.name] = "" }
    return result
}

internal fun JevQuestionSetDecision.compositeVariables(composites: List<JevComposite>): LinkedHashMap<String, String> {
    val result = LinkedHashMap<String, String>()
    composites.forEach { composite ->
        result[composite.name] = this.composites[composite.name]?.let(::formatJevNumber).orEmpty()
    }
    return result
}

/** Variables for a question set in the exact [JevVariableNames.forQuestions] order (questions, then composites). */
internal fun JevQuestionSetDecision.allVariables(composites: List<JevComposite>): LinkedHashMap<String, String> =
    variables().apply { putAll(compositeVariables(composites)) }

// ---------------------------------------------------------------------------
// Classifier decision (§4.3)
// ---------------------------------------------------------------------------

internal data class JevClassifierDecision(
    /** Final category (the Jev choice, or `default_category` on fallback). */
    val category: String,
    val rawChoice: String,
    val confidence: Double?,
    val probabilities: Map<String, Double>,
    /** Non-null ⇒ fell back to `default_category` ([JEV_FALLBACK_LOW_CONFIDENCE]). */
    val fallbackReason: String?,
    val questions: JevQuestionSetDecision
) {
    val fellBack: Boolean get() = fallbackReason != null
}

/**
 * Applies the classifier rules to a validated [response]: main answer with the
 * `min_confidence` gate (→ `default_category`, else fail), extra questions, composites.
 *
 * @throws WorkflowExecutionException for a below-threshold answer without a default.
 */
internal fun decideJevClassifier(stepName: String, config: ClassifierConfig, response: JevResponse): JevClassifierDecision {
    val jevConfig = requireNotNull(config.jev) { "classifier is not a Jev classifier" }
    val main = response.answers[config.outputVariable] as? JevChoiceAnswer
        ?: throw JevApiException(
            JevApiException.Kind.INVALID_RESPONSE, null,
            "TypeSafe API returned an invalid response: no choice answer for question '${config.outputVariable}'"
        )
    val minConfidence = jevConfig.minConfidence
    val below = minConfidence != null && isBelow(main.confidence, minConfidence)
    val category = if (below) {
        config.defaultCategory ?: throw WorkflowExecutionException(
            "Classifier step '$stepName': Jev confidence ${describeConfidence(main.confidence)} is below " +
                "min_confidence ${formatJevNumber(minConfidence)} and no default_category is set"
        )
    } else {
        main.choice
    }
    val questions = evaluateJevQuestionSet(stepName, jevConfig.questions, jevConfig.composites, response.answers)
    return JevClassifierDecision(
        category = category,
        rawChoice = main.choice,
        confidence = main.confidence,
        probabilities = main.probabilities,
        fallbackReason = if (below) JEV_FALLBACK_LOW_CONFIDENCE else null,
        questions = questions
    )
}

/**
 * Classifier variables in [ClassifierConfig.jevWrittenVariables] order. On a
 * low-confidence fallback `<out>` is the default while `_confidence` /
 * `_probabilities` keep the actual (informative) Jev values.
 */
internal fun JevClassifierDecision.variables(config: ClassifierConfig): LinkedHashMap<String, String> {
    val jevConfig = requireNotNull(config.jev)
    val out = config.outputVariable
    val result = LinkedHashMap<String, String>()
    result[out] = category
    result[out + JevVariableNames.CONFIDENCE_SUFFIX] = confidence?.let(::formatJevNumber).orEmpty()
    result[out + JevVariableNames.PROBABILITIES_SUFFIX] =
        jevProbabilitiesJson(config.categories.map { it.name }, probabilities)
    result.putAll(questions.allVariables(jevConfig.composites))
    return result
}

/**
 * Classifier error-fallback values (transient failure with `default_category`):
 * `<out>` = default, `<out>_confidence` = "0", `<out>_probabilities` = "{}",
 * every other derived variable "" except `<id>_yes` = "false".
 */
internal fun jevClassifierErrorFallbackVariables(config: ClassifierConfig, defaultCategory: String): LinkedHashMap<String, String> {
    val jevConfig = requireNotNull(config.jev)
    val out = config.outputVariable
    val result = LinkedHashMap<String, String>()
    result[out] = defaultCategory
    result[out + JevVariableNames.CONFIDENCE_SUFFIX] = "0"
    result[out + JevVariableNames.PROBABILITIES_SUFFIX] = "{}"
    result.putAll(jevQuestionSetFallbackVariables(jevConfig.questions, jevConfig.composites))
    return result
}

/**
 * Classifier step output. The first line `classification: <category>` is an
 * invariant used by resume hydration; `confidence:` only when the final category
 * is Jev's own answer.
 */
internal fun jevClassifierOutput(category: String, confidence: Double?): String = buildString {
    append("classification: ").append(category)
    if (confidence != null) append("\nconfidence: ").append(formatJevNumber(confidence))
}

// ---------------------------------------------------------------------------
// Decision payloads (§4.6) — event `detail`, parsed by the frontend
// ---------------------------------------------------------------------------

internal fun jevClassifierDecisionPayload(
    config: ClassifierConfig,
    model: String?,
    decision: JevClassifierDecision,
    usage: JevUsage?
): String {
    val jevConfig = requireNotNull(config.jev)
    fun build(includeProbabilities: Boolean) = buildJsonObject {
        put("engine", "jev")
        put("kind", "classifier")
        put("model", model)
        put("output_variable", config.outputVariable)
        put("category", decision.category)
        put("confidence", jevJsonNumber(decision.confidence))
        if (includeProbabilities) {
            put("probabilities", jevProbabilitiesObject(config.categories.map { it.name }, decision.probabilities))
        }
        put("min_confidence", jevJsonNumber(jevConfig.minConfidence))
        put("fell_back", decision.fellBack)
        put("fallback_reason", decision.fallbackReason)
        if (decision.fellBack) put("jev_choice", decision.rawChoice)
        put("answers", answersObject(decision.questions, includeProbabilities))
        put("composites", compositesObject(decision.questions))
        put("usage", usageObject(usage))
        if (!includeProbabilities) put("truncated", true)
    }
    return encodeCompactPayload(::build)
}

/** Payload of a classifier that fell back to `default_category` because the Jev call failed. */
internal fun jevClassifierErrorPayload(config: ClassifierConfig, model: String?, category: String, error: String): String {
    val jevConfig = requireNotNull(config.jev)
    val payload = buildJsonObject {
        put("engine", "jev")
        put("kind", "classifier")
        put("model", model)
        put("output_variable", config.outputVariable)
        put("category", category)
        put("confidence", JsonNull)
        put("probabilities", JsonObject(emptyMap()))
        put("min_confidence", jevJsonNumber(jevConfig.minConfidence))
        put("fell_back", true)
        put("fallback_reason", JEV_FALLBACK_ERROR)
        put("answers", JsonObject(emptyMap()))
        put("composites", JsonObject(emptyMap()))
        put("usage", JsonNull)
        put("error", error.take(500))
    }
    return payloadJson.encodeToString(JsonObject.serializer(), payload)
}

internal fun jevRepeatUntilDecisionPayload(
    iteration: Int,
    model: String?,
    decision: JevQuestionSetDecision,
    usage: JevUsage?
): String {
    fun build(includeProbabilities: Boolean) = buildJsonObject {
        put("engine", "jev")
        put("kind", "repeat_until")
        put("model", model)
        put("iteration", iteration)
        put("fell_back", false)
        put("fallback_reason", null as String?)
        put("answers", answersObject(decision, includeProbabilities))
        put("composites", compositesObject(decision))
        put("usage", usageObject(usage))
        if (!includeProbabilities) put("truncated", true)
    }
    return encodeCompactPayload(::build)
}

/** Payload of a repeat_until evaluation whose Jev call failed transiently (fallback values written). */
internal fun jevRepeatUntilErrorPayload(iteration: Int, model: String?, error: String): String {
    val payload = buildJsonObject {
        put("engine", "jev")
        put("kind", "repeat_until")
        put("model", model)
        put("iteration", iteration)
        put("fell_back", true)
        put("fallback_reason", JEV_FALLBACK_ERROR)
        put("answers", JsonObject(emptyMap()))
        put("composites", JsonObject(emptyMap()))
        put("usage", JsonNull)
        put("error", error.take(500))
    }
    return payloadJson.encodeToString(JsonObject.serializer(), payload)
}

private fun encodeCompactPayload(build: (includeProbabilities: Boolean) -> JsonObject): String {
    val full = payloadJson.encodeToString(JsonObject.serializer(), build(true))
    if (full.length <= JEV_PAYLOAD_SOFT_LIMIT_CHARS) return full
    // Large category / option sets: drop the probability maps (still available in the
    // `<x>_probabilities` variables) so the event detail survives the 8 KB compactor.
    return payloadJson.encodeToString(JsonObject.serializer(), build(false))
}

private fun answersObject(decision: JevQuestionSetDecision, includeProbabilities: Boolean): JsonObject = buildJsonObject {
    decision.outcomes.forEach { outcome ->
        put(outcome.id, buildJsonObject {
            put("type", outcome.question.type.wireName)
            when (outcome) {
                is JevChoiceOutcome -> {
                    put("value", outcome.value)
                    put("confidence", jevJsonNumber(outcome.confidence))
                    if (includeProbabilities) {
                        put("probabilities", jevProbabilitiesObject(outcome.optionNames, outcome.probabilities))
                    }
                    put("fell_back", outcome.fellBack)
                    if (outcome.fellBack) put("jev_choice", outcome.rawChoice)
                }

                is JevScoreOutcome -> {
                    put("score", jevJsonNumber(outcome.score))
                    put("level", outcome.level)
                    put("label", outcome.label)
                    put("normalized", jevJsonNumber(outcome.normalized))
                    put("confidence", jevJsonNumber(outcome.confidence))
                    if (includeProbabilities) {
                        put("probabilities", jevProbabilitiesObject(outcome.levelKeys, outcome.probabilities))
                    }
                }

                is JevNoulOutcome -> {
                    put("value", jevJsonNumber(outcome.value))
                    put("yes", outcome.yes)
                    put("threshold", jevJsonNumber(outcome.threshold))
                }
            }
        })
    }
}

private fun compositesObject(decision: JevQuestionSetDecision): JsonObject = buildJsonObject {
    decision.composites.forEach { (name, value) -> put(name, jevJsonNumber(value)) }
}

private fun usageObject(usage: JevUsage?): JsonElement = usage?.let {
    buildJsonObject {
        put("input_tokens", it.inputTokens)
        put("output_tokens", it.outputTokens)
    }
} ?: JsonNull

// ---------------------------------------------------------------------------
// repeat_until critique (§4.4)
// ---------------------------------------------------------------------------

/**
 * Deterministic English critique stored in `{{steps.<step>:evaluate.output}}`:
 * one line per question, weakest normalized dimension (score / noul) first,
 * then choice questions, then composites.
 */
internal fun buildJevCritique(
    iteration: Int,
    model: String,
    decision: JevQuestionSetDecision,
    composites: List<JevComposite>
): String = buildString {
    append("Jev evaluation (iteration ").append(iteration).append(", model ").append(model).append("):")
    val ranked = decision.outcomes.withIndex()
        .sortedWith(
            compareBy<IndexedValue<JevQuestionOutcome>> { it.value.normalizedValue == null }
                .thenBy { it.value.normalizedValue ?: 0.0 }
                .thenBy { it.index }
        )
        .map { it.value }
    ranked.forEach { outcome ->
        append("\n- ").append(outcome.id).append(": ")
        when (outcome) {
            is JevScoreOutcome -> {
                append("level ").append(outcome.level).append('/').append(outcome.levelCount - 1)
                outcome.label?.let { append(" \"").append(it).append('"') }
                append(" (score ").append(formatJevNumber(outcome.score))
                outcome.confidence?.let { append(", confidence ").append(formatJevNumber(it)) }
                append(')')
            }

            is JevNoulOutcome -> {
                append(if (outcome.yes) "yes" else "no").append(" (p=").append(formatJevNumber(outcome.value)).append(')')
            }

            is JevChoiceOutcome -> {
                append(outcome.value)
                if (outcome.fellBack) {
                    append(" (default; Jev chose ").append(outcome.rawChoice)
                    outcome.confidence?.let { append(" with confidence ").append(formatJevNumber(it)) }
                    append(" below min_confidence ").append(formatJevNumber(outcome.question.minConfidence ?: 0.0))
                    append(')')
                } else {
                    outcome.confidence?.let { append(" (confidence ").append(formatJevNumber(it)).append(')') }
                }
            }
        }
    }
    composites.forEach { composite ->
        val value = decision.composites[composite.name] ?: return@forEach
        append("\n- ").append(composite.name).append(" (composite): ").append(formatJevNumber(value))
    }
}

// ---------------------------------------------------------------------------
// Usage event (§4.5)
// ---------------------------------------------------------------------------

/**
 * The single token-bearing event per Jev HTTP call, shaped like the Koog
 * `llm_call_completed` event: detail `model=<id>, provider=TypeSafe; input=N, output=M`.
 * The `provider=TypeSafe` literal is what host metering keys on to price Jev
 * tokens separately — keep it exact.
 */
internal fun buildJevUsageEvent(response: JevResponse): AgentEvent {
    val usage = response.usage
    val inputTokens = usage?.inputTokens?.let(::clampTokenCount)
    val outputTokens = usage?.outputTokens?.let(::clampTokenCount)
    val totalTokens = usage?.let { clampTokenCount(it.inputTokens + it.outputTokens) }
    val modelInfo = "model=${response.model}, provider=$TYPESAFE_PROVIDER_DISPLAY_NAME"
    val detail = if (usage != null) "$modelInfo; input=${usage.inputTokens}, output=${usage.outputTokens}" else modelInfo
    return AgentEvent(
        type = "llm_call_completed",
        category = "llm",
        subCategory = "call",
        summary = "✅ 模型调用完成 · ${response.model}（$TYPESAFE_PROVIDER_DISPLAY_NAME）",
        detail = detail,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun clampTokenCount(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/** A missing confidence counts as below any threshold. */
private fun isBelow(confidence: Double?, minConfidence: Double): Boolean = confidence == null || confidence < minConfidence

private fun describeConfidence(confidence: Double?): String = confidence?.let(::formatJevNumber) ?: "(none)"

private fun answerTypeMismatch(question: JevQuestionConfig, answer: JevAnswer): JevApiException =
    JevApiException(
        JevApiException.Kind.INVALID_RESPONSE, null,
        "TypeSafe API returned an invalid response: answer for question '${question.id}' has the wrong type " +
            "(expected '${question.type.wireName}', got '${answer::class.simpleName}')"
    )
