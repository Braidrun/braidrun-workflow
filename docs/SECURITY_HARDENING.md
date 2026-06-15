# braidrun-workflow Security Hardening (2026-04)

Landed across phases 1-5 (foundational audit, 2026-04), Phase 6 (Office /
PDF tool consolidation), Phase 7 (Koog 0.8.0 follow-up), Phase 8
(2026-04-29 sandbox / browser / crypto / persistence follow-up), and Phase 9
(2026-05-06 redirect-SSRF / SQL parser / log redaction).

**Verification:** after every phase, `compileKotlin` +
`compileTestKotlin` + `test` (1233 tests after
Phase 9) ran green. No test disabling or skipping.

Quick table-of-contents:

- [Phase 1 — P0 + critical P1 security](#phase-1--p0--critical-p1)
- [Phase 2 — P2 concurrency & resource](#phase-2--p2-concurrency--resource)
- [Phase 3 — P2 security deepen](#phase-3--p2-security-deepen)
- [Phase 4 — architecture & cross-cutting](#phase-4--architecture--cross-cutting)
- [Phase 5 — AgentCommon god-class split](#agentcommon-god-class-split--complete-phase-5)
- [Phase 6 — Office / PDF tool consolidation](#office--pdf-tool-consolidation--completed-phase-6)
- [Phase 7 — Koog 0.8.0 follow-up](#phase-7--2026-04-audit-follow-up)
- [Phase 8 — 2026-04-29 sandbox / browser / crypto / persistence](#phase-8--2026-04-29-audit-follow-up)
- [Phase 9 — 2026-05-06 redirect-SSRF / SQL parser / log redaction](#phase-9--2026-05-06-audit-follow-up)
- [New configuration surface](#new-configuration-surface)
- [New shared helpers](#new-shared-helpers)
- [Operator runbook](#operator-runbook)

---

## Phase 1 — P0 + critical P1

### P0 · Hard-coded MongoDB credentials removed

- `EnvironmentConfig.getDefaultDatabaseUrl()` no longer returns
  `mongodb://braidrun:<redacted-password>@…`. Callers must set the URL via
  `-Dapp.config.database.url=…` or `BRAIDRUN_AGENT_MONGO_URL`.
- `EnvironmentConfig.getFileStoragePath()` default changed from `/root/braidrun`
  to `<java.io.tmpdir>/braidrun`.
- `printEnvironmentInfo()` now runs every URL through
  `redactSensitiveUrlForLogs()` before logging.

> ⚠️ **Operator action still required:** rotate the leaked MongoDB password at
> the database level. The same credential lives in 13 other files across the
> monorepo; those are out of scope for this module but are listed in the
> audit report.

### P1 security

| Area | File(s) | Change |
|------|---------|--------|
| Git sandbox | `GitTools.kt` + `AgentCommon.kt` | Converted `object GitTools` → `class GitTools(executor, userId, context)` routing through `SubprocessExecutor`; every `git` invocation now respects Docker isolation in production. `gitCheckout` inserts `--` to prevent LLM-supplied ref being parsed as a git flag. |
| Native exec env leak | `NativeSubprocessExecutor.kt` | Clears inherited JVM env; repopulates from a 15-item safe whitelist (`PATH`, `HOME`, `LANG`, `TZ`, `TMPDIR`, …). Operators extend via `BRAIDRUN_NATIVE_EXEC_ENV_ALLOWLIST`. |
| Docker mount validation | `DockerSubprocessExecutor.kt` | `canonicalizeAndValidateHostPath()` rejects paths resolving into `/etc /var/run /var/lib/docker /proc /sys /root /boot /dev`. `validateExtraMounts` kept literal-`..` rejection **and** added normalized-path sensitive-root check. Skipped mounts now log at WARN level, not DEBUG. |
| MCP error sanitization | `AgentMcpServer.kt` | New `sanitizeToolException()` strips absolute paths → `<path>`, FQCNs → `<internal>`, stack frames. Each error gets a 12-char `errorId` for server-side log correlation. |
| Workflow condition injection | `ConditionEvaluator.kt` | Condition template is split into `(left, op, right)` **before** any substitution, so a substituted step-output can't rewrite the operator. |
| ASA token exfiltration | `ASATokenAutomationTools.kt` | `reportUrl` parameter removed — the destination is now server-side `defaultReportUrl` only. Constructor validates it's on the `DEFAULT_REPORT_HOST_ALLOWLIST` (a placeholder example domain that operators must override with their own). Success messages no longer echo token prefixes. |
| Workflow resolver symlink bypass | `WorkflowResolver.kt` | `isWithinAnySearchRoot` no longer falls back to non-canonical root when `canonicalFile()` fails. |
| SubWorkflow cycle warnings | `WorkflowParser.kt` | Detection still best-effort (can't resolve every reference statically), but now aggregates skipped references and emits a single warning pointing operators at the runtime guard (`SubWorkflowStackElement`) for hard safety. |
| MCP path resolve scope | `AgentMcpUtils.kt` | Upward `resolveExistingPath` search kept (needed by real MCP configs) but each successful upward hit logs at INFO for audit. `println("…")` replaced with `logger.warn { … }` so stdout stays clean for MCP protocol traffic. |
| MCP zombie processes | `MCPServerManager.kt` | `destroyForcibly()` failure no longer silent; logs the PID so operators can hunt it. |
| OCR argument whitelist | `OCRTools.kt` | `language` validated via `^[a-z0-9_]{2,32}$` (supports `+` combos); `pageSegMode` locked to 0..13. |
| Config `ref:` path | `Configuration.kt` | New `resolveAndValidateConfigRef` restricts dereferences to `CONFIG_REF_ALLOWED_ROOTS` (default `user.dir` + `java.io.tmpdir`; extend via `BRAIDRUN_CONFIG_REF_ROOTS`). |

---

## Phase 2 — P2 concurrency & resource

| File | Change |
|------|--------|
| `AgentState.kt` | `acquireHistoryLock` replaced with `TrackedHistoryLock` carrying `lastAccessNanos`; entire acquisition flows through one `synchronized(guard)` so a lock returned to a caller can't be evicted out from under them. Eviction only touches entries idle > 10 min. |
| `ResilientMongoOperations.kt` | `isRetryableError` widened to match `TimeoutException`, `TimeoutCancellationException`, `SSLException`, and Mongo driver-specific FQCNs (`MongoSocket*`, `MongoNotPrimary`, `MongoNodeIsRecovering`, `MongoServerUnavailable`). CAS loop bounded to 16 attempts then `set()`. Jitter math clamped against NaN / Infinity / overflow. |
| `WorkflowMonitor.kt` + `WorkflowModels.kt` | `StepMetrics.events` now `Collections.synchronizedList(…)`; new `addEventBounded(event, max)` performs size-check + drop-oldest + append atomically; new `eventsSnapshot()` returns a point-in-time copy for safe iteration by SSE readers. `getInputTokens/Output/Total` sum under `synchronized(events)`. |
| `ConcurrentFilePromptCache.kt` | Registry key normalized via `toRealPath()` with `toAbsolutePath().normalize()` fallback — eliminates "two paths to the same directory land on different mutexes". |
| `ModelRegistry.kt` | Dead `@Volatile var initialized` removed (written, never read). |
| `MongoDocumentStore.kt` | `put()` switched from `delete + insertOne` to atomic `replaceOne(filter, doc, ReplaceOptions().upsert(true))`. Constructor catches client-creation failure and rewraps with redacted message so the connection string never reaches logs. |
| `InMemoryDocumentStore.kt` | Key encoding uses length-prefixed form so `::` delimiter can't collide; `list(...)` takes a `values.toList()` snapshot before filter/sort. |
| `AgentDatabase.kt` | `resolveMongoConnection()` fails fast on blank `connectionString` / `dbName` with a clear error pointing at the env var. |
| `RAGTools.kt` | Policy comment added; Phase 4 follow-up delivers actual suspend variants. |
| `MCPServerManager.kt` | Startup-failure stderr bounded to 64 KB via `readBounded`. |
| `ChatPDFDocumentManager.kt` | New `tryFindDocument` → `LookupResult.Found / NotFound / Error` sealed class; legacy `findDocument` preserved as thin wrapper. |

---

## Phase 3 — P2 security deepen

Two new shared helpers land here and are used everywhere that previously
hand-rolled similar logic.

### New helper: `PoiSecurity`

File: `tools/PoiSecurity.kt`

Idempotent `ensureHardened()` configures Apache POI globally:

- `ZipSecureFile.minInflateRatio = 0.005` (200× compression cap — zip bomb guard)
- `ZipSecureFile.maxEntrySize = 256 MB`
- `ZipSecureFile.maxTextSize = 256 MB`
- verifies the JVM's `SAXParserFactory` disallows DOCTYPE declarations; warns
  if the host JVM defeats POI's default XXE protection

Plus a `sanitizePdfDocument(doc)` that strips `OpenAction` before any PDFBox
parsing path. Called from every Office / PDF / iWork `ToolSet`'s `init { }`.

### New helper: `SpreadsheetSafety`

File: `tools/SpreadsheetSafety.kt`

`escapeFormula(value)` prepends `'` when a string starts with `= + - @ \t \r`
so spreadsheets opened downstream don't execute formulas injected via LLM.
Wired into every CSV/XLSX write path (OfficeCsvTools, ExcelEnhancedTools).

### New helper: `UrlSafety`

File: `tools/UrlSafety.kt`

`validateAndNormalizeUrl(url)` is the extracted-and-shared version of
WebTools' SSRF guard:

- scheme must be `http` or `https` (rejects `file:`, `jar:`, `javascript:`, …)
- host must resolve; every DNS answer is checked against
  loopback / link-local / site-local / private / multicast / cloud-metadata
- opt-out via `WEB_TOOLS_ALLOW_PRIVATE_URLS=true`

Now used by **WebTools** (formerly internal copy) **and**
`MultimediaGenerationTools` (formerly an unvalidated
`startsWith("http")` test).

### Path validator unification

`ToolPathSecurity.validateInputPath(path)` introduced. `validateOutputPath` was
already the canonical output gate; the new input gate is applied to every
`ImageIO.read(File(path))` call in `ImageProcessingTools`.

### FileReadGuard narrowed

- Whitelist **drops** `.properties`, `.toml`, `.ini` (too credential-dense)
- Blacklist **adds** `.key`, `.jks`, `.keystore`, `.kube*`, `.docker*`, `.netrc`
- Blacklist now matches name patterns regardless of extension:
  `*secret*`, `*token*`, `*credential*`, `*api_key*`, `*password*`

### Email hardening

`EmailTools.kt`:

- IMAP / IMAPS: `mail.imap.ssl.checkserveridentity=true`,
  `mail.imap.ssl.protocols=TLSv1.2 TLSv1.3` (and `imaps.*` equivalents)
- SMTP: same certificate-identity + TLS-protocol enforcement;
  `mail.smtp.starttls.required=true` blocks silent plaintext fallback
- Attachments validated via `ToolPathSecurity.validateInputPath` + per-file
  and total-bytes caps (defaults 25 MB each; configurable via
  `email_max_attachment_bytes` / `email_max_total_attachment_bytes`)

### DatabaseTools

- `DriverManager.setLoginTimeout(10)`
- `conn.setNetworkTimeout(direct-executor, 60s)`
- New `BRAIDRUN_DB_READONLY=true` (system property or env var) gate. When set,
  any SQL that isn't `SELECT / SHOW / DESCRIBE / EXPLAIN / WITH / PRAGMA` is
  rejected before touching the database.

### ConditionEvaluator operators

`contains` default behavior reverted to case-sensitive (consistent with
`==` / `!=`). New operators:

- `contains_cs` — explicit case-sensitive
- `contains_ci` — explicit case-insensitive

Legacy workflows can set `WORKFLOW_CONDITION_CONTAINS_ICASE=true` during the
migration window to keep the old case-insensitive default for bare `contains`.

### Misc P2

- `AppleAppInfoTools`: `appId` must be numeric ≤ 20 chars; `country` must be
  a 2-letter ISO code; both URL-encoded.
- `PowerPointEnhancedTools.parseColor`: accepts 3- or 6-digit hex, rejects
  everything else with a clear message (was crashing on `#FF`).
- `KnowledgeMemoryTools`: per-entry 64 KB cap, per-store 16 MB cap (both
  configurable). New `memory_namespace` parameter isolates memory under
  `<storage>/<namespace>/` subdir.
- `HttpAccess`: request / socket / connect timeouts clamped to `[1, 600]` s.
  Dead `BRAIDRUN_SKIP_SSL_VERIFICATION` flag now logs at ERROR noting it's
  ignored (was silently giving operators a false sense of security).

---

## Phase 4 — architecture & cross-cutting

### `WorkflowTemplateResolver` (new)

File: `workflow/WorkflowTemplateResolver.kt`

Three duplicate copies of the `{{…}}` substitution loop
(`ConditionEvaluator.resolveTemplateExpr`,
`StateMachineEngine.resolveTemplate`,
`WorkflowExecutor.resolveTemplate`) now all call
`WorkflowTemplateResolver.resolve(template, context)`. Substitution rules and
order documented in one place; future improvements (single-pass tokenizer,
new placeholder forms) touch one file instead of three.

### `LogSanitizers` broadened and applied

New `redactForLog(message)` on top of the existing `redactSensitiveUrlForLogs`:

- `INLINE_CREDENTIAL_URL_REGEX` — any `scheme://user:pass@host` anywhere in
  the message
- `AUTHORIZATION_HEADER_REGEX` — `Authorization: Bearer / Basic / Token /
  ApiKey <blob>`
- `LONG_SECRET_BLOB_REGEX` — OpenAI `sk-…`, AWS `AKIA…`, GitHub `ghp_…`,
  Slack `xox[abpors]-…` tokens

Applied to `HttpAccess.logRetry` so any URL or Authorization header that ends
up in retry logs gets scrubbed.

### `SubprocessExecutorFactory` (new)

File: `commons/SubprocessExecutorFactory.kt`

`AgentCommon.kt`'s `createSubprocessExecutor` + `buildSubprocessToolContext`
extracted into a dedicated object. The old top-level functions are now thin
re-exports so existing callers don't migrate in the same change. First step
of breaking up the `AgentCommon.kt` god class.

### AgentCommon god-class split — complete (Phase 5)

After the Phase 4 `SubprocessExecutorFactory` carve-out, the remaining
responsibilities in the 1,500-line `AgentCommon.kt` have been split into
three new files (all in `com.fartech.agents.commons`):

| New file | Moved in | Line count |
|----------|----------|-----------|
| `ToolRegistryBuilder.kt` | `getDefaultToolRegistry`, `parseToolSet` (both String and AgentTools overloads), `parseExactToolSet`, `buildToolRegistry` (the giant `toolSet.contains("…")` branch ladder), `browserToolsDisabled` | 432 |
| `PromptExecutorFactory.kt` | `determineCachePolicy`, `createPromptExecutor` | 125 |
| `AgentBootstrap.kt` | `buildAgent` (non-inline + reified overloads), `buildAndRunAgent`, `buildAndRunStringAgent`, `buildAndRunStructureAgent`, `buildAndRunStructureToolAgent`, `buildAndRunConfiguredAgentWithStructuredOutput`, `defaultInstallFeatures`, `streamCollectNode`, `extractJsonFromResponse`, `structuredOutputJson` | 585 |
| `AgentCommon.kt` (remaining) | progress-logger helpers, typealiases, `Defaults`, `DEFAULT_FIXING_PARSER`, `EnvCache`, `compactEnvSettings`, `envSettings`, `determineDefaultStrategy` | 463 |

**API compatibility:** every public top-level function kept the same name,
package (`com.fartech.agents.commons`), and signature, so callers in
downstream consumers and tests continued to compile
without changes. Verified via cross-module smoke build.

**Why this particular split:** each new file has a single responsibility —
"build the tool registry", "build the prompt executor", "build/run the
agent" — and `AgentCommon.kt` is now just the irreducible shared surface
(logging + env info + typealiases + strategy dispatcher). Adding a new
tool group, cache backend, or agent-run variant now touches exactly one
small file instead of hunting through 1,500 lines.

### MCP server hardening

`AgentMcpServer.kt`:

| Control | Default | Configuration |
|---------|---------|---------------|
| Tool allowlist | all tools allowed | `BRAIDRUN_MCP_ALLOWED_TOOLS=tool_a,tool_b` |
| Per-tool rate limit | 600 calls / min | `BRAIDRUN_MCP_RATE_LIMIT_PER_MIN` |
| Max input bytes | 1 MiB | `BRAIDRUN_MCP_MAX_INPUT_BYTES` |

Each violation returns a structured `CallToolResult` with
`isError=true` and `structuredContent.error` ∈
`{tool_not_allowlisted, rate_limited, payload_too_large}`. Stdio clients see
the same responses as HTTP/SSE clients — uniform regardless of transport.

### RAGTools suspend API

New suspend variants:

- `RAGTools.indexDocumentWithSourceSuspend(...)`
- `RAGTools.indexFileSuspend(...)`

Existing non-suspend methods retained for koog's `@Tool` dispatch and legacy
integrations. `WorkflowExecutor`'s two call sites migrated to the suspend
variants — `initializeKnowledgeBase`, `autoIndexStepOutput`, and
`hydrateResumeState` were promoted to `suspend fun`.

---

## Phase 7 — 2026-04 audit follow-up

A fresh audit pass over the Tier-1/Tier-2 Koog-0.8.0 additions and several
tools paths that had been added or expanded after the Phase 4 cutoff found a
small set of leaks, unbounded caches, and validation gaps. All fixed and
pinned with 21 new tests (total now 1107).

### Resource leaks

| File | Change |
|------|--------|
| `PromptExecutorFactory.kt` | `RedisClient` now pooled by connect-URL via `getOrCreatePooledRedisClient` and closed by a single JVM-shutdown hook. Previously every `determineCachePolicy` call under `cache_policy=redis` allocated a new Netty event-loop + TCP pool that nothing ever closed — in workflow-web this leaks FDs until JVM exit. |
| `InstantMessagingTools.kt` | Four `ConcurrentHashMap` discovery caches (`discoveredChatIds`, `discoveredTelegramContexts`, `discoveredTelegramOffsets`, `discoveredTelegramBotUsernames`) replaced with `Collections.synchronizedMap(LinkedHashMap(..., accessOrder=true))` bounded at **4096** entries with LRU eviction. Long-running deployments with many distinct bot-token × session-id pairs no longer accumulate unboundedly. |

### Crash safety

| File | Change |
|------|--------|
| `PromptExecutorFactory.kt` | `redis_duration` parsing wrapped in `parseRedisDuration` — malformed ISO-8601 / arbitrary strings now log a WARN and fall back to 900 s instead of raising `DateTimeParseException` out of agent startup. |
| `PromptExecutorFactory.kt` | `memory_cache_max_entries` and `max_files` clamped to `[1, 1_000_000]` via `clampPositive`. A misconfigured YAML can no longer set a trillion-entry cache and OOM the JVM. |
| `CascadingFallbackPromptExecutor.kt` | `close()` now logs each tier's close failure (`runCatching { … }.onFailure { log }`) instead of silently swallowing. Resource leaks in downstream executors (e.g. Mongo session timeout) surface in operator logs. |

### Input validation

| File | Change |
|------|--------|
| `WeakModelToolCallFix.kt` | New `resolveToolChoice(parameters, toolRegistry)` overload validates `tool_choice: <name>` against the actual registry. A typo (`"searc_web"` instead of `"search_web"`) now falls back to `Auto` with a WARN log listing available tools, instead of producing `Named(<typo>)` which Koog cannot satisfy — previously the agent would loop until `max_iterations`. The no-arg overload preserves pre-2026-04 semantics for call sites that don't have a registry. Call site in [`AgentBootstrap.kt`](../src/main/kotlin/com/fartech/agents/commons/AgentBootstrap.kt) threads the MCP-augmented registry through. |
| `LongTermMemoryInstall.kt` | An explicit `long_term_memory_namespace: ""` would key every caller into `inMemoryStores[""]` and leak context cross-user. We now trim the value and fall back to the default template on blank, with a WARN so operators see the override was ignored. Also trims `session_id` to guard against accidental whitespace-only values. |
| `WebTools.kt` | `downloadFile` Content-Length check narrowed to `contentLength in 1L..Long.MAX_VALUE` — previously `-1 > max` evaluated false and the fast-fail branch was skipped for chunked / unknown-length responses. The actual cap was still enforced by the per-chunk size check during streaming, but the fast-fail now covers both cases consistently. |
| `DatabaseTools.kt` | `isReadOnlySql` now strips both `--` line comments and `/* … */` block comments (with nested-comment handling) before inspecting the leading keyword, and rejects any statement-separator `;` with non-whitespace content after it. A prior implementation looked only at the first-line prefix — an LLM-crafted `/* hide */ SELECT 1; DROP TABLE foo` on a driver with `allowMultiQueries=true` would have passed. Quote-aware scan (`'…'`, `"…"`, doubled-quote escape) so `SELECT ';' AS col` is correctly accepted. |

### New tests (all `test`)

- [`PromptExecutorFactoryTest`](../src/test/kotlin/com/fartech/agents/commons/PromptExecutorFactoryTest.kt) — 5 tests pinning cache-size clamping + unknown-policy fallback.
- [`DatabaseToolsReadOnlyTest`](../src/test/kotlin/com/fartech/agents/tools/DatabaseToolsReadOnlyTest.kt) — 13 tests covering comment stripping, multi-statement rejection, quoted-separator acceptance, nested-block comments.
- `WeakModelToolCallFixTest` extended with 3 tests pinning the `toolRegistry`-aware `resolveToolChoice` overload (validates against empty registry, preserves backwards-compat with `null`, bypasses validation for canonical `auto/required/none`).

---

## Phase 8 — 2026-04-29 audit follow-up

A second full audit pass (5 parallel review agents) over `braidrun-agent` after
Phase 7 turned up a clutch of **resource-exhaustion** holes in the subprocess
sandbox plus a small handful of validation gaps. All fixed and pinned with
**21 new tests** (total now **1172**).

### Sandbox hardening

| File | Change |
|------|--------|
| `tools/exec/SubprocessExecutor.kt` | New constants `MAX_STREAM_BYTES = 8 MiB`, `MAX_STDIN_BYTES = 8 MiB`, `STREAM_TRUNCATION_MARKER`. Single source of truth for both executor implementations. |
| `tools/exec/DockerSubprocessExecutor.kt` | **Stdout / stderr capture capped at MAX_STREAM_BYTES** with truncation marker — a misbehaving (or hostile) container pumping unbounded output can no longer OOM the JVM during `collectLogs`. **Stdin payload validated against MAX_STDIN_BYTES** before container creation. **`--pids-limit 256`** via `DEFAULT_PIDS_LIMIT` to deflect fork-bomb DoS that would otherwise exhaust the host's PID table. **`--ulimit nofile=1024:1024 fsize=512MiB`** via `DEFAULT_ULIMITS` to cap file descriptors and per-file size — runaway loops can't fill the tmpfs by creating millions of small files. |
| `tools/exec/NativeSubprocessExecutor.kt` | Stdin payload validated against MAX_STDIN_BYTES before `start()`. Stdout / stderr capture moved into a new `readBoundedStream()` helper that drains the rest of the pipe after the cap is hit (so the child isn't deadlocked on a full pipe) and appends the truncation marker. Same ceiling and behaviour as the Docker path so test fixtures and dev-mode behaviour stay aligned. |
| `commons/SubprocessExecutorFactory.kt` | New `validateImageTag(tag)` and `validateDockerNetworkName(name)` validators reject tag / network strings that would otherwise be string-interpolated into `braidrun/exec-*:$tag` or `--network=…` arguments. OCI-compliant patterns: tag matches `^[A-Za-z0-9_][A-Za-z0-9_.\-]*$` (≤128 chars); network matches `^[a-zA-Z0-9][a-zA-Z0-9_.-]*$`. Closes the smuggle-a-different-registry vector even though the parameter source is operator-controlled. |

### Tool / tool-runtime hardening

| File | Change |
|------|--------|
| `tools/BrowserTools.kt` | `browser_navigate` now rejects any URL whose scheme is not `http`/`https`. The previous unguarded `page.navigate(url)` accepted `file://` (LLM could stage `file:///etc/passwd` then exfiltrate via `browser_get_content`), `javascript:` (XSS into an already-loaded page), and `data:` / `blob:` (same vector). SSRF host checks intentionally NOT applied here — browser flows legitimately drive against localhost test harnesses; the dangerous bit is the scheme. `browser_set_cookies` now caps `cookiesJson` at 256 KiB and 200 cookies per call before parsing — without that gate an LLM passing a multi-MB cookie blob would force the entire tree into heap and ship N IPC messages through Playwright. |
| `commons/AgentModels.kt` | `debugLlmConfig` now logs through `KotlinLogging.logger {}` instead of `println`. When the agent runs inside the MCP stdio transport, stdout is the JSON-RPC channel — a stray `println` corrupts the protocol stream. The opt-in env var (`BRAIDRUN_DEBUG_LLM_PARAMS=true`) gating still applies but the routing now respects the surrounding I/O contract. |

### Crypto / persistence hardening

| File | Change |
|------|--------|
| `commons/AgentMemoryInstall.kt` | Added `isAcceptableEncryptionKey()` — operator-supplied `agent_memory_encryption_key` is now verified to decode to ≥32 bytes (AES-256 minimum) before being handed to `Aes256GCMEncryptor`. Tries standard / URL-safe / MIME base64, plus accepts raw ≥32-byte ASCII secrets to match Koog's permissive constructor. Keys that fail validation cause the feature to **fail closed** (install `NoMemory`, log ERROR) rather than silently downgrade to weaker-than-AES-256 key material — preserving the security property the parameter name advertises. |
| `commons/AgentState.kt` | File-backed conversation history now trimmed to a sliding window via `history_max_persisted_messages` (default 1000, set 0 to disable the cap). Without this, a long-running session accumulated unbounded JSON on disk; every `saveHistoryMessage` then read + re-serialised the entire growing list, so latency degraded as the file grew. The injection-side cap (`history_max_messages`, default 50) was already present but only governed how much got read back. |

### New tests (all `test`)

- [`SubprocessExecutorFactoryTest`](../src/test/kotlin/com/fartech/agents/commons/SubprocessExecutorFactoryTest.kt) — 6 tests pinning `validateImageTag` / `validateDockerNetworkName` accept-and-reject paths.
- [`AgentMemoryInstallTest`](../src/test/kotlin/com/fartech/agents/commons/AgentMemoryInstallTest.kt) — 7 tests pinning `isAcceptableEncryptionKey`: standard b64, url-safe b64, raw ASCII ≥32, blank, short b64, short ASCII, malformed b64.
- `NativeSubprocessExecutorTest` extended with 2 tests: oversized stdin rejected before spawn, huge stdout truncated with marker.

### Findings re-verified and intentionally NOT fixed

The Phase 8 audit also surfaced ~10 lower-severity items that were re-verified
against the source and judged either false positives or design choices:

- **`CascadingFallbackPromptExecutor` "dead code" `?: IllegalStateException`** — kept. The Kotlin compiler can't prove `lastError` is non-null after the loop; the fallback is defensive and self-documenting.
- **State-machine `while(true)` global timeout** — `maxTransitions` (default 64) bounds iterations; per-state code already inherits the agent / step timeout via the surrounding coroutine context. Adding a second, separate clock would compound retry tuning without closing a real DoS vector.
- **YAML alias / anchor expansion** — kaml's parser already caps depth; the workflow loader runs against author-controlled YAML, not untrusted input. Reconsider when / if a public YAML upload endpoint is added.
- **Image digest pinning instead of tag** — operationally heavy (every image bump becomes a code change). The Phase 8 tag validator is sufficient given the operator trust boundary.
- **Browser `page.evaluate` JavaScript injection** — that's the tool's documented purpose. The mitigation is the operator's deployment policy (don't expose `browser_evaluate` to user-facing prompts), not a source-code change.

---

## Phase 9 — 2026-05-06 audit follow-up

A third full audit pass (5 parallel review agents covering subprocess sandbox,
workflow engine, tools layer, agent runtime, MCP/storage/agents) over
`braidrun-agent` after Phase 8. Of ~30 raised findings, **6 were confirmed real
and fixed**, 12 were confirmed false positives, and the rest were duplicates of
already-shipped Phase 1-8 hardening. Pinned with **34 new tests** (total now
**1233**).

### P1 — SSRF via auto-followed redirects

**Files:** `commonsKt/HttpAccess.kt`, `tools/WebTools.kt`, new
`commonsKt/HttpHostSafety.kt`, refactored `tools/UrlSafety.kt`.

Pre-Phase-9, every HTTP path in the agent followed 3xx redirects without
re-validating the redirect target:

- `HttpAccess` (Ktor + OkHttp engine) had `engine.config.followRedirects(true)`.
- `WebTools.fetchWebpage` / `extractLinks` / `searchInWebpage` used
  `Jsoup.connect(validUrl).get()` which follows redirects by default.
- `WebTools.downloadFile` used `URL.openConnection()` whose default is
  `instanceFollowRedirects=true`.

The attack: an attacker registers `attacker.com` (a public IP that passes
`UrlSafety.validateAndNormalizeUrl`), then 302-redirects the agent's request to
`http://localhost:8080/admin` or `http://169.254.169.254/latest/meta-data/`.
The initial URL passed the SSRF gate; the actual fetch landed inside the
perimeter — full SSRF.

**Fixes:**

| File | Change |
|------|--------|
| `commonsKt/HttpHostSafety.kt` (new) | Extracted the host-class check (`assertHostIsPublic`) and a URL convenience wrapper (`assertUrlIsPublic`) into `commonsKt` so `HttpAccess` can call them without creating a circular module dependency on `agents.tools`. The agent-facing `UrlSafety.validateAndNormalizeUrl` now delegates here. Same `WEB_TOOLS_ALLOW_PRIVATE_URLS=true` opt-out as before. |
| `commonsKt/HttpAccess.kt` | Switched the OkHttp engine to `followRedirects(false)` and added `SsrfRedirectInterceptor` — an OkHttp `Interceptor` that walks the redirect chain manually, calls `HttpHostSafety.assertUrlIsPublic` on every Location target, and rejects unsafe targets as `IOException` (mapped from the underlying `SecurityException` so OkHttp / Ktor surface it as a network failure). Hop ceiling matches OkHttp's historical default (`MAX_REDIRECT_HOPS = 10`). RFC 7231 §6.4 method semantics preserved (301/302/303 of POST/PUT downgrades to GET; 307/308 preserve method + body). |
| `tools/WebTools.kt` | New `safeJsoupGet(url, userAgent, timeoutMs)` helper — calls `.followRedirects(false).ignoreHttpErrors(true).execute()` and walks Location headers manually with `validateAndNormalizeUrl` re-validation per hop. The three `Jsoup.connect(validUrl).get()` call sites (`fetchWebpage`, `extractLinks`, `searchInWebpage`) all migrated. |
| `tools/WebTools.kt` (downloadFile) | Replaced the single-shot `URL.openConnection()` with a hop loop that calls `instanceFollowRedirects = false` on each `HttpURLConnection`, parses the Location header, and re-validates against `validateAndNormalizeUrl` before opening the next connection. Same hop ceiling as above. |

### P2 — DatabaseTools read-only bypass via string-aware comment stripping

**File:** `tools/DatabaseTools.kt:90-126` (`stripSqlComments`)

The Phase 7 hardening added comment-stripping + multi-statement rejection but
the stripper did **not** track quoted string literals. A motivated LLM could
embed an unmatched `/*` inside a string literal — the stripper would then swallow
everything after it (including the `;` that `containsStatementSeparator` relies
on) and the trimmed leftover would start with `SELECT`, passing the read-only
check. Drivers with `allowMultiQueries=true` would then run the trailing
destructive statement.

**Repro:** `SELECT '/*' UNION ALL SELECT 1; DROP TABLE x` →
- Stripper sees `/*` inside the string, enters block-comment mode, scans for
  `*/`, never finds one, swallows everything to EOF → `SELECT '`.
- `containsStatementSeparator` sees no `;` → returns false.
- `isReadOnlySql` sees `SELECT '` starting with SELECT → returns true.
- Original SQL forwarded to JDBC; multi-statement payload runs.

**Fix:** added a string-literal branch to `stripSqlComments` that copies through
quoted text verbatim (handling `''` / `""` SQL escape pairs). Comment markers
inside literals are now correctly preserved. 5 new tests in
`DatabaseToolsReadOnlyTest`.

### P2 — LogSanitizers regex coverage

**File:** `commonsKt/LogSanitizers.kt:74-79` (`LONG_SECRET_BLOB_REGEX`)

Pre-Phase-9 the secret-blob regex matched OpenAI `sk-…`, AWS `AKIA…`, GitHub
`ghp_…`, and Slack `xox*-…`. It missed:

- GitHub OAuth scope (`ghs_`), user-to-server (`ghu_`), refresh (`ghr_`),
  OAuth (`gho_`), GitHub Apps (`gha_`) tokens.
- Stripe live (`sk_live_`), test (`sk_test_`), and restricted (`rk_live_`,
  `rk_test_`) keys.
- Google API keys (`AIza…`).
- JWT tokens (`eyJ…header.payload.sig`).

**Fix:** broadened `LONG_SECRET_BLOB_REGEX` to cover all of the above. 9 new
tests in `LogSanitizersTest`.

### P2 — BrowserTools.browser_screenshot path validation

**File:** `tools/BrowserTools.kt:166-186`

The `path` parameter flowed straight into `Page.ScreenshotOptions().setPath(Paths.get(path))`
with no validation. An LLM could write a screenshot to `/etc/cron.d/payload` or
`../../.ssh/authorized_keys` (depending on the agent process's effective
permissions). This is the same protection that every other "write a file"
tool already enforces via `ToolPathSecurity.validateOutputPath`.

**Fix:** call `ToolPathSecurity.validateOutputPath(path)` first; pass the
validated `safeFile.toPath()` to Playwright.

### P2 — ModelRegistry silent OpenRouter fallback

**File:** `commons/ModelRegistry.kt:353-373` (`mapProviderStringToEnum`)

A typo in workflow YAML (`deepseek_` instead of `deepseek`) silently routed to
`LLMProvider.OpenRouter` with the OpenRouter API key — no operator visibility,
the request just landed on a different provider than the operator believed.

**Fix:** keep the OpenRouter fallback (legitimate third-party providers like
amazon / cohere / together actually do route through it) but log a WARN so the
misroute surfaces at agent boot. Operators can grep for "Unknown LLM provider"
to spot typos.

### P3 — FileManagementTools.copyFile entry-count cap

**File:** `tools/FileManagementTools.kt:123-165`

`copyFile` against a directory called `src.walkTopDown().forEach { ... }` with
no entry cap. A multi-million-file source (e.g. cached node_modules with a
self-referential symlink-free loop, or a deliberately-prepared tree) would pin
the JVM thread and fill the destination.

**Fix:** added `MAX_COPY_ENTRIES = 100_000` constant on the companion. The walk
counts entries; above the cap, the operation is rejected with a clear message.
Success path now also reports the entry count for operator visibility.

### Phase 9 false positives (verified, not fixed)

The five-agent audit also raised ~12 findings that turned out to be false
positives after re-verification against the actual source:

- **`CodeExecutionTools` / `ShellTools` / `GitTools` mount canonicalization** —
  flagged as missing but `DockerSubprocessExecutor.buildBinds()` already calls
  `canonicalizeAndValidateHostPath()` on every mount before binding, and the
  canonical path is what's passed to Docker. Tool-layer validation would be
  redundant.
- **McpRateLimiter unbounded keys** — `tool.name` keys come from the registered
  Koog tool registry, not from arbitrary client input. The maps are bounded by
  the actual number of registered tools (typically <100).
- **`WorkflowMonitor.completedExecutions` trim race** — already wrapped in
  `synchronized(completedExecutions)` so the size check + remove is atomic.
- **SubWorkflow dynamic-path cycle bypass via templates** — `SubWorkflowConfig.path`
  is a static string from YAML, never template-substituted. Cycle detection via
  `targetIdentity in callStack` is reliable.
- **InstantMessagingTools LRU bypass** — `boundedLruMap` returns a
  `LinkedHashMap` with `removeEldestEntry { size > maxEntries }` — a textbook
  Java LRU bound. Every `put` triggers the size check; the cap is hard.
- **SkillTools ZIP slip on Windows** — the canonical-path-startsWith check
  (`!file.canonicalPath.startsWith(cachedDirPath)`) is correct; mixed
  separators are normalized by `canonicalPath`.
- **RAGTools file read without ToolPathSecurity** — already guarded by
  `requireAllowedPath` (allowed-dirs check) AND `readGuard.validateReadFile`.
- **EmailTools header injection via RFC 2047** — the explicit `\r`/`\n` reject
  in `to`/`cc`/`bcc`/`subject`/`fromName` runs before address parsing; `setSubject(value, "UTF-8")`
  RFC 2047 encodes after the CRLF check. Encoded-words don't get decoded back to
  raw CRLF during SMTP transmission.
- **DeepSeekThinkingFixClient injection of unrecognized message types** — Kotlin's
  type system enforces `Message` subtypes; reflection-based injection is a
  separate attack vector that the message-pattern filter wasn't designed to
  defend against.
- **AgentMcpUtils upward path search "command injection"** — already an accepted
  trade-off (Phase 1 audit). The upward walk logs INFO on every successful
  upward hit, depth is bounded at 8, and stricter isolation breaks legit MCP
  configs that span CLI / tests / workflow-web cwds.
- **ASATokenAgent password-in-parameters leak** — the `parameters.parameter("asa_apple_password", "")`
  call fetches but immediately discards the value. The agent prompt instructs
  the user to enter cookies via `enhanced_ask_user`, never via parameters.

### New tests (all `test`)

- `DatabaseToolsReadOnlyTest` extended with **5 tests**: unmatched `/*` inside
  single-quoted literal, double-quoted identifier, comment markers inside
  literal preserved verbatim, doubled single-quote escape (`''`), line-comment
  inside string preserved.
- `LogSanitizersTest` extended with **10 tests** covering `ghs_` / `ghu_` /
  `ghr_` / `gho_`, Stripe live / test / restricted, Google API key, JWT, and a
  negative-control "non-secret text".
- `HttpHostSafetyTest` (new file) — **17 tests** pinning `assertHostIsPublic`
  (loopback hostname / IPv4 literal / IPv6 literal / RFC 1918 10.x / 172.16-31 /
  192.168.x / 169.254.x cloud-metadata / 0.0.0.0 wildcard / blank / unresolvable)
  and `assertUrlIsPublic` (file: / javascript: / data: / gopher: / loopback host /
  no host).
- `FileManagementToolsTest` extended with **2 tests**: `MAX_COPY_ENTRIES` is in
  the documented range, and `copyFile` success path includes entry count in the
  response.

**Verification:** `test` 1233 tests pass (up from 1172 in
Phase 8).

---

## Phase 10 — 2026-05-08 audit follow-up

A fourth full audit pass (5 parallel review agents covering recent skills /
approval / auto-resume changes, the tools layer, MCP / exec / utils / agents
subsystems, the workflow engine, and commons) over `braidrun-agent` after
Phase 9. Of ~30 raised findings, **7 were confirmed real and fixed**, ~12
were confirmed false positives, and the rest were duplicates of already-
shipped Phase 1-9 hardening. Pinned with **6 new tests** (total now **1249**).

### P1 — manual-approval rejection state asymmetry

**File:** `workflow/WorkflowExecutor.kt:6474+`

When a `manual_approval` step is **rejected**, the engine threw
`WorkflowApprovalRequiredException` immediately without setting any of the
context variables that the **approved** branch routinely populates via
`applyApprovedReviewableActions`:

```kotlin
context.setVariable("approval_decision", "approved")     // only on approve
context.setVariable("approval_comment", …)
context.setVariable("approval_approved_count", "<N>")
```

Downstream conditions like `if: "{{var:approval_decision}} == 'rejected'"`
silently never matched, and sub-workflow callers that read
`context.variables` after the rejection saw stale state from a prior
approval. The new `reviewable_actions` workflow type exposed this gap.

**Fix:** rejection branch now mirrors the same six variables (global +
step-scoped) the approved branch sets, with `approval_approved_count="0"` and
the approver's comment threaded through. Pinned by
`WorkflowExecutorBehaviorTest.\`rejecting manual approval populates
approval_decision variables\``.

### P1 — BraidrunHookExecutor unbounded subprocess capture

**File:** `commons/AgentHooks.kt`

Hook handler scripts (Python / Node / Kotlin scripts shipped with skills)
ran through `ProcessBuilder` with **unbounded `bufferedReader().readText()`**
on both stdout and stderr — a misbehaving handler that printed gigabytes of
JSON or stderr would OOM the JVM before the timeout ever fired. The 5 s
reader join was also unsynchronised with `destroyForcibly()`, leaving reader
threads running on already-destroyed pipes when the process timeout tripped.

**Fix:** matches the Phase 8 sandbox-cap pattern.

| Constant | Value |
|----------|-------|
| `BraidrunHookExecutor.MAX_HOOK_STREAM_BYTES` | `8 * 1024 * 1024` (mirrors `SubprocessExecutor.MAX_STREAM_BYTES`) |
| Truncation marker | `\n[hook output truncated at 8 MiB]\n` |

New private `readBoundedAndDrain(input, maxBytes)` captures up to the cap and
discards-but-drains the rest so producers never wedge on a full pipe; the
timeout path now calls `destroyForcibly()` **first** then joins reader
threads (which see EOF and exit promptly).

### P1 — MCPServerManager unbounded `npm install` / `pip install` / `python -m venv`

**File:** `commons/MCPServerManager.kt`

Five `ProcessBuilder().…waitFor()` calls during MCP server preparation had
**no timeout** and **unbounded `bufferedReader().readText()`**. A stuck npm
registry lookup, a hung pip resolver, or a corrupted venv state could pin
the agent boot indefinitely. In multi-instance deployments this prevented a
node from ever reaching `/ready`, blocking nginx upstream rotation.

**Fix:** new `runPrepareStep(cmd, workDir, timeoutSeconds = 600)` helper:
- Drains the combined output stream on a daemon thread, capped at 256 KiB
  but draining past the cap so producers never wedge.
- Enforces a 10-minute wall-clock timeout (2 minutes for `python -m venv`).
- Returns `null` on timeout; call sites log a clean failure instead of hanging.

All five sites (npm install / npm run build / venv / pip install ×2)
migrated. New constants:

```kotlin
internal const val PREPARE_STEP_TIMEOUT_SECONDS: Long = 600
internal const val PREPARE_OUTPUT_MAX_BYTES: Int = 256 * 1024
```

### P2 — iOS app generator's `xcodegen` unbounded `waitFor`

**File:** `agents/app_generators/iOS/IOSAppDevelopAgent.kt`

Two `ProcessBuilder("xcodegen", …)` calls (version probe + spec generation)
used unbounded `waitFor()` and unbounded `bufferedReader().readText()`. iOS
project generation runs inside the agent's main coroutine, so a stuck
xcodegen wedged the entire agent process.

**Fix:**
- Version probe: 30 s timeout. Skip generation on timeout.
- Spec generation: 5-minute timeout + 1 MiB drained-stream output buffer.
- Timeout failures are logged and counted toward the existing retry budget.

### P2 — SkillManager MCP-init daemon threads untracked

**File:** `commons/AgentSkills.kt`

`initializeMCPServersForSkill` started one fire-and-forget daemon thread per
MCP server using `runBlocking` to bridge to coroutine APIs. The threads:
- ignored exceptions beyond logging (no observer notified),
- were not tracked anywhere, so `shutdown()` racing against an in-flight
  `startMCPServer` could leak the new server,
- prevented graceful JVM exit if a server was mid-prepare during shutdown.

**Fix:** new `mcpInitThreads: CopyOnWriteArrayList<Thread>` tracks every
init thread; a paired daemon cleans up the entry when the init completes;
`shutdown()` now joins pending threads with a 3 s bounded budget before
calling `mcpServerManager.stopAllServers()`. Net: `systemctl stop` no
longer risks orphaning a half-prepared MCP server.

### P2 — ConcurrentFilePromptCache mutex registry unbounded

**File:** `commons/ConcurrentFilePromptCache.kt`

The global `mutexRegistry: ConcurrentHashMap<String, Mutex>` had no cap and
no eviction. A long-running agent process (e.g. a web host with
hundreds of distinct workflow executions over a day) accumulated dead
Mutex references forever — the cache directories themselves got cleaned up
by the retention scheduler, but the path → Mutex mapping never released.

**Fix:** registry capped at `MAX_MUTEX_REGISTRY_ENTRIES = 4096` with LRU
eviction (synchronised `LinkedHashSet` access-order tracker). Eviction
preserves the entry just inserted by the current call so we never evict
the mutex the caller is about to use. New `acquireMutex(key)` companion
helper consolidates the lookup-or-allocate-with-eviction logic. Pinned by
`ConcurrentFilePromptCacheTest`.

### P2 — InstantMessagingTools webhook URL SSRF defense-in-depth

**File:** `tools/InstantMessagingTools.kt`

All five webhook backends (DingTalk / WeChat Work / Feishu / Slack /
Discord) read their target URL from `parameters.parameter()` and passed it
directly to `httpAccess.post()`. In a multi-tenant host application
deployments the parameter can be seeded by the workflow YAML, and prior
phases didn't validate it. So a tenant-injected
`im_dingtalk_webhook_url=http://localhost:8080/admin` or
`http://169.254.169.254/...` would happily POST whatever payload the LLM
crafted to internal infrastructure.

**Fix:** new private `assertWebhookUrlPublic(url, label)` helper routes
through `UrlSafety.validateAndNormalizeUrl` (which delegates to
`HttpHostSafety` — same gate that all other LLM-controlled URLs already
pass through). All five backends call it before any HTTP transport.
Operators with legitimate internal webhooks opt out via
`WEB_TOOLS_ALLOW_PRIVATE_URLS=true`, mirroring the existing WebTools
behaviour. Telegram and WhatsApp use hard-coded API endpoints and don't
need this guard.

Pinned by `InstantMessagingToolsTest` with four new tests covering
loopback / cloud-metadata / RFC 1918 / scheme rejection.

### Phase 10 false positives (verified, not fixed)

The four-agent audit also raised ~12 findings that turned out to be false
positives after re-verification against the actual source:

- **`WorkflowTemplateResolver.forEach` ConcurrentModificationException** —
  `ConcurrentHashMap.forEach` is *weakly consistent*, not fail-fast. No CME
  possible. The doc-comment warning is conservative but the code is correct.
- **IPv6 `[::1]` SSRF bypass** — `URL("http://[::1]/").host` returns `[::1]`
  with brackets, but `InetAddress.getByName("[::1]")` accepts the bracketed
  form and returns the loopback address; `addr.isLoopbackAddress` is true
  and `HttpHostSafety` rejects it correctly. Already pinned by
  `HttpHostSafetyTest.\`IPv6 loopback literal is rejected\``.
- **`reviewable_actions` allowlist bypass via `decision.edits`** — by-design.
  Approvers are authorized to edit the items they approve.
- **`AgentState` history-lock TOCTOU** — eviction only targets locks idle
  for ≥10 minutes. A caller holding a reference for 10+ minutes between
  `acquireHistoryLock` and `.lock()` is implausible.
- **`DockerSubprocessExecutor` bind-mount TOCTOU** — canonicalisation runs
  immediately before container creation. The 20 ms theoretical window
  requires local filesystem write access, already a higher privilege than
  the container can grant.
- **`LogSanitizers` multi-line regex** — log frames are line-buffered
  upstream; multi-line secrets in a single log message are a non-pattern.
  Adding `DOT_MATCHES_ALL` would also widen ReDoS surface.
- **`SpreadsheetSafety` formula prefix bypass** — a cell value `'=…` makes
  Excel display the literal string with a leading apostrophe (apostrophe
  is the "text prefix" sentinel) — formula does not execute.
- **MCP server `done.join()` unbounded wait** — the `done` job is signalled
  by the `onClose` callback installed before `createSession`. Ktor /
  stdio always close on disconnect.
- **`ImageProcessingTools` decompression bomb** — real concern but
  lower priority. `ImageIO.read` is lazy for many formats, and
  `ToolPathSecurity` keeps untrusted images out of the agent.
- **`SubAgentTools` `max_iterations` not clamped** — workflow-author
  controlled, not LLM-influenced.
- **`FileReadGuard` ReDoS in name regex** — bounded by the OS file-name cap
  (255 chars), worst case is constant time.
- **`KnowledgeMemoryTools` 1-second timestamp granularity** — collisions
  produce stable sort order ties, no data loss.

### New tests (all `test`)

- `WorkflowExecutorBehaviorTest` extended with **1 test**: rejection
  populates the same approval state variables as the approved branch.
- `InstantMessagingToolsTest` extended with **4 tests**: DingTalk loopback
  rejection, Feishu cloud-metadata rejection, Slack RFC 1918 rejection,
  Discord file:// scheme rejection.
- `ConcurrentFilePromptCacheTest` (new file) — **2 tests**: registry stays
  bounded under 4500-entry churn, two caches at the same canonical path
  share the same Mutex instance.

**Verification:** `test` 1249 tests pass (up from 1233 in
Phase 9). 0 failures, 0 errors.

---

## New configuration surface

All new switches are opt-in; defaults preserve pre-audit behavior unless
noted. Grouped by concern.

### Secrets & storage

| Variable | Role |
|----------|------|
| `app.config.database.url` (system prop) / `BRAIDRUN_AGENT_MONGO_URL` | Required — MongoDB connection string. No default. |
| `BRAIDRUN_AGENT_REDIS_URL` | Redis URL. Default `redis://127.0.0.1:6379`. |
| `app.config.fileStoragePath` (system prop) | Default `<java.io.tmpdir>/braidrun`. |
| `BRAIDRUN_CONFIG_REF_ROOTS` | `:`-separated list of additional roots allowed for `ref:` dereferences. Defaults include `user.dir` + `java.io.tmpdir`. |

### Subprocess executor

| Variable | Role |
|----------|------|
| `BRAIDRUN_NATIVE_EXEC_ENV_ALLOWLIST` | Comma-separated extra env var names to propagate into native subprocess children. |
| `subprocess_mode` (workflow param) | `native` (default) or `docker`. |
| `docker_exec_image_tag`, `docker_egress_network` | Docker exec image / network selection. **Validated against OCI tag / Docker network-name patterns at agent boot — bad input fails fast (Phase 8).** |
| Subprocess output caps (Phase 8) | Hard-coded ceilings: stdout / stderr capped at `MAX_STREAM_BYTES = 8 MiB` (truncation marker appended); stdin capped at `MAX_STDIN_BYTES = 8 MiB` (rejected pre-spawn); Docker containers also bounded by `--pids-limit 256`, `--ulimit nofile=1024:1024`, `--ulimit fsize=512MiB`. No env-var override — these are sandbox invariants. |

### MCP server

| Variable | Role | Default |
|----------|------|---------|
| `BRAIDRUN_MCP_ALLOWED_TOOLS` | Comma-separated tool name allowlist | *unset → all registered tools allowed* |
| `BRAIDRUN_MCP_RATE_LIMIT_PER_MIN` | Per-tool rate limit | `600` |
| `BRAIDRUN_MCP_MAX_INPUT_BYTES` | Max JSON payload size per call | `1048576` (1 MiB) |

### Workflow condition

| Variable | Role |
|----------|------|
| `WORKFLOW_CONDITION_CONTAINS_ICASE=true` | Restore legacy case-insensitive `contains` semantic during migration. New workflows should use the explicit `contains_ci` / `contains_cs` operators. |

### Database

| Variable | Role |
|----------|------|
| `BRAIDRUN_DB_READONLY=true` | Reject non-SELECT SQL at the tool layer. Useful when the agent connects to a production DB. |

### Email

| Workflow param | Role | Default |
|----------------|------|---------|
| `email_max_attachment_bytes` | Per-file cap | 25 MB |
| `email_max_total_attachment_bytes` | Aggregate cap | 25 MB |

### Knowledge memory

| Workflow param | Role | Default |
|----------------|------|---------|
| `memory_max_entry_bytes` | Per-entry soft cap | 64 KiB |
| `memory_max_store_bytes` | Per-store soft cap | 16 MiB |
| `memory_namespace` | Per-user / per-agent isolation suffix | *unset → shared* |

### HTTP client

| Variable | Role | Default | Cap |
|----------|------|---------|-----|
| `BRAIDRUN_HTTP_REQ_TIMEOUT_S` | Request timeout | 180 s | 600 s |
| `BRAIDRUN_HTTP_SOCK_TIMEOUT_S` | Socket read timeout | 180 s | 600 s |
| `BRAIDRUN_HTTP_CONN_TIMEOUT_S` | TCP connect timeout | 180 s | 600 s |
| `BRAIDRUN_SKIP_SSL_VERIFICATION` | **dead flag** — logs ERROR if set; does not actually disable TLS verification | *unset* | *n/a* |

### History persistence

| Workflow param | Role | Default |
|----------------|------|---------|
| `history_max_persisted_messages` (Phase 8) | Sliding-window cap on the on-disk history JSON for the `file` backend. Set 0 to disable (legacy unbounded behaviour). | 1000 |

### Browser tools

| Hard limit | Role |
|------------|------|
| `MAX_COOKIES_JSON_BYTES = 256 KiB` (Phase 8) | Reject `browser_set_cookies` payloads larger than this before parsing. |
| `MAX_COOKIES_PER_CALL = 200` (Phase 8) | Reject batches with more than 200 cookies. |
| Allowed `browser_navigate` schemes | `http` / `https` only. `file://`, `javascript:`, `data:`, `blob:`, etc. rejected at the tool layer. |

### Agent memory

| Workflow param | Role | Default |
|----------------|------|---------|
| `agent_memory_encryption_key` | Base64-encoded ≥32-byte AES-256 key for at-rest encryption. **Validated to decode to ≥32 bytes (Phase 8); shorter keys cause the feature to fall back to `NoMemory` with an ERROR rather than silently degrade crypto.** Unset = plaintext storage. | *unset* |

---

## New shared helpers

Everything below lives under `com.fartech.agents.tools` unless noted, and is
`internal` — call from within the module.

| Helper | File | Purpose |
|--------|------|---------|
| `PoiSecurity.ensureHardened()` | `PoiSecurity.kt` | Zip-bomb caps + XXE sanity-check; idempotent. |
| `PoiSecurity.sanitizePdfDocument(doc)` | same | Clear PDF OpenAction after open. |
| `SpreadsheetSafety.escapeFormula(s)` | `SpreadsheetSafety.kt` | Prepend `'` if cell starts with formula trigger. |
| `UrlSafety.validateAndNormalizeUrl(url)` | `UrlSafety.kt` | SSRF-safe URL validator (scheme + DNS host check). |
| `ToolPathSecurity.validateInputPath(path)` | `ToolPathSecurity.kt` | Mirror of `validateOutputPath` for read operations. |
| `WorkflowTemplateResolver.resolve(t, ctx)` | `workflow/WorkflowTemplateResolver.kt` | Canonical `{{…}}` substitution. |
| `SubprocessExecutorFactory.create(params)` | `commons/SubprocessExecutorFactory.kt` | Build a native or Docker executor. |
| `redactForLog(s)` | `ftapp2/commonsKt/LogSanitizers.kt` | Scrub secrets from arbitrary log messages. |
| `getOrCreatePooledRedisClient(url)` | `commons/PromptExecutorFactory.kt` | One `RedisClient` per distinct URL, closed by a single JVM shutdown hook. |
| `parseRedisDuration(raw)` | `commons/PromptExecutorFactory.kt` | Plain-seconds or ISO-8601; falls back to 900 s on malformed input with WARN. |
| `clampPositive(name, value, hardCap, default)` | `commons/PromptExecutorFactory.kt` | Reject non-positive, clamp above hard cap, both log WARN. |
| `resolveToolChoice(parameters, toolRegistry)` | `commons/WeakModelToolCallFix.kt` | Overload that validates `Named(<tool>)` against the supplied registry. |
| `SubprocessExecutorFactory.validateImageTag(tag)` | `commons/SubprocessExecutorFactory.kt` | Reject image tags that don't match the OCI reference grammar (Phase 8). |
| `SubprocessExecutorFactory.validateDockerNetworkName(name)` | same | Reject Docker network names that don't match the OCI grammar (Phase 8). |
| `AgentMemoryInstall.isAcceptableEncryptionKey(raw)` | `commons/AgentMemoryInstall.kt` | Verify a base64 / URL-safe-base64 / MIME-base64 / raw-ASCII encryption key decodes to ≥32 bytes (Phase 8). |
| `SubprocessExecutor.MAX_STREAM_BYTES`, `MAX_STDIN_BYTES`, `STREAM_TRUNCATION_MARKER` | `tools/exec/SubprocessExecutor.kt` | Single-source caps shared by Docker / native executors (Phase 8). |
| `HttpHostSafety.assertHostIsPublic(host)` / `assertUrlIsPublic(url)` | `commonsKt/HttpHostSafety.kt` | Shared SSRF guard (host-class check + scheme check) in `commonsKt` so `HttpAccess` can re-validate every redirect hop without depending on `agents.tools` (Phase 9). |
| `HttpAccess.SsrfRedirectInterceptor` / `MAX_REDIRECT_HOPS = 10` | `commonsKt/HttpAccess.kt` | OkHttp interceptor that walks 3xx Location headers manually, calling `assertUrlIsPublic` per hop. Replaces `engine.config.followRedirects(true)` so a malicious redirect can't bypass the per-call URL guard (Phase 9). |
| `WebTools.safeJsoupGet(url, userAgent, timeoutMs)` | `tools/WebTools.kt` | Drop-in for `Jsoup.connect(url).get()` that disables auto-redirects and re-validates each Location through `validateAndNormalizeUrl` (Phase 9). |
| `FileManagementTools.MAX_COPY_ENTRIES = 100_000` | `tools/FileManagementTools.kt` | Hard cap on `copyFile` directory walks — pre-Phase-9 unbounded `walkTopDown` was a DoS vector against agents with large source paths (Phase 9). |

---

## Deferred to a later Phase

### Office / PDF tool consolidation — ✅ COMPLETED (Phase 6)

Pre-Phase-6 state: **9 document-related ToolSet classes**
(`WordTools` + `WordAdvancedTools` + `WordEnhancedTools`,
`ExcelTools` + `ExcelAdvancedTools` + `ExcelEnhancedTools`,
`PowerPointTools` + `PowerPointAdvancedTools` + `PowerPointEnhancedTools`,
`CSVTools`, `IWorkTools` + `ApplePagesTools` + `AppleKeynoteTools` +
`AppleNumbersTools`, `PDFTools` + `PDFAdvancedTools`). Unclear mapping
from user intent to tool choice; LLM saw duplicate-purpose tools.

**Post-Phase-6 state**: 5 canonical document tool groups visible to
operators and the LLM: `word`, `excel`, `powerpoint`, `pdf`, `iwork`
(+ `csv` and the convenience alias `office` which packs Word + Excel +
PowerPoint + CSV in one selection). Kotlin classes reduced where the merge
was mechanical; the rest marked `@Deprecated` with the same single-group
registration story.

**Physical merges:**

- **PDFTools** — absorbed every `@Tool` method from `PDFAdvancedTools`
  (`extractPagesRange`, `rotatePage`, `deletePages`, `addImageToPage`,
  `fillFormFields`, `encryptPdf`, `decryptPdf`, `setMetadata`,
  `addHeaderFooterText`, `addTextBoxToPage`). The old object is now a
  `@Deprecated` empty shell.
- **IWorkTools** — absorbed the format-specific wrappers (`pagesInfo`,
  `keynoteInfo`, `numbersInfo`, …) from `ApplePagesTools` /
  `AppleKeynoteTools` / `AppleNumbersTools`. Those three classes are now
  `@Deprecated` empty shells.

**Deprecation-only (body retained for zero behavioral change):**

- `WordAdvancedTools` / `WordEnhancedTools` / `ExcelAdvancedTools` /
  `ExcelEnhancedTools` / `PowerPointAdvancedTools` /
  `PowerPointEnhancedTools` — all marked `@Deprecated`, still registered by
  `ToolRegistryBuilder` under the single `word` / `excel` / `powerpoint`
  group. External Kotlin callers should migrate to
  `parseToolSet(listOf("word"))` instead of instantiating the tier classes
  directly. The bodies stay in place until a subsequent release cycle
  removes the class shells entirely.

**Ripples updated in this pass:**

- `ToolRegistryBuilder.buildToolRegistry` — simplified PDF / iWork
  registration to one `tools(PDFTools)` / `tools(IWorkTools)` line each;
  Word / Excel / PowerPoint blocks now `@Suppress("DEPRECATION")` with a
  clear comment that the tier classes remain internally for one release.
- `AgentMcpServer.agentMcpToolGroups` — descriptions for `pdf` / `iwork` /
  `office` / `word` / `excel` / `powerpoint` / `csv` refreshed to state the
  single-group story.
- `AIWritingAgent`, `OfficeAgent`, `PDFAgent` — all three migrated to
  `parseExactToolSet(listOf("word" | "office" | "pdf"))`. No more direct
  `WordAdvancedTools()` / `PDFAdvancedTools` instantiation in agent code.
- Tests `OfficeDocumentToolsTest`, `PowerPointToolIntegrationTest` — class-
  level `@Suppress("DEPRECATION")` since they validate the tier bodies
  directly; tests continue to pass unchanged.
- CSV stays a dedicated group (semantically distinct from Excel workbooks).

**Verification:** `test` 374 tests + targeted workflow-web
tests + downstream smoke builds
all green.

**Removing the deprecated classes:** track via the `@Deprecated` warnings
that will show up in any external integrator's build. Once zero callers
remain outside braidrun-agent's own test suite, the shells can be deleted in
a follow-up commit.

### AgentCommon god-class further split — ✅ COMPLETED in Phase 5

`ToolRegistryBuilder`, `PromptExecutorFactory`, and `AgentBootstrap` were
all extracted in Phase 5; see the section above. No further god-class
carve-out is outstanding for `AgentCommon.kt`.

### Workflow-web suspend migration — ✅ COMPLETED (Phase 5 follow-up)

`AssistantDocsKnowledgeBaseService` has been migrated. Specifically:

- **`RAGTools` gained three more suspend variants:**
  `searchKnowledgeEntriesSuspend`, `deleteDocumentSuspend`,
  `clearKnowledgeBaseSuspend`. The non-suspend overloads remain as thin
  `runBlocking` wrappers for koog's `@Tool` dispatcher and any lingering
  non-suspend integrations.
- **The service's lock moved from `Any` to `kotlinx.coroutines.sync.Mutex`.**
  The old `synchronized(lock)` block forbade suspend calls inside it, which
  was the structural reason every RAG call went through `runBlocking`. The
  coroutine Mutex lets the critical section hold across embedding round-trips
  without parking a Dispatchers.Default worker.
- **New suspend entry points:** `ensureReadySuspend`, `rebuildSuspend`,
  `retrieveContextSuspend`, `performIncrementalUpdateSuspend`. Legacy
  non-suspend overloads (`ensureReady`, `rebuild`, `retrieveContext`) are
  retained as thin `runBlocking` wrappers for Java interop and startup-thread
  use. Semantics preserved bit-for-bit.
- **Call sites migrated:** `AssistantPipeline.informationalResponder` now
  calls `retrieveContextSuspend` inside its `async {}` block (was previously
  parking a worker on the embedding call); `SystemSettingsRoutes`'s
  `POST /docs-kb/rebuild` handler now calls `rebuildSuspend`.

Verified against `test` (374 tests) and
downstream consumer tests — both green after the migration.

---

## Operator runbook

### Database password rotation

1. On the MongoDB replica set, change the password for the `braidrun` user.
2. Update your deployment's environment to set
   `BRAIDRUN_AGENT_MONGO_URL=mongodb://braidrun:<new-password>@host:27017/…`
   (or the equivalent system property).
3. Roll the relevant services.

The audit pass **did not rotate the password** — only the code path that
previously hard-coded it.

### Tightening the MCP server

When exposing the MCP server over HTTP (outside the default stdio single-client
deployment), set at least:

```bash
BRAIDRUN_MCP_ALLOWED_TOOLS="read_file,list_dir,workflow_list_templates"
BRAIDRUN_MCP_RATE_LIMIT_PER_MIN=120
BRAIDRUN_MCP_MAX_INPUT_BYTES=262144   # 256 KiB
```

### Locking down agent database access

If an agent is connected to a production database it only needs to read:

```bash
BRAIDRUN_DB_READONLY=true
```

Any non-SELECT statement (DROP, INSERT, UPDATE, …) is rejected at the tool
layer before reaching the database driver.

### Configuring strict file read / write

The `FileReadGuard` is opt-in per `SandboxedFileSystemProvider` instance. In
workflow YAML, set:

```yaml
variables:
  strict_sandbox: "true"
```

Output-side path validation (`ToolPathSecurity.validateOutputPath`) is always
on for every tool that writes files.
