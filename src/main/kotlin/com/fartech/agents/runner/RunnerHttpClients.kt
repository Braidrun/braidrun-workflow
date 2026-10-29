package com.fartech.agents.runner

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.HttpHostSafety
import com.fartech.ftapp2.commonsKt.HttpSingleDispatch
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

/** A uniform OkHttp network path: direct, or through the single fixed HTTP proxy the supervisor
 * declared for this attempt (system property `braidrun.runner.proxy`). PAC, split routes and private
 * Agent proxies are still refused. The supervisor attests jvm-http through this same path; another
 * client's egress receipt cannot authorize model/connector requests.
 */
class RunnerHttpClients private constructor(private val gate: RunnerEnvironmentGate, private val credentialCheck: () -> Unit, singleDispatch: Boolean) : AutoCloseable {
    constructor(gate: RunnerEnvironmentGate, credentialCheck: () -> Unit = {}) : this(gate, credentialCheck, false)
    /** Uses HttpAccess's SSRF redirect loop, then our gate for the initial request and every hop. */
    val toolHttpAccess = HttpAccess.withOkHttpConfig { builder ->
        configureDirect(builder) {
            gate.requirePaths(setOf("jvm-http")); credentialCheck()
        }
    }
    /** Web tools own the bounded redirect loop and need the effective URL for HTML relative links. */
    val webHttpAccess = HttpAccess.withOkHttpConfig({ it.followRedirects = false }) { builder ->
        builder.interceptors().remove(HttpAccess.SsrfRedirectInterceptor)
        HttpSingleDispatch.install(builder)
        configureDirect(builder) {
            gate.requirePaths(setOf("jvm-http")); credentialCheck()
        }
    }
    val client = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
        engine { config {
            if (singleDispatch) HttpSingleDispatch.install(this)
            configureDirect(this) {
                gate.requirePaths(setOf("jvm-http")); credentialCheck()
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
    override fun close() { toolHttpAccess.close(); webHttpAccess.close(); client.close() }
    companion object {
        /** Child Agent calls cannot repeat an exchange after an ambiguous provider response. */
        internal fun singleDispatch(gate: RunnerEnvironmentGate, credentialCheck: () -> Unit) = RunnerHttpClients(gate, credentialCheck, true)
        const val DECLARED_PROXY_PROPERTY = "braidrun.runner.proxy"
        /** The fixed HTTP(S) proxy the supervisor declared for every adapted client of this attempt, if any. */
        fun declaredProxy(properties: java.util.Properties = System.getProperties()): java.net.URI? {
            val raw = properties.getProperty(DECLARED_PROXY_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val uri = java.net.URI(raw)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) { "Declared proxy must be a plain http(s)://host:port" }
            return uri
        }
        /** Runner policy never honors the library's private-URL development escape hatch. */
        internal fun strictDns(delegate: okhttp3.Dns = okhttp3.Dns.SYSTEM): okhttp3.Dns = okhttp3.Dns { hostname ->
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty()) throw java.net.UnknownHostException("Runner DNS returned no addresses")
            try {
                HttpHostSafety.assertResolvedAddressesPublic(hostname, addresses, allowPrivateOverride = false)
            } catch (error: SecurityException) {
                throw java.net.UnknownHostException("Runner DNS rejected a non-public destination").apply { initCause(error) }
            }
            addresses
        }
        /** Shared transport configuration for business clients and the bounded public probe. */
        internal fun configureDirect(builder: okhttp3.OkHttpClient.Builder, beforeRequest: (okhttp3.Request) -> Unit): okhttp3.OkHttpClient.Builder = builder.apply {
            val declared = declaredProxy()
            if (declared == null) {
                proxy(java.net.Proxy.NO_PROXY)
                dns(strictDns())
            } else {
                // Names resolve at the declared proxy; the literal-host check below still refuses private targets.
                proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved(declared.host, declared.port)))
            }
            retryOnConnectionFailure(false)
            followRedirects(false); followSslRedirects(false)
            addInterceptor { chain ->
                beforeRequest(chain.request())
                require(chain.request().url.username.isEmpty() && chain.request().url.password.isEmpty()) { "Runner URL credentials are not admitted" }
                HttpHostSafety.assertHostIsPublic(chain.request().url.host, allowPrivateOverride = false)
                chain.proceed(chain.request())
            }
        }
        /** Either no proxy anywhere, or exactly the declared fixed proxy in both JVM properties and the process environment. */
        fun requireDirect(environment: Map<String, String> = System.getenv(), properties: java.util.Properties = System.getProperties()) {
            val declared = declaredProxy(properties)
            val blankProperty = { name: String -> properties.getProperty(name).isNullOrBlank() || properties.getProperty(name) == "false" }
            require(listOf("NO_PROXY", "no_proxy").all { environment[it].isNullOrBlank() } && blankProperty("java.net.useSystemProxies") && blankProperty("socksProxyHost")) { "Split or system-selected proxy routes are not admitted" }
            if (declared == null) {
                require(listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy").all { environment[it].isNullOrBlank() } &&
                    listOf("http.proxyHost", "https.proxyHost").all(blankProperty)) { "JVM fixed/private proxy path is not yet admitted" }
            } else {
                val url = declared.toString().trimEnd('/')
                require(listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy").all { environment[it].isNullOrBlank() || environment[it]!!.trimEnd('/') == url }) { "Process proxy differs from the declared fixed proxy" }
                require(listOf("http.proxyHost", "https.proxyHost").all { properties.getProperty(it).isNullOrBlank() || properties.getProperty(it) == declared.host } &&
                    listOf("http.proxyPort", "https.proxyPort").all { properties.getProperty(it).isNullOrBlank() || properties.getProperty(it) == declared.port.toString() }) { "JVM proxy properties differ from the declared fixed proxy" }
            }
        }
    }
}
