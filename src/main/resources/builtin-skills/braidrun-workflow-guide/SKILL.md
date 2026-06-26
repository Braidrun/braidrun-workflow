---
name: braidrun-workflow-guide
description: Current English guide for Braidrun Workflow YAML, CLI usage, library usage, tool groups, Docker runtime, and external Claude Code / Codex agents.
version: "3.0.0"
author: braidrun
tags:
  - guide
  - workflow
  - yaml
  - cli
  - mcp
  - docker
attachments:
  - config-template.yaml
  - workflow-template.yaml
  - workflow-capability-reference.md
---

# Braidrun Workflow Guide

Use this skill when a user or agent needs current guidance for authoring or running Braidrun Workflow YAML.

## Current Runtime

- CLI binary: `braidrun-workflow`
- Main commands: `validate`, `dry-run`, `run`, `agent`, `list-presets`, `list-tools`, `mcp-server`
- Library entry points: `WorkflowParser`, `WorkflowExecutor`, `AgentPresetRegistry`
- Recommended agent definition style: `preset + overrides`
- The `asa` preset is a normal `universal_agent` preset for Apple Search Ads research and analysis.

## Authoring Rules

1. Prefer built-in presets over hand-written full agent definitions.
2. Use code steps for deterministic shell, Python, JavaScript, TypeScript, Ruby, Lua, or CLI work.
3. Use Docker subprocess mode for production or untrusted code and shell execution.
4. Keep API keys outside workflow YAML when possible.
5. Validate YAML with `braidrun-workflow validate` before running it.
6. Use `dry-run` to review the step and agent plan without calling models or tools.

## Useful Commands

```bash
braidrun-workflow validate workflow.yaml
braidrun-workflow dry-run workflow.yaml
braidrun-workflow run workflow.yaml --subprocess-mode docker
braidrun-workflow agent --preset coder --prompt "Inspect this repository."
braidrun-workflow list-presets
braidrun-workflow list-tools
braidrun-workflow mcp-server --tool-group file_system,shell,git
```

## External Agents

Use the `external_agent` tool group when the parent agent should spawn Claude Code or OpenAI Codex.

Common runtime parameters:

- `external_agent_claude_command`
- `external_agent_claude_model`
- `external_agent_codex_command`
- `external_agent_codex_model`
- `external_agent_claude_extra_args`
- `external_agent_codex_extra_args`

Prefer API-key authentication for stable automation.
