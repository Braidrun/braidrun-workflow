# Managed listing Runner supplement

Paired web branch: `flashapps2025/braidrun:codex/store-listing-review-loop` (PR #20).
Web contract source: `06a461311e96721e84ecfbbce604df26b3140907`.
Engine branch: `codex/store-listing-runner-controls`, based on web's current engine dependency tag `1.4.6-rc.20261022.1`.

This change embeds the formal listing worker, directory publication and Slack delivery supplement and updates the engine negotiation pin and runner-host JAR manifest together. Human feedback, retry and acceptance have no grant purpose. Existing per-call transport and selector behavior stay unchanged.

The 0.1.40 platform template also supports private reference images attached to owner feedback. `listing.read` accepts either the original snapshot fields or the exact `referenceId`, `feedbackId`, `offset` variant. Image reads are restricted to the active claim node and bound feedback; no upload/delete or human decision authority is added. The platform and Python Supervisor must be updated with this engine.

Schema SHA256: `e65c475deadf310d641c9aac93f94672150d13072a1a37bba504fb716f2d0324`.

Earlier listing supplement validation with JDK 21:

```sh
./gradlew test --tests '*RunnerCodeControlNegotiationTest' --tests '*RunnerIpcTest' --tests '*RunnerHeadlessTest' runnerHostJar installDist --console=plain
```

32 tests pass, no skipped tests. The built `runner-host.jar` embedded schema and manifest were verified against the paired web schema; the installed distribution's real `java -jar runner-host.jar --capabilities` advertises `control.perCall.v1` and `artifact.collection.v1`. Do not modify an installed JAR. Merge both repositories, publish the normal engine version, build a new Runner runtime with the web Supervisor/scripts and select that runtime. The existing old runtime is intentionally refused before listing credential delegation. The web dependency still points at its prior released tag until a normal paired engine release is chosen; do not assume a branch push deploys or upgrades the installed Runner.

Reference-image increment, after pinning the web source commit above:

```sh
./gradlew test --tests '*RunnerCodeControlNegotiationTest' --tests '*RunnerIpcTest' --tests '*RunnerHeadlessTest' --tests '*RunnerPortableCapabilitiesTest' runnerHostJar installDist --console=plain
```

All 46 tests passed without skips (negotiation 11, IPC 7, headless 15, portable capabilities 13). `runnerHostJar` and `installDist` built successfully. The actual JAR's embedded schema, manifest hash, and contract origin were independently verified against the paired web source. A paired release and installed-runtime update are still required.

No production deployment, paid model call, actual Slack delivery, App Store upload or replay of old Invitation executions was performed. Online acceptance must use a new test execution and separately record reference upload, owner feedback, revision, per-image independent review, owner confirmation, immutable downloads without private originals, and the real saved Slack receipt.
