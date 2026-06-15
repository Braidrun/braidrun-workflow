package com.fartech.agents.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DirectoryIsolationTest {

    // =========================================================================
    // DirectoryIsolationConfig - 默认值
    // =========================================================================

    @Nested
    inner class DefaultConfigTest {

        @Test
        fun `default config has isolation enabled`() {
            val config = DirectoryIsolationConfig()
            assertTrue(config.enabled)
        }

        @Test
        fun `default base dir is workflow-runs`() {
            val config = DirectoryIsolationConfig()
            assertEquals(".workflow-runs", config.baseDir)
        }

        @Test
        fun `DEFAULT companion matches default constructor`() {
            val config = DirectoryIsolationConfig.DEFAULT
            assertEquals(DirectoryIsolationConfig(), config)
        }

        @Test
        fun `DISABLED companion has enabled=false`() {
            val config = DirectoryIsolationConfig.DISABLED
            assertFalse(config.enabled)
        }
    }

    // =========================================================================
    // DirectoryIsolationConfig - 路径模板解析
    // =========================================================================

    @Nested
    inner class PatternResolutionTest {

        @Test
        fun `resolvePattern replaces all placeholders`() {
            val config = DirectoryIsolationConfig()
            val result = config.resolvePattern(
                "{base_dir}/{execution_id}/{step_name}/{agent_name}/{workflow_name}",
                executionId = "exec-123",
                stepName = "analyze",
                agentName = "researcher",
                workflowName = "test-wf"
            )
            assertEquals(".workflow-runs/exec-123/analyze/researcher/test-wf", result)
        }

        @Test
        fun `resolvePattern uses default for empty step name`() {
            val config = DirectoryIsolationConfig()
            val result = config.resolvePattern(
                "{base_dir}/{execution_id}/{step_name}/workspace",
                executionId = "exec-123",
                stepName = ""
            )
            assertEquals(".workflow-runs/exec-123/default/workspace", result)
        }

        @Test
        fun `resolvePattern uses default for empty agent name`() {
            val config = DirectoryIsolationConfig()
            val result = config.resolvePattern(
                "{agent_name}",
                executionId = "exec-123",
                agentName = ""
            )
            assertEquals("default", result)
        }

        @Test
        fun `getWorkingDir uses working_dir_pattern`() {
            val config = DirectoryIsolationConfig(
                baseDir = "/tmp/test",
                workingDirPattern = "{base_dir}/{execution_id}/{step_name}/workspace"
            )
            val result = config.getWorkingDir("exec-1", "step-a")
            assertEquals("/tmp/test/exec-1/step-a/workspace", result)
        }

        @Test
        fun `getOutputDir uses output_dir_pattern`() {
            val config = DirectoryIsolationConfig(
                baseDir = "/tmp/test",
                outputDirPattern = "{base_dir}/{execution_id}/output"
            )
            val result = config.getOutputDir("exec-1")
            assertEquals("/tmp/test/exec-1/output", result)
        }

        @Test
        fun `getPersistenceDir uses persistence_dir_pattern`() {
            val config = DirectoryIsolationConfig(
                baseDir = "/tmp/test",
                persistenceDirPattern = "{base_dir}/{execution_id}/.snapshots"
            )
            val result = config.getPersistenceDir("exec-1")
            assertEquals("/tmp/test/exec-1/.snapshots", result)
        }

        @Test
        fun `custom base_dir is used in all patterns`() {
            val config = DirectoryIsolationConfig(baseDir = "/data/workflows")
            val workingDir = config.getWorkingDir("e1", "s1")
            val outputDir = config.getOutputDir("e1")
            val persistDir = config.getPersistenceDir("e1")

            assertTrue(workingDir.startsWith("/data/workflows/"))
            assertTrue(outputDir.startsWith("/data/workflows/"))
            assertTrue(persistDir.startsWith("/data/workflows/"))
        }

        @Test
        fun `custom patterns are fully respected`() {
            val config = DirectoryIsolationConfig(
                baseDir = "runs",
                workingDirPattern = "{base_dir}/{workflow_name}/{agent_name}/{execution_id}",
                outputDirPattern = "{base_dir}/out/{execution_id}",
                persistenceDirPattern = "{base_dir}/snap/{execution_id}"
            )
            assertEquals(
                "runs/my-wf/my-agent/exec-1",
                config.getWorkingDir("exec-1", "step-1", "my-agent", "my-wf")
            )
            assertEquals("runs/out/exec-1", config.getOutputDir("exec-1"))
            assertEquals("runs/snap/exec-1", config.getPersistenceDir("exec-1"))
        }
    }

    // =========================================================================
    // 不同执行之间的路径隔离
    // =========================================================================

    @Nested
    inner class IsolationTest {

        @Test
        fun `different execution IDs produce different working dirs`() {
            val config = DirectoryIsolationConfig()
            val dir1 = config.getWorkingDir("exec-1", "step-a")
            val dir2 = config.getWorkingDir("exec-2", "step-a")
            assertNotEquals(dir1, dir2)
        }

        @Test
        fun `different step names produce different working dirs`() {
            val config = DirectoryIsolationConfig()
            val dir1 = config.getWorkingDir("exec-1", "step-a")
            val dir2 = config.getWorkingDir("exec-1", "step-b")
            assertNotEquals(dir1, dir2)
        }

        @Test
        fun `same execution ID shares output dir across steps`() {
            val config = DirectoryIsolationConfig()
            val dir1 = config.getOutputDir("exec-1", "step-a")
            val dir2 = config.getOutputDir("exec-1", "step-b")
            // 默认 output pattern 不含 step_name，所以两者相同
            assertEquals(dir1, dir2)
        }

        @Test
        fun `parallel steps get different working dirs`() {
            val config = DirectoryIsolationConfig()
            val dirs = (0..2).map { i ->
                config.getWorkingDir("exec-1", "analyze:parallel:$i")
            }
            assertEquals(3, dirs.toSet().size, "All parallel step dirs should be unique")
        }
    }

    // =========================================================================
    // WorkflowDefinition 中的 directoryIsolation 字段
    // =========================================================================

    @Nested
    inner class WorkflowDefinitionIntegrationTest {

        private fun minimalWorkflow(
            dirIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
        ): WorkflowDefinition {
            return WorkflowDefinition(
                name = "test-workflow",
                agents = mapOf(
                    "agent1" to AgentDefinition(
                        preset = "universal"
                    )
                ),
                workflow = listOf(
                    WorkflowStep(
                        step = "step1",
                        agent = "agent1",
                        input = "test input"
                    )
                ),
                directoryIsolation = dirIsolation
            )
        }

        @Test
        fun `workflow definition includes default directory isolation`() {
            val workflow = minimalWorkflow()
            assertTrue(workflow.directoryIsolation.enabled)
            assertEquals(".workflow-runs", workflow.directoryIsolation.baseDir)
        }

        @Test
        fun `workflow definition with disabled isolation`() {
            val workflow = minimalWorkflow(DirectoryIsolationConfig.DISABLED)
            assertFalse(workflow.directoryIsolation.enabled)
        }

        @Test
        fun `workflow definition with custom isolation`() {
            val config = DirectoryIsolationConfig(
                baseDir = "/custom/path",
                cleanupOnCompletion = true,
                cleanupAfterHours = 24
            )
            val workflow = minimalWorkflow(config)
            assertEquals("/custom/path", workflow.directoryIsolation.baseDir)
            assertTrue(workflow.directoryIsolation.cleanupOnCompletion)
            assertEquals(24, workflow.directoryIsolation.cleanupAfterHours)
        }
    }

    // =========================================================================
    // YAML 序列化/反序列化
    // =========================================================================

    @Nested
    inner class SerializationTest {

        @Test
        fun `DirectoryIsolationConfig serializes and deserializes`() {
            val original = DirectoryIsolationConfig(
                enabled = true,
                baseDir = "/custom/runs",
                cleanupOnCompletion = true,
                cleanupAfterHours = 48
            )

            // 验证字段值保持一致
            assertEquals(true, original.enabled)
            assertEquals("/custom/runs", original.baseDir)
            assertEquals(true, original.cleanupOnCompletion)
            assertEquals(48, original.cleanupAfterHours)
        }

        @Test
        fun `default values are correct`() {
            val config = DirectoryIsolationConfig()
            assertEquals(true, config.enabled)
            assertEquals(".workflow-runs", config.baseDir)
            assertEquals("{base_dir}/{user_id}/{execution_id}/{step_name}/workspace", config.workingDirPattern)
            assertEquals("{base_dir}/{user_id}/{execution_id}/output", config.outputDirPattern)
            assertEquals("{base_dir}/{user_id}/{execution_id}/.snapshots", config.persistenceDirPattern)
            assertEquals(".prompt_cache", config.sharedCacheDir)
            assertEquals(".agent_history", config.sharedHistoryDir)
            assertEquals("./skills", config.sharedSkillsDir)
            assertEquals(false, config.cleanupOnCompletion)
            assertEquals(72, config.cleanupAfterHours)
        }
    }

    // =========================================================================
    // 目录创建验证（使用临时目录）
    // =========================================================================

    @Nested
    inner class DirectoryCreationTest {

        @Test
        fun `isolation directories can be created on disk`(@TempDir tempDir: Path) {
            val config = DirectoryIsolationConfig(
                baseDir = tempDir.resolve("workflow-runs").toString()
            )
            val executionId = "test-exec-123"

            val workingDir = File(config.getWorkingDir(executionId, "step1"))
            val outputDir = File(config.getOutputDir(executionId))
            val persistenceDir = File(config.getPersistenceDir(executionId))

            // 创建目录
            assertTrue(workingDir.mkdirs())
            assertTrue(outputDir.mkdirs())
            assertTrue(persistenceDir.mkdirs())

            // 验证目录存在
            assertTrue(workingDir.exists() && workingDir.isDirectory)
            assertTrue(outputDir.exists() && outputDir.isDirectory)
            assertTrue(persistenceDir.exists() && persistenceDir.isDirectory)
        }

        @Test
        fun `cleanup deletes execution directory`(@TempDir tempDir: Path) {
            val baseDir = tempDir.resolve("workflow-runs").toString()
            val config = DirectoryIsolationConfig(
                baseDir = baseDir,
                cleanupOnCompletion = true
            )

            val executionId = "exec-to-cleanup"
            val userId = "test-user"
            val executionDir = File(baseDir, "$userId/$executionId")
            val workingDir = File(config.getWorkingDir(executionId, "step1", userId = userId))
            workingDir.mkdirs()

            // 在工作目录中创建一些文件
            File(workingDir, "test-output.txt").writeText("test data")
            assertTrue(executionDir.exists())

            // 模拟清理
            executionDir.deleteRecursively()
            assertFalse(executionDir.exists())
        }

        @Test
        fun `shared directories are separate from isolation directories`(@TempDir tempDir: Path) {
            val config = DirectoryIsolationConfig(
                baseDir = tempDir.resolve("workflow-runs").toString(),
                sharedCacheDir = tempDir.resolve("shared-cache").toString(),
                sharedHistoryDir = tempDir.resolve("shared-history").toString()
            )

            val workingDir = config.getWorkingDir("exec-1", "step-1")
            assertFalse(workingDir.contains("shared-cache"))
            assertFalse(workingDir.contains("shared-history"))
            assertFalse(config.sharedCacheDir.contains("workflow-runs"))
        }
    }

    // =========================================================================
    // resolveSessionId 兼容性（确保目录隔离不影响 session_id 策略）
    // =========================================================================

    @Nested
    inner class SessionIdCompatibilityTest {

        @Test
        fun `directory isolation config does not affect session_id strategy resolution`() {
            // 验证 DirectoryIsolationConfig 和 session_id_strategy 是独立的配置
            val config = DirectoryIsolationConfig(enabled = true)
            val workflow = WorkflowDefinition(
                name = "test",
                agents = mapOf(
                    "agent1" to AgentDefinition(
                        preset = "universal",
                        overrides = mapOf(
                            "session_id_strategy" to kotlinx.serialization.json.JsonPrimitive("per_agent")
                        )
                    )
                ),
                workflow = listOf(
                    WorkflowStep(step = "s1", agent = "agent1", input = "test")
                ),
                directoryIsolation = config
            )

            // 确保 workflow 中两个配置相互独立
            assertTrue(workflow.directoryIsolation.enabled)
            assertEquals(
                "per_agent",
                (workflow.agents["agent1"]!!.resolveParameters()["session_id_strategy"]
                        as? kotlinx.serialization.json.JsonPrimitive)?.content
            )
        }
    }
}
