# Feedback-context E capability alignment

P/S contract source: `c0fb3353259c229589c1194de8a48ce8a532c744`,
`FEEDBACK_CONTEXT_HTTP.md`; P/S-only schema SHA-256:
`87d31e913c3e23146b4ba876aad82a363c441561ee11a58cc266bf2e29412d7f`.

Baseline E `8e5bafca736cca715bd14f46022cb91d7bcf13b4` strictly checks every
start/resume authorization.requiredCapabilities in RunnerCodeControlNegotiation.select
before environment installation, bundle admission or workflow execution. Its ready
inventory did not contain `control.feedbackContext.v1`, so the new valid P/S selection
would be rejected as RUNNER_REQUIRED_CAPABILITY_UNAVAILABLE.

The engine now advertises that capability alongside `control.perCall.v1` only when
its existing pinned CC supplement is available. A task requesting feedbackContext
must explicitly request perCall too; ready availability alone never selects a mode.
Feedback-only and feedback+legacy fail with RUNNER_FEEDBACK_CONTEXT_REQUIRES_PER_CALL;
feedback+perCall+legacy still fails with RUNNER_CONTROL_MODE_CONFLICT. Missing engine
capabilities and unknown required capabilities remain rejected.

No workflow feature is inferred from a step name or model. RunnerAdmission's static
workflow inspection is unchanged: this is a trusted start authorization requirement.
Both production RunnerHostSession and the test-only launcher use the same selector.
No new IPC frame, HTTP client, schema embedding or producer permission is added. The
new HTTP schema is P/S metadata and is deliberately not treated as an E IPC schema.
Existing CC feedback.read, current consumer scope and original source gate semantics
remain owned by P/S.

S must still require its actual guarded feedback adapter, schema/package pin and
kernel/current-consumer checks before admitting this capability. E's ready string
only establishes engine negotiation compatibility, not S implementation or full
workflow acceptance. Old 8e5 distribution and HTTP harness remain immutable; the
harness is still tied to its original 8e5 source and is not relabeled as a new package.

Validation is limited to the five RunnerCodeControlNegotiationTest cases (three
existing, two added with positive/negative combinations), fixed installDist and an
actual new JAR ready inventory check. No unrelated workflow matrix or external
feedback endpoint is exercised. No deployment or existing Runner upgrade.
