# Claude Code and Codex Agents

Braidrun Workflow can delegate a task to external agent runtimes through the `external_agent` tool group:

- Claude Code through the `claude` CLI
- OpenAI Codex through the `codex` CLI

These are full external runtimes launched as subprocesses through the same native or Docker executor used by shell and code tools.

## Enable the Tool Group

Use an agent preset or override that includes:

```yaml
tool_set:
  - external_agent
```

The built-in `coder` preset is the normal starting point for coding workflows; add `external_agent` only when the parent agent should be able to spawn Claude Code or Codex.

## Claude Code

Native mode:

```bash
npm install -g @anthropic-ai/claude-code
export ANTHROPIC_API_KEY=...
```

Runtime parameters:

```bash
--param external_agent_claude_command=claude
--param external_agent_claude_model=<claude-model-or-alias>
```

Alternative `npx` mode:

```bash
--param external_agent_claude_command=npx
--param external_agent_claude_extra_args=-y,@anthropic-ai/claude-code
```

## Codex

Native mode:

```bash
npm install -g @openai/codex
export OPENAI_API_KEY=...
```

Runtime parameters:

```bash
--param external_agent_codex_command=codex
--param external_agent_codex_model=<openai-model-id>
```

Alternative `npx` mode:

```bash
--param external_agent_codex_command=npx
--param external_agent_codex_extra_args=-y,@openai/codex
```

## Authentication

The external agent tools resolve credentials from runtime parameters, configured provider keys, or environment variables.

API-key mode is recommended for stable production runs:

- Claude: `anthropic_api_key` or `ANTHROPIC_API_KEY`
- Codex: `openai_api_key` or `OPENAI_API_KEY`

Subscription-token modes exist for account-owner workflows, but API-key mode is the safer default for public or team deployments.

## Docker Notes

In Docker mode, the `claude` and `codex` binaries must exist inside the configured exec image, or the command must use `npx`. The working directory is mounted into the subprocess workspace by the executor.

## Minimal Workflow Example

```yaml
name: codex-delegation
version: 1.0.0
agents:
  coder:
    preset: coder
    overrides:
      tool_set:
        - external_agent
workflow:
  - step: ask_codex
    agent: coder
    input: "Use the Codex external agent to inspect this repository and summarize the CLI entry points."
```

Run it with API-key authentication:

```bash
braidrun-workflow run workflow.yaml \
  --subprocess-mode docker \
  --param openai_api_key="$OPENAI_API_KEY" \
  --param external_agent_codex_command=npx \
  --param external_agent_codex_extra_args=-y,@openai/codex \
  --param external_agent_codex_model=<openai-model-id>
```
