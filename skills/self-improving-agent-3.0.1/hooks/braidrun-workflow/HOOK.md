---
name: self-improvement
description: "Injects self-improvement reminder at bootstrap; auto-detects errors and captures learnings on tool/node/agent failures and session lifecycle events"
emoji: "🧠"
homepage: "https://github.com/fartech/braidrun-workflow"
metadata: {"braidrun-workflow":{"events":["agent:bootstrap","tool:call:failed","tool:validation:failed","node:execution:failed","subgraph:execution:failed","llm:streaming:failed","agent:error","agent:completed","session:start","session:end"],"virtualFilePath":"SELF_IMPROVEMENT_REMINDER.md"}}
---

## Self-Improvement Reminder

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

Keep entries simple: date, title, what happened, what to do differently.
