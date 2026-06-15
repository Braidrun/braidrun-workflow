package com.fartech.agents.mcp

import com.fartech.agents.commons.parseExactToolSet
import com.fartech.ftapp2.commonsKt.HttpAccess
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMcpServerTest {

    @Test
    fun `resolveAgentMcpToolGroups returns all groups when omitted`() {
        val resolved = resolveAgentMcpToolGroups(emptyList())

        assertEquals(getSupportedAgentMcpToolGroups().map { it.name }, resolved)
    }

    @Test
    fun `resolveAgentMcpToolGroups supports repeated and comma separated inputs`() {
        val resolved = resolveAgentMcpToolGroups(
            listOf("shell,web", "git", "shell")
        )

        assertEquals(listOf("shell", "web", "git"), resolved)
    }

    @Test
    fun `resolveAgentMcpToolGroups supports word excel and powerpoint groups`() {
        val resolved = resolveAgentMcpToolGroups(
            listOf("word,excel", "powerpoint")
        )

        assertEquals(listOf("word", "excel", "powerpoint"), resolved)
    }

    @Test
    fun `resolveAgentMcpToolGroups rejects unknown group`() {
        val error = assertFailsWith<IllegalArgumentException> {
            resolveAgentMcpToolGroups(listOf("shell", "unknown_group"))
        }

        assertContains(error.message ?: "", "unknown_group")
    }

    @Test
    fun `parseExactToolSet does not implicitly add skill tools`() {
        val registry = parseExactToolSet(
            parameters = emptyList(),
            httpAccess = HttpAccess(),
            tools = listOf("shell"),
            skillManager = null
        )

        assertFalse(registry.tools.any { it.name == "searchSkills" })
    }

    @Test
    fun `parseExactToolSet keeps explicitly requested skill tools`() {
        val registry = parseExactToolSet(
            parameters = emptyList(),
            httpAccess = HttpAccess(),
            tools = listOf("skill_tools"),
            skillManager = null
        )

        assertTrue(registry.tools.any { it.name == "searchSkills" })
    }

    @Test
    fun `parseExactToolSet word tools expose docx capabilities only`() {
        val registry = parseExactToolSet(
            parameters = emptyList(),
            httpAccess = HttpAccess(),
            tools = listOf("word"),
            skillManager = null
        )

        assertTrue(registry.tools.any { it.name == "addHeading" })
        assertFalse(registry.tools.any { it.name == "writeStyledCell" })
        assertFalse(registry.tools.any { it.name == "createPresentation" })
    }

    @Test
    fun `parseExactToolSet excel tools expose xlsx capabilities only`() {
        val registry = parseExactToolSet(
            parameters = emptyList(),
            httpAccess = HttpAccess(),
            tools = listOf("excel"),
            skillManager = null
        )

        assertTrue(registry.tools.any { it.name == "writeStyledCell" })
        assertFalse(registry.tools.any { it.name == "addHeading" })
        assertFalse(registry.tools.any { it.name == "createPresentation" })
    }

    @Test
    fun `parseExactToolSet powerpoint tools expose pptx capabilities only`() {
        val registry = parseExactToolSet(
            parameters = emptyList(),
            httpAccess = HttpAccess(),
            tools = listOf("powerpoint"),
            skillManager = null
        )

        assertTrue(registry.tools.any { it.name == "createPresentation" })
        assertFalse(registry.tools.any { it.name == "addHeading" })
        assertFalse(registry.tools.any { it.name == "writeStyledCell" })
    }

    @Test
    fun `parseExactToolSet avoids duplicate office tools when office and word are combined`() {
        val registry = parseExactToolSet(
            parameters = emptyList(),
            httpAccess = HttpAccess(),
            tools = listOf("office", "word"),
            skillManager = null
        )

        val duplicateNames = registry.tools
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }

        assertTrue("readDocxText" in registry.tools.map { it.name })
        assertTrue(duplicateNames.isEmpty(), "Unexpected duplicate tool names: $duplicateNames")
    }
}
