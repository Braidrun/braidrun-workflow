package com.fartech.agents.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SkillToolsVersionNormalizationTest {

    @Test
    fun `normalizeClawHubVersion filters null-like placeholders`() {
        assertNull(normalizeClawHubVersion(null))
        assertNull(normalizeClawHubVersion(""))
        assertNull(normalizeClawHubVersion("   "))
        assertNull(normalizeClawHubVersion("null"))
        assertNull(normalizeClawHubVersion("NULL"))
        assertNull(normalizeClawHubVersion("undefined"))
        assertNull(normalizeClawHubVersion("unknown"))
    }

    @Test
    fun `normalizeClawHubVersion keeps valid versions`() {
        assertEquals("1.2.3", normalizeClawHubVersion("1.2.3"))
        assertEquals("2.0.0-beta.1", normalizeClawHubVersion(" 2.0.0-beta.1 "))
    }
}
