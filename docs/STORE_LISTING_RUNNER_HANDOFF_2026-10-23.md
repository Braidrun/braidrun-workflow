# Managed listing Runner supplement

Paired web branch: `flashapps2025/braidrun:codex/store-listing-review-loop` (PR #20).
Web contract source: `9e1471434ed806a8730a94fa4c42fef50554c5e2`.
Engine branch: `codex/store-listing-runner-controls`, based on web's current engine dependency tag `1.4.6-rc.20261022.1`.

This change embeds the formal listing worker, directory publication and Slack delivery supplement and updates the engine negotiation pin and runner-host JAR manifest together. Human feedback, retry and acceptance have no grant purpose. Existing per-call transport and selector behavior stay unchanged.

Schema SHA256: `67797af382d7f2e90851be2bfa3891bd53e975ace554e50ed9dedddc1493f539`.

Validation with JDK 21:

```sh
./gradlew test --tests '*RunnerCodeControlNegotiationTest' --tests '*RunnerIpcTest' --tests '*RunnerHeadlessTest' runnerHostJar installDist --console=plain
```

32 tests pass, no skipped tests. The built `runner-host.jar` embedded schema and manifest were verified against the paired web schema; the installed distribution's real `java -jar runner-host.jar --capabilities` advertises `control.perCall.v1` and `artifact.collection.v1`. Do not modify an installed JAR. Merge both repositories, publish the normal engine version, build a new Runner runtime with the web Supervisor/scripts and select that runtime. The existing old runtime is intentionally refused before listing credential delegation. The web dependency still points at its prior released tag until a normal paired engine release is chosen; do not assume a branch push deploys or upgrades the installed Runner.

No production deployment, paid model call, actual Slack delivery, App Store upload or replay of old Invitation executions was performed. Online acceptance must use a new test execution and separately record owner feedback, revision, independent review, owner confirmation, immutable downloads and the real saved Slack receipt.
