package com.fartech.agents.utils

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 终端问题修复工具
 * 解决 JLine "Unable to create a system terminal" 警告
 */
object TerminalFix {

    init {
        // 禁用 JLine 的日志警告
        disableJLineLogging()

        // 设置系统属性以改善终端兼容性
        setupTerminalProperties()
    }

    /**
     * 禁用 JLine 的警告日志
     */
    private fun disableJLineLogging() {
        try {
            // 禁用 JLine 相关的日志
            val jlineLogger = Logger.getLogger("org.jline")
            jlineLogger.level = Level.SEVERE

            val jlineUtilsLogger = Logger.getLogger("org.jline.utils")
            jlineUtilsLogger.level = Level.SEVERE

            val jlineTerminalLogger = Logger.getLogger("org.jline.terminal")
            jlineTerminalLogger.level = Level.SEVERE

        } catch (e: Exception) {
            // 忽略日志配置错误
        }
    }

    /**
     * 设置终端属性以改善兼容性
     */
    private fun setupTerminalProperties() {
        try {
            // 设置终端类型
            System.setProperty("jline.terminal", "auto")

            // 禁用 JNA 以避免某些环境的问题
            System.setProperty("jline.terminal.jna", "false")

            // 强制使用系统终端
            System.setProperty("jline.terminal.force", "true")

            // 设置编码
            System.setProperty("file.encoding", "UTF-8")

            // 禁用颜色如果不支持
            if (System.getenv("TERM") == "dumb") {
                System.setProperty("jline.terminal.ansi", "false")
            }

        } catch (e: Exception) {
            // 忽略属性设置错误
        }
    }

    /**
     * 创建一个安静的终端实例（不显示警告）
     */
    fun createQuietTerminal(): Terminal {
        // 临时重定向 System.err 来捕获警告
        val originalErr = System.err
        val suppressedErr = PrintStream(ByteArrayOutputStream())
        System.setErr(suppressedErr)
        return try {
            try {
                TerminalBuilder.builder()
                    .system(true)
                    .build()
            } catch (e: Exception) {
                // 如果系统终端失败，使用 dumb terminal
                TerminalBuilder.builder()
                    .dumb(true)
                    .streams(System.`in`, System.out)
                    .build()
            }
        } catch (e: Exception) {
            // 最后的备用方案
            TerminalBuilder.builder()
                .dumb(true)
                .build()
        } finally {
            // 恢复原始 System.err
            System.setErr(originalErr)
            suppressedErr.close()
        }
    }

    /**
     * 安全读取用户输入，避免阻塞问题
     */
    fun safeReadLine(prompt: String = ""): String {
        return try {
            if (prompt.isNotEmpty()) {
                print(prompt)
                System.out.flush()
            }

            val reader = System.`in`.bufferedReader()
            val input = reader.readLine()?.trim() ?: ""

            // 确保输出流被正确刷新
            System.out.flush()

            input
        } catch (e: Exception) {
            println("❌ 输入读取失败: ${e.message}")
            ""
        }
    }

    /**
     * 安全读取多行用户输入，避免阻塞问题
     */
    fun safeReadMultiline(prompt: String = "", endPrompt: String = "输入空行结束"): String {
        return try {
            if (prompt.isNotEmpty()) {
                println(prompt)
            }
            println("📝 多行输入模式 ($endPrompt)")

            val lines = mutableListOf<String>()
            var lineNumber = 1
            val reader = System.`in`.bufferedReader()

            while (true) {
                val linePrompt = String.format("%02d│ ", lineNumber)
                print(linePrompt)
                System.out.flush()

                val line = reader.readLine()?.trim() ?: break

                if (line.isEmpty()) {
                    break
                }

                lines.add(line)
                lineNumber++
            }

            // 确保输出流被正确刷新
            System.out.flush()

            lines.joinToString("\n")
        } catch (e: Exception) {
            println("❌ 多行输入读取失败: ${e.message}")
            ""
        }
    }

    /**
     * 检查当前终端环境
     */
    fun checkTerminalEnvironment(): String {
        val info = StringBuilder()

        info.append("🔧 终端环境信息:\n")
        info.append("• TERM: ${System.getenv("TERM") ?: "未设置"}\n")
        info.append("• COLORTERM: ${System.getenv("COLORTERM") ?: "未设置"}\n")
        info.append("• TERM_PROGRAM: ${System.getenv("TERM_PROGRAM") ?: "未设置"}\n")
        info.append("• OS: ${System.getProperty("os.name")}\n")
        info.append("• Java版本: ${System.getProperty("java.version")}\n")

        val isSupported = try {
            val terminal = createQuietTerminal()
            val supported = terminal.type != "dumb"
            terminal.close()
            supported
        } catch (e: Exception) {
            false
        }

        info.append("• 终端支持: ${if (isSupported) "✅ 支持高级功能" else "⚠️ 基础模式"}\n")

        return info.toString()
    }

    /**
     * 应用终端修复
     */
    fun applyFix() {
        println("🔧 正在应用终端兼容性修复...")

        // 初始化修复（构造函数中已经完成）

        println("✅ 终端修复已应用")
        println("💡 提示: JLine 警告已被抑制，程序将正常运行")
    }
}
