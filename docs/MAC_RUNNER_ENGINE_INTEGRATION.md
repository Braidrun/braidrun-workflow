# JVM Runner host integration handoff

User constraint: **do not deploy, publish, enable the feature, upgrade existing Runners or migrate
production executions**. Development, tests and scoped commits are authorized. Full App Factory
acceptance remains an integration milestone; admission rejection is not completion of missing work.

Engine baseline: `6330754b8d76674555e89f7994c17d88fab7e3ca`.
Canonical contract: `fdef2232826517af77c1fb8d3d832b261960a53e`, protocol 1. Resources and
15 positive/negative fixtures are copied from that immutable platform commit. No private wire
protocol has been introduced. Platform and supervisor must pin this same commit/schema hash.

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

Subscription CLI scope/lease renewal/rotation and App Factory local stage execution are the next
necessary adaptations. MCP, skills/hooks, containers, RAG, browser and unported connector paths
remain explicit startup rejections until each has supervised launch, source/path portability,
network, minimal credential and side-effect evidence. JVM capability readiness does not enable
production; the platform is the only final integrator and release owner.

Platform integrates the engine with `-PworkflowRuntimePath=<this worktree>`. Supervisor committed
pin was 4752de49 when last inspected; refresh it to fdef2232 before formal cross-source acceptance.
The earlier RunnerConnector nullable URL receiver compile issue is fixed; the current successful
compile/test log supersedes that old platform-manifest failure. Keep evidence for tests, commits,
cross-build, actual Mac/account acceptance, package, deployment and activation separate.
