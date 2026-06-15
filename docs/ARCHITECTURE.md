# Architecture

braidrun-workflow is a single-module Kotlin/JVM library. This document maps
the packages and how a workflow execution flows through them.

## Package map

```
com.fartech.agents
├── workflow      Workflow engine
│   ├── WorkflowModels.kt        typed model of the YAML schema (WorkflowDefinition,
│   │                            WorkflowStep, CodeStepConfig, error handling, …)
│   ├── WorkflowParser.kt        YAML → WorkflowDefinition, with validation
│   ├── WorkflowExecutor.kt      step orchestration: dependencies, conditions,
│   │                            parallelism, iteration, retries, checkpoints
│   ├── WorkflowBatchExecutor.kt batch runs over input sets
│   ├── WorkflowMonitor.kt       execution progress/events
│   └── WorkflowDebug.kt         step-through debugging support
│
├── commons       Agent runtime (on top of Koog)
│   ├── AgentBootstrap.kt        builds an AIAgent from a preset + parameters
│   ├── AgentStrategies.kt       agent loop strategies
│   ├── AgentSkills.kt           skill discovery/loading (SKILL.md convention,
│   │                            project/user/builtin scopes)
│   ├── AgentHooks.kt            lifecycle hook execution
│   ├── PromptExecutorFactory.kt LLM provider selection & client construction
│   ├── AgentDatabase.kt         Mongo/Redis-backed agent state
│   ├── AgentMcpUtils.kt         consuming external MCP servers
│   └── AgentA2A.kt              agent-to-agent (A2A) server/client wiring
│
├── tools         Built-in tool groups (~40 files)
│   ├── file system, shell, HTTP, email, SQL/Mongo/Redis,
│   ├── Office documents (Word/Excel/PowerPoint via POI, PDF via PDFBox),
│   ├── browser automation (Playwright), web scraping (Jsoup),
│   ├── RAG/embeddings, skill tools, …
│   └── exec/                    code-step executors: native subprocess and
│                                Docker-per-step sandbox
│
├── agents        Higher-level agents and presets (OfficeAgent, AnalyzeAgent,
│                 app generators, A2A wrappers)
├── mcp           AgentMcpServer — exposes tool groups as an MCP stdio server
└── utils         small shared helpers

com.fartech.ftapp2.commonsKt   shared infrastructure helpers (HttpAccess,
                               jackson modules, logging sanitizers)
com.fartech.storage            storage abstractions
org.openapitools.vertxweb      generated API models kept for compatibility
```

Bundled resources:

```
src/main/resources/
├── agent-presets/      19 ready-to-use agent presets (universal, researcher,
│                       writer, web_scraper, word_document, …)
├── builtin-skills/     skills shipped inside the jar, discovered via
│                       builtin-skills-index.txt (braidrun-agent-guide:
│                       workflow authoring guide + capability reference)
├── models/             model catalog definitions
└── mcp.json            default MCP server registrations
```

## Execution flow

```
YAML file/string
      │  WorkflowParser.parseFile / parseYaml  (validation errors here)
      ▼
WorkflowDefinition
      │  WorkflowExecutor.execute(workflow, externalExecutionId)
      ▼
step graph (dependsOn) ──► per-step dispatch:
      ├── code:      CodeStepExecutor (native subprocess or Docker sandbox)
      ├── agent:     AgentBootstrap builds a Koog AIAgent from the preset,
      │              runs the agent loop with the step input
      ├── sub_workflow: resolved via WorkflowResolver, executed recursively
      └── state machine / iteration / repeat-until constructs
      │
      ▼
WorkflowExecutionResult (success, stepResults, skippedSteps, errors)
```

Cross-cutting concerns:

- **Variables** — `{{var:name}}` interpolation in step inputs; code steps see
  them as `WF_VAR_*` environment variables. Step outputs can be extracted
  back into variables.
- **Checkpoints** — Koog's Persistence feature snapshots agent state;
  `runFromCheckpoint` resumes interrupted executions.
- **Isolation** — `DirectoryIsolationConfig` gives each execution its own
  working directory with shared skills/cache/history mounts.
- **Observability** — `WorkflowMonitor` events, optional Koog tracing and
  OpenTelemetry (Langfuse/Weave/Datadog) exporters, token accounting.
- **Guardrails** — env allowlists for subprocesses, MCP tool allowlist/rate
  limits, read-only DB mode; see
  [SECURITY_HARDENING.md](SECURITY_HARDENING.md).

## Design notes

- The library has **no executable entry point** — it is embedded by hosts
  (a web platform, a CLI, an MCP server harness). `AgentMcpServer` provides a
  stdio MCP server when a host wires it to a transport.
- LLM provider clients are constructed per-preset through
  `PromptExecutorFactory`, so multiple providers/models can coexist in one
  workflow (different agents on different models).
- Skills follow the cross-client `SKILL.md` convention and are discovered
  from project (`.agents/skills/`, `.claude/skills/`), user
  (`~/.braidrun/skills/`, …) and builtin (classpath) scopes.
