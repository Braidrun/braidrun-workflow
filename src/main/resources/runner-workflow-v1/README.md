# Runner workflow protocol 1 (frozen)

This is the canonical wire contract for platform, JVM host and Python supervisor.
`protocol.schema.json` uses JSON Schema 2020-12. Validate the relevant `$defs`
entry (not merely the root union); `fixtures/index.json` names that entry.
Timestamps are UTC RFC3339, hashes lowercase SHA-256, counters JSON integers.
No credentials appear in execution bundles, events, results, logs or artifacts.
Schema validity is necessary but does not establish authority or semantic validity.

## HTTP

All paths are under `/api/workflow-runner/v1`. Authentication is a separately
issued `WORKFLOW_RUNNER` principal bound to a runner and host scope; existing
`APP_FACTORY_RUNNER` credentials grant no authority here. Identity comes from
that principal and persisted claim, never a body owner field alone. Revocation
is checked on every request. Scope, actor, workflow owner, enrollment owner and
billing owner are independent identities.

| Method/path | Request definition | Response definition |
|---|---|---|
| POST /claim | claimRequest | claimResponse (204 when no eligible task) |
| POST /executions/{id}/renew | renewRequest | renewResponse |
| POST /executions/{id}/environment | operation + environment | ack |
| GET /executions/{id}/bundle | binding/lease headers | bundle (files downloaded by hash) |
| POST /executions/{id}/events | eventBatch | ack |
| POST /executions/{id}/commands/ack | operation + ackCommandSeq | ack |
| POST /executions/{id}/credentials/lease | operation + credentialRequest | credentialGrant |
| POST /executions/{id}/credentials/rotate | operation + credentialRotation | ack |
| POST /executions/{id}/artifacts/chunks | operation + artifactChunk | ack |
| POST /executions/{id}/complete | operation + result | ack |
| POST /executions/{id}/report-authorization | binding | reportGrant |
| POST /executions/{id}/reports | report | ack (quarantine persistence only) |

Here `operation + X` means an object with all operation fields and one field
named exactly X (`environment`, `credentialRequest`, `credentialRotation`,
`artifactChunk`, `result`); commands/ack adds the integer `ackCommandSeq`.
All mutation retries keep the same request/event/command/artifact identity.
409 means binding, revision, sequence or credential-version conflict. 403 means
no authority. 412 means environment/capability precondition failed. 410 means
execution lease expired; client stops business processes and obtains report-only
authority. Errors have the `error` shape. Limits: HTTP JSON 4 MiB; events at most
128 per batch, chunks at most 1 MiB decoded, maximum pending controls 256.

## Fencing and persistence

Claim uses atomic QUEUED -> PREFLIGHT; it binds instance, attempt, epoch, hashes
and lease and must not deliver the same execution to a competing claimant.
Renew every 15 seconds; initial lease 60 seconds. Calculate remaining time using
serverTime and a local monotonic clock, conservatively accounting for round-trip
time. Lost connectivity is never permission to continue past the local deadline.
CAS predicates contain full binding, expected phase/revision and lease ID/expiry.
Every successful mutation increments revision; responses carry the new revision.
Process sequential writes per attempt. A retry is answered from the stored
idempotency receipt before stale-revision rejection. Renew/control consumption
and credential access require a live lease. No expired task is automatically
requeued or executed on another host/server.

Events must be contiguous starting at 1. Deduplicate by binding + eventSeq AND
eventId; a changed payload under the same identity conflicts. ackSeq is the
highest contiguous DURABLY stored sequence, not the highest received sequence.
Do not compact the local journal before this ACK. Commands have analogous
monotonic sequences and durable IDs. Approval decisions originate only from
human-authorized platform routes; runner cannot approve. Decisions retain
approvalId, idempotencyKey, childExecutionPath, editable data and decisionVersion.
Checkpoint files contain actual variables, outputs, state position and approval
state; a step name/log is not a resumable checkpoint.

Finalization requires finalEventSeq <= persisted ackSeq, verified artifact
manifest/result hashes and a matching confirmed stopReceipt. Confirmed means
all registered processes EXITED and no unmanaged process detected. Before engine
start, engineStarted=false and an empty registry can prove no process was
started; do not invent process cleanup. During execution a stop request remains
STOP_REQUESTED / STOP_UNCONFIRMED until evidence establishes stopped. Terminal
results are immutable; late success cannot overwrite an interruption/cancellation.

Report-only authorization is available on lease expiry, disconnect, stop
unconfirmed OR terminal state, so stop evidence can establish finalization.
It expires in 24 hours and binds the OLD complete attempt/instance/epoch/hashes.
It accepts only existing journal, stop receipts, external-effect receipts and
artifacts into a distinct reconciliation store. It grants no renew, execution,
new business request or credential rights. A report ACK means persisted in
quarantine, not execution completion. Platform reconciliation may associate
valid evidence with the same old attempt using CAS; never overwrite a newer
epoch/terminal result. Revoked authentication cannot obtain a report grant.
Restart uses a new instance; explicit reconciliation and old-instance stop proof
precede a new attempt/epoch. Preserve original execution ID and effect receipts.

## Environment semantics

Environment evidence is bound to the complete attempt; validity at most 60 sec.
ALLOW requires nonempty evidence covering every declared execution network path,
source, address family and profile hash. Any credible CN path => BLOCK_CN.
Unknown/conflicting/expired/unsupported route => BLOCK_UNVERIFIED. Dependencies
missing => BLOCK_DEPENDENCY. HK/MO/TW are not CN; existing service region rules
remain separate. zh primary language or effective locale => visible warning,
never an approval gate; failed language read also warns. Only ALLOW and live
lease permit business credentials/engine start. Recheck before steps/external
calls, resume/approval continuation and after wake/network change. Supervisor
also checks every 30 sec and stops on invalidation or lease loss independently
of upload/log backpressure. Fixture IP addresses are documentation addresses,
not actual network evidence.

## NDJSON IPC

UTF-8, one complete JSON `ipcFrame` per newline. stdin supervisor -> JVM;
stdout JVM -> supervisor only protocol frames; stderr ordinary redacted logs.
Maximum frame 1 MiB UTF-8 including newline; reject malformed/truncated/oversize
lines and stop safely. Bounded queue 128 frames; never block environment/lease
supervision on stdout or HTTP. request frameId is unique per attempt; responses
carry replyTo. No bearer secret in CLI args; use restricted stdin authorization.

| Frame | Direction | Meaning |
|---|---|---|
| ready | JVM -> supervisor | Matching runtimeHash, advertised capabilities |
| start | supervisor -> JVM | Verified bundle and fresh ALLOW evidence |
| event / ack | JVM -> supervisor / reverse | Journal then platform durable ACK |
| approval / approvalDecision | JVM -> supervisor / reverse | Persisted request / human decision |
| userInteraction / userResponse | JVM -> supervisor / reverse | Remote ask/tell interaction |
| credentialRequest / credentialGrant | JVM -> supervisor / reverse | Minimum scoped credential lease |
| credentialRotation | JVM -> supervisor | Runner-performed refresh version CAS |
| checkpoint | JVM -> supervisor | Hashed real restore state file |
| cancel / environmentInvalidated | supervisor -> JVM | Stop new operations and registered process tree |
| result / stopReceipt | JVM -> supervisor | Result plus supervised stop evidence |
| error | either | Structured failure, never permission to fallback |

Only credentialGrant and credentialRotation carry secret material; these frames
are transient, never journaled or echoed. Registered process ownership is tied
to PID AND process start identity; a reused PID is not permission to kill it.

## Compatibility and release

Historical missing executionTarget means SERVER. New requests with legacy
mac_runner_ref and no explicit target are rejected. Children inherit root target
and authority. SERVER is an explicit compatibility choice for internal callers.
Version 1 is strict: unknown frame types, fields, enum values or version rejected.
Changes require a new protocol version or all three implementations and fixture
updates in a coordinated commit; never mutate a pinned runtime's contract.
No runner is eligible merely from its version string: required capabilities,
runtime/schema/bundle hashes and supported architecture must match the verified
release manifest. New capability stays disabled until complete acceptance;
unsupported definitions/connectors fail before any business preparation.
OAuth refresh and connector calls run on Runner; no shared confidential client
secret is implicitly delegated and no platform business broker fallback allowed.

## Coordinated pre-release completion (protocol still 1)

The first three-end integration review identified missing launch context and
supervised process/output/resume semantics. The v1 contract is completed before
any runtime is released; all three clients must pin the NEW contract commit and
schema hash. No existing runner/runtime uses this draft protocol.

`start` and `resume` carry authorization, lease and nonsecret inputs. These do
not authorize execution until fresh ALLOW and valid lease. `resume` additionally
requires a real checkpoint, previousBinding and a platform reconciliation receipt
hash: old registered process stop + external-effect ledger check must precede
issue. A repeated start is a conflict, never restart. `leaseRenewed` and
`environmentRefreshed` only refresh a matching attempt, never begin execution.

`processLaunch` JVM -> supervisor delegates launch to the supervisor-owned
launcher (no unsupervised JVM ProcessBuilder fallback). `processStarted` returns
registered PID/group/start identity; `processExited` returns registered exit and
artifact IDs. The launcher rejects absolute/escaping cwd, unapproved environment,
daemonization/container capabilities and unsupported process ownership. Secret
environment lives only in memory; processLaunch is transient and redacted.
Supervisor must supervise independently of IPC backpressure. A still-live JVM
cannot claim its own confirmed stop: it emits result with an honest unconfirmed
receipt; supervisor replaces only that receipt after JVM and all registry entries
exit, and sends HTTP complete with the independently measured receipt.

`outputRead`/`outputValue` and `outputPublish`/`outputAck` bridge scoped Braidrun
outputs using inherited child scope. `callbackTokenRequest`/`callbackTokenGrant`
are restricted to the enumerated Braidrun controls; no business connector/broker
or OAuth-refresh operation is ever granted. callbackTokenGrant is transient,
secret-bearing, never journaled. Unsupported output/token capability rejects at
admission. Approval editableColumns contains {group, definition:approvalGroup};
items and editedItems contain {group, item}. Preserve name/source/output/key/column
metadata and item topology; no flattening or ambiguous group inference allowed.

### Full adapter payload completion

CredentialGrant includes the new task `revision` after persisting its lease.
Grouped approvalDecision includes `comment` and explicit `editMode`: AS_IS means
preserve all original groups/items; REPLACE means supplied groups/items replace
original data (including explicitly empty groups). No decision consumes an
ambiguous empty edit list. Preserve decision version/idempotency keys.

processLaunch includes stdin, wall-clock timeoutSeconds and captureLimitBytes;
stdin/env are transient and redacted. processExited returns bounded stdout/stderr,
captureTruncated and timedOut plus optional artifact IDs for the full capture.
The inline result must fit the 1 MiB frame. If capture would exceed it, return
truncated inline output and full capture artifact IDs; an adapter requiring full
inline output must reject or explicitly read the authorized artifact, never
silently reinterpret truncated bytes as complete stdout. Mounts/container network
settings remain unsupported until separately advertised/validated.

Output read/publish carries the complete existing WorkflowOutputRuntime fields.
Selector is the existing WorkflowOutputSelector object with modes
execution_id/latest_successful/input_variable and execution_id,
execution_id_variable, step; missingPolicy is fail/skip_step/use_default. Read must enforce caller/target identity and source visibility;
publish must enforce root/child execution identity, output type/contract and
visibility. Never infer sourceExecutionId from outputName. callbackTokenRequest
includes requestedTtlSeconds and operationBudgetSeconds (max 3600); deny a grant
whose remaining TTL is less than the operation budget. Long operations must
obtain renewable controls, never a long-lived unrestricted bearer.

Metadata hashes use recursively sorted JSON object keys and compact UTF-8 JSON,
arrays retain order, integer counters retain exact integers, strings use normal
JSON escaping with Unicode characters preserved. Files/chunks hash raw bytes.
`bundleHash` hashes {rootWorkflowId,files,definitions} without bundleHash; arrays
are sorted by path/workflowId. ArtifactManifestHash hashes sorted chunk receipts
(artifactId,logicalPath,chunkIndex,chunkCount,sha256,size), excluding server-only
storageKey. Event/report idempotency hashes use this canonical metadata encoding.

claimResponse includes the nonsecret `inputs` map for start/resume, alongside the
frozen bundle. Inline key/token/password material is refused; use scoped credential
references. GET bundle and bundle/files/{sha256} use URL-safe base64 JSON binding
in X-Runner-Binding, X-Runner-Lease-ID and X-Runner-Revision headers. Each file
hash must belong to the persisted secret-free manifest; no filesystem path access.
Human Runner artifact download uses execution visibility and streams chunks in
order through /api/executions/{id}/runner-artifacts/{artifactId}.

POST /executions/{id}/host-requests takes `hostFrameOperation` (operation + full
original `frame`) and durably stores approval/userInteraction requests, returning
ack/revision. The original frameId and idempotencyKey are preserved. These requests
have their own inbox and never consume/rewrite JVM eventSeq. Repeating the same
frame retries its durable projection; changing content under one ID conflicts.
Human decisions/replies arrive in the ordinary command inbox with the same
idempotencyKey. Output/callback adapters use their own restricted controls and
must be accepted before being advertised; host-requests cannot bypass that gate.

ResultHash must match durably uploaded `.runner-state/result.json` raw bytes.
That result/checkpoint journal metadata is excluded from the business artifact
manifest; the manifest hashes sorted business chunk receipts. Complete never
accepts an arbitrary result hash or a guessed artifact hash.

## Interactive process extension (pre-release protocol 1)

This is a versioned IPC extension of the unpublished v1 contract; every client
must pin this new contract commit/hash before advertising interactive capability.
It is distinct from one-shot processLaunch/processExited and HTTP host-requests.
All frames retain the full attempt/instance/epoch/root binding.

- `processOpen` JVM -> supervisor creates an interactive process exactly once for
  stable requestId. The existing processStarted response retains that requestId,
  registrationId and PID/start identity. Reuse with changed launch content conflicts;
  restart/disconnect never starts a second process for an old intent. A running
  process resumes only under the original live identity; no new epoch inherits stdin.
- `processOutput` supervisor -> JVM emits globally contiguous outputSeq per process
  across stdout/stderr, raw-byte base64 encoding and decodedBytes/dataHash. Maximum
  decoded chunk 32 KiB. Redact known secrets BEFORE any wire/spool/log publication;
  secret-carrying input stays transient. Final processClosed includes finalOutputSeq.
- `processOutputAck` JVM -> supervisor acknowledges the contiguous consumed output
  prefix. Durable redacted spool permits same-identity retransmission after a lost
  ACK; input is never replayed as a side effect of output retransmission. A new epoch
  cannot ACK/control another process. Journaling output does not authorize execution.
- `processInput` JVM -> supervisor carries contiguous inputSeq, the same encoding,
  raw-byte length/hash and explicit eof. eof requires zero bytes and closes stdin
  once; input after EOF conflicts. Stable hash-only delivery intent is persisted
  BEFORE writing; `processInputAck` reports WRITTEN only after the write/flush.
  Duplicate WRITTEN input returns its stored ACK without rewriting. If crash/disconnect
  leaves an intent without definitive ACK, report DELIVERY_UNCERTAIN and stop/reconcile;
  never automatically resend bytes/EOF. No secret input data enters durable evidence.
- `processCancel` JVM -> supervisor is idempotent by commandId and registration/start
  identity, TERM then bounded grace (<=5s), KILL then readback. `processClosed` only
  confirms that registered process scope, not the still-live root JVM. Unreadable,
  escaping or unmanaged processes keep stopConfirmed=false and interrupt the root.
- Bounded queues: at most 64 unacknowledged output frames and configured pending bytes
  <=2 MiB per process; total output <=16 MiB, configured timeout <=24h. Buffer overflow
  or missing consumption stops the owned process scope with an explicit reason, never
  blocks the independent environment/lease watchdog. A timeout/closed root rejects
  new stdin/launch while allowing existing evidence ACK/reporting. Binary/streamed
  stdout is not silently treated as complete line output; clients preserve bytes.

Approval requests and decisions include explicit `occurrenceId`. Stable keys bind
immutable request content, child/step and occurrence. A new occurrence uses the
logical `root__child:step:<occurrenceId>` idempotency key and gets an independent
PENDING decision. A repeated key with changed content/occurrence conflicts. The
engine must persist occurrence identity in its typed checkpoint; an old decision
cannot wake another occurrence. Approval task decisions persist the complete stable
payload and repair their UI mirror without a second human decision or new timestamp.

POST /executions/{id}/output-control takes hostFrameOperation for outputRead or
outputPublish, validates live full binding/lease and the frozen output authorization
manifest, and returns a correlated ipcFrame. It is separate from host-requests.
Request frame identity/hash is durably retained; identical completed retries return
that response and never broaden source/visibility/type/contract publication policy.
The HTTP response header X-Runner-Revision carries the current task CAS revision.

Claim retries recover original binding/attempt/lease only for the authenticated
same instance and identical request fingerprint. Responses carry the REAL phase
and explicit claimAuthority. Only PREFLIGHT authority may perform preflight/start;
REPORT_ONLY permits original journal/stop reconciliation and never engine start,
credentials, renewal or new business operations. Cancel between lost response and
retry returns STOP_REQUESTED + REPORT_ONLY, never fabricated PREFLIGHT. Lease expiry
is not extended by recovering the receipt; serverTime is refreshed conservatively.
Approval type in eventBatch is rejected before persistence; only host-requests owns
immutable occurrences. Startup/periodic platform outbox repair projects already
persisted canonical decisions without a second approve or replaying ACKed frames.

## Decision-correlated environment freshness (semantics v1)

This freezes semantics using existing frame fields; the schema bytes/shape remain
81d611. Periodic environmentRefreshed frames carry NO replyTo and cannot authorize
approval/userInteraction continuation. After receiving the actual persisted human
control decision, supervisor performs a NEW environment check for that decision,
publishes it to platform, receives durable ACK, then sends environmentRefreshed
with replyTo equal to the original approval/userInteraction request frameId. The
approvalDecision/userResponse follows and replies to that same original frameId.

Both frames retain the complete current binding. A correlated check has a distinct
environmentCheckId, starts AFTER the control decision was received, remains <=60s,
and must be ALLOW under all required routes. Engine matches original request frame,
occurrence/idempotencyKey and binding, and consumes only its newly correlated check;
a wait-time periodic probe, unrelated request, reused check ID or old epoch cannot
release continuation. Reject/time-out/control-loss never defaults to approval.
The platform outbox decision version remains the original human decision version.
