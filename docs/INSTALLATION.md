# Installation

This repository can be used in two ways:

- as a CLI for running workflow YAML locally or in automation
- as a Kotlin/JVM library embedded in another application

Use [Braidrun](https://braidrun.com) for the hosted product experience with visual workflow management, execution history, monitoring, and team operations.

## Requirements

- JDK 21 or newer
- Docker Desktop or Docker Engine for Docker subprocess mode
- Node.js only when running Claude Code or Codex in native mode
- Provider API keys for LLM-backed workflows

## Build the CLI

```bash
git clone https://github.com/braidrun/braidrun-workflow.git
cd braidrun-workflow
./gradlew installDist
```

The generated binary is:

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow
```

Verify the installation:

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow validate examples/workflows/hello-code.yaml
./build/install/braidrun-workflow/bin/braidrun-workflow run examples/workflows/hello-code.yaml --quiet
```

Expected output:

```text
OK hello-code (1 step(s), 0 agent(s))
Hello from Braidrun Workflow
```

## Install Docker

Docker is required when you use `--subprocess-mode docker`. It is recommended for production and for workflows that execute untrusted code, shell commands, Git operations, browser automation, Claude Code, or Codex.

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

Install Docker Desktop with the WSL 2 backend, then verify from PowerShell:

```powershell
docker version
docker run --rm hello-world
```

See [Docker Runtime](DOCKER.md) for runtime images, network setup, and troubleshooting.

## Publish as a Local Library

```bash
./gradlew publishToMavenLocal
```

Then depend on the published Maven coordinate from your application and use the parser/executor APIs shown in [Library Usage](LIBRARY_USAGE.md).
