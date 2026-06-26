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
