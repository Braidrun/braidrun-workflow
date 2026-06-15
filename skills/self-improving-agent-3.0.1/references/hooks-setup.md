# Hook Setup Guide

Configure automatic self-improvement triggers for AI coding agents.

## Overview

Hooks enable proactive learning capture by injecting reminders at key moments:

- **UserPromptSubmit**: Reminder after each prompt to evaluate learnings
- **PostToolUse (Bash)**: Error detection when commands fail

## Claude Code Setup

### Option 1: Project-Level Configuration

Create `.claude/settings.json` in your project root:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "./skills/self-improvement/scripts/activator.sh"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "./skills/self-improvement/scripts/error-detector.sh"
          }
        ]
      }
    ]
  }
}
```

### Option 2: User-Level Configuration

Add to `~/.claude/settings.json` for global activation:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "~/.claude/skills/self-improvement/scripts/activator.sh"
          }
        ]
      }
    ]
  }
}
```

### Minimal Setup (Activator Only)

For lower overhead, use only the UserPromptSubmit hook:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "./skills/self-improvement/scripts/activator.sh"
          }
        ]
      }
    ]
  }
}
```

## Codex CLI Setup

Codex uses the same hook system as Claude Code. Create `.codex/settings.json`:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "./skills/self-improvement/scripts/activator.sh"
          }
        ]
      }
    ]
  }
}
```

## GitHub Copilot Setup

Copilot doesn't support hooks directly. Instead, add guidance to `.github/copilot-instructions.md`:

```markdown
## Self-Improvement

After completing tasks that involved:
- Debugging non-obvious issues
- Discovering workarounds
- Learning project-specific patterns
- Resolving unexpected errors

Consider logging the learning to `.learnings/` using the format from the self-improvement skill.

For high-value learnings that would benefit other sessions, consider skill extraction.
```

## Verification

### Test Activator Hook

1. Enable the hook configuration
2. Start a new Claude Code session
3. Send any prompt
4. Verify you see `<self-improvement-reminder>` in the context

### Test Error Detector Hook

1. Enable PostToolUse hook for Bash
2. Run a command that fails: `ls /nonexistent/path`
3. Verify you see `<error-detected>` reminder

### Dry Run Extract Script

```bash
./skills/self-improvement/scripts/extract-skill.sh test-skill --dry-run
```

Expected output shows the skill scaffold that would be created.

## Troubleshooting

### Hook Not Triggering

1. **Check script permissions**: `chmod +x scripts/*.sh`
2. **Verify path**: Use absolute paths or paths relative to project root
3. **Check settings location**: Project vs user-level settings
4. **Restart session**: Hooks are loaded at session start

### Permission Denied

```bash
chmod +x ./skills/self-improvement/scripts/activator.sh
chmod +x ./skills/self-improvement/scripts/error-detector.sh
chmod +x ./skills/self-improvement/scripts/extract-skill.sh
```

### Script Not Found

If using relative paths, ensure you're in the correct directory or use absolute paths:

```json
{
  "command": "/absolute/path/to/skills/self-improvement/scripts/activator.sh"
}
```

### Too Much Overhead

If the activator feels intrusive:

1. **Use minimal setup**: Only UserPromptSubmit, skip PostToolUse
2. **Add matcher filter**: Only trigger for certain prompts:

```json
{
  "matcher": "fix|debug|error|issue",
  "hooks": [...]
}
```

## Hook Output Budget

The activator is designed to be lightweight:

- **Target**: ~50-100 tokens per activation
- **Content**: Structured reminder, not verbose instructions
- **Format**: XML tags for easy parsing

If you need to reduce overhead further, you can edit `activator.sh` to output less text.

## Security Considerations

- Hook scripts run with the same permissions as Claude Code
- Scripts only output text; they don't modify files or run commands
- Error detector reads `CLAUDE_TOOL_OUTPUT` environment variable
- All scripts are opt-in (you must configure them explicitly)

## braidrun-agent Setup

braidrun-agent hooks are configured automatically — the `SkillManager` discovers this skill's `hooks/braidrun-agent/`
directory and loads `HOOK.md` + `handler.py` at startup.

### Events Handled

This skill registers for **10 braidrun-agent events**:

| Event                       | Behaviour                                                       |
|-----------------------------|-----------------------------------------------------------------|
| `agent:bootstrap`           | Injects SELF_IMPROVEMENT_REMINDER.md as virtual bootstrap file  |
| `tool:call:failed`          | Auto-logs tool call failures to `.learnings/ERRORS.md`          |
| `tool:validation:failed`    | Auto-logs tool validation failures to `.learnings/ERRORS.md`    |
| `node:execution:failed`     | Auto-logs node execution failures to `.learnings/ERRORS.md`     |
| `subgraph:execution:failed` | Auto-logs subgraph execution failures to `.learnings/ERRORS.md` |
| `llm:streaming:failed`      | Auto-logs LLM streaming failures to `.learnings/ERRORS.md`      |
| `agent:error`               | Auto-logs agent-level errors to `.learnings/ERRORS.md`          |
| `agent:completed`           | Reminds to review and capture learnings from the session        |
| `session:start`             | Summarises pending high-priority learnings                      |
| `session:end`               | Reminds to finalise any open learnings                          |

### Error Auto-Capture

When a failure event fires, `handler.py` automatically appends a structured error entry to `.learnings/ERRORS.md` using
the standard `ERR-YYYYMMDD-XXX` format. The entry includes the tool name and error message (when available via the
`config.toolName` / `config.error` fields passed by `dispatchHooks`).

### Session Lifecycle

At `session:start`, the handler scans `.learnings/` for pending entries and surfaces high/critical priority items in the
operator console. At `session:end` and `agent:completed`, it reminds the agent to finalise open learnings.

### Verification

1. Place the skill in your skills directory (e.g. `~/.braidrun/skills-cache/self-improving-agent-3.0.1/`)
2. Start a braidrun-agent session
3. Verify the bootstrap message: `🧠 Self-improvement hook active on <agent-name>`
4. Trigger a tool failure (e.g. use a non-existent tool) and check `.learnings/ERRORS.md` for auto-logged entry
5. Check session start messages for pending learnings count

### Disabling for braidrun-agent

Set `hooks.enabled: false` in your agent config, or remove the skill from the skills directory.

## Disabling Hooks

To temporarily disable without removing configuration:

1. **Comment out in settings**:

```json
{
  "hooks": {
    // "UserPromptSubmit": [...]
  }
}
```

2. **Or delete the settings file**: Hooks won't run without configuration
