package com.fartech.agents.cli

import com.fartech.agents.commons.SubprocessExecutorFactory
import com.fartech.agents.mcp.getSupportedAgentMcpToolGroups
import com.fartech.agents.mcp.startAgentMcpServer
import com.fartech.agents.tools.ToolRunScope
import com.fartech.agents.workflow.AgentDefinition
import com.fartech.agents.workflow.AgentPresetRegistry
import com.fartech.agents.workflow.ApprovalDecision
import com.fartech.agents.workflow.ApprovalHandler
import com.fartech.agents.workflow.ApprovalRequest
import com.fartech.agents.workflow.FileSystemWorkflowResolver
import com.fartech.agents.workflow.WorkflowDefinition
import com.fartech.agents.workflow.WorkflowExecutionResult
import com.fartech.agents.workflow.WorkflowExecutor
import com.fartech.agents.workflow.WorkflowParser
import com.fartech.agents.workflow.WorkflowStep
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

fun main(args: Array<String>) {
    // One local user: browser contexts stay shared across runs for the life of the process.
    ToolRunScope.declareSingleUserProcess()
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

internal class BraidrunWorkflowCli(
    private val fileApprovalHandlerFactory: (File) -> ApprovalHandler = { FileApprovalHandler(it) },
    // Last parameter on purpose: callers pass the interactive factory as a trailing lambda.
    private val interactiveApprovalHandlerFactory: () -> ApprovalHandler = ::systemInteractiveApprovalHandler
) {
    suspend fun run(args: List<String>): Int {
        if (args.isEmpty() || args.first() in setOf("-h", "--help", "help")) {
            println(HELP)
            return 0
        }

        return when (val command = args.first()) {
            "--version", "version" -> {
                println("braidrun-workflow $VERSION")
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
        // Fail before starting work when interactive approvals cannot be answered.
        if (options.interactiveApprovals && options.approvalDir != null) {
            throw CliException("Use either --interactive-approvals or --approval-dir, not both")
        }
        val approvalHandler = when {
            options.interactiveApprovals -> interactiveApprovalHandlerFactory()
            options.approvalDir != null -> fileApprovalHandlerFactory(options.approvalDir.absoluteFile)
            else -> null
        }
        val workflowFile = File(options.path!!).absoluteFile
        val resolver = resolverFor(workflowFile)
        val workflow = WorkflowParser.parseFile(workflowFile.absolutePath, resolver)
        val parameters = buildParameters(options)
        val executor = WorkflowExecutor(
            httpAccess = HttpAccess(),
            baseParameters = parameters,
            approvalHandler = approvalHandler,
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
        var interactiveApprovals = false
        var approvalDir: File? = null

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

                "--interactive-approvals" -> {
                    interactiveApprovals = true
                    i += 1
                }

                "--approval-dir" -> {
                    approvalDir = File(args.valueAfter(i, arg))
                    i += 2
                }

                else -> {
                    if (arg.startsWith("-")) throw CliException("Unknown option '$arg'")
                    if (path != null) throw CliException("Only one workflow path is allowed")
                    path = arg
                    i += 1
                }
            }
        }

        if (!allowExecutionOnly && (variables.isNotEmpty() || parameters.isNotEmpty() || executionId != null || output != null || quiet || interactiveApprovals || approvalDir != null)) {
            throw CliException("This command only accepts a workflow path and --subprocess-mode")
        }
        if (requirePath && path == null) throw CliException("Workflow path is required")

        return ExecutionOptions(path, variables, parameters, subprocessMode, executionId, output, quiet, interactiveApprovals, approvalDir)
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
                step.classifier != null -> if (step.classifier.isJev) "classifier(jev)" else "classifier"
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
        generateSequence(::readlnOrNull).joinToString("\n").takeIf { it.isNotBlank() }
            ?: throw CliException("stdin was empty")
}

private data class ExecutionOptions(
    val path: String?,
    val variables: Map<String, String>,
    val parameters: Map<String, String>,
    val subprocessMode: String,
    val executionId: String?,
    val output: File?,
    val quiet: Boolean,
    val interactiveApprovals: Boolean = false,
    val approvalDir: File? = null
)

/** One terminal reader at a time, even when parallel workflow steps request approval. */
internal class InteractiveApprovalHandler(
    private val readLine: suspend () -> String?,
    private val printLine: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ApprovalHandler {
    private val promptLock = Mutex()
    private var inputUnavailable = false

    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision = promptLock.withLock {
        if (inputUnavailable) {
            return@withLock reject(request, "Interactive approval input is closed; restart with a fresh terminal.")
        }
        printLine("\n=== MANUAL APPROVAL REQUIRED ===")
        printLine("Request ID: ${request.approvalId}")
        printLine("Workflow: ${request.workflowName}; execution: ${request.executionId}; step: ${request.stepName}")
        printLine("Review material:")
        printLine(request.message)
        for (group in request.reviewableGroups) {
            printLine("Group: ${group.title ?: group.name} (${group.name}); ${group.items.size} item(s)")
            group.sourcePath?.let { printLine("Source: $it") }
            group.items.forEachIndexed { index, item -> printLine("${index + 1}. $item") }
        }

        // The executor wraps this handler in its own timeout. Finish just before
        // that deadline so rejection and its explanation reach workflow variables.
        val timeoutMillis = request.timeout?.toLongOrNull()?.takeIf { it > 0 }
            ?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000)
        if (timeoutMillis == null) {
            return@withLock reject(request, "Missing or invalid approval timeout; no approval granted.")
        }
        val elapsed = (nowMillis() - request.requestedAt).coerceAtLeast(0)
        val margin = (timeoutMillis / 10).coerceIn(1, 1000)
        val remaining = timeoutMillis - elapsed - margin
        if (remaining <= 0) return@withLock reject(request, "Approval timed out; no approval granted.")
        printLine("Response deadline: ${java.time.Instant.ofEpochMilli(nowMillis() + remaining)}")
        printLine("Type approve [comment] or reject [comment], then Enter. Approval accepts every listed item unchanged.")
        try {
            val decision = withTimeoutOrNull(remaining.milliseconds) {
                awaitExplicitDecision(request)
            }
            if (decision == null) {
                // Console reads can survive interruption. Never let a late line
                // from this expired prompt answer another approval in this run.
                inputUnavailable = true
                reject(request, "Approval timed out; no approval granted. Interactive input is now closed.")
            } else {
                decision
            }
        } catch (e: CancellationException) {
            inputUnavailable = true
            printLine("Approval wait cancelled or timed out for ${request.approvalId}; no approval granted. Interactive input is now closed.")
            throw e
        } catch (e: Exception) {
            inputUnavailable = true
            reject(request, "Approval input failed (${e::class.java.simpleName}); no approval granted.")
        }
    }

    private suspend fun awaitExplicitDecision(request: ApprovalRequest): ApprovalDecision {
        while (true) {
            val line = readLine() ?: run {
                inputUnavailable = true
                return reject(request, "Approval input reached EOF; no approval granted.")
            }
            val match = Regex("^(approve|reject)(?:\\s+(.*))?$", RegexOption.IGNORE_CASE).matchEntire(line.trim())
            if (match == null) {
                printLine("No decision recorded. Enter approve [comment] or reject [comment].")
                continue
            }
            val approved = match.groupValues[1].equals("approve", ignoreCase = true)
            val comment = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
            printLine("Decision: ${if (approved) "APPROVED" else "REJECTED"}; request ID: ${request.approvalId}")
            comment?.let { printLine("Comment: $it") }
            return ApprovalDecision(approved = approved, comment = comment)
        }
    }

    private fun reject(request: ApprovalRequest, reason: String): ApprovalDecision {
        printLine("Decision: REJECTED; request ID: ${request.approvalId}; $reason")
        return ApprovalDecision(approved = false, comment = reason)
    }
}

internal fun systemInteractiveApprovalHandler(): ApprovalHandler {
    val console = System.console() ?: throw CliException(
        "--interactive-approvals requires an interactive TTY. No approvals were granted; rerun in a terminal."
    )
    return InteractiveApprovalHandler(
        readLine = {
            // Do not put a potentially uninterruptible terminal read inside a
            // structured IO job: it would prevent the approval timeout exiting.
            suspendCancellableCoroutine { continuation ->
                val reader = Thread({
                    try {
                        val line = console.readLine()
                        if (continuation.isActive) continuation.resume(line)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }, "braidrun-interactive-approval").apply { isDaemon = true }
                continuation.invokeOnCancellation { reader.interrupt() }
                reader.start()
            }
        },
        printLine = { line ->
            // Console.readLine holds its writer lock while waiting for input.
            // Use stderr so timeout/cancellation messages cannot block behind it.
            System.err.println(line)
            System.err.flush()
        }
    )
}

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

private val VERSION: String =
    BraidrunWorkflowCli::class.java.`package`?.implementationVersion ?: "1.0.9"

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
      --interactive-approvals       Run only: wait for explicit approve/reject in a TTY.
                                    EOF or timeout never approves; there is no default answer.
      --approval-dir dir            Run only: headless approvals. Each request is written to
                                    dir/requests/<id>.json; answer by writing dir/decisions/<id>.json
                                    ({"approved": true|false, "comment": "..."}). dir/policy.json may
                                    mark soft gates that continue after auto_approve_after_seconds.
      -o, --output file             Write the execution summary to a file.
      --quiet                       Print only step outputs.
      -h, --help                    Show this help.
""".trimIndent()
