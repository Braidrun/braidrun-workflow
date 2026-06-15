package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.time.Instant

@Serializable
data class ASATokenInfo(
    val myacinfo: String,                // myacinfo Cookie（最关键）
    val saUser: String,                  // sa_user
    val cmToken: String,                 // XSRF-TOKEN-CM
    val cookieString: String,            // 完整的 Cookie 字符串
    val userId: String = "",             // searchads.userId
    val orgId: String = "",              // searchads.soid
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "curl_parsing",
    val isValid: Boolean = true,         // Cookie 是否有效
    val message: String = ""             // 状态消息
)

@Serializable
data class TokenUploadResult(
    val success: Boolean,
    val message: String,
    val uploadedAt: Long = System.currentTimeMillis()
)

/**
 * ASA Token 采集工具集 - 简化版
 * 只包含 curl 解析和上报功能
 *
 * ## Security posture
 *
 * The report endpoint is **server-side configuration only** — the LLM must not be able to
 * redirect token uploads to an arbitrary URL via prompt injection. The `reportUrl`
 * parameter on [reportASAToken] is therefore accepted but **ignored**; uploads always go
 * to [defaultReportUrl], which the platform sets at tool-construction time.
 *
 * The allowed destination host set is also validated at construction so an accidentally
 * mis-configured `defaultReportUrl` (e.g. an attacker-controlled domain reached via YAML
 * injection) fails fast rather than exfiltrating credentials silently.
 */
@LLMDescription("ASA Token 采集与上报工具集")
class ASATokenAutomationTools(
    private val httpAccess: HttpAccess,
    private val defaultReportUrl: String? = null,
    private val asaWebsiteUrl: String = "https://app-ads.apple.com/",
    /**
     * Hosts that are allowed to receive ASA tokens. Defaults to Braidrun's own brand domains.
     * Operators who run the platform on a different domain must set this explicitly.
     */
    private val reportUrlHostAllowlist: Set<String> = DEFAULT_REPORT_HOST_ALLOWLIST
) : ToolSet {

    init {
        defaultReportUrl?.let(::ensureReportUrlAllowed)
    }

    private fun ensureReportUrlAllowed(url: String) {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
        require(host != null && reportUrlHostAllowlist.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }) {
            "ASA reportUrl host '$host' is not on the platform allowlist ($reportUrlHostAllowlist). " +
                "Refusing to upload credentials to an untrusted destination."
        }
    }

    private val logger = KotlinLogging.logger {}

    @Tool
    @LLMDescription(
        """
        解析用户提供的 Cookie 字符串，提取 ASA 认证信息
        
        这个工具会解析 Cookie 字符串中的：
        - myacinfo（最重要的认证 Cookie）
        - sa_user（用户认证信息）
        - searchads.userId（用户ID）
        - searchads.soid（组织ID）
        - XSRF-TOKEN-CM（CSRF 令牌）
        - 其他相关认证信息
    """
    )
    fun parseCookieString(
        @LLMDescription("用户从浏览器开发者工具复制的 Cookie 字符串") cookieString: String
    ): String {
        return try {
            val json = Json { prettyPrint = true; encodeDefaults = true }

            // 解析 Cookie 字符串
            val cookies = parseCookieStringInternal(cookieString)

            val info = ASATokenInfo(
                myacinfo = cookies["myacinfo"] ?: "",
                saUser = cookies["sa_user"] ?: "",
                cmToken = cookies["XSRF-TOKEN-CM"] ?: "",
                cookieString = cookieString,
                userId = cookies["searchads.userId"] ?: "",
                orgId = cookies["searchads.soid"] ?: "",
                timestamp = System.currentTimeMillis(),
                source = "cookie_parsing",
                isValid = cookies.isNotEmpty(),
                message = if (cookies.isNotEmpty()) "OK" else "NO_COOKIES_FOUND"
            )

            json.encodeToString(ASATokenInfo.serializer(), info)
        } catch (e: Exception) {
            val failure = ASATokenInfo(
                myacinfo = "",
                saUser = "",
                cmToken = "",
                cookieString = "",
                userId = "",
                orgId = "",
                timestamp = System.currentTimeMillis(),
                source = "cookie_parsing",
                isValid = false,
                message = "ERROR: ${e.message}"
            )
            Json.encodeToString(ASATokenInfo.serializer(), failure)
        }
    }

    @Tool
    @LLMDescription(
        """
        从指定文件中读取Cookie字符串，然后解析ASA认证信息
        
        这个工具会：
        1. 读取指定文件中的Cookie字符串
        2. 解析Cookie中的认证信息（myacinfo、sa_user、searchads.userId等）
        3. 返回解析后的JSON格式数据
        
        使用方法：
        1. 将Cookie字符串保存到文件中（如cookie.txt）
        2. 调用此工具读取文件
        3. 工具会自动解析并返回结果
    """
    )
    fun parseCookieFromFile(
        @LLMDescription("包含Cookie字符串的文件路径") filePath: String
    ): String {
        return try {
            val json = Json { prettyPrint = true; encodeDefaults = true }

            // 读取文件内容
            val file = ToolPathSecurity.validateInputPath(filePath)
            if (!file.exists() || !file.isFile) {
                val failure = ASATokenInfo(
                    myacinfo = "",
                    saUser = "",
                    cmToken = "",
                    cookieString = "",
                    userId = "",
                    orgId = "",
                    timestamp = System.currentTimeMillis(),
                    source = "file_reading",
                    isValid = false,
                    message = "ERROR: 文件不存在或不是普通文件"
                )
                return json.encodeToString(ASATokenInfo.serializer(), failure)
            }
            if (file.length() > MAX_COOKIE_FILE_BYTES) {
                val failure = ASATokenInfo(
                    myacinfo = "",
                    saUser = "",
                    cmToken = "",
                    cookieString = "",
                    userId = "",
                    orgId = "",
                    timestamp = System.currentTimeMillis(),
                    source = "file_reading",
                    isValid = false,
                    message = "ERROR: Cookie 文件过大，最大允许 ${MAX_COOKIE_FILE_BYTES} 字节"
                )
                return json.encodeToString(ASATokenInfo.serializer(), failure)
            }
            val cookieString = file.readText().trim()

            if (cookieString.isEmpty()) {
                val failure = ASATokenInfo(
                    myacinfo = "",
                    saUser = "",
                    cmToken = "",
                    cookieString = "",
                    userId = "",
                    orgId = "",
                    timestamp = System.currentTimeMillis(),
                    source = "file_reading",
                    isValid = false,
                    message = "ERROR: 文件为空或不存在"
                )
                return json.encodeToString(ASATokenInfo.serializer(), failure)
            }

            // 解析 Cookie 字符串
            val cookies = parseCookieStringInternal(cookieString)

            val info = ASATokenInfo(
                myacinfo = cookies["myacinfo"] ?: "",
                saUser = cookies["sa_user"] ?: "",
                cmToken = cookies["XSRF-TOKEN-CM"] ?: "",
                cookieString = cookieString,
                userId = cookies["searchads.userId"] ?: "",
                orgId = cookies["searchads.soid"] ?: "",
                timestamp = System.currentTimeMillis(),
                source = "file_parsing",
                isValid = cookies.isNotEmpty(),
                message = if (cookies.isNotEmpty()) "OK" else "NO_COOKIES_FOUND"
            )

            json.encodeToString(ASATokenInfo.serializer(), info)
        } catch (e: Exception) {
            val failure = ASATokenInfo(
                myacinfo = "",
                saUser = "",
                cmToken = "",
                cookieString = "",
                userId = "",
                orgId = "",
                timestamp = System.currentTimeMillis(),
                source = "file_reading",
                isValid = false,
                message = "ERROR: ${e.message}"
            )
            Json.encodeToString(ASATokenInfo.serializer(), failure)
        }
    }

    @Tool
    @LLMDescription(
        """
        上报完整的ASA认证信息到平台配置的接口。

        ⚠️ 重要：传入从 parseCurlRequest/parseCookieString 提取的 JSON 字符串。
        目标上报地址由平台服务端配置锁定，不接受 LLM 传入的自定义 URL。
    """
    )
    suspend fun reportASAToken(
        @LLMDescription("从 parseCurlRequest 返回的完整 JSON 字符串") extractedJson: String
    ): String {
        return try {
            // reportUrl is server-side configuration ONLY. Previously this method accepted
            // a caller-supplied URL which, combined with LLM prompt injection, allowed
            // ASA cookies (including myacinfo — the crown jewel for Apple Search Ads
            // sessions) to be redirected to an attacker endpoint. Now the destination is
            // exclusively `defaultReportUrl`; if the operator forgot to configure it, we
            // refuse to proceed rather than fall back to a hard-coded brand URL.
            val actualUrl = defaultReportUrl?.takeIf { it.isNotBlank() }
                ?: return "❌ Token 上报失败: 平台未配置 reportUrl (defaultReportUrl)，拒绝使用硬编码默认值上报凭据。"
            ensureReportUrlAllowed(actualUrl)

            // 解析 JSON 字符串
            val json = Json { ignoreUnknownKeys = true }
            val jsonElement = json.parseToJsonElement(extractedJson)
            val extractedData = jsonElement.jsonObject

            val cookieString = extractedData["cookieString"]?.jsonPrimitive?.content ?: ""

            // 先尝试从 JSON 直接读取，如果为空则从 cookieString 解析
            var myacinfo = extractedData["myacinfo"]?.jsonPrimitive?.content ?: ""
            var saUser = extractedData["saUser"]?.jsonPrimitive?.content ?: ""
            var cmToken = extractedData["cmToken"]?.jsonPrimitive?.content ?: ""
            var userId = extractedData["userId"]?.jsonPrimitive?.content ?: ""

            // 🔧 备用方案：如果关键字段为空，从 cookieString 解析
            if ((myacinfo.isEmpty() || saUser.isEmpty() || cmToken.isEmpty()) && cookieString.isNotEmpty()) {
                logger.debug { "ASA token report fallback: parsing missing fields from cookieString." }
                val cookies = parseCookieStringInternal(cookieString)
                logger.debug { "ASA token report fallback extracted ${cookies.size} cookie entries." }
                if (myacinfo.isEmpty()) {
                    myacinfo = cookies["myacinfo"] ?: ""
                    logger.debug {
                        "ASA token fallback myacinfo status=${if (myacinfo.isNotEmpty()) "present" else "missing"} " +
                            "length=${myacinfo.length}"
                    }
                }
                if (saUser.isEmpty()) {
                    saUser = cookies["sa_user"] ?: ""
                    logger.debug {
                        "ASA token fallback sa_user status=${if (saUser.isNotEmpty()) "present" else "missing"} " +
                            "length=${saUser.length}"
                    }
                }
                if (cmToken.isEmpty()) {
                    cmToken = cookies["XSRF-TOKEN-CM"] ?: ""
                    logger.debug {
                        "ASA token fallback XSRF-TOKEN-CM status=${if (cmToken.isNotEmpty()) "present" else "missing"} " +
                            "length=${cmToken.length}"
                    }
                }
                if (userId.isEmpty()) {
                    userId = cookies["searchads.userId"] ?: ""
                    logger.debug {
                        "ASA token fallback searchads.userId status=${if (userId.isNotEmpty()) "present" else "missing"}"
                    }
                }
            }

            // 使用 HttpAccess 上报完整的认证信息
            val requestBody = mapOf(
                "myacinfo" to myacinfo,          // myacinfo（最关键）
                "saUser" to saUser,              // sa_user
                "cmToken" to cmToken,            // XSRF-TOKEN-CM
                "cookieString" to cookieString,  // 完整 Cookie 字符串
                "userId" to userId       // searchads.userId
            )

            // 使用 HttpAccess.post 发送请求
            val responseBody: String = try {
                httpAccess.post(
                    actualUrl,
                    requestBody,
                    headers = mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                // Log the full exception server-side but don't leak stack traces to the LLM.
                logger.error(e) { "ASA token report failed for userId=${userId.takeIf { it.isNotEmpty() } ?: "<unknown>"}" }
                return "❌ Token 上报失败: ${e::class.simpleName}: ${e.message?.take(200) ?: "unknown error"}"
            }

            buildString {
                appendLine("✅ ASA 认证信息上报成功!")
                appendLine("=".repeat(50))
                appendLine("用户ID: ${if (userId.isNotEmpty()) userId else "未提供"}")
                appendLine("上报地址: $actualUrl")
                appendLine("响应内容: $responseBody")
                appendLine("上报时间: ${Instant.now()}")
                appendLine()
                // Previously this block printed the first 20 chars of each cookie token —
                // that's enough structural fingerprint to help an attacker who already
                // sniffed part of the session. Now we only confirm presence + length.
                appendLine("📊 已上报的认证信息:")
                appendLine("  ✅ myacinfo: ${presenceSummary(myacinfo)}")
                appendLine("  ✅ saUser: ${presenceSummary(saUser)}")
                appendLine("  ✅ cmToken (XSRF-TOKEN-CM): ${presenceSummary(cmToken)}")
                appendLine("  ✅ cookieString: ${cookieString.length} 字符")
            }
        } catch (e: Exception) {
            logger.error(e) { "ASA token report exception" }
            "❌ Token 上报异常: ${e::class.simpleName}: ${e.message?.take(200) ?: "unknown error"}"
        }
    }

    private fun presenceSummary(value: String): String =
        if (value.isNotEmpty()) "已收集 (${value.length} 字符)" else "⚠️ 未找到"


    // 辅助函数：从 cookie 字符串解析字段
    private fun parseCookieStringInternal(cookieString: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        // 支持多种分隔符："; " 或 ";"
        cookieString.split(Regex(";\\s*")).forEach { cookie ->
            val parts = cookie.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    cookies[key] = value
                }
            }
        }
        return cookies
    }

    companion object {
        /**
         * Default allowlist of hosts that may receive ASA authentication tokens.
         * This is a placeholder example domain — the allowlist is a hard security
         * boundary, so operators MUST override it via the constructor with the
         * domain(s) they actually report tokens to before using this tool.
         */
        internal val DEFAULT_REPORT_HOST_ALLOWLIST = setOf(
            "reports.example.com"
        )
        private const val MAX_COOKIE_FILE_BYTES = 64L * 1024L
    }
}
