# Braidrun Workflow

Braidrun Workflow is the open workflow runtime behind [Braidrun](https://braidrun.com). It runs YAML-defined AI workflows with agent presets, deterministic code steps, tool execution, sub-workflows, MCP integration, external Claude Code / Codex delegation, and Docker-backed subprocess isolation.

Use this repository when you need the runtime as a command-line tool or an embeddable Kotlin/JVM library. Use [braidrun.com](https://braidrun.com) when you want the hosted product experience: visual workflow editing, team execution history, managed deployments, monitoring, and a web UI for non-developer operators.

## Capabilities

- Workflow YAML execution from CLI or Kotlin code.
- Agent steps, code steps, parallel execution, conditions, iteration, retries, state machines, and sub-workflows.
- Built-in presets for coding, research, writing, data analysis, documents, browser automation, DevOps, communication, and marketing research.
- Built-in tool groups for files, shell, Git, HTTP, browser automation, documents, databases, RAG, email, image processing, and MCP.
- External coding-agent delegation through Claude Code and OpenAI Codex subprocesses.
- Native subprocess mode for trusted local development and Docker subprocess mode for production isolation.

## Quick Start

Requirements:

- JDK 21 or newer
- Docker Desktop or Docker Engine for Docker subprocess mode
- Provider API keys for LLM-backed agent workflows

```bash
./gradlew installDist
./build/install/braidrun-workflow/bin/braidrun-workflow validate examples/workflows/hello-code.yaml
./build/install/braidrun-workflow/bin/braidrun-workflow run examples/workflows/hello-code.yaml --quiet
```

Run a preset agent directly:

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow agent \
  --preset coder \
  --prompt "Inspect this repository and summarize the test strategy."
```

Run with Docker-backed subprocess isolation:

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow run examples/workflows/hello-code.yaml \
  --subprocess-mode docker
```

## Documentation

- [Installation](docs/INSTALLATION.md)
- [Docker Runtime](docs/DOCKER.md)
- [CLI](docs/CLI.md)
- [Library Usage](docs/LIBRARY_USAGE.md)
- [Workflow YAML](docs/WORKFLOW_GUIDE.md)
- [Agent Presets](docs/AGENT_PRESETS.md)
- [Claude Code and Codex Agents](docs/EXTERNAL_AGENTS.md)
- [MCP](docs/MCP.md)
- [Security](docs/SECURITY.md)

## Build and Verify

```bash
./gradlew compileKotlin
./gradlew test
./gradlew build
./gradlew publishToMavenLocal
```

## Repository Layout

| Path | Purpose |
| --- | --- |
| `src/main/kotlin` | Workflow runtime, agent runtime, tools, CLI, and MCP server |
| `src/main/resources/agent-presets` | Built-in agent preset YAML |
| `src/main/resources/builtin-skills` | Skills bundled in the jar |
| `examples/workflows` | Small runnable workflows for CLI and library users |
| `docs` | Public usage and operations documentation |

## Braidrun Product

This repository is the workflow runtime. The product layer lives at [braidrun.com](https://braidrun.com), where workflows can be created, reviewed, executed, and monitored through a managed web experience. If you are evaluating Braidrun for a team or production workflow deployment, start with the website and use this repository for runtime integration details.

## License

Apache-2.0. See [LICENSE](LICENSE).
