# Security

Use Docker mode for workflows that execute code, shell commands, browser automation, Git operations, or external agents on untrusted input.

Recommended defaults:

- Run production workflows with `subprocess_mode=docker`.
- Set explicit `working_dir` and `output_dir`.
- Avoid placing API keys in workflow YAML.
- Use MCP allowlists when exposing tool groups.
- Keep browser and shell tools out of presets that do not need them.
- Validate workflow YAML before storing or executing it.

Native mode is intended for local development and trusted automation.

## Host policy latches

A multi-tenant or metered host (for example the braidrun web service) embeds the
engine in its own JVM, so some engine defaults that are fine for the CLI would act
on the server itself. `com.fartech.agents.workflow.WorkflowHostPolicy` holds
process-wide switches the host declares once at startup, before it builds any
executor:

```kotlin
WorkflowHostPolicy.requireCodeStepExecutor()
WorkflowHostPolicy.requirePublicLlmEndpoints()
WorkflowHostPolicy.requireExplicitKeysForCustomLlmEndpoints()
WorkflowHostPolicy.requireSingleLlmChoice()
WorkflowHostPolicy.restrictSkillSideEffects()
```

| Latch | Effect once declared |
| --- | --- |
| `requireCodeStepExecutor()` | Every `WorkflowExecutor` without an injected `codeStepExecutor` refuses `code:` steps, including executors built by the agent `workflow` tool or outside any executor. No bare `ProcessBuilder` on the host. |
| `requirePublicLlmEndpoints()` | `LlmEndpointPolicy` checks the effective base URL (user-set or provider default) of every chat client built by `createLLMClient`, the RAG embedder and the multimedia generation tools: https only, and every resolved address must be public. `WEB_TOOLS_ALLOW_PRIVATE_URLS` does not relax it. A violation throws `LlmEndpointNotAllowedException` while the client is built; it is a configuration error, so it is not retried and does not fall back. This refuses LM Studio, Ollama, plain-http proxies and private or loopback hosts. A DNS failure on a provider's default host is left to the request path and its retries. The check does not pin connect-time DNS resolution. |
| `requireExplicitKeysForCustomLlmEndpoints()` | When a base URL differs from the provider default in scheme, host or port, the client only uses keys supplied with the run (`*_api_key` parameters, `llm_provider_keys`), never the host's environment keys (`OPENAI_API_KEY`, ...). A missing key is a configuration error, except for keyless local providers. The same rule covers custom RAG-embedding and multimedia base URLs. It is separate from `requirePublicLlmEndpoints()` so a private deployment that allows internal endpoints still never sends its environment keys to user-chosen hosts. |
| `requireSingleLlmChoice()` | A workflow's `num_choices` is capped at 1. Multi-choice rounds go through Koog's `executeMultipleChoices`, which fires no LLM-call events, so a user-set `num_choices > 1` would switch off token, cost and quota accounting while the provider bills every choice. |
| `restrictSkillSideEffects()` | No skill hook handler script runs, no per-skill MCP server is prepared or started, `SkillAdminTools` (skill download, cache deletion, refresh) are neither registered nor callable, ClawHub installs drop `hooks/` and `mcp-servers/`, and `inspectSkill` / `listCachedSkills` only see skills the agent's own scoped manager loaded. See [Skills](SKILLS.md#hosted-mode). |
| `allowSkillMcpAutoStart()` | The one opt-in: per-skill MCP servers (`<skill>/mcp-servers/`) are off by default everywhere and start only after this call. It has no effect once `restrictSkillSideEffects()` was declared. |

Why latches and not parameters: `ConfigurationParameter`s and workflow YAML are
user-controlled on a hosted service, so a setting a user can write cannot be
what protects the host from that user. The latches are set only by host code,
cannot be turned off again, and apply to every executor, agent and tool registry
in the process, including ones built indirectly (sub-workflows, the agent
`workflow` tool, pooled managers). User settings such as
`skills_config.hookScriptExecutionEnabled` can narrow what a latch allows, never
widen it. Without any latch (CLI and plain library use) behavior is unchanged,
except that per-skill MCP auto-start is off until `allowSkillMcpAutoStart()`.

## Parameters that name host resources

When the engine runs inside a server (braidrun-web runs every agent in its own JVM), workflow parameters are user input. `overrides:` and presets included, they can name things on the host itself. A host declares the following one-way `WorkflowHostPolicy` latches at startup, next to the ones above. They are never ConfigurationParameters, so a workflow cannot turn them off.

```kotlin
WorkflowHostPolicy.requirePublicServiceEndpoints()
WorkflowHostPolicy.refuseStdioMcpServers()
WorkflowHostPolicy.requireTenantScopedStorage()
```

| Parameter | Risk without the latch | Latch | Effect |
|---|---|---|---|
| `tracing_file_path` | Opened with `CREATE` from the host JVM: a workflow can write into any file the server user can | `requireTenantScopedStorage()` | Ignored; traces go to `.workflow-runs/traces/agent-<session_id>.ndjson` |
| `long_term_memory_namespace` | Every LTM store (Mongo or in-memory) is keyed by namespace alone: `ltm:<other user>:…` reads or poisons another tenant's memory | `requireTenantScopedStorage()` | Confined under `ltm:<user_id>:` for the host-injected `user_id`; an agent with no `user_id` gets no LTM |
| `langfuse_url`, `mcp_servers.<name>.url` | Server-side requests to any address (SSRF: metadata service, internal APIs) | `requirePublicServiceEndpoints()` | Must be `https` (`wss` for `type: websocket`) on a public host, or the agent fails to build |
| `cache_policy: redis` + `redis_client_url` | A RESP connection from the server to any host, its own Redis included | `requirePublicServiceEndpoints()` | Falls back to the in-memory prompt cache |
| `mcp_servers.<name>` without `url` (stdio) | `command` runs as a native process in the server JVM, outside `SubprocessExecutor` and Docker | `refuseStdioMcpServers()` | The agent fails to build before any process starts |

Trace files are always opened for append, never truncated, with or without the latch.

The public-endpoint checks resolve DNS when the agent is built. They do not pin the address used at connect time, the same limit `LlmEndpointPolicy` has.

Independent of any latch, a model whose provider is served through OpenRouter (`xai`, `qwen`, `meta`, `mistral`, `perplexity` and unknown provider ids) authenticates only with an OpenRouter key: `openrouter_api_key`, `llm_provider_keys.openrouter` or `OPENROUTER_API_KEY`. A vendor's own key (`MISTRAL_API_KEY`, `llm_provider_keys.mistral`, `cohere_api_key`, …) is never sent to openrouter.ai.

## Run-scoped tool state

Some tools keep live state between calls. The browser tools keep a Playwright
context (pages, cookies, logins) per `contextId`. In a shared host JVM that state
is keyed by run as well, so one run can never address another run's contexts,
whatever `contextId` it passes.

The run comes from `com.fartech.agents.tools.ToolRunScope`, a coroutine-context
element that only host code installs:

- `WorkflowExecutor.execute` runs each execution as its own run (the execution id).
  When `execute` returns (completed, failed or cancelled), that run's browser
  contexts are closed. `sub_workflow` steps share their parent's run. A nested
  execution started by the agent `workflow` tool is a run of its own.
- A host that runs agents outside `WorkflowExecutor` wraps each run in
  `ToolRunScope.withRunScope(id) { ... }`. The braidrun web assistant does this per turn.
- Tool calls outside any run scope get a namespace private to the `BrowserTools`
  instance. A host that shares tool registries between users must therefore
  install a scope.
- Contexts idle for 30 minutes are closed as a backstop
  (`BRAIDRUN_BROWSER_CONTEXT_IDLE_TTL_MINUTES`).

The scope is deliberately not taken from the `execution_id` / `session_id`
parameters: workflow YAML can choose its own `session_id`
(`session_id_strategy: fixed`), and nothing injects `execution_id` outside
`WorkflowExecutor`.

The browser process itself is shared, so its launch options are host settings.
`PLAYWRIGHT_ARGS` and `PLAYWRIGHT_HEADLESS` run parameters are ignored, because
Chromium switches such as `--renderer-cmd-prefix` start arbitrary commands.
Set `PLAYWRIGHT_HEADLESS` in the host environment instead.

A single-user process calls `ToolRunScope.declareSingleUserProcess()` once at
startup; the `braidrun-workflow` CLI does. Every run then shares one scope,
contexts live until the process exits, no idle TTL applies, and run parameters
configure the browser launch again. A multi-tenant host must not call it.
