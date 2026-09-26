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
