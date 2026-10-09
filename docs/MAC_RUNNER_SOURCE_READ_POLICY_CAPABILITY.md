# Historical source-read policy: E negotiation alignment

Formal P/S source: `28b9fcc38590201374e11e78794cf050d91f159d`,
`SOURCE_READ_POLICY.md`. Raw `source-read-policy.schema.json` SHA-256:
`088bcb74eb685ac3988168084b414438dc762fe3ab605f1d938609867c725f16`.
The schema is P/S-owned and is not embedded as an E IPC schema.

Baseline E `399ce946fe6d7851bf2efc55b27b519af2691dfd` rejects this new required
capability as unavailable. The shared start/resume selector in RunnerHostSession
runs before environment installation, frozen-bundle admission and workflow execution.
It now accepts explicitly required `control.sourceReadPolicy.v1` only together with
explicit `control.perCall.v1`, with both capabilities present in the engine inventory.
The ready inventory advertises sourceReadPolicy only when the exact existing CC
supplement is available. Inventory presence alone never selects perCall.

Missing explicit perCall (including sourceReadPolicy+legacy) fails with
RUNNER_SOURCE_READ_POLICY_REQUIRES_PER_CALL. Selecting perCall and legacy together
still fails with RUNNER_CONTROL_MODE_CONFLICT. Missing available capabilities and
unknown required capabilities remain rejected. Source-read and feedback-context
may both be explicitly selected with perCall; neither adds permission to the other.

No new E IPC frame, embedded HTTP policy schema, source reader or static-workflow
capability inference is added. P/S own the authenticated runtime carrier, exact
consumer/child edge, schema/package pin and guarded source.read helper. Existing
runtime-role bundle verification is unchanged. The consumer start/process authority
must remain distinct from the historical source tuple; E's negotiation cannot
create source attestation, human acceptance or authority for an arbitrary artifact.

Tests: seven RunnerCodeControlNegotiationTest cases, including two new cases for
positive composition, missing dependencies/capabilities, legacy conflict, unknown
capability and default-mode preservation. Fixed installDist, actual new JAR ready
inventory, unchanged five engine schema pins, manifest, raw XML and hashes are
recorded in the new batch receipt. No unrelated full workflow matrix is rerun.

S must supply and verify the real guarded adapter before a matching runtime is
accepted. Real P source stream to S SEALED staging and the next E operation remain
separate acceptance work. Old399/b7b0/8e5 packages and8e5 harness retain their original
source and hashes; this batch does not relabel them. No deployment or Runner upgrade.
