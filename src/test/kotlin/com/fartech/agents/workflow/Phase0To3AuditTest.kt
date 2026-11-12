package com.fartech.agents.workflow

import com.fartech.agents.commons.FakeAnthropicApi
import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase0To3AuditTest {

    @BeforeEach
    fun setup() {
        WorkflowMonitor.clear()
        AgentPresetRegistry.resetToBuiltins()
    }

    // =========================================================================
    // P0-1: Built-in Python get_var() Runtime Fallback
    // =========================================================================

    @Test
    fun `P0-1 python code step resolves get_var without mutating workflow codePreamble`() = runBlocking {
        val workflow = WorkflowDefinition(
            name = "python-get-var-default",
            agents = emptyMap(),
            variables = mapOf(
                "foo_bar" to "hello_world",
                "hyphen-key" to "dash_value"
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "run_py",
                    code = CodeStepConfig(
                        language = "python",
                        script = """
                            print(f"{get_var('foo_bar')}|{get_var('hyphen-key')}|{get_var('missing', 'def_val')}")
                        """.trimIndent()
                    )
                )
            )
        )

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false
        )
        val result = executor.execute(workflow)

        assertTrue(result.success, "Expected Python step to succeed: ${result.error}")
        assertEquals("hello_world|dash_value|def_val", result.stepResults["run_py"]?.output?.trim())
        assertTrue(workflow.codePreamble.isEmpty(), "WorkflowDefinition.codePreamble must NEVER be mutated")
    }

    @Test
    fun `P0-1 python get_var fallback does not override user def get_var in preamble or script`() = runBlocking {
        val withPreamble = WorkflowDefinition(
            name = "python-custom-preamble-get-var",
            agents = emptyMap(),
            variables = mapOf("foo_bar" to "from_env"),
            codePreamble = mapOf(
                "python" to CodePreambleConfig(
                    inline = """
                        def get_var(key, default=""):
                            return f"preamble:{key}"
                    """.trimIndent()
                )
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "check_preamble",
                    code = CodeStepConfig(
                        language = "python",
                        script = "print(get_var('foo_bar'))"
                    )
                )
            )
        )

        val withScriptDef = WorkflowDefinition(
            name = "python-custom-script-get-var",
            agents = emptyMap(),
            variables = mapOf("foo_bar" to "from_env"),
            workflow = listOf(
                WorkflowStep(
                    step = "check_script",
                    code = CodeStepConfig(
                        language = "python",
                        script = """
                            def get_var(key, default=""):
                                return f"script:{key}"
                            print(get_var('foo_bar'))
                        """.trimIndent()
                    )
                )
            )
        )

        val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)

        val resPreamble = executor.execute(withPreamble)
        assertTrue(resPreamble.success, resPreamble.error)
        assertEquals("preamble:foo_bar", resPreamble.stepResults["check_preamble"]?.output?.trim())

        val resScript = executor.execute(withScriptDef)
        assertTrue(resScript.success, resScript.error)
        assertEquals("script:foo_bar", resScript.stepResults["check_script"]?.output?.trim())

        val buildMethod = WorkflowExecutor::class.java.getDeclaredMethod(
            "buildFinalCodeScript",
            WorkflowStep::class.java,
            WorkflowDefinition::class.java,
            CodeStepConfig::class.java
        ).apply { isAccessible = true }

        val builtPreamble = buildMethod.invoke(
            executor,
            withPreamble.workflow.single(),
            withPreamble,
            withPreamble.workflow.single().code
        ) as String
        assertFalse("__br_os" in builtPreamble, "Must not inject fallback when preamble defines get_var")

        val builtScript = buildMethod.invoke(
            executor,
            withScriptDef.workflow.single(),
            withScriptDef,
            withScriptDef.workflow.single().code
        ) as String
        assertFalse("__br_os" in builtScript, "Must not inject fallback when script defines get_var")
    }

    // =========================================================================
    // P0-5: ci-cd-pipeline.yaml Bash Variable Expansion Fix
    // =========================================================================

    @Test
    fun `P0-5 ci-cd-pipeline template uses shell env vars instead of template interpolation in code script`() {
        val workflow = WorkflowParser.parseFile("workflows/templates/ci-cd-pipeline.yaml")
        val scripts = workflow.workflow.mapNotNull { it.code?.script }
        assertTrue(scripts.isNotEmpty())
        scripts.forEach { script ->
            assertFalse("{{var:build_command}}" in script, "code.script must not contain {{var:build_command}}")
            assertFalse("{{var:test_command}}" in script, "code.script must not contain {{var:test_command}}")
        }
        assertTrue(scripts.any { "\"\$WF_VAR_BUILD_COMMAND\"" in it })
        assertTrue(scripts.any { "\"\$WF_VAR_TEST_COMMAND\"" in it })
    }

    // =========================================================================
    // P0-8: Classifier Substring Collision Fix
    // =========================================================================

    @Test
    fun `P0-8 classifier resolves unstructured instead of colliding with shorter structured category`() = runBlocking {
        val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)
        val categories = listOf("structured", "mixed", "unstructured")

        assertEquals("unstructured", executor.matchClassifierCategory("Category: unstructured.", categories))
        assertEquals("unstructured", executor.matchClassifierCategory("unstructured.", categories))
        assertEquals("structured", executor.matchClassifierCategory("Category: structured.", categories))

        FakeAnthropicApi { _, _ -> listOf(FakeAnthropicApi.text("Category: unstructured.")) }.use { api ->
            val yaml = """
                name: classifier-collision-test
                version: "1.0.0"
                agents:
                  router_llm:
                    preset: lightweight
                    overrides:
                      history_enabled: false
                      llm_provider_keys: { anthropic: sk-ant-test }
                      llm_config:
                        models:
                          - { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
                        fallback: { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
                workflow:
                  - step: route
                    classifier:
                      agent: router_llm
                      input: "Raw free-form notes"
                      output_variable: detected_kind
                      categories:
                        - name: structured
                          description: "Tabular"
                        - name: mixed
                          description: "Hybrid"
                        - name: unstructured
                          description: "Free text"
                  - step: on_structured
                    depends_on: [route]
                    condition: "detected_kind == 'structured'"
                    code:
                      language: bash
                      script: "echo picked_structured"
                  - step: on_mixed
                    depends_on: [route]
                    condition: "detected_kind == 'mixed'"
                    code:
                      language: bash
                      script: "echo picked_mixed"
                  - step: on_unstructured
                    depends_on: [route]
                    condition: "detected_kind == 'unstructured'"
                    code:
                      language: bash
                      script: "echo picked_unstructured"
            """.trimIndent()

            val result = executor.execute(WorkflowParser.parseYaml(yaml))
            assertTrue(result.success, "Workflow failed: ${result.error}")
            assertEquals("unstructured", result.variables["detected_kind"])
            assertEquals("picked_unstructured", result.stepResults["on_unstructured"]?.output?.trim())
            assertTrue("on_structured" in result.skippedSteps, "on_structured should be skipped, skipped=${result.skippedSteps}")
            assertTrue("on_mixed" in result.skippedSteps, "on_mixed should be skipped, skipped=${result.skippedSteps}")
        }
    }

    // =========================================================================
    // Phase 1: Agent Presets & Opt-In skill_tools for Workflow Step Agents
    // =========================================================================

    @Test
    fun `Phase 1 builtin presets satisfy strategy, temperature, leaf sub_agent, lightweight, and distinct fallback invariants`() {
        val allPresets = AgentPresetRegistry.getAll().filter { it.builtin }
        assertEquals(19, allPresets.size)

        // 1. Strategy switched from just_work_parallel_reasoning to just_work_parallel
        for (id in listOf("coder", "data_analyst", "devops", "universal_reasoning")) {
            val preset = assertNotNull(AgentPresetRegistry.get(id))
            assertEquals("just_work_parallel", preset.parameters["strategy"]?.jsonPrimitive?.content, "Strategy mismatch on $id")
        }

        // 2. writer temperature lowered to 0.7
        val writer = assertNotNull(AgentPresetRegistry.get("writer"))
        val writerTemp = writer.parameters["llm_config"]?.jsonObject?.get("temperature")?.jsonPrimitive?.doubleOrNull
        assertEquals(0.7, writerTemp)

        // 3. Leaf presets omit sub_agent; orchestrator/coding presets retain sub_agent
        val leafWithoutSubAgent = listOf(
            "writer",
            "office_document",
            "word_document",
            "excel_workbook",
            "powerpoint_presentation",
            "multimedia_creator",
            "communication",
            "data_analyst"
        )
        for (id in leafWithoutSubAgent) {
            val tools = AgentPresetRegistry.get(id)!!.parameters["tool_set"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertFalse("sub_agent" in tools, "Leaf preset '$id' should not include sub_agent in default tool_set: $tools")
        }
        for (id in listOf("universal", "universal_reasoning", "researcher", "coder", "devops")) {
            val tools = AgentPresetRegistry.get(id)!!.parameters["tool_set"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("sub_agent" in tools, "Orchestrator/coding preset '$id' should retain sub_agent: $tools")
        }

        // 4. lightweight trimmed to [exit] and max_iterations = 64
        val lightweight = assertNotNull(AgentPresetRegistry.get("lightweight"))
        assertEquals(64, lightweight.parameters["max_iterations"]?.jsonPrimitive?.intOrNull)
        assertEquals(
            listOf("exit"),
            lightweight.parameters["tool_set"]?.jsonArray?.map { it.jsonPrimitive.content }
        )

        // 5. All 19 presets configure a fallback model distinct from primary
        allPresets.forEach { preset ->
            val llmConfig = preset.parameters["llm_config"]!!.jsonObject
            val primaryModel = llmConfig["models"]!!.jsonArray.first().jsonObject["model"]!!.jsonPrimitive.content
            val fallbackModel = llmConfig["fallback"]!!.jsonObject["model"]!!.jsonPrimitive.content
            assertTrue(
                primaryModel != fallbackModel,
                "Preset '${preset.id}' must have distinct primary ($primaryModel) and fallback ($fallbackModel) models"
            )
            assertEquals("gemini-2.5-flash", fallbackModel, "Preset '${preset.id}' fallback model")
        }
    }

    @Test
    fun `Phase 1 workflow step agents disable skill_tools by default unless explicitly opted in`(@TempDir tempDir: Path) = runBlocking {
        val skillDir = tempDir.resolve("skills/demo-skill").toFile().apply {
            mkdirs()
            File(this, "SKILL.md").writeText("---\nname: demo-skill\ndescription: Demo skill\n---\nInstructions\n")
        }
        val skillsRoot = skillDir.parentFile.absolutePath

        // Case A: Workflow agent uses coder preset (whose preset YAML lists skill_tools) WITHOUT opting in -> no skill tools sent
        FakeAnthropicApi { _, _ -> listOf(FakeAnthropicApi.text("done")) }.use { api ->
            val yaml = """
                name: default-skills-disabled
                version: "1.0.0"
                agents:
                  worker:
                    preset: coder
                    overrides:
                      strategy: single_run
                      history_enabled: false
                      llm_provider_keys: { anthropic: sk-ant-test }
                      llm_config:
                        models:
                          - { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
                        fallback: { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
                workflow:
                  - step: code_task
                    agent: worker
                    input: "Reply done."
            """.trimIndent()

            val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)
            val result = executor.execute(WorkflowParser.parseYaml(yaml))
            assertTrue(result.success, result.error)

            val wireTools = api.exchanges.first().request["tools"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                .orEmpty()
            assertFalse(
                wireTools.any { it.contains("skill", ignoreCase = true) },
                "Default workflow step agent must not register skill_tools without opt-in, got tools: $wireTools"
            )
        }

        // Case B: Workflow agent explicitly opts in via skills_config -> skill tools ARE registered
        FakeAnthropicApi { _, _ -> listOf(FakeAnthropicApi.text("done")) }.use { api ->
            val workflow = WorkflowDefinition(
                name = "explicit-skills-enabled",
                agents = mapOf(
                    "skilled_worker" to AgentDefinition(
                        preset = "coder",
                        skillsConfig = SkillsConfiguration(
                            enabled = true,
                            skillsPath = skillsRoot,
                            scanStandardPaths = false,
                            builtinSkillsEnabled = false
                        ),
                        overrides = mapOf(
                            "strategy" to JsonPrimitive("single_run"),
                            "history_enabled" to JsonPrimitive(false),
                            "llm_provider_keys" to JsonObject(mapOf("anthropic" to JsonPrimitive("sk-ant-test"))),
                            "llm_config" to JsonObject(
                                mapOf(
                                    "models" to JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "provider" to JsonPrimitive("anthropic"),
                                                    "model" to JsonPrimitive("claude-sonnet-4-6"),
                                                    "base_url" to JsonPrimitive(api.baseUrl)
                                                )
                                            )
                                        )
                                    ),
                                    "fallback" to JsonObject(
                                        mapOf(
                                            "provider" to JsonPrimitive("anthropic"),
                                            "model" to JsonPrimitive("claude-sonnet-4-6"),
                                            "base_url" to JsonPrimitive(api.baseUrl)
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                workflow = listOf(
                    WorkflowStep(step = "skilled_task", agent = "skilled_worker", input = "Reply done.")
                )
            )

            val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)
            val result = executor.execute(workflow)
            assertTrue(result.success, result.error)

            val wireTools = api.exchanges.first().request["tools"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                .orEmpty()
            assertTrue(
                wireTools.any { it.contains("skill", ignoreCase = true) },
                "Explicitly opted-in workflow agent must retain skill_tools, got tools: $wireTools"
            )
        }
    }

    // =========================================================================
    // Phase 2: WorkflowTemplateResolver Single-Pass Non-Reentrant Resolution
    // =========================================================================

    @Test
    fun `Phase 2 WorkflowTemplateResolver resolves in single pass without double-expanding upstream outputs`() {
        val context = WorkflowExecutionContext(
            workflowName = "resolver-security",
            executionId = "exec-resolver",
            variables = ConcurrentHashMap(
                mapOf<String, Any>(
                    "secret" to "SUPER_SECRET_API_KEY",
                    "shared" to "from_variable",
                    "nested_var" to "{{var:secret}}"
                )
            ),
            stepOutputs = ConcurrentHashMap(
                mapOf(
                    "untrusted_step" to "User said: {{var:secret}} and {{steps.other.output}}",
                    "other" to "INTERNAL_DATA",
                    "shared" to "from_step_output"
                )
            )
        )

        val resolvedFromStep = WorkflowTemplateResolver.resolve(
            "Analyze payload: {{steps.untrusted_step.output}}",
            context
        )
        assertEquals(
            "Analyze payload: User said: {{var:secret}} and {{steps.other.output}}",
            resolvedFromStep,
            "Upstream step output containing {{var:secret}} must NOT be expanded in a second pass"
        )

        val resolvedFromVar = WorkflowTemplateResolver.resolve(
            "Nested var: {{var:nested_var}}",
            context
        )
        assertEquals(
            "Nested var: {{var:secret}}",
            resolvedFromVar,
            "Variable value containing {{var:secret}} must NOT be expanded in a second pass"
        )

        // Precedence: bare {{shared}} resolves stepOutputs before variables; {{var:shared}} resolves variables
        assertEquals("from_step_output|from_variable|{{unknown}}",
            WorkflowTemplateResolver.resolve("{{shared}}|{{var:shared}}|{{unknown}}", context)
        )
    }

    // =========================================================================
    // Phase 2: Recursive state_machine sub_workflow Validation & Cycle Detection
    // =========================================================================

    @Test
    fun `Phase 2 WorkflowParser and WorkflowAnalyzer traverse sub_workflow inside state_machine states`() {
        val strictChild = WorkflowDefinition(
            name = "strict-child",
            version = "1.0.0",
            agents = emptyMap(),
            variables = mapOf("required_topic" to ""),
            workflow = listOf(
                WorkflowStep(step = "echo_child", code = CodeStepConfig(language = "bash", script = "echo ok"))
            ),
            module = WorkflowModuleContract(
                inputs = listOf(ModuleInputSpec(name = "required_topic", required = true)),
                outputs = listOf(ModuleOutputSpec(name = "child_out", source = "{{steps.echo_child.output}}"))
            )
        )
        val resolver = InMemoryWorkflowResolver(byId = mapOf("strict-child-id" to strictChild))

        // 1. Missing required input inside state_machine state's sub_workflow is caught by validateSubWorkflows
        val invalidParentYaml = """
            name: sm-subworkflow-invalid
            version: "1.0.0"
            agents: {}
            workflow:
              - step: flow
                state_machine:
                  initial_state: run_child
                  final_states: [done]
                  states:
                    run_child:
                      name: run_child
                      step:
                        sub_workflow:
                          workflow_id: strict-child-id
                          inputs: {}
                      transitions:
                        - event: complete
                          target: done
                    done:
                      name: done
        """.trimIndent()

        val validationEx = assertThrows<WorkflowValidationException> {
            WorkflowParser.parseYaml(invalidParentYaml, resolver)
        }
        assertTrue(validationEx.message!!.contains("required_topic"), "Actual message: ${validationEx.message}")

        // 2. Indirect cycle through state_machine sub_workflow (wfA -> state_machine -> wfB -> wfA) is caught
        val wfAYaml = """
            name: wf-a
            version: "1.0.0"
            agents: {}
            workflow:
              - step: sm
                state_machine:
                  initial_state: call_b
                  final_states: [done]
                  states:
                    call_b:
                      name: call_b
                      step:
                        sub_workflow:
                          name: wf-b
                      transitions:
                        - event: complete
                          target: done
                    done:
                      name: done
        """.trimIndent()
        val wfBYaml = """
            name: wf-b
            version: "1.0.0"
            agents: {}
            workflow:
              - step: back_to_a
                sub_workflow:
                  name: wf-a
        """.trimIndent()
        val wfAParsed = WorkflowParser.parseYaml(wfAYaml)
        val wfBParsed = WorkflowParser.parseYaml(wfBYaml)
        val cycleResolver = InMemoryWorkflowResolver(byName = mapOf("wf-a" to wfAParsed, "wf-b" to wfBParsed))

        val cycleEx = assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wfAParsed, cycleResolver)
        }
        assertTrue(cycleEx.message!!.contains("cyclic", ignoreCase = true), "Actual message: ${cycleEx.message}")

        // 3. WorkflowAnalyzer tracks variable reads & writes inside state_machine sub_workflow and set_var actions
        val validParentYaml = """
            name: sm-subworkflow-valid
            version: "1.0.0"
            agents: {}
            variables:
              input_topic: "Kotlin"
            workflow:
              - step: flow
                state_machine:
                  initial_state: run_child
                  final_states: [done]
                  states:
                    run_child:
                      name: run_child
                      step:
                        sub_workflow:
                          workflow_id: strict-child-id
                          inputs:
                            required_topic: "{{var:input_topic}}"
                          outputs:
                            captured_result: child_out
                      transitions:
                        - event: complete
                          target: done
                          actions:
                            - action: set_var
                              key: final_flag
                              value: "{{var:captured_result}}"
                    done:
                      name: done
        """.trimIndent()
        val validParent = WorkflowParser.parseYaml(validParentYaml, resolver)
        val referenced = WorkflowAnalyzer.collectReferencedVariables(validParent)
        val written = WorkflowAnalyzer.collectWrittenVariables(validParent)
        assertTrue("input_topic" in referenced, "Referenced vars: $referenced")
        assertTrue("captured_result" in written, "Written vars: $written")
        assertTrue("final_flag" in written, "Written vars should include set_var target: $written")
    }

    // =========================================================================
    // Phase 2: State Machine set_var Alias & Per-State retry / timeout_seconds
    // =========================================================================

    @Test
    fun `Phase 2 state_machine supports set_var action alias, per-state retry, and per-state timeout_seconds`() = runBlocking {
        val attempts = AtomicInteger(0)
        val retryExecutor = object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                val script = File(request.workingDir, request.command.last()).readText()
                return when {
                    "flaky_marker" in script -> {
                        val n = attempts.incrementAndGet()
                        if (n < 3) {
                            SubprocessExecutor.ExecResult(exitCode = 1, stdout = "", stderr = "transient-$n", durationMs = 1)
                        } else {
                            SubprocessExecutor.ExecResult(exitCode = 0, stdout = "recovered_on_$n", stderr = "", durationMs = 1)
                        }
                    }
                    "slow_marker" in script -> {
                        kotlinx.coroutines.delay(3_000)
                        SubprocessExecutor.ExecResult(exitCode = 0, stdout = "too_late", stderr = "", durationMs = 3_000)
                    }
                    else -> SubprocessExecutor.ExecResult(exitCode = 0, stdout = "ok", stderr = "", durationMs = 1)
                }
            }
        }

        val yaml = """
            name: sm-retry-timeout-set-var
            version: "1.0.0"
            agents: {}
            workflow:
              - step: machine
                state_machine:
                  initial_state: flaky_state
                  final_states: [timed_out_handled]
                  states:
                    flaky_state:
                      name: flaky_state
                      step:
                        code:
                          language: bash
                          script: "echo flaky_marker"
                        retry:
                          max_attempts: 3
                          initial_delay: 10
                          max_delay: 20
                      transitions:
                        - event: complete
                          target: slow_state
                          actions:
                            - action: set_var
                              key: retry_outcome
                              value: "passed_after_retry"
                    slow_state:
                      name: slow_state
                      step:
                        code:
                          language: bash
                          script: "echo slow_marker"
                        timeout_seconds: 1
                      transitions:
                        - event: error
                          target: timed_out_handled
                          actions:
                            - action: set_var
                              key: timeout_outcome
                              value: "caught_timeout"
                    timed_out_handled:
                      name: timed_out_handled
        """.trimIndent()

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = retryExecutor
        )
        val result = executor.execute(WorkflowParser.parseYaml(yaml))

        assertTrue(result.success, "Expected state machine to handle retry and timeout transition: ${result.error}")
        assertEquals(3, attempts.get(), "Flaky state should have retried until 3rd attempt")
        assertEquals("passed_after_retry", result.variables["retry_outcome"])
        assertEquals("caught_timeout", result.variables["timeout_outcome"])
        assertEquals("timed_out_handled", result.variables["machine_final_state"])
    }

    // =========================================================================
    // Phase 3: Silent Failure Fixes & Parallel .wf_env Isolation
    // =========================================================================

    @Test
    fun `Phase 3 group_chat returns failure when all speaker turns fail instead of silently succeeding`() = runBlocking {
        val yaml = """
            name: group-chat-all-fail
            version: "1.0.0"
            agents:
              alice:
                preset: lightweight
                overrides:
                  retry_max_attempts: 1
                  history_enabled: false
                  llm_provider_keys: { anthropic: sk-ant-test }
                  llm_config:
                    models:
                      - { provider: anthropic, model: claude-sonnet-4-6, base_url: "http://127.0.0.1:1/v1" }
                    fallback: { provider: anthropic, model: claude-sonnet-4-6, base_url: "http://127.0.0.1:1/v1" }
              bob:
                preset: lightweight
                overrides:
                  retry_max_attempts: 1
                  history_enabled: false
                  llm_provider_keys: { anthropic: sk-ant-test }
                  llm_config:
                    models:
                      - { provider: anthropic, model: claude-sonnet-4-6, base_url: "http://127.0.0.1:1/v1" }
                    fallback: { provider: anthropic, model: claude-sonnet-4-6, base_url: "http://127.0.0.1:1/v1" }
            workflow:
              - step: debate
                group_chat:
                  participants: [alice, bob]
                  max_rounds: 1
                  initial_message: "Discuss architecture"
        """.trimIndent()

        val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)
        val result = executor.execute(WorkflowParser.parseYaml(yaml))

        assertFalse(result.success, "group_chat must fail when all speaker turns fail")
        assertFalse(result.stepResults["debate"]?.success ?: true)
        assertTrue(
            result.stepResults["debate"]?.error.orEmpty().contains("failed", ignoreCase = true),
            "Expected descriptive error, got: ${result.stepResults["debate"]?.error}"
        )
    }

    @Test
    fun `Phase 3 repeat_until logs evaluate_agent error and fails when fail_on_max_iterations is true`() = runBlocking {
        FakeAnthropicApi { _, _ -> listOf(FakeAnthropicApi.text("draft_attempt")) }.use { api ->
            val yaml = """
                name: repeat-until-fail-on-max
                version: "1.0.0"
                agents:
                  drafter:
                    preset: lightweight
                    overrides:
                      history_enabled: false
                      llm_provider_keys: { anthropic: sk-ant-test }
                      llm_config:
                        models:
                          - { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
                        fallback: { provider: anthropic, model: claude-sonnet-4-6, base_url: "${api.baseUrl}" }
                  broken_evaluator:
                    preset: lightweight
                    overrides:
                      retry_max_attempts: 1
                      history_enabled: false
                      llm_provider_keys: { anthropic: sk-ant-test }
                      llm_config:
                        models:
                          - { provider: anthropic, model: claude-sonnet-4-6, base_url: "http://127.0.0.1:1/v1" }
                        fallback: { provider: anthropic, model: claude-sonnet-4-6, base_url: "http://127.0.0.1:1/v1" }
                variables:
                  score: "3"
                workflow:
                  - step: refine
                    agent: drafter
                    input: "Write a draft."
                    repeat_until:
                      condition: "score >= 8"
                      max_iterations: 2
                      fail_on_max_iterations: true
                      evaluate_agent: broken_evaluator
                      evaluate_prompt: "Score {{steps.refine.output}}"
                      extract_pattern: "score=(\\d+)"
                      extract_variable: score
            """.trimIndent()

            val executionId = "repeat-fail-" + UUID.randomUUID()
            val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = true)
            val result = executor.execute(WorkflowParser.parseYaml(yaml), externalExecutionId = executionId)

            assertFalse(result.success, "Workflow must fail when fail_on_max_iterations=true and condition is unmet")
            assertTrue(
                result.stepResults["refine"]?.error.orEmpty().contains("exhausted max_iterations (2)"),
                "Actual step error: ${result.stepResults["refine"]?.error}"
            )
            val events = WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("refine")?.eventsSnapshot().orEmpty()
            assertTrue(
                events.any { it.type == "repeat_until_evaluate_failed" && it.summary.contains("broken_evaluator") },
                "Expected repeat_until_evaluate_failed monitor event, got: ${events.map { it.type to it.summary }}"
            )
        }
    }

    @Test
    fun `Phase 3 extract supports multiline anchors without inline flag and clear_on_missing`() = runBlocking {
        val workflow = WorkflowDefinition(
            name = "extract-multiline-and-clear",
            agents = emptyMap(),
            variables = mapOf(
                "stale_regex_var" to "old_regex_val",
                "stale_json_var" to "old_json_val",
                "kept_var" to "keep_me"
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "emit",
                    code = CodeStepConfig(
                        language = "python",
                        script = """
                            print("header line")
                            print("version=2.4.1")
                            print('{"present": "yes"}')
                        """.trimIndent()
                    ),
                    extract = listOf(
                        ExtractConfig(pattern = "^version=([0-9.]+)$", variable = "parsed_version"),
                        ExtractConfig(pattern = "^missing_key=(\\w+)$", variable = "stale_regex_var", clearOnMissing = true),
                        ExtractConfig(jsonPath = "$.absent_field", variable = "stale_json_var", clearOnMissing = true),
                        ExtractConfig(pattern = "^missing_keep=(\\w+)$", variable = "kept_var", clearOnMissing = false)
                    )
                )
            )
        )

        val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)
        val result = executor.execute(workflow)

        assertTrue(result.success, result.error)
        assertEquals("2.4.1", result.variables["parsed_version"], "Multiline ^...$ anchor should match middle line")
        assertNull(result.variables["stale_regex_var"], "clear_on_missing=true should remove stale regex variable")
        assertNull(result.variables["stale_json_var"], "clear_on_missing=true should remove stale json_path variable")
        assertEquals("keep_me", result.variables["kept_var"], "clear_on_missing=false should preserve existing variable")
    }

    @Test
    fun `Phase 3 parallel code steps without directory isolation isolate per-step wf_env directories`(@TempDir tempDir: Path) = runBlocking {
        val observedPythonPaths = Collections.synchronizedList(mutableListOf<String>())
        val realNative = NativeSubprocessExecutor()
        val spyingExecutor = object : SubprocessExecutor {
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                request.env["PYTHONPATH"]?.let { observedPythonPaths += it }
                return realNative.execute(request)
            }
        }

        val largeAlpha = "A".repeat(70_000)
        val largeBeta = "B".repeat(72_000)
        val workDir = tempDir.toFile().canonicalPath

        val workflow = WorkflowDefinition(
            name = "parallel-wf-env-isolation",
            agents = emptyMap(),
            directoryIsolation = DirectoryIsolationConfig(enabled = false),
            variables = mapOf(
                "big_alpha" to largeAlpha,
                "big_beta" to largeBeta
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "step_alpha",
                    code = CodeStepConfig(
                        language = "python",
                        workingDirectory = workDir,
                        script = """
                            import time
                            time.sleep(0.1)
                            v = get_var("big_alpha")
                            print(f"alpha:{len(v)}:{v[0]}:{v[-1]}")
                        """.trimIndent()
                    )
                ),
                WorkflowStep(
                    step = "step_beta",
                    code = CodeStepConfig(
                        language = "python",
                        workingDirectory = workDir,
                        script = """
                            import time
                            time.sleep(0.1)
                            v = get_var("big_beta")
                            print(f"beta:{len(v)}:{v[0]}:{v[-1]}")
                        """.trimIndent()
                    )
                )
            )
        )

        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = emptyList(),
            enableMonitoring = false,
            codeStepExecutor = spyingExecutor
        )
        val result = executor.execute(workflow)

        assertTrue(result.success, "Parallel oversized-env code steps failed: ${result.error}")
        assertEquals("alpha:70000:A:A", result.stepResults["step_alpha"]?.output?.trim())
        assertEquals("beta:72000:B:B", result.stepResults["step_beta"]?.output?.trim())

        assertTrue(
            observedPythonPaths.any { ".wf_env_step_alpha" in it },
            "Expected per-step .wf_env_step_alpha in PYTHONPATH, got: $observedPythonPaths"
        )
        assertTrue(
            observedPythonPaths.any { ".wf_env_step_beta" in it },
            "Expected per-step .wf_env_step_beta in PYTHONPATH, got: $observedPythonPaths"
        )
        assertFalse(File(workDir, ".wf_env_step_alpha").exists(), ".wf_env_step_alpha should be cleaned up")
        assertFalse(File(workDir, ".wf_env_step_beta").exists(), ".wf_env_step_beta should be cleaned up")
    }

    @Test
    fun `Phase 2 on_failure action continue and set_var recover optional notification steps cleanly`() = runBlocking {
        val yaml = """
            name: optional-notify-recovery
            version: "1.0.0"
            agents: {}
            variables:
              delivery_status: "pending"
            workflow:
              - step: build_report
                code:
                  language: bash
                  script: "echo report_ready"
              - step: deliver_to_slack
                depends_on: [build_report]
                code:
                  language: bash
                  script: "echo 'webhook unconfigured' >&2; exit 1"
                on_failure:
                  - action: continue
                    message: "Optional Slack notification failed; continuing workflow"
                  - action: set_var
                    key: delivery_status
                    value: "skipped_on_error"
              - step: finalize
                depends_on: [build_report]
                code:
                  language: bash
                  script: "echo finalized"
        """.trimIndent()

        val executor = WorkflowExecutor(httpAccess = HttpAccess(), baseParameters = emptyList(), enableMonitoring = false)
        val result = executor.execute(WorkflowParser.parseYaml(yaml))

        assertTrue(result.success, "Optional step with on_failure action=continue should not fail overall workflow: ${result.error}")
        assertTrue("deliver_to_slack" in result.recoveredFailures, "deliver_to_slack should be recorded in recoveredFailures")
        assertEquals("skipped_on_error", result.variables["delivery_status"])
        assertEquals("finalized", result.stepResults["finalize"]?.output?.trim())
    }
}
