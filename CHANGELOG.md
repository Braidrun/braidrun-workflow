# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.0.6]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.6
[1.0.5]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.5
