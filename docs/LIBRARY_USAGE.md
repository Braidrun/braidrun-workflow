# Library Usage

Use the same parser and executor that the CLI uses.

```kotlin
import com.fartech.agents.commons.SubprocessExecutorFactory
import com.fartech.agents.workflow.FileSystemWorkflowResolver
import com.fartech.agents.workflow.WorkflowExecutor
import com.fartech.agents.workflow.WorkflowParser
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

fun main() = runBlocking {
    val workflowFile = File("examples/workflows/hello-code.yaml").absoluteFile
    val resolver = FileSystemWorkflowResolver(listOf(workflowFile.parentFile, File(".")))
    val workflow = WorkflowParser.parseFile(workflowFile.absolutePath, resolver)

    val parameters = listOf(
        ConfigurationParameter("subprocess_mode", JsonPrimitive("native")),
        ConfigurationParameter("working_dir", JsonPrimitive(File(".").absolutePath)),
        ConfigurationParameter("output_dir", JsonPrimitive(File("output").absolutePath))
    )

    val executor = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = parameters,
        workflowResolver = resolver,
        codeStepExecutor = SubprocessExecutorFactory.create(parameters)
    )

    val result = executor.execute(
        workflow = workflow,
        initialInput = mapOf("topic" to "Braidrun Workflow"),
        externalExecutionId = "example-run"
    )

    println(result.success)
    println(result.stepResults)
}
```

Recommended integration pattern:

- Parse and validate workflow YAML before storing it.
- Inject `working_dir`, `output_dir`, and `subprocess_mode` explicitly.
- Use Docker mode for untrusted code or shell work.
- Keep LLM provider credentials outside workflow YAML when possible.
- For TypeSafe Jev steps (`classifier.jev`, `repeat_until.jev`), a multi-tenant host
  should pass each user's key and turn off the process-environment fallback. Then an
  operator's `TYPESAFE_API_KEY` never pays for users' steps:

  ```kotlin
  WorkflowExecutor(
      httpAccess = HttpAccess(),
      baseParameters = parameters,
      jevCredentials = JevCredentials(apiKey = userKey, defaultModel = "jev-latest"),
      jevEnvKeyFallback = false
  )
  ```

  Sub-workflows and workflows that an agent runs through the `workflow` tool use the
  same settings: the executor hands its `workflow` tool a `NestedWorkflowRuntime` with
  its code step executor, proxy env, credential providers and Jev policy. If you build
  a `WorkflowTools` yourself, pass it a `NestedWorkflowRuntime` with the same values.

  `JevCredentials` is in `com.fartech.agents.jev`. See
  [Jev (TypeSafe) Decisions](WORKFLOW_GUIDE.md#jev-typesafe-decisions) for the full
  order in which keys and models are resolved.

## Embedding in a Multi-Tenant Host

The library builds on Koog 1.3.0 (stable modules) and 1.3.0-beta (beta-only
modules); see `build.gradle.kts` for which module is on which stream. If your
build also depends on Koog directly, use the same versions.

A server that runs other people's workflows should declare the host policy
latches once at startup, before building any executor:

```kotlin
import com.fartech.agents.workflow.WorkflowHostPolicy

WorkflowHostPolicy.requireCodeStepExecutor()                  // code: steps only via your executor
WorkflowHostPolicy.requirePublicLlmEndpoints()                // no LLM / embedding / multimedia calls to private hosts
WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints() // env keys never go to a user-set base_url
WorkflowHostPolicy.requireSingleLlmChoice()                   // num_choices capped at 1, so every round is metered
WorkflowHostPolicy.restrictSkillSideEffects()                 // no skill hooks, skill MCP servers or agent skill installs
```

They are one-way and process-wide, deliberately not configuration parameters,
because workflow YAML and parameters are user-controlled. See
[Security](SECURITY.md#host-policy-latches) for what each one does. The CLI and
plain library use set none of them. Per-skill MCP auto-start is off unless you
call `WorkflowHostPolicy.allowSkillMcpAutoStart()`.

Skill tools: the mutating skill operations (`downloadSkillFromClawHub`,
`downloadSkillFromGit`, `clearSkillCache`, `refreshSkills`) are on
`SkillAdminTools`, not `SkillTools`. For user-initiated installs, call the host
API `SkillTools.downloadSkillFromClawHub(..., refreshManager = false)`, validate
the result, then refresh your skill manager. See [Skills](SKILLS.md).

Metering: token usage arrives through the LLM-call events for every round of
the default strategies. Prompt-cache hits carry no token counts and set
`braidrun_prompt_cache_hit=true` in the response metadata.
