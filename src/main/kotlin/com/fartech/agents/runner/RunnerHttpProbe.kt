package com.fartech.agents.runner

import com.fartech.ftapp2.commonsKt.HttpHostSafety
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Public, credential-free observation only. It neither issues environment authority nor
 * accepts a runtime. Supervisor must bind a fresh observation to its own profile/attempt.
 * No arbitrary URL/headers/body or account configuration can be supplied to this entry point. */
object RunnerHttpProbe {
    const val ENDPOINT = "https://api.country.is/"
    internal const val MAX_BYTES = 4096

    fun observe(): JsonObject {
        RunnerHttpClients.requireDirect()
        val client = RunnerHttpClients.configureDirect(okhttp3.OkHttpClient.Builder()) { request ->
            RunnerHttpClients.requireDirect()
            require(request.url.toString() == ENDPOINT && request.method == "GET" && request.body == null) { "Probe purpose exceeded" }
            require(request.header("Authorization") == null && request.header("Cookie") == null) { "Probe cannot carry credentials" }
        }.callTimeout(15, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        try {
            client.newCall(okhttp3.Request.Builder().url(ENDPOINT).get().build()).execute().use { response ->
                require(response.code == 200) { "Probe response unavailable or redirected" }
                val body = requireNotNull(response.body) { "Probe response empty" }
                require(body.contentLength() <= MAX_BYTES) { "Probe response oversized" }
                val bytes = body.byteStream().readNBytes(MAX_BYTES + 1)
                return parseObservation(bytes, Instant.now())
            }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); client.cache?.close() }
    }

    internal fun parseObservation(bytes: ByteArray, observedAt: Instant): JsonObject {
        require(bytes.size in 1..MAX_BYTES) { "Probe response size invalid" }
        val data = runnerJson.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()).jsonObject
        val ip = data.string("ip")
        val isV4 = ip.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && ip.split('.').all { it.toInt() <= 255 && (it == "0" || !it.startsWith('0')) }
        val isV6 = ':' in ip && ip.matches(Regex("[0-9A-Fa-f:]+")) && runCatching { java.net.InetAddress.getByName(ip) is java.net.Inet6Address }.getOrDefault(false)
        require(isV4 || isV6) { "Probe did not report an IP literal" }
        HttpHostSafety.assertResolvedAddressesPublic(ip, listOf(java.net.InetAddress.getByName(ip)), allowPrivateOverride = false)
        val country = data.string("country")
        val decision = when {
            country == "CN" -> "BLOCK_CN"
            country !in java.util.Locale.getISOCountries().toSet() -> "BLOCK_UNKNOWN"
            else -> "OBSERVED_NON_CN"
        }
        return buildJsonObject {
            put("client", "jvm-http"); put("endpoint", ENDPOINT); put("observedAt", observedAt.toString())
            put("ip", ip); put("countryCode", country); put("addressFamily", if (isV4) "IPv4" else "IPv6")
            put("transport", "okhttp-direct-tls-ssrf-dns-no-redirect-no-retry")
            put("observationDecision", decision); put("accepted", false)
        }
    }
}

fun main(args: Array<String>) {
    require(args.isEmpty()) { "Public JVM probe takes no arguments" }
    try { println(RunnerHttpProbe.observe()) } catch (failure: Exception) {
        println(buildJsonObject {
            put("client", "jvm-http"); put("observedAt", Instant.now().toString())
            put("observationDecision", "BLOCK_UNKNOWN"); put("accepted", false); put("reason", "PROBE_UNAVAILABLE")
            // Class names only: raw exception messages may contain transport/user configuration.
            put("failureType", failure.javaClass.name); failure.cause?.let { put("causeType", it.javaClass.name) }
        })
        kotlin.system.exitProcess(2)
    }
}
