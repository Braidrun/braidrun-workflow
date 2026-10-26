# E artifact-set IPC and producer-boundary stage

Raw schema source: `d349d5d269364f6967863952257aac069821c00e`.
`artifact-set.schema.json` SHA-256:
`4b254106f3ed68bedb1bb058cddf0deba74c7a643ed9f4bb59f57b7dcf1323ff`.
Correlation/order semantics: `cb53a03fd75d938792664e7af88d1a6e2d8f6c6b`.
These are separate origins; cb53 does not relabel d349 raw bytes. Five existing E
schema pins remain unchanged. New JAR manifest records both origins independently.

## Implemented stage

`artifact.set.v1` requires explicit `artifact.collection.v1`. E consumes only the
runtime-role, manifest-hashed `runtime/runner-artifact-sets.v1.json` carrier. Policy
checks include root/static child graph, Code/Agent/state producer, unique declaration
IDs, disjoint exact-file/set carriers, bounded FILE/TREE/JSON_FILE selectors, private
path rejection, normalization aliases and per-declaration limits. E does not inspect,
scan, fingerprint or upload business file bytes. P/S own PRESENT/ABSENT observation,
sealed snapshot, selector-manifest proofs and full member index.

Natural Agent completion invokes the set boundary after actual persistence. Code
invokes it after its subprocess result/exit/capture returns and before
`code_step_completed`/normal logical step closure. A nonzero natural exit retains
sets when the policy includes DIAGNOSTIC, with outcome FAILED; the original failure
still propagates after collection. Cancellation/unknown exit never synthesizes ready.
Producer identity comes from original monitor start, admitted definition and actual
invocation; dynamic calls cannot claim a static child declaration.

All applicable IDs (up to 128) are emitted before awaiting collection, in original
partitions of at most 64 IDs and full event frames <=64KiB. Ready partitions retain
original eventSeq/eventId/frameId in a private journal and one shared event sequencer.
A partition needs its separately correlated ready ACK; an unrelated cumulative ACK
cannot substitute. All partition Complete receipts are required before logical
closure or the next step. A producer may have no applicable declarations and continue
normally; optional absence belongs to S/P snapshot evidence, not polling in E.

The three frame definitions are validated by exact named `$defs`: `collectFrame`,
`indexAckFrame`, `completeFrame`. They are enabled only after set negotiation and have
strict inbound/outbound directions and full binding equality. Set requests persist
original request -> event correlation before sequencing. Same boundary cannot acquire
new request/set/ticket identities. Unknown/mismatched child/start/policy/proof refuses.
Malformed/partial journals require reconciliation rather than replacement identities.

The original S Collect F_S produces original E commit F_E with replyTo F_S. Both
base ACK and IndexAck have replyTo F_E. IndexAck must carry matching original eventId,
eventSeq, setId and snapshot hash, and supplies exact P receipt revision/setIndexHash.
Complete has replyTo F_S and those same identities. Both proofs are persisted before
Complete. Neither proof alone releases the barrier. Retries/reconstruction reuse the
original ready/event/request/Complete and never rerun the business producer. Closed
or expired authority refuses new work while original private evidence stays retained.

Initial ticket expiresAt is parsed and frozen with the request, but is not a new E
execution deadline. P/S renewal updates only their upload ledger for the same sealed
intent. E never rewrites the request. Collection and proofs check the actual active
producer and inherited original code/step/global budget, lease/environment and cancel
fences. Resource admission wait is excluded from the existing execution budget; no
post-write or post-exit timeout reset is introduced.

## Verification boundary

The 44-case targeted batch passes with no failures/errors/skips. Tests cover normal typed HTTP root/child writes with a deterministic pre-write
pause, no early ready, cancellation and original deadlines; real no-account CLI
root/child/state final persistence; native Bash root/child and nonzero diagnostics;
actual ProcessBuilder exit forwarded through normal RunnerProcesses processExited
before ready; all 128 IDs/two partitions; wrong index correlation; changed identities;
missing/unrelated ready ACK; elapsed upload metadata; durable component reconstruction;
exact P's ten named-definition fixtures and bounded old IPC/schema/HTTP regressions.
The committed Python smoke driver additionally starts the fixed production JAR and actual native Bash through process IPC, retaining both event and control traces. P/S prepare/upload/index services remain controlled in these engine tests. The native
process fixture does not establish real S kernel peer registration or P HTTP/Mongo
acceptance. The separate reconstruction case uses a controlled producer guard and
shared sequencer, and is not a workflow restoration or replay permit.

## Remaining lifecycle and capability work

P/S-only transport supplement `fcf5b9f4b79a34c283863707c9b34d0f2ffbcd24` adds
`gate-set-control.schema.json` and `source-inventory-upload.schema.json`. Their raw
schemas are not E IPC schemas and are not embedded here. Dependency checks for
`control.gateSets.v1` (set+perCall) and `source.attestation.v1` (set) exist, but those
two capabilities remain absent from E ready inventory in this stage.

Gate-set capability needs actual guarded P/S provider and exact schema/package pins.
Source attestation also needs E integration at the original approve-candidate-delivery
manual request (original 259200seconds and approvalRequestKey), with original P proof
and human decision, plus S/P confirmed-stop control lifecycle. PRE must not hold the
source producer across intervening normal steps; POST cannot launch E/business work.
No scanner, source accepted flag, auto-approval or fallback is introduced here.

The coordination file gives fixed commit/distribution/JAR SHA, XML/logs and receipts.
Old immutable packages and the8e5 harness retain their own source and hashes. Builds
and native/JVM runs acquire the shared heavy-build lock atomically; only owner releases.
This is a reviewable set stage, not full G40/M30 acceptance or deployment.
