# Engine RC release receipt — 2026-10-29

The user subsequently authorized feature-branch commits/pushes, a new engine RC and runtime package builds. Earlier
"not committed/pushed" validation records describe their historical validation phase; this authorization releases
only the publication boundary. Merging main, deployment, production configuration/workflow changes and production
Runner modifications remain forbidden.

Candidate source is the recovered main `786ec888817fd45ef49df7f03142946608261989` plus readiness
`ff1b5610143b790c72418e26832bcacbbc25a94f`. The commit must retain both merge parents. Target branch:
`codex/runner-asa-release-20261029`. Intended new immutable annotated tag: `1.4.6-rc.20261029.2`.
The existing `.1` tag belongs to another commit and is neither replaced nor reused.

The current source/classes/resources match the 79-class, 600-test passing fingerprint in
`ENGINE_JUNIT_COMPLETION.json`. The verified RunnerLoopTest duplicate-declaration removal is included. The only
post-validation build change is the version label; no runtime source is changed for publication. The Gradle build
label is bumped to match the new RC so the jar's Implementation-Version is accurate. Build/POM/jar validation will
be recorded after JitPack completes; publication is not production acceptance.

Only explicit source/docs paths are committed. Private inputs, agent history, raw test logs/XML, helper classes,
recovery patches and local build outputs stay out of Git. See `PRE_RELEASE_AUDIT.json` for source hashes and scope.
