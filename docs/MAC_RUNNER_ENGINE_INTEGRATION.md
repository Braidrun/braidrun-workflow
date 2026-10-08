# JVM Runner host integration handoff

User constraint: **do not deploy, publish, enable the feature, upgrade existing Runners or migrate
production executions**. Development, tests and scoped commits are authorized. Full App Factory
acceptance remains an integration milestone; admission rejection is not completion of missing work.

Engine baseline: `6330754b8d76674555e89f7994c17d88fab7e3ca`.
Canonical wire/schema source: `81d611a65b7c93e3341c69db51c7736a1c411175`, protocol 1,
SHA-256 `09a3c3f6d6bd430a126303f25ad9795c5818d72eec62bc2896cfe84af40a504a`.
`contract-origin.txt` and the JAR `Runner-Contract-Commit` header identify this schema source;
platform, supervisor and package builder must use that same wire/schema pin.
Decision/environment semantics remain `39a264aacca2f46ff8fb3e53232b2e76eee627ac` and frozen
child-scope semantics are `316ca337d550d7bb6e0300452392bb14f7205951`. Their separate provenance
is packaged in `runner-workflow-v1/contract-semantics.txt`; neither changes schema bytes or
introduces a wire field.

## Implemented core

`WorkflowHost` injects identity, event/metering, approvals and ask/tell interaction, output services,
subprocesses, credentials, callback tokens, dynamic definition admission, transport and savepoints.
The interpreter remains `WorkflowExecutor`; ordinary library/CLI calls omit the host. Both static
and Agent `workflow.executeWorkflow` children inherit the root target, host, output/approval
adapters, actor/owner/billing scope and credential references. Dynamic YAML must match a frozen
bundle definition/hash; unlisted/changed definitions are rejected. Dynamic invocation identities
are distinct, including fresh agent tool instances. Complete definition closure inspection includes
dormant child/state-machine branches and refuses unknown features before preparation.

`RunnerHostMainKt` runs one root per JVM. Launch after installDist:
`<pinned java> -jar <runtime>/runner-host.jar --binding <nonsecret binding.json>`.
Dependencies stay under `<runtime>/lib`. The existing CLI main class/version is retained.
Supervisor launches JVM in the execution directory; physical cwd is validated. Bundle metadata
and every file/definition hash are verified; scripts/definitions are frozen, and host-owned runtime
directories stay in the execution root. Never pass bearer material in argv.

The bounded stdio channel validates canonical schema, direction, complete binding, strict UTF-8,
newline, size/queue bounds and duplicate identities. Changed duplicate/stale-epoch frames fail;
late replies cannot wake a request. Approval/user commands without replyTo correlate only with
an existing same-type exact idempotencyKey under the same binding. Repeated observations cannot
extend evidence or hide changed CN data. Lease/environment expiry, control invalidation and cancel
stop the engine; user cancel remains CANCELLED. The supervisor owns process launch/registration
and actual termination, including independent enforcement while IPC/logs stall.

Grouped approvals preserve source/output/key/columns/items and comments. AS_IS and REPLACE
are distinct, including zero selected rows. Approval occurrences are checkpointed, so repeated
iterations get distinct human decisions and a resumed pending approval keeps its identity.
Continuation waits for a new environment observation (supervisor can refresh before delivering
the human decision). Chinese language warnings are projected as a structured environment event.

Event frames and savepoints are written privately, fsynced and atomically installed before their
IPC handoff. ACKs cannot exceed emitted sequence; older durable ACKs do not regress it. Savepoints
preserve typed variables, outputs, results, transitions, failure recovery and pending approval
identity/data. Root step boundaries and simple root approval waits are resumable after platform
old-stop/effect reconciliation and a new instance/attempt/epoch. In-flight nested/parallel/loop
states are explicitly unsafe and refuse replay; no arbitrary checkpoint/name is a resume permit.
Execution directory relocation is refused. Final result bytes are in `.runner-state/result.json`;
its raw hash is returned. JVM emits an honest **unconfirmed** stop receipt while alive; supervisor
must measure exit, replace only the receipt, upload result/checkpoint metadata, flush durable event
ACKs and independently compute the business chunk receipt manifest before HTTP completion.

Output reads/publishes retain the full existing runtime DTOs, selector, missing policy, source and
visibility. Callback grants contain only enumerated Braidrun controls and must cover the operation
budget; no business broker/OAuth-refresh operation can be granted. Credential material stays in
transient authorization/runtime memory, never events, checkpoints, results, logs or argv. Rotation
uses the granted credential version and canonical material receipt; failure latches uncertain
rotation and forbids reuse. A generic local OAuth connector issues upstream and refresh requests
in this JVM, with explicit public/native or authorized user-owned client mode; access-only tokens
cannot refresh and shared confidential secrets are not implicitly delegated.

Model construction uses the coroutine-scoped host factory (Koog no longer inherits HttpAccess by
default). Key parameters use `credential:<credentialRef>@<version>`; each child leases its own
scope. Hosted models cannot fall back to process environment keys. Direct JVM HTTP uses explicit
NO_PROXY transport, strict TLS, pinned validated DNS, no redirects/direct retry fallback and gates
before requests. Actual `jvm-http` path attestation is required; curl alone does not certify it.
Scripts require a separately attested `subprocess` path plus the supervisor's tool/network/HOME
credential policy. Oversize captured output fails explicitly rather than using truncated output
as complete stdout. Safe proxy/PAC/private-client adaptation is not inferred from env variables.

## Current evidence and remaining work

`compileKotlin`, `installDist` and 33 targeted tests pass in `/tmp/mac-runner-engine-test.log`:
canonical fixtures, grouped approvals/ask/tell, duplicate/late/old frames, cancellation, typed
savepoint resume, source identity inheritance, credential scope/local refresh/CAS, outputs,
post-approval evidence, a real local JVM/bash/stdio smoke, model SDK through an injected host
HTTP fixture with usage, and nested sandbox/Jev/library regressions. Real JVM/bash evidence uses
synthetic supervisor/egress data; model HTTP is MockEngine. These are not public egress, real
subscription account, Apple, full platform E2E or production acceptance.

Subscription CLI scope/lease renewal/rotation is implemented below; App Factory local stage
execution and platform credential integration still need complete cross-source acceptance.
MCP, skills/hooks, containers, RAG, browser and unported connector paths
remain explicit startup rejections until each has supervised launch, source/path portability,
network, minimal credential and side-effect evidence. JVM capability readiness does not enable
production; the platform is the only final integrator and release owner.

Platform integrates the engine with `-PworkflowRuntimePath=<this worktree>`. Supervisor now pins fdef2232 for its integration work; keep the exact same canonical resource
hash in both runtime packages before formal acceptance.
The earlier RunnerConnector nullable URL receiver compile issue is fixed; the current successful
compile/test log supersedes that old platform-manifest failure. Keep evidence for tests, commits,
cross-build, actual Mac/account acceptance, package, deployment and activation separate.

## Subscription CLI and ACK-bound capture completion

Codex/Claude subscription adapters now use explicit `external_agent_codex_auth_json` /
`external_agent_claude_oauth_token` credential references plus explicit subscription auth mode.
They select only that provider/ref in the current child/step scope. Grant permission is renewed
while the CLI/model remains active; this does not ask the platform to refresh upstream tokens.
Changed vault versions/material or failed renewal interrupt the attempt. Successful Codex local
refresh uses version CAS and advances a nonsecret credential cursor in the host checkpoint.
Rotation intent/material is fsynced under the private `.runner-private/pending-rotations` scope;
normal ACK removes it, uncertainty retains it for authorized reconciliation. That directory is
excluded from business artifact registration. Auth JSON tokens are recursively registered for
redaction. Native CODEX_HOME and HOME are per-run private, never operator HOME; arbitrary local
auth-file/home configuration, unmanaged MCP and unbound CLI session resume remain refused.
Hosted rotation failures propagate and temporary Codex homes are removed even on that failure.
The default ordinary CLI/library behavior remains compatible.

`RunnerArtifactReader` consumes the supervisor-owned artifact index format from artifacts.py:
`.runner-state/artifact-refs/<ACKed remote ID>.json`. It requires full binding, exact remote ID,
format/ACK flag, complete ordered chunk receipts, raw/chunk sizes/hashes, immutable spool-relative
path and the expected registered process capture path. Capture identity is recomputed from the
canonical binding/path/raw hash. Unknown absolute/local IDs, changed spools, wrong attempts,
missing chunks and symlink paths fail. Large stdout/stderr are read through this verified adapter;
integrity failure latches interruption rather than falling through to another business step.
Tests include real local >1 MiB bash output and tamper/cross-execution/symlink negatives. ACKs
are fixture ACKs in engine unit tests; supervisor/platform normal upload acceptance is separate.

New adapter evidence: 49 targeted tests passed (includes 11 native auth-file compatibility cases),
plus compile and installDist. Current test log: `/tmp/mac-runner-engine-cli-test.log`.
No real subscription account, public egress, Apple write or deployment was performed.

Remaining exact integration requirements: CLI profiles must attest `agent-cli`, model profiles
`jvm-http`, scripts `subprocess`, using their actual client config and environment. App Factory
modules must invoke supervisor local stages instead of submitting remote stage queues, while
preserving Apple dual gates and receipts (platform template ownership). Long-lived stdio MCP
requires interactive stdin/output transport beyond the current processLaunch/exited contract;
CLI progress currently reaches the JVM after registered exit/full capture. This transport gap
must be completed by a coordinated platform/supervisor contract revision, not a private engine
protocol. Skill hooks/MCP/containers and remaining connector modules are unfinished capability
work, not considered complete by rejection. Keep feature disabled until complete acceptance.

Interactive preparation uses `WorkflowInteractiveProcess` and `RunnerBytePipe` with explicit
bounded backlog, EOF and cancellation wakeup; no unapproved wire fields were added. Hosted MCP
registration routes through `WorkflowHost.mcpTransport` and fails explicitly, never ProcessBuilder
or silently missing tools. Hook execution routes through admitted script + scoped supervised
executor, inheriting the current child/step and cancellation job. Static skill config is confined
to frozen bundle skills; ambient HOME discovery, downloads/upgrades, hook auto-execution and
skill admin mutation cannot widen it. Full hook/MCP activation still awaits the interactive
protocol and per-client acceptance. Ordinary non-hosted hook/MCP behavior is retained.

## Official interactive extension implementation

Interactive extension introduced at `261b43bbc9ccc492ad658e42cca821174da6ef5e` (22 shared fixtures);
current embedded wire pin is the 81d611a6 compatibility revision below.
Approval payloads now carry explicit occurrenceId, and decisions must match it as well as the
persisted key/version. Existing occurrence cursors and pending request state remain checkpointed.

`RunnerInteractiveProcesses` uses only processOpen/Started/Output/OutputAck/Input/InputAck/
Cancel/Closed from that pin. Binary chunks validate base64 length/hash and global per-process
sequence; stdout/stderr consumption advances only the contiguous consumed prefix. Duplicate
unchanged output can re-ACK without refeeding bytes. Input and EOF are chunked, written once,
and require WRITTEN with exact sequence/length/eof; uncertain/rejected delivery latches authority
loss without automatic resend. Registered close requires matching sequence and confirmed scope
exit; it never proves the still-live root JVM stopped. Independent lease/environment supervision
remains active. Per-process queues, chunks, total bytes and process count are bounded.

The native adapter uses the interactive path for live stdout callbacks and large stdin; one-shot
captures retain the verified ACK artifact reader. MCP SDK StdioClientTransport is backed by these
registered channels, with owned close requests. Scope/job context reaches model tools, MCP and
hooks. Static skills retain declared selection while HOME scanning, registry downloads/upgrades,
script auto-execution and admin mutation are withheld; full skill inventory hashes are checked.
Hook scripts have an injected supervised route and fail explicitly rather than spawning bare
processes. HTTP MCP, bundled auto-start daemons, containers and browser remain unaccepted scope.

Latest evidence: 77 scoped engine/library tests pass, including SDK MCP initialize/tool catalog
through hosted byte channels, interactive binary order/consumption ACK, uncertain input no replay,
bounded backlog/EOF/cancel wakeup, scoped hook delegation, static skill policy, 22 contract fixtures,
subscription/capture tests and legacy hook/MCP/auth/sandbox/Jev regressions. Compile/installDist
pass. Log `/tmp/mac-runner-engine-interactive-test.log`. These use controlled protocol/provider
fixtures; platform owns actual fixed-source JVM/Python/HTTP/Mongo/package and full App Factory
acceptance. This is a component implementation handoff, not a complete App Factory rollout or
real-account acceptance. No deployment/publishing/activation/existing Runner upgrade occurred.

## Compatibility update: claim authority and approval inbox

The embedded contract now pins platform `81d611a65b7c93e3341c69db51c7736a1c411175`, schema
SHA-256 `09a3c3f6d6bd430a126303f25ad9795c5818d72eec62bc2896cfe84af40a504a`.
ClaimResponse retains its real phase and requires PREFLIGHT/REPORT_ONLY authority. This is an
HTTP supervisor admission boundary: the JVM does not claim tasks, convert claimResponse into
start, or possess a report-only business execution path. REPORT_ONLY must stop before supervisor
JVM launch, renewal and credential access; its acceptance remains supervisor-owned and must be
verified in the jointly pinned package. The JVM wire still strictly accepts start/resume frames
under their existing schemas; full claim/report payloads cannot be used as start frames.

Approval requests use dedicated approval IPC, persisted by supervisor HTTP host-requests with
immutable occurrence/key. Monitoring uses step/usage events, not approval eventBatch entries.
The generic JVM event publisher now rejects kind=approval before changing sequence or writing
local event evidence. The real headless startup fixture includes an approval: no script launches
before the human fixture decision/fresh environment; every event frame must remain non-approval.
This compatibility update runs only protocol/IPC/approval adapter/headless-start targeted tests;
prior wider component tests are not claimed as rerun. No deployment/release/activation occurred.

Compatibility evidence: 13 targeted tests, 0 failures/errors/skips, passed in
`/tmp/mac-runner-engine-contract-81d-test.log`; current compile passed with these tests.

## Independent 0d4f7de review repair evidence

This batch addresses engine review `/Users/liguoliang/Documents/Codex/2026-10-08/mac-runner-development/reviews/engine-0d4f7de.md`.
The engine now pins semantics commit `39a264aacca2f46ff8fb3e53232b2e76eee627ac`; schema bytes
remain hash `09a3c3f6d6bd430a126303f25ad9795c5818d72eec62bc2896cfe84af40a504a` (81d shape).

1. P1 static child reentry: hosted children get parent-owned monotonic invocation IDs
   `root__step__call_N`; child invocation counts serialize/hydrate with typed savepoints.
   Ordinary no-host child IDs retain legacy behavior. A real interpreter state-machine loop
   child -> control -> child approves only the first occurrence; the second stays pending and
   the effect count remains 1. Root boundary and checkpoint codec tests preserve counters.
2. P1 credential expiry: renewal is scheduled from actual remaining grant TTL (bounded by the
   prior interval), with an independent deadline guard. Expiry latches authority loss and signals
   cancellation even if renewal RPC remains blocked. A received renewal cannot replace an expired
   generation or revive a latched attempt. A short-TTL synthetic grant keeps root/environment
   valid, blocks renewal, cancels the CLI by the credential deadline and rejects late revival.
3. P1 EOF: local interactive pipes fail/wake independently of sending remote processCancel.
   HostMain IPC/control loss closes them immediately; gate invalidation also reaches broker pipes.
   Streaming root and blocked stdout/stderr readers complete within a bound without processClosed.
   Local close never claims remote stop confirmation.
4. P1 rotated secret publication: private scoped Codex auth.json is observed before CLI execution;
   each event/log redaction refreshes and registers its current opaque tokens. Unreadable/invalid
   scope files suppress output; exit-time registration is no longer the first protection. Fake
   executor atomically rewrites auth.json, emits command output containing the new opaque tokens
   through live callback, then exits: neither event frames nor durable events contain those tokens.
   Supervisor wire/capture/spool publication must use the same pre-publication scoped-file refresh;
   its current source has refresh_scoped_secrets hooks, but this batch does not substitute an engine
   test for final supervisor fixed-SHA/combined acceptance.
5. P2 fast normal exit: no-input EOF becomes a local no-op after a confirmed normal close;
   processOpen continuation racing processClosed still returns exit 0, not a false failure.
6. P2 session capacity: confirmed/failed sessions leave active capacity and enter bounded
   tombstones preserving duplicate/late validation. Seventeen sequential open/close calls pass.
7. P2 human freshness: official replyTo association replaces pre-request generation checks.
   Only a distinct ALLOW proof correlated to the exact original request frame can release its
   key/occurrence; periodic, unrelated, relabeled old receipt and old-epoch evidence cannot.
   Associated pre-delivery checks work for approval and askUser. Human responses require replyTo.

32 directed engine/library regression tests passed, 0 failures/errors/skips, with compilation.
Log: `/tmp/mac-runner-engine-review-fixes.log`. Account credentials/probes/business are synthetic;
no real account, Apple operation, deployment, publishing or feature activation occurred. Wider
prior suites were not rerun without a new reason. This batch requires re-review and fixed-source
package integration; it does not declare complete App Factory acceptance.


## Checkpoint recovery after the a4f2dabb re-review

This scoped recovery preserves the original four-file patch and prior 8-test evidence before
applying it in an independent engine worktree. Environment receipt IDs retain their first hash
and association for the entire attempt; an older periodic receipt cannot be relabeled for a
human decision after newer receipts. Empty EOF checks its lifecycle after the gate probe under
the confirmed-close monitor. Local reader closure retains process/output registration until
remote `processClosed`, allowing bounded, validated TERM/grace output and evidence ACKs. Closing a reader settles the
already accepted unread prefix so discarded bytes cannot leave a permanent ACK hole.

Actual child invocation paths remain unchanged (`nested/call_N`, including real `call_1` step
names followed by their own ordinal). Platform commit `316ca337d550d7bb6e0300452392bb14f7205951`
resolves exact frozen parent/edge/hash/ordinal pairs to logical ACL lineage. Engine credentials,
approvals and interactions continue to send the actual path; no generic marker stripping or
additional client field is introduced.

The wire/schema origin correction is source metadata, not a patch to an existing JAR. A rebuilt
engine distribution and its manifest/resources must be verified before platform packaging.
Engine compilation and the 8 focused tests are separate evidence from combined
JVM/Python/HTTP/Mongo/package acceptance, which remains owned by platform. No deployment,
activation, existing Runner upgrade, real-account/Apple action or Linux VM/container is involved.

Recovery verification: compilation and `installDist` passed; 8 focused tests (3 decision freshness,
4 stream lifecycle, 1 byte/ACK/input regression) passed with zero failures/errors/skips. The new
installed `runner-host.jar` reports schema-source `81d611a65b7c93e3341c69db51c7736a1c411175`,
contains the unchanged schema hash above and the separate semantics provenance. A standalone
contract harness loaded this installed distribution and passed all 22 bundled positive/negative
contract fixtures. These are engine distribution checks, not combined platform package acceptance.
Evidence: `/Users/liguoliang/Documents/Codex/2026-10-08/mac-runner-development/engine-recovery-evidence/`.
