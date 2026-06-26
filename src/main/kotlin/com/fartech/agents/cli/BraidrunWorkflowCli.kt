package com.fartech.agents.cli

import com.fartech.agents.commons.SubprocessExecutorFactory
import com.fartech.agents.mcp.getSupportedAgentMcpToolGroups
import com.fartech.agents.mcp.startAgentMcpServer
import com.fartech.agents.workflow.AgentDefinition
import com.fartech.agents.workflow.AgentPresetRegistry
import com.fartech.agents.workflow.FileSystemWorkflowResolver
import com.fartech.agents.workflow.WorkflowDefinition
import com.fartech.agents.workflow.WorkflowExecutionResult
import com.fartech.agents.workflow.WorkflowExecutor
import com.fartech.agents.workflow.WorkflowParser
import com.fartech.agents.workflow.WorkflowStep
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val code = try {
        runBlocking { BraidrunWorkflowCli().run(args.toList()) }
    } catch (e: CliException) {
        System.err.println("Error: ${e.message}")
        2
    } catch (e: Throwable) {
        System.err.println("Error: ${e.message ?: e::class.java.name}")
        1
    }
    exitProcess(code)
}

private class BraidrunWorkflowCli {
    suspend fun run(args: List<String>): Int {
        if (args.isEmpty() || args.first() in setOf("-h", "--help", "help")) {
            println(HELP)
            return 0
        }

        return when (val command = args.first()) {
            "--version", "version" -> {
                println("braidrun-workflow 1.0.0-SNAPSHOT")
                0
            }

            "run" -> runWorkflow(args.drop(1))
            "validate" -> validateWorkflow(args.drop(1))
            "dry-run" -> dryRunWorkflow(args.drop(1))
            "agent" -> runSingleAgent(args.drop(1))
            "list-presets" -> listPresets()
            "list-tools" -> listTools()
            "mcp-server" -> runMcpServer(args.drop(1))
            else -> throw CliException("Unknown command '$command'. Use --help for available commands.")
        }
    }

    private suspend fun runWorkflow(args: List<String>): Int {
        val options = parseExecutionOptions(args, requirePath = true)
        val workflowFile = File(options.path!!).absoluteFile
        val resolver = resolverFor(workflowFile)
        val workflow = WorkflowParser.parseFile(workflowFile.absolutePath, resolver)
        val parameters = buildParameters(options)
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = parameters,
            workflowResolver = resolver,
            codeStepExecutor = SubprocessExecutorFactory.create(parameters)
        )
        val result = executor.execute(
            workflow = workflow,
            initialInput = options.variables,
            externalExecutionId = options.executionId
        )
        writeOrPrint(formatResult(workflow, result, options.quiet), options.output)
        return if (result.success) 0 else 1
    }

    private fun validateWorkflow(args: List<String>): Int {
        val options = parseExecutionOptions(args, requirePath = true, allowExecutionOnly = false)
        val workflowFile = File(options.path!!).absoluteFile
        val workflow = WorkflowParser.parseFile(workflowFile.absolutePath, resolverFor(workflowFile))
        println("OK ${workflow.name} (${workflow.workflow.size} step(s), ${workflow.agents.size} agent(s))")
        return 0
    }

    private fun dryRunWorkflow(args: List<String>): Int {
        val options = parseExecutionOptions(args, requirePath = true, allowExecutionOnly = false)
        val workflowFile = File(options.path!!).absoluteFile
        val workflow = WorkflowParser.parseFile(workflowFile.absolutePath, resolverFor(workflowFile))
        println(formatPlan(workflow))
        return 0
    }

    private suspend fun runSingleAgent(args: List<String>): Int {
        val options = parseAgentOptions(args)
        val prompt = options.prompt
            ?: if (options.readStdin) readStdin()
            else throw CliException("agent requires --prompt <text> or --stdin")

        val overrides: Map<String, JsonElement> = options.overrides.mapValues { JsonPrimitive(it.value) }
        val workflow = WorkflowDefinition(
            name = "cli-agent-${options.preset}",
            agents = mapOf(
                "main" to AgentDefinition(
                    preset = options.preset,
                    overrides = overrides
                )
            ),
            workflow = listOf(
                WorkflowStep(
                    step = "run",
                    agent = "main",
                    input = prompt
                )
            )
        )
        val execOptions = ExecutionOptions(
            path = null,
            variables = emptyMap(),
            parameters = options.parameters,
            subprocessMode = options.subprocessMode,
            executionId = options.executionId,
            output = options.output,
            quiet = options.quiet
        )
        val parameters = buildParameters(execOptions)
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = parameters,
            codeStepExecutor = SubprocessExecutorFactory.create(parameters)
        )
        val result = executor.execute(workflow, externalExecutionId = options.executionId)
        writeOrPrint(formatResult(workflow, result, options.quiet), options.output)
        return if (result.success) 0 else 1
    }

    private fun listPresets(): Int {
        AgentPresetRegistry.getAll()
            .sortedWith(compareBy({ it.category }, { it.id }))
            .forEach { preset ->
                println("${preset.id}\t${preset.category}\t${preset.displayName}\t${preset.description}")
            }
        return 0
    }

    private fun listTools(): Int {
        getSupportedAgentMcpToolGroups()
            .sortedBy { it.name }
            .forEach { group -> println("${group.name}\t${group.description}") }
        return 0
    }

    private suspend fun runMcpServer(args: List<String>): Int {
        val options = parseMcpOptions(args)
        val parameters = buildParameters(
            ExecutionOptions(
                path = null,
                variables = emptyMap(),
                parameters = options.parameters,
                subprocessMode = options.subprocessMode,
                executionId = null,
                output = null,
                quiet = false
            )
        )
        startAgentMcpServer(HttpAccess(), parameters, options.toolGroups)
        return 0
    }

    private fun parseExecutionOptions(
        args: List<String>,
        requirePath: Boolean,
        allowExecutionOnly: Boolean = true
    ): ExecutionOptions {
        var path: String? = null
        val variables = linkedMapOf<String, String>()
        val parameters = linkedMapOf<String, String>()
        var subprocessMode = "native"
        var executionId: String? = null
        var output: File? = null
        var quiet = false

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-v", "--var" -> {
                    variables += splitAssignment(args.valueAfter(i, arg))
                    i += 2
                }

                "--param" -> {
                    parameters += splitAssignment(args.valueAfter(i, arg))
                    i += 2
                }

                "--subprocess-mode" -> {
                    subprocessMode = args.valueAfter(i, arg).also(::validateSubprocessMode)
                    i += 2
                }

                "--execution-id" -> {
                    executionId = args.valueAfter(i, arg)
                    i += 2
                }

                "-o", "--output" -> {
                    output = File(args.valueAfter(i, arg))
                    i += 2
                }

                "--quiet" -> {
                    quiet = true
                    i += 1
                }

                else -> {
                    if (arg.startsWith("-")) throw CliException("Unknown option '$arg'")
                    if (path != null) throw CliException("Only one workflow path is allowed")
                    path = arg
                    i += 1
                }
            }
        }

        if (!allowExecutionOnly && (variables.isNotEmpty() || parameters.isNotEmpty() || executionId != null || output != null || quiet)) {
            throw CliException("This command only accepts a workflow path and --subprocess-mode")
        }
        if (requirePath && path == null) throw CliException("Workflow path is required")

        return ExecutionOptions(path, variables, parameters, subprocessMode, executionId, output, quiet)
    }

    private fun parseAgentOptions(args: List<String>): AgentOptions {
        var preset: String? = null
        var prompt: String? = null
        var readStdin = false
        val overrides = linkedMapOf<String, String>()
        val parameters = linkedMapOf<String, String>()
        var subprocessMode = "native"
        var executionId: String? = null
        var output: File? = null
        var quiet = false

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--preset" -> preset = args.valueAfter(i, arg)
                "--prompt" -> prompt = args.valueAfter(i, arg)
                "--stdin" -> readStdin = true
                "--override" -> overrides += splitAssignment(args.valueAfter(i, arg))
                "--param" -> parameters += splitAssignment(args.valueAfter(i, arg))
                "--subprocess-mode" -> subprocessMode = args.valueAfter(i, arg).also(::validateSubprocessMode)
                "--execution-id" -> executionId = args.valueAfter(i, arg)
                "-o", "--output" -> output = File(args.valueAfter(i, arg))
                "--quiet" -> quiet = true
                else -> throw CliException("Unknown option '$arg'")
            }
            i += if (argTakesValue(args[i])) 2 else 1
        }

        val presetId = preset ?: throw CliException("agent requires --preset <id>")
        if (AgentPresetRegistry.get(presetId) == null) throw CliException("Unknown preset '$presetId'")
        return AgentOptions(presetId, prompt, readStdin, overrides, parameters, subprocessMode, executionId, output, quiet)
    }

    private fun parseMcpOptions(args: List<String>): McpOptions {
        val groups = mutableListOf<String>()
        val parameters = linkedMapOf<String, String>()
        var subprocessMode = "native"

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--tool-group", "--tools" -> groups += args.valueAfter(i, arg)
                "--param" -> parameters += splitAssignment(args.valueAfter(i, arg))
                "--subprocess-mode" -> subprocessMode = args.valueAfter(i, arg).also(::validateSubprocessMode)
                else -> throw CliException("Unknown option '$arg'")
            }
            i += 2
        }

        return McpOptions(groups, parameters, subprocessMode)
    }

    private fun buildParameters(options: ExecutionOptions): List<ConfigurationParameter> {
        val merged = linkedMapOf<String, String>()
        merged["subprocess_mode"] = options.subprocessMode
        merged["working_dir"] = File(".").absoluteFile.normalize().path
        merged["output_dir"] = File("output").absoluteFile.normalize().path
        options.executionId?.let { merged["execution_id"] = it }
        merged += options.parameters
        return merged.map { (key, value) -> ConfigurationParameter(key, JsonPrimitive(value)) }
    }

    private fun resolverFor(workflowFile: File): FileSystemWorkflowResolver {
        val parent = workflowFile.parentFile ?: File(".").absoluteFile
        return FileSystemWorkflowResolver(listOf(parent, File(".").absoluteFile))
    }

    private fun formatPlan(workflow: WorkflowDefinition): String = buildString {
        appendLine("Workflow: ${workflow.name}")
        workflow.description?.takeIf { it.isNotBlank() }?.let { appendLine("Description: $it") }
        appendLine("Agents:")
        workflow.agents.toSortedMap().forEach { (name, agent) ->
            val preset = agent.preset ?: agent.type
            appendLine("  - $name: $preset")
        }
        appendLine("Steps:")
        workflow.workflow.forEachIndexed { index, step ->
            val target = step.agent ?: when {
                step.groupChat != null -> "group_chat"
                step.parallel != null -> "parallel"
                step.stateMachine != null -> "state_machine"
                step.code != null -> "code"
                step.classifier != null -> "classifier"
                step.agentBased != null -> "agent_based"
                step.subWorkflow != null -> "sub_workflow"
                else -> "runtime"
            }
            val depends = if (step.dependsOn.isEmpty()) "" else " depends_on=${step.dependsOn.joinToString(",")}"
            appendLine("  ${index + 1}. ${step.step} -> $target$depends")
        }
    }.trimEnd()

    private fun formatResult(workflow: WorkflowDefinition, result: WorkflowExecutionResult, quiet: Boolean): String = buildString {
        if (!quiet) {
            appendLine("Workflow: ${result.workflowName}")
            appendLine("Status: ${if (result.success) "success" else "failed"}")
            appendLine("Duration: ${"%.2f".format(result.durationSeconds)}s")
            result.error?.let { appendLine("Error: $it") }
            appendLine()
        }
        workflow.workflow.forEach { step ->
            val stepResult = result.stepResults[step.step] ?: return@forEach
            if (!quiet) {
                appendLine("## ${stepResult.stepName}")
                appendLine("Status: ${if (stepResult.success) "success" else "failed"}")
                stepResult.error?.let { appendLine("Error: $it") }
            }
            stepResult.output?.takeIf { it.isNotBlank() }?.let {
                appendLine(it.trimEnd())
            }
            if (!quiet) appendLine()
        }
        if (!quiet && result.skippedSteps.isNotEmpty()) {
            appendLine("Skipped: ${result.skippedSteps.sorted().joinToString(", ")}")
        }
    }.trimEnd()

    private fun writeOrPrint(text: String, output: File?) {
        if (output == null) {
            println(text)
            return
        }
        output.parentFile?.mkdirs()
        output.writeText(text)
    }

    private fun splitAssignment(raw: String): Pair<String, String> {
        val idx = raw.indexOf('=')
        if (idx <= 0) throw CliException("Expected key=value, got '$raw'")
        val key = raw.substring(0, idx).trim()
        val value = raw.substring(idx + 1)
        if (key.isEmpty()) throw CliException("Assignment key cannot be empty")
        return key to value
    }

    private fun validateSubprocessMode(value: String) {
        if (value !in setOf("native", "docker")) {
            throw CliException("--subprocess-mode must be native or docker")
        }
    }

    private fun argTakesValue(arg: String): Boolean =
        arg in setOf("--preset", "--prompt", "--override", "--param", "--subprocess-mode", "--execution-id", "-o", "--output")

    private fun List<String>.valueAfter(index: Int, option: String): String =
        getOrNull(index + 1) ?: throw CliException("$option requires a value")

    private fun readStdin(): String =
        generateSequence(::readLine).joinToString("\n").takeIf { it.isNotBlank() }
            ?: throw CliException("stdin was empty")
}

private data class ExecutionOptions(
    val path: String?,
    val variables: Map<String, String>,
    val parameters: Map<String, String>,
    val subprocessMode: String,
    val executionId: String?,
    val output: File?,
    val quiet: Boolean
)

private data class AgentOptions(
    val preset: String,
    val prompt: String?,
    val readStdin: Boolean,
    val overrides: Map<String, String>,
    val parameters: Map<String, String>,
    val subprocessMode: String,
    val executionId: String?,
    val output: File?,
    val quiet: Boolean
)

private data class McpOptions(
    val toolGroups: List<String>,
    val parameters: Map<String, String>,
    val subprocessMode: String
)

private class CliException(message: String) : RuntimeException(message)

private val HELP = """
    Braidrun Workflow CLI

    Usage:
      braidrun-workflow run <workflow.yaml> [options]
      braidrun-workflow validate <workflow.yaml>
      braidrun-workflow dry-run <workflow.yaml>
      braidrun-workflow agent --preset <id> (--prompt <text> | --stdin) [options]
      braidrun-workflow list-presets
      braidrun-workflow list-tools
      braidrun-workflow mcp-server [--tool-group <name>[,<name>]] [options]

    Options:
      -v, --var key=value           Initial workflow variable for run.
      --override key=value          Agent parameter override for the agent command.
      --param key=value             Runtime parameter passed to the executor.
      --subprocess-mode native|docker
                                    Command/code/tool subprocess mode. Default: native.
      --execution-id id             Reuse a caller-provided execution id.
      -o, --output file             Write the execution summary to a file.
      --quiet                       Print only step outputs.
      -h, --help                    Show this help.
""".trimIndent()
