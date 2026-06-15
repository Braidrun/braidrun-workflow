package com.fartech.agents.workflow

import com.fartech.agents.commons.extractJsonFromResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Structured output schema for periodic report AI commentary.
 *
 * The model returns this typed object; WorkflowExecutor serializes it into the
 * legacy ai_commentary_parts.json array consumed by the existing assembler.
 */
@Serializable
data class AiCommentaryStructuredOutput(
    @SerialName("investment_decisions")
    val investmentDecisions: List<AiCommentaryInvestmentDecision> = emptyList(),

    @SerialName("summaries")
    val summaries: List<AiCommentaryTextItems> = emptyList(),

    @SerialName("followups")
    val followups: List<AiCommentaryTextItems> = emptyList(),

    @SerialName("row_commentaries")
    val rowCommentaries: List<AiCommentaryRow> = emptyList(),

    @SerialName("focus_commentaries")
    val focusCommentaries: List<AiCommentaryFocus> = emptyList(),

    @SerialName("analysis_notes")
    val analysisNotes: List<String> = emptyList()
) {
    fun isBusinessEmpty(): Boolean =
        investmentDecisions.isEmpty() &&
            summaries.isEmpty() &&
            followups.isEmpty() &&
            rowCommentaries.isEmpty() &&
            focusCommentaries.isEmpty()

    fun hasRequiredBusinessCoverage(): Boolean {
        if (isBusinessEmpty()) return false
        val summaryItemCount = summaries.sumOf { textItemCount(it.items) }
        val followupItemCount = followups.sumOf { textItemCount(it.items) }
        val hasDecision = investmentDecisions.any {
            it.status.isNotBlank() || it.structure.isNotBlank() ||
                it.reason.isNotBlank() || it.action.isNotBlank()
        }
        val hasProductLevelCommentary = rowCommentaries.isNotEmpty() || focusCommentaries.isNotEmpty()
        return summaryItemCount > 0 && followupItemCount > 0 && (hasDecision || hasProductLevelCommentary)
    }
}

@Serializable
data class AiCommentaryInvestmentDecision(
    @SerialName("sheet_name")
    val sheetName: String = "",
    val status: String = "",
    val structure: String = "",
    val reason: String = "",
    val action: String = ""
)

@Serializable
data class AiCommentaryTextItems(
    @SerialName("sheet_name")
    val sheetName: String = "",
    val items: List<String> = emptyList()
)

@Serializable
data class AiCommentaryRow(
    @SerialName("sheet_name")
    val sheetName: String = "",
    val product: String = "",
    val commentary: String = "",
    val suggestion: String = ""
)

@Serializable
data class AiCommentaryFocus(
    @SerialName("sheet_name")
    val sheetName: String = "",
    val product: String = "",
    val commentary: String = "",
    val reason: String = ""
)

/**
 * Few-shot example wired into the manual-mode JSON schema for the
 * `ai_commentary_parts` structured output. Used as `structureExamples` so the
 * model (especially DeepSeek, which goes through StructuredRequest.Manual)
 * sees a concrete instance instead of inferring shape from the schema alone.
 *
 * Keep this short and representative — every example token costs ~per-call.
 */
val AI_COMMENTARY_PARTS_EXAMPLE: AiCommentaryStructuredOutput =
    AiCommentaryStructuredOutput(
        investmentDecisions = listOf(
            AiCommentaryInvestmentDecision(
                sheetName = "汇总日报",
                status = "保预算验证",
                structure = "周付占比偏高，回收滞后",
                reason = "1. 新销售回收率仍待观察",
                action = "1. 维持预算并核查退款来源"
            )
        ),
        summaries = listOf(
            AiCommentaryTextItems(
                sheetName = "汇总日报",
                items = listOf(
                    "1. 整体收入环比上升但利润承压",
                    "2. 周付占比偏高，长尾回收待跟踪"
                )
            )
        ),
        followups = listOf(
            AiCommentaryTextItems(
                sheetName = "汇总日报",
                items = listOf(
                    "1. 复盘低利润产品的退款来源",
                    "2. 收紧高 CPA 渠道的预算"
                )
            )
        ),
        rowCommentaries = listOf(
            AiCommentaryRow(
                sheetName = "汇总日报",
                product = "示例产品 A",
                commentary = "利润率偏低，主要受退款抬升",
                suggestion = "先排查退款来源并暂停低质量素材"
            )
        ),
        focusCommentaries = listOf(
            AiCommentaryFocus(
                sheetName = "汇总日报",
                product = "示例产品 B",
                commentary = "当日收入最高",
                reason = "作为预算基准产品"
            )
        ),
        analysisNotes = listOf("示例：周付样本不足时降低置信度")
    )

private val aiCommentaryLooseJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

internal fun parseAiCommentaryStructuredOutputLoose(raw: String): AiCommentaryStructuredOutput? {
    return aiCommentaryJsonCandidates(raw)
        .asSequence()
        .mapNotNull { parseAiCommentaryStructuredJson(it) }
        .filterNot { it.isBusinessEmpty() }
        .maxByOrNull { it.businessScore() }
}

internal fun AiCommentaryStructuredOutput.businessScore(): Int =
    investmentDecisions.size * 4 +
        summaries.size * 4 +
        followups.size * 4 +
        rowCommentaries.size +
        focusCommentaries.size +
        summaries.sumOf { textItemCount(it.items) } +
        followups.sumOf { textItemCount(it.items) }

private fun parseAiCommentaryStructuredJson(jsonText: String): AiCommentaryStructuredOutput? {
    val root = runCatching { aiCommentaryLooseJson.parseToJsonElement(jsonText) }.getOrNull() ?: return null
    val output = when (root) {
        is JsonArray -> parseAiCommentaryLegacyParts(root)
        is JsonObject -> parseAiCommentaryObject(root)
        else -> return null
    }
    return output
}

private fun aiCommentaryJsonCandidates(raw: String): List<String> {
    val candidates = linkedSetOf<String>()
    fun addCandidate(candidate: String?) {
        val text = candidate?.trim().orEmpty()
        if (text.startsWith("{") || text.startsWith("[")) {
            candidates += text
            repairInnerQuotes(text).takeIf { it != text }?.let { candidates += it }
        }
    }

    raw.trim().takeIf { it.startsWith("{") || it.startsWith("[") }?.let(::addCandidate)
    extractJsonFromResponse(raw)?.let(::addCandidate)

    Regex("""```(?:json)?\s*\n?([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
        .findAll(raw)
        .map { it.groupValues[1].trim() }
        .filter { it.startsWith("{") || it.startsWith("[") }
        .forEach(::addCandidate)

    extractBalancedJsonTexts(raw).forEach(::addCandidate)
    return candidates.toList()
}

private fun repairInnerQuotes(text: String): String {
    val source = text
    val result = StringBuilder(source.length)
    var inString = false
    var escaped = false
    source.forEachIndexed { index, char ->
        if (inString) {
            if (escaped) {
                result.append(char)
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\') {
                result.append(char)
                escaped = true
                return@forEachIndexed
            }
            if (char == '"') {
                var nextIndex = index + 1
                while (nextIndex < source.length && source[nextIndex].isWhitespace()) nextIndex++
                val nextChar = source.getOrNull(nextIndex)
                if (nextChar != null && nextChar !in setOf(':', ',', ']', '}')) {
                    result.append('”')
                    return@forEachIndexed
                }
                inString = false
                result.append(char)
                return@forEachIndexed
            }
            result.append(char)
            return@forEachIndexed
        }
        if (char == '"') inString = true
        result.append(char)
    }
    return result.toString()
}

private fun extractBalancedJsonTexts(raw: String): List<String> {
    val results = linkedSetOf<String>()
    raw.indices
        .filter { raw[it] == '{' || raw[it] == '[' }
        .forEach { start ->
            balancedJsonFrom(raw, start)?.let(results::add)
        }
    return results.toList()
}

private fun balancedJsonFrom(raw: String, start: Int): String? {
    val opening = raw.getOrNull(start) ?: return null
    val closing = when (opening) {
        '{' -> '}'
        '[' -> ']'
        else -> return null
    }
    var depth = 0
    var inString = false
    var escape = false
    for (i in start until raw.length) {
        val ch = raw[i]
        if (escape) {
            escape = false
            continue
        }
        if (ch == '\\' && inString) {
            escape = true
            continue
        }
        if (ch == '"') {
            inString = !inString
            continue
        }
        if (inString) continue
        if (ch == opening) depth++
        if (ch == closing) depth--
        if (depth == 0) return raw.substring(start, i + 1)
    }
    return null
}

private fun parseAiCommentaryObject(root: JsonObject): AiCommentaryStructuredOutput {
    firstArrayValue(root, "parts", "items", "data", "commentary_parts", "ai_commentary_parts")
        ?.let { return parseAiCommentaryLegacyParts(it) }

    root["sheets"]?.asArray()?.let { sheets ->
        return parseAiCommentarySheets(sheets)
    }

    val output = AiCommentaryStructuredOutput(
        investmentDecisions = valuesForKeys(root, "investment_decisions", "investmentDecisions")
            .mapNotNull { parseInvestmentDecision(it) } +
            valuesForKeys(root, "investment_decision", "investmentDecision")
                .mapNotNull { parseInvestmentDecision(it) },
        summaries = valuesForKeys(root, "summaries", "summary_commentaries", "summaryCommentaries")
            .mapNotNull { parseTextItems(it) } +
            valuesForKeys(root, "summary_commentary", "summary", "overall_commentary")
                .mapNotNull { parseTextItems(it, defaultKey = "summary_commentary") },
        followups = valuesForKeys(root, "followups", "followup_suggestions", "followupSuggestions")
            .mapNotNull { parseTextItems(it) } +
            valuesForKeys(root, "followup_suggestion", "suggestion", "suggestions")
                .mapNotNull { parseTextItems(it, defaultKey = "followup_suggestion") },
        rowCommentaries = valuesForKeys(root, "row_commentaries", "rowCommentaries", "row_commentary")
            .mapNotNull { parseRowCommentary(it) },
        focusCommentaries = valuesForKeys(root, "focus_commentaries", "focusCommentaries", "focus_commentary")
            .mapNotNull { parseFocusCommentary(it) },
        analysisNotes = textList(root["analysis_notes"] ?: root["analysisNotes"] ?: root["notes"])
    )

    return if (output.isBusinessEmpty() && root["type"] != null) {
        parseAiCommentaryLegacyParts(JsonArray(listOf(root)))
    } else {
        output
    }
}

private fun parseAiCommentaryLegacyParts(parts: JsonArray): AiCommentaryStructuredOutput {
    val investmentDecisions = mutableListOf<AiCommentaryInvestmentDecision>()
    val summaries = mutableListOf<AiCommentaryTextItems>()
    val followups = mutableListOf<AiCommentaryTextItems>()
    val rows = mutableListOf<AiCommentaryRow>()
    val focus = mutableListOf<AiCommentaryFocus>()
    val notes = mutableListOf<String>()

    parts.forEach { element ->
        val obj = element.asObject() ?: return@forEach
        val kind = textValue(obj["type"] ?: obj["kind"] ?: obj["category"]).lowercase()

        when (kind) {
            "investment", "investment_decision", "investment_decision_commentary" ->
                parseInvestmentDecision(obj)?.let(investmentDecisions::add)
            "summary", "summary_commentary", "overall_commentary" ->
                parseTextItems(obj, defaultKey = "summary_commentary")?.let(summaries::add)
            "followup", "followup_suggestion", "suggestion", "suggestions" ->
                parseTextItems(obj, defaultKey = "followup_suggestion")?.let(followups::add)
            "row", "row_commentary", "product_commentary" ->
                parseRowCommentary(obj)?.let(rows::add)
            "focus", "focus_commentary", "telegram_focus" ->
                parseFocusCommentary(obj)?.let(focus::add)
        }

        obj["investment_decision"]?.let { parseInvestmentDecision(it)?.let(investmentDecisions::add) }
        obj["summary_commentary"]?.let { parseTextItems(it, obj.sheetName(), "summary_commentary")?.let(summaries::add) }
        obj["followup_suggestion"]?.let { parseTextItems(it, obj.sheetName(), "followup_suggestion")?.let(followups::add) }
        obj["row_commentary"]?.let { parseRowCommentary(it, obj.sheetName())?.let(rows::add) }
        obj["focus_commentary"]?.let { parseFocusCommentary(it, obj.sheetName())?.let(focus::add) }
        notes += textList(obj["analysis_notes"] ?: obj["analysisNotes"] ?: obj["notes"])
    }

    return AiCommentaryStructuredOutput(
        investmentDecisions = investmentDecisions,
        summaries = summaries,
        followups = followups,
        rowCommentaries = rows,
        focusCommentaries = focus,
        analysisNotes = notes
    )
}

private fun parseAiCommentarySheets(sheets: JsonArray): AiCommentaryStructuredOutput {
    val investmentDecisions = mutableListOf<AiCommentaryInvestmentDecision>()
    val summaries = mutableListOf<AiCommentaryTextItems>()
    val followups = mutableListOf<AiCommentaryTextItems>()
    val rows = mutableListOf<AiCommentaryRow>()
    val focus = mutableListOf<AiCommentaryFocus>()
    val notes = mutableListOf<String>()

    sheets.forEach { sheetElement ->
        val sheet = sheetElement.asObject() ?: return@forEach
        val sheetName = sheet.sheetName()
        sheet["investment_decision"]?.let { parseInvestmentDecision(it, sheetName)?.let(investmentDecisions::add) }
        sheet["investmentDecision"]?.let { parseInvestmentDecision(it, sheetName)?.let(investmentDecisions::add) }
        for (key in listOf("summary_commentary", "summary", "summaries")) {
            sheet[key]?.let { parseTextItems(it, sheetName, key)?.let(summaries::add) }
        }
        for (key in listOf("followup_suggestion", "followups", "suggestions")) {
            sheet[key]?.let { parseTextItems(it, sheetName, key)?.let(followups::add) }
        }
        valuesForKeys(sheet, "row_commentaries", "rowCommentaries", "rows").mapNotNull {
            parseRowCommentary(it, sheetName)
        }.forEach(rows::add)
        valuesForKeys(sheet, "focus_commentaries", "focusCommentaries", "focus").mapNotNull {
            parseFocusCommentary(it, sheetName)
        }.forEach(focus::add)
        notes += textList(sheet["analysis_notes"] ?: sheet["analysisNotes"] ?: sheet["notes"])
    }

    return AiCommentaryStructuredOutput(investmentDecisions, summaries, followups, rows, focus, notes)
}

private fun parseInvestmentDecision(element: JsonElement, fallbackSheetName: String = ""): AiCommentaryInvestmentDecision? {
    val obj = element.asObject() ?: return null
    val decision = AiCommentaryInvestmentDecision(
        sheetName = obj.sheetName(fallbackSheetName),
        status = textValue(obj["status"]),
        structure = textValue(obj["structure"]),
        reason = textValue(obj["reason"]),
        action = textValue(obj["action"])
    )
    return decision.takeIf {
        it.sheetName.isNotBlank() || it.status.isNotBlank() || it.structure.isNotBlank() ||
            it.reason.isNotBlank() || it.action.isNotBlank()
    }
}

private fun parseTextItems(
    element: JsonElement,
    fallbackSheetName: String = "",
    defaultKey: String = "items"
): AiCommentaryTextItems? {
    val obj = element.asObject()
    val sheetName = obj?.sheetName(fallbackSheetName) ?: fallbackSheetName
    val items = when {
        obj == null -> textList(element)
        obj["items"] != null -> textList(obj["items"])
        obj[defaultKey] != null -> textList(obj[defaultKey])
        else -> textList(obj["text"] ?: obj["content"] ?: obj["commentary"] ?: obj["summary"] ?: obj["reason"])
    }
    return AiCommentaryTextItems(sheetName = sheetName, items = items).takeIf { it.items.isNotEmpty() }
}

private fun parseRowCommentary(element: JsonElement, fallbackSheetName: String = ""): AiCommentaryRow? {
    val obj = element.asObject() ?: return null
    val row = AiCommentaryRow(
        sheetName = obj.sheetName(fallbackSheetName),
        product = textValue(obj["product"] ?: obj["app_name"] ?: obj["name"]),
        commentary = textValue(obj["commentary"] ?: obj["text"] ?: obj["content"]),
        suggestion = textValue(obj["suggestion"] ?: obj["action"] ?: obj["advice"])
    )
    return row.takeIf { it.product.isNotBlank() && (it.commentary.isNotBlank() || it.suggestion.isNotBlank()) }
}

private fun parseFocusCommentary(element: JsonElement, fallbackSheetName: String = ""): AiCommentaryFocus? {
    val obj = element.asObject() ?: return null
    val focus = AiCommentaryFocus(
        sheetName = obj.sheetName(fallbackSheetName),
        product = textValue(obj["product"] ?: obj["app_name"] ?: obj["name"]),
        commentary = textValue(obj["commentary"] ?: obj["text"] ?: obj["content"]),
        reason = textValue(obj["reason"] ?: obj["why"])
    )
    return focus.takeIf { it.product.isNotBlank() && (it.commentary.isNotBlank() || it.reason.isNotBlank()) }
}

internal fun AiCommentaryStructuredOutput.toAiCommentaryPartsJson(): JsonArray {
    val output = withRequiredCommentaryFallbacks()
    val parts = mutableListOf<JsonElement>()

    output.investmentDecisions.forEach { decision ->
        val obj = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("investment_decision")
        )
        putString(obj, "sheet_name", decision.sheetName)
        putString(obj, "status", decision.status)
        putString(obj, "structure", decision.structure)
        putString(obj, "reason", decision.reason)
        putString(obj, "action", decision.action)
        if (obj.size > 1) parts += JsonObject(obj)
    }

    output.summaries.forEach { summary ->
        val obj = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("summary_commentary")
        )
        putString(obj, "sheet_name", summary.sheetName)
        putStringArray(obj, "items", summary.items)
        if (obj.containsKey("items")) parts += JsonObject(obj)
    }

    output.followups.forEach { followup ->
        val obj = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("followup_suggestion")
        )
        putString(obj, "sheet_name", followup.sheetName)
        putStringArray(obj, "items", followup.items)
        if (obj.containsKey("items")) parts += JsonObject(obj)
    }

    output.rowCommentaries.forEach { row ->
        val obj = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("row_commentary")
        )
        putString(obj, "sheet_name", row.sheetName)
        putString(obj, "product", row.product)
        putString(obj, "commentary", row.commentary)
        putString(obj, "suggestion", row.suggestion)
        if (obj.containsKey("product") && (obj.containsKey("commentary") || obj.containsKey("suggestion"))) {
            parts += JsonObject(obj)
        }
    }

    output.focusCommentaries.forEach { focus ->
        val obj = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("focus_commentary")
        )
        putString(obj, "sheet_name", focus.sheetName)
        putString(obj, "product", focus.product)
        putString(obj, "commentary", focus.commentary)
        putString(obj, "reason", focus.reason)
        if (obj.containsKey("product") && (obj.containsKey("commentary") || obj.containsKey("reason"))) {
            parts += JsonObject(obj)
        }
    }

    return JsonArray(parts)
}

private fun AiCommentaryStructuredOutput.withRequiredCommentaryFallbacks(): AiCommentaryStructuredOutput {
    if (isBusinessEmpty()) return this

    val sheetNames = linkedSetOf<String>()
    investmentDecisions.mapTo(sheetNames) { it.sheetName.trim() }
    summaries.mapTo(sheetNames) { it.sheetName.trim() }
    followups.mapTo(sheetNames) { it.sheetName.trim() }
    rowCommentaries.mapTo(sheetNames) { it.sheetName.trim() }
    focusCommentaries.mapTo(sheetNames) { it.sheetName.trim() }
    if (sheetNames.isEmpty()) sheetNames += ""

    val completedSummaries = summaries.toMutableList()
    val completedFollowups = followups.toMutableList()

    fun List<AiCommentaryTextItems>.hasItemsFor(sheetName: String): Boolean =
        any { it.sheetName.trim() == sheetName && it.items.any(String::isNotBlank) }

    sheetNames.forEach { sheetName ->
        if (!completedSummaries.hasItemsFor(sheetName)) {
            completedSummaries += AiCommentaryTextItems(
                sheetName = sheetName,
                items = fallbackSummaryItems(sheetName)
            )
        }
        if (!completedFollowups.hasItemsFor(sheetName)) {
            completedFollowups += AiCommentaryTextItems(
                sheetName = sheetName,
                items = fallbackFollowupItems(sheetName)
            )
        }
    }

    return copy(summaries = completedSummaries, followups = completedFollowups)
}

private fun AiCommentaryStructuredOutput.fallbackSummaryItems(sheetName: String): List<String> {
    val items = mutableListOf<String>()
    investmentDecisions
        .filter { it.sheetName.trim() == sheetName }
        .forEach { decision ->
            appendUnique(items, decision.reason)
            appendUnique(items, decision.action)
            appendUnique(items, decision.structure)
            appendUnique(items, decision.status)
        }
    focusCommentaries
        .filter { it.sheetName.trim() == sheetName }
        .forEach {
            appendUnique(items, it.commentary)
            appendUnique(items, it.reason)
        }
    rowCommentaries
        .filter { it.sheetName.trim() == sheetName }
        .forEach { appendUnique(items, it.commentary) }
    followups
        .filter { it.sheetName.trim() == sheetName }
        .flatMap { it.items }
        .forEach { appendUnique(items, "后续动作指向：$it") }

    return items.take(4).ifEmpty {
        listOf("本次 AI 解读只返回了部分结构化字段，系统已根据可用字段补齐整体解读；请结合报表核心指标复核后执行。")
    }
}

private fun AiCommentaryStructuredOutput.fallbackFollowupItems(sheetName: String): List<String> {
    val items = mutableListOf<String>()
    investmentDecisions
        .filter { it.sheetName.trim() == sheetName }
        .forEach {
            appendUnique(items, it.action)
            appendUnique(items, it.reason)
        }
    rowCommentaries
        .filter { it.sheetName.trim() == sheetName }
        .forEach { appendUnique(items, it.suggestion) }
    summaries
        .filter { it.sheetName.trim() == sheetName }
        .flatMap { it.items }
        .forEach { appendUnique(items, "围绕该整体判断继续跟进：$it") }

    return items.take(4).ifEmpty {
        listOf("继续跟进本期报告中的核心变化，优先复核异常波动产品，并在下一周期对投放预算、回收率和退款率做闭环复盘。")
    }
}

private fun appendUnique(items: MutableList<String>, value: String) {
    val text = value.trim()
    if (text.isNotEmpty() && text !in items) items += text
}

private fun putString(target: MutableMap<String, JsonElement>, key: String, value: String?) {
    val text = value?.trim().orEmpty()
    if (text.isNotEmpty()) target[key] = JsonPrimitive(text)
}

private fun putStringArray(target: MutableMap<String, JsonElement>, key: String, values: List<String>) {
    val normalized = values.mapNotNull { value ->
        value.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
    }
    if (normalized.isNotEmpty()) target[key] = JsonArray(normalized)
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonElement.asArray(): JsonArray? = this as? JsonArray

private fun JsonObject.sheetName(fallback: String = ""): String =
    textValue(this["sheet_name"] ?: this["sheetName"] ?: this["sheet"] ?: this["name"]).ifBlank { fallback }

private fun valuesForKeys(obj: JsonObject, vararg keys: String): List<JsonElement> =
    keys.flatMap { key ->
        when (val value = obj[key]) {
            is JsonArray -> value.toList()
            null -> emptyList()
            else -> listOf(value)
        }
    }

private fun firstArrayValue(obj: JsonObject, vararg keys: String): JsonArray? =
    keys.firstNotNullOfOrNull { obj[it] as? JsonArray }

private fun textList(value: JsonElement?): List<String> =
    when (value) {
        is JsonArray -> value.mapNotNull { textValue(it).takeIf(String::isNotBlank) }
        null -> emptyList()
        else -> listOfNotNull(textValue(value).takeIf(String::isNotBlank))
    }

private fun textValue(value: JsonElement?): String =
    when (value) {
        is JsonPrimitive -> value.contentOrNull?.trim().orEmpty()
        is JsonArray -> value.mapNotNull { textValue(it).takeIf(String::isNotBlank) }.joinToString("\n")
        is JsonObject -> value.toString()
        null -> ""
    }

private fun textItemCount(items: List<String>): Int =
    items.count { it.isNotBlank() }
