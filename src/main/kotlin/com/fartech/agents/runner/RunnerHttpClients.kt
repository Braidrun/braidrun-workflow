package com.fartech.agents.runner

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.HttpHostSafety
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

/** A uniform, direct OkHttp network path. Fixed proxy/PAC/private Agent settings are refused
 * until matched by a separate probe/adapter. The supervisor must attest jvm-http using this
 * same path; another client's egress receipt cannot authorize model/connector requests.
 */
class RunnerHttpClients(private val gate: RunnerEnvironmentGate, private val credentialCheck: () -> Unit = {}) : AutoCloseable {
    val client = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
        engine { config {
            proxy(java.net.Proxy.NO_PROXY)
            retryOnConnectionFailure(false)
            followRedirects(false); followSslRedirects(false)
            dns(HttpAccess.SsrfValidatingDns)
            addInterceptor { chain ->
                gate.check(); credentialCheck()
                HttpHostSafety.assertUrlIsPublic(chain.request().url.toString())
                chain.proceed(chain.request())
            }
        } }
    }
    val factory: KoogHttpClient.Factory = object : KoogHttpClient.Factory {
        private val delegate = KtorKoogHttpClient.Factory(client)
        override fun create(clientName: String, baseUrl: String, headers: Map<String, String>, queryParameters: Map<String, String>,
                            requestTimeoutMillis: Long, connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: Json): KoogHttpClient {
            require(baseUrl.startsWith("https://")) { "Runner model endpoint must use TLS" }
            return delegate.create(clientName, baseUrl, headers, queryParameters, requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json)
        }
    }
    override fun close() { client.close() }
    companion object {
        fun requireDirect(environment: Map<String, String> = System.getenv(), properties: java.util.Properties = System.getProperties()) {
            require(listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy").all { environment[it].isNullOrBlank() } &&
                listOf("http.proxyHost", "https.proxyHost", "socksProxyHost", "java.net.useSystemProxies").all { properties.getProperty(it).isNullOrBlank() || properties.getProperty(it) == "false" }) {
                "JVM fixed/private proxy path is not yet admitted"
            }
        }
    }
}
