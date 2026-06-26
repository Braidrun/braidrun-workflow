package com.fartech.agents.workflow

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.serialization.Serializable
import java.io.File

@LLMDescription("Toolset for defining and executing complex multi-agent workflows")
class WorkflowTools(
    private val httpAccess: HttpAccess,
    private val parameters: List<ConfigurationParameter>
) : ToolSet {

    private val executor = WorkflowExecutor(httpAccess, parameters, enableMonitoring = true)
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
        @LLMDescription("Name of the template (without .yaml extension)")
        templateName: String,

        @LLMDescription("Output path for the new workflow file")
        outputPath: String,

        @LLMDescription("Variables to substitute in the template as comma-separated key=value pairs, e.g. 'topic=AI,language=zh'")
        variables: String = ""
    ): String {
        return try {
            val templatePath = "$templatesDir/$templateName.yaml"
            val templateFile = File(templatePath)

            if (!templateFile.exists()) {
                return "❌ Template not found: $templateName"
            }

            var content = templateFile.readText()

            // 替换变量
            parseKeyValueString(variables).forEach { (key, value) ->
                content = content.replace("{{$key}}", value)
            }

            File(outputPath).writeText(content)

            "✅ Workflow created from template '$templateName' → $outputPath"
        } catch (e: Exception) {
            "❌ Failed to create workflow from template: ${e.message}"
        }
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
