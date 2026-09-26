package com.fartech.agents.commons

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import ai.koog.utils.time.KoogClock
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * Pins that the multi-tool graphs (default `just_work_parallel` strategy →
 * `toolCycleGraphMulti` → `requestLLMMultiplePreservingDeepSeekReasoning`)
 * make normal, metered LLM calls: every round fires the agent pipeline's
 * LLM-call events (the only source of `llm_call_completed` / token events)
 * and history carries exactly one assistant message per round.
 *
 * Before the fix these rounds went through Koog's `executeMultipleChoices`,
 * which fires no pipeline events at all.
 */
class MultiToolRoundLlmEventsTest {

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000,
    )

    private object EchoTool : SimpleTool<EchoTool.Args>(
        argsType = typeToken<Args>(),
        name = "echo",
        description = "Echoes the given text.",
    ) {
        @Serializable
        data class Args(val text: String)

        override suspend fun execute(args: Args): String = "echo: ${args.text}"
    }

    /** Replays [responses] in order and records how it was called. */
    private class ScriptedExecutor(responses: List<Message.Assistant>) : PromptExecutor() {
        private val queue = ArrayDeque(responses)
        val executePrompts: MutableList<Prompt> = Collections.synchronizedList(mutableListOf())
        var multipleChoicesCalls = 0

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            executePrompts += prompt
            return queue.removeFirst()
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow { error("streaming is not used by these strategies") }

        override suspend fun executeMultipleChoices(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): LLMChoice {
            multipleChoicesCalls++
            return listOf(queue.removeFirst(), queue.removeFirst())
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("moderation is not used by these strategies")

        override fun close() = Unit
    }

    private class RunCapture {
        val llmCallsStarted = Collections.synchronizedList(mutableListOf<String>())
        val llmCallsCompleted = Collections.synchronizedList(mutableListOf<Message.Assistant?>())
        var finalHistory: List<Message> = emptyList()
    }

    private fun usage(input: Int, output: Int) = ResponseMetaInfo.create(
        KoogClock.System,
        totalTokensCount = input + output,
        inputTokensCount = input,
        outputTokensCount = output,
    )

    private fun params(vararg pairs: Pair<String, String>): List<ConfigurationParameter> =
        pairs.map { (key, value) -> ConfigurationParameter(key = key, value = JsonPrimitive(value)) }

    private fun runAgent(
        executor: ScriptedExecutor,
        strategyName: String = "just_work_parallel",
        numberOfChoices: Int? = null,
        strategy: ((ToolRegistry) -> AIAgentGraphStrategy<String, String>)? = null,
    ): Pair<String, RunCapture> = runBlocking {
        val capture = RunCapture()
        val toolRegistry = ToolRegistry { tool(EchoTool) }
        val agent = GraphAIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("multi-tool-round-test", params = LLMParams(numberOfChoices = numberOfChoices)) {
                    system("You are a test agent.")
                },
                model = model,
                maxAgentIterations = 50,
            ),
            strategy = strategy?.invoke(toolRegistry) ?: determineDefaultStrategy(
                httpAccess = HttpAccess(),
                parameters = params("strategy" to strategyName),
                toolRegistry = toolRegistry,
            ),
            toolRegistry = toolRegistry,
            installFeatures = {
                handleEvents {
                    onLLMCallStarting { capture.llmCallsStarted += it.eventId }
                    onLLMCallCompleted { capture.llmCallsCompleted += it.response }
                    onAgentCompleted { ctx ->
                        capture.finalHistory = ctx.context.llm.readSession { prompt.messages }
                    }
                }
            },
        )
        agent.run("Echo hi, then finish.") to capture
    }

    @Test
    fun `just_work_parallel fires LLM call events with usage for every round`() {
        val executor = ScriptedExecutor(
            listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "call_1",
                            tool = EchoTool.name,
                            args = JsonObject(mapOf("text" to JsonPrimitive("hi"))),
                        )
                    ),
                    metaInfo = usage(input = 100, output = 20),
                    finishReason = "tool_calls",
                ),
                Message.Assistant(content = "done", metaInfo = usage(input = 150, output = 5), finishReason = "stop"),
            )
        )

        val (output, capture) = runAgent(executor)

        assertEquals("done", output)
        assertEquals(0, executor.multipleChoicesCalls, "single-choice rounds must not use executeMultipleChoices")
        assertEquals(2, executor.executePrompts.size)
        assertEquals(2, capture.llmCallsStarted.size, "onLLMCallStarting must fire for every round")
        assertEquals(
            listOf(100 to 20, 150 to 5),
            capture.llmCallsCompleted.map { it!!.metaInfo.inputTokensCount to it.metaInfo.outputTokensCount },
            "onLLMCallCompleted must carry each round's usage (the metering source)",
        )

        // Exactly one assistant message per round, and the tool result answers the call's id.
        val secondRoundHistory = executor.executePrompts[1].messages
        val firstRoundAssistant = secondRoundHistory.filterIsInstance<Message.Assistant>().single()
        val call = firstRoundAssistant.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        val result = secondRoundHistory.last().parts.filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals("call_1", call.id)
        assertEquals(call.id, result.id)
        assertEquals("echo: hi", result.output)

        val finalAssistants = capture.finalHistory.filterIsInstance<Message.Assistant>()
        assertEquals(2, finalAssistants.size, "history must hold one assistant message per round")
        assertEquals("done", finalAssistants.last().textContent())
    }

    @Test
    fun `reasoning-only round is replaced in place by a provider-valid text fallback`() {
        val executor = ScriptedExecutor(
            listOf(
                Message.Assistant(
                    parts = listOf(MessagePart.Reasoning("I already know the answer: 42.")),
                    metaInfo = usage(input = 80, output = 30),
                    finishReason = "stop",
                ),
            )
        )

        val (output, capture) = runAgent(executor)

        assertEquals("I already know the answer: 42.", output)
        assertEquals(1, capture.llmCallsCompleted.size)
        val assistant = capture.finalHistory.filterIsInstance<Message.Assistant>().single()
        assertTrue(assistant.hasProviderValidAssistantPayload())
        assertTrue(assistant.parts.none { it is MessagePart.Reasoning })
        assertEquals("I already know the answer: 42.", assistant.textContent())
    }

    @Test
    fun `single_run strategy rounds are metered too`() {
        val executor = ScriptedExecutor(
            listOf(Message.Assistant(content = "hello", metaInfo = usage(input = 12, output = 3), finishReason = "stop"))
        )

        val (output, capture) = runAgent(executor, strategyName = "single_run")

        assertEquals("hello", output)
        assertEquals(0, executor.multipleChoicesCalls)
        assertEquals(listOf(12), capture.llmCallsCompleted.map { it!!.metaInfo.inputTokensCount })
        assertEquals(1, capture.finalHistory.filterIsInstance<Message.Assistant>().size)
    }

    @Test
    fun `assistant buffered strategy meters every round after restored history`() {
        // singleRunWithParallelAbility is the web assistant's buffered path
        // (sync endpoint + durable /global-agent/chat/runs); its usage comes
        // only from onLLMCallCompleted.
        val executor = ScriptedExecutor(
            listOf(
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "call_a",
                            tool = EchoTool.name,
                            args = JsonObject(mapOf("text" to JsonPrimitive("x"))),
                        )
                    ),
                    metaInfo = usage(input = 40, output = 4),
                    finishReason = "tool_calls",
                ),
                Message.Assistant(content = "all done", metaInfo = usage(input = 60, output = 6), finishReason = "stop"),
            )
        )

        val (output, capture) = runAgent(executor, strategy = {
            singleRunWithParallelAbility(
                name = "__assistant_test__",
                historyMessages = listOf(
                    mapOf("role" to "user", "content" to "earlier question"),
                    mapOf("role" to "assistant", "content" to "earlier answer"),
                ),
            )
        })

        assertEquals("all done", output)
        assertEquals(0, executor.multipleChoicesCalls)
        assertEquals(listOf(40 to 4, 60 to 6), capture.llmCallsCompleted.map {
            it!!.metaInfo.inputTokensCount to it.metaInfo.outputTokensCount
        })
        val secondRound = executor.executePrompts[1].messages
        val call = secondRound.filterIsInstance<Message.Assistant>().last()
            .parts.filterIsInstance<MessagePart.Tool.Call>().single()
        val result = secondRound.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals(call.id, result.id)
        // Restored history assistant + one per round.
        assertEquals(3, capture.finalHistory.filterIsInstance<Message.Assistant>().size)
    }

    @Test
    fun `explicit multiple choices still use executeMultipleChoices and append every choice`() {
        val executor = ScriptedExecutor(
            listOf(
                Message.Assistant(content = "first", metaInfo = usage(input = 10, output = 1), finishReason = "stop"),
                Message.Assistant(content = "second", metaInfo = usage(input = 10, output = 1), finishReason = "stop"),
            )
        )

        val (_, capture) = runAgent(executor, numberOfChoices = 2)

        assertEquals(1, executor.multipleChoicesCalls)
        // Upstream ContextualPromptExecutor.executeMultipleChoices fires no pipeline events.
        assertTrue(capture.llmCallsCompleted.isEmpty())
        assertEquals(
            listOf("first", "second"),
            capture.finalHistory.filterIsInstance<Message.Assistant>().map { it.textContent() },
        )
    }
}
