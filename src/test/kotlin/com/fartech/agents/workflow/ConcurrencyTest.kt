package com.fartech.agents.workflow

import com.charleskorn.kaml.Yaml
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ConcurrencyTest {

    // =========================================================================
    // ConcurrencyConfig - 默认值和验证
    // =========================================================================

    @Nested
    inner class ConcurrencyConfigTest {

        @Test
        fun `default config has concurrency disabled`() {
            val config = ConcurrencyConfig()
            assertFalse(config.enabled)
        }

        @Test
        fun `default maxConcurrency is 0 (unlimited)`() {
            val config = ConcurrencyConfig()
            assertEquals(0, config.maxConcurrency)
        }

        @Test
        fun `can create enabled config with custom maxConcurrency`() {
            val config = ConcurrencyConfig(enabled = true, maxConcurrency = 4)
            assertTrue(config.enabled)
            assertEquals(4, config.maxConcurrency)
        }

        @Test
        fun `negative maxConcurrency throws exception`() {
            assertThrows(IllegalArgumentException::class.java) {
                ConcurrencyConfig(maxConcurrency = -1)
            }
        }
    }

    // =========================================================================
    // ConcurrencyConfig - YAML 序列化 / 反序列化
    // =========================================================================

    @Nested
    inner class ConcurrencyYamlSerializationTest {

        @Test
        fun `WorkflowDefinition with concurrency can be deserialized`() {
            val yaml = """
                name: test-concurrent
                version: "1.0"
                agents:
                  agent_a:
                    preset: universal
                workflow:
                  - step: step_a
                    agent: agent_a
                    input: "do something"
                concurrency:
                  enabled: true
                  max_concurrency: 3
            """.trimIndent()

            val def = Yaml.default.decodeFromString(WorkflowDefinition.serializer(), yaml)
            assertTrue(def.concurrency.enabled)
            assertEquals(3, def.concurrency.maxConcurrency)
        }

        @Test
        fun `WorkflowDefinition without concurrency gets default`() {
            val yaml = """
                name: test-no-concurrency
                version: "1.0"
                agents:
                  agent_a:
                    preset: universal
                workflow:
                  - step: step_a
                    agent: agent_a
                    input: "do something"
            """.trimIndent()

            val def = Yaml.default.decodeFromString(WorkflowDefinition.serializer(), yaml)
            assertFalse(def.concurrency.enabled)
            assertEquals(0, def.concurrency.maxConcurrency)
        }

        @Test
        fun `WorkflowDefinition concurrency enabled only`() {
            val yaml = """
                name: test-concurrent-defaults
                version: "1.0"
                agents:
                  agent_a:
                    preset: universal
                workflow:
                  - step: step_a
                    agent: agent_a
                    input: "do something"
                concurrency:
                  enabled: true
            """.trimIndent()

            val def = Yaml.default.decodeFromString(WorkflowDefinition.serializer(), yaml)
            assertTrue(def.concurrency.enabled)
            assertEquals(0, def.concurrency.maxConcurrency) // unlimited by default
        }
    }

    // =========================================================================
    // getTopologicalLayers - 分层拓扑排序
    // =========================================================================

    @Nested
    inner class TopologicalLayersTest {

        private val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )

        private fun makeWorkflow(steps: List<WorkflowStep>): WorkflowDefinition {
            // 从步骤中收集所有引用的 agent 名称
            val agentNames = steps.mapNotNull { it.agent }.toSet()
            val agents = agentNames.associateWith {
                AgentDefinition(preset = "universal")
            }
            return WorkflowDefinition(
                name = "test",
                agents = agents,
                workflow = steps
            )
        }

        @Test
        fun `single step produces one layer`() {
            val workflow = makeWorkflow(
                listOf(
                    WorkflowStep(step = "step_a", agent = "a", input = "go")
                )
            )

            val layers = executor.getTopologicalLayers(workflow)
            assertEquals(1, layers.size)
            assertEquals(listOf("step_a"), layers[0].map { it.step })
        }

        @Test
        fun `two independent steps in same layer`() {
            val workflow = makeWorkflow(
                listOf(
                    WorkflowStep(step = "step_a", agent = "a", input = "go"),
                    WorkflowStep(step = "step_b", agent = "a", input = "go")
                )
            )

            val layers = executor.getTopologicalLayers(workflow)
            assertEquals(1, layers.size)
            assertEquals(2, layers[0].size)
            assertTrue(layers[0].map { it.step }.containsAll(listOf("step_a", "step_b")))
        }

        @Test
        fun `linear chain produces one step per layer`() {
            val workflow = makeWorkflow(
                listOf(
                    WorkflowStep(step = "step_a", agent = "a", input = "go"),
                    WorkflowStep(step = "step_b", agent = "a", input = "go", dependsOn = listOf("step_a")),
                    WorkflowStep(step = "step_c", agent = "a", input = "go", dependsOn = listOf("step_b"))
                )
            )

            val layers = executor.getTopologicalLayers(workflow)
            assertEquals(3, layers.size)
            assertEquals("step_a", layers[0][0].step)
            assertEquals("step_b", layers[1][0].step)
            assertEquals("step_c", layers[2][0].step)
        }

        @Test
        fun `diamond DAG produces correct layers`() {
            // A → B, A → C, B+C → D
            val workflow = makeWorkflow(
                listOf(
                    WorkflowStep(step = "A", agent = "a", input = "go"),
                    WorkflowStep(step = "B", agent = "a", input = "go", dependsOn = listOf("A")),
                    WorkflowStep(step = "C", agent = "a", input = "go", dependsOn = listOf("A")),
                    WorkflowStep(step = "D", agent = "a", input = "go", dependsOn = listOf("B", "C"))
                )
            )

            val layers = executor.getTopologicalLayers(workflow)
            assertEquals(3, layers.size)
            // Layer 0: A
            assertEquals(listOf("A"), layers[0].map { it.step })
            // Layer 1: B and C (can run concurrently)
            assertEquals(2, layers[1].size)
            assertTrue(layers[1].map { it.step }.containsAll(listOf("B", "C")))
            // Layer 2: D
            assertEquals(listOf("D"), layers[2].map { it.step })
        }

        @Test
        fun `fan-out fan-in pattern`() {
            // A → B1, B2, B3 → C
            val workflow = makeWorkflow(
                listOf(
                    WorkflowStep(step = "A", agent = "a", input = "go"),
                    WorkflowStep(step = "B1", agent = "a", input = "go", dependsOn = listOf("A")),
                    WorkflowStep(step = "B2", agent = "a", input = "go", dependsOn = listOf("A")),
                    WorkflowStep(step = "B3", agent = "a", input = "go", dependsOn = listOf("A")),
                    WorkflowStep(step = "C", agent = "a", input = "go", dependsOn = listOf("B1", "B2", "B3"))
                )
            )

            val layers = executor.getTopologicalLayers(workflow)
            assertEquals(3, layers.size)
            assertEquals(1, layers[0].size) // A
            assertEquals(3, layers[1].size) // B1, B2, B3
            assertEquals(1, layers[2].size) // C
        }

        @Test
        fun `priority ordering within layers`() {
            val workflow = makeWorkflow(
                listOf(
                    WorkflowStep(step = "low", agent = "a", input = "go", priority = 1),
                    WorkflowStep(step = "high", agent = "a", input = "go", priority = 10),
                    WorkflowStep(step = "mid", agent = "a", input = "go", priority = 5)
                )
            )

            val layers = executor.getTopologicalLayers(workflow)
            assertEquals(1, layers.size)
            // Should be ordered by priority descending
            assertEquals(listOf("high", "mid", "low"), layers[0].map { it.step })
        }

        @Test
        fun `circular dependency throws exception`() {
            assertThrows(WorkflowExecutionException::class.java) {
                val workflow = makeWorkflow(
                    listOf(
                        WorkflowStep(step = "A", agent = "a", input = "go", dependsOn = listOf("B")),
                        WorkflowStep(step = "B", agent = "a", input = "go", dependsOn = listOf("A"))
                    )
                )
                executor.getTopologicalLayers(workflow)
            }
        }

        @Test
        fun `complex multi-layer DAG`() {
            // Layer 0: A, B (independent)
            // Layer 1: C (depends on A), D (depends on B)
            // Layer 2: E (depends on C and D)
            val workflow = makeWorkflow(
                listOf(
                    WorkflowStep(step = "A", agent = "a", input = "go"),
                    WorkflowStep(step = "B", agent = "a", input = "go"),
                    WorkflowStep(step = "C", agent = "a", input = "go", dependsOn = listOf("A")),
                    WorkflowStep(step = "D", agent = "a", input = "go", dependsOn = listOf("B")),
                    WorkflowStep(step = "E", agent = "a", input = "go", dependsOn = listOf("C", "D"))
                )
            )

            val layers = executor.getTopologicalLayers(workflow)
            assertEquals(3, layers.size)
            assertEquals(2, layers[0].size) // A, B
            assertEquals(2, layers[1].size) // C, D
            assertEquals(1, layers[2].size) // E
        }
    }

    // =========================================================================
    // WorkflowDefinition with ConcurrencyConfig - 集成验证
    // =========================================================================

    @Nested
    inner class WorkflowDefinitionConcurrencyIntegrationTest {

        @Test
        fun `full workflow YAML with concurrency config`() {
            val yaml = """
                name: concurrent-workflow
                version: "1.0"
                description: "Test concurrent execution"
                agents:
                  researcher:
                    preset: researcher
                  writer:
                    preset: writer
                  analyst:
                    preset: data_analyst
                workflow:
                  - step: research
                    agent: researcher
                    input: "Research topic X"
                  - step: analyze
                    agent: analyst
                    input: "Analyze data"
                  - step: write_report
                    agent: writer
                    input: "Write based on {{research}} and {{analyze}}"
                    depends_on:
                      - research
                      - analyze
                concurrency:
                  enabled: true
                  max_concurrency: 2
            """.trimIndent()

            val def = Yaml.default.decodeFromString(WorkflowDefinition.serializer(), yaml)

            assertEquals("concurrent-workflow", def.name)
            assertTrue(def.concurrency.enabled)
            assertEquals(2, def.concurrency.maxConcurrency)
            assertEquals(3, def.workflow.size)

            // Verify the DAG structure allows concurrency
            val executor = WorkflowExecutor(
                httpAccess = HttpAccess(),
                baseParameters = emptyList(),
                enableMonitoring = false
            )
            val layers = executor.getTopologicalLayers(def)
            assertEquals(2, layers.size)
            // Layer 0: research and analyze can run concurrently
            assertEquals(2, layers[0].size)
            // Layer 1: write_report depends on both
            assertEquals(1, layers[1].size)
            assertEquals("write_report", layers[1][0].step)
        }

        @Test
        fun `concurrency disabled preserves sequential behavior`() {
            val yaml = """
                name: sequential-workflow
                version: "1.0"
                agents:
                  agent_a:
                    preset: universal
                workflow:
                  - step: step1
                    agent: agent_a
                    input: "task 1"
                  - step: step2
                    agent: agent_a
                    input: "task 2"
                concurrency:
                  enabled: false
            """.trimIndent()

            val def = Yaml.default.decodeFromString(WorkflowDefinition.serializer(), yaml)
            assertFalse(def.concurrency.enabled)
        }

        @Test
        fun `concurrent executor advances downstream branch before slower sibling finishes`() = runBlocking {
            val eventDir = Files.createTempDirectory("wf-concurrency-events")
            try {
                val eventDirPath = eventDir.toAbsolutePath().toString()
                val yaml = """
                    name: streaming-concurrent-workflow
                    version: "1.0"
                    agents: {}
                    workflow:
                      - step: fast
                        code:
                          language: python
                          timeout: 5
                          script: |
                            import time
                            from pathlib import Path
                            event_dir = Path(r"$eventDirPath")
                            (event_dir / "fast_start").write_text(str(time.monotonic()), encoding="utf-8")
                            time.sleep(0.1)
                            (event_dir / "fast_end").write_text(str(time.monotonic()), encoding="utf-8")
                            print("fast_status=ok")
                      - step: slow
                        code:
                          language: python
                          timeout: 5
                          script: |
                            import time
                            from pathlib import Path
                            event_dir = Path(r"$eventDirPath")
                            (event_dir / "slow_start").write_text(str(time.monotonic()), encoding="utf-8")
                            time.sleep(1.0)
                            (event_dir / "slow_end").write_text(str(time.monotonic()), encoding="utf-8")
                            print("slow_status=ok")
                      - step: fast_child
                        code:
                          language: python
                          timeout: 5
                          script: |
                            import time
                            from pathlib import Path
                            event_dir = Path(r"$eventDirPath")
                            (event_dir / "fast_child_start").write_text(str(time.monotonic()), encoding="utf-8")
                            print("fast_child_status=ok")
                        depends_on:
                          - fast
                    concurrency:
                      enabled: true
                      max_concurrency: 2
                """.trimIndent()

                val workflow = WorkflowParser.parseYaml(yaml)
                val executor = WorkflowExecutor(
                    httpAccess = HttpAccess(),
                    baseParameters = emptyList(),
                    enableMonitoring = false
                )

                val result = executor.execute(workflow)

                assertTrue(result.success)
                val fastChildStart = Files.readString(eventDir.resolve("fast_child_start")).trim().toDouble()
                val slowEnd = Files.readString(eventDir.resolve("slow_end")).trim().toDouble()
                assertTrue(
                    fastChildStart < slowEnd,
                    "fast_child should start as soon as fast completes, without waiting for slow sibling"
                )
            } finally {
                eventDir.toFile().deleteRecursively()
            }
        }
    }
}
