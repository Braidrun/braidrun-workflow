package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowHostScope
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.HttpHostSafety
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** No platform business broker. This transport issues every API/refresh request in this JVM. */
fun interface RunnerConnectorTransport {
    suspend fun send(request: RunnerConnectorRequest): RunnerConnectorResponse
}
class RunnerConnectorRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
    val body: String? = null
) { override fun toString() = "RunnerConnectorRequest(<redacted>)" }
class RunnerConnectorResponse(val status: Int, val body: String) {
    override fun toString() = "RunnerConnectorResponse(status=$status, <redacted>)"
}

class LocalRunnerConnectorTransport(private val http: RunnerHttpClients, private val gate: RunnerEnvironmentGate) : RunnerConnectorTransport {
    override suspend fun send(request: RunnerConnectorRequest): RunnerConnectorResponse {
        gate.check()
        val url = Url(request.url)
        require(url.protocol == URLProtocol.HTTPS && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) { "Connector requires HTTPS without URL credentials" }
        HttpHostSafety.assertUrlIsPublic(request.url)
        val response = http.client.request(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (key, value) -> header(key, value) }
            if (request.form != null) setBody(FormDataContent(Parameters.build { request.form.forEach { (k,v) -> append(k,v) } }))
            else request.body?.let { setBody(it) }
        }
        // Redirects never forward bearer credentials to another authority.
        require(response.status.value !in 300..399) { "Connector redirect refused" }
        return RunnerConnectorResponse(response.status.value, response.bodyAsText())
    }
}

enum class RunnerOAuthMode { PUBLIC_NATIVE, USER_OWNED_CONFIDENTIAL, ACCESS_TOKEN_ONLY }

/** Provider endpoints and mode are trusted adapter configuration, not workflow-controlled URLs.
 * A provider using a platform-shared confidential client cannot construct an adapter here.
 */
class RunnerOAuthConnector(
    private val credentials: RunnerCredentials,
    private val transport: RunnerConnectorTransport,
    private val apiOrigin: String,
    private val tokenUrl: String,
    private val mode: RunnerOAuthMode,
    private val secrets: RunnerSecrets
) {
    init {
        require(Url(apiOrigin).protocol == URLProtocol.HTTPS && Url(tokenUrl).protocol == URLProtocol.HTTPS) { "Connector endpoints must be HTTPS" }
        require(Url(apiOrigin).user.isNullOrEmpty() && Url(apiOrigin).password.isNullOrEmpty() && Url(tokenUrl).user.isNullOrEmpty() && Url(tokenUrl).password.isNullOrEmpty()) { "URL credentials refused" }
    }
    suspend fun call(lease: RunnerCredentialLease, scope: WorkflowHostScope, method: String, relativePath: String, body: String? = null): RunnerConnectorResponse {
        val material = credentials.material(lease, scope, "connector.api")
        require(relativePath.startsWith("/") && !relativePath.startsWith("//") && !relativePath.contains('\\') &&
            !relativePath.contains('#') && !relativePath.contains("..")) { "Invalid provider-relative path" }
        val base = Url(apiOrigin); val url = Url(apiOrigin.trimEnd('/') + relativePath)
        require(url.host == base.host && url.port == base.port && url.protocol == base.protocol) { "Connector origin escape" }
        val expires = material["accessTokenExpiresAt"]?.jsonPrimitive?.content?.let(java.time.Instant::parse)
        require(expires == null || expires > java.time.Instant.now()) { "Access token expired; explicit local refresh required" }
        val response = transport.send(RunnerConnectorRequest(method, url.toString(), mapOf("Authorization" to "Bearer ${material.string("accessToken")}"), body = body))
        secrets.register(runCatching { runnerJson.parseToJsonElement(response.body).jsonObject["access_token"] ?: JsonNull }.getOrDefault(JsonNull))
        // No automatic retries of effects, and no server-side refresh on 401.
        return response
    }
    suspend fun refresh(lease: RunnerCredentialLease, scope: WorkflowHostScope) {
        require(mode != RunnerOAuthMode.ACCESS_TOKEN_ONLY) { "This connector cannot safely refresh" }
        val material = credentials.material(lease, scope, "oauth.refresh")
        val form = linkedMapOf("grant_type" to "refresh_token", "refresh_token" to material.string("refreshToken"), "client_id" to material.string("clientId"))
        if (mode == RunnerOAuthMode.USER_OWNED_CONFIDENTIAL) {
            credentials.material(lease, scope, "oauth.userOwnedClient")
            form["client_secret"] = material.string("clientSecret")
        } else {
            require("clientSecret" !in material) { "Public/native client cannot receive confidential secret" }
        }
        val response = transport.send(RunnerConnectorRequest("POST", tokenUrl, form = form))
        val payload = runCatching { runnerJson.parseToJsonElement(response.body).jsonObject }.getOrElse { error("OAuth response invalid") }
        // Register every new token before any exception/persistence callback.
        payload["access_token"]?.let(secrets::register); payload["refresh_token"]?.let(secrets::register); payload["id_token"]?.let(secrets::register)
        require(response.status in 200..299 && payload["access_token"]?.jsonPrimitive?.content?.isNotBlank() == true) { "Local OAuth refresh failed" }
        val rotated = JsonObject(material + mapOf("accessToken" to payload.getValue("access_token")) +
            (payload["refresh_token"]?.let { mapOf("refreshToken" to it) } ?: emptyMap()) +
            (payload["expires_in"]?.jsonPrimitive?.longOrNull?.let { mapOf("accessTokenExpiresAt" to JsonPrimitive(java.time.Instant.now().plusSeconds(it).toString())) } ?: emptyMap()))
        credentials.rotate(lease, scope, rotated)
    }
}
