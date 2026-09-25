package com.fartech.agents.workflow

import com.fartech.agents.jev.JEV_MISSING_CREDENTIALS_MESSAGE
import com.fartech.agents.jev.JevAnswer
import com.fartech.agents.jev.JevApiException
import com.fartech.agents.jev.JevChoiceAnswer
import com.fartech.agents.jev.JevChoiceQuestion
import com.fartech.agents.jev.JevClient
import com.fartech.agents.jev.JevClientFactory
import com.fartech.agents.jev.JevClientSettings
import com.fartech.agents.jev.JevCredentials
import com.fartech.agents.jev.JevJson
import com.fartech.agents.jev.JevNoulAnswer
import com.fartech.agents.jev.JevNoulQuestion
import com.fartech.agents.jev.JevRequest
import com.fartech.agents.jev.JevResponse
import com.fartech.agents.jev.JevScoreAnswer
import com.fartech.agents.jev.JevScoreQuestion
import com.fartech.agents.jev.JevUsage
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
 * Executor-level tests for `classifier.jev` and `repeat_until.jev` with a fake
 * [JevClientFactory] injected through the WorkflowExecutor constructor (spec §4, §8).
 */
class JevWorkflowExecutorTest {

    private val secretKey = "sk-typesafe-test-SECRET-123"

    /** Records every client creation / request and answers with [handler] (call index is 0-based). */
    private class FakeJevClientFactory(
        private val handler: suspend (request: JevRequest, callIndex: Int) -> JevResponse
    ) : JevClientFactory {
        val apiKeys: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val settings: MutableList<JevClientSettings> = Collections.synchronizedList(mutableListOf())
        val requests: MutableList<JevRequest> = Collections.synchronizedList(mutableListOf())

        override fun create(apiKey: String, settings: JevClientSettings, httpClient: HttpClient): JevClient {
            apiKeys += apiKey
            this.settings += settings
            return object : JevClient {
                override suspend fun systemOne(request: JevRequest): JevResponse {
                    val index = requests.size
                    requests += request
                    return handler(request, index)
                }
            }
        }
    }

    private val executionIds = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        WorkflowMonitor.clear()
    }

    @AfterEach
    fun tearDown() {
        WorkflowMonitor.clear()
    }

    // ==================== Fixtures ====================

    private fun executor(
        factory: JevClientFactory,
        credentials: JevCredentials? = JevCredentials(secretKey),
        baseParameters: List<ConfigurationParameter> = emptyList(),
        envKeyFallback: Boolean = false
    ) = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = baseParameters,
        enableMonitoring = true,
        jevCredentials = credentials,
        jevClientFactory = factory,
        jevEnvKeyFallback = envKeyFallback
    )

    private suspend fun WorkflowExecutor.run(
        workflow: WorkflowDefinition,
        initialInput: Map<String, Any> = emptyMap(),
        resumeState: WorkflowResumeState? = null
    ): Pair<String, WorkflowExecutionResult> {
        val executionId = "jev-test-" + UUID.randomUUID()
        executionIds += executionId
        return executionId to execute(workflow, initialInput, executionId, resumeState)
    }

    private fun events(executionId: String, stepName: String): List<AgentEvent> =
        WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get(stepName)?.events?.toList().orEmpty()

    private fun triageClassifier(
        defaultCategory: String? = "general",
        minConfidence: Double? = 0.6,
        channelDefault: String? = "email",
        model: String? = "jev-latest"
    ) = ClassifierConfig(
        input = "Ticket: {{var:ticket_text}}",
        instructions = "Which team should handle this ticket from {{var:customer}}?",
        categories = listOf(
            ClassifierCategory("billing", "Payments, invoices, refunds"),
            ClassifierCategory("technical", "Bugs, outages, integrations"),
            ClassifierCategory("general", "Anything else")
        ),
        outputVariable = "team",
        defaultCategory = defaultCategory,
        jev = ClassifierJevConfig(
            model = model,
            minConfidence = minConfidence,
            questions = listOf(
                JevQuestionConfig(
                    id = "is_urgent",
                    type = JevQuestionType.NOUL,
                    instructions = "Is it time-sensitive for {{var:customer}}?",
                    yesDescription = "Explicitly time-sensitive",
                    threshold = 0.7
                ),
                JevQuestionConfig(
                    id = "frustration",
                    type = JevQuestionType.SCORE,
                    instructions = "How frustrated does the customer appear?",
                    levels = listOf("Calm", "Frustrated but civil", "Very angry")
                ),
                JevQuestionConfig(
                    id = "channel",
                    type = JevQuestionType.CHOICE,
                    instructions = "Which channel should we reply on?",
                    options = listOf(
                        ClassifierCategory("email", "Reply by email"),
                        ClassifierCategory("phone", "Call the customer back")
                    ),
                    defaultOption = channelDefault,
                    minConfidence = 0.5
                )
            ),
            composites = listOf(JevComposite("priority", mapOf("frustration" to 0.4, "is_urgent" to 0.6)))
        )
    )

    private fun codeStep(name: String, script: String, condition: String? = null, dependsOn: List<String> = emptyList()) =
        WorkflowStep(
            step = name,
            code = CodeStepConfig(language = "bash", script = script),
            condition = condition,
            dependsOn = dependsOn
        )

    private fun triageWorkflow(classifier: ClassifierConfig = triageClassifier()) = WorkflowDefinition(
        name = "jev-triage",
        agents = emptyMap(),
        variables = mapOf("ticket_text" to "I was charged twice, please fix it today!", "customer" to "Ada"),
        workflow = listOf(
            WorkflowStep(step = "triage", classifier = classifier),
            codeStep("billing_path", "printf billing", condition = "team == billing", dependsOn = listOf("triage")),
            codeStep("general_path", "printf general", condition = "team == general", dependsOn = listOf("triage")),
            codeStep(
                "urgent_path", "printf urgent",
                condition = "is_urgent_yes == true && team_confidence >= 0.8",
                dependsOn = listOf("triage")
            )
        )
    )

    private fun triageAnswers(
        team: JevChoiceAnswer = JevChoiceAnswer(
            choice = "billing",
            probabilities = mapOf("billing" to 0.88, "technical" to 0.12, "general" to 0.0),
            confidence = 0.81
        ),
        channel: JevChoiceAnswer = JevChoiceAnswer(
            choice = "email",
            probabilities = mapOf("email" to 0.8, "phone" to 0.2),
            confidence = 0.77
        )
    ): Map<String, JevAnswer> = linkedMapOf(
        "team" to team,
        "is_urgent" to JevNoulAnswer(0.95),
        "frustration" to JevScoreAnswer(
            score = 1.05,
            legend = mapOf("0" to "Calm", "1" to "Frustrated but civil", "2" to "Very angry"),
            probabilities = mapOf("0" to 0.0, "1" to 0.95, "2" to 0.05),
            confidence = 0.92
        ),
        "channel" to channel
    )

    private fun response(answers: Map<String, JevAnswer>, usage: JevUsage? = JevUsage(318, 34)) =
        JevResponse(model = "jev-1.13.0", answers = answers, usage = usage)

    private fun assertKeyNeverLeaks(executionId: String, result: WorkflowExecutionResult) {
        result.variables.forEach { (name, value) ->
            assertFalse(value.toString().contains(secretKey), "variable '$name' leaks the API key")
        }
        result.stepResults.values.forEach { step ->
            assertFalse(step.output.orEmpty().contains(secretKey), "output of '${step.stepName}' leaks the API key")
            assertFalse(step.error.orEmpty().contains(secretKey), "error of '${step.stepName}' leaks the API key")
            step.producedVariables.values.forEach { assertFalse(it.contains(secretKey)) }
        }
        WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.values?.forEach { metrics ->
            metrics.events.toList().forEach { event ->
                assertFalse(event.summary.contains(secretKey), "event summary leaks the API key")
                assertFalse(event.detail.orEmpty().contains(secretKey), "event detail leaks the API key")
            }
        }
    }

    // ==================== classifier.jev ====================

    @Test
    fun `jev classifier writes formatted variables, branches downstream and emits a single usage event`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ -> response(triageAnswers()) }
        val (executionId, result) = executor(factory).run(triageWorkflow())

        assertTrue(result.success, result.error)

        // One HTTP call for all four questions, key from the host credentials.
        assertEquals(1, factory.requests.size)
        assertEquals(listOf(secretKey), factory.apiKeys)
        val request = factory.requests.single()
        assertEquals("jev-latest", request.model)
        assertEquals(JsonPrimitive("Ticket: I was charged twice, please fix it today!"), request.state)
        assertEquals(listOf("team", "is_urgent", "frustration", "channel"), request.questions.keys.toList())
        val main = assertIs<JevChoiceQuestion>(request.questions.getValue("team"))
        assertEquals(JsonPrimitive("Which team should handle this ticket from Ada?"), main.instructions)
        assertEquals(listOf("billing", "technical", "general"), main.criteria.keys.toList())
        assertEquals(JsonPrimitive("Payments, invoices, refunds"), main.criteria["billing"])
        val urgent = assertIs<JevNoulQuestion>(request.questions.getValue("is_urgent"))
        assertEquals(JsonPrimitive("Is it time-sensitive for Ada?"), urgent.instructions)
        assertEquals(JsonPrimitive("Explicitly time-sensitive"), urgent.criteria?.whenTrue)
        assertNull(urgent.criteria?.whenFalse)
        val frustration = assertIs<JevScoreQuestion>(request.questions.getValue("frustration"))
        assertEquals(3, frustration.criteria.size)
        val wire = JevJson.encodeToString(JevRequest.serializer(), request)
        assertContains(wire, "\"type\":\"choice\"")
        assertContains(wire, "\"type\":\"score\"")
        assertContains(wire, "\"type\":\"noul\"")

        // §4.1 / §4.2 variables.
        val expected = linkedMapOf(
            "team" to "billing",
            "team_confidence" to "0.81",
            "team_probabilities" to """{"billing":0.88,"technical":0.12,"general":0}""",
            "is_urgent" to "0.95",
            "is_urgent_yes" to "true",
            "frustration" to "1.05",
            "frustration_level" to "1",
            "frustration_normalized" to "0.525",
            "frustration_confidence" to "0.92",
            "frustration_probabilities" to """{"0":0,"1":0.95,"2":0.05}""",
            "channel" to "email",
            "channel_confidence" to "0.77",
            "channel_probabilities" to """{"email":0.8,"phone":0.2}""",
            "priority" to "0.78"
        )
        expected.forEach { (name, value) -> assertEquals(value, result.variables[name], "variable '$name'") }
        assertEquals(triageClassifier().jevWrittenVariables(), expected.keys.toList())

        val triage = result.stepResults.getValue("triage")
        assertEquals("classification: billing\nconfidence: 0.81", triage.output)
        assertEquals("classifier(jev:jev-latest)", triage.agentName)
        // Classifier variables are set before executeStepOnce snapshots them (resume savepoint).
        assertEquals("0.78", triage.producedVariables["priority"])
        assertEquals("0.81", triage.producedVariables["team_confidence"])

        // Downstream condition branching.
        assertEquals("billing", result.stepResults.getValue("billing_path").output)
        assertFalse(result.stepResults.containsKey("general_path"))
        assertEquals("urgent", result.stepResults.getValue("urgent_path").output)

        // Events: started → usage (single token-bearing llm_call_completed) → completed.
        val triageEvents = events(executionId, "triage")
        assertEquals(
            listOf("classifier_started", "llm_call_completed", "classifier_completed"),
            triageEvents.map { it.type }
        )
        val started = triageEvents.first()
        assertEquals("agent", started.category)
        assertEquals("classifier", started.subCategory)
        assertEquals("🏷️ 分类路由开始 (Jev): 3 类别", started.summary)
        val usage = triageEvents[1]
        assertEquals("llm", usage.category)
        assertEquals("call", usage.subCategory)
        assertEquals("model=jev-1.13.0, provider=TypeSafe; input=318, output=34", usage.detail)
        assertEquals(318, usage.inputTokens)
        assertEquals(34, usage.outputTokens)
        assertEquals(352, usage.totalTokens)
        assertEquals(1, triageEvents.count { it.inputTokens != null || it.outputTokens != null })
        assertFalse(triageEvents.any { it.type == "token_usage" || it.subCategory == "token" })
        assertTrue(Regex("""model[=:]\s*([\w/.-]+)""").find(usage.detail!!)?.groupValues?.get(1) == "jev-1.13.0")

        val completed = triageEvents.last()
        assertEquals("classifier", completed.subCategory)
        assertEquals("🏷️ 分类结果: billing（Jev 置信度 0.81）", completed.summary)
        val payload = Json.parseToJsonElement(completed.detail!!).jsonObject
        assertEquals("jev", payload.getValue("engine").jsonPrimitive.content)
        assertEquals("classifier", payload.getValue("kind").jsonPrimitive.content)
        assertEquals("jev-1.13.0", payload.getValue("model").jsonPrimitive.content)
        assertEquals("team", payload.getValue("output_variable").jsonPrimitive.content)
        assertEquals("billing", payload.getValue("category").jsonPrimitive.content)
        assertEquals(0.81, payload.getValue("confidence").jsonPrimitive.double)
        assertEquals(0.6, payload.getValue("min_confidence").jsonPrimitive.double)
        assertFalse(payload.getValue("fell_back").jsonPrimitive.boolean)
        assertEquals("null", payload.getValue("fallback_reason").toString())
        val answers = payload.getValue("answers").jsonObject
        assertEquals(true, answers.getValue("is_urgent").jsonObject.getValue("yes").jsonPrimitive.boolean)
        assertEquals(1, answers.getValue("frustration").jsonObject.getValue("level").jsonPrimitive.int)
        assertEquals("Frustrated but civil", answers.getValue("frustration").jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("email", answers.getValue("channel").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(0.78, payload.getValue("composites").jsonObject.getValue("priority").jsonPrimitive.double)
        assertEquals(318, payload.getValue("usage").jsonObject.getValue("input_tokens").jsonPrimitive.int)

        assertKeyNeverLeaks(executionId, result)
    }

    @Test
    fun `below min_confidence falls back to default_category but keeps the actual jev values`() = runBlocking {
        val lowConfidence = JevChoiceAnswer(
            choice = "billing",
            probabilities = mapOf("billing" to 0.42, "technical" to 0.4, "general" to 0.18),
            confidence = 0.42
        )
        val factory = FakeJevClientFactory { _, _ -> response(triageAnswers(team = lowConfidence)) }
        val (executionId, result) = executor(factory).run(triageWorkflow())

        assertTrue(result.success, result.error)
        assertEquals("general", result.variables["team"])
        assertEquals("0.42", result.variables["team_confidence"])
        assertEquals("""{"billing":0.42,"technical":0.4,"general":0.18}""", result.variables["team_probabilities"])
        // Extra questions are written normally.
        assertEquals("true", result.variables["is_urgent_yes"])
        assertEquals("0.78", result.variables["priority"])
        assertEquals("classification: general", result.stepResults.getValue("triage").output)
        assertEquals("general", result.stepResults.getValue("general_path").output)
        assertFalse(result.stepResults.containsKey("billing_path"))
        assertFalse(result.stepResults.containsKey("urgent_path"))

        val payload = Json.parseToJsonElement(events(executionId, "triage").last().detail!!).jsonObject
        assertTrue(payload.getValue("fell_back").jsonPrimitive.boolean)
        assertEquals("low_confidence", payload.getValue("fallback_reason").jsonPrimitive.content)
        assertEquals("general", payload.getValue("category").jsonPrimitive.content)
    }

    @Test
    fun `below min_confidence without default_category fails the step`() = runBlocking {
        val lowConfidence = JevChoiceAnswer("billing", mapOf("billing" to 0.42, "technical" to 0.4, "general" to 0.18), 0.42)
        val factory = FakeJevClientFactory { _, _ -> response(triageAnswers(team = lowConfidence)) }
        val (executionId, result) = executor(factory).run(triageWorkflow(triageClassifier(defaultCategory = null)))

        assertFalse(result.success)
        val triage = result.stepResults.getValue("triage")
        assertFalse(triage.success)
        assertContains(
            triage.error.orEmpty(),
            "Classifier step 'triage': Jev confidence 0.42 is below min_confidence 0.6 and no default_category is set"
        )
        assertNull(result.variables["team"])
        // Tokens were spent, so the usage event is still recorded.
        assertEquals(1, events(executionId, "triage").count { it.type == "llm_call_completed" })
    }

    @Test
    fun `transient error with default_category writes the error fallback values over stale ones`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ ->
            throw JevApiException(JevApiException.Kind.OVERLOADED, 529, "TypeSafe API is overloaded (529) after 4 attempts")
        }
        val stale = mapOf(
            "team_confidence" to "0.99",
            "team_probabilities" to "{\"billing\":1}",
            "is_urgent" to "0.9",
            "is_urgent_yes" to "true",
            "frustration_normalized" to "1",
            "channel" to "phone",
            "priority" to "0.95"
        )
        val (executionId, result) = executor(factory).run(triageWorkflow(), initialInput = stale)

        assertTrue(result.success, result.error)
        val expected = linkedMapOf(
            "team" to "general",
            "team_confidence" to "0",
            "team_probabilities" to "{}",
            "is_urgent" to "",
            "is_urgent_yes" to "false",
            "frustration" to "",
            "frustration_level" to "",
            "frustration_normalized" to "",
            "frustration_confidence" to "",
            "frustration_probabilities" to "",
            "channel" to "",
            "channel_confidence" to "",
            "channel_probabilities" to "",
            "priority" to ""
        )
        expected.forEach { (name, value) -> assertEquals(value, result.variables[name], "variable '$name'") }
        assertEquals("classification: general", result.stepResults.getValue("triage").output)
        assertEquals("general", result.stepResults.getValue("general_path").output)
        // "" never satisfies a numeric comparison; is_urgent_yes is "false".
        assertFalse(result.stepResults.containsKey("urgent_path"))

        val triageEvents = events(executionId, "triage")
        assertEquals(listOf("classifier_started", "jev_call_failed", "classifier_completed"), triageEvents.map { it.type })
        assertEquals("🏷️ 分类结果: general（Jev 失败，使用默认类别）", triageEvents.last().summary)
        val payload = Json.parseToJsonElement(triageEvents.last().detail!!).jsonObject
        assertEquals("error", payload.getValue("fallback_reason").jsonPrimitive.content)
        assertTrue(payload.getValue("fell_back").jsonPrimitive.boolean)
        assertEquals("jev-latest", payload.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `transient error without default_category fails the step`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ ->
            throw JevApiException(JevApiException.Kind.NETWORK, null, "TypeSafe API request failed after 4 attempts: timeout")
        }
        val (_, result) = executor(factory).run(triageWorkflow(triageClassifier(defaultCategory = null, minConfidence = null)))

        assertFalse(result.success)
        assertContains(result.stepResults.getValue("triage").error.orEmpty(), "Classifier step 'triage': TypeSafe API request failed")
        assertNull(result.variables["team"])
    }

    @Test
    fun `401 fails the step even with a default_category`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ ->
            throw JevApiException(JevApiException.Kind.UNAUTHORIZED, 401, "TypeSafe API rejected the API key (401)")
        }
        val (executionId, result) = executor(factory).run(triageWorkflow())

        assertFalse(result.success)
        val triage = result.stepResults.getValue("triage")
        assertFalse(triage.success)
        assertContains(triage.error.orEmpty(), "rejected the API key (401)")
        assertNull(result.variables["team"], "a configuration error must never silently use default_category")
        assertFalse(result.stepResults.containsKey("general_path"))
        assertEquals(ExecutionStatus.FAILED, WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("triage")?.status)
        assertKeyNeverLeaks(executionId, result)
    }

    @Test
    fun `missing credentials fails the step without calling the factory`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ -> error("must not be called") }
        val (_, result) = executor(factory, credentials = null, envKeyFallback = false).run(triageWorkflow())

        assertFalse(result.success)
        assertContains(result.stepResults.getValue("triage").error.orEmpty(), JEV_MISSING_CREDENTIALS_MESSAGE)
        assertTrue(factory.apiKeys.isEmpty())
        assertNull(result.variables["team"])
    }

    @Test
    fun `key from base parameters and model from host credentials are used`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ -> response(triageAnswers()) }
        val params = listOf(ConfigurationParameter("typesafe_api_key", JsonPrimitive("param-key")))
        val (_, fromParams) = executor(factory, credentials = null, baseParameters = params).run(triageWorkflow())
        assertTrue(fromParams.success, fromParams.error)
        assertEquals(listOf("param-key"), factory.apiKeys)

        val hostFactory = FakeJevClientFactory { _, _ -> response(triageAnswers()) }
        val (_, fromHost) = executor(hostFactory, credentials = JevCredentials(secretKey, defaultModel = "jev-1.13.0"))
            .run(triageWorkflow(triageClassifier(model = null)))
        assertTrue(fromHost.success, fromHost.error)
        assertEquals("jev-1.13.0", hostFactory.requests.single().model)
        assertEquals("classifier(jev)", fromHost.stepResults.getValue("triage").agentName)
    }

    @Test
    fun `extra choice question below min_confidence uses default_option or fails`() = runBlocking {
        val unsureChannel = JevChoiceAnswer("phone", mapOf("email" to 0.3, "phone" to 0.7), 0.3)
        val factory = FakeJevClientFactory { _, _ -> response(triageAnswers(channel = unsureChannel)) }
        val (_, result) = executor(factory).run(triageWorkflow())
        assertTrue(result.success, result.error)
        assertEquals("email", result.variables["channel"])
        assertEquals("0.3", result.variables["channel_confidence"])
        assertEquals("""{"email":0.3,"phone":0.7}""", result.variables["channel_probabilities"])

        val failingFactory = FakeJevClientFactory { _, _ -> response(triageAnswers(channel = unsureChannel)) }
        val (_, failed) = executor(failingFactory).run(triageWorkflow(triageClassifier(channelDefault = null)))
        assertFalse(failed.success)
        assertContains(
            failed.stepResults.getValue("triage").error.orEmpty(),
            "Jev question 'channel' confidence 0.3 is below min_confidence 0.5 and no default_option is set"
        )
    }

    @Test
    fun `invalid response is treated as transient`() = runBlocking {
        // Missing the 'channel' answer → INVALID_RESPONSE → default_category fallback.
        val factory = FakeJevClientFactory { _, _ -> response(triageAnswers().filterKeys { it != "channel" }) }
        val (_, result) = executor(factory).run(triageWorkflow())
        assertTrue(result.success, result.error)
        assertEquals("general", result.variables["team"])
        assertEquals("0", result.variables["team_confidence"])
        assertEquals("", result.variables["channel"])
    }

    @Test
    fun `resume restores classifier jev variables without calling jev`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ -> response(triageAnswers()) }
        val (_, first) = executor(factory).run(triageWorkflow())
        assertTrue(first.success, first.error)

        val resumeFactory = FakeJevClientFactory { _, _ -> error("resume must not call Jev") }
        val workflow = triageWorkflow().let { wf ->
            wf.copy(
                workflow = wf.workflow + codeStep(
                    "report",
                    "printf '%s|%s|%s' \"${'$'}WF_VAR_TEAM\" \"${'$'}WF_VAR_TEAM_CONFIDENCE\" \"${'$'}WF_VAR_PRIORITY\"",
                    dependsOn = listOf("triage")
                )
            )
        }
        val (_, resumed) = executor(resumeFactory).run(
            workflow,
            resumeState = WorkflowResumeState(preservedStepResults = mapOf("triage" to first.stepResults.getValue("triage")))
        )
        assertTrue(resumed.success, resumed.error)
        assertTrue(resumeFactory.requests.isEmpty())
        assertEquals("billing|0.81|0.78", resumed.stepResults.getValue("report").output)
    }

    @Test
    fun `cancellation propagates instead of falling back to default_category`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val factory = FakeJevClientFactory { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val executor = executor(factory)
        val executionId = "jev-cancel-" + UUID.randomUUID()
        val run = async { executor.execute(triageWorkflow(), externalExecutionId = executionId) }
        withTimeout(10_000) { started.await() }
        run.cancel()
        assertThrows<CancellationException> { runBlocking { run.await() } }
        assertTrue(run.isCancelled)
        assertEquals(ExecutionStatus.CANCELLED, WorkflowMonitor.getMetrics(executionId)?.status)
        assertFalse(events(executionId, "triage").any { it.type == "classifier_completed" })
    }

    // ==================== state machine ====================

    @Test
    fun `state-machine jev classifier routes and overwrites stale values on re-entry`() = runBlocking {
        val factory = FakeJevClientFactory { _, callIndex ->
            when (callIndex) {
                0 -> response(triageAnswers())
                else -> throw JevApiException(JevApiException.Kind.RATE_LIMITED, 429, "TypeSafe API rate limit (429) after 4 attempts")
            }
        }
        val workflow = WorkflowDefinition(
            name = "sm-jev",
            agents = emptyMap(),
            variables = mapOf("ticket_text" to "Refund please", "customer" to "Ada"),
            workflow = listOf(
                WorkflowStep(
                    step = "flow",
                    stateMachine = StateMachineConfig(
                        initialState = "route",
                        finalStates = listOf("done"),
                        states = mapOf(
                            "route" to StateDefinition(
                                name = "route",
                                stepConfig = StateStepConfig(classifier = triageClassifier()),
                                transitions = listOf(
                                    StateTransition(event = "complete", target = "done", condition = "pass == second"),
                                    StateTransition(event = "complete", target = "billing_desk", condition = "team == billing"),
                                    StateTransition(event = "complete", target = "done")
                                )
                            ),
                            "billing_desk" to StateDefinition(
                                name = "billing_desk",
                                stepConfig = StateStepConfig(
                                    code = CodeStepConfig(language = "bash", script = "echo pass=second"),
                                    extract = listOf(ExtractConfig(pattern = "pass=(\\w+)", variable = "pass"))
                                ),
                                transitions = listOf(StateTransition(event = "complete", target = "route"))
                            ),
                            "done" to StateDefinition(name = "done")
                        )
                    )
                )
            )
        )

        val (executionId, result) = executor(factory).run(workflow)

        assertTrue(result.success, result.error)
        assertEquals(2, factory.requests.size)
        assertEquals("done", result.variables["flow_final_state"])
        // Second entry fell back: every stale first-run value was overwritten.
        assertEquals("general", result.variables["team"])
        assertEquals("0", result.variables["team_confidence"])
        assertEquals("false", result.variables["is_urgent_yes"])
        assertEquals("", result.variables["priority"])
        // Events land on the state's own sub-step (step.step = "flow.route"). The monitor
        // re-registers the sub-step on re-entry, so only the second (fallback) entry remains.
        val stateEvents = events(executionId, "flow.route")
        assertEquals(listOf("classifier_started", "jev_call_failed", "classifier_completed"), stateEvents.map { it.type })
        assertEquals("🏷️ 分类结果: general（Jev 失败，使用默认类别）", stateEvents.last().summary)
    }

    // ==================== repeat_until.jev ====================

    private fun draftLoop(
        maxIterations: Int = 5,
        onSuccess: List<TransitionAction> = emptyList(),
        state: String? = "{{steps.draft.output}} || feedback: {{steps.draft:evaluate.output}}"
    ) = WorkflowStep(
        step = "draft",
        // The parser rejects repeat_until on code steps; the executor runs it, which lets these
        // tests drive the loop deterministically without an LLM agent.
        code = CodeStepConfig(language = "bash", script = "echo draft-body"),
        onSuccess = onSuccess,
        repeatUntil = RepeatUntilConfig(
            condition = "quality >= 0.75",
            maxIterations = maxIterations,
            jev = RepeatUntilJevConfig(
                model = "jev-latest",
                state = state,
                questions = listOf(
                    JevQuestionConfig(
                        id = "accuracy",
                        type = JevQuestionType.SCORE,
                        instructions = "How factually accurate is the draft?",
                        levels = listOf("Clear errors", "Minor imprecision", "Accurate")
                    ),
                    JevQuestionConfig(
                        id = "on_brand",
                        type = JevQuestionType.NOUL,
                        instructions = "Does the draft follow a friendly, plain-language tone?"
                    )
                ),
                composites = listOf(JevComposite("quality", mapOf("accuracy" to 0.7, "on_brand" to 0.3)))
            )
        )
    )

    private fun loopAnswers(score: Double, onBrand: Double, confidence: Double = 0.84): Map<String, JevAnswer> = linkedMapOf(
        "accuracy" to JevScoreAnswer(score = score, probabilities = mapOf("0" to 0.1, "1" to 0.6, "2" to 0.3), confidence = confidence),
        "on_brand" to JevNoulAnswer(onBrand)
    )

    @Test
    fun `repeat_until jev loop stops when the composite meets the condition and stores the critique`() = runBlocking {
        val factory = FakeJevClientFactory { _, callIndex ->
            when (callIndex) {
                0 -> response(loopAnswers(score = 0.5, onBrand = 0.4), JevUsage(100, 5))
                else -> response(loopAnswers(score = 2.0, onBrand = 0.9), JevUsage(120, 6))
            }
        }
        val workflow = WorkflowDefinition(
            name = "jev-loop",
            agents = emptyMap(),
            workflow = listOf(
                draftLoop(),
                codeStep(
                    "report",
                    "printf '%s' \"${'$'}STEP_OUTPUT_DRAFT_EVALUATE\"",
                    dependsOn = listOf("draft")
                )
            )
        )

        val (executionId, result) = executor(factory).run(workflow)

        assertTrue(result.success, result.error)
        assertEquals(2, factory.requests.size, "loop must stop once quality >= 0.75")
        // Default/explicit state is template-resolved; iteration 2 sees iteration 1's critique.
        assertEquals(JsonPrimitive("draft-body || feedback: {{steps.draft:evaluate.output}}"), factory.requests[0].state)
        assertContains(factory.requests[1].state.jsonPrimitive.content, "Jev evaluation (iteration 1, model jev-1.13.0):")

        assertEquals("2", result.variables["accuracy"])
        assertEquals("2", result.variables["accuracy_level"])
        assertEquals("1", result.variables["accuracy_normalized"])
        assertEquals("0.84", result.variables["accuracy_confidence"])
        assertEquals("""{"0":0.1,"1":0.6,"2":0.3}""", result.variables["accuracy_probabilities"])
        assertEquals("0.9", result.variables["on_brand"])
        assertEquals("true", result.variables["on_brand_yes"])
        assertEquals("0.97", result.variables["quality"])

        assertEquals(
            """
            Jev evaluation (iteration 2, model jev-1.13.0):
            - on_brand: yes (p=0.9)
            - accuracy: level 2/2 "Accurate" (score 2, confidence 0.84)
            - quality (composite): 0.97
            """.trimIndent(),
            result.stepResults.getValue("report").output
        )

        // Savepoint refresh: the persisted snapshot carries the FINAL Jev values.
        val draft = result.stepResults.getValue("draft")
        assertTrue(draft.success)
        assertEquals("0.97", draft.producedVariables["quality"])
        assertEquals("true", draft.producedVariables["on_brand_yes"])

        val draftEvents = events(executionId, "draft")
        val usageEvents = draftEvents.filter { it.type == "llm_call_completed" }
        assertEquals(2, usageEvents.size)
        assertEquals("model=jev-1.13.0, provider=TypeSafe; input=100, output=5", usageEvents[0].detail)
        assertEquals(126, usageEvents[1].totalTokens)
        val evaluations = draftEvents.filter { it.type == "repeat_until_evaluate" }
        assertEquals(listOf("Jev evaluation completed (iteration 1)", "Jev evaluation completed (iteration 2)"), evaluations.map { it.summary })
        val payload = Json.parseToJsonElement(evaluations.last().detail!!).jsonObject
        assertEquals("repeat_until", payload.getValue("kind").jsonPrimitive.content)
        assertEquals(2, payload.getValue("iteration").jsonPrimitive.int)
        assertFalse(payload.containsKey("category"))
        assertFalse(payload.containsKey("output_variable"))
        assertEquals(0.97, payload.getValue("composites").jsonObject.getValue("quality").jsonPrimitive.double)
        assertEquals(ExecutionStatus.COMPLETED, WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("draft")?.status)
        assertKeyNeverLeaks(executionId, result)
    }

    @Test
    fun `repeat_until jev defaults the state to the step output`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ -> response(loopAnswers(score = 2.0, onBrand = 0.9)) }
        val workflow = WorkflowDefinition(name = "jev-loop-default-state", agents = emptyMap(), workflow = listOf(draftLoop(state = null)))
        val (_, result) = executor(factory).run(workflow)
        assertTrue(result.success, result.error)
        assertEquals(JsonPrimitive("draft-body"), factory.requests.single().state)
    }

    @Test
    fun `repeat_until jev configuration error fails fast and does not fire on_success`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ ->
            throw JevApiException(JevApiException.Kind.UNAUTHORIZED, 401, "TypeSafe API rejected the API key (401)")
        }
        val workflow = WorkflowDefinition(
            name = "jev-loop-401",
            agents = emptyMap(),
            workflow = listOf(
                draftLoop(onSuccess = listOf(TransitionAction(next = "celebrate"))),
                codeStep("celebrate", "printf celebrate")
            )
        )

        val (executionId, result) = executor(factory).run(workflow)

        assertFalse(result.success)
        assertEquals(1, factory.requests.size, "a configuration error must not burn the remaining iterations")
        assertFalse(result.stepResults.containsKey("celebrate"), "on_success target must not run")
        val draft = result.stepResults.getValue("draft")
        assertFalse(draft.success)
        assertContains(draft.error.orEmpty(), "repeat_until Jev evaluation failed for step 'draft'")
        assertContains(draft.error.orEmpty(), "(401)")
        assertEquals("draft-body", draft.output?.trim())
        assertFalse("draft" in result.recoveredFailures)
        val metrics = WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("draft")
        assertEquals(ExecutionStatus.FAILED, metrics?.status)
        assertContains(metrics?.error.orEmpty(), "(401)")
        assertKeyNeverLeaks(executionId, result)
    }

    @Test
    fun `unexpected repeat_until jev errors also fail the step without firing on_success`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ -> error("unexpected client bug") }
        val workflow = WorkflowDefinition(
            name = "jev-loop-bug",
            agents = emptyMap(),
            workflow = listOf(
                draftLoop(onSuccess = listOf(TransitionAction(next = "celebrate"))),
                codeStep("celebrate", "printf celebrate")
            )
        )
        val (executionId, result) = executor(factory).run(workflow)
        assertFalse(result.success)
        assertFalse(result.stepResults.containsKey("celebrate"))
        assertContains(result.stepResults.getValue("draft").error.orEmpty(), "unexpected client bug")
        assertEquals(ExecutionStatus.FAILED, WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("draft")?.status)
    }

    @Test
    fun `repeat_until jev missing credentials fails fast`() = runBlocking {
        val factory = FakeJevClientFactory { _, _ -> error("must not be called") }
        val workflow = WorkflowDefinition(name = "jev-loop-no-key", agents = emptyMap(), workflow = listOf(draftLoop()))
        val (executionId, result) = executor(factory, credentials = null, envKeyFallback = false).run(workflow)
        assertFalse(result.success)
        assertContains(result.stepResults.getValue("draft").error.orEmpty(), JEV_MISSING_CREDENTIALS_MESSAGE)
        assertEquals(ExecutionStatus.FAILED, WorkflowMonitor.getMetrics(executionId)?.stepMetrics?.get("draft")?.status)
    }

    @Test
    fun `repeat_until jev transient error writes fallback values and the loop continues`() = runBlocking {
        val factory = FakeJevClientFactory { _, callIndex ->
            when (callIndex) {
                0 -> throw JevApiException(JevApiException.Kind.RATE_LIMITED, 429, "TypeSafe API rate limit (429) after 4 attempts")
                else -> response(loopAnswers(score = 2.0, onBrand = 0.9))
            }
        }
        val workflow = WorkflowDefinition(name = "jev-loop-transient", agents = emptyMap(), workflow = listOf(draftLoop(maxIterations = 1)))
        val stale = mapOf("quality" to "0.99", "on_brand_yes" to "true", "accuracy_level" to "2")

        val (executionId, result) = executor(factory).run(workflow, initialInput = stale)

        // max_iterations = 1: the only evaluation failed transiently → fallback values, step still succeeds.
        assertTrue(result.success, result.error)
        assertEquals(1, factory.requests.size)
        assertEquals("", result.variables["quality"])
        assertEquals("false", result.variables["on_brand_yes"])
        assertEquals("", result.variables["accuracy_level"])
        assertEquals("", result.stepResults.getValue("draft").producedVariables["quality"])
        val draftEvents = events(executionId, "draft")
        assertTrue(draftEvents.any { it.type == "jev_call_failed" })
        val evaluation = draftEvents.last { it.type == "repeat_until_evaluate" }
        assertEquals("error", Json.parseToJsonElement(evaluation.detail!!).jsonObject.getValue("fallback_reason").jsonPrimitive.content)

        val continuing = FakeJevClientFactory { _, callIndex ->
            when (callIndex) {
                0 -> throw JevApiException(JevApiException.Kind.SERVER_ERROR, 503, "TypeSafe API server error (503) after 4 attempts")
                else -> response(loopAnswers(score = 2.0, onBrand = 0.9))
            }
        }
        val (_, looped) = executor(continuing).run(
            WorkflowDefinition(name = "jev-loop-continue", agents = emptyMap(), workflow = listOf(draftLoop(maxIterations = 3)))
        )
        assertTrue(looped.success, looped.error)
        assertEquals(2, continuing.requests.size)
        assertEquals("0.97", looped.variables["quality"])
    }

    @Test
    fun `resume after a repeat_until jev step restores the final jev values`() = runBlocking {
        val factory = FakeJevClientFactory { _, callIndex ->
            when (callIndex) {
                0 -> response(loopAnswers(score = 0.5, onBrand = 0.4))
                else -> response(loopAnswers(score = 2.0, onBrand = 0.9))
            }
        }
        val workflow = WorkflowDefinition(
            name = "jev-loop-resume",
            agents = emptyMap(),
            workflow = listOf(
                draftLoop(),
                codeStep(
                    "report",
                    "printf '%s|%s' \"${'$'}WF_VAR_QUALITY\" \"${'$'}WF_VAR_ACCURACY_LEVEL\"",
                    dependsOn = listOf("draft")
                )
            )
        )
        val (_, first) = executor(factory).run(workflow)
        assertTrue(first.success, first.error)
        assertEquals("0.97|2", first.stepResults.getValue("report").output)

        val resumeFactory = FakeJevClientFactory { _, _ -> error("resume must not call Jev") }
        val (_, resumed) = executor(resumeFactory).run(
            workflow,
            resumeState = WorkflowResumeState(preservedStepResults = mapOf("draft" to first.stepResults.getValue("draft")))
        )
        assertTrue(resumed.success, resumed.error)
        assertTrue(resumeFactory.requests.isEmpty())
        assertEquals("0.97|2", resumed.stepResults.getValue("report").output)
    }

    @Test
    fun `repeat_until jev cancellation propagates`() {
        val factory = FakeJevClientFactory { _, _ -> throw CancellationException("stopped by user") }
        val workflow = WorkflowDefinition(name = "jev-loop-cancel", agents = emptyMap(), workflow = listOf(draftLoop()))
        assertThrows<CancellationException> {
            runBlocking { executor(factory).execute(workflow) }
        }
        assertEquals(1, factory.requests.size)
    }

    @Test
    fun `positional executor construction stays source compatible`() {
        // Web uses named args; tests and WorkflowTools use 2-3 positional args.
        val executor = WorkflowExecutor(HttpAccess(), emptyList(), false)
        assertNotNull(executor)
    }
}
