# Contributing to braidrun-workflow

Thanks for your interest in contributing! This document describes how to get
a development environment running and what we expect from contributions.

## Development setup

Requirements:

- JDK 21
- No global Gradle needed — use the bundled wrapper (Gradle 9.5)

```bash
git clone <repo-url>
cd braidrun-workflow
./gradlew build          # compile + run all tests
```

Useful tasks:

| Task | Purpose |
|------|---------|
| `./gradlew assemble` | compile and package without running tests |
| `./gradlew test` | run the unit/integration test suite |
| `./gradlew test --tests "com.fartech.agents.workflow.*"` | run a subset |
| `./gradlew publishToMavenLocal` | install the library into your local Maven repository |

Notes on the test suite:

- The suite is hermetic: it does not require LLM API keys or network access.
- Tests that need Docker or a local browser are skipped automatically when
  the dependency is unavailable.

## Making changes

1. Fork and create a feature branch from `main`.
2. Keep changes focused — one logical change per pull request.
3. Add or update tests for any behavior change. The workflow engine is
   heavily covered by tests under
   `src/test/kotlin/com/fartech/agents/workflow/`; follow the existing
   patterns there.
4. Run `./gradlew build` locally before opening the PR.
5. Update `CHANGELOG.md` (the `[Unreleased]` section) for user-visible
   changes.

## Code style

- Kotlin official code style (`kotlin.code.style=official`), 4-space indent —
  enforced via `.editorconfig`.
- Public API surface should have KDoc; internal helpers only need comments
  where the code cannot speak for itself.
- Workflow YAML examples and templates live under `workflows/templates/`;
  validate them via the parser tests, e.g.
  `./gradlew test --tests "com.fartech.agents.workflow.WorkflowTemplateValidationTest"`.

## Commit messages

- Use the imperative mood ("Add X", "Fix Y").
- Reference issues where applicable ("Fixes #12").

## Reporting bugs / proposing features

Open a GitHub issue with:

- what you did, what you expected, what happened instead
- a minimal workflow YAML or code snippet reproducing the problem
- library version / commit, JDK version and OS

For security issues, see [SECURITY.md](SECURITY.md) — please do not open
public issues.
