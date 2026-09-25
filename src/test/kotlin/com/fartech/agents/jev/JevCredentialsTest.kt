package com.fartech.agents.jev

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JevCredentialsTest {

    private fun param(key: String, value: String) = ConfigurationParameter(key, JsonPrimitive(value))

    private fun providerKeys(vararg entries: Pair<String, String>) = ConfigurationParameter(
        "llm_provider_keys",
        buildJsonObject { entries.forEach { (k, v) -> put(k, v) } },
    )

    /** Resolution with an explicit (possibly empty) env so the real process env never leaks in. */
    private fun resolve(
        stepModel: String? = null,
        credentials: JevCredentials? = null,
        parameters: List<ConfigurationParameter> = emptyList(),
        envKeyFallback: Boolean = true,
        env: Map<String, String> = emptyMap(),
    ) = resolveJevCall(stepModel, credentials, parameters, envKeyFallback, env)

    @Nested
    inner class ApiKey {

        @Test
        fun `host credentials win over parameters and env`() {
            val resolved = resolve(
                credentials = JevCredentials("host-key"),
                parameters = listOf(param("typesafe_api_key", "param-key")),
                env = mapOf(TYPESAFE_API_KEY_ENV to "env-key"),
            )
            assertEquals("host-key", resolved.apiKey)
            assertEquals(JevKeySource.HOST, resolved.keySource)
        }

        @Test
        fun `blank host key falls through to parameters`() {
            val resolved = resolve(
                credentials = JevCredentials("  ", defaultModel = "jev-preview"),
                parameters = listOf(param("typesafe_api_key", "param-key")),
            )
            assertEquals("param-key", resolved.apiKey)
            assertEquals(JevKeySource.PARAMETERS, resolved.keySource)
            // the host default model is still honored even though its key was blank
            assertEquals("jev-preview", resolved.model)
        }

        @Test
        fun `parameters win over env`() {
            val resolved = resolve(
                parameters = listOf(param("typesafe_api_key", "param-key")),
                env = mapOf(TYPESAFE_API_KEY_ENV to "env-key"),
            )
            assertEquals("param-key", resolved.apiKey)
            assertEquals(JevKeySource.PARAMETERS, resolved.keySource)
        }

        @Test
        fun `every alias parameter name is honored`() {
            for (name in listOf("typesafe_api_key", "typesafe_ai_api_key", "jev_api_key")) {
                assertEquals("k-$name", resolve(parameters = listOf(param(name, "k-$name"))).apiKey, name)
            }
        }

        @Test
        fun `llm_provider_keys aliases are honored`() {
            for (alias in listOf("typesafe", "typesafe_ai", "jev")) {
                val resolved = resolve(parameters = listOf(providerKeys(alias to "map-$alias")))
                assertEquals("map-$alias", resolved.apiKey, alias)
                assertEquals(JevKeySource.PARAMETERS, resolved.keySource)
            }
        }

        @Test
        fun `standalone parameter wins over llm_provider_keys`() {
            val resolved = resolve(
                parameters = listOf(providerKeys("typesafe" to "map-key"), param("jev_api_key", "param-key")),
            )
            assertEquals("param-key", resolved.apiKey)
        }

        @Test
        fun `other providers' keys are never used`() {
            val error = assertFailsWith<JevApiException> {
                resolve(
                    parameters = listOf(param("openrouter_api_key", "or-key"), providerKeys("openai" to "oa-key")),
                    env = mapOf("OPENROUTER_API_KEY" to "or-env"),
                )
            }
            assertEquals(JevApiException.Kind.MISSING_CREDENTIALS, error.kind)
        }

        @Test
        fun `env key is used when enabled`() {
            val resolved = resolve(env = mapOf(TYPESAFE_API_KEY_ENV to " env-key "))
            assertEquals("env-key", resolved.apiKey)
            assertEquals(JevKeySource.ENVIRONMENT, resolved.keySource)
        }

        @Test
        fun `envKeyFallback false disables the env key`() {
            val error = assertFailsWith<JevApiException> {
                resolve(envKeyFallback = false, env = mapOf(TYPESAFE_API_KEY_ENV to "env-key"))
            }
            assertEquals(JevApiException.Kind.MISSING_CREDENTIALS, error.kind)
            assertTrue(error.isConfigurationError)
            assertEquals(JEV_MISSING_CREDENTIALS_MESSAGE, error.message)
            assertFalse(error.message!!.contains("env-key"))
        }

        @Test
        fun `envKeyFallback false still honors env base url and model`() {
            val resolved = resolve(
                parameters = listOf(param("typesafe_api_key", "param-key")),
                envKeyFallback = false,
                env = mapOf(
                    TYPESAFE_API_KEY_ENV to "env-key",
                    TYPESAFE_BASE_URL_ENV to "https://gw.example.com/v1",
                    TYPESAFE_DEFAULT_MODEL_ENV to "jev-1.13.0",
                ),
            )
            assertEquals("param-key", resolved.apiKey)
            assertEquals("https://gw.example.com/v1", resolved.baseUrl)
            assertEquals("jev-1.13.0", resolved.model)
        }

        @Test
        fun `missing key message tells every way to configure it`() {
            val error = assertFailsWith<JevApiException> { resolve() }
            assertEquals(JevApiException.Kind.MISSING_CREDENTIALS, error.kind)
            assertEquals(null, error.status)
            assertEquals(
                "TypeSafe API key is not configured. Set TYPESAFE_API_KEY, pass --param typesafe_api_key=..., " +
                    "or connect \"TypeSafe (Jev)\" in the Braidrun credential center.",
                error.message,
            )
        }

        @Test
        fun `malformed llm_provider_keys does not break resolution or leak`() {
            val nested = ConfigurationParameter(
                "llm_provider_keys",
                buildJsonObject { put("typesafe", buildJsonObject { put("secret", "nested-key") }) },
            )
            val error = assertFailsWith<JevApiException> { resolve(parameters = listOf(nested)) }
            assertEquals(JevApiException.Kind.MISSING_CREDENTIALS, error.kind)
            assertFalse(error.message!!.contains("nested-key"))
        }
    }

    @Nested
    inner class Model {

        private val keyParam = param("typesafe_api_key", "k")

        @Test
        fun `step model wins`() {
            val resolved = resolve(
                stepModel = "jev-1.13.0",
                credentials = JevCredentials("k", defaultModel = "jev-preview"),
                parameters = listOf(param(TYPESAFE_MODEL_PARAM, "jev-param")),
                env = mapOf(TYPESAFE_DEFAULT_MODEL_ENV to "jev-env"),
            )
            assertEquals("jev-1.13.0", resolved.model)
        }

        @Test
        fun `host default model beats parameter and env`() {
            val resolved = resolve(
                stepModel = " ",
                credentials = JevCredentials("k", defaultModel = "jev-preview"),
                parameters = listOf(param(TYPESAFE_MODEL_PARAM, "jev-param")),
                env = mapOf(TYPESAFE_DEFAULT_MODEL_ENV to "jev-env"),
            )
            assertEquals("jev-preview", resolved.model)
        }

        @Test
        fun `parameter beats env`() {
            val resolved = resolve(
                parameters = listOf(keyParam, param(TYPESAFE_MODEL_PARAM, "jev-param")),
                env = mapOf(TYPESAFE_DEFAULT_MODEL_ENV to "jev-env"),
            )
            assertEquals("jev-param", resolved.model)
        }

        @Test
        fun `env beats the built-in default`() {
            val resolved = resolve(parameters = listOf(keyParam), env = mapOf(TYPESAFE_DEFAULT_MODEL_ENV to "jev-env"))
            assertEquals("jev-env", resolved.model)
        }

        @Test
        fun `falls back to jev-latest`() {
            assertEquals(JEV_DEFAULT_MODEL, resolve(parameters = listOf(keyParam)).model)
            assertEquals("jev-latest", resolve(parameters = listOf(keyParam)).model)
        }
    }

    @Nested
    inner class BaseUrl {

        private val keyParam = param("typesafe_api_key", "k")

        @Test
        fun `defaults to the public API`() {
            assertEquals(TYPESAFE_DEFAULT_BASE_URL, resolve(parameters = listOf(keyParam)).baseUrl)
        }

        @Test
        fun `env base url is honored`() {
            val resolved = resolve(
                parameters = listOf(keyParam),
                env = mapOf(TYPESAFE_BASE_URL_ENV to " https://gw.example.com/typesafe "),
            )
            assertEquals("https://gw.example.com/typesafe", resolved.baseUrl)
            assertEquals("https://gw.example.com/typesafe", resolved.clientSettings().baseUrl)
        }

        @Test
        fun `base url is never taken from parameters`() {
            val resolved = resolve(
                parameters = listOf(
                    keyParam,
                    param("typesafe_base_url", "https://attacker.example"),
                    param("TYPESAFE_BASE_URL", "https://attacker.example"),
                    param("base_url", "https://attacker.example"),
                ),
            )
            assertEquals(TYPESAFE_DEFAULT_BASE_URL, resolved.baseUrl)
        }

        @Test
        fun `non-http env base url is ignored`() {
            val resolved = resolve(parameters = listOf(keyParam), env = mapOf(TYPESAFE_BASE_URL_ENV to "ftp://x"))
            assertEquals(TYPESAFE_DEFAULT_BASE_URL, resolved.baseUrl)
        }

        @Test
        fun `client settings keep caller timeouts`() {
            val resolved = resolve(parameters = listOf(keyParam), env = mapOf(TYPESAFE_BASE_URL_ENV to "http://localhost:9"))
            val settings = resolved.clientSettings(JevClientSettings(requestTimeoutMillis = 1_234, maxRetries = 1))
            assertEquals("http://localhost:9", settings.baseUrl)
            assertEquals(1_234, settings.requestTimeoutMillis)
            assertEquals(1, settings.maxRetries)
        }
    }

    @Nested
    inner class Redaction {

        @Test
        fun `credentials toString never prints the key`() {
            val credentials = JevCredentials("ts-super-secret", defaultModel = "jev-latest")
            assertEquals("JevCredentials(apiKey=***, defaultModel=jev-latest)", credentials.toString())
        }

        @Test
        fun `resolved call toString never prints the key`() {
            val resolved = resolve(credentials = JevCredentials("ts-super-secret"))
            assertFalse(resolved.toString().contains("ts-super-secret"))
            assertTrue(resolved.toString().contains("apiKey=***"))
        }

        @Test
        fun `credentials have value equality`() {
            assertEquals(JevCredentials("a", "m"), JevCredentials("a", "m"))
            assertEquals(JevCredentials("a", "m").hashCode(), JevCredentials("a", "m").hashCode())
            assertNotEquals(JevCredentials("a", "m"), JevCredentials("a", null))
        }
    }

    @Nested
    inner class ProviderIds {

        @Test
        fun `typesafe provider ids are recognized case-insensitively`() {
            for (id in listOf("typesafe", "TypeSafe", "typesafe_ai", "typesafe-ai", "jev", " JEV ")) {
                assertTrue(isTypeSafeProviderId(id), id)
            }
            for (id in listOf("openrouter", "type_safe_x", "", null)) {
                assertFalse(isTypeSafeProviderId(id), id.toString())
            }
        }

        @Test
        fun `canonical alias list`() {
            assertEquals(listOf("typesafe", "typesafe_ai", "jev"), TYPESAFE_PROVIDER_ALIASES)
        }
    }
}
