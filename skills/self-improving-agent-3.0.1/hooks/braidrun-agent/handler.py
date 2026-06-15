#!/usr/bin/env python3
"""
Self-Improvement Hook — braidrun-agent handler script
=====================================================
Fires on multiple events to provide continuous self-improvement capabilities:

- ``agent:bootstrap``          — injects reminder as virtual bootstrap file
- ``tool:call:failed``         — auto-logs tool call failures to ERRORS.md
- ``tool:validation:failed``   — auto-logs tool validation failures to ERRORS.md
- ``node:execution:failed``    — auto-logs node execution failures to ERRORS.md
- ``subgraph:execution:failed``— auto-logs subgraph execution failures to ERRORS.md
- ``llm:streaming:failed``     — auto-logs LLM streaming failures to ERRORS.md
- ``agent:error``              — auto-logs agent-level errors to ERRORS.md
- ``agent:completed``          — reminds to review and capture learnings
- ``session:start``            — loads and summarises pending learnings
- ``session:end``              — reminds to finalise any open learnings

Protocol
--------
- Reads a JSON context object from **stdin**.
- Writes a JSON result object to **stdout**.

Context fields (stdin JSON)
~~~~~~~~~~~~~~~~~~~~~~~~~~~
  event        : str            — event type, e.g. "agent:bootstrap"
  sessionKey   : str            — session identifier (empty when unavailable)
  workspaceDir : str            — absolute path to the current workspace
  skillName    : str            — name of the skill that owns this hook
  config       : dict[str, str] — agent configuration key-value pairs including
                                   any additionalConfig from the event dispatcher,
                                   e.g. {"toolName": "Bash", "error": "..."}

Result fields (stdout JSON)
~~~~~~~~~~~~~~~~~~~~~~~~~~~
  injectContent : str                      — plain text appended to system prompt
  virtualFiles  : list[{path, content}]    — virtual files injected as bootstrap files
  messages      : list[str]                — informational messages printed to operator console

This handler mirrors the behaviour of the OpenClaw ``handler.ts`` counterpart
and extends it with error-detection and learning-review capabilities.
"""

import json
import os
import sys
from datetime import datetime, timezone

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

REMINDER_CONTENT = """## Self-Improvement Reminder

After completing tasks, evaluate if any learnings should be captured:

**Log when:**
- User corrects you → `.learnings/LEARNINGS.md`
- Command/operation fails → `.learnings/ERRORS.md`
- User wants missing capability → `.learnings/FEATURE_REQUESTS.md`
- You discover your knowledge was wrong → `.learnings/LEARNINGS.md`
- You find a better approach → `.learnings/LEARNINGS.md`

**Promote when pattern is proven:**
- Behavioral patterns → `SOUL.md`
- Workflow improvements → `AGENTS.md`
- Tool gotchas → `TOOLS.md`

Keep entries simple: date, title, what happened, what to do differently."""

ERRORS_FILE = ".learnings/ERRORS.md"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _empty_result():
    """Return an empty JSON result."""
    return json.dumps({})


def _now_iso():
    """ISO-8601 timestamp."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _today():
    """YYYYMMDD date string."""
    return datetime.now(timezone.utc).strftime("%Y%m%d")


def _short_id():
    """3-character hex suffix for entry IDs."""
    import random
    return f"{random.randint(0, 0xFFF):03X}"


def _count_pending(workspace_dir: str) -> int:
    """Count pending entries across all .learnings/ files."""
    learnings_dir = os.path.join(workspace_dir, ".learnings")
    if not os.path.isdir(learnings_dir):
        return 0
    count = 0
    for fname in os.listdir(learnings_dir):
        if not fname.endswith(".md"):
            continue
        try:
            with open(os.path.join(learnings_dir, fname), "r") as f:
                for line in f:
                    if "**Status**: pending" in line:
                        count += 1
        except OSError:
            pass
    return count


def _read_high_priority_pending(workspace_dir: str, max_items: int = 5) -> list:
    """Read high/critical priority pending entries as summary lines."""
    learnings_dir = os.path.join(workspace_dir, ".learnings")
    if not os.path.isdir(learnings_dir):
        return []
    items = []
    for fname in sorted(os.listdir(learnings_dir)):
        if not fname.endswith(".md"):
            continue
        try:
            with open(os.path.join(learnings_dir, fname), "r") as f:
                content = f.read()
        except OSError:
            continue
        # Split into entries by "## [" headers
        entries = content.split("\n## [")
        for entry in entries:
            if "**Status**: pending" not in entry:
                continue
            if "**Priority**: high" not in entry and "**Priority**: critical" not in entry:
                continue
            # Extract the ID/title line
            lines = entry.strip().split("\n")
            title = lines[0] if lines else ""
            if not title.startswith("["):
                title = "[" + title
            # Truncate long titles
            if len(title) > 80:
                title = title[:77] + "..."
            items.append(f"- {title}")
            if len(items) >= max_items:
                return items
    return items


def _append_error_entry(workspace_dir: str, summary: str, error_detail: str, area: str = "backend") -> bool:
    """Append an error entry to .learnings/ERRORS.md. Returns True on success."""
    learnings_dir = os.path.join(workspace_dir, ".learnings")
    errors_path = os.path.join(workspace_dir, ERRORS_FILE)
    try:
        os.makedirs(learnings_dir, exist_ok=True)
        entry_id = f"ERR-{_today()}-{_short_id()}"
        entry = f"""## [{entry_id}] {summary}

**Logged**: {_now_iso()}
**Priority**: high
**Status**: pending
**Area**: {area}

### Summary
{summary}

### Error
```
{error_detail}
```

### Context
- Auto-captured by self-improvement hook

### Metadata
- Reproducible: unknown
- Source: hook_auto_capture

---
"""
        with open(errors_path, "a") as f:
            f.write("\n" + entry)
        return True
    except OSError:
        return False


# ---------------------------------------------------------------------------
# Event handlers
# ---------------------------------------------------------------------------


def handle_bootstrap(ctx: dict) -> str:
    """Inject the self-improvement reminder as a virtual bootstrap file."""
    config = ctx.get("config", {})
    model = config.get("model", "")
    agent_name = config.get("agent_name", "")
    label = agent_name or "braidrun-agent"
    msg_parts = [f"🧠 Self-improvement hook active on {label}"]
    if model:
        msg_parts.append(f"(model: {model})")

    return json.dumps({
        "injectContent": None,
        "virtualFiles": [
            {
                "path": "SELF_IMPROVEMENT_REMINDER.md",
                "content": REMINDER_CONTENT,
            }
        ],
        "messages": [" ".join(msg_parts)],
    })


def handle_tool_call_failed(ctx: dict) -> str:
    """Auto-log tool call failures."""
    config = ctx.get("config", {})
    tool_name = config.get("toolName", "unknown")
    error = config.get("error", "unknown error")
    workspace = ctx.get("workspaceDir", "")

    summary = f"Tool call failed: {tool_name}"
    logged = False
    if workspace:
        logged = _append_error_entry(workspace, summary, f"Tool: {tool_name}\nError: {error}")

    messages = [f"🧠 Tool failure detected: {tool_name}"]
    if logged:
        messages.append(f"  → Auto-logged to {ERRORS_FILE}")

    return json.dumps({"messages": messages})


def handle_tool_validation_failed(ctx: dict) -> str:
    """Auto-log tool validation failures."""
    config = ctx.get("config", {})
    tool_name = config.get("toolName", "unknown")
    error = config.get("error", "unknown error")
    workspace = ctx.get("workspaceDir", "")

    summary = f"Tool validation failed: {tool_name}"
    logged = False
    if workspace:
        logged = _append_error_entry(workspace, summary, f"Tool: {tool_name}\nValidation Error: {error}")

    messages = [f"🧠 Tool validation failure: {tool_name}"]
    if logged:
        messages.append(f"  → Auto-logged to {ERRORS_FILE}")

    return json.dumps({"messages": messages})


def handle_node_execution_failed(ctx: dict) -> str:
    """Auto-log node execution failures."""
    workspace = ctx.get("workspaceDir", "")

    summary = "Graph node execution failed"
    logged = False
    if workspace:
        logged = _append_error_entry(workspace, summary, "A graph node failed during execution.", area="infra")

    messages = ["🧠 Node execution failure detected"]
    if logged:
        messages.append(f"  → Auto-logged to {ERRORS_FILE}")

    return json.dumps({"messages": messages})


def handle_subgraph_execution_failed(ctx: dict) -> str:
    """Auto-log subgraph execution failures."""
    workspace = ctx.get("workspaceDir", "")

    summary = "Subgraph execution failed"
    logged = False
    if workspace:
        logged = _append_error_entry(workspace, summary, "A subgraph failed during execution.", area="infra")

    messages = ["🧠 Subgraph execution failure detected"]
    if logged:
        messages.append(f"  → Auto-logged to {ERRORS_FILE}")

    return json.dumps({"messages": messages})


def handle_llm_streaming_failed(ctx: dict) -> str:
    """Auto-log LLM streaming failures."""
    workspace = ctx.get("workspaceDir", "")

    summary = "LLM streaming failed"
    logged = False
    if workspace:
        logged = _append_error_entry(workspace, summary, "LLM streaming encountered an error.", area="infra")

    messages = ["🧠 LLM streaming failure detected"]
    if logged:
        messages.append(f"  → Auto-logged to {ERRORS_FILE}")

    return json.dumps({"messages": messages})


def handle_agent_error(ctx: dict) -> str:
    """Auto-log agent-level errors."""
    workspace = ctx.get("workspaceDir", "")

    summary = "Agent encountered an unrecoverable error"
    logged = False
    if workspace:
        logged = _append_error_entry(workspace, summary, "The agent reported an unrecoverable error.", area="infra")

    messages = ["🧠 Agent error detected"]
    if logged:
        messages.append(f"  → Auto-logged to {ERRORS_FILE}")

    return json.dumps({"messages": messages})


def handle_agent_completed(ctx: dict) -> str:
    """Remind to review learnings when agent completes."""
    workspace = ctx.get("workspaceDir", "")
    pending = _count_pending(workspace) if workspace else 0

    messages = ["🧠 Agent completed — evaluate if learnings emerged from this session"]
    if pending > 0:
        messages.append(f"  📋 {pending} pending learning(s) in .learnings/ — consider reviewing")

    return json.dumps({"messages": messages})


def handle_session_start(ctx: dict) -> str:
    """At session start, summarise pending high-priority learnings."""
    workspace = ctx.get("workspaceDir", "")
    if not workspace:
        return _empty_result()

    pending = _count_pending(workspace)
    if pending == 0:
        return json.dumps({
            "messages": ["🧠 Session started — no pending learnings"]
        })

    high_items = _read_high_priority_pending(workspace)
    messages = [f"🧠 Session started — {pending} pending learning(s) in .learnings/"]
    if high_items:
        messages.append("  ⚠️ High/critical priority items:")
        messages.extend(f"    {item}" for item in high_items)

    return json.dumps({"messages": messages})


def handle_session_end(ctx: dict) -> str:
    """At session end, remind to finalise open learnings."""
    workspace = ctx.get("workspaceDir", "")
    pending = _count_pending(workspace) if workspace else 0

    messages = ["🧠 Session ending — finalise any open learnings"]
    if pending > 0:
        messages.append(f"  📋 {pending} pending learning(s) remain in .learnings/")

    return json.dumps({"messages": messages})


# ---------------------------------------------------------------------------
# Event dispatch table
# ---------------------------------------------------------------------------

EVENT_HANDLERS = {
    "agent:bootstrap": handle_bootstrap,
    "tool:call:failed": handle_tool_call_failed,
    "tool:validation:failed": handle_tool_validation_failed,
    "node:execution:failed": handle_node_execution_failed,
    "subgraph:execution:failed": handle_subgraph_execution_failed,
    "llm:streaming:failed": handle_llm_streaming_failed,
    "agent:error": handle_agent_error,
    "agent:completed": handle_agent_completed,
    "session:start": handle_session_start,
    "session:end": handle_session_end,
}


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    # Read context from stdin (sent by BraidrunHookExecutor as JSON)
    try:
        ctx = json.load(sys.stdin)
    except Exception:
        # If stdin is not valid JSON, emit empty result and exit cleanly
        print(_empty_result())
        return

    event: str = ctx.get("event", "")
    session_key: str = ctx.get("sessionKey", "")

    # Skip sub-agent sessions (session key contains ":subagent:")
    if ":subagent:" in session_key:
        print(_empty_result())
        return

    # Dispatch to appropriate handler
    handler = EVENT_HANDLERS.get(event)
    if handler:
        print(handler(ctx))
    else:
        print(_empty_result())


if __name__ == "__main__":
    main()
