# Engine RC release receipt — 2026-10-29

The user subsequently authorized feature-branch commits/pushes, a new engine RC and runtime package builds. Earlier
"not committed/pushed" validation records describe their historical validation phase; this authorization releases
only the publication boundary. Merging main, deployment, production configuration/workflow changes and production
Runner modifications remain forbidden.

## Published source and immutable tag

- Feature branch: `codex/runner-asa-release-20261029` (normally pushed, no force push).
- Release source commit: `db1fd8adbf3c0648df31f355eb3c52273cbaf1e4`.
- Preserved merge parents: main baseline `786ec888817fd45ef49df7f03142946608261989` and readiness
  `ff1b5610143b790c72418e26832bcacbbc25a94f`.
- Annotated tag: `1.4.6-rc.20261029.2`; tag object `cc6f10299d595f71be4e9feb716b288fa629442c`.
- Remote tag peeled target matches the release commit. Remote `claude/runner-readiness` was unchanged at `ff1b561`.
- Existing `.1` belongs to `540b2663dc8e93be9d54eb3effeb3574f84b4e73`; it was neither replaced nor reused.
- Publication-receipt changes are a subsequent documentation commit on the same feature branch. The RC tag stays
  on the release source commit above; the branch tip may therefore be newer than its tagged source.

## Validation and package identity

The pre-publication source/classes/resources matched the 79-class, 600-test passing fingerprint
`bcccd9e2e8bb1edd5fab9f1be0bd1cc2a77c260e0c2293b25f306df284a29d29` in
`ENGINE_JUNIT_COMPLETION.json`. The verified RunnerLoopTest duplicate-declaration removal is included. The only
post-validation build change is the version label; no runtime source was changed for publication. The Gradle build
label was bumped to match the RC so the jar's Implementation-Version is accurate. The old fingerprint does not
include `build.gradle.kts`; it is evidence for the verified runtime/test source and compiled snapshot, not a claim
that the subsequently labelled JitPack binary ran those tests.

JitPack returned `status=ok`, `isTag=true`, `private=false`, and exactly the release source commit. Its build log
reports `publishToMavenLocal -x test` and `BUILD SUCCESSFUL in 1m 55s`. JitPack skipped tests; the local 600-test
record is the test evidence. This publication did not start a local JVM or Gradle process.

POM, main JAR and source JAR returned HTTP 200. POM coordinates and JAR Implementation-Version both match `.2`.
Both ZIP archives pass integrity checks. All 238 main Kotlin sources in the published sources JAR match the
candidate byte for byte. All 75 main resources in the published JAR match, including all 30 Runner protocol
resource files. The main JAR contains 2,353 class files.

- Main JAR SHA-256: `b0ad1d3136202ffd7f473a3b809ee46db9278801294d03bab380cbb08dbfb839`.
- Sources JAR SHA-256: `1dad09651f4ce7516b8ceeb74361621673696fe21a103a734dcc1f8278c5d83c`.
- Exact platform dependency: `com.github.Braidrun:braidrun-workflow:1.4.6-rc.20261029.2`.

[Build status](https://jitpack.io/api/builds/com.github.Braidrun/braidrun-workflow/1.4.6-rc.20261029.2),
[POM](https://jitpack.io/com/github/Braidrun/braidrun-workflow/1.4.6-rc.20261029.2/braidrun-workflow-1.4.6-rc.20261029.2.pom),
[main JAR](https://jitpack.io/com/github/Braidrun/braidrun-workflow/1.4.6-rc.20261029.2/braidrun-workflow-1.4.6-rc.20261029.2.jar),
[build log](https://jitpack.io/com/github/Braidrun/braidrun-workflow/1.4.6-rc.20261029.2/build.log).

## Scope and remaining boundaries

Only explicit source/docs paths were committed. Private inputs, agent history, raw test logs/XML, helper classes,
recovery patches and local build outputs stay out of Git. See `PRE_RELEASE_AUDIT.json` for the source scope,
`JITPACK_RELEASE.json` for artifact hashes/status, and `JITPACK_SOURCE_VERIFICATION.json` for file-level hashes.

The engine tag and Maven artifacts are ready for platform pinning and runtime package builds. No platform files
were changed by this release task. This does not establish four-platform runtime package acceptance, production
Runner installation, workflow execution, artifact ACK/cleanup or production ASA acceptance; those remain separate
release gates. Nothing was merged into main or deployed and no production configuration/workflow was changed.
