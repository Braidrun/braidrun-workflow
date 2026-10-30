package com.fartech.agents.runner

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/** Pure Koog 1.3 adapter; the trusted host selects this BEFORE cloud client/key preparation.
 * No provider factory, cache, retry, fallback, tools, schema generation or model discovery is used.
 * The optional JSON schema and output validator must come from the verified frozen policy.
 * checkLive is the host business/lease gate and is rechecked immediately before publication.
 */
class RunnerLocalModelPromptExecutor internal constructor(
    private val binding: RunnerLocalModelBinding,
    private val stepName: String,
    childExecutionPath: List<String>,
    private val complete: suspend (RunnerLocalModelRequest, (String) -> Unit) -> RunnerLocalModelResult,
    private val closeTransport: () -> Unit,
    private val checkLive: () -> Unit,
    private val validateOutput: (String) -> Unit,
    private val approvedSchema: LLMParams.Schema? = null,
) : PromptExecutor() {
    constructor(
        binding: RunnerLocalModelBinding,
        stepName: String,
        childExecutionPath: List<String>,
        client: RunnerLocalModelClient,
        checkLive: () -> Unit,
        validateOutput: (String) -> Unit,
        approvedSchema: LLMParams.Schema? = null,
        onClose: () -> Unit = {},
    ) : this(binding, stepName, childExecutionPath, client::complete, { try { client.close() } finally { onClose() } }, checkLive, validateOutput, approvedSchema) {
        require(client.binding == binding) { "Local transport binding differs from the approved prompt binding" }
    }

    private val childPath = childExecutionPath.toList()
    private val closed = AtomicBoolean()
    val localModel = LLModel(
        provider = LLMProvider.Ollama,
        id = binding.model,
        capabilities = listOfNotNull(LLMCapability.Completion, approvedSchema?.capability),
        contextLength = binding.contextTokens.toLong(),
        maxOutputTokens = binding.maximumOutputTokens.toLong(),
    )

    init {
        require(stepName.isNotBlank() && stepName.length <= 256 && childPath.size <= 32 && childPath.all { it.isNotBlank() && it.length <= 256 }) { "Local model producer scope invalid" }
        val schema = when (approvedSchema) {
            null -> null
            is LLMParams.Schema.JSON.Basic -> approvedSchema.schema
            is LLMParams.Schema.JSON.Standard -> approvedSchema.schema
            else -> error("Local model schema dialect not approved")
        }
        require(schema == null || schema["type"] == JsonPrimitive("object")) { "Local structured output must be an approved JSON object" }
    }

    private fun check(model: LLModel = localModel) {
        check(!closed.get()) { "Local prompt executor closed" }
        checkLive()
        require(model == localModel) { "Local model must exactly match the frozen model; no fallback" }
    }

    override suspend fun resolveModel(model: LLModel, promptExecutorOperation: PromptExecutorOperation): ResolvedModel {
        check(model)
        require(promptExecutorOperation in setOf(PromptExecutorOperation.Execute, PromptExecutorOperation.Streaming)) { "Local model operation not admitted" }
        return ResolvedModel(localModel)
    }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        currentCoroutineContext().ensureActive()
        check(model)
        require(tools.isEmpty()) { "Local P1 tools are not admitted" }
        val params = prompt.params
        require(params::class == LLMParams::class && params.additionalProperties.isNullOrEmpty() && params.speculation == null && params.user == null) { "Local model parameters not admitted" }
        require(params.temperature == null || params.temperature == 0.0) { "Local temperature must match the approved fixed value 0" }
        require(params.numberOfChoices == null || params.numberOfChoices == 1) { "Local multiple choices not admitted" }
        require(params.toolChoice == null || params.toolChoice == LLMParams.ToolChoice.None) { "Local tool choice not admitted" }
        require(params.schema == approvedSchema) { "Local output schema differs from approved policy" }
        val outputTokens = params.maxTokens ?: binding.maximumOutputTokens
        require(outputTokens in 1..binding.maximumOutputTokens) { "Local output tokens exceed frozen limit" }
        val messages = prompt.messages.map { message ->
            val role = when (message) {
                is Message.System -> "system"
                is Message.User -> "user"
                is Message.Assistant -> "assistant"
                else -> error("Local P1 message role not admitted")
            }
            require(message.parts.all { it is MessagePart.Text && it.cacheControl == null }) { "Local P1 requires plain text; media, tools and reasoning are not admitted" }
            RunnerLocalMessage(role, message.parts.joinToString("") { (it as MessagePart.Text).text })
        }
        val request = RunnerLocalModelRequest(stepName, messages, outputTokens, approvedSchema != null, childPath)
        val result = complete(request, validateOutput)
        currentCoroutineContext().ensureActive()
        check(model)
        require(result.model == binding.model && result.digest == binding.digest && result.serviceVersion == binding.serviceVersion) { "Local result identity changed" }
        require(result.content.isNotBlank()) { "Local response is empty" }
        fun tokenCount(value: Long?, maximum: Int): Int? {
            require(value == null || value in 0..maximum.toLong()) { "Local response usage exceeds frozen limit" }
            return value?.toInt()
        }
        val input = tokenCount(result.promptTokens, binding.contextTokens)
        val output = tokenCount(result.outputTokens, outputTokens)
        val total = if (input != null && output != null) input + output else null
        require(total == null || total <= binding.contextTokens) { "Local response context exceeded" }
        val metadata = buildJsonObject {
            put("braidrun_model_execution", "local")
            put("model_digest", binding.digest)
            put("service_version", binding.serviceVersion)
            put("endpoint_id", binding.endpointId)
            put("local_request_id", result.requestId)
            put("elapsed_nanos", result.elapsedNanos)
            result.firstTokenNanos?.let { put("first_token_nanos", it) }
        }
        return Message.Assistant(content = result.content, finishReason = "stop", metaInfo = ResponseMetaInfo.create(
            KoogClock.System, totalTokensCount = total, inputTokensCount = input, outputTokensCount = output,
            modelId = binding.model, metadata = metadata,
        ))
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
        // Buffer until identity, output validation and independent stop proof have succeeded.
        // The one End carries usage once; no token counts are invented when the service omits them.
        val result = execute(prompt, model, tools)
        check(model)
        emit(StreamFrame.TextDelta(result.textContent()))
        check(model)
        emit(StreamFrame.End(finishReason = "stop", metaInfo = result.metaInfo))
    }

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        error("Local multiple choices not admitted")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Local moderation not admitted")
    override suspend fun models(): List<LLModel> { check(); return listOf(localModel) }
    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator = error("Local schema must be frozen before execution")
    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator = error("Local schema must be frozen before execution")
    override fun close() { if (closed.compareAndSet(false, true)) closeTransport() }
}
