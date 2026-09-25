package com.fartech.agents.jev

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// ============================================================================
// TypeSafe Jev ("System One") wire format
// ============================================================================
//
// `POST {base}/v1/systemone` takes a `state` plus a map of typed questions and
// answers every question in ONE call. Questions and answers are discriminated by
// their `type` field (`choice` / `score` / `noul`), which is exactly kotlinx
// polymorphism with `classDiscriminator = "type"` over the sealed bases below.
//
// Always (de)serialize these with [JevJson]: the engine's shared HttpAccess client
// registers its own lenient Json (encodeDefaults=false) plus Jackson, and relying
// on content negotiation there could silently change the wire shape.

/** A typed Jev question. `instructions` may be a string, an object or an array. */
@Serializable
sealed interface JevQuestion {
    val instructions: JsonElement
}

/** Pick one option. `criteria` maps option → description (`null` = no extra detail); ≤ 255 options. */
@Serializable
@SerialName("choice")
data class JevChoiceQuestion(
    override val instructions: JsonElement,
    val criteria: Map<String, JsonElement?>,
) : JevQuestion

/** Rate along an ordered rubric. `criteria` = level descriptions, level 0 first (2..10 levels). */
@Serializable
@SerialName("score")
data class JevScoreQuestion(
    override val instructions: JsonElement,
    val criteria: List<JsonElement>,
) : JevQuestion

/** Yes/no question; answered with the probability of "yes". `criteria` is optional. */
@Serializable
@SerialName("noul")
data class JevNoulQuestion(
    override val instructions: JsonElement,
    val criteria: JevNoulCriteria? = null,
) : JevQuestion

/** Optional descriptions of what a yes (`true`) and a no (`false`) mean for a noul question. */
@Serializable
data class JevNoulCriteria(
    @SerialName("true") val whenTrue: JsonElement? = null,
    @SerialName("false") val whenFalse: JsonElement? = null,
)

/**
 * Request body. Question ids (map keys) are chosen by the caller, echoed back in
 * [JevResponse.answers], and never used for inference.
 */
@Serializable
data class JevRequest(
    val model: String,
    val state: JsonElement,
    val questions: Map<String, JevQuestion>,
)

/** A typed Jev answer; its `type` always matches the question it answers. */
@Serializable
sealed interface JevAnswer

/** The highest-probability option plus the full distribution (option → probability). */
@Serializable
@SerialName("choice")
data class JevChoiceAnswer(
    val choice: String,
    val probabilities: Map<String, Double> = emptyMap(),
    val confidence: Double? = null,
) : JevAnswer

/**
 * Probability-weighted level (may land between levels, 0..n-1). `legend` and
 * `probabilities` are keyed by the level index as a string ("0", "1", ...).
 */
@Serializable
@SerialName("score")
data class JevScoreAnswer(
    val score: Double,
    val legend: Map<String, String> = emptyMap(),
    val probabilities: Map<String, Double> = emptyMap(),
    val confidence: Double? = null,
) : JevAnswer

/** Probability that the answer is yes (0..1). Noul answers carry no confidence. */
@Serializable
@SerialName("noul")
data class JevNoulAnswer(
    val noul: Double,
) : JevAnswer

@Serializable
data class JevUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
)

/** Response body. `model` is the concrete version that answered (e.g. `jev-1.13.0`). */
@Serializable
data class JevResponse(
    val model: String,
    val answers: Map<String, JevAnswer>,
    val usage: JevUsage? = null,
)

/**
 * Dedicated Json for the Jev wire format: emits the `type` discriminator for every
 * question, drops absent optional fields (`explicitNulls = false`) and ignores
 * response fields added by newer API versions.
 */
val JevJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** The wire `type` name of a question (`choice` / `score` / `noul`). */
val JevQuestion.wireType: String
    get() = when (this) {
        is JevChoiceQuestion -> "choice"
        is JevScoreQuestion -> "score"
        is JevNoulQuestion -> "noul"
    }

/** The wire `type` name of an answer (`choice` / `score` / `noul`). */
val JevAnswer.wireType: String
    get() = when (this) {
        is JevChoiceAnswer -> "choice"
        is JevScoreAnswer -> "score"
        is JevNoulAnswer -> "noul"
    }

/**
 * Checks a decoded [JevResponse] against the [request] that produced it: every
 * requested question id must be answered, each answer's type must match its
 * question's type, a choice must be one of the question's options, and every
 * number must be finite. Extra, unrequested answers are ignored.
 *
 * @throws JevApiException with kind [JevApiException.Kind.INVALID_RESPONSE] on the first violation.
 */
fun JevResponse.validateAgainst(request: JevRequest, requestId: String? = null) {
    fun invalid(reason: String): Nothing = throw JevApiException(
        kind = JevApiException.Kind.INVALID_RESPONSE,
        status = null,
        message = "TypeSafe API returned an invalid response: $reason" +
            (requestId?.let { " (request id: $it)" } ?: ""),
        requestId = requestId,
    )

    if (model.isBlank()) invalid("missing model")
    for ((id, question) in request.questions) {
        val answer = answers[id] ?: invalid("no answer for question '$id'")
        if (answer.wireType != question.wireType) {
            invalid("answer for question '$id' has type '${answer.wireType}', expected '${question.wireType}'")
        }
        when (answer) {
            is JevChoiceAnswer -> {
                val options = (question as JevChoiceQuestion).criteria.keys
                if (answer.choice !in options) invalid("choice for question '$id' is not one of its options")
                if (answer.probabilities.values.any { !it.isFinite() }) invalid("non-finite probability for question '$id'")
                if (answer.confidence?.isFinite() == false) invalid("non-finite confidence for question '$id'")
            }
            is JevScoreAnswer -> {
                if (!answer.score.isFinite()) invalid("non-finite score for question '$id'")
                if (answer.probabilities.values.any { !it.isFinite() }) invalid("non-finite probability for question '$id'")
                if (answer.confidence?.isFinite() == false) invalid("non-finite confidence for question '$id'")
            }
            is JevNoulAnswer -> {
                if (!answer.noul.isFinite()) invalid("non-finite value for question '$id'")
            }
        }
    }
}
