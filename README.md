# Braidrun Workflow

Braidrun Workflow is the open workflow runtime behind [Braidrun](https://braidrun.com). It orchestrates YAML-defined AI workflows across an embedded Koog runtime and external Claude Code and OpenAI Codex runtimes, with agent presets, deterministic code steps, tool execution, sub-workflows, MCP integration, and Docker-backed subprocess isolation.

Use this repository when you need the runtime as a command-line tool or an embeddable Kotlin/JVM library. Use [braidrun.com](https://braidrun.com) when you want the hosted product experience: visual workflow editing, team execution history, managed deployments, monitoring, and a web UI for non-developer operators.

## Capabilities

- Workflow YAML execution from CLI or Kotlin code.
- Agent steps, code steps, parallel execution, conditions, iteration, retries, state machines, and sub-workflows.
- Typed decisions with the TypeSafe Jev decision model: classifier routing with calibrated confidence, plus Jev-graded `repeat_until` loops.
- Built-in presets for coding, research, writing, data analysis, documents, browser automation, DevOps, communication, and marketing research.
- Built-in tool groups for files, shell, Git, HTTP, browser automation, documents, databases, RAG, email, image processing, and MCP. Browser screenshots and MCP image results reach Anthropic, Gemini 3+ and Responses-API-only OpenAI vision models as images (text placeholders elsewhere).
- Koog 1.3.0 model catalog (Claude 5, GPT-6 / GPT-5.6, Gemini 3.x, Qwen 3.x, Grok 4.x and more), with per-request fitting of `temperature`, `tool_choice` and Anthropic `max_tokens` to each model.
- In-house Agent Skills (`SKILL.md`) with scoped discovery, `useSkill` activation and host-level lockdown for multi-tenant servers.
- Multi-runtime agent execution: embedded Koog agents, plus Claude Code and OpenAI Codex as direct workflow agents or delegated sub-agents.
- Native subprocess mode for trusted local development and Docker subprocess mode for production isolation.

## Agent Runtimes

| Runtime | Integration | Best for |
| --- | --- | --- |
| Koog | Embedded Kotlin/JVM runtime | General LLM-backed agents, tools, and custom workflow strategies |
| Claude Code | `claude` CLI subprocess | Repository-aware coding agents and delegated engineering tasks |
| OpenAI Codex | `codex` CLI subprocess | Coding agents, repository automation, and delegated engineering tasks |

Claude Code and Codex can run as first-class workflow agent steps or be spawned by a parent agent through the `external_agent` tool group. Both use the same native or Docker-backed subprocess executor as code and shell steps. See [Claude Code and Codex Agents](docs/EXTERNAL_AGENTS.md) for setup and authentication.

## Quick Start

Requirements:

- JDK 21 or newer
- Docker Desktop or Docker Engine for Docker subprocess mode
- Provider API keys for Koog-backed agent workflows
- `TYPESAFE_API_KEY` only for Jev decision steps (`classifier.jev`, `repeat_until.jev`)
- Node.js and the relevant `claude` or `codex` CLI only when using Claude Code or Codex agents

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

## Provider Keys

The runtime reads provider keys from environment variables. A runtime parameter
works too: `--param <provider>_api_key=...`, for example `--param openai_api_key=...`.
Keep keys out of workflow YAML.

| Provider | Environment variable | Used by |
| --- | --- | --- |
| OpenRouter (preset default) | `OPENROUTER_API_KEY` | Koog agents |
| OpenAI | `OPENAI_API_KEY` | Koog agents |
| Anthropic | `ANTHROPIC_API_KEY` | Koog agents, Claude Code |
| Google | `GOOGLE_API_KEY` | Koog agents |
| DeepSeek | `DEEPSEEK_API_KEY` | Koog agents |
| TypeSafe (Jev) | `TYPESAFE_API_KEY` | `classifier.jev` and `repeat_until.jev` only |

Other providers follow the same `<PROVIDER>_API_KEY` pattern.

TypeSafe Jev is a decision model, not a chat model, so it cannot be an agent's
provider. See
[Jev (TypeSafe) Decisions](docs/WORKFLOW_GUIDE.md#jev-typesafe-decisions) for model
selection, `TYPESAFE_BASE_URL` / `TYPESAFE_DEFAULT_MODEL` and error handling.

```bash
export TYPESAFE_API_KEY=...
./build/install/braidrun-workflow/bin/braidrun-workflow run examples/workflows/jev-support-triage.yaml
```

## Use as a Library

Released tags are published through [JitPack](https://jitpack.io/#Braidrun/braidrun-workflow):

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.Braidrun:braidrun-workflow:1.3.0")
}
```

The library exposes the same parser and executor the CLI uses. See [Library Usage](docs/LIBRARY_USAGE.md) for a complete example.

## Documentation

- [Installation](docs/INSTALLATION.md)
- [Docker Runtime](docs/DOCKER.md)
- [CLI](docs/CLI.md)
- [Library Usage](docs/LIBRARY_USAGE.md)
- [Workflow YAML](docs/WORKFLOW_GUIDE.md)
- [Agent Presets](docs/AGENT_PRESETS.md)
- [Claude Code and Codex Agents](docs/EXTERNAL_AGENTS.md)
- [MCP](docs/MCP.md)
- [Skills](docs/SKILLS.md)
- [Prompt Caching and Token Accounting](docs/PROMPT_CACHING.md)
- [Security](docs/SECURITY.md)
- [Changelog](CHANGELOG.md)

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
