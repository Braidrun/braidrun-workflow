package com.fartech.agents.commons

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for the input-validation gates added to
 * [SubprocessExecutorFactory] in Phase 7. These don't spawn a docker daemon —
 * they exercise the static validators that protect the factory from operator
 * (or upstream config) input that would otherwise smuggle a different
 * registry / image / network name into the launched container.
 */
class SubprocessExecutorFactoryTest {

    @Test
    fun `validateImageTag accepts well-formed tags`() {
        for (good in listOf("1.0", "1.2.3", "v2", "release_2026-04", "main", "latest")) {
            assertEquals(good, SubprocessExecutorFactory.validateImageTag(good))
        }
    }

    @Test
    fun `validateImageTag rejects blank input`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubprocessExecutorFactory.validateImageTag("")
        }
    }

    @Test
    fun `validateImageTag rejects tags with metacharacters`() {
        for (bad in listOf(
            "1.0,evil-registry.io/x",
            "1.0 --some-flag",
            "1.0;rm -rf /",
            "../../1.0",
            "1.0/sub/path",
            "1.0:nested",
            "tag with space",
            "tag\nwith\nnewline"
        )) {
            assertThrows(IllegalArgumentException::class.java, {
                SubprocessExecutorFactory.validateImageTag(bad)
            }, "expected reject for '$bad'")
        }
    }

    @Test
    fun `validateImageTag rejects leading dot or hyphen`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubprocessExecutorFactory.validateImageTag(".hidden")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubprocessExecutorFactory.validateImageTag("-flag")
        }
    }

    @Test
    fun `validateDockerNetworkName accepts standard names`() {
        for (good in listOf("workflow-egress-only", "bridge", "none", "host", "my_net.1")) {
            assertEquals(good, SubprocessExecutorFactory.validateDockerNetworkName(good))
        }
    }

    @Test
    fun `validateDockerNetworkName rejects metacharacters`() {
        for (bad in listOf(
            "",
            "/etc/passwd",
            "name with space",
            "name;evil",
            "../../net",
            "net,other"
        )) {
            assertThrows(IllegalArgumentException::class.java, {
                SubprocessExecutorFactory.validateDockerNetworkName(bad)
            }, "expected reject for '$bad'")
        }
    }
}
