package com.fartech.agents.commons

import ai.koog.prompt.executor.clients.LLMClientException
import com.fartech.agents.tools.EmbedderFactory
import com.fartech.agents.workflow.WorkflowHostPolicy
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The [WorkflowHostPolicy.requirePublicLlmEndpoints] latch: once a host declares it, every
 * LLM client / RAG embedder the engine builds must target https on a public host.
 */
class LlmEndpointPolicyTest {

    @BeforeEach
    fun setUp() = WorkflowHostPolicy.resetForTests()

    @AfterEach
    fun tearDown() = WorkflowHostPolicy.resetForTests()

    /** Resolves every name to one public address, so no test depends on real DNS. */
    private val publicResolver: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("8.8.8.8")) }

    private fun literalResolver(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

    // ---------------------------------------------------------------- violation()

    @Test
    fun `https on a public host is allowed`() {
        assertNull(LlmEndpointPolicy.violation("https://api.x.ai/v1", publicResolver))
        assertNull(LlmEndpointPolicy.violation("https://8.8.8.8/v1", ::literalResolver))
    }

    @Test
    fun `plain http is rejected even for a public host`() {
        assertEquals(
            "only https is allowed, got scheme 'http'",
            LlmEndpointPolicy.violation("http://api.openai.com/v1", publicResolver),
        )
    }

    @Test
    fun `loopback, private, link-local and unique-local hosts are rejected`() {
        for (url in listOf(
            "https://127.0.0.1/v1",
            "https://localhost:1234/v1",
            "https://10.0.0.5/v1",
            "https://192.168.1.10:11434",
            "https://169.254.169.254/latest",
            "https://[::1]/v1",
            "https://[fd00::1]/v1",
        )) {
            val violation = LlmEndpointPolicy.violation(url, ::literalResolver)
            assertNotNull(violation, url)
            assertFalse(violation!!.contains("WEB_TOOLS_ALLOW_PRIVATE_URLS"), "the tools opt-out does not apply: $violation")
        }
    }

    @Test
    fun `a name resolving to a private address is rejected`() {
        val rebinding: (String) -> List<InetAddress> = {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.1.2.3"))
        }
        assertTrue(LlmEndpointPolicy.violation("https://llm.example.com/v1", rebinding)!!.contains("10.1.2.3"))
    }

    @Test
    fun `malformed, host-less and unresolvable URLs are rejected`() {
        assertNotNull(LlmEndpointPolicy.violation("https://exa mple.com", publicResolver))
        assertNotNull(LlmEndpointPolicy.violation("https:///v1", publicResolver))
        assertNotNull(LlmEndpointPolicy.violation("api.openai.com/v1", publicResolver))
        assertEquals(
            "host 'nope.invalid' could not be resolved",
            LlmEndpointPolicy.violation("https://nope.invalid/v1") { throw UnknownHostException(it) },
        )
    }

    @Test
    fun `every public provider default passes and the local defaults do not`() {
        val publicDefaults = listOf(
            "https://api.openai.com/v1",
            "https://api.anthropic.com/v1",
            "https://generativelanguage.googleapis.com",
            "https://api.deepseek.com/v1",
            "https://openrouter.ai",
            "https://api.mistral.ai/v1",
            "https://api.moonshot.cn/v1",
            "https://api.minimax.chat/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://api.z.ai/api/coding/paas/v4",
            "https://integrate.api.nvidia.com/v1",
        )
        publicDefaults.forEach { assertNull(LlmEndpointPolicy.violation(it, publicResolver), it) }
        // LM Studio / Ollama defaults.
        assertNotNull(LlmEndpointPolicy.violation("http://localhost:1234/v1", ::literalResolver))
        assertNotNull(LlmEndpointPolicy.violation("http://localhost:11434", ::literalResolver))
    }

    // ---------------------------------------------------------------- the latch

    @Test
    fun `without the latch nothing is checked`() {
        assertFalse(WorkflowHostPolicy.requiresPublicLlmEndpoints)
        LlmEndpointPolicy.check("http://127.0.0.1:1234/v1", "lmstudio")
        val http = CapturingHttpClientFactory()
        createLLMClient(emptyList(), LLModelConfig(provider = "lmstudio", model = "qwen3-coder-30b"), emptyMap(), http)
        assertEquals("http://localhost:1234/v1", http.created.single().baseUrl)
    }

    @Test
    fun `latched createLLMClient refuses local defaults before building a client`() {
        WorkflowHostPolicy.requirePublicLlmEndpoints()
        assertTrue(WorkflowHostPolicy.requiresPublicLlmEndpoints)
        val http = CapturingHttpClientFactory()

        for (config in listOf(
            LLModelConfig(provider = "lmstudio", model = "qwen3-coder-30b"),
            LLModelConfig(provider = "ollama", model = "gpt-oss:120b"),
        )) {
            val error = assertThrows(LlmEndpointNotAllowedException::class.java) {
                createLLMClient(emptyList(), config, mapOf("ollama" to "k"), http)
            }
            assertTrue(error.message!!.contains("provider '${config.provider}'"), error.message)
            assertTrue(error.message!!.contains("only https is allowed"), error.message)
        }
        assertTrue(http.created.isEmpty(), "no client may be built for a refused endpoint")
    }

    @Test
    fun `latched createLLMClient refuses a user base_url pointing inside the network`() {
        WorkflowHostPolicy.requirePublicLlmEndpoints()
        val http = CapturingHttpClientFactory()

        for (baseUrl in listOf("https://169.254.169.254/v1", "https://127.0.0.1:8443/v1", "http://8.8.8.8/v1")) {
            val error = assertThrows(LlmEndpointNotAllowedException::class.java) {
                createLLMClient(
                    emptyList(),
                    LLModelConfig(provider = "openai", model = "gpt-4o", baseUrl = baseUrl),
                    mapOf("openai" to "sk-openai-test"),
                    http,
                )
            }
            // A configuration error: not a client (transport) error, never a cascade trigger.
            assertFalse(LLMClientException::class.java.isInstance(error))
            assertFalse(CascadingFallbackPromptExecutor(listOf(NoopExecutor)).isRetryableAcrossProviders(error))
        }
        assertTrue(http.created.isEmpty())
    }

    @Test
    fun `latched createLLMClient accepts https on a public address`() {
        WorkflowHostPolicy.requirePublicLlmEndpoints()
        val http = CapturingHttpClientFactory()
        createLLMClient(
            emptyList(),
            LLModelConfig(provider = "openai", model = "gpt-4o", baseUrl = "https://8.8.8.8/v1"),
            mapOf("openai" to "sk-openai-test"),
            http,
        )
        assertEquals("https://8.8.8.8/v1", http.created.single().baseUrl)
    }

    @Test
    fun `latched RAG embedder refuses a private embedding base URL`() {
        WorkflowHostPolicy.requirePublicLlmEndpoints()
        val parameters = listOf(
            ConfigurationParameter("rag_embedding_base_url", JsonPrimitive("http://127.0.0.1:8080/v1")),
            ConfigurationParameter("rag_embedding_api_key", JsonPrimitive("sk-test")),
        )
        val error = assertThrows(LlmEndpointNotAllowedException::class.java) {
            EmbedderFactory.Default.create(parameters)
        }
        assertTrue(error.message!!.contains("rag_embedding"), error.message)
    }

    @Test
    fun `an unresolvable provider default is left to the request path, a user host is not`() {
        WorkflowHostPolicy.requirePublicLlmEndpoints()
        val dnsDown: (String) -> List<InetAddress> = { throw UnknownHostException(it) }

        // A DNS blip on api.openai.com must not become a non-retryable configuration error.
        LlmEndpointPolicy.check("https://api.openai.com/v1", "openai", providerDefault = true, resolve = dnsDown)
        assertThrows(LlmEndpointNotAllowedException::class.java) {
            LlmEndpointPolicy.check("https://llm.example.com/v1", "openai", providerDefault = false, resolve = dnsDown)
        }
        // The default-host leniency only covers DNS: a local default is still refused.
        assertThrows(LlmEndpointNotAllowedException::class.java) {
            LlmEndpointPolicy.check("http://localhost:1234/v1", "lmstudio", providerDefault = true, resolve = dnsDown)
        }
    }

    @Test
    fun `custom endpoint means a different origin than the provider default`() {
        val default = "https://api.openai.com/v1"
        assertFalse(LlmEndpointPolicy.isCustomEndpoint(null, default))
        assertFalse(LlmEndpointPolicy.isCustomEndpoint("  ", default))
        assertFalse(LlmEndpointPolicy.isCustomEndpoint("https://API.openai.com:443/v2", default))
        assertTrue(LlmEndpointPolicy.isCustomEndpoint("https://attacker.example/v1", default))
        assertTrue(LlmEndpointPolicy.isCustomEndpoint("http://api.openai.com/v1", default))
        assertTrue(LlmEndpointPolicy.isCustomEndpoint("https://api.openai.com:8443/v1", default))
        assertTrue(LlmEndpointPolicy.isCustomEndpoint("not a url", default))
    }

    // ---------------------------------------------------------------- explicit keys for custom endpoints

    @Test
    fun `explicit-keys latch refuses a custom base_url without a run-supplied key`() {
        WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints()
        val http = CapturingHttpClientFactory()

        for (config in listOf(
            LLModelConfig(provider = "openai", model = "gpt-5.5", baseUrl = "https://attacker.example/v1"),
            LLModelConfig(provider = "openrouter", model = "openai/gpt-5.5", baseUrl = "https://attacker.example"),
            LLModelConfig(provider = "anthropic", model = "claude-opus-5", baseUrl = "https://attacker.example/v1"),
        )) {
            // Whatever OPENAI_API_KEY / OPENROUTER_API_KEY / ANTHROPIC_API_KEY the host has, none is used.
            val error = assertThrows(IllegalStateException::class.java) {
                createLLMClient(emptyList(), config, emptyMap(), http)
            }
            assertTrue(error.message!!.contains("custom base_url"), error.message)
        }
        assertTrue(http.created.isEmpty(), "no client may be built with an environment key")
    }

    @Test
    fun `explicit-keys latch sends only the run-supplied key to a custom base_url`() {
        WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints()
        val http = CapturingHttpClientFactory()

        createLLMClient(
            emptyList(),
            LLModelConfig(provider = "openai", model = "gpt-5.5", baseUrl = "https://attacker.example/v1"),
            mapOf("openai" to "sk-user-supplied"),
            http,
        )

        val created = http.created.single()
        assertEquals("https://attacker.example/v1", created.baseUrl)
        assertEquals("Bearer sk-user-supplied", created.headers["Authorization"])
    }

    @Test
    fun `explicit-keys latch leaves the provider default endpoint alone`() {
        WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints()
        val http = CapturingHttpClientFactory()
        // Same origin as the default (only the path differs): not a custom endpoint.
        createLLMClient(
            emptyList(),
            LLModelConfig(provider = "openai", model = "gpt-5.5", baseUrl = "https://api.openai.com/v1"),
            mapOf("openai" to "sk-openai-test"),
            http,
        )
        assertEquals("https://api.openai.com/v1", http.created.single().baseUrl)
    }

    @Test
    fun `explicit-keys latch keeps environment keys away from a custom RAG embedding host`() {
        WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints()
        assertTrue(com.fartech.agents.tools.isCustomEmbeddingEndpoint("https://attacker.example/v1"))
        assertFalse(com.fartech.agents.tools.isCustomEmbeddingEndpoint("https://api.openai.com/v1"))
        assertFalse(com.fartech.agents.tools.isCustomEmbeddingEndpoint("https://openrouter.ai/api/v1"))
        val env = mapOf("OPENAI_API_KEY" to "sk-host-env")
        val custom = listOf(ConfigurationParameter("rag_embedding_base_url", JsonPrimitive("https://attacker.example/v1")))
        assertEquals("", com.fartech.agents.tools.resolveEmbeddingApiKeyFor(custom, "https://attacker.example/v1", env))
        // The default embedding host still falls back to the host's key.
        assertEquals("sk-host-env", com.fartech.agents.tools.resolveEmbeddingApiKeyFor(emptyList(), "https://api.openai.com/v1", env))
        // Without the latch (CLI / library) the environment key is used as before.
        WorkflowHostPolicy.resetForTests()
        assertEquals("sk-host-env", com.fartech.agents.tools.resolveEmbeddingApiKeyFor(custom, "https://attacker.example/v1", env))
    }

    private object NoopExecutor : ai.koog.prompt.executor.model.PromptExecutor() {
        override suspend fun execute(
            prompt: ai.koog.prompt.Prompt,
            model: ai.koog.prompt.llm.LLModel,
            tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
        ): ai.koog.prompt.message.Message.Assistant = error("unused")

        override suspend fun moderate(
            prompt: ai.koog.prompt.Prompt,
            model: ai.koog.prompt.llm.LLModel,
        ): ai.koog.prompt.dsl.ModerationResult = error("unused")

        override fun executeStreaming(
            prompt: ai.koog.prompt.Prompt,
            model: ai.koog.prompt.llm.LLModel,
            tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
        ): kotlinx.coroutines.flow.Flow<ai.koog.prompt.streaming.StreamFrame> = error("unused")

        override fun close() = Unit
    }
}
