# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.6]

### Added

- `braidrun-workflow run --approval-dir <dir>` (`FileApprovalHandler`): headless
  manual approvals for runs without the web platform. Each request is written to
  `<dir>/requests/<id>.json`; a decision file in `<dir>/decisions/<id>.json`
  resolves it (the handler renames consumed decisions), and
  `<dir>/policy.json` can auto-approve named steps after a delay (soft gates).
  Used by the App Factory Mac runner (2026-09-16).
- `external_agent_codex_auth_file`: native (non-Docker) subscription runs can
  hand Codex an owner-only `auth.json` from outside the workspace instead of an
  inline credential. The file never appears in argv, events or artifacts; a
  token the CLI refreshes is written back atomically with private permissions
  and a compare-and-swap baseline, so concurrent runs and a changed source never
  overwrite each other.

### Fixed

- The bash prelude that restores spilled workflow variables
  (`SHELL_SPILLED_ENV_BRIDGE`) read each spilled file with bash's own
  `read -d ''`, which walks the file byte by byte. Once agent step outputs
  reached the 8 MiB stream cap, every bash code step spent ~4 s per spilled
  variable before its first command ran; with several spilled variables the
  prelude alone outlived 60–120 s step timeouts and the step failed as
  `[TIMED OUT]` with no output (App Factory, 2026-09-17). Values are now
  restored with `cat` + `printf -v` (trailing newlines preserved,
  bash 3.2 compatible); the sandbox test restores a multi-MiB value through a
  real shell.
- Claude subscription rate-limit cool-downs are taken only from
  `rate_limit_event` entries whose status is `rejected`. Claude Code also
  streams warning events for windows that are merely filling up; reading their
  `resetsAt` parked a healthy credential for hours.

## [1.1.5]

### Added

- `WorkflowStep.idempotent` (YAML `idempotent: true`). The engine does not read
  it; it is metadata the host reads to decide whether a step may be replayed
  during crash recovery or a rolling-deploy handoff. Until now the field
  existed only in the host's own model, so a step loaded from a template YAML
  could never be marked replay-safe and every handoff of a workflow parked on
  an approval was refused. Defaults to `false`, so existing workflows keep the
  conservative behaviour.

## [1.1.4]

### Fixed

- `WorkflowExecutor.processExtract` now maps a `/output` or `/output/...` value
  extracted from a step's output back to the execution's host output directory
  when the workflow runs in Docker subprocess mode. Agents and code steps see
  the output directory as `/output` and echo that path in their `key=value`
  lines; host-side consumers of the extracted variable resolved the literal
  container path and silently read nothing (the ASA keyword optimizer dropped
  every AI open-keyword review this way). Templates no longer need per-step
  translation. `remapContainerOutputPath` is the pure helper behind it.
- `WorkflowExecutor.executeWithRetry` records attempts that return
  `success=false` without throwing. Previously such an attempt fell through the
  loop unrecorded, so a step whose final attempt failed validation was reported
  with an earlier attempt's stale exception (or a generic message), and the
  monitor's final `completeStep` carried the wrong error. The last attempt's
  own `StepExecutionResult` is now returned, with its error and output intact.

## [1.1.3]

### Added

- `WorkflowExecutor` accepts `onCodexAuthJsonRotated` and threads it, together
  with the Codex credential pool, into every `ExternalAgentTools` it builds:
  the Codex Agent step and the `external_agent` tool group of managed agents
  and orchestrators. When the Codex CLI refreshes its ChatGPT tokens inside the
  per-run `CODEX_HOME`, the host now receives the rotated `auth.json` and the
  id of the credential that produced it before the directory is removed.
  Without the hook the workflow path discarded every in-sandbox refresh, so
  the stored refresh token went stale while the run itself succeeded —
  observed in production as `invalid_refresh_token` on credentials still in
  daily use.

### Changed

- The Codex Agent step draws its login from the runtime's Codex credential
  pool (ordered candidates, cooldowns, pinning) like the `external_agent` tool
  group already did, instead of only the `external_agent_codex_auth_json`
  parameter. The parameter remains the fallback when no pool is supplied.

### Fixed

- A Claude subscription five-hour session limit could cool the credential down
  for a week. Claude Code streams a `rate_limit_event` for the *other* window
  too — typically the seven-day one as `allowed_warning`, carrying a reset up
  to a week away — before the five-hour window rejects, and
  `claudeRateLimitResetAtMillis` took the first `"resetsAt"` it found in the
  output regardless of which event it belonged to. Observed 2026-09-02 in
  production: `You've hit your session limit · resets 2:40pm (UTC)` cooled the
  card until 2026-09-09, the pool fell back to its one remaining card, that
  card hit its own five-hour limit, and every Claude step failed with
  `All accessible Claude subscription credentials are unavailable`.
  The reset is now taken only from `rate_limit_event`s whose `status` is
  `rejected` (the latest reset when several windows reject at once), then from
  a `resetsAt` on the terminal `result` record, then from the human text.
  Warnings never set a cooldown.

## [1.1.1]

### Fixed

- The strict read guard's extension whitelist covered no Apple text format, so
  an agent working on an iOS or macOS project could write `ContentView.swift`
  and then be denied when it read the same file back — a `SecurityException`
  through `SandboxedFileSystemProvider`, a security-error string through
  `SafeFileTools.readFile`. Metadata operations (`exists`, `list`, `metadata`,
  `size`) deliberately bypass the guard, so such a file existed and listed but
  could never be read, and `copy`/`move` run the guard on their source, so
  renaming to an allowed extension was no way around it. The guard is only
  active under `strict_sandbox`, so this surfaced in hardened deployments
  rather than in local development.
  Both whitelists now accept the formats a Swift project is made of: `swift`,
  `m`, `mm`, `h`, `modulemap`, `podspec`, `plist`, `xcprivacy`, `xcstrings`,
  `strings`, `stringsdict`, `pbxproj`, `xcconfig`, `entitlements`, `xcscheme`,
  `xcworkspacedata`, `resolved`, `storyboard`, and `xib`. No binary format was
  added — `ipa`, `dylib`, `xcarchive`, `car`, and `nib` remain blocked, and the
  new tests assert that they stay blocked.
  `FileReadGuard.DEFAULT_READ_EXTENSION_WHITELIST` and
  `SafeFileTools.ALLOWED_TEXT_EXTENSIONS` still differ on purpose: the former
  gates reading content and continues to exclude secret-bearing config formats
  that the latter allows on write.

## [1.1.0]

### Added

- Fenced conditional writes for multi-writer coordination:
  `DocumentStore.putFenced(document, fence)` and
  `TypedDocumentCollection.saveFenced(entity, fence)`. A fenced write records
  a monotonic token on the stored row (`StoredDocument.fence`, new optional
  envelope column) and is rejected when a NEWER token already owns the row —
  the primitive a host application needs to stop a writer whose ownership
  lease silently expired (e.g. a node resuming a workflow execution after a
  GC pause outlived its distributed lock) from clobbering the new owner's
  state. Plain `put` still replaces unconditionally and clears the fence, so
  unfenced writers and pre-upgrade rows keep today's semantics.
  - Mongo: gated `replaceOne` with an insert fallback; a concurrent
    first-insert race is resolved via the host's unique envelope index on
    `(namespace, collection, id)`.
  - SQLite: single-statement upsert with a `DO UPDATE ... WHERE` fence gate;
    existing databases gain the `fence` column via an in-place guarded
    `ALTER TABLE` on open.
  - In-memory: per-key atomic `compute`.
- Breaking for custom `DocumentStore` implementations only: the interface
  gained the abstract `putFenced` member (hence the minor version bump). The
  three shipped stores all implement it; downstream code that merely USES a
  store is source- and binary-compatible.

## [1.0.24]

### Fixed

- Per-turn token usage is reported as an increment instead of only the first
  sighting of a turn. Claude reports one assistant turn under the same
  `message.id` more than once and the numbers grow as the turn proceeds: the
  message-start snapshot carries the real input and cache counts but a
  placeholder `output_tokens`, and the completed message carries the real
  output. Keeping the first sighting froze the placeholder, so a turn that
  generated 5,704 tokens was published as 2. Summing
  `claude_code_sub_agent_usage` events now yields the turn's real usage;
  prompt-cache counters are reported once rather than on every event.

## [1.0.23]

### Added

- Fail over between Codex subscription credentials, matching the Claude pool:
  `ExternalAgentTools` takes a `codexCredentialProvider`, cools a credential
  down on a ChatGPT quota stop, and moves to the next candidate on a quota stop
  or on a login the provider has rejected. Supersedes 1.0.22's classify-only
  behaviour.
- Pools are strictly per vendor. Each engine draws from its own provider, so an
  exhausted Claude subscription is never covered by a ChatGPT one, and the other
  way round — different vendor, different account, different billing subject.

### Changed

- Codex failover replays the invocation only while the run shows no side effect
  in its JSON stream (`command_execution`, `file_change`, `patch_apply`,
  `mcp_tool_call`, `web_search`); a run that already acted is cooled down but
  not replayed. Same rule Claude has followed since the pool landed.
- **Breaking:** `onCodexAuthJsonRotated` now receives `(credentialId, authJson)`
  instead of `(authJson)`. With a pool, the rotated `auth.json` belongs to the
  credential the run actually used; writing it back to whichever credential the
  caller resolved first would overwrite a different ChatGPT account's login.

## [1.0.22]

### Fixed

- Recognise the Claude subscription session-limit shape (`is_error` + HTTP 429
  carrying the CLI's own limit sentence, with no `rate_limit_event` and no
  `terminal_reason`), so an exhausted credential is cooled down and failover can
  pick another one. A bare 429 with no corroboration still does not cool a
  credential.
- Derive the cooldown deadline from the CLI's `resets <time> (UTC)` text when no
  machine-readable `resetsAt` is present, instead of falling back to the 15
  minute default and re-selecting a still-limited credential.
- Lead external-agent failure excerpts with the decoded terminal fields
  (`subtype` / `is_error` / `api_error_status` / `result`, and Codex's
  `turn.failed` error message) instead of the last 2000 characters of output,
  which truncated the reason away.

### Added

- Classify ChatGPT subscription quota stops from Codex's error channel and
  report them through a `codex_subscription_rate_limited` event. Codex runs on a
  single `auth.json`, so this classifies and reports only — there is no
  credential failover.

## [1.0.21]

### Fixed

- Emit Claude Code usage after every assistant turn and Codex usage when a turn
  completes, so long-running external-agent steps expose token consumption
  before the subprocess exits.
- Preserve the final token aggregate when Claude Code or Codex exits with an
  error, including Claude's maximum-turns termination.

## [1.0.20]

### Fixed

- Make Docker subprocess stdin available before the container process starts by
  redirecting from a bounded, short-lived file bind-mounted read-only at a fixed
  container path. This removes the docker-java attach race that could close the
  hijacked connection before Claude Code or Codex received their prompt, causing
  `no stdin data received` and `Input must be provided` failures. The independent
  mount also works when the host workspace is not traversable by container uid
  2000. Command arguments remain separate from shell source, and the temporary
  input file is deleted after every execution.

## [1.0.19]

### Added

- Added a SQLite-backed `DocumentStore` implementation and `sqlite` storage
  profile for embedded and desktop deployments that do not need MongoDB.

### Fixed

- Send Claude Code and Codex prompts through bounded subprocess stdin instead
  of positional command-line arguments, preventing Linux `MAX_ARG_STRLEN` /
  `E2BIG` failures for large workflow and execution context. Docker delivery
  used the existing one-shot stdin attach.
- Bound every remaining external-agent argv entry and total argv size before
  spawning. Oversized Claude system prompts retain their beginning and end;
  invalid oversized CLI configuration now fails with an explicit diagnostic
  instead of the operating system's opaque `argument list too long` error.

## [1.0.17]

### Fixed

- Enforced the `timeout.total` budget in the parallel `iterate_over` branch.
  Only the sequential branch checked the deadline between items, so a
  `parallel: true` fan-out ran unbounded past the workflow budget — and when
  such a step was the last one there was no later step boundary to catch the
  overrun either, leaving the budget unenforced entirely. Iterations already
  running are not interrupted; once the budget is spent no further iteration
  starts, matching the sequential branch. Manual-approval wait stays excluded
  from the budget as before.

## [1.0.12]

### Fixed

- Made Docker-mounted Claude subscription config directories writable by the
  fixed sandbox UID so session state and Bash setup no longer fail with EACCES.
- Declared the OpenAI Chat Completions endpoint for direct Kimi/Moonshot
  models so Koog can select request parameters for `kimi-k3` and related models.

## [1.0.11]

### Added

- Added Kimi K3 model metadata, capabilities, and provider parameter support.

### Fixed

- Reused the injected workflow subprocess executor for external Claude/Codex
  tools created by agent-based orchestrators and nested sub-agents.

## [1.0.10]

### Fixed

- Reused the Web-injected subprocess executor for managed external Claude/Codex
  agents so container mounts and host-to-container path rewriting remain active.

## [1.0.9]

### Fixed

- Kept Codex subscription `CODEX_HOME` directories isolated per invocation in
  workflow executions even though `WorkflowExecutor` injects a runtime
  `session_id`; only chat sessions without an `execution_id`, explicit resume
  calls, and configured homes retain persistent Codex state.

## [1.0.8]

### Fixed

- Isolated ephemeral Codex subscription `CODEX_HOME` directories per external-agent
  invocation, preventing one concurrent child from deleting another child's
  authentication and state directory while it is still starting or running.
- Preserved stable Codex home directories for configured and resumable sessions.

## [1.0.7]

### Added

- Complete localized agent descriptions for all 59 agents in the bundled test
  workflow templates across the 11 supported display locales.
- Regression coverage requiring every test-workflow agent to declare a canonical
  English description and complete localized descriptions.

### Fixed

- Isolated Claude Code configuration directories by execution and step so
  concurrent external-agent runs no longer overwrite each other's credentials.
- Made Codex home-directory creation safe when concurrent runs initialize the
  same execution directory.

## [1.0.6]

### Added

- Display-only `translations` metadata for workflow names/descriptions and
  agent names/descriptions, with locale-specific fallback handled by consumers.
- Complete `en`, `zh`, `zhHant`, `ja`, `ko`, `es`, `fr`, `de`, `ar`, `pt`, and
  `vi` display metadata for every bundled workflow template.
- Bundled workflow template YAML resources and an index so library consumers can
  discover the public template catalog from the published jar.
- Regression coverage that validates locale completeness, canonical English
  descriptions, preserved technical identifiers, and English-only runtime text.

### Changed

- Kept workflow prompts, code, variables, markers, and test behavior canonical
  in English; localization applies only to user-visible metadata.

### Removed

- Removed the obsolete `test-text-summarizer` test template.

## [1.0.5]

First public release that ships the command-line interface alongside the
embeddable runtime library. Versions 1.0.0 through 1.0.3 were library-only
pre-CLI snapshots; 1.0.4 was not published.

### Added

- Command-line interface (`braidrun-workflow`) with `run`, `validate`,
  `dry-run`, `agent`, `list-presets`, `list-tools`, and `mcp-server` commands.
- `--version` now reports the build version from the jar manifest.

### Changed

- Removed internal regression templates that referenced private business
  skills so the published tree contains only generic, brand-neutral examples.

### Runtime

The runtime library provides:

- Workflow YAML runtime supporting agent steps, deterministic code steps,
  parallel execution, conditions, iteration, retries, state machines, and
  sub-workflows.
- Kotlin/JVM library API (`WorkflowParser`, `WorkflowExecutor`,
  `FileSystemWorkflowResolver`) for embedding the runtime in other applications.
- Built-in agent presets for coding, research, writing, data analysis,
  documents, browser automation, DevOps, communication, and marketing research.
- Built-in tool groups for files, shell, Git, HTTP, browser automation,
  documents, databases, RAG, email, image processing, and MCP.
- External coding-agent delegation through Claude Code and OpenAI Codex
  subprocesses.
- Native subprocess mode for trusted local development and Docker subprocess
  mode for production isolation.
- MCP server mode exposing selected tool groups over the Model Context Protocol.
- LLM provider integration via the Koog AI Agents framework, including
  Anthropic, OpenAI, DeepSeek, OpenRouter, Z.ai, and NVIDIA model registries.

[1.1.1]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.1.1
[1.0.20]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.20
[1.0.19]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.19
[1.0.17]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.17
[1.0.9]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.9
[1.0.8]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.8
[1.0.7]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.7
[1.0.6]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.6
[1.0.5]: https://github.com/Braidrun/braidrun-workflow/releases/tag/1.0.5
