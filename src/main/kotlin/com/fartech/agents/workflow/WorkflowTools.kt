package com.fartech.agents.workflow

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.agents.jev.JevClientFactory
import com.fartech.agents.jev.JevCredentials
import com.fartech.agents.tools.ClaudeCredentialProvider
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Host-owned runtime of the nested [WorkflowExecutor] behind [WorkflowTools.executeWorkflow].
 * Each field has the same meaning as the same-named [WorkflowExecutor] constructor param.
 *
 * A [WorkflowExecutor] builds this from its own constructor fields when an agent's tool set
 * contains `workflow`, so a nested run keeps the host's sandbox (`code:` steps through
 * [codeStepExecutor] instead of a bare ProcessBuilder on the host), egress proxy env,
 * credential pools, callback-token minting, sub-workflow resolution and Jev policy.
 * Host-only on purpose: never derived from ConfigurationParameters, which are user-controlled
 * on the web. The defaults match a standalone executor (CLI / library use).
 */
class NestedWorkflowRuntime(
    val codeStepExecutor: SubprocessExecutor? = null,
    val extraCodeStepEnv: Map<String, String> = emptyMap(),
    val claudeCredentialProvider: ClaudeCredentialProvider? = null,
    val codexCredentialProvider: ClaudeCredentialProvider? = null,
    val onCodexAuthJsonRotated: ((credentialId: String?, authJson: String) -> Unit)? = null,
    val executionApiTokenProvider: ((Long) -> String)? = null,
    val workflowResolver: WorkflowResolver? = null,
    val jevCredentials: JevCredentials? = null,
    val jevClientFactory: JevClientFactory = JevClientFactory.Default,
    val jevEnvKeyFallback: Boolean = true
)

@LLMDescription("Toolset for defining and executing complex multi-agent workflows")
class WorkflowTools(
    private val httpAccess: HttpAccess,
    private val parameters: List<ConfigurationParameter>,
    /** Runtime inherited from the host executor that owns this tool; see [NestedWorkflowRuntime]. */
    private val runtime: NestedWorkflowRuntime = NestedWorkflowRuntime()
) : ToolSet {

    private val executor = WorkflowExecutor(
        httpAccess,
        parameters,
        enableMonitoring = true,
        workflowResolver = runtime.workflowResolver,
        codeStepExecutor = runtime.codeStepExecutor,
        extraCodeStepEnv = runtime.extraCodeStepEnv,
        claudeCredentialProvider = runtime.claudeCredentialProvider,
        codexCredentialProvider = runtime.codexCredentialProvider,
        onCodexAuthJsonRotated = runtime.onCodexAuthJsonRotated,
        executionApiTokenProvider = runtime.executionApiTokenProvider,
        jevCredentials = runtime.jevCredentials,
        jevClientFactory = runtime.jevClientFactory,
        jevEnvKeyFallback = runtime.jevEnvKeyFallback
    )
    private val versionControl = WorkflowVersionControl()
    private val templatesDir: String = parameters.parameter("workflow_templates_dir", "./workflows/templates")

    @Tool
    @LLMDescription("Execute a workflow defined in a YAML file")
    suspend fun executeWorkflow(
        @LLMDescription("Path to the workflow YAML file")
        workflowPath: String,

        @LLMDescription("Initial input variables as comma-separated key=value pairs, e.g. 'topic=AI,language=zh'")
        inputs: String = ""
    ): String {
        return try {
            val workflow = WorkflowParser.parseFile(workflowPath)

            val inputMap = parseKeyValueString(inputs)

            val result = executor.execute(workflow, inputMap)

            if (result.success) {
                buildSuccessReport(result)
            } else {
                "❌ Workflow '${result.workflowName}' failed: ${result.error}"
            }
        } catch (e: Exception) {
            "❌ Failed to execute workflow: ${e.message}\n${e.stackTraceToString()}"
        }
    }

    @Tool
    @LLMDescription("Validate a workflow YAML file for syntax and logic errors")
    suspend fun validateWorkflow(
        @LLMDescription("Path to the workflow YAML file")
        workflowPath: String
    ): String {
        return try {
            val workflow = WorkflowParser.parseFile(workflowPath)
            WorkflowParser.validateWorkflow(workflow)

            buildString {
                appendLine("✅ Workflow validation passed")
                appendLine()
                appendLine(WorkflowParser.getWorkflowSummary(workflow))
            }
        } catch (e: WorkflowValidationException) {
            "❌ Validation failed: ${e.message}"
        } catch (e: Exception) {
            "❌ Error validating workflow: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get detailed information about a workflow definition")
    suspend fun describeWorkflow(
        @LLMDescription("Path to the workflow YAML file")
        workflowPath: String
    ): String {
        return try {
            val workflow = WorkflowParser.parseFile(workflowPath)

            buildString {
                appendLine("# Workflow: ${workflow.name}")
                appendLine("Version: ${workflow.version}")
                workflow.description?.let { appendLine("Description: $it") }
                appendLine()

                appendLine("## Agents (${workflow.agents.size})")
                workflow.agents.forEach { (name, agent) ->
                    appendLine("### $name")
                    if (agent.preset != null) {
                        appendLine("- Preset: ${agent.preset}")
                        if (agent.overrides.isNotEmpty()) {
                            appendLine("- Overrides: ${agent.overrides.keys.joinToString(", ")}")
                        }
                    } else {
                        appendLine("- Type: ${agent.type}")
                        appendLine("- Strategy: ${agent.strategy}")
                        agent.llm?.let { appendLine("- LLM: ${it.provider}/${it.model}") }
                        if (agent.tools.isNotEmpty()) {
                            appendLine("- Tools: ${agent.tools.joinToString(", ")}")
                        }
                    }
                    appendLine()
                }

                appendLine("## Execution Plan")
                val executionOrder = WorkflowParser.getTopologicalOrder(workflow)
                executionOrder.forEachIndexed { index, step ->
                    appendLine("${index + 1}. **${step.step}** (${step.displayAgentName})")
                    if (step.dependsOn.isNotEmpty()) {
                        appendLine("   - Depends on: ${step.dependsOn.joinToString(", ")}")
                    }
                    if (step.condition != null) {
                        appendLine("   - Condition: ${step.condition}")
                    }
                    if (step.parallel != null) {
                        appendLine("   - Parallel: ${step.parallel.tasks.size} tasks")
                    }
                }
            }
        } catch (e: Exception) {
            "❌ Error describing workflow: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("List all available workflow templates")
    suspend fun listWorkflowTemplates(): String {
        val templatesDirectory = File(templatesDir)
        if (!templatesDirectory.exists() || !templatesDirectory.isDirectory) {
            return "❌ Templates directory not found: $templatesDir"
        }

        val templates = templatesDirectory.listFiles()
            ?.filter { it.extension == "yaml" || it.extension == "yml" }
            ?.map { file ->
                try {
                    val workflow = WorkflowParser.parseFile(file.absolutePath)
                    WorkflowTemplate(
                        name = workflow.name,
                        file = file.name,
                        description = workflow.description ?: "No description",
                        agents = workflow.agents.size,
                        steps = workflow.workflow.size
                    )
                } catch (e: Exception) {
                    WorkflowTemplate(
                        name = file.nameWithoutExtension,
                        file = file.name,
                        description = "Error: ${e.message}",
                        agents = 0,
                        steps = 0
                    )
                }
            } ?: emptyList()

        if (templates.isEmpty()) {
            return "No workflow templates found in $templatesDir"
        }

        return buildString {
            appendLine("📁 Available Workflow Templates ($templatesDir)")
            appendLine()
            templates.forEach { template ->
                appendLine("**${template.name}** (${template.file})")
                appendLine("  ${template.description}")
                appendLine("  Agents: ${template.agents}, Steps: ${template.steps}")
                appendLine()
            }
        }
    }

    @Tool
    @LLMDescription("Create a new workflow from a template")
    suspend fun createWorkflowFromTemplate(
        @LLMDescription("Template file name as listed by listWorkflowTemplates, without the .yaml extension and without directories")
        templateName: String,

        @LLMDescription("Output path for the new .yaml/.yml workflow file, inside the working directory")
        outputPath: String,

        @LLMDescription("Variables to substitute in the template as comma-separated key=value pairs, e.g. 'topic=AI,language=zh'")
        variables: String = ""
    ): String {
        return try {
            val templateFile = resolveTemplateFile(templateName)
            val outputFile = resolveTemplateOutputFile(outputPath)
            val substitutions = parseKeyValueString(variables)
            substitutions.forEach { (key, value) ->
                // A value spanning lines could add YAML keys or steps next to the placeholder.
                require(value.none { it == '\n' || it == '\r' }) { "Variable '$key' must be a single line" }
            }

            if (!templateFile.exists()) {
                return "❌ Template not found: $templateName"
            }

            var content = templateFile.readText()

            // 替换变量
            substitutions.forEach { (key, value) ->
                content = content.replace("{{$key}}", value)
            }

            outputFile.writeText(content)

            "✅ Workflow created from template '$templateName' → ${outputFile.path}"
        } catch (e: Exception) {
            "❌ Failed to create workflow from template: ${e.message}"
        }
    }

    /** A template is a file directly inside [templatesDir], named without separators or `..`. */
    private fun resolveTemplateFile(templateName: String): File {
        require(templateName.isNotBlank()) { "Template name must not be blank" }
        require(".." !in templateName && templateName.none { it in "/\\:" || it.isISOControl() }) {
            "Invalid template name '$templateName': use a name from listWorkflowTemplates, without directories or '..'"
        }
        return File(templatesDir, "$templateName.yaml")
    }

    /**
     * Confines template output the way the sandboxed file tools confine writes: inside
     * `working_dir` / `output_dir`, or inside the process working directory when neither is set
     * (CLI runs). Relative paths resolve against the first root. Only `.yaml` / `.yml` files, so
     * the tool cannot drop scripts, hooks or dotfiles even inside those roots.
     */
    private fun resolveTemplateOutputFile(outputPath: String): File {
        require(outputPath.isNotBlank()) { "Output path must not be blank" }
        require(outputPath.none { it.isISOControl() }) { "Output path must not contain control characters" }
        require(File(outputPath).extension.lowercase() in setOf("yaml", "yml")) {
            "Output path must end with .yaml or .yml: $outputPath"
        }
        val roots = listOf("working_dir", "output_dir")
            .map { parameters.parameter(it, "") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(System.getProperty("user.dir")) }
            .map { File(it).canonicalFile }
        // canonicalFile also resolves symlinks, so a link inside a root cannot point the write elsewhere.
        val requested = File(outputPath).let { if (it.isAbsolute) it else File(roots.first(), outputPath) }.canonicalFile
        require(roots.any { requested.toPath().startsWith(it.toPath()) }) {
            "Output path '$outputPath' is outside the allowed directories: ${roots.joinToString(", ")}"
        }
        return requested
    }

    @Tool
    @LLMDescription("Visualize workflow execution graph in Mermaid format")
    suspend fun visualizeWorkflow(
        @LLMDescription("Path to the workflow YAML file")
        workflowPath: String
    ): String {
        return try {
            val workflow = WorkflowParser.parseFile(workflowPath)

            buildString {
                appendLine("```mermaid")
                appendLine("graph TD")
                appendLine("    Start([Start])")

                // 添加步骤节点
                workflow.workflow.forEach { step ->
                    val nodeId = step.step.replace(" ", "_")
                    appendLine("    $nodeId[\"${step.step}\\n(${step.displayAgentName})\"]")
                }

                // 添加依赖边
                val executionOrder = WorkflowParser.getTopologicalOrder(workflow)
                val firstStep = executionOrder.firstOrNull()
                if (firstStep != null) {
                    val firstNodeId = firstStep.step.replace(" ", "_")
                    appendLine("    Start --> $firstNodeId")
                }

                workflow.workflow.forEach { step ->
                    val nodeId = step.step.replace(" ", "_")

                    // 依赖关系
                    if (step.dependsOn.isNotEmpty()) {
                        step.dependsOn.forEach { dep ->
                            val depId = dep.replace(" ", "_")
                            appendLine("    $depId --> $nodeId")
                        }
                    }

                    // 转换动作
                    step.onSuccess.forEach { action ->
                        action.next?.let { next ->
                            val nextId = next.replace(" ", "_")
                            appendLine("    $nodeId -->|success| $nextId")
                        }
                    }

                    step.onFailure.forEach { action ->
                        action.next?.let { next ->
                            val nextId = next.replace(" ", "_")
                            appendLine("    $nodeId -->|failure| $nextId")
                        }
                    }
                }

                appendLine("    End([End])")

                // 连接最后的步骤到 End
                val lastSteps = workflow.workflow.filter { step ->
                    step.onSuccess.none { it.next != null } &&
                            step.onFailure.none { it.next != null }
                }
                lastSteps.forEach { step ->
                    val nodeId = step.step.replace(" ", "_")
                    appendLine("    $nodeId --> End")
                }

                appendLine("```")
            }
        } catch (e: Exception) {
            "❌ Error visualizing workflow: ${e.message}"
        }
    }

    /**
     * 构建成功报告
     */
    private fun buildSuccessReport(result: WorkflowExecutionResult): String = buildString {
        appendLine("✅ Workflow '${result.workflowName}' completed successfully")
        appendLine()
        appendLine("⏱️ Duration: ${result.durationSeconds}s")
        appendLine()
        appendLine("📊 Step Results:")
        result.stepResults.forEach { (stepName, stepResult) ->
            val status = if (stepResult.success) "✅" else "❌"
            appendLine("  $status $stepName (${stepResult.durationSeconds}s)")
            if (stepResult.retryCount > 0) {
                appendLine("     ↻ Retried ${stepResult.retryCount} times")
            }
            if (stepResult.output != null) {
                val preview = stepResult.output.take(100)
                appendLine("     Output: $preview${if (stepResult.output.length > 100) "..." else ""}")
            }
        }

        if (result.variables.isNotEmpty()) {
            appendLine()
            appendLine("📝 Variables:")
            result.variables.forEach { (key, value) ->
                appendLine("  - $key: $value")
            }
        }
    }

    @Tool
    @LLMDescription("Get real-time execution metrics for a workflow")
    suspend fun getWorkflowMetrics(
        @LLMDescription("Execution ID to query metrics for")
        executionId: String
    ): String {
        val metrics = WorkflowMonitor.getMetrics(executionId)
            ?: return "❌ No metrics found for execution: $executionId"

        return buildString {
            appendLine("📊 Workflow Execution Metrics")
            appendLine("Execution ID: ${metrics.executionId}")
            appendLine("Workflow: ${metrics.workflowName}")
            appendLine("Status: ${metrics.status}")
            appendLine("Duration: ${metrics.getDuration()}ms")
            appendLine("Progress: ${metrics.completedSteps}/${metrics.totalSteps} steps")
            appendLine("Failed: ${metrics.failedSteps}, Skipped: ${metrics.skippedSteps}")
            appendLine("Success Rate: ${"%.2f".format(metrics.getSuccessRate() * 100)}%")
        }
    }

    @Tool
    @LLMDescription("Generate execution report for a completed workflow")
    suspend fun generateExecutionReport(
        @LLMDescription("Execution ID to generate report for")
        executionId: String
    ): String {
        return WorkflowMonitor.generateReport(executionId)
    }

    @Tool
    @LLMDescription("Get statistics for a specific workflow")
    suspend fun getWorkflowStats(
        @LLMDescription("Name of the workflow")
        workflowName: String
    ): String {
        val stats = WorkflowMonitor.getWorkflowStats(workflowName)

        return buildString {
            appendLine("📈 Workflow Statistics: $workflowName")
            appendLine("Total Executions: ${stats.totalExecutions}")
            appendLine("Successful: ${stats.successfulExecutions}")
            appendLine("Failed: ${stats.failedExecutions}")
            appendLine("Success Rate: ${"%.2f".format(stats.successRate * 100)}%")
            appendLine("Average Duration: ${stats.averageDuration}ms")
        }
    }

    @Tool
    @LLMDescription("Save a new version of a workflow")
    suspend fun saveWorkflowVersion(
        @LLMDescription("Path to the workflow file")
        workflowPath: String,

        @LLMDescription("Description of this version")
        description: String? = null,

        @LLMDescription("Who created this version")
        createdBy: String? = null
    ): String {
        return try {
            val workflow = WorkflowParser.parseFile(workflowPath)
            val version = versionControl.saveVersion(workflow, workflowPath, description, createdBy)

            "✅ Saved version ${version.version} for workflow '${workflow.name}'"
        } catch (e: Exception) {
            "❌ Failed to save version: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("List all versions of a workflow")
    suspend fun listWorkflowVersions(
        @LLMDescription("Name of the workflow")
        workflowName: String
    ): String {
        val versions = versionControl.getVersions(workflowName)

        if (versions.isEmpty()) {
            return "No versions found for workflow: $workflowName"
        }

        return buildString {
            appendLine("📚 Versions for workflow: $workflowName")
            appendLine()
            versions.forEach { version ->
                appendLine("Version: ${version.version}")
                appendLine("  Created: ${version.createdAt}")
                version.createdBy?.let { appendLine("  By: $it") }
                version.description?.let { appendLine("  Description: $it") }
                appendLine("  Checksum: ${version.checksum.take(12)}...")
                appendLine()
            }
        }
    }

    @Tool
    @LLMDescription("Rollback a workflow to a previous version")
    suspend fun rollbackWorkflow(
        @LLMDescription("Name of the workflow")
        workflowName: String,

        @LLMDescription("Target version to rollback to")
        targetVersion: String,

        @LLMDescription("Path where to restore the workflow")
        targetPath: String
    ): String {
        return if (versionControl.rollback(workflowName, targetVersion, targetPath)) {
            "✅ Successfully rolled back workflow '$workflowName' to version $targetVersion"
        } else {
            "❌ Failed to rollback workflow"
        }
    }

    @Tool
    @LLMDescription("Compare two versions of a workflow")
    suspend fun compareWorkflowVersions(
        @LLMDescription("Name of the workflow")
        workflowName: String,

        @LLMDescription("First version")
        version1: String,

        @LLMDescription("Second version")
        version2: String
    ): String {
        val comparison = versionControl.compareVersions(workflowName, version1, version2)

        return buildString {
            appendLine("🔍 Version Comparison: $workflowName")
            appendLine("Comparing: $version1 vs $version2")
            appendLine("Identical: ${comparison.identical}")
            appendLine()

            if (!comparison.identical && comparison.changes.isNotEmpty()) {
                appendLine("Changes (${comparison.changes.size}):")
                comparison.changes.take(50).forEach { change ->
                    appendLine("  $change")
                }
                if (comparison.changes.size > 50) {
                    appendLine("  ... and ${comparison.changes.size - 50} more changes")
                }
            }
        }
    }
}

/**
 * 解析 "key1=value1,key2=value2" 格式的字符串为 Map
 */
private fun parseKeyValueString(input: String): Map<String, String> {
    if (input.isBlank()) return emptyMap()
    return input.split(",")
        .filter { it.contains("=") }
        .associate { pair ->
            val (key, value) = pair.split("=", limit = 2)
            key.trim() to value.trim()
        }
}

@Serializable
data class WorkflowTemplate(
    val name: String,
    val file: String,
    val description: String,
    val agents: Int,
    val steps: Int
)
