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
