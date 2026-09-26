package com.fartech.agents.tools

import com.fartech.agents.commons.LlmEndpointNotAllowedException
import com.fartech.agents.workflow.WorkflowHostPolicy
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultimediaGenerationToolsTest {
    @Test
    fun `multimedia api key prefers dedicated key`() {
        val tools = MultimediaGenerationTools(
            httpAccess = HttpAccess(),
            parameters = listOf(
                ConfigurationParameter("multimedia_api_key", JsonPrimitive("dedicated-key")),
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("standalone-key")),
                providerKeys("llm_provider_keys", "openrouter", "shared-key")
            )
        )

        assertEquals("dedicated-key", tools.resolveMultimediaApiKeyForTest())
    }

    @Test
    fun `multimedia api key falls back to standalone provider parameter`() {
        val tools = MultimediaGenerationTools(
            httpAccess = HttpAccess(),
            parameters = listOf(
                ConfigurationParameter("openrouter_api_key", JsonPrimitive("standalone-key")),
                providerKeys("llm_provider_keys", "openrouter", "shared-key")
            )
        )

        assertEquals("standalone-key", tools.resolveMultimediaApiKeyForTest())
    }

    @Test
    fun `multimedia api key falls back to shared provider keys`() {
        val tools = MultimediaGenerationTools(
            httpAccess = HttpAccess(),
            parameters = listOf(providerKeys("llm_provider_keys", "open_router", "shared-key"))
        )

        assertEquals("shared-key", tools.resolveMultimediaApiKeyForTest())
    }

    @Test
    fun `reference image rejects non-image local file`(@TempDir dir: File) {
        val file = File(dir, "secret.txt").also { it.writeText("not an image") }
        val tools = MultimediaGenerationTools(HttpAccess(), emptyList())

        val ex = assertFailsWith<IllegalArgumentException> {
            tools.referenceImageJson(file.absolutePath)
        }

        assertTrue(ex.message!!.contains("not an allowed image type"))
    }

    @Test
    fun `reference image rejects oversized local file`(@TempDir dir: File) {
        val file = File(dir, "large.png").also { it.writeBytes(ByteArray(16)) }
        val tools = MultimediaGenerationTools(
            HttpAccess(),
            listOf(ConfigurationParameter("multimedia_max_reference_image_bytes", JsonPrimitive("8")))
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            tools.referenceImageJson(file.absolutePath)
        }

        assertTrue(ex.message!!.contains("exceeds multimedia_max_reference_image_bytes"))
    }

    @Test
    fun `latched host refuses a private multimedia base url before any request`() {
        WorkflowHostPolicy.requirePublicLlmEndpoints()
        try {
            val tools = MultimediaGenerationTools(
                httpAccess = HttpAccess(),
                parameters = listOf(
                    ConfigurationParameter(
                        "llm_config",
                        Json.parseToJsonElement(
                            """{"models":[{"provider":"openrouter","model":"openai/gpt-5.5","base_url":"https://127.0.0.1"}]}"""
                        ),
                    ),
                    ConfigurationParameter("openrouter_api_key", JsonPrimitive("sk-or-test")),
                ),
            )

            val ex = assertFailsWith<LlmEndpointNotAllowedException> {
                runBlocking { tools.generateImage(prompt = "a cat", imagePath = "unused.png") }
            }
            assertTrue(ex.message!!.contains("multimedia"), ex.message)
        } finally {
            WorkflowHostPolicy.resetForTests()
        }
    }

    @Test
    fun `custom multimedia base url never uses environment keys under the explicit-keys latch`() {
        WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints()
        try {
            val custom = MultimediaGenerationTools(
                HttpAccess(),
                listOf(ConfigurationParameter("multimedia_base_url", JsonPrimitive("https://attacker.example"))),
            )
            // No key supplied with the run: an empty key, whatever OPENROUTER_API_KEY holds.
            assertEquals("", custom.multimediaApiKey())

            val supplied = MultimediaGenerationTools(
                HttpAccess(),
                listOf(
                    ConfigurationParameter("multimedia_base_url", JsonPrimitive("https://attacker.example")),
                    providerKeys("llm_provider_keys", "openrouter", "shared-key"),
                ),
            )
            assertEquals("shared-key", supplied.multimediaApiKey())
        } finally {
            WorkflowHostPolicy.resetForTests()
        }
    }

    private fun providerKeys(key: String, provider: String, value: String): ConfigurationParameter =
        ConfigurationParameter(key, JsonObject(mapOf(provider to JsonPrimitive(value))))

    private fun MultimediaGenerationTools.resolveMultimediaApiKeyForTest(): String = multimediaApiKey()
}
