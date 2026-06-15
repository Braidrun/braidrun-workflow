package com.fartech.agents.workflow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File

class WorkflowTemplateValidationTest {

    private fun resolveTemplatePath(fileName: String): String {
        val candidates = listOf(
            "workflows/templates/$fileName",
            "braidrun-web/workflows/templates/$fileName",
            "../braidrun-web/workflows/templates/$fileName",
            "../../braidrun-web/workflows/templates/$fileName",
            "workflows/modules/$fileName",
            "braidrun-web/workflows/modules/$fileName",
            "../braidrun-web/workflows/modules/$fileName",
            "../../braidrun-web/workflows/modules/$fileName",
            "braidrun-agent/workflows/templates/$fileName",
            "../braidrun-agent/workflows/templates/$fileName",
            "../../braidrun-agent/workflows/templates/$fileName"
        )

        return candidates.firstOrNull { File(it).exists() }
            ?: error("Template file '$fileName' not found in any candidate path: $candidates")
    }

    private fun parseTemplate(fileName: String): WorkflowDefinition =
        WorkflowParser.parseFile(resolveTemplatePath(fileName))

    private fun allTemplateFiles(): List<File> {
        val filesByName = linkedMapOf<String, File>()
        val candidateDirs = listOf(
            File("braidrun-web/workflows/templates"),
            File("../braidrun-web/workflows/templates"),
            File("../../braidrun-web/workflows/templates"),
            File("workflows/templates"),
            // 内置 braidrun-* 模块也参与同样的静态校验(parser 校验、agent session 策略校验等)
            File("braidrun-web/workflows/modules"),
            File("../braidrun-web/workflows/modules"),
            File("../../braidrun-web/workflows/modules"),
            File("workflows/modules"),
            File("braidrun-agent/workflows/templates"),
            File("../braidrun-agent/workflows/templates"),
            File("../../braidrun-agent/workflows/templates")
        )

        candidateDirs
            .filter { it.exists() }
            .forEach { directory ->
                directory.walkTopDown()
                    .filter { it.isFile && it.extension == "yaml" }
                    .sortedBy { it.name }
                    .forEach { file -> filesByName.putIfAbsent(file.name, file) }
            }

        return filesByName.values.toList()
    }

    @Test
    fun `test iOS app development workflow template`() {
        val templatePath = resolveTemplatePath("ios-app-full-development.yaml")
        val file = File(templatePath)

        assertTrue(file.exists(), "Template file should exist: $templatePath")

        // Parse the workflow
        val workflow = WorkflowParser.parseFile(templatePath)

        // Validate basic structure
        assertEquals("ios-app-full-development", workflow.name)
        assertEquals("3.0.0", workflow.version)
        assertNotNull(workflow.description)

        // Validate variables
        assertTrue(workflow.variables.containsKey("app_category"))
        assertTrue(workflow.variables.containsKey("target_audience"))
        assertTrue(workflow.variables.containsKey("budget"))
        assertTrue(workflow.variables.containsKey("timeline"))
        assertTrue(workflow.variables.containsKey("quality_threshold"))

        // Validate agents
        assertTrue(workflow.agents.containsKey("market_analyst"))
        assertTrue(workflow.agents.containsKey("product_manager"))
        assertTrue(workflow.agents.containsKey("ios_architect"))
        assertTrue(workflow.agents.containsKey("ios_developer"))
        assertTrue(workflow.agents.containsKey("ui_designer"))
        assertTrue(workflow.agents.containsKey("qa_engineer"))
        assertTrue(workflow.agents.containsKey("appstore_specialist"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))

        // Validate workflow steps (13 original + 1 group_chat technical-review-meeting = 14)
        assertEquals(14, workflow.workflow.size, "Should have 14 workflow steps")

        // Validate step names
        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("market-research"))
        assertTrue(stepNames.contains("product-requirements"))
        assertTrue(stepNames.contains("core-features-development"))
        assertTrue(stepNames.contains("app-store-submission-checklist"))
        assertTrue(stepNames.contains("project-summary"))

        // Validate dependencies
        val projectSetupStep = workflow.workflow.find { it.step == "project-setup" }
        assertNotNull(projectSetupStep)
        assertTrue(projectSetupStep!!.dependsOn.contains("technical-architecture"))

        // Validate topological order
        val executionOrder = WorkflowParser.getTopologicalOrder(workflow)
        assertEquals(workflow.workflow.size, executionOrder.size)

        // Validate that dependencies come before dependents
        val stepIndices = executionOrder.withIndex().associate { it.value.step to it.index }
        for (step in executionOrder) {
            for (dep in step.dependsOn) {
                val depIndex = stepIndices[dep]
                val stepIndex = stepIndices[step.step]
                assertNotNull(depIndex, "Dependency $dep should exist")
                assertNotNull(stepIndex, "Step ${step.step} should exist")
                assertTrue(
                    depIndex!! < stepIndex!!,
                    "Dependency $dep (index $depIndex) should come before ${step.step} (index $stepIndex)"
                )
            }
        }

        println("✅ iOS App Development workflow template validated successfully")
    }

    @Test
    fun `test Google Ads App campaign workflow template`() {
        val templatePath = resolveTemplatePath("google-ads-app-campaign.yaml")
        val file = File(templatePath)

        assertTrue(file.exists(), "Template file should exist: $templatePath")

        // Parse the workflow
        val workflow = WorkflowParser.parseFile(templatePath)

        // Validate basic structure
        assertEquals("google-ads-app-campaign", workflow.name)
        assertEquals("3.0.0", workflow.version)
        assertNotNull(workflow.description)

        // Validate variables
        assertTrue(workflow.variables.containsKey("app_name"))
        assertTrue(workflow.variables.containsKey("app_id"))
        assertTrue(workflow.variables.containsKey("platform"))
        assertTrue(workflow.variables.containsKey("budget_daily"))
        assertTrue(workflow.variables.containsKey("target_cpi"))

        // Validate agents
        assertTrue(workflow.agents.containsKey("ads_strategist"))
        assertTrue(workflow.agents.containsKey("ad_copywriter"))
        assertTrue(workflow.agents.containsKey("audience_analyst"))
        assertTrue(workflow.agents.containsKey("creative_advisor"))
        assertTrue(workflow.agents.containsKey("ads_executor"))
        assertTrue(workflow.agents.containsKey("data_analyst"))
        assertTrue(workflow.agents.containsKey("optimization_specialist"))

        // Validate workflow steps
        assertEquals(13, workflow.workflow.size, "Should have 13 workflow steps")

        // Validate step names
        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("market-analysis"))
        assertTrue(stepNames.contains("campaign-strategy"))
        assertTrue(stepNames.contains("ad-copy-creation"))
        assertTrue(stepNames.contains("optimization-playbook"))
        assertTrue(stepNames.contains("campaign-playbook"))

        // Validate parallel execution configuration
        val adCopyStep = workflow.workflow.find { it.step == "ad-copy-creation" }
        assertNotNull(adCopyStep)
        assertNotNull(adCopyStep!!.parallel)
        assertEquals(3, adCopyStep.parallel!!.maxParallel)
        assertTrue(adCopyStep.parallel.aggregateResults)

        // Validate topological order
        val executionOrder = WorkflowParser.getTopologicalOrder(workflow)
        assertEquals(workflow.workflow.size, executionOrder.size)

        println("✅ Google Ads App Campaign workflow template validated successfully")
    }

    @Test
    fun `test Apple Search Ads campaign workflow template`() {
        val templatePath = resolveTemplatePath("apple-search-ads-campaign.yaml")
        val file = File(templatePath)

        assertTrue(file.exists(), "Template file should exist: $templatePath")

        // Parse the workflow
        val workflow = WorkflowParser.parseFile(templatePath)

        // Validate basic structure
        assertEquals("apple-search-ads-campaign", workflow.name)
        assertEquals("3.0.0", workflow.version)
        assertNotNull(workflow.description)

        // Validate variables
        assertTrue(workflow.variables.containsKey("app_id"))
        assertTrue(workflow.variables.containsKey("app_name"))
        assertTrue(workflow.variables.containsKey("budget_daily"))
        assertTrue(workflow.variables.containsKey("target_cpt"))
        assertTrue(workflow.variables.containsKey("campaign_type"))

        // Validate agents
        assertTrue(workflow.agents.containsKey("asa_strategist"))
        assertTrue(workflow.agents.containsKey("keyword_researcher"))
        assertTrue(workflow.agents.containsKey("aso_specialist"))
        assertTrue(workflow.agents.containsKey("creative_consultant"))
        assertTrue(workflow.agents.containsKey("asa_executor"))
        assertTrue(workflow.agents.containsKey("performance_analyst"))
        assertTrue(workflow.agents.containsKey("bid_optimizer"))

        // Validate workflow steps
        assertEquals(14, workflow.workflow.size, "Should have 14 workflow steps")

        // Validate step names
        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("competitor-analysis"))
        assertTrue(stepNames.contains("keyword-discovery"))
        assertTrue(stepNames.contains("aso-optimization"))
        assertTrue(stepNames.contains("campaign-structure"))
        assertTrue(stepNames.contains("asa-playbook"))

        // Validate parallel execution configuration
        val keywordStep = workflow.workflow.find { it.step == "keyword-discovery" }
        assertNotNull(keywordStep)
        assertNotNull(keywordStep!!.parallel)
        assertEquals(3, keywordStep.parallel!!.maxParallel)

        // Validate timeout configuration
        assertNotNull(workflow.timeout)
        assertEquals("3600s", workflow.timeout!!.total)
        assertEquals("400s", workflow.timeout.perStep)

        // Validate error handling
        assertNotNull(workflow.errorHandling)
        assertEquals(2, workflow.errorHandling!!.maxRetries)
        assertFalse(workflow.errorHandling.continueOnError)

        // Validate topological order
        val executionOrder = WorkflowParser.getTopologicalOrder(workflow)
        assertEquals(workflow.workflow.size, executionOrder.size)

        println("✅ Apple Search Ads Campaign workflow template validated successfully")
    }

    @Test
    fun `test PRD review refinement workflow template`() {
        val templatePath = resolveTemplatePath("prd-review-refinement.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("prd-review-refinement", workflow.name)
        assertTrue(workflow.agents.containsKey("pm_expert"))
        assertTrue(workflow.agents.containsKey("tech_architect"))
        assertTrue(workflow.agents.containsKey("test_manager"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))
        assertEquals(7, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("prd-logic-audit"))
        assertTrue(stepNames.contains("technical-feasibility-check"))
        assertTrue(stepNames.contains("test-coverage-design"))
        assertTrue(stepNames.contains("aggregate-assessments"))
        assertTrue(stepNames.contains("cross-functional-review"))
        assertTrue(stepNames.contains("enhanced-prd"))
        assertTrue(stepNames.contains("final-report"))

        println("✅ PRD Review Refinement workflow template validated successfully")
    }

    @Test
    fun `test User Growth strategy workflow template`() {
        val templatePath = resolveTemplatePath("user-growth-strategy.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("user-growth-strategy", workflow.name)
        assertTrue(workflow.agents.containsKey("growth_analyst"))
        assertTrue(workflow.agents.containsKey("channel_expert"))
        assertTrue(workflow.agents.containsKey("product_growth"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))
        assertEquals(4, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("growth-analysis"))
        assertTrue(stepNames.contains("growth-meeting"))
        assertTrue(stepNames.contains("growth-strategy"))
        assertTrue(stepNames.contains("execution-plan"))

        println("✅ User Growth Strategy workflow template validated successfully")
    }

    @Test
    fun `test Content Marketing and SEO workflow template`() {
        val templatePath = resolveTemplatePath("content-marketing-seo.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("content-marketing-seo", workflow.name)
        assertTrue(workflow.agents.containsKey("seo_analyst"))
        assertTrue(workflow.agents.containsKey("content_strategist"))
        assertTrue(workflow.agents.containsKey("seo_writer"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))
        assertEquals(5, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("keyword-research"))
        assertTrue(stepNames.contains("seo-strategy-meeting"))
        assertTrue(stepNames.contains("content-creation"))
        assertTrue(stepNames.contains("seo-review"))
        assertTrue(stepNames.contains("distribution-plan"))

        println("✅ Content Marketing and SEO workflow template validated successfully")
    }

    @Test
    fun `test Backend Service design workflow template`() {
        val templatePath = resolveTemplatePath("backend-service-design.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("backend-service-design", workflow.name)
        assertTrue(workflow.agents.containsKey("backend_architect"))
        assertTrue(workflow.agents.containsKey("database_expert"))
        assertTrue(workflow.agents.containsKey("api_specialist"))
        assertTrue(workflow.agents.containsKey("security_consultant"))
        assertTrue(workflow.agents.containsKey("design_reviewer"))
        assertEquals(7, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("architecture-design"))
        assertTrue(stepNames.contains("db-schema-modeling"))
        assertTrue(stepNames.contains("api-specification"))
        assertTrue(stepNames.contains("aggregate-designs"))
        assertTrue(stepNames.contains("architecture-review-meeting"))
        assertTrue(stepNames.contains("optimize-design"))
        assertTrue(stepNames.contains("final-technical-doc"))

        println("✅ Backend Service Design workflow template validated successfully")
    }

    @Test
    fun `test OPC Product Launch Marketing workflow template`() {
        val templatePath = resolveTemplatePath("opc-product-launch-marketing.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("opc-product-launch-marketing", workflow.name)
        assertTrue(workflow.agents.containsKey("market_analyst"))
        assertTrue(workflow.agents.containsKey("launch_copywriter"))
        assertTrue(workflow.agents.containsKey("community_builder"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))
        assertEquals(5, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("market-positioning"))
        assertTrue(stepNames.contains("launch-strategy"))
        assertTrue(stepNames.contains("multi-platform-copy"))
        assertTrue(stepNames.contains("copy-review"))
        assertTrue(stepNames.contains("launch-roadmap"))

        println("✅ OPC Product Launch Marketing workflow template validated successfully")
    }

    @Test
    fun `test OPC Content and Social Media workflow template`() {
        val templatePath = resolveTemplatePath("opc-content-social-media.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("opc-content-social-media", workflow.name)
        assertTrue(workflow.agents.containsKey("content_strategist"))
        assertTrue(workflow.agents.containsKey("content_creator"))
        assertTrue(workflow.agents.containsKey("growth_advisor"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))
        assertEquals(4, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("content-strategy"))
        assertTrue(stepNames.contains("topic-discussion"))
        assertTrue(stepNames.contains("content-creation"))
        assertTrue(stepNames.contains("distribution-schedule"))

        println("✅ OPC Content and Social Media workflow template validated successfully")
    }

    @Test
    fun `test OPC Customer Feedback Automation workflow template`() {
        val templatePath = resolveTemplatePath("opc-customer-feedback-automation.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("opc-customer-feedback-automation", workflow.name)
        assertTrue(workflow.agents.containsKey("feedback_classifier"))
        assertTrue(workflow.agents.containsKey("support_writer"))
        assertTrue(workflow.agents.containsKey("product_advisor"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))
        assertEquals(4, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("sentiment-and-classification"))
        assertTrue(stepNames.contains("feedback-discussion"))
        assertTrue(stepNames.contains("drafting-support-responses"))
        assertTrue(stepNames.contains("roadmap-alignment"))

        println("✅ OPC Customer Feedback Automation workflow template validated successfully")
    }

    @Test
    fun `test OPC Brand Identity Strategy workflow template`() {
        val templatePath = resolveTemplatePath("opc-brand-identity-strategy.yaml")
        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("opc-brand-identity-strategy", workflow.name)
        assertTrue(workflow.agents.containsKey("brand_strategist"))
        assertTrue(workflow.agents.containsKey("brand_storyteller"))
        assertTrue(workflow.agents.containsKey("visual_designer"))
        assertTrue(workflow.agents.containsKey("quality_reviewer"))
        assertEquals(4, workflow.workflow.size)

        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("brand-core-definition"))
        assertTrue(stepNames.contains("brand-discussion"))
        assertTrue(stepNames.contains("brand-storytelling"))
        assertTrue(stepNames.contains("visual-identity-guide"))

        println("✅ OPC Brand Identity Strategy workflow template validated successfully")
    }

    @Test
    fun `test workflow generator template`() {
        val templatePath = resolveTemplatePath("workflow-generator.yaml")
        val file = File(templatePath)

        assertTrue(file.exists(), "Template file should exist: $templatePath")

        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("workflow-generator", workflow.name)
        assertEquals("1.3.0", workflow.version)
        assertTrue(workflow.tags.contains("workflow_tools"))
        assertTrue(workflow.tags.contains("skill_tools"))

        assertTrue(workflow.variables.containsKey("reference_templates"))
        assertTrue(workflow.variables.containsKey("review_gate"))
        assertTrue(workflow.variables.containsKey("validation_result"))
        assertTrue(workflow.variables.containsKey("validation_errors"))

        assertTrue(workflow.agents.containsKey("workflow_researcher"))
        assertTrue(workflow.agents.containsKey("workflow_architect"))
        assertTrue(workflow.agents.containsKey("yaml_engineer"))
        assertTrue(workflow.agents.containsKey("syntax_checker"))
        assertTrue(workflow.agents.containsKey("doc_writer"))

        val perExecutionAgents = listOf(
            "requirement_analyst",
            "workflow_researcher",
            "workflow_architect",
            "yaml_engineer",
            "quality_reviewer",
            "syntax_checker",
            "doc_writer"
        )
        perExecutionAgents.forEach { agentName ->
            val agent = workflow.agents[agentName] ?: fail("Agent '$agentName' should exist")
            assertEquals(
                JsonPrimitive("per_execution"),
                agent.resolveParameters()["session_id_strategy"],
                "Agent '$agentName' should share session within one execution"
            )
        }

        val yamlEngineerPrompt = (workflow.agents["yaml_engineer"]
            ?.resolveParameters()
            ?.get("system_prompt") as? JsonPrimitive)?.content ?: fail("yaml_engineer prompt should exist")
        assertTrue(
            yamlEngineerPrompt.contains("session_id_strategy"),
            "yaml_engineer prompt should explicitly require session_id_strategy authoring"
        )

        assertEquals(10, workflow.workflow.size, "Workflow generator should have 10 orchestration steps")

        val stepNames = workflow.workflow.map { it.step }
        assertFalse(stepNames.contains("classify_requirement"))
        assertFalse(stepNames.contains("compute_parameters"))
        assertTrue(stepNames.contains("research_current_capabilities"))
        assertTrue(stepNames.contains("deep_analysis"))
        assertTrue(stepNames.contains("select_reference_templates"))
        assertTrue(stepNames.contains("architecture_design"))
        assertTrue(stepNames.contains("generate_steps_detail"))
        assertTrue(stepNames.contains("expert_review_meeting"))
        assertTrue(stepNames.contains("assemble_and_refine"))
        assertTrue(stepNames.contains("documentation_delivery"))

        val researchStep = workflow.workflow.find { it.step == "research_current_capabilities" }
            ?: fail("research_current_capabilities step should exist")
        assertTrue(
            researchStep.dependsOn.isEmpty(),
            "research_current_capabilities should run first without fixed pre-classification"
        )

        val deepAnalysisStep = workflow.workflow.find { it.step == "deep_analysis" }
            ?: fail("deep_analysis step should exist")
        val deepAnalysisExtract = deepAnalysisStep.extract ?: fail("deep_analysis should extract planning variables")
        val extractedVariables = deepAnalysisExtract.map { it.variable }.toSet()
        assertTrue(extractedVariables.contains("complexity_level"))
        assertTrue(extractedVariables.contains("estimated_steps"))
        assertTrue(extractedVariables.contains("recommended_modes"))
        assertTrue(extractedVariables.contains("step_list"))
        assertTrue(
            deepAnalysisStep.input?.contains("固定分类路由") == true,
            "deep_analysis should explicitly choose structure without a rigid upfront classifier"
        )

        val architectureStep = workflow.workflow.find { it.step == "architecture_design" }
            ?: fail("architecture_design step should exist")
        val architectureConfig = architectureStep.agentBased ?: fail("architecture_design should use agent_based")
        val orchestrator = architectureConfig.orchestrator
        assertEquals(3, architectureConfig.participants.size)
        assertEquals("workflow_architect", orchestrator.agentRef)
        assertEquals(
            JsonPrimitive("per_execution"),
            orchestrator.resolveParameters(workflow.agents)["session_id_strategy"],
            "architecture_design orchestrator should keep session within the execution"
        )

        val generateStep = workflow.workflow.find { it.step == "generate_steps_detail" }
            ?: fail("generate_steps_detail step should exist")
        val iterateConfig = generateStep.iterateOver ?: fail("generate_steps_detail should use iterate_over")
        assertEquals("current_step", iterateConfig.itemVariable)

        val reviewStep = workflow.workflow.find { it.step == "expert_review_meeting" }
            ?: fail("expert_review_meeting step should exist")
        val reviewConfig = reviewStep.groupChat ?: fail("expert_review_meeting should use group_chat")
        assertEquals(4, reviewConfig.participants.size)
        assertTrue(
            reviewConfig.initialMessage?.contains("session_id_strategy") == true,
            "expert review meeting should audit session_id_strategy completeness"
        )

        val refineStep = workflow.workflow.find { it.step == "assemble_and_refine" }
            ?: fail("assemble_and_refine step should exist")
        val repeatConfig = refineStep.repeatUntil ?: fail("assemble_and_refine should use repeat_until")
        assertEquals("syntax_checker", repeatConfig.evaluateAgent)
        assertTrue(
            refineStep.input?.contains("session_id_strategy") == true,
            "assemble_and_refine should require explicit session_id_strategy output"
        )

        val deliveryStep = workflow.workflow.find { it.step == "documentation_delivery" }
            ?: fail("documentation_delivery step should exist")
        assertNotNull(deliveryStep.stateMachine)

        val globalAgent = workflow.globalAgent?.agent ?: fail("Global agent should be configured")
        assertEquals(
            JsonPrimitive("per_execution"),
            globalAgent.resolveParameters()["session_id_strategy"],
            "Global agent should share session within one execution"
        )

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Workflow generator template validated successfully")
    }

    @Test
    fun `test direct workflow generator template`() {
        val templatePath = resolveTemplatePath("workflow-generator-direct.yaml")
        val file = File(templatePath)

        assertTrue(file.exists(), "Template file should exist: $templatePath")

        val workflow = WorkflowParser.parseFile(templatePath)

        assertEquals("workflow-generator-direct", workflow.name)
        assertEquals("1.0.0", workflow.version)
        assertTrue(workflow.tags.contains("workflow_tools"))
        assertTrue(workflow.tags.contains("skill_tools"))
        assertTrue(workflow.tags.contains("直接生成"))

        assertTrue(workflow.variables.containsKey("reference_templates"))
        assertTrue(workflow.variables.containsKey("reusable_resources"))
        assertTrue(workflow.variables.containsKey("review_gate"))
        assertTrue(workflow.variables.containsKey("validation_result"))
        assertTrue(workflow.variables.containsKey("validation_errors"))

        val expectedAgents = listOf(
            "workflow_researcher",
            "workflow_architect",
            "yaml_engineer",
            "syntax_checker",
            "doc_writer"
        )
        expectedAgents.forEach { agentName ->
            val agent = workflow.agents[agentName] ?: fail("Agent '$agentName' should exist")
            assertEquals(
                JsonPrimitive("per_execution"),
                agent.resolveParameters()["session_id_strategy"],
                "Agent '$agentName' should explicitly use per_execution"
            )
        }

        assertEquals(7, workflow.workflow.size, "Direct workflow generator should have 7 orchestration steps")

        val stepNames = workflow.workflow.map { it.step }
        assertEquals(
            listOf(
                "research_requirement_and_resources",
                "design_workflow_directly",
                "generate_workflow_directly",
                "final_validation",
                "write_workflow_readme",
                "write_validation_blockers",
                "delivery_summary"
            ),
            stepNames
        )

        assertTrue(workflow.workflow.none { it.isClassifier }, "Direct workflow generator should not use classifier steps")
        assertTrue(workflow.workflow.none { it.isGroupChat }, "Direct workflow generator should not use group_chat steps")
        assertTrue(workflow.workflow.none { it.isAgentBased }, "Direct workflow generator should not use agent_based steps")
        assertTrue(workflow.workflow.none { it.iterateOver != null }, "Direct workflow generator should not use iterate_over")
        assertTrue(workflow.workflow.none { it.stateMachine != null }, "Direct workflow generator should not use state_machine")

        val researchStep = workflow.workflow.find { it.step == "research_requirement_and_resources" }
            ?: fail("research_requirement_and_resources should exist")
        val researchExtract = researchStep.extract ?: fail("research step should extract reference and resource variables")
        assertEquals(setOf("reference_templates", "reusable_resources"), researchExtract.map { it.variable }.toSet())

        val designStep = workflow.workflow.find { it.step == "design_workflow_directly" }
            ?: fail("design_workflow_directly should exist")
        val designExtract = designStep.extract ?: fail("design step should extract planning variables")
        assertTrue(designExtract.any { it.variable == "complexity_level" })
        assertTrue(designExtract.any { it.variable == "step_list" })
        assertTrue(
            designStep.input?.contains("不要先做固定分类路由") == true,
            "design step should directly decide workflow structure"
        )

        val generateStep = workflow.workflow.find { it.step == "generate_workflow_directly" }
            ?: fail("generate_workflow_directly should exist")
        val repeatConfig = generateStep.repeatUntil ?: fail("generate_workflow_directly should use repeat_until")
        assertEquals("syntax_checker", repeatConfig.evaluateAgent)
        assertEquals("review_gate == PASS", repeatConfig.condition)
        assertEquals(3, repeatConfig.maxIterations)

        val finalValidationStep = workflow.workflow.find { it.step == "final_validation" }
            ?: fail("final_validation should exist")
        val finalValidationExtract = finalValidationStep.extract ?: fail("final_validation should extract validation metrics")
        assertTrue(finalValidationExtract.any { it.variable == "validation_result" })
        assertTrue(finalValidationExtract.any { it.variable == "total_steps" })
        assertTrue(finalValidationExtract.any { it.variable == "total_agents" })

        val readmeStep = workflow.workflow.find { it.step == "write_workflow_readme" }
            ?: fail("write_workflow_readme should exist")
        assertEquals("validation_result == PASSED", readmeStep.condition)

        val blockersStep = workflow.workflow.find { it.step == "write_validation_blockers" }
            ?: fail("write_validation_blockers should exist")
        assertEquals("validation_result == FAILED", blockersStep.condition)

        val summaryStep = workflow.workflow.find { it.step == "delivery_summary" }
            ?: fail("delivery_summary should exist")
        assertTrue(summaryStep.dependsOn.contains("write_workflow_readme"))
        assertTrue(summaryStep.dependsOn.contains("write_validation_blockers"))

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Direct workflow generator template validated successfully")
    }

    // ========== 新功能测试模板验证 ==========

    @Test
    fun `test group chat workflow template`() {
        val workflow = parseTemplate("test-workflow-group-chat.yaml")

        assertEquals("test-workflow-group-chat", workflow.name)
        assertEquals("1.0.0", workflow.version)

        // Validate agents
        assertTrue(workflow.agents.containsKey("pm"))
        assertTrue(workflow.agents.containsKey("designer"))
        assertTrue(workflow.agents.containsKey("engineer"))
        assertTrue(workflow.agents.containsKey("qa"))

        // Validate steps
        assertEquals(4, workflow.workflow.size)
        val stepNames = workflow.workflow.map { it.step }
        assertTrue(stepNames.contains("feature_discussion"))
        assertTrue(stepNames.contains("technical_brainstorm"))
        assertTrue(stepNames.contains("final_review"))
        assertTrue(stepNames.contains("write_summary"))

        // Validate group_chat configurations
        val step1 = workflow.workflow.find { it.step == "feature_discussion" }!!
        assertTrue(step1.isGroupChat)
        val groupChat1 = step1.groupChat ?: fail("feature_discussion.groupChat should exist")
        assertEquals(3, groupChat1.participants.size)
        assertEquals("pm", groupChat1.moderator)
        assertEquals("round_robin", groupChat1.speakerSelection)
        assertEquals("pm", groupChat1.summaryAgent)
        assertEquals(3, groupChat1.maxRounds)

        val step2 = workflow.workflow.find { it.step == "technical_brainstorm" }!!
        assertTrue(step2.isGroupChat)
        val groupChat2 = step2.groupChat ?: fail("technical_brainstorm.groupChat should exist")
        assertEquals("random", groupChat2.speakerSelection)

        val step3 = workflow.workflow.find { it.step == "final_review" }!!
        assertTrue(step3.isGroupChat)
        val groupChat3 = step3.groupChat ?: fail("final_review.groupChat should exist")
        assertEquals(2, groupChat3.participants.size)
        assertNull(groupChat3.moderator)

        // Last step is regular agent step
        val step4 = workflow.workflow.find { it.step == "write_summary" }!!
        assertFalse(step4.isGroupChat)
        assertEquals("pm", step4.agent)

        // Validate parser
        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Group Chat test template validated successfully")
    }

    @Test
    fun `test repeat until workflow template`() {
        val workflow = parseTemplate("test-workflow-repeat-until.yaml")

        assertEquals("test-workflow-repeat-until", workflow.name)
        assertEquals(3, workflow.workflow.size)

        // Step 1: repeat_until with evaluate_agent
        val step1 = workflow.workflow.find { it.step == "draft_article" }!!
        val repeatUntil1 = step1.repeatUntil ?: fail("draft_article.repeatUntil should exist")
        assertEquals("reviewer", repeatUntil1.evaluateAgent)
        assertNotNull(repeatUntil1.evaluatePrompt)
        assertEquals("quality_score=(\\d+)", repeatUntil1.extractPattern)
        assertEquals("quality_score", repeatUntil1.extractVariable)
        assertEquals(3, repeatUntil1.maxIterations)

        // Step 2: repeat_until without evaluate_agent
        val step2 = workflow.workflow.find { it.step == "refine_summary" }!!
        val repeatUntil2 = step2.repeatUntil ?: fail("refine_summary.repeatUntil should exist")
        assertNull(repeatUntil2.evaluateAgent)
        assertEquals("summary_done=(\\w+)", repeatUntil2.extractPattern)
        assertEquals("summary_done", repeatUntil2.extractVariable)

        // Step 3: regular step referencing repeat_until outputs
        val step3 = workflow.workflow.find { it.step == "final_edit" }!!
        assertNull(step3.repeatUntil)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Repeat Until test template validated successfully")
    }

    @Test
    fun `test agent based workflow template`() {
        val workflow = parseTemplate("test-workflow-agent-based.yaml")

        assertEquals("test-workflow-agent-based", workflow.name)
        assertEquals(4, workflow.workflow.size)

        // Step 1: regular step
        val step1 = workflow.workflow.find { it.step == "gather_requirements" }!!
        assertFalse(step1.isAgentBased)

        // Step 2: agent_based with full config
        val step2 = workflow.workflow.find { it.step == "dynamic_implementation" }!!
        assertTrue(step2.isAgentBased)
        val agentBased2 = step2.agentBased ?: fail("dynamic_implementation.agentBased should exist")
        assertEquals(listOf("developer", "tester"), agentBased2.participants)
        assertEquals(10, agentBased2.maxSteps)
        assertEquals(100000L, agentBased2.budgetTokens)
        assertEquals(300, agentBased2.timeoutSeconds)
        assertNotNull(agentBased2.orchestrator)
        assertEquals("universal", agentBased2.orchestrator.preset)

        // Step 3: agent_based with minimal config
        val step3 = workflow.workflow.find { it.step == "quick_review" }!!
        assertTrue(step3.isAgentBased)
        val agentBased3 = step3.agentBased ?: fail("quick_review.agentBased should exist")
        assertEquals(5, agentBased3.maxSteps)
        assertEquals(0L, agentBased3.budgetTokens) // default
        assertNull(agentBased3.timeoutSeconds) // not set

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Agent-based test template validated successfully")
    }

    @Test
    fun `test code steps workflow template`() {
        val workflow = parseTemplate("test-workflow-code-steps.yaml")

        assertEquals("test-workflow-code-steps", workflow.name)
        assertEquals(5, workflow.workflow.size)

        // Step 1: Python code
        val step1 = workflow.workflow.find { it.step == "python_data_processing" }!!
        assertTrue(step1.isCode)
        val code1 = step1.code ?: fail("python_data_processing.code should exist")
        assertEquals("python", code1.language)
        assertNotNull(code1.script)
        assertNull(code1.scriptFile)
        assertEquals(15, code1.timeout)

        // Step 2: Bash code
        val step2 = workflow.workflow.find { it.step == "bash_setup" }!!
        assertTrue(step2.isCode)
        val code2 = step2.code ?: fail("bash_setup.code should exist")
        assertEquals("bash", code2.language)

        // Step 3: JavaScript code
        val step3 = workflow.workflow.find { it.step == "js_transform" }!!
        assertTrue(step3.isCode)
        val code3 = step3.code ?: fail("js_transform.code should exist")
        assertEquals("javascript", code3.language)

        // Step 4: Code with extract
        val step4 = workflow.workflow.find { it.step == "python_with_extract" }!!
        assertTrue(step4.isCode)
        val extract4 = step4.extract ?: fail("python_with_extract.extract should exist")
        assertEquals(2, extract4.size)
        assertEquals("validation_result", extract4[0].variable)
        assertEquals("total_count", extract4[1].variable)

        // Step 5: Agent step using code outputs
        val step5 = workflow.workflow.find { it.step == "generate_report" }!!
        assertFalse(step5.isCode)
        assertEquals("helper", step5.agent)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Code Steps test template validated successfully")
    }

    @Test
    fun `test extract workflow template`() {
        val workflow = parseTemplate("test-workflow-extract.yaml")

        assertEquals("test-workflow-extract", workflow.name)
        assertEquals(4, workflow.workflow.size)

        // Step 1: Agent step with regex extract (4 variables)
        val step1 = workflow.workflow.find { it.step == "analyze_data" }!!
        val extract1 = step1.extract ?: fail("analyze_data.extract should exist")
        assertEquals(4, extract1.size)
        assertTrue(extract1.all { it.pattern != null })
        assertTrue(extract1.all { it.jsonPath == null })

        // Step 2: Code step with JSON path extract
        val step2 = workflow.workflow.find { it.step == "compute_metrics" }!!
        val extract2 = step2.extract ?: fail("compute_metrics.extract should exist")
        assertEquals(3, extract2.size)
        assertTrue(extract2.all { it.jsonPath != null })
        assertTrue(extract2.all { it.pattern == null })

        // Step 3: Code step with regex extract
        val step3 = workflow.workflow.find { it.step == "validate_results" }!!
        val extract3 = step3.extract ?: fail("validate_results.extract should exist")
        assertEquals(2, extract3.size)

        // Step 4: Agent step referencing all extracted variables
        val step4 = workflow.workflow.find { it.step == "final_report" }!!
        assertNull(step4.extract)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Extract test template validated successfully")
    }

    @Test
    fun `test classifier workflow template`() {
        val workflow = parseTemplate("test-workflow-classifier.yaml")

        assertEquals("test-workflow-classifier", workflow.name)
        assertEquals(5, workflow.workflow.size)

        // Step 1: Classifier
        val step1 = workflow.workflow.find { it.step == "classify_ticket" }!!
        assertTrue(step1.isClassifier)
        val classifier = step1.classifier ?: fail("classify_ticket.classifier should exist")
        assertEquals("classifier_agent", classifier.agent)
        assertEquals(3, classifier.categories.size)
        assertEquals("ticket_category", classifier.outputVariable)
        assertEquals("general", classifier.defaultCategory)

        val categoryNames = classifier.categories.map { it.name }
        assertTrue(categoryNames.contains("technical"))
        assertTrue(categoryNames.contains("billing"))
        assertTrue(categoryNames.contains("general"))

        // Steps 2-4: Conditional branches
        val techStep = workflow.workflow.find { it.step == "handle_technical" }!!
        assertEquals("ticket_category == 'technical'", techStep.condition)

        val billingStep = workflow.workflow.find { it.step == "handle_billing" }!!
        assertEquals("ticket_category == 'billing'", billingStep.condition)

        val generalStep = workflow.workflow.find { it.step == "handle_general" }!!
        assertEquals("ticket_category == 'general'", generalStep.condition)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Classifier test template validated successfully")
    }

    @Test
    fun `test iterate over workflow template`() {
        val workflow = parseTemplate("test-workflow-iterate-over.yaml")

        assertEquals("test-workflow-iterate-over", workflow.name)
        assertEquals(4, workflow.workflow.size)

        // Step 1: Serial iteration with comma delimiter
        val step1 = workflow.workflow.find { it.step == "process_tasks_serial" }!!
        val iterateOver1 = step1.iterateOver ?: fail("process_tasks_serial.iterateOver should exist")
        assertEquals(",", iterateOver1.delimiter)
        assertEquals("task_item", iterateOver1.itemVariable)
        assertEquals("task_index", iterateOver1.indexVariable)
        assertEquals(10, iterateOver1.maxItems)
        assertFalse(iterateOver1.parallel)
        assertEquals("serial_results", iterateOver1.resultsVariable)

        // Step 3: Parallel iteration with newline delimiter
        val step3 = workflow.workflow.find { it.step == "parallel_check" }!!
        val iterateOver3 = step3.iterateOver ?: fail("parallel_check.iterateOver should exist")
        assertEquals("\n", iterateOver3.delimiter)
        assertTrue(iterateOver3.parallel)
        assertEquals(3, iterateOver3.maxParallel)
        assertEquals("check_results", iterateOver3.resultsVariable)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Iterate Over test template validated successfully")
    }

    @Test
    fun `test aggregate workflow template`() {
        val workflow = parseTemplate("test-workflow-aggregate.yaml")

        assertEquals("test-workflow-aggregate", workflow.name)
        assertEquals(8, workflow.workflow.size)

        // Step 4: concat strategy
        val concatStep = workflow.workflow.find { it.step == "concat_aggregate" }!!
        val concatAggregate = concatStep.aggregate ?: fail("concat_aggregate.aggregate should exist")
        assertEquals("concat", concatAggregate.strategy)
        assertEquals(3, concatAggregate.sources.size)
        assertEquals("concat_reviews", concatAggregate.outputVariable)

        // Step 5: numbered_list strategy
        val numberedStep = workflow.workflow.find { it.step == "numbered_aggregate" }!!
        val numberedAggregate = numberedStep.aggregate ?: fail("numbered_aggregate.aggregate should exist")
        assertEquals("numbered_list", numberedAggregate.strategy)

        // Step 6: json_array strategy + code + extract
        val jsonStep = workflow.workflow.find { it.step == "json_aggregate" }!!
        val jsonAggregate = jsonStep.aggregate ?: fail("json_aggregate.aggregate should exist")
        assertEquals("json_array", jsonAggregate.strategy)
        assertTrue(jsonStep.isCode)
        assertNotNull(jsonStep.extract)

        // Step 7: pick_longest strategy
        val longestStep = workflow.workflow.find { it.step == "longest_review" }!!
        val longestAggregate = longestStep.aggregate ?: fail("longest_review.aggregate should exist")
        assertEquals("pick_longest", longestAggregate.strategy)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Aggregate test template validated successfully")
    }

    @Test
    fun `test knowledge base workflow template`() {
        val workflow = parseTemplate("test-workflow-knowledge-base.yaml")

        assertEquals("test-workflow-knowledge-base", workflow.name)
        assertEquals(4, workflow.workflow.size)

        // Validate knowledge_base config
        val knowledgeBase = workflow.knowledgeBase ?: fail("workflow.knowledgeBase should exist")
        assertTrue(knowledgeBase.enabled)
        assertEquals("./kb-test", knowledgeBase.storageDir)
        assertEquals("text-embedding-3-small", knowledgeBase.embeddingModel)
        assertEquals("openai", knowledgeBase.embeddingProvider)
        assertTrue(knowledgeBase.autoIndexOutputs)
        assertTrue(knowledgeBase.autoInjectRagTools)
        assertEquals(500, knowledgeBase.chunkSize)
        assertEquals(50, knowledgeBase.chunkOverlap)
        assertEquals(100, knowledgeBase.maxIndexedDocuments)
        assertEquals(1000, knowledgeBase.maxTotalChunks)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Knowledge Base test template validated successfully")
    }

    @Test
    fun `test state machine workflow template`() {
        val workflow = parseTemplate("test-workflow-state-machine.yaml")

        assertEquals("test-workflow-state-machine", workflow.name)
        val stateMachineStep = workflow.workflow.find { it.stateMachine != null }
            ?: fail("state machine step should exist")
        val stateMachine = stateMachineStep.stateMachine ?: fail("review_flow.stateMachine should exist")
        assertEquals("review_flow", stateMachineStep.step)
        assertEquals("classify", stateMachine.initialState)
        assertTrue(stateMachine.states.containsKey("classify"))
        assertTrue(stateMachine.states.containsKey("revise"))

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ State machine test template validated successfully")
    }

    @Test
    fun `test all features combined workflow template`() {
        val workflow = parseTemplate("test-workflow-all-features.yaml")

        assertEquals("test-workflow-all-features", workflow.name)
        assertEquals(9, workflow.workflow.size)

        // Verify all feature types are present
        val steps = workflow.workflow

        // Classifier
        val classifierStep = steps.find { it.isClassifier }
            ?: fail("Should have a classifier step")
        assertEquals("classify_project", classifierStep.step)

        // Code
        val codeSteps = steps.filter { it.isCode }
        assertTrue(codeSteps.size >= 2, "Should have at least 2 code steps")

        // Group Chat
        val groupChatStep = steps.find { it.isGroupChat }
            ?: fail("Should have a group_chat step")
        assertEquals("requirement_review", groupChatStep.step)

        // repeat_until
        val repeatStep = steps.find { it.repeatUntil != null }
            ?: fail("Should have a repeat_until step")
        assertEquals("design_iteration", repeatStep.step)

        // iterate_over
        val iterateStep = steps.find { it.iterateOver != null }
            ?: fail("Should have an iterate_over step")
        assertEquals("execute_tasks", iterateStep.step)

        // aggregate
        val aggregateStep = steps.find { it.aggregate != null }
            ?: fail("Should have an aggregate step")
        assertEquals("aggregate_results", aggregateStep.step)

        // extract
        val extractStep = steps.find { it.extract != null }
        assertNotNull(extractStep, "Should have an extract step")

        // knowledge_base
        val knowledgeBase = workflow.knowledgeBase ?: fail("workflow.knowledgeBase should exist")
        assertTrue(knowledgeBase.enabled)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        // Validate topological order
        val executionOrder = WorkflowParser.getTopologicalOrder(workflow)
        assertEquals(workflow.workflow.size, executionOrder.size)

        println("✅ All Features combined test template validated successfully")
    }

    @Test
    fun `test skill testing workflow template`() {
        val workflow = parseTemplate("test-workflow-skill-testing.yaml")

        assertEquals("test-workflow-skill-testing", workflow.name)
        assertEquals("1.0.0", workflow.version)
        assertEquals("file_or_text", workflow.variableTypes["test_request"])
        assertEquals("file_or_text", workflow.variableTypes["success_criteria"])
        assertTrue(workflow.codePreamble.containsKey("python"))

        val skillRunner = workflow.agents["skill_runner"] ?: fail("skill_runner should exist")
        val skillRunnerParams = skillRunner.resolveParameters()
        val skillsConfig = skillRunnerParams["skills_config"] as? JsonObject
            ?: fail("skill_runner should expose skills_config through overrides")
        assertEquals(true, skillsConfig["skillWhitelistMode"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, skillsConfig["builtinSkillsEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, skillsConfig["hooksEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, skillsConfig["scanStandardPaths"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(
            "{{var:skill_name}}",
            skillsConfig["enabledSkills"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        )

        val prepareStep = workflow.workflow.find { it.step == "prepare_test_context" }
            ?: fail("prepare_test_context should exist")
        assertTrue(prepareStep.isCode)
        val prepareExtract = prepareStep.extract ?: fail("prepare_test_context.extract should exist")
        assertTrue(prepareExtract.any { it.variable == "skill_present" })
        assertTrue(prepareExtract.any { it.variable == "report_file_path" })

        val executeStep = workflow.workflow.find { it.step == "execute_skill_test" }
            ?: fail("execute_skill_test should exist")
        assertEquals("skill_runner", executeStep.agent)
        assertEquals("skill_present == 'YES'", executeStep.condition)
        val executeExtract = executeStep.extract ?: fail("execute_skill_test.extract should exist")
        assertTrue(executeExtract.any { it.variable == "skill_loaded" })
        assertTrue(executeExtract.any { it.variable == "runner_status" })

        val ensureReportStep = workflow.workflow.find { it.step == "ensure_test_report" }
            ?: fail("ensure_test_report should exist")
        assertTrue(ensureReportStep.isCode)
        val ensureReportExtract = ensureReportStep.extract ?: fail("ensure_test_report.extract should exist")
        assertTrue(ensureReportExtract.any { it.variable == "report_ready" })
        assertTrue(ensureReportExtract.any { it.variable == "report_source" })

        val deterministicStep = workflow.workflow.find { it.step == "deterministic_validation" }
            ?: fail("deterministic_validation should exist")
        assertTrue(deterministicStep.isCode)
        assertTrue(deterministicStep.dependsOn.contains("ensure_test_report"))
        val deterministicExtract = deterministicStep.extract ?: fail("deterministic_validation.extract should exist")
        assertTrue(deterministicExtract.any { it.variable == "deterministic_status" })
        assertTrue(deterministicExtract.any { it.variable == "markers_matched" })

        val semanticStep = workflow.workflow.find { it.step == "semantic_review" }
            ?: fail("semantic_review should exist")
        assertTrue(semanticStep.isCode)
        assertTrue(semanticStep.dependsOn.contains("deterministic_validation"))

        val finalStep = workflow.workflow.find { it.step == "finalize_test_result" }
            ?: fail("finalize_test_result should exist")
        assertTrue(finalStep.isCode)

        assertDoesNotThrow { WorkflowParser.validateWorkflow(workflow) }

        println("✅ Skill Testing test template validated successfully")
    }

    // ========== 全局验证测试 ==========

    @Test
    fun `test all templates have valid agent references`() {
        val templates = listOf(
            "ios-app-full-development.yaml",
            "android-app-full-development.yaml",
            "aso-optimization-workflow.yaml",
            "user-feedback-bug-triaging.yaml",
            "social-media-ad-campaign.yaml",
            "flutter-cross-platform-development.yaml",
            "google-ads-app-campaign.yaml",
            "apple-search-ads-campaign.yaml",
            "ci-cd-pipeline.yaml",
            "code-review.yaml",
            "data-analysis.yaml",
            "prd-review-refinement.yaml",
            "user-growth-strategy.yaml",
            "content-marketing-seo.yaml",
            "backend-service-design.yaml",
            "opc-product-launch-marketing.yaml",
            "opc-content-social-media.yaml",
            "opc-customer-feedback-automation.yaml",
            "opc-brand-identity-strategy.yaml",
            "test-workflow-group-chat.yaml",
            "test-workflow-repeat-until.yaml",
            "test-workflow-agent-based.yaml",
            "test-workflow-code-steps.yaml",
            "test-workflow-extract.yaml",
            "test-workflow-classifier.yaml",
            "test-workflow-iterate-over.yaml",
            "test-workflow-aggregate.yaml",
            "test-workflow-state-machine.yaml",
            "test-workflow-knowledge-base.yaml",
            "test-workflow-all-features.yaml",
            "test-workflow-skill-testing.yaml",
            "ticket-triage-state-machine.yaml",
            "approval-lifecycle-state-machine.yaml",
            "openclaw-ai-assistant.yaml",
            "water-treatment-plant-process.yaml",
            "water-system-electrical-automation.yaml",
            "water-quality-monitoring-emergency.yaml"
        ).map(::resolveTemplatePath)

        for (templatePath in templates) {
            val workflow = WorkflowParser.parseFile(templatePath)

            // Collect all agent references from workflow steps
            val referencedAgents = workflow.workflow.flatMap { it.referencedAgents }.toSet()

            // Verify all referenced agents are defined
            for (agentName in referencedAgents) {
                assertTrue(
                    workflow.agents.containsKey(agentName),
                    "Agent '$agentName' referenced in workflow but not defined in agents section of $templatePath"
                )
            }

            println("✅ All agent references valid in $templatePath")
        }
    }

    @Test
    fun `test all templates have valid variable interpolation`() {
        val templates = listOf(
            "ios-app-full-development.yaml",
            "android-app-full-development.yaml",
            "aso-optimization-workflow.yaml",
            "user-feedback-bug-triaging.yaml",
            "social-media-ad-campaign.yaml",
            "flutter-cross-platform-development.yaml",
            "google-ads-app-campaign.yaml",
            "apple-search-ads-campaign.yaml",
            "ci-cd-pipeline.yaml",
            "code-review.yaml",
            "data-analysis.yaml",
            "prd-review-refinement.yaml",
            "user-growth-strategy.yaml",
            "content-marketing-seo.yaml",
            "backend-service-design.yaml",
            "opc-product-launch-marketing.yaml",
            "opc-content-social-media.yaml",
            "opc-customer-feedback-automation.yaml",
            "opc-brand-identity-strategy.yaml",
            "test-workflow-group-chat.yaml",
            "test-workflow-repeat-until.yaml",
            "test-workflow-agent-based.yaml",
            "test-workflow-code-steps.yaml",
            "test-workflow-extract.yaml",
            "test-workflow-classifier.yaml",
            "test-workflow-iterate-over.yaml",
            "test-workflow-aggregate.yaml",
            "test-workflow-state-machine.yaml",
            "test-workflow-knowledge-base.yaml",
            "test-workflow-all-features.yaml",
            "test-workflow-skill-testing.yaml",
            "ticket-triage-state-machine.yaml",
            "approval-lifecycle-state-machine.yaml",
            "openclaw-ai-assistant.yaml",
            "water-treatment-plant-process.yaml",
            "water-system-electrical-automation.yaml",
            "water-quality-monitoring-emergency.yaml"
        ).map(::resolveTemplatePath)

        val variablePattern = Regex("\\{\\{([^}]+)\\}\\}")

        for (templatePath in templates) {
            val workflow = WorkflowParser.parseFile(templatePath)

            // Collect variables that are dynamically set at runtime:
            // - directory isolation injected paths
            // - repeat_until extract_variable
            // - extract[].variable (generalized extract)
            // - classifier output_variable
            // - aggregate output_variable
            // - iterate_over item_variable, index_variable, results_variable
            val dynamicVariables = mutableSetOf<String>()
            if (workflow.directoryIsolation.enabled) {
                dynamicVariables.add("working_dir")
                dynamicVariables.add("output_dir")
                dynamicVariables.add("persistence_storage_root")
            }
            workflow.workflow.forEach { s ->
                dynamicVariables.add(s.step)
                s.repeatUntil?.extractVariable?.let { dynamicVariables.add(it) }
                s.extract?.forEach { e -> dynamicVariables.add(e.variable) }
                s.classifier?.outputVariable?.let { dynamicVariables.add(it) }
                s.aggregate?.outputVariable?.let { dynamicVariables.add(it) }
                s.iterateOver?.let { io ->
                    dynamicVariables.add(io.itemVariable)
                    dynamicVariables.add(io.indexVariable)
                    io.resultsVariable?.let { dynamicVariables.add(it) }
                }
                s.stateMachine?.let { sm ->
                    dynamicVariables.add("${s.step}_current_state")
                    dynamicVariables.add("${s.step}_history_size")
                    dynamicVariables.add("${s.step}_final_state")
                    dynamicVariables.add("${s.step}_transition_count")
                    dynamicVariables.add("${s.step}_history")
                    dynamicVariables.add("${s.step}_last_output")
                    dynamicVariables.add("${s.step}_last_error")

                    sm.states.forEach { (stateName, stateDef) ->
                        dynamicVariables.add("${s.step}.$stateName")
                        dynamicVariables.add(
                            "${s.step}_${stateName.replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').ifBlank { "state" }}_output"
                        )
                        stateDef.stepConfig?.extract?.forEach { extract ->
                            dynamicVariables.add(extract.variable)
                        }
                        stateDef.stepConfig?.classifier?.outputVariable?.let { dynamicVariables.add(it) }
                    }
                }
            }

            for (step in workflow.workflow) {
                val inputsToCheck = mutableListOf<Pair<String, String>>()
                step.input?.let { inputsToCheck.add(step.step to it) }
                step.groupChat?.initialMessage?.let { inputsToCheck.add(step.step to it) }
                step.classifier?.input?.let { inputsToCheck.add(step.step to it) }
                step.stateMachine?.states?.forEach { (stateName, stateDef) ->
                    stateDef.stepConfig?.input?.let { inputsToCheck.add("${step.step}.$stateName" to it) }
                    stateDef.stepConfig?.groupChat?.initialMessage?.let {
                        inputsToCheck.add("${step.step}.$stateName" to it)
                    }
                    stateDef.stepConfig?.classifier?.input?.let {
                        inputsToCheck.add("${step.step}.$stateName" to it)
                    }
                }

                for ((owner, template) in inputsToCheck) {
                    val matches = variablePattern.findAll(template)

                    for (match in matches) {
                        val varRef = match.groupValues[1].trim()

                        if (!varRef.startsWith("steps.")) {
                            if (varRef == "task" && step.parallel != null) {
                                continue
                            }

                            val actualVarName = if (varRef.startsWith("var:")) varRef.removePrefix("var:") else varRef

                            if (actualVarName in dynamicVariables) {
                                continue
                            }

                            assertTrue(
                                workflow.variables.containsKey(actualVarName),
                                "Variable '$actualVarName' referenced in step '$owner' but not defined in variables section of $templatePath"
                            )
                        }
                    }
                }
            }

            println("✅ All variable references valid in $templatePath")
        }
    }

    @Test
    fun `test all template agents declare valid session strategy`() {
        val validStrategies = setOf("auto", "per_execution", "per_agent", "fixed")
        val templateFiles = allTemplateFiles()

        fun assertValidStrategy(
            owner: String,
            agentName: String,
            agentDef: AgentDefinition,
            referencedAgents: Map<String, AgentDefinition>? = null
        ) {
            val strategy = (agentDef.resolveParameters(referencedAgents)["session_id_strategy"] as? JsonPrimitive)?.content
            assertNotNull(strategy, "$owner agent '$agentName' should declare session_id_strategy")
            assertTrue(
                strategy in validStrategies,
                "$owner agent '$agentName' should use a valid session_id_strategy, got '$strategy'"
            )
        }

        fun validateStepAgents(
            templateName: String,
            steps: List<WorkflowStep>,
            referencedAgents: Map<String, AgentDefinition>
        ) {
            steps.forEach { step ->
                step.agentBased?.orchestrator?.let { orchestrator ->
                    assertValidStrategy(
                        templateName,
                        "${step.step}.agent_based.orchestrator",
                        orchestrator,
                        referencedAgents
                    )
                }

                step.stateMachine?.states?.forEach { (stateName, stateDef) ->
                    stateDef.stepConfig?.let { nestedStep ->
                        nestedStep.agentBased?.orchestrator?.let { orchestrator ->
                            assertValidStrategy(
                                templateName,
                                "${step.step}.$stateName.agent_based.orchestrator",
                                orchestrator,
                                referencedAgents
                            )
                        }
                    }
                }
            }
        }

        templateFiles.forEach { file ->
            val templatePath = resolveTemplatePath(file.name)
            val workflow = WorkflowParser.parseFile(templatePath)

            workflow.agents.forEach { (agentName, agentDef) ->
                assertValidStrategy(file.name, agentName, agentDef, workflow.agents)
            }

            workflow.globalAgent?.agent?.let { globalAgent ->
                assertValidStrategy(file.name, "global_agent.agent", globalAgent, workflow.agents)
            }

            validateStepAgents(file.name, workflow.workflow, workflow.agents)
            println("✅ All agent session strategies valid in $templatePath")
        }
    }
}
