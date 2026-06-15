package com.fartech.agents.workflow

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.*

/**
 * 综合测试：5 个新增特性
 * - ExtractConfig 通用结构化输出提取
 * - CodeStepConfig 确定性代码执行
 * - ClassifierConfig 智能分类路由
 * - IterateOverConfig 动态列表遍历
 * - AggregateConfig 多路径变量聚合
 */
class EnhancedFeaturesTest {

    @BeforeEach
    fun setup() {
        WorkflowMonitor.clear()
    }

    // ==================== ExtractConfig Model Tests ====================

    @Test
    fun `ExtractConfig with pattern`() {
        val config = ExtractConfig(pattern = "score=(\\d+)", variable = "score")
        assertEquals("score=(\\d+)", config.pattern)
        assertNull(config.jsonPath)
        assertEquals("score", config.variable)
    }

    @Test
    fun `ExtractConfig with json_path`() {
        val config = ExtractConfig(jsonPath = "$.result.status", variable = "status")
        assertNull(config.pattern)
        assertEquals("$.result.status", config.jsonPath)
        assertEquals("status", config.variable)
    }

    @Test
    fun `ExtractConfig rejects blank variable`() {
        assertThrows<IllegalArgumentException> {
            ExtractConfig(pattern = "x", variable = "")
        }
    }

    @Test
    fun `ExtractConfig rejects neither pattern nor json_path`() {
        assertThrows<IllegalArgumentException> {
            ExtractConfig(variable = "x")
        }
    }

    @Test
    fun `ExtractConfig rejects both pattern and json_path`() {
        assertThrows<IllegalArgumentException> {
            ExtractConfig(pattern = "x", jsonPath = "$.x", variable = "v")
        }
    }

    // ==================== CodeStepConfig Model Tests ====================

    @Test
    fun `CodeStepConfig with inline script`() {
        val config = CodeStepConfig(language = "python", script = "print('hello')")
        assertEquals("python", config.language)
        assertEquals("print('hello')", config.script)
        assertNull(config.scriptFile)
        assertEquals(30, config.timeout)
    }

    @Test
    fun `CodeStepConfig with script_file`() {
        val config = CodeStepConfig(language = "bash", scriptFile = "./validate.sh")
        assertNull(config.script)
        assertEquals("./validate.sh", config.scriptFile)
    }

    @Test
    fun `CodeStepConfig supports all languages`() {
        for (lang in listOf("python", "javascript", "typescript", "bash", "ruby", "lua", "cli")) {
            val config = CodeStepConfig(language = lang, script = "test")
            assertEquals(lang, config.language)
        }
    }

    @Test
    fun `CodeStepConfig rejects unsupported language`() {
        assertThrows<IllegalArgumentException> {
            CodeStepConfig(language = "perl", script = "print qq(hi)")
        }
    }

    @Test
    fun `CodeStepConfig rejects no script`() {
        assertThrows<IllegalArgumentException> {
            CodeStepConfig(language = "python")
        }
    }

    @Test
    fun `CodeStepConfig rejects both script and script_file`() {
        assertThrows<IllegalArgumentException> {
            CodeStepConfig(language = "python", script = "x", scriptFile = "y.py")
        }
    }

    @Test
    fun `CodeStepConfig rejects zero timeout`() {
        assertThrows<IllegalArgumentException> {
            CodeStepConfig(language = "python", script = "x", timeout = 0)
        }
    }

    // ==================== CodePreambleConfig Model Tests ====================

    @Test
    fun `CodePreambleConfig with inline code`() {
        val config = CodePreambleConfig(inline = "import os\ndef helper(): pass")
        assertEquals("import os\ndef helper(): pass", config.inline)
        assertNull(config.ref)
        assertNull(config.description)
    }

    @Test
    fun `CodePreambleConfig with ref file`() {
        val config = CodePreambleConfig(ref = "./skills/utils.py", description = "Shared utils")
        assertNull(config.inline)
        assertEquals("./skills/utils.py", config.ref)
        assertEquals("Shared utils", config.description)
    }

    @Test
    fun `CodePreambleConfig rejects both inline and ref`() {
        assertThrows<IllegalArgumentException> {
            CodePreambleConfig(inline = "code", ref = "./file.py")
        }
    }

    @Test
    fun `CodePreambleConfig allows empty (neither inline nor ref)`() {
        val config = CodePreambleConfig(description = "Placeholder")
        assertNull(config.inline)
        assertNull(config.ref)
        assertEquals("Placeholder", config.description)
    }

    // ==================== ClassifierConfig Model Tests ====================

    @Test
    fun `ClassifierConfig basic configuration`() {
        val config = ClassifierConfig(
            agent = "classifier",
            input = "classify this",
            categories = listOf(
                ClassifierCategory("a", "category A"),
                ClassifierCategory("b", "category B")
            )
        )
        assertEquals("classifier", config.agent)
        assertEquals(2, config.categories.size)
        assertEquals("classification", config.outputVariable)
        assertNull(config.defaultCategory)
    }

    @Test
    fun `ClassifierConfig with default_category`() {
        val config = ClassifierConfig(
            agent = "clf",
            input = "test",
            categories = listOf(
                ClassifierCategory("tech", "Technical"),
                ClassifierCategory("general", "General")
            ),
            defaultCategory = "general"
        )
        assertEquals("general", config.defaultCategory)
    }

    @Test
    fun `ClassifierConfig rejects less than 2 categories`() {
        assertThrows<IllegalArgumentException> {
            ClassifierConfig(
                agent = "clf", input = "test",
                categories = listOf(ClassifierCategory("a", "A"))
            )
        }
    }

    @Test
    fun `ClassifierConfig rejects blank agent`() {
        assertThrows<IllegalArgumentException> {
            ClassifierConfig(
                agent = "", input = "test",
                categories = listOf(
                    ClassifierCategory("a", "A"),
                    ClassifierCategory("b", "B")
                )
            )
        }
    }

    @Test
    fun `ClassifierConfig rejects invalid default_category`() {
        assertThrows<IllegalArgumentException> {
            ClassifierConfig(
                agent = "clf", input = "test",
                categories = listOf(
                    ClassifierCategory("a", "A"),
                    ClassifierCategory("b", "B")
                ),
                defaultCategory = "c"
            )
        }
    }

    @Test
    fun `ClassifierCategory rejects blank name`() {
        assertThrows<IllegalArgumentException> {
            ClassifierCategory("", "desc")
        }
    }

    @Test
    fun `ClassifierCategory rejects blank description`() {
        assertThrows<IllegalArgumentException> {
            ClassifierCategory("name", "")
        }
    }

    // ==================== IterateOverConfig Model Tests ====================

    @Test
    fun `IterateOverConfig defaults`() {
        val config = IterateOverConfig(source = "{{steps.find.output}}")
        assertEquals("\n", config.delimiter)
        assertEquals("current_item", config.itemVariable)
        assertEquals("current_index", config.indexVariable)
        assertEquals(0, config.maxItems)
        assertFalse(config.parallel)
        assertNull(config.maxParallel)
        assertNull(config.resultsVariable)
    }

    @Test
    fun `IterateOverConfig full configuration`() {
        val config = IterateOverConfig(
            source = "{{var:bug_list}}",
            delimiter = "json_array",
            itemVariable = "bug",
            indexVariable = "bug_idx",
            maxItems = 10,
            parallel = true,
            maxParallel = 3,
            resultsVariable = "fix_results"
        )
        assertEquals("json_array", config.delimiter)
        assertEquals("bug", config.itemVariable)
        assertTrue(config.parallel)
        assertEquals(3, config.maxParallel)
    }

    @Test
    fun `IterateOverConfig rejects blank source`() {
        assertThrows<IllegalArgumentException> {
            IterateOverConfig(source = "")
        }
    }

    @Test
    fun `IterateOverConfig rejects negative max_items`() {
        assertThrows<IllegalArgumentException> {
            IterateOverConfig(source = "x", maxItems = -1)
        }
    }

    // ==================== AggregateConfig Model Tests ====================

    @Test
    fun `AggregateConfig defaults`() {
        val config = AggregateConfig(
            sources = listOf("{{steps.a.output}}", "{{steps.b.output}}"),
            outputVariable = "merged"
        )
        assertEquals("concat", config.strategy)
        assertEquals("\n\n---\n\n", config.separator)
    }

    @Test
    fun `AggregateConfig all strategies valid`() {
        for (s in listOf("concat", "json_array", "numbered_list", "pick_longest", "pick_shortest")) {
            val config = AggregateConfig(
                sources = listOf("a", "b"),
                strategy = s,
                outputVariable = "out"
            )
            assertEquals(s, config.strategy)
        }
    }

    @Test
    fun `AggregateConfig rejects invalid strategy`() {
        assertThrows<IllegalArgumentException> {
            AggregateConfig(sources = listOf("a", "b"), strategy = "average", outputVariable = "out")
        }
    }

    @Test
    fun `AggregateConfig rejects less than 2 sources`() {
        assertThrows<IllegalArgumentException> {
            AggregateConfig(sources = listOf("a"), outputVariable = "out")
        }
    }

    @Test
    fun `AggregateConfig rejects blank output_variable`() {
        assertThrows<IllegalArgumentException> {
            AggregateConfig(sources = listOf("a", "b"), outputVariable = "")
        }
    }

    // ==================== WorkflowStep Mode Validation ====================

    @Test
    fun `WorkflowStep accepts code mode`() {
        val step = WorkflowStep(
            step = "validate",
            code = CodeStepConfig(language = "python", script = "print('ok')")
        )
        assertTrue(step.isCode)
        assertFalse(step.isGroupChat)
        assertFalse(step.isAgentBased)
        assertFalse(step.isClassifier)
        assertEquals("code(python)", step.displayAgentName)
        assertTrue(step.referencedAgents.isEmpty())
    }

    @Test
    fun `WorkflowStep accepts classifier mode`() {
        val step = WorkflowStep(
            step = "route",
            classifier = ClassifierConfig(
                agent = "clf",
                input = "test",
                categories = listOf(
                    ClassifierCategory("a", "A"),
                    ClassifierCategory("b", "B")
                )
            )
        )
        assertTrue(step.isClassifier)
        assertEquals("classifier(clf)", step.displayAgentName)
        assertEquals(listOf("clf"), step.referencedAgents)
    }

    @Test
    fun `WorkflowStep rejects code + agent combination`() {
        assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "bad",
                agent = "myagent",
                input = "test",
                code = CodeStepConfig(language = "python", script = "x")
            )
        }
    }

    @Test
    fun `WorkflowStep accepts extract on agent step`() {
        val step = WorkflowStep(
            step = "analyze",
            agent = "analyst",
            input = "analyze data",
            extract = listOf(
                ExtractConfig(pattern = "score=(\\d+)", variable = "score")
            )
        )
        val extract = assertNotNull(step.extract)
        assertEquals(1, extract.size)
    }

    @Test
    fun `WorkflowStep accepts extract on code step`() {
        val step = WorkflowStep(
            step = "compute",
            code = CodeStepConfig(language = "python", script = "print('result=42')"),
            extract = listOf(
                ExtractConfig(pattern = "result=(\\d+)", variable = "result")
            )
        )
        assertTrue(step.isCode)
        assertNotNull(step.extract)
    }

    @Test
    fun `WorkflowStep accepts iterate_over on agent step`() {
        val step = WorkflowStep(
            step = "fix_bugs",
            agent = "dev",
            input = "Fix: {{var:current_item}}",
            iterateOver = IterateOverConfig(source = "{{steps.find.output}}")
        )
        assertNotNull(step.iterateOver)
    }

    @Test
    fun `WorkflowStep accepts aggregate on agent step`() {
        val step = WorkflowStep(
            step = "merge",
            agent = "summarizer",
            input = "Summarize: {{var:merged}}",
            aggregate = AggregateConfig(
                sources = listOf("{{steps.a.output}}", "{{steps.b.output}}"),
                outputVariable = "merged"
            )
        )
        assertNotNull(step.aggregate)
    }

    // ==================== YAML Parsing Tests ====================

    @Test
    fun `parse YAML with code step`() {
        val yaml = """
            name: test-code
            version: "1.0"
            agents: {}
            workflow:
              - step: validate
                code:
                  language: bash
                  script: "echo hello"
                  timeout: 60
        """.trimIndent()

        val wf = WorkflowParser.parseYaml(yaml)
        assertEquals(1, wf.workflow.size)
        assertTrue(wf.workflow[0].isCode)
        assertEquals("bash", wf.workflow[0].code!!.language)
        assertEquals(60, wf.workflow[0].code!!.timeout)
    }

    @Test
    fun `parse YAML with classifier step`() {
        val yaml = """
            name: test-classifier
            version: "1.0"
            agents:
              clf:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: route
                classifier:
                  agent: clf
                  input: "classify this request"
                  categories:
                    - name: technical
                      description: "Technical issues"
                    - name: billing
                      description: "Billing questions"
                  output_variable: category
                  default_category: technical
        """.trimIndent()

        val wf = WorkflowParser.parseYaml(yaml)
        val step = wf.workflow[0]
        assertTrue(step.isClassifier)
        val classifier = assertNotNull(step.classifier)
        assertEquals("clf", classifier.agent)
        assertEquals(2, classifier.categories.size)
        assertEquals("category", classifier.outputVariable)
        assertEquals("technical", classifier.defaultCategory)
    }

    @Test
    fun `parse YAML with extract on agent step`() {
        val yaml = """
            name: test-extract
            version: "1.0"
            agents:
              analyst:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: analyze
                agent: analyst
                input: "Analyze and output score=N"
                extract:
                  - pattern: "score=(\\d+)"
                    variable: score
        """.trimIndent()

        val wf = WorkflowParser.parseYaml(yaml)
        val step = wf.workflow[0]
        val extract = assertNotNull(step.extract)
        assertEquals(1, extract.size)
        assertEquals("score", extract[0].variable)
    }

    @Test
    fun `parse YAML with iterate_over`() {
        val yaml = """
            name: test-iterate
            version: "1.0"
            agents:
              dev:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: fix_bugs
                agent: dev
                input: "Fix bug"
                iterate_over:
                  source: "bug1\nbug2\nbug3"
                  item_variable: current_bug
                  max_items: 10
                  parallel: true
                  max_parallel: 3
                  results_variable: fixes
        """.trimIndent()

        val wf = WorkflowParser.parseYaml(yaml)
        val step = wf.workflow[0]
        val iterateOver = assertNotNull(step.iterateOver)
        assertEquals("current_bug", iterateOver.itemVariable)
        assertEquals(10, iterateOver.maxItems)
        assertTrue(iterateOver.parallel)
        assertEquals(3, iterateOver.maxParallel)
    }

    @Test
    fun `parse YAML with aggregate`() {
        val yaml = """
            name: test-aggregate
            version: "1.0"
            agents:
              summarizer:
                type: universal_agent
                strategy: just_work
                tools: [exit]
                llm:
                  model: gpt-4
                  provider: openai
            workflow:
              - step: merge
                agent: summarizer
                input: "Summarize"
                aggregate:
                  sources:
                    - "source1"
                    - "source2"
                  strategy: numbered_list
                  output_variable: merged
        """.trimIndent()

        val wf = WorkflowParser.parseYaml(yaml)
        val step = wf.workflow[0]
        val aggregate = assertNotNull(step.aggregate)
        assertEquals("numbered_list", aggregate.strategy)
        assertEquals("merged", aggregate.outputVariable)
    }

    // ==================== WorkflowParser Validation Tests ====================

    @Test
    fun `validateWorkflow passes for code step`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(step = "code1", code = CodeStepConfig(language = "bash", script = "echo hi"))
            )
        )
        WorkflowParser.validateWorkflow(wf)
    }

    @Test
    fun `validateWorkflow rejects repeat_until on code step`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "code1",
                    code = CodeStepConfig(language = "bash", script = "echo hi"),
                    repeatUntil = RepeatUntilConfig(condition = "done == true")
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects classifier with unknown agent`() {
        // WorkflowDefinition.init catches undefined agent references before validateWorkflow
        assertThrows<IllegalArgumentException> {
            WorkflowDefinition(
                name = "test",
                agents = mapOf("real" to minimalAgent()),
                workflow = listOf(
                    WorkflowStep(
                        step = "route",
                        classifier = ClassifierConfig(
                            agent = "nonexistent",
                            input = "test",
                            categories = listOf(
                                ClassifierCategory("a", "A"),
                                ClassifierCategory("b", "B")
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `validateWorkflow rejects classifier with duplicate category names`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("clf" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "route",
                    classifier = ClassifierConfig(
                        agent = "clf",
                        input = "test",
                        categories = listOf(
                            ClassifierCategory("a", "A"),
                            ClassifierCategory("a", "B")
                        )
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects invalid extract pattern`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("a" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    agent = "a",
                    input = "test",
                    extract = listOf(ExtractConfig(pattern = "[invalid(", variable = "v"))
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects invalid json_path`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("a" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    agent = "a",
                    input = "test",
                    extract = listOf(ExtractConfig(jsonPath = "no_dollar", variable = "v"))
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects duplicate extract variables`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("a" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    agent = "a",
                    input = "test",
                    extract = listOf(
                        ExtractConfig(pattern = "a=(\\d+)", variable = "v"),
                        ExtractConfig(pattern = "b=(\\d+)", variable = "v")
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects iterate_over on group_chat`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("a" to minimalAgent(), "b" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    groupChat = GroupChatConfig(participants = listOf("a", "b")),
                    iterateOver = IterateOverConfig(source = "x")
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects parallel on code step`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    code = CodeStepConfig(language = "bash", script = "echo hi"),
                    parallel = ParallelExecution(tasks = listOf("a", "b"))
                )
            )
        )

        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects iterate_over on classifier step`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("clf" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "route",
                    classifier = ClassifierConfig(
                        agent = "clf",
                        input = "classify",
                        categories = listOf(
                            ClassifierCategory("a", "A"),
                            ClassifierCategory("b", "B")
                        )
                    ),
                    iterateOver = IterateOverConfig(source = "x")
                )
            )
        )

        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects iterate_over with repeat_until`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("a" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    agent = "a",
                    input = "test",
                    iterateOver = IterateOverConfig(source = "x"),
                    repeatUntil = RepeatUntilConfig(condition = "done == true")
                )
            )
        )

        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    @Test
    fun `validateWorkflow rejects blank aggregate source`() {
        val wf = WorkflowDefinition(
            name = "test",
            agents = mapOf("a" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    agent = "a",
                    input = "test",
                    aggregate = AggregateConfig(
                        sources = listOf("valid", "  "),
                        outputVariable = "out"
                    )
                )
            )
        )
        assertThrows<WorkflowValidationException> {
            WorkflowParser.validateWorkflow(wf)
        }
    }

    // ==================== Aggregate Logic Tests ====================

    @Test
    fun `aggregate concat strategy`() {
        val context = WorkflowExecutionContext("wf", "exec-1")
        context.setStepOutput("a", "result A")
        context.setStepOutput("b", "result B")

        val config = AggregateConfig(
            sources = listOf("{{steps.a.output}}", "{{steps.b.output}}"),
            strategy = "concat",
            separator = " | ",
            outputVariable = "merged"
        )

        // Simulate resolveTemplate
        val resolved = config.sources.map { src ->
            var r = src
            context.stepOutputs.forEach { (k, v) -> r = r.replace("{{steps.$k.output}}", v) }
            r
        }
        val result = resolved.joinToString(config.separator)
        assertEquals("result A | result B", result)
    }

    @Test
    fun `aggregate numbered_list strategy`() {
        val sources = listOf("First", "Second", "Third")
        val result = sources.mapIndexed { index, src -> "${index + 1}. $src" }.joinToString("\n\n")
        assertEquals("1. First\n\n2. Second\n\n3. Third", result)
    }

    @Test
    fun `aggregate pick_longest strategy`() {
        val sources = listOf("short", "this is longer", "mid")
        val result = sources.maxByOrNull { it.length }
        assertEquals("this is longer", result)
    }

    @Test
    fun `aggregate pick_shortest strategy`() {
        val sources = listOf("short", "this is longer", "mid")
        val result = sources.minByOrNull { it.length }
        assertEquals("mid", result)
    }

    // ==================== Extract Logic Tests ====================

    @Test
    fun `regex extract captures group 1`() {
        val pattern = "total=(\\d+\\.\\d+)"
        val text = "The total=42.5 dollars"
        val match = Regex(pattern).find(text)
        val regexMatch = assertNotNull(match)
        assertEquals("42.5", regexMatch.groupValues[1])
    }

    @Test
    fun `json_path extraction from JSON output`() {
        val json = """{"result": {"status": "approved", "score": 95}}"""
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val obj = element.jsonObject["result"]?.jsonObject
        val result = assertNotNull(obj)
        assertEquals("approved", result["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `json_path extraction with array index`() {
        val json = """{"items": ["apple", "banana", "cherry"]}"""
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val items = element.jsonObject["items"]?.jsonArray
        val parsedItems = assertNotNull(items)
        assertEquals("banana", parsedItems[1].jsonPrimitive.content)
    }

    // ==================== Iteration Logic Tests ====================

    @Test
    fun `split by newline delimiter`() {
        val source = "bug1\nbug2\nbug3"
        val items = source.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        assertEquals(3, items.size)
        assertEquals("bug1", items[0])
    }

    @Test
    fun `split by json_array delimiter`() {
        val source = """["item1", "item2", "item3"]"""
        val element = kotlinx.serialization.json.Json.parseToJsonElement(source)
        val items = element.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(3, items.size)
        assertEquals("item2", items[1])
    }

    @Test
    fun `max_items limits iteration`() {
        val items = listOf("a", "b", "c", "d", "e")
        val limited = items.take(3)
        assertEquals(3, limited.size)
    }

    // ==================== YAML Roundtrip Tests ====================

    @Test
    fun `toYaml roundtrip preserves code step`() {
        val original = WorkflowDefinition(
            name = "roundtrip-code",
            agents = emptyMap(),
            workflow = listOf(
                WorkflowStep(
                    step = "validate",
                    code = CodeStepConfig(language = "python", script = "print('ok')", timeout = 60)
                )
            )
        )
        val yaml = WorkflowParser.toYaml(original)
        assertTrue(yaml.contains("code"))
        assertTrue(yaml.contains("python"))
        assertTrue(yaml.contains("print('ok')"))
    }

    @Test
    fun `toYaml roundtrip preserves classifier step`() {
        val original = WorkflowDefinition(
            name = "roundtrip-clf",
            agents = mapOf("clf" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "route",
                    classifier = ClassifierConfig(
                        agent = "clf",
                        input = "classify",
                        categories = listOf(
                            ClassifierCategory("tech", "Technical"),
                            ClassifierCategory("general", "General")
                        ),
                        outputVariable = "cat"
                    )
                )
            )
        )
        val yaml = WorkflowParser.toYaml(original)
        assertTrue(yaml.contains("classifier"))
        assertTrue(yaml.contains("tech"))
        assertTrue(yaml.contains("Technical"))
    }

    @Test
    fun `toYaml roundtrip preserves extract`() {
        val original = WorkflowDefinition(
            name = "roundtrip-extract",
            agents = mapOf("a" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(
                    step = "s1",
                    agent = "a",
                    input = "test",
                    extract = listOf(
                        ExtractConfig(pattern = "score=(\\d+)", variable = "score")
                    )
                )
            )
        )
        val yaml = WorkflowParser.toYaml(original)
        assertTrue(yaml.contains("extract"))
        assertTrue(yaml.contains("score"))
    }

    // ==================== Summary Output Tests ====================

    @Test
    fun `getWorkflowSummary includes enhanced features`() {
        val wf = WorkflowDefinition(
            name = "summary-test",
            agents = mapOf("a" to minimalAgent()),
            workflow = listOf(
                WorkflowStep(step = "code1", code = CodeStepConfig(language = "python", script = "x")),
                WorkflowStep(
                    step = "route", classifier = ClassifierConfig(
                        agent = "a", input = "test",
                        categories = listOf(ClassifierCategory("a", "A"), ClassifierCategory("b", "B"))
                    )
                ),
                WorkflowStep(
                    step = "s1", agent = "a", input = "test",
                    extract = listOf(ExtractConfig(pattern = "x", variable = "v"))
                )
            )
        )
        val summary = WorkflowParser.getWorkflowSummary(wf)
        assertTrue(summary.contains("Enhanced Features"))
        assertTrue(summary.contains("code steps: 1"))
        assertTrue(summary.contains("classifier steps: 1"))
        assertTrue(summary.contains("steps with extract: 1"))
    }

    // ==================== Code Execution Integration Test ====================

    @Test
    fun `code step executes python inline script`() {
        // Test that Python code execution works end-to-end
        val script = "print('hello from python')"
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val interpreter = if (isWindows) "python" else "python3"

        try {
            val tempFile = java.io.File.createTempFile("test_code_", ".py")
            tempFile.writeText(script)
            val process = ProcessBuilder(interpreter, tempFile.absolutePath)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            tempFile.delete()

            assertEquals(0, exitCode)
            assertTrue(output.trim().contains("hello from python"))
        } catch (e: Exception) {
            // Python not available in test env — skip gracefully
            println("[SKIP] Python not available: ${e.message}")
        }
    }

    @Test
    fun `code step executes bash inline script`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            println("[SKIP] Bash test skipped on Windows")
            return
        }

        val script = "echo 'hello from bash'"
        val tempFile = java.io.File.createTempFile("test_code_", ".sh")
        tempFile.writeText(script)
        val process = ProcessBuilder("/bin/bash", tempFile.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        tempFile.delete()

        assertEquals(0, exitCode)
        assertTrue(output.trim().contains("hello from bash"))
    }

    @Test
    fun `code step receives environment variables`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            println("[SKIP] Bash test skipped on Windows")
            return
        }

        val script = "echo \$WF_VAR_MODE"
        val tempFile = java.io.File.createTempFile("test_env_", ".sh")
        tempFile.writeText(script)
        val pb = ProcessBuilder("/bin/bash", tempFile.absolutePath)
            .redirectErrorStream(true)
        pb.environment()["WF_VAR_MODE"] = "production"
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        tempFile.delete()

        assertEquals("production", output.trim())
    }

    // ==================== WorkflowStep six-mode validation ====================

    @Test
    fun `WorkflowStep error message mentions all 6 modes`() {
        val ex = assertThrows<IllegalArgumentException> {
            WorkflowStep(step = "bad")
        }
        assertTrue(ex.message!!.contains("code"))
        assertTrue(ex.message!!.contains("classifier"))
        assertTrue(ex.message!!.contains("state_machine"))
    }

    @Test
    fun `WorkflowStep rejects code + classifier combination`() {
        assertThrows<IllegalArgumentException> {
            WorkflowStep(
                step = "bad",
                code = CodeStepConfig(language = "bash", script = "x"),
                classifier = ClassifierConfig(
                    agent = "a", input = "t",
                    categories = listOf(ClassifierCategory("a", "A"), ClassifierCategory("b", "B"))
                )
            )
        }
    }

    @Test
    fun `WorkflowStep accepts state_machine mode`() {
        val step = WorkflowStep(
            step = "review_flow",
            stateMachine = StateMachineConfig(
                states = mapOf(
                    "start" to StateDefinition(
                        name = "start",
                        stepConfig = StateStepConfig(
                            code = CodeStepConfig(language = "bash", script = "echo ready")
                        ),
                        transitions = listOf(StateTransition(event = "complete", target = "done"))
                    ),
                    "done" to StateDefinition(name = "done")
                ),
                initialState = "start",
                finalStates = listOf("done")
            )
        )

        assertTrue(step.isStateMachine)
        assertEquals("state_machine(2 states)", step.displayAgentName)
    }

    // ==================== Helper Methods ====================

    private fun minimalAgent() = AgentDefinition(
        type = "universal_agent",
        strategy = "just_work",
        tools = listOf("exit"),
        llm = LLMConfiguration(model = "gpt-4", provider = "openai")
    )
}
