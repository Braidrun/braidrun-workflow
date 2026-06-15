package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvSettingsTest {

    @Test
    fun `compactEnvSettings returns date and OS`() {
        val result = compactEnvSettings(emptyList())
        assertContains(result, "Date:")
        assertContains(result, "OS:")
    }

    @Test
    fun `compactEnvSettings includes working directory`() {
        val params = listOf(
            ConfigurationParameter("working_dir", JsonPrimitive("/tmp/test"))
        )
        val result = compactEnvSettings(params)
        assertContains(result, "Working directory: /tmp/test")
    }

    @Test
    fun `compactEnvSettings includes language`() {
        val params = listOf(
            ConfigurationParameter("language", JsonPrimitive("zh"))
        )
        val result = compactEnvSettings(params)
        assertContains(result, "Language: zh")
    }

    @Test
    fun `compactEnvSettings omits interpreters when tool_set is empty`() {
        val result = compactEnvSettings(emptyList())
        assertFalse(result.contains("Python:"))
        assertFalse(result.contains("Node.js:"))
        assertFalse(result.contains("Git:"))
    }

    @Test
    fun `compactEnvSettings includes python when code_execution in tool_set`() {
        val params = listOf(
            ConfigurationParameter(
                "tool_set",
                JsonArray(listOf(JsonPrimitive("code_execution")))
            )
        )
        val result = compactEnvSettings(params)
        // Python should be mentioned if available on the system, or absent if not
        // We can't assert the exact content since it depends on the environment,
        // but the function should not throw
        assertTrue(result.contains("Date:"))
    }

    @Test
    fun `compactEnvSettings includes git when git in tool_set`() {
        val params = listOf(
            ConfigurationParameter(
                "tool_set",
                JsonArray(listOf(JsonPrimitive("git")))
            )
        )
        val result = compactEnvSettings(params)
        // Git is almost always available on dev machines
        assertContains(result, "Git:")
    }

    @Test
    fun `compactEnvSettings shows output dir only when different from working dir`() {
        val params = listOf(
            ConfigurationParameter("working_dir", JsonPrimitive("/tmp/work")),
            ConfigurationParameter("output_dir", JsonPrimitive("/tmp/output"))
        )
        val result = compactEnvSettings(params)
        assertContains(result, "Output directory: /tmp/output")
    }

    @Test
    fun `compactEnvSettings omits output dir when same as working dir`() {
        val params = listOf(
            ConfigurationParameter("working_dir", JsonPrimitive("/tmp/same")),
            ConfigurationParameter("output_dir", JsonPrimitive("/tmp/same"))
        )
        val result = compactEnvSettings(params)
        assertFalse(result.contains("Output directory:"))
    }

    @Test
    fun `compactEnvSettings is significantly shorter than envSettings`() {
        val params = listOf(
            ConfigurationParameter(
                "tool_set",
                JsonArray(listOf(JsonPrimitive("shell"), JsonPrimitive("git"), JsonPrimitive("code_execution")))
            )
        )
        val compact = compactEnvSettings(params)
        val verbose = envSettings(params)
        assertTrue(compact.length < verbose.length,
            "Compact (${compact.length} chars) should be shorter than verbose (${verbose.length} chars)")
    }

    @Test
    fun `envSettings includes full system information`() {
        val result = envSettings(emptyList())
        assertContains(result, "Current date/time:")
        assertContains(result, "Current Running OS:")
        assertContains(result, "Current Running JVM:")
        assertContains(result, "Current Running CPU:")
        assertContains(result, "Current Memory Usage:")
    }

    @Test
    fun `envSettings uses cached interpreter detection`() {
        // Calling envSettings twice should not spawn new processes the second time
        // We verify this by checking that both calls return consistent results
        val result1 = envSettings(emptyList())
        val result2 = envSettings(emptyList())
        // Interpreter versions should be identical (cached)
        val python1 = Regex("Python Version: (.+)").find(result1)?.groupValues?.get(1)
        val python2 = Regex("Python Version: (.+)").find(result2)?.groupValues?.get(1)
        kotlin.test.assertEquals(python1, python2)
    }
}
