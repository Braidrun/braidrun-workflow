# Tutorial

1. Build the CLI.

```bash
./gradlew installDist
```

2. Validate an example.

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow validate examples/workflows/hello-code.yaml
```

3. Run it.

```bash
./build/install/braidrun-workflow/bin/braidrun-workflow run examples/workflows/hello-code.yaml
```

4. Run an agent preset directly.

```bash
export OPENROUTER_API_KEY=...
./build/install/braidrun-workflow/bin/braidrun-workflow agent \
  --preset researcher \
  --prompt "Find three recent facts about Kotlin agent frameworks and summarize them."
```

5. Embed the same runtime from Kotlin.

See [Library Usage](LIBRARY_USAGE.md).
