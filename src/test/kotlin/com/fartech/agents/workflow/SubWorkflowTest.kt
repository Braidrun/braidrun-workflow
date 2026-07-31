package com.fartech.agents.workflow

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFalse

/**
 * 验证 sub_workflow 步骤模式与 WorkflowModuleContract 的端到端行为:
 * - 数据类的 init 校验
 * - YAML round-trip 解析
 * - WorkflowParser 的 validateModuleContract / validateSubWorkflows / detectSubWorkflowCycles
 * - WorkflowResolver 接口与 InMemoryWorkflowResolver
 * - 严格契约模式 vs 宽松模式
 *
 * 不实际运行 LLM,只验证静态校验和数据流。
 */
class SubWorkflowTest {

    // ========================================================================
    // SubWorkflowConfig 数据类校验
    // ========================================================================

    @Test
    fun `SubWorkflowConfig requires exactly one of workflowId path or name`() {
        // 多个引用 → 报错
        assertThrows<IllegalArgumentException> {
            SubWorkflowConfig(workflowId = "abc", path = "./child.yaml")
        }
        // 没有引用 → 报错
        assertThrows<IllegalArgumentException> {
            SubWorkflowConfig()
        }
        // 仅 workflowId → 通过
        SubWorkflowConfig(workflowId = "abc")
        // 仅 path → 通过
        SubWorkflowConfig(path = "./child.yaml")
        // 仅 name → 通过
        SubWorkflowConfig(name = "video_module")
    }

    @Test
    fun `SubWorkflowConfig pinned strategy requires pinnedVersion`() {
        assertThrows<IllegalArgumentException> {
            SubWorkflowConfig(
                workflowId = "abc",
                versionStrategy = SubWorkflowVersionStrategy.PINNED
            )
        }
        // 提供 pinnedVersion → 通过
        SubWorkflowConfig(
            workflowId = "abc",
            versionStrategy = SubWorkflowVersionStrategy.PINNED,
            pinnedVersion = "1.2.0"
        )
    }

    @Test
    fun `SubWorkflowConfig identityLabel formats correctly`() {
        val byId = SubWorkflowConfig(workflowId = "wf-123")
        assertEquals("id=wf-123", byId.identityLabel())

        val byPath = SubWorkflowConfig(path = "./video.yaml")
        assertEquals("path=./video.yaml", byPath.identityLabel())

        val pinnedById = SubWorkflowConfig(
            workflowId = "wf-123",
            versionStrategy = SubWorkflowVersionStrategy.PINNED,
            pinnedVersion = "1.2.0"
        )
        assertEquals("id=wf-123@1.2.0", pinnedById.identityLabel())
    }

    // ========================================================================
    // WorkflowModuleContract 数据类校验
    // ========================================================================

    @Test
    fun `WorkflowModuleContract rejects duplicate input names`() {
        assertThrows<IllegalArgumentException> {
            WorkflowModuleContract(
                inputs = listOf(
                    ModuleInputSpec(name = "topic"),
                    ModuleInputSpec(name = "topic")
                )
            )
        }
    }

    @Test
    fun `ModuleInputSpec required cannot have default value`() {
        assertThrows<IllegalArgumentException> {
            ModuleInputSpec(name = "topic", required = true, default = "AI")
        }
    }

    @Test
    fun `ModuleInputSpec enum requires allowed_values`() {
        assertThrows<IllegalArgumentException> {
            ModuleInputSpec(name = "style", type = "enum")
        }
        // 提供 allowed_values → 通过
        ModuleInputSpec(
            name = "style",
            type = "enum",
            allowedValues = listOf("cinematic", "minimal")
        )
    }

    @Test
    fun `ModuleInputSpec enum default must be in allowed values`() {
        assertThrows<IllegalArgumentException> {
            ModuleInputSpec(
                name = "style",
                type = "enum",
                default = "punk",
                allowedValues = listOf("cinematic", "minimal")
            )
        }
    }

    @Test
    fun `ModuleInputSpec accepts credential provider types`() {
        ModuleInputSpec(name = "connection", type = "credential:google_marketing")
        ModuleInputSpec(name = "connection", type = "credential:z-ai")
    }

    @Test
    fun `ModuleInputSpec rejects malformed credential provider types`() {
        listOf("credential:", "credential:provider name", "credential:/provider").forEach { type ->
            assertThrows<IllegalArgumentException> {
                ModuleInputSpec(name = "connection", type = type)
            }
        }
    }

    @Test
    fun `ModuleInputSpec mapsToVariable defaults to name`() {
        val spec = ModuleInputSpec(name = "topic")
        assertEquals("topic", spec.variableKey())

        val withMap = ModuleInputSpec(name = "topic", mapsToVariable = "refined_topic")
        assertEquals("refined_topic", withMap.variableKey())
    }

    @Test
    fun `ModuleOutputSpec rejects blank source`() {
        assertThrows<IllegalArgumentException> {
            ModuleOutputSpec(name = "video_path", source = "")
        }
    }

    // ========================================================================
    // ModuleContractComparator 兼容性检查
    // ========================================================================

    @Test
    fun `comparator detects new required input as breaking change`() {
        val old = WorkflowModuleContract(
            inputs = listOf(
                ModuleInputSpec(name = "topic", required = true)
            ),
            outputs = listOf(
                ModuleOutputSpec(name = "result", source = "{{var:result}}")
            )
        )
        val new = WorkflowModuleContract(
            inputs = listOf(
                ModuleInputSpec(name = "topic", required = true),
                ModuleInputSpec(name = "style", required = true)
            ),
            outputs = listOf(
                ModuleOutputSpec(name = "result", source = "{{var:result}}")
            )
        )
        val report = ModuleContractComparator.compare(old, new)
        assertTrue(report.hasBreakingChanges)
        assertTrue(report.breakingChanges.any { it.contains("style") })
    }

    @Test
    fun `comparator treats new optional input as non-breaking`() {
        val old = WorkflowModuleContract(
            inputs = listOf(ModuleInputSpec(name = "topic", required = true))
        )
        val new = WorkflowModuleContract(
            inputs = listOf(
                ModuleInputSpec(name = "topic", required = true),
                ModuleInputSpec(name = "style", required = false, default = "cinematic")
            )
        )
        val report = ModuleContractComparator.compare(old, new)
        assertFalse(report.hasBreakingChanges)
        assertTrue(report.nonBreakingChanges.any { it.contains("style") })
    }

    @Test
    fun `comparator detects type change as breaking`() {
        val old = WorkflowModuleContract(
            inputs = listOf(ModuleInputSpec(name = "count", type = "string"))
        )
        val new = WorkflowModuleContract(
            inputs = listOf(ModuleInputSpec(name = "count", type = "number"))
        )
        val report = ModuleContractComparator.compare(old, new)
        assertTrue(report.hasBreakingChanges)
        assertNotNull(report.suggestedContractVersion)
    }

    @Test
    fun `comparator detects deleted output as breaking`() {
        val old = WorkflowModuleContract(
            outputs = listOf(
                ModuleOutputSpec(name = "video_path", source = "{{var:p}}"),
                ModuleOutputSpec(name = "duration", source = "{{var:d}}")
            )
        )
        val new = WorkflowModuleContract(
            outputs = listOf(
                ModuleOutputSpec(name = "video_path", source = "{{var:p}}")
            )
        )
        val report = ModuleContractComparator.compare(old, new)
        assertTrue(report.hasBreakingChanges)
        assertTrue(report.breakingChanges.any { it.contains("duration") })
    }

    @Test
    fun `comparator detects tightened number range as breaking`() {
        val old = WorkflowModuleContract(
            inputs = listOf(ModuleInputSpec(name = "max_duration", type = "number", min = 10.0, max = 600.0))
        )
        val new = WorkflowModuleContract(
            inputs = listOf(ModuleInputSpec(name = "max_duration", type = "number", min = 30.0, max = 300.0))
        )

        val report = ModuleContractComparator.compare(old, new)

        assertTrue(report.hasBreakingChanges)
        assertTrue(report.breakingChanges.any { it.contains("minimum increased") })
        assertTrue(report.breakingChanges.any { it.contains("maximum decreased") })
    }

    // ========================================================================
    // WorkflowParser 校验集成
    // ========================================================================

    @Test
    fun `validateModuleContract passes on valid contract`() {
        val yaml = """
            name: video-module
            version: "1.0.0"
            tags: [video]
            module:
              display_name: "Video Generation"
              category: content
              contract_version: "1.0.0"
              inputs:
                - name: topic
                  type: string
                  required: true
                - name: style
                  type: enum
                  required: false
                  default: cinematic
                  allowed_values: [cinematic, minimal]
              outputs:
                - name: video_path
                  type: path
                  source: "{{var:final_path}}"
            agents:
              writer:
                preset: writer
            variables:
              topic: ""
              style: "cinematic"
              final_path: ""
            workflow:
              - step: generate
                agent: writer
                input: "Write about {{var:topic}} in {{var:style}} style"
        """.trimIndent()
        val workflow = WorkflowParser.parseYaml(yaml)
        assertTrue(workflow.isModule)
        assertEquals("Video Generation", workflow.module?.displayName)
        assertEquals(2, workflow.module?.inputs?.size)
        assertEquals(1, workflow.module?.outputs?.size)
    }

    @Test
    fun `validateModuleContract rejects input mapping to undeclared variable`() {
        val yaml = """
            name: bad-module
            version: "1.0.0"
            module:
              inputs:
                - name: missing_var
                  type: string
              outputs: []
            agents:
              writer:
                preset: writer
            variables:
              topic: ""
            workflow:
              - step: gen
                agent: writer
                input: "Write {{var:topic}}"
        """.trimIndent()
        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `validateModuleContract rejects output source referencing undefined step`() {
        val yaml = """
            name: bad-output
            version: "1.0.0"
            module:
              inputs: []
              outputs:
                - name: result
                  source: "{{steps.nonexistent.output}}"
            agents:
              writer:
                preset: writer
            variables:
              topic: ""
            workflow:
              - step: gen
                agent: writer
                input: "Write {{var:topic}}"
        """.trimIndent()
        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `WorkflowStep modeCount accepts sub_workflow as valid mode`() {
        // 一个有效的 sub_workflow 步骤应该通过 modeCount == 1 校验
        val step = WorkflowStep(
            step = "call_child",
            subWorkflow = SubWorkflowConfig(workflowId = "child-1")
        )
        assertTrue(step.isSubWorkflow)
        assertTrue(step.referencedAgents.isEmpty())
        assertTrue(step.displayAgentName.startsWith("sub_workflow("))
    }

    @Test
    fun `WorkflowStep rejects sub_workflow combined with another mode`() {
        assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "bad",
                agent = "writer",
                input = "Write something",
                subWorkflow = SubWorkflowConfig(workflowId = "child-1")
            )
        }
    }

    @Test
    fun `validateSubWorkflows rejects sub_workflow with iterate_over`() {
        val yaml = """
            name: parent
            version: "1.0.0"
            agents:
              writer:
                preset: writer
            workflow:
              - step: bad_call
                sub_workflow:
                  workflow_id: child-1
                iterate_over:
                  source: "a,b,c"
                  delimiter: ","
        """.trimIndent()
        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(yaml)
        }
    }

    @Test
    fun `validateSubWorkflows warns but does not fail when no resolver configured`() {
        val yaml = """
            name: parent
            version: "1.0.0"
            agents:
              writer:
                preset: writer
            workflow:
              - step: prepare
                agent: writer
                input: "Refine"
              - step: call_child
                sub_workflow:
                  workflow_id: child-not-resolved
                depends_on: [prepare]
        """.trimIndent()
        // NullWorkflowResolver 模式下,有 sub_workflow 步骤但只警告
        // (parseYaml 默认使用 NullWorkflowResolver,不应该抛错)
        val workflow = WorkflowParser.parseYaml(yaml)
        assertEquals(2, workflow.workflow.size)
        assertTrue(workflow.workflow.last().isSubWorkflow)
    }

    // ========================================================================
    // InMemoryWorkflowResolver
    // ========================================================================

    @Test
    fun `InMemoryWorkflowResolver resolves by workflow id`() {
        val childDef = WorkflowDefinition(
            name = "child",
            version = "1.0.0",
            agents = mapOf("a" to AgentDefinition(preset = "writer")),
            workflow = listOf(WorkflowStep(step = "s1", agent = "a", input = "do"))
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("child-1" to childDef))
        val resolved = resolver.resolve(WorkflowReference(workflowId = "child-1"))
        assertEquals("child", resolved.definition.name)
    }

    @Test
    fun `InMemoryWorkflowResolver throws on unknown id`() {
        val resolver = InMemoryWorkflowResolver()
        assertThrows<WorkflowValidationException> {
            resolver.resolve(WorkflowReference(workflowId = "missing"))
        }
    }

    @Test
    fun `validateSubWorkflows passes with valid resolver lookup against module contract`() {
        val childDef = WorkflowDefinition(
            name = "child-with-module",
            version = "1.0.0",
            agents = mapOf("writer" to AgentDefinition(preset = "writer")),
            variables = mapOf("topic" to "", "style" to "cinematic"),
            workflow = listOf(
                WorkflowStep(step = "gen", agent = "writer", input = "Write {{var:topic}}")
            ),
            module = WorkflowModuleContract(
                inputs = listOf(
                    ModuleInputSpec(name = "topic", type = "string", required = true),
                    ModuleInputSpec(name = "style", type = "enum", required = false, allowedValues = listOf("cinematic", "minimal"))
                ),
                outputs = listOf(
                    ModuleOutputSpec(name = "result", type = "string", source = "{{steps.gen.output}}")
                )
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("child-id" to childDef))

        val parentYaml = """
            name: parent
            version: "1.0.0"
            agents:
              writer:
                preset: writer
            variables:
              topic: "AI"
            workflow:
              - step: call_child
                sub_workflow:
                  workflow_id: child-id
                  inputs:
                    topic: "{{var:topic}}"
                    style: cinematic
                  outputs:
                    summary: result
        """.trimIndent()
        val parent = WorkflowParser.parseYaml(parentYaml, resolver)
        assertEquals(1, parent.workflow.size)
        assertTrue(parent.workflow.first().isSubWorkflow)
    }

    @Test
    fun `validateSubWorkflows rejects unknown input key in strict module mode`() {
        val childDef = WorkflowDefinition(
            name = "strict-child",
            version = "1.0.0",
            agents = mapOf("writer" to AgentDefinition(preset = "writer")),
            variables = mapOf("topic" to ""),
            workflow = listOf(WorkflowStep(step = "gen", agent = "writer", input = "Write {{var:topic}}")),
            module = WorkflowModuleContract(
                inputs = listOf(ModuleInputSpec(name = "topic", required = true)),
                outputs = listOf(ModuleOutputSpec(name = "result", source = "{{steps.gen.output}}"))
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("strict" to childDef))

        val parentYaml = """
            name: parent
            version: "1.0.0"
            agents:
              writer:
                preset: writer
            workflow:
              - step: call_child
                sub_workflow:
                  workflow_id: strict
                  inputs:
                    topic: "AI"
                    unknown_key: "oops"
                  outputs:
                    s: result
        """.trimIndent()
        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(parentYaml, resolver)
        }
    }

    @Test
    fun `validateSubWorkflows rejects missing required input in strict module mode`() {
        val childDef = WorkflowDefinition(
            name = "strict-child",
            version = "1.0.0",
            agents = mapOf("writer" to AgentDefinition(preset = "writer")),
            variables = mapOf("topic" to ""),
            workflow = listOf(WorkflowStep(step = "gen", agent = "writer", input = "Write {{var:topic}}")),
            module = WorkflowModuleContract(
                inputs = listOf(ModuleInputSpec(name = "topic", required = true)),
                outputs = listOf(ModuleOutputSpec(name = "result", source = "{{steps.gen.output}}"))
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("strict" to childDef))

        val parentYaml = """
            name: parent
            version: "1.0.0"
            agents:
              writer:
                preset: writer
            workflow:
              - step: call_child
                sub_workflow:
                  workflow_id: strict
                  inputs: {}
                  outputs:
                    s: result
        """.trimIndent()
        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(parentYaml, resolver)
        }
    }

    @Test
    fun `validateSubWorkflows rejects output mapped to undeclared contract output`() {
        val childDef = WorkflowDefinition(
            name = "strict-child",
            version = "1.0.0",
            agents = mapOf("writer" to AgentDefinition(preset = "writer")),
            variables = mapOf("topic" to ""),
            workflow = listOf(WorkflowStep(step = "gen", agent = "writer", input = "Write {{var:topic}}")),
            module = WorkflowModuleContract(
                inputs = listOf(ModuleInputSpec(name = "topic", required = true)),
                outputs = listOf(ModuleOutputSpec(name = "result", source = "{{steps.gen.output}}"))
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("strict" to childDef))

        val parentYaml = """
            name: parent
            version: "1.0.0"
            agents:
              writer:
                preset: writer
            workflow:
              - step: call_child
                sub_workflow:
                  workflow_id: strict
                  inputs:
                    topic: "AI"
                  outputs:
                    s: undeclared_output_name
        """.trimIndent()
        assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(parentYaml, resolver)
        }
    }

    @Test
    fun `validateSubWorkflows rejects literal number input outside contract range`() {
        val childDef = WorkflowDefinition(
            name = "strict-child",
            version = "1.0.0",
            agents = mapOf("writer" to AgentDefinition(preset = "writer")),
            variables = mapOf("max_duration" to "60"),
            workflow = listOf(WorkflowStep(step = "gen", agent = "writer", input = "Write")),
            module = WorkflowModuleContract(
                inputs = listOf(
                    ModuleInputSpec(name = "max_duration", type = "number", min = 10.0, max = 600.0)
                ),
                outputs = listOf(ModuleOutputSpec(name = "result", source = "{{steps.gen.output}}"))
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("strict" to childDef))

        val parentYaml = """
            name: parent
            version: "1.0.0"
            agents:
              writer:
                preset: writer
            workflow:
              - step: call_child
                sub_workflow:
                  workflow_id: strict
                  inputs:
                    max_duration: "5"
                  outputs:
                    s: result
        """.trimIndent()

        val ex = assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(parentYaml, resolver)
        }
        assertTrue(ex.message!!.contains("below min"))
    }

    @Test
    fun `detectSubWorkflowCycles catches simple A to B to A cycle`() {
        val workflowB = WorkflowDefinition(
            name = "B",
            version = "1.0.0",
            agents = mapOf("w" to AgentDefinition(preset = "writer")),
            workflow = listOf(
                WorkflowStep(
                    step = "callA",
                    subWorkflow = SubWorkflowConfig(workflowId = "A")
                )
            )
        )
        val workflowA = WorkflowDefinition(
            name = "A",
            version = "1.0.0",
            agents = mapOf("w" to AgentDefinition(preset = "writer")),
            workflow = listOf(
                WorkflowStep(
                    step = "callB",
                    subWorkflow = SubWorkflowConfig(workflowId = "B")
                )
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("A" to workflowA, "B" to workflowB))
        val ex = assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflowA, resolver)
        }
        assertTrue(ex.message!!.contains("Cyclic"))
    }

    @Test
    fun `detectSubWorkflowCycles catches self-reference`() {
        val workflowSelf = WorkflowDefinition(
            name = "Self",
            version = "1.0.0",
            agents = mapOf("w" to AgentDefinition(preset = "writer")),
            workflow = listOf(
                WorkflowStep(
                    step = "callSelf",
                    subWorkflow = SubWorkflowConfig(workflowId = "Self")
                )
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("Self" to workflowSelf))
        val ex = assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(workflowSelf, resolver)
        }
        assertTrue(ex.message!!.contains("Cyclic") || ex.message!!.contains("recursion"))
    }

    @Test
    fun `WorkflowAnalyzer detects sub_workflow input variable references`() {
        val workflow = WorkflowDefinition(
            name = "p",
            version = "1.0.0",
            agents = mapOf("w" to AgentDefinition(preset = "writer")),
            variables = mapOf("topic" to "AI"),
            workflow = listOf(
                WorkflowStep(
                    step = "child_call",
                    subWorkflow = SubWorkflowConfig(
                        workflowId = "child",
                        inputs = mapOf("topic" to "{{var:topic}}")
                    )
                )
            )
        )
        val refs = WorkflowAnalyzer.collectReferencedVariables(workflow)
        assertTrue("topic" in refs)
    }
}
