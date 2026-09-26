# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0]

Koog upgrade (1.0.0 → 1.3.0) plus the model, metering, multimodal and skill
work it enabled. 1.2.0 and 1.3.0 are not tagged yet; library consumers
resolving from JitPack need the tag (or a local `publishToMavenLocal`).

### Upgrade notes

Act on these when moving from 1.2.x:

- **Skill admin tools moved.** `downloadSkillFromClawHub`,
  `downloadSkillFromGit`, `clearSkillCache` and `refreshSkills` are no longer
  model tools on `SkillTools`; they live on the new `SkillAdminTools` toolset.
  `SkillTools` keeps the read-only tools. `SkillTools.downloadSkillFromClawHub`
  stays as a host API (not a `@Tool`) and gains `refreshManager: Boolean = true`.
  The `skill_tools` group registers `SkillAdminTools` only while
  `WorkflowHostPolicy.allowsSkillAdminTools` is true.
- **Per-skill MCP auto-start is off by default** everywhere, including the CLI.
  Hosts that want `<skill>/mcp-servers/` started must call
  `WorkflowHostPolicy.allowSkillMcpAutoStart()`.
- **New `WorkflowHostPolicy` latches** (one-way, process-wide; see
  `docs/SECURITY.md#host-policy-latches`): `requirePublicLlmEndpoints()`,
  `requireExplicitKeysForCustomLlmEndpoints()`, `requireSingleLlmChoice()`,
  `restrictSkillSideEffects()` and `allowSkillMcpAutoStart()`. Multi-tenant or
  metered hosts should declare the first four at startup, next to
  `requireCodeStepExecutor()`. CLI and library defaults are unchanged.
- **Metering now covers previously unmetered rounds.** Every round of the
  default `just_work_parallel` strategy and the `single_run*` strategies fires
  the normal LLM-call events, so `llm_call_completed` / token usage, cost,
  quota, `LLM_CALL_*` skill hooks, tracing spans, long-term-memory retrieval
  and the weak-model tool-call fix now apply to them. Expect higher reported
  token use and cost on the default strategy: this is real usage that was
  invisible before. Rounds with `num_choices > 1` remain unmetered upstream.
- **Prompt-cache hits report no token usage.** They carry timestamp-only
  metadata plus `braidrun_prompt_cache_hit=true` in response metadata; code
  that read the model id or provider metadata from a cache hit now gets empty
  values.
- **Provider prompt-cache tokens are reported separately.** `AgentEvent` gains
  `cacheReadTokens` / `cacheCreationTokens`; `inputTokens` excludes them for
  every provider (Gemini's cached share moves out of `inputTokens`). Price
  cache reads and writes at the provider's cache rates, not as input. See
  `docs/PROMPT_CACHING.md`.
- **Anthropic prompt caching is on by default** (`anthropic_prompt_caching`).
  Direct Anthropic system prompts arrive as two blocks: the stable prompt, then
  the environment block.
- **`StepMetrics` token sums count each call once.** They used to add the
  `token_usage` display duplicate, so they (and `sub_workflow_completed`
  totals) were twice the real usage.
- **The response-cache key changed**, so existing Redis / file entries miss
  once. Streaming requests no longer go through the response cache.
- **`PromptCacheHints` helpers default to the 5-minute TTL** instead of one
  hour.
- **Anthropic default `max_tokens`** is 16,000 (64,000 when streaming), capped
  by the model's output limit, instead of Koog's 2048. Replies without an
  explicit `max_tokens` can be longer and cost more.
- **`llm_config` snake_case keys are now honored.** `base_url`, `max_token`,
  `is_vision`, `display_name`, `custom_models`, `cascade_fallbacks` and, in
  custom models, `model_id`, `context_length`, `max_output_tokens` were
  silently ignored before. Stored workflows with a snake_case `base_url` start
  sending requests to it.
- **Catalog ids removed or retargeted.** Retired and shut-down models were
  removed (details under Removed). A workflow pinned to a removed id now goes
  through the custom-model path with the id sent verbatim. Alias targets:
  `claude-opus` → `claude-opus-4-8`, `claude-sonnet` → `claude-sonnet-4-6`,
  `claude-fable` → `claude-fable-5-1`, `claude-haiku` → `claude-haiku-4-5`.
  `claude-sonnet-4` / `claude-opus-4` now send `claude-sonnet-4-0` /
  `claude-opus-4-0`; dotted direct-Anthropic keys (`claude-opus-4.7`) send the
  dashed first-party id.
- **Removed shims.** The OpenRouter tool-argument double-encoding workaround
  (`ToolArgsFixingKoogHttpClient`) and the OpenTelemetry span-tree workaround
  are gone. Agent runs that hit the span-tree `IllegalStateException` now fail
  with it instead of completing with an empty output.
- **`useSkill` always returns the full skill content**, also on repeat calls.
  `SKILL_ACTIVATED` still fires once per manager.
- **Skill discovery changes identity for some skills.** Frontmatter is parsed
  as real YAML: nested keys such as `translations.<locale>.name` no longer
  override the top-level `name` / `description`, and inline `# comments` are
  stripped. A skill whose identity came from such a nested key loads under its
  top-level name now; update allow/deny lists that referenced the old name.
- **`clearSkillCache` requires a cache key**; `downloadSkillFromGit` accepts
  only `https://` URLs.
- `SkillManager.createSkillSystemPrompt` gained an optional
  `skillToolsRegistered` parameter (default `true`).
- **Browser contexts are per run.** Playwright contexts are keyed by the
  host-injected `ToolRunScope` plus `contextId`, so a `contextId` names a
  context inside one run only. `WorkflowExecutor.execute` makes each execution
  its own run and closes that run's contexts when it returns (completed,
  failed or cancelled). A nested execution (the agent `workflow` tool) is a
  run of its own; `sub_workflow` steps share their parent's. Tool calls
  outside any run scope get a namespace private to the `BrowserTools`
  instance. `browser_close_all` closes only the calling run's contexts.
  Contexts idle for 30 minutes are closed
  (`BRAIDRUN_BROWSER_CONTEXT_IDLE_TTL_MINUTES`). Library hosts that run agents
  outside `WorkflowExecutor` should wrap each run in
  `ToolRunScope.withRunScope(id) { ... }`.
- **The CLI keeps the old sharing.** `braidrun-workflow` calls
  `ToolRunScope.declareSingleUserProcess()`: every run shares one scope,
  contexts live until the process exits, and no idle TTL applies. Other
  single-user embedders can call it too.
- **`PLAYWRIGHT_ARGS` and `PLAYWRIGHT_HEADLESS` parameters are ignored**
  unless the process declared itself single-user. The browser process is
  shared, so its launch options are host settings: set `PLAYWRIGHT_HEADLESS`
  in the environment. `PLAYWRIGHT_USER_AGENT` is per context and still applies.

### Changed

- Upgraded Koog to 1.3.0 on the stable stream and 1.3.0-beta for beta-only
  modules (agents-ext, agents-mcp, a2a-*, longterm-memory, rag-vector,
  prompt-cache-redis and the google / deepseek / mistralai / dashscope
  clients). `agents-features-opentelemetry` is requested on the stable stream.
  The module-to-stream map is in `build.gradle.kts`. Fixes inherited from
  Koog 1.1–1.3 include reasoning deltas from OpenAI-compatible and DeepSeek
  streams (reasoning is now shown and replayed for these providers), streamed
  tool calls no longer fragmenting on providers that repeat the call id, and
  Gemini replies traced correctly in Langfuse.
- Model parameters are fitted to the model on every request, including
  fallback and cascade tiers, by `ModelParamsSanitizingLLMClient` (applied by
  `createLLMClient`):
  - `temperature` is omitted for models that reject it (Claude Opus 4.7+,
    Sonnet 5, Fable / Mythos 5.x, Kimi K3, and any model without the
    `temperature` capability). Other tiers keep the configured value.
  - A forced tool choice becomes `auto` where it is not supported (models
    without `tool_choice`, and Claude models that think by default).
  - Direct Anthropic requests without `max_tokens` get the new defaults (see
    Upgrade notes).
- Single-choice rounds in `requestLLMMultiplePreservingDeepSeekReasoning` go
  through `requestLLM()`, so they fire LLM-call events and run the response
  processor. The reasoning-only fallback is kept.
- The prompt cache is wrapped in `MeteringSafeCachedPromptExecutor`, and
  prompts that carry tool-result images bypass it
  (`ToolResultMediaBypassingPromptCache`) for every `cache_policy`.
- Model catalog synced with Koog 1.3.0 and provider docs (2026-09-26).
  Capability metadata corrected: Claude Opus 4.7+ / Fable / Sonnet 5 and
  GPT-5.x / 6 no longer declare `temperature`; Claude models that think by
  default no longer declare `tool_choice`; every model on an
  OpenAI-compatible provider declares `openai.completions` or
  `openai.responses`.
- The `mistral` provider sends OpenRouter `mistralai/...` slugs.
- Default image generation uses Google's GA ids: the multimedia catalog
  (`models/multimedia.yaml`) marks `google/gemini-3.1-flash-image` as its
  default, and the `generateImage` tool's `NANO_BANANA_2` / `NANO_BANANA_PRO`
  choices now send `google/gemini-3.1-flash-image` / `google/gemini-3-pro-image`
  instead of the retired `-preview` ids. A `-preview` id can still be chosen
  with `multimedia_default_image_model`.
- Skill discovery walks with pruning: `.git`, `node_modules`, `build`, `dist`,
  `target` and the other skipped directories are not entered. Load order is
  sorted by path, and one 2000-directory budget covers the whole discovery
  pass (hooks use the same pruning).
- The `<available_skills>` catalog escapes `&`, `<` and `>`, uses a stable
  example name, and is omitted when `useSkill` is not registered
  (`disable_skills`, or an explicit `tool_set` without `skill_tools`).
  `createSkillManager` returns `null` when skills are disabled.
- Skill frontmatter `version` / `author` are read from `metadata.*` first.
  List-valued metadata stays a list; nested maps become JSON text.
- An execution's staged skills copy (`.skills-runtime/`) contains only the
  skills the agent's scoped `skills_config` enables, never `.state/`, `.git`
  or symbolic links; its directory name includes a selection fingerprint.

### Added

- Multimodal tool results (Koog 1.3):
  - `browser_screenshot` (now `BrowserScreenshotTool`) returns the PNG as an
    image part, shrunk to at most 1568 px and 1 MB. The file is still saved.
  - MCP `ImageContent` is sent as image parts instead of base64 JSON text;
    audio / blob base64 is replaced by placeholders.
  - `ToolResultMediaAdaptingLLMClient` replaces images with short text
    placeholders wherever the route or model cannot show them.
  - New workflow parameters `tool_result_images_enabled` (default `true`),
    `tool_result_images_max_per_request` (default 4, clamped 0–12) and
    `tool_result_images_max_per_run` (default 24, clamped 0–200).
  - `data:` URIs and long base64 runs in `tool_call_completed` payloads are
    redacted.
- Models: Claude Fable 5.1, Opus 5.5, Opus 5, Sonnet 5, Opus 4.8; GPT-6
  Astra / Sol / Luna and GPT-5.6 Sol / Terra / Luna; Gemini 3.1 Flash-Lite,
  3.5 Flash / Flash-Lite, 3.6 / 3.7 / 3.8 Flash; DeepSeek `deepseek-flash`;
  Qwen 3.5–3.8; Grok 4.3–4.7; GLM-5.3; Kimi K3 / K2.7 Code; MiniMax-M3;
  Ollama `qwen3.6:27b` / `qwen3.8:27b`. First-party Anthropic ids are dashed
  (`claude-opus-5`); OpenRouter ids are dotted (`anthropic/claude-opus-4.8`).
- `WorkflowHostPolicy.requireSingleLlmChoice()`,
  `requirePublicLlmEndpoints()`, `requireExplicitKeysForCustomLlmEndpoints()`,
  `restrictSkillSideEffects()` and `allowSkillMcpAutoStart()`.
- `LlmEndpointNotAllowedException`, thrown by the internal endpoint check behind
  `requirePublicLlmEndpoints()` (a configuration error: not retried, no fallback).
- `SkillAdminTools` toolset.
- Built-in (classpath) skills inline their attachments on activation, each
  capped by `maxAttachmentSize` (`braidrun-workflow-guide` now ships its config
  template this way).
- `docs/SKILLS.md`: skill reference and the decision to keep the in-house skill
  system rather than `ai.koog:skills`.
- Anthropic prompt caching: `PromptCachingLLMClient` places breakpoints on the
  stable system prefix, the tail of each tool-loop round and the last tool;
  `systemWithVolatileTail` keeps the per-build environment block (current
  date) out of the cached prefix. `docs/PROMPT_CACHING.md` documents it and
  the `PromptCachingHarnessTest` measurements.
- `LlmTokenUsage` / `ResponseMetaInfo.tokenUsage()`: provider-normalized usage
  with prompt-cache reads and writes.

### Fixed

- Direct Anthropic models (Claude 5, Opus 4.7, custom and dotted ids such as
  `claude-opus-4.7`) run instead of failing with "Unsupported model".
- OpenAI-compatible direct providers (OpenAI, DashScope / Qwen direct, Kimi,
  MiniMax, Z.ai, NVIDIA, LM Studio) run catalog, custom and ad-hoc models that
  declared no OpenAI endpoint (previously "Cannot determine proper LLM
  params").
- Requests to models that reject `temperature` or forced `tool_choice` no
  longer fail with HTTP 400, and a primary that rejects temperature no longer
  strips it from other tiers.
- Prompt-cache hits no longer re-report the previous round's token usage.
- Streamed Anthropic rounds report their prompt-cache reads and writes (Koog
  1.3.0 drops them from streamed responses).
- The response cache no longer replays another model's answer for an identical
  prompt, no longer shares entries between tools that differ only in parameter
  schema, treats a storage-key collision as a miss, and no longer fails on
  prompts that carry cache markers.
- A cache marker of another provider's type (for example a Bedrock marker on a
  fallback to Anthropic) is dropped instead of failing the request.
- Skill resource listings skip dot-directories, `node_modules`, `venv` and
  `__pycache__`.

### Removed

- `ToolArgsFixingKoogHttpClient` and the other OpenRouter tool-argument
  double-encoding shims; the OpenTelemetry span-tree workaround.
- Retired / shut-down catalog ids, including Claude 3.x and `claude-*-4.1`,
  `claude-opus-latest`, `claude-opus-4.6-fast`; Gemini 1.5 / 2.0 and
  shut-down Gemini previews; `deepseek-chat`, `deepseek-reasoner`,
  `deepseek-r1*`, `deepseek-coder*`, `deepseek-v3*`; OpenAI models past
  shutdown (`o1-mini`, `o1-preview`, `gpt-4-32k`, `gpt-5-chat`, `*-codex`,
  ...); and OpenRouter entries OpenRouter no longer serves.

### Security

- `restrictSkillSideEffects()`: no skill hook script runs, no per-skill MCP
  server starts, `SkillAdminTools` are neither registered nor callable,
  ClawHub installs (fresh and cache hits) drop `hooks/` and `mcp-servers/`,
  and `inspectSkill` / `listCachedSkills` only see skills the agent's own
  manager loaded. A `skills_config` flag can only narrow this.
- `clearSkillCache` rejects empty keys, `.`, `..`, paths and control
  characters, stays inside the cache directory and never follows symlinks.
- `downloadSkillFromGit` runs `git -c core.symlinks=false -c
  protocol.allow=never -c protocol.https.allow=always clone --depth 1
  --no-recurse-submodules -- <url>` without a shell, rejects a symlinked
  `SKILL.md`, and keeps the subdirectory inside the clone.
- `requirePublicLlmEndpoints()` validates chat-client, RAG-embedder and
  multimedia base URLs (https, public resolved addresses).
  `requireExplicitKeysForCustomLlmEndpoints()` keeps host environment keys
  away from non-default base URLs.
- `requireSingleLlmChoice()` closes the `num_choices > 1` metering bypass on
  hosts that bill per token.
- Browser tools no longer cross runs. Contexts and pages were JVM-global and
  keyed only by the model-chosen `contextId` (default `"default"`), so in a
  shared host JVM any run could read another run's page content, cookies and
  session state, drive its logged-in pages, or close every run's browser with
  `browser_close_all`. The run scope comes from the host (`ToolRunScope`, a
  coroutine-context element), not from `execution_id` / `session_id`
  parameters, which workflow YAML can set (`session_id_strategy: fixed`).
- The shared Chromium is no longer launched with a run's `PLAYWRIGHT_ARGS`:
  the first run to start the browser chose switches for every tenant, and
  switches such as `--renderer-cmd-prefix` or `--gpu-launcher` run arbitrary
  commands on the host.

## [1.2.0]

### Added

- TypeSafe Jev decisions. Jev is a decision model: it returns typed answers
  with calibrated probabilities instead of text. See "Jev (TypeSafe) Decisions"
  in `docs/WORKFLOW_GUIDE.md`.
  - `classifier.jev`: Jev answers the classifier instead of an LLM agent.
    - One request covers the main choice over `categories`, optional extra
      `questions` (`choice` / `score` / `noul`) and weighted `composites`.
    - Besides `output_variable`, it writes `<out>_confidence`,
      `<out>_probabilities` and per-question variables such as `<id>_yes`,
      `<id>_level` and `<id>_normalized`.
    - `jev.min_confidence` sends uncertain answers to `default_category`.
    - Works in top-level steps and `state_machine` states.
  - `repeat_until.jev`: typed Jev questions grade each iteration instead of
    an `evaluate_agent` plus regex extraction. It writes variables and
    composites, and stores a deterministic critique (weakest dimension first)
    in `{{steps.<step>:evaluate.output}}`.
  - Configuration errors fail the step and never fall back to a default:
    missing key, HTTP 401/403 and HTTP 400/404/422. In a loop no further
    iterations run.
  - Transient errors fall back after retries: HTTP 429, 529 and 5xx, network
    errors and invalid responses. The classifier uses `default_category`, the
    loop writes empty fallback values, and a `jev_call_failed` warning event is
    recorded. Fallback values always overwrite values from earlier runs.
  - Each Jev call records one `llm_call_completed` event with token counts
    and the detail `model=<id>, provider=TypeSafe; input=N, output=M`.
    `classifier_completed` / `repeat_until_evaluate` carry a JSON decision
    payload. Jev is never called again when an execution resumes.
  - New package `com.fartech.agents.jev`:
    - typed wire DTOs;
    - `JevClient` / `HttpJevClient`: retries with backoff, honors
      `retry-after`, and sends requests through the executor's `HttpAccess`
      client, so the egress proxy and SSRF guard apply;
    - `JevClientFactory`, `JevCredentials` and `JevApiException`.
  - New `WorkflowExecutor` constructor parameters, defaulted and appended
    last: `jevCredentials`, `jevClientFactory` and `jevEnvKeyFallback`.
  - Key resolution: host `JevCredentials`, then parameters `typesafe_api_key`
    / `typesafe_ai_api_key` / `jev_api_key` / `llm_provider_keys[typesafe]`,
    then env `TYPESAFE_API_KEY` (only when `jevEnvKeyFallback` is true, the
    default).
  - Model resolution: `jev.model`, then the host default, `typesafe_model`,
    `TYPESAFE_DEFAULT_MODEL` and finally `jev-latest`.
  - Base URL comes only from `TYPESAFE_BASE_URL`. The key never appears in
    variables, outputs, events or logs.
- `classifier.instructions`, optional, for both engines. It is the Jev
  question text, or a `TASK:` section in the LLM classifier prompt. Without
  it the LLM prompt is unchanged.
- `JevVariableNames`: the naming rules for derived Jev variables, over plain
  `(id, type)` pairs so host-side lint can reuse them.
- Examples `examples/workflows/jev-support-triage.yaml` (Jev only, no chat
  model) and `examples/workflows/jev-quality-loop.yaml`, run in tests against a
  fake Jev client.

### Changed

- `ClassifierConfig.agent` is now `String?` and defaults to `null`, because a
  Jev classifier has no agent.
  - This is source-incompatible for Kotlin code that reads `agent` as
    non-null.
  - YAML without `jev` still needs a non-blank `agent`, with the same
    validation message.
  - `referencedAgents` ignores blank classifier agents.
- The provider ids `typesafe`, `typesafe_ai` and `jev` are no longer accepted
  as chat-model providers.
  - `createLLMClient`, `determineLLMModel` and `ModelRegistry` throw
    `TypeSafe Jev is a decision model, not a chat model...`.
  - Before, they fell back to OpenRouter and would have sent the key to
    openrouter.ai.
  - Key lookup knows `TYPESAFE_API_KEY` and the aliases.
    `llm_provider_keys[typesafe|typesafe_ai|jev]` maps to `typesafe_api_key`.
- Classifiers inside `state_machine` states now also reject duplicate
  category names.
- `dry-run` labels Jev classifiers `classifier(jev)`. The monitor shows
  `classifier(jev)` or `classifier(jev:<model>)`. The workflow summary marks
  Jev-evaluated `repeat_until` steps.

### Security

- The agent `workflow` tool no longer runs `code:` steps outside the host's
  sandbox. Its nested `WorkflowExecutor` used to get no `codeStepExecutor`,
  so a workflow the agent wrote ran its `code:` steps with a bare
  ProcessBuilder in the host JVM — on braidrun-web, the server itself,
  outside the Docker sandbox and egress proxy.
  - New `NestedWorkflowRuntime`: a `WorkflowExecutor` hands its
    `codeStepExecutor`, `extraCodeStepEnv`, Claude/Codex credential providers
    and auth.json rotation sink, `executionApiTokenProvider`,
    `workflowResolver` and Jev policy to the nested executor.
  - `WorkflowTools(httpAccess, parameters, runtime)` replaces the Jev-only
    constructor parameters. The two-argument form is unchanged.
  - New `WorkflowHostPolicy.requireCodeStepExecutor()`: a host declares it
    once, and from then on every executor without a `codeStepExecutor`
    refuses `code:` steps. This covers `workflow` tools built outside any
    executor. The CLI and library default is unchanged.
- The agent `workflow` tool's `createWorkflowFromTemplate` no longer reads or
  writes arbitrary paths. The model supplies both arguments. Before, a
  template name with `../` read any `.yaml` file, and `outputPath` wrote
  anywhere the process could write.
  - `templateName` must be a bare name from `listWorkflowTemplates`. Names
    containing `/`, `\`, `:`, `..` or control characters are rejected.
  - `outputPath` must end in `.yaml` / `.yml`. It must also resolve,
    symlinks included, inside `working_dir` or `output_dir`, the same roots
    the sandboxed file tools use. When neither is set (CLI runs), the root is
    the process working directory. Relative paths resolve against the first
    root.
  - Substituted variable values must be single-line, so a value cannot add
    YAML keys or steps.

Deferred to later releases:

- AI-evaluated `condition:` expressions. Conditions stay synchronous,
  injection-safe comparisons: decide in a Jev step and branch on its
  variables.
- A dedicated Jev step type.
- Jev for `group_chat` termination or speaker selection, `iterate_over`
  filters, aggregate pick-best, extract candidate picking and manual-approval
  auto-decisions.

## [1.1.9]

- Exclude resource admission waits from execution budgets without restarting an executing body.
- Renew scoped code-step callback credentials only at actual process launch, after admission.
- Route subprocess tools through the host-provided executor and classify direct code-step requests.
- Confirm process/container settlement before releasing host reservations; make native cancellation waits interruptible.
- Give admitted Docker containers stable reservation names and reconcile stopped/absent containers without killing running work.

## [1.1.8]

- Allow trusted runtimes to issue fresh scoped callback credentials before each code step, including steps after long approval waits. Refuse execution if renewal fails.

## [1.1.7]

### Fixed

- Classify Docker status-wait timeouts before subscription authentication errors.
- Inspect Codex error channels rather than generated application code or tool output
  when identifying authentication failures; avoid false expiry from HTTP 401 examples.
- Keep complete filenames when extracting persistence requirements from prompts,
  rather than matching a suffix after regex backtracking.

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
