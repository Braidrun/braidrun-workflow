# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial extraction of the workflow/agent library from the Braidrun
  platform's internal `braidrun-agent` module:
  - declarative workflow engine (YAML parsing, validation, execution,
    checkpoints/resume, batch execution, monitoring, debugging)
  - preset-based agent runtime on top of Koog with multi-provider LLM support
  - built-in tool library (~40 tool groups) and MCP client/server integration
  - filesystem- and classpath-based skill loading (`SKILL.md` convention)
- Open-source project scaffolding: license, contribution guide, CI workflow,
  changelog, security policy.

### Changed (relative to the internal module)

- Renamed the legacy internal branding throughout the codebase:
  - hook API types `Dingyue*` → `Braidrun*` (e.g. `DingyueHookExecutor` →
    `BraidrunHookExecutor`, `DingyueHookContext` → `BraidrunHookContext`)
  - environment variables `DING_*` → `BRAIDRUN_*` (e.g.
    `DING_SKIP_SSL_VERIFICATION` → `BRAIDRUN_SKIP_SSL_VERIFICATION`,
    `DING_HTTP_*` timeouts → `BRAIDRUN_HTTP_*`)
  - Docker audit labels `dingyue.*` → `braidrun.*`
- Named Mongo environments reduced to `braidrun` / `test`; default database
  names no longer reference internal deployments.

### Removed (relative to the internal module)

- Business-specific skills, workflow templates, internal documents and
  presentation material that are not part of the library.
- Web-platform documentation (frontend debug panel, platform REST API,
  module-library feature docs) — this repository documents the library only.
- Broken/internal developer scripts that depended on the original monorepo
  layout.
