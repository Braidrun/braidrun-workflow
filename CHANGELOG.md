# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.21]

### Fixed

- Emit Claude Code usage after every assistant turn and Codex usage when a turn
  completes, so long-running external-agent steps expose token consumption
  before the subprocess exits.
- Preserve the final token aggregate when Claude Code or Codex exits with an
  error, including Claude's maximum-turns termination.

## [1.0.20]

### Fixed

- Make Docker subprocess stdin available before the container process starts by
  redirecting from a bounded, short-lived file bind-mounted read-only at a fixed
  container path. This removes the docker-java attach race that could close the
  hijacked connection before Claude Code or Codex received their prompt, causing
  `no stdin data received` and `Input must be provided` failures. The independent
  mount also works when the host workspace is not traversable by container uid
  2000. Command arguments remain separate from shell source, and the temporary
  input file is deleted after every execution.

## [1.0.19]

### Added

- Added a SQLite-backed `DocumentStore` implementation and `sqlite` storage
  profile for embedded and desktop deployments that do not need MongoDB.

### Fixed

- Send Claude Code and Codex prompts through bounded subprocess stdin instead
  of positional command-line arguments, preventing Linux `MAX_ARG_STRLEN` /
  `E2BIG` failures for large workflow and execution context. Docker delivery
  used the existing one-shot stdin attach.
- Bound every remaining external-agent argv entry and total argv size before
  spawning. Oversized Claude system prompts retain their beginning and end;
  invalid oversized CLI configuration now fails with an explicit diagnostic
  instead of the operating system's opaque `argument list too long` error.

## [1.0.17]

### Fixed

- Enforced the `timeout.total` budget in the parallel `iterate_over` branch.
  Only the sequential branch checked the deadline between items, so a
  `parallel: true` fan-out ran unbounded past the workflow budget — and when
  such a step was the last one there was no later step boundary to catch the
  overrun either, leaving the budget unenforced entirely. Iterations already
  running are not interrupted; once the budget is spent no further iteration
  starts, matching the sequential branch. Manual-approval wait stays excluded
  from the budget as before.

## [1.0.12]

### Fixed

- Made Docker-mounted Claude subscription config directories writable by the
  fixed sandbox UID so session state and Bash setup no longer fail with EACCES.
- Declared the OpenAI Chat Completions endpoint for direct Kimi/Moonshot
  models so Koog can select request parameters for `kimi-k3` and related models.

## [1.0.11]

### Added

- Added Kimi K3 model metadata, capabilities, and provider parameter support.

### Fixed

- Reused the injected workflow subprocess executor for external Claude/Codex
  tools created by agent-based orchestrators and nested sub-agents.

## [1.0.10]

### Fixed

- Reused the Web-injected subprocess executor for managed external Claude/Codex
  agents so container mounts and host-to-container path rewriting remain active.

## [1.0.9]

### Fixed

- Kept Codex subscription `CODEX_HOME` directories isolated per invocation in
  workflow executions even though `WorkflowExecutor` injects a runtime
  `session_id`; only chat sessions without an `execution_id`, explicit resume
  calls, and configured homes retain persistent Codex state.

## [1.0.8]

### Fixed

- Isolated ephemeral Codex subscription `CODEX_HOME` directories per external-agent
  invocation, preventing one concurrent child from deleting another child's
  authentication and state directory while it is still starting or running.
- Preserved stable Codex home directories for configured and resumable sessions.

## [1.0.7]

### Added

- Complete localized agent descriptions for all 59 agents in the bundled test
  workflow templates across the 11 supported display locales.
- Regression coverage requiring every test-workflow agent to declare a canonical
  English description and complete localized descriptions.

### Fixed

- Isolated Claude Code configuration directories by execution and step so
  concurrent external-agent runs no longer overwrite each other's credentials.
- Made Codex home-directory creation safe when concurrent runs initialize the
  same execution directory.

## [1.0.6]

### Added

- Display-only `translations` metadata for workflow names/descriptions and
  agent names/descriptions, with locale-specific fallback handled by consumers.
- Complete `en`, `zh`, `zhHant`, `ja`, `ko`, `es`, `fr`, `de`, `ar`, `pt`, and
  `vi` display metadata for every bundled workflow template.
- Bundled workflow template YAML resources and an index so library consumers can
  discover the public template catalog from the published jar.
- Regression coverage that validates locale completeness, canonical English
  descriptions, preserved technical identifiers, and English-only runtime text.

### Changed

- Kept workflow prompts, code, variables, markers, and test behavior canonical
  in English; localization applies only to user-visible metadata.

### Removed

- Removed the obsolete `test-text-summarizer` test template.

## [1.0.5]

First public release that ships the command-line interface alongside the
embeddable runtime library. Versions 1.0.0 through 1.0.3 were library-only
pre-CLI snapshots; 1.0.4 was not published.

### Added

- Command-line interface (`braidrun-workflow`) with `run`, `validate`,
  `dry-run`, `agent`, `list-presets`, `list-tools`, and `mcp-server` commands.
- `--version` now reports the build version from the jar manifest.

### Changed

- Removed internal regression templates that referenced private business
  skills so the published tree contains only generic, brand-neutral examples.

### Runtime

The runtime library provides:

- Workflow YAML runtime supporting agent steps, deterministic code steps,
  parallel execution, conditions, iteration, retries, state machines, and
  sub-workflows.
- Kotlin/JVM library API (`WorkflowParser`, `WorkflowExecutor`,
  `FileSystemWorkflowResolver`) for embedding the runtime in other applications.
- Built-in agent presets for coding, research, writing, data analysis,
  documents, browser automation, DevOps, communication, and marketing research.
- Built-in tool groups for files, shell, Git, HTTP, browser automation,
  documents, databases, RAG, email, image processing, and MCP.
- External coding-agent delegation through Claude Code and OpenAI Codex
  subprocesses.
- Native subprocess mode for trusted local development and Docker subprocess
  mode for production isolation.
- MCP server mode exposing selected tool groups over the Model Context Protocol.
- LLM provider integration via the Koog AI Agents framework, including
  Anthropic, OpenAI, DeepSeek, OpenRouter, Z.ai, and NVIDIA model registries.

[1.0.20]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.20
[1.0.19]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.19
[1.0.17]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.17
[1.0.9]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.9
[1.0.8]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.8
[1.0.7]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.7
[1.0.6]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.6
[1.0.5]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.5
