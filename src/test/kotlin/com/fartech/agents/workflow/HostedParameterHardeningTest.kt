package com.fartech.agents.workflow

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import ai.koog.prompt.cache.memory.InMemoryPromptCache
import com.fartech.agents.commons.CapturingHttpClientFactory
import com.fartech.agents.commons.LongTermMemoryInstall
import com.fartech.agents.commons.McpServerConfig
import com.fartech.agents.commons.McpServerNotAllowedException
import com.fartech.agents.commons.ServiceEndpointNotAllowedException
import com.fartech.agents.commons.ServiceEndpointPolicy
import com.fartech.agents.commons.appendingTraceFileWriter
import com.fartech.agents.commons.createLLMClient
import com.fartech.agents.commons.determineCachePolicy
import com.fartech.agents.commons.effectiveCachePolicy
import com.fartech.agents.commons.getLLMGroupConfig
import com.fartech.agents.commons.requireAllowedMcpServers
import com.fartech.agents.commons.resolveTracePath
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.nio.file.Paths
import java.util.Collections
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.jvm.isAccessible
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On braidrun-web every agent step runs in the web JVM, and everything under an agent's
 * `overrides:` is user input. These tests feed each host-resource parameter through a parsed
 * workflow's `overrides:` into the executor's real agent build ([WorkflowExecutor] `createAgent`
 * / `prepareAgentRuntime`) and check what the host latches in [WorkflowHostPolicy] make of it:
 *
 * - `tracing_file_path`, `long_term_memory_namespace`: [WorkflowHostPolicy.requireTenantScopedStorage]
 * - `langfuse_url`, MCP server URLs, `cache_policy: redis`: [WorkflowHostPolicy.requirePublicServiceEndpoints]
 * - stdio `mcp_servers`: [WorkflowHostPolicy.refuseStdioMcpServers]
 * - first-party keys for OpenRouter-routed providers: never sent to openrouter.ai (no latch)
 *
 * The latches are process-global, so every test resets them.
 */
class HostedParameterHardeningTest {

    @TempDir
    lateinit var tempDir: File

    private val capturedNamespaces: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setUp() {
        WorkflowHostPolicy.resetForTests()
        LongTermMemoryInstall.storageProvider = { namespace ->
            capturedNamespaces += namespace
            InMemoryRecordStorage(defaultNamespace = namespace)
        }
    }

    @AfterEach
    fun tearDown() {
        WorkflowHostPolicy.resetForTests()
        LongTermMemoryInstall.storageProvider = null
    }

    // ==================== Fixtures ====================

    private fun declareHostPolicy() {
        WorkflowHostPolicy.requirePublicServiceEndpoints()
        WorkflowHostPolicy.refuseStdioMcpServers()
        WorkflowHostPolicy.requireTenantScopedStorage()
    }

    /** The web injects the acting user as a protected base parameter. */
    private fun hostExecutor(userId: String? = HOST_USER) = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = listOfNotNull(userId?.let { ConfigurationParameter("user_id", JsonPrimitive(it)) }),
        enableMonitoring = false
    )

    /**
     * The `worker` agent of a parsed workflow whose `overrides:` holds [overridesYaml] (plus an
     * offline OpenAI model, and a key for the default OpenRouter fallback client, unless
     * [llmConfigYaml] replaces them).
     */
    private fun agentWithOverrides(
        overridesYaml: String,
        llmConfigYaml: String = """
            openai_api_key: sk-test-openai
            openrouter_api_key: sk-or-v1-fallback
            llm_config:
              models:
                - provider: openai
                  model: gpt-4o-mini
        """.trimIndent(),
    ): AgentDefinition {
        val overrides = (
            """
            disable_skills: true
            tool_set: [exit]
            """.trimIndent() + "\n" + llmConfigYaml + "\n" + overridesYaml.trimIndent()
            ).prependIndent("      ")
        val yaml = "name: hosted-overrides\n" +
            "version: 1.0.0\n" +
            "agents:\n" +
            "  worker:\n" +
            "    overrides:\n" +
            overrides + "\n" +
            "workflow:\n" +
            "  - step: work\n" +
            "    agent: worker\n" +
            "    input: hi\n"
        return WorkflowParser.parseYaml(yaml).agents.getValue("worker")
    }

    /** Builds the step agent exactly as a workflow run would, then closes it. */
    private fun WorkflowExecutor.buildStepAgent(agent: AgentDefinition) = runBlocking {
        val create = WorkflowExecutor::class.declaredFunctions.single { it.name == "createAgent" }
        create.isAccessible = true
        val managed = try {
            create.callSuspendBy(
                mapOf(
                    create.instanceParameter!! to this@buildStepAgent,
                    create.parameters.single { it.name == "agentDef" } to agent,
                    create.parameters.single { it.name == "sessionId" } to SESSION_ID
                )
            )!!
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        val aiAgent = managed.javaClass.getDeclaredField("agent").apply { isAccessible = true }.get(managed)
        (aiAgent as AIAgent<*, *>).close()
    }

    /** The parameters the step agent is built from (overrides + protected runtime parameters). */
    private fun WorkflowExecutor.stepParameters(agent: AgentDefinition): List<ConfigurationParameter> = runBlocking {
        val prepare = WorkflowExecutor::class.declaredFunctions.single { it.name == "prepareAgentRuntime" }
        prepare.isAccessible = true
        val runtime = prepare.callSuspendBy(
            mapOf(
                prepare.instanceParameter!! to this@stepParameters,
                prepare.parameters.single { it.name == "agentDef" } to agent,
                prepare.parameters.single { it.name == "sessionId" } to SESSION_ID
            )
        )!!
        @Suppress("UNCHECKED_CAST")
        runtime.javaClass.getDeclaredField("parameters").apply { isAccessible = true }.get(runtime)
            as List<ConfigurationParameter>
    }

    private inline fun <reified T : Throwable> assertRefused(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            return assertIs<T>(e, "expected ${T::class.simpleName}, got $e")
        }
        fail("expected ${T::class.simpleName}, but the agent was built")
    }

    // ==================== tracing_file_path ====================

    @Nested
    inner class TracingFilePath {

        private val victim get() = File(tempDir, "host-owned.conf")

        private fun tracingAgent() = agentWithOverrides(
            """
            tracing_enabled: true
            tracing_file_path: ${victim.absolutePath}
            """
        )

        @Test
        fun `under the host policy a user tracing_file_path is ignored for the default trace directory`() {
            declareHostPolicy()
            victim.writeText("host data")

            val path = resolveTracePath(hostExecutor().stepParameters(tracingAgent()))

            assertEquals(
                Paths.get(".workflow-runs/traces/agent-$SESSION_ID.ndjson").toAbsolutePath().normalize(),
                path
            )
            hostExecutor().buildStepAgent(tracingAgent())
            assertEquals("host data", victim.readText())
        }

        @Test
        fun `without the host policy the explicit path is honoured and appended to, never truncated`() {
            victim.writeText("host data\n")

            val path = assertNotNull(resolveTracePath(hostExecutor().stepParameters(tracingAgent())))
            assertEquals(victim.toPath().toAbsolutePath().normalize(), path)

            // The writer the Tracing feature installs; initialize() opens the file at run start.
            val writer = appendingTraceFileWriter(path)
            runBlocking {
                writer.initialize()
                writer.close()
            }
            assertEquals("host data\n", victim.readText())
        }
    }

    // ==================== langfuse_url ====================

    @Nested
    inner class LangfuseUrl {

        private fun langfuseAgent(url: String) = agentWithOverrides(
            """
            enable_langfuse_tracing: true
            langfuse_public_key: pk-test
            langfuse_secret_key: sk-test
            langfuse_url: "$url"
            """
        )

        @Test
        fun `under the host policy a metadata-service langfuse_url fails the build`() {
            declareHostPolicy()

            val error = assertRefused<ServiceEndpointNotAllowedException> {
                hostExecutor().buildStepAgent(langfuseAgent("http://169.254.169.254/latest"))
            }
            assertContains(error.message!!, "Langfuse")
            assertContains(error.message!!, "only https is allowed")
            assertFalse(error.message!!.contains("/latest"), "the path is redacted")
        }

        @Test
        fun `under the host policy an https langfuse_url on a loopback address fails the build`() {
            declareHostPolicy()

            assertRefused<ServiceEndpointNotAllowedException> {
                hostExecutor().buildStepAgent(langfuseAgent("https://127.0.0.1:3000"))
            }
        }

        @Test
        fun `an https langfuse_url on a public host passes`() {
            declareHostPolicy()
            val publicAddress = InetAddress.getByAddress("cloud.langfuse.com", byteArrayOf(34, 117, 59, 81))

            ServiceEndpointPolicy.check("https://cloud.langfuse.com", "Langfuse", ServiceEndpointPolicy.HTTPS) {
                listOf(publicAddress)
            }
        }

        @Test
        fun `without the host policy any langfuse_url is accepted`() {
            hostExecutor().buildStepAgent(langfuseAgent("http://127.0.0.1:3000"))
        }
    }

    // ==================== long_term_memory_namespace ====================

    @Nested
    inner class LongTermMemoryNamespace {

        private fun memoryAgent(extra: String) = agentWithOverrides("long_term_memory_enabled: true\n$extra")

        @Test
        fun `under the host policy another tenant's namespace is nested under the acting user`() {
            declareHostPolicy()

            hostExecutor().buildStepAgent(memoryAgent("long_term_memory_namespace: \"ltm:bob:bobs-session\""))

            assertEquals(listOf("ltm:$HOST_USER:ltm:bob:bobs-session"), capturedNamespaces)
        }

        @Test
        fun `a user_id override cannot move the prefix to another tenant`() {
            declareHostPolicy()

            hostExecutor().buildStepAgent(
                memoryAgent("user_id: bob\nlong_term_memory_namespace: \"ltm:bob:bobs-session\"")
            )

            assertEquals(listOf("ltm:$HOST_USER:ltm:bob:bobs-session"), capturedNamespaces)
        }

        @Test
        fun `a namespace already under the acting user is kept as is`() {
            declareHostPolicy()

            hostExecutor().buildStepAgent(memoryAgent("long_term_memory_namespace: \"ltm:$HOST_USER:project-x\""))

            assertEquals(listOf("ltm:$HOST_USER:project-x"), capturedNamespaces)
        }

        @Test
        fun `the default namespace is unchanged`() {
            declareHostPolicy()

            hostExecutor().buildStepAgent(memoryAgent(""))

            assertEquals(listOf("ltm:$HOST_USER:$SESSION_ID"), capturedNamespaces)
        }

        @Test
        fun `under the host policy an agent without a host user_id gets no long-term memory`() {
            declareHostPolicy()

            hostExecutor(userId = null).buildStepAgent(memoryAgent("long_term_memory_namespace: \"ltm:bob:bobs-session\""))

            assertEquals(emptyList(), capturedNamespaces)
        }

        @Test
        fun `a host user_id containing a colon gets no long-term memory`() {
            declareHostPolicy()

            // `ltm:a:` would be a prefix of user `a:b`'s namespaces.
            hostExecutor(userId = "a:b").buildStepAgent(memoryAgent(""))

            assertEquals(emptyList(), capturedNamespaces)
        }

        @Test
        fun `without the host policy a custom namespace is used verbatim`() {
            hostExecutor().buildStepAgent(memoryAgent("long_term_memory_namespace: \"ltm:bob:bobs-session\""))

            assertEquals(listOf("ltm:bob:bobs-session"), capturedNamespaces)
        }
    }

    // ==================== cache_policy: redis ====================

    @Nested
    inner class RedisPromptCache {

        private val redisUrl = "redis://127.0.0.1:6391"

        private fun redisAgent() = agentWithOverrides(
            """
            cache_policy: redis
            redis_client_url: "$redisUrl"
            """
        )

        @Suppress("UNCHECKED_CAST")
        private fun pooledRedisUrls(): Set<String> =
            (Class.forName("com.fartech.agents.commons.PromptExecutorFactoryKt")
                .getDeclaredField("pooledRedisClients")
                .apply { isAccessible = true }
                .get(null) as Map<String, *>).keys

        @Test
        fun `under the host policy cache_policy redis falls back to the in-memory cache`() {
            declareHostPolicy()
            val parameters = hostExecutor().stepParameters(redisAgent())

            assertEquals("memory", effectiveCachePolicy(parameters))
            assertIs<InMemoryPromptCache>(determineCachePolicy(parameters))
            hostExecutor().buildStepAgent(redisAgent())
            assertFalse(redisUrl in pooledRedisUrls(), "no Redis client for the user's URL")
        }

        @Test
        fun `without the host policy cache_policy redis is kept`() {
            assertEquals("redis", effectiveCachePolicy(hostExecutor().stepParameters(redisAgent())))
        }
    }

    // ==================== mcp_servers ====================

    @Nested
    inner class McpServers {

        private val marker get() = File(tempDir, "mcp-ran-on-host.marker")

        private fun stdioAgent() = agentWithOverrides(
            """
            mcp_servers:
              escape:
                command: sh
                args: ["-c", "touch '${marker.absolutePath}'"]
            """
        )

        private fun urlAgent(url: String, type: String) = agentWithOverrides(
            """
            mcp_servers:
              remote:
                type: $type
                url: "$url"
            """
        )

        @Test
        fun `under the host policy a stdio MCP server fails the build before its command runs`() {
            declareHostPolicy()

            val error = assertRefused<McpServerNotAllowedException> { hostExecutor().buildStepAgent(stdioAgent()) }

            assertContains(error.message!!, "escape")
            Thread.sleep(200)
            assertFalse(marker.exists(), "the stdio command must not have started")
        }

        @Test
        fun `a disabled stdio MCP server is not refused`() {
            declareHostPolicy()

            hostExecutor().buildStepAgent(
                agentWithOverrides(
                    """
                    mcp_servers:
                      escape:
                        command: sh
                        enabled: false
                    """
                )
            )
        }

        @Test
        fun `without the host policy stdio MCP servers pass the check`() {
            // Not built end to end: a stdio server that exits before the MCP handshake (like the
            // marker command above) leaves the handshake waiting forever.
            requireAllowedMcpServers(mapOf("escape" to McpServerConfig(command = "sh")))
        }

        @Test
        fun `under the host policy private or plaintext MCP URLs fail the build`() {
            declareHostPolicy()

            for ((url, type) in listOf(
                "http://169.254.169.254/sse" to "sse",
                "https://127.0.0.1:8931/mcp" to "http",
                "wss://10.0.0.5/ws" to "websocket",
                "ws://203.0.113.10/ws" to "websocket",
            )) {
                val error = assertRefused<ServiceEndpointNotAllowedException> {
                    hostExecutor().buildStepAgent(urlAgent(url, type))
                }
                assertContains(error.message!!, "MCP server 'remote'", message = url)
            }
        }

        @Test
        fun `wss is accepted for a websocket MCP server on a public host`() {
            declareHostPolicy()
            val publicAddress = InetAddress.getByAddress("mcp.example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

            ServiceEndpointPolicy.check("wss://mcp.example.com/ws", "MCP server 'remote'", ServiceEndpointPolicy.HTTPS_OR_WSS) {
                listOf(publicAddress)
            }
        }
    }

    // ==================== OpenRouter keys ====================

    @Nested
    inner class OpenRouterKeys {

        private fun routedAgent(provider: String, keysYaml: String) = agentWithOverrides(
            keysYaml,
            llmConfigYaml = """
                llm_config:
                  models:
                    - provider: $provider
                      model: some-model
            """.trimIndent()
        )

        /** Builds the step's chat client from the overrides-derived parameters, recording its HTTP clients. */
        private fun buildClient(agent: AgentDefinition): Pair<CapturingHttpClientFactory, Result<*>> {
            val parameters = hostExecutor().stepParameters(agent)
            val keys = parameters.parameter("llm_provider_keys", mapOf<String, String>())
            val http = CapturingHttpClientFactory()
            val result = runCatching {
                createLLMClient(parameters, parameters.getLLMGroupConfig().models.single(), keys, http)
            }
            return http to result
        }

        @Test
        fun `a mistral model sends the OpenRouter key, not the Mistral one, to openrouter_ai`() {
            val (http, result) = buildClient(
                routedAgent(
                    "mistral",
                    """
                    mistral_api_key: $FIRST_PARTY_KEY
                    llm_provider_keys:
                      mistral: $FIRST_PARTY_KEY
                      openrouter: sk-or-v1-test
                    """
                )
            )

            result.getOrThrow()
            val client = http.created.single()
            assertEquals("https://openrouter.ai", client.baseUrl)
            assertTrue(client.headers.values.any { "sk-or-v1-test" in it }, "the OpenRouter key authenticates")
            assertFalse(client.headers.values.any { FIRST_PARTY_KEY in it }, "the Mistral key stays home")
        }

        @Test
        fun `first-party keys alone are never sent to openrouter_ai`() {
            for ((provider, keysYaml) in listOf(
                "mistral" to "mistral_api_key: $FIRST_PARTY_KEY",
                "perplexity" to "llm_provider_keys:\n  perplexity: $FIRST_PARTY_KEY",
                "qwen" to "qwen_api_key: $FIRST_PARTY_KEY",
                "cohere" to "cohere_api_key: $FIRST_PARTY_KEY",
            )) {
                val (http, result) = buildClient(routedAgent(provider, keysYaml))

                // With no OpenRouter key the build fails, unless this machine has OPENROUTER_API_KEY.
                result.exceptionOrNull()?.let { error ->
                    assertContains(error.message!!, "OpenRouter", message = provider)
                    assertContains(error.message!!, "openrouter_api_key", message = provider)
                }
                assertFalse(
                    http.created.any { created -> created.headers.values.any { FIRST_PARTY_KEY in it } },
                    "$provider: a first-party key reached an openrouter.ai client"
                )
            }
        }

        @Test
        fun `an openrouter model is unaffected`() {
            val (http, result) = buildClient(
                routedAgent("openrouter", "openrouter_api_key: sk-or-v1-direct")
            )

            result.getOrThrow()
            assertTrue(http.created.single().headers.values.any { "sk-or-v1-direct" in it })
        }
    }

    // ==================== The latches ====================

    @Test
    fun `the latches are off by default and one-way`() {
        assertFalse(WorkflowHostPolicy.requiresPublicServiceEndpoints)
        assertFalse(WorkflowHostPolicy.refusesStdioMcpServers)
        assertFalse(WorkflowHostPolicy.requiresTenantScopedStorage)

        declareHostPolicy()
        declareHostPolicy()

        assertTrue(WorkflowHostPolicy.requiresPublicServiceEndpoints)
        assertTrue(WorkflowHostPolicy.refusesStdioMcpServers)
        assertTrue(WorkflowHostPolicy.requiresTenantScopedStorage)
    }

    private companion object {
        const val HOST_USER = "alice"
        const val SESSION_ID = "hosted-session"
        const val FIRST_PARTY_KEY = "first-party-vendor-key"
    }
}
