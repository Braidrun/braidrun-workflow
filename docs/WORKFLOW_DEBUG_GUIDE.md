# Debugging Workflows

Use these commands before running an LLM-backed workflow:

```bash
braidrun-workflow validate workflow.yaml
braidrun-workflow dry-run workflow.yaml
```

Common issues:

- Unknown preset: run `braidrun-workflow list-presets`.
- Unknown tool group: run `braidrun-workflow list-tools`.
- Docker failure: verify Docker is installed and the configured exec images are available.
- Missing model credentials: set provider API keys through environment variables or runtime parameters.
- Sub-workflow not found: keep child workflow YAML near the parent file or run from a directory that contains it.
