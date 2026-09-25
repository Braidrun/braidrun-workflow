package com.fartech.agents.workflow

import com.fartech.agents.jev.JevAnswer
import com.fartech.agents.jev.JevApiException
import com.fartech.agents.jev.JevChoiceAnswer
import com.fartech.agents.jev.JevChoiceQuestion
import com.fartech.agents.jev.JevClient
import com.fartech.agents.jev.JevClientFactory
import com.fartech.agents.jev.JevClientSettings
import com.fartech.agents.jev.JevCredentials
import com.fartech.agents.jev.JevNoulAnswer
import com.fartech.agents.jev.JevRequest
import com.fartech.agents.jev.JevResponse
import com.fartech.agents.jev.JevScoreAnswer
import com.fartech.agents.jev.JevUsage
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keeps the public Jev examples (`examples/workflows/jev-*.yaml`) honest: they must parse,
 * validate, and route the way their comments and docs/WORKFLOW_GUIDE.md describe when run
 * against a fake Jev client (no network, no chat model).
 */
class JevExamplesTest {

    private val triagePath = "examples/workflows/jev-support-triage.yaml"
    private val qualityLoopPath = "examples/workflows/jev-quality-loop.yaml"

    private class FakeJevClientFactory(
        private val handler: (request: JevRequest, callIndex: Int) -> JevResponse
    ) : JevClientFactory {
        val requests: MutableList<JevRequest> = Collections.synchronizedList(mutableListOf())

        override fun create(apiKey: String, settings: JevClientSettings, httpClient: HttpClient): JevClient =
            object : JevClient {
                override suspend fun systemOne(request: JevRequest): JevResponse {
                    val index = requests.size
                    requests += request
                    return handler(request, index)
                }
            }
    }

    @BeforeEach
    fun setUp() {
        WorkflowMonitor.clear()
    }

    @AfterEach
    fun tearDown() {
        WorkflowMonitor.clear()
    }

    private fun executor(factory: JevClientFactory) = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = emptyList(),
        jevCredentials = JevCredentials("sk-typesafe-example-test"),
        jevClientFactory = factory,
        jevEnvKeyFallback = false
    )

    private fun run(workflow: WorkflowDefinition, factory: JevClientFactory): WorkflowExecutionResult = runBlocking {
        executor(factory).execute(workflow, emptyMap(), "jev-example-" + UUID.randomUUID())
    }

    private fun response(answers: Map<String, JevAnswer>) =
        JevResponse(model = "jev-1.13.0", answers = answers, usage = JevUsage(310, 30))

    private fun triageAnswers(team: JevChoiceAnswer, isUrgent: Double = 0.92, frustration: Double = 1.2): Map<String, JevAnswer> =
        linkedMapOf(
            "team" to team,
            "is_urgent" to JevNoulAnswer(isUrgent),
            "frustration" to JevScoreAnswer(
                score = frustration,
                probabilities = mapOf("0" to 0.05, "1" to 0.7, "2" to 0.25),
                confidence = 0.88
            ),
            "channel" to JevChoiceAnswer(
                choice = "email",
                probabilities = mapOf("email" to 0.7, "phone" to 0.3),
                confidence = 0.7
            )
        )

    private fun ranSteps(result: WorkflowExecutionResult): Set<String> = result.stepResults.keys

    // ==================== jev-support-triage.yaml ====================

    @Test
    fun `support triage example is a jev-only workflow whose conditions read jev variables`() {
        val workflow = WorkflowParser.parseFile(triagePath)
        WorkflowParser.validateWorkflow(workflow)

        assertTrue(workflow.agents.isEmpty(), "a Jev-only workflow needs no chat agent")
        assertTrue(workflow.workflow.all { it.referencedAgents.isEmpty() })

        val classifier = assertNotNull(workflow.workflow.first { it.step == "triage" }.classifier)
        assertTrue(classifier.isJev)
        assertNull(classifier.agent)
        assertEquals("classifier(jev)", workflow.workflow.first().displayAgentName)
        assertEquals(
            listOf(
                "team", "team_confidence", "team_probabilities",
                "is_urgent", "is_urgent_yes",
                "frustration", "frustration_level", "frustration_normalized", "frustration_confidence", "frustration_probabilities",
                "channel", "channel_confidence", "channel_probabilities",
                "priority"
            ),
            classifier.jevWrittenVariables()
        )

        // Every variable the branch conditions compare is written by the Jev classifier.
        val written = WorkflowAnalyzer.collectWrittenVariables(workflow)
        listOf("team", "team_confidence", "is_urgent_yes", "priority").forEach { name ->
            assertTrue(name in written, "'$name' should be a written variable")
            assertTrue(workflow.workflow.any { it.condition?.contains(name) == true }, "'$name' should drive a condition")
        }
    }

    @Test
    fun `support triage example routes a confident billing ticket and escalates it`() {
        val factory = FakeJevClientFactory { _, _ ->
            response(
                triageAnswers(
                    JevChoiceAnswer(
                        choice = "billing",
                        probabilities = mapOf("billing" to 0.9, "technical" to 0.04, "general" to 0.06),
                        confidence = 0.86
                    )
                )
            )
        }

        val result = run(WorkflowParser.parseFile(triagePath), factory)

        assertTrue(result.success, result.error)
        val request = factory.requests.single()
        assertEquals(listOf("team", "is_urgent", "frustration", "channel"), request.questions.keys.toList())
        assertContains(request.state.jsonPrimitive.content, "charged my card twice")
        assertEquals(
            "Which team should handle this support ticket?",
            assertIs<JevChoiceQuestion>(request.questions.getValue("team")).instructions.jsonPrimitive.content
        )

        assertEquals("billing", result.variables["team"])
        assertEquals("0.86", result.variables["team_confidence"])
        assertEquals("true", result.variables["is_urgent_yes"])
        // priority = (0.4 * 1.2/2 + 0.6 * 0.92) / 1.0
        assertEquals("0.792", result.variables["priority"])

        assertEquals(setOf("triage", "route_billing", "escalate", "summary"), ranSteps(result))
        assertEquals("Queue: billing (confidence 0.86), reply by email", result.stepResults.getValue("route_billing").output?.trim())
        assertContains(result.stepResults.getValue("summary").output.orEmpty(), "team=billing confidence=0.86")
        assertContains(
            result.stepResults.getValue("summary").output.orEmpty(),
            """team_probabilities={"billing":0.9,"technical":0.04,"general":0.06}"""
        )
    }

    @Test
    fun `support triage example sends a low-confidence ticket to general and flags it for review`() {
        val factory = FakeJevClientFactory { _, _ ->
            response(
                triageAnswers(
                    JevChoiceAnswer(
                        choice = "technical",
                        probabilities = mapOf("billing" to 0.35, "technical" to 0.45, "general" to 0.2),
                        confidence = 0.45
                    ),
                    isUrgent = 0.2,
                    frustration = 0.4
                )
            )
        }

        val result = run(WorkflowParser.parseFile(triagePath), factory)

        assertTrue(result.success, result.error)
        assertEquals("general", result.variables["team"])
        // The real (low) confidence is kept, which is what needs_review keys on.
        assertEquals("0.45", result.variables["team_confidence"])
        assertEquals(setOf("triage", "route_general", "needs_review", "summary"), ranSteps(result))
        assertEquals("classification: general", result.stepResults.getValue("triage").output)
    }

    @Test
    fun `support triage example falls back to general when jev is unavailable`() {
        val factory = FakeJevClientFactory { _, _ ->
            throw JevApiException(JevApiException.Kind.OVERLOADED, 529, "TypeSafe API is overloaded (HTTP 529) after 4 attempts")
        }

        val result = run(WorkflowParser.parseFile(triagePath), factory)

        assertTrue(result.success, result.error)
        assertEquals("general", result.variables["team"])
        assertEquals("0", result.variables["team_confidence"])
        assertEquals("false", result.variables["is_urgent_yes"])
        assertEquals("", result.variables["priority"])
        // "" never satisfies `priority >= 0.7`, so escalate stays skipped.
        assertEquals(setOf("triage", "route_general", "needs_review", "summary"), ranSteps(result))
    }

    @Test
    fun `support triage example fails instead of guessing when the key is rejected`() {
        val factory = FakeJevClientFactory { _, _ ->
            throw JevApiException(JevApiException.Kind.UNAUTHORIZED, 401, "TypeSafe API rejected the API key (HTTP 401)")
        }

        val result = run(WorkflowParser.parseFile(triagePath), factory)

        assertFalse(result.success)
        assertNull(result.variables["team"])
        assertFalse(result.stepResults.getValue("triage").success)
        assertFalse("route_general" in ranSteps(result))
    }

    // ==================== docs/WORKFLOW_GUIDE.md ====================

    /**
     * The classifier / Jev sections of the guide show workflow snippets; every snippet that is a
     * workflow (`agents:` …) or a step (`- step:` …) must parse and validate as written.
     */
    @Test
    fun `workflow guide classifier and jev snippets parse and validate`() {
        val guide = java.io.File("docs/WORKFLOW_GUIDE.md").readText()
        val section = guide.substring(guide.indexOf("## Classifier Step"))
        val snippets = Regex("```yaml\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(section)
            .map { it.groupValues[1] }
            .filter { it.startsWith("agents:") || it.startsWith("- step:") }
            .toList()
        assertTrue(snippets.size >= 3, "expected the classifier, classifier.jev and repeat_until.jev snippets")

        snippets.forEach { snippet ->
            val yaml = if (snippet.startsWith("agents:")) {
                "name: guide-snippet\n$snippet"
            } else {
                "name: guide-snippet\nagents:\n  writer:\n    preset: writer\nworkflow:\n" +
                    snippet.lines().joinToString("\n") { if (it.isBlank()) it else "  $it" }
            }
            val workflow = WorkflowParser.parseYaml(yaml)
            WorkflowParser.validateWorkflow(workflow)
        }
    }

    // ==================== jev-quality-loop.yaml ====================

    @Test
    fun `quality loop example uses repeat_until jev instead of an evaluate agent`() {
        val workflow = WorkflowParser.parseFile(qualityLoopPath)
        WorkflowParser.validateWorkflow(workflow)

        val draft = workflow.workflow.first { it.step == "draft" }
        assertEquals("writer", draft.agent)
        val repeatUntil = assertNotNull(draft.repeatUntil)
        val jev = assertNotNull(repeatUntil.jev)
        assertNull(repeatUntil.evaluateAgent)
        assertNull(repeatUntil.extractPattern)
        assertEquals("quality >= 0.75", repeatUntil.condition)
        assertEquals(
            listOf(
                "accuracy", "accuracy_level", "accuracy_normalized", "accuracy_confidence", "accuracy_probabilities",
                "on_brand", "on_brand_yes",
                "quality"
            ),
            jev.writtenVariables()
        )
        assertContains(jev.stateTemplateFor("draft"), "{{steps.draft.output}}")
        assertContains(draft.input.orEmpty(), "{{steps.draft:evaluate.output}}")
        assertEquals(setOf("writer"), workflow.workflow.flatMap { it.referencedAgents }.toSet())
        assertTrue(WorkflowAnalyzer.collectWrittenVariables(workflow).containsAll(jev.writtenVariables()))
    }

    /**
     * Swaps the writer agent for a deterministic code step (the executor runs repeat_until on
     * it; only the parser forbids that) so the example's real Jev config, condition and
     * downstream gate run without a chat model.
     */
    private fun qualityLoopWithoutLlm(): WorkflowDefinition {
        val workflow = WorkflowParser.parseFile(qualityLoopPath)
        return workflow.copy(
            agents = emptyMap(),
            workflow = workflow.workflow.map { step ->
                if (step.step == "draft") {
                    step.copy(agent = null, input = null, code = CodeStepConfig(language = "bash", script = "echo 'Retries are here.'"))
                } else {
                    step
                }
            }
        )
    }

    private fun loopAnswers(accuracy: Double, onBrand: Double): Map<String, JevAnswer> = linkedMapOf(
        "accuracy" to JevScoreAnswer(score = accuracy, probabilities = mapOf("0" to 0.1, "1" to 0.3, "2" to 0.6), confidence = 0.8),
        "on_brand" to JevNoulAnswer(onBrand)
    )

    @Test
    fun `quality loop example stops once the composite reaches the bar and skips the gate`() {
        val factory = FakeJevClientFactory { _, callIndex ->
            when (callIndex) {
                0 -> response(loopAnswers(accuracy = 1.0, onBrand = 0.4))
                else -> response(loopAnswers(accuracy = 2.0, onBrand = 0.9))
            }
        }

        val result = run(qualityLoopWithoutLlm(), factory)

        assertTrue(result.success, result.error)
        assertEquals(2, factory.requests.size)
        val state = factory.requests.first().state.jsonPrimitive.content
        assertContains(state, "Release notes:\nScheduled runs now retry failed steps automatically")
        assertContains(state, "Announcement draft:\nRetries are here.")
        assertEquals("0.97", result.variables["quality"])
        assertFalse("quality_gate" in ranSteps(result))
        val report = result.stepResults.getValue("report").output.orEmpty()
        assertContains(report, "quality=0.97 accuracy_level=2 on_brand=true")
        assertContains(report, "Jev evaluation (iteration 2, model jev-1.13.0):")
    }

    @Test
    fun `quality loop example gate fires when the bar is never met or jev is unavailable`() {
        val neverGoodEnough = FakeJevClientFactory { _, _ -> response(loopAnswers(accuracy = 1.0, onBrand = 0.5)) }
        val belowBar = run(qualityLoopWithoutLlm(), neverGoodEnough)
        assertTrue(belowBar.success, belowBar.error)
        assertEquals(3, neverGoodEnough.requests.size, "max_iterations bounds the loop")
        assertEquals("0.5", belowBar.variables["quality"])
        assertContains(belowBar.stepResults.getValue("quality_gate").output.orEmpty(), "Quality bar not met (quality='0.5')")

        WorkflowMonitor.clear()
        val unavailable = FakeJevClientFactory { _, _ ->
            throw JevApiException(JevApiException.Kind.RATE_LIMITED, 429, "TypeSafe API rate limit exceeded (HTTP 429) after 4 attempts")
        }
        val failedEvaluations = run(qualityLoopWithoutLlm(), unavailable)
        assertTrue(failedEvaluations.success, failedEvaluations.error)
        assertEquals(3, unavailable.requests.size, "transient Jev errors do not stop the loop")
        assertEquals("", failedEvaluations.variables["quality"])
        assertContains(failedEvaluations.stepResults.getValue("quality_gate").output.orEmpty(), "Quality bar not met (quality='')")
    }
}
