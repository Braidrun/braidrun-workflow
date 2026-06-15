package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
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

    private fun providerKeys(key: String, provider: String, value: String): ConfigurationParameter =
        ConfigurationParameter(key, JsonObject(mapOf(provider to JsonPrimitive(value))))

    private fun MultimediaGenerationTools.resolveMultimediaApiKeyForTest(): String {
        val method = MultimediaGenerationTools::class.java.getDeclaredMethod("multimediaApiKey")
        method.isAccessible = true
        return method.invoke(this) as String
    }
}
