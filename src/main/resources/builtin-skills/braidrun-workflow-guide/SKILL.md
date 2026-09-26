---
name: braidrun-workflow-guide
description: Current English guide for Braidrun Workflow YAML, CLI usage, library usage, tool groups, Docker runtime, external Claude Code / Codex agents, and TypeSafe Jev decision steps.
version: "3.1.0"
author: braidrun
tags:
  - guide
  - workflow
  - yaml
  - cli
  - mcp
  - docker
  - jev
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
7. When a TypeSafe key is available and a route or quality gate needs a calibrated decision, prefer a Jev step (`classifier.jev`, `repeat_until.jev`) over an LLM judge parsed with regex.

## Models and Agent Settings

- Set the model in `overrides.llm_config` (`models`, `fallback`, `cascade_fallbacks`); snake_case and camelCase keys both work. Pick ids from `src/main/resources/models/*.yaml`: first-party Anthropic ids are dashed (`claude-opus-5`), OpenRouter ids dotted (`claude-sonnet-4.5`).
- The engine fits params per model: `temperature` is dropped where rejected (Claude Opus 4.7+ / Sonnet 5 / Fable 5, GPT-5.x) and forced `tool_choice` becomes `auto` where unsupported, so one config can mix model families. Direct Anthropic defaults to 16K `max_tokens` (64K streaming).
- `num_choices > 1` is unmetered and capped at 1 on hosted Braidrun; leave it unset.
- Anthropic, Gemini 3+ and Responses-only OpenAI vision models see images from `browser_screenshot` and MCP tools. Bound cost with `tool_result_images_max_per_request` (default 4) and `tool_result_images_max_per_run` (default 24), or `tool_result_images_enabled: false`.
- Skills: `useSkill` always returns the full skill; per-skill MCP servers do not auto-start.

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

## Typed Decisions with TypeSafe Jev

Jev is TypeSafe AI's decision model. It answers typed `choice` / `score` / `noul`
questions with calibrated probabilities and cannot write text, so it is never an
agent `llm.provider`.

- `classifier.jev`: add `jev: {}` (optional `model`, `min_confidence`, `questions`,
  `composites`) and omit `agent`. Jev picks the category, and extra questions are
  answered in the same request. Besides `output_variable`, it writes
  `<out>_confidence`, `<out>_probabilities` and per-question variables such as
  `<id>_yes`, `<id>_level` and `<id>_normalized`.
- `repeat_until.jev`: typed questions grade each iteration instead of
  `evaluate_agent`. The critique goes to `{{steps.<step>:evaluate.output}}`.
- Branch with plain conditions on those variables, for example
  `team_confidence >= 0.8` or `is_urgent_yes == 'true'`. Conditions never call a model.
- Key: `TYPESAFE_API_KEY` or `--param typesafe_api_key=...`. Model: `jev.model`, then
  `typesafe_model`, `TYPESAFE_DEFAULT_MODEL` or `jev-latest`.
- A missing or rejected key fails the step. Transient errors fall back to
  `default_category` when it is set.
- Limits: 255 categories or options, 2..10 score levels, 64k tokens per request.
  English is most accurate.

See `workflow-capability-reference.md` for field tables. Runnable examples:

- `examples/workflows/jev-support-triage.yaml`
- `examples/workflows/jev-quality-loop.yaml`
