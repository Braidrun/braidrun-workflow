package com.fartech.agents.agents.a2a

import ai.koog.agents.a2a.core.A2AMessage
import ai.koog.agents.a2a.core.toKoogMessage
import ai.koog.agents.a2a.server.feature.withA2AAgentServer
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import com.fartech.agents.commons.BraidrunA2AExecutor
import com.fartech.agents.commons.parseToolSet
import com.fartech.agents.commons.sendTextTaskMessage
import com.fartech.agents.commons.startA2AgentServer
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlin.uuid.ExperimentalUuidApi


@OptIn(ExperimentalUuidApi::class)
suspend fun a2aEchoAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>
) {
    startA2AgentServer(
        parameters,
        BraidrunA2AExecutor(
            httpAccess,
            parameters,
            parseToolSet(parameters, httpAccess, listOf("")),
            "You are a simple AI assistant that echoes back what users say.",
            strategy = strategy<A2AMessage, String>("__a2a_echo_strategy__") {
                val echo by node<A2AMessage, String> { userInput ->
                    // Koog 1.0.0 — `Message.content` accessor removed;
                    // use `textContent()` which walks `parts` for text.
                    val text = userInput.toKoogMessage().textContent()
                    withA2AAgentServer {
                        sendTextTaskMessage(text)
                    }
                    text
                }
                nodeStart then echo then nodeFinish
            }
        )
    )
}
