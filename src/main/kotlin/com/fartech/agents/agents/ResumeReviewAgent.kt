package com.fartech.agents.agents

import com.fartech.agents.commons.buildAndRunStructureAgent
import com.fartech.agents.commons.determineLLMModel
import com.fartech.agents.commons.firstLLMConfig
import com.fartech.ftapp2.commonsKt.*
import kotlinx.serialization.json.Json
import java.util.*

/**
 * AI 简历检查器 Agent
 * - 负责从用户简历中提取关键信息
 * - 输出标准化的评分与改进建议
 * - 根据语言选项自动切换反馈语言，默认为自动检测
 */
suspend fun resumeReviewAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>
) {
    printlnColor(AnsiColor.CYAN, "🚀 启动 AI 简历检查器代理...")

    val languagePreference = resolveLanguagePreference(
        parameters.parameter("resume_feedback_language", parameters.parameter("language", "auto"))
    )
    val formatVersion = parameters.parameter("resume_output_format", "resume_review_v1")
    val scoringRubric = parameters.parameter("resume_scoring_rubric", DEFAULT_SCORING_RUBRIC)
    val formatSpecification = buildFormatSpecification(formatVersion)
    val resumePayload = parameters.parameter("resume_payload", parameters.parameter("prompt", "").trim())

    if (resumePayload.isBlank()) {
        printlnColor(AnsiColor.RED, "❌ 未检测到简历正文，请通过 prompt 或 resume_payload 传入文本。")
        return
    }

    val systemPrompt = parameters.parameter(
        "system_prompt",
        """
        你是 AIAgent (ResumeReviewAgent)，一名资深的简历评审与职业发展顾问。
        目标：阅读用户提供的简历（可包含中文、英文或双语内容），给出结构化的评分、亮点、改进建议与下一步行动方案。

        语言要求：
        ${languagePreference.instruction}
        meta.language_used 字段必须填写你最终使用的 BCP-47 语言代码；如果是自动检测，请把检测结果同步到 meta.detected_resume_language。

        评分原则（可覆盖但需包含默认指标）：
        $scoringRubric

        输出格式：
        $formatSpecification

        重要规则：
        1. 只输出合法 JSON，禁止添加 Markdown、额外解释或代码框。
        2. scores.*.value 取整，范围 0-100；level 仅能为 S/A/B/C/D。
        3. strengths 与 improvements 至少 3 条，不超过 5 条；每条 actions 控制在 1-3 条。
        4. next_steps、keywords_to_add、red_flags 为空时返回空数组 []，而不是 null。
        5. 优先根据候选岗位与经历匹配度给出建议，明确指出 ATS（Applicant Tracking System）潜在风险。
        6. 如果用户的输入与简历无关，请礼貌提示需要真实简历内容，并在 JSON 中将各个列表置空、score 设为 0。
        """.trimIndent()
    )

    val llmModel = determineLLMModel(parameters.firstLLMConfig())
    val (_, structuredResult) = buildAndRunStructureAgent(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt,
        input = resumePayload,
        emptyValue = ResumeReviewResult(),
        llmModel = llmModel,
        examples = listOf(ExampleResumeReviewZh, ExampleResumeReviewEn)
    )

    val normalizedResult = finalizeReviewResult(structuredResult ?: ResumeReviewResult(), languagePreference, formatVersion)
    val jsonOutput = resumeReviewJson.encodeToString(normalizedResult)
    printlnColor(AnsiColor.GREEN, jsonOutput)
}

private fun buildFormatSpecification(version: String): String {
    val normalized = version.lowercase(Locale.ROOT)
    if (normalized == "resume_review_v1") {
        return DEFAULT_RESPONSE_FORMAT
    }
    // 允许外部直接通过 systemPrompt 覆盖格式
    return version
}

private data class LanguagePreference(
    val code: String,
    val instruction: String
)

private fun resolveLanguagePreference(rawValue: String?): LanguagePreference {
    val languageCode = normalizeLanguageCode(rawValue)
    val instruction = when (languageCode) {
        "auto" -> "自动检测简历语言；若检测失败或文本含多语言，则改用简体中文返回，同时在 meta.detected_resume_language 中写入 \"unknown\"。"
        "zh-CN" -> "所有自然语言描述（summary、strengths、improvements、next_steps、keywords_to_add、red_flags）必须使用简体中文。"
        "en" -> "所有自然语言描述必须使用英语（en）。"
        "af" -> "所有自然语言描述必须使用 Afrikaans（af）。"
        "az" -> "所有自然语言描述必须使用 Azerbaijani（az）。"
        "id" -> "所有自然语言描述必须使用 Bahasa Indonesia（id）。"
        else -> "所有自然语言描述必须使用 $languageCode。"
    }
    return LanguagePreference(languageCode, instruction)
}

private fun normalizeLanguageCode(raw: String?): String {
    if (raw.isNullOrBlank()) return "auto"
    val normalized = raw.trim()
        .lowercase(Locale.ROOT)
        .replace("（", "(")
        .replace("）", ")")
    LANGUAGE_ALIAS[normalized]?.let { return it }

    val sanitized = normalized.replace('_', '-')
    val codeRegex = Regex("^[a-z]{2,3}(-[a-z0-9]{2,4})?\$")
    return if (codeRegex.matches(sanitized)) sanitized else "auto"
}

private val LANGUAGE_ALIAS = mapOf(
    "auto" to "auto",
    "默认" to "auto",
    "automatic" to "auto",
    "自动" to "auto",
    "zh" to "zh-CN",
    "zh-cn" to "zh-CN",
    "zh_hans" to "zh-CN",
    "zh-hans" to "zh-CN",
    "简体中文" to "zh-CN",
    "中文" to "zh-CN",
    "中文(简体)" to "zh-CN",
    "中文（简体）" to "zh-CN",
    "en" to "en",
    "english" to "en",
    "英语" to "en",
    "af" to "af",
    "afrikaans" to "af",
    "南非荷兰语" to "af",
    "az" to "az",
    "azerbaijan" to "az",
    "azerbaijani" to "az",
    "azerbaycan dili" to "az",
    "阿塞拜疆语" to "az",
    "id" to "id",
    "indonesian" to "id",
    "bahasa indonesia" to "id",
    "印尼语" to "id"
)

private val resumeReviewJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

private fun finalizeReviewResult(
    raw: ResumeReviewResult,
    preference: LanguagePreference,
    formatVersion: String
): ResumeReviewResult {
    val sanitizedMeta = finalizeMeta(raw.meta, preference, formatVersion)
    val sanitizedScores = sanitizeScores(raw.scores)
    val cleanedStrengths = enforceStrengthAndImprovementBounds(
        entries = raw.strengths.filter { it.title.isNotBlank() || it.detail.isNotBlank() },
        minSize = 3,
        maxSize = 5
    ) { idx ->
        ResumeStrength(
            title = preference.localized("待补充亮点 ${idx + 1}", "Add highlight ${idx + 1}"),
            detail = preference.localized("请补充一个量化亮点。", "Add one more quantified strength.")
        )
    }
    val cleanedImprovements = enforceStrengthAndImprovementBounds(
        entries = raw.improvements.map { sanitizeImprovement(it, preference) }.filter {
            it.title.isNotBlank() || it.problem.isNotBlank()
        },
        minSize = 3,
        maxSize = 5
    ) { idx ->
        ResumeImprovement(
            title = preference.localized("待补充改进点 ${idx + 1}", "Add improvement ${idx + 1}"),
            problem = preference.localized("请补充一个可量化的不足。", "Provide another tangible gap."),
            impact = "medium",
            actions = listOf(
                preference.localized(
                    "写出 1-2 条可执行措施来弥补该不足。",
                    "List 1-2 concrete actions to fix this gap."
                )
            )
        )
    }

    val nextSteps = raw.nextSteps.filter { it.isNotBlank() }
    val keywords = raw.keywordsToAdd.filter { it.isNotBlank() }
    val redFlags = raw.redFlags.filter { it.isNotBlank() }

    return raw.copy(
        meta = sanitizedMeta,
        scores = sanitizedScores,
        strengths = cleanedStrengths,
        improvements = cleanedImprovements,
        nextSteps = nextSteps,
        keywordsToAdd = keywords,
        redFlags = redFlags
    )
}

private fun finalizeMeta(
    meta: ResumeReviewMeta,
    preference: LanguagePreference,
    formatVersion: String
): ResumeReviewMeta {
    val desiredLanguage = when (preference.code) {
        "auto" -> meta.languageUsed.ifBlank { meta.detectedResumeLanguage.ifBlank { "zh-CN" } }
        else -> preference.code
    }
    val detectedLanguage = when (preference.code) {
        "auto" -> meta.detectedResumeLanguage.ifBlank { "unknown" }
        else -> meta.detectedResumeLanguage.ifBlank { preference.code }
    }
    return meta.copy(
        formatVersion = meta.formatVersion.ifBlank { formatVersion },
        languageUsed = desiredLanguage,
        detectedResumeLanguage = detectedLanguage
    )
}

private fun sanitizeScores(scores: ResumeScores): ResumeScores {
    return ResumeScores(
        overall = sanitizeOverall(scores.overall),
        experience = sanitizeDetailScore(scores.experience),
        skills = sanitizeDetailScore(scores.skills),
        impact = sanitizeDetailScore(scores.impact),
        presentation = sanitizeDetailScore(scores.presentation),
        ats = sanitizeDetailScore(scores.ats)
    )
}

private fun sanitizeOverall(score: ResumeScoreOverall): ResumeScoreOverall {
    val clampedValue = score.value.coerceIn(0, 100)
    val normalizedLevel = score.level.uppercase(Locale.ROOT)
        .takeIf { it in SCORE_LEVELS } ?: deriveLevel(clampedValue)
    val rationale = score.rationale.ifBlank { "Provide a short rationale for the overall score." }
    return score.copy(value = clampedValue, level = normalizedLevel, rationale = rationale)
}

private fun sanitizeDetailScore(score: ResumeScoreDetail): ResumeScoreDetail {
    val clampedValue = score.value.coerceIn(0, 100)
    val rationale = score.rationale.ifBlank { "Add a concise explanation for this dimension." }
    return score.copy(value = clampedValue, rationale = rationale)
}

private fun deriveLevel(value: Int): String = when {
    value >= 90 -> "S"
    value >= 80 -> "A"
    value >= 70 -> "B"
    value >= 60 -> "C"
    else -> "D"
}

private fun <T> enforceStrengthAndImprovementBounds(
    entries: List<T>,
    minSize: Int,
    maxSize: Int,
    placeholderFactory: (Int) -> T
): List<T> {
    val trimmed = entries.take(maxSize)
    if (trimmed.size >= minSize) return trimmed
    val placeholdersNeeded = minSize - trimmed.size
    val placeholders = (0 until placeholdersNeeded).map(placeholderFactory)
    return (trimmed + placeholders).take(maxSize)
}

private fun sanitizeImprovement(
    improvement: ResumeImprovement,
    preference: LanguagePreference
): ResumeImprovement {
    val normalizedImpact = improvement.impact.lowercase(Locale.ROOT)
        .takeIf { it in IMPACT_LEVELS } ?: "medium"
    val normalizedActions = improvement.actions.filter { it.isNotBlank() }
        .ifEmpty {
            listOf(
                preference.localized(
                    "补充可衡量的行动步骤，例如“梳理数据指标并补充到技能模块”。",
                    "Add a measurable action item, e.g., \"audit metrics and add to skills section\"."
                )
            )
        }
    val title = improvement.title.ifBlank {
        preference.localized("完善改进标题", "Clarify improvement focus")
    }
    val problem = improvement.problem.ifBlank {
        preference.localized("补充此改进点要解决的具体问题。", "Describe the specific gap you want to fix.")
    }
    return improvement.copy(
        title = title,
        problem = problem,
        impact = normalizedImpact,
        actions = normalizedActions
    )
}

private fun LanguagePreference.localized(zh: String, en: String): String = when (code) {
    "zh-CN", "auto" -> zh
    else -> en
}

private val SCORE_LEVELS = setOf("S", "A", "B", "C", "D")
private val IMPACT_LEVELS = setOf("high", "medium", "low")

private val DEFAULT_SCORING_RUBRIC = """
1) 结构清晰度（20%）：模块划分是否完备（个人信息、技能、经历、教育、项目），时间线是否清晰，排版是否一致。
2) 经验与影响力（25%）：是否强调结果、指标和业务价值；使用 STAR（Situation-Task-Action-Result）叙事。
3) 技能匹配度（20%）：核心技能是否与目标岗位契合，是否提供熟练度及应用场景。
4) ATS 友好度（20%）：关键词覆盖、动词多样性、避免图片/表格等不可解析元素。
5) 商业洞察与领导力（15%）：体现战略思考、跨团队协作、指标看板或提效案例。
""".trimIndent()

private val DEFAULT_RESPONSE_FORMAT = """
必须返回以下 JSON 结构（所有 key 均为必填）：
{
  "meta": {
    "format_version": "resume_review_v1",
    "language_used": "<你输出时使用的 BCP-47 语言代码，例如 zh-CN>",
    "detected_resume_language": "<自动检测到的简历语言代码，无法判断时填 \"unknown\">"
  },
  "scores": {
    "overall": {"value": 0-100, "level": "S|A|B|C|D", "rationale": "1-2 句解释"},
    "experience": {"value": 0-100, "rationale": "围绕经历深度与结果"},
    "skills": {"value": 0-100, "rationale": "围绕技能匹配度"},
    "impact": {"value": 0-100, "rationale": "围绕成果与业务影响"},
    "presentation": {"value": 0-100, "rationale": "围绕结构与排版"},
    "ats": {"value": 0-100, "rationale": "围绕 ATS 关键词/格式友好度"}
  },
  "summary": "2-3 句总览，点出求职定位与整体评价",
  "strengths": [
    {"title": "亮点一", "detail": "具体说明亮点带来的价值"},
    {"title": "亮点二", "detail": "…"}
  ],
  "improvements": [
    {
      "title": "改进点一",
      "problem": "指出存在的问题或缺失的信息",
      "impact": "high|medium|low",
      "actions": ["具体操作 1", "具体操作 2"]
    }
  ],
  "next_steps": [
    "下一步行动 1",
    "下一步行动 2"
  ],
  "keywords_to_add": [
    "建议补充的关键词 1",
    "建议补充的关键词 2"
  ],
  "red_flags": [
    "潜在风险或不一致的描述（若无风险返回空数组）"
  ]
}
""".trimIndent()

