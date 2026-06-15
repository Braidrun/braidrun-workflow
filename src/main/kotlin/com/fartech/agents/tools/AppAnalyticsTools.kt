package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import mu.KotlinLogging

private val appAnalyticsLogger = KotlinLogging.logger {}

/**
 * 应用数据分析工具集
 * 
 * 提供统一的数据分析接口，基于 braidrun-web 的 FastComputer 实现。
 * 通过一个统一接口和结构化的参数，实现所有统计分析功能。
 * 
 * 主要功能：
 * 1. 获取应用列表 - 查看用户有哪些应用
 * 2. 统一数据分析 - 通过参数控制实现所有统计功能（利润、用户、订阅等）
 */
@LLMDescription("应用数据分析工具集，提供应用列表查询和统一的多维度数据分析功能")
class AppAnalyticsTools : ToolSet {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // ============================================
    // 1. 获取应用列表
    // ============================================

    @Tool
    @LLMDescription("获取用户的应用列表，包含应用的基本信息")
    fun getAppList(
        @LLMDescription("平台类型：iOS, Android, 或留空返回所有平台")
        platform: String? = null,

        @LLMDescription("是否启用分页")
        paging: Boolean = true,

        @LLMDescription("返回记录数限制，最大100")
        limit: Int = 20,

        @LLMDescription("跳过的记录数")
        skip: Int = 0,

        @LLMDescription("排序字段：name, createdAt, updatedAt")
        sort: String? = null,

        @LLMDescription("排序顺序：asc 或 desc")
        sortOrder: String? = "desc"
    ): String {
        return try {
            val request = GetAppListRequest(
                platform = platform,
                paging = paging,
                limit = limit.coerceIn(1, 100),
                skip = skip.coerceAtLeast(0),
                sort = sort,
                sortOrder = sortOrder
            )

            json.encodeToString(
                ToolInvocationResult(
                    tool = "getAppList",
                    request = json.encodeToJsonElement(request),
                    status = "pending",
                    message = "需要调用后端 API: /api/apps/list"
                )
            )
        } catch (e: Exception) {
            appAnalyticsLogger.warn(e) { "获取应用列表时发生错误" }
            createErrorResult("getAppList", "获取应用列表时发生错误: ${e.message}")
        }
    }

    // ============================================
    // 2. 统一数据分析接口
    // ============================================

    @Tool
    @LLMDescription(
        """
        统一的数据分析接口，基于 FastComputer 实现所有统计功能。
        
        核心功能：
        - 利润分析：收入、成本、利润趋势、税后收入
        - 用户分析：活跃用户、新增用户、付费用户、流失率
        - 订阅分析：订阅数、续订率、试用转化率、订阅类型分布
        - 退款分析：退款金额、退款率、退款用户、退款周期
        - 收入分析：MRR、ARR、ARPU、ARPPU、LTV
        
        通过 metrics 参数指定需要的指标，通过 groupBy 参数指定分组维度。
        所有指标会自动计算，无需手动指定依赖指标。
    """
    )
    fun analyzeAppData(
        @LLMDescription("应用ID列表，必填，至少一个")
        appIds: List<String>,

        @LLMDescription("开始时间戳（毫秒），必须小于结束时间")
        startDate: Long,

        @LLMDescription("结束时间戳（毫秒），必须大于开始时间")
        endDate: Long,

        @LLMDescription(
            """
            需要分析的指标列表，支持以下指标（可多选）：
            
            【收入指标】
            - sales: 总销售额
            - newSales: 新用户销售额
            - renewal: 续订收入
            - afterTaxIncome: 税后收入
            - annualSubscriptionRevenue: 年订阅收入
            - weeklySubscriptionRevenue: 周订阅收入
            
            【退款指标】
            - refund: 退款金额
            - afterTaxRefund: 税后退款
            - refundRate: 退款率
            - refundAmountRate: 退款金额率
            - avgRefundDuration: 平均退款时长
            
            【用户指标】
            - activeUserCount: 活跃用户数
            - newUsers: 新增用户数
            - paidUsers: 付费用户数
            - newSalesUsers: 新购用户数
            - renewalUsers: 续订用户数
            - trialUsers: 试用用户数
            - trialPaidUsers: 试用转付费用户数
            - trialOnlyUsers: 仅试用用户数
            - activeSubscriptionUsers: 活跃订阅用户数
            - activeRenewalUsers: 活跃续订用户数
            - expiredUsers: 过期用户数
            - gracePeriodUsers: 宽限期用户数
            - payFailedUsers: 支付失败用户数
            - unsubscribeRefundUsers: 取消订阅退款用户数
            - disableRenewalUsers: 关闭自动续订用户数
            - refundRequestUsers: 退款请求用户数
            - refundReplyUsers: 退款回复用户数
            
            【订阅类型指标】
            - annualSubscriptionCount: 年订阅数量
            - weeklySubscriptionCount: 周订阅数量
            - inAppAnnualSubscriptionCount: 应用内年订阅数量
            - guidePageAnnualSubscriptionCount: 引导页年订阅数量
            - annualSubscriptionRate: 年订阅率
            - weeklySubscriptionRate: 周订阅率
            
            【复合指标】（自动计算，基于其他指标）
            - MRR: 月经常性收入
            - ARR: 年经常性收入
            - ARPU: 平均每用户收入
            - ARPPU: 平均每付费用户收入
            - LTV: 用户生命周期价值
            - payRate: 付费率
            - renewalRate: 续订率
            - trialRate: 试用率
            - trialConversionRate: 试用转化率
            - unsubscribeRate: 取消订阅率
            - trialCancellationRate: 试用取消率
            - averageConversionTime: 平均转化时间
            - numberOfPurchasesPerUser: 每用户购买次数
            - refundRequestRate: 退款请求率
            - refundRequestReplyRate: 退款回复率
            
            默认值：["sales", "activeUserCount", "newUsers", "paidUsers"]
        """
        )
        metrics: List<String> = listOf("sales", "activeUserCount", "newUsers", "paidUsers"),

        @LLMDescription(
            """
            数据分组维度，支持以下维度（可多选）：
            - total: 总计（不分组，返回汇总数据）
            - app: 按应用分组
            - date: 按日期分组（按天）
            - country: 按国家分组
            - city: 按城市分组
            
            可以组合使用，如：["total", "date"] 表示同时返回总计和按日期的数据。
            默认值：["total", "date"]
        """
        )
        groupBy: List<String> = listOf("total", "date"),

        @LLMDescription(
            """
            时区，支持标准时区名称，如：
            - UTC: 协调世界时
            - Asia/Shanghai: 中国标准时间
            - America/New_York: 美国东部时间
            - Europe/London: 英国时间
            默认：UTC
        """
        )
        timeZone: String = "UTC",

        @LLMDescription(
            """
            是否精确统计活跃用户：
            - true: 按用户创建时间统计（更精确，但计算慢）
            - false: 按订单时间统计（更快，但可能有偏差）
            默认：false
        """
        )
        isMeticulous: Boolean = false,

        @LLMDescription("是否包含沙盒环境的测试数据，默认：false")
        includeSandbox: Boolean = false,

        @LLMDescription(
            """
            是否启用销售模式：
            - true: 只统计首次购买、试用开始、重新订阅等销售事件
            - false: 统计所有订单事件
            默认：false
        """
        )
        salesMode: Boolean = false,

        @LLMDescription(
            """
            是否启用回溯模式：
            - true: 统计历史某个时间点的数据状态（需配合 backInTimeEnd 使用）
            - false: 统计时间范围内的实际发生数据
            默认：false
        """
        )
        backInTime: Boolean = false,

        @LLMDescription("回溯模式的结束时间戳（毫秒），仅在 backInTime=true 时有效")
        backInTimeEnd: Long? = null,

        @LLMDescription(
            """
            应用的苹果税率映射，用于计算税后收入。
            格式：{"appId1": 0.15, "appId2": 0.30}
            - 0.15 表示 15% 税率（小型企业计划）
            - 0.30 表示 30% 税率（标准税率）
            默认：{}（空映射）
        """
        )
        appAppleTaxJson: String = "{}",

        @LLMDescription(
            """
            汇率映射，用于统一货币计算。
            格式：{"USD": 1.0, "CNY": 7.2, "EUR": 0.92}
            所有金额会按此汇率转换为基准货币（通常是 USD）。
            默认：{}（不进行汇率转换）
        """
        )
        exchangeRateJson: String = "{}"
    ): String {
        return try {
            // 参数验证
            if (appIds.isEmpty()) {
                return createErrorResult("analyzeAppData", "应用ID列表不能为空")
            }

            if (startDate >= endDate) {
                return createErrorResult("analyzeAppData", "开始时间必须小于结束时间")
            }

            if (backInTime && backInTimeEnd == null) {
                return createErrorResult("analyzeAppData", "启用回溯模式时必须指定 backInTimeEnd")
            }

            // 解析 JSON 参数
            val appAppleTax = parseJsonMap(appAppleTaxJson, "appAppleTaxJson")
            val exchangeRate = parseJsonMap(exchangeRateJson, "exchangeRateJson")

            // 验证 metrics
            val validMetrics = metrics.filter { it.isNotBlank() }
            if (validMetrics.isEmpty()) {
                return createErrorResult("analyzeAppData", "至少需要指定一个指标")
            }

            // 验证 groupBy
            val validGroupBy = groupBy.filter { it in VALID_GROUP_BY_DIMENSIONS }
            if (validGroupBy.isEmpty()) {
                return createErrorResult("analyzeAppData", "至少需要指定一个有效的分组维度")
            }

            val request = AnalyzeAppDataRequest(
                appIds = appIds,
                startDate = startDate,
                endDate = endDate,
                metrics = validMetrics,
                groupBy = validGroupBy,
                timeZone = timeZone,
                isMeticulous = isMeticulous,
                includeSandbox = includeSandbox,
                salesMode = salesMode,
                backInTime = backInTime,
                backInTimeEnd = backInTimeEnd,
                appAppleTax = appAppleTax,
                exchangeRate = exchangeRate
            )

            json.encodeToString(
                ToolInvocationResult(
                    tool = "analyzeAppData",
                    request = json.encodeToJsonElement(request),
                    status = "pending",
                    message = "需要调用后端 API: /api/stats/unified"
                )
            )
        } catch (e: Exception) {
            appAnalyticsLogger.warn(e) { "分析应用数据时发生错误" }
            createErrorResult("analyzeAppData", "分析应用数据时发生错误: ${e.message}")
        }
    }

    // ============================================
    // 辅助方法
    // ============================================

    private fun parseJsonMap(jsonStr: String, fieldName: String): Map<String, Double> {
        return try {
            if (jsonStr.isBlank() || jsonStr == "{}") {
                emptyMap()
            } else {
                Json.decodeFromString<Map<String, Double>>(jsonStr)
            }
        } catch (e: Exception) {
            appAnalyticsLogger.warn(e) { "解析 $fieldName 失败" }
            throw IllegalArgumentException("解析 $fieldName 失败: ${e.message}")
        }
    }

    private fun createErrorResult(tool: String, message: String): String {
        return json.encodeToString(
            ToolInvocationResult(
                tool = tool,
                request = null,
                status = "error",
                message = message
            )
        )
    }

    companion object {
        private val VALID_GROUP_BY_DIMENSIONS = setOf("total", "app", "date", "country", "city")
    }
}

// ============================================
// 数据模型定义
// ============================================

/**
 * 工具调用结果的统一结构
 */
@Serializable
@LLMDescription("工具调用结果")
data class ToolInvocationResult(
    @property:LLMDescription("调用的工具名称")
    val tool: String,

    @property:LLMDescription("请求参数（结构化对象）")
    val request: JsonElement? = null,

    @property:LLMDescription("调用状态：pending(等待执行), success(成功), error(失败)")
    val status: String,

    @property:LLMDescription("状态消息或错误信息")
    val message: String
)

// ============================================
// 请求模型
// ============================================

/**
 * 获取应用列表请求
 */
@Serializable
@LLMDescription("获取应用列表的请求参数")
data class GetAppListRequest(
    @property:LLMDescription("平台类型：iOS, Android, 或 null 表示所有平台")
    val platform: String? = null,

    @property:LLMDescription("是否启用分页")
    val paging: Boolean = true,

    @property:LLMDescription("返回记录数限制（1-100）")
    val limit: Int = 20,

    @property:LLMDescription("跳过的记录数")
    val skip: Int = 0,

    @property:LLMDescription("排序字段")
    val sort: String? = null,

    @property:LLMDescription("排序顺序：asc 或 desc")
    val sortOrder: String? = "desc"
)

/**
 * 统一数据分析请求
 * 
 * 这个请求对象映射到 FastComputer 的参数：
 * - metrics -> unitList
 * - groupBy -> byTotal, byAppId, byCountry, byDate, byCity
 * - 其他参数直接对应 FastComputer 的构造参数
 */
@Serializable
@LLMDescription("统一数据分析的请求参数，对应 FastComputer 的所有功能")
data class AnalyzeAppDataRequest(
    @property:LLMDescription("应用ID列表")
    val appIds: List<String>,

    @property:LLMDescription("开始时间戳（毫秒）")
    val startDate: Long,

    @property:LLMDescription("结束时间戳（毫秒）")
    val endDate: Long,

    @property:LLMDescription("需要分析的指标列表（对应 FastComputer 的 unitList）")
    val metrics: List<String>,

    @property:LLMDescription("数据分组维度（对应 FastComputer 的 byTotal, byAppId, byCountry, byDate, byCity）")
    val groupBy: List<String>,

    @property:LLMDescription("时区（对应 FastComputer 的 timeZone）")
    val timeZone: String = "UTC",

    @property:LLMDescription("是否精确统计活跃用户（对应 FastComputer 的 isMeticulous）")
    val isMeticulous: Boolean = false,

    @property:LLMDescription("是否包含沙盒数据（对应 FastComputer 的 includeSandbox）")
    val includeSandbox: Boolean = false,

    @property:LLMDescription("是否启用销售模式（对应 FastComputer 的 salesMode）")
    val salesMode: Boolean = false,

    @property:LLMDescription("是否启用回溯模式（对应 FastComputer 的 backInTime）")
    val backInTime: Boolean = false,

    @property:LLMDescription("回溯模式的结束时间戳（对应 FastComputer 的 backInTimeEnd）")
    val backInTimeEnd: Long? = null,

    @property:LLMDescription("应用的苹果税率映射（对应 FastComputer 的 appAppleTax）")
    val appAppleTax: Map<String, Double> = emptyMap(),

    @property:LLMDescription("汇率映射（对应 FastComputer 的 useExchangeRateMap）")
    val exchangeRate: Map<String, Double> = emptyMap()
) {
    /**
     * 转换为 FastComputer 的参数格式
     */
    fun toFastComputerParams(): Map<String, Any?> {
        return mapOf(
            "includeSandbox" to includeSandbox,
            "isMeticulous" to isMeticulous,
            "salesMode" to salesMode,
            "backInTime" to backInTime,
            "backInTimeEnd" to (backInTimeEnd ?: 0L),
            "startDate" to startDate,
            "endDate" to endDate,
            "unitList" to metrics,
            "byTotal" to groupBy.contains("total"),
            "byAppId" to groupBy.contains("app"),
            "byCountry" to groupBy.contains("country"),
            "byDate" to groupBy.contains("date"),
            "byCity" to groupBy.contains("city"),
            "timeZone" to timeZone,
            "appAppleTax" to appAppleTax,
            "useExchangeRateMap" to exchangeRate
        )
    }
}

// ============================================
// 响应模型
// ============================================

/**
 * 应用信息
 */
@Serializable
@LLMDescription("应用基本信息")
data class AppInfo(
    @property:LLMDescription("应用ID")
    val appId: String,

    @property:LLMDescription("应用名称")
    val appName: String,

    @property:LLMDescription("平台类型：iOS 或 Android")
    val platform: String,

    @property:LLMDescription("Bundle ID（iOS）或 Package Name（Android）")
    val bundleId: String,

    @property:LLMDescription("应用图标URL")
    val iconUrl: String? = null,

    @property:LLMDescription("创建时间（ISO 8601 格式）")
    val createdAt: String,

    @property:LLMDescription("最后更新时间（ISO 8601 格式）")
    val updatedAt: String? = null,

    @property:LLMDescription("应用状态：active(活跃), inactive(未激活), archived(已归档)")
    val status: String = "active"
)

/**
 * 应用列表响应
 */
@Serializable
@LLMDescription("应用列表响应数据")
data class AppListResponse(
    @property:LLMDescription("应用列表")
    val apps: List<AppInfo>,

    @property:LLMDescription("总数")
    val total: Int,

    @property:LLMDescription("当前页码（从0开始）")
    val page: Int,

    @property:LLMDescription("每页数量")
    val pageSize: Int
)

/**
 * 数据分析响应
 * 
 * 对应 FastComputer 的输出结果：
 * - resultLongMap -> 整数类型指标
 * - resultDoubleMap -> 浮点数类型指标
 * - computedResults -> 复合指标（计算得出）
 * - breakdownData -> 细分数据
 */
@Serializable
@LLMDescription("数据分析响应，包含多维度的统计数据")
data class AnalyzeAppDataResponse(
    @property:LLMDescription("应用ID列表")
    val appIds: List<String>,

    @property:LLMDescription("时间范围")
    val timeRange: TimeRange,

    @property:LLMDescription("请求的指标列表")
    val requestedMetrics: List<String>,

    @property:LLMDescription("请求的分组维度列表")
    val requestedGroupBy: List<String>,

    @property:LLMDescription("总计数据（如果请求了 total 维度）。Key 为指标名称，Value 为指标值")
    val totalData: Map<String, Double>? = null,

    @property:LLMDescription("按应用分组的数据（如果请求了 app 维度）。第一层 Key 为应用ID，第二层 Key 为指标名称")
    val byApp: Map<String, Map<String, Double>>? = null,

    @property:LLMDescription("按日期分组的数据（如果请求了 date 维度）。第一层 Key 为日期（YYYY-MM-DD），第二层 Key 为指标名称")
    val byDate: Map<String, Map<String, Double>>? = null,

    @property:LLMDescription("按国家分组的数据（如果请求了 country 维度）。第一层 Key 为国家代码（ISO 3166-1 alpha-2），第二层 Key 为指标名称")
    val byCountry: Map<String, Map<String, Double>>? = null,

    @property:LLMDescription("按城市分组的数据（如果请求了 city 维度）。第一层 Key 为城市名称，第二层 Key 为指标名称")
    val byCity: Map<String, Map<String, Double>>? = null,

    @property:LLMDescription("细分数据（如退款周期分析、订阅类型分布等）。第一层 Key 为分析类型，第二层 Key 为细分维度")
    val breakdownData: Map<String, Map<String, Double>>? = null,

    @property:LLMDescription("元数据信息")
    val metadata: ResponseMetadata? = null
)

/**
 * 时间范围
 */
@Serializable
@LLMDescription("时间范围")
data class TimeRange(
    @property:LLMDescription("开始时间戳（毫秒）")
    val startDate: Long,

    @property:LLMDescription("结束时间戳（毫秒）")
    val endDate: Long,

    @property:LLMDescription("开始日期字符串（YYYY-MM-DD）")
    val startDateStr: String? = null,

    @property:LLMDescription("结束日期字符串（YYYY-MM-DD）")
    val endDateStr: String? = null,

    @property:LLMDescription("时区")
    val timeZone: String = "UTC"
)

/**
 * 响应元数据
 */
@Serializable
@LLMDescription("响应元数据")
data class ResponseMetadata(
    @property:LLMDescription("计算耗时（毫秒）")
    val computationTime: Long? = null,

    @property:LLMDescription("数据来源：realtime(实时计算), cache(缓存)")
    val dataSource: String? = null,

    @property:LLMDescription("缓存过期时间（毫秒时间戳）")
    val cacheExpiry: Long? = null,

    @property:LLMDescription("警告信息列表")
    val warnings: List<String>? = null
)

/**
 * 指标分类和说明
 */
object MetricCatalog {
    /**
     * 收入指标
     */
    val REVENUE_METRICS = mapOf(
        "sales" to "总销售额，包含所有类型的收入",
        "newSales" to "新用户产生的销售额",
        "renewal" to "续订产生的收入",
        "afterTaxIncome" to "扣除平台税费后的收入",
        "annualSubscriptionRevenue" to "年订阅产生的收入",
        "weeklySubscriptionRevenue" to "周订阅产生的收入"
    )

    /**
     * 退款指标
     */
    val REFUND_METRICS = mapOf(
        "refund" to "退款总额",
        "afterTaxRefund" to "扣除平台税费后的退款",
        "refundRate" to "退款率 = 退款用户数 / 付费用户数",
        "refundAmountRate" to "退款金额率 = 退款金额 / 总销售额",
        "avgRefundDuration" to "平均退款处理时长（天）"
    )

    /**
     * 用户指标
     */
    val USER_METRICS = mapOf(
        "activeUserCount" to "活跃用户数",
        "newUsers" to "新增用户数",
        "paidUsers" to "付费用户数",
        "newSalesUsers" to "首次购买的用户数",
        "renewalUsers" to "续订用户数",
        "trialUsers" to "试用用户数",
        "trialPaidUsers" to "从试用转为付费的用户数",
        "trialOnlyUsers" to "仅试用未付费的用户数",
        "activeSubscriptionUsers" to "当前有活跃订阅的用户数",
        "activeRenewalUsers" to "当前有活跃续订的用户数",
        "expiredUsers" to "订阅已过期的用户数",
        "gracePeriodUsers" to "处于宽限期的用户数",
        "payFailedUsers" to "支付失败的用户数"
    )

    /**
     * 复合指标
     */
    val COMPOSITE_METRICS = mapOf(
        "MRR" to "月经常性收入（Monthly Recurring Revenue）",
        "ARR" to "年经常性收入（Annual Recurring Revenue）",
        "ARPU" to "平均每用户收入（Average Revenue Per User）",
        "ARPPU" to "平均每付费用户收入（Average Revenue Per Paying User）",
        "LTV" to "用户生命周期价值（Lifetime Value）",
        "payRate" to "付费率 = 付费用户数 / 活跃用户数",
        "renewalRate" to "续订率 = 续订用户数 / 可续订用户数",
        "trialConversionRate" to "试用转化率 = 试用转付费用户数 / 试用用户数"
    )

    /**
     * 所有指标
     */
    val ALL_METRICS = REVENUE_METRICS + REFUND_METRICS + USER_METRICS + COMPOSITE_METRICS
}
