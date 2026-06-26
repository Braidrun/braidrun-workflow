# Docker Runtime

Braidrun Workflow can run subprocess-backed work through Docker. This is the recommended mode for production and for workflows that can execute code, shell commands, Git, browser automation, Claude Code, or Codex.

Docker mode is selected with:

```bash
braidrun-workflow run workflow.yaml --subprocess-mode docker
```

## What Docker Protects

Docker mode routes subprocess-backed tools through ephemeral containers instead of direct host processes. This applies to:

- code steps
- shell tools
- Git tools
- browser automation
- external Claude Code and Codex agents

Docker is not required for YAML validation, dry-run planning, preset listing, tool listing, or embedding the parser without executing subprocess tools.

## Install Docker

macOS:

```bash
brew install --cask docker
open /Applications/Docker.app
docker version
docker run --rm hello-world
```

Linux:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"
newgrp docker
docker version
docker run --rm hello-world
```

Windows:

Install Docker Desktop with the WSL 2 backend, then run:

```powershell
docker version
docker run --rm hello-world
```

## Runtime Images

Docker mode uses executor images selected by language/tool type:

- `braidrun/exec-shell:<tag>`
- `braidrun/exec-python-node:<tag>`
- `braidrun/exec-node:<tag>`

The default tag is `1.0`. Override it with:

```bash
braidrun-workflow run workflow.yaml \
  --subprocess-mode docker \
  --param docker_exec_image_tag=1.0
```

Pull images ahead of time in deployment environments:

```bash
docker pull braidrun/exec-shell:1.0
docker pull braidrun/exec-python-node:1.0
docker pull braidrun/exec-node:1.0
```

## Egress Network

The Docker executor can use a named egress network. Create one explicitly when your deployment expects it:

```bash
docker network create workflow-egress-only
```

Pass the network name:

```bash
braidrun-workflow run workflow.yaml \
  --subprocess-mode docker \
  --param docker_egress_network=workflow-egress-only
```

## Working Directory and Output Directory

Set explicit directories for repeatable production runs:

```bash
braidrun-workflow run workflow.yaml \
  --subprocess-mode docker \
  --param working_dir="$PWD" \
  --param output_dir="$PWD/output"
```

## External Agents in Docker

Claude Code and Codex must be available inside the node-capable executor image. You can either bake the binaries into the image or use `npx`:

Use a workflow YAML file that enables `external_agent`, such as
`examples/workflows/codex-delegation.yaml`, then pass the external-agent
runtime parameters:

```bash
braidrun-workflow run examples/workflows/codex-delegation.yaml \
  --subprocess-mode docker \
  --param external_agent_codex_command=npx \
  --param external_agent_codex_extra_args=-y,@openai/codex
```

See [Claude Code and Codex Agents](EXTERNAL_AGENTS.md) for authentication and model parameters.

## Troubleshooting

- `Cannot connect to the Docker daemon`: start Docker Desktop or the Docker service.
- `permission denied while trying to connect to Docker`: add your user to the Docker group on Linux, then open a new shell.
- `image not found`: pull the executor image or set `docker_exec_image_tag`.
- `network not found`: create the network or set `docker_egress_network` to an existing network.
- Workflow works in native mode but not Docker mode: check mounted `working_dir`, `output_dir`, and whether required binaries exist inside the executor image.
