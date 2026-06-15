package com.fartech.agents.agents

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResumeReviewResult(
    val meta: ResumeReviewMeta = ResumeReviewMeta(),
    val scores: ResumeScores = ResumeScores(),
    val summary: String = "",
    val strengths: List<ResumeStrength> = emptyList(),
    val improvements: List<ResumeImprovement> = emptyList(),
    @SerialName("next_steps")
    val nextSteps: List<String> = emptyList(),
    @SerialName("keywords_to_add")
    val keywordsToAdd: List<String> = emptyList(),
    @SerialName("red_flags")
    val redFlags: List<String> = emptyList()
)

@Serializable
data class ResumeReviewMeta(
    @SerialName("format_version")
    val formatVersion: String = "resume_review_v1",
    @SerialName("language_used")
    val languageUsed: String = "zh-CN",
    @SerialName("detected_resume_language")
    val detectedResumeLanguage: String = "unknown"
)

@Serializable
data class ResumeScores(
    val overall: ResumeScoreOverall = ResumeScoreOverall(),
    val experience: ResumeScoreDetail = ResumeScoreDetail(),
    val skills: ResumeScoreDetail = ResumeScoreDetail(),
    val impact: ResumeScoreDetail = ResumeScoreDetail(),
    val presentation: ResumeScoreDetail = ResumeScoreDetail(),
    val ats: ResumeScoreDetail = ResumeScoreDetail()
)

@Serializable
data class ResumeScoreOverall(
    val value: Int = 0,
    val level: String = "C",
    val rationale: String = ""
)

@Serializable
data class ResumeScoreDetail(
    val value: Int = 0,
    val rationale: String = ""
)

@Serializable
data class ResumeStrength(
    val title: String = "",
    val detail: String = ""
)

@Serializable
data class ResumeImprovement(
    val title: String = "",
    val problem: String = "",
    val impact: String = "medium",
    val actions: List<String> = emptyList()
)

val ExampleResumeReviewZh = ResumeReviewResult(
    meta = ResumeReviewMeta(
        formatVersion = "resume_review_v1",
        languageUsed = "zh-CN",
        detectedResumeLanguage = "zh-CN"
    ),
    scores = ResumeScores(
        overall = ResumeScoreOverall(value = 78, level = "B", rationale = "核心运营成果明确，但 ATS 关键词覆盖不足"),
        experience = ResumeScoreDetail(value = 80, rationale = "5 年互联网运营经验，量化指标清晰"),
        skills = ResumeScoreDetail(value = 74, rationale = "工具链齐全，但缺少数据科学类技能"),
        impact = ResumeScoreDetail(value = 79, rationale = "带来 18% 增长，复盘逻辑成熟"),
        presentation = ResumeScoreDetail(value = 72, rationale = "排版整齐但缺少空白与层级对比"),
        ats = ResumeScoreDetail(value = 68, rationale = "关键词密度不足，动词重复")
    ),
    summary = "候选人具备中大型互联网运营经验，能稳定交付增长结果，若加强数据分析关键词与排版即可冲击更高等级岗位。",
    strengths = listOf(
        ResumeStrength("指标量化完整", "每段经历均给出 DAU、GMV 等指标，便于招聘方快速定位产出。"),
        ResumeStrength("跨团队协同案例", "明确描述与产品、技术共建活动，提高管理岗位匹配度。"),
        ResumeStrength("增长逻辑清晰", "拆解 AARRR 漏斗与实验数据，体现业务洞察。")
    ),
    improvements = listOf(
        ResumeImprovement(
            title = "补充 ATS 关键词",
            problem = "招聘 JD 中的 BI、Looker、SQL 等关键词未覆盖。",
            impact = "high",
            actions = listOf(
                "在技能模块新增 BI/SQL/Looker 熟练度描述",
                "在项目经历中嵌入“通过 SQL/Looker 分析”等动词",
                "将“数据分析”单列为能力模块，列出工具栈"
            )
        ),
        ResumeImprovement(
            title = "强调领导力层次",
            problem = "团队规模、汇报关系描述不足，难以体现管理幅度。",
            impact = "medium",
            actions = listOf(
                "在经历标题后补充“带领 8 人团队/向 VP 汇报”等",
                "增加 OKR 制定、复盘节奏等管理动作",
                "描述跨 BU 推动阻力及化解方法"
            )
        ),
        ResumeImprovement(
            title = "排版层级优化",
            problem = "正文密度高且字体统一，阅读疲劳。",
            impact = "low",
            actions = listOf(
                "为每段经历加入 1 行成就总结",
                "使用粗体突出关键指标/动词",
                "减少冗长句式，控制在 24 字以内"
            )
        )
    ),
    nextSteps = listOf(
        "收集目标公司 JD，梳理缺口技能并补充到技能矩阵",
        "按 STAR 结构重写 3 个关键项目，突出“行动—结果”",
        "在 LinkedIn/拉勾等渠道同步更新，确保文案一致"
    ),
    keywordsToAdd = listOf("SQL", "Looker", "A/B testing", "Retention cohort"),
    redFlags = listOf("最后一段经历缺少起止时间，建议补全以避免背景调查疑虑")
)

val ExampleResumeReviewEn = ExampleResumeReviewZh.copy(
    meta = ResumeReviewMeta(
        formatVersion = "resume_review_v1",
        languageUsed = "en",
        detectedResumeLanguage = "en"
    ),
    summary = "Product marketing professional with quantified growth wins; polish ATS keywords and leadership evidence to compete for senior roles.",
    strengths = listOf(
        ResumeStrength(
            "Metric-driven narratives",
            "Each role lists KPIs and outcome deltas, which speeds up recruiter screening."
        ),
        ResumeStrength(
            "Cross-functional influence",
            "Clear description of how you aligned product, sales, and growth teams."
        ),
        ResumeStrength(
            "Experimentation mindset",
            "Highlights hypothesis, action, and measured uplift for each campaign."
        )
    ),
    nextSteps = listOf(
        "Add BI/SQL stack to the top skills block to pass ATS filters",
        "Rephrase top two projects using STAR with quantified impact",
        "Update LinkedIn headline to mirror the refreshed elevator pitch"
    ),
    keywordsToAdd = listOf("Funnel analytics", "Lifecycle automation", "SQL", "Experiment design"),
    redFlags = listOf("Current role title differs from LinkedIn profile; keep naming consistent.")
)

