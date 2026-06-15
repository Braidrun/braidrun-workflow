package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class AsaCampaignData(
    val campaignId: String,
    val name: String,
    val status: String,
    val budget: Double,
    val spend: Double,
    val impressions: Long,
    val clicks: Long,
    val installs: Long,
    val ctr: Double,
    val cpc: Double,
    val cpi: Double
)

@Serializable
data class AsaKeywordData(
    val keywordId: String,
    val keyword: String,
    val matchType: String,
    val bid: Double,
    val impressions: Long,
    val clicks: Long,
    val installs: Long,
    val ctr: Double,
    val cpc: Double,
    val cpi: Double
)

@Serializable
data class AsaReportData(
    val date: String,
    val campaigns: List<AsaCampaignData>,
    val keywords: List<AsaKeywordData>,
    val totalSpend: Double,
    val totalImpressions: Long,
    val totalClicks: Long,
    val totalInstalls: Long,
    val avgCtr: Double,
    val avgCpc: Double,
    val avgCpi: Double
)

@LLMDescription("Apple Search Ads 专业分析工具集")
class AsaAnalysisTools : ToolSet {

    @Tool
    @LLMDescription("分析 ASA 广告活动表现数据，提供优化建议")
    fun analyzeCampaignPerformance(
        @LLMDescription("广告活动数据 JSON") campaignData: String,
        @LLMDescription("分析维度：performance, budget, keywords") analysisType: String = "performance"
    ): String {
        return try {
            val data = Json.decodeFromString<List<AsaCampaignData>>(campaignData)

            when (analysisType.lowercase()) {
                "performance" -> analyzePerformance(data)
                "budget" -> analyzeBudget(data)
                "keywords" -> analyzeKeywords(data)
                else -> "未知的分析类型: $analysisType"
            }
        } catch (e: Exception) {
            logger.warn(e) { "ASA 分析数据时出错" }
            "分析数据时出错: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("生成 ASA 关键词优化建议")
    fun generateKeywordOptimizationSuggestions(
        @LLMDescription("关键词数据 JSON") keywordData: String,
        @LLMDescription("优化目标：ctr, cpc, installs") targetMetric: String = "ctr"
    ): String {
        return try {
            val data = Json.decodeFromString<List<AsaKeywordData>>(keywordData)

            buildString {
                appendLine("🔍 关键词优化分析报告")
                appendLine("=".repeat(50))
                appendLine("优化目标: ${targetMetric.uppercase()}")
                appendLine("分析关键词数量: ${data.size}")
                appendLine()

                when (targetMetric.lowercase()) {
                    "ctr" -> generateCTROptimization(data)
                    "cpc" -> generateCPCOptimization(data)
                    "installs" -> generateInstallsOptimization(data)
                    else -> appendLine("未知的优化目标: $targetMetric")
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "ASA 生成关键词优化建议时出错" }
            "生成关键词优化建议时出错: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("创建 ASA 预算分配建议")
    fun createBudgetAllocationPlan(
        @LLMDescription("活动数据 JSON") campaignData: String,
        @LLMDescription("总预算金额") totalBudget: Double,
        @LLMDescription("分配策略：roi, performance, balanced") strategy: String = "roi"
    ): String {
        return try {
            val data = Json.decodeFromString<List<AsaCampaignData>>(campaignData)

            buildString {
                appendLine("💰 ASA 预算分配计划")
                appendLine("=".repeat(50))
                appendLine("总预算: $${String.format("%.2f", totalBudget)}")
                appendLine("分配策略: ${strategy.uppercase()}")
                appendLine("活动数量: ${data.size}")
                appendLine()

                when (strategy.lowercase()) {
                    "roi" -> generateROIBasedAllocation(data, totalBudget)
                    "performance" -> generatePerformanceBasedAllocation(data, totalBudget)
                    "balanced" -> generateBalancedAllocation(data, totalBudget)
                    else -> appendLine("未知的分配策略: $strategy")
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "ASA 创建预算分配计划时出错" }
            "创建预算分配计划时出错: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("生成 ASA 竞争分析报告")
    fun generateCompetitorAnalysis(
        @LLMDescription("应用包名") appId: String,
        @LLMDescription("目标市场") market: String = "US",
        @LLMDescription("分析深度：basic, detailed") depth: String = "basic"
    ): String {
        return buildString {
            appendLine("🏆 ASA 竞争分析报告")
            appendLine("=".repeat(50))
            appendLine("应用ID: $appId")
            appendLine("目标市场: $market")
            appendLine("分析深度: ${depth.uppercase()}")
            appendLine()

            appendLine("📊 竞争环境概览:")
            appendLine("- 使用 braidrun-mcp searchads_call 通过应用 ID 获取 ASA 竞争数据")
            appendLine("- 通过 app_call 查询应用元数据与 ASA adamId 的匹配关系")
            appendLine("- 使用 analytics_call 进行竞争对手深度分析")
            appendLine("- 基于 braidrun 平台数据调整投放策略和关键词覆盖")
        }
    }


    // 私有辅助方法
    private fun analyzePerformance(data: List<AsaCampaignData>): String {
        val avgCtr = data.map { it.ctr }.average()
        val avgCpc = data.map { it.cpc }.average()
        val avgCpi = data.map { it.cpi }.average()

        return buildString {
            appendLine("📊 活动表现分析:")
            appendLine("- 平均 CTR: ${String.format("%.2f", avgCtr)}%")
            appendLine("- 平均 CPC: $${String.format("%.2f", avgCpc)}")
            appendLine("- 平均 CPI: $${String.format("%.2f", avgCpi)}")
            appendLine()

            val topPerformer = data.maxByOrNull { it.ctr }
            val lowPerformer = data.minByOrNull { it.ctr }

            appendLine("🏆 最佳表现活动: ${topPerformer?.name} (CTR: ${String.format("%.2f", topPerformer?.ctr)}%)")
            appendLine("⚠️ 需要优化活动: ${lowPerformer?.name} (CTR: ${String.format("%.2f", lowPerformer?.ctr)}%)")
        }
    }

    private fun analyzeBudget(data: List<AsaCampaignData>): String {
        val totalBudget = data.sumOf { it.budget }
        val totalSpend = data.sumOf { it.spend }
        val budgetUtilization = (totalSpend / totalBudget) * 100

        return buildString {
            appendLine("💰 预算分析:")
            appendLine("- 总预算: $${String.format("%.2f", totalBudget)}")
            appendLine("- 已花费: $${String.format("%.2f", totalSpend)}")
            appendLine("- 预算利用率: ${String.format("%.1f", budgetUtilization)}%")
            appendLine()

            if (budgetUtilization > 90) {
                appendLine("⚠️ 预算利用率较高，建议增加预算或优化出价")
            } else if (budgetUtilization < 50) {
                appendLine("💡 预算利用率较低，建议提高出价或扩大关键词覆盖")
            } else {
                appendLine("✅ 预算利用率合理")
            }
        }
    }

    private fun analyzeKeywords(data: List<AsaCampaignData>): String {
        return buildString {
            appendLine("🔍 关键词分析:")
            appendLine("- 使用 braidrun-mcp searchads_call 通过应用 ID 获取详细关键词数据")
            appendLine("- 通过 analytics_call 分析高 CTR 关键词的共同特征")
            appendLine("- 使用 optimization_call 识别低表现关键词的优化机会")
            appendLine("- 基于 braidrun 平台应用数据寻找新的关键词机会")
        }
    }

    private fun generateCTROptimization(data: List<AsaKeywordData>): String {
        val highCTR = data.filter { it.ctr > data.map { k -> k.ctr }.average() }
        val lowCTR = data.filter { it.ctr < data.map { k -> k.ctr }.average() }

        return buildString {
            appendLine("📈 CTR 优化建议:")
            appendLine("- 高 CTR 关键词 (${highCTR.size}个): 提高出价，增加展示量")
            appendLine("- 低 CTR 关键词 (${lowCTR.size}个): 优化广告文案，提高相关性")
            appendLine("- 建议暂停 CTR < 1% 的关键词")
        }
    }

    private fun generateCPCOptimization(data: List<AsaKeywordData>): String {
        val avgCpc = data.map { it.cpc }.average()
        val highCpc = data.filter { it.cpc > avgCpc }

        return buildString {
            appendLine("💰 CPC 优化建议:")
            appendLine("- 平均 CPC: $${String.format("%.2f", avgCpc)}")
            appendLine("- 高 CPC 关键词 (${highCpc.size}个): 降低出价或暂停")
            appendLine("- 建议关注质量得分，提高广告相关性")
        }
    }

    private fun generateInstallsOptimization(data: List<AsaKeywordData>): String {
        val highInstalls = data.filter { it.installs > data.map { k -> k.installs }.average() }

        return buildString {
            appendLine("📱 安装量优化建议:")
            appendLine("- 高安装量关键词 (${highInstalls.size}个): 增加预算分配")
            appendLine("- 建议优化应用页面，提高转化率")
            appendLine("- 关注关键词与应用的相关性")
        }
    }

    private fun generateROIBasedAllocation(data: List<AsaCampaignData>, totalBudget: Double): String {
        val totalInstalls = data.sumOf { it.installs }
        if (totalInstalls <= 0L) {
            return "📊 ROI 导向分配: 无安装数据，无法按 ROI 分配预算"
        }

        return buildString {
            appendLine("📊 ROI 导向分配:")
            data.sortedByDescending { if (it.spend > 0.0) it.installs / it.spend else 0.0 }.forEach { campaign ->
                // Long/Long would be integer division — every campaign would allocate $0.00.
                val allocation = (campaign.installs.toDouble() / totalInstalls) * totalBudget
                appendLine("- ${campaign.name}: $${String.format("%.2f", allocation)}")
            }
        }
    }

    private fun generatePerformanceBasedAllocation(data: List<AsaCampaignData>, totalBudget: Double): String {
        return buildString {
            appendLine("🎯 表现导向分配:")
            data.sortedByDescending { it.ctr }.forEach { campaign ->
                val allocation = (campaign.ctr / data.sumOf { it.ctr }) * totalBudget
                appendLine("- ${campaign.name}: $${String.format("%.2f", allocation)}")
            }
        }
    }

    private fun generateBalancedAllocation(data: List<AsaCampaignData>, totalBudget: Double): String {
        val allocationPerCampaign = totalBudget / data.size

        return buildString {
            appendLine("⚖️ 平衡分配:")
            data.forEach { campaign ->
                appendLine("- ${campaign.name}: $${String.format("%.2f", allocationPerCampaign)}")
            }
        }
    }

    private fun predictSpend(data: List<AsaReportData>, days: Int): Double {
        val avgDailySpend = data.map { it.totalSpend }.average()
        return avgDailySpend * days
    }

    private fun predictInstalls(data: List<AsaReportData>, days: Int): Long {
        val avgDailyInstalls = data.map { it.totalInstalls }.average()
        return (avgDailyInstalls * days).toLong()
    }

    private fun predictCTR(data: List<AsaReportData>, days: Int): Double {
        return data.map { it.avgCtr }.average()
    }
}
