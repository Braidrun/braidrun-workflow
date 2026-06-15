package com.fartech.agents.workflow

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.*

/**
 * 工作流级共享知识库（KnowledgeBaseConfig）综合测试
 *
 * 覆盖：
 * - KnowledgeBaseConfig 数据模型验证
 * - KnowledgeBaseSourceFile 数据模型验证
 * - WorkflowDefinition 知识库字段
 * - WorkflowParser 验证逻辑
 * - YAML 解析 roundtrip
 * - getWorkflowSummary 输出
 */
class KnowledgeBaseTest {

    @BeforeEach
    fun setup() {
        WorkflowMonitor.clear()
    }

    // ==================== KnowledgeBaseConfig Model Tests ====================

    @Test
    fun `KnowledgeBaseConfig defaults are correct`() {
        val config = KnowledgeBaseConfig()
        assertTrue(config.enabled)
        assertNull(config.storageDir)
        assertEquals("text-embedding-3-small", config.embeddingModel)
        assertEquals("openai", config.embeddingProvider)
        assertTrue(config.autoIndexOutputs)
        assertEquals(1000, config.chunkSize)
        assertEquals(200, config.chunkOverlap)
        assertEquals(0, config.maxIndexedDocuments)
        assertEquals(0, config.maxTotalChunks)
        assertTrue(config.sourceFiles.isEmpty())
        assertTrue(config.autoInjectRagTools)
    }

    @Test
    fun `KnowledgeBaseConfig full configuration`() {
        val config = KnowledgeBaseConfig(
            enabled = true,
            storageDir = "./my-kb",
            embeddingModel = "text-embedding-3-large",
            embeddingProvider = "openai",
            autoIndexOutputs = false,
            chunkSize = 2000,
            chunkOverlap = 300,
            maxIndexedDocuments = 50,
            maxTotalChunks = 500,
            sourceFiles = listOf(
                KnowledgeBaseSourceFile(path = "./docs/ref.md", tags = "reference"),
                KnowledgeBaseSourceFile(path = "./docs/guide.md")
            ),
            autoInjectRagTools = false
        )
        assertEquals("./my-kb", config.storageDir)
        assertEquals("text-embedding-3-large", config.embeddingModel)
        assertFalse(config.autoIndexOutputs)
        assertEquals(2000, config.chunkSize)
        assertEquals(300, config.chunkOverlap)
        assertEquals(50, config.maxIndexedDocuments)
        assertEquals(500, config.maxTotalChunks)
        assertEquals(2, config.sourceFiles.size)
        assertFalse(config.autoInjectRagTools)
    }

    @Test
    fun `KnowledgeBaseConfig rejects zero chunk_size`() {
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseConfig(chunkSize = 0)
        }
    }

    @Test
    fun `KnowledgeBaseConfig rejects negative chunk_size`() {
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseConfig(chunkSize = -1)
        }
    }

    @Test
    fun `KnowledgeBaseConfig rejects negative chunk_overlap`() {
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseConfig(chunkOverlap = -1)
        }
    }

    @Test
    fun `KnowledgeBaseConfig rejects chunk_overlap greater than or equal to chunk_size`() {
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseConfig(chunkSize = 500, chunkOverlap = 500)
        }
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseConfig(chunkSize = 500, chunkOverlap = 600)
        }
    }

    @Test
    fun `KnowledgeBaseConfig rejects negative max_indexed_documents`() {
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseConfig(maxIndexedDocuments = -1)
        }
    }

    @Test
    fun `KnowledgeBaseConfig rejects negative max_total_chunks`() {
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseConfig(maxTotalChunks = -1)
        }
    }

    @Test
    fun `KnowledgeBaseConfig allows zero overlap`() {
        val config = KnowledgeBaseConfig(chunkOverlap = 0)
        assertEquals(0, config.chunkOverlap)
    }

    // ==================== KnowledgeBaseSourceFile Model Tests ====================

    @Test
    fun `KnowledgeBaseSourceFile defaults`() {
        val sf = KnowledgeBaseSourceFile(path = "./docs/ref.md")
        assertEquals("./docs/ref.md", sf.path)
        assertEquals("", sf.tags)
    }

    @Test
    fun `KnowledgeBaseSourceFile with tags`() {
        val sf = KnowledgeBaseSourceFile(path = "./data.csv", tags = "data,reference")
        assertEquals("data,reference", sf.tags)
    }

    @Test
    fun `KnowledgeBaseSourceFile rejects blank path`() {
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseSourceFile(path = "")
        }
        assertThrows<IllegalArgumentException> {
            KnowledgeBaseSourceFile(path = "   ")
        }
    }

    // ==================== WorkflowDefinition with KnowledgeBase ====================

    @Test
    fun `WorkflowDefinition with knowledge_base`() {
        val workflow = WorkflowDefinition(
            name = "kb-test",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "write", agent = "writer", input = "Write something")
            ),
            knowledgeBase = KnowledgeBaseConfig(
                enabled = true,
                embeddingModel = "text-embedding-3-large",
                sourceFiles = listOf(KnowledgeBaseSourceFile(path = "./ref.md"))
            )
        )
        val knowledgeBase = assertNotNull(workflow.knowledgeBase)
        assertTrue(knowledgeBase.enabled)
        assertEquals(1, knowledgeBase.sourceFiles.size)
    }

    @Test
    fun `WorkflowDefinition without knowledge_base defaults to null`() {
        val workflow = WorkflowDefinition(
            name = "no-kb",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "write", agent = "writer", input = "Write something")
            )
        )
        assertNull(workflow.knowledgeBase)
    }

    // ==================== WorkflowParser Validation ====================

    @Test
    fun `Parser validates workflow with valid knowledge_base`() {
        val yaml = """
            name: kb-workflow
            agents:
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            knowledge_base:
              enabled: true
              embedding_model: text-embedding-3-small
              embedding_provider: openai
              auto_index_outputs: true
              chunk_size: 1000
              chunk_overlap: 200
              source_files:
                - path: ./docs/ref.md
                  tags: reference
            workflow:
              - step: write
                agent: writer
                input: "Write something"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val knowledgeBase = assertNotNull(workflow.knowledgeBase)
        assertTrue(knowledgeBase.enabled)
        assertEquals(1, knowledgeBase.sourceFiles.size)
        assertEquals("./docs/ref.md", knowledgeBase.sourceFiles[0].path)
        assertEquals("reference", knowledgeBase.sourceFiles[0].tags)

        // Should not throw
        WorkflowParser.validateWorkflow(workflow)
    }

    @Test
    fun `Parser validates knowledge_base with disabled state`() {
        val yaml = """
            name: kb-disabled
            agents:
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            knowledge_base:
              enabled: false
            workflow:
              - step: write
                agent: writer
                input: "Write something"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val knowledgeBase = assertNotNull(workflow.knowledgeBase)
        assertFalse(knowledgeBase.enabled)

        // Disabled KB should pass validation without checking further
        WorkflowParser.validateWorkflow(workflow)
    }

    @Test
    fun `Parser rejects knowledge_base with unsupported embedding_provider`() {
        val yaml = """
            name: kb-bad-provider
            agents:
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            knowledge_base:
              enabled: true
              embedding_provider: unknown_provider
            workflow:
              - step: write
                agent: writer
                input: "Write something"
        """.trimIndent()

        // parseYaml calls validateWorkflow internally, so exception is thrown at parse time
        val ex = assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
        assertTrue(ex.message!!.contains("embedding_provider"))
        assertTrue(ex.message!!.contains("unknown_provider"))
    }

    @Test
    fun `Parser rejects knowledge_base with duplicate source_files paths`() {
        val yaml = """
            name: kb-dup-files
            agents:
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            knowledge_base:
              enabled: true
              source_files:
                - path: ./docs/ref.md
                - path: ./docs/ref.md
            workflow:
              - step: write
                agent: writer
                input: "Write something"
        """.trimIndent()

        // parseYaml calls validateWorkflow internally, so exception is thrown at parse time
        val ex = assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
        assertTrue(ex.message!!.contains("duplicate"))
    }

    @Test
    fun `Parser accepts knowledge_base with ollama provider`() {
        val yaml = """
            name: kb-ollama
            agents:
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            knowledge_base:
              enabled: true
              embedding_provider: ollama
              embedding_model: nomic-embed-text
            workflow:
              - step: write
                agent: writer
                input: "Write something"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        val knowledgeBase = assertNotNull(workflow.knowledgeBase)
        assertEquals("ollama", knowledgeBase.embeddingProvider)
        assertEquals("nomic-embed-text", knowledgeBase.embeddingModel)

        // Should not throw
        WorkflowParser.validateWorkflow(workflow)
    }

    @Test
    fun `Parser validates workflow without knowledge_base`() {
        val yaml = """
            name: no-kb
            agents:
              writer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: write
                agent: writer
                input: "Write something"
        """.trimIndent()

        val workflow = WorkflowParser.parseYaml(yaml)
        assertNull(workflow.knowledgeBase)

        // Should not throw
        WorkflowParser.validateWorkflow(workflow)
    }

    // ==================== YAML Roundtrip ====================

    @Test
    fun `toYaml roundtrip preserves knowledge_base`() {
        val original = WorkflowDefinition(
            name = "roundtrip-kb",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "write", agent = "writer", input = "Write something")
            ),
            knowledgeBase = KnowledgeBaseConfig(
                enabled = true,
                storageDir = "./my-kb",
                embeddingModel = "text-embedding-3-large",
                autoIndexOutputs = true,
                chunkSize = 2000,
                chunkOverlap = 400,
                sourceFiles = listOf(
                    KnowledgeBaseSourceFile(path = "./docs/ref.md", tags = "reference")
                )
            )
        )

        val yaml = WorkflowParser.toYaml(original)
        assertTrue(yaml.contains("knowledge_base"))
        assertTrue(yaml.contains("text-embedding-3-large"))
        assertTrue(yaml.contains("./my-kb"))
        assertTrue(yaml.contains("./docs/ref.md"))
        assertTrue(yaml.contains("reference"))

        // Parse back
        val parsed = WorkflowParser.parseYaml(yaml)
        val knowledgeBase = assertNotNull(parsed.knowledgeBase)
        assertTrue(knowledgeBase.enabled)
        assertEquals("./my-kb", knowledgeBase.storageDir)
        assertEquals("text-embedding-3-large", knowledgeBase.embeddingModel)
        assertEquals(2000, knowledgeBase.chunkSize)
        assertEquals(400, knowledgeBase.chunkOverlap)
        assertEquals(1, knowledgeBase.sourceFiles.size)
        assertEquals("reference", knowledgeBase.sourceFiles[0].tags)
    }

    @Test
    fun `toYaml roundtrip without knowledge_base`() {
        val original = WorkflowDefinition(
            name = "no-kb-roundtrip",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "write", agent = "writer", input = "Write something")
            )
        )

        val yaml = WorkflowParser.toYaml(original)
        // knowledge_base should not appear when null
        // (kaml may or may not include it depending on default handling)

        val parsed = WorkflowParser.parseYaml(yaml)
        // Either null or default-disabled — both are acceptable
        if (parsed.knowledgeBase != null) {
            // If serialized with defaults, it should still be valid
            WorkflowParser.validateWorkflow(parsed)
        }
    }

    // ==================== getWorkflowSummary ====================

    @Test
    fun `getWorkflowSummary includes knowledge_base info`() {
        val workflow = WorkflowDefinition(
            name = "summary-kb",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "write", agent = "writer", input = "Write something")
            ),
            knowledgeBase = KnowledgeBaseConfig(
                enabled = true,
                embeddingModel = "text-embedding-3-small",
                sourceFiles = listOf(
                    KnowledgeBaseSourceFile(path = "./ref.md", tags = "docs")
                )
            )
        )

        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertTrue(summary.contains("Knowledge Base: ENABLED"))
        assertTrue(summary.contains("text-embedding-3-small"))
        assertTrue(summary.contains("auto_index_outputs: true"))
        assertTrue(summary.contains("./ref.md"))
        assertTrue(summary.contains("docs"))
    }

    @Test
    fun `getWorkflowSummary excludes disabled knowledge_base`() {
        val workflow = WorkflowDefinition(
            name = "summary-no-kb",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "write", agent = "writer", input = "Write something")
            ),
            knowledgeBase = KnowledgeBaseConfig(enabled = false)
        )

        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertFalse(summary.contains("Knowledge Base"))
    }

    @Test
    fun `getWorkflowSummary without knowledge_base`() {
        val workflow = WorkflowDefinition(
            name = "summary-null-kb",
            agents = mapOf("writer" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "write", agent = "writer", input = "Write something")
            )
        )

        val summary = WorkflowParser.getWorkflowSummary(workflow)
        assertFalse(summary.contains("Knowledge Base"))
    }

    // ==================== WorkflowExecutionContext sharedKnowledgeBase ====================

    @Test
    fun `WorkflowExecutionContext sharedKnowledgeBase defaults to null`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        assertNull(context.sharedKnowledgeBase)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `KnowledgeBaseConfig with empty source_files is valid`() {
        val config = KnowledgeBaseConfig(sourceFiles = emptyList())
        assertTrue(config.sourceFiles.isEmpty())
    }

    @Test
    fun `KnowledgeBaseConfig chunk_overlap can be zero`() {
        val config = KnowledgeBaseConfig(chunkSize = 500, chunkOverlap = 0)
        assertEquals(0, config.chunkOverlap)
    }

    @Test
    fun `KnowledgeBaseConfig maxIndexedDocuments zero means unlimited`() {
        val config = KnowledgeBaseConfig(maxIndexedDocuments = 0)
        assertEquals(0, config.maxIndexedDocuments)
    }

    @Test
    fun `KnowledgeBaseConfig with all limits set`() {
        val config = KnowledgeBaseConfig(
            maxIndexedDocuments = 100,
            maxTotalChunks = 1000
        )
        assertEquals(100, config.maxIndexedDocuments)
        assertEquals(1000, config.maxTotalChunks)
    }

    @Test
    fun `KnowledgeBaseConfig disabled with defaults`() {
        val config = KnowledgeBaseConfig(enabled = false)
        assertFalse(config.enabled)
        // All other defaults should still be valid
        assertEquals("text-embedding-3-small", config.embeddingModel)
    }

    // ==================== Helper Methods ====================

    private fun minimalAgent() = AgentDefinition(
        type = "universal_agent",
        strategy = "just_work",
        tools = listOf("exit"),
        llm = LLMConfiguration(model = "gpt-4", provider = "openai")
    )
}
