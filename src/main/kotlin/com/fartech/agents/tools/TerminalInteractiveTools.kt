package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.utils.TerminalFix
import kotlinx.serialization.Serializable
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException

@Serializable
@LLMDescription("Tools for interactive with users in terminal, like chatting, asking for input, showing progress, etc.")
class TerminalInteractiveTools : ToolSet {

    @Tool
    @LLMDescription("Talk to user in terminal with given message. Does not require response from user.")
    fun talkToUser(
        @LLMDescription("context for the sub-agent")
        message: String
    ): String {
        // Keep it minimal and independent: no Enhanced* dependencies
        println(message)
        System.out.flush()
        return "Done"
    }

    @Tool
    @LLMDescription("Ask user question and get user's answer as input in terminal. Returns the input as a string.")
    fun askUserForInput(
        @LLMDescription("prompt for the user")
        prompt: String
    ): String {
        // Apply terminal fixes but do not depend on EnhancedAskUser/EnhancedSayToUser
        try {
            val normalizedPrompt = prompt.replace("\\n", "\n")
            if (normalizedPrompt.isNotEmpty()) {
                println(normalizedPrompt)
            }
            val terminal = TerminalFix.createQuietTerminal()
            val reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("BraidrunCLI")
                .build()
            val input = reader.readLine("You: ")?.trim() ?: ""
            // Do not close the system terminal explicitly; JLine manages it. Return the captured input.
            return input
        } catch (e: UserInterruptException) {
            // Ctrl-C: treat as empty input
            return ""
        } catch (e: EndOfFileException) {
            // Ctrl-D / EOF
            return ""
        } catch (e: Exception) {
            // Fallback to a very safe stdin read without JLine
            val normalizedPrompt = prompt.replace("\\n", "\n")
            if (normalizedPrompt.isNotEmpty()) {
                println(normalizedPrompt)
            }
            return TerminalFix.safeReadLine("You: ")
        }
    }
}
