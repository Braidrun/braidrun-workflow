# E original approval and terminal lifecycle preparation

This scoped change continues E181 under d349 source-attestation, fcf5 transport and
cb53 set/physical-exit semantics. Raw schema bytes and all their original pins remain.
No new E IPC frame, source scanner, source-proof accepted flag or account bearer is added.

## Original approval boundary and budget

The interpreter reaches `approve-candidate-delivery` through its original normal
manual_approval path. Earlier source producer/set collection finishes normally; normal
intermediate steps remain executable. The approval step's Code/Agent body cannot run
while that original manual request awaits the remote decision. Approval time is still
excluded from the ordinary workflow active budget, including the proof wait.

Original requestedAt is now included with pendingApprovalIds in host savepoints and
resume state. Its absolute requestedAt+original timeout bounds both transport waiting
and fresh environment confirmation. A resumed original request cannot acquire a new
259200-second budget. Format-1 older savepoints already retained the original request
in pendingApprovals; restore recovers that exact timestamp, or requires reconciliation
if it is absent/conflicting. It does not manufacture a time or change the key.

The host persists the original normal approval frame, full binding, actual child,
idempotencyKey/occurrence, original material/comment context and timeout before dispatch.
Reconstruction within the same binding reuses the exact original frameId/payload;
changed context/material/requestedAt refuses. Exact retransmission of the original
inbound human decision can satisfy a re-registered original waiter. It still must
match the original key/occurrence and pass fresh environment plus live authority.
A retained decision is evidence, not an automatic local approval fallback. The remote
original decision must be received again and is retained unchanged before continuation.
New fenced bindings use their normal explicit resume authorization; no old-bound frame
is replayed into a new epoch or used as permission to rerun an uncertain business step.

## E terminal fence and POST_STOP ownership

After the normal Code/Agent interpreter result, E closes its business side before
writing/publishing result: approval waits, collection jobs, interactive channels,
credential scopes and HTTP resources close; the shared event sequence is sealed.
Late business operations/events cannot create a new producer, publication or approval.
Normal bounded event ACKs and the original result remain usable. The original status
and failure/cancel reason are retained; no successful source verdict is inferred.

A private finalization record binds status/finalEventSeq and
E_BUSINESS_CLOSED_JVM_EXIT_PENDING. The public result continues to carry the existing
unconfirmed JVM_EXIT_PENDING stop receipt. E drains its local pipe and exits, allowing
S to independently confirm the actual JVM and registered business registry exit.
Closing E resources is not OS process-stop evidence. E neither waits for POST_STOP
before its own exit nor launches a scan/control process, lease, credential or business
operation during finalization. S/P retain the pinned runtime/directory and own their
separate challenge-bound POST_STOP scanner, inventory upload and final source authority.

## Verification and unresolved P/S dependency

The final directed batch contains 20 cases across four focused classes; it does not rerun the closed E18144-case matrix. Tests cover same-binding durable approval frame/decision retransmission, changed context,
original remaining deadline (including post-decision freshness), older checkpoint time
recovery and absent/conflicting timestamps, original normal source/intermediate/manual
approval steps, controlled proof unavailable, approval/rejection/cancellation/late reply,
expired259200 resume with earlier completed steps retained, and terminal event/resource
fences. Human/P proof services and previous-resume projections are controlled fixtures;
the normal source/intermediate/delivery Code bodies execute actual native Bash. This
does not establish real source attestation, a human decision or full M30 acceptance.

The existing base approvalDecision contains no scan/set-proof fields. E has recorded
these specific P questions in engine coordination: confirm that P/S forward the normal
original decision only after PRE proof/owner authorization; that idempotencyKey is the
source challenge's original approvalRequestKey; and that unconfirmed E result/exit is
the intended handoff to S/P confirmed-stop control state without a new E source RPC.

`control.gateSets.v1` and `source.attestation.v1` remain withheld from E ready inventory.
Their strict dependency guards from E181 remain. S's actual gate-set provider/pins and
P's source ApprovalBridge/final-authority plumbing were not complete when this batch
started. Publishing d349/fcf5 schemas or testing the existing E hold is not provider
assembly. E will advertise only after the original hook and agreed guarded P/S wiring
are assembled. All source/scan HTTP schemas stay P/S-owned, not E embedded schemas.

No full E18144-case matrix rerun, deployment, feature enablement, existing Runner
upgrade, production migration, real account, Docker/VM or network/language change.
