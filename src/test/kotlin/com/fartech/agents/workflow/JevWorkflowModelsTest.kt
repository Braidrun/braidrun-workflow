package com.fartech.agents.workflow

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YAML model / parser / analyzer coverage for `classifier.jev` and `repeat_until.jev`
 * (spec §2, §2.3, §4.2 naming).
 */
class JevWorkflowModelsTest {

    // ==================== Fixtures ====================

    private val triageYaml = """
        name: jev-triage
        version: "1.0"
        agents: {}
        workflow:
          - step: triage
            classifier:
              input: "{{var:ticket_text}}"
              instructions: "Which team should handle this support ticket for {{var:customer_name}}?"
              categories:
                - {name: billing,   description: "Payments, invoices, refunds"}
                - {name: technical, description: "Bugs, outages, integrations"}
                - {name: general,   description: "Anything else"}
              output_variable: team
              default_category: general
              jev:
                model: jev-latest
                min_confidence: 0.6
                questions:
                  - id: is_urgent
                    type: noul
                    instructions: "Does the customer say the problem is time-sensitive?"
                    yes_description: "Explicitly time-sensitive"
                    no_description: "No urgency expressed"
                    threshold: 0.7
                  - id: frustration
                    type: score
                    instructions: "How frustrated does {{var:customer_name}} appear?"
                    levels: ["Calm", "Frustrated but civil", "Very angry"]
                  - id: channel
                    type: choice
                    instructions: "Which channel should we reply on?"
                    options:
                      - {name: email, description: "Reply by email"}
                      - {name: phone, description: "Call the customer back"}
                    default_option: email
                    min_confidence: 0.5
                composites:
                  - name: priority
                    weights: {frustration: 0.4, is_urgent: 0.6}
    """.trimIndent()

    private val qualityLoopYaml = """
        name: jev-quality-loop
        version: "1.0"
        agents:
          writer:
            type: universal_agent
            strategy: just_work
            tools: [exit]
            llm:
              model: gpt-4
              provider: openai
        workflow:
          - step: draft
            agent: writer
            input: "Write the announcement. Reviewer feedback: {{steps.draft:evaluate.output}}"
            repeat_until:
              condition: "quality >= 0.75"
              max_iterations: 3
              jev:
                model: jev-latest
                state: "{{steps.draft.output}} for {{var:audience}}"
                questions:
                  - {id: accuracy, type: score, instructions: "How factually accurate is the draft about {{var:product}}?", levels: ["Clear errors", "Minor imprecision", "Accurate"]}
                  - {id: on_brand, type: noul, instructions: "Does the draft follow a friendly, plain-language tone?"}
                composites:
                  - {name: quality, weights: {accuracy: 0.7, on_brand: 0.3}}
    """.trimIndent()

    private fun categories(count: Int = 2) = (1..count).map { ClassifierCategory("c$it", "Category $it") }

    private fun noul(id: String, threshold: Double? = null) =
        JevQuestionConfig(id = id, type = JevQuestionType.NOUL, instructions = "Is it $id?", threshold = threshold)

    private fun score(id: String, levels: List<String> = listOf("low", "mid", "high")) =
        JevQuestionConfig(id = id, type = JevQuestionType.SCORE, instructions = "Rate $id", levels = levels)

    private fun choice(id: String, vararg names: String, defaultOption: String? = null, minConfidence: Double? = null) =
        JevQuestionConfig(
            id = id,
            type = JevQuestionType.CHOICE,
            instructions = "Pick $id",
            options = names.map { ClassifierCategory(it, "Option $it") },
            defaultOption = defaultOption,
            minConfidence = minConfidence
        )

    private fun jevClassifier(
        jev: ClassifierJevConfig = ClassifierJevConfig(),
        outputVariable: String = "team",
        categories: List<ClassifierCategory> = categories(),
        agent: String? = null,
        instructions: String? = null
    ) = ClassifierConfig(
        agent = agent,
        input = "{{var:text}}",
        categories = categories,
        outputVariable = outputVariable,
        instructions = instructions,
        jev = jev
    )

    private fun assertInvalid(expectedMessagePart: String, block: () -> Unit) {
        val error = assertThrows<IllegalArgumentException> { block() }
        assertContains(error.message.orEmpty(), expectedMessagePart)
    }

    private fun assertYamlInvalid(yaml: String, expectedMessagePart: String) {
        val error = assertThrows<WorkflowValidationException> { WorkflowParser.parseYaml(yaml) }
        assertContains(error.message.orEmpty(), expectedMessagePart)
    }

    // ==================== classifier.jev parsing ====================

    @Nested
    inner class ClassifierJevParsing {

        @Test
        fun `parses the full classifier jev example with agents empty`() {
            val workflow = WorkflowParser.parseYaml(triageYaml)
            val step = workflow.workflow.single()
            val classifier = assertNotNull(step.classifier)

            assertTrue(classifier.isJev)
            assertNull(classifier.agent)
            assertEquals("Which team should handle this support ticket for {{var:customer_name}}?", classifier.instructions)
            val jev = assertNotNull(classifier.jev)
            assertEquals("jev-latest", jev.model)
            assertEquals(0.6, jev.minConfidence)
            assertEquals(listOf("is_urgent", "frustration", "channel"), jev.questions.map { it.id })
            assertEquals(
                listOf(JevQuestionType.NOUL, JevQuestionType.SCORE, JevQuestionType.CHOICE),
                jev.questions.map { it.type }
            )
            val urgent = jev.questions[0]
            assertEquals(0.7, urgent.threshold)
            assertEquals(0.7, urgent.effectiveThreshold)
            assertEquals("Explicitly time-sensitive", urgent.yesDescription)
            assertEquals(listOf("Calm", "Frustrated but civil", "Very angry"), jev.questions[1].levels)
            assertEquals("email", jev.questions[2].defaultOption)
            assertEquals(0.5, jev.questions[2].minConfidence)
            assertEquals(mapOf("frustration" to 0.4, "is_urgent" to 0.6), jev.composites.single().weights)

            assertEquals(emptyList(), step.referencedAgents)
            assertEquals("classifier(jev:jev-latest)", step.displayAgentName)
        }

        @Test
        fun `empty jev block selects the jev engine with defaults`() {
            val workflow = WorkflowParser.parseYaml(
                """
                name: minimal-jev
                agents: {}
                workflow:
                  - step: route
                    classifier:
                      input: "{{var:text}}"
                      categories:
                        - {name: a, description: "A"}
                        - {name: b, description: "B"}
                      jev: {}
                """.trimIndent()
            )
            val step = workflow.workflow.single()
            val classifier = assertNotNull(step.classifier)
            assertTrue(classifier.isJev)
            val jev = assertNotNull(classifier.jev)
            assertNull(jev.model)
            assertNull(jev.minConfidence)
            assertEquals(emptyList(), jev.questions)
            assertEquals(emptyList(), jev.composites)
            assertEquals("classifier(jev)", step.displayAgentName)
            assertEquals(
                listOf("classification", "classification_confidence", "classification_probabilities"),
                classifier.jevWrittenVariables()
            )
        }

        @Test
        fun `agent and jev together are rejected`() {
            assertYamlInvalid(
                """
                name: both
                agents:
                  clf:
                    type: universal_agent
                    llm: {model: gpt-4, provider: openai}
                workflow:
                  - step: route
                    classifier:
                      agent: clf
                      input: "x"
                      categories:
                        - {name: a, description: "A"}
                        - {name: b, description: "B"}
                      jev: {}
                """.trimIndent(),
                "'agent' and 'jev' are mutually exclusive"
            )
        }

        @Test
        fun `classifier without agent and without jev keeps the existing message`() {
            assertInvalid("classifier agent cannot be blank") {
                ClassifierConfig(input = "x", categories = categories())
            }
            assertInvalid("classifier agent cannot be blank") {
                ClassifierConfig(agent = "  ", input = "x", categories = categories())
            }
        }

        @Test
        fun `blank agent with jev is accepted as no agent`() {
            val config = jevClassifier(agent = "")
            assertTrue(config.isJev)
            assertEquals(emptyList(), WorkflowStep(step = "s", classifier = config).referencedAgents)
        }

        @Test
        fun `jev classifier supports at most 255 categories while the agent engine keeps no cap`() {
            jevClassifier(categories = categories(255))
            assertInvalid("at most 255 categories") { jevClassifier(categories = categories(256)) }
            // Agent engine: unchanged (no upper bound).
            ClassifierConfig(agent = "clf", input = "x", categories = categories(256))
        }

        @Test
        fun `instructions must be non-blank when set`() {
            assertInvalid("instructions cannot be blank") { jevClassifier(instructions = "  ") }
            assertInvalid("instructions cannot be blank") {
                ClassifierConfig(agent = "clf", input = "x", categories = categories(), instructions = "")
            }
            assertEquals("Route it", ClassifierConfig(agent = "clf", input = "x", categories = categories(), instructions = "Route it").instructions)
        }

        @Test
        fun `jev model and min_confidence are validated`() {
            assertInvalid("model must be a non-blank model id") { ClassifierJevConfig(model = " ") }
            assertInvalid("model must be a non-blank model id") { ClassifierJevConfig(model = "jev latest") }
            assertInvalid("min_confidence must be between 0 and 1") { ClassifierJevConfig(minConfidence = 1.2) }
            assertInvalid("min_confidence must be between 0 and 1") { ClassifierJevConfig(minConfidence = -0.1) }
            assertInvalid("min_confidence must be between 0 and 1") { ClassifierJevConfig(minConfidence = Double.NaN) }
            assertEquals(0.0, ClassifierJevConfig(minConfidence = 0.0).minConfidence)
            assertEquals(1.0, ClassifierJevConfig(minConfidence = 1.0).minConfidence)
        }

        @Test
        fun `parser rejects duplicate category names for jev classifiers`() {
            assertYamlInvalid(
                """
                name: dup
                agents: {}
                workflow:
                  - step: route
                    classifier:
                      input: "x"
                      categories:
                        - {name: a, description: "A"}
                        - {name: a, description: "Again"}
                      jev: {}
                """.trimIndent(),
                "duplicate category names: a"
            )
        }

        @Test
        fun `agent classifier still requires a defined agent`() {
            val error = assertThrows<WorkflowValidationException> {
                WorkflowParser.parseYaml(
                    """
                    name: missing-agent
                    agents:
                      other:
                        type: universal_agent
                        llm: {model: gpt-4, provider: openai}
                    workflow:
                      - step: route
                        classifier:
                          agent: clf
                          input: "x"
                          categories:
                            - {name: a, description: "A"}
                            - {name: b, description: "B"}
                    """.trimIndent()
                )
            }
            assertContains(error.message.orEmpty(), "undefined agent 'clf'")
        }
    }

    // ==================== Question / composite rules ====================

    @Nested
    inner class QuestionRules {

        @Test
        fun `question id must match the identifier regex`() {
            listOf("1bad", "has-dash", "has space", "", "é").forEach { id ->
                assertInvalid("must match") { noul(id) }
            }
            listOf("_x", "A1", "is_urgent", "x_2_y").forEach { id -> assertEquals(id, noul(id).id) }
        }

        @Test
        fun `question instructions must be non-blank`() {
            assertInvalid("instructions cannot be blank") {
                JevQuestionConfig(id = "q", type = JevQuestionType.NOUL, instructions = " ")
            }
        }

        @Test
        fun `choice question rules`() {
            assertInvalid("options are required") {
                JevQuestionConfig(id = "q", type = JevQuestionType.CHOICE, instructions = "Pick")
            }
            assertInvalid("must have 2 to 255 options") { choice("q", "only") }
            assertInvalid("must have 2 to 255 options") {
                JevQuestionConfig(
                    id = "q", type = JevQuestionType.CHOICE, instructions = "Pick",
                    options = (1..256).map { ClassifierCategory("o$it", "O$it") }
                )
            }
            assertInvalid("duplicate option names: a") { choice("q", "a", "a") }
            assertInvalid("default_option 'z' must be one of the options") { choice("q", "a", "b", defaultOption = "z") }
            assertInvalid("min_confidence must be between 0 and 1") { choice("q", "a", "b", minConfidence = 2.0) }
            assertInvalid("not allowed for type choice") {
                JevQuestionConfig(
                    id = "q", type = JevQuestionType.CHOICE, instructions = "Pick",
                    options = listOf(ClassifierCategory("a", "A"), ClassifierCategory("b", "B")),
                    levels = listOf("x", "y")
                )
            }
            assertInvalid("not allowed for type choice") {
                JevQuestionConfig(
                    id = "q", type = JevQuestionType.CHOICE, instructions = "Pick",
                    options = listOf(ClassifierCategory("a", "A"), ClassifierCategory("b", "B")),
                    threshold = 0.5
                )
            }
            val ok = choice("q", "a", "b", defaultOption = "b", minConfidence = 0.4)
            assertEquals("b", ok.defaultOption)
        }

        @Test
        fun `score question rules`() {
            assertInvalid("levels are required") {
                JevQuestionConfig(id = "q", type = JevQuestionType.SCORE, instructions = "Rate")
            }
            assertInvalid("must have 2 to 10 levels") { score("q", listOf("only")) }
            assertInvalid("must have 2 to 10 levels") { score("q", (0..10).map { "l$it" }) }
            assertInvalid("levels cannot be blank") { score("q", listOf("low", " ")) }
            assertInvalid("not allowed for type score") {
                JevQuestionConfig(
                    id = "q", type = JevQuestionType.SCORE, instructions = "Rate",
                    levels = listOf("a", "b"), minConfidence = 0.5
                )
            }
            assertInvalid("not allowed for type score") {
                JevQuestionConfig(
                    id = "q", type = JevQuestionType.SCORE, instructions = "Rate",
                    levels = listOf("a", "b"), yesDescription = "yes"
                )
            }
            assertEquals(10, score("q", (0..9).map { "l$it" }).levels?.size)
        }

        @Test
        fun `noul question rules`() {
            assertInvalid("threshold must be between 0 and 1") { noul("q", threshold = 1.5) }
            assertInvalid("yes_description cannot be blank") {
                JevQuestionConfig(id = "q", type = JevQuestionType.NOUL, instructions = "Yes?", yesDescription = "")
            }
            assertInvalid("no_description cannot be blank") {
                JevQuestionConfig(id = "q", type = JevQuestionType.NOUL, instructions = "Yes?", noDescription = " ")
            }
            assertInvalid("not allowed for type noul") {
                JevQuestionConfig(id = "q", type = JevQuestionType.NOUL, instructions = "Yes?", levels = listOf("a", "b"))
            }
            assertInvalid("not allowed for type noul") {
                JevQuestionConfig(
                    id = "q", type = JevQuestionType.NOUL, instructions = "Yes?",
                    options = listOf(ClassifierCategory("a", "A"), ClassifierCategory("b", "B"))
                )
            }
            assertEquals(DEFAULT_JEV_NOUL_THRESHOLD, noul("q").effectiveThreshold)
        }

        @Test
        fun `composite rules`() {
            assertInvalid("jev composite name '9x' is invalid") { JevComposite("9x", mapOf("a" to 1.0)) }
            assertInvalid("must have at least one weight") { JevComposite("c", emptyMap()) }
            assertInvalid("must be a finite number > 0") { JevComposite("c", mapOf("a" to 0.0)) }
            assertInvalid("must be a finite number > 0") { JevComposite("c", mapOf("a" to -1.0)) }
            assertInvalid("must be a finite number > 0") { JevComposite("c", mapOf("a" to Double.POSITIVE_INFINITY)) }

            assertInvalid("not a score or noul question") {
                ClassifierJevConfig(
                    questions = listOf(choice("channel", "a", "b")),
                    composites = listOf(JevComposite("c", mapOf("channel" to 1.0)))
                )
            }
            assertInvalid("not a score or noul question") {
                RepeatUntilJevConfig(
                    questions = listOf(noul("ok")),
                    composites = listOf(JevComposite("c", mapOf("missing" to 1.0)))
                )
            }
            assertInvalid("duplicate composite names: c") {
                RepeatUntilJevConfig(
                    questions = listOf(noul("ok")),
                    composites = listOf(JevComposite("c", mapOf("ok" to 1.0)), JevComposite("c", mapOf("ok" to 2.0)))
                )
            }
        }

        @Test
        fun `question ids must be unique`() {
            assertInvalid("duplicate question ids: q") {
                ClassifierJevConfig(questions = listOf(noul("q"), score("q")))
            }
        }

        @Test
        fun `derived variable collisions are rejected`() {
            // Question id equals classifier output_variable (would also collide in the request's question map).
            assertInvalid("'team'") { jevClassifier(jev = ClassifierJevConfig(questions = listOf(noul("team")))) }
            // Question id equals a derived classifier variable.
            assertInvalid("'team_confidence'") {
                jevClassifier(jev = ClassifierJevConfig(questions = listOf(noul("team_confidence"))))
            }
            // Question id equals another question's derived variable.
            assertInvalid("'frustration_level'") {
                ClassifierJevConfig(questions = listOf(score("frustration"), noul("frustration_level")))
            }
            // Composite name equals a question id / derived name.
            assertInvalid("'ok'") {
                RepeatUntilJevConfig(questions = listOf(noul("ok")), composites = listOf(JevComposite("ok", mapOf("ok" to 1.0))))
            }
            assertInvalid("'ok_yes'") {
                RepeatUntilJevConfig(
                    questions = listOf(noul("ok")),
                    composites = listOf(JevComposite("ok_yes", mapOf("ok" to 1.0)))
                )
            }
        }

        @Test
        fun `yaml level question errors surface as validation errors`() {
            val yaml = triageYaml.replace("id: frustration", "id: 2frustration")
            assertYamlInvalid(yaml, "jev question id '2frustration' is invalid")
            val unknownType = triageYaml.replace("type: noul", "type: boolean")
            assertThrows<WorkflowValidationException> { WorkflowParser.parseYaml(unknownType) }
        }
    }

    // ==================== repeat_until.jev ====================

    @Nested
    inner class RepeatUntilJev {

        @Test
        fun `parses the repeat_until jev example`() {
            val workflow = WorkflowParser.parseYaml(qualityLoopYaml)
            val repeat = assertNotNull(workflow.workflow.single().repeatUntil)
            val jev = assertNotNull(repeat.jev)
            assertNull(repeat.evaluateAgent)
            assertEquals("quality >= 0.75", repeat.condition)
            assertEquals("{{steps.draft.output}} for {{var:audience}}", jev.state)
            assertEquals(listOf("accuracy", "on_brand"), jev.questions.map { it.id })
            assertEquals(
                listOf(
                    "accuracy", "accuracy_level", "accuracy_normalized", "accuracy_confidence", "accuracy_probabilities",
                    "on_brand", "on_brand_yes",
                    "quality"
                ),
                jev.writtenVariables()
            )
            assertEquals("{{steps.draft.output}} for {{var:audience}}", jev.stateTemplateFor("draft"))
            assertEquals("{{steps.x.output}}", RepeatUntilJevConfig(questions = listOf(noul("q"))).stateTemplateFor("x"))
        }

        @Test
        fun `parser summary marks jev evaluation`() {
            val summary = WorkflowParser.getWorkflowSummary(WorkflowParser.parseYaml(qualityLoopYaml))
            assertContains(summary, "repeat_until: quality >= 0.75 (max 3 iterations, Jev evaluation)")
            val triageSummary = WorkflowParser.getWorkflowSummary(WorkflowParser.parseYaml(triageYaml))
            assertContains(triageSummary, "- triage (classifier(jev:jev-latest))")
        }

        @Test
        fun `jev is mutually exclusive with evaluate agent and extraction`() {
            val jev = RepeatUntilJevConfig(questions = listOf(noul("ok")))
            val message = "repeat_until: 'jev' cannot be combined with evaluate_agent/evaluate_prompt/extract_pattern/extract_variable"
            assertInvalid(message) { RepeatUntilConfig(condition = "ok_yes == true", evaluateAgent = "rev", jev = jev) }
            assertInvalid(message) { RepeatUntilConfig(condition = "ok_yes == true", evaluatePrompt = "rate", jev = jev) }
            assertInvalid(message) { RepeatUntilConfig(condition = "ok_yes == true", extractPattern = "x=(\\d)", jev = jev) }
            assertInvalid(message) { RepeatUntilConfig(condition = "ok_yes == true", extractVariable = "x", jev = jev) }
            // Blank strings are treated as absent (editors may send "").
            RepeatUntilConfig(condition = "ok_yes == true", evaluatePrompt = "", jev = jev)
        }

        @Test
        fun `questions are required and state must be non-blank`() {
            assertInvalid("at least one question") { RepeatUntilJevConfig(questions = emptyList()) }
            assertInvalid("state cannot be blank") { RepeatUntilJevConfig(state = " ", questions = listOf(noul("q"))) }
            assertInvalid("model must be a non-blank model id") { RepeatUntilJevConfig(model = "", questions = listOf(noul("q"))) }
        }

        @Test
        fun `yaml with jev and evaluate_agent is rejected`() {
            val yaml = qualityLoopYaml.replace("max_iterations: 3", "max_iterations: 3\n      evaluate_agent: writer")
            assertYamlInvalid(yaml, "'jev' cannot be combined")
        }

        @Test
        fun `existing repeat_until restrictions are unchanged`() {
            // code + repeat_until stays rejected even with jev.
            assertYamlInvalid(
                """
                name: code-loop
                agents: {}
                workflow:
                  - step: gen
                    code: {language: bash, script: "echo hi"}
                    repeat_until:
                      condition: "ok_yes == true"
                      jev:
                        questions:
                          - {id: ok, type: noul, instructions: "OK?"}
                """.trimIndent(),
                "repeat_until cannot be used with code steps"
            )
            // classifier + repeat_until stays allowed (no new restriction).
            val workflow = WorkflowParser.parseYaml(
                """
                name: classifier-loop
                agents: {}
                workflow:
                  - step: route
                    classifier:
                      input: "{{var:text}}"
                      categories:
                        - {name: a, description: "A"}
                        - {name: b, description: "B"}
                      jev: {}
                    repeat_until:
                      condition: "sure_yes == true"
                      max_iterations: 2
                      jev:
                        state: "{{steps.route.output}}"
                        questions:
                          - {id: sure, type: noul, instructions: "Is the routing unambiguous?"}
                """.trimIndent()
            )
            assertNotNull(workflow.workflow.single().repeatUntil?.jev)
        }
    }

    // ==================== toYaml round trip ====================

    @Test
    fun `toYaml round-trips jev classifier and repeat_until configs`() {
        listOf(triageYaml, qualityLoopYaml).forEach { source ->
            val parsed = WorkflowParser.parseYaml(source)
            val exported = WorkflowParser.toYaml(parsed)
            val reparsed = WorkflowParser.parseYaml(exported)
            assertEquals(parsed.workflow, reparsed.workflow)
            assertContains(exported, "jev:")
        }
    }

    @Test
    fun `toYaml keeps an agent classifier an agent classifier`() {
        val source = """
            name: agent-classifier
            agents:
              clf:
                type: universal_agent
                llm: {model: gpt-4, provider: openai}
            workflow:
              - step: route
                classifier:
                  agent: clf
                  input: "x"
                  categories:
                    - {name: a, description: "A"}
                    - {name: b, description: "B"}
        """.trimIndent()
        val parsed = WorkflowParser.parseYaml(source)
        val reparsed = WorkflowParser.parseYaml(WorkflowParser.toYaml(parsed))
        val classifier = assertNotNull(reparsed.workflow.single().classifier)
        assertFalse(classifier.isJev)
        assertEquals("clf", classifier.agent)
        assertEquals("classifier(clf)", reparsed.workflow.single().displayAgentName)
        assertEquals(listOf("clf"), reparsed.workflow.single().referencedAgents)
        assertEquals(emptyList(), classifier.jevWrittenVariables())
    }

    // ==================== JevVariableNames ====================

    @Nested
    inner class VariableNames {

        @Test
        fun `forQuestions follows the normative order`() {
            val names = JevVariableNames.forQuestions(
                listOf("is_urgent" to "noul", "frustration" to "score", "channel" to "choice"),
                listOf("priority")
            )
            assertEquals(
                listOf(
                    "is_urgent", "is_urgent_yes",
                    "frustration", "frustration_level", "frustration_normalized", "frustration_confidence", "frustration_probabilities",
                    "channel", "channel_confidence", "channel_probabilities",
                    "priority"
                ),
                names
            )
        }

        @Test
        fun `unknown or blank types still yield the base id`() {
            assertEquals(
                listOf("a", "b", "c", "c_yes", "comp"),
                JevVariableNames.forQuestions(listOf("a" to "", "b" to "boolean", "c" to "noul"), listOf("comp"))
            )
            assertEquals(listOf("x"), JevVariableNames.forQuestions(listOf("x" to "Choice"), emptyList()))
        }

        @Test
        fun `forClassifier includes the output variable first`() {
            assertEquals(
                listOf("team", "team_confidence", "team_probabilities", "is_urgent", "is_urgent_yes", "priority"),
                JevVariableNames.forClassifier("team", listOf("is_urgent" to "noul"), listOf("priority"))
            )
        }

        @Test
        fun `duplicates reports each duplicated name once in first-occurrence order`() {
            assertEquals(listOf("b", "a"), JevVariableNames.duplicates(listOf("a", "b", "b", "a", "b", "c")))
            assertEquals(emptyList(), JevVariableNames.duplicates(listOf("a", "b")))
        }

        @Test
        fun `engine wrappers delegate to the primitives helper`() {
            val classifier = WorkflowParser.parseYaml(triageYaml).workflow.single().classifier!!
            assertEquals(
                JevVariableNames.forClassifier(
                    "team",
                    listOf("is_urgent" to "noul", "frustration" to "score", "channel" to "choice"),
                    listOf("priority")
                ),
                classifier.jevWrittenVariables()
            )
            assertEquals(classifier.jevWrittenVariables().drop(3), classifier.jev!!.questionVariables())
        }
    }

    // ==================== state machine ====================

    @Nested
    inner class StateMachineStates {

        private fun stateMachineYaml(secondCategory: String) = """
            name: sm-jev
            agents: {}
            workflow:
              - step: flow
                state_machine:
                  initial_state: route
                  final_states: [done]
                  states:
                    route:
                      name: route
                      step:
                        classifier:
                          input: "{{var:text}}"
                          categories:
                            - {name: a, description: "A"}
                            - {name: $secondCategory, description: "B"}
                          jev: {min_confidence: 0.5}
                      transitions:
                        - {event: complete, target: done}
                    done:
                      name: done
        """.trimIndent()

        @Test
        fun `jev classifier state needs no agent`() {
            val workflow = WorkflowParser.parseYaml(stateMachineYaml("b"))
            val stateStep = workflow.workflow.single().stateMachine!!.states.getValue("route").stepConfig!!
            assertTrue(stateStep.classifier!!.isJev)
            assertEquals(0.5, stateStep.classifier.jev!!.minConfidence)
            assertEquals(emptyList(), stateStep.referencedAgents)
            assertEquals(emptyList(), workflow.workflow.single().referencedAgents)
        }

        @Test
        fun `state classifier duplicate categories are now rejected`() {
            assertYamlInvalid(stateMachineYaml("a"), "state 'route' classifier has duplicate category names: a")
        }
    }

    // ==================== WorkflowAnalyzer ====================

    @Nested
    inner class Analyzer {

        @Test
        fun `reads include instructions, question instructions and repeat_until state`() {
            val triage = WorkflowParser.parseYaml(triageYaml)
            val triageRefs = WorkflowAnalyzer.collectReferencedVariables(triage)
            assertTrue("ticket_text" in triageRefs)
            assertTrue("customer_name" in triageRefs)

            val loop = WorkflowParser.parseYaml(qualityLoopYaml)
            val loopRefs = WorkflowAnalyzer.collectReferencedVariables(loop)
            assertTrue("audience" in loopRefs, loopRefs.toString())
            assertTrue("product" in loopRefs, loopRefs.toString())
            assertTrue("draft" in WorkflowAnalyzer.collectReferencedStepOutputs(loop))
        }

        @Test
        fun `writes include jev derived variables`() {
            val triageWrites = WorkflowAnalyzer.collectWrittenVariables(WorkflowParser.parseYaml(triageYaml))
            assertEquals(
                WorkflowParser.parseYaml(triageYaml).workflow.single().classifier!!.jevWrittenVariables().toSet(),
                triageWrites
            )
            val loopWrites = WorkflowAnalyzer.collectWrittenVariables(WorkflowParser.parseYaml(qualityLoopYaml))
            assertTrue(loopWrites.containsAll(listOf("accuracy", "accuracy_normalized", "on_brand_yes", "quality")))
        }

        @Test
        fun `read-write analysis sees jev variables across the boundary`() {
            val triage = WorkflowParser.parseYaml(triageYaml)
            val workflow = triage.copy(
                agents = mapOf(
                    "writer" to AgentDefinition(
                        type = "universal_agent",
                        llm = LLMConfiguration(model = "gpt-4", provider = "openai")
                    )
                ),
                workflow = triage.workflow + WorkflowStep(
                    step = "notify",
                    agent = "writer",
                    input = "Team {{var:team}} (confidence {{var:team_confidence}}), priority {{var:priority}}",
                    dependsOn = listOf("triage")
                )
            )
            val usage = WorkflowAnalyzer.analyzeVariableReadWrite(workflow, setOf("triage"))
            assertTrue("customer_name" in usage.readOnly, usage.toString())
            assertTrue("ticket_text" in usage.readOnly, usage.toString())
            assertTrue(usage.writtenAndExposed.containsAll(listOf("team", "team_confidence", "priority")), usage.toString())
            assertTrue("is_urgent_yes" in usage.writtenInternalOnly, usage.toString())
        }
    }
}
