# Architecture

Braidrun Workflow has four runtime layers:

1. YAML parsing and validation via `WorkflowParser`.
2. Execution orchestration via `WorkflowExecutor`.
3. Agent construction from `AgentDefinition` and `AgentPresetRegistry`.
4. Tool registration through named tool groups and MCP-compatible tool surfaces.

Workflow YAML resolves presets into runtime parameters, and the executor builds a Koog-backed agent from those parameters.

Code, shell, Git, browser, Claude Code, and Codex subprocesses run through the shared subprocess executor. Use `subprocess_mode=native` for local development and `subprocess_mode=docker` for production-style isolation.
