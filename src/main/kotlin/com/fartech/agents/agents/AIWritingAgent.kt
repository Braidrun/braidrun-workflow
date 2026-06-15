package com.fartech.agents.agents

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.AskUser
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.SayToUser
import com.fartech.agents.commons.buildAndRunStringAgent
import com.fartech.agents.commons.parseExactToolSet
import com.fartech.agents.tools.SafeFileTools
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import java.util.*

/**
 * AI Writing Agent
 * 专门用于生成 Word 文档的 Agent
 * 
 * 工作流程：
 * 1. 根据用户提供的主题和要求，生成 Markdown 格式的内容
 * 2. 使用 WordAdvancedTools.createDocxFromMarkdown 将 Markdown 转换为格式化的 Word 文档
 * 
 * @param httpAccess HTTP 访问接口
 * @param parameters 配置参数，必须包含：
 *   - prompt: 用户提示（包含主题和要求）
 *   - output_path: Word 文档输出路径
 * @return 生成的 Markdown 内容（用于保存到数据库）
 */
suspend fun aiWritingAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>
): String {
    // Phase 6: instead of hand-wiring `tools(WordAdvancedTools())` directly, go through
    // `parseExactToolSet(listOf("word"))` which registers the full Word tier surface
    // (basic + advanced + enhanced) as a single group. That keeps this agent's tool
    // menu aligned with what the registry produces for workflow-configured Word
    // agents — no divergence between CLI / workflow / API surfaces.
    val wordToolRegistry = parseExactToolSet(parameters, httpAccess, tools = listOf("word"))
    val toolRegistry = ToolRegistry {
        tool(SayToUser)
        tool(AskUser)
        tool(ExitTool)
        tools(SafeFileTools())
        wordToolRegistry.tools.forEach { tool(it) }
    }

    val outputPath = parameters.parameter("output_path", "")
    if (outputPath.isBlank()) {
        throw IllegalArgumentException("output_path parameter is required and cannot be empty")
    }

    val systemPrompt = """
        你是 AIWritingAgent，一名专业的文档写作助手，专门帮助用户创建高质量的 Word 文档。
        
        当前时间: ${Date()} 时区: ${TimeZone.getDefault()}。
        
        你的任务：
        1. 根据用户提供的主题和核心重点，生成一份完整的、结构化的文档内容
        2. 内容必须使用 Markdown 格式编写，包括：
           - 标题：使用 # ## ### 等表示不同级别的标题
           - 粗体：使用 **文本** 表示重要内容
           - 斜体：使用 *文本* 表示强调
           - 列表：使用 - 或 * 表示无序列表，使用数字表示有序列表
           - 代码：使用 `代码` 表示行内代码，使用 ```代码块``` 表示代码块
           - 引用：使用 > 表示引用
           - 表格：使用 Markdown 表格语法
        3. 确保文档结构清晰，层次分明，内容丰富
        4. 生成 Markdown 内容后，必须使用 WordAdvancedTools.createDocxFromMarkdown 工具将内容转换为 Word 文档
        
        重要提示：
        - 必须使用 Markdown 格式，不要返回纯文本
        - 生成的内容应该完整、专业、有逻辑性
        - 使用适当的 Markdown 语法来增强文档的可读性
        - 转换 Word 文档时，输出路径为：$outputPath
        - 在调用 createDocxFromMarkdown 时，markdownText 参数应该是完整的 Markdown 内容
        
        工作流程：
        1. 理解用户的需求和主题
        2. 生成完整的 Markdown 格式文档内容（内容要丰富、专业）
        3. 使用 createDocxFromMarkdown(markdownText=生成的Markdown内容, outputPath=$outputPath) 工具将 Markdown 转换为 Word 文档
        4. 确认文档已成功生成后，在最终回复中返回完整的 Markdown 内容（这是必需的，用于保存到数据库）
        
        重要：在最后一步，你必须返回完整的 Markdown 内容，格式如下：
        ```
        [MARKDOWN_CONTENT_START]
        [这里是你生成的完整 Markdown 内容]
        [MARKDOWN_CONTENT_END]
        ```
        
        注意：不要只是生成 Markdown 文本，必须调用工具将其转换为 Word 文档，然后在最后返回 Markdown 内容！
        
        """.trimIndent()

    val (agent, result) = buildAndRunStringAgent(
        httpAccess = httpAccess,
        parameters = parameters,
        systemPrompt = systemPrompt,
        toolRegistry = toolRegistry
    )

    agent.close()

    if (result == null) {
        return ""
    }

    // 尝试从结果中提取 Markdown 内容
    // 如果 agent 按照指示返回了标记的 Markdown，提取它
    val markdownStartMarker = "[MARKDOWN_CONTENT_START]"
    val markdownEndMarker = "[MARKDOWN_CONTENT_END]"

    val startIndex = result.indexOf(markdownStartMarker)
    val endIndex = result.indexOf(markdownEndMarker)

    return if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
        // 提取标记的 Markdown 内容
        result.substring(startIndex + markdownStartMarker.length, endIndex).trim()
    } else {
        // 如果没有标记，返回整个结果（可能包含 Markdown 或其他内容）
        // 这是一个后备方案，理想情况下 agent 应该返回标记的内容
        result
    }
}
