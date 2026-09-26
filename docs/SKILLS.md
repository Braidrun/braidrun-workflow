# Skills

Braidrun Workflow agents can load [Agent Skills](https://agentskills.io): a
directory with a `SKILL.md` file (YAML frontmatter plus Markdown instructions)
and optional scripts, references and assets. The skill system is implemented in
this repository (`SkillLoader`, `SkillManager` and `SkillsConfiguration` in
`commons/AgentSkills.kt`, the tools in `tools/SkillTools.kt` and
`tools/SkillAdminTools.kt`). It does not use Koog's `ai.koog:skills` module.

## Decision record: keep the in-house skill system

**Status:** decided for 1.3.0 (Koog 1.3.0 upgrade). Re-checked at every Koog
minor release against the triggers below.

**Decision:** keep our skill implementation and do not add `ai.koog:skills` as a
dependency. We adopt the ideas worth having (real YAML frontmatter, pruned
discovery walk, escaped catalog) in our own code.

Why:

- `ai.koog:skills` is published only on the beta stream (1.2.0-beta and
  1.3.0-beta, with identical sources). It is about 530 lines in three files.
- It does discovery and catalog formatting only. It has no activation or skill
  body API; Koog's guidance is to give the agent a file-read tool instead.
- Its frontmatter parser is `private` and line-based (no block scalars or lists;
  nested keys overwrite top-level ones). It misreads 9 of the 21 skills shipped
  with braidrun-web, for example renaming `apple-connect` to one of its
  translations.
- Its discovery API is `suspend` and built on `FileSystemProvider`, while our
  skill managers initialize synchronously. An unguarded metadata call aborts
  discovery under our sandboxed file system, and it cannot reach classpath
  built-in skills.
- Migrating would keep about 70% of the subsystem as our own code (activation,
  allow/deny lists, hooks, ClawHub, per-run materialization) and fix none of the
  security issues that mattered, which were all in that remaining code.
- `useSkill` is our policy enforcement point: it only resolves skills that
  passed the agent's scoped allow/deny filter. A generic file-read activation
  would let a model read any reachable `SKILL.md`.

| Capability | Braidrun (1.3.0) | `ai.koog:skills` 1.3.0-beta |
| --- | --- | --- |
| Release stream | Part of this library | Beta only |
| API shape | Synchronous | `suspend`, `FileSystemProvider` |
| Discovery roots | configured, additional, project, user, cache and built-in scopes with precedence | Caller-supplied roots, first- or last-found |
| Skip dirs / cap | Pruned walk, one 2000-directory budget per pass | Pruned walk, silent global cap |
| Frontmatter | Real YAML (kaml), lenient fallback | Private line parser |
| Catalog | XML with usage preamble, escapes `&` `<` `>`, omitted when empty or without `useSkill` | XML / JSON / YAML, empty block still emitted |
| Activation | `useSkill` → `<skill_content>` with resources and attachments | None |
| Classpath built-ins | Yes | No |
| Allow/deny lists, trust gate | Yes | No |
| Hooks, per-skill MCP, ClawHub / Git install | Yes, gated by host policy | No |

### Triggers to revisit

| Trigger | Action |
| --- | --- |
| At every Koog minor release: `ai.koog:skills` is GA (not beta) **and** adds a public or pluggable YAML-correct frontmatter parser, an activation / body API, compaction protection, or agent-level integration | Run the shipped skills through it (golden test). With 0 misreads and a sync-friendly API, re-evaluate replacing `loadSkillsFromPath` and the parser. |
| A consumer needs Koog's `Skill` type or a JSON / YAML catalog | Add a projection in one internal file, `implementation` scope only. |
| The kaml golden tests stay green in both repos | Add spec fields (`license`, `compatibility`, advisory `allowedTools`) and a lenient spec validator with diagnostics. |
| Re-sending skill content on every `useSkill` shows measurable token cost | Per-conversation activation tracking and protection of `<skill_content>` from history compaction. |
| Prompt-cache work blames the system-prompt prefix | Drop `<location>` from the catalog or move the catalog. |
| Logs show hallucinated skill names | A `useSkill` tool whose parameter is an enum of loaded names. |
| A sandboxed run fails to read a skill resource | Read-only sandbox allowance for materialized skill roots. |
| One release with MCP auto-start off and no skill using `mcp-servers/` | Delete `MCPServerManager` or rebuild it on the agent MCP utilities. |
| An external consumer (skills-ref, ClawHub, Claude Code, Codex) rejects our files | Spec-clean export of the first-party guides. |
| We decide to consume Koog's discovery | Upstream PR: pluggable parser, guarded metadata calls, case-insensitive `SKILL.md`, no empty catalog block. |

## SKILL.md format

```markdown
---
name: pdf-processing
description: Extract text and tables from PDF files. Use when the user works with PDFs.
version: "1.2.0"
author: acme
tags: [pdf, documents]
attachments:
  - scripts/extract.py
metadata:
  version: "1.3.0"      # wins over the top-level version
translations:
  zh:
    name: PDF 处理      # display text only, never the skill identity
---

# PDF processing

Instructions for the model...
```

Frontmatter rules (`SkillFrontmatterParser`):

- Frontmatter is parsed as real YAML (kaml). If it is not valid YAML (typically
  an unquoted colon inside a value, or duplicate keys), the whole block falls
  back to the older lenient line parser, so skills that loaded before keep
  loading. Inline `# comments` are stripped.
- Only **top-level** `name`, `description`, `tags`, `dependencies` and
  `attachments` set the skill's identity. Nested keys such as
  `translations.<locale>.name` never override them.
- `version` and `author` are read from `metadata.version` / `metadata.author`
  first, then from the top level.
- Other `metadata.*` entries are kept: scalars as strings, lists as lists,
  nested maps as JSON text.

The file must be named `SKILL.md`; the skill directory may carry an `@version`
or `@zip` suffix (ClawHub and ZIP installs).

## Discovery

`SkillLoader.loadAllSkills()` scans, in this order:

1. `skillsPath` (scope `configured`; default `./skills`, or the ClawHub cache
   `~/.braidrun/skills-cache` / `clawhub_cache_dir` when only `skill_tools` is
   requested).
2. `additionalSkillPaths` (scope `additional`).
3. With `scanStandardPaths` (default `true`): project paths
   `<cwd>/.braidrun/skills`, `<cwd>/.agents/skills`, `<cwd>/.claude/skills`
   (scope `project`; skipped unless trusted when `requireProjectTrust` is set),
   then user paths `~/.braidrun/skills`, `~/.agents/skills`,
   `~/.claude/skills` (scope `user`).
4. Built-in skills from the classpath (`builtinSkillsResourcePath`, default
   `/builtin-skills`, listed in `builtin-skills-index.txt`) when
   `builtinSkillsEnabled` (default `true`).

On a name collision the scope precedence is project > configured > additional >
user > cache > builtin, and a warning is logged. Within one scope the first skill
in sorted path order wins, so load order is deterministic.

The walk prunes `.git`, `node_modules`, `.svn`, `.hg`, `__pycache__`,
`.gradle`, `.idea`, `.vscode`, `build`, `dist`, `target`, `.tox`,
`.mypy_cache`, `.pytest_cache` and `.eggs` without entering them, stops at depth
5, and shares one budget of 2000 directories across all roots of a discovery
pass. Hook discovery uses the same pruning and budget.

Allow and deny lists apply before a skill is loaded: `enabled`,
`skillWhitelistMode` with `enabledSkills`, `disabledSkills` and
`notLoadSkills`. `disable_skills: true` (parameter or system property) or
`skills_config.enabled: false` turns the subsystem off, and
`createSkillManager` then returns `null`.

## Catalog and activation

With `progressiveDisclosure` (default `true`) the system prompt carries a short
usage preamble and an `<available_skills>` catalog with each skill's `name`,
`description` and `location`, sorted by name. Values are XML-escaped for `&`,
`<` and `>` only. The catalog is omitted when no skill is loaded, and when the
agent's tool registry has no `useSkill` (an explicit `tool_set` without
`skill_tools`, or `disable_skills`).

`useSkill(name)` returns the full skill as `<skill_content name="...">`: version,
author, tags, the Markdown body, the skill directory for resolving relative
paths, a `<skill_resources>` list (depth 3, at most 50 files; dot-directories,
`node_modules`, `venv` and `__pycache__` are skipped) and the attachments.
It returns the full content on every call, also for a skill activated earlier.
The `SKILL_ACTIVATED` hook fires only on the first activation per manager.

Attachments are listed with their description; scripts get a run command.
Built-in (classpath) skills have no file path, so their attachments are inlined
whole on activation, each capped by `maxAttachmentSize` (default 100 KB).
Filesystem attachments are inlined only when short. Skill content is capped by
`maxSkillContentSize` (default 500 KB).

## Hooks

A skill can ship `hooks/braidrun-workflow/HOOK.md`. Its body is injected at the
hook's events; an optional handler script (`allowedScriptTypes`, default `py`,
`js`, `ts`, `kts`) runs at event time with a JSON context on stdin and a
`hookTimeoutSeconds` timeout (default 30). Workspace hooks
(`<workspaceDir>/hooks/braidrun-workflow/`) and user-global hooks
(`~/.braidrun/hooks/braidrun-workflow/`, or `userGlobalHooksDir`) are loaded
too. `hooksEnabled` turns hooks off entirely; `hookScriptExecutionEnabled`
turns off only the scripts. Under `restrictSkillSideEffects()` no hook script
runs, whatever `skills_config` says.

## Per-skill MCP servers

Each subdirectory of `<skill>/mcp-servers/` describes an MCP server the skill
can start. Auto-start is **off by default** in every host, including the CLI.
A host that wants it calls `WorkflowHostPolicy.allowSkillMcpAutoStart()` once at
startup; the opt-in has no effect under `restrictSkillSideEffects()`. Configure
MCP servers for agents through the agent's MCP settings instead (see
[MCP](MCP.md)).

## Skill tools

The `skill_tools` tool group registers two toolsets.

| Toolset | Tools | Registered |
| --- | --- | --- |
| `SkillTools` (read-only) | `useSkill`, `findLocalSkills`, `listConfiguredSkills`, `inspectSkill`, `listCachedSkills`, `searchSkills`, `getSkillDetails` | Always, with `skill_tools` |
| `SkillAdminTools` (mutating) | `downloadSkillFromClawHub`, `downloadSkillFromGit`, `clearSkillCache`, `refreshSkills` | Only while `WorkflowHostPolicy.allowsSkillAdminTools` (no `restrictSkillSideEffects()`). Every call re-checks and returns "disabled by host policy" otherwise. |

Before 1.3.0 all of these were on `SkillTools`. Kotlin code that called the
mutating methods on `SkillTools` must use `SkillAdminTools(skillTools)`.
`SkillTools.downloadSkillFromClawHub(slug, version, tag, forceDownload,
refreshManager = true)` stays as a host API (not a model tool). A host that
validates a package before loading it passes `refreshManager = false` and
refreshes its manager after accepting the result. On a cache hit the returned
`SkillReference.skillName` is the skill's declared name.

Constraints on the admin tools:

- `clearSkillCache(cacheKey)` removes exactly one cache entry (for example
  `slug@version`). An empty key, `.`, `..`, keys containing `/`, `\` or
  control characters, and targets that are not directly inside the cache
  directory are rejected. Deletion does not follow symlinks. The whole cache can
  no longer be cleared by an agent.
- `downloadSkillFromGit(repositoryUrl, subdirectory)` accepts only `https://`
  URLs with a host. It runs git without a shell:
  `git -c core.symlinks=false -c protocol.allow=never -c protocol.https.allow=always clone --depth 1 --no-recurse-submodules -- <url> <dest>`,
  with `GIT_TERMINAL_PROMPT=0`. Repository symlinks are checked out as plain
  files, submodules are not fetched, a `SKILL.md` that is a symlink is rejected,
  and `subdirectory` must stay inside the clone.

## ClawHub

`searchSkills`, `getSkillDetails` and the ClawHub download use
`clawhub_base_url` (default `https://clawhub.ai`). Downloads are cached under
`skillsPath`, else `clawhub_cache_dir` (default `~/.braidrun/skills-cache`), in
`slug@version` directories; `autoUpgrade` checks ClawHub for newer versions at
load time (default off). `autoSearchDownload` is carried in the configuration
but not used by the engine.

## Per-run materialization

With directory isolation and `materializeRuntimeSkills` (default `true`), an
execution stages the skills its agent may use under `.skills-runtime/` and
points the skill tools there. Only skills enabled by the agent's scoped
`skills_config` are staged; `.state/`, `.git` and symbolic links are never
copied, and the staged directory name includes a fingerprint of the selection.

## Hosted mode

A multi-tenant host calls `WorkflowHostPolicy.restrictSkillSideEffects()` at
startup (braidrun-web does). From then on, in the whole JVM:

- no skill hook handler script runs (static `HOOK.md` text is still injected
  when `hooksEnabled`);
- no per-skill MCP server is prepared or started, even after
  `allowSkillMcpAutoStart()`;
- `SkillAdminTools` are neither registered nor callable;
- `SkillTools.downloadSkillFromClawHub` drops the package's `hooks/` and
  `mcp-servers/`, for fresh downloads and cache hits;
- `inspectSkill` only reads directories of skills the agent's own manager
  loaded, and `listCachedSkills` only lists those.

`skills_config` flags such as `hookScriptExecutionEnabled` can only narrow this,
never widen it. See [Security](SECURITY.md#host-policy-latches).

## Configuration reference

`skills_config` (`SkillsConfiguration`) keys and defaults:

| Key | Default | Meaning |
| --- | --- | --- |
| `skillsPath` | `./skills` | Primary skills directory (scope `configured`) |
| `additionalSkillPaths` | `[]` | Extra directories (scope `additional`) |
| `scanStandardPaths` | `true` | Scan project and user standard paths |
| `requireProjectTrust` / `trustedProjects` | `false` / `[]` | Gate project-level skills |
| `enabled` | `true` | Whole skill subsystem |
| `skillWhitelistMode` / `enabledSkills` | `null` / `[]` | Allowlist mode (built-ins auto-allowed when `builtinSkillsEnabled`) |
| `disabledSkills` / `notLoadSkills` | `[]` | Deny lists (merged) |
| `progressiveDisclosure` | `true` | Catalog in the prompt, content via `useSkill` |
| `maxAttachmentSize` | `100000` | Bytes per attachment |
| `maxSkillContentSize` | `500000` | Bytes per skill |
| `autoUpgrade` | `false` | Check ClawHub for newer cached versions |
| `materializeRuntimeSkills` | `true` | Stage skills per execution |
| `builtinSkillsEnabled` / `builtinSkillsResourcePath` | `true` / `/builtin-skills` | Classpath skills |
| `hooksEnabled` | `true` | Hook subsystem |
| `hookScriptExecutionEnabled` | `true` | Hook handler scripts (host policy can still forbid) |
| `hookTimeoutSeconds` | `30` | Script timeout |
| `allowedScriptTypes` | `py, js, ts, kts` | Script extensions allowed |
| `workspaceDir` / `userGlobalHooksDir` | `null` | Extra hook roots |
