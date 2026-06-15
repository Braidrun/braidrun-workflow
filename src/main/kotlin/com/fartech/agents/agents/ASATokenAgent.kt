package com.fartech.agents.agents

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.fartech.agents.commons.buildAndRunStringAgent
// Koog 1.0.0 — `nodeExecuteTool` shim lives in commons.DeepSeekReasoningNodes
// (Koog removed the singular helper; the shim adapts the new
// `environment.executeTool` API). Same for the multi variant.
import com.fartech.agents.commons.nodeExecuteTool
import com.fartech.agents.commons.nodeLLMRequestPreservingDeepSeekReasoning
import com.fartech.agents.commons.nodeLLMSendToolResultsPreservingDeepSeekReasoning
import com.fartech.agents.commons.parseToolSet
import com.fartech.agents.commons.requestLLMPreservingDeepSeekReasoning
import com.fartech.agents.tools.ASATokenAutomationTools
import com.fartech.ftapp2.commonsKt.*

/**
 * ASA Token 采集代理 - 简化版
 * 使用 Playwright MCP 完成登录，然后由用户手动提供 curl 请求
 */
suspend fun asaTokenAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>
) {
    printlnColor(AnsiColor.CYAN, "启动 ASA Token 采集代理")

    // 从配置文件读取凭证信息
    val asaWebsiteUrl = parameters.parameter("asa_website_url", "https://app-ads.apple.com")
    parameters.parameter("asa_apple_id", "")
    parameters.parameter("asa_apple_password", "")
    val reportUrl = parameters.parameter("asa_report_url", "")
    val cookieFilePath = parameters.parameter("cookie_file_path", "./cookie.txt")

    val systemPrompt = parameters.parameter(
        "system_prompt",
        """
        你是 ASA Token 采集专家 (ASATokenAgent)。
        
        🎯 核心任务
        1. 使用 Playwright MCP 帮助用户完成 ASA 登录
        2. 指导用户手动复制 Cookie 字符串
        3. 等待用户提供 Cookie 字符串
        4. 解析 Cookie 中的认证信息并上报
        
        📋 详细工作流程
        
        第一步：登录协助
        - 调用 mcp_playwright_browser_navigate 导航到 https://searchads.apple.com
        - 使用 mcp_playwright_browser_snapshot 检查页面状态
        - 如果需要登录，指导用户完成登录流程
        
        第二步：指导用户复制 Cookie
        - 登录成功后，使用 enhanced_say_to_user 指导用户：
          1. 打开浏览器开发者工具 (F12)
          2. 切换到 Application 标签页
          3. 在左侧找到 Cookies → https://app-ads.apple.com
          4. 复制所有 Cookie 值（选择所有 Cookie，复制）
          5. 将复制的 Cookie 字符串粘贴给 agent
        
        第三步：等待用户输入 Cookie
        - 使用 enhanced_ask_user 工具等待用户提供 Cookie 字符串
        - 设置 multiline=true 支持多行输入
        - 设置 message="请将Cookie字符串保存到cookie.txt文件中，然后输入'已保存到cookie.txt'"
        - 设置 placeholder="格式：已保存到cookie.txt（推荐）或直接粘贴Cookie字符串"
        - 等待用户确认Cookie已保存到文件
        
        推荐使用文件方式：
        - 指导用户将Cookie字符串保存到文件：$cookieFilePath
        - 用户输入"已保存到cookie.txt"确认
        - 使用 parseCookieFromFile 工具读取文件中的Cookie
        - 文件方式可以避免终端输入问题
        
        第四步：解析并上报
        - 使用 parseCookieString 工具解析用户提供的 Cookie 字符串
        - 提取认证信息（myacinfo、sa_user、searchads.userId等）
        - 使用 reportASAToken 上报数据
        
        ⚠️ 重要提醒
        - 在第三步中，必须使用 enhanced_ask_user 工具等待用户输入
        - 不要跳过等待用户输入的步骤
        - 不要在指导用户复制Cookie后直接关闭浏览器或结束任务
        - 必须等待用户提供Cookie字符串后才能进行解析和上报
        - 只有在完成所有步骤（登录、指导、等待输入、解析、上报）后才能调用 exit_tool 结束任务
        
        🚫 禁止操作
        - 不要尝试自动提取 Cookie（HttpOnly cookies 无法通过 JavaScript 获取）
        - 不要使用复杂的自动化工具
        - 不要询问用户密码等敏感信息
        - 不要在指导用户复制Cookie后直接关闭浏览器或结束任务
        - 不要跳过等待用户输入Cookie的步骤
        - 不要在任务未完成时结束（必须完成完整的四步流程）
        
        ✅ 推荐方法
        - 使用 MCP 浏览器工具完成登录
        - 使用 enhanced_say_to_user 指导用户
        - 使用 enhanced_ask_user 等待用户确认Cookie已保存到文件
        - 根据用户输入智能选择解析方式：
          * 如果用户输入"已保存到cookie.txt"或类似确认，使用 parseCookieFromFile
          * 如果用户直接粘贴Cookie字符串，使用 parseCookieString
        - 解析 Cookie 中的认证信息
        - 只有在所有步骤完成后才调用 exit_tool
        
        🎯 任务完成条件
        - 完成登录协助
        - 指导用户复制Cookie
        - 等待并获取用户输入的Cookie字符串
        - 成功解析Cookie中的认证信息
        - 成功上报ASA Token数据
        - 只有满足以上所有条件才能调用 exit_tool 结束任务
        
        【可用工具】
        - mcp_playwright_browser_*: 浏览器操作工具
        - enhanced_say_to_user: 向用户发送消息
        - enhanced_ask_user: 等待用户输入（支持多行）
        - parseCookieString: 解析 Cookie 字符串
        - parseCookieFromFile: 从文件读取并解析 Cookie 字符串
        - reportASAToken: 上报认证信息
        - exit_tool: 结束任务
        """.trimIndent()
    )

    buildAndRunStringAgent(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt,
        toolRegistry = parseToolSet(
            parameters,
            httpAccess,
            listOf("interactive", "exit", "mcp"),
            listOf(ASATokenAutomationTools(httpAccess, reportUrl, asaWebsiteUrl))
        ),
        strategyBuilder = { _, _, _ ->
            asaTokenAutomationStrategy("asa_token_automation")
        }
    )

}

/**
 * ASA Token 采集策略 - 参考continuousChatStrategy的强制工具调用版本
 */
private fun asaTokenAutomationStrategy(name: String = "asaTokenAutomation"): AIAgentGraphStrategy<String, String> {
    return strategy(name) {
        val nodeAutoCollect by nodeLLMRequestPreservingDeepSeekReasoning(name = "autoCollect", allowToolCalls = true)
        val nodeExecuteTool by nodeExecuteTool(name = "executeTool")
        val nodeSendToolResult by nodeLLMSendToolResultsPreservingDeepSeekReasoning(name = "sendToolResult")

        // 添加强制工具调用节点 - 参考continuousChatStrategy
        val giveFeedbackToCallTools by node<String, Message.Assistant> { input ->
            llm.writeSession {
                appendPrompt {
                    user(
                        "作为ASA Token采集专家，你必须使用工具来完成任务！请调用以下工具之一：\n" +
                                "- mcp_playwright_browser_navigate: 导航到ASA网站\n" +
                                "- mcp_playwright_browser_snapshot: 检查页面状态\n" +
                                "- enhanced_say_to_user: 指导用户操作\n" +
                                "- enhanced_ask_user: 等待用户确认Cookie已保存到文件\n" +
                                "- parseCookieString: 解析直接粘贴的Cookie字符串\n" +
                                "- parseCookieFromFile: 从cookie.txt文件读取并解析Cookie\n" +
                                "- reportASAToken: 上报认证信息\n" +
                                "- exit_tool: 完成任务后结束\n\n" +
                                "重要：如果用户说'已保存到cookie.txt'，请使用 parseCookieFromFile 工具！\n" +
                                "不要只发送纯文本消息，必须调用工具！"
                    )
                }
                requestLLMPreservingDeepSeekReasoning()
            }
        }

        // 流程定义
        edge(nodeStart forwardTo nodeAutoCollect)

        // AI请求后，如果有工具调用则执行工具.
        // Koog 1.0.0 — `onToolCall { predicate }` (predicate form) and
        // `onAssistantMessage` removed; use `onCondition` over `msg.parts`.
        edge(nodeAutoCollect forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })

        // AI请求后，如果没有工具调用则强制引导使用工具
        edge(nodeAutoCollect forwardTo giveFeedbackToCallTools onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { msg -> msg.textContent() })

        // 强制工具调用反馈循环。Tool.Call 边必须先声明(与 nodeAutoCollect /
        // nodeSendToolResult 一致):模型常在调用工具的同时附带说明文本,
        // 若 Text 自环先匹配,工具调用会被丢弃并陷入反复"必须使用工具"的循环。
        edge(giveFeedbackToCallTools forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })
        edge(giveFeedbackToCallTools forwardTo giveFeedbackToCallTools onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { msg -> msg.textContent() })

        // 特殊结束条件：当 exit_tool 执行完成时直接结束.
        // `nodeExecuteTool` returns the batched ReceivedToolResults;
        // predicate runs over all results.
        edge(nodeExecuteTool forwardTo nodeFinish onCondition { results: ReceivedToolResults ->
            results.toolResults.any { it.tool == "__exit__" || it.tool == "exit_tool" }
        } transformed { _ -> "" })

        // 执行其他工具后发送结果
        edge(nodeExecuteTool forwardTo nodeSendToolResult onCondition { results: ReceivedToolResults ->
            results.toolResults.none { it.tool == "__exit__" || it.tool == "exit_tool" }
        })

        // 工具结果发送后，如果有新的工具调用则继续执行
        edge(nodeSendToolResult forwardTo nodeExecuteTool onCondition { msg ->
            msg.parts.any { it is MessagePart.Tool.Call }
        })

        // 工具结果发送后，如果没有工具调用则强制引导使用工具
        edge(nodeSendToolResult forwardTo giveFeedbackToCallTools onCondition { msg ->
            msg.parts.any { it is MessagePart.Text }
        } transformed { msg -> msg.textContent() })
    }
}
