# Getting Started

Start with the CLI if you want to run workflows from a terminal. Start with the library API if you are embedding the runtime in a Kotlin application.

## Run a Code-Only Workflow

```bash
./gradlew installDist
./build/install/braidrun-workflow/bin/braidrun-workflow run examples/workflows/hello-code.yaml
```

This workflow does not call an LLM. It is useful for confirming the parser, executor, and code-step runtime.

## Run an Agent Workflow

Set an API key for the provider used by your preset configuration. The built-in presets default to OpenRouter-style configuration unless you override the model settings.

```bash
export OPENROUTER_API_KEY=...
./build/install/braidrun-workflow/bin/braidrun-workflow run examples/workflows/research-summary.yaml
```

## Validate Before Running

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow validate examples/workflows/research-summary.yaml
./build/install/braidrun-workflow/bin/braidrun-workflow dry-run examples/workflows/research-summary.yaml
```

`validate` parses and checks the workflow. `dry-run` prints the agent and step plan without calling models or tools.

## Next Steps

- Use [CLI](CLI.md) for command reference.
- Use [Workflow YAML](WORKFLOW_GUIDE.md) for schema examples.
- Use [Library Usage](LIBRARY_USAGE.md) to embed the executor.
- Use [Docker Runtime](DOCKER.md) before running untrusted code or shell tools.
