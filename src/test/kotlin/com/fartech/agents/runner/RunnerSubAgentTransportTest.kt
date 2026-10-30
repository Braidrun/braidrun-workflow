package com.fartech.agents.runner

import com.fartech.agents.commons.CapturingHttpClientFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.*

class RunnerSubAgentTransportTest {
    @Test fun `an SDK retry after failed dispatch cannot send a second request`(): Unit = runBlocking {
        val http = CapturingHttpClientFactory { _, _ -> throw java.io.IOException("connection lost after dispatch") }
        val wire = RunnerSubAgentWireGuard()
        val client = wire.factory(http).create("fixture", "https://example.org", emptyMap(), emptyMap(), 1000, 1000, 1000, Json)
        wire.begin()
        try {
            assertFailsWith<java.io.IOException> { client.post("completion", "{}", String::class, String::class, emptyMap(), emptyMap()) }
            // An SDK's catch-and-retry loop still uses the host-owned factory client.
            assertFailsWith<IllegalStateException> { client.post("completion", "{}", String::class, String::class, emptyMap(), emptyMap()) }
        } finally { wire.finish(); client.close() }
        assertEquals(1, http.requests.size)
    }

    @Test fun `precall discovery late dispatch and streaming have no transport authority`(): Unit = runBlocking {
        val http = CapturingHttpClientFactory(); val wire = RunnerSubAgentWireGuard()
        val client = wire.factory(http).create("fixture", "https://example.org", emptyMap(), emptyMap(), 1000, 1000, 1000, Json)
        assertFailsWith<IllegalStateException> { client.post("completion", "{}", String::class, String::class, emptyMap(), emptyMap()) }
        wire.begin()
        assertFailsWith<IllegalStateException> { client.get("models", String::class, emptyMap(), emptyMap()) }
        assertFailsWith<IllegalStateException> { client.lines("stream", "{}", String::class, emptyMap(), emptyMap()) }
        wire.finish()
        assertFailsWith<IllegalStateException> { client.post("completion", "{}", String::class, String::class, emptyMap(), emptyMap()) }
        assertTrue(http.requests.isEmpty()); client.close()
    }

    @Test fun `each separately budgeted model call has exactly one HTTP dispatch`(): Unit = runBlocking {
        val http = CapturingHttpClientFactory(); val wire = RunnerSubAgentWireGuard()
        val client = wire.factory(http).create("fixture", "https://example.org", emptyMap(), emptyMap(), 1000, 1000, 1000, Json)
        repeat(2) {
            wire.begin()
            client.post("completion", "{}", String::class, String::class, emptyMap(), emptyMap())
            assertFailsWith<IllegalStateException> { client.post("completion", "{}", String::class, String::class, emptyMap(), emptyMap()) }
            wire.finish()
        }
        assertEquals(2, http.requests.size); client.close()
    }
}
