package com.fartech.agents.runner

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class RunnerHttpProbeTest {
    @Test fun `literal public observations stay unaccepted and classify CN and unknown separately`() {
        for ((country, decision) in mapOf("JP" to "OBSERVED_NON_CN", "CN" to "BLOCK_CN", "ZZ" to "BLOCK_UNKNOWN")) {
            val observation = RunnerHttpProbe.parseObservation("""{"ip":"8.8.8.8","country":"$country"}""".toByteArray(), Instant.EPOCH)
            assertEquals(decision, observation.string("observationDecision"))
            assertEquals("false", observation.getValue("accepted").toString())
            assertEquals("IPv4", observation.string("addressFamily"))
            assertEquals(Instant.EPOCH.toString(), observation.string("observedAt"))
        }
        assertEquals("IPv6", RunnerHttpProbe.parseObservation("""{"ip":"2606:4700:4700::1111","country":"US"}""".toByteArray(), Instant.EPOCH).string("addressFamily"))
    }
    @Test fun `malformed private hostname and oversized responses cannot authorize`() {
        for (ip in listOf("127.0.0.1", "192.168.1.2", "::1", "example.com", "999.1.2.3", "01.2.3.4", "fe80::1%en0")) {
            assertFails { RunnerHttpProbe.parseObservation("""{"ip":"$ip","country":"JP"}""".toByteArray(), Instant.EPOCH) }
        }
        assertFails { RunnerHttpProbe.parseObservation(ByteArray(4097), Instant.EPOCH) }
        assertFails { RunnerHttpProbe.parseObservation(byteArrayOf(0xff.toByte()), Instant.EPOCH) }
    }
    @Test fun `probe and business HTTP share direct no redirect no retry DNS configuration`() {
        val client = RunnerHttpClients.configureDirect(okhttp3.OkHttpClient.Builder()) {}.build()
        try {
            assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
            assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
            assertSame(com.fartech.ftapp2.commonsKt.HttpAccess.SsrfValidatingDns, client.dns)
            assertFailsWith<IllegalArgumentException> { RunnerHttpClients.requireDirect(mapOf("HTTPS_PROXY" to "http://proxy.invalid"), java.util.Properties()) }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
