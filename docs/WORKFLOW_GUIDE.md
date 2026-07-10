# Workflow YAML

Every workflow has a name, optional agents, and a list of steps.

```yaml
name: example
version: 1.0.0
agents:
  analyst:
    preset: data_analyst
workflow:
  - step: summarize
    agent: analyst
    input: "Summarize the data in ./data/input.csv."
```

## Display Translations

Use `translations` for user-visible workflow and agent metadata. The canonical
`name` and `description` remain English defaults. Runtime fields such as step
inputs, system prompts, code, variables, state names, and extraction markers are
not localized.

```yaml
name: code-review
version: 1.0.0
description: Generic code review workflow for repository changes.
translations:
  en:
    name: Code Review
    description: Generic code review workflow for repository changes.
  zh:
    name: 代码审查
    description: 面向仓库改动的通用代码审查工作流。
agents:
  reviewer:
    preset: coder
    description: Reviews changes for correctness and regressions.
    translations:
      en:
        description: Reviews changes for correctness and regressions.
      zh:
        description: 审查改动的正确性与回归风险。
workflow:
  - step: review
    agent: reviewer
    input: "Review the current repository changes."
```

Bundled templates provide `en`, `zh`, `zhHant`, `ja`, `ko`, `es`, `fr`, `de`,
`ar`, `pt`, and `vi`. Consumers should fall back to the canonical fields when a
custom workflow does not provide the requested locale.

## Code Step

```yaml
name: hello-code
version: 1.0.0
agents: {}
workflow:
  - step: hello
    code:
      language: bash
      script: |
        echo "Hello from Braidrun Workflow"
```

Supported code languages are `python`, `javascript`, `typescript`, `bash`, `ruby`, `lua`, and `cli`.

## Agent Step

```yaml
agents:
  coder:
    preset: coder
    overrides:
      max_iterations: 32
workflow:
  - step: inspect
    agent: coder
    input: "Inspect this repository and list the most important tests."
```

## Parallel Steps

Use `depends_on` to make independent steps run when their dependencies are satisfied.

```yaml
workflow:
  - step: collect
    agent: researcher
    input: "Collect source material."
  - step: analyze
    agent: analyst
    depends_on: [collect]
    input: "Analyze the collected material."
  - step: write
    agent: writer
    depends_on: [analyze]
    input: "Write the final report."
```

## Runtime Variables

CLI variables passed with `--var key=value` are available to the workflow execution context.

```bash
braidrun-workflow run workflow.yaml --var topic="agent workflow runtime"
```

## Sub-Workflows

Use `sub_workflow` when one workflow should call another workflow as a reusable module. The CLI uses a file-system resolver rooted at the parent workflow directory and the current working directory.
