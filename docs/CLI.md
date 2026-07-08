# CLI

Build the CLI:

```bash
./gradlew installDist
```

Use the generated binary:

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow --help
```

On macOS, if a copied or locally generated script is blocked by file provenance attributes, invoke the same script through the system shell:

```bash
/bin/sh ./build/install/braidrun-workflow/bin/braidrun-workflow --help
```

## Commands

```bash
braidrun-workflow validate <workflow.yaml>
braidrun-workflow dry-run <workflow.yaml>
braidrun-workflow run <workflow.yaml> [--var key=value] [--param key=value]
braidrun-workflow agent --preset <id> (--prompt <text> | --stdin)
braidrun-workflow list-presets
braidrun-workflow list-tools
braidrun-workflow mcp-server [--tool-group file_system,shell]
braidrun-workflow --version
braidrun-workflow --help
```

- `validate` parses a workflow and reports its step and agent counts.
- `dry-run` prints the resolved execution plan (agents, steps, dependencies) without running it.
- `run` executes a workflow file.
- `agent` runs a single preset agent against a prompt, without a workflow file.
- `list-presets` prints the available agent presets as `id`, `category`, `display name`, `description`.
- `list-tools` prints the MCP tool groups that `mcp-server` can expose.
- `mcp-server` starts a Model Context Protocol server over stdio. Repeat `--tool-group` (alias `--tools`) to select groups; omit to expose all.

## Runtime Modes

```bash
braidrun-workflow run workflow.yaml --subprocess-mode native
braidrun-workflow run workflow.yaml --subprocess-mode docker
```

`native` is convenient for local development. `docker` is the recommended mode for production and untrusted subprocess work.

## Parameters

- `--var key=value` (alias `-v`) passes an initial workflow variable to `run`.
- `--param key=value` passes a runtime parameter to the executor and tool registry.
- `--override key=value` overrides a scalar agent preset parameter for the `agent` command. Use workflow YAML `overrides` for lists and objects such as `tool_set` or `llm_config`.
- `--stdin` makes the `agent` command read the prompt from standard input instead of `--prompt`.
- `--execution-id id` sets the execution id.
- `--quiet` prints only step outputs.
- `--output file` (alias `-o`) writes the execution summary to a file.

Example:

```bash
braidrun-workflow agent \
  --preset coder \
  --prompt "Review the repository structure." \
  --param working_dir="$PWD" \
  --subprocess-mode docker
```
