package com.fartech.agents.commons

import ai.koog.a2a.consts.A2AConsts
import ai.koog.a2a.exceptions.A2AUnsupportedOperationException
import ai.koog.a2a.model.*
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import ai.koog.agents.a2a.core.A2AMessage
import ai.koog.agents.a2a.core.toKoogMessage
import ai.koog.agents.a2a.server.feature.A2AAgentServer
import ai.koog.agents.a2a.server.feature.withA2AAgentServer
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.oneOfParameterCollection
import com.fartech.ftapp2.commonsKt.parameter
import io.ktor.server.cio.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class A2ASecurityScheme(
    val bearer: HTTPAuthSecurityScheme?,
    val apiKey: APIKeySecurityScheme?
)

/**
 * Sends a task update event with the provided task information, content, state, and completion flag.
 *
 * @param task The task object containing identifiers and context information for the task.
 * @param content The content message associated with the task update.
 * @param state The current state of the task to be included in the update.
 * @param final Indicates whether this update marks the final state of the task. Defaults to false.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun A2AAgentServer.sendTaskUpdate(
    task: Task,
    content: String,
    state: TaskState,
    final: Boolean = false
) = eventProcessor.sendTaskEvent(
    TaskStatusUpdateEvent(
        taskId = task.id,
        contextId = task.contextId,
        status = TaskStatus(
            state = state,
            message = Message(
                messageId = Uuid.random().toString(),
                role = Role.Agent,
                parts = listOf(
                    TextPart(content)
                ),
                contextId = context.contextId,
                taskId = context.taskId,
            ),
            timestamp = kotlin.time.Clock.System.now(),
        ),
        final = final
    )
)

/**
 * Sends a list of task-related messages through the A2AAgentServer.
 *
 * @param messages A list of string messages to be sent. Each message represents a textual part or
 *                 content to be included in the communication.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun A2AAgentServer.sendTextTaskMessages(
    messages: List<String>
) {
    // For immediate responses (like chatbots)
    eventProcessor.sendMessage(
        Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = messages.map { TextPart(it) },
            contextId = context.contextId,
            taskId = context.taskId
        )
    )
}

suspend fun A2AAgentServer.sendTextTaskMessage(
    message: String
) = sendTextTaskMessages(listOf(message))

/**
 * Sends a JSON object as a task message to the event processor.
 *
 * This function is used for sending immediate responses, such as those required in chatbot scenarios.
 *
 * @param json The primary JSON object that represents the content of the task message.
 * @param metadata An optional JSON object containing metadata associated with the task message.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun A2AAgentServer.sendJsonObjectTaskMessage(
    json: JsonObject,
    metadata: JsonObject? = null
) {
    // For immediate responses (like chatbots)
    eventProcessor.sendMessage(
        Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(DataPart(json, metadata = metadata)),
            contextId = context.contextId,
            taskId = context.taskId
        )
    )
}

/**
 * Sends a list of files through the A2AAgentServer to be processed.
 *
 * @param files A list of `File` objects to be sent.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun A2AAgentServer.sendFiles(
    files: List<File>
) {
    // For immediate responses (like chatbots)
    eventProcessor.sendMessage(
        Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = files.map { FilePart(it) },
            contextId = context.contextId,
            taskId = context.taskId
        )
    )
}

suspend fun A2AAgentServer.sendFile(
    file: File
) = sendFiles(listOf(file))

/**
 * Parses the list of configuration parameters to extract and construct a list of `AgentSkill` objects.
 *
 * This method specifically looks for a configuration parameter with the key `agent_skills`, interprets
 * its value as a JSON array, and converts each element into an `AgentSkill` object. Each skill's
 * attributes, such as id, name, description, tags, examples, input modes, and output modes, are
 * extracted from the JSON structure.
 *
 * @return A list of `AgentSkill` objects constructed based on the configuration parameters.
 */
private fun List<ConfigurationParameter>.agentSkills(): List<AgentSkill> {
    fun JsonElement.stringArray(key: String) =
        jsonObject[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toList() ?: emptyList()

    val skills = mutableListOf<AgentSkill>()
    val jsonSkills = this.parameter("agent_skills", JsonArray(emptyList()))
    jsonSkills.forEach { skill ->
        skills.add(
            AgentSkill(
                id = skill.jsonObject["id"]?.jsonPrimitive?.content ?: "",
                name = skill.jsonObject["name"]?.jsonPrimitive?.content ?: "",
                description = skill.jsonObject["description"]?.jsonPrimitive?.content ?: "",
                tags = skill.jsonObject["tags"]?.jsonArray?.map { it.jsonPrimitive.content }?.toList() ?: emptyList(),
                examples = skill.stringArray("examples"),
                inputModes = skill.stringArray("inputModes"),
                outputModes = skill.stringArray("outputModes"),
            )
        )
    }
    return skills
}

/**
 * Creates an `AgentCard` object using a list of configuration parameters.
 *
 * The method uses the provided configuration parameters to construct the required attributes
 * for an `AgentCard`, such as name, description, URL, version, capabilities, skills, and more.
 *
 * @param parameters A list of `ConfigurationParameter` objects containing key-value pairs
 *                   used to configure the agent's attributes.
 * @return An `AgentCard` instance populated with the attributes extracted and derived
 *         from the input configuration parameters.
 */
private fun createAgentCard(
    parameters: List<ConfigurationParameter>
): AgentCard = with(
    AgentCard(
        name = parameters.parameter("a2a_agent_name", "Braidrun A2A Agent"),
        description = parameters.parameter("a2a_agent_description", "An A2A agent powered by Braidrun"),
        url = parameters.parameter<String>(
            "a2a_agent_url",
            "http://localhost:${parameters.parameter("port", 9999)}/${parameters.parameter("a2a_agent_path", "a2a")}"
        ),
        version = parameters.parameter("a2a_agent_version", "1.0.0"),
        protocolVersion = parameters.parameter("a2a_agent_protocol_version", "0.3.0"),
        preferredTransport = TransportProtocol.JSONRPC,

        // Capabilities Declaration
        capabilities =
            AgentCapabilities(
                streaming = parameters.parameter(
                    "a2a_agent_capability_streaming",
                    false
                ),              // Support real-time responses
                pushNotifications = parameters.parameter(
                    "a2a_agent_capability_push_notifications",
                    false
                ),      // Send async notifications
                stateTransitionHistory = parameters.parameter(
                    "a2a_agent_capability_state_transition_history",
                    false
                ),  // Maintain task history
                extensions = parameters.parameter("a2a_agent_capability_extensions", null)
            ),

        // Content Type Support
        defaultInputModes = parameters.oneOfParameterCollection(
            "a2a_agent_input_modes",
            listOf("text/plain"),
            listOf("text/plain", "text/markdown", "image/jpeg")
        ),
        defaultOutputModes = parameters.oneOfParameterCollection(
            "a2a_agent_output_modes",
            listOf("text/plain"),
            listOf("text/plain", "text/markdown", "image/jpeg")
        ),

        // Agent Skills
        skills = parameters.agentSkills(),

        iconUrl = parameters.parameter("a2a_agent_icon_url", "https://localhost:/a2a/logo.png"),
        documentationUrl = parameters.parameter("a2a_agent_documentation_url", "https://localhost:/a2a/docs"),
        provider = AgentProvider(
            organization = parameters.parameter("a2a_agent_organization", "Braidrun"),
            url = parameters.parameter("a2a_agent_provider_url", "https://braidrun.ai")
        ),

        // Additional Interfaces
        additionalInterfaces = parameters.parameter("a2a_agent_additional_interfaces", emptyList())
    )
) {
    val securitySchemes = parameters.parameter("a2a_security_schemes", A2ASecurityScheme(null, null))
    if (securitySchemes.bearer != null || securitySchemes.apiKey != null) {
        return this.copy(
            securitySchemes = mutableMapOf<String, SecurityScheme>().also {
                if (securitySchemes.bearer != null) it["bearer"] = securitySchemes.bearer
                if (securitySchemes.apiKey != null) it["api-key"] = securitySchemes.apiKey
            },
            security = mutableListOf<Map<String, List<String>>>().also {
                if (securitySchemes.bearer != null) it.add(
                    mapOf(
                        "bearer" to parameters.parameter(
                            "a2a_bearer_allowed_methods",
                            listOf()
                        )
                    )
                )
                if (securitySchemes.apiKey != null) it.add(
                    mapOf(
                        "api-key" to parameters.parameter(
                            "a2a_api_key_list",
                            listOf()
                        )
                    )
                )
            },
            supportsAuthenticatedExtendedCard = true
        )
    }
    return this
}

/**
 * Starts an A2A agent server with the provided configuration, executor, and agent card.
 *
 * @param parameters A list of configuration parameters to initialize the server, including port and path information.
 * @param agentExecutor The executor responsible for executing agent-specific operations.
 * @return An instance of the A2AServer initialized with the provided parameters and ready to handle requests.
 */
suspend fun startA2AgentServer(
    parameters: List<ConfigurationParameter>,
    agentExecutor: AgentExecutor
) {
    // Server setup
    val server = A2AServer(agentExecutor = agentExecutor, agentCard = createAgentCard(parameters))
    val transport = HttpJSONRPCServerTransport(server)
    transport.start(
        engineFactory = CIO,
        port = parameters.parameter("port", 9999),
        path = "/${parameters.parameter("a2a_agent_path", "a2a")}",
        agentCardPath = "/${parameters.parameter("a2a_agent_card_path", A2AConsts.AGENT_CARD_WELL_KNOWN_PATH)}"
    )
}


/**
 * BraidrunA2AExecutor is a generic class responsible for executing tasks in an Agent-to-Agent (A2A)
 * communication setup. It facilitates the handling of message exchanges between agents within an AIAgent
 * subgraph by processing input parameters, managing prompt contexts, and invoking the required tools
 * from the tool registry.
 *
 * @param Output The type of output produced as a result of the execution.
 * @property httpAccess An instance of HttpAccess to handle HTTP-related operations required during execution.
 * @property parameters A list of ConfigurationParameter objects that define settings or inputs
 *                       for the execution context.
 * @property toolRegistry An instance of ToolRegistry that maintains and provides the tools required
 *                        for task execution.
 * @property systemPrompt A string used as the system-level prompt to guide agent behavior during interactions.
 * @property graph An AIAgentSubgraphDelegate instance that represents the subgraph for managing the
 *                 lifecycle and processing of messages within the A2A system.
 */
class BraidrunA2AExecutor(
    private val httpAccess: HttpAccess,
    private val parameters: List<ConfigurationParameter>,
    private val toolRegistry: ToolRegistry,
    private val systemPrompt: String,
    private val strategy: AIAgentGraphStrategy<A2AMessage, String>? = null,
    private val graph: AIAgentSubgraphDelegate<Task, String>? = null
) : AgentExecutor {
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val agent = buildAgent<A2AMessage, String>(
            httpAccess,
            parameters,
            systemPrompt,
            toolRegistry,
            installFeatures = {
                defaultInstallFeatures()
                install(A2AAgentServer) {
                    this.context = context
                    this.eventProcessor = eventProcessor
                }
            }
        ) { _, _, _ ->
            strategy ?: strategy<A2AMessage, String>(
                name = "__a2a_default_strategy__"
            ) {
                // Node: Load conversation history from message storage
                val setupMessageContext by node<A2AMessage, A2AMessage> { userInput ->
                    if (!userInput.referenceTaskIds.isNullOrEmpty()) {
                        throw A2AUnsupportedOperationException("This agent doesn't understand task references in referenceTaskIds yet.")
                    }

                    // Load current context messages
                    val contextMessages: List<A2AMessage> = withA2AAgentServer {
                        context.messageStorage.getAll()
                    }

                    // Append the current context messages to prompt
                    llm.writeSession {
                        appendPrompt {
                            messages(contextMessages.map { it.toKoogMessage() })
                        }
                    }
                    userInput
                }

                // Node: Load an existing task (if continuing) or prepare for new task creation
                val setupTaskContext by node<A2AMessage, Task> { userInput ->
                    // Check if the message continues the task that already exists
                    val currentTask: Task? = withA2AAgentServer {
                        context.task?.id?.let { id ->
                            // Load task with full conversation history to continue working on it
                            context.taskStorage.get(id, historyLength = null)
                        }
                    }

                    currentTask?.let { task ->
                        val currentTaskMessages =
                            (task.history.orEmpty() + listOfNotNull(task.status.message) + userInput)
                                .map { it.toKoogMessage() }
                        llm.writeSession {
                            appendPrompt {
                                user {
                                    +"There's an ongoing task, the next messages contain conversation history for this task"
                                }
                                messages(currentTaskMessages)
                            }
                        }
                        currentTask
                    } ?: Task(
                        contextId = context.contextId,
                        id = context.taskId,
                        status = TaskStatus(
                            state = TaskState.Submitted,
                            message = userInput,
                            timestamp = kotlin.time.Clock.System.now(),
                        )
                    ).also {
                        llm.writeSession {
                            appendPrompt {
                                messages(listOf(userInput.toKoogMessage()))
                            }
                        }
                        withA2AAgentServer {
                            // Koog 1.0.0 — `.content` → `.textContent()`
                            sendTaskUpdate(
                                it,
                                userInput.toKoogMessage().textContent(),
                                TaskState.Submitted
                            )
                        }
                    }
                }

                val performToolExecution by graph ?: toolCycleGraph<Task, String>(
                    name = "__a2a_tool_cycle_graph__",
                    inputPrompt = suspend { task, context ->
                        context.withA2AAgentServer {
                            sendTaskUpdate(
                                task,
                                // Koog 1.0.0 — `.content` → `.textContent()`
                                task.status.message?.toKoogMessage()?.textContent() ?: "",
                                TaskState.Working
                            )
                        }
                        "Now call available tools to complete the task with user inputs."
                    },
                    outputCompute = suspend { resp, context ->
                        context.withA2AAgentServer {
                            val task = this.context.taskStorage.get(this.context.taskId)
                            ?: error("Task ${this.context.taskId} not found in storage")
                            // Koog 1.0.0 — `Message.Assistant.content` → `.textContent()`
                            val text = resp.textContent()
                            sendTaskUpdate(
                                task,
                                text,
                                TaskState.Completed,
                                final = true
                            )
                            text
                        }
                    },
                    // Koog 1.0.0 — ToolRegistry.tools widened to List<ToolBase>; filter to Tool.
                    tools = toolRegistry.tools.filterIsInstance<ai.koog.agents.core.tools.Tool<*, *>>()
                )
                nodeStart then setupMessageContext then setupTaskContext then performToolExecution then nodeFinish
            }
        }
        try {
            agent.run(context.params.message)
        } finally {
            // Mirror buildAndRunAgent's close-in-finally discipline: each A2A request
            // builds an equally heavy agent (LLM clients, MCP-augmented registry);
            // without close() those resources leak per inbound request.
            runCatching { agent.close() }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun cancel(
        context: RequestContext<TaskIdParams>,
        eventProcessor: SessionEventProcessor,
        agentJob: Deferred<Unit>?
    ) {
        agentJob?.cancel()
        eventProcessor.sendTaskEvent(
            TaskStatusUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                status = TaskStatus(
                    state = TaskState.Canceled,
                    message = Message(
                        messageId = Uuid.random().toString(),
                        role = Role.Agent,
                        parts = listOf(TextPart("Task cancelled")),
                        contextId = context.contextId,
                        taskId = context.taskId
                    ),
                    timestamp = kotlin.time.Clock.System.now(),
                ),
                final = true
            )
        )
    }
}
