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

## Use as a Library

### From JitPack (published releases)

Released tags are available through [JitPack](https://jitpack.io/#Braidrun/braidrun-workflow). Add the repository and the coordinate to your build:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.Braidrun:braidrun-workflow:1.0.5")
}
```

Groovy DSL:

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.Braidrun:braidrun-workflow:1.0.5'
}
```

### From a local Maven repository

To build and consume the library without JitPack:

```bash
./gradlew publishToMavenLocal
```

This publishes `com.fartech.braidrun:braidrun-workflow:1.0.5` to `~/.m2`. Add `mavenLocal()` to your repositories and depend on that coordinate.

See [Library Usage](LIBRARY_USAGE.md) for the parser/executor APIs.
