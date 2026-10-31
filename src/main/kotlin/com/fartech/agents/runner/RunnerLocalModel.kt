package com.fartech.agents.runner

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Frozen by the trusted host, never deserialized from an Agent's arbitrary parameters.
 * Native bindings use an opaque native:// identity; legacy Ollama bindings retain loopback validation.
 * No credentials, arbitrary headers, provider fallback or model-management API exists here.
 */
data class RunnerLocalModelBinding(
    val runnerId: String,
    val ownerId: String,
    val executionId: String,
    val attemptId: String,
    val endpointId: String,
    val endpoint: String,
    val serviceVersion: String,
    val model: String,
    val digest: String,
    val contextTokens: Int,
    val maximumOutputTokens: Int,
) {
    init {
        require(listOf(runnerId, ownerId, executionId, attemptId, endpointId).all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")) }) { "Local model binding identity invalid" }
        require(endpoint == "native://$endpointId" || ENDPOINT.matches(endpoint) && endpoint.substringAfterLast(':').toIntOrNull()?.let { it in 1..65535 } == true) { "Local model endpoint must be canonical http://127.0.0.1:<port>" }
        require(serviceVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))) { "Local service version must be pinned" }
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*:[A-Za-z0-9][A-Za-z0-9._-]*")) && !model.contains("cloud", ignoreCase = true)) { "Local model requires an explicit non-cloud tag" }
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "Local model digest must be SHA-256" }
        require(contextTokens in 1..32768 && maximumOutputTokens in 1..4096 && maximumOutputTokens < contextTokens) { "Local model token limits invalid" }
        require(!native || contextTokens in 512..8192 && maximumOutputTokens <= 1024) { "Native model token limits invalid" }
    }
    val native: Boolean get() = endpoint == "native://$endpointId"
    val provider: ai.koog.prompt.llm.LLMProvider get() = if (native) RunnerNativeModelProtocol.provider else ai.koog.prompt.llm.LLMProvider.Ollama
    companion object { private val ENDPOINT = Regex("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}") }
}

data class RunnerLocalMessage(val role: String, val content: String) {
    init { require(role in setOf("system", "user", "assistant")) { "Local text transport does not admit tool messages" } }
}

data class RunnerLocalModelRequest(
    val stepName: String,
    val messages: List<RunnerLocalMessage>,
    val outputTokens: Int,
    val jsonObject: Boolean = false,
    val childExecutionPath: List<String> = emptyList(),
)

/** This authority must be provided by the supervisor's binding/resource journal, not workflow code.
 * reserve must atomically reserve ONE slot across all attempts using this service, check <=30s health,
 * P0 approval, resource headroom and actual prompt token count (including the service chat template).
 * It must hold a model/configuration lock until finish, and reject unknown state after a restart.
 * Waiting is cancellable and limited by the caller to 120s. A failed/cancelled reserve must roll back.
 */
interface RunnerLocalModelAuthority {
    suspend fun reserve(binding: RunnerLocalModelBinding, requestId: String, request: RunnerLocalModelRequest): RunnerLocalModelGrant
}

interface RunnerLocalModelGrant {
    val binding: RunnerLocalModelBinding
    val requestId: String
    /** Checks current lease, owner/Runner/attempt, health freshness and resource reservation. */
    fun check()
    /** Persist before any inference request may leave the engine. An ambiguous send is still sent. */
    suspend fun markInferenceMayHaveStarted()
    /** Persist resolution and release once, or quarantine before returning false. A closed socket is
     * NOT stop evidence. No default implementation is supplied: a host without stop proof must refuse
     * integration. The reservation journal must also quarantine on timeout/crash/no finish message. */
    suspend fun finish(observation: RunnerLocalStopObservation): Boolean
}

enum class RunnerLocalStopObservation { NOT_SENT, SERVICE_REPORTED_DONE, STOP_UNCONFIRMED }

data class RunnerLocalModelResult(
    val content: String,
    val model: String,
    val digest: String,
    val serviceVersion: String,
    val requestId: String,
    val firstTokenNanos: Long?,
    val elapsedNanos: Long,
    val promptTokens: Long?,
    val outputTokens: Long?,
)

/** Separate local-only transport. It does not modify RunnerHttpClients/LlmEndpointPolicy, the
 * host's cloud client factory, admission or advertised capabilities. Integration is intentionally
 * fail-closed until the supervisor implements the authority contract and P0/P1 acceptance passes.
 */
class RunnerLocalModelClient(
    val binding: RunnerLocalModelBinding,
    private val gate: RunnerEnvironmentGate,
    private val fence: RunnerBusinessFence,
    private val authority: RunnerLocalModelAuthority,
) : AutoCloseable {
    init { require(!binding.native) { "Native models require supervisor IPC" } }
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    @Volatile private var closed = false
    private val client = transportBuilder().build()

    suspend fun complete(input: RunnerLocalModelRequest, validateOutput: (String) -> Unit): RunnerLocalModelResult = fence.operation {
        checkOpen()
        val request = input.copy(messages = input.messages.toList(), childExecutionPath = input.childExecutionPath.toList())
        require(request.stepName.isNotBlank() && request.messages.isNotEmpty() && request.messages.size <= 256) { "Local model request invalid" }
        require(request.childExecutionPath.size <= 32 && request.childExecutionPath.all { it.isNotBlank() && it.length <= 256 }) { "Local model child scope invalid" }
        require(request.outputTokens in 1..binding.maximumOutputTokens) { "Local output limit exceeds binding" }
        require(request.messages.sumOf { it.content.length.toLong() } <= MAX_REQUEST_BYTES) { "Local prompt exceeds transport limit" }
        // This byte cap is a transport bound, NOT a token estimate; authority must validate real tokens.
        val payload = payload(request)
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "Local prompt exceeds transport limit" }
        val requestId = UUID.randomUUID().toString()
        val grant = withTimeout(120_000) { authority.reserve(binding, requestId, request) }
        // Never act on (including releasing) a reservation belonging to another attempt. The
        // supervisor must recover/quarantine an unresolved reservation from its durable journal.
        require(grant.binding == binding && grant.requestId == requestId) { "Local model grant does not match frozen binding" }
        var observation = RunnerLocalStopObservation.NOT_SENT
        var finished = false
        try {
            val started = System.nanoTime()
            // P1 hot-inference budget. Cold-load admission is a separate, still-unwired contract.
            val result = withTimeout(HOT_TIMEOUT_MILLIS) {
                verifyIdentity(grant)
                checkGrant(grant)
                // Journal before the network boundary; even cancellation during marking is uncertain.
                observation = RunnerLocalStopObservation.STOP_UNCONFIRMED
                grant.markInferenceMayHaveStarted()
                val output = exchange("/api/chat", payload, grant) { response -> parseChat(response, started, grant) }
                observation = RunnerLocalStopObservation.SERVICE_REPORTED_DONE
                verifyIdentity(grant)
                checkGrant(grant)
                require(output.outputTokens == null || output.outputTokens in 0..request.outputTokens.toLong()) { "Local model exceeded output budget" }
                require(output.promptTokens == null || output.promptTokens in 0..binding.contextTokens.toLong()) { "Local model exceeded context budget" }
                if (output.promptTokens != null && output.outputTokens != null) {
                    require(output.promptTokens + output.outputTokens <= binding.contextTokens) { "Local model exceeded context budget" }
                }
                if (request.jsonObject) require(runnerJson.parseToJsonElement(output.content) is JsonObject) { "Local model did not produce a JSON object" }
                validateOutput(output.content)
                checkGrant(grant)
                output.copy(requestId = requestId, elapsedNanos = System.nanoTime() - started)
            }
            // No result can escape until the supervisor confirms slot resolution; an uncertain finish
            // is an execution failure even when the service returned a syntactically valid answer.
            finished = true
            val confirmed = withContext(NonCancellable) { withTimeout(30_000) { grant.finish(observation) } }
            check(confirmed) { "Local model stop unconfirmed; inference slot quarantined" }
            checkOpen()
            currentCoroutineContext().ensureActive()
            result
        } finally {
            if (!finished) withContext(NonCancellable) {
                // The authority's durable reservation must remain quarantined if this fails/times out.
                runCatching { withTimeout(30_000) { grant.finish(observation) } }
            }
        }
    }

    private fun payload(request: RunnerLocalModelRequest): String = buildJsonObject {
        put("model", binding.model)
        putJsonArray("messages") { request.messages.forEach { message -> add(buildJsonObject { put("role", message.role); put("content", message.content) }) } }
        put("stream", true)
        put("think", false)
        if (request.jsonObject) put("format", "json")
        putJsonObject("options") { put("num_ctx", binding.contextTokens); put("num_predict", request.outputTokens); put("temperature", 0) }
    }.toString()

    private suspend fun verifyIdentity(grant: RunnerLocalModelGrant) {
        val version = json("/api/version", grant)
        require(version["version"]?.jsonPrimitive?.content == binding.serviceVersion) { "Local model service version changed" }
        val rows = json("/api/tags", grant)["models"]?.jsonArray ?: error("Local model inventory unavailable")
        val matches = rows.map { it.jsonObject }.filter { it["name"]?.jsonPrimitive?.content == binding.model }
        require(matches.size == 1 && matches.single()["digest"]?.jsonPrimitive?.content == binding.digest) { "Local model missing or digest changed" }
        require(matches.single().keys.none { it.startsWith("remote_") }) { "Remote Ollama model is not admitted" }
        val details = json("/api/show", grant, buildJsonObject { put("model", binding.model) }.toString())
        require(details.keys.none { it.startsWith("remote_") && details[it] != JsonNull && details[it] != JsonPrimitive("") }) { "Remote Ollama model is not admitted" }
        require(details["capabilities"]?.jsonArray?.any { it.jsonPrimitive.content == "completion" } == true) { "Local model has no text completion capability" }
    }

    private suspend fun json(path: String, grant: RunnerLocalModelGrant, payload: String? = null): JsonObject = exchange(path, payload, grant) { response ->
        val bytes = requireNotNull(response.body).byteStream().readNBytes(MAX_IDENTITY_BYTES + 1)
        require(bytes.size <= MAX_IDENTITY_BYTES) { "Local model identity response oversized" }
        runnerJson.parseToJsonElement(decodeUtf8(bytes)).jsonObject
    }

    private suspend fun <T> exchange(path: String, payload: String?, grant: RunnerLocalModelGrant, read: (okhttp3.Response) -> T): T = coroutineScope {
        require(path in setOf("/api/version", "/api/tags", "/api/show", "/api/chat") && (payload != null) == (path in setOf("/api/show", "/api/chat")))
        checkGrant(grant)
        val request = okhttp3.Request.Builder().url(binding.endpoint + path).apply {
            if (payload == null) get() else post(payload.toRequestBody("application/json".toMediaType()))
        }.build()
        val call = client.newCall(request)
        synchronized(active) { checkGrant(grant); active.add(call) }
        val watcher = launch(Dispatchers.Default) {
            try { while (isActive) { checkGrant(grant); delay(50) } }
            finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                checkGrant(grant)
                try {
                    call.execute().use { response ->
                        require(response.code == 200) { "Local model request refused, failed or redirected" }
                        read(response).also { checkGrant(grant) }
                    }
                } catch (failure: java.io.IOException) {
                    // OkHttp reports call.cancel() as IOException. Preserve coroutine cancellation
                    // so a caller cannot mistake an intentional stop for a retriable transport error.
                    currentCoroutineContext().ensureActive()
                    throw failure
                }
            }
        } finally {
            call.cancel(); watcher.cancel(); active.remove(call)
            withContext(NonCancellable) { watcher.join() }
        }
    }

    private fun parseChat(response: okhttp3.Response, started: Long, grant: RunnerLocalModelGrant): RunnerLocalModelResult {
        val body = requireNotNull(response.body)
        require(body.contentLength() <= MAX_RESPONSE_BYTES) { "Local model response oversized" }
        val source = body.source()
        val output = StringBuilder()
        var totalBytes = 0L
        var final: JsonObject? = null
        var firstToken: Long? = null
        while (!source.exhausted()) {
            checkGrant(grant)
            val newline = source.indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES.toLong() + 1)
            require(newline >= 0) { "Local model stream line missing or oversized" }
            val bytes = source.readByteArray(newline + 1)
            val line = decodeUtf8(bytes).trimEnd('\n', '\r')
            totalBytes += bytes.size
            require(totalBytes <= MAX_RESPONSE_BYTES) { "Local model response oversized" }
            if (line.isBlank()) continue
            require(final == null) { "Local model emitted data after terminal response" }
            val value = runnerJson.parseToJsonElement(line).jsonObject
            require("error" !in value && value["model"]?.jsonPrimitive?.content == binding.model) { "Local model response identity or status invalid" }
            val message = value["message"]?.jsonObject ?: error("Local model message missing")
            require(message["role"]?.jsonPrimitive?.content == "assistant" && "tool_calls" !in message && "images" !in message) { "Local response exceeds text-only capability" }
            val text = message["content"]?.jsonPrimitive?.content ?: error("Local model content missing")
            if (text.isNotEmpty() && firstToken == null) firstToken = System.nanoTime() - started
            output.append(text)
            if (value["done"]?.jsonPrimitive?.booleanOrNull == true) final = value
        }
        require(final != null && final["done_reason"]?.jsonPrimitive?.content == "stop" && output.isNotBlank()) { "Local model response incomplete, empty or truncated" }
        return RunnerLocalModelResult(output.toString(), binding.model, binding.digest, binding.serviceVersion, "", firstToken,
            0, final["prompt_eval_count"]?.jsonPrimitive?.longOrNull, final["eval_count"]?.jsonPrimitive?.longOrNull)
    }

    private fun checkOpen() { check(!closed) { "Local model transport closed" }; fence.check(); gate.check() }
    private fun checkGrant(grant: RunnerLocalModelGrant) { checkOpen(); grant.check() }
    override fun close() {
        synchronized(active) { closed = true; active.forEach { it.cancel() } }
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }
    companion object {
        private const val MAX_REQUEST_BYTES = 128 * 1024
        private const val MAX_IDENTITY_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val MAX_LINE_BYTES = 64 * 1024
        private fun decodeUtf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        internal const val HOT_TIMEOUT_MILLIS = 120_000L
        internal fun transportBuilder() = okhttp3.OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(HOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).callTimeout(HOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }
}
