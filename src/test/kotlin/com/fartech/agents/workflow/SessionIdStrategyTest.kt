package com.fartech.agents.workflow

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for session_id and session_id_strategy fields in AgentDefinition,
 * and resolveSessionId logic in WorkflowExecutor.
 */
class SessionIdStrategyTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ============================================================
    // 1. AgentDefinition field tests
    // ============================================================

    @Test
    fun `AgentDefinition default sessionId is null`() {
        val agent = AgentDefinition()
        assertEquals(null, agent.sessionId)
    }

    @Test
    fun `AgentDefinition default sessionIdStrategy is null`() {
        val agent = AgentDefinition()
        assertEquals(null, agent.sessionIdStrategy)
    }

    @Test
    fun `AgentDefinition with sessionId and strategy`() {
        val agent = AgentDefinition(
            sessionId = "my-fixed-session",
            sessionIdStrategy = "fixed"
        )
        assertEquals("my-fixed-session", agent.sessionId)
        assertEquals("fixed", agent.sessionIdStrategy)
    }

    @Test
    fun `AgentDefinition serialization includes session fields`() {
        val agent = AgentDefinition(
            sessionId = "test-session",
            sessionIdStrategy = "per_agent"
        )
        val jsonStr = json.encodeToString(AgentDefinition.serializer(), agent)
        assertTrue(jsonStr.contains("\"session_id\":\"test-session\""))
        assertTrue(jsonStr.contains("\"session_id_strategy\":\"per_agent\""))
    }

    @Test
    fun `AgentDefinition deserialization reads session fields`() {
        val jsonStr = """{"session_id":"abc123","session_id_strategy":"per_execution"}"""
        val agent = json.decodeFromString(AgentDefinition.serializer(), jsonStr)
        assertEquals("abc123", agent.sessionId)
        assertEquals("per_execution", agent.sessionIdStrategy)
    }

    @Test
    fun `AgentDefinition serialization roundtrip preserves session fields`() {
        val original = AgentDefinition(
            sessionId = "round-trip",
            sessionIdStrategy = "fixed",
            historyEnabled = true,
            historyStorageRoot = "/tmp/history",
            historyMaxMessages = 100
        )
        val jsonStr = json.encodeToString(AgentDefinition.serializer(), original)
        val decoded = json.decodeFromString(AgentDefinition.serializer(), jsonStr)
        assertEquals(original.sessionId, decoded.sessionId)
        assertEquals(original.sessionIdStrategy, decoded.sessionIdStrategy)
        assertEquals(original.historyEnabled, decoded.historyEnabled)
        assertEquals(original.historyStorageRoot, decoded.historyStorageRoot)
        assertEquals(original.historyMaxMessages, decoded.historyMaxMessages)
    }

    // ============================================================
    // 2. buildLegacyParameters tests
    // ============================================================

    @Test
    fun `buildLegacyParameters includes session_id when set`() {
        val agent = AgentDefinition(sessionId = "my-session")
        val params = agent.resolveParameters()
        assertEquals(JsonPrimitive("my-session"), params["session_id"])
    }

    @Test
    fun `buildLegacyParameters includes session_id_strategy when set`() {
        val agent = AgentDefinition(sessionIdStrategy = "per_agent")
        val params = agent.resolveParameters()
        assertEquals(JsonPrimitive("per_agent"), params["session_id_strategy"])
    }

    @Test
    fun `buildLegacyParameters omits session fields when null`() {
        val agent = AgentDefinition()
        val params = agent.resolveParameters()
        assertTrue("session_id" !in params)
        assertTrue("session_id_strategy" !in params)
    }

    @Test
    fun `buildLegacyParameters includes all history fields`() {
        val agent = AgentDefinition(
            sessionId = "sid",
            sessionIdStrategy = "fixed",
            historyEnabled = true,
            historyStorageRoot = "/custom/history",
            restoreSessionId = "old-session",
            historyMaxMessages = 25
        )
        val params = agent.resolveParameters()
        assertEquals(JsonPrimitive("sid"), params["session_id"])
        assertEquals(JsonPrimitive("fixed"), params["session_id_strategy"])
        assertEquals(JsonPrimitive(true), params["history_enabled"])
        assertEquals(JsonPrimitive("/custom/history"), params["history_storage_root"])
        assertEquals(JsonPrimitive("old-session"), params["restore_session_id"])
        assertEquals(JsonPrimitive(25), params["history_max_messages"])
    }

    @Test
    fun `buildLegacyParameters includes unified storage fields`() {
        val agent = AgentDefinition(
            persistenceStorageType = "sql",
            persistenceNamespace = "braidrun-agent",
            persistenceCollectionPrefix = "agent",
            sqlDialect = "sqlite",
            sqlDatabasePath = ".braidrun-agent.sqlite",
            sqlTableName = "dy_documents",
            mongoConnectionString = "mongodb://localhost:27017",
            mongoDbName = "braidrun",
            mongoCollectionPrefix = "agent",
            mongoDocumentCollection = "dy_documents"
        )

        val params = agent.resolveParameters()

        assertEquals(JsonPrimitive("sql"), params["persistence_storage_type"])
        assertEquals(JsonPrimitive("braidrun-agent"), params["persistence_namespace"])
        assertEquals(JsonPrimitive("agent"), params["persistence_collection_prefix"])
        assertEquals(JsonPrimitive("sqlite"), params["sql_dialect"])
        assertEquals(JsonPrimitive(".braidrun-agent.sqlite"), params["sql_database_path"])
        assertEquals(JsonPrimitive("dy_documents"), params["sql_table_name"])
        assertEquals(JsonPrimitive("mongodb://localhost:27017"), params["mongo_connection_string"])
        assertEquals(JsonPrimitive("braidrun"), params["mongo_db_name"])
        assertEquals(JsonPrimitive("agent"), params["mongo_collection_prefix"])
        assertEquals(JsonPrimitive("dy_documents"), params["mongo_document_collection"])
    }

    // ============================================================
    // 3. resolveSessionId tests (WorkflowExecutor)
    // ============================================================

    private fun createExecutor(): WorkflowExecutor {
        return WorkflowExecutor(
            httpAccess = com.fartech.ftapp2.commonsKt.HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
    }

    private fun createContext(
        workflowName: String = "test-workflow",
        executionId: String = "exec-001"
    ): WorkflowExecutionContext {
        return WorkflowExecutionContext(
            workflowName = workflowName,
            executionId = executionId
        )
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun responseMetaInfo(): ResponseMetaInfo {
        return ResponseMetaInfo(timestamp = Instant.parse("2026-01-01T00:00:00Z"))
    }

    /**
     * Koog 1.0.0 — the WorkflowExecutor helper used to be `extractResponseText`
     * accepting the (now-removed) top-level `Message.Response` interface and
     * branching on Assistant vs Reasoning variants. Post-migration it's
     * `extractReasoningText` taking a `MessagePart.Reasoning` directly,
     * because reasoning lives inside `Message.Assistant.parts` now and
     * assistant text comes back via `Message.textContent()`. The test
     * mirrors the new shape: reasoning-extraction tests reflect into
     * `extractReasoningText`; assistant-text tests just call `textContent()`
     * on a synthesised Message.Assistant.
     */
    private fun extractReasoningText(
        executor: WorkflowExecutor,
        part: MessagePart.Reasoning,
    ): String? {
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "extractReasoningText",
            MessagePart.Reasoning::class.java,
        )
        method.isAccessible = true
        return method.invoke(executor, part) as String?
    }

    private fun buildSummaryWithPreview(
        executor: WorkflowExecutor,
        prefix: String,
        content: String?,
        maxLength: Int
    ): String {
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "buildSummaryWithPreview",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(executor, prefix, content, maxLength) as String
    }

    private fun truncateDetail(executor: WorkflowExecutor, content: String?, maxLength: Int): String? {
        val method = WorkflowExecutor::class.java.getDeclaredMethod(
            "truncateDetail",
            String::class.java,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(executor, content, maxLength) as String?
    }

    @Test
    fun `resolveSessionId auto strategy generates executionId-agentName-stepName`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition()

        val sessionId = executor.resolveSessionId(agentDef, context, "researcher", "step-1")
        assertEquals("exec-001:researcher:step-1", sessionId)
    }

    @Test
    fun `resolveSessionId auto strategy is default when no strategy set`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition(sessionIdStrategy = null)

        val sessionId = executor.resolveSessionId(agentDef, context, "agent1", "do-research")
        assertEquals("exec-001:agent1:do-research", sessionId)
    }

    @Test
    fun `resolveSessionId per_execution strategy generates executionId-agentName`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition(sessionIdStrategy = "per_execution")

        val sessionId = executor.resolveSessionId(agentDef, context, "writer", "step-2")
        assertEquals("exec-001:writer", sessionId)
    }

    @Test
    fun `resolveSessionId per_agent strategy generates workflowName-agentName`() {
        val executor = createExecutor()
        val context = createContext(workflowName = "my-workflow")
        val agentDef = AgentDefinition(sessionIdStrategy = "per_agent")

        val sessionId = executor.resolveSessionId(agentDef, context, "analyst", "step-3")
        assertEquals("my-workflow:analyst", sessionId)
    }

    @Test
    fun `resolveSessionId fixed strategy uses sessionId field`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition(
            sessionId = "custom-session-123",
            sessionIdStrategy = "fixed"
        )

        val sessionId = executor.resolveSessionId(agentDef, context, "agent1", "step-1")
        assertEquals("custom-session-123", sessionId)
    }

    @Test
    fun `resolveSessionId fixed strategy falls back to workflowName-agentName when no sessionId`() {
        val executor = createExecutor()
        val context = createContext(workflowName = "fallback-wf")
        val agentDef = AgentDefinition(sessionIdStrategy = "fixed")

        val sessionId = executor.resolveSessionId(agentDef, context, "agent1", "step-1")
        assertEquals("fallback-wf:agent1", sessionId)
    }

    @Test
    fun `resolveSessionId unknown strategy defaults to auto`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition(sessionIdStrategy = "unknown_strategy")

        val sessionId = executor.resolveSessionId(agentDef, context, "agent1", "step-1")
        assertEquals("exec-001:agent1:step-1", sessionId)
    }

    @Test
    fun `resolveSessionId reads strategy from overrides`() {
        val executor = createExecutor()
        val context = createContext(workflowName = "wf1")
        val agentDef = AgentDefinition(
            overrides = mapOf(
                "session_id_strategy" to JsonPrimitive("per_agent")
            )
        )

        val sessionId = executor.resolveSessionId(agentDef, context, "agent1", "step-1")
        assertEquals("wf1:agent1", sessionId)
    }

    @Test
    fun `resolveSessionId reads fixed session_id from overrides`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition(
            overrides = mapOf(
                "session_id_strategy" to JsonPrimitive("fixed"),
                "session_id" to JsonPrimitive("override-sid")
            )
        )

        val sessionId = executor.resolveSessionId(agentDef, context, "agent1", "step-1")
        assertEquals("override-sid", sessionId)
    }

    // ============================================================
    // 4. Session isolation scenario tests
    // ============================================================

    @Test
    fun `different steps get different session_ids with auto strategy`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition()

        val sid1 = executor.resolveSessionId(agentDef, context, "agent1", "step-1")
        val sid2 = executor.resolveSessionId(agentDef, context, "agent1", "step-2")
        val sid3 = executor.resolveSessionId(agentDef, context, "agent2", "step-1")

        assertTrue(sid1 != sid2, "Same agent, different steps should have different sessions")
        assertTrue(sid1 != sid3, "Different agents should have different sessions")
        assertTrue(sid2 != sid3, "Different agents and steps should have different sessions")
    }

    @Test
    fun `same agent same step in different executions get different session_ids with auto`() {
        val executor = createExecutor()
        val ctx1 = createContext(executionId = "exec-001")
        val ctx2 = createContext(executionId = "exec-002")
        val agentDef = AgentDefinition()

        val sid1 = executor.resolveSessionId(agentDef, ctx1, "agent1", "step-1")
        val sid2 = executor.resolveSessionId(agentDef, ctx2, "agent1", "step-1")

        assertTrue(sid1 != sid2, "Different executions should have different sessions")
    }

    @Test
    fun `per_agent strategy shares across executions`() {
        val executor = createExecutor()
        val ctx1 = createContext(executionId = "exec-001", workflowName = "wf")
        val ctx2 = createContext(executionId = "exec-002", workflowName = "wf")
        val agentDef = AgentDefinition(sessionIdStrategy = "per_agent")

        val sid1 = executor.resolveSessionId(agentDef, ctx1, "analyst", "step-1")
        val sid2 = executor.resolveSessionId(agentDef, ctx2, "analyst", "step-1")

        assertEquals(sid1, sid2, "per_agent strategy should share session across executions")
        assertEquals("wf:analyst", sid1)
    }

    @Test
    fun `per_execution strategy shares across steps but not executions`() {
        val executor = createExecutor()
        val ctx1 = createContext(executionId = "exec-001")
        val ctx2 = createContext(executionId = "exec-002")
        val agentDef = AgentDefinition(sessionIdStrategy = "per_execution")

        val sid1_step1 = executor.resolveSessionId(agentDef, ctx1, "agent1", "step-1")
        val sid1_step2 = executor.resolveSessionId(agentDef, ctx1, "agent1", "step-2")
        val sid2_step1 = executor.resolveSessionId(agentDef, ctx2, "agent1", "step-1")

        assertEquals(sid1_step1, sid1_step2, "Same execution, same agent should share session")
        assertTrue(sid1_step1 != sid2_step1, "Different executions should not share session")
    }

    @Test
    fun `parallel tasks get unique session_ids`() {
        val executor = createExecutor()
        val context = createContext()
        val agentDef = AgentDefinition()

        // Simulating parallel task naming convention from WorkflowExecutor
        val sid0 = executor.resolveSessionId(agentDef, context, "agent1", "step-1:parallel:0")
        val sid1 = executor.resolveSessionId(agentDef, context, "agent1", "step-1:parallel:1")
        val sid2 = executor.resolveSessionId(agentDef, context, "agent1", "step-1:parallel:2")

        assertTrue(sid0 != sid1)
        assertTrue(sid1 != sid2)
        assertTrue(sid0 != sid2)
    }

    // ============================================================
    // 5. YAML deserialization tests
    // ============================================================

    @Test
    fun `AgentDefinition from YAML-like JSON with session config`() {
        val jsonStr = """{
            "preset": "universal",
            "overrides": {
                "session_id_strategy": "per_agent",
                "history_enabled": true,
                "history_max_messages": 20,
                "system_prompt": "You are a helpful assistant"
            }
        }"""
        val agent = json.decodeFromString(AgentDefinition.serializer(), jsonStr)
        assertEquals("universal", agent.preset)
        val params = agent.resolveParameters()
        assertNotNull(params["session_id_strategy"])
        assertEquals(JsonPrimitive("per_agent"), params["session_id_strategy"])
    }

    @Test
    fun `AgentDefinition legacy mode with session fields`() {
        val agent = AgentDefinition(
            type = "universal_agent",
            strategy = "just_work_parallel",
            sessionId = "legacy-sid",
            sessionIdStrategy = "fixed",
            historyEnabled = true,
            historyStorageRoot = "/data/history",
            historyMaxMessages = 30
        )
        val params = agent.resolveParameters()
        assertEquals(JsonPrimitive("legacy-sid"), params["session_id"])
        assertEquals(JsonPrimitive("fixed"), params["session_id_strategy"])
        assertEquals(JsonPrimitive(true), params["history_enabled"])
        assertEquals(JsonPrimitive("/data/history"), params["history_storage_root"])
        assertEquals(JsonPrimitive(30), params["history_max_messages"])
    }

    // ============================================================
    // 6. Monitoring message extraction tests
    // ============================================================

    @Test
    fun `Message_Assistant textContent trims to readable assistant text`() {
        // Koog 1.0.0 — the public 0.x `extractResponseText(Message.Assistant)`
        // was a thin trim around `.content`. The 1.0.0 equivalent is
        // `Message.textContent().trim()` which walks `parts` and joins Text
        // entries. The WorkflowExecutor consumer (the LLM-call event handler)
        // does the same trim before emitting the model_reply event.
        val response = Message.Assistant(
            content = "  你好，世界  ",
            metaInfo = responseMetaInfo(),
        )
        assertEquals("你好，世界", response.textContent().trim())
    }

    @Test
    fun `extractReasoningText falls back to reasoning summary when content blank`() {
        val executor = createExecutor()
        // Koog 1.0.0 — `Message.Reasoning` (top-level message) collapsed to
        // `MessagePart.Reasoning` inside an Assistant message. The data class
        // shape is `(content: List<String>, summary: List<String>?, encrypted: String?, id: String?)`.
        val part = MessagePart.Reasoning(
            content = emptyList(),
            summary = listOf("这是推理摘要"),
            encrypted = null,
            id = "r1",
        )

        val extracted = extractReasoningText(executor, part)

        assertEquals("这是推理摘要", extracted)
    }

    @Test
    fun `extractReasoningText uses encrypted placeholder when reasoning text missing`() {
        val executor = createExecutor()
        val part = MessagePart.Reasoning(
            content = emptyList(),
            summary = null,
            encrypted = "abcdef",
            id = "r2",
        )

        val extracted = extractReasoningText(executor, part)

        assertEquals("[加密推理内容，长度:6]", extracted)
    }

    @Test
    fun `buildSummaryWithPreview and truncateDetail format content correctly`() {
        val executor = createExecutor()

        val summary = buildSummaryWithPreview(
            executor = executor,
            prefix = "💬 模型回复",
            content = "hello\nworld",
            maxLength = 5
        )
        val detail = truncateDetail(executor, "  abcdef  ", 4)

        assertEquals("💬 模型回复：hello...", summary)
        assertEquals("abcd...", detail)
    }
}
