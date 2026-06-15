# Getting Started

This guide takes you from zero to executing your first workflow with
braidrun-workflow.

## 1. Add the library to your project

The library is not yet published to a public Maven repository. Until then,
install it locally:

```bash
git clone <repo-url>
cd braidrun-workflow
./gradlew publishToMavenLocal
```

Then in your project's `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.fartech.braidrun:braidrun-workflow:1.0.0-SNAPSHOT")
}
```

Requirements: JDK 21, Kotlin 2.x.

## 2. Write a workflow

Workflows are declarative YAML documents. The simplest possible workflow uses
a single code step and needs no LLM provider at all:

```yaml
---
name: hello-workflow
version: 1.0.0
description: Minimal single-step workflow
variables:
  who: "world"
workflow:
  - step: greet
    code:
      language: bash
      script: |
        echo "Hello, {{var:who}}!"
```

Save it as `hello.yaml`. See [WORKFLOW_GUIDE.md](WORKFLOW_GUIDE.md) for the
full YAML reference: agent steps, parallel execution, conditions, iteration,
repeat-until, sub-workflows, state machines and error handling.

## 3. Execute it from Kotlin

```kotlin
import com.fartech.agents.workflow.WorkflowExecutor
import com.fartech.agents.workflow.WorkflowParser
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val workflow = WorkflowParser.parseFile("hello.yaml")

    val executor = WorkflowExecutor(
        httpAccess = HttpAccess(),
        baseParameters = emptyList(),
        enableMonitoring = false,
    )

    val result = executor.execute(workflow, externalExecutionId = "demo-1")

    println("success = ${result.success}")
    result.stepResults.forEach { (step, stepResult) ->
        println("$step -> ${stepResult.output}")
    }
}
```

`WorkflowParser` validates the document and returns a typed
`WorkflowDefinition`; `WorkflowExecutor.execute` is a suspend function that
runs the steps and returns a `WorkflowExecutionResult` with per-step results,
skipped steps and error details.

## 4. Use LLM agent steps

Steps can delegate to agents built from presets (see
`src/main/resources/agent-presets/`):

```yaml
agents:
  helper:
    preset: universal
    overrides:
      tool_set: [exit, file_system, shell]
workflow:
  - step: summarize
    agent: helper
    input: |
      Summarize the file ./input/report.txt into ./output/summary.md,
      then call exit.
```

Agent steps need an LLM provider. Credentials are picked up from environment
variables, e.g.:

| Provider | Variable |
|----------|----------|
| OpenRouter | `OPENROUTER_API_KEY` |
| OpenAI | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| Google Gemini | `GOOGLE_API_KEY` / `GOOGLE_GENAI_API_KEY` |
| DeepSeek | `DEEPSEEK_API_KEY` |
| Mistral | `MISTRAL_API_KEY` |
| Qwen (DashScope) | `DASHSCOPE_API_KEY` |

## 5. Where to go next

- [WORKFLOW_GUIDE.md](WORKFLOW_GUIDE.md) — complete workflow YAML reference
- [TUTORIAL.md](TUTORIAL.md) — step-by-step walkthroughs
- [ARCHITECTURE.md](ARCHITECTURE.md) — how the library is structured
- [MCP_SERVER_GUIDE.md](MCP_SERVER_GUIDE.md) — exposing tool groups over MCP
- [SECURITY_HARDENING.md](SECURITY_HARDENING.md) — sandboxing and guardrails
  you should configure before running untrusted workflows
- `workflows/templates/` — 140+ runnable example and test workflows
