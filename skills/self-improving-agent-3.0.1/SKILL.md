---
name: self-improvement
description: "Captures learnings, errors, and corrections to enable continuous improvement. Use when: (1) A command or operation fails unexpectedly, (2) User corrects Claude ('No, that's wrong...', 'Actually...'), (3) User requests a capability that doesn't exist, (4) An external API or tool fails, (5) Claude realizes its knowledge is outdated or incorrect, (6) A better approach is discovered for a recurring task. Also review learnings before major tasks."
metadata:
---

# Self-Improvement Skill

Log learnings and errors to markdown files for continuous improvement. Coding agents can later process these into fixes,
and important learnings get promoted to project memory.

## Quick Reference

| Situation                          | Action                                                                                             |
|------------------------------------|----------------------------------------------------------------------------------------------------|
| Command/operation fails            | Log to `.learnings/ERRORS.md`                                                                      |
| User corrects you                  | Log to `.learnings/LEARNINGS.md` with category `correction`                                        |
| User wants missing feature         | Log to `.learnings/FEATURE_REQUESTS.md`                                                            |
| API/external tool fails            | Log to `.learnings/ERRORS.md` with integration details                                             |
| Knowledge was outdated             | Log to `.learnings/LEARNINGS.md` with category `knowledge_gap`                                     |
| Found better approach              | Log to `.learnings/LEARNINGS.md` with category `best_practice`                                     |
| Simplify/Harden recurring patterns | Log/update `.learnings/LEARNINGS.md` with `Source: simplify-and-harden` and a stable `Pattern-Key` |
| Similar to existing entry          | Link with `**See Also**`, consider priority bump                                                   |
| Broadly applicable learning        | Promote to `CLAUDE.md`, `AGENTS.md`, and/or `.github/copilot-instructions.md`                      |
| Workflow improvements              | Promote to `AGENTS.md` (OpenClaw workspace)                                                        |
| Tool gotchas                       | Promote to `TOOLS.md` (OpenClaw workspace)                                                         |
| Behavioral patterns                | Promote to `SOUL.md` (OpenClaw workspace)                                                          |

## OpenClaw Setup (Recommended)

OpenClaw is the primary platform for this skill. It uses workspace-based prompt injection with automatic skill loading.

### Installation

**Via ClawdHub (recommended):**

```bash
clawdhub install self-improving-agent
```

**Manual:**

```bash
git clone https://github.com/peterskoett/self-improving-agent.git ~/.openclaw/skills/self-improving-agent
```

Remade for openclaw from original
repo : https://github.com/pskoett/pskoett-ai-skills - https://github.com/pskoett/pskoett-ai-skills/tree/main/skills/self-improvement

### Workspace Structure

OpenClaw injects these files into every session:

```
~/.openclaw/workspace/
├── AGENTS.md          # Multi-agent workflows, delegation patterns
├── SOUL.md            # Behavioral guidelines, personality, principles
├── TOOLS.md           # Tool capabilities, integration gotchas
├── MEMORY.md          # Long-term memory (main session only)
├── memory/            # Daily memory files
│   └── YYYY-MM-DD.md
└── .learnings/        # This skill's log files
    ├── LEARNINGS.md
    ├── ERRORS.md
    └── FEATURE_REQUESTS.md
```

### Create Learning Files

```bash
mkdir -p ~/.openclaw/workspace/.learnings
```

Then create the log files (or copy from `assets/`):

- `LEARNINGS.md` — corrections, knowledge gaps, best practices
- `ERRORS.md` — command failures, exceptions
- `FEATURE_REQUESTS.md` — user-requested capabilities

### Promotion Targets

When learnings prove broadly applicable, promote them to workspace files:

| Learning Type         | Promote To  | Example                                |
|-----------------------|-------------|----------------------------------------|
| Behavioral patterns   | `SOUL.md`   | "Be concise, avoid disclaimers"        |
| Workflow improvements | `AGENTS.md` | "Spawn sub-agents for long tasks"      |
| Tool gotchas          | `TOOLS.md`  | "Git push needs auth configured first" |

### Inter-Session Communication

OpenClaw provides tools to share learnings across sessions:

- **sessions_list** — View active/recent sessions
- **sessions_history** — Read another session's transcript
- **sessions_send** — Send a learning to another session
- **sessions_spawn** — Spawn a sub-agent for background work

### Optional: Enable Hook

For automatic reminders at session start:

```bash
# Copy hook to OpenClaw hooks directory
cp -r hooks/openclaw ~/.openclaw/hooks/self-improvement

# Enable it
openclaw hooks enable self-improvement
```

See `references/openclaw-integration.md` for complete details.

---

## braidrun-agent Setup

braidrun-agent is a Kotlin-based AI agent framework that supports skill loading and hook injection via `SkillManager`.

### Installation

Place the skill in your braidrun-agent skills directory (configured via `skills_config.skillsPath`, default:
`~/.braidrun/skills-cache`):

```bash
mkdir -p ~/.braidrun/skills-cache
cp -r skills/self-improving-agent-3.0.1 ~/.braidrun/skills-cache/
```

Or configure `skills_config` in your agent's `config.yaml`:

```yaml
skills_config:
  skillsPath: "./skills"
  enabled: true
```

### How the Hook Works

The hook at `hooks/braidrun-agent/HOOK.md` fires on **10 events** to provide comprehensive self-improvement
capabilities — no extra configuration is needed beyond having the skill loaded.

At bootstrap, the hook injects a self-improvement reminder as a virtual file. During the session, it automatically
captures tool failures, node/subgraph errors, LLM streaming failures, and agent errors to `.learnings/ERRORS.md`. At
session boundaries it reviews pending learnings and reminds the agent to capture new ones.

braidrun-agent's `SkillManager` scans each skill's `hooks/braidrun-agent/` directory at startup. It first looks for an
executable handler script (`handler.py`, `handler.js`, or `handler.kts`), then falls back to the static `HOOK.md` body.

#### Handler Script (Code Execution — Recommended)

This skill ships a `handler.py` alongside `HOOK.md`. When Python 3 is available the script is executed at event time:

```
hooks/braidrun-agent/
├── HOOK.md        ← metadata (name, events, requires …)
└── handler.py     ← executed at runtime; output takes precedence over HOOK.md body
```

**Script protocol** (identical for `.py`, `.js`, `.kts`):

| Direction  | Format      | Description                                  |
|------------|-------------|----------------------------------------------|
| **stdin**  | JSON object | `BraidrunHookContext` – see fields below      |
| **stdout** | JSON object | `BraidrunHookScriptResult` – see fields below |

**Input context fields (stdin JSON):**

| Field          | Type                      | Description                                                                                              |
|----------------|---------------------------|----------------------------------------------------------------------------------------------------------|
| `event`        | string                    | Event type, e.g. `"agent:bootstrap"`                                                                     |
| `sessionKey`   | string                    | Session identifier; empty when not available                                                             |
| `workspaceDir` | string                    | Absolute path to the current workspace                                                                   |
| `skillName`    | string                    | Name of the skill that owns this hook                                                                    |
| `config`       | `{[key: string]: string}` | Agent configuration snapshot: `model`, `agent_name`, `workspace_dir`, `session_key`, `clawhub_cache_dir` |

**Output fields:**

| Field           | Type                | Description                                                                        |
|-----------------|---------------------|------------------------------------------------------------------------------------|
| `injectContent` | string \| null      | Plain text appended to the system prompt                                           |
| `virtualFiles`  | `[{path, content}]` | Virtual files injected as bootstrap files (mirrors OpenClaw `bootstrapFiles.push`) |
| `messages`      | `[string]`          | Messages printed to the operator console (not injected into prompt)                |

Minimal Python handler template:

```python
import json, sys

ctx = json.load(sys.stdin)          # BraidrunHookContext
cfg = ctx.get("config", {})

# Skip sub-agent sessions
if ":subagent:" in ctx.get("sessionKey", ""):
    print(json.dumps({})); sys.exit(0)

model = cfg.get("model", "")
print(json.dumps({
    "injectContent": None,
    "virtualFiles": [{"path": "MY_FILE.md", "content": "# My content"}],
    "messages": [f"Hook active" + (f" (model: {model})" if model else "")],
}))
```

Minimal Node.js handler template (`handler.js`):

```javascript
const chunks = [];
process.stdin.on('data', d => chunks.push(d));
process.stdin.on('end', () => {
    const ctx = JSON.parse(Buffer.concat(chunks).toString());
    if ((ctx.sessionKey || '').includes(':subagent:')) {
        process.stdout.write('{}'); return;
    }
    process.stdout.write(JSON.stringify({
        virtualFiles: [{ path: 'MY_FILE.md', content: '# My content' }],
        messages: [],
    }));
});
```

The handler receives the full event context, can perform conditional logic (e.g., skip sub-agents, check workspace
files), call external APIs, and return different content per session — capabilities not possible with static HOOK.md
content alone.

#### Static HOOK.md Fallback

When **no handler script** is present (or Python/Node is not installed), `SkillManager` falls back to the static
`HOOK.md` body. This skill's HOOK.md declares:

```json
{"braidrun-agent":{"events":["agent:bootstrap"],"virtualFilePath":"SELF_IMPROVEMENT_REMINDER.md"}}
```

Because `virtualFilePath` is set, the content is injected as a **virtual bootstrap file**. At session start the agent
sees:

```
## Bootstrap Files

The following files were automatically loaded at session start:

[File: SELF_IMPROVEMENT_REMINDER.md]
## Self-Improvement Reminder
...
```

This mirrors OpenClaw's `bootstrapFiles.push({path, content, virtual:true})` behaviour.

#### Script Execution Priority

| Hook state                                              | Behaviour                                     |
|---------------------------------------------------------|-----------------------------------------------|
| `handler.py` / `.js` / `.kts` found & runtime available | Script is executed; its output is used        |
| Script present but runtime missing / exits non-zero     | Script is skipped; falls back to HOOK.md body |
| No script, `virtualFilePath` set                        | Body injected as virtual file (static)        |
| No script, no `virtualFilePath`                         | Body injected as inline system-prompt text    |

### Hook Eligibility

Hooks support an optional `requires` object and an `always` flag in their metadata to control eligibility:

```json
{"braidrun-agent":{"events":["agent:bootstrap"],"virtualFilePath":"REMINDER.md","requires":{"bins":["git"],"os":["darwin","linux"],"env":["MY_VAR"],"config":["/path/to/config.yaml"]}}}
```

| Field     | Meaning                                                                     |
|-----------|-----------------------------------------------------------------------------|
| `bins`    | All listed binaries must exist on PATH                                      |
| `anyBins` | At least one binary must exist on PATH                                      |
| `env`     | All listed environment variables must be set                                |
| `os`      | Current OS must match one of the values (e.g. `darwin`, `linux`, `windows`) |
| `config`  | All listed file/directory paths must exist on the filesystem                |

Set `always: true` in the YAML frontmatter to bypass all eligibility checks:

```yaml
---
name: my-hook
always: true
metadata: {"braidrun-agent":{"events":["agent:bootstrap"]}}
---
```

### Workspace-Level Hooks

braidrun-agent loads hooks from three directories in priority order (lowest → highest):

| Tier          | Directory                                     | Priority |
|---------------|-----------------------------------------------|----------|
| Skill-bundled | `<skills-cache>/<skill>/hooks/braidrun-agent/` | Lowest   |
| User-global   | `~/.braidrun/hooks/braidrun-agent/`             | Medium   |
| Workspace     | `<workspaceDir>/hooks/braidrun-agent/`         | Highest  |

A later tier overrides a skill-bundled hook with the same `name`.

**User-global hooks** at `~/.braidrun/hooks/braidrun-agent/` apply to every agent on your machine — useful for personal
reminders or cross-project tools.

**Workspace hooks** are project-local. Configure the workspace directory via:

```yaml
workspace_dir: "/path/to/your/project"
```

Both tiers support the same directory layouts:

- A single `HOOK.md` directly inside the directory, or
- Multiple subdirectories each containing their own `HOOK.md` (and optional `handler.py`).

### Workspace Structure

Create learning files in your project:

```bash
mkdir -p .learnings
```

Then create the log files (or copy from `assets/`):

- `LEARNINGS.md` — corrections, knowledge gaps, best practices
- `ERRORS.md` — command failures, exceptions
- `FEATURE_REQUESTS.md` — user-requested capabilities

### Promotion Targets

When learnings prove broadly applicable, promote them to:

| Learning Type             | Promote To  |
|---------------------------|-------------|
| Project facts/conventions | `CLAUDE.md` |
| Workflow improvements     | `AGENTS.md` |
| Tool gotchas              | `TOOLS.md`  |

---

## Generic Setup (Other Agents)

For Claude Code, Codex, Copilot, or other agents, create `.learnings/` in your project:

```bash
mkdir -p .learnings
```

Copy templates from `assets/` or create files with headers.

### Add reference to agent files AGENTS.md, CLAUDE.md, or .github/copilot-instructions.md to remind yourself to log learnings. (this is an alternative to hook-based reminders)

#### Self-Improvement Workflow

When errors or corrections occur:

1. Log to `.learnings/ERRORS.md`, `LEARNINGS.md`, or `FEATURE_REQUESTS.md`
2. Review and promote broadly applicable learnings to:
    - `CLAUDE.md` - project facts and conventions
    - `AGENTS.md` - workflows and automation
    - `.github/copilot-instructions.md` - Copilot context

## Logging Format

### Learning Entry

Append to `.learnings/LEARNINGS.md`:

```markdown
## [LRN-YYYYMMDD-XXX] category

**Logged**: ISO-8601 timestamp
**Priority**: low | medium | high | critical
**Status**: pending
**Area**: frontend | backend | infra | tests | docs | config

### Summary
One-line description of what was learned

### Details
Full context: what happened, what was wrong, what's correct

### Suggested Action
Specific fix or improvement to make

### Metadata
- Source: conversation | error | user_feedback
- Related Files: path/to/file.ext
- Tags: tag1, tag2
- See Also: LRN-20250110-001 (if related to existing entry)
- Pattern-Key: simplify.dead_code | harden.input_validation (optional, for recurring-pattern tracking)
- Recurrence-Count: 1 (optional)
- First-Seen: 2025-01-15 (optional)
- Last-Seen: 2025-01-15 (optional)

---
```

### Error Entry

Append to `.learnings/ERRORS.md`:

```markdown
## [ERR-YYYYMMDD-XXX] skill_or_command_name

**Logged**: ISO-8601 timestamp
**Priority**: high
**Status**: pending
**Area**: frontend | backend | infra | tests | docs | config

### Summary
Brief description of what failed

### Error
```

Actual error message or output

```

### Context
- Command/operation attempted
- Input or parameters used
- Environment details if relevant

### Suggested Fix
If identifiable, what might resolve this

### Metadata
- Reproducible: yes | no | unknown
- Related Files: path/to/file.ext
- See Also: ERR-20250110-001 (if recurring)

---
```

### Feature Request Entry

Append to `.learnings/FEATURE_REQUESTS.md`:

```markdown
## [FEAT-YYYYMMDD-XXX] capability_name

**Logged**: ISO-8601 timestamp
**Priority**: medium
**Status**: pending
**Area**: frontend | backend | infra | tests | docs | config

### Requested Capability
What the user wanted to do

### User Context
Why they needed it, what problem they're solving

### Complexity Estimate
simple | medium | complex

### Suggested Implementation
How this could be built, what it might extend

### Metadata
- Frequency: first_time | recurring
- Related Features: existing_feature_name

---
```

## ID Generation

Format: `TYPE-YYYYMMDD-XXX`

- TYPE: `LRN` (learning), `ERR` (error), `FEAT` (feature)
- YYYYMMDD: Current date
- XXX: Sequential number or random 3 chars (e.g., `001`, `A7B`)

Examples: `LRN-20250115-001`, `ERR-20250115-A3F`, `FEAT-20250115-002`

## Resolving Entries

When an issue is fixed, update the entry:

1. Change `**Status**: pending` → `**Status**: resolved`
2. Add resolution block after Metadata:

```markdown
### Resolution
- **Resolved**: 2025-01-16T09:00:00Z
- **Commit/PR**: abc123 or #42
- **Notes**: Brief description of what was done
```

Other status values:

- `in_progress` - Actively being worked on
- `wont_fix` - Decided not to address (add reason in Resolution notes)
- `promoted` - Elevated to CLAUDE.md, AGENTS.md, or .github/copilot-instructions.md

## Promoting to Project Memory

When a learning is broadly applicable (not a one-off fix), promote it to permanent project memory.

### When to Promote

- Learning applies across multiple files/features
- Knowledge any contributor (human or AI) should know
- Prevents recurring mistakes
- Documents project-specific conventions

### Promotion Targets

| Target                            | What Belongs There                                                          |
|-----------------------------------|-----------------------------------------------------------------------------|
| `CLAUDE.md`                       | Project facts, conventions, gotchas for all Claude interactions             |
| `AGENTS.md`                       | Agent-specific workflows, tool usage patterns, automation rules             |
| `.github/copilot-instructions.md` | Project context and conventions for GitHub Copilot                          |
| `SOUL.md`                         | Behavioral guidelines, communication style, principles (OpenClaw workspace) |
| `TOOLS.md`                        | Tool capabilities, usage patterns, integration gotchas (OpenClaw workspace) |

### How to Promote

1. **Distill** the learning into a concise rule or fact
2. **Add** to appropriate section in target file (create file if needed)
3. **Update** original entry:
    - Change `**Status**: pending` → `**Status**: promoted`
    - Add `**Promoted**: CLAUDE.md`, `AGENTS.md`, or `.github/copilot-instructions.md`

### Promotion Examples

**Learning** (verbose):
> Project uses pnpm workspaces. Attempted `npm install` but failed.
> Lock file is `pnpm-lock.yaml`. Must use `pnpm install`.

**In CLAUDE.md** (concise):

```markdown
## Build & Dependencies
- Package manager: pnpm (not npm) - use `pnpm install`
```

**Learning** (verbose):
> When modifying API endpoints, must regenerate TypeScript client.
> Forgetting this causes type mismatches at runtime.

**In AGENTS.md** (actionable):

```markdown
## After API Changes
1. Regenerate client: `pnpm run generate:api`
2. Check for type errors: `pnpm tsc --noEmit`
```

## Recurring Pattern Detection

If logging something similar to an existing entry:

1. **Search first**: `grep -r "keyword" .learnings/`
2. **Link entries**: Add `**See Also**: ERR-20250110-001` in Metadata
3. **Bump priority** if issue keeps recurring
4. **Consider systemic fix**: Recurring issues often indicate:
    - Missing documentation (→ promote to CLAUDE.md or .github/copilot-instructions.md)
    - Missing automation (→ add to AGENTS.md)
    - Architectural problem (→ create tech debt ticket)

## Simplify & Harden Feed

Use this workflow to ingest recurring patterns from the `simplify-and-harden`
skill and turn them into durable prompt guidance.

### Ingestion Workflow

1. Read `simplify_and_harden.learning_loop.candidates` from the task summary.
2. For each candidate, use `pattern_key` as the stable dedupe key.
3. Search `.learnings/LEARNINGS.md` for an existing entry with that key:
    - `grep -n "Pattern-Key: <pattern_key>" .learnings/LEARNINGS.md`
4. If found:
    - Increment `Recurrence-Count`
    - Update `Last-Seen`
    - Add `See Also` links to related entries/tasks
5. If not found:
    - Create a new `LRN-...` entry
    - Set `Source: simplify-and-harden`
    - Set `Pattern-Key`, `Recurrence-Count: 1`, and `First-Seen`/`Last-Seen`

### Promotion Rule (System Prompt Feedback)

Promote recurring patterns into agent context/system prompt files when all are true:

- `Recurrence-Count >= 3`
- Seen across at least 2 distinct tasks
- Occurred within a 30-day window

Promotion targets:

- `CLAUDE.md`
- `AGENTS.md`
- `.github/copilot-instructions.md`
- `SOUL.md` / `TOOLS.md` for OpenClaw workspace-level guidance when applicable

Write promoted rules as short prevention rules (what to do before/while coding),
not long incident write-ups.

## Periodic Review

Review `.learnings/` at natural breakpoints:

### When to Review

- Before starting a new major task
- After completing a feature
- When working in an area with past learnings
- Weekly during active development

### Quick Status Check

```bash
# Count pending items
grep -h "Status\*\*: pending" .learnings/*.md | wc -l

# List pending high-priority items
grep -B5 "Priority\*\*: high" .learnings/*.md | grep "^## \["

# Find learnings for a specific area
grep -l "Area\*\*: backend" .learnings/*.md
```

### Review Actions

- Resolve fixed items
- Promote applicable learnings
- Link related entries
- Escalate recurring issues

## Detection Triggers

Automatically log when you notice:

**Corrections** (→ learning with `correction` category):

- "No, that's not right..."
- "Actually, it should be..."
- "You're wrong about..."
- "That's outdated..."

**Feature Requests** (→ feature request):

- "Can you also..."
- "I wish you could..."
- "Is there a way to..."
- "Why can't you..."

**Knowledge Gaps** (→ learning with `knowledge_gap` category):

- User provides information you didn't know
- Documentation you referenced is outdated
- API behavior differs from your understanding

**Errors** (→ error entry):

- Command returns non-zero exit code
- Exception or stack trace
- Unexpected output or behavior
- Timeout or connection failure

## Priority Guidelines

| Priority   | When to Use                                                   |
|------------|---------------------------------------------------------------|
| `critical` | Blocks core functionality, data loss risk, security issue     |
| `high`     | Significant impact, affects common workflows, recurring issue |
| `medium`   | Moderate impact, workaround exists                            |
| `low`      | Minor inconvenience, edge case, nice-to-have                  |

## Area Tags

Use to filter learnings by codebase region:

| Area       | Scope                                      |
|------------|--------------------------------------------|
| `frontend` | UI, components, client-side code           |
| `backend`  | API, services, server-side code            |
| `infra`    | CI/CD, deployment, Docker, cloud           |
| `tests`    | Test files, testing utilities, coverage    |
| `docs`     | Documentation, comments, READMEs           |
| `config`   | Configuration files, environment, settings |

## Best Practices

1. **Log immediately** - context is freshest right after the issue
2. **Be specific** - future agents need to understand quickly
3. **Include reproduction steps** - especially for errors
4. **Link related files** - makes fixes easier
5. **Suggest concrete fixes** - not just "investigate"
6. **Use consistent categories** - enables filtering
7. **Promote aggressively** - if in doubt, add to CLAUDE.md or .github/copilot-instructions.md
8. **Review regularly** - stale learnings lose value

## Gitignore Options

**Keep learnings local** (per-developer):

```gitignore
.learnings/
```

**Track learnings in repo** (team-wide):
Don't add to .gitignore - learnings become shared knowledge.

**Hybrid** (track templates, ignore entries):

```gitignore
.learnings/*.md
!.learnings/.gitkeep
```

## Hook Integration

Enable automatic reminders through agent hooks. This is **opt-in** - you must explicitly configure hooks.

### Quick Setup (Claude Code / Codex)

Create `.claude/settings.json` in your project:

```json
{
  "hooks": {
    "UserPromptSubmit": [{
      "matcher": "",
      "hooks": [{
        "type": "command",
        "command": "./skills/self-improvement/scripts/activator.sh"
      }]
    }]
  }
}
```

This injects a learning evaluation reminder after each prompt (~50-100 tokens overhead).

### Full Setup (With Error Detection)

```json
{
  "hooks": {
    "UserPromptSubmit": [{
      "matcher": "",
      "hooks": [{
        "type": "command",
        "command": "./skills/self-improvement/scripts/activator.sh"
      }]
    }],
    "PostToolUse": [{
      "matcher": "Bash",
      "hooks": [{
        "type": "command",
        "command": "./skills/self-improvement/scripts/error-detector.sh"
      }]
    }]
  }
}
```

### Available Hook Scripts

| Script                      | Hook Type          | Purpose                                   |
|-----------------------------|--------------------|-------------------------------------------|
| `scripts/activator.sh`      | UserPromptSubmit   | Reminds to evaluate learnings after tasks |
| `scripts/error-detector.sh` | PostToolUse (Bash) | Triggers on command errors                |

See `references/hooks-setup.md` for detailed configuration and troubleshooting.

## Automatic Skill Extraction

When a learning is valuable enough to become a reusable skill, extract it using the provided helper.

### Skill Extraction Criteria

A learning qualifies for skill extraction when ANY of these apply:

| Criterion              | Description                                         |
|------------------------|-----------------------------------------------------|
| **Recurring**          | Has `See Also` links to 2+ similar issues           |
| **Verified**           | Status is `resolved` with working fix               |
| **Non-obvious**        | Required actual debugging/investigation to discover |
| **Broadly applicable** | Not project-specific; useful across codebases       |
| **User-flagged**       | User says "save this as a skill" or similar         |

### Extraction Workflow

1. **Identify candidate**: Learning meets extraction criteria
2. **Run helper** (or create manually):
   ```bash
   ./skills/self-improvement/scripts/extract-skill.sh skill-name --dry-run
   ./skills/self-improvement/scripts/extract-skill.sh skill-name
   ```
3. **Customize SKILL.md**: Fill in template with learning content
4. **Update learning**: Set status to `promoted_to_skill`, add `Skill-Path`
5. **Verify**: Read skill in fresh session to ensure it's self-contained

### Manual Extraction

If you prefer manual creation:

1. Create `skills/<skill-name>/SKILL.md`
2. Use template from `assets/SKILL-TEMPLATE.md`
3. Follow [Agent Skills spec](https://agentskills.io/specification):
    - YAML frontmatter with `name` and `description`
    - Name must match folder name
    - No README.md inside skill folder

### Extraction Detection Triggers

Watch for these signals that a learning should become a skill:

**In conversation:**

- "Save this as a skill"
- "I keep running into this"
- "This would be useful for other projects"
- "Remember this pattern"

**In learning entries:**

- Multiple `See Also` links (recurring issue)
- High priority + resolved status
- Category: `best_practice` with broad applicability
- User feedback praising the solution

### Skill Quality Gates

Before extraction, verify:

- [ ] Solution is tested and working
- [ ] Description is clear without original context
- [ ] Code examples are self-contained
- [ ] No project-specific hardcoded values
- [ ] Follows skill naming conventions (lowercase, hyphens)

## Multi-Agent Support

This skill works across different AI coding agents with agent-specific activation.

### Claude Code

**Activation**: Hooks (UserPromptSubmit, PostToolUse)
**Setup**: `.claude/settings.json` with hook configuration
**Detection**: Automatic via hook scripts

### Codex CLI

**Activation**: Hooks (same pattern as Claude Code)
**Setup**: `.codex/settings.json` with hook configuration
**Detection**: Automatic via hook scripts

### GitHub Copilot

**Activation**: Manual (no hook support)
**Setup**: Add to `.github/copilot-instructions.md`:

```markdown
## Self-Improvement

After solving non-obvious issues, consider logging to `.learnings/`:
1. Use format from self-improvement skill
2. Link related entries with See Also
3. Promote high-value learnings to skills

Ask in chat: "Should I log this as a learning?"
```

**Detection**: Manual review at session end

### braidrun-agent

**Activation**: Hook injection via `SkillManager` at agent bootstrap  
**Setup**: See "braidrun-agent Setup" section above  
**Detection**: Automatic — `SkillManager` discovers hooks from three tiers (skill-bundled → user-global → workspace),
executes `handler.py` if Python 3 is available, and injects the virtual bootstrap file `SELF_IMPROVEMENT_REMINDER.md`
into every session

**All available braidrun-agent events** (37 total):

| Category                  | Events                                                                                                      |
|---------------------------|-------------------------------------------------------------------------------------------------------------|
| Bootstrap                 | `agent:bootstrap`                                                                                           |
| Commands                  | `command`, `command:new`, `command:reset`, `command:stop`                                                   |
| Session                   | `session:compact:before`, `session:compact:after`, `session:start`, `session:end`                           |
| Gateway                   | `gateway:startup`                                                                                           |
| Messages                  | `message`, `message:received`, `message:transcribed`, `message:preprocessed`, `message:sent`                |
| Errors                    | `agent:error`                                                                                               |
| Skills                    | `skill:activated`, `skill:deactivated`                                                                      |
| Shutdown                  | `agent:shutdown`                                                                                            |
| Agent lifecycle (Koog)    | `agent:starting`, `agent:completed`, `agent:closing`                                                        |
| Strategy (Koog)           | `strategy:starting`, `strategy:completed`                                                                   |
| Node execution (Koog)     | `node:execution:starting`, `node:execution:completed`, `node:execution:failed`                              |
| Subgraph execution (Koog) | `subgraph:execution:starting`, `subgraph:execution:completed`, `subgraph:execution:failed`                  |
| LLM calls (Koog)          | `llm:call:starting`, `llm:call:completed`                                                                   |
| LLM streaming (Koog)      | `llm:streaming:starting`, `llm:streaming:completed`, `llm:streaming:failed`, `llm:streaming:frame:received` |
| Tool calls (Koog)         | `tool:call:starting`, `tool:call:completed`, `tool:call:failed`, `tool:validation:failed`                   |

**Events used by this skill** (10):

| Event                       | Behaviour                                                        |
|-----------------------------|------------------------------------------------------------------|
| `agent:bootstrap`           | Injects SELF_IMPROVEMENT_REMINDER.md as a virtual bootstrap file |
| `tool:call:failed`          | Auto-logs tool call failures to `.learnings/ERRORS.md`           |
| `tool:validation:failed`    | Auto-logs tool validation failures to `.learnings/ERRORS.md`     |
| `node:execution:failed`     | Auto-logs node execution failures to `.learnings/ERRORS.md`      |
| `subgraph:execution:failed` | Auto-logs subgraph execution failures to `.learnings/ERRORS.md`  |
| `llm:streaming:failed`      | Auto-logs LLM streaming failures to `.learnings/ERRORS.md`       |
| `agent:error`               | Auto-logs agent-level errors to `.learnings/ERRORS.md`           |
| `agent:completed`           | Reminds to review and capture learnings from the session         |
| `session:start`             | Summarises pending high-priority learnings                       |
| `session:end`               | Reminds to finalise any open learnings                           |

### OpenClaw

**Activation**: Workspace injection + inter-agent messaging
**Setup**: See "OpenClaw Setup" section above
**Detection**: Via session tools and workspace files

### Agent-Agnostic Guidance

Regardless of agent, apply self-improvement when you:

1. **Discover something non-obvious** - solution wasn't immediate
2. **Correct yourself** - initial approach was wrong
3. **Learn project conventions** - discovered undocumented patterns
4. **Hit unexpected errors** - especially if diagnosis was difficult
5. **Find better approaches** - improved on your original solution

### Copilot Chat Integration

For Copilot users, add this to your prompts when relevant:

> After completing this task, evaluate if any learnings should be logged to `.learnings/` using the self-improvement
> skill format.

Or use quick prompts:

- "Log this to learnings"
- "Create a skill from this solution"
- "Check .learnings/ for related issues"
