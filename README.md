# braidrun-workflow

A Kotlin/JVM library for building and running LLM agent workflows, built on
[Koog](https://github.com/JetBrains/koog).

Define workflows declaratively in YAML — agent steps, code steps, parallel
execution, conditions, iteration, sub-workflows, state machines — and execute
them with checkpoint/resume, monitoring and sandboxing. Or embed the
preset-based agent runtime directly.

> **Status:** pre-release. This library was recently extracted from the
> Braidrun platform and is being prepared for its first public release.
> APIs and coordinates may still change.

## Features

- **Declarative workflow engine** — parse, validate and execute workflow
  YAML: sequential / parallel / conditional steps, iteration, repeat-until,
  sub-workflows, state machines, retries, checkpoints and resume.
- **Agent runtime** — preset-based agent bootstrap on top of Koog with
  multi-provider LLM execution (OpenAI, Anthropic, OpenRouter, Google,
  DeepSeek, Mistral, Qwen, Ollama, Bedrock, LiteRT), prompt caching, token
  accounting, tracing, long-term and structured-fact memory, A2A.
- **Tool library** — ~40 built-in tool groups: files, shell, HTTP, browser
  automation (Playwright), email, SQL databases, Redis, MongoDB, Office
  documents (POI/PDFBox), RAG/embeddings.
- **MCP integration** — consume external MCP servers as tools, or expose the
  built-in tool groups as an MCP stdio server.
- **Skills** — load agent skills following the cross-client `SKILL.md`
  convention (`.agents/skills/`, `.claude/skills/`, builtin classpath
  skills).
- **Sandboxing & guardrails** — Docker-per-step code execution, subprocess
  env allowlists, MCP tool allowlists/rate limits, read-only DB mode.

## Quick start

```yaml
# hello.yaml
---
name: hello-workflow
version: 1.0.0
workflow:
  - step: greet
    code:
      language: bash
      script: echo "Hello, world!"
```

```kotlin
val workflow = WorkflowParser.parseFile("hello.yaml")
val executor = WorkflowExecutor(
    httpAccess = HttpAccess(),
    baseParameters = emptyList(),
)
val result = executor.execute(workflow, externalExecutionId = "demo-1")
```

See [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) for the full setup,
including LLM agent steps.

## Build

Requirements: JDK 21 (Gradle 9.5 wrapper included).

```bash
./gradlew build                 # compile + run all tests
./gradlew publishToMavenLocal   # install into your local Maven repository
```

The test suite is hermetic — no API keys or external services needed.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/kotlin` | library source |
| `src/main/resources/agent-presets` | built-in agent presets |
| `src/main/resources/builtin-skills` | skills bundled in the jar |
| `workflows/templates` | 140+ example / test workflow YAML definitions |
| `skills/` | example skill packages |
| `docs/` | guides and reference documentation |

## Documentation

- [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) — first workflow in 5 minutes
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — package map and execution flow
- [docs/WORKFLOW_GUIDE.md](docs/WORKFLOW_GUIDE.md) — workflow YAML reference
- [docs/TUTORIAL.md](docs/TUTORIAL.md) — step-by-step walkthroughs
- [docs/API_REFERENCE.md](docs/API_REFERENCE.md) — public API surface
- [docs/WORKFLOW_DEBUG_GUIDE.md](docs/WORKFLOW_DEBUG_GUIDE.md) — debugging workflows
- [docs/MCP_SERVER_GUIDE.md](docs/MCP_SERVER_GUIDE.md) — exposing tool groups over MCP
- [docs/SECURITY_HARDENING.md](docs/SECURITY_HARDENING.md) — guardrail configuration

Some guides are currently written in Chinese; English translations are
planned.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). For security issues, see
[SECURITY.md](SECURITY.md).

## License

[Apache-2.0](LICENSE)
